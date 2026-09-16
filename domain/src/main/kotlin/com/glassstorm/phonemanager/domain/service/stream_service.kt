package com.glassstorm.phonemanager.domain.service

import com.glassstorm.phonemanager.domain.dto.RelayResult
import com.glassstorm.phonemanager.domain.dto.RelaySession
import com.glassstorm.phonemanager.domain.dto.RelayStats
import kotlinx.coroutines.flow.Flow

/**
 * Audio/video relay use-cases.
 *
 * Port (interface) owned by `:domain`; implemented in `:service`, which resolves
 * its `SttPort` / `FrameSink` collaborators through the Context registry.
 *
 * Media payloads are OPAQUE: audio is raw little-endian PCM16 mono @ 16 kHz and
 * video is raw H.264 NAL units the hub never decodes or re-encodes.
 *
 * ## Queue policy
 *
 * Audio and video have SEPARATE queues with SEPARATE policies:
 *
 *  * audio PARKS — [GoPushAudio] suspends while the audio queue is full, so audio
 *    is NEVER dropped (it is the irreplaceable half of the relay);
 *  * video DROPS OLDEST — [GoPushVideo] evicts the oldest queued frame when full,
 *    because a live video stream tolerates losing stale frames.
 *
 * Frames are consumed by per-session pump loops, so pushing is decoupled from
 * recognition/sink latency. All pushes for an unknown or closed session are
 * no-ops: a frame must never outlive the stream that opened it.
 */
interface StreamService {
    /** Bind a new relay session to [deviceId] and return its handle. */
    fun GoOpenSession(deviceId: String): RelaySession

    /** Enqueue opaque PCM16 audio. Parks (suspends) while the audio queue is full: audio is NEVER dropped. */
    suspend fun GoPushAudio(sessionId: String, audioPcm16: ByteArray, sampleRateHz: Int)

    /** Enqueue an opaque H.264 NAL. Evicts the OLDEST queued video frame when full. */
    fun GoPushVideo(sessionId: String, h264Nal: ByteArray)

    /** Relayed utterances for [sessionId]; completes when the session closes. */
    fun GoResults(sessionId: String): Flow<RelayResult>

    /** Idempotent teardown: drains queued frames, closes the STT session exactly once. */
    suspend fun GoCloseSession(sessionId: String)

    /** Point-in-time accounting snapshot. */
    fun GoStats(): RelayStats

    companion object {
        /** Sample rate of the frozen `audio_pcm16_16k` wire payload. */
        const val GoAudioSampleRateHz: Int = 16_000
    }
}
