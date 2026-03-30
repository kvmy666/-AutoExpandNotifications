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
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot

class SnapperService : Service() {

    companion object {
        const val CHANNEL_ID              = "snapper_service"
        /** Legacy debug action — behaves identically to ACTION_CAPTURE. */
        const val ACTION_TEST_CAPTURE     = "io.github.kvmy666.autoexpand.ACTION_TEST_CAPTURE"
        /** Triggered by QS tile or edge button to start a capture. */
        const val ACTION_CAPTURE          = "io.github.kvmy666.autoexpand.ACTION_CAPTURE"
        /** Show the persistent edge button overlay. */
        const val ACTION_SHOW_EDGE_BUTTON = "io.github.kvmy666.autoexpand.ACTION_SHOW_EDGE_BUTTON"
        /** Hide the edge button overlay (service stops itself if nothing else is active). */
        const val ACTION_HIDE_EDGE_BUTTON = "io.github.kvmy666.autoexpand.ACTION_HIDE_EDGE_BUTTON"
        /** Intent extra: set to true when the intent comes from the QS tile (needs panel-close delay). */
        const val EXTRA_QS_TRIGGERED      = "from_qs"
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
    private var trashZone:   SnapTrashZone?   = null
    private var contextMenu: SnapContextMenu? = null
    private var menuTarget:  SnapFloatView?   = null

    // ── Edge button ───────────────────────────────────────────────────────────────

    private var edgeButton: SnapEdgeButton? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_TEST_CAPTURE -> { if (cropView == null) handleCapture() }
            ACTION_CAPTURE      -> {
                val fromQs = intent?.getBooleanExtra(EXTRA_QS_TRIGGERED, false) ?: false
                if (cropView == null) handleCapture(delayMs = if (fromQs) 700L else 0L)
            }
            ACTION_SHOW_EDGE_BUTTON  -> showEdgeButton()
            ACTION_HIDE_EDGE_BUTTON  -> { hideEdgeButton(); stopSelfIfIdle() }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        dismissAll()
        floatingSnaps.toList().forEach { removeSnap(it, animate = false) }
        dismissTrashZone()
        dismissContextMenu()
        edgeButton?.let { safeRemoveView(it); edgeButton = null }
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
     * Capture triggered from the edge button.
     * Briefly hides the button so it doesn't appear in the screenshot, then restores it.
     */
    private fun handleCaptureHideButton() {
        edgeButton?.alpha = 0f
        Thread {
            Thread.sleep(80)  // one frame at 60fps = ~16ms; 80ms is safely beyond any refresh rate
            handleCaptureInternal()
            handler.post { edgeButton?.alpha = 1f }
        }.start()
    }

    // ── Screenshot capture ────────────────────────────────────────────────────────

    private fun handleCapture(delayMs: Long = 0L) {
        Thread {
            if (delayMs > 0L) {
                Log.d(TAG, "QS delay: waiting ${delayMs}ms for panel close")
                Thread.sleep(delayMs)
            }
            handleCaptureInternal()
        }.start()
    }

    private fun handleCaptureInternal() {
        val t0  = System.currentTimeMillis()
        val bmp = captureScreen()
        if (bmp == null) {
            Log.e(TAG, "Capture failed")
            handler.post { stopSelfIfIdle() }
            return
        }
        Log.d(TAG, "Capture OK in ${System.currentTimeMillis() - t0}ms — ${bmp.width}×${bmp.height}")
        handler.post { showCropUi(bmp) }
    }

    private fun captureScreen(): Bitmap? = try {
        val f    = File(cacheDir, "snap_${System.currentTimeMillis()}.png")
        val p    = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -d 0 -p ${f.absolutePath}"))
        val exit = p.waitFor()
        Log.d(TAG, "screencap exit=$exit")
        if (exit != 0 || !f.exists() || f.length() == 0L) { f.delete(); null }
        else BitmapFactory.decodeFile(f.absolutePath).also { f.delete() }
    } catch (e: Exception) { Log.e(TAG, "captureScreen: ${e.message}"); null }

    // ── Crop UI ───────────────────────────────────────────────────────────────────

    private fun showCropUi(bmp: Bitmap) {
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

        val view = SnapCropView(this, bmp).apply {
            onSelectionComplete = { rect -> onCropSelected(rect) }
            onAdjustStarted     = { dismissActionBar() }
            onCancelRequested   = { handler.post { dismissAll(); stopSelfIfIdle() } }
            onDoubleTapPin      = doubleTapPin@{
                val rect = cropView?.getSelRect()       ?: return@doubleTapPin
                val snap = cropView?.getCroppedBitmap() ?: return@doubleTapPin
                lastSelRect = rect
                // Temporarily store snap so doFloat() can grab it even after cropView changes
                handler.post { doFloat() }
            }
            onFullScreenPin     = fullScreenPin@{
                val fullBmp = cropView?.getFullBitmap() ?: return@fullScreenPin
                handler.post {
                    dismissAll()
                    createFloatingSnap(fullBmp, RectF(0f, 0f, fullBmp.width.toFloat(), fullBmp.height.toFloat()))
                    saveToHistory(fullBmp)
                    updateNotification()
                }
            }
        }
        windowManager.addView(view, params)
        cropView = view
        view.requestFocus()
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

        val barX = ((selRect.left + selRect.right) / 2f - bar.barWidthPx / 2f)
            .toInt().coerceIn(
                (8f * density).toInt(),
                screen.width() - bar.barWidthPx - (8f * density).toInt()
            )
        val margin     = (16f * density).toInt()
        val preferredY = selRect.bottom.toInt() + margin
        val barY       = if (preferredY + bar.barHeightPx > screen.height() - (16f * density).toInt())
            selRect.top.toInt() - margin - bar.barHeightPx
        else preferredY

        val params = WindowManager.LayoutParams(
            bar.barWidthPx, bar.barHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
        when (action) {
            SnapperAction.COPY  -> exportBitmap(cropView?.getCroppedBitmap(), action) { dismissAll(); stopSelfIfIdle() }
            SnapperAction.SAVE  -> exportBitmap(cropView?.getCroppedBitmap(), action) { dismissAll(); stopSelfIfIdle() }
            SnapperAction.SHARE -> exportBitmap(cropView?.getCroppedBitmap(), action) { dismissAll(); stopSelfIfIdle() }
            SnapperAction.OCR   -> {
                val bmp = cropView?.getCroppedBitmap() ?: run { toast("No selection"); return }
                dismissAll()
                doOcr(bmp) { stopSelfIfIdle() }
            }
            SnapperAction.FLOAT -> doFloat()
            SnapperAction.CLOSE -> { dismissAll(); stopSelfIfIdle() }
        }
    }

    // ── Float action ──────────────────────────────────────────────────────────────

    private fun doFloat() {
        val rect = lastSelRect ?: return
        val bmp  = cropView?.getCroppedBitmap() ?: return
        dismissAll()
        createFloatingSnap(bmp, rect)
        saveToHistory(bmp)
        updateNotification()
        Log.d(TAG, "Float created — ${floatingSnaps.size} snap(s) active")
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
        view.onDragStart  = { showTrashZone() }
        view.onDragUpdate = { cx, cy -> onSnapDragUpdate(cx, cy) }
        view.onDrop       = { cx, cy -> onSnapDrop(view, cx, cy) }
        view.onTap        = { toggleContextMenu(view) }
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
                db.prune(limit)
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

    // ── Context menu ──────────────────────────────────────────────────────────────

    private fun toggleContextMenu(view: SnapFloatView) {
        if (menuTarget == view && contextMenu != null) { dismissContextMenu(); return }
        dismissContextMenu()
        showContextMenu(view)
    }

    private fun showContextMenu(view: SnapFloatView) {
        val menu   = SnapContextMenu(this)
        val screen = windowManager.currentWindowMetrics.bounds
        val margin = (8f * density).toInt()
        val menuX  = view.wmParams.x.coerceIn(margin, screen.width() - menu.menuW - margin)
        val menuY  = if (view.wmParams.y - menu.menuH - margin >= 0)
            view.wmParams.y - menu.menuH - margin
        else
            view.wmParams.y + view.snapH + margin

        val params = WindowManager.LayoutParams(
            menu.menuW, menu.menuH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = menuX; y = menuY }

        menu.onAction = { action -> handleSnapContextAction(view, action) }
        windowManager.addView(menu, params)
        menu.animateIn()
        contextMenu = menu
        menuTarget  = view
    }

    private fun dismissContextMenu() {
        contextMenu?.let { safeRemoveView(it); contextMenu = null }
        menuTarget = null
    }

    private fun handleSnapContextAction(view: SnapFloatView, action: SnapperAction?) {
        dismissContextMenu()
        when (action) {
            null              -> animateDismissSnap(view)
            SnapperAction.OCR -> doOcr(view.getBitmap()) {}
            else              -> exportBitmap(view.getBitmap(), action) {}
        }
    }

    // ── Snap dismissal ────────────────────────────────────────────────────────────

    private fun animateDismissSnap(view: SnapFloatView) {
        dismissContextMenu()
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
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Jeez Snapper")
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
