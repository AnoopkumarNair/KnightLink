package knightlink.app.session

import knightlink.core.runtime.SessionState
import knightlink.core.signals.SignalValue
import knightlink.core.telemetry.Reading
import knightlink.core.telemetry.TelemetrySnapshot
import knightlink.core.vehicle.AdapterInfo
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.CoveredParameter
import knightlink.core.vehicle.DefinitionCoverage
import knightlink.core.vehicle.DerivedFacts
import knightlink.core.vehicle.EcuReport
import knightlink.core.vehicle.MissingParameter
import knightlink.core.vehicle.ProbeStage
import knightlink.core.vehicle.Timing
import knightlink.core.vehicle.UnmappedPids
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class UiStateTest {
    private val vin = "TESTCAR1234567890"

    private fun snapshot(vararg entries: Triple<String?, SignalValue, Long>): TelemetrySnapshot =
        entries.fold(TelemetrySnapshot.EMPTY) { snapshot, (ecu, signal, atMs) -> snapshot.with(listOf(Reading(ecu, signal)), atMs.milliseconds) }

    @Test
    fun sessionStatesMapToConnectionStatuses() {
        assertEquals(ConnectionStatus.Idle, SessionState.Idle.toStatus())
        assertEquals(ConnectionStatus.Idle, SessionState.Stopped.toStatus())
        assertEquals(ConnectionStatus.Connecting(3), SessionState.Connecting(3).toStatus())
        assertEquals(ConnectionStatus.Initialising, SessionState.Initialising.toStatus())
        assertEquals(ConnectionStatus.Probing(ProbeStage.BENCHMARK), SessionState.Probing(ProbeStage.BENCHMARK).toStatus())
        assertEquals(ConnectionStatus.Failed("no"), SessionState.Failed("no").toStatus())
        assertEquals(ConnectionStatus.Live, SessionState.Polling(report(), coverage()).toStatus())
        assertEquals(ConnectionStatus.WaitingForIgnition(3, busFault = false), SessionState.WaitingForIgnition(3, null).toStatus())
        assertEquals(ConnectionStatus.WaitingForIgnition(1, busFault = true), SessionState.WaitingForIgnition(1, "CAN_ERROR").toStatus())
        assertEquals(ConnectionStatus.Recovering(2, "nothing answered"), SessionState.Recovering(2, "nothing answered").toStatus())
    }

    @Test
    fun reconnectingShowsWholeSecondsRoundedUp() {
        assertEquals(ConnectionStatus.Reconnecting(2, 1, "x"), SessionState.Reconnecting(2, 1000.milliseconds, "x").toStatus())
        assertEquals(ConnectionStatus.Reconnecting(2, 2, "x"), SessionState.Reconnecting(2, 1001.milliseconds, "x").toStatus())
        assertEquals(ConnectionStatus.Reconnecting(2, 30, "x"), SessionState.Reconnecting(2, 30.seconds, "x").toStatus())
    }

    @Test
    fun onlyIdleIsInactive() {
        assertFalse(UiState.IDLE.isActive)
        assertTrue(UiState.IDLE.copy(connection = ConnectionStatus.Failed("x")).isActive)
        assertTrue(UiState.IDLE.copy(connection = ConnectionStatus.Live).isActive)
    }

    @Test
    fun valuesAreFormattedWithoutNoise() {
        assertEquals("799", formatValue(799.0))
        assertEquals("0", formatValue(-0.0))
        assertEquals("-40", formatValue(-40.0))
        assertEquals("17.65", formatValue(17.647058823529413))
        assertEquals("98.50", formatValue(98.5))
        assertEquals("16383.8", formatValue(16383.75))
        assertEquals("-", formatValue(Double.NaN))
        assertEquals("-", formatValue(Double.POSITIVE_INFINITY))
    }

    @Test
    fun unitsAreShownTheWayADriverReadsThem() {
        assertEquals("°C", displayUnit("degC"))
        assertEquals("%", displayUnit("percent"))
        assertEquals("rpm", displayUnit("rpm"))
        assertEquals("km/h", displayUnit("km/h"))
        assertEquals("", displayUnit("enum"))
        assertEquals("", displayUnit("bitmap"))
    }

    @Test
    fun valuesAreSortedBySignalIdThenEcu() {
        val values = liveValues(
            snapshot(
                Triple("tcm", SignalValue("engine.rpm", 1.0, "rpm"), 0),
                Triple("rdcm", SignalValue("def.level", 2.0, "percent"), 0),
                Triple("ecm", SignalValue("engine.rpm", 3.0, "rpm"), 0),
                Triple(null, SignalValue("dpf.soot_pct", 4.0, "percent"), 0),
            ),
            now = 0.milliseconds,
            staleAfter = 5.seconds,
        )
        assertEquals(listOf("def.level", "dpf.soot_pct", "engine.rpm", "engine.rpm"), values.map { it.signalId })
        assertEquals(listOf("ecm", "tcm"), values.filter { it.signalId == "engine.rpm" }.map { it.ecu })
        assertEquals(listOf("def.level|rdcm", "dpf.soot_pct|", "engine.rpm|ecm", "engine.rpm|tcm"), values.map { it.key })
    }

    @Test
    fun aValueBecomesStaleOnlyAfterTheLimit() {
        val snapshot = snapshot(Triple("ecm", SignalValue("engine.rpm", 800.0, "rpm"), 10_000))
        fun at(nowMs: Long) = liveValues(snapshot, nowMs.milliseconds, 5.seconds).single()

        assertFalse(at(10_000).stale)
        assertFalse(at(15_000).stale)
        assertTrue(at(15_001).stale)
        assertEquals(9, at(19_999).ageSeconds)
        assertEquals("800", at(15_001).valueText)
        assertEquals("rpm", at(15_001).unit)
    }

    @Test
    fun aReadingFromTheFutureIsNotNegativeAge() {
        // The controller's clock starts marginally before the session's, so a reading can be a few milliseconds ahead of "now".
        val value = liveValues(snapshot(Triple("ecm", SignalValue("a.b", 1.0, "x"), 1_000)), 990.milliseconds, 5.seconds).single()
        assertEquals(0, value.ageSeconds)
        assertFalse(value.stale)
    }

    @Test
    fun valuesGroupByTheTextBeforeTheFirstDot() {
        fun v(id: String) = LiveValue(id, "ecm", "1", "", 0, false)
        val groups = groupByPrefix(listOf(v("def.level"), v("dpf.soot_pct"), v("dpf.regen_status"), v("engine.rpm"), v("plain")))
        assertEquals(listOf("def", "dpf", "engine", "plain"), groups.map { it.prefix })
        assertEquals(listOf("dpf.soot_pct", "dpf.regen_status"), groups[1].values.map { it.signalId })
        assertEquals("dpf", signalPrefix("dpf.soot_pct"))
        assertEquals("plain", signalPrefix("plain"))
        assertEquals("a", signalPrefix("a.b.c"))
    }

    @Test
    fun theVehicleSummaryNeverHoldsAFullVin() {
        val summary = vehicleSummary(report(), coverage())

        assertEquals("TES" + "*".repeat(14), summary.vinRedacted)
        assertFalse(summary.toString().contains(vin))
        assertFalse(summary.toString().contains("1234567890"))
    }

    @Test
    fun theVehicleSummaryCarriesWhatTheCardShows() {
        val summary = vehicleSummary(report(), coverage())

        assertEquals(listOf(EcuSummary("Engine ECU", "7E0"), EcuSummary(null, "7E1")), summary.ecus)
        assertEquals("ELM327 v2.3", summary.adapterBanner)
        assertEquals(21.5, summary.requestsPerSecond)
        assertTrue(summary.hasDpfData)
        assertEquals(listOf("0C  engine.rpm  (ecm)"), summary.confirmed)
        assertEquals(listOf("7A  dpf.a, dpf.b"), summary.missing)
        assertEquals(listOf("7E1: 05 0D"), summary.unmapped)
    }

    @Test
    fun aVehicleWithoutAVinHasNone() {
        val noVin = report().copy(ecus = report().ecus.map { it.copy(vin = null) })
        assertNull(vehicleSummary(noVin, coverage()).vinRedacted)
    }

    private fun report(): CapabilityReport {
        val ecus = listOf(
            EcuReport("7E0", "7E8", "Engine ECU", emptyList(), listOf("0C", "7A"), vin),
            EcuReport("7E1", "7E9", null, emptyList(), listOf("05", "0D"), null),
        )
        return CapabilityReport(
            adapter = AdapterInfo("ELM327 v2.3", null, null, null, null),
            timing = Timing(probeMs = 1000.0, benchmarkSamples = 5, medianRoundTripMs = 46.5, estimatedRequestsPerSecond = 21.5),
            ecus = ecus,
            derived = DerivedFacts.of(ecus),
        )
    }

    private fun coverage() = DefinitionCoverage(
        confirmed = listOf(CoveredParameter("0C", "ecm", listOf("engine.rpm"), listOf("7E0"))),
        missing = listOf(MissingParameter("7A", null, listOf("dpf.a", "dpf.b"))),
        unmapped = listOf(UnmappedPids("7E1", listOf("05", "0D"))),
    )
}
