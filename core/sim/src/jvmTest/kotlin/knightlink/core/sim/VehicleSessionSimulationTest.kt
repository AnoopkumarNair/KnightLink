package knightlink.core.sim

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.adapter.Elm327Driver
import knightlink.core.runtime.SessionState
import knightlink.core.runtime.VehicleSession
import knightlink.core.scheduler.PollResult
import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import knightlink.core.telemetry.TelemetrySnapshot
import knightlink.core.transport.ByteStream
import knightlink.core.transport.Transport
import knightlink.core.vehicle.ProbeStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole runtime on virtual time: scenario file -> virtual ECUs -> virtual adapter -> transport -> session
 * (driver, probe, poll loop, reconnect) -> telemetry. No hardware, no real waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleSessionSimulationTest {
    private val root = File(System.getProperty("knightlink.repoRoot"))
    private val simulation = SimulationFile.parse(File(root, "fixtures/sim/harrier-dpf-regen.json").readText())
    private val initScript = AdapterDefinition.parse(File(root, "definitions/adapters/vgate-icar-pro-2s.json").readText()).initScript
    private val catalog = catalogFor(simulation.definition)

    /** Time the adapter takes per command: in the range measured for ELM327 adapters over BLE. */
    private val roundTrip = 45.milliseconds
    private val adapterConfig = VirtualElm327.Config(responseDelay = roundTrip)

    private fun definition(id: String): DefinitionFile = DefinitionJson.parse(File(root, "definitions/$id.json").readText())

    private fun catalogFor(id: String): SignalCatalog {
        val top = definition(id)
        return SignalCatalog.of(top.extends.map(::definition) + top)
    }

    /** Counts closes of the stream it hands out; the simulator itself does not care how often it is closed. */
    private class CountingStream(private val adapter: VirtualElm327) : ByteStream by adapter {
        var closes = 0
            private set

        override suspend fun close() {
            closes++
            adapter.close()
        }
    }

    /** An adapter that has not woken up: it answers every command with `?`. */
    private class RejectingStream : ByteStream {
        private val channel = Channel<ByteArray>(Channel.UNLIMITED)
        var closes = 0
            private set

        override val incoming: Flow<ByteArray> = channel.receiveAsFlow()

        override suspend fun write(bytes: ByteArray) {
            channel.send("?\r\r>".encodeToByteArray())
        }

        override suspend fun close() {
            closes++
            channel.close()
        }
    }

    /** Hands out the simulator's streams wrapped in [CountingStream], and keeps them. */
    private class CountingTransport(private val inner: SimulatedTransport) : Transport {
        val streams = ArrayList<CountingStream>()
        override val kind = inner.kind

        override suspend fun open(): ByteStream = CountingStream(inner.open()).also { streams += it }
    }

    private data class Timed(val atMs: Long, val state: SessionState)

    private class Rig(
        val vehicle: VirtualVehicle,
        val simulated: SimulatedTransport,
        val transport: CountingTransport,
        val session: VehicleSession,
        val states: List<Timed>,
    ) {
        val kinds: List<SessionState> get() = states.map { it.state }
    }

    private fun TestScope.rig(
        failFirstOpens: Int = 0,
        config: VehicleSession.Config = fastDpf(),
        vehicle: VirtualVehicle = simulation.buildVehicle(),
    ): Rig {
        val simulated = SimulatedTransport(vehicle = { vehicle }, config = adapterConfig, failFirstOpens = failFirstOpens)
        val transport = CountingTransport(simulated)
        val session = VehicleSession(transport, initScript, catalog, backgroundScope, testTimeSource, config)
        val states = ArrayList<Timed>()
        // Unconfined, so that states set back to back without a suspension between them are all seen and not conflated.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.state.collect { states += Timed(currentTime, it) } }
        return Rig(vehicle, simulated, transport, session, states)
    }

    /** The DPF is the story of the scenario, so it is polled faster than the rest. */
    private fun fastDpf(base: VehicleSession.Config = VehicleSession.Config()): VehicleSession.Config = base.copy(
        // The timelines below count commands and round trips, so the warm-up and the pause after ATZ (tested on their own) are left out.
        driver = if (base.driver == Elm327Driver.Config()) Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO) else base.driver,
        pollPeriod = { parameter -> if (parameter.signals.any { it.definition.id.startsWith("dpf.") }) 500.milliseconds else 1.seconds },
    )

    /** Lets virtual time pass. (advanceUntilIdle() would not: sessions live in backgroundScope, which it ignores.) */
    private fun TestScope.run(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    /** The request in a command the driver sent, without the "stop after N replies" digit: `01051` is `0105`. */
    private fun requestOf(command: String): String? =
        when {
            command.isEmpty() || command.startsWith("AT") -> null
            command.length % 2 == 1 -> command.dropLast(1)
            else -> command
        }

    private fun VirtualElm327.requests(): List<String> = received.mapNotNull(::requestOf)

    private fun TelemetrySnapshot.soot(): Double? = value("dpf.soot_pct")

    /** After stopping, nothing may reach the adapter any more, however much virtual time passes. */
    private fun TestScope.assertSilent(adapter: VirtualElm327) {
        val sent = adapter.received.size
        advanceUntilIdle()
        run(60.seconds)
        assertEquals(sent, adapter.received.size, "the adapter received traffic after stop()")
    }

    @Test
    fun happyPathGoesFromConnectingThroughProbingToPollingAndPublishesTelemetry() = runTest {
        val rig = rig()
        rig.session.start()
        run(20.seconds)

        val states = rig.kinds
        assertEquals(
            listOf(
                SessionState.Idle,
                SessionState.Connecting(1),
                SessionState.Initialising,
                SessionState.Probing(ProbeStage.ADAPTER),
                SessionState.Probing(ProbeStage.ECU_SWEEP),
                SessionState.Probing(ProbeStage.LIVE_DATA_SUPPORT),
                SessionState.Probing(ProbeStage.VEHICLE_INFO),
                SessionState.Probing(ProbeStage.BENCHMARK),
                SessionState.Probing(ProbeStage.DERIVED_FACTS),
            ),
            states.take(9),
        )
        val polling = assertIs<SessionState.Polling>(states[9])
        assertEquals(10, states.size, "no further state changes while polling normally")
        assertEquals(listOf("7E0", "7E1", "7E4"), polling.report.ecus.map { it.requestId })
        assertTrue(polling.coverage.confirmed.isNotEmpty())

        val telemetry = rig.session.telemetry.value
        assertEquals(799.0, telemetry.value("engine.rpm")!!, 0.0)
        assertEquals(89.02, telemetry.soot()!!, 0.005)
        // The AdBlue level comes from the reductant controller, not from the engine ECU.
        assertEquals(95.686, telemetry.value("def.level_pct", ecu = "rdcm")!!, 0.005)
        assertNull(telemetry.value("def.level_pct", ecu = "ecm"))
        assertEquals(14.1, telemetry.value("electrical.module_voltage")!!, 0.005)
    }

    @Test
    fun onlyPidsTheProbeConfirmedAreRequestedAndNeverTheFunctionalAddress() = runTest {
        val rig = rig()
        rig.session.start()
        run(20.seconds)

        val adapter = rig.simulated.current!!
        val requests = adapter.requests()

        // The catalog defines these standard PIDs, but no ECU of the simulated car reports them.
        for (unsupported in listOf("0104", "010F", "0111", "0133", "015C")) {
            assertTrue(unsupported !in requests, "$unsupported was requested although no ECU supports it")
        }
        assertTrue("ATSH7DF" !in adapter.received)

        // What is polled (support maps, which end in 00/20/40..., belong to the probe): exactly the confirmed parameters.
        val polled = requests.filter { it.startsWith("01") && it.substring(2).toInt(16) % 0x20 != 0 }.toSet()
        assertEquals(
            setOf("0105", "010B", "010C", "010D", "0110", "012F", "0131", "0142", "0146", "0178", "018B", "019D", "019B"),
            polled,
        )
        assertTrue(requests.all { it.startsWith("01") || it.startsWith("09") }, "only services 01 and 09 may be sent: $requests")
    }

    @Test
    fun aGenericParameterIsAskedOfThePrimaryEcuOnlyEvenIfAnotherOneAnswersToo() = runTest {
        val rig = rig()
        rig.session.start()
        run(20.seconds)
        // Both the engine ECU and the reductant controller report engine speed; only the first ECU of the catalog is asked.
        assertEquals(799.0, rig.session.telemetry.value.value("engine.rpm", ecu = "ecm"))
        assertNull(rig.session.telemetry.value.value("engine.rpm", ecu = "rdcm"))
    }

    @Test
    fun aDifferentPrimaryEcuMovesTheGenericParametersToIt() = runTest {
        val rig = rig(config = fastDpf(VehicleSession.Config(primaryEcu = "rdcm")))
        rig.session.start()
        run(20.seconds)
        val telemetry = rig.session.telemetry.value
        assertEquals(799.0, telemetry.value("engine.rpm", ecu = "rdcm"))
        assertNull(telemetry.value("engine.rpm", ecu = "ecm"))
    }

    @Test
    fun theFirstTwoOpensFailAndTheSessionRetriesWith1sThen2sBackoff() = runTest {
        val rig = rig(failFirstOpens = 2)
        rig.session.start()
        run(20.seconds)

        val timeline = rig.states.take(8)
        assertEquals(Timed(0, SessionState.Idle), timeline[0])
        assertEquals(Timed(0, SessionState.Connecting(1)), timeline[1])
        val first = assertIs<SessionState.Reconnecting>(timeline[2].state)
        assertEquals(0L, timeline[2].atMs)
        assertEquals(2, first.attempt)
        assertEquals(1.seconds, first.retryIn)
        assertTrue(first.cause.startsWith("could not open the link: Simulated open failure"), first.cause)
        assertEquals(Timed(1_000, SessionState.Connecting(2)), timeline[3])
        val second = assertIs<SessionState.Reconnecting>(timeline[4].state)
        assertEquals(1_000L, timeline[4].atMs)
        assertEquals(3, second.attempt)
        assertEquals(2.seconds, second.retryIn)
        assertEquals(Timed(3_000, SessionState.Connecting(3)), timeline[5])
        assertEquals(Timed(3_000, SessionState.Initialising), timeline[6])
        // The init script is 9 commands at 45 ms each.
        assertEquals(3_000L + 9 * roundTrip.inWholeMilliseconds, timeline[7].atMs)
        assertIs<SessionState.Probing>(timeline[7].state)

        assertIs<SessionState.Polling>(rig.session.state.value)
        assertEquals(3, rig.simulated.opens)
        assertEquals(799.0, rig.session.telemetry.value.value("engine.rpm"))
    }

    @Test
    fun aTransportThatAlwaysFailsEndsInFailedAfterMaxConnectAttempts() = runTest {
        val rig = rig(failFirstOpens = Int.MAX_VALUE, config = fastDpf(VehicleSession.Config(maxConnectAttempts = 3)))
        rig.session.start()
        run(60.seconds)

        assertIs<SessionState.Failed>(rig.session.state.value)
        assertEquals(3, rig.simulated.opens)
        assertEquals(3_000L, rig.states.last().atMs)
        run(600.seconds)
        assertEquals(3, rig.simulated.opens, "no more attempts once failed")
    }

    @Test
    fun anAdapterThatAnswersQuestionMarkToAnEssentialInitCommandIsAConnectionFailureAndIsRetried() = runTest {
        // "ATCRA7E8" (a receive filter) is not a command the simulated adapter knows, so it answers "?" like a real ELM327 would; here it is declared essential.
        val simulated = SimulatedTransport(vehicle = { simulation.buildVehicle() }, config = adapterConfig)
        val transport = CountingTransport(simulated)
        val session =
            VehicleSession(
                transport,
                initScript + "ATCRA7E8",
                catalog,
                backgroundScope,
                testTimeSource,
                fastDpf(
                    VehicleSession.Config(
                        maxConnectAttempts = 2,
                        driver = Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO, essentialCommands = listOf("ATCRA")),
                    ),
                ),
            )
        val states = ArrayList<SessionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.state.collect { states += it } }

        session.start()
        run(30.seconds)

        val reconnecting = states.filterIsInstance<SessionState.Reconnecting>().single()
        assertTrue(reconnecting.cause.contains("rejected 'ATCRA7E8'"), reconnecting.cause)
        assertEquals(listOf(1, 1), transport.streams.map { it.closes })
        assertEquals(2, simulated.opens)
        assertIs<SessionState.Failed>(session.state.value)
    }

    @Test
    fun anAdapterThatAnswersQuestionMarkToAnOrdinaryInitCommandIsUsedAndTheCommandIsReportedAsUnsupported() = runTest {
        val simulated = SimulatedTransport(vehicle = { simulation.buildVehicle() }, config = adapterConfig)
        val session = VehicleSession(simulated, initScript + "ATCRA7E8", catalog, backgroundScope, testTimeSource, fastDpf())
        session.start()
        run(20.seconds)

        val polling = assertIs<SessionState.Polling>(session.state.value)
        assertEquals(listOf("ATCRA7E8"), polling.report.adapter.unsupportedCommands)
        assertEquals(799.0, session.telemetry.value.value("engine.rpm"))
    }

    @Test
    fun aRejectedInitScriptOnTheFirstOpenIsRetriedAndTheNextOpenReachesPolling() = runTest {
        val simulated = SimulatedTransport(vehicle = { simulation.buildVehicle() }, config = adapterConfig)
        val rejecting = RejectingStream()
        var opens = 0
        val transport = object : Transport {
            override val kind = simulated.kind

            override suspend fun open(): ByteStream = if (++opens == 1) rejecting else simulated.open()
        }
        val session = VehicleSession(transport, initScript, catalog, backgroundScope, testTimeSource, fastDpf())
        session.start()
        run(20.seconds)

        assertIs<SessionState.Polling>(session.state.value)
        assertEquals(2, opens)
        assertEquals(1, rejecting.closes, "the rejected adapter's stream is closed")
        assertEquals(799.0, session.telemetry.value.value("engine.rpm"))
    }

    @Test
    fun unpluggingTheAdapterMidPollingIsNoticedWithinOneRoundTripAndTheSessionReconnectsWithoutProbing() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)
        val polling = assertIs<SessionState.Polling>(rig.session.state.value)
        val before = rig.simulated.current!!
        assertEquals(1, before.requests().count { it == "0902" }, "only the engine ECU lists a VIN, so the first probe reads it once")
        val lastReadingBefore = rig.session.telemetry.value.reading("engine.rpm")!!.at

        val unplugged = currentTime
        before.disconnect()
        run(roundTrip)

        val lost = rig.states.first { it.state is SessionState.Reconnecting }
        assertTrue(lost.atMs - unplugged <= roundTrip.inWholeMilliseconds, "noticed after ${lost.atMs - unplugged} ms")
        val reconnecting = lost.state as SessionState.Reconnecting
        // A connection that had been polling starts a fresh outage: the next attempt is number 1 again, after the shortest wait.
        assertEquals(1, reconnecting.attempt)
        assertEquals(1.seconds, reconnecting.retryIn)
        assertTrue(reconnecting.cause.contains("closed"), reconnecting.cause)
        assertEquals(1, rig.transport.streams[0].closes, "the old stream is closed as soon as the link is found to be gone")

        run(20.seconds)

        val again = assertIs<SessionState.Polling>(rig.session.state.value)
        assertEquals(2, rig.simulated.opens)
        val after = rig.simulated.current!!
        assertNotSame(before, after)
        assertSame(polling.report, again.report, "the report of the first probe is reused")
        assertEquals(0, after.requests().count { it == "0902" }, "no vehicle information is read again")
        assertEquals(0, after.requests().count { it == "0100" || it == "0120" }, "no ECU sweep or support map is read again")
        val afterOutage = rig.kinds.dropWhile { it !is SessionState.Reconnecting }
        assertEquals(listOf(SessionState.Connecting(1), SessionState.Initialising), afterOutage.drop(1).take(2))
        assertTrue(afterOutage.none { it is SessionState.Probing })
        assertTrue(rig.session.telemetry.value.reading("engine.rpm")!!.at > lastReadingBefore, "readings flow again")
        assertEquals(1, rig.transport.streams[0].closes)
    }

    @Test
    fun withReprobeOnReconnectTheVehicleIsProbedAgainOnTheNewAdapter() = runTest {
        val rig = rig(config = fastDpf(VehicleSession.Config(reprobeOnReconnect = true)))
        rig.session.start()
        run(10.seconds)
        val polling = assertIs<SessionState.Polling>(rig.session.state.value)

        rig.simulated.current!!.disconnect()
        run(20.seconds)

        val again = assertIs<SessionState.Polling>(rig.session.state.value)
        assertEquals(1, rig.simulated.current!!.requests().count { it == "0902" })
        assertNotSame(polling.report, again.report)
        assertEquals(polling.report.ecus.map { it.requestId }, again.report.ecus.map { it.requestId })
        assertEquals(2, rig.kinds.count { it == SessionState.Probing(ProbeStage.ECU_SWEEP) })
    }

    @Test
    fun aLinkThatFailsWithAnErrorInsteadOfClosingIsAlsoRecovered() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)
        rig.simulated.current!!.disconnect(IllegalStateException("gatt status 133"))
        runCurrent()
        val reconnecting = assertIs<SessionState.Reconnecting>(rig.session.state.value)
        assertTrue(reconnecting.cause.contains("gatt status 133"), reconnecting.cause)
        run(20.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)
    }

    @Test
    fun theBackoffStartsAgainAtOneSecondAfterEveryConnectionThatReachedPolling() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)
        repeat(2) {
            rig.simulated.current!!.disconnect()
            run(20.seconds)
        }
        val waits = rig.kinds.filterIsInstance<SessionState.Reconnecting>().map { it.retryIn }
        assertEquals(listOf(1.seconds, 1.seconds), waits)
        assertEquals(3, rig.simulated.opens)
    }

    @Test
    fun stopDuringPollingEndsStoppedClosesTheStreamOnceAndLeavesTheAdapterAlone() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)

        rig.session.stop()

        assertEquals(SessionState.Stopped, rig.session.state.value)
        assertEquals(listOf(1), rig.transport.streams.map { it.closes })
        assertSilent(rig.simulated.current!!)
        assertEquals(1, rig.simulated.opens)
        assertEquals(SessionState.Stopped, rig.states.last().state)
    }

    @Test
    fun stopDuringTheBackoffWaitDoesNotReconnect() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)
        rig.simulated.current!!.disconnect()
        run(300.milliseconds)
        assertIs<SessionState.Reconnecting>(rig.session.state.value)

        rig.session.stop()

        assertEquals(SessionState.Stopped, rig.session.state.value)
        assertSilent(rig.simulated.current!!)
        assertEquals(1, rig.simulated.opens)
        assertEquals(listOf(1), rig.transport.streams.map { it.closes })
    }

    @Test
    fun stopDuringProbingStopsTheProbeAndClosesTheStreamOnce() = runTest {
        val rig = rig()
        rig.session.start()
        // The init script is 9 commands of 45 ms and the ECU sweep another 8, so 600 ms is inside the probe.
        run(600.milliseconds)
        assertIs<SessionState.Probing>(rig.session.state.value)

        rig.session.stop()

        assertEquals(SessionState.Stopped, rig.session.state.value)
        assertEquals(listOf(1), rig.transport.streams.map { it.closes })
        assertSilent(rig.simulated.current!!)
        assertTrue(rig.kinds.none { it is SessionState.Polling })
    }

    @Test
    fun readingsAreStampedWithTimeSinceSessionStartAndTheScenarioIsObservedThroughTelemetry() = runTest {
        val vehicle = simulation.buildVehicle()
        val rig = rig(vehicle = vehicle)
        val snapshots = ArrayList<Pair<Long, TelemetrySnapshot>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.session.telemetry.collect { snapshots += currentTime to it } }
        // The scenario's clock starts together with the session's.
        backgroundScope.launch { simulation.play(vehicle) }

        rig.session.start()
        run(30.seconds)

        val withSoot = snapshots.filter { it.second.soot() != null }
        assertTrue(withSoot.isNotEmpty())
        assertEquals(89.02, withSoot.first().second.soot()!!, 0.005)

        // Each snapshot's newest reading is stamped with the virtual time at which it was published, and time only moves forward.
        for ((time, snapshot) in snapshots.drop(1)) {
            assertEquals(time, snapshot.readings.maxOf { it.at }.inWholeMilliseconds)
        }
        val newest = snapshots.map { it.second.readings.maxOfOrNull { reading -> reading.at } ?: Duration.ZERO }
        assertEquals(newest.sorted(), newest)
        assertTrue(newest.last() > newest.first())

        // The timeline sets soot to 100 % and the regeneration status to 2 at 8 s; the session sees both soon after.
        val full = snapshots.first { it.second.soot()?.let { soot -> soot >= 99.99 } == true }.second.reading("dpf.soot_pct")!!
        assertTrue(full.at in 8.seconds..9.seconds, "soot reached 100 % at ${full.at}")
        val regen = snapshots.first { it.second.value("dpf.regen_status") == 2.0 }.second.reading("dpf.regen_status")!!
        assertTrue(regen.at in 8.seconds..9.seconds, "regeneration seen at ${regen.at}")
        // And the story ends as in the capture: regeneration over, soot back down.
        assertEquals(0.0, rig.session.telemetry.value.value("dpf.regen_status"))
        assertEquals(17.65, rig.session.telemetry.value.soot()!!, 0.005)
    }

    @Test
    fun pollResultsAreSharedWithoutReplay() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)
        val late = ArrayList<PollResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.session.pollResults.collect { late += it } }
        runCurrent()
        assertTrue(late.isEmpty(), "a collector that subscribes late gets nothing from the past")

        run(5.seconds)

        assertTrue(late.size > 5)
        assertTrue(late.all { it.answered })
    }
}
