package knightlink.app.session

import knightlink.app.ble.StringStore
import knightlink.app.data.AppConfig
import knightlink.app.data.DashboardReadings
import knightlink.app.data.DashboardStore
import knightlink.app.data.DefinitionStore
import knightlink.app.data.RepoAssetSource
import knightlink.core.recorder.InMemoryRecordSink
import knightlink.core.recorder.RecordSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The odometer and range the driver types: kept with no connection, through reconnects and restarts, and stamped only into the drive they belong to. */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardReadingsTest {
    private val assets = RepoAssetSource()
    private val definitions = DefinitionStore(assets)
    private val config = AppConfig.load(assets)
    private val demo = ConnectionTarget.Demo(config.demoScenario)

    private class MapStore(val values: MutableMap<String, String> = HashMap()) : StringStore {
        override fun get(key: String): String? = values[key]

        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class MemorySinks : RecordSinkFactory {
        val sinks = ArrayList<InMemoryRecordSink>()

        override fun newSink(): RecordSink = InMemoryRecordSink().also { sinks += it }

        override fun finishCurrent() = Unit
    }

    private var now = 1_800_000_000_000L
    private val transports = DemoTransportFactory(definitions::simulation)

    /** A phone sample source that sends nothing: as in the app, it makes the recording take external lines (mode, odo, dte). */
    private object NoSamples : SampleSource {
        override fun start(emit: (String) -> Unit) = Unit

        override fun stop() = Unit
    }

    private fun TestScope.controller(store: StringStore, sinks: MemorySinks = MemorySinks(), samples: SampleSource? = null): SessionController = SessionController(
        backgroundScope,
        { config },
        definitions,
        transports,
        sinks,
        testTimeSource,
        dashboardStore = DashboardStore(store),
        wallClock = { now },
        samples = samples,
    )

    private fun TestScope.awaitLive(controller: SessionController) {
        val limit = currentTime + 60_000L
        while (controller.uiState.value.connection != ConnectionStatus.Live && currentTime < limit) advanceTimeBy(250.milliseconds)
        assertEquals(ConnectionStatus.Live, controller.uiState.value.connection)
    }

    @Test
    fun endReadingsSavedWithNoConnectionShowAtOnceAndSurviveARestart() = runTest {
        val store = MapStore()
        val first = controller(store)
        assertFalse(first.markDashboard("85556", "431", end = true), "no run is recording")
        assertEquals(85556L, first.uiState.value.dashboard.odometerEndKm, "shown at once, with no connection")
        assertEquals(431L, first.uiState.value.dashboard.rangeEndKm)
        assertEquals(now, first.uiState.value.dashboard.endSavedAtMs)

        // The app is closed and opened again.
        val second = controller(store)
        assertEquals(85556L, second.uiState.value.dashboard.odometerEndKm)
        assertEquals(431L, second.uiState.value.dashboard.rangeEndKm)
    }

    @Test
    fun startReadingsSurviveReconnectsAndAreStampedIntoEveryRecordingOfTheDrive() = runTest {
        val store = MapStore()
        val sinks = MemorySinks()
        val controller = controller(store, sinks)
        controller.connect(demo)
        awaitLive(controller)
        assertTrue(controller.markDashboard("85548", "439", end = false))
        advanceTimeBy(2.seconds)
        assertEquals(85548L, controller.uiState.value.dashboard.odometerStartKm)

        // The link drops and comes back: the readings stay on screen, and the new recording gets them too.
        transports.lastTransport!!.simulated.current!!.disconnect()
        advanceTimeBy(500.milliseconds)
        awaitLive(controller)
        advanceTimeBy(2.seconds)
        assertEquals(85548L, controller.uiState.value.dashboard.odometerStartKm)
        assertEquals(439L, controller.uiState.value.dashboard.rangeStartKm)
        assertTrue(sinks.sinks.size >= 2, "a recording per link: ${sinks.sinks.size}")
        assertTrue(sinks.sinks.last().lines.any { it.contains("dashboard start: odometer 85548 km") }, "stamped into the recording after the reconnect")

        controller.disconnect()
        assertEquals(85548L, controller.uiState.value.dashboard.odometerStartKm, "still shown when idle")
        assertEquals(85548L, DashboardStore(store).load().odometerStartKm)
    }

    @Test
    fun theStartReadingsOfAFinishedDriveAreNotStampedIntoTheNextOne() = runTest {
        val store = MapStore()
        val morning = controller(store)
        morning.markDashboard("85548", "439", end = false)
        now += 30 * 60_000L
        morning.markDashboard("85556", "431", end = true)

        now += 8 * 60 * 60_000L
        val sinks = MemorySinks()
        val afternoon = controller(store, sinks)
        afternoon.connect(demo)
        awaitLive(afternoon)
        advanceTimeBy(2.seconds)
        assertTrue(sinks.sinks.none { sink -> sink.lines.any { it.contains("85548") } }, "the morning's start is not this drive's")
        afternoon.disconnect()
    }

    @Test
    fun startReadingsAreCurrentOnlyForTheDriveTheyBelongTo() {
        val t = 1_000_000_000L
        val open = DashboardReadings(odometerStartKm = 100, startSavedAtMs = t)
        assertTrue(open.startIsCurrent(t + 1000))
        assertFalse(open.startIsCurrent(t + DashboardReadings.CURRENT_DRIVE_MAX_AGE_MS + 1), "too old")
        assertFalse(open.copy(odometerEndKm = 110, endSavedAtMs = t + 10).startIsCurrent(t + 1000), "closed by the end readings")
        assertTrue(open.copy(odometerEndKm = 90, endSavedAtMs = t - 10).startIsCurrent(t + 1000), "the end is of the drive before")
        assertFalse(DashboardReadings(odometerStartKm = 100).startIsCurrent(t), "no time: saved by an older build")
    }

    @Test
    fun theStoreKeepsTheSaveTimes() {
        val store = DashboardStore(MapStore())
        val readings = DashboardReadings(1, 2, 3, 4, startSavedAtMs = 5, endSavedAtMs = 6)
        store.save(readings)
        assertEquals(readings, store.load())
    }

    @Test
    fun aNewStartDropsThePreviousDrivesEndSoAStaleEndIsNeverShown() = runTest {
        val store = MapStore()
        val controller = controller(store)
        // The previous drive, closed with its end readings.
        controller.markDashboard("85540", "450", end = false)
        now += 30 * 60_000L
        controller.markDashboard("85556", "431", end = true)

        // Hours later a new drive starts at 85564: the 85556 end is not this drive's.
        now += 5 * 60 * 60_000L
        controller.markDashboard("85564", "425", end = false)
        val readings = controller.uiState.value.dashboard
        assertEquals(85564L, readings.odometerStartKm)
        assertEquals(null, readings.odometerEndKm, "the previous drive's end is gone")
        assertEquals(null, readings.rangeEndKm)
        assertTrue(readings.needsEnd(now + 60_000L), "the end is asked for when the drive is over")
        assertEquals(null, DashboardStore(store).load().odometerEndKm, "also on the phone")

        now += 29 * 60_000L
        controller.markDashboard("85590", "", end = true)
        val closed = controller.uiState.value.dashboard
        assertEquals(85590L, closed.odometerEndKm)
        assertEquals(null, closed.rangeEndKm, "an empty field never brings back an earlier drive's value")
        assertTrue(closed.endIsCurrent)
        assertFalse(closed.needsEnd(now))
    }

    @Test
    fun anEndBelowTheStartOrSavedBeforeItIsNotThisDrives() {
        val t = 1_000_000_000L
        val start = DashboardReadings(odometerStartKm = 85564, rangeStartKm = 425, startSavedAtMs = t)
        assertFalse(start.copy(odometerEndKm = 85556, rangeEndKm = 431, endSavedAtMs = t - 1).endIsCurrent, "saved before the start")
        assertFalse(start.copy(odometerEndKm = 85556, endSavedAtMs = t + 1).endIsCurrent, "below the start")
        assertTrue(start.copy(odometerEndKm = 85590, endSavedAtMs = t + 1).endIsCurrent)
        assertTrue(start.needsEnd(t + 1000))
        // Correcting the start within the same drive keeps it a start; one above the end held drops that end.
        val corrected = start.copy(odometerEndKm = 85570, endSavedAtMs = null).withStart(85580, null, t + 2000)
        assertEquals(85580L, corrected.odometerStartKm)
        assertEquals(425L, corrected.rangeStartKm, "an empty field keeps the start's value")
        assertEquals(null, corrected.odometerEndKm)
    }

    @Test
    fun aDriveModeTapWritesTheModeLineIntoTheRecording() = runTest {
        val sinks = MemorySinks()
        val controller = controller(MapStore(), sinks, NoSamples)
        assertFalse(controller.markDriveMode(DriveMode.ECO), "nothing is recording yet")
        controller.connect(demo)
        awaitLive(controller)
        assertTrue(controller.markDriveMode(DriveMode.SPORT))
        advanceTimeBy(2.seconds)
        assertTrue(sinks.sinks.last().lines.any { it.contains(knightlink.core.recorder.ExternalSamples.modeLine("SPORT")) }, "the same mode line as before")
        assertEquals(DriveMode.SPORT, controller.uiState.value.drive?.mode)
        controller.disconnect()
    }
}
