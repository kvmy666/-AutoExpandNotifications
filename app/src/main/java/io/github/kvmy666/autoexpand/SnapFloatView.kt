package io.github.kvmy666.autoexpand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
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
    /** Extra space around the bitmap to show the drop shadow without clipping. */
    val shadowPad = (24f * density).toInt()

    val snapW = bitmap.width  + shadowPad * 2
    val snapH = bitmap.height + shadowPad * 2

    // ── Drawing ───────────────────────────────────────────────────────────────────

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(18f * density, 0f, 6f * density, Color.argb(150, 0, 0, 0))
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)  // required for setShadowLayer
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(snapW, snapH)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(bitmap, shadowPad.toFloat(), shadowPad.toFloat(), bmpPaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────────

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var initParamX    = 0
    private var initParamY    = 0
    private var isDragging    = false
    private var wasDragging   = false   // guard: suppress tap after drag

    private val dragThreshold = 10f * density

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!wasDragging) onTap?.invoke()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap?.invoke()
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.action) {
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
                    onDragStart?.invoke()
                }
                if (isDragging) {
                    wmParams.x = (initParamX + dx).toInt()
                    wmParams.y = (initParamY + dy).toInt()
                    wm.updateViewLayout(this, wmParams)
                    onDragUpdate?.invoke(
                        wmParams.x + snapW / 2f,
                        wmParams.y + snapH / 2f
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    onDrop?.invoke(
                        wmParams.x + snapW / 2f,
                        wmParams.y + snapH / 2f
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
