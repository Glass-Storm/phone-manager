package com.glassstorm.phonemanager.ui

/**
 * Everything the Stream screen renders, derived only from the `StreamService`
 * domain port.
 *
 * A `false` [available] means the port was not registered in the Context, which
 * the screen renders as an explicit "not available" line instead of crashing.
 *
 * The counters are a snapshot of the ACTIVE session: [audioFrames],
 * [videoFrames], [videoDropped] and [transcripts] mirror the relay's own
 * accounting while a session is live and reset to zero once it stops, so the idle
 * screen never shows the cumulative totals of sessions that have already ended.
 */
data class StreamUiState(
    val available: Boolean = false,
    val sessionId: String? = null,
    val peerId: String? = null,
    val audioFrames: Long = 0L,
    val videoFrames: Long = 0L,
    val videoDropped: Long = 0L,
    val transcripts: Long = 0L,
    val liveSessions: Int = 0,
    val latestTranscript: String? = null,
    val latestSpeakerLabel: String? = null,
)
