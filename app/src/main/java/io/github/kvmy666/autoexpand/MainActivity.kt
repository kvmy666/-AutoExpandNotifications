
package io.github.kvmy666.autoexpand

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import java.util.concurrent.TimeUnit
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.FileProvider
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

