package knightlink.core.vehicle

import kotlinx.serialization.Serializable

/** How the vehicle makes fuel consumption available, best source first. */
@Serializable
public enum class FuelMethod {
    /** An ECU reports the fuel rate itself (PID 9D or 5E, both "engine fuel rate"). */
    DIRECT_RATE,

    /** Mass air flow is available (PID 10); the rate must be estimated from it. */
    MAF,

    /** Only manifold pressure and engine speed are available (PIDs 0B and 0C); the least accurate estimate. */
    SPEED_DENSITY,

    /** None of the above. */
    UNKNOWN,
}

/** A service 01 PID that more than one ECU answers. */
@Serializable
public data class DuplicatePid(
    /** Upper-case 2-digit hex. */
    val pid: String,
    /** Request ids (upper-case hex) of every ECU that supports it, ascending. */
    val ecus: List<String>,
    /** The ECU to use when nothing else decides: the lowest request id. */
    val primary: String,
)

/**
 * Conclusions drawn from the ECUs' service 01 support maps. The functions are pure, so they can be
 * tested without a probe.
 */
@Serializable
public data class DerivedFacts(
    val fuelMethod: FuelMethod,
    /** True when any ECU supports a diesel particulate filter PID (7A, 7B, 7C or 8B). */
    val hasDpfData: Boolean,
    /**
     * PIDs answered by more than one ECU, ascending by PID. A request sent to the functional address would
     * get several answers to the same PID, so polling must address ECUs individually.
     */
    val duplicates: List<DuplicatePid>,
) {
    public companion object {
        private val DIRECT_RATE_PIDS = listOf(0x9D, 0x5E)
        private const val PID_MAF = 0x10
        private const val PID_MAP = 0x0B
        private const val PID_RPM = 0x0C
        private val DPF_PIDS = listOf(0x7A, 0x7B, 0x7C, 0x8B)

        public fun of(ecus: List<EcuReport>): DerivedFacts = DerivedFacts(fuelMethod(ecus), hasDpfData(ecus), duplicates(ecus))

        /**
         * Support is pooled over all ECUs, because the PIDs that matter often live on different ones.
         * Speed-density therefore means "manifold pressure and engine speed are both readable somewhere".
         */
        public fun fuelMethod(ecus: List<EcuReport>): FuelMethod {
            fun anySupports(pid: Int) = ecus.any { it.supports(pid) }
            return when {
                DIRECT_RATE_PIDS.any(::anySupports) -> FuelMethod.DIRECT_RATE
                anySupports(PID_MAF) -> FuelMethod.MAF
                anySupports(PID_MAP) && anySupports(PID_RPM) -> FuelMethod.SPEED_DENSITY
                else -> FuelMethod.UNKNOWN
            }
        }

        public fun hasDpfData(ecus: List<EcuReport>): Boolean = ecus.any { ecu -> DPF_PIDS.any(ecu::supports) }

        public fun duplicates(ecus: List<EcuReport>): List<DuplicatePid> =
            ecus.sortedBy { it.requestCanId }
                .flatMap { ecu -> ecu.supportedPids.map { pid -> pid to ecu.requestId } }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size > 1 }
                .entries.sortedBy { it.key }
                .map { (pid, owners) -> DuplicatePid(pid, owners, primary = owners.first()) }
    }
}
