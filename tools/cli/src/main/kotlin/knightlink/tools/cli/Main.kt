package knightlink.tools.cli

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.adapter.Elm327Driver
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponseDecoder
import knightlink.core.scheduler.DiagnosticRequester
import knightlink.core.scheduler.PollScheduler
import knightlink.core.scheduler.PollingLoop
import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import knightlink.core.sim.SimulationFile
import knightlink.core.sim.VirtualElm327
import knightlink.core.telemetry.TelemetryDecoder
import knightlink.core.telemetry.pollItems
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val USAGE = """KnightLink developer tool

  simulate <simulation.json> [seconds]   Run a simulated vehicle through the real stack and print what changes
  decode <definition-id> <frame>...      Decode adapter output lines, e.g. decode vehicles/tata/harrier-2020-kryotec-at "7E8 03 41 05 6B"

Run from the repository root (definitions are read from ./definitions)."""

fun main(args: Array<String>) {
    try {
        when (args.firstOrNull()) {
            "simulate" -> simulate(File(args.getOrNull(1) ?: fail("simulate needs a file")), args.getOrNull(2)?.toIntOrNull() ?: 30)
            "decode" -> decode(args.getOrNull(1) ?: fail("decode needs a definition id"), args.drop(2))
            else -> println(USAGE)
        }
    } catch (e: CliError) {
        System.err.println("error: ${e.message}\n\n$USAGE")
        exitProcess(2)
    }
}

private class CliError(message: String) : Exception(message)

private fun fail(message: String): Nothing = throw CliError(message)

private fun definition(id: String): DefinitionFile {
    val file = File("definitions/$id.json")
    if (!file.isFile) fail("no definition at ${file.path}")
    return DefinitionJson.parse(file.readText())
}

private fun catalogFor(id: String): SignalCatalog {
    val top = definition(id)
    return SignalCatalog.of(top.extends.map(::definition) + top)
}

private fun decode(definitionId: String, lines: List<String>) {
    if (lines.isEmpty()) fail("decode needs at least one frame")
    val decoder = TelemetryDecoder(catalogFor(definitionId))
    val response = ElmResponseDecoder(extendedIds = false).decode(lines)
    response.statuses.forEach { println("status: $it") }
    response.protocolErrors.forEach { println("protocol error: $it") }
    for (message in response.messages) {
        val source = message.sourceId.toString(16).uppercase()
        when (message) {
            is DiagnosticResponse.Negative ->
                println("$source rejected service ${message.service.toString(16)}: ${message.reason ?: "code ${message.code}"}")
            is DiagnosticResponse.Positive -> {
                val readings = decoder.decode(message)
                if (readings.isEmpty()) println("$source: no definition for this reply")
                readings.forEach { println(format(it.ecu, it.signal.id, it.signal.value, it.signal.unit)) }
            }
        }
    }
}

private fun simulate(file: File, seconds: Int) {
    if (!file.isFile) fail("no simulation file at ${file.path}")
    val simulation = SimulationFile.parse(file.readText())
    val catalog = catalogFor(simulation.definition)
    val adapterDefinition = AdapterDefinition.parse(File("definitions/adapters/vgate-icar-pro-2s.json").readText())
    val decoder = TelemetryDecoder(catalog)
    val primaryEcu = catalog.ecus.firstOrNull()?.id ?: fail("${simulation.definition} lists no ECUs")

    println("${simulation.title}\n")
    // Single-threaded on purpose: the simulator is not thread-safe.
    runBlocking {
        val vehicle = simulation.buildVehicle()
        val adapter = VirtualElm327(vehicle, VirtualElm327.Config(responseDelay = 45.milliseconds))
        val driver = Elm327Driver(adapter, this)
        println("adapter: ${driver.initialize(adapterDefinition.initScript).banner}")

        val loop = PollingLoop(DiagnosticRequester(driver::request), PollScheduler(catalog.pollItems(primaryEcu) { 1.seconds }))
        val player = launch { simulation.play(vehicle) { step -> println("--- scenario: ${step.note ?: "step at ${step.atMs} ms"}") } }

        val latest = HashMap<String, Double>()
        var polls = 0
        loop.results().takeWhile { it.at < seconds.seconds }.collect { result ->
            polls++
            result.error?.let { println("request ${result.item} failed: ${it.message}") }
            result.response?.messages?.filterIsInstance<DiagnosticResponse.Positive>()?.flatMap(decoder::decode)?.forEach { reading ->
                val key = "${reading.ecu}/${reading.signal.id}"
                if (latest.put(key, reading.signal.value) != reading.signal.value) {
                    println("%6.1fs  %s".format(result.at.inWholeMilliseconds / 1000.0, format(reading.ecu, reading.signal.id, reading.signal.value, reading.signal.unit)))
                }
            }
        }
        println("\n$polls polls in $seconds s (${"%.1f".format(polls.toDouble() / seconds)} per second), ${latest.size} signals")
        // Stop the two background coroutines so runBlocking can return.
        player.cancel()
        driver.stop()
    }
}

private fun format(ecu: String?, id: String, value: Double, unit: String): String =
    "%-5s %-30s %10.2f %s".format(ecu ?: "-", id, value, unit)
