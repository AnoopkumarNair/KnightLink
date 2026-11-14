package knightlink.core.common

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HexTest {
    @Test
    fun roundTrips() {
        val bytes = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        assertEquals("007F80FF", bytes.toHex())
        assertContentEquals(bytes, "00 7f 80 FF".hexToBytes())
    }

    @Test
    fun rejectsBadInput() {
        assertFailsWith<IllegalArgumentException> { "ABC".hexToBytes() }
        assertFailsWith<IllegalArgumentException> { "ZZ".hexToBytes() }
    }
}
