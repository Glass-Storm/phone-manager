package com.glassstorm.phonemanager.core.domain.service

import com.glassstorm.phonemanager.core.model.LivePeer
import com.glassstorm.phonemanager.core.model.PeerRole
import com.glassstorm.phonemanager.core.model.RelayOutput
import com.glassstorm.phonemanager.core.model.RelaySession
import com.glassstorm.phonemanager.core.model.RelayStats
import kotlinx.coroutines.flow.Flow

/**
 * Audio/video relay use-cases.
 *
 * Port (interface) owned by `:core:domain`; implemented in `:service`, which
 * resolves its `SpeechPort` / `GesturePort` / `TtsPort` / `EnrollmentStore` /
 * `FrameSink` collaborators through the Context registry.
 *
 * Media payloads are OPAQUE: audio is raw little-endian PCM16 mono @ 16 kHz and
 * video is raw H.264 NAL units the hub never decodes or re-encodes. A JPEG still
 * ([pushImage]) is the ONE payload the hub decodes, and only inside the gesture
 * adapter — never here.
 *
 * ## Queue policy
 *
 * Audio, video and image have SEPARATE queues with SEPARATE policies:
 *
 *  * audio PARKS — [pushAudio] suspends while the audio queue is full, so audio
 *    is NEVER dropped (it is the irreplaceable half of the relay);
 *  * video DROPS OLDEST — [pushVideo] evicts the oldest queued frame when full,
 *    because a live video stream tolerates losing stale frames;
 *  * image PARKS — [pushImage] suspends while the image queue is full, so a
 *    still is never dropped without a counter.
 *
 * Frames are consumed by per-session pump loops, so pushing is decoupled from
 * recognition/sink latency. All pushes for an unknown or closed session are
 * no-ops: a frame must never outlive the stream that opened it.
 */
interface StreamService {
    /** Bind a new relay session to [deviceId] and return its handle. */
    fun openSession(deviceId: String): RelaySession

    /**
     * Bind the [role] a live session declared in its bootstrap `stream_open`
     * frame. The registry then resolves the OTHER peer's session by role, which
     * is what lets an image from the necklace reach the glasses. A no-op for
     * [PeerRole.UNSPECIFIED] or an unknown session; idempotent.
     */
    fun bindRole(
        sessionId: String,
        role: PeerRole,
    )

    /** Enqueue opaque PCM16 audio. Parks (suspends) while the audio queue is full: audio is NEVER dropped. */
    suspend fun pushAudio(
        sessionId: String,
        audioPcm16: ByteArray,
        sampleRateHz: Int,
    )

    /** Enqueue an opaque H.264 NAL. Evicts the OLDEST queued video frame when full. */
    fun pushVideo(
        sessionId: String,
        h264Nal: ByteArray,
    )

    /**
     * Enqueue a JPEG still for gesture recognition. Recognition runs in the
     * session's image pump (the adapter decodes; this layer never does), and its
     * output is routed to the glasses session plus synthesized to the caller.
     * Parks while the image queue is full: an image is never dropped silently.
     */
    suspend fun pushImage(
        sessionId: String,
        jpeg: ByteArray,
        ptsMs: Long,
    )

    /**
     * Enroll [name] with the speaker audio [pcm16] in the hub-owned directory and
     * emit the [RelayOutput.EnrollResponse] onto this session's results flow.
     * A no-op for an unknown or closed session.
     */
    suspend fun pushEnroll(
        sessionId: String,
        name: String,
        pcm16: ByteArray,
        ptsMs: Long,
    )

    /** Relay outputs for [sessionId]; completes when the session closes. */
    fun results(sessionId: String): Flow<RelayOutput>

    /**
     * A hot flow of EVERY relay output from EVERY live session (speech results and
     * gesture results), used by the diagnostics view to observe the hub without
     * binding to one session. It has NO replay: a collector that attaches after an
     * output was produced sees nothing until the next output, so a fresh observer
     * never seeds itself from a previous (possibly dead) session. The flow itself
     * never completes while the hub is alive.
     */
    fun outputs(): Flow<RelayOutput>

    /**
     * A point-in-time snapshot of the peers attached to live relay sessions, with
     * the role each one has bound ([PeerRole.UNSPECIFIED] when unbound).
     */
    fun livePeers(): List<LivePeer>

    /** Idempotent teardown: drains queued frames, closes the speech session exactly once. */
    suspend fun closeSession(sessionId: String)

    /** Point-in-time accounting snapshot. */
    fun stats(): RelayStats

    companion object {
        /** Sample rate of the frozen `audio_pcm16_16k` wire payload. */
        const val AUDIO_SAMPLE_RATE_HZ: Int = 16_000
    }
}
