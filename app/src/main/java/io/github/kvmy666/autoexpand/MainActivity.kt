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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        fun isModuleActive(context: Context): Boolean {
            return try {
                val value = Settings.Global.getString(
                    context.contentResolver, "autoexpand_active"
                )
                if (value.isNullOrEmpty()) return false
                val markerTime = value.toLongOrNull() ?: return false
                val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
                markerTime >= bootTime - 60_000
            } catch (_: Throwable) {
                false
            }
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

        setContent {
            val colorScheme = if (isSystemInDarkTheme()) {
                dynamicDarkColorScheme(this)
            } else {
                dynamicLightColorScheme(this)
            }
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

    // ── Tab state ─────────────────────────────────────────────────────────────
    var selectedTab by remember { mutableIntStateOf(0) }

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
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(rebootMsg)
        }
    }

    fun onStringPref(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        MainActivity.makePrefsWorldReadable(context)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon     = { Icon(Icons.Default.Notifications, contentDescription = null) },
                    label    = { Text("Notifications") },
                    selected = selectedTab == 0,
                    onClick  = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon     = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                    label    = { Text("Keyboard") },
                    selected = selectedTab == 1,
                    onClick  = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon     = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    label    = { Text("Snapper") },
                    selected = selectedTab == 2,
                    onClick  = { selectedTab = 2 }
                )
                NavigationBarItem(
                    icon     = { Icon(Icons.Default.Info, contentDescription = null) },
                    label    = { Text("Guide") },
                    selected = selectedTab == 3,
                    onClick  = { selectedTab = 3 }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (selectedTab) {

                // ── Tab 0: Notifications ──────────────────────────────────────
                0 -> {
                    val active = remember { MainActivity.isModuleActive(context) }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (active)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = stringResource(
                                if (active) R.string.module_active else R.string.module_inactive
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                    }

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

                }

                // ── Tab 1: Keyboard Enhancer ──────────────────────────────────
                1 -> {
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

                // ── Tab 2: Screen Snapper ─────────────────────────────────────
                2 -> {
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

                // ── Tab 3: Guide ──────────────────────────────────────────────
                3 -> { GuideScreen() }
            }

            Spacer(modifier = Modifier.padding(bottom = 8.dp))
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
