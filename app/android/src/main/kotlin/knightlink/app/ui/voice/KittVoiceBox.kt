package knightlink.app.ui.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Display modes for Phone & Car Dashboard Orientations on Samsung Galaxy S25 Ultra:
 * - ADAPTIVE: Auto-detects based on available container width & aspect ratio.
 * - PORTRAIT_HERO: Standard vertical phone layout (412 x 892 dp viewport).
 * - LANDSCAPE_COCKPIT: Compact horizontal banner (892 x 412 dp horizontal phone mount).
 * - FULLSCREEN_NIGHT_HUD: Immersive blacked-out AMOLED night driving screen.
 */
enum class VoiceBoxDisplayMode {
    ADAPTIVE,
    PORTRAIT_HERO,
    LANDSCAPE_COCKPIT,
    FULLSCREEN_NIGHT_HUD,
}

/**
 * K.I.T.T. voice box, drawn like the one on the Knight Rider dashboard: a thin red line above three columns of red LED
 * bars that grow outward from the middle (the centre column tallest), each bar a little narrower toward the ends so the
 * column reads as a barrel, and a small three-light scanner under them. Everything is drawn on one canvas on pure black,
 * so it keeps its proportions at any width and never crops.
 */
@Composable
fun KittVoiceBox(
    level: Float,
    modifier: Modifier = Modifier,
    displayMode: VoiceBoxDisplayMode = VoiceBoxDisplayMode.ADAPTIVE,
    primaryColor: Color = Color(0xFFFF1E27),
    ambientGlowColor: Color = Color(0x99FF0000),
) {
    val smoothLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "kittLevel",
    )
    val scan by rememberInfiniteTransition(label = "kittScan").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SCAN_PERIOD_MS.toInt(), easing = LinearEasing), RepeatMode.Restart),
        label = "kittScanPhase",
    )
    val sized = when (displayMode) {
        VoiceBoxDisplayMode.LANDSCAPE_COCKPIT -> modifier.height(120.dp).aspectRatio(ASPECT, matchHeightConstraintsFirst = true)
        else -> modifier.fillMaxWidth().aspectRatio(ASPECT)
    }
    Canvas(sized) { drawKitt(smoothLevel, scan, primaryColor, ambientGlowColor) }
}

/** Width over height of the whole drawing: the line is as wide as the drawing, the bars sit in the middle three quarters. */
private const val ASPECT = 0.74f
private const val CENTRE_PAIRS = 11
private const val SIDE_PAIRS = 6
private const val SIDE_LEVEL = 0.85f
private val OFF = Color(0xFF1B0204)
private val IDLE = Color(0xFF6E0A0E)

private fun DrawScope.drawKitt(level: Float, phase: Float, lit: Color, glow: Color) {
    val w = size.width
    val h = size.height
    // The line above.
    drawRect(lit, topLeft = Offset(w * 0.05f, h * 0.03f), size = Size(w * 0.90f, h * 0.012f))

    val top = h * 0.12f
    val regionHeight = h * 0.74f
    val midY = top + regionHeight / 2f
    val pitch = regionHeight / (CENTRE_PAIRS * 2)
    val barHeight = pitch * 0.70f
    val sideWidth = w * 0.215f
    val centreWidth = w * 0.245f
    val gap = w * 0.06f
    val left = (w - (2 * sideWidth + centreWidth + 2 * gap)) / 2f
    drawColumn(left, sideWidth, SIDE_PAIRS, level * SIDE_LEVEL, midY, pitch, barHeight, lit, glow)
    drawColumn(left + sideWidth + gap, centreWidth, CENTRE_PAIRS, level, midY, pitch, barHeight, lit, glow)
    drawColumn(left + sideWidth + gap + centreWidth + gap, sideWidth, SIDE_PAIRS, level * SIDE_LEVEL, midY, pitch, barHeight, lit, glow)

    // The scanner under the bars: one bright light and two fading ones that follow it back and forth.
    val squareSide = h * 0.036f
    val scanY = h * 0.915f
    val travel = w * 0.30f
    val head = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
    val direction = if (phase < 0.5f) 1f else -1f
    for (i in 0..2) {
        val x = w * 0.5f - travel / 2f + (head * travel) - i * direction * squareSide * 1.5f
        val alpha = when (i) {
            0 -> 1f
            1 -> 0.45f
            else -> 0.18f
        }
        drawRect(lit.copy(alpha = alpha), topLeft = Offset(x, scanY), size = Size(squareSide, squareSide))
    }
}

private const val SCAN_PERIOD_MS = 1800L

private fun DrawScope.drawColumn(
    x: Float,
    width: Float,
    pairs: Int,
    level: Float,
    midY: Float,
    pitch: Float,
    barHeight: Float,
    lit: Color,
    glow: Color,
) {
    val active = (pairs * level.coerceIn(0f, 1f)).roundToInt().coerceIn(0, pairs)
    val corner = CornerRadius(barHeight / 4f)
    for (k in 0 until pairs * 2) {
        val offset = k - pairs + 0.5f
        val pair = (abs(offset) + 0.5f).toInt()
        // Bars are widest at the middle and about 70 % as wide at the ends.
        val shape = (0.70f + 0.30f * cos(PI.toFloat() / 2f * (abs(offset) / pairs))).coerceIn(0.7f, 1f)
        val barWidth = width * shape
        val topLeft = Offset(x + (width - barWidth) / 2f, midY + offset * pitch - barHeight / 2f)
        val on = pair <= active
        val color = when {
            on -> lit
            pair == 1 -> IDLE
            else -> OFF
        }
        if (on) {
            drawRoundRect(
                glow.copy(alpha = 0.22f),
                Offset(topLeft.x - barHeight * 0.3f, topLeft.y - barHeight * 0.3f),
                Size(
                    barWidth + barHeight * 0.6f,
                    barHeight * 1.6f,
                ),
                corner,
            )
        }
        drawRoundRect(color, topLeft, Size(barWidth, barHeight), corner)
    }
}
