package knightlink.app.ui.voice

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import knightlink.app.R
import knightlink.voice.api.SpeechState
import kotlin.math.abs

/**
 * The full-screen speaking visualiser: the voice as three columns of LED-like segments, the line being said under it, and
 * "Say again" / "Stop". Compose it only while it should be shown ([SpeechState.Idle] draws nothing at all), so no frame is
 * requested while the screen is not needed. Tapping anywhere but a button calls [onDismiss]; the caller keeps the overlay hidden until
 * the next line.
 *
 * @param lastSpoken the text of the last line spoken, shown when no line is being spoken
 * @param reduceMotion draw the grid still at a fixed medium fill instead of animating it
 */
@Composable
fun VoiceOverlay(
    state: SpeechState,
    lastSpoken: String?,
    pack: ThemePack,
    reduceMotion: Boolean,
    onSayAgain: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state is SpeechState.Idle) return

    val colors = pack.colors
    val text = Color(colors.text)
    val meter = rememberMeterFrame(state, reduceMotion)
    val status = when (state) {
        SpeechState.Listening -> stringResource(R.string.voice_listening)
        SpeechState.Thinking -> stringResource(R.string.voice_thinking)
        else -> null
    }
    val line = if (state is SpeechState.Speaking) state.utterance.text else lastSpoken.orEmpty()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(colors.background))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.voice_dismiss),
                onClick = onDismiss,
            ),
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Canvas(
                Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .height(280.dp)
                    // Decorative: the same information is in the text below.
                    .clearAndSetSemantics { },
            ) { drawMeter(pack, meter, state, reduceMotion) }

            Column(
                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (status != null) {
                    Text(status, color = text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                }
                if (line.isNotEmpty()) {
                    Text(line, color = text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onSayAgain, colors = ButtonDefaults.outlinedButtonColors(contentColor = text)) {
                    Text(stringResource(R.string.voice_say_again))
                }
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(colors.low), contentColor = text),
                ) {
                    Text(stringResource(R.string.voice_stop))
                }
            }
        }
    }
}

/** What changes from frame to frame. Held in snapshot state and read only while drawing, so a frame redraws the canvas and recomposes nothing. */
private class MeterFrame {
    var centre by mutableFloatStateOf(0f)
    var side by mutableFloatStateOf(0f)
    var clockMillis by mutableLongStateOf(0L)
}

@Composable
private fun rememberMeterFrame(state: SpeechState, reduceMotion: Boolean): MeterFrame {
    val frame = remember { MeterFrame() }
    val current by rememberUpdatedState(state)
    // Frames are requested only by this effect, which leaves the composition with the overlay.
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        var last = androidx.compose.runtime.withFrameNanos { it }
        val start = last
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = (now - last) / NANOS_PER_MILLI
            last = now
            val clock = ((now - start) / NANOS_PER_MILLI).toLong()
            val target = when (val s = current) {
                is SpeechState.Speaking ->
                    if (s.level >= 0f) s.level else BarMeterModel.syntheticLevel(clock, s.utterance.text.hashCode())
                else -> 0f
            }
            frame.centre = BarMeterModel.smooth(frame.centre, target, dt)
            frame.side = BarMeterModel.smooth(
                frame.side,
                BarMeterModel.sideTarget(target),
                dt,
                BarMeterModel.SIDE_ATTACK_MS,
                BarMeterModel.SIDE_RELEASE_MS,
            )
            frame.clockMillis = clock
        }
    }
    return frame
}

private const val NANOS_PER_MILLI = 1_000_000f
private const val SEGMENT_FILL = 0.72f
private const val COLUMN_GAP_RATIO = 0.5f

private fun DrawScope.drawMeter(pack: ThemePack, frame: MeterFrame, state: SpeechState, reduceMotion: Boolean) {
    val colors = pack.colors
    val columns = pack.columns
    val maxCount = columns.max()
    val pitch = size.height / maxCount
    val segmentHeight = pitch * SEGMENT_FILL
    val columnWidth = size.width / (columns.size + (columns.size - 1) * COLUMN_GAP_RATIO)
    val gap = columnWidth * COLUMN_GAP_RATIO
    val corner = CornerRadius(segmentHeight / 3f)
    val midY = size.height / 2f
    val clock = frame.clockMillis
    val breath = BarMeterModel.breath(clock)

    columns.forEachIndexed { index, count ->
        val x = index * (columnWidth + gap)
        val isCentre = index == columns.size / 2
        val half = BarMeterModel.half(count)
        val level = when {
            reduceMotion -> BarMeterModel.STATIC_LEVEL
            state is SpeechState.Speaking -> if (isCentre) frame.centre else frame.side
            else -> 0f
        }
        val head = BarMeterModel.thinkingHead(clock, count)
        for (row in 0 until count) {
            val distance = abs(row - half)
            val top = midY - count * pitch / 2f + row * pitch + (pitch - segmentHeight) / 2f
            val ramp = Color(BarMeterModel.lerpColor(colors.low, colors.high, BarMeterModel.rampPosition(distance, count)))
            drawSegment(Color(colors.off), x, top, columnWidth, segmentHeight, corner)
            val intensity = when {
                state is SpeechState.Listening && !reduceMotion -> BarMeterModel.listeningGlow(distance, breath)
                state is SpeechState.Thinking && !reduceMotion ->
                    if (isCentre) BarMeterModel.thinkingGlow(count - 1 - row, head) else 0f
                BarMeterModel.isLit(distance, level, count) -> 1f
                else -> 0f
            }
            if (intensity > 0f) drawSegment(ramp.copy(alpha = intensity), x, top, columnWidth, segmentHeight, corner)
        }
    }
}

private fun DrawScope.drawSegment(color: Color, x: Float, y: Float, width: Float, height: Float, corner: CornerRadius) {
    drawRoundRect(color, topLeft = Offset(x, y), size = Size(width, height), cornerRadius = corner)
}

/**
 * Whether the system asks for less motion: animations switched off (animator duration scale 0, the "remove animations" setting).
 * Follows the setting live, so the overlay reacts without being recreated.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var reduce by remember { mutableStateOf(animationsOff(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = animationsOff(resolver)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        reduce = animationsOff(resolver)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduce
}

private fun animationsOff(resolver: android.content.ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
