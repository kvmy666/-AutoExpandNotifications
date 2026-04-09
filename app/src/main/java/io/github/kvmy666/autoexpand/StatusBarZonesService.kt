package io.github.kvmy666.autoexpand

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that signals "Status Bar Zones are active".
 * Actual touch detection happens in the SystemUI hook (MainHook.hookStatusBarZones).
 */
class StatusBarZonesService : Service() {

    companion object {
        const val CHANNEL_ID       = "zones_service"
        const val ACTION_START     = "io.github.kvmy666.autoexpand.ACTION_START_ZONES"
        const val ACTION_STOP      = "io.github.kvmy666.autoexpand.ACTION_STOP_ZONES"
        private const val NOTIFICATION_ID = 1002
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForeground(NOTIFICATION_ID, buildNotification())
            ACTION_STOP  -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Status Bar Zones active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
}
