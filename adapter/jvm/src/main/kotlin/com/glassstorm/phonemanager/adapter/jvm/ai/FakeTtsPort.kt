package com.glassstorm.phonemanager.adapter.jvm.ai

import com.glassstorm.phonemanager.core.domain.adapter.speech.TtsPort
import com.glassstorm.phonemanager.core.model.AudioFormat

/** Samples synthesized per input character: 20 ms at 16 kHz. */
private const val SAMPLES_PER_CHAR = AudioFormat.CHUNK_SAMPLES

/**
 * The DEFAULT text-to-speech engine for every JVM/unit build: fully offline and
 * DETERMINISTIC.
 *
 * Output is raw little-endian PCM16 mono at exactly
 * [AudioFormat.SAMPLE_RATE_HZ] (16 kHz). A character-scaled length and a
 * digest-derived square wave make the bytes a pure function of the input text, so
 * a relay test can assert a stable frame count without a native engine. Empty text
 * yields an empty array.
 */
class FakeTtsPort : TtsPort {
    override suspend fun synthesize(text: String): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        val samples = text.length * SAMPLES_PER_CHAR
        val out = ByteArray(samples * AudioFormat.BYTES_PER_SAMPLE)
        val amplitude = (Fnv1a.hash(text.toByteArray()) % 64L) * 0x0100L
        for (sample in 0 until samples) {
            val value = if (sample % 2 == 0) amplitude else -amplitude
            // Little-endian PCM16.
            out[sample * 2] = (value.toInt() and 0xFF).toByte()
            out[sample * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }
}
