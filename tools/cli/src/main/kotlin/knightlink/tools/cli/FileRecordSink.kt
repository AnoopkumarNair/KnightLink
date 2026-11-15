package knightlink.tools.cli

import knightlink.core.recorder.RecordSink
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Appends recording lines to a new file: UTF-8, each line written in one call and flushed, so a crash loses at most
 * the line in flight and the file stays readable (see `Recording.parse`). Refuses to touch an existing file, because
 * a second header in the middle of a recording would corrupt it.
 */
internal class FileRecordSink private constructor(private val out: OutputStream) : RecordSink, AutoCloseable {
    @Synchronized
    override fun append(line: String) {
        try {
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: IOException) {
            // A recording that silently stops is worse than a session that stops: say what happened.
            throw IOException("Cannot write the recording: ${e.message}", e)
        }
    }

    @Synchronized
    override fun close() {
        out.close()
    }

    companion object {
        fun create(path: Path): FileRecordSink = try {
            FileRecordSink(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.APPEND))
        } catch (e: FileAlreadyExistsException) {
            throw CliError("${path.toFile().path} already exists; choose a new file name")
        }
    }
}
