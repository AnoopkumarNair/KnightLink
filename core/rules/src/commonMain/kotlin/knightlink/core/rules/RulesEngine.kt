package knightlink.core.rules

import knightlink.core.telemetry.TelemetrySnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The deterministic safety rules for one drive (ADR 0004): telemetry in, alerts and answers out. No clock, no randomness, no coroutines; every
 * decision is a function of the snapshots and times it was given, so a recorded drive replays to the same alerts.
 *
 * It is a state machine (regeneration progress, hysteresis, "stopped since", what was already spoken), so use **one instance per session** and
 * call [evaluate] on every new snapshot and also on a tick of about one second, so that time-based transitions (a regeneration result expiring,
 * soot staying high, a cool-down finishing, a stale reading ageing out) happen without new data. [now] must not go backwards.
 *
 * Unknown is a first-class result. A reading that is old, stamped in the future, out of range or from a signal that is not standard or verified
 * is not an input at all; a rule without inputs neither raises nor clears an alert, and an answer says it cannot tell.
 */
public class RulesEngine(private val config: RulesConfig, trust: SignalTrust) {
    private val gate = InputGate(trust)
    private val manager = AlertManager(config.alerts)
    private val tracker = config.dpf?.let { DpfTracker(it, (config.signals.soot?.maxAgeMs ?: 0L).milliseconds) }
    private var lastNow: Duration? = null
    private var stoppedSince: Duration? = null
    private var sootHighSince: Duration? = null
    private var interrupted: RegenRecord? = null
    private var interruptedAt: Duration = Duration.ZERO
    private var adblue = Level.OK
    private var coolantHigh = false

    private enum class Level { OK, LOW, CRITICAL }

    /**
     * @param now time since the session started, the timebase of the snapshot's readings
     * @param link how the connection is doing; with the engine off or the link down, alerts are not repeated
     * @throws IllegalArgumentException if [now] is earlier than in the previous call
     */
    public fun evaluate(snapshot: TelemetrySnapshot, now: Duration, link: LinkStatus = LinkStatus.LIVE): RulesState {
        lastNow?.let { require(now >= it) { "now must not go backwards: $now after $it" } }
        lastNow = now
        val signals = config.signals
        val status = gate.read(snapshot, signals.regenStatus, now)
        val soot = gate.read(snapshot, signals.soot, now)
        val rpm = gate.read(snapshot, signals.rpm, now)
        val speed = gate.read(snapshot, signals.speed, now)
        val egt = gate.readMax(snapshot, signals.egt, now)
        val coolant = gate.read(snapshot, signals.coolant, now)
        val adblueLevel = gate.read(snapshot, signals.adblueLevel, now)

        val engineOff = link == LinkStatus.NO_IGNITION || (rpm != null && rpm.value < config.engine.runningRpm)
        val running = !engineOff && rpm != null
        val stopped = running && config.cooldown != null && speed != null && speed.value <= config.cooldown.stoppedSpeedKmh
        stoppedSince = if (stopped) stoppedSince ?: now else null

        val dpf = tracker?.update(now, engineOff, status, soot) ?: DpfStatus(DpfPhase.UNKNOWN, soot?.value, null, null)
        val live = link == LinkStatus.LIVE

        tracker?.justFinished?.let { record ->
            if (record.outcome == RegenOutcome.COMPLETED) {
                manager.raiseEvent(PhraseKeys.REGEN_COMPLETED, AlertPriority.INFO, completedPhrase(record), now)
            } else {
                interrupted = record
                interruptedAt = now
            }
        }

        val conditions = LinkedHashMap<String, Condition>()
        if (config.dpf != null && tracker != null) {
            conditions[PhraseKeys.REGEN_ACTIVE] = regenActive(dpf, tracker.statusFresh, live)
            conditions[PhraseKeys.REGEN_INTERRUPTED] = interruptedCondition(config.dpf, dpf, soot, now, live)
            conditions[PhraseKeys.SOOT_HIGH] = sootHigh(config.dpf, dpf, tracker.statusFresh, soot, now, live)
        }
        config.adblue?.let { adblueConditions(it, adblueLevel, live, conditions) }
        config.coolant?.let { conditions[PhraseKeys.COOLANT_HIGH] = coolantCondition(it, coolant, live) }

        val result = manager.update(now, conditions)

        val stale = buildSet {
            if (rpm == null) add("rpm")
            if (config.dpf != null) {
                if (status == null) add("regenStatus")
                if (soot == null) add("soot")
            }
            if (config.cooldown != null) {
                if (egt == null) add("egt")
                if (speed == null) add("speed")
            }
            if (config.adblue != null && adblueLevel == null) add("adblueLevel")
            if (config.coolant != null && coolant == null) add("coolant")
        }
        val checkable = (config.dpf != null && status != null) ||
            (config.adblue != null && adblueLevel != null) ||
            (config.coolant != null && coolant != null)
        val advice = switchOffAdvice(dpf, rpm, engineOff, egt, now, status != null)
        return RulesState(now, dpf, advice, result.alerts, result.announcements, stale, checkable)
    }

    private fun regenActive(dpf: DpfStatus, statusFresh: Boolean, live: Boolean): Condition =
        when {
            dpf.phase == DpfPhase.REGEN_ACTIVE && statusFresh -> Condition.Active(AlertPriority.ADVISORY, Phrase(PhraseKeys.REGEN_ACTIVE), live, live)
            // Remembered as active but the status is not being read: not evidence enough to raise it, and not to clear it either.
            dpf.phase == DpfPhase.REGEN_ACTIVE || dpf.phase == DpfPhase.UNKNOWN -> Condition.Unknown
            else -> Condition.Inactive
        }

    private fun interruptedCondition(dpfConfig: DpfConfig, dpf: DpfStatus, soot: Fresh?, now: Duration, live: Boolean): Condition {
        val record = interrupted ?: return Condition.Inactive
        // A new regeneration is the answer to the warning. Soot back below normal also ends it, but not before the result has been shown for its
        // hold time: a regeneration that stops at a modest soot load would otherwise clear the very moment it was raised.
        val soothed = soot != null && soot.value < dpfConfig.sootRisingClearPct && now - interruptedAt >= dpfConfig.resultHoldMs.milliseconds
        if (dpf.phase == DpfPhase.REGEN_ACTIVE || soothed) {
            interrupted = null
            return Condition.Inactive
        }
        val phrase = when {
            record.outcome == RegenOutcome.ENGINE_OFF && record.sootAfter == null -> Phrase(PhraseKeys.REGEN_INTERRUPTED_ENGINE_OFF_UNKNOWN_SOOT)
            record.outcome == RegenOutcome.ENGINE_OFF -> Phrase(
                PhraseKeys.REGEN_INTERRUPTED_ENGINE_OFF,
                mapOf(
                    "soot" to checkNotNull(record.sootAfter).rounded(),
                ),
            )
            else -> Phrase(PhraseKeys.REGEN_INTERRUPTED, mapOf("soot" to checkNotNull(record.sootAfter).rounded()))
        }
        // Said once even with the engine off (that is when the driver is still there to hear it); repeated only while the link is live.
        return Condition.Active(AlertPriority.WARNING, phrase, speakable = live || record.outcome == RegenOutcome.ENGINE_OFF, reannounce = live)
    }

    private fun sootHigh(dpfConfig: DpfConfig, dpf: DpfStatus, statusFresh: Boolean, soot: Fresh?, now: Duration, live: Boolean): Condition {
        // "No regeneration is running" has to be known, not assumed, and "continuously high" needs an unbroken run of fresh readings.
        if (soot == null || !statusFresh || dpf.phase == DpfPhase.REGEN_ACTIVE) {
            sootHighSince = null
            return if (statusFresh && dpf.phase == DpfPhase.REGEN_ACTIVE) Condition.Inactive else Condition.Unknown
        }
        if (soot.value < dpfConfig.sootHighPct) {
            sootHighSince = null
            return Condition.Inactive
        }
        val since = sootHighSince ?: now.also { sootHighSince = it }
        // Not high for long enough yet: no reason to raise it, and none to clear an alert that is already up.
        if (now - since < dpfConfig.sootHighHoldMs.milliseconds) return Condition.Unknown
        return Condition.Active(AlertPriority.WARNING, Phrase(PhraseKeys.SOOT_HIGH, mapOf("soot" to soot.value.rounded())), live, live)
    }

    private fun adblueConditions(adblueConfig: AdblueConfig, level: Fresh?, live: Boolean, into: MutableMap<String, Condition>) {
        if (level == null) {
            into[PhraseKeys.ADBLUE_LOW] = Condition.Unknown
            into[PhraseKeys.ADBLUE_CRITICAL] = Condition.Unknown
            return
        }
        val value = level.value
        val margin = adblueConfig.clearMarginPct
        adblue = when (adblue) {
            Level.OK -> when {
                value <= adblueConfig.criticalPct -> Level.CRITICAL
                value <= adblueConfig.lowPct -> Level.LOW
                else -> Level.OK
            }
            Level.LOW -> when {
                value <= adblueConfig.criticalPct -> Level.CRITICAL
                value > adblueConfig.lowPct + margin -> Level.OK
                else -> Level.LOW
            }
            Level.CRITICAL -> when {
                value <= adblueConfig.criticalPct + margin -> Level.CRITICAL
                value > adblueConfig.lowPct + margin -> Level.OK
                else -> Level.LOW
            }
        }
        val phrase = { key: String -> Phrase(key, mapOf("level" to value.rounded())) }
        // Critical replaces low: only one of the two is ever active.
        into[PhraseKeys.ADBLUE_LOW] =
            if (adblue == Level.LOW) Condition.Active(AlertPriority.WARNING, phrase(PhraseKeys.ADBLUE_LOW), live, live) else Condition.Inactive
        into[PhraseKeys.ADBLUE_CRITICAL] =
            if (adblue == Level.CRITICAL) Condition.Active(AlertPriority.CRITICAL, phrase(PhraseKeys.ADBLUE_CRITICAL), live, live) else Condition.Inactive
    }

    private fun coolantCondition(coolantConfig: CoolantConfig, coolant: Fresh?, live: Boolean): Condition {
        if (coolant == null) return Condition.Unknown
        coolantHigh = if (coolantHigh) coolant.value >= coolantConfig.highC - coolantConfig.clearMarginC else coolant.value >= coolantConfig.highC
        return if (coolantHigh) {
            Condition.Active(AlertPriority.CRITICAL, Phrase(PhraseKeys.COOLANT_HIGH, mapOf("temp" to coolant.value.rounded())), live, live)
        } else {
            Condition.Inactive
        }
    }

    private fun completedPhrase(record: RegenRecord): Phrase {
        val after = checkNotNull(record.sootAfter).rounded()
        val before = record.sootBefore ?: return Phrase(PhraseKeys.REGEN_COMPLETED_AFTER_ONLY, mapOf("sootAfter" to after))
        return Phrase(PhraseKeys.REGEN_COMPLETED, mapOf("sootBefore" to before.rounded(), "sootAfter" to after))
    }

    /**
     * Order matters and is conservative: a regeneration in progress beats everything, including not knowing the engine speed; the engine being off
     * is only believed from a fresh reading or the ignition state; and "safe" needs every configured check to have fresh data behind it.
     */
    private fun switchOffAdvice(dpf: DpfStatus, rpm: Fresh?, engineOff: Boolean, egt: Fresh?, now: Duration, statusKnown: Boolean): SwitchOffAdvice {
        if (engineOff) return SwitchOffAdvice.EngineOff
        if (dpf.phase == DpfPhase.REGEN_ACTIVE) return SwitchOffAdvice.NotYet(SwitchOffReason.REGEN_ACTIVE, tracker?.remaining())
        val missing = buildSet {
            if (rpm == null) add("rpm")
            if (config.dpf != null && !statusKnown) add("regenStatus")
            if (config.cooldown != null && egt == null) add("egt")
        }
        if (missing.isNotEmpty()) return SwitchOffAdvice.Unknown(missing)
        val cooldown = config.cooldown
        if (cooldown != null && egt != null && egt.value >= cooldown.egtHotC) {
            val idle = cooldown.cooldownIdleMs.milliseconds
            val left = idle - (stoppedSince?.let { now - it } ?: Duration.ZERO)
            return SwitchOffAdvice.NotYet(SwitchOffReason.HOT_EXHAUST, left.takeIf { it > Duration.ZERO })
        }
        return SwitchOffAdvice.Safe
    }
}
