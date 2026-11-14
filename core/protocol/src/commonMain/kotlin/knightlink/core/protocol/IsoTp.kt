package knightlink.core.protocol

/** A complete ISO 15765-2 (ISO-TP) message from one ECU. */
public class IsoTpMessage(public val sourceId: Int, public val payload: ByteArray)

public sealed interface IsoTpResult {
    public data object Incomplete : IsoTpResult
    public class Complete(public val message: IsoTpMessage) : IsoTpResult
    public data class Error(val sourceId: Int, val reason: String) : IsoTpResult
}

/**
 * Reassembles single- and multi-frame ISO-TP messages. Keeps one in-progress message per source
 * identifier, so interleaved replies from several ECUs are handled. The adapter sends flow control
 * itself; flow-control frames seen here are ignored.
 */
public class IsoTpReassembler {
    private class Pending(val buffer: ByteArray) {
        var filled = 0
        var nextSequence = 1
    }

    private val pending = HashMap<Int, Pending>()

    public fun accept(frame: CanFrame): IsoTpResult {
        val data = frame.data
        if (data.isEmpty()) return IsoTpResult.Error(frame.id, "empty frame")
        val pci = data[0].toInt() and 0xFF
        return when (pci ushr 4) {
            0 -> single(frame, pci and 0x0F)
            1 -> first(frame, pci and 0x0F)
            2 -> consecutive(frame, pci and 0x0F)
            3 -> IsoTpResult.Incomplete
            else -> IsoTpResult.Error(frame.id, "unknown PCI type ${pci ushr 4}")
        }
    }

    public fun reset() {
        pending.clear()
    }

    private fun single(frame: CanFrame, length: Int): IsoTpResult {
        pending.remove(frame.id)
        if (length == 0 || frame.data.size < 1 + length) {
            return IsoTpResult.Error(frame.id, "single frame declares $length bytes, has ${frame.data.size - 1}")
        }
        return IsoTpResult.Complete(IsoTpMessage(frame.id, frame.data.copyOfRange(1, 1 + length)))
    }

    private fun first(frame: CanFrame, lengthHigh: Int): IsoTpResult {
        if (frame.data.size < 2) return IsoTpResult.Error(frame.id, "truncated first frame")
        val total = (lengthHigh shl 8) or (frame.data[1].toInt() and 0xFF)
        if (total == 0) return IsoTpResult.Error(frame.id, "first frame declares zero length")
        val state = Pending(ByteArray(total))
        val take = minOf(total, frame.data.size - 2)
        frame.data.copyInto(state.buffer, 0, 2, 2 + take)
        state.filled = take
        return finishOrStore(frame.id, state)
    }

    private fun consecutive(frame: CanFrame, sequence: Int): IsoTpResult {
        val state = pending[frame.id] ?: return IsoTpResult.Error(frame.id, "consecutive frame without first frame")
        if (sequence != state.nextSequence) {
            pending.remove(frame.id)
            return IsoTpResult.Error(frame.id, "expected sequence ${state.nextSequence}, got $sequence")
        }
        val take = minOf(state.buffer.size - state.filled, frame.data.size - 1)
        frame.data.copyInto(state.buffer, state.filled, 1, 1 + take)
        state.filled += take
        state.nextSequence = (sequence + 1) and 0x0F
        return finishOrStore(frame.id, state)
    }

    private fun finishOrStore(id: Int, state: Pending): IsoTpResult =
        if (state.filled == state.buffer.size) {
            pending.remove(id)
            IsoTpResult.Complete(IsoTpMessage(id, state.buffer))
        } else {
            pending[id] = state
            IsoTpResult.Incomplete
        }
}
