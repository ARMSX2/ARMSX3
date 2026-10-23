package com.armsx2.online

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.armsx2.R
import com.armsx2.i18n.I18n

/**
 * Keeps a running game's RPCN session reachable while the app is in the background.
 *
 * Android 15 cuts an app's network a few seconds after it leaves the screen unless the app runs a
 * foreground service, and RPCN's server takes a player whose connection drops out of every room.
 * So a lobby left for a few seconds to message a friend was gone, and invites to it could not be
 * joined (the session signs in again by itself, but the room does not come back). The game still
 * pauses when the app leaves the screen (MainActivityRuntime.onPause): this holds the connection
 * and nothing else.
 *
 * GitHub flavor only. The play bundle declares no foreground service (see the main
 * AndroidManifest), and one there needs a Play Console declaration first; src/play has a no-op.
 */
object OnlineSession {
    @Volatile
    internal var running = false

    /** Only while the app is in sight: Android refuses a foreground service started from the
     *  background. Once started it keeps running there until [release]. */
    fun keepAlive(context: Context) {
        if (running) return
        running = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, OnlineSessionService::class.java))
        }.isSuccess
    }

    fun release(context: Context) {
        if (!running) return
        running = false
        runCatching { context.stopService(Intent(context, OnlineSessionService::class.java)) }
    }
}

class OnlineSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Has to follow startForegroundService within seconds: a service stopped before this
        // crashes the app, so nothing comes ahead of it that could fail.
        val notification = notification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Also reached when the user swipes the app away (stopWithTask), so the next online game
        // starts it again.
        OnlineSession.running = false
        super.onDestroy()
    }

    // Shown only when the app may post notifications; Android lists the app under active apps
    // either way.
    private fun notification(): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(I18n.get("online.channel"))
                .build()
        )

        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_armsx3_notification)
            .setContentTitle(I18n.get("online.notification.title"))
            .setContentText(I18n.get("online.notification.text"))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .apply { if (open != null) setContentIntent(open) }
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "online-session"
        const val NOTIFICATION_ID = 3001
    }
}
