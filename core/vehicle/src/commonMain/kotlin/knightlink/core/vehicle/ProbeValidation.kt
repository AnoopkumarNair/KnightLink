package knightlink.core.vehicle

import kotlin.coroutines.cancellation.CancellationException

/**
 * Whether the vehicle on the other end of [link] still looks like the one this report describes, found out cheaply: every ECU of the report
 * is asked `01 00` (the first support bitmap, one frame) and must answer with the bitmap the report holds. A silent ECU, a different bitmap
 * or a failed request all mean no: the caller then probes in full. Nothing but service 01 is sent.
 */
public suspend fun CapabilityReport.stillMatches(link: ProbeLink): Boolean {
    if (ecus.isEmpty()) return false
    for (ecu in ecus) {
        val response = try {
            link.request(ecu.requestCanId, byteArrayOf(SERVICE_LIVE_DATA.toByte(), 0x00), expectedResponses = 1)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        val bitmap = response.positiveReply(SERVICE_LIVE_DATA, 0x00, from = ecu.responseCanId)?.bitmap() ?: return false
        val first = decodeSupportRange(0, bitmap).pids
        val known = ecu.supportedPids.map { it.toInt(16) }.filter { it < PIDS_PER_RANGE }.toSet()
        if (first != known) return false
    }
    return true
}
