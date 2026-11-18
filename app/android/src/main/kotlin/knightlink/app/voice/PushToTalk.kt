package knightlink.app.voice

import knightlink.voice.intents.AnswerContext
import knightlink.voice.intents.Intent
import knightlink.voice.intents.VoiceAssistant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the push-to-talk card shows. */
sealed interface PushToTalkState {
    /** Nothing happening; ready for a press. */
    data object Idle : PushToTalkState

    /** The microphone is open; [partial] is what has been recognised so far (empty at first). */
    data class Listening(val partial: String) : PushToTalkState

    /** [heard] is what the recogniser understood, [answerText] what KnightLink answered (also spoken), [intent] what it was taken to mean. */
    data class Answered(val heard: String, val answerText: String, val intent: Intent) : PushToTalkState

    /** Nothing was recognised or the recogniser failed; the screen maps [error] to a message. Nothing is spoken. */
    data class Failed(val error: RecognitionError) : PushToTalkState
}

/**
 * Push-to-talk: one press starts listening, the next stops and answers. Plain Kotlin; the Android parts are the [recognizer] adapter
 * and the output behind [assistant].
 *
 * Threading: NOT thread-safe. [toggle], [cancel] and every recogniser callback must happen on one thread (the main thread); the
 * Android recogniser adapter marshals its callbacks there. [state] may be read from anywhere.
 *
 * @param context supplies the facts to answer from, read at the moment the answer is made
 */
class PushToTalk(
    private val recognizer: SpeechRecognizerPort,
    private val assistant: VoiceAssistant,
    private val context: () -> AnswerContext,
) {
    private val _state = MutableStateFlow<PushToTalkState>(PushToTalkState.Idle)
    val state: StateFlow<PushToTalkState> = _state.asStateFlow()

    /** Incremented per start, so a callback of an earlier recognition that arrives late is ignored. */
    private var generation = 0

    /** Idle, answered or failed: start listening. Listening: stop and answer what was heard so far. */
    fun toggle() {
        if (_state.value is PushToTalkState.Listening) {
            recognizer.stop()
        } else {
            startListening()
        }
    }

    /** Drops a recognition in progress (e.g. the screen went away) and returns to idle. Nothing is answered. */
    fun cancel() {
        if (_state.value is PushToTalkState.Listening) recognizer.cancel()
        generation++
        _state.value = PushToTalkState.Idle
    }

    private fun startListening() {
        // The app must never hear its own voice: silence the output before the microphone opens.
        assistant.stop()
        val mine = ++generation
        _state.value = PushToTalkState.Listening("")
        recognizer.start(
            object : SpeechRecognizerPort.Listener {
                override fun onReady() = Unit

                override fun onPartial(text: String) {
                    if (current(mine)) _state.value = PushToTalkState.Listening(text)
                }

                override fun onResults(alternatives: List<String>) {
                    if (current(mine)) answer(alternatives)
                }

                override fun onError(error: RecognitionError) {
                    if (current(mine)) _state.value = PushToTalkState.Failed(error)
                }
            },
        )
    }

    private fun current(mine: Int) = mine == generation && _state.value is PushToTalkState.Listening

    private fun answer(alternatives: List<String>) {
        if (alternatives.isEmpty()) {
            _state.value = PushToTalkState.Failed(RecognitionError.NO_MATCH)
            return
        }
        val response = assistant.respond(alternatives, context())
        val text = response.text
        _state.value = if (text == null) {
            PushToTalkState.Idle
        } else {
            PushToTalkState.Answered(response.match.heard.ifBlank { alternatives.first() }, text, response.match.intent)
        }
    }
}
