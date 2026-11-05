package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.domain.adapter.enrollment.EnrollmentStore
import com.glassstorm.phonemanager.core.domain.adapter.gesture.GesturePort
import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.speech.SpeechPort
import com.glassstorm.phonemanager.core.domain.adapter.speech.TtsPort
import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.glassstorm.phonemanager.core.model.EnrolledSpeaker
import com.glassstorm.phonemanager.core.model.GestureOutcome
import com.glassstorm.phonemanager.core.model.PeerRole
import com.glassstorm.phonemanager.core.model.SpeechUtterance
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The gesture PROCESSING policy: the hub turns a fast JPEG still stream (the live
 * Pi pushes ~10 fps) into one model inference per still the model can keep up
 * with, always on the newest still. A one-slot latest-wins queue is the only
 * backlog control: no artificial fps cap, so a held gesture keeps being spoken
 * and a switch is heard as soon as the model recognizes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamGestureSamplingTest {
    @get:Rule
    val deadline: Timeout = Timeout.seconds(60)

    private lateinit var scope: CoroutineScope

    @Test
    fun `a ten hz still stream recognizes every still on the newest frame`() =
        runTest {
            // Given a 10 fps producer, each still carrying a distinct marker byte
            val gesture = MarkerGesturePort(testScheduler) { GestureOutcome("Thumb_Up", "好", 0.9f) }
            val stream = stream(gesture = gesture, tts = SamplingSilentTtsPort(), scheduler = testScheduler)
            val session = stream.openSession("necklace")
            stream.bindRole(session.sessionId, PeerRole.DAEMON)

            launch {
                repeat(20) { index ->
                    stream.pushImage(
                        session.sessionId,
                        jpeg = byteArrayOf((index + 1).toByte()),
                        ptsMs = index.toLong(),
                    )
                    delay(50L)
                }
            }

            // When the whole two-second stream has been offered
            advanceUntilIdle()

            // Then a fast model processed every still, in order, with no fps cap...
            assertThat(gesture.markers).hasSize(20)
            assertThat(gesture.markers).containsExactlyElementsIn((1..20).toList()).inOrder()
            // ...and the last still it saw was the NEWEST offered one, not a stale frame
            assertThat(gesture.markers.last()).isEqualTo(20)

            stream.closeSession(session.sessionId)
            advanceUntilIdle()
            scope.cancel()
        }

    @Test
    fun `a label switch is recognized and spoken within 600 ms`() =
        runTest {
            // Given a marker-driven engine and a timestamping voice
            val gesture =
                MarkerGesturePort(testScheduler) { marker ->
                    if (marker == 1) {
                        GestureOutcome("Thumb_Up", "好", 0.9f)
                    } else {
                        GestureOutcome("Fist", "停", 0.9f)
                    }
                }
            val tts = TimestampedTtsPort(testScheduler)
            val stream = stream(gesture = gesture, tts = tts, scheduler = testScheduler)
            val session = stream.openSession("necklace")
            stream.bindRole(session.sessionId, PeerRole.DAEMON)

            // When the first gesture is processed and the hand switches shortly after
            stream.pushImage(session.sessionId, jpeg = byteArrayOf(1), ptsMs = 1L)
            advanceTimeBy(100)
            val switchedAt = testScheduler.currentTime
            stream.pushImage(session.sessionId, jpeg = byteArrayOf(2), ptsMs = 2L)
            advanceTimeBy(600)

            // Then the newest still was recognized without any sample-period wait,
            // with no stale Thumb_Up replay
            assertThat(gesture.markers).containsExactly(1, 2).inOrder()
            assertThat(gesture.recognizedAtMs[1] - switchedAt).isAtMost(600L)
            // And the switch reached TTS immediately
            assertThat(tts.texts).containsExactly("好", "停").inOrder()
            assertThat(tts.spokenAtMs[1] - switchedAt).isAtMost(600L)

            stream.closeSession(session.sessionId)
            advanceUntilIdle()
            scope.cancel()
        }

    @Test
    fun `a burst of stills collapses to the newest one at capacity one`() =
        runTest {
            // Given five stills offered before the pump gets a turn
            val gesture = MarkerGesturePort(testScheduler) { GestureOutcome("Thumb_Up", "好", 0.9f) }
            val stream = stream(gesture = gesture, tts = SamplingSilentTtsPort(), scheduler = testScheduler)
            val session = stream.openSession("necklace")
            stream.bindRole(session.sessionId, PeerRole.DAEMON)

            repeat(5) { index ->
                stream.pushImage(
                    session.sessionId,
                    jpeg = byteArrayOf((index + 1).toByte()),
                    ptsMs = index.toLong(),
                )
            }

            // Then four were evicted and only the newest still is left
            assertThat(stream.stats().imageDropped).isEqualTo(4L)
            advanceUntilIdle()
            assertThat(gesture.markers).containsExactly(5)

            stream.closeSession(session.sessionId)
            advanceUntilIdle()
            scope.cancel()
        }

    @Test
    fun `a slow model recognizes the newest still and never builds a backlog`() =
        runTest {
            // Given a model slower than the producer (300 ms vs 50 ms per still)
            val gesture =
                MarkerGesturePort(testScheduler, modelDelayMs = 300L) {
                    GestureOutcome("Thumb_Up", "好", 0.9f)
                }
            val stream = stream(gesture = gesture, tts = SamplingSilentTtsPort(), scheduler = testScheduler)
            val session = stream.openSession("necklace")
            stream.bindRole(session.sessionId, PeerRole.DAEMON)

            launch {
                repeat(10) { index ->
                    stream.pushImage(
                        session.sessionId,
                        jpeg = byteArrayOf((index + 1).toByte()),
                        ptsMs = index.toLong(),
                    )
                    delay(50L)
                }
            }

            advanceUntilIdle()

            // Then the burst collapsed instead of queueing: fewer than ten inferences...
            assertThat(gesture.markers.size).isLessThan(10)
            // ...no stale frame was ever replayed...
            assertThat(gesture.markers).isInOrder()
            // ...and the last still the model saw was the NEWEST one offered
            assertThat(gesture.markers.last()).isEqualTo(10)

            stream.closeSession(session.sessionId)
            advanceUntilIdle()
            scope.cancel()
        }

    @Test
    fun `closing while the model is mid inference drains without a sampling wait`() =
        runTest {
            // Given a still already being recognized by a slow model
            val gesture =
                MarkerGesturePort(testScheduler, modelDelayMs = 100L) {
                    GestureOutcome("Thumb_Up", "好", 0.9f)
                }
            val stream = stream(gesture = gesture, tts = SamplingSilentTtsPort(), scheduler = testScheduler)
            val session = stream.openSession("necklace")
            stream.bindRole(session.sessionId, PeerRole.DAEMON)
            stream.pushImage(session.sessionId, jpeg = byteArrayOf(1), ptsMs = 1L)
            advanceTimeBy(10)
            assertThat(gesture.markers).isEmpty()

            // When the session closes before the model returns
            val closer = launch { stream.closeSession(session.sessionId) }
            advanceUntilIdle()

            // Then the drain waited only for the in-flight inference, and the still
            // still reached the model
            assertThat(closer.isCompleted).isTrue()
            assertThat(gesture.markers).containsExactly(1)
            scope.cancel()
        }

    private fun stream(
        gesture: GesturePort,
        tts: TtsPort,
        scheduler: TestCoroutineScheduler,
    ): StreamServiceImpl {
        scope = CoroutineScope(StandardTestDispatcher(scheduler))
        return StreamServiceImpl.forTesting(
            speechPort = SamplingSilentSpeechPort(),
            gesturePort = gesture,
            ttsPort = tts,
            enrollmentStore = SamplingSilentEnrollmentStore(),
            frameSink = SamplingSilentFrameSink(),
            scope = scope,
        )
    }
}

/**
 * [GesturePort] fake keyed by the still's marker byte: it records every marker it
 * actually recognized (and when), so the latest-wins order is observable.
 * [modelDelayMs] stands in for a slow native recognizer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class MarkerGesturePort(
    private val scheduler: TestCoroutineScheduler,
    private val modelDelayMs: Long = 0L,
    private val outcomeFor: (Int) -> GestureOutcome?,
) : GesturePort {
    val markers: MutableList<Int> = mutableListOf()
    val recognizedAtMs: MutableList<Long> = mutableListOf()

    override suspend fun recognize(jpeg: ByteArray): GestureOutcome? {
        if (modelDelayMs > 0L) delay(modelDelayMs)
        val marker = jpeg.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        markers += marker
        recognizedAtMs += scheduler.currentTime
        return outcomeFor(marker)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class TimestampedTtsPort(
    private val scheduler: TestCoroutineScheduler,
) : TtsPort {
    val texts: MutableList<String> = mutableListOf()
    val spokenAtMs: MutableList<Long> = mutableListOf()

    override suspend fun synthesize(text: String): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        texts += text
        spokenAtMs += scheduler.currentTime
        return ByteArray(4) { 7 }
    }
}

private class SamplingSilentTtsPort : TtsPort {
    override suspend fun synthesize(text: String): ByteArray = ByteArray(0)
}

private class SamplingSilentSpeechPort : SpeechPort {
    override suspend fun transcribe(
        sessionId: String,
        pcm16: ByteArray,
    ): SpeechUtterance? = null

    override suspend fun close(sessionId: String) = Unit

    override suspend fun flush(sessionId: String): SpeechUtterance? = null
}

private class SamplingSilentEnrollmentStore : EnrollmentStore {
    override suspend fun enroll(
        name: String,
        pcm16: ByteArray,
    ): EnrollOutcome = EnrollOutcome.accepted

    override suspend fun names(): List<String> = emptyList()

    override suspend fun enrollments(): List<EnrolledSpeaker> = emptyList()
}

private class SamplingSilentFrameSink : FrameSink {
    override fun acceptVideo(
        sessionId: String,
        h264Nal: ByteArray,
    ) = Unit
}
