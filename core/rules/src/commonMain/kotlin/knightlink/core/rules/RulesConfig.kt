package knightlink.core.rules

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Highest rules-file schema this build understands. Newer files are rejected, not half-read. */
public const val SUPPORTED_RULES_SCHEMA_VERSION: Int = 1

/** A rules file that is malformed, uses a newer schema, or holds values that make no sense. */
public class RulesConfigException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Which signal feeds a rule input, and how old its reading may be. Ages are per input because a coolant temperature that is 20 s old is still
 * true while an engine speed that old is not.
 *
 * @property ecu when set, only the reading from this control unit counts; otherwise the newest reading of the signal from any unit
 */
@Serializable
public data class SignalRef(val id: String, val maxAgeMs: Long, val ecu: String? = null, val notes: String? = null)

/** The signals the rules read. [rpm] and [speed] are always needed; any other role left out turns the rules that need it off (they report unknown). */
@Serializable
public data class SignalsConfig(
    val rpm: SignalRef,
    val speed: SignalRef,
    val regenStatus: SignalRef? = null,
    val soot: SignalRef? = null,
    val egt: List<SignalRef> = emptyList(),
    val coolant: SignalRef? = null,
    val adblueLevel: SignalRef? = null,
    val notes: String? = null,
)

/** What counts as the engine running. Below this the engine is treated as off. */
@Serializable
public data class EngineConfig(val runningRpm: Double, val notes: String? = null)

/**
 * Particulate filter rules. Percentages are of soot load.
 *
 * @property regenActiveMask the regeneration status counts as active when any of these bits is set
 * @property sootRisingPct soot at or above this is "rising"; it is back to normal only below [sootRisingClearPct]
 * @property sootHighPct soot at or above this, for [sootHighHoldMs] without a regeneration, raises the warning
 * @property completedSootPct a regeneration that ends with soot at or below this is complete; above it, it stopped early
 * @property regenEndConfirmReadings how many distinct "not active" readings in a row end a regeneration (one glitch must not)
 * @property resultHoldMs how long "completed" or "interrupted" is shown before the phase falls back to normal
 * @property maxRemainingEstimateMs an estimated time left above this is not believable and is not reported
 */
@Serializable
public data class DpfConfig(
    val regenActiveMask: Int,
    val sootRisingPct: Double,
    val sootRisingClearPct: Double,
    val sootHighPct: Double,
    val sootHighHoldMs: Long,
    val completedSootPct: Double,
    val regenEndConfirmReadings: Int,
    val resultHoldMs: Long,
    val maxRemainingEstimateMs: Long = 3_600_000,
    val notes: String? = null,
)

/**
 * The switch-off guard's exhaust rule: hot exhaust ([egtHotC] or more) means "idle a while before switching off".
 *
 * @property cooldownIdleMs how long the car has to stand still with the engine running before hot exhaust is considered cooled enough
 * @property stoppedSpeedKmh at or below this speed the car counts as standing still
 */
@Serializable
public data class CooldownConfig(val egtHotC: Double, val cooldownIdleMs: Long, val stoppedSpeedKmh: Double, val notes: String? = null)

/** Reductant level thresholds, in percent. The warning clears only [clearMarginPct] above the level that raised it. */
@Serializable
public data class AdblueConfig(val lowPct: Double, val criticalPct: Double, val clearMarginPct: Double, val notes: String? = null)

/** Coolant temperature threshold. The warning clears only [clearMarginC] below the temperature that raised it. */
@Serializable
public data class CoolantConfig(val highC: Double, val clearMarginC: Double, val notes: String? = null)

/** Re-announcement interval per priority, in milliseconds. 0 means never: the alert is spoken once. */
@Serializable
public data class ReannounceConfig(val critical: Long, val warning: Long, val advisory: Long, val info: Long) {
    internal fun forPriority(priority: AlertPriority): Long =
        when (priority) {
            AlertPriority.CRITICAL -> critical
            AlertPriority.WARNING -> warning
            AlertPriority.ADVISORY -> advisory
            AlertPriority.INFO -> info
        }
}

/**
 * How alerts are spoken.
 *
 * @property quietAfterClearMs an alert that comes back sooner than this after it cleared is flapping: it becomes active but is not spoken again
 * @property minGapMs at most one spoken batch per this interval; critical alerts ignore it
 * @property eventLifetimeMs how long an event alert (something that happened, not a condition that holds) stays in the list
 */
@Serializable
public data class AlertsConfig(
    val reannounceMs: ReannounceConfig,
    val quietAfterClearMs: Long,
    val minGapMs: Long,
    val eventLifetimeMs: Long,
    val notes: String? = null,
)

/**
 * Every threshold, signal and timing the rules use, for one vehicle (ADR 0002: this is data, not code). A section left out turns its rules off.
 */
@Serializable
public data class RulesConfig(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    /** Id of the vehicle definition these rules are written for. */
    val vehicle: String,
    val signals: SignalsConfig,
    val engine: EngineConfig,
    val alerts: AlertsConfig,
    val dpf: DpfConfig? = null,
    val cooldown: CooldownConfig? = null,
    val adblue: AdblueConfig? = null,
    val coolant: CoolantConfig? = null,
    val notes: String? = null,
) {
    /** @throws RulesConfigException if a value makes no sense */
    public fun validate() {
        fun check(condition: Boolean, message: () -> String) {
            if (!condition) throw RulesConfigException("$id: ${message()}")
        }

        fun ref(role: String, ref: SignalRef) {
            check(ref.id.isNotBlank()) { "signals.$role: id is empty" }
            check(ref.ecu == null || ref.ecu.isNotBlank()) { "signals.$role: ecu is empty" }
            check(ref.maxAgeMs in 1..MAX_AGE_LIMIT_MS) { "signals.$role: maxAgeMs must be 1..$MAX_AGE_LIMIT_MS" }
        }
        check(schemaVersion in 1..SUPPORTED_RULES_SCHEMA_VERSION) {
            "schema $schemaVersion is not supported; this build supports 1..$SUPPORTED_RULES_SCHEMA_VERSION"
        }
        check(id.isNotBlank()) { "id is empty" }
        check(vehicle.isNotBlank()) { "vehicle is empty" }
        signals.regenStatus?.let { ref("regenStatus", it) }
        signals.soot?.let { ref("soot", it) }
        signals.egt.forEachIndexed { index, it -> ref("egt[$index]", it) }
        ref("rpm", signals.rpm)
        ref("speed", signals.speed)
        signals.coolant?.let { ref("coolant", it) }
        signals.adblueLevel?.let { ref("adblueLevel", it) }

        check(engine.runningRpm > 0 && engine.runningRpm <= MAX_RPM) { "engine.runningRpm must be in 1..$MAX_RPM" }

        dpf?.let {
            check(it.regenActiveMask in 1..255) { "dpf.regenActiveMask must be 1..255" }
            check(it.sootRisingClearPct in 0.0..100.0 && it.sootRisingPct in 0.0..100.0 && it.sootRisingClearPct < it.sootRisingPct) {
                "dpf: sootRisingClearPct must be below sootRisingPct, both within 0..100"
            }
            check(it.sootHighPct in 0.0..100.0 && it.sootHighPct >= it.sootRisingPct) { "dpf.sootHighPct must be within sootRisingPct..100" }
            check(it.completedSootPct in 0.0..100.0) { "dpf.completedSootPct must be within 0..100" }
            check(it.sootHighHoldMs >= 0 && it.resultHoldMs >= 0) { "dpf: sootHighHoldMs and resultHoldMs must not be negative" }
            check(it.regenEndConfirmReadings >= 1) { "dpf.regenEndConfirmReadings must be at least 1" }
            check(it.maxRemainingEstimateMs > 0) { "dpf.maxRemainingEstimateMs must be positive" }
        }
        cooldown?.let {
            check(it.egtHotC in 0.0..MAX_EGT_C) { "cooldown.egtHotC must be within 0..$MAX_EGT_C" }
            check(it.cooldownIdleMs >= 0) { "cooldown.cooldownIdleMs must not be negative" }
            check(it.stoppedSpeedKmh >= 0) { "cooldown.stoppedSpeedKmh must not be negative" }
        }
        adblue?.let {
            check(it.criticalPct >= 0 && it.criticalPct < it.lowPct && it.lowPct <= 100) { "adblue: need 0 <= criticalPct < lowPct <= 100" }
            check(it.clearMarginPct >= 0) { "adblue.clearMarginPct must not be negative" }
        }
        coolant?.let {
            check(it.highC in MIN_COOLANT_HIGH_C..MAX_COOLANT_HIGH_C) { "coolant.highC must be within $MIN_COOLANT_HIGH_C..$MAX_COOLANT_HIGH_C" }
            check(it.clearMarginC >= 0 && it.clearMarginC < it.highC) { "coolant.clearMarginC must be within 0 until highC" }
        }
        val re = alerts.reannounceMs
        check(re.critical >= 0 && re.warning >= 0 && re.advisory >= 0 && re.info >= 0) { "alerts.reannounceMs must not be negative" }
        check(alerts.quietAfterClearMs >= 0 && alerts.minGapMs >= 0 && alerts.eventLifetimeMs >= 0) { "alerts: timings must not be negative" }
    }

    public companion object {
        // Strict on purpose: an unknown key is a typo or a newer schema, and a silently ignored threshold is a silently dead rule.
        private val json = Json { ignoreUnknownKeys = false }

        private const val MAX_AGE_LIMIT_MS = 600_000L
        private const val MAX_RPM = 20_000.0
        private const val MAX_EGT_C = 1_500.0
        private const val MIN_COOLANT_HIGH_C = 50.0
        private const val MAX_COOLANT_HIGH_C = 200.0

        /** @throws RulesConfigException if the text is not a valid rules file */
        public fun parse(text: String): RulesConfig {
            val config = try {
                json.decodeFromString(serializer(), text)
            } catch (e: IllegalArgumentException) {
                throw RulesConfigException("Invalid rules file: ${e.message}", e)
            }
            config.validate()
            return config
        }
    }
}
