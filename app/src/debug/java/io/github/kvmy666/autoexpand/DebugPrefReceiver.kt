package io.github.kvmy666.autoexpand

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only remote control for prefs.
 *
 * Lives in `src/debug`, so it does not exist in release builds at all — no exported receiver
 * ships to users.
 *
 * Why it exists: the engine and probe switches have to be flippable from the host during a
 * test run. Patching `Settings.Global` directly is not durable, because any call to
 * `MainActivity.writePrefsFile` republishes the blob from SharedPreferences and silently drops
 * keys that were never written there. Writing the real SharedPreferences and then republishing
 * is the only change that sticks.
 *
 * Usage:
 *   adb shell am broadcast -a io.github.kvmy666.autoexpand.SET_PREF \
 *       -n io.github.kvmy666.autoexpand/.DebugPrefReceiver \
 *       --es key notif_engine_v2 --es value 1
 *
 * `type` may be bool (default), string or int.
 */
class DebugPrefReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_POST_TEST   -> return postTest(context, intent)
            ACTION_CANCEL_TEST -> return cancelTest(context)
            ACTION -> Unit
            else -> return
        }
        val key = intent.getStringExtra("key") ?: return
        val value = intent.getStringExtra("value") ?: return
        val type = intent.getStringExtra("type") ?: "bool"
        try {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                when (type) {
                    "string" -> putString(key, value)
                    "int"    -> putInt(key, value.toIntOrNull() ?: 0)
                    else     -> putBoolean(key, value == "1" || value.equals("true", true))
                }
            }.apply()
            MainActivity.makePrefsWorldReadable(context)
            MainActivity.writePrefsFile(context)
            Log.d("AutoExpand", "DebugPrefReceiver: $key = $value ($type) — republished")
        } catch (t: Throwable) {
            Log.e("AutoExpand", "DebugPrefReceiver failed: $t")
        }
    }

    /**
     * Fires a [TestNotifier] shape from the host, so grouped/lock-screen cases can be exercised
     * without physically touching the phone — the lock-screen ones are impossible otherwise,
     * since posting them requires the app to be backgrounded behind the keyguard.
     *
     *   adb shell am broadcast -a io.github.kvmy666.autoexpand.POST_TEST \
     *       -n io.github.kvmy666.autoexpand/.DebugPrefReceiver --es kind Group --es delay 0
     */
    private fun postTest(context: Context, intent: Intent) {
        val name = intent.getStringExtra("kind") ?: "Group"
        val delay = intent.getStringExtra("delay")?.toLongOrNull() ?: 0L
        val kind = TestNotifier.Kind.entries.firstOrNull { it.name.equals(name, true) }
        if (kind == null) {
            Log.e("AutoExpand", "POST_TEST: unknown kind '$name' — have ${TestNotifier.Kind.entries.map { it.name }}")
            return
        }
        TestNotifier.post(context, kind, delay)
        Log.d("AutoExpand", "POST_TEST: ${kind.name} in ${delay}ms")
    }

    private fun cancelTest(context: Context) {
        TestNotifier.cancelAll(context)
        Log.d("AutoExpand", "CANCEL_TEST: cleared")
    }

    private companion object {
        const val ACTION = "io.github.kvmy666.autoexpand.SET_PREF"
        const val ACTION_POST_TEST = "io.github.kvmy666.autoexpand.POST_TEST"
        const val ACTION_CANCEL_TEST = "io.github.kvmy666.autoexpand.CANCEL_TEST"
    }
}
