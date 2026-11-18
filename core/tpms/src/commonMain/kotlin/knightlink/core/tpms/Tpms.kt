package knightlink.core.tpms

import knightlink.core.signals.DefinitionException
import knightlink.core.signals.Expression
import knightlink.core.signals.ExpressionException
import knightlink.core.signals.Provenance
import knightlink.core.signals.SUPPORTED_SCHEMA_VERSION
import knightlink.core.signals.SignalValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where in an advertisement a sensor puts its data. */
public enum class TpmsDataKind {
    /** Manufacturer-specific data, filed under a 16-bit company identifier. */
    MANUFACTURER,

    /** Service data, filed under a 16-bit service UUID. */
    SERVICE,
}

/**
 * Describes how one kind of BLE tyre sensor encodes its advertisement. Exactly one of [companyId] (manufacturer data) and [serviceUuid]
 * (service data) says where the data is; [dataPrefix] is what that data starts with, so a listener can filter on it in hardware.
 */
@Serializable
public data class TpmsDefinition(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    /** Bluetooth company identifier as four hex digits, most significant first (e.g. `004C`), for manufacturer data. */
    val companyId: String? = null,
    /** 16-bit service UUID as four hex digits (e.g. `FEAA`), for service data. */
    val serviceUuid: String? = null,
    /** Hex the data starts with (e.g. a frame type); the formulas see it too, as `A`, `B`, ... Empty for none. */
    val dataPrefix: String = "",
    val provenance: Provenance,
    val fields: List<TpmsField>,
    val notes: String? = null,
    /** Where the sensor's own identifier sits in the data, when the layout has one; it tells sensors apart without any device address. */
    val sensorId: TpmsByteRange? = null,
) {
    public companion object {
        private val json = Json { ignoreUnknownKeys = false }

        public fun parse(text: String): TpmsDefinition {
            val definition = try {
                json.decodeFromString(serializer(), text)
            } catch (e: IllegalArgumentException) {
                throw DefinitionException("Invalid TPMS definition: ${e.message}", e)
            }
            val id = definition.id
            if (definition.schemaVersion > SUPPORTED_SCHEMA_VERSION) {
                throw DefinitionException("'$id' uses unsupported schema ${definition.schemaVersion}")
            }
            if ((definition.companyId == null) == (definition.serviceUuid == null)) {
                throw DefinitionException("'$id': give exactly one of companyId and serviceUuid")
            }
            val key = definition.companyId ?: definition.serviceUuid!!
            if (key.length != 4 || !key.all { it.isHexDigit() }) throw DefinitionException("'$id': '$key' is not four hex digits")
            if (definition.dataPrefix.length % 2 != 0 || !definition.dataPrefix.all { it.isHexDigit() }) {
                throw DefinitionException("'$id': dataPrefix '${definition.dataPrefix}' is not whole bytes of hex")
            }
            definition.sensorId?.let {
                if (it.offset < 0 || it.length < 1) throw DefinitionException("'$id': sensorId needs offset >= 0 and length >= 1")
            }
            return definition
        }

        private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}

/** [length] bytes of the data from [offset] (0 is `A`). */
@Serializable
public data class TpmsByteRange(val offset: Int, val length: Int)

@Serializable
public data class TpmsField(
    /** `pressure_psi`, `temp_c`, ... The wheel position is added by the pairing layer, not here. */
    val id: String,
    /** Formula over the data: `A` is its first byte (after the company id or service UUID, which are not part of the data). */
    val formula: String,
    val unit: String,
)

/** Decodes the advertisement data of one kind of tyre sensor using a [TpmsDefinition]. */
public class TpmsDecoder(public val definition: TpmsDefinition) {
    /** Where the data is. */
    public val kind: TpmsDataKind = if (definition.companyId != null) TpmsDataKind.MANUFACTURER else TpmsDataKind.SERVICE

    /** The company id or the 16-bit service UUID. */
    public val key: Int = (definition.companyId ?: definition.serviceUuid!!).toInt(HEX_RADIX)

    /** What the data starts with. */
    public val prefix: ByteArray = ByteArray(definition.dataPrefix.length / 2) { definition.dataPrefix.substring(2 * it, 2 * it + 2).toInt(HEX_RADIX).toByte() }

    private val fields: List<Pair<TpmsField, Expression>> = definition.fields.map { field ->
        try {
            field to Expression.compile(field.formula)
        } catch (e: ExpressionException) {
            throw DefinitionException("${definition.id}: field '${field.id}': ${e.message}", e)
        }
    }

    private val requiredBytes = maxOf(prefix.size, fields.maxOfOrNull { it.second.requiredBytes } ?: 0)

    /** Whether [data], found as [kind] under [key], is this layout's: the right place and the right prefix. */
    public fun matches(kind: TpmsDataKind, key: Int, data: ByteArray): Boolean =
        kind == this.kind && key == this.key && data.size >= prefix.size && prefix.indices.all { data[it] == prefix[it] }

    /**
     * @param data the manufacturer data after the company id, or the service data after the UUID
     * @return null when the data is not this layout's (see [matches]) or is too short
     */
    public fun decode(kind: TpmsDataKind, key: Int, data: ByteArray): List<SignalValue>? {
        if (!matches(kind, key, data) || data.size < requiredBytes) return null
        return fields.map { (field, expression) -> SignalValue(field.id, expression.evaluate(data), field.unit) }
    }

    /** The sensor identifier in [data] as upper-case hex, or null when the layout has none or the data is too short. */
    public fun sensorId(data: ByteArray): String? {
        val range = definition.sensorId ?: return null
        if (data.size < range.offset + range.length) return null
        return buildString {
            for (i in range.offset until range.offset + range.length) {
                val v = data[i].toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }

    private companion object {
        const val HEX = "0123456789ABCDEF"
        const val HEX_RADIX = 16
    }
}
