package knightlink.app.session

import knightlink.app.data.AppConfig
import knightlink.app.data.DefinitionStore
import knightlink.app.data.RepoAssetSource
import knightlink.core.recorder.EventKind
import knightlink.core.recorder.InMemoryRecordSink
import knightlink.core.recorder.RecordSink
import knightlink.core.scan.ScanOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
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

/** The full scan through the session host, on the demo vehicle: the button's path, the progress the screen shows, the result and the phone's sensors. */
@OptIn(ExperimentalCoroutinesApi::class)
class FullScanControllerTest {
    private val assets = RepoAssetSource()
    private val store = DefinitionStore(assets)
    private val config = AppConfig.load(assets)
    private val demo = ConnectionTarget.Demo(config.demoScenario)

    private class MemorySinks : RecordSinkFactory {
        val sinks = ArrayList<InMemoryRecordSink>()
        val sidecars = ArrayList<Pair<String, String>>()

        override fun newSink(): RecordSink = InMemoryRecordSink().also { sinks += it }

        override fun finishCurrent() = Unit

        override fun writeSidecar(suffix: String, text: String) {
            sidecars += suffix to text
        }
    }

    private class FakeSamples : SampleSource {
        var started = 0
        var stopped = 0
        private var emit: ((String) -> Unit)? = null

        override fun start(emit: (String) -> Unit) {
            started++
            this.emit = emit
        }

        override fun stop() {
            stopped++
            emit = null
        }

        fun send(line: String) {
            emit?.invoke(line)
        }
    }

    private class Rig(val controller: SessionController, val sinks: MemorySinks, val samples: FakeSamples)

    private fun TestScope.rig(): Rig {
        val sinks = MemorySinks()
        val samples = FakeSamples()
        val controller = SessionController(
            backgroundScope,
            { config },
            store,
            DemoTransportFactory(store::simulation),
            sinks,
            testTimeSource,
            samples = samples,
        )
        return Rig(controller, sinks, samples)
    }

    private fun TestScope.awaitLive(controller: SessionController) {
        val limit = currentTime + 60_000L
        while (controller.uiState.value.connection != ConnectionStatus.Live && currentTime < limit) advanceTimeBy(250.milliseconds)
        assertEquals(ConnectionStatus.Live, controller.uiState.value.connection)
    }

    private fun TestScope.advanceUntil(limitMinutes: Int, done: () -> Boolean) {
        val limit = currentTime + limitMinutes * 60_000L
        while (!done() && currentTime < limit) advanceTimeBy(2.seconds)
    }

    @Test
    fun theDemoStartsWithAScanThatCanBeStarted() = runTest {
        val rig = rig()
        assertFalse(rig.controller.startFullScan(), "no session yet")
        rig.controller.connect(demo)
        awaitLive(rig.controller)
        advanceTimeBy(2.seconds)
        assertEquals(ScanStatus.Ready, rig.controller.uiState.value.scan)
    }

    @Test
    fun aScanAskedForOnTheDemoRunsThroughItsStagesAndEndsWithAResultAndASidecarFile() = runTest(timeout = 120.seconds) {
        val rig = rig()
        rig.controller.connect(demo)
        awaitLive(rig.controller)
        advanceTimeBy(2.seconds)

        assertTrue(rig.controller.startFullScan())
        assertFalse(rig.controller.startFullScan(), "one at a time")
        val seen = LinkedHashSet<Any?>()
        advanceUntil(30) {
            (rig.controller.uiState.value.scan as? ScanStatus.Running)?.let { seen += it.stage }
            rig.controller.uiState.value.scan is ScanStatus.Finished
        }
        val finished = assertIs<ScanStatus.Finished>(rig.controller.uiState.value.scan)
        assertEquals(ScanOutcome.COMPLETED, finished.outcome, finished.detail)
        assertEquals(8, finished.summary.ecus)
        assertEquals(1, finished.summary.warningLamp)
        assertTrue(finished.minutes in 5..20, "${finished.minutes} minutes")
        assertTrue(seen.size >= 5, "the screen saw the stages: $seen")

        // The result is written next to the recording, with the VIN masked.
        val (suffix, text) = rig.sinks.sidecars.single()
        assertEquals(".scan.json", suffix)
        assertTrue("\"outcome\": \"COMPLETED\"" in text)
        assertFalse(store.simulation(config.demoScenario).ecus.first { it.vin != null }.vin!! in text)

        // The connection log is not given the long line of JSON; the recording is.
        val events = rig.sinks.sinks.single().toRecording().events.filter { it.kind == EventKind.EVENT }.map { it.payload }
        assertEquals(1, events.count { it.startsWith("scan-result: ") })

        // Live values come back, and the scan can be dismissed and run again.
        advanceTimeBy(20.seconds)
        assertNotNull(rig.controller.uiState.value.liveValues.firstOrNull { it.signalId == "engine.rpm" && !it.stale })
        rig.controller.dismissFullScan()
        advanceTimeBy(1.seconds)
        assertEquals(ScanStatus.Ready, rig.controller.uiState.value.scan)
        rig.controller.disconnect()
    }

    @Test
    fun aRunningScanCanBeStoppedAndKeepsWhatItFound() = runTest(timeout = 120.seconds) {
        val rig = rig()
        rig.controller.connect(demo)
        awaitLive(rig.controller)
        rig.controller.startFullScan()
        advanceUntil(5) { (rig.controller.uiState.value.scan as? ScanStatus.Running)?.stage == knightlink.core.scan.ScanStage.IDENTIFICATION }
        val running = assertIs<ScanStatus.Running>(rig.controller.uiState.value.scan)
        assertTrue(running.total > 0 && running.done in 0..running.total)

        rig.controller.cancelFullScan()
        advanceTimeBy(5.seconds)
        val finished = assertIs<ScanStatus.Finished>(rig.controller.uiState.value.scan)
        assertEquals(ScanOutcome.CANCELLED, finished.outcome)
        assertTrue(finished.summary.ecus >= 8)
        assertEquals(ConnectionStatus.Live, rig.controller.uiState.value.connection)
        rig.controller.disconnect()
    }

    @Test
    fun thePhonesSensorsRunWithTheSessionAndTheirLinesGoIntoTheRecording() = runTest {
        val rig = rig()
        rig.controller.connect(demo)
        awaitLive(rig.controller)
        assertEquals(1, rig.samples.started)
        rig.samples.send("sensors accel=linear gyro=yes gps=on")
        rig.samples.send("mot 0 0.10 0.20 0.30 0.001 0.002 0.003")
        rig.samples.send("gps 12.34568 45.67890 0.0 - 5")
        advanceTimeBy(2.seconds)

        val recording = rig.sinks.sinks.single().toRecording()
        val external = recording.events.filter { it.kind == EventKind.EXTERNAL }.map { it.payload }
        assertEquals(listOf("sensors accel=linear gyro=yes gps=on", "mot 0 0.10 0.20 0.30 0.001 0.002 0.003", "gps 12.34568 45.67890 0.0 - 5"), external)
        assertTrue("\"schemaVersion\":3" in rig.sinks.sinks.single().lines.first())

        assertEquals(0, rig.samples.stopped)
        rig.controller.disconnect()
        assertEquals(1, rig.samples.stopped, "the sensors stop with the session")
    }

    @Test
    fun everyChangeOfTheCaptureModeIsWrittenIntoTheRecordingAndANewLinkStartsActiveWithoutALine() = runTest {
        val rig = rig()
        rig.controller.connect(demo)
        awaitLive(rig.controller)
        advanceTimeBy(1.seconds)
        assertTrue(rig.controller.setCaptureMode(passive = true))
        advanceTimeBy(1.seconds)
        assertTrue(rig.controller.setCaptureMode(passive = false))
        advanceTimeBy(3.seconds)

        val external = rig.sinks.sinks.single().toRecording().events.filter { it.kind == EventKind.EXTERNAL }.map { it.payload }
        assertEquals(listOf("capture passive", "capture active"), external.filter { it.startsWith("capture ") })
        rig.controller.disconnect()
    }

    @Test
    fun aSessionWithoutSensorsIsAnOrdinarySchema2Recording() = runTest {
        val sinks = MemorySinks()
        val controller = SessionController(backgroundScope, { config }, store, DemoTransportFactory(store::simulation), sinks, testTimeSource)
        controller.connect(demo)
        awaitLive(controller)
        assertTrue("\"schemaVersion\":2" in sinks.sinks.single().lines.first())
        controller.disconnect()
    }

    @Test
    fun theMinutesOfAFinishedScanAreRoundedUp() {
        val result = knightlink.core.scan.ScanResult(outcome = ScanOutcome.COMPLETED, durationMs = 61_000)
        val status = knightlink.core.runtime.ScanState.Finished(result).toStatus()
        assertEquals(2, assertIs<ScanStatus.Finished>(status).minutes)
        assertEquals(1, assertIs<ScanStatus.Finished>(knightlink.core.runtime.ScanState.Finished(result.copy(durationMs = 60_000)).toStatus()).minutes)
    }
}
