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
 */
interface SttPort {
    /**
     * Transcribe one chunk of [audioPcm16] captured at [sampleRateHz].
     *
     * Returns the recognized text, or `null` when the chunk produced no complete
     * utterance. Implementations MUST NOT throw on ordinary `null` outcomes.
     */
    suspend fun GoTranscribe(audioPcm16: ByteArray, sampleRateHz: Int): String?
}
