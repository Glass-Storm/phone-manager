package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.domain.adapter.enrollment.EnrollmentStore
import com.glassstorm.phonemanager.core.domain.adapter.gesture.GesturePort
import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.speech.SpeechPort
import com.glassstorm.phonemanager.core.domain.adapter.speech.TtsPort
import com.glassstorm.phonemanager.core.model.AudioFormat
import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.glassstorm.phonemanager.core.model.EnrolledSpeaker
import com.glassstorm.phonemanager.core.model.GestureOutcome
import com.glassstorm.phonemanager.core.model.PeerRole
import com.glassstorm.phonemanager.core.model.RelayOutput
import com.glassstorm.phonemanager.core.model.SpeechUtterance
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Process-safety proof for the relay pumps: a throwing ENGINE (the shape of an
 * ai-android native failure, a MediaPipe abort, or a Bitmap OOM) must be contained
 * at the failing item, must never cancel a sibling pump, and must never skip
 * session teardown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamPumpResilienceTest {
    @get:Rule
    val deadline: Timeout = Timeout.seconds(60)

    @Test
    fun `a throwing speech port does not stop the video pump or skip teardown`() =
        runTest {
            val speech = FailingSpeechPort()
            val sink = CapturingFrameSink()
            val stream = stream(testScheduler, speech = speech, frameSink = sink)
            val session = stream.openSession("glasses")
            stream.bindRole(session.sessionId, PeerRole.GLASS)

            stream.pushAudio(session.sessionId, pcm(), AudioFormat.SAMPLE_RATE_HZ)
            stream.pushVideo(session.sessionId, nal())
            advanceUntilIdle()

            assertThat(sink.videoNals).hasSize(1)

            stream.closeSession(session.sessionId)
            advanceUntilIdle()

            assertThat(speech.closedSessions).containsExactly(session.sessionId)
            scope.cancel()
        }

    @Test
    fun `a throwing gesture port does not stop the audio pump or skip teardown`() =
        runTest {
            val speech = CapturingSpeechPort()
            val stream = stream(testScheduler, speech = speech, gesture = FailingGesturePort())
            val session = stream.openSession("glasses")
            stream.bindRole(session.sessionId, PeerRole.GLASS)
            val outputs = mutableListOf<RelayOutput>()
            val collector = launch { stream.results(session.sessionId).collect { outputs += it } }

            stream.pushAudio(session.sessionId, pcm(), AudioFormat.SAMPLE_RATE_HZ)
            stream.pushImage(session.sessionId, jpeg(), ptsMs = 0L)
            advanceUntilIdle()

            val subtitles = outputs.filterIsInstance<RelayOutput.Speech>()
            assertThat(subtitles).hasSize(1)

            stream.closeSession(session.sessionId)
            advanceUntilIdle()
            collector.join()

            assertThat(speech.closedSessions).containsExactly(session.sessionId)
            scope.cancel()
        }

    @Test
    fun `a throwing frame sink does not stop the audio or image pumps`() =
        runTest {
            val speech = CapturingSpeechPort()
            val stream = stream(testScheduler, speech = speech, frameSink = FailingFrameSink())
            val session = stream.openSession("glasses")
            stream.bindRole(session.sessionId, PeerRole.GLASS)
            val outputs = mutableListOf<RelayOutput>()
            val collector = launch { stream.results(session.sessionId).collect { outputs += it } }

            stream.pushAudio(session.sessionId, pcm(), AudioFormat.SAMPLE_RATE_HZ)
            stream.pushVideo(session.sessionId, nal())
            stream.pushImage(session.sessionId, jpeg(), ptsMs = 0L)
            advanceUntilIdle()

            assertThat(outputs.filterIsInstance<RelayOutput.Speech>()).hasSize(1)
            assertThat(outputs.filterIsInstance<RelayOutput.Gesture>()).hasSize(1)

            stream.closeSession(session.sessionId)
            advanceUntilIdle()
            collector.join()

            assertThat(speech.closedSessions).containsExactly(session.sessionId)
            scope.cancel()
        }

    @Test
    fun `a throwing tts port still delivers every gesture and keeps audio alive`() =
        runTest {
            val speech = CapturingSpeechPort()
            val stream = stream(testScheduler, speech = speech, tts = FailingTtsPort())
            val session = stream.openSession("glasses")
            stream.bindRole(session.sessionId, PeerRole.GLASS)
            val outputs = mutableListOf<RelayOutput>()
            val collector = launch { stream.results(session.sessionId).collect { outputs += it } }

            stream.pushAudio(session.sessionId, pcm(), AudioFormat.SAMPLE_RATE_HZ)
            stream.pushImage(session.sessionId, jpeg(), ptsMs = 0L)
            stream.pushImage(session.sessionId, jpeg(), ptsMs = 0L)
            advanceUntilIdle()

            assertThat(outputs.filterIsInstance<RelayOutput.Gesture>()).hasSize(2)
            assertThat(outputs.filterIsInstance<RelayOutput.Speech>()).hasSize(1)
            assertThat(outputs.filterIsInstance<RelayOutput.TtsAudio>()).isEmpty()

            stream.closeSession(session.sessionId)
            advanceUntilIdle()
            collector.join()

            assertThat(speech.closedSessions).containsExactly(session.sessionId)
            scope.cancel()
        }

    @Test
    fun `images are latest wins so a full queue never parks the push`() =
        runTest {
            val sink = CapturingFrameSink()
            scope = testScope(testScheduler)
            val stream =
                StreamServiceImpl.forTesting(
                    speechPort = CapturingSpeechPort(),
                    gesturePort = AlwaysGesturePort(),
                    ttsPort = SilentTtsPort(),
                    enrollmentStore = SilentEnrollmentStore(),
                    frameSink = sink,
                    imageCapacity = 2,
                    scope = scope,
                )
            val session = stream.openSession("necklace")
            stream.bindRole(session.sessionId, PeerRole.DAEMON)

            repeat(5) { stream.pushImage(session.sessionId, jpeg(), ptsMs = it.toLong()) }

            assertThat(stream.stats().imageDropped).isEqualTo(3L)

            advanceUntilIdle()
            stream.closeSession(session.sessionId)
            advanceUntilIdle()
            scope.cancel()
        }

    // ---------------------------------------------------------------------- helpers

    private lateinit var scope: CoroutineScope

    private fun stream(
        scheduler: TestCoroutineScheduler,
        speech: SpeechPort = CapturingSpeechPort(),
        gesture: GesturePort = AlwaysGesturePort(),
        tts: TtsPort = SilentTtsPort(),
        frameSink: FrameSink = CapturingFrameSink(),
    ): StreamServiceImpl {
        val pumpScope = testScope(scheduler)
        scope = pumpScope
        return StreamServiceImpl.forTesting(
            speechPort = speech,
            gesturePort = gesture,
            ttsPort = tts,
            enrollmentStore = SilentEnrollmentStore(),
            frameSink = frameSink,
            scope = pumpScope,
        )
    }

    private fun testScope(scheduler: TestCoroutineScheduler): CoroutineScope = CoroutineScope(StandardTestDispatcher(scheduler))

    private fun pcm(): ByteArray = ByteArray(AudioFormat.CHUNK_BYTES) { (it % 100).toByte() }

    private fun nal(): ByteArray = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65)

    private fun jpeg(): ByteArray = byteArrayOf(1, 2, 3)
}

private class FailingSpeechPort : SpeechPort {
    val closedSessions: MutableList<String> = mutableListOf()

    override suspend fun transcribe(
        sessionId: String,
        pcm16: ByteArray,
    ): SpeechUtterance? = throw IllegalStateException("native ASR abort")

    override suspend fun close(sessionId: String) {
        closedSessions += sessionId
    }

    override suspend fun flush(sessionId: String): SpeechUtterance? = null
}

private class CapturingSpeechPort : SpeechPort {
    val closedSessions: MutableList<String> = mutableListOf()

    override suspend fun transcribe(
        sessionId: String,
        pcm16: ByteArray,
    ): SpeechUtterance = SpeechUtterance(text = "hello", speakerName = null)

    override suspend fun close(sessionId: String) {
        closedSessions += sessionId
    }

    override suspend fun flush(sessionId: String): SpeechUtterance? = null
}

private class FailingGesturePort : GesturePort {
    override suspend fun recognize(jpeg: ByteArray): GestureOutcome? = throw IllegalStateException("MediaPipe abort")
}

private class AlwaysGesturePort : GesturePort {
    override suspend fun recognize(jpeg: ByteArray): GestureOutcome = GestureOutcome(label = "Thumb_Up", text = "Yes", confidence = 0.9f)
}

private class FailingTtsPort : TtsPort {
    override suspend fun synthesize(text: String): ByteArray = throw IllegalStateException("TTS native abort")
}

private class SilentTtsPort : TtsPort {
    override suspend fun synthesize(text: String): ByteArray = ByteArray(0)
}

private class CapturingFrameSink : FrameSink {
    val videoNals: MutableList<ByteArray> = mutableListOf()

    override fun acceptVideo(
        sessionId: String,
        h264Nal: ByteArray,
    ) {
        videoNals += h264Nal
    }
}

private class FailingFrameSink : FrameSink {
    override fun acceptVideo(
        sessionId: String,
        h264Nal: ByteArray,
    ) = throw IllegalStateException("sink write failed")
}

private class SilentEnrollmentStore : EnrollmentStore {
    override suspend fun enroll(
        name: String,
        pcm16: ByteArray,
    ): EnrollOutcome = EnrollOutcome.accepted

    override suspend fun names(): List<String> = emptyList()

    override suspend fun enrollments(): List<EnrolledSpeaker> = emptyList()
}
