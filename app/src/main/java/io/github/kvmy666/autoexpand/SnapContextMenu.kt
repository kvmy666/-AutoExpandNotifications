package io.github.kvmy666.autoexpand

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Floating context menu for a pinned snap.
 * Items: Copy · Save · Share · OCR · ── · Remove from screen (red)
 *
 * [onAction] receives a [SnapperAction] for the first 4 items, or null for "Remove".
 */
class SnapContextMenu(context: Context) : View(context) {

    var onAction: ((SnapperAction?) -> Unit)? = null

    // ── Layout ────────────────────────────────────────────────────────────────────

    private val density  = resources.displayMetrics.density
    private val itemH    = (48f * density)
    private val padH     = (16f * density)
    private val textSize = (14f * density)
    private val cornerR  = (12f * density)

    private val items = listOf(
        MenuItem("Copy",              SnapperAction.COPY,  false),
        MenuItem("Save to gallery",   SnapperAction.SAVE,  false),
        MenuItem("Share",             SnapperAction.SHARE, false),
        MenuItem("Extract text",      SnapperAction.OCR,   false),
        MenuItem("Remove from screen",null,                true)   // destructive
    )

    val menuW = (196f * density).toInt()
    val menuH = (itemH * items.size).toInt()

    // ── Paints ────────────────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 28, 28, 28)
    }
    private val normalTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = this@SnapContextMenu.textSize
    }
    private val destructTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 80, 80)
        textSize = this@SnapContextMenu.textSize
    }
    private val dividerPaint = Paint().apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 1f
    }

    // ── Measure ───────────────────────────────────────────────────────────────────

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(menuW, menuH)
    }

    // ── Draw ──────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        // Background
        canvas.drawRoundRect(
            RectF(0f, 0f, menuW.toFloat(), menuH.toFloat()),
            cornerR, cornerR, bgPaint
        )

        items.forEachIndexed { i, item ->
            val top  = i * itemH
            val cy   = top + itemH / 2f

            // Divider above (skip first item)
            if (i > 0) {
                canvas.drawLine(padH, top, menuW - padH, top, dividerPaint)
            }

            // Label
            val paint = if (item.destructive) destructTextPaint else normalTextPaint
            val fm    = paint.fontMetrics
            val ty    = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(item.label, padH, ty, paint)
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val idx = (event.y / itemH).toInt().coerceIn(0, items.size - 1)
            onAction?.invoke(items[idx].action)
        }
        return true
    }

    // ── Entry animation ───────────────────────────────────────────────────────────

    fun animateIn() {
        scaleY = 0.85f; alpha = 0f; pivotY = 0f
        animate().scaleY(1f).alpha(1f)
            .setDuration(180).setInterpolator(DecelerateInterpolator()).start()
    }

    // ── Data ──────────────────────────────────────────────────────────────────────

    private data class MenuItem(
        val label: String,
        val action: SnapperAction?,   // null = Remove
        val destructive: Boolean
    )
}
