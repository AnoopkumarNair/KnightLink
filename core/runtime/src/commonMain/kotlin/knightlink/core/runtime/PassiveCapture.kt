package knightlink.core.runtime

import knightlink.core.adapter.Elm327Driver
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.common.emit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** How the session talks to the car: it asks (polls, as always) or only listens to what the car broadcasts on its own. */
public enum class CaptureMode {
    ACTIVE,
    PASSIVE,
}

/** One 11-bit identifier group that the adapter is told to listen to: the identifiers whose bits under [mask] equal those of [filter]. */
public data class IdGroup(val filter: Int, val mask: Int) {
    init {
        require(filter in ID_RANGE && mask in ID_RANGE) { "an 11-bit filter and mask are 0..7FF" }
    }

    /** The first and last identifier that passes, e.g. `100-1FF`. */
    public val label: String
        get() {
            val low = filter and mask
            val high = low or (mask.inv() and ID_MAX)
            return "${hex3(low)}-${hex3(high)}"
        }

    public companion object {
        private const val ID_MAX = 0x7FF
        private val ID_RANGE = 0..ID_MAX
        private const val GROUP_COUNT = 8
        private const val GROUP_SIZE = 0x100

        /** 000-0FF, 100-1FF ... 700-7FF. */
        public val DEFAULT: List<IdGroup> = List(GROUP_COUNT) { IdGroup(it * GROUP_SIZE, 0x700) }
    }
}

/** What was heard in one slice of listening. [ids] maps an identifier to how many frames carried it. */
public data class SliceSummary(
    val label: String,
    val seconds: Double,
    val frames: Int,
    val ids: Map<Int, Int>,
    val bufferFull: Int,
) {
    /**
     * The line for the connection log: `passive: group 100-1FF 3.0 s, 412 frames, 9 ids (1A0:120 1B4:60 ...), buffer-full 0`. The busiest
     * [top] identifiers are listed, ties by identifier. Identifiers and counts only: no frame contents, so no VIN.
     */
    public fun note(top: Int = PassiveCapture.Config.DEFAULT_TOP_IDS): String {
        val ranked = ids.entries.sortedWith(compareBy({ -it.value }, { it.key }))
        val shown = ranked.take(top).joinToString(" ") { "${hex3(it.key)}:${it.value}" }
        val more = if (ranked.size > top) " +${ranked.size - top} more" else ""
        return "passive: $label ${tenths(seconds)} s, $frames frames, ${ids.size} ids ($shown$more), buffer-full $bufferFull"
    }
}

private fun hex3(id: Int): String = id.toString(16).uppercase().padStart(3, '0')

private fun tenths(value: Double): String {
    val scaled = kotlin.math.round(value * 10).toLong()
    return "${scaled / 10}.${scaled % 10}"
}

/**
 * Reads the adapter's monitor output (`ATMA`) tolerantly. A frame line is an 11-bit identifier of three hex digits and 0 to 8 data bytes,
 * with or without spaces (`1A0 12 34 56 78 00 00 00 00` or `1A01234567800000000`). Everything else is ignored: `SEARCHING...`, `STOPPED`,
 * a line cut in half (an odd number of digits, or more than eight data bytes), noise. `BUFFER FULL` is counted.
 */
public object MonitorLines {
    private const val ID_DIGITS = 3
    private const val MAX_DATA_BYTES = 8

    /** The identifier of a frame line, or null when [line] is not one. */
    public fun frameId(line: String): Int? {
        val compact = line.filterNot { it.isWhitespace() }
        if (compact.length < ID_DIGITS || (compact.length - ID_DIGITS) % 2 != 0 || compact.length > ID_DIGITS + 2 * MAX_DATA_BYTES) return null
        if (!compact.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) return null
        return compact.substring(0, ID_DIGITS).toInt(16)
    }

    /** Whether [line] says the adapter's buffer overflowed. */
    public fun isBufferFull(line: String): Boolean = line.filterNot { it.isWhitespace() }.uppercase().startsWith("BUFFERFULL")

    /** Whether the adapter did not know the command (`?`). */
    public fun isUnknown(line: String): Boolean = line.trim() == "?"

    /** Summarises [lines] heard over [seconds] under [label]. */
    public fun summarise(label: String, seconds: Double, lines: List<String>): SliceSummary {
        val ids = HashMap<Int, Int>()
        var bufferFull = 0
        var frames = 0
        for (line in lines) {
            val id = frameId(line)
            if (id != null) {
                frames++
                ids[id] = (ids[id] ?: 0) + 1
            } else if (isBufferFull(line)) {
                bufferFull++
            }
        }
        return SliceSummary(label, seconds, frames, ids, bufferFull)
    }
}

/**
 * Passive capture: for as long as it runs the adapter only listens to what the car broadcasts on the OBD port and KnightLink sends nothing
 * to the vehicle (see [knightlink.core.adapter.ReadOnlyPolicy.passiveViolation] for the commands this path may use). The frames are
 * recorded raw by the byte-level recorder like all adapter traffic, and each slice is summarised into one [SessionEvents.PASSIVE_SLICE]
 * note; nothing is decoded here. The aim is to compare the broadcast with the polled data and the driver's mode taps on a PC afterwards.
 *
 * Unfiltered traffic is often more than the Bluetooth link carries (the adapter then says `BUFFER FULL`), so listening goes in slices: first
 * an unfiltered one, then one per [Config.groups] entry, over and over. Every wait is limited, and [run] ends as soon as `until` completes.
 *
 * Not thread-safe: one coroutine runs it. [driver] must belong to the session that is not polling meanwhile.
 */
public class PassiveCapture(
    private val driver: Elm327Driver,
    private val config: Config = Config(),
    private val events: SessionEventSink = SessionEventSink.None,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /**
     * @property sliceDuration how long one slice listens
     * @property groups the identifier groups listened to after the unfiltered slice, in order
     * @property stopTimeout how long the adapter may take to answer the stop character with its prompt
     * @property idleDelay the pause between two cycles while the bus is silent (ignition off): the cycle then repeats slowly
     * @property silenceLimit the bus that stays silent this long ends the capture ([Ended.BUS_SILENT]), so the session goes back to polling and
     *   finds out whether the car sleeps
     * @property settleDelay the pause before the first write: a poll that was cancelled may leave the adapter waiting for the car, and the first
     *   byte sent then interrupts it; one adapter timeout period (`ATST`) lets it finish
     * @property topIds how many identifiers a slice note lists
     */
    public data class Config(
        val sliceDuration: Duration = 3.seconds,
        val groups: List<IdGroup> = IdGroup.DEFAULT,
        val stopTimeout: Duration = 2.seconds,
        val idleDelay: Duration = 5.seconds,
        val silenceLimit: Duration = 2.minutes,
        val topIds: Int = DEFAULT_TOP_IDS,
        val settleDelay: Duration = 300.milliseconds,
    ) {
        init {
            require(sliceDuration > Duration.ZERO) { "sliceDuration must be positive" }
            require(stopTimeout > Duration.ZERO) { "stopTimeout must be positive" }
            require(idleDelay > Duration.ZERO) { "idleDelay must be positive" }
            require(silenceLimit > Duration.ZERO) { "silenceLimit must be positive" }
            require(topIds >= 1) { "topIds must be at least 1" }
        }

        public companion object {
            public const val DEFAULT_TOP_IDS: Int = 12
        }
    }

    /** Why [run] returned. */
    public enum class Ended {
        /** `until` completed: the caller wants to poll again. */
        REQUESTED,

        /** Nothing was heard for [Config.silenceLimit]. */
        BUS_SILENT,

        /** The adapter cannot capture safely: it refused a setup command, uses automatic protocol search, or does not know `ATMA`. */
        UNSUPPORTED,
    }

    private var filtersWork = true

    /**
     * Listens, slice after slice, until [until] completes ([Ended.REQUESTED]), the bus has been silent for [Config.silenceLimit]
     * ([Ended.BUS_SILENT]) or the adapter cannot monitor ([Ended.UNSUPPORTED]). Leaves the adapter as the capture set it: call [restore].
     *
     * @throws knightlink.core.adapter.AdapterException when the adapter stops answering; a lost link throws its disconnected exception
     */
    public suspend fun run(until: Deferred<Unit>): Ended {
        delay(config.settleDelay)
        // Under automatic protocol search ATMA makes the adapter send requests of its own: only a fixed protocol is safe.
        val protocol = driver.passiveCommand("ATDPN").firstOrNull { it.isNotBlank() }?.trim()?.uppercase()
        if (protocol == null || protocol.length != 1 || protocol[0] !in "123456789BC") {
            note(
                "passive: the protocol is not fixed (the adapter said '${protocol.orEmpty().take(
                    MAX_QUOTED,
                )}'), so listening could make it send requests; not capturing",
            )
            return Ended.UNSUPPORTED
        }
        // Silent monitoring first: it must be exactly OK, or the adapter may transmit acknowledgements. The others make it print raw frames compactly.
        for (command in listOf("ATCSM1", "ATCAF0", "ATH1", "ATS0")) {
            val reply = driver.passiveCommand(command)
            if (reply.size != 1 || !reply[0].trim().equals("OK", ignoreCase = true)) {
                note("passive: the adapter did not accept $command (it said '${reply.joinToString(" ").take(MAX_QUOTED)}'); not capturing")
                return Ended.UNSUPPORTED
            }
        }
        var silentSince: TimeMark? = null
        while (!until.isCompleted) {
            val all = slice(null, until)
            if (all == null) return Ended.UNSUPPORTED
            if (all.frames == 0 && !until.isCompleted) {
                val since = silentSince ?: timeSource.markNow().also {
                    note("passive: the bus is silent (ignition off?); listening again every ${config.idleDelay}")
                }
                silentSince = since
                if (since.elapsedNow() >= config.silenceLimit) return Ended.BUS_SILENT
                withTimeoutOrNull(config.idleDelay) { until.await() }
                continue
            }
            silentSince = null
            if (!filtersWork) continue
            for (group in config.groups) {
                if (until.isCompleted) break
                if (slice(group, until) == null) return Ended.UNSUPPORTED
            }
        }
        return Ended.REQUESTED
    }

    /**
     * Puts the adapter back to what the polling script expects: automatic receive, automatic formatting, the ordinary OBD filter. When the
     * adapter does not take one of those, it is initialised again with its script instead (no probe, no reset of what the session learned).
     */
    public suspend fun restore() {
        val restored = listOf("ATAR", "ATCAF1", "ATCM7F8", "ATCF7E8").all { accepted(it) }
        if (!restored) driver.reinitialize("restoring the adapter after passive capture")
    }

    /** One slice; null when the adapter does not know `ATMA`. A group whose filter the adapter refuses switches the groups off for good. */
    private suspend fun slice(group: IdGroup?, until: Deferred<Unit>): SliceSummary? {
        val label = if (group == null) "all ids" else "group ${group.label}"
        // Mask 000 lets every identifier through, so the unfiltered slice is unfiltered whatever the adapter's default is.
        val filtered = accepted("ATCM" + hex3(group?.mask ?: 0)) && accepted("ATCF" + hex3(group?.filter ?: 0))
        if (group != null && !filtered) {
            filtersWork = false
            note("passive: the adapter does not take ATCF/ATCM; only the unfiltered slice is captured")
            return SliceSummary(label, 0.0, 0, emptyMap(), 0)
        }
        val started = timeSource.markNow()
        val lines = driver.monitor(config.sliceDuration, config.stopTimeout, until)
        if (lines.any(MonitorLines::isUnknown) && lines.none { MonitorLines.frameId(it) != null }) {
            note("passive: the adapter does not know ATMA, so it cannot capture")
            return null
        }
        val summary = MonitorLines.summarise(label, started.elapsedNow().inWholeMilliseconds / MILLIS_PER_SECOND, lines)
        note(summary.note(config.topIds))
        return summary
    }

    /** True when the adapter said OK. */
    private suspend fun accepted(command: String): Boolean = driver.passiveCommand(command).any { it.trim().equals("OK", ignoreCase = true) }

    private suspend fun note(detail: String) {
        try {
            events.emit(SessionEvents.PASSIVE_SLICE, detail)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A broken log must not end a capture.
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
        const val MAX_QUOTED = 20
    }
}
