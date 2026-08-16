package io.github.kvmy666.autoexpand.hook

import android.util.Log
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicInteger

/**
 * Read-only diagnostic probe. Mutates nothing — it exists purely to turn "the notification
 * collapsed for no reason" into a timestamped log line showing exactly which state flags
 * were set when it happened.
 *
 * Gated behind `notif_probe_enabled` (opt-in, default OFF) because it hooks `onLayout`,
 * which is extremely hot. When the pref is off the hook body costs one map lookup.
 *
 * Three things get captured:
 *  1. per-row state on layout (throttled, and only when the state actually changed)
 *  2. a stack trace for the first few `setUserExpanded` calls — reveals *who* is expanding
 *     or collapsing a row, which is what separates our writes from the user's and from
 *     the OEM's locked-shade transition
 *  3. keyguard transitions via `setOnKeyguard`
 *
 * Read with: `adb logcat -s AENotifProbe:D`
 */
class NotifProbe(private val prefs: PrefsBridge) {

    private companion object {
        const val TAG = "AENotifProbe"
        const val ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
        /** Stack traces are huge; a handful is enough to identify a call path. */
        const val MAX_TRACES = 8
        const val THROTTLE_MS = 250L
    }

    private val tracesLogged = AtomicInteger(0)

    private fun on(): Boolean = try { prefs.isOptInEnabled("notif_probe_enabled") } catch (_: Throwable) { false }

    private fun b(o: Any, f: String): String =
        try { XposedHelpers.getBooleanField(o, f).toString() } catch (_: Throwable) { "?" }

    private fun call(o: Any, m: String, vararg a: Any): String =
        try { XposedHelpers.callMethod(o, m, *a)?.toString() ?: "null" } catch (_: Throwable) { "?" }

    private fun keyOf(row: Any): String = try {
        val entry = try { XposedHelpers.callMethod(row, "getEntry") }
                    catch (_: Throwable) { XposedHelpers.callMethod(row, "getEntryLegacy") }
        (XposedHelpers.callMethod(entry, "getKey") as? String) ?: "?"
    } catch (_: Throwable) { "?" }

    private fun childCount(row: Any): String = try {
        val c = XposedHelpers.getObjectField(row, "mChildrenContainer")
        if (c == null) "0" else call(c, "getNotificationChildCount")
    } catch (_: Throwable) { "?" }

    /** One compact line describing everything that decides whether a row renders expanded. */
    private fun snapshot(row: Any): String = buildString {
        append("hu=").append(b(row, "mIsHeadsUp"))
        append(" kg=").append(b(row, "mOnKeyguard"))
        append(" child=").append(call(row, "isChildInGroup"))
        append(" summaryKids=").append(call(row, "isSummaryWithChildren"))
        append(" kids=").append(childCount(row))
        append(" userExp=").append(b(row, "mUserExpanded"))
        append(" userChanged=").append(b(row, "mHasUserChangedExpansion"))
        append(" sysExp=").append(b(row, "mIsSystemExpanded"))
        append(" sysChildExp=").append(b(row, "mIsSystemChildExpanded"))
        append(" kidsExp=").append(b(row, "mChildrenExpanded"))
        append(" pinned=").append(call(row, "isPinned"))
        append(" isExp(F)=").append(call(row, "isExpanded", false))
        append(" isExp(T)=").append(call(row, "isExpanded", true))
        append(" showingExp=").append(call(row, "isShowingExpanded"))
        append(" h=").append(call(row, "getIntrinsicHeight"))
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val rowClass = try { XposedHelpers.findClass(ROW, lpparam.classLoader) } catch (t: Throwable) {
            Log.e(TAG, "row class not found: $t"); return
        }

        // 1. Per-row state on every layout — throttled, and only when the snapshot changed.
        try {
            XposedBridge.hookAllMethods(rowClass, "onLayout", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!on()) return
                    try {
                        val row = param.thisObject
                        if ((row as? View)?.isShown != true) return
                        val now = android.os.SystemClock.uptimeMillis()
                        val last = XposedHelpers.getAdditionalInstanceField(row, "probeTs") as? Long ?: 0L
                        if (now - last < THROTTLE_MS) return
                        val snap = snapshot(row)
                        val prev = XposedHelpers.getAdditionalInstanceField(row, "probeSnap") as? String
                        if (snap == prev) return
                        XposedHelpers.setAdditionalInstanceField(row, "probeTs", now)
                        XposedHelpers.setAdditionalInstanceField(row, "probeSnap", snap)
                        Log.d(TAG, "layout key=${keyOf(row)} $snap")
                    } catch (_: Throwable) {}
                }
            })
        } catch (t: Throwable) { Log.e(TAG, "onLayout probe failed: $t") }

        // 2. Who calls setUserExpanded? Separates our writes from real user taps and from
        //    the OEM locked-shade transition.
        try {
            XposedBridge.hookAllMethods(rowClass, "setUserExpanded", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!on()) return
                    try {
                        val args = param.args.joinToString(",")
                        Log.d(TAG, "setUserExpanded($args) key=${keyOf(param.thisObject)}")
                        if (tracesLogged.get() < MAX_TRACES) {
                            tracesLogged.incrementAndGet()
                            Log.d(TAG, "  caller:\n" + Log.getStackTraceString(Throwable("setUserExpanded")))
                        }
                    } catch (_: Throwable) {}
                }
            })
        } catch (t: Throwable) { Log.e(TAG, "setUserExpanded probe failed: $t") }

        // 3. Keyguard transitions — the state change behind both lock-screen bugs.
        try {
            XposedBridge.hookAllMethods(rowClass, "setOnKeyguard", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!on()) return
                    try {
                        Log.d(TAG, "setOnKeyguard(${param.args.getOrNull(0)}) key=${keyOf(param.thisObject)} ${snapshot(param.thisObject)}")
                    } catch (_: Throwable) {}
                }
            })
        } catch (t: Throwable) { Log.e(TAG, "setOnKeyguard probe failed: $t") }

        Log.d(TAG, "probe installed (enabled=${on()})")
    }
}
