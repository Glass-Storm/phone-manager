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
 * (expensive) embedding call. Re-enrolling a name replaces its voiceprint while keeping
 * insertion order stable.
 *
 * Persistence is a small tab-separated text file (one speaker per line: name, model id,
 * then a comma-separated embedding), so the store needs no database and no JSON
 * dependency, and a corrupt file degrades to an empty directory rather than a crash.
 *
 * @param modelDirectory the directory holding the speaker-embedding ONNX graph.
 * @param storageFile the app-private file the directory is persisted to.
 * @param config the embedding extractor config.
 */
class AiEnrollmentStore(
    modelDirectory: File,
    private val storageFile: File,
    config: SpeakerEmbeddingConfig = SpeakerEmbeddingConfig(),
) : EnrollmentStore,
    AutoCloseable {
    private val extractor = SherpaSpeakerEmbeddingExtractor.create(config = config, modelDir = modelDirectory)
    private val speakers = LinkedHashMap<String, EnrolledSpeaker>()
    private val lock = Any()

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
        val embedding = embeddingFor(pcm16) ?: return EnrollOutcome.rejected(EnrollOutcome.REASON_VOICEPRINT_FAILED)
        synchronized(lock) {
            speakers.remove(name)
            speakers[name] = EnrolledSpeaker(name = name, voiceprint = Voiceprint(embedding, EMBEDDING_MODEL_ID))
            persist()
        }
        return EnrollOutcome.accepted
    }

    override suspend fun names(): List<String> = synchronized(lock) { speakers.keys.toList() }

    override suspend fun enrollments(): List<EnrolledSpeaker> = synchronized(lock) { speakers.values.toList() }

    /** Releases the embedding extractor. Idempotent. */
    override fun close() = extractor.close()

    private suspend fun embeddingFor(pcm16: ByteArray): FloatArray? {
        val samples = AudioPcm.toFloats(pcm16)
        val voiceprint =
            withContext(Dispatchers.Default) {
                extractor
                    .extract(flowOf(AudioChunk(samples = samples, sampleRateHz = AudioFormat.SAMPLE_RATE_HZ)))
                    .firstOrNull()
            }
        return voiceprint?.embedding
    }

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
