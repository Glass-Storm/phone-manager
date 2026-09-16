package com.glassstorm.phonemanager.domain.adapter.speech

/**
 * Port (interface) for speech-to-text.
 *
 * Owned by `:domain`; implemented in `:adapter` (an offline mock by default, a
 * cloud provider when configured) and resolved through the Context registry.
 * Defining the seam here keeps `:service` free of any concrete engine.
 *
 * Audio is raw little-endian PCM16 mono. Implementations MAY return `null` when
 * a chunk carries no complete utterance.
 *
 * A recognition session is identified by the relay [sessionId] the hub minted
 * ([com.glassstorm.phonemanager.domain.dto.RelaySession.GoSessionId]) so engines
 * that stream state internally can correlate and release it. [GoClose] is the
 * deterministic teardown: it MUST be idempotent and MUST be called exactly once
 * per session by the relay.
 */
interface SttPort {
    /**
     * Transcribe one chunk of [audioPcm16] for [sessionId], captured at
     * [sampleRateHz].
     *
     * Returns the recognized text, or `null` when the chunk produced no complete
     * utterance. Implementations MUST NOT throw on ordinary `null` outcomes.
     */
    suspend fun GoTranscribe(
        sessionId: String,
        audioPcm16: ByteArray,
        sampleRateHz: Int,
    ): String?

    /** Release any engine state for [sessionId]. Idempotent. */
    suspend fun GoClose(sessionId: String)
}
