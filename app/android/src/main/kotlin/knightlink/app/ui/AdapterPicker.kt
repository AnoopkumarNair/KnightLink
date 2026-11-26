package knightlink.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import knightlink.app.R
import knightlink.app.ble.PickerState
import knightlink.app.ble.ScanResults
import knightlink.app.ble.pickerStates
import knightlink.platform.ble.DiscoveredAdapter
import knightlink.platform.spp.BondedSppDevice
import kotlinx.coroutines.flow.Flow

private val LIST_MAX_HEIGHT = 320.dp

/**
 * Lists the adapters a scan finds, live, strongest signal first. The scan runs for as long as this dialog is shown (it is
 * collected by the composition, so closing the dialog stops it) and for at most the scan time of [pickerStates]; then
 * "Scan again" starts a new one.
 *
 * @param scan starts a scan when collected
 * @param bluetoothIsOff whether a failed scan is explained by Bluetooth being off; offers [onEnableBluetooth] then
 */
@Composable
fun AdapterPickerDialog(
    scan: () -> Flow<DiscoveredAdapter>,
    bluetoothIsOff: () -> Boolean,
    onEnableBluetooth: () -> Unit,
    onPick: (DiscoveredAdapter) -> Unit,
    bondedSpp: () -> List<BondedSppDevice> = { emptyList() },
    onPickSpp: (BondedSppDevice) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var run by remember { mutableIntStateOf(0) }
    val state by produceState<PickerState>(PickerState.Scanning(ScanResults()), run) {
        pickerStates(scan()).collect { value = it }
    }
    val sppDevices = remember(run) { bondedSpp() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PickerStatus(state)
                if (state.results.adapters.isNotEmpty() || sppDevices.isNotEmpty()) {
                    Text(stringResource(R.string.picker_tap_hint), style = MaterialTheme.typography.bodyMedium)
                }
                if (state is PickerState.Failed && bluetoothIsOff()) {
                    TextButton(onClick = onEnableBluetooth) { Text(stringResource(R.string.picker_enable_bluetooth)) }
                }
                LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
                    items(state.results.adapters, key = { "ble:${it.address}" }) { adapter -> AdapterRow(adapter, onClick = { onPick(adapter) }) }
                    if (sppDevices.isNotEmpty()) {
                        item(key = "spp-header") {
                            Text(
                                stringResource(R.string.picker_spp_section),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(sppDevices, key = { "spp:${it.address}" }) { device -> SppAdapterRow(device, onClick = { onPickSpp(device) }) }
                    }
                }
            }
        },
        confirmButton = {
            if (state !is PickerState.Scanning) {
                TextButton(onClick = { run++ }) { Text(stringResource(R.string.picker_scan_again)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PickerStatus(state: PickerState) {
    Row(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            is PickerState.Scanning -> {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.picker_scanning), style = MaterialTheme.typography.bodyMedium)
            }
            is PickerState.Finished ->
                if (state.results.adapters.isEmpty()) Text(stringResource(R.string.picker_none_found), style = MaterialTheme.typography.bodyMedium)
            is PickerState.Failed -> Text(stringResource(R.string.picker_failed, state.message), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AdapterRow(adapter: DiscoveredAdapter, onClick: () -> Unit) {
    // The whole row is the tap target, and a visible Connect button says so: a bare name did not read as tappable on the phone.
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(adapter.name ?: stringResource(R.string.picker_unnamed), style = MaterialTheme.typography.titleSmall)
            Text(
                adapter.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.picker_signal, adapter.rssi),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onClick) { Text(stringResource(R.string.picker_connect)) }
    }
}

@Composable
private fun SppAdapterRow(device: BondedSppDevice, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.titleSmall)
            Text(
                device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.picker_spp_badge),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onClick) { Text(stringResource(R.string.picker_connect)) }
    }
}
