package com.glassstorm.phonemanager.ui

import com.glassstorm.phonemanager.core.model.LivePeer

/**
 * Everything the 即時 (Live) screen renders, derived only from the `StreamService`
 * and `HubServer` domain ports.
 *
 * ## Tri-state availability
 *
 * [available] is `null` until the FIRST `stats()` read resolves. That is distinct
 * from a resolved failure ([available] = `false`), which is the only state that
 * renders `無法使用`: the page must not flash a red error while it is still
 * reading, so `null` renders a quiet `檢查中` placeholder instead.
 *
 * ## Live results belong to the LIVE hub
 *
 * [latestTranscript], [latestSpeakerLabel] and [latestGesture] describe the
 * CURRENT live session, not the last one ever seen. The ViewModel clears them
 * whenever the hub is not running or [livePeers] becomes empty, and the
 * `latest*At` fields carry the wall-clock instant the result was RECEIVED so the
 * card can show how stale it is. (The wire `ptsMs` is producer-relative and is
 * not a wall clock; the received-at instant is the honest display value.)
 *
 * The counters are the relay's own CUMULATIVE totals: this page observes the
 * always-on relay rather than driving a session of its own, so it never resets
 * them between sessions. [imageFrames] and [gestures] are the JPEG-still /
 * gesture-engine channel; [videoFrames] is the offered H.264 NAL count and is
 * shown only as an honestly labelled diagnostic.
 */
data class StreamUiState(
    val available: Boolean? = null,
    val hubRunning: Boolean = false,
    val livePeers: List<LivePeer> = emptyList(),
    val audioFrames: Long = 0L,
    val videoFrames: Long = 0L,
    val imageFrames: Long = 0L,
    val gestures: Long = 0L,
    val transcripts: Long = 0L,
    val latestTranscript: String? = null,
    val latestSpeakerLabel: String? = null,
    val latestTranscriptAt: Long? = null,
    val latestGesture: String? = null,
    val latestGestureAt: Long? = null,
)
