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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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

    var shadeEnabled by remember { mutableStateOf(prefs.getBoolean("expand_shade_enabled", true)) }
    var headsUpEnabled by remember { mutableStateOf(prefs.getBoolean("expand_headsup_enabled", true)) }
    var lockscreenEnabled by remember { mutableStateOf(prefs.getBoolean("expand_lockscreen_enabled", true)) }
    var backHapticEnabled by remember { mutableStateOf(prefs.getBoolean("disable_back_haptic_enabled", true)) }
    var headsupPopupEnabled by remember { mutableStateOf(prefs.getBoolean("disable_headsup_popup_enabled", true)) }
    var ungroupEnabled by remember { mutableStateOf(prefs.getBoolean("ungroup_notifications_enabled", true)) }
    var excludedCount by remember { mutableIntStateOf(prefs.getStringSet("excluded_apps", emptySet())?.size ?: 0) }

    // Keyboard Enhancer
    // Screen Snapper
    var snapperEnabled    by remember { mutableStateOf(prefs.getBoolean("snapper_enabled", false)) }
    var snapperMethod     by remember { mutableStateOf(prefs.getString("snapper_activation_method", "qs_tile") ?: "qs_tile") }
    var snapperButtonSide by remember { mutableStateOf(prefs.getString("snapper_button_side", "right") ?: "right") }
    var snapperDoubleTap  by remember { mutableStateOf(prefs.getBoolean("snapper_double_tap_dismiss", true)) }
    var snapperHistLimit  by remember { mutableStateOf(prefs.getString("snapper_history_limit", "50") ?: "50") }

    // Keyboard Enhancer
    var kbEnhancerEnabled by remember { mutableStateOf(prefs.getBoolean("keyboard_enhancer_enabled", true)) }
    var toolbarMultiplier by remember { mutableFloatStateOf(prefs.getString("toolbar_height_multiplier", "1.0")?.toFloatOrNull() ?: 1.0f) }
    var shortcut1 by remember { mutableStateOf(prefs.getString("shortcut_text_1", "") ?: "") }
    var shortcut2 by remember { mutableStateOf(prefs.getString("shortcut_text_2", "") ?: "") }
    var clipboardMaxEntries by remember { mutableStateOf(prefs.getString("clipboard_max_entries", "500") ?: "500") }
    var btnClipboardEnabled by remember { mutableStateOf(prefs.getBoolean("btn_clipboard_enabled", true)) }
    var btnSelectAllEnabled by remember { mutableStateOf(prefs.getBoolean("btn_selectall_enabled", true)) }
    var btnCursorEnabled by remember { mutableStateOf(prefs.getBoolean("btn_cursor_enabled", true)) }
    var btnShortcutEnabled by remember { mutableStateOf(prefs.getBoolean("btn_shortcut_enabled", true)) }

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
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Module status
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

            // Notification expansion toggles
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
                    ToggleRow(
                        title = stringResource(R.string.expand_lockscreen_title),
                        description = stringResource(R.string.expand_lockscreen_desc),
                        checked = lockscreenEnabled,
                        onCheckedChange = { lockscreenEnabled = it; onToggle("expand_lockscreen_enabled", it) }
                    )
                }
            }

            // OxygenOS tweaks
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

            // Excluded apps
            Card(
                onClick = {
                    context.startActivity(Intent(context, AppListActivity::class.java))
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.excluded_apps_title),
                        style = MaterialTheme.typography.bodyLarge
                    )
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

            // [DEBUG] Screen Snapper test
            if (BuildConfig.DEBUG) {
                SnapperDebugCard(snackbarHostState)
            }

            // Screen Snapper settings
            SnapperSettingsCard(
                prefs              = prefs,
                snapperEnabled     = snapperEnabled,
                snapperMethod      = snapperMethod,
                snapperButtonSide  = snapperButtonSide,
                snapperDoubleTap   = snapperDoubleTap,
                snapperHistLimit   = snapperHistLimit,
                onEnabledChange    = { enabled ->
                    snapperEnabled = enabled
                    prefs.edit().putBoolean("snapper_enabled", enabled).apply()
                    val svc = Intent(context, SnapperService::class.java)
                    if (enabled && snapperMethod != "qs_tile") {
                        svc.action = SnapperService.ACTION_SHOW_EDGE_BUTTON
                        context.startForegroundService(svc)
                    } else if (!enabled) {
                        svc.action = SnapperService.ACTION_HIDE_EDGE_BUTTON
                        context.startForegroundService(svc)
                    }
                },
                onMethodChange     = { method ->
                    snapperMethod = method
                    prefs.edit().putString("snapper_activation_method", method).apply()
                    if (snapperEnabled) {
                        val svc = Intent(context, SnapperService::class.java)
                        svc.action = if (method != "qs_tile")
                            SnapperService.ACTION_SHOW_EDGE_BUTTON
                        else
                            SnapperService.ACTION_HIDE_EDGE_BUTTON
                        context.startForegroundService(svc)
                    }
                },
                onSideChange       = { side ->
                    snapperButtonSide = side
                    prefs.edit().putString("snapper_button_side", side).apply()
                    if (snapperEnabled && snapperMethod != "qs_tile") {
                        // Restart edge button with new side
                        val svc = Intent(context, SnapperService::class.java)
                        svc.action = SnapperService.ACTION_HIDE_EDGE_BUTTON
                        context.startForegroundService(svc)
                        val svc2 = Intent(context, SnapperService::class.java)
                        svc2.action = SnapperService.ACTION_SHOW_EDGE_BUTTON
                        context.startForegroundService(svc2)
                    }
                },
                onDoubleTapChange  = { dt ->
                    snapperDoubleTap = dt
                    prefs.edit().putBoolean("snapper_double_tap_dismiss", dt).apply()
                },
                onHistLimitChange  = { lim ->
                    snapperHistLimit = lim.filter { it.isDigit() }
                    prefs.edit().putString("snapper_history_limit", snapperHistLimit).apply()
                }
            )

            // Keyboard Enhancer
            Card {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.keyboard_enhancer_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Master toggle
                    ToggleRow(
                        title = stringResource(R.string.keyboard_enhancer_enabled_title),
                        description = stringResource(R.string.keyboard_enhancer_enabled_desc),
                        checked = kbEnhancerEnabled,
                        onCheckedChange = { kbEnhancerEnabled = it; onToggle("keyboard_enhancer_enabled", it) }
                    )

                    // Toolbar height slider
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

                    // Buttons section header
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

                    // Shortcut texts (only shown when shortcut button is enabled)
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

                    // Clipboard settings (only shown when clipboard button is enabled)
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnapperSettingsCard(
    prefs             : SharedPreferences,
    snapperEnabled    : Boolean,
    snapperMethod     : String,
    snapperButtonSide : String,
    snapperDoubleTap  : Boolean,
    snapperHistLimit  : String,
    onEnabledChange   : (Boolean) -> Unit,
    onMethodChange    : (String)  -> Unit,
    onSideChange      : (String)  -> Unit,
    onDoubleTapChange : (Boolean) -> Unit,
    onHistLimitChange : (String)  -> Unit,
) {
    val context = LocalContext.current
    val showEdgeOptions = snapperMethod != "qs_tile"

    Card {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text     = stringResource(R.string.snapper_section_title),
                style    = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color    = MaterialTheme.colorScheme.primary
            )

            // Master toggle
            ToggleRow(
                title          = stringResource(R.string.snapper_enabled_title),
                description    = stringResource(R.string.snapper_enabled_desc),
                checked        = snapperEnabled,
                onCheckedChange = onEnabledChange
            )

            // Activation method segmented buttons
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text  = stringResource(R.string.snapper_activation_title),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.padding(top = 8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = snapperMethod == "qs_tile",
                        onClick  = { onMethodChange("qs_tile") },
                        shape    = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        label    = { Text(stringResource(R.string.snapper_method_qs)) }
                    )
                    SegmentedButton(
                        selected = snapperMethod == "edge_button",
                        onClick  = { onMethodChange("edge_button") },
                        shape    = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        label    = { Text(stringResource(R.string.snapper_method_edge)) }
                    )
                    SegmentedButton(
                        selected = snapperMethod == "both",
                        onClick  = { onMethodChange("both") },
                        shape    = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        label    = { Text(stringResource(R.string.snapper_method_both)) }
                    )
                }
            }

            // Edge button side (only when edge button is part of the method)
            if (showEdgeOptions) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text  = stringResource(R.string.snapper_button_side_title),
                        style = MaterialTheme.typography.bodyLarge
                    )
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

            // Double-tap dismiss
            ToggleRow(
                title           = stringResource(R.string.snapper_double_tap_title),
                description     = stringResource(R.string.snapper_double_tap_desc),
                checked         = snapperDoubleTap,
                onCheckedChange = onDoubleTapChange
            )

            // History limit
            OutlinedTextField(
                value          = snapperHistLimit,
                onValueChange  = onHistLimitChange,
                label          = { Text(stringResource(R.string.snapper_history_limit_title)) },
                supportingText = { Text(stringResource(R.string.snapper_history_limit_desc)) },
                modifier       = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine     = true
            )

            // View history button
            Button(
                onClick  = { context.startActivity(Intent(context, SnapHistoryActivity::class.java)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .padding(bottom = 8.dp)
            ) {
                Text(stringResource(R.string.snapper_view_history))
            }
        }
    }
}

@Composable
private fun SnapperDebugCard(snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "DEBUG — Screen Snapper",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "Tap to test screenshot capture. Requires SYSTEM_ALERT_WINDOW permission. Check logcat -s JeezSnapper for timing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    if (!Settings.canDrawOverlays(context)) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar("Grant 'Display over other apps' permission, then try again")
                        }
                    } else {
                        val svc = Intent(context, SnapperService::class.java).apply {
                            action = SnapperService.ACTION_TEST_CAPTURE
                        }
                        context.startForegroundService(svc)
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar("Snapper started — watch logcat JeezSnapper")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Test Screenshot Capture")
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
