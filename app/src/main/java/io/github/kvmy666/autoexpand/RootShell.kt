package io.github.kvmy666.autoexpand

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * One-shot root command runner for the **app** process.
 *
 * Deliberately not used from hook code: `su` is not reliably on SystemUI's PATH (see the
 * KernelSU note in ActionDispatcher), and a command like `pkill com.android.systemui` issued
 * from inside SystemUI would kill its own caller mid-exec. Privileged work that targets
 * SystemUI has to originate here, in the app.
 *
 * Blocking — call it off the main thread.
 */
object RootShell {

    data class Result(val ok: Boolean, val output: String)

    fun exec(cmd: String, timeoutSeconds: Long = 8): Result {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = proc.inputStream.bufferedReader().readText() +
                      proc.errorStream.bufferedReader().readText()
            val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            val code = if (finished) proc.exitValue() else -1
            proc.destroy()
            Result(finished && code == 0, out.trim())
        } catch (t: Throwable) {
            Log.d("AutoExpand", "RootShell.exec failed: $t")
            Result(false, t.toString())
        }
    }

    fun isAvailable(): Boolean = exec("id", timeoutSeconds = 3).output.contains("uid=0")

    /**
     * Restarts SystemUI so freshly-installed hook code is loaded without a full reboot.
     * SIGTERM (not -9) lets it shut down cleanly; init restarts it automatically.
     */
    fun restartSystemUi(): Result = exec("pkill -TERM -f com.android.systemui")
}
