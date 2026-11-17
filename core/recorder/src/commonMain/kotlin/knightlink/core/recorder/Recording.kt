package knightlink.core.recorder

import knightlink.core.common.hexToBytes
import knightlink.core.common.toHex
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A recording file is damaged or was written by a newer version of the format. */
public class RecordingFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** What happened on the link. The names are the values of the `d` key in the file. */
@Serializable
public enum class EventKind(internal val carriesBytes: Boolean = false) {
    /** The link was opened. */
    @SerialName("open")
    OPEN,

    /** Bytes written to the adapter. */
    @SerialName("tx")
    TX(carriesBytes = true),

    /** One chunk of bytes received from the adapter, exactly as the transport delivered it. */
    @SerialName("rx")
    RX(carriesBytes = true),

    /** The consumer closed the link. */
    @SerialName("close")
    CLOSE,

    /** The incoming stream failed; the payload is the failure message. */
    @SerialName("error")
    ERROR,

    /**
     * The remote end closed the link: the incoming stream completed normally without the consumer having closed it. This
     * is the adapter hanging up, which is what makes a session reconnect. A consumer that closes afterwards still
     * writes [CLOSE].
     */
    @SerialName("eof")
    EOF,

    /**
     * Something the session or the adapter driver wanted on record, as one line of text: a step of the init script, a state change,
     * a reset, a reconnect. Not traffic, so a replay skips it; it is what explains why the traffic looks the way it does. Files
     * that hold these are schema 2 (see [Recording]).
     */
    @SerialName("ev")
    EVENT,

    /**
     * Samples from outside the link, taken while it was open (the phone's motion sensors and position), as one line of text: a source
     * tag, then values (see [ExternalSamples]). Not traffic, so a replay skips it; it lets the drive be read next to the vehicle's data.
     * Files that hold these are schema 3.
     */
    @SerialName("ext")
    EXTERNAL,
}

/**
 * The first line of a recording.
 *
 * @param adapter id of the adapter definition the session used, when known.
 * @param note free text, for example what the drive was.
 */
public data class RecordingHeader(val adapter: String? = null, val note: String? = null)

/**
 * One line of a recording after the header.
 *
 * @param timeMs milliseconds since the recording started.
 * @param payload for [EventKind.TX] and [EventKind.RX] the raw bytes as upper-case hex (lossless, nothing here assumes
 *   text); for [EventKind.ERROR] the failure message; empty for the other kinds.
 */
public data class RecordingEvent(val timeMs: Long, val kind: EventKind, val payload: String = "") {
    init {
        require(timeMs >= 0) { "An event cannot happen before the recording starts: $timeMs ms" }
        if (kind.carriesBytes) {
            require(payload.length % 2 == 0 && payload.all { it in '0'..'9' || it in 'A'..'F' }) {
                "The payload of a $kind event must be upper-case hex with an even number of digits"
            }
        }
    }

    /** The bytes of a [EventKind.TX] or [EventKind.RX] event. */
    public val bytes: ByteArray
        get() {
            check(kind.carriesBytes) { "A $kind event carries no bytes" }
            return payload.hexToBytes()
        }

    public companion object {
        public fun open(timeMs: Long): RecordingEvent = RecordingEvent(timeMs, EventKind.OPEN)

        public fun tx(timeMs: Long, bytes: ByteArray): RecordingEvent = RecordingEvent(timeMs, EventKind.TX, bytes.toHex())

        public fun rx(timeMs: Long, bytes: ByteArray): RecordingEvent = RecordingEvent(timeMs, EventKind.RX, bytes.toHex())

        public fun close(timeMs: Long): RecordingEvent = RecordingEvent(timeMs, EventKind.CLOSE)

        public fun error(timeMs: Long, message: String): RecordingEvent = RecordingEvent(timeMs, EventKind.ERROR, message)

        public fun eof(timeMs: Long): RecordingEvent = RecordingEvent(timeMs, EventKind.EOF)

        /** A session event; [text] is one line for a person, without a VIN or an address. */
        public fun event(timeMs: Long, text: String): RecordingEvent = RecordingEvent(timeMs, EventKind.EVENT, text)

        /** External samples; [line] is one line made by [ExternalSamples]. */
        public fun external(timeMs: Long, line: String): RecordingEvent = RecordingEvent(timeMs, EventKind.EXTERNAL, line)
    }
}

/**
 * A captured adapter session: the byte-level conversation between the app and an adapter, in order, with timing.
 *
 * The file form is JSON Lines: line 1 is a header `{"schemaVersion":1,"kind":"knightlink-session","adapter":...,"note":...}`,
 * every further line is an event `{"t":<ms>,"d":"tx"|"rx"|"open"|"close"|"error"|"eof","x":"<payload>"}`. One line per
 * event means a recorder can append and flush as it goes, and a crash costs at most the line being written.
 *
 * `eof` was added without a new schema version: a reader that predates it rejects the unknown `d` value with a
 * [RecordingFormatException] instead of misreading the file, which is what a version bump would have achieved.
 *
 * Schema 2 adds the `ev` kind (see [EventKind.EVENT]); its `x` is a line of text. A file is written as schema 2 only when it
 * holds such events, so every file without them stays byte-for-byte what schema 1 wrote, and a reader that only knows schema 1
 * refuses a schema 2 file with a clear message. This reader accepts both.
 *
 * Schema 3 adds the `ext` kind (see [EventKind.EXTERNAL]). It follows the same rule: a file is written as schema 3 only when it holds
 * such events, so files without them stay as they were, and a reader that does not know the kind refuses the file instead of
 * misreading it. This reader accepts 1, 2 and 3.
 *
 * @param truncated true when [parse] dropped an incomplete last line, i.e. the session ended in a crash.
 */
public data class Recording(
    val header: RecordingHeader,
    val events: List<RecordingEvent>,
    val truncated: Boolean = false,
) {
    /** The file text: header line, then one line per event, each ended by a newline. */
    public fun toText(): String = buildString {
        val schema = when {
            events.any { it.kind == EventKind.EXTERNAL } -> SCHEMA_EXTERNAL
            events.any { it.kind == EventKind.EVENT } -> SCHEMA_EVENTS
            else -> BASE_SCHEMA_VERSION
        }
        append(headerLine(header, schema)).append('\n')
        events.forEach { append(eventLine(it)).append('\n') }
    }

    public companion object {
        /** The newest schema this build reads and writes. */
        public const val SCHEMA_VERSION: Int = 3

        /** What a file with session events (and no external samples) says. */
        public const val SCHEMA_EVENTS: Int = 2

        /** What a file with external samples says. */
        public const val SCHEMA_EXTERNAL: Int = 3

        /** What a file without session events says; see the class comment. */
        internal const val BASE_SCHEMA_VERSION: Int = 1
        internal const val KIND: String = "knightlink-session"

        // Strict on purpose: an unknown key means the file came from a different format, and guessing would hide that.
        // encodeDefaults so that null header fields and empty payloads are written out as documented.
        private val json = Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        }

        @Serializable
        private class HeaderLine(val schemaVersion: Int, val kind: String, val adapter: String? = null, val note: String? = null)

        @Serializable
        private class EventLine(val t: Long, val d: EventKind, val x: String = "")

        internal fun headerLine(header: RecordingHeader, schemaVersion: Int = BASE_SCHEMA_VERSION): String =
            json.encodeToString(HeaderLine.serializer(), HeaderLine(schemaVersion, KIND, header.adapter, header.note))

        internal fun eventLine(event: RecordingEvent): String =
            json.encodeToString(EventLine.serializer(), EventLine(event.timeMs, event.kind, event.payload))

        /**
         * Reads the file text written by [toText] or by a [RecordingByteStream].
         *
         * Only the last line may be incomplete: a crash while writing leaves a partial line without its newline.
         * That line is dropped and [Recording.truncated] is set, so a recording of a session that ended badly is
         * still readable. A damaged line anywhere else is an error.
         *
         * @throws RecordingFormatException if the text is not a recording, has unknown keys, or uses a newer schema.
         */
        public fun parse(text: String): Recording {
            val segments = text.split('\n')
            var header: RecordingHeader? = null
            val events = ArrayList<RecordingEvent>()
            var truncated = false
            for ((index, segment) in segments.withIndex()) {
                val line = segment.trimEnd('\r')
                if (line.isBlank()) continue
                // Every segment but the last ended with a newline; the last is "" when the text ends with one.
                val unterminated = index == segments.lastIndex
                val element = try {
                    json.parseToJsonElement(line)
                } catch (e: SerializationException) {
                    // Incomplete JSON can only come from a write that was cut short, and only the last write can be.
                    if (!unterminated) throw RecordingFormatException("Line ${index + 1} is not valid JSON: ${e.message}", e)
                    truncated = true
                    continue
                }
                try {
                    if (header == null) header = decodeHeader(element) else events += decodeEvent(element)
                } catch (e: RecordingFormatException) {
                    throw RecordingFormatException("Line ${index + 1}: ${e.message}", e)
                }
            }
            val complete = header ?: throw RecordingFormatException(
                if (truncated) "The recording ends inside its header line" else "The recording is empty: it has no header line",
            )
            return Recording(complete, events, truncated)
        }

        private fun decodeHeader(element: JsonElement): RecordingHeader {
            val version = ((element as? JsonObject)?.get("schemaVersion") as? JsonPrimitive)?.intOrNull
                ?: throw RecordingFormatException("The header has no integer schemaVersion")
            // Checked before anything else: a newer schema may add keys that the strict decode below would complain about.
            if (version > SCHEMA_VERSION) {
                throw RecordingFormatException("The recording uses schema $version; this build reads schema up to $SCHEMA_VERSION")
            }
            if (version < 1) throw RecordingFormatException("Unsupported schemaVersion $version")
            val line = decode(HeaderLine.serializer(), element)
            if (line.kind != KIND) throw RecordingFormatException("Not a session recording: kind is '${line.kind}', expected '$KIND'")
            return RecordingHeader(line.adapter, line.note)
        }

        private fun decodeEvent(element: JsonElement): RecordingEvent {
            val line = decode(EventLine.serializer(), element)
            return try {
                // Hex is accepted in either case but held upper-case, so equal recordings compare equal.
                RecordingEvent(line.t, line.d, if (line.d.carriesBytes) line.x.uppercase() else line.x)
            } catch (e: IllegalArgumentException) {
                throw RecordingFormatException(e.message ?: "Invalid event", e)
            }
        }

        private fun <T> decode(serializer: KSerializer<T>, element: JsonElement): T = try {
            json.decodeFromJsonElement(serializer, element)
        } catch (e: SerializationException) {
            throw RecordingFormatException(e.message ?: "Invalid line", e)
        } catch (e: IllegalArgumentException) {
            throw RecordingFormatException(e.message ?: "Invalid line", e)
        }
    }
}
