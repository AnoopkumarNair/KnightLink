package knightlink.tools.cli

import knightlink.core.common.hexToBytes
import knightlink.core.recorder.EventKind
import knightlink.core.recorder.ExternalSamples
import knightlink.core.recorder.Recording
import knightlink.core.recorder.TpmsSample
import knightlink.core.recorder.trafficExchanges
import knightlink.core.signals.SignalCatalog
import knightlink.core.tpms.TpmsDecoder
import knightlink.core.tpms.TpmsDefinition
import java.io.File

/** Thresholds of the "travels with the car" check; data for the report, chosen on the command line. */
internal class CoTravel(
    val movingKmh: Double = 15.0,
    val sharePercent: Double = 80.0,
    /** Fewer moving minutes than this, and no sensor is called the car's. */
    val minMovingMinutes: Int = 10,
)

internal fun tpmsReport(rawArgs: List<String>) {
    val args = Args(rawArgs, options = setOf("--definition", "--moving-kmh", "--share"))
    val path = args.positional.singleOrNull() ?: fail("tpms-report needs one recording")
    val decoders = args.option("--definition")?.let { listOf(tpmsDecoder(it)) } ?: allTpmsDecoders()
    val rules = CoTravel(
        movingKmh = args.option("--moving-kmh")?.let { it.toDoubleOrNull() ?: fail("--moving-kmh must be a number, not '$it'") } ?: 15.0,
        sharePercent =
        args.option("--share")?.let { it.toDoubleOrNull()?.takeIf { p -> p in 0.0..100.0 } ?: fail("--share is a percentage, not '$it'") } ?: 80.0,
    )
    renderTpmsReport(readRecording(path), decoders, catalogFor(DEFAULT_VEHICLE), rules).forEach(::println)
}

private fun tpmsDecoder(id: String): TpmsDecoder {
    val file = File("definitions/$id.json")
    if (!file.isFile) fail("no tyre sensor definition at ${file.path}")
    return TpmsDecoder(TpmsDefinition.parse(file.readText()))
}

private fun allTpmsDecoders(): List<TpmsDecoder> =
    File("definitions/tpms").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.map { TpmsDecoder(TpmsDefinition.parse(it.readText())) }

/**
 * The tyre sensor advertisements of a recording, as lines of text: each distinct payload in the order first heard, with its bytes lettered as
 * formulas name them and read with every layout, so the layout whose numbers match the sensor app's can be picked; then, per sensor id of
 * each layout that has one, in how many of the minutes the car was moving it was heard. Sensors on the car are heard in nearly every moving
 * minute (they send for about 10 s each minute); another car's drop out within a few minutes.
 */
internal fun renderTpmsReport(recording: Recording, decoders: List<TpmsDecoder>, catalog: SignalCatalog, rules: CoTravel = CoTravel()): List<String> {
    val samples = recording.events.filter { it.kind == EventKind.EXTERNAL }.mapNotNull { ExternalSamples.parseTpms(it.timeMs, it.payload) }
    val lines = ArrayList<String>()
    if (samples.isEmpty()) {
        lines += "No tyre sensor advertisements in this recording (no tpms lines)."
        return lines
    }
    val groups = samples.groupBy { it.companyId to it.payloadHex }.values.sortedBy { it.first().timeMs }
    lines += "Tyre sensor advertisements: ${samples.size} lines, ${groups.size} distinct payloads, company ids " +
        samples.map { hex4(it.companyId) }.distinct().joinToString(", ")
    lines += "Layouts: " + decoders.joinToString("; ") { "${it.definition.id} (${it.definition.provenance.name.lowercase()})" }
    for (group in groups) {
        val first = group.first()
        val rssi = group.mapNotNull { it.rssi }
        lines += ""
        lines += "payload ${first.payloadHex.ifEmpty { "(empty)" }} under ${hex4(first.companyId)}: ${group.size} lines, " +
            "%.1f s .. %.1f s".format(first.timeMs / 1000.0, group.last().timeMs / 1000.0) +
            if (rssi.isEmpty()) "" else ", ${rssi.min()}..${rssi.max()} dBm"
        val bytes = first.payloadHex.hexToBytes()
        lines += "  byte " + bytes.indices.joinToString(" ") { " " + letter(it) }
        lines += "       " + first.payloadHex.chunked(2).joinToString(" ")
        for (decoder in decoders) lines += "  ${decoder.definition.id}: " + readWith(decoder, bytes)
    }
    lines += coverage(samples, decoders, recording, catalog, rules)
    return lines
}

private fun readWith(decoder: TpmsDecoder, payload: ByteArray): String {
    // Read with the layout's own company id: which of the listened-for ids it came under is shown above, and is part of what is checked.
    val values = decoder.decode(decoder.definition.companyId.toInt(16), payload) ?: return "(too short for this layout)"
    val id = decoder.sensorId(payload)?.let { "id $it  " }.orEmpty()
    return id + values.joinToString("  ") { "${it.id} %.1f ${it.unit}".format(it.value) }
}

private fun coverage(samples: List<TpmsSample>, decoders: List<TpmsDecoder>, recording: Recording, catalog: SignalCatalog, rules: CoTravel): List<String> {
    val lines = ArrayList<String>()
    val withIds = decoders.filter { it.definition.sensorId != null }
    if (withIds.isEmpty()) return lines
    val carSpeed = vehicleSamples(recording.trafficExchanges(), catalog).getValue("vehicle.speed")
    val gpsSpeed = recording.events.filter { it.kind == EventKind.EXTERNAL }
        .mapNotNull { e -> ExternalSamples.parseGps(e.timeMs, e.payload)?.speedKmh?.let { Sample(e.timeMs, it) } }
    val speeds = carSpeed.ifEmpty { gpsSpeed }
    val source = if (carSpeed.isNotEmpty()) {
        "car speed"
    } else if (gpsSpeed.isNotEmpty()) {
        "GPS speed"
    } else {
        "no speed"
    }
    val moving = speeds.filter { it.value > rules.movingKmh }.map { minute(it.timeMs) }.toSortedSet()
    val decided = moving.size >= rules.minMovingMinutes
    for (decoder in withIds) {
        lines += ""
        lines += "Minute coverage by sensor id, read with ${decoder.definition.id}: ${moving.size} moving minutes " +
            "($source above %.0f km/h)".format(rules.movingKmh) +
            if (decided) "; on the car: heard in at least %.0f %% of them".format(rules.sharePercent) else "; fewer than ${rules.minMovingMinutes}: no decision"
        lines += "  %-14s  %6s  %7s  %7s  %9s  %9s  %s".format("sensor id", "lines", "minutes", "moving", "share", "seen s", "verdict")
        val byId = samples.mapNotNull { s -> decoder.sensorId(s.payloadHex.hexToBytes())?.let { it to s } }.groupBy({ it.first }, { it.second })
        val rows = byId.map { (id, heard) ->
            val minutes = heard.map { minute(it.timeMs) }.toSet()
            val movingHeard = minutes.count { it in moving }
            val share = if (moving.isEmpty()) 0.0 else 100.0 * movingHeard / moving.size
            Triple(id, heard, share) to (minutes.size to movingHeard)
        }.sortedByDescending { it.first.third }
        for ((row, counts) in rows) {
            val (id, heard, share) = row
            val verdict = when {
                !decided -> "-"
                share >= rules.sharePercent -> "car"
                // A spare may send less often; a steady sensor heard in half the minutes is worth a look, not a verdict.
                share >= rules.sharePercent / 2 -> "spare?"
                else -> "other"
            }
            lines += "  %-14s  %6d  %7d  %7d  %8.0f%%  %9s  %s".format(
                id,
                heard.size,
                counts.first,
                counts.second,
                share,
                "%.0f-%.0f".format(heard.first().timeMs / 1000.0, heard.last().timeMs / 1000.0),
                verdict,
            )
        }
    }
    return lines
}

private fun minute(timeMs: Long): Long = Math.floorDiv(timeMs, 60_000L)

private fun hex4(id: Int): String = id.toString(16).uppercase().padStart(4, '0')

/** `A`..`Z` as formulas name the payload bytes, then `?` beyond (formulas cannot reach those). */
private fun letter(index: Int): String = if (index < 26) ('A' + index).toString() + " " else "? "
