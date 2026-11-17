package knightlink.core.adapter

import knightlink.core.common.hexToBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** KnightLink never changes the vehicle's state and never floods its bus: the gate in front of every write, and the rate ceiling. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadOnlyGuardTest {
    // The policy ---------------------------------------------------------------------------------------------

    @Test
    fun readingServicesAreAllowed() {
        val reads = listOf("0100", "010C", "010C1", "0902", "0300", "03", "07", "0A", "0200", "0600")
        val udsReads = listOf("22F190", "22F1901", "1902FF", "190400FF01", "1906")
        for (request in reads + udsReads) {
            assertNull(ReadOnlyPolicy.violation(request), request)
        }
    }

    @Test
    fun everyServiceThatCanChangeTheVehicleIsRefused() {
        val writes = listOf(
            "04", // clear OBD codes
            "0800", // control of on-board systems
            "1003", // diagnostic session control (extended)
            "1001", // even the default session: not needed for reading
            "1101", // ECU reset
            "14FFFFFF", // clear DTC information
            "2701", // security access
            "2803", // communication control
            "2EF19000", // write data by identifier
            "2F123403", // input/output control
            "310100FF", // routine control
            "3400", "3500", "3601", "3700", // download / upload
            "3D00", // write memory
            "3E00", // tester present: harmless, but nothing reads with it, so not allowed until something needs it
            "8502", // control DTC setting
        )
        for (request in writes) assertNotNull(ReadOnlyPolicy.violation(request), request)
    }

    @Test
    fun readDtcInformationIsAllowedOnlyWithAReportingSubFunction() {
        assertNull(ReadOnlyPolicy.violation("1902FF"))
        assertNotNull(ReadOnlyPolicy.violation("19"))
        assertNotNull(ReadOnlyPolicy.violation("19FF"))
    }

    @Test
    fun adapterCommandsThatOnlyTouchTheAdapterAreAllowed() {
        val allowed = listOf("ATZ", "ATD", "ATWS", "ATI", "AT@1", "ATRV", "ATDPN", "ATE0", "ATL0", "ATS0", "ATH1") +
            listOf("ATSP6", "ATSP0", "ATAT1", "ATST32", "ATSH7E0", "AT SH 7E0", "ATCRA7E8", "ATCAF1", "AT")
        for (command in allowed) assertNull(ReadOnlyPolicy.violation(command), command)
    }

    @Test
    fun adapterCommandsThatWriteTheAdapterOrTalkOnTheBusByThemselvesAreRefused() {
        val refused = listOf("ATPP0CSV01", "ATPP0CON", "AT@3ABC", "ATCV1250", "ATWM8110F13E", "ATSW96", "ATBI") +
            listOf("ATMA", "ATMR10", "ATMT10", "ATSI", "ATFI", "ATSHXYZ", "STI", "VTI")
        for (command in refused) assertNotNull(ReadOnlyPolicy.violation(command), command)
    }

    @Test
    fun aBareCarriageReturnIsRefusedBecauseItRepeatsWhateverWasSentLast() {
        assertNotNull(ReadOnlyPolicy.violation(""))
        assertNotNull(ReadOnlyPolicy.violation("  "))
    }

    @Test
    fun anAdapterDefinitionCannotCarryARefusedCommand() {
        val error = assertFailsWith<IllegalArgumentException> {
            AdapterDefinition.parse("""{"schemaVersion":1,"id":"a","title":"X","chipset":"elm327","initScript":["ATZ","ATPP0CSV01"]}""")
        }
        assertTrue(error.message!!.contains("ATPP0CSV01"), error.message)
    }

    // The driver -----------------------------------------------------------------------------------------------

    private fun TestScope.adapter(): FakeAdapter = FakeAdapter({ currentTime }) { command ->
        val key = command.filterNot { it.isWhitespace() }.uppercase()
        if (key.startsWith("AT")) reply("OK") else reply("7E804410C0C7C")
    }

    private fun TestScope.driver(adapter: FakeAdapter, config: Elm327Driver.Config = Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO)) =
        Elm327Driver(adapter, backgroundScope, config = config)

    @Test
    fun aRefusedRequestNeverReachesTheAdapter() = runTest {
        val adapter = adapter()
        val driver = driver(adapter)
        for (payload in listOf("04", "14FFFFFF", "2EF19000", "3101FF00", "1101")) {
            assertFailsWith<ReadOnlyViolationException> { driver.request(0x7E0, payload.hexToBytes(), expectedResponses = null) }
        }
        assertFailsWith<ReadOnlyViolationException> { driver.command("ATPP0CSV01") }
        assertFailsWith<ReadOnlyViolationException> { driver.command("") }
        // Not even the header for the refused requests went out: the gate runs before every write.
        assertTrue(
            adapter.written.none {
                it.startsWith("04") ||
                    it.startsWith("14") ||
                    it.startsWith("2E") ||
                    it.startsWith("31") ||
                    it.startsWith("11") ||
                    it.startsWith("ATPP")
            },
            adapter.written.toString(),
        )
    }

    @Test
    fun anInitScriptWithARefusedCommandFailsBeforeSendingIt() = runTest {
        val adapter = adapter()
        assertFailsWith<ReadOnlyViolationException> { driver(adapter).initialize(listOf("ATZ", "ATE0", "ATWM8110F13E", "ATH1")) }
        assertEquals(listOf("ATZ", "ATE0"), adapter.written)
    }

    @Test
    fun requestsAreNeverCloserTogetherThanTheMinimumInterval() = runTest {
        val clock = kotlin.time.TestTimeSource()
        val adapter = adapter()
        val driver =
            driver(adapter, Elm327Driver.Config(warmUp = false, postResetDelay = Duration.ZERO, minRequestInterval = 20.milliseconds, timeSource = clock))
        driver.request(0x7E0, "010C".hexToBytes(), expectedResponses = 1)
        val before = currentTime
        // The test clock has not moved, as if the adapter answered instantly: the driver must still wait the full interval.
        driver.request(0x7E0, "010C".hexToBytes(), expectedResponses = 1)
        assertTrue(currentTime - before >= 20, "waited ${currentTime - before} ms")
    }

    @Test
    fun theRateCeilingCannotBeLiftedAboveOneHundredRequestsASecond() {
        assertFailsWith<IllegalArgumentException> { Elm327Driver.Config(minRequestInterval = 5.milliseconds) }
    }
}
