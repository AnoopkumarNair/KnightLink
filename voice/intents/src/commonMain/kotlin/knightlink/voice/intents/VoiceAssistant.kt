package knightlink.voice.intents

import kotlin.concurrent.Volatile

/**
 * Push-to-talk question answering: takes what the recogniser heard and the current facts, speaks the answer through [output], and
 * remembers it so "say that again" can repeat it. Stop and repeat are handled here; everything else is answered by [Answers].
 *
 * Safe to call from several threads: the only state is the last answer, an immutable value in a volatile field.
 */
public class VoiceAssistant(
    private val matcher: IntentMatcher = IntentMatcher(),
    private val text: AnswerText = EnglishAnswerText,
    private val output: AnswerOutput = PrintAnswerOutput(),
    private val warnings: WarningsSource = NoWarningsSource,
    private val policy: AnswerPolicy = AnswerPolicy(),
) {
    /**
     * What came of one question.
     *
     * @property match what was understood
     * @property answer what was said; null when nothing was (stop)
     * @property text the answer in words; null when nothing was said
     */
    public data class Response(val match: IntentMatch, val answer: Answer?, val text: String?)

    private class Spoken(val answer: Answer, val text: String)

    @Volatile
    private var last: Spoken? = null

    /** The answer said last (not counting repeats of it, which say the same), or null before the first one. */
    public val lastAnswer: Answer? get() = last?.answer

    /** Stops speaking now, e.g. when the push-to-talk button goes down again. Does not touch [lastAnswer]. */
    public fun stop() {
        output.stop()
    }

    /**
     * Answers [alternatives] (the recogniser's guesses, most confident first) from [context].
     * STOP silences the output and says nothing; REPEAT says the last answer again (or that there is none).
     */
    public fun respond(alternatives: List<String>, context: AnswerContext): Response {
        val match = matcher.match(alternatives)
        return when (match.intent) {
            Intent.STOP -> {
                output.stop()
                Response(match, null, null)
            }
            Intent.REPEAT -> {
                val previous = last
                if (previous != null) {
                    output.speak(previous.answer, previous.text)
                    Response(match, previous.answer, previous.text)
                } else {
                    say(match, Answer(AnswerKeys.REPEAT_NOTHING), remember = false)
                }
            }
            else -> say(match, Answers.answer(match.intent, context, warnings, policy), remember = true)
        }
    }

    private fun say(match: IntentMatch, answer: Answer, remember: Boolean): Response {
        val rendered = text.render(answer)
        if (remember) last = Spoken(answer, rendered)
        output.speak(answer, rendered)
        return Response(match, answer, rendered)
    }
}
