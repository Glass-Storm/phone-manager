package com.glassstorm.phonemanager.core.domain.adapter.enrollment

import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.glassstorm.phonemanager.core.model.EnrolledSpeaker

/**
 * Port (interface) for the HUB-OWNED voiceprint enrollment directory: a mapping
 * from a display name to that speaker's voiceprint.
 *
 * Owned by `:core:domain`; backed by the app's persistence (SQLite) on a device
 * and by an in-memory fake on the JVM. The wire contract never carries it:
 * enrollment rides the stream as an `enroll_request`, and the hub validates and
 * stores the voiceprint itself.
 *
 * The name rule (`^[\p{Han}A-Za-z0-9]+$`, 1–24 chars) and the 8 s audio minimum
 * are enforced here, at the hub, on every implementation.
 */
interface EnrollmentStore {
    /**
     * Enrolls [name] with the speaker audio [pcm16] (PCM16 mono 16 kHz).
     *
     * Returns [EnrollOutcome.accepted] on success, or a rejection carrying
     * [EnrollOutcome.REASON_INVALID_NAME] / [EnrollOutcome.REASON_AUDIO_TOO_SHORT].
     */
    suspend fun enroll(
        name: String,
        pcm16: ByteArray,
    ): EnrollOutcome

    /** The display names currently enrolled, in insertion order. */
    suspend fun names(): List<String>

    /** Every enrolled speaker (name + voiceprint), in insertion order. */
    suspend fun enrollments(): List<EnrolledSpeaker>
}
