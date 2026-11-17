package knightlink.voice.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The [Speaker] every platform shares: the queue policy of [SpeechQueue] in front of a platform [SpeechEngine].
 *
 * One worker coroutine in [scope] speaks one line at a time. Callers may use it from any thread: every decision is made by
 * applying a pure function to one immutable snapshot with a compare-and-set, so a call returns its [SpeakResult] at once and two
 * callers can never see half of each other's change. The worker, and the [state] shown to the screen, follow that snapshot.
 *
 * It ends with [close] or when [scope] is cancelled; after that [speak] returns [SpeakResult.CLOSED].
 *
 * @param engine turns a line into sound
 * @param scope where the worker runs; cancelling it silences the speaker
 * @param timeSource the clock for the quiet period and for [Utterance.maxAge]
 * @param quietPeriod how long an identical line in the same category is not repeated
 */
public class QueuedSpeaker(
    private val engine: SpeechEngine,
    scope: CoroutineScope,
    timeSource: TimeSource = TimeSource.Monotonic,
    quietPeriod: Duration = 60.seconds,
) : Speaker {
    /** The line being spoken. [level] is kept here, next to the line, so a stale callback of an earlier line cannot touch a later one. */
    private data class Running(val id: Long, val utterance: Utterance, val level: Float)

    private data class Core(
        val queue: SpeechQueue,
        val mode: VoiceMode = VoiceMode.SPEAKING,
        val running: Running? = null,
        val lastSpoken: Utterance? = null,
        val nextId: Long = 1L,
        val closed: Boolean = false,
    ) {
        /** Which lines may be spoken in the current [mode]: all while speaking, only critical ones while the driver talks. */
        fun minPriority(): Priority = if (mode == VoiceMode.SPEAKING) Priority.STATUS else Priority.CRITICAL

        fun interrupt(heard: Boolean): Core {
            val line = running ?: return this
            val requeue = line.utterance.priority >= Priority.ALERT && !heard
            return copy(running = null, queue = queue.interrupted(requeue = requeue, heard = heard))
        }

        fun offer(
            utterance: Utterance,
            force: Boolean,
        ): Pair<Core, SpeakResult> {
            val offered = queue.offer(utterance, force)
            val next = copy(queue = offered.queue)
            val current = running?.utterance
            val interrupts = offered.result != SpeakResult.UNCHANGED && current != null && utterance.priority > current.priority
            return (if (interrupts) next.interrupt(heard = false) else next) to offered.result
        }
    }

    private val core = MutableStateFlow(Core(SpeechQueue(quietPeriod = quietPeriod, timeSource = timeSource)))
    private val speechState = MutableStateFlow<SpeechState>(SpeechState.Idle)
    private val spoken = MutableStateFlow<Utterance?>(null)

    override val state: StateFlow<SpeechState> = speechState.asStateFlow()
    override val lastSpoken: StateFlow<Utterance?> = spoken.asStateFlow()

    private val publisher: Job = scope.launch { core.collect { publish(it) } }
    private val worker: Job = scope.launch { work() }

    init {
        worker.invokeOnCompletion {
            core.update { it.copy(closed = true, running = null, queue = it.queue.cleared()) }
            publisher.cancel()
            speechState.value = SpeechState.Idle
        }
    }

    override fun speak(utterance: Utterance): SpeakResult =
        transact { c -> if (c.closed) c to SpeakResult.CLOSED else c.offer(utterance, force = false) }

    override fun repeatLast(): Boolean =
        transact { c ->
            val last = c.lastSpoken
            if (c.closed || last == null) {
                c to false
            } else {
                // At least an answer's priority, so it is not lost behind chatter, and never less than it had.
                c.offer(last.copy(priority = maxOf(last.priority, Priority.ANSWER)), force = true).first to true
            }
        }

    override fun skip() {
        // Stopped on purpose: it counts as heard and is not put back, even if it was an alert.
        transact { c -> c.interrupt(heard = true) to Unit }
    }

    override fun stopAll() {
        transact { c -> c.interrupt(heard = true).let { it.copy(queue = it.queue.cleared()) } to Unit }
    }

    override fun setMode(mode: VoiceMode) {
        transact { c ->
            val next = c.copy(mode = mode)
            val line = c.running
            // A critical line keeps going: it is the one thing the driver must hear even with the microphone open.
            val stops = mode == VoiceMode.LISTENING && line != null && line.utterance.priority < Priority.CRITICAL
            (if (stops) next.interrupt(heard = false) else next) to Unit
        }
    }

    /** Stops speaking, drops everything waiting and ends the worker. */
    public fun close() {
        worker.cancel()
    }

    private fun <R> transact(change: (Core) -> Pair<Core, R>): R {
        var result: R? = null
        core.update { c ->
            val (next, r) = change(c)
            result = r
            next
        }
        @Suppress("UNCHECKED_CAST")
        return result as R
    }

    private fun publish(c: Core) {
        val running = c.running
        speechState.value =
            when {
                running != null -> SpeechState.Speaking(running.utterance, running.level)
                c.mode == VoiceMode.LISTENING -> SpeechState.Listening
                c.mode == VoiceMode.THINKING -> SpeechState.Thinking
                else -> SpeechState.Idle
            }
        spoken.value = c.lastSpoken
    }

    private suspend fun work() {
        var finished: Long? = null
        while (true) {
            val line = nextLine(finished)
            finished = line.id
            speakLine(line)
        }
    }

    /** Ends [finished] and takes the next line in one step, so the state never shows a gap between two lines that follow each other. */
    private suspend fun nextLine(finished: Long?): Running {
        var previous = finished
        while (true) {
            var taken: Running? = null
            core.update { c ->
                taken = null
                var next = c
                if (previous != null && next.running?.id == previous) next = next.copy(running = null, queue = next.queue.finished())
                if (next.running == null) {
                    val polled = next.queue.poll(next.minPriority())
                    next = next.copy(queue = polled.queue)
                    if (polled.utterance != null) {
                        val line = Running(next.nextId, polled.utterance, SpeechState.UNKNOWN_LEVEL)
                        taken = line
                        next = next.copy(running = line, lastSpoken = polled.utterance, nextId = next.nextId + 1)
                    }
                }
                next
            }
            previous = null
            taken?.let { return it }
            core.first { it.running == null && it.queue.hasEligible(it.minPriority()) }
        }
    }

    private suspend fun speakLine(line: Running) =
        coroutineScope {
            val sound =
                launch {
                    try {
                        engine.speak(line.utterance.text) { level -> publishLevel(line.id, level) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The line could not be spoken; there is nobody to tell while driving, so go on with the next one.
                    }
                }
            // Whoever stopped this line (a higher priority, skip, listening) already took it out of the snapshot; that is the signal.
            val stopper =
                launch {
                    core.first { it.running?.id != line.id }
                    sound.cancel()
                }
            sound.join()
            stopper.cancel()
        }

    private fun publishLevel(
        id: Long,
        level: Float,
    ) {
        if (level.isNaN()) return
        val clamped = level.coerceIn(0f, 1f)
        core.update { c ->
            val running = c.running
            if (running != null && running.id == id) c.copy(running = running.copy(level = clamped)) else c
        }
    }
}
