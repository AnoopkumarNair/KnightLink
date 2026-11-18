package knightlink.app.voice

/** What the steering-wheel (headset) button must do to start push-to-talk. */
enum class MediaButtonGesture {
    OFF,
    LONG_PRESS,
    DOUBLE_PRESS,
    ;

    companion object {
        val DEFAULT = LONG_PRESS

        /** The stored setting, or [DEFAULT] when nothing (or something unknown) is stored. */
        fun parse(stored: String?): MediaButtonGesture = entries.firstOrNull { it.name == stored } ?: DEFAULT
    }
}

/** A media key event as plain data, so the gesture logic runs without Android. */
data class MediaKeyEvent(
    val down: Boolean,
    val keyCode: Int,
    val repeatCount: Int,
    /** Milliseconds, on any clock that is the same for every event. */
    val eventTimeMs: Long,
    /** The system's `FLAG_LONG_PRESS`. */
    val isLongPress: Boolean,
)

/**
 * Decides from a stream of media key events whether the configured [gesture] happened. Pure and single-threaded (one instance per
 * receiver, called from the thread the events arrive on).
 *
 * - Long press: a DOWN with the long-press flag or a repeat count above zero, or an UP that follows its DOWN by at least
 *   [LONG_PRESS_MS]. Fires once per press, whichever of these comes first.
 * - Double press: two UPs less than [DOUBLE_PRESS_MS] apart. The second UP fires and the pair is used up, so three quick presses
 *   fire once, not twice.
 *
 * Only the keys in [ACCEPTED_KEYS] count; every other key is left alone (not consumed).
 */
class ButtonGesture(var gesture: MediaButtonGesture) {
    private var downAt: Long? = null
    private var firedThisPress = false
    private var lastUpAt: Long? = null

    /**
     * @return true when the event was consumed: it is one of our keys and a gesture other than OFF is configured.
     *   [onFired] is called at most once per recognised gesture, before this returns.
     */
    fun onEvent(event: MediaKeyEvent, onFired: () -> Unit): Boolean {
        if (event.keyCode !in ACCEPTED_KEYS) return false
        when (gesture) {
            MediaButtonGesture.OFF -> return false
            MediaButtonGesture.LONG_PRESS -> longPress(event, onFired)
            MediaButtonGesture.DOUBLE_PRESS -> doublePress(event, onFired)
        }
        return true
    }

    /** Forgets a press in progress, e.g. when the trigger is switched off. */
    fun reset() {
        downAt = null
        firedThisPress = false
        lastUpAt = null
    }

    private fun longPress(event: MediaKeyEvent, onFired: () -> Unit) {
        if (event.down) {
            // A first DOWN (repeat count 0) always starts a press, even if the UP of the previous one was lost.
            if (downAt == null || event.repeatCount == 0) {
                downAt = event.eventTimeMs
                firedThisPress = false
            }
            if ((event.isLongPress || event.repeatCount > 0) && !firedThisPress) {
                firedThisPress = true
                onFired()
            }
        } else {
            val start = downAt
            if (start != null && !firedThisPress && event.eventTimeMs - start >= LONG_PRESS_MS) onFired()
            downAt = null
            firedThisPress = false
        }
    }

    private fun doublePress(event: MediaKeyEvent, onFired: () -> Unit) {
        if (event.down) return
        val previous = lastUpAt
        if (previous != null && event.eventTimeMs - previous < DOUBLE_PRESS_MS) {
            lastUpAt = null
            onFired()
        } else {
            lastUpAt = event.eventTimeMs
        }
    }

    companion object {
        const val LONG_PRESS_MS = 600L
        const val DOUBLE_PRESS_MS = 400L

        // android.view.KeyEvent values, as plain ints so this class needs no Android to run.
        const val KEYCODE_HEADSETHOOK = 79
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_MEDIA_PLAY = 126
        const val KEYCODE_MEDIA_PAUSE = 127
        val ACCEPTED_KEYS = setOf(KEYCODE_HEADSETHOOK, KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE)
    }
}
