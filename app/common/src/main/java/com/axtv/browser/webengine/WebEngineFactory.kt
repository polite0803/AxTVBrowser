package com.axtv.browser.webengine

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import com.axtv.browser.Config
import com.axtv.browser.model.BrowserTab
import com.axtv.browser.net.AppProxy
import com.axtv.browser.net.ProxySettings

/**
 * A setting that only some engines have, which the engine reads from [Config] itself: the app only needs to know
 * whether the settings screen shows its row (see [WebEngineProviderCallback.supportsSetting]). What the app asks
 * the engine to *do* is not here but a capability of its own: [ProxySupport], [SiteDataClearingSupport].
 */
enum class EngineSpecificSetting {
    /** Darkening of pages that have no dark theme while the dark UI mode is on */
    ALGORITHMIC_DARKENING,
    /** The engine's own blocking of trackers ([Config.trackingProtection]) */
    TRACKING_PROTECTION,
    /** The web Notifications API: sites can show system notifications ([Config.webNotifications]) */
    WEB_NOTIFICATIONS
}

interface WebEngineProviderCallback {
    /**
     * [webViewContainer] is where engines put their views (see [WebEngine.onAttachToWindow]). An engine with a
     * [proxy] goes through the proxy of [AppProxy.settings] before it returns, so that nothing loads before.
     */
    suspend fun initialize(context: Context, webViewContainer: ViewGroup)
    fun createWebEngine(tab: BrowserTab): WebEngine
    suspend fun clearCache(ctx: Context)
    fun onThemeSettingUpdated(value: Config.Theme)
    fun getWebEngineVersionString(): String

    /**
     * Null when the engine can not go through a proxy. An engine whose support comes only with some version of
     * its library answers this on each call, so that nothing of the engine is loaded while it is asked.
     */
    val proxy: ProxySupport?

    /** Null when the engine can not remove the data of one site */
    val siteDataClearing: SiteDataClearingSupport?

    /** Called in the incognito process before the engine is initialized */
    fun prepareIncognitoProcess() {}

    /** Leaving the incognito mode, before its tabs are closed */
    suspend fun clearIncognitoBrowsingData(context: Context) {}

    /** Leaving the incognito mode, after its tabs are closed */
    suspend fun deleteIncognitoData() {}

    /** The engine could not create a view for a tab (for example the system WebView is broken) */
    fun onViewCreationFailed(activity: Activity, error: Throwable) {}

    /** Whether the engine has [setting] */
    fun supportsSetting(setting: EngineSpecificSetting): Boolean = false
}

data class WebEngineProvider(
    val name: String,
    val callback: WebEngineProviderCallback
)

/**
 * The web engine of the app. Each variant of the app has one engine, which registers itself here
 * (see `TVBro.initWebEngineStuff`).
 */
object WebEngineFactory {
    const val TAG = "WebEngineFactory"
    private var registeredProvider: WebEngineProvider? = null
    private lateinit var initializedProvider: WebEngineProvider

    /** Set by the app in the browser processes; null blocks nothing */
    @Volatile
    var adBlocker: EngineAdBlocker? = null

    fun registerProvider(provider: WebEngineProvider) {
        val registered = registeredProvider
        check(registered == null || registered.name == provider.name) {
            "Web engine ${registered?.name} is registered already, can not register ${provider.name}"
        }
        registeredProvider = provider
    }

    @UiThread
    suspend fun initialize(context: Context, webViewContainer: ViewGroup) {
        val provider = registeredProvider ?: throw IllegalStateException("No web engine is registered")
        provider.callback.initialize(context, webViewContainer)
        initializedProvider = provider
    }

    /** Makes [createWebEngine] use the [provider] without [initialize] */
    @VisibleForTesting
    fun initializeForTests(provider: WebEngineProvider) {
        initializedProvider = provider
    }

    /** The provider of the engine, available before [initialize] */
    private fun selectedProvider(): WebEngineProvider? {
        if (this::initializedProvider.isInitialized) return initializedProvider
        return registeredProvider
    }

    fun prepareIncognitoProcess() {
        selectedProvider()?.callback?.prepareIncognitoProcess()
    }

    suspend fun clearIncognitoBrowsingData(context: Context) {
        selectedProvider()?.callback?.clearIncognitoBrowsingData(context)
    }

    suspend fun deleteIncognitoData() {
        selectedProvider()?.callback?.deleteIncognitoData()
    }

    fun onViewCreationFailed(activity: Activity, error: Throwable) {
        selectedProvider()?.callback?.onViewCreationFailed(activity, error)
    }

    /** Whether the engine supports [setting] */
    fun supportsSetting(setting: EngineSpecificSetting): Boolean =
        selectedProvider()?.callback?.supportsSetting(setting) ?: false

    /** The engine goes through a proxy when the settings ask for one; null when it can not (or none is registered) */
    val proxy: ProxySupport? get() = selectedProvider()?.callback?.proxy

    /** The engine can remove the data of one site; null when it can not (or none is registered) */
    val siteDataClearing: SiteDataClearingSupport? get() = selectedProvider()?.callback?.siteDataClearing

    fun createWebEngine(tab: BrowserTab): WebEngine {
        return initializedProvider.callback.createWebEngine(tab)
    }

    suspend fun clearCache(ctx: Context) {
        initializedProvider.callback.clearCache(ctx)
    }

    /** See [ProxySupport.applyProxy]; does nothing before [initialize] (it applies the proxy itself) */
    suspend fun applyProxy(proxy: ProxySettings?) {
        if (!this::initializedProvider.isInitialized) return
        initializedProvider.callback.proxy?.applyProxy(proxy)
    }

    fun onThemeSettingUpdated(value: Config.Theme) {
        initializedProvider.callback.onThemeSettingUpdated(value)
    }

    fun getWebEngineVersionString(): String {
        return initializedProvider.callback.getWebEngineVersionString()
    }
}
