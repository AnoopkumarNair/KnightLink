package knightlink.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import knightlink.voice.intents.Answer
import knightlink.voice.intents.AnswerOutput
import java.util.Locale

/**
 * TEMPORARY: says answers with the phone's plain `TextToSpeech`, so push-to-talk can be tried before voice output exists.
 *
 * The voice-output session's `voice/api` Speaker replaces this at integration (the Speaker is mapped onto [AnswerOutput]); delete
 * this file then. On purpose there is no audio-focus handling, ducking or voice choice here: that is the Speaker's job. What the
 * screen shows is [PushToTalk]'s state, not this output.
 *
 * Main thread only. Text handed over before the engine has started is kept (the newest only) and said when it is ready.
 */
class StubTtsAnswerOutput(context: Context) : AnswerOutput, AutoCloseable {
    private var ready = false
    private var pending: String? = null
    private var engine: TextToSpeech? = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    private fun onInit(status: Int) {
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) return
        tts.setLanguage(Locale.getDefault())
        ready = true
        pending?.let { say(tts, it) }
        pending = null
    }

    override fun speak(answer: Answer, text: String) {
        val tts = engine
        if (tts != null && ready) say(tts, text) else pending = text
    }

    override fun stop() {
        pending = null
        if (ready) engine?.stop()
    }

    /** Releases the engine. */
    override fun close() {
        engine?.shutdown()
        engine = null
        ready = false
        pending = null
    }

    private fun say(tts: TextToSpeech, text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private companion object {
        const val UTTERANCE_ID = "knightlink-answer"
    }
}
