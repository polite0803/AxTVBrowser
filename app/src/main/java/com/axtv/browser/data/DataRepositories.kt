package com.axtv.browser.data

import com.axtv.browser.singleton.AppDatabase
import com.axtv.browser.sync.SyncKeyStore

/**
 * Repositories of one database: the app and the UI get the stored data only through them.
 */
class DataRepositories(val database: AppDatabase) {
    val hostConfigs = HostConfigRepository(database.hostsDao())
    val tabs = TabsRepository(database.tabsDao())
    val history = HistoryRepository(database.historyDao())
    val favorites = FavoritesRepository(database.favoritesDao())
    val downloads = DownloadsRepository(database.downloadDao())
    val pairedDevices = PairedDevicesRepository(database.pairedDevicesDao()) { SyncKeyStore.instance }
}
