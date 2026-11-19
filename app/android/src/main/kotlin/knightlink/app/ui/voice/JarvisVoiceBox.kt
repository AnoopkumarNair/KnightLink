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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * J.A.R.V.I.S. Stark Industries Holographic HUD Visualizer.
 *
 * Driven by speech loudness level (0f to 1f) from LoudnessEnvelope.
 * Features rotating concentric rings and an audio-reactive pulsing holographic core.
 * Adaptive to S25 Ultra Portrait (412dp) vs Landscape Car Mount (892dp).
 */
@Composable
fun JarvisVoiceBox(
    level: Float,
    modifier: Modifier = Modifier,
    displayMode: VoiceBoxDisplayMode = VoiceBoxDisplayMode.ADAPTIVE,
    cyanColor: Color = Color(0xFF00E5FF),
) {
    val smoothLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "jarvisLevel",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "jarvisSpin")
    val rotationCw by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "cw",
    )
    val rotationCcw by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ccw",
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val isLandscape = when (displayMode) {
            VoiceBoxDisplayMode.LANDSCAPE_COCKPIT -> true
            VoiceBoxDisplayMode.PORTRAIT_HERO -> false
            VoiceBoxDisplayMode.FULLSCREEN_NIGHT_HUD -> false
            VoiceBoxDisplayMode.ADAPTIVE -> maxWidth >= 500.dp
        }

        val hudSize: Dp = if (isLandscape) 74.dp else 135.dp
        val coreMinSize: Dp = if (isLandscape) 26.dp else 42.dp
        val coreGrowth: Dp = if (isLandscape) 18.dp else 28.dp

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF000000), RoundedCornerShape(12.dp))
                    .border(1.5.dp, Color(0xFF004455), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "STARK INDUSTRIES",
                        color = cyanColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        text = "J.A.R.V.I.S. PROTOCOLS",
                        color = Color(0xFF66B2FF),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                    )
                }

                // Rotating HUD circle
                Box(
                    modifier = Modifier.size(hudSize),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(hudSize)) {
                        rotate(rotationCw) {
                            drawCircle(
                                color = cyanColor.copy(alpha = 0.6f),
                                radius = size.minDimension / 2 - 4.dp.toPx(),
                                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)),
                            )
                        }
                        rotate(rotationCcw) {
                            drawArc(
                                color = cyanColor.copy(alpha = 0.85f),
                                startAngle = 0f,
                                sweepAngle = 100f,
                                useCenter = false,
                                style = Stroke(width = 2.dp.toPx()),
                            )
                            drawArc(
                                color = cyanColor.copy(alpha = 0.85f),
                                startAngle = 180f,
                                sweepAngle = 100f,
                                useCenter = false,
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }
                    }

                    val coreSize = coreMinSize + (coreGrowth * smoothLevel)
                    Box(
                        modifier = Modifier.size(
                            coreSize + 10.dp,
                        ).blur(8.dp).background(cyanColor.copy(alpha = 0.35f + smoothLevel * 0.45f), RoundedCornerShape(percent = 50)),
                    )
                    Box(modifier = Modifier.size(coreSize).background(cyanColor, RoundedCornerShape(percent = 50)))
                }

                Text(
                    text = if (smoothLevel > 0.05f) "VOICE: ACTIVE" else "STANDBY",
                    color = if (smoothLevel > 0.05f) cyanColor else Color(0xFF005577),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF000000), RoundedCornerShape(16.dp))
                    .border(2.dp, Color(0xFF005577), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(145.dp)
                        .background(Color(0xFF000000), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(hudSize)) {
                        val center = this.center
                        rotate(rotationCw) {
                            drawCircle(
                                color = cyanColor.copy(alpha = 0.65f),
                                radius = size.minDimension / 2 - 6.dp.toPx(),
                                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 0f)),
                            )
                        }
                        rotate(rotationCcw) {
                            drawArc(
                                color = cyanColor.copy(alpha = 0.85f),
                                startAngle = 0f,
                                sweepAngle = 110f,
                                useCenter = false,
                                style = Stroke(width = 2.5.dp.toPx()),
                            )
                            drawArc(
                                color = cyanColor.copy(alpha = 0.85f),
                                startAngle = 180f,
                                sweepAngle = 110f,
                                useCenter = false,
                                style = Stroke(width = 2.5.dp.toPx()),
                            )
                        }
                        drawLine(
                            color = cyanColor.copy(alpha = 0.25f),
                            start = center.copy(x = 0f),
                            end = center.copy(x = size.width),
                            strokeWidth = 1.dp.toPx(),
                        )
                        drawLine(
                            color = cyanColor.copy(alpha = 0.25f),
                            start = center.copy(y = 0f),
                            end = center.copy(y = size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }

                    val coreSize = coreMinSize + (coreGrowth * smoothLevel)
                    Box(
                        modifier = Modifier.size(
                            coreSize + 16.dp,
                        ).blur(12.dp).background(cyanColor.copy(alpha = 0.35f + smoothLevel * 0.45f), RoundedCornerShape(percent = 50)),
                    )
                    Box(modifier = Modifier.size(coreSize).background(cyanColor, RoundedCornerShape(percent = 50)))
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "STARK INDUSTRIES • J.A.R.V.I.S. HOLOGRAPHIC HUD",
                    color = cyanColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp,
                )
            }
        }
    }
}
