package knightlink.core.sim

import knightlink.core.common.toHex
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A simulated vehicle and what happens to it over time, loaded from /fixtures/sim.
 * Replies are raw hex so a scenario can be copied straight out of a capture.
 */
@Serializable
public data class SimulationFile(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    /** Definition id of the vehicle this simulates, used to decode what the simulator reports. */
    val definition: String,
    val ecus: List<Ecu>,
    val timeline: List<Step> = emptyList(),
) {
    @Serializable
    public data class Ecu(
        val id: String,
        val requestId: String,
        val responseId: String,
        /** A made-up 17-character VIN, served on service 09 PID 02. Never a real one. */
        val vin: String? = null,
        /** Request hex (service + PID) to reply data hex. */
        val data: Map<String, String> = emptyMap(),
    )

    @Serializable
    public data class Step(
        val atMs: Long,
        val ecu: String,
        val set: Map<String, String> = emptyMap(),
        val remove: List<String> = emptyList(),
        val note: String? = null,
    )

    public fun buildVehicle(): VirtualVehicle = VirtualVehicle(
        ecus.map { spec ->
            VirtualEcu(spec.requestId.toInt(16), spec.responseId.toInt(16)).also { ecu ->
                spec.data.forEach { (request, data) -> ecu.set(request, data) }
                spec.vin?.let {
                    require(it.length == VIN_LENGTH) { "$id: VIN must be $VIN_LENGTH characters" }
                    ecu.set("0902", "01" + it.encodeToByteArray().toHex())
                }
            }
        },
    )

    /** Applies the timeline to [vehicle] in real (or virtual test) time. Returns when the last step is applied. */
    public suspend fun play(vehicle: VirtualVehicle, onStep: (Step) -> Unit = {}) {
        val requestIds = ecus.associate { it.id to it.requestId.toInt(16) }
        var elapsed = Duration.ZERO
        for (step in timeline.sortedBy { it.atMs }) {
            val at = step.atMs.milliseconds
            if (at > elapsed) {
                delay(at - elapsed)
                elapsed = at
            }
            val target = vehicle.ecu(requestIds[step.ecu] ?: throw IllegalArgumentException("$id: unknown ECU '${step.ecu}'"))
            step.set.forEach { (request, data) -> target.set(request, data) }
            step.remove.forEach { target.remove(it) }
            onStep(step)
        }
    }

    public companion object {
        private const val VIN_LENGTH = 17
        private val json = Json { ignoreUnknownKeys = false }

        public fun parse(text: String): SimulationFile = json.decodeFromString(serializer(), text)
    }
}
