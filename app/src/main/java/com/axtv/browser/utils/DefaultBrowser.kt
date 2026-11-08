package com.axtv.browser.utils

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Being the default browser: the links of other apps open in this app. It is the browser role of Android 10
 * and newer, which not every device offers (a TV without the role handler of the system does not).
 */
object DefaultBrowser {
    /** The system lets an app ask for the browser role */
    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_BROWSER) == true
    }

    /** The links of other apps open in this app */
    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
    }

    /** The intent of the dialog asking the user, null when the device has no browser role */
    fun requestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val roles = context.getSystemService(RoleManager::class.java) ?: return null
        if (!roles.isRoleAvailable(RoleManager.ROLE_BROWSER)) return null
        return roles.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
    }
}
