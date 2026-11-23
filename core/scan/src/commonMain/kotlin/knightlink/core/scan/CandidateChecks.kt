package knightlink.core.scan

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Highest schema of the candidate file this build reads. */
public const val SUPPORTED_CANDIDATES_SCHEMA: Int = 1

/**
 * Data identifiers worth one try on one control unit, outside any scan: [dids] and [ranges] (inclusive, four hex digits each).
 * Pure data like the sweep plan: it names identifiers, never a service, so it can only make the app read.
 */
@Serializable
public data class CandidateCheck(
    val requestId: String,
    val label: String,
    val ranges: List<DidRange> = emptyList(),
    val dids: List<String> = emptyList(),
    /** Ask even when the probe did not list this unit: body units outside the engine-bay addresses (7E0-7E7) are never probed. */
    val unprobed: Boolean = false,
) {
    public val requestCanId: Int get() = requestId.toInt(16)

    /** Every identifier once, [dids] first, then the ranges. */
    public fun identifiers(): List<Int> {
        val ordered = LinkedHashSet<Int>()
        dids.forEach { ordered += it.toInt(16) }
        ranges.forEach { range -> for (did in range.first..range.last) ordered += did }
        return ordered.toList()
    }
}

/** What to try once while connected: `definitions/discovery/candidates/<vehicle>.json`. */
@Serializable
public data class CandidateChecks(
    val schemaVersion: Int,
    val id: String,
    val checks: List<CandidateCheck>,
    val notes: String? = null,
) {
    init {
        require(schemaVersion in 1..SUPPORTED_CANDIDATES_SCHEMA) { "$id: unsupported schema $schemaVersion" }
        var total = 0
        for (check in checks) {
            val unit = check.requestId.toIntOrNull(16)
            require(check.requestId.length == 3 && unit != null && unit in 0..0x7FF) { "$id: '${check.requestId}' is not an 11-bit request id" }
            require(check.label.isNotBlank()) { "$id: ${check.requestId} has no label" }
            check.dids.forEach { require(it.length == 4 && it.toIntOrNull(16) != null) { "$id: '$it' is not a four-digit identifier" } }
            check.ranges.forEach { range ->
                require(range.from.length == 4 && range.to.length == 4 && range.from.toIntOrNull(16) != null && range.to.toIntOrNull(16) != null) {
                    "$id: '${range.from}-${range.to}' is not a range of four-digit identifiers"
                }
                require(range.first <= range.last) { "$id: the range ${range.from}-${range.to} ends before it starts" }
            }
            total += check.identifiers().size
        }
        require(total <= MAX_IDENTIFIERS) { "$id: $total identifiers is more than the $MAX_IDENTIFIERS a one-time check may ask" }
    }

    public companion object {
        /** A one-time check is for a few hundred requests, not a scan. */
        public const val MAX_IDENTIFIERS: Int = 512

        private val json = Json { ignoreUnknownKeys = false }

        /** @throws IllegalArgumentException if [text] is not a valid candidate file. */
        public fun parse(text: String): CandidateChecks =
            try {
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Invalid candidate file: ${e.message}", e)
            }
    }
}
