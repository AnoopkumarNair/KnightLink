# KnightPack Specification & On-Device Integration Guide

**Document ID:** `KNIGHTLINK-SPEC-KP-2026-V1`  
**Target Milestone:** KnightLink Milestone M3 (Alerts & Visualizer) & M7 (Personas & Themes)  
**Companion Tooling:** VoiceMaker (`D:\Projects\VoiceMaker`)  
**Status:** Approved Reference Specification  

---

## 1. Overview & Architectural Role

KnightLink is a voice-first automotive companion app. To provide deep immersion without bundling copyrighted/trademarked film or TV assets into the base repository or store builds, VoiceMaker compiles standalone, side-loadable persona packages called **KnightPacks** (`.knightpack`).

A `.knightpack` bundle completely encapsulates:
1. **Persona Metadata & Prompting**: Driver call-sign, personality rules, and LLM system prompts.
2. **Reactive Visualizer Configuration**: Multi-column LED bar meters, concentric rings, or waveforms with custom shader colors and ballistics.
3. **Automotive Phonetic Lexicon**: Phonetic expansions for engineering acronyms (`DPF`, `TPMS`, `EGT`, `ATF`, `DEF`, `DTC`, `ECU`).
4. **Pre-Rendered Golden Safety Alerts (ROM Audio)**: 17 critical vehicle alerts with pre-calculated 50 FPS loudness envelopes for **instant (<10 ms) zero-latency playback**.
5. **Zero-Shot Emotional Voice References**: Studio-isolated, noise-gated reference audio clips and acoustic styles for on-device neural synthesis.

```
┌────────────────────────────────────────────────────────────────────────┐
│                        KnightLink Voice Runtime                        │
├───────────────────────────────────┬────────────────────────────────────┤
│     Pre-Rendered Safety Alerts    │     Dynamic Telemetry Responses    │
│       (17 Critical Triggers)      │       (Ad-hoc Spoken Answers)      │
├───────────────────────────────────┼────────────────────────────────────┤
│ • alert.coolant_overheat          │ • "What is my current DPF soot?"   │
│ • alert.tpms_rapid_leak           │ • "How many km until next regen?"  │
│ • alert.alternator_fault          │ • "Is it safe to shut down?"       │
├───────────────────────────────────┼────────────────────────────────────┤
│ Instant (<10 ms) AudioTrack Play  │ Google LiteRT-LM Runtime           │
│ Pre-computed 50 FPS Envelope (.env│ (Qwen3-TTS 0.6B INT4/INT8 NPU)     │
│ 0% CPU/NPU Compute Overhead       │ Real-time 20 ms Envelope Extraction│
└───────────────────────────────────┴────────────────────────────────────┘
```

---

## 2. Container Structure & File Hierarchy

A `.knightpack` is a standard unencrypted ZIP archive with the `.knightpack` extension. When imported on Android, the app extracts it to the app-private files directory:
`context.filesDir/packs/{persona_id}/`

### Archive File Layout

```
{persona_id}.knightpack
├── manifest.json              # Package metadata, schema version, engine target
├── persona.json               # Identity, driver call-sign, and system prompt
├── visualizer.json            # Visualizer archetype, ballistics, and theme colors
├── audio_fx.json              # Causal SOS filter parameters (bandpass, mid-boost, reverb)
├── lexicon.json               # Automotive acronym pronunciation replacements
├── car_dialogue.json          # Complete 35-event automotive dialogue matrix
├── audio/                     # Pre-rendered safety alert WAVs + 50 FPS envelopes
│   ├── alert.alternator_fault.wav
│   ├── alert.alternator_fault.env.json
│   ├── alert.coolant_overheat.wav
│   ├── alert.coolant_overheat.env.json
│   ├── alert.dpf_high_soot.wav
│   ├── alert.dpf_high_soot.env.json
│   ├── alert.tpms_rapid_leak.wav
│   ├── alert.tpms_rapid_leak.env.json
│   └── ... (17 total alerts)
└── voice/                     # Neural zero-shot cloning references
    ├── reference.wav          # Primary clean voice reference (48kHz mono PCM)
    ├── reference.txt          # Sidecar transcript
    ├── style.npy              # 512-dim acoustic style latent tensor
    └── emotion_vault/         # Contextual emotional anchors
        ├── calm.wav / calm.txt
        ├── urgent.wav / urgent.txt
        └── witty.wav / witty.txt
```

---

## 3. Core Schemas & Data Contracts

### 3.1 `manifest.json`

```json
{
  "schema_version": 1,
  "engine": "google-litertlm-qwen3",
  "persona_id": "kitt",
  "name": "K.I.T.T.",
  "driver_name": "Michael",
  "description": "Knight Industries Two Thousand - Automotive AI",
  "version": "1.0.0",
  "author": "VoiceMaker",
  "has_prerendered_audio": true,
  "created_at": "2026-11-18T18:36:40.620527+00:00"
}
```

### 3.2 `visualizer.json`

Specifies the hardware UI rendering parameters:

```json
{
  "archetype": "bar-meter",
  "primary_color": "#FF0000",
  "secondary_color": "#880000",
  "background_color": "#110000",
  "bar_count": 3,
  "glow_intensity": 0.8,
  "attack_ms": 20,
  "decay_ms": 65,
  "sensitivity": 1.2
}
```
* **Archetypes**: `bar-meter` (3-column symmetric LED), `concentric-rings` (circular HUD arcs), `matrix-waveform` (oscilloscope grid), `pulsing-orb` (spherical particle halo).
* **Ballistics**: `attack_ms` (20 ms) and `decay_ms` (65 ms) match analog LED driver decay for authentic phosphor/LED feel.

### 3.3 `audio/{event_id}.env.json` (50 FPS Envelope)

Array of float loudness values normalized between `0.0` and `1.0`, evaluated in 20 ms time steps (50 frames per second):

```json
[
  0.0, 0.05, 0.22, 0.65, 0.89, 0.94, 0.82, 0.45, 0.12, 0.0, ...
]
```
During audio playback, the Kotlin UI animator reads the envelope array at index `t_millis / 20` to update LED segments with **zero FFT overhead during driving**.

---

## 4. Android Integration & Dependencies

### 4.1 Required Gradle Dependencies

In `app/android/build.gradle.kts` (or a dedicated `platform/voice-android` module):

```kotlin
dependencies {
    // 1. Serialization for Manifest & Envelopes
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 2. Google LiteRT-LM (On-Device Neural Voice & LLM Runtime)
    implementation("com.google.ai.edge.litert:litert-lm:0.1.0")
    // or standard LiteRT runtime if using custom C++ NDK bindings:
    // implementation("com.google.ai.edge.litert:litert:1.0.1")

    // 3. High-Performance Audio Playback
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // Note: Low-latency alerts bypass ExoPlayer and use direct android.media.AudioTrack

    // 4. Coroutines & Reactive Flows
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

### 4.2 On-Device Model Artifacts

For on-demand dynamic responses, the on-device synthesis engine requires the quantized Qwen3-TTS 0.6B weights:
* **Model Format**: `qwen3_tts_int8.litertlm` (approx. 650 MB) or `qwen3_tts_int4.litertlm` (approx. 360 MB).
* **Location**: Stored in `context.filesDir/models/litert/`.
* **Execution**: Dispatched to Snapdragon NPU via LiteRT NPU Delegate (Snapdragon 8 Elite yields ~350 ms TTFT).

---

## 5. KnightLink Kotlin Implementation Architecture

### 5.1 Package Import & Extraction Manager

```kotlin
package in.byteheaven.knightlink.voice

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class KnightPackManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val packsDir = File(context.filesDir, "packs").apply { mkdirs() }

    fun installPack(inputStream: InputStream): Result<KnightPackManifest> = runCatching {
        var manifest: KnightPackManifest? = null
        val tempExtractDir = File(context.cacheDir, "kp_import_${System.currentTimeMillis()}").apply { mkdirs() }

        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val targetFile = File(tempExtractDir, entry.name)
                if (entry.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    targetFile.parentFile?.mkdirs()
                    targetFile.outputStream().use { fos -> zis.copyTo(fos) }
                    if (entry.name == "manifest.json") {
                        manifest = json.decodeFromString<KnightPackManifest>(targetFile.readText())
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val personaId = manifest?.personaId ?: error("Invalid .knightpack: missing manifest.json")
        val finalPackDir = File(packsDir, personaId)
        if (finalPackDir.exists()) finalPackDir.deleteRecursively()
        tempExtractDir.renameTo(finalPackDir)

        manifest!!
    }

    fun getInstalledPacks(): List<File> =
        packsDir.listFiles { f -> f.isDirectory && File(f, "manifest.json").exists() }?.toList().orEmpty()
}
```

### 5.2 Zero-Latency Safety Alert Playback with Symmetrical LED Animation

```kotlin
package in.byteheaven.knightlink.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File

class KnightVoicePlayer(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _visualizerLoudness = MutableStateFlow(0f)
    val visualizerLoudness: StateFlow<Float> = _visualizerLoudness

    private val playerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Plays pre-rendered alert with 0 ms synthesis lag and emits 50 FPS loudness envelope.
     */
    fun playInstantAlert(packDir: File, eventId: String) {
        val audioFile = File(packDir, "audio/$eventId.wav")
        val envFile = File(packDir, "audio/$eventId.env.json")

        if (!audioFile.exists()) return

        playerScope.launch {
            val pcmBytes = audioFile.readBytes()
            // Strip 44-byte RIFF header for raw AudioTrack streaming
            val rawPcm = pcmBytes.copyOfRange(44, pcmBytes.size)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(rawPcm.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(rawPcm, 0, rawPcm.size)

            // Launch envelope animation coroutine in sync
            val envelope: List<Float> = if (envFile.exists()) {
                json.decodeFromString(envFile.readText())
            } else emptyList()

            track.play()

            if (envelope.isNotEmpty()) {
                for (frame in envelope) {
                    _visualizerLoudness.value = frame
                    delay(20) // 50 FPS
                }
            } else {
                val durationMs = (rawPcm.size.toLong() * 1000) / (48000 * 2)
                delay(durationMs)
            }

            _visualizerLoudness.value = 0f
            track.release()
        }
    }
}
```

---

## 6. Power & Driving Safety Constraints

1. **Foreground Service Decoupling**:
   - Spoken alerts and audio playback run inside KnightLink's foreground `SessionService`.
   - If the user switches apps or the phone screen turns off, **speech audio continues uninterrupted**, but Compose visualizer frame renders are paused to eliminate GPU battery draw.
2. **Deterministic Precedence**:
   - Pre-rendered alerts (`alert.*`) take instant priority over dynamic LLM queries.
   - If an alert occurs while dynamic speech is playing, the dynamic speech is ducked/cancelled immediately.
3. **Automotive Acronym Lexicon Application**:
   - Before any text is passed to on-device TTS or speech synthesis, it must pass through `lexicon.json` regex mapping so strings like `"DPF"` and `"TPMS"` are vocalized as `"D P F"` and `"T P M S"`.
