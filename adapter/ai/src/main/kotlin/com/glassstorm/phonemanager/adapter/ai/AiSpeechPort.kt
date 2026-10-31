package com.glassstorm.phonemanager.adapter.ai

import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import hk.edu.hkmu.core.engine.SpeakerEnrollment as AiSpeakerEnrollment
import hk.edu.hkmu.core.engine.Voiceprint as AiVoiceprint

private const val TAG = "AiSpeechPort"

/** One ASR call over a whole buffered utterance, returning EVERY detected segment. */
internal interface AsrTranscriber {
    suspend fun transcribe(samples: FloatArray): List<String>

    fun close()
}

/** One diarization pass over a whole buffered utterance. */
internal interface SpeakerDiarizer {
    suspend fun diarize(samples: FloatArray)

    fun close()
}

/** One speaker-embedding extraction over a whole buffered utterance. */
internal interface SpeakerEmbeddingSource {
    suspend fun embedding(samples: FloatArray): AiVoiceprint?

    fun close()
}

/**
 * The DEVICE implementation of [SpeechPort], composed from ai-android's ASR,
 * diarization and speaker-embedding engines plus the hub-owned [EnrollmentStore].
 *
 * The relay hands the port ONE 16 kHz chunk at a time (20 ms, 640 bytes), which is far
 * too short to contain a complete utterance. The port therefore accumulates chunks per
 * session through [UtteranceSegmenter], a pure energy-VAD state machine, and only calls
 * the engines once an utterance has closed (after roughly [silenceMillis] of silence or
 * when it reaches [maxUtteranceMillis]). The WHOLE buffered utterance is then transcribed
 * by [SenseVoiceAsrEngine], segmented by [SherpaDiarizationEngine] to locate the speaker
 * turn, and an embedding is extracted over that utterance to resolve the speaker against
 * the enrolled directory. A speaker that does not match any enrollment resolves to `null`
 * — the hub's contract for an unregistered speaker — and the adapter never invents a name.
 *
 * SenseVoice's own 300 ms VAD may split one hub utterance into several segments, so the
 * adapter joins EVERY returned segment instead of keeping only the first. The native
 * engines are shared singletons across session pumps, so each engine call is serialised
 * by its own [Mutex]: sherpa and MediaPipe handles are not safe for concurrent calls.
 *
 * @param asrModelDirectory the directory holding the ASR `model*.onnx` + `tokens.txt`.
 * @param diarizationModelDirectory the directory holding the segmentation + embedding graphs.
 * @param enrollmentStore the hub-owned voiceprint directory used to name a speaker.
 * @param threshold the cosine-similarity threshold above which a speaker is considered a match.
 * @param speechRmsThreshold chunk RMS that counts as speech (see [UtteranceSegmenter]).
 * @param silenceMillis trailing silence that closes an utterance.
 * @param maxUtteranceMillis maximum buffered utterance length before a forced close.
 */
class AiSpeechPort internal constructor(
    private val enrollmentStore: EnrollmentStore,
    private val asr: AsrTranscriber,
    private val diarizer: SpeakerDiarizer,
    private val embedder: SpeakerEmbeddingSource,
    private val threshold: Float = DEFAULT_VOICEPRINT_MATCH_THRESHOLD,
    speechRmsThreshold: Float = UtteranceSegmenter.DEFAULT_SPEECH_RMS_THRESHOLD,
    silenceMillis: Long = UtteranceSegmenter.DEFAULT_SILENCE_MILLIS,
    maxUtteranceMillis: Long = UtteranceSegmenter.DEFAULT_MAX_UTTERANCE_MILLIS,
) : SpeechPort {
    @Suppress("LongParameterList")
    constructor(
        asrModelDirectory: File,
        diarizationModelDirectory: File,
        enrollmentStore: EnrollmentStore,
        asrConfig: AsrConfig = AsrConfig(),
        diarizationConfig: DiarizationConfig = DiarizationConfig(),
        embeddingConfig: SpeakerEmbeddingConfig = SpeakerEmbeddingConfig(),
        threshold: Float = DEFAULT_VOICEPRINT_MATCH_THRESHOLD,
        speechRmsThreshold: Float = UtteranceSegmenter.DEFAULT_SPEECH_RMS_THRESHOLD,
        silenceMillis: Long = UtteranceSegmenter.DEFAULT_SILENCE_MILLIS,
        maxUtteranceMillis: Long = UtteranceSegmenter.DEFAULT_MAX_UTTERANCE_MILLIS,
    ) : this(
        enrollmentStore = enrollmentStore,
        asr = SenseVoiceAsrTranscriber(SenseVoiceAsrEngine.create(config = asrConfig, modelDir = asrModelDirectory)),
        diarizer =
            SherpaDiarizer(
                SherpaDiarizationEngine.create(config = diarizationConfig, modelDir = diarizationModelDirectory),
            ),
        embedder =
            SherpaEmbeddingSource(
                SherpaSpeakerEmbeddingExtractor.create(config = embeddingConfig, modelDir = diarizationModelDirectory),
            ),
        threshold = threshold,
        speechRmsThreshold = speechRmsThreshold,
        silenceMillis = silenceMillis,
        maxUtteranceMillis = maxUtteranceMillis,
    )

    private val asrMutex = Mutex()
    private val diarizerMutex = Mutex()
    private val embedderMutex = Mutex()
    private val segmenter =
        UtteranceSegmenter(
            speechRmsThreshold = speechRmsThreshold,
            silenceMillis = silenceMillis,
            maxUtteranceMillis = maxUtteranceMillis,
        )

    override suspend fun transcribe(
        sessionId: String,
        pcm16: ByteArray,
    ): SpeechUtterance? =
        try {
            // Only a finished utterance reaches the engines: a 20 ms chunk can never
            // be transcribed as a complete utterance on its own.
            val utterance = segmenter.accept(sessionId, pcm16) ?: return null
            withContext(Dispatchers.Default) {
                transcribeUtterance(utterance.pcm16)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "transcription failed for session $sessionId; dropping the utterance", failure)
            null
        }

    override suspend fun flush(sessionId: String): SpeechUtterance? =
        try {
            val utterance = segmenter.flush(sessionId) ?: return null
            withContext(Dispatchers.Default) {
                transcribeUtterance(utterance.pcm16)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "flushing the last utterance of session $sessionId failed", failure)
            null
        }

    private suspend fun transcribeUtterance(pcm16: ByteArray): SpeechUtterance? {
        val samples = AudioPcm.toFloats(pcm16)

        val text =
            asrMutex
                .withLock { asr.transcribe(samples) }
                .joinToString(separator = " ") { it.trim() }
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: return null

        // Diarization locates the speaker turn; with one whole utterance the turn spans
        // it, and the embedding of that utterance is what we match.
        diarizerMutex.withLock { diarizer.diarize(samples) }
        val embedding = embedderMutex.withLock { embedder.embedding(samples) }
        return SpeechUtterance(text = text, speakerName = embedding?.let { resolveSpeaker(it) })
    }

    /** Drops the session's buffered (not yet closed) utterance. Idempotent. */
    override suspend fun close(sessionId: String) {
        try {
            segmenter.close(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "releasing session $sessionId failed", failure)
        }
    }

    /** Releases the ASR, diarization and embedding engines. Idempotent. */
    fun release() {
        closeEngine("embedding") { embedder.close() }
        closeEngine("diarization") { diarizer.close() }
        closeEngine("asr") { asr.close() }
    }

    private fun closeEngine(
        name: String,
        close: () -> Unit,
    ) {
        try {
            close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "releasing the $name engine failed", failure)
        }
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

private fun audioFlow(samples: FloatArray) = flowOf(AudioChunk(samples = samples, sampleRateHz = AudioFormat.SAMPLE_RATE_HZ))

private class SenseVoiceAsrTranscriber(
    private val engine: SenseVoiceAsrEngine,
) : AsrTranscriber {
    override suspend fun transcribe(samples: FloatArray): List<String> =
        engine
            .transcribe(audioFlow(samples))
            .toList()
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }

    override fun close() = engine.close()
}

private class SherpaDiarizer(
    private val engine: SherpaDiarizationEngine,
) : SpeakerDiarizer {
    override suspend fun diarize(samples: FloatArray) {
        engine.diarize(audioFlow(samples)).firstOrNull()
    }

    override fun close() = engine.close()
}

private class SherpaEmbeddingSource(
    private val engine: SherpaSpeakerEmbeddingExtractor,
) : SpeakerEmbeddingSource {
    override suspend fun embedding(samples: FloatArray): AiVoiceprint? = engine.extract(audioFlow(samples)).firstOrNull()

    override fun close() = engine.close()
}
