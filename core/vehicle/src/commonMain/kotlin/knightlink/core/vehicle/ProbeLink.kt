package knightlink.core.vehicle

import knightlink.core.protocol.DiagnosticLink

/** The adapter operations a [CapabilityProbe] needs. Any adapter driver is one; tests script a fake. */
public typealias ProbeLink = DiagnosticLink

/** The probe could not produce a meaningful report, for example because the adapter answered nothing at all. */
public class ProbeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The steps of a probe, reported through the `onProgress` callback of [CapabilityProbe.run] when each starts. */
public enum class ProbeStage {
    /** Identify the adapter itself. */
    ADAPTER,

    /** Find which ECUs answer on the physical request addresses. */
    ECU_SWEEP,

    /** Read each ECU's service 01 supported-PID map. */
    LIVE_DATA_SUPPORT,

    /** Read each ECU's service 09 vehicle information (VIN, calibration ids, name). */
    VEHICLE_INFO,

    /** Measure how fast the adapter and vehicle answer. */
    BENCHMARK,

    /** Derive fuel method, DPF availability and duplicated PIDs from what was read. */
    DERIVED_FACTS,
}
