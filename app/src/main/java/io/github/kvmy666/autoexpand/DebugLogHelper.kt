package io.github.kvmy666.autoexpand

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogHelper {

    private val FILTER_KEYWORDS = listOf(
        "autoexpand", "AutoExpandTrace", "PrefsProvider", "avc: denied", "SecurityException",
        "SystemUI", "Snapper", "io.github.kvmy666", "Failed to find provider",
        "Zones", "TweaksHud", "ActivityTaskManager", "ActivityManager", "absinthe", "anywhere"
    )

    fun capture(context: Context): File? {
        return try {
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

            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(context.getExternalFilesDir(null), "autoexpand_debug_$ts.txt")

            outFile.bufferedWriter().use { writer ->
                // Device + capture header
                writer.write("=== Auto Expand Debug Capture ===\n")
                writer.write("Time:    ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
                writer.write("Device:  ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
                writer.write("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
                writer.write("Logcat:  $totalLines lines total | ${filtered.size} filtered\n")
                writer.write("============================\n\n")

                // Filtered logcat
                writer.write("=== Logcat ===\n")
                filtered.forEach { line -> writer.write(line); writer.newLine() }

                // Hook dispatch diag — confirms shortcutData reached the dispatcher
                writer.write("\n=== Hook Dispatch (tweaks_hook_diag.txt) ===\n")
                appendFile(writer, "/data/local/tmp/tweaks_hook_diag.txt")

                // Legacy ActionDispatcher diag from older builds. Current Anywhere launch uses a direct deep link.
                writer.write("\n=== Legacy Dispatch Result (dispatch_diag.txt) ===\n")
                appendFile(writer, "/data/local/tmp/dispatch_diag.txt")
            }

            outFile
        } catch (_: Throwable) {
            null
        }
    }

    private fun appendFile(writer: BufferedWriter, path: String) {
        try {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                writer.write(f.readText())
                return
            }
            // Fallback: read via su (file may be root-owned)
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat '$path' 2>&1"))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(); p.destroy()
            writer.write(if (out.isNotBlank()) out else "(not found or empty)\n")
        } catch (e: Throwable) {
            writer.write("(read error: $e)\n")
        }
    }
}
