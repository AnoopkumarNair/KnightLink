package knightlink.tools.cli

import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.recorder.EventKind
import knightlink.core.recorder.ExternalSamples
import knightlink.core.recorder.Recording
import knightlink.core.recorder.trafficExchanges
import knightlink.core.runtime.drivetruth.DriveSample
import knightlink.core.runtime.drivetruth.DriveTruth
import knightlink.core.runtime.drivetruth.DriveTruthConfig
import knightlink.core.runtime.drivetruth.DriveTruthReport
import knightlink.core.runtime.drivetruth.FuelType
import knightlink.core.runtime.drivetruth.GpsFix
import knightlink.core.signals.SignalCatalog
import knightlink.core.telemetry.TelemetryDecoder

private const val HARRIER_TANK_LITRES = 50.0

internal fun driveTruth(rawArgs: List<String>) {
    val args = Args(rawArgs, options = setOf("--definition", "--fuel", "--price", "--tank"))
    val path = args.positional.singleOrNull() ?: fail("drivetruth needs one recording")
    val recording = readRecording(path)
    val catalog = catalogFor(args.option("--definition") ?: DEFAULT_VEHICLE)
    val fuel = when (args.option("--fuel")?.lowercase() ?: "diesel") {
        "diesel" -> FuelType.DIESEL
        "petrol" -> FuelType.PETROL
        else -> fail("--fuel is diesel or petrol")
    }
    val price = args.option("--price")?.let { it.toDoubleOrNull() ?: fail("--price must be a number") }
    val tank = args.option("--tank")?.let { it.toDoubleOrNull() ?: fail("--tank must be a number") } ?: HARRIER_TANK_LITRES
    renderDriveTruth(recording, catalog, DriveTruthConfig(fuel, price, tank)).forEach(::println)
}

/** One reading of a signal at a time, from the car's replies or the phone's GPS, before they are merged into samples. */
private class Reading(val timeMs: Long, val id: String, val value: Double, val gps: GpsFix? = null)

/** Feeds the recording to the reducer, in time order, and returns the report as lines of text. */
internal fun renderDriveTruth(recording: Recording, catalog: SignalCatalog, config: DriveTruthConfig): List<String> {
    val decoder = TelemetryDecoder(catalog)
    val readings = ArrayList<Reading>()
    val seen = sortedMapOf<String, Int>()
    for (exchange in recording.trafficExchanges()) {
        if (exchange.header == null || !exchange.request.startsWith("01")) continue
        for (message in decode(exchange).filterIsInstance<DiagnosticResponse.Positive>()) {
            for (reading in decoder.decode(message)) {
                seen.merge(reading.signal.id, 1, Int::plus)
                readings += Reading(exchange.timeMs, reading.signal.id, reading.signal.value)
            }
        }
    }
    var fixes = 0
    for (event in recording.events.filter { it.kind == EventKind.EXTERNAL }) {
        val gps = ExternalSamples.parseGps(event.timeMs, event.payload) ?: continue
        fixes++
        readings += Reading(event.timeMs, "gps", 0.0, GpsFix(gps.latitude, gps.longitude, gps.speedKmh?.let { it / 3.6 }, gps.accuracyM))
    }
    readings.sortBy { it.timeMs }

    val truth = DriveTruth(config)
    for (r in readings) {
        val sample = when (r.id) {
            "vehicle.speed" -> DriveSample(r.timeMs, speedKmh = r.value)
            "engine.rpm" -> DriveSample(r.timeMs, rpm = r.value)
            "fuel.rate_lph" -> DriveSample(r.timeMs, fuelRateLph = r.value)
            "fuel.rate_gps" -> DriveSample(r.timeMs, fuelRateGps = r.value)
            "intake.maf" -> DriveSample(r.timeMs, mafGps = r.value)
            "fuel.level_pct" -> DriveSample(r.timeMs, fuelLevelPercent = r.value)
            "engine.coolant_temp" -> DriveSample(r.timeMs, coolantC = r.value)
            "engine.throttle_pct" -> DriveSample(r.timeMs, throttlePercent = r.value)
            "gps" -> DriveSample(r.timeMs, gps = r.gps)
            else -> null
        }
        if (sample != null) truth.add(sample)
    }
    val report = truth.report()
    return buildList {
        add("Signals decoded: " + seen.entries.joinToString { "${it.key} x${it.value}" }.ifEmpty { "none" })
        add("GPS fixes in the recording: $fixes")
        add("Drive ended (60 s standstill, engine off): ${truth.isDriveEnded()}")
        addAll(formatReport(report, config))
    }
}

internal fun formatReport(r: DriveTruthReport, config: DriveTruthConfig): List<String> = buildList {
    fun km(v: Double?) = v?.let { "%.2f km".format(it) } ?: "not available"
    fun num(v: Double?, pattern: String) = v?.let { pattern.format(it) } ?: "-"
    add("Duration %.1f min (moving %.1f, idle %.1f)".format(r.durationMinutes, r.movingMinutes, r.idleMinutes))
    add("Car distance (PID 0D integrated): ${km(r.carDistanceKm)}")
    add("GPS distance:                      ${km(r.gpsDistanceKm)}")
    add("Car vs GPS: " + (r.carVsGpsPercent?.let { "%+.1f %%".format(it) } ?: "not available"))
    add("Fuel (${config.fuelType}): " + (r.fuelLitres?.let { "%.2f L via %s".format(it, r.fuelSource) } ?: "not available"))
    add("Cost: ${num(r.costRupees, "%.1f")}  per km: ${num(r.costPerKmRupees, "%.2f")}  idle cost: ${num(r.idleCostRupees, "%.1f")}")
    add("km/L: ${num(r.kmPerLitre, "%.1f")}")
    add("Notes: ${r.notes.joinToString().ifEmpty { "none" }}")
    for (e in r.events) add("Event at %.0f s: %s %s".format(e.atSeconds, e.kind, e.detail.orEmpty()))
}
