package knightlink.voice.intents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VoiceAssistantTest {
    private class RecordingOutput : AnswerOutput {
        val spoken = mutableListOf<Pair<Answer, String>>()
        var stops = 0

        override fun speak(answer: Answer, text: String) {
            spoken += answer to text
        }

        override fun stop() {
            stops++
        }
    }

    private val output = RecordingOutput()
    private val assistant = VoiceAssistant(output = output, warnings = FakeWarnings(true))
    private val ctx = context(reading("engine.coolant_temp", 90.0), reading("fuel.level_pct", 60.0))

    @Test
    fun answersSpeaksAndRemembers() {
        val response = assistant.respond(listOf("what's the coolant temperature"), ctx)
        assertEquals(Intent.COOLANT_TEMPERATURE, response.match.intent)
        assertEquals("The coolant is at 90 degrees, which is normal.", response.text)
        assertEquals(response.answer, assistant.lastAnswer)
        assertEquals(listOf(response.answer to response.text), output.spoken)
    }

    @Test
    fun repeatSaysTheLastAnswerAgain() {
        val first = assistant.respond(listOf("fuel level"), ctx)
        val repeat = assistant.respond(listOf("say that again"), ctx)
        assertEquals(Intent.REPEAT, repeat.match.intent)
        assertEquals(first.answer, repeat.answer)
        assertEquals(first.text, repeat.text)
        assertEquals(2, output.spoken.size)
        assertEquals(output.spoken[0], output.spoken[1])
        assertEquals(first.answer, assistant.lastAnswer)
        // Repeating twice still repeats the original.
        assistant.respond(listOf("pardon"), ctx)
        assertEquals(first.text, output.spoken[2].second)
    }

    @Test
    fun repeatWithNothingSaidYet() {
        val response = assistant.respond(listOf("repeat"), ctx)
        assertEquals(AnswerKeys.REPEAT_NOTHING, response.answer?.key)
        assertEquals("I haven't said anything yet.", response.text)
        assertEquals(1, output.spoken.size)
        // That apology is not itself remembered as the thing to repeat.
        assertNull(assistant.lastAnswer)
    }

    @Test
    fun stopSilencesAndSaysNothing() {
        assistant.respond(listOf("fuel level"), ctx)
        val before = output.spoken.size
        val response = assistant.respond(listOf("stop"), ctx)
        assertEquals(Intent.STOP, response.match.intent)
        assertNull(response.answer)
        assertNull(response.text)
        assertEquals(1, output.stops)
        assertEquals(before, output.spoken.size)
        assertNotNull(assistant.lastAnswer)
    }

    @Test
    fun stopMethodStopsTheOutput() {
        assistant.stop()
        assertEquals(1, output.stops)
    }

    @Test
    fun unknownSpeaksTheHelp() {
        val response = assistant.respond(listOf("play some music"), ctx)
        assertEquals(Intent.UNKNOWN, response.match.intent)
        assertEquals(AnswerKeys.HELP, response.answer?.key)
        assertEquals(1, output.spoken.size)
    }

    @Test
    fun linkStateIsSpokenForDataQuestions() {
        val response = assistant.respond(listOf("fuel"), context(link = LinkStatus.NOT_CONNECTED))
        assertEquals("I'm not connected to the car.", response.text)
    }

    @Test
    fun usesTheGivenWarningsSourceAndText() {
        val custom = VoiceAssistant(output = output, text = { "custom:" + it.key }, warnings = FakeWarnings(true, listOf(warning("w"))))
        assertEquals("custom:warnings.one", custom.respond(listOf("any warnings"), ctx).text)
    }
}
