package knightlink.app.session

import knightlink.core.sim.SimulatedTransport
import knightlink.core.sim.SimulationFile
import knightlink.core.sim.VirtualElm327
import knightlink.core.sim.VirtualVehicle
import knightlink.core.transport.ByteStream
import knightlink.core.transport.Transport
import knightlink.core.transport.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Demo mode: a simulated vehicle and adapter ([SimulatedTransport]) behind the same [Transport] interface a real adapter
 * will use, so the complete stack (driver, probe, scheduler, session, recorder) runs unchanged on the phone.
 *
 * Unlike a one-shot simulation the scenario's timeline is played in a loop, so values keep changing for as long as the
 * demo runs; see [ScenarioPlayer].
 *
 * @param scenario loads a scenario by id (from the bundled assets); throws [IllegalArgumentException] or a subclass of
 *   it for an unknown id
 * @param responseDelay how long the virtual adapter takes per command; 45 ms is in the range measured for ELM327 adapters
 *   over BLE, so the achieved request rate and the poll timing look like the real thing
 * @param restartAfter how long the last state of the timeline stays before the vehicle is reset and the timeline starts again
 */
class DemoTransportFactory(
    private val scenario: (String) -> SimulationFile,
    private val responseDelay: Duration = 45.milliseconds,
    private val restartAfter: Duration = 5.seconds,
) : TransportFactory {
    /** The transport handed out last; for tests that inspect the virtual adapter. */
    internal var lastTransport: DemoTransport? = null
        private set

    override fun create(target: ConnectionTarget): Transport = when (target) {
        is ConnectionTarget.Demo ->
            DemoTransport(scenario(target.scenarioId), VirtualElm327.Config(responseDelay = responseDelay), restartAfter)
                .also { lastTransport = it }
        is ConnectionTarget.Ble -> throw UnsupportedOperationException("Bluetooth support is not built in yet")
    }
}

/** One demo vehicle. The vehicle outlives reconnects, as a real car outlives its adapter; each [open] gets a fresh virtual adapter. */
internal class DemoTransport(
    private val scenario: SimulationFile,
    adapterConfig: VirtualElm327.Config,
    private val restartAfter: Duration,
) : Transport {
    private val vehicle = scenario.buildVehicle()
    internal val simulated = SimulatedTransport(vehicle = { vehicle }, config = adapterConfig)

    // The simulator is single-threaded by design, but the session runs on a multi-threaded dispatcher and the scenario player
    // is a separate coroutine. Every touch of the vehicle (a command arriving, a scenario step) takes this lock.
    private val vehicleLock = Mutex()

    override val kind: TransportKind get() = simulated.kind

    override suspend fun open(): ByteStream {
        val adapter = simulated.open()
        val player = ScenarioPlayer(scenario, vehicle, vehicleLock, restartAfter)
        vehicleLock.withLock { player.restore() }
        // A child of whoever opens the link (the session), so it ends with the session even if close() is never reached.
        // The supervisor itself is what close() cancels: cancelling only the player would leave the supervisor attached to the
        // session's job, one more per reconnect.
        val context = currentCoroutineContext()
        val supervisor = SupervisorJob(context[Job])
        CoroutineScope(context.minusKey(Job) + supervisor).launch { player.run() }
        return GuardedStream(adapter, vehicleLock, supervisor)
    }

    private class GuardedStream(
        private val adapter: VirtualElm327,
        private val lock: Mutex,
        private val player: Job,
    ) : ByteStream {
        override val incoming: Flow<ByteArray> get() = adapter.incoming

        override suspend fun write(bytes: ByteArray) {
            lock.withLock { adapter.write(bytes) }
        }

        override suspend fun close() {
            player.cancel()
            adapter.close()
        }
    }
}

/**
 * Plays a scenario's timeline over and over: the steps in time order, then [restartAfter] with the last state in place
 * (so a value the timeline ends on, such as a finished regeneration, is on screen long enough to be seen), then the
 * vehicle is reset to its initial state and the timeline starts again.
 *
 * The reset is exact for everything the timeline touches: those replies are removed and, where the scenario's initial data
 * has them, set again. That is all the timeline can change, because [SimulationFile.Step] only sets or removes replies.
 * (The identification replies built from the ECU's `vin`, `name` and `calibrationId` fields are not restored, so a
 * timeline must not touch service 09.)
 */
internal class ScenarioPlayer(
    private val scenario: SimulationFile,
    private val vehicle: VirtualVehicle,
    private val lock: Mutex,
    private val restartAfter: Duration,
) {
    init {
        require(restartAfter > Duration.ZERO) { "restartAfter must be positive, or an empty-handed loop would spin" }
    }

    private val requestIds = scenario.ecus.associate { it.id to it.requestId.toInt(16) }
    private val steps = scenario.timeline.sortedBy { it.atMs }

    /** Loops until cancelled. Returns at once for a scenario without a timeline, since nothing would ever change. */
    suspend fun run() {
        if (steps.isEmpty()) return
        while (true) {
            var elapsed = Duration.ZERO
            for (step in steps) {
                val at = step.atMs.milliseconds
                if (at > elapsed) {
                    delay(at - elapsed)
                    elapsed = at
                }
                lock.withLock { apply(step) }
            }
            delay(restartAfter)
            lock.withLock { restore() }
        }
    }

    private fun apply(step: SimulationFile.Step) {
        val ecu = vehicle.ecu(requestIds[step.ecu] ?: throw IllegalArgumentException("${scenario.id}: unknown ECU '${step.ecu}'"))
        step.set.forEach { (request, data) -> ecu.set(request, data) }
        step.remove.forEach { ecu.remove(it) }
    }

    /** Back to the scenario's initial replies. Callers hold the vehicle lock. */
    fun restore() {
        for (spec in scenario.ecus) {
            val ecu = vehicle.ecu(requestIds.getValue(spec.id))
            val initial = spec.data.mapKeys { normalise(it.key) }
            val touched = steps.filter { it.ecu == spec.id }.flatMap { it.set.keys + it.remove }.map(::normalise).toSet()
            for (request in touched) {
                ecu.remove(request)
                initial[request]?.let { ecu.set(request, it) }
            }
        }
    }

    private fun normalise(request: String): String = request.filterNot { it.isWhitespace() }.uppercase()
}
