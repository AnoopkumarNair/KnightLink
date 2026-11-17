package knightlink.app.session

import knightlink.app.data.AppConfig
import knightlink.app.data.DefinitionStore
import knightlink.app.data.RepoAssetSource
import knightlink.core.recorder.EventKind
import knightlink.core.recorder.InMemoryRecordSink
import knightlink.core.recorder.RecordSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The session host on virtual time, with the demo transport: the real stack (driver, probe, scheduler, session, recorder)
 * running against the simulated vehicle of the bundled scenario, as it does on the phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerTest {
    private val assets = RepoAssetSource()
    private val store = DefinitionStore(assets)
    private val config = AppConfig.load(assets)
    private val demo = ConnectionTarget.Demo(config.demoScenario)

    /** Keeps every sink it hands out, in memory. */
    private class MemorySinks : RecordSinkFactory {
        val sinks = ArrayList<InMemoryRecordSink>()
        var finished = 0

        override fun newSink(): RecordSink = InMemoryRecordSink().also { sinks += it }

        override fun finishCurrent() {
            finished++
        }
    }

    private class Rig(val controller: SessionController, val factory: DemoTransportFactory, val sinks: MemorySinks)

    private fun TestScope.rig(transports: DemoTransportFactory = DemoTransportFactory(store::simulation)): Rig {
        val sinks = MemorySinks()
        val controller = SessionController(backgroundScope, { config }, store, transports, sinks, testTimeSource)
        return Rig(controller, transports, sinks)
    }

    /** Advances virtual time until the connection is live, or fails the test after [limitSeconds]. */
    private fun TestScope.awaitLive(controller: SessionController, limitSeconds: Int = 60) {
        val limit = currentTime + limitSeconds * 1000L
        while (controller.uiState.value.connection != ConnectionStatus.Live && currentTime < limit) advanceTimeBy(250.milliseconds)
        assertEquals(ConnectionStatus.Live, controller.uiState.value.connection)
    }

    private fun UiState.value(signalId: String): LiveValue? = liveValues.firstOrNull { it.signalId == signalId }

    @Test
    fun startsIdle() = runTest {
        assertEquals(UiState.IDLE, rig().controller.uiState.value)
    }

    @Test
    fun theDemoReachesLiveWithTheVehicleAndItsValues() = runTest {
        val rig = rig()
        assertTrue(rig.controller.connect(demo))
        // Feedback is immediate, before anything has been loaded.
        assertEquals(ConnectionStatus.Connecting(1), rig.controller.uiState.value.connection)

        awaitLive(rig.controller)
        advanceTimeBy(5.seconds)

        val state = rig.controller.uiState.value
        assertNotNull(state.value("engine.rpm"), "engine speed: ${state.liveValues.map { it.signalId }}")
        assertNotNull(state.value("dpf.soot_pct"))
        assertTrue(state.liveValues.none { it.stale }, "everything is being refreshed")
        assertEquals(state.liveValues.map { it.signalId }, state.liveValues.map { it.signalId }.sorted())

        val vehicle = assertNotNull(state.vehicle)
        assertTrue(vehicle.ecus.isNotEmpty())
        assertTrue(vehicle.hasDpfData)
        assertTrue(vehicle.confirmed.isNotEmpty())
        assertNotNull(vehicle.requestsPerSecond)
        assertNotNull(vehicle.adapterBanner)
    }

    @Test
    fun theVehicleSummaryShowsOnlyTheRedactedVin() = runTest {
        val rig = rig()
        rig.controller.connect(demo)
        awaitLive(rig.controller)

        val vehicle = assertNotNull(rig.controller.uiState.value.vehicle)
        val vin = assertNotNull(vehicle.vinRedacted)
        assertEquals(17, vin.length)
        assertTrue(vin.drop(3).all { it == '*' }, vin)
        // The scenario's VIN is in the recording, which stays private; it must not be anywhere in what the screen shows.
        val scenarioVin = store.simulation(config.demoScenario).ecus.firstNotNullOf { it.vin }
        assertFalse(rig.controller.uiState.value.toString().contains(scenarioVin.drop(3)))
    }

    @Test
    fun theLoopedTimelineBringsSootToFullAndBackAgainAndAgain() = runTest {
        val rig = rig()
        val soot = ArrayList<Double>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.controller.uiState.collect { state -> state.value("dpf.soot_pct")?.let { soot += it.valueText.toDouble() } }
        }
        rig.controller.connect(demo)
        awaitLive(rig.controller)
        // The timeline is 20 s and the end state is held for 5 s: three laps need about 75 s.
        advanceTimeBy(80.seconds)

        val fullAt = soot.indexOfFirst { it == 100.0 }
        assertTrue(fullAt >= 0, "soot never reached 100: ${soot.distinct()}")
        val lowAt = soot.withIndex().indexOfFirst { (i, v) -> i > fullAt && v < 20.0 }
        assertTrue(lowAt > fullAt, "soot never went back below 20: ${soot.distinct()}")
        // Back at the start of a lap, the soot climbs to full again: the timeline restarted after the vehicle was reset.
        assertTrue(soot.withIndex().any { (i, v) -> i > lowAt && v == 100.0 }, "soot did not reach 100 a second time: ${soot.distinct()}")
    }

    @Test
    fun theRecordingIsFedFromTheFirstByteAndEndsWithTheClose() = runTest {
        val rig = rig()
        rig.controller.connect(demo)
        awaitLive(rig.controller)
        advanceTimeBy(3.seconds)

        assertEquals(1, rig.sinks.sinks.size, "one link was opened, so one recording")
        val live = rig.sinks.sinks.single().lines
        assertTrue(live.first().contains("knightlink-session"), "the first line is the header")
        assertTrue(live.size > 50, "lines: ${live.size}")
        assertEquals(0, rig.sinks.finished, "still recording")

        rig.controller.disconnect()

        val recording = rig.sinks.sinks.single().toRecording()
        // The traffic ends with the close; what follows is the session saying why it ended.
        assertEquals(EventKind.CLOSE, recording.events.last { it.kind != EventKind.EVENT }.kind)
        assertTrue(recording.events.last().let { it.kind == EventKind.EVENT && it.payload.startsWith("end:") }, recording.events.last().toString())
        // Recording starts with the first attempt, before any traffic: the connect attempt is already on record.
        assertTrue(recording.events.first().let { it.kind == EventKind.EVENT && it.payload.contains("Connecting") }, recording.events.first().toString())
        assertTrue(recording.events.any { it.kind == EventKind.TX })
        assertTrue(recording.events.any { it.kind == EventKind.RX })
        assertTrue(rig.sinks.finished >= 1, "the sink was released")
        // The header names the adapter definition, so a replay knows which init script produced it.
        assertEquals(config.adapterDefinition, recording.header.adapter)
    }

    @Test
    fun theConnectionLogTellsTheStoryOfASessionAndHoldsNoVin() = runTest {
        val directory = kotlin.io.path.createTempDirectory("connection-log").toFile()
        try {
            val log = ConnectionLog(directory)
            val controller = SessionController(
                backgroundScope,
                { config },
                store,
                DemoTransportFactory(store::simulation),
                MemorySinks(),
                testTimeSource,
                connectionLog = log,
            )
            controller.connect(demo)
            awaitLive(controller)
            advanceTimeBy(3.seconds)
            controller.disconnect()

            val text = log.files().joinToString("\n") { it.readText() }
            val expected = listOf(
                "app: connect requested: demo",
                "connect-attempt: attempt 1",
                "link-up",
                "init-end: ok",
                "probe-result: 3 ECUs",
                "state: Polling",
                "end: the session was stopped",
                "app: disconnected",
            )
            for (line in expected) assertTrue(line in text, "missing '$line' in:\n$text")
            // The session ended and so did the attempt: the next one starts a new file.
            controller.connect(demo)
            assertEquals(2, log.files().size)
            controller.disconnect()
            val scenarioVin = store.simulation(config.demoScenario).ecus.firstNotNullOf { it.vin }
            assertFalse(scenarioVin in log.shareable())
            assertFalse(scenarioVin.drop(3) in log.shareable())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun disconnectEndsInIdleAndTheAdapterIsNotAskedAnythingMore() = runTest {
        val rig = rig()
        rig.controller.connect(demo)
        awaitLive(rig.controller)
        advanceTimeBy(5.seconds)
        val adapter = assertNotNull(rig.factory.lastTransport?.simulated?.current)
        assertTrue(adapter.received.size > 20, "the session talked to the adapter")

        rig.controller.disconnect()
        val after = adapter.received.size

        assertEquals(UiState.IDLE, rig.controller.uiState.value)
        advanceTimeBy(60.seconds)
        assertEquals(after, adapter.received.size, "no traffic after disconnect")
        assertEquals(UiState.IDLE, rig.controller.uiState.value, "nothing publishes after disconnect")
    }

    @Test
    fun aSecondConnectIsRefusedWhileOneExistsAndAcceptedAfterDisconnect() = runTest {
        val rig = rig()
        assertTrue(rig.controller.connect(demo))
        assertFalse(rig.controller.connect(demo))
        awaitLive(rig.controller)
        assertFalse(rig.controller.connect(demo))
        assertEquals(1, rig.sinks.sinks.size)

        rig.controller.disconnect()
        assertTrue(rig.controller.connect(demo))
        awaitLive(rig.controller)
        assertEquals(2, rig.sinks.sinks.size, "a new connection is a new recording")
    }

    @Test
    fun disconnectBeforeAnythingIsLoadedStartsNoSession() = runTest {
        val rig = rig()
        rig.controller.connect(demo)
        rig.controller.disconnect()
        advanceTimeBy(10.seconds)

        assertEquals(UiState.IDLE, rig.controller.uiState.value)
        assertEquals(0, rig.sinks.sinks.size, "no link was ever opened")
    }

    @Test
    fun disconnectWithoutAConnectionDoesNothing() = runTest {
        val rig = rig()
        rig.controller.disconnect()
        assertEquals(UiState.IDLE, rig.controller.uiState.value)
    }

    @Test
    fun bluetoothIsNotBuiltInYetAndSaysSo() = runTest {
        val rig = rig()
        rig.controller.connect(ConnectionTarget.Ble("00:00:00:00:00:00"))
        runCurrent()

        val status = assertIs<ConnectionStatus.Failed>(rig.controller.uiState.value.connection)
        assertEquals("Bluetooth support is not built in yet", status.reason)
        assertEquals(0, rig.sinks.sinks.size)
        // A failed connection is still a connection to be ended by the user, who then can try again.
        assertFalse(rig.controller.connect(demo))
        rig.controller.disconnect()
        assertTrue(rig.controller.connect(demo))
    }

    @Test
    fun anUnknownScenarioIsAFailedConnectionNotACrash() = runTest {
        val rig = rig()
        rig.controller.connect(ConnectionTarget.Demo("sim/does-not-exist"))
        runCurrent()

        val status = assertIs<ConnectionStatus.Failed>(rig.controller.uiState.value.connection)
        assertTrue("sim/does-not-exist" in status.reason, status.reason)
    }

    @Test
    fun aBrokenConfigIsAFailedConnectionNotACrash() = runTest {
        val sinks = MemorySinks()
        val controller = SessionController(
            backgroundScope,
            config = { throw knightlink.app.data.DefinitionStoreException("app-config.json is broken") },
            store = store,
            transports = DemoTransportFactory(store::simulation),
            recordings = sinks,
            timeSource = testTimeSource,
        )
        controller.connect(demo)
        runCurrent()

        assertEquals(ConnectionStatus.Failed("app-config.json is broken"), controller.uiState.value.connection)
    }

    @Test
    fun theLastValuesAndTheVehicleSurviveALostLink() = runTest {
        val rig = rig()
        rig.controller.connect(demo)
        awaitLive(rig.controller)
        advanceTimeBy(3.seconds)
        assertTrue(rig.controller.uiState.value.liveValues.none { it.stale })

        // Lose the adapter: the session goes round again, and the values and the vehicle it had stay on screen meanwhile.
        rig.factory.lastTransport!!.simulated.current!!.disconnect()
        advanceTimeBy(500.milliseconds)
        val state = rig.controller.uiState.value
        assertTrue(state.connection is ConnectionStatus.Reconnecting || state.connection is ConnectionStatus.Connecting, "${state.connection}")
        assertTrue(state.liveValues.isNotEmpty(), "the last values are kept")
        assertNotNull(state.vehicle, "the vehicle card is kept")
    }
}
