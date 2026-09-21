package com.glassstorm.phonemanager.domain.dto

/**
 * A live media relay session bound to one paired device. Pure data.
 *
 * [GoSessionId] is minted by the hub per inbound stream so audio and video
 * frames from the same peer can be correlated and torn down together.
 */
data class RelaySession(
    val GoSessionId: String,
    val GoDeviceId: String,
)

/**
 * One recognized utterance produced from an audio frame. Pure data; mirrors the
 * `ecosys.v1.StreamResult` message without depending on the generated types.
 */
data class RelayResult(
    val GoText: String,
    val GoSpeakerLabel: String,
    val GoPtsMs: Long,
)

/**
 * A point-in-time snapshot of relay accounting. Pure data.
 *
 * The counters describe the RELAY's own queue decisions, never the media
 * contents:
 *
 *  * [GoAudioFrames] — audio frames ACCEPTED into the queue (audio is never
 *    dropped, so accepted == offered here);
 *  * [GoVideoFrames] — video NALs OFFERED to the video queue. This is incremented
 *    before admission: it counts every NAL the peer pushed at a live session,
 *    including one that was dropped-oldest and, in the rare full-queue race, one
 *    that could not be re-enqueued after an eviction. It is NOT "frames
 *    delivered" — a NAL counted here may since have been evicted (see
 *    [GoVideoDropped]);
 *  * [GoVideoDropped] — video NALs removed from the queue by the drop-oldest
 *    policy to make room because the video queue was full. `GoVideoFrames` and
 *    `GoVideoDropped` are independent monotonic totals, not a partition: in the
 *    rare full-queue race a NAL offered immediately after an eviction can be lost
 *    without being counted as an eviction, so they are offered and evicted
 *    counts respectively, never "delivered minus dropped";
 *  * [GoTranscripts] — recognized utterances emitted;
 *  * [GoLiveSessions] — currently open sessions.
 */
data class RelayStats(
    val GoAudioFrames: Long,
    val GoVideoFrames: Long,
    val GoVideoDropped: Long,
    val GoTranscripts: Long,
    val GoLiveSessions: Int,
)
