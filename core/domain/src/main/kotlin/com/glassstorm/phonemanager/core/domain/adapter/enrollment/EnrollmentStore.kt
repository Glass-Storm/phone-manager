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
     * Whether this store is the on-device, model-backed implementation.
     *
     * `false` means the deterministic in-memory fake is bound because the
     * `diarization/` models were not provisioned: a stored voiceprint is not
     * persisted and can never match a real voice. The Settings screen renders the
     * "engine not enabled" state instead of a form that would mislead the
     * operator into thinking a speaker was registered.
     *
     * Defaults to `false` so a new implementation fails SAFE: an adapter that
     * really persists voiceprints must opt in by overriding this.
     */
    val deviceBacked: Boolean
        get() = false

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

    /**
     * Deletes the voiceprint stored under [name].
     *
     * Returns `true` when a voiceprint was removed, `false` when [name] was not
     * enrolled. A store that cannot persist voiceprints has nothing to delete, so
     * the default is a no-op returning `false`; a real adapter MUST override.
     */
    suspend fun remove(name: String): Boolean = false

    /** Every enrolled speaker (name + voiceprint), in insertion order. */
    suspend fun enrollments(): List<EnrolledSpeaker>
}
