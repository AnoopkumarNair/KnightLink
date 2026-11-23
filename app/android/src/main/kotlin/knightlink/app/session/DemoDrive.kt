package knightlink.app.session

import knightlink.app.data.DashboardReadings
import knightlink.core.runtime.drivetruth.DriveTruthNote
import knightlink.core.runtime.drivetruth.DriveTruthReport
import knightlink.core.runtime.drivetruth.FuelSource
import knightlink.core.scan.FaultKind
import knightlink.core.scan.Monitor
import knightlink.core.scan.ReadinessSummary
import knightlink.core.vehicle.FuelMethod
import knightlink.core.vehicle.ProbeStage
import knightlink.core.vehicle.SupportTier
import knightlink.core.vehicle.VehicleIdentity

/**
 * A made-up car and drive for looking at the screens without a car: the guided flow from "connecting" to the Drive Truth card, one frame at a
 * time. Nothing here is read from a car or an adapter; the make, the figures and the fault codes are invented. Debug builds offer it as "Demo drive".
 */
object DemoDrive {
    const val MAKE = "Demo Motors"
    const val MODEL = "Roamer"
    private const val YEAR = 2022

    val identity =
        VehicleIdentity(
            MAKE,
            makeVerified = true,
            model = MODEL,
            year = YEAR,
            vinLast4 = "0042",
            protocol = "ISO 15765-4 CAN (11 bit, 500 kbaud)",
            tier = SupportTier.FULL,
        )

    val profile =
        CarProfile(MAKE, MODEL, nickname = "Demo car", fuelType = FuelType.DIESEL, transmission = Transmission.MANUAL, odometerKm = 48_210, odometerAtMs = 1L)

    /** The report the card shows: invented figures that agree with each other (₹142 for 24.8 km). */
    val report = DriveTruthReport(
        durationMinutes = 38.0,
        movingMinutes = 31.0,
        idleMinutes = 7.0,
        carDistanceKm = 24.8,
        gpsDistanceKm = 24.3,
        carVsGpsPercent = 2.1,
        fuelLitres = 1.56,
        fuelSource = FuelSource.FUEL_RATE_LPH,
        costRupees = 142.0,
        costPerKmRupees = 5.7,
        idleCostRupees = 12.0,
        kmPerLitre = 15.9,
        notes = listOf(DriveTruthNote.FUEL_DENSITY_ASSUMED),
        events = emptyList(),
    )

    private val faults = FaultSummary(
        FaultsStatus.READY,
        listOf(
            FaultItem("P0420", "Catalyst system efficiency below threshold (bank 1)", FaultKind.CONFIRMED, "7E0"),
            FaultItem("P0171", "System too lean (bank 1)", FaultKind.PENDING, "7E0"),
            FaultItem("P1A2B", null, FaultKind.PERMANENT, "7E0"),
        ),
        ReadinessSummary(
            milOn = false,
            storedCount = 1,
            compressionIgnition = true,
            monitors = (1..8).map { Monitor("Monitor $it", supported = true, complete = it != 3) },
        ),
    )

    private val vehicle = VehicleSummary(
        ecus = listOf(EcuSummary("Engine", "7E0")),
        vinRedacted = "DEM**************",
        adapterBanner = "ELM327 v2.2",
        requestsPerSecond = 21.5,
        fuelMethod = FuelMethod.DIRECT_RATE,
        hasDpfData = false,
        confirmed = listOf("0C  engine.rpm", "0D  vehicle.speed", "05  engine.coolant_temp", "2F  fuel.level_pct", "5E  fuel.rate_lph"),
        missing = listOf("A4  transmission.gear"),
        unmapped = emptyList(),
    )

    private fun live(speed: Int, rpm: Int) = listOf(
        LiveValue("engine.coolant_temp", null, "88", "°C", 0, stale = false),
        LiveValue("engine.rpm", null, rpm.toString(), "rpm", 0, stale = false),
        LiveValue("fuel.level_pct", null, "62", "%", 0, stale = false),
        LiveValue("fuel.rate_lph", null, if (speed > 0) "3.4" else "0.8", "L/h", 0, stale = false),
        LiveValue("vehicle.speed", null, speed.toString(), "km/h", 0, stale = false),
    )

    private fun base(connection: ConnectionStatus) = UiState(connection, vehicle = null, liveValues = emptyList(), fuelPrice = 91.0)

    private fun car(
        connection: ConnectionStatus,
        speed: Int = 0,
        rpm: Int = 780,
        faults: FaultSummary = DemoDrive.faults,
        ladder: LadderProgress = LadderProgress.Idle,
        stage: DriveStage = DriveStage.NOT_STARTED,
        truth: DriveTruthReport? = null,
    ) = base(connection).copy(
        vehicle = vehicle,
        liveValues = live(speed, rpm),
        identity = identity,
        knownVehicle = true,
        faults = faults,
        garage = GarageStatus.Known(profile),
        ladder = ladder,
        ladderResult = if (ladder == LadderProgress.Idle) LadderProgress.Complete("L2", 12) else LadderProgress.Idle,
        bodyResult = BodyScanProgress.Complete(5),
        driveStage = stage,
        driveTruth = truth,
        dashboard = DashboardReadings(odometerStartKm = 48_210),
        tyres = null,
    )

    /** The frames in order; show each for a couple of seconds. */
    val frames: List<UiState> = listOf(
        base(ConnectionStatus.Connecting(1)),
        base(ConnectionStatus.Initialising),
        base(ConnectionStatus.WaitingForIgnition(1, busFault = false)),
        base(ConnectionStatus.Negotiating(1)),
        base(ConnectionStatus.Probing(ProbeStage.VEHICLE_INFO)),
        car(ConnectionStatus.Live, faults = FaultSummary(FaultsStatus.READING, emptyList(), null), ladder = LadderProgress.Running("L1", "Engine", 3, 6, 40)),
        car(ConnectionStatus.Live, ladder = LadderProgress.Running("L2", "Engine", 5, 6, 12)),
        car(ConnectionStatus.Live),
        car(ConnectionStatus.Live, speed = 46, rpm = 2100, stage = DriveStage.DRIVING),
        car(ConnectionStatus.Live, stage = DriveStage.ENDED),
        car(ConnectionStatus.Live, stage = DriveStage.ENDED, truth = report),
    )
}
