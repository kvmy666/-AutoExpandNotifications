
package io.github.kvmy666.autoexpand

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import android.util.Log
import java.io.File
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    companion object {
        fun isModuleActive(context: Context): Boolean {
            // Primary: monotonic heartbeat published to Settings.Global by the hook.
            // elapsedRealtime is boot-relative, so this is immune to wall-clock/NTP skew
            // (the cause of false "inactive" reports), and Settings.Global survives the
            // /data/local/tmp EACCES that breaks the file heartbeat on newer LSPosed.
            try {
                val stored = Settings.Global.getString(context.contentResolver, "ae_heartbeat")?.toLongOrNull()
                if (stored != null) {
                    val delta = SystemClock.elapsedRealtime() - stored
                    if (delta in 0..(3 * 60 * 1000L)) return true
                }
            } catch (_: Throwable) {}
            // Fallback: heartbeat file written by hook thread (works on Xiaomi HyperOS)
            try {
                val file = File("/data/local/tmp/tweaks_heartbeat")
                if (file.exists()) {
                    val ts = file.readText().trim().toLongOrNull()
                    if (ts != null && System.currentTimeMillis() - ts < 3 * 60 * 1000L) return true
                }
            } catch (_: Throwable) {}
            // Fallback: legacy Settings.Global marker (OnePlus OxygenOS)
            return try {
                val value = Settings.Global.getString(context.contentResolver, "autoexpand_active")
                if (value.isNullOrEmpty()) return false
                val markerTime = value.toLongOrNull() ?: return false
                val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
                markerTime >= bootTime - 60_000
            } catch (_: Throwable) { false }
        }

        fun writePrefsFile(context: Context) {
            try {
                val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                val json = JSONObject()
                for ((key, value) in prefs.all) {
                    @Suppress("UNCHECKED_CAST")
                    json.put(key, when (value) {
                        is Boolean -> if (value) "1" else "0"
                        is Set<*>  -> (value as Set<String>).joinToString("\n")
                        else -> value.toString()
                    })
                }
                val tmpFile = File(context.cacheDir, "tweaks_prefs_tmp.json")
                tmpFile.writeText(json.toString())
                // Primary IPC channel: Settings.Global. The legacy /data/local/tmp file is
                // unreadable from system_server / SystemUI on newer SELinux policies (EACCES),
                // so we also publish the whole prefs blob (base64, shell-safe) to a global
                // setting that those system processes CAN read. The file write is kept as a
                // fallback for devices/environments where it still works.
                val b64 = android.util.Base64.encodeToString(
                    json.toString().toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
                )
                val cmd = "cp ${tmpFile.absolutePath} /data/local/tmp/tweaks_prefs.json; " +
                          "chmod 644 /data/local/tmp/tweaks_prefs.json; " +
                          "settings put global ae_prefs_json $b64"
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                proc.waitFor()
                proc.destroy()
                Log.d("Snapper", "DIAG: prefs published (file + Settings.Global), keys=${json.length()}")
            } catch (e: Throwable) {
                Log.d("Snapper", "DIAG: writePrefsFile failed (no root?): $e")
            }
        }

        // Broadcast a single pref key/value to SystemUI and write the prefs file.
        fun broadcastPref(context: Context, key: String, value: String) {
            try {
                context.sendBroadcast(
                    Intent("io.github.kvmy666.autoexpand.PREF_CHANGED")
                        .setPackage("com.android.systemui")
                        .putExtra("key", key)
                        .putExtra("value", value)
                )
            } catch (_: Throwable) {}
            writePrefsFile(context)
        }

        fun makePrefsWorldReadable(context: Context) {
            try {
                val dataDir = File(context.applicationInfo.dataDir)
                val prefsDir = File(dataDir, "shared_prefs")
                val prefsFile = File(prefsDir, "prefs.xml")
                dataDir.setReadable(true, false)
                dataDir.setExecutable(true, false)
                prefsDir.setReadable(true, false)
                prefsDir.setExecutable(true, false)
                if (prefsFile.exists()) {
                    prefsFile.setReadable(true, false)
                }
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // Write defaults on first launch so the file exists for XSharedPreferences
        if (!prefs.contains("expand_shade_enabled")) {
            prefs.edit()
                .putBoolean("expand_shade_enabled", true)
                .putBoolean("expand_headsup_enabled", true)
                .putBoolean("expand_lockscreen_enabled", true)
                .putBoolean("disable_back_haptic_enabled", true)
                .putBoolean("disable_headsup_popup_enabled", true)
                .putBoolean("ungroup_notifications_enabled", true)
                .putStringSet("excluded_apps", emptySet())
                .apply()
        }
        // Ensure new prefs exist for upgrades
        if (!prefs.contains("disable_headsup_popup_enabled")) {
            prefs.edit().putBoolean("disable_headsup_popup_enabled", true).apply()
        }
        if (!prefs.contains("ungroup_notifications_enabled")) {
            prefs.edit().putBoolean("ungroup_notifications_enabled", true).apply()
        }
        if (!prefs.contains("keyboard_enhancer_enabled")) {
            prefs.edit()
                .putBoolean("keyboard_enhancer_enabled", true)
                .putString("toolbar_height_multiplier", "1.0")
                .putString("toolbar_button_multiplier", "1.0")
                .putString("shortcut_text_1", "")
                .putString("shortcut_text_2", "")
                .putString("clipboard_max_entries", "500")
                .putBoolean("btn_clipboard_enabled", true)
                .putBoolean("btn_selectall_enabled", true)
                .putBoolean("btn_cursor_enabled", false)   // A3: cursor-nav OFF by default
                .putBoolean("btn_trackpad_enabled", true)  // A4: trackpad stick ON by default
                .putBoolean("trackpad_haptics_enabled", true) // A2
                .putString("vibration_strength", "100")       // global haptic strength 0..100
                .putBoolean("clip_full_text_enabled", true)   // A1
                .putBoolean("undo_enabled", true)             // B
                .putBoolean("undo_button_enabled", true)
                .putBoolean("shake_undo_enabled", true)
                .putString("shake_sensitivity", "1.0")        // shake-undo trigger strength
                .putBoolean("btn_shortcut_enabled", true)
                .apply()
        }
        if (!prefs.contains("snapper_enabled")) {
            prefs.edit()
                .putBoolean("snapper_enabled", false)
                .putString("snapper_activation_method", "qs_tile")
                .putString("snapper_button_side", "right")
                .putBoolean("snapper_double_tap_dismiss", true)
                .putString("snapper_history_limit", "50")
                .apply()
        }
        if (!prefs.contains("snapper_hardware_chord_enabled")) {
            prefs.edit().putBoolean("snapper_hardware_chord_enabled", true).apply()
        }
        if (!prefs.contains("enable_snapper_entirely")) {
            prefs.edit().putBoolean("enable_snapper_entirely", true).apply()
        }
        if (!prefs.contains("zones_enabled")) {
            prefs.edit()
                .putBoolean("zones_enabled", false)
                .putInt("zones_left_width_pct",  25)
                .putInt("zones_right_width_pct", 25)
                .putString("zones_left_single_tap_action",  "no_action")
                .putString("zones_left_double_tap_action",  "no_action")
                .putString("zones_left_triple_tap_action",  "no_action")
                .putString("zones_left_long_press_action",  "no_action")
                .putString("zones_right_single_tap_action", "no_action")
                .putString("zones_right_double_tap_action", "no_action")
                .putString("zones_right_triple_tap_action", "no_action")
                .putString("zones_right_long_press_action", "no_action")
                .putString("zones_left_open_app_pkg",       "")
                .putString("zones_right_open_app_pkg",      "")
                .apply()
        }

        // Re-attach edge button if it was active before (e.g. after app restart).
        // Skipped entirely when the master switch is OFF so nothing reactivates.
        if (prefs.getBoolean("enable_snapper_entirely", true) &&
            prefs.getBoolean("snapper_enabled", false) &&
            prefs.getString("snapper_activation_method", "qs_tile") != "qs_tile"
        ) {
            startForegroundService(
                Intent(this, SnapperService::class.java).apply {
                    action = SnapperService.ACTION_SHOW_EDGE_BUTTON
                }
            )
        }

        makePrefsWorldReadable(this)
        writePrefsFile(this)

        setContent {
            MaterialTheme(colorScheme = appDarkColorScheme()) {
                SettingsScreen(prefs)
            }
        }
    }
}

