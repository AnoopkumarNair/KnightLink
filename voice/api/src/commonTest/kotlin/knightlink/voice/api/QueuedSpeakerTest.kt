package knightlink.voice.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class FakeEngine : SpeechEngine {
    val started = mutableListOf<String>()
    val completed = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val failOn = mutableSetOf<String>()
    var duration: Duration = 1.seconds
    var levels: List<Float> = emptyList()

    override suspend fun speak(
        text: String,
        onLevel: (Float) -> Unit,
    ) {
        started += text
        if (text in failOn) throw SpeechEngineException("cannot speak '$text'")
        try {
            delay(10.milliseconds)
            for (level in levels) {
                onLevel(level)
                delay(100.milliseconds)
            }
            delay(duration)
            completed += text
        } catch (e: CancellationException) {
            cancelled += text
            throw e
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class QueuedSpeakerTest {
    private fun line(
        text: String,
        priority: Priority = Priority.ANSWER,
        category: String = text,
        maxAge: Duration? = null,
    ) = Utterance(text, priority, category, maxAge)

    /**
     * The speaker's scope is not [TestScope.backgroundScope]: `advanceUntilIdle` ignores background work, and the speaking is exactly
     * the work these tests wait for.
     */
    private fun TestScope.speakerOf(
        engine: FakeEngine,
        scope: CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
    ) = QueuedSpeaker(engine, scope, testScheduler.timeSource)

    @Test
    fun speaksInOrderAndEndsIdle() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            assertEquals(SpeakResult.QUEUED, speaker.speak(line("one")))
            assertEquals(SpeakResult.QUEUED, speaker.speak(line("two")))
            assertNull(speaker.lastSpoken.value)
            runCurrent()
            assertEquals(SpeechState.Speaking(line("one"), SpeechState.UNKNOWN_LEVEL), speaker.state.value)
            advanceUntilIdle()
            assertEquals(listOf("one", "two"), engine.completed)
            assertEquals(SpeechState.Idle, speaker.state.value)
            assertEquals(line("two"), speaker.lastSpoken.value)
        }

    @Test
    fun aHigherPriorityInterruptsAndALowerOneIsNotSpokenAgain() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.speak(line("status", Priority.STATUS))
            runCurrent()
            speaker.speak(line("answer", Priority.ANSWER))
            advanceUntilIdle()
            assertEquals(listOf("status", "answer"), engine.started)
            assertEquals(listOf("status"), engine.cancelled)
            assertEquals(listOf("answer"), engine.completed)
        }

    @Test
    fun anInterruptedAlertIsSpokenAgainAfterTheInterruption() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.speak(line("check dpf", Priority.ALERT))
            runCurrent()
            speaker.speak(line("overheating", Priority.CRITICAL))
            advanceUntilIdle()
            assertEquals(listOf("check dpf", "overheating", "check dpf"), engine.started)
            assertEquals(listOf("overheating", "check dpf"), engine.completed)
        }

    @Test
    fun anEqualPriorityWaitsItsTurn() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.speak(line("one"))
            runCurrent()
            speaker.speak(line("two"))
            advanceUntilIdle()
            assertEquals(emptyList(), engine.cancelled)
            assertEquals(listOf("one", "two"), engine.completed)
        }

    @Test
    fun aNewerLineOfTheSameCategoryReplacesTheWaitingOne() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.speak(line("first", category = "a"))
            runCurrent()
            assertEquals(SpeakResult.QUEUED, speaker.speak(line("old", category = "b")))
            assertEquals(SpeakResult.REPLACED, speaker.speak(line("new", category = "b")))
            advanceUntilIdle()
            assertEquals(listOf("first", "new"), engine.completed)
        }

    @Test
    fun listeningStopsTheLineAndHoldsTheWaitingOnesUntilSpeakingAgain() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.speak(line("answer"))
            runCurrent()
            speaker.setMode(VoiceMode.LISTENING)
            runCurrent()
            assertEquals(listOf("answer"), engine.cancelled)
            assertEquals(SpeechState.Listening, speaker.state.value)

            speaker.speak(line("later", Priority.ALERT))
            advanceTimeBy(10.seconds)
            assertEquals(listOf("answer"), engine.started)
            assertEquals(SpeechState.Listening, speaker.state.value)

            speaker.setMode(VoiceMode.SPEAKING)
            advanceUntilIdle()
            assertEquals(listOf("answer", "later"), engine.started)
            assertEquals(SpeechState.Idle, speaker.state.value)
        }

    @Test
    fun anAlertStoppedByListeningIsSpokenWhenSpeakingResumes() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.speak(line("alert", Priority.ALERT))
            runCurrent()
            speaker.setMode(VoiceMode.LISTENING)
            runCurrent()
            speaker.setMode(VoiceMode.SPEAKING)
            advanceUntilIdle()
            assertEquals(listOf("alert", "alert"), engine.started)
            assertEquals(listOf("alert"), engine.completed)
        }

    @Test
    fun thinkingHoldsNewLinesWithoutInterrupting() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.speak(line("running"))
            runCurrent()
            speaker.setMode(VoiceMode.THINKING)
            speaker.speak(line("waits"))
            advanceUntilIdle()
            assertEquals(listOf("running"), engine.completed)
            assertEquals(SpeechState.Thinking, speaker.state.value)
            speaker.setMode(VoiceMode.SPEAKING)
            advanceUntilIdle()
            assertEquals(listOf("running", "waits"), engine.completed)
        }

    @Test
    fun aCriticalLineIsSpokenWhileListeningAndTheStateReturnsToListening() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.setMode(VoiceMode.LISTENING)
            speaker.speak(line("alert", Priority.ALERT))
            speaker.speak(line("critical", Priority.CRITICAL))
            runCurrent()
            assertEquals(SpeechState.Speaking(line("critical", Priority.CRITICAL), SpeechState.UNKNOWN_LEVEL), speaker.state.value)
            advanceUntilIdle()
            assertEquals(listOf("critical"), engine.completed)
            assertEquals(SpeechState.Listening, speaker.state.value)
        }

    @Test
    fun aCriticalLineBeingSpokenIsNotStoppedByListening() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.speak(line("critical", Priority.CRITICAL))
            runCurrent()
            speaker.setMode(VoiceMode.LISTENING)
            advanceUntilIdle()
            assertEquals(listOf("critical"), engine.completed)
            assertEquals(SpeechState.Listening, speaker.state.value)
        }

    @Test
    fun repeatLastSaysTheLastLineAgainDespiteTheQuietPeriod() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            assertFalse(speaker.repeatLast())
            speaker.speak(line("fuel is at 40 percent", Priority.STATUS, "fuel"))
            advanceUntilIdle()
            assertEquals(SpeakResult.UNCHANGED, speaker.speak(line("fuel is at 40 percent", Priority.STATUS, "fuel")))
            assertTrue(speaker.repeatLast())
            advanceUntilIdle()
            assertEquals(listOf("fuel is at 40 percent", "fuel is at 40 percent"), engine.completed)
            assertEquals(Priority.ANSWER, speaker.lastSpoken.value?.priority)
        }

    @Test
    fun repeatLastKeepsAHigherPriority() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.speak(line("alert", Priority.ALERT))
            advanceUntilIdle()
            speaker.repeatLast()
            advanceUntilIdle()
            assertEquals(Priority.ALERT, speaker.lastSpoken.value?.priority)
            assertEquals(2, engine.completed.size)
        }

    @Test
    fun theSameLineIsQuietForTheQuietPeriodOnly() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.speak(line("connected", Priority.STATUS, "connection"))
            advanceUntilIdle()
            assertEquals(SpeakResult.UNCHANGED, speaker.speak(line("connected", Priority.STATUS, "connection")))
            advanceTimeBy(61.seconds)
            assertEquals(SpeakResult.QUEUED, speaker.speak(line("connected", Priority.STATUS, "connection")))
            advanceUntilIdle()
            assertEquals(2, engine.completed.size)
        }

    @Test
    fun aStaleStatusIsDroppedInsteadOfSpoken() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.setMode(VoiceMode.THINKING)
            speaker.speak(line("connecting", Priority.STATUS, "connection", maxAge = 20.seconds))
            advanceTimeBy(30.seconds)
            speaker.setMode(VoiceMode.SPEAKING)
            advanceUntilIdle()
            assertEquals(emptyList(), engine.started)
            assertEquals(SpeechState.Idle, speaker.state.value)
        }

    @Test
    fun skipStopsTheLineAndGoesOnWithTheNext() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.speak(line("one"))
            speaker.speak(line("two"))
            runCurrent()
            speaker.skip()
            advanceUntilIdle()
            assertEquals(listOf("one"), engine.cancelled)
            assertEquals(listOf("two"), engine.completed)
        }

    @Test
    fun skipDoesNotBringAnAlertBack() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.speak(line("alert", Priority.ALERT))
            runCurrent()
            speaker.skip()
            advanceUntilIdle()
            assertEquals(listOf("alert"), engine.started)
            assertEquals(SpeechState.Idle, speaker.state.value)
        }

    @Test
    fun stopAllStopsTheLineAndDropsTheWaitingOnes() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.speak(line("one"))
            speaker.speak(line("two"))
            speaker.speak(line("three", Priority.ALERT))
            runCurrent()
            speaker.stopAll()
            advanceUntilIdle()
            assertEquals(listOf("three"), engine.cancelled)
            assertEquals(emptyList(), engine.completed)
            assertEquals(SpeechState.Idle, speaker.state.value)
            speaker.speak(line("four"))
            advanceUntilIdle()
            assertEquals(listOf("four"), engine.completed)
        }

    @Test
    fun anEngineThatFailsDoesNotStopTheNextLine() =
        runTest {
            val engine = FakeEngine().apply { failOn += "broken" }
            val speaker = speakerOf(engine)
            speaker.speak(line("broken"))
            speaker.speak(line("fine"))
            advanceUntilIdle()
            assertEquals(listOf("broken", "fine"), engine.started)
            assertEquals(listOf("fine"), engine.completed)
            assertEquals(SpeechState.Idle, speaker.state.value)
        }

    @Test
    fun levelsArePublishedAndClamped() =
        runTest {
            val engine = FakeEngine().apply { levels = listOf(0.25f, 2f, -1f) }
            val speaker = speakerOf(engine)
            val seen = mutableListOf<SpeechState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { speaker.state.collect { seen += it } }
            speaker.speak(line("hello"))
            advanceUntilIdle()
            val levels = seen.filterIsInstance<SpeechState.Speaking>().map { it.level }
            assertEquals(listOf(SpeechState.UNKNOWN_LEVEL, 0.25f, 1f, 0f), levels)
            assertEquals(SpeechState.Idle, seen.last())
        }

    @Test
    fun aClosedSpeakerRefusesLines() =
        runTest {
            val engine = FakeEngine()
            val speaker = speakerOf(engine)
            speaker.speak(line("one"))
            runCurrent()
            speaker.close()
            runCurrent()
            assertEquals(listOf("one"), engine.cancelled)
            assertEquals(SpeakResult.CLOSED, speaker.speak(line("two")))
            assertFalse(speaker.repeatLast())
            assertEquals(SpeechState.Idle, speaker.state.value)
        }

    @Test
    fun cancellingTheScopeClosesTheSpeaker() =
        runTest {
            val job = Job()
            val speaker = speakerOf(FakeEngine(), CoroutineScope(StandardTestDispatcher(testScheduler) + job))
            runCurrent()
            job.cancel()
            runCurrent()
            assertEquals(SpeakResult.CLOSED, speaker.speak(line("late")))
        }
}
