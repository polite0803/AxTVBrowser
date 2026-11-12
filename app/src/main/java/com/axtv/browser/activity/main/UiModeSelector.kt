package com.axtv.browser.activity.main

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.axtv.browser.Config

/** The look of the browser screen */
enum class UiMode { TV, PHONE }

/**
 * Picks the look of the browser screen: the Interface setting, or on Auto what the device is.
 */
object UiModeSelector {
    fun select(context: Context, setting: Config.InterfaceMode): UiMode = when (setting) {
        Config.InterfaceMode.TV -> UiMode.TV
        Config.InterfaceMode.PHONE -> UiMode.PHONE
        Config.InterfaceMode.AUTO -> detect(context)
    }

    fun detect(context: Context): UiMode {
        val configuration = context.resources.configuration
        return detect(
            uiModeType = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK,
            hasLeanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
            touchscreen = configuration.touchscreen
        )
    }

    /**
     * The touch UI is for phones and tablets: not a TV and a touch screen that is really there (the
     * configuration comes from the input devices; TV boxes with tablet firmware declare the touchscreen
     * feature too). The width does not tell a phone or a tablet from a TV: a 1080p TV at xhdpi is 540dp wide.
     */
    fun detect(uiModeType: Int, hasLeanback: Boolean, touchscreen: Int): UiMode {
        val touch = uiModeType != Configuration.UI_MODE_TYPE_TELEVISION &&
                !hasLeanback &&
                touchscreen == Configuration.TOUCHSCREEN_FINGER
        return if (touch) UiMode.PHONE else UiMode.TV
    }

    /**
     * Whether the phone interface needs a way back to the TV one: the user chose Phone on a TV, or
     * Auto picked the phone interface on a TV the device detection did not see. The phone UI has no
     * virtual cursor there, so it offers a button that goes back to the TV interface. A real phone
     * or tablet never needs it.
     */
    fun phoneUiNeedsTvRecovery(
        setting: Config.InterfaceMode, deviceMode: UiMode, systemTvMode: Boolean, fireTv: Boolean
    ): Boolean = (setting == Config.InterfaceMode.PHONE || setting == Config.InterfaceMode.AUTO) &&
            (deviceMode == UiMode.TV || systemTvMode || fireTv)
}
