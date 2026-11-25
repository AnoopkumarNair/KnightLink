package knightlink.app.session

import knightlink.app.data.DashboardReadings
import knightlink.core.runtime.BodyScanState
import knightlink.core.runtime.CaptureMode
import knightlink.core.runtime.ScanState
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
import kotlin.time.Duration
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
        assertEquals(ConnectionStatus.VehicleAsleep(4), SessionState.VehicleAsleep(4).toStatus())
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
    fun aSlowlyRefreshedValueIsNotStaleBetweenRefreshesAndAStaticOneNever() {
        val snapshot = snapshot(
            Triple("ecm", SignalValue("engine.coolant_temp", 40.0, "degC"), 0),
            Triple("ecm", SignalValue("obd.standard", 6.0, "enum"), 0),
            Triple("ecm", SignalValue("engine.rpm", 800.0, "rpm"), 0),
        )
        val periods = mapOf("engine.coolant_temp" to 6.seconds, "obd.standard" to Duration.INFINITE, "engine.rpm" to 150.milliseconds)
        fun stale(nowMs: Long) = liveValues(snapshot, nowMs.milliseconds, 5.seconds, periods).filter { it.stale }.map { it.signalId }

        // Past the plain limit: only the fast one is stale. The slow one is stale after three of its own periods, the static one never.
        assertEquals(listOf("engine.rpm"), stale(10_000))
        assertEquals(listOf("engine.rpm"), stale(18_000))
        assertEquals(listOf("engine.coolant_temp", "engine.rpm"), stale(18_001))
        assertEquals(listOf("engine.coolant_temp", "engine.rpm"), stale(3_600_000))
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

    @Test
    fun aScanThatLostTheLinkIsShownAsResumableAndACompletedOneIsNot() {
        val lost = knightlink.core.scan.ScanResult(outcome = knightlink.core.scan.ScanOutcome.LINK_LOST, detail = "link lost")
        val shown = ScanState.Finished(lost).toStatus() as ScanStatus.Finished
        assertEquals(knightlink.core.scan.ScanOutcome.LINK_LOST, shown.outcome)
        assertEquals(true, shown.resumable)
        val done = ScanState.Finished(knightlink.core.scan.ScanResult(outcome = knightlink.core.scan.ScanOutcome.COMPLETED)).toStatus() as ScanStatus.Finished
        assertEquals(false, done.resumable)
    }

    @Test
    fun aRefuelIsShownInLitresOfTheTankAndNothingShowsWithoutRefuelOrMode() {
        assertNull(driveSummary(emptyList(), null))
        val fill = knightlink.core.runtime.FuelFill(atMs = 1L, fromPercent = 22.7, toPercent = 100.0)
        val summary = driveSummary(listOf(fill), DriveMode.SPORT)!!
        assertEquals(DriveMode.SPORT, summary.mode)
        assertEquals(1, summary.fillCount)
        assertEquals(FuelFillSummary(1L, 23, 100, 39), summary.lastFill)
    }

    @Test
    fun aStateIsActiveByDefaultAndCarriesPassiveCaptureWhenTheSessionListensOnly() {
        assertEquals(CaptureMode.ACTIVE, UiState.IDLE.captureMode)
        val passive = UiState.IDLE.copy(captureMode = CaptureMode.PASSIVE)
        assertEquals(CaptureMode.PASSIVE, passive.captureMode)
        assertEquals(UiState.IDLE.liveValues, passive.liveValues)
    }

    @Test
    fun theBodyScanIsShownAsProgressThenAsAnOutcomeTheDriverCanActOn() {
        assertEquals(BodyScanProgress.Idle, BodyScanState.Idle.toProgress())
        val running = BodyScanState.Running("instrument cluster", done = 250, total = 1000, secondsLeft = 47).toProgress()
        assertEquals(BodyScanProgress.Running("instrument cluster", 250, 1000, 47), running)
        assertEquals(0.25f, (running as BodyScanProgress.Running).fraction)
        assertEquals(0f, BodyScanProgress.Running("x", 0, 0, 0).fraction, "no total yet: an empty bar, not a division by zero")
        assertEquals(1f, BodyScanProgress.Running("x", 12, 10, 0).fraction)
        assertFalse(running.isOutcome, "while it runs the driver is asked to wait")

        assertEquals(BodyScanProgress.Complete(7), BodyScanState.Complete(7).toProgress())
        assertEquals(BodyScanProgress.Partial(3), BodyScanState.Partial(3).toProgress())
        // Why the stage ended reaches the screen; only the time cap and the user's skip say "you can drive".
        val moving = BodyScanState.Partial(3, knightlink.core.runtime.BodyOutcome.MOVING, 12).toProgress()
        assertEquals(BodyScanProgress.Partial(3, knightlink.core.runtime.BodyOutcome.MOVING, 12), moving)
        kotlin.test.assertFalse((moving as BodyScanProgress.Partial).mayDrive)
        kotlin.test.assertFalse(BodyScanProgress.Partial(3, knightlink.core.runtime.BodyOutcome.SPEED_UNKNOWN).mayDrive)
        kotlin.test.assertFalse(BodyScanProgress.Partial(3, knightlink.core.runtime.BodyOutcome.FAILED).mayDrive)
        kotlin.test.assertTrue(BodyScanProgress.Partial(3, knightlink.core.runtime.BodyOutcome.TIME_CAP).mayDrive)
        kotlin.test.assertTrue(BodyScanProgress.Partial(3, knightlink.core.runtime.BodyOutcome.SKIPPED).mayDrive)
        assertEquals(BodyScanProgress.Skipped(moving = true), BodyScanState.Skipped(true, "the car moves (40 km/h)").toProgress())
        assertEquals(BodyScanProgress.Skipped(moving = false), BodyScanState.Skipped(false, "the speed cannot be read").toProgress())
        for (outcome in listOf(BodyScanProgress.Complete(0), BodyScanProgress.Partial(0), BodyScanProgress.Skipped(true))) assertTrue(outcome.isOutcome)
        assertFalse(BodyScanProgress.Idle.isOutcome)
        assertEquals(BodyScanProgress.Idle, UiState.IDLE.bodyScan)
    }

    @Test
    fun dashboardReadingsAreWholeKilometresAndNothingElse() {
        assertEquals(12345L, DashboardReadings.parseKm("12345"))
        assertEquals(310L, DashboardReadings.parseKm(" 310 "))
        assertEquals(0L, DashboardReadings.parseKm("0"))
        for (bad in listOf("", "  ", "12.5", "-3", "12 345", "abc", "99999999", "12345678901234567890")) assertEquals(null, DashboardReadings.parseKm(bad), bad)
    }

    @Test
    fun theParkedCheckCanBeRunAgainOnlyWhenConnectedNotRunningAndUnitsArePending() {
        val live = UiState(ConnectionStatus.Live, vehicle = null, liveValues = emptyList(), parkedCheckPending = 2)
        assertTrue(live.copy(bodyScan = BodyScanProgress.Partial(3, knightlink.core.runtime.BodyOutcome.SPEED_UNKNOWN)).canRunParkedCheckAgain)
        assertTrue(live.copy(bodyScan = BodyScanProgress.Skipped(moving = false)).canRunParkedCheckAgain)
        assertTrue(live.canRunParkedCheckAgain, "idle with units pending (the banner has gone)")
        assertFalse(live.copy(bodyScan = BodyScanProgress.Running("cluster", 1, 10, 30)).canRunParkedCheckAgain, "not while it runs")
        assertFalse(live.copy(parkedCheckPending = 0).canRunParkedCheckAgain, "nothing left")
        assertFalse(live.copy(connection = ConnectionStatus.Idle).canRunParkedCheckAgain, "not connected")
        assertFalse(live.copy(captureMode = CaptureMode.PASSIVE).canRunParkedCheckAgain, "only listening")
        assertFalse(live.copy(knownVehicle = false).canRunParkedCheckAgain)
        assertFalse(live.copy(bodyResult = BodyScanProgress.Complete(2)).canRunParkedCheckAgain, "it completed")
    }

    @Test
    fun aSkipAfterRetriesSaysSo() {
        assertEquals(BodyScanProgress.Skipped(moving = false, retried = true), BodyScanState.Skipped(false, "x", retried = true).toProgress())
        assertEquals(BodyScanProgress.Skipped(moving = true), BodyScanState.Skipped(true, "x").toProgress())
    }

    @Test
    fun anAutomaticStageIsMarkedAutoAllTheWayToTheScreen() {
        val running = BodyScanState.Running("instrument cluster", 10, 100, 30, auto = true).toProgress()
        assertEquals(BodyScanProgress.Running("instrument cluster", 10, 100, 30, auto = true), running)
        assertTrue(running.isAuto)
        assertTrue(BodyScanState.Partial(3, knightlink.core.runtime.BodyOutcome.MOVING, 20, auto = true).toProgress().isAuto)
        assertTrue(BodyScanState.Complete(3, auto = true).toProgress().isAuto)
        assertFalse(BodyScanState.Running("x", 1, 2, 3).toProgress().isAuto, "the parked stage at the start is announced as before")
        assertFalse(BodyScanState.Partial(3).toProgress().isAuto)
    }

    @Test
    fun theSwitchesDefaultToOnAndTheOdometerEstimateToUnavailable() {
        assertTrue(UiState.IDLE.autoBodyCheck)
        assertTrue(UiState.IDLE.clusterDeepCheck)
        assertEquals(OdometerEstimateUi.Unavailable, UiState.IDLE.odometerEstimate)
    }

    @Test
    fun theParkedCheckCanBeRunAgainWhileAnAutomaticStageIsNotRunning() {
        val live = UiState.IDLE.copy(connection = ConnectionStatus.Live, parkedCheckPending = 1)
        assertTrue(live.canRunParkedCheckAgain)
        assertFalse(live.copy(bodyScan = BodyScanProgress.Running("x", 1, 2, 3, auto = true)).canRunParkedCheckAgain, "not while one runs")
    }
}
