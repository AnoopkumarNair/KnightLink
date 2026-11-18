package knightlink.app.voice

import knightlink.core.telemetry.TelemetrySnapshot
import knightlink.voice.intents.Answer
import knightlink.voice.intents.AnswerContext
import knightlink.voice.intents.AnswerOutput
import knightlink.voice.intents.Intent
import knightlink.voice.intents.LinkStatus
import knightlink.voice.intents.VoiceAssistant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class PushToTalkTest {
    private class FakeRecognizer : SpeechRecognizerPort {
        val calls = ArrayList<String>()
        var listener: SpeechRecognizerPort.Listener? = null

        override fun start(listener: SpeechRecognizerPort.Listener) {
            calls += "start"
            this.listener = listener
        }

        override fun stop() {
            calls += "stop"
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    /** Records everything in one list so the order of stop and speak can be checked. */
    private class RecordingOutput(val events: MutableList<String>) : AnswerOutput {
        override fun speak(answer: Answer, text: String) {
            events += "speak:$text"
        }

        override fun stop() {
            events += "stop"
        }
    }

    private val events = ArrayList<String>()
    private val recognizer = FakeRecognizer()
    private val contexts = ArrayList<AnswerContext>()
    private val context = AnswerContext(TelemetrySnapshot.EMPTY, Duration.ZERO, LinkStatus.NOT_CONNECTED, { _, _ -> null })
    private val talk = PushToTalk(recognizer, VoiceAssistant(output = RecordingOutput(events)), { context.also { contexts += it } })

    private fun spoken() = events.filter { it.startsWith("speak:") }

    @Test
    fun startsIdle() {
        assertEquals(PushToTalkState.Idle, talk.state.value)
    }

    @Test
    fun theFirstPressSilencesTheOutputThenStartsListening() {
        talk.toggle()

        assertEquals(PushToTalkState.Listening(""), talk.state.value)
        assertEquals(listOf("start"), recognizer.calls)
        assertEquals(listOf("stop"), events, "the app's own voice is stopped before the microphone opens")
    }

    @Test
    fun aSecondPressStopsTheRecogniserAndWaitsForWhatItHeard() {
        talk.toggle()
        talk.toggle()

        assertEquals(listOf("start", "stop"), recognizer.calls)
        assertEquals(PushToTalkState.Listening(""), talk.state.value, "the answer comes with the recogniser's results")
    }

    @Test
    fun partialTextIsShownWhileListening() {
        talk.toggle()
        recognizer.listener!!.onReady()
        recognizer.listener!!.onPartial("how is the")

        assertEquals(PushToTalkState.Listening("how is the"), talk.state.value)
    }

    @Test
    fun resultsAreAnsweredAndSpoken() {
        talk.toggle()
        recognizer.listener!!.onResults(listOf("how is the DPF", "how is the DP F"))

        val answered = assertIs<PushToTalkState.Answered>(talk.state.value)
        assertEquals(Intent.DPF, answered.intent)
        assertEquals("how is the DPF", answered.heard)
        assertTrue(answered.answerText.isNotBlank())
        assertEquals(listOf("speak:${answered.answerText}"), spoken())
        assertEquals(1, contexts.size, "the context is read when the answer is made")
    }

    @Test
    fun aStopAnswerGoesBackToIdleAndSaysNothing() {
        talk.toggle()
        recognizer.listener!!.onResults(listOf("stop talking"))

        assertEquals(PushToTalkState.Idle, talk.state.value)
        assertTrue(spoken().isEmpty())
        assertEquals(listOf("stop", "stop"), events, "once for the press, once for the spoken stop")
    }

    @Test
    fun noMatchAndNoSpeechFailWithoutSpeaking() {
        for (error in listOf(RecognitionError.NO_MATCH, RecognitionError.NO_SPEECH)) {
            talk.toggle()
            recognizer.listener!!.onError(error)
            assertEquals(PushToTalkState.Failed(error), talk.state.value)
        }
        assertTrue(spoken().isEmpty())
    }

    @Test
    fun emptyResultsCountAsNoMatch() {
        talk.toggle()
        recognizer.listener!!.onResults(emptyList())

        assertEquals(PushToTalkState.Failed(RecognitionError.NO_MATCH), talk.state.value)
        assertTrue(spoken().isEmpty())
    }

    @Test
    fun aPressAfterAnAnswerOrAFailureListensAgain() {
        talk.toggle()
        recognizer.listener!!.onResults(listOf("how is the DPF"))
        assertIs<PushToTalkState.Answered>(talk.state.value)

        talk.toggle()
        assertEquals(PushToTalkState.Listening(""), talk.state.value)
        recognizer.listener!!.onError(RecognitionError.NETWORK)
        assertEquals(PushToTalkState.Failed(RecognitionError.NETWORK), talk.state.value)

        talk.toggle()
        assertEquals(PushToTalkState.Listening(""), talk.state.value)
        assertEquals(listOf("start", "start", "start"), recognizer.calls)
    }

    @Test
    fun pressingWhileAnswerIsBeingSpokenSilencesItFirst() {
        talk.toggle()
        recognizer.listener!!.onResults(listOf("how is the DPF"))
        events.clear()

        talk.toggle()

        assertEquals(listOf("stop"), events)
    }

    @Test
    fun cancelDropsTheRecognitionAndIgnoresWhatArrivesLater() {
        talk.toggle()
        val late = assertNotNull(recognizer.listener)
        talk.cancel()

        assertEquals(PushToTalkState.Idle, talk.state.value)
        assertEquals(listOf("start", "cancel"), recognizer.calls)

        late.onResults(listOf("how is the DPF"))
        late.onPartial("how")
        late.onError(RecognitionError.OTHER)
        assertEquals(PushToTalkState.Idle, talk.state.value)
        assertTrue(spoken().isEmpty())
    }

    @Test
    fun cancelWhenNotListeningDoesNotTouchTheRecogniser() {
        talk.cancel()

        assertTrue(recognizer.calls.isEmpty())
        assertEquals(PushToTalkState.Idle, talk.state.value)
    }

    @Test
    fun aLateResultOfAnEarlierRecognitionIsIgnored() {
        talk.toggle()
        val first = assertNotNull(recognizer.listener)
        talk.cancel()
        talk.toggle()

        first.onResults(listOf("how is the DPF"))

        assertEquals(PushToTalkState.Listening(""), talk.state.value)
        assertTrue(spoken().isEmpty())
    }
}
