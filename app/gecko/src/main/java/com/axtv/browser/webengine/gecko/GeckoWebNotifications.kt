package com.axtv.browser.webengine.gecko

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import com.axtv.browser.AppContext
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate
import org.mozilla.geckoview.WebRequest

/** The notification of a page, carried to [GeckoNotificationReceiver] by its intents */
private const val EXTRA_NOTIFICATION = "web_notification"

/** The name of the action button of the notification the user pressed, null for the notification itself */
private const val EXTRA_ACTION = "web_notification_action"

/**
 * The notifications pages show with the web Notifications API: GeckoView hands them here and the app puts them
 * in the system shade. Its channel is created here and not by the application: the shade lists it only once a
 * page has asked for notifications, and the WebView app has none at all.
 */
class GeckoWebNotifications(private val context: Context) : WebNotificationDelegate {
    companion object {
        const val CHANNEL_ID = "web_notifications"
        const val ACTION_CLICK = "com.axtv.browser.NOTIFICATION_CLICK"
        const val ACTION_DISMISS = "com.axtv.browser.NOTIFICATION_DISMISS"
        /** GeckoView itself carries an icon url only that long in an intent */
        private const val IMAGE_URL_MAX = 150
        val TAG: String = GeckoWebNotifications::class.java.simpleName

        /**
         * The id of the notification of a site, taken from its tag: the one that closes it is found without
         * keeping anything in memory, since a click can come to a process that showed nothing.
         */
        fun idOf(tag: String): Int = tag.hashCode()

        /**
         * Whether a notification of GeckoView reaches the shade. [enabled] is [Config.webNotifications]; the
         * notifications of a private tab ([privateBrowsing]) never show, because the click of one could only
         * open its site in a normal tab and keep it in the history.
         */
        fun shows(enabled: Boolean, privateBrowsing: Boolean): Boolean = enabled && !privateBrowsing

        /** The page that made the notification, or null when it is not an address to open */
        fun pageOf(source: String?): Uri? =
            if (source != null && (source.startsWith("http://") || source.startsWith("https://")))
                Uri.parse(source) else null
    }

    private val manager = NotificationManagerCompat.from(context)

    override fun onShowNotification(notification: WebNotification) {
        if (!shows(AppContext.provideConfig().webNotifications, notification.privateBrowsing)) {
            closeInGecko(notification)
            return
        }
        ensureChannel()
        //without its permission the system throws the notification away and Gecko would keep it open forever
        if (!manager.areNotificationsEnabled()) {
            Log.i(TAG, "The app may not notify: nothing shown for ${notification.source}")
            closeInGecko(notification)
            return
        }
        val id = idOf(notification.tag)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(notification.title)
            .setContentText(notification.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.text))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent(ACTION_CLICK, id, notification, null))
            .setDeleteIntent(pendingIntent(ACTION_DISMISS, id, notification, null))
        for (action in notification.actions) {
            builder.addAction(NotificationCompat.Action(0, action.title,
                pendingIntent(ACTION_CLICK, id, notification, action.name)))
        }
        if (notification.silent) builder.setSilent(true)
        try {
            manager.notify(id, builder.build())
        } catch (e: Exception) {
            //the permission came and went in between, or the shade refuses it
            Log.e(TAG, "Can not show the notification of ${notification.source}", e)
            closeInGecko(notification)
            return
        }
        loadIcon(notification, builder)
    }

    override fun onCloseNotification(notification: WebNotification) {
        manager.cancel(idOf(notification.tag))
    }

    /**
     * The icon of a notification is a url of its site: it shows at once without it and is replaced when the
     * image is here, so a site that sends nothing or a broken image costs nothing.
     */
    private fun loadIcon(webNotification: WebNotification, builder: NotificationCompat.Builder) {
        val url = webNotification.imageUrl?.takeIf { it.isNotEmpty() && it.length < IMAGE_URL_MAX } ?: return
        val id = idOf(webNotification.tag)
        try {
            GeckoWebExecutor(GeckoWebEngine.runtime).fetch(
                WebRequest.Builder(url).addHeader("Accept", "image/").build()
            ).accept({ response ->
                val icon = response?.body?.use { BitmapFactory.decodeStream(it) }
                if (icon != null) manager.notify(id, builder.setLargeIcon(icon).build())
            }, { e ->
                Log.w(TAG, "No icon for the notification of ${webNotification.source}", e)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Can not fetch the icon of the notification", e)
        }
    }

    /** Tells GeckoView the notification is gone, on the thread it wants */
    private fun closeInGecko(notification: WebNotification) {
        GeckoWebEngine.uiHandler.post {
            try {
                notification.dismiss()
            } catch (e: Exception) {
                Log.w(TAG, "Can not tell GeckoView the notification closed", e)
            }
        }
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID,
            context.getString(R.string.web_notifications_channel), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.web_notifications_channel_description)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun pendingIntent(action: String, id: Int, notification: WebNotification,
                              event: String?): PendingIntent = PendingIntent.getBroadcast(context, id,
            Intent(action, null, context, GeckoNotificationReceiver::class.java)
                .putExtra(EXTRA_NOTIFICATION, notification).putExtra(EXTRA_ACTION, event),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/**
 * Takes the click and the dismissal of the notification of a page back to GeckoView. The click opens the page
 * that made it. A notification of a run of the app that is gone reaches nothing in Gecko: its click only opens
 * the page.
 */
class GeckoNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notification = IntentCompat.getParcelableExtra(intent, EXTRA_NOTIFICATION,
            WebNotification::class.java) ?: return
        val event = intent.getStringExtra(EXTRA_ACTION)
        //a receiver of the manifest runs on the main thread, which is what WebNotification asks for
        if (intent.action == GeckoWebNotifications.ACTION_CLICK) {
            try {
                if (event == null) notification.click() else notification.click(event)
            } catch (e: Exception) {
                Log.w(GeckoWebNotifications.TAG, "The notification is of a run of the app that is gone", e)
            }
            GeckoWebNotifications.pageOf(notification.source)?.let {
                context.startActivity(Intent(Intent.ACTION_VIEW, it).setPackage(context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else {
            try {
                notification.dismiss()
            } catch (e: Exception) {
                Log.w(GeckoWebNotifications.TAG, "The notification is of a run of the app that is gone", e)
            }
        }
    }
}
