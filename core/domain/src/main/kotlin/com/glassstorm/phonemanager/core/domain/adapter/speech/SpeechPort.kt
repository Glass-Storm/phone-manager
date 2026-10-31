package com.glassstorm.phonemanager.core.domain.adapter.speech

import com.glassstorm.phonemanager.core.model.SpeechUtterance

/**
 * Port (interface) for speech-to-text WITH speaker attribution (diarization).
 *
 * Owned by `:core:domain`; implemented in an adapter (the deterministic JVM fake
 * by default, the on-device ai-android ASR + diarization engines on a device).
 *
 * Audio is raw little-endian PCM16 mono at 16 kHz. A recognition session is
 * identified by the relay [sessionId] the hub minted, so an engine that keeps
 * streaming state can correlate and release it. Implementations MAY return `null`
 * when a chunk carries no complete utterance.
 */
interface SpeechPort {
    /**
     * Transcribes one chunk of [pcm16] for [sessionId].
     *
     * Returns the recognized [SpeechUtterance] (text plus an optional enrolled
     * speaker name), or `null` when the chunk produced no complete utterance. The
     * speaker name MUST be `null` when the speaker is not enrolled; an adapter
     * MUST NOT invent one. Implementations MUST NOT throw on a `null` outcome.
     */
    suspend fun transcribe(
        sessionId: String,
        pcm16: ByteArray,
    ): SpeechUtterance?

    /**
     * Drains any audio buffered for [sessionId] into a final utterance and
     * transcribes it.
     *
     * A peer may stop without the trailing silence that closes a streaming
     * utterance, so the relay calls this after its audio pump has drained and
     * before [close]. Returns the final [SpeechUtterance], or `null` when the
     * session has no buffered speech. This does not release the session: [close]
     * remains the lifecycle terminator.
     */
    suspend fun flush(sessionId: String): SpeechUtterance?

    /** Release any engine state for [sessionId]. Idempotent. */
    suspend fun close(sessionId: String)
}
