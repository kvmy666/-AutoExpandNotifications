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

    private var appContext: Context? = null       // captured from SystemUI process
    private var sysServerContext: Context? = null // captured from system_server process
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
            "android"                    -> handleSystemServer(lpparam)
            "com.android.systemui"       -> handleSystemUi(lpparam)
            "com.oplus.exsystemservice"  -> handleExSystemService(lpparam)
        }
    }

    // =====================================================
    // system_server hooks — PhoneWindowManager key interception
    // =====================================================

    private fun handleSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Capture system_server Application context
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application", lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        sysServerContext = param.thisObject as? Context
                        XposedBridge.log("Snapper: system_server context captured")
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("Snapper: sysServerContext capture failed: ${e.message}")
        }

        // ── Candidate PhoneWindowManager classes on OxygenOS 16 ──────────────────
        val pwmCandidates = listOf(
            "com.android.server.policy.PhoneWindowManager",
            "com.oplus.server.policy.OplusPhoneWindowManager",
            "com.oplus.server.policy.OplusPhoneWindowManagerExt"
        )

        for (cls in pwmCandidates) {
            // interceptKeyBeforeQueueing — fires for EVERY key event
            try {
                XposedHelpers.findAndHookMethod(
                    cls, lpparam.classLoader,
                    "interceptKeyBeforeQueueing",
                    KeyEvent::class.java, Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val ev = param.args[0] as KeyEvent
                            val kc = ev.keyCode
                            // Only log POWER and VOLUME_DOWN — the screenshot chord
                            if (kc == KeyEvent.KEYCODE_VOLUME_DOWN || kc == KeyEvent.KEYCODE_POWER) {
                                Log.d("JeezSnapper",
                                    "PWM.interceptKeyBeforeQueueing [$cls] keyCode=$kc action=${ev.action} flags=${ev.flags}")
                            }
                        }
                    }
                )
                XposedBridge.log("Snapper: hooked $cls.interceptKeyBeforeQueueing [system_server]")
            } catch (e: Throwable) {
                XposedBridge.log("Snapper: skip $cls.interceptKeyBeforeQueueing: ${e.message}")
            }

            // Hook handleKeyGestureEvent — gestureType=11 is the screenshot chord (confirmed via logcat)
            try {
                val clazz = XposedHelpers.findClass(cls, lpparam.classLoader)
                XposedBridge.hookAllMethods(clazz, "handleKeyGestureEvent", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Log all gesture types to confirm gestureType=11 is screenshot
                        val type = param.args.getOrNull(0)
                        Log.d("JeezSnapper", "$cls.handleKeyGestureEvent gestureType=$type")
                    }
                })
                XposedBridge.log("Snapper: hooked $cls.handleKeyGestureEvent")
            } catch (_: Throwable) {}

            // Hook all screenshot-related methods by name
            val screenshotMethods = listOf(
                "takeScreenshot", "requestScreenshot", "handleScreenshot",
                "screenshotChordVolumeLongPressEventReceived", "handleVolumeDownLongPress",
                "sendScreenshotRequest", "triggerScreenshot"
            )
            val clazz = try { XposedHelpers.findClass(cls, lpparam.classLoader) } catch (_: Throwable) { null }
            if (clazz != null) {
                for (method in screenshotMethods) {
                    try {
                        XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                Log.d("JeezSnapper", "SCREENSHOT_TRIGGER [$cls.$method] — intercepting")
                                // Once confirmed which method fires, we'll block + start Snapper here.
                                // For now: log only.
                            }
                        })
                        XposedBridge.log("Snapper: hooked $cls.$method [system_server]")
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    // =====================================================
    // com.oplus.exsystemservice — OxygenOS 16 screenshot manager
    //
    // Empirically confirmed via logcat (Phase 12):
    //   Power+VolDown → KeyCombinationManager (system_server) → gestureType=11
    //   → OplusScreenshotManagerService.takeScreenshot (com.oplus.exsystemservice)
    //   → binds com.oplus.screenshot/ScreenshotService
    //
    // We block takeScreenshot here and redirect to SnapperService.
    // Bypass: check /data/local/tmp/.snapper_bypass before blocking.
    // =====================================================

    private var exSvcContext: Context? = null

    private fun handleExSystemService(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Capture Application context for this process
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application", lpparam.classLoader, "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        exSvcContext = param.thisObject as? Context
                        XposedBridge.log("Snapper: com.oplus.exsystemservice context captured")
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("Snapper: exSvcContext capture failed: ${e.message}")
        }

        // ClassLoader spy to find the exact OplusScreenshotManagerService class name
        try {
            XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.args[0] as? String ?: return
                        if (param.result != null && name.contains("creenshot", ignoreCase = true)) {
                            Log.d("JeezSnapper", "exSvc CLASS_LOADED: $name")
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // Hook all candidate class/method names — block takeScreenshot and start Snapper
        val interceptHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                Log.d("JeezSnapper", "OplusScreenshotManagerService.${param.method.name} INTERCEPTED")
                val ctx = exSvcContext ?: run {
                    Log.e("JeezSnapper", "exSvcContext null — cannot redirect")
                    return
                }
                // Bypass flag: let native screenshot through when triggered by our "Full Screenshot" button
                val bypassFile = java.io.File("/data/local/tmp/.snapper_bypass")
                if (bypassFile.exists()) {
                    try { bypassFile.delete() } catch (_: Throwable) {}
                    Log.d("JeezSnapper", "bypass flag set — allowing native screenshot")
                    return
                }
                // Block native screenshot
                param.result = null
                // Start Snapper
                try {
                    ctx.startForegroundService(Intent("io.github.kvmy666.autoexpand.ACTION_CAPTURE").apply {
                        component = ComponentName(
                            "io.github.kvmy666.autoexpand",
                            "io.github.kvmy666.autoexpand.SnapperService"
                        )
                    })
                    Log.d("JeezSnapper", "OplusScreenshotManagerService blocked → SnapperService started")
                } catch (e: Throwable) {
                    Log.e("JeezSnapper", "startForegroundService from exSvc failed: ${e.message}")
                }
            }
        }

        // Try candidate class names found from logcat analysis
        val candidates = listOf(
            "com.oplus.exsystemservice.screenshot.OplusScreenshotManagerService",
            "com.oplus.systemserver.screenshot.OplusScreenshotManagerService",
            "com.oplus.exsystemservice.OplusScreenshotManagerService",
            "com.oplus.screenshot.OplusScreenshotManagerService"
        )
        for (cls in candidates) {
            val clazz = try { XposedHelpers.findClass(cls, lpparam.classLoader) }
                        catch (_: Throwable) { null } ?: continue
            // Hook all overloads of takeScreenshot
            try {
                XposedBridge.hookAllMethods(clazz, "takeScreenshot", interceptHook)
                XposedBridge.log("Snapper: hooked $cls.takeScreenshot [exsystemservice] ✓")
            } catch (e: Throwable) {
                XposedBridge.log("Snapper: $cls.takeScreenshot hook failed: ${e.message}")
            }
        }
    }

    // =====================================================
    // SystemUI hooks — notification + screenshot interception
    // =====================================================

    private fun handleSystemUi(lpparam: XC_LoadPackage.LoadPackageParam) {

        val rowClass = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
        val contentViewClass = "com.android.systemui.statusbar.notification.row.NotificationContentView"

        // ── ClassLoader spy — discover which screenshot classes OxygenOS 16 actually loads ──
        // This is a one-time diagnostic: produces "JeezSnapper CLASS_LOADED: com.oplus.xxx" in logcat.
        try {
            XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.args[0] as? String ?: return
                        if (param.result != null &&
                            (name.contains("creenshot", ignoreCase = true) ||
                             name.contains("Capture", ignoreCase = true))) {
                            Log.d("JeezSnapper", "CLASS_LOADED: $name")
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // ── TakeScreenshotService hook — intercept native screenshot ──────────────
        // Bypass: SnapperService writes /data/local/tmp/.snapper_bypass then injects keyevent 120
        val snapAction = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val ctx = appContext ?: run {
                    Log.e("JeezSnapper", "TakeScreenshotService hook fired but appContext is null")
                    return
                }
                val bypassFile = java.io.File("/data/local/tmp/.snapper_bypass")
                if (bypassFile.exists()) {
                    try { bypassFile.delete() } catch (_: Throwable) {}
                    Log.d("JeezSnapper", "bypass flag set — allowing native screenshot through")
                    return
                }
                param.result = android.app.Service.START_NOT_STICKY
                try {
                    ctx.startForegroundService(Intent("io.github.kvmy666.autoexpand.ACTION_CAPTURE").apply {
                        component = ComponentName(
                            "io.github.kvmy666.autoexpand",
                            "io.github.kvmy666.autoexpand.SnapperService"
                        )
                    })
                    Log.d("JeezSnapper", "TakeScreenshotService intercepted → SnapperService started")
                } catch (e: Throwable) {
                    Log.e("JeezSnapper", "startForegroundService failed: ${e.message}")
                }
            }
        }

        val screenshotServiceCandidates = listOf(
            "com.android.systemui.screenshot.TakeScreenshotService",
            "com.oplus.systemui.screenshot.OplusTakeScreenshotService",
            "com.oplus.screenshot.TakeScreenshotService"
        )
        for (cls in screenshotServiceCandidates) {
            try {
                XposedHelpers.findAndHookMethod(
                    cls, lpparam.classLoader,
                    "onStartCommand",
                    Intent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    snapAction
                )
                Log.d("JeezSnapper", "hooked TakeScreenshotService via $cls [SystemUI]")
                break
            } catch (e: Throwable) {
                Log.d("JeezSnapper", "skip TakeScreenshotService $cls: ${e.message}")
            }
        }

        // ── Also try hooking ScreenshotHelper.takeScreenshot ─────────────────────
        val helperCandidates = listOf(
            "com.android.systemui.screenshot.ScreenshotHelper",
            "com.oplus.systemui.screenshot.OplusScreenshotHelper"
        )
        for (cls in helperCandidates) {
            val clazz = try { XposedHelpers.findClass(cls, lpparam.classLoader) } catch (_: Throwable) { null }
                ?: continue
            try {
                XposedBridge.hookAllMethods(clazz, "takeScreenshot", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Log.d("JeezSnapper", "ScreenshotHelper.takeScreenshot called [$cls]")
                        val ctx = appContext ?: return
                        val bypassFile = java.io.File("/data/local/tmp/.snapper_bypass")
                        if (bypassFile.exists()) {
                            try { bypassFile.delete() } catch (_: Throwable) {}
                            return
                        }
                        param.result = null  // cancel the screenshot
                        try {
                            ctx.startForegroundService(Intent("io.github.kvmy666.autoexpand.ACTION_CAPTURE").apply {
                                component = ComponentName(
                                    "io.github.kvmy666.autoexpand",
                                    "io.github.kvmy666.autoexpand.SnapperService"
                                )
                            })
                            Log.d("JeezSnapper", "ScreenshotHelper intercepted → SnapperService started")
                        } catch (e: Throwable) {
                            Log.e("JeezSnapper", "startForegroundService failed: ${e.message}")
                        }
                    }
                })
                Log.d("JeezSnapper", "hooked ScreenshotHelper.takeScreenshot via $cls")
            } catch (e: Throwable) {
                Log.d("JeezSnapper", "skip ScreenshotHelper $cls: ${e.message}")
            }
        }

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
