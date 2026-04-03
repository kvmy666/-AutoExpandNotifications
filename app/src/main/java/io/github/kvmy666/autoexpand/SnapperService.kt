package io.github.kvmy666.autoexpand

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.hypot

class SnapperService : Service() {

    companion object {
        const val CHANNEL_ID              = "snapper_service"
        /** Triggered by QS tile or edge button to start a capture. */
        const val ACTION_CAPTURE          = "io.github.kvmy666.autoexpand.ACTION_CAPTURE"
        /** Show the persistent edge button overlay. */
        const val ACTION_SHOW_EDGE_BUTTON = "io.github.kvmy666.autoexpand.ACTION_SHOW_EDGE_BUTTON"
        /** Hide the edge button overlay (service stops itself if nothing else is active). */
        const val ACTION_HIDE_EDGE_BUTTON = "io.github.kvmy666.autoexpand.ACTION_HIDE_EDGE_BUTTON"
        const val EXTRA_QS_TRIGGERED      = "from_qs"
        /** Float a saved snap from history as an overlay; pass EXTRA_SNAP_PATH with the file path. */
        const val ACTION_FLOAT_SNAP       = "io.github.kvmy666.autoexpand.ACTION_FLOAT_SNAP"
        const val EXTRA_SNAP_PATH         = "snap_path"
        private const val NOTIFICATION_ID = 1001
        private const val TAG             = "JeezSnapper"
        private const val FILE_PROVIDER   = "io.github.kvmy666.autoexpand.fileprovider"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private val density get() = resources.displayMetrics.density

    // ── Crop phase state ──────────────────────────────────────────────────────────

    private var cropView:    SnapCropView?  = null
    private var actionBar:   SnapActionBar? = null
    private var lastSelRect: RectF?         = null

    // ── Floating snaps ────────────────────────────────────────────────────────────

    private val floatingSnaps = mutableListOf<SnapFloatView>()
    private var trashZone:      SnapTrashZone?  = null
    private var snapBar:        SnapActionBar?  = null
    private var snapBarTarget:  SnapFloatView?  = null

    // ── Edge button ───────────────────────────────────────────────────────────────

    private var edgeButton: SnapEdgeButton? = null

    // ── Persistent root shell ─────────────────────────────────────────────────────

    private var suProcess: Process? = null
    private var suStdin:   DataOutputStream? = null

    // ── Screencap prefetch ────────────────────────────────────────────────────────

    @Volatile private var prefetchBitmap: Bitmap? = null
    @Volatile private var prefetchDone:   Boolean = false
    private var prefetchThread: Thread? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startSuShell()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_CAPTURE           -> startCapture()
            ACTION_SHOW_EDGE_BUTTON  -> showEdgeButton()
            ACTION_HIDE_EDGE_BUTTON  -> { hideEdgeButton(); stopSelfIfIdle() }
            ACTION_FLOAT_SNAP        -> {
                val path = intent.getStringExtra(EXTRA_SNAP_PATH) ?: return START_NOT_STICKY
                floatSnapFromHistory(path)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        dismissAll()
        floatingSnaps.toList().forEach { removeSnap(it, animate = false) }
        dismissTrashZone()
        dismissSnapBar()
        edgeButton?.let { safeRemoveView(it); edgeButton = null }
        try { suStdin?.writeBytes("exit\n"); suStdin?.flush() } catch (_: Exception) {}
        suProcess?.destroy()
        suProcess = null; suStdin = null
        super.onDestroy()
    }

    // ── Notification ──────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Snapper")
            .setContentText(
                if (floatingSnaps.isEmpty()) "Ready to capture"
                else "${floatingSnaps.size} snap(s) pinned"
            )
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    // ── Edge button ───────────────────────────────────────────────────────────────

    private fun showEdgeButton() {
        if (edgeButton != null) return
        val prefs  = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val side   = prefs.getString("snapper_button_side", "right") ?: "right"
        val screen = windowManager.currentWindowMetrics.bounds
        val btn    = SnapEdgeButton(this)
        val bx     = if (side == "right") screen.width() - btn.buttonW else 0
        val by     = screen.height() / 2 - btn.buttonH / 2

        val params = WindowManager.LayoutParams(
            btn.buttonW, btn.buttonH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = bx; y = by }

        btn.onTap = { if (cropView == null) handleCaptureHideButton() }
        btn.alpha = 0f
        windowManager.addView(btn, params)
        edgeButton = btn
        btn.animate().alpha(1f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        Log.d(TAG, "Edge button shown (side=$side)")
    }

    private fun hideEdgeButton() {
        val btn = edgeButton ?: return
        edgeButton = null
        btn.animate().alpha(0f).setDuration(200).withEndAction { safeRemoveView(btn) }.start()
        Log.d(TAG, "Edge button hidden")
    }

    /**
     * Capture triggered from the edge button — show crop UI immediately (live overlay).
     */
    private fun handleCaptureHideButton() {
        startCapture()
    }

    // ── Screenshot capture (deferred — only runs when user commits to an action) ───

    /**
     * Hides ALL our overlay windows so SurfaceFlinger excludes them from screencap.
     * Called just before running screencap after user action.
     */
    private fun hideAllForCapture() {
        edgeButton?.alpha = 0f
        snapBar?.alpha    = 0f
        trashZone?.alpha  = 0f
        floatingSnaps.forEach { it.alpha = 0f }
    }

    private fun restoreAfterCapture() {
        edgeButton?.alpha = 1f
        floatingSnaps.forEach { it.alpha = 1f }
    }

    private fun restoreUiAfterFailure() {
        restoreAfterCapture()
        cropView?.alpha  = 1f
        actionBar?.alpha = 1f
    }

    /**
     * Hides Snapper overlays, then fires screencap in a background thread immediately
     * so the bitmap is ready before the user finishes selecting a crop region.
     * The crop UI is added with FLAG_SECURE so SurfaceFlinger excludes it automatically.
     */
    private fun startPrefetchScreencap() {
        prefetchBitmap = null
        prefetchDone   = false
        // Hide our persistent overlays so they don't bleed into the screenshot.
        edgeButton?.alpha = 0f
        snapBar?.alpha    = 0f
        trashZone?.alpha  = 0f
        floatingSnaps.forEach { it.alpha = 0f }
        prefetchThread = Thread {
            // screencap calls SurfaceFlinger which captures the current frame immediately
            // (within one vsync, ~16 ms). File write/encode takes longer. The crop UI is
            // shown after a 100 ms delay so it is never in the captured frame.
            prefetchBitmap = runScreencap()
            handler.post {
                edgeButton?.alpha = 1f
                floatingSnaps.forEach { it.alpha = 1f }
            }
            prefetchDone = true
            Log.d(TAG, "Prefetch screencap done — bitmap=${prefetchBitmap != null}")
        }.also { it.start() }
    }

    /** Single entry-point for all capture triggers (chord, edge button, QS tile). */
    private fun startCapture() {
        if (cropView != null) return
        startPrefetchScreencap()
        // Delay crop UI by 100 ms — screencap grabs its SurfaceFlinger frame in ~16 ms
        // (one vsync), so the crop overlay is guaranteed to be absent from the capture.
        handler.postDelayed({ showCropUi() }, 100L)
    }

    private fun startSuShell() {
        try {
            suProcess?.destroy()
            val p = Runtime.getRuntime().exec("su")
            suStdin   = DataOutputStream(p.outputStream)
            suProcess = p
            Log.d(TAG, "su shell started")
        } catch (e: Exception) {
            Log.e(TAG, "su shell init failed: ${e.message}")
            suProcess = null; suStdin = null
        }
    }

    /** Runs screencap via persistent su shell. Falls back to one-off spawn if shell dies. */
    private fun runScreencap(): Bitmap? {
        val snap     = File(cacheDir, "snap_${System.currentTimeMillis()}.png")
        val sentinel = File(cacheDir, "snap.done")
        sentinel.delete()
        try {
            if (suProcess?.isAlive != true || suStdin == null) startSuShell()
            val stdin = suStdin ?: throw IOException("no su shell")
            stdin.writeBytes("screencap -p ${snap.absolutePath}; echo x > ${sentinel.absolutePath}\n")
            stdin.flush()
            val deadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < deadline) {
                if (sentinel.exists()) { sentinel.delete(); break }
                Thread.sleep(20)
            }
            Log.d(TAG, "screencap exit=0 (persistent shell)")
        } catch (e: Exception) {
            Log.w(TAG, "persistent shell failed (${e.message}) — spawning fresh su")
            suProcess?.destroy(); suProcess = null; suStdin = null
            try {
                val exit = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p ${snap.absolutePath}")).waitFor()
                Log.d(TAG, "screencap exit=$exit (fallback)")
            } catch (ex: Exception) {
                Log.e(TAG, "fallback screencap failed: ${ex.message}")
            }
        }
        return if (snap.exists() && snap.length() > 0L)
            BitmapFactory.decodeFile(snap.absolutePath).also { snap.delete() }
        else { snap.delete(); null }
    }

    /**
     * Hides all overlays (including crop UI), runs screencap, crops to [selRect],
     * then calls [onDone] on the main thread with the cropped bitmap (or null on failure).
     * By the time su+screencap finishes (~300-500ms), the compositor has long settled.
     */
    private fun captureAndCrop(selRect: RectF, onDone: (Bitmap?) -> Unit) {
        val viewW = cropView?.width  ?: 0
        val viewH = cropView?.height ?: 0
        // Screencap is already running in the background (started in startPrefetchScreencap).
        // Just hide the crop UI visually so the transition looks clean.
        cropView?.alpha  = 0f
        actionBar?.alpha = 0f
        Thread {
            // Wait for prefetch to finish (max 5 s safety net)
            val deadline = System.currentTimeMillis() + 5000L
            while (!prefetchDone && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            val full = prefetchBitmap
            prefetchBitmap = null // release large bitmap ASAP
            if (full == null) {
                handler.post { restoreUiAfterFailure(); onDone(null) }
                return@Thread
            }
            val scaleX = full.width.toFloat()  / viewW.coerceAtLeast(1)
            val scaleY = full.height.toFloat() / viewH.coerceAtLeast(1)
            val bx = (selRect.left    * scaleX).toInt().coerceIn(0, full.width  - 1)
            val by = (selRect.top     * scaleY).toInt().coerceIn(0, full.height - 1)
            val bw = (selRect.width() * scaleX).toInt().coerceAtLeast(1).coerceAtMost(full.width  - bx)
            val bh = (selRect.height()* scaleY).toInt().coerceAtLeast(1).coerceAtMost(full.height - by)
            val crop = Bitmap.createBitmap(full, bx, by, bw, bh)
            Log.d(TAG, "captureAndCrop: waited=${prefetchDone}, crop=${bw}x${bh}")
            handler.post { onDone(crop) }
        }.start()
    }

    // ── Crop UI ───────────────────────────────────────────────────────────────────

    private fun showCropUi() {
        dismissCropUi()
        // FLAG_NOT_FOCUSABLE intentionally omitted so the window can receive
        // focus-change events (home gesture) and key events (back gesture).
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val view = SnapCropView(this).apply {
            onSelectionComplete = { rect -> onCropSelected(rect) }
            onAdjustStarted     = { dismissActionBar() }
            onCancelRequested   = { handler.post { dismissAll(); stopSelfIfIdle() } }
            onDoubleTapPin      = doubleTapPin@{
                val rect = cropView?.getSelRect() ?: return@doubleTapPin
                lastSelRect = rect
                captureAndCrop(rect) { bmp ->
                    if (bmp != null) handler.post { doFloat(bmp) }
                    else handler.post { toast("Capture failed"); restoreUiAfterFailure() }
                }
            }
            onFullScreenPin = {
                handler.post {
                    // Dismiss Snapper entirely, then fire the native OS screenshot.
                    // stopSelfIfIdle() is intentionally deferred — calling it before the
                    // 300 ms delay kills the service and cancels the postDelayed callback.
                    prefetchBitmap = null
                    dismissAll()
                    handler.postDelayed({
                        try {
                            if (suProcess?.isAlive != true || suStdin == null) startSuShell()
                            suStdin?.writeBytes("input keyevent KEYCODE_SYSRQ\n")
                            suStdin?.flush()
                            Log.d(TAG, "Native screenshot triggered via su shell")
                        } catch (e: Exception) {
                            Log.e(TAG, "Native screenshot failed: ${e.message}")
                        }
                        stopSelfIfIdle()
                    }, 300L) // let Snapper UI fully disappear before screencap fires
                }
            }
        }
        windowManager.addView(view, params)
        cropView = view
        view.requestFocus()
        view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        Log.d(TAG, "Crop UI shown")
    }

    private fun dismissCropUi() {
        cropView?.let { safeRemoveView(it); cropView = null }
    }

    // ── Action bar ────────────────────────────────────────────────────────────────

    private fun onCropSelected(selRect: RectF) {
        lastSelRect = selRect
        dismissActionBar()

        val bar    = SnapActionBar(this).apply { onAction = { handleCropAction(it) } }
        val screen = windowManager.currentWindowMetrics.bounds

        // Convert selRect from the crop view's local coordinates to absolute screen coordinates.
        // getLocationOnScreen() accounts for any window inset/gravity offset on the device.
        val cropLoc = IntArray(2)
        cropView?.getLocationOnScreen(cropLoc)
        val absSelBottom = cropLoc[1] + selRect.bottom.toInt()
        val absSelTop    = cropLoc[1] + selRect.top.toInt()

        val barX = ((selRect.left + selRect.right) / 2f - bar.barWidthPx / 2f)
            .toInt().coerceIn(
                (8f * density).toInt(),
                screen.width() - bar.barWidthPx - (8f * density).toInt()
            )
        val margin     = (16f * density).toInt()
        val preferredY = absSelBottom + margin
        val barY       = if (preferredY + bar.barHeightPx > screen.height() - margin)
            absSelTop - margin - bar.barHeightPx
        else preferredY

        Log.d(TAG, "Toolbar: cropBottomY=$absSelBottom toolbarTopY=$barY gap=${barY - absSelBottom}px")

        val params = WindowManager.LayoutParams(
            bar.barWidthPx, bar.barHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = barX; y = barY }

        bar.scaleX = 0.8f; bar.scaleY = 0.8f; bar.alpha = 0f
        windowManager.addView(bar, params)
        actionBar = bar
        bar.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        Log.d(TAG, "Action bar shown at ($barX,$barY)")
    }

    private fun dismissActionBar() {
        actionBar?.let { safeRemoveView(it); actionBar = null }
    }

    private fun dismissAll() {
        dismissActionBar()
        dismissCropUi()
    }

    // ── Crop action bar handlers ──────────────────────────────────────────────────

    private fun handleCropAction(action: SnapperAction) {
        Log.d(TAG, "Crop action: $action")
        if (action == SnapperAction.CLOSE) { dismissAll(); stopSelfIfIdle(); return }
        val selRect = cropView?.getSelRect() ?: return
        if (action == SnapperAction.FLOAT) {
            lastSelRect = selRect
            captureAndCrop(selRect) { bmp ->
                if (bmp != null) handler.post { doFloat(bmp) }
                else handler.post { toast("Capture failed"); restoreUiAfterFailure() }
            }
            return
        }
        captureAndCrop(selRect) { bmp ->
            if (bmp == null) { handler.post { toast("Capture failed"); restoreUiAfterFailure() }; return@captureAndCrop }
            when (action) {
                SnapperAction.COPY  -> handler.post { exportBitmap(bmp, action) { dismissAll(); stopSelfIfIdle() } }
                SnapperAction.SAVE  -> handler.post { exportBitmap(bmp, action) { dismissAll(); stopSelfIfIdle() } }
                SnapperAction.SHARE -> handler.post { exportBitmap(bmp, action) { dismissAll(); stopSelfIfIdle() } }
                SnapperAction.OCR   -> handler.post { dismissAll(); doOcr(bmp) { stopSelfIfIdle() } }
                else -> {}
            }
        }
    }

    // ── Float action ──────────────────────────────────────────────────────────────

    private fun doFloat(bmp: Bitmap) {
        val rect = lastSelRect ?: RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        dismissAll()
        createFloatingSnap(bmp, rect)
        saveToHistory(bmp)
        updateNotification()
        Log.d(TAG, "Float created — ${floatingSnaps.size} snap(s) active")
    }

    /** Re-opens a history snap as a floating overlay (called from ACTION_FLOAT_SNAP). */
    private fun floatSnapFromHistory(path: String) {
        Thread {
            val bmp = BitmapFactory.decodeFile(path)
            if (bmp == null) { handler.post { toast("Could not load snap") }; return@Thread }
            handler.post {
                val screen = windowManager.currentWindowMetrics.bounds
                // Place it centred in the top portion of the screen
                val srcRect = RectF(
                    screen.width() * 0.1f,  screen.height() * 0.15f,
                    screen.width() * 0.9f,  screen.height() * 0.15f + bmp.height.toFloat() *
                            (screen.width() * 0.8f / bmp.width)
                )
                createFloatingSnap(bmp, srcRect)
                updateNotification()
                Log.d(TAG, "History snap floated — ${floatingSnaps.size} active")
            }
        }.start()
    }

    // ── Floating snap creation ────────────────────────────────────────────────────

    private fun createFloatingSnap(bmp: Bitmap, sourceRect: RectF) {
        val prefs            = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val doubleTapDismiss = prefs.getBoolean("snapper_double_tap_dismiss", true)
        val view             = SnapFloatView(this, bmp, windowManager)
        val startX           = sourceRect.left.toInt() - view.shadowPad
        val startY           = sourceRect.top.toInt()  - view.shadowPad

        val params = WindowManager.LayoutParams(
            view.snapW, view.snapH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX; y = startY
        }

        view.wmParams     = params
        view.onDragStart  = { dismissSnapBar(); showTrashZone() }
        view.onDragUpdate = { cx, cy -> onSnapDragUpdate(cx, cy) }
        view.onDrop       = { cx, cy -> onSnapDrop(view, cx, cy) }
        view.onTap        = { toggleSnapBar(view) }
        if (doubleTapDismiss) {
            view.onDoubleTap = { animateDismissSnap(view) }
        }

        windowManager.addView(view, params)
        floatingSnaps.add(view)
    }

    // ── History ───────────────────────────────────────────────────────────────────

    /** Saves the bitmap to disk and records it in [SnapHistoryDb]. Off main thread. */
    private fun saveToHistory(bmp: Bitmap) {
        Thread {
            try {
                val histDir = File(filesDir, "snaps/history").also { it.mkdirs() }
                val ts      = System.currentTimeMillis()
                val file    = File(histDir, "snap_$ts.png")
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }

                val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
                val limit = prefs.getString("snapper_history_limit", "50")?.toIntOrNull() ?: 50

                val db = SnapHistoryDb(this)
                db.insert(file.absolutePath, ts, bmp.width, bmp.height)
                db.pruneWithFiles(limit) // deletes both DB records and PNG files
                db.close()
                Log.d(TAG, "Snap saved to history: ${file.name}")
            } catch (e: Exception) {
                Log.e(TAG, "History save failed: ${e.message}", e)
            }
        }.start()
    }

    // ── Trash zone ────────────────────────────────────────────────────────────────

    private fun showTrashZone() {
        if (trashZone != null) return
        val zone   = SnapTrashZone(this)
        val screen = windowManager.currentWindowMetrics.bounds
        val x      = screen.width()  / 2 - zone.circleDiameter / 2
        val y      = screen.height() - zone.circleDiameter - (48f * density).toInt()

        val params = WindowManager.LayoutParams(
            zone.circleDiameter, zone.circleDiameter,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

        zone.visibility = android.view.View.GONE
        windowManager.addView(zone, params)
        trashZone = zone
        zone.show()
    }

    private fun dismissTrashZone() {
        trashZone?.let { zone ->
            zone.hide()
            handler.postDelayed({ safeRemoveView(zone) }, 200)
            trashZone = null
        }
    }

    private fun trashCenterOnScreen(): Pair<Float, Float> {
        val screen = windowManager.currentWindowMetrics.bounds
        val diam   = (72f * density)
        val margin = (48f * density)
        return Pair(screen.width() / 2f, screen.height() - margin - diam / 2f)
    }

    private fun onSnapDragUpdate(snapCx: Float, snapCy: Float) {
        val (tx, ty) = trashCenterOnScreen()
        val hovered  = hypot(snapCx - tx, snapCy - ty) < (72f * density) / 2f + 40f * density
        trashZone?.setSnapHovered(hovered)
    }

    private fun onSnapDrop(view: SnapFloatView, snapCx: Float, snapCy: Float) {
        val (tx, ty)  = trashCenterOnScreen()
        val overTrash = hypot(snapCx - tx, snapCy - ty) < (72f * density) / 2f + 40f * density
        dismissTrashZone()
        if (overTrash) {
            view.animateToTrash(tx, ty) { removeSnap(view, animate = false) }
        }
    }

    // ── Snap action bar (floating snaps) ─────────────────────────────────────────

    private fun toggleSnapBar(target: SnapFloatView) {
        if (snapBarTarget == target && snapBar != null) { dismissSnapBar(); return }
        dismissSnapBar()
        showSnapBar(target)
    }

    private fun showSnapBar(target: SnapFloatView) {
        val bar    = SnapActionBar(this, arrayOf(
            SnapperAction.COPY, SnapperAction.SAVE, SnapperAction.SHARE,
            SnapperAction.OCR, SnapperAction.CLOSE))
        val screen = windowManager.currentWindowMetrics.bounds
        val margin = (8f * density).toInt()
        val gap    = (8f * density).toInt()

        val barX = (target.wmParams.x + target.snapW / 2f - bar.barWidthPx / 2f)
            .toInt().coerceIn(margin, screen.width() - bar.barWidthPx - margin)

        val contentBottom = target.wmParams.y + target.snapH - target.shadowPad
        val contentTop    = target.wmParams.y + target.shadowPad
        val barY = if (contentBottom + gap + bar.barHeightPx < screen.height() - margin)
            contentBottom + gap
        else
            (contentTop - gap - bar.barHeightPx).coerceAtLeast(margin)

        val params = WindowManager.LayoutParams(
            bar.barWidthPx, bar.barHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = barX; y = barY }

        bar.onAction     = { action -> handleSnapAction(target, action) }
        bar.onOutsideTap = { dismissSnapBar() }
        bar.scaleX = 0.85f; bar.scaleY = 0.85f; bar.alpha = 0f
        windowManager.addView(bar, params)
        snapBar = bar; snapBarTarget = target
        bar.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        Log.d(TAG, "Snap bar shown at ($barX,$barY)")
    }

    private fun dismissSnapBar() {
        snapBar?.let { safeRemoveView(it); snapBar = null }
        snapBarTarget = null
    }

    private fun handleSnapAction(target: SnapFloatView, action: SnapperAction) {
        dismissSnapBar()
        when (action) {
            SnapperAction.CLOSE -> animateDismissSnap(target)
            SnapperAction.OCR   -> doOcr(target.getBitmap()) {}
            else                -> exportBitmap(target.getBitmap(), action) {}
        }
    }

    // ── Snap dismissal ────────────────────────────────────────────────────────────

    private fun animateDismissSnap(view: SnapFloatView) {
        dismissSnapBar()
        view.animateDismiss { removeSnap(view, animate = false) }
    }

    private fun removeSnap(view: SnapFloatView, animate: Boolean) {
        if (animate) { view.animateDismiss { removeSnap(view, animate = false) }; return }
        floatingSnaps.remove(view)
        safeRemoveView(view)
        updateNotification()
        stopSelfIfIdle()
        Log.d(TAG, "Snap removed — ${floatingSnaps.size} remaining")
    }

    private fun stopSelfIfIdle() {
        if (floatingSnaps.isEmpty() && cropView == null && edgeButton == null) stopSelf()
    }

    // ── OCR ───────────────────────────────────────────────────────────────────────

    private fun doOcr(bitmap: Bitmap, onDone: () -> Unit) {
        val image      = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                recognizer.close()
                val text = result.text.trim()
                Log.d(TAG, "OCR: ${text.length} chars extracted")
                if (text.isBlank()) {
                    toast("No text found in selection")
                } else {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Snap OCR", text))
                    toast("Copied text from selection")
                }
                onDone()
            }
            .addOnFailureListener { e ->
                recognizer.close()
                Log.e(TAG, "OCR failed: ${e.message}", e)
                toast("Text recognition failed")
                onDone()
            }
    }

    // ── Export (Copy / Save / Share) ──────────────────────────────────────────────

    private fun exportBitmap(bmp: Bitmap?, action: SnapperAction, onDone: () -> Unit) {
        if (bmp == null) { toast("No image"); return }
        Thread {
            try {
                when (action) {
                    SnapperAction.COPY  -> copyToClipboard(bmp)
                    SnapperAction.SAVE  -> saveToGallery(bmp)
                    SnapperAction.SHARE -> shareImage(bmp)
                    else                -> {}
                }
                handler.post(onDone)
            } catch (e: Exception) {
                Log.e(TAG, "Export $action failed: ${e.message}", e)
                handler.post { toast("Failed: ${e.message}") }
            }
        }.start()
    }

    private fun copyToClipboard(bmp: Bitmap) {
        val file = snapCacheFile("copy")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri  = FileProvider.getUriForFile(this, FILE_PROVIDER, file)
        val clip = ClipData.newUri(contentResolver, "Snap", uri)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        handler.post { toast("Image copied to clipboard") }
    }

    private fun saveToGallery(bmp: Bitmap) {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Snap_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Snapper")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        uri?.let { contentResolver.openOutputStream(it)?.use { s -> bmp.compress(Bitmap.CompressFormat.PNG, 100, s) } }
        handler.post { toast("Saved to gallery") }
    }

    private fun shareImage(bmp: Bitmap) {
        val file    = snapCacheFile("share")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri     = FileProvider.getUriForFile(this, FILE_PROVIDER, file)
        val chooser = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Share snap"
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        handler.post { startActivity(chooser) }
    }

    private fun snapCacheFile(tag: String): File =
        File(cacheDir, "snaps").also { it.mkdirs() }.let { File(it, "snap_$tag.png") }

    // ── Notification update ───────────────────────────────────────────────────────

    private fun updateNotification() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    // ── Utilities ─────────────────────────────────────────────────────────────────

    private fun safeRemoveView(v: android.view.View) {
        try { windowManager.removeView(v) } catch (_: Exception) {}
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
