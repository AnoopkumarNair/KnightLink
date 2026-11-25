package knightlink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import knightlink.app.R
import knightlink.app.data.DashboardReadings
import knightlink.app.session.BodyScanProgress
import knightlink.app.session.DriveMode
import knightlink.app.session.DriveSummary
import knightlink.app.session.UiState
import knightlink.app.ui.design.KlCard
import knightlink.app.ui.design.KlColors
import knightlink.app.ui.design.KlSectionTitle
import knightlink.app.ui.design.KlSpacing
import knightlink.app.ui.design.KlTextField
import knightlink.app.ui.design.PrimaryButton
import knightlink.app.ui.design.SecondaryButton

/**
 * The dashboard readings of the drive on the friend-facing screens: odometer and range at the start and at the end, each with its Save and the
 * "Saved: ..." line. Shown whatever the connection is doing (the end readings are typed with the car off and the link gone). End readings of an
 * earlier drive are not shown as this drive's.
 */
@Composable
fun DriveReadingsCard(dashboard: DashboardReadings, onDashboard: (String, String, Boolean) -> Unit) {
    val endCurrent = dashboard.endIsCurrent
    KlCard {
        KlSectionTitle(stringResource(R.string.readings_title))
        Text(stringResource(R.string.readings_hint), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
        ReadingsEntry(
            R.string.dashboard_start_title,
            R.string.dashboard_save_start,
            dashboard.odometerStartKm,
            dashboard.rangeStartKm,
            dashboard.startSavedAtMs,
            startOdometer = null,
        ) { odo, dte -> onDashboard(odo, dte, false) }
        ReadingsEntry(
            R.string.dashboard_end_title,
            R.string.dashboard_save_end,
            dashboard.odometerEndKm.takeIf { endCurrent },
            dashboard.rangeEndKm.takeIf { endCurrent },
            dashboard.endSavedAtMs.takeIf { endCurrent },
            startOdometer = dashboard.odometerStartKm,
        ) { odo, dte -> onDashboard(odo, dte, true) }
    }
}

@Composable
private fun ReadingsEntry(
    title: Int,
    saveLabel: Int,
    odometer: Long?,
    range: Long?,
    savedAtMs: Long?,
    startOdometer: Long?,
    onSave: (String, String) -> Unit,
) {
    var odo by rememberSaveable(odometer, savedAtMs) { mutableStateOf(odometer?.toString().orEmpty()) }
    var dte by rememberSaveable(range, savedAtMs) { mutableStateOf(range?.toString().orEmpty()) }
    val focus = LocalFocusManager.current
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = KlColors.OnSurface)
    ReadingFields(odo, { odo = it }, dte, { dte = it })
    EndBelowStartWarning(odo, startOdometer)
    SecondaryButton(
        stringResource(saveLabel),
        {
            focus.clearFocus()
            onSave(odo, dte)
        },
        enabled = DashboardReadings.parseKm(odo) != null || DashboardReadings.parseKm(dte) != null,
    )
    if (odometer != null || range != null) {
        Text(
            stringResource(
                R.string.dashboard_saved,
                odometer?.toString() ?: "-",
                range?.toString() ?: "-",
                savedAtMs?.let(::formatSavedAt) ?: stringResource(R.string.dashboard_saved_earlier),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = KlColors.OnSurfaceMuted,
        )
    }
}

@Composable
private fun ReadingFields(odo: String, onOdo: (String) -> Unit, dte: String, onDte: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KlSpacing.S)) {
        KlTextField(odo, { onOdo(it.filter(Char::isDigit).take(READING_DIGITS)) }, stringResource(R.string.dashboard_odometer), Modifier.weight(1f), number = true)
        KlTextField(dte, { onDte(it.filter(Char::isDigit).take(READING_DIGITS)) }, stringResource(R.string.dashboard_range), Modifier.weight(1f), number = true)
    }
}

@Composable
private fun EndBelowStartWarning(odo: String, startOdometer: Long?) {
    val typed = DashboardReadings.parseKm(odo)
    if (startOdometer != null && typed != null && typed < startOdometer) {
        Text(stringResource(R.string.readings_end_before_start, startOdometer.toString()), style = MaterialTheme.typography.bodySmall, color = KlColors.Attention)
    }
}

/**
 * The end-of-drive prompt: "Enter the end odometer and range", the two fields, Save and Skip. As a dialog ([dialog]) when the driver taps
 * Disconnect, or as a prominent card when the drive has ended otherwise.
 */
@Composable
fun EndReadingsPrompt(dashboard: DashboardReadings, dialog: Boolean, onSave: (String, String) -> Unit, onSkip: () -> Unit) {
    var odo by rememberSaveable { mutableStateOf("") }
    var dte by rememberSaveable { mutableStateOf("") }
    val canSave = DashboardReadings.parseKm(odo) != null || DashboardReadings.parseKm(dte) != null
    val body: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(KlSpacing.S)) {
            Text(stringResource(R.string.readings_end_prompt_body), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
            ReadingFields(odo, { odo = it }, dte, { dte = it })
            EndBelowStartWarning(odo, dashboard.odometerStartKm)
        }
    }
    if (dialog) {
        AlertDialog(
            onDismissRequest = onSkip,
            title = { Text(stringResource(R.string.readings_end_prompt)) },
            text = body,
            confirmButton = { TextButton(onClick = { onSave(odo, dte) }, enabled = canSave) { Text(stringResource(R.string.readings_end_save)) } },
            dismissButton = { TextButton(onClick = onSkip) { Text(stringResource(R.string.readings_end_skip)) } },
        )
    } else {
        KlCard {
            Text(stringResource(R.string.readings_end_prompt), style = MaterialTheme.typography.titleLarge, color = KlColors.Attention)
            body()
            PrimaryButton(stringResource(R.string.readings_end_save), { onSave(odo, dte) }, enabled = canSave)
            SecondaryButton(stringResource(R.string.readings_end_skip), onSkip)
        }
    }
}

/** Eco / City / Sport: a tap writes the mode into the drive's recording (the same `mode` line the Developer page writes). */
@Composable
fun DriveModeCard(drive: DriveSummary?, recording: Boolean, onMode: (DriveMode) -> Unit) {
    KlCard {
        KlSectionTitle(stringResource(R.string.mode_title))
        Text(
            stringResource(if (recording) R.string.mode_hint else R.string.mode_not_recording),
            style = MaterialTheme.typography.bodyMedium,
            color = KlColors.OnSurfaceMuted,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KlSpacing.S)) {
            DriveMode.entries.forEach { mode ->
                FilterChip(
                    selected = drive?.mode == mode,
                    onClick = { onMode(mode) },
                    enabled = recording,
                    label = { Text(stringResource(mode.label())) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The parked check of the car's other units: while it runs, "Keep the car parked, N s left" with Skip; when it ended early (or was skipped) and
 * units are still pending, "Run the parked check again". Nothing otherwise.
 */
@Composable
fun ParkedCheckCard(state: UiState, onRunAgain: () -> Unit, onSkip: () -> Unit) {
    val body = state.bodyScan
    when {
        // Started by itself while the car stands: one quiet line, no countdown, no skip button to reach for at a light.
        body is BodyScanProgress.Running && body.auto -> Text(
            stringResource(R.string.bodyscan_auto_running),
            style = MaterialTheme.typography.bodyMedium,
            color = KlColors.OnSurfaceMuted,
        )
        body is BodyScanProgress.Running -> KlCard {
            Text(
                stringResource(R.string.parked_again_running, body.secondsLeft),
                style = MaterialTheme.typography.titleLarge,
                color = KlColors.Attention,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            LinearProgressIndicator(progress = { body.fraction }, modifier = Modifier.fillMaxWidth())
            SecondaryButton(stringResource(R.string.action_skip), onSkip)
        }
        state.canRunParkedCheckAgain -> KlCard {
            KlSectionTitle(stringResource(R.string.parked_again_title))
            val retried = (body as? BodyScanProgress.Skipped)?.retried == true || (state.bodyResult as? BodyScanProgress.Skipped)?.retried == true
            if (retried) {
                Text(stringResource(R.string.parked_again_retried), style = MaterialTheme.typography.bodyMedium, color = KlColors.Attention)
            }
            Text(stringResource(R.string.parked_again_body), style = MaterialTheme.typography.bodyMedium, color = KlColors.OnSurfaceMuted)
            PrimaryButton(stringResource(R.string.parked_again_action), onRunAgain)
        }
    }
}

private fun DriveMode.label(): Int = when (this) {
    DriveMode.ECO -> R.string.drive_mode_eco
    DriveMode.CITY -> R.string.drive_mode_city
    DriveMode.SPORT -> R.string.drive_mode_sport
}

private fun formatSavedAt(atMs: Long): String =
    if (atMs <= 0L) "" else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(atMs))

private const val READING_DIGITS = 7
