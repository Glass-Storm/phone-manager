package com.glassstorm.phonemanager.core.domain.adapter.enrollment

/**
 * Port (interface) for capturing a ONE-SHOT voice sample from the hub's own
 * microphone.
 *
 * The hub is normally a passive relay: peer audio arrives as `audio_pcm16_16k`
 * frames and the hub never records anything. Voiceprint enrollment is the single
 * exception — the operator speaks a sentence into the phone so the hub can learn
 * that voice — and this port is its capture edge.
 *
 * Audio is raw little-endian PCM16 mono at 16 kHz, the hub's canonical format
 * ([com.glassstorm.phonemanager.core.model.AudioFormat]), so a capture can be
 * handed straight to [EnrollmentStore.enroll] with no conversion. Implementations
 * MUST return `null` instead of throwing when capture is impossible (permission
 * missing, microphone busy, device without an input), and MUST return the bytes
 * actually captured, even when fewer than requested: the 8 s minimum is enforced
 * once, by the store, so the caller gets the precise rejection reason.
 */
interface VoiceCapturePort {
    /**
     * Records up to [durationMillis] of PCM16 mono 16 kHz audio.
     *
     * Returns the captured bytes, or `null` when the microphone could not be
     * opened or read at all.
     */
    suspend fun record(durationMillis: Long): ByteArray?
}
