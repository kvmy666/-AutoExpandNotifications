package io.github.kvmy666.autoexpand

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Lightweight SQLite store for floated snap history.
 * Each record points to a PNG file in filesDir/snaps/history/.
 */
class SnapHistoryDb(context: Context) :
    SQLiteOpenHelper(context, "snap_history.db", null, 1) {

    data class Entry(
        val id:        Long,
        val filePath:  String,
        val timestamp: Long,
        val width:     Int,
        val height:    Int
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE snap_history (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                file_path TEXT    NOT NULL,
                timestamp INTEGER NOT NULL,
                width     INTEGER NOT NULL,
                height    INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS snap_history")
        onCreate(db)
    }

    fun insert(filePath: String, timestamp: Long, width: Int, height: Int) {
        val cv = ContentValues().apply {
            put("file_path", filePath)
            put("timestamp", timestamp)
            put("width",     width)
            put("height",    height)
        }
        writableDatabase.insert("snap_history", null, cv)
    }

    fun getAll(limit: Int = 200): List<Entry> {
        val cursor = readableDatabase.rawQuery(
            "SELECT id, file_path, timestamp, width, height FROM snap_history " +
                    "ORDER BY timestamp DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        val result = mutableListOf<Entry>()
        while (cursor.moveToNext()) {
            result.add(
                Entry(
                    id        = cursor.getLong(0),
                    filePath  = cursor.getString(1),
                    timestamp = cursor.getLong(2),
                    width     = cursor.getInt(3),
                    height    = cursor.getInt(4)
                )
            )
        }
        cursor.close()
        return result
    }

    fun delete(id: Long) {
        writableDatabase.delete("snap_history", "id = ?", arrayOf(id.toString()))
    }

    fun deleteAll() {
        writableDatabase.delete("snap_history", null, null)
    }

    /** Remove records older than [limit] most recent entries. */
    fun prune(limit: Int) {
        if (limit <= 0) return
        writableDatabase.execSQL(
            """DELETE FROM snap_history WHERE id NOT IN (
                SELECT id FROM snap_history ORDER BY timestamp DESC LIMIT $limit
            )"""
        )
    }
}
