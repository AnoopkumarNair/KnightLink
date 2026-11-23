package knightlink.core.runtime

import knightlink.core.common.SessionEventSink
import knightlink.core.common.toHex
import knightlink.core.protocol.DiagnosticLink
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponse
import knightlink.core.scan.FaultEntry
import knightlink.core.scan.FaultKind
import knightlink.core.scan.FaultReport
import knightlink.core.scan.ReadinessSummary
import knightlink.core.vehicle.EcuReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The ladder on a scripted link: what it compares after a level, what it accepts as an answer, and what it puts back. */
@OptIn(ExperimentalCoroutinesApi::class)
class LearningLadderTest {
    private val log = ArrayList<String>()
    private val events = ArrayList<String>()
    private var lamp = false
    private var answer: (Int, String) -> ElmResponse? = { _, _ -> null }

    private fun hex3(id: Int) = id.toString(16).uppercase().padStart(3, '0')

    private fun response(vararg messages: DiagnosticResponse) = ElmResponse(messages.toList(), emptyList(), emptyList(), emptyList(), emptyList())

    private fun positive(source: Int, service: Int, vararg data: Int) = DiagnosticResponse.Positive(source, service, data.map { it.toByte() }.toByteArray())

    private fun negative(source: Int, service: Int, code: Int) = DiagnosticResponse.Negative(source, service, code)

    /** Codes the units hold, per request id, as the bytes after the service of a reply to 03. */
    private val stored = HashMap<Int, IntArray>()

    private val link = object : DiagnosticLink {
        override suspend fun command(text: String): List<String> {
            log += text
            return if (text == "ATRV") listOf("12.6V") else listOf("OK")
        }

        override suspend fun request(requestId: Int, payload: ByteArray, expectedResponses: Int?): ElmResponse {
            val hex = payload.toHex()
            log += "REQ ${hex3(requestId)} $hex"
            val source = requestId + 8
            answer(requestId, hex)?.let { return it }
            return when {
                hex == "010D" || hex == "010C" -> response(positive(source, 1, payload[1].toInt() and 0xFF, 0, 0))
                hex == "0101" -> response(positive(source, 1, 0x01, if (lamp) 0x81 else 0x00, 0x07, 0x61, 0x00))
                hex == "03" -> response(positive(source, 3, *(stored[requestId] ?: intArrayOf(0))))
                hex == "07" || hex == "0A" -> response(positive(source, payload[0].toInt(), 0))
                hex.startsWith("19") -> response(negative(source, 0x19, 0x12))
                hex.startsWith("22") -> if (requestId in 0x7E0..0x7E7) response(negative(source, 0x22, 0x31)) else response()
                else -> response()
            }
        }
    }

    private fun unit(id: Int) = EcuReport(hex3(id), hex3(id + 8), null, emptyList(), listOf("01"), null)

    private fun readiness(milOn: Boolean?) = ReadinessSummary(milOn, 0, false, emptyList())

    private fun TestScope.ladder(
        units: List<EcuReport>,
        baseline: FaultReport?,
        level: LadderLevel,
        config: LadderConfig = LadderConfig(ranges = listOf(0xF100..0xF10F)),
        skip: () -> Boolean = { false },
    ) = LearningLadder(
        link = link,
        units = units,
        baseline = baseline,
        maxLevel = level,
        config = config,
        events = SessionEventSink { events += it.toString() },
        timeSource = testTimeSource,
        deepScan = { true },
        publish = { },
        skip = skip,
    )

    private fun answeredAll(vararg ids: Int) = ids.flatMap { id ->
        FaultKind.entries.map { FaultReport.answerKey(hex3(id), uds = false, kind = it) }
    }.toSet()

    @Test
    fun theAdapterIsPutBackBeforeTheFaultCodesAreReadAfterALevel() = runTest {
        val baseline = FaultReport(emptyList(), readiness(false), listOf("7E0"), answered = answeredAll(0x7E0))
        val result = ladder(listOf(unit(0x7E0)), baseline, LadderLevel.L2).run()
        assertIs<LadderResult.Complete>(result)
        val wide = log.indexOf("ATCM700")
        val back = log.indexOf("ATCM7F8")
        assertTrue(wide in 0 until back, log.toString())
        val reads = log.withIndex().filter { it.value == "REQ 7E0 03" }.map { it.index }
        assertTrue(reads.size >= 2)
        assertTrue(reads.none { it in wide..back }, "no fault code is read while the adapter listens to every 7xx id: $reads in $wide..$back")
    }

    @Test
    fun aFaultReadThatTheGuardStopsEndsTheLadderInsteadOfCountingAsNoFlag() = runTest {
        var skipped = false
        val baseline = FaultReport(emptyList(), readiness(false), listOf("7E0"), answered = answeredAll(0x7E0))
        val ladder = ladder(listOf(unit(0x7E0)), baseline, LadderLevel.L1, skip = { skipped })
        // The user taps Skip while the level is over and the fault codes are about to be read.
        answer = { _, hex ->
            if (hex == "22F195") skipped = true
            null
        }
        val result = ladder.run()
        assertIs<LadderResult.Stopped>(result)
        assertTrue("skipped" in result.reason, result.reason)
    }

    @Test
    fun aUnitThatMissedTheFirstReadingIsNotACarWithNewFaultCodes() = runTest {
        // The first reading had no answer from 7E1 (and so no lamp); now it answers with a stored code and the lamp on.
        lamp = true
        stored[0x7E1] = intArrayOf(1, 0x01, 0x33)
        val baseline = FaultReport(emptyList(), null, listOf("7E0", "7E1"), answered = answeredAll(0x7E0))
        val result = ladder(listOf(unit(0x7E0), unit(0x7E1)), baseline, LadderLevel.L1).run()
        assertIs<LadderResult.Complete>(result)
    }

    @Test
    fun aNewCodeFromAUnitThatAnsweredBothTimesIsFlaggedAndSoIsTheLampWhenItWasReadOff() = runTest {
        stored[0x7E0] = intArrayOf(1, 0x01, 0x33)
        val first = FaultReport(emptyList(), readiness(false), listOf("7E0"), answered = answeredAll(0x7E0))
        val flagged = ladder(listOf(unit(0x7E0)), first, LadderLevel.L1).run()
        assertTrue("P0133" in assertIs<LadderResult.Flagged>(flagged).reason)

        stored.clear()
        lamp = true
        log.clear()
        val lamps = ladder(listOf(unit(0x7E0)), first, LadderLevel.L1).run()
        assertTrue("lamp" in assertIs<LadderResult.Flagged>(lamps).reason)
        // The same code as before is not new.
        lamp = false
        stored[0x7E0] = intArrayOf(1, 0x01, 0x33)
        val known = FaultReport(listOf(FaultEntry("P0133", FaultKind.CONFIRMED, "7E0")), readiness(false), listOf("7E0"), answered = answeredAll(0x7E0))
        assertIs<LadderResult.Complete>(ladder(listOf(unit(0x7E0)), known, LadderLevel.L1).run())
    }

    @Test
    fun discoveryTakesAUnitOnlyForTheAnswerToItsOwnRequestAndNeverAsksAnIdAKnownUnitAnswersFrom() = runTest {
        val baseline = FaultReport(emptyList(), readiness(false), listOf("7E0"), answered = answeredAll(0x7E0))
        answer = { id, hex ->
            when {
                // 720 answers, but with the reply to another identifier (a late reply): dropped.
                id == 0x720 && hex.startsWith("22F190") -> response(positive(0x728, 0x22, 0xF1, 0x91, 0x00))
                // 7A0 answers properly, from 7A8.
                id == 0x7A0 && hex.startsWith("22F190") -> response(positive(0x7A8, 0x22, 0xF1, 0x90, 0x41, 0x42))
                else -> null
            }
        }
        assertIs<LadderResult.Complete>(ladder(listOf(unit(0x7E0)), baseline, LadderLevel.L2).run())
        assertTrue(events.any { "720" in it && "dropped" in it }, events.toString())
        assertTrue(events.any { "a unit answers 7A0 from 7A8" in it }, events.toString())
        assertTrue(log.none { it.startsWith("REQ 7A8") }, "nothing is sent to an id a known unit answers from")
    }

    @Test
    fun aMismatchedAnswerInAnIdentifierReadIsNotCounted() = runTest {
        val baseline = FaultReport(emptyList(), readiness(false), listOf("7E0"), answered = answeredAll(0x7E0))
        answer = { _, hex -> if (hex.startsWith("22F187")) response(positive(0x7E8, 0x22, 0xF1, 0x88, 0x01)) else null }
        ladder(listOf(unit(0x7E0)), baseline, LadderLevel.L1).run()
        assertTrue(events.none { "F187 ->" in it }, events.toString())
    }

    @Test
    fun aUnitThatSaysServiceNotSupportedInThisSessionIsNotSweptFurther() = runTest {
        val baseline = FaultReport(emptyList(), readiness(false), listOf("7E0"), answered = answeredAll(0x7E0))
        answer = { id, hex -> if (id == 0x7E0 && hex.startsWith("22")) response(negative(id + 8, 0x22, 0x7F)) else null }
        assertIs<LadderResult.Complete>(ladder(listOf(unit(0x7E0)), baseline, LadderLevel.L3).run())
        assertTrue(log.count { it.startsWith("REQ 7E0 22") } <= 2, log.filter { it.startsWith("REQ 7E0 22") }.toString())
    }

    @Test
    fun theParkedTimeBoxIsCheckedBetweenRequestsNotOnlyBetweenUnits() = runTest {
        val baseline = FaultReport(emptyList(), readiness(false), listOf("7E0"), answered = answeredAll(0x7E0))
        val config = LadderConfig(ranges = listOf(0xF100..0xF1FF), totalBox = kotlin.time.Duration.parse("8s"))
        val mark = testTimeSource.markNow()
        val result = ladder(listOf(unit(0x7E0)), baseline, LadderLevel.L3, config = config).run()
        assertTrue("time box" in assertIs<LadderResult.Stopped>(result).reason, result.toString())
        assertTrue(mark.elapsedNow() < kotlin.time.Duration.parse("10s"), "${mark.elapsedNow()}")
    }
}
