package knightlink.core.runtime

import knightlink.core.scheduler.PollItem
import knightlink.core.signals.CompiledParameter
import knightlink.core.signals.SignalCatalog
import knightlink.core.vehicle.DefinitionCoverage
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
 * @param period wanted interval per parameter; return null to leave one out
 */
internal fun confirmedPollItems(
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
        )
    }
}
