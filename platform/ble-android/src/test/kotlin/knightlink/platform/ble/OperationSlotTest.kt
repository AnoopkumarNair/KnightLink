package knightlink.platform.ble

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationSlotTest {
    @Test
    fun aCallbackOfTheMatchingKindCompletesTheOperation() =
        runTest {
            val slot = OperationSlot()
            val ticket = slot.begin(OpKind.MTU)

            assertTrue(slot.complete(OpKind.MTU, status = 0, value = 247))

            assertEquals(OpResult(0, 247), ticket.await())
        }

    @Test
    fun aCallbackArrivingBeforeTheStartCallReturnedStillCompletes() =
        runTest {
            val slot = OperationSlot()
            // begin() happens before the framework call, so a callback delivered immediately finds the ticket waiting.
            val ticket = slot.begin(OpKind.DISCOVER)
            slot.complete(OpKind.DISCOVER, status = 0)

            assertEquals(0, ticket.await().status)
        }

    @Test
    fun aCallbackOfAnotherKindIsIgnored() =
        runTest {
            val slot = OperationSlot()
            val ticket = slot.begin(OpKind.DISCOVER)

            // A late answer to an MTU request that timed out must not complete service discovery.
            assertFalse(slot.complete(OpKind.MTU, status = 0, value = 100))
            assertFalse(ticket.result.isCompleted)
            assertTrue(slot.complete(OpKind.DISCOVER, status = 0))
        }

    @Test
    fun aCallbackWithNothingInFlightIsDropped() {
        assertFalse(OperationSlot().complete(OpKind.WRITE, status = 0))
    }

    @Test
    fun aSecondCallbackForTheSameOperationIsDropped() {
        val slot = OperationSlot()
        slot.begin(OpKind.WRITE)

        assertTrue(slot.complete(OpKind.WRITE, status = 0))
        assertFalse(slot.complete(OpKind.WRITE, status = 133))
    }

    @Test
    fun onlyOneOperationMayBeInFlight() {
        val slot = OperationSlot()
        slot.begin(OpKind.MTU)

        assertFailsWith<IllegalStateException> { slot.begin(OpKind.DISCOVER) }
    }

    @Test
    fun endingAnOperationFreesTheSlot() {
        val slot = OperationSlot()
        val first = slot.begin(OpKind.MTU)

        slot.end(first)

        slot.begin(OpKind.DISCOVER)
    }

    @Test
    fun endingAnOldTicketDoesNotFreeANewerOperation() {
        val slot = OperationSlot()
        val first = slot.begin(OpKind.MTU)
        slot.end(first)
        slot.begin(OpKind.DISCOVER)

        slot.end(first)

        assertFailsWith<IllegalStateException> { slot.begin(OpKind.WRITE) }
    }

    @Test
    fun failingTheSlotFailsTheOperationInFlight() =
        runTest {
            val slot = OperationSlot()
            val ticket = slot.begin(OpKind.WRITE)

            slot.fail(IllegalStateException("link lost"))

            assertEquals("link lost", assertFailsWith<IllegalStateException> { ticket.await() }.message)
        }

    @Test
    fun anOperationBegunAfterTheFailureFailsImmediatelyInsteadOfWaitingForever() =
        runTest {
            val slot = OperationSlot()
            slot.fail(IllegalStateException("link lost"))

            val ticket = slot.begin(OpKind.DESCRIPTOR)

            assertFailsWith<IllegalStateException> { ticket.await() }
        }
}
