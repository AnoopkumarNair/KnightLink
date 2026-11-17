package knightlink.core.vehicle

import knightlink.core.protocol.ElmResponse
import knightlink.core.protocol.ElmStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilityProbeBusFaultTest {
    /** A link whose bus is down: the adapter answers every request with [status] instead of data. */
    private class FaultyBusLink(private val status: ElmStatus) : ProbeLink {
        override suspend fun command(text: String): List<String> = FakeLink.DEFAULT_COMMANDS[text] ?: listOf("?")

        override suspend fun request(requestId: Int, payload: ByteArray, expectedResponses: Int?): ElmResponse =
            ElmResponse(emptyList(), listOf(status), emptyList(), emptyList())
    }

    @Test
    fun noEcuAndABusStatusWordIsReportedAsABusThatCannotBeReached() = runTest {
        for (status in listOf(ElmStatus.CAN_ERROR, ElmStatus.UNABLE_TO_CONNECT, ElmStatus.BUS_ERROR)) {
            val report = CapabilityProbe(FaultyBusLink(status), config = CapabilityProbe.Config(benchmarkSamples = 0)).run()
            assertTrue(report.ecus.isEmpty())
            assertEquals(status.name, report.busFault)
            assertTrue(report.warnings.any { it.startsWith("vehicle bus not reachable") }, report.warnings.toString())
        }
    }

    @Test
    fun plainSilenceIsNotABusFault() = runTest {
        val report = CapabilityProbe(FakeLink(), config = CapabilityProbe.Config(benchmarkSamples = 0)).run()
        assertTrue(report.ecus.isEmpty())
        assertNull(report.busFault)
        assertTrue(report.warnings.none { it.contains("bus") })
    }

    @Test
    fun theReportRoundTripsThroughJsonWithTheNewFields() = runTest {
        val report = CapabilityProbe(FaultyBusLink(ElmStatus.CAN_ERROR), config = CapabilityProbe.Config(benchmarkSamples = 0)).run()
            .let { it.copy(adapter = it.adapter.copy(unsupportedCommands = listOf("ATAT1"))) }
        assertEquals(report, CapabilityReport.fromJson(report.toJson()))
    }
}
