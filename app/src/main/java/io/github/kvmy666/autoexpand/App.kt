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
        // Test channels — used only by the debug-build test-notification sender.
        // The two channels above are IMPORTANCE_LOW and so can never produce a
        // heads-up banner; expand testing needs both a HIGH and a LOW channel.
        if (BuildConfig.DEBUG) {
            manager.createNotificationChannel(NotificationChannel(
                TestNotifier.CHANNEL_HIGH,
                "Test notifications (heads-up)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Debug-only: fires heads-up test notifications" })
            manager.createNotificationChannel(NotificationChannel(
                TestNotifier.CHANNEL_LOW,
                "Test notifications (silent)",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Debug-only: shade-only test notifications, no banner" })
        }
    }
}
