package com.glassstorm.phonemanager.adapter.jvm.ai

import com.glassstorm.phonemanager.core.model.AudioFormat
import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.glassstorm.phonemanager.core.model.GestureMeaningTable
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Contract tests for the deterministic JVM fakes.
 *
 * The headline proof is DETERMINISM: the same input must yield the same output on
 * any instance, forever. There is no clock, no randomness and no network in these
 * fakes, so a non-equal result is a defect, not a flake.
 */
class AiFakesTest {
    /** Deterministic, non-trivial byte payload (no RNG). */
    private fun bytes(
        length: Int,
        shift: Int = 0,
    ): ByteArray = ByteArray(length) { ((it + shift) % 251).toByte() }

    /** A valid 8 s PCM16 clip at 16 kHz. */
    private fun enrollmentClip(): ByteArray = bytes(AudioFormat.ENROLLMENT_MIN_BYTES)

    @Test
    fun `an empty image yields no gesture`() =
        runBlocking {
            assertThat(FakeGesturePort().recognize(ByteArray(0))).isNull()
        }

    @Test
    fun `the same image yields the same gesture across instances`() =
        runBlocking {
            val image = bytes(1024)

            val first = FakeGesturePort().recognize(image)
            val second = FakeGesturePort().recognize(image)

            assertThat(first).isNotNull()
            assertThat(second).isEqualTo(first)
            assertThat(first!!.text).isNotEmpty()
            assertThat(first.confidence).isAtLeast(0f)
            assertThat(first.confidence).isAtMost(1f)
        }

    @Test
    fun `every canned label maps to a non-empty meaning`() {
        val table = GestureMeaningTable.standard

        GestureMeaningTable.cannedLabels.forEach { label ->
            assertThat(table.meaningFor(label)).isNotEmpty()
        }
    }

    @Test
    fun `an unmapped label yields a null meaning`() {
        assertThat(GestureMeaningTable.standard.meaningFor("No_Such_Label")).isNull()
    }

    @Test
    fun `the same audio yields the same transcript across instances`() =
        runBlocking {
            val audio = bytes(640)

            val first = FakeSpeechPort().transcribe("s1", audio)
            val second = FakeSpeechPort().transcribe("s2", audio)

            assertThat(first).isNotNull()
            assertThat(second).isEqualTo(first)
            assertThat(first!!.text).startsWith("mock:640:")
        }

    @Test
    fun `a chunk after close yields no transcript`() =
        runBlocking {
            val port = FakeSpeechPort()
            port.transcribe("s1", bytes(640))
            port.close("s1")

            assertThat(port.transcribe("s1", bytes(640))).isNull()
        }

    @Test
    fun `tts output is 16 khz pcm16 and scales with the text`() =
        runBlocking {
            val port = FakeTtsPort()

            val audio = port.synthesize("hello")

            assertThat(audio.size % AudioFormat.BYTES_PER_SAMPLE).isEqualTo(0)
            assertThat(audio.size).isEqualTo("hello".length * AudioFormat.CHUNK_BYTES)
        }

    @Test
    fun `empty text synthesizes an empty array`() =
        runBlocking {
            assertThat(FakeTtsPort().synthesize("")).isEmpty()
        }

    @Test
    fun `enroll rejects an invalid name and a too-short clip`() =
        runBlocking {
            val store = FakeEnrollmentStore()

            assertThat(store.enroll("A B", enrollmentClip()).reason)
                .isEqualTo(EnrollOutcome.REASON_INVALID_NAME)
            assertThat(store.enroll("", enrollmentClip()).reason)
                .isEqualTo(EnrollOutcome.REASON_INVALID_NAME)
            assertThat(store.enroll("Alice", bytes(1000)).reason)
                .isEqualTo(EnrollOutcome.REASON_AUDIO_TOO_SHORT)
        }

    @Test
    fun `enroll accepts a valid name and 8 s clip and is deterministic`() =
        runBlocking {
            val first = FakeEnrollmentStore()
            val second = FakeEnrollmentStore()

            assertThat(first.enroll("Alice", enrollmentClip())).isEqualTo(EnrollOutcome.accepted)
            assertThat(second.enroll("Alice", enrollmentClip())).isEqualTo(EnrollOutcome.accepted)

            assertThat(first.names()).containsExactly("Alice")
            assertThat(first.enrollments()).isEqualTo(second.enrollments())
        }

    @Test
    fun `re-enrolling a name replaces it without changing order`() =
        runBlocking {
            val store = FakeEnrollmentStore()
            store.enroll("Alice", enrollmentClip())
            store.enroll("小明", enrollmentClip())
            store.enroll("Alice", bytes(AudioFormat.ENROLLMENT_MIN_BYTES, shift = 5))

            assertThat(store.names()).containsExactly("Alice", "小明").inOrder()
        }
}
