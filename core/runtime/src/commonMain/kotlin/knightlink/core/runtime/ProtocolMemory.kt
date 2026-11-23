package knightlink.core.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What an adapter found out about the protocol of the car it was plugged into. Holds no VIN: only the manufacturer code (`vinPrefix`, three
 * characters) of the car, which is what the protocol of a make tends to follow.
 *
 * @property adapterId the adapter definition's id
 * @property vinPrefix the first three VIN characters of the car it was found on; null while the car had not told its VIN
 * @property protocol the `ATSP` character of the protocol that was locked, e.g. `6`
 * @property dpn what `ATDPN` answered, e.g. `A6`
 * @property viaAuto true when the adapter's own search found it (`ATSP0`), false when it was one of the explicit tries
 * @property negotiationMs how long finding it took
 * @property failedAttempts what was tried before and did not answer, in words
 * @property seenAtMs the phone's clock when it was recorded (0 when there was none)
 */
@Serializable
public data class ProtocolRecord(
    val adapterId: String,
    val vinPrefix: String? = null,
    val protocol: String,
    val dpn: String = "",
    val viaAuto: Boolean = false,
    val negotiationMs: Long = 0,
    val failedAttempts: List<String> = emptyList(),
    val seenAtMs: Long = 0,
)

/** Where protocol records are kept between sessions. Small, so calls are plain, not suspending. */
public interface ProtocolMemoryStore {
    /** The record saved most recently for [adapterId], or null. */
    public fun last(adapterId: String): ProtocolRecord?

    /** The record of [adapterId] on a car whose VIN starts with [vinPrefix], or null. */
    public fun lookup(adapterId: String, vinPrefix: String): ProtocolRecord?

    /** Every record, oldest first. */
    public fun all(): List<ProtocolRecord>

    /** Saves [record] (replacing the one of the same adapter and VIN prefix) and makes it the most recent one of its adapter. */
    public fun save(record: ProtocolRecord)
}

/** Keeps the records in memory; for tests, and the base of a file-backed store. */
public class InMemoryProtocolMemoryStore(initial: List<ProtocolRecord> = emptyList()) : ProtocolMemoryStore {
    private val records = ArrayList(initial)

    override fun last(adapterId: String): ProtocolRecord? = records.lastOrNull { it.adapterId == adapterId }

    override fun lookup(adapterId: String, vinPrefix: String): ProtocolRecord? =
        records.lastOrNull { it.adapterId == adapterId && it.vinPrefix.equals(vinPrefix, ignoreCase = true) }

    override fun all(): List<ProtocolRecord> = records.toList()

    override fun save(record: ProtocolRecord) {
        records.removeAll { it.adapterId == record.adapterId && it.vinPrefix == record.vinPrefix }
        records += record
        // A record without a VIN prefix only bridges the time before the car has told its VIN; it goes once the prefix is known.
        if (record.vinPrefix != null) records.removeAll { it.adapterId == record.adapterId && it.vinPrefix == null }
    }

    /** The whole store as JSON, for a file. */
    public fun toJson(): String = json.encodeToString(ProtocolRecords.serializer(), ProtocolRecords(records = records.toList()))

    public companion object {
        private val json = Json { encodeDefaults = true }
        private val reader = Json { ignoreUnknownKeys = true }

        /** The store a file's [text] holds; an empty store when it is not readable (a damaged file is a fresh start, not an error). */
        public fun fromJson(text: String?): InMemoryProtocolMemoryStore {
            if (text.isNullOrBlank()) return InMemoryProtocolMemoryStore()
            return try {
                InMemoryProtocolMemoryStore(reader.decodeFromString(ProtocolRecords.serializer(), text).records)
            } catch (_: SerializationException) {
                InMemoryProtocolMemoryStore()
            } catch (_: IllegalArgumentException) {
                InMemoryProtocolMemoryStore()
            }
        }
    }
}

@Serializable
internal data class ProtocolRecords(val schemaVersion: Int = 1, val records: List<ProtocolRecord>)
