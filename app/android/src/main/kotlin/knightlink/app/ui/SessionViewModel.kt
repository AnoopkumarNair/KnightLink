package knightlink.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import knightlink.app.session.RecordingShare
import knightlink.app.session.RecordingStore
import knightlink.app.session.SessionController
import knightlink.app.session.SessionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _pendingShare = MutableStateFlow<RecordingShare.Prepared?>(null)

    /** A scrubbed copy waiting for the user to confirm, because the scrubber had warnings. */
    val pendingShare: StateFlow<RecordingShare.Prepared?> = _pendingShare.asStateFlow()

    private val _shareError = MutableStateFlow<String?>(null)

    /** Why the latest attempt to prepare a recording failed; null when there is nothing to report. */
    val shareError: StateFlow<String?> = _shareError.asStateFlow()

    /** Whether the notification permission has been asked for in this process; it is asked for once, not on every start. */
    var notificationPermissionAsked: Boolean = false

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
        connection.unbind()
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
