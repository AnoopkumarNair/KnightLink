package knightlink.tools.cli

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.adapter.Elm327Driver
import knightlink.core.recorder.EventKind
import knightlink.core.recorder.Recording
import knightlink.core.recorder.RecordingFormatException
import knightlink.core.recorder.RecordingScrubber
import knightlink.core.recorder.ReplayByteStream
import knightlink.core.recorder.ReplayMode
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

private fun readRecording(path: String): Recording {
    val file = File(path)
    if (!file.isFile) fail("no recording at ${file.path}")
    val recording = try {
        Recording.parse(file.readText(Charsets.UTF_8))
    } catch (e: RecordingFormatException) {
        fail("${file.path} is not a readable recording: ${e.message}")
    }
    if (recording.truncated) println("note: the last line of ${file.path} was incomplete (the session probably ended in a crash) and was ignored")
    return recording
}

/**
 * The commands the recorded app sent to set the adapter up: the leading adapter (AT) settings, up to the first
 * request or the first query. The adapter definition is not needed for this, so a recording from any adapter replays.
 * Queries (identity, voltage, protocol) and header selection belong to the probe and the polls, not to the init script.
 * The driver's warm-up (see [hasWarmUp]) is not part of the script and is skipped.
 */
internal fun initCommandsOf(recording: Recording): List<String> {
    val commands = sentCommandsOf(recording).filter { it.isNotBlank() }.drop(if (hasWarmUp(recording)) WARM_UP.size else 0)
    return commands.takeWhile { command ->
        val key = command.filterNot { it.isWhitespace() }.uppercase()
        key.startsWith("AT") && key !in QUERY_COMMANDS && !key.startsWith("ATSH")
    }.map { it.trim() }
}

private fun sentCommandsOf(recording: Recording): List<String> {
    val sent = recording.events.filter { it.kind == EventKind.TX }.joinToString("") { it.bytes.decodeToString() }
    return sent.split('\r').dropLast(1)
}

/**
 * Whether the recorded session began with the driver's warm-up: two `ATE0` before anything else. Older recordings did not
 * (their first command is the definition's script); a replay must match.
 */
internal fun hasWarmUp(recording: Recording): Boolean =
    sentCommandsOf(recording).filter { it.isNotBlank() }.take(WARM_UP.size).map { it.filterNot { c -> c.isWhitespace() }.uppercase() } == WARM_UP

private val WARM_UP = listOf("ATE0", "ATE0")

/** Whether the recording holds the definition's probe commands, i.e. was made by a build that lengthened the adapter timeout for the probe. */
internal fun usesProbeScript(recording: Recording, definition: AdapterDefinition): Boolean {
    val sent = sentCommandsOf(recording).map { it.filterNot { c -> c.isWhitespace() }.uppercase() }.toSet()
    return definition.probeScript.isNotEmpty() && definition.probeScript.all { it.filterNot { c -> c.isWhitespace() }.uppercase() in sent }
}

private val QUERY_COMMANDS = setOf("ATI", "AT@1", "AT@2", "ATRV", "ATDP", "ATDPN", "ATIGN")

internal fun replay(rawArgs: List<String>) {
    val args = Args(rawArgs, flags = setOf("--strict"), options = setOf("--speed"))
    if (args.positional.size != 2) fail("replay needs a recording and a definition id")
    val recording = readRecording(args.positional[0])
    val definitionId = args.positional[1]
    val catalog = catalogFor(definitionId)
    val strict = args.flag("--strict")
    val speed = args.option("--speed")?.let(::positiveNumber) ?: 1.0

    // A strict replay has to send exactly what was recorded, so the script must be the one that was used.
    val definition = if (strict) {
        adapterDefinition(recording.header.adapter ?: fail("a strict replay needs the recording header to name the adapter definition it used"))
    } else {
        null
    }
    val initScript = definition?.initScript ?: initCommandsOf(recording)
    val withTuning = definition != null && usesProbeScript(recording, definition)
    val initSource = if (strict) "the adapter definition" else "the recording"
    println("${recording.events.size} events, ${if (strict) "strict" else "by command"}, init: ${initScript.size} commands from $initSource")
    printEvents(recording)

    val stream = ReplayByteStream(recording, if (strict) ReplayMode.STRICT else ReplayMode.BY_COMMAND, speed)
    val session = runBlocking {
        // The warm-up is part of what the recording holds, or not; the replay must do the same to match it.
        val driver = Elm327Driver(stream, this, config = (definition?.driverConfig() ?: Elm327Driver.Config()).copy(warmUp = hasWarmUp(recording)))
        try {
            runSession(
                driver,
                initScript,
                catalog,
                poll = true,
                probeScript = if (withTuning) definition!!.probeScript else emptyList(),
                pollScript = if (withTuning) definition!!.pollScript else emptyList(),
            ) { System.err.println("probe: $it") }
        } finally {
            stream.close()
            driver.stop()
        }
    }

    // The probe and the polls treat any failed request as "no answer", so a mismatch can hide in warnings: report it first.
    stream.firstMismatch?.let { throw it }
    printReport(recording.header.note ?: "Replay of ${args.positional[0]}", definitionId, session.report, session.coverage)
    println("\nReadings (one poll of every confirmed parameter):")
    session.readings.forEach { println("  " + format(it.ecu, it.signal.id, it.signal.value, it.signal.unit)) }
    session.pollFailures.forEach { println("poll failed: $it") }
    if (strict) {
        println("\nReplayed ${recording.events.size - (stream.remainingEvents ?: 0)} of ${recording.events.size} recorded events")
    } else {
        println("\nCommands the recording has no answer for: ${stream.unknownCommands}")
        stream.unknownCommandTexts.distinct().forEach { println("  $it") }
    }
}

internal fun scrub(rawArgs: List<String>) {
    val args = Args(rawArgs)
    if (args.positional.size != 2) fail("scrub needs an input and an output file")
    val recording = readRecording(args.positional[0])
    val result = RecordingScrubber.scrub(recording)
    try {
        // CREATE_NEW: never overwrite, in particular not the input.
        Files.write(File(args.positional[1]).toPath(), result.recording.toText().toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE_NEW)
    } catch (e: FileAlreadyExistsException) {
        fail("${args.positional[1]} already exists; choose a new file name")
    }
    result.warnings.forEach { println("warning: $it") }
    println("Wrote ${result.recording.events.size} events to ${args.positional[1]} (${result.warnings.size} warnings)")
}

/** The session events of a recording (schema 2 and later), which say why the traffic looks the way it does. Nothing is printed for older files. */
internal fun printEvents(recording: Recording) {
    val events = recording.events.filter { it.kind == EventKind.EVENT }
    if (events.isEmpty()) return
    println("\nSession events (${events.size}):")
    events.forEach { println("  %7.3f s  %s".format(it.timeMs / 1000.0, it.payload)) }
    println()
}
