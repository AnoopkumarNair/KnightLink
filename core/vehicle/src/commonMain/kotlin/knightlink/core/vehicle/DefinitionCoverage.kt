package knightlink.core.vehicle

import knightlink.core.signals.CompiledParameter
import knightlink.core.signals.SignalCatalog

/** A catalog parameter (service 01) that the vehicle confirmed it supports. */
public data class CoveredParameter(
    /** Upper-case 2-digit hex. */
    val pid: String,
    /** Id of the catalog ECU the definition targets; null when it applies to any ECU. */
    val catalogEcu: String?,
    val signals: List<String>,
    /** Request ids (upper-case hex) of the probed ECUs that support the PID: just the target ECU, or every supporting ECU for a definition without one. */
    val answeredBy: List<String>,
)

/** A catalog parameter (service 01) the vehicle did not report as supported. */
public data class MissingParameter(
    val pid: String,
    val catalogEcu: String?,
    val signals: List<String>,
)

/** PIDs one probed ECU supports that the catalog has no definition for. */
public data class UnmappedPids(
    /** Request id of the probed ECU, upper-case hex. */
    val requestId: String,
    /** Upper-case 2-digit hex, ascending. */
    val pids: List<String>,
)

/**
 * How well a vehicle definition matches what a probe found. Probed ECUs are matched to catalog ECUs by
 * request CAN id, because ECU ids are only names chosen by the definition author.
 */
public data class DefinitionCoverage(
    /** Defined and supported. */
    val confirmed: List<CoveredParameter>,
    /** Defined but not supported, or defined for an ECU that did not answer. */
    val missing: List<MissingParameter>,
    /** Supported but not defined, per ECU. ECUs with nothing unmapped are left out. */
    val unmapped: List<UnmappedPids>,
) {
    public companion object {
        /** Only service 01 is compared: it is the only service whose support the probe reads. */
        public fun compare(report: CapabilityReport, catalog: SignalCatalog): DefinitionCoverage {
            val catalogEcuByRequestId = catalog.ecus.associate { it.requestCanId to it.id }
            val parameters = catalog.parameters
                .filter { it.service == SERVICE_LIVE_DATA }
                .sortedWith(compareBy({ it.pid.toInt(16) }, { it.definition.ecu.orEmpty() }))

            val confirmed = ArrayList<CoveredParameter>()
            val missing = ArrayList<MissingParameter>()
            for (parameter in parameters) {
                val owners = supporters(parameter, report, catalog)
                val signals = parameter.signals.map { it.definition.id }
                if (owners.isEmpty()) {
                    missing += MissingParameter(parameter.pid, parameter.definition.ecu, signals)
                } else {
                    confirmed += CoveredParameter(parameter.pid, parameter.definition.ecu, signals, owners.map { it.requestId })
                }
            }

            val unmapped = report.ecus.mapNotNull { ecu ->
                val catalogEcu = catalogEcuByRequestId[ecu.requestCanId]
                val pids = ecu.supportedPids.filter { catalog.find(SERVICE_LIVE_DATA, it, catalogEcu) == null }
                if (pids.isEmpty()) null else UnmappedPids(ecu.requestId, pids)
            }
            return DefinitionCoverage(confirmed, missing, unmapped)
        }

        /** The probed ECUs that can answer [parameter]: the one it targets, or any that supports its PID. */
        private fun supporters(parameter: CompiledParameter, report: CapabilityReport, catalog: SignalCatalog): List<EcuReport> {
            val pid = parameter.pid.toInt(16)
            val target = parameter.definition.ecu?.let { id -> catalog.ecus.first { it.id == id } }
            return report.ecus.filter { ecu -> (target == null || ecu.requestCanId == target.requestCanId) && ecu.supports(pid) }
        }
    }
}
