package com.glassstorm.phonemanager.adapter.ai

import com.glassstorm.phonemanager.core.domain.adapter.speech.TtsPort
import com.glassstorm.phonemanager.core.model.AudioFormat
import hk.edu.hkmu.core.config.TtsConfig
import hk.edu.hkmu.tts.SherpaTtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The DEVICE implementation of [TtsPort], backed by ai-android's [SherpaTtsEngine].
 *
 * ai-android emits normalized Float32 [hk.edu.hkmu.core.engine.AudioChunk]s at the
 * MODEL's own rate (the Cantonese VITS voice runs at 22 050 Hz). The hub's contract is
 * PCM16 mono at exactly [AudioFormat.SAMPLE_RATE_HZ], so the adapter concatenates the
 * chunks and resamples to 16 kHz before converting to little-endian PCM16. The rate is
 * read from the engine's own chunks, never assumed.
 *
 * @param modelRoot the directory holding the selected voice's model directory.
 * @param config the TTS engine config (language selects the voice).
 */
class AiTtsPort(
    modelRoot: File,
    config: TtsConfig = TtsConfig(),
) : TtsPort,
    AutoCloseable {
    private val engine = SherpaTtsEngine(config = config, modelRoot = modelRoot)

    override suspend fun synthesize(text: String): ByteArray =
        withContext(Dispatchers.Default) {
            if (text.isEmpty()) return@withContext ByteArray(0)
            val chunks = engine.synthesize(text).toList()
            if (chunks.isEmpty()) return@withContext ByteArray(0)
            val sourceRateHz = chunks.first().sampleRateHz
            val concatenated = concatenate(chunks.map { it.samples })
            val resampled = AudioPcm.resample(concatenated, sourceRateHz, AudioFormat.SAMPLE_RATE_HZ)
            AudioPcm.toPcm16Le(resampled)
        }

    /** Releases the synthesis sessions. Idempotent. */
    override fun close() = engine.close()

    private fun concatenate(parts: List<FloatArray>): FloatArray {
        val total = parts.sumOf { it.size }
        val out = FloatArray(total)
        var offset = 0
        for (part in parts) {
            part.copyInto(out, destinationOffset = offset)
            offset += part.size
        }
        return out
    }
}
