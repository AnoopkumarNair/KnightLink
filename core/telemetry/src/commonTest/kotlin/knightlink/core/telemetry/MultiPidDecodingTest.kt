package knightlink.core.telemetry

import knightlink.core.common.hexToBytes
import knightlink.core.protocol.DiagnosticResponse
import knightlink.core.signals.DefinitionJson
import knightlink.core.signals.SignalCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MultiPidDecodingTest {
    private val catalog = SignalCatalog.of(
        listOf(
            DefinitionJson.parse(
                """
                {"schemaVersion":1,"id":"c","kind":"vehicle","title":"C",
                 "ecus":[{"id":"ecm","name":"ECM","requestId":"7E0","responseId":"7E8"}],
                 "parameters":[
                  {"service":"01","pid":"04","provenance":"standard","length":1,"signals":[{"id":"load","formula":"A/2.55","unit":"percent"}]},
                  {"service":"01","pid":"0C","provenance":"standard","length":2,"signals":[{"id":"rpm","formula":"(256*A+B)/4","unit":"rpm"}]},
                  {"service":"01","pid":"0D","provenance":"standard","length":1,"signals":[{"id":"speed","formula":"A","unit":"km/h"}]},
                  {"service":"01","pid":"78","provenance":"standard","length":9,"signals":[
                     {"id":"egt1","formula":"(256*B+C)/10-40","unit":"degC"},{"id":"egt2","formula":"(256*D+E)/10-40","unit":"degC"}]},
                  {"service":"01","pid":"7E","provenance":"standard","signals":[{"id":"nolen","formula":"A","unit":"u"}]},
                  {"service":"22","pid":"F190","provenance":"standard","signals":[{"id":"did","formula":"A","unit":"u"}]}]}
                """,
            ),
        ),
    )
    private val decoder = TelemetryDecoder(catalog)

    private fun decode(service: Int, hex: String) = decoder.decode(DiagnosticResponse.Positive(0x7E8, service, hex.hexToBytes())).associate {
        it.signal.id to
            it.signal.value
    }

    @Test
    fun aSinglePidReplyDecodesAsBefore() {
        assertEquals(mapOf("rpm" to 985.0), decode(1, "0C 0F 64"))
    }

    @Test
    fun aReplyWithSeveralPidsIsCutByTheDeclaredLengths() {
        val readings = decode(1, "0C 0F 64 0D 0B 04 80")
        assertEquals(985.0, readings.getValue("rpm"))
        assertEquals(11.0, readings.getValue("speed"))
        assertEquals(50.196, readings.getValue("load"), 0.001)
    }

    @Test
    fun theReadingsKeepTheEcuAndTheOrderOfTheReply() {
        val readings = decoder.decode(DiagnosticResponse.Positive(0x7E8, 1, "0D 0B 0C 0F 64".hexToBytes()))
        assertEquals(listOf("speed", "rpm"), readings.map { it.signal.id })
        assertEquals(listOf("ecm", "ecm"), readings.map { it.ecu })
    }

    @Test
    fun aLongPidAmongOthers() {
        val readings = decode(1, "04 80 78 0F 06 F2 05 89 03 97 02 C9 0D 05")
        assertEquals(137.8, readings.getValue("egt1"), 0.0005)
        assertEquals(101.7, readings.getValue("egt2"), 0.0005)
        assertEquals(5.0, readings.getValue("speed"))
    }

    @Test
    fun aPidWithoutALengthTakesTheRestOfTheReply() {
        assertEquals(mapOf("nolen" to 9.0), decode(1, "7E 09 0D 0B"))
    }

    @Test
    fun aWrongDeclaredLengthDoesNotCutIntoDataThatIsNotAPid() {
        // 0D is declared as 1 byte but the ECU sent 2; the second byte (FE) is no known PID, so it is part of the reading.
        assertEquals(mapOf("speed" to 11.0), decode(1, "0D 0B FE"))
    }

    @Test
    fun anUnknownPidStopsTheWalkAndKeepsWhatCameBefore() {
        assertEquals(mapOf("speed" to 11.0), decode(1, "0D 0B 99 01 02"))
        assertEquals(emptyMap(), decode(1, "99 01"))
    }

    @Test
    fun aTruncatedLastPidIsLeftOutNotReportedAsZero() {
        val readings = decode(1, "0D 0B 0C 0F")
        assertEquals(11.0, readings.getValue("speed"))
        assertNull(readings["rpm"])
    }

    @Test
    fun otherServicesAreDecodedByTheirIdentifierAsBefore() {
        assertEquals(mapOf("did" to 7.0), decode(0x22, "F1 90 07"))
    }

    @Test
    fun theCountDigitIsOnlyOfferedForRepliesThatFitOneFrame() {
        fun count(pid: String) = catalog.find(1, pid)!!.singleFrameCount()
        assertEquals(1, count("0C")) // 41 0C + 2 bytes = 4 of 7
        assertNull(count("78")) // 41 78 + 9 bytes: multi-frame, the count would cut it to the first frame
        assertNull(count("7E")) // length unknown
    }

    @Test
    fun pollItemsCarryTheLengthAndTheCountRule() {
        val items = catalog.pollItems("ecm") { null }
        assertEquals(emptyList(), items)
        val all = catalog.pollItems("ecm") { kotlin.time.Duration.parse("1s") }.associateBy { it.id }
        assertEquals(1, all.getValue("ecm:010C").expectedResponses)
        assertEquals(2, all.getValue("ecm:010C").dataLength)
        assertNull(all.getValue("ecm:0178").expectedResponses)
        assertNull(all.getValue("ecm:017E").dataLength)
    }
}
