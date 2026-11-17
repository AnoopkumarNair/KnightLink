package knightlink.core.tpms

import knightlink.core.signals.DefinitionException
import knightlink.core.signals.Expression
import knightlink.core.signals.ExpressionException
import knightlink.core.signals.Provenance
import knightlink.core.signals.SUPPORTED_SCHEMA_VERSION
import knightlink.core.signals.SignalValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Describes how one brand of BLE tyre sensor encodes its advertisement. */
@Serializable
public data class TpmsDefinition(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    /** Bluetooth company identifier as hex, most significant byte first (e.g. `BA06`). */
    val companyId: String,
    val provenance: Provenance,
    val fields: List<TpmsField>,
    val notes: String? = null,
    /** Where the sensor's own identifier sits in the payload, when the layout has one; it tells sensors apart without any device address. */
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
            if (definition.schemaVersion > SUPPORTED_SCHEMA_VERSION) {
                throw DefinitionException("'${definition.id}' uses unsupported schema ${definition.schemaVersion}")
            }
            definition.sensorId?.let {
                if (it.offset < 0 || it.length < 1) throw DefinitionException("'${definition.id}': sensorId needs offset >= 0 and length >= 1")
            }
            return definition
        }
    }
}

/** [length] bytes of the payload from [offset] (0 is `A`, the first byte after the company id). */
@Serializable
public data class TpmsByteRange(val offset: Int, val length: Int)

@Serializable
public data class TpmsField(
    /** `pressure_psi`, `temp_c`, ... The wheel position is added by the pairing layer, not here. */
    val id: String,
    /** Formula over the manufacturer payload: `A` is the first byte after the 2-byte company id. */
    val formula: String,
    val unit: String,
)

/** Decodes manufacturer-specific advertisement data using a [TpmsDefinition]. */
public class TpmsDecoder(public val definition: TpmsDefinition) {
    private val companyId: Int = definition.companyId.toIntOrNull(16)
        ?: throw DefinitionException("${definition.id}: companyId must be hex")

    private val fields: List<Pair<TpmsField, Expression>> = definition.fields.map { field ->
        try {
            field to Expression.compile(field.formula)
        } catch (e: ExpressionException) {
            throw DefinitionException("${definition.id}: field '${field.id}': ${e.message}", e)
        }
    }

    private val requiredBytes = fields.maxOfOrNull { it.second.requiredBytes } ?: 0

    /**
     * @param companyId the advertisement's company identifier
     * @param payload manufacturer data after the company identifier
     * @return null when the advertisement is from another manufacturer or is too short
     */
    public fun decode(companyId: Int, payload: ByteArray): List<SignalValue>? {
        if (companyId != this.companyId || payload.size < requiredBytes) return null
        return fields.map { (field, expression) -> SignalValue(field.id, expression.evaluate(payload), field.unit) }
    }

    /** The sensor identifier in [payload] as upper-case hex, or null when the layout has none or the payload is too short. */
    public fun sensorId(payload: ByteArray): String? {
        val range = definition.sensorId ?: return null
        if (payload.size < range.offset + range.length) return null
        return buildString {
            for (i in range.offset until range.offset + range.length) {
                val v = payload[i].toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }

    private companion object {
        const val HEX = "0123456789ABCDEF"
    }
}
