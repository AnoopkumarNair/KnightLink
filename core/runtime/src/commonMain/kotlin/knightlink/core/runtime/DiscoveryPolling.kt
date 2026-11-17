package knightlink.core.runtime

import knightlink.core.common.hexToBytes
import knightlink.core.scan.DidAnswer
import knightlink.core.scan.DidSweepPlan
import knightlink.core.scan.ScanResult
import knightlink.core.scheduler.PollItem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Most identifiers polled for discovery in all, however many a scan found. */
public const val MAX_DISCOVERY_ITEMS: Int = 60

/** Requests a second the discovery items together ask for at most; the rest of the adapter's speed stays with the vehicle's own signals. */
private const val DISCOVERY_REQUESTS_PER_SECOND = 8.0

private val FASTEST: Duration = 1.seconds
private val SLOWEST: Duration = 8.seconds

/** A reply of `62 <did 2> <data>` fits one CAN frame when the data is at most four bytes. */
private const val SINGLE_FRAME_DATA_BYTES = 4

/**
 * How often each discovery item is asked: one a second when there are few, and slower as they become many, so that all of them together
 * take about [DISCOVERY_REQUESTS_PER_SECOND] requests a second of the adapter's budget (between one and eight seconds each).
 */
public fun discoveryPeriod(count: Int): Duration = (count / DISCOVERY_REQUESTS_PER_SECOND * 1000).milliseconds.coerceIn(FASTEST, SLOWEST)

/**
 * The data identifiers a scan found, as poll items to ask while driving, so that what they mean can be worked out afterwards from how their
 * bytes change next to speed and revs. Each is asked on its own (service 22, never in a batch), and its reply is recorded as it is: nothing
 * here decodes it, because nothing says what it means.
 *
 * Which identifiers, from the plan: every identifier of a target that has no limit, and for the others at most
 * [knightlink.core.scan.SweepTarget.pollLimit] of those whose data is at most
 * [knightlink.core.scan.SweepTarget.pollMaxBytes] bytes; [MAX_DISCOVERY_ITEMS] in all. All have the same period, [discoveryPeriod].
 */
public fun discoveryPollItems(result: ScanResult, plan: DidSweepPlan): List<PollItem> {
    val chosen = ArrayList<Pair<Int, DidAnswer>>()
    for (target in plan.targets) {
        val sweep = result.sweeps.firstOrNull { it.requestId.equals(target.requestId, ignoreCase = true) } ?: continue
        val candidates = sweep.positive
            .filter { answer -> target.pollMaxBytes?.let { answer.hex.length / 2 <= it && answer.hex.isNotEmpty() } ?: true }
            .let { list -> target.pollLimit?.let { list.take(it) } ?: list }
        candidates.forEach { chosen += target.requestCanId to it }
    }
    val items = chosen.take(MAX_DISCOVERY_ITEMS)
    val period = discoveryPeriod(items.size)
    return items.map { (requestId, answer) ->
        val did = answer.did.toInt(16)
        PollItem(
            id = "${requestId.toString(16).uppercase()}:22${answer.did}",
            requestId = requestId,
            payload = byteArrayOf(0x22, (did shr 8).toByte(), did.toByte()),
            period = period,
            // The count digit only for a reply that fits one frame; the reply's length is known from the scan, and an unreadable one is asked whole.
            expectedResponses = if (answer.hex.isNotEmpty() && answer.hex.hexToBytes().size <= SINGLE_FRAME_DATA_BYTES) 1 else null,
            // No data length: an item without one cannot be batched, which is what a service 22 request must not be.
            dataLength = null,
        )
    }
}
