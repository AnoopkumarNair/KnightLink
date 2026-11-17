package knightlink.platform.ble

import knightlink.core.transport.TransportException
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WriteRetrierTest {
    private fun script(vararg results: WriteAttempt): suspend () -> WriteAttempt {
        val queue = ArrayDeque(results.toList())
        return { queue.removeFirst() }
    }

    @Test
    fun anAcceptedWriteNeedsNoWaiting() =
        runTest {
            WriteRetrier().run(script(WriteAttempt.Accepted))

            assertEquals(0, currentTime)
        }

    @Test
    fun busyIsRetriedWithGrowingPausesUntilAccepted() =
        runTest {
            val retrier = WriteRetrier(maxAttempts = 10, initialBackoffMs = 2, maxBackoffMs = 40)

            retrier.run(script(WriteAttempt.Busy, WriteAttempt.Busy, WriteAttempt.Busy, WriteAttempt.Accepted))

            // Pauses of 2, 4 and 8 ms.
            assertEquals(14, currentTime)
        }

    @Test
    fun backoffDoublesAndStopsAtTheCap() {
        val retrier = WriteRetrier(maxAttempts = 10, initialBackoffMs = 2, maxBackoffMs = 40)

        assertEquals(listOf(2L, 4L, 8L, 16L, 32L, 40L, 40L), (0..6).map(retrier::backoffMs))
        assertEquals(40L, retrier.backoffMs(1000))
    }

    @Test
    fun aStackThatStaysBusyEventuallyFailsInsteadOfHanging() =
        runTest {
            val retrier = WriteRetrier(maxAttempts = 3, initialBackoffMs = 1, maxBackoffMs = 1)
            var attempts = 0

            val error =
                assertFailsWith<TransportException> {
                    retrier.run {
                        attempts++
                        WriteAttempt.Busy
                    }
                }

            assertEquals(3, attempts)
            assertTrue("busy" in error.message.orEmpty(), error.message)
        }

    @Test
    fun aRejectedWriteFailsAtOnceWithTheReason() =
        runTest {
            var attempts = 0

            val error =
                assertFailsWith<TransportException> {
                    WriteRetrier().run {
                        attempts++
                        WriteAttempt.Rejected("status 133")
                    }
                }

            assertEquals(1, attempts)
            assertTrue("status 133" in error.message.orEmpty(), error.message)
        }

    @Test
    fun invalidSettingsAreRejected() {
        assertFailsWith<IllegalArgumentException> { WriteRetrier(maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { WriteRetrier(initialBackoffMs = 0) }
        assertFailsWith<IllegalArgumentException> { WriteRetrier(initialBackoffMs = 50, maxBackoffMs = 10) }
    }
}
