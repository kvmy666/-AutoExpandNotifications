package io.github.kvmy666.autoexpand

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A-series coverage for the store: migration from a real 3.2.x dataset, delete isolation
 * between texts and images, refcounting, and the soft-delete/undo/commit state machine.
 *
 * Robolectric supplies a genuine SQLite, so these exercise the actual SQL rather than a
 * mock. The migration test is the one that guards ~1,250 live entries on the author's
 * device: the old `onUpgrade` dropped the table, and this is what proves it no longer can.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardDatabaseTest {

    private lateinit var ctx: Context
    private var db: ClipboardDatabase? = null

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getDatabasePath("clipboard.db").let { f ->
            f.delete()
            java.io.File(f.parentFile, "clipboard.db-journal").delete()
        }
    }

    @After
    fun tearDown() {
        db?.close()
        db = null
    }

    private fun open(): ClipboardDatabase = ClipboardDatabase(ctx).also { db = it }

    /** Recreate the exact 3.2.x schema and populate it, with no image columns at all. */
    private fun seedLegacyDataset(rows: List<Triple<String, Long, Pair<Int, Int>>>) {
        val path = ctx.getDatabasePath("clipboard.db")
        path.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(path, null)
        raw.execSQL(
            """CREATE TABLE clipboard_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                is_pinned INTEGER NOT NULL DEFAULT 0,
                is_favorite INTEGER NOT NULL DEFAULT 0
            )"""
        )
        raw.version = 1
        for ((text, ts, flags) in rows) {
            raw.insert("clipboard_entries", null, ContentValues().apply {
                put("text", text); put("timestamp", ts)
                put("is_pinned", flags.first); put("is_favorite", flags.second)
            })
        }
        raw.close()
    }

    // ── A8: migration from a real 3.2.0 dataset ─────────────────────────

    @Test
    fun `legacy dataset survives the additive migration with order and pins intact`() {
        seedLegacyDataset(listOf(
            Triple("oldest", 1000L, 0 to 0),
            Triple("pinned one", 2000L, 1 to 0),
            Triple("favourite one", 3000L, 0 to 1),
            Triple("newest", 4000L, 0 to 0)
        ))

        val d = open()
        val all = d.getAll(ClipboardDatabase.SortMode.NEWEST)

        assertEquals("no entry may be lost", 4, all.size)
        // NEWEST = pinned first, then timestamp desc.
        assertEquals("pinned one", all[0].text)
        assertTrue(all[0].isPinned)
        assertEquals(listOf("pinned one", "newest", "favourite one", "oldest"), all.map { it.text })
        assertTrue(all.first { it.text == "favourite one" }.isFavorite)
        // Every legacy row is now a TEXT entry with inert image fields.
        assertTrue(all.all { it.kind == ClipboardDatabase.KIND_TEXT })
        assertTrue(all.all { it.imageHash == null })
    }

    @Test
    fun `migration is idempotent across repeated opens`() {
        seedLegacyDataset(listOf(Triple("keep me", 1000L, 1 to 1)))
        repeat(3) {
            val d = ClipboardDatabase(ctx)
            assertEquals(1, d.getAll().size)
            d.close()
        }
        val d = open()
        val e = d.getAll().single()
        assertEquals("keep me", e.text)
        assertTrue(e.isPinned)
        assertTrue(e.isFavorite)
    }

    @Test
    fun `onUpgrade no longer drops the table`() {
        seedLegacyDataset(listOf(Triple("survivor", 1L, 0 to 0)))
        val d = open()
        val raw = d.writableDatabase
        // Simulate the path that used to be catastrophic.
        d.onUpgrade(raw, 1, 2)
        assertEquals("survivor", d.getAll().single().text)
    }

    // ── A1/A2/A3: image entries, dedup, refcounting ─────────────────────

    private fun addImage(d: ClipboardDatabase, hash: String, bytes: Long = 1000L): Long =
        d.insertImage(hash, "/t/$hash", "/f/$hash", 100, 200, bytes, animated = false)

    @Test
    fun `image entry is created with its metadata`() {
        val d = open()
        val id = d.insertImage("abc", "/t/abc", "/f/abc", 1080, 1920, 240 * 1024, animated = false)
        assertTrue(id > 0)
        val e = d.getAll().single()
        assertTrue(e.isImage)
        assertEquals("abc", e.imageHash)
        assertEquals(1080, e.imgW)
        assertEquals(1920, e.imgH)
        assertEquals(240L * 1024, e.imgBytes)
    }

    @Test
    fun `same image twice yields two entries and one refcounted hash`() {
        val d = open()
        addImage(d, "dup")
        addImage(d, "other")     // break the "same as most recent" guard
        addImage(d, "dup")
        assertEquals(3, d.getAll().size)
        assertEquals(2, d.hashRefCount("dup"))
    }

    @Test
    fun `deleting one of two entries sharing a hash keeps the bytes`() {
        val d = open()
        val first = addImage(d, "shared")
        addImage(d, "spacer")
        addImage(d, "shared")

        // First delete: another reference remains, so no hash is released.
        assertNull("bytes must survive while a reference remains", d.delete(first))
        assertEquals(1, d.hashRefCount("shared"))

        // Second delete: last reference goes, so the hash is released.
        val second = d.getAll().first { it.imageHash == "shared" }.id
        assertEquals("shared", d.delete(second))
        assertEquals(0, d.hashRefCount("shared"))
    }

    @Test
    fun `byte accounting charges a shared hash once`() {
        val d = open()
        addImage(d, "same", bytes = 5000L)
        addImage(d, "spacer", bytes = 1000L)
        addImage(d, "same", bytes = 5000L)
        assertEquals(6000L, d.liveImageBytes())
    }

    // ── A10/A11: delete isolation — the highest-risk regression ─────────

    @Test
    fun `deleteAllTexts leaves every image entry untouched`() {
        val d = open()
        d.insert("text one"); d.insert("text two")
        addImage(d, "img1"); addImage(d, "img2")

        d.softDeleteAll(ClipboardDatabase.KIND_TEXT, System.currentTimeMillis() + 15_000)
        d.commitPendingDelete()

        val left = d.getAll()
        assertEquals(2, left.size)
        assertTrue("only images may remain", left.all { it.isImage })
        assertEquals(setOf("img1", "img2"), left.mapNotNull { it.imageHash }.toSet())
    }

    @Test
    fun `deleteAllImages leaves every text entry untouched`() {
        val d = open()
        d.insert("text one"); d.insert("text two")
        addImage(d, "img1"); addImage(d, "img2")

        d.softDeleteAll(ClipboardDatabase.KIND_IMAGE, System.currentTimeMillis() + 15_000)
        val released = d.commitPendingDelete()

        val left = d.getAll()
        assertEquals(2, left.size)
        assertTrue("only texts may remain", left.none { it.isImage })
        assertEquals(listOf("text two", "text one"), left.map { it.text })
        assertEquals(setOf("img1", "img2"), released.toSet())
    }

    // ── A12/A13: undo ───────────────────────────────────────────────────

    @Test
    fun `undo after deleteAllTexts restores entries order and pins`() {
        val d = open()
        d.insert("a"); d.insert("b"); d.insert("c")
        val pinned = d.getAll().first { it.text == "b" }.id
        d.togglePin(pinned)
        val before = d.getAll().map { it.id to (it.text to it.isPinned) }

        d.softDeleteAll(ClipboardDatabase.KIND_TEXT, System.currentTimeMillis() + 15_000)
        assertEquals("list must look empty during the window", 0, d.getAll().size)

        assertEquals(3, d.undoPendingDelete())
        assertEquals(before, d.getAll().map { it.id to (it.text to it.isPinned) })
        assertFalse(d.hasPendingDelete())
    }

    @Test
    fun `undo after deleteAllImages restores entries and their hashes`() {
        val d = open()
        addImage(d, "i1"); addImage(d, "i2")
        d.softDeleteAll(ClipboardDatabase.KIND_IMAGE, System.currentTimeMillis() + 15_000)
        assertEquals(0, d.getAll().size)

        // Pending rows still hold their reference, so the files were never eligible.
        assertEquals(1, d.hashRefCount("i1"))

        assertEquals(2, d.undoPendingDelete())
        assertEquals(setOf("i1", "i2"), d.getAll().mapNotNull { it.imageHash }.toSet())
    }

    // ── A14/A15/A16: the timer state machine ────────────────────────────

    @Test
    fun `commit hard-deletes and releases the bytes`() {
        val d = open()
        addImage(d, "gone")
        d.softDeleteAll(ClipboardDatabase.KIND_IMAGE, System.currentTimeMillis() + 15_000)
        val released = d.commitPendingDelete()
        assertEquals(listOf("gone"), released)
        assertEquals(0, d.getAll().size)
        assertEquals(0, d.hashRefCount("gone"))
        assertFalse(d.hasPendingDelete())
    }

    @Test
    fun `process death mid-countdown commits exactly once`() {
        val d = open()
        d.insert("doomed")
        addImage(d, "keep")
        d.softDeleteAll(ClipboardDatabase.KIND_TEXT, System.currentTimeMillis() + 15_000)
        d.close()

        // Reopen as a fresh process would.
        val d2 = ClipboardDatabase(ctx).also { db = it }
        assertTrue(d2.hasPendingDelete())
        val first = d2.resolveStalePending()
        assertTrue("text entries hold no hash", first.isEmpty())
        assertEquals("image survives", 1, d2.getAll().size)

        // Second resolve is a no-op — committed exactly once, no half state.
        assertTrue(d2.resolveStalePending().isEmpty())
        assertEquals(1, d2.getAll().size)
        assertFalse(d2.hasPendingDelete())
    }

    @Test
    fun `deadline is persisted as an absolute timestamp`() {
        val d = open()
        d.insert("x")
        val deadline = System.currentTimeMillis() + 15_000
        d.softDeleteAll(ClipboardDatabase.KIND_TEXT, deadline)
        assertEquals(deadline, d.pendingDeadline())
        assertEquals(ClipboardDatabase.KIND_TEXT, d.pendingKind())
    }

    @Test
    fun `second delete commits the first then arms its own`() {
        val d = open()
        d.insert("text")
        addImage(d, "img")

        d.softDeleteAll(ClipboardDatabase.KIND_TEXT, System.currentTimeMillis() + 15_000)
        // The caller's contract: commit the outstanding batch before arming a new one.
        val released = d.commitPendingDelete()
        assertTrue(released.isEmpty())
        d.softDeleteAll(ClipboardDatabase.KIND_IMAGE, System.currentTimeMillis() + 15_000)

        assertEquals(1, d.countPending())
        assertEquals(ClipboardDatabase.KIND_IMAGE, d.pendingKind())
        assertEquals(0, d.getAll().size)
        // Undo brings back only the second batch; the first is gone for good.
        d.undoPendingDelete()
        assertEquals(1, d.getAll().size)
        assertTrue(d.getAll().single().isImage)
    }

    // ── A17: GC keep-set ────────────────────────────────────────────────

    @Test
    fun `referenced hashes include pending-delete rows`() {
        val d = open()
        addImage(d, "live")
        addImage(d, "pending")
        d.softDeleteAll(ClipboardDatabase.KIND_IMAGE, System.currentTimeMillis() + 15_000)
        // Everything is pending now; GC must still keep both, or undo would restore
        // rows whose files had been deleted underneath them.
        assertEquals(setOf("live", "pending"), d.allReferencedHashes())
    }

    // ── A18: concurrency ────────────────────────────────────────────────

    @Test
    fun `interleaved captures and deletes leave a consistent store`() {
        val d = open()
        val pool = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(1)
        val tasks = (1..40).map { i ->
            Runnable {
                latch.await()
                if (i % 5 == 0) d.delete(i.toLong()) else {
                    if (i % 2 == 0) d.insert("t$i") else addImage(d, "h$i")
                }
            }
        }
        tasks.forEach { pool.submit(it) }
        latch.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        // The invariant that matters: the store is readable and internally consistent.
        val all = d.getAll()
        assertNotNull(all)
        assertEquals(all.size, all.map { it.id }.distinct().size)
        assertTrue(all.filter { it.isImage }.all { it.imageHash != null })
        assertEquals(all.count { it.isImage }, d.countLive(ClipboardDatabase.KIND_IMAGE))
        assertEquals(all.count { !it.isImage }, d.countLive(ClipboardDatabase.KIND_TEXT))
    }

    // ── A9: forward compatibility ───────────────────────────────────────

    @Test
    fun `unknown columns from a newer schema survive a read-modify-write`() {
        val d = open()
        d.insert("existing")
        // A future build adds a column we know nothing about.
        d.writableDatabase.execSQL("ALTER TABLE clipboard_entries ADD COLUMN future_flag TEXT")
        d.writableDatabase.execSQL("UPDATE clipboard_entries SET future_flag = 'keepme'")

        d.insert("added by this version")
        d.togglePin(d.getAll().first { it.text == "existing" }.id)

        val kept = d.readableDatabase.rawQuery(
            "SELECT future_flag FROM clipboard_entries WHERE text = 'existing'", null
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        assertEquals("unknown field must not be dropped on rewrite", "keepme", kept)
        assertEquals(2, d.getAll().size)
    }


    // ── A19: screenshot source tag + watermark ──────────────────────────

    @Test
    fun `the src column is added to a populated legacy table without touching rows`() {
        seedLegacyDataset(listOf(Triple("legacy one", 1_000L, 1 to 0)))
        val d = open()

        val cols = mutableListOf<String>()
        d.readableDatabase.rawQuery("PRAGMA table_info(clipboard_entries)", null).use { c ->
            while (c.moveToNext()) cols.add(c.getString(1))
        }
        assertTrue("src must exist after migration", cols.contains("src"))

        val row = d.getAll().single()
        assertEquals("legacy one", row.text)
        assertTrue("the legacy pin must survive", row.isPinned)
        // A text row written before this feature existed reads back as a clipboard entry,
        // which is exactly what it was.
        assertEquals(ClipboardDatabase.SRC_CLIPBOARD, row.source)
        assertFalse(row.isScreenshot)
    }

    @Test
    fun `a screenshot entry round-trips its source and its capture time`() {
        val d = open()
        val captured = 1_700_000_000_000L
        val id = d.insertImage(
            "hash-shot", "/t/a.webp", "/f/a.webp", 1272, 2772, 4_096L, false,
            ClipboardDatabase.SRC_SCREENSHOT, captured, false
        )
        assertTrue(id > 0)

        val row = d.getAll().single()
        assertTrue(row.isImage)
        assertTrue(row.isScreenshot)
        assertEquals(ClipboardDatabase.SRC_SCREENSHOT, row.source)
        assertEquals("the capture time, not the ingest time, must be stored", captured, row.timestamp)
    }

    @Test
    fun `a clipboard image still defaults to the clipboard source`() {
        val d = open()
        d.insertImage("hash-clip", "/t/b.webp", "/f/b.webp", 100, 100, 512L, false)
        val row = d.getAll().single()
        assertEquals(ClipboardDatabase.SRC_CLIPBOARD, row.source)
        assertFalse(row.isScreenshot)
    }

    @Test
    fun `two byte-identical screenshots both become entries`() {
        // The static-screen case: same bytes, genuinely two screenshots. The watcher has
        // already ruled out a real duplicate by file identity, so the row guard must not
        // collapse them.
        val d = open()
        val a = d.insertImage(
            "same", "/t/s.webp", "/f/s.webp", 10, 10, 64L, false,
            ClipboardDatabase.SRC_SCREENSHOT, 2_000L, false
        )
        val b = d.insertImage(
            "same", "/t/s.webp", "/f/s.webp", 10, 10, 64L, false,
            ClipboardDatabase.SRC_SCREENSHOT, 3_000L, false
        )
        assertTrue(a > 0)
        assertTrue(b > 0)
        assertEquals(2, d.countLive(ClipboardDatabase.KIND_IMAGE))
        // ...but they share one file, so they are charged once.
        assertEquals(64L, d.liveImageBytes())
        assertEquals(2, d.hashRefCount("same"))
    }

    @Test
    fun `a re-copied clipboard image is still collapsed`() {
        // The guard the screenshot path opts out of must stay on for the clipboard path.
        val d = open()
        assertTrue(d.insertImage("dup", "/t/d.webp", "/f/d.webp", 10, 10, 64L, false) > 0)
        assertEquals(-1L, d.insertImage("dup", "/t/d.webp", "/f/d.webp", 10, 10, 64L, false))
        assertEquals(1, d.countLive(ClipboardDatabase.KIND_IMAGE))
    }

    @Test
    fun `the screenshot watermark starts unarmed and persists`() {
        val d = open()
        assertEquals("0 means never armed — the watcher must not back-fill", 0L, d.screenshotWatermark())
        d.setScreenshotWatermark(63275L)
        assertEquals(63275L, d.screenshotWatermark())

        d.close()
        val reopened = ClipboardDatabase(ctx).also { db = it }
        assertEquals("must survive a process restart", 63275L, reopened.screenshotWatermark())
    }

    @Test
    fun `deleting all images removes screenshots too and leaves texts alone`() {
        // The highest-risk regression, re-checked now that images have two sources.
        val d = open()
        d.insert("a card number")
        d.insertImage("clip", "/t/c.webp", "/f/c.webp", 10, 10, 64L, false)
        d.insertImage(
            "shot", "/t/h.webp", "/f/h.webp", 10, 10, 64L, false,
            ClipboardDatabase.SRC_SCREENSHOT, 5_000L, false
        )
        assertEquals(2, d.countLive(ClipboardDatabase.KIND_IMAGE))

        d.softDeleteAll(ClipboardDatabase.KIND_IMAGE, System.currentTimeMillis() + 15_000L)
        d.commitPendingDelete()

        assertEquals(0, d.countLive(ClipboardDatabase.KIND_IMAGE))
        assertEquals(1, d.countLive(ClipboardDatabase.KIND_TEXT))
        assertEquals("a card number", d.getAll().single().text)
    }
}
