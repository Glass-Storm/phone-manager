package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContext
import com.glassstorm.phonemanager.domain.service.StreamService
import com.glassstorm.phonemanager.service.security.AuthInterceptor
import ecosys.v1.StreamFrame
import ecosys.v1.StreamServiceGrpcKt
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bidirectional media relay RPC.
 *
 * Consumes inbound [StreamFrame]s, routes audio to the relay queue and video to
 * its own queue (both opaque), and emits result frames back on the same stream.
 * Token enforcement already happened in [AuthInterceptor].
 *
 * The session is bound to the device the TOKEN proved, never to an unvalidated
 * id inside a frame.
 *
 * ## Teardown
 *
 * The session is closed on EVERY exit — normal completion of the inbound flow, a
 * peer cancel, or a downstream failure — via a pair of nested `finally` blocks
 * run under [NonCancellable]. Closing the session closes the results channel,
 * which completes the results collector, so no coroutine is left behind.
 */
class StreamGrpcService(
    ctx: Context,
) : StreamServiceGrpcKt.StreamServiceCoroutineImplBase() {
    private val stream: StreamService = FromContext<StreamService>(ctx)

    override fun openStream(requests: Flow<StreamFrame>): Flow<StreamFrame> =
        channelFlow {
            // Never trust a frame field for identity: use the token-proved device id.
            val deviceId =
                AuthInterceptor.deviceIdKey.get()
                    ?: throw StatusException(Status.UNAUTHENTICATED.withDescription("missing bearer token"))
            val session = stream.openSession(deviceId)
            try {
                coroutineScope {
                    // Relayed utterances are forwarded as they are produced.
                    launch {
                        stream.results(session.sessionId).collect { result ->
                            send(transcriptFrame(result.text))
                        }
                    }
                    try {
                        requests.collect { frame -> dispatch(session.sessionId, frame) }
                    } finally {
                        // Closes the results channel -> the launched collector completes.
                        withContext(NonCancellable) { stream.closeSession(session.sessionId) }
                    }
                }
            } finally {
                // Idempotent safety net for the cancel/error paths that skip the inner
                // finally (e.g. cancellation while collecting the inbound flow).
                withContext(NonCancellable) { stream.closeSession(session.sessionId) }
            }
        }

    private suspend fun dispatch(
        sessionId: String,
        frame: StreamFrame,
    ) {
        when (frame.payloadCase) {
            StreamFrame.PayloadCase.AUDIO_PCM16_16K ->
                stream.pushAudio(
                    sessionId = sessionId,
                    audioPcm16 = frame.audioPcm1616K.toByteArray(),
                    sampleRateHz = StreamService.AUDIO_SAMPLE_RATE_HZ,
                )

            StreamFrame.PayloadCase.VIDEO_H264_NAL ->
                stream.pushVideo(sessionId, frame.videoH264Nal.toByteArray())

            // Transcript/result frames are hub->peer output; a peer echoing them
            // changes nothing, so they are accepted and ignored.
            StreamFrame.PayloadCase.TRANSCRIPT,
            StreamFrame.PayloadCase.RESULT,
            StreamFrame.PayloadCase.PAYLOAD_NOT_SET,
            null,
            -> Unit
        }
    }

    private fun transcriptFrame(text: String): StreamFrame =
        StreamFrame
            .newBuilder()
            .setTranscript(text)
            .build()
}
