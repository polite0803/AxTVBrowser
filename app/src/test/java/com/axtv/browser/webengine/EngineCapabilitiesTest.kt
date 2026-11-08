package com.axtv.browser.webengine

import android.content.Context
import com.axtv.browser.browser.FakeWebEngine
import com.axtv.browser.net.ProxySettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What an engine can do beyond [WebEngine]: the app asks for the object that does the work, so an engine that
 * says it can and one that does it are the same thing.
 */
@RunWith(RobolectricTestRunner::class)
class EngineCapabilitiesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun anEngineThatDeclaresNoCapabilitiesGivesNothing() {
        WebEngineFactory.initializeForTests(WebEngineProvider("Plain",
            object : WebEngineProviderCallback by FakeWebEngine.provider.callback {
                override val proxy: ProxySupport? = null
                override val siteDataClearing: SiteDataClearingSupport? = null
            }))
        assertNull(WebEngineFactory.proxy)
        assertNull(WebEngineFactory.siteDataClearing)
        //a changed proxy is not applied to an engine that takes none: asking it would be an empty success
        runBlocking { WebEngineFactory.applyProxy(ProxySettings(ProxySettings.Type.HTTP, "p", 8080)) }
        WebEngineFactory.initializeForTests(FakeWebEngine.provider)
    }

    @Test
    fun theCapabilityTheEngineDeclaresIsTheOneTheAppUses() {
        var applied: ProxySettings? = null
        var cleared: String? = null
        WebEngineFactory.initializeForTests(WebEngineProvider("Capable",
            object : WebEngineProviderCallback by FakeWebEngine.provider.callback {
                override val proxy: ProxySupport? = object : ProxySupport {
                    override suspend fun applyProxy(proxy: ProxySettings?) {
                        applied = proxy
                    }
                }

                override val siteDataClearing: SiteDataClearingSupport? = object : SiteDataClearingSupport {
                    override suspend fun clearSiteData(context: Context, host: String) {
                        cleared = host
                    }
                }
            }))
        val proxy = WebEngineFactory.proxy
        val clearing = WebEngineFactory.siteDataClearing
        assertNotNull(proxy)
        assertNotNull(clearing)
        val to = ProxySettings(ProxySettings.Type.SOCKS5, "p", 1080)
        runBlocking {
            proxy?.applyProxy(to)
            clearing?.clearSiteData(context, "www.a.com")
        }
        assertEquals(to, applied)
        assertEquals("www.a.com", cleared)
        WebEngineFactory.initializeForTests(FakeWebEngine.provider)
    }
}
