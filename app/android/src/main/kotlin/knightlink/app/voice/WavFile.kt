package knightlink.app.voice

import java.io.InputStream

/** A WAV file that cannot be used for playback, with the reason in words. */
class WavFormatException(message: String) : Exception(message)

/** What a WAV file holds, as the app plays it: [samples] are 16-bit mono PCM at [sampleRate]. */
class WavAudio(val sampleRate: Int, val samples: ShortArray) {
    val durationMillis: Long get() = samples.size * MILLIS_PER_SECOND / sampleRate

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}

/**
 * Reads the WAV files that the text-to-speech engines of Android write: RIFF, PCM, 16 bits, one or two channels. Engines differ
 * in what they put around the audio, so the reader walks the chunks instead of assuming a 44-byte header: unknown chunks
 * (`LIST`, `fact`, ...) are skipped, odd-sized chunks are followed by a pad byte, and a `data` chunk whose size is 0 or
 * 0xFFFFFFFF (written before the length was known) or larger than the file (a cut-off file) is taken to run to the end of the file.
 * Stereo is averaged to mono, as one AudioTrack and one loudness envelope are all the app needs.
 */
object WavFile {
    fun parse(input: InputStream): WavAudio = parse(input.readBytes())

    fun parse(bytes: ByteArray): WavAudio {
        if (bytes.size < RIFF_HEADER_SIZE || ascii(bytes, 0) != "RIFF" || ascii(bytes, 8) != "WAVE") {
            throw WavFormatException("Not a RIFF/WAVE file")
        }
        var format: Format? = null
        var position = RIFF_HEADER_SIZE.toLong()
        while (position + CHUNK_HEADER_SIZE <= bytes.size) {
            val id = ascii(bytes, position.toInt())
            val declared = u32(bytes, position.toInt() + 4)
            val body = (position + CHUNK_HEADER_SIZE).toInt()
            when (id) {
                "fmt " -> format = readFormat(bytes, body, declared)
                "data" -> {
                    val chosen = format ?: throw WavFormatException("The data chunk comes before the fmt chunk")
                    val remaining = (bytes.size - body).toLong()
                    val length = if (declared == 0L || declared == UNKNOWN_SIZE || declared > remaining) remaining else declared
                    return decode(bytes, body, length.toInt(), chosen)
                }
            }
            position = body + declared + (declared and 1L)
        }
        throw WavFormatException(if (format == null) "No fmt chunk" else "No data chunk")
    }

    private class Format(val channels: Int, val sampleRate: Int)

    private fun readFormat(bytes: ByteArray, body: Int, declared: Long): Format {
        if (declared < FMT_MIN_SIZE || body + FMT_MIN_SIZE > bytes.size) throw WavFormatException("The fmt chunk is cut off")
        var tag = u16(bytes, body)
        // WAVE_FORMAT_EXTENSIBLE carries the real format in the first two bytes of the sub-format GUID.
        if (tag == FORMAT_EXTENSIBLE && declared >= FMT_EXTENSIBLE_SIZE && body + FMT_EXTENSIBLE_SIZE <= bytes.size) {
            tag = u16(bytes, body + SUBFORMAT_OFFSET)
        }
        val channels = u16(bytes, body + 2)
        val sampleRate = u32(bytes, body + 4)
        val bits = u16(bytes, body + 14)
        if (tag != FORMAT_PCM) throw WavFormatException("Unsupported WAV format: not PCM (format tag $tag)")
        if (bits != BITS_PER_SAMPLE) throw WavFormatException("Unsupported WAV format: $bits bits per sample, only 16 is supported")
        if (channels != 1 && channels != 2) {
            throw WavFormatException("Unsupported WAV format: $channels channels, only mono and stereo are supported")
        }
        if (sampleRate <= 0L || sampleRate > Int.MAX_VALUE) throw WavFormatException("Unsupported WAV format: sample rate $sampleRate")
        return Format(channels, sampleRate.toInt())
    }

    private fun decode(bytes: ByteArray, start: Int, length: Int, format: Format): WavAudio {
        val frameBytes = format.channels * BYTES_PER_SAMPLE
        val frames = length / frameBytes
        val samples = ShortArray(frames) { frame ->
            val at = start + frame * frameBytes
            if (format.channels == 1) {
                s16(bytes, at).toShort()
            } else {
                ((s16(bytes, at) + s16(bytes, at + BYTES_PER_SAMPLE)) / 2).toShort()
            }
        }
        return WavAudio(format.sampleRate, samples)
    }

    private fun ascii(bytes: ByteArray, at: Int): String = String(bytes, at, 4, Charsets.ISO_8859_1)

    private fun u16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Long = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)

    private fun s16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)

    private const val RIFF_HEADER_SIZE = 12
    private const val CHUNK_HEADER_SIZE = 8
    private const val FMT_MIN_SIZE = 16L
    private const val FMT_EXTENSIBLE_SIZE = 40L
    private const val SUBFORMAT_OFFSET = 24
    private const val FORMAT_PCM = 1
    private const val FORMAT_EXTENSIBLE = 0xFFFE
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = 2
    private const val UNKNOWN_SIZE = 0xFFFFFFFFL
}
