package com.glassstorm.phonemanager.adapter.ai

import com.glassstorm.phonemanager.core.domain.adapter.enrollment.EnrollmentStore
import com.glassstorm.phonemanager.core.model.AudioFormat
import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.glassstorm.phonemanager.core.model.EnrolledSpeaker
import com.glassstorm.phonemanager.core.model.EnrollmentName
import com.glassstorm.phonemanager.core.model.Voiceprint
import hk.edu.hkmu.core.config.SpeakerEmbeddingConfig
import hk.edu.hkmu.core.engine.AudioChunk
import hk.edu.hkmu.diarize.SherpaSpeakerEmbeddingExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The DEVICE implementation of [EnrollmentStore]: a name -> voiceprint directory
 * computed with ai-android's speaker-embedding extractor and persisted to an
 * app-private file.
 *
 * The hub owns this data end to end: the wire contract never carries a voiceprint, only
 * an `enroll_request` with the raw audio. The name rule ([EnrollmentName]) and the 8 s
 * minimum ([AudioFormat.ENROLLMENT_MIN_BYTES]) are enforced here, on the hub, before the
 * (expensive) embedding call. Re-enrolling a name replaces its voiceprint IN PLACE, so
 * the directory keeps its original insertion order.
 *
 * Persistence is a small tab-separated text file (one speaker per line: name, model id,
 * then a comma-separated embedding), so the store needs no database and no JSON
 * dependency, and a corrupt file degrades to an empty directory rather than a crash.
 *
 * @param storageFile the app-private file the directory is persisted to.
 * @param embedder turns one enrollment clip into a voiceprint (the on-device
 *   sherpa extractor in production; an injected double under test).
 */
class AiEnrollmentStore internal constructor(
    private val storageFile: File,
    private val embedder: VoiceprintEmbedder,
) : EnrollmentStore,
    AutoCloseable {
    /**
     * Production constructor: builds the on-device sherpa speaker-embedding extractor.
     * The extractor loads its native backend LAZILY, so construction never touches the
     * native library — only the first enrollment does.
     *
     * @param modelDirectory the directory holding the speaker-embedding ONNX graph.
     * @param storageFile the app-private file the directory is persisted to.
     * @param config the embedding extractor config.
     */
    constructor(
        modelDirectory: File,
        storageFile: File,
        config: SpeakerEmbeddingConfig = SpeakerEmbeddingConfig(),
    ) : this(
        storageFile = storageFile,
        embedder =
            SherpaVoiceprintEmbedder(
                SherpaSpeakerEmbeddingExtractor.create(config = config, modelDir = modelDirectory),
            ),
    )

    private val speakers = LinkedHashMap<String, EnrolledSpeaker>()
    private val lock = Any()
    private val embedderMutex = Mutex()

    init {
        load()
    }

    override suspend fun enroll(
        name: String,
        pcm16: ByteArray,
    ): EnrollOutcome {
        if (!EnrollmentName.isValid(name)) return EnrollOutcome.rejected(EnrollOutcome.REASON_INVALID_NAME)
        if (pcm16.size < AudioFormat.ENROLLMENT_MIN_BYTES) {
            return EnrollOutcome.rejected(EnrollOutcome.REASON_AUDIO_TOO_SHORT)
        }
        val embedding =
            embedderMutex.withLock {
                embedder.embeddingFor(pcm16)
            } ?: return EnrollOutcome.rejected(EnrollOutcome.REASON_VOICEPRINT_FAILED)
        synchronized(lock) {
            // A plain put on an EXISTING LinkedHashMap key updates the value in place,
            // keeping the original position. Do NOT remove-then-put: that would move a
            // re-enrolled name to the END and break the documented order guarantee.
            speakers[name] = EnrolledSpeaker(name = name, voiceprint = Voiceprint(embedding, EMBEDDING_MODEL_ID))
            persist()
        }
        return EnrollOutcome.accepted
    }

    override suspend fun names(): List<String> = synchronized(lock) { speakers.keys.toList() }

    override suspend fun enrollments(): List<EnrolledSpeaker> = synchronized(lock) { speakers.values.toList() }

    /** Releases the embedding extractor. Idempotent. */
    override fun close() = embedder.close()

    private fun load() {
        if (!storageFile.isFile) return
        runCatching {
            storageFile.forEachLine { line ->
                val record = parse(line) ?: return@forEachLine
                speakers[record.name] = record
            }
        }.onFailure {
            speakers.clear()
        }
    }

    private fun parse(line: String): EnrolledSpeaker? {
        val fields = line.split('\t')
        if (fields.size < 3) return null
        val name = fields[0]
        val modelId = fields[1].ifEmpty { null }
        val embedding =
            fields[2]
                .split(',')
                .mapNotNull { it.toFloatOrNull() }
                .toFloatArray()
        if (name.isEmpty() || embedding.isEmpty()) return null
        return EnrolledSpeaker(name = name, voiceprint = Voiceprint(embedding, modelId))
    }

    private fun persist() {
        storageFile.parentFile?.mkdirs()
        storageFile.bufferedWriter().use { writer ->
            for (speaker in speakers.values) {
                writer.append(speaker.name)
                writer.append('\t')
                writer.append(speaker.voiceprint.modelId ?: "")
                writer.append('\t')
                writer.append(speaker.voiceprint.embedding.joinToString(separator = ","))
                writer.append('\n')
            }
        }
    }

    private companion object {
        /** Provenance stamped on every fake/device embedding so a model swap is detectable. */
        const val EMBEDDING_MODEL_ID: String = "hk.edu.hkmu.diarize.pyannote-3dspeaker-zh"
    }
}

/**
 * Turns one speaker's PCM16 enrollment clip into a voiceprint, or `null` when the
 * audio produced no embedding. Injected so the ordering/lifecycle logic below is
 * testable on the host JVM (the real sherpa backend is arm64-only native).
 */
internal interface VoiceprintEmbedder {
    /** Extracts a voiceprint from one 16 kHz mono PCM16 clip. */
    suspend fun embeddingFor(pcm16: ByteArray): FloatArray?

    /** Releases any native state. Idempotent. */
    fun close()
}

/** The on-device [VoiceprintEmbedder]: ai-android's sherpa speaker-embedding extractor. */
private class SherpaVoiceprintEmbedder(
    private val extractor: SherpaSpeakerEmbeddingExtractor,
) : VoiceprintEmbedder {
    override suspend fun embeddingFor(pcm16: ByteArray): FloatArray? {
        val samples = AudioPcm.toFloats(pcm16)
        val voiceprint =
            withContext(Dispatchers.Default) {
                extractor
                    .extract(flowOf(AudioChunk(samples = samples, sampleRateHz = AudioFormat.SAMPLE_RATE_HZ)))
                    .firstOrNull()
            }
        return voiceprint?.embedding
    }

    override fun close() = extractor.close()
}
