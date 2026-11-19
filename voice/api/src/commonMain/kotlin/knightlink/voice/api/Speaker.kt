package knightlink.voice.api

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * How urgent a line is. A line of a higher priority interrupts a lower one that is being spoken; within one priority lines are
 * spoken in the order they were asked for.
 *
 * Guidance for callers: [STATUS] for connection status and other things the driver may as well hear late or not at all,
 * [ANSWER] for replies to the driver's own question, [ALERT] for warnings about the car, [CRITICAL] for safety alerts that must
 * be heard now.
 */
public enum class Priority {
    STATUS,
    ANSWER,
    ALERT,
    CRITICAL,
}

/**
 * One line to speak.
 *
 * @property text what to say, as a natural sentence. Never empty.
 * @property priority see [Priority]
 * @property category what the line is about, e.g. `connection`, `answer`, `alert.dpf`. At most one line per category waits in the
 *   queue (a newer one replaces it), and a line equal in text to the last line spoken in its category within the quiet period is not
 *   spoken again ("quiet when nothing changed"). Dot-separated lower-case words by convention.
 * @property maxAge a line still waiting after this long is dropped instead of spoken (a status that is no longer true); null to wait
 *   as long as it takes
 */
public data class Utterance(
    val text: String,
    val priority: Priority,
    val category: String,
    val maxAge: Duration? = null,
) {
    init {
        require(text.isNotBlank()) { "An utterance needs text" }
        require(category.isNotBlank()) { "An utterance needs a category" }
    }
}

/** What the voice is doing, for a screen to show. Observed through [Speaker.state]. */
public sealed interface SpeechState {
    /** Silent, nothing pending. */
    public data object Idle : SpeechState

    /**
     * A line is being spoken.
     *
     * @property level loudness of the audio being heard right now, 0 (silence) to 1 (loudest frame of this line), updated about every
     *   20 ms while the engine can measure it. Stays at [UNKNOWN_LEVEL] when the engine cannot (a screen then animates on its own).
     */
    public data class Speaking(val utterance: Utterance, val level: Float) : SpeechState

    /** The microphone is open (push-to-talk); nothing is spoken meanwhile. */
    public data object Listening : SpeechState

    /** An answer is being worked out after listening; nothing is spoken meanwhile. */
    public data object Thinking : SpeechState

    public companion object {
        /** [Speaking.level] when the engine reports no loudness. */
        public const val UNKNOWN_LEVEL: Float = -1f
    }
}

/** Set by push-to-talk (or, later, a wake word) to show and hold the voice while the driver is talking. */
public enum class VoiceMode {
    /** Normal: lines are spoken as they come. */
    SPEAKING,

    /** The microphone is open. The line being spoken stops at once; new lines wait (unless [Priority.CRITICAL]). */
    LISTENING,

    /** The driver has spoken and the answer is being prepared. New lines wait (unless [Priority.CRITICAL]). */
    THINKING,
}

/** Whether [Speaker.speak] took a line, and if not, why. */
public enum class SpeakResult {
    /** Will be spoken (now or when its turn comes). */
    QUEUED,

    /** Replaced a waiting line of the same category, which will not be spoken. */
    REPLACED,

    /** Not spoken: the same text was spoken in this category within the quiet period, or is being spoken now. */
    UNCHANGED,

    /** Not spoken: the speaker is closed. */
    CLOSED,
}

/**
 * The app's voice: everything KnightLink says goes through one of these, so that priorities, de-duplication and "say that again"
 * work across features (connection status, alerts, answers).
 *
 * All methods may be called from any thread and return at once; speech happens in the background.
 */
public interface Speaker {
    /** What the voice is doing now. */
    public val state: StateFlow<SpeechState>

    /** The last line that started to be spoken; null before the first. This is what [repeatLast] says and what a screen shows as text. */
    public val lastSpoken: StateFlow<Utterance?>

    /**
     * Queues [utterance] under the queue policy:
     * - a higher [Priority] than the line being spoken interrupts it (an interrupted [Priority.ALERT] or [Priority.CRITICAL] line is
     *   spoken again afterwards; lower ones are dropped);
     * - a waiting line of the same category is replaced;
     * - a line whose text equals the last spoken in its category within the quiet period is not spoken ([SpeakResult.UNCHANGED]).
     */
    public fun speak(utterance: Utterance): SpeakResult

    /** Says the last spoken line again, now, as if new (it skips the quiet-period check). False when nothing was spoken yet. */
    public fun repeatLast(): Boolean

    /** Stops the line being spoken; the next waiting line, if any, follows. */
    public fun skip()

    /** Stops the line being spoken and drops every waiting line. */
    public fun stopAll()

    /** See [VoiceMode]. */
    public fun setMode(mode: VoiceMode)
}

/**
 * Turns text into sound. One per platform (Android: text-to-speech rendered to PCM and played with loudness; tests: a fake).
 */
public interface SpeechEngine {
    /**
     * Speaks [text] and returns when it has been heard in full. While audible, calls [onLevel] with the current loudness, 0 to 1, about
     * every 20 ms, in step with what is heard; an engine that cannot measure loudness never calls it.
     *
     * Cancelling the calling coroutine must stop the sound promptly (within about 100 ms).
     *
     * @throws SpeechEngineException when the line could not be spoken; the speaker logs it and goes on with the next line
     */
    public suspend fun speak(text: String, onLevel: (Float) -> Unit)
}

public class SpeechEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
