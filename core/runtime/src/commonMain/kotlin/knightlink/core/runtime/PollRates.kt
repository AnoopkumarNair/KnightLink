package knightlink.core.runtime

import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * What polling achieved lately.
 *
 * @property requestsPerSecond requests sent to the adapter per second; the bus load, whatever number of signals they carried
 * @property batchedShare the share of those requests that asked for several PIDs at once, 0 to 1
 * @property signals readings per second of each signal id, as the achieved update rate: a signal asked 6 times a second has 6.0
 * @property window how many seconds the numbers are averaged over
 */
public class PollRates(
    public val requestsPerSecond: Double,
    public val batchedShare: Double,
    public val signals: Map<String, Double>,
    public val window: Duration,
) {
    /**
     * One line for the recording: the totals, then the signals grouped by their rate, fastest group first (`6.7/s a b c; 1.1/s d e`).
     * Contains no vehicle identity.
     *
     * @param singleOnly request ids (hex) of the ECUs that failed batches and are asked one PID at a time
     */
    public fun summary(singleOnly: List<String> = emptyList()): String = buildString {
        append("last ${window.inWholeSeconds} s: ${format(requestsPerSecond)} requests/s, ${(batchedShare * PERCENT).toInt()} % batched")
        if (singleOnly.isNotEmpty()) append(", batching off for ${singleOnly.joinToString(" ")}")
        signals.entries.groupBy({ format(it.value) }, { it.key }).entries
            .sortedByDescending { (_, ids) -> signals.getValue(ids.first()) }
            .forEach { (rate, ids) -> append("; ").append(rate).append("/s ").append(ids.sorted().joinToString(" ")) }
    }

    public companion object {
        public val EMPTY: PollRates = PollRates(0.0, 0.0, emptyMap(), Duration.ZERO)

        private const val PERCENT = 100

        /** One decimal below 10, none from 10 up: a rate of 0.17 shows as 0.2, 24.3 as 24. */
        private fun format(value: Double): String {
            val tenths = (value * 10).roundToLong()
            return if (value >= 10) value.roundToLong().toString() else "${tenths / 10}.${tenths % 10}"
        }
    }
}

/**
 * Counts requests and readings with their times, and turns the last few seconds into [PollRates]. Not thread-safe: one coroutine
 * records and reads.
 *
 * @param keep how long events are remembered; the longest window that can be asked for
 */
internal class RateMeter(private val start: Duration, private val keep: Duration = 30.seconds) {
    private class Request(val at: Duration, val pids: Int)

    private val requests = ArrayDeque<Request>()
    private val readings = HashMap<String, ArrayDeque<Duration>>()

    fun request(at: Duration, pids: Int) {
        requests.addLast(Request(at, pids))
    }

    fun reading(signalId: String, at: Duration) {
        readings.getOrPut(signalId) { ArrayDeque() }.addLast(at)
    }

    /** The rates over the last [window] (or since [start], while that is shorter), as of [now]. */
    fun rates(now: Duration, window: Duration): PollRates {
        forget(now)
        val span = minOf(window, now - start).coerceAtLeast(MIN_SPAN)
        val from = now - span
        val recent = requests.filter { it.at >= from }
        val signals = readings.mapValues { (_, times) -> times.count { it >= from } / span.toDouble(DurationUnit.SECONDS) }.filterValues { it > 0.0 }
        val batched = recent.count { it.pids > 1 }
        return PollRates(
            requestsPerSecond = recent.size / span.toDouble(DurationUnit.SECONDS),
            batchedShare = if (recent.isEmpty()) 0.0 else batched.toDouble() / recent.size,
            signals = signals,
            window = span,
        )
    }

    private fun forget(now: Duration) {
        val cutoff = now - keep
        while (requests.isNotEmpty() && requests.first().at < cutoff) requests.removeFirst()
        val iterator = readings.iterator()
        while (iterator.hasNext()) {
            val times = iterator.next().value
            while (times.isNotEmpty() && times.first() < cutoff) times.removeFirst()
            if (times.isEmpty()) iterator.remove()
        }
    }

    private companion object {
        val MIN_SPAN: Duration = 1.seconds
    }
}
