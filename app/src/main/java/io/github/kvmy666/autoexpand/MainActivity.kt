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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.FileProvider
import java.io.File
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    companion object {
        fun isModuleActive(context: Context): Boolean {
            // Primary: heartbeat file written by hook thread (works on Xiaomi HyperOS)
            try {
                val file = File("/data/local/tmp/jeez_heartbeat")
                if (file.exists()) {
                    val ts = file.readText().trim().toLongOrNull()
                    if (ts != null && System.currentTimeMillis() - ts < 3 * 60 * 1000L) return true
                }
            } catch (_: Throwable) {}
            // Fallback: Settings.Global marker (OnePlus OxygenOS)
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
                val tmpFile = File(context.cacheDir, "jeez_prefs_tmp.json")
                tmpFile.writeText(json.toString())
                val cmd = "cp ${tmpFile.absolutePath} /data/local/tmp/jeez_prefs.json && chmod 644 /data/local/tmp/jeez_prefs.json"
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                proc.waitFor()
                proc.destroy()
                Log.d("JeezSnapper", "DIAG: prefs file written, keys=${json.length()}")
            } catch (e: Throwable) {
                Log.d("JeezSnapper", "DIAG: writePrefsFile failed (no root?): $e")
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
                .putString("shortcut_text_1", "")
                .putString("shortcut_text_2", "")
                .putString("clipboard_max_entries", "500")
                .putBoolean("btn_clipboard_enabled", true)
                .putBoolean("btn_selectall_enabled", true)
                .putBoolean("btn_cursor_enabled", true)
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

        // Re-attach edge button if it was active before (e.g. after app restart)
        if (prefs.getBoolean("snapper_enabled", false) &&
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
            val colorScheme = darkColorScheme(
                background       = Color(0xFF000000),
                surface          = Color(0xFF000000),
                surfaceVariant   = Color(0xFF1C1C1E),
                onBackground     = Color(0xFFFFFFFF),
                onSurface        = Color(0xFFFFFFFF),
                onSurfaceVariant = Color(0xFF8E8E93),
            )
            MaterialTheme(colorScheme = colorScheme) {
                SettingsScreen(prefs)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(prefs: SharedPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val rebootMsg = stringResource(R.string.reboot_required)
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // ── Navigation state ──────────────────────────────────────────────────────
    var selectedFeature by remember { mutableStateOf<String?>(null) }

    // ── Notifications state ───────────────────────────────────────────────────
    var shadeEnabled       by remember { mutableStateOf(prefs.getBoolean("expand_shade_enabled", true)) }
    var headsUpEnabled     by remember { mutableStateOf(prefs.getBoolean("expand_headsup_enabled", true)) }
    var lockscreenEnabled  by remember { mutableStateOf(prefs.getBoolean("expand_lockscreen_enabled", true)) }
    var backHapticEnabled  by remember { mutableStateOf(prefs.getBoolean("disable_back_haptic_enabled", true)) }
    var headsupPopupEnabled by remember { mutableStateOf(prefs.getBoolean("disable_headsup_popup_enabled", true)) }
    var ungroupEnabled     by remember { mutableStateOf(prefs.getBoolean("ungroup_notifications_enabled", true)) }
    var excludedCount      by remember { mutableIntStateOf(prefs.getStringSet("excluded_apps", emptySet())?.size ?: 0) }
    var headsUpMaxLines    by remember { mutableStateOf(prefs.getString("headsup_max_lines", "5") ?: "5") }

    // ── Screen Snapper state ──────────────────────────────────────────────────
    var snapperMethod        by remember { val v = prefs.getString("snapper_activation_method", "edge_button") ?: "edge_button"; mutableStateOf(if (v == "qs_tile") "edge_button" else v) }
    var snapperButtonSide    by remember { mutableStateOf(prefs.getString("snapper_button_side", "right") ?: "right") }
    var snapperDoubleTap     by remember { mutableStateOf(prefs.getBoolean("snapper_double_tap_dismiss", true)) }
    var snapperHistLimit     by remember { mutableStateOf(prefs.getString("snapper_history_limit", "50") ?: "50") }
    var snapperHardwareChord   by remember { mutableStateOf(prefs.getBoolean("snapper_hardware_chord_enabled", true)) }
    var snapperMasterEnabled   by remember { mutableStateOf(prefs.getBoolean("enable_snapper_entirely", true)) }

    // ── Status Bar Zones state ────────────────────────────────────────────────
    var zonesEnabled      by remember { mutableStateOf(prefs.getBoolean("zones_enabled", false)) }
    var zonesLeftPct      by remember { mutableIntStateOf(prefs.getInt("zones_left_width_pct",  25)) }
    var zonesRightPct     by remember { mutableIntStateOf(prefs.getInt("zones_right_width_pct", 25)) }
    var zonesPreviewActive by remember { mutableStateOf(false) }
    // 8 gesture→action strings (side_gesture_action)
    var zLSingle  by remember { mutableStateOf(prefs.getString("zones_left_single_tap_action",  "no_action") ?: "no_action") }
    var zLDouble  by remember { mutableStateOf(prefs.getString("zones_left_double_tap_action",  "no_action") ?: "no_action") }
    var zLTriple  by remember { mutableStateOf(prefs.getString("zones_left_triple_tap_action",  "no_action") ?: "no_action") }
    var zLLong    by remember { mutableStateOf(prefs.getString("zones_left_long_press_action",   "no_action") ?: "no_action") }
    var zRSingle  by remember { mutableStateOf(prefs.getString("zones_right_single_tap_action", "no_action") ?: "no_action") }
    var zRDouble  by remember { mutableStateOf(prefs.getString("zones_right_double_tap_action", "no_action") ?: "no_action") }
    var zRTriple  by remember { mutableStateOf(prefs.getString("zones_right_triple_tap_action", "no_action") ?: "no_action") }
    var zRLong    by remember { mutableStateOf(prefs.getString("zones_right_long_press_action",  "no_action") ?: "no_action") }
    var zLAppPkg  by remember { mutableStateOf(prefs.getString("zones_left_open_app_pkg",   "") ?: "") }
    var zRAppPkg  by remember { mutableStateOf(prefs.getString("zones_right_open_app_pkg",  "") ?: "") }

    // ── Keyboard Enhancer state ───────────────────────────────────────────────
    var kbEnhancerEnabled   by remember { mutableStateOf(prefs.getBoolean("keyboard_enhancer_enabled", true)) }
    var toolbarMultiplier   by remember { mutableFloatStateOf(prefs.getString("toolbar_height_multiplier", "1.0")?.toFloatOrNull() ?: 1.0f) }
    var shortcut1           by remember { mutableStateOf(prefs.getString("shortcut_text_1", "") ?: "") }
    var shortcut2           by remember { mutableStateOf(prefs.getString("shortcut_text_2", "") ?: "") }
    var clipboardMaxEntries by remember { mutableStateOf(prefs.getString("clipboard_max_entries", "500") ?: "500") }
    var btnClipboardEnabled by remember { mutableStateOf(prefs.getBoolean("btn_clipboard_enabled", true)) }
    var btnPasteEnabled     by remember { mutableStateOf(prefs.getBoolean("btn_paste_enabled", true)) }
    var btnSelectAllEnabled by remember { mutableStateOf(prefs.getBoolean("btn_selectall_enabled", true)) }
    var btnCursorEnabled    by remember { mutableStateOf(prefs.getBoolean("btn_cursor_enabled", true)) }
    var btnShortcutEnabled  by remember { mutableStateOf(prefs.getBoolean("btn_shortcut_enabled", true)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                excludedCount = prefs.getStringSet("excluded_apps", emptySet())?.size ?: 0
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun onToggle(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        MainActivity.makePrefsWorldReadable(context)
        MainActivity.broadcastPref(context, key, if (value) "1" else "0")
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(rebootMsg)
        }
    }

    fun onStringPref(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        MainActivity.makePrefsWorldReadable(context)
        MainActivity.broadcastPref(context, key, value)
    }

    BackHandler(enabled = selectedFeature != null) { selectedFeature = null }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (selectedFeature) {
                            "notifications" -> "Notifications"
                            "keyboard"      -> "Keyboard Enhancer"
                            "snapper"       -> "Screen Snapper"
                            "guide"         -> "Guide"
                            "zones"         -> "Status Bar Zones"
                            else            -> stringResource(R.string.app_name)
                        }
                    )
                },
                navigationIcon = {
                    if (selectedFeature != null) {
                        IconButton(onClick = { selectedFeature = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (selectedFeature == null) {
            val isActive = remember { MainActivity.isModuleActive(context) }
            HomeScreen(
                isActive             = isActive,
                shadeEnabled         = shadeEnabled,
                snapperMasterEnabled = snapperMasterEnabled,
                zonesEnabled         = zonesEnabled,
                kbEnhancerEnabled    = kbEnhancerEnabled,
                modifier             = Modifier.padding(padding),
                onNavigate           = { selectedFeature = it }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (selectedFeature) {

                    // ── Notifications ─────────────────────────────────────────────
                    "notifications" -> {
                        Card {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                ToggleRow(
                                    title = stringResource(R.string.expand_shade_title),
                                    description = stringResource(R.string.expand_shade_desc),
                                    checked = shadeEnabled,
                                    onCheckedChange = { shadeEnabled = it; onToggle("expand_shade_enabled", it) }
                                )
                                ToggleRow(
                                    title = stringResource(R.string.expand_headsup_title),
                                    description = stringResource(R.string.expand_headsup_desc),
                                    checked = headsUpEnabled,
                                    onCheckedChange = { headsUpEnabled = it; onToggle("expand_headsup_enabled", it) }
                                )
                                OutlinedTextField(
                                    value           = headsUpMaxLines,
                                    onValueChange   = { v ->
                                        headsUpMaxLines = v.filter { it.isDigit() }
                                        prefs.edit().putString("headsup_max_lines", headsUpMaxLines).apply()
                                        MainActivity.makePrefsWorldReadable(context)
                                    },
                                    label           = { Text(stringResource(R.string.headsup_max_lines_title)) },
                                    supportingText  = { Text(stringResource(R.string.headsup_max_lines_desc)) },
                                    modifier        = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine      = true
                                )
                                ToggleRow(
                                    title = stringResource(R.string.expand_lockscreen_title),
                                    description = stringResource(R.string.expand_lockscreen_desc),
                                    checked = lockscreenEnabled,
                                    onCheckedChange = { lockscreenEnabled = it; onToggle("expand_lockscreen_enabled", it) }
                                )
                            }
                        }

                        Card {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                ToggleRow(
                                    title = stringResource(R.string.disable_headsup_popup_title),
                                    description = stringResource(R.string.disable_headsup_popup_desc),
                                    checked = headsupPopupEnabled,
                                    onCheckedChange = { headsupPopupEnabled = it; onToggle("disable_headsup_popup_enabled", it) }
                                )
                                ToggleRow(
                                    title = stringResource(R.string.ungroup_notifications_title),
                                    description = stringResource(R.string.ungroup_notifications_desc),
                                    checked = ungroupEnabled,
                                    onCheckedChange = { ungroupEnabled = it; onToggle("ungroup_notifications_enabled", it) }
                                )
                                ToggleRow(
                                    title = stringResource(R.string.disable_back_haptic_title),
                                    description = stringResource(R.string.disable_back_haptic_desc),
                                    checked = backHapticEnabled,
                                    onCheckedChange = { backHapticEnabled = it; onToggle("disable_back_haptic_enabled", it) }
                                )
                            }
                        }

                        Card(onClick = { context.startActivity(Intent(context, AppListActivity::class.java)) }) {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(text = stringResource(R.string.excluded_apps_title), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = if (excludedCount == 0)
                                        stringResource(R.string.excluded_apps_desc_none)
                                    else
                                        stringResource(R.string.excluded_apps_desc_count, excludedCount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (BuildConfig.DEBUG) {
                            DebugCard()
                        }
                    }

                    // ── Keyboard Enhancer ─────────────────────────────────────────
                    "keyboard" -> {
                        Card {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = stringResource(R.string.keyboard_enhancer_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                ToggleRow(
                                    title = stringResource(R.string.keyboard_enhancer_enabled_title),
                                    description = stringResource(R.string.keyboard_enhancer_enabled_desc),
                                    checked = kbEnhancerEnabled,
                                    onCheckedChange = { kbEnhancerEnabled = it; onToggle("keyboard_enhancer_enabled", it) }
                                )
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    Text(
                                        text = stringResource(R.string.keyboard_toolbar_height_title) + ": ${"%.1f".format(toolbarMultiplier)}×",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = stringResource(R.string.keyboard_toolbar_height_desc),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Slider(
                                        value = toolbarMultiplier,
                                        onValueChange = { toolbarMultiplier = it },
                                        onValueChangeFinished = { onStringPref("toolbar_height_multiplier", toolbarMultiplier.toString()) },
                                        valueRange = 0.5f..2.0f,
                                        steps = 29
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.keyboard_buttons_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                ToggleRow(
                                    title = stringResource(R.string.btn_clipboard_title),
                                    description = stringResource(R.string.btn_clipboard_desc),
                                    checked = btnClipboardEnabled,
                                    onCheckedChange = { btnClipboardEnabled = it; onToggle("btn_clipboard_enabled", it) }
                                )
                                ToggleRow(
                                    title = "Paste Button",
                                    description = "Show a quick-paste button (📥) that pastes the last clipboard item",
                                    checked = btnPasteEnabled,
                                    onCheckedChange = { btnPasteEnabled = it; onToggle("btn_paste_enabled", it) }
                                )
                                ToggleRow(
                                    title = stringResource(R.string.btn_selectall_title),
                                    description = stringResource(R.string.btn_selectall_desc),
                                    checked = btnSelectAllEnabled,
                                    onCheckedChange = { btnSelectAllEnabled = it; onToggle("btn_selectall_enabled", it) }
                                )
                                ToggleRow(
                                    title = stringResource(R.string.btn_cursor_title),
                                    description = stringResource(R.string.btn_cursor_desc),
                                    checked = btnCursorEnabled,
                                    onCheckedChange = { btnCursorEnabled = it; onToggle("btn_cursor_enabled", it) }
                                )
                                ToggleRow(
                                    title = stringResource(R.string.btn_shortcut_title),
                                    description = stringResource(R.string.btn_shortcut_desc),
                                    checked = btnShortcutEnabled,
                                    onCheckedChange = { btnShortcutEnabled = it; onToggle("btn_shortcut_enabled", it) }
                                )
                                if (btnShortcutEnabled) {
                                    OutlinedTextField(
                                        value = shortcut1,
                                        onValueChange = { shortcut1 = it; onStringPref("shortcut_text_1", it) },
                                        label = { Text(stringResource(R.string.keyboard_shortcut1_title)) },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                        singleLine = false,
                                        maxLines = 3
                                    )
                                    OutlinedTextField(
                                        value = shortcut2,
                                        onValueChange = { shortcut2 = it; onStringPref("shortcut_text_2", it) },
                                        label = { Text(stringResource(R.string.keyboard_shortcut2_title)) },
                                        supportingText = { Text(stringResource(R.string.keyboard_shortcut2_desc)) },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                        singleLine = false,
                                        maxLines = 3
                                    )
                                }
                                if (btnClipboardEnabled) {
                                    OutlinedTextField(
                                        value = clipboardMaxEntries,
                                        onValueChange = {
                                            clipboardMaxEntries = it.filter { c -> c.isDigit() }
                                            onStringPref("clipboard_max_entries", clipboardMaxEntries)
                                        },
                                        label = { Text(stringResource(R.string.keyboard_clipboard_max_title)) },
                                        supportingText = { Text(stringResource(R.string.keyboard_clipboard_max_desc)) },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).padding(bottom = 8.dp),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true
                                    )
                                }
                            }
                        }
                    }

                    // ── Screen Snapper ────────────────────────────────────────────
                    "snapper" -> {
                        SnapperSettingsCard(
                            snapperMasterEnabled = snapperMasterEnabled,
                            snapperMethod        = snapperMethod,
                            snapperButtonSide    = snapperButtonSide,
                            snapperDoubleTap     = snapperDoubleTap,
                            snapperHistLimit     = snapperHistLimit,
                            onMasterEnabledChange = { v ->
                                snapperMasterEnabled = v
                                prefs.edit().putBoolean("enable_snapper_entirely", v).apply()
                                MainActivity.makePrefsWorldReadable(context)
                            },
                            onMethodChange    = { method ->
                                snapperMethod = method
                                val chordOn = method == "chord" || method == "both"
                                val edgeOn  = method == "edge_button" || method == "both"
                                prefs.edit()
                                    .putString("snapper_activation_method", method)
                                    .putBoolean("snapper_hardware_chord_enabled", chordOn)
                                    .apply()
                                MainActivity.makePrefsWorldReadable(context)
                                val svc = Intent(context, SnapperService::class.java)
                                svc.action = if (edgeOn) SnapperService.ACTION_SHOW_EDGE_BUTTON
                                             else        SnapperService.ACTION_HIDE_EDGE_BUTTON
                                context.startForegroundService(svc)
                            },
                            onSideChange      = { side ->
                                snapperButtonSide = side
                                prefs.edit().putString("snapper_button_side", side).apply()
                                if (snapperMethod == "edge_button" || snapperMethod == "both") {
                                    val svc = Intent(context, SnapperService::class.java)
                                    svc.action = SnapperService.ACTION_HIDE_EDGE_BUTTON
                                    context.startForegroundService(svc)
                                    val svc2 = Intent(context, SnapperService::class.java)
                                    svc2.action = SnapperService.ACTION_SHOW_EDGE_BUTTON
                                    context.startForegroundService(svc2)
                                }
                            },
                            onDoubleTapChange = { dt ->
                                snapperDoubleTap = dt
                                prefs.edit().putBoolean("snapper_double_tap_dismiss", dt).apply()
                            },
                            onHistLimitChange = { lim ->
                                snapperHistLimit = lim.filter { it.isDigit() }
                                prefs.edit().putString("snapper_history_limit", snapperHistLimit).apply()
                            }
                        )
                    }

                    // ── Guide ──────────────────────────────────────────────────────
                    "guide" -> { GuideScreen() }

                    // ── Status Bar Zones ───────────────────────────────────────────
                    "zones" -> {
                        ZonesTab(
                            prefs           = prefs,
                            zonesEnabled    = zonesEnabled,
                            zonesPreviewActive = zonesPreviewActive,
                            zonesLeftPct    = zonesLeftPct,
                            zonesRightPct   = zonesRightPct,
                            zLSingle = zLSingle, zLDouble = zLDouble,
                            zLTriple = zLTriple, zLLong   = zLLong,
                            zRSingle = zRSingle, zRDouble = zRDouble,
                            zRTriple = zRTriple, zRLong   = zRLong,
                            zLAppPkg = zLAppPkg, zRAppPkg = zRAppPkg,
                            onEnabledChange = { v ->
                                zonesEnabled = v
                                prefs.edit().putBoolean("zones_enabled", v).apply()
                                MainActivity.makePrefsWorldReadable(context)
                                MainActivity.broadcastPref(context, "zones_enabled", if (v) "1" else "0")
                                val svc = Intent(context, StatusBarZonesService::class.java)
                                svc.action = if (v) StatusBarZonesService.ACTION_START
                                             else   StatusBarZonesService.ACTION_STOP
                                context.startForegroundService(svc)
                            },
                            onPreviewActiveChange = { zonesPreviewActive = it },
                            onLeftPctChange  = { v ->
                                zonesLeftPct = v
                                prefs.edit().putInt("zones_left_width_pct", v).apply()
                                MainActivity.makePrefsWorldReadable(context)
                            },
                            onRightPctChange = { v ->
                                zonesRightPct = v
                                prefs.edit().putInt("zones_right_width_pct", v).apply()
                                MainActivity.makePrefsWorldReadable(context)
                            },
                            onPreviewDone = {
                                zonesPreviewActive = false
                                MainActivity.broadcastPref(context, "zones_left_width_pct",  zonesLeftPct.toString())
                                MainActivity.broadcastPref(context, "zones_right_width_pct", zonesRightPct.toString())
                                MainActivity.writePrefsFile(context)
                            },
                            onActionChange = { prefKey, value, appPkg ->
                                prefs.edit().putString(prefKey, value).apply()
                                if (appPkg != null) {
                                    val pkgKey = if (prefKey.startsWith("zones_left")) "zones_left_open_app_pkg"
                                                 else "zones_right_open_app_pkg"
                                    prefs.edit().putString(pkgKey, appPkg).apply()
                                    if (prefKey.startsWith("zones_left")) zLAppPkg = appPkg
                                    else zRAppPkg = appPkg
                                }
                                // update local state
                                when (prefKey) {
                                    "zones_left_single_tap_action"  -> zLSingle = value
                                    "zones_left_double_tap_action"  -> zLDouble = value
                                    "zones_left_triple_tap_action"  -> zLTriple = value
                                    "zones_left_long_press_action"  -> zLLong   = value
                                    "zones_right_single_tap_action" -> zRSingle = value
                                    "zones_right_double_tap_action" -> zRDouble = value
                                    "zones_right_triple_tap_action" -> zRTriple = value
                                    "zones_right_long_press_action" -> zRLong   = value
                                }
                                MainActivity.makePrefsWorldReadable(context)
                                MainActivity.writePrefsFile(context)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
}

// =============================================================================
// Status Bar Zones Tab
// =============================================================================

// File-level class — NOT inside the composable to avoid Kotlin local-class
// descriptor instability across recompositions.
private class ZonePreviewHolder {
    var left:  View? = null
    var right: View? = null
}

private fun zonePreviewParams(w: Int, h: Int, x: Int) = WindowManager.LayoutParams(
    w, h,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT
).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; y = 0 }

@Suppress("LongParameterList")
@Composable
private fun ZonesTab(
    prefs: SharedPreferences,
    zonesEnabled: Boolean,
    zonesPreviewActive: Boolean,
    zonesLeftPct: Int,
    zonesRightPct: Int,
    zLSingle: String, zLDouble: String, zLTriple: String, zLLong: String,
    zRSingle: String, zRDouble: String, zRTriple: String, zRLong: String,
    zLAppPkg: String, zRAppPkg: String,
    onEnabledChange: (Boolean) -> Unit,
    onPreviewActiveChange: (Boolean) -> Unit,
    onLeftPctChange: (Int) -> Unit,
    onRightPctChange: (Int) -> Unit,
    onPreviewDone: () -> Unit,
    onActionChange: (prefKey: String, value: String, appPkg: String?) -> Unit,
) {
    val context = LocalContext.current
    // Use application context — Activity context WindowManager can produce
    // BadTokenException on second open when the previous Activity token is stale.
    val wm = remember { context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    // Compute display geometry once
    val screenW = remember { wm.currentWindowMetrics.bounds.width() }
    val sbHeightPx = remember {
        val h = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        if (h > 0) h else {
            val rid = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (rid > 0) context.resources.getDimensionPixelSize(rid) else 80
        }
    }
    val cutout = remember { wm.currentWindowMetrics.windowInsets.displayCutout }
    // maxLeftW / maxRightW: max zone widths in px, using bounding rect for centered punch-holes
    val (maxLeftW, maxRightW) = remember {
        if (cutout == null) {
            Pair(screenW * 3 / 10, screenW * 3 / 10)
        } else {
            val topRect = cutout.boundingRects.minByOrNull { it.top }
            if (topRect != null && topRect.left > 0) {
                Pair(topRect.left, screenW - topRect.right)
            } else {
                val cl = cutout.safeInsetLeft.takeIf { it > 0 } ?: (screenW * 3 / 10)
                val cr = cutout.safeInsetRight.takeIf { it > 0 } ?: (screenW * 3 / 10)
                Pair(cl, cr)
            }
        }
    }

    // File-level holder — stable across recompositions
    val holder = remember { ZonePreviewHolder() }

    fun makePreviewView(fillColor: Int, borderColor: Int): View {
        val fill   = Paint().apply { color = fillColor;   style = Paint.Style.FILL }
        val border = Paint().apply { color = borderColor; style = Paint.Style.STROKE; strokeWidth = 4f }
        return object : View(context.applicationContext) {
            override fun onDraw(c: Canvas) {
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
                c.drawRect(2f, 2f, width - 2f, height - 2f, border)
            }
        }.apply { setLayerType(View.LAYER_TYPE_HARDWARE, null) }
    }

    // Create/destroy preview views when active changes
    DisposableEffect(zonesPreviewActive) {
        if (zonesPreviewActive) {
            if (!android.provider.Settings.canDrawOverlays(context)) {
                return@DisposableEffect onDispose {}
            }
            // Clean up any stale views from a previous session that onDispose may not have caught
            holder.left?.let  { v -> try { wm.removeView(v) } catch (_: Throwable) {} ; holder.left  = null }
            holder.right?.let { v -> try { wm.removeView(v) } catch (_: Throwable) {} ; holder.right = null }

            val lw = (screenW * zonesLeftPct  / 100f).toInt().coerceIn(1, maxLeftW.coerceAtLeast(1))
            val rw = (screenW * zonesRightPct / 100f).toInt().coerceIn(1, maxRightW.coerceAtLeast(1))
            val lv = makePreviewView(
                android.graphics.Color.argb(110, 0, 120, 255),
                android.graphics.Color.argb(220, 0, 120, 255)
            )
            val rv = makePreviewView(
                android.graphics.Color.argb(110, 0, 190, 100),
                android.graphics.Color.argb(220, 0, 190, 100)
            )
            try {
                wm.addView(lv, zonePreviewParams(lw, sbHeightPx, 0))
                holder.left = lv
            } catch (t: Throwable) { Log.e("JeezZones", "preview left addView failed: $t") }
            try {
                wm.addView(rv, zonePreviewParams(rw, sbHeightPx, screenW - rw))
                holder.right = rv
            } catch (t: Throwable) { Log.e("JeezZones", "preview right addView failed: $t") }
        }
        onDispose {
            holder.left?.let  { v -> try { wm.removeView(v) } catch (_: Throwable) {} ; holder.left  = null }
            holder.right?.let { v -> try { wm.removeView(v) } catch (_: Throwable) {} ; holder.right = null }
        }
    }

    // Resize preview views in real-time as sliders change (runs after every recomposition)
    SideEffect {
        if (!zonesPreviewActive) return@SideEffect
        val lw = (screenW * zonesLeftPct  / 100f).toInt().coerceIn(1, maxLeftW.coerceAtLeast(1))
        val rw = (screenW * zonesRightPct / 100f).toInt().coerceIn(1, maxRightW.coerceAtLeast(1))
        holder.left?.let  { v -> try { wm.updateViewLayout(v, zonePreviewParams(lw, sbHeightPx, 0)) }            catch (_: Throwable) {} }
        holder.right?.let { v -> try { wm.updateViewLayout(v, zonePreviewParams(rw, sbHeightPx, screenW - rw)) } catch (_: Throwable) {} }
    }

    // App picker ──────────────────────────────────────────────────────────────
    data class AppEntry(val name: String, val pkg: String)
    var appPickerFor by remember { mutableStateOf<String?>(null) }
    val appList      = remember { mutableStateListOf<AppEntry>() }

    LaunchedEffect(appPickerFor) {
        if (appPickerFor == null || appList.isNotEmpty()) return@LaunchedEffect
        val entries = withContext(Dispatchers.IO) {
            context.packageManager.getInstalledApplications(0)
                .filter { it.packageName != context.packageName }
                .map { AppEntry(context.packageManager.getApplicationLabel(it).toString(), it.packageName) }
                .sortedBy { it.name.lowercase() }
        }
        appList.addAll(entries)
    }

    if (appPickerFor != null) {
        AlertDialog(
            onDismissRequest = { appPickerFor = null },
            title = { Text("Pick an App") },
            text = {
                LazyColumn {
                    items(appList) { app ->
                        Text(
                            text = "${app.name}\n${app.pkg}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val key = appPickerFor ?: return@clickable
                                    onActionChange(key, "open_app", app.pkg)
                                    appPickerFor = null
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { appPickerFor = null }) { Text("Cancel") } }
        )
    }

    // Master toggle + size adjust card ────────────────────────────────────────
    Card {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text  = "Status Bar Zones",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary
            )
            ToggleRow(
                title = "Enable Status Bar Zones",
                description = "Tap zones on left and right of the camera cutout",
                checked = zonesEnabled,
                onCheckedChange = onEnabledChange
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            if (!zonesPreviewActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPreviewActiveChange(true) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Adjust Zone Sizes", style = MaterialTheme.typography.bodyLarge)
                        Text("Live colored overlay preview on status bar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("▶", color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Left zone: $zonesLeftPct%",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF0078FF))
                    Slider(
                        value = zonesLeftPct.toFloat(),
                        onValueChange = { onLeftPctChange(it.toInt().coerceIn(10, 50)) },
                        onValueChangeFinished = {},
                        valueRange = 10f..50f,
                        steps = 39
                    )
                    Text("Right zone: $zonesRightPct%",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF00BE64))
                    Slider(
                        value = zonesRightPct.toFloat(),
                        onValueChange = { onRightPctChange(it.toInt().coerceIn(10, 50)) },
                        onValueChangeFinished = {},
                        valueRange = 10f..50f,
                        steps = 39
                    )
                    Button(onClick = onPreviewDone, modifier = Modifier.fillMaxWidth()) {
                        Text("Done — Save Sizes")
                    }
                }
            }
        }
    }

    // Left Zone Actions ───────────────────────────────────────────────────────
    ZoneSideCard(
        sideLabel = "Left Zone",
        singleAction = zLSingle, doubleAction = zLDouble,
        tripleAction = zLTriple, longAction   = zLLong,
        appPkg = zLAppPkg, prefPrefix = "zones_left",
        onActionChange = onActionChange,
        onPickApp = { appPickerFor = it }
    )

    // Right Zone Actions ──────────────────────────────────────────────────────
    ZoneSideCard(
        sideLabel = "Right Zone",
        singleAction = zRSingle, doubleAction = zRDouble,
        tripleAction = zRTriple, longAction   = zRLong,
        appPkg = zRAppPkg, prefPrefix = "zones_right",
        onActionChange = onActionChange,
        onPickApp = { appPickerFor = it }
    )

    // Haptic Feedback ─────────────────────────────────────────────────────────
    var hapticEnabled by remember {
        mutableStateOf(prefs.getString("zones_haptic_enabled", "1") == "1")
    }
    Card {
        ToggleRow(
            title = "Haptic Feedback",
            description = "Vibrate when a zone action fires",
            checked = hapticEnabled,
            onCheckedChange = { v ->
                hapticEnabled = v
                val strVal = if (v) "1" else "0"
                prefs.edit().putString("zones_haptic_enabled", strVal).apply()
                MainActivity.broadcastPref(context, "zones_haptic_enabled", strVal)
                MainActivity.writePrefsFile(context)
            }
        )
    }
}

@Composable
private fun ZoneSideCard(
    sideLabel: String,
    singleAction: String, doubleAction: String,
    tripleAction: String, longAction:   String,
    appPkg: String,
    prefPrefix: String,
    onActionChange: (prefKey: String, value: String, appPkg: String?) -> Unit,
    onPickApp: (prefKey: String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text = sideLabel,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary
            )

            val gestures = listOf(
                "single_tap_action" to "Single Tap"   to singleAction,
                "double_tap_action" to "Double Tap"   to doubleAction,
                "triple_tap_action" to "Triple Tap"   to tripleAction,
                "long_press_action" to "Long Press"   to longAction,
            )

            gestures.forEachIndexed { idx, (keyLabel, currentKey) ->
                val (keySuffix, label) = keyLabel
                val prefKey = "${prefPrefix}_${keySuffix}"
                ActionDropdownRow(
                    label       = label,
                    currentKey  = currentKey,
                    appPkg      = appPkg,
                    onSelect    = { selected ->
                        if (selected == "open_app") {
                            onPickApp(prefKey)
                        } else {
                            onActionChange(prefKey, selected, null)
                        }
                    }
                )
                if (idx < gestures.size - 1) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            Spacer(modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun ActionDropdownRow(
    label: String,
    currentKey: String,
    appPkg: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = ZoneAction.ALL.firstOrNull { it.first == currentKey }?.second ?: "No Action"
    val appLabel    = if (currentKey == "open_app" && appPkg.isNotBlank()) " ($appPkg)" else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.4f))
        Row(
            modifier = Modifier.weight(0.6f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayName + appLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("▼", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ZoneAction.ALL.forEach { (key, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { expanded = false; onSelect(key) }
                )
            }
        }
    }
}

@Composable
private fun GuideScreen() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // Privacy banner
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("🔒 100% Local & Private", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "Everything in this module runs entirely on your device. No data is ever collected, " +
                    "sent to any server, or shared with anyone. No internet permission is used. " +
                    "Screenshots, clipboard contents, and all settings stay local.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Auto Expand Notifications
        GuideCard(
            title = "Auto Expand Notifications",
            sections = listOf(
                "What it does" to
                    "Automatically expands notifications to their full size as soon as they arrive — " +
                    "in the notification shade, as heads-up popups, and on the lock screen. " +
                    "No more tapping to expand.",
                "How to use" to
                    "Enable the toggles for the areas you want:\n" +
                    "• Notification Shade — expands notifications when you pull down\n" +
                    "• Heads-Up Popups — expands banners that slide in at the top\n" +
                    "• Lock Screen — expands notifications shown on the lock screen\n\n" +
                    "Use 'Excluded Apps' to skip expansion for specific apps (e.g. messaging apps " +
                    "where you want previews hidden).",
                "Other toggles" to
                    "• Disable Heads-Up Popup — blocks the banner from appearing at all (notification still arrives silently)\n" +
                    "• Ungroup Notifications — shows each notification individually instead of grouped\n" +
                    "• Disable Back Gesture Haptic — removes the vibration when you swipe back",
                "Requirements" to
                    "LSPosed/Xposed must be installed. The module must be enabled for System UI in LSPosed. " +
                    "A reboot is required after toggling any setting."
            )
        )

        // Keyboard Enhancer
        GuideCard(
            title = "Keyboard Enhancer",
            sections = listOf(
                "What it does" to
                    "Adds a toolbar row above the Gboard keyboard with quick-action buttons: " +
                    "clipboard history, paste, select all, cursor movement, and custom text shortcuts.",
                "How to use" to
                    "The toolbar appears automatically whenever Gboard opens in any app. " +
                    "Each button can be turned on or off individually in the Keyboard tab.",
                "Buttons explained" to
                    "• 📋 Clipboard — opens your clipboard history so you can paste any previous item. " +
                    "Tap an item to paste it. Long-press to favourite it.\n" +
                    "• 📥 Paste — instantly pastes the most recent clipboard item with one tap\n" +
                    "• ☰ Select All — selects all text in the current field\n" +
                    "• ← → Cursor — moves the cursor left or right one character at a time\n" +
                    "• ⚡ Shortcut — pastes your pre-set custom text snippets",
                "Clipboard history" to
                    "Clipboard entries are stored in a local database on your device. " +
                    "You can set the maximum number of entries to keep. " +
                    "All clipboard data stays on your device and is never uploaded anywhere.",
                "Requirements" to
                    "LSPosed must be enabled for Gboard (com.google.android.inputmethod.latin). " +
                    "A reboot is required after toggling any setting."
            )
        )

        // Snapper
        GuideCard(
            title = "Screen Snapper",
            sections = listOf(
                "What it does" to
                    "Replaces the system screenshot with a powerful crop-and-float tool. " +
                    "Instead of saving a full screenshot, you draw a selection and pin it " +
                    "as a floating overlay that stays on top of all apps.",
                "How to trigger" to
                    "Choose your activation method in the Snapper tab:\n" +
                    "• Software — tap the edge button (the small handle on the side of your screen)\n" +
                    "• Hardware — press Power + Volume Down at the same time (chord)\n" +
                    "• Both — either method works",
                "Crop screen" to
                    "After triggering:\n" +
                    "• Draw a rectangle to select the area you want\n" +
                    "• Drag the handles to adjust the selection\n" +
                    "• Double-tap inside the selection to instantly float it\n" +
                    "• Tap the corner-bracket icon (bottom left) to take a full native screenshot instead\n" +
                    "• Tap × to cancel",
                "After selecting" to
                    "An action bar appears with options:\n" +
                    "• Float — pins the crop as a floating overlay on screen\n" +
                    "• Copy — copies the image to clipboard\n" +
                    "• Save — saves to your gallery\n" +
                    "• Share — opens the share sheet\n" +
                    "• OCR — extracts text from the image",
                "Floating overlay" to
                    "The pinned snap floats over all apps:\n" +
                    "• Drag to move it anywhere\n" +
                    "• Pinch to resize it\n" +
                    "• Tap to show the action bar (save, share, copy, etc.)\n" +
                    "• Drag to the trash zone to dismiss",
                "Snap History" to
                    "Floated snaps are saved locally to internal storage. " +
                    "Open the history from the Snapper tab to view, share, re-float, or delete them. " +
                    "The history limit controls how many are kept — older ones are deleted automatically. " +
                    "All images stay on your device.",
                "Requirements" to
                    "LSPosed must be enabled for Android System (system_server). " +
                    "Root access is required for the hardware chord and screenshot capture. " +
                    "A reboot is required after changing the Hardware setting."
            )
        )

        // Status Bar Zones
        GuideCard(
            title = "Status Bar Zones",
            sections = listOf(
                "What it does" to
                    "Adds invisible tap zones on the left and right sides of the status bar " +
                    "(around the camera cutout). Assign single tap, double tap, triple tap, " +
                    "or long press to any action — flashlight, Wi-Fi, DND, volume, ringer, and more.",
                "How to use" to
                    "1. Enable 'Status Bar Zones' in the Zones screen.\n" +
                    "2. Tap 'Adjust Zone Sizes' to preview and resize the zones.\n" +
                    "3. Assign actions to each gesture under Left Zone and Right Zone.\n" +
                    "4. Tap the left or right side of the status bar to fire the action.",
                "Requirements" to
                    "LSPosed module active • Overlay permission granted • " +
                    "Some actions (Wi-Fi, Bluetooth, Mobile Data, Power Saver) require the SystemUI hook"
            )
        )
    }
}

@Composable
private fun GuideCard(title: String, sections: List<Pair<String, String>>) {
    var expanded by remember { mutableStateOf(false) }
    Card(onClick = { expanded = !expanded }) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text(if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                sections.forEach { (heading, body) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(heading, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

// =============================================================================
// Home Screen
// =============================================================================

@Composable
private fun HomeScreen(
    isActive: Boolean,
    shadeEnabled: Boolean,
    snapperMasterEnabled: Boolean,
    zonesEnabled: Boolean,
    kbEnhancerEnabled: Boolean,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    var showSupportDialog by remember { mutableStateOf(false) }

    if (showSupportDialog) {
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            title = { Text("Support the Developer") },
            text = {
                Text(
                    "Hi — I'm a software engineering student building this module in my free time. " +
                    "I pay for everything out of my own pocket, and this project takes real time and effort to maintain.\n\n" +
                    "If Jeez Tweaks has been useful to you, any support is genuinely appreciated " +
                    "— but please know that the module is and will always be completely free to use. " +
                    "You don't owe me anything for using it.\n\n" +
                    "Thank you for trying it out either way. 🙏"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSupportDialog = false
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/kroomfahd"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Continue to PayPal") }
            },
            dismissButton = {
                TextButton(onClick = { showSupportDialog = false }) { Text("Maybe later") }
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) Color(0xFF1A3A2A) else Color(0xFF3A1A1A)
                )
            ) {
                Text(
                    text = if (isActive)
                        "Module Active"
                    else
                        "Module Inactive — enable in LSPosed and reboot",
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isActive) Color(0xFF4CAF50) else Color(0xFFFF5252),
                    textAlign = TextAlign.Center
                )
            }
        }
        item {
            FeatureCard(
                icon      = Icons.Default.Notifications,
                iconColor = Color(0xFF2196F3),
                title     = "Notifications",
                subtitle  = "Auto-expand in shade, heads-up, lock screen",
                isEnabled = shadeEnabled,
                onClick   = { onNavigate("notifications") }
            )
        }
        item {
            FeatureCard(
                icon      = Icons.Default.Keyboard,
                iconColor = Color(0xFF4CAF50),
                title     = "Keyboard Enhancer",
                subtitle  = "Clipboard, shortcuts, cursor tools in Gboard",
                isEnabled = kbEnhancerEnabled,
                onClick   = { onNavigate("keyboard") }
            )
        }
        item {
            FeatureCard(
                icon      = Icons.Default.CameraAlt,
                iconColor = Color(0xFFFF9800),
                title     = "Screen Snapper",
                subtitle  = "Pin cropped screenshots on screen",
                isEnabled = snapperMasterEnabled,
                onClick   = { onNavigate("snapper") }
            )
        }
        item {
            FeatureCard(
                icon      = Icons.Default.TouchApp,
                iconColor = Color(0xFF9C27B0),
                title     = "Status Bar Zones",
                subtitle  = "Tap the status bar to trigger quick actions",
                isEnabled = zonesEnabled,
                onClick   = { onNavigate("zones") }
            )
        }
        item {
            FeatureCard(
                icon      = Icons.Default.Favorite,
                iconColor = Color(0xFFE91E63),
                title     = "Support the Developer",
                subtitle  = "Help keep this project alive",
                isEnabled = null,
                onClick   = { showSupportDialog = true }
            )
        }
        item {
            FeatureCard(
                icon      = Icons.Default.BugReport,
                iconColor = Color(0xFFF44336),
                title     = "Report a Problem",
                subtitle  = "Contact me on Telegram",
                isEnabled = null,
                onClick   = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=kvmy1"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/kvmy1"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            )
        }
        item {
            FeatureCard(
                icon      = Icons.Default.Info,
                iconColor = Color(0xFF9E9E9E),
                title     = "Guide",
                subtitle  = "How to use Jeez Tweaks",
                isEnabled = null,
                onClick   = { onNavigate("guide") }
            )
        }
    }
}

@Composable
private fun FeatureCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    isEnabled: Boolean?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors  = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isEnabled != null) {
                Text(
                    text  = if (isEnabled) "ON" else "OFF",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isEnabled) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                )
            } else {
                Text(
                    "›",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnapperSettingsCard(
    snapperMasterEnabled : Boolean,
    snapperMethod        : String,
    snapperButtonSide    : String,
    snapperDoubleTap     : Boolean,
    snapperHistLimit     : String,
    onMasterEnabledChange: (Boolean) -> Unit,
    onMethodChange       : (String)  -> Unit,
    onSideChange         : (String)  -> Unit,
    onDoubleTapChange    : (Boolean) -> Unit,
    onHistLimitChange    : (String)  -> Unit,
) {
    val context = LocalContext.current
    var hasOverlayPermission by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }

    // Re-check when user returns from system overlay permission screen
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = android.provider.Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val grantOverlayIntent = android.content.Intent(
        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        android.net.Uri.parse("package:${context.packageName}")
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text     = stringResource(R.string.snapper_section_title),
                style    = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color    = MaterialTheme.colorScheme.primary
            )

            // ── Overlay permission warning ────────────────────────────────────
            if (!hasOverlayPermission) {
                Card(
                    onClick  = { context.startActivity(grantOverlayIntent) },
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier          = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector       = Icons.Filled.Info,
                            contentDescription = null,
                            tint              = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Column {
                            Text(
                                text  = "Overlay Permission Required",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text  = "Tap here to grant 'Display over other apps' — Snapper will not work without it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            ToggleRow(
                title           = stringResource(R.string.snapper_master_toggle_title),
                description     = if (hasOverlayPermission) stringResource(R.string.snapper_master_toggle_desc)
                                  else "Grant overlay permission first",
                checked         = snapperMasterEnabled && hasOverlayPermission,
                onCheckedChange = { v ->
                    if (v && !hasOverlayPermission) {
                        context.startActivity(grantOverlayIntent)
                    } else {
                        onMasterEnabledChange(v)
                    }
                }
            )

            // Activation method: Software (edge button) | Hardware (chord) | Both
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(text = stringResource(R.string.snapper_activation_title), style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.padding(top = 8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = snapperMethod == "edge_button",
                        onClick  = { onMethodChange("edge_button") },
                        shape    = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        label    = { Text("Software") }
                    )
                    SegmentedButton(
                        selected = snapperMethod == "chord",
                        onClick  = { onMethodChange("chord") },
                        shape    = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        label    = { Text("Hardware") }
                    )
                    SegmentedButton(
                        selected = snapperMethod == "both",
                        onClick  = { onMethodChange("both") },
                        shape    = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        label    = { Text(stringResource(R.string.snapper_method_both)) }
                    )
                }
            }

            if (snapperMethod == "edge_button" || snapperMethod == "both") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(text = stringResource(R.string.snapper_button_side_title), style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.padding(top = 8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = snapperButtonSide == "left",
                            onClick  = { onSideChange("left") },
                            shape    = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            label    = { Text(stringResource(R.string.snapper_side_left)) }
                        )
                        SegmentedButton(
                            selected = snapperButtonSide == "right",
                            onClick  = { onSideChange("right") },
                            shape    = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            label    = { Text(stringResource(R.string.snapper_side_right)) }
                        )
                    }
                }
            }

            ToggleRow(
                title           = stringResource(R.string.snapper_double_tap_title),
                description     = stringResource(R.string.snapper_double_tap_desc),
                checked         = snapperDoubleTap,
                onCheckedChange = onDoubleTapChange
            )

            OutlinedTextField(
                value           = snapperHistLimit,
                onValueChange   = onHistLimitChange,
                label           = { Text(stringResource(R.string.snapper_history_limit_title)) },
                supportingText  = { Text(stringResource(R.string.snapper_history_limit_desc)) },
                modifier        = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine      = true
            )

            Button(
                onClick  = { context.startActivity(Intent(context, SnapHistoryActivity::class.java)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).padding(bottom = 8.dp)
            ) {
                Text(stringResource(R.string.snapper_view_history))
            }
        }
    }
}


@Composable
private fun DebugCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Debug Capture") },
            text = {
                Text(
                    "Prepare the test sequence:\n\n" +
                    "1. Trigger a notification (e.g. receive a message)\n" +
                    "2. Toggle any setting in this app\n" +
                    "3. Tap OK — logcat will be captured after 30 seconds\n\n" +
                    "Root access is required."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    isCapturing = true
                    scope.launch {
                        delay(30_000)
                        val file = withContext(Dispatchers.IO) { DebugLogHelper.capture(context) }
                        isCapturing = false
                        if (file != null) {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Debug Log"))
                        } else {
                            Toast.makeText(context, "Failed — root access required", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("OK — Start") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            }
        )
    }

    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Debug Logcat",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "For Xiaomi / MIUI users — diagnose IPC & SELinux issues. Captures system logs filtered for module activity and permission denials.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isCapturing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Capturing logcat…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Button(
                    onClick = { showDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Debug Capture")
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
