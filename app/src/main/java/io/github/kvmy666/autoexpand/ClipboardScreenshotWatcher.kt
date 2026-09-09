package io.github.kvmy666.autoexpand

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Notices that the user took a system screenshot and hands it to the vault.
 *
 * ## Why this watches the output instead of hooking the screenshot pipeline
 *
 * The pipeline was the first choice and it was investigated on the target build before a
 * line of this was written. On CPH2747 / OxygenOS 16.0.9.400 the screenshot never touches
 * AOSP's `com.android.systemui.screenshot`: `OplusScreenshotManagerService` (inside
 * `com.oplus.exsystemservice`, uid 1000) binds `com.oplus.screenshot/.service.ScreenshotService`,
 * and the bitmap is captured and encoded entirely inside `com.oplus.screenshot` — a
 * `priv_app` running as its own uid, which is not in the module's LSPosed scope.
 *
 * Even hooked, that process could not put the bytes anywhere the vault can read them: the
 * vault lives in Gboard's private data directory, and Gboard, SystemUI and the screenshot
 * app are three different uids. Any pipeline route therefore needs a new scope *and* a new
 * IPC channel carrying full-resolution images — which is exactly the cost the task said to
 * refuse. See docs/screenshot-routes.md for the log evidence.
 *
 * What is left is the route the task called last resort, and on this device it is the only
 * one that is both reachable and free: Gboard already holds `READ_MEDIA_IMAGES`, so the
 * process that owns the vault can see the screenshot itself, with **no new permission, no
 * new scope, no new process and no new service**.
 *
 * ## Consequences worth knowing
 *
 * The watcher lives and dies with Gboard's process. A screenshot taken while Gboard is not
 * running is not seen live — it is picked up by [catchUp] the next time the keyboard
 * starts, which is necessarily before the user can open the vault to look for it. Entries
 * carry the capture time from MediaStore, not the ingest time, so a caught-up batch still
 * sorts where it belongs.
 *
 * Everything here is fail-quiet. A revoked permission, a missing column, a vanished file
 * or a full disk all end in a log line and no entry.
 */
class ClipboardScreenshotWatcher(
    private val ctx: Context,
    private val sink: Sink
) {

    /** Where an ingested screenshot goes. Implemented by the keyboard hook. */
    interface Sink {
        /**
         * @param uri          readable handle to the screenshot bytes.
         * @param identity     stable identity of the underlying file, for cross-route dedup.
         * @param capturedAtMs when the screenshot was taken, in wall-clock millis.
         */
        fun onScreenshot(uri: Uri, identity: String, capturedAtMs: Long)

        /** False while the feature is toggled off, so a late event does nothing. */
        fun screenshotCaptureEnabled(): Boolean
    }

    companion object {
        const val TAG = "AutoExpandShot"

        /** Below this, ingesting would risk filling the disk the host process writes to. */
        const val MIN_FREE_BYTES = 50L * 1024 * 1024

        /** Newest-first cap on a single catch-up pass, so a long absence cannot stall boot. */
        private const val CATCHUP_LIMIT = 20

        /** Recently ingested file identities, so two attached routes cannot double-insert. */
        private const val SEEN_CAP = 64

        /**
         * Slack on the file route's "is this new?" test, covering a screenshot already being
         * written when the watcher attached and any clock skew between the file and us.
         */
        private const val STALE_GRACE_MS = 10_000L

        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )

        /**
         * Directory-based, because a directory segment is not localised the way
         * `bucket_display_name` can be. Both known layouts are matched: OxygenOS writes
         * `Pictures/Screenshots/`, stock AOSP and several other ROMs use `DCIM/Screenshots/`.
         */
        private const val SELECTION =
            "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?) AND " +
                "${MediaStore.Images.Media._ID} > ?"

        private fun selectionArgs(since: Long) =
            arrayOf("%Screenshots/%", "Screenshot%", since.toString())

        /** Candidate directories for the fallback route, in the order they are tried. */
        fun screenshotDirs(): List<File> = try {
            listOf(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Screenshots")
            )
        } catch (_: Throwable) {
            emptyList()
        }

        /** A screenshot the system has finished writing — not a partial or a sidecar. */
        fun looksLikeScreenshotFile(name: String): Boolean {
            val n = name.lowercase()
            if (n.startsWith(".") || n.endsWith(".tmp") || n.endsWith(".pending")) return false
            return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")
        }
    }

    /** Candidate routes, highest priority first. */
    enum class Route { MEDIA_STORE, FILE_OBSERVER }

    @Volatile var verbose = false

    private val attached = linkedSetOf<Route>()
    @Volatile private var attachedAtMs = Long.MAX_VALUE
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var observer: ContentObserver? = null
    private val fileObservers = mutableListOf<FileObserver>()

    /** Guarded by itself; access is from the watcher thread and from the sink's executor. */
    private val seen = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > SEEN_CAP
    }

    /**
     * Optional second sink for the same lines. Some builds filter app-level debug output,
     * and the evidence trail is worth more than the tidiness of a single channel; the hook
     * points this at the module's own log.
     */
    @Volatile var mirror: ((String) -> Unit)? = null

    private fun d(msg: String) {
        try { Log.d(TAG, msg) } catch (_: Throwable) {}
        try { mirror?.invoke(msg) } catch (_: Throwable) {}
    }
    private fun v(msg: String) { if (verbose) d(msg) }

    val attachedRoutes: Set<Route> get() = attached

    /**
     * The watermark lives in the vault database, not here — see
     * [ClipboardDatabase.screenshotWatermark]. It is reached through these so the watcher
     * stays free of any database dependency and can be unit-tested on its own.
     */
    @Volatile private var watermarkGetter: (() -> Long)? = null
    @Volatile private var watermarkSetter: ((Long) -> Unit)? = null

    fun bindWatermark(get: () -> Long, set: (Long) -> Unit) {
        watermarkGetter = get
        watermarkSetter = set
    }

    // ─────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────

    /**
     * Try every route in priority order and keep each one that attaches.
     *
     * Both are kept rather than only the best, because a route can attach and then stop
     * delivering (a ROM that never notifies MediaStore, a directory that moves). Duplicates
     * that result are collapsed by file identity in [offer], which is what makes running
     * them together safe.
     *
     * @return true if at least one route attached.
     */
    @Synchronized
    fun start(): Boolean {
        if (attached.isNotEmpty()) return true
        try {
            val t = HandlerThread("ae-shot").apply { start() }
            thread = t
            handler = Handler(t.looper)
        } catch (t: Throwable) {
            d("attach failed: no worker thread (${t.message})")
            return false
        }

        attachedAtMs = System.currentTimeMillis()
        attachMediaStore()
        attachFileObserver()

        if (attached.isEmpty()) {
            // The one line the task asked for: unambiguous, and the module stays functional.
            d("no route attached — screenshot capture unavailable on this build")
            stop()
            return false
        }
        d("attached routes=${attached.joinToString(",")}")

        // First ever enable: start from now instead of importing the whole gallery.
        if (currentWatermark() <= 0L) {
            val newest = newestScreenshotId()
            watermarkSetter?.invoke(newest)
            d("watermark armed at _id=$newest (no back-fill of existing screenshots)")
        }
        return true
    }

    @Synchronized
    fun stop() {
        try { observer?.let { ctx.contentResolver.unregisterContentObserver(it) } } catch (_: Throwable) {}
        observer = null
        for (fo in fileObservers) try { fo.stopWatching() } catch (_: Throwable) {}
        fileObservers.clear()
        try { thread?.quitSafely() } catch (_: Throwable) {}
        thread = null
        handler = null
        if (attached.isNotEmpty()) d("routes detached")
        attached.clear()
        attachedAtMs = Long.MAX_VALUE
        synchronized(seen) { seen.clear() }
    }

    // ─────────────────────────────────────────────────────
    // Route 1 — MediaStore observer (preferred)
    // ─────────────────────────────────────────────────────

    private fun attachMediaStore() {
        try {
            // Probe before claiming the route: a revoked READ_MEDIA_IMAGES throws here, and
            // registering an observer that can never read anything is worse than no route.
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID), null, null,
                "${MediaStore.Images.Media._ID} DESC LIMIT 1"
            )?.use { /* readable */ } ?: run {
                d("route MEDIA_STORE unavailable: query returned no cursor")
                return
            }

            val obs = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    v("MEDIA_STORE onChange uri=$uri")
                    scanSince()
                }
            }
            ctx.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs
            )
            observer = obs
            attached += Route.MEDIA_STORE
        } catch (t: Throwable) {
            d("route MEDIA_STORE failed to attach: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────
    // Route 2 — directory observer (fallback)
    // ─────────────────────────────────────────────────────

    private fun attachFileObserver() {
        val dirs = screenshotDirs().filter {
            try { it.isDirectory && it.canRead() } catch (_: Throwable) { false }
        }
        if (dirs.isEmpty()) {
            d("route FILE_OBSERVER unavailable: no readable screenshot directory")
            return
        }
        for (dir in dirs) {
            try {
                @Suppress("DEPRECATION")
                val fo = object : FileObserver(dir.absolutePath, CLOSE_WRITE or MOVED_TO) {
                    override fun onEvent(event: Int, path: String?) {
                        val name = path ?: return
                        if (!looksLikeScreenshotFile(name)) return
                        val f = File(dir, name)
                        v("FILE_OBSERVER event=$event ${f.absolutePath}")
                        handler?.post { offerFile(f) }
                    }
                }
                fo.startWatching()
                fileObservers += fo
                attached += Route.FILE_OBSERVER
                v("FILE_OBSERVER watching ${dir.absolutePath}")
            } catch (t: Throwable) {
                d("route FILE_OBSERVER failed on ${dir.absolutePath}: ${t.message}")
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // Scanning
    // ─────────────────────────────────────────────────────

    /**
     * Catch up on anything that landed while this process was not running. Safe to call on
     * every keyboard start: it is one indexed query and, in the common case, zero rows.
     */
    fun catchUp() {
        handler?.post { scanSince() } ?: scanSince()
    }

    private fun scanSince(explicit: Long? = null) {
        if (!sink.screenshotCaptureEnabled()) return
        val since = explicit ?: currentWatermark()
        if (since < 0L) return
        try {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                PROJECTION, SELECTION, selectionArgs(since),
                "${MediaStore.Images.Media._ID} ASC LIMIT $CATCHUP_LIMIT"
            )?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.Images.Media._ID)
                val dataCol = c.getColumnIndex(MediaStore.Images.Media.DATA)
                val nameCol = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                if (idCol < 0) return
                var found = 0
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    // `_data` is deprecated and may come back null or redacted; the row id
                    // is then the identity, which is still stable per file.
                    val data = if (dataCol >= 0) runCatching { c.getString(dataCol) }.getOrNull() else null
                    val name = if (nameCol >= 0) runCatching { c.getString(nameCol) }.getOrNull() else null
                    val secs = if (dateCol >= 0) runCatching { c.getLong(dateCol) }.getOrNull() ?: 0L else 0L
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    found++
                    offer(
                        uri = uri,
                        identity = data ?: "media:$id",
                        capturedAtMs = if (secs > 0L) secs * 1000L else System.currentTimeMillis(),
                        label = name ?: "id=$id",
                        mediaId = id
                    )
                }
                v("scan since _id=$since found=$found")
            } ?: v("scan since _id=$since: no cursor")
        } catch (t: Throwable) {
            d("scan failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Fallback-route entry point: a raw file with no MediaStore row to lean on.
     *
     * The MediaStore route has a persisted `_id` watermark; this one has nothing equivalent,
     * so it needs its own guard against old files. `CLOSE_WRITE` and `MOVED_TO` fire on
     * existing screenshots too — a media rescan, a gallery edit, a backup agent touching the
     * directory — and without this an unrelated rescan would import screenshots from weeks
     * ago. Anything last modified before the watcher attached is not a new screenshot.
     */
    private fun offerFile(f: File) {
        if (!sink.screenshotCaptureEnabled()) return
        try {
            if (!f.isFile || f.length() <= 0L) return
            val modified = f.lastModified()
            if (modified in 1 until (attachedAtMs - STALE_GRACE_MS)) {
                v("skipped ${f.name}: written before the watcher attached (rescan, not a new screenshot)")
                return
            }
            offer(
                uri = Uri.fromFile(f),
                identity = f.absolutePath,
                capturedAtMs = modified.takeIf { it > 0L } ?: System.currentTimeMillis(),
                label = f.name,
                mediaId = -1L
            )
        } catch (t: Throwable) {
            d("file offer failed: ${t.message}")
        }
    }

    /**
     * The single funnel both routes go through, so "exactly one entry per screenshot" is a
     * property of one piece of code rather than of route bookkeeping.
     */
    private fun offer(uri: Uri, identity: String, capturedAtMs: Long, label: String, mediaId: Long) {
        val fresh = synchronized(seen) { seen.put(identity, true) == null }
        if (!fresh) {
            v("skipped $label: already ingested by another route")
            return
        }
        if (freeBytes() < MIN_FREE_BYTES) {
            d("skipped $label: less than ${MIN_FREE_BYTES / (1024 * 1024)} MB free")
            // Do not keep it in `seen`: a retry after the user frees space should work.
            synchronized(seen) { seen.remove(identity) }
            return
        }
        d("captured $label (${uri.scheme}) at $capturedAtMs")
        sink.onScreenshot(uri, identity, capturedAtMs)
        if (mediaId > 0L) advanceWatermark(mediaId)
    }

    // ─────────────────────────────────────────────────────
    // Watermark plumbing — owned by the sink's database
    // ─────────────────────────────────────────────────────

    private fun currentWatermark(): Long = try {
        watermarkGetter?.invoke() ?: -1L
    } catch (_: Throwable) {
        -1L
    }

    private fun advanceWatermark(id: Long) {
        try {
            if (id > currentWatermark()) watermarkSetter?.invoke(id)
        } catch (_: Throwable) {
        }
    }

    private fun newestScreenshotId(): Long = try {
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), SELECTION, selectionArgs(0L),
            "${MediaStore.Images.Media._ID} DESC LIMIT 1"
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
    } catch (_: Throwable) {
        0L
    }

    private fun freeBytes(): Long = try {
        android.os.StatFs(ctx.filesDir.absolutePath).availableBytes
    } catch (_: Throwable) {
        Long.MAX_VALUE   // unknown is not a reason to refuse
    }
}
