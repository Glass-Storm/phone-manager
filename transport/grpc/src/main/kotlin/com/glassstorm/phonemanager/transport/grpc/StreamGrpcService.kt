package com.glassstorm.phonemanager.transport.grpc

import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.transport.grpc.security.AuthInterceptor
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
 * [StreamService] is a CONSTRUCTOR dependency supplied by `TransportModule` at
 * the composition root.
 *
 * ## Teardown
 *
 * The session is closed on EVERY exit — normal completion of the inbound flow, a
 * peer cancel, or a downstream failure — via a pair of nested `finally` blocks
 * run under [NonCancellable]. Closing the session closes the results channel,
 * which completes the results collector, so no coroutine is left behind.
 */
class StreamGrpcService(
    private val stream: StreamService,
) : StreamServiceGrpcKt.StreamServiceCoroutineImplBase() {
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

            // TODO(T8): the real relay routing lands here. An inbound `image` is
            // decoded and recognised by the gesture port, and an inbound
            // `enroll_request` is folded into the enrollment store; both produce
            // output on OTHER sessions resolved from the `stream_open` binding.
            // Until T8 exists, these frames are received without throwing so a
            // new frame type never aborts an in-flight session.
            StreamFrame.PayloadCase.IMAGE,
            StreamFrame.PayloadCase.ENROLL_REQUEST,
            StreamFrame.PayloadCase.STREAM_OPEN,
            -> Unit

            // Hub->peer output and diagnostics. A peer echoing one of these back
            // changes nothing, exactly like `transcript`/`result`, so they are
            // accepted and ignored. TODO(T8) is what actually emits them.
            StreamFrame.PayloadCase.GESTURE,
            StreamFrame.PayloadCase.TTS_AUDIO,
            StreamFrame.PayloadCase.ENROLL_RESPONSE,
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
