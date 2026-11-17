package knightlink.core.recorder

import knightlink.core.transport.TransportException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal fun tx(timeMs: Long, text: String) = RecordingEvent.tx(timeMs, text.encodeToByteArray())

internal fun rx(timeMs: Long, text: String) = RecordingEvent.rx(timeMs, text.encodeToByteArray())

internal fun recordingOf(vararg events: RecordingEvent) = Recording(RecordingHeader(), events.toList())

/** What a consumer sees on `incoming`: the virtual time of each chunk and its text. */
private class Collected(val chunks: MutableList<Pair<Long, String>> = ArrayList()) {
    var failure: Throwable? = null
    var completed = false
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.collect(stream: ReplayByteStream): Pair<Collected, Job> {
    val seen = Collected()
    val job = launch {
        try {
            stream.incoming.collect { seen.chunks += currentTime to it.decodeToString() }
            seen.completed = true
        } catch (e: ReplayedFailureException) {
            seen.failure = e
        }
    }
    return seen to job
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayStrictTest {
    private val session = recordingOf(
        RecordingEvent.open(0),
        tx(10, "ATZ\r"),
        rx(110, "ELM"),
        rx(160, " v2\r\r>"),
        tx(400, "01"),
        tx(401, "00\r"),
        rx(1001, "41 00 BE 3F A8 13\r\r>"),
        RecordingEvent.close(1100),
    )

    @Test
    fun reproducesChunksAndTheirDelaysExactlyAndConsumesEveryEvent() = runTest {
        val stream = ReplayByteStream(session, ReplayMode.STRICT)
        val (seen, job) = collect(stream)
        assertEquals(session.events.size - 1, stream.remainingEvents, "the open event is not waiting for anything")

        stream.write("ATZ\r".encodeToByteArray())
        delay(500)
        // Same bytes, different chunking: how the consumer splits its writes does not matter.
        stream.write("0".encodeToByteArray())
        stream.write("100".encodeToByteArray())
        stream.write("\r".encodeToByteArray())
        job.join()

        assertEquals(
            listOf(100L to "ELM", 150L to " v2\r\r>", 1100L to "41 00 BE 3F A8 13\r\r>"),
            seen.chunks,
        )
        assertTrue(seen.completed, "the recorded close ends the incoming stream")
        assertEquals(0, stream.remainingEvents)
        assertNull(stream.firstMismatch)
    }

    @Test
    fun speedDividesTheRecordedDelays() = runTest {
        val stream = ReplayByteStream(session, ReplayMode.STRICT, speed = 2.0)
        val (seen, job) = collect(stream)
        stream.write("ATZ\r".encodeToByteArray())
        delay(500)
        stream.write("0100\r".encodeToByteArray())
        job.join()
        // 100 and 50 ms halved, then 600 ms halved after the write at 500.
        assertEquals(listOf(50L, 75L, 800L), seen.chunks.map { it.first })
    }

    @Test
    fun aResponseCostsTheConsumersTimeNotTheWritersTime() = runTest {
        val stream = ReplayByteStream(session, ReplayMode.STRICT)
        val (_, job) = collect(stream)
        stream.write("ATZ\r".encodeToByteArray())
        assertEquals(0, currentTime, "write returns at once; the latency is spent on the reading side, where a timeout can see it")
        job.cancel()
    }

    @Test
    fun theWritesOfTheRecordingAreOnlyAnsweredWhenFullyMatched() = runTest {
        val stream = ReplayByteStream(session, ReplayMode.STRICT)
        val (seen, job) = collect(stream)
        stream.write("AT".encodeToByteArray())
        delay(1000)
        assertTrue(seen.chunks.isEmpty())
        stream.write("Z\r".encodeToByteArray())
        delay(1000)
        assertEquals(2, seen.chunks.size)
        job.cancel()
    }

    @Test
    fun aDifferentCommandIsAMismatchNamingEventExpectedAndActual() = runTest {
        val stream = ReplayByteStream(session, ReplayMode.STRICT)
        stream.write("ATZ\r".encodeToByteArray())
        val error = assertFailsWith<ReplayMismatchException> { stream.write("X100\r".encodeToByteArray()) }
        assertEquals(4, error.eventIndex)
        assertEquals("0100\\r", error.expected)
        assertEquals("X100\\r", error.actual)
        assertTrue("event #4" in error.message!! && "0100\\r" in error.message!! && "X100\\r" in error.message!!, error.message)
        assertSame(error, stream.firstMismatch)
        // A consumer that swallows the exception cannot carry on as if nothing had happened.
        assertFailsWith<ReplayMismatchException> { stream.write("0100\r".encodeToByteArray()) }
    }

    @Test
    fun aMismatchInTheMiddleOfAChunkedCommandPointsAtTheEventHoldingThatByte() = runTest {
        val stream = ReplayByteStream(session, ReplayMode.STRICT)
        stream.write("ATZ\r".encodeToByteArray())
        stream.write("01".encodeToByteArray())
        val error = assertFailsWith<ReplayMismatchException> { stream.write("0X\r".encodeToByteArray()) }
        assertEquals(5, error.eventIndex)
        assertEquals("0100\\r", error.expected)
        assertEquals("010X\\r", error.actual)
    }

    @Test
    fun writingAfterTheRecordedWritesHaveRunOutIsAMismatch() = runTest {
        val stream = ReplayByteStream(session, ReplayMode.STRICT)
        stream.write("ATZ\r".encodeToByteArray())
        stream.write("0100\r".encodeToByteArray())
        val error = assertFailsWith<ReplayMismatchException> { stream.write("\u0001\r".encodeToByteArray()) }
        assertEquals(session.events.size, error.eventIndex)
        assertEquals("<end of recording>", error.expected)
        assertEquals("\\x01\\r", error.actual)
    }

    @Test
    fun chunksRecordedBeforeTheFirstCommandAreDeliveredWhenReadingStarts() = runTest {
        val greeting = recordingOf(RecordingEvent.open(500), rx(530, "ELM327 v2.3\r\r>"), tx(900, "ATI\r"), rx(950, "ELM327\r\r>"))
        val stream = ReplayByteStream(greeting, ReplayMode.STRICT)
        val (seen, job) = collect(stream)
        delay(100)
        assertEquals(listOf(30L to "ELM327 v2.3\r\r>"), seen.chunks)
        stream.write("ATI\r".encodeToByteArray())
        delay(100)
        assertEquals(listOf(30L, 150L), seen.chunks.map { it.first })
        assertEquals(0, stream.remainingEvents)
        job.cancel()
    }

    @Test
    fun aRecordedErrorFailsTheIncomingStreamAtThatPoint() = runTest {
        val failing = recordingOf(RecordingEvent.open(0), tx(10, "ATZ\r"), rx(20, "OK\r\r>"), RecordingEvent.error(520, "link dropped"))
        val stream = ReplayByteStream(failing, ReplayMode.STRICT)
        val (seen, job) = collect(stream)
        stream.write("ATZ\r".encodeToByteArray())
        job.join()
        assertEquals(listOf(10L to "OK\r\r>"), seen.chunks)
        assertEquals("link dropped", seen.failure?.message)
        assertTrue(seen.failure is ReplayedFailureException)
        assertEquals(510L, currentTime)
        assertFalse(seen.completed)
        assertEquals(0, stream.remainingEvents)
    }

    @Test
    fun aRecordedHangUpCompletesIncomingAtThatTimeAndThenEveryWriteFailsLikeOnADeadLink() = runTest {
        val hungUp = recordingOf(
            RecordingEvent.open(0),
            tx(10, "ATZ\r"),
            rx(110, "OK\r\r>"),
            RecordingEvent.eof(500),
            RecordingEvent.close(510),
        )
        val stream = ReplayByteStream(hungUp, ReplayMode.STRICT)
        val (seen, job) = collect(stream)

        stream.write("ATZ\r".encodeToByteArray())
        delay(300)
        assertFalse(seen.completed, "the adapter is still there until the recorded time")
        job.join()

        assertEquals(listOf(100L to "OK\r\r>"), seen.chunks)
        assertTrue(seen.completed, "a hang-up ends incoming normally, not with a failure")
        assertNull(seen.failure)
        assertEquals(490L, currentTime, "100 ms of latency, then the 390 ms that passed until the adapter hung up")
        assertEquals(0, stream.remainingEvents)
        assertFailsWith<TransportException> { stream.write("ATZ\r".encodeToByteArray()) }
        assertFailsWith<TransportException> { stream.write(byteArrayOf(0x41)) }
        assertNull(stream.firstMismatch, "a write on a dead link is not a deviation from the recording")
    }

    @Test
    fun aHangUpAfterAnUnansweredRequestLeavesThatRequestWithoutAnswer() = runTest {
        val inFlight = recordingOf(RecordingEvent.open(0), tx(10, "0100\r"), RecordingEvent.eof(40), tx(45, "0100\r"), RecordingEvent.close(50))
        val stream = ReplayByteStream(inFlight, ReplayMode.STRICT)
        val (seen, job) = collect(stream)
        stream.write("0100\r".encodeToByteArray())
        job.join()
        assertTrue(seen.chunks.isEmpty())
        assertTrue(seen.completed)
        assertEquals(30L, currentTime)
        assertEquals(0, stream.remainingEvents, "what the consumer attempted after the hang-up is not waiting to be replayed")
    }

    @Test
    fun closingTheStreamEndsIncoming() = runTest {
        val stream = ReplayByteStream(recordingOf(tx(1, "A\r"), rx(2, "B")), ReplayMode.STRICT)
        val (seen, job) = collect(stream)
        stream.close()
        job.join()
        assertTrue(seen.completed)
        assertFailsWith<IllegalStateException> { stream.write(byteArrayOf(1)) }
    }

    @Test
    fun speedMustBePositive() {
        assertFailsWith<IllegalArgumentException> { ReplayByteStream(session, ReplayMode.STRICT, speed = 0.0) }
        assertFailsWith<IllegalArgumentException> { ReplayByteStream(session, ReplayMode.STRICT, speed = Double.NaN) }
    }

    @Test
    fun byCommandHasNoOrderToConsume() {
        assertNull(ReplayByteStream(session, ReplayMode.BY_COMMAND).remainingEvents)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayByCommandTest {
    private val session = recordingOf(
        RecordingEvent.open(0),
        tx(10, "ATZ\r"),
        rx(110, "ELM327 v2.3\r\r>"),
        tx(300, "0100\r"),
        rx(345, "41 00 BE 3F\r"),
        rx(350, "\r>"),
        tx(500, "010C\r"),
        rx(560, "41 0C 0C 7C\r\r>"),
        tx(700, "010C\r"),
        rx(770, "41 0C 1F 40\r\r>"),
        tx(900, "AT"),
        tx(901, "RV\r"),
        rx(1010, "14.1V\r\r>"),
    )

    @Test
    fun answersEachCommandWithItsOwnResponseWhateverTheOrder() = runTest {
        val stream = ReplayByteStream(session, ReplayMode.BY_COMMAND)
        val (seen, job) = collect(stream)
        // Reverse of the recorded order, with different spacing and case and a command written in two pieces.
        stream.write("atrv\r".encodeToByteArray())
        delay(2000)
        stream.write("01 00".encodeToByteArray())
        stream.write("\r".encodeToByteArray())
        delay(2000)
        stream.write("ATZ\r".encodeToByteArray())
        delay(2000)
        job.cancel()

        assertEquals(
            listOf(
                109L to "14.1V\r\r>",
                2000L + 45 to "41 00 BE 3F\r",
                2000L + 50 to "\r>",
                4000L + 100 to "ELM327 v2.3\r\r>",
            ),
            seen.chunks.map { it.first to it.second },
        )
        assertEquals(0, stream.unknownCommands)
    }

    @Test
    fun repeatedCommandsGetTheRecordedResponsesInTurnAndWrapRound() = runTest {
        val stream = ReplayByteStream(session, ReplayMode.BY_COMMAND)
        val (seen, job) = collect(stream)
        repeat(5) {
            stream.write("010C\r".encodeToByteArray())
            delay(1000)
        }
        job.cancel()
        assertEquals(
            listOf("41 0C 0C 7C\r\r>", "41 0C 1F 40\r\r>", "41 0C 0C 7C\r\r>", "41 0C 1F 40\r\r>", "41 0C 0C 7C\r\r>"),
            seen.chunks.map { it.second },
        )
    }

    @Test
    fun usesTheRecordedLatencyOfTheExchange() = runTest {
        val stream = ReplayByteStream(session, ReplayMode.BY_COMMAND, speed = 2.0)
        val (seen, job) = collect(stream)
        stream.write("010C\r".encodeToByteArray())
        stream.write("010C\r".encodeToByteArray())
        delay(1000)
        job.cancel()
        // 60 ms and then 70 ms recorded, halved; the second answer queues behind the first.
        assertEquals(listOf(30L, 65L), seen.chunks.map { it.first })
    }

    @Test
    fun aCommandNeverRecordedGetsAQuestionMarkAndIsCounted() = runTest {
        val stream = ReplayByteStream(session, ReplayMode.BY_COMMAND)
        val (seen, job) = collect(stream)
        stream.write("ATFOO 1\r".encodeToByteArray())
        stream.write("0105\r".encodeToByteArray())
        delay(10)
        job.cancel()
        assertEquals(listOf("?\r\r>", "?\r\r>"), seen.chunks.map { it.second })
        assertEquals(2, stream.unknownCommands)
        assertEquals(listOf("ATFOO1", "0105"), stream.unknownCommandTexts)
    }

    @Test
    fun aRecordedCommandWithoutAnyResponseStaysSilentLikeTheOriginal() = runTest {
        val silent = recordingOf(tx(1, "0100\r"), tx(900, "0100\r"), rx(950, "41 00\r\r>"))
        val stream = ReplayByteStream(silent, ReplayMode.BY_COMMAND)
        val (seen, job) = collect(stream)
        stream.write("0100\r".encodeToByteArray())
        delay(1000)
        assertTrue(seen.chunks.isEmpty(), "the first request timed out on the real link")
        stream.write("0100\r".encodeToByteArray())
        delay(1000)
        assertEquals(1, seen.chunks.size)
        assertEquals(0, stream.unknownCommands)
        job.cancel()
    }

    @Test
    fun aBareCommandWithoutItsCarriageReturnIsNotAnswered() = runTest {
        val stream = ReplayByteStream(session, ReplayMode.BY_COMMAND)
        val (seen, job) = collect(stream)
        stream.write("ATZ".encodeToByteArray())
        delay(1000)
        job.cancel()
        assertTrue(seen.chunks.isEmpty())
        assertEquals(0, stream.unknownCommands)
    }

    @Test
    fun aRecordedHangUpIsIgnoredAndWhatWasWrittenAfterItIsNotAnExchange() = runTest {
        val hungUp = recordingOf(
            tx(1, "0100\r"),
            rx(50, "41 00 AA\r\r>"),
            RecordingEvent.eof(910),
            // The consumer's attempt on the dead link: on record, but it never reached the adapter and got no answer.
            tx(920, "0100\r"),
            RecordingEvent.close(930),
        )
        val stream = ReplayByteStream(hungUp, ReplayMode.BY_COMMAND)
        val (seen, job) = collect(stream)
        repeat(3) {
            stream.write("0100\r".encodeToByteArray())
            delay(1000)
        }
        assertFalse(seen.completed, "the replayed link does not hang up")
        job.cancel()
        // Had the attempt on the dead link counted as an exchange, every second answer would have been silence.
        assertEquals(List(3) { "41 00 AA\r\r>" }, seen.chunks.map { it.second })
        assertEquals(0, stream.unknownCommands)
    }
}

/** The same request goes to different ECUs: what it means depends on the header that was selected when it was sent. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayByEcuTest {
    /** A recording of a consumer that asks, one command at a time, and what the adapter answered. */
    private class Script {
        private var time = 0L
        private val events = arrayListOf(RecordingEvent.open(0))

        fun ask(command: String, answer: String?) = apply {
            time += 100
            events += tx(time, "$command\r")
            if (answer != null) {
                time += 50
                events += rx(time, answer)
            }
        }

        fun build() = Recording(RecordingHeader(), events.toList())
    }

    private val ok = "OK\r\r>"
    private val okWithEcho7E0 = "ATSH7E0\r\nOK\r\n\r\n>"

    /** Sends each command and gives the adapter time to answer it. */
    private suspend fun ReplayByteStream.send(vararg commands: String) {
        for (command in commands) {
            write((command + "\r").encodeToByteArray())
            delay(1000)
        }
    }

    /** What the replay answered to [commands], in order, and the stream itself for its counters. */
    private fun replayed(recording: Recording, vararg commands: String): Pair<List<String>, ReplayByteStream> {
        val answers = ArrayList<String>()
        lateinit var stream: ReplayByteStream
        runTest {
            stream = ReplayByteStream(recording, ReplayMode.BY_COMMAND)
            val (seen, job) = collect(stream)
            stream.send(*commands)
            job.cancel()
            answers += seen.chunks.map { it.second }
        }
        return answers to stream
    }

    @Test
    fun theSameRequestIsAnsweredByTheEcuItWasSentToWhateverTheOrderAndTheNumberOfRepeats() {
        val recording = Script()
            .ask("ATRV", "14.1V\r\r>")
            .ask("ATSH7E0", okWithEcho7E0)
            .ask("010C1", "41 0C 0A 00\r\r>")
            .ask("ATSH7E1", ok)
            .ask("010C1", "41 0C 14 00\r\r>")
            .ask("010C1", "41 0C 15 00\r\r>")
            .build()

        // The other ECU first, a different number of requests per ECU, spacing and case of the commands changed, and an
        // AT command that was recorded under no header while another one is selected now.
        val (answers, stream) = replayed(
            recording,
            "ATSH 7e1", "010C1", "010C1", "010C1", "ATRV", "ATSH7E0", "010C1", "010c1",
        )

        assertEquals(
            listOf(
                ok,
                "41 0C 14 00\r\r>",
                "41 0C 15 00\r\r>",
                "41 0C 14 00\r\r>",
                "14.1V\r\r>",
                okWithEcho7E0,
                "41 0C 0A 00\r\r>",
                "41 0C 0A 00\r\r>",
            ),
            answers,
        )
        assertEquals(0, stream.unknownCommands, stream.unknownCommandTexts.toString())
    }

    @Test
    fun theResponseCountDigitDoesNotMakeTwoRequestsDifferent() {
        val recording = Script()
            .ask("ATSH7E0", okWithEcho7E0)
            .ask("09021", "7E8 10 0B 49 02 01 4D 41 54\r\r>")
            .build()

        // A session recorded with the digit is answered when the same request is now sent without it, and the other way round.
        val (answers, stream) = replayed(recording, "ATSH7E0", "0902", "09021")

        assertEquals(listOf(okWithEcho7E0, "7E8 10 0B 49 02 01 4D 41 54\r\r>", "7E8 10 0B 49 02 01 4D 41 54\r\r>"), answers)
        assertEquals(0, stream.unknownCommands)
    }

    @Test
    fun aRequestNeverRecordedForTheSelectedEcuIsUnknownAndReportedWithItsHeaderEvenIfAnotherEcuWasAsked() {
        val recording = Script()
            .ask("ATSH7E0", ok)
            .ask("0105", "41 05 5A\r\r>")
            .ask("ATSH7E1", ok)
            .ask("010C1", "41 0C 14 00\r\r>")
            .build()

        val (answers, stream) = replayed(recording, "ATSH7E1", "0105", "010C1", "ATSH7E0", "010C1", "ATSH7E4", "0105")

        assertEquals(
            listOf(ok, "?\r\r>", "41 0C 14 00\r\r>", ok, "?\r\r>", "?\r\r>", "41 05 5A\r\r>"),
            answers,
            "ATSH7E4 was never recorded, so it is unknown and selects nothing: the next request still goes to 7E0",
        )
        // A request is matched and reported without its response-count digit, so 010C1 shows as 010C.
        assertEquals(listOf("7E1:0105", "7E0:010C", "ATSH7E4"), stream.unknownCommandTexts)
        assertEquals(3, stream.unknownCommands)
    }

    @Test
    fun aRequestWithNoHeaderSelectedIsKeyedByItsTextAloneAndReportedThatWay() {
        val recording = Script().ask("ATSH7E0", ok).ask("0105", "41 05 5A\r\r>").build()
        val (answers, stream) = replayed(recording, "0105")
        assertEquals(listOf("?\r\r>"), answers)
        assertEquals(listOf("0105"), stream.unknownCommandTexts)
    }

    @Test
    fun atzAndAtdForgetTheSelectedHeaderOnTheRecordingAndOnTheReplay() {
        val recording = Script()
            .ask("0100", "41 00 AA\r\r>")
            .ask("ATSH7E0", ok)
            .ask("0100", "41 00 BB\r\r>")
            .ask("ATZ", "ELM327 v2.3\r\r>")
            .ask("0100", "41 00 CC\r\r>")
            .ask("ATSH7E0", ok)
            .ask("ATD", ok)
            .ask("0100", "41 00 DD\r\r>")
            .build()

        val (answers, stream) = replayed(recording, "ATSH7E0", "0100", "ATZ", "0100", "ATSH7E0", "ATD", "0100")

        assertEquals(
            listOf(ok, "41 00 BB\r\r>", "ELM327 v2.3\r\r>", "41 00 AA\r\r>", ok, ok, "41 00 CC\r\r>"),
            answers,
            "after ATZ and after ATD the request is the one that was recorded with no header, used in turn",
        )
        assertEquals(0, stream.unknownCommands, stream.unknownCommandTexts.toString())
    }

    @Test
    fun anAtshTheAdapterAnsweredWithAQuestionMarkSelectsNothing() {
        val recording = Script()
            .ask("ATSH7E0", "?\r\r>")
            .ask("0100", "41 00 AA\r\r>")
            .build()

        val (answers, stream) = replayed(recording, "ATSH7E0", "0100")

        assertEquals(listOf("?\r\r>", "41 00 AA\r\r>"), answers)
        assertEquals(0, stream.unknownCommands, stream.unknownCommandTexts.toString())
    }

    @Test
    fun anAtshThatGotNoAnswerAtAllSelectsNothingEither() {
        val recording = Script()
            .ask("ATSH7E0", null)
            .ask("0100", "41 00 AA\r\r>")
            .build()

        val (answers, stream) = replayed(recording, "ATSH7E0", "0100")

        assertEquals(listOf("41 00 AA\r\r>"), answers)
        assertEquals(0, stream.unknownCommands)
    }

    @Test
    fun theLatencyAndTheWrapRoundOfAnEcusResponsesAreKeptPerEcu() = runTest {
        val recording = Script()
            .ask("ATSH7E0", ok)
            .ask("010C", "41 0C 0A 00\r\r>")
            .ask("ATSH7E1", ok)
            .ask("010C", "41 0C 14 00\r\r>")
            .ask("010C", "41 0C 15 00\r\r>")
            .build()
        val stream = ReplayByteStream(recording, ReplayMode.BY_COMMAND)
        val (seen, job) = collect(stream)
        stream.send("ATSH7E1")
        val before = currentTime
        stream.write("010C\r".encodeToByteArray())
        delay(1000)
        stream.send("010C", "010C", "ATSH7E0", "010C", "010C")
        job.cancel()

        // 50 ms recorded between request and answer, however much recorded time lay between the ECUs' requests.
        assertEquals(before + 50, seen.chunks[1].first)
        assertEquals(
            listOf("41 0C 14 00\r\r>", "41 0C 15 00\r\r>", "41 0C 14 00\r\r>", "41 0C 0A 00\r\r>", "41 0C 0A 00\r\r>"),
            seen.chunks.map { it.second }.filter { it.startsWith("41") },
        )
    }
}
