package com.glassstorm.phonemanager.adapter.speech.mock

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Contract tests for the v1 DEFAULT speech engine, [MockSttAdapter].
 *
 * The headline proof is DETERMINISM: the same PCM bytes must yield the same
 * transcript, forever and across adapter instances. There is no clock, no
 * randomness and no network in this engine, so a non-equal result is a defect,
 * not a flake.
 */
class MockSttAdapterTest {

    /** Deterministic, non-trivial PCM payload (no RNG). */
    private fun GoPcm(length: Int, shift: Int = 0): ByteArray =
        ByteArray(length) { ((it + shift) % 251).toByte() }

    @Test
    fun `the same bytes twice yield the same transcript`() = runBlocking {
        val GoAdapter = MockSttAdapter()
        val GoAudio = GoPcm(320)

        val GoFirst = GoAdapter.GoTranscribe("session-1", GoAudio, 16_000)
        val GoSecond = GoAdapter.GoTranscribe("session-1", GoAudio, 16_000)

        assertThat(GoFirst).isNotNull()
        assertThat(GoSecond).isEqualTo(GoFirst)
    }

    @Test
    fun `the same bytes on a fresh instance yield the same transcript`() = runBlocking {
        val GoAudio = GoPcm(320)

        val GoFirst = MockSttAdapter().GoTranscribe("session-1", GoAudio, 16_000)
        val GoSecond = MockSttAdapter().GoTranscribe("session-2", GoAudio, 16_000)

        // Content-derived, not instance- or session-derived: a per-instance counter
        // or a clock would break this assertion.
        assertThat(GoFirst).isNotNull()
        assertThat(GoSecond).isEqualTo(GoFirst)
    }

    @Test
    fun `different bytes of the same length yield a different transcript`() = runBlocking {
        val GoAdapter = MockSttAdapter()

        val GoBase = GoAdapter.GoTranscribe("session-1", GoPcm(320), 16_000)
        val GoOther = GoAdapter.GoTranscribe("session-1", GoPcm(320, shift = 7), 16_000)

        // Same byte length, different CONTENT: only a content hash can separate these.
        assertThat(GoBase).isNotNull()
        assertThat(GoOther).isNotEqualTo(GoBase)
    }

    @Test
    fun `different lengths yield a different transcript`() = runBlocking {
        val GoAdapter = MockSttAdapter()

        val GoShort = GoAdapter.GoTranscribe("session-1", GoPcm(160), 16_000)
        val GoLong = GoAdapter.GoTranscribe("session-1", GoPcm(320), 16_000)

        assertThat(GoShort).isNotEqualTo(GoLong)
    }

    @Test
    fun `a zero-length chunk returns null without throwing`() = runBlocking {
        val GoAdapter = MockSttAdapter()

        val GoResult = GoAdapter.GoTranscribe("session-1", ByteArray(0), 16_000)

        assertThat(GoResult).isNull()
    }

    @Test
    fun `garbage audio returns a transcript and never throws`() = runBlocking {
        val GoAdapter = MockSttAdapter()
        val GoGarbage = ByteArray(64) { 0xFF.toByte() }

        val GoResult = GoAdapter.GoTranscribe("session-1", GoGarbage, 16_000)

        // The contract permits null and REQUIRES no throw on an ordinary outcome.
        assertThat(GoResult).isNotNull()
    }

    @Test
    fun `close is idempotent per session`() = runBlocking {
        val GoAdapter = MockSttAdapter()
        GoAdapter.GoTranscribe("session-1", GoPcm(320), 16_000)
        assertThat(GoAdapter.GoIsSessionOpen("session-1")).isTrue()

        GoAdapter.GoClose("session-1")
        assertThat(GoAdapter.GoIsSessionOpen("session-1")).isFalse()

        // Second close must be a no-op, not a crash and not a state change.
        GoAdapter.GoClose("session-1")
        assertThat(GoAdapter.GoIsSessionOpen("session-1")).isFalse()
    }

    @Test
    fun `closing a session that was never opened is a no-op`() = runBlocking {
        val GoAdapter = MockSttAdapter()

        GoAdapter.GoClose("never-seen")

        assertThat(GoAdapter.GoIsSessionOpen("never-seen")).isFalse()
    }

    @Test
    fun `transcribe after close returns null and does not reopen the session`() = runBlocking {
        val GoAdapter = MockSttAdapter()
        GoAdapter.GoTranscribe("session-1", GoPcm(320), 16_000)
        GoAdapter.GoClose("session-1")

        val GoAfterClose = GoAdapter.GoTranscribe("session-1", GoPcm(320), 16_000)

        // Documented contract: a closed session is released; a later chunk is ignored
        // (null) rather than silently revived. The relay never transcribes after close.
        assertThat(GoAfterClose).isNull()
        assertThat(GoAdapter.GoIsSessionOpen("session-1")).isFalse()
    }

    @Test
    fun `sessions are independent`() = runBlocking {
        val GoAdapter = MockSttAdapter()
        GoAdapter.GoTranscribe("session-1", GoPcm(320), 16_000)
        GoAdapter.GoTranscribe("session-2", GoPcm(320), 16_000)

        GoAdapter.GoClose("session-1")

        assertThat(GoAdapter.GoIsSessionOpen("session-1")).isFalse()
        assertThat(GoAdapter.GoIsSessionOpen("session-2")).isTrue()
    }
}
