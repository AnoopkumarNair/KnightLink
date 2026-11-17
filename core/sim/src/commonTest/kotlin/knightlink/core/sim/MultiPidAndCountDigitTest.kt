package knightlink.core.sim

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a real ECU and a real ELM327 do with several PIDs in one request, and with the response-count digit on a long reply. */
class MultiPidAndCountDigitTest {
    private fun ecm() = VirtualEcu(0x7E0, 0x7E8).apply {
        set("0104", "40")
        set("010C", "0C7C")
        set("010D", "28")
        set("0178", "0F06F20589039702C9") // 9 data bytes: a multi-frame reply
    }

    private suspend fun VirtualElm327.talk(vararg commands: String): List<String> {
        for (command in commands) write("$command\r".encodeToByteArray())
        close()
        val buffer = StringBuilder()
        incoming.toList().forEach { buffer.append(it.decodeToString()) }
        return buffer.split('>').dropLast(1)
    }

    private fun hex(bytes: ByteArray?) = bytes?.joinToString("") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }

    @Test
    fun anEcuAnswersTheSupportedPidsOfAMultiPidRequestInTheOrderAsked() {
        val ecu = ecm()
        assertEquals("410D2804400C0C7C", hex(ecu.respond(byteArrayOf(1, 0x0D, 0x04, 0x0C))))
        assertEquals("410C0C7C0D28", hex(ecu.respond(byteArrayOf(1, 0x0C, 0x0D))))
    }

    @Test
    fun unsupportedPidsAreLeftOutAndAnEcuWithNoneStaysSilent() {
        val ecu = ecm()
        assertEquals("410C0C7C0D28", hex(ecu.respond(byteArrayOf(1, 0x0C, 0x5C, 0x0D))))
        assertNull(ecu.respond(byteArrayOf(1, 0x5C, 0x5D)))
    }

    @Test
    fun aSupportBitmapCanBePartOfARequestToo() {
        val ecu = ecm()
        // 01 00 reports 04, 0C, 0D, 78 (and the continuation bit for the next range, because 0178 is above 0x20).
        val reply = hex(ecu.respond(byteArrayOf(1, 0x00, 0x04)))!!
        assertTrue(reply.startsWith("4100"), reply)
        assertTrue(reply.endsWith("0440"), reply)
    }

    @Test
    fun aMultiPidReplyIsSegmentedLikeAnyLongReply() = runTest {
        val replies = VirtualElm327(VirtualVehicle(listOf(ecm()))).talk("ATE0", "ATL0", "ATS0", "ATH1", "ATSH7E0", "010C0D04780D")
        // 41 + 0C 0C7C (3) + 0D 28 (2) + 04 40 (2) + 78 + 9 bytes (10) + 0D 28 (2) = 20 bytes, so a first frame and two consecutive frames.
        val lines = replies.last().split('\r').filter { it.isNotBlank() }
        assertEquals(3, lines.size, lines.toString())
        assertTrue(lines[0].startsWith("7E8101441"), lines[0])
        assertTrue(lines[1].startsWith("7E821"), lines[1])
        assertTrue(lines[2].startsWith("7E822"), lines[2])
    }

    @Test
    fun withACountOfOneALongReplyIsCutToItsFirstFrameAndNoFlowControlIsSent() = runTest {
        val replies = VirtualElm327(VirtualVehicle(listOf(ecm()))).talk("ATE0", "ATL0", "ATS0", "ATH1", "ATSH7E0", "01781", "0178")
        val counted = replies[5].split('\r').filter { it.isNotBlank() }
        val uncounted = replies[6].split('\r').filter { it.isNotBlank() }
        // What the 2026-11-17 recording shows: 7E8100B4178... and nothing after it.
        assertEquals(listOf("7E8100B41780F06F205"), counted)
        assertEquals(2, uncounted.size)
        assertTrue(uncounted[1].startsWith("7E821"))
    }

    @Test
    fun withACountOfOneAShortReplyIsComplete() = runTest {
        val replies = VirtualElm327(VirtualVehicle(listOf(ecm()))).talk("ATE0", "ATL0", "ATS0", "ATH1", "ATSH7E0", "010C1")
        assertEquals("7E804410C0C7C\r\r", replies.last())
    }

    @Test
    fun withHeadersOffTheCutReplyShowsTheLengthAndTheFirstRowOnly() = runTest {
        val replies = VirtualElm327(VirtualVehicle(listOf(ecm()))).talk("ATE0", "ATL0", "ATS0", "ATH0", "ATSH7E0", "01781")
        val lines = replies.last().split('\r').filter { it.isNotBlank() }
        assertEquals(listOf("00B", "0:41780F06F205"), lines)
    }

    @Test
    fun anEcuThatDoesNotTakeMultiPidRequestsStaysSilentOnThemButAnswersSingleOnes() {
        val ecu = ecm().apply { multiPid = false }
        assertNull(ecu.respond(byteArrayOf(1, 0x0C, 0x0D)))
        assertEquals("410C0C7C", hex(ecu.respond(byteArrayOf(1, 0x0C))))
    }
}
