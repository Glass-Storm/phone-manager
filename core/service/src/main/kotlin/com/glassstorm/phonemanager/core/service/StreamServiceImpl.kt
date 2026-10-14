package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.domain.adapter.enrollment.EnrollmentStore
import com.glassstorm.phonemanager.core.domain.adapter.gesture.GesturePort
import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.speech.SpeechPort
import com.glassstorm.phonemanager.core.domain.adapter.speech.TtsPort
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.glassstorm.phonemanager.core.model.PeerRole
import com.glassstorm.phonemanager.core.model.RelayOutput
import com.glassstorm.phonemanager.core.model.RelayResult
import com.glassstorm.phonemanager.core.model.RelaySession
import com.glassstorm.phonemanager.core.model.RelayStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.inject.Inject

/**
 * Audio/video/image relay use-cases with bounded queues and deterministic teardown.
 *
 * Orchestration only: this class owns the pumps and the session lifecycle, while
 * [RelayStatsRecorder] owns the counters and [RelaySessionRegistry] owns the live
 * session map (including the role index used to route between peers).
 *
 * [SpeechPort], [GesturePort], [TtsPort], [EnrollmentStore] and [FrameSink] are
 * CONSTRUCTOR dependencies, so any engine (deterministic JVM fake, on-device
 * ai-android engine, recorder) can be composed in without `:service` knowing
 * about it.
 *
 * ## Architecture
 *
 * Each session owns a [RelayQueue] and THREE independent pump loops on [scope]
 * (audio, video, image — no `select`, so a slow recognizer can never starve the
 * video sink or vice versa):
 *
 *  * the audio pump transcribes frames and emits [RelayOutput.Speech] on an
 *    UNLIMITED result channel;
 *  * the video pump hands NALs to the [FrameSink] byte-for-byte unchanged;
 *  * the image pump recognizes the still, emits the [RelayOutput.Gesture] to the
 *    GLASSES session and synthesizes the meaning as [RelayOutput.TtsAudio] to the
 *    NECKLACE session (both resolved by role from the registry).
 *
 * [closeSession] DRAINS rather than discards: it closes the queue, joins the
 * pumps (so already-queued frames still reach the engines/sink), closes the
 * result channel, then releases the speech session exactly once. It never cancels.
 *
 * ## Constructor shape
 *
 * The tuning parameters (`audioCapacity`, `videoCapacity`, `imageCapacity`,
 * `scope`) exist ONLY so tests can drive the queue policies deterministically;
 * production always wants the defaults. Kotlin default arguments are invisible to
 * Dagger, so they are dropped from the [Inject] constructor and owned as the
 * private constants [DEFAULT_AUDIO_CAPACITY] / [DEFAULT_VIDEO_CAPACITY] /
 * [DEFAULT_IMAGE_CAPACITY]. The [forTesting] factory is the seam the suite uses
 * to pass an explicit scope or capacity.
 */
class StreamServiceImpl
    private constructor(
        private val speechPort: SpeechPort,
        private val gesturePort: GesturePort,
        private val ttsPort: TtsPort,
        private val enrollmentStore: EnrollmentStore,
        private val frameSink: FrameSink,
        private val audioCapacity: Int,
        private val videoCapacity: Int,
        private val imageCapacity: Int,
        private val scope: CoroutineScope,
    ) : StreamService {
        @Inject
        constructor(
            speechPort: SpeechPort,
            gesturePort: GesturePort,
            ttsPort: TtsPort,
            enrollmentStore: EnrollmentStore,
            frameSink: FrameSink,
        ) : this(
            speechPort,
            gesturePort,
            ttsPort,
            enrollmentStore,
            frameSink,
            DEFAULT_AUDIO_CAPACITY,
            DEFAULT_VIDEO_CAPACITY,
            DEFAULT_IMAGE_CAPACITY,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

        private val random = SecureRandom()

        private val stats = RelayStatsRecorder()
        private val registry = RelaySessionRegistry()

        override fun openSession(deviceId: String): RelaySession {
            val relay = RelaySession(sessionId = newSessionId(), deviceId = deviceId)
            val queue = RelayQueue(audioCapacity, videoCapacity, imageCapacity)
            val results = Channel<RelayOutput>(Channel.UNLIMITED)
            val sessionId = relay.sessionId
            // One parent job per session: joining it drains ALL pumps.
            val pump =
                scope.launch {
                    launch { audioPump(sessionId, queue, results) }
                    launch { videoPump(sessionId, queue) }
                    launch { imagePump(sessionId, queue, results) }
                }
            registry.register(sessionId, SessionState(relay, queue, results, pump))
            return relay
        }

        override fun bindRole(
            sessionId: String,
            role: PeerRole,
        ) {
            if (registry.find(sessionId) == null) return
            registry.bindRole(sessionId, role)
        }

        override suspend fun pushAudio(
            sessionId: String,
            audioPcm16: ByteArray,
            sampleRateHz: Int,
        ) {
            val state = registry.find(sessionId) ?: return
            try {
                // Parks while the audio queue is full — audio is never dropped.
                state.queue.admitAudio(audioPcm16)
            } catch (closed: ClosedSendChannelException) {
                // The session closed between the lookup and the send: a no-op, like an
                // unknown session. Not admitted, so it is not counted.
                return
            }
            stats.onAudioFrame()
        }

        override fun pushVideo(
            sessionId: String,
            h264Nal: ByteArray,
        ) {
            val state = registry.find(sessionId) ?: return
            stats.onVideoOffered(dropped = state.queue.admitVideo(h264Nal))
        }

        override suspend fun pushImage(
            sessionId: String,
            jpeg: ByteArray,
            ptsMs: Long,
        ) {
            val state = registry.find(sessionId) ?: return
            try {
                // Parks while the image queue is full — a still is never dropped silently.
                state.queue.admitImage(QueuedImage(jpeg = jpeg, ptsMs = ptsMs))
            } catch (closed: ClosedSendChannelException) {
                return
            }
        }

        override suspend fun pushEnroll(
            sessionId: String,
            name: String,
            pcm16: ByteArray,
            ptsMs: Long,
        ) {
            val state = registry.find(sessionId) ?: return
            val outcome =
                runCatching { enrollmentStore.enroll(name, pcm16) }
                    .getOrElse { EnrollOutcome.rejected(EnrollOutcome.REASON_VOICEPRINT_FAILED) }
            try {
                state.results.send(RelayOutput.EnrollResponse(ok = outcome.ok, reason = outcome.reason))
            } catch (closed: ClosedSendChannelException) {
                return
            }
        }

        override fun results(sessionId: String): Flow<RelayOutput> = registry.find(sessionId)?.results?.receiveAsFlow() ?: emptyFlow()

        override suspend fun closeSession(sessionId: String) {
            // remove FIRST: a concurrent push then sees "closed" and is a no-op, so no
            // frame can be enqueued into a queue that is about to be closed.
            val state = registry.remove(sessionId) ?: return
            state.queue.close()
            state.pump.join()
            state.results.close()
            speechPort.close(sessionId)
        }

        override fun stats(): RelayStats = stats.snapshot(registry.size())

        private suspend fun audioPump(
            sessionId: String,
            queue: RelayQueue,
            results: Channel<RelayOutput>,
        ) {
            for (pcm in queue.audio) {
                val utterance = speechPort.transcribe(sessionId, pcm)
                if (utterance != null) {
                    stats.onTranscript()
                    val result =
                        RelayResult(
                            text = utterance.text,
                            // An unmatched speaker is the EMPTY label, never an invented name.
                            speakerLabel = utterance.speakerName ?: "",
                            ptsMs = 0L,
                        )
                    results.send(RelayOutput.Speech(result))
                }
            }
        }

        private suspend fun videoPump(
            sessionId: String,
            queue: RelayQueue,
        ) {
            for (nal in queue.video) {
                // Opaque by contract: the exact bytes are handed on, never decoded.
                frameSink.acceptVideo(sessionId, nal)
            }
        }

        private suspend fun imagePump(
            sessionId: String,
            queue: RelayQueue,
            results: Channel<RelayOutput>,
        ) {
            for (image in queue.image) {
                // JPEG decoding happens ONLY inside the gesture adapter, never here.
                val outcome = gesturePort.recognize(image.jpeg) ?: continue
                // Gesture text to the glasses; TTS audio to the necklace. Both roles
                // fall back to the sender when no counterpart has bound yet.
                deliver(
                    targetSessionId = registry.sessionFor(PeerRole.GLASS) ?: sessionId,
                    output =
                        RelayOutput.Gesture(
                            text = outcome.text,
                            label = outcome.label,
                            confidence = outcome.confidence,
                            ptsMs = image.ptsMs,
                        ),
                )
                val pcm = ttsPort.synthesize(outcome.text)
                if (pcm.isNotEmpty()) {
                    deliver(
                        targetSessionId = registry.sessionFor(PeerRole.DAEMON) ?: sessionId,
                        output = RelayOutput.TtsAudio(pcm16 = pcm, ptsMs = image.ptsMs),
                    )
                }
            }
        }

        /**
         * Send [output] onto [targetSessionId]'s results channel, or a no-op when the
         * target closed first. Unlike the media queues, the results channel is
         * UNLIMITED, so the send never parks.
         */
        private suspend fun deliver(
            targetSessionId: String,
            output: RelayOutput,
        ) {
            val target = registry.find(targetSessionId) ?: return
            try {
                target.results.send(output)
            } catch (closed: ClosedSendChannelException) {
                return
            }
        }

        private fun newSessionId(): String {
            val bytes = ByteArray(16).also { random.nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        companion object {
            /** Audio buffer depth in frames. Generous: audio must never be dropped. */
            const val DEFAULT_AUDIO_CAPACITY: Int = 64

            /** Video buffer depth in NALs. Beyond this the oldest frame is evicted. */
            const val DEFAULT_VIDEO_CAPACITY: Int = 256

            /** Image buffer depth in stills. Generous: a still is never dropped silently. */
            const val DEFAULT_IMAGE_CAPACITY: Int = 32

            /**
             * Test seam: builds the relay with explicit capacities and scope so the
             * queue policies are deterministic. Production uses the `@Inject`
             * constructor, which always applies the defaults above.
             */
            fun forTesting(
                speechPort: SpeechPort,
                gesturePort: GesturePort,
                ttsPort: TtsPort,
                enrollmentStore: EnrollmentStore,
                frameSink: FrameSink,
                audioCapacity: Int = DEFAULT_AUDIO_CAPACITY,
                videoCapacity: Int = DEFAULT_VIDEO_CAPACITY,
                imageCapacity: Int = DEFAULT_IMAGE_CAPACITY,
                scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ): StreamServiceImpl =
                StreamServiceImpl(
                    speechPort = speechPort,
                    gesturePort = gesturePort,
                    ttsPort = ttsPort,
                    enrollmentStore = enrollmentStore,
                    frameSink = frameSink,
                    audioCapacity = audioCapacity,
                    videoCapacity = videoCapacity,
                    imageCapacity = imageCapacity,
                    scope = scope,
                )
        }
    }
