package io.github.kvmy666.autoexpand

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.KeyEvent
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

class KeyboardHook : IXposedHookLoadPackage {

    private val TAG = "AutoExpand"
    private val GBOARD_PKG = "com.google.android.inputmethod.latin"
    private val PREFS_FILE = "/data/local/tmp/tweaks_prefs.json"
    @Volatile private var filePrefCache: Map<String, String>? = null
    private val dbExecutor = Executors.newSingleThreadExecutor()

    // Cached prefs (refreshed every 2s)
    @Volatile private var cachedEnabled = true
    @Volatile private var cachedMultiplier = 1.0f       // toolbar HEIGHT scale
    @Volatile private var cachedBtnMultiplier = 1.0f    // button SIZE scale (width + glyph), independent of height
    @Volatile private var cachedShortcut1 = ""
    @Volatile private var cachedShortcut2 = ""
    @Volatile private var cachedMaxEntries = 500
    @Volatile private var cachedBtnClipboard = true
    @Volatile private var cachedBtnPaste     = true
    @Volatile private var cachedBtnSelectAll = true
    @Volatile private var cachedBtnCursor = false   // A3: cursor-nav button OFF by default
    @Volatile private var cachedBtnShortcut = true
    @Volatile private var cachedBtnTrackpad = true
    // Trackpad stick haptics (grab pop + steering ticks). Independent of the stick itself.
    @Volatile private var cachedTrackpadHaptics = true
    // CopyVault row rendering: full text (default) vs first-line-only truncation.
    @Volatile private var cachedClipFullText = true
    // Sensitivity: >1 = faster (smaller effective step). Tunable via pref "trackpad_sensitivity".
    @Volatile private var cachedTrackpadSensitivity = 1.0f
    // When true, deliver DPAD via privileged `input keyevent` (root) instead of the
    // InputConnection — fallback for apps that swallow IME DPAD events.
    @Volatile private var cachedTrackpadRoot = false
    @Volatile private var lastCacheTime = 0L
    private val CACHE_INTERVAL_MS = 2000L

    // ── 2D trackpad cursor tuning ──
    // px of finger travel per one DPAD step. Smaller = more sensitive / faster.
    // stepY < stepX so vertical (line) movement is brisk — "3 lines in a blink".
    private val TRACKPAD_STEP_X = 16f
    private val TRACKPAD_STEP_Y = 22f

    // Persistent root shell for the privileged fallback (lazy; avoids per-step su spawn).
    @Volatile private var rootStdin: java.io.OutputStream? = null

    // Throttles per-step trackpad ticks so a fast flick can't flood the vibrator.
    @Volatile private var lastTickMs = 0L
    @Volatile private var vibrator: Vibrator? = null

    private var clipboardDb: ClipboardDatabase? = null
    private var activeImsRef: InputMethodService? = null
    @Volatile private var clipSortMode = ClipboardDatabase.SortMode.NEWEST

    // Last drag position of the floating selection bar (null = default placement).
    // Persisted across opens so the bar reappears where the user left it.
    @Volatile private var selMenuOffX: Int? = null
    @Volatile private var selMenuOffY: Int? = null

    // ── Undo (restore deleted text) — Phase B1 ──
    // One in-memory ring buffer per IME process. Every deletion path (backspace,
    // cut, select-all+delete, selection overwritten by typing/paste) is captured
    // UNIFORMLY by diffing the editor text on each onUpdateSelection against a
    // shadow snapshot — no per-path hooking, works in every app/WebView.
    @Volatile private var cachedUndoEnabled = true       // master (undo_enabled)
    @Volatile private var cachedUndoButton  = true       // toolbar button (undo_button_enabled)
    private data class UndoRecord(
        var deletedText: String,
        var anchor: Int,          // absolute index where the text was removed
        var timestamp: Long,
        val sessionId: Int
    )
    private val undoStack = ArrayDeque<UndoRecord>()      // newest = last()
    private val UNDO_MAX_DEPTH = 20
    private val UNDO_MAX_CHARS = 20000
    // Deletion runs: a continuous deletion burst collapses into ONE undo record as
    // long as deletes stay contiguous and no pause exceeds RUN_PAUSE_MS. A real cursor
    // move, an insertion, or a field/app change ends the run (see onSelectionUpdate).
    private val RUN_PAUSE_MS = 1000L                      // max gap inside one deletion run
    private val ECHO_WINDOW_MS = 250L                     // ignore the no-op selection echo right after our own edit
    private val UNDO_TRACK_LIMIT = 20000                  // don't shadow giant fields
    @Volatile private var undoSessionId = 0
    @Volatile private var undoRunId = 0                   // increments whenever a new run/record starts
    @Volatile private var runOpen = false                 // current top record can still absorb more deletes
    @Volatile private var lastEditMs = 0L                 // uptime of the last captured text edit
    @Volatile private var shadowText: String? = null      // last-known full field text
    @Volatile private var shadowValid = false
    @Volatile private var suppressUndoCapture = false     // true while WE re-insert

    // ── Shake-to-undo (B3) — shake to restore the last deletion via a Cancel/Undo alert ──
    @Volatile private var cachedShakeUndo = true          // shake_undo_enabled
    @Volatile private var cachedShakeSensitivity = 1.0f   // shake_sensitivity (>1 = more sensitive)
    // Global haptic strength 0..100 (applies to trackpad ticks + shake-undo confirm).
    // 100 = device-tuned predefined effect (original feel); below = amplitude/duration scaled.
    @Volatile private var cachedVibStrength = 100         // vibration_strength
    private val SHAKE_BASE_THRESHOLD_G = 2.7f             // |a|/g spike to count as a shake
    private val SHAKE_COOLDOWN_MS = 800L                  // one shake = one prompt
    @Volatile private var lastShakeMs = 0L
    @Volatile private var sensorManager: SensorManager? = null
    @Volatile private var shakeListener: SensorEventListener? = null
    @Volatile private var sensorRegistered = false
    @Volatile private var toolbarView: View? = null       // anchor for the centered alert
    @Volatile private var undoAlertShowing = false
    // Lazy: must NOT touch the framework at class-construction time. LSPosed instantiates
    // this hook class during load; an eager Handler(Looper.getMainLooper()) here can throw
    // and abort the whole module load (→ no toolbar). Created on first shake instead.
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // ─────────────────────────────────────────────────────
    // Prefs
    // ─────────────────────────────────────────────────────

    private fun refreshPrefs(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastCacheTime < CACHE_INTERVAL_MS) return
        lastCacheTime = now
        try {
            val text = File(PREFS_FILE).readText()
            val json = JSONObject(text)
            val map = mutableMapOf<String, String>()
            for (key in json.keys()) map[key] = json.getString(key)
            filePrefCache = map
        } catch (_: Throwable) {}
        filePrefCache?.let { cache ->
            // String/float/int prefs: keep cached value if key absent
            cachedMultiplier    = cache["toolbar_height_multiplier"]?.toFloatOrNull() ?: cachedMultiplier
            cachedBtnMultiplier = cache["toolbar_button_multiplier"]?.toFloatOrNull() ?: cachedBtnMultiplier
            cachedShortcut1    = cache["shortcut_text_1"] ?: cachedShortcut1
            cachedShortcut2    = cache["shortcut_text_2"] ?: cachedShortcut2
            cachedMaxEntries   = cache["clipboard_max_entries"]?.toIntOrNull() ?: cachedMaxEntries
            // Boolean prefs: use ?. so a missing key keeps the default (true) instead of flipping to false
            cachedEnabled      = cache["keyboard_enhancer_enabled"]?.let { it == "1" } ?: cachedEnabled
            cachedBtnClipboard = cache["btn_clipboard_enabled"]?.let { it == "1" } ?: cachedBtnClipboard
            cachedBtnPaste     = cache["btn_paste_enabled"]?.let { it == "1" } ?: cachedBtnPaste
            cachedBtnSelectAll = cache["btn_selectall_enabled"]?.let { it == "1" } ?: cachedBtnSelectAll
            cachedBtnCursor    = cache["btn_cursor_enabled"]?.let { it == "1" } ?: cachedBtnCursor
            cachedBtnShortcut  = cache["btn_shortcut_enabled"]?.let { it == "1" } ?: cachedBtnShortcut
            cachedBtnTrackpad  = cache["btn_trackpad_enabled"]?.let { it == "1" } ?: cachedBtnTrackpad
            cachedTrackpadHaptics = cache["trackpad_haptics_enabled"]?.let { it == "1" } ?: cachedTrackpadHaptics
            cachedClipFullText = cache["clip_full_text_enabled"]?.let { it == "1" } ?: cachedClipFullText
            cachedUndoEnabled  = cache["undo_enabled"]?.let { it == "1" } ?: cachedUndoEnabled
            cachedUndoButton   = cache["undo_button_enabled"]?.let { it == "1" } ?: cachedUndoButton
            cachedShakeUndo    = cache["shake_undo_enabled"]?.let { it == "1" } ?: cachedShakeUndo
            cachedShakeSensitivity = cache["shake_sensitivity"]?.toFloatOrNull() ?: cachedShakeSensitivity
            cachedVibStrength  = cache["vibration_strength"]?.toIntOrNull() ?: cachedVibStrength
            cachedTrackpadSensitivity = cache["trackpad_sensitivity"]?.toFloatOrNull() ?: cachedTrackpadSensitivity
            cachedTrackpadRoot = cache["trackpad_root_injection_enabled"]?.let { it == "1" } ?: cachedTrackpadRoot
        }
    }

    // ─────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != GBOARD_PKG) return
        try { installKeyboardHooks(lpparam) } catch (t: Throwable) {
            XposedBridge.log("$TAG KeyboardHook init failed (silent): $t")
        }
    }

    private fun installKeyboardHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("$TAG KeyboardHook loaded in $GBOARD_PKG")

        // ── Phase 1: Reconnaissance logging ──────────────
        hookLifecycleForLogs(lpparam)

        // ── Phase 2+3: Toolbar injection via setInputView ─
        hookSetInputView(lpparam)

        // ── Undo (B1): track deletions + scope per input session ─
        hookEditTracking(lpparam)
    }

    // ─────────────────────────────────────────────────────
    // Undo — deletion tracking + session scoping (B1)
    // onUpdateSelection fires after every edit; we diff the editor text against a
    // shadow snapshot to recover exactly what was removed (universal capture).
    // onStartInput bumps the session id + clears history so undo can never restore
    // text into a different field or app.
    // ─────────────────────────────────────────────────────
    private fun hookEditTracking(lpparam: XC_LoadPackage.LoadPackageParam) {
        val imsClass = "android.inputmethodservice.InputMethodService"
        try {
            XposedHelpers.findAndHookMethod(
                imsClass, lpparam.classLoader, "onStartInput",
                EditorInfo::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // New (or restarted) field → start a fresh undo session; any
                        // open deletion run is closed so it can't bleed into a new field.
                        synchronized(undoStack) { undoStack.clear() }
                        undoSessionId++
                        shadowText = null; shadowValid = false
                        runOpen = false; lastEditMs = 0L
                        XposedBridge.log("$TAG [KB] undo session=$undoSessionId (onStartInput, cleared)")
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] onStartInput hook failed: ${t.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                imsClass, lpparam.classLoader, "onUpdateSelection",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ims = param.thisObject as? InputMethodService ?: return
                        try { onSelectionUpdate(ims) } catch (_: Throwable) {}
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] onUpdateSelection hook failed: ${t.message}")
        }

        // Shake listener follows the keyboard's VISIBLE lifecycle: register when the
        // input view shows, unregister when it hides — no battery drain when idle.
        try {
            XposedHelpers.findAndHookMethod(
                imsClass, lpparam.classLoader, "onStartInputView",
                EditorInfo::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ims = param.thisObject as? InputMethodService ?: return
                        try { refreshPrefs(ims.applicationContext); registerShake(ims.applicationContext) } catch (_: Throwable) {}
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] onStartInputView(shake) hook failed: ${t.message}")
        }
        try {
            XposedHelpers.findAndHookMethod(
                imsClass, lpparam.classLoader, "onFinishInputView",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) { unregisterShake() }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] onFinishInputView(shake) hook failed: ${t.message}")
        }
    }

    // ── Shake detection ──
    private fun registerShake(ctx: Context) {
        if (sensorRegistered) return
        if (!cachedUndoEnabled || !cachedShakeUndo) return
        try {
            val sm = sensorManager
                ?: (ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.also { sensorManager = it }
            val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sm == null || accel == null) {
                XposedBridge.log("$TAG [KB] shake: no accelerometer — disabled")
                return
            }
            val listener = shakeListener ?: object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    val gx = e.values[0] / SensorManager.GRAVITY_EARTH
                    val gy = e.values[1] / SensorManager.GRAVITY_EARTH
                    val gz = e.values[2] / SensorManager.GRAVITY_EARTH
                    val gForce = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
                    val threshold = SHAKE_BASE_THRESHOLD_G / cachedShakeSensitivity.coerceIn(0.1f, 2.0f)
                    if (gForce > threshold) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastShakeMs < SHAKE_COOLDOWN_MS) return   // debounce
                        lastShakeMs = now
                        // Sensor thread → marshal to main before touching the IC / UI.
                        mainHandler.post { onShakeDetected(ctx) }
                    }
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }.also { shakeListener = it }
            sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
            sensorRegistered = true
            XposedBridge.log("$TAG [KB] shake: registered")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] shake register failed: ${t.message}")
        }
    }

    private fun unregisterShake() {
        try {
            val sm = sensorManager ?: return
            val l = shakeListener ?: return
            if (sensorRegistered) {
                sm.unregisterListener(l)
                sensorRegistered = false
                XposedBridge.log("$TAG [KB] shake: unregistered")
            }
        } catch (_: Throwable) {}
    }

    // Runs on the MAIN thread. A shake offers to restore the last
    // deletion via a Cancel/Undo alert — never auto-applies. No-op if nothing to undo.
    private fun onShakeDetected(ctx: Context) {
        if (!cachedUndoEnabled || !cachedShakeUndo) return
        if (undoAlertShowing) return
        val ims = activeImsRef ?: return
        val anchor = toolbarView ?: return
        if (!anchor.isShown) return
        val hasUndo = synchronized(undoStack) { undoStack.isNotEmpty() }
        if (!hasUndo) { XposedBridge.log("$TAG [KB] shake: nothing to undo"); return }
        XposedBridge.log("$TAG [KB] shake: detected → alert")
        showUndoAlert(ctx, ims, anchor)
    }

    // "Undo" confirmation: dim scrim + centered OLED card, Cancel | Undo.
    private fun showUndoAlert(ctx: Context, ims: InputMethodService, anchor: View) {
        val dp = ctx.resources.displayMetrics.density
        val preview = synchronized(undoStack) { undoStack.lastOrNull()?.deletedText } ?: return
        undoAlertShowing = true
        hapticEffect(ctx, VibrationEffect.EFFECT_HEAVY_CLICK, 40L)   // grab attention

        val scrim = FrameLayout(ctx).apply { setBackgroundColor(Color.parseColor("#99000000")) }
        val popup = PopupWindow(scrim,
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isClippingEnabled = false
            isOutsideTouchable = true
        }
        fun close() {
            try { popup.dismiss() } catch (_: Throwable) {}
            undoAlertShowing = false
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(UI.ELEVATED, 22f * dp, UI.DIVIDER, (1f * dp).toInt())
            isClickable = true   // absorb taps so they don't fall through to the scrim
            val px = (22f * dp).toInt()
            setPadding(px, (18f * dp).toInt(), px, 0)
        }
        card.addView(TextView(ctx).apply {
            text = "Undo Delete"
            setTextColor(UI.TEXT); textSize = 17f; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val snippet = preview.replace("\n", " ").let { if (it.length > 60) it.take(60) + "…" else it }
        card.addView(TextView(ctx).apply {
            text = "Restore “$snippet”?"
            setTextColor(UI.TEXT_DIM); textSize = 13f; gravity = Gravity.CENTER
            setPadding(0, (8f * dp).toInt(), 0, (16f * dp).toInt())
        })
        card.addView(View(ctx).apply {
            setBackgroundColor(UI.DIVIDER)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1f * dp).toInt())
        })

        fun alertBtn(label: String, bold: Boolean, color: Int, onClick: () -> Unit) = TextView(ctx).apply {
            text = label
            setTextColor(color); textSize = 16f; gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            minHeight = (52f * dp).toInt()
            background = ripple(null, 0f)
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() }
        }
        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnRow.addView(
            alertBtn("Cancel", false, UI.TEXT_DIM) { close() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        btnRow.addView(View(ctx).apply {
            setBackgroundColor(UI.DIVIDER)
            layoutParams = LinearLayout.LayoutParams((1f * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
        })
        btnRow.addView(
            alertBtn("Undo", true, UI.ACCENT) { undo(ims); close() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        card.addView(btnRow)

        scrim.addView(card, FrameLayout.LayoutParams(
            (300f * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        scrim.setOnClickListener { close() }   // tap outside the card = Cancel

        try {
            popup.showAtLocation(anchor, Gravity.CENTER, 0, 0)
        } catch (t: Throwable) {
            undoAlertShowing = false
            XposedBridge.log("$TAG [KB] undo alert show failed: ${t.message}")
        }
    }

    // Read the current full field text and diff it against the shadow snapshot.
    // Any net removal (pure delete, or a selection replaced by typing/paste) is
    // recorded as an UndoRecord; insertions just refresh the snapshot.
    private fun onSelectionUpdate(ims: InputMethodService) {
        if (!cachedUndoEnabled || suppressUndoCapture) return
        val ic = ims.currentInputConnection ?: return
        val ext = try {
            ic.getExtractedText(ExtractedTextRequest().apply { token = 0 }, 0)
        } catch (_: Throwable) { null }
        val cur = ext?.text?.toString()
        // Can't read text, partial window, or oversized field → stop tracking it.
        if (cur == null || (ext.startOffset != 0) || cur.length > UNDO_TRACK_LIMIT) {
            shadowValid = false; runOpen = false; return
        }
        val old = if (shadowValid) shadowText else null
        if (old == null) { shadowText = cur; shadowValid = true; return }   // first snapshot, nothing to diff

        val now = SystemClock.uptimeMillis()
        if (cur == old) {
            // No text change → a pure selection / cursor move. The settling echo that
            // fires immediately after OUR OWN edit also lands here (text already equals
            // the shadow); ignore it within ECHO_WINDOW_MS so it can't split a run.
            // A move that happens LATER is a genuine reposition → it ends the run.
            if (runOpen && now - lastEditMs > ECHO_WINDOW_MS) {
                runOpen = false
                XposedBridge.log("$TAG [KB] undo run-break: cursor move (echo=false)")
            }
            return
        }

        // Text changed → isolate the changed middle slice via common prefix/suffix.
        var p = 0
        val maxP = minOf(old.length, cur.length)
        while (p < maxP && old[p] == cur[p]) p++
        var s = 0
        while (s < (minOf(old.length, cur.length) - p) &&
               old[old.length - 1 - s] == cur[cur.length - 1 - s]) s++
        val removed  = old.substring(p, old.length - s)
        val inserted = cur.substring(p, cur.length - s)
        when {
            removed.isNotEmpty() && inserted.isEmpty() ->
                // Pure deletion → part of the current deletion run (coalescable).
                pushDeletion(removed, p, coalescable = true)
            removed.isNotEmpty() && inserted.isNotEmpty() -> {
                // Selection overwritten by typing/paste/autocorrect: store the removed
                // block as its own single record, and end the run.
                pushDeletion(removed, p, coalescable = false)
                runOpen = false
            }
            else ->
                // Pure insertion → ends any open deletion run; nothing to capture.
                if (runOpen) { runOpen = false; XposedBridge.log("$TAG [KB] undo run-break: insertion") }
        }
        lastEditMs = now
        shadowText = cur; shadowValid = true
    }

    // Record a deletion. A coalescable delete merges into the open run if it stays
    // contiguous and within RUN_PAUSE_MS (one continuous erase = one undo).
    // A non-coalescable delete (e.g. a selection replaced by typing) is always its own
    // record. Backspace prepends (it removes the char BEFORE the cursor), forward-delete
    // appends — so the restored text reads in natural order.
    private fun pushDeletion(removed: String, anchor: Int, coalescable: Boolean) {
        if (removed.isEmpty()) return
        synchronized(undoStack) {
            val now = SystemClock.uptimeMillis()
            val top = undoStack.lastOrNull()
            if (coalescable && runOpen && top != null && top.sessionId == undoSessionId &&
                now - top.timestamp <= RUN_PAUSE_MS) {
                // Backspace: this removal sits immediately before the previous one → prepend.
                if (anchor + removed.length == top.anchor) {
                    top.deletedText = removed + top.deletedText
                    top.anchor = anchor; top.timestamp = now
                    XposedBridge.log("$TAG [KB] undo COALESCE< run=$undoRunId prepend len=${removed.length} -> '${top.deletedText.take(30)}'")
                    return
                }
                // Forward-delete: this removal sits at the same anchor as the previous → append.
                if (anchor == top.anchor) {
                    top.deletedText = top.deletedText + removed
                    top.timestamp = now
                    XposedBridge.log("$TAG [KB] undo COALESCE> run=$undoRunId append len=${removed.length}")
                    return
                }
                // Non-contiguous: the run is effectively over → fall through to a new record.
            }
            undoRunId++
            undoStack.addLast(UndoRecord(removed, anchor, now, undoSessionId))
            while (undoStack.size > UNDO_MAX_DEPTH) undoStack.removeFirst()
            var total = undoStack.sumOf { it.deletedText.length }
            while (total > UNDO_MAX_CHARS && undoStack.size > 1) {
                total -= undoStack.removeFirst().deletedText.length
            }
            runOpen = coalescable   // a new run is "open" only if more deletes may join it
            XposedBridge.log("$TAG [KB] undo PUSH run=$undoRunId coalescable=$coalescable anchor=$anchor len=${removed.length} depth=${undoStack.size} text='${removed.take(30)}'")
        }
    }

    // Restore the most recent deletion: re-insert at its anchor, caret after the text.
    // If the anchor is no longer valid (text changed a lot), fall back to the cursor.
    private fun undo(ims: InputMethodService) {
        val rec = synchronized(undoStack) { undoStack.removeLastOrNull() }
        if (rec == null) { XposedBridge.log("$TAG [KB] undo: nothing to restore"); return }
        runOpen = false   // restoring is not a deletion run; next delete starts fresh
        val ic = ims.currentInputConnection ?: return
        suppressUndoCapture = true
        try {
            val len = readLen(ims)
            val valid = rec.anchor >= 0 && (len < 0 || rec.anchor <= len)
            ic.beginBatchEdit()
            if (valid) {
                ic.setSelection(rec.anchor, rec.anchor)
                ic.commitText(rec.deletedText, 1)
                XposedBridge.log("$TAG [KB] undo APPLY anchor=${rec.anchor} len=${rec.deletedText.length} text='${rec.deletedText.take(40)}'")
            } else {
                ic.commitText(rec.deletedText, 1)  // fallback: at current cursor
                XposedBridge.log("$TAG [KB] undo APPLY fallback-at-cursor len=${rec.deletedText.length} text='${rec.deletedText.take(40)}' (anchor=${rec.anchor} len=$len)")
            }
            ic.endBatchEdit()
            // Refresh the shadow so this re-insertion isn't recaptured as an edit.
            val ext = ic.getExtractedText(ExtractedTextRequest().apply { token = 0 }, 0)
            shadowText = ext?.text?.toString(); shadowValid = shadowText != null
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] undo error: ${t.message}")
        } finally {
            suppressUndoCapture = false
        }
    }

    // ─────────────────────────────────────────────────────
    // Phase 1 — Logging only
    // ─────────────────────────────────────────────────────

    private fun hookLifecycleForLogs(lpparam: XC_LoadPackage.LoadPackageParam) {
        val imsClass = "android.inputmethodservice.InputMethodService"

        // onStartInputView — log EditorInfo
        try {
            XposedHelpers.findAndHookMethod(
                imsClass, lpparam.classLoader,
                "onStartInputView",
                EditorInfo::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val ei = param.args[0] as? EditorInfo
                        XposedBridge.log("$TAG [KB] onStartInputView package=${ei?.packageName} inputType=${ei?.inputType} restarting=${param.args[1]}")
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] onStartInputView hook failed: ${t.message}")
        }

        // getWindow — log once (first call only) for window type reference
        var windowLogged = false
        try {
            XposedHelpers.findAndHookMethod(
                imsClass, lpparam.classLoader,
                "getWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (windowLogged) return
                        windowLogged = true
                        try {
                            val dialog = param.result
                            val window = XposedHelpers.callMethod(dialog, "getWindow")
                            val attrs = XposedHelpers.callMethod(window, "getAttributes")
                            XposedBridge.log("$TAG [KB] getWindow (once) attrs=${attrs}")
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] getWindow hook failed: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────
    // Phase 2+3 — setInputView toolbar injection [AFTER hook]
    //
    // WHY after (not before):
    //   Gboard subclasses InputMethodService and overrides setInputView.
    //   Its override likely casts the incoming View to its own InputView type
    //   BEFORE calling super.setInputView(). If we replace param.args[0] with
    //   our LinearLayout in a [before] hook, Gboard's cast throws a silent
    //   ClassCastException and the view is never placed correctly.
    //
    // FIX: hook [after] — by then Gboard has already placed the keyboard view
    //   into mInputFrame correctly. We access mInputFrame via reflection, pull
    //   the keyboard view out, wrap it in our container + toolbar, and put the
    //   container back. No type issues possible.
    // ─────────────────────────────────────────────────────

    private fun hookSetInputView(lpparam: XC_LoadPackage.LoadPackageParam) {
        val imsClass = "android.inputmethodservice.InputMethodService"
        val TOOLBAR_TAG = "ae_kb_toolbar"
        var callCount = 0

        try {
            XposedHelpers.findAndHookMethod(
                imsClass, lpparam.classLoader,
                "setInputView", View::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        callCount++
                        try {
                            val ims = param.thisObject as? InputMethodService ?: return
                            val ctx = ims.applicationContext
                            val kbView = param.args[0] as? ViewGroup ?: return

                            XposedBridge.log("$TAG [KB] setInputView #$callCount after kbView=${kbView.javaClass.simpleName}")

                            refreshPrefs(ctx)
                            if (!cachedEnabled) return

                            // Skip if toolbar already injected into this view tree
                            if (kbView.findViewWithTag<View>(TOOLBAR_TAG) != null) {
                                XposedBridge.log("$TAG [KB] toolbar already exists (#$callCount), skip")
                                return
                            }

                            activeImsRef = ims
                            if (clipboardDb == null) {
                                clipboardDb = ClipboardDatabase(ctx, cachedMaxEntries)
                                registerClipboardListener(ctx, ims)
                            }

                            // ─────────────────────────────────────────────
                            // WHY WE INJECT HERE, NOT BY WRAPPING InputView:
                            //
                            // InputView measures at 2631px = full screen height.
                            // Gboard independently sets the keyboard window height
                            // to ~893px. If we wrap InputView and put toolbar
                            // BELOW it, the toolbar lands at y=2631 — outside
                            // the 893px window, invisible.
                            //
                            // Instead: inject toolbar INSIDE the keyboard content
                            // LinearLayout (the 893px one holding KeyboardHolder).
                            // That LinearLayout grows → its parent FrameLayout grows
                            // → Gboard's window resizes naturally, just like when
                            // the emoji picker opens.
                            //
                            // Path: InputView
                            //         → FrameLayout(tag=.keyboard-base-area)
                            //           → LinearLayout (this one, currently 893px)
                            //             → KeyboardHolder
                            //             → [our toolbar]   ← injected here
                            // ─────────────────────────────────────────────

                            val contentLayout = findKeyboardContentLayout(kbView)

                            if (contentLayout == null) {
                                XposedBridge.log("$TAG [KB] content layout not found — logging full tree")
                                logViewHierarchy(kbView, 0)
                                return
                            }

                            XposedBridge.log("$TAG [KB] contentLayout found: ${contentLayout.javaClass.simpleName} ${contentLayout.width}x${contentLayout.height} children=${contentLayout.childCount}")

                            val dp = ctx.resources.displayMetrics.density
                            // Start with a thin placeholder — resized after measurement
                            val initialHeight = (44f * dp).toInt()

                            val toolbar = buildToolbar(ctx, ims, kbView)
                            toolbar.tag = TOOLBAR_TAG
                            toolbar.setBackgroundColor(Color.TRANSPARENT)
                            toolbarView = toolbar   // anchor for the shake-undo alert

                            contentLayout.addView(toolbar, LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, initialHeight
                            ))

                            contentLayout.post {
                                val referenceHeight = findEnterKeyHeight(kbView)

                                // Toolbar = 50% of one key row × user multiplier (1.0 = half a key
                                // row ≈ 25dp). Low floor so the -10 step (0.5×) can actually shrink;
                                // the multiplier scales the full button (height here, width at btnSize).
                                val toolbarHeight = when {
                                    referenceHeight > 0 ->
                                        (referenceHeight * 0.5f * cachedMultiplier).toInt()
                                            .coerceAtLeast((14f * dp).toInt())
                                    else -> initialHeight
                                }
                                toolbar.layoutParams = toolbar.layoutParams.also { it.height = toolbarHeight }

                                // Glyph size follows the BUTTON-SIZE multiplier (independent of bar
                                // height): base 16sp × cachedBtnMultiplier, but never taller than the
                                // bar so it can't overflow when height is small.
                                val emojiSp = (16f * cachedBtnMultiplier)
                                    .coerceIn(9f, 40f)
                                    .coerceAtMost(toolbarHeight / dp * 0.95f)
                                for (i in 0 until toolbar.childCount) {
                                    val child = toolbar.getChildAt(i)
                                    if (child is TextView) child.textSize = emojiSp
                                }
                                XposedBridge.log("$TAG [KB] toolbar h=$toolbarHeight ref=$referenceHeight emojiSp=$emojiSp")
                            }

                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG [KB] error: ${t.message}\n${t.stackTraceToString().take(600)}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] hook failed: ${t.message}")
        }
    }

    // Navigate InputView → FrameLayout(.keyboard-base-area) → LinearLayout
    private fun findKeyboardContentLayout(inputView: ViewGroup): LinearLayout? {
        for (i in 0 until inputView.childCount) {
            val child = inputView.getChildAt(i)
            val tag = child.tag?.toString() ?: ""
            if (tag.contains("keyboard-base-area") && child is ViewGroup) {
                for (j in 0 until child.childCount) {
                    val grandchild = child.getChildAt(j)
                    if (grandchild is LinearLayout && grandchild.visibility != View.GONE) {
                        XposedBridge.log("$TAG [KB] found contentLayout at depth 2, child[$i][$j]")
                        return grandchild
                    }
                }
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────
    // 2D trackpad — single DPAD step
    //
    // Primary: InputConnection.sendKeyEvent — instant, and crucially the TARGET
    // app's TextView handles UP/DOWN line navigation natively (it knows its own
    // layout/wrapping, which the IME cannot). Fallback: privileged `input keyevent`
    // via a persistent root shell, for editors that swallow IME DPAD events.
    // ─────────────────────────────────────────────────────
    private fun moveCursorDpad(ims: InputMethodService, keyCode: Int) {
        if (cachedTrackpadRoot) { rootKeyEvent(keyCode); return }
        try {
            val ic = ims.currentInputConnection ?: return
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP,   keyCode, 0))
        } catch (_: Throwable) {}
    }

    // KeyEvent.KEYCODE_* values equal the numeric codes `input keyevent` expects.
    // A persistent su stdin keeps this fast (no per-step process spawn for su itself).
    private fun rootKeyEvent(keyCode: Int) {
        try {
            if (rootStdin == null) {
                val proc = Runtime.getRuntime().exec("su")
                rootStdin = proc.outputStream
            }
            rootStdin?.apply {
                write("input keyevent $keyCode\n".toByteArray())
                flush()
            }
        } catch (_: Throwable) {
            rootStdin = null   // force re-open next time
        }
    }

    // Absolute caret offset, or -1 if the editor won't report it. We do NOT use this to
    // guess boundaries from logical before/after (that's wrong under RTL, where visual
    // DPAD direction ≠ logical direction). Instead we read it before/after a DPAD step
    // and treat "didn't change" as the boundary — which is RTL-agnostic.
    // Tries getExtractedText first, then getSurroundingText (apps support one or the other).
    private fun readCaret(ims: InputMethodService): Int {
        val ic = ims.currentInputConnection ?: return -1
        try {
            val ext = ic.getExtractedText(ExtractedTextRequest().apply { token = 0 }, 0)
            if (ext != null && ext.selectionStart >= 0) return ext.selectionStart
        } catch (_: Throwable) {}
        try {
            val st = ic.getSurroundingText(0, 0, 0)
            if (st != null && st.selectionStart >= 0) return st.offset + st.selectionStart
        } catch (_: Throwable) {}
        return -1
    }

    // Total text length, or -1 if unknown. Lets us pre-block DOWN at end-of-text
    // (the "caret jumps to the Send button" case) without ever sending the escaping key.
    private fun readLen(ims: InputMethodService): Int {
        val ic = ims.currentInputConnection ?: return -1
        try {
            val ext = ic.getExtractedText(ExtractedTextRequest().apply { token = 0 }, 0)
            val t = ext?.text
            if (t != null) return t.length
        } catch (_: Throwable) {}
        return -1
    }

    // First strong directional character around the caret decides L-to-R vs R-to-L,
    // so horizontal drag maps to the correct VISUAL direction in Arabic/Hebrew fields.
    private fun isRtlContext(ims: InputMethodService): Boolean {
        val ic = ims.currentInputConnection ?: return false
        val sample = (ic.getTextBeforeCursor(60, 0)?.toString() ?: "") +
                     (ic.getTextAfterCursor(60, 0)?.toString() ?: "")
        for (ch in sample) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            }
        }
        return false
    }

    // ── trackpad haptics ──
    private fun vib(ctx: Context): Vibrator? {
        vibrator?.let { return it }
        val v = try {
            if (Build.VERSION.SDK_INT >= 31)
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            else
                @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        } catch (_: Throwable) { null }
        vibrator = v
        return v
    }

    // Strength-scaled haptic. At 100% we fire the device-tuned predefined effect (the
    // exact original feel — zero regression). Below 100% we scale amplitude (1..255)
    // where the vibrator supports it, else fall back to duration scaling so older /
    // limited devices still get a usable strength range. 0% = silent.
    private fun hapticEffect(ctx: Context, predefined: Int, baseDurationMs: Long) {
        val s = cachedVibStrength.coerceIn(0, 100)
        if (s == 0) return
        val v = vib(ctx) ?: return
        try {
            when {
                s >= 100 -> v.vibrate(VibrationEffect.createPredefined(predefined))
                v.hasAmplitudeControl() -> {
                    val amp = Math.round(s / 100f * 255f).coerceIn(1, 255)
                    v.vibrate(VibrationEffect.createOneShot(baseDurationMs, amp))
                }
                else -> {
                    val dur = (baseDurationMs * s / 100f).toLong().coerceAtLeast(1L)
                    v.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        } catch (_: Throwable) {}
    }

    // Heavy "pop" when grabbing the trackpad (enter control mode).
    private fun hapticHeavy(ctx: Context) {
        if (!cachedTrackpadHaptics) return   // A2: stick-haptics toggle
        hapticEffect(ctx, VibrationEffect.EFFECT_HEAVY_CLICK, 40L)
    }

    // Firm bump when the caret hits the first/last character (can't move further).
    private fun hapticBoundary(ctx: Context) {
        if (!cachedTrackpadHaptics) return
        hapticEffect(ctx, VibrationEffect.EFFECT_DOUBLE_CLICK, 60L)
    }

    // Subtle tick per cursor step while controlling; throttled so fast flicks don't flood.
    private fun hapticTick(ctx: Context) {
        if (!cachedTrackpadHaptics) return   // A2: stick-haptics toggle
        val now = SystemClock.uptimeMillis()
        if (now - lastTickMs < 14L) return
        lastTickMs = now
        hapticEffect(ctx, VibrationEffect.EFFECT_TICK, 12L)
    }

    // ─────────────────────────────────────────────────────
    // Cursor word movement helper
    // ─────────────────────────────────────────────────────

    private fun moveCursorByWord(ims: InputMethodService, forward: Boolean) {
        try {
            val ic = ims.currentInputConnection ?: return
            if (forward) {
                // Get text after cursor to find next word boundary
                val after = ic.getTextAfterCursor(500, 0)?.toString() ?: return
                // Skip whitespace first, then skip non-whitespace (the word)
                var i = 0
                while (i < after.length && after[i].isWhitespace()) i++
                while (i < after.length && !after[i].isWhitespace()) i++
                if (i > 0) {
                    val req = ExtractedTextRequest().apply { token = 0 }
                    val cursorPos = ic.getExtractedText(req, 0)?.selectionEnd ?: return
                    ic.setSelection(cursorPos + i, cursorPos + i)
                }
            } else {
                // Get text before cursor to find previous word boundary
                val before = ic.getTextBeforeCursor(500, 0)?.toString() ?: return
                var i = before.length
                // Skip whitespace going backward, then skip non-whitespace (the word)
                while (i > 0 && before[i - 1].isWhitespace()) i--
                while (i > 0 && !before[i - 1].isWhitespace()) i--
                val req = ExtractedTextRequest().apply { token = 0 }
                val cursorPos = ic.getExtractedText(req, 0)?.selectionStart ?: return
                val newPos = cursorPos - (before.length - i)
                ic.setSelection(newPos, newPos)
            }
        } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────
    // Toolbar construction
    // ─────────────────────────────────────────────────────

    private fun buildToolbar(ctx: Context, ims: InputMethodService, keyboardView: View): LinearLayout {
        val toolbar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.CENTER
        }

        val dp = ctx.resources.displayMetrics.density
        // Button width scales with its OWN multiplier (independent of toolbar height).
        // Base 44dp; multiplier = 2^(step/10) from the "Button size" stepper.
        val btnSize = (44f * dp * cachedBtnMultiplier).toInt().coerceAtLeast((16f * dp).toInt())

        // Emoji button — fixed 48dp square, centered, no background
        fun makeBtn(emoji: String): TextView = TextView(ctx).apply {
            text = emoji
            textSize = 16f   // resized in post{} to match key height
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
        }

        fun add(view: View) {
            toolbar.addView(view, LinearLayout.LayoutParams(btnSize, LinearLayout.LayoutParams.MATCH_PARENT))
        }

        // Button 1 — Clipboard 📋
        if (cachedBtnClipboard) {
            val btn = makeBtn("📋")
            btn.setOnClickListener {
                btn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showClipboardPopup(ctx, ims, toolbar)
            }
            add(btn)
        }

        // Button 2 — Select ✂️  (tap = last word, long-press = all)
        if (cachedBtnSelectAll) {
            val btn = makeBtn("✂️")
            val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
            val selHandler = Handler(Looper.getMainLooper())
            var longPressFired = false

            btn.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressFired = false
                        selHandler.postDelayed({
                            longPressFired = true
                            btn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            try {
                                val ic = ims.currentInputConnection ?: return@postDelayed
                                // Long-press = select ALL, then show our own floating
                                // Cut/Copy/Paste/Share bar (rendered by the keyboard, so it
                                // works in EVERY app with no LSPosed scope — see showSelectionMenu).
                                ic.performContextMenuAction(android.R.id.selectAll)
                                showSelectionMenu(ctx, ims, toolbar)
                            } catch (_: Throwable) {}
                        }, longPressTimeout)
                    }
                    MotionEvent.ACTION_UP -> {
                        selHandler.removeCallbacksAndMessages(null)
                        if (!longPressFired) {
                            btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            try {
                                val ic = ims.currentInputConnection ?: return@setOnTouchListener true
                                // If text is already selected (e.g. the user just Copied and
                                // tapped scissor again), don't re-select — just reopen the bar.
                                if (!ic.getSelectedText(0).isNullOrEmpty()) {
                                    showSelectionMenu(ctx, ims, toolbar)
                                    return@setOnTouchListener true
                                }
                                val before = ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
                                val trimmed = before.trimEnd()
                                val lastSpace = trimmed.lastIndexOf(' ')
                                    .let { if (it == -1) trimmed.lastIndexOf('\n') else it }
                                val wordStart = if (lastSpace == -1) 0 else lastSpace + 1
                                val wordLen = trimmed.length - wordStart
                                if (wordLen > 0) {
                                    val req = ExtractedTextRequest().apply { token = 0 }
                                    val extracted = ic.getExtractedText(req, 0)
                                    val cursorPos = extracted?.selectionStart ?: before.length
                                    val selStart = cursorPos - (before.length - wordStart)
                                    val selEnd = cursorPos - (before.length - trimmed.length)
                                    if (selEnd > selStart) {
                                        // Select the last word, then show our own floating
                                        // Cut/Copy/Paste/Share bar (keyboard-rendered → works in
                                        // every app with no scope). See showSelectionMenu.
                                        ic.setSelection(selStart, selEnd)
                                        showSelectionMenu(ctx, ims, toolbar)
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> selHandler.removeCallbacksAndMessages(null)
                }
                true
            }
            add(btn)
        }

        // Button 3 — Cursor left ⬅️ and right ➡️
        // tap = move by word, long-press = go to start/end
        if (cachedBtnCursor) {
            val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

            fun makeCursorBtn(emoji: String, forward: Boolean): TextView {
                val curBtn = makeBtn(emoji)
                val curHandler = Handler(Looper.getMainLooper())
                var longFired = false
                curBtn.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            longFired = false
                            curHandler.postDelayed({
                                longFired = true
                                curBtn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                try {
                                    val ic = ims.currentInputConnection ?: return@postDelayed
                                    if (forward) {
                                        val req = ExtractedTextRequest().apply { token = 0 }
                                        val len = ic.getExtractedText(req, 0)?.text?.length ?: 0
                                        ic.setSelection(len, len)
                                    } else {
                                        ic.setSelection(0, 0)
                                    }
                                } catch (_: Throwable) {}
                            }, longPressTimeout)
                        }
                        MotionEvent.ACTION_UP -> {
                            curHandler.removeCallbacksAndMessages(null)
                            if (!longFired) {
                                curBtn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                moveCursorByWord(ims, forward)
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> curHandler.removeCallbacksAndMessages(null)
                    }
                    true
                }
                return curBtn
            }

            add(makeCursorBtn("⬅️", forward = false))
            add(makeCursorBtn("➡️", forward = true))
        }

        // Button 3b — 2D Trackpad 🕹️ (free cursor control)
        // Press-and-hold + drag: X drag → LEFT/RIGHT, Y drag → UP/DOWN (true multi-line,
        // navigated natively by the target editor). DPAD events go through the
        // InputConnection by default (instant); optional root fallback for stubborn apps.
        if (cachedBtnTrackpad) {
            val btn = makeBtn("🕹️")
            var active = false
            var lastX = 0f; var lastY = 0f
            var accX = 0f;  var accY = 0f
            var steps = 0
            // Per-gesture state, seeded ONCE on ACTION_DOWN. During a continuous drag we
            // track the caret INTERNALLY (predIndex) and never re-read the editor per step —
            // a per-step read lags behind a fast flick, so recomputing from it snapped the
            // cursor backward (the rubber-band). The only reads are grab, release, and one
            // resync after each vertical line change (line layout is owned by the app).
            var predIndex = -1        // internally-tracked caret offset (-1 = unknown at grab)
            var textLen = -1          // total length (-1 = unknown → no right-edge detection)
            var rtl = false           // RTL field → DPAD_RIGHT decreases the logical index
            var canTrack = false      // true once we have a valid caret to count from
            val blocked = HashSet<Int>()   // directions parked at a boundary this gesture
            btn.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        active = true; accX = 0f; accY = 0f; steps = 0
                        lastX = e.rawX; lastY = e.rawY
                        blocked.clear()
                        predIndex = readCaret(ims); textLen = readLen(ims); rtl = isRtlContext(ims)
                        canTrack = predIndex >= 0
                        hapticHeavy(ctx)   // heavy "pop" on grab
                        // Keep the gesture ours — don't let the keyboard layout steal the drag.
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        XposedBridge.log("$TAG [KB] trackpad DOWN caret=$predIndex len=$textLen rtl=$rtl track=$canTrack root=$cachedTrackpadRoot")
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!active) return@setOnTouchListener true
                        accX += e.rawX - lastX
                        accY += e.rawY - lastY
                        lastX = e.rawX; lastY = e.rawY
                        val sens = cachedTrackpadSensitivity.coerceIn(0.25f, 6f)
                        val stepX = TRACKPAD_STEP_X / sens
                        val stepY = TRACKPAD_STEP_Y / sens

                        // Horizontal step — PURE internal tracking, zero round-trips.
                        // We know the index moves ±1 per DPAD (RTL flips the sign), and the
                        // left edge is always 0, so we can refuse the escaping key from
                        // predIndex alone — no read to lag behind the finger, no rubber-band.
                        fun emitHoriz(keyCode: Int): Boolean {
                            if (keyCode in blocked) return false
                            if (canTrack) {
                                val atLeftEdge  = predIndex <= 0
                                val atRightEdge = textLen >= 0 && predIndex >= textLen
                                val escapes = when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (rtl) atLeftEdge else atRightEdge
                                    KeyEvent.KEYCODE_DPAD_LEFT  -> if (rtl) atRightEdge else atLeftEdge
                                    else -> false
                                }
                                if (escapes) {
                                    blocked.add(keyCode); hapticBoundary(ctx)
                                    XposedBridge.log("$TAG [KB] trackpad PRE-boundary key=$keyCode pred=$predIndex len=$textLen")
                                    return false
                                }
                            }
                            moveCursorDpad(ims, keyCode); steps++
                            if (canTrack) {
                                predIndex += when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (rtl) -1 else 1
                                    else                        -> if (rtl) 1 else -1
                                }
                                if (predIndex < 0) predIndex = 0
                                if (textLen >= 0 && predIndex > textLen) predIndex = textLen
                            }
                            // Moved away from one edge → re-arm the opposite direction.
                            blocked.remove(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                                KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                            hapticTick(ctx)
                            XposedBridge.log("$TAG [KB] trackpad step key=$keyCode pred=$predIndex read=false")
                            return true
                        }

                        // Vertical step — the target editor owns line wrapping, so after a
                        // DPAD_UP/DOWN the absolute horizontal index is unknown. Emit, then do
                        // exactly ONE resync read to restore predIndex (per line change, not
                        // per step). Block only at the true text start/end when known.
                        fun emitVert(keyCode: Int): Boolean {
                            if (keyCode in blocked) return false
                            if (canTrack) {
                                val escapes = when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_UP   -> predIndex <= 0
                                    KeyEvent.KEYCODE_DPAD_DOWN -> textLen >= 0 && predIndex >= textLen
                                    else -> false
                                }
                                if (escapes) {
                                    blocked.add(keyCode); hapticBoundary(ctx)
                                    XposedBridge.log("$TAG [KB] trackpad PRE-boundary key=$keyCode pred=$predIndex len=$textLen")
                                    return false
                                }
                            }
                            moveCursorDpad(ims, keyCode); steps++
                            hapticTick(ctx)
                            val resync = readCaret(ims)
                            if (resync >= 0) {
                                predIndex = resync; canTrack = true
                                blocked.remove(KeyEvent.KEYCODE_DPAD_LEFT)
                                blocked.remove(KeyEvent.KEYCODE_DPAD_RIGHT)
                            } else {
                                canTrack = false   // editor stopped reporting → suspend H math
                            }
                            XposedBridge.log("$TAG [KB] trackpad step key=$keyCode pred=$predIndex read=true")
                            return true
                        }

                        while (accX >= stepX)  { if (emitHoriz(KeyEvent.KEYCODE_DPAD_RIGHT)) accX -= stepX else { accX = 0f; break } }
                        while (accX <= -stepX) { if (emitHoriz(KeyEvent.KEYCODE_DPAD_LEFT))  accX += stepX else { accX = 0f; break } }
                        while (accY >= stepY)  { if (emitVert(KeyEvent.KEYCODE_DPAD_DOWN))   accY -= stepY else { accY = 0f; break } }
                        while (accY <= -stepY) { if (emitVert(KeyEvent.KEYCODE_DPAD_UP))     accY += stepY else { accY = 0f; break } }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        active = false
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        // One resync on release to absorb any drift from internal counting.
                        val finalCaret = readCaret(ims)
                        XposedBridge.log("$TAG [KB] trackpad UP steps=$steps pred=$predIndex actual=$finalCaret root=$cachedTrackpadRoot")
                        true
                    }
                    else -> false
                }
            }
            add(btn)
        }

        // Button 3c — Undo ↩️ (restore deleted text). Tap = undo one deletion.
        if (cachedUndoEnabled && cachedUndoButton) {
            val btn = makeBtn("↩️")
            btn.setOnClickListener {
                btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                undo(ims)
            }
            add(btn)
        }

        // Button 4 — Shortcut ⭐ (tap = shortcut1, long-press = shortcut2)
        if (cachedBtnShortcut) {
            val btn = makeBtn("⭐")
            btn.setOnClickListener {
                btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                try {
                    lastCacheTime = 0L          // force read latest prefs
                    refreshPrefs(ctx)
                    if (cachedShortcut1.isNotEmpty()) ims.currentInputConnection?.commitText(cachedShortcut1, 1)
                } catch (_: Throwable) {}
            }
            btn.setOnLongClickListener {
                btn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                try {
                    lastCacheTime = 0L
                    refreshPrefs(ctx)
                    if (cachedShortcut2.isNotEmpty()) ims.currentInputConnection?.commitText(cachedShortcut2, 1)
                } catch (_: Throwable) {}
                true
            }
            add(btn)
        }

        // Button 5 — Paste 📥 (last Android clipboard item)
        if (cachedBtnPaste) {
            val btn = makeBtn("📥")
            btn.setOnClickListener {
                btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                try {
                    val clipMgr = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val text = clipMgr?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    if (!text.isNullOrEmpty()) ims.currentInputConnection?.commitText(text, 1)
                } catch (_: Throwable) {}
            }
            add(btn)
        }

        return toolbar
    }

    // ─────────────────────────────────────────────────────
    // Shared OLED-dark design tokens + drawable helpers
    // Used by both the selection bar and the clipboard vault so the
    // two surfaces share one consistent visual language.
    // ─────────────────────────────────────────────────────
    private object UI {
        val BG        = Color.parseColor("#0F0F14")  // deepest backdrop (OLED black-ish)
        val SURFACE   = Color.parseColor("#191921")  // cards / containers
        val ELEVATED  = Color.parseColor("#23232E")  // popovers above surface
        val ACCENT    = Color.parseColor("#7C8AFF")  // primary accent (indigo)
        val ACCENT_BG = Color.parseColor("#332E3BFF") // accent tint (selected pill)
        val TEXT      = Color.parseColor("#ECECF1")  // primary text (~7:1)
        val TEXT_DIM  = Color.parseColor("#9A9AB0")  // secondary text
        val DIVIDER   = Color.parseColor("#2A2A36")  // hairlines / strokes
        val DANGER    = Color.parseColor("#FF6B6B")  // destructive
        val RIPPLE    = Color.parseColor("#33FFFFFF") // press ripple
    }

    // Solid rounded rectangle.
    private fun roundRect(color: Int, radius: Float, strokeColor: Int? = null, strokeWidthPx: Int = 0) =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            if (strokeColor != null && strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
        }

    // Rounded only on the top edge (for bottom-anchored sheets).
    private fun roundTop(color: Int, radius: Float) =
        GradientDrawable().apply {
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            setColor(color)
        }

    // Ripple over an optional rounded base, clipped to the same radius.
    private fun ripple(baseColor: Int?, radius: Float): RippleDrawable {
        val content = baseColor?.let { roundRect(it, radius) }
        val mask = roundRect(Color.WHITE, radius)
        return RippleDrawable(ColorStateList.valueOf(UI.RIPPLE), content, mask)
    }

    // ─────────────────────────────────────────────────────
    // Selection action bar (our own Cut/Copy/Paste/Share)
    // Rendered by the keyboard itself and driven entirely through
    // the InputConnection + clipboard, so it works in EVERY app
    // with NO LSPosed scope, no root, no accessibility — incl. apps
    // installed in the future and WebView editors. This replaces the
    // system floating toolbar (an ActionMode owned by the target
    // app's editor, which a keyboard cannot summon cross-process).
    // ─────────────────────────────────────────────────────
    private fun showSelectionMenu(ctx: Context, ims: InputMethodService, anchor: View) {
        val dp = ctx.resources.displayMetrics.density
        val radius = 18f * dp
        // OLED-dark floating pill: rounded #191921 surface with a hairline border and
        // soft elevation. Each action is a vertical icon+label chip (44dp+ target),
        // matching the clipboard vault's visual language.
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(UI.SURFACE, radius, UI.DIVIDER, (1f * dp).toInt())
            clipToOutline = true
            setPadding((4f * dp).toInt(), (4f * dp).toInt(), (6f * dp).toInt(), (4f * dp).toInt())
        }
        // Not focusable → the target editor keeps focus + its selection while our bar shows.
        // isClippingEnabled = false lets the window be positioned anywhere on screen,
        // outside the keyboard/IME bounds (we drag it via the grip handle below).
        val popup = PopupWindow(bar,
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 12f * dp
            isOutsideTouchable = true
            isClippingEnabled = false
        }

        fun clip(): ClipboardManager? =
            ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        fun selectedText(): CharSequence? =
            ims.currentInputConnection?.getSelectedText(0)

        // ── Drag handle ── grip the bar and move it anywhere on screen.
        // Gravity is BOTTOM|CENTER_HORIZONTAL, so x offset = rightward shift and
        // y offset = upward shift from the bottom edge.
        var curX = selMenuOffX ?: 0
        var curY = selMenuOffY ?: (anchor.height + (8f * dp).toInt())
        val handle = TextView(ctx).apply {
            text = "⋮⋮"
            textSize = 16f
            setTextColor(UI.TEXT_DIM)
            gravity = Gravity.CENTER
            minWidth = (28f * dp).toInt()
            minHeight = (48f * dp).toInt()
            setPadding((4f * dp).toInt(), 0, (4f * dp).toInt(), 0)
        }
        var downRawX = 0f; var downRawY = 0f; var startX = 0; var startY = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY; startX = curX; startY = curY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    curX = startX + (e.rawX - downRawX).toInt()
                    curY = startY - (e.rawY - downRawY).toInt()
                    try { popup.update(curX, curY, -1, -1) } catch (_: Throwable) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    selMenuOffX = curX; selMenuOffY = curY; true
                }
                else -> false
            }
        }
        bar.addView(handle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))

        fun item(icon: String, label: String, onClick: () -> Unit) {
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumWidth = (54f * dp).toInt()
                minimumHeight = (48f * dp).toInt()             // CRITICAL: ≥44dp touch target
                setPadding((8f * dp).toInt(), (6f * dp).toInt(), (8f * dp).toInt(), (6f * dp).toInt())
                background = ripple(null, 12f * dp)
            }
            chip.addView(TextView(ctx).apply {
                text = icon
                textSize = 17f
                gravity = Gravity.CENTER
            })
            chip.addView(TextView(ctx).apply {
                text = label
                setTextColor(UI.TEXT)
                textSize = 11f
                gravity = Gravity.CENTER
                isSingleLine = true
                setPadding(0, (2f * dp).toInt(), 0, 0)
            })
            chip.setOnClickListener {
                chip.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                try { onClick() } catch (_: Throwable) {}
            }
            bar.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        item("✂️", "Cut") {
            val ic = ims.currentInputConnection
            val s = selectedText()
            if (ic != null && !s.isNullOrEmpty()) {
                clip()?.setPrimaryClip(ClipData.newPlainText("", s))
                ic.commitText("", 1)   // replace selection with nothing = delete
            }
            popup.dismiss()
        }
        item("📋", "Copy") {
            val s = selectedText()
            if (!s.isNullOrEmpty()) clip()?.setPrimaryClip(ClipData.newPlainText("", s))
            popup.dismiss()
        }
        item("📥", "Paste") {
            val ic = ims.currentInputConnection
            val t = clip()?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)
            if (ic != null && !t.isNullOrEmpty()) ic.commitText(t, 1)
            popup.dismiss()
        }
        item("🔳", "All") {
            ims.currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            // keep the bar open so the user can immediately Cut/Copy the whole field
        }

        anchor.post {
            try { popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, curX, curY) }
            catch (_: Throwable) {}
        }
    }

    private fun showClipboardPopup(ctx: Context, ims: InputMethodService, anchor: View) {
        val db = clipboardDb ?: return
        val dp = ctx.resources.displayMetrics.density
        val popupWidth = anchor.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels

        val cornerR = 22f * dp
        val outerContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundTop(UI.BG, cornerR)
            clipToOutline = true
        }

        // ── Header: title + close ──
        var showFavoritesOnly = false
        // Lazy paging: show 50 first (fast open), tap the footer to load 50 more.
        val pageSize = 50
        var displayLimit = pageSize
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16f * dp).toInt(), (12f * dp).toInt(), (10f * dp).toInt(), (8f * dp).toInt())
        }
        val title = TextView(ctx).apply {
            text = "📋  Clipboard"
            textSize = 15f
            setTextColor(UI.TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val closeBtn = TextView(ctx).apply {
            text = "✕"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(UI.TEXT_DIM)
            minWidth = (36f * dp).toInt()
            minHeight = (36f * dp).toInt()
            background = ripple(null, 18f * dp)
        }
        headerRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(closeBtn)
        outerContainer.addView(headerRow)

        // ── Segmented tabs: All | ❤️ Favorites ──
        val tabStrip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRect(UI.SURFACE, 12f * dp)
            setPadding((3f * dp).toInt(), (3f * dp).toInt(), (3f * dp).toInt(), (3f * dp).toInt())
        }
        fun tab(label: String) = TextView(ctx).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, (8f * dp).toInt(), 0, (8f * dp).toInt())
        }
        val tabAll = tab("All")
        val tabFav = tab("❤️ Favorites")
        tabStrip.addView(tabAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabStrip.addView(tabFav, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        outerContainer.addView(tabStrip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins((14f * dp).toInt(), 0, (14f * dp).toInt(), (8f * dp).toInt()) })

        // ── Toolbar: sort chip + delete all chip ──
        val sortLabels = listOf("Newest ↓", "Oldest ↑", "📌 First", "❤️ First")
        val sortModes = listOf(
            ClipboardDatabase.SortMode.NEWEST,
            ClipboardDatabase.SortMode.OLDEST,
            ClipboardDatabase.SortMode.PINNED_FIRST,
            ClipboardDatabase.SortMode.FAVORITES_FIRST
        )
        val sortRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14f * dp).toInt(), 0, (14f * dp).toInt(), (8f * dp).toInt())
        }
        val chipR = 10f * dp
        val sortBtn = TextView(ctx).apply {
            text = "⇅  " + sortLabels[sortModes.indexOf(clipSortMode).coerceAtLeast(0)]
            setTextColor(UI.TEXT)
            textSize = 11f
            setPadding((12f * dp).toInt(), (7f * dp).toInt(), (12f * dp).toInt(), (7f * dp).toInt())
            background = ripple(UI.SURFACE, chipR)
        }
        val deleteAllBtn = TextView(ctx).apply {
            text = "🗑  Delete all"
            setTextColor(UI.DANGER)
            textSize = 11f
            setPadding((12f * dp).toInt(), (7f * dp).toInt(), (12f * dp).toInt(), (7f * dp).toInt())
            background = ripple(Color.parseColor("#1FFF6B6B"), chipR)
        }
        sortRow.addView(sortBtn)
        sortRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        sortRow.addView(deleteAllBtn)
        outerContainer.addView(sortRow)

        // ── Scrollable list ──
        val scrollView = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            setPadding((10f * dp).toInt(), 0, (10f * dp).toInt(), (8f * dp).toInt())
            clipToPadding = false
        }
        val listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(listContainer)
        outerContainer.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val popup = PopupWindow(outerContainer, popupWidth, (360f * dp).toInt(), true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 16f * dp
        }

        fun updateTabs() {
            val r = 9f * dp
            tabAll.background = if (!showFavoritesOnly) roundRect(UI.ACCENT_BG, r) else null
            tabAll.setTextColor(if (!showFavoritesOnly) UI.ACCENT else UI.TEXT_DIM)
            tabFav.background = if (showFavoritesOnly) roundRect(UI.ACCENT_BG, r) else null
            tabFav.setTextColor(if (showFavoritesOnly) UI.ACCENT else UI.TEXT_DIM)
        }

        fun reloadList() {
            dbExecutor.submit {
                val entries = db.getAll(clipSortMode, showFavoritesOnly)
                listContainer.post {
                    listContainer.removeAllViews()
                    if (entries.isEmpty()) {
                        listContainer.addView(TextView(ctx).apply {
                            text = if (showFavoritesOnly) "No favorites yet — long-press an entry and tap ★" else "No clipboard history yet"
                            setTextColor(UI.TEXT_DIM)
                            textSize = 14f
                            gravity = Gravity.CENTER
                            setPadding((16f * dp).toInt(), (40f * dp).toInt(), (16f * dp).toInt(), (40f * dp).toInt())
                        })
                    } else {
                        val shown = displayLimit.coerceAtMost(entries.size)
                        entries.take(shown).forEach { entry ->
                            buildClipboardRow(ctx, dp, entry, db, ims, popup, listContainer) { reloadList() }
                        }
                        val remaining = entries.size - shown
                        if (remaining > 0) {
                            val more = remaining.coerceAtMost(pageSize)
                            listContainer.addView(TextView(ctx).apply {
                                text = "▾  Load $more more  ($remaining left)"
                                setTextColor(UI.ACCENT)
                                textSize = 13f
                                gravity = Gravity.CENTER
                                background = ripple(UI.SURFACE, 12f * dp)
                                setPadding(0, (12f * dp).toInt(), 0, (12f * dp).toInt())
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { setMargins(0, (6f * dp).toInt(), 0, (2f * dp).toInt()) }
                                setOnClickListener {
                                    displayLimit += pageSize
                                    reloadList()
                                }
                            })
                        }
                    }
                }
            }
        }

        closeBtn.setOnClickListener { popup.dismiss() }
        tabAll.setOnClickListener {
            if (showFavoritesOnly) { showFavoritesOnly = false; displayLimit = pageSize; updateTabs(); reloadList() }
        }
        tabFav.setOnClickListener {
            if (!showFavoritesOnly) { showFavoritesOnly = true; displayLimit = pageSize; updateTabs(); reloadList() }
        }
        sortBtn.setOnClickListener {
            val idx = (sortModes.indexOf(clipSortMode) + 1) % sortModes.size
            clipSortMode = sortModes[idx]
            sortBtn.text = "⇅  " + sortLabels[idx]
            displayLimit = pageSize
            reloadList()
        }
        deleteAllBtn.setOnClickListener {
            try {
                showDeleteAllConfirm(ctx, dp, deleteAllBtn) {
                    dbExecutor.submit { db.deleteAll(); deleteAllBtn.post { reloadList() } }
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [KB] deleteAll confirm failed: ${t.message}")
            }
        }

        updateTabs()
        reloadList()
        anchor.post { popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.START, 0, anchor.height) }
    }

    private fun buildClipboardRow(
        ctx: Context, dp: Float,
        entry: ClipboardDatabase.Entry,
        db: ClipboardDatabase,
        ims: InputMethodService,
        popup: PopupWindow,
        container: LinearLayout,
        reload: () -> Unit
    ) {
        val cardR = 14f * dp
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(UI.SURFACE, cardR)
            setPadding((14f * dp).toInt(), (11f * dp).toInt(), (6f * dp).toInt(), (11f * dp).toInt())
        }

        val badge = TextView(ctx).apply {
            text = when {
                entry.isPinned -> "📌"
                entry.isFavorite -> "★"
                else -> ""
            }
            setTextColor(if (entry.isFavorite && !entry.isPinned) UI.ACCENT else UI.TEXT)
            textSize = 13f
            setPadding(0, 0, if (entry.isPinned || entry.isFavorite) (6f * dp).toInt() else 0, 0)
        }

        val textView = TextView(ctx).apply {
            text = entry.text
            setTextColor(UI.TEXT)
            textSize = 14f
            // A1: full text by default; with the toggle off, show a 3-line preview.
            if (cachedClipFullText) {
                maxLines = Int.MAX_VALUE
                ellipsize = null
            } else {
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val optBtn = TextView(ctx).apply {
            text = "⋮"
            textSize = 18f
            setTextColor(UI.TEXT_DIM)
            gravity = Gravity.CENTER
            minWidth = (34f * dp).toInt()
            minHeight = (34f * dp).toInt()
            background = ripple(null, 17f * dp)
        }

        row.addView(badge)
        row.addView(textView)
        row.addView(optBtn)

        row.setOnClickListener {
            try { ims.currentInputConnection?.commitText(entry.text, 1); popup.dismiss() } catch (_: Throwable) {}
        }

        optBtn.setOnClickListener {
            showEntryOptions(ctx, dp, entry, db, optBtn, reload)
        }

        container.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, (3f * dp).toInt(), 0, (3f * dp).toInt()) })
    }

    // PopupWindow-based confirm (AlertDialog needs Activity context — IME has none).
    // Mirrors showEntryOptions: WRAP_CONTENT height + showAsDropDown to stay anchored
    // inside the IME window, avoiding BadTokenException from showAtLocation.
    private fun showDeleteAllConfirm(ctx: Context, dp: Float, anchor: View, onConfirm: () -> Unit) {
        val cardR = 18f * dp
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(UI.ELEVATED, cardR, UI.DIVIDER, (1f * dp).toInt())
            clipToOutline = true
            setPadding((16f * dp).toInt(), (14f * dp).toInt(), (16f * dp).toInt(), (10f * dp).toInt())
        }
        val msg = TextView(ctx).apply {
            text = "Delete all clipboard items?\nThis cannot be undone."
            setTextColor(UI.TEXT)
            textSize = 13f
            setPadding(0, 0, 0, (12f * dp).toInt())
        }
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val popup = PopupWindow(
            container,
            (250f * dp).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 20f * dp
        }
        val cancelBtn = TextView(ctx).apply {
            text = "Cancel"
            setTextColor(UI.TEXT_DIM)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding((16f * dp).toInt(), (9f * dp).toInt(), (16f * dp).toInt(), (9f * dp).toInt())
            background = ripple(null, 10f * dp)
            setOnClickListener { popup.dismiss() }
        }
        val confirmBtn = TextView(ctx).apply {
            text = "Delete all"
            setTextColor(UI.DANGER)
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding((16f * dp).toInt(), (9f * dp).toInt(), (16f * dp).toInt(), (9f * dp).toInt())
            background = ripple(Color.parseColor("#1FFF6B6B"), 10f * dp)
            setOnClickListener { popup.dismiss(); onConfirm() }
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(View(ctx), LinearLayout.LayoutParams((6f * dp).toInt(), 1))
        btnRow.addView(confirmBtn)
        container.addView(msg)
        container.addView(btnRow)
        try { popup.showAsDropDown(anchor) } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] showDeleteAllConfirm.showAsDropDown failed: ${t.message}")
        }
    }

    // Uses PopupWindow instead of AlertDialog — AlertDialog requires Activity context, IME has none
    private fun showEntryOptions(
        ctx: Context, dp: Float,
        entry: ClipboardDatabase.Entry,
        db: ClipboardDatabase,
        anchor: View,
        reload: () -> Unit
    ) {
        val cardR = 16f * dp
        val optContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(UI.ELEVATED, cardR, UI.DIVIDER, (1f * dp).toInt())
            clipToOutline = true
            setPadding((6f * dp).toInt(), (6f * dp).toInt(), (6f * dp).toInt(), (6f * dp).toInt())
        }

        val optPopup = PopupWindow(
            optContainer,
            (176f * dp).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 20f * dp
        }

        fun optRow(label: String, danger: Boolean = false, action: () -> Unit): TextView = TextView(ctx).apply {
            text = label
            setTextColor(if (danger) UI.DANGER else UI.TEXT)
            textSize = 14f
            background = ripple(null, 10f * dp)
            setPadding((14f * dp).toInt(), (11f * dp).toInt(), (14f * dp).toInt(), (11f * dp).toInt())
            setOnClickListener { optPopup.dismiss(); action() }
        }

        optContainer.addView(optRow(if (entry.isPinned) "📌  Unpin" else "📌  Pin") {
            dbExecutor.submit { db.togglePin(entry.id); anchor.post { reload() } }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        optContainer.addView(optRow(if (entry.isFavorite) "★  Unfavorite" else "★  Favorite") {
            dbExecutor.submit { db.toggleFavorite(entry.id); anchor.post { reload() } }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        optContainer.addView(optRow("🗑  Delete", danger = true) {
            dbExecutor.submit { db.delete(entry.id); anchor.post { reload() } }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        optPopup.showAsDropDown(anchor)
    }

    // ─────────────────────────────────────────────────────
    // Clipboard capture
    // ─────────────────────────────────────────────────────

    private fun registerClipboardListener(ctx: Context, ims: InputMethodService) {
        try {
            val clipMgr = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            clipMgr.addPrimaryClipChangedListener {
                try {
                    val text = clipMgr.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    if (!text.isNullOrEmpty()) {
                        dbExecutor.submit {
                            clipboardDb?.insert(text)
                        }
                    }
                } catch (_: Throwable) {}
            }
            XposedBridge.log("$TAG [KB] clipboard listener registered")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] clipboard listener registration failed: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────

    private fun logViewHierarchy(view: View, depth: Int) {
        val indent = "  ".repeat(depth)
        XposedBridge.log("$TAG [KB] $indent${view.javaClass.name} ${view.measuredWidth}x${view.measuredHeight} id=${view.id}")
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                logViewHierarchy(view.getChildAt(i), depth + 1)
            }
        }
    }

    // Post-measurement tree log — shows actual pixel sizes, visibility, layoutParams
    private fun logFullTree(view: View, indent: String = "") {
        val vis = when (view.visibility) {
            View.VISIBLE -> "VIS"; View.INVISIBLE -> "INVIS"; View.GONE -> "GONE"; else -> "?"
        }
        fun lpStr(n: Int) = when (n) { -1 -> "MATCH"; -2 -> "WRAP"; else -> "$n" }
        val lp = view.layoutParams
        val lpDesc = if (lp != null) "${lpStr(lp.width)}x${lpStr(lp.height)}" else "null"
        XposedBridge.log("$TAG [TREE] $indent${view.javaClass.simpleName} " +
            "${view.width}x${view.height} lp=$lpDesc vis=$vis alpha=${view.alpha} " +
            "tag=${view.tag} elev=${view.elevation.toInt()}")
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) logFullTree(view.getChildAt(i), "$indent  ")
        }
    }

    private fun findEnterKeyHeight(root: View): Int {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                val cd = child.contentDescription?.toString() ?: ""
                if (cd.contains("return", ignoreCase = true) ||
                    cd.contains("enter", ignoreCase = true) ||
                    cd.contains("done", ignoreCase = true)
                ) {
                    return child.measuredHeight
                }
                val result = findEnterKeyHeight(child)
                if (result > 0) return result
            }
        }
        return 0
    }
}
