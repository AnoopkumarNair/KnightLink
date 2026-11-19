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

/** Discovery items are asked slowly: what changes there is worked out afterwards, and the vehicle's own signals have the budget. */
private val FASTEST: Duration = 5.seconds
private val SLOWEST: Duration = 8.seconds

/** The poll item id of data identifier [did] (four hex digits) of the control unit asked at [requestId]. */
public fun discoveryItemId(requestId: Int, did: String): String = "${requestId.toString(16).uppercase()}:22${did.uppercase()}"

private val MODE01_MIRROR = 0xF400..0xF4FF

/** A reply of `62 <did 2> <data>` fits one CAN frame when the data is at most four bytes. */
private const val SINGLE_FRAME_DATA_BYTES = 4

/**
 * How often each discovery item that changes is asked: every five seconds when there are few, and slower as they become many, so that all of
 * them together take at most about [DISCOVERY_REQUESTS_PER_SECOND] requests a second of the adapter's budget (between five and eight seconds each).
 */
public fun discoveryPeriod(count: Int): Duration = (count / DISCOVERY_REQUESTS_PER_SECOND * 1000).milliseconds.coerceIn(FASTEST, SLOWEST)

/**
 * The data identifiers a scan found, as poll items to ask while driving, so that what they mean can be worked out afterwards from how their
 * bytes change next to speed and revs. Each is asked on its own (service 22, never in a batch), and its reply is recorded as it is: nothing
 * here decodes it, because nothing says what it means.
 *
 * Which identifiers, from the plan: every identifier of a target that has no limit, and for the others at most
 * [knightlink.core.scan.SweepTarget.pollLimit] of those whose data is at most
 * [knightlink.core.scan.SweepTarget.pollMaxBytes] bytes; [MAX_DISCOVERY_ITEMS] in all. Identifiers F400-F4FF are left out (they mirror Mode 01 PIDs).
 * The items named in [constantItems] (ids as `7E0:22E001`: values that never changed while polling) are asked once per session; the others
 * share the period [discoveryPeriod].
 */
public fun discoveryPollItems(result: ScanResult, plan: DidSweepPlan, constantItems: Set<String> = emptySet()): List<PollItem> {
    val chosen = ArrayList<Pair<Int, DidAnswer>>()
    for (target in plan.targets) {
        val sweep = result.sweeps.firstOrNull { it.requestId.equals(target.requestId, ignoreCase = true) } ?: continue
        val candidates = sweep.positive
            // F4xx identifiers repeat Mode 01 PIDs, which are polled as such.
            .filter { answer -> answer.did.toInt(16) !in MODE01_MIRROR }
            .filter { answer -> target.pollMaxBytes?.let { answer.hex.length / 2 <= it && answer.hex.isNotEmpty() } ?: true }
            .let { list -> target.pollLimit?.let { list.take(it) } ?: list }
        candidates.forEach { chosen += target.requestCanId to it }
    }
    val items = chosen.take(MAX_DISCOVERY_ITEMS)
    val changing = items.count { (requestId, answer) -> discoveryItemId(requestId, answer.did) !in constantItems }
    val period = discoveryPeriod(changing)
    return items.map { (requestId, answer) ->
        val did = answer.did.toInt(16)
        val id = discoveryItemId(requestId, answer.did)
        PollItem(
            id = id,
            requestId = requestId,
            payload = byteArrayOf(0x22, (did shr 8).toByte(), did.toByte()),
            period = if (id in constantItems) Duration.INFINITE else period,
            // The count digit only for a reply that fits one frame; the reply's length is known from the scan, and an unreadable one is asked whole.
            expectedResponses = if (answer.hex.isNotEmpty() && answer.hex.hexToBytes().size <= SINGLE_FRAME_DATA_BYTES) 1 else null,
            // No data length: an item without one cannot be batched, which is what a service 22 request must not be.
            dataLength = null,
        )
    }
}
