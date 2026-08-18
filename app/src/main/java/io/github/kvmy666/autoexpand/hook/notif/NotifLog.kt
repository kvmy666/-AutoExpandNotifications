package io.github.kvmy666.autoexpand.hook.notif

import android.util.Log

/**
 * Logging for the v2 engine, off by default.
 *
 * The message is a lambda so nothing is concatenated or allocated when logging is disabled —
 * this runs on paths that execute thousands of times per second. The enabled flag is a plain
 * `@Volatile` refreshed from cold events only, never a prefs read from a hot hook (the shipped
 * engine does a `Settings.Global` binder read from inside `onLayout`).
 */
object NotifLog {

    const val TAG = "AENotif"

    @Volatile var enabled = false

    inline fun d(msg: () -> String) {
        if (enabled) Log.d(TAG, msg())
    }

    /** Always logged — install/capability lines are low-volume and needed for ROM triage. */
    fun i(msg: String) = Log.d(TAG, msg)

    fun e(msg: String, t: Throwable? = null) =
        if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
}
