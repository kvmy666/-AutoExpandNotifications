package com.autoexpand.xposed

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
                .putStringSet("excluded_apps", emptySet())
                .apply()
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
    var excludedCount by remember { mutableIntStateOf(prefs.getStringSet("excluded_apps", emptySet())?.size ?: 0) }

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

            // Back haptic toggle
            Card {
                ToggleRow(
                    title = stringResource(R.string.disable_back_haptic_title),
                    description = stringResource(R.string.disable_back_haptic_desc),
                    checked = backHapticEnabled,
                    onCheckedChange = { backHapticEnabled = it; onToggle("disable_back_haptic_enabled", it) }
                )
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
