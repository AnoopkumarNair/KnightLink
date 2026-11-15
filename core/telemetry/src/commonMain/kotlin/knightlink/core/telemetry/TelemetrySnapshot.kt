package knightlink.core.telemetry

import kotlin.time.Duration

/** A [Reading] and when it was taken, as time since the session started (not wall-clock time). */
public data class TimedReading(val reading: Reading, val at: Duration)

/**
 * The latest value of every signal seen so far. Immutable: [with] returns a new snapshot, so a snapshot
 * handed to the UI or the rules engine never changes under the reader's feet and can be compared by content.
 *
 * Readings are kept per (ECU, signal): two ECUs reporting the same signal do not overwrite each other.
 * Every reading carries its own timestamp, so a consumer can tell a fresh value from one that stopped updating.
 */
public class TelemetrySnapshot private constructor(private val entries: Map<Key, TimedReading>) {
    private data class Key(val ecu: String?, val signalId: String)

    /** Number of (ECU, signal) pairs held. */
    public val size: Int get() = entries.size

    public fun isEmpty(): Boolean = entries.isEmpty()

    /** Every reading held, in the order the pairs were first seen. */
    public val readings: List<TimedReading> get() = entries.values.toList()

    /**
     * The latest reading of [signalId].
     *
     * @param ecu id of the ECU that reported it. When null, the most recently taken reading from any ECU
     *   is returned (the first one seen if several share a timestamp), which is what a caller wants when
     *   the definitions do not tie the signal to one ECU.
     */
    public fun reading(signalId: String, ecu: String? = null): TimedReading? =
        if (ecu != null) {
            entries[Key(ecu, signalId)]
        } else {
            entries.entries.filter { it.key.signalId == signalId }.maxByOrNull { it.value.at }?.value
        }

    /** Shorthand for the numeric value of [reading]. */
    public fun value(signalId: String, ecu: String? = null): Double? = reading(signalId, ecu)?.reading?.signal?.value

    /** A snapshot in which [readings] taken at [at] replace the older readings of the same (ECU, signal) pairs. */
    public fun with(readings: List<Reading>, at: Duration): TelemetrySnapshot {
        if (readings.isEmpty()) return this
        // Copy on write: a snapshot has a few dozen entries, and sharing none of them with the old one keeps it immutable.
        val updated = LinkedHashMap(entries)
        for (reading in readings) updated[Key(reading.ecu, reading.signal.id)] = TimedReading(reading, at)
        return TelemetrySnapshot(updated)
    }

    override fun equals(other: Any?): Boolean = other is TelemetrySnapshot && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "TelemetrySnapshot(${entries.size} readings)"

    public companion object {
        public val EMPTY: TelemetrySnapshot = TelemetrySnapshot(emptyMap())
    }
}
