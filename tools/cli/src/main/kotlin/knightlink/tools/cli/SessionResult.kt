package knightlink.tools.cli

import knightlink.core.adapter.Elm327Driver
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.runtime.confirmedPollItems
import knightlink.core.runtime.pollRound
import knightlink.core.scheduler.DiagnosticRequester
import knightlink.core.scheduler.PollScheduler
import knightlink.core.signals.SignalCatalog
import knightlink.core.telemetry.Reading
import knightlink.core.telemetry.TelemetryDecoder
import knightlink.core.vehicle.CapabilityProbe
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DefinitionCoverage
import knightlink.core.vehicle.ProbeStage

/** What one session (init, probe, one poll of every parameter the car confirmed) found. The report has its VINs redacted. */
internal class SessionResult(
    val report: CapabilityReport,
    val coverage: DefinitionCoverage,
    val readings: List<Reading>,
    val pollFailures: List<String>,
)

/**
 * The session `probe --record` records and `replay` replays: adapter init script, capability probe, then one poll of
 * every parameter the probe confirmed. The poll list is the app's own ([confirmedPollItems]: each parameter from the ECU its
 * definition names, the others from the primary ECU) and is worked through by the app's scheduler, so the requests, batches
 * included, are the ones the app's first poll round sends. The same steps in the same order is what lets a recording made by
 * one be replayed strictly by the other.
 *
 * @param maxBatch most PIDs per request, as in the app; 1 for a recording made before batching existed
 */
internal suspend fun runSession(
    driver: Elm327Driver,
    initScript: List<String>,
    catalog: SignalCatalog,
    poll: Boolean,
    probeScript: List<String> = emptyList(),
    pollScript: List<String> = emptyList(),
    maxBatch: Int = PollScheduler.MAX_BATCH,
    onProgress: suspend (ProbeStage) -> Unit = {},
): SessionResult {
    driver.initialize(initScript + probeScript)
    // Only the redacted form is ever used afterwards: a probe of a real car must not put a full VIN in a terminal log.
    val report = CapabilityProbe(driver).run(onProgress).let { it.copy(adapter = it.adapter.copy(unsupportedCommands = driver.unsupportedCommands)) }.redacted()
    // The probe's timing is done with; what a re-initialisation restores is the polling one, as in the app.
    if (probeScript.isNotEmpty() || pollScript.isNotEmpty()) driver.applyScript(initScript + pollScript, send = pollScript)
    val coverage = DefinitionCoverage.compare(report, catalog)

    val decoder = TelemetryDecoder(catalog)
    val readings = ArrayList<Reading>()
    val failures = ArrayList<String>()
    if (poll) {
        val items = confirmedPollItems(catalog, coverage, catalog.ecus.firstOrNull()?.id) { it.tier.period }
        for (result in pollRound(items, maxBatch, DiagnosticRequester(driver::request))) {
            val error = result.error
            if (error != null) {
                failures += "${result.items.joinToString(" ")}: ${error.message ?: error::class.simpleName}"
            } else {
                result.response?.messages?.filterIsInstance<DiagnosticResponse.Positive>()?.flatMap(decoder::decode)?.forEach { readings += it }
            }
        }
    }
    return SessionResult(report, coverage, readings, failures)
}
