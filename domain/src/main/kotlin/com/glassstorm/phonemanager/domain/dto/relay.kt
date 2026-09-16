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
 *  * [GoAudioFrames] — audio frames ACCEPTED into the queue (never dropped);
 *  * [GoVideoFrames] — video NALs ACCEPTED into the queue;
 *  * [GoVideoDropped] — video NALs evicted by drop-oldest because the video
 *    queue was full;
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
