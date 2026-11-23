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
}
