package io.github.kvmy666.autoexpand

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Translucent pill handle shown on the left or right screen edge.
 * Tap it to trigger a screen capture.
 */
class SnapEdgeButton(context: Context) : View(context) {

    var onTap: (() -> Unit)? = null

    private val density = resources.displayMetrics.density

    /** Width and height exposed so the service can size the WM window. */
    val buttonW = (20f * density).toInt()
    val buttonH = (56f * density).toInt()

    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 220, 220, 220)
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
    }
    private val rect    = RectF()
    private var pressed = false

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(buttonW, buttonH)

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, buttonW.toFloat(), buttonH.toFloat())
        val r = buttonW / 2f
        canvas.drawRoundRect(rect, r, r, if (pressed) pressedPaint else normalPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN   -> { pressed = true;  invalidate() }
            MotionEvent.ACTION_UP     -> { pressed = false; invalidate(); onTap?.invoke() }
            MotionEvent.ACTION_CANCEL -> { pressed = false; invalidate() }
        }
        return true
    }
}
