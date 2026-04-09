package io.github.kvmy666.autoexpand

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            SnapperService.CHANNEL_ID,
            "Screen Snapper",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the Screen Snapper service running"
            setShowBadge(false)
        })
        manager.createNotificationChannel(NotificationChannel(
            StatusBarZonesService.CHANNEL_ID,
            "Status Bar Zones",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the tap zones service running"
            setShowBadge(false)
        })
    }
}
