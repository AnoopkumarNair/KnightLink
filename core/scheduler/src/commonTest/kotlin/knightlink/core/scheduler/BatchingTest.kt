package knightlink.core.scheduler

import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun hex2(value: Int) = (value and 0xFF).toString(16).uppercase().padStart(2, '0')

private fun pid(
    pid: Int,
    period: Duration = 1.seconds,
    length: Int? = 1,
    requestId: Int = 0x7E0,
    expected: Int? = null,
) = PollItem("${requestId.toString(16).uppercase()}:01${hex2(pid)}", requestId, byteArrayOf(0x01, pid.toByte()), period, expected, length)

private fun PollDecision.poll(): PollDecision.Poll = assertIs<PollDecision.Poll>(this)

private fun hex(bytes: ByteArray) = bytes.joinToString("") { hex2(it.toInt()) }

class BatchingTest {
    @Test
    fun dueItemsOfOneEcuShareARequestInTheOrderOfUrgency() {
        val scheduler = PollScheduler(listOf(pid(0x0C, 150.milliseconds, 2), pid(0x0D, 150.milliseconds), pid(0x04, 150.milliseconds)), maxBatch = 6)
        val poll = scheduler.next(Duration.ZERO).poll()
        assertEquals(3, poll.items.size)
        assertEquals("01" + poll.items.joinToString("") { hex2(it.payload[1].toInt()) }, hex(poll.payload))
        assertEquals(0x7E0, poll.requestId)
    }

    @Test
    fun aBatchHoldsAtMostSixPidsAndTheRestFollows() {
        val items = (1..8).map { pid(it, 1.seconds) }
        val scheduler = PollScheduler(items, maxBatch = 6)
        val first = scheduler.next(Duration.ZERO).poll()
        assertEquals(6, first.items.size)
        assertEquals(7, first.payload.size)
        scheduler.onResult(first, Duration.ZERO, first.items.toSet())
        val second = scheduler.next(Duration.ZERO).poll()
        assertEquals(2, second.items.size)
        assertTrue(first.items.none { it in second.items })
    }

    @Test
    fun maxBatchOneAsksEveryPidAlone() {
        val scheduler = PollScheduler(listOf(pid(1), pid(2), pid(3)))
        assertEquals(1, scheduler.next(Duration.ZERO).poll().items.size)
    }

    @Test
    fun itemsOfOtherEcusAreNeverInTheSameRequest() {
        val scheduler =
            PollScheduler(listOf(pid(1, requestId = 0x7E0), pid(2, requestId = 0x7E0), pid(3, requestId = 0x7E4), pid(4, requestId = 0x7E4)), maxBatch = 6)
        val first = scheduler.next(Duration.ZERO).poll()
        assertEquals(2, first.items.size)
        assertTrue(first.items.all { it.requestId == first.requestId })
    }

    @Test
    fun anItemWithoutAKnownLengthOrOtherThanServiceZeroOneIsNeverBatched() {
        val unknown = pid(1, length = null)
        val uds = PollItem("uds", 0x7E0, byteArrayOf(0x22, 0x10, 0x00), 1.seconds, null, 2)
        val ok1 = pid(2)
        val ok2 = pid(3)
        val scheduler = PollScheduler(listOf(unknown, uds, ok1, ok2), maxBatch = 6)
        val seen = ArrayList<PollDecision.Poll>()
        var now = Duration.ZERO
        while (seen.size < 3) {
            val poll = scheduler.next(now).poll()
            seen += poll
            scheduler.onResult(poll, now, poll.items.toSet())
        }
        // Whichever goes first: the unbatchable ones are always alone, and the two batchable ones go together.
        assertTrue(seen.filter { unknown in it.items || uds in it.items }.all { it.items.size == 1 })
        assertTrue(seen.any { ok1 in it.items && ok2 in it.items })
    }

    @Test
    fun anItemThatIsNearlyDueRidesAlongButAFreshOneDoesNot() {
        val fast = pid(1, 100.milliseconds)
        val medium = pid(2, 1.seconds)
        val scheduler = PollScheduler(listOf(fast, medium), maxBatch = 6)
        val first = scheduler.next(Duration.ZERO).poll()
        scheduler.onResult(first, Duration.ZERO, first.items.toSet())
        // The fast item is due at 100 ms; the medium one has waited a tenth of its period: it stays out.
        assertEquals(listOf(fast), scheduler.next(100.milliseconds).poll().items)
        // At 720 ms the medium item is not due yet (72 %), but the fast one is, so the request carries both.
        scheduler.onResult(PollDecision.Poll(fast), Duration.ZERO + 600.milliseconds, setOf(fast))
        assertEquals(setOf(fast, medium), scheduler.next(720.milliseconds).poll().items.toSet())
    }

    @Test
    fun theCountDigitIsOnlyUsedWhenAllRepliesFitOneFrame() {
        // 41 04 xx: 3 bytes. 41 04 xx 05 xx = 5 bytes, fits. Adding a 2 byte PID makes 8, which does not.
        val small = PollDecision.Poll(pid(4), listOf(pid(5)))
        assertEquals(1, small.expectedResponses)
        val large = PollDecision.Poll(pid(4), listOf(pid(5), pid(0x0C, length = 2)))
        assertNull(large.expectedResponses)
        // A single request uses the digit the item carries.
        assertEquals(1, PollDecision.Poll(pid(4, expected = 1)).expectedResponses)
        assertNull(PollDecision.Poll(pid(4)).expectedResponses)
    }

    @Test
    fun aRepliesPidsAreCutByTheirLengthsAndOnlyThoseThatArrivedCount() {
        val rpm = pid(0x0C, length = 2)
        val speed = pid(0x0D)
        val load = pid(0x04)
        val poll = PollDecision.Poll(rpm, listOf(speed, load))
        fun reply(vararg bytes: Int) = ElmResponse(
            listOf(DiagnosticResponse.Positive(0x7E8, 1, ByteArray(bytes.size) { bytes[it].toByte() })),
            emptyList(),
            emptyList(),
            emptyList(),
        )
        assertEquals(listOf(rpm, speed, load), answeredItems(poll, reply(0x0C, 0x0F, 0x64, 0x0D, 0x0B, 0x04, 0x20)))
        // The ECU left one out (it does not support it): the others still count.
        assertEquals(listOf(rpm, load), answeredItems(poll, reply(0x0C, 0x0F, 0x64, 0x04, 0x20)))
        // A reply cut short ends the walk; what came before it counts.
        assertEquals(listOf(rpm), answeredItems(poll, reply(0x0C, 0x0F, 0x64, 0x0D)))
        assertEquals(emptyList(), answeredItems(poll, ElmResponse(emptyList(), emptyList(), emptyList(), emptyList())))
        // A single request is answered by any positive reply.
        assertEquals(listOf(rpm), answeredItems(PollDecision.Poll(rpm), reply(0x0C, 0x0F, 0x64)))
    }

    // --- fallback -------------------------------------------------------------------------------------------

    /** Runs [rounds] requests, answering each from [answers], and returns every poll made. */
    private fun run(scheduler: PollScheduler, rounds: Int, answers: (PollDecision.Poll) -> Set<PollItem>): List<PollDecision.Poll> {
        val polls = ArrayList<PollDecision.Poll>()
        var now = Duration.ZERO
        repeat(rounds) {
            val decision = scheduler.next(now)
            val poll = (decision as? PollDecision.Poll) ?: run {
                now += (decision as PollDecision.Wait).duration
                scheduler.next(now).poll()
            }
            polls += poll
            scheduler.onResult(poll, now, answers(poll))
            now += 50.milliseconds
        }
        return polls
    }

    @Test
    fun anEcuThatDoesNotTakeBatchesIsAskedOneAtATimeAfterTwoConfirmedFailures() {
        val items = (1..6).map { pid(it, 100.milliseconds) }
        val scheduler = PollScheduler(items, maxBatch = 6)
        // A batch gets no answer, a single one does: the batch is the problem.
        val polls = run(scheduler, 12) { poll -> if (poll.items.size > 1) emptySet() else poll.items.toSet() }
        assertEquals(setOf(0x7E0), scheduler.singleRequestOnly)
        val firstSingleOnly = polls.indexOfFirst { it.items.size == 1 }
        assertTrue(firstSingleOnly in 1..3, "the batch is followed at once by a single request to check: $firstSingleOnly")
        assertTrue(polls.drop(4).all { it.items.size == 1 }, "after two failed batches every request is single: ${polls.map { it.items.size }}")
        assertEquals(2, polls.count { it.items.size > 1 })
    }

    @Test
    fun aSilentEcuIsNotBlamedOnBatching() {
        val items = (1..6).map { pid(it, 100.milliseconds) }
        val scheduler = PollScheduler(items, maxBatch = 6)
        // Nothing is answered at all, batch or single: the car is off, not unable to batch.
        run(scheduler, 30) { emptySet() }
        assertTrue(scheduler.singleRequestOnly.isEmpty())
    }

    @Test
    fun aPidThatKeepsMissingFromBatchAnswersIsAskedAloneWhileTheOthersStayBatched() {
        val bad = pid(3)
        val items = listOf(pid(1), pid(2), bad, pid(4))
        val scheduler = PollScheduler(items, maxBatch = 6)
        // The ECU answers every batch but leaves PID 3 out.
        val polls = run(scheduler, 12) { poll -> poll.items.filter { it !== bad }.toSet() }
        assertTrue(scheduler.singleRequestOnly.isEmpty(), "one bad PID does not end batching for the ECU")
        val batchesWithBad = polls.filter { bad in it.items && it.items.size > 1 }
        assertEquals(2, batchesWithBad.size, "it is tried in a batch twice")
        assertTrue(polls.any { it.items == listOf(bad) }, "then it is asked alone")
        assertTrue(polls.any { it.items.size > 1 && bad !in it.items }, "the others still share requests")
    }

    @Test
    fun aSuccessfulBatchClearsTheSuspicion() {
        val items = (1..4).map { pid(it, 100.milliseconds) }
        val scheduler = PollScheduler(items, maxBatch = 6)
        var failFirst = true
        run(scheduler, 20) { poll ->
            if (poll.items.size > 1 && failFirst) {
                failFirst = false
                emptySet()
            } else {
                poll.items.toSet()
            }
        }
        assertTrue(scheduler.singleRequestOnly.isEmpty(), "one failed batch, then fine: batching stays")
    }

    @Test
    fun anItemAskedOnceIsNeverDueAgain() {
        val once = pid(1, Duration.INFINITE)
        val scheduler = PollScheduler(listOf(once, pid(2, 1.seconds)))
        val first = scheduler.next(Duration.ZERO).poll()
        scheduler.onResult(first, Duration.ZERO, first.items.toSet())
        val second = scheduler.next(Duration.ZERO).poll()
        scheduler.onResult(second, Duration.ZERO, second.items.toSet())
        for (seconds in listOf(1, 10, 1000)) {
            val decision = scheduler.next(seconds.seconds)
            assertTrue(decision is PollDecision.Poll && decision.items.none { it === once }, "$seconds s")
            if (decision is PollDecision.Poll) scheduler.onResult(decision, seconds.seconds, decision.items.toSet())
        }
        // A restart (after the adapter was initialised again) asks it again.
        scheduler.restart()
        val again = ArrayList<PollItem>()
        repeat(2) {
            val poll = scheduler.next(Duration.ZERO).poll()
            again += poll.items
            scheduler.onResult(poll, Duration.ZERO, poll.items.toSet())
        }
        assertTrue(once in again)
    }

    @Test
    fun maxBatchMustBeBetweenOneAndSix() {
        assertFailsWith<IllegalArgumentException> { PollScheduler(emptyList(), maxBatch = 0) }
        assertFailsWith<IllegalArgumentException> { PollScheduler(emptyList(), maxBatch = 7) }
    }

    // --- the loop ---------------------------------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theLoopSendsTheBatchAndReportsEveryItemOfIt() = runTest {
        val sent = ArrayList<Triple<Int, String, Int?>>()
        val requester = DiagnosticRequester { id, payload, expected ->
            sent += Triple(id, hex(payload), expected)
            // Answers 0C (2 bytes) and 0D (1 byte) in request order.
            ElmResponse(
                listOf(DiagnosticResponse.Positive(0x7E8, 1, byteArrayOf(0x0C, 0x0F, 0x64, 0x0D, 0x0B))),
                emptyList(),
                emptyList(),
                emptyList(),
            )
        }
        val rpm = pid(0x0C, 150.milliseconds, 2)
        val speed = pid(0x0D, 150.milliseconds)
        val loop = PollingLoop(requester, PollScheduler(listOf(rpm, speed), maxBatch = 6), testTimeSource)

        val result = loop.results().take(2).toList()

        assertEquals(Triple(0x7E0, "010C0D", 1), sent.first(), "01 41 0C xx xx 0D xx is 6 bytes: one frame, so the count digit is fine")
        assertEquals(listOf(rpm, speed), result.first().items)
        assertEquals(listOf(rpm, speed), result.first().answeredItems)
        assertContentEquals(result.first().items, result.first().answeredItems)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aLoopWithOnlyAskedOnceItemsKeepsWakingInsteadOfWaitingForever() = runTest {
        val requester = DiagnosticRequester { _, _, _ ->
            ElmResponse(listOf(DiagnosticResponse.Positive(0x7E8, 1, byteArrayOf(0x1C, 0x06))), emptyList(), emptyList(), emptyList())
        }
        val loop = PollingLoop(requester, PollScheduler(listOf(pid(0x1C, Duration.INFINITE))), testTimeSource, idleDelay = 1.seconds)
        // One result, then the loop sleeps in slices; cancelling the collection ends it. (A forever-delay would hang the test.)
        assertEquals(1, loop.results().take(1).toList().size)
    }
}
