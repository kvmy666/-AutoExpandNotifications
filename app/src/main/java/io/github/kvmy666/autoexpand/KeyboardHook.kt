package io.github.kvmy666.autoexpand

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.inputmethodservice.InputMethodService
import android.net.Uri
import java.text.BreakIterator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.KeyEvent
import android.os.SystemClock
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.TextAttribute
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
    // Select-mode toggle button (🖍️) — rides alongside the stick, never alone.
    @Volatile private var cachedBtnSelectMode = true
    // Trackpad stick haptics (grab pop + steering ticks). Independent of the stick itself.
    @Volatile private var cachedTrackpadHaptics = true
    // CopyVault row rendering: full text (default) vs first-line-only truncation.
    @Volatile private var cachedClipFullText = true
    // Save copied images. Opt-in, default OFF for the first release: with it off, not one
    // line of the capture path runs and the vault behaves exactly as 3.3.0 did.
    @Volatile private var cachedClipImages = false
    @Volatile private var cachedImgMaxEntries = ClipboardImagePolicy.DEFAULT_MAX_ENTRIES
    @Volatile private var cachedImgMaxBytes = ClipboardImagePolicy.DEFAULT_MAX_BYTES
    // Auto-capture system screenshots. Independent of [cachedClipImages]: a user may want
    // screenshots in the vault without every copied image, or the reverse. Default OFF.
    @Volatile private var cachedShotCapture = false
    // Additionally push each captured screenshot onto the real system clipboard. Default
    // OFF, and deliberately so: it overwrites whatever the user had copied.
    @Volatile private var cachedShotToClipboard = false
    // Per-event chatter on the AutoExpandShot tag. Lifecycle lines are logged regardless.
    @Volatile private var cachedShotVerbose = false
    // Sensitivity: >1 = faster (smaller effective step). Tunable via pref "trackpad_sensitivity".
    @Volatile private var cachedTrackpadSensitivity = 1.0f
    // When true, deliver DPAD via privileged `input keyevent` (root) instead of the
    // InputConnection — fallback for apps that swallow IME DPAD events.
    @Volatile private var cachedTrackpadRoot = false

    // The floating Cut/Copy/Paste bar, while one is on screen. Held so a second request
    // replaces it instead of stacking on top of it.
    @Volatile private var selectionMenu: PopupWindow? = null

    // Set by the onStartInputView hook when input restarts on a target with no text
    // (inputType == 0) — the signature of DPAD focus-search handing focus to a
    // neighbouring, non-editable view. A trackpad gesture in flight uses this as a
    // cheap "the field may be gone" hint and re-verifies with one caret read.
    @Volatile private var inputRestartedNonEditable = false

    // ── Select mode (🖍️) ──
    // A live toggle, not a pref: while it is on, the stick drags a SELECTION instead of
    // the bare caret. Object-level so it survives toolbar rebuilds (Gboard recreates the
    // input view on every app switch) — the button paints itself from this on build.
    @Volatile private var selectModeOn = false
    // The fixed end of the selection. Kept between gestures so a second grab keeps
    // extending from where the first one started instead of re-anchoring. -1 = none.
    @Volatile private var selAnchor = -1

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

    // ── Clipboard images (3.4.0) ──
    // Decode/compress must never share dbExecutor: a 2048px WEBP encode takes long
    // enough that queueing it behind the DB thread would stall list reloads.
    private val imgExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var imageStore: ClipboardImageStore? = null
    // One-shot per process: commit any delete that a process death interrupted, and GC.
    @Volatile private var imageStoreRepaired = false
    @Volatile private var shotWatcher: ClipboardScreenshotWatcher? = null
    /** Set while the vault popup is on screen, so a new screenshot can appear in place. */
    @Volatile private var vaultLiveRefresh: (() -> Unit)? = null
    @Volatile private var pendingScrollRestoreY = -1
    // Thumbnails are decoded off the main thread and cached by hash. Bounded so a long
    // scroll through a 100-image vault cannot grow Gboard's heap without limit.
    private val thumbCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 60
    }
    // One-line note shown above the list when a save was refused to protect a pin.
    @Volatile private var imgBudgetHint: String? = null

    /**
     * Suppression window for our own clipboard writes.
     *
     * The paste-back fallback puts the image on the system clipboard so the user can paste
     * it manually — which fires our own OnPrimaryClipChangedListener and re-captures the
     * image we just handed out, as a brand-new entry. Observed on device: pasting a stored
     * image added a second row whose bytes were our own re-encoded WebP (and which the
     * animated sniffer flagged GIF, since WebP shares the RIFF container).
     *
     * Hash matching cannot fix this — the re-read bytes are the WebP, not the original
     * source — so the guard is a short time window instead. Losing a genuine user copy
     * inside it costs one uncaptured image; without it every paste silently grows the vault.
     */
    @Volatile private var selfClipUntilMs = 0L

    // Soft-delete undo window. The deadline is absolute wall-clock, not a countdown, so
    // that a process death resolves deterministically instead of leaving a batch dangling.
    private val UNDO_WINDOW_MS = 15_000L
    @Volatile private var pendingDeleteDeadline = 0L
    @Volatile private var pendingDeleteLabel = ""

    // ── Clipboard-search input redirection ──
    // The clipboard popup lives inside Gboard's own process, so when its search
    // field is open the keyboard still commits text to the HOST app's editor. We
    // wrap InputMethodService.getCurrentInputConnection() once; the wrapper behaves
    // exactly like the real connection EXCEPT while [clipSearchActive] is true, when
    // it routes editing/reading to the search field's own editor ([clipSearchIC]).
    @Volatile private var clipSearchActive = false
    // A self-contained editor (its own private Editable) that the keyboard's
    // keystrokes are redirected into while search is open. BaseInputConnection in
    // fallback mode needs NO window focus, so the popup stays non-focusable and the
    // keyboard never hides.
    @Volatile private var clipSearchIC: android.view.inputmethod.BaseInputConnection? = null
    // Invoked (on the calling thread) after each redirected edit so the UI can
    // mirror the search editor's text and re-run the filter.
    @Volatile private var clipSearchOnEdit: (() -> Unit)? = null
    // Cache so we hand the same wrapper back for the same underlying connection.
    @Volatile private var icWrapReal: InputConnection? = null
    @Volatile private var icWrapWrapper: InputConnection? = null

    // ── Rich content (GIF / sticker) pass-through ──
    // While this is set (per-thread), getCurrentInputConnection() hands back the REAL
    // connection instead of our wrapper.
    //
    // WHY: inserting a GIF or sticker goes through InputConnection.commitContent().
    // The receiving app is handed a content:// URI it can only open if the framework
    // grants it read permission, and the code that creates that grant identifies the
    // caller by REFERENCE — it proceeds only when the connection commitContent was
    // invoked on is the very same object getCurrentInputConnection() returns.
    // (Historically that check lived in InputMethodService.exposeContent(); no method
    // by that name exists on Android 16, but the behaviour is unchanged.)
    //
    // Our wrapper is a different object, so the check fails and the grant is silently
    // skipped. commitContent still reports success to Gboard, so the keyboard thinks
    // it worked, but the app can't read the file and nothing is sent. The giveaway in
    // logcat is these two lines in the same millisecond:
    //
    //   CommitContentHelper: Committed image with mime-type=[image/gif] ... success=true
    //   ContentProviderHelper: Permission Denial: opening provider ...fileprovider
    //                          from ProcessRecord{...target app}
    //
    // Unwrapping for the duration of the call makes the check pass. Set from BOTH
    // ends so it holds no matter which connection object Gboard committed through:
    // our wrapper's commitContent override, and the grant method itself.
    private val icUnwrap = ThreadLocal.withInitial { false }

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
    @Volatile private var cachedUndoButton  = false      // toolbar button (undo_button_enabled) — OFF by default, shake undoes
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
            filePrefCache = PrefsJson.parse(File(PREFS_FILE).readText())
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
            cachedBtnSelectMode = cache["btn_selectmode_enabled"]?.let { it == "1" } ?: cachedBtnSelectMode
            // Losing the button must lose the mode with it — otherwise the stick keeps
            // selecting with nothing on screen to say why, and no way to switch it off.
            if ((!cachedBtnSelectMode || !cachedBtnTrackpad) && selectModeOn) {
                selectModeOn = false; selAnchor = -1
                XposedBridge.log("$TAG [KB] select mode OFF (button hidden)")
            }
            cachedTrackpadHaptics = cache["trackpad_haptics_enabled"]?.let { it == "1" } ?: cachedTrackpadHaptics
            cachedClipFullText = cache["clip_full_text_enabled"]?.let { it == "1" } ?: cachedClipFullText
            // Opt-in: absent key means OFF, unlike the toggles above which default ON.
            cachedClipImages   = cache["clip_images_enabled"]?.let { it == "1" } ?: cachedClipImages
            cachedImgMaxEntries = cache["clip_img_max_entries"]?.toIntOrNull() ?: cachedImgMaxEntries
            cachedImgMaxBytes  = cache["clip_img_max_mb"]?.toLongOrNull()?.times(1024 * 1024)
                ?: cachedImgMaxBytes
            cachedShotCapture     = cache["shot_capture_enabled"]?.let { it == "1" } ?: cachedShotCapture
            cachedShotToClipboard = cache["shot_to_clipboard_enabled"]?.let { it == "1" } ?: cachedShotToClipboard
            cachedShotVerbose     = cache["shot_log_verbose"]?.let { it == "1" } ?: cachedShotVerbose
            cachedUndoEnabled  = cache["undo_enabled"]?.let { it == "1" } ?: cachedUndoEnabled
            cachedUndoButton   = cache["undo_button_enabled"]?.let { it == "1" } ?: cachedUndoButton
            cachedShakeUndo    = cache["shake_undo_enabled"]?.let { it == "1" } ?: cachedShakeUndo
            cachedShakeSensitivity = cache["shake_sensitivity"]?.toFloatOrNull() ?: cachedShakeSensitivity
            cachedVibStrength  = cache["vibration_strength"]?.toIntOrNull() ?: cachedVibStrength
            cachedTrackpadSensitivity = cache["trackpad_sensitivity"]?.toFloatOrNull() ?: cachedTrackpadSensitivity
            cachedTrackpadRoot = cache["trackpad_root_injection_enabled"]?.let { it == "1" } ?: cachedTrackpadRoot
        }
        reconcileShotWatcher(ctx)
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

        // ── Clipboard search: wrap the input connection for keystroke redirect ─
        hookInputConnection(lpparam)
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
                        // inputType == 0 → input restarted on something that holds no text.
                        if ((param.args[0] as? EditorInfo)?.inputType == 0) inputRestartedNonEditable = true
                        try { refreshPrefs(ims.applicationContext); registerShake(ims.applicationContext) } catch (_: Throwable) {}
                        // Screenshot capture must not depend on the toolbar being injected:
                        // the toolbar needs a real editor, and Gboard is also brought up over
                        // targets that hold no text at all. Opening the vault here costs one
                        // SQLiteOpenHelper the process would open moments later anyway.
                        try { ensureVault(ims.applicationContext) } catch (_: Throwable) {}
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
                            ensureVault(ctx)
                            if (!clipboardListenerRegistered) {
                                clipboardListenerRegistered = true
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

    // Move the caret to an absolute offset. This is the safe way to move horizontally:
    // a DPAD at the edge of the text is left unhandled by the editor and falls through to
    // Android's focus search, which is exactly what threw the cursor out of the composer.
    // setSelection names an offset, the editor clamps it, and no key event is ever produced,
    // so focus cannot move. finishComposingText() first — a live composing region fights the
    // new selection and the editor would snap the caret back to the end of it.
    private fun setCaret(ims: InputMethodService, offset: Int): Boolean {
        val ic = ims.currentInputConnection ?: return false
        return try {
            ic.finishComposingText()
            ic.setSelection(offset, offset)
        } catch (_: Throwable) { false }
    }

    // Select-mode twin of setCaret: name BOTH ends of the selection. anchor stays put,
    // active is the end the stick is dragging. Same reasoning as setCaret — naming
    // offsets produces no key event, so nothing can fall through to focus search.
    private fun setSelectionRange(ims: InputMethodService, anchor: Int, active: Int): Boolean {
        val ic = ims.currentInputConnection ?: return false
        return try {
            ic.finishComposingText()
            ic.setSelection(anchor, active)
        } catch (_: Throwable) { false }
    }

    // Both ends of the current selection, or null if the editor won't report them.
    // start/end come back in document order — which end is the anchor is OUR bookkeeping,
    // not something the editor tells us.
    private fun readSelection(ims: InputMethodService): IntArray? {
        val ic = ims.currentInputConnection ?: return null
        try {
            val ext = ic.getExtractedText(ExtractedTextRequest().apply { token = 0 }, 0)
            if (ext != null && ext.selectionStart >= 0 && ext.selectionEnd >= 0)
                return intArrayOf(
                    minOf(ext.selectionStart, ext.selectionEnd),
                    maxOf(ext.selectionStart, ext.selectionEnd)
                )
        } catch (_: Throwable) {}
        try {
            val st = ic.getSurroundingText(0, 0, 0)
            if (st != null && st.selectionStart >= 0 && st.selectionEnd >= 0)
                return intArrayOf(
                    st.offset + minOf(st.selectionStart, st.selectionEnd),
                    st.offset + maxOf(st.selectionStart, st.selectionEnd)
                )
        } catch (_: Throwable) {}
        return null
    }

    // Vertical selection: a DPAD with SHIFT held. There is no offset we could name for
    // "one line up" (only the app knows its own wrapping), so the key stays — but the
    // real SHIFT_LEFT down/up around it is what makes the editor extend instead of move:
    // ArrowKeyMovementMethod reads MetaKeyKeyListener's selecting state off the buffer,
    // which only a genuine shift press sets. The metaState on the DPAD itself is for the
    // editors that check the event instead.
    private fun moveCursorDpadShift(ims: InputMethodService, keyCode: Int) {
        if (cachedTrackpadRoot) { rootShiftKeyEvent(keyCode); return }
        try {
            val ic = ims.currentInputConnection ?: return
            val now = SystemClock.uptimeMillis()
            fun ev(action: Int, code: Int, meta: Int) =
                KeyEvent(now, now, action, code, 0, meta)
            ic.sendKeyEvent(ev(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0))
            ic.sendKeyEvent(ev(KeyEvent.ACTION_DOWN, keyCode, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
            ic.sendKeyEvent(ev(KeyEvent.ACTION_UP,   keyCode, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
            ic.sendKeyEvent(ev(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_SHIFT_LEFT, 0))
        } catch (_: Throwable) {}
    }

    // Root fallback for the above. `input keyevent` can't hold a modifier, so this uses
    // `input keycombination`, which presses its keycodes together.
    private fun rootShiftKeyEvent(keyCode: Int) {
        try {
            if (rootStdin == null) {
                val proc = Runtime.getRuntime().exec("su")
                rootStdin = proc.outputStream
            }
            rootStdin?.apply {
                write("input keycombination ${KeyEvent.KEYCODE_SHIFT_LEFT} $keyCode\n".toByteArray())
                flush()
            }
        } catch (_: Throwable) {
            rootStdin = null   // force re-open next time
        }
    }

    // The whole field's text, or null if the editor won't hand it over. Grabbed ONCE per
    // gesture - dragging the caret never edits, so one snapshot holds - and it is what
    // lets a horizontal step land on a grapheme boundary instead of inside an emoji or
    // between an Arabic letter and its combining mark.
    private fun readText(ims: InputMethodService): CharSequence? {
        val ic = ims.currentInputConnection ?: return null
        return try {
            ic.getExtractedText(ExtractedTextRequest().apply { token = 0 }, 0)?.text
        } catch (_: Throwable) { null }
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

        // Button 3a — Select mode 🖍️ (toggle)
        // While it is ON the stick drags a SELECTION instead of the bare caret: the offset
        // the gesture started from stays anchored, and everything between it and the moving
        // cursor stays highlighted — forwards or backwards. It does nothing without the
        // stick, so it only appears alongside it.
        if (cachedBtnTrackpad && cachedBtnSelectMode) {
            val btn = makeBtn("🖍️")
            // The button IS the state readout — dimmed when off, lit as an accent pill when
            // on. The pill is the module's own accent tint + hairline (same treatment as a
            // selected row in the vault), which reads on light and dark keyboard themes
            // alike; a plain white wash would vanish on a light one.
            fun paint() {
                btn.alpha = if (selectModeOn) 1f else 0.45f
                btn.background = if (selectModeOn)
                    roundRect(UI.ACCENT_BG, 10f * dp, UI.ACCENT, (1f * dp).toInt())
                else null
            }
            paint()
            btn.setOnClickListener {
                selectModeOn = !selectModeOn
                selAnchor = -1             // next grab re-anchors wherever the caret is
                paint()
                btn.performHapticFeedback(
                    if (selectModeOn) HapticFeedbackConstants.LONG_PRESS
                    else             HapticFeedbackConstants.KEYBOARD_TAP
                )
                XposedBridge.log("$TAG [KB] select mode ${if (selectModeOn) "ON" else "OFF"}")
            }
            add(btn)
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
                                      // in select mode this is the ACTIVE end of the selection
            var anchor = -1           // select mode: the fixed end (-1 = plain caret dragging)
            var textLen = -1          // total length (-1 = unknown → no right-edge detection)
            var rtl = false           // RTL field → DPAD_RIGHT decreases the logical index
            var canTrack = false      // true once we have a valid caret to count from
            var escaped = false       // focus left the editor → send nothing more this gesture
            var needsResync = false   // a vertical DPAD moved the caret; re-read before naming an offset
            var gestureText: CharSequence? = null   // one snapshot per gesture, for grapheme steps
            var graphemes: BreakIterator? = null    // grapheme boundaries over gestureText
            val blocked = HashSet<Int>()   // directions parked at a boundary this gesture
            btn.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        active = true; accX = 0f; accY = 0f; steps = 0
                        lastX = e.rawX; lastY = e.rawY
                        blocked.clear(); escaped = false; needsResync = false; inputRestartedNonEditable = false
                        // Select mode seeds two offsets, not one. A selection already on
                        // screen whose anchor we recognise is CONTINUED (so a second grab
                        // keeps growing the same highlight); anything else re-anchors here.
                        val sel = if (selectModeOn) readSelection(ims) else null
                        if (sel != null) {
                            when {
                                sel[0] == sel[1]    -> { anchor = sel[0]; predIndex = sel[0] }
                                selAnchor == sel[1] -> { anchor = sel[1]; predIndex = sel[0] }
                                else                -> { anchor = sel[0]; predIndex = sel[1] }
                            }
                            selAnchor = anchor
                        } else {
                            // Not in select mode, or the editor won't report a selection —
                            // fall back to plain caret dragging rather than guess an anchor.
                            anchor = -1
                            predIndex = readCaret(ims)
                            if (!selectModeOn) selAnchor = -1
                        }
                        rtl = isRtlContext(ims)
                        gestureText = readText(ims)
                        textLen = gestureText?.length ?: readLen(ims)
                        graphemes = gestureText?.let { t ->
                            try { BreakIterator.getCharacterInstance().apply { setText(t.toString()) } }
                            catch (_: Throwable) { null }
                        }
                        canTrack = predIndex >= 0
                        hapticHeavy(ctx)   // heavy "pop" on grab
                        // Keep the gesture ours — don't let the keyboard layout steal the drag.
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        XposedBridge.log("$TAG [KB] trackpad DOWN caret=$predIndex anchor=$anchor sel=$selectModeOn len=$textLen rtl=$rtl track=$canTrack root=$cachedTrackpadRoot")
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!active || escaped) return@setOnTouchListener true
                        accX += e.rawX - lastX
                        accY += e.rawY - lastY
                        lastX = e.rawX; lastY = e.rawY
                        val sens = cachedTrackpadSensitivity.coerceIn(0.25f, 6f)
                        val stepX = TRACKPAD_STEP_X / sens
                        val stepY = TRACKPAD_STEP_Y / sens

                        // A restart with inputType == 0 means a DPAD may have just handed
                        // focus to a neighbouring view. It also fires spuriously (Snapchat
                        // restarts input mid-gesture while the composer keeps focus), so
                        // confirm with ONE caret read before abandoning the gesture. Costs
                        // nothing on the normal path — it only reads when the flag is up.
                        fun fieldLost(): Boolean {
                            if (escaped) return true
                            if (!inputRestartedNonEditable) return false
                            inputRestartedNonEditable = false
                            if (readCaret(ims) >= 0) return false      // spurious restart
                            escaped = true; hapticBoundary(ctx)
                            XposedBridge.log("$TAG [KB] trackpad ESCAPED (input restarted with no text field) steps=$steps")
                            return true
                        }

                        // -- Horizontal: setSelection, not DPAD --------------------------
                        // Android's Selection.moveLeft/moveRight return false once the caret is
                        // at the end of the text. An unhandled DPAD falls through to focus
                        // search, which hands focus to a neighbouring view - the escape. Naming
                        // an absolute offset removes the key event entirely, so there is nothing
                        // to fall through, and it is exact: the +/-1 bookkeeping the old path
                        // kept drifted under RTL until the caret and predIndex disagreed.
                        // One IC call per touch event now, not one per step.
                        // One offset step, measured in grapheme clusters. Stepping by raw
                        // index would drop the caret inside an emoji (two chars) or between
                        // an Arabic letter and its combining mark - the editor accepts that
                        // offset, and the next keystroke splits the character. Falls back to
                        // raw indices only when the editor refused to hand over its text.
                        fun stepOffset(from: Int, delta: Int): Int {
                            val limit = if (textLen >= 0) textLen else Int.MAX_VALUE
                            val bi  = graphemes
                            val len = gestureText?.length
                            if (bi == null || len == null) return (from + delta).coerceIn(0, limit)
                            return try {
                                var idx = from.coerceIn(0, len)
                                var n = kotlin.math.abs(delta)
                                while (n > 0) {
                                    // following()/preceding() snap to the nearest boundary even
                                    // when idx is mid-cluster, so this self-corrects.
                                    val next = if (delta > 0) bi.following(idx) else bi.preceding(idx)
                                    if (next == BreakIterator.DONE) break   // start/end of text
                                    idx = next; n--
                                }
                                idx.coerceIn(0, limit)
                            } catch (_: Throwable) { (from + delta).coerceIn(0, limit) }
                        }

                        // Some editors report nothing until they have been touched, so a failed
                        // read at grab time must not freeze the stick for the whole gesture.
                        fun ensureCaret(): Boolean {
                            if (canTrack) return true
                            val c = readCaret(ims)
                            if (c < 0) return false
                            predIndex = c; canTrack = true
                            if (textLen < 0) textLen = readLen(ims)
                            return true
                        }

                        // Re-read the offset the stick is steering after the app moved it
                        // (a vertical step). Outside select mode that is just the caret;
                        // inside it, it is whichever end of the selection is NOT the anchor.
                        fun resyncActive(): Boolean {
                            if (selectModeOn && anchor >= 0) {
                                val s = readSelection(ims) ?: return false
                                when {
                                    // Collapsed. Either the caret came home to the anchor
                                    // (a genuine empty selection), or this editor ignored the
                                    // SHIFT and just MOVED the caret a line. The second case is
                                    // repairable and common: the app has already done the part
                                    // only it can do — placing the caret on the right line —
                                    // so we simply rebuild the range around it. That makes
                                    // vertical selection work even where shift is swallowed.
                                    s[0] == s[1] -> {
                                        predIndex = s[0]
                                        if (predIndex != anchor) {
                                            setSelectionRange(ims, anchor, predIndex)
                                            XposedBridge.log("$TAG [KB] trackpad V-repair anchor=$anchor active=$predIndex")
                                        }
                                    }
                                    s[0] == anchor -> predIndex = s[1]
                                    s[1] == anchor -> predIndex = s[0]
                                    // Neither end is ours any more (the editor re-selected
                                    // on its own). Adopt what it reports rather than yank
                                    // the highlight back to a stale anchor.
                                    else -> { anchor = s[0]; selAnchor = anchor; predIndex = s[1] }
                                }
                                return true
                            }
                            val c = readCaret(ims)
                            if (c < 0) return false
                            predIndex = c
                            return true
                        }

                        fun applyHoriz(visualSteps: Int): Boolean {
                            if (fieldLost() || visualSteps == 0) return false
                            if (!canTrack) return false   // no caret to name - never guess an offset
                            if (needsResync) {
                                // A vertical DPAD just moved the caret somewhere only the app
                                // knows. Re-read before naming an offset, or we would yank the
                                // caret back to a stale one.
                                if (!resyncActive()) { canTrack = false; return false }
                                needsResync = false
                            }
                            // In an RTL field a drag to the visual right walks the logical index
                            // down, so the sign flips.
                            val delta  = if (rtl) -visualSteps else visualSteps
                            val target = stepOffset(predIndex, delta)
                            if (target == predIndex) {
                                hapticBoundary(ctx)
                                XposedBridge.log("$TAG [KB] trackpad H-edge pred=$predIndex len=$textLen")
                                return false
                            }
                            // Select mode names both ends; plain mode collapses onto one.
                            val moved = if (selectModeOn && anchor >= 0)
                                setSelectionRange(ims, anchor, target)
                            else
                                setCaret(ims, target)
                            if (!moved) { canTrack = false; return false }
                            steps += kotlin.math.abs(target - predIndex)
                            predIndex = target
                            hapticTick(ctx)
                            XposedBridge.log("$TAG [KB] trackpad H-set pred=$predIndex anchor=$anchor")
                            return true
                        }

                        // The two cases setSelection cannot serve: root injection mode (the
                        // InputConnection is bypassed on purpose) and editors that never report
                        // a caret, where there is no offset to name. Both keep the legacy
                        // per-step DPAD path, with 3.2.2's guards bounding the damage.
                        fun emitHorizKey(keyCode: Int): Boolean {
                            if (fieldLost() || keyCode in blocked) return false
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
                            // Shift-DPAD when selecting: this path is only reached in root
                            // mode or when the caret is unreadable, so there is no offset to
                            // name — the modifier is the only way to extend instead of move.
                            if (selectModeOn) moveCursorDpadShift(ims, keyCode)
                            else             moveCursorDpad(ims, keyCode)
                            steps++
                            if (canTrack) {
                                predIndex += when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (rtl) -1 else 1
                                    else                        -> if (rtl) 1 else -1
                                }
                                if (predIndex < 0) predIndex = 0
                                if (textLen >= 0 && predIndex > textLen) predIndex = textLen
                            }
                            blocked.remove(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                                KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                            hapticTick(ctx)
                            return true
                        }

                        // -- Vertical: still a DPAD, but one key in flight at a time -----
                        // Only the app knows its own soft-wrap layout, so there is no offset we
                        // could name for "one line up". Selection.moveUp returns false - and so
                        // escapes - only when the caret is ALREADY at offset 0; moveDown likewise
                        // at textLen. The old code tested exactly that and still let the escape
                        // through, because the burst loop fired several keys per touch event while
                        // sendKeyEvent is asynchronous: the app was a key or two behind, so the
                        // offset being tested was stale. Sending at most one key per event, gated
                        // on a caret read taken immediately before it, makes the test mean
                        // something again.
                        fun emitVert(keyCode: Int): Boolean {
                            if (fieldLost() || keyCode in blocked) return false
                            if (!resyncActive()) {
                                canTrack = false; escaped = true; hapticBoundary(ctx)
                                XposedBridge.log("$TAG [KB] trackpad ESCAPED key=$keyCode (caret unreadable)")
                                return false
                            }
                            val now = predIndex
                            canTrack = true; needsResync = false
                            val escapes = when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP   -> now <= 0
                                KeyEvent.KEYCODE_DPAD_DOWN -> textLen >= 0 && now >= textLen
                                else -> false
                            }
                            if (escapes) {
                                blocked.add(keyCode); hapticBoundary(ctx)
                                XposedBridge.log("$TAG [KB] trackpad V-boundary key=$keyCode pred=$now len=$textLen")
                                return false
                            }
                            if (selectModeOn && anchor >= 0) moveCursorDpadShift(ims, keyCode)
                            else                            moveCursorDpad(ims, keyCode)
                            steps++
                            hapticTick(ctx)
                            needsResync = true      // the app owns the new offset now
                            blocked.remove(KeyEvent.KEYCODE_DPAD_LEFT)
                            blocked.remove(KeyEvent.KEYCODE_DPAD_RIGHT)
                            XposedBridge.log("$TAG [KB] trackpad V-step key=$keyCode from=$now")
                            return true
                        }

                        // Horizontal drains fully - setSelection is one call regardless of
                        // distance, so a fast flick costs no more than a slow one.
                        var hSteps = 0
                        while (accX >= stepX)  { hSteps++; accX -= stepX }
                        while (accX <= -stepX) { hSteps--; accX += stepX }
                        if (hSteps != 0) {
                            val exact = !cachedTrackpadRoot && ensureCaret()
                            val ok = if (exact) applyHoriz(hSteps) else {
                                val key = if (hSteps > 0) KeyEvent.KEYCODE_DPAD_RIGHT
                                          else            KeyEvent.KEYCODE_DPAD_LEFT
                                var any = false
                                repeat(kotlin.math.abs(hSteps)) { if (emitHorizKey(key)) any = true }
                                any
                            }
                            if (!ok) accX = 0f
                        }

                        // Vertical takes ONE step per event and caps the leftover, so a fast
                        // flick cannot build a backlog that keeps firing after the finger stops.
                        if (accY >= stepY) {
                            if (emitVert(KeyEvent.KEYCODE_DPAD_DOWN)) accY = (accY - stepY).coerceAtMost(stepY)
                            else accY = 0f
                        } else if (accY <= -stepY) {
                            if (emitVert(KeyEvent.KEYCODE_DPAD_UP)) accY = (accY + stepY).coerceAtLeast(-stepY)
                            else accY = 0f
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        active = false
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        // Remember the anchor so the next grab keeps growing THIS highlight
                        // instead of collapsing it and starting over.
                        if (selectModeOn && anchor >= 0) selAnchor = anchor
                        // One resync on release to absorb any drift from internal counting.
                        val finalCaret = readCaret(ims)
                        XposedBridge.log("$TAG [KB] trackpad UP steps=$steps pred=$predIndex anchor=$anchor actual=$finalCaret escaped=$escaped root=$cachedTrackpadRoot")
                        // Selecting is nearly always the first half of "copy this" — so the
                        // action bar comes to the finger instead of costing another tap.
                        // steps > 0 keeps a stray tap on the stick from popping it, and the
                        // getSelectedText check keeps it shut when the drag selected nothing.
                        if (selectModeOn && !escaped && steps > 0) {
                            val sel = try { ims.currentInputConnection?.getSelectedText(0) }
                                      catch (_: Throwable) { null }
                            if (!sel.isNullOrEmpty()) {
                                XposedBridge.log("$TAG [KB] select mode: opening action bar len=${sel.length}")
                                showSelectionMenu(ctx, ims, toolbar)
                            }
                        }
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
        // One bar at a time. Two entry points can reach this (the ✂️ button and releasing
        // the stick in select mode), and each call builds its own PopupWindow — without
        // this, a tap on ✂️ followed by a stick release leaves two stacked bars, the lower
        // one unreachable.
        try { selectionMenu?.dismiss() } catch (_: Throwable) {}
        selectionMenu = null

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
        selectionMenu = popup
        popup.setOnDismissListener { if (selectionMenu === popup) selectionMenu = null }

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

    // Hook getCurrentInputConnection so the keyboard always talks to OUR wrapper.
    // Installed once at load; the wrapper is a no-op passthrough unless search is on.
    private fun hookInputConnection(lpparam: XC_LoadPackage.LoadPackageParam) {
        val imsClass = "android.inputmethodservice.InputMethodService"
        try {
            XposedHelpers.findAndHookMethod(
                imsClass, lpparam.classLoader, "getCurrentInputConnection",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // A GIF/sticker commit is in flight — leave the REAL connection
                        // in place so the URI grant's identity check passes (see icUnwrap).
                        if (icUnwrap.get() == true) return
                        val real = param.result as? InputConnection ?: return
                        if (real is SearchRedirectConnection) return
                        val cached = icWrapWrapper
                        param.result = if (cached != null && icWrapReal === real) cached
                        else SearchRedirectConnection(real).also { icWrapReal = real; icWrapWrapper = it }
                    }
                }
            )
            XposedBridge.log("$TAG [KB] getCurrentInputConnection wrap installed")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] getCurrentInputConnection hook failed: ${t.message}")
        }

        // Unwrap around the grant method too. Covers the case where Gboard commits
        // through a connection reference it cached itself rather than through our
        // wrapper — then our commitContent override never runs, but this still lets the
        // grant through. Inert on Android 16 (no such method); the override carries it.
        try {
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { icUnwrap.set(true) }
                override fun afterHookedMethod(param: MethodHookParam) {
                    icUnwrap.set(false)
                    XposedBridge.log("$TAG [KB] rich-content grant passed through")
                }
            }
            // Matched by SIGNATURE, not by name: the grant hook has historically been
            // called exposeContent() but is absent under that name on Android 16, so
            // look for whatever method takes (InputContentInfo, InputConnection) —
            // that pair is unambiguous — anywhere up the service's class hierarchy.
            var cls: Class<*>? = XposedHelpers.findClass(imsClass, lpparam.classLoader)
            var hooked = 0
            while (cls != null && hooked == 0) {
                for (m in cls.declaredMethods) {
                    val p = m.parameterTypes
                    if (p.size == 2 &&
                        InputContentInfo::class.java.isAssignableFrom(p[0]) &&
                        InputConnection::class.java.isAssignableFrom(p[1])) {
                        XposedBridge.hookMethod(m, hook)
                        hooked++
                        XposedBridge.log("$TAG [KB] rich-content grant hook on ${cls!!.name}.${m.name}")
                    }
                }
                cls = cls.superclass
            }
            if (hooked == 0) XposedBridge.log("$TAG [KB] no grant method found — relying on commitContent unwrap")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] rich-content grant hook failed: ${t.message}")
        }
    }

    // Delegates everything to the real connection, EXCEPT while clipSearchActive it
    // routes edits/reads to the clipboard search field's editor (clipSearchIC). This
    // is how the keyboard "types into" our search box without the host app seeing it.
    private inner class SearchRedirectConnection(target: InputConnection) :
        InputConnectionWrapper(target, true) {

        // Runs the redirect block when search is active and returns its result;
        // returns null to signal "not redirected — caller should fall through to
        // super". Only valid for the boolean editing methods.
        private inline fun edit(block: (InputConnection) -> Boolean): Boolean? {
            val s = clipSearchIC
            if (clipSearchActive && s != null) {
                val r = try { block(s) } catch (_: Throwable) { true }
                try { clipSearchOnEdit?.invoke() } catch (_: Throwable) {}
                return r
            }
            return null
        }

        override fun commitText(t: CharSequence?, p: Int): Boolean =
            edit { it.commitText(t, p) } ?: super.commitText(t, p)
        override fun commitText(t: CharSequence, p: Int, a: TextAttribute?): Boolean =
            edit { it.commitText(t, p) } ?: super.commitText(t, p, a)
        override fun setComposingText(t: CharSequence?, p: Int): Boolean =
            edit { it.setComposingText(t, p) } ?: super.setComposingText(t, p)
        override fun setComposingText(t: CharSequence, p: Int, a: TextAttribute?): Boolean =
            edit { it.setComposingText(t, p) } ?: super.setComposingText(t, p, a)
        override fun setComposingRegion(s: Int, e: Int): Boolean =
            edit { it.setComposingRegion(s, e) } ?: super.setComposingRegion(s, e)
        override fun finishComposingText(): Boolean =
            edit { it.finishComposingText() } ?: super.finishComposingText()
        override fun deleteSurroundingText(b: Int, a: Int): Boolean =
            edit { it.deleteSurroundingText(b, a) } ?: super.deleteSurroundingText(b, a)
        override fun deleteSurroundingTextInCodePoints(b: Int, a: Int): Boolean =
            edit { it.deleteSurroundingTextInCodePoints(b, a) } ?: super.deleteSurroundingTextInCodePoints(b, a)
        // Hardware-style key events. In non-dummy BaseInputConnection, sendKeyEvent
        // does NOT edit the buffer, so the Backspace key (which Gboard delivers as a
        // KEYCODE_DEL key event) would do nothing. Handle DEL + printable chars
        // against the search editor ourselves, and consume everything else so no key
        // leaks to the host app while searching.
        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            val s = clipSearchIC
            if (clipSearchActive && s != null) {
                if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> { s.deleteSurroundingText(1, 0); clipSearchOnEdit?.invoke() }
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { /* ignore */ }
                        else -> {
                            val u = event.unicodeChar
                            if (u != 0) { s.commitText(u.toChar().toString(), 1); clipSearchOnEdit?.invoke() }
                        }
                    }
                }
                return true
            }
            return super.sendKeyEvent(event)
        }
        // GIF / sticker insertion. Never redirected into the clipboard-search editor —
        // that editor holds plain text only, and the image belongs in the host app
        // either way. Unwrapped for the duration of the call so the framework grants
        // the receiving app read access to the URI (see icUnwrap).
        override fun commitContent(
            inputContentInfo: InputContentInfo,
            flags: Int,
            opts: android.os.Bundle?
        ): Boolean {
            icUnwrap.set(true)
            return try {
                super.commitContent(inputContentInfo, flags, opts)
            } finally {
                icUnwrap.set(false)
            }
        }
        override fun commitCompletion(text: CompletionInfo?): Boolean =
            edit { it.commitCompletion(text) } ?: super.commitCompletion(text)
        override fun commitCorrection(info: CorrectionInfo?): Boolean =
            edit { it.commitCorrection(info) } ?: super.commitCorrection(info)
        override fun performEditorAction(action: Int): Boolean =
            edit { it.performEditorAction(action) } ?: super.performEditorAction(action)
        override fun setSelection(s: Int, e: Int): Boolean =
            edit { it.setSelection(s, e) } ?: super.setSelection(s, e)

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            val s = clipSearchIC
            return if (clipSearchActive && s != null) runCatching { s.getTextBeforeCursor(n, flags) }.getOrNull()
                   else super.getTextBeforeCursor(n, flags)
        }
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
            val s = clipSearchIC
            return if (clipSearchActive && s != null) runCatching { s.getTextAfterCursor(n, flags) }.getOrNull()
                   else super.getTextAfterCursor(n, flags)
        }
        override fun getSelectedText(flags: Int): CharSequence? {
            val s = clipSearchIC
            return if (clipSearchActive && s != null) runCatching { s.getSelectedText(flags) }.getOrNull()
                   else super.getSelectedText(flags)
        }
        override fun getCursorCapsMode(reqModes: Int): Int {
            val s = clipSearchIC
            return if (clipSearchActive && s != null) runCatching { s.getCursorCapsMode(reqModes) }.getOrDefault(0)
                   else super.getCursorCapsMode(reqModes)
        }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            val s = clipSearchIC
            return if (clipSearchActive && s != null) runCatching { s.getExtractedText(request, flags) }.getOrNull()
                   else super.getExtractedText(request, flags)
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
        var searchQuery = ""
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
        val tabAll = tab("🐈‍⬛ All")
        val tabFav = tab("❣️ Favorites")
        tabStrip.addView(tabAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabStrip.addView(tabFav, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        outerContainer.addView(tabStrip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins((14f * dp).toInt(), 0, (14f * dp).toInt(), (8f * dp).toInt()) })

        // ── Collapsible search row (hidden until 🔍 is tapped) ──
        val searchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(UI.SURFACE, 12f * dp)
            setPadding((12f * dp).toInt(), 0, (4f * dp).toInt(), 0)
            visibility = View.GONE
        }
        val searchField = EditText(ctx).apply {
            hint = "Search clipboard…"
            setHintTextColor(UI.TEXT_DIM)
            setTextColor(UI.TEXT)
            textSize = 14f
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(0, (10f * dp).toInt(), 0, (10f * dp).toInt())
        }
        val searchClear = TextView(ctx).apply {
            text = "✕"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(UI.TEXT_DIM)
            minWidth = (32f * dp).toInt()
            minHeight = (32f * dp).toInt()
            background = ripple(null, 16f * dp)
            visibility = View.GONE
        }
        searchRow.addView(searchField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(searchClear)
        outerContainer.addView(searchRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins((14f * dp).toInt(), 0, (14f * dp).toInt(), (8f * dp).toInt()) })

        // ── Toolbar: sort chip + delete all chip ──
        val sortLabels = listOf("Newest ↓", "Oldest ↑", "📌 First", "❣️ First")
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
        val searchBtn = TextView(ctx).apply {
            text = "🔍  Search"
            setTextColor(UI.TEXT)
            textSize = 11f
            setPadding((12f * dp).toInt(), (7f * dp).toInt(), (12f * dp).toInt(), (7f * dp).toInt())
            background = ripple(UI.SURFACE, chipR)
        }
        // The single "Delete all" is now two type-scoped buttons. They must NEVER touch
        // the other type — that is the highest-risk regression in this feature, and it is
        // enforced in SQL by softDeleteAll(kind) rather than trusted to the caller.
        fun dangerChip(label: String) = TextView(ctx).apply {
            text = label
            setTextColor(UI.DANGER)
            textSize = 11f
            setPadding((10f * dp).toInt(), (7f * dp).toInt(), (10f * dp).toInt(), (7f * dp).toInt())
            background = ripple(Color.parseColor("#1FFF6B6B"), chipR)
        }
        val deleteTextsBtn = dangerChip("🗑  Texts")
        val deleteImagesBtn = dangerChip("🗑  Images")

        sortRow.addView(sortBtn)
        sortRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        sortRow.addView(searchBtn)
        sortRow.addView(View(ctx), LinearLayout.LayoutParams((6f * dp).toInt(), 1))
        sortRow.addView(deleteTextsBtn)
        sortRow.addView(View(ctx), LinearLayout.LayoutParams((6f * dp).toInt(), 1))
        sortRow.addView(deleteImagesBtn)
        outerContainer.addView(sortRow)

        // ── Undo bar: hidden until a delete arms it, then ticks 15 → 0 ──
        val undoBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = roundRect(UI.ELEVATED, 12f * dp, UI.DIVIDER, (1f * dp).toInt())
            setPadding((14f * dp).toInt(), (9f * dp).toInt(), (8f * dp).toInt(), (9f * dp).toInt())
        }
        val undoLabel = TextView(ctx).apply {
            setTextColor(UI.TEXT)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val undoBtn = TextView(ctx).apply {
            text = "↩  Undo"
            setTextColor(UI.ACCENT)
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding((12f * dp).toInt(), (6f * dp).toInt(), (12f * dp).toInt(), (6f * dp).toInt())
            background = ripple(null, 10f * dp)
        }
        undoBar.addView(undoLabel)
        undoBar.addView(undoBtn)
        outerContainer.addView(undoBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins((14f * dp).toInt(), 0, (14f * dp).toInt(), (8f * dp).toInt()) })

        // ── Counters: "Images: 34 items · 41.2 MB / 100 MB" ──
        // The spec puts this in the settings app; it lives here because the store sits in
        // Gboard's private data dir, which the module's own process cannot read.
        val statsLabel = TextView(ctx).apply {
            setTextColor(UI.TEXT_DIM)
            textSize = 10f
            setPadding((16f * dp).toInt(), 0, (16f * dp).toInt(), (6f * dp).toInt())
        }
        outerContainer.addView(statsLabel)

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

        // Non-focusable: the popup must NOT take IME focus, or the keyboard hides
        // and there's nothing to type with. Keystrokes are redirected into the
        // search editor via the InputConnection hook instead.
        val popup = PopupWindow(outerContainer, popupWidth, (360f * dp).toInt(), false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isTouchable = true
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
                val all = db.getAll(clipSortMode, showFavoritesOnly)
                val query = searchQuery
                // When searching, rank by relevance (overrides sort mode); otherwise
                // keep the sort-mode order and render plain (no highlight) rows.
                val rows: List<Pair<ClipboardDatabase.Entry, List<IntRange>>> =
                    if (query.isBlank()) all.map { it to emptyList() }
                    else ClipboardSearch.search(all, query).map { it.entry to it.matchRanges }

                // Cost readout, computed on the DB thread with the rest of the query.
                val imgCount = db.countLive(ClipboardDatabase.KIND_IMAGE)
                val imgBytes = db.liveImageBytes()
                val hint = imgBudgetHint
                statsLabel.post {
                    statsLabel.visibility = if (cachedClipImages || imgCount > 0) View.VISIBLE else View.GONE
                    statsLabel.text = when {
                        hint != null -> hint
                        else -> "Images: $imgCount item${if (imgCount == 1) "" else "s"} · " +
                            "${ClipboardImagePolicy.formatBytes(imgBytes)} / " +
                            ClipboardImagePolicy.formatBytes(cachedImgMaxBytes)
                    }
                    statsLabel.setTextColor(if (hint != null) UI.DANGER else UI.TEXT_DIM)
                }

                listContainer.post {
                    listContainer.removeAllViews()
                    if (rows.isEmpty()) {
                        listContainer.addView(TextView(ctx).apply {
                            text = when {
                                query.isNotBlank() -> "No matches for \"$query\""
                                showFavoritesOnly  -> "No favorites yet — long-press an entry and tap ★"
                                else               -> "No clipboard history yet"
                            }
                            setTextColor(UI.TEXT_DIM)
                            textSize = 14f
                            gravity = Gravity.CENTER
                            setPadding((16f * dp).toInt(), (40f * dp).toInt(), (16f * dp).toInt(), (40f * dp).toInt())
                        })
                    } else {
                        val shown = displayLimit.coerceAtMost(rows.size)
                        rows.take(shown).forEach { (entry, ranges) ->
                            buildClipboardRow(ctx, dp, entry, db, ims, popup, listContainer, ranges) { reloadList() }
                        }
                        val remaining = rows.size - shown
                        if (remaining > 0) {
                            val more = remaining.coerceAtMost(pageSize)
                            listContainer.addView(TextView(ctx).apply {
                                text = "♾️  Load $more more  ($remaining left)"
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
                    // A rebuild triggered by something other than the user (a screenshot
                    // landing while the vault is open) must not throw away where they were
                    // reading. User-initiated reloads leave this at -1 and scroll to top as
                    // before.
                    val restore = pendingScrollRestoreY
                    if (restore >= 0) {
                        pendingScrollRestoreY = -1
                        scrollView.post { try { scrollView.scrollTo(0, restore) } catch (_: Throwable) {} }
                    }
                }
            }
        }

        vaultLiveRefresh = {
            pendingScrollRestoreY = try { scrollView.scrollY } catch (_: Throwable) { -1 }
            reloadList()
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
        // ── Delete + 15-second undo ──
        //
        // No confirmation dialog: the rows vanish at once and an undo bar counts down.
        // Deletion is SOFT — rows are tombstoned and the countdown holds the only path
        // back. Files are never moved to a trash directory: under content-hash dedup the
        // file being "trashed" may still back an entry that was NOT deleted, so bytes are
        // released only at commit, and only for hashes whose last reference has gone.
        val undoTicker = object : Runnable {
            override fun run() {
                val remain = ((pendingDeleteDeadline - System.currentTimeMillis()) / 1000L).toInt()
                if (remain <= 0) {
                    commitPendingDelete()
                    undoBar.visibility = View.GONE
                    reloadList()
                    return
                }
                undoLabel.text = "$pendingDeleteLabel · ${remain}s"
                undoBar.postDelayed(this, 250L)
            }
        }

        fun hideUndo() {
            undoBar.removeCallbacks(undoTicker)
            undoBar.visibility = View.GONE
        }

        fun armUndo(kind: Int, count: Int, deadline: Long) {
            pendingDeleteDeadline = deadline
            pendingDeleteLabel = if (kind == ClipboardDatabase.KIND_IMAGE)
                "Deleted $count image${if (count == 1) "" else "s"}"
            else
                "Deleted $count text${if (count == 1) "" else "s"}"
            undoBar.visibility = View.VISIBLE
            undoBar.removeCallbacks(undoTicker)
            undoTicker.run()
        }

        fun deleteAllOfKind(kind: Int) {
            dbExecutor.submit {
                try {
                    // Exactly one pending undo at a time: a second delete commits the first
                    // immediately rather than merging the two batches.
                    if (db.hasPendingDelete()) commitPendingDeleteBlocking()
                    val deadline = System.currentTimeMillis() + UNDO_WINDOW_MS
                    val n = db.softDeleteAll(kind, deadline)
                    undoBar.post {
                        if (n > 0) armUndo(kind, n, deadline) else hideUndo()
                        reloadList()
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG [KB] delete-all failed: ${t.message}")
                }
            }
        }

        deleteTextsBtn.setOnClickListener { deleteAllOfKind(ClipboardDatabase.KIND_TEXT) }
        deleteImagesBtn.setOnClickListener { deleteAllOfKind(ClipboardDatabase.KIND_IMAGE) }

        undoBtn.setOnClickListener {
            hideUndo()
            dbExecutor.submit {
                val n = db.undoPendingDelete()
                undoBar.post { reloadList() }
                XposedBridge.log("$TAG [KB] undo restored $n entries")
            }
        }

        // A dangling Handler callback would hold this popup's views (and Gboard's context)
        // alive, so the countdown is torn down when the bar leaves the window. The pending
        // delete itself survives — it is a row state with an absolute deadline, and the
        // next process start commits it.
        undoBar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { undoBar.removeCallbacks(undoTicker) }
        })

        // A pending batch can outlive the popup that created it — the user deletes, then
        // closes the keyboard. Because the deadline is absolute, reopening resolves it the
        // same way a fresh process would: past the deadline it commits, still inside the
        // window it re-arms the bar with the time that is actually left. Without this the
        // rows would stay invisible-but-uncommitted, with no way to take the delete back.
        dbExecutor.submit {
            try {
                if (!db.hasPendingDelete()) return@submit
                val deadline = db.pendingDeadline()
                val kind = db.pendingKind()
                val n = db.countPending()
                if (System.currentTimeMillis() >= deadline) {
                    commitPendingDeleteBlocking()
                    undoBar.post { hideUndo(); reloadList() }
                } else {
                    undoBar.post { armUndo(kind, n, deadline) }
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [KB] pending-delete resume failed: ${t.message}")
            }
        }

        // ── Search wiring ──
        fun styleSearchChip(active: Boolean) {
            searchBtn.setTextColor(if (active) UI.ACCENT else UI.TEXT)
            searchBtn.background = ripple(if (active) UI.ACCENT_BG else UI.SURFACE, chipR)
        }
        val searchDebounce = Runnable {
            displayLimit = pageSize
            reloadList()
        }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                searchClear.visibility = if (searchQuery.isEmpty()) View.GONE else View.VISIBLE
                searchField.removeCallbacks(searchDebounce)
                searchField.postDelayed(searchDebounce, 120L)
            }
        })

        // Mirror the redirected editor's text into the visible field; the field's
        // TextWatcher then updates the query and re-runs the filter. setText() does
        // not pass back through the redirect, so there's no recursion.
        fun syncFromEditor() {
            val ic = clipSearchIC ?: return
            val text = ic.getEditable()?.toString() ?: ""
            searchField.post {
                if (searchField.text?.toString() == text) return@post
                searchField.setText(text)
                searchField.setSelection(text.length)
            }
        }

        // Full height covers the keyboard (fine for browsing clips); when search
        // opens we LIFT the popup above the keyboard so the keys are usable.
        val fullHeight = (360f * dp).toInt()
        val searchHeight = (300f * dp).toInt()
        fun openSearch() {
            searchRow.visibility = View.VISIBLE
            styleSearchChip(true)
            // Self-contained editor that RETAINS text. fullEditor=true disables
            // BaseInputConnection's "dummy mode" (which would turn commitText into
            // key events and clear the buffer); we back it with our own persistent
            // Editable so commit/compose/delete accumulate normally. Needs no focus.
            clipSearchIC = object : android.view.inputmethod.BaseInputConnection(searchField, true) {
                private val ed = android.text.Editable.Factory.getInstance().newEditable("")
                    .apply { android.text.Selection.setSelection(this, 0) }
                override fun getEditable(): android.text.Editable = ed
            }
            clipSearchOnEdit = { syncFromEditor() }
            clipSearchActive = true
            searchField.isCursorVisible = true
            searchField.requestFocus()
            // CRITICAL: keys live BELOW the lifted panel, i.e. OUTSIDE it. With
            // outside-touch dismissal on, the first key tap would close the popup and
            // leak the keystroke to the app. Disable it so the keyboard stays usable
            // and the redirect stays armed; the ✕ / result tap still close the popup.
            popup.isOutsideTouchable = false
            // Lift the panel to sit ON TOP OF the keyboard so the keys show below.
            val kbH = anchor.rootView?.height?.takeIf { it > 0 } ?: (300f * dp).toInt()
            try { popup.update(0, kbH, popupWidth, searchHeight) }
            catch (t: Throwable) { XposedBridge.log("$TAG [KB] search lift failed: ${t.message}") }
        }
        fun closeSearch(clearText: Boolean) {
            clipSearchActive = false
            clipSearchOnEdit = null
            clipSearchIC = null
            popup.isOutsideTouchable = true
            searchRow.visibility = View.GONE
            styleSearchChip(false)
            if (clearText && searchQuery.isNotEmpty()) searchField.setText("")
            // Drop the panel back over the keyboard.
            try { popup.update(0, anchor.height, popupWidth, fullHeight) } catch (_: Throwable) {}
        }

        searchClear.setOnClickListener {
            clipSearchIC?.getEditable()?.clear()
            searchField.setText("")
        }
        searchBtn.setOnClickListener {
            if (searchRow.visibility == View.VISIBLE) closeSearch(clearText = true) else openSearch()
        }
        // Never leave the IC redirected once the popup is gone.
        popup.setOnDismissListener {
            clipSearchActive = false
            clipSearchOnEdit = null
            clipSearchIC = null
            vaultLiveRefresh = null
            pendingScrollRestoreY = -1
        }

        updateTabs()
        reloadList()
        anchor.post { popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.START, 0, anchor.height) }
    }

    // Bold + accent-tint the matched character ranges. Ranges are clamped to the
    // text length so they stay valid even if the row is later truncated for display.
    private fun highlight(text: String, ranges: List<IntRange>): CharSequence {
        val span = SpannableString(text)
        val len = text.length
        for (r in ranges) {
            val start = r.first.coerceIn(0, len)
            val end = (r.last + 1).coerceIn(start, len)
            if (start >= end) continue
            span.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(ForegroundColorSpan(UI.ACCENT), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return span
    }

    /**
     * Decode a row thumbnail off the main thread and drop it in, unless the row was reused
     * for a different entry while we were working.
     *
     * The vault list has no RecyclerView — `reloadList()` rebuilds every row — but rows are
     * still handed to `post {}` callbacks that can outlive them, so the id tag check is the
     * same guard a ViewHolder would need. Cache hits paint synchronously, which keeps a
     * scroll through already-seen images free of flicker.
     */
    private fun loadThumb(entry: ClipboardDatabase.Entry, view: ImageView) {
        val hash = entry.imageHash ?: return
        val path = entry.thumbPath ?: return
        synchronized(thumbCache) { thumbCache[hash] }?.let { view.setImageBitmap(it); return }
        view.setImageBitmap(null)
        imgExecutor.submit {
            try {
                val bmp = BitmapFactory.decodeFile(path) ?: return@submit
                synchronized(thumbCache) { thumbCache[hash] = bmp }
                view.post {
                    // Same entry still in this view? Otherwise the row was rebuilt.
                    if (view.tag == entry.id) view.setImageBitmap(bmp)
                }
            } catch (_: Throwable) {
            } catch (_: OutOfMemoryError) {
            }
        }
    }

    /**
     * Commit the outstanding soft-delete and release any bytes it orphaned.
     * Must run on [dbExecutor] — call [commitPendingDelete] from anywhere else.
     */
    private fun commitPendingDeleteBlocking() {
        try {
            val db = clipboardDb ?: return
            val released = db.commitPendingDelete()
            val store = imageStore
            if (store != null) {
                for (h in released) store.deleteHash(h)
                if (released.isNotEmpty()) {
                    synchronized(thumbCache) { released.forEach { thumbCache.remove(it) } }
                }
            }
            if (released.isNotEmpty()) {
                XposedBridge.log("$TAG [KB] delete committed, ${released.size} image file(s) freed")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] delete commit failed: ${t.message}")
        }
    }

    private fun commitPendingDelete() {
        dbExecutor.submit { commitPendingDeleteBlocking() }
    }

    /** A brief inline message. An IME has no Activity, so Toast styling is done by hand. */
    private fun toastLike(ctx: Context, dp: Float, anchor: View, msg: String) {
        try {
            val tv = TextView(ctx).apply {
                text = msg
                setTextColor(UI.TEXT)
                textSize = 12f
                background = roundRect(UI.ELEVATED, 10f * dp, UI.DIVIDER, (1f * dp).toInt())
                setPadding((12f * dp).toInt(), (8f * dp).toInt(), (12f * dp).toInt(), (8f * dp).toInt())
            }
            val pw = PopupWindow(tv, LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, false).apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                elevation = 20f * dp
            }
            pw.showAsDropDown(anchor)
            anchor.postDelayed({ try { pw.dismiss() } catch (_: Throwable) {} }, 2200L)
        } catch (_: Throwable) {
        }
    }

    private fun buildClipboardRow(
        ctx: Context, dp: Float,
        entry: ClipboardDatabase.Entry,
        db: ClipboardDatabase,
        ims: InputMethodService,
        popup: PopupWindow,
        container: LinearLayout,
        matchRanges: List<IntRange> = emptyList(),
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

        // ── Image rows: a 40dp thumbnail ahead of the existing text column ──
        // Everything else about the row is untouched — same card, same 14dp radius, same
        // padding, same ⋮ on the right — so an image row reads as a text row that happens
        // to carry a picture. addView order below puts it after the badge, and the row is
        // a plain horizontal LinearLayout, so Gravity/START resolution mirrors it in RTL.
        val thumbView: ImageView? = if (entry.isImage) ImageView(ctx).apply {
            val side = (40f * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(side, side).apply {
                marginEnd = (10f * dp).toInt()
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundRect(UI.ELEVATED, 8f * dp)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, 8f * dp)
                }
            }
            // Tagged with the entry id so a decode that finishes after the row was
            // reused for another entry is discarded instead of showing the wrong image.
            tag = entry.id
        } else null

        val textView = TextView(ctx).apply {
            text = when {
                entry.isImage -> ClipboardImagePolicy.rowLabel(
                    entry.imgW, entry.imgH, entry.imgBytes, entry.isAnimated, entry.isScreenshot
                )
                matchRanges.isEmpty() -> entry.text
                else -> highlight(entry.text, matchRanges)
            }
            if (entry.isImage) setTextColor(UI.TEXT_DIM) else setTextColor(UI.TEXT)
            textSize = 14f
            // A1: full text by default; with the toggle off, show a 3-line preview.
            // An image label is always one line, which is what keeps an image row exactly
            // as tall as a single-line text row.
            if (entry.isImage) {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            } else if (cachedClipFullText) {
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
        if (thumbView != null) row.addView(thumbView)
        row.addView(textView)
        row.addView(optBtn)

        if (thumbView != null) loadThumb(entry, thumbView)

        row.setOnClickListener {
            // Disarm the search redirect first so the clip lands in the HOST app.
            clipSearchActive = false
            if (entry.isImage) {
                // Paste-back is I/O (a copy into the host provider's directory), so it
                // must not run on the touch handler's thread.
                imgExecutor.submit {
                    val ok = pasteImage(ctx, ims, entry)
                    row.post { if (ok) popup.dismiss() else toastLike(ctx, dp, row, "Couldn't paste image") }
                }
            } else {
                try { ims.currentInputConnection?.commitText(entry.text, 1); popup.dismiss() } catch (_: Throwable) {}
            }
        }

        optBtn.setOnClickListener {
            showEntryOptions(ctx, dp, entry, db, optBtn, reload)
        }

        container.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, (3f * dp).toInt(), 0, (3f * dp).toInt()) })
    }

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
                    val clip = clipMgr.primaryClip
                    val item = clip?.getItemAt(0)
                    val desc = clip?.description
                    val imageUri = item?.uri?.takeIf { desc != null && descHasImage(desc) }

                    if (imageUri != null) {
                        // A mixed clip (text + image) saves BOTH, as two entries. Using
                        // item.text rather than coerceToText matters here: coerceToText on
                        // an image item yields the URI string, which would put a
                        // "content://..." row in the text vault.
                        val itemText = item.text?.toString()
                        if (!itemText.isNullOrEmpty()) {
                            dbExecutor.submit { clipboardDb?.insert(itemText) }
                        }
                        if (cachedClipImages &&
                            android.os.SystemClock.elapsedRealtime() >= selfClipUntilMs
                        ) {
                            // The URI grant dies when the source app releases the clip, so
                            // this is queued immediately — but never on the host's main
                            // thread, where a 25 MB read would ANR Gboard.
                            imgExecutor.submit { captureImage(ctx, imageUri) }
                        }
                        return@addPrimaryClipChangedListener
                    }

                    val text = item?.coerceToText(ctx)?.toString()
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

    private fun descHasImage(desc: android.content.ClipDescription): Boolean = try {
        (0 until desc.mimeTypeCount).any { desc.getMimeType(it)?.startsWith("image/") == true }
    } catch (_: Throwable) {
        false
    }

    /**
     * Copy the bytes, enforce the budgets, insert the row. Runs on [imgExecutor].
     *
     * Every failure is silent by design: an unreadable URI (the grant was already revoked),
     * undecodable bytes, an oversize source or a full disk all end with no entry and no
     * orphan file, and the text vault is completely untouched.
     */
    private fun captureImage(ctx: Context, uri: Uri) {
        ingestImage(ctx, uri, ClipboardDatabase.SRC_CLIPBOARD, System.currentTimeMillis(), true)
    }

    // ─────────────────────────────────────────────────────
    // System screenshot auto-capture
    // ─────────────────────────────────────────────────────

    @Volatile private var clipboardListenerRegistered = false

    /**
     * Open the vault once per process and bring the screenshot watcher up with it.
     *
     * Called from both the input-view hook and the toolbar injection, because those fire in
     * either order depending on the editor Gboard was raised over.
     */
    private fun ensureVault(ctx: Context) {
        if (clipboardDb == null) {
            clipboardDb = ClipboardDatabase(ctx, cachedMaxEntries)
            imageStore = ClipboardImageStore(ctx)
            repairImageStore()
        }
        // The watcher needs the vault for its watermark, so it can only attach once the
        // vault exists; refreshPrefs would otherwise not retry for another 2 s.
        reconcileShotWatcher(ctx)
    }

    /**
     * Structured logging on a tag of its own, so `adb logcat -s AutoExpandShot:D` shows the
     * whole feature and nothing else. [XposedBridge.log] is not used here: it lands under
     * the LSPosed framework tag, which cannot be filtered this way.
     *
     * Lifecycle lines (attach / no route / skip / save) are always emitted — there are only
     * a handful per screenshot, and they are the evidence trail. Per-event chatter is behind
     * the `shot_log_verbose` pref and stays off.
     */
    private fun shotLog(msg: String) {
        try { android.util.Log.d(ClipboardScreenshotWatcher.TAG, msg) } catch (_: Throwable) {}
    }

    /**
     * Bring the watcher in line with the toggle. Called from [refreshPrefs], so flipping the
     * setting takes effect within the 2 s pref-cache window with no keyboard restart.
     *
     * Turning the feature off tears every route down: with it off, not one line of the
     * capture path runs and no observer is registered against MediaStore.
     */
    private fun reconcileShotWatcher(ctx: Context) {
        try {
            if (!cachedShotCapture) {
                shotWatcher?.let { it.stop(); shotWatcher = null }
                return
            }
            // The vault has to exist first — the watermark lives in it.
            val db = clipboardDb ?: return

            var w = shotWatcher
            if (w == null) {
                w = ClipboardScreenshotWatcher(ctx, shotSink(ctx))
                w.bindWatermark({ db.screenshotWatermark() }, { db.setScreenshotWatermark(it) })
                w.verbose = cachedShotVerbose
                if (!w.start()) return          // start() already logged why
                shotWatcher = w
                w.catchUp()                     // anything taken while we were not running
            } else {
                w.verbose = cachedShotVerbose
            }
        } catch (t: Throwable) {
            shotLog("watcher reconcile failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun shotSink(ctx: Context) = object : ClipboardScreenshotWatcher.Sink {
        override fun screenshotCaptureEnabled() = cachedShotCapture

        override fun onScreenshot(uri: Uri, identity: String, capturedAtMs: Long) {
            // Onto the image executor, never the watcher's thread: WebP encoding is slow and
            // the watcher thread has to stay free to notice the next screenshot.
            imgExecutor.submit {
                if (!cachedShotCapture) return@submit
                val id = ingestImage(
                    ctx, uri,
                    ClipboardDatabase.SRC_SCREENSHOT,
                    capturedAtMs,
                    // Two screenshots of a motionless screen are byte-identical but are
                    // genuinely two screenshots; the watcher's file-identity guard has
                    // already ruled out a true duplicate.
                    skipIfSameAsLast = false
                )
                if (id > 0L) vaultLiveRefresh?.let { refresh -> try { refresh() } catch (_: Throwable) {} }
            }
        }
    }

    /**
     * Optional second half: put the screenshot on the real system clipboard too.
     *
     * Overwriting the primary clip is destructive, so before it happens the clip that is
     * about to be lost is saved into the vault — the task's condition for this toggle. That
     * normally already happened via the clipboard listener, and [ClipboardDatabase.insert]
     * collapses the repeat; this covers the case where the clip was set while Gboard's
     * process was not running.
     */
    private fun pushScreenshotToClipboard(ctx: Context, full: java.io.File) {
        try {
            if (!full.isFile) return
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: run {
                shotLog("clipboard copy skipped: no ClipboardManager")
                return
            }

            // 1. Rescue whatever is on the clipboard right now.
            try {
                val existing = cm.primaryClip?.getItemAt(0)?.text?.toString()
                if (!existing.isNullOrEmpty()) clipboardDb?.insert(existing)
            } catch (t: Throwable) {
                shotLog("clipboard copy aborted: could not preserve the current clip (${t.message})")
                return
            }

            // 2. Only then overwrite it.
            val exposed = exposeViaHostProvider(ctx, full) ?: run {
                shotLog("clipboard copy skipped: no host FileProvider root")
                return
            }
            // Arm the self-clip guard BEFORE the write, or our own listener re-captures it.
            selfClipUntilMs = android.os.SystemClock.elapsedRealtime() + 2500L
            cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, "screenshot", exposed.first))
            shotLog("copied to system clipboard as ${exposed.first}")
        } catch (t: Throwable) {
            shotLog("clipboard copy failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * The one ingest path. Both the clipboard listener and the screenshot watcher come
     * through here, so budgets, eviction, refcounting and self-healing are defined exactly
     * once — the task's "no second storage path" requirement is structural, not a promise.
     *
     * @return the new row id, or -1 if nothing was inserted.
     */
    private fun ingestImage(
        ctx: Context,
        uri: Uri,
        source: Int,
        timestampMs: Long,
        skipIfSameAsLast: Boolean
    ): Long {
        val store = imageStore ?: return -1L
        val db = clipboardDb ?: return -1L
        val isShot = source == ClipboardDatabase.SRC_SCREENSHOT
        try {
            val saved = store.saveFromUri(ctx, uri) ?: run {
                XposedBridge.log("$TAG [KB] image capture skipped (unreadable/oversize/undecodable)")
                if (isShot) shotLog("skipped $uri: unreadable, oversize or undecodable")
                return -1L
            }

            val existing = db.imageEntriesOldestFirst().map {
                ClipboardImagePolicy.Candidate(it.id, it.imageHash, it.imgBytes, it.isPinned, it.timestamp)
            }
            val plan = ClipboardImagePolicy.planEviction(
                existing, saved.hash, saved.bytes, cachedImgMaxEntries, cachedImgMaxBytes
            )

            if (plan.blocked) {
                // Only pinned entries remain and the budget is still exceeded. Refuse the
                // save rather than evict a pin, and drop the bytes we just wrote if nothing
                // else references them.
                imgBudgetHint = "Vault full — unpin an image to save new ones"
                if (db.hashRefCount(saved.hash) == 0) store.deleteHash(saved.hash)
                XposedBridge.log("$TAG [KB] image refused: budget met only by pinned entries")
                if (isShot) shotLog("skipped: budget met only by pinned entries")
                return -1L
            }

            for (id in plan.evict) {
                db.delete(id)?.let { store.deleteHash(it) }
            }

            val rowId = db.insertImage(
                saved.hash, saved.thumbPath, saved.fullPath,
                saved.width, saved.height, saved.bytes, saved.animated,
                source, timestampMs, skipIfSameAsLast
            )
            if (rowId <= 0L && db.hashRefCount(saved.hash) == 0) {
                // Rejected as a duplicate of the most recent entry and nothing else points
                // at these bytes — don't leave them behind.
                store.deleteHash(saved.hash)
            }
            imgBudgetHint = null
            XposedBridge.log(
                "$TAG [KB] image saved ${saved.width}x${saved.height} " +
                    "${saved.bytes}B evicted=${plan.evict.size} hash=${saved.hash.take(8)}"
            )
            if (isShot) {
                shotLog(
                    "saved as entry id=$rowId ${saved.width}x${saved.height} " +
                        "${saved.bytes}B evicted=${plan.evict.size} hash=${saved.hash.take(8)}"
                )
                if (rowId > 0L && cachedShotToClipboard) {
                    pushScreenshotToClipboard(ctx, store.fullFile(saved.hash))
                }
            }
            return rowId
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] image capture failed: ${t.message}")
            if (isShot) shotLog("ingest failed: ${t.javaClass.simpleName}: ${t.message}")
            return -1L
        }
    }

    /**
     * Once per process: commit a delete whose countdown died with the previous process,
     * then run the cheap self-healing GC pass in both directions — orphan files with no
     * row, and rows whose file has vanished.
     *
     * The spec puts this on settings-app launch; it lives here instead because the store
     * sits in Gboard's private data dir and the module's own process cannot reach it.
     */
    private fun repairImageStore() {
        if (imageStoreRepaired) return
        imageStoreRepaired = true
        dbExecutor.submit {
            try {
                val db = clipboardDb ?: return@submit
                val store = imageStore ?: return@submit

                // A pending batch is COMMITTED, never resurrected: the user asked for the
                // delete and the undo affordance that could have taken it back is gone.
                val released = db.resolveStalePending()
                released.forEach { store.deleteHash(it) }

                val gc = store.gc(db.allReferencedHashes())

                var droppedRows = 0
                for (e in db.imageEntriesOldestFirst()) {
                    val h = e.imageHash
                    if (h == null || !store.hasBytes(h)) {
                        db.delete(e.id); droppedRows++
                    }
                }
                if (released.isNotEmpty() || gc.orphanFilesDeleted > 0 ||
                    gc.tempFilesDeleted > 0 || droppedRows > 0
                ) {
                    XposedBridge.log(
                        "$TAG [KB] image store repaired: committed=${released.size} " +
                            "orphans=${gc.orphanFilesDeleted} temps=${gc.tempFilesDeleted} " +
                            "droppedRows=$droppedRows"
                    )
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [KB] image store repair failed: ${t.message}")
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // Image paste-back
    // ─────────────────────────────────────────────────────

    /**
     * Hand a stored image to the host editor.
     *
     * The module's own FileProvider is useless here: a URI grant can only be issued by the
     * process that owns the provider, and we are running inside Gboard. So we borrow
     * **Gboard's own** FileProvider — the one it already uses to send GIFs and stickers —
     * by discovering its authority and declared roots at runtime, copying the image under
     * one of those roots, and building the URI against that authority.
     *
     * `commitContent` is the good path (it inserts inline). Editors that don't accept the
     * MIME type fall back to the system clipboard, so the user can long-press → Paste.
     *
     * See [icUnwrap]: the framework identifies the grant's caller by reference, so our
     * InputConnection wrapper has to step aside for the duration of the call or the grant
     * is silently skipped and the receiving app gets a URI it cannot open.
     */
    private fun pasteImage(ctx: Context, ims: InputMethodService, entry: ClipboardDatabase.Entry): Boolean {
        val path = entry.fullPath ?: return false
        val src = File(path)
        if (!src.isFile) return false
        try {
            val exposed = exposeViaHostProvider(ctx, src) ?: run {
                XposedBridge.log("$TAG [KB] no host FileProvider root found for paste")
                return false
            }
            val (uri, _) = exposed
            val mime = "image/webp"

            val editorInfo = ims.currentInputEditorInfo
            val accepts = editorInfo?.contentMimeTypes?.any {
                it == "image/*" || it == mime || it == "*/*"
            } == true

            if (accepts) {
                val info = InputContentInfo(uri, android.content.ClipDescription("image", arrayOf(mime)))
                val ic = ims.currentInputConnection
                if (ic != null) {
                    val prev = icUnwrap.get() == true
                    icUnwrap.set(true)
                    try {
                        val flags = InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
                        if (ic.commitContent(info, flags, null)) {
                            XposedBridge.log("$TAG [KB] image committed via commitContent")
                            return true
                        }
                    } finally {
                        icUnwrap.set(prev)
                    }
                }
            }

            // Fallback: put it on the clipboard and tell the user to paste.
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm != null) {
                // Arm the guard BEFORE the write, or the listener races us and re-captures.
                selfClipUntilMs = android.os.SystemClock.elapsedRealtime() + 2500L
                cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, "image", uri))
                XposedBridge.log("$TAG [KB] image placed on clipboard (editor rejects $mime)")
                return true
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] image paste failed: ${t.message}")
        }
        return false
    }

    /** Cached so the provider XML is parsed once per process, not once per paste. */
    @Volatile private var hostProviderRoot: Pair<String, File>? = null

    /**
     * Find a FileProvider in the **host** package and a directory it actually serves, then
     * copy [src] there and build a grantable URI.
     *
     * Discovery is by metadata rather than a hardcoded authority so this keeps working
     * across Gboard versions (and on any other IME the module is ever scoped to).
     */
    private fun exposeViaHostProvider(ctx: Context, src: File): Pair<Uri, String>? {
        val (authority, dir) = hostProviderRoot ?: findHostProviderRoot(ctx)?.also {
            hostProviderRoot = it
        } ?: return null
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val dest = File(dir, "ae_clip_${src.nameWithoutExtension}.webp")
            if (!dest.isFile || dest.length() != src.length()) {
                src.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, authority, dest)
            uri to authority
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] host provider expose failed: ${t.message}")
            null
        }
    }

    private fun findHostProviderRoot(ctx: Context): Pair<String, File>? {
        try {
            val pm = ctx.packageManager
            val info = pm.getPackageInfo(
                ctx.packageName,
                PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA
            )
            for (p in info.providers ?: emptyArray()) {
                val authority = p.authority ?: continue
                val resId = p.metaData?.getInt("android.support.FILE_PROVIDER_PATHS", 0) ?: 0
                if (resId == 0) continue
                val dir = firstServedDir(ctx, resId) ?: continue
                XposedBridge.log("$TAG [KB] host FileProvider: $authority -> $dir")
                return authority to dir
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] host provider discovery failed: ${t.message}")
        }
        return null
    }

    /** Parse a `file_paths` XML and return the first root we can write into. */
    private fun firstServedDir(ctx: Context, resId: Int): File? {
        try {
            val xml = ctx.resources.getXml(resId)
            var event = xml.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    val path = xml.getAttributeValue(
                        "http://schemas.android.com/apk/res/android", "path"
                    ) ?: xml.getAttributeValue(null, "path")
                    val base: File? = when (xml.name) {
                        "files-path" -> ctx.filesDir
                        "cache-path" -> ctx.cacheDir
                        "external-files-path" -> ctx.getExternalFilesDir(null)
                        "external-cache-path" -> ctx.externalCacheDir
                        else -> null    // root-path/external-path are too broad to borrow
                    }
                    if (base != null) {
                        val dir = if (path.isNullOrBlank()) base else File(base, path)
                        val target = File(dir, "aeclip")
                        if (target.isDirectory || target.mkdirs()) return target
                    }
                }
                event = xml.next()
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [KB] file_paths parse failed: ${t.message}")
        }
        return null
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
