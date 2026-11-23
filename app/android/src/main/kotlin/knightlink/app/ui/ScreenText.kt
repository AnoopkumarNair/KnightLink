package knightlink.app.ui

import android.content.res.Resources
import knightlink.app.R
import knightlink.app.session.CoverageDomain
import knightlink.app.session.CoverageNote
import knightlink.app.session.CoverageState
import knightlink.app.session.DriveTruthCardModel
import knightlink.app.session.DriveTruthLine
import knightlink.app.session.FuelType
import knightlink.app.session.GuidedStep
import knightlink.app.session.StepDetail
import knightlink.app.session.StepSentence
import knightlink.app.session.StepState
import knightlink.app.session.Transmission
import knightlink.app.session.formatKm
import knightlink.app.session.formatMinutes
import knightlink.app.session.formatRupees
import knightlink.app.session.formatSignedPercent
import knightlink.app.ui.design.StepVisualState
import knightlink.app.ui.design.Tone
import knightlink.core.runtime.drivetruth.DriveTruthNote
import knightlink.core.runtime.drivetruth.FuelSource

/** The words of the friend-facing screens: each model value maps to one string resource, here and nowhere else. */

fun GuidedStep.titleRes(): Int = when (this) {
    GuidedStep.ADAPTER -> R.string.step_adapter
    GuidedStep.IGNITION -> R.string.step_ignition
    GuidedStep.CAR_ANSWERS -> R.string.step_answers
    GuidedStep.CAR_IDENTIFIED -> R.string.step_identified
    GuidedStep.HEALTH_CHECK -> R.string.step_health
    GuidedStep.READY_TO_DRIVE -> R.string.step_ready
    GuidedStep.DRIVE_DETECTED -> R.string.step_drive
    GuidedStep.DRIVE_ENDED -> R.string.step_report
}

fun StepState.visual(): StepVisualState = when (this) {
    StepState.PENDING -> StepVisualState.PENDING
    StepState.ACTIVE -> StepVisualState.ACTIVE
    StepState.DONE -> StepVisualState.DONE
    StepState.FAILED -> StepVisualState.FAILED
}

fun StepState.wordRes(): Int = when (this) {
    StepState.PENDING -> R.string.step_state_pending
    StepState.ACTIVE -> R.string.step_state_active
    StepState.DONE -> R.string.step_state_done
    StepState.FAILED -> R.string.step_state_failed
}

/** The one plain sentence of a step. */
fun StepDetail.text(resources: Resources): String {
    val a = arg
    return when (sentence) {
        StepSentence.ADAPTER_WAITING -> resources.getString(R.string.s_adapter_waiting)
        StepSentence.ADAPTER_CONNECTING -> resources.getString(R.string.s_adapter_connecting)
        StepSentence.ADAPTER_FOUND -> resources.getString(R.string.s_adapter_found)
        StepSentence.ADAPTER_LINK_LOST -> resources.getString(R.string.s_adapter_link_lost)
        StepSentence.ADAPTER_FAILED -> resources.getString(R.string.s_adapter_failed)
        StepSentence.IGNITION_PROMPT -> resources.getString(R.string.s_ignition_prompt)
        StepSentence.IGNITION_WAITING -> resources.getString(R.string.s_ignition_waiting)
        StepSentence.IGNITION_OK -> resources.getString(R.string.s_ignition_ok)
        StepSentence.IGNITION_FAILED -> resources.getString(R.string.s_ignition_failed)
        StepSentence.ANSWER_LATER -> resources.getString(R.string.s_answer_later)
        StepSentence.ANSWER_LOOKING -> resources.getString(R.string.s_answer_looking)
        StepSentence.ANSWER_OK -> if (a != null) resources.getString(R.string.s_answer_ok_protocol, a) else resources.getString(R.string.s_answer_ok)
        StepSentence.ANSWER_PROTOCOL_UNSUPPORTED ->
            if (a != null) resources.getString(R.string.s_answer_unsupported_protocol, a) else resources.getString(R.string.s_answer_unsupported)
        StepSentence.ANSWER_GATEWAY -> resources.getString(R.string.s_answer_gateway)
        StepSentence.IDENTIFY_LATER -> resources.getString(R.string.s_identify_later)
        StepSentence.IDENTIFY_READING -> resources.getString(R.string.s_identify_reading)
        StepSentence.IDENTIFY_OK -> if (a != null) resources.getString(R.string.s_identify_ok_name, a) else resources.getString(R.string.s_identify_ok)
        StepSentence.HEALTH_LATER -> resources.getString(R.string.s_health_later)
        StepSentence.HEALTH_RUNNING -> if (a != null) resources.getString(R.string.s_health_running_unit, a) else resources.getString(R.string.s_health_running)
        StepSentence.HEALTH_LEARNING -> resources.getString(R.string.s_health_learning)
        StepSentence.HEALTH_OK -> resources.getString(R.string.s_health_ok)
        StepSentence.HEALTH_STOPPED -> resources.getString(R.string.s_health_stopped)
        StepSentence.HEALTH_FLAGGED -> resources.getString(R.string.s_health_flagged)
        StepSentence.READY_LATER -> resources.getString(R.string.s_ready_later)
        StepSentence.READY_NOW -> resources.getString(R.string.s_ready_now)
        StepSentence.READY_DONE -> resources.getString(R.string.s_ready_done)
        StepSentence.DRIVE_LATER -> resources.getString(R.string.s_drive_later)
        StepSentence.DRIVE_WAITING -> resources.getString(R.string.s_drive_waiting)
        StepSentence.DRIVE_UNDER_WAY -> resources.getString(R.string.s_drive_under_way)
        StepSentence.DRIVE_STARTED -> resources.getString(R.string.s_drive_started)
        StepSentence.REPORT_LATER -> resources.getString(R.string.s_report_later)
        StepSentence.REPORT_BUILDING -> resources.getString(R.string.s_report_building)
        StepSentence.REPORT_READY -> resources.getString(R.string.s_report_ready)
    }
}

fun CoverageDomain.titleRes(): Int = when (this) {
    CoverageDomain.IDENTITY -> R.string.cov_identity
    CoverageDomain.ENGINE_BASICS -> R.string.cov_engine
    CoverageDomain.FUEL -> R.string.cov_fuel
    CoverageDomain.DISTANCE -> R.string.cov_distance
    CoverageDomain.FAULTS -> R.string.cov_faults
    CoverageDomain.GEARBOX -> R.string.cov_gearbox
    CoverageDomain.DRIVE_MODE -> R.string.cov_drive_mode
    CoverageDomain.TYRES -> R.string.cov_tyres
    CoverageDomain.BODY_UNITS -> R.string.cov_body
    CoverageDomain.EMISSIONS -> R.string.cov_emissions
}

fun CoverageState.wordRes(): Int = when (this) {
    CoverageState.COLLECTED -> R.string.cov_state_collected
    CoverageState.PARTIAL -> R.string.cov_state_partial
    CoverageState.NOT_AVAILABLE -> R.string.cov_state_unavailable
    CoverageState.NOT_TRIED -> R.string.cov_state_not_tried
}

fun CoverageState.tone(): Tone = when (this) {
    CoverageState.COLLECTED -> Tone.GOOD
    CoverageState.PARTIAL -> Tone.ATTENTION
    CoverageState.NOT_AVAILABLE -> Tone.NEUTRAL
    CoverageState.NOT_TRIED -> Tone.NEUTRAL
}

fun CoverageNote.wordRes(): Int = when (this) {
    CoverageNote.MODEL_MISSING -> R.string.cov_note_model_missing
    CoverageNote.FROM_YOU -> R.string.cov_note_from_you
    CoverageNote.CALCULATED -> R.string.cov_note_calculated
    CoverageNote.UNCONFIRMED -> R.string.cov_note_unconfirmed
    CoverageNote.APPROXIMATE -> R.string.cov_note_approximate
    CoverageNote.NEEDS_FULL_PROFILE -> R.string.cov_note_needs_profile
    CoverageNote.ASKED_NOTHING_ANSWERED -> R.string.cov_note_asked_nothing
    CoverageNote.STOPPED_EARLY -> R.string.cov_note_stopped
    CoverageNote.IN_PROGRESS -> R.string.cov_note_in_progress
    CoverageNote.DEEP_SCAN_OFF -> R.string.cov_note_deep_off
    CoverageNote.NO_READINESS -> R.string.cov_note_no_readiness
}

fun FuelType.labelRes(): Int = when (this) {
    FuelType.PETROL -> R.string.fuel_petrol
    FuelType.DIESEL -> R.string.fuel_diesel
    FuelType.CNG -> R.string.fuel_cng
    FuelType.HYBRID -> R.string.fuel_hybrid
}

fun Transmission.labelRes(): Int = when (this) {
    Transmission.MANUAL -> R.string.gearbox_manual
    Transmission.AUTOMATIC -> R.string.gearbox_automatic
}

/** One line of the Drive Truth card in words. The card and the shared text use the same lines. */
fun DriveTruthLine.text(resources: Resources): String = when (this) {
    is DriveTruthLine.Cost -> when {
        km != null && perKm != null -> resources.getString(R.string.dt_cost_full, formatRupees(rupees), formatKm(km), formatRupees(perKm))
        km != null -> resources.getString(R.string.dt_cost_km, formatRupees(rupees), formatKm(km))
        else -> resources.getString(R.string.dt_cost_only, formatRupees(rupees))
    }
    is DriveTruthLine.Fuel ->
        kmPerLitre?.let { resources.getString(R.string.dt_fuel_economy, formatKm(litres), formatKm(it)) }
            ?: resources.getString(R.string.dt_fuel, formatKm(litres))
    is DriveTruthLine.Idle ->
        idleText(resources, minutes, costRupees)
    is DriveTruthLine.Distance -> when {
        carKm != null && gpsKm != null -> resources.getString(R.string.dt_distance_both, formatKm(carKm), formatKm(gpsKm))
        carKm != null -> resources.getString(R.string.dt_distance_car, formatKm(carKm))
        else -> resources.getString(R.string.dt_distance_gps, formatKm(gpsKm ?: 0.0))
    }
    is DriveTruthLine.CarVsGps -> resources.getString(R.string.dt_vs_gps, formatSignedPercent(percent))
    is DriveTruthLine.Time -> resources.getString(R.string.dt_time, formatMinutes(totalMinutes), formatMinutes(movingMinutes))
    is DriveTruthLine.Method -> resources.getString(
        when (source) {
            FuelSource.FUEL_RATE_LPH, FuelSource.FUEL_RATE_GPS -> R.string.dt_method_rate
            FuelSource.MAF -> R.string.dt_method_maf
            FuelSource.FUEL_LEVEL_DROP -> R.string.dt_method_level
        },
    )
}

private fun idleText(resources: Resources, minutes: Double, cost: Double?): String =
    if (cost != null) {
        resources.getString(R.string.dt_idle_cost, formatMinutes(minutes), formatRupees(cost))
    } else {
        resources.getString(R.string.dt_idle, formatMinutes(minutes))
    }

fun DriveTruthNote.textRes(): Int = when (this) {
    DriveTruthNote.FUEL_NOT_REPORTED -> R.string.dt_note_fuel_not_reported
    DriveTruthNote.FUEL_ONLY_APPROXIMATE -> R.string.dt_note_fuel_approx
    DriveTruthNote.FUEL_PRICE_MISSING -> R.string.dt_note_price_missing
    DriveTruthNote.GPS_POOR -> R.string.dt_note_gps_poor
    DriveTruthNote.SPEED_SIGNAL_GAPS -> R.string.dt_note_speed_gaps
    DriveTruthNote.TOO_SHORT_TO_COMPARE -> R.string.dt_note_too_short
    DriveTruthNote.DRIVE_NOT_ENDED -> R.string.dt_note_not_ended
    DriveTruthNote.FUEL_FROM_AIRFLOW -> R.string.dt_note_airflow
    DriveTruthNote.FUEL_DENSITY_ASSUMED -> R.string.dt_note_density
}

/** The card as plain text for the share sheet: the same lines and notes the card shows, a header first. */
fun DriveTruthCardModel.shareText(resources: Resources): String = buildString {
    appendLine(resources.getString(R.string.dt_share_header))
    lines.forEach { appendLine(it.text(resources)) }
    if (notes.isNotEmpty()) appendLine()
    notes.forEach { appendLine(resources.getString(it.textRes())) }
}.trimEnd()
