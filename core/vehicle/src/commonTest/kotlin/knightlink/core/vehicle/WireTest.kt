package knightlink.core.vehicle

import knightlink.core.common.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class WireTest {
    @Test
    fun mostSignificantBitOfTheFirstByteIsTheFirstPidOfTheRange() {
        val range = decodeSupportRange(0x00, "80000000".hexToBytes())
        assertEquals(setOf(0x01), range.pids)
        assertEquals(false, range.hasNext)
    }

    @Test
    fun decodesTheTextbookBitmapAndItsNextRangeFlag() {
        // BE 1F A8 13 is the example used throughout the J1979 literature.
        val range = decodeSupportRange(0x00, "BE1FA813".hexToBytes())
        assertEquals(
            setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F),
            range.pids,
        )
        assertEquals(true, range.hasNext)
    }

    @Test
    fun rangePidsAreNeverReportedAsSupported() {
        val range = decodeSupportRange(0x20, "00000001".hexToBytes())
        assertEquals(emptySet(), range.pids)
        assertEquals(true, range.hasNext)
    }

    @Test
    fun pidsAreOffsetByTheRangeBase() {
        val range = decodeSupportRange(0x80, "00200000".hexToBytes())
        assertEquals(setOf(0x8B), range.pids)
    }

    @Test
    fun lastPidBeforeTheNextRangeIsBitThirtyOne() {
        assertEquals(setOf(0x3F), decodeSupportRange(0x20, "00000002".hexToBytes()).pids)
    }

    @Test
    fun shortBitmapIsRejected() {
        assertFailsWith<IllegalArgumentException> { decodeSupportRange(0, "BE1F".hexToBytes()) }
    }

    @Test
    fun medianOfAnOddNumberOfSamplesIsTheMiddleOne() {
        assertEquals(45.milliseconds, median(listOf(90.milliseconds, 45.milliseconds, 40.milliseconds)))
    }

    @Test
    fun medianOfAnEvenNumberOfSamplesIsTheMeanOfTheMiddleTwo() {
        assertEquals(50.milliseconds, median(listOf(70.milliseconds, 30.milliseconds, 40.milliseconds, 60.milliseconds)))
    }

    @Test
    fun medianNeedsSamples() {
        assertFailsWith<IllegalArgumentException> { median(emptyList()) }
    }

    @Test
    fun printableTextDropsNulsAndControlBytesAndTrims() {
        val bytes = "41420043 0D 20 00".hexToBytes()
        assertEquals("ABC", printableText(bytes, from = 0))
        assertEquals(null, printableText("0000 20 00".hexToBytes(), from = 0))
        assertEquals("BC", printableText(bytes, from = 1))
        assertEquals("AB", printableText(bytes, from = 0, to = 3))
    }
}
