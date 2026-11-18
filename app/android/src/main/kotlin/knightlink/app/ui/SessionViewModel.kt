package knightlink.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import knightlink.app.ble.LastAdapter
import knightlink.app.ble.LastAdapterStore
import knightlink.app.ble.PendingStart
import knightlink.app.ble.PermissionDenial
import knightlink.app.ble.StringStore
import knightlink.app.data.AndroidAssetSource
import knightlink.app.data.AppConfig
import knightlink.app.data.DefinitionStore
import knightlink.app.session.ConnectionLog
import knightlink.app.session.RecordingShare
import knightlink.app.session.RecordingStore
import knightlink.app.session.SessionController
import knightlink.app.session.SessionService
import knightlink.app.session.requireBle
import knightlink.app.voice.AndroidSpeechRecognizer
import knightlink.app.voice.MediaButtonGesture
import knightlink.app.voice.PushToTalk
import knightlink.app.voice.StubTtsAnswerOutput
import knightlink.platform.ble.BleAdapterScanner
import knightlink.platform.ble.DiscoveredAdapter
import knightlink.voice.intents.NoWarningsSource
import knightlink.voice.intents.VoiceAssistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * What outlives a rotation on the UI side: the binding to [SessionService] (made with the application context, so it is not
 * tied to one activity instance, and the screen does not flash "idle" while a new activity binds), and the state of the share
 * flow. The session itself lives in the service.
 */
class SessionViewModel(application: Application) : AndroidViewModel(application) {
    private val connection = Connection(application)

    /** The service's controller; null until the first bind completes, and while the service process is gone. */
    val controller: StateFlow<SessionController?> = connection.controller

    val recordings = RecordingStore(SessionService.recordingsDirectory(application))

    /** The rolling text log of connection attempts; the same instance the service writes to. */
    val connectionLog: ConnectionLog = ConnectionLog.of(SessionService.connectionLogDirectory(application))

    private val _pendingShare = MutableStateFlow<RecordingShare.Prepared?>(null)

    /** A scrubbed copy waiting for the user to confirm, because the scrubber had warnings. */
    val pendingShare: StateFlow<RecordingShare.Prepared?> = _pendingShare.asStateFlow()

    private val _shareError = MutableStateFlow<String?>(null)

    /** Why the latest attempt to prepare a recording failed; null when there is nothing to report. */
    val shareError: StateFlow<String?> = _shareError.asStateFlow()

    /** Whether the notification permission has been asked for in this process; it is asked for once, not on every start. */
    var notificationPermissionAsked: Boolean = false

    /** What the user asked for while the permissions are being sorted out; null when nothing is waiting. Main thread only. */
    var pendingStart: PendingStart? = null

    private val _permissionDenial = MutableStateFlow<PermissionDenial?>(null)

    /** Set when Bluetooth permissions were refused, until the user has seen the explanation. */
    val permissionDenial: StateFlow<PermissionDenial?> = _permissionDenial.asStateFlow()

    private val _pickerOpen = MutableStateFlow(false)

    /** Whether the adapter picker is showing. Kept here so a rotation does not close it. */
    val pickerOpen: StateFlow<Boolean> = _pickerOpen.asStateFlow()

    private val lastAdapterStore = LastAdapterStore(SharedPreferencesStringStore(application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)))
    private val _lastAdapter = MutableStateFlow(lastAdapterStore.load())

    /** The adapter connected to last, if any. */
    val lastAdapter: StateFlow<LastAdapter?> = _lastAdapter.asStateFlow()

    private val scanner = BleAdapterScanner(application)

    private val preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    // Voice: created here (application context, main thread) so a rotation neither drops a question nor leaks the recogniser.
    private val recognizer = AndroidSpeechRecognizer(application)
    private val speech = StubTtsAnswerOutput(application)
    private val assistant = VoiceAssistant(output = speech, warnings = NoWarningsSource)

    /** Push to talk. Main thread only. Answers from the service's session, or as "not connected" when there is none. */
    val pushToTalk = PushToTalk(recognizer, assistant) { controller.value?.answerContext() ?: SessionController.NOT_CONNECTED_CONTEXT }

    /** True when speech is recognised on this phone; false when the phone's speech service may send audio online. */
    val recognisedOnDevice: Boolean get() = recognizer.isOnDevice

    private val _mediaGesture = MutableStateFlow(MediaButtonGesture.parse(preferences.getString(MEDIA_BUTTON_GESTURE, null)))

    /** What the steering-wheel button must do to start push to talk. */
    val mediaGesture: StateFlow<MediaButtonGesture> = _mediaGesture.asStateFlow()

    fun setMediaGesture(gesture: MediaButtonGesture) {
        preferences.edit { putString(MEDIA_BUTTON_GESTURE, gesture.name) }
        _mediaGesture.value = gesture
    }

    private val _micExplanationOpen = MutableStateFlow(false)

    /** Whether the "why the microphone" explanation is showing; it comes before the system's permission prompt. */
    val micExplanationOpen: StateFlow<Boolean> = _micExplanationOpen.asStateFlow()

    fun setMicExplanationOpen(open: Boolean) {
        _micExplanationOpen.value = open
    }

    private val _micDenial = MutableStateFlow<PermissionDenial?>(null)

    /** Set when the microphone permission was refused, until the user has seen the explanation. */
    val micDenial: StateFlow<PermissionDenial?> = _micDenial.asStateFlow()

    fun setMicDenial(denial: PermissionDenial?) {
        _micDenial.value = denial
    }

    /**
     * A scan for the configured adapter, started when collected and stopped when the collection ends. Fails (inside the flow)
     * if the definition has no Bluetooth profile, or the scan cannot start; see [pickerStates].
     */
    fun scan(): Flow<DiscoveredAdapter> = flow {
        val profile = withContext(Dispatchers.IO) {
            val assets = AndroidAssetSource(getApplication<Application>().assets)
            DefinitionStore(assets).adapter(AppConfig.load(assets).adapterDefinition).requireBle()
        }
        emitAll(scanner.scan(profile))
    }

    /** Remembers [adapter] for the next start. Only the address and the advertised name are kept. */
    fun remember(adapter: LastAdapter) {
        lastAdapterStore.save(adapter)
        _lastAdapter.value = lastAdapterStore.load()
    }

    fun setPermissionDenial(denial: PermissionDenial?) {
        _permissionDenial.value = denial
    }

    fun setPickerOpen(open: Boolean) {
        _pickerOpen.value = open
    }

    init {
        connection.bind()
    }

    fun setPendingShare(prepared: RecordingShare.Prepared?) {
        _pendingShare.value = prepared
    }

    fun setShareError(message: String?) {
        _shareError.value = message
    }

    override fun onCleared() {
        pushToTalk.cancel()
        recognizer.close()
        speech.close()
        connection.unbind()
    }

    private companion object {
        const val PREFERENCES = "knightlink"
        const val MEDIA_BUTTON_GESTURE = "media_button_gesture"
    }

    private class SharedPreferencesStringStore(private val preferences: SharedPreferences) : StringStore {
        override fun get(key: String): String? = preferences.getString(key, null)

        override fun put(key: String, value: String?) {
            preferences.edit { if (value == null) remove(key) else putString(key, value) }
        }
    }

    private class Connection(private val context: Context) : ServiceConnection {
        private val _controller = MutableStateFlow<SessionController?>(null)
        val controller: StateFlow<SessionController?> = _controller.asStateFlow()
        private var bound = false

        fun bind() {
            // BIND_AUTO_CREATE: an idle app has no running service, and binding creates it with an idle controller. Starting a
            // session then calls startService, which keeps the service alive after this binding goes away.
            if (!bound) bound = context.bindService(SessionService.bindIntent(context), this, Context.BIND_AUTO_CREATE)
        }

        fun unbind() {
            if (bound) context.unbindService(this)
            bound = false
            _controller.value = null
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            _controller.value = (service as? SessionService.LocalBinder)?.controller
        }

        override fun onServiceDisconnected(name: ComponentName) {
            _controller.value = null
        }

        override fun onBindingDied(name: ComponentName) {
            _controller.value = null
        }
    }
}
