package knightlink.app

import android.content.res.Resources
import knightlink.app.session.BodyScanProgress
import knightlink.app.session.ConnectionStatus
import knightlink.app.session.ScanStatus
import knightlink.core.runtime.BodyOutcome
import knightlink.core.runtime.CannotReadReason
import knightlink.core.scan.ScanOutcome
import knightlink.core.scan.ScanStage
import knightlink.core.vehicle.FuelMethod
import knightlink.core.vehicle.ProbeStage

/**
 * The parked scan's early end in plain words: [spoken] for the voice and the notification, else for the screen. "You can drive" only when the
 * stage really ended (the time cap or the user's skip); otherwise it says the scan stopped early and why (on 2026-11-24 it said "you can drive"
 * after a single speed reading of 3 km/h ended the stage 21 s in).
 */
fun BodyScanProgress.Partial.text(resources: Resources, spoken: Boolean = false): String = when (ended) {
    BodyOutcome.MOVING -> speedKmh?.let { resources.getString(if (spoken) R.string.bodyscan_spoken_partial_moving else R.string.bodyscan_partial_moving, it) }
        ?: resources.getString(if (spoken) R.string.bodyscan_spoken_partial_moving_unknown else R.string.bodyscan_partial_moving_unknown)
    BodyOutcome.SPEED_UNKNOWN -> resources.getString(if (spoken) R.string.bodyscan_spoken_partial_speed_unknown else R.string.bodyscan_partial_speed_unknown)
    BodyOutcome.FAILED -> resources.getString(if (spoken) R.string.bodyscan_spoken_partial_failed else R.string.bodyscan_partial_failed)
    BodyOutcome.SKIPPED -> resources.getString(if (spoken) R.string.bodyscan_spoken_partial_skipped else R.string.bodyscan_partial_skipped)
    BodyOutcome.TIME_CAP, BodyOutcome.BUDGET, BodyOutcome.COMPLETED ->
        resources.getString(if (spoken) R.string.bodyscan_spoken_partial else R.string.bodyscan_partial)
}

/** The connection in plain words, for the screen and for the notification. */
fun ConnectionStatus.text(resources: Resources): String = when (this) {
    ConnectionStatus.Idle -> resources.getString(R.string.status_idle)
    is ConnectionStatus.Connecting -> resources.getString(R.string.status_connecting, attempt)
    ConnectionStatus.Initialising -> resources.getString(R.string.status_initialising)
    is ConnectionStatus.Probing -> resources.getString(R.string.status_probing, resources.getString(stage.textRes()))
    is ConnectionStatus.Negotiating -> resources.getString(R.string.status_negotiating)
    is ConnectionStatus.Learning -> resources.getString(R.string.status_learning, level)
    is ConnectionStatus.CannotRead -> when (val why = reason) {
        CannotReadReason.NoResponse -> resources.getString(R.string.status_cannot_read_no_response)
        is CannotReadReason.UnsupportedProtocol -> resources.getString(R.string.status_cannot_read_protocol, why.protocolName)
        CannotReadReason.GatewayBlocked -> resources.getString(R.string.status_cannot_read_gateway)
    }
    is ConnectionStatus.WaitingForIgnition ->
        resources.getString(if (busFault) R.string.status_bus_not_reachable else R.string.status_waiting_for_ignition, probes)
    is ConnectionStatus.VehicleAsleep -> resources.getString(R.string.status_vehicle_asleep, checks)
    is ConnectionStatus.Recovering -> resources.getString(R.string.status_recovering, attempt, reason)
    ConnectionStatus.Live -> resources.getString(R.string.status_live)
    is ConnectionStatus.Reconnecting -> resources.getString(R.string.status_reconnecting, retryInSeconds, cause)
    is ConnectionStatus.Failed -> resources.getString(R.string.status_failed, reason)
}

private fun ProbeStage.textRes(): Int = when (this) {
    ProbeStage.ADAPTER -> R.string.probe_stage_adapter
    ProbeStage.ECU_SWEEP -> R.string.probe_stage_ecu_sweep
    ProbeStage.LIVE_DATA_SUPPORT -> R.string.probe_stage_live_data_support
    ProbeStage.VEHICLE_INFO -> R.string.probe_stage_vehicle_info
    ProbeStage.BENCHMARK -> R.string.probe_stage_benchmark
    ProbeStage.DERIVED_FACTS -> R.string.probe_stage_derived_facts
}

fun FuelMethod.textRes(): Int = when (this) {
    FuelMethod.DIRECT_RATE -> R.string.fuel_direct_rate
    FuelMethod.MAF -> R.string.fuel_maf
    FuelMethod.SPEED_DENSITY -> R.string.fuel_speed_density
    FuelMethod.UNKNOWN -> R.string.fuel_unknown
}

/**
 * The scan's progress or result in sentences, for the screen (and, later, to be spoken): what it is doing and how far, a notice when the
 * car moves, and at the end what it found. Null when there is nothing to say (no scan, or one that can be started).
 */
fun ScanStatus.text(resources: Resources): String? = when (this) {
    ScanStatus.Unavailable, ScanStatus.Ready -> null
    ScanStatus.Starting -> resources.getString(R.string.scan_starting)
    is ScanStatus.Running -> when {
        paused && speedKmh != null -> resources.getString(R.string.scan_paused, speedKmh)
        paused -> resources.getString(R.string.scan_paused_unknown)
        else -> when (stage) {
            ScanStage.PROBE -> resources.getString(R.string.scan_stage_probe, subject.orEmpty()).trim()
            ScanStage.READINESS -> resources.getString(R.string.scan_stage_readiness, subject.orEmpty(), done + 1, total)
            ScanStage.DISCOVERY -> resources.getString(R.string.scan_stage_discovery, done, total)
            ScanStage.IDENTIFICATION -> resources.getString(R.string.scan_stage_identification, subject.orEmpty(), done, total)
            ScanStage.FAULTS -> resources.getString(R.string.scan_stage_faults, subject.orEmpty(), done + 1, total)
            ScanStage.SWEEP -> resources.getString(R.string.scan_stage_sweep, subject.orEmpty(), done, total)
            null -> resources.getString(R.string.scan_stage_working)
        }
    }
    is ScanStatus.Finished -> {
        val found = summary
        val identifiers = found.didsPerEcu.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        when (outcome) {
            ScanOutcome.COMPLETED ->
                resources.getString(
                    R.string.scan_finished,
                    minutes,
                    found.ecus,
                    found.confirmed,
                    found.pending,
                    found.warningLamp,
                    found.notCompleted,
                ) + if (identifiers.isNotEmpty()) " " + resources.getString(R.string.scan_finished_ids, identifiers) else ""
            ScanOutcome.CANCELLED ->
                resources.getString(R.string.scan_stopped_by_user, minutes, found.ecus)
            ScanOutcome.STOPPED -> resources.getString(R.string.scan_stopped_early, minutes, detail ?: "", found.ecus)
            ScanOutcome.LINK_LOST -> resources.getString(R.string.scan_link_lost, minutes, found.ecus)
        }
    }
    is ScanStatus.Failed -> resources.getString(R.string.scan_failed, reason)
}

/** How far the stage of a running scan has got, 0 to 1; null when that is not known. */
fun ScanStatus.Running.fraction(): Float? = if (paused || total <= 0) null else (done.toFloat() / total).coerceIn(0f, 1f)
