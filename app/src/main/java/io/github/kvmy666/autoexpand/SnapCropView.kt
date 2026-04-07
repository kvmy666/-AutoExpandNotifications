package io.github.kvmy666.autoexpand

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Full-screen crop view for Screen Snapper.
 *
 * Draws a dark dim overlay over the live screen. The user draws a rectangle
 * by dragging; the dim is "punched out" inside the selection, showing the
 * live screen clearly. Corner handles and edge strips allow resize without
 * constraints — dragging a corner or edge past its opposite side flips the
 * rectangle naturally. The actual screencap is deferred until user action.
 *
 * Two permanent bottom buttons are always visible:
 *  • Full Screenshot (left)  — pins the entire capture as a floating snap
 *  • Cancel (right, ×)       — dismisses the Snapper
 *
 * When the window loses focus (home gesture) or the back key is pressed,
 * [onCancelRequested] is invoked to let the service clean up.
 */
class SnapCropView(context: Context) : View(context) {

    // ── Callbacks ────────────────────────────────────────────────────────────────

    /** Called on ACTION_UP with a valid (non-trivial) selection. */
    var onSelectionComplete: ((RectF) -> Unit)? = null
    /** Called on ACTION_DOWN when a selection already exists (user is adjusting). */
    var onAdjustStarted: (() -> Unit)? = null
    /** Called when home/back gesture is detected — service should dismiss all. */
    var onCancelRequested: (() -> Unit)? = null
    /** Called when user double-taps inside the selection — shortcut to pin/float. */
    var onDoubleTapPin: (() -> Unit)? = null
    /** Called when the "Full Screenshot" bottom button is tapped. */
    var onFullScreenPin: (() -> Unit)? = null

    // ── Public helpers ────────────────────────────────────────────────────────────

    /** Returns a copy of the current selection rect in view px, or null if none. */
    fun getSelRect(): RectF? = if (hasSel) RectF(selRect) else null

    // ── Selection state ───────────────────────────────────────────────────────────

    private val selRect = RectF()
    private var hasSel     = false
    private var isDragging = false

    /**
     * Anchor point for DRAWING and corner-drag modes.
     * For DRAWING: the first touch point.
     * For CORNER_*: the opposite corner (stays fixed while the finger moves).
     */
    private var drawOriginX = 0f
    private var drawOriginY = 0f

    // ── Touch state ───────────────────────────────────────────────────────────────

    private var dragMode        = DragMode.NONE
    private var lastX           = 0f
    private var lastY           = 0f
    private var doubleTapPending = false
    private var tapOnButton     = false

    // ── Dimensions ────────────────────────────────────────────────────────────────

    private val density   = resources.displayMetrics.density
    private val handleLen = dp(20f)
    private val handleW   = dp(5f)
    private val borderW   = dp(1.5f)
    private val cornerHit = dp(30f)
    private val edgeHit   = dp(14f)
    private val minSize   = dp(40f)   // only enforced on initial draw release

    // ── Bottom buttons ────────────────────────────────────────────────────────────

    private val btnFullRect  = RectF()
    private val btnCloseRect = RectF()

    private val btnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 30, 30, 30)
    }
    private val btnIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap  = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // ── Paints ────────────────────────────────────────────────────────────────────

    private val bitmapDst = RectF()

    private val dimPaint = Paint().apply {
        color = Color.BLACK; alpha = 120; isAntiAlias = false
    }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); isAntiAlias = false
    }
    private val borderPaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = borderW; isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = handleW; strokeCap = Paint.Cap.SQUARE; isAntiAlias = true
    }
    /** Rule-of-thirds grid lines — subtle white at ~22% opacity. */
    private val gridPaint = Paint().apply {
        color = Color.argb(55, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
        isAntiAlias = false
    }

    init {
        // Hardware layer needed so saveLayer + PorterDuff.CLEAR composites correctly.
        setLayerType(LAYER_TYPE_HARDWARE, null)
        // Focusable so we receive key events (back) and window-focus callbacks (home).
        isFocusable         = true
        isFocusableInTouchMode = true
    }

    // ── Gesture detector — double-tap to pin ──────────────────────────────────────

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (hasSel && selRect.contains(e.x, e.y) && onDoubleTapPin != null) {
                    doubleTapPending = true
                    return true
                }
                return false
            }
        }
    )

    // ── Layout ────────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bitmapDst.set(0f, 0f, w.toFloat(), h.toFloat())
        Log.d("JeezSnapper", "CropView: view=${w}×${h}")

        // Two 52dp buttons, 16dp gap, 16dp from screen bottom, horizontally centred
        val btnSize = dp(52f)
        val gap     = dp(16f)
        val totalW  = btnSize * 2 + gap
        val startX  = (w - totalW) / 2f
        val btnY    = h - dp(16f) - btnSize
        btnFullRect.set(startX,                btnY, startX + btnSize,  btnY + btnSize)
        btnCloseRect.set(startX + btnSize + gap, btnY, startX + totalW, btnY + btnSize)

        // Exclude the entire crop view from system edge-gesture detection so
        // touches at the screen edges route to us instead of triggering Back.
        systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, w, h))
    }

    // ── Drawing ───────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        // 1. Dim with selection punched out (live screen shows through transparent background)
        val sc = canvas.saveLayer(bitmapDst, null)
        canvas.drawRect(bitmapDst, dimPaint)
        if (hasSel) canvas.drawRect(selRect, clearPaint)
        canvas.restoreToCount(sc)

        // 3. Selection border + rule-of-thirds grid + handles
        if (hasSel) {
            canvas.drawRect(selRect, borderPaint)
            drawGrid(canvas)
            drawHandles(canvas)
        }

        // 4. Permanent bottom buttons (always visible)
        drawBottomButtons(canvas)
    }

    /**
     * Draws the eight L-shaped corner handles.
     * The arm length is clamped to half the rect's shortest dimension so handles never
     * visually overlap or cross each other when the selection is very thin. (Task 4)
     */
    private fun drawHandles(canvas: Canvas) {
        val r   = selRect
        // Clamp so handle arms never exceed half the rect size — prevents |-----| glitch
        val len = handleLen.coerceAtMost(min(r.width() / 2f, r.height() / 2f))
        canvas.drawLine(r.left,         r.top + len,    r.left,         r.top,          handlePaint)
        canvas.drawLine(r.left,         r.top,          r.left + len,   r.top,          handlePaint)
        canvas.drawLine(r.right - len,  r.top,          r.right,        r.top,          handlePaint)
        canvas.drawLine(r.right,        r.top,          r.right,        r.top + len,    handlePaint)
        canvas.drawLine(r.left,         r.bottom - len, r.left,         r.bottom,       handlePaint)
        canvas.drawLine(r.left,         r.bottom,       r.left + len,   r.bottom,       handlePaint)
        canvas.drawLine(r.right - len,  r.bottom,       r.right,        r.bottom,       handlePaint)
        canvas.drawLine(r.right,        r.bottom,       r.right,        r.bottom - len, handlePaint)
    }

    /** Rule-of-thirds grid: two vertical + two horizontal lines dividing selRect into 9 zones. */
    private fun drawGrid(canvas: Canvas) {
        val r  = selRect
        val w3 = r.width()  / 3f
        val h3 = r.height() / 3f
        canvas.drawLine(r.left + w3,     r.top,    r.left + w3,     r.bottom, gridPaint)
        canvas.drawLine(r.left + 2*w3,   r.top,    r.left + 2*w3,   r.bottom, gridPaint)
        canvas.drawLine(r.left, r.top + h3,    r.right, r.top + h3,    gridPaint)
        canvas.drawLine(r.left, r.top + 2*h3,  r.right, r.top + 2*h3,  gridPaint)
    }

    private fun drawBottomButtons(canvas: Canvas) {
        val cornerR = dp(10f)
        btnIconPaint.strokeWidth = dp(2.5f)

        // Full-screenshot button (left) — corner-bracket icon
        canvas.drawRoundRect(btnFullRect, cornerR, cornerR, btnBgPaint)
        drawFullIcon(canvas, btnFullRect.centerX(), btnFullRect.centerY())

        // Cancel button (right) — × icon
        canvas.drawRoundRect(btnCloseRect, cornerR, cornerR, btnBgPaint)
        val cx = btnCloseRect.centerX(); val cy = btnCloseRect.centerY(); val arm = dp(8f)
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, btnIconPaint)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, btnIconPaint)
    }

    /** Four L-shaped corner brackets indicating "full frame". */
    private fun drawFullIcon(canvas: Canvas, cx: Float, cy: Float) {
        val s   = dp(10f)
        val arm = dp(5f)
        val p   = btnIconPaint
        canvas.drawLine(cx - s,       cy - s + arm, cx - s,       cy - s,       p)
        canvas.drawLine(cx - s,       cy - s,       cx - s + arm, cy - s,       p)
        canvas.drawLine(cx + s - arm, cy - s,       cx + s,       cy - s,       p)
        canvas.drawLine(cx + s,       cy - s,       cx + s,       cy - s + arm, p)
        canvas.drawLine(cx - s,       cy + s - arm, cx - s,       cy + s,       p)
        canvas.drawLine(cx - s,       cy + s,       cx - s + arm, cy + s,       p)
        canvas.drawLine(cx + s - arm, cy + s,       cx + s,       cy + s,       p)
        canvas.drawLine(cx + s,       cy + s,       cx + s,       cy + s - arm, p)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)   // detects double-tap → sets doubleTapPending
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                if (btnFullRect.contains(x, y) || btnCloseRect.contains(x, y)) {
                    tapOnButton = true
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                } else {
                    tapOnButton = false
                    if (!doubleTapPending) onDown(x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tapOnButton && !doubleTapPending) onMove(x, y)
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                when {
                    tapOnButton -> {
                        tapOnButton = false
                        if (btnFullRect.contains(x, y))       onFullScreenPin?.invoke()
                        else if (btnCloseRect.contains(x, y)) onCancelRequested?.invoke()
                    }
                    doubleTapPending -> {
                        doubleTapPending = false
                        onDoubleTapPin?.invoke()
                    }
                    else -> onUp(x, y)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging       = false
                tapOnButton      = false
                doubleTapPending = false
                dragMode         = DragMode.NONE
            }
        }
        return true
    }

    private fun onDown(x: Float, y: Float) {
        if (hasSel) onAdjustStarted?.invoke()
        dragMode = if (!hasSel) {
            startDrawing(x, y)
            DragMode.DRAWING
        } else {
            hitTest(x, y)
        }
        // For corner drags: record the OPPOSITE corner as anchor so we get iOS-like flip behaviour
        when (dragMode) {
            DragMode.CORNER_TL -> { drawOriginX = selRect.right;  drawOriginY = selRect.bottom }
            DragMode.CORNER_TR -> { drawOriginX = selRect.left;   drawOriginY = selRect.bottom }
            DragMode.CORNER_BL -> { drawOriginX = selRect.right;  drawOriginY = selRect.top    }
            DragMode.CORNER_BR -> { drawOriginX = selRect.left;   drawOriginY = selRect.top    }
            else -> {}
        }
        lastX = x; lastY = y
    }

    private fun startDrawing(x: Float, y: Float) {
        drawOriginX = x; drawOriginY = y
        selRect.set(x, y, x, y)
        hasSel = true
    }

    private fun onMove(x: Float, y: Float) {
        val dx = x - lastX
        val dy = y - lastY

        when (dragMode) {
            // DRAWING and all corner drags use anchor/live min-max — rectangle flips freely
            DragMode.DRAWING,
            DragMode.CORNER_TL, DragMode.CORNER_TR,
            DragMode.CORNER_BL, DragMode.CORNER_BR -> {
                selRect.set(
                    min(drawOriginX, x), min(drawOriginY, y),
                    max(drawOriginX, x), max(drawOriginY, y)
                )
                clampToView()
            }
            DragMode.CENTER -> {
                selRect.offset(dx, dy)
                clampToView()
            }
            // Edge drags: unconstrained; switch dragMode when crossing the opposite edge
            DragMode.EDGE_L -> {
                selRect.left += dx
                if (selRect.left > selRect.right) {
                    val t = selRect.left; selRect.left = selRect.right; selRect.right = t
                    dragMode = DragMode.EDGE_R
                }
                clampToView()
            }
            DragMode.EDGE_R -> {
                selRect.right += dx
                if (selRect.right < selRect.left) {
                    val t = selRect.right; selRect.right = selRect.left; selRect.left = t
                    dragMode = DragMode.EDGE_L
                }
                clampToView()
            }
            DragMode.EDGE_T -> {
                selRect.top += dy
                if (selRect.top > selRect.bottom) {
                    val t = selRect.top; selRect.top = selRect.bottom; selRect.bottom = t
                    dragMode = DragMode.EDGE_B
                }
                clampToView()
            }
            DragMode.EDGE_B -> {
                selRect.bottom += dy
                if (selRect.bottom < selRect.top) {
                    val t = selRect.bottom; selRect.bottom = selRect.top; selRect.top = t
                    dragMode = DragMode.EDGE_T
                }
                clampToView()
            }
            DragMode.NONE -> {}
        }
        invalidate()
        lastX = x; lastY = y
    }

    private fun onUp(x: Float, y: Float) {
        if (dragMode == DragMode.DRAWING) {
            if (selRect.width() < minSize || selRect.height() < minSize) {
                hasSel = false; invalidate(); dragMode = DragMode.NONE; return
            }
        }
        if (hasSel) {
            Log.d("JeezSnapper",
                "Selection: ${selRect.toShortString()} — ${selRect.width().toInt()}×${selRect.height().toInt()}px")
            onSelectionComplete?.invoke(RectF(selRect))
        }
        dragMode = DragMode.NONE
    }

    // ── System gesture / key handling ─────────────────────────────────────────────

    /**
     * Fires when the window loses focus — triggered by the home gesture or any
     * system UI that takes the foreground. Used to auto-cancel the Snapper.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus && !isDragging) onCancelRequested?.invoke()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        systemGestureExclusionRects = emptyList()
    }

    /** Intercepts the back key/gesture while this window is focused. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onCancelRequested?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ── Hit testing ───────────────────────────────────────────────────────────────

    private fun hitTest(x: Float, y: Float): DragMode {
        val r = selRect
        if (dist(x, y, r.left,  r.top)    < cornerHit) return DragMode.CORNER_TL
        if (dist(x, y, r.right, r.top)    < cornerHit) return DragMode.CORNER_TR
        if (dist(x, y, r.left,  r.bottom) < cornerHit) return DragMode.CORNER_BL
        if (dist(x, y, r.right, r.bottom) < cornerHit) return DragMode.CORNER_BR
        val onH = x in r.left..r.right
        val onV = y in r.top..r.bottom
        if (onH && y in r.top    - edgeHit..r.top    + edgeHit) return DragMode.EDGE_T
        if (onH && y in r.bottom - edgeHit..r.bottom + edgeHit) return DragMode.EDGE_B
        if (onV && x in r.left   - edgeHit..r.left   + edgeHit) return DragMode.EDGE_L
        if (onV && x in r.right  - edgeHit..r.right  + edgeHit) return DragMode.EDGE_R
        if (r.contains(x, y)) return DragMode.CENTER
        hasSel = false
        startDrawing(x, y)
        return DragMode.DRAWING
    }

    // ── Utilities ─────────────────────────────────────────────────────────────────

    private fun clampToView() {
        val w = width.toFloat(); val h = height.toFloat()
        selRect.left   = selRect.left.coerceIn(0f, w)
        selRect.right  = selRect.right.coerceIn(0f, w)
        selRect.top    = selRect.top.coerceIn(0f, h)
        selRect.bottom = selRect.bottom.coerceIn(0f, h)
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    private fun dp(v: Float) = v * density
}

// ── Drag mode enum ────────────────────────────────────────────────────────────────

private enum class DragMode {
    NONE, DRAWING, CENTER,
    CORNER_TL, CORNER_TR, CORNER_BL, CORNER_BR,
    EDGE_L, EDGE_R, EDGE_T, EDGE_B
}
