package io.github.kvmy666.autoexpand

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.kvmy666.autoexpand.hook.GlobalSearchHook
import io.github.kvmy666.autoexpand.hook.KeepScreenOnController
import io.github.kvmy666.autoexpand.hook.LauncherDrawerHook
import io.github.kvmy666.autoexpand.hook.NotificationExpander
import io.github.kvmy666.autoexpand.hook.PrefsBridge
import io.github.kvmy666.autoexpand.hook.SnapperChordHook
import io.github.kvmy666.autoexpand.hook.ZonesHook

class MainHook : IXposedHookLoadPackage {

    /** Shared prefs/IPC reader; owns the captured app context (see PrefsBridge). */
    private val prefs = PrefsBridge()

    /** Phase D — global-search Enter/Go launches the first result. */
    private val globalSearch = GlobalSearchHook(prefs)

    /** Launcher drawer auto-open keyboard, IME sync, Enter/Go launches first result, auto-open single result, keep app grid before typing, reopen search at top. */
    private val launcherDrawer = LauncherDrawerHook(prefs)

    /** Snapper hardware chord (Power + Volume-Down), installed in system_server. */
    private val snapperChord = SnapperChordHook(prefs)

    /** Status-bar zones (taps/long-press) + privileged-action receiver, in SystemUI. */
    private val zones = ZonesHook(prefs)

    /** Keep-screen-on overlay + its live PREF_CHANGED receiver, in SystemUI. */
    private val keepScreenOn = KeepScreenOnController(prefs)

    /** All notification expand/collapse behavior + the SystemUI notification hooks. */
    private val notif = NotificationExpander(prefs)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Top-level safety net: any uncaught throwable must NOT propagate
        // to Zygote/system_server. Silent fail > bootloop.
        try {
            when (lpparam.packageName) {
                "android"              -> try { handleSystemServer(lpparam) } catch (t: Throwable) {
                    Log.e("AutoExpand", "system_server hook init failed: $t")
                }
                "com.android.systemui" -> try { handleSystemUi(lpparam) } catch (t: Throwable) {
                    Log.e("AutoExpand", "SystemUI hook init failed: $t")
                }
                "com.oppo.quicksearchbox" -> try { globalSearch.install(lpparam) } catch (t: Throwable) {
                    Log.e("TweaksLauncher", "Global search hook init failed: $t")
                }
                "com.android.launcher" -> try { launcherDrawer.install(lpparam) } catch (t: Throwable) {
                    Log.e("TweaksLauncher", "Launcher drawer hook init failed: $t")
                }
                // Gboard + all other apps: the selection action bar is rendered
                // entirely by KeyboardHook (keyboard-side, no per-app injection needed).
            }
        } catch (t: Throwable) {
            Log.e("AutoExpand", "handleLoadPackage top-level threw: $t")
        }
    }

    // =====================================================
    // system_server — Snapper hardware chord (Power + Volume-Down).
    // Delegated to SnapperChordHook; see that class for the full
    // two-hook strategy and safety contract.
    // =====================================================
    private fun handleSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            snapperChord.install(lpparam)
        } catch (t: Throwable) {
            Log.e("Snapper", "handleSystemServer crashed: $t")
        }
    }

    // =====================================================
    // SystemUI hooks — notification tweaks only
    // Screenshot hooks removed: OxygenOS 16 routes screenshots through
    // com.oplus.exsystemservice / com.oplus.screenshot (not SystemUI).
    // Interception is handled in system_server via handleSystemServer().
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
                            zones.registerReceiver(app)
                            keepScreenOn.registerPrefReceiver(app)
                            // Apply keep-screen-on from the persisted pref (default OFF).
                            keepScreenOn.apply(prefs.isOptInEnabled("keep_screen_on_enabled"))
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
        notif.install(lpparam)

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
