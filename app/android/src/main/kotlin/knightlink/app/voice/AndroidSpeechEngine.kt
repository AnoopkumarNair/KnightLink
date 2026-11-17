package knightlink.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import knightlink.voice.api.LoudnessEnvelope
import knightlink.voice.api.SpeechEngine
import knightlink.voice.api.SpeechEngineException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Speaks with the phone's own text-to-speech engine, fully offline, and reports the loudness of what is heard.
 *
 * The normal path renders each line to a WAV file with `synthesizeToFile`, reads the samples ([WavFile]), and plays them itself
 * with an [AudioTrack]. Owning the samples is what makes the loudness known: the track's playback position says which 20 ms of
 * the line is audible now, and the [LoudnessEnvelope] of the samples gives its level, so the screen can move in step with the
 * voice. `TextToSpeech.speak` plays straight to the speaker and offers no such view.
 *
 * Anything that goes wrong on that path (the engine refusing or failing to render, a file that is not 16-bit PCM, an AudioTrack
 * that cannot be made) falls back to plain `TextToSpeech.speak`: the line is still heard, only without levels, and the screen then
 * animates on its own. If the engine itself is missing or never starts, [speak] throws [SpeechEngineException].
 *
 * Audio focus: before a line, `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` is requested, so music and podcasts get quieter while the
 * voice talks and recover after. A refused request does not stop the line: the usage is navigation guidance, which the system
 * lets be heard over other audio anyway, and a driver should hear "Lost the adapter" even if another app holds focus.
 *
 * Must be created on a thread with a looper (the main thread): [TextToSpeech] delivers its initialisation callback there.
 *
 * @param volume the volume setting, 0.1 to 1.0, read for each line
 */
class AndroidSpeechEngine(context: Context, private val volume: () -> Float) : SpeechEngine {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // Completed once with whether the engine started. Late starts still count: a slow engine works from the next line on.
    private val initialised = CompletableDeferred<Boolean>()

    // utterance id -> completed with true when the engine finished it, false when it stopped or failed.
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val counter = AtomicLong()

    private val tts: TextToSpeech = TextToSpeech(appContext) { status -> initialised.complete(status == TextToSpeech.SUCCESS) }

    // Whether the language has been chosen, and whether there is one. Guarded by this.
    private var languageChosen = false
    private var languageAvailable = false

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    finish(utteranceId, true)
                }

                @Deprecated("Replaced by onError(String, Int); the engine still calls this one on old versions")
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    finish(utteranceId, false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w(TAG, "The speech engine reported error $errorCode")
                    finish(utteranceId, false)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    finish(utteranceId, false)
                }
            },
        )
    }

    private fun finish(utteranceId: String?, ok: Boolean) {
        utteranceId?.let { pending[it]?.complete(ok) }
    }

    override suspend fun speak(text: String, onLevel: (Float) -> Unit) {
        awaitEngine()
        val audio = try {
            synthesize(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not render the line to audio (${e::class.simpleName}); using plain speech", e)
            null
        }
        if (audio != null) {
            try {
                play(audio, onLevel)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not play the rendered audio (${e::class.simpleName}); using plain speech", e)
            }
        }
        speakDirectly(text)
    }

    /** Stops speaking and gives the engine back. The engine is not usable afterwards. */
    fun close() {
        pending.values.forEach { it.complete(false) }
        tts.stop()
        tts.shutdown()
    }

    private suspend fun awaitEngine() {
        val started = withTimeoutOrNull(INIT_TIMEOUT_MS) { initialised.await() }
            ?: throw SpeechEngineException("The speech engine did not start in time")
        if (!started) throw SpeechEngineException("No speech engine is available")
        chooseLanguage()
    }

    /** Picks the language once: see [pickLocale]. */
    private fun chooseLanguage() {
        synchronized(this) {
            if (languageChosen) return
            languageChosen = true
            tts.setAudioAttributes(attributes)
            val locale = pickLocale(Locale.getDefault()) { isUsable(tts.isLanguageAvailable(it)) }
            languageAvailable = locale != null && isUsable(tts.setLanguage(locale))
            if (!languageAvailable) Log.w(TAG, "The speech engine has no usable language; relying on its own default")
        }
    }

    private fun isUsable(availability: Int): Boolean =
        availability != TextToSpeech.LANG_MISSING_DATA && availability != TextToSpeech.LANG_NOT_SUPPORTED && availability >= TextToSpeech.LANG_AVAILABLE

    /** Renders [text] to a temporary WAV file, reads it, and removes the file. */
    private suspend fun synthesize(text: String): WavAudio {
        if (!languageAvailable) throw SpeechEngineException("No language data for rendering")
        val directory = File(appContext.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
        val id = nextId()
        val file = File(directory, "$id.wav")
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        try {
            if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) {
                throw SpeechEngineException("The speech engine refused to render the line")
            }
            val finished = withTimeoutOrNull(SYNTHESIS_TIMEOUT_MS) { done.await() }
                ?: throw SpeechEngineException("Rendering the line took too long")
            if (!finished) throw SpeechEngineException("The speech engine failed to render the line")
            return withContext(Dispatchers.IO) { WavFile.parse(file.readBytes()) }
        } finally {
            pending.remove(id)
            if (!done.isCompleted) tts.stop()
            file.delete()
        }
    }

    /** Plays [audio] and reports its loudness as it is heard; returns when the end has been played, stops promptly when cancelled. */
    private suspend fun play(audio: WavAudio, onLevel: (Float) -> Unit) {
        val envelope = LoudnessEnvelope(audio.samples, audio.sampleRate)
        val track = createTrack(audio.sampleRate)
        withAudioFocus {
            try {
                track.setVolume(VoiceSettings.clampVolume(volume()))
                track.play()
                coroutineScope {
                    val writer = launch(Dispatchers.IO) { writeAll(track, audio.samples) }
                    try {
                        followPlayback(track, audio, envelope, onLevel)
                    } finally {
                        // Before the writer is waited for: it may sit in a blocking write that only the release ends.
                        track.pause()
                        track.flush()
                        track.release()
                        writer.cancel()
                    }
                }
            } finally {
                // Only for a failure before the scope above started; release is harmless a second time.
                track.release()
            }
        }
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw SpeechEngineException("Playback at $sampleRate Hz is not supported")
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimum, sampleRate / 2 * BYTES_PER_SAMPLE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun writeAll(track: AudioTrack, samples: ShortArray) {
        var offset = 0
        while (offset < samples.size) {
            val written = track.write(samples, offset, minOf(WRITE_CHUNK_SAMPLES, samples.size - offset))
            if (written < 0) return
            offset += written
        }
    }

    /** Reports the level at the playback position about every 20 ms until the end of the line has been played. */
    private suspend fun followPlayback(track: AudioTrack, audio: WavAudio, envelope: LoudnessEnvelope, onLevel: (Float) -> Unit) {
        val total = audio.samples.size.toLong()
        // A track that stops advancing (an audio route that vanished) must not hold the queue for ever.
        val deadline = System.nanoTime() + (audio.durationMillis + STALL_ALLOWANCE_MS) * NANOS_PER_MILLI
        while (currentCoroutineContext().isActive) {
            // The head position is an unsigned 32-bit count that the API returns as an Int.
            val position = track.playbackHeadPosition.toLong() and UNSIGNED_INT
            onLevel(envelope.levelAt(position))
            if (position >= total) break
            if (System.nanoTime() > deadline) {
                Log.w(TAG, "Playback did not reach the end in time; moving on")
                break
            }
            delay(POLL_MILLIS)
        }
        onLevel(0f)
        delay(DRAIN_MILLIS)
    }

    /** The fallback: the engine plays the line itself. No levels. */
    private suspend fun speakDirectly(text: String) {
        val id = nextId()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        withAudioFocus {
            try {
                val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, VoiceSettings.clampVolume(volume())) }
                if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) {
                    throw SpeechEngineException("The speech engine refused the line")
                }
                val finished = withTimeoutOrNull(SPEAK_TIMEOUT_MS) { done.await() }
                    ?: throw SpeechEngineException("Speaking the line took too long")
                if (!finished) throw SpeechEngineException("The speech engine failed to speak the line")
            } finally {
                pending.remove(id)
                if (!done.isCompleted) tts.stop()
            }
        }
    }

    private suspend fun withAudioFocus(block: suspend () -> Unit) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) Log.i(TAG, "Audio focus was not granted; speaking anyway")
        try {
            block()
        } finally {
            withContext(NonCancellable) { audioManager.abandonAudioFocusRequest(request) }
        }
    }

    private fun nextId(): String = "knightlink-${counter.incrementAndGet()}"

    private companion object {
        const val TAG = "KnightLinkVoice"
        const val CACHE_DIRECTORY = "voice"
        const val INIT_TIMEOUT_MS = 5_000L
        const val SYNTHESIS_TIMEOUT_MS = 10_000L
        const val SPEAK_TIMEOUT_MS = 30_000L
        const val STALL_ALLOWANCE_MS = 2_000L
        const val POLL_MILLIS = 16L
        const val DRAIN_MILLIS = 60L
        const val WRITE_CHUNK_SAMPLES = 2048
        const val BYTES_PER_SAMPLE = 2
        const val NANOS_PER_MILLI = 1_000_000L
        const val UNSIGNED_INT = 0xFFFFFFFFL
    }
}
