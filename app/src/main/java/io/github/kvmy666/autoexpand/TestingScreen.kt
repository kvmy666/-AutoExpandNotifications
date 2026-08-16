package io.github.kvmy666.autoexpand

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only harness for the notification engine.
 *
 * Three things live here, all of them iteration-speed tools rather than features:
 * a deterministic notification sender, a one-tap SystemUI restart so freshly-built hook
 * code loads without a reboot, and the switch for the read-only diagnostic probe.
 */
@Composable
internal fun TestingScreen(
    prefs: SharedPreferences,
    onToggle: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var delaySeconds by remember { mutableIntStateOf(0) }
    var probeEnabled by remember { mutableStateOf(prefs.getBoolean("notif_probe_enabled", false)) }
    var restarting by remember { mutableStateOf(false) }

    var hasPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            Toast.makeText(
                context,
                "Notification permission denied — test notifications cannot be posted",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        if (!hasPermission) {
            SettingsCard {
                SectionLabel("Permission required", color = MaterialTheme.colorScheme.error)
                Text(
                    "POST_NOTIFICATIONS has not been granted, so nothing can be posted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) { Text("Grant permission") }
            }
        }

        // ── Delay ─────────────────────────────────────────────────────────────
        SettingsCard {
            SectionLabel("Post delay")
            Text(
                "Use a delay to lock the screen before the notification arrives — lock-screen " +
                "behaviour can only be tested with a notification that lands while locked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0, 5, 10, 15).forEach { s ->
                    FilterChip(
                        selected = delaySeconds == s,
                        onClick  = { delaySeconds = s },
                        label    = { Text(if (s == 0) "Now" else "${s}s") },
                        colors   = FilterChipDefaults.filterChipColors()
                    )
                }
            }
        }

        // ── Senders ───────────────────────────────────────────────────────────
        SettingsCard {
            SectionLabel("Send test notification", accent = true)
            TestNotifier.Kind.entries.forEach { kind ->
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Button(
                        onClick = {
                            TestNotifier.post(context, kind, delaySeconds * 1000L)
                            if (delaySeconds > 0) {
                                Toast.makeText(
                                    context,
                                    "Posting in ${delaySeconds}s — lock the screen now",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        enabled  = hasPermission,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(kind.label) }
                    Text(
                        kind.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                    )
                }
            }
            OutlinedButton(
                onClick  = { TestNotifier.cancelAll(context) },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text("Clear all test notifications") }
        }

        // ── Diagnostics ───────────────────────────────────────────────────────
        SettingsCard {
            SectionLabel("Diagnostics")
            ToggleRow(
                title = "Notification probe",
                description = "Read-only. Logs full row state under tag AENotifProbe. " +
                              "Changes no behaviour — leave OFF unless capturing a trace.",
                checked = probeEnabled,
                onCheckedChange = { probeEnabled = it; onToggle("notif_probe_enabled", it) }
            )
            Text(
                "adb logcat -s AENotifProbe:D",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // ── SystemUI restart ──────────────────────────────────────────────────
        SettingsCard {
            SectionLabel("Reload module")
            Text(
                "Restarts SystemUI so newly-installed hook code loads without a reboot. " +
                "The screen will flicker and the status bar will disappear for a second.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Button(
                onClick = {
                    restarting = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { RootShell.restartSystemUi() }
                        restarting = false
                        if (!result.ok) {
                            Toast.makeText(
                                context,
                                "Restart failed — no root? ${result.output.take(80)}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                enabled  = !restarting,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text(if (restarting) "Restarting…" else "Restart SystemUI") }
            Text(
                "Termux equivalent:\nsu -c 'pkill -TERM -f com.android.systemui'",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}
