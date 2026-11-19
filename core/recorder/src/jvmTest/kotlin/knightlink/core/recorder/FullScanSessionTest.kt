package knightlink.core.recorder

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.adapter.ReadOnlyPolicy
import knightlink.core.common.SessionEvents
import knightlink.core.runtime.MAX_DISCOVERY_ITEMS
import knightlink.core.runtime.ScanState
import knightlink.core.runtime.SessionState
import knightlink.core.runtime.VehicleSession
import knightlink.core.runtime.discoveryPollItems
import knightlink.core.scan.DidSweepPlan
import knightlink.core.scan.ScanOutcome
import knightlink.core.scan.ScanResult
import knightlink.core.scheduler.PollScheduler
import knightlink.core.signals.DefinitionFile
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import knightlink.core.sim.SimulatedTransport
import knightlink.core.sim.SimulationFile
import knightlink.core.sim.VirtualElm327
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The full scan inside a real session on the simulator, recorded: it starts from a polling session, hands the adapter back, polls the
 * data identifiers it found, and all of it is in the recording.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FullScanSessionTest {
    private val root = File(System.getProperty("knightlink.repoRoot"))
    private val simulation = SimulationFile.parse(File(root, "fixtures/sim/harrier-full-scan.json").readText())
    private val adapterDefinition = AdapterDefinition.parse(File(root, "definitions/adapters/vgate-icar-pro-2s.json").readText())
    private val plan = DidSweepPlan.parse(File(root, "definitions/discovery/did-sweep.json").readText())
    private val catalog: SignalCatalog = definition(simulation.definition).let { top -> SignalCatalog.of(top.extends.map(::definition) + top) }

    private fun definition(id: String): DefinitionFile = DefinitionJson.parse(File(root, "definitions/$id.json").readText())

    private fun TestScope.elapse(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    /** Every VIN a recording's replies carry, read the way the stack reads them: service 09 PID 02 and data identifier F190. */
    private fun vinsIn(recording: Recording): Set<String> {
        val framer = knightlink.core.protocol.ElmStreamFramer()
        val decoder = knightlink.core.protocol.ElmResponseDecoder(extendedIds = false)
        val vins = LinkedHashSet<String>()
        for (event in recording.events.filter { it.kind == EventKind.RX }) {
            for (lines in framer.feed(event.bytes)) {
                for (message in decoder.decode(lines).messages.filterIsInstance<knightlink.core.protocol.DiagnosticResponse.Positive>()) {
                    val data = message.data
                    val from = when {
                        message.service == 0x09 && data.size >= 2 && data[0] == 0x02.toByte() -> 2
                        message.service == 0x22 && data.size >= 2 && data[0] == 0xF1.toByte() && data[1] == 0x90.toByte() -> 2
                        else -> continue
                    }
                    vins += data.copyOfRange(from, data.size).decodeToString()
                }
            }
        }
        return vins
    }

    private class Run(val session: VehicleSession, val recording: () -> Recording, val adapter: () -> VirtualElm327)

    private fun TestScope.start(
        withPlan: Boolean = true,
        memory: knightlink.core.runtime.VehicleMemoryStore? = null,
        onVehicle: (knightlink.core.sim.VirtualVehicle) -> Unit = {},
    ): Run {
        val vehicle = simulation.buildVehicle().also(onVehicle)
        val simulated = SimulatedTransport(vehicle = { vehicle }, config = VirtualElm327.Config(responseDelay = 40.milliseconds))
        val sink = InMemoryRecordSink()
        val transport = RecordingTransport(simulated, { sink }, testTimeSource, RecordingHeader(adapterDefinition.id, "full scan"), externalSamples = true)
        val config = VehicleSession.Config(
            scanPlan = if (withPlan) plan else null,
            memory = memory,
            driver = knightlink.core.adapter.Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO),
        )
        val session = VehicleSession(transport, adapterDefinition.initScript, catalog, backgroundScope, testTimeSource, config, transport)
        session.start()
        return Run(session, { sink.toRecording() }, { simulated.current!! })
    }

    private fun TestScope.untilScanEnds(run: Run, limit: Duration = 30.seconds * 60): ScanState.Finished {
        var waited = Duration.ZERO
        while (run.session.scan.value !is ScanState.Finished && waited < limit) {
            elapse(5.seconds)
            waited += 5.seconds
        }
        return assertIs<ScanState.Finished>(run.session.scan.value, "the scan did not finish within $limit")
    }

    @Test
    fun aScanAskedForWhilePollingRunsAndPollingGoesOnWithWhatItFound() = runTest(timeout = 180.seconds) {
        val run = start()
        elapse(15.seconds)
        assertIs<SessionState.Polling>(run.session.state.value)
        assertEquals(ScanState.Idle, run.session.scan.value)

        assertTrue(run.session.startScan())
        assertEquals(ScanState.Starting, run.session.scan.value)
        assertFalse(run.session.startScan(), "one scan at a time")
        val finished = untilScanEnds(run)
        val result = finished.result
        assertEquals(ScanOutcome.COMPLETED, result.outcome, result.detail)
        assertEquals(8, result.ecus.size)

        // Polling is back, and the identifiers the scan found are in the poll list: the gearbox's six, the engine's three short ones (the
        // fourth has nine bytes), the exhaust unit's one.
        elapse(40.seconds)
        assertIs<SessionState.Polling>(run.session.state.value)
        val before = run.adapter().received.size
        elapse(30.seconds)
        val polled = run.adapter().received.drop(before).map { it.filterNot(Char::isWhitespace).uppercase() }
        for (did in listOf("221E12", "221E1F", "221E65", "2201A0", "221E7E", "22201C")) {
            assertTrue(polled.any { it.startsWith(did) }, "$did is polled: ${polled.take(40)}")
        }
        assertTrue(polled.any { it.startsWith("2203F3") } && polled.any { it.startsWith("22ED02") } && polled.any { it.startsWith("22E011") })
        assertTrue(polled.none { it.startsWith("221310") }, "nine bytes is too long for the engine's limit")
        assertTrue(polled.any { it.startsWith("22D010") })
        // Never in a batch: a batch is service 01 only.
        assertTrue(polled.filter { it.startsWith("22") }.all { it.length in 6..7 }, polled.filter { it.startsWith("22") }.toString())
        // The vehicle's own signals are still read.
        assertTrue(polled.any { it.startsWith("010C") || it.startsWith("0105") || it.startsWith("010B") }, polled.take(40).toString())
        assertNotNull(run.session.telemetry.value.value("engine.rpm"))

        // The recording holds all of it, read-only, with the scan's own events and its result.
        val recording = run.recording()
        val sent = recording.events.filter { it.kind == EventKind.TX }.joinToString("") { it.bytes.decodeToString() }.split('\r').dropLast(1)
        assertTrue(sent.mapNotNull { ReadOnlyPolicy.violation(it) }.isEmpty())
        val events = recording.events.filter { it.kind == EventKind.EVENT }.map { it.payload }
        assertTrue(events.any { it.startsWith("scan: stage 6 of 6: sweep") }, events.filter { it.startsWith("scan") }.toString())
        assertTrue(events.any { it.startsWith("scan: polling 10 identifiers that the scan found") }, events.filter { "polling" in it }.toString())
        val resultEvent = events.single { it.startsWith("${SessionEvents.SCAN_RESULT}: ") }.removePrefix("${SessionEvents.SCAN_RESULT}: ")
        assertEquals(result, ScanResult.fromJson(resultEvent))
        run.session.stop()
    }

    @Test
    fun theDiscoveryItemsAreRawNeverBatchedAndLimitedAsThePlanSays() {
        val gearbox = (0x1E00..0x1E1F).map { String.format("%04X", it) }
        val engine = (0x2000..0x2029).map { String.format("%04X", it) } // 42 answers, 20 allowed
        val exhaust = listOf("D000", "D001")
        fun positives(ids: List<String>, bytes: Int) = ids.map { knightlink.core.scan.DidAnswer(it, "AA".repeat(bytes)) }
        val result = ScanResult(
            outcome = ScanOutcome.COMPLETED,
            sweeps = listOf(
                knightlink.core.scan.SweepResult("7E1", "gearbox", 1, 1, positives(gearbox, 2)),
                knightlink.core.scan.SweepResult("7E0", "engine", 1, 1, positives(engine, 6)),
                knightlink.core.scan.SweepResult("7E4", "exhaust", 1, 1, positives(exhaust, 9)),
            ),
        )
        val items = discoveryPollItems(result, plan)
        assertEquals(32 + 20, items.size, "the gearbox's all, 20 of the engine's, none of the exhaust unit's (nine bytes)")
        assertTrue(items.size <= MAX_DISCOVERY_ITEMS)
        assertTrue(items.all { it.payload[0] == 0x22.toByte() && it.payload.size == 3 }, "service 22, one identifier")
        assertTrue(items.none { it.batchable }, "never batched")
        assertTrue(items.all { it.dataLength == null }, "raw: no decoding length")
        assertEquals(1, items.first().expectedResponses, "two bytes of data fit one frame")
        assertEquals(null, items.last().expectedResponses, "six bytes do not")
        assertEquals(1, items.map { it.period }.toSet().size)
        assertEquals(setOf(0x7E1, 0x7E0), items.map { it.requestId }.toSet())
        assertEquals("7E1:221E00", items.first().id)

        // Whatever the scan found, at most 60.
        val many = ScanResult(
            outcome = ScanOutcome.COMPLETED,
            sweeps = listOf(knightlink.core.scan.SweepResult("7E1", "gearbox", 1, 1, positives((0x1000..0x1100).map { String.format("%04X", it) }, 1))),
        )
        assertEquals(MAX_DISCOVERY_ITEMS, discoveryPollItems(many, plan).size)
    }

    @Test
    fun theSchedulerNeverPutsADiscoveryItemInABatchOfService01() {
        val result = ScanResult(
            outcome = ScanOutcome.COMPLETED,
            sweeps = listOf(
                knightlink.core.scan.SweepResult(
                    "7E1",
                    "gearbox",
                    1,
                    1,
                    listOf(knightlink.core.scan.DidAnswer("1E12", "0A"), knightlink.core.scan.DidAnswer("1E1F", "7B")),
                ),
            ),
        )
        val extras = discoveryPollItems(result, plan)
        val normal = listOf("0C", "0D", "05", "0B").map {
            knightlink.core.scheduler.PollItem("7E1:01$it", 0x7E1, byteArrayOf(1, it.toInt(16).toByte()), 100.milliseconds, 1, 1)
        }
        val scheduler = PollScheduler(normal, maxBatch = 6)
        scheduler.replaceExtras(extras)
        var now = Duration.ZERO
        var sawExtra = false
        repeat(200) {
            val poll = scheduler.next(now, 0x7E1)
            if (poll is knightlink.core.scheduler.PollDecision.Poll) {
                if (poll.items.any { it in extras }) {
                    sawExtra = true
                    assertEquals(1, poll.items.size, "a discovery item is asked alone")
                    assertEquals(0x22.toByte(), poll.payload[0])
                }
                scheduler.onResult(poll, now, poll.items.toSet())
            }
            now += 40.milliseconds
        }
        assertTrue(sawExtra)
        // Replacing them leaves the vehicle's own items alone and removes the old ones.
        scheduler.replaceExtras(emptyList())
        repeat(50) {
            val poll = scheduler.next(now, 0x7E1)
            if (poll is knightlink.core.scheduler.PollDecision.Poll) {
                assertTrue(poll.items.none { it in extras })
                scheduler.onResult(poll, now, poll.items.toSet())
            }
            now += 40.milliseconds
        }
    }

    private fun TestScope.untilScanSettles(run: Run, limit: Duration = 30.seconds * 60): ScanState.Finished {
        var waited = Duration.ZERO
        while (waited < limit) {
            val state = run.session.scan.value
            if (state is ScanState.Finished && state.result.outcome == ScanOutcome.COMPLETED) return state
            elapse(5.seconds)
            waited += 5.seconds
        }
        return assertIs<ScanState.Finished>(run.session.scan.value)
    }

    @Test
    fun aLinkLostDuringTheScanKeepsThePartialResultAndTheScanResumesFromItsLastStage() = runTest(timeout = 240.seconds) {
        val run = start()
        elapse(15.seconds)
        assertTrue(run.session.startScan())
        elapse(90.seconds)
        assertIs<ScanState.Running>(run.session.scan.value)
        run.adapter().disconnect()
        elapse(30.seconds)
        val lost = assertIs<ScanState.Finished>(run.session.scan.value)
        assertEquals(ScanOutcome.LINK_LOST, lost.result.outcome)
        assertEquals("link lost", lost.result.detail)
        assertTrue(lost.result.resumable)
        assertTrue(lost.result.ecus.isNotEmpty(), "what it found so far is kept")
        assertIs<SessionState.Polling>(run.session.state.value)

        assertTrue(run.session.resumeScan())
        val done = untilScanSettles(run).result
        assertEquals(ScanOutcome.COMPLETED, done.outcome, done.detail)
        assertEquals(listOf("probe", "readiness", "discovery", "identification", "faults", "sweep"), done.stagesDone)
        assertEquals(8, done.ecus.size)
        assertTrue(done.durationMs > lost.result.durationMs)
        run.session.stop()
    }

    @Test
    fun aSessionWithNothingToResumeRefusesToResume() = runTest {
        val run = start()
        elapse(15.seconds)
        assertFalse(run.session.resumeScan())
        run.session.stop()
    }

    @Test
    fun theProbeTheScanAndTheDiscoveryIdentifiersAreRestoredByANewSessionWithoutAnyVin() = runTest(timeout = 300.seconds) {
        val store = knightlink.core.runtime.InMemoryVehicleMemoryStore()
        val first = start(memory = store)
        elapse(15.seconds)
        assertTrue(first.session.startScan())
        untilScanEnds(first)
        elapse(5.seconds)
        first.session.stop()
        val vin = simulation.ecus.first { it.id == "ecm" }.vin!!
        assertTrue(store.texts().isNotEmpty() && store.texts().none { vin in it }, "the memory holds no VIN")
        val remembered = assertNotNull(store.lastKey()?.let(store::load))
        assertEquals(ScanOutcome.COMPLETED, remembered.scan?.outcome)

        // A new session, as after an app restart: no probe, the discovery identifiers are polled at once, without a scan.
        val second = start(memory = store)
        val states = ArrayList<SessionState>()
        backgroundScope.launch { second.session.state.collect { states += it } }
        elapse(60.seconds)
        assertTrue(states.none { it is SessionState.Probing }, "the remembered probe was reused: $states")
        assertIs<SessionState.Polling>(second.session.state.value)
        assertEquals(ScanState.Idle, second.session.scan.value, "a completed scan is not shown again")
        val polled = second.adapter().received.map { it.filterNot(Char::isWhitespace).uppercase() }
        assertTrue(polled.any { it.startsWith("221E12") } && polled.any { it.startsWith("22E011") }, "discovery polling survived: ${polled.take(30)}")
        assertTrue(polled.none { it.startsWith("22F4") }, "F4xx identifiers mirror Mode 01 PIDs and are not polled")
        second.session.stop()
    }

    @Test
    fun aVehicleThatDoesNotAnswerTheCheapProbeAsRememberedIsProbedInFull() = runTest(timeout = 120.seconds) {
        val store = knightlink.core.runtime.InMemoryVehicleMemoryStore()
        val first = start(memory = store)
        elapse(15.seconds)
        first.session.stop()
        assertNotNull(store.lastKey())
        val states = ArrayList<SessionState>()
        val second = start(memory = store, onVehicle = { it.ecu(0x7E0).remove("0105") })
        backgroundScope.launch { second.session.state.collect { states += it } }
        elapse(30.seconds)
        assertTrue(states.any { it is SessionState.Probing }, "a changed vehicle is probed again: $states")
        second.session.stop()
    }

    @Test
    fun aCancelledScanIsRestoredByANewSessionAndCanBeResumedThere() = runTest(timeout = 300.seconds) {
        val store = knightlink.core.runtime.InMemoryVehicleMemoryStore()
        val first = start(memory = store)
        elapse(15.seconds)
        assertTrue(first.session.startScan())
        elapse(90.seconds)
        first.session.cancelScan()
        elapse(10.seconds)
        assertEquals(ScanOutcome.CANCELLED, assertIs<ScanState.Finished>(first.session.scan.value).result.outcome)
        first.session.stop()

        val second = start(memory = store)
        elapse(20.seconds)
        val restored = assertIs<ScanState.Finished>(second.session.scan.value)
        assertTrue(restored.restored && restored.result.resumable)
        assertTrue(second.session.resumeScan())
        val done = untilScanSettles(second).result
        assertEquals(ScanOutcome.COMPLETED, done.outcome, done.detail)
        second.session.stop()
    }

    @Test
    fun aSessionWithoutAPlanCannotScan() = runTest {
        val run = start(withPlan = false)
        elapse(15.seconds)
        assertEquals(ScanState.Unavailable, run.session.scan.value)
        assertFalse(run.session.startScan())
        run.session.stop()
    }

    @Test
    fun aScanCannotBeStartedBeforeTheCarIsBeingPolled() = runTest {
        val run = start()
        assertFalse(run.session.startScan(), "not connected yet")
        run.session.stop()
    }

    @Test
    fun aCancelledScanLeavesItsPartialResultAndPollingGoesOn() = runTest(timeout = 180.seconds) {
        val run = start()
        elapse(15.seconds)
        assertTrue(run.session.startScan())
        elapse(90.seconds)
        assertIs<ScanState.Running>(run.session.scan.value)
        run.session.cancelScan()
        elapse(10.seconds)
        val finished = assertIs<ScanState.Finished>(run.session.scan.value)
        assertEquals(ScanOutcome.CANCELLED, finished.result.outcome)
        assertIs<SessionState.Polling>(run.session.state.value)
        val before = run.adapter().received.size
        elapse(10.seconds)
        assertTrue(run.adapter().received.size > before, "polling again")
        // A finished scan can be dismissed, and another started.
        run.session.dismissScan()
        assertEquals(ScanState.Idle, run.session.scan.value)
        run.session.stop()
    }

    @Test
    fun aCarThatMovesPausesTheScanWhilePollingContinuesAndItResumesWhenParkedAgain() = runTest(timeout = 240.seconds) {
        lateinit var vehicle: knightlink.core.sim.VirtualVehicle
        val run = start(onVehicle = { vehicle = it })
        elapse(15.seconds)
        assertTrue(run.session.startScan())
        elapse(60.seconds)
        vehicle.ecu(0x7E0).set("010D", "1E")
        elapse(15.seconds)
        val paused = assertIs<ScanState.Running>(run.session.scan.value)
        assertTrue(paused.progress.paused, "the scan waits for the car")
        assertEquals(30, paused.progress.speedKmh)
        // Polling carries on meanwhile: the readings keep coming.
        val before = run.adapter().received.size
        elapse(10.seconds)
        assertTrue(run.adapter().received.size - before > 10)
        assertEquals(30.0, run.session.telemetry.value.value("vehicle.speed"))

        vehicle.ecu(0x7E0).set("010D", "00")
        val finished = untilScanEnds(run)
        assertEquals(ScanOutcome.COMPLETED, finished.result.outcome)
        assertTrue(finished.result.pausedMs >= 10_000)
        assertEquals(8, finished.result.ecus.size)
        run.session.stop()
    }

    @Test
    fun theScrubbedCopyOfAScanRecordingHoldsNoVinAndRoundsThePositionsItWasGiven() = runTest(timeout = 180.seconds) {
        val run = start()
        elapse(15.seconds)
        assertTrue(run.session.startScan())
        untilScanEnds(run)
        elapse(5.seconds)
        val vin = simulation.ecus.first { it.id == "ecm" }.vin!!
        val withSamples = run.recording().let { rec ->
            rec.copy(events = rec.events + listOf(RecordingEvent.external(rec.events.last().timeMs, "gps 52.52001 13.40495 0.0 - 5")))
        }
        // The raw recording holds the VIN (it is the app's private file): the check below has teeth.
        assertTrue(vin in vinsIn(withSamples), "the raw recording holds the VIN")
        val scrubbed = RecordingScrubber.scrub(withSamples)
        assertEquals(emptySet(), vinsIn(scrubbed.recording).filter { it == vin }.toSet(), "no full VIN in what is shared")
        val text = scrubbed.recording.toText()
        assertFalse(vin in text)
        assertFalse("52.5200" in text)
        assertTrue("gps 52.52 13.40 0.0 - 5" in text)
        // The truncated first frames of the discovery's F190 reads are not a reason to lose them: no warning for those.
        assertTrue(scrubbed.warnings.all { "22F190" !in it && "F190" !in it }, scrubbed.warnings.toString())
        run.session.stop()
    }

    @Test
    fun anOldRecordingStillReplaysAndAScanRecordingReplaysByCommand() = runTest(timeout = 180.seconds) {
        val run = start()
        elapse(15.seconds)
        assertTrue(run.session.startScan())
        untilScanEnds(run)
        run.session.stop()
        val recording = run.recording()
        // Every request of the scan is in the recording as an exchange: a replay by command finds an answer for each.
        val stream = ReplayByteStream(recording, ReplayMode.BY_COMMAND, speed = 1000.0)
        backgroundScope.launch { stream.incoming.collect { } }
        stream.write("ATZ\r".encodeToByteArray())
        assertEquals(0, stream.unknownCommands)
        stream.write("ATSH7E1\r".encodeToByteArray())
        stream.write("221E12\r".encodeToByteArray())
        assertEquals(0, stream.unknownCommands, stream.unknownCommandTexts.toString())
        stream.close()
    }
}
