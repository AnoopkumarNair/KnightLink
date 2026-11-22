package knightlink.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PassiveCaptureTest {
    @Test
    fun aFrameLineIsAThreeDigitIdentifierAndUpToEightDataBytesWithOrWithoutSpaces() {
        assertEquals(0x1A0, MonitorLines.frameId("1A0 12 34 56 78 00 00 00 00"))
        assertEquals(0x1A0, MonitorLines.frameId("1A01234567800000000"))
        assertEquals(0x7E8, MonitorLines.frameId("7e8 03 41 0c"))
        assertEquals(0x123, MonitorLines.frameId("123"))
    }

    @Test
    fun statusWordsNoiseAndHalfLinesAreNotFrames() {
        for (line in listOf("SEARCHING...", "STOPPED", "BUFFER FULL", "OK", "?", "", "1A", "1A0 12 3", "1A0 00 00 00 00 00 00 00 00 00", "1A0 ZZ")) {
            assertNull(MonitorLines.frameId(line), line)
        }
    }

    @Test
    fun aSliceIsSummarisedWithTheBusiestIdentifiersFirstAndBufferFullCounted() {
        val lines = listOf("SEARCHING...") + List(3) { "1B4 01" } + List(5) { "1A0 12 34" } + listOf("BUFFER FULL", "garbage", "STOPPED") + List(3) { "0C9 00" }
        val summary = MonitorLines.summarise("group 100-1FF", 3.0, lines)
        assertEquals(11, summary.frames)
        assertEquals(1, summary.bufferFull)
        assertEquals("passive: group 100-1FF 3.0 s, 11 frames, 3 ids (1A0:5 0C9:3 1B4:3), buffer-full 1", summary.note())
    }

    @Test
    fun aNoteListsAtMostTheRequestedNumberOfIdentifiersAndSaysHowManyMoreThereWere() {
        val ids = (0x100..0x114).associateWith { 1 }
        val note = SliceSummary("all ids", 2.96, 21, ids, 0).note(top = 12)
        assertEquals(true, note.endsWith("+9 more), buffer-full 0"), note)
        assertEquals(true, note.contains("21 ids (100:1 101:1"), note)
        assertEquals(true, note.contains("3.0 s"), note)
    }

    @Test
    fun anIdentifierGroupNamesItsRange() {
        assertEquals("100-1FF", IdGroup(0x100, 0x700).label)
        assertEquals(8, IdGroup.DEFAULT.size)
        assertEquals("700-7FF", IdGroup.DEFAULT.last().label)
    }
}
