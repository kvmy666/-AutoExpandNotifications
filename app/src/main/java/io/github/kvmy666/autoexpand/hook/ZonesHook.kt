package io.github.kvmy666.autoexpand.hook

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.kvmy666.autoexpand.ActionDispatcher
import io.github.kvmy666.autoexpand.ZoneAction

/**
 * Status-bar zones — tap/double/triple-tap + long-press on the left/right edges of the
 * status bar dispatch configurable actions. Touch detection finds the STATUS_BAR window at
 * runtime (OEM-agnostic) via WindowManagerImpl.addView. Also owns the privileged-action
 * receiver so toggles (wifi/bt/data/power-saver) run with system_server permissions.
 */
class ZonesHook(private val prefs: PrefsBridge) {

    // Created lazily so the Handler is built after Looper.prepareMainLooper() runs.
    private val zoneHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    @Volatile private var activeZoneSide: String? = null
    @Volatile private var zoneDownY = 0f
    @Volatile private var zoneDownRawY = 0f
    private val leftZoneTracker  by lazy { ZoneTapTracker("left") }
    private val rightZoneTracker by lazy { ZoneTapTracker("right") }

    private inner class ZoneTapTracker(private val side: String) {
        private var tapCount = 0
        private var longConsumed = false
        private var lpRunnable: Runnable? = null
        private var tapRunnable: Runnable? = null

        fun onDown(ctx: Context) {
            longConsumed = false
            tapRunnable?.let { zoneHandler.removeCallbacks(it) }
            lpRunnable?.let { zoneHandler.removeCallbacks(it) }
            val lp = Runnable {
                longConsumed = true
                tapCount = 0
                dispatchZoneGesture(side, "long_press", ctx)
            }
            lpRunnable = lp
            zoneHandler.postDelayed(lp, 500L)
        }

        fun onUp(ctx: Context) {
            lpRunnable?.let { zoneHandler.removeCallbacks(it); lpRunnable = null }
            if (longConsumed) { longConsumed = false; return }
            tapCount++
            val count = tapCount
            tapRunnable?.let { zoneHandler.removeCallbacks(it) }
            val tr = Runnable {
                tapCount = 0
                val suffix = when { count >= 3 -> "triple_tap"; count == 2 -> "double_tap"; else -> "single_tap" }
                dispatchZoneGesture(side, suffix, ctx)
            }
            tapRunnable = tr
            zoneHandler.postDelayed(tr, 300L)
        }

        fun cancel() {
            lpRunnable?.let  { zoneHandler.removeCallbacks(it); lpRunnable  = null }
            tapRunnable?.let { zoneHandler.removeCallbacks(it); tapRunnable = null }
            tapCount = 0
            longConsumed = false
        }
    }

    private fun dispatchZoneGesture(side: String, suffix: String, ctx: Context) {
        val actionKey    = prefs.getStringPref("zones_${side}_${suffix}_action", "no_action")
        if (actionKey == "no_action") return
        val pkg          = prefs.getStringPref("zones_${side}_open_app_pkg", "")
        val shortcutData = prefs.getStringPref("zones_${side}_${suffix}_shortcut", "")
        val action       = ZoneAction.fromKey(actionKey, pkg, shortcutData)
        Log.d("Zones", "hook gesture $side/$suffix → $actionKey")
        // Write hook-side diag for non-technical user debugging (read by DebugLogHelper)
        if (actionKey == "launch_shortcut") {
            try {
                java.io.File("/data/local/tmp/tweaks_hook_diag.txt").writeText(
                    "gesture=$side/$suffix\nactionKey=$actionKey\n" +
                    "shortcutData=$shortcutData\nactionType=${action::class.simpleName}\n"
                )
            } catch (_: Throwable) {}
        }
        if (prefs.getStringPref("zones_haptic_enabled", "1") == "1") {
            try {
                val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE)
                        as android.os.Vibrator
                val effect = if (suffix == "long_press")
                    android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_HEAVY_CLICK)
                else
                    android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK)
                vib.vibrate(effect)
            } catch (_: Throwable) {}
        }
        ActionDispatcher.dispatch(action, ctx)
    }

    private fun handleZoneTouch(ev: MotionEvent, view: android.view.View, ctx: Context) {
        if (!prefs.isFeatureEnabled("zones_enabled")) return
        val x = ev.x
        val y = ev.y
        val h = view.height.takeIf { it > 0 } ?: return

        val screenW    = view.resources.displayMetrics.widthPixels
        val leftW      = (screenW * prefs.getIntPref("zones_left_width_pct",  25) / 100f).toInt()
        val rightStart = screenW - (screenW * prefs.getIntPref("zones_right_width_pct", 25) / 100f).toInt()

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (y > h) return
                zoneDownY = y
                zoneDownRawY = ev.rawY
                activeZoneSide = when {
                    x < leftW      -> "left"
                    x > rightStart -> "right"
                    else           -> null
                }
                when (activeZoneSide) {
                    "left"  -> leftZoneTracker.onDown(ctx)
                    "right" -> rightZoneTracker.onDown(ctx)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeZoneSide != null &&
                    (y > h || kotlin.math.abs(y - zoneDownY) > 20 || kotlin.math.abs(ev.rawY - zoneDownRawY) > 20)
                ) {
                    leftZoneTracker.cancel(); rightZoneTracker.cancel(); activeZoneSide = null
                }
            }
            MotionEvent.ACTION_UP -> {
                when (activeZoneSide) {
                    "left"  -> leftZoneTracker.onUp(ctx)
                    "right" -> rightZoneTracker.onUp(ctx)
                }
                activeZoneSide = null
            }
            MotionEvent.ACTION_CANCEL -> {
                leftZoneTracker.cancel(); rightZoneTracker.cancel(); activeZoneSide = null
            }
        }
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook WindowManagerImpl.addView to intercept STATUS_BAR window creation —
        // avoids depending on OEM-specific class names like OplusPhoneStatusBarView.
        try {
            XposedHelpers.findAndHookMethod(
                "android.view.WindowManagerImpl", lpparam.classLoader,
                "addView",
                android.view.View::class.java,
                android.view.ViewGroup.LayoutParams::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view   = param.args[0] as? android.view.View ?: return
                            val lp     = param.args[1] as? android.view.WindowManager.LayoutParams ?: return
                            if (lp.type != android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR) return

                            Log.d("Zones", "STATUS_BAR view attached: ${view.javaClass.name}")
                            val ctx = view.context ?: prefs.appContext ?: return
                            view.setOnTouchListener { v, ev ->
                                try { handleZoneTouch(ev, v, ctx) } catch (t: Throwable) { Log.e("Zones", "touch: $t") }
                                false // don't consume — status bar swipe still works
                            }
                            Log.d("Zones", "zone OnTouchListener installed on status bar")
                        } catch (t: Throwable) {
                            Log.e("Zones", "addView hook error: $t")
                        }
                    }
                }
            )
            Log.d("Zones", "WindowManagerImpl.addView hook installed")
        } catch (e: Throwable) {
            Log.e("Zones", "WindowManagerImpl.addView hook failed: $e")
        }

        // Also try direct class name hook as secondary path
        val candidates = listOf(
            "com.android.systemui.statusbar.phone.PhoneStatusBarView",
            "com.oplus.systemui.statusbar.phone.OplusPhoneStatusBarView",
            "com.android.systemui.statusbar.phone.StatusBarRootView"
        )
        for (cls in candidates) {
            try {
                XposedHelpers.findAndHookMethod(
                    cls, lpparam.classLoader,
                    "dispatchTouchEvent", MotionEvent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val ev   = param.args[0] as MotionEvent
                                val view = param.thisObject as android.view.View
                                val ctx  = view.context ?: prefs.appContext ?: return
                                handleZoneTouch(ev, view, ctx)
                            } catch (t: Throwable) {
                                Log.e("Zones", "dispatchTouchEvent hook: $t")
                            }
                        }
                    }
                )
                Log.d("Zones", "zone dispatchTouchEvent hook installed on $cls")
                break
            } catch (_: Throwable) {
                Log.d("Zones", "zone hook skip $cls")
            }
        }

        // Fallback: dynamically hook dispatchTouchEvent on the discovered STATUS_BAR view class
        try {
            XposedHelpers.findAndHookMethod(
                "android.view.WindowManagerImpl", lpparam.classLoader,
                "addView",
                android.view.View::class.java,
                android.view.ViewGroup.LayoutParams::class.java,
                object : XC_MethodHook() {
                    private var hooked = false
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (hooked) return
                        try {
                            val view = param.args[0] as? android.view.View ?: return
                            val lp   = param.args[1] as? android.view.WindowManager.LayoutParams ?: return
                            if (lp.type != android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR) return
                            val cls  = view.javaClass
                            // Hook dispatchTouchEvent on the actual runtime class
                            val method = cls.getMethod("dispatchTouchEvent", MotionEvent::class.java)
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param2: MethodHookParam) {
                                    try {
                                        val ev   = param2.args[0] as MotionEvent
                                        val view2 = param2.thisObject as android.view.View
                                        val ctx2  = view2.context ?: prefs.appContext ?: return
                                        handleZoneTouch(ev, view2, ctx2)
                                    } catch (t: Throwable) { Log.e("Zones", "dynamic hook: $t") }
                                }
                            })
                            hooked = true
                            Log.d("Zones", "dynamic dispatchTouchEvent hook installed on ${cls.name}")
                        } catch (t: Throwable) {
                            Log.e("Zones", "dynamic hook setup: $t")
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    fun registerReceiver(app: android.app.Application) {
        try {
            val filter = android.content.IntentFilter(ActionDispatcher.ACTION_PRIVILEGED)
            app.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: android.content.Intent) {
                    try {
                        val key = intent.getStringExtra(ActionDispatcher.EXTRA_ACTION_KEY) ?: return
                        handlePrivilegedZoneAction(ctx, key)
                    } catch (t: Throwable) {
                        Log.e("Zones", "privileged action failed: $t")
                    }
                }
            }, filter, Context.RECEIVER_NOT_EXPORTED)
            Log.d("Zones", "zone privileged action receiver registered")
        } catch (t: Throwable) {
            Log.e("Zones", "zone receiver registration failed: $t")
        }
    }

    @Suppress("DEPRECATION")
    private fun handlePrivilegedZoneAction(ctx: Context, key: String) {
        when (key) {
            "toggle_wifi" -> {
                try {
                    val wm = ctx.getSystemService(Context.WIFI_SERVICE)
                        as android.net.wifi.WifiManager
                    wm.setWifiEnabled(!wm.isWifiEnabled)
                } catch (t: Throwable) { Log.e("Zones", "wifi toggle: $t") }
            }
            "toggle_bluetooth" -> {
                try {
                    val ba = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
                    if (ba.isEnabled) ba.disable() else ba.enable()
                } catch (t: Throwable) { Log.e("Zones", "bt toggle: $t") }
            }
            "toggle_mobile_data" -> {
                try {
                    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
                        as android.telephony.TelephonyManager
                    XposedHelpers.callMethod(tm, "setDataEnabled", !tm.isDataEnabled)
                } catch (t: Throwable) { Log.e("Zones", "mobile data toggle: $t") }
            }
            "toggle_power_saver" -> {
                try {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE)
                        as android.os.PowerManager
                    XposedHelpers.callMethod(pm, "setPowerSaveModeEnabled", !pm.isPowerSaveMode)
                } catch (t: Throwable) { Log.e("Zones", "power saver toggle: $t") }
            }
        }
    }
}
