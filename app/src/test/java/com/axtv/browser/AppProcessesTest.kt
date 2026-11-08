package com.axtv.browser

import android.content.ComponentName
import com.axtv.browser.AppProcesses.Kind
import com.axtv.browser.activity.IncognitoModeMainActivity
import com.axtv.browser.activity.main.MainActivity
import com.axtv.browser.service.downloads.DownloadService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppProcessesTest {
    private val pkg = "com.axtv.browser.gecko"

    @Test
    fun theBrowserProcessesAreInitialized() {
        assertEquals(Kind.BROWSER, AppProcesses.kindOf(pkg, pkg))
        assertEquals(Kind.BROWSER, AppProcesses.kindOf("$pkg:incognito", pkg))
    }

    @Test
    fun theDownloadsProcessIsKnown() {
        assertEquals(Kind.DOWNLOADS, AppProcesses.kindOf("$pkg:downloads", pkg))
    }

    @Test
    fun theProcessesOfGeckoViewAreNotTheApps() {
        for (suffix in listOf("tab_disable_art_image_9", "isolatedTab_disable_art_image_0", "gpu_disable_art_image_",
                "socket_disable_art_image_", "crashhelper_disable_art_image_", "media", "utility_disable_art_image_")) {
            assertEquals(suffix, Kind.OTHER, AppProcesses.kindOf("$pkg:$suffix", pkg))
        }
    }

    /** A name that could not be read, or one of another package, must not leave the browser uninitialized */
    @Test
    fun unknownNamesAreTakenForTheBrowser() {
        assertEquals(Kind.BROWSER, AppProcesses.kindOf("", pkg))
        assertEquals(Kind.BROWSER, AppProcesses.kindOf("java", pkg))
        assertEquals(Kind.BROWSER, AppProcesses.kindOf("com.axtv.browser", pkg))
    }

    @Test
    fun theSuffixesAreThoseOfTheManifest() {
        val context = RuntimeEnvironment.getApplication()
        val pm = context.packageManager
        val incognito = pm.getActivityInfo(ComponentName(context, IncognitoModeMainActivity::class.java), 0).processName
        val downloads = pm.getServiceInfo(ComponentName(context, DownloadService::class.java), 0).processName
        val browser = pm.getActivityInfo(ComponentName(context, MainActivity::class.java), 0).processName
        assertEquals(context.packageName + AppProcesses.INCOGNITO_SUFFIX, incognito)
        assertEquals(context.packageName + AppProcesses.DOWNLOADS_SUFFIX, downloads)
        assertEquals(Kind.BROWSER, AppProcesses.kindOf(browser, context.packageName))
        assertEquals(Kind.BROWSER, AppProcesses.kindOf(incognito, context.packageName))
        assertEquals(Kind.DOWNLOADS, AppProcesses.kindOf(downloads, context.packageName))
    }
}
