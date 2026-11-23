package knightlink.app.session

import knightlink.core.scan.FaultKind
import knightlink.core.scan.ReadinessSummary
import knightlink.core.vehicle.FuelMethod
import knightlink.core.vehicle.UNKNOWN_MAKE

/** The areas the coverage report speaks about, in the order the screen lists them. */
enum class CoverageDomain {
    IDENTITY,
    ENGINE_BASICS,
    FUEL,
    DISTANCE,
    FAULTS,
    GEARBOX,
    DRIVE_MODE,
    TYRES,
    BODY_UNITS,
    EMISSIONS,
}

/** What became of one area. Never "bad": an area the car does not offer is a fact about the car, not a fault. */
enum class CoverageState { COLLECTED, PARTIAL, NOT_AVAILABLE, NOT_TRIED }

/** A short reason beside the state; the screen maps each to a sentence. */
enum class CoverageNote {
    MODEL_MISSING,
    FROM_YOU,
    CALCULATED,
    UNCONFIRMED,
    APPROXIMATE,
    NEEDS_FULL_PROFILE,
    ASKED_NOTHING_ANSWERED,
    STOPPED_EARLY,
    IN_PROGRESS,
    DEEP_SCAN_OFF,
    NO_READINESS,
}

data class CoverageItem(val domain: CoverageDomain, val state: CoverageState, val note: CoverageNote? = null)

private val ENGINE_SIGNALS = listOf("vehicle.speed", "engine.rpm", "engine.coolant_temp", "engine.load_pct")
private const val ENGINE_ENOUGH = 3

/**
 * The coverage report: one state per area, from what the session found. Pure, so the rules are tested on the JVM.
 *
 * "Not available on this car" is only said after the car was asked (the probe ran, or the question was put and refused or unanswered); before
 * that an area is "not tried yet".
 */
fun coverageReport(state: UiState): List<CoverageItem> {
    val ids = state.liveValues.mapTo(HashSet()) { it.signalId }
    val probed = state.vehicle != null
    val identified = state.identity != null
    fun absent() = if (probed) CoverageState.NOT_AVAILABLE else CoverageState.NOT_TRIED
    fun item(domain: CoverageDomain, s: CoverageState, note: CoverageNote? = null) = CoverageItem(domain, s, note)

    val identity = state.identity
    val idItem = when {
        identity == null -> item(CoverageDomain.IDENTITY, CoverageState.NOT_TRIED)
        identity.make != UNKNOWN_MAKE && identity.year != null && identity.model != null -> item(CoverageDomain.IDENTITY, CoverageState.COLLECTED)
        identity.make != UNKNOWN_MAKE || identity.year != null || identity.model != null ->
            item(CoverageDomain.IDENTITY, CoverageState.PARTIAL, if (identity.model == null) CoverageNote.MODEL_MISSING else null)
        else -> item(CoverageDomain.IDENTITY, CoverageState.NOT_AVAILABLE)
    }

    val engineCount = ENGINE_SIGNALS.count { it in ids }
    val engine = when {
        engineCount >= ENGINE_ENOUGH -> item(CoverageDomain.ENGINE_BASICS, CoverageState.COLLECTED)
        engineCount > 0 -> item(CoverageDomain.ENGINE_BASICS, CoverageState.PARTIAL)
        else -> item(CoverageDomain.ENGINE_BASICS, absent())
    }

    val method = state.vehicle?.fuelMethod ?: FuelMethod.UNKNOWN
    val hasLevel = "fuel.level_pct" in ids
    val hasRate = method == FuelMethod.DIRECT_RATE || method == FuelMethod.MAF || "fuel.rate_lph" in ids
    val fuel = when {
        hasLevel && hasRate -> item(CoverageDomain.FUEL, CoverageState.COLLECTED)
        hasLevel || hasRate -> item(CoverageDomain.FUEL, CoverageState.PARTIAL)
        method == FuelMethod.SPEED_DENSITY -> item(CoverageDomain.FUEL, CoverageState.PARTIAL, CoverageNote.APPROXIMATE)
        else -> item(CoverageDomain.FUEL, absent())
    }

    val typedOdometer = state.dashboard.odometerStartKm != null || (state.garage as? GarageStatus.Known)?.profile?.odometerKm != null
    val distance = when {
        ids.any { it.contains("odometer") } -> item(CoverageDomain.DISTANCE, CoverageState.COLLECTED)
        typedOdometer -> item(CoverageDomain.DISTANCE, CoverageState.PARTIAL, CoverageNote.FROM_YOU)
        else -> item(CoverageDomain.DISTANCE, absent())
    }

    val faults = when (state.faults.status) {
        FaultsStatus.READY ->
            if (state.faults.readiness != null) {
                item(CoverageDomain.FAULTS, CoverageState.COLLECTED)
            } else {
                item(CoverageDomain.FAULTS, CoverageState.PARTIAL, CoverageNote.NO_READINESS)
            }
        FaultsStatus.FAILED -> item(CoverageDomain.FAULTS, CoverageState.NOT_AVAILABLE)
        FaultsStatus.READING, FaultsStatus.NOT_READ -> item(
            CoverageDomain.FAULTS,
            CoverageState.NOT_TRIED,
            CoverageNote.IN_PROGRESS.takeIf {
                state.faults.status ==
                    FaultsStatus.READING
            },
        )
    }

    val gearIds = ids.filter { it.contains("gear") }
    val gearbox = when {
        gearIds.any { !it.contains("calc") } -> item(CoverageDomain.GEARBOX, CoverageState.COLLECTED)
        gearIds.isNotEmpty() -> item(CoverageDomain.GEARBOX, CoverageState.PARTIAL, CoverageNote.CALCULATED)
        else -> item(CoverageDomain.GEARBOX, absent())
    }

    val driveMode = when {
        !identified -> item(CoverageDomain.DRIVE_MODE, CoverageState.NOT_TRIED)
        !state.knownVehicle -> item(CoverageDomain.DRIVE_MODE, CoverageState.NOT_AVAILABLE, CoverageNote.NEEDS_FULL_PROFILE)
        state.drive?.mode != null -> item(CoverageDomain.DRIVE_MODE, CoverageState.PARTIAL, CoverageNote.FROM_YOU)
        else -> item(CoverageDomain.DRIVE_MODE, CoverageState.NOT_TRIED)
    }

    val tyres = state.tyres
    val tyreItem = when {
        tyres == null -> item(CoverageDomain.TYRES, if (identified) CoverageState.NOT_AVAILABLE else CoverageState.NOT_TRIED)
        tyres.readings.isNotEmpty() -> item(CoverageDomain.TYRES, CoverageState.PARTIAL, if (tyres.unconfirmed) CoverageNote.UNCONFIRMED else null)
        tyres.listening -> item(CoverageDomain.TYRES, CoverageState.NOT_TRIED, CoverageNote.IN_PROGRESS)
        else -> item(CoverageDomain.TYRES, CoverageState.NOT_AVAILABLE)
    }

    val body = bodyUnits(state)

    val emissions = when {
        state.vehicle?.hasDpfData == true ||
            ids.any {
                it.startsWith("dpf.") || it.startsWith("def.")
            } -> item(CoverageDomain.EMISSIONS, CoverageState.COLLECTED)
        else -> item(CoverageDomain.EMISSIONS, absent())
    }

    return listOf(idItem, engine, fuel, distance, faults, gearbox, driveMode, tyreItem, body, emissions)
}

/** The other control units: the parked body scan on a car with a profile, the learning ladder on any other. */
private fun bodyUnits(state: UiState): CoverageItem {
    val domain = CoverageDomain.BODY_UNITS
    if (state.identity == null) return CoverageItem(domain, CoverageState.NOT_TRIED)
    if (state.knownVehicle) {
        val progress = if (state.bodyScan != BodyScanProgress.Idle) state.bodyScan else state.bodyResult
        return when (progress) {
            is BodyScanProgress.Complete -> CoverageItem(domain, CoverageState.COLLECTED)
            is BodyScanProgress.Partial -> CoverageItem(domain, CoverageState.PARTIAL)
            is BodyScanProgress.Running -> CoverageItem(domain, CoverageState.NOT_TRIED, CoverageNote.IN_PROGRESS)
            is BodyScanProgress.Skipped -> CoverageItem(domain, CoverageState.NOT_TRIED)
            BodyScanProgress.Idle -> CoverageItem(domain, CoverageState.NOT_TRIED)
        }
    }
    val progress = if (state.ladder != LadderProgress.Idle) state.ladder else state.ladderResult
    return when (progress) {
        is LadderProgress.Complete ->
            if (progress.answers > 0) {
                CoverageItem(domain, CoverageState.COLLECTED)
            } else {
                CoverageItem(domain, CoverageState.NOT_AVAILABLE, CoverageNote.ASKED_NOTHING_ANSWERED)
            }
        is LadderProgress.Stopped -> CoverageItem(domain, CoverageState.PARTIAL, CoverageNote.STOPPED_EARLY)
        is LadderProgress.Flagged -> CoverageItem(domain, CoverageState.PARTIAL, CoverageNote.STOPPED_EARLY)
        is LadderProgress.Running -> CoverageItem(domain, CoverageState.NOT_TRIED, CoverageNote.IN_PROGRESS)
        LadderProgress.Idle -> CoverageItem(domain, CoverageState.NOT_TRIED, CoverageNote.DEEP_SCAN_OFF.takeIf { !state.deepScan })
    }
}

/** The health verdict the summary leads with. Neutral: it counts what the car reported and says nothing about how serious it is. */
data class HealthVerdict(val kind: Kind, val faultCount: Int, val lampOn: Boolean) {
    enum class Kind { NOT_CHECKED, ALL_CLEAR, CODES_STORED }
}

fun healthVerdict(faults: FaultSummary): HealthVerdict {
    if (faults.status != FaultsStatus.READY) return HealthVerdict(HealthVerdict.Kind.NOT_CHECKED, 0, lampOn = false)
    val lamp = faults.readiness?.milOn == true
    val count = faults.items.size
    return if (count == 0 && !lamp) {
        HealthVerdict(HealthVerdict.Kind.ALL_CLEAR, 0, lampOn = false)
    } else {
        HealthVerdict(HealthVerdict.Kind.CODES_STORED, count, lamp)
    }
}

/** "7 of 8 emission checks ready": [ready] of [total] monitors the car supports that have completed; null when the car reported none. */
data class ReadinessLine(val ready: Int, val total: Int)

fun readinessLine(readiness: ReadinessSummary?): ReadinessLine? {
    val total = readiness?.supported ?: return null
    return if (total == 0) null else ReadinessLine(readiness.complete, total)
}

/** The fault kinds in the order the list shows them: confirmed, pending, permanent. */
val FAULT_KIND_ORDER: List<FaultKind> = listOf(FaultKind.CONFIRMED, FaultKind.PENDING, FaultKind.PERMANENT)
