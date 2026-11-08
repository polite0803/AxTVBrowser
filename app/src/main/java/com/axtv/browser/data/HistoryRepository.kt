package com.axtv.browser.data

import androidx.core.net.toUri
import com.axtv.browser.activity.main.AddressSuggestions
import com.axtv.browser.activity.main.HistoryNavigator
import com.axtv.browser.browser.VisitedHistoryLogger
import com.axtv.browser.model.HistoryItem
import com.axtv.browser.model.dao.HistoryDao
import java.util.Calendar
import java.util.Locale

/** A site of the history: its host, [visits] of its pages and when one of them was visited [lastVisit]. */
data class SiteVisits(val host: String, val visits: Int, val lastVisit: Long)

/**
 * The browsing history.
 */
class HistoryRepository(private val dao: HistoryDao) : VisitedHistoryLogger.Store, HistoryNavigator.Store,
    AddressSuggestions.Store {
    companion object {
        private const val MAX_ITEMS = 5000
        private const val KEPT_MONTHS_WHEN_TRIMMED = 3
    }

    /**
     * Deletes the items older than a few months when there are too many of them.
     */
    suspend fun trim() {
        if (dao.count() <= MAX_ITEMS) return
        val c = Calendar.getInstance()
        c.add(Calendar.MONTH, -KEPT_MONTHS_WHEN_TRIMMED)
        dao.deleteWhereTimeLessThan(c.time.time)
    }

    suspend fun last(limit: Int): List<HistoryItem> = dao.last(limit)

    suspend fun mostVisited(): List<HistoryItem> = dao.frequentlyUsedUrls()

    /**
     * The whole history, the latest items first. [trim] keeps it small enough to be searched in memory
     * (SQLite's LIKE folds the case of ASCII letters only).
     */
    override suspend fun all(): List<HistoryItem> = dao.allByTime()

    override suspend fun delete(items: List<HistoryItem>) = dao.delete(*items.toTypedArray())

    override suspend fun deleteAll() = dao.deleteWhereTimeLessThan(Long.MAX_VALUE)

    /**
     * Deletes the visits from [time] on and gives back how many items went; [time] is now minus the length of the
     * chosen period, 0 for all of the history.
     */
    suspend fun deleteSince(time: Long): Int = dao.deleteWhereTimeGreaterOrEqual(time)

    /**
     * The sites visited from [time] on, the most recently visited first, with their visits. The host of an
     * address is taken here, because SQLite has no way of cutting it out; the built-in home page and the other
     * pages of the app have no host and are not sites.
     */
    suspend fun sitesVisitedSince(time: Long): List<SiteVisits> {
        //the visits come with the latest one first, so the first row of a host gives both its date and its place
        val byHost = LinkedHashMap<String, SiteVisits>()
        for (page in dao.pagesVisitedSince(time)) {
            val host = page.url.toUri().host?.lowercase(Locale.ROOT)?.ifEmpty { null } ?: continue
            val seen = byHost[host]
            byHost[host] = if (seen == null) SiteVisits(host, 1, page.time)
            else seen.copy(visits = seen.visits + 1)
        }
        return byHost.values.toList()
    }

    override suspend fun insert(item: HistoryItem): Long = dao.insert(item)

    override suspend fun updateTitle(id: Long, title: String) = dao.updateTitle(id, title)
}
