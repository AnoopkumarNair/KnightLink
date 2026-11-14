package knightlink.core.vehicle

import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.EcuDefinition
import knightlink.core.signals.ParameterDefinition
import knightlink.core.signals.Provenance
import knightlink.core.signals.SignalCatalog
import knightlink.core.signals.SignalDefinition
import kotlin.test.Test
import kotlin.test.assertEquals

class DefinitionCoverageTest {
    private fun parameter(pid: String, ecu: String? = null, service: String = "01", signal: String = "sig.$pid") = ParameterDefinition(
        service = service,
        pid = pid,
        ecu = ecu,
        provenance = Provenance.STANDARD,
        signals = listOf(SignalDefinition(signal, "A", "unit")),
    )

    private fun catalog(vararg parameters: ParameterDefinition): SignalCatalog = SignalCatalog.of(
        listOf(
            DefinitionFile(
                schemaVersion = 1,
                id = "test/vehicle",
                kind = DefinitionFile.Kind.VEHICLE,
                title = "Test",
                ecus = listOf(
                    // Deliberately not 7E0 first: matching must go by CAN id, not by position or name.
                    EcuDefinition("scr", "Reductant", "7E4", "7EC"),
                    EcuDefinition("engine", "Engine", "7E0", "7E8"),
                ),
                parameters = parameters.toList(),
            ),
        ),
    )

    private val probed = CapabilityReport(
        adapter = AdapterInfo(null, null, null, null, null),
        timing = Timing(0.0, 0, null, null),
        ecus = listOf(ecu(0x7E0, "05", "0C", "9D"), ecu(0x7E4, "05", "9B")),
        derived = DerivedFacts(FuelMethod.DIRECT_RATE, false, emptyList()),
    )

    @Test
    fun definitionForAnEcuIsConfirmedOnlyWhenThatEcuSupportsThePid() {
        val coverage = DefinitionCoverage.compare(probed, catalog(parameter("9D", "engine"), parameter("9B", "scr"), parameter("9D", "scr", signal = "other")))
        assertEquals(
            listOf(
                CoveredParameter("9B", "scr", listOf("sig.9B"), listOf("7E4")),
                CoveredParameter("9D", "engine", listOf("sig.9D"), listOf("7E0")),
            ),
            coverage.confirmed,
        )
        // 9D exists on the engine ECU, but the definition says the reductant controller.
        assertEquals(listOf(MissingParameter("9D", "scr", listOf("other"))), coverage.missing)
    }

    @Test
    fun definitionWithoutEcuIsConfirmedByAnyEcuAndRecordsWhich() {
        val coverage = DefinitionCoverage.compare(probed, catalog(parameter("05"), parameter("0C")))
        assertEquals(
            listOf(
                CoveredParameter("05", null, listOf("sig.05"), listOf("7E0", "7E4")),
                CoveredParameter("0C", null, listOf("sig.0C"), listOf("7E0")),
            ),
            coverage.confirmed,
        )
        assertEquals(emptyList(), coverage.missing)
    }

    @Test
    fun definitionNoEcuSupportsIsMissing() {
        val coverage = DefinitionCoverage.compare(probed, catalog(parameter("04"), parameter("33")))
        assertEquals(emptyList(), coverage.confirmed)
        assertEquals(listOf(MissingParameter("04", null, listOf("sig.04")), MissingParameter("33", null, listOf("sig.33"))), coverage.missing)
    }

    @Test
    fun definitionForAnEcuThatDidNotAnswerIsMissing() {
        val onlyEngine = probed.copy(ecus = listOf(ecu(0x7E0, "05", "9B")))
        val coverage = DefinitionCoverage.compare(onlyEngine, catalog(parameter("9B", "scr")))
        assertEquals(listOf(MissingParameter("9B", "scr", listOf("sig.9B"))), coverage.missing)
    }

    @Test
    fun onlyServiceOneDefinitionsAreCompared() {
        val coverage = DefinitionCoverage.compare(probed, catalog(parameter("F190", service = "22"), parameter("05")))
        assertEquals(listOf("05"), coverage.confirmed.map { it.pid })
        assertEquals(emptyList(), coverage.missing)
    }

    @Test
    fun supportedPidsWithoutADefinitionAreUnmappedPerEcu() {
        val coverage = DefinitionCoverage.compare(probed, catalog(parameter("05"), parameter("9D", "engine")))
        // 0C is generic-less here, 9B only exists on the scr entry (none defined), so each ECU has its own gaps.
        assertEquals(
            listOf(UnmappedPids("7E0", listOf("0C")), UnmappedPids("7E4", listOf("9B"))),
            coverage.unmapped,
        )
    }

    @Test
    fun aPidDefinedOnlyForAnotherEcuIsUnmappedHere() {
        val coverage = DefinitionCoverage.compare(probed, catalog(parameter("05"), parameter("0C"), parameter("9B", "scr"), parameter("9D", "scr")))
        // The engine reports 9D, but the catalog only defines it for the reductant controller.
        assertEquals(listOf(UnmappedPids("7E0", listOf("9D"))), coverage.unmapped)
    }

    @Test
    fun ecuWithNothingUnmappedIsLeftOut() {
        val coverage = DefinitionCoverage.compare(probed, catalog(parameter("05"), parameter("0C"), parameter("9D"), parameter("9B")))
        assertEquals(emptyList(), coverage.unmapped)
    }

    @Test
    fun ecuUnknownToTheCatalogOnlyMatchesGenericDefinitions() {
        val strange = probed.copy(ecus = listOf(ecu(0x7E2, "05", "9B")))
        val coverage = DefinitionCoverage.compare(strange, catalog(parameter("05"), parameter("9B", "scr")))
        assertEquals(listOf(UnmappedPids("7E2", listOf("9B"))), coverage.unmapped)
        assertEquals(listOf("05"), coverage.confirmed.map { it.pid })
        assertEquals(listOf("9B"), coverage.missing.map { it.pid })
    }

    @Test
    fun anEmptyReportConfirmsNothing() {
        val coverage = DefinitionCoverage.compare(probed.copy(ecus = emptyList()), catalog(parameter("05")))
        assertEquals(emptyList(), coverage.confirmed)
        assertEquals(listOf("05"), coverage.missing.map { it.pid })
        assertEquals(emptyList(), coverage.unmapped)
    }

    @Test
    fun resultsAreOrderedByPidWhateverTheCatalogOrder() {
        val coverage = DefinitionCoverage.compare(probed.copy(ecus = emptyList()), catalog(parameter("9D", "engine"), parameter("05"), parameter("0C")))
        assertEquals(listOf("05", "0C", "9D"), coverage.missing.map { it.pid })
    }
}
