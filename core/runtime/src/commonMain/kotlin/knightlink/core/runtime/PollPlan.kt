package knightlink.core.runtime

import knightlink.core.protocol.ElmResponse
import knightlink.core.scheduler.DiagnosticRequester
import knightlink.core.scheduler.PollDecision
import knightlink.core.scheduler.PollItem
import knightlink.core.scheduler.PollResult
import knightlink.core.scheduler.PollScheduler
import knightlink.core.scheduler.answeredItems
import knightlink.core.signals.CompiledParameter
import knightlink.core.signals.SignalCatalog
import knightlink.core.telemetry.singleFrameCount
import knightlink.core.vehicle.DefinitionCoverage
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration

private const val SERVICE_LIVE_DATA = 0x01

/** The broadcast address. A poll that used it would be answered by every ECU at once and flood the bus. */
private const val FUNCTIONAL_REQUEST_ID = 0x7DF

/**
 * The requests to repeat for a vehicle, built only from what the probe confirmed.
 *
 * - A parameter the definitions tie to an ECU is asked of that ECU.
 * - A parameter without an ECU is asked of [primaryEcu] if that ECU answers it, otherwise of the answering
 *   ECU with the lowest request id (a stable choice that does not depend on probe order).
 * - A parameter no probed ECU supports is never asked for, so a PID the vehicle does not have costs no bus time.
 * - Every request is physical: the functional address is never used.
 *
 * @param primaryEcu catalog id of the preferred ECU for generic parameters; null prefers none
 * - The poll period of a parameter is up to the caller; the app uses the parameter's tier ([knightlink.core.signals.PollTier]).
 *
 * The app and `tools/cli replay` both build their poll list here, so a recording of one can be replayed by the other.
 *
 * @param period wanted interval per parameter; return null to leave one out
 */
public fun confirmedPollItems(
    catalog: SignalCatalog,
    coverage: DefinitionCoverage,
    primaryEcu: String?,
    period: (CompiledParameter) -> Duration?,
): List<PollItem> {
    val ecuByRequestId = catalog.ecus.associateBy { it.requestCanId }
    val primaryRequestId = catalog.ecus.firstOrNull { it.id == primaryEcu }?.requestCanId

    return coverage.confirmed.mapNotNull { covered ->
        val parameter = catalog.find(SERVICE_LIVE_DATA, covered.pid, covered.catalogEcu) ?: return@mapNotNull null
        val wanted = period(parameter) ?: return@mapNotNull null
        val answering = covered.answeredBy.map { it.toInt(16) }.filter { it != FUNCTIONAL_REQUEST_ID }

        val requestId = if (covered.catalogEcu != null) {
            catalog.ecus.firstOrNull { it.id == covered.catalogEcu }?.requestCanId?.takeIf { it in answering }
        } else {
            primaryRequestId?.takeIf { it in answering } ?: answering.minOrNull()
        } ?: return@mapNotNull null

        val owner = ecuByRequestId[requestId]?.id ?: requestId.toString(16).uppercase()
        PollItem(
            id = "$owner:01${covered.pid}",
            requestId = requestId,
            payload = byteArrayOf(SERVICE_LIVE_DATA.toByte(), covered.pid.toInt(16).toByte()),
            period = wanted,
            // The count digit is only for replies that fit one CAN frame: it would cut a longer reply to its first frame.
            expectedResponses = parameter.singleFrameCount(),
            dataLength = parameter.length,
        )
    }
}

/**
 * Asks every item once, in the order and with the batching the app's first poll round has, and returns what came back (a failed request
 * is a result with an error, not an exception). For tools that need the app's requests without its clock: a replay of an app recording
 * then has an answer for every command.
 *
 * @param maxBatch most PIDs per request, see [PollScheduler]
 */
public suspend fun pollRound(items: List<PollItem>, maxBatch: Int, requester: DiagnosticRequester): List<PollResult> {
    val scheduler = PollScheduler(items, maxBatch = maxBatch)
    val results = ArrayList<PollResult>()
    // Every item is due at the start and, once asked, not due again at the same instant: the loop ends after one round.
    while (true) {
        val poll = scheduler.next(Duration.ZERO) as? PollDecision.Poll ?: break
        var response: ElmResponse? = null
        var error: Throwable? = null
        try {
            response = requester.request(poll.requestId, poll.payload, poll.expectedResponses)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
        val answered = answeredItems(poll, response)
        scheduler.onResult(poll, Duration.ZERO, answered.toSet())
        results += PollResult(poll.item, response, error, Duration.ZERO, Duration.ZERO, poll.items, answered)
    }
    return results
}
