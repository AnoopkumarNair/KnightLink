package knightlink.core.adapter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Describes one adapter model: how to reach it and how to bring it up. Loaded from /definitions/adapters. */
@Serializable
public data class AdapterDefinition(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    val chipset: String,
    val firmware: String? = null,
    val ble: BleProfile? = null,
    /** Commands sent in order after connecting. */
    val initScript: List<String>,
    val notes: String? = null,
) {
    @Serializable
    public data class BleProfile(
        /** Advertised device names that identify this adapter. */
        val names: List<String> = emptyList(),
        val serviceUuid: String,
        val writeCharacteristicUuid: String,
        val notifyCharacteristicUuid: String,
        /** Services that look usable but are not the serial channel. */
        val ignoreServiceUuids: List<String> = emptyList(),
    )

    public companion object {
        private val json = Json { ignoreUnknownKeys = false }

        public fun parse(text: String): AdapterDefinition = json.decodeFromString(serializer(), text)
    }
}
