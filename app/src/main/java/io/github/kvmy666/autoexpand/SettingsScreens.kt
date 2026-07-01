package io.github.kvmy666.autoexpand

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.graphics.graphicsLayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(prefs: SharedPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val rebootMsg = stringResource(R.string.reboot_required)
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // ── Navigation state ──────────────────────────────────────────────────────
    var selectedFeature by remember { mutableStateOf<String?>(null) }

    // ── What's New dialog ─────────────────────────────────────────────────────
    var showWhatsNew by remember { mutableStateOf(!prefs.getBoolean("whats_new_seen_3_2_1", false)) }
    var whatsNewDontShow by remember { mutableStateOf(false) }

    // ── Notifications state ───────────────────────────────────────────────────
    var shadeEnabled       by remember { mutableStateOf(prefs.getBoolean("expand_shade_enabled", true)) }
    var headsUpEnabled     by remember { mutableStateOf(prefs.getBoolean("expand_headsup_enabled", true)) }
    var disableHeadsupHooks by remember { mutableStateOf(prefs.getBoolean("disable_headsup_hooks_enabled", false)) }
    var lockscreenEnabled  by remember { mutableStateOf(prefs.getBoolean("expand_lockscreen_enabled", true)) }
    var backHapticEnabled  by remember { mutableStateOf(prefs.getBoolean("disable_back_haptic_enabled", true)) }

    // ── System Behavior state ─────────────────────────────────────────────────
    var keepScreenOnEnabled by remember { mutableStateOf(prefs.getBoolean("keep_screen_on_enabled", false)) }
    var globalSearchEnterEnabled by remember { mutableStateOf(prefs.getBoolean("global_search_enter_launch_enabled", false)) }
    var launcherDrawerKeyboardEnabled by remember { mutableStateOf(prefs.getBoolean("launcher_drawer_keyboard_enabled", false)) }
    var launcherDrawerImeSyncEnabled by remember { mutableStateOf(prefs.getBoolean("launcher_drawer_ime_sync_enabled", false)) }
    var launcherDrawerEnterEnabled by remember { mutableStateOf(prefs.getBoolean("launcher_drawer_enter_launch_enabled", false)) }
    var launcherDrawerAutoSingleEnabled by remember { mutableStateOf(prefs.getBoolean("launcher_drawer_auto_launch_single_enabled", false)) }
    var launcherDrawerKeepLayoutEnabled by remember { mutableStateOf(prefs.getBoolean("launcher_drawer_keep_layout_until_query_enabled", false)) }
    var launcherDrawerReopenTopEnabled by remember { mutableStateOf(prefs.getBoolean("launcher_drawer_reopen_search_at_top_enabled", false)) }
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
    // Per-gesture shortcut bindings ("pkg::id" or "")
    var zLSingleShortcut by remember { mutableStateOf(prefs.getString("zones_left_single_tap_shortcut",  "") ?: "") }
    var zLDoubleShortcut by remember { mutableStateOf(prefs.getString("zones_left_double_tap_shortcut",  "") ?: "") }
    var zLTripleShortcut by remember { mutableStateOf(prefs.getString("zones_left_triple_tap_shortcut",  "") ?: "") }
    var zLLongShortcut   by remember { mutableStateOf(prefs.getString("zones_left_long_press_shortcut",  "") ?: "") }
    var zRSingleShortcut by remember { mutableStateOf(prefs.getString("zones_right_single_tap_shortcut", "") ?: "") }
    var zRDoubleShortcut by remember { mutableStateOf(prefs.getString("zones_right_double_tap_shortcut", "") ?: "") }
    var zRTripleShortcut by remember { mutableStateOf(prefs.getString("zones_right_triple_tap_shortcut", "") ?: "") }
    var zRLongShortcut   by remember { mutableStateOf(prefs.getString("zones_right_long_press_shortcut", "") ?: "") }

    // ── Keyboard Enhancer state ───────────────────────────────────────────────
    var kbEnhancerEnabled   by remember { mutableStateOf(prefs.getBoolean("keyboard_enhancer_enabled", true)) }
    var toolbarMultiplier   by remember { mutableFloatStateOf(prefs.getString("toolbar_height_multiplier", "1.0")?.toFloatOrNull() ?: 1.0f) }
    var buttonMultiplier    by remember { mutableFloatStateOf(prefs.getString("toolbar_button_multiplier", "1.0")?.toFloatOrNull() ?: 1.0f) }
    var shortcut1           by remember { mutableStateOf(prefs.getString("shortcut_text_1", "") ?: "") }
    var shortcut2           by remember { mutableStateOf(prefs.getString("shortcut_text_2", "") ?: "") }
    var clipboardMaxEntries by remember { mutableStateOf(prefs.getString("clipboard_max_entries", "500") ?: "500") }
    var btnClipboardEnabled by remember { mutableStateOf(prefs.getBoolean("btn_clipboard_enabled", true)) }
    var btnPasteEnabled     by remember { mutableStateOf(prefs.getBoolean("btn_paste_enabled", true)) }
    var btnSelectAllEnabled by remember { mutableStateOf(prefs.getBoolean("btn_selectall_enabled", true)) }
    var btnCursorEnabled    by remember { mutableStateOf(prefs.getBoolean("btn_cursor_enabled", false)) }   // A3: OFF by default
    var btnTrackpadEnabled  by remember { mutableStateOf(prefs.getBoolean("btn_trackpad_enabled", true)) }  // A4: ON by default
    var trackpadHaptics     by remember { mutableStateOf(prefs.getBoolean("trackpad_haptics_enabled", true)) } // A2
    var vibStrength         by remember { mutableIntStateOf(prefs.getString("vibration_strength", "100")?.toIntOrNull() ?: 100) }
    var clipFullText        by remember { mutableStateOf(prefs.getBoolean("clip_full_text_enabled", true)) }    // A1
    var btnShortcutEnabled  by remember { mutableStateOf(prefs.getBoolean("btn_shortcut_enabled", true)) }
    // ── Undo (B2/B3/B4) ──
    val hasAccelerometer = remember {
        (context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager)
            ?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) != null
    }
    var undoEnabled       by remember { mutableStateOf(prefs.getBoolean("undo_enabled", true)) }
    var undoButtonEnabled by remember { mutableStateOf(prefs.getBoolean("undo_button_enabled", true)) }
    var shakeUndoEnabled  by remember { mutableStateOf(prefs.getBoolean("shake_undo_enabled", true) && hasAccelerometer) }
    var shakeSensitivity  by remember { mutableFloatStateOf(prefs.getString("shake_sensitivity", "1.0")?.toFloatOrNull() ?: 1.0f) }

    // ── Root detection ────────────────────────────────────────────────────────
    val rootAvailable by produceState(initialValue = true) {
        value = withContext(Dispatchers.IO) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(3, TimeUnit.SECONDS)
                p.destroy()
                out.contains("uid=0")
            } catch (_: Throwable) { false }
        }
    }

    // Module-active state — re-checked on resume so a working module that wasn't yet
    // reporting at first launch (e.g. heartbeat not written yet) self-heals.
    var isActive by remember { mutableStateOf(MainActivity.isModuleActive(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                excludedCount = prefs.getStringSet("excluded_apps", emptySet())?.size ?: 0
                isActive = MainActivity.isModuleActive(context)
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

    if (showWhatsNew) {
        AlertDialog(
            onDismissRequest = {
                if (whatsNewDontShow) prefs.edit().putBoolean("whats_new_seen_3_2_1", true).apply()
                showWhatsNew = false
            },
            title = { Text("What's New in v3.2.1") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("New features", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text("• Clipboard Search (🔍) — tap Search in the Clipboard Vault to instantly find any saved clip. Fast, accent-insensitive, multi-word, with matches highlighted")
                    Text("• Unlimited clipboard history — saved clips are no longer capped")
                    Text("• Keep Screen On — new toggle in System Behavior that stops the screen from sleeping while you're using your phone")
                    Spacer(Modifier.height(4.dp))
                    Text("Fixes", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text("• Global Search: the Go / Enter key now launches the first app in the results")
                    Text("• Several notification expand fixes for more reliable auto-expand")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = whatsNewDontShow, onCheckedChange = { whatsNewDontShow = it })
                        Text("Don't show this again", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (whatsNewDontShow) prefs.edit().putBoolean("whats_new_seen_3_2_1", true).apply()
                    showWhatsNew = false
                }) { Text("Got it") }
            }
        )
    }

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
                            "system"        -> "System Behavior"
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
      Crossfade(targetState = selectedFeature, animationSpec = tween(260), label = "screen") { feature ->
        if (feature == null) {
            HomeScreen(
                isActive             = isActive,
                rootAvailable        = rootAvailable,
                shadeEnabled         = shadeEnabled,
                snapperMasterEnabled = snapperMasterEnabled,
                zonesEnabled         = zonesEnabled,
                kbEnhancerEnabled    = kbEnhancerEnabled,
                systemBehaviorEnabled = keepScreenOnEnabled || backHapticEnabled || globalSearchEnterEnabled || launcherDrawerKeyboardEnabled || launcherDrawerImeSyncEnabled || launcherDrawerEnterEnabled || launcherDrawerAutoSingleEnabled || launcherDrawerKeepLayoutEnabled || launcherDrawerReopenTopEnabled,
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
                when (feature) {

                    // ── Notifications ─────────────────────────────────────────────
                    "notifications" -> {
                        // ── Auto-expand ──
                        SettingsCard {
                            SectionLabel("Auto-expand")
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

                        // ── Behavior ──
                        SettingsCard {
                            SectionLabel("Behavior")
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
                        }

                        // ── Advanced (master kill-switch, set apart so it isn't toggled by mistake) ──
                        SettingsCard {
                            SectionLabel("Advanced", color = MaterialTheme.colorScheme.error)
                            ToggleRow(
                                title = "Disable Heads-Up Hooks",
                                description = "Master kill-switch — when ON, no auto-expand or HUD layout hooks run. Use this if your ROM produces a blank or oversized HUD.",
                                checked = disableHeadsupHooks,
                                onCheckedChange = { disableHeadsupHooks = it; onToggle("disable_headsup_hooks_enabled", it) }
                            )
                        }

                        VaultCard(
                            onClick = { context.startActivity(Intent(context, AppListActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
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

                    }

                    // ── System Behavior ───────────────────────────────────────────
                    "system" -> {
                        // ── Gestures ──
                        SettingsCard {
                            SectionLabel("Gestures")
                            ToggleRow(
                                title = stringResource(R.string.disable_back_haptic_title),
                                description = stringResource(R.string.disable_back_haptic_desc),
                                checked = backHapticEnabled,
                                onCheckedChange = { backHapticEnabled = it; onToggle("disable_back_haptic_enabled", it) }
                            )
                        }

                        // ── Display ──
                        SettingsCard {
                            SectionLabel("Display")
                            ToggleRow(
                                title = stringResource(R.string.keep_screen_on_title),
                                description = stringResource(R.string.keep_screen_on_desc),
                                checked = keepScreenOnEnabled,
                                onCheckedChange = { keepScreenOnEnabled = it; onToggle("keep_screen_on_enabled", it) }
                            )
                            Text(
                                text = stringResource(R.string.keep_screen_on_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.Warning,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }

                        // ── Global Search ──
                        SettingsCard {
                            SectionLabel("Global Search")
                            ToggleRow(
                                title = stringResource(R.string.global_search_enter_title),
                                description = stringResource(R.string.global_search_enter_desc),
                                checked = globalSearchEnterEnabled,
                                onCheckedChange = { globalSearchEnterEnabled = it; onToggle("global_search_enter_launch_enabled", it) }
                            )
                        }

                        // ── Launcher Drawer ──
                        SettingsCard {
                            SectionLabel("Launcher Drawer")
                            ToggleRow(
                                title = stringResource(R.string.launcher_drawer_keyboard_title),
                                description = stringResource(R.string.launcher_drawer_keyboard_desc),
                                checked = launcherDrawerKeyboardEnabled,
                                onCheckedChange = { launcherDrawerKeyboardEnabled = it; onToggle("launcher_drawer_keyboard_enabled", it) }
                            )
                            ToggleRow(
                                title = stringResource(R.string.launcher_drawer_ime_sync_title),
                                description = stringResource(R.string.launcher_drawer_ime_sync_desc),
                                checked = launcherDrawerImeSyncEnabled,
                                onCheckedChange = { launcherDrawerImeSyncEnabled = it; onToggle("launcher_drawer_ime_sync_enabled", it) }
                            )
                            ToggleRow(
                                title = stringResource(R.string.launcher_drawer_enter_title),
                                description = stringResource(R.string.launcher_drawer_enter_desc),
                                checked = launcherDrawerEnterEnabled,
                                onCheckedChange = { launcherDrawerEnterEnabled = it; onToggle("launcher_drawer_enter_launch_enabled", it) }
                            )
                            ToggleRow(
                                title = stringResource(R.string.launcher_drawer_auto_single_title),
                                description = stringResource(R.string.launcher_drawer_auto_single_desc),
                                checked = launcherDrawerAutoSingleEnabled,
                                onCheckedChange = { launcherDrawerAutoSingleEnabled = it; onToggle("launcher_drawer_auto_launch_single_enabled", it) }
                            )
                            ToggleRow(
                                title = stringResource(R.string.launcher_drawer_keep_layout_title),
                                description = stringResource(R.string.launcher_drawer_keep_layout_desc),
                                checked = launcherDrawerKeepLayoutEnabled,
                                onCheckedChange = { launcherDrawerKeepLayoutEnabled = it; onToggle("launcher_drawer_keep_layout_until_query_enabled", it) }
                            )
                            ToggleRow(
                                title = stringResource(R.string.launcher_drawer_reopen_top_title),
                                description = stringResource(R.string.launcher_drawer_reopen_top_desc),
                                checked = launcherDrawerReopenTopEnabled,
                                onCheckedChange = { launcherDrawerReopenTopEnabled = it; onToggle("launcher_drawer_reopen_search_at_top_enabled", it) }
                            )
                        }
                    }

                    // ── Keyboard Enhancer ─────────────────────────────────────────
                    "keyboard" -> {
                        // ── Master switch ──
                        SettingsCard {
                            SectionLabel(
                                stringResource(R.string.keyboard_enhancer_title),
                                color = MaterialTheme.colorScheme.primary
                            )
                            ToggleRow(
                                title = stringResource(R.string.keyboard_enhancer_enabled_title),
                                description = stringResource(R.string.keyboard_enhancer_enabled_desc),
                                checked = kbEnhancerEnabled,
                                onCheckedChange = { kbEnhancerEnabled = it; onToggle("keyboard_enhancer_enabled", it) }
                            )
                        }

                        // ── Toolbar size ──
                        SettingsCard {
                            SectionLabel("Toolbar size")
                            // Height & button-size steppers: scale = 2^(step/10), 0 = default.
                            StepperRow(
                                title = stringResource(R.string.keyboard_toolbar_height_title),
                                description = stringResource(R.string.keyboard_toolbar_height_desc),
                                multiplier = toolbarMultiplier,
                                onMultiplierChange = { scale ->
                                    toolbarMultiplier = scale
                                    onStringPref("toolbar_height_multiplier", scale.toString())
                                }
                            )
                            StepperRow(
                                title = "Button size",
                                description = "Width and emoji size of each toolbar button, independent of height",
                                multiplier = buttonMultiplier,
                                onMultiplierChange = { scale ->
                                    buttonMultiplier = scale
                                    onStringPref("toolbar_button_multiplier", scale.toString())
                                }
                            )
                        }

                        // ── Toolbar buttons ──
                        SettingsCard {
                            SectionLabel(stringResource(R.string.keyboard_buttons_title))
                            ToggleRow(
                                title = stringResource(R.string.btn_clipboard_title),
                                description = stringResource(R.string.btn_clipboard_desc),
                                checked = btnClipboardEnabled,
                                onCheckedChange = { btnClipboardEnabled = it; onToggle("btn_clipboard_enabled", it) }
                            )
                            ToggleRow(
                                title = "Show full text",
                                description = "Clipboard items show their full text. Turn off to show only the first 3 lines.",
                                checked = clipFullText,
                                onCheckedChange = { clipFullText = it; onToggle("clip_full_text_enabled", it) }
                            )
                            AnimatedVisibility(visible = btnClipboardEnabled) {
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
                                title = "Trackpad Stick",
                                description = "Show the joystick button (🕹️) — press and drag to move the cursor in any direction.",
                                checked = btnTrackpadEnabled,
                                onCheckedChange = { btnTrackpadEnabled = it; onToggle("btn_trackpad_enabled", it) }
                            )
                            AnimatedVisibility(visible = btnTrackpadEnabled) {
                                ToggleRow(
                                    title = "Stick Haptics",
                                    description = "Vibration feedback while using the trackpad stick (grab pop + steering ticks).",
                                    checked = trackpadHaptics,
                                    onCheckedChange = { trackpadHaptics = it; onToggle("trackpad_haptics_enabled", it) }
                                )
                            }
                            ToggleRow(
                                title = stringResource(R.string.btn_shortcut_title),
                                description = stringResource(R.string.btn_shortcut_desc),
                                checked = btnShortcutEnabled,
                                onCheckedChange = { btnShortcutEnabled = it; onToggle("btn_shortcut_enabled", it) }
                            )
                            AnimatedVisibility(visible = btnShortcutEnabled) {
                              Column {
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
                            }
                        }

                        // ── Haptics (global: trackpad ticks + shake-undo confirm) ──
                        SettingsCard {
                            SectionLabel("Haptics")
                            LabeledSlider(
                                label = if (vibStrength == 0) "Vibration Strength: Off"
                                        else "Vibration Strength: $vibStrength%",
                                description = "Intensity of trackpad and shake-undo vibrations. 100% uses the device's tuned feel.",
                                value = vibStrength.toFloat(),
                                valueRange = 0f..100f,
                                steps = 19,
                                onValueChange = { vibStrength = it.toInt().coerceIn(0, 100) },
                                onValueChangeFinished = { onStringPref("vibration_strength", vibStrength.toString()) }
                            )
                        }

                        // ── Undo ──
                        SettingsCard {
                            SectionLabel("Undo")
                            ToggleRow(
                                title = "Undo (restore deleted text)",
                                description = "Bring back text you deleted — backspace, cut, select-all delete, or text replaced by typing.",
                                checked = undoEnabled,
                                onCheckedChange = { undoEnabled = it; onToggle("undo_enabled", it) }
                            )
                            if (undoEnabled) {
                                ToggleRow(
                                    title = "Undo Button",
                                    description = "Show a ↩️ button in the toolbar that restores the last deletion.",
                                    checked = undoButtonEnabled,
                                    onCheckedChange = { undoButtonEnabled = it; onToggle("undo_button_enabled", it) }
                                )
                                if (hasAccelerometer) {
                                    ToggleRow(
                                        title = "Shake to Undo",
                                        description = "Shake the phone to restore deleted text, confirmed with a Cancel/Undo prompt.",
                                        checked = shakeUndoEnabled,
                                        onCheckedChange = { shakeUndoEnabled = it; onToggle("shake_undo_enabled", it) }
                                    )
                                    if (shakeUndoEnabled) {
                                        LabeledSlider(
                                            label = "Shake Sensitivity: ${"%.1f".format(shakeSensitivity)}×",
                                            description = "Higher = a lighter shake triggers undo. Lower = needs a firmer shake.",
                                            value = shakeSensitivity,
                                            valueRange = 0.1f..2.0f,
                                            steps = 18,
                                            onValueChange = { shakeSensitivity = (Math.round(it * 10f) / 10f).coerceIn(0.1f, 2.0f) },
                                            onValueChangeFinished = { onStringPref("shake_sensitivity", shakeSensitivity.toString()) }
                                        )
                                    }
                                } else {
                                    ToggleRow(
                                        title = "Shake to Undo",
                                        description = "Unavailable — this device has no accelerometer. Use the Undo button instead.",
                                        checked = false,
                                        onCheckedChange = { }
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
                                MainActivity.broadcastPref(context, "enable_snapper_entirely", if (v) "1" else "0")
                                if (!v) {
                                    // Tell the running service to tear down the edge button. It then
                                    // stops itself via stopSelfIfIdle(). Do NOT call stopService()
                                    // right after startForegroundService() — that races the service's
                                    // own startForeground() call and crashes with
                                    // ForegroundServiceDidNotStartInTimeException.
                                    val svc = Intent(context, SnapperService::class.java)
                                    svc.action = SnapperService.ACTION_HIDE_EDGE_BUTTON
                                    try { context.startForegroundService(svc) } catch (_: Throwable) {}
                                }
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
                            zLShortcuts = mapOf(
                                "single_tap_action" to zLSingleShortcut,
                                "double_tap_action" to zLDoubleShortcut,
                                "triple_tap_action" to zLTripleShortcut,
                                "long_press_action" to zLLongShortcut,
                            ),
                            zRShortcuts = mapOf(
                                "single_tap_action" to zRSingleShortcut,
                                "double_tap_action" to zRDoubleShortcut,
                                "triple_tap_action" to zRTripleShortcut,
                                "long_press_action" to zRLongShortcut,
                            ),
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
                            },
                            onShortcutChange = { shortcutPrefKey, shortcutData ->
                                prefs.edit().putString(shortcutPrefKey, shortcutData).apply()
                                when (shortcutPrefKey) {
                                    "zones_left_single_tap_shortcut"  -> zLSingleShortcut = shortcutData
                                    "zones_left_double_tap_shortcut"  -> zLDoubleShortcut = shortcutData
                                    "zones_left_triple_tap_shortcut"  -> zLTripleShortcut = shortcutData
                                    "zones_left_long_press_shortcut"  -> zLLongShortcut   = shortcutData
                                    "zones_right_single_tap_shortcut" -> zRSingleShortcut = shortcutData
                                    "zones_right_double_tap_shortcut" -> zRDoubleShortcut = shortcutData
                                    "zones_right_triple_tap_shortcut" -> zRTripleShortcut = shortcutData
                                    "zones_right_long_press_shortcut" -> zRLongShortcut   = shortcutData
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
}

@Composable
internal fun GuideScreen() {
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
internal fun GuideCard(title: String, sections: List<Pair<String, String>>) {
    var expanded by remember { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "guide-chevron")
    VaultCard(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
                Text("▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = chevron })
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
}
