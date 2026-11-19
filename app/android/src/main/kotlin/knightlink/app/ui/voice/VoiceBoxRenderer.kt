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
        "concentric" in key || "ring" in key || "jarvis" in key -> {
            JarvisVoiceBox(level = level, modifier = modifier, displayMode = displayMode)
        }
        "matrix" in key || "waveform" in key || "optimus" in key -> {
            OptimusVoiceBox(level = level, modifier = modifier, displayMode = displayMode)
        }
        else -> {
            // Default canonical Knight 2000 3-Bar Capsule
            KittVoiceBox(level = level, modifier = modifier, displayMode = displayMode)
        }
    }
}
