package knightlink.core.signals

/** A decoded reading. [inRange] is false when the value falls outside the definition's plausible range. */
public data class SignalValue(
    val id: String,
    val value: Double,
    val unit: String,
    val inRange: Boolean = true,
)

public class CompiledSignal internal constructor(
    public val definition: SignalDefinition,
    public val expression: Expression,
)

/** A [ParameterDefinition] with its formulas compiled once. */
public class CompiledParameter internal constructor(
    public val definition: ParameterDefinition,
    public val signals: List<CompiledSignal>,
) {
    public val service: Int = definition.service.toInt(16)
    public val pid: String = definition.pid.uppercase()

    /** Data bytes after the PID in a reply, when the definition says. */
    public val length: Int? get() = definition.length

    /** How often this parameter is wanted. */
    public val tier: PollTier get() = definition.poll ?: PollTier.NORMAL

    /**
     * Decodes the data bytes that follow the PID. Signals whose bytes are missing (short reply) or whose
     * formula yields a non-finite number are left out rather than reported as zero.
     */
    public fun decode(data: ByteArray): List<SignalValue> = signals.mapNotNull { signal ->
        if (data.size < signal.expression.requiredBytes) return@mapNotNull null
        val value = signal.expression.evaluate(data)
        if (!value.isFinite()) return@mapNotNull null
        val def = signal.definition
        val inRange = (def.min == null || value >= def.min) && (def.max == null || value <= def.max)
        SignalValue(def.id, value, def.unit, inRange)
    }
}

/**
 * The parameters known for one vehicle: definition layers merged in order, later layers overriding
 * earlier ones for the same service + PID + ECU.
 */
public class SignalCatalog private constructor(
    public val ecus: List<EcuDefinition>,
    private val byKey: Map<Key, CompiledParameter>,
) {
    private data class Key(val service: Int, val pid: String, val ecu: String?)

    public val parameters: Collection<CompiledParameter> get() = byKey.values

    /** Finds the entry for a request. An ECU-specific entry wins over a generic one. */
    public fun find(service: Int, pid: String, ecu: String? = null): CompiledParameter? {
        val key = pid.uppercase()
        return (if (ecu != null) byKey[Key(service, key, ecu)] else null) ?: byKey[Key(service, key, null)]
    }

    public fun ecuByResponseId(canId: Int): EcuDefinition? = ecus.firstOrNull { it.responseCanId == canId }

    public companion object {
        /** @throws DefinitionException if any formula, id or reference is invalid. */
        public fun of(layers: List<DefinitionFile>): SignalCatalog {
            val ecus = LinkedHashMap<String, EcuDefinition>()
            val byKey = LinkedHashMap<Key, CompiledParameter>()
            for (layer in layers) {
                for (ecu in layer.ecus) {
                    check(layer, ecu.requestId.toIntOrNull(16) != null && ecu.responseId.toIntOrNull(16) != null) {
                        "ECU '${ecu.id}' has a non-hex CAN id"
                    }
                    ecus[ecu.id] = ecu
                }
                for (parameter in layer.parameters) {
                    val service = parameter.service.toIntOrNull(16)
                    check(layer, service != null && parameter.pid.toLongOrNull(16) != null) {
                        "parameter ${parameter.service} ${parameter.pid}: service and pid must be hex"
                    }
                    check(layer, parameter.ecu == null || parameter.ecu in ecus) {
                        "parameter ${parameter.service} ${parameter.pid} refers to unknown ECU '${parameter.ecu}'"
                    }
                    check(layer, parameter.signals.isNotEmpty()) {
                        "parameter ${parameter.service} ${parameter.pid} has no signals"
                    }
                    check(layer, parameter.length == null || parameter.length in 1..MAX_REPLY_BYTES) {
                        "parameter ${parameter.service} ${parameter.pid}: length must be 1..$MAX_REPLY_BYTES"
                    }
                    val compiled = parameter.signals.map { signal ->
                        val expression = try {
                            Expression.compile(signal.formula)
                        } catch (e: ExpressionException) {
                            throw DefinitionException("${layer.id}: signal '${signal.id}': ${e.message}", e)
                        }
                        CompiledSignal(signal, expression)
                    }
                    byKey[Key(service!!, parameter.pid.uppercase(), parameter.ecu)] = CompiledParameter(parameter, compiled)
                }
            }
            return SignalCatalog(ecus.values.toList(), byKey)
        }

        /** The longest service 01 reply data: an ISO-TP message holds 4095 bytes, but formulas read A..Z. */
        private const val MAX_REPLY_BYTES = 26

        private inline fun check(layer: DefinitionFile, condition: Boolean, message: () -> String) {
            if (!condition) throw DefinitionException("${layer.id}: ${message()}")
        }
    }
}
