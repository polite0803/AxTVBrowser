package com.axtv.browser.browser

import androidx.annotation.MainThread
import com.axtv.browser.Config
import com.axtv.browser.net.AppProxy
import com.axtv.browser.webengine.WebEngineFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the proxy of the process ([AppProxy]) and of the engine the same as the settings ([Config.proxy]).
 * Nothing here touches the engine before it is initialized: the WebView engine must not be loaded before
 * the incognito process gave it its data directory, and the engine applies [AppProxy.settings] itself in
 * `initialize`.
 */
object ProxySetup {
    private val applying = Mutex()

    /**
     * Called first in each process: browser processes follow the settings, the downloads process gets the
     * proxy of each download with its command.
     */
    fun start(config: Config, browserProcess: Boolean) {
        AppProxy.install()
        if (browserProcess) AppProxy.settings = config.proxy
    }

    /**
     * The proxy settings changed: once the address is complete (or the proxy is turned off) the app and the
     * engine go through the new proxy, and the current page loads again through it
     */
    @MainThread
    suspend fun apply(config: Config, controller: BrowserController) = applying.withLock {
        val proxy = config.proxy
        if (proxy == AppProxy.settings) return@withLock
        AppProxy.settings = proxy
        WebEngineFactory.applyProxy(proxy)
        controller.reload()
    }
}
