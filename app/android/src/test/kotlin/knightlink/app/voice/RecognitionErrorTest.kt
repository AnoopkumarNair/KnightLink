package knightlink.app.voice

import android.speech.SpeechRecognizer
import kotlin.test.Test
import kotlin.test.assertEquals

/** The error codes are compile-time constants, so they are inlined and the android.jar stubs are never called. */
class RecognitionErrorTest {
    @Test
    fun platformErrorCodesMapToOurs() {
        assertEquals(RecognitionError.NO_MATCH, recognitionErrorOf(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(RecognitionError.NO_SPEECH, recognitionErrorOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(RecognitionError.NO_PERMISSION, recognitionErrorOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(RecognitionError.NETWORK, recognitionErrorOf(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(RecognitionError.NETWORK, recognitionErrorOf(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertEquals(RecognitionError.BUSY, recognitionErrorOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(RecognitionError.BUSY, recognitionErrorOf(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS))
        assertEquals(RecognitionError.UNAVAILABLE, recognitionErrorOf(SpeechRecognizer.ERROR_SERVER_DISCONNECTED))
        assertEquals(RecognitionError.UNAVAILABLE, recognitionErrorOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertEquals(RecognitionError.UNAVAILABLE, recognitionErrorOf(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals(RecognitionError.OTHER, recognitionErrorOf(SpeechRecognizer.ERROR_AUDIO))
        assertEquals(RecognitionError.OTHER, recognitionErrorOf(SpeechRecognizer.ERROR_SERVER))
        assertEquals(RecognitionError.OTHER, recognitionErrorOf(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(RecognitionError.OTHER, recognitionErrorOf(999))
    }
}
