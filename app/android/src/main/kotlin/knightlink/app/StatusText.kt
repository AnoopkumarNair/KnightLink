package knightlink.app

import android.content.res.Resources
import knightlink.app.session.ConnectionStatus
import knightlink.core.vehicle.FuelMethod
import knightlink.core.vehicle.ProbeStage

/** The connection in plain words, for the screen and for the notification. */
fun ConnectionStatus.text(resources: Resources): String = when (this) {
    ConnectionStatus.Idle -> resources.getString(R.string.status_idle)
    is ConnectionStatus.Connecting -> resources.getString(R.string.status_connecting, attempt)
    ConnectionStatus.Initialising -> resources.getString(R.string.status_initialising)
    is ConnectionStatus.Probing -> resources.getString(R.string.status_probing, resources.getString(stage.textRes()))
    is ConnectionStatus.WaitingForIgnition ->
        resources.getString(if (busFault) R.string.status_bus_not_reachable else R.string.status_waiting_for_ignition, probes)
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
