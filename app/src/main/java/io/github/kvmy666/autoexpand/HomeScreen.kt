package io.github.kvmy666.autoexpand

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

// =============================================================================
// Home Screen
// =============================================================================

@Composable
internal fun HomeScreen(
    isActive: Boolean,
    rootAvailable: Boolean,
    shadeEnabled: Boolean,
    snapperMasterEnabled: Boolean,
    zonesEnabled: Boolean,
    kbEnhancerEnabled: Boolean,
    systemBehaviorEnabled: Boolean,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    var showSupportDialog by remember { mutableStateOf(false) }

    if (showSupportDialog) {
        val openPayPal: (String) -> Unit = { amount ->
            showSupportDialog = false
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/kroomfahd/$amount"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            title = { Text("Buy me a coffee ☕") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Pick an amount — opens PayPal.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { openPayPal("1.99") },
                            modifier = Modifier.weight(1f)
                        ) { Text("$1.99") }
                        Button(
                            onClick = { openPayPal("5") },
                            modifier = Modifier.weight(1f)
                        ) { Text("$5") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { openPayPal("10") },
                            modifier = Modifier.weight(1f)
                        ) { Text("$10") }
                        Button(
                            onClick = { openPayPal("20") },
                            modifier = Modifier.weight(1f)
                        ) { Text("$20") }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Or send via username:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("username", "@kroomfahd"))
                            android.widget.Toast.makeText(context, "Copied @kroomfahd", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("@kroomfahd — tap to copy") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSupportDialog = false }) { Text("Close") }
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (!rootAvailable) {
            item {
                VaultCard(
                    modifier    = Modifier.fillMaxWidth(),
                    borderColor = AppColors.Warning.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Root access not detected",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.Warning
                        )
                        Text(
                            "Some features (zone gestures, screenshot, lock screen) require root to work. " +
                            "Grant root access in your root manager and reopen the app.\n\n" +
                            "This app is 100% safe — no internet access, no remote servers, no analytics. " +
                            "Everything runs locally on your device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            VaultCard(
                modifier    = Modifier.fillMaxWidth(),
                borderColor = if (isActive) AppColors.Positive.copy(alpha = 0.5f)
                              else AppColors.Warning.copy(alpha = 0.5f)
            ) {
                Text(
                    text = if (isActive)
                        "Module Active"
                    else
                        "Module Inactive — enable in LSPosed and reboot",
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isActive) AppColors.Positive else AppColors.Warning,
                    textAlign = TextAlign.Center
                )
            }
        }
        item {
            FeatureCard(
                icon      = Icons.Default.Notifications,
                iconColor = Color(0xFF2196F3),
                title     = "Notification Tweak",
                subtitle  = "Auto-expand in shade, heads-up, lock screen",
                isEnabled = shadeEnabled,
                onClick   = { onNavigate("notifications") }
            )
        }
        item {
            FeatureCard(
                icon      = Icons.Default.Keyboard,
                iconColor = Color(0xFF4CAF50),
                title     = "Gboard Tweak",
                subtitle  = "Clipboard, shortcuts, cursor tools in Gboard",
                isEnabled = kbEnhancerEnabled,
                onClick   = { onNavigate("keyboard") }
            )
        }
        item {
            FeatureCard(
                icon      = Icons.Default.CameraAlt,
                iconColor = Color(0xFFFF9800),
                title     = "Snapper Tweak",
                subtitle  = "Pin cropped screenshots on screen",
                isEnabled = snapperMasterEnabled,
                onClick   = { onNavigate("snapper") }
            )
        }
        item {
            FeatureCard(
                icon      = Icons.Default.TouchApp,
                iconColor = Color(0xFF9C27B0),
                title     = "Status Bar Tweak",
                subtitle  = "Tap the status bar to trigger quick actions",
                isEnabled = zonesEnabled,
                onClick   = { onNavigate("zones") }
            )
        }
        item {
            FeatureCard(
                icon      = Icons.Default.Tune,
                iconColor = Color(0xFF00BCD4),
                title     = "System Behavior",
                subtitle  = "Back gesture haptic, keep screen on",
                isEnabled = systemBehaviorEnabled,
                onClick   = { onNavigate("system") }
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SmallActionCard(
                    icon      = Icons.Default.Favorite,
                    iconColor = Color(0xFFE91E63),
                    label     = "Support Dev",
                    modifier  = Modifier.weight(1f),
                    onClick   = { showSupportDialog = true }
                )
                SmallActionCard(
                    icon      = Icons.Default.Star,
                    iconColor = Color(0xFFFFC107),
                    label     = "Star on GitHub",
                    modifier  = Modifier.weight(1f),
                    onClick   = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/kvmy666/-AutoExpandNotifications"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SmallActionCard(
                    icon      = Icons.Default.BugReport,
                    iconColor = Color(0xFFF44336),
                    label     = "Report Problem",
                    modifier  = Modifier.weight(1f),
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
                SmallActionCard(
                    icon      = Icons.Default.Send,
                    iconColor = Color(0xFF29B6F6),
                    label     = "Telegram Group",
                    modifier  = Modifier.weight(1f),
                    onClick   = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=autoexpandNotification"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/autoexpandNotification"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )
            }
        }
        if (io.github.kvmy666.autoexpand.BuildConfig.DEBUG) {
            item { DebugCard() }
        }
    }
}

@Composable
internal fun FeatureCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    isEnabled: Boolean?,
    onClick: () -> Unit,
) {
    val active = isEnabled == true
    VaultCard(
        onClick     = onClick,
        modifier    = Modifier.fillMaxWidth(),
        borderColor = if (active) AppColors.Accent.copy(alpha = 0.55f) else AppColors.Divider
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
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
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isEnabled != null) {
                StatePill(active)
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

/** Animated ON/OFF pill — accent-tinted when active, dim surface when off. */
@Composable
internal fun StatePill(active: Boolean) {
    val bg by animateColorAsState(
        if (active) AppColors.AccentTint else AppColors.Surface, tween(220), label = "pill-bg"
    )
    val fg by animateColorAsState(
        if (active) AppColors.Accent else AppColors.TextDim, tween(220), label = "pill-fg"
    )
    Text(
        text = if (active) "ON" else "OFF",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
internal fun SmallActionCard(
    icon: ImageVector,
    iconColor: Color,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    VaultCard(onClick = onClick, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}
