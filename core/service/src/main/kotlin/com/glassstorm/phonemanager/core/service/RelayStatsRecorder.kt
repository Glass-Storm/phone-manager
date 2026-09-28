package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.model.RelayStats
import java.util.concurrent.atomic.AtomicLong

/**
 * Relay accounting counters.
 *
 * Owns the four monotonic totals a [StreamService] reports. The counters are
 * [AtomicLong] because the relay pumps run on the service scope, not the caller's
 * thread. [snapshot] folds in the caller-supplied live-session count so this
 * collaborator needs no knowledge of the session registry.
 */
internal class RelayStatsRecorder {
    private val audioFrames = AtomicLong()
    private val videoFrames = AtomicLong()
    private val videoDropped = AtomicLong()
    private val transcripts = AtomicLong()

    /** Count one audio frame ACCEPTED into the audio queue. */
    fun onAudioFrame() {
        audioFrames.incrementAndGet()
    }

    /** Count one recognized utterance. */
    fun onTranscript() {
        transcripts.incrementAndGet()
    }

    /**
     * Count one video NAL OFFERED to a live session, and — when [dropped] — one
     * eviction performed by the drop-oldest policy.
     */
    fun onVideoOffered(dropped: Boolean) {
        videoFrames.incrementAndGet()
        if (dropped) videoDropped.incrementAndGet()
    }

    /** Point-in-time snapshot carrying [liveSessions] from the session registry. */
    fun snapshot(liveSessions: Int): RelayStats =
        RelayStats(
            audioFrames = audioFrames.get(),
            videoFrames = videoFrames.get(),
            videoDropped = videoDropped.get(),
            transcripts = transcripts.get(),
            liveSessions = liveSessions,
        )
}
