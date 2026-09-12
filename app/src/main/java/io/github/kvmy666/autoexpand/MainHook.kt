package io.github.kvmy666.autoexpand

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.kvmy666.autoexpand.hook.GlobalSearchHook
import io.github.kvmy666.autoexpand.hook.KeepScreenOnController
import io.github.kvmy666.autoexpand.hook.NotifProbe
import io.github.kvmy666.autoexpand.hook.NotificationExpander
import io.github.kvmy666.autoexpand.hook.notif.NotifEngineV2
import io.github.kvmy666.autoexpand.hook.PrefsBridge
import io.github.kvmy666.autoexpand.hook.SystemColorHook
import io.github.kvmy666.autoexpand.hook.ZonesHook

class MainHook : IXposedHookLoadPackage {

    /** Shared prefs/IPC reader; owns the captured app context (see PrefsBridge). */
    private val prefs = PrefsBridge()

    /** Phase D — global-search Enter/Go launches the first result. */
    private val globalSearch = GlobalSearchHook(prefs)

    /** Status-bar zones (taps/long-press) + privileged-action receiver, in SystemUI. */
    private val zones = ZonesHook(prefs)

    /** Keep-screen-on overlay + its live PREF_CHANGED receiver, in SystemUI. */
    private val keepScreenOn = KeepScreenOnController(prefs)

    /** OxygenOS "Custom color" fix — mirrors the custom palette into the OEM type accent. */
    private val systemColor = SystemColorHook(prefs)

    /** All notification expand/collapse behavior + the SystemUI notification hooks. */
    private val notif = NotificationExpander(prefs)

    /** Read-only diagnostic probe; inert unless notif_probe_enabled is ON. */
    private val notifProbe = NotifProbe(prefs)

    /** Shade + lock-screen engine v2; installs only when notif_engine_v2 is ON. */
    private val notifV2 = NotifEngineV2(prefs)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Top-level safety net: any uncaught throwable must NOT propagate
        // to Zygote/system_server. Silent fail > bootloop.
        try {
            when (lpparam.packageName) {
                "com.android.systemui" -> try { handleSystemUi(lpparam) } catch (t: Throwable) {
                    Log.e("AutoExpand", "SystemUI hook init failed: $t")
                }
                "com.oppo.quicksearchbox" -> try { globalSearch.install(lpparam) } catch (t: Throwable) {
                    Log.e("TweaksLauncher", "Global search hook init failed: $t")
                }
                // Gboard + all other apps: the selection action bar is rendered
                // entirely by KeyboardHook (keyboard-side, no per-app injection needed).
            }
        } catch (t: Throwable) {
            Log.e("AutoExpand", "handleLoadPackage top-level threw: $t")
        }
    }

    // =====================================================
    // SystemUI hooks — notification tweaks only.
    // The module deliberately hooks NO system services: it is not in the
    // `android` (system_server) scope at all, so it cannot affect hardware
    // key handling. Snapper is triggered from its QS tile or edge button.
    // =====================================================

    private fun handleSystemUi(lpparam: XC_LoadPackage.LoadPackageParam) {

        // =====================================================
        // Capture SystemUI context + write module-active marker
        // =====================================================
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application", lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val app = param.thisObject as android.app.Application
                            prefs.appContext = app
                            Log.d("Snapper", "DIAG: SystemUI hook init — appContext captured, pkg=${app.packageName}")
                            prefs.loadFilePrefs()
                            prefs.startFileObserver()
                            prefs.startHeartbeatThread()
                            // Only now are prefs actually readable, so this is the earliest
                            // point the engine choice can be trusted. Still well before any
                            // notification row exists.
                            try {
                                if (prefs.isOptInEnabled("notif_engine_v2")) notifV2.install(lpparam)
                            } catch (t: Throwable) {
                                Log.e("AutoExpand", "notif engine v2 init failed: $t")
                            }
                            zones.registerReceiver(app)
                            keepScreenOn.registerPrefReceiver(app)
                            // Apply keep-screen-on from the persisted pref (default OFF).
                            keepScreenOn.apply(prefs.isOptInEnabled("keep_screen_on_enabled"))
                            // OxygenOS "Custom color" fix (opt-in, default OFF).
                            try { systemColor.install(app) } catch (t: Throwable) {
                                Log.e("AutoExpand", "system color fix init failed: $t")
                            }
                            // Legacy: write Settings.Global marker for OnePlus backward compat
                            try {
                                android.provider.Settings.Global.putString(
                                    app.contentResolver, "autoexpand_active",
                                    System.currentTimeMillis().toString()
                                )
                            } catch (_: Throwable) {}
                        } catch (e: Throwable) {
                            Log.d("Snapper", "DIAG: SystemUI hook init FAILED: $e")
                        }
                        try { prefs.reloadIfStale() } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}

        // Notification expand/collapse hooks (single + grouped, shade/LS/heads-up).
        // Heads-up and swipe-to-toggle always come from here; the shade/lock-screen drivers
        // inside it stand down when v2 is enabled.
        notif.install(lpparam)

        // Engine v2 is installed from the Application.onCreate hook instead, because the pref
        // that selects it is only readable once prefs.loadFilePrefs() has run.

        // Read-only state probe. Installed always, active only when the pref is ON.
        try { notifProbe.install(lpparam) } catch (t: Throwable) {
            Log.e("AutoExpand", "notif probe init failed: $t")
        }

        // =====================================================
        // BACK GESTURE HAPTIC
        // =====================================================

        try {
            XposedHelpers.findAndHookMethod(
                "com.oplus.systemui.navigationbar.gesture.VibrationHelper", lpparam.classLoader,
                "doVibrateCustomized",
                android.content.Context::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.isFeatureEnabled("disable_back_haptic_enabled")) return
                        param.result = null
                    }
                }
            )
        } catch (_: Throwable) {}

        zones.install(lpparam)
    }
}
