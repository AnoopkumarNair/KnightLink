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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import knightlink.app.R
import knightlink.app.ble.LastAdapter
import knightlink.app.fraction
import knightlink.app.session.ConnectionStatus
import knightlink.app.session.LiveValue
import knightlink.app.session.RecordingsSummary
import knightlink.app.session.ScanStatus
import knightlink.app.session.UiState
import knightlink.app.session.ValueGroup
import knightlink.app.session.VehicleSummary
import knightlink.app.session.groupByPrefix
import knightlink.app.text
import knightlink.app.textRes

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
    val onCancelScan: () -> Unit = {},
    val onDismissScan: () -> Unit = {},
)

/** What the drive-data card shows and can do: whether the phone's location is being recorded, and how to allow it. */
class DriveDataActions(val locationAllowed: Boolean, val onAllowLocation: () -> Unit)

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
) {
    // Grouping is a function of the values only; it is redone when they change, not on every recomposition.
    val groups = remember(state.liveValues) { groupByPrefix(state.liveValues) }
    val padding = PaddingValues(SCREEN_PADDING)
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        if (maxWidth >= WIDE_LAYOUT_MIN_WIDTH) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
                    summaryItems(state, recordings, lastAdapter, actions, driveData, onShareLatest, onShareLog)
                }
                LazyColumn(Modifier.weight(1f), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
                    liveItems(groups)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
                summaryItems(state, recordings, lastAdapter, actions, driveData, onShareLatest, onShareLog)
                liveItems(groups)
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
    onShareLatest: () -> Unit,
    onShareLog: () -> Unit,
) {
    item(key = "connection", contentType = "card") { ConnectionCard(state.connection, state.scan, lastAdapter, actions) }
    state.vehicle?.let { vehicle -> item(key = "vehicle", contentType = "card") { VehicleCard(vehicle, state.pollingRate) } }
    item(key = "drive-data", contentType = "card") { DriveDataCard(driveData) }
    item(key = "recordings", contentType = "card") { RecordingsCard(recordings, onShareLatest, onShareLog) }
}

@Composable
private fun ConnectionCard(status: ConnectionStatus, scan: ScanStatus, lastAdapter: LastAdapter?, actions: ConnectionActions) {
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
                if (status == ConnectionStatus.Live) ScanSection(scan, resources, actions)
                // One button for both kinds of link.
                Button(onClick = actions.onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_disconnect))
                }
            }
        }
    }
}

/** The full scan on the connection card: the button while the car is live, then progress (or the pause notice), then what it found. */
@Composable
private fun ScanSection(scan: ScanStatus, resources: android.content.res.Resources, actions: ConnectionActions) {
    val text = scan.text(resources)
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
                OutlinedButton(onClick = actions.onStartScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_full_scan)) }
                if (scan == ScanStatus.Ready) Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodySmall)
                if (scan !is ScanStatus.Ready) {
                    OutlinedButton(onClick = actions.onDismissScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_dismiss_scan)) }
                }
            }
        }
    }
}

@Composable
private fun DriveDataCard(driveData: DriveDataActions) {
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

private fun LazyListScope.liveItems(groups: List<ValueGroup>) {
    item(key = "live-title", contentType = "title") {
        Text(stringResource(R.string.live_title), style = MaterialTheme.typography.titleMedium)
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
