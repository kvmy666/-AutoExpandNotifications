package io.github.kvmy666.autoexpand

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.kvmy666.autoexpand.hook.PrefsBridge

class MainHook : IXposedHookLoadPackage {

    /** Shared prefs/IPC reader; owns the captured app context (see PrefsBridge). */
    private val prefs = PrefsBridge()

    private val HUD_TAG        = "TweaksHud"
    private fun ts() = System.nanoTime() / 1_000_000L

    // Keep-screen-on (System Behavior): a 1x1 invisible overlay window carrying
    // FLAG_KEEP_SCREEN_ON, hosted in the always-alive SystemUI process.
    @Volatile private var keepScreenOnView: View? = null

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

    private fun dispatchZoneGesture(side: String, suffix: String, ctx: android.content.Context) {
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

    /**
     * Add/remove the invisible keep-screen-on overlay in the SystemUI process.
     * Idempotent: a single 1x1 TYPE_APPLICATION_OVERLAY window with FLAG_KEEP_SCREEN_ON
     * keeps the display awake for as long as it's attached (works on lockscreen too).
     * Must run on a Looper thread — posted to the main looper.
     */
    private fun applyKeepScreenOn(enabled: Boolean) {
        val ctx = prefs.appContext ?: return
        zoneHandler.post {
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

    // Field name discovered dynamically (cached) to avoid guessing across builds.
    @Volatile private var expandClickField: java.lang.reflect.Field? = null
    @Volatile private var expandClickFieldResolved = false

    // Keyguard auto-expand window: while uptimeMillis < this, the onExpandClicked
    // callback (which transitions to the locked shade → scrim/dim/unlock-prompt) is
    // blocked at its source. Covers both the synchronous click and any deferred/async
    // dispatch, and any keyguard expand path — without affecting real user taps.
    @Volatile private var lsAutoExpandUntil = 0L
    @Volatile private var onExpandClickedHooked = false

    private fun beginLsAutoExpandWindow() {
        lsAutoExpandUntil = android.os.SystemClock.uptimeMillis() + 400L
    }

    /** Lazily hook the row's onExpandClick listener implementation so onExpandClicked
     *  can be suppressed while a keyguard auto-expand window is active. Installed once,
     *  from the first listener instance we observe. */
    private fun ensureOnExpandClickedHook(listener: Any) {
        if (onExpandClickedHooked) return
        try {
            XposedBridge.hookAllMethods(listener.javaClass, "onExpandClicked",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val blocking = android.os.SystemClock.uptimeMillis() < lsAutoExpandUntil
                            if (blocking) {
                                Log.d("TweaksLS", "[${ts()}] onExpandClicked BLOCKED cls=${param.thisObject.javaClass.name}")
                                param.setResult(null)
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
            onExpandClickedHooked = true
            Log.d("TweaksLS", "onExpandClicked hooked on ${listener.javaClass.name}")
        } catch (t: Throwable) {
            Log.d("TweaksLS", "onExpandClicked hook failed: $t")
        }
    }

    /** ExpandableNotificationRow's onExpandClick listener field. On AOSP this is
     *  mOnExpandClickListener; the row's expand-button OnClickListener reads it at
     *  click time and, on KEYGUARD, calls into CentralSurfaces.onExpandClicked which
     *  transitions to the locked shade (the scrim/dim + unlock-prompt side effect). */
    private fun getExpandClickListenerField(row: Any): java.lang.reflect.Field? {
        if (expandClickFieldResolved) return expandClickField
        expandClickFieldResolved = true
        try {
            var c: Class<*>? = row.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    val n = f.name.lowercase()
                    val tn = f.type.simpleName.lowercase()
                    if (n.contains("expandclick") || tn.contains("onexpandclicklistener")) {
                        f.isAccessible = true
                        expandClickField = f
                        Log.d("TweaksLS", "expandClick listener field = ${c.name}.${f.name} : ${f.type.name}")
                        return f
                    }
                }
                c = c.superclass
            }
            Log.d("TweaksLS", "expandClick listener field NOT FOUND on ${row.javaClass.name}")
        } catch (t: Throwable) {
            Log.d("TweaksLS", "expandClick field lookup failed: $t")
        }
        return expandClickField
    }

    /**
     * Lockscreen-only expand. performClick on the real arrow is the only thing that
     * reliably sticks (the row's own setUserExpanded runs inside the click handler),
     * but on keyguard the same click also notifies onExpandClicked → goToLockedShade,
     * which raises the shade scrim and prompts for unlock.
     *
     * Defence in depth: (1) open a short keyguard window during which onExpandClicked is
     * blocked at its source (covers async/deferred dispatch and any second expand path);
     * (2) also null the listener field for the synchronous click. Shade/heads-up paths
     * never call this.
     */
    private fun clickExpandSilentlyOnKeyguard(row: Any, btn: View) {
        beginLsAutoExpandWindow()
        val f = getExpandClickListenerField(row)
        if (f == null) { btn.performClick(); return }   // fail open — never regress expansion
        var saved: Any? = null
        var nulled = false
        try {
            saved = f.get(row)
            if (saved != null) ensureOnExpandClickedHook(saved)
            f.set(row, null)
            nulled = true
        } catch (_: Throwable) {}
        try {
            btn.performClick()
        } finally {
            if (nulled) try { f.set(row, saved) } catch (_: Throwable) {}
        }
        Log.d("TweaksLS", "[${ts()}] LS silent expand row=${System.identityHashCode(row)} listenerSuppressed=$nulled hadListener=${saved != null}")
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
        if (!prefs.isFeatureEnabled(featureKey)) return true
        if (pkg != null && pkg in prefs.getExcludedApps()) return true
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
        if (prefs.isKillSwitchActive("disable_headsup_hooks_enabled")) return
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
                "com.oppo.quicksearchbox" -> try { handleGlobalSearch(lpparam) } catch (t: Throwable) {
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

    // =====================================================
    // Global Search — Enter launches the first result
    //
    // Pref `global_search_enter_launch_enabled` (opt-in, default OFF). On the OnePlus
    // app drawer the search box just launches the OEM global-search app
    // (com.oppo.quicksearchbox / SearchDrawActivity); pressing Enter there does nothing
    // by default. When this pref is ON we attach an editor-action listener to the search
    // input so Enter taps the first result row — which is how that app launches an app
    // (confirmed: a real touch on the row opens the app even though the row reports
    // clickable=false, i.e. it's handled by a RecyclerView touch listener).
    //
    // IDs/classes verified live on-device (uiautomator) + the app's resources:
    //   activity  com.oplus.globalsearch.ui.SearchDrawActivity
    //   input     id/search_bar_search_input  (android.widget.EditText)
    //   results   id/recycler_view_result     (RecyclerView; rows carry id/text_content)
    //
    // SAFETY: gated on the pref + full try/catch; OFF = the search app is untouched.
    // =====================================================
    private val LAUNCHER_TAG = "TweaksLauncher"
    private val GS_KEY = "global_search_enter_launch_enabled"
    @Volatile private var lastSearchLaunchTs = 0L
    @Volatile private var gsActivity: android.app.Activity? = null
    // The live search EditText — captured in onCreateInputConnection so the launch path is
    // activity-agnostic (the global search has two entry activities: SearchActivity from the
    // home swipe-down and SearchDrawActivity from the drawer; both reuse the same field id).
    @Volatile private var searchEditRef: java.lang.ref.WeakReference<android.view.View>? = null
    private val hookedIcClasses = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Resolve the current search activity from the focused field, falling back to gsActivity. */
    private fun currentSearchActivity(): android.app.Activity? =
        (searchEditRef?.get()?.let { activityOf(it) }) ?: gsActivity

    private fun handleGlobalSearch(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        // (1) onResume: supply an appContext from the activity (this third-party process has
        // none) so the Settings.Global pref channel is readable; track the live activity; and
        // wire a key-event fallback. The home swipe-down opens SearchActivity while the drawer
        // uses SearchDrawActivity — hook both so either entry point works.
        for (clsName in listOf(
            "com.oplus.globalsearch.ui.SearchActivity",
            "com.oplus.globalsearch.ui.SearchDrawActivity"
        )) {
            try {
                val act = XposedHelpers.findClass(clsName, cl)
                XposedBridge.hookAllMethods(act, "onResume", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val activity = param.thisObject as android.app.Activity
                            gsActivity = activity
                            if (prefs.appContext == null) {
                                prefs.appContext = activity.applicationContext
                                prefs.loadFilePrefs()
                            }
                            if (!prefs.isOptInEnabled(GS_KEY)) return
                            attachSearchKeyFallback(activity)
                        } catch (t: Throwable) { Log.d(LAUNCHER_TAG, "onResume hook err: $t") }
                    }
                })
                XposedBridge.hookAllMethods(act, "onPause", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (gsActivity === param.thisObject) gsActivity = null
                    }
                })
            } catch (t: Throwable) {
                Log.d(LAUNCHER_TAG, "activity hook install failed ($clsName): $t")
            }
        }
        // (2) View-level onEditorAction (covers any keyboard that routes through it).
        try {
            XposedHelpers.findAndHookMethod(
                android.widget.TextView::class.java, "onEditorAction",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!prefs.isOptInEnabled(GS_KEY)) return
                            val tv = param.thisObject as? android.widget.TextView ?: return
                            if (resourceEntryAndPkg(tv).first != "search_bar_search_input") return
                            val q = tv.text?.toString()?.trim().orEmpty()
                            Log.d(LAUNCHER_TAG, "[${ts()}] onEditorAction id=${param.args.getOrNull(0)} q=\"$q\"")
                            if (q.isEmpty()) return
                            val activity = activityOf(tv) ?: return
                            if (launchFirstDebounced(activity)) param.setResult(null)
                        } catch (t: Throwable) { Log.d(LAUNCHER_TAG, "onEditorAction hook err: $t") }
                    }
                })
        } catch (t: Throwable) {
            Log.d(LAUNCHER_TAG, "onEditorAction hook install failed: $t")
        }
        // (3) The OEM search field swallows the soft "Go" action inside its custom
        // InputConnection (it never reaches onEditorAction or a key event). Discover that
        // IC class at runtime from onCreateInputConnection, then hook its performEditorAction
        // / sendKeyEvent so the action is intercepted at the true source.
        try {
            XposedHelpers.findAndHookMethod(
                android.widget.TextView::class.java, "onCreateInputConnection",
                android.view.inputmethod.EditorInfo::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val tv = param.thisObject as? android.widget.TextView ?: return
                            if (resourceEntryAndPkg(tv).first != "search_bar_search_input") return
                            // Capture the field + an appContext here so the launch path works
                            // even before either entry activity's onResume hook runs.
                            searchEditRef = java.lang.ref.WeakReference(tv)
                            if (prefs.appContext == null) {
                                prefs.appContext = tv.context?.applicationContext
                                prefs.loadFilePrefs()
                            }
                            if (!prefs.isOptInEnabled(GS_KEY)) return
                            val ic = param.result ?: return
                            val info = param.args.getOrNull(0) as? android.view.inputmethod.EditorInfo
                            Log.d(LAUNCHER_TAG, "search IC=${ic.javaClass.name} imeOptions=${info?.imeOptions}")
                            hookSearchInputConnection(ic.javaClass)
                        } catch (t: Throwable) { Log.d(LAUNCHER_TAG, "onCreateIC hook err: $t") }
                    }
                })
            Log.d(LAUNCHER_TAG, "global search hook installed")
        } catch (t: Throwable) {
            Log.d(LAUNCHER_TAG, "onCreateIC hook install failed: $t")
        }
    }

    /**
     * Hook performEditorAction/sendKeyEvent on the search field's actual IC class (once).
     * The OEM uses the standard com.android.internal.inputmethod.EditableInputConnection,
     * whose performEditorAction/sendKeyEvent are *inherited* from BaseInputConnection — so
     * hookAllMethods(icClass, ...) would hook nothing. findAndHookMethod walks the superclass
     * chain to the real declaration, and a hook on the inherited method still fires for calls
     * made on the subclass instance.
     */
    private fun hookSearchInputConnection(icClass: Class<*>) {
        if (!hookedIcClasses.add(icClass.name)) return
        try {
            XposedHelpers.findAndHookMethod(
                icClass, "performEditorAction", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!prefs.isOptInEnabled(GS_KEY)) return
                            Log.d(LAUNCHER_TAG, "[${ts()}] IC.performEditorAction id=${param.args.getOrNull(0)} cls=${param.thisObject.javaClass.name}")
                            val activity = currentSearchActivity() ?: return
                            if (launchFirstDebounced(activity)) param.setResult(true) // consume
                        } catch (t: Throwable) { Log.d(LAUNCHER_TAG, "IC.performEditorAction err: $t") }
                    }
                })
            Log.d(LAUNCHER_TAG, "hooked IC.performEditorAction via ${icClass.name}")
        } catch (t: Throwable) {
            Log.d(LAUNCHER_TAG, "hook performEditorAction err: $t")
        }
        try {
            XposedHelpers.findAndHookMethod(
                icClass, "sendKeyEvent", KeyEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!prefs.isOptInEnabled(GS_KEY)) return
                            val ev = param.args.getOrNull(0) as? KeyEvent ?: return
                            if (ev.action != KeyEvent.ACTION_UP) return
                            if (ev.keyCode != KeyEvent.KEYCODE_ENTER && ev.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return
                            Log.d(LAUNCHER_TAG, "[${ts()}] IC.sendKeyEvent ENTER")
                            val activity = currentSearchActivity() ?: return
                            if (launchFirstDebounced(activity)) param.setResult(true)
                        } catch (t: Throwable) { Log.d(LAUNCHER_TAG, "IC.sendKeyEvent err: $t") }
                    }
                })
            Log.d(LAUNCHER_TAG, "hooked IC.sendKeyEvent via ${icClass.name}")
        } catch (t: Throwable) {
            Log.d(LAUNCHER_TAG, "hook sendKeyEvent err: $t")
        }
    }

    /** Fallback: some keyboards send a raw ENTER key rather than an editor action. */
    private fun attachSearchKeyFallback(activity: android.app.Activity) {
        try {
            val editId = activity.resources.getIdentifier("search_bar_search_input", "id", activity.packageName)
            if (editId == 0) return
            val edit = activity.findViewById<android.widget.TextView>(editId) ?: return
            edit.setOnKeyListener(android.view.View.OnKeyListener { v, keyCode, event ->
                try {
                    if (!prefs.isOptInEnabled(GS_KEY)) return@OnKeyListener false
                    if (event.action != KeyEvent.ACTION_UP) return@OnKeyListener false
                    if (keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER)
                        return@OnKeyListener false
                    if ((v as? android.widget.TextView)?.text?.toString()?.trim().isNullOrEmpty())
                        return@OnKeyListener false
                    Log.d(LAUNCHER_TAG, "[${ts()}] key ENTER fallback")
                    launchFirstDebounced(activity)
                } catch (t: Throwable) { Log.d(LAUNCHER_TAG, "key fallback err: $t"); false }
            })
        } catch (t: Throwable) { Log.d(LAUNCHER_TAG, "attachSearchKeyFallback err: $t") }
    }

    /** De-duplicated launch — the editor-action and key paths can both fire for one press. */
    private fun launchFirstDebounced(activity: android.app.Activity): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSearchLaunchTs < 1000L) return true
        val ok = launchFirstSearchResult(activity)
        if (ok) lastSearchLaunchTs = now
        return ok
    }

    /** Unwrap a view's context chain to the hosting Activity. */
    private fun activityOf(v: android.view.View): android.app.Activity? {
        var c: Context? = v.context
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * Tap the first result row in the global-search results list. The two entry activities use
     * different list ids (SearchActivity → recycler_view_shelf, SearchDrawActivity →
     * recycler_view_result), so rather than hard-code a recycler id we walk the whole decor
     * tree for the first visible "text_content" view — that id marks a real result row (section
     * headers use text_title), and tapping anywhere on the row launches it.
     */
    private fun launchFirstSearchResult(activity: android.app.Activity): Boolean {
        return try {
            val textId = activity.resources.getIdentifier("text_content", "id", activity.packageName)
            if (textId == 0) { Log.d(LAUNCHER_TAG, "no text_content id"); return false }
            val root = activity.window?.decorView ?: return false
            val first = findFirstVisibleById(root, textId) ?: run {
                Log.d(LAUNCHER_TAG, "no result row found"); return false
            }
            val label = (first as? android.widget.TextView)?.text
            Log.d(LAUNCHER_TAG, "first result = \"$label\"")
            tapViewInWindow(activity, first)
            true
        } catch (t: Throwable) {
            Log.d(LAUNCHER_TAG, "launchFirstSearchResult err: $t"); false
        }
    }

    /** Depth-first (visual top-to-bottom) search for the first shown view with the given id. */
    private fun findFirstVisibleById(v: android.view.View, id: Int): android.view.View? {
        if (v.visibility != View.VISIBLE) return null
        if (v.id == id && v.width > 0 && v.height > 0) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                findFirstVisibleById(v.getChildAt(i) ?: continue, id)?.let { return it }
            }
        }
        return null
    }

    /**
     * Replay a real touch on a view by dispatching DOWN+UP at its on-screen centre to the
     * window's decor view. The result rows launch on a genuine touch (RecyclerView touch
     * listener) rather than View.performClick(), so a synthetic motion event is required.
     */
    private fun tapViewInWindow(activity: android.app.Activity, v: android.view.View) {
        val loc = IntArray(2); v.getLocationInWindow(loc)
        val x = loc[0] + v.width / 2f
        val y = loc[1] + v.height / 2f
        val decor = activity.window?.decorView ?: v.rootView
        val now = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_UP, x, y, 0)
        try {
            decor.dispatchTouchEvent(down)
            decor.dispatchTouchEvent(up)
        } finally { down.recycle(); up.recycle() }
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
                                if (!prefs.isFeatureEnabled("enable_snapper_entirely")) return
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
                                            if (!prefs.isFeatureEnabled("snapper_hardware_chord_enabled")) return
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
                                if (!prefs.isFeatureEnabled("enable_snapper_entirely")) return
                                val gestureEvent = param.args.getOrNull(0) ?: return
                                // args[0] is KeyGestureEvent — use toString() to identify
                                // SCREENSHOT_CHORD (confirmed "KEY_GESTURE_TYPE_SCREENSHOT_CHORD")
                                if (!gestureEvent.toString().contains("SCREENSHOT_CHORD")) return
                                if (!prefs.isFeatureEnabled("snapper_hardware_chord_enabled")) return

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
                            prefs.appContext = app
                            Log.d("Snapper", "DIAG: SystemUI hook init — appContext captured, pkg=${app.packageName}")
                            prefs.loadFilePrefs()
                            prefs.startFileObserver()
                            prefs.startHeartbeatThread()
                            registerZoneActionReceiver(app)
                            registerPrefChangedReceiver(app)
                            // Apply keep-screen-on from the persisted pref (default OFF).
                            applyKeepScreenOn(prefs.isOptInEnabled("keep_screen_on_enabled"))
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
                                    prefs.isKillSwitchActive("disable_headsup_hooks_enabled")) {
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
                            // Re-entry fix: aeAutoExpanded is a per-row one-shot, so a group the
                            // system re-collapses between shade opens won't re-expand on later opens.
                            // A fresh open is the first fire after a quiet gap (in-open fires cluster
                            // <=~205ms; real re-opens are >=~300ms apart), so clear the one-shot then
                            // to allow exactly one re-apply per open.
                            val nowTs = android.os.SystemClock.uptimeMillis()
                            val lastTs = XposedHelpers.getAdditionalInstanceField(row, "aeLastSysExpTs") as? Long ?: 0L
                            XposedHelpers.setAdditionalInstanceField(row, "aeLastSysExpTs", nowTs)
                            if (nowTs - lastTs > 300L) XposedHelpers.setAdditionalInstanceField(row, "aeAutoExpanded", false)
                            val already = XposedHelpers.getAdditionalInstanceField(row, "aeAutoExpanded") as? Boolean ?: false
                            if (already) return
                            XposedHelpers.setAdditionalInstanceField(row, "aeAutoExpanded", true)
                            // Defer to next frame — running synchronously during the layout pass
                            // that just called setSystemExpanded(false) gets undone by that pass.
                            rowView.post {
                                try {
                                    // Keyguard: block the onExpandClicked → locked-shade side effect
                                    // for any expand the fallback performClick might trigger.
                                    if (onKG) beginLsAutoExpandWindow()
                                    val ok = try {
                                        XposedHelpers.callMethod(row, "setUserExpanded", true, true); true
                                    } catch (_: Throwable) {
                                        try { XposedHelpers.callMethod(row, "setUserExpanded", true); true } catch (_: Throwable) { false }
                                    }
                                    if (!ok) findStrictExpandButton(rowView)?.performClick()
                                    // Do NOT clear aeAutoExpanded here — re-entry is handled by the
                                    // quiet-gap reset above. (A previous self-heal keyed off a no-arg
                                    // isExpanded() that always failed on this build, clearing the
                                    // one-shot every frame and corrupting groups.)
                                    Log.d(HUD_TAG, "[${ts()}] shade/LS post-expand row=${System.identityHashCode(row)} pkg=$pkg onKG=$onKG ok=$ok")
                                } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}

        // =====================================================
        // UNIVERSAL EXPAND DRIVER (Issue 1) — single + grouped,
        // shade + lockscreen, on EVERY open.
        // LogCat proved (a) setSystemExpanded only fires on
        // notification post/set-change, NOT on plain re-opens, and
        // (b) only clicking the real system expand arrow actually
        // sticks — setUserExpanded returns true but doesn't hold.
        // onLayout fires on every open, so it is the reliable place
        // to click the arrow. Per-type collapse signal so an already
        // open row is never toggled shut: group summary uses
        // areChildrenExpanded()==false; single uses isShowingExpanded()
        // ==false (isGroupExpanded() does not exist on this build).
        // aeAutoExpanded one-shot + 300ms quiet-gap reset = exactly
        // one click per open and no relayout feedback loop.
        // =====================================================
        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass(rowClass, lpparam.classLoader),
                "onLayout",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val row = param.thisObject
                            val rowView = row as? View ?: return
                            // Heads-up has its own dedicated expand path; leave it untouched.
                            val isHU = try { XposedHelpers.getBooleanField(row, "mIsHeadsUp") } catch (_: Throwable) { false }
                            if (isHU) return
                            // Child rows are driven by their parent's expansion — skip.
                            val isChild = try { XposedHelpers.callMethod(row, "isChildInGroup") as? Boolean ?: false } catch (_: Throwable) { false }
                            if (isChild) return
                            if (!rowView.isShown) return      // only when actually visible
                            val isGroup = isGroupSummaryRow(row)
                            val onKG = try { XposedHelpers.getBooleanField(row, "mOnKeyguard") } catch (_: Throwable) { false }
                            val pkg = getNotificationPackage(row)
                            val featureKey = if (onKG) "expand_lockscreen_enabled" else "expand_shade_enabled"
                            if (shouldSkipNotification(featureKey, pkg)) return
                            val nowTs = android.os.SystemClock.uptimeMillis()
                            val lastTs = XposedHelpers.getAdditionalInstanceField(row, "aeLastLayoutTs") as? Long ?: 0L
                            XposedHelpers.setAdditionalInstanceField(row, "aeLastLayoutTs", nowTs)
                            if (nowTs - lastTs > 300L) XposedHelpers.setAdditionalInstanceField(row, "aeAutoExpanded", false)
                            val already = XposedHelpers.getAdditionalInstanceField(row, "aeAutoExpanded") as? Boolean ?: false
                            if (already) return
                            XposedHelpers.setAdditionalInstanceField(row, "aeAutoExpanded", true)
                            rowView.post {
                                try {
                                    fun st(m: String): Any? = try { XposedHelpers.callMethod(row, m) } catch (_: Throwable) { null }
                                    // Per-type collapse signal — never click an already-open row shut.
                                    val collapsed = if (isGroup) st("areChildrenExpanded") == false
                                                    else st("isShowingExpanded") == false
                                    val btn = findStrictExpandButton(rowView)
                                    if (collapsed && btn != null) {
                                        if (onKG) clickExpandSilentlyOnKeyguard(row, btn)
                                        else btn.performClick()       // the only reliable, instant expand
                                    } else if (collapsed) {
                                        // No arrow found (rare) — fall back to state set.
                                        try { XposedHelpers.callMethod(row, "setUserExpanded", true, true) }
                                        catch (_: Throwable) { try { XposedHelpers.callMethod(row, "setUserExpanded", true) } catch (_: Throwable) {} }
                                    }
                                    Log.d(HUD_TAG, "[${ts()}] onLayout expand row=${System.identityHashCode(row)} pkg=$pkg group=$isGroup onKG=$onKG collapsed=$collapsed btn=${btn!=null}")
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
                        if (!prefs.isFeatureEnabled("disable_headsup_popup_enabled")) return
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
                    if (!prefs.isFeatureEnabled("disable_headsup_popup_enabled")) return
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
                    if (!prefs.isFeatureEnabled("disable_headsup_popup_enabled")) return
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
                            if (!prefs.isFeatureEnabled("expand_headsup_enabled")) return
                            if (prefs.isKillSwitchActive("disable_headsup_hooks_enabled")) return
                            val maxLines = prefs.getIntPref("headsup_max_lines", 5)
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
                        if (!prefs.isFeatureEnabled("disable_back_haptic_enabled")) return
                        param.result = null
                    }
                }
            )
        } catch (_: Throwable) {}

        hookStatusBarZones(lpparam)
    }

    private fun handleZoneTouch(ev: MotionEvent, view: android.view.View, ctx: android.content.Context) {
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

    // =========================================================================
    // Status Bar Zones — privileged action receiver
    // =========================================================================

    /**
     * Live pref updates from the app (no reboot). The app sends
     * io.github.kvmy666.autoexpand.PREF_CHANGED (key/value extras) on every toggle.
     * We refresh the cache and apply behaviors that need an explicit trigger
     * (currently keep-screen-on, which adds/removes its overlay window here).
     */
    private fun registerPrefChangedReceiver(app: android.app.Application) {
        try {
            val filter = android.content.IntentFilter("io.github.kvmy666.autoexpand.PREF_CHANGED")
            app.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                    try {
                        prefs.loadFilePrefs()
                        val key = intent.getStringExtra("key") ?: return
                        val value = intent.getStringExtra("value")
                        if (key == "keep_screen_on_enabled") {
                            applyKeepScreenOn(value == "1")
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
