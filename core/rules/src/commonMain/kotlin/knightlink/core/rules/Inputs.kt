package knightlink.core.rules

import knightlink.core.telemetry.TelemetrySnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A reading the rules may use: its value and when it was taken. */
internal class Fresh(val value: Double, val at: Duration)

/**
 * The one gate between telemetry and the rules. A reading passes only when it is
 * - from a trusted definition (standard or verified, never inferred or unknown),
 * - inside the definition's plausible range,
 * - not stamped in the future, and
 * - no older than the input's maximum age.
 *
 * The snapshot keeps the last value of every signal while the link is down or the car is asleep, so age is the only thing that tells a live value
 * from one that stopped updating. Anything that fails is simply unknown: the rules never fill the gap with a guess.
 */
internal class InputGate(private val trust: SignalTrust) {
    fun read(snapshot: TelemetrySnapshot, ref: SignalRef?, now: Duration): Fresh? {
        if (ref == null) return null
        if (!trust.provenance(ref.id, ref.ecu).isTrusted()) return null
        val timed = snapshot.reading(ref.id, ref.ecu) ?: return null
        val signal = timed.reading.signal
        if (!signal.inRange || !signal.value.isFinite()) return null
        if (timed.at > now || now - timed.at > ref.maxAgeMs.milliseconds) return null
        return Fresh(signal.value, timed.at)
    }

    /** The hottest of several sensors that are fresh; null when none is. */
    fun readMax(snapshot: TelemetrySnapshot, refs: List<SignalRef>, now: Duration): Fresh? =
        refs.mapNotNull { read(snapshot, it, now) }.maxByOrNull { it.value }
}
