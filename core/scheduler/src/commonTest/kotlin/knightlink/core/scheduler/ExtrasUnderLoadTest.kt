package knightlink.core.scheduler

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ExtrasUnderLoadTest {
    private fun live(pid: Int, period: Duration) = PollItem("live$pid", 0x7E0, byteArrayOf(0x01, pid.toByte()), period, dataLength = 1)

    private fun raw(id: String, requestId: Int) = PollItem(id, requestId, byteArrayOf(0x22, 0x20, id.last().code.toByte()), 6.seconds, expectedResponses = 1)

    /** 2026-11-22: with the adapter saturated by fast signals, the seeded raw identifiers were asked once in 67 minutes. */
    @Test
    fun rawExtrasAreStillAskedAboutEveryPeriodWhileTheFastSignalsSaturateTheAdapter() {
        val fast = listOf(0x04, 0x0B, 0x0C, 0x0D, 0x45, 0x49, 0x4A, 0x4C, 0x62, 0x8E).map { live(it, 150.milliseconds) }
        val slow = listOf(0x05, 0x1F, 0x2F, 0x30, 0x31, 0x33, 0x42, 0x46).map { live(it, 6.seconds) }
        val scheduler = PollScheduler(fast + slow, maxBatch = 6)
        val extras = (1..8).map { raw("7E0:2200$it", 0x7E0) } + (1..8).map { raw("7E4:2200$it", 0x7E4) }
        scheduler.replaceExtras(extras)

        val asked = HashMap<String, Int>()
        var now = Duration.ZERO
        var current: Int? = null
        while (now < 300.seconds) {
            val decision = scheduler.next(now, current)
            if (decision !is PollDecision.Poll) {
                now += 10.milliseconds
                continue
            }
            // About 70 ms per exchange, 25 ms more when the other control unit has to be addressed first.
            val cost = 70.milliseconds + if (current != null && current != decision.requestId) 25.milliseconds else Duration.ZERO
            scheduler.onResult(decision, now, decision.items.toSet())
            decision.items.forEach { asked[it.id] = (asked[it.id] ?: 0) + 1 }
            current = decision.requestId
            now += cost
        }
        val worst = extras.minOf { asked[it.id] ?: 0 }
        assertTrue(worst >= 30, "the least asked raw identifier was asked $worst times in 300 s (about 50 expected): $asked")
    }
}
