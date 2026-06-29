package io.github.kvmy666.autoexpand.hook

import android.content.Context
import android.util.Log
import android.view.View

/**
 * Keep-screen-on (System Behavior): a 1x1 invisible overlay window carrying
 * FLAG_KEEP_SCREEN_ON, hosted in the always-alive SystemUI process. Also owns the
 * PREF_CHANGED receiver so the overlay is added/removed live (no reboot) when the toggle
 * changes.
 */
class KeepScreenOnController(private val prefs: PrefsBridge) {

    @Volatile private var keepScreenOnView: View? = null

    // Posts overlay add/remove onto the main looper (WindowManager requires a Looper thread).
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * Add/remove the invisible keep-screen-on overlay in the SystemUI process.
     * Idempotent: a single 1x1 TYPE_APPLICATION_OVERLAY window with FLAG_KEEP_SCREEN_ON
     * keeps the display awake for as long as it's attached (works on lockscreen too).
     */
    fun apply(enabled: Boolean) {
        val ctx = prefs.appContext ?: return
        handler.post {
            try {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                if (enabled) {
                    if (keepScreenOnView != null) return@post
                    val v = View(ctx)
                    val lp = android.view.WindowManager.LayoutParams(
                        1, 1,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT
                    )
                    lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    lp.x = 0; lp.y = 0
                    wm.addView(v, lp)
                    keepScreenOnView = v
                    Log.d("Snapper", "DIAG: keepScreenOn overlay ADDED")
                } else {
                    keepScreenOnView?.let {
                        try { wm.removeView(it) } catch (_: Throwable) {}
                        keepScreenOnView = null
                        Log.d("Snapper", "DIAG: keepScreenOn overlay REMOVED")
                    }
                }
            } catch (t: Throwable) {
                Log.d("Snapper", "DIAG: keepScreenOn apply failed: $t")
            }
        }
    }

    /**
     * Live pref updates from the app (no reboot). The app sends
     * io.github.kvmy666.autoexpand.PREF_CHANGED (key/value extras) on every toggle.
     * We refresh the cache and apply behaviors that need an explicit trigger
     * (currently keep-screen-on, which adds/removes its overlay window here).
     */
    fun registerPrefReceiver(app: android.app.Application) {
        try {
            val filter = android.content.IntentFilter("io.github.kvmy666.autoexpand.PREF_CHANGED")
            app.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                    try {
                        prefs.loadFilePrefs()
                        val key = intent.getStringExtra("key") ?: return
                        val value = intent.getStringExtra("value")
                        if (key == "keep_screen_on_enabled") {
                            apply(value == "1")
                        }
                    } catch (t: Throwable) {
                        Log.d("Snapper", "DIAG: PREF_CHANGED receiver failed: $t")
                    }
                }
            }, filter, android.content.Context.RECEIVER_EXPORTED)
            Log.d("Snapper", "DIAG: PREF_CHANGED receiver registered")
        } catch (t: Throwable) {
            Log.d("Snapper", "DIAG: PREF_CHANGED receiver registration failed: $t")
        }
    }
}
