package com.glassstorm.phonemanager.domain.service

import com.glassstorm.phonemanager.domain.dto.RelayResult
import com.glassstorm.phonemanager.domain.dto.RelaySession

/**
 * Audio/video relay use-cases.
 *
 * Port (interface) owned by `:domain`; implemented in `:service`, which resolves
 * its `SttPort` / `FrameSink` collaborators through the Context registry.
 *
 * Media payloads are OPAQUE: audio is raw little-endian PCM16 mono @ 16 kHz and
 * video is raw H.264 NAL units the hub never decodes or re-encodes.
 *
 * [GoPushAudio] returns a [RelayResult] only when the speech-to-text port
 * recognized an utterance; `null` means the frame was accepted but produced no
 * result (the steady state for partial audio).
 */
interface StreamService {
    /** Bind a new relay session to [deviceId] and return its handle. */
    fun GoOpenSession(deviceId: String): RelaySession

    /** Hand opaque PCM16 audio to the STT port, returning any recognized utterance. */
    suspend fun GoPushAudio(sessionId: String, audioPcm16: ByteArray, sampleRateHz: Int): RelayResult?

    /** Hand an opaque H.264 NAL to the video sink unchanged. */
    fun GoPushVideo(sessionId: String, h264Nal: ByteArray)

    /** Tear the session down. Idempotent for an unknown or already-closed session. */
    fun GoCloseSession(sessionId: String)

    companion object {
        /** Sample rate of the frozen `audio_pcm16_16k` wire payload. */
        const val GoAudioSampleRateHz: Int = 16_000
    }
}
