package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContext
import com.glassstorm.phonemanager.domain.dto.RelayResult
import com.glassstorm.phonemanager.domain.dto.RelaySession
import com.glassstorm.phonemanager.domain.dto.RelayStats
import com.glassstorm.phonemanager.domain.service.StreamService
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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

/**
 * Audio/video relay use-cases with bounded queues and deterministic teardown.
 *
 * Resolves its [SttPort] and [FrameSink] collaborators from the Context registry
 * by their domain INTERFACE types, so any engine (offline mock, cloud provider,
 * recorder) can be composed in without `:service` knowing about it.
 *
 * ## Architecture
 *
 * Each session owns a [RelayQueue] and TWO independent pump loops on [GoScope]
 * (one audio, one video — no `select`, so a slow STT engine can never starve the
 * video sink or vice versa):
 *
 *  * the audio pump recognizes frames and emits [RelayResult]s on an UNLIMITED
 *    result channel;
 *  * the video pump hands NALs to the [FrameSink] byte-for-byte unchanged.
 *
 * [GoCloseSession] DRAINS rather than discards: it closes the queue, joins the
 * pumps (so already-queued frames still reach STT/sink), closes the result
 * channel, then releases the STT session exactly once. It never cancels.
 *
 * Counters are [AtomicLong] because the pumps run on [GoScope], not the caller's
 * thread.
 */
class StreamServiceImpl(
    private val GoCtx: Context,
    private val GoAudioCapacity: Int = GO_DEFAULT_AUDIO_CAPACITY,
    private val GoVideoCapacity: Int = GO_DEFAULT_VIDEO_CAPACITY,
    private val GoScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : StreamService {

    private val GoRandom = SecureRandom()
    private val GoSessions: ConcurrentHashMap<String, GoSessionState> = ConcurrentHashMap()

    private val GoAudioFrames = AtomicLong()
    private val GoVideoFrames = AtomicLong()
    private val GoVideoDropped = AtomicLong()
    private val GoTranscripts = AtomicLong()

    override fun GoOpenSession(deviceId: String): RelaySession {
        val GoRelay = RelaySession(GoSessionId = GoNewSessionId(), GoDeviceId = deviceId)
        val GoQueue = RelayQueue(GoAudioCapacity, GoVideoCapacity)
        val GoResults = Channel<RelayResult>(Channel.UNLIMITED)
        val GoSessionId = GoRelay.GoSessionId
        // One parent job per session: joining it drains BOTH pumps.
        val GoPump = GoScope.launch {
            launch { GoAudioPump(GoSessionId, GoQueue, GoResults) }
            launch { GoVideoPump(GoSessionId, GoQueue) }
        }
        GoSessions[GoSessionId] = GoSessionState(GoRelay, GoQueue, GoResults, GoPump)
        return GoRelay
    }

    override suspend fun GoPushAudio(sessionId: String, audioPcm16: ByteArray, sampleRateHz: Int) {
        val GoState = GoSessions[sessionId] ?: return
        try {
            // Parks while the audio queue is full — audio is never dropped.
            GoState.GoQueue.GoAdmitAudio(audioPcm16)
        } catch (GoClosed: ClosedSendChannelException) {
            // The session closed between the lookup and the send: a no-op, like an
            // unknown session. Not admitted, so it is not counted.
            return
        }
        GoAudioFrames.incrementAndGet()
    }

    override fun GoPushVideo(sessionId: String, h264Nal: ByteArray) {
        val GoState = GoSessions[sessionId] ?: return
        GoVideoFrames.incrementAndGet()
        if (GoState.GoQueue.GoAdmitVideo(h264Nal)) GoVideoDropped.incrementAndGet()
    }

    override fun GoResults(sessionId: String): Flow<RelayResult> =
        GoSessions[sessionId]?.GoResults?.receiveAsFlow() ?: emptyFlow()

    override suspend fun GoCloseSession(sessionId: String) {
        // remove FIRST: a concurrent push then sees "closed" and is a no-op, so no
        // frame can be enqueued into a queue that is about to be closed.
        val GoState = GoSessions.remove(sessionId) ?: return
        GoState.GoQueue.GoClose()
        GoState.GoPump.join()
        GoState.GoResults.close()
        FromContext<SttPort>(GoCtx).GoClose(sessionId)
    }

    override fun GoStats(): RelayStats = RelayStats(
        GoAudioFrames = GoAudioFrames.get(),
        GoVideoFrames = GoVideoFrames.get(),
        GoVideoDropped = GoVideoDropped.get(),
        GoTranscripts = GoTranscripts.get(),
        GoLiveSessions = GoSessions.size,
    )

    private suspend fun GoAudioPump(
        sessionId: String,
        queue: RelayQueue,
        results: Channel<RelayResult>,
    ) {
        for (GoPcm in queue.GoAudio) {
            val GoText = FromContext<SttPort>(GoCtx)
                .GoTranscribe(sessionId, GoPcm, StreamService.GoAudioSampleRateHz)
            if (GoText != null) {
                GoTranscripts.incrementAndGet()
                results.send(RelayResult(GoText = GoText, GoSpeakerLabel = "", GoPtsMs = 0L))
            }
        }
    }

    private suspend fun GoVideoPump(sessionId: String, queue: RelayQueue) {
        for (GoNal in queue.GoVideo) {
            // Opaque by contract: the exact bytes are handed on, never decoded.
            FromContext<FrameSink>(GoCtx).GoAcceptVideo(sessionId, GoNal)
        }
    }

    private fun GoNewSessionId(): String {
        val GoBytes = ByteArray(16).also { GoRandom.nextBytes(it) }
        return GoBytes.joinToString("") { "%02x".format(it) }
    }

    private class GoSessionState(
        val GoRelay: RelaySession,
        val GoQueue: RelayQueue,
        val GoResults: Channel<RelayResult>,
        val GoPump: Job,
    )

    private companion object {
        /** Audio buffer depth in frames. Generous: audio must never be dropped. */
        const val GO_DEFAULT_AUDIO_CAPACITY: Int = 64

        /** Video buffer depth in NALs. Beyond this the oldest frame is evicted. */
        const val GO_DEFAULT_VIDEO_CAPACITY: Int = 256
    }
}
