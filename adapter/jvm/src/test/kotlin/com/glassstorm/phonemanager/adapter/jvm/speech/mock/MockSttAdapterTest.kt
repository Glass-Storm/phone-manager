package com.glassstorm.phonemanager.adapter.jvm.speech.mock

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
    private fun pcm(
        length: Int,
        shift: Int = 0,
    ): ByteArray = ByteArray(length) { ((it + shift) % 251).toByte() }

    @Test
    fun `the same bytes twice yield the same transcript`() =
        runBlocking {
            val adapter = MockSttAdapter()
            val audio = pcm(320)

            val first = adapter.transcribe("session-1", audio, 16_000)
            val second = adapter.transcribe("session-1", audio, 16_000)

            assertThat(first).isNotNull()
            assertThat(second).isEqualTo(first)
        }

    @Test
    fun `the same bytes on a fresh instance yield the same transcript`() =
        runBlocking {
            val audio = pcm(320)

            val first = MockSttAdapter().transcribe("session-1", audio, 16_000)
            val second = MockSttAdapter().transcribe("session-2", audio, 16_000)

            // Content-derived, not instance- or session-derived: a per-instance counter
            // or a clock would break this assertion.
            assertThat(first).isNotNull()
            assertThat(second).isEqualTo(first)
        }

    @Test
    fun `different bytes of the same length yield a different transcript`() =
        runBlocking {
            val adapter = MockSttAdapter()

            val base = adapter.transcribe("session-1", pcm(320), 16_000)
            val other = adapter.transcribe("session-1", pcm(320, shift = 7), 16_000)

            // Same byte length, different CONTENT: only a content hash can separate these.
            assertThat(base).isNotNull()
            assertThat(other).isNotEqualTo(base)
        }

    @Test
    fun `different lengths yield a different transcript`() =
        runBlocking {
            val adapter = MockSttAdapter()

            val short = adapter.transcribe("session-1", pcm(160), 16_000)
            val long = adapter.transcribe("session-1", pcm(320), 16_000)

            assertThat(short).isNotEqualTo(long)
        }

    @Test
    fun `a zero-length chunk returns null without throwing`() =
        runBlocking {
            val adapter = MockSttAdapter()

            val result = adapter.transcribe("session-1", ByteArray(0), 16_000)

            assertThat(result).isNull()
        }

    @Test
    fun `garbage audio returns a transcript and never throws`() =
        runBlocking {
            val adapter = MockSttAdapter()
            val garbage = ByteArray(64) { 0xFF.toByte() }

            val result = adapter.transcribe("session-1", garbage, 16_000)

            // The contract permits null and REQUIRES no throw on an ordinary outcome.
            assertThat(result).isNotNull()
        }

    @Test
    fun `close is idempotent per session`() =
        runBlocking {
            val adapter = MockSttAdapter()
            adapter.transcribe("session-1", pcm(320), 16_000)
            assertThat(adapter.isSessionOpen("session-1")).isTrue()

            adapter.close("session-1")
            assertThat(adapter.isSessionOpen("session-1")).isFalse()

            // Second close must be a no-op, not a crash and not a state change.
            adapter.close("session-1")
            assertThat(adapter.isSessionOpen("session-1")).isFalse()
        }

    @Test
    fun `closing a session that was never opened is a no-op`() =
        runBlocking {
            val adapter = MockSttAdapter()

            adapter.close("never-seen")

            assertThat(adapter.isSessionOpen("never-seen")).isFalse()
        }

    @Test
    fun `transcribe after close returns null and does not reopen the session`() =
        runBlocking {
            val adapter = MockSttAdapter()
            adapter.transcribe("session-1", pcm(320), 16_000)
            adapter.close("session-1")

            val afterClose = adapter.transcribe("session-1", pcm(320), 16_000)

            // Documented contract: a closed session is released; a later chunk is ignored
            // (null) rather than silently revived. The relay never transcribes after close.
            assertThat(afterClose).isNull()
            assertThat(adapter.isSessionOpen("session-1")).isFalse()
        }

    @Test
    fun `sessions are independent`() =
        runBlocking {
            val adapter = MockSttAdapter()
            adapter.transcribe("session-1", pcm(320), 16_000)
            adapter.transcribe("session-2", pcm(320), 16_000)

            adapter.close("session-1")

            assertThat(adapter.isSessionOpen("session-1")).isFalse()
            assertThat(adapter.isSessionOpen("session-2")).isTrue()
        }
}
