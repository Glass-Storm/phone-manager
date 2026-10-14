package com.glassstorm.phonemanager.adapter.ai

import com.glassstorm.phonemanager.core.model.AudioFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Contract tests for the pure PCM helpers.
 *
 * These run on the host JVM (no engine, no native), which is exactly the point: the
 * hub's canonical 16 kHz PCM16 little-endian format is byte-verified without an ARM
 * device, and the resampler's length/endpoint behaviour is proven independently of the
 * VITS model that produces 22 050 Hz audio.
 */
class AudioPcmTest {
    @Test
    fun `pcm16 round-trips through floats`() {
        val samples = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f)

        val roundTripped = AudioPcm.toFloats(AudioPcm.toPcm16Le(samples))

        assertThat(roundTripped.size).isEqualTo(samples.size)
        for (index in samples.indices) {
            assertThat(roundTripped[index]).isWithin(1e-3f).of(samples[index])
        }
    }

    @Test
    fun `pcm16 is little-endian`() {
        val bytes = AudioPcm.toPcm16Le(floatArrayOf(1f))

        assertThat(bytes.size).isEqualTo(AudioFormat.BYTES_PER_SAMPLE)
        // 32767 == 0x7FFF -> little-endian low byte 0xFF, high byte 0x7F.
        assertThat(bytes[0]).isEqualTo(0xFF.toByte())
        assertThat(bytes[1]).isEqualTo(0x7F.toByte())
    }

    @Test
    fun `out-of-range samples clamp instead of wrapping`() {
        val clamped = AudioPcm.toPcm16Le(floatArrayOf(2f, -2f))
        val inRange = AudioPcm.toPcm16Le(floatArrayOf(1f, -1f))

        assertThat(clamped).isEqualTo(inRange)
    }

    @Test
    fun `resampling to the same rate is a no-op`() {
        val samples = floatArrayOf(0.1f, 0.2f, 0.3f)

        assertThat(AudioPcm.resample(samples, 16_000, 16_000)).isSameInstanceAs(samples)
    }

    @Test
    fun `resampling 22050 to 16000 shortens by the rate ratio`() {
        val samples = FloatArray(22_050) { 0.25f }

        val resampled = AudioPcm.resample(samples, 22_050, 16_000)

        assertThat(resampled.size).isEqualTo(16_000)
        assertThat(resampled.first()).isWithin(1e-3f).of(0.25f)
        assertThat(resampled.last()).isWithin(1e-3f).of(0.25f)
    }

    @Test
    fun `resampling empty input yields empty output`() {
        assertThat(AudioPcm.resample(FloatArray(0), 22_050, 16_000)).isEmpty()
    }
}
