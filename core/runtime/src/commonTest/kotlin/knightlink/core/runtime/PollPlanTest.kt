package knightlink.core.runtime

import knightlink.core.scheduler.PollItem
import knightlink.core.signals.CompiledParameter
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import knightlink.core.vehicle.AdapterInfo
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DefinitionCoverage
import knightlink.core.vehicle.DerivedFacts
import knightlink.core.vehicle.EcuReport
import knightlink.core.vehicle.Timing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun Byte.hex(): String = (toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')

private fun List<PollItem>.summary(): List<String> =
    map { "${it.requestId.toString(16).uppercase()} ${it.payload.joinToString("") { b -> b.hex() }}" }.sorted()

class PollPlanTest {
    private val catalog = testCatalog()

    private fun ecu(request: String, response: String, vararg pids: String) =
        EcuReport(request, response, name = null, calibrationIds = emptyList(), supportedPids = pids.toList(), vin = null)

    private fun plan(
        vararg ecus: EcuReport,
        primary: String? = "ecm",
        period: (CompiledParameter) -> Duration? = { 1.seconds },
    ): List<PollItem> {
        val list = ecus.toList()
        val report = CapabilityReport(
            adapter = AdapterInfo(null, null, null, null, null),
            timing = Timing(0.0, 0, null, null),
            ecus = list,
            derived = DerivedFacts.of(list),
        )
        return confirmedPollItems(catalog, DefinitionCoverage.compare(report, catalog), primary, period)
    }

    @Test
    fun aParameterTiedToAnEcuIsAskedOfThatEcu() {
        val items = plan(ecu("7E0", "7E8", "8B"), ecu("7E4", "7EC", "9B"))
        assertEquals(listOf("7E0 018B", "7E4 019B"), items.summary())
    }

    @Test
    fun aGenericParameterGoesToThePrimaryEcuWhenItAnswers() {
        val both = arrayOf(ecu("7E0", "7E8", "0C"), ecu("7E4", "7EC", "0C"))
        assertEquals(listOf("7E0 010C"), plan(*both, primary = "ecm").summary())
        assertEquals(listOf("7E4 010C"), plan(*both, primary = "rdcm").summary())
    }

    @Test
    fun aGenericParameterFallsBackToTheLowestAnsweringRequestId() {
        // The primary ECU (ecm, 7E0) does not support PID 0C; 7E1 and 7E4 do.
        val items = plan(ecu("7E4", "7EC", "0C"), ecu("7E1", "7E9", "0C"), ecu("7E0", "7E8", "05"))
        assertEquals(listOf("7E0 0105", "7E1 010C"), items.summary())
    }

    @Test
    fun withoutAPrimaryEcuTheLowestAnsweringRequestIdIsUsed() {
        val items = plan(ecu("7E4", "7EC", "0C"), ecu("7E0", "7E8", "0C"), primary = null)
        assertEquals(listOf("7E0 010C"), items.summary())
    }

    @Test
    fun aPidNoEcuReportedIsNeverPolled() {
        // PID 04 is in the catalog but no probed ECU supports it; 8B is supported by 7E0 only, so rdcm must not be asked for it.
        val items = plan(ecu("7E0", "7E8", "05", "8B"), ecu("7E4", "7EC", "05"))
        val text = items.summary()
        assertTrue(text.none { it.endsWith("0104") }, text.toString())
        assertEquals(listOf("7E0 0105", "7E0 018B"), text)
    }

    @Test
    fun anEcuSpecificParameterIsNotPolledWhenOnlyAnotherEcuSupportsThePid() {
        // 9B is defined for rdcm, but only the engine ECU claims it.
        assertEquals(emptyList(), plan(ecu("7E0", "7E8", "9B")).summary())
    }

    @Test
    fun theFunctionalAddressIsNeverUsed() {
        // A (hand-made) report that lists the broadcast address as an ECU must not make the plan poll it.
        val items = plan(ecu("7DF", "7E8", "0C", "05"), ecu("7E1", "7E9", "05"))
        assertEquals(listOf("7E1 0105"), items.summary())
        assertTrue(items.none { it.requestId == 0x7DF })
    }

    @Test
    fun parametersWithoutAPeriodAreLeftOut() {
        val items = plan(ecu("7E0", "7E8", "05", "0C", "8B")) { parameter -> if (parameter.pid == "8B") 500.milliseconds else null }
        assertEquals(listOf("7E0 018B"), items.summary())
        assertEquals(0.5.seconds, items.single().period)
    }

    @Test
    fun onlyRepliesThatFitOneFrameAskForOneReplyAndIdsAreUniqueAndReadable() {
        val items = plan(ecu("7E0", "7E8", "05", "0C", "8B"), ecu("7E4", "7EC", "0C", "9B"))
        // The count digit cuts a multi-frame reply to its first frame: 018B has 7 data bytes, so it is asked without one.
        assertEquals(
            mapOf("ecm:0105" to 1, "ecm:010C" to 1, "ecm:018B" to null, "rdcm:019B" to 1),
            items.associate { it.id to it.expectedResponses },
        )
        assertEquals(mapOf("ecm:0105" to 1, "ecm:010C" to 2, "ecm:018B" to 7, "rdcm:019B" to 4), items.associate { it.id to it.dataLength })
        assertEquals(items.size, items.map { it.id }.toSet().size)
        assertEquals(setOf("ecm:0105", "ecm:010C", "ecm:018B", "rdcm:019B"), items.map { it.id }.toSet())
    }

    @Test
    fun anEcuTheCatalogDoesNotKnowStillGetsItsGenericParametersByRequestIdName() {
        val items = plan(ecu("7E5", "7ED", "0C"), primary = "ecm")
        assertEquals(listOf("7E5 010C"), items.summary())
        assertEquals("7E5:010C", items.single().id)
    }

    @Test
    fun confirmedUdsPollItemsIncludesVerifiedService22AndSkipsInferred() {
        val udsDef = DefinitionJson.parse(
            """
            {
              "schemaVersion": 1,
              "id": "vehicles/test/uds",
              "kind": "vehicle",
              "title": "Test UDS",
              "ecus": [
                { "id": "tcm", "name": "Gearbox", "requestId": "7E1", "responseId": "7E9" }
              ],
              "parameters": [
                {
                  "service": "22",
                  "pid": "1E12",
                  "ecu": "tcm",
                  "length": 1,
                  "provenance": "verified",
                  "signals": [{ "id": "transmission.gear", "unit": "", "formula": "A" }]
                },
                {
                  "service": "22",
                  "pid": "1E1F",
                  "ecu": "tcm",
                  "length": 2,
                  "provenance": "inferred",
                  "signals": [{ "id": "transmission.atf_temp", "unit": "C", "formula": "A - 40" }]
                }
              ]
            }
            """.trimIndent(),
        )
        val udsCatalog = SignalCatalog.of(listOf(udsDef))
        val report = CapabilityReport(
            adapter = AdapterInfo(null, null, null, null, null),
            timing = Timing(0.0, 0, null, null),
            ecus = listOf(ecu("7E1", "7E9", "30")),
            derived = DerivedFacts.of(listOf(ecu("7E1", "7E9", "30"))),
        )
        val items = confirmedUdsPollItems(udsCatalog, report, primaryEcu = "tcm") { 1.seconds }
        assertEquals(listOf("7E1 221E12"), items.summary())
        assertEquals("tcm:221E12", items.single().id)
        assertEquals(1, items.single().expectedResponses)
    }
}
