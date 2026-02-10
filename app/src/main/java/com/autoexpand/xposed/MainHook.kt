package com.autoexpand.xposed

import android.content.Context
import android.net.Uri
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    private val TAG = "AutoExpand"

    private var appContext: Context? = null
    private val PROVIDER_URI = Uri.parse("content://com.autoexpand.xposed.prefs")

    // Cached prefs
    private var cachedBoolPrefs = mutableMapOf<String, Boolean>()
    private var cachedExcludedApps = emptySet<String>()
    private var lastCacheTime = 0L
    private val CACHE_INTERVAL_MS = 2000L

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
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: ContentProvider query failed: ${e.message}")
        }
    }

    private fun isFeatureEnabled(key: String): Boolean {
        refreshCacheIfNeeded()
        return cachedBoolPrefs[key] ?: true // fail-open
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

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        XposedBridge.log("$TAG: Loaded in SystemUI")

        val rowClass = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
        val contentViewClass = "com.android.systemui.statusbar.notification.row.NotificationContentView"

        // =====================================================
        // Capture SystemUI context for ContentProvider queries
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

                            // Write active marker so the settings app can confirm hooks are running
                            android.provider.Settings.Global.putString(
                                app.contentResolver,
                                "autoexpand_active",
                                System.currentTimeMillis().toString()
                            )

                            // Pre-load cache
                            refreshCacheIfNeeded()
                            XposedBridge.log("$TAG: Context captured, prefs loaded: ${cachedBoolPrefs.size} bools, ${cachedExcludedApps.size} excluded apps")
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}

        // =====================================================
        // SHADE HOOKS — keep notifications expanded in shade
        // =====================================================

        try {
            XposedHelpers.findAndHookMethod(
                rowClass, lpparam.classLoader,
                "setSystemExpanded", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = getNotificationPackage(param.thisObject)
                        if (shouldSkipNotification("expand_shade_enabled", pkg)) return
                        param.args[0] = true
                    }
                }
            )
            XposedBridge.log("$TAG: Hooked setSystemExpanded")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: FAILED hook setSystemExpanded: ${e.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                rowClass, lpparam.classLoader,
                "setSystemChildExpanded", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = getNotificationPackage(param.thisObject)
                        if (shouldSkipNotification("expand_shade_enabled", pkg)) return
                        param.args[0] = true
                    }
                }
            )
            XposedBridge.log("$TAG: Hooked setSystemChildExpanded")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: FAILED hook setSystemChildExpanded: ${e.message}")
        }

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
            XposedBridge.log("$TAG: Hooked setExpandable")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: FAILED hook setExpandable: ${e.message}")
        }

        // =====================================================
        // LOCK SCREEN HOOK — expand notifications on keyguard
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
            XposedBridge.log("$TAG: Hooked setOnKeyguard")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: FAILED hook setOnKeyguard: ${e.message}")
        }

        // =====================================================
        // HEADS-UP HOOKS — force expanded layout in HUN banner
        // =====================================================

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
                                val expandedChild = XposedHelpers.getObjectField(param.thisObject, "mExpandedChild")
                                if (expandedChild != null) {
                                    param.result = 1 // VISIBLE_TYPE_EXPANDED
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
            XposedBridge.log("$TAG: Hooked calculateVisibleType")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: FAILED hook calculateVisibleType: ${e.message}")
        }

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
            XposedBridge.log("$TAG: Hooked getIntrinsicHeight")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: FAILED hook getIntrinsicHeight: ${e.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                rowClass, lpparam.classLoader,
                "setHeadsUp", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[0] as Boolean) {
                            val pkg = getNotificationPackage(param.thisObject)
                            if (shouldSkipNotification("expand_headsup_enabled", pkg)) return
                            try {
                                XposedHelpers.setBooleanField(param.thisObject, "mExpandedWhenPinned", true)
                            } catch (_: Throwable) {}
                        }
                    }
                }
            )
            XposedBridge.log("$TAG: Hooked setHeadsUp")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: FAILED hook setHeadsUp: ${e.message}")
        }

        // =====================================================
        // BACK GESTURE HAPTIC — disable OPlus back swipe vibration
        // =====================================================

        val oplusVibHelper = "com.oplus.systemui.navigationbar.gesture.VibrationHelper"
        try {
            XposedHelpers.findAndHookMethod(
                oplusVibHelper, lpparam.classLoader,
                "doVibrateCustomized",
                android.content.Context::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isFeatureEnabled("disable_back_haptic_enabled")) return
                        param.result = null // suppress vibration
                    }
                }
            )
            XposedBridge.log("$TAG: Hooked OPlus VibrationHelper.doVibrateCustomized")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: FAILED hook OPlus VibrationHelper.doVibrateCustomized: ${e.message}")
        }

        XposedBridge.log("$TAG: Hook setup complete")
    }
}
