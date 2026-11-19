package knightlink.app.session

import knightlink.core.recorder.Recording
import knightlink.core.recorder.RecordingScrubber
import java.io.File

/**
 * Makes a recording safe to hand to another app. The original is never shared: a recording holds the full VIN (ADR 0002),
 * so the only file that ever leaves the recordings directory is the scrubbed copy this writes into a separate directory.
 */
object RecordingShare {
    /**
     * A scrubbed copy, ready to share.
     *
     * @property warnings responses the scrubber could not read with confidence and replaced by `NO DATA` (failing closed:
     *   the copy holds no VIN, but is missing data). Shown to the user before sharing. Never contains a VIN.
     */
    class Prepared(val file: File, val warnings: List<String>)

    /**
     * Scrubs [source] and writes the copy into [outDirectory], first deleting what an earlier share left there: only the
     * latest scrubbed copy stays on the device.
     *
     * @throws knightlink.core.recorder.RecordingFormatException if [source] is not a readable recording
     * @throws java.io.IOException if a file cannot be read or written
     */
    fun prepare(source: File, outDirectory: File): Prepared {
        val result = RecordingScrubber.scrub(Recording.parse(RecordingStore.readText(source)))
        outDirectory.mkdirs()
        outDirectory.listFiles { file -> file.name.endsWith(SUFFIX) }?.forEach { it.delete() }
        val copy = File(outDirectory, RecordingStore.baseName(source) + "-scrubbed" + RecordingStore.EXTENSION)
        copy.writeText(result.recording.toText(), Charsets.UTF_8)
        return Prepared(copy, result.warnings)
    }

    private const val SUFFIX = "-scrubbed" + RecordingStore.EXTENSION
}
