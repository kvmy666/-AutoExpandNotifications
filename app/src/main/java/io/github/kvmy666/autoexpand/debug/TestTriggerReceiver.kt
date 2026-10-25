package io.github.kvmy666.autoexpand.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.kvmy666.autoexpand.BuildConfig

/**
 * Debug-only trigger for the test notifications.
 *
 * The Testing screen works, but driving it over adb is fragile: a stale `uiautomator` dump or a
 * scroll that lands one row off selects the wrong control (it once switched the engine to v1
 * mid-test). This receiver lets a test be fired by action and disappears from release builds.
 *
 * ```
 * adb shell am broadcast -a io.github.kvmy666.autoexpand.TEST \
 *   --es kind StaleThenFresh --el delay_ms 3000
 * ```
 */
class TestTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION) return
        val name = intent.getStringExtra("kind") ?: return
        val kind = TestNotifier.Kind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return
        TestNotifier.post(context, kind, intent.getLongExtra("delay_ms", 0L))
    }

    companion object {
        const val ACTION = "io.github.kvmy666.autoexpand.TEST"
    }
}
