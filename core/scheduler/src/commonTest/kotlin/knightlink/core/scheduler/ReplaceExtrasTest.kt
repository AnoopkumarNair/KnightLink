package knightlink.core.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReplaceExtrasTest {
    private fun item(id: String, period: Duration) = PollItem(id, 0x7E0, byteArrayOf(0x22, 0x20, id.last().code.toByte()), period)

    @Test
    fun anExtraThatStaysKeepsItsTimingWhenTheListChangesAndANewOneIsDueAtOnce() {
        val scheduler = PollScheduler(listOf(item("live", 150.milliseconds)))
        scheduler.replaceExtras(listOf(item("slow", 8.seconds)))
        var now = Duration.ZERO
        repeat(2) {
            val poll = assertIs<PollDecision.Poll>(scheduler.next(now))
            scheduler.onResult(poll, now, setOf(poll.item))
            now += 50.milliseconds
        }

        // The list changes: the extra that was asked a moment ago is listed first, a new one second. If the old one lost its history it
        // would be due again at once and, listed first, would be asked before the new one.
        scheduler.replaceExtras(listOf(item("slow", 8.seconds), item("other", 8.seconds)))
        val next = assertIs<PollDecision.Poll>(scheduler.next(now + 20.milliseconds))
        assertEquals("other", next.item.id)
    }
}
