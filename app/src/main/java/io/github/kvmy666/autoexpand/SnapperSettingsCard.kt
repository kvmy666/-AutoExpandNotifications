package io.github.kvmy666.autoexpand

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.FileProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SnapperSettingsCard(
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

    VaultCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            SectionLabel(stringResource(R.string.snapper_section_title), accent = true)

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

            AnimatedVisibility(visible = snapperMethod == "edge_button" || snapperMethod == "both") {
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
internal fun DebugCard() {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(30) }

    // Countdown + capture runs while isCapturing is true
    LaunchedEffect(isCapturing) {
        if (!isCapturing) return@LaunchedEffect
        for (i in 30 downTo 1) { countdown = i; delay(1000) }
        val file = withContext(Dispatchers.IO) { DebugLogHelper.capture(context) }
        isCapturing = false; countdown = 30
        if (file != null) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share Debug Log"
            ))
        } else {
            Toast.makeText(context, "Capture failed — root may be needed", Toast.LENGTH_LONG).show()
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Capture Debug Logs") },
            text = {
                Text(
                    "1. Tap Start — a 30-second timer begins\n" +
                    "2. Do the action that is NOT working\n" +
                    "   (e.g. long-press your status bar zone)\n" +
                    "3. Wait for the timer to finish\n" +
                    "4. Share the log file that appears\n\n" +
                    "Root access is required."
                )
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false; isCapturing = true }) { Text("Start") }
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
                Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Capture Debug Logs", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = "30-second log snapshot. Tap Start, reproduce the failing action, then share the file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isCapturing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Recording… ${countdown}s remaining", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Button(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Start Debug Capture")
                }
            }
        }
    }
}
