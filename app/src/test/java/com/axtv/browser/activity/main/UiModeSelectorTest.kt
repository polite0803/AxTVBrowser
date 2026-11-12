package com.axtv.browser.activity.main

import android.content.res.Configuration
import com.axtv.browser.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiModeSelectorTest {
    private fun detect(
        uiModeType: Int = Configuration.UI_MODE_TYPE_NORMAL,
        hasLeanback: Boolean = false,
        touchscreen: Int = Configuration.TOUCHSCREEN_FINGER
    ) = UiModeSelector.detect(uiModeType, hasLeanback, touchscreen)

    @Test
    fun touchPhoneOrTabletGetsThePhoneUi() {
        assertEquals(UiMode.PHONE, detect())
    }

    @Test
    fun androidTvGetsTheTvUi() {
        assertEquals(UiMode.TV, detect(uiModeType = Configuration.UI_MODE_TYPE_TELEVISION,
            hasLeanback = true, touchscreen = Configuration.TOUCHSCREEN_NOTOUCH))
    }

    @Test
    fun tvBoxWithTabletFirmwareAndNoTouchPanelGetsTheTvUi() {
        assertEquals(UiMode.TV, detect(touchscreen = Configuration.TOUCHSCREEN_NOTOUCH))
    }

    @Test
    fun leanbackDeviceWithATouchScreenGetsTheTvUi() {
        assertEquals(UiMode.TV, detect(hasLeanback = true))
    }

    @Test
    fun televisionUiModeWithATouchScreenGetsTheTvUi() {
        assertEquals(UiMode.TV, detect(uiModeType = Configuration.UI_MODE_TYPE_TELEVISION))
    }

    @Test
    fun phoneInterfaceChosenOnATvOffersTheWayBack() {
        assertTrue(UiModeSelector.phoneUiNeedsTvRecovery(
            Config.InterfaceMode.PHONE, UiMode.TV, systemTvMode = true, fireTv = false))
    }

    @Test
    fun autoPickedPhoneOnATvOffersTheWayBack() {
        assertTrue(UiModeSelector.phoneUiNeedsTvRecovery(
            Config.InterfaceMode.AUTO, UiMode.PHONE, systemTvMode = true, fireTv = false))
    }

    @Test
    fun phoneInterfaceOnARealPhoneDoesNotOfferTheTvUi() {
        assertFalse(UiModeSelector.phoneUiNeedsTvRecovery(
            Config.InterfaceMode.PHONE, UiMode.PHONE, systemTvMode = false, fireTv = false))
    }

    @Test
    fun tabletFirmwareOnATvStillOffersTheWayBack() {
        assertTrue(UiModeSelector.phoneUiNeedsTvRecovery(
            Config.InterfaceMode.PHONE, UiMode.PHONE, systemTvMode = true, fireTv = false))
        assertTrue(UiModeSelector.phoneUiNeedsTvRecovery(
            Config.InterfaceMode.PHONE, UiMode.PHONE, systemTvMode = false, fireTv = true))
    }
}
