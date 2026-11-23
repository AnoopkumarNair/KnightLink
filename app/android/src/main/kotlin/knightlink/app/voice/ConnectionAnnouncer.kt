package knightlink.app.voice

import knightlink.app.session.ConnectionStatus
import knightlink.voice.api.Priority
import knightlink.voice.api.Utterance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the voice tells the driver about the connection. The words are in string resources; see [text]. */
sealed interface Announcement {
    data object Connected : Announcement

    data object WaitingForIgnition : Announcement

    /** The adapter is there but the car's bus does not answer. */
    data object BusNotReachable : Announcement

    data object ConnectionLost : Announcement

    data object Reconnected : Announcement

    /** [reason] is short already (see [shortReason]); it may be empty. */
    data class Failed(val reason: String) : Announcement

    data object Disconnected : Announcement
}

/** A status line is only worth hearing for a short while; a connection message that waited behind others is no longer true. */
val STATUS_MAX_AGE = 20.seconds

/** The line to speak for [text], which is the [Announcement] in words. */
fun statusUtterance(text: String): Utterance = Utterance(text, Priority.STATUS, category = "connection", maxAge = STATUS_MAX_AGE)

/**
 * Decides what to say about a stream of [ConnectionStatus] changes. The driver is in the car and the connection is not what they
 * are looking at, so it speaks only what they would want to know, and never twice about the same thing:
 * - Connecting, initialising and probing are silent: they are the normal way to a connection, and the next line says how it went.
 * - The first `Live` of a connection is [Announcement.Connected].
 * - Entering "waiting for the ignition" says so once ([Announcement.WaitingForIgnition], or [Announcement.BusNotReachable] when the
 *   adapter reported the bus unreachable); the probe counter ticking up does not repeat it.
 * - Losing the link ([ConnectionStatus.Reconnecting], [ConnectionStatus.Recovering]) says [Announcement.ConnectionLost] only when
 *   the outage has lasted [lossDelay], and once per outage however many attempts and status changes follow. An outage that ends
 *   inside the delay is never mentioned, and neither is its end. If the loss was spoken, coming back says [Announcement.Reconnected].
 * - A failure says [Announcement.Failed] with the first sentence of the reason.
 * - Going idle says [Announcement.Disconnected] only if something was said during this connection, and not straight after a
 *   failure: a start that fails at once is already explained by the failure, and a quick start that was never heard should not end
 *   with a lone "Disconnected".
 *
 * While [isEnabled] is false nothing is announced, but the state keeps following the statuses, and a line that was not spoken
 * leaves no trace: switching the setting on later does not produce "Reconnected" for a loss that nobody was told about.
 *
 * @param isEnabled read at the moment of speaking, so a setting changed in the middle of an outage is honoured
 * @param announce receives each announcement; may be called from the collecting coroutine or from the loss timer
 */
class ConnectionAnnouncer(
    private val lossDelay: Duration = DEFAULT_LOSS_DELAY,
    private val isEnabled: () -> Boolean,
    private val announce: (Announcement) -> Unit,
) {
    private val lock = Any()

    // All of the following is guarded by lock: the collector and the loss timer both change it.
    private var previous: ConnectionStatus = ConnectionStatus.Idle
    private var liveSeen = false
    private var spokenInterruption = false
    private var spokenAnything = false
    private var failed = false
    private var inOutage = false
    private var outageId = 0
    private var outageTimer: Job? = null

    /** Follows [statuses] until it ends or the caller is cancelled. */
    suspend fun collect(statuses: Flow<ConnectionStatus>) {
        try {
            coroutineScope { statuses.collect { handle(it, this) } }
        } finally {
            synchronized(lock) { endOutage() }
        }
    }

    private fun handle(status: ConnectionStatus, scope: CoroutineScope) {
        val toSay = synchronized(lock) { next(status, scope).also { previous = status } }
        toSay?.let(announce)
    }

    /** Updates the state for [status] and returns what to say, if anything. Called with [lock] held. */
    private fun next(status: ConnectionStatus, scope: CoroutineScope): Announcement? =
        when (status) {
            ConnectionStatus.Idle -> {
                endOutage()
                val farewell = if (spokenAnything && !failed && isEnabled()) Announcement.Disconnected else null
                resetConnection()
                farewell
            }
            is ConnectionStatus.Connecting, ConnectionStatus.Initialising, is ConnectionStatus.Negotiating, is ConnectionStatus.Probing -> null
            is ConnectionStatus.CannotRead, is ConnectionStatus.Learning -> null
            is ConnectionStatus.WaitingForIgnition -> {
                // The link is up again, so an outage is over; the ignition is the story now.
                endOutage()
                if (previous is ConnectionStatus.WaitingForIgnition) {
                    null
                } else {
                    spoken(if (status.busFault) Announcement.BusNotReachable else Announcement.WaitingForIgnition)?.also { spokenInterruption = true }
                }
            }
            ConnectionStatus.Live -> {
                endOutage()
                val line = when {
                    !liveSeen -> spoken(Announcement.Connected)
                    spokenInterruption -> spoken(Announcement.Reconnected)
                    else -> null
                }
                liveSeen = true
                spokenInterruption = false
                line
            }
            is ConnectionStatus.Reconnecting, is ConnectionStatus.Recovering -> {
                if (!inOutage) startOutage(scope)
                null
            }
            is ConnectionStatus.Failed -> {
                endOutage()
                failed = true
                spoken(Announcement.Failed(shortReason(status.reason)))
            }
            is ConnectionStatus.VehicleAsleep -> null
        }

    /** [line] if it may be said now (and it is then remembered as said), otherwise null. */
    private fun spoken(line: Announcement): Announcement? {
        if (!isEnabled()) return null
        spokenAnything = true
        return line
    }

    private fun startOutage(scope: CoroutineScope) {
        inOutage = true
        val id = ++outageId
        outageTimer = scope.launch {
            delay(lossDelay)
            val line = synchronized(lock) {
                if (inOutage && outageId == id) spoken(Announcement.ConnectionLost)?.also { spokenInterruption = true } else null
            }
            line?.let(announce)
        }
    }

    private fun endOutage() {
        inOutage = false
        outageId++
        outageTimer?.cancel()
        outageTimer = null
    }

    private fun resetConnection() {
        liveSeen = false
        spokenInterruption = false
        spokenAnything = false
        failed = false
    }

    companion object {
        val DEFAULT_LOSS_DELAY: Duration = 4.seconds

        private const val MAX_REASON_LENGTH = 80

        /**
         * The first sentence of a failure reason, trimmed and at most 80 characters (cut at a word where there is one): the reason
         * may be a long technical message, and a driver wants to hear a few words of it.
         */
        fun shortReason(reason: String): String {
            val trimmed = reason.trim()
            val end = trimmed.indexOfFirst { it == '\n' || it == '\r' }.takeIf { it >= 0 } ?: trimmed.length
            val firstLine = trimmed.substring(0, end)
            // A full stop followed by space ends a sentence; one inside "v1.2" or "file.txt" does not.
            val stop = Regex("[.!?](\\s|$)").find(firstLine)?.range?.first
            val sentence = (if (stop == null) firstLine else firstLine.substring(0, stop)).trim()
            if (sentence.length <= MAX_REASON_LENGTH) return sentence
            val cut = sentence.substring(0, MAX_REASON_LENGTH)
            val atWord = cut.lastIndexOf(' ')
            return (if (atWord > MAX_REASON_LENGTH / 2) cut.substring(0, atWord) else cut).trimEnd(',', ';', ':', ' ')
        }
    }
}
