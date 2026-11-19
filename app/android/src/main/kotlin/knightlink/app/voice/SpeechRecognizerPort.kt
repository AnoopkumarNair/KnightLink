package knightlink.app.voice

/** Why a recognition did not produce text. Mapped from the platform's error codes by the Android adapter. */
enum class RecognitionError {
    /** Speech was heard but nothing recognised. */
    NO_MATCH,

    /** Nobody spoke before the recogniser gave up. */
    NO_SPEECH,

    /** The microphone permission is missing. */
    NO_PERMISSION,

    /** The phone's speech service needed the network and could not reach it. */
    NETWORK,

    /** The speech service is busy with another request. */
    BUSY,

    /** There is no speech service on this phone, or it cannot be used. */
    UNAVAILABLE,
    OTHER,
}

/**
 * One speech recogniser, as push-to-talk needs it. Implemented on Android over `SpeechRecognizer`; faked in tests.
 *
 * Every [Listener] callback must arrive on the one thread that also calls these methods (the main thread on Android): the adapter
 * marshals, [PushToTalk] does not lock.
 */
interface SpeechRecognizerPort {
    /** Starts listening; [listener] gets exactly one of [Listener.onResults] and [Listener.onError] per start, unless [cancel] is called. */
    fun start(listener: Listener)

    /** Stops listening and delivers what was heard so far through [Listener.onResults] (or [Listener.onError]). */
    fun stop()

    /** Stops listening and drops everything: no callback follows. */
    fun cancel()

    interface Listener {
        /** The microphone is open: the user may speak. */
        fun onReady()

        /** What has been recognised so far. */
        fun onPartial(text: String)

        /** The final guesses, most confident first. */
        fun onResults(alternatives: List<String>)

        fun onError(error: RecognitionError)
    }
}
