package io.github.kvmy666.autoexpand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Full-screen crop view for Screen Snapper.
 *
 * Draws the frozen screenshot behind a dark dim overlay. The user draws a rectangle
 * by dragging; the dim is "punched out" inside the selection to show the screenshot
 * at full brightness. Corner handles and edge strips allow resize. Center drag moves.
 *
 * Call [onSelectionComplete] to be notified when the user lifts their finger after
 * making a valid selection. Call [getCroppedBitmap] to extract the selected region.
 */
class SnapCropView(
    context: Context,
    private val screenshot: Bitmap
) : View(context) {

    // ── Callbacks ────────────────────────────────────────────────────────────────

    /** Called on ACTION_UP with a valid (non-trivial) selection. */
    var onSelectionComplete: ((RectF) -> Unit)? = null

    /** Called on ACTION_DOWN when a selection already exists (user is adjusting). */
    var onAdjustStarted: (() -> Unit)? = null

    // ── Selection state ───────────────────────────────────────────────────────────

    private val selRect = RectF()
    private var hasSel = false

    // Used only while DragMode.DRAWING
    private var drawOriginX = 0f
    private var drawOriginY = 0f

    // ── Touch state ───────────────────────────────────────────────────────────────

    private var dragMode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f

    // ── Dimensions ────────────────────────────────────────────────────────────────

    private val density = resources.displayMetrics.density
    private val handleLen = dp(20f)     // length of each L-arm
    private val handleW   = dp(5f)      // handle stroke width
    private val borderW   = dp(1.5f)    // thin selection border
    private val cornerHit = dp(30f)     // hit-test radius for corners
    private val edgeHit   = dp(14f)     // hit-test half-width for edges
    private val minSize   = dp(40f)     // minimum selection dimension

    // ── Paints ────────────────────────────────────────────────────────────────────

    private val bitmapDst = RectF()     // set in onSizeChanged to fill view

    private val dimPaint = Paint().apply {
        color = Color.BLACK
        alpha = 120     // ~47 % opacity
        isAntiAlias = false
    }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = false
    }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = borderW
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = handleW
        strokeCap = Paint.Cap.SQUARE
        isAntiAlias = true
    }

    init {
        // Hardware layer needed so saveLayer + PorterDuff.CLEAR composites correctly.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ── Layout ────────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bitmapDst.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    // ── Drawing ───────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        // 1. Frozen screenshot (scaled to fill view)
        canvas.drawBitmap(screenshot, null, bitmapDst, null)

        // 2. Dark dim with selection punched out
        val sc = canvas.saveLayer(bitmapDst, null)
        canvas.drawRect(bitmapDst, dimPaint)
        if (hasSel) canvas.drawRect(selRect, clearPaint)
        canvas.restoreToCount(sc)

        // 3. Selection border + corner handles
        if (hasSel) {
            canvas.drawRect(selRect, borderPaint)
            drawHandles(canvas)
        }
    }

    private fun drawHandles(canvas: Canvas) {
        val r = selRect
        val len = handleLen

        // Top-left ─ vertical arm then horizontal arm
        canvas.drawLine(r.left, r.top + len, r.left, r.top, handlePaint)
        canvas.drawLine(r.left, r.top, r.left + len, r.top, handlePaint)

        // Top-right
        canvas.drawLine(r.right - len, r.top, r.right, r.top, handlePaint)
        canvas.drawLine(r.right, r.top, r.right, r.top + len, handlePaint)

        // Bottom-left
        canvas.drawLine(r.left, r.bottom - len, r.left, r.bottom, handlePaint)
        canvas.drawLine(r.left, r.bottom, r.left + len, r.bottom, handlePaint)

        // Bottom-right
        canvas.drawLine(r.right - len, r.bottom, r.right, r.bottom, handlePaint)
        canvas.drawLine(r.right, r.bottom, r.right, r.bottom - len, handlePaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> onDown(x, y)
            MotionEvent.ACTION_MOVE -> onMove(x, y)
            MotionEvent.ACTION_UP   -> onUp(x, y)
            MotionEvent.ACTION_CANCEL -> dragMode = DragMode.NONE
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
        lastX = x
        lastY = y
    }

    private fun startDrawing(x: Float, y: Float) {
        drawOriginX = x
        drawOriginY = y
        selRect.set(x, y, x, y)
        hasSel = true
    }

    private fun onMove(x: Float, y: Float) {
        val dx = x - lastX
        val dy = y - lastY

        when (dragMode) {
            DragMode.DRAWING -> {
                selRect.set(
                    min(drawOriginX, x),
                    min(drawOriginY, y),
                    max(drawOriginX, x),
                    max(drawOriginY, y)
                )
                clampToView()
            }
            DragMode.CENTER -> {
                selRect.offset(dx, dy)
                clampToView()
            }
            DragMode.CORNER_TL -> {
                selRect.left = min(selRect.left + dx, selRect.right  - minSize)
                selRect.top  = min(selRect.top  + dy, selRect.bottom - minSize)
                clampToView()
            }
            DragMode.CORNER_TR -> {
                selRect.right = max(selRect.right + dx, selRect.left   + minSize)
                selRect.top   = min(selRect.top   + dy, selRect.bottom - minSize)
                clampToView()
            }
            DragMode.CORNER_BL -> {
                selRect.left   = min(selRect.left   + dx, selRect.right  - minSize)
                selRect.bottom = max(selRect.bottom + dy, selRect.top    + minSize)
                clampToView()
            }
            DragMode.CORNER_BR -> {
                selRect.right  = max(selRect.right  + dx, selRect.left + minSize)
                selRect.bottom = max(selRect.bottom + dy, selRect.top  + minSize)
                clampToView()
            }
            DragMode.EDGE_L -> {
                selRect.left = min(selRect.left + dx, selRect.right - minSize)
                clampToView()
            }
            DragMode.EDGE_R -> {
                selRect.right = max(selRect.right + dx, selRect.left + minSize)
                clampToView()
            }
            DragMode.EDGE_T -> {
                selRect.top = min(selRect.top + dy, selRect.bottom - minSize)
                clampToView()
            }
            DragMode.EDGE_B -> {
                selRect.bottom = max(selRect.bottom + dy, selRect.top + minSize)
                clampToView()
            }
            DragMode.NONE -> {}
        }

        invalidate()
        lastX = x
        lastY = y
    }

    private fun onUp(x: Float, y: Float) {
        if (dragMode == DragMode.DRAWING) {
            val tooSmall = selRect.width() < minSize || selRect.height() < minSize
            if (tooSmall) {
                hasSel = false
                invalidate()
                dragMode = DragMode.NONE
                return
            }
        }
        if (hasSel) {
            Log.d("JeezSnapper", "Selection: ${selRect.toShortString()} — ${selRect.width().toInt()}×${selRect.height().toInt()}px")
            onSelectionComplete?.invoke(RectF(selRect))
        }
        dragMode = DragMode.NONE
    }

    // ── Hit testing ───────────────────────────────────────────────────────────────

    private fun hitTest(x: Float, y: Float): DragMode {
        val r = selRect

        // Corners (priority over edges)
        if (dist(x, y, r.left,  r.top)    < cornerHit) return DragMode.CORNER_TL
        if (dist(x, y, r.right, r.top)    < cornerHit) return DragMode.CORNER_TR
        if (dist(x, y, r.left,  r.bottom) < cornerHit) return DragMode.CORNER_BL
        if (dist(x, y, r.right, r.bottom) < cornerHit) return DragMode.CORNER_BR

        // Edges
        val onH = x in r.left..r.right
        val onV = y in r.top..r.bottom
        if (onH && y in r.top    - edgeHit..r.top    + edgeHit) return DragMode.EDGE_T
        if (onH && y in r.bottom - edgeHit..r.bottom + edgeHit) return DragMode.EDGE_B
        if (onV && x in r.left   - edgeHit..r.left   + edgeHit) return DragMode.EDGE_L
        if (onV && x in r.right  - edgeHit..r.right  + edgeHit) return DragMode.EDGE_R

        // Inside → move
        if (r.contains(x, y)) return DragMode.CENTER

        // Outside → start new drawing
        hasSel = false
        startDrawing(x, y)
        return DragMode.DRAWING
    }

    // ── Utilities ─────────────────────────────────────────────────────────────────

    private fun clampToView() {
        val w = width.toFloat()
        val h = height.toFloat()
        // Clamp edges individually; keep rect valid (don't clamp so hard it collapses)
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

    // ── Public helpers ────────────────────────────────────────────────────────────

    /**
     * Returns the portion of [screenshot] that corresponds to the current selection,
     * mapped from view coordinates to bitmap pixel coordinates.
     * Returns null if there is no selection.
     */
    fun getCroppedBitmap(): Bitmap? {
        if (!hasSel || width == 0 || height == 0) return null
        val scaleX = screenshot.width.toFloat() / width
        val scaleY = screenshot.height.toFloat() / height
        val bx = (selRect.left   * scaleX).toInt().coerceIn(0, screenshot.width  - 1)
        val by = (selRect.top    * scaleY).toInt().coerceIn(0, screenshot.height - 1)
        val bw = (selRect.width() * scaleX).toInt().coerceAtLeast(1).coerceAtMost(screenshot.width  - bx)
        val bh = (selRect.height()* scaleY).toInt().coerceAtLeast(1).coerceAtMost(screenshot.height - by)
        return Bitmap.createBitmap(screenshot, bx, by, bw, bh)
    }
}

// ── Drag mode enum ────────────────────────────────────────────────────────────────

private enum class DragMode {
    NONE, DRAWING, CENTER,
    CORNER_TL, CORNER_TR, CORNER_BL, CORNER_BR,
    EDGE_L, EDGE_R, EDGE_T, EDGE_B
}
