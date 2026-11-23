package knightlink.app.session

import knightlink.core.recorder.EventKind
import knightlink.core.recorder.Recording
import knightlink.core.recorder.RecordingEvent
import knightlink.core.recorder.RecordingHeader
import knightlink.core.vehicle.AdapterInfo
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.DerivedFacts
import knightlink.core.vehicle.EcuReport
import knightlink.core.vehicle.FuelMethod
import knightlink.core.vehicle.Timing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FindingsExportTest {
    private val fullVin = "MAT123456789ABCDE"

    private fun report(): CapabilityReport {
        val ecu = EcuReport("7E0", "7E8", "Engine", listOf("CAL123"), listOf("04", "05", "0C", "0D"), fullVin)
        return CapabilityReport(
            adapter = AdapterInfo("ELM327 v2.2", null, null, null, "A6"),
            timing = Timing(100.0, 3, 40.0, 25.0),
            ecus = listOf(ecu),
            derived = DerivedFacts(FuelMethod.UNKNOWN, hasDpfData = false, duplicates = emptyList()),
        )
    }

    private fun input() = FindingsInput(
        report = report(),
        identity = DemoDrive.identity,
        faults = DemoDrive.frames[7].faults,
        scan = null,
        appVersion = "1.2.3",
        bundleId = "bundle-1",
    )

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun theCapabilitiesHoldTheFactsAndOnlyTheManufacturerCodeOfTheVin() {
        val text = FindingsExport.capabilities(input())
        assertFalse(text.contains(fullVin), "the full VIN is in the file")
        assertFalse(text.contains("123456789"), "part of the VIN is in the file")
        val json = parse(text)
        assertEquals("bundle-1", json["bundleId"]?.jsonPrimitive?.content)
        val vehicle = json["vehicle"]!!.jsonObject
        assertEquals("MAT", vehicle["wmi"]?.jsonPrimitive?.content)
        assertEquals("2022", vehicle["modelYear"]?.jsonPrimitive?.content)
        assertEquals(DemoDrive.MAKE, vehicle["make"]?.jsonPrimitive?.content)
        assertEquals(DemoDrive.MODEL, vehicle["model"]?.jsonPrimitive?.content)
        val unit = json["units"]!!.jsonArray.single().jsonObject
        assertEquals("7E0", unit["requestId"]?.jsonPrimitive?.content)
        assertEquals(listOf("CAL123"), unit["calibrationIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("04", "05", "0C", "0D"), unit["supportedPids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("1.2.3", json["app"]!!.jsonObject["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun faultsAndReadinessAreIncludedWithoutAnyLocationOrName() {
        val json = parse(FindingsExport.capabilities(input()))
        val faults = json["faults"]!!.jsonArray
        assertEquals(3, faults.size)
        assertEquals("P0420", faults[0].jsonObject["code"]?.jsonPrimitive?.content)
        assertEquals("confirmed", faults[0].jsonObject["kind"]?.jsonPrimitive?.content)
        val readiness = json["readiness"]!!.jsonObject
        assertEquals(8, readiness["monitors"]!!.jsonArray.size)
        val text = FindingsExport.capabilities(input()).lowercase()
        for (word in listOf("latitude", "longitude", "gps", "location", "owner", "nickname", "demo car")) assertFalse(text.contains(word), word)
    }

    @Test
    fun withoutAReportTheFileIsStillValidAndSaysNothingAboutAVin() {
        val json = parse(FindingsExport.capabilities(input().copy(report = null, identity = null, faults = FaultSummary.NOT_READ)))
        assertEquals(JsonArray(emptyList()), json["units"])
        assertTrue(json["vehicle"]!!.jsonObject["wmi"] is kotlinx.serialization.json.JsonNull)
        assertNotNull(json["readiness"])
    }

    @Test
    fun theScrubbedRecordingHasNoGpsAndKeepsRelativeTimes() {
        val recording = Recording(
            RecordingHeader("adapter", "app, bluetooth"),
            listOf(
                RecordingEvent.open(0),
                RecordingEvent.external(500, "gps 12.97162 77.59463 41.0 90 5"),
                RecordingEvent.external(900, "mode CITY"),
                RecordingEvent.close(1500),
            ),
        )
        val scrubbed = FindingsExport.scrubbedRecording(recording)
        assertTrue(scrubbed.events.none { it.kind == EventKind.EXTERNAL && it.payload.startsWith("gps") })
        assertEquals(listOf(0L, 900L, 1500L), scrubbed.events.map { it.timeMs })
        assertFalse(scrubbed.toText().contains("12.97"))
    }

    @Test
    fun theZipHasTheReadmeTheCapabilitiesAndTheOptionalFiles() {
        val file = File.createTempFile("findings", ".zip")
        try {
            FindingsExport.writeZip(file, "{}", "recording text", "{\"scan\":1}")
            ZipFile(file).use { zip ->
                assertEquals(
                    setOf(FindingsExport.README_FILE, FindingsExport.CAPABILITIES_FILE, FindingsExport.RECORDING_FILE, FindingsExport.SCAN_FILE),
                    zip.entries().asSequence().map { it.name }.toSet(),
                )
                val readme = zip.getInputStream(zip.getEntry(FindingsExport.README_FILE)).readBytes().decodeToString()
                assertTrue(readme.contains("Not included: the VIN"))
                assertEquals("recording text", zip.getInputStream(zip.getEntry(FindingsExport.RECORDING_FILE)).readBytes().decodeToString())
            }
            FindingsExport.writeZip(file, "{}", null, null)
            ZipFile(file).use { zip -> assertEquals(2, zip.size()) }
        } finally {
            file.delete()
        }
    }

    // The VIN never leaves --------------------------------------------------------------------------------------------

    /** The adapter's text for the multi-frame answer to 09 02 (spaces on, one unit at 7E8), cut into the three frames of a real reply. */
    private fun vinReply(vin: String): String {
        val payload = byteArrayOf(0x49, 0x02, 0x01) + vin.encodeToByteArray()
        fun hex(bytes: ByteArray) = bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
        val lines = ArrayList<String>()
        lines += "7E8 " + hex(byteArrayOf(0x10, payload.size.toByte()) + payload.copyOfRange(0, 6))
        var offset = 6
        var sequence = 1
        while (offset < payload.size) {
            val end = minOf(payload.size, offset + 7)
            lines += "7E8 " + hex(byteArrayOf((0x20 or sequence).toByte()) + payload.copyOfRange(offset, end))
            offset = end
            sequence++
        }
        return lines.joinToString("\r", postfix = "\r\r>")
    }

    private fun sessionRecording(): Recording = Recording(
        RecordingHeader("adapter", "app, bluetooth"),
        listOf(
            RecordingEvent.open(0),
            RecordingEvent.tx(100, "0902\r".encodeToByteArray()),
            RecordingEvent.rx(140, vinReply(fullVin).encodeToByteArray()),
            RecordingEvent.event(150, "probe: vehicle identification $fullVin read"),
            RecordingEvent.external(500, "gps 12.97162 77.59463 41.0 90 5"),
            RecordingEvent.external(600, "odo 48210 start"),
            RecordingEvent.external(610, "dte 310 start"),
            RecordingEvent.external(620, "tpms 004C 0215AABBCCDD -60"),
            RecordingEvent.external(700, "mode CITY"),
            RecordingEvent.external(800, "mot 20 0.10 0.20 9.80 0.001 0.002 0.003"),
            RecordingEvent.event(900, "app: dashboard start: odometer 48210 km, range 310 km"),
            RecordingEvent.close(1500),
        ),
    )

    private fun zipTexts(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes().decodeToString() }
    }

    @Test
    fun theVinIsNowhereInTheZipNotEvenAsBytesAndNoPositionOrDashboardEntryIs() {
        val file = File.createTempFile("findings", ".zip")
        try {
            val scan = "{\"ecus\":[{\"vin\":\"MAT**************\"}]}"
            FindingsExport.write(file, input(), sessionRecording(), scan)
            val texts = zipTexts(file)
            assertEquals(
                setOf(FindingsExport.README_FILE, FindingsExport.CAPABILITIES_FILE, FindingsExport.RECORDING_FILE, FindingsExport.SCAN_FILE),
                texts.keys,
            )
            val vinTail = fullVin.drop(3)
            val asHex = vinTail.encodeToByteArray().joinToString("") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
            val spaced = vinTail.encodeToByteArray().joinToString(" ") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
            for ((name, text) in texts) {
                assertFalse(text.contains(fullVin), "$name holds the VIN")
                assertFalse(text.contains(vinTail), "$name holds the VIN after its manufacturer code")
                assertFalse(text.contains(asHex) || text.contains(spaced), "$name holds the VIN as hex bytes")
                for (word in listOf("12.97", "77.59", "gps", "48210", "310 km", "odo ", "dte ", "tpms", "AABBCCDD")) {
                    assertFalse(text.contains(word), "$name holds '$word'")
                }
            }
            val recording = texts.getValue(FindingsExport.RECORDING_FILE)
            assertTrue(recording.contains("mode CITY") && recording.contains("mot 20"), "the motion and the drive mode stay, the consent says so")
            assertTrue(texts.getValue(FindingsExport.CAPABILITIES_FILE).contains("\"wmi\": \"MAT\""))
        } finally {
            file.delete()
        }
    }

    @Test
    fun aVinThatSlipsIntoAnyPartOfTheFileStopsTheExportAndNothingIsWritten() {
        val file = File.createTempFile("findings", ".zip")
        file.delete()
        assertFailsWith<IllegalStateException> { FindingsExport.write(file, input(), sessionRecording(), "{\"vin\":\"$fullVin\"}") }
        assertFalse(file.exists(), "no file is left behind")
        // The same for a recording in which the scrubber could not know the VIN (no 09 02 exchange in it): the report's VIN is still looked for.
        val text = Recording(RecordingHeader("a", "b"), listOf(RecordingEvent.open(0), RecordingEvent.event(5, "x $fullVin y"), RecordingEvent.close(9)))
        assertFailsWith<IllegalStateException> { FindingsExport.write(file, input(), text, null) }
        assertFalse(file.exists())
    }

    @Test
    fun theConsentTextListsWhatTheZipHoldsAndLeavesOutWhatItDoesNot() {
        val strings = File("src/main/res/values/strings.xml").readText()
        fun string(name: String) = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(strings)!!.groupValues[1]
        val included = string("findings_included")
        val excluded = string("findings_excluded")
        val includedItems = listOf(
            "make, model and model year",
            "first three characters",
            "control units",
            "calibration ids",
            "Fault codes",
            "adapter versions",
            "random id",
            "motion readings",
            "full scan",
        )
        for (item in includedItems) {
            assertTrue(included.contains(item), "the consent does not say '$item' is included")
        }
        for (item in listOf("The VIN", "no GPS", "name", "odometer", "tyre sensor", "Clock times")) {
            assertTrue(excluded.contains(item), "the consent does not say '$item' is left out")
        }
        // The README inside the file says the same things.
        val readme = FindingsExport.readme()
        for (item in listOf("manufacturer code", "calibration ids", "random id", "motion readings", "full scan", "odometer", "tyre sensor", "no GPS")) {
            assertTrue(readme.contains(item), "the README does not mention '$item'")
        }
    }
}
