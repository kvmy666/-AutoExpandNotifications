package io.github.kvmy666.autoexpand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.Choreographer
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Persistent floating overlay showing a cropped bitmap.
 *
 * Supports:
 * - Drag to move (via WindowManager position updates)
 * - Single tap → context menu
 * - Double tap → dismiss
 * - Drop shadow via software layer + Paint.setShadowLayer
 */
class SnapFloatView(
    context: Context,
    rawBitmap: Bitmap,
    private val wm: WindowManager
) : View(context) {

    /** Bitmap with 8dp rounded corners pre-applied (PorterDuff SRC_IN mask). */
    private val bitmap: Bitmap = rawBitmap.withRoundedCorners(8f * context.resources.displayMetrics.density)

    // ── Callbacks ─────────────────────────────────────────────────────────────────

    var onDragStart:  (() -> Unit)? = null
    /** Called every frame with the snap's center in screen coords. */
    var onDragUpdate: ((cx: Float, cy: Float) -> Unit)? = null
    /** Called on finger-up after a drag, with final snap center. */
    var onDrop:       ((cx: Float, cy: Float) -> Unit)? = null
    var onTap:        (() -> Unit)? = null
    var onDoubleTap:  (() -> Unit)? = null

    // ── WindowManager params (set by service after addView) ────────────────────

    lateinit var wmParams: WindowManager.LayoutParams

    fun getBitmap(): Bitmap = bitmap

    // ── Dimensions ────────────────────────────────────────────────────────────────

    private val density = resources.displayMetrics.density
    /**
     * Padding around the bitmap. Previously held space for a drop shadow, but
     * setShadowLayer + LAYER_TYPE_SOFTWARE caused OxygenOS's compositor to apply
     * a forced mirror-reflection effect on TYPE_APPLICATION_OVERLAY windows.
     * Shadow removed; pad kept at 0 — snap appears at exact crop coordinates.
     */
    val shadowPad = 0

    val snapW = bitmap.width
    val snapH = bitmap.height

    // ── Drawing ───────────────────────────────────────────────────────────────────

    /** Clean hardware-accelerated paint — no shadow layer, no software compositing. */
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        // Hardware layer: OxygenOS's reflection compositor only fires on SOFTWARE layers.
        // Forcing hardware prevents the ghost mirror artifact. (Task 2 fix)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        elevation     = 0f
        outlineProvider = null   // disable Android's automatic shadow from elevation
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (MeasureSpec.getMode(widthMeasureSpec)  == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec)  else snapW
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else snapH
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bmpPaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────────

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var initParamX    = 0
    private var initParamY    = 0
    private var isDragging    = false
    private var wasDragging   = false   // guard: suppress tap after drag

    private val dragThreshold = 10f * density

    // ── Choreographer throttle — one WM IPC per vsync frame ──────────────────────

    private var pendingX      = 0
    private var pendingY      = 0
    private var choreoPending = false
    private val choreoCb      = Choreographer.FrameCallback {
        choreoPending = false
        val screen = wm.currentWindowMetrics.bounds
        wmParams.x = pendingX.coerceIn(0, (screen.width()  - wmParams.width).coerceAtLeast(0))
        wmParams.y = pendingY.coerceIn(0, (screen.height() - wmParams.height).coerceAtLeast(0))
        wm.updateViewLayout(this, wmParams)
    }

    // ── Pinch-to-zoom ─────────────────────────────────────────────────────────────

    private var currentScale = 1f
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                currentScale = (currentScale * detector.scaleFactor).coerceIn(0.1f, 2.0f)
                val newW = (snapW * currentScale).toInt().coerceAtLeast(1)
                val newH = (snapH * currentScale).toInt().coerceAtLeast(1)
                val oldW = wmParams.width
                val oldH = wmParams.height
                // Keep focal point fixed in screen coords by shifting the window origin
                val fx = (detector.focusX / oldW.toFloat()).coerceIn(0f, 1f)
                val fy = (detector.focusY / oldH.toFloat()).coerceIn(0f, 1f)
                val screen = wm.currentWindowMetrics.bounds
                wmParams.x = (wmParams.x + ((oldW - newW) * fx).toInt())
                    .coerceIn(0, (screen.width()  - newW).coerceAtLeast(0))
                wmParams.y = (wmParams.y + ((oldH - newH) * fy).toInt())
                    .coerceIn(0, (screen.height() - newH).coerceAtLeast(0))
                wmParams.width  = newW
                wmParams.height = newH
                wm.updateViewLayout(this@SnapFloatView, wmParams)
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!wasDragging) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onTap?.invoke()
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap?.invoke()
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        // Second finger touches down → kill any active drag immediately
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            isDragging  = false
            wasDragging = true   // suppress tap after multi-touch gesture
            return true
        }

        // Any multi-finger contact: feed gesture detector but skip drag entirely
        if (event.pointerCount > 1 || scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(event)
            return true
        }

        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                initParamX    = wmParams.x
                initParamY    = wmParams.y
                isDragging    = false
                wasDragging   = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                if (!isDragging && hypot(dx, dy) > dragThreshold) {
                    isDragging  = true
                    wasDragging = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onDragStart?.invoke()
                }
                if (isDragging) {
                    pendingX = (initParamX + dx).toInt()
                    pendingY = (initParamY + dy).toInt()
                    if (!choreoPending) {
                        choreoPending = true
                        Choreographer.getInstance().postFrameCallback(choreoCb)
                    }
                    onDragUpdate?.invoke(
                        pendingX + wmParams.width  / 2f,
                        pendingY + wmParams.height / 2f
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    onDrop?.invoke(
                        wmParams.x + wmParams.width  / 2f,
                        wmParams.y + wmParams.height / 2f
                    )
                }
            }
        }
        return true
    }

    // ── Animation ─────────────────────────────────────────────────────────────────

    /**
     * Animate the snap shrinking toward [targetX, targetY] (screen coords of
     * the trash zone center) then call [onDone].
     */
    fun animateToTrash(targetX: Float, targetY: Float, onDone: () -> Unit) {
        val tx = targetX - (wmParams.x + snapW / 2f)
        val ty = targetY - (wmParams.y + snapH / 2f)
        animate()
            .translationX(tx).translationY(ty)
            .scaleX(0f).scaleY(0f).alpha(0f)
            .setDuration(250)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                alpha = 0f
                visibility = View.INVISIBLE
                onDone()
            }
            .start()
    }

    /** Simple scale+fade dismiss (for double-tap). */
    fun animateDismiss(onDone: () -> Unit) {
        animate()
            .scaleX(0f).scaleY(0f).alpha(0f)
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                alpha = 0f
                visibility = View.INVISIBLE
                onDone()
            }
            .start()
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────────

/**
 * Returns a new ARGB_8888 bitmap with transparent rounded corners of radius [r] px.
 * The shadow drawn by [Paint.setShadowLayer] naturally follows the alpha boundary.
 */
private fun Bitmap.withRoundedCorners(r: Float): Bitmap {
    val out    = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint  = Paint(Paint.ANTI_ALIAS_FLAG)
    // Draw the rounded-rect mask
    canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, paint)
    // Composite the source bitmap inside the mask
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(this, 0f, 0f, paint)
    return out
}
