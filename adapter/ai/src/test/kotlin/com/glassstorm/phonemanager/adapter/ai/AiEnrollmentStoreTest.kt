package com.glassstorm.phonemanager.adapter.ai

import com.glassstorm.phonemanager.core.model.AudioFormat
import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Tests for the host-JVM behaviour of the device [AiEnrollmentStore].
 *
 * The real backend is arm64-only sherpa native, so these drive the store through
 * an injected [VoiceprintEmbedder]: the store's own name/audio policy, its
 * persistence, and — the headline — its ORDER guarantee are exercised without
 * loading any weights. The device extractor wiring is the untested seam (it only
 * delegates to ai-android), exactly mirroring the port's own `forTesting` split.
 */
class AiEnrollmentStoreTest {
    /** Deterministic embedder: a constant embedding, no native, no model. */
    private class FakeEmbedder : VoiceprintEmbedder {
        var closeCalls: Int = 0
            private set

        override suspend fun embeddingFor(pcm16: ByteArray): FloatArray = floatArrayOf(pcm16.size.toFloat())

        override fun close() {
            closeCalls += 1
        }
    }

    /** A valid 8 s PCM16 clip at 16 kHz. */
    private fun clip(shift: Int = 0): ByteArray = ByteArray(AudioFormat.ENROLLMENT_MIN_BYTES) { ((it + shift) % 251).toByte() }

    private fun tempStorage(): File = File.createTempFile("ai-enroll-", ".txt").also { it.deleteOnExit() }

    @Test
    fun `re-enrolling a name preserves insertion order`() =
        runBlocking {
            val store = AiEnrollmentStore(tempStorage(), FakeEmbedder())
            store.enroll("Alice", clip())
            store.enroll("小明", clip(shift = 3))

            store.enroll("Alice", clip(shift = 9))

            assertThat(store.names()).containsExactly("Alice", "小明").inOrder()
            assertThat(store.enrollments()).hasSize(2)
        }

    @Test
    fun `the persisted file is rewritten in the same order after a re-enroll`() =
        runBlocking {
            val storage = tempStorage()
            val store = AiEnrollmentStore(storage, FakeEmbedder())
            store.enroll("Alice", clip())
            store.enroll("小明", clip(shift = 3))
            store.enroll("Alice", clip(shift = 9))

            // A fresh store reads the file back and must keep the same order.
            val reopened = AiEnrollmentStore(storage, FakeEmbedder())

            assertThat(reopened.names()).containsExactly("Alice", "小明").inOrder()
        }

    @Test
    fun `an invalid name and a short clip are rejected before any embedding`() =
        runBlocking {
            val store = AiEnrollmentStore(tempStorage(), FakeEmbedder())

            assertThat(store.enroll("A B", clip()).reason).isEqualTo(EnrollOutcome.REASON_INVALID_NAME)
            assertThat(store.enroll("Alice", ByteArray(1000)).reason).isEqualTo(EnrollOutcome.REASON_AUDIO_TOO_SHORT)
            assertThat(store.names()).isEmpty()
        }

    @Test
    fun `close releases the embedder`() {
        val embedder = FakeEmbedder()
        val store = AiEnrollmentStore(tempStorage(), embedder)

        store.close()
        store.close()

        assertThat(embedder.closeCalls).isEqualTo(2)
    }
}
