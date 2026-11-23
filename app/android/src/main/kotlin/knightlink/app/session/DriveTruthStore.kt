package knightlink.app.session

import knightlink.app.ble.StringStore
import knightlink.core.runtime.drivetruth.DriveEvent
import knightlink.core.runtime.drivetruth.DriveEventKind
import knightlink.core.runtime.drivetruth.DriveTruthNote
import knightlink.core.runtime.drivetruth.DriveTruthReport
import knightlink.core.runtime.drivetruth.FuelSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The last drive of each car, kept in the phone's small settings store so that the report card is still there after the app restarts. The key
 * is the car's garage key (make and the end of the VIN), never the VIN. A damaged or unknown-format entry reads as "no drive", never as an error.
 */
class DriveTruthStore(private val store: StringStore) {
    fun save(carKey: String, report: DriveTruthReport) {
        store.put(field(carKey), encode(report))
    }

    fun load(carKey: String): DriveTruthReport? = decode(store.get(field(carKey)))

    private fun field(carKey: String) = "drive_truth.$carKey"

    companion object {
        private const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(report: DriveTruthReport): String = buildJsonObject {
            put("v", VERSION)
            put("duration", report.durationMinutes)
            put("moving", report.movingMinutes)
            put("idle", report.idleMinutes)
            put("carKm", report.carDistanceKm)
            put("gpsKm", report.gpsDistanceKm)
            put("carVsGps", report.carVsGpsPercent)
            put("litres", report.fuelLitres)
            put("source", report.fuelSource?.name)
            put("cost", report.costRupees)
            put("costPerKm", report.costPerKmRupees)
            put("idleCost", report.idleCostRupees)
            put("kmPerLitre", report.kmPerLitre)
            put("notes", buildJsonArray { report.notes.forEach { add(JsonPrimitive(it.name)) } })
            put(
                "events",
                buildJsonArray {
                    for (e in report.events) {
                        add(
                            buildJsonObject {
                                put("at", e.atSeconds)
                                put("kind", e.kind.name)
                                put("detail", e.detail)
                            },
                        )
                    }
                },
            )
        }.toString()

        /** The report [text] holds, or null for nothing, a text of another version, or damage. Unknown notes, sources and events are left out, not fatal. */
        fun decode(text: String?): DriveTruthReport? {
            if (text.isNullOrBlank()) return null
            return try {
                val o = json.parseToJsonElement(text).jsonObject
                if (o["v"]?.jsonPrimitive?.doubleOrNull?.toInt() != VERSION) return null
                DriveTruthReport(
                    durationMinutes = o.number("duration") ?: return null,
                    movingMinutes = o.number("moving") ?: return null,
                    idleMinutes = o.number("idle") ?: return null,
                    carDistanceKm = o.number("carKm"),
                    gpsDistanceKm = o.number("gpsKm"),
                    carVsGpsPercent = o.number("carVsGps"),
                    fuelLitres = o.number("litres"),
                    fuelSource = o.text("source")?.let { name -> FuelSource.entries.firstOrNull { it.name == name } },
                    costRupees = o.number("cost"),
                    costPerKmRupees = o.number("costPerKm"),
                    idleCostRupees = o.number("idleCost"),
                    kmPerLitre = o.number("kmPerLitre"),
                    notes = (o["notes"] as? JsonArray).orEmpty().mapNotNull { n ->
                        DriveTruthNote.entries.firstOrNull { it.name == n.jsonPrimitive.content }
                    },
                    events = (o["events"] as? JsonArray).orEmpty().mapNotNull { e ->
                        val obj = e.jsonObject
                        val kind = obj.text("kind")?.let { name -> DriveEventKind.entries.firstOrNull { it.name == name } } ?: return@mapNotNull null
                        DriveEvent(obj.number("at") ?: return@mapNotNull null, kind, obj.text("detail"))
                    },
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

        private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
    }
}
