package com.glassstorm.phonemanager.adapter.ai

import com.glassstorm.phonemanager.core.domain.adapter.enrollment.EnrollmentStore
import com.glassstorm.phonemanager.core.domain.adapter.speech.SpeechPort
import com.glassstorm.phonemanager.core.model.AudioFormat
import com.glassstorm.phonemanager.core.model.SpeechUtterance
import hk.edu.hkmu.asr.SenseVoiceAsrEngine
import hk.edu.hkmu.core.config.AsrConfig
import hk.edu.hkmu.core.config.DiarizationConfig
import hk.edu.hkmu.core.config.SpeakerEmbeddingConfig
import hk.edu.hkmu.core.engine.AudioChunk
import hk.edu.hkmu.core.engine.DEFAULT_VOICEPRINT_MATCH_THRESHOLD
import hk.edu.hkmu.core.engine.EnrollmentDirectory
import hk.edu.hkmu.diarize.SherpaDiarizationEngine
import hk.edu.hkmu.diarize.SherpaSpeakerEmbeddingExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File
import hk.edu.hkmu.core.engine.SpeakerEnrollment as AiSpeakerEnrollment
import hk.edu.hkmu.core.engine.Voiceprint as AiVoiceprint

/**
 * The DEVICE implementation of [SpeechPort], composed from ai-android's ASR,
 * diarization and speaker-embedding engines plus the hub-owned [EnrollmentStore].
 *
 * The relay hands the port ONE 16 kHz chunk at a time, so each call is self-contained:
 * the chunk is transcribed by [SenseVoiceAsrEngine], segmented by
 * [SherpaDiarizationEngine] to locate the speaker turn, and an embedding is extracted
 * over that chunk to resolve the speaker against the enrolled directory. A speaker that
 * does not match any enrollment resolves to `null` — the hub's contract for an
 * unregistered speaker — and the adapter never invents a name.
 *
 * @param asrModelDirectory the directory holding the ASR `model*.onnx` + `tokens.txt`.
 * @param diarizationModelDirectory the directory holding the segmentation + embedding graphs.
 * @param enrollmentStore the hub-owned voiceprint directory used to name a speaker.
 * @param threshold the cosine-similarity threshold above which a speaker is considered a match.
 */
class AiSpeechPort(
    asrModelDirectory: File,
    diarizationModelDirectory: File,
    private val enrollmentStore: EnrollmentStore,
    asrConfig: AsrConfig = AsrConfig(),
    diarizationConfig: DiarizationConfig = DiarizationConfig(),
    embeddingConfig: SpeakerEmbeddingConfig = SpeakerEmbeddingConfig(),
    private val threshold: Float = DEFAULT_VOICEPRINT_MATCH_THRESHOLD,
) : SpeechPort {
    private val asr = SenseVoiceAsrEngine.create(config = asrConfig, modelDir = asrModelDirectory)
    private val diarizer = SherpaDiarizationEngine.create(config = diarizationConfig, modelDir = diarizationModelDirectory)
    private val embedder = SherpaSpeakerEmbeddingExtractor.create(config = embeddingConfig, modelDir = diarizationModelDirectory)

    override suspend fun transcribe(
        sessionId: String,
        pcm16: ByteArray,
    ): SpeechUtterance? =
        withContext(Dispatchers.Default) {
            if (pcm16.isEmpty()) return@withContext null
            val samples = AudioPcm.toFloats(pcm16)

            fun audio() = flowOf(AudioChunk(samples = samples, sampleRateHz = AudioFormat.SAMPLE_RATE_HZ))

            val text =
                asr
                    .transcribe(audio())
                    .firstOrNull()
                    ?.text
                    ?.takeIf { it.isNotBlank() }
                    ?: return@withContext null

            // Diarization locates the speaker turn; with a single relay chunk the turn
            // spans the chunk, and the embedding of that chunk is what we match.
            diarizer.diarize(audio()).firstOrNull()
            val embedding = embedder.extract(audio()).firstOrNull()
            SpeechUtterance(text = text, speakerName = embedding?.let { resolveSpeaker(it) })
        }

    /**
     * The engines here are stateless across chunks, so releasing a session is a no-op.
     * The contract's teardown is still honoured so the relay can call it unconditionally.
     */
    override suspend fun close(sessionId: String) = Unit

    /** Releases the ASR, diarization and embedding engines. Idempotent. */
    fun release() {
        embedder.close()
        diarizer.close()
        asr.close()
    }

    private suspend fun resolveSpeaker(embedding: AiVoiceprint): String? {
        val directory =
            EnrollmentDirectory(
                enrollments =
                    enrollmentStore.enrollments().map { enrolled ->
                        AiSpeakerEnrollment(
                            displayName = enrolled.name,
                            voiceprint = AiVoiceprint(enrolled.voiceprint.embedding, enrolled.voiceprint.modelId),
                        )
                    },
                threshold = threshold,
            )
        return directory.match(embedding)?.displayName
    }
}
