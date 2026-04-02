package io.github.kvmy666.autoexpand

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    private var appContext: Context? = null  // captured from SystemUI process
    private val PROVIDER_URI = Uri.parse("content://io.github.kvmy666.autoexpand.prefs")

    // Cached prefs
    private var cachedBoolPrefs = mutableMapOf<String, Boolean>()
    private var cachedExcludedApps = emptySet<String>()
    private var lastCacheTime = 0L
    private val CACHE_INTERVAL_MS = 2000L

    // F1: Swipe-down direction tracking state
    @Volatile private var f1DownStartY = 0f
    @Volatile private var f1IsDownwardSwipe = false
    @Volatile private var f1HasToggled = false
    @Volatile private var f1SwipeTime = 0L
    @Volatile private var f1CurrentRow: Any? = null

    // Guard: suppress setExpandedWhenPinned sync during setHeadsUp init
    @Volatile private var inSetHeadsUp = false

    private fun refreshCacheIfNeeded() {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (now - lastCacheTime < CACHE_INTERVAL_MS) return
        lastCacheTime = now

        try {
            val cursor = ctx.contentResolver.query(PROVIDER_URI, null, null, null, null)
            if (cursor != null) {
                val newBools = mutableMapOf<String, Boolean>()
                var newExcluded = emptySet<String>()

                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    val type = cursor.getString(1)
                    val value = cursor.getString(2)
                    when (type) {
                        "bool" -> newBools[key] = value == "1"
                        "string_set" -> if (key == "excluded_apps") {
                            newExcluded = if (value.isEmpty()) emptySet()
                            else value.split("\n").toSet()
                        }
                    }
                }
                cursor.close()

                cachedBoolPrefs = newBools
                cachedExcludedApps = newExcluded
            }
        } catch (_: Throwable) {}
    }

    private fun isFeatureEnabled(key: String): Boolean {
        refreshCacheIfNeeded()
        return cachedBoolPrefs[key] ?: true
    }

    private fun getExcludedApps(): Set<String> {
        refreshCacheIfNeeded()
        return cachedExcludedApps
    }

    private fun getNotificationPackage(row: Any): String? {
        return try {
            val entry = XposedHelpers.callMethod(row, "getEntry")
            val sbn = XposedHelpers.callMethod(entry, "getSbn")
            XposedHelpers.callMethod(sbn, "getPackageName") as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun getNotificationPackageFromContentView(view: Any): String? {
        return try {
            val row = XposedHelpers.getObjectField(view, "mContainingNotification")
            if (row != null) getNotificationPackage(row) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun shouldSkipNotification(featureKey: String, pkg: String?): Boolean {
        if (!isFeatureEnabled(featureKey)) return true
        if (pkg != null && pkg in getExcludedApps()) return true
        return false
    }

    /**
     * Toggle heads-up notification expand/collapse state.
     * Directly sets mActualHeight to force the card to resize,
     * since notifyHeightChanged alone doesn't resize the OPlus floating window.
     */
    private fun toggleHeadsUpExpandState(row: Any) {
        try {
            val isCollapsed = XposedHelpers.getAdditionalInstanceField(row, "aeCollapsed") as? Boolean ?: false
            val newExpanded = isCollapsed // collapsed→expand, expanded→collapse

            // Set state FIRST so our height hooks return the correct values
            XposedHelpers.setAdditionalInstanceField(row, "aeCollapsed", !newExpanded)
            XposedHelpers.setBooleanField(row, "mExpandedWhenPinned", newExpanded)

            // Query the correct target height (our hooks will use updated state)
            val targetHeight = XposedHelpers.callMethod(row, "getIntrinsicHeight") as Int

            // Directly set the actual visual height on the row
            // setActualHeight internally: mActualHeight = h, updateClipping(), notifyHeightChanged()
            XposedHelpers.callMethod(row, "setActualHeight", targetHeight)

            // Force layout pass on row and parent (OPlus window)
            (row as? View)?.let { rowView ->
                rowView.requestLayout()
                (rowView.parent as? View)?.requestLayout()
            }
        } catch (_: Throwable) {}
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android"              -> handleSystemServer(lpparam)
            "com.android.systemui" -> handleSystemUi(lpparam)
        }
    }

    // =====================================================
    // system_server — SAFE interceptKeyBeforeQueueing hook
    //
    // Intercepts the Power+VolumeDown chord BEFORE KeyCombinationManager
    // detects it. Consuming KEYCODE_VOLUME_DOWN while Power is held prevents
    // the native KeyCombinationManager chord rule from firing at all.
    //
    // SAFETY CONTRACT: entire hook body is in try/catch(Throwable).
    // On ANY exception we log and return — the original method is never
    // blocked by our code, so system_server cannot deadlock.
    // =====================================================

    /** Tracks whether KEYCODE_POWER is currently held down. Volatile for thread safety. */
    @Volatile private var pwmPowerDown = false

    private fun handleSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        // OxygenOS 16 uses PhoneWindowManager (standard AOSP class in system_server).
        // From logcat: "KEYLOG_PhoneWindowManager: interceptKeyBeforeQueueing" fires before
        // KeyCombinationManager detects the chord — this is our safe intercept point.
        val pwmCandidates = listOf(
            "com.android.server.policy.PhoneWindowManager",
            "com.oplus.server.policy.OplusPhoneWindowManager"
        )

        for (cls in pwmCandidates) {
            try {
                XposedHelpers.findAndHookMethod(
                    cls, lpparam.classLoader,
                    "interceptKeyBeforeQueueing",
                    KeyEvent::class.java, Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // ── CRITICAL SAFETY BOUNDARY ────────────────────────────────────────
                            // Everything inside this try/catch. If we throw, original runs safely.
                            try {
                                val ev = param.args[0] as? KeyEvent ?: return

                                when (ev.keyCode) {
                                    KeyEvent.KEYCODE_POWER -> {
                                        // Track power-key state — never consume it, just observe
                                        pwmPowerDown = (ev.action == KeyEvent.ACTION_DOWN)
                                    }
                                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                        if (ev.action == KeyEvent.ACTION_DOWN && pwmPowerDown) {
                                            // ── Bypass check ────────────────────────────────
                                            // "Full Screenshot" button writes this file so the
                                            // native screenshot can go through for that one press.
                                            val bypass = java.io.File("/data/local/tmp/.snapper_bypass")
                                            if (bypass.exists()) {
                                                try { bypass.delete() } catch (_: Throwable) {}
                                                Log.d("JeezSnapper", "bypass flag — native screenshot allowed")
                                                return  // let original handle it
                                            }

                                            // ── Consume the chord ───────────────────────────
                                            // Return value 0 means the event is consumed and NOT
                                            // passed to apps or KeyCombinationManager internals.
                                            param.result = 0

                                            // ── Launch SnapperService ────────────────────────
                                            // Get context from PhoneWindowManager's mContext field.
                                            val ctx: Context? = try {
                                                XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                                            } catch (_: Throwable) { null }

                                            if (ctx != null) {
                                                try {
                                                    ctx.startForegroundService(
                                                        Intent("io.github.kvmy666.autoexpand.ACTION_CAPTURE").apply {
                                                            component = ComponentName(
                                                                "io.github.kvmy666.autoexpand",
                                                                "io.github.kvmy666.autoexpand.SnapperService"
                                                            )
                                                        }
                                                    )
                                                    Log.d("JeezSnapper",
                                                        "P+VolDown chord consumed [$cls] → SnapperService launched")
                                                } catch (e: Throwable) {
                                                    Log.e("JeezSnapper", "startForegroundService failed: ${e.message}")
                                                    // Restore so the native screenshot still works
                                                    param.result = null
                                                }
                                            } else {
                                                Log.e("JeezSnapper", "mContext null on $cls — not blocking chord")
                                                param.result = null  // restore — don't silently eat the key
                                            }
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                // ── DEADLOCK PREVENTION ──────────────────────────────────────────
                                // Log the error and clear any partial result so the original method
                                // can run normally. System never freezes from our hook.
                                Log.e("JeezSnapper", "interceptKeyBeforeQueueing hook threw: $t")
                                param.result = null
                            }
                        }
                    }
                )
                XposedBridge.log("Snapper: SAFE interceptKeyBeforeQueueing hook registered on $cls")
                break  // first class that hooks successfully is enough
            } catch (e: Throwable) {
                XposedBridge.log("Snapper: cannot hook $cls.interceptKeyBeforeQueueing: ${e.message}")
            }
        }
    }

    // =====================================================
    // SystemUI hooks — notification tweaks only
    // Screenshot hooks removed: OxygenOS 16 routes screenshots through
    // com.oplus.exsystemservice / com.oplus.screenshot (not SystemUI).
    // Interception is handled in system_server via handleSystemServer().
    // =====================================================

    private fun handleSystemUi(lpparam: XC_LoadPackage.LoadPackageParam) {

        val rowClass = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
        val contentViewClass = "com.android.systemui.statusbar.notification.row.NotificationContentView"

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
                            appContext = app
                            android.provider.Settings.Global.putString(
                                app.contentResolver, "autoexpand_active",
                                System.currentTimeMillis().toString()
                            )
                        } catch (_: Throwable) {}
                        try { refreshCacheIfNeeded() } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}

        // =====================================================
        // SHADE HOOKS
        // =====================================================

        try {
            XposedHelpers.findAndHookMethod(
                rowClass, lpparam.classLoader,
                "setSystemExpanded", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = getNotificationPackage(param.thisObject)
                        val enabled = isFeatureEnabled("expand_shade_enabled")
                        val excluded = pkg != null && pkg in getExcludedApps()
                        if (!enabled || excluded) {
                            param.args[0] = false
                            return
                        }
                        param.args[0] = true
                    }
                }
            )
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(
                rowClass, lpparam.classLoader,
                "setSystemChildExpanded", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = getNotificationPackage(param.thisObject)
                        val enabled = isFeatureEnabled("expand_shade_enabled")
                        val excluded = pkg != null && pkg in getExcludedApps()
                        if (!enabled || excluded) {
                            param.args[0] = false
                            return
                        }
                        param.args[0] = true
                    }
                }
            )
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(
                rowClass, lpparam.classLoader,
                "setExpandable", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = getNotificationPackage(param.thisObject)
                        if (shouldSkipNotification("expand_shade_enabled", pkg)) return
                        param.args[0] = true
                    }
                }
            )
        } catch (_: Throwable) {}

        // =====================================================
        // LOCK SCREEN HOOK
        // =====================================================

        try {
            XposedHelpers.findAndHookMethod(
                rowClass, lpparam.classLoader,
                "setOnKeyguard", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = getNotificationPackage(param.thisObject)
                        if (shouldSkipNotification("expand_lockscreen_enabled", pkg)) return
                        param.args[0] = false
                    }
                }
            )
        } catch (_: Throwable) {}

        // =====================================================
        // HEADS-UP HOOKS — force expanded layout
        // Uses custom "aeCollapsed" field (via setAdditionalInstanceField)
        // to track expand/collapse state independently of mExpandedWhenPinned
        // timing. This avoids the race condition where layout methods run
        // before mExpandedWhenPinned is set.
        // =====================================================

        // calculateVisibleType — force expanded view type
        try {
            XposedHelpers.findAndHookMethod(
                contentViewClass, lpparam.classLoader,
                "calculateVisibleType",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val pkg = getNotificationPackageFromContentView(param.thisObject)
                            if (shouldSkipNotification("expand_headsup_enabled", pkg)) return
                            val isHeadsUp = XposedHelpers.getBooleanField(param.thisObject, "mIsHeadsUp")
                            val result = param.result as Int
                            if (isHeadsUp && result == 2) {
                                val row = XposedHelpers.getObjectField(param.thisObject, "mContainingNotification")
                                if (row != null) {
                                    val collapsed = XposedHelpers.getAdditionalInstanceField(row, "aeCollapsed") as? Boolean ?: false
                                    if (collapsed) return
                                }
                                val expandedChild = XposedHelpers.getObjectField(param.thisObject, "mExpandedChild")
                                if (expandedChild != null) {
                                    param.result = 1
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}

        // getIntrinsicHeight — force expanded height
        try {
            XposedHelpers.findAndHookMethod(
                rowClass, lpparam.classLoader,
                "getIntrinsicHeight",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val pkg = getNotificationPackage(param.thisObject)
                            if (shouldSkipNotification("expand_headsup_enabled", pkg)) return
                            val isHeadsUp = XposedHelpers.getBooleanField(param.thisObject, "mIsHeadsUp")
                            if (!isHeadsUp) return
                            val collapsed = XposedHelpers.getAdditionalInstanceField(param.thisObject, "aeCollapsed") as? Boolean ?: false
                            if (collapsed) return
                            val privateLayout = XposedHelpers.getObjectField(param.thisObject, "mPrivateLayout")
                                ?: return
                            val expandedChild = XposedHelpers.getObjectField(privateLayout, "mExpandedChild")
                                as? View ?: return
                            val expandedHeight = expandedChild.measuredHeight
                            if (expandedHeight <= 0) return
                            val currentHeight = param.result as Int
                            if (expandedHeight != currentHeight) {
                                param.result = expandedHeight
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}

        // getPinnedHeadsUpHeight — force expanded height for heads-up window sizing
        try {
            XposedHelpers.findAndHookMethod(
                rowClass, lpparam.classLoader,
                "getPinnedHeadsUpHeight", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val pkg = getNotificationPackage(param.thisObject)
                            if (shouldSkipNotification("expand_headsup_enabled", pkg)) return
                            val isHeadsUp = XposedHelpers.getBooleanField(param.thisObject, "mIsHeadsUp")
                            if (!isHeadsUp) return
                            val collapsed = XposedHelpers.getAdditionalInstanceField(param.thisObject, "aeCollapsed") as? Boolean ?: false
                            if (collapsed) return
                            val maxExpand = XposedHelpers.callMethod(param.thisObject, "getMaxExpandHeight") as Int
                            val current = param.result as Int
                            if (maxExpand > current) {
                                param.result = maxExpand
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}

        // setHeadsUp — mark notification for auto-expand on arrival
        try {
            XposedHelpers.findAndHookMethod(
                rowClass, lpparam.classLoader,
                "setHeadsUp", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[0] as Boolean) {
                            val pkg = getNotificationPackage(param.thisObject)
                            if (shouldSkipNotification("expand_headsup_enabled", pkg)) return
                            inSetHeadsUp = true
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, "aeCollapsed", false)
                            try { XposedHelpers.setBooleanField(param.thisObject, "mExpandedWhenPinned", true) } catch (_: Throwable) {}
                        }
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[0] as Boolean) {
                            inSetHeadsUp = false
                            val pkg = getNotificationPackage(param.thisObject)
                            if (shouldSkipNotification("expand_headsup_enabled", pkg)) return
                            try {
                                XposedHelpers.setBooleanField(param.thisObject, "mExpandedWhenPinned", true)
                                XposedHelpers.callMethod(param.thisObject, "notifyHeightChanged", false)
                            } catch (_: Throwable) {}
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // setExpandedWhenPinned — sync aeCollapsed BEFORE original runs
        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass(rowClass, lpparam.classLoader),
                "setExpandedWhenPinned",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (inSetHeadsUp) return
                        val value = param.args[0] as Boolean
                        XposedHelpers.setAdditionalInstanceField(param.thisObject, "aeCollapsed", !value)
                    }
                }
            )
        } catch (_: Throwable) {}

        // =====================================================
        // DISABLE HEADS-UP POPUP ON SWIPE DOWN
        // Track swipe direction passively, block app launch
        // on downward swipe and toggle expand/collapse instead
        // =====================================================

        val oplusHuTouchHelper = "com.oplus.systemui.notification.headsup.windowframe.OplusHeadsUpTouchHelper"
        try {
            XposedHelpers.findAndHookMethod(
                oplusHuTouchHelper, lpparam.classLoader,
                "onInterceptTouchEvent", MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isFeatureEnabled("disable_headsup_popup_enabled")) return
                        val ev = param.args[0] as MotionEvent
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                f1DownStartY = ev.rawY
                                f1IsDownwardSwipe = false
                                f1HasToggled = false
                                f1SwipeTime = 0L
                                f1CurrentRow = null
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (param.result == true) {
                                    val dy = ev.rawY - f1DownStartY
                                    if (dy > 10 && !f1IsDownwardSwipe) {
                                        f1IsDownwardSwipe = true
                                        f1SwipeTime = System.currentTimeMillis()
                                        try {
                                            f1CurrentRow = XposedHelpers.callMethod(
                                                param.thisObject, "getMTouchingHeadsUpView")
                                        } catch (_: Throwable) { f1CurrentRow = null }
                                    }
                                }
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // Block app launch on downward swipe, toggle expand instead
        try {
            val actCls = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter",
                lpparam.classLoader)

            XposedBridge.hookAllMethods(actCls, "onNotificationClicked", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isFeatureEnabled("disable_headsup_popup_enabled")) return
                    val isRecent = f1IsDownwardSwipe && (System.currentTimeMillis() - f1SwipeTime < 2000)
                    if (!isRecent) return
                    param.result = null
                    if (!f1HasToggled) {
                        f1HasToggled = true
                        try {
                            if (param.args.size >= 2) {
                                toggleHeadsUpExpandState(param.args[1])
                            }
                        } catch (_: Throwable) {}
                    }
                }
            })

            XposedBridge.hookAllMethods(actCls, "startNotificationIntent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isFeatureEnabled("disable_headsup_popup_enabled")) return
                    val isRecent = f1IsDownwardSwipe && (System.currentTimeMillis() - f1SwipeTime < 2000)
                    if (!isRecent) return
                    param.result = null
                    if (!f1HasToggled) {
                        f1HasToggled = true
                        try {
                            var row: Any? = null
                            for (arg in param.args) {
                                if (arg != null && arg.javaClass.name.contains("ExpandableNotificationRow")) {
                                    row = arg
                                    break
                                }
                            }
                            if (row == null) {
                                for (arg in param.args) {
                                    if (arg != null && arg.javaClass.name.contains("NotificationEntry")) {
                                        row = try { XposedHelpers.callMethod(arg, "getRow") } catch (_: Throwable) { null }
                                        break
                                    }
                                }
                            }
                            if (row == null) row = f1CurrentRow
                            if (row != null) {
                                toggleHeadsUpExpandState(row)
                            }
                        } catch (_: Throwable) {}
                    }
                }
            })
        } catch (_: Throwable) {}

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
                        if (!isFeatureEnabled("disable_back_haptic_enabled")) return
                        param.result = null
                    }
                }
            )
        } catch (_: Throwable) {}
    }
}
