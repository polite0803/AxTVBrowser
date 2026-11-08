package com.axtv.browser.activity.main

import android.content.Intent
import android.media.tv.TvContract
import android.util.Log
import android.view.LayoutInflater
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.axtv.browser.AppContext
import com.axtv.browser.BuildConfig
import com.axtv.browser.Config
import com.axtv.browser.R
import com.axtv.browser.TVBro
import com.axtv.browser.activity.main.dialogs.ShortcutKeyDialog
import com.axtv.browser.browser.BrowserController
import com.axtv.browser.browser.ProxySetup
import com.axtv.browser.channels.ChannelKind
import com.axtv.browser.databinding.DialogNoticeBinding
import com.axtv.browser.channels.HomeChannels
import com.axtv.browser.data.FavoritesRepository
import com.axtv.browser.data.SiteVisits
import com.axtv.browser.browser.adblock.AdBlockService
import com.axtv.browser.browser.adblock.FilterListCatalog
import com.axtv.browser.net.ProxySettings
import com.axtv.browser.singleton.shortcuts.KeyBinding
import com.axtv.browser.singleton.shortcuts.Shortcut
import com.axtv.browser.singleton.shortcuts.ShortcutMgr
import com.axtv.browser.utils.DefaultBrowser
import com.axtv.browser.utils.DownloadFolder
import com.axtv.browser.utils.launchForResult
import com.axtv.browser.utils.activemodel.ActiveModelsRepository
import com.axtv.browser.webengine.EngineSpecificSetting
import com.axtv.browser.webengine.WebEngineFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The settings of the app for the settings screens: [Config], the [SettingsModel] and the models of ad
 * blocking and updates. The checks of the engine and the build are made here.
 */
class AppSettingsStore(
    private val activity: AppCompatActivity,
    private val settingsModel: SettingsModel,
    private val controller: BrowserController
) : SettingsNavigator.Store {
    companion object {
        private const val TAG = "AppSettingsStore"
        private const val BROWSABLE_CHECK_DELAY = 1000L
        private var startup: SettingsNavigator.StartupValues? = null

        /** Remembers the values the process starts with, before anything changes them (MainActivity.onCreate) */
        fun rememberStartupValues(config: Config) {
            if (startup != null) return
            startup = valuesOf(config)
        }

        private fun valuesOf(config: Config) = SettingsNavigator.StartupValues(
            config.interfaceMode, config.theme.value, config.webviewUseAlgorithmicDarkeningWithDarkUiMode, config.webEngineDebug)
    }

    private val config = AppContext.provideConfig()
    private val adblock = TVBro.container.adBlock
    private val autoUpdate = ActiveModelsRepository.get(AutoUpdateModel::class, activity)
    private val shortcuts = ShortcutMgr.getInstance(activity)

    override val startupValues: SettingsNavigator.StartupValues
        get() = startup ?: valuesOf(config).also { startup = it }

    override var searchEngineUrl: String
        get() = config.searchEngineURL.value
        set(value) = settingsModel.setSearchEngineURL(value)
    override val homePageMode: Config.HomePageMode get() = settingsModel.homePageMode
    override val homePageUrl: String get() = settingsModel.homePage
    override val homePageLinksMode: Config.HomePageLinksMode get() = settingsModel.homePageLinksMode

    override fun setHomePage(mode: Config.HomePageMode, customUrl: String?, linksMode: Config.HomePageLinksMode) {
        settingsModel.setHomePageProperties(mode, customUrl, linksMode)
        //the home page shows the links of the new mode
        TVBro.container.appScope.launch { controller.homePageLinks.load() }
    }

    override var homePageBookmarksFolder: Long
        get() = config.homePageBookmarksFolder
        set(value) {
            config.homePageBookmarksFolder = value
            //the home page shows the bookmarks of the new folder
            TVBro.container.appScope.launch { controller.homePageLinks.load() }
        }

    override val supportsHomeChannels: Boolean get() = HomeChannels.isSupported(activity)
    override var channelBookmarks: Boolean
        get() = config.channelBookmarks
        set(value) {
            config.channelBookmarks = value
            applyChannel(ChannelKind.BOOKMARKS, value)
        }
    override val channelBookmarksSource: Config.ChannelBookmarksSource get() = config.channelBookmarksSource
    override val channelBookmarksFolder: Long get() = config.channelBookmarksFolder

    override fun setChannelBookmarks(source: Config.ChannelBookmarksSource, folder: Long) {
        config.channelBookmarksSource = source
        config.channelBookmarksFolder = folder
        applyChannel(ChannelKind.BOOKMARKS, config.channelBookmarks)
    }

    override suspend fun bookmarkFolders(): List<SettingsNavigator.BookmarkFolder> {
        val favorites = TVBro.container.data.favorites
        val result = ArrayList<SettingsNavigator.BookmarkFolder>()
        val visited = HashSet<Long>()
        suspend fun walk(folder: Long, path: String?) {
            if (!visited.add(folder)) return
            for (child in favorites.children(folder)) {
                if (!child.isFolder) continue
                val childPath = listOfNotNull(path, child.title ?: "").joinToString(" / ")
                result += SettingsNavigator.BookmarkFolder(child.id, childPath)
                walk(child.id, childPath)
            }
        }
        walk(FavoritesRepository.ROOT, null)
        return result
    }

    override var channelTabs: Boolean
        get() = config.channelTabs
        set(value) {
            config.channelTabs = value
            applyChannel(ChannelKind.TABS, value)
        }
    override var channelDownloads: Boolean
        get() = config.channelDownloads
        set(value) {
            config.channelDownloads = value
            applyChannel(ChannelKind.DOWNLOADS, value)
        }

    /**
     * Creates, fills or deletes the channel at once. A new channel shows on the home screen only once the user
     * lets it (the first one of an app is let by the system itself): the system asks in its own dialog.
     */
    private fun applyChannel(kind: ChannelKind, enabled: Boolean) {
        val context = activity.applicationContext
        TVBro.container.appScope.launch {
            TVBro.container.tabWrites.awaitPending()
            val channelId = HomeChannels.sync(context, listOf(kind))[kind] ?: return@launch
            if (!enabled) return@launch
            //the system lets the first channel of an app by itself, a moment after it is created
            delay(BROWSABLE_CHECK_DELAY)
            if (HomeChannels.isBrowsable(context, kind) != false) return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val intent = Intent(TvContract.ACTION_REQUEST_CHANNEL_BROWSABLE).putExtra(TvContract.EXTRA_CHANNEL_ID, channelId)
            try {
                activity.launchForResult(ActivityResultContracts.StartActivityForResult(), intent) {}
            } catch (e: Exception) {
                Log.w(TAG, "No dialog to show the channel of ${kind.id}", e)
            }
        }
    }

    override var defaultDesktopView: Boolean?
        get() = config.defaultDesktopView
        set(value) {
            config.defaultDesktopView = value
            controller.onSiteViewSettingsChanged()
        }
    override var userAgent: String?
        get() = config.userAgent
        set(value) {
            config.userAgent = value
            controller.onSiteViewSettingsChanged()
        }
    override var allowAutoplayMedia: Boolean by config::allowAutoplayMedia
    override var adBlockEnabled: Boolean by config::adBlockEnabled
    override val supportsTrackingProtection: Boolean
        get() = WebEngineFactory.supportsSetting(EngineSpecificSetting.TRACKING_PROTECTION)
    /** It applies to the pages loaded after the change */
    override var trackingProtection: Boolean by config::trackingProtection
    override val supportsWebNotifications: Boolean
        get() = WebEngineFactory.supportsSetting(EngineSpecificSetting.WEB_NOTIFICATIONS)
    /** Off, the notifications of the sites already allowed stop showing too; their permission stays */
    override var webNotifications: Boolean by config::webNotifications
    override val filterLists: SettingsNavigator.FilterLists get() = filterListsOf(adblock.status.value)
    override val filterListsChanges: Flow<SettingsNavigator.FilterLists> =
        adblock.status.map(::filterListsOf).distinctUntilChanged()

    override fun setFilterListEnabled(id: String, enabled: Boolean) {
        adblock.setListEnabled(id, enabled)
    }

    override fun setRegionalFilterList(id: String?) {
        adblock.setRegionalList(id)
    }

    override fun addCustomFilterList(url: String) {
        adblock.addCustomList(url)
    }

    override fun removeCustomFilterList(id: String) {
        adblock.removeCustomList(id)
    }

    override suspend fun updateFilterLists(): Boolean = adblock.update(force = true)

    private fun filterListsOf(status: AdBlockService.Status): SettingsNavigator.FilterLists {
        fun of(list: AdBlockService.ListState, regional: Boolean) = SettingsNavigator.FilterList(list.id, list.title,
            list.enabled, regional, list.filters, list.lastUpdate, list.failed)
        return SettingsNavigator.FilterLists(
            lists = status.lists.map { of(it, FilterListCatalog.find(it.id)?.isRegional ?: false) },
            custom = status.customLists.map { of(it, false) },
            updating = status.updating, lastUpdate = status.lastUpdate, skipped = status.skipped,
            notReady = adblock.notReadyRequestCount, errors = adblock.checkErrors,
        )
    }

    override var interfaceMode: Config.InterfaceMode by config::interfaceMode
    /** Its observer sets the night mode and tells the engine */
    override var theme: Config.Theme
        get() = config.theme.value
        set(value) {
            config.theme.value = value
        }
    override val supportsDarkening: Boolean
        get() = WebEngineFactory.supportsSetting(EngineSpecificSetting.ALGORITHMIC_DARKENING)
    override var darkening: Boolean by config::webviewUseAlgorithmicDarkeningWithDarkUiMode
    /** Its observer sets the window flag */
    override var matchFrameRate: Boolean
        get() = config.matchFrameRate
        set(value) {
            config.matchFrameRate = value
        }
    override var pageVideoHint: Boolean by config::pageVideoHint
    override var builtInPlayerByDefault: Boolean
        get() = config.builtInPlayerByDefault
        set(value) {
            config.builtInPlayerByDefault = value
            controller.onDefaultPlayerChanged()
        }

    //the browsing data of the privacy screen: the controller owns the history and the data of the sites
    override val supportsSiteDataClearing: Boolean get() = WebEngineFactory.siteDataClearing != null
    override suspend fun sitesWithData(): List<SiteVisits> = controller.sitesWithData()
    override suspend fun clearHistorySince(time: Long): Int = controller.clearHistorySince(time)
    override suspend fun clearSitesData(hosts: List<String>): Int = controller.clearSitesData(hosts)

    override var keepScreenOn: Boolean
        get() = settingsModel.keepScreenOn.value
        set(value) {
            settingsModel.keepScreenOn.value = value
        }

    override var joystickNavigation: Boolean
        get() = !config.disableMotionAxesDpadNavigation
        set(value) {
            config.disableMotionAxesDpadNavigation = !value
        }
    override var cursorSpeed: Int by config::cursorMaxSpeedPercent
    override var cursorAcceleration: Int by config::cursorAccelerationPercent
    override fun bindingOf(shortcut: Shortcut): KeyBinding = shortcuts.bindingOf(shortcut)

    override suspend fun clearCache() = WebEngineFactory.clearCache(AppContext.get())
    override var webEngineDebug: Boolean by config::webEngineDebug
    override val supportsDefaultBrowser: Boolean get() = DefaultBrowser.isSupported(activity)
    override val isDefaultBrowser: Boolean get() = DefaultBrowser.isDefault(activity)
    override val downloadFolderPickerAvailable: Boolean by lazy { DownloadFolder.pickerAvailable(AppContext.get()) }
    override val downloadFolderName: String?
        get() = config.downloadFolderUri?.let { config.downloadFolderName ?: it }
    override fun resetDownloadFolder() = DownloadFolder.reset(AppContext.get(), config)

    override val supportsProxy: Boolean get() = WebEngineFactory.proxy != null
    override var proxyEnabled: Boolean
        get() = config.proxyEnabled
        set(value) {
            config.proxyEnabled = value
            applyProxy()
        }
    override var proxyType: ProxySettings.Type
        get() = config.proxyType
        set(value) {
            config.proxyType = value
            applyProxy()
        }
    override var proxyHost: String
        get() = config.proxyHost
        set(value) {
            config.proxyHost = value
            applyProxy()
        }
    override var proxyPort: Int
        get() = config.proxyPort
        set(value) {
            config.proxyPort = value
            applyProxy()
        }
    override var proxyUsername: String
        get() = config.proxyUsername
        set(value) {
            config.proxyUsername = value
            applyProxy()
        }
    override var proxyPassword: String
        get() = config.proxyPassword
        set(value) {
            config.proxyPassword = value
            applyProxy()
        }
    override val proxy: ProxySettings? get() = config.proxy

    /**
     * The app, the engine and the current page go through the proxy at once; after the other settings changed
     * with it (an address with a port), so that it is applied once
     */
    private fun applyProxy() {
        TVBro.container.appScope.launch(Dispatchers.Main) { ProxySetup.apply(config, controller) }
    }

    override val versionName: String get() = BuildConfig.VERSION_NAME
    override val buildName: String get() = BuildConfig.FLAVOR_appstore
    override val engineVersion: String get() = WebEngineFactory.getWebEngineVersionString()
    override val notices: List<ThirdPartyNotices.Notice> by lazy { ThirdPartyNotices.load(activity.assets) }
    override val builtInUpdates: Boolean get() = BuildConfig.BUILT_IN_AUTO_UPDATE
    override var autoCheckUpdates: Boolean
        get() = autoUpdate.needAutoCheckUpdates
        set(value) = autoUpdate.saveAutoCheckUpdates(value)
    override var updateChannel: String by config::updateChannel

    override suspend fun checkUpdates(force: Boolean): SettingsNavigator.Updates? {
        autoUpdate.checkUpdate(force) {}.join()
        val result = autoUpdate.updateChecker.versionCheckResult ?: return null
        return SettingsNavigator.Updates(result.availableChannels.toList(),
            result.latestVersionName.takeIf { autoUpdate.updateChecker.hasUpdate() })
    }
}

/**
 * What both shells give their settings screen: the dialogs, the key capture, links in a new tab, the update
 * and the restart.
 * @param showMessage the shell's own way to tell something
 */
class SettingsShellCallback(
    private val host: BrowserShell.Host,
    private val showMessage: (icon: Int, message: String) -> Unit
) : SettingsNavigator.ShellCallback {
    private val activity get() = host.activity

    override val store: SettingsNavigator.Store by lazy {
        AppSettingsStore(activity, host.settingsModel, host.controller)
    }

    override fun showMessage(@DrawableRes icon: Int, message: String) = showMessage.invoke(icon, message)

    override fun confirm(title: String?, message: String, positive: String, onResult: (Boolean) -> Unit) {
        var answered = false
        val answer = { yes: Boolean ->
            if (!answered) {
                answered = true
                onResult(yes)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> answer(true) }
            .setNegativeButton(R.string.cancel) { _, _ -> answer(false) }
            .setOnDismissListener { answer(false) }
            .show()
    }

    override fun captureKey(shortcut: Shortcut, onDone: () -> Unit) {
        ShortcutKeyDialog(activity, shortcut).apply {
            setOnDismissListener { onDone() }
            show()
        }
    }

    override fun pickFromList(title: String, options: List<String>, checked: Int, onChosen: (Int) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                onChosen(which)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun pickDownloadFolder(onDone: () -> Unit) {
        val config = AppContext.provideConfig()
        //the picker starts at the chosen folder: it takes a document uri
        val initial = DownloadFolder.chosen(config)?.let { runCatching { DownloadFolder.folderDocument(it) }.getOrNull() }
        activity.launchForResult(ActivityResultContracts.OpenDocumentTree(), initial) { tree ->
            if (tree != null && !DownloadFolder.set(activity, config, tree)) {
                showMessage(R.drawable.ic_warning, activity.getString(R.string.settings_download_folder_failed))
            }
            onDone()
        }
    }

    override fun askDefaultBrowser(onDone: () -> Unit) {
        val intent = DefaultBrowser.requestIntent(activity)
        if (intent == null) {
            //the row is there only while the device offers the role, but it can go away with a system update
            showMessage(R.drawable.ic_warning, activity.getString(R.string.settings_default_browser_failed))
            onDone()
            return
        }
        activity.launchForResult(ActivityResultContracts.StartActivityForResult(), intent) { onDone() }
    }

    override fun open(url: String) {
        host.openInNewTab(url, host.controller.tabsStates.size)
    }

    override fun showNotice(notice: ThirdPartyNotices.Notice, open: (String) -> Unit) {
        val vb = DialogNoticeBinding.inflate(LayoutInflater.from(activity))
        vb.tvNotice.text = ThirdPartyNotices.text(activity.assets, notice)
        val builder = AlertDialog.Builder(activity)
            .setTitle(notice.name)
            .setView(vb.root)
            .setPositiveButton(R.string.ok, null)
        notice.url?.let { url -> builder.setNeutralButton(R.string.open) { _, _ -> open(url) } }
        builder.show()
        //the D-pad scrolls the text first
        vb.svNotice.requestFocus()
    }

    /** The screen is closed already; the dialog of the update offers to download it */
    override fun startUpdate() {
        val autoUpdate = ActiveModelsRepository.get(AutoUpdateModel::class, activity)
        val mainActivity = activity as? MainActivity ?: return
        if (autoUpdate.updateChecker.hasUpdate()) {
            autoUpdate.showUpdateDialogIfNeeded(mainActivity, true)
        }
    }

    override fun restart() {
        TVBro.exitProcessAfterMainActivityFinish(restartMainActivity = true)
        activity.finish()
    }
}
