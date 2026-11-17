package knightlink.core.sim

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.adapter.Elm327Driver
import knightlink.core.common.SessionEvent
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.runtime.SessionState
import knightlink.core.runtime.VehicleSession
import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import knightlink.core.telemetry.TelemetrySnapshot
import knightlink.core.transport.ByteStream
import knightlink.core.transport.Transport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the session copes when the car or the adapter misbehaves mid-session: a car that falls silent, a car that is asleep, a
 * vehicle bus that cannot be reached, an adapter that resets itself. Whole runtime against the simulator, on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRecoverySimulationTest {
    private val root = File(System.getProperty("knightlink.repoRoot"))
    private val simulation = SimulationFile.parse(File(root, "fixtures/sim/harrier-dpf-regen.json").readText())
    private val adapterDefinition = AdapterDefinition.parse(File(root, "definitions/adapters/vgate-icar-pro-2s.json").readText())
    private val catalog = catalogFor(simulation.definition)

    private fun definition(id: String): DefinitionFile = DefinitionJson.parse(File(root, "definitions/$id.json").readText())

    private fun catalogFor(id: String): SignalCatalog {
        val top = definition(id)
        return SignalCatalog.of(top.extends.map(::definition) + top)
    }

    private class CountingStream(private val adapter: VirtualElm327) : ByteStream by adapter {
        var closes = 0
            private set

        override suspend fun close() {
            closes++
            adapter.close()
        }
    }

    private class CountingTransport(private val inner: SimulatedTransport) : Transport {
        val streams = ArrayList<CountingStream>()
        override val kind = inner.kind

        override suspend fun open(): ByteStream = CountingStream(inner.open()).also { streams += it }
    }

    private class Collected : SessionEventSink {
        val events = ArrayList<SessionEvent>()

        override suspend fun emit(event: SessionEvent) {
            events += event
        }

        fun of(name: String): List<String> = events.filter { it.name == name }.map { it.detail }
    }

    private class Rig(
        val vehicle: VirtualVehicle,
        val simulated: SimulatedTransport,
        val transport: CountingTransport,
        val session: VehicleSession,
        val states: List<SessionState>,
        val events: Collected,
    ) {
        val adapter: VirtualElm327 get() = simulated.current!!

        inline fun <reified T : SessionState> statesOf(): List<T> = states.filterIsInstance<T>()
    }

    private fun TestScope.rig(
        awake: Boolean = true,
        base: VehicleSession.Config = VehicleSession.Config(),
        withScripts: Boolean = false,
        vehicle: VirtualVehicle = simulation.buildVehicle(),
    ): Rig {
        vehicle.awake = awake
        val simulated = SimulatedTransport(vehicle = { vehicle }, config = VirtualElm327.Config(responseDelay = 45.milliseconds))
        val transport = CountingTransport(simulated)
        val config = base.copy(
            driver = if (withScripts) adapterDefinition.driverConfig() else Elm327Driver.Config(),
            probeScript = if (withScripts) adapterDefinition.probeScript else emptyList(),
            pollScript = if (withScripts) adapterDefinition.pollScript else emptyList(),
            pollPeriod = { 500.milliseconds },
        )
        val events = Collected()
        val session = VehicleSession(transport, adapterDefinition.initScript, catalog, backgroundScope, testTimeSource, config, events)
        val states = ArrayList<SessionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.state.collect { states += it } }
        return Rig(vehicle, simulated, transport, session, states, events)
    }

    private fun TestScope.run(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private fun TelemetrySnapshot.rpmAt(): Duration? = readings.firstOrNull { it.reading.signal.id == "engine.rpm" }?.at

    // Silence watchdog ------------------------------------------------------------------------------------------

    @Test
    fun aCarThatFallsSilentGetsTheAdapterInitialisedAgainTwiceAndThenTheLinkIsReopened() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)
        val silentSince = currentTime

        rig.vehicle.awake = false
        run(5.seconds)
        // Nothing has answered for the window yet; the poll loop only just noticed.
        run(1.seconds)
        val first = assertIs<SessionState.Recovering>(rig.session.state.value)
        assertEquals(1, first.attempt)
        assertTrue(first.reason.contains("nothing answered for 5s"), first.reason)
        run(1.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)
        assertTrue(currentTime - silentSince in 5_000..8_000)

        run(10.seconds)
        val second = rig.statesOf<SessionState.Recovering>().last()
        assertEquals(2, second.attempt)
        assertEquals(1, rig.simulated.opens, "the link is kept for both re-initialisations")

        run(10.seconds)
        val reconnecting = rig.statesOf<SessionState.Reconnecting>().first()
        assertTrue(reconnecting.cause.contains("no answer after 2 re-initialisations"), reconnecting.cause)
        // The sequence was: Polling, Recovering(1), Polling, Recovering(2), Polling, then the reconnect.
        val sequence = rig.states.dropWhile { it !is SessionState.Polling }.map { it::class.simpleName }
        assertEquals(
            listOf("Polling", "Recovering", "Polling", "Recovering", "Polling", "Reconnecting"),
            sequence.take(6),
        )
        assertEquals(1, rig.transport.streams.first().closes)
    }

    @Test
    fun afterTheReconnectTheCarIsProbedAfreshAndASleepingCarKeepsTheLinkInWaitingForIgnition() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)
        rig.vehicle.awake = false
        run(40.seconds)

        assertEquals(2, rig.simulated.opens, "one reconnect after the silence")
        assertTrue(rig.statesOf<SessionState.WaitingForIgnition>().isNotEmpty())
        val link = rig.transport.streams.last()
        assertEquals(0, link.closes, "waiting does not close the link")

        // The ignition is switched on: the next probe finds the ECUs and polling resumes on the same link.
        rig.vehicle.awake = true
        run(15.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)
        assertEquals(2, rig.simulated.opens)
        assertEquals(0, link.closes)
        assertEquals(799.0, rig.session.telemetry.value.value("engine.rpm"))
    }

    @Test
    fun aShortSilenceIsRecoveredByOneReinitialisationAndNothingIsReopened() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)

        rig.vehicle.awake = false
        run(6.seconds)
        assertIs<SessionState.Recovering>(rig.session.state.value)
        rig.vehicle.awake = true
        run(10.seconds)

        assertIs<SessionState.Polling>(rig.session.state.value)
        assertEquals(1, rig.simulated.opens)
        assertTrue(rig.statesOf<SessionState.Reconnecting>().isEmpty())
        // The outage is over once something answers, so a second silence earns two fresh re-initialisations.
        val before = rig.statesOf<SessionState.Recovering>().size
        rig.vehicle.awake = false
        run(7.seconds)
        assertEquals(before + 1, rig.statesOf<SessionState.Recovering>().size)
        assertEquals(1, rig.statesOf<SessionState.Recovering>().last().attempt)
    }

    @Test
    fun aCarWithNothingToPollIsNotMistakenForASilentOne() = runTest {
        // One ECU that answers, but supports only a parameter the vehicle definition does not know: there is nothing to ask for.
        val ecu = VirtualEcu(0x7E0, 0x7E8).also { it.set("01C5", "00") }
        val rig = rig(vehicle = VirtualVehicle(listOf(ecu)))
        rig.session.start()
        run(60.seconds)

        val polling = assertIs<SessionState.Polling>(rig.session.state.value)
        assertTrue(polling.coverage.confirmed.isEmpty())
        assertTrue(rig.statesOf<SessionState.Recovering>().isEmpty(), "no silence to recover from when nothing is asked")
        assertEquals(1, rig.simulated.opens)
    }

    // Ignition off ------------------------------------------------------------------------------------------------

    @Test
    fun anEmptyProbeKeepsTheLinkWaitsForTheIgnitionAndReprobesUntilTheCarAnswers() = runTest {
        val rig = rig(awake = false)
        rig.session.start()
        run(20.seconds)

        val waiting = rig.statesOf<SessionState.WaitingForIgnition>()
        assertTrue(waiting.size >= 3, "$waiting")
        assertEquals((1..waiting.size).toList(), waiting.map { it.probes })
        assertEquals(null, waiting.first().busFault)
        assertEquals(1, rig.simulated.opens)
        assertEquals(0, rig.transport.streams.single().closes)
        assertTrue(rig.statesOf<SessionState.Reconnecting>().isEmpty())

        rig.vehicle.awake = true
        run(15.seconds)

        val polling = assertIs<SessionState.Polling>(rig.session.state.value)
        assertEquals(listOf("7E0", "7E1", "7E4"), polling.report.ecus.map { it.requestId })
        assertEquals(1, rig.simulated.opens, "the same link all along")
        assertEquals(799.0, rig.session.telemetry.value.value("engine.rpm"))
    }

    @Test
    fun theProbeIsRepeatedEveryFourSeconds() = runTest {
        val rig = rig(awake = false)
        rig.session.start()
        run(30.seconds)
        val probes = rig.adapter.received.count { it == "ATI" }
        // The sweep of eight addresses takes about 0.4 s plus the adapter queries; the pause is 4 s.
        assertTrue(probes in 5..8, "probes in 30 s: $probes")
    }

    // Bus faults ----------------------------------------------------------------------------------------------------

    @Test
    fun aVehicleBusThatCannotBeReachedIsNamedInTheStateAndTheLinkIsKept() = runTest {
        val rig = rig()
        rig.session.start()
        run(200.milliseconds)
        rig.adapter.busFault = "CAN ERROR"
        run(12.seconds)

        val waiting = rig.statesOf<SessionState.WaitingForIgnition>()
        assertTrue(waiting.isNotEmpty())
        assertEquals("CAN_ERROR", waiting.first().busFault)
        assertEquals(1, rig.simulated.opens)
        assertTrue(
            rig.events.of(SessionEvents.PROBE_RESULT).first().contains("the adapter said CAN_ERROR"),
            rig.events.of(SessionEvents.PROBE_RESULT).toString(),
        )

        rig.adapter.busFault = null
        run(15.seconds)
        assertIs<SessionState.Polling>(rig.session.state.value)
    }

    @Test
    fun aBusFaultWhilePollingIsNamedInTheReasonOfTheRecovery() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)
        rig.adapter.busFault = "UNABLE TO CONNECT"
        run(8.seconds)

        val recovering = rig.statesOf<SessionState.Recovering>().first()
        assertTrue(recovering.reason.contains("vehicle bus not reachable (UNABLE TO CONNECT)"), recovering.reason)
        assertTrue(rig.adapter.received.isNotEmpty())
    }

    // Adapter reset ---------------------------------------------------------------------------------------------------

    @Test
    fun anAdapterThatResetsMidPollingIsInitialisedAgainOnTheSameLinkAndPollingContinues() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)
        val before = rig.session.telemetry.value.rpmAt()!!
        val initsBefore = rig.adapter.received.count { it == "ATZ" }

        rig.adapter.powerDip()
        run(5.seconds)

        assertIs<SessionState.Polling>(rig.session.state.value)
        assertEquals(1, rig.simulated.opens)
        assertTrue(rig.statesOf<SessionState.Reconnecting>().isEmpty())
        assertTrue(rig.statesOf<SessionState.Recovering>().isEmpty(), "the driver fixed it before the silence window")
        assertEquals(initsBefore + 1, rig.adapter.received.count { it == "ATZ" })
        assertTrue(rig.session.telemetry.value.rpmAt()!! > before, "values are fresh again")
        assertTrue(rig.events.of(SessionEvents.ADAPTER_RESET).isNotEmpty())
        assertTrue(rig.events.of(SessionEvents.REINIT).any { it.contains("adapter reset") }, rig.events.of(SessionEvents.REINIT).toString())
    }

    @Test
    fun anAdapterThatLosesItsSettingsWithoutABannerIsRecognisedByItsNextReply() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)

        rig.adapter.powerDipBeforeNextCommand()
        run(5.seconds)

        assertIs<SessionState.Polling>(rig.session.state.value)
        assertEquals(1, rig.events.of(SessionEvents.ADAPTER_RESET).size, rig.events.of(SessionEvents.ADAPTER_RESET).toString())
        assertEquals(1, rig.simulated.opens)
        val after = rig.adapter.received.dropWhile { it != "ATZ" }.dropWhile { it != "ATZ" }
        assertTrue(after.isNotEmpty(), "the script ran again after the reset")
    }

    // Probe timing ------------------------------------------------------------------------------------------------------

    @Test
    fun theProbeRunsWithTheLongerTimeoutAndPollingAndEveryReinitialisationWithTheDefinitionsOwn() = runTest {
        val rig = rig(withScripts = true)
        rig.session.start()
        run(15.seconds)
        val sent = rig.adapter.received.toList()

        val probeTimeout = sent.indexOf("ATST64")
        val firstInfo = sent.indexOf("ATI")
        val pollTimeout = sent.lastIndexOf("ATST32")
        assertTrue(probeTimeout in 0 until firstInfo, "the longer timeout comes before the probe: $sent")
        assertTrue(pollTimeout > probeTimeout)
        // The poll timeout was set after the probe: the last probe request (the benchmark's 01 00 round trips) precedes it.
        assertTrue(sent.take(pollTimeout).any { it.startsWith("0100") }, "the probe ran before the timeout was switched back")

        // A reset: the script that runs again is the polling one.
        rig.adapter.powerDip()
        run(5.seconds)
        val afterReset = rig.adapter.received.drop(sent.size)
        assertFalse("ATST64" in afterReset, "a re-initialisation must not bring back the probe's timeout: $afterReset")
        assertTrue("ATST32" in afterReset)
    }

    // Events -------------------------------------------------------------------------------------------------------------

    @Test
    fun theSessionTellsItsStoryInOrderAndNeverMentionsTheVin() = runTest {
        val rig = rig()
        rig.session.start()
        run(15.seconds)
        rig.session.stop()

        val names = rig.events.events.map { it.name }
        fun first(name: String) = names.indexOf(name).also { assertTrue(it >= 0, "no $name event in $names") }
        assertTrue(first(SessionEvents.CONNECT_ATTEMPT) < first(SessionEvents.LINK_UP))
        assertTrue(first(SessionEvents.LINK_UP) < first(SessionEvents.INIT_START))
        assertTrue(first(SessionEvents.INIT_START) < first(SessionEvents.INIT_END))
        assertTrue(first(SessionEvents.INIT_END) < first(SessionEvents.PROBE_RESULT))
        assertEquals(SessionEvents.END, names.last())
        assertEquals("the session was stopped", rig.events.events.last().detail)

        val probe = rig.events.of(SessionEvents.PROBE_RESULT).single()
        assertTrue(probe.startsWith("3 ECUs: 7E0"), probe)
        val states = rig.events.of(SessionEvents.STATE)
        assertTrue(states.any { it.startsWith("Polling, 3 ECUs") }, states.toString())

        val vin = simulation.ecus.firstNotNullOf { it.vin }
        val text = rig.events.events.joinToString("\n")
        assertFalse(vin in text, "the VIN must not appear in the events")
        assertFalse(vin.drop(3) in text)
    }

    @Test
    fun reconnectsAndTheirReasonsAreEvents() = runTest {
        val rig = rig()
        rig.session.start()
        run(10.seconds)
        rig.adapter.disconnect()
        run(5.seconds)

        val reconnect = rig.events.of(SessionEvents.RECONNECT).first()
        assertTrue(reconnect.contains("because: "), reconnect)
        assertEquals(2, rig.events.of(SessionEvents.CONNECT_ATTEMPT).size)
        assertEquals(2, rig.events.of(SessionEvents.LINK_UP).size)
    }

    @Test
    fun aSinkThatThrowsDoesNotEndTheSession() = runTest {
        val simulated = SimulatedTransport(vehicle = { simulation.buildVehicle() }, config = VirtualElm327.Config(responseDelay = 45.milliseconds))
        val session = VehicleSession(
            simulated,
            adapterDefinition.initScript,
            catalog,
            backgroundScope,
            testTimeSource,
            events = SessionEventSink { throw IllegalStateException("disk full") },
        )
        session.start()
        run(15.seconds)
        assertIs<SessionState.Polling>(session.state.value)
    }
}
