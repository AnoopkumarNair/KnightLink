package knightlink.core.vehicle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilityReportTest {
    private val vin = "SIMTESTCAR0000001"

    private fun report(vararg ecus: EcuReport, warnings: List<String> = emptyList()) = CapabilityReport(
        adapter = AdapterInfo("ELM327 v2.3", "OBDII to RS232 Interpreter", "14.1V", 14.1, "A6"),
        timing = Timing(probeMs = 812.5, benchmarkSamples = 10, medianRoundTripMs = 45.0, estimatedRequestsPerSecond = 1000.0 / 45.0),
        ecus = ecus.toList(),
        derived = DerivedFacts.of(ecus.toList()),
        warnings = warnings,
    )

    private val sample = report(
        ecu(0x7E0, "05", "0C", "9D", vin = vin, name = "ECM-EngineControl", calibrationIds = listOf("CAL-1")),
        ecu(0x7E1, "31"),
    )

    @Test
    fun jsonRoundTripKeepsEverything() {
        val copy = CapabilityReport.fromJson(sample.toJson())
        assertEquals(sample, copy)
    }

    @Test
    fun jsonRoundTripKeepsNullsAndWarnings() {
        val bare = CapabilityReport(
            adapter = AdapterInfo(null, null, null, null, null),
            timing = Timing(0.0, 0, null, null),
            ecus = emptyList(),
            derived = DerivedFacts(FuelMethod.UNKNOWN, false, emptyList()),
            warnings = listOf("7E3: request failed: boom"),
        )
        assertEquals(bare, CapabilityReport.fromJson(bare.toJson()))
    }

    @Test
    fun jsonIsPrettyPrintedAndStatesItsSchemaVersion() {
        val json = sample.toJson()
        assertTrue(json.contains("\n    \"schemaVersion\": 1"), json)
        assertTrue(json.contains("\"requestId\": \"7E0\""), json)
        assertTrue(json.contains("\"supportedPids\": ["), json)
        assertTrue(json.contains("\"fuelMethod\": \"DIRECT_RATE\""), json)
    }

    @Test
    fun idsAndPidsAreWrittenAsUpperCaseHexStrings() {
        val written = CapabilityReport.fromJson(sample.toJson()).ecus.first()
        assertEquals("7E0", written.requestId)
        assertEquals("7E8", written.responseId)
        assertEquals(listOf("05", "0C", "9D"), written.supportedPids)
        assertEquals(0x7E0, written.requestCanId)
        assertEquals(0x7E8, written.responseCanId)
    }

    @Test
    fun newerSchemaIsRejected() {
        val newer = sample.toJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        val error = assertFailsWith<IllegalArgumentException> { CapabilityReport.fromJson(newer) }
        assertTrue(error.message!!.contains("schema 2"))
    }

    @Test
    fun unknownKeysAndGarbageAreRejectedAsInvalidReports() {
        val extra = sample.toJson().replaceFirst("{", "{\n \"surprise\": 1,")
        assertFailsWith<IllegalArgumentException> { CapabilityReport.fromJson(extra) }
        assertFailsWith<IllegalArgumentException> { CapabilityReport.fromJson("not json") }
    }

    @Test
    fun redactedKeepsOnlyTheFirstThreeVinCharactersAndMasksTheRest() {
        val redacted = sample.redacted()
        assertEquals("SIM**************", redacted.ecus[0].vin)
        assertEquals(17, redacted.ecus[0].vin!!.length)
        assertNull(redacted.ecus[1].vin)
    }

    @Test
    fun redactedReportNeverContainsTheVinInItsJson() {
        val json = sample.redacted().toJson()
        assertFalse(json.contains(vin))
        assertFalse(json.contains(vin.drop(3)))
        assertTrue(json.contains("SIM**************"))
    }

    @Test
    fun redactingLeavesEverythingElseAlone() {
        val redacted = sample.redacted()
        assertEquals(sample.copy(ecus = sample.ecus.map { it.copy(vin = null) }), redacted.copy(ecus = redacted.ecus.map { it.copy(vin = null) }))
    }

    @Test
    fun redactingDoesNotModifyTheOriginal() {
        sample.redacted()
        assertEquals(vin, sample.ecus[0].vin)
    }

    @Test
    fun redactionHidesTheLengthOfAnUnusualVinToo() {
        for (odd in listOf("AB", "SIMTEST", "X".repeat(30))) {
            val redacted = report(ecu(0x7E0, "05", vin = odd)).redacted().ecus.single().vin!!
            assertEquals(17, redacted.length, odd)
            assertEquals(odd.take(3).padEnd(3, '*'), redacted.take(3))
            assertTrue(redacted.drop(3).all { it == '*' })
        }
    }

    @Test
    fun redactionIsIdempotent() {
        assertEquals(sample.redacted(), sample.redacted().redacted())
    }
}
