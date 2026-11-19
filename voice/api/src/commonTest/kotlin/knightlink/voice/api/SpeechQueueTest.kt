package knightlink.voice.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class SpeechQueueTest {
    private val clock = TestTimeSource()

    /** A mutable wrapper so a test reads like a script; the queue itself is immutable. */
    private inner class Harness(capacity: Int = 16) {
        var queue = SpeechQueue(quietPeriod = 60.seconds, capacity = capacity, timeSource = clock)

        fun offer(
            text: String,
            priority: Priority = Priority.STATUS,
            category: String = text,
            maxAge: kotlin.time.Duration? = null,
            force: Boolean = false,
        ): SpeakResult {
            val offered = queue.offer(Utterance(text, priority, category, maxAge), force)
            queue = offered.queue
            return offered.result
        }

        fun poll(min: Priority = Priority.STATUS): String? {
            val polled = queue.poll(min)
            queue = polled.queue
            return polled.utterance?.text
        }

        fun drain(): List<String> = generateSequence { poll() }.toList()

        fun waiting(): List<String> = queue.waitingLines.map { it.text }
    }

    @Test
    fun higherPriorityFirstAndFifoWithinAPriority() {
        val q = Harness()
        q.offer("status one")
        q.offer("answer", Priority.ANSWER)
        q.offer("status two")
        q.offer("critical", Priority.CRITICAL)
        q.offer("alert", Priority.ALERT)
        q.offer("answer two", Priority.ANSWER)
        assertEquals(listOf("critical", "alert", "answer", "answer two", "status one", "status two"), q.drain())
    }

    @Test
    fun newerLineOfACategoryReplacesTheWaitingOneInPlace() {
        val q = Harness()
        q.offer("first", category = "x")
        q.offer("second", category = "y")
        assertEquals(SpeakResult.REPLACED, q.offer("first, changed", category = "x"))
        assertEquals(listOf("first, changed", "second"), q.waiting())
    }

    @Test
    fun replacementOfAnotherPriorityIsPlacedByItsOwnPriority() {
        val up = Harness()
        up.offer("x1", category = "x")
        up.offer("y1", category = "y")
        assertEquals(SpeakResult.REPLACED, up.offer("x2", Priority.ALERT, category = "x"))
        assertEquals(listOf("x2", "y1"), up.waiting())

        val down = Harness()
        down.offer("x1", Priority.ALERT, category = "x")
        down.offer("y1", Priority.ALERT, category = "y")
        assertEquals(SpeakResult.REPLACED, down.offer("x2", Priority.STATUS, category = "x"))
        assertEquals(listOf("y1", "x2"), down.waiting())
    }

    @Test
    fun aLineEqualToTheWaitingOneIsUnchanged() {
        val q = Harness()
        q.offer("same", category = "x")
        assertEquals(SpeakResult.UNCHANGED, q.offer("same", category = "x"))
        assertEquals(1, q.waiting().size)
    }

    @Test
    fun theSameLineWithinTheQuietPeriodIsUnchangedAndAfterItIsQueued() {
        val q = Harness()
        q.offer("connected", category = "connection")
        assertEquals("connected", q.poll())
        q.queue = q.queue.finished()

        clock += 59.seconds
        assertEquals(SpeakResult.UNCHANGED, q.offer("connected", category = "connection"))
        assertEquals(SpeakResult.QUEUED, q.offer("connected", category = "other"))
        clock += 2.seconds
        assertEquals(SpeakResult.QUEUED, q.offer("connected", category = "connection"))
    }

    @Test
    fun aDifferentTextInTheQuietPeriodIsQueued() {
        val q = Harness()
        q.offer("connected", category = "connection")
        q.poll()
        q.queue = q.queue.finished()
        assertEquals(SpeakResult.QUEUED, q.offer("disconnected", category = "connection"))
    }

    @Test
    fun theLineBeingSpokenIsUnchangedEvenAfterTheQuietPeriod() {
        val q = Harness()
        q.offer("long line", category = "x")
        q.poll()
        clock += 5.seconds * 60
        assertEquals(SpeakResult.UNCHANGED, q.offer("long line", category = "x"))
    }

    @Test
    fun forcingSkipsTheQuietCheck() {
        val q = Harness()
        q.offer("a", category = "x")
        q.poll()
        q.queue = q.queue.finished()
        assertEquals(SpeakResult.QUEUED, q.offer("a", category = "x", force = true))
    }

    @Test
    fun staleLinesAreDroppedWhenPolled() {
        val q = Harness()
        q.offer("stale", maxAge = 20.seconds)
        q.offer("patient")
        clock += 19.seconds
        assertEquals(listOf("stale", "patient"), q.waiting())
        clock += 2.seconds
        assertEquals("patient", q.poll())
        assertNull(q.poll())
        assertEquals(emptyList(), q.waiting())
    }

    @Test
    fun aStaleLineIsDroppedEvenWhenHeld() {
        val q = Harness()
        q.offer("stale", Priority.ANSWER, maxAge = 5.seconds)
        clock += 6.seconds
        assertNull(q.poll(Priority.CRITICAL))
        assertEquals(emptyList(), q.waiting())
    }

    @Test
    fun aMinimumPriorityHoldsTheLowerLines() {
        val q = Harness()
        q.offer("status")
        q.offer("alert", Priority.ALERT)
        assertNull(q.poll(Priority.CRITICAL))
        assertEquals(false, q.queue.hasEligible(Priority.CRITICAL))
        q.offer("critical", Priority.CRITICAL)
        assertEquals("critical", q.poll(Priority.CRITICAL))
        assertEquals(listOf("alert", "status"), q.waiting())
    }

    @Test
    fun aFullQueueDropsTheLowestPriorityOldestLine() {
        val q = Harness(capacity = 3)
        q.offer("s1")
        q.offer("a1", Priority.ALERT)
        q.offer("s2")
        q.offer("c1", Priority.CRITICAL)
        assertEquals(listOf("c1", "a1", "s2"), q.waiting())
        q.offer("s3")
        assertEquals(listOf("c1", "a1", "s3"), q.waiting())
    }

    @Test
    fun aStoppedAlertGoesBackToTheFrontOfItsPriority() {
        val q = Harness()
        q.offer("alert one", Priority.ALERT)
        q.poll()
        q.offer("alert two", Priority.ALERT)
        q.queue = q.queue.interrupted(requeue = true, heard = false)
        assertEquals(listOf("alert one", "alert two"), q.waiting())
    }

    @Test
    fun aStoppedLineIsNotPutBackBehindANewerOneOfItsCategory() {
        val q = Harness()
        q.offer("v1", Priority.ALERT, category = "alert.x")
        q.poll()
        q.offer("v2", Priority.ALERT, category = "alert.x")
        q.queue = q.queue.interrupted(requeue = true, heard = false)
        assertEquals(listOf("v2"), q.waiting())
    }

    @Test
    fun aLineNobodyHeardMayBeOfferedAgainButASkippedOneStaysQuiet() {
        val unheard = Harness()
        unheard.offer("a", category = "x")
        unheard.poll()
        unheard.queue = unheard.queue.interrupted(requeue = false, heard = false)
        assertEquals(SpeakResult.QUEUED, unheard.offer("a", category = "x"))

        val skipped = Harness()
        skipped.offer("a", category = "x")
        skipped.poll()
        skipped.queue = skipped.queue.interrupted(requeue = false, heard = true)
        assertEquals(SpeakResult.UNCHANGED, skipped.offer("a", category = "x"))
    }

    @Test
    fun clearedDropsEverythingWaiting() {
        val q = Harness()
        q.offer("a")
        q.offer("b")
        q.queue = q.queue.cleared()
        assertEquals(emptyList(), q.waiting())
    }
}
