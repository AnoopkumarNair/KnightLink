package knightlink.core.protocol

import knightlink.core.common.toHex

/** One CAN frame as reported by the adapter with headers on. */
public class CanFrame(
    public val id: Int,
    public val extended: Boolean,
    public val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is CanFrame && id == other.id && extended == other.extended && data.contentEquals(other.data)

    override fun hashCode(): Int = (id * 31 + extended.hashCode()) * 31 + data.contentHashCode()

    override fun toString(): String = "CanFrame(${id.toString(16).uppercase()} ${data.toHex()})"
}
