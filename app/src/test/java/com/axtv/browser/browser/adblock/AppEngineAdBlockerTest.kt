package com.axtv.browser.browser.adblock

import android.net.Uri
import androidx.room.Room
import com.axtv.browser.AppContext
import com.axtv.browser.Config
import com.axtv.browser.TVBro
import com.axtv.browser.adblock.psl.PublicSuffixList
import com.axtv.browser.singleton.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppEngineAdBlockerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var blocker: AppEngineAdBlocker
    private val config get() = AppContext.provideConfig()

    private val psl by lazy {
        PublicSuffixList.load(RuntimeEnvironment.getApplication().assets.open("adblock/psl.txt").use { it.readBytes() })
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        AppDatabase.setInstanceForTests(db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fetcher = AdBlockServiceTest.FakeFetcher()
        fetcher.lists[FilterListCatalog.find(FilterListCatalog.EASYLIST)!!.url] = listOf(
            "||ads.example.net^", "||img.example.net^\$image", "*\$ping,third-party", "||bad.example^\$document",
            "site.com##.site-ad", "##.ad-box", "##[data-ad]",
        ).joinToString("\n")
        fetcher.lists[FilterListCatalog.find(FilterListCatalog.EASYPRIVACY)!!.url] = "! none\n"
        val service = AdBlockService(File(temp.root, "adblock"), scope, config, { psl }, fetcher)
        runBlocking {
            service.initialize()
            service.update(force = false)
        }
        blocker = AppEngineAdBlocker(service, TVBro.container.data.hostConfigs, config)
        config.adBlockEnabled = true
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppDatabase.setInstanceForTests(null)
        db.close()
    }

    private fun webView(url: String, accept: String? = "*/*", page: String = "https://site.com/", mainFrame: Boolean = false) =
        blocker.shouldBlock(Uri.parse(url), null, accept, mainFrame, Uri.parse(page), Uri.parse(page))

    private fun gecko(url: String, type: String, document: String = "https://site.com/", top: String = document) =
        blocker.shouldBlock(Uri.parse(url), type, null, type == "main_frame", Uri.parse(document), Uri.parse(top))

    @Test
    fun blocksAds() {
        assertTrue(webView("https://ads.example.net/a.js"))
        assertTrue(gecko("https://ads.example.net/a", "script"))
        assertFalse(webView("https://cdn.example.org/a.js"))
    }

    @Test
    fun theSiteSettingOfTheTabsPageApplies() {
        runBlocking { TVBro.container.data.hostConfigs.setAdBlock("site.com", false) }
        assertFalse(webView("https://ads.example.net/a.js"))
        //a frame of another site on that page: the page of the tab decides
        assertFalse(gecko("https://ads.example.net/a", "script", document = "https://frame.com/", top = "https://site.com/"))
        assertTrue(gecko("https://ads.example.net/a", "script", document = "https://site.com/", top = "https://other.com/"))
    }

    @Test
    fun framesWithoutAnAddressAreOfTheTabsPage() {
        //an ad frame (about:blank) of site.com loads a third-party script
        assertTrue(gecko("https://ping.example.org/collect", "ping", document = "about:blank", top = "https://site.com/"))
        assertTrue(gecko("https://ping.example.org/collect", "ping", document = "about:srcdoc", top = "https://site.com/"))
        //the same site is not third-party
        assertFalse(gecko("https://cdn.site.com/collect", "ping", document = "about:blank", top = "https://site.com/"))
    }

    @Test
    fun hidingOfThePageAndItsFrames() {
        val page = blocker.hidingStyles(Uri.parse("https://site.com/news"), Uri.parse("https://site.com/news"))
        assertTrue(page.css.contains(".site-ad") && page.css.contains("[data-ad]"))
        assertTrue(page.generic)
        assertTrue(blocker.genericHidingStyles(Uri.parse("https://site.com/"), null, listOf("ad-box"), emptyList())
            .contains(".ad-box"))
        //a frame of another site gets the selectors of its own site
        assertFalse(blocker.hidingStyles(Uri.parse("https://frame.org/"), Uri.parse("https://site.com/")).css.contains(".site-ad"))
        //a frame of about:blank those of the tab's page
        assertTrue(blocker.hidingStyles(Uri.parse("about:blank"), Uri.parse("https://site.com/")).css.contains(".site-ad"))
    }

    @Test
    fun noHidingWhereAdsAreShown() {
        runBlocking { TVBro.container.data.hostConfigs.setAdBlock("site.com", false) }
        assertEquals("", blocker.hidingStyles(Uri.parse("https://site.com/"), Uri.parse("https://site.com/")).css)
        assertEquals("", blocker.genericHidingStyles(Uri.parse("https://site.com/"), Uri.parse("https://site.com/"),
            listOf("ad-box"), emptyList()))
        //a frame on such a page too
        assertEquals("", blocker.hidingStyles(Uri.parse("https://frame.org/"), Uri.parse("https://site.com/")).css)
        assertEquals("", blocker.hidingStyles(Uri.parse(Config.HOME_PAGE_URL), Uri.parse(Config.HOME_PAGE_URL)).css)
    }

    @Test
    fun theGlobalSetting() {
        config.adBlockEnabled = false
        assertFalse(webView("https://ads.example.net/a.js"))
    }

    @Test
    fun nothingIsBlockedOnTheHomePage() {
        assertFalse(webView("https://ads.example.net/a.js", page = Config.HOME_PAGE_URL))
    }

    @Test
    fun webViewTypesComeFromTheAcceptHeader() {
        assertTrue(webView("https://img.example.net/x", accept = "image/avif,image/webp,image/apng,*/*;q=0.8"))
        assertFalse(webView("https://img.example.net/x", accept = "*/*"))
        assertTrue(gecko("https://img.example.net/x", "image"))
        assertFalse(gecko("https://img.example.net/x", "script"))
    }

    @Test
    fun pingFiltersDoNotBlockScriptsWithoutAnExtension() {
        assertFalse(webView("https://cdn.other.net/loader?id=1"))
        assertTrue(gecko("https://cdn.other.net/collect?id=1", "ping"))
    }

    @Test
    fun pagesAreBlockedOnlyByDocumentFilters() {
        assertTrue(webView("https://bad.example/", accept = "text/html", page = "https://bad.example/", mainFrame = true))
        assertTrue(gecko("https://bad.example/", "main_frame", document = "https://bad.example/"))
        assertFalse(webView("https://ads.example.net/", accept = "text/html", page = "https://ads.example.net/", mainFrame = true))
        assertTrue(blocker.blocksPages)
    }

    @Test
    fun requestsWithoutAnEngineAreCounted() {
        //no list downloaded: the pages would load with nothing checked
        val empty = AdBlockService(File(temp.root, "empty"), scope, config, { psl }, AdBlockServiceTest.FakeFetcher())
        runBlocking { empty.initialize() }
        assertNull(empty.engine)
        val pending = AppEngineAdBlocker(empty, TVBro.container.data.hostConfigs, config)
        assertFalse(pending.shouldBlock(Uri.parse("https://ads.example.net/a.js"), null, "*/*", false,
            Uri.parse("https://site.com/"), Uri.parse("https://site.com/")))
        assertEquals(1, empty.notReadyRequestCount)
        //a page that shows ads asks nothing, and so does a browser without blocking
        runBlocking { TVBro.container.data.hostConfigs.setAdBlock("off.com", false) }
        assertFalse(pending.shouldBlock(Uri.parse("https://ads.example.net/a.js"), null, "*/*", false,
            Uri.parse("https://off.com/"), Uri.parse("https://off.com/")))
        config.adBlockEnabled = false
        assertFalse(pending.shouldBlock(Uri.parse("https://ads.example.net/a.js"), null, "*/*", false,
            Uri.parse("https://other.com/"), Uri.parse("https://other.com/")))
        assertEquals(1, empty.notReadyRequestCount)
    }
}
