package com.axtv.browser.data

import androidx.room.Room
import com.axtv.browser.model.FavoriteItem
import com.axtv.browser.model.HistoryItem
import com.axtv.browser.model.TabEntity
import com.axtv.browser.singleton.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
class DataRepositoriesTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val data = DataRepositories(db)

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun savedSelectedTabUnselectsTheOthers() = runBlocking {
        val a = data.tabs.save(TabEntity(url = "https://a.com/", selected = true, position = 0))
        val b = data.tabs.save(TabEntity(url = "https://b.com/", selected = true, position = 1))
        assertEquals(mapOf(a to false, b to true), data.tabs.load(incognito = false).associate { it.id to it.selected })

        //an update keeps the id
        assertEquals(a, data.tabs.save(TabEntity(id = a, url = "https://a2.com/", selected = true, position = 0)))
        assertEquals(mapOf(a to true, b to false), data.tabs.load(incognito = false).associate { it.id to it.selected })
    }

    @Test
    fun tabSelectionIsKeptPerMode() = runBlocking {
        val incognito = data.tabs.save(TabEntity(url = "https://i.com/", selected = true, incognito = true))
        data.tabs.save(TabEntity(url = "https://a.com/", selected = true))
        assertEquals(true, data.tabs.load(incognito = true).single { it.id == incognito }.selected)
    }

    @Test
    fun savedBookmarkGetsItsId() = runBlocking {
        val item = FavoriteItem().apply { title = "A"; url = "https://a.com/" }
        data.favorites.save(item)
        assertNotEquals(0L, item.id)
        item.title = "A2"
        data.favorites.save(item)
        assertEquals(listOf("A2"), data.favorites.all().map { it.title })
    }

    @Test
    fun historyIsTrimmedOnlyWhenTooLong() = runBlocking {
        val old = Calendar.getInstance().apply { add(Calendar.YEAR, -1) }.timeInMillis
        fun item(time: Long) = HistoryItem().apply { url = "https://a.com/"; title = ""; this.time = time }
        data.history.insert(item(old))
        data.history.trim()
        assertEquals(1, data.history.last(10).size)

        repeat(5000) { data.history.insert(item(System.currentTimeMillis())) }
        data.history.trim()
        //the old item went, the recent ones stay
        assertEquals(5000, db.historyDao().count())
        assertEquals(0, data.history.all().count { it.time == old })
    }

    @Test
    fun historyGroupsItsVisitsBySite() = runBlocking {
        fun visit(url: String, time: Long) = HistoryItem().apply { this.url = url; title = ""; this.time = time }
        data.history.insert(visit("https://a.com/", 100))
        data.history.insert(visit("https://A.COM/page", 200))
        data.history.insert(visit("https://b.com/", 150))
        //a page of the app itself has no host and is not a site
        data.history.insert(visit("about:home", 300))

        val sites = data.history.sitesVisitedSince(0)
        //the site of the latest visit first, with all of its visits and that visit of its own
        assertEquals(listOf("a.com", "b.com"), sites.map { it.host })
        assertEquals(2, sites.first().visits)
        assertEquals(200L, sites.first().lastVisit)
        assertEquals(listOf("a.com"), data.history.sitesVisitedSince(180).map { it.host })

        assertEquals(2, data.history.deleteSince(180))
        assertEquals(listOf(100L, 150L), data.history.all().map { it.time }.sorted())
        assertEquals(2, data.history.deleteSince(0))
        assertTrue(data.history.all().isEmpty())
    }
}
