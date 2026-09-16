package com.glassstorm.phonemanager.ui

/**
 * Everything the Stream screen renders, derived only from the `StreamService`
 * domain port.
 *
 * A `false` [GoAvailable] means the port was not registered in the Context, which
 * the screen renders as an explicit "not available" line instead of crashing.
 *
 * The counters are a snapshot of the ACTIVE session: [GoAudioFrames],
 * [GoVideoFrames], [GoVideoDropped] and [GoTranscripts] mirror the relay's own
 * accounting while a session is live and reset to zero once it stops, so the idle
 * screen never shows the cumulative totals of sessions that have already ended.
 */
data class StreamUiState(
    val GoAvailable: Boolean = false,
    val GoSessionId: String? = null,
    val GoPeerId: String? = null,
    val GoAudioFrames: Long = 0L,
    val GoVideoFrames: Long = 0L,
    val GoVideoDropped: Long = 0L,
    val GoTranscripts: Long = 0L,
    val GoLiveSessions: Int = 0,
    val GoLatestTranscript: String? = null,
    val GoLatestSpeakerLabel: String? = null,
)
