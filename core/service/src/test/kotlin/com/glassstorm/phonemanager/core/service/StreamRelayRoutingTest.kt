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
import com.glassstorm.phonemanager.core.model.LivePeer
import com.glassstorm.phonemanager.core.model.PeerRole
import com.glassstorm.phonemanager.core.model.RelayOutput
import com.glassstorm.phonemanager.core.model.SpeechUtterance
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The FOCUSED fake-engined relay routing suite: the cross-session gesture loop,
 * the audio->subtitle loop, and the enrollment round trip.
 *
 * The engines are local fakes (exactly like a real adapter would be: the domain
 * INTERFACES only), so this proves the ROUTING — which session receives which
 * frame — independently of any real gesture/ASR/TTS model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamRelayRoutingTest {
    @get:Rule
    val deadline: Timeout = Timeout.seconds(60)

    @Test
    fun `an image from the necklace yields a gesture to the glasses and tts to the necklace`() =
        runTest {
            // Given a necklace session and a glasses session, each bound to its role
            val wired =
                wired(
                    gesture = FixedGesturePort(GestureOutcome(label = "Thumb_Up", text = "Yes", confidence = 0.9f)),
                    tts = FixedTtsPort(ByteArray(4) { 7 }),
                )
            val scope = testScope(testScheduler)
            val stream = wired.newStream(scope)
            val necklace = stream.openSession("necklace")
            stream.bindRole(necklace.sessionId, PeerRole.DAEMON)
            val glasses = stream.openSession("glasses")
            stream.bindRole(glasses.sessionId, PeerRole.GLASS)
            val necklaceOutputs = mutableListOf<RelayOutput>()
            val glassesOutputs = mutableListOf<RelayOutput>()
            val necklaceCollector = launch { stream.results(necklace.sessionId).collect { necklaceOutputs += it } }
            val glassesCollector = launch { stream.results(glasses.sessionId).collect { glassesOutputs += it } }

            // When the necklace pushes a still image
            stream.pushImage(necklace.sessionId, jpeg = byteArrayOf(1, 2, 3), ptsMs = 123L)
            advanceUntilIdle()

            // Then the glasses session received the gesture text + label + confidence
            val gesture = glassesOutputs.filterIsInstance<RelayOutput.Gesture>().single()
            assertThat(gesture.text).isEqualTo("Yes")
            assertThat(gesture.label).isEqualTo("Thumb_Up")
            assertThat(gesture.confidence).isEqualTo(0.9f)
            assertThat(gesture.ptsMs).isEqualTo(123L)

            // And the necklace session received the synthesized speech for the same meaning
            val tts = necklaceOutputs.filterIsInstance<RelayOutput.TtsAudio>().single()
            assertThat(tts.pcm16).isEqualTo(ByteArray(4) { 7 })
            assertThat(tts.ptsMs).isEqualTo(123L)

            // And the GLASSES session received the SAME synthesized audio, so its player is live
            val glassesTts = glassesOutputs.filterIsInstance<RelayOutput.TtsAudio>().single()
            assertThat(glassesTts.pcm16).isEqualTo(tts.pcm16)
            assertThat(glassesTts.ptsMs).isEqualTo(123L)

            // And the image/gesture channel is counted separately from H.264 NALs.
            val stats = stream.stats()
            assertThat(stats.imageFrames).isEqualTo(1L)
            assertThat(stats.gestures).isEqualTo(1L)

            stream.closeSession(necklace.sessionId)
            stream.closeSession(glasses.sessionId)
            advanceUntilIdle()
            necklaceCollector.join()
            glassesCollector.join()
            scope.cancel()
        }

    @Test
    fun `a failing tts still delivers the gesture and keeps the image pump alive`() =
        runTest {
            // Given a synthesizer that fails on every text (the Cantonese voice rejects
            // a meaning it cannot speak), plus a necklace and a glasses session
            val wired =
                wired(
                    gesture = FixedGesturePort(GestureOutcome(label = "Thumb_Up", text = "好", confidence = 0.9f)),
                    tts = ThrowingTtsPort(),
                )
            val scope = testScope(testScheduler)
            val stream = wired.newStream(scope)
            val necklace = stream.openSession("necklace")
            stream.bindRole(necklace.sessionId, PeerRole.DAEMON)
            val glasses = stream.openSession("glasses")
            stream.bindRole(glasses.sessionId, PeerRole.GLASS)
            val necklaceOutputs = mutableListOf<RelayOutput>()
            val glassesOutputs = mutableListOf<RelayOutput>()
            val necklaceCollector = launch { stream.results(necklace.sessionId).collect { necklaceOutputs += it } }
            val glassesCollector = launch { stream.results(glasses.sessionId).collect { glassesOutputs += it } }

            // When TWO stills are pushed while synthesis throws both times
            stream.pushImage(necklace.sessionId, jpeg = byteArrayOf(1, 2, 3), ptsMs = 1L)
            stream.pushImage(necklace.sessionId, jpeg = byteArrayOf(4, 5, 6), ptsMs = 2L)
            advanceUntilIdle()

            // Then BOTH gestures were delivered: the first failure did not kill the pump
            val gestures = glassesOutputs.filterIsInstance<RelayOutput.Gesture>()
            assertThat(gestures.map { it.ptsMs }).containsExactly(1L, 2L).inOrder()
            assertThat(gestures.map { it.text }).containsExactly("好", "好")

            // And the TTS half was skipped rather than crashing the relay
            assertThat(necklaceOutputs.filterIsInstance<RelayOutput.TtsAudio>()).isEmpty()

            stream.closeSession(necklace.sessionId)
            stream.closeSession(glasses.sessionId)
            advanceUntilIdle()
            necklaceCollector.join()
            glassesCollector.join()
            scope.cancel()
        }

    @Test
    fun `an unmatched hand produces no gesture and no tts`() =
        runTest {
            // Given a gesture port that detects no hand
            val wired = wired(gesture = FixedGesturePort(null))
            val scope = testScope(testScheduler)
            val stream = wired.newStream(scope)
            val necklace = stream.openSession("necklace")
            stream.bindRole(necklace.sessionId, PeerRole.DAEMON)
            val glasses = stream.openSession("glasses")
            stream.bindRole(glasses.sessionId, PeerRole.GLASS)
            val glassesOutputs = mutableListOf<RelayOutput>()
            val necklaceOutputs = mutableListOf<RelayOutput>()
            launch { stream.results(glasses.sessionId).collect { glassesOutputs += it } }
            launch { stream.results(necklace.sessionId).collect { necklaceOutputs += it } }

            // When the image carries no recognizable hand
            stream.pushImage(necklace.sessionId, jpeg = byteArrayOf(), ptsMs = 1L)
            advanceUntilIdle()

            // Then nothing was emitted anywhere, but the offered still was counted
            assertThat(glassesOutputs).isEmpty()
            assertThat(necklaceOutputs).isEmpty()
            assertThat(stream.stats().imageFrames).isEqualTo(1L)
            assertThat(stream.stats().gestures).isEqualTo(0L)

            stream.closeSession(necklace.sessionId)
            stream.closeSession(glasses.sessionId)
            advanceUntilIdle()
            scope.cancel()
        }

    @Test
    fun `audio from the glasses yields a speaker tagged subtitle on the same session`() =
        runTest {
            // Given a glasses session whose speech engine resolves an enrolled speaker
            val wired = wired(speech = FixedSpeechPort(SpeechUtterance(text = "hello", speakerName = "Alice")))
            val scope = testScope(testScheduler)
            val stream = wired.newStream(scope)
            val glasses = stream.openSession("glasses")
            stream.bindRole(glasses.sessionId, PeerRole.GLASS)
            val outputs = mutableListOf<RelayOutput>()
            val collector = launch { stream.results(glasses.sessionId).collect { outputs += it } }

            // When the glasses pushes an audio frame
            stream.pushAudio(glasses.sessionId, pcm(8), AudioFormat.SAMPLE_RATE_HZ)
            advanceUntilIdle()

            // Then the subtitle came back on the SAME session with the speaker label
            val speech = outputs.filterIsInstance<RelayOutput.Speech>().single()
            assertThat(speech.result.text).isEqualTo("hello")
            assertThat(speech.result.speakerLabel).isEqualTo("Alice")
            assertThat(stream.stats().transcripts).isEqualTo(1L)

            stream.closeSession(glasses.sessionId)
            advanceUntilIdle()
            collector.join()
            scope.cancel()
        }

    @Test
    fun `an unregistered speaker yields an empty subtitle label`() =
        runTest {
            // Given a speech engine that cannot resolve the speaker
            val wired = wired(speech = FixedSpeechPort(SpeechUtterance(text = "who am i", speakerName = null)))
            val scope = testScope(testScheduler)
            val stream = wired.newStream(scope)
            val glasses = stream.openSession("glasses")
            stream.bindRole(glasses.sessionId, PeerRole.GLASS)
            val outputs = mutableListOf<RelayOutput>()
            val collector = launch { stream.results(glasses.sessionId).collect { outputs += it } }

            // When audio is pushed
            stream.pushAudio(glasses.sessionId, pcm(8), AudioFormat.SAMPLE_RATE_HZ)
            advanceUntilIdle()

            // Then the subtitle carries the EMPTY label, never an invented name
            val speech = outputs.filterIsInstance<RelayOutput.Speech>().single()
            assertThat(speech.result.speakerLabel).isEmpty()

            stream.closeSession(glasses.sessionId)
            advanceUntilIdle()
            collector.join()
            scope.cancel()
        }

    @Test
    fun `an enroll request yields an enroll response on the same session`() =
        runTest {
            // Given an enrollment store that accepts
            val wired = wired(enrollment = FixedEnrollmentStore(EnrollOutcome.accepted))
            val scope = testScope(testScheduler)
            val stream = wired.newStream(scope)
            val glasses = stream.openSession("glasses")
            val outputs = mutableListOf<RelayOutput>()
            val collector = launch { stream.results(glasses.sessionId).collect { outputs += it } }

            // When a valid enrollment request arrives
            stream.pushEnroll(glasses.sessionId, name = "Alice", pcm16 = ByteArray(8), ptsMs = 5L)
            advanceUntilIdle()

            // Then the response came back ok on the same session
            val response = outputs.filterIsInstance<RelayOutput.EnrollResponse>().single()
            assertThat(response.ok).isTrue()
            assertThat(response.reason).isNull()

            stream.closeSession(glasses.sessionId)
            advanceUntilIdle()
            collector.join()
            scope.cancel()
        }

    @Test
    fun `a rejected enroll request carries the reason back`() =
        runTest {
            // Given an enrollment store that rejects
            val wired =
                wired(enrollment = FixedEnrollmentStore(EnrollOutcome.rejected(EnrollOutcome.REASON_INVALID_NAME)))
            val scope = testScope(testScheduler)
            val stream = wired.newStream(scope)
            val glasses = stream.openSession("glasses")
            val outputs = mutableListOf<RelayOutput>()
            val collector = launch { stream.results(glasses.sessionId).collect { outputs += it } }

            // When the request is rejected
            stream.pushEnroll(glasses.sessionId, name = "bad name", pcm16 = ByteArray(8), ptsMs = 5L)
            advanceUntilIdle()

            // Then the machine-checkable reason crosses the boundary
            val response = outputs.filterIsInstance<RelayOutput.EnrollResponse>().single()
            assertThat(response.ok).isFalse()
            assertThat(response.reason).isEqualTo(EnrollOutcome.REASON_INVALID_NAME)

            stream.closeSession(glasses.sessionId)
            advanceUntilIdle()
            collector.join()
            scope.cancel()
        }

    @Test
    fun `live peers report each role and leave an unbound session unspecified`() =
        runTest {
            // Given a necklace, a glasses and an unbound session
            val scope = testScope(testScheduler)
            val stream = wired().newStream(scope)
            val necklace = stream.openSession("necklace")
            stream.bindRole(necklace.sessionId, PeerRole.DAEMON)
            val glasses = stream.openSession("glasses")
            stream.bindRole(glasses.sessionId, PeerRole.GLASS)
            stream.openSession("unbound")

            // Then each live peer carries its bound role, or unspecified
            assertThat(stream.livePeers())
                .containsExactly(
                    LivePeer(deviceId = "glasses", role = PeerRole.GLASS),
                    LivePeer(deviceId = "necklace", role = PeerRole.DAEMON),
                    LivePeer(deviceId = "unbound", role = PeerRole.UNSPECIFIED),
                )

            stream.closeSession(necklace.sessionId)
            stream.closeSession(glasses.sessionId)
            advanceUntilIdle()
            scope.cancel()
        }

    @Test
    fun `every speech output is mirrored onto the shared outputs flow`() =
        runTest {
            // Given a live session with a collector on the shared outputs flow
            val wired = wired(speech = FixedSpeechPort(SpeechUtterance(text = "hello", speakerName = "Alice")))
            val scope = testScope(testScheduler)
            val stream = wired.newStream(scope)
            val glasses = stream.openSession("glasses")
            stream.bindRole(glasses.sessionId, PeerRole.GLASS)
            val outputs = mutableListOf<RelayOutput>()
            val collector = launch { stream.outputs().collect { outputs += it } }

            // When a subtitle is produced
            stream.pushAudio(glasses.sessionId, pcm(8), AudioFormat.SAMPLE_RATE_HZ)
            advanceUntilIdle()

            // Then the shared flow mirrored the same speech output
            val speech = outputs.filterIsInstance<RelayOutput.Speech>().single()
            assertThat(speech.result.text).isEqualTo("hello")
            assertThat(speech.result.speakerLabel).isEqualTo("Alice")

            collector.cancel()
            stream.closeSession(glasses.sessionId)
            advanceUntilIdle()
            scope.cancel()
        }

    @Test
    fun `every gesture output is mirrored onto the shared outputs flow`() =
        runTest {
            // Given a necklace session and a collector on the shared outputs flow
            val wired =
                wired(
                    gesture = FixedGesturePort(GestureOutcome(label = "Thumb_Up", text = "Yes", confidence = 0.9f)),
                    tts = FixedTtsPort(ByteArray(0)),
                )
            val scope = testScope(testScheduler)
            val stream = wired.newStream(scope)
            val necklace = stream.openSession("necklace")
            stream.bindRole(necklace.sessionId, PeerRole.DAEMON)
            val outputs = mutableListOf<RelayOutput>()
            val collector = launch { stream.outputs().collect { outputs += it } }

            // When the necklace pushes a recognizable still
            stream.pushImage(necklace.sessionId, jpeg = byteArrayOf(1, 2, 3), ptsMs = 42L)
            advanceUntilIdle()

            // Then the shared flow mirrored the gesture output
            val gesture = outputs.filterIsInstance<RelayOutput.Gesture>().single()
            assertThat(gesture.text).isEqualTo("Yes")
            assertThat(gesture.ptsMs).isEqualTo(42L)

            collector.cancel()
            stream.closeSession(necklace.sessionId)
            advanceUntilIdle()
            scope.cancel()
        }

    @Test
    fun `an utterance buffered at close is flushed as one final transcript`() =
        runTest {
            // Given an engine that only yields on flush, like a peer that stopped
            // without the trailing silence that closes the utterance
            val speech = BufferedSpeechPort(SpeechUtterance(text = "last words", speakerName = "Alice"))
            val scope = testScope(testScheduler)
            val stream = wired(speech = speech).newStream(scope)
            val glasses = stream.openSession("glasses")
            stream.bindRole(glasses.sessionId, PeerRole.GLASS)
            val outputs = mutableListOf<RelayOutput>()
            val collector = launch { stream.results(glasses.sessionId).collect { outputs += it } }

            // When one audio frame arrives and the session closes before a close-of-utterance
            stream.pushAudio(glasses.sessionId, pcm(8), AudioFormat.SAMPLE_RATE_HZ)
            advanceUntilIdle()
            assertThat(outputs.filterIsInstance<RelayOutput.Speech>()).isEmpty()

            stream.closeSession(glasses.sessionId)
            advanceUntilIdle()

            // Then the buffered speech was flushed as exactly ONE transcript before close
            val subtitle = outputs.filterIsInstance<RelayOutput.Speech>().single()
            assertThat(subtitle.result.text).isEqualTo("last words")
            assertThat(subtitle.result.speakerLabel).isEqualTo("Alice")
            assertThat(stream.stats().transcripts).isEqualTo(1L)
            assertThat(speech.events).containsExactly("transcribe", "flush", "close").inOrder()

            collector.join()
            scope.cancel()
        }

    @Test
    fun `a still queued at close still yields its synthesized speech`() =
        runTest {
            // Given a necklace still already accepted but not yet processed at close
            val tts = CapturingTtsPort(ByteArray(4) { 7 })
            val wired =
                wired(
                    gesture = FixedGesturePort(GestureOutcome(label = "Thumb_Up", text = "Yes", confidence = 0.9f)),
                    tts = tts,
                )
            val scope = testScope(testScheduler)
            val stream = wired.newStream(scope)
            val necklace = stream.openSession("necklace")
            stream.bindRole(necklace.sessionId, PeerRole.DAEMON)
            val glasses = stream.openSession("glasses")
            stream.bindRole(glasses.sessionId, PeerRole.GLASS)
            val glassesOutputs = mutableListOf<RelayOutput>()
            val collector = launch { stream.results(glasses.sessionId).collect { glassesOutputs += it } }

            // When the still is queued and the sender closes before the pumps run
            stream.pushImage(necklace.sessionId, jpeg = byteArrayOf(1, 2, 3), ptsMs = 9L)
            stream.closeSession(necklace.sessionId)
            advanceUntilIdle()

            // Then the drain recognized the still and synthesized its speech
            assertThat(glassesOutputs.filterIsInstance<RelayOutput.Gesture>()).hasSize(1)
            assertThat(glassesOutputs.filterIsInstance<RelayOutput.TtsAudio>()).hasSize(1)
            assertThat(tts.synthesized).containsExactly("Yes")

            stream.closeSession(glasses.sessionId)
            advanceUntilIdle()
            collector.join()
            scope.cancel()
        }

    @Test
    fun `a still queued at the daemon half-close delivers its tts to the glasses and the closing daemon`() =
        runTest {
            // Given a necklace still already accepted but not yet processed at close
            val tts = CapturingTtsPort(ByteArray(4) { 7 })
            val wired =
                wired(
                    gesture = FixedGesturePort(GestureOutcome(label = "Thumb_Up", text = "Yes", confidence = 0.9f)),
                    tts = tts,
                )
            val scope = testScope(testScheduler)
            val stream = wired.newStream(scope)
            val necklace = stream.openSession("necklace")
            stream.bindRole(necklace.sessionId, PeerRole.DAEMON)
            val glasses = stream.openSession("glasses")
            stream.bindRole(glasses.sessionId, PeerRole.GLASS)
            val necklaceOutputs = mutableListOf<RelayOutput>()
            val glassesOutputs = mutableListOf<RelayOutput>()
            val necklaceCollector = launch { stream.results(necklace.sessionId).collect { necklaceOutputs += it } }
            val glassesCollector = launch { stream.results(glasses.sessionId).collect { glassesOutputs += it } }
            advanceUntilIdle()

            // When the still is queued and the DAEMON half-closes before the pumps run
            stream.pushImage(necklace.sessionId, jpeg = byteArrayOf(1, 2, 3), ptsMs = 9L)
            stream.closeSession(necklace.sessionId)
            advanceUntilIdle()

            // Then the drained still's speech reached BOTH the live glasses session
            // AND the closing DAEMON session, which is still reading during its drain
            val glassesTts = glassesOutputs.filterIsInstance<RelayOutput.TtsAudio>().single()
            val daemonTts = necklaceOutputs.filterIsInstance<RelayOutput.TtsAudio>().single()
            assertThat(daemonTts.pcm16).isEqualTo(ByteArray(4) { 7 })
            assertThat(daemonTts.ptsMs).isEqualTo(9L)
            assertThat(glassesTts.pcm16).isEqualTo(daemonTts.pcm16)
            assertThat(tts.synthesized).containsExactly("Yes")

            stream.closeSession(glasses.sessionId)
            advanceUntilIdle()
            necklaceCollector.join()
            glassesCollector.join()
            scope.cancel()
        }

    // ---------------------------------------------------------------------- helpers

    private class Wired(
        val speech: SpeechPort,
        val gesture: GesturePort,
        val tts: TtsPort,
        val enrollment: EnrollmentStore,
    ) {
        fun newStream(scope: CoroutineScope): StreamServiceImpl =
            StreamServiceImpl.forTesting(
                speechPort = speech,
                gesturePort = gesture,
                ttsPort = tts,
                enrollmentStore = enrollment,
                frameSink = NoopFrameSink(),
                scope = scope,
            )
    }

    private fun wired(
        speech: SpeechPort = FixedSpeechPort(SpeechUtterance(text = "unset", speakerName = null)),
        gesture: GesturePort = FixedGesturePort(null),
        tts: TtsPort = FixedTtsPort(ByteArray(0)),
        enrollment: EnrollmentStore = FixedEnrollmentStore(EnrollOutcome.accepted),
    ): Wired = Wired(speech, gesture, tts, enrollment)

    private fun testScope(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(scheduler))

    private fun pcm(size: Int): ByteArray = ByteArray(size) { (it + 1).toByte() }
}

/** [GesturePort] fake returning a fixed outcome (also for the "no hand" null case). */
private class FixedGesturePort(
    private val outcome: GestureOutcome?,
) : GesturePort {
    override suspend fun recognize(jpeg: ByteArray): GestureOutcome? = if (jpeg.isEmpty()) null else outcome
}

/** [SpeechPort] fake returning a fixed utterance and recording session closes. */
private class FixedSpeechPort(
    private val utterance: SpeechUtterance?,
) : SpeechPort {
    val closedSessions: MutableList<String> = mutableListOf()

    override suspend fun transcribe(
        sessionId: String,
        pcm16: ByteArray,
    ): SpeechUtterance? = if (pcm16.isEmpty()) null else utterance

    override suspend fun close(sessionId: String) {
        closedSessions += sessionId
    }

    override suspend fun flush(sessionId: String): SpeechUtterance? = null
}

/** [SpeechPort] fake that keeps its utterance until [flush], like a streaming engine. */
private class BufferedSpeechPort(
    private val utterance: SpeechUtterance,
) : SpeechPort {
    val events: MutableList<String> = mutableListOf()

    override suspend fun transcribe(
        sessionId: String,
        pcm16: ByteArray,
    ): SpeechUtterance? {
        events += "transcribe"
        return null
    }

    override suspend fun flush(sessionId: String): SpeechUtterance {
        events += "flush"
        return utterance
    }

    override suspend fun close(sessionId: String) {
        events += "close"
    }
}

/** [TtsPort] fake returning fixed PCM bytes. */
private class FixedTtsPort(
    private val pcm16: ByteArray,
) : TtsPort {
    override suspend fun synthesize(text: String): ByteArray = if (text.isEmpty()) ByteArray(0) else pcm16
}

/** [TtsPort] fake that records every text it synthesized. */
private class CapturingTtsPort(
    private val pcm16: ByteArray,
) : TtsPort {
    val synthesized: MutableList<String> = mutableListOf()

    override suspend fun synthesize(text: String): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        synthesized += text
        return pcm16
    }
}

/** [TtsPort] fake whose synthesis always fails, like a voice that rejects the language. */
private class ThrowingTtsPort : TtsPort {
    override suspend fun synthesize(text: String): ByteArray = throw IllegalStateException("no samples for '$text'")
}

/** [EnrollmentStore] fake returning a fixed outcome. */
private class FixedEnrollmentStore(
    private val outcome: EnrollOutcome,
) : EnrollmentStore {
    override suspend fun enroll(
        name: String,
        pcm16: ByteArray,
    ): EnrollOutcome = outcome

    override suspend fun names(): List<String> = emptyList()

    override suspend fun enrollments(): List<EnrolledSpeaker> = emptyList()
}

/** [FrameSink] fake that accepts (and ignores) every opaque NAL. */
private class NoopFrameSink : FrameSink {
    override fun acceptVideo(
        sessionId: String,
        h264Nal: ByteArray,
    ) = Unit
}
