package knightlink.core.runtime.twin

import knightlink.core.runtime.BodyCheckMemory
import knightlink.core.runtime.DidHistory
import knightlink.core.runtime.FuelMemory
import knightlink.core.runtime.GearModel
import knightlink.core.runtime.VehicleMemory
import knightlink.core.scan.FaultKind
import knightlink.core.scan.FaultReport
import knightlink.core.scan.ScanResult
import knightlink.core.signals.Provenance
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.VehicleIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Per-domain coverage state for a car's digital twin ("what is collected vs. what is missing").
 */
@Serializable
public enum class DomainState {
    COLLECTED,
    PARTIAL,
    NOT_AVAILABLE,
    NOT_TRIED,
}

/** Diagnostic/functional domains tracked by [TwinCoverageReport]. */
@Serializable
public enum class TwinDomain {
    IDENTITY,
    ENGINE_BASICS,
    FUEL,
    DISTANCE_ODOMETER,
    FAULTS_READINESS,
    GEARBOX,
    DRIVE_MODE,
    TYRES,
    BODY_UNITS,
    EMISSIONS,
}

@Serializable
public data class TwinCoverageEntry(
    val domain: TwinDomain,
    val state: DomainState,
    val reason: String? = null,
)

@Serializable
public data class TwinCoverageReport(
    val entries: Map<TwinDomain, TwinCoverageEntry> = emptyMap(),
) {
    public fun stateOf(domain: TwinDomain): DomainState = entries[domain]?.state ?: DomainState.NOT_TRIED
}

@Serializable
public data class TwinIdentity(
    val wmi: String? = null,
    val make: String? = null,
    val model: String? = null,
    val modelYear: Int? = null,
    val fuelType: String? = null,
    val transmission: String? = null,
    val definitionId: String? = null,
    val probeFingerprint: String? = null,
)

@Serializable
public data class TwinCapabilities(
    val protocol: String? = null,
    val units: List<String> = emptyList(),
    val supportedPidsByEcu: Map<String, List<String>> = emptyMap(),
    val answeredDidsByEcu: Map<String, List<String>> = emptyMap(),
    val refusedDidsByEcu: Map<String, List<String>> = emptyMap(),
)

@Serializable
public data class TwinSignalEntry(
    val signalId: String,
    val ecu: String? = null,
    val provenance: Provenance,
    val unit: String = "",
    val lastValue: Double? = null,
    val lastUpdatedMs: Long? = null,
)

@Serializable
public data class TwinUserFacts(
    val odometerKm: Int? = null,
    val odometerUpdatedMs: Long? = null,
    val tankCapacityL: Double? = null,
    val fuelPricePerLitre: Double? = null,
    val name: String? = null,
)

@Serializable
public data class TwinLearnedModels(
    val gear: GearModel = GearModel(),
    val fuel: FuelMemory = FuelMemory(),
    val body: BodyCheckMemory = BodyCheckMemory(),
    val odometerScale: Double? = null,
    val discovery: Map<String, DidHistory> = emptyMap(),
)

@Serializable
public data class TwinDriveSummary(
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val distanceKm: Double? = null,
    val fuelLitres: Double? = null,
    val costRupees: Double? = null,
    val idleMinutes: Double? = null,
    val ended: Boolean = true,
)

@Serializable
public data class TwinFaultRecord(
    val code: String,
    val ecu: String? = null,
    val confirmed: Boolean = false,
    val pending: Boolean = false,
    val recordedAtMs: Long,
)

@Serializable
public data class TwinHistory(
    val sessionCount: Int = 0,
    val lastSessionAtMs: Long? = null,
    val recentDrives: List<TwinDriveSummary> = emptyList(),
    val recentFaults: List<TwinFaultRecord> = emptyList(),
)

/**
 * Versioned digital twin document for a single vehicle instance, keyed by [key] (salted SHA-256 of VIN + calibration ids).
 * Never holds a raw VIN or precise GPS coordinates.
 */
@Serializable
public data class CarTwin(
    val schemaVersion: Int = SCHEMA_VERSION,
    val key: String,
    val identity: TwinIdentity = TwinIdentity(),
    val capabilities: TwinCapabilities = TwinCapabilities(),
    val signals: Map<String, TwinSignalEntry> = emptyMap(),
    val userFacts: TwinUserFacts = TwinUserFacts(),
    val learned: TwinLearnedModels = TwinLearnedModels(),
    val coverage: TwinCoverageReport = TwinCoverageReport(),
    val history: TwinHistory = TwinHistory(),
    val probe: CapabilityReport? = null,
    val scan: ScanResult? = null,
) {
    public fun toJson(): String = writer.encodeToString(serializer(), this)

    public companion object {
        public const val SCHEMA_VERSION: Int = 1
        public const val MAX_RECENT_DRIVES: Int = 50
        public const val MAX_RECENT_FAULTS: Int = 100

        private val writer = Json { encodeDefaults = true }
        private val reader = Json { ignoreUnknownKeys = true }

        /** @return parsed [CarTwin] or null if corrupted or from an unsupported future schema. */
        public fun fromJson(text: String): CarTwin? = try {
            reader.decodeFromString(serializer(), text).takeIf { it.schemaVersion <= SCHEMA_VERSION }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Persistent store for [CarTwin] documents, one per vehicle key. */
public interface CarTwinStore {
    public fun lastKey(): String?

    public fun load(key: String): CarTwin?

    public fun save(twin: CarTwin)
}

/** In-memory [CarTwinStore] for tests and ephemeral sessions. */
public class InMemoryCarTwinStore : CarTwinStore {
    private val items = LinkedHashMap<String, String>()
    private var last: String? = null

    override fun lastKey(): String? = last

    override fun load(key: String): CarTwin? = items[key]?.let(CarTwin::fromJson)

    override fun save(twin: CarTwin) {
        items[twin.key] = twin.toJson()
        last = twin.key
    }
}

/** Typed read API over a [CarTwin] for downstream features (Drive Truth, Insights, AI tools). */
public interface TwinApi {
    public fun twin(): CarTwin?

    public fun identity(): TwinIdentity?

    public fun coverage(): TwinCoverageReport

    public fun signal(signalId: String): TwinSignalEntry?

    public fun signals(): Collection<TwinSignalEntry>

    public fun recentDrives(limit: Int = 10): List<TwinDriveSummary>

    public fun recentFaults(limit: Int = 20): List<TwinFaultRecord>

    public fun userFacts(): TwinUserFacts

    public fun learnedModels(): TwinLearnedModels
}

/** [TwinApi] backed by a live or stored [CarTwin] snapshot provider. */
public class LocalTwinApi(private val provider: () -> CarTwin?) : TwinApi {
    override fun twin(): CarTwin? = provider()

    override fun identity(): TwinIdentity? = provider()?.identity

    override fun coverage(): TwinCoverageReport = provider()?.coverage ?: TwinCoverageReport()

    override fun signal(signalId: String): TwinSignalEntry? = provider()?.signals?.get(signalId)

    override fun signals(): Collection<TwinSignalEntry> = provider()?.signals?.values.orEmpty()

    override fun recentDrives(limit: Int): List<TwinDriveSummary> =
        provider()?.history?.recentDrives?.takeLast(limit.coerceAtLeast(0)).orEmpty()

    override fun recentFaults(limit: Int): List<TwinFaultRecord> =
        provider()?.history?.recentFaults?.takeLast(limit.coerceAtLeast(0)).orEmpty()

    override fun userFacts(): TwinUserFacts = provider()?.userFacts ?: TwinUserFacts()

    override fun learnedModels(): TwinLearnedModels = provider()?.learned ?: TwinLearnedModels()
}

/** Builds or updates a [CarTwin] from [VehicleMemory], probe reports, faults, and completed drives. */
public object CarTwinBuilder {
    public fun fromVehicleMemory(memory: VehicleMemory): CarTwin {
        val redacted = memory.probe.redacted()
        val wmi = redacted.ecus.firstNotNullOfOrNull { it.vin?.take(3) }
        val units = redacted.ecus.map { it.requestId }
        val pids = redacted.ecus.associate { it.requestId to it.supportedPids.sorted() }
        val answeredDids = memory.scan?.ecus?.associate { ecu ->
            ecu.requestId to ecu.identity.map { id -> id.did }.sorted()
        }.orEmpty()
        return CarTwin(
            key = memory.key,
            identity = TwinIdentity(wmi = wmi, model = memory.model),
            capabilities = TwinCapabilities(
                units = units,
                supportedPidsByEcu = pids,
                answeredDidsByEcu = answeredDids,
            ),
            learned = TwinLearnedModels(
                gear = memory.gear,
                fuel = memory.fuel,
                body = memory.body,
                discovery = memory.discovery,
            ),
            probe = redacted,
            scan = memory.scan,
        ).let { twin ->
            twin.copy(coverage = evaluateCoverage(twin))
        }
    }

    public fun mergeIdentity(
        twin: CarTwin,
        identity: VehicleIdentity?,
        definitionId: String?,
        fuelType: String? = twin.identity.fuelType,
        transmission: String? = twin.identity.transmission,
    ): CarTwin = twin.copy(
        identity = twin.identity.copy(
            make = identity?.make ?: twin.identity.make,
            model = identity?.model ?: twin.identity.model,
            modelYear = identity?.year ?: twin.identity.modelYear,
            fuelType = fuelType,
            transmission = transmission,
            definitionId = definitionId ?: twin.identity.definitionId,
        ),
        capabilities = twin.capabilities.copy(
            protocol = identity?.protocol ?: twin.capabilities.protocol,
        ),
    )

    public fun recordDrive(twin: CarTwin, drive: TwinDriveSummary): CarTwin {
        val drives = (twin.history.recentDrives + drive).takeLast(CarTwin.MAX_RECENT_DRIVES)
        return twin.copy(history = twin.history.copy(recentDrives = drives))
    }

    public fun recordFaults(twin: CarTwin, report: FaultReport, recordedAtMs: Long): CarTwin {
        if (report.faults.isEmpty()) return twin
        val entries = report.faults.map { entry ->
            TwinFaultRecord(
                code = entry.code,
                ecu = entry.ecu,
                confirmed = entry.kind == FaultKind.CONFIRMED,
                pending = entry.kind == FaultKind.PENDING,
                recordedAtMs = recordedAtMs,
            )
        }
        val merged = (twin.history.recentFaults + entries).takeLast(CarTwin.MAX_RECENT_FAULTS)
        return twin.copy(history = twin.history.copy(recentFaults = merged))
    }

    public fun evaluateCoverage(twin: CarTwin): TwinCoverageReport {
        val entries = LinkedHashMap<TwinDomain, TwinCoverageEntry>()
        val id = twin.identity
        entries[TwinDomain.IDENTITY] = TwinCoverageEntry(
            TwinDomain.IDENTITY,
            if (!id.make.isNullOrBlank() && !id.model.isNullOrBlank()) DomainState.COLLECTED else DomainState.PARTIAL,
        )
        val hasEnginePids = twin.capabilities.supportedPidsByEcu.values.any { it.isNotEmpty() }
        entries[TwinDomain.ENGINE_BASICS] = TwinCoverageEntry(
            TwinDomain.ENGINE_BASICS,
            if (hasEnginePids) DomainState.COLLECTED else DomainState.NOT_TRIED,
        )
        val hasFuel = twin.signals.keys.any { it.startsWith("fuel.") } || twin.learned.fuel.fills.isNotEmpty()
        entries[TwinDomain.FUEL] = TwinCoverageEntry(
            TwinDomain.FUEL,
            if (hasFuel) DomainState.COLLECTED else DomainState.PARTIAL,
        )
        entries[TwinDomain.DISTANCE_ODOMETER] = TwinCoverageEntry(
            TwinDomain.DISTANCE_ODOMETER,
            if (twin.userFacts.odometerKm != null || twin.learned.odometerScale != null) DomainState.COLLECTED else DomainState.NOT_TRIED,
        )
        entries[TwinDomain.FAULTS_READINESS] = TwinCoverageEntry(
            TwinDomain.FAULTS_READINESS,
            if (twin.scan != null || twin.history.recentFaults.isNotEmpty()) DomainState.COLLECTED else DomainState.NOT_TRIED,
        )
        entries[TwinDomain.GEARBOX] = TwinCoverageEntry(
            TwinDomain.GEARBOX,
            if (twin.learned.gear.clusters.isNotEmpty() || twin.signals.keys.any { it.startsWith("transmission.") }) {
                DomainState.COLLECTED
            } else {
                DomainState.NOT_TRIED
            },
        )
        entries[TwinDomain.BODY_UNITS] = TwinCoverageEntry(
            TwinDomain.BODY_UNITS,
            when {
                twin.learned.body.units.isNotEmpty() && twin.learned.body.units.values.all { it.done } -> DomainState.COLLECTED
                twin.learned.body.units.isNotEmpty() -> DomainState.PARTIAL
                else -> DomainState.NOT_TRIED
            },
        )
        return TwinCoverageReport(entries)
    }
}
