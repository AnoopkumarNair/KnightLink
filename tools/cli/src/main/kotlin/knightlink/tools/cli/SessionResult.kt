package knightlink.tools.cli

import knightlink.core.adapter.Elm327Driver
import knightlink.core.common.hexToBytes
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.signals.SignalCatalog
import knightlink.core.telemetry.Reading
import knightlink.core.telemetry.TelemetryDecoder
import knightlink.core.vehicle.CapabilityProbe
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DefinitionCoverage
import knightlink.core.vehicle.ProbeStage
import kotlin.coroutines.cancellation.CancellationException

/** What one session (init, probe, one poll of every parameter the car confirmed) found. The report has its VINs redacted. */
internal class SessionResult(
    val report: CapabilityReport,
    val coverage: DefinitionCoverage,
    val readings: List<Reading>,
    val pollFailures: List<String>,
)

/**
 * The session `probe --record` records and `replay` replays: adapter init script, capability probe, then one request
 * for every parameter the probe confirmed, to each ECU that supports it. The same steps in the same order is what lets
 * a recording made by one be replayed strictly by the other.
 */
internal suspend fun runSession(
    driver: Elm327Driver,
    initScript: List<String>,
    catalog: SignalCatalog,
    poll: Boolean,
    onProgress: (ProbeStage) -> Unit = {},
): SessionResult {
    driver.initialize(initScript)
    // Only the redacted form is ever used afterwards: a probe of a real car must not put a full VIN in a terminal log.
    val report = CapabilityProbe(driver).run(onProgress).redacted()
    val coverage = DefinitionCoverage.compare(report, catalog)

    val decoder = TelemetryDecoder(catalog)
    val readings = ArrayList<Reading>()
    val failures = ArrayList<String>()
    if (poll) {
        for (parameter in coverage.confirmed) {
            for (requestId in parameter.answeredBy) {
                try {
                    val response = driver.request(requestId.toInt(16), "01${parameter.pid}".hexToBytes(), expectedResponses = 1)
                    response.messages.filterIsInstance<DiagnosticResponse.Positive>().flatMap(decoder::decode).forEach { readings += it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += "01 ${parameter.pid} to $requestId: ${e.message ?: e::class.simpleName}"
                }
            }
        }
    }
    return SessionResult(report, coverage, readings, failures)
}
