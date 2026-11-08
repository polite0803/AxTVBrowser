package com.axtv.browser.webengine.gecko

import com.axtv.browser.Config
import org.mozilla.geckoview.GeckoRuntimeSettings

fun Config.Theme.toGeckoPreferredColorScheme(): Int {
    return when (this) {
        Config.Theme.SYSTEM -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        Config.Theme.WHITE -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        Config.Theme.BLACK -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
    }
}