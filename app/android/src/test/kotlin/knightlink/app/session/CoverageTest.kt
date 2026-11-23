package knightlink.app.session

import knightlink.app.data.DashboardReadings
import knightlink.core.scan.FaultKind
import knightlink.core.scan.Monitor
import knightlink.core.scan.ReadinessSummary
import knightlink.core.vehicle.FuelMethod
import knightlink.core.vehicle.SupportTier
import knightlink.core.vehicle.UNKNOWN_MAKE
import knightlink.core.vehicle.VehicleIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoverageTest {
    private val demo = DemoDrive.frames[7]

    private fun stateOf(state: UiState, domain: CoverageDomain) = coverageReport(state).first { it.domain == domain }

    private fun withSignals(state: UiState, vararg ids: String) =
        state.copy(liveValues = ids.map { LiveValue(it, null, "1", "", 0, stale = false) })

    @Test
    fun everyDomainIsListedOnceInOrder() {
        assertEquals(CoverageDomain.entries, coverageReport(UiState.IDLE).map { it.domain })
    }

    @Test
    fun nothingIsClaimedNotAvailableBeforeTheCarWasAsked() {
        val items = coverageReport(UiState.IDLE)
        assertEquals(CoverageState.NOT_TRIED, items.first { it.domain == CoverageDomain.IDENTITY }.state)
        assertEquals(CoverageState.NOT_TRIED, items.first { it.domain == CoverageDomain.GEARBOX }.state)
        assertEquals(CoverageState.NOT_TRIED, items.first { it.domain == CoverageDomain.EMISSIONS }.state)
    }

    @Test
    fun identityNeedsMakeModelAndYearForCollected() {
        assertEquals(CoverageState.COLLECTED, stateOf(demo, CoverageDomain.IDENTITY).state)
        val noModel = demo.copy(identity = DemoDrive.identity.copy(model = null))
        assertEquals(CoverageState.PARTIAL, stateOf(noModel, CoverageDomain.IDENTITY).state)
        assertEquals(CoverageNote.MODEL_MISSING, stateOf(noModel, CoverageDomain.IDENTITY).note)
        val nothing = demo.copy(identity = VehicleIdentity(UNKNOWN_MAKE, false, null, null, null, null, SupportTier.BASIC))
        assertEquals(CoverageState.NOT_AVAILABLE, stateOf(nothing, CoverageDomain.IDENTITY).state)
    }

    @Test
    fun engineBasicsAreCollectedFromThreeOfFourSignals() {
        assertEquals(CoverageState.COLLECTED, stateOf(demo, CoverageDomain.ENGINE_BASICS).state)
        val some = withSignals(demo, "engine.rpm")
        assertEquals(CoverageState.PARTIAL, stateOf(some, CoverageDomain.ENGINE_BASICS).state)
        val none = withSignals(demo)
        assertEquals(CoverageState.NOT_AVAILABLE, stateOf(none, CoverageDomain.ENGINE_BASICS).state)
    }

    @Test
    fun fuelNeedsTheLevelAndARateForCollectedAndSpeedDensityIsOnlyApproximate() {
        assertEquals(CoverageState.COLLECTED, stateOf(demo, CoverageDomain.FUEL).state)
        val levelOnly = withSignals(demo, "fuel.level_pct").copy(vehicle = demo.vehicle?.copy(fuelMethod = FuelMethod.UNKNOWN))
        assertEquals(CoverageState.PARTIAL, stateOf(levelOnly, CoverageDomain.FUEL).state)
        val approx = withSignals(demo).copy(vehicle = demo.vehicle?.copy(fuelMethod = FuelMethod.SPEED_DENSITY))
        assertEquals(CoverageNote.APPROXIMATE, stateOf(approx, CoverageDomain.FUEL).note)
        val nothing = withSignals(demo).copy(vehicle = demo.vehicle?.copy(fuelMethod = FuelMethod.UNKNOWN))
        assertEquals(CoverageState.NOT_AVAILABLE, stateOf(nothing, CoverageDomain.FUEL).state)
    }

    @Test
    fun anOdometerTheDriverTypedIsPartialAndSaidSo() {
        val typed = demo.copy(dashboard = DashboardReadings(odometerStartKm = 1000), garage = GarageStatus.New)
        assertEquals(CoverageState.PARTIAL, stateOf(typed, CoverageDomain.DISTANCE).state)
        assertEquals(CoverageNote.FROM_YOU, stateOf(typed, CoverageDomain.DISTANCE).note)
        val untyped = demo.copy(dashboard = DashboardReadings(), garage = GarageStatus.New)
        assertEquals(CoverageState.NOT_AVAILABLE, stateOf(untyped, CoverageDomain.DISTANCE).state)
        val read = withSignals(untyped, "vehicle.odometer_km")
        assertEquals(CoverageState.COLLECTED, stateOf(read, CoverageDomain.DISTANCE).state)
    }

    @Test
    fun faultsAreCollectedWithReadinessAndPartialWithout() {
        assertEquals(CoverageState.COLLECTED, stateOf(demo, CoverageDomain.FAULTS).state)
        val noReadiness = demo.copy(faults = FaultSummary(FaultsStatus.READY, emptyList(), null))
        assertEquals(CoverageState.PARTIAL, stateOf(noReadiness, CoverageDomain.FAULTS).state)
        assertEquals(
            CoverageState.NOT_AVAILABLE,
            stateOf(demo.copy(faults = FaultSummary(FaultsStatus.FAILED, emptyList(), null)), CoverageDomain.FAULTS).state,
        )
        assertEquals(CoverageState.NOT_TRIED, stateOf(demo.copy(faults = FaultSummary.NOT_READ), CoverageDomain.FAULTS).state)
    }

    @Test
    fun aCalculatedGearIsPartialAndAReadGearIsCollected() {
        assertEquals(CoverageState.NOT_AVAILABLE, stateOf(demo, CoverageDomain.GEARBOX).state)
        assertEquals(CoverageState.PARTIAL, stateOf(withSignals(demo, "transmission.gear_calc"), CoverageDomain.GEARBOX).state)
        assertEquals(CoverageState.COLLECTED, stateOf(withSignals(demo, "transmission.gear"), CoverageDomain.GEARBOX).state)
    }

    @Test
    fun driveModeAndTyresAreFactsAboutTheCarNotFaults() {
        val unknownCar = demo.copy(knownVehicle = false)
        assertEquals(CoverageState.NOT_AVAILABLE, stateOf(unknownCar, CoverageDomain.DRIVE_MODE).state)
        assertEquals(CoverageNote.NEEDS_FULL_PROFILE, stateOf(unknownCar, CoverageDomain.DRIVE_MODE).note)
        assertEquals(CoverageState.NOT_TRIED, stateOf(demo, CoverageDomain.DRIVE_MODE).state)
        val labelled = demo.copy(drive = DriveSummary(DriveMode.SPORT, null, 0))
        assertEquals(CoverageState.PARTIAL, stateOf(labelled, CoverageDomain.DRIVE_MODE).state)
        assertEquals(CoverageState.NOT_AVAILABLE, stateOf(demo, CoverageDomain.TYRES).state)
    }

    @Test
    fun bodyUnitsFollowTheBodyScanOnAKnownCarAndTheLadderOnAnyOther() {
        assertEquals(CoverageState.COLLECTED, stateOf(demo, CoverageDomain.BODY_UNITS).state)
        val partial = demo.copy(bodyResult = BodyScanProgress.Partial(2))
        assertEquals(CoverageState.PARTIAL, stateOf(partial, CoverageDomain.BODY_UNITS).state)
        val unknown = demo.copy(knownVehicle = false, bodyResult = BodyScanProgress.Idle)
        assertEquals(CoverageState.COLLECTED, stateOf(unknown, CoverageDomain.BODY_UNITS).state)
        val silent = unknown.copy(ladderResult = LadderProgress.Complete("L3", 0))
        assertEquals(CoverageState.NOT_AVAILABLE, stateOf(silent, CoverageDomain.BODY_UNITS).state)
        assertEquals(CoverageNote.ASKED_NOTHING_ANSWERED, stateOf(silent, CoverageDomain.BODY_UNITS).note)
        val stopped = unknown.copy(ladderResult = LadderProgress.Stopped("L2", "moved"))
        assertEquals(CoverageState.PARTIAL, stateOf(stopped, CoverageDomain.BODY_UNITS).state)
        val notYet = unknown.copy(ladderResult = LadderProgress.Idle, deepScan = false)
        assertEquals(CoverageNote.DEEP_SCAN_OFF, stateOf(notYet, CoverageDomain.BODY_UNITS).note)
    }

    @Test
    fun emissionsAreCollectedWhenDpfOrAdblueDataExists() {
        assertEquals(CoverageState.NOT_AVAILABLE, stateOf(demo, CoverageDomain.EMISSIONS).state)
        assertEquals(CoverageState.COLLECTED, stateOf(withSignals(demo, "dpf.soot_pct"), CoverageDomain.EMISSIONS).state)
        assertEquals(CoverageState.COLLECTED, stateOf(withSignals(demo, "def.level_pct"), CoverageDomain.EMISSIONS).state)
    }

    @Test
    fun theVerdictCountsCodesAndTheLampWithoutJudging() {
        assertEquals(HealthVerdict.Kind.NOT_CHECKED, healthVerdict(FaultSummary.NOT_READ).kind)
        val clear = FaultSummary(FaultsStatus.READY, emptyList(), ReadinessSummary(false, 0, false, emptyList()))
        assertEquals(HealthVerdict.Kind.ALL_CLEAR, healthVerdict(clear).kind)
        val lampOnly = FaultSummary(FaultsStatus.READY, emptyList(), ReadinessSummary(true, 0, false, emptyList()))
        assertEquals(HealthVerdict(HealthVerdict.Kind.CODES_STORED, 0, lampOn = true), healthVerdict(lampOnly))
        val some = FaultSummary(FaultsStatus.READY, listOf(FaultItem("P0300", null, FaultKind.PENDING, "7E0")), null)
        assertEquals(HealthVerdict(HealthVerdict.Kind.CODES_STORED, 1, lampOn = false), healthVerdict(some))
    }

    @Test
    fun theReadinessLineCountsOnlySupportedMonitors() {
        val monitors =
            listOf(
                Monitor("a", supported = true, complete = true),
                Monitor("b", supported = true, complete = false),
                Monitor("c", supported = false, complete = null),
            )
        assertEquals(ReadinessLine(1, 2), readinessLine(ReadinessSummary(false, 0, false, monitors)))
        assertNull(readinessLine(null))
        assertNull(readinessLine(ReadinessSummary(false, 0, false, emptyList())))
        assertEquals(ReadinessLine(7, 8), readinessLine(DemoDrive.frames[7].faults.readiness))
    }
}
