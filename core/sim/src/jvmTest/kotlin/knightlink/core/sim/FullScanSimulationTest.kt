package knightlink.core.sim

import knightlink.core.adapter.AdapterDefinition
import knightlink.core.adapter.Elm327Driver
import knightlink.core.adapter.ReadOnlyPolicy
import knightlink.core.common.SessionEvent
import knightlink.core.common.SessionEventSink
import knightlink.core.common.SessionEvents
import knightlink.core.scan.DidSweepPlan
import knightlink.core.scan.FullScan
import knightlink.core.scan.ScanOutcome
import knightlink.core.scan.ScanRefusedException
import knightlink.core.scan.ScanResult
import knightlink.core.scan.ScanStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The full scan end to end on virtual time: scan -> real driver -> virtual ELM327 (with its receive filter and flow control) -> virtual car. */
@OptIn(ExperimentalCoroutinesApi::class)
class FullScanSimulationTest {
    private val root = File(System.getProperty("knightlink.repoRoot"))
    private val simulation = SimulationFile.parse(File(root, "fixtures/sim/harrier-full-scan.json").readText())
    private val initScript = AdapterDefinition.parse(File(root, "definitions/adapters/vgate-icar-pro-2s.json").readText()).initScript
    private val plan = DidSweepPlan.parse(File(root, "definitions/discovery/did-sweep.json").readText())

    private class Env(
        val vehicle: VirtualVehicle,
        val adapter: VirtualElm327,
        val driver: Elm327Driver,
        val events: MutableList<SessionEvent>,
        val time: kotlin.time.TimeSource,
    ) {
        val sink = SessionEventSink { events += it }
        val scanEvents: List<String> get() = events.filter { it.name == SessionEvents.SCAN }.map { it.detail }
    }

    private suspend fun TestScope.env(vehicle: VirtualVehicle = simulation.buildVehicle()): Env {
        val adapter = VirtualElm327(vehicle, VirtualElm327.Config(responseDelay = 30.milliseconds))
        val driver =
            Elm327Driver(adapter, backgroundScope, config = Elm327Driver.Config(timeSource = testTimeSource, warmUp = false, postResetDelay = Duration.ZERO))
        driver.initialize(initScript)
        return Env(vehicle, adapter, driver, ArrayList(), testTimeSource)
    }

    private fun Env.scan(
        plan: DidSweepPlan,
        config: FullScan.Config = FullScan.Config(),
        whileMoving: suspend (Int?) -> Unit = { delay(1.seconds) },
    ) = FullScan(driver, plan, sink, time, config, whileMoving)

    /** The diagnostic requests the adapter was sent (hex commands, with the response-count digit), in order. */
    private fun Env.requests(): List<String> = adapter.received.map { it.filterNot(Char::isWhitespace).uppercase() }.filter { c ->
        c.isNotEmpty() &&
            c.all { it in "0123456789ABCDEF" }
    }

    private fun Env.headers(): List<String> = adapter.received.map {
        it.filterNot(Char::isWhitespace).uppercase()
    }.filter { it.startsWith("ATSH") }.map { it.removePrefix("ATSH") }

    // The whole scan --------------------------------------------------------------------------------------

    @Test
    fun aParkedCarIsScannedFromEndToEndAndEveryControlUnitIsListedOnce() = runTest(timeout = 120.seconds) {
        val env = env()
        val scan = env.scan(plan)
        val result = scan.run()

        assertEquals(ScanOutcome.COMPLETED, result.outcome, result.detail)
        assertEquals(listOf("probe", "readiness", "discovery", "identification", "faults", "sweep"), result.stagesDone)
        assertEquals(listOf("700", "701", "703", "705", "750", "7E0", "7E1", "7E4"), result.ecus.map { it.requestId })
        assertEquals(listOf("708", "709", "70B", "70D", "758", "7E8", "7E9", "7EC"), result.ecus.map { it.responseId })
        assertEquals(setOf("probe"), result.ecus.filter { it.requestId.startsWith("7E") }.map { it.foundBy }.toSet())
        assertEquals(setOf("discovery"), result.ecus.filter { !it.requestId.startsWith("7E") }.map { it.foundBy }.toSet())
        assertEquals(result.ecus.size, result.ecus.map { it.responseId }.toSet().size, "one responder is never listed twice")
        assertEquals(result, scan.result.value)
    }

    @Test
    fun theProbeNamesTheOtherEcusIdentifiersAreReadAndTheVinIsNeverInTheResult() = runTest(timeout = 120.seconds) {
        val env = env()
        val result = env.scan(plan).run()
        val vin = simulation.ecus.first { it.id == "ecm" }.vin!!

        // The stack's own probe now returns names and calibration ids (the response-count digit is left off the long replies).
        val ecm = result.ecus.first { it.requestId == "7E0" }
        assertEquals("ECM-EngineControl", ecm.name)
        assertTrue(ecm.identity.any { it.did == "0904" && it.text == "SIM-ECM-CAL-0001" })
        assertTrue(ecm.identity.any { it.did == "0902" && it.text == "SIM" + "*".repeat(14) }, ecm.identity.toString())

        val gearbox = result.ecus.first { it.requestId == "7E1" }
        val byDid = gearbox.identity.associateBy { it.did }
        assertEquals("SIM-TCM-7000456", byDid.getValue("F187").text)
        assertEquals("spare part number", byDid.getValue("F187").label)
        assertEquals("SIM HYUNDAI", byDid.getValue("F18A").text)
        assertEquals("SIM 6AT", byDid.getValue("F197").text)
        assertNull(byDid["F190"], "the gearbox of the simulation does not know the VIN")

        val cluster = result.ecus.first { it.requestId == "700" }
        val clusterVin = cluster.identity.first { it.did == "F190" }
        assertEquals("SIM" + "*".repeat(14), clusterVin.text)
        assertNull(clusterVin.hex, "the VIN's bytes are not kept")
        assertEquals("SIM CLUSTER", cluster.name, "named from F197 when the unit says nothing over service 09")

        val json = result.toJson(pretty = true)
        assertFalse(vin in json, "no full VIN in the result")
        assertFalse(vin.encodeToByteArray().joinToString("") { "%02X".format(it) } in json, "and not as hex")
        assertFalse(env.events.any { vin in it.toString() }, "nor in an event")
        // Everything but the VIN round-trips through the file form.
        assertEquals(result, ScanResult.fromJson(json))
    }

    @Test
    fun readinessOnBoardTestsAndFaultCodesAreReadAndSortedByStatus() = runTest(timeout = 120.seconds) {
        val result = env().scan(plan).run()

        val readiness = result.readiness.first { it.requestId == "7E0" }
        val since = assertNotNull(readiness.sinceClear)
        assertEquals(false, since.milOn)
        assertTrue(since.compressionIgnition)
        assertEquals(false, since.monitors.first { it.name == "particulate filter" }.complete, "the DPF monitor has not run")
        assertNotNull(readiness.thisCycle)
        assertEquals(listOf("01", "02"), readiness.supportedMids)
        assertEquals(listOf(2, 1), readiness.midResults.map { it.tests.size })
        assertNull(result.readiness.first { it.requestId == "7E1" }.sinceClear)

        val engine = result.ecus.first { it.requestId == "7E0" }
        assertEquals(listOf("P0A1F", "P0299"), engine.storedCodes)
        assertEquals(listOf("P0123", "P0299"), engine.pendingCodes)
        assertEquals(emptyList(), engine.permanentCodes)
        val faults = assertNotNull(engine.udsFaults)
        assertEquals(listOf("P0A1F-1F", "P0299"), faults.confirmed.map { it.code })
        assertEquals(listOf("P0123", "P0299"), faults.pending.map { it.code })
        assertEquals(listOf("U0103"), faults.warningLamp.map { it.code })
        assertEquals(3, faults.notCompletedOnly)
        assertEquals(7, faults.total)

        assertEquals(1, result.ecus.first { it.requestId == "7E1" }.udsFaults!!.notCompletedOnly)
        assertEquals(setOf("B1A0C"), result.ecus.first { it.requestId == "700" }.udsFaults!!.confirmed.map { it.code }.toSet())
        assertNull(result.ecus.first { it.requestId == "703" }.udsFaults, "refused: no such service")
        assertEquals(0, result.ecus.first { it.requestId == "750" }.udsFaults!!.total)

        val summary = result.summary
        assertEquals(8, summary.ecus)
        // P0A1F, P0299 (engine, once although it is in the OBD and UDS lists), B1A0C (cluster).
        assertEquals(3, summary.confirmed)
        assertEquals(2, summary.pending)
        assertEquals(1, summary.warningLamp)
        assertEquals(3 + 1 + 2, summary.notCompleted, "engine 3, gearbox 1, body control 2: none of them faults")
    }

    @Test
    fun theManufacturerIdentifiersThatAnswerAreRecordedWithTheirBytesAndAreOnlyGuesses() = runTest(timeout = 120.seconds) {
        val result = env().scan(plan).run()
        val gearbox = result.sweeps.first { it.requestId == "7E1" }
        assertEquals(setOf("1E12", "1E1F", "1E65", "01A0", "1E7E", "201C"), gearbox.positive.map { it.did }.toSet())
        assertEquals("0A", gearbox.positive.first { it.did == "1E12" }.hex)
        assertEquals("00112233445566778899", gearbox.positive.first { it.did == "1E7E" }.hex, "a long reply is asked again, whole, after its first frame")
        assertTrue(gearbox.positive.all { it.provenance == knightlink.core.signals.Provenance.INFERRED })
        assertEquals(2049, gearbox.planned)
        assertEquals(2049, gearbox.asked)
        assertEquals(2049 - 6, gearbox.refused, "everything else is 'request out of range'")
        assertNull(gearbox.stoppedBecause)

        val engine = result.sweeps.first { it.requestId == "7E0" }
        assertEquals(setOf("03F3", "E011", "ED02", "1310"), engine.positive.map { it.did }.toSet())
        assertEquals("010203040506070809", engine.positive.first { it.did == "1310" }.hex)
        assertEquals(setOf("D010"), result.sweeps.first { it.requestId == "7E4" }.positive.map { it.did }.toSet())
        assertEquals(mapOf("7E1" to 6, "7E0" to 4, "7E4" to 1), result.summary.didsPerEcu)
    }

    // Safety ----------------------------------------------------------------------------------------------

    @Test
    fun everyCommandTheScanSendsIsAllowedAndNoRequestGoesToTheFunctionalAddress() = runTest(timeout = 120.seconds) {
        val env = env()
        env.scan(plan).run()
        val violations = env.adapter.received.mapNotNull { command -> ReadOnlyPolicy.violation(command)?.let { "$command: $it" } }
        assertTrue(violations.isEmpty(), violations.take(5).toString())
        assertFalse("7DF" in env.headers(), "physical addressing only")
        // Only reads: services 01, 03, 06, 19 (report), 22 and 09 (probe).
        val services = env.requests().map { it.take(2) }.toSet()
        assertTrue(services.all { it in setOf("01", "03", "06", "07", "09", "0A", "19", "22") }, services.toString())
    }

    @Test
    fun theScanNeverAsksForMoreThanTwentyRequestsASecond() = runTest(timeout = 120.seconds) {
        val env = env()
        val result = env.scan(plan).run()
        val requests = env.requests().size
        val seconds = result.durationMs / 1000.0
        assertTrue(requests / seconds <= 20.0, "$requests requests in $seconds s")
        // And not lazy either: the sweep of three units alone is more than six thousand requests.
        assertTrue(requests > 6000, "$requests")
    }

    @Test
    fun theAdapterIsPutBackWhenTheScanEnds() = runTest(timeout = 120.seconds) {
        val env = env()
        env.scan(plan).run()
        val tail = env.adapter.received.takeLast(6).map { it.filterNot(Char::isWhitespace).uppercase() }
        assertTrue("ATCM7F8" in tail && "ATCF7E8" in tail, tail.toString())
        // Ordinary polling works again: the default receive filter is back.
        val reply = env.driver.request(0x7E0, byteArrayOf(0x01, 0x0D), expectedResponses = 1)
        assertEquals(1, reply.messages.size)
        // A unit with an identifier that is not 7E8..7EF is not heard again (nothing listens there).
        val body = env.driver.request(0x700, byteArrayOf(0x22, 0xF1.toByte(), 0x87.toByte()), expectedResponses = 1)
        assertTrue(body.respondingIds.isEmpty())
    }

    @Test
    fun aControlUnitThatAnswersAnythingUnexpectedIsNotAskedFurtherAndTheRestOfTheScanGoesOn() = runTest(timeout = 120.seconds) {
        val vehicle = simulation.buildVehicle()
        vehicle.ecu(0x7E1).refuse("221E66", 0x22) // conditions not correct
        val env = env(vehicle)
        val result = env.scan(plan).run()

        assertEquals(ScanOutcome.COMPLETED, result.outcome)
        val gearbox = result.sweeps.first { it.requestId == "7E1" }
        assertTrue(gearbox.stoppedBecause!!.contains("22") && gearbox.stoppedBecause!!.contains("1E66"), gearbox.stoppedBecause)
        // The candidates come first, in the order of the file: 1E12, 1E1F, 1E65 were answered, then 1E66 stopped it.
        assertEquals(setOf("1E12", "1E1F", "1E65"), gearbox.positive.map { it.did }.toSet())
        assertEquals(4, gearbox.asked)
        // The other units are swept as usual.
        assertEquals(4, result.sweeps.first { it.requestId == "7E0" }.positive.size)
    }

    @Test
    fun aControlUnitThatAnswersResponsePendingIsAlsoLeftAlone() = runTest(timeout = 120.seconds) {
        val vehicle = simulation.buildVehicle()
        vehicle.ecu(0x7E4).refuse("220000", 0x78)
        val result = env(vehicle).scan(plan).run()
        val exhaust = result.sweeps.first { it.requestId == "7E4" }
        assertTrue(exhaust.stoppedBecause!!.contains("78"), exhaust.stoppedBecause)
        assertEquals(1, exhaust.asked)
    }

    @Test
    fun aControlUnitThatStopsAnsweringStopsTheWholeScan() = runTest(timeout = 120.seconds) {
        val vehicle = simulation.buildVehicle()
        // The gearbox goes quiet when the sweep starts.
        val sink = SessionEventSink { event ->
            if (event.name == SessionEvents.SCAN && event.detail.startsWith("stage 6")) vehicle.ecu(0x7E1).silent = true
        }
        val adapter = VirtualElm327(vehicle, VirtualElm327.Config(responseDelay = 30.milliseconds))
        val driver =
            Elm327Driver(adapter, backgroundScope, config = Elm327Driver.Config(timeSource = testTimeSource, warmUp = false, postResetDelay = Duration.ZERO))
        driver.initialize(initScript)
        val scan = FullScan(driver, plan, sink, testTimeSource)
        val result = scan.run()
        assertEquals(ScanOutcome.STOPPED, result.outcome)
        assertTrue(result.detail!!.contains("7E1") && result.detail!!.contains("stopped answering"), result.detail)
        // What had been found before is kept.
        assertEquals(8, result.ecus.size)
        assertEquals(listOf("probe", "readiness", "discovery", "identification", "faults"), result.stagesDone)
    }

    @Test
    fun aControlUnitThatIgnoresIdentifiersItDoesNotHaveIsNotWaitedOnForEveryOne() = runTest(timeout = 120.seconds) {
        val vehicle = simulation.buildVehicle()
        vehicle.ecu(0x7E4).refusesUnknownDids = false
        val result = env(vehicle).scan(plan, FullScan.Config(silentRunLimit = 24)).run()
        assertEquals(ScanOutcome.COMPLETED, result.outcome)
        val exhaust = result.sweeps.first { it.requestId == "7E4" }
        assertTrue(exhaust.asked < exhaust.planned / 4, "asked ${exhaust.asked} of ${exhaust.planned}")
        // It still answers the identifier it has, which sits in a range that is reached.
        assertEquals(setOf("D010"), exhaust.positive.map { it.did }.toSet())
        assertTrue(result.warnings.any { it.startsWith("exhaust treatment:") && "skipped" in it }, result.warnings.toString())
    }

    @Test
    fun aResponderThatAnswersSeveralRequestIdsIsListedOnce() = runTest(timeout = 30.seconds) {
        val base = simulation.buildVehicle()
        // A gateway that also answers requests to 710 from the same identifier as 700 does (708).
        val alias = VirtualEcu(0x710, 0x708).also { it.set("22F190", "AABBCC") }
        val env = env(VirtualVehicle(base.ecus + alias))
        val small = DidSweepPlan.parse(
            """{ "schemaVersion": 1, "id": "discovery/small", "title": "t", "requestsPerSecond": 20,
                "controlUnitIds": { "from": "700", "to": "71F" }, "ranges": [ { "from": "F400", "to": "F40F" } ],
                "targets": [ { "requestId": "7E1", "label": "gearbox" } ] }""",
        )
        val result = env.scan(small).run()
        assertEquals(1, result.ecus.count { it.responseId == "708" }, result.ecus.map { it.requestId to it.responseId }.toString())
        assertEquals("700", result.ecus.first { it.responseId == "708" }.requestId)
        assertTrue(result.warnings.any { it.contains("already listed") }, result.warnings.toString())
    }

    // The car moves ----------------------------------------------------------------------------------------

    @Test
    fun aCarThatMovesPausesTheScanAndPutsTheAdapterBackUntilItIsParkedAgain() = runTest(timeout = 120.seconds) {
        val env = env()
        var calls = 0
        val moves = launch {
            // Some time into the scan (the sweep has started by then) the car drives off.
            delay(150.seconds)
            env.vehicle.ecu(0x7E0).set("010D", "28")
        }
        val scan = env.scan(plan) { speed ->
            calls++
            assertEquals(40, speed)
            // Meanwhile nothing diagnostic is sent by the scan, and the adapter is back to its ordinary settings.
            val tail = env.adapter.received.takeLast(4).map { it.filterNot(Char::isWhitespace).uppercase() }
            assertTrue(tail.any { it == "ATCF7E8" } || calls > 1, tail.toString())
            if (calls == 3) env.vehicle.ecu(0x7E0).set("010D", "00")
            delay(2.seconds)
        }
        val result = scan.run()
        moves.cancel()

        assertEquals(ScanOutcome.COMPLETED, result.outcome)
        assertEquals(3, calls)
        assertTrue(result.pausedMs >= 4000, "paused ${result.pausedMs} ms")
        assertTrue(env.scanEvents.any { it.startsWith("paused: the car is moving (40 km/h)") }, env.scanEvents.toString())
        assertTrue(env.scanEvents.any { it.startsWith("goes on: the car is parked again") })
        // The wide receive filter was set again after the pause: the scan went on finding the body units' answers.
        assertTrue(env.adapter.received.count { it.filterNot(Char::isWhitespace).uppercase() == "ATCM700" } >= 2)
        assertEquals(8, result.ecus.size)
        assertEquals(6, result.sweeps.first { it.requestId == "7E1" }.positive.size)
    }

    @Test
    fun aCarThatAlreadyMovesAtTheStartIsWaitedFor() = runTest(timeout = 120.seconds) {
        val vehicle = simulation.buildVehicle()
        vehicle.ecu(0x7E0).set("010D", "0A")
        val env = env(vehicle)
        var calls = 0
        val result = env.scan(plan) {
            if (++calls == 2) vehicle.ecu(0x7E0).set("010D", "00")
            delay(1.seconds)
        }.run()
        assertEquals(2, calls)
        assertEquals(ScanOutcome.COMPLETED, result.outcome)
        // Nothing but the speed was asked while it moved.
        val firstScanRequest = env.requests().indexOfFirst { it != "010D1" }
        assertEquals(4, firstScanRequest, "the speed is read before the scan, once more after each wait, and nothing else")
        assertTrue(env.requests().take(firstScanRequest).all { it == "010D1" })
    }

    @Test
    fun aSpeedThatCannotBeReadMeansNoScan() = runTest(timeout = 120.seconds) {
        val vehicle = simulation.buildVehicle()
        vehicle.ecu(0x7E0).remove("010D")
        val env = env(vehicle)
        assertFailsWith<ScanRefusedException> { env.scan(plan).run() }
        assertTrue(env.requests().all { it.startsWith("010D") }, "only the speed was asked: ${env.requests()}")
    }

    @Test
    fun aSpeedThatStopsBeingReadableEndsTheScan() = runTest(timeout = 120.seconds) {
        val vehicle = simulation.buildVehicle()
        val env = env(vehicle)
        val sinkAfter = launch {
            delay(100.seconds)
            vehicle.ecu(0x7E0).remove("010D")
        }
        val result = env.scan(plan).run()
        sinkAfter.cancel()
        assertEquals(ScanOutcome.STOPPED, result.outcome)
        assertTrue(result.detail!!.contains("speed"), result.detail)
    }

    // Cancel ----------------------------------------------------------------------------------------------

    @Test
    fun aCancelledScanKeepsWhatItFoundPutsTheAdapterBackAndPublishesItsResult() = runTest(timeout = 120.seconds) {
        val env = env()
        val scan = env.scan(plan)
        val job = backgroundScope.launch { scan.run() }
        delay(120.seconds)
        job.cancel()
        job.join()
        val result = assertNotNull(scan.result.value)
        assertEquals(ScanOutcome.CANCELLED, result.outcome)
        assertTrue("sweep" !in result.stagesDone)
        assertEquals(8, result.ecus.size)
        assertFalse(scan.progress.value.running)
        val tail = env.adapter.received.takeLast(6).map { it.filterNot(Char::isWhitespace).uppercase() }
        assertTrue("ATCM7F8" in tail && "ATCF7E8" in tail, tail.toString())
        assertTrue(env.events.any { it.name == SessionEvents.SCAN_RESULT })
    }

    @Test
    fun theResultIsWrittenToTheLogAsOneLineOfJsonWithoutTheVin() = runTest(timeout = 120.seconds) {
        val env = env()
        val result = env.scan(plan).run()
        val line = env.events.last { it.name == SessionEvents.SCAN_RESULT }.detail
        assertFalse('\n' in line)
        assertEquals(result, ScanResult.fromJson(line))
        assertFalse("SIMHARRIER2020001" in line)
    }

    // Progress and battery --------------------------------------------------------------------------------

    @Test
    fun progressShowsTheStageAndTheBatteryIsReadAboutEveryThirtySeconds() = runTest(timeout = 120.seconds) {
        val env = env()
        val scan = env.scan(plan)
        val seen = ArrayList<Pair<ScanStage?, String?>>()
        val watcher = backgroundScope.launch { scan.progress.collect { seen += it.stage to it.subject } }
        val result = scan.run()
        watcher.cancel()
        assertTrue(seen.any { it.first == ScanStage.DISCOVERY })
        assertTrue(seen.any { it.first == ScanStage.SWEEP && it.second == "gearbox" })
        assertTrue(seen.any { it.first == ScanStage.IDENTIFICATION && it.second == "700" })
        val expected = (result.durationMs / 30_000).toInt()
        assertTrue(result.battery.size in expected..expected + 2, "${result.battery.size} readings in ${result.durationMs} ms")
        assertTrue(result.battery.all { it.text == "14.1V" })
        val gaps = result.battery.zipWithNext { a, b -> b.atMs - a.atMs }
        assertTrue(gaps.all { it in 30_000..34_000 }, gaps.toString())
    }

    // The adapter's model ---------------------------------------------------------------------------------

    @Test
    fun theSimulatedAdapterOnlyListensTo7E8Through7EFUntilToldOtherwise() = runTest {
        val env = env()
        suspend fun reply(id: Int) = env.driver.request(id, byteArrayOf(0x22, 0xF1.toByte(), 0x87.toByte()), expectedResponses = 1)
        assertTrue(reply(0x700).respondingIds.isEmpty(), "708 is not in the default filter")
        assertEquals(1, env.driver.command("ATCM700").size)
        env.driver.command("ATCF700")
        assertEquals(1, reply(0x700).respondingIds.size)
        assertEquals(0x708, reply(0x700).respondingIds.single())
        env.driver.command("ATCM7F8")
        env.driver.command("ATCF7E8")
        assertTrue(reply(0x700).respondingIds.isEmpty())
    }

    @Test
    fun flowControlThatGoesToAnotherIdentifierLeavesALongReplyCutAfterItsFirstFrame() = runTest {
        val env = env()
        env.driver.command("ATCM700")
        env.driver.command("ATCF700")
        val request = byteArrayOf(0x22, 0xF1.toByte(), 0x87.toByte())
        env.driver.command("ATFCSH701")
        env.driver.command("ATFCSD300000")
        env.driver.command("ATFCSM1")
        val cut = env.driver.request(0x700, request, expectedResponses = null)
        assertTrue(cut.messages.isEmpty() && cut.respondingIds.isNotEmpty(), "the ECU waits for a flow control that never comes")
        env.driver.command("ATFCSH700")
        val whole = env.driver.request(0x700, request, expectedResponses = null)
        assertEquals(1, whole.messages.size)
    }

    @Test
    fun aSmallPlanScansSmallAndSweepsOnlyItsTargets() = runTest(timeout = 30.seconds) {
        val env = env()
        val small = DidSweepPlan.parse(
            """{ "schemaVersion": 1, "id": "discovery/small", "title": "t", "requestsPerSecond": 20,
                "controlUnitIds": { "from": "700", "to": "70F" }, "ranges": [ { "from": "F400", "to": "F40F" } ],
                "targets": [ { "requestId": "7E1", "label": "gearbox" } ] }""",
        )
        val result = env.scan(small).run()
        assertEquals(ScanOutcome.COMPLETED, result.outcome)
        assertEquals(listOf("700", "701", "703", "705", "7E0", "7E1", "7E4"), result.ecus.map { it.requestId })
        assertEquals(16, result.sweeps.single().asked)
        assertEquals(1, result.sweeps.size)
    }
}
