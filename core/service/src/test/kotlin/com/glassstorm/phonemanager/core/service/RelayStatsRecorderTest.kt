package com.glassstorm.phonemanager.core.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Direct proof for [RelayStatsRecorder]: each counter and the folded snapshot. */
class RelayStatsRecorderTest {
    @Test
    fun `audio frames are counted`() {
        // Given a fresh recorder
        val stats = RelayStatsRecorder()

        // When two audio frames arrive
        stats.onAudioFrame()
        stats.onAudioFrame()

        // Then the snapshot reports exactly two
        assertThat(stats.snapshot(liveSessions = 0).audioFrames).isEqualTo(2L)
    }

    @Test
    fun `transcripts are counted`() {
        // Given a fresh recorder
        val stats = RelayStatsRecorder()

        // When three transcripts are emitted
        repeat(3) { stats.onTranscript() }

        // Then the snapshot reports exactly three
        assertThat(stats.snapshot(liveSessions = 0).transcripts).isEqualTo(3L)
    }

    @Test
    fun `an offered video frame with drop increments frames and dropped`() {
        // Given a fresh recorder
        val stats = RelayStatsRecorder()

        // When one NAL is offered and evicted by drop-oldest
        stats.onVideoOffered(dropped = true)

        // Then both the offered and dropped totals advanced
        val snapshot = stats.snapshot(liveSessions = 0)
        assertThat(snapshot.videoFrames).isEqualTo(1L)
        assertThat(snapshot.videoDropped).isEqualTo(1L)
    }

    @Test
    fun `an offered video frame without drop increments only frames`() {
        // Given a fresh recorder
        val stats = RelayStatsRecorder()

        // When one NAL is offered and admitted without eviction
        stats.onVideoOffered(dropped = false)

        // Then only the offered total advanced
        val snapshot = stats.snapshot(liveSessions = 0)
        assertThat(snapshot.videoFrames).isEqualTo(1L)
        assertThat(snapshot.videoDropped).isEqualTo(0L)
    }

    @Test
    fun `offered stills are counted separately from their evictions`() {
        // Given a fresh recorder
        val stats = RelayStatsRecorder()

        // When two stills are offered and the second is evicted
        stats.onImageOffered(dropped = false)
        stats.onImageOffered(dropped = true)

        // Then the offered total counts both, while only one was dropped
        val snapshot = stats.snapshot(liveSessions = 0)
        assertThat(snapshot.imageFrames).isEqualTo(2L)
        assertThat(snapshot.imageDropped).isEqualTo(1L)
    }

    @Test
    fun `gestures are counted only when recognition produces a result`() {
        // Given a fresh recorder
        val stats = RelayStatsRecorder()

        // When two stills are offered but only one is recognized
        stats.onImageOffered(dropped = false)
        stats.onImageOffered(dropped = false)
        stats.onGesture()

        // Then the still and gesture totals answer different questions
        val snapshot = stats.snapshot(liveSessions = 0)
        assertThat(snapshot.imageFrames).isEqualTo(2L)
        assertThat(snapshot.gestures).isEqualTo(1L)
    }

    @Test
    fun `the snapshot carries the live session count`() {
        // Given a recorder with some counts
        val stats = RelayStatsRecorder()
        stats.onAudioFrame()
        stats.onVideoOffered(dropped = false)
        stats.onImageOffered(dropped = false)
        stats.onGesture()
        stats.onTranscript()

        // When the snapshot is taken with three live sessions
        val snapshot = stats.snapshot(liveSessions = 3)

        // Then every field reflects the accumulated state
        assertThat(snapshot.audioFrames).isEqualTo(1L)
        assertThat(snapshot.videoFrames).isEqualTo(1L)
        assertThat(snapshot.videoDropped).isEqualTo(0L)
        assertThat(snapshot.imageFrames).isEqualTo(1L)
        assertThat(snapshot.imageDropped).isEqualTo(0L)
        assertThat(snapshot.gestures).isEqualTo(1L)
        assertThat(snapshot.transcripts).isEqualTo(1L)
        assertThat(snapshot.liveSessions).isEqualTo(3)
    }
}
