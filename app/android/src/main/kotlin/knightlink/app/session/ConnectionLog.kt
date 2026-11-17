package knightlink.app.session

import knightlink.core.common.Redaction
import knightlink.core.common.SessionEvent
import knightlink.core.common.SessionEventSink
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A rolling, plain-text record of connection attempts, readable on a phone that is not on adb. One file per attempt in
 * [directory] (app-private storage), named by its UTC start time; only the newest [keep] files are kept, and a file that
 * outgrows [maxFileBytes] loses the middle of its text (the first and the last lines are what explain a failure).
 *
 * An attempt starts with the first thing that happens when none is open (the user taps an adapter in the picker, long before
 * any session exists) and is closed by [endAttempt], which the controller calls when the session has been disconnected. The
 * next event then starts a new file.
 *
 * What goes in: the session's events (it is a [SessionEventSink]) and what the app itself does ([app]). All of it passes
 * through [Redaction] on the way in, so no Bluetooth address and nothing shaped like a VIN is ever written, whatever a
 * caller passes; [shareable] checks again on the way out. Writing never throws: a full disk costs the log, not the session.
 */
class ConnectionLog(
    val directory: File,
    private val keep: Int = DEFAULT_KEEP,
    private val maxFileBytes: Int = DEFAULT_MAX_FILE_BYTES,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(keep >= 1) { "keep must be at least 1" }
        require(maxFileBytes >= MIN_FILE_BYTES) { "maxFileBytes must be at least $MIN_FILE_BYTES" }
    }

    private var current: File? = null

    /** Receives the session's events; this is what [knightlink.core.runtime.VehicleSession] and the adapter driver write to. */
    val sink: SessionEventSink = SessionEventSink { event -> append(event) }

    /** Records something the app did (picker tap, service start, foreground start): not part of the session, but part of the story. */
    @Synchronized
    fun app(text: String) {
        write("app: $text")
    }

    /** Records a session event. */
    @Synchronized
    fun append(event: SessionEvent) {
        write(event.toString())
    }

    /** The attempt is over: the next line starts a new file. */
    @Synchronized
    fun endAttempt() {
        current = null
    }

    /** The log files, newest first. */
    @Synchronized
    fun files(): List<File> = directory.listFiles { file ->
        file.isFile && NAME.matches(file.name)
    }.orEmpty().sortedByDescending { it.name.removeSuffix(EXTENSION) }

    /**
     * All kept attempts in one text, oldest first, with a heading per attempt, ready to be shared. Redacted once more as it is
     * read, so a file that was written by an older build (or edited) cannot leak either.
     */
    @Synchronized
    fun shareable(): String {
        val files = files().reversed()
        if (files.isEmpty()) return "KnightLink connection log: nothing has been recorded yet.\n"
        return buildString {
            append("KnightLink connection log, ").append(files.size).append(if (files.size == 1) " attempt" else " attempts").append(", oldest first.\n")
            append("No VIN and no device address are recorded.\n")
            for (file in files) {
                append("\n==== ").append(file.name.removeSuffix(EXTENSION)).append(" ====\n")
                append(
                    Redaction.redact(
                        try {
                            file.readText(Charsets.UTF_8)
                        } catch (_: IOException) {
                            "(unreadable)\n"
                        },
                    ),
                )
            }
        }
    }

    /** Writes [shareable] into [outDirectory] as the only copy there (an earlier one is replaced) and returns it; null if that fails. */
    @Synchronized
    fun prepareShare(outDirectory: File): File? = try {
        outDirectory.mkdirs()
        outDirectory.listFiles { file -> file.name == SHARED_NAME }?.forEach { it.delete() }
        File(outDirectory, SHARED_NAME).also { it.writeText(shareable(), Charsets.UTF_8) }
    } catch (_: IOException) {
        null
    }

    private fun write(text: String) {
        try {
            val file = current ?: startFile()
            val line = TIME.format(clock.instant().atOffset(ZoneOffset.UTC)) + "  " + Redaction.redact(text).replace('\n', ' ').replace('\r', ' ') + "\n"
            file.appendText(line, Charsets.UTF_8)
            if (file.length() > maxFileBytes) shrink(file)
        } catch (_: IOException) {
            // The log is a convenience; it must never become a reason for a session to fail.
        }
    }

    private fun startFile(): File {
        directory.mkdirs()
        val base = FILE_TIME.format(clock.instant().atOffset(ZoneOffset.UTC))
        var attempt = 0
        var file: File
        do {
            file = File(directory, base + (if (attempt == 0) "" else "_$attempt") + EXTENSION)
            attempt++
        } while (!file.createNewFile())
        current = file
        files().drop(keep).forEach { it.delete() }
        return file
    }

    /** Keeps the head and the tail: the first lines say how the attempt began and the last say how it ended. */
    private fun shrink(file: File) {
        val lines = file.readLines(Charsets.UTF_8)
        val head = lines.take(HEAD_LINES)
        var tailBytes = 0
        val tail = lines.drop(HEAD_LINES).asReversed().takeWhile { line ->
            tailBytes += line.length + 1
            tailBytes <= maxFileBytes / 2
        }.asReversed()
        val dropped = lines.size - head.size - tail.size
        file.writeText((head + "... $dropped lines left out to keep the log small ..." + tail).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    companion object {
        const val DEFAULT_KEEP = 5
        const val DEFAULT_MAX_FILE_BYTES = 200 * 1024
        const val EXTENSION = ".log"
        const val SHARED_NAME = "knightlink-connection-log.txt"
        private const val MIN_FILE_BYTES = 1024
        private const val HEAD_LINES = 40
        private val NAME = Regex("""connection-\d{8}T\d{6}\.\d{3}Z(_\d+)?\.log""")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("'connection-'yyyyMMdd'T'HHmmss.SSS'Z'")

        private val instances = HashMap<String, ConnectionLog>()

        /**
         * The one log of [directory] in this process. The activity (picker tap) and the service (the session) must write into the
         * same attempt, so they must share an instance.
         */
        @Synchronized
        fun of(directory: File): ConnectionLog = instances.getOrPut(directory.absolutePath) { ConnectionLog(directory) }
    }
}
