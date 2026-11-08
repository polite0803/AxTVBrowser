package com.axtv.browser.webengine.gecko.delegates

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.axtv.browser.AppContext
import com.axtv.browser.webengine.gecko.GeckoWebEngine
import com.axtv.browser.webengine.gecko.R
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission

class MyPermissionDelegate(private val webEngine: GeckoWebEngine): PermissionDelegate {
    override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<String>?,
        callback: PermissionDelegate.Callback) {
        if (permissions == null) {
            callback.reject()
            return
        }
        val engineCallback = webEngine.callback
        if (engineCallback == null) {
            callback.reject()
            return
        }
        engineCallback.requestPermissions(permissions) { results ->
            if (results.isNotEmpty() && permissions.all { results[it] == true }) {
                callback.grant()
            } else {
                callback.reject()
            }
        }
    }

    override fun onContentPermissionRequest(session: GeckoSession,
        perm: ContentPermission): GeckoResult<Int>? {
        val activity = webEngine.callback?.getActivity() ?: return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
        if (perm.permission == PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION) {
            return askForNotifications(activity, session, perm)
        }
        val resId: Int = when (perm.permission) {
            PermissionDelegate.PERMISSION_GEOLOCATION -> R.string.request_geolocation
            PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> R.string.request_storage
            PermissionDelegate.PERMISSION_XR -> R.string.request_xr
            PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE, PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ->
                return if (!AppContext.provideConfig().allowAutoplayMedia) {
                GeckoResult.fromValue(ContentPermission.VALUE_DENY)
            } else {
                GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
            }
            PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> R.string.request_media_key_system_access
            PermissionDelegate.PERMISSION_STORAGE_ACCESS -> R.string.request_storage_access
            else -> return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
        }

        val title: String = activity.getString(resId, Uri.parse(perm.uri).authority)
        return webEngine.promptDelegate.onPermissionPrompt(session, title, perm)
    }

    /**
     * A site asks for the web Notifications API. [Config.webNotifications] turns it off for every site and
     * refuses without asking; on Android 13 and newer the system permission of the app comes first, because with
     * it off a notification would be thrown away and the site would still think it shows one.
     */
    private fun askForNotifications(activity: Activity, session: GeckoSession,
                                    perm: ContentPermission): GeckoResult<Int> {
        if (!AppContext.provideConfig().webNotifications) {
            return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
        }
        if (mayNotify(activity)) return siteNotificationRequest(activity, session, perm)
        val callback = webEngine.callback ?: return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
        val answer = GeckoResult<Int>()
        callback.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { granted ->
            val next = if (granted[Manifest.permission.POST_NOTIFICATIONS] == true)
                siteNotificationRequest(activity, session, perm)
            else GeckoResult.fromValue(ContentPermission.VALUE_DENY)
            next.accept({ value -> answer.complete(value) }, { e ->
                Log.e(GeckoWebEngine.TAG, "The notification permission went wrong", e)
                answer.complete(ContentPermission.VALUE_DENY)
            })
        }
        return answer
    }

    private fun siteNotificationRequest(activity: Activity, session: GeckoSession,
                                        perm: ContentPermission): GeckoResult<Int> =
        webEngine.promptDelegate.onPermissionPrompt(session, activity.getString(R.string.request_notification,
            Uri.parse(perm.uri).authority), perm)
            ?: GeckoResult.fromValue(ContentPermission.VALUE_DENY)

    /** Android 12 and older need no permission of their own for the notification shade */
    private fun mayNotify(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<PermissionDelegate.MediaSource>?,
        audio: Array<PermissionDelegate.MediaSource>?,
        callback: PermissionDelegate.MediaCallback
    ) {
        // If we don't have device permissions at this point, just automatically reject the request
        // as we will have already have requested device permissions before getting to this point
        // and if we've reached here and we don't have permissions then that means that the user
        // denied them.
        val activity = webEngine.callback?.getActivity()
        if (activity == null) {
            callback.reject()
            return
        }
        if ((audio != null && ContextCompat.checkSelfPermission(
                activity, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED)
            || (video != null && ContextCompat.checkSelfPermission(
                activity, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED)
        ) {
            callback.reject()
            return
        }

        val host = Uri.parse(uri).authority
        val title: String = if (audio == null) {
            activity.getString(R.string.request_video, host)
        } else if (video == null) {
            activity.getString(R.string.request_audio, host)
        } else {
            activity.getString(R.string.request_media, host)
        }

        val videoNames = normalizeMediaName(video)
        val audioNames = normalizeMediaName(audio)

        webEngine.promptDelegate.onMediaPrompt(session, title, video, audio, videoNames, audioNames, callback)
    }

    private fun normalizeMediaName(sources: Array<PermissionDelegate.MediaSource>?): Array<String>? {
        if (sources == null) {
            return null
        }
        val context = webEngine.callback?.getActivity() ?: return null
        val res = arrayOfNulls<String>(sources.size)
        for (i in sources.indices) {
            val mediaSource = sources[i].source
            val name = sources[i].name
            if (PermissionDelegate.MediaSource.SOURCE_CAMERA == mediaSource) {
                if (name!!.lowercase().contains("front")) {
                    res[i] = context.getString(R.string.media_front_camera)
                } else {
                    res[i] = context.getString(R.string.media_back_camera)
                }
            } else if (!name!!.isEmpty()) {
                res[i] = name
            } else if (PermissionDelegate.MediaSource.SOURCE_MICROPHONE == mediaSource) {
                res[i] = context.getString(R.string.media_microphone)
            } else {
                res[i] = context.getString(R.string.media_other)
            }
        }
        return res.requireNoNulls()
    }
}