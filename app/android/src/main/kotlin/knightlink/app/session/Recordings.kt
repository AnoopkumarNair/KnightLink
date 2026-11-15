package knightlink.app.session

import knightlink.core.recorder.RecordSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Where the recording of each connection goes. */
interface RecordSinkFactory {
    /** A sink for a new connection (one per link opened, so a reconnect starts a new recording). The previous sink is finished. */
    fun newSink(): RecordSink

    /** No more lines will arrive for the current sink: release whatever it holds. Safe to call with no sink and more than once. */
    fun finishCurrent()
}

/** How many recordings there are on the device and how much room they take. */
data class RecordingsSummary(val count: Int, val totalBytes: Long) {
    companion object {
        val EMPTY = RecordingsSummary(0, 0)
    }
}

/**
 * The recordings directory (app-private storage): one `.jsonl` file per connection, named by its UTC start time so that
 * the names sort chronologically. At most [keep] files are kept, the newest.
 */
class RecordingStore(val directory: File, private val keep: Int = DEFAULT_KEEP) {
    init {
        require(keep >= 1) { "keep must be at least 1" }
    }

    /** The recordings, newest first. */
    fun list(): List<File> = directory.listFiles { file -> file.isFile && NAME.matches(file.name) }.orEmpty().sortedByDescending(::sortKey)

    fun newest(): File? = list().firstOrNull()

    fun summary(): RecordingsSummary = list().let { files -> RecordingsSummary(files.size, files.sumOf { it.length() }) }

    /** Deletes every recording beyond the newest [keep]. A file that cannot be deleted is left for the next call. */
    fun prune() {
        list().drop(keep).forEach { it.delete() }
    }

    // Without the extension, so that "…Z" sorts before its collision-suffixed sibling "…Z_1" (a '.' would sort between them wrongly).
    private fun sortKey(file: File): String = file.name.removeSuffix(EXTENSION)

    companion object {
        const val DEFAULT_KEEP = 20
        const val EXTENSION = ".jsonl"
        private val NAME = Regex("""\d{8}T\d{6}\.\d{3}Z(_\d+)?\.jsonl""")
    }
}

/**
 * Writes each connection's recording to its own file in a [RecordingStore], appending and flushing one line at a time, so
 * a crash or a killed process costs at most the line in flight.
 *
 * A write that fails (disk full) stops that recording quietly: losing a recording must never take down the session that is
 * reading the car.
 */
class FileRecordSinkFactory(private val store: RecordingStore, private val clock: Clock = Clock.systemUTC()) : RecordSinkFactory {
    private var current: FileSink? = null

    @Synchronized
    override fun newSink(): RecordSink {
        current?.close()
        current = null
        val sink = try {
            store.directory.mkdirs()
            FileSink(createFile())
        } catch (_: IOException) {
            // No recording is better than no connection: the car must stay readable when storage is unavailable.
            return RecordSink { }
        }
        current = sink
        // After creating the file, so the new recording counts among the kept ones.
        store.prune()
        return sink
    }

    @Synchronized
    override fun finishCurrent() {
        current?.close()
        current = null
    }

    private fun createFile(): File {
        val base = TIME.format(clock.instant().atOffset(ZoneOffset.UTC))
        var attempt = 0
        while (true) {
            val file = File(store.directory, base + (if (attempt == 0) "" else "_$attempt") + RecordingStore.EXTENSION)
            // createNewFile is atomic: two connections in the same millisecond cannot share a file.
            if (file.createNewFile()) return file
            attempt++
        }
    }

    private class FileSink(file: File) : RecordSink {
        private var out: FileOutputStream? = FileOutputStream(file, true)

        @Synchronized
        override fun append(line: String) {
            val stream = out ?: return
            try {
                // One write call per line, unbuffered: the line reaches the file (or fails) before append returns.
                stream.write((line + "\n").toByteArray(Charsets.UTF_8))
                stream.flush()
            } catch (_: IOException) {
                // Drop the rest of this recording instead of failing the session that feeds it.
                close()
            }
        }

        @Synchronized
        fun close() {
            try {
                out?.close()
            } catch (_: IOException) {
                // Nothing useful can be done about a failed close of a file that is already finished.
            }
            out = null
        }
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'")
    }
}
