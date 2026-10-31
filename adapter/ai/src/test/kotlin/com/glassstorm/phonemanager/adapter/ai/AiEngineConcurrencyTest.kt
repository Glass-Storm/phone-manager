package com.glassstorm.phonemanager.adapter.ai

import com.glassstorm.phonemanager.core.model.AudioFormat
import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.glassstorm.phonemanager.core.model.GestureOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency proof for the shared native engines: every port serialises access to
 * its single handle, so two session pumps can never enter MediaPipe/sherpa at once.
 */
class AiEngineConcurrencyTest {
    @Test
    fun `two concurrent gesture calls never overlap on the recognizer`() =
        runBlocking {
            val engine = OverlapDetectingGestureEngine()
            val port = AiGesturePort(engine)

            coroutineScope {
                val first = async(Dispatchers.Default) { port.recognize(JPEG) }
                val second = async(Dispatchers.Default) { port.recognize(JPEG) }
                awaitAll(first, second)
            }

            assertThat(engine.maxConcurrent.get()).isEqualTo(1)
            assertThat(engine.calls.get()).isEqualTo(2)
        }

    @Test
    fun `two concurrent enrollments never overlap on the embedder`() =
        runBlocking {
            val embedder = OverlapDetectingEmbedder()
            val store = AiEnrollmentStore(File.createTempFile("ai-concurrency-", ".txt").also { it.deleteOnExit() }, embedder)

            coroutineScope {
                val first = async(Dispatchers.Default) { store.enroll("Alice", clip()) }
                val second = async(Dispatchers.Default) { store.enroll("Bob", clip()) }
                val outcomes = awaitAll(first, second)
                assertThat(outcomes).containsExactly(EnrollOutcome.accepted, EnrollOutcome.accepted)
            }

            assertThat(embedder.maxConcurrent.get()).isEqualTo(1)
            assertThat(embedder.calls.get()).isEqualTo(2)
        }

    private fun clip(): ByteArray = ByteArray(AudioFormat.ENROLLMENT_MIN_BYTES) { (it % 251).toByte() }

    private class OverlapDetectingGestureEngine : GestureEngine {
        val calls = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        private val active = AtomicInteger()

        override fun recognize(jpeg: ByteArray): GestureOutcome {
            calls.incrementAndGet()
            track()
            try {
                Thread.sleep(HOLD_MILLIS)
                return GestureOutcome(label = "Thumb_Up", text = "Yes", confidence = 0.9f)
            } finally {
                active.decrementAndGet()
            }
        }

        private fun track() {
            val now = active.incrementAndGet()
            maxConcurrent.updateAndGet { previous -> maxOf(previous, now) }
        }

        override fun close() = Unit
    }

    private class OverlapDetectingEmbedder : VoiceprintEmbedder {
        val calls = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        private val active = AtomicInteger()

        override suspend fun embeddingFor(pcm16: ByteArray): FloatArray {
            calls.incrementAndGet()
            val now = active.incrementAndGet()
            maxConcurrent.updateAndGet { previous -> maxOf(previous, now) }
            try {
                Thread.sleep(HOLD_MILLIS)
                return floatArrayOf(pcm16.size.toFloat())
            } finally {
                active.decrementAndGet()
            }
        }

        override fun close() = Unit
    }

    private companion object {
        val JPEG = byteArrayOf(1, 2, 3)

        const val HOLD_MILLIS = 100L
    }
}
