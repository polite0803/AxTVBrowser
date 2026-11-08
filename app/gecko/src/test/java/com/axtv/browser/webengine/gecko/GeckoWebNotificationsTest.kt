package com.axtv.browser.webengine.gecko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeckoWebNotificationsTest {
    @Test
    fun theSettingAndPrivateTabsDecideWhatShows() {
        assertTrue(GeckoWebNotifications.shows(enabled = true, privateBrowsing = false))
        //the click of a notification of a private tab could only open its site in a normal tab
        assertFalse(GeckoWebNotifications.shows(enabled = true, privateBrowsing = true))
        assertFalse(GeckoWebNotifications.shows(enabled = false, privateBrowsing = false))
        assertFalse(GeckoWebNotifications.shows(enabled = false, privateBrowsing = true))
    }

    @Test
    fun aSiteKeepsTheIdOfItsNotificationTag() {
        //a click may reach a process that never showed the notification: the id comes from its tag alone
        assertEquals(GeckoWebNotifications.idOf("news"), GeckoWebNotifications.idOf("news"))
        assertFalse(GeckoWebNotifications.idOf("news") == GeckoWebNotifications.idOf("mail"))
    }
}
