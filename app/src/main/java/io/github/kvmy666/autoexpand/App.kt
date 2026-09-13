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
        // Live triggers (theme-settings observer + toggle receiver) before the first apply,
        // so a colour picked while this process is alive is picked up within a second.
        CouiAccentFix.install(this)
        reapplyCouiAccentFix()
    }

    /**
     * OxygenOS "Custom colour" fix. Runs here rather than in the hooks because it needs `su`,
     * which only the app process reliably has — see [CouiAccentFix]. Off the main thread: it
     * spawns several `su` shells and restarts the OEM apps that were already running.
     */
    private fun reapplyCouiAccentFix() {
        Thread {
            try {
                CouiAccentFix.apply(this)
            } catch (t: Throwable) {
                android.util.Log.d("Snapper", "DIAG: CouiAccent startup apply failed: $t")
            }
        }.apply {
            isDaemon = true
            name = "coui-accent-fix"
        }.start()
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
