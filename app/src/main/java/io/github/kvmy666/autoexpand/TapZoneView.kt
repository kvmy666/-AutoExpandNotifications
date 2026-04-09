package io.github.kvmy666.autoexpand

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View

class TapZoneView(
    context: Context,
    val onGesture: (ZoneTapGesture) -> Unit
) : View(context) {

    enum class ZoneTapGesture { SINGLE_TAP, DOUBLE_TAP, TRIPLE_TAP, LONG_PRESS }

    private companion object {
        const val TAP_WINDOW_MS = 300L
        const val LONG_PRESS_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tapCount = 0
    private var lastTapTime = 0L
    private var longPressConsumed = false
    private var longPressRunnable: Runnable? = null

    private val dispatchRunnable = Runnable {
        val count = tapCount
        tapCount = 0
        onGesture(when {
            count >= 3 -> ZoneTapGesture.TRIPLE_TAP
            count == 2 -> ZoneTapGesture.DOUBLE_TAP
            else       -> ZoneTapGesture.SINGLE_TAP
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressConsumed = false
                val lp = Runnable {
                    longPressConsumed = true
                    handler.removeCallbacks(dispatchRunnable)
                    tapCount = 0
                    onGesture(ZoneTapGesture.LONG_PRESS)
                }
                longPressRunnable = lp
                handler.postDelayed(lp, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_UP -> {
                longPressRunnable?.let { handler.removeCallbacks(it) }
                longPressRunnable = null
                if (longPressConsumed) return true
                val now = System.currentTimeMillis()
                handler.removeCallbacks(dispatchRunnable)
                if (now - lastTapTime < TAP_WINDOW_MS) {
                    tapCount = (tapCount + 1).coerceAtMost(3)
                } else {
                    tapCount = 1
                }
                lastTapTime = now
                handler.postDelayed(dispatchRunnable, TAP_WINDOW_MS)
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { handler.removeCallbacks(it) }
                longPressRunnable = null
                handler.removeCallbacks(dispatchRunnable)
                tapCount = 0
                longPressConsumed = false
            }
        }
        return true
    }
}
