package io.github.kvmy666.autoexpand

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.FileObserver
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.File

class MainHook : IXposedHookLoadPackage {

    private var appContext: Context? = null  // captured from SystemUI process

    // XSharedPreferences — reads prefs XML directly; survives app-process death (Xiaomi SmartPower)
    private val xprefs = XSharedPreferences("io.github.kvmy666.autoexpand", "prefs")

    private var lastCacheTime = 0L
    private val CACHE_INTERVAL_MS = 2000L

    private val PREFS_FILE     = "/data/local/tmp/tweaks_prefs.json"
    private val HEARTBEAT_FILE = "/data/local/tmp/tweaks_heartbeat"
    private val HUD_TAG        = "TweaksHud"
    private fun ts() = System.nanoTime() / 1_000_000L
    @Volatile private var filePrefCache: Map<String, String>? = null
    @Volatile private var fileObserver: FileObserver? = null

    // Zone gesture state — lazy so Handler is created after Looper.prepareMainLooper() runs
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

        fun onDown(ctx: android.content.Context) {
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

        fun onUp(ctx: android.content.Context) {
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

    private fun getStringPref(key: String, default: String): String {
        reloadIfStale()
        filePrefCache?.get(key)?.let { return it }
        return try { xprefs.getString(key, default) ?: default } catch (_: Throwable) { default }
    }

    private fun dispatchZoneGesture(side: String, suffix: String, ctx: android.content.Context) {
        val actionKey    = getStringPref("zones_${side}_${suffix}_action", "no_action")
        if (actionKey == "no_action") return
        val pkg          = getStringPref("zones_${side}_open_app_pkg", "")
        val shortcutData = getStringPref("zones_${side}_${suffix}_shortcut", "")
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
        if (getStringPref("zones_haptic_enabled", "1") == "1") {
            try {
                val vib = ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE)
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

    // F1: Swipe-down direction tracking state
    @Volatile private var f1DownStartY = 0f
    @Volatile private var f1IsDownwardSwipe = false
    @Volatile private var f1HasToggled = false
    @Volatile private var f1SwipeTime = 0L
    @Volatile private var f1CurrentRow: Any? = null

    private fun reloadIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastCacheTime < CACHE_INTERVAL_MS) return
        lastCacheTime = now
        try { xprefs.reload() } catch (_: Throwable) {}
        loadFilePrefs()
    }

    private fun loadFilePrefs() {
        try {
            val text = File(PREFS_FILE).readText()
            val json = JSONObject(text)
            val map = mutableMapOf<String, String>()
            for (key in json.keys()) map[key] = json.getString(key)
            filePrefCache = map
        } catch (e: Throwable) {
            Log.d("Snapper", "DIAG: file prefs load failed: $e")
        }
    }

    private fun startFileObserver() {
        try {
            val fileName = "tweaks_prefs.json"
            val observer = object : FileObserver(File("/data/local/tmp"), CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == fileName) loadFilePrefs()
                }
            }
            observer.startWatching()
            fileObserver = observer
            Log.d("Snapper", "DIAG: FileObserver started")
        } catch (e: Throwable) {
            Log.d("Snapper", "DIAG: FileObserver start failed: $e")
        }
    }

    private fun startHeartbeatThread() {
        val t = Thread {
            val file = File(HEARTBEAT_FILE)
            while (true) {
                try { file.writeText(System.currentTimeMillis().toString()) }
                catch (e: Throwable) { Log.d("Snapper", "DIAG: heartbeat write failed: $e") }
                Thread.sleep(60_000)
            }
        }
        t.isDaemon = true
        t.name = "tweaks-heartbeat"
        t.start()
    }

    private fun isFeatureEnabled(key: String): Boolean {
        reloadIfStale()
        val fileVal = filePrefCache?.get(key)
        fileVal?.let { return it == "1" }
        return try { xprefs.getBoolean(key, true) } catch (_: Throwable) { true }
    }

    // Kill-switches default OFF (opt-in). Using isFeatureEnabled here would default ON,
    // disabling every HUD hook for any user whose prefs file hasn't been written with the key yet.
    private fun isKillSwitchActive(key: String): Boolean {
        reloadIfStale()
        val fileVal = filePrefCache?.get(key)
        fileVal?.let { return it == "1" }
        return try { xprefs.getBoolean(key, false) } catch (_: Throwable) { false }
    }

    private fun getExcludedApps(): Set<String> {
        reloadIfStale()
        filePrefCache?.get("excluded_apps")?.let { v ->
            return if (v.isEmpty()) emptySet() else v.split("\n").toSet()
        }
        return try { xprefs.getStringSet("excluded_apps", emptySet()) ?: emptySet() } catch (_: Throwable) { emptySet() }
    }

    private fun getIntPref(key: String, default: Int): Int {
        reloadIfStale()
        filePrefCache?.get(key)?.toIntOrNull()?.let { return it }
        return try { xprefs.getString(key, null)?.toIntOrNull() ?: default } catch (_: Throwable) { default }
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

    /**
     * True if the row's SBN carries FLAG_GROUP_SUMMARY. Robust on first paint —
     * doesn't depend on children being attached yet or on NotificationChildrenContainer
     * hooks firing (some OEM SystemUIs use a different class name).
     */
    private fun isGroupSummaryRow(row: Any): Boolean = try {
        val entry = XposedHelpers.callMethod(row, "getEntry")
        val sbn = XposedHelpers.callMethod(entry, "getSbn")
        val notif = XposedHelpers.callMethod(sbn, "getNotification") as android.app.Notification
        val isSummary = (notif.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0
        // Only treat as a "real" group when children are actually attached.
        // WhatsApp etc. tag single-chat HUDs with FLAG_GROUP_SUMMARY before any
        // child arrives — those should auto-expand like normal notifications.
        isSummary && getRowChildCount(row) > 0
    } catch (_: Throwable) { false }

    /** True when the given row is a child of a heads-up parent. */
    private fun parentHeadsUp(child: Any): Boolean = try {
        val parent = XposedHelpers.getObjectField(child, "mNotificationParent") ?: return false
        XposedHelpers.getBooleanField(parent, "mIsHeadsUp")
    } catch (_: Throwable) { false }

    /** Resource (entry, package) for a view id, or (null, null). */
    private fun resourceEntryAndPkg(view: View): Pair<String?, String?> {
        if (view.id == View.NO_ID) return null to null
        val res = view.context.resources
        val entry = try { res.getResourceEntryName(view.id) } catch (_: Throwable) { null }
        val pkg   = try { res.getResourcePackageName(view.id) } catch (_: Throwable) { null }
        return entry to pkg
    }

    private fun getRowChildCount(row: Any): Int = try {
        val container = XposedHelpers.getObjectField(row, "mChildrenContainer")
        if (container != null) XposedHelpers.callMethod(container, "getNotificationChildCount") as? Int ?: 0 else 0
    } catch (_: Throwable) { 0 }

    @Suppress("UNCHECKED_CAST")
    private fun getNotificationChildren(row: Any): List<View> {
        return try {
            val container = XposedHelpers.getObjectField(row, "mChildrenContainer") ?: return emptyList()
            (try { XposedHelpers.callMethod(container, "getAttachedChildren") } catch (_: Throwable) { null } as? List<View>)
                ?: (try { XposedHelpers.callMethod(container, "getNotificationChildren") } catch (_: Throwable) { null } as? List<View>)
                ?: emptyList()
        } catch (_: Throwable) { emptyList() }
    }

    /** Strict variant: must be the actual NotificationExpandButton with id android:expand_button.
     *  Skips alternate_expand_target (which on some OEMs routes to the row's launch click). */
    private fun findStrictExpandButton(view: View): View? = findExpandButtonImpl(view, strict = true)

    /** DFS for the system expand button on a row, skipping nested rows so we
     *  click the parent's button rather than a child's. */
    private fun findExpandButton(view: View): View? = findExpandButtonImpl(view, strict = false)

    private fun findExpandButtonImpl(view: View, strict: Boolean): View? {
        try {
            if (view.visibility != View.VISIBLE) return null
            val (entry, pkg) = resourceEntryAndPkg(view)
            val matches = pkg == "android" && view.hasOnClickListeners() && when {
                strict -> entry == "expand_button" &&
                    view.javaClass.name == "com.android.internal.widget.NotificationExpandButton"
                else -> entry == "expand_button" || entry == "alternate_expand_target"
            }
            if (matches) return view
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i) ?: continue
                    if (child.javaClass.name.endsWith("ExpandableNotificationRow")) continue
                    val found = findExpandButtonImpl(child, strict)
                    if (found != null) return found
                }
            }
        } catch (_: Throwable) {}
        return null
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
        // Master kill-switch for HUD hooks. When ON, every HUD-expand path is skipped
        // so OEM HUD layouts remain untouched (escape hatch for ROMs where our pass
        // produces a blank or oversized HUD).
        // Note: the disable_headsup_hooks_enabled kill-switch is NOT consulted here.
        // It only gates the grouped-HUD expand path (see expandGroupIfNeeded and the
        // addNotification HU branch). Single notifications still expand normally when
        // the kill-switch is ON — only group parents stay at OEM-default (collapsed).
        if (!isFeatureEnabled(featureKey)) return true
        if (pkg != null && pkg in getExcludedApps()) return true
        return false
    }

    /**
     * State-aware grouped HUD pass. Runs once per HUD lifetime.
     * Idempotent: only clicks the parent arrow when collapsed, only clicks a child arrow
     * when expanded — so re-entry from setHeadsUp re-fires can't bounce the state.
     */
    private fun expandGroupIfNeeded(rowView: View, rowObj: Any, pkg: String?) {
        // Master kill-switch: when ON, leave grouped HUDs at OEM default (collapsed parent).
        // Singles bypass this function entirely, so their auto-expand path is unaffected.
        if (isKillSwitchActive("disable_headsup_hooks_enabled")) return
        val rowId = System.identityHashCode(rowObj)
        val count = getRowChildCount(rowObj)
        Log.d(HUD_TAG, "[${ts()}] expandGroupIfNeeded ENTER row=$rowId pkg=$pkg count=$count")
        if (count == 0) {
            Log.d(HUD_TAG, "[${ts()}] not grouped yet row=$rowId pkg=$pkg (deferring to addNotification)")
            return
        }
        XposedHelpers.setAdditionalInstanceField(rowObj, "aeIsGroup", true)

        val parentExpanded = try {
            XposedHelpers.callMethod(rowObj, "isExpanded") as? Boolean
        } catch (_: Throwable) { null } ?: false
        if (!parentExpanded) {
            // Direct state set is more reliable on OPlus HUDs than performClick — the HUD
            // overlay sometimes ignores button-click expansion until first user interaction.
            val ok = try {
                XposedHelpers.callMethod(rowObj, "setUserExpanded", true, true); true
            } catch (_: Throwable) {
                try { XposedHelpers.callMethod(rowObj, "setUserExpanded", true); true } catch (_: Throwable) { false }
            }
            if (!ok) findExpandButton(rowView)?.performClick()
        }
        Log.d(HUD_TAG, "[${ts()}] grouped row=$rowId pkg=$pkg children=$count parentWasExpanded=$parentExpanded")

        var stateSet = 0
        var clicked = 0
        for (child in getNotificationChildren(rowObj)) {
            val cid = System.identityHashCode(child)
            val before = try { (XposedHelpers.callMethod(child, "getIntrinsicHeight") as? Int) ?: -1 } catch (_: Throwable) { -1 }
            val ok = collapseChildNoAnim(child)
            if (ok) stateSet++
            else { findStrictExpandButton(child)?.performClick(); clicked++ }
            val after = try { (XposedHelpers.callMethod(child, "getIntrinsicHeight") as? Int) ?: -1 } catch (_: Throwable) { -1 }
            Log.d(HUD_TAG, "[${ts()}] expandGroupIfNeeded child=$cid ok=$ok h=$before→$after")
        }
        Log.d(HUD_TAG, "[${ts()}] children collapsed row=$rowId stateSet=$stateSet clicked=$clicked total=$count")
    }

    /** Direct state-setter on a child row. No animation. Returns true if call landed. */
    private fun collapseChildNoAnim(child: Any): Boolean {
        try {
            XposedHelpers.callMethod(child, "setUserExpanded", false, false)
            return true
        } catch (_: Throwable) {}
        return try {
            XposedHelpers.callMethod(child, "setUserExpanded", false)
            true
        } catch (_: Throwable) { false }
    }

    /**
     * Toggle heads-up notification expand/collapse state.
     * Directly sets mActualHeight to force the card to resize,
     * since notifyHeightChanged alone doesn't resize the OPlus floating window.
     */
    private fun toggleHeadsUpExpandState(row: Any) {
        try {
            // Grouped HUDs are handled exclusively through the system arrow.
            val isGroup = XposedHelpers.getAdditionalInstanceField(row, "aeIsGroup") as? Boolean ?: false
            if (isGroup) {
                (row as? View)?.let { findExpandButton(it)?.performClick() }
                return
            }
            val isCollapsed = XposedHelpers.getAdditionalInstanceField(row, "aeCollapsed") as? Boolean ?: false
            val newExpanded = isCollapsed // collapsed→expand, expanded→collapse

            // Set state FIRST so our height hooks return the correct values
            XposedHelpers.setAdditionalInstanceField(row, "aeCollapsed", !newExpanded)
            XposedHelpers.setBooleanField(row, "mExpandedWhenPinned", newExpanded)
            val targetHeight = XposedHelpers.callMethod(row, "getIntrinsicHeight") as Int

            XposedHelpers.callMethod(row, "setActualHeight", targetHeight)

            (row as? View)?.let { rowView ->
                rowView.requestLayout()
                (rowView.parent as? View)?.requestLayout()
            }
        } catch (_: Throwable) {}
    }

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
            }
        } catch (t: Throwable) {
            Log.e("AutoExpand", "handleLoadPackage top-level threw: $t")
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

    // ── Chord state machine ─────────────────────────────────────────────────────
    //
    // PHASE 18 LOGCAT EVIDENCE:
    //   interceptKeyBeforeQueueing fires on tid=168 for POWER key=26 then
    //   VOLUME_DOWN key=25 (same thread, sequential).
    //   handleKeyGestureEvent fires on tid=41 AFTER both keys are down. Its
    //   args[0] is a KeyGestureEvent object (NOT an int) — previous attempts
    //   that cast args[0] as? Int silently skipped the block entirely.
    //   Two calls: action=1 (start) then action=2 (complete). Both must be blocked.
    //   Both hooks confirmed on com.android.server.policy.PhoneWindowManager.
    //
    // TWO-HOOK STRATEGY:
    //   Hook 1 (interceptKeyBeforeQueueing):
    //     - Power-first press order: VOLUME_DOWN arrives with pwmPowerDown=true →
    //       consume VOLUME_DOWN (param.result=0) → launch Snapper → chordTriggered=true
    //     - POWER UP with chordTriggered=true → param.setResult(0) → no screen-off
    //   Hook 2 (handleKeyGestureEvent):
    //     - Volume-first press order: gesture fires before interceptKeyBQ catches it
    //     - Check args[0].toString() for "SCREENSHOT_CHORD" (confirmed string)
    //     - param.setResult(null) on BOTH action=1 and action=2
    //     - If !chordTriggered: launch Snapper here instead
    // ────────────────────────────────────────────────────────────────────────────
    @Volatile private var chordTriggered  = false
    @Volatile private var powerDownTime   = 0L
    @Volatile private var volumeDownTime  = 0L
    private companion object {
        const val CHORD_WINDOW_MS = 150L
        const val GROUP_TAG = "TweaksGroup"
    }

    private fun handleSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            installChordHooks(lpparam)
        } catch (t: Throwable) {
            Log.e("Snapper", "handleSystemServer crashed: $t")
        }
    }

    private fun installChordHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Phase 18 confirmed both hooks work on PhoneWindowManager.
        // Try it first; fall back to OplusPhoneWindowManager if not found.
        val candidates = listOf(
            "com.android.server.policy.PhoneWindowManager",
            "com.oplus.server.policy.OplusPhoneWindowManager"
        )

        // ── Hook 1: interceptKeyBeforeQueueing ─────────────────────────────────
        // Handles Power-first press order and POWER UP screen-off prevention.
        for (cls in candidates) {
            try {
                XposedHelpers.findAndHookMethod(
                    cls, lpparam.classLoader,
                    "interceptKeyBeforeQueueing",
                    KeyEvent::class.java, Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (!isFeatureEnabled("enable_snapper_entirely")) return
                                val ev = param.args[0] as? KeyEvent ?: return
                                when (ev.keyCode) {
                                    KeyEvent.KEYCODE_POWER -> when (ev.action) {
                                        KeyEvent.ACTION_DOWN -> {
                                            powerDownTime  = ev.downTime   // uptimeMillis domain
                                            chordTriggered = false
                                            // Pass through — do NOT consume
                                        }
                                        KeyEvent.ACTION_UP -> {
                                            if (chordTriggered) {
                                                chordTriggered = false
                                                try { XposedHelpers.callMethod(param.thisObject, "cancelPowerKeyLongPress") } catch (_: Throwable) {}
                                                try { XposedHelpers.setBooleanField(param.thisObject, "mPowerKeyDown", false) } catch (_: Throwable) {}
                                                param.setResult(0)
                                                Log.d("Snapper", "POWER_UP consumed — screen stays on")
                                            }
                                            // else: pass through — normal screen-off
                                        }
                                    }
                                    KeyEvent.KEYCODE_VOLUME_DOWN -> when (ev.action) {
                                        KeyEvent.ACTION_DOWN -> {
                                            volumeDownTime = ev.downTime   // always record
                                            if (!isFeatureEnabled("snapper_hardware_chord_enabled")) return
                                            val gap = ev.downTime - powerDownTime
                                            if (gap > 0L && gap < CHORD_WINDOW_MS) {
                                                chordTriggered = true
                                                // Cancel power long-press NOW — Gemini fires while
                                                // Power is still held, so POWER_UP is too late.
                                                try { XposedHelpers.callMethod(param.thisObject, "cancelPowerKeyLongPress") } catch (_: Throwable) {}
                                                try { XposedHelpers.setBooleanField(param.thisObject, "mPowerKeyHandled", true) } catch (_: Throwable) {}
                                                param.result = 0
                                                val ctx = getCtx(param)
                                                if (ctx != null) {
                                                    try {
                                                        launchSnapper(ctx)
                                                        Log.d("Snapper", "Snapper launched (interceptKeyBQ / Power-first)")
                                                    } catch (e: Throwable) {
                                                        Log.e("Snapper", "launch failed: ${e.message}")
                                                        chordTriggered = false
                                                        param.result = null
                                                    }
                                                } else {
                                                    Log.e("Snapper", "mContext null (interceptKeyBQ)")
                                                    chordTriggered = false
                                                    param.result = null
                                                }
                                            }
                                            // else: gap out of window — pass through normally
                                        }
                                        KeyEvent.ACTION_UP -> {
                                            if (chordTriggered) param.result = 0
                                            // else: pass through
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.e("Snapper", "interceptKeyBQ hook threw: $t")
                                param.result = null
                            }
                        }
                    }
                )
                Log.d("Snapper", "Hook1 (interceptKeyBQ) registered on $cls")
                break
            } catch (e: Throwable) {
                Log.e("Snapper", "Hook1 FAILED on $cls: $e")
            }
        }

        // ── Hook 2: handleKeyGestureEvent ──────────────────────────────────────
        // Handles Volume-first press order AND provides a second layer of defense.
        // args[0] is a KeyGestureEvent object — check toString() for SCREENSHOT_CHORD.
        // Block BOTH action=1 (start) and action=2 (complete) with setResult(null).
        for (cls in candidates) {
            try {
                val clazz = XposedHelpers.findClass(cls, lpparam.classLoader)
                val hooks = XposedBridge.hookAllMethods(clazz, "handleKeyGestureEvent",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (!isFeatureEnabled("enable_snapper_entirely")) return
                                val gestureEvent = param.args.getOrNull(0) ?: return
                                // args[0] is KeyGestureEvent — use toString() to identify
                                // SCREENSHOT_CHORD (confirmed "KEY_GESTURE_TYPE_SCREENSHOT_CHORD")
                                if (!gestureEvent.toString().contains("SCREENSHOT_CHORD")) return
                                if (!isFeatureEnabled("snapper_hardware_chord_enabled")) return

                                // Block the native screenshot (both action=1 and action=2)
                                param.setResult(null)
                                Log.d("Snapper", "handleKeyGestureEvent SCREENSHOT_CHORD blocked")

                                // Launch Snapper only on simultaneous press (Vol-first path)
                                // Both keys must have been pressed within CHORD_WINDOW_MS of each other.
                                // volumeDownTime == 0 means Volume was never pressed → single Power press → skip.
                                val bothSimultaneous = volumeDownTime > 0L &&
                                    kotlin.math.abs(powerDownTime - volumeDownTime) < CHORD_WINDOW_MS
                                if (!chordTriggered && bothSimultaneous) {
                                    chordTriggered = true
                                    val ctx = getCtx(param)
                                    if (ctx != null) {
                                        try {
                                            launchSnapper(ctx)
                                            Log.d("Snapper", "Snapper launched (handleKeyGesture / Vol-first)")
                                        } catch (e: Throwable) {
                                            Log.e("Snapper", "launch failed (handleKeyGesture): ${e.message}")
                                            chordTriggered = false
                                        }
                                    } else {
                                        Log.e("Snapper", "mContext null (handleKeyGesture)")
                                        chordTriggered = false
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.e("Snapper", "handleKeyGestureEvent hook threw: $t")
                            }
                        }
                    }
                )
                if (hooks.isNotEmpty()) {
                    Log.d("Snapper", "Hook2 (handleKeyGestureEvent) registered ${hooks.size} methods on $cls")
                    break
                }
            } catch (e: Throwable) {
                Log.e("Snapper", "Hook2 FAILED on $cls: $e")
            }
        }
    }

    private fun getCtx(param: XC_MethodHook.MethodHookParam): Context? = try {
        XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
    } catch (_: Throwable) { null }

    private fun launchSnapper(ctx: Context) {
        ctx.startForegroundService(
            Intent("io.github.kvmy666.autoexpand.ACTION_CAPTURE").apply {
                component = ComponentName(
                    "io.github.kvmy666.autoexpand",
                    "io.github.kvmy666.autoexpand.SnapperService"
                )
            }
        )
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
                            Log.d("Snapper", "DIAG: SystemUI hook init — appContext captured, pkg=${app.packageName}")
                            loadFilePrefs()
                            startFileObserver()
                            startHeartbeatThread()
                            registerZoneActionReceiver(app)
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
                        try { reloadIfStale() } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}

        // === On child attach to a group (HU parent only):
        //  - Collapse the child before first layout (kills expanded-frame flicker).
        try {
            val containerClass = "com.android.systemui.statusbar.notification.row.NotificationChildrenContainer"
            XposedHelpers.findAndHookMethod(
                containerClass, lpparam.classLoader,
                "addNotification",
                XposedHelpers.findClass(rowClass, lpparam.classLoader),
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val notifParent = XposedHelpers.getObjectField(param.thisObject, "mContainingNotification") ?: return
                            val pkg = getNotificationPackage(notifParent)
                            val child = param.args[0] ?: return
                            val parentHU = try { XposedHelpers.getBooleanField(notifParent, "mIsHeadsUp") } catch (_: Throwable) { false }
                            val onKeyguard = try { XposedHelpers.getBooleanField(notifParent, "mOnKeyguard") } catch (_: Throwable) { false }
                            val parentId = System.identityHashCode(notifParent)
                            val childIdx = try { XposedHelpers.callMethod(notifParent, "getNotificationChildCount") as Int } catch (_: Throwable) { -1 }
                            val childCls = child.javaClass.simpleName
                            val childParentRef = try {
                                XposedHelpers.getObjectField(child, "mNotificationParent")?.let { System.identityHashCode(it) }
                            } catch (_: Throwable) { null }
                            Log.d(HUD_TAG, "[${ts()}] addNotification ENTER parent=$parentId pkg=$pkg parentHU=$parentHU onKG=$onKeyguard childIdx=$childIdx childCls=$childCls childParentRef=$childParentRef match=${childParentRef == parentId}")

                            if (parentHU) {
                                if (shouldSkipNotification("expand_headsup_enabled", pkg) ||
                                    isKillSwitchActive("disable_headsup_hooks_enabled")) {
                                    Log.d(HUD_TAG, "[${ts()}] addNotification EXIT parent=$parentId branch=HU skipped"); return
                                }
                                XposedHelpers.setAdditionalInstanceField(notifParent, "aeIsGroup", true)
                                // Expand parent so the compact group list is visible from first paint.
                                try { XposedHelpers.callMethod(notifParent, "setUserExpanded", true, true) } catch (_: Throwable) {}
                                val ok = collapseChildNoAnim(child)
                                val h = try { (XposedHelpers.callMethod(child, "getIntrinsicHeight") as? Int) ?: -1 } catch (_: Throwable) { -1 }
                                Log.d(HUD_TAG, "[${ts()}] addNotification EXIT parent=$parentId branch=HU ok=$ok childH=$h pkg=$pkg")
                                return
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}

        // =====================================================
        // SHADE / LOCK SCREEN — group parent auto-expand
        // Trigger: setSystemExpanded(false) fires on the group parent
        // every time shade/LS renders it. We click the parent's
        // expand-button arrow once per row lifetime. The system
        // renders children collapsed naturally when the parent is
        // expanded in shade/LS, so children need no extra work.
        // =====================================================
        try {
            XposedHelpers.findAndHookMethod(
                rowClass, lpparam.classLoader,
                "setSystemExpanded", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val row = param.thisObject
                            val rowView = row as? View ?: return
                            val isHU = try { XposedHelpers.getBooleanField(row, "mIsHeadsUp") } catch (_: Throwable) { false }
                            if (isHU) return
                            // Skip child rows inside a grouped notification — their parent is expanded
                            // by the addNotification hook; forcing setUserExpanded here would undo that.
                            val isChild = try { XposedHelpers.callMethod(row, "isChildInGroup") as? Boolean ?: false } catch (_: Throwable) { false }
                            if (isChild) return
                            val onKG = try { XposedHelpers.getBooleanField(row, "mOnKeyguard") } catch (_: Throwable) { false }
                            val pkg = getNotificationPackage(row)
                            val featureKey = if (onKG) "expand_lockscreen_enabled" else "expand_shade_enabled"
                            if (shouldSkipNotification(featureKey, pkg)) return
                            val already = XposedHelpers.getAdditionalInstanceField(row, "aeAutoExpanded") as? Boolean ?: false
                            if (already) return
                            val isExpanded = try { XposedHelpers.callMethod(row, "isExpanded") as? Boolean ?: false } catch (_: Throwable) { false }
                            if (isExpanded) {
                                XposedHelpers.setAdditionalInstanceField(row, "aeAutoExpanded", true)
                                return
                            }
                            XposedHelpers.setAdditionalInstanceField(row, "aeAutoExpanded", true)
                            // Defer to next frame — running synchronously during the layout pass
                            // that just called setSystemExpanded(false) gets undone by that pass.
                            rowView.post {
                                try {
                                    val ok = try {
                                        XposedHelpers.callMethod(row, "setUserExpanded", true, true); true
                                    } catch (_: Throwable) {
                                        try { XposedHelpers.callMethod(row, "setUserExpanded", true); true } catch (_: Throwable) { false }
                                    }
                                    if (!ok) findStrictExpandButton(rowView)?.performClick()
                                    val ne = try { XposedHelpers.callMethod(row, "isExpanded") as? Boolean ?: false } catch (_: Throwable) { false }
                                    Log.d(HUD_TAG, "[${ts()}] shade/LS post-expand row=${System.identityHashCode(row)} pkg=$pkg onKG=$onKG ok=$ok nowExpanded=$ne")
                                    // If state still didn't take, clear flag so a subsequent setSystemExpanded fire retries.
                                    if (!ne) XposedHelpers.setAdditionalInstanceField(row, "aeAutoExpanded", false)
                                } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}
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
                                    val aeGroupFlag = XposedHelpers.getAdditionalInstanceField(row, "aeIsGroup") as? Boolean ?: false
                                    val isGroup = aeGroupFlag || isGroupSummaryRow(row)
                                    val collapsed = XposedHelpers.getAdditionalInstanceField(row, "aeCollapsed") as? Boolean ?: false
                                    Log.d(HUD_TAG, "[${ts()}] calcVisibleType row=${System.identityHashCode(row)} pkg=$pkg result=$result isGroup=$isGroup aeCollapsed=$collapsed willOverride=${!isGroup && !collapsed}")
                                    // Recovery path for OEMs whose NotificationChildrenContainer.addNotification
                                    // we don't hook (e.g. Xiaomi 17). Children attached after setHeadsUp(true)
                                    // deferred — run the expand pass now so the parent gets setUserExpanded(true)
                                    // and children render in the compact group list.
                                    if (!aeGroupFlag && isGroup && row is View &&
                                        !shouldSkipNotification("expand_headsup_enabled", pkg)) {
                                        try { expandGroupIfNeeded(row, row, pkg) } catch (_: Throwable) {}
                                    }
                                    if (isGroup) return
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

        // getIntrinsicHeight — force expanded height (skip grouped summaries — let system size them)
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
                            val aeGroupFlag = XposedHelpers.getAdditionalInstanceField(param.thisObject, "aeIsGroup") as? Boolean ?: false
                            if (aeGroupFlag || isGroupSummaryRow(param.thisObject)) return
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

        // getPinnedHeadsUpHeight — force expanded height for heads-up window sizing (skip grouped summaries)
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
                            val aeGroupFlag = XposedHelpers.getAdditionalInstanceField(param.thisObject, "aeIsGroup") as? Boolean ?: false
                            if (aeGroupFlag || isGroupSummaryRow(param.thisObject)) return
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

        // setHeadsUp — set expand/collapse state before first draw, no post-draw manipulation
        try {
            XposedHelpers.findAndHookMethod(
                rowClass, lpparam.classLoader,
                "setHeadsUp", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.args[0] as Boolean
                        val pkg = getNotificationPackage(param.thisObject)
                        if (shouldSkipNotification("expand_headsup_enabled", pkg)) return
                        val rowId = System.identityHashCode(param.thisObject)
                        val cc = getRowChildCount(param.thisObject)
                        if (v) {
                            val alreadyGroup = XposedHelpers.getAdditionalInstanceField(param.thisObject, "aeIsGroup") as? Boolean ?: false
                            val aeCollapsed = XposedHelpers.getAdditionalInstanceField(param.thisObject, "aeCollapsed")
                            Log.d(HUD_TAG, "[${ts()}] setHeadsUp(true) row=$rowId pkg=$pkg children=$cc aeGroup=$alreadyGroup aeCollapsed=$aeCollapsed")
                            if (!alreadyGroup) {
                                XposedHelpers.setAdditionalInstanceField(param.thisObject, "aeIsGroup", false)
                            }
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, "aeCollapsed", false)
                        } else {
                            Log.d(HUD_TAG, "[${ts()}] setHeadsUp(false) row=$rowId pkg=$pkg children=$cc — resetting aeIsGroup/aeAutoExpanded")
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, "aeIsGroup", false)
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, "aeAutoExpanded", false)
                        }
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[0] as Boolean != true) return
                        val pkg = getNotificationPackage(param.thisObject)
                        if (shouldSkipNotification("expand_headsup_enabled", pkg)) return
                        val alreadyGroup = XposedHelpers.getAdditionalInstanceField(param.thisObject, "aeIsGroup") as? Boolean ?: false
                        if (alreadyGroup) return
                        val rowView = param.thisObject as? View ?: return
                        expandGroupIfNeeded(rowView, param.thisObject, pkg)
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
                        val isGroup = XposedHelpers.getAdditionalInstanceField(param.thisObject, "aeIsGroup") as? Boolean ?: false
                        if (isGroup) return
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
                                val row = param.args[1]
                                toggleHeadsUpExpandState(row)
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
        // HEADS-UP MAX LINES enforcement
        // Target: com.android.internal.widget.MessagingTextMessage.setMaxDisplayedLines
        // Called from MessagingLinearLayout.onMeasure with Integer.MAX_VALUE when
        // our module forces full expansion — clamp to user-configured limit.
        // 0 = unlimited (skip). Wrapped in try/catch — any failure is silent.
        // =====================================================
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.internal.widget.MessagingTextMessage", lpparam.classLoader,
                "setMaxDisplayedLines", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!isFeatureEnabled("expand_headsup_enabled")) return
                            if (isKillSwitchActive("disable_headsup_hooks_enabled")) return
                            val maxLines = getIntPref("headsup_max_lines", 5)
                            if (maxLines <= 0) return
                            val requested = param.args[0] as Int
                            if (requested > maxLines) {
                                param.args[0] = maxLines
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
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

        hookStatusBarZones(lpparam)
    }

    private fun handleZoneTouch(ev: MotionEvent, view: android.view.View, ctx: android.content.Context) {
        if (!isFeatureEnabled("zones_enabled")) return
        val x = ev.x
        val y = ev.y
        val h = view.height.takeIf { it > 0 } ?: return

        val screenW    = view.resources.displayMetrics.widthPixels
        val leftW      = (screenW * getIntPref("zones_left_width_pct",  25) / 100f).toInt()
        val rightStart = screenW - (screenW * getIntPref("zones_right_width_pct", 25) / 100f).toInt()

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

    private fun hookStatusBarZones(lpparam: XC_LoadPackage.LoadPackageParam) {
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
                            val ctx = view.context ?: appContext ?: return
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
                                val ctx  = view.context ?: appContext ?: return
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
                                        val ctx2  = view2.context ?: appContext ?: return
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

    // =========================================================================
    // Status Bar Zones — privileged action receiver
    // =========================================================================

    private fun registerZoneActionReceiver(app: android.app.Application) {
        try {
            val filter = android.content.IntentFilter(ActionDispatcher.ACTION_PRIVILEGED)
            app.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                    try {
                        val key = intent.getStringExtra(ActionDispatcher.EXTRA_ACTION_KEY) ?: return
                        handlePrivilegedZoneAction(ctx, key)
                    } catch (t: Throwable) {
                        Log.e("Zones", "privileged action failed: $t")
                    }
                }
            }, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            Log.d("Zones", "zone privileged action receiver registered")
        } catch (t: Throwable) {
            Log.e("Zones", "zone receiver registration failed: $t")
        }
    }

    @Suppress("DEPRECATION")
    private fun handlePrivilegedZoneAction(ctx: android.content.Context, key: String) {
        when (key) {
            "toggle_wifi" -> {
                try {
                    val wm = ctx.getSystemService(android.content.Context.WIFI_SERVICE)
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
                    val tm = ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE)
                        as android.telephony.TelephonyManager
                    XposedHelpers.callMethod(tm, "setDataEnabled", !tm.isDataEnabled)
                } catch (t: Throwable) { Log.e("Zones", "mobile data toggle: $t") }
            }
            "toggle_power_saver" -> {
                try {
                    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE)
                        as android.os.PowerManager
                    XposedHelpers.callMethod(pm, "setPowerSaveModeEnabled", !pm.isPowerSaveMode)
                } catch (t: Throwable) { Log.e("Zones", "power saver toggle: $t") }
            }
        }
    }
}
