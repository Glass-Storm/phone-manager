package com.glassstorm.phonemanager.adapter.ai

import com.glassstorm.phonemanager.core.domain.adapter.enrollment.EnrollmentStore
import com.glassstorm.phonemanager.core.model.AudioFormat
import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.glassstorm.phonemanager.core.model.EnrolledSpeaker
import com.glassstorm.phonemanager.core.model.SpeechUtterance
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import hk.edu.hkmu.core.engine.Voiceprint as AiVoiceprint

/**
 * Host-JVM tests for [AiSpeechPort]'s segmentation/engine seams: every SenseVoice
 * segment is kept, the buffered tail is flushed on close, and the shared ASR
 * handle is never entered concurrently by two session pumps.
 */
class AiSpeechPortTest {
    @Test
    fun `every asr segment of one utterance is kept`() =
        runBlocking {
            val asr = FakeAsr(segments = listOf("first phrase", "second phrase"))
            val port = port(asr = asr)

            val utterance = feedUntilClosed(port)

            assertThat(utterance).isNotNull()
            assertThat(utterance!!.text).isEqualTo("first phrase second phrase")
        }

    @Test
    fun `flush transcribes the buffered speech that never saw trailing silence`() =
        runBlocking {
            val asr = FakeAsr(segments = listOf("unfinished phrase"))
            val port = port(asr = asr)
            repeat(SPEECH_CHUNKS) { port.transcribe(SESSION_ID, speech()) }

            val utterance = port.flush(SESSION_ID)

            assertThat(utterance).isNotNull()
            assertThat(utterance!!.text).isEqualTo("unfinished phrase")
            assertThat(asr.calls.get()).isEqualTo(1)
            assertThat(port.flush(SESSION_ID)).isNull()
        }

    @Test
    fun `concurrent session flushes never enter the asr handle together`() =
        runBlocking {
            val asr = FakeAsr(segments = listOf("one phrase"), delayMs = 100L)
            val port = port(asr = asr)
            repeat(SPEECH_CHUNKS) { port.transcribe("session-a", speech()) }
            repeat(SPEECH_CHUNKS) { port.transcribe("session-b", speech()) }

            coroutineScope {
                val first = async(Dispatchers.Default) { port.flush("session-a") }
                val second = async(Dispatchers.Default) { port.flush("session-b") }
                awaitAll(first, second)
            }

            assertThat(asr.maxConcurrent.get()).isEqualTo(1)
        }

    private suspend fun feedUntilClosed(port: AiSpeechPort): SpeechUtterance? {
        repeat(SPEECH_CHUNKS) { port.transcribe(SESSION_ID, speech()) }
        var utterance: SpeechUtterance? = null
        repeat(SILENCE_CHUNKS) { utterance = port.transcribe(SESSION_ID, silence()) ?: utterance }
        return utterance
    }

    private fun port(
        asr: AsrTranscriber,
        diarizer: SpeakerDiarizer = NoopDiarizer,
        embedder: SpeakerEmbeddingSource = NoopEmbedding,
    ): AiSpeechPort =
        AiSpeechPort(
            enrollmentStore = EmptyEnrollmentStore,
            asr = asr,
            diarizer = diarizer,
            embedder = embedder,
        )

    private fun speech(): ByteArray =
        AudioPcm.toPcm16Le(
            FloatArray(AudioFormat.CHUNK_SAMPLES) { index ->
                if (index % 2 == 0) SPEECH_AMPLITUDE else -SPEECH_AMPLITUDE
            },
        )

    private fun silence(): ByteArray = ByteArray(AudioFormat.CHUNK_BYTES)

    private class FakeAsr(
        private val segments: List<String>,
        private val delayMs: Long = 0L,
    ) : AsrTranscriber {
        val calls = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        private val active = AtomicInteger()

        override suspend fun transcribe(samples: FloatArray): List<String> {
            calls.incrementAndGet()
            val now = active.incrementAndGet()
            maxConcurrent.updateAndGet { previous -> maxOf(previous, now) }
            try {
                if (delayMs > 0L) delay(delayMs)
                return segments
            } finally {
                active.decrementAndGet()
            }
        }

        override fun close() = Unit
    }

    private object NoopDiarizer : SpeakerDiarizer {
        override suspend fun diarize(samples: FloatArray) = Unit

        override fun close() = Unit
    }

    private object NoopEmbedding : SpeakerEmbeddingSource {
        override suspend fun embedding(samples: FloatArray): AiVoiceprint? = null

        override fun close() = Unit
    }

    private object EmptyEnrollmentStore : EnrollmentStore {
        override suspend fun enroll(
            name: String,
            pcm16: ByteArray,
        ): EnrollOutcome = EnrollOutcome.accepted

        override suspend fun names(): List<String> = emptyList()

        override suspend fun enrollments(): List<EnrolledSpeaker> = emptyList()
    }

    private companion object {
        const val SESSION_ID = "session"

        const val SPEECH_CHUNKS = 5

        const val SPEECH_AMPLITUDE = 0.2f

        const val CHUNK_MILLIS = 20L

        val SILENCE_CHUNKS = (UtteranceSegmenter.DEFAULT_SILENCE_MILLIS / CHUNK_MILLIS).toInt()
    }
}
