package knightlink.core.rules

import knightlink.core.signals.Provenance
import knightlink.core.signals.SignalValue
import knightlink.core.telemetry.Reading
import knightlink.core.telemetry.TelemetrySnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal val trustEverything = SignalTrust { _, _ -> Provenance.STANDARD }

internal fun testConfig(
    adblueEcu: String? = null,
    dpf: DpfConfig? = DpfConfig(
        regenActiveMask = 2,
        sootRisingPct = 80.0,
        sootRisingClearPct = 75.0,
        sootHighPct = 100.0,
        sootHighHoldMs = 120_000,
        completedSootPct = 30.0,
        regenEndConfirmReadings = 2,
        resultHoldMs = 120_000,
    ),
    cooldown: CooldownConfig? = CooldownConfig(egtHotC = 300.0, cooldownIdleMs = 120_000, stoppedSpeedKmh = 0.0),
    adblue: AdblueConfig? = AdblueConfig(lowPct = 10.0, criticalPct = 3.0, clearMarginPct = 2.0),
    coolant: CoolantConfig? = CoolantConfig(highC = 110.0, clearMarginC = 3.0),
    egt: List<SignalRef> = listOf(SignalRef("egt1", 5000)),
): RulesConfig = RulesConfig(
    schemaVersion = 1,
    id = "rules/test",
    title = "test",
    vehicle = "vehicles/test",
    signals = SignalsConfig(
        regenStatus = SignalRef("regen", 5000),
        soot = SignalRef("soot", 5000),
        egt = egt,
        rpm = SignalRef("rpm", 3000),
        speed = SignalRef("speed", 3000),
        coolant = SignalRef("coolant", 30_000),
        adblueLevel = SignalRef("adblue", 30_000, ecu = adblueEcu),
    ),
    engine = EngineConfig(runningRpm = 300.0),
    alerts = testAlertsConfig(),
    dpf = dpf,
    cooldown = cooldown,
    adblue = adblue,
    coolant = coolant,
)

internal fun testAlertsConfig(
    critical: Long = 120_000,
    warning: Long = 900_000,
    advisory: Long = 0,
    info: Long = 0,
    quiet: Long = 60_000,
    gap: Long = 3_000,
    lifetime: Long = 120_000,
): AlertsConfig = AlertsConfig(ReannounceConfig(critical, warning, advisory, info), quiet, gap, lifetime)

/** One snapshot-building, engine-driving rig: [tick] stamps what it is given with the time of the call, like the session does. */
internal class Rig(config: RulesConfig = testConfig(), trust: SignalTrust = trustEverything) {
    val engine = RulesEngine(config, trust)
    var snapshot: TelemetrySnapshot = TelemetrySnapshot.EMPTY
    val spoken = ArrayList<Pair<Duration, Alert>>()

    fun feed(at: Duration, vararg values: Pair<String, Double>, ecu: String? = null, inRange: Boolean = true) {
        snapshot = snapshot.with(values.map { (id, value) -> Reading(ecu, SignalValue(id, value, "u", inRange)) }, at)
    }

    fun eval(now: Duration, link: LinkStatus = LinkStatus.LIVE): RulesState =
        engine.evaluate(snapshot, now, link).also { state -> state.announcements.forEach { spoken += now to it } }

    /** Readings taken at [seconds], then an evaluation at [seconds]. */
    fun tick(seconds: Int, vararg values: Pair<String, Double>, link: LinkStatus = LinkStatus.LIVE): RulesState {
        val now = seconds.seconds
        feed(now, *values)
        return eval(now, link)
    }

    fun spokenIds(): List<String> = spoken.map { it.second.id }
}

/** A healthy car, with the given values replaced. */
internal fun car(
    soot: Double = 20.0,
    status: Double = 0.0,
    rpm: Double = 800.0,
    speed: Double = 40.0,
    egt: Double = 150.0,
    coolant: Double = 90.0,
    adblue: Double = 50.0,
): Array<Pair<String, Double>> = arrayOf(
    "soot" to soot,
    "regen" to status,
    "rpm" to rpm,
    "speed" to speed,
    "egt1" to egt,
    "coolant" to coolant,
    "adblue" to adblue,
)
