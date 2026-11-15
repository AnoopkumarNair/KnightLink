package knightlink.core.recorder

/**
 * Where a recording goes, one line at a time. File sinks need platform I/O and live outside this module.
 *
 * A file sink should add the line terminator itself, write the line in one operation and flush it, so that a
 * crash costs at most the line in flight (see [Recording.parse]). Exceptions from [append] reach the code
 * that is writing to or reading from the adapter: deciding whether a full disk should end the session is the
 * sink's job, not this module's.
 */
public fun interface RecordSink {
    /** Appends one complete line. [line] has no line terminator. */
    public fun append(line: String)
}

/** Keeps the lines in memory. For tests and for recordings that are saved after the session. */
public class InMemoryRecordSink : RecordSink {
    private val collected = ArrayList<String>()

    /** The lines appended so far. */
    public val lines: List<String> get() = collected.toList()

    override fun append(line: String) {
        collected += line
    }

    /** The recording text as a file would hold it. */
    public fun text(): String = collected.joinToString(separator = "\n", postfix = "\n")

    /** The recording so far. A session that is still running reads as complete up to its last line. */
    public fun toRecording(): Recording = Recording.parse(text())
}
