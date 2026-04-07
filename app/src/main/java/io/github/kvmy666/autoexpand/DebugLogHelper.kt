package io.github.kvmy666.autoexpand

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogHelper {

    private val FILTER_KEYWORDS = listOf(
        "autoexpand", "PrefsProvider", "avc: denied", "SecurityException",
        "SystemUI", "JeezSnapper", "io.github.kvmy666", "Failed to find provider"
    )

    fun capture(context: Context): File? {
        return try {
            // 2>&1 drains stderr inline, preventing the process from blocking on a full stderr buffer
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "logcat -d -v threadtime 2>&1"))
            var totalLines = 0
            val filtered = try {
                proc.inputStream.bufferedReader().useLines { seq ->
                    seq.onEach { totalLines++ }
                        .filter { line -> FILTER_KEYWORDS.any { kw -> line.contains(kw, ignoreCase = true) } }
                        .toList()
                }
            } finally {
                proc.waitFor()
                proc.destroy()
            }

            if (totalLines == 0) return null

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(context.getExternalFilesDir(null), "autoexpand_debug_$timestamp.txt")

            outFile.bufferedWriter().use { writer ->
                writer.write("=== AutoExpand Debug Log — $timestamp ===\n")
                writer.write("Total logcat lines: $totalLines | Filtered: ${filtered.size}\n\n")
                filtered.forEach { line -> writer.write(line); writer.newLine() }
            }

            outFile
        } catch (_: Throwable) {
            null
        }
    }
}
