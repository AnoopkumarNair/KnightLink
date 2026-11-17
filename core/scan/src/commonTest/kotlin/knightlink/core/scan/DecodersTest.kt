package knightlink.core.scan

import knightlink.core.common.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecodersTest {
    // Fault codes ------------------------------------------------------------------------------------------

    @Test
    fun anSaeCodeIsReadFromItsTwoBytes() {
        assertEquals("P0123", DtcDecoder.sae(0x01, 0x23))
        assertEquals("P0A1F", DtcDecoder.sae(0x0A, 0x1F))
        assertEquals("C0300", DtcDecoder.sae(0x43, 0x00))
        assertEquals("B1A0C", DtcDecoder.sae(0x9A, 0x0C))
        assertEquals("U0100", DtcDecoder.sae(0xC1, 0x00))
        assertEquals("U3FFF", DtcDecoder.sae(0xFF, 0xFF))
    }

    @Test
    fun anObdReplyListsItsCodesAndPaddingIsIgnored() {
        assertEquals(listOf("P0A1F", "P0123"), DtcDecoder.obd("020A1F0123".hexToBytes()))
        assertEquals(emptyList(), DtcDecoder.obd("00".hexToBytes()))
        assertEquals(emptyList(), DtcDecoder.obd("0200000000".hexToBytes()))
        assertEquals(emptyList(), DtcDecoder.obd(ByteArray(0)))
        // A cut pair is not a code.
        assertEquals(listOf("P0123"), DtcDecoder.obd("02012302".hexToBytes()))
    }

    @Test
    fun aUdsReplyListsCodesWithTheirStatusAndTheFailureTypeWhenThereIsOne() {
        val codes = DtcDecoder.uds("FF0A1F1F09" + "01230004" + "C1000010", ::hexOf)
        assertEquals(listOf(StatusDtc("P0A1F-1F", "09"), StatusDtc("P0123", "04"), StatusDtc("U0100", "10")), codes)
        assertEquals(emptyList(), DtcDecoder.uds("FF".hexToBytes()))
        assertEquals(emptyList(), DtcDecoder.uds(ByteArray(0)))
    }

    private fun hexOf(text: String): ByteArray = text.hexToBytes()

    private fun DtcDecoder.uds(text: String, parse: (String) -> ByteArray): List<StatusDtc> = uds(parse(text))

    @Test
    fun aCodeWhoseTestHasNotRunIsNotAFault() {
        assertEquals(setOf(FaultClass.NOT_COMPLETED_ONLY), DtcStatus.classes(0x10))
        assertEquals(setOf(FaultClass.NOT_COMPLETED_ONLY), DtcStatus.classes(0x50))
        assertEquals(setOf(FaultClass.NOT_COMPLETED_ONLY), DtcStatus.classes(0x00))
        assertEquals(setOf(FaultClass.NOT_COMPLETED_ONLY), DtcStatus.classes(0x40))
    }

    @Test
    fun theStatusBitsSortACodeIntoTheClassesThatApply() {
        assertEquals(setOf(FaultClass.CONFIRMED), DtcStatus.classes(0x08))
        assertEquals(setOf(FaultClass.CONFIRMED, FaultClass.WARNING_LAMP), DtcStatus.classes(0x88))
        assertEquals(setOf(FaultClass.PENDING), DtcStatus.classes(0x04))
        assertEquals(setOf(FaultClass.CONFIRMED, FaultClass.PENDING), DtcStatus.classes(0x0C))
        assertEquals(setOf(FaultClass.WARNING_LAMP), DtcStatus.classes(0x80))
        // Failing now, or since the codes were cleared, but neither pending nor confirmed.
        assertEquals(setOf(FaultClass.FAILED_TEST), DtcStatus.classes(0x01))
        assertEquals(setOf(FaultClass.FAILED_TEST), DtcStatus.classes(0x20))
    }

    @Test
    fun theFaultListKeepsTheNotCompletedCodesApartAndCountsThem() {
        val codes = DtcDecoder.uds("FF" + "0A1F1F09" + "01230004" + "0299000C" + "C1000010" + "C1010050" + "C1020000" + "C1030080", ::hexOf)
        val faults = DtcDecoder.classify(codes)
        assertEquals(7, faults.total)
        assertEquals(listOf("P0A1F-1F", "P0299"), faults.confirmed.map { it.code })
        assertEquals(listOf("P0123", "P0299"), faults.pending.map { it.code })
        assertEquals(listOf("U0103"), faults.warningLamp.map { it.code })
        assertEquals(3, faults.notCompletedOnly)
        assertEquals(setOf("P0A1F-1F", "P0299", "P0123", "U0103"), faults.faultCodes)
        assertFalse("U0100" in faults.faultCodes)
    }

    // Readiness ---------------------------------------------------------------------------------------------

    @Test
    fun theMonitorStatusOfADieselNamesItsOwnMonitors() {
        // 01: lamp off, one code; B: three continuous monitors supported, compression ignition; C: NOx, boost, exhaust gas, PM filter and others.
        val status = assertNotNull(MonitorStatus.decode("010FEB40".hexToBytes(), withLamp = true))
        assertEquals(false, status.milOn)
        assertEquals(1, status.dtcCount)
        assertTrue(status.compressionIgnition)
        val byName = status.monitors.associateBy { it.name }
        assertEquals(Monitor("misfire", true, true), byName["misfire"])
        assertEquals(Monitor("particulate filter", true, false), byName["particulate filter"], "its not-complete bit is set")
        assertEquals(Monitor("NOx/SCR aftertreatment", true, true), byName["NOx/SCR aftertreatment"])
        assertEquals(Monitor("boost pressure", true, true), byName["boost pressure"])
        assertNull(byName["catalyst"], "a spark-ignition monitor does not exist on a diesel")
    }

    @Test
    fun theMonitorStatusOfAPetrolCarUsesTheOtherNames() {
        val status = assertNotNull(MonitorStatus.decode("81070000".hexToBytes(), withLamp = true))
        assertEquals(true, status.milOn)
        assertEquals(1, status.dtcCount)
        assertFalse(status.compressionIgnition)
        assertEquals(setOf("misfire", "fuel system", "components"), status.monitors.filter { it.supported }.map { it.name }.toSet())
        assertTrue(status.monitors.any { it.name == "catalyst" && !it.supported && it.complete == null })
    }

    @Test
    fun aNotCompleteBitMeansTheMonitorHasNotRun() {
        val status = assertNotNull(MonitorStatus.decode("0017".hexToBytes() + "0000".hexToBytes(), withLamp = false))
        // B = 0x17: misfire, fuel and components supported; bit 4 says misfire is not complete.
        assertEquals(Monitor("misfire", true, false), status.monitors.first { it.name == "misfire" })
        assertEquals(Monitor("fuel system", true, true), status.monitors.first { it.name == "fuel system" })
        assertNull(status.milOn)
    }

    @Test
    fun aStatusThatIsTooShortIsNotRead() {
        assertNull(MonitorStatus.decode("010F".hexToBytes(), withLamp = true))
    }

    // Service 06 --------------------------------------------------------------------------------------------

    @Test
    fun aSupportBitmapListsTheMonitorIdsAndSaysWhetherAnotherRangeFollows() {
        val (mids, next) = Mode06Decoder.supported(0, "C0000001".hexToBytes())
        assertEquals(listOf(1, 2), mids)
        assertTrue(next)
        val (higher, last) = Mode06Decoder.supported(0x20, "00000100".hexToBytes())
        assertEquals(listOf(0x20 + 24), higher)
        assertFalse(last)
    }

    @Test
    fun theTestsOfAMonitorAreReadAndCheckedAgainstTheirLimits() {
        // Two nine-byte results: mid 01, test 80, scaling 0B, value 0x0123, limits 0..FFFF; and test 81 with a value above its maximum.
        val data = "01800B01230000FFFF" + "01810B00500000004F"
        val results = Mode06Decoder.results(data.hexToBytes())
        val mid = results.single()
        assertEquals("01", mid.mid)
        assertEquals(2, mid.tests.size)
        assertEquals(TestResult("80", "0B", 0x0123, 0, 0xFFFF, true), mid.tests[0])
        assertFalse(mid.tests[1].withinLimits)
    }

    @Test
    fun aReplyThatRepeatsTheMonitorIdOnlyOnceIsReadToo() {
        val results = Mode06Decoder.results("01" + "800B01230000FFFF" + "810B00500000FFFF").let { it }
        assertEquals(2, results.single().tests.size)
        assertEquals("81", results.single().tests[1].testId)
    }

    @Test
    fun aReplyOfTheWrongSizeGivesNoResults() {
        assertEquals(emptyList(), Mode06Decoder.results("0180".hexToBytes()))
        assertEquals(emptyList(), Mode06Decoder.results(ByteArray(0)))
    }

    private fun Mode06Decoder.results(text: String): List<MidResult> = results(text.hexToBytes())
}
