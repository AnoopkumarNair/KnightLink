package knightlink.app.ui.voice

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dual VU Matrix Oscilloscope Visualizer.
 *
 * Driven by speech loudness level (0f to 1f) from LoudnessEnvelope.
 * Symmetrical 9-bar matrix expanding dynamically with voice cadence.
 * Adaptive to Portrait vs Landscape Car Mount viewports.
 */
@Composable
fun DualVuVoiceBox(
    level: Float,
    modifier: Modifier = Modifier,
    displayMode: VoiceBoxDisplayMode = VoiceBoxDisplayMode.ADAPTIVE,
) {
    val smoothLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "dualVuLevel",
    )

    val barScales = listOf(0.30f, 0.50f, 0.70f, 0.88f, 1.0f, 0.88f, 0.70f, 0.50f, 0.30f)
    val barColors = listOf(
        Color(0xFFFFAA00), Color(0xFF3388FF), Color(0xFFFFAA00),
        Color(0xFF66A3FF), Color(0xFFFFFFFF), Color(0xFF66A3FF),
        Color(0xFFFFAA00), Color(0xFF3388FF), Color(0xFFFFAA00),
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val isLandscape = when (displayMode) {
            VoiceBoxDisplayMode.LANDSCAPE_COCKPIT -> true
            VoiceBoxDisplayMode.PORTRAIT_HERO -> false
            VoiceBoxDisplayMode.FULLSCREEN_NIGHT_HUD -> false
            VoiceBoxDisplayMode.ADAPTIVE -> maxWidth >= 500.dp
        }

        val containerHeight = if (isLandscape) 78.dp else 145.dp
        val maxBarH = if (isLandscape) 52.dp else 100.dp
        val minBarH = if (isLandscape) 12.dp else 18.dp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF000000), RoundedCornerShape(14.dp))
                .border(1.5.dp, Color(0xFF224488), RoundedCornerShape(14.dp))
                .padding(if (isLandscape) 8.dp else 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(containerHeight)
                    .background(Color(0xFF000000), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF3366CC), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // Center Line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0x4466A3FF)),
                )

                // 9-Bar Symmetrical Waveform
                Row(
                    horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 6.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    barScales.forEachIndexed { idx, scale ->
                        val minH = minBarH * scale
                        val maxH = maxBarH * scale
                        val barH = minH + (maxH - minH) * smoothLevel
                        val color = barColors[idx]
                        val barW = if (isLandscape) 8.dp else 10.dp

                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .width(barW + 4.dp)
                                    .height(barH + 6.dp)
                                    .blur(6.dp)
                                    .background(color.copy(alpha = 0.35f + smoothLevel * 0.4f), RoundedCornerShape(3.dp)),
                            )
                            Box(
                                modifier = Modifier
                                    .width(barW)
                                    .height(barH)
                                    .background(color, RoundedCornerShape(3.dp)),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "DUAL MATRIX • VOICE RESONANCE",
                color = Color(0xFF66A3FF),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
            )
        }
    }
}
