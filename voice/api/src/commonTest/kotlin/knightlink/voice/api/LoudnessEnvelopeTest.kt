package knightlink.voice.api

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoudnessEnvelopeTest {
    private val rate = 8000 // 160 samples per 20 ms frame

    private fun sine(
        amplitude: Int,
        samples: Int,
    ) = ShortArray(samples) { (amplitude * sin(2 * PI * 440 * it / rate)).toInt().toShort() }

    @Test
    fun silenceIsAllZeros() {
        val envelope = LoudnessEnvelope(ShortArray(800), rate)
        assertEquals(5, envelope.frameCount)
        assertTrue((0 until envelope.frameCount).all { envelope.level(it) == 0f })
    }

    @Test
    fun emptyInputHasNoFrames() {
        val envelope = LoudnessEnvelope(ShortArray(0), rate)
        assertEquals(0, envelope.frameCount)
        assertEquals(0f, envelope.levelAt(0))
    }

    @Test
    fun aLouderFrameHasAHigherLevelAndTheLoudestIsOne() {
        val envelope = LoudnessEnvelope(sine(5000, 160) + sine(20000, 160), rate)
        assertEquals(2, envelope.frameCount)
        assertEquals(1f, envelope.level(1), 1e-6f)
        assertTrue(envelope.level(0) in 0.01f..0.99f)
        // A quarter of the amplitude is a quarter of the RMS, which the square root lifts to a half.
        assertEquals(sqrt(0.25f), envelope.level(0), 0.01f)
    }

    @Test
    fun theLastPartialFrameCounts() {
        val envelope = LoudnessEnvelope(sine(10000, 160 * 2 + 40), rate)
        assertEquals(3, envelope.frameCount)
        // The partial frame is averaged over its own length, so a steady tone is as loud there as in a full frame.
        assertEquals(envelope.level(1), envelope.level(2), 0.05f)
    }

    @Test
    fun frameLengthIsConfigurable() {
        val envelope = LoudnessEnvelope(sine(10000, 800), rate, frameMillis = 50)
        assertEquals(400, envelope.samplesPerFrame)
        assertEquals(2, envelope.frameCount)
    }

    @Test
    fun levelAtMapsAPositionToItsFrame() {
        val envelope = LoudnessEnvelope(ShortArray(160) + sine(10000, 160) + ShortArray(160), rate)
        assertEquals(0f, envelope.levelAt(0))
        assertEquals(0f, envelope.levelAt(159))
        assertEquals(1f, envelope.levelAt(160), 1e-6f)
        assertEquals(1f, envelope.levelAt(319), 1e-6f)
        assertEquals(0f, envelope.levelAt(320))
    }

    @Test
    fun levelAtClampsBeforeTheStartAndIsZeroPastTheEnd() {
        val envelope = LoudnessEnvelope(sine(10000, 160), rate)
        assertEquals(1f, envelope.levelAt(-50), 1e-6f)
        assertEquals(0f, envelope.levelAt(160))
        assertEquals(0f, envelope.levelAt(Long.MAX_VALUE))
    }

    @Test
    fun littleEndianBytesGiveTheSameEnvelopeAsShorts() {
        val samples = sine(3000, 200) + sine(30000, 200) + shortArrayOf(Short.MIN_VALUE, -1, 0, 1)
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            bytes[2 * i] = (s.toInt() and 0xFF).toByte()
            bytes[2 * i + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        val fromShorts = LoudnessEnvelope(samples, rate)
        val fromBytes = LoudnessEnvelope.fromLittleEndianBytes(bytes + byteArrayOf(7), rate)
        assertEquals(fromShorts.frameCount, fromBytes.frameCount)
        for (i in 0 until fromShorts.frameCount) assertEquals(fromShorts.level(i), fromBytes.level(i))
    }
}
