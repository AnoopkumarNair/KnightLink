package knightlink.app.voice

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WavFileTest {
    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    private fun le32(value: Long) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun chunk(id: String, body: ByteArray, declared: Long = body.size.toLong(), pad: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(le32(declared))
        out.write(body)
        if (pad && body.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun fmt(channels: Int = 1, rate: Int = 22050, bits: Int = 16, tag: Int = 1): ByteArray =
        chunk("fmt ", le16(tag) + le16(channels) + le32(rate.toLong()) + le32(rate.toLong() * channels * bits / 8) + le16(channels * bits / 8) + le16(bits))

    private fun pcm(vararg samples: Int): ByteArray = samples.fold(ByteArray(0)) { acc, s -> acc + le16(s) }

    private fun wav(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold("WAVE".toByteArray()) { acc, c -> acc + c }
        return "RIFF".toByteArray() + le32(body.size.toLong()) + body
    }

    @Test
    fun readsMonoPcmAndTheSampleRate() {
        val audio = WavFile.parse(wav(fmt(rate = 16000), chunk("data", pcm(1, -2, 300, -32768))))

        assertEquals(16000, audio.sampleRate)
        assertContentEquals(shortArrayOf(1, -2, 300, -32768), audio.samples)
    }

    @Test
    fun readsFromAStreamToo() {
        val audio = WavFile.parse(wav(fmt(), chunk("data", pcm(5, 6))).inputStream())

        assertContentEquals(shortArrayOf(5, 6), audio.samples)
    }

    @Test
    fun stereoIsAveragedToMono() {
        val audio = WavFile.parse(wav(fmt(channels = 2), chunk("data", pcm(100, 300, -50, -150))))

        assertContentEquals(shortArrayOf(200, -100), audio.samples)
    }

    @Test
    fun unknownChunksAreSkippedIncludingOddSizedOnesWithPadding() {
        val audio = WavFile.parse(wav(chunk("LIST", "abc".toByteArray()), fmt(), chunk("fact", "xyzzy".toByteArray()), chunk("data", pcm(7, 8))))

        assertContentEquals(shortArrayOf(7, 8), audio.samples)
    }

    @Test
    fun aDataChunkOfSizeZeroTakesTheRestOfTheFile() {
        val audio = WavFile.parse(wav(fmt(), chunk("data", pcm(1, 2, 3), declared = 0)))

        assertContentEquals(shortArrayOf(1, 2, 3), audio.samples)
    }

    @Test
    fun aDataChunkOfSizeFFFFFFFFTakesTheRestOfTheFile() {
        val audio = WavFile.parse(wav(fmt(), chunk("data", pcm(1, 2, 3, 4), declared = 0xFFFFFFFFL)))

        assertContentEquals(shortArrayOf(1, 2, 3, 4), audio.samples)
    }

    @Test
    fun aCutOffDataChunkIsReadAsFarAsItGoesAndAHalfSampleIsDropped() {
        val full = wav(fmt(), chunk("data", pcm(1, 2, 3), declared = 100))

        val audio = WavFile.parse(full.copyOf(full.size - 1))

        assertContentEquals(shortArrayOf(1, 2), audio.samples)
    }

    @Test
    fun extensibleFormatWithPcmSubFormatIsAccepted() {
        val guid = le16(1) + ByteArray(14)
        val body = le16(0xFFFE) + le16(1) + le32(22050) + le32(44100) + le16(2) + le16(16) + le16(22) + le16(16) + le32(4) + guid
        val audio = WavFile.parse(wav(chunk("fmt ", body), chunk("data", pcm(9))))

        assertContentEquals(shortArrayOf(9), audio.samples)
    }

    @Test
    fun otherFormatsAreRejectedWithAReason() {
        val float = assertFailsWith<WavFormatException> { WavFile.parse(wav(fmt(tag = 3, bits = 32), chunk("data", pcm(1, 2)))) }
        assertTrue("PCM" in float.message.orEmpty() || "bits" in float.message.orEmpty())
        val eightBit = assertFailsWith<WavFormatException> { WavFile.parse(wav(fmt(bits = 8), chunk("data", ByteArray(4)))) }
        assertTrue("8 bits" in eightBit.message.orEmpty())
        val surround = assertFailsWith<WavFormatException> { WavFile.parse(wav(fmt(channels = 6), chunk("data", pcm(1, 2)))) }
        assertTrue("6 channels" in surround.message.orEmpty())
    }

    @Test
    fun notAWavFileIsRejected() {
        assertFailsWith<WavFormatException> { WavFile.parse(ByteArray(0)) }
        assertFailsWith<WavFormatException> { WavFile.parse("RIFF0000AVI LIST".toByteArray()) }
    }

    @Test
    fun missingChunksAreReported() {
        assertFailsWith<WavFormatException> { WavFile.parse(wav(fmt())) }
        assertFailsWith<WavFormatException> { WavFile.parse(wav(chunk("data", pcm(1)))) }
        assertFailsWith<WavFormatException> { WavFile.parse(wav(chunk("fmt ", ByteArray(4)))) }
    }

    @Test
    fun durationFollowsTheSampleRate() {
        val audio = WavFile.parse(wav(fmt(rate = 1000), chunk("data", pcm(*IntArray(500)))))

        assertEquals(500L, audio.durationMillis)
    }
}
