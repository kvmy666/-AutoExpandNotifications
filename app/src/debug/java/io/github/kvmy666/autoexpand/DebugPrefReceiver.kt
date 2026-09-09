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
            ACTION_RUN_AUDIT   -> return runAudit(context)
            ACTION_COPY_IMAGE  -> return copyTestImage(context, intent)
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
     * Run the vault audit script as root and leave its report where adb can read it.
     *
     * The app already holds root (that is how prefs are published), but `su` is not reachable
     * from the adb shell uid under KernelSU Next, and the vault lives inside Gboard's private
     * data directory — so proving "nothing was deleted or altered" needs a root reader that is
     * not the adb shell. This is it.
     *
     * The command is a fixed literal, not anything taken from the intent: a debug build must
     * not ship a receiver that runs arbitrary root commands for any app that can broadcast.
     * The script itself prints counts and metadata only, never clip text.
     *
     *   adb shell am broadcast -a io.github.kvmy666.autoexpand.RUN_AUDIT      *       -n io.github.kvmy666.autoexpand/.DebugPrefReceiver
     */
    private fun runAudit(context: Context) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-M", "-c", "sh $AUDIT_SCRIPT"))
            val ok = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            Log.d("AutoExpand", "RUN_AUDIT: finished=$ok exit=${if (ok) proc.exitValue() else -1}")
        } catch (t: Throwable) {
            Log.e("AutoExpand", "RUN_AUDIT failed: $t")
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

    /**
     * Put a generated image on the system clipboard, so the Gboard-side capture listener
     * can be exercised from the host. There is no adb command that sets an image clip, and
     * the alternative is driving Photos through its share sheet by coordinate, which breaks
     * on every UI change.
     *
     *   adb shell am broadcast -a io.github.kvmy666.autoexpand.COPY_IMAGE \
     *       -n io.github.kvmy666.autoexpand/.DebugPrefReceiver --es size 1200x800
     */
    private fun copyTestImage(context: Context, intent: Intent) {
        try {
            val spec = intent.getStringExtra("size") ?: "1200x800"
            val w = spec.substringBefore('x').toIntOrNull() ?: 1200
            val h = spec.substringAfter('x').toIntOrNull() ?: 800
            val seed = intent.getStringExtra("seed") ?: System.currentTimeMillis().toString()

            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            // Deterministic-per-seed content, so "copy the same image twice" is testable.
            val rnd = java.util.Random(seed.hashCode().toLong())
            canvas.drawColor(android.graphics.Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
            val paint = android.graphics.Paint().apply { isAntiAlias = true }
            repeat(24) {
                paint.color = android.graphics.Color.argb(
                    200, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)
                )
                canvas.drawCircle(
                    rnd.nextInt(w).toFloat(), rnd.nextInt(h).toFloat(),
                    (20 + rnd.nextInt(120)).toFloat(), paint
                )
            }

            val dir = java.io.File(context.cacheDir, "snaps").apply { mkdirs() }
            val file = java.io.File(dir, "clip_test_$seed.png")
            java.io.FileOutputStream(file).use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bmp.recycle()

            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "io.github.kvmy666.autoexpand.fileprovider", file
            )
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newUri(context.contentResolver, "image", uri)
            cm.setPrimaryClip(clip)
            Log.d("AutoExpand", "COPY_IMAGE: ${w}x${h} seed=$seed uri=$uri size=${file.length()}")
        } catch (t: Throwable) {
            Log.e("AutoExpand", "COPY_IMAGE failed: $t")
        }
    }

    private fun cancelTest(context: Context) {
        TestNotifier.cancelAll(context)
        Log.d("AutoExpand", "CANCEL_TEST: cleared")
    }

    private companion object {
        const val ACTION = "io.github.kvmy666.autoexpand.SET_PREF"
        const val ACTION_POST_TEST = "io.github.kvmy666.autoexpand.POST_TEST"
        const val ACTION_CANCEL_TEST = "io.github.kvmy666.autoexpand.CANCEL_TEST"
        const val ACTION_COPY_IMAGE = "io.github.kvmy666.autoexpand.COPY_IMAGE"
        const val ACTION_RUN_AUDIT = "io.github.kvmy666.autoexpand.RUN_AUDIT"

        /** Fixed path. Never built from intent data — see [runAudit]. */
        private const val AUDIT_SCRIPT = "/data/local/tmp/ae_audit.sh"
    }
}
