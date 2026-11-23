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
            "1001", // the default session without an address: only a request to a body unit may ask for it (see below)
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
    fun theDefaultSessionMayBeRequestedOnlyFromABodyUnit() {
        for (body in listOf(0x700, 0x701, 0x703, 0x705, 0x750, 0x7DE)) {
            assertNull(ReadOnlyPolicy.violation("1001", body), body.toString(16))
            assertNull(ReadOnlyPolicy.violation("10011", body), "with the response count")
        }
        for (obd in listOf(0x7DF, 0x7E0, 0x7E1, 0x7E4, 0x7E7)) assertNotNull(ReadOnlyPolicy.violation("1001", obd), obd.toString(16))
        assertNotNull(ReadOnlyPolicy.violation("1001"), "a raw command has no address")
        assertNotNull(ReadOnlyPolicy.violation("1001", 0x1000), "not an 11-bit identifier")
    }

    @Test
    fun everyOtherServiceThatChangesStateStaysRefusedEvenForABodyUnit() {
        val refused = listOf(
            "1003", "1002", "1004", "100301", "100101", "10", // other sessions, extra bytes, no sub-function
            "3E00", "3E80", // tester present
            "2701", "2702", // security access
            "2EF19000", // write data by identifier
            "310100FF", // routine control
            "1101", "1103", // ECU reset
            "14FFFFFF", // clear DTC information
            "2803", "2F123403", "3400", "3D00", "8502", "04", "0800",
        )
        for (request in refused) {
            assertNotNull(ReadOnlyPolicy.violation(request, 0x700), "$request to 700")
            assertNotNull(ReadOnlyPolicy.violation(request, 0x7E0), "$request to 7E0")
        }
    }

    @Test
    fun theReceiveAddressOfTheAdapterMayBeSetToOneIdentifierOrReset() {
        for (command in listOf("ATCRA708", "AT CRA 708", "ATCRA7E8", "ATCRA18DAF110", "ATCRA")) assertNull(ReadOnlyPolicy.violation(command), command)
        for (command in listOf("ATCRA70", "ATCRA7080", "ATCRAXYZ", "ATCRA 7 0 8 0 0")) assertNotNull(ReadOnlyPolicy.violation(command), command)
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
    fun theRequestHeaderMayOnlyBeADiagnosticRequestIdAndTheProtocolCloseCommandIsNotAllowed() {
        for (command in listOf("ATSH7E0", "ATSH7DF", "ATSH700", "ATSH7FF", "ATSH7E7")) assertNull(ReadOnlyPolicy.violation(command), command)
        for (command in listOf("ATSH0C9", "ATSH7E8", "ATSH7EF", "ATSH18DB33F1", "ATSH7E", "ATSH6FF", "ATCP18", "ATCP")) {
            assertNotNull(ReadOnlyPolicy.violation(command), command)
        }
    }

    @Test
    fun theReceiveAndFlowControlSettingsOfTheAdapterAreAllowed() {
        val allowed = listOf(
            "ATCF700", "AT CF 700", "ATCM700", "ATCM 7FF", "ATCF18DAF110", "ATFCSH700", "AT FC SH 700",
            "ATFCSD300000", "AT FC SD 30 00 00", "ATFCSD30", "ATFCSM0", "ATFCSM1", "ATCFC0", "ATCFC1",
        )
        for (command in allowed) assertNull(ReadOnlyPolicy.violation(command), command)
    }

    @Test
    fun theFlowControlDataMayOnlyBeClearToSendAndTheOtherSettingsOnlyTakeTheirOwnArguments() {
        val refused = listOf(
            "ATFCSD", // no data
            "ATFCSD3", // odd length
            "ATFCSD320000", // wait, not clear to send
            "ATFCSD3100", // overflow
            "ATFCSD0300", // does not start with 30
            "ATFCSD30000000000000", // longer than a flow-control frame
            "ATFCSM2", "ATFCSM", "ATFCSM01", // only the automatic and the user-defined mode
            "ATFCSH", "ATFCSH70", "ATFCSH7000", "ATCF", "ATCM12", "ATCFXYZ", "ATCMGGG",
            "ATFC", "ATFCSX1", // anything else under FC
        )
        for (command in refused) assertNotNull(ReadOnlyPolicy.violation(command), command)
    }

    @Test
    fun theReceiveSettingsDoNotOpenTheDoorToTheDiagnosticServicesThatWrite() {
        // They only change what the adapter listens to; a request is still judged by its service.
        for (request in listOf("04", "1003", "2EF19000", "3101FF00", "2701", "14FFFFFF")) assertNotNull(ReadOnlyPolicy.violation(request), request)
        assertNotNull(ReadOnlyPolicy.violation("ATPP0CSV01"))
        assertNotNull(ReadOnlyPolicy.violation("ATWM8110F13E"))
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
    fun theDefaultSessionReachesTheAdapterOnlyForABodyUnitAndNothingElseOfServiceTenDoes() = runTest {
        val adapter = adapter()
        val driver = driver(adapter)
        for (id in listOf(0x700, 0x7E0)) {
            for (payload in listOf("1002", "1003", "3E00", "2701", "2EF19000", "310100FF", "1101", "14FFFFFF")) {
                assertFailsWith<ReadOnlyViolationException> { driver.request(id, payload.hexToBytes(), expectedResponses = null) }
            }
        }
        assertFailsWith<ReadOnlyViolationException> { driver.request(0x7E0, "1001".hexToBytes(), expectedResponses = 1) }
        assertFailsWith<ReadOnlyViolationException> { driver.command("1001") }
        assertTrue(adapter.written.none { it.startsWith("10") || it.startsWith("3E") }, adapter.written.toString())

        driver.request(0x700, "1001".hexToBytes(), expectedResponses = 1)
        assertTrue(adapter.written.contains("ATSH700") && adapter.written.contains("10011"), adapter.written.toString())
    }

    @Test
    fun aDiagnosticRequestGoesOnlyToTheDiagnosticBlock() = runTest {
        for (id in listOf(0x700, 0x7DF, 0x7E0, 0x7E4, 0x7FF)) assertNull(ReadOnlyPolicy.requestIdViolation(id), id.toString(16))
        // The low identifiers carry the car's own broadcast: a data file must not be able to address them.
        for (id in listOf(0x000, 0x1A0, 0x2C0, 0x6FF, 0x800, -1)) assertNotNull(ReadOnlyPolicy.requestIdViolation(id), id.toString(16))
        assertTrue(ReadOnlyPolicy.isBodyUnit(0x7F0) && !ReadOnlyPolicy.isBodyUnit(0x1A0) && !ReadOnlyPolicy.isBodyUnit(0x7E4))

        val adapter = adapter()
        val driver = driver(adapter)
        for (id in listOf(0x1A0, 0x2C0, 0x6FF)) {
            assertFailsWith<ReadOnlyViolationException> { driver.request(id, "22F190".hexToBytes(), expectedResponses = null) }
        }
        assertTrue(adapter.written.none { it.startsWith("ATSH") || it.startsWith("22") }, adapter.written.toString())
    }

    @Test
    fun theHeaderIsSentRightBeforeEverySessionRequestEvenWhenItIsTheOneInUse() = runTest {
        val adapter = adapter()
        val driver = driver(adapter)
        driver.request(0x700, "22F190".hexToBytes(), expectedResponses = 1)
        driver.request(0x700, "1001".hexToBytes(), expectedResponses = 1)
        driver.request(0x700, "1001".hexToBytes(), expectedResponses = 1)
        driver.request(0x700, "22F191".hexToBytes(), expectedResponses = 1)
        val sent = adapter.written
        // ATSH700 once for the first request, then once more right before each 10 01; the plain request after them reuses it.
        assertEquals(listOf("ATSH700", "22F1901", "ATSH700", "10011", "ATSH700", "10011", "22F1911"), sent)
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
