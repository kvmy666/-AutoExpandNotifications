package io.github.kvmy666.autoexpand

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs

/**
 * Coverage for the screenshot scanner's decision logic: which files count, which rows are
 * new, what the watermark does, and — the one that matters most — that a screenshot seen by
 * both attached routes still produces exactly one entry.
 *
 * The observers themselves are not exercised here; whether a given ROM notifies MediaStore
 * is not something a JVM test can answer, and it is verified on device instead (see the
 * C-series checklist and the logcat evidence in docs/screenshot-routes.md).
 *
 * [ClipboardScreenshotWatcher.catchUp] runs inline when no worker thread has been started,
 * which is what lets these run deterministically without touching a Looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardScreenshotWatcherTest {

    private lateinit var ctx: Context
    private val ingested = mutableListOf<Pair<Uri, Long>>()
    private var watermark = 0L
    private var enabled = true

    /** Rows the fake MediaStore will serve, newest last. */
    private val rows = mutableListOf<Array<Any?>>()

    private inner class FakeMedia : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?
        ): Cursor {
            val cols = projection ?: arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED
            )
            val since = selectionArgs?.lastOrNull()?.toLongOrNull() ?: 0L
            val c = MatrixCursor(cols)
            val ordered = if (sortOrder?.contains("DESC") == true) rows.reversed() else rows
            for (r in ordered) {
                if ((r[0] as Long) <= since) continue
                c.addRow(cols.map { col ->
                    when (col) {
                        MediaStore.Images.Media._ID -> r[0]
                        MediaStore.Images.Media.DATA -> r[1]
                        MediaStore.Images.Media.DISPLAY_NAME -> r[2]
                        MediaStore.Images.Media.DATE_ADDED -> r[3]
                        else -> null
                    }
                })
                if (sortOrder?.contains("LIMIT 1") == true) break
            }
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private fun row(id: Long, name: String, addedSecs: Long) = arrayOf<Any?>(
        id, "/storage/emulated/0/Pictures/Screenshots/$name", name, addedSecs
    )

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ingested.clear(); rows.clear(); watermark = 0L; enabled = true
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, FakeMedia())
        // Plenty of room, so the low-storage guard is not what a test is measuring.
        ShadowStatFs.registerStats(ctx.filesDir.absolutePath, 1_000_000, 900_000, 900_000)
    }

    private fun watcher(): ClipboardScreenshotWatcher {
        val w = ClipboardScreenshotWatcher(ctx, object : ClipboardScreenshotWatcher.Sink {
            override fun screenshotCaptureEnabled() = enabled
            override fun onScreenshot(uri: Uri, identity: String, capturedAtMs: Long) {
                ingested.add(uri to capturedAtMs)
            }
        })
        w.bindWatermark({ watermark }, { watermark = it })
        return w
    }

    // ── file filter ─────────────────────────────────────────────────────

    @Test
    fun `real screenshot files are accepted`() {
        for (n in listOf("Screenshot_2026-09-10-01-52-03-96.jpg", "shot.PNG", "a.jpeg", "b.webp")) {
            assertTrue(n, ClipboardScreenshotWatcher.looksLikeScreenshotFile(n))
        }
    }

    @Test
    fun `partial and hidden files are rejected`() {
        // A half-written file would hash to bytes that never existed as an image.
        for (n in listOf(".pending-1-Screenshot.jpg", "Screenshot.jpg.tmp", ".trashed-x.png", "notes.txt")) {
            assertFalse(n, ClipboardScreenshotWatcher.looksLikeScreenshotFile(n))
        }
    }

    // ── scanning ────────────────────────────────────────────────────────

    @Test
    fun `a new screenshot is ingested once and advances the watermark`() {
        rows += row(100L, "Screenshot_a.jpg", 1_700_000_000L)
        watermark = 99L

        watcher().catchUp()

        assertEquals(1, ingested.size)
        assertEquals(1_700_000_000_000L, ingested[0].second)
        assertEquals(100L, watermark)
    }

    @Test
    fun `rows at or below the watermark are not re-ingested`() {
        rows += row(100L, "Screenshot_a.jpg", 1_700_000_000L)
        watermark = 100L

        watcher().catchUp()

        assertTrue(ingested.isEmpty())
        assertEquals(100L, watermark)
    }

    @Test
    fun `a scan twice over does not double-insert`() {
        rows += row(100L, "Screenshot_a.jpg", 1_700_000_000L)
        val w = watcher()

        w.catchUp()
        w.catchUp()

        assertEquals(1, ingested.size)
    }

    @Test
    fun `the same file offered twice yields exactly one entry, watermark or no watermark`() {
        // Both routes resolve a screenshot to the same `_data` path, and that path is the
        // identity the funnel dedups on. Proven with the watermark deliberately unable to
        // persist, so the identity guard is the only thing standing between one entry and
        // two — which is also what happens if the meta write ever fails.
        rows += row(100L, "Screenshot_a.jpg", 1_700_000_000L)
        val w = ClipboardScreenshotWatcher(ctx, object : ClipboardScreenshotWatcher.Sink {
            override fun screenshotCaptureEnabled() = enabled
            override fun onScreenshot(uri: Uri, identity: String, capturedAtMs: Long) {
                ingested.add(uri to capturedAtMs)
            }
        })
        w.bindWatermark({ 0L }, { /* write lost */ })

        w.catchUp()
        w.catchUp()

        assertEquals(1, ingested.size)
    }

    @Test
    fun `a burst is ingested in full, in order, with no gaps`() {
        // Test case 4: fifteen rapid screenshots, none dropped, none doubled.
        for (i in 1..15) rows += row(100L + i, "Screenshot_$i.jpg", 1_700_000_000L + i)

        watcher().catchUp()

        assertEquals(15, ingested.size)
        assertEquals(115L, watermark)
        val times = ingested.map { it.second }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun `nothing is ingested while the feature is off`() {
        rows += row(100L, "Screenshot_a.jpg", 1_700_000_000L)
        enabled = false

        watcher().catchUp()

        assertTrue(ingested.isEmpty())
        assertEquals("a disabled feature must not move the watermark either", 0L, watermark)
    }

    @Test
    fun `low free storage skips the ingest and leaves it retryable`() {
        ShadowStatFs.registerStats(ctx.filesDir.absolutePath, 1_000_000, 1, 1)
        rows += row(100L, "Screenshot_a.jpg", 1_700_000_000L)
        val w = watcher()

        w.catchUp()
        assertTrue("no entry when the disk is nearly full", ingested.isEmpty())

        // Space comes back; the same screenshot must still be ingestable.
        ShadowStatFs.registerStats(ctx.filesDir.absolutePath, 1_000_000, 900_000, 900_000)
        w.catchUp()
        assertEquals(1, ingested.size)
    }

    @Test
    fun `an unarmed watermark does not back-fill an existing gallery`() {
        for (i in 1..5) rows += row(100L + i, "Screenshot_$i.jpg", 1_700_000_000L + i)
        val w = watcher()

        assertTrue(w.start())
        assertEquals("must arm at the newest existing screenshot", 105L, watermark)
        assertTrue("nothing already in the gallery may be imported", ingested.isEmpty())

        w.stop()
    }
}
