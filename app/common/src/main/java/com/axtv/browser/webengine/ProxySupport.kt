package com.axtv.browser.webengine

import com.axtv.browser.net.ProxySettings

/**
 * An engine that can make its requests go through a proxy. The app keeps the proxy of the process
 * ([com.axtv.browser.net.AppProxy]) and the one of the engine the same (see `ProxySetup`), and the
 * settings screen has the proxy rows only for an engine that has this ([WebEngineFactory.proxy]).
 */
interface ProxySupport {
    /**
     * Makes all pages of the engine go through [proxy] (directly when null), except the local addresses of
     * [ProxySettings.BYPASS_DOMAINS] and [ProxySettings.BYPASS_NETWORKS]; returns once it is applied.
     * Pages never fall back to a direct connection when the proxy fails.
     */
    suspend fun applyProxy(proxy: ProxySettings?)
}
