package knightlink.core.scheduler

import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun item(id: String, period: Duration, requestId: Int = 0x7E0) =
    PollItem(id, requestId, byteArrayOf(0x01, 0x0C), period)

/** Drives a scheduler as if every poll took [cost], and counts polls per item and ECU switches. */
private class Simulated(val scheduler: PollScheduler, val cost: Duration, val answers: (PollItem) -> Boolean = { true }) {
    val counts = HashMap<String, Int>()
    var switches = 0
    var now = Duration.ZERO
    private var current: Int? = null

    fun run(until: Duration) {
        while (now < until) {
            when (val decision = scheduler.next(now, current)) {
                is PollDecision.Wait -> now += decision.duration
                PollDecision.Idle -> return
                is PollDecision.Poll -> {
                    if (current != null && current != decision.item.requestId) switches++
                    current = decision.item.requestId
                    val sentAt = now
                    now += cost
                    counts[decision.item.id] = (counts[decision.item.id] ?: 0) + 1
                    scheduler.onResult(decision.item, sentAt, answers(decision.item))
                }
            }
        }
    }
}

class PollSchedulerTest {
    @Test
    fun emptySchedulerIsIdle() {
        assertEquals(PollDecision.Idle, PollScheduler(emptyList()).next(Duration.ZERO))
    }

    @Test
    fun startsWithFastestItemsThenWaitsUntilSomethingIsDue() {
        val slow = item("slow", 5.seconds)
        val fast = item("fast", 200.milliseconds)
        val scheduler = PollScheduler(listOf(slow, fast))

        assertEquals("fast", assertIs<PollDecision.Poll>(scheduler.next(Duration.ZERO)).item.id)
        scheduler.onResult(fast, sentAt = Duration.ZERO, answered = true)
        assertEquals("slow", assertIs<PollDecision.Poll>(scheduler.next(50.milliseconds)).item.id)
        scheduler.onResult(slow, sentAt = 50.milliseconds, answered = true)

        // fast is due at 200 ms, slow at 5050 ms.
        assertEquals(100.milliseconds, assertIs<PollDecision.Wait>(scheduler.next(100.milliseconds)).duration)
        assertEquals("fast", assertIs<PollDecision.Poll>(scheduler.next(200.milliseconds)).item.id)
    }

    @Test
    fun meetsWantedRatesWhenCapacityAllows() {
        val sim = Simulated(PollScheduler(listOf(item("a", 200.milliseconds), item("b", 1.seconds), item("c", 5.seconds))), cost = 50.milliseconds)
        sim.run(60.seconds)
        // 60 s at 5 Hz, 1 Hz and 0.2 Hz. A poll can start slightly late when another is in flight.
        assertTrue(sim.counts.getValue("a") in 285..301, "a=${sim.counts["a"]}")
        assertTrue(sim.counts.getValue("b") in 58..61, "b=${sim.counts["b"]}")
        assertTrue(sim.counts.getValue("c") in 12..13, "c=${sim.counts["c"]}")
    }

    @Test
    fun overloadSlowsEveryItemByTheSameProportion() {
        // Wanted: 3 items at 10 Hz and one at 1 Hz = 31 polls/s. Capacity: 10 polls/s.
        val items = listOf("f1", "f2", "f3").map { item(it, 100.milliseconds) } + item("slow", 1.seconds)
        val sim = Simulated(PollScheduler(items), cost = 100.milliseconds)
        sim.run(120.seconds)

        assertEquals(1200, sim.counts.values.sum())
        val fast = listOf("f1", "f2", "f3").map { sim.counts.getValue(it) }
        assertTrue(fast.max() - fast.min() <= 2, "fast items should be served equally: $fast")
        val ratio = fast.average() / sim.counts.getValue("slow")
        assertTrue(ratio in 8.0..12.0, "fast:slow should stay near the wanted 10:1, was $ratio")
    }

    @Test
    fun unansweredItemsAreBackedOffAndRecover() {
        val dead = item("dead", 200.milliseconds)
        val live = item("live", 200.milliseconds)
        var deadAnswers = false
        val scheduler = PollScheduler(listOf(dead, live))
        val sim = Simulated(scheduler, cost = 50.milliseconds) { it !== dead || deadAnswers }
        sim.run(60.seconds)
        val deadBefore = sim.counts.getValue("dead")
        assertTrue(deadBefore < 25, "dead item should be polled rarely, was $deadBefore")
        assertTrue(sim.counts.getValue("live") > 200)

        deadAnswers = true
        sim.run(120.seconds)
        assertTrue(sim.counts.getValue("dead") - deadBefore > 150, "item should return to its normal rate once it answers")
    }

    @Test
    fun prefersTheSelectedEcuToAvoidHeaderSwitches() {
        fun decide(tcmPeriod: Duration, current: Int?): String {
            val ecm = item("ecm", 200.milliseconds, 0x7E0)
            val tcm = item("tcm", tcmPeriod, 0x7E1)
            val scheduler = PollScheduler(listOf(ecm, tcm), switchThreshold = 1.5)
            scheduler.onResult(ecm, sentAt = Duration.ZERO, answered = true)
            scheduler.onResult(tcm, sentAt = Duration.ZERO, answered = true)
            return assertIs<PollDecision.Poll>(scheduler.next(300.milliseconds, current)).item.id
        }
        // At 300 ms the ECM item is 1.5 periods overdue.
        // TCM 2.0 periods overdue: the most overdue item wins when no ECU is selected...
        assertEquals("tcm", decide(150.milliseconds, current = null))
        // ...but is not worth a header change while the ECM is selected (2.0 <= 1.5 * 1.5)...
        assertEquals("ecm", decide(150.milliseconds, current = 0x7E0))
        // ...until it is far enough behind (3.0 > 1.5 * 1.5).
        assertEquals("tcm", decide(100.milliseconds, current = 0x7E0))
    }

    @Test
    fun affinityDoesNotStarveAnEcu() {
        val items = listOf(
            item("ecm1", 200.milliseconds, 0x7E0),
            item("tcm1", 210.milliseconds, 0x7E1),
            item("ecm2", 220.milliseconds, 0x7E0),
            item("tcm2", 230.milliseconds, 0x7E1),
        )
        val sim = Simulated(PollScheduler(items), cost = 60.milliseconds).apply { run(60.seconds) }
        val counts = sim.counts.values
        assertEquals(4, counts.size)
        assertTrue(counts.max() - counts.min() <= counts.max() / 5, "uneven service: ${sim.counts}")
        assertTrue(sim.switches <= sim.counts.values.sum() / 2, "at most one header change per two polls: ${sim.switches}")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PollingLoopTest {
    private val positive = ElmResponse(listOf(DiagnosticResponse.Positive(0x7E8, 1, byteArrayOf(0x0C, 0x0C, 0x7C))), emptyList(), emptyList(), emptyList())

    @Test
    fun pollsOnScheduleAndReportsRoundTrip() = runTest {
        val requester = DiagnosticRequester { _, _, _ ->
            delay(40.milliseconds)
            positive
        }
        val loop = PollingLoop(requester, PollScheduler(listOf(item("rpm", 200.milliseconds))), testTimeSource)

        val results = loop.results().take(4).toList()

        assertTrue(results.all { it.answered && it.roundTrip == 40.milliseconds })
        // Sent every 200 ms; each result is stamped when its reply arrives 40 ms later.
        assertEquals(listOf(40, 240, 440, 640).map { it.milliseconds }, results.map { it.at })
    }

    @Test
    fun aFailedRequestIsReportedAndPollingContinues() = runTest {
        var calls = 0
        val requester = DiagnosticRequester { _, _, _ -> if (++calls == 2) throw IllegalStateException("timeout") else positive }
        val loop = PollingLoop(requester, PollScheduler(listOf(item("rpm", 100.milliseconds))), testTimeSource)

        val results = loop.results().take(3).toList()

        assertEquals(listOf(true, false, true), results.map { it.answered })
        assertIs<IllegalStateException>(results[1].error)
    }

    @Test
    fun stopsWhenTheAdapterKeepsFailing() = runTest {
        val requester = DiagnosticRequester { _, _, _ -> throw IllegalStateException("gone") }
        val loop = PollingLoop(requester, PollScheduler(listOf(item("rpm", 100.milliseconds))), testTimeSource, maxConsecutiveFailures = 3)

        val seen = ArrayList<PollResult>()
        assertFailsWith<PollingStalledException> { loop.results().collect { seen += it } }
        assertEquals(2, seen.size)
    }
}
