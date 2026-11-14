package knightlink.core.vehicle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityProbeTest {
    private val vin = "SIMTESTCAR0000001"

    // 0x05 and 0x0C, and nothing further.
    private val smallMap = "4100 08100000"

    private fun engine(vararg extra: Pair<String, Script>) = EcuScript(
        responseId = 0x7E8,
        replies = mapOf("0100" to reply(smallMap)) + extra,
    )

    private fun probe(link: FakeLink, config: CapabilityProbe.Config = CapabilityProbe.Config(benchmarkSamples = 0)) =
        CapabilityProbe(link, config = config)

    // --- stage 1: adapter -----------------------------------------------------------------------------------

    @Test
    fun adapterStageReadsBannerDescriptionVoltageAndProtocol() = runTest {
        val report = probe(FakeLink(mapOf(0x7E0 to engine()))).run()
        assertEquals(
            AdapterInfo(banner = "ELM327 v2.3", description = "OBDII to RS232 Interpreter", voltageText = "14.1V", voltage = 14.1, protocolNumber = "A6"),
            report.adapter,
        )
    }

    @Test
    fun adapterAnsweringQuestionMarkLeavesTheFieldEmpty() = runTest {
        val link = FakeLink(
            mapOf(0x7E0 to engine()),
            commandReplies = mapOf("ATI" to listOf("ELM327 v1.5"), "ATRV" to listOf("?")),
        )
        val adapter = probe(link).run().adapter
        assertEquals(AdapterInfo("ELM327 v1.5", null, null, null, null), adapter)
    }

    @Test
    fun voltageTextThatIsNotANumberIsKeptAsTextOnly() = runTest {
        for (text in listOf("LOW", "NaN", "V")) {
            val link = FakeLink(mapOf(0x7E0 to engine()), commandReplies = FakeLink.DEFAULT_COMMANDS + ("ATRV" to listOf(text)))
            val adapter = probe(link).run().adapter
            assertEquals(text, adapter.voltageText)
            assertNull(adapter.voltage, text)
        }
    }

    @Test
    fun voltageWithSpaceBeforeTheUnitStillParses() = runTest {
        val link = FakeLink(mapOf(0x7E0 to engine()), commandReplies = FakeLink.DEFAULT_COMMANDS + ("ATRV" to listOf("12.4 V")))
        assertEquals(12.4, probe(link).run().adapter.voltage)
    }

    @Test
    fun adapterThatDoesNotAnswerItsOwnCommandsIsAProbeFailure() = runTest {
        val link = FakeLink(commandError = IllegalStateException("link down"))
        val error = assertFailsWith<ProbeException> { probe(link).run() }
        assertEquals("link down", error.cause?.message)
        assertTrue(link.requests.isEmpty(), "no point sweeping when the adapter is dead")
    }

    // --- stage 2: sweep -------------------------------------------------------------------------------------

    @Test
    fun sweepAsksEveryPhysicalAddressForServiceZeroOnePidZeroZero() = runTest {
        val link = FakeLink()
        val report = probe(link).run()
        assertEquals((0x7E0..0x7E7).map { it to "0100" }, link.requests)
        assertTrue(report.ecus.isEmpty())
        assertEquals(DerivedFacts(FuelMethod.UNKNOWN, false, emptyList()), report.derived)
        assertNull(report.timing.medianRoundTripMs)
    }

    @Test
    fun ecusAreReportedInRequestOrderWithTheResponseIdTakenFromTheReply() = runTest {
        val link = FakeLink(
            mapOf(
                0x7E4 to EcuScript(0x7EC, mapOf("0100" to reply(smallMap))),
                0x7E0 to EcuScript(0x7E8, mapOf("0100" to reply(smallMap))),
                // The reply id deliberately does not follow the usual request + 8 rule.
                0x7E2 to EcuScript(0x7E8, mapOf("0100" to reply(smallMap, from = 0x7EA))),
            ),
        )
        val ecus = probe(link).run().ecus
        assertEquals(listOf("7E0" to "7E8", "7E2" to "7EA", "7E4" to "7EC"), ecus.map { it.requestId to it.responseId })
    }

    @Test
    fun negativeReplyToTheSweepIsNotAnEcu() = runTest {
        val link = FakeLink(mapOf(0x7E1 to EcuScript(0x7E9, mapOf("0100" to reply("7F 01 11")))))
        assertTrue(probe(link).run().ecus.isEmpty())
    }

    @Test
    fun failingAddressDoesNotAbortTheSweep() = runTest {
        val link = FakeLink(
            mapOf(
                0x7E0 to EcuScript(0x7E8, mapOf("0100" to Script.Fail(IllegalStateException("adapter timeout")))),
                0x7E1 to engine().let { EcuScript(0x7E9, it.replies) },
                0x7E3 to EcuScript(0x7EB, mapOf("0100" to Script.Fail(IllegalStateException("boom")))),
            ),
        )
        val report = probe(link).run()
        assertEquals(listOf("7E1"), report.ecus.map { it.requestId })
        assertEquals(listOf("7E0: request failed: adapter timeout", "7E3: request failed: boom"), report.warnings)
    }

    @Test
    fun everyAddressFailingIsAProbeFailure() = runTest {
        val failure = IllegalStateException("bus off")
        val ecus = (0x7E0..0x7E7).associateWith { EcuScript(it + 8, mapOf("0100" to Script.Fail(failure))) }
        val error = assertFailsWith<ProbeException> { probe(FakeLink(ecus)).run() }
        assertEquals(failure, error.cause)
    }

    @Test
    fun cancellationIsNeverSwallowedByTheSweep() = runTest {
        val link = FakeLink(mapOf(0x7E0 to EcuScript(0x7E8, mapOf("0100" to Script.Fail(CancellationException("cancelled"))))))
        assertFailsWith<CancellationException> { probe(link).run() }
    }

    @Test
    fun cancellationIsNeverSwallowedInLaterStages() = runTest {
        val link = FakeLink(mapOf(0x7E0 to engine("0900" to Script.Fail(CancellationException("cancelled")))))
        assertFailsWith<CancellationException> { probe(link).run() }
    }

    @Test
    fun cancellationIsNeverSwallowedByTheAdapterStage() = runTest {
        val link = FakeLink(commandError = CancellationException("cancelled"))
        assertFailsWith<CancellationException> { probe(link).run() }
    }

    // --- stage 3: service 01 support ------------------------------------------------------------------------

    @Test
    fun supportMapIsSortedAndExcludesRangePids() = runTest {
        val link = FakeLink(mapOf(0x7E0 to engine()))
        assertEquals(listOf("05", "0C"), probe(link).run().ecus.single().supportedPids)
    }

    @Test
    fun supportMapFollowsTheNextRangeBitUntilItIsClear() = runTest {
        val link = FakeLink(
            mapOf(
                0x7E0 to EcuScript(
                    0x7E8,
                    mapOf(
                        "0100" to reply("41 00 BE 1F A8 13"),
                        "0120" to reply("41 20 80 00 00 01"),
                        "0140" to reply("41 40 40 00 00 00"),
                        // Must never be asked: 0140 had no next-range flag.
                        "0160" to reply("41 60 FF FF FF FF"),
                    ),
                ),
            ),
        )
        val ecu = probe(link).run().ecus.single()
        assertEquals(
            listOf("01", "03", "04", "05", "06", "07", "0C", "0D", "0E", "0F", "10", "11", "13", "15", "1C", "1F", "21", "42"),
            ecu.supportedPids,
        )
        // After the eight sweep requests, only the follow-on ranges are read.
        assertEquals(listOf("0120", "0140"), link.requests.map { it.second }.filter { it.startsWith("01") }.drop(8))
        assertTrue(link.requests.none { it.second == "0160" })
    }

    @Test
    fun theSweepReplyIsReusedSoTheFirstRangeIsReadOnce() = runTest {
        val link = FakeLink(mapOf(0x7E0 to engine()))
        probe(link).run()
        assertEquals(1, link.requests.count { it == 0x7E0 to "0100" })
    }

    @Test
    fun supportChainStopsAtTheLastRange() = runTest {
        // Every range claims a successor, so only the 0xE0 limit ends the chain.
        val replies = (0x00..0xE0 step 0x20).associate { base -> "01" + base.toPidHex() to reply("41 ${base.toPidHex()} 00 00 00 01") }
        val link = FakeLink(mapOf(0x7E0 to EcuScript(0x7E8, replies)))
        val report = probe(link).run()
        assertTrue(report.ecus.single().supportedPids.isEmpty())
        val asked = link.requests.map { it.second }.filter { it.startsWith("01") && it != "0100" }
        assertEquals(listOf("0120", "0140", "0160", "0180", "01A0", "01C0", "01E0"), asked)
    }

    @Test
    fun unansweredRangeEndsTheChainButKeepsWhatWasRead() = runTest {
        val link = FakeLink(mapOf(0x7E0 to EcuScript(0x7E8, mapOf("0100" to reply("41 00 08 00 00 01")))))
        val report = probe(link).run()
        assertEquals(listOf("05"), report.ecus.single().supportedPids)
        assertEquals(listOf("7E0: no usable answer to 01 20; support map may be incomplete"), report.warnings)
    }

    @Test
    fun rangeThatFailsWithAnExceptionIsReportedAndKeepsWhatWasRead() = runTest {
        val link = FakeLink(
            mapOf(
                0x7E0 to EcuScript(
                    0x7E8,
                    mapOf("0100" to reply("41 00 08 00 00 01"), "0120" to Script.Fail(IllegalStateException("timeout"))),
                ),
            ),
        )
        val report = probe(link).run()
        assertEquals(listOf("05"), report.ecus.single().supportedPids)
        assertTrue(report.warnings.any { it.contains("01 20 failed: timeout") }, report.warnings.toString())
    }

    @Test
    fun replyTooShortForABitmapGivesAnEmptyMapAndAWarning() = runTest {
        val link = FakeLink(mapOf(0x7E0 to EcuScript(0x7E8, mapOf("0100" to reply("41 00 08")))))
        val report = probe(link).run()
        assertEquals("7E0", report.ecus.single().requestId, "a positive reply is enough to count as an ECU")
        assertTrue(report.ecus.single().supportedPids.isEmpty())
        assertEquals(1, report.warnings.size)
    }

    @Test
    fun answersFromOtherEcusAreIgnoredWhenReadingOneEcu() = runTest {
        val link = FakeLink(
            mapOf(
                0x7E0 to EcuScript(
                    0x7E8,
                    mapOf("0100" to reply("41 00 80 00 00 01"), "0120" to reply("41 20 80 00 00 00", from = 0x7E9)),
                ),
            ),
        )
        val report = probe(link).run()
        assertEquals(listOf("01"), report.ecus.single().supportedPids)
    }

    // --- stage 4: service 09 --------------------------------------------------------------------------------

    private val infoBitmap = "49 00 50 40 00 00" // 02, 04 and 0A

    @Test
    fun vinNameAndCalibrationIdsAreDecodedFromNulPaddedFields() = runTest {
        val link = FakeLink(
            mapOf(
                0x7E0 to engine(
                    "0900" to reply(infoBitmap),
                    "0902" to reply("49 02 01 ${vin.asciiHex()}"),
                    "090A" to reply("49 0A 01 ${"ECM-EngineControl".asciiHex()} 00 00 00"),
                    "0904" to reply("49 04 02 ${"SIM-CAL-A".asciiHex()} ${"00".repeat(7)} ${"SIM-CAL-B".asciiHex()} ${"00".repeat(7)}"),
                ),
            ),
        )
        val ecu = probe(link).run().ecus.single()
        assertEquals(vin, ecu.vin)
        assertEquals("ECM-EngineControl", ecu.name)
        assertEquals(listOf("SIM-CAL-A", "SIM-CAL-B"), ecu.calibrationIds)
    }

    @Test
    fun paddingWithSpacesAndStrayControlBytesIsStripped() = runTest {
        val link = FakeLink(
            mapOf(
                0x7E0 to engine(
                    "0900" to reply(infoBitmap),
                    "0902" to reply("49 02 01 00 00 ${vin.asciiHex()}"),
                    "090A" to reply("49 0A 01 ${"ECM".asciiHex()} 0D 00 ${"  ".asciiHex()}"),
                    "0904" to reply("49 04 01 ${"CAL1".asciiHex()} ${"20".repeat(12)}"),
                ),
            ),
        )
        val ecu = probe(link).run().ecus.single()
        assertEquals(vin, ecu.vin)
        assertEquals("ECM", ecu.name)
        assertEquals(listOf("CAL1"), ecu.calibrationIds)
    }

    @Test
    fun calibrationIdsAreLimitedToTheAnnouncedCount() = runTest {
        val link = FakeLink(
            mapOf(
                0x7E0 to engine(
                    "0900" to reply(infoBitmap),
                    "0904" to
                        reply("49 04 02 ${"AAA".asciiHex()} ${"00".repeat(13)} ${"BBB".asciiHex()} ${"00".repeat(13)} ${"CCC".asciiHex()} ${"00".repeat(13)}"),
                ),
            ),
        )
        assertEquals(listOf("AAA", "BBB"), probe(link).run().ecus.single().calibrationIds)
    }

    @Test
    fun blankCalibrationIdsAreDropped() = runTest {
        val link = FakeLink(
            mapOf(
                0x7E0 to engine(
                    "0900" to reply(infoBitmap),
                    "0904" to reply("49 04 03 ${"AAA".asciiHex()} ${"00".repeat(13)} ${"00".repeat(16)} ${"CCC".asciiHex()} ${"00".repeat(13)}"),
                ),
            ),
        )
        // Three announced, the middle one is empty.
        assertEquals(listOf("AAA", "CCC"), probe(link).run().ecus.single().calibrationIds)
    }

    @Test
    fun negativeReplyToAnInfoRequestLeavesThatFieldEmpty() = runTest {
        val link = FakeLink(
            mapOf(
                0x7E0 to engine(
                    "0900" to reply(infoBitmap),
                    "0902" to reply("49 02 01 ${vin.asciiHex()}"),
                    "0904" to reply("7F 09 12"),
                    "090A" to reply("7F 09 31"),
                ),
            ),
        )
        val ecu = probe(link).run().ecus.single()
        assertEquals(vin, ecu.vin)
        assertNull(ecu.name)
        assertEquals(emptyList(), ecu.calibrationIds)
    }

    @Test
    fun infoItemsNotListedInTheBitmapAreNotRequested() = runTest {
        val link = FakeLink(
            mapOf(
                0x7E0 to engine(
                    "0900" to reply("49 00 40 00 00 00"), // only 02
                    "0902" to reply("49 02 01 ${vin.asciiHex()}"),
                    "0904" to reply("49 04 01 ${"X".asciiHex()}"),
                    "090A" to reply("49 0A 01 ${"X".asciiHex()}"),
                ),
            ),
        )
        val ecu = probe(link).run().ecus.single()
        assertEquals(vin, ecu.vin)
        assertNull(ecu.name)
        assertEquals(listOf("0900", "0902"), link.requests.map { it.second }.filter { it.startsWith("09") })
    }

    @Test
    fun ecuWithoutServiceNineIsNotAskedForItsItems() = runTest {
        val link = FakeLink(mapOf(0x7E0 to engine()))
        val ecu = probe(link).run().ecus.single()
        assertNull(ecu.vin)
        assertNull(ecu.name)
        assertEquals(emptyList(), ecu.calibrationIds)
        assertEquals(listOf("0900"), link.requests.map { it.second }.filter { it.startsWith("09") })
    }

    @Test
    fun emptyVinFieldBecomesNull() = runTest {
        val link = FakeLink(mapOf(0x7E0 to engine("0900" to reply("49 00 40 00 00 00"), "0902" to reply("49 02 01 ${"00".repeat(17)}"))))
        assertNull(probe(link).run().ecus.single().vin)
    }

    // --- stage 5: benchmark ---------------------------------------------------------------------------------

    @Test
    fun benchmarkSendsTheConfiguredNumberOfRequestsToTheFirstEcu() = runTest {
        val link = FakeLink(mapOf(0x7E1 to engine(), 0x7E5 to engine()))
        probe(link, CapabilityProbe.Config(benchmarkSamples = 4)).run()
        // One sweep request plus four samples, all to the lowest request id that answered.
        assertEquals(5, link.requests.count { it == 0x7E1 to "0100" })
        assertEquals(1, link.requests.count { it == 0x7E5 to "0100" })
    }

    @Test
    fun benchmarkDefaultIsTenSamples() = runTest {
        val link = FakeLink(mapOf(0x7E0 to engine()))
        CapabilityProbe(link, config = CapabilityProbe.Config()).run()
        assertEquals(11, link.requests.count { it == 0x7E0 to "0100" })
    }

    @Test
    fun benchmarkReportsMedianRoundTripAndRequestRateOnVirtualTime() = runTest {
        val link = FakeLink(mapOf(0x7E0 to engine()), latency = { _, _, _ -> 45.milliseconds })
        val report = CapabilityProbe(link, testTimeSource, CapabilityProbe.Config(benchmarkSamples = 10)).run()
        assertEquals(10, report.timing.benchmarkSamples)
        assertEquals(45.0, report.timing.medianRoundTripMs)
        assertEquals(1000.0 / 45.0, assertNotNull(report.timing.estimatedRequestsPerSecond), 1e-9)
    }

    @Test
    fun benchmarkMedianIgnoresOutliers() = runTest {
        // nth 1 is the sweep request; samples are nth 2..6 with these latencies.
        val delays = mapOf(2 to 40, 3 to 500, 4 to 50, 5 to 60, 6 to 45)
        val link = FakeLink(mapOf(0x7E0 to engine()), latency = { _, hex, nth -> if (hex == "0100") (delays[nth] ?: 0).milliseconds else Duration.ZERO })
        val report = CapabilityProbe(link, testTimeSource, CapabilityProbe.Config(benchmarkSamples = 5)).run()
        assertEquals(50.0, report.timing.medianRoundTripMs)
        assertEquals(20.0, report.timing.estimatedRequestsPerSecond)
    }

    @Test
    fun totalProbeTimeIsMeasuredWithTheTimeSource() = runTest {
        val link = FakeLink(mapOf(0x7E0 to engine()), latency = { _, _, _ -> 10.milliseconds })
        val report = CapabilityProbe(link, testTimeSource, CapabilityProbe.Config(benchmarkSamples = 0)).run()
        // 8 sweep requests and one 09 00 request, 10 ms each.
        assertEquals(90.0, report.timing.probeMs)
    }

    @Test
    fun unansweredBenchmarkRequestsAreNotCountedAndAreReported() = runTest {
        // nth 1 is the sweep; samples are nth 2..5, of which 3 and 4 get no answer (and take 300 ms to find out).
        val link = FakeLink(
            mapOf(0x7E0 to engine()),
            latency = { _, hex, nth -> if (hex == "0100" && nth > 1) (if (nth in 3..4) 300 else 40).milliseconds else Duration.ZERO },
            silentWhen = { _, hex, nth -> hex == "0100" && nth in 3..4 },
        )
        val report = CapabilityProbe(link, testTimeSource, CapabilityProbe.Config(benchmarkSamples = 4)).run()
        assertEquals(2, report.timing.benchmarkSamples)
        assertEquals(40.0, report.timing.medianRoundTripMs)
        assertEquals(listOf("7E0: 2 of 4 benchmark requests were not answered"), report.warnings)
    }

    @Test
    fun benchmarkWithoutAnyAnswerHasNoTiming() = runTest {
        val link = FakeLink(mapOf(0x7E0 to engine()), silentWhen = { _, hex, nth -> hex == "0100" && nth > 1 })
        val report = CapabilityProbe(link, testTimeSource, CapabilityProbe.Config(benchmarkSamples = 3)).run()
        assertEquals(0, report.timing.benchmarkSamples)
        assertNull(report.timing.medianRoundTripMs)
        assertNull(report.timing.estimatedRequestsPerSecond)
    }

    @Test
    fun zeroLatencyGivesNoRequestRateInsteadOfInfinity() = runTest {
        val report = CapabilityProbe(FakeLink(mapOf(0x7E0 to engine())), testTimeSource, CapabilityProbe.Config(benchmarkSamples = 3)).run()
        assertEquals(0.0, report.timing.medianRoundTripMs)
        assertNull(report.timing.estimatedRequestsPerSecond)
    }

    // --- whole probe ----------------------------------------------------------------------------------------

    @Test
    fun stagesAreReportedInOrder() = runTest {
        val stages = ArrayList<ProbeStage>()
        probe(FakeLink(mapOf(0x7E0 to engine()))).run(onProgress = { stages += it })
        assertEquals(ProbeStage.entries.toList(), stages)
    }

    @Test
    fun theProbeOnlyEverSendsReadOnlyTraffic() = runTest {
        val link = FakeLink(
            mapOf(
                0x7E0 to engine(
                    "0900" to reply(infoBitmap),
                    "0902" to reply("49 02 01 ${vin.asciiHex()}"),
                    "0904" to reply("49 04 01 ${"CAL".asciiHex()}"),
                    "090A" to reply("49 0A 01 ${"ECM".asciiHex()}"),
                ),
            ),
        )
        probe(link, CapabilityProbe.Config(benchmarkSamples = 3)).run()
        assertTrue(link.requests.isNotEmpty())
        assertTrue(link.requests.all { it.second.length == 4 && it.second.take(2) in setOf("01", "09") }, link.requests.toString())
        assertEquals(listOf("ATI", "AT@1", "ATRV", "ATDPN"), link.commands)
    }

    @Test
    fun negativeBenchmarkSamplesAreRejected() {
        assertFailsWith<IllegalArgumentException> { CapabilityProbe.Config(benchmarkSamples = -1) }
    }
}
