package knightlink.app.session

import knightlink.core.recorder.EventKind
import knightlink.core.recorder.ExternalSamples
import knightlink.core.recorder.Recording
import knightlink.core.recorder.RecordingEvent
import knightlink.core.recorder.RecordingScrubber
import knightlink.core.scan.ScanSummary
import knightlink.core.vehicle.CapabilityReport
import knightlink.core.vehicle.UNKNOWN_MAKE
import knightlink.core.vehicle.VehicleIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What "Share this car's findings" is made of. Everything the consent screen lists is here, and nothing else goes into the file: no full VIN
 * (only the manufacturer code, its first three characters), no location, no name of the driver, no clock times. [bundleId] is random per export,
 * so two exports of the same car cannot be linked by the file.
 */
data class FindingsInput(
    val report: CapabilityReport?,
    val identity: VehicleIdentity?,
    val faults: FaultSummary,
    /** What the full scan found, when one ran; counts only, no values. */
    val scan: ScanSummary?,
    val appVersion: String,
    val bundleId: String,
)

object FindingsExport {
    const val CAPABILITIES_FILE = "capabilities.json"
    const val RECORDING_FILE = "recording-scrubbed.jsonl"
    const val SCAN_FILE = "scan-result.json"
    const val README_FILE = "README.txt"

    private val json = Json { prettyPrint = true }

    /** The capabilities file: facts about the car's electronics, never the VIN or a position. */
    fun capabilities(input: FindingsInput): String = json.encodeToString(JsonObject.serializer(), capabilitiesJson(input))

    internal fun capabilitiesJson(input: FindingsInput): JsonObject {
        val report = input.report
        val identity = input.identity
        // The manufacturer code is the first three characters of the VIN; the full VIN is never read out of the report.
        val wmi = report?.ecus?.firstNotNullOfOrNull { it.vin }?.take(WMI_LENGTH)
        return buildJsonObject {
            put("format", "knightlink-findings")
            put("formatVersion", FORMAT_VERSION)
            put("bundleId", input.bundleId)
            putJsonObject("app") {
                put("version", input.appVersion)
                put("adapterBanner", report?.adapter?.banner)
                put("adapterProtocolNumber", report?.adapter?.protocolNumber)
            }
            putJsonObject("vehicle") {
                put("protocol", identity?.protocol)
                put("wmi", wmi)
                put("modelYear", identity?.year)
                put("make", identity?.make?.takeIf { it != UNKNOWN_MAKE })
                put("model", identity?.model)
                put("supportTier", identity?.tier?.name)
            }
            put(
                "units",
                buildJsonArray {
                    for (ecu in report?.ecus.orEmpty()) {
                        add(
                            buildJsonObject {
                                put("requestId", ecu.requestId)
                                put("responseId", ecu.responseId)
                                put("name", ecu.name)
                                put("calibrationIds", stringArray(ecu.calibrationIds))
                                put("supportedPids", stringArray(ecu.supportedPids))
                            },
                        )
                    }
                },
            )
            put(
                "answeredIdentifiers",
                buildJsonObject { input.scan?.didsPerEcu?.toSortedMap()?.forEach { (unit, count) -> put(unit, count) } },
            )
            put(
                "faults",
                buildJsonArray {
                    for (fault in input.faults.items) {
                        add(
                            buildJsonObject {
                                put("code", fault.code)
                                put("kind", fault.kind.name.lowercase())
                                put("unit", fault.ecu)
                            },
                        )
                    }
                },
            )
            val readiness = input.faults.readiness
            if (readiness == null) {
                put("readiness", JsonNull)
            } else {
                putJsonObject("readiness") {
                    put("lampOn", readiness.milOn)
                    put("storedCount", readiness.storedCount)
                    put("dieselMonitors", readiness.compressionIgnition)
                    putJsonArray("monitors") {
                        for (m in readiness.monitors) {
                            add(
                                buildJsonObject {
                                    put("name", m.name)
                                    put("supported", m.supported)
                                    put("complete", m.complete)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun stringArray(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    /**
     * The scrubbed copy for the findings: the usual scrub (VIN removed), every GPS line dropped, and what the driver typed from the dashboard
     * (odometer and range lines and events) and the tyre sensors' advertisements left out, because the consent text does not list them.
     * Times in a recording are milliseconds since it started.
     */
    fun scrubbedRecording(recording: Recording): Recording {
        val scrubbed = RecordingScrubber.scrub(recording, dropGps = true).recording
        return scrubbed.copy(events = scrubbed.events.filterNot { it.leavesOutOfFindings() })
    }

    private fun RecordingEvent.leavesOutOfFindings(): Boolean = when (kind) {
        EventKind.EXTERNAL -> listOf(ExternalSamples.TPMS, ExternalSamples.ODOMETER, ExternalSamples.RANGE).any { payload == it || payload.startsWith("$it ") }
        EventKind.EVENT -> payload.startsWith(DASHBOARD_EVENT)
        else -> false
    }

    /** What the README inside the file says, for whoever opens it. */
    fun readme(): String =
        """
        KnightLink findings for one car.

        Included: the protocol, the make, model and model year, the manufacturer code (the first three characters of the VIN), the control
        units that answered with the data they support and their calibration ids, fault codes and emission readiness, app and adapter versions,
        a random id of this file, the recording of the car's conversation with the adapter (with the VIN scrubbed) together with the phone's
        motion readings and the drive mode buttons that were pressed, and the result of the full scan when one was run.

        Not included: the VIN, any location (no GPS at all), the driver's name, the odometer and range typed from the dashboard, tyre sensor
        readings, and any clock time (times in the recording count from its start).
        """.trimIndent()

    /**
     * Makes the findings file in one step: the capabilities, the scrubbed [recording] and [scanJson]. Refuses (throws) when the full VIN, or the
     * part of it after the manufacturer code, is found in anything that would go into the file, whatever the scrubber did.
     *
     * @throws IllegalStateException when a VIN of the report is in the file's text
     */
    fun write(out: File, input: FindingsInput, recording: Recording?, scanJson: String?) {
        val capabilities = capabilities(input)
        val recordingText = recording?.let { scrubbedRecording(it).toText() }
        val vins = input.report?.ecus?.mapNotNull { it.vin }?.filter { it.length > WMI_LENGTH }.orEmpty()
        for (text in listOfNotNull(capabilities, recordingText, scanJson, readme())) {
            for (vin in vins) {
                check(!text.contains(vin) && !text.contains(vin.drop(WMI_LENGTH))) { "the VIN is in the findings file" }
            }
        }
        writeZip(out, capabilities, recordingText, scanJson)
    }

    /**
     * Writes the findings zip to [out] (replacing it): the capabilities, the README, and when given the scrubbed recording and the scan result.
     */
    fun writeZip(out: File, capabilities: String, scrubbedRecording: String?, scanJson: String?) {
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun add(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add(README_FILE, readme())
            add(CAPABILITIES_FILE, capabilities)
            scrubbedRecording?.let { add(RECORDING_FILE, it) }
            scanJson?.let { add(SCAN_FILE, it) }
        }
    }

    private const val WMI_LENGTH = 3

    /** How the session's note of what the driver typed from the dashboard starts in a recording's event lines. */
    private const val DASHBOARD_EVENT = "app: dashboard "

    private const val FORMAT_VERSION = 1
}
