package knightlink.core.runtime

import knightlink.core.adapter.ReadOnlyViolationException
import knightlink.core.common.toHex
import knightlink.core.protocol.DiagnosticLink
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse
import knightlink.core.protocol.ElmStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LadderGuardTest {
    private val sent = ArrayList<String>()
    private var speed = 0
    private var rpm = 0
    private var volts = "12.6V"
    private var answer: (Int, String) -> ElmResponse = { _, _ -> response() }

    private fun response(vararg messages: DiagnosticResponse, statuses: List<ElmStatus> = emptyList()) =
        ElmResponse(messages.toList(), statuses, emptyList(), emptyList(), emptyList())

    private fun positive(service: Int, vararg data: Int) = DiagnosticResponse.Positive(0x7E8, service, data.map { it.toByte() }.toByteArray())

    private val link = object : DiagnosticLink {
        override suspend fun command(text: String): List<String> = if (text == "ATRV") listOf(volts) else emptyList()

        override suspend fun request(requestId: Int, payload: ByteArray, expectedResponses: Int?): ElmResponse {
            val hex = payload.toHex()
            sent += hex
            return when (hex) {
                "010D" -> response(positive(1, 0x0D, speed))
                "010C" -> response(positive(1, 0x0C, rpm shr 6, (rpm shl 2) and 0xFF))
                else -> answer(requestId, hex)
            }
        }
    }

    private fun TestScope.guard(config: LadderGuard.Config = LadderGuard.Config()) = LadderGuard(link, testTimeSource, config)

    @Test
    fun theHardLinesAreNeverSentWhateverTheCallerAsks() = runTest {
        val guard = guard()
        val never = listOf(
            "2E F1 90 00", "27 01", "31 01 FF 00", "10 03", "10 02", "10 01", "11 01",
            "14 FF FF FF", "28 00 01", "85 02", "3E 00", "2F 01 02 03", "04", "08 01",
        )
        for (payload in never) {
            assertFailsWith<ReadOnlyViolationException>(payload) {
                guard.request(0x7E0, payload.filterNot { it == ' ' }.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), 1)
            }
        }
        for (id in listOf(0x6FF, 0x800, 0x100, 0x18DB33F1)) {
            assertFailsWith<ReadOnlyViolationException>("id $id") {
                guard.request(id, byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()), 1)
            }
        }
        assertFailsWith<ReadOnlyViolationException> { guard.request(0x7E0, byteArrayOf(0x19, 0x04, 0xFF.toByte()), 1) }
        assertTrue(sent.isEmpty(), sent.toString())
        // What it does send: the reading services.
        guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()), 1)
        guard.request(0x726, byteArrayOf(0x19, 0x02, 0xFF.toByte()), 1)
        assertEquals(listOf("22F190", "1902FF"), sent)
    }

    @Test
    fun threeSecurityOrConditionRefusalsInARowStopTheUnitButOutOfRangeDoesNot() = runTest {
        val guard = guard()
        answer = { id, _ -> response(DiagnosticResponse.Negative(id + 8, 0x22, 0x31)) }
        repeat(30) { guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), it.toByte()), 1) }
        answer = { id, _ -> response(DiagnosticResponse.Negative(id + 8, 0x22, 0x33)) }
        guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), 0x00), 1)
        guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), 0x01), 1)
        val aborted = assertFailsWith<LevelAborted> { guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), 0x02), 1) }
        assertEquals(AbortScope.UNIT, aborted.scope)
        assertEquals(0x7E0, aborted.unit)
    }

    @Test
    fun anAnswerInBetweenStartsTheCountAgain() = runTest {
        val guard = guard()
        val refuse = { id: Int -> response(DiagnosticResponse.Negative(id + 8, 0x22, 0x22)) }
        answer = { id, hex -> if (hex.endsWith("FF")) response(positive(0x22, 0xF1, 0xFF)) else refuse(id) }
        repeat(2) { guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), it.toByte()), 1) }
        guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), 0xFF.toByte()), 1)
        repeat(2) { guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), it.toByte()), 1) }
    }

    @Test
    fun refusalsFromMoreThanHalfTheUnitsStopTheLevel() = runTest {
        val guard = guard()
        answer = { id, _ -> response(DiagnosticResponse.Negative(id + 8, 0x22, 0x33)) }
        guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), 0x00), 1)
        val aborted = assertFailsWith<LevelAborted> { guard.request(0x726, byteArrayOf(0x22, 0xF1.toByte(), 0x00), 1) }
        assertEquals(AbortScope.LEVEL, aborted.scope)
    }

    @Test
    fun twoBusErrorsInALevelStopItAndAskForANewProbe() = runTest {
        val guard = guard()
        answer = { _, _ -> response(statuses = listOf(ElmStatus.CAN_ERROR)) }
        guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), 0x00), 1)
        val aborted = assertFailsWith<LevelAborted> { guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), 0x01), 1) }
        assertEquals(AbortScope.LEVEL, aborted.scope)
        assertTrue(aborted.reprobe)
        // A new level counts afresh.
        guard.beginLevel()
        answer = { _, _ -> response(statuses = listOf(ElmStatus.LV_RESET)) }
        guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), 0x02), 1)
    }

    @Test
    fun speedAboveZeroTheEngineStartingOrALowBatteryWithTheEngineOffEndEverything() = runTest {
        val config = LadderGuard.Config(checkEvery = 2)
        run {
            val guard = guard(config)
            assertNull(guard.begin())
            guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), 0x00), 1)
            speed = 12
            val aborted = assertFailsWith<LevelAborted> { repeat(4) { guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), it.toByte()), 1) } }
            assertEquals(AbortScope.LADDER, aborted.scope)
            assertTrue("moves" in aborted.reason)
        }
        speed = 0
        run {
            val guard = guard(config)
            assertNull(guard.begin())
            rpm = 800
            val aborted = assertFailsWith<LevelAborted> { repeat(4) { guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), it.toByte()), 1) } }
            assertEquals(AbortScope.LADDER, aborted.scope)
            assertTrue("engine" in aborted.reason)
        }
        rpm = 0
        run {
            val guard = guard(config)
            assertNull(guard.begin())
            volts = "11.3V"
            val aborted = assertFailsWith<LevelAborted> { repeat(4) { guard.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), it.toByte()), 1) } }
            assertTrue("battery" in aborted.reason)
        }
        // With the engine running a reading under 12 V is the alternator's business, not a reason to stop.
        rpm = 800
        volts = "11.9V"
        val running = guard(config)
        assertNull(running.begin())
        repeat(6) { running.request(0x7E0, byteArrayOf(0x22, 0xF1.toByte(), it.toByte()), 1) }
    }

    @Test
    fun theLadderDoesNotStartWhenTheCarMovesOrTheBatteryIsLow() = runTest {
        speed = 5
        assertTrue(guard().begin()!!.contains("moves"))
        speed = 0
        volts = "11.0V"
        assertTrue(guard().begin()!!.contains("battery"))
    }
}
