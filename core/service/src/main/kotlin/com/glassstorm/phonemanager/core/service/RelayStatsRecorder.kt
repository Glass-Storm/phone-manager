package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.model.RelayStats
import java.util.concurrent.atomic.AtomicLong

/**
 * Relay accounting counters.
 *
 * Owns the monotonic totals a [com.glassstorm.phonemanager.core.domain.service.StreamService]
 * reports. The counters are [AtomicLong] because the relay pumps run on the
 * service scope, not the caller's thread. [snapshot] folds in the caller-supplied
 * live-session count so this collaborator needs no knowledge of the session
 * registry.
 *
 * Image traffic is counted in TWO places because the two numbers answer different
 * questions: [onImageOffered] counts every JPEG still the peer pushed, while
 * [onGesture] counts only the stills that the gesture engine turned into a result.
 * The 即時 page shows both, so an offered-but-unrecognized still is not mistaken
 * for a gesture.
 */
internal class RelayStatsRecorder {
    private val audioFrames = AtomicLong()
    private val videoFrames = AtomicLong()
    private val videoDropped = AtomicLong()
    private val imageFrames = AtomicLong()
    private val imageDropped = AtomicLong()
    private val gestures = AtomicLong()
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

    /** Count one image still OFFERED, and — when [dropped] — one latest-wins eviction. */
    fun onImageOffered(dropped: Boolean) {
        imageFrames.incrementAndGet()
        if (dropped) imageDropped.incrementAndGet()
    }

    /** Count one gesture result PRODUCED from a recognized still. */
    fun onGesture() {
        gestures.incrementAndGet()
    }

    /** Point-in-time snapshot carrying [liveSessions] from the session registry. */
    fun snapshot(liveSessions: Int): RelayStats =
        RelayStats(
            audioFrames = audioFrames.get(),
            videoFrames = videoFrames.get(),
            videoDropped = videoDropped.get(),
            transcripts = transcripts.get(),
            liveSessions = liveSessions,
            imageDropped = imageDropped.get(),
            imageFrames = imageFrames.get(),
            gestures = gestures.get(),
        )
}
