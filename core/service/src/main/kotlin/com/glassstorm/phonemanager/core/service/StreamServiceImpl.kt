package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.domain.adapter.enrollment.EnrollmentStore
import com.glassstorm.phonemanager.core.domain.adapter.gesture.GesturePort
import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.speech.SpeechPort
import com.glassstorm.phonemanager.core.domain.adapter.speech.TtsPort
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.glassstorm.phonemanager.core.model.LivePeer
import com.glassstorm.phonemanager.core.model.PeerRole
import com.glassstorm.phonemanager.core.model.RelayOutput
import com.glassstorm.phonemanager.core.model.RelayResult
import com.glassstorm.phonemanager.core.model.RelaySession
import com.glassstorm.phonemanager.core.model.RelayStats
import com.glassstorm.phonemanager.core.model.SpeechUtterance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
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
 * Each session owns a [RelayQueue] and FOUR independent pump loops on [scope]
 * (audio, video, image, TTS — no `select`, so a slow recognizer can never starve
 * the video sink or vice versa):
 *
 *  * the audio pump transcribes frames and emits [RelayOutput.Speech] on an
 *    UNLIMITED result channel;
 *  * the video pump hands NALs to the [FrameSink] byte-for-byte unchanged;
 *  * the image pump recognizes the still and emits the [RelayOutput.Gesture] to
 *    the GLASSES session, then hands the recognized TEXT to the TTS pump;
 *  * the TTS pump synthesizes the latest text and emits [RelayOutput.TtsAudio] to
 *    BOTH the GLASSES and the NECKLACE session (deduplicated when they are the
 *    same session), so the glasses player is live.
 *
 * Every pump guards its per-item body and its loop: an adapter throwing (native
 * engine, bitmap OOM, malformed frame) is logged and ends at most that item or
 * that pump, never the process.
 *
 * [closeSession] DRAINS rather than discards: it latches the session CLOSING
 * (rejecting new pushes) but keeps it resolvable, closes the queue and the TTS
 * handoff, joins the pumps (so already-queued frames still reach the engines/sink,
 * and the latest utterance is still synthesized and delivered to BOTH roles,
 * including this half-closed sender), flushes the trailing transcript, and only
 * then unregisters the session (rebinding the role to a surviving session) and
 * closes the result channel, releasing the speech session exactly once. It never
 * cancels.
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
            newPumpScope(),
        )

        private val random = SecureRandom()

        private val logger: Logger = Logger.getLogger(StreamServiceImpl::class.java.name)

        private val stats = RelayStatsRecorder()
        private val registry = RelaySessionRegistry()

        /**
         * The observability mirror: every [RelayOutput] any pump produces is also
         * published here, independently of the per-session results channel. It has
         * NO replay: a collector that attaches late (a fresh 即時 ViewModel) must
         * start empty instead of rendering a dead session's last result, and
         * `tryEmit` never parks a pump even when no diagnostic view is listening.
         */
        private val outputs = MutableSharedFlow<RelayOutput>(extraBufferCapacity = 64)

        override fun openSession(deviceId: String): RelaySession {
            val relay = RelaySession(sessionId = newSessionId(), deviceId = deviceId)
            val queue = RelayQueue(audioCapacity, videoCapacity, imageCapacity)
            val results = Channel<RelayOutput>(Channel.UNLIMITED)
            // Latest-wins text handoff: a burst of stills can never park the image
            // pump on synthesis, and only the newest utterance is ever spoken.
            val tts = Channel<String>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            // The still whose text is currently being synthesized, for its pts only.
            val latestImagePts = AtomicLong(0L)
            val sessionId = relay.sessionId
            // One parent job per session: joining it drains ALL pumps. The image
            // pump also signals when the still queue is drained, so closeSession can
            // close the TTS handoff only AFTER no more stills can enqueue text.
            val imageDrained = CompletableDeferred<Unit>()
            val pump =
                scope.launch {
                    launch { audioPump(sessionId, queue, results) }
                    launch { videoPump(sessionId, queue) }
                    launch { imagePump(sessionId, queue, tts, latestImagePts, imageDrained) }
                    launch { ttsPump(sessionId, tts, latestImagePts) }
                }
            registry.register(sessionId, SessionState(relay, queue, results, tts, imageDrained, pump))
            return relay
        }

        override fun bindRole(
            sessionId: String,
            role: PeerRole,
        ) {
            val state = registry.find(sessionId) ?: return
            // A CLOSING session must not gain or move its routing entry mid-drain.
            if (state.isClosing) return
            registry.bindRole(sessionId, role)
        }

        override suspend fun pushAudio(
            sessionId: String,
            audioPcm16: ByteArray,
            sampleRateHz: Int,
        ) {
            val state = registry.find(sessionId) ?: return
            if (state.isClosing) return
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
            if (state.isClosing) return
            stats.onVideoOffered(dropped = state.queue.admitVideo(h264Nal))
        }

        override suspend fun pushImage(
            sessionId: String,
            jpeg: ByteArray,
            ptsMs: Long,
        ) {
            val state = registry.find(sessionId) ?: return
            if (state.isClosing) return
            val dropped = state.queue.admitImage(QueuedImage(jpeg = jpeg, ptsMs = ptsMs))
            stats.onImageOffered(dropped = dropped)
        }

        override suspend fun pushEnroll(
            sessionId: String,
            name: String,
            pcm16: ByteArray,
            ptsMs: Long,
        ) {
            val state = registry.find(sessionId) ?: return
            if (state.isClosing) return
            val outcome =
                runCatching { enrollmentStore.enroll(name, pcm16) }
                    .getOrElse { EnrollOutcome.rejected(EnrollOutcome.REASON_VOICEPRINT_FAILED) }
            val output = RelayOutput.EnrollResponse(ok = outcome.ok, reason = outcome.reason)
            publish(output)
            try {
                state.results.send(output)
            } catch (closed: ClosedSendChannelException) {
                return
            }
        }

        override fun results(sessionId: String): Flow<RelayOutput> = registry.find(sessionId)?.results?.receiveAsFlow() ?: emptyFlow()

        override fun outputs(): Flow<RelayOutput> = outputs.asSharedFlow()

        override fun livePeers(): List<LivePeer> = registry.livePeers()

        override suspend fun closeSession(sessionId: String) {
            // Mark CLOSING but leave the session registered: a half-closed peer keeps
            // reading during the drain, so the TTS synthesized from a still that is
            // still queued at close must resolve back to this session. New pushes are
            // rejected by the CLOSING latch inside every push method.
            val state = registry.markClosing(sessionId) ?: return
            state.queue.close()
            // Drain the stills BEFORE closing the TTS handoff: the last gesture's text
            // is still enqueued by the image pump while the queue drains.
            state.imageDrained.await()
            state.tts.close()
            state.pump.join()
            // The peer may have stopped without the trailing silence that closes an
            // utterance, so flush the segmenter's remainder as one final transcript.
            flushSpeech(sessionId, state.results)
            // Only now that the late TTS was delivered is the session unreachable; the
            // role is released and the most recent surviving session is rebound.
            registry.remove(sessionId)
            state.results.close()
            speechPort.close(sessionId)
        }

        private suspend fun flushSpeech(
            sessionId: String,
            results: Channel<RelayOutput>,
        ) {
            val utterance =
                try {
                    speechPort.flush(sessionId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    logger.log(Level.WARNING, "flushing the last utterance of session $sessionId failed", failure)
                    null
                }
            emitSpeech(results, utterance)
        }

        private suspend fun emitSpeech(
            results: Channel<RelayOutput>,
            utterance: SpeechUtterance?,
        ) {
            if (utterance == null) return
            stats.onTranscript()
            val result =
                RelayResult(
                    text = utterance.text,
                    // An unmatched speaker is the EMPTY label, never an invented name.
                    speakerLabel = utterance.speakerName ?: "",
                    ptsMs = 0L,
                )
            val output = RelayOutput.Speech(result)
            publish(output)
            try {
                results.send(output)
            } catch (closed: ClosedSendChannelException) {
                // The closing session's channel can already be closed; the output is
                // still mirrored on the shared diagnostics flow.
            }
        }

        override fun stats(): RelayStats = stats.snapshot(registry.size())

        private suspend fun audioPump(
            sessionId: String,
            queue: RelayQueue,
            results: Channel<RelayOutput>,
        ) {
            try {
                for (pcm in queue.audio) {
                    guarded("audio") {
                        emitSpeech(results, speechPort.transcribe(sessionId, pcm))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Throwable) {
                logger.log(Level.SEVERE, "audio pump died for session $sessionId", fatal)
            }
        }

        private suspend fun videoPump(
            sessionId: String,
            queue: RelayQueue,
        ) {
            try {
                for (nal in queue.video) {
                    guarded("video") {
                        // Opaque by contract: the exact bytes are handed on, never decoded.
                        frameSink.acceptVideo(sessionId, nal)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Throwable) {
                logger.log(Level.SEVERE, "video pump died for session $sessionId", fatal)
            }
        }

        private suspend fun imagePump(
            sessionId: String,
            queue: RelayQueue,
            tts: Channel<String>,
            latestImagePts: AtomicLong,
            imageDrained: CompletableDeferred<Unit>,
        ) {
            try {
                for (image in queue.image) {
                    guarded("image") {
                        // JPEG decoding happens ONLY inside the gesture adapter, never here.
                        val outcome = gesturePort.recognize(image.jpeg)
                        if (outcome != null) {
                            stats.onGesture()
                            // Gesture text to the glasses; the role falls back to the
                            // sender when no counterpart has bound yet.
                            deliver(
                                output =
                                    RelayOutput.Gesture(
                                        text = outcome.text,
                                        label = outcome.label,
                                        confidence = outcome.confidence,
                                        ptsMs = image.ptsMs,
                                    ),
                                targetSessionIds = arrayOf(registry.sessionFor(PeerRole.GLASS) ?: sessionId),
                            )
                            latestImagePts.set(image.ptsMs)
                            tts.trySend(outcome.text)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Throwable) {
                logger.log(Level.SEVERE, "image pump died for session $sessionId", fatal)
            } finally {
                imageDrained.complete(Unit)
            }
        }

        private suspend fun ttsPump(
            sessionId: String,
            tts: Channel<String>,
            latestImagePts: AtomicLong,
        ) {
            try {
                for (text in tts) {
                    guarded("tts") {
                        val pcm = ttsPort.synthesize(text)
                        if (pcm.isNotEmpty()) {
                            // The deck's scenario 3: the glasses PLAY the audio, and the
                            // daemon still receives it. Dedupe when both roles resolve to
                            // the same session (the sender fallback).
                            deliver(
                                output = RelayOutput.TtsAudio(pcm16 = pcm, ptsMs = latestImagePts.get()),
                                targetSessionIds =
                                    arrayOf(
                                        registry.sessionFor(PeerRole.GLASS) ?: sessionId,
                                        registry.sessionFor(PeerRole.DAEMON) ?: sessionId,
                                    ),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Throwable) {
                logger.log(Level.SEVERE, "tts pump died for session $sessionId", fatal)
            }
        }

        /**
         * Run one pump item, containing ANY adapter failure at this item.
         * Cancellation is the one throwable that must propagate (teardown).
         */
        private suspend fun guarded(
            pump: String,
            block: suspend () -> Unit,
        ) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                logger.log(Level.WARNING, "$pump pump item failed", failure)
            }
        }

        /**
         * Send [output] onto every target's results channel, or a no-op when a
         * target closed first. Duplicate targets (both roles resolving to the
         * sender) are delivered once. The results channel is UNLIMITED, so the
         * send never parks.
         */
        private suspend fun deliver(
            output: RelayOutput,
            targetSessionIds: Array<String>,
        ) {
            publish(output)
            for (targetSessionId in targetSessionIds.toSet()) {
                val target = registry.find(targetSessionId) ?: continue
                try {
                    target.results.send(output)
                } catch (closed: ClosedSendChannelException) {
                    continue
                }
            }
        }

        private fun publish(output: RelayOutput) {
            outputs.tryEmit(output)
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

            /** Image buffer depth in stills. Beyond this the oldest still is evicted. */
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
                scope: CoroutineScope = newPumpScope(),
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

            /**
             * The production pump scope: a [SupervisorJob] keeps one pump's failure
             * from cancelling its siblings, and the handler keeps any escaped
             * throwable off the process default handler (which would kill the app).
             */
            private fun newPumpScope(): CoroutineScope =
                CoroutineScope(
                    SupervisorJob() +
                        Dispatchers.Default +
                        CoroutineExceptionHandler { _, fatal ->
                            Logger
                                .getLogger(StreamServiceImpl::class.java.name)
                                .log(Level.SEVERE, "relay pump died", fatal)
                        },
                )
        }
    }
