package knightlink.app.session

import knightlink.app.data.AppConfig
import knightlink.app.data.DefinitionStore
import knightlink.app.data.RepoAssetSource
import knightlink.core.recorder.EventKind
import knightlink.core.recorder.ExternalSamples
import knightlink.core.recorder.InMemoryRecordSink
import knightlink.core.recorder.RecordSink
import knightlink.core.tpms.TpmsDataKind
import knightlink.platform.ble.AdvertisementMatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TyreSensorsTest {
    private val assets = RepoAssetSource()
    private val store = DefinitionStore(assets)
    private val config = AppConfig.load(assets)
    private val layouts = config.tpmsDefinitions.map(store::tpms)
    private val eddystone = layouts.single { it.kind == TpmsDataKind.SERVICE }
    private val key = eddystone.key

    /** An Eddystone-UID frame as the sensors send it: frame type 00, tx power, namespace, then 350 kPa and 25 °C in the instance. */
    private val payload = byteArrayOf(0x00, -27, 0x11, 0x22, 0x01, 0x07, 0x33, 0x44, 0x55, 0x66, 0x77, -120, 0x2B, -64, 0x19, 0x00, 0x00, 0x00)

    @Test
    fun theAppListensForTyreSensorsWithEveryBundledLayout() {
        assertTrue(layouts.size >= 2, "both candidate layouts are configured")
        assertEquals(setOf(TpmsDataKind.SERVICE, TpmsDataKind.MANUFACTURER), layouts.map { it.kind }.toSet())
        // One hardware filter per layout: where the data is and what it starts with.
        assertEquals(2, TyreSensorCapture(layouts).matches.size)
    }

    @Test
    fun oneLinePerDistinctPayloadPerFiveSeconds() {
        val capture = TyreSensorCapture(layouts)
        val first = assertNotNull(capture.accept(1000, true, key, payload, -60))
        assertEquals(payload.size, assertNotNull(ExternalSamples.parseTpms(1000, first)).payloadHex.length / 2)
        // The rest of the burst, every 852 ms: held back until five seconds have passed.
        assertNull(capture.accept(1852, true, key, payload, -61))
        assertNull(capture.accept(5999, true, key, payload, -61))
        assertNotNull(capture.accept(6000, true, key, payload, -62))
        // A changed value is a new payload, recorded at once.
        val warmer = payload.copyOf().also { it[14] = 0x1A }
        assertNotNull(capture.accept(6100, true, key, warmer, -60))
        assertEquals(5, capture.readings(6100).sumOf { it.count })
    }

    @Test
    fun aCrowdIsCappedPerSecond() {
        val capture = TyreSensorCapture(layouts, maxLinesPerSecond = 3)
        val lines = (0 until 10).mapNotNull { i -> capture.accept(2000 + i.toLong(), true, key, payload.copyOf().also { it[2] = i.toByte() }, -80) }
        assertEquals(3, lines.size)
        assertNotNull(capture.accept(3000, true, key, payload.copyOf().also { it[2] = 9 }, -80), "a new second, a new allowance")
    }

    @Test
    fun theScreenShowsEachPayloadReadWithEveryLayoutAndForgetsOldOnes() {
        val capture = TyreSensorCapture(layouts, keepMs = 60_000)
        capture.accept(0, true, key, payload, -55)
        val reading = capture.readings(3000).single()
        assertEquals(payload.size * 2, reading.payloadHex.length)
        assertEquals(-55, reading.rssi)
        assertEquals(3, reading.ageSeconds)
        assertEquals("s:" + key.toString(16).uppercase(), reading.where)
        // Only the layout whose place and prefix fit reads it.
        val decoded = reading.decoded.single()
        assertTrue("id 11220107334455667788" in decoded && "pressure_kpa 350 kPa" in decoded && "temp_c 25 degC" in decoded, decoded)
        assertTrue(capture.unconfirmed)
        assertTrue(capture.readings(60_001).isEmpty())
    }

    @Test
    fun aPayloadTooShortForEveryLayoutIsStillRecorded() {
        val capture = TyreSensorCapture(layouts)
        assertNotNull(capture.accept(0, true, key, byteArrayOf(0, 2), -70))
        assertTrue(capture.readings(0).single().decoded.isEmpty())
    }

    @Test
    fun valuesAreShownWithOneDecimalAtMost() {
        assertEquals("33", TyreSensorCapture.formatValue(33.0))
        assertEquals("50.8", TyreSensorCapture.formatValue(50.7614))
        assertEquals("-6", TyreSensorCapture.formatValue(-6.0))
        assertEquals("-0.5", TyreSensorCapture.formatValue(-0.5))
    }

    // Through the session host --------------------------------------------------------------------------------------

    private class MemorySinks : RecordSinkFactory {
        val sinks = ArrayList<InMemoryRecordSink>()

        override fun newSink(): RecordSink = InMemoryRecordSink().also { sinks += it }

        override fun finishCurrent() = Unit
    }

    private class FakeRadio(private val listening: Boolean = true) : AdvertisementSource {
        var startedWith: List<AdvertisementMatch>? = null
        var stopped = 0
        private var deliver: ((Boolean, Int, ByteArray, Int) -> Unit)? = null

        override fun start(matches: List<AdvertisementMatch>, onAdvertisement: (serviceData: Boolean, key: Int, data: ByteArray, rssi: Int) -> Unit): Boolean {
            startedWith = matches
            if (listening) deliver = onAdvertisement
            return listening
        }

        override fun stop() {
            stopped++
            deliver = null
        }

        fun send(key: Int, payload: ByteArray, rssi: Int) {
            deliver?.invoke(true, key, payload, rssi)
        }
    }

    private fun TestScope.awaitLive(controller: SessionController) {
        val limit = currentTime + 60_000L
        while (controller.uiState.value.connection != ConnectionStatus.Live && currentTime < limit) advanceTimeBy(250.milliseconds)
        assertEquals(ConnectionStatus.Live, controller.uiState.value.connection)
    }

    @Test
    fun advertisementsAreRecordedAndShownWhileTheSessionKeepsPolling() = runTest {
        val sinks = MemorySinks()
        val radio = FakeRadio()
        val controller =
            SessionController(backgroundScope, { config }, store, DemoTransportFactory(store::simulation), sinks, testTimeSource, tyreSensors = radio)
        controller.connect(ConnectionTarget.Demo(config.demoScenario))
        awaitLive(controller)
        assertEquals(
            setOf(
                true to key,
                false to layouts.single {
                    it.kind == TpmsDataKind.MANUFACTURER
                }.key,
            ),
            radio.startedWith?.map { it.serviceData to it.key }?.toSet(),
        )

        val sentBefore = sinks.sinks.single().toRecording().events.count { it.kind == EventKind.TX }
        // A ten-second burst every 852 ms, as the sensors send.
        repeat(12) {
            radio.send(key, payload, -58)
            advanceTimeBy(852.milliseconds)
        }
        advanceTimeBy(2.seconds)

        val recording = sinks.sinks.single().toRecording()
        val tpms = recording.events.filter { it.kind == EventKind.EXTERNAL }.mapNotNull { ExternalSamples.parseTpms(it.timeMs, it.payload) }
        assertTrue(tpms.size in 2..3, "two or three lines of a ten-second burst, not twelve: $tpms")
        assertTrue(recording.events.count { it.kind == EventKind.TX } > sentBefore + 10, "polling went on beside the capture")
        assertEquals(ConnectionStatus.Live, controller.uiState.value.connection)
        assertTrue("\"schemaVersion\":3" in sinks.sinks.single().lines.first())

        val tyres = assertNotNull(controller.uiState.value.tyres)
        assertTrue(tyres.listening)
        assertTrue(tyres.unconfirmed)
        assertEquals(12, tyres.readings.single().count)

        controller.disconnect()
        assertEquals(1, radio.stopped, "the capture stops with the session")
    }

    @Test
    fun withoutBluetoothOrPermissionTheSessionRunsAndSaysItIsNotListening() = runTest {
        val sinks = MemorySinks()
        val controller = SessionController(
            backgroundScope,
            { config },
            store,
            DemoTransportFactory(store::simulation),
            sinks,
            testTimeSource,
            tyreSensors = FakeRadio(listening = false),
        )
        controller.connect(ConnectionTarget.Demo(config.demoScenario))
        awaitLive(controller)
        assertFalse(assertNotNull(controller.uiState.value.tyres).listening)
        controller.disconnect()
    }

    @Test
    fun aConfigWithoutTyreLayoutsCapturesNothing() = runTest {
        val radio = FakeRadio()
        val controller = SessionController(
            backgroundScope,
            { config.copy(tpmsDefinitions = emptyList()) },
            store,
            DemoTransportFactory(store::simulation),
            MemorySinks(),
            testTimeSource,
            tyreSensors = radio,
        )
        controller.connect(ConnectionTarget.Demo(config.demoScenario))
        awaitLive(controller)
        assertNull(radio.startedWith)
        assertNull(controller.uiState.value.tyres)
        controller.disconnect()
    }
}
