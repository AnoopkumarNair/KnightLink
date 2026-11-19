package knightlink.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import knightlink.voice.api.LoudnessEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Zero-latency automotive alert player.
 * Plays pre-rendered alert WAVs (<10 ms AudioTrack) and streams 50 FPS loudness envelopes to the visualizer.
 */
class KnightVoicePlayer(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val _visualizerLoudness = MutableStateFlow(0f)
    val visualizerLoudness: StateFlow<Float> = _visualizerLoudness.asStateFlow()

    private var activeJob: Job? = null

    /**
     * Plays a pre-rendered alert instantly if available in [packDir].
     * Returns true if the alert was found and scheduled for playback; false otherwise.
     */
    fun playInstantAlert(packDir: File, eventId: String, volume: Float = 1.0f): Boolean {
        val audioFile = File(packDir, "audio/$eventId.wav")
        if (!audioFile.exists()) return false

        val envFile = File(packDir, "audio/$eventId.env.json")

        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                playAudioFile(audioFile, envFile, volume)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play instant alert $eventId", e)
            } finally {
                _visualizerLoudness.value = 0f
            }
        }
        return true
    }

    /**
     * Stops any currently playing alert audio and resets visualizer loudness.
     */
    fun stop() {
        activeJob?.cancel()
        activeJob = null
        _visualizerLoudness.value = 0f
    }

    private suspend fun playAudioFile(audioFile: File, envFile: File, volume: Float) {
        val bytes = audioFile.readBytes()
        val audio = WavFile.parse(bytes)
        val sampleRate = audio.sampleRate
        val samples = audio.samples
        val byteCount = samples.size * 2

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(byteCount)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            track.setVolume(VoiceSettings.clampVolume(volume))
            track.write(samples, 0, samples.size)

            val envelope: List<Float> = if (envFile.exists()) {
                runCatching { json.decodeFromString<List<Float>>(envFile.readText()) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            withAudioFocus {
                track.play()
                if (envelope.isNotEmpty()) {
                    for (frame in envelope) {
                        if (!currentCoroutineContext().isActive) break
                        _visualizerLoudness.value = frame
                        delay(FRAME_DELAY_MS)
                    }
                } else {
                    val computedEnv = LoudnessEnvelope(samples, sampleRate)
                    val frameCount = (audio.durationMillis / FRAME_DELAY_MS).toInt()
                    for (i in 0 until frameCount) {
                        if (!currentCoroutineContext().isActive) break
                        val samplePos = ((i * FRAME_DELAY_MS * sampleRate) / 1000).toLong()
                        _visualizerLoudness.value = computedEnv.levelAt(samplePos)
                        delay(FRAME_DELAY_MS)
                    }
                }
            }
        } finally {
            try {
                track.stop()
                track.release()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun withAudioFocus(block: suspend () -> Unit) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        val granted = audioManager?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        try {
            block()
        } finally {
            withContext(NonCancellable) {
                if (granted) audioManager?.abandonAudioFocusRequest(request)
            }
        }
    }

    private companion object {
        const val TAG = "KnightVoicePlayer"
        const val FRAME_DELAY_MS = 20L // 50 FPS
    }
}
