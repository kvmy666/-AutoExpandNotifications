package io.github.kvmy666.autoexpand

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * Floating capsule action bar shown after the user completes a crop selection.
 *
 * Six icon buttons (left → right): Copy, Save, Share, OCR, Float, Close.
 * Entry animation (scale + fade) is driven externally via [ViewPropertyAnimator].
 */
class SnapActionBar(context: Context) : View(context) {

    var onAction: ((SnapperAction) -> Unit)? = null

    // ── Dimensions ────────────────────────────────────────────────────────────────

    private val d = resources.displayMetrics.density
    private val btnSize = 44f * d
    private val btnGap  =  4f * d
    private val padH    = 12f * d
    private val barH    = 52f * d

    val barWidthPx  = (padH * 2 + btnSize * 6 + btnGap * 5).toInt()
    val barHeightPx = barH.toInt()

    private val buttons = SnapperAction.values()

    // ── Paints ────────────────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 26, 26, 26)
    }
    /** Shared stroke paint for icon paths – reset per icon as needed. */
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    /** Dark fill for front-rect overlap masking (COPY icon). */
    private val bgFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 26, 26, 26)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * d
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // ── Measure ───────────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(barWidthPx, barHeightPx)
    }

    // ── Draw ──────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val r = barH / 2f
        canvas.drawRoundRect(0f, 0f, barWidthPx.toFloat(), barH, r, r, bgPaint)

        buttons.forEachIndexed { i, action ->
            val cx = padH + btnSize / 2f + i * (btnSize + btnGap)
            val cy = barH / 2f
            drawIcon(canvas, action, cx, cy)
        }
    }

    private fun drawIcon(canvas: Canvas, action: SnapperAction, cx: Float, cy: Float) {
        val u = d  // 1dp unit
        when (action) {
            SnapperAction.COPY  -> iconCopy(canvas, cx, cy, u)
            SnapperAction.SAVE  -> iconSave(canvas, cx, cy, u)
            SnapperAction.SHARE -> iconShare(canvas, cx, cy, u)
            SnapperAction.OCR   -> iconOcr(canvas, cx, cy, u)
            SnapperAction.FLOAT -> iconFloat(canvas, cx, cy, u)
            SnapperAction.CLOSE -> iconClose(canvas, cx, cy, u)
        }
    }

    // ── Icon drawing (all coordinates relative to button center cx,cy) ────────────

    /** Two overlapping squares; front square has dark fill to mask back overlap. */
    private fun iconCopy(c: Canvas, cx: Float, cy: Float, u: Float) {
        val s = 6.5f * u; val o = 3.5f * u
        // Back square (offset down-left)
        c.drawRect(cx - s, cy - s + o, cx + s - o, cy + s, stroke)
        // Front square (offset up-right) — fill first to hide back underneath
        c.drawRect(cx - s + o, cy - s, cx + s, cy + s - o, bgFill)
        c.drawRect(cx - s + o, cy - s, cx + s, cy + s - o, stroke)
    }

    /** Downward arrow + three-sided tray at the bottom. */
    private fun iconSave(c: Canvas, cx: Float, cy: Float, u: Float) {
        // Shaft
        c.drawLine(cx, cy - 6f * u, cx, cy + 0.5f * u, stroke)
        // Arrowhead
        c.drawLine(cx - 4f * u, cy - 2.5f * u, cx, cy + 2f * u, stroke)
        c.drawLine(cx + 4f * u, cy - 2.5f * u, cx, cy + 2f * u, stroke)
        // Tray (three sides, open top)
        c.drawLine(cx - 7f * u, cy + 3.5f * u, cx - 7f * u, cy + 7f * u, stroke)
        c.drawLine(cx - 7f * u, cy + 7f * u,   cx + 7f * u, cy + 7f * u, stroke)
        c.drawLine(cx + 7f * u, cy + 7f * u,   cx + 7f * u, cy + 3.5f * u, stroke)
    }

    /** Upward arrow emerging from an open-top box. */
    private fun iconShare(c: Canvas, cx: Float, cy: Float, u: Float) {
        // Shaft going up
        c.drawLine(cx, cy + 2f * u, cx, cy - 5.5f * u, stroke)
        // Arrowhead
        c.drawLine(cx - 4f * u, cy - 2f * u, cx, cy - 6.5f * u, stroke)
        c.drawLine(cx + 4f * u, cy - 2f * u, cx, cy - 6.5f * u, stroke)
        // Box (left, bottom, right sides — top open)
        c.drawLine(cx - 7f * u, cy - 1f * u, cx - 7f * u, cy + 7f * u, stroke)
        c.drawLine(cx - 7f * u, cy + 7f * u, cx + 7f * u, cy + 7f * u, stroke)
        c.drawLine(cx + 7f * u, cy + 7f * u, cx + 7f * u, cy - 1f * u, stroke)
    }

    /** Rounded rectangle outline with a bold 'A' centered inside. */
    private fun iconOcr(c: Canvas, cx: Float, cy: Float, u: Float) {
        val rf = RectF(cx - 8f * u, cy - 7f * u, cx + 8f * u, cy + 7f * u)
        c.drawRoundRect(rf, 2f * u, 2f * u, stroke)
        c.drawText("A", cx, cy + 4.5f * u, textPaint)
    }

    /** Two overlapping frame outlines (no fill masking — shows intersection). */
    private fun iconFloat(c: Canvas, cx: Float, cy: Float, u: Float) {
        val s = 6.5f * u; val o = 3.5f * u
        c.drawRect(cx - s, cy - s + o, cx + s - o, cy + s, stroke)
        c.drawRect(cx - s + o, cy - s, cx + s, cy + s - o, stroke)
    }

    /** X mark. */
    private fun iconClose(c: Canvas, cx: Float, cy: Float, u: Float) {
        c.drawLine(cx - 6.5f * u, cy - 6.5f * u, cx + 6.5f * u, cy + 6.5f * u, stroke)
        c.drawLine(cx + 6.5f * u, cy - 6.5f * u, cx - 6.5f * u, cy + 6.5f * u, stroke)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            // Which button was tapped?
            val rawIndex = ((x - padH) / (btnSize + btnGap)).toInt()
            // Also verify x is actually inside the button (not in the gap)
            val btnStart = padH + rawIndex * (btnSize + btnGap)
            if (rawIndex in buttons.indices && x >= btnStart && x <= btnStart + btnSize) {
                onAction?.invoke(buttons[rawIndex])
            }
        }
        return true
    }
}
