package com.glassstorm.phonemanager.adapter.ai

import com.glassstorm.phonemanager.core.model.AudioFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Contract tests for the pure [UtteranceSegmenter].
 *
 * The relay feeds 20 ms chunks, so these tests drive exactly that cadence on the host
 * JVM: no engine, no native, no Android. They pin the VAD boundaries the real adapter
 * relies on — silence-only streams never close, speech closes after the configured
 * silence, a runaway speech run closes at the max length, and sessions clear on close.
 */
class UtteranceSegmenterTest {
    private val segmenter = UtteranceSegmenter()

    private val closed = mutableListOf<ClosedUtterance>()

    private fun accept(chunk: ByteArray) {
        segmenter.accept(SESSION_ID, chunk)?.let(closed::add)
    }

    private fun speech(amplitude: Float = 0.2f): ByteArray =
        AudioPcm.toPcm16Le(
            FloatArray(AudioFormat.CHUNK_SAMPLES) { index ->
                if (index % 2 == 0) amplitude else -amplitude
            },
        )

    private fun silence(): ByteArray = ByteArray(AudioFormat.CHUNK_BYTES)

    private val silenceChunksToClose: Int =
        (UtteranceSegmenter.DEFAULT_SILENCE_MILLIS / CHUNK_MILLIS).toInt()

    @Test
    fun `silence only chunks never close an utterance`() {
        repeat(SILENCE_ONLY_CHUNKS) {
            assertThat(segmenter.accept(SESSION_ID, silence())).isNull()
        }

        assertThat(closed).isEmpty()
    }

    @Test
    fun `speech followed by silence closes exactly one utterance containing the speech`() {
        val speechChunk = speech()

        accept(speechChunk)
        repeat(silenceChunksToClose) { accept(silence()) }

        assertThat(closed).hasSize(1)
        val utterance = closed.single()
        assertThat(utterance.pcm16.copyOfRange(0, speechChunk.size)).isEqualTo(speechChunk)
        assertThat(utterance.startMs).isEqualTo(0L)
    }

    @Test
    fun `silence longer than the utterance cap never closes an utterance`() {
        repeat(LONG_SILENCE_CHUNKS) {
            assertThat(segmenter.accept(SESSION_ID, silence())).isNull()
        }

        assertThat(closed).isEmpty()
    }

    @Test
    fun `a long silent lead in does not count toward the max utterance cap`() {
        // Given an 11 s silent lead-in: more than the 10 s max-utterance cap
        repeat(LONG_SILENCE_CHUNKS) { accept(silence()) }
        assertThat(closed).isEmpty()

        // When speech starts, the buffered silence must not force a premature close
        repeat(SPEECH_BURST_CHUNKS) { accept(speech()) }
        assertThat(closed).isEmpty()

        // And the utterance closes normally after the configured trailing silence
        repeat(silenceChunksToClose) { accept(silence()) }
        assertThat(closed).hasSize(1)

        val utterance = closed.single()
        assertThat(utterance.startMs).isEqualTo(LONG_SILENCE_CHUNKS * CHUNK_MILLIS)
        // Only the bounded pre-roll plus the speech and its trailing silence is kept,
        // never the 11 s lead-in.
        val preRollBytes =
            (AudioFormat.SAMPLE_RATE_HZ * UtteranceSegmenter.DEFAULT_PRE_ROLL_MILLIS / 1000)
                .toInt() * AudioFormat.BYTES_PER_SAMPLE
        val speechAndSilenceBytes = (SPEECH_BURST_CHUNKS + silenceChunksToClose) * AudioFormat.CHUNK_BYTES
        assertThat(utterance.pcm16.size).isAtMost(preRollBytes + speechAndSilenceBytes)
    }

    @Test
    fun `a very long speech run closes at the maximum utterance length`() {
        repeat(LONG_SPEECH_CHUNKS) { accept(speech()) }

        assertThat(closed).hasSize(1)
        val maxBytes =
            (AudioFormat.SAMPLE_RATE_HZ * UtteranceSegmenter.DEFAULT_MAX_UTTERANCE_MILLIS / 1000)
                .toInt() * AudioFormat.BYTES_PER_SAMPLE
        assertThat(closed.single().pcm16.size).isEqualTo(maxBytes)
    }

    @Test
    fun `two separated speech bursts produce two utterances`() {
        repeat(SPEECH_BURST_CHUNKS) { accept(speech()) }
        repeat(silenceChunksToClose) { accept(silence()) }
        repeat(SPEECH_BURST_CHUNKS) { accept(speech(amplitude = 0.4f)) }
        repeat(silenceChunksToClose) { accept(silence()) }

        assertThat(closed).hasSize(2)
        val secondBurstStartMs = (SPEECH_BURST_CHUNKS + silenceChunksToClose) * CHUNK_MILLIS
        assertThat(closed[1].startMs).isEqualTo(secondBurstStartMs)
    }

    @Test
    fun `closing a session clears its buffered utterance`() {
        repeat(SPEECH_BURST_CHUNKS) { accept(speech()) }
        repeat(silenceChunksToClose / 2) { accept(silence()) }
        assertThat(closed).isEmpty()

        segmenter.close(SESSION_ID)

        repeat(silenceChunksToClose) { accept(silence()) }
        assertThat(closed).isEmpty()

        repeat(SPEECH_BURST_CHUNKS) { accept(speech()) }
        repeat(silenceChunksToClose) { accept(silence()) }

        assertThat(closed).hasSize(1)
        // The cleared session restarted on the silence, so speech begins after it.
        assertThat(closed.single().startMs).isEqualTo(silenceChunksToClose * CHUNK_MILLIS)
    }

    @Test
    fun `flushing closes the buffered utterance immediately`() {
        val speechChunk = speech()
        repeat(SPEECH_BURST_CHUNKS) { accept(speechChunk) }
        assertThat(closed).isEmpty()

        val flushed = segmenter.flush(SESSION_ID)

        assertThat(flushed).isNotNull()
        assertThat(flushed!!.pcm16.copyOfRange(0, speechChunk.size)).isEqualTo(speechChunk)
        assertThat(segmenter.flush(SESSION_ID)).isNull()
    }

    @Test
    fun `flushing a session with no speech returns null`() {
        repeat(SILENCE_ONLY_CHUNKS) { accept(silence()) }

        assertThat(segmenter.flush(SESSION_ID)).isNull()
    }

    private companion object {
        const val SESSION_ID = "session"

        const val CHUNK_MILLIS = 20L

        const val SILENCE_ONLY_CHUNKS = 100

        const val SPEECH_BURST_CHUNKS = 5

        val LONG_SILENCE_CHUNKS = (UtteranceSegmenter.DEFAULT_MAX_UTTERANCE_MILLIS / CHUNK_MILLIS).toInt() + 50

        val LONG_SPEECH_CHUNKS =
            (UtteranceSegmenter.DEFAULT_MAX_UTTERANCE_MILLIS / CHUNK_MILLIS).toInt()
    }
}
