package knightlink.app.ui.voice

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
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
 * K.I.T.T. voice box, drawn like the dashboard original: three columns of rounded red LED bars growing outward from a
 * glowing horizontal centre line (the middle column tallest), brightest at the centre and fading toward the ends, with a
 * faint trace of the unlit bars. One canvas on pure black, so it keeps its proportions at any width and never crops.
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
    // The drawing keeps its proportions and is as large as the width, the height on offer and a cap allow: a box that insists on a width and a
    // proportion fits neither a short landscape screen nor a long spoken line under it.
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val cap = if (displayMode == VoiceBoxDisplayMode.LANDSCAPE_COCKPIT) LANDSCAPE_MAX_HEIGHT else PORTRAIT_MAX_HEIGHT
        val byWidth = maxWidth / ASPECT
        val byHeight = if (constraints.hasBoundedHeight) maxHeight else Dp.Infinity
        val height = minOf(cap, byWidth, byHeight)
        Canvas(Modifier.size(height * ASPECT, height)) { drawKitt(smoothLevel, primaryColor, ambientGlowColor) }
    }
}

private val PORTRAIT_MAX_HEIGHT = 360.dp
private val LANDSCAPE_MAX_HEIGHT = 120.dp

/** Width over height of the whole drawing. */
private const val ASPECT = 0.8f
private const val CENTRE_PAIRS = 11
private const val SIDE_PAIRS = 6
private const val SIDE_LEVEL = 0.85f

/** How bright a bar is by its distance from the middle: 1 at the centre line, [EDGE_BRIGHTNESS] at the far end. */
private const val EDGE_BRIGHTNESS = 0.22f
private const val UNLIT_BRIGHTNESS = 0.16f

private fun DrawScope.drawKitt(level: Float, lit: Color, glow: Color) {
    val w = size.width
    val h = size.height
    val midY = h / 2f
    val pitch = h * 0.92f / (CENTRE_PAIRS * 2)
    val barHeight = pitch * 0.72f
    val sideWidth = w * 0.17f
    val centreWidth = w * 0.19f
    val gap = w * 0.07f
    val left = (w - (2 * sideWidth + centreWidth + 2 * gap)) / 2f

    // The centre line, fading out toward both sides of the drawing.
    val fade = Brush.horizontalGradient(listOf(Color.Transparent, glow.copy(alpha = 0.55f), glow, glow.copy(alpha = 0.55f), Color.Transparent))
    drawRect(fade, topLeft = Offset(0f, midY - 1.5f), size = Size(w, 3f))
    drawRect(fade, topLeft = Offset(0f, midY - 6f), size = Size(w, 12f), alpha = 0.18f)

    drawColumn(left, sideWidth, SIDE_PAIRS, level * SIDE_LEVEL, midY, pitch, barHeight, lit)
    drawColumn(left + sideWidth + gap, centreWidth, CENTRE_PAIRS, level, midY, pitch, barHeight, lit)
    drawColumn(left + sideWidth + gap + centreWidth + gap, sideWidth, SIDE_PAIRS, level * SIDE_LEVEL, midY, pitch, barHeight, lit)
}

private fun DrawScope.drawColumn(
    x: Float,
    width: Float,
    pairs: Int,
    level: Float,
    midY: Float,
    pitch: Float,
    barHeight: Float,
    lit: Color,
) {
    val active = (pairs * level.coerceIn(0f, 1f)).roundToInt().coerceIn(0, pairs)
    val corner = CornerRadius(barHeight / 3f)
    for (k in 0 until pairs * 2) {
        val offset = k - pairs + 0.5f
        val pair = (abs(offset) + 0.5f).toInt()
        val distance = abs(offset) / pairs
        // Brightest at the centre line, fading toward the ends; the same fade applies to the dim unlit bars.
        val brightness = 1f - (1f - EDGE_BRIGHTNESS) * distance
        val on = pair <= active
        val alpha = if (on) brightness else brightness * UNLIT_BRIGHTNESS
        val topLeft = Offset(x, midY + offset * pitch - barHeight / 2f)
        if (on) {
            val halo = barHeight * 0.45f
            drawRoundRect(lit.copy(alpha = alpha * 0.09f), Offset(topLeft.x - halo, topLeft.y - halo), Size(width + halo * 2, barHeight + halo * 2), corner)
        }
        drawRoundRect(lit.copy(alpha = alpha), topLeft, Size(width, barHeight), corner)
    }
}
