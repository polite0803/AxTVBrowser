package com.axtv.browser.webengine

import android.content.Context

/**
 * An engine that can remove the data of one site. The browser core uses it for "Clear site data" of a site and
 * for the privacy screen, and offers both only for an engine that has this ([WebEngineFactory.siteDataClearing]).
 */
interface SiteDataClearingSupport {
    /**
     * Removes the cookies (the user is signed out), the stored data (local storage, IndexedDB and the like)
     * and the cache of the site of [host]. The browser keeps the settings and the history of the site.
     */
    suspend fun clearSiteData(context: Context, host: String)
}
