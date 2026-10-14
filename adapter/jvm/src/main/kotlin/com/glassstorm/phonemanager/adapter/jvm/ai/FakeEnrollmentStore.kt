package com.glassstorm.phonemanager.adapter.jvm.ai

import com.glassstorm.phonemanager.core.domain.adapter.enrollment.EnrollmentStore
import com.glassstorm.phonemanager.core.model.AudioFormat
import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.glassstorm.phonemanager.core.model.EnrolledSpeaker
import com.glassstorm.phonemanager.core.model.EnrollmentName
import com.glassstorm.phonemanager.core.model.Voiceprint

/** Dimensions of the deterministic fake voiceprint. */
private const val FAKE_EMBEDDING_DIM = 16

/**
 * The DEFAULT enrollment directory for every JVM/unit build: in-memory and
 * DETERMINISTIC.
 *
 * It enforces the same hub-owned policy as the device adapter — the name rule
 * ([EnrollmentName]) and the 8 s audio minimum
 * ([AudioFormat.ENROLLMENT_MIN_BYTES]) — and derives a reproducible voiceprint
 * from the audio bytes, so an enroll-then-resolve test needs no native embedding
 * model. Re-enrolling an existing name REPLACES it, keeping insertion order stable.
 */
class FakeEnrollmentStore : EnrollmentStore {
    private val speakers = mutableListOf<EnrolledSpeaker>()
    private val lock = Any()

    override suspend fun enroll(
        name: String,
        pcm16: ByteArray,
    ): EnrollOutcome {
        if (!EnrollmentName.isValid(name)) return EnrollOutcome.rejected(EnrollOutcome.REASON_INVALID_NAME)
        if (pcm16.size < AudioFormat.ENROLLMENT_MIN_BYTES) {
            return EnrollOutcome.rejected(EnrollOutcome.REASON_AUDIO_TOO_SHORT)
        }
        synchronized(lock) {
            speakers.removeAll { it.name == name }
            speakers.add(EnrolledSpeaker(name, voiceprintFrom(pcm16)))
        }
        return EnrollOutcome.accepted
    }

    override suspend fun names(): List<String> = synchronized(lock) { speakers.map { it.name } }

    override suspend fun enrollments(): List<EnrolledSpeaker> = synchronized(lock) { speakers.toList() }

    /** A deterministic 16-dim embedding whose values are derived from the audio digest. */
    private fun voiceprintFrom(pcm16: ByteArray): Voiceprint {
        val digest = Fnv1a.hash(pcm16)
        val embedding = FloatArray(FAKE_EMBEDDING_DIM) { index ->
            ((digest ushr (index % 32)) and 0xFF).toFloat() / 255f
        }
        return Voiceprint(embedding = embedding, modelId = "fake-fnv1a")
    }
}
