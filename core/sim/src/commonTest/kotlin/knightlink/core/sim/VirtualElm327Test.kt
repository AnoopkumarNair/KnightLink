package knightlink.core.sim

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VirtualElm327Test {
    private fun vehicle(): VirtualVehicle {
        val ecm = VirtualEcu(0x7E0, 0x7E8).apply {
            set("0105", "6B")
            set("010C", "0C7C")
            set("0146", "44")
            set("018B", "7300E300EF00B3")
        }
        val rdcm = VirtualEcu(0x7E4, 0x7EC).apply { set("0105", "6A") }
        return VirtualVehicle(listOf(ecm, rdcm))
    }

    /** Sends commands one at a time and returns each raw reply up to and including the prompt. */
    private suspend fun VirtualElm327.talk(vararg commands: String): List<String> {
        val replies = ArrayList<String>()
        val buffer = StringBuilder()
        for (command in commands) {
            write("$command\r".encodeToByteArray())
        }
        close()
        incoming.toList().forEach { buffer.append(it.decodeToString()) }
        buffer.split('>').dropLast(1).forEach { replies += it }
        return replies
    }

    @Test
    fun defaultsEchoAndUseLinefeedsUntilSwitchedOff() = runTest {
        val replies = VirtualElm327(vehicle()).talk("ATI", "ATE0", "ATL0", "ATI")
        assertEquals("ATI\r\nELM327 v2.3\r\n\r\n", replies[0])
        assertEquals("ATE0\r\nOK\r\n\r\n", replies[1])
        assertEquals("OK\r\r", replies[2])
        assertEquals("ELM327 v2.3\r\r", replies[3])
    }

    @Test
    fun functionalRequestGetsOneReplyPerEcuAndTheSuffixLimitsIt() = runTest {
        val replies = VirtualElm327(vehicle()).talk("ATE0", "ATL0", "ATS0", "ATH1", "0105", "01051")
        assertEquals("7E80341056B\r7EC0341056A\r\r", replies[4])
        assertEquals("7E80341056B\r\r", replies[5])
    }

    @Test
    fun physicalHeaderTargetsOneEcu() = runTest {
        val replies = VirtualElm327(vehicle()).talk("ATE0", "ATL0", "ATH1", "ATSH7E4", "0105", "010C", "ATSH7E0", "010C")
        assertEquals("7EC 03 41 05 6A\r\r", replies[4])
        assertEquals("NO DATA\r\r", replies[5])
        assertEquals("7E8 04 41 0C 0C 7C\r\r", replies[7])
    }

    @Test
    fun longRepliesAreSegmentedLikeTheSrsCapture() = runTest {
        val replies = VirtualElm327(vehicle()).talk("ATE0", "ATL0", "ATH1", "ATSH7E0", "018B")
        assertEquals("7E8 10 09 41 8B 73 00 E3 00\r7E8 21 EF 00 B3\r\r", replies[4])
    }

    @Test
    fun headersOffFormats() = runTest {
        val replies = VirtualElm327(vehicle()).talk("ATE0", "ATL0", "ATSH7E0", "0105", "018B")
        assertEquals("41 05 6B\r\r", replies[3])
        assertEquals("009\r0: 41 8B 73 00 E3 00\r1: EF 00 B3\r\r", replies[4])
    }

    @Test
    fun supportedPidBitmapIsDerivedFromTheData() = runTest {
        val replies = VirtualElm327(vehicle()).talk("ATE0", "ATL0", "ATH1", "ATSH7E0", "0100", "0120", "0140", "0180", "01A0")
        // 0x05 and 0x0C in the first range, plus the "more ranges follow" bit.
        assertEquals("7E8 06 41 00 08 10 00 01\r\r", replies[4])
        assertEquals("7E8 06 41 20 00 00 00 01\r\r", replies[5])
        // 0x46 in the third range, more follow (0x8B).
        assertEquals("7E8 06 41 40 04 00 00 01\r\r", replies[6])
        // 0x8B is the 11th PID of the 0x81.. range; nothing beyond it.
        assertEquals("7E8 06 41 80 00 20 00 00\r\r", replies[7])
        assertEquals("NO DATA\r\r", replies[8])
    }

    @Test
    fun unknownRequestsFollowTheStandard() = runTest {
        val replies = VirtualElm327(vehicle()).talk("ATE0", "ATL0", "ATH1", "ATSH7E0", "22F190", "3E00", "ATXYZ", "HELLO")
        assertEquals("7E8 03 7F 22 31\r\r", replies[4])
        assertEquals("7E8 03 7F 3E 11\r\r", replies[5])
        assertEquals("?\r\r", replies[6])
        assertEquals("?\r\r", replies[7])
    }

    @Test
    fun deliversInSmallChunks() = runTest {
        val adapter = VirtualElm327(vehicle(), VirtualElm327.Config(chunkSize = 4))
        adapter.write("ATI\r".encodeToByteArray())
        val chunks = adapter.incoming.take(3).toList()
        assertTrue(chunks.all { it.size <= 4 })
        assertEquals("ATI\r\nELM327 ", chunks.joinToString("") { it.decodeToString() })
    }
}
