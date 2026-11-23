package knightlink.app.session

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import knightlink.app.MainActivity
import knightlink.app.R
import knightlink.app.SharedPreferencesStringStore
import knightlink.app.data.AndroidAssetSource
import knightlink.app.data.AppConfig
import knightlink.app.data.DashboardStore
import knightlink.app.data.DefinitionStore
import knightlink.app.data.DefinitionStoreException
import knightlink.app.text
import knightlink.app.voice.AndroidSpeechEngine
import knightlink.app.voice.ConnectionAnnouncer
import knightlink.app.voice.KnightPackManager
import knightlink.app.voice.KnightVoicePlayer
import knightlink.app.voice.VoiceSettingsStore
import knightlink.app.voice.statusUtterance
import knightlink.app.voice.text
import knightlink.core.runtime.VehicleSession
import knightlink.platform.ble.BleTransport
import knightlink.voice.api.QueuedSpeaker
import knightlink.voice.api.Speaker
import knightlink.voice.api.SpeechState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Hosts the [SessionController], so a session survives the activity: rotation, the activity being destroyed and the app being
 * swiped away from the recents list do not touch it. The activity binds to read [SessionController.uiState] (see
 * [LocalBinder]) and starts and stops the session with intents ([startDemoIntent], [disconnectIntent]), the same ones the
 * notification uses, so there is one path for commands whichever way they arrive.
 *
 * The service owns the scope the session runs in (a supervisor over [Dispatchers.Default]) and stops itself once the
 * session has been disconnected.
 *
 * Foreground or not: see [needsForegroundService]. The demo is not a link to a device, so it runs as an ordinary started
 * service with an ordinary ongoing notification; a link that needs the process kept alive becomes a foreground service of
 * type `connectedDevice`, guarded so that a refusal by the system degrades to the ordinary mode instead of crashing.
 */
class SessionService : Service() {
    /** The activity's handle on the service. In-process only, so it hands out the controller itself. */
    inner class LocalBinder : Binder() {
        val controller: SessionController get() = this@SessionService.controller
        val speaker: Speaker get() = this@SessionService.speaker
        val voiceSettings: VoiceSettingsStore get() = this@SessionService.voiceSettings
        val knightPackManager: KnightPackManager get() = this@SessionService.knightPackManager
        val knightVoicePlayer: KnightVoicePlayer get() = this@SessionService.knightVoicePlayer
    }

    // The handler matters: an unexpected exception of the session is reported through the scope (the session also shows it as
    // Failed), and without a handler it would reach the thread's uncaught handler and kill the app.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unexpected error in the session scope", e) },
    )
    private val binder = LocalBinder()
    private lateinit var controller: SessionController
    private lateinit var notifications: SessionNotifications
    private lateinit var assets: AndroidAssetSource
    private lateinit var connectionLog: ConnectionLog
    lateinit var knightPackManager: KnightPackManager
        private set
    lateinit var knightVoicePlayer: KnightVoicePlayer
        private set
    private lateinit var voiceSettings: VoiceSettingsStore
    private lateinit var speechEngine: AndroidSpeechEngine
    private lateinit var queuedSpeaker: QueuedSpeaker
    private val speaker: Speaker get() = queuedSpeaker

    @Volatile
    private var closed = false

    @Volatile
    private var sawActive = false

    // Whether startForeground has succeeded and not been undone; written on the main thread and when shutting down.
    @Volatile
    private var foreground = false

    // Held while a Bluetooth session runs in the foreground; see acquireWakeLock.
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    // The heartbeat that notices when the whole app stood still; see startStallWatch. Touched from the main thread and from the scope.
    @Volatile
    private var stallJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        assets = AndroidAssetSource(applicationContext.assets)
        connectionLog = ConnectionLog.of(connectionLogDirectory(applicationContext))
        val store = DefinitionStore(assets)
        val memoryStore = FileVehicleMemoryStore(applicationContext)
        controller = SessionController(
            scope = scope,
            config = { AppConfig.load(assets) },
            store = store,
            transports = AppTransportFactory(
                demo = DemoTransportFactory(scenario = store::simulation),
                adapter = { store.adapter(AppConfig.load(assets).adapterDefinition) },
                ble = { address, profile -> BleTransport(applicationContext, address, profile) },
            ),
            recordings = FileRecordSinkFactory(RecordingStore(recordingsDirectory(applicationContext))),
            connectionLog = connectionLog,
            sessionConfig = VehicleSession.Config(memory = memoryStore),
            protocolMemory = FileProtocolMemoryStore(java.io.File(applicationContext.filesDir, "protocol-memory.json")),
            samples = AndroidSampleSource(applicationContext),
            tyreSensors = AndroidAdvertisementSource(applicationContext),
            dashboardStore = DashboardStore(
                SharedPreferencesStringStore(applicationContext.getSharedPreferences(SharedPreferencesStringStore.FILE, Context.MODE_PRIVATE)),
            ),
            settings = SharedPreferencesStringStore(applicationContext.getSharedPreferences(SharedPreferencesStringStore.FILE, Context.MODE_PRIVATE)),
        )
        notifications = SessionNotifications(this)
        knightPackManager = KnightPackManager(applicationContext)
        knightVoicePlayer = KnightVoicePlayer(applicationContext, scope)
        startVoice()
        watchSession()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DEMO -> {
                connectionLog.app("service: demo start command received")
                startLink(ConnectionTarget.Demo(demoScenarioId()))
            }
            ACTION_START_BLE -> {
                Log.i(TAG, "Bluetooth start command received")
                connectionLog.app("service: Bluetooth start command received")
                val address = intent.getStringExtra(EXTRA_ADDRESS)?.takeIf { it.isNotBlank() }
                if (address != null) {
                    startLink(ConnectionTarget.Ble(address))
                } else {
                    connectionLog.app("service: the start command had no adapter address")
                    rejectForegroundStart()
                }
            }
            ACTION_DISCONNECT -> scope.launch {
                connectionLog.app("service: disconnect requested")
                controller.disconnect()
                // Also when there was nothing to disconnect (a notification outlived the process): it must not linger.
                shutDown()
            }
            else -> stopSelf(startId)
        }
        // Not sticky: a session killed with the process is not worth resurrecting without the user.
        return START_NOT_STICKY
    }

    private fun startLink(target: ConnectionTarget) {
        // Connect first: it sets "Connecting" synchronously, so the foreground notification starts with the right text.
        val wasForeground = foreground
        val accepted = controller.connect(target)
        if (target.needsForegroundService()) {
            // Always, also for a refused request: the activity started this service with startForegroundService, and Android
            // kills the app if startForeground does not follow within a few seconds.
            enterForeground()
            // Refused means another session owns the service. If it is not one that runs in the foreground, do not change its mode.
            if (!accepted && !wasForeground) leaveForeground()
        }
    }

    /** A start command that cannot be carried out, which must still reach startForeground (see [startLink]) before the service ends. */
    private fun rejectForegroundStart() {
        val wasForeground = foreground
        enterForeground()
        when {
            controller.uiState.value.connection == ConnectionStatus.Idle -> shutDown()
            !wasForeground -> leaveForeground()
        }
    }

    /**
     * `connectedDevice` services have a runtime precondition since Android 14 (see [needsForegroundService]); when it is not
     * met, or the system refuses a foreground start for any other reason, the service carries on as an ordinary one.
     */
    private fun enterForeground() {
        // The heartbeat runs whether or not the system lets the service become a foreground one: that is when it is most worth having.
        startStallWatch()
        var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasLocation) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
        }
        try {
            ServiceCompat.startForeground(this, SessionNotifications.ID, notifications.build(controller.uiState.value.connection), type)
            foreground = true
            acquireWakeLock()
            connectionLog.app("service: running in the foreground")
        } catch (e: RuntimeException) {
            // SecurityException (precondition missing), ForegroundServiceStartNotAllowedException and
            // ForegroundServiceTypeException have no common type narrower than this.
            Log.w(TAG, "Could not start as a foreground service; continuing as an ordinary service", e)
            connectionLog.app("service: could not start in the foreground (${e::class.simpleName}: ${e.message}); continuing as an ordinary service")
        }
    }

    /** Back to an ordinary service; the notification stays (and is updated as before), only the foreground priority goes. */
    private fun leaveForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        foreground = false
        releaseWakeLock()
        stopStallWatch()
    }

    /**
     * Keeps the CPU awake while a link to the adapter runs. A foreground service alone does not: on 2026-11-19 the whole process
     * (adapter, motion and GPS streams alike) stood still for 4 minutes with the screen off, and the first write after it timed out
     * and cost the link. Held only while a Bluetooth session is in the foreground, with a ceiling in case a release is ever missed.
     */
    @SuppressLint("WakelockTimeout") // a timeout is set; lint cannot see it through the helper
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_LOCK_CEILING_MS)
            wakeLock = lock
            connectionLog.app("service: keeping the processor awake while connected")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not take a wake lock", e)
            connectionLog.app("service: could not take a wake lock (${e::class.simpleName}: ${e.message})")
        }
    }

    /**
     * A heartbeat every few seconds that writes a line to the connection log when the app stood still for a while, with which kind of
     * stand-still it was and the phone's power state when it came back (see [StallWatch]). Whatever the cause of a stall, the recording
     * alone could not say; this can, from the next drive.
     */
    @Synchronized
    private fun startStallWatch() {
        if (stallJob?.isActive == true) return
        stallJob = scope.launch {
            val watch = StallWatch()
            while (true) {
                try {
                    watch.tick(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())?.let { stall ->
                        connectionLog.app(stall.describe() + "; " + powerState())
                    }
                } catch (e: RuntimeException) {
                    // A diagnostic must never end itself silently: say so once per failure and carry on.
                    Log.w(TAG, "Stall watch failed", e)
                }
                delay(StallWatch.TICK_MS)
            }
        }
    }

    @Synchronized
    private fun stopStallWatch() {
        stallJob?.cancel()
        stallJob = null
    }

    /** The phone's power state now, for the line a stall writes: screen, Doze, battery saver, battery exemption, wake lock. */
    private fun powerState(): String {
        val power = getSystemService(PowerManager::class.java) ?: return "power state unknown"
        val lightDoze = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) power.isDeviceLightIdleMode.toString() else "n/a"
        return "now: screen on ${power.isInteractive}, doze ${power.isDeviceIdleMode}, light doze $lightDoze, " +
            "battery saver ${power.isPowerSaveMode}, exempt from battery optimisation ${power.isIgnoringBatteryOptimizations(packageName)}, " +
            "wake lock held ${wakeLock?.isHeld == true}"
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        try {
            if (lock.isHeld) lock.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not release the wake lock", e)
        }
    }

    private fun demoScenarioId(): String =
        try {
            AppConfig.load(assets).demoScenario
        } catch (e: DefinitionStoreException) {
            // The controller reads the same config first thing and reports it as a failed connection; it needs a target to do so.
            Log.e(TAG, "Cannot read the app config", e)
            ""
        }

    /**
     * The voice: settings, speech engine with automotive acronym expansion, the queue in front of it, and the announcer that turns connection changes into lines.
     * Created with the service (also when it only runs bound and idle), so every screen and feature shares one voice.
     */
    private fun startVoice() {
        voiceSettings = VoiceSettingsStore(
            SharedPreferencesStringStore(applicationContext.getSharedPreferences(SharedPreferencesStringStore.FILE, Context.MODE_PRIVATE)),
        )
        speechEngine = AndroidSpeechEngine(
            applicationContext,
            volume = { voiceSettings.settings.value.volume },
            lexicon = { knightPackManager.getActivePack()?.lexicon },
        )
        queuedSpeaker = QueuedSpeaker(speechEngine, scope)
        val announcer = ConnectionAnnouncer(
            isEnabled = { voiceSettings.settings.value.spokenStatus },
            announce = { speaker.speak(statusUtterance(it.text(applicationContext.resources))) },
        )
        scope.launch { announcer.collect(controller.uiState.map { it.connection }.distinctUntilChanged()) }
        scope.launch { announceBodyScan() }
        scope.launch { announceDrive() }
    }

    /**
     * Tells the driver when the parked scan of the other units is over: a one-shot notification (heads-up) and, when spoken status is on, the voice.
     * Only the step from "running" to an outcome counts, so a banner that is still on screen is not announced again.
     */
    private suspend fun announceBodyScan() {
        var wasRunning = false
        controller.uiState.map { it.bodyScan }.distinctUntilChanged().collect { progress ->
            val message = when {
                wasRunning && progress is BodyScanProgress.Complete -> R.string.bodyscan_spoken_complete
                wasRunning && progress is BodyScanProgress.Partial -> R.string.bodyscan_spoken_partial
                else -> null
            }
            wasRunning = progress is BodyScanProgress.Running
            if (message == null) return@collect
            val text = applicationContext.getString(message)
            connectionLog.app("body scan: $text")
            if (!closed) notifications.postScanDone(text)
            if (voiceSettings.settings.value.spokenStatus) speaker.speak(statusUtterance(text))
        }
    }

    /** One spoken line when a drive of this connection ends (spoken status on); the card restored from an earlier drive is not announced. */
    private suspend fun announceDrive() {
        controller.uiState.map { if (it.driveStage == DriveStage.ENDED) it.driveTruth else null }.distinctUntilChanged().collect { report ->
            val summary = report?.let(::spokenDriveSummary) ?: return@collect
            if (!voiceSettings.settings.value.spokenStatus) return@collect
            val resources = applicationContext
            val text = if (summary.rupees != null) {
                resources.getString(R.string.dt_spoken_cost, summary.km, summary.rupees)
            } else {
                resources.getString(R.string.dt_spoken_km, summary.km)
            }
            speaker.speak(statusUtterance(text))
        }
    }

    /** The notification follows the status (at most once a second); leaving the idle state for good stops the service. */
    private fun watchSession() {
        val statuses = controller.uiState.map { it.connection }.distinctUntilChanged()
        // Every change, unconflated, for diagnosing real sessions from logcat. The statuses carry no address and no VIN.
        scope.launch { statuses.collect { Log.i(TAG, "Status: $it") } }
        scope.launch {
            statuses.filter { it != ConnectionStatus.Idle }.conflate().collect { status ->
                sawActive = true
                if (!closed) notifications.show(status)
                delay(NOTIFICATION_INTERVAL_MS)
            }
        }
        scope.launch {
            statuses.filter { it == ConnectionStatus.Idle }.collect { if (sawActive) shutDown() }
        }
    }

    private fun shutDown() {
        if (closed) return
        sawActive = false
        foreground = false
        releaseWakeLock()
        stopStallWatch()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notifications.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        closed = true
        releaseWakeLock()
        stopStallWatch()
        notifications.cancel()
        // The session must stop and its stream must close, and neither may block the main thread: do it on the service's own
        // scope, and cancel the scope only once that is done. NonCancellable keeps the cleanup out of the scope's cancellation.
        scope.launch(NonCancellable) {
            controller.disconnect()
            delay(SPEECH_SETTLE_MS)
            withTimeoutOrNull(SPEECH_GRACE_MS) { speaker.state.first { it is SpeechState.Idle } }
            queuedSpeaker.close()
            speechEngine.close()
            knightVoicePlayer.stop()
        }.invokeOnCompletion { scope.cancel() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SessionService"
        private const val ACTION_START_DEMO = "knightlink.app.action.START_DEMO"
        private const val ACTION_START_BLE = "knightlink.app.action.START_BLE"
        private const val ACTION_DISCONNECT = "knightlink.app.action.DISCONNECT"
        private const val EXTRA_ADDRESS = "knightlink.app.extra.ADDRESS"
        private const val NOTIFICATION_INTERVAL_MS = 1000L
        private const val WAKE_LOCK_TAG = "knightlink:session"
        private const val WAKE_LOCK_CEILING_MS = 12L * 60 * 60 * 1000
        private const val SPEECH_SETTLE_MS = 300L
        private const val SPEECH_GRACE_MS = 6000L

        fun startDemoIntent(context: Context): Intent = Intent(context, SessionService::class.java).setAction(ACTION_START_DEMO)

        /**
         * Starts a session with the adapter at [address]. Send it with `ContextCompat.startForegroundService`: the service
         * becomes a foreground service at once, as a link to a device must be.
         */
        fun startBleIntent(context: Context, address: String): Intent =
            Intent(context, SessionService::class.java).setAction(ACTION_START_BLE).putExtra(EXTRA_ADDRESS, address)

        fun disconnectIntent(context: Context): Intent = Intent(context, SessionService::class.java).setAction(ACTION_DISCONNECT)

        fun bindIntent(context: Context): Intent = Intent(context, SessionService::class.java)

        /** The recordings directory: app-private storage, never shared as it is (see [RecordingShare]). */
        fun recordingsDirectory(context: Context): File = File(context.filesDir, "recordings")

        /** The rolling connection log (app-private; shared only as a redacted copy, see [ConnectionLog.prepareShare]). */
        fun connectionLogDirectory(context: Context): File = File(context.filesDir, "connection-log")
    }
}

/**
 * Whether a link of this kind needs a foreground service to keep the process alive while the app is in the background.
 *
 * The demo does not. Android 14 and later only let a `connectedDevice` foreground service start when the app holds one of
 * the permissions that go with a device link (a Bluetooth permission granted at runtime, a USB permission, or one of a short
 * list of network and NFC permissions declared in the manifest), and the demo talks to no device. Faking one of those only to
 * qualify would be dishonest and gets the app rejected in review; starting the service anyway throws SecurityException.
 */
fun ConnectionTarget.needsForegroundService(): Boolean = this is ConnectionTarget.Ble

/** The ongoing notification of the session, with a Disconnect action. */
internal class SessionNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notification_channel_description)
            },
        )
    }

    fun build(status: ConnectionStatus): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(context, 0, SessionService.disconnectIntent(context), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(status.text(context.resources))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.action_disconnect), disconnect)
            .build()
    }

    /** Without the notification permission (Android 13 and later) the system drops this silently: the session does not depend on it. */
    fun show(status: ConnectionStatus) {
        manager.notify(ID, build(status))
    }

    fun cancel() {
        manager.cancel(ID)
        manager.cancel(SCAN_ID)
    }

    /** A one-shot heads-up that the parked scan is over and the driver may drive; the session's own notification is not touched. */
    fun postScanDone(text: String) {
        manager.createNotificationChannel(
            NotificationChannel(SCAN_CHANNEL_ID, context.getString(R.string.bodyscan_channel_name), NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            SCAN_ID,
            NotificationCompat.Builder(context, SCAN_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_session)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setTimeoutAfter(SCAN_NOTIFICATION_TIMEOUT_MS)
                .setContentIntent(open)
                .build(),
        )
    }

    companion object {
        const val ID = 1
        const val SCAN_ID = 2
        private const val CHANNEL_ID = "session"
        private const val SCAN_CHANNEL_ID = "scan"
        private const val SCAN_NOTIFICATION_TIMEOUT_MS = 120_000L
    }
}
