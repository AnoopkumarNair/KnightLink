package knightlink.app.session

import knightlink.core.recorder.RecordSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Where the recording of each connection goes. */
interface RecordSinkFactory {
    /** A sink for a new connection (one per link opened, so a reconnect starts a new recording). The previous sink is finished. */
    fun newSink(): RecordSink

    /** No more lines will arrive for the current sink: release whatever it holds. Safe to call with no sink and more than once. */
    fun finishCurrent()

    /**
     * Writes [text] next to the current recording, under its name plus [suffix] (the full scan's result, `.scan.json`). Nothing happens when
     * there is no recording. A file that cannot be written is not an error: it is a convenience beside the recording.
     */
    fun writeSidecar(suffix: String, text: String) = Unit
}

/** How many recordings there are on the device and how much room they take. */
data class RecordingsSummary(val count: Int, val totalBytes: Long) {
    companion object {
        val EMPTY = RecordingsSummary(0, 0)
    }
}

/**
 * The recordings directory (app-private storage): one `.jsonl` file per connection, named by its UTC start time so that
 * the names sort chronologically. A recording is written as plain text while its connection runs and compressed to `.jsonl.gz` once it
 * is finished ([compressFinished]): the text is highly repetitive and shrinks about five-fold. At most [keep] files are kept, the newest,
 * and together at most [maxBytes] (the oldest go first; the newest is always kept).
 */
class RecordingStore(val directory: File, private val keep: Int = DEFAULT_KEEP, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    init {
        require(keep >= 1) { "keep must be at least 1" }
        require(maxBytes >= 1) { "maxBytes must be at least 1" }
    }

    /** The recordings, newest first. */
    fun list(): List<File> = directory.listFiles { file -> file.isFile && NAME.matches(file.name) }.orEmpty().sortedByDescending(::sortKey)

    fun newest(): File? = list().firstOrNull()

    fun summary(): RecordingsSummary = list().let { files -> RecordingsSummary(files.size, files.sumOf { it.length() }) }

    /**
     * Deletes every recording beyond the newest [keep], then the oldest while the recordings together are over [maxBytes] (never the
     * newest), and the files written next to each. A file that cannot be deleted is left for the next call.
     */
    fun prune() {
        val files = list()
        val doomed = files.drop(keep).toMutableList()
        var total = files.take(keep).sumOf { it.length() }
        for (recording in files.take(keep).asReversed()) {
            if (total <= maxBytes || recording == files.first()) break
            total -= recording.length()
            doomed += recording
        }
        doomed.forEach { recording ->
            recording.delete()
            val base = baseName(recording)
            directory.listFiles { file -> file.name.startsWith("$base.") && file.name != recording.name }?.forEach { it.delete() }
        }
    }

    /**
     * Compresses every plain recording except [exclude] (the one a connection is still writing) to `.jsonl.gz` and removes the plain file.
     * Safe to call at any time and again after a failure: a recording is replaced only after its compressed copy is complete, and a
     * recording a killed process left behind is simply compressed next time.
     */
    fun compressFinished(exclude: File? = null) {
        list().filter { !it.name.endsWith(GZIP_EXTENSION) && it != exclude }.forEach { plain ->
            val target = File(plain.parentFile, plain.name + GZIP_EXTENSION)
            val partial = File(plain.parentFile, plain.name + GZIP_EXTENSION + ".part")
            try {
                plain.inputStream().use { input -> GZIPOutputStream(partial.outputStream().buffered()).use { input.copyTo(it) } }
                if (partial.renameTo(target)) plain.delete() else partial.delete()
            } catch (_: IOException) {
                partial.delete() // disk full or similar: the plain recording stays, and is tried again later
            }
        }
    }

    // Without the extension, so that "…Z" sorts before its collision-suffixed sibling "…Z_1" (a '.' would sort between them wrongly).
    private fun sortKey(file: File): String = baseName(file)

    companion object {
        const val DEFAULT_KEEP = 20
        const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024
        const val EXTENSION = ".jsonl"
        const val GZIP_EXTENSION = ".gz"
        private val NAME = Regex("""\d{8}T\d{6}\.\d{3}Z(_\d+)?\.jsonl(\.gz)?""")

        /** The text of a recording, plain or compressed. */
        fun readText(file: File): String =
            if (file.name.endsWith(GZIP_EXTENSION)) {
                GZIPInputStream(file.inputStream().buffered()).use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                file.readText(Charsets.UTF_8)
            }

        /** The file's name without `.jsonl` or `.jsonl.gz`. */
        fun baseName(file: File): String = file.name.removeSuffix(GZIP_EXTENSION).removeSuffix(EXTENSION)
    }
}

/**
 * Writes each connection's recording to its own file in a [RecordingStore], appending and flushing one line at a time, so
 * a crash or a killed process costs at most the line in flight.
 *
 * A write that fails (disk full) stops that recording quietly: losing a recording must never take down the session that is
 * reading the car.
 */
class FileRecordSinkFactory(
    private val store: RecordingStore,
    private val clock: Clock = Clock.systemUTC(),
    /** Runs a compression job; by default on a background thread. Tests pass something that runs it at once, or not at all. */
    private val compress: (() -> Unit) -> Unit = ::runInBackground,
) : RecordSinkFactory {
    private var current: FileSink? = null
    private var currentFile: File? = null
    private val compressLock = Any()

    @Synchronized
    override fun newSink(): RecordSink {
        current?.close()
        current = null
        val sink = try {
            store.directory.mkdirs()
            val file = createFile()
            currentFile = file
            FileSink(file)
        } catch (_: IOException) {
            // No recording is better than no connection: the car must stay readable when storage is unavailable.
            return RecordSink { }
        }
        current = sink
        // After creating the file, so the new recording counts among the kept ones.
        store.prune()
        compressInBackground(currentFile)
        return sink
    }

    @Synchronized
    override fun finishCurrent() {
        current?.close()
        current = null
        compressInBackground(null)
    }

    @Synchronized
    override fun writeSidecar(suffix: String, text: String) {
        val file = currentFile ?: return
        try {
            File(file.parentFile, RecordingStore.baseName(file) + suffix).writeText(text, Charsets.UTF_8)
        } catch (_: IOException) {
            // Beside the recording, not part of it: losing it must not trouble the session.
        }
    }

    /** Compressing a long recording takes a second or two on a phone: not on the thread that starts or ends a connection. */
    private fun compressInBackground(exclude: File?) {
        compress { synchronized(compressLock) { store.compressFinished(exclude) } }
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
        fun runInBackground(job: () -> Unit) {
            Thread(job, "recording-compress").apply { isDaemon = true }.start()
        }

        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'")
    }
}
