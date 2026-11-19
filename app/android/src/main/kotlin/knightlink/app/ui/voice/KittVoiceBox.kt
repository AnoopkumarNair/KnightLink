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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * K.I.T.T. 3-Column Segmented LED Voice Box for Compose Multiplatform (Android / S25 Ultra, Desktop, iOS).
 *
 * Symmetrical vertical expansion from horizontal centerline:
 * - Canonical 1982 Knight Rider VU-meter look with rectangular LED segment blocks.
 * - Center Column: 14 segmented LEDs (7 pairs above/below center).
 * - Left/Right Columns: 10 segmented LEDs (5 pairs above/below center).
 * - Resting idle state faintly glows at center divider line.
 * - Pure true-black background (#000000) for 0% OLED power drain on S25 Ultra AMOLED.
 */
@Composable
fun KittVoiceBox(
    level: Float,
    modifier: Modifier = Modifier,
    displayMode: VoiceBoxDisplayMode = VoiceBoxDisplayMode.ADAPTIVE,
    primaryColor: Color = Color(0xFFFF1E27),
    ambientGlowColor: Color = Color(0x99FF0000),
) {
    // Smooth incoming 20ms LoudnessEnvelope level ticks
    val smoothLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "kittLevel",
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val isLandscape = when (displayMode) {
            VoiceBoxDisplayMode.LANDSCAPE_COCKPIT -> true
            VoiceBoxDisplayMode.PORTRAIT_HERO -> false
            VoiceBoxDisplayMode.FULLSCREEN_NIGHT_HUD -> false
            VoiceBoxDisplayMode.ADAPTIVE -> maxWidth >= 500.dp
        }

        if (isLandscape) {
            // ==========================================================
            // 🚗 LANDSCAPE COCKPIT MODE (892 x 412 dp phone car-mount)
            // Ultra-compact 82dp height so live telemetry gauges stay in view
            // ==========================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF000000))
                    .border(1.5.dp, Color(0xFF2A0808), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Left Wing: Status indicators
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = "KNIGHT 2000",
                        color = Color(0xFFFF3333),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DashboardPillButton(text = "AUTO", bg = Color(0xFFE6B800), fg = Color.Black)
                        DashboardPillButton(text = "NORMAL", bg = Color(0xFFCC2900), fg = Color.White)
                        DashboardPillButton(text = "PURSUIT", bg = Color(0xFF7A0000), fg = Color(0xFFFF9999))
                    }
                }

                // Center Bezel: 3-Column Segmented LED Array
                Box(
                    modifier = Modifier
                        .width(180.dp)
                        .height(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF000000))
                        .border(1.dp, Color(0xFF330000), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // Center zero-guideline
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0x55FF2222)),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KittSegmentedLedColumn(
                            pairCount = 4,
                            level = smoothLevel * 0.8f,
                            segmentWidth = 16.dp,
                            segmentHeight = 4.dp,
                            primaryColor = primaryColor,
                        )
                        KittSegmentedLedColumn(pairCount = 6, level = smoothLevel, segmentWidth = 22.dp, segmentHeight = 4.dp, primaryColor = primaryColor)
                        KittSegmentedLedColumn(
                            pairCount = 4,
                            level = smoothLevel * 0.8f,
                            segmentWidth = 16.dp,
                            segmentHeight = 4.dp,
                            primaryColor = primaryColor,
                        )
                    }
                }

                // Right Wing: Telemetry label
                Text(
                    text = "VOICE ACTIVE",
                    color = if (smoothLevel > 0.05f) Color(0xFFFF2222) else Color(0xFF661111),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                )
            }
        } else {
            // ==========================================================
            // 📱 PORTRAIT HERO MODE (412 x 892 dp phone portrait)
            // ==========================================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF000000))
                    .border(2.dp, Color(0xFF2A0808), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Outer Display Bezel Frame (Towering Knight Rider TV Proportions)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF000000))
                        .border(1.5.dp, Color(0xFF330000), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // Center Horizontal Zero-Reference Guideline
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0x55FF2222)),
                    )

                    // 3-Column Segmented LED Array (20 Segments Center, 14 Sides)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KittSegmentedLedColumn(
                            pairCount = 7,
                            level = smoothLevel * 0.85f,
                            segmentWidth = 28.dp,
                            segmentHeight = 6.dp,
                            primaryColor = primaryColor,
                        )
                        KittSegmentedLedColumn(pairCount = 10, level = smoothLevel, segmentWidth = 36.dp, segmentHeight = 6.dp, primaryColor = primaryColor)
                        KittSegmentedLedColumn(
                            pairCount = 7,
                            level = smoothLevel * 0.85f,
                            segmentWidth = 28.dp,
                            segmentHeight = 6.dp,
                            primaryColor = primaryColor,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DashboardPillButton(text = "AUTO", bg = Color(0xFFE6B800), fg = Color.Black)
                    DashboardPillButton(text = "NORMAL", bg = Color(0xFFCC2900), fg = Color.White)
                    DashboardPillButton(text = "PURSUIT", bg = Color(0xFF7A0000), fg = Color(0xFFFF9999))
                }
            }
        }
    }
}

/**
 * Renders a canonical K.I.T.T. LED column with symmetrical segments expanding outward
 * from the center zero-guideline.
 */
@Composable
private fun KittSegmentedLedColumn(
    pairCount: Int,
    level: Float,
    segmentWidth: Dp,
    segmentHeight: Dp,
    primaryColor: Color,
) {
    val activePairs = (pairCount * level.coerceIn(0f, 1f)).toInt().coerceAtMost(pairCount)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Upper Bank (descending toward center)
        for (i in pairCount downTo 1) {
            val isLit = i <= activePairs
            val isIdle = (i == 1 && !isLit)
            val segColor = when {
                isLit -> primaryColor
                isIdle -> Color(0xFF770B0E)
                else -> Color(0xFF190305)
            }
            Box(
                modifier = Modifier
                    .width(segmentWidth)
                    .height(segmentHeight)
                    .padding(bottom = 2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(segColor),
            )
        }

        // Center Horizontal Divider Mark
        Box(
            modifier = Modifier
                .width(segmentWidth + 4.dp)
                .height(2.dp)
                .background(Color(0xFFFF2222)),
        )

        // Lower Bank (ascending away from center)
        for (i in 1..pairCount) {
            val isLit = i <= activePairs
            val isIdle = (i == 1 && !isLit)
            val segColor = when {
                isLit -> primaryColor
                isIdle -> Color(0xFF770B0E)
                else -> Color(0xFF190305)
            }
            Box(
                modifier = Modifier
                    .width(segmentWidth)
                    .height(segmentHeight)
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(segColor),
            )
        }
    }
}

@Composable
private fun DashboardPillButton(
    text: String,
    bg: Color,
    fg: Color,
) {
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(3.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.SansSerif,
        )
    }
}
