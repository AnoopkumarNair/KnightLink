package knightlink.core.sim

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.adapter.Elm327Driver
import knightlink.core.common.SessionEvent
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.runtime.PollRates
import knightlink.core.runtime.SessionState
import knightlink.core.runtime.VehicleSession
import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The Harrier definition polled through the whole stack against a simulated car that reports what the real one reported on 2026-11-17:
 * how fast the driving signals update, how many requests that takes, that slow signals still update, and what happens to batching when an
 * ECU does not take it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PollingSpeedSimulationTest {
    private val root = File(System.getProperty("knightlink.repoRoot"))
    private val initScript = AdapterDefinition.parse(File(root, "definitions/adapters/vgate-icar-pro-2s.json").readText()).initScript
    private val catalog: SignalCatalog = definition("vehicles/tata/harrier-2020-kryotec-at").let { top ->
        SignalCatalog.of(top.extends.map(::definition) + top)
    }

    private fun definition(id: String): DefinitionFile = DefinitionJson.parse(File(root, "definitions/$id.json").readText())

    /** The PIDs the three ECUs of the real car reported. */
    private val ecmPids = "01 04 05 0B 0C 0D 10 13 1C 1F 21 2F 30 31 33 34 42 45 46 49 4A 4C 4F 62 63 68 69 6B 6D 71 78 7A 83 85 88 8B 8E 92 98 9D A1".split(
        " ",
    )
    private val tcmPids = "01 30 31".split(" ")
    private val rdcmPids = "01 04 05 0C 0D 1C 21 33 46 85 9B".split(" ")

    /** A plausible reply for each PID, with the length the definition gives (zeros and a few set bytes: the values do not matter here). */
    private fun ecu(request: Int, response: Int, pids: List<String>) = VirtualEcu(request, response).apply {
        for (pid in pids) {
            val length = catalog.find(1, pid, ecuId(request))?.length ?: catalog.find(1, pid)?.length ?: continue
            set("01$pid", "10".repeat(length))
        }
    }

    private fun ecuId(request: Int) = catalog.ecus.first { it.requestCanId == request }.id

    private fun car(ecmMultiPid: Boolean = true): VirtualVehicle =
        VirtualVehicle(listOf(ecu(0x7E0, 0x7E8, ecmPids).also { it.multiPid = ecmMultiPid }, ecu(0x7E1, 0x7E9, tcmPids), ecu(0x7E4, 0x7EC, rdcmPids)))

    /** Round trip of the real adapter (30 ms median) and a guess at what it listens on when it was not told how many replies to expect. */
    private val adapter = VirtualElm327.Config(responseDelay = 30.milliseconds, listenWithoutCount = 25.milliseconds)

    private class Run(val session: VehicleSession, val adapter: VirtualElm327, val events: List<SessionEvent>)

    private fun TestScope.start(vehicle: VirtualVehicle, maxBatch: Int = 6): Run {
        val events = ArrayList<SessionEvent>()
        val transport = SimulatedTransport(vehicle = { vehicle }, config = adapter)
        val config = VehicleSession.Config(maxBatch = maxBatch, driver = Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO))
        val session = VehicleSession(transport, initScript, catalog, backgroundScope, testTimeSource, config, SessionEventSink { events += it })
        session.start()
        // Probe, then poll for a minute; the rates are over the last 10 seconds.
        advanceTimeBy(80.seconds)
        runCurrent()
        return Run(session, transport.current!!, events)
    }

    private fun PollRates.rate(signal: String): Double = signals[signal] ?: 0.0

    @Test
    fun rpmAndSpeedUpdateAtLeastFiveTimesASecondWhileSlowSignalsStillUpdateAtMostFiftyRequestsASecond() = runTest {
        val run = start(car())
        assertIs<SessionState.Polling>(run.session.state.value)

        val rates = run.session.rates.value
        println("rates: ${rates.summary()}")
        assertTrue(rates.rate("engine.rpm") >= 5.0, "rpm ${rates.rate("engine.rpm")} Hz")
        assertTrue(rates.rate("vehicle.speed") >= 5.0, "speed ${rates.rate("vehicle.speed")} Hz")
        assertTrue(rates.rate("engine.load_pct") >= 5.0, "load ${rates.rate("engine.load_pct")} Hz")
        // Normal and slow tiers are not starved: about once a second, and about every six seconds.
        assertTrue(rates.rate("intake.maf") in 0.5..2.0, "maf ${rates.rate("intake.maf")} Hz")
        assertTrue(rates.rate("exhaust.egt_b1s1") in 0.5..2.0, "egt ${rates.rate("exhaust.egt_b1s1")} Hz")
        assertTrue(rates.rate("engine.coolant_temp") in 0.05..0.5, "coolant ${rates.rate("engine.coolant_temp")} Hz")
        assertTrue(rates.rate("fuel.level_pct") in 0.05..0.5, "fuel level ${rates.rate("fuel.level_pct")} Hz")
        assertTrue(rates.requestsPerSecond in 5.0..50.0, "requests ${rates.requestsPerSecond} per second")
        assertTrue(rates.batchedShare > 0.5, "most requests carry several PIDs: ${rates.batchedShare}")

        // Static items are asked once, and their value stays.
        assertNotNull(run.session.telemetry.value.value("obd.standard"))
        assertNotNull(run.session.telemetry.value.value("engine.torque_reference_nm"))
        // The AdBlue values come from the reductant controller.
        assertNotNull(run.session.telemetry.value.value("def.level_pct", ecu = "rdcm"))
    }

    @Test
    fun withoutBatchingTheSameCarIsSlowerOnTheFastSignalsAndSendsMoreRequestsPerReading() = runTest {
        val batched = start(car()).session.rates.value
        val single = start(car(), maxBatch = 1).session.rates.value
        println("batched:  ${batched.requestsPerSecond} requests/s, rpm ${batched.rate("engine.rpm")} Hz, maf ${batched.rate("intake.maf")} Hz")
        println("single:   ${single.requestsPerSecond} requests/s, rpm ${single.rate("engine.rpm")} Hz, maf ${single.rate("intake.maf")} Hz")
        assertTrue(batched.rate("engine.rpm") > single.rate("engine.rpm"))
        assertEquals(0.0, single.batchedShare)
        assertTrue(single.requestsPerSecond <= 50.0)
    }

    @Test
    fun theRatesAreWrittenToTheRecordingEveryThirtySeconds() = runTest {
        val run = start(car())
        val lines = run.events.filter { it.name == SessionEvents.RATES }
        assertTrue(lines.size >= 2, "rates events: ${lines.size}")
        val first = lines.first().detail
        assertTrue(first.startsWith("last 30 s: "), first)
        assertTrue("requests/s" in first && "% batched" in first && "engine.rpm" in first && "vehicle.speed" in first, first)
        // Compact: one line, no vehicle identity.
        assertTrue('\n' !in first && first.length < 3500, "${first.length} characters")
    }

    @Test
    fun anEcuThatIgnoresMultiPidRequestsFallsBackToSingleRequestsAndEverythingStillUpdates() = runTest {
        val run = start(car(ecmMultiPid = false))
        val rates = run.session.rates.value
        println("fallback: ${rates.summary(listOf("7E0"))}")
        val requests = run.adapter.received.filter { it.isNotEmpty() && !it.startsWith("AT") }
        val batches = requests.filter { it.startsWith("01") && it.length > 6 && it.length % 2 == 0 }
        // The batches were tried twice (and checked by a single request each time), then never again.
        assertTrue(batches.size in 1..4, "batch requests: ${batches.size}")
        assertTrue(requests.takeLast(100).none { it.startsWith("01") && it.length > 6 && it.length % 2 == 0 }, "still batching at the end")
        assertTrue(rates.rate("engine.rpm") > 1.0, "rpm ${rates.rate("engine.rpm")} Hz")
        assertTrue(rates.rate("vehicle.speed") > 1.0)
        assertTrue(rates.rate("engine.coolant_temp") > 0.0)
        assertTrue(run.events.any { it.name == SessionEvents.RATES && "batching off for 7E0" in it.detail })
    }

    @Test
    fun theBusLoadStaysUnderTheCeilingEvenWhenTheAdapterIsFast() = runTest {
        // An adapter that answers in 5 ms could be asked 200 times a second; the driver's ceiling is 50.
        val vehicle = car()
        val events = ArrayList<SessionEvent>()
        val transport = SimulatedTransport(vehicle = { vehicle }, config = VirtualElm327.Config(responseDelay = 5.milliseconds))
        val session = VehicleSession(
            transport,
            initScript,
            catalog,
            backgroundScope,
            testTimeSource,
            VehicleSession.Config(maxBatch = 1, driver = Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO)),
            SessionEventSink { events += it },
        )
        session.start()
        advanceTimeBy(60.seconds)
        runCurrent()
        val rates = session.rates.value
        println("fast adapter, no batching: ${rates.requestsPerSecond} requests/s")
        // 50 a second is the driver's ceiling (20 ms between requests); the window's edges may add a request.
        assertTrue(rates.requestsPerSecond <= 50.5, "requests ${rates.requestsPerSecond} per second")
    }
}
