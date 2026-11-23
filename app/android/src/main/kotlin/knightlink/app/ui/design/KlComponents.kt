package knightlink.app.ui.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import knightlink.app.ui.voice.rememberReduceMotion

/** What a chip or an indicator means. Always paired with a word, so colour is never the only signal. */
enum class Tone { NEUTRAL, GOOD, ATTENTION, PROBLEM, ACCENT }

@Composable
fun toneColor(tone: Tone): Color = when (tone) {
    Tone.NEUTRAL -> KlColors.OnSurfaceMuted
    Tone.GOOD -> KlColors.Good
    Tone.ATTENTION -> KlColors.Attention
    Tone.PROBLEM -> KlColors.Problem
    Tone.ACCENT -> KlColors.Accent
}

/** The card every block of content sits on: one surface colour, one corner radius, one hairline border, 16 dp inside. */
@Composable
fun KlCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(KlSpacing.L), verticalArrangement = Arrangement.spacedBy(KlSpacing.M), content = content)
    }
    if (onClick == null) {
        Surface(modifier.fillMaxWidth(), shape = KlShapes.Card, color = KlColors.Surface, border = BorderStroke(1.dp, KlColors.Outline), content = body)
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = KlShapes.Card,
            color = KlColors.Surface,
            border = BorderStroke(1.dp, KlColors.Outline),
            content = body,
        )
    }
}

/** A card's heading; announced as a heading by a screen reader. */
@Composable
fun KlSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = modifier.semantics { heading() })
}

/** A small pill: a word on a tinted background, with a dot of the tone's colour. */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val color = toneColor(tone)
    Row(
        modifier
            .background(color.copy(alpha = CHIP_BACKGROUND_ALPHA), KlShapes.Chip)
            .padding(horizontal = KlSpacing.M, vertical = KlSpacing.Xs + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KlSpacing.S),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape).clearAndSetSemantics { })
        Text(text, style = MaterialTheme.typography.labelMedium, color = KlColors.OnSurface)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = KlShapes.Button,
        colors = ButtonDefaults.buttonColors(containerColor = KlColors.Accent, contentColor = KlColors.OnAccent),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = KlShapes.Button,
        border = BorderStroke(1.dp, KlColors.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = KlColors.Accent),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

/** A quiet text button with a 48 dp touch target. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = KlSpacing.Touch)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = KlColors.Accent)
    }
}

/** A text field in the app's style. [number] opens the number pad. */
@Composable
fun KlTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    prefix: String? = null,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        prefix = prefix?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        shape = KlShapes.Field,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = KlColors.Accent,
            unfocusedBorderColor = KlColors.Outline,
            focusedLabelColor = KlColors.Accent,
            unfocusedLabelColor = KlColors.OnSurfaceMuted,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = KlSpacing.Touch),
    )
}

/**
 * One step of the guided flow: an indicator (a number, a check, a mark or a gentle pulse), the step's name, and the one plain sentence under it.
 * [action] is the Retry or Skip button of a step that has one. The whole row is one description for a screen reader.
 *
 * @param progress 0 to 1 when the step knows how far it is; otherwise an active step only pulses gently
 * @param animate false once the step has been active for [LONG_WAIT_SECONDS]: it stops pulsing and the screen says in words that it is still
 *   working, so nothing animates forever
 */
@Composable
fun StepItem(
    number: Int,
    title: String,
    sentence: String,
    state: StepVisualState,
    stateWord: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    animate: Boolean = true,
    action: (@Composable () -> Unit)? = null,
) {
    val alpha = if (state == StepVisualState.PENDING) PENDING_ALPHA else 1f
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$number: $title. $stateWord. $sentence" }
            .padding(vertical = KlSpacing.S),
        horizontalArrangement = Arrangement.spacedBy(KlSpacing.M),
    ) {
        StepIndicator(number, state, animate)
        Column(Modifier.weight(1f).alpha(alpha), verticalArrangement = Arrangement.spacedBy(KlSpacing.Xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = KlColors.OnSurface)
            Text(
                sentence,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state == StepVisualState.FAILED) KlColors.Problem else KlColors.OnSurfaceMuted,
                modifier = Modifier.semantics { if (state == StepVisualState.ACTIVE || state == StepVisualState.FAILED) liveRegion = LiveRegionMode.Polite },
            )
            if (state == StepVisualState.ACTIVE && progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = KlSpacing.S),
                    color = KlColors.Accent,
                    trackColor = KlColors.Outline,
                    strokeCap = StrokeCap.Round,
                )
            }
            action?.invoke()
        }
    }
}

enum class StepVisualState { PENDING, ACTIVE, DONE, FAILED }

@Composable
private fun StepIndicator(number: Int, state: StepVisualState, animate: Boolean) {
    val reduce = rememberReduceMotion()
    val pulse = if (state == StepVisualState.ACTIVE && animate && !reduce) {
        val transition = rememberInfiniteTransition(label = "step-pulse")
        val value by transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
            label = "pulse",
        )
        value
    } else {
        1f
    }
    val color = when (state) {
        StepVisualState.PENDING -> KlColors.OnSurfaceMuted
        StepVisualState.ACTIVE -> KlColors.Accent
        StepVisualState.DONE -> KlColors.Good
        StepVisualState.FAILED -> KlColors.Problem
    }
    val glyph = when (state) {
        StepVisualState.DONE -> "✓"
        StepVisualState.FAILED -> "!"
        else -> number.toString()
    }
    Box(
        Modifier
            .size(36.dp)
            .alpha(pulse)
            .border(2.dp, color, CircleShape)
            .background(if (state == StepVisualState.DONE) color.copy(alpha = CHIP_BACKGROUND_ALPHA) else Color.Transparent, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** A big calm figure: the number, its unit, a label above and the method below ("from the car's speed signal" or "not reported by this car"). */
@Composable
fun BigFigure(label: String, value: String?, unit: String, method: String, modifier: Modifier = Modifier, dim: Boolean = false) {
    val description = if (value == null) "$label: $method" else "$label: $value $unit. $method"
    Column(
        modifier.semantics(mergeDescendants = true) { contentDescription = description }.alpha(if (dim) PENDING_ALPHA else 1f),
        verticalArrangement = Arrangement.spacedBy(KlSpacing.Xs),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = KlColors.OnSurfaceMuted)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(KlSpacing.S)) {
            Text(value ?: "—", style = MaterialTheme.typography.displayLarge, color = if (value == null) KlColors.OnSurfaceMuted else KlColors.OnSurface)
            if (value != null && unit.isNotEmpty()) {
                Text(unit, style = MaterialTheme.typography.titleMedium, color = KlColors.OnSurfaceMuted, modifier = Modifier.padding(bottom = KlSpacing.S))
            }
        }
        Text(method, style = MaterialTheme.typography.labelMedium, color = KlColors.OnSurfaceMuted)
    }
}

/** A line of a table: a name on the left and the state on the right; [onClick] makes the row a button. */
@Composable
fun KlRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = KlSpacing.Touch)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(vertical = KlSpacing.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KlSpacing.M),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = KlColors.OnSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
        }
        trailing?.invoke()
    }
}

/** Nothing to show yet: a title, one sentence, and optionally the one thing to do. */
@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = KlSpacing.Xxl, horizontal = KlSpacing.L),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KlSpacing.M),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = KlColors.OnSurface, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = KlColors.OnSurfaceMuted, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) SecondaryButton(actionLabel, onAction)
    }
}

/** Something went wrong, said kindly, with the way out. Never a stack trace and never blame. */
@Composable
fun ErrorState(title: String, message: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    KlCard(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = KlColors.Problem,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(message, style = MaterialTheme.typography.bodyLarge, color = KlColors.OnSurface)
        PrimaryButton(actionLabel, onAction)
    }
}

/** A thin horizontal gauge, 0 to 1, with its value said in words for a screen reader. */
@Composable
fun KlGauge(fraction: Float, description: String, modifier: Modifier = Modifier, tone: Tone = Tone.ACCENT) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().heightIn(min = 10.dp).semantics { contentDescription = description },
        color = toneColor(tone),
        trackColor = KlColors.Outline,
        strokeCap = StrokeCap.Round,
    )
}

/** After this long an active step stops animating and says it is still working. */
const val LONG_WAIT_SECONDS = 60

private const val CHIP_BACKGROUND_ALPHA = 0.16f
private const val PENDING_ALPHA = 0.6f
private const val PULSE_MS = 900
