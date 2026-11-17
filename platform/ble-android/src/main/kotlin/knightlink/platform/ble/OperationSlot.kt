package knightlink.platform.ble

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference

/** The kinds of GATT operation that finish with a callback. Android allows exactly one of them in flight per connection. */
internal enum class OpKind { MTU, DISCOVER, DESCRIPTOR, WRITE }

/** What a GATT callback reported for an operation: the status code and, where it has one, a value (the MTU). */
internal data class OpResult(
    val status: Int,
    val value: Int = 0,
)

/**
 * The single "operation in flight" slot of one connection. Coroutines [begin] an operation, start it on the Bluetooth
 * stack and await the [Ticket]; the stack's callback, on whatever binder thread it arrives, [complete]s it.
 *
 * Callbacks must never block, so this is lock-free: an [AtomicReference] for the slot and a [CompletableDeferred] for
 * the hand-over. A callback that arrives for an operation nobody waits for (a late answer after a timeout, a
 * peripheral-initiated MTU exchange) is dropped, and because the kind has to match it can never be mistaken for the
 * answer to a different operation.
 */
internal class OperationSlot {
    class Ticket internal constructor(
        val kind: OpKind,
    ) {
        internal val result = CompletableDeferred<OpResult>()

        /** Waits for the callback. Throws the cause given to [OperationSlot.fail] if the connection ended first. */
        suspend fun await(): OpResult = result.await()
    }

    private val current = AtomicReference<Ticket?>(null)

    @Volatile private var terminal: Throwable? = null

    /** Claims the slot for [kind]. Throws [IllegalStateException] if an operation is already in flight (a programming error). */
    fun begin(kind: OpKind): Ticket {
        val ticket = Ticket(kind)
        check(current.compareAndSet(null, ticket)) { "A GATT operation is already in flight" }
        // A connection that has already failed must not leave a new operation waiting for a callback that will never come.
        // fail() publishes the cause before it drains the slot, and this reads it after claiming the slot, so one of the
        // two always sees the other.
        terminal?.let { ticket.result.completeExceptionally(it) }
        return ticket
    }

    /** Delivers a callback. Returns true if it completed the operation in flight; false if it was stale or of another kind. */
    fun complete(
        kind: OpKind,
        status: Int,
        value: Int = 0,
    ): Boolean {
        val ticket = current.get() ?: return false
        return ticket.kind == kind && ticket.result.complete(OpResult(status, value))
    }

    /** Frees the slot after the operation finished, timed out or was cancelled. A no-op if it was already freed. */
    fun end(ticket: Ticket) {
        current.compareAndSet(ticket, null)
    }

    /** The connection is over: fails the operation in flight and every one begun later with [cause]. */
    fun fail(cause: Throwable) {
        terminal = cause
        current.getAndSet(null)?.result?.completeExceptionally(cause)
    }
}
