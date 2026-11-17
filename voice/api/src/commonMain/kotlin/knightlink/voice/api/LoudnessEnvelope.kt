package knightlink.voice.api

import kotlin.math.sqrt

/**
 * How loud a spoken line is over time, for a screen to animate in step with the sound.
 *
 * The RMS of each frame is divided by the loudest frame, so every line fills the range whatever its volume, and then passed through
 * a square root. Speech is mostly quiet between a few loud syllables; on a linear scale those pauses and soft endings would be
 * nearly flat, while the square root lifts them to something visible and still keeps silence at 0 and the loudest frame at 1. It
 * is monotonic and needs no tuned constants (a decibel scale would need a floor to choose).
 *
 * @param samples 16-bit mono PCM
 * @param sampleRate samples per second
 * @param frameMillis length of one frame, 20 ms by default, which is also how often the level is reported
 */
public class LoudnessEnvelope(
    samples: ShortArray,
    public val sampleRate: Int,
    public val frameMillis: Int = DEFAULT_FRAME_MILLIS,
) {
    init {
        require(sampleRate > 0) { "Sample rate must be positive" }
        require(frameMillis > 0) { "Frame length must be positive" }
    }

    /** Samples in one frame. */
    public val samplesPerFrame: Int = maxOf(1, (sampleRate.toLong() * frameMillis / MILLIS_PER_SECOND).toInt())

    private val levels: FloatArray = compute(samples)

    /** Number of frames, including a last partial one. */
    public val frameCount: Int get() = levels.size

    /** The level of frame [index], 0 to 1; 0 outside the line. */
    public fun level(index: Int): Float = levels.getOrElse(index) { 0f }

    /**
     * The level at [positionSamples] samples into the line, for syncing with playback. A position before the start gives the first
     * frame; one past the end gives 0.
     */
    public fun levelAt(positionSamples: Long): Float {
        val frame = maxOf(0L, positionSamples) / samplesPerFrame
        return if (frame >= levels.size) 0f else levels[frame.toInt()]
    }

    private fun compute(samples: ShortArray): FloatArray {
        val frames = (samples.size + samplesPerFrame - 1) / samplesPerFrame
        val rms = DoubleArray(frames)
        for (frame in 0 until frames) {
            val from = frame * samplesPerFrame
            val to = minOf(samples.size, from + samplesPerFrame)
            var sum = 0.0
            for (i in from until to) sum += samples[i].toDouble() * samples[i]
            rms[frame] = sqrt(sum / (to - from))
        }
        val loudest = rms.maxOrNull() ?: 0.0
        if (loudest == 0.0) return FloatArray(frames)
        return FloatArray(frames) { sqrt(rms[it] / loudest).toFloat() }
    }

    public companion object {
        /** The frame length of the level updates the [SpeechEngine] contract promises. */
        public const val DEFAULT_FRAME_MILLIS: Int = 20

        private const val MILLIS_PER_SECOND = 1000

        /** Reads 16-bit little-endian mono PCM, as most text-to-speech engines render it; a trailing odd byte is ignored. */
        public fun fromLittleEndianBytes(
            pcm: ByteArray,
            sampleRate: Int,
            frameMillis: Int = DEFAULT_FRAME_MILLIS,
        ): LoudnessEnvelope =
            LoudnessEnvelope(
                ShortArray(pcm.size / 2) { ((pcm[2 * it].toInt() and 0xFF) or (pcm[2 * it + 1].toInt() shl 8)).toShort() },
                sampleRate,
                frameMillis,
            )
    }
}
