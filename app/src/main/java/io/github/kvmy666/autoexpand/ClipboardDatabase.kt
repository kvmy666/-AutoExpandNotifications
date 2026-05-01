package io.github.kvmy666.autoexpand

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ClipboardDatabase(
    context: Context,
    private var maxEntries: Int = 500
) : SQLiteOpenHelper(context, "clipboard.db", null, 1) {

    data class Entry(
        val id: Long,
        val text: String,
        val timestamp: Long,
        val isPinned: Boolean,
        val isFavorite: Boolean
    )

    companion object {
        private const val TABLE = "clipboard_entries"
        private const val COL_ID = "id"
        private const val COL_TEXT = "text"
        private const val COL_TS = "timestamp"
        private const val COL_PINNED = "is_pinned"
        private const val COL_FAV = "is_favorite"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TEXT TEXT NOT NULL,
                $COL_TS INTEGER NOT NULL,
                $COL_PINNED INTEGER NOT NULL DEFAULT 0,
                $COL_FAV INTEGER NOT NULL DEFAULT 0
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(text: String) {
        val db = writableDatabase
        // Dedup: skip if identical to most recent entry
        val cursor = db.rawQuery(
            "SELECT $COL_TEXT FROM $TABLE ORDER BY $COL_TS DESC LIMIT 1",
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
        }
        db.insert(TABLE, null, values)

        // Prune oldest non-pinned, non-favorite entries above limit
        pruneOldEntries(db)
    }

    private fun pruneOldEntries(db: SQLiteDatabase) {
        val count = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        val excess = count - maxEntries
        if (excess <= 0) return
        db.execSQL(
            """DELETE FROM $TABLE WHERE $COL_ID IN (
                SELECT $COL_ID FROM $TABLE
                WHERE $COL_PINNED = 0 AND $COL_FAV = 0
                ORDER BY $COL_TS ASC LIMIT $excess
            )"""
        )
    }

    enum class SortMode { NEWEST, OLDEST, PINNED_FIRST, FAVORITES_FIRST }

    fun getAll(sort: SortMode = SortMode.NEWEST, favoritesOnly: Boolean = false): List<Entry> {
        val order = when (sort) {
            SortMode.NEWEST          -> "$COL_PINNED DESC, $COL_TS DESC"
            SortMode.OLDEST          -> "$COL_PINNED DESC, $COL_TS ASC"
            SortMode.PINNED_FIRST    -> "$COL_PINNED DESC, $COL_FAV DESC, $COL_TS DESC"
            SortMode.FAVORITES_FIRST -> "$COL_FAV DESC, $COL_PINNED DESC, $COL_TS DESC"
        }
        val where = if (favoritesOnly) "WHERE ($COL_PINNED = 1 OR $COL_FAV = 1)" else ""
        val cursor = readableDatabase.rawQuery(
            "SELECT $COL_ID, $COL_TEXT, $COL_TS, $COL_PINNED, $COL_FAV FROM $TABLE $where ORDER BY $order",
            null
        )
        val result = mutableListOf<Entry>()
        while (cursor.moveToNext()) {
            result.add(
                Entry(
                    id = cursor.getLong(0),
                    text = cursor.getString(1),
                    timestamp = cursor.getLong(2),
                    isPinned = cursor.getInt(3) == 1,
                    isFavorite = cursor.getInt(4) == 1
                )
            )
        }
        cursor.close()
        return result
    }

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

    fun delete(id: Long) {
        writableDatabase.execSQL("DELETE FROM $TABLE WHERE $COL_ID = $id")
    }

    fun deleteAll() {
        writableDatabase.execSQL("DELETE FROM $TABLE")
    }

    fun updateMaxEntries(max: Int) {
        maxEntries = max
        pruneOldEntries(writableDatabase)
    }
}
