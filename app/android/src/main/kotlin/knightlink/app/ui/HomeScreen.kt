package knightlink.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import knightlink.app.R
import knightlink.app.ble.LastAdapter
import knightlink.app.data.DashboardReadings
import knightlink.app.fraction
import knightlink.app.session.BodyScanProgress
import knightlink.app.session.ConnectionStatus
import knightlink.app.session.DriveMode
import knightlink.app.session.DriveSummary
import knightlink.app.session.FaultSummary
import knightlink.app.session.FaultsStatus
import knightlink.app.session.LadderProgress
import knightlink.app.session.LiveValue
import knightlink.app.session.RecordingsSummary
import knightlink.app.session.ScanStatus
import knightlink.app.session.TyreSensors
import knightlink.app.session.UiState
import knightlink.app.session.ValueGroup
import knightlink.app.session.VehicleSummary
import knightlink.app.session.groupByPrefix
import knightlink.app.text
import knightlink.app.textRes
import knightlink.app.voice.MediaButtonGesture
import knightlink.app.voice.PushToTalkState
import knightlink.app.voice.RecognitionError
import knightlink.app.voice.VoiceSettings
import knightlink.core.runtime.CaptureMode
import knightlink.core.vehicle.VehicleIdentity
import kotlin.math.roundToInt

/** Wide enough for the cards and the live values side by side: any phone in landscape. */
private val WIDE_LAYOUT_MIN_WIDTH = 600.dp
private val SCREEN_PADDING = 16.dp
private val ITEM_SPACING = 12.dp

/** What the connection card can do; one object so the screen's signature does not grow with every way to connect. */
class ConnectionActions(
    val onStartDemo: () -> Unit,
    val onConnectLast: (LastAdapter) -> Unit,
    val onChooseAdapter: () -> Unit,
    val onDisconnect: () -> Unit,
    val onStartScan: () -> Unit = {},
    val onResumeScan: () -> Unit = {},
    val onCancelScan: () -> Unit = {},
    val onDismissScan: () -> Unit = {},
    /** False while Android may stop the app in the background (battery optimisation applies); the card then asks the user to allow it. */
    val backgroundAllowed: Boolean = true,
    val onAllowBackground: () -> Unit = {},
    /** The driver says the car is now in this drive mode; it is stamped into the recording. */
    val onDriveMode: (DriveMode) -> Unit = {},
    /** True switches the session to passive capture (it only listens to the car's broadcast), false back to active polling. */
    val onPassiveCapture: (Boolean) -> Unit = {},
    /** The driver typed what the dashboard shows: odometer and range as text, and whether it is the end of the drive (else the start). */
    val onDashboard: (odometer: String, range: String, end: Boolean) -> Unit = { _, _, _ -> },
    /** After "can't read this car": negotiates and probes again. */
    val onRetry: () -> Unit = {},
    /** Reads the fault codes again (read-only). */
    val onRefreshFaults: () -> Unit = {},
    /** The deep scan switch for a car the app has no definition for. */
    val onDeepScan: (Boolean) -> Unit = {},
    /** Ends the learning ladder that runs now. */
    val onSkipLadder: () -> Unit = {},
    /** "Scan again": runs the learning ladder once more. */
    val onRescan: () -> Unit = {},
)

/**
 * What the push-to-talk card shows and can do.
 *
 * @property onDevice true when speech is recognised on the phone
 */
class TalkActions(
    val state: PushToTalkState,
    val onDevice: Boolean,
    val gesture: MediaButtonGesture,
    val onTalk: () -> Unit,
    val onGesture: (MediaButtonGesture) -> Unit,
) {
    companion object {
        val NONE = TalkActions(PushToTalkState.Idle, onDevice = false, gesture = MediaButtonGesture.OFF, onTalk = {}, onGesture = {})
    }
}

/** What the drive-data card shows and can do: whether the phone's location is being recorded, and how to allow it. */
class DriveDataActions(val locationAllowed: Boolean, val onAllowLocation: () -> Unit)

/**
 * What the voice card shows and can do. [available] is false until the service (which owns the voice) is bound: the controls are
 * then shown but disabled.
 *
 * @property lastSpoken the text of the last line spoken, shown under the buttons; null before the first
 */
class VoiceActions(
    val settings: VoiceSettings,
    val lastSpoken: String?,
    val available: Boolean,
    val onSpokenStatusChange: (Boolean) -> Unit,
    val onVolumeChange: (Float) -> Unit,
    val onTest: () -> Unit,
    val onSayAgain: () -> Unit,
    val activePersonaName: String = "K.I.T.T.",
    val activeArchetype: String = "bar-meter",
    val visualizerLevel: Float = 0f,
) {
    companion object {
        val Unavailable = VoiceActions(VoiceSettings(), null, false, {}, {}, {}, {})
    }
}

/**
 * The one screen. Portrait: a single scrolling list (cards, then the live values). Landscape: the cards on the left, the live
 * values on the right, each scrolling on its own, so the values stay in view while the buttons are reachable.
 *
 * Both layouts render the live values lazily, with a stable key per row, so a refresh redraws only the rows whose value changed.
 */
@Composable
fun HomeScreen(
    state: UiState,
    recordings: RecordingsSummary,
    lastAdapter: LastAdapter?,
    actions: ConnectionActions,
    onShareLatest: () -> Unit,
    onShareLog: () -> Unit,
    modifier: Modifier = Modifier,
    driveData: DriveDataActions = DriveDataActions(locationAllowed = false, onAllowLocation = {}),
    voice: VoiceActions = VoiceActions.Unavailable,
    talk: TalkActions = TalkActions.NONE,
) {
    // Grouping is a function of the values only; it is redone when they change, not on every recomposition.
    val groups = remember(state.liveValues) { groupByPrefix(state.liveValues) }
    val padding = PaddingValues(SCREEN_PADDING)
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        if (maxWidth >= WIDE_LAYOUT_MIN_WIDTH) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
                    summaryItems(state, recordings, lastAdapter, actions, driveData, voice, talk, onShareLatest, onShareLog)
                }
                LazyColumn(Modifier.weight(1f), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
                    liveItems(groups, state.captureMode == CaptureMode.PASSIVE)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
                summaryItems(state, recordings, lastAdapter, actions, driveData, voice, talk, onShareLatest, onShareLog)
                liveItems(groups, state.captureMode == CaptureMode.PASSIVE)
            }
        }
    }
}

private fun LazyListScope.summaryItems(
    state: UiState,
    recordings: RecordingsSummary,
    lastAdapter: LastAdapter?,
    actions: ConnectionActions,
    driveData: DriveDataActions,
    voice: VoiceActions,
    talk: TalkActions,
    onShareLatest: () -> Unit,
    onShareLog: () -> Unit,
) {
    item(key = "talk", contentType = "card") { TalkCard(talk) }
    item(key = "connection", contentType = "card") { ConnectionCard(state.connection, state.scan, lastAdapter, actions, state.deepScan, state.knownVehicle) }
    if (state.ladder != LadderProgress.Idle) {
        item(key = "ladder", contentType = "card") { LadderBanner(state.ladder, actions.onSkipLadder) }
    }
    if (state.identity != null || state.faults.status != FaultsStatus.NOT_READ) {
        item(key = "faults", contentType = "card") { IdentityAndFaultsCard(state.identity, state.faults, actions.onRefreshFaults) }
    }
    item(key = "voice", contentType = "card") { VoiceCard(voice) }
    state.vehicle?.let { vehicle -> item(key = "vehicle", contentType = "card") { VehicleCard(vehicle, state.pollingRate) } }
    // Before the vehicle summary exists (the scan runs before polling starts), so it is its own item and not part of the drive card.
    if (state.bodyScan != BodyScanProgress.Idle) {
        item(key = "bodyscan", contentType = "card") { BodyScanBanner(state.bodyScan) }
    }
    if (state.vehicle != null) {
        item(key = "drive", contentType = "card") {
            DriveCard(state.drive, state.captureMode, state.connection, state.dashboard, actions.onDriveMode, actions.onPassiveCapture, actions.onDashboard)
        }
    }
    item(key = "drive-data", contentType = "card") { DriveDataCard(driveData, state.tyres) }
    item(key = "recordings", contentType = "card") { RecordingsCard(recordings, onShareLatest, onShareLog) }
}

/** The drive mode buttons (tap when the car's mode changes, so the recording says which mode each stretch was driven in) and the last refuel. */
@Composable
private fun DriveCard(
    drive: DriveSummary?,
    captureMode: CaptureMode,
    connection: ConnectionStatus,
    dashboard: DashboardReadings,
    onMode: (DriveMode) -> Unit,
    onPassiveCapture: (Boolean) -> Unit,
    onDashboard: (String, String, Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.drive_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.drive_mode_hint), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DriveMode.entries.forEach { mode ->
                    FilterChip(
                        selected = drive?.mode == mode,
                        onClick = { onMode(mode) },
                        label = { Text(stringResource(mode.labelRes()), modifier = Modifier.padding(vertical = 8.dp)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val passive = captureMode == CaptureMode.PASSIVE
            // Only while the car is being polled (to switch on) or while capturing (to switch off): not while connecting or recovering.
            OutlinedButton(
                onClick = { onPassiveCapture(!passive) },
                enabled = passive || connection == ConnectionStatus.Live,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (passive) R.string.capture_back_to_active else R.string.capture_passive))
            }
            Text(stringResource(R.string.capture_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.dashboard_hint), style = MaterialTheme.typography.bodySmall)
            DashboardEntry(R.string.dashboard_start_title, R.string.dashboard_save_start, dashboard.odometerStartKm, dashboard.rangeStartKm) { odo, dte ->
                onDashboard(odo, dte, false)
            }
            DashboardEntry(R.string.dashboard_end_title, R.string.dashboard_save_end, dashboard.odometerEndKm, dashboard.rangeEndKm) { odo, dte ->
                onDashboard(odo, dte, true)
            }
            val fill = drive?.lastFill
            Text(
                if (fill == null) {
                    stringResource(R.string.drive_no_refuel)
                } else {
                    stringResource(R.string.drive_last_refuel, fill.litres, fill.fromPercent, fill.toPercent, formatWhen(fill.atMs))
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (drive != null &&
                drive.fillCount > 1
            ) {
                Text(stringResource(R.string.drive_refuel_count, drive.fillCount), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Two number fields (odometer, range) and the button that saves them: what the dashboard shows at the start or at the end of the drive. */
@Composable
private fun DashboardEntry(title: Int, saveLabel: Int, odometer: Long?, range: Long?, onSave: (String, String) -> Unit) {
    var odo by rememberSaveable(odometer) { mutableStateOf(odometer?.toString().orEmpty()) }
    var dte by rememberSaveable(range) { mutableStateOf(range?.toString().orEmpty()) }
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = odo,
            onValueChange = { odo = it.filter(Char::isDigit).take(DASHBOARD_DIGITS) },
            label = { Text(stringResource(R.string.dashboard_odometer)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = dte,
            onValueChange = { dte = it.filter(Char::isDigit).take(DASHBOARD_DIGITS) },
            label = { Text(stringResource(R.string.dashboard_range)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    OutlinedButton(onClick = { onSave(odo, dte) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(saveLabel)) }
}

private const val DASHBOARD_DIGITS = 7

/** Tells the driver to keep the car parked while the other units are scanned, how long it will take, and when they may drive. */
@Composable
private fun BodyScanBanner(progress: BodyScanProgress) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (progress) {
                BodyScanProgress.Idle -> Unit
                is BodyScanProgress.Running -> {
                    Text(stringResource(R.string.bodyscan_running, progress.secondsLeft), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.bodyscan_running_unit, progress.label, progress.done, progress.total),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is BodyScanProgress.Complete -> Text(stringResource(R.string.bodyscan_complete), style = MaterialTheme.typography.titleMedium)
                is BodyScanProgress.Partial -> Text(stringResource(R.string.bodyscan_partial), style = MaterialTheme.typography.titleMedium)
                is BodyScanProgress.Skipped ->
                    Text(
                        stringResource(if (progress.moving) R.string.bodyscan_skipped_moving else R.string.bodyscan_skipped_unknown),
                        style = MaterialTheme.typography.titleMedium,
                    )
            }
        }
    }
}

private fun DriveMode.labelRes(): Int = when (this) {
    DriveMode.ECO -> R.string.drive_mode_eco
    DriveMode.CITY -> R.string.drive_mode_city
    DriveMode.SPORT -> R.string.drive_mode_sport
}

/** A refuel's time in the phone's own style, or an empty text when the session had no clock. */
private fun formatWhen(atMs: Long): String =
    if (atMs <= 0L) {
        ""
    } else {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(atMs))
    }

/** Push to talk: the big button, what was heard and answered, where recognition happens, and the steering-wheel button setting. */
@Composable
private fun TalkCard(talk: TalkActions) {
    val state = talk.state
    val listening = state is PushToTalkState.Listening
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.talk_title), style = MaterialTheme.typography.titleMedium)
            Button(onClick = talk.onTalk, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)) {
                Text(
                    stringResource(if (listening) R.string.talk_listening else R.string.talk_ask),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            // One live region for whatever the card has to say, so a screen reader reads the answer (or the failure) without being asked.
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state) {
                    PushToTalkState.Idle -> Unit
                    is PushToTalkState.Listening ->
                        if (state.partial.isNotBlank()) Text(stringResource(R.string.talk_you, state.partial), style = MaterialTheme.typography.bodyLarge)
                    is PushToTalkState.Answered -> {
                        Text(stringResource(R.string.talk_you, state.heard), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.talk_answer, state.answerText), style = MaterialTheme.typography.titleMedium)
                    }
                    is PushToTalkState.Failed -> Text(stringResource(state.error.message()), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(
                stringResource(if (talk.onDevice) R.string.talk_on_device else R.string.talk_online),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.talk_gesture_title), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((gesture, label) in GESTURE_LABELS) {
                    FilterChip(selected = talk.gesture == gesture, onClick = { talk.onGesture(gesture) }, label = { Text(stringResource(label)) })
                }
            }
            if (talk.gesture != MediaButtonGesture.OFF) Text(stringResource(R.string.talk_gesture_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val GESTURE_LABELS = listOf(
    MediaButtonGesture.OFF to R.string.talk_gesture_off,
    MediaButtonGesture.LONG_PRESS to R.string.talk_gesture_long_press,
    MediaButtonGesture.DOUBLE_PRESS to R.string.talk_gesture_double_press,
)

private fun RecognitionError.message(): Int = when (this) {
    RecognitionError.NO_MATCH -> R.string.talk_failed_no_match
    RecognitionError.NO_SPEECH -> R.string.talk_failed_no_speech
    RecognitionError.NO_PERMISSION -> R.string.talk_failed_no_permission
    RecognitionError.NETWORK -> R.string.talk_failed_network
    RecognitionError.BUSY -> R.string.talk_failed_busy
    RecognitionError.UNAVAILABLE -> R.string.talk_failed_unavailable
    RecognitionError.OTHER -> R.string.talk_failed_other
}

@Composable
private fun ConnectionCard(
    status: ConnectionStatus,
    scan: ScanStatus,
    lastAdapter: LastAdapter?,
    actions: ConnectionActions,
    deepScan: Boolean,
    knownVehicle: Boolean,
) {
    // LocalResources, unlike LocalContext.current.resources, is invalidated by a configuration change, so the text follows a language change.
    val resources = LocalResources.current
    val idle = status == ConnectionStatus.Idle
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.titleMedium)
            // A live region, so a screen reader announces the change from "Connecting" to "Live" without being asked.
            Text(
                status.text(resources),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            // For a car the app has no definition for: whether it is also swept for data identifiers while parked. Read-only either way.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.deep_scan_label), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.deep_scan_hint), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = deepScan, onCheckedChange = actions.onDeepScan)
            }
            if (idle) {
                // A remembered adapter is one tap away; choosing another is the secondary action. Without one, the picker is the way in.
                if (lastAdapter != null) {
                    Button(onClick = { actions.onConnectLast(lastAdapter) }, modifier = Modifier.fillMaxWidth()) {
                        Text(lastAdapter.name?.let { stringResource(R.string.action_connect_last, it) } ?: stringResource(R.string.action_connect_last_unnamed))
                    }
                    OutlinedButton(onClick = actions.onChooseAdapter, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_choose_another))
                    }
                } else {
                    Button(onClick = actions.onChooseAdapter, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_connect_bluetooth))
                    }
                }
                OutlinedButton(onClick = actions.onStartDemo, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_start_demo))
                }
            } else {
                if (!actions.backgroundAllowed) {
                    Text(stringResource(R.string.background_explanation), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = actions.onAllowBackground, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_allow_background))
                    }
                }
                if (status is ConnectionStatus.CannotRead) {
                    Button(onClick = actions.onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_retry)) }
                }
                if (status == ConnectionStatus.Live) {
                    ScanSection(scan, resources, actions)
                    if (!knownVehicle) {
                        OutlinedButton(onClick = actions.onRescan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_scan_again)) }
                    }
                }
                // One button for both kinds of link.
                Button(onClick = actions.onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_disconnect))
                }
            }
        }
    }
}

/** Which level of the learning ladder runs on a car the app has no definition for, and at the end that the car may be driven. */
@Composable
private fun LadderBanner(progress: LadderProgress, onSkip: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (progress) {
                LadderProgress.Idle -> Unit
                is LadderProgress.Running -> {
                    Text(stringResource(R.string.ladder_running, progress.level, progress.secondsLeft), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.ladder_running_unit, progress.label, progress.done, progress.total),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_skip)) }
                }
                is LadderProgress.Complete -> Text(stringResource(R.string.ladder_complete), style = MaterialTheme.typography.titleMedium)
                is LadderProgress.Stopped -> {
                    Text(stringResource(R.string.ladder_stopped, progress.level), style = MaterialTheme.typography.titleMedium)
                    Text(progress.reason, style = MaterialTheme.typography.bodySmall)
                }
                is LadderProgress.Flagged -> {
                    Text(stringResource(R.string.ladder_flagged), style = MaterialTheme.typography.titleMedium)
                    Text(progress.reason, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** A plain identity line and the fault codes the car reported, each with its generic description or "no description available"; read-only. */
@Composable
private fun IdentityAndFaultsCard(identity: VehicleIdentity?, faults: FaultSummary, onRefresh: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (identity != null) {
                val name = listOfNotNull(identity.make, identity.model, identity.year?.toString()).joinToString(" ")
                Text(name, style = MaterialTheme.typography.titleMedium)
                val details = listOfNotNull(identity.vinLast4?.let { stringResource(R.string.identity_vin_end, it) }, identity.protocol).joinToString(" · ")
                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.faults_title), style = MaterialTheme.typography.titleSmall)
            when (faults.status) {
                FaultsStatus.NOT_READ -> Unit
                FaultsStatus.READING -> Text(stringResource(R.string.faults_reading), style = MaterialTheme.typography.bodyMedium)
                FaultsStatus.FAILED -> Text(faults.failure ?: stringResource(R.string.faults_failed), style = MaterialTheme.typography.bodyMedium)
                FaultsStatus.READY -> {
                    if (faults.items.isEmpty()) Text(stringResource(R.string.faults_none), style = MaterialTheme.typography.bodyMedium)
                    for (item in faults.items) {
                        Text(
                            stringResource(R.string.fault_line, item.code, item.description ?: stringResource(R.string.fault_no_description)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_read_faults)) }
        }
    }
}

/** The full scan on the connection card: the button while the car is live, then progress (or the pause notice), then what it found. */
@Composable
private fun ScanSection(scan: ScanStatus, resources: android.content.res.Resources, actions: ConnectionActions) {
    val text = scan.text(resources)
    // Running a scan again replaces the result on the screen, so it is asked about first.
    var confirmRerun by rememberSaveable { mutableStateOf(false) }
    if (confirmRerun) {
        AlertDialog(
            onDismissRequest = { confirmRerun = false },
            title = { Text(stringResource(R.string.scan_rerun_title)) },
            text = { Text(stringResource(R.string.scan_rerun_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRerun = false
                        actions.onStartScan()
                    },
                ) { Text(stringResource(R.string.action_run_again)) }
            },
            dismissButton = { TextButton(onClick = { confirmRerun = false }) { Text(stringResource(R.string.action_keep_result)) } },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (text != null) {
            // A live region: a screen reader says the progress, and above all the pause notice, without being asked.
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        when (scan) {
            is ScanStatus.Running -> {
                scan.fraction()?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                OutlinedButton(onClick = actions.onCancelScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel_scan)) }
            }
            ScanStatus.Starting ->
                OutlinedButton(onClick = actions.onCancelScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel_scan)) }
            ScanStatus.Unavailable -> Unit
            ScanStatus.Ready, is ScanStatus.Finished, is ScanStatus.Failed -> {
                if (scan is ScanStatus.Finished && scan.resumable) {
                    Button(onClick = actions.onResumeScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_resume_scan)) }
                }
                if (scan is ScanStatus.Finished) {
                    OutlinedButton(onClick = { confirmRerun = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_run_again)) }
                } else {
                    OutlinedButton(onClick = actions.onStartScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_full_scan)) }
                }
                if (scan == ScanStatus.Ready) Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodySmall)
                if (scan !is ScanStatus.Ready) {
                    OutlinedButton(onClick = actions.onDismissScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_dismiss_scan)) }
                }
            }
        }
    }
}

@Composable
private fun VoiceCard(voice: VoiceActions) {
    val percent = (voice.settings.volume * 100).roundToInt()
    val volumeLabel = stringResource(R.string.voice_volume)
    val volumeState = stringResource(R.string.voice_volume_percent, percent)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.voice_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    voice.activePersonaName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.voice_spoken_status),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = voice.settings.spokenStatus, onCheckedChange = voice.onSpokenStatusChange, enabled = voice.available)
            }
            Text(volumeLabel, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = voice.settings.volume,
                onValueChange = voice.onVolumeChange,
                valueRange = VoiceSettings.MIN_VOLUME..VoiceSettings.MAX_VOLUME,
                enabled = voice.available,
                modifier = Modifier.semantics {
                    contentDescription = volumeLabel
                    stateDescription = volumeState
                },
            )
            Button(onClick = voice.onTest, enabled = voice.available, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_test_voice))
            }
            OutlinedButton(onClick = voice.onSayAgain, enabled = voice.available && voice.lastSpoken != null, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_say_again))
            }
            voice.lastSpoken?.let { Text(stringResource(R.string.voice_last_spoken, it), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun DriveDataCard(driveData: DriveDataActions, tyres: TyreSensors?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.drive_data_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.drive_data_motion), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(if (driveData.locationAllowed) R.string.drive_data_location_on else R.string.drive_data_location_off),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!driveData.locationAllowed) {
                OutlinedButton(onClick = driveData.onAllowLocation, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_allow_location)) }
            }
            tyres?.let { TyreSection(it) }
        }
    }
}

/**
 * The tyre sensors heard lately, each payload read with every layout, so the values can be compared with the sensor's own app on the spot.
 * Marked unconfirmed while the layouts are only inferred: nothing here raises an alert.
 */
@Composable
private fun TyreSection(tyres: TyreSensors) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(if (tyres.unconfirmed) R.string.tyres_title_unconfirmed else R.string.tyres_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            when {
                !tyres.listening -> stringResource(R.string.tyres_not_listening)
                tyres.readings.isEmpty() -> stringResource(R.string.tyres_listening_nothing_yet)
                else -> stringResource(R.string.tyres_listening, tyres.layouts.joinToString(", "))
            },
            style = MaterialTheme.typography.bodySmall,
        )
        tyres.readings.forEach { reading ->
            Column(Modifier.padding(start = 12.dp, top = 4.dp)) {
                Text(
                    stringResource(R.string.tyres_payload, reading.where, reading.payloadHex),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    stringResource(R.string.tyres_heard, reading.rssi, reading.ageSeconds, reading.count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (reading.decoded.isEmpty()) {
                    Text(stringResource(R.string.tyres_no_layout_fits), style = MaterialTheme.typography.bodySmall)
                }
                reading.decoded.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun VehicleCard(vehicle: VehicleSummary, pollingRate: Double?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.vehicle_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.vehicle_ecus, vehicle.ecus.size), style = MaterialTheme.typography.bodyMedium)
            vehicle.ecus.forEach { ecu ->
                val name = ecu.name ?: stringResource(R.string.vehicle_ecu_unnamed, ecu.requestId)
                Text(
                    stringResource(R.string.vehicle_ecu_line, name, ecu.requestId),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            // Only ever the redacted form: manufacturer code and asterisks.
            Text(
                vehicle.vinRedacted?.let { stringResource(R.string.vehicle_vin, it) } ?: stringResource(R.string.vehicle_vin_unknown),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                vehicle.adapterBanner?.let { stringResource(R.string.vehicle_adapter, it) } ?: stringResource(R.string.vehicle_adapter_unknown),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                vehicle.requestsPerSecond?.let { stringResource(R.string.vehicle_rate, "%.1f".format(it)) } ?: stringResource(R.string.vehicle_rate_unknown),
                style = MaterialTheme.typography.bodyMedium,
            )
            pollingRate?.let { Text(stringResource(R.string.vehicle_polling_rate, "%.1f".format(it)), style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.vehicle_fuel, stringResource(vehicle.fuelMethod.textRes())), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(if (vehicle.hasDpfData) R.string.vehicle_dpf_yes else R.string.vehicle_dpf_no), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.coverage_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            CoverageRow("confirmed", stringResource(R.string.coverage_confirmed, vehicle.confirmed.size), vehicle.confirmed)
            CoverageRow("missing", stringResource(R.string.coverage_missing, vehicle.missing.size), vehicle.missing)
            CoverageRow("unmapped", stringResource(R.string.coverage_unmapped, vehicle.unmapped.size), vehicle.unmapped)
        }
    }
}

/** A count that expands to its list. [id] keeps the expanded state of each row apart, and across rotation. */
@Composable
private fun CoverageRow(id: String, label: String, lines: List<String>) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = stringResource(if (expanded) R.string.coverage_collapse else R.string.coverage_expand),
                    role = Role.Button,
                    onClick = { expanded = !expanded },
                )
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            // Decoration: the click label above already says what the row does.
            Text(if (expanded) "▴" else "▾", modifier = Modifier.clearAndSetSemantics { })
        }
        if (expanded) {
            if (lines.isEmpty()) {
                Text(stringResource(R.string.coverage_empty), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp))
            }
            lines.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun RecordingsCard(recordings: RecordingsSummary, onShareLatest: () -> Unit, onShareLog: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.recordings_title), style = MaterialTheme.typography.titleMedium)
            Text(
                if (recordings.count == 0) {
                    stringResource(R.string.recordings_none)
                } else {
                    pluralStringResource(
                        R.plurals.recordings_summary,
                        recordings.count,
                        recordings.count,
                        Formatter.formatShortFileSize(context, recordings.totalBytes),
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onShareLatest, enabled = recordings.count > 0, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_share_latest))
            }
            // Always available: it is what explains an attempt that never got as far as a recording.
            OutlinedButton(onClick = onShareLog, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_share_connection_log))
            }
        }
    }
}

private fun LazyListScope.liveItems(groups: List<ValueGroup>, paused: Boolean) {
    item(key = "live-title", contentType = "title") {
        Text(stringResource(R.string.live_title), style = MaterialTheme.typography.titleMedium)
    }
    if (paused) {
        item(key = "live-paused", contentType = "title") {
            Text(
                stringResource(R.string.capture_live_paused),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
    if (groups.isEmpty()) {
        item(key = "live-empty", contentType = "title") {
            Text(
                stringResource(R.string.live_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    for (group in groups) {
        item(key = "group:${group.prefix}", contentType = "group") {
            Text(
                group.prefix,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        // LiveValue is immutable and compared by content, so a row whose value did not change is skipped on refresh.
        items(group.values, key = { it.key }, contentType = { "value" }) { value -> LiveRow(value) }
    }
}

@Composable
private fun LiveRow(value: LiveValue) {
    val valueLine = stringResource(R.string.live_row_value, value.valueText, value.unit).trim()
    val secondary = when {
        value.stale && value.ecu != null -> stringResource(R.string.live_ecu_stale, value.ecu, value.ageSeconds)
        value.stale -> stringResource(R.string.live_stale_only, value.ageSeconds)
        else -> value.ecu
    }
    // One announcement per row; a stale value says so instead of reading as current.
    val description = if (value.stale) {
        stringResource(R.string.live_stale_description, value.signalId, valueLine, value.ageSeconds)
    } else {
        "${value.signalId}: $valueLine"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (value.stale) STALE_ALPHA else 1f)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(value.signalId, style = MaterialTheme.typography.bodyMedium)
            if (secondary != null) {
                Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(valueLine, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
    }
}

private const val STALE_ALPHA = 0.5f
