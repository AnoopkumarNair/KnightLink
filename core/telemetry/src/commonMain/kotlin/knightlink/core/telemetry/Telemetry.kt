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

/** Turns diagnostic replies into readings using a vehicle's [SignalCatalog]. */
public class TelemetryDecoder(private val catalog: SignalCatalog) {
    /** @return the readings in [message], or an empty list when the catalog has no definition for it. */
    public fun decode(message: DiagnosticResponse.Positive): List<Reading> {
        val ecu = catalog.ecuByResponseId(message.sourceId)?.id
        for (pidLength in PID_LENGTHS) {
            if (message.data.size < pidLength) continue
            val pid = message.data.copyOfRange(0, pidLength).toHex()
            val parameter = catalog.find(message.service, pid, ecu) ?: continue
            return parameter.decode(message.data.copyOfRange(pidLength, message.data.size)).map { Reading(ecu, it) }
        }
        return emptyList()
    }

    private companion object {
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
        )
    }
}
