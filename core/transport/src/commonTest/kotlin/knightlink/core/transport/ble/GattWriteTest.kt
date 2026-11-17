package knightlink.core.transport.ble

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class GattWriteTest {
    private fun bytes(count: Int) = ByteArray(count) { it.toByte() }

    @Test
    fun defaultMtuGivesTwentyBytePayload() {
        assertEquals(20, payloadForMtu(DEFAULT_ATT_MTU))
        assertEquals(244, payloadForMtu(247))
    }

    @Test
    fun payloadIsCappedAtTheMaximumAttributeLength() {
        assertEquals(MAX_ATT_VALUE_BYTES, payloadForMtu(517))
        assertEquals(MAX_ATT_VALUE_BYTES, payloadForMtu(1000))
    }

    @Test
    fun anMtuBelowTheMinimumIsRejected() {
        assertFailsWith<IllegalArgumentException> { payloadForMtu(22) }
    }

    @Test
    fun shortInputIsOneChunk() {
        val chunks = chunkForWrite("ATZ\r".encodeToByteArray())

        assertEquals(1, chunks.size)
        assertContentEquals("ATZ\r".encodeToByteArray(), chunks[0])
    }

    @Test
    fun longInputIsSplitInOrderAtThePayloadSize() {
        val input = bytes(45)

        val chunks = chunkForWrite(input, 20)

        assertEquals(listOf(20, 20, 5), chunks.map { it.size })
        assertContentEquals(input, chunks.reduce { a, b -> a + b })
    }

    @Test
    fun inputExactlyTheSizeOfAChunkIsNotSplit() {
        assertEquals(listOf(20), chunkForWrite(bytes(20), 20).map { it.size })
        assertEquals(listOf(20, 1), chunkForWrite(bytes(21), 20).map { it.size })
    }

    @Test
    fun emptyInputHasNothingToSend() {
        assertTrue(chunkForWrite(ByteArray(0)).isEmpty())
    }

    @Test
    fun chunksAreCopiesSoLaterChangesToTheInputDoNotLeakIn() {
        val input = bytes(4)
        val chunk = chunkForWrite(input, 20).single()

        input[0] = 99

        assertNotSame(input, chunk)
        assertEquals(0, chunk[0])
    }

    @Test
    fun invalidPayloadSizesAreRejected() {
        assertFailsWith<IllegalArgumentException> { chunkForWrite(bytes(3), 0) }
        assertFailsWith<IllegalArgumentException> { chunkForWrite(bytes(3), -1) }
        assertFailsWith<IllegalArgumentException> { chunkForWrite(bytes(3), MAX_ATT_VALUE_BYTES + 1) }
        assertFailsWith<IllegalArgumentException> { BluetoothUuid.fromShort(0x10000) }
    }
}
