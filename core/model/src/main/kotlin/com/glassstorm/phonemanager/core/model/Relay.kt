package com.glassstorm.phonemanager.core.model

/**
 * Which side of the ecosystem a live stream session belongs to. Pure data: the
 * domain's own vocabulary for the wire `ecosys.v1.DeviceRole`, so `:core:service`
 * can route between peers without importing the generated proto types.
 *
 * A session binds its role ONCE, from the explicit `stream_open` frame the peer
 * sends first; [UNSPECIFIED] is the zero value for a legacy session that has not
 * (yet) declared one.
 */
enum class PeerRole {
    UNSPECIFIED,
    GLASS,
    DAEMON,
}

/**
 * A live media relay session bound to one paired device. Pure data.
 *
 * [sessionId] is minted by the hub per inbound stream so audio and video
 * frames from the same peer can be correlated and torn down together.
 */
data class RelaySession(
    val sessionId: String,
    val deviceId: String,
)

/**
 * One recognized utterance produced from an audio frame. Pure data; mirrors the
 * `ecosys.v1.StreamResult` message without depending on the generated types.
 */
data class RelayResult(
    val text: String,
    val speakerLabel: String,
    val ptsMs: Long,
)

/**
 * One output the relay delivers to a session's results flow. Pure data.
 *
 * The relay orchestrates the peer-to-peer routing (`image` -> gesture recognition
 * + synthesis, `audio` -> transcription, `enroll_request` -> enrollment) and
 * emits a [RelayOutput] per produced result; the transport maps each variant
 * onto its `ecosys.v1.StreamFrame` member. Keeping the outputs as domain values
 * is what lets `:core:service` route without ever seeing `ecosys.v1.*`.
 */
sealed interface RelayOutput {
    /** A recognized utterance, mapped to `StreamResult` with the speaker tag. */
    data class Speech(
        val result: RelayResult,
    ) : RelayOutput

    /** A recognized gesture, mapped to `GestureResult` for the glasses. */
    data class Gesture(
        val text: String,
        val label: String,
        val confidence: Float,
        val ptsMs: Long,
    ) : RelayOutput

    /** Hub-synthesized speech (PCM16 mono 16 kHz), mapped to `TtsAudio`. */
    data class TtsAudio(
        val pcm16: ByteArray,
        val ptsMs: Long,
    ) : RelayOutput

    /** The reply to an enrollment request, mapped to `EnrollResponse`. */
    data class EnrollResponse(
        val ok: Boolean,
        val reason: String?,
    ) : RelayOutput
}

/**
 * A point-in-time snapshot of relay accounting. Pure data.
 *
 * The counters describe the RELAY's own queue decisions, never the media
 * contents:
 *
 *  * [audioFrames] — audio frames ACCEPTED into the queue (audio is never
 *    dropped, so accepted == offered here);
 *  * [videoFrames] — video NALs OFFERED to the video queue. This is incremented
 *    before admission: it counts every NAL the peer pushed at a live session,
 *    including one that was dropped-oldest and, in the rare full-queue race, one
 *    that could not be re-enqueued after an eviction. It is NOT "frames
 *    delivered" — a NAL counted here may since have been evicted (see
 *    [videoDropped]);
 *  * [videoDropped] — video NALs removed from the queue by the drop-oldest
 *    policy to make room because the video queue was full. `videoFrames` and
 *    `videoDropped` are independent monotonic totals, not a partition: in the
 *    rare full-queue race a NAL offered immediately after an eviction can be lost
 *    without being counted as an eviction, so they are offered and evicted
 *    counts respectively, never "delivered minus dropped";
 *  * [transcripts] — recognized utterances emitted;
 *  * [liveSessions] — currently open sessions.
 */
data class RelayStats(
    val audioFrames: Long,
    val videoFrames: Long,
    val videoDropped: Long,
    val transcripts: Long,
    val liveSessions: Int,
)
