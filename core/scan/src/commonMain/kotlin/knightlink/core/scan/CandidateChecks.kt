package knightlink.core.scan

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val RESPONSE_OFFSET = 8
private const val VIN_LENGTH = 17

/** [CandidateCheck.session] for the extended session. */
public const val EXTENDED_SESSION: String = "extended"

/** The one unit that may be asked in the extended session: the instrument cluster. */
private const val EXTENDED_UNIT = "700"

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
    /**
     * The diagnostic session the identifiers are asked in: null for the default one, `extended` for `10 03` first (and `3E 00` while it lasts,
     * `10 01` after). Only the instrument cluster (700) may have `extended` (see ReadOnlyPolicy), and only a known vehicle's parked stage asks it.
     */
    val session: String? = null,
) {
    public val requestCanId: Int get() = requestId.toInt(16)

    /** Asked in the extended session. */
    public val extended: Boolean get() = session == EXTENDED_SESSION

    /** What its progress is kept under: the request id, with `x` for the extended-session pass (so the two passes of one unit are apart). */
    public val progressKey: String get() = if (extended) "${requestId}x" else requestId

    /** The control unit's response identifier under the usual UDS rule for 11-bit identifiers: the request identifier plus 8 (700 answers from 708). */
    public val responseCanId: Int get() = requestCanId + RESPONSE_OFFSET

    /**
     * The identifiers in the order they are asked, in groups: [dids] first, then each range. A unit that is silent for a long run of one group
     * is not asked the rest of that group, but the next group is still tried.
     */
    public fun groups(): List<List<Int>> {
        val seen = HashSet<Int>()
        val groups = ArrayList<List<Int>>()
        val first = dids.map { it.toInt(16) }.filter { seen.add(it) }
        if (first.isNotEmpty()) groups += first
        for (range in ranges) {
            val group = (range.first..range.last).filter { seen.add(it) }
            if (group.isNotEmpty()) groups += group
        }
        return groups
    }

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
    /**
     * VIN prefixes (the manufacturer code, e.g. `MAT`) of the vehicles this file is for. The check runs only when the probed VIN starts with one of
     * them; a vehicle without a VIN, or with another one, is left alone. Prefixes of at most three characters also match a remembered probe, whose
     * VIN is masked after the manufacturer code. Empty: any vehicle (the file's author has said nothing about which).
     */
    val appliesTo: List<String> = emptyList(),
    /**
     * The id of the earlier candidate file this one follows: the stored progress of that file is kept for each unit whose number of identifiers
     * is unchanged, and a changed unit starts afresh on its new list. Null: progress of any other file is dropped.
     */
    val continues: String? = null,
) {
    /** Whether the check is for a vehicle with [vin] (null when the probe found none). */
    public fun appliesTo(vin: String?): Boolean =
        appliesTo.isEmpty() || (vin != null && appliesTo.any { vin.startsWith(it, ignoreCase = true) })

    init {
        require(schemaVersion in 1..SUPPORTED_CANDIDATES_SCHEMA) { "$id: unsupported schema $schemaVersion" }
        appliesTo.forEach { require(it.length in 1..VIN_LENGTH && it.all { c -> c.isLetterOrDigit() }) { "$id: '$it' is not a VIN prefix" } }
        var total = 0
        var extendedTotal = 0
        for (check in checks) {
            val unit = check.requestId.toIntOrNull(16)
            require(check.requestId.length == 3 && unit != null && unit in 0x700..0x7FF) { "$id: '${check.requestId}' is not a request id from 700 to 7FF" }
            require(check.label.isNotBlank()) { "$id: ${check.requestId} has no label" }
            require(check.session == null || check.session == EXTENDED_SESSION) { "$id: session '${check.session}' is not known" }
            require(!check.extended || check.requestId.equals(EXTENDED_UNIT, ignoreCase = true)) {
                "$id: only $EXTENDED_UNIT may be asked in the extended session"
            }
            check.dids.forEach { require(it.length == 4 && it.toIntOrNull(16) != null) { "$id: '$it' is not a four-digit identifier" } }
            check.ranges.forEach { range ->
                require(range.from.length == 4 && range.to.length == 4 && range.from.toIntOrNull(16) != null && range.to.toIntOrNull(16) != null) {
                    "$id: '${range.from}-${range.to}' is not a range of four-digit identifiers"
                }
                require(range.first <= range.last) { "$id: the range ${range.from}-${range.to} ends before it starts" }
            }
            if (check.extended) extendedTotal += check.identifiers().size else total += check.identifiers().size
        }
        require(extendedTotal <= MAX_IDENTIFIERS) { "$id: $extendedTotal extended-session identifiers is more than $MAX_IDENTIFIERS" }
        require(total <= MAX_IDENTIFIERS) { "$id: $total identifiers is more than the $MAX_IDENTIFIERS a one-time check may ask" }
    }

    public companion object {
        /** A one-time check is for a couple of thousand requests at scan pace (about two minutes), not a scan. */
        public const val MAX_IDENTIFIERS: Int = 2048

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
