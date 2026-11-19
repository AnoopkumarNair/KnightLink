package knightlink.app.voice

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * Starts push-to-talk from a steering-wheel or headset media button, using a framework [MediaSession] (no extra library).
 *
 * The session exists only while it is wanted: between [start] (the activity's onStart) and [stop] (onStop), and only when the
 * configured gesture is not [MediaButtonGesture.OFF]. It reports itself as paused with play/pause actions, which is what makes the
 * system route media buttons to it; [ButtonGesture] then decides whether a long or double press happened.
 *
 * Be honest about what this can and cannot do:
 * - Android sends media buttons to the most recently PLAYING media session. While music plays in another app the button goes
 *   there, not here. This session is only reached when nothing else has played more recently.
 * - When this session does hold the buttons, a single press does nothing: it is consumed and not forwarded to the music app.
 *   Pressing the button for a long time, or twice quickly (per the setting), is the only thing that does something.
 *
 * Main thread only. [onTrigger] runs on the main thread.
 */
class MediaButtonTrigger(
    private val context: Context,
    private val gesture: ButtonGesture,
    private val onTrigger: () -> Unit,
) {
    private var session: MediaSession? = null
    private var started = false

    /** The activity is in front: take the buttons if a gesture is configured. */
    fun start() {
        started = true
        refresh()
    }

    /** The activity is no longer in front: give the buttons back. */
    fun stop() {
        started = false
        refresh()
    }

    /** Switches the gesture; turning it off releases the session. */
    fun setGesture(value: MediaButtonGesture) {
        gesture.gesture = value
        gesture.reset()
        refresh()
    }

    private fun refresh() {
        val wanted = started && gesture.gesture != MediaButtonGesture.OFF
        if (wanted && session == null) {
            session = create()
        } else if (!wanted) {
            release()
        }
    }

    private fun create(): MediaSession {
        val created = MediaSession(context, SESSION_TAG)
        created.setCallback(
            object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.keyEvent() ?: return false
                    return gesture.onEvent(event.toPlain(), onTrigger)
                }
            },
            Handler(Looper.getMainLooper()),
        )
        created.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                .build(),
        )
        created.isActive = true
        return created
    }

    private fun release() {
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        gesture.reset()
    }

    private fun Intent.keyEvent(): KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }

    private fun KeyEvent.toPlain() = MediaKeyEvent(
        down = action == KeyEvent.ACTION_DOWN,
        keyCode = keyCode,
        repeatCount = repeatCount,
        eventTimeMs = eventTime,
        isLongPress = flags and KeyEvent.FLAG_LONG_PRESS != 0,
    )

    private companion object {
        const val SESSION_TAG = "KnightLinkTalk"
    }
}
