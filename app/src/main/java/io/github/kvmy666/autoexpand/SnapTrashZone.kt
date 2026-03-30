package io.github.kvmy666.autoexpand

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Red circular trash zone shown at the bottom of the screen while a snap is being dragged.
 * Scales up when the snap hovers over it.
 */
class SnapTrashZone(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    /** Diameter of the circle in px. Exposed so the service can size the WM window. */
    val circleDiameter = (72f * density).toInt()

    private val radius = circleDiameter / 2f
    private val cx     = radius
    private val cy     = radius

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 220, 50, 50)
        style = Paint.Style.FILL
    }
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(circleDiameter, circleDiameter)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, radius - 2f, circlePaint)
        val arm = radius * 0.35f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, xPaint)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, xPaint)
    }

    // ── Public API ────────────────────────────────────────────────────────────────

    fun show() {
        alpha = 0f
        visibility = VISIBLE
        animate().alpha(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
    }

    fun hide() {
        animate().alpha(0f).setDuration(150)
            .withEndAction { visibility = GONE }
            .start()
    }

    private var isSnapHovered = false

    fun setSnapHovered(hovered: Boolean) {
        if (hovered == isSnapHovered) return
        isSnapHovered = hovered
        val target = if (hovered) 1.2f else 1.0f
        val interp = if (hovered) OvershootInterpolator(2f) else DecelerateInterpolator()
        animate().scaleX(target).scaleY(target)
            .setDuration(150).setInterpolator(interp).start()
    }
}
