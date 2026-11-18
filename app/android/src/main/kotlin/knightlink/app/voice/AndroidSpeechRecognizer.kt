package knightlink.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * [SpeechRecognizerPort] over the platform's `SpeechRecognizer`. Thin on purpose: everything that decides anything is in [PushToTalk].
 *
 * Prefers a recogniser that runs on the phone: on Android 12 and later the on-device one when it is available, otherwise the
 * default speech service with the "prefer offline" extra, which the service may or may not honour. [isOnDevice] says which of the
 * two guarantees applies, for the screen to tell the user.
 *
 * Main thread only: the recogniser is created, used and destroyed there, and its callbacks arrive there (they are posted to the
 * main looper if they ever come from elsewhere).
 */
class AndroidSpeechRecognizer(private val context: Context) : SpeechRecognizerPort, AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechRecognizerPort.Listener? = null

    /**
     * Whether the on-device recogniser is used. True at first when the phone has one; false for good once it reports that it lacks the
     * language (pack not installed), after which the phone's ordinary speech service is used.
     */
    private var useOnDevice: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** True when audio is recognised on this phone and never sent anywhere; false when the phone's speech service decides. */
    val isOnDevice: Boolean get() = useOnDevice

    override fun start(listener: SpeechRecognizerPort.Listener) {
        if (!isOnDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError(RecognitionError.UNAVAILABLE)
            return
        }
        this.listener = listener
        val engine = recognizer ?: create().also { recognizer = it }
        engine.startListening(recognizerIntent())
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        // Dropped first, so nothing the engine still delivers reaches the old listener.
        listener = null
        recognizer?.cancel()
    }

    /** Releases the recogniser. Call from the main thread. */
    override fun close() {
        listener = null
        recognizer?.destroy()
        recognizer = null
    }

    private fun create(): SpeechRecognizer {
        val engine = if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        engine.setRecognitionListener(Forwarder())
        return engine
    }

    private fun recognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

    /** Runs [block] on the main thread: at once when already there. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private inner class Forwarder : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = onMain { listener?.onReady() }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) = onMain {
            partialResults.texts().firstOrNull()?.let { listener?.onPartial(it) }
        }

        override fun onResults(results: Bundle?) = onMain {
            val target = listener
            listener = null
            target?.onResults(results.texts())
        }

        override fun onError(error: Int) = onMain {
            if (useOnDevice && (error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE)) {
                fallBackToStandardRecognizer()
                return@onMain
            }
            val target = listener
            listener = null
            target?.onError(recognitionErrorOf(error))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** The on-device recogniser cannot do this language: switch to the standard one and ask again, once, with the same listener. */
    private fun fallBackToStandardRecognizer() {
        val target = listener ?: return
        useOnDevice = false
        recognizer?.destroy()
        recognizer = null
        start(target)
    }

    private fun Bundle?.texts(): List<String> = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()

    private companion object {
        const val MAX_RESULTS = 5
    }
}

/** Maps a `SpeechRecognizer.ERROR_*` code. Pure, so it is tested without a device. */
internal fun recognitionErrorOf(code: Int): RecognitionError = when (code) {
    SpeechRecognizer.ERROR_NO_MATCH -> RecognitionError.NO_MATCH
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionError.NO_SPEECH
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionError.NO_PERMISSION
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> RecognitionError.NETWORK
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS -> RecognitionError.BUSY
    ERROR_SERVER_DISCONNECTED, ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> RecognitionError.UNAVAILABLE
    else -> RecognitionError.OTHER
}

// Error codes added in Android 12 and 13 (API 31 and 33), by value, so the mapping needs no API level check.
private const val ERROR_TOO_MANY_REQUESTS = 10
private const val ERROR_SERVER_DISCONNECTED = 11
private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
private const val ERROR_LANGUAGE_UNAVAILABLE = 13
