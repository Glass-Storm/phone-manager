package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.core.domain.context.Context
import com.glassstorm.phonemanager.core.domain.context.fromContext
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.model.RelayResult
import com.glassstorm.phonemanager.core.model.RelaySession
import com.glassstorm.phonemanager.core.model.RelayStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Audio/video relay use-cases with bounded queues and deterministic teardown.
 *
 * [SttPort] and [FrameSink] are CONSTRUCTOR dependencies, so any engine (offline
 * mock, cloud provider, recorder) can be composed in without `:service` knowing
 * about it.
 *
 * ## Architecture
 *
 * Each session owns a [RelayQueue] and TWO independent pump loops on [scope]
 * (one audio, one video — no `select`, so a slow STT engine can never starve the
 * video sink or vice versa):
 *
 *  * the audio pump recognizes frames and emits [RelayResult]s on an UNLIMITED
 *    result channel;
 *  * the video pump hands NALs to the [FrameSink] byte-for-byte unchanged.
 *
 * [closeSession] DRAINS rather than discards: it closes the queue, joins the
 * pumps (so already-queued frames still reach STT/sink), closes the result
 * channel, then releases the STT session exactly once. It never cancels.
 *
 * Counters are [AtomicLong] because the pumps run on [scope], not the caller's
 * thread.
 *
 * ## Constructor shape (transitional, T15)
 *
 * The tuning parameters (`audioCapacity`, `videoCapacity`, `scope`) exist ONLY so
 * tests can drive the queue policies deterministically; production always wants
 * the defaults. Kotlin default arguments are invisible to Dagger, so they are
 * dropped from the [Inject] constructor and owned as the private constants
 * [DEFAULT_AUDIO_CAPACITY] / [DEFAULT_VIDEO_CAPACITY]; the registry-compat
 * constructor keeps them for the tests that pass an explicit scope or capacity.
 * T16 keeps the test secondary constructor when it migrates the suites.
 */
class StreamServiceImpl private constructor(
    private val sttProvider: () -> SttPort,
    private val frameSinkProvider: () -> FrameSink,
    private val audioCapacity: Int,
    private val videoCapacity: Int,
    private val scope: CoroutineScope,
) : StreamService {
    @Inject
    constructor(
        sttPort: SttPort,
        frameSink: FrameSink,
    ) : this(
        { sttPort },
        { frameSink },
        DEFAULT_AUDIO_CAPACITY,
        DEFAULT_VIDEO_CAPACITY,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** Registry-compat constructor; T16 rewrites it as a test-only seam. */
    constructor(
        ctx: Context,
        audioCapacity: Int = DEFAULT_AUDIO_CAPACITY,
        videoCapacity: Int = DEFAULT_VIDEO_CAPACITY,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) : this(
        { fromContext(ctx) },
        { fromContext(ctx) },
        audioCapacity,
        videoCapacity,
        scope,
    )

    private val random = SecureRandom()
    private val sessions: ConcurrentHashMap<String, SessionState> = ConcurrentHashMap()

    private val audioFrames = AtomicLong()
    private val videoFrames = AtomicLong()
    private val videoDropped = AtomicLong()
    private val transcripts = AtomicLong()

    override fun openSession(deviceId: String): RelaySession {
        val relay = RelaySession(sessionId = newSessionId(), deviceId = deviceId)
        val queue = RelayQueue(audioCapacity, videoCapacity)
        val results = Channel<RelayResult>(Channel.UNLIMITED)
        val sessionId = relay.sessionId
        // One parent job per session: joining it drains BOTH pumps.
        val pump =
            scope.launch {
                launch { audioPump(sessionId, queue, results) }
                launch { videoPump(sessionId, queue) }
            }
        sessions[sessionId] = SessionState(relay, queue, results, pump)
        return relay
    }

    override suspend fun pushAudio(
        sessionId: String,
        audioPcm16: ByteArray,
        sampleRateHz: Int,
    ) {
        val state = sessions[sessionId] ?: return
        try {
            // Parks while the audio queue is full — audio is never dropped.
            state.queue.admitAudio(audioPcm16)
        } catch (closed: ClosedSendChannelException) {
            // The session closed between the lookup and the send: a no-op, like an
            // unknown session. Not admitted, so it is not counted.
            return
        }
        audioFrames.incrementAndGet()
    }

    override fun pushVideo(
        sessionId: String,
        h264Nal: ByteArray,
    ) {
        val state = sessions[sessionId] ?: return
        videoFrames.incrementAndGet()
        if (state.queue.admitVideo(h264Nal)) videoDropped.incrementAndGet()
    }

    override fun results(sessionId: String): Flow<RelayResult> = sessions[sessionId]?.results?.receiveAsFlow() ?: emptyFlow()

    override suspend fun closeSession(sessionId: String) {
        // remove FIRST: a concurrent push then sees "closed" and is a no-op, so no
        // frame can be enqueued into a queue that is about to be closed.
        val state = sessions.remove(sessionId) ?: return
        state.queue.close()
        state.pump.join()
        state.results.close()
        sttProvider().close(sessionId)
    }

    override fun stats(): RelayStats =
        RelayStats(
            audioFrames = audioFrames.get(),
            videoFrames = videoFrames.get(),
            videoDropped = videoDropped.get(),
            transcripts = transcripts.get(),
            liveSessions = sessions.size,
        )

    private suspend fun audioPump(
        sessionId: String,
        queue: RelayQueue,
        results: Channel<RelayResult>,
    ) {
        for (pcm in queue.audio) {
            val text =
                sttProvider()
                    .transcribe(sessionId, pcm, StreamService.AUDIO_SAMPLE_RATE_HZ)
            if (text != null) {
                transcripts.incrementAndGet()
                results.send(RelayResult(text = text, speakerLabel = "", ptsMs = 0L))
            }
        }
    }

    private suspend fun videoPump(
        sessionId: String,
        queue: RelayQueue,
    ) {
        for (nal in queue.video) {
            // Opaque by contract: the exact bytes are handed on, never decoded.
            frameSinkProvider().acceptVideo(sessionId, nal)
        }
    }

    private fun newSessionId(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private class SessionState(
        val relay: RelaySession,
        val queue: RelayQueue,
        val results: Channel<RelayResult>,
        val pump: Job,
    )

    companion object {
        /** Audio buffer depth in frames. Generous: audio must never be dropped. */
        const val DEFAULT_AUDIO_CAPACITY: Int = 64

        /** Video buffer depth in NALs. Beyond this the oldest frame is evicted. */
        const val DEFAULT_VIDEO_CAPACITY: Int = 256
    }
}
