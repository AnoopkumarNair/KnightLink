package knightlink.core.telemetry

import knightlink.core.common.hexToBytes
import knightlink.core.common.toHex
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.scheduler.PollItem
import knightlink.core.signals.CompiledParameter
import knightlink.core.signals.SignalCatalog
import knightlink.core.signals.SignalValue
import kotlin.time.Duration

/** A decoded value together with the ECU that reported it. */
public data class Reading(val ecu: String?, val signal: SignalValue)

/**
 * Turns diagnostic replies into readings using a vehicle's [SignalCatalog].
 *
 * A service 01 reply can answer several PIDs at once (`41 0C <2 bytes> 0D <1 byte> ...`, the answer to `01 0C 0D`): it is cut into PIDs
 * by the lengths the definitions give. A PID without a declared length takes the rest of the reply, so it is read correctly when it is
 * the only one, which is how it is asked.
 */
public class TelemetryDecoder(private val catalog: SignalCatalog) {
    /** @return the readings in [message], or an empty list when the catalog has no definition for it. */
    public fun decode(message: DiagnosticResponse.Positive): List<Reading> {
        val ecu = catalog.ecuByResponseId(message.sourceId)?.id
        if (message.service == SERVICE_LIVE_DATA) return decodeLiveData(message.data, ecu)
        for (pidLength in PID_LENGTHS) {
            if (message.data.size < pidLength) continue
            val pid = message.data.copyOfRange(0, pidLength).toHex()
            val parameter = catalog.find(message.service, pid, ecu) ?: continue
            return parameter.decode(message.data.copyOfRange(pidLength, message.data.size)).map { Reading(ecu, it) }
        }
        return emptyList()
    }

    private fun decodeLiveData(data: ByteArray, ecu: String?): List<Reading> {
        val readings = ArrayList<Reading>()
        var offset = 0
        while (offset < data.size) {
            val parameter = catalog.find(SERVICE_LIVE_DATA, pidAt(data, offset), ecu) ?: break
            var end = data.size
            val declared = parameter.length
            // Cut at the declared length only when what follows is a PID we know; otherwise the length may be wrong and the data is all one reading.
            if (declared != null && offset + 1 + declared < data.size && catalog.find(SERVICE_LIVE_DATA, pidAt(data, offset + 1 + declared), ecu) != null) {
                end = offset + 1 + declared
            }
            parameter.decode(data.copyOfRange(offset + 1, end)).forEach { readings += Reading(ecu, it) }
            offset = end
        }
        return readings
    }

    private fun pidAt(data: ByteArray, offset: Int): String = data.copyOfRange(offset, offset + 1).toHex()

    private companion object {
        const val SERVICE_LIVE_DATA = 0x01

        /** One byte for OBD PIDs, two for UDS data identifiers. */
        val PID_LENGTHS = intArrayOf(1, 2)
    }
}

/**
 * Builds the poll list for a vehicle from its catalog.
 *
 * @param primaryEcu the ECU asked for parameters that the definitions do not tie to one ECU
 * @param period wanted interval per parameter; return null to leave a parameter out
 */
public fun SignalCatalog.pollItems(primaryEcu: String, period: (CompiledParameter) -> Duration?): List<PollItem> {
    val requestIds = ecus.associate { it.id to it.requestCanId }
    val primary = requestIds[primaryEcu] ?: throw IllegalArgumentException("Unknown ECU '$primaryEcu'")
    return parameters.mapNotNull { parameter ->
        val wanted = period(parameter) ?: return@mapNotNull null
        val ecu = parameter.definition.ecu
        PollItem(
            id = "${ecu ?: primaryEcu}:${parameter.definition.service}${parameter.pid}",
            requestId = if (ecu != null) requestIds.getValue(ecu) else primary,
            payload = (parameter.definition.service + parameter.pid).hexToBytes(),
            period = wanted,
            expectedResponses = parameter.singleFrameCount(),
            dataLength = parameter.length,
        )
    }
}

/**
 * The adapter's response-count digit for asking [this] alone: 1 when the definition says the reply fits one CAN frame (service byte, PID
 * and data in at most 7 bytes), otherwise none. With a count the adapter stops after the first frame of a longer reply and never sends
 * flow control, so a multi-frame reply, or one of unknown length, must be asked without it.
 */
public fun CompiledParameter.singleFrameCount(): Int? {
    val length = length ?: return null
    val pidBytes = pid.length / 2
    return if (1 + pidBytes + length <= SINGLE_FRAME_BYTES) 1 else null
}

private const val SINGLE_FRAME_BYTES = 7
