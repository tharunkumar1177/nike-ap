// ConnectionService.kt: connected-device foreground service that keeps the active transport alive.
package dev.padlink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.padlink.MainActivity
import dev.padlink.PadLinkApp
import dev.padlink.R
import dev.padlink.transport.LinkState
import dev.padlink.transport.LinkStatus
import kotlinx.coroutines.launch

/** Runs while the app is in use or connected so the HID registration and sockets survive. */
class ConnectionService : LifecycleService() {

    /** Promotes to foreground, activates the router, and mirrors link state into the notification. */
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startInForeground(buildNotification(LinkState.IDLE))
        val router = PadLinkApp.from(this).router
        router.activate()
        lifecycleScope.launch {
            router.state.collect { state ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    /** Handles the notification's Disconnect action. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            PadLinkApp.from(this).router.deactivate()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Stops input delivery when the user swipes the app away. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        PadLinkApp.from(this).router.deactivate()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /** Calls the API-appropriate startForeground overload. */
    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Creates the low-importance notification channel once. */
    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    /** Builds the ongoing notification text for a link state. */
    private fun buildNotification(state: LinkState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (state.status) {
            LinkStatus.CONNECTED -> getString(R.string.notification_connected, state.targetName ?: "")
            LinkStatus.CONNECTING -> getString(R.string.status_connecting)
            else -> getString(R.string.notification_ready)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_disconnect), stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.padlink.action.STOP"

        /** Starts (or keeps) the service in the foreground. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }
    }
}
