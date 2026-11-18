package knightlink.platform.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleAdvertisementListenerTest {
    @Test
    fun aSixteenBitServiceUuidIsWrittenOnTheBluetoothBaseUuid() {
        assertEquals("0000ABCD-0000-1000-8000-00805F9B34FB", AdvertisementMatch(true, 0xABCD, ByteArray(0)).serviceUuid())
        assertEquals("00000012-0000-1000-8000-00805F9B34FB", AdvertisementMatch(true, 0x12, ByteArray(0)).serviceUuid())
    }

    @Test
    fun dataIsAcceptedOnlyWithTheWholePrefix() {
        val match = AdvertisementMatch(false, 0x1234, byteArrayOf(0x02, 0x15))
        assertTrue(match.accepts(byteArrayOf(0x02, 0x15, 0x7F)))
        assertTrue(match.accepts(byteArrayOf(0x02, 0x15)))
        assertFalse(match.accepts(byteArrayOf(0x02, 0x16, 0x7F)))
        assertFalse(match.accepts(byteArrayOf(0x02)))
        assertTrue(AdvertisementMatch(true, 1, ByteArray(0)).accepts(ByteArray(0)))
    }

    @Test
    fun aKeyBeyondSixteenBitsIsRefused() {
        assertFailsWith<IllegalArgumentException> { AdvertisementMatch(false, 0x10000, ByteArray(0)) }
    }
}
