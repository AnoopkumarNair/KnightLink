package knightlink.tools.cli

import knightlink.core.common.SessionEvents
import knightlink.core.common.toHex
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.protocol.ElmResponseDecoder
import knightlink.core.protocol.ElmStreamFramer
import knightlink.core.recorder.EventKind
import knightlink.core.recorder.ExternalSamples
import knightlink.core.recorder.Recording
import knightlink.core.recorder.TrafficExchange
import knightlink.core.recorder.trafficExchanges
import knightlink.core.scan.ScanEcu
import knightlink.core.scan.ScanOutcome
import knightlink.core.scan.ScanResult
import knightlink.core.signals.SignalCatalog
import knightlink.core.telemetry.TelemetryDecoder
import java.io.File
import kotlin.math.sqrt

/** The vehicle definition whose lengths and formulas turn the recorded vehicle speed and engine speed into numbers, unless `--definition` says another. */
internal const val DEFAULT_VEHICLE = "vehicles/tata/harrier-2020-kryotec-at"

/** Every `scan-result` event of a recording, in order. */
internal fun scanResultsIn(recording: Recording): List<ScanResult> {
    val prefix = SessionEvents.SCAN_RESULT + ": "
    return recording.events.filter { it.kind == EventKind.EVENT && it.payload.startsWith(prefix) }.mapNotNull {
        try {
            ScanResult.fromJson(it.payload.removePrefix(prefix))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * The full scan's result in a recording: the last COMPLETED `scan-result` event (a recording may hold several scans, and a later one that was
 * cancelled or lost its link must not hide the one that finished); when none completed, the last of any kind; or the `.scan.json` file
 * written next to the recording.
 */
internal fun scanResultOf(recording: Recording, recordingFile: File? = null): ScanResult? {
    val all = scanResultsIn(recording)
    (all.lastOrNull { it.outcome == ScanOutcome.COMPLETED } ?: all.lastOrNull())?.let { return it }
    val sidecar = recordingFile?.let { File(it.parentFile, it.name.removeSuffix(".jsonl") + ".scan.json") }
    return sidecar?.takeIf { it.isFile }?.let { ScanResult.fromJson(it.readText(Charsets.UTF_8)) }
}

internal fun fullscanReport(rawArgs: List<String>) {
    val args = Args(rawArgs)
    val path = args.positional.singleOrNull() ?: fail("fullscan-report needs one recording")
    val recording = readRecording(path)
    val result = scanResultOf(recording, File(path)) ?: fail("$path holds no full scan result (no scan-result event, and no .scan.json beside it)")
    renderScanReport(result).forEach(::println)
    val others = scanResultsIn(recording).filter { it != result }
    if (others.isNotEmpty()) {
        println()
        println("Other scans in this recording (not reported above):")
        for (other in others) {
            println(
                "  ${other.outcome.name.lowercase()}${other.detail?.let { " ($it)" }.orEmpty()}, stages done: " +
                    other.stagesDone.joinToString(", ").ifEmpty { "none" } + ", ${other.ecus.size} control units",
            )
        }
    }
}

/** The report as lines. The VIN is already masked in the result; nothing here could show more. */
internal fun renderScanReport(result: ScanResult): List<String> = buildList {
    val minutes = "%.1f".format(result.durationMs / 60_000.0)
    add(
        "Full scan: ${result.outcome}${result.detail?.let { " ($it)" }.orEmpty()}, $minutes min, of which paused for the car moving ${"%.1f".format(
            result.pausedMs / 60_000.0,
        )} min",
    )
    add("Stages done: ${result.stagesDone.joinToString(", ").ifEmpty { "none" }}")
    if (result.battery.isNotEmpty()) add("Battery: " + result.battery.joinToString(", ") { "${it.text} at ${it.atMs / 1000} s" })
    val summary = result.summary
    add(
        "Found ${summary.ecus} control units. Faults: ${summary.confirmed} confirmed, ${summary.pending} pending, " +
            "${summary.warningLamp} asking for the warning lamp; " +
            "${summary.notCompleted} codes only say their test has not run (not faults).",
    )
    add("")
    add("Control units:")
    for (ecu in result.ecus) addAll(renderEcu(ecu).map { "  $it" })

    if (result.readiness.isNotEmpty()) {
        add("")
        add("Readiness and on-board tests:")
        for (unit in result.readiness) {
            val since = unit.sinceClear
            val head = if (since == null) {
                "no monitor status"
            } else {
                "MIL ${if (since.milOn == true) "ON" else "off"}, ${since.dtcCount} codes, ${if (since.compressionIgnition) "compression" else "spark"} ignition"
            }
            add("  ${unit.requestId}: $head")
            since?.monitors?.filter { it.supported }?.let { monitors ->
                add("      since clear:  " + monitors.joinToString(", ") { "${it.name} ${if (it.complete == true) "complete" else "NOT complete"}" })
            }
            unit.thisCycle?.monitors?.filter { it.supported }?.let { monitors ->
                add("      this cycle:   " + monitors.joinToString(", ") { "${it.name} ${if (it.complete == true) "complete" else "NOT complete"}" })
            }
            if (unit.supportedMids.isNotEmpty()) {
                val tests = unit.midResults.flatMap { it.tests }
                add(
                    "      service 06:   monitors ${unit.supportedMids.joinToString(" ")}, ${tests.size} tests, ${tests.count {
                        !it.withinLimits
                    }} outside their limits",
                )
                for (mid in unit.midResults) {
                    for (test in mid.tests) {
                        add(
                            "        ${mid.mid}/${test.testId} scaling ${test.scaling}: value ${test.value} limits ${test.min}..${test.max}${if (test.withinLimits) "" else "  OUTSIDE"}",
                        )
                    }
                }
            }
        }
    }

    add("")
    add("Manufacturer identifiers (service 22; what answers, not what it means):")
    if (result.sweeps.isEmpty()) add("  not reached")
    for (sweep in result.sweeps) {
        val state = sweep.stoppedBecause?.let { "  STOPPED: $it" }.orEmpty()
        add(
            "  ${sweep.requestId} ${sweep.label}: ${sweep.positive.size} answered of ${sweep.asked} asked (${sweep.planned} planned; ${sweep.refused} out of range, ${sweep.silent} silent)$state",
        )
        if (sweep.protectedDids.isNotEmpty()) add("      protected: ${sweep.protectedDids.joinToString(" ")}")
        for (answer in sweep.positive) add("      ${answer.did} = ${answer.hex.ifEmpty { "(seen, not read whole)" }}  [${answer.provenance.name.lowercase()}]")
    }
    result.warnings.forEach { add("warning: $it") }
}

private fun renderEcu(ecu: ScanEcu): List<String> = buildList {
    add("${ecu.requestId} -> ${ecu.responseId}  ${ecu.name ?: "(no name)"}  [found by ${ecu.foundBy}]")
    for (identifier in ecu.identity) {
        val value = identifier.text?.let { "\"$it\"" } ?: identifier.hex ?: "-"
        add("    ${identifier.did} ${identifier.label ?: ""}: $value".replace("  :", ":"))
    }
    ecu.storedCodes?.let { add("    OBD stored (03): ${it.ifEmpty { listOf("none") }.joinToString(" ")}") }
    ecu.pendingCodes?.let { add("    OBD pending (07): ${it.ifEmpty { listOf("none") }.joinToString(" ")}") }
    ecu.permanentCodes?.let { add("    OBD permanent (0A): ${it.ifEmpty { listOf("none") }.joinToString(" ")}") }
    ecu.udsFaults?.let { faults ->
        add("    UDS faults (19 02 FF): ${faults.total} listed; ${faults.notCompletedOnly} of them only say their test has not run (not faults)")
        fun line(name: String, codes: List<knightlink.core.scan.StatusDtc>) {
            if (codes.isNotEmpty()) add("      $name: ${codes.joinToString(" ") { "${it.code}(${it.status})" }}")
        }
        line("confirmed", faults.confirmed)
        line("pending", faults.pending)
        line("warning lamp requested", faults.warningLamp)
        line("failing a test, not confirmed", faults.failedTest)
    }
    ecu.note?.let { add("    note: $it") }
}

// did-timeline -------------------------------------------------------------------------------------------------------

internal fun didTimeline(rawArgs: List<String>) {
    val args = Args(rawArgs, options = setOf("--definition"))
    if (args.positional.size != 3) fail("did-timeline needs a recording, a control unit (its request id, e.g. 7E1) and a data identifier (e.g. 1E12)")
    val recording = readRecording(args.positional[0])
    val catalog = catalogFor(args.option("--definition") ?: DEFAULT_VEHICLE)
    renderDidTimeline(recording, args.positional[1], args.positional[2], catalog).forEach(::println)
}

internal class Sample(val timeMs: Long, val value: Double)

/** The car's own speed (`vehicle.speed`) and engine speed (`engine.rpm`) from every service 01 reply in the recording, decoded the way the app does it. */
internal fun vehicleSamples(exchanges: List<TrafficExchange>, catalog: SignalCatalog): Map<String, List<Sample>> {
    val decoder = TelemetryDecoder(catalog)
    val speed = ArrayList<Sample>()
    val rpm = ArrayList<Sample>()
    for (exchange in exchanges) {
        if (exchange.header == null || !exchange.request.startsWith("01")) continue
        for (message in decode(exchange).filterIsInstance<DiagnosticResponse.Positive>()) {
            for (reading in decoder.decode(message)) {
                when (reading.signal.id) {
                    "vehicle.speed" -> speed += Sample(exchange.timeMs, reading.signal.value)
                    "engine.rpm" -> rpm += Sample(exchange.timeMs, reading.signal.value)
                }
            }
        }
    }
    return mapOf("vehicle.speed" to speed, "engine.rpm" to rpm)
}

/**
 * The raw bytes of data identifier [did] of control unit [ecu] each time it was read, next to what the car's own speed and engine speed read
 * around then and what the phone's motion sensors and GPS said, as lines of text. "Around then" is the latest reading at or before the
 * read, so the columns can be correlated by eye or pasted into a spreadsheet.
 */
internal fun renderDidTimeline(recording: Recording, ecu: String, did: String, catalog: SignalCatalog): List<String> {
    val header = ecu.trim().uppercase()
    val identifier = did.trim().uppercase().also {
        require(it.length == 4 && it.all { c -> c in '0'..'9' || c in 'A'..'F' }) { "the identifier is four hex digits, not '$did'" }
    }
    val request = "22$identifier"
    val exchanges = recording.trafficExchanges()

    // The vehicle's own signals, from every service 01 reply in the recording (single or batched), decoded the way the app does it.
    val vehicle = vehicleSamples(exchanges, catalog)
    val speed = vehicle.getValue("vehicle.speed")
    val rpm = vehicle.getValue("engine.rpm")
    // The phone: how hard it was accelerated (the length of the acceleration vector), and its GPS speed.
    val acceleration = ArrayList<Sample>()
    val gpsSpeed = ArrayList<Sample>()
    var phoneSeen = false
    for (event in recording.events.filter { it.kind == EventKind.EXTERNAL }) {
        ExternalSamples.parseMotion(event.timeMs, event.payload)?.let { samples ->
            phoneSeen = true
            samples.forEach { acceleration += Sample(it.timeMs, sqrt(it.ax * it.ax + it.ay * it.ay + it.az * it.az)) }
        }
        ExternalSamples.parseGps(event.timeMs, event.payload)?.speedKmh?.let { gpsSpeed += Sample(event.timeMs, it) }
    }
    acceleration.sortBy { it.timeMs }

    val rows = exchanges.filter { it.header == header && it.request == request }
    val lines = ArrayList<String>()
    lines += "Data identifier $identifier of $header: ${rows.size} reads" + if (phoneSeen) "" else " (this recording has no phone samples)"
    lines += "%8s  %-26s  %9s  %7s  %10s  %9s".format("time s", "raw bytes", "speed km/h", "rpm", "phone acc", "gps km/h")
    for (row in rows) {
        val reading = decode(row).firstNotNullOfOrNull { message ->
            when {
                message is DiagnosticResponse.Positive &&
                    message.service == 0x22 &&
                    message.data.size >= 2 &&
                    message.data.copyOfRange(0, 2).toHex() == identifier -> message.data.copyOfRange(2, message.data.size).toHex().ifEmpty { "(no data)" }
                message is DiagnosticResponse.Negative -> "7F ${"%02X".format(message.code)}"
                else -> null
            }
        } ?: "-"
        lines += "%8.2f  %-26s  %9s  %7s  %10s  %9s".format(
            row.timeMs / 1000.0,
            reading,
            latest(speed, row.timeMs)?.let { "%.0f".format(it) } ?: "-",
            latest(rpm, row.timeMs)?.let { "%.0f".format(it) } ?: "-",
            latest(acceleration, row.timeMs)?.let { "%.2f".format(it) } ?: "-",
            latest(gpsSpeed, row.timeMs)?.let { "%.1f".format(it) } ?: "-",
        )
    }
    if (rows.isEmpty()) lines += "(no read of $request to $header in this recording)"
    return lines
}

/** The value of the latest sample at or before [timeMs]; null when there is none or it is more than 30 seconds old. */
private fun latest(samples: List<Sample>, timeMs: Long): Double? {
    var low = 0
    var high = samples.size - 1
    var best: Sample? = null
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (samples[mid].timeMs <= timeMs) {
            best = samples[mid]
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best?.takeIf { timeMs - it.timeMs <= MAX_AGE_MS }?.value
}

private const val MAX_AGE_MS = 30_000L

internal fun decode(exchange: TrafficExchange) =
    ElmStreamFramer().feed(exchange.response).firstOrNull()?.let { ElmResponseDecoder(extendedIds = false).decode(it).messages }.orEmpty()
