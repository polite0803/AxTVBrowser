package com.axtv.browser.browser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The lists of the asset `adblock/lists.json`, as the app reads them */
@RunWith(RobolectricTestRunner::class)
class FilterListCatalogTest {
    @Test
    fun theListsOfTheAssetAreWellFormed() {
        val lists = FilterListCatalog.lists
        assertTrue("the catalog has ${lists.size} lists", lists.size > 10)
        assertEquals(lists.size, lists.map { it.id }.distinct().size)
        for (list in lists) {
            assertTrue(list.id, list.title.isNotBlank() && list.url.startsWith("https://"))
        }
        //a language has only one regional list: the default of the system language would otherwise be a coin toss
        val regional = lists.filter { it.isRegional }.flatMap { list -> list.languages.map { it to list.id } }
        assertEquals(regional.map { it.first }.size, regional.map { it.first }.distinct().size)
    }

    @Test
    fun theCoreListsAreThereWithTheirAddresses() {
        assertEquals("https://easylist.to/easylist/easylist.txt", FilterListCatalog.find(FilterListCatalog.EASYLIST)?.url)
        assertEquals("https://easylist.to/easylist/easyprivacy.txt",
            FilterListCatalog.find(FilterListCatalog.EASYPRIVACY)?.url)
        assertNotNull(FilterListCatalog.find("ruadlist"))
        assertTrue(FilterListCatalog.find("ruadlist")!!.isRegional)
    }

    @Test
    fun theDefaultListsFollowTheLanguage() {
        val core = setOf(FilterListCatalog.EASYLIST, FilterListCatalog.EASYPRIVACY)
        assertEquals(core, FilterListCatalog.defaults("en"))
        assertEquals(core + "ruadlist", FilterListCatalog.defaults("ru"))
        assertEquals(core + "easylistchina", FilterListCatalog.defaults("zh"))
        assertEquals(core + "indianlist", FilterListCatalog.defaults("ta"))
    }
}
