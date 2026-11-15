package knightlink.app

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import knightlink.app.session.RecordingShare
import knightlink.app.session.RecordingsSummary
import knightlink.app.session.SessionService
import knightlink.app.session.UiState
import knightlink.app.ui.HomeScreen
import knightlink.app.ui.SessionViewModel
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

    // The result is ignored on purpose: the app works the same without notifications, so a denial must not stop the demo.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startDemo() }

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

        HomeScreen(
            state = state,
            recordings = recordings,
            onStartDemo = ::onStartDemoClicked,
            onDisconnect = { startService(SessionService.disconnectIntent(this)) },
            onShareLatest = ::shareLatest,
        )

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

    private fun onStartDemoClicked() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission && !viewModel.notificationPermissionAsked) {
            viewModel.notificationPermissionAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startDemo()
        }
    }

    private fun startDemo() {
        startService(SessionService.startDemoIntent(this))
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

    /** Hands [scrubbed] to the system share sheet. Only files in the scrubbed copies' directory are exposed by the provider. */
    private fun send(scrubbed: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", scrubbed)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser_title)))
    }

    private companion object {
        const val SHARE_DIRECTORY = "shared"
        const val RECORDINGS_REFRESH_MS = 5000L
        val IDLE_STATE = MutableStateFlow(UiState.IDLE)
    }
}
