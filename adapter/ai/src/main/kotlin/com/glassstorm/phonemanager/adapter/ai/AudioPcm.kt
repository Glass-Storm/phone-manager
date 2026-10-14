package com.glassstorm.phonemanager.adapter.ai

import com.glassstorm.phonemanager.core.model.AudioFormat

/**
 * Pure PCM conversion helpers shared by the ai-android adapters.
 *
 * Every function here is a pure function of its arguments — no engine, no native,
 * no Android — so the byte-exactness of the hub's canonical format
 * ([AudioFormat.SAMPLE_RATE_HZ] PCM16 mono little-endian) is unit-testable on a
 * bare JVM, independent of the arm64 engine that actually performs the synthesis.
 */
object AudioPcm {
    /** The positive full-scale value a normalized `1.0f` sample maps to. */
    private const val FULL_SCALE: Float = 32_767f

    /**
     * Converts normalized Float32 samples in `[-1.0, 1.0]` to little-endian PCM16.
     *
     * Out-of-range samples are clamped, so a model that overshoots cannot wrap a
     * positive peak into a negative sample.
     */
    fun toPcm16Le(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * AudioFormat.BYTES_PER_SAMPLE)
        for (index in samples.indices) {
            val value = (samples[index].coerceIn(-1f, 1f) * FULL_SCALE).toInt()
            out[index * 2] = (value and 0xFF).toByte()
            out[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Converts little-endian PCM16 bytes to normalized Float32 in `[-1.0, 1.0]`,
     * the representation every ai-android engine consumes
     * ([hk.edu.hkmu.core.engine.AudioChunk]).
     */
    fun toFloats(pcm16: ByteArray): FloatArray {
        val sampleCount = pcm16.size / AudioFormat.BYTES_PER_SAMPLE
        return FloatArray(sampleCount) { index ->
            val low = pcm16[index * 2].toInt() and 0xFF
            val high = pcm16[index * 2 + 1].toInt()
            val value = (high shl 8) or low
            value.toShort().toFloat() / 32_768f
        }
    }

    /**
     * Linearly resamples [samples] from [sourceRateHz] to [targetRateHz].
     *
     * ai-android reports each engine's output rate at runtime (the Cantonese VITS
     * model runs at 22 050 Hz), while the hub's ONE canonical rate is 16 kHz, so the
     * adapter resamples rather than trusting the model. A no-op when the rates
     * already match; empty input yields empty output.
     */
    fun resample(
        samples: FloatArray,
        sourceRateHz: Int,
        targetRateHz: Int,
    ): FloatArray {
        if (sourceRateHz == targetRateHz || samples.isEmpty()) return samples
        val outLength = (samples.size.toLong() * targetRateHz / sourceRateHz).toInt()
        if (outLength <= 0) return FloatArray(0)
        return FloatArray(outLength) { index ->
            val sourcePosition = index.toDouble() * sourceRateHz / targetRateHz
            val lower = sourcePosition.toInt().coerceIn(0, samples.size - 1)
            val upper = (lower + 1).coerceAtMost(samples.size - 1)
            val fraction = (sourcePosition - lower).toFloat()
            samples[lower] * (1f - fraction) + samples[upper] * fraction
        }
    }
}
