package knightlink.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
import androidx.core.net.toUri
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
import knightlink.app.ui.DriveDataActions
import knightlink.app.ui.HomeScreen
import knightlink.app.ui.SessionViewModel
import knightlink.app.ui.TalkActions
import knightlink.app.ui.VoiceActions
import knightlink.app.ui.voice.ThemePack
import knightlink.app.ui.voice.VoiceOverlay
import knightlink.app.ui.voice.rememberReduceMotion
import knightlink.app.voice.ButtonGesture
import knightlink.app.voice.MediaButtonTrigger
import knightlink.app.voice.VoiceSettings
import knightlink.platform.ble.BlePermissions
import knightlink.platform.ble.DiscoveredAdapter
import knightlink.voice.api.Priority
import knightlink.voice.api.SpeakResult
import knightlink.voice.api.Speaker
import knightlink.voice.api.SpeechState
import knightlink.voice.api.Utterance
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

    // Optional: the phone's location, recorded next to a drive. Whatever the answer, the session and the motion sensors do not depend on it.
    private val locationRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        // Android 12 and later let the user grant the approximate location only, which a GPS fix cannot be read with: not allowed then.
        val granted = answers[Manifest.permission.ACCESS_FINE_LOCATION] == true
        locationAllowed.value = granted
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Blocked for good (or asked twice): the settings are the only way left.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private val locationAllowed = MutableStateFlow(false)

    /** Whether Android's battery optimisation leaves this app alone (the phone's sensors stalled for tens of seconds before link drops without it). */
    private val backgroundExempt = MutableStateFlow(true)

    /** Asks Android to exempt the app from battery optimisation; the system dialog explains and the user decides. */
    @SuppressLint("BatteryLife")
    private fun allowBackground() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // The microphone, asked for after the explanation dialog. Once granted, the question the user pressed for starts at once.
    private val micRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.pushToTalk.toggle()
        } else {
            viewModel.setMicDenial(PermissionPolicy.denial(listOf(Manifest.permission.RECORD_AUDIO)) { shouldShowRequestPermissionRationale(it) })
        }
    }

    // The steering-wheel button. The activity is in front whenever it fires, so the explanation dialog can be shown.
    private val mediaButton by lazy { MediaButtonTrigger(this, ButtonGesture(viewModel.mediaGesture.value), ::onTalk) }

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

    override fun onStart() {
        super.onStart()
        mediaButton.setGesture(viewModel.mediaGesture.value)
        mediaButton.start()
    }

    override fun onStop() {
        mediaButton.stop()
        // The microphone is not for the background: a question in progress is dropped.
        viewModel.pushToTalk.cancel()
        super.onStop()
    }

    /** The talk button, or the steering-wheel gesture. Without the microphone permission the explanation comes first. */
    private fun onTalk() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.pushToTalk.toggle()
        } else {
            viewModel.setMicExplanationOpen(true)
        }
    }

    override fun onResume() {
        super.onResume()
        locationAllowed.value = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        backgroundExempt.value = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true
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
        val location by locationAllowed.collectAsStateWithLifecycle()
        val backgroundAllowed by backgroundExempt.collectAsStateWithLifecycle()

        val speaker by viewModel.speaker.collectAsStateWithLifecycle()
        val voiceStore by viewModel.voiceSettings.collectAsStateWithLifecycle()
        val voiceSettings by remember(voiceStore) { voiceStore?.settings ?: DEFAULT_VOICE_SETTINGS }.collectAsStateWithLifecycle()
        val lastSpoken by remember(speaker) { speaker?.lastSpoken ?: NO_LINE }.collectAsStateWithLifecycle()
        val speechState by remember(speaker) { speaker?.state ?: IDLE_SPEECH_STATE }.collectAsStateWithLifecycle()
        val activePack by viewModel.activePack.collectAsStateWithLifecycle()
        val player by viewModel.knightVoicePlayer.collectAsStateWithLifecycle()
        val playerLoudness by remember(player) { player?.visualizerLoudness ?: ZERO_LEVEL }.collectAsStateWithLifecycle()

        val currentSpeakingLevel = (speechState as? SpeechState.Speaking)?.level?.takeIf { it >= 0f } ?: 0f
        val visualizerLevel = maxOf(currentSpeakingLevel, playerLoudness)
        val talkState by viewModel.pushToTalk.state.collectAsStateWithLifecycle()
        val gesture by viewModel.mediaGesture.collectAsStateWithLifecycle()
        val micExplanation by viewModel.micExplanationOpen.collectAsStateWithLifecycle()
        val micDenial by viewModel.micDenial.collectAsStateWithLifecycle()

        HomeScreen(
            state = state,
            recordings = recordings,
            lastAdapter = lastAdapter,
            actions = ConnectionActions(
                onStartDemo = { begin(PendingStart.Demo) },
                onConnectLast = { begin(PendingStart.ConnectTo(it)) },
                onChooseAdapter = { begin(PendingStart.ChooseAdapter) },
                onDisconnect = { startService(SessionService.disconnectIntent(this)) },
                onStartScan = { controller?.startFullScan() },
                onDriveMode = { controller?.markDriveMode(it) },
                onDashboard = { odo, dte, end -> controller?.markDashboard(odo, dte, end) },
                onPassiveCapture = { controller?.setCaptureMode(it) },
                onResumeScan = { controller?.resumeFullScan() },
                onRetry = { controller?.retry() },
                onRefreshFaults = { controller?.refreshFaults() },
                onDeepScan = { controller?.setDeepScan(it) },
                onSkipLadder = { controller?.skipLadder() },
                onRescan = { controller?.rerunLadder() },
                onCancelScan = { controller?.cancelFullScan() },
                onDismissScan = { controller?.dismissFullScan() },
                backgroundAllowed = backgroundAllowed,
                onAllowBackground = ::allowBackground,
            ),
            onShareLatest = ::shareLatest,
            onShareLog = ::shareConnectionLog,
            driveData = DriveDataActions(
                locationAllowed = location,
                onAllowLocation = {
                    locationRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                },
            ),
            voice = VoiceActions(
                settings = voiceSettings,
                lastSpoken = lastSpoken?.text,
                available = speaker != null && voiceStore != null,
                onSpokenStatusChange = { voiceStore?.setSpokenStatus(it) },
                onVolumeChange = { voiceStore?.setVolume(it) },
                onTest = { speaker?.let(::testVoice) },
                onSayAgain = { speaker?.repeatLast() },
                activePersonaName = activePack?.manifest?.name ?: "K.I.T.T.",
                activeArchetype = activePack?.visualizer?.archetype ?: "bar-meter",
                visualizerLevel = visualizerLevel,
            ),
            talk = TalkActions(
                state = talkState,
                onDevice = viewModel.recognisedOnDevice,
                gesture = gesture,
                onTalk = ::onTalk,
                onGesture = {
                    viewModel.setMediaGesture(it)
                    mediaButton.setGesture(it)
                },
            ),
        )

        var overlayDismissedForUtterance by remember { mutableStateOf<Utterance?>(null) }
        val currentUtterance = (speechState as? SpeechState.Speaking)?.utterance
        val showOverlay = speechState !is SpeechState.Idle && (currentUtterance == null || currentUtterance != overlayDismissedForUtterance)

        if (showOverlay) {
            VoiceOverlay(
                state = speechState,
                lastSpoken = lastSpoken?.text,
                pack = ThemePack.DEFAULT,
                reduceMotion = rememberReduceMotion(),
                onSayAgain = { speaker?.repeatLast() },
                onStop = { speaker?.skip() },
                onDismiss = { overlayDismissedForUtterance = currentUtterance },
                archetype = activePack?.visualizer?.archetype ?: "bar-meter",
            )
        }

        if (micExplanation) MicExplanationDialog()
        micDenial?.let { MicDenialDialog(it) }

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

    /**
     * Speaks the sample line. Pressing the button twice within the quiet period would make the speaker drop the identical line, but a
     * test must always be heard, so then the last line (this one) is said again.
     */
    private fun testVoice(speaker: Speaker) {
        val result = speaker.speak(Utterance(getString(R.string.voice_test), Priority.ANSWER, category = "test"))
        if (result == SpeakResult.UNCHANGED) speaker.repeatLast()
    }

    /** Why the microphone, shown before Android's own prompt. */
    @Composable
    private fun MicExplanationDialog() {
        AlertDialog(
            onDismissRequest = { viewModel.setMicExplanationOpen(false) },
            title = { Text(stringResource(R.string.mic_title)) },
            text = { Text(stringResource(R.string.mic_explanation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setMicExplanationOpen(false)
                        micRequest.launch(Manifest.permission.RECORD_AUDIO)
                    },
                ) { Text(stringResource(R.string.mic_allow)) }
            },
            dismissButton = { TextButton(onClick = { viewModel.setMicExplanationOpen(false) }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    /** The microphone was refused: explain and offer another try, or the settings when Android will not ask again. */
    @Composable
    private fun MicDenialDialog(denial: PermissionDenial) {
        val blocked = denial == PermissionDenial.NEEDS_SETTINGS
        AlertDialog(
            onDismissRequest = { viewModel.setMicDenial(null) },
            title = { Text(stringResource(R.string.mic_title)) },
            text = { Text(stringResource(R.string.mic_denied) + if (blocked) "\n\n" + stringResource(R.string.mic_blocked) else "") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setMicDenial(null)
                        if (blocked) {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                        } else {
                            micRequest.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                ) { Text(stringResource(if (blocked) R.string.permission_open_settings else R.string.permission_try_again)) }
            },
            dismissButton = { TextButton(onClick = { viewModel.setMicDenial(null) }) { Text(stringResource(R.string.cancel)) } },
        )
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
        val DEFAULT_VOICE_SETTINGS = MutableStateFlow(VoiceSettings())
        val NO_LINE = MutableStateFlow<Utterance?>(null)
        val IDLE_SPEECH_STATE = MutableStateFlow<SpeechState>(SpeechState.Idle)
        val ZERO_LEVEL = MutableStateFlow(0f)
    }
}
