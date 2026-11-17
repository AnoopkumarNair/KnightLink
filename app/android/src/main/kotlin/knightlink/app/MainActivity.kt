package knightlink.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import knightlink.app.ble.LastAdapter
import knightlink.app.ble.PendingStart
import knightlink.app.ble.PermissionDenial
import knightlink.app.ble.PermissionPolicy
import knightlink.app.session.RecordingShare
import knightlink.app.session.RecordingsSummary
import knightlink.app.session.SessionService
import knightlink.app.session.UiState
import knightlink.app.ui.AdapterPickerDialog
import knightlink.app.ui.ConnectionActions
import knightlink.app.ui.HomeScreen
import knightlink.app.ui.SessionViewModel
import knightlink.platform.ble.BlePermissions
import knightlink.platform.ble.DiscoveredAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one activity. It only shows the session and sends it commands; the session lives in [SessionService], so recreating
 * or destroying this activity never touches it.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: SessionViewModel by viewModels()

    // One request for everything a start needs; the individual answers are not read, the permissions are checked again afterwards.
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionResult() }

    // Nothing to read from the result: if Bluetooth is on afterwards, "Scan again" works.
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Home()
                }
            }
        }
    }

    @Composable
    private fun Home() {
        val controller by viewModel.controller.collectAsStateWithLifecycle()
        // A new flow only when the service's controller changes; until the first bind completes the screen reads as idle.
        val uiFlow = remember(controller) { controller?.uiState ?: IDLE_STATE }
        val state by uiFlow.collectAsStateWithLifecycle()
        val recordings = rememberRecordingsSummary(state.isActive)
        val pendingShare by viewModel.pendingShare.collectAsStateWithLifecycle()
        val shareError by viewModel.shareError.collectAsStateWithLifecycle()

        val lastAdapter by viewModel.lastAdapter.collectAsStateWithLifecycle()
        val pickerOpen by viewModel.pickerOpen.collectAsStateWithLifecycle()
        val denial by viewModel.permissionDenial.collectAsStateWithLifecycle()

        HomeScreen(
            state = state,
            recordings = recordings,
            lastAdapter = lastAdapter,
            actions = ConnectionActions(
                onStartDemo = { begin(PendingStart.Demo) },
                onConnectLast = { begin(PendingStart.ConnectTo(it)) },
                onChooseAdapter = { begin(PendingStart.ChooseAdapter) },
                onDisconnect = { startService(SessionService.disconnectIntent(this)) },
            ),
            onShareLatest = ::shareLatest,
            onShareLog = ::shareConnectionLog,
        )

        if (pickerOpen) {
            AdapterPickerDialog(
                scan = viewModel::scan,
                bluetoothIsOff = ::bluetoothIsOff,
                onEnableBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                onPick = ::onAdapterPicked,
                onDismiss = { viewModel.setPickerOpen(false) },
            )
        }
        denial?.let { PermissionDenialDialog(it) }

        pendingShare?.let { prepared ->
            ShareWarningDialog(
                prepared,
                onShare = {
                    viewModel.setPendingShare(null)
                    send(prepared.file)
                },
                onCancel = { viewModel.setPendingShare(null) },
            )
        }
        shareError?.let { message ->
            AlertDialog(
                onDismissRequest = { viewModel.setShareError(null) },
                confirmButton = { TextButton(onClick = { viewModel.setShareError(null) }) { Text(stringResource(android.R.string.ok)) } },
                text = { Text(stringResource(R.string.share_failed, message)) },
            )
        }
    }

    /** The recordings count and size: read when the screen appears and when a connection starts or ends, and every few seconds while one is active. */
    @Composable
    private fun rememberRecordingsSummary(active: Boolean): RecordingsSummary {
        var summary by remember { mutableStateOf(RecordingsSummary.EMPTY) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(active) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                do {
                    summary = withContext(Dispatchers.IO) { viewModel.recordings.summary() }
                    if (active) delay(RECORDINGS_REFRESH_MS)
                } while (active)
            }
        }
        return summary
    }

    @Composable
    private fun ShareWarningDialog(prepared: RecordingShare.Prepared, onShare: () -> Unit, onCancel: () -> Unit) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.share_warning_title)) },
            text = { Text(stringResource(R.string.share_warning_intro) + "\n\n" + prepared.warnings.joinToString("\n")) },
            confirmButton = { TextButton(onClick = onShare) { Text(stringResource(R.string.share_anyway)) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        )
    }

    /**
     * Every way to start a session goes through here: the permissions it needs are requested in one go (the Bluetooth ones for
     * a real adapter, and the notification one once per process), then [proceed] does what was asked.
     */
    private fun begin(start: PendingStart) {
        viewModel.pendingStart = start
        val request = buildList {
            if (start !is PendingStart.Demo) addAll(BlePermissions.missing(this@MainActivity))
            val askNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !viewModel.notificationPermissionAsked
            if (askNotifications) {
                viewModel.notificationPermissionAsked = true
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (request.isEmpty()) proceed() else permissionRequest.launch(request.toTypedArray())
    }

    private fun onPermissionResult() {
        val start = viewModel.pendingStart ?: return
        // The notification permission is optional, and the demo needs nothing else: whatever the answer, the demo starts.
        val denial = if (start is PendingStart.Demo) {
            null
        } else {
            PermissionPolicy.denial(BlePermissions.missing(this)) { shouldShowRequestPermissionRationale(it) }
        }
        if (denial == null) proceed() else viewModel.setPermissionDenial(denial)
    }

    private fun proceed() {
        val start = viewModel.pendingStart ?: return
        viewModel.pendingStart = null
        when (start) {
            PendingStart.Demo -> startService(SessionService.startDemoIntent(this))
            PendingStart.ChooseAdapter -> viewModel.setPickerOpen(true)
            is PendingStart.ConnectTo -> startBluetoothSession(start.adapter.address)
        }
    }

    // The service must become a foreground service at once for a real link: startForegroundService is how it is allowed to.
    private fun startBluetoothSession(address: String) {
        viewModel.connectionLog.app("asking the system to start the Bluetooth session service")
        ContextCompat.startForegroundService(this, SessionService.startBleIntent(this, address))
    }

    private fun onAdapterPicked(adapter: DiscoveredAdapter) {
        // Diagnostics for the first real sessions: never the address, only that a pick happened.
        Log.i(TAG, "Adapter picked (named: ${adapter.name != null}); starting a Bluetooth session")
        viewModel.connectionLog.app("adapter picked in the list (named: ${adapter.name != null}, name: ${adapter.name ?: "-"}); starting a Bluetooth session")
        viewModel.setPickerOpen(false)
        viewModel.remember(LastAdapter(adapter.address, adapter.name))
        startBluetoothSession(adapter.address)
    }

    private fun bluetoothIsOff(): Boolean = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == false

    @Composable
    private fun PermissionDenialDialog(denial: PermissionDenial) {
        val blocked = denial == PermissionDenial.NEEDS_SETTINGS
        val dismiss = {
            viewModel.setPermissionDenial(null)
            viewModel.pendingStart = null
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.permission_title)) },
            text = {
                Text(stringResource(R.string.permission_explanation) + if (blocked) "\n\n" + stringResource(R.string.permission_blocked) else "")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setPermissionDenial(null)
                        if (blocked) {
                            viewModel.pendingStart = null
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                            )
                        } else {
                            viewModel.pendingStart?.let(::begin)
                        }
                    },
                ) { Text(stringResource(if (blocked) R.string.permission_open_settings else R.string.permission_try_again)) }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }

    /** Scrubs the newest recording (off the main thread), then shares the scrubbed copy, after the user has seen any warnings. */
    private fun shareLatest() {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    viewModel.recordings.newest()?.let { RecordingShare.prepare(it, File(cacheDir, SHARE_DIRECTORY)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // IOException, RecordingFormatException (not a readable recording), or anything the scrubber throws on an
                    // odd file: a failed share is reported, never a crash.
                    e
                }
            }
            when (outcome) {
                null -> Unit
                is RecordingShare.Prepared -> if (outcome.warnings.isEmpty()) send(outcome.file) else viewModel.setPendingShare(outcome)
                is Exception -> viewModel.setShareError(outcome.message ?: outcome::class.simpleName)
            }
        }
    }

    /** Writes the connection log into the shared directory (off the main thread) and offers it to the share sheet. */
    private fun shareConnectionLog() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { viewModel.connectionLog.prepareShare(File(cacheDir, SHARE_DIRECTORY)) }
            if (file == null) viewModel.setShareError(getString(R.string.share_log_failed)) else send(file, R.string.share_log_chooser_title)
        }
    }

    /** Hands [scrubbed] to the system share sheet. Only files in the scrubbed copies' directory are exposed by the provider. */
    private fun send(scrubbed: File, chooserTitle: Int = R.string.share_chooser_title) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", scrubbed)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        startActivity(Intent.createChooser(send, getString(chooserTitle)))
    }

    private companion object {
        const val TAG = "KnightLink"
        const val SHARE_DIRECTORY = "shared"
        const val RECORDINGS_REFRESH_MS = 5000L
        val IDLE_STATE = MutableStateFlow(UiState.IDLE)
    }
}
