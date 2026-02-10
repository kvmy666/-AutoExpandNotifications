package com.autoexpand.xposed

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class PrefsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.autoexpand.xposed.prefs"
    }

    private fun prefs() = context?.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    override fun query(
        uri: Uri, projection: Array<out String>?,
        selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("key", "type", "value"))
        val prefs = prefs() ?: return cursor
        for ((key, value) in prefs.all) {
            when (value) {
                is Boolean -> cursor.addRow(arrayOf(key, "bool", if (value) "1" else "0"))
                is Set<*> -> cursor.addRow(arrayOf(key, "string_set",
                    @Suppress("UNCHECKED_CAST")
                    (value as Set<String>).joinToString("\n")))
                else -> cursor.addRow(arrayOf(key, "string", value.toString()))
            }
        }
        return cursor
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, sa: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, sa: Array<out String>?) = 0
}
