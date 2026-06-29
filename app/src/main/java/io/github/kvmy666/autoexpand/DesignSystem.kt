package io.github.kvmy666.autoexpand

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

// =============================================================================
// Design system — the app's visual language is cloned from the keyboard's
// Clipboard Vault popover (KeyboardHook.UI), so the Compose settings and the
// in-keyboard surfaces read as one product:
//   • a deep OLED backdrop (#0F0F14)
//   • elevated cards (#23232E) with a hairline divider stroke + 20dp corners
//   • a single indigo accent (#7C8AFF), tinted into pills for selected state
//   • soft, fast motion: press-scale on cards, color tweens on state changes
//
// The widgets here are presentational only — they take a value + a lambda and
// never own pref state, so every existing toggle/callback is wired through
// unchanged.
// =============================================================================

object AppColors {
    val Background  = Color(0xFF0F0F14)   // deepest backdrop — matches vault BG
    val Surface     = Color(0xFF191921)   // inner containers / chips
    val Elevated    = Color(0xFF23232E)   // cards / rows above the surface
    val Accent      = Color(0xFF4DB6AC)   // calm teal — the one accent (easy on the eye)
    val AccentTint  = Color(0x294DB6AC)   // translucent teal (ON pill background)
    val Positive    = Color(0xFF30D158)   // "on" / active state
    val Warning     = Color(0xFFFF6B6B)   // destructive / kill-switch (vault DANGER)
    val TextPrimary = Color(0xFFECECF1)   // primary text (~7:1 on backdrop)
    val TextDim     = Color(0xFF9A9AB0)   // secondary text
    val Divider     = Color(0xFF2A2A36)   // hairlines / card strokes
}

/** Shared corner radii so every surface rounds the same as the vault. */
object AppShapes {
    val Card = RoundedCornerShape(20.dp)
    val Chip = RoundedCornerShape(12.dp)
}

/** The app-wide Material3 dark scheme, derived from [AppColors]. */
fun appDarkColorScheme() = darkColorScheme(
    primary          = AppColors.Accent,
    onPrimary        = Color(0xFF0A0A12),
    background       = AppColors.Background,
    surface          = AppColors.Background,
    surfaceVariant   = AppColors.Elevated,
    onBackground     = AppColors.TextPrimary,
    onSurface        = AppColors.TextPrimary,
    onSurfaceVariant = AppColors.TextDim,
    error            = AppColors.Warning,
)

/**
 * The core vault surface: an elevated, hairline-bordered, rounded card that
 * presses inward when tapped. [borderColor] animates so callers can make a card
 * glow with the accent when its feature is active.
 */
@Composable
fun VaultCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    borderColor: Color = AppColors.Divider,
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.975f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "vault-press"
    )
    val animBorder by animateColorAsState(borderColor, tween(220), label = "vault-border")

    var surface = modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(AppShapes.Card)
        .background(AppColors.Elevated)
        .border(1.dp, animBorder, AppShapes.Card)
    if (onClick != null) {
        surface = surface.clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onClick = onClick
        )
    }
    Column(modifier = surface.padding(vertical = 6.dp), content = content)
}

/** A settings group rendered as a vault card (drop-in for the old Card wrapper). */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) = VaultCard(modifier = modifier, content = content)

/** A small group label with an accent/dim dot, introducing related rows in a card. */
@Composable
fun SectionLabel(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    accent: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Spacer(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (accent) AppColors.Accent else color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

/**
 * Logarithmic −/value/+ stepper. The displayed step is −10..+10 (0 = default); the
 * stored value is the scale 2^(step/10), so +10 = 2×, −10 = ½×. [multiplier] is the
 * current stored scale; [onMultiplierChange] receives the new scale to persist.
 */
@Composable
fun StepperRow(
    title: String,
    description: String,
    multiplier: Float,
    onMultiplierChange: (Float) -> Unit
) {
    val safe = if (multiplier > 0f && multiplier.isFinite()) multiplier else 1.0f
    val step = (10.0 * (ln(safe.toDouble()) / ln(2.0))).roundToInt().coerceIn(-10, 10)
    fun applyStep(newStep: Int) {
        val s = newStep.coerceIn(-10, 10)
        onMultiplierChange(2.0.pow(s / 10.0).toFloat())
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = "$title: ${"%.2f".format(multiplier)}×  (step $step)",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            OutlinedButton(onClick = { applyStep(step - 1) }, enabled = step > -10) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = if (step > 0) "+$step" else "$step",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(48.dp)
            )
            OutlinedButton(onClick = { applyStep(step + 1) }, enabled = step < 10) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/** A slider with a value label and a one-line description above it. */
@Composable
fun LabeledSlider(
    label: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps
        )
    }
}

// =============================================================================
// Shared toggle row (moved from MainActivity).
// =============================================================================
@Composable
internal fun ToggleRow(
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
