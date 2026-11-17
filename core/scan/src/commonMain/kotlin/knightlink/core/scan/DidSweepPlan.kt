package knightlink.core.scan

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Highest schema of the sweep file this build reads. */
public const val SUPPORTED_SWEEP_SCHEMA: Int = 1

/** A range of data identifiers, inclusive, as four hex digits each. */
@Serializable
public data class DidRange(val from: String, val to: String) {
    /** The first and last identifier as numbers. */
    public val first: Int get() = from.toInt(16)
    public val last: Int get() = to.toInt(16)
    public val size: Int get() = last - first + 1
}

/** The 11-bit request identifiers asked in the control unit discovery, inclusive, as three hex digits, minus [skip]. */
@Serializable
public data class IdRange(val from: String, val to: String, val skip: List<String> = emptyList()) {
    public fun ids(): List<Int> = (from.toInt(16)..to.toInt(16)).filter { id -> skip.none { it.toInt(16) == id } }
}

/**
 * One control unit whose manufacturer data identifiers are swept.
 *
 * @property label what the unit is called in progress texts; data, because it is knowledge about one vehicle
 * @property ranges this unit's own ranges instead of the plan's; null means the plan's
 * @property candidates identifiers asked first, before the ranges (the ones that are guesses worth checking at once)
 * @property pollLimit how many of the identifiers this unit answered are polled raw while driving afterwards; null means all of them
 * @property pollMaxBytes the identifiers whose data is longer than this are not polled; null means any length
 */
@Serializable
public data class SweepTarget(
    val requestId: String,
    val label: String,
    val ranges: List<DidRange>? = null,
    val candidates: List<String> = emptyList(),
    val pollLimit: Int? = null,
    val pollMaxBytes: Int? = null,
) {
    public val requestCanId: Int get() = requestId.toInt(16)
}

/**
 * What the full scan sweeps, from `definitions/discovery/did-sweep.json`: which request identifiers to try for control unit discovery,
 * and which service 22 data identifiers to ask which control units. Pure data: it can only name identifiers, never a service, so a file
 * cannot make the scan do anything but read.
 *
 * @property requestsPerSecond pace of the sweeps; [FullScan] never goes above [MAX_REQUESTS_PER_SECOND]
 */
@Serializable
public data class DidSweepPlan(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    val requestsPerSecond: Double,
    val controlUnitIds: IdRange,
    val ranges: List<DidRange>,
    val targets: List<SweepTarget>,
    val notes: String? = null,
) {
    init {
        require(requestsPerSecond > 0.0 && requestsPerSecond <= MAX_REQUESTS_PER_SECOND) {
            "$id: requestsPerSecond must be above 0 and at most $MAX_REQUESTS_PER_SECOND"
        }
        val idLow = hex(controlUnitIds.from, 3, "controlUnitIds.from")
        val idHigh = hex(controlUnitIds.to, 3, "controlUnitIds.to")
        require(idLow in CAN_ID_RANGE && idHigh in CAN_ID_RANGE && idLow <= idHigh) { "$id: controlUnitIds must be a range of 11-bit ids" }
        controlUnitIds.skip.forEach { hex(it, 3, "controlUnitIds.skip") }
        require(controlUnitIds.skip.none { it.toInt(16) !in idLow..idHigh }) { "$id: controlUnitIds.skip lies outside the range" }
        validate(ranges, "ranges")
        require(targets.isNotEmpty()) { "$id: no targets" }
        require(targets.map { it.requestId.uppercase() }.toSet().size == targets.size) { "$id: a target is listed twice" }
        for (target in targets) {
            require(hex(target.requestId, 3, "target.requestId") in CAN_ID_RANGE) { "$id: ${target.requestId} is not an 11-bit id" }
            require(target.label.isNotBlank()) { "$id: ${target.requestId} has no label" }
            target.ranges?.let { validate(it, "${target.requestId}.ranges") }
            target.candidates.forEach { hex(it, 4, "${target.requestId}.candidates") }
            require((target.pollLimit ?: 0) >= 0 && (target.pollMaxBytes ?: 0) >= 0) { "$id: ${target.requestId} has a negative poll limit" }
            require(target.candidates.map { it.uppercase() }.toSet().size == target.candidates.size) { "$id: ${target.requestId} lists a candidate twice" }
        }
    }

    private fun validate(list: List<DidRange>, name: String) {
        require(list.isNotEmpty()) { "$id: $name is empty" }
        val sorted = list.map {
            val low = hex(it.from, 4, "$name.from")
            val high = hex(it.to, 4, "$name.to")
            require(low <= high) { "$id: $name has a range that ends before it starts: ${it.from}-${it.to}" }
            low to high
        }.sortedBy { it.first }
        for (i in 1 until sorted.size) require(sorted[i].first > sorted[i - 1].second) { "$id: $name has overlapping ranges" }
        require(sorted.sumOf { it.second - it.first + 1 } <= MAX_IDENTIFIERS_PER_TARGET) { "$id: $name is longer than $MAX_IDENTIFIERS_PER_TARGET identifiers" }
    }

    /** The identifiers to ask [target], in order: its candidates first, then its ranges, every identifier once. */
    public fun identifiersFor(target: SweepTarget): List<Int> {
        val ordered = LinkedHashSet<Int>()
        target.candidates.forEach { ordered += it.toInt(16) }
        (target.ranges ?: ranges).forEach { range -> for (did in range.first..range.last) ordered += did }
        return ordered.toList()
    }

    /**
     * [identifiersFor] cut into the pieces the sweep treats separately: the candidates, then each range without what was already asked.
     * A control unit that stays silent through a whole piece is not asked the rest of it.
     */
    public fun chunksFor(target: SweepTarget): List<List<Int>> {
        val seen = LinkedHashSet<Int>()
        val chunks = ArrayList<List<Int>>()
        target.candidates.map { it.toInt(16) }.filter { seen.add(it) }.takeIf { it.isNotEmpty() }?.let { chunks += it }
        for (range in target.ranges ?: ranges) {
            val chunk = (range.first..range.last).filter { seen.add(it) }
            if (chunk.isNotEmpty()) chunks += chunk
        }
        return chunks
    }

    public companion object {
        /** The most the scan sends to the vehicle, whatever a file says. */
        public const val MAX_REQUESTS_PER_SECOND: Double = 20.0

        private const val MAX_IDENTIFIERS_PER_TARGET = 4096
        private val CAN_ID_RANGE = 0x000..0x7FF

        private val json = Json { ignoreUnknownKeys = false }

        /**
         * @throws IllegalArgumentException if the text is not a valid plan: malformed JSON, an unknown key, a newer schema, hex that is not hex,
         *   ranges that overlap, or a pace above the ceiling.
         */
        public fun parse(text: String): DidSweepPlan {
            val plan = try {
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Invalid sweep plan: ${e.message}", e)
            }
            require(plan.schemaVersion in 1..SUPPORTED_SWEEP_SCHEMA) {
                "${plan.id}: schema ${plan.schemaVersion} is not supported; this build supports up to $SUPPORTED_SWEEP_SCHEMA"
            }
            return plan
        }

        private fun hex(text: String, digits: Int, what: String): Int {
            require(text.length == digits && text.all { it in '0'..'9' || it in 'A'..'F' }) {
                "$what must be $digits upper-case hex digits, not '$text'"
            }
            return text.toInt(16)
        }
    }
}
