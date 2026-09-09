package io.github.kvmy666.autoexpand

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Clipboard vault store. Lives in **Gboard's** data dir
 * (`/data/data/com.google.android.inputmethod.latin/databases/clipboard.db`) because
 * [KeyboardHook] builds it from `ims.applicationContext` and the hook runs inside the
 * Gboard process. The module's own app process cannot reach it.
 *
 * ## Schema versioning — read before touching the constructor
 *
 * The version stays **1 forever**. New columns are added by [ensureColumns], an idempotent
 * `ALTER TABLE ADD COLUMN` pass driven by `PRAGMA table_info`, run on every open.
 *
 * Bumping the version instead would be a data-loss event: [onUpgrade] historically did
 * `DROP TABLE` + recreate. That is now a no-op that repairs columns rather than dropping,
 * but the version still must not move, for a second reason — **downgrade**. An older build
 * (3.2.x) opening a version-2 file hits `SQLiteOpenHelper`'s default `onDowngrade`, which
 * throws. Staying at 1 means old builds open the file normally and simply ignore the extra
 * columns, because their `getAll` selects an explicit column list and their `insert` uses
 * `ContentValues`. The only visible downgrade artifact is that an old build shows entries
 * that are soft-deleted-but-not-yet-committed, since it has no `deleted_at` filter.
 */
class ClipboardDatabase(
    context: Context,
    private var maxEntries: Int = 500
) : SQLiteOpenHelper(context, "clipboard.db", null, 1) {

    data class Entry(
        val id: Long,
        val text: String,
        val timestamp: Long,
        val isPinned: Boolean,
        val isFavorite: Boolean,
        // ── image fields; all inert for text entries ──
        val kind: Int = KIND_TEXT,
        val imageHash: String? = null,
        val thumbPath: String? = null,
        val fullPath: String? = null,
        val imgW: Int = 0,
        val imgH: Int = 0,
        val imgBytes: Long = 0L,
        val isAnimated: Boolean = false,
        val source: Int = SRC_CLIPBOARD
    ) {
        val isImage: Boolean get() = kind == KIND_IMAGE
        val isScreenshot: Boolean get() = kind == KIND_IMAGE && source == SRC_SCREENSHOT
    }

    companion object {
        const val KIND_TEXT = 0
        const val KIND_IMAGE = 1

        /**
         * Where an image entry came from. Additive and defaulted, so every row written by
         * 3.3.x or by the first image release reads back as [SRC_CLIPBOARD] — which is what
         * it was.
         */
        const val SRC_CLIPBOARD = 0
        const val SRC_SCREENSHOT = 1

        private const val TABLE = "clipboard_entries"
        private const val META = "ae_meta"

        private const val COL_ID = "id"
        private const val COL_TEXT = "text"
        private const val COL_TS = "timestamp"
        private const val COL_PINNED = "is_pinned"
        private const val COL_FAV = "is_favorite"

        // Added in 3.4.0. Every one of these is nullable or carries a DEFAULT, so an
        // ALTER TABLE onto a populated 3.2.x table cannot fail and cannot rewrite a row.
        private const val COL_KIND = "kind"
        private const val COL_HASH = "image_hash"
        private const val COL_THUMB = "thumb_path"
        private const val COL_FULL = "full_path"
        private const val COL_W = "img_w"
        private const val COL_H = "img_h"
        private const val COL_BYTES = "img_bytes"
        private const val COL_ANIM = "is_animated"
        private const val COL_DELETED = "deleted_at"

        // Added in 3.4.0 alongside screenshot auto-capture. Same additive contract.
        private const val COL_SRC = "src"

        const val META_PENDING_DEADLINE = "pending_delete_deadline"
        const val META_PENDING_KIND = "pending_delete_kind"
        const val META_SHOT_WATERMARK = "screenshot_watermark"

        /** column name → DDL fragment, applied additively by [ensureColumns]. */
        private val ADDED_COLUMNS = linkedMapOf(
            COL_KIND to "INTEGER NOT NULL DEFAULT $KIND_TEXT",
            COL_HASH to "TEXT",
            COL_THUMB to "TEXT",
            COL_FULL to "TEXT",
            COL_W to "INTEGER NOT NULL DEFAULT 0",
            COL_H to "INTEGER NOT NULL DEFAULT 0",
            COL_BYTES to "INTEGER NOT NULL DEFAULT 0",
            COL_ANIM to "INTEGER NOT NULL DEFAULT 0",
            COL_DELETED to "INTEGER NOT NULL DEFAULT 0",
            COL_SRC to "INTEGER NOT NULL DEFAULT $SRC_CLIPBOARD"
        )

        private const val SELECT_COLS =
            "$COL_ID, $COL_TEXT, $COL_TS, $COL_PINNED, $COL_FAV, " +
            "$COL_KIND, $COL_HASH, $COL_THUMB, $COL_FULL, $COL_W, $COL_H, $COL_BYTES, $COL_ANIM, $COL_SRC"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Fresh install: the base table is created exactly as 3.2.x created it, then
        // ensureColumns adds the rest. Keeping the base DDL byte-identical means a fresh
        // 3.4.0 file and an upgraded 3.2.x file converge on the same shape.
        db.execSQL(
            """CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TEXT TEXT NOT NULL,
                $COL_TS INTEGER NOT NULL,
                $COL_PINNED INTEGER NOT NULL DEFAULT 0,
                $COL_FAV INTEGER NOT NULL DEFAULT 0
            )"""
        )
        ensureColumns(db)
    }

    /**
     * Never destructive. The version is pinned at 1 so this should never run; if a future
     * change does move it, repairing columns is the only safe behaviour. The historical
     * `DROP TABLE` here would have erased every saved entry.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        ensureColumns(db)
    }

    /** Tolerate a file written by a newer build rather than throwing (the default). */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        ensureColumns(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        ensureColumns(db)
    }

    /**
     * Idempotent additive migration. Reads the live column set and adds only what is
     * missing. Safe to call on every open, safe to call concurrently (a lost race just
     * throws "duplicate column name", which we swallow).
     */
    private fun ensureColumns(db: SQLiteDatabase) {
        try {
            val present = mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info($TABLE)", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) present.add(c.getString(nameIdx))
            }
            // An empty set means the table does not exist yet; onCreate will handle it.
            if (present.isEmpty()) return
            for ((col, ddl) in ADDED_COLUMNS) {
                if (col in present) continue
                try {
                    db.execSQL("ALTER TABLE $TABLE ADD COLUMN $col $ddl")
                } catch (_: Throwable) {
                    // Duplicate column from a concurrent opener — already what we wanted.
                }
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS $META (k TEXT PRIMARY KEY, v TEXT)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ae_hash ON $TABLE($COL_HASH)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ae_kind ON $TABLE($COL_KIND, $COL_DELETED)")
        } catch (_: Throwable) {
            // Never throw into the Gboard process. A failed migration degrades to
            // text-only behaviour; the image paths all null-check before use.
        }
    }

    // ─────────────────────────────────────────────────────
    // Insert
    // ─────────────────────────────────────────────────────

    fun insert(text: String) {
        val db = writableDatabase
        // Dedup: skip if identical to the most recent TEXT entry.
        val cursor = db.rawQuery(
            "SELECT $COL_TEXT FROM $TABLE WHERE $COL_KIND = $KIND_TEXT AND $COL_DELETED = 0 " +
                "ORDER BY $COL_TS DESC LIMIT 1",
            null
        )
        val lastText = if (cursor.moveToFirst()) cursor.getString(0) else null
        cursor.close()
        if (lastText == text) return

        val values = ContentValues().apply {
            put(COL_TEXT, text)
            put(COL_TS, System.currentTimeMillis())
            put(COL_PINNED, 0)
            put(COL_FAV, 0)
            put(COL_KIND, KIND_TEXT)
        }
        db.insert(TABLE, null, values)
        // Unlimited text history — entries are never auto-pruned.
    }

    /**
     * Insert an image entry. The bytes are already on disk (see `ClipboardImageStore`);
     * this only records the row. Returns the new row id, or -1.
     *
     * Dedup is by content hash: copying the same image twice creates a second *entry*
     * pointing at the same *files*, so the row count reflects what the user did while the
     * bytes are stored once. [hashRefCount] is what decides when bytes may go.
     */
    /**
     * @param source        [SRC_CLIPBOARD] or [SRC_SCREENSHOT]; purely a badge, never a filter
     *                      for eviction or deletion.
     * @param timestampMs   when the image came into existence. The clipboard path passes now;
     *                      the screenshot path passes the capture time off MediaStore so a
     *                      batch caught up after the process restarts still sorts honestly.
     * @param skipIfSameAsLast
     *                      guards the clipboard path, where one copy can fire the listener
     *                      twice. The screenshot path passes false: it is already
     *                      de-duplicated by a monotonic MediaStore watermark, and two
     *                      screenshots of a motionless screen are byte-identical yet are
     *                      genuinely two screenshots.
     */
    fun insertImage(
        hash: String,
        thumbPath: String,
        fullPath: String,
        width: Int,
        height: Int,
        bytes: Long,
        animated: Boolean,
        source: Int = SRC_CLIPBOARD,
        timestampMs: Long = System.currentTimeMillis(),
        skipIfSameAsLast: Boolean = true
    ): Long {
        val db = writableDatabase
        // Skip only if the most recent live entry is the very same image — mirrors the
        // text path, and stops a single copy that fires the listener twice from doubling.
        if (skipIfSameAsLast) {
            db.rawQuery(
                "SELECT $COL_HASH FROM $TABLE WHERE $COL_DELETED = 0 ORDER BY $COL_TS DESC LIMIT 1",
                null
            ).use { c -> if (c.moveToFirst() && c.getString(0) == hash) return -1L }
        }

        val values = ContentValues().apply {
            put(COL_TEXT, "")               // NOT NULL in the 3.2.x schema
            put(COL_TS, timestampMs)
            put(COL_PINNED, 0)
            put(COL_FAV, 0)
            put(COL_KIND, KIND_IMAGE)
            put(COL_HASH, hash)
            put(COL_THUMB, thumbPath)
            put(COL_FULL, fullPath)
            put(COL_W, width)
            put(COL_H, height)
            put(COL_BYTES, bytes)
            put(COL_ANIM, if (animated) 1 else 0)
            put(COL_SRC, source)
        }
        return db.insert(TABLE, null, values)
    }

    // ─────────────────────────────────────────────────────
    // Read
    // ─────────────────────────────────────────────────────

    enum class SortMode { NEWEST, OLDEST, PINNED_FIRST, FAVORITES_FIRST }

    fun getAll(sort: SortMode = SortMode.NEWEST, favoritesOnly: Boolean = false): List<Entry> {
        val order = when (sort) {
            SortMode.NEWEST          -> "$COL_PINNED DESC, $COL_TS DESC"
            SortMode.OLDEST          -> "$COL_PINNED DESC, $COL_TS ASC"
            SortMode.PINNED_FIRST    -> "$COL_PINNED DESC, $COL_FAV DESC, $COL_TS DESC"
            SortMode.FAVORITES_FIRST -> "$COL_FAV DESC, $COL_PINNED DESC, $COL_TS DESC"
        }
        val favClause = if (favoritesOnly) " AND ($COL_PINNED = 1 OR $COL_FAV = 1)" else ""
        return query("WHERE $COL_DELETED = 0$favClause ORDER BY $order")
    }

    private fun query(tail: String): List<Entry> {
        val result = mutableListOf<Entry>()
        try {
            readableDatabase.rawQuery("SELECT $SELECT_COLS FROM $TABLE $tail", null).use { c ->
                while (c.moveToNext()) {
                    result.add(
                        Entry(
                            id = c.getLong(0),
                            text = c.getString(1) ?: "",
                            timestamp = c.getLong(2),
                            isPinned = c.getInt(3) == 1,
                            isFavorite = c.getInt(4) == 1,
                            kind = c.getInt(5),
                            imageHash = c.getString(6),
                            thumbPath = c.getString(7),
                            fullPath = c.getString(8),
                            imgW = c.getInt(9),
                            imgH = c.getInt(10),
                            imgBytes = c.getLong(11),
                            isAnimated = c.getInt(12) == 1,
                            source = c.getInt(13)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
        }
        return result
    }

    /** Live image entries, oldest first — the eviction order. */
    fun imageEntriesOldestFirst(): List<Entry> =
        query("WHERE $COL_DELETED = 0 AND $COL_KIND = $KIND_IMAGE ORDER BY $COL_TS ASC")

    fun countLive(kind: Int): Int = scalarLong(
        "SELECT COUNT(*) FROM $TABLE WHERE $COL_DELETED = 0 AND $COL_KIND = $kind"
    ).toInt()

    /**
     * Total bytes actually on disk for live images — summed over **distinct hashes**, so
     * two entries sharing one file are counted once. This is the number the budget and the
     * "41.2 MB / 100 MB" readout use.
     */
    fun liveImageBytes(): Long = scalarLong(
        "SELECT COALESCE(SUM(b), 0) FROM (" +
            "SELECT MAX($COL_BYTES) AS b FROM $TABLE " +
            "WHERE $COL_DELETED = 0 AND $COL_KIND = $KIND_IMAGE AND $COL_HASH IS NOT NULL " +
            "GROUP BY $COL_HASH)"
    )

    /**
     * How many entries — live **or** pending-delete — still reference [hash]. Bytes may
     * only be removed at zero. Pending-delete rows count, because an undo must find its
     * files still there.
     */
    fun hashRefCount(hash: String): Int = scalarLong(
        "SELECT COUNT(*) FROM $TABLE WHERE $COL_HASH = ?", arrayOf(hash)
    ).toInt()

    /** Every hash referenced by any surviving row — the GC keep-set. */
    fun allReferencedHashes(): Set<String> {
        val out = mutableSetOf<String>()
        try {
            readableDatabase.rawQuery(
                "SELECT DISTINCT $COL_HASH FROM $TABLE WHERE $COL_HASH IS NOT NULL", null
            ).use { c -> while (c.moveToNext()) c.getString(0)?.let { out.add(it) } }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun scalarLong(sql: String, args: Array<String>? = null): Long = try {
        readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    } catch (_: Throwable) {
        0L
    }

    // ─────────────────────────────────────────────────────
    // Mutate
    // ─────────────────────────────────────────────────────

    fun togglePin(id: Long) {
        val db = writableDatabase
        val current = db.rawQuery("SELECT $COL_PINNED FROM $TABLE WHERE $COL_ID = ?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        db.execSQL("UPDATE $TABLE SET $COL_PINNED = ${if (current == 1) 0 else 1} WHERE $COL_ID = $id")
    }

    fun toggleFavorite(id: Long) {
        val db = writableDatabase
        val current = db.rawQuery("SELECT $COL_FAV FROM $TABLE WHERE $COL_ID = ?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        db.execSQL("UPDATE $TABLE SET $COL_FAV = ${if (current == 1) 0 else 1} WHERE $COL_ID = $id")
    }

    /**
     * Hard-delete one entry. Returns the image hash that just lost its last reference, or
     * null — the caller deletes those bytes. Text entries always return null.
     */
    fun delete(id: Long): String? {
        val db = writableDatabase
        val hash = db.rawQuery(
            "SELECT $COL_HASH FROM $TABLE WHERE $COL_ID = ?", arrayOf(id.toString())
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        db.execSQL("DELETE FROM $TABLE WHERE $COL_ID = $id")
        if (hash == null) return null
        return if (hashRefCount(hash) == 0) hash else null
    }

    /** Legacy signature kept for callers that ignore orphaned bytes. */
    fun deleteAll() {
        writableDatabase.execSQL("DELETE FROM $TABLE")
    }

    // ─────────────────────────────────────────────────────
    // Soft delete + undo
    // ─────────────────────────────────────────────────────
    //
    // Files are NEVER moved to a trash directory. Under content-hash dedup a "trash move"
    // is unsafe: the file being trashed may still back a live entry that was not deleted.
    // Instead the ROWS are tombstoned, and bytes are removed only at commit time and only
    // for hashes whose reference count has reached zero. Undo is then a pure row update,
    // which is what makes "restores the exact previous state — same files" true by
    // construction rather than by careful bookkeeping.

    /**
     * Tombstone every live entry of [kind] and arm the undo window.
     * Returns how many rows were marked. The deadline is absolute wall-clock so a process
     * death mid-countdown resolves deterministically on next open (see [resolveStalePending]).
     */
    fun softDeleteAll(kind: Int, deadlineMs: Long): Int {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val n = db.compileStatement(
                "UPDATE $TABLE SET $COL_DELETED = $now WHERE $COL_DELETED = 0 AND $COL_KIND = $kind"
            ).executeUpdateDelete()
            if (n > 0) {
                putMeta(db, META_PENDING_DEADLINE, deadlineMs.toString())
                putMeta(db, META_PENDING_KIND, kind.toString())
            }
            db.setTransactionSuccessful()
            return n
        } catch (_: Throwable) {
            return 0
        } finally {
            try { db.endTransaction() } catch (_: Throwable) {}
        }
    }

    /** Clear every tombstone and disarm. Returns rows restored. */
    fun undoPendingDelete(): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val n = db.compileStatement(
                "UPDATE $TABLE SET $COL_DELETED = 0 WHERE $COL_DELETED > 0"
            ).executeUpdateDelete()
            clearMeta(db)
            db.setTransactionSuccessful()
            return n
        } catch (_: Throwable) {
            return 0
        } finally {
            try { db.endTransaction() } catch (_: Throwable) {}
        }
    }

    /**
     * Hard-delete every tombstoned row. Returns the hashes that lost their last reference,
     * for the caller to unlink. Idempotent: a second call with nothing pending returns
     * empty, which is what makes "committed exactly once" hold across a crash.
     */
    fun commitPendingDelete(): List<String> {
        val db = writableDatabase
        val doomed = mutableListOf<String>()
        db.beginTransaction()
        try {
            // Hashes referenced ONLY by tombstoned rows lose their last reference here.
            db.rawQuery(
                "SELECT $COL_HASH FROM $TABLE WHERE $COL_DELETED > 0 AND $COL_HASH IS NOT NULL " +
                    "GROUP BY $COL_HASH HAVING COUNT(*) = (" +
                    "  SELECT COUNT(*) FROM $TABLE t2 WHERE t2.$COL_HASH = $TABLE.$COL_HASH)",
                null
            ).use { c -> while (c.moveToNext()) c.getString(0)?.let { doomed.add(it) } }

            db.execSQL("DELETE FROM $TABLE WHERE $COL_DELETED > 0")
            clearMeta(db)
            db.setTransactionSuccessful()
        } catch (_: Throwable) {
            return emptyList()
        } finally {
            try { db.endTransaction() } catch (_: Throwable) {}
        }
        return doomed
    }

    fun hasPendingDelete(): Boolean = countPending() > 0

    fun countPending(): Int = scalarLong("SELECT COUNT(*) FROM $TABLE WHERE $COL_DELETED > 0").toInt()

    fun pendingKind(): Int = getMeta(META_PENDING_KIND)?.toIntOrNull() ?: -1

    fun pendingDeadline(): Long = getMeta(META_PENDING_DEADLINE)?.toLongOrNull() ?: 0L

    /**
     * Called once per process start. A pending batch that survived a process death is
     * **committed**, never resurrected — the user asked for the delete and the countdown
     * UI that could have taken it back is gone. Returns hashes to unlink.
     */
    fun resolveStalePending(): List<String> =
        if (hasPendingDelete()) commitPendingDelete() else emptyList()

    // ─────────────────────────────────────────────────────
    // Meta
    // ─────────────────────────────────────────────────────

    private fun putMeta(db: SQLiteDatabase, k: String, v: String) {
        db.execSQL("INSERT OR REPLACE INTO $META (k, v) VALUES (?, ?)", arrayOf(k, v))
    }

    /**
     * High-water mark of the newest screenshot already ingested, as a MediaStore `_id`.
     *
     * It lives in the database rather than in prefs on purpose: it has to stay in lockstep
     * with the rows it describes, so restoring or clearing the vault cannot leave a stale
     * mark that silently swallows the next screenshots.
     *
     * 0 means "never armed" — the watcher then arms it at the current maximum instead of
     * back-filling the user's entire screenshot history on first enable.
     */
    fun screenshotWatermark(): Long = getMeta(META_SHOT_WATERMARK)?.toLongOrNull() ?: 0L

    fun setScreenshotWatermark(id: Long) {
        try {
            putMeta(writableDatabase, META_SHOT_WATERMARK, id.toString())
        } catch (_: Throwable) {
        }
    }

    private fun clearMeta(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM $META WHERE k IN (?, ?)", arrayOf(META_PENDING_DEADLINE, META_PENDING_KIND))
    }

    fun getMeta(k: String): String? = try {
        readableDatabase.rawQuery("SELECT v FROM $META WHERE k = ?", arrayOf(k)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Throwable) {
        null
    }

    // Retained for caller compatibility. History is now unlimited, so the limit
    // is recorded but never enforced (no pruning).
    fun updateMaxEntries(max: Int) {
        maxEntries = max
    }
}
