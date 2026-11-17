package knightlink.tools.cli

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.adapter.Elm327Driver
import knightlink.core.common.toHex
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponseDecoder
import knightlink.core.recorder.RecordSink
import knightlink.core.recorder.RecordingByteStream
import knightlink.core.recorder.RecordingHeader
import knightlink.core.recorder.ReplayMismatchException
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
import knightlink.core.transport.ByteStream
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DefinitionCoverage
import knightlink.core.vehicle.ProbeException
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val USAGE = """KnightLink developer tool

  simulate <simulation.json> [seconds] [--record <out.jsonl>]
                                         Run a simulated vehicle through the real stack and print what changes;
                                         --record writes every byte exchanged with the virtual adapter to a new session recording
  probe <simulation.json> [--json] [--record <out.jsonl>]
                                         Run the read-only capability probe against a simulated vehicle and compare it with its definition;
                                         --json prints the report as JSON instead (VIN redacted);
                                         --record also polls every confirmed parameter once and records the whole session
  replay <recording.jsonl> <definition-id> [--strict] [--no-batch] [--speed <factor>]
                                         Run init, probe and one poll of every confirmed parameter against a recorded session instead of an adapter.
                                         The poll list and the batching are the app's, so an app recording has an answer for every command;
                                         --no-batch asks every PID alone, for recordings made before batching.
                                         Default: each command is answered with the recorded answer to that command, in any order, and the
                                         commands the recording does not know are counted. --strict: the stack must send exactly what was
                                         recorded, in order (init commands come from the adapter definition the recording names).
                                         --speed divides the recorded delays (default 1)
  scrub <in.jsonl> <out.jsonl>           Write a copy of a recording that is safe to share: VIN reduced to its manufacturer code. Prints warnings
  fullscan-report <recording.jsonl>      Print the result of a full scan from a recording (its scan-result event, or the .scan.json beside it): control units
                                         with their identities (VIN masked), readiness and on-board tests, fault codes by status (codes whose test has not
                                         run are counted apart: they are not faults), and which manufacturer identifiers each unit answered
  did-timeline <recording.jsonl> <ecu> <did> [--definition <id>]
                                         Print the raw bytes of one service 22 identifier (e.g. 7E1 1E12) each time it was read, next to the car's speed
                                         and engine speed and the phone's acceleration and GPS speed at that time, for correlation
  tpms-report <recording.jsonl> [--definition <id>] [--moving-kmh <speed>] [--share <percent>]
                                         List each distinct tyre sensor payload the phone heard (first and last time, count, signal strength), read
                                         with every layout in definitions/tpms (or only --definition), then, per sensor id of each layout that has one,
                                         the share of moving minutes it was heard in (moving: car speed, else GPS speed, above --moving-kmh, default 15)
                                         and whether it passes --share (default 80) as travelling with the car
  decode <definition-id> <frame>...      Decode adapter output lines, e.g. decode vehicles/tata/harrier-2020-kryotec-at "7E8 03 41 05 6B"

Run from the repository root (definitions are read from ./definitions)."""

fun main(args: Array<String>) {
    try {
        when (args.firstOrNull()) {
            "simulate" -> simulate(args.drop(1))
            "probe" -> probe(args.drop(1))
            "replay" -> replay(args.drop(1))
            "scrub" -> scrub(args.drop(1))
            "fullscan-report" -> fullscanReport(args.drop(1))
            "did-timeline" -> didTimeline(args.drop(1))
            "tpms-report" -> tpmsReport(args.drop(1))
            "decode" -> decode(args.getOrNull(1) ?: fail("decode needs a definition id"), args.drop(2))
            else -> println(USAGE)
        }
    } catch (e: ProbeException) {
        System.err.println("probe failed: ${e.message}")
        exitProcess(1)
    } catch (e: ReplayMismatchException) {
        System.err.println("replay failed: ${e.message}")
        exitProcess(1)
    } catch (e: CliError) {
        System.err.println("error: ${e.message}\n\n$USAGE")
        exitProcess(2)
    }
}

internal class CliError(message: String) : Exception(message)

internal fun fail(message: String): Nothing = throw CliError(message)

internal fun positiveInt(text: String): Int = text.toIntOrNull()?.takeIf { it > 0 } ?: fail("seconds must be a positive whole number, not '$text'")

internal fun positiveNumber(text: String): Double =
    text.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: fail("--speed must be a positive number, not '$text'")

/** Command line split into positional arguments, `--flag`s and `--option value` pairs; anything else starting with `--` is an error. */
internal class Args(raw: List<String>, flags: Set<String> = emptySet(), options: Set<String> = emptySet()) {
    val positional = ArrayList<String>()
    private val flagsSet = HashSet<String>()
    private val optionValues = HashMap<String, String>()

    init {
        val iterator = raw.iterator()
        while (iterator.hasNext()) {
            val arg = iterator.next()
            when {
                arg in flags -> flagsSet += arg
                arg in options -> optionValues[arg] = if (iterator.hasNext()) iterator.next() else fail("$arg needs a value")
                arg.startsWith("--") -> fail("unknown option $arg")
                else -> positional += arg
            }
        }
    }

    fun flag(name: String): Boolean = name in flagsSet

    fun option(name: String): String? = optionValues[name]
}

internal fun definition(id: String): DefinitionFile {
    val file = File("definitions/$id.json")
    if (!file.isFile) fail("no definition at ${file.path}")
    return DefinitionJson.parse(file.readText())
}

internal fun catalogFor(id: String): SignalCatalog {
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

private fun simulate(rawArgs: List<String>) {
    val args = Args(rawArgs, options = setOf("--record"))
    val file = File(args.positional.getOrNull(0) ?: fail("simulate needs a file"))
    val seconds = args.positional.getOrNull(1)?.let(::positiveInt) ?: 30
    if (args.positional.size > 2) fail("simulate takes a file and an optional number of seconds")
    if (!file.isFile) fail("no simulation file at ${file.path}")
    val simulation = SimulationFile.parse(file.readText())
    val catalog = catalogFor(simulation.definition)
    val adapterDefinition = adapterDefinition(DEFAULT_ADAPTER)
    val decoder = TelemetryDecoder(catalog)
    val primaryEcu = catalog.ecus.firstOrNull()?.id ?: fail("${simulation.definition} lists no ECUs")
    val sink = args.option("--record")?.let { FileRecordSink.create(File(it).toPath()) }

    println("${simulation.title}\n")
    // The sink is closed only after the session, so the last events (the close) are in the file.
    sink.use {
        simulateWith(simulation, catalog, adapterDefinition, decoder, primaryEcu, seconds, sink)
    }
    args.option("--record")?.let { println("recording written to $it") }
}

private fun simulateWith(
    simulation: SimulationFile,
    catalog: SignalCatalog,
    adapterDefinition: AdapterDefinition,
    decoder: TelemetryDecoder,
    primaryEcu: String,
    seconds: Int,
    sink: RecordSink?,
) {
    // Single-threaded on purpose: the simulator is not thread-safe.
    runBlocking {
        val vehicle = simulation.buildVehicle()
        val adapter = VirtualElm327(vehicle, VirtualElm327.Config(responseDelay = 45.milliseconds))
        val link = recordingLink(adapter, sink, adapterDefinition, "simulated: ${simulation.id}")
        val driver = Elm327Driver(link, this, config = adapterDefinition.driverConfig())
        println("adapter: ${driver.initialize(adapterDefinition.initScript).banner}")

        // The simulated car has no probe step here, so ask only for what its ECUs hold; periods and batching are the app's.
        val held = simulation.ecus.flatMap { ecu -> ecu.data.keys.map { "${ecu.requestId.uppercase()}:${it.uppercase()}" } }.toSet()
        val items = catalog.pollItems(primaryEcu) { it.tier.period }.filter { item ->
            "${item.requestId.toString(16).uppercase()}:${item.payload.toHex()}" in held
        }
        val loop = PollingLoop(DiagnosticRequester(driver::request), PollScheduler(items, maxBatch = PollScheduler.MAX_BATCH))
        val player = launch { simulation.play(vehicle) { step -> println("--- scenario: ${step.note ?: "step at ${step.atMs} ms"}") } }

        val latest = HashMap<String, Double>()
        var polls = 0
        loop.results().takeWhile { it.at < seconds.seconds }.collect { result ->
            polls++
            result.error?.let { println("request ${result.item} failed: ${it.message}") }
            result.response?.messages?.filterIsInstance<DiagnosticResponse.Positive>()?.flatMap(decoder::decode)?.forEach { reading ->
                val key = "${reading.ecu}/${reading.signal.id}"
                if (latest.put(key, reading.signal.value) != reading.signal.value) {
                    println(
                        "%6.1fs  %s".format(
                            result.at.inWholeMilliseconds / 1000.0,
                            format(reading.ecu, reading.signal.id, reading.signal.value, reading.signal.unit),
                        ),
                    )
                }
            }
        }
        println("\n$polls polls in $seconds s (${"%.1f".format(polls.toDouble() / seconds)} per second), ${latest.size} signals")
        // Stop the two background coroutines so runBlocking can return. Closing the link puts the close event on record.
        player.cancel()
        link.close()
        driver.stop()
    }
}

internal const val DEFAULT_ADAPTER = "adapters/vgate-icar-pro-2s"

internal fun adapterDefinition(id: String): AdapterDefinition {
    val file = File("definitions/$id.json")
    if (!file.isFile) fail("no adapter definition at ${file.path}")
    return AdapterDefinition.parse(file.readText())
}

/** [adapter] itself, or a recorder around it when [sink] is given. */
private fun recordingLink(adapter: ByteStream, sink: RecordSink?, definition: AdapterDefinition, note: String): ByteStream =
    if (sink == null) adapter else RecordingByteStream(adapter, sink, TimeSource.Monotonic, RecordingHeader(definition.id, note))

internal fun format(ecu: String?, id: String, value: Double, unit: String): String =
    "%-5s %-30s %10.2f %s".format(ecu ?: "-", id, value, unit)

private fun probe(rawArgs: List<String>) {
    val args = Args(rawArgs, flags = setOf("--json"), options = setOf("--record"))
    val file = File(args.positional.singleOrNull() ?: fail("probe needs one simulation file"))
    if (!file.isFile) fail("no simulation file at ${file.path}")
    val asJson = args.flag("--json")

    val simulation = SimulationFile.parse(file.readText())
    val catalog = catalogFor(simulation.definition)
    val adapterDefinition = adapterDefinition(DEFAULT_ADAPTER)
    val recordPath = args.option("--record")
    val sink = recordPath?.let { FileRecordSink.create(File(it).toPath()) }

    val session = sink.use {
        runBlocking {
            val adapter = VirtualElm327(simulation.buildVehicle(), VirtualElm327.Config(responseDelay = 45.milliseconds))
            val link = recordingLink(adapter, sink, adapterDefinition, "simulated: ${simulation.id}, probe session")
            val driver = Elm327Driver(link, this, config = adapterDefinition.driverConfig())
            try {
                // Progress goes to stderr so that stdout stays valid JSON with --json. A recording also holds one poll of
                // every confirmed parameter: that is the session `replay` runs, so the file replays strictly too.
                runSession(driver, adapterDefinition.initScript, catalog, poll = sink != null, adapterDefinition.probeScript, adapterDefinition.pollScript) {
                    System.err.println("probe: $it")
                }
            } finally {
                // Stops the reader coroutine so runBlocking can return.
                link.close()
                driver.stop()
            }
        }
    }

    // Only the redacted form is ever printed: a probe of a real car must not put a full VIN in a terminal log.
    if (asJson) {
        println(session.report.toJson())
    } else {
        printReport(simulation.title, simulation.definition, session.report, session.coverage)
    }
    recordPath?.let { System.err.println("recording written to $it") }
}

internal fun printReport(title: String, definitionId: String, report: CapabilityReport, coverage: DefinitionCoverage) {
    val adapter = report.adapter
    println("$title\n")
    println("Adapter:  ${adapter.banner ?: "?"} / ${adapter.description ?: "?"}, ${adapter.voltageText ?: "? V"}, protocol ${adapter.protocolNumber ?: "?"}")
    println("ECUs:     ${report.ecus.size} found")
    for (ecu in report.ecus) {
        println("  ${ecu.requestId} -> ${ecu.responseId}  ${ecu.name ?: "(no name)"}")
        println("      VIN ${ecu.vin ?: "-"}   calibration ${ecu.calibrationIds.ifEmpty { listOf("-") }.joinToString(", ")}")
        println("      ${ecu.supportedPids.size} service 01 PIDs: ${ecu.supportedPids.joinToString(" ")}")
    }
    println("Fuel:     ${report.derived.fuelMethod}")
    println("DPF data: ${if (report.derived.hasDpfData) "yes" else "no"}")
    println(
        "Shared PIDs (poll each ECU by its own address): ${report.derived.duplicates.ifEmpty {
            null
        }?.joinToString { "${it.pid} on ${it.ecus.joinToString("+")}" } ?: "none"}",
    )
    val timing = report.timing
    val rate = timing.estimatedRequestsPerSecond?.let { "%.1f requests/s".format(it) } ?: "n/a"
    println(
        "Speed:    median round trip ${timing.medianRoundTripMs?.let {
            "%.1f ms".format(it)
        } ?: "n/a"} over ${timing.benchmarkSamples} samples, about $rate",
    )
    println("Probe took %.1f s".format(timing.probeMs / 1000))
    report.warnings.forEach { println("warning: $it") }

    println("\nCoverage of $definitionId")
    println("  confirmed (${coverage.confirmed.size}): ${coverage.confirmed.joinToString { "${it.pid}@${it.answeredBy.joinToString("+")}" }}")
    println(
        "  missing   (${coverage.missing.size}): ${coverage.missing.joinToString {
            it.pid + (it.catalogEcu?.let { ecu -> "@$ecu" } ?: "")
        }.ifEmpty { "none" }}",
    )
    println(
        "  unmapped  (${coverage.unmapped.sumOf {
            it.pids.size
        }}): ${coverage.unmapped.joinToString { "${it.requestId}: ${it.pids.joinToString(" ")}" }.ifEmpty { "none" }}",
    )
}
