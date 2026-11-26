package knightlink.app.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class KnightPackManifest(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val engine: String = "google-litertlm-qwen3",
    @SerialName("persona_id") val personaId: String,
    val name: String,
    @SerialName("driver_name") val driverName: String = "Driver",
    val description: String = "",
    val version: String = "1.0.0",
    val author: String = "VoiceMaker",
    @SerialName("has_prerendered_audio") val hasPrerenderedAudio: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class VisualizerConfig(
    val archetype: String = "bar-meter",
    @SerialName("primary_color") val primaryColor: String = "#FF1111",
    @SerialName("secondary_color") val secondaryColor: String = "#880000",
    @SerialName("background_color") val backgroundColor: String = "#1A0202",
    @SerialName("bar_count") val barCount: Int = 3,
    @SerialName("glow_intensity") val glowIntensity: Float = 0.8f,
    @SerialName("attack_ms") val attackMs: Int = 20,
    @SerialName("decay_ms") val decayMs: Int = 65,
    val sensitivity: Float = 1.0f,
)

@Serializable
data class PersonaMetadata(
    @SerialName("persona_id") val personaId: String,
    val name: String,
    @SerialName("driver_name") val driverName: String = "Driver",
    @SerialName("system_prompt") val systemPrompt: String = "",
)

@Serializable
data class CarDialogueMatrix(
    val events: Map<String, String> = emptyMap(),
)

data class InstalledKnightPack(
    val dir: File,
    val manifest: KnightPackManifest,
    val visualizer: VisualizerConfig,
    val persona: PersonaMetadata?,
    val lexicon: LexiconEngine,
    val dialogue: Map<String, String> = emptyMap(),
)
