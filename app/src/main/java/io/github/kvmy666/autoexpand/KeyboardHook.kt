package io.github.kvmy666.autoexpand

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
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
    private val PREFS_FILE = "/data/local/tmp/jeez_prefs.json"
    @Volatile private var filePrefCache: Map<String, String>? = null
    private val dbExecutor = Executors.newSingleThreadExecutor()

    // Cached prefs (refreshed every 2s)
    @Volatile private var cachedEnabled = true
    @Volatile private var cachedMultiplier = 1.0f
    @Volatile private var cachedShortcut1 = ""
    @Volatile private var cachedShortcut2 = ""
    @Volatile private var cachedMaxEntries = 500
    @Volatile private var cachedBtnClipboard = true
    @Volatile private var cachedBtnPaste     = true
    @Volatile private var cachedBtnSelectAll = true
    @Volatile private var cachedBtnCursor = true
    @Volatile private var cachedBtnShortcut = true
    @Volatile private var lastCacheTime = 0L
    private val CACHE_INTERVAL_MS = 2000L

    private var clipboardDb: ClipboardDatabase? = null
    private var activeImsRef: InputMethodService? = null
    @Volatile private var clipSortMode = ClipboardDatabase.SortMode.NEWEST

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
            cachedEnabled      = cache["keyboard_enhancer_enabled"] == "1"
            cachedMultiplier   = cache["toolbar_height_multiplier"]?.toFloatOrNull() ?: 1.0f
            cachedShortcut1    = cache["shortcut_text_1"] ?: cachedShortcut1
            cachedShortcut2    = cache["shortcut_text_2"] ?: cachedShortcut2
            cachedMaxEntries   = cache["clipboard_max_entries"]?.toIntOrNull() ?: cachedMaxEntries
            cachedBtnClipboard = cache["btn_clipboard_enabled"] == "1"
            cachedBtnPaste     = cache["btn_paste_enabled"] == "1"
            cachedBtnSelectAll = cache["btn_selectall_enabled"] == "1"
            cachedBtnCursor    = cache["btn_cursor_enabled"] == "1"
            cachedBtnShortcut  = cache["btn_shortcut_enabled"] == "1"
        }
    }

    // ─────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != GBOARD_PKG) return

        XposedBridge.log("$TAG KeyboardHook loaded in $GBOARD_PKG")

        // ── Phase 1: Reconnaissance logging ──────────────
        hookLifecycleForLogs(lpparam)

        // ── Phase 2+3: Toolbar injection via setInputView ─
        hookSetInputView(lpparam)
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

                            contentLayout.addView(toolbar, LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, initialHeight
                            ))

                            contentLayout.post {
                                val referenceHeight = findEnterKeyHeight(kbView)

                                // Toolbar = 50% of one key row × user multiplier (so 1.0 = half a key row ≈ 25dp)
                                val toolbarHeight = when {
                                    referenceHeight > 0 ->
                                        (referenceHeight * 0.5f * cachedMultiplier).toInt()
                                            .coerceAtLeast((36f * dp).toInt())
                                    else -> initialHeight
                                }
                                toolbar.layoutParams = toolbar.layoutParams.also { it.height = toolbarHeight }

                                // Emoji fills ~60% of toolbar height
                                val emojiSp = (toolbarHeight / dp * 0.6f).coerceIn(13f, 22f)
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
        val btnSize = (44f * dp).toInt()

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
                                ic.performContextMenuAction(android.R.id.selectAll)
                            } catch (_: Throwable) {}
                        }, longPressTimeout)
                    }
                    MotionEvent.ACTION_UP -> {
                        selHandler.removeCallbacksAndMessages(null)
                        if (!longPressFired) {
                            btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            try {
                                val ic = ims.currentInputConnection ?: return@setOnTouchListener true
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
                                    ic.performContextMenuAction(android.R.id.startSelectingText)
                                    ic.setSelection(selStart, selEnd)
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
    // Clipboard popup
    // ─────────────────────────────────────────────────────

    private fun showClipboardPopup(ctx: Context, ims: InputMethodService, anchor: View) {
        val db = clipboardDb ?: return
        val dp = ctx.resources.displayMetrics.density
        val popupWidth = anchor.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels

        val outerContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // ── Tab strip: All | ❤️ Favorites ──
        var showFavoritesOnly = false
        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
        }
        val tabAll = TextView(ctx).apply {
            text = "All"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding((16f * dp).toInt(), (8f * dp).toInt(), (16f * dp).toInt(), (8f * dp).toInt())
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
        }
        val tabFav = TextView(ctx).apply {
            text = "❤️ Favorites"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding((16f * dp).toInt(), (8f * dp).toInt(), (16f * dp).toInt(), (8f * dp).toInt())
            setTextColor(Color.parseColor("#AAFFFFFF"))
            setBackgroundColor(Color.TRANSPARENT)
        }
        val closeBtn = TextView(ctx).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#AAFFFFFF"))
            setPadding((16f * dp).toInt(), (8f * dp).toInt(), (16f * dp).toInt(), (8f * dp).toInt())
        }
        tabRow.addView(tabAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabRow.addView(tabFav, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabRow.addView(closeBtn)
        outerContainer.addView(tabRow)

        // ── Sort button row ──
        val sortLabels = listOf("Newest ↓", "Oldest ↑", "📌 First", "❤️ First")
        val sortModes = listOf(
            ClipboardDatabase.SortMode.NEWEST,
            ClipboardDatabase.SortMode.OLDEST,
            ClipboardDatabase.SortMode.PINNED_FIRST,
            ClipboardDatabase.SortMode.FAVORITES_FIRST
        )
        val sortRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12f * dp).toInt(), (4f * dp).toInt(), (12f * dp).toInt(), (4f * dp).toInt())
        }
        val sortBtn = TextView(ctx).apply {
            text = sortLabels[sortModes.indexOf(clipSortMode).coerceAtLeast(0)]
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding((8f * dp).toInt(), (4f * dp).toInt(), (8f * dp).toInt(), (4f * dp).toInt())
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }
        sortRow.addView(sortBtn)
        outerContainer.addView(sortRow)

        // ── Scrollable list ──
        val scrollView = ScrollView(ctx)
        val listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(listContainer)
        outerContainer.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val popup = PopupWindow(outerContainer, popupWidth, (320f * dp).toInt(), true).apply {
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#EE1E1E2E")))
            isOutsideTouchable = true
            elevation = 8f * dp
        }

        fun updateTabs() {
            tabAll.setBackgroundColor(if (!showFavoritesOnly) Color.parseColor("#44FFFFFF") else Color.TRANSPARENT)
            tabAll.setTextColor(if (!showFavoritesOnly) Color.WHITE else Color.parseColor("#AAFFFFFF"))
            tabFav.setBackgroundColor(if (showFavoritesOnly) Color.parseColor("#44FFFFFF") else Color.TRANSPARENT)
            tabFav.setTextColor(if (showFavoritesOnly) Color.WHITE else Color.parseColor("#AAFFFFFF"))
        }

        fun reloadList() {
            dbExecutor.submit {
                val entries = db.getAll(clipSortMode, showFavoritesOnly)
                listContainer.post {
                    listContainer.removeAllViews()
                    if (entries.isEmpty()) {
                        listContainer.addView(TextView(ctx).apply {
                            text = if (showFavoritesOnly) "No favorites yet — long-press an entry and tap ❤️" else "No clipboard history"
                            setTextColor(Color.parseColor("#88FFFFFF"))
                            textSize = 14f
                            gravity = Gravity.CENTER
                            setPadding((16f * dp).toInt(), (32f * dp).toInt(), (16f * dp).toInt(), (32f * dp).toInt())
                        })
                    } else {
                        val displayLimit = 50
                        entries.take(displayLimit).forEach { entry ->
                            buildClipboardRow(ctx, dp, entry, db, ims, popup, listContainer) { reloadList() }
                        }
                        if (entries.size > displayLimit) {
                            listContainer.addView(TextView(ctx).apply {
                                text = "…and ${entries.size - displayLimit} more items"
                                setTextColor(Color.parseColor("#88FFFFFF"))
                                textSize = 13f
                                gravity = Gravity.CENTER
                                setPadding(0, (12f * dp).toInt(), 0, (12f * dp).toInt())
                            })
                        }
                    }
                }
            }
        }

        closeBtn.setOnClickListener { popup.dismiss() }
        tabAll.setOnClickListener {
            if (showFavoritesOnly) { showFavoritesOnly = false; updateTabs(); reloadList() }
        }
        tabFav.setOnClickListener {
            if (!showFavoritesOnly) { showFavoritesOnly = true; updateTabs(); reloadList() }
        }
        sortBtn.setOnClickListener {
            val idx = (sortModes.indexOf(clipSortMode) + 1) % sortModes.size
            clipSortMode = sortModes[idx]
            sortBtn.text = sortLabels[idx]
            reloadList()
        }

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
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12f * dp).toInt(), (10f * dp).toInt(), (8f * dp).toInt(), (10f * dp).toInt())
        }

        val badge = TextView(ctx).apply {
            text = when {
                entry.isPinned -> "📌"
                entry.isFavorite -> "★"
                else -> ""
            }
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, if (entry.isPinned || entry.isFavorite) (4f * dp).toInt() else 0, 0)
        }

        val textView = TextView(ctx).apply {
            text = entry.text
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val optBtn = TextView(ctx).apply {
            text = "⚙️"
            textSize = 16f
            setPadding((8f * dp).toInt(), 0, (8f * dp).toInt(), 0)
            gravity = Gravity.CENTER_VERTICAL
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

        container.addView(row)
        container.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1f * dp).toInt())
        })
    }

    // Uses PopupWindow instead of AlertDialog — AlertDialog requires Activity context, IME has none
    private fun showEntryOptions(
        ctx: Context, dp: Float,
        entry: ClipboardDatabase.Entry,
        db: ClipboardDatabase,
        anchor: View,
        reload: () -> Unit
    ) {
        val optContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF2A2A3E"))
            setPadding((4f * dp).toInt(), (4f * dp).toInt(), (4f * dp).toInt(), (4f * dp).toInt())
        }

        val optPopup = PopupWindow(
            optContainer,
            (160f * dp).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#FF2A2A3E")))
            isOutsideTouchable = true
            elevation = 16f * dp
        }

        fun optRow(label: String, action: () -> Unit): TextView = TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding((12f * dp).toInt(), (10f * dp).toInt(), (12f * dp).toInt(), (10f * dp).toInt())
            setOnClickListener { optPopup.dismiss(); action() }
        }

        optContainer.addView(optRow(if (entry.isPinned) "Unpin" else "📌 Pin") {
            dbExecutor.submit { db.togglePin(entry.id); anchor.post { reload() } }
        })
        optContainer.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1f * dp).toInt())
        })
        optContainer.addView(optRow(if (entry.isFavorite) "Unfavorite" else "★ Favorite") {
            dbExecutor.submit { db.toggleFavorite(entry.id); anchor.post { reload() } }
        })
        optContainer.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1f * dp).toInt())
        })
        optContainer.addView(optRow("🗑 Delete") {
            dbExecutor.submit { db.delete(entry.id); anchor.post { reload() } }
        })

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
