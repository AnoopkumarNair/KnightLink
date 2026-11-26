package knightlink.app.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Universal Voice Box Dispatcher for KnightLink.
 * Reads the archetype from the active .knightpack bundle (or persona name)
 * and renders the corresponding audio-reactive visualizer.
 * Supports S25 Ultra Portrait & Landscape adaptive modes.
 */
@Composable
fun VoiceBoxRenderer(
    archetype: String,
    level: Float,
    modifier: Modifier = Modifier,
    displayMode: VoiceBoxDisplayMode = VoiceBoxDisplayMode.ADAPTIVE,
) {
    val key = archetype.lowercase().replace(".", "").replace(" ", "").replace("-", "")
    when {
        "concentric" in key || "ring" in key -> {
            RingHudVoiceBox(level = level, modifier = modifier, displayMode = displayMode)
        }
        "matrix" in key || "waveform" in key || "vu" in key -> {
            DualVuVoiceBox(level = level, modifier = modifier, displayMode = displayMode)
        }
        else -> {
            // Default 3-column LED bar meter
            BarMeterVoiceBox(level = level, modifier = modifier, displayMode = displayMode)
        }
    }
}
