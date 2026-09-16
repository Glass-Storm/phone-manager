package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContext
import com.glassstorm.phonemanager.domain.service.StreamService
import com.glassstorm.phonemanager.service.security.AuthInterceptor
import ecosys.v1.StreamFrame
import ecosys.v1.StreamServiceGrpcKt
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The bidirectional media relay RPC.
 *
 * Consumes inbound [StreamFrame]s, routes audio to the STT port and video to the
 * frame sink (both opaque), and emits result frames back on the same stream.
 * Token enforcement already happened in [AuthInterceptor].
 *
 * The session is bound to the device the TOKEN proved, never to an unvalidated
 * id inside a frame. This is the simple-correct core; T14 adds backpressure,
 * drop-oldest video policy, and disconnect cleanup.
 */
class StreamGrpcService(GoCtx: Context) : StreamServiceGrpcKt.StreamServiceCoroutineImplBase() {

    private val GoStream: StreamService = FromContext<StreamService>(GoCtx)

    override fun openStream(requests: Flow<StreamFrame>): Flow<StreamFrame> = flow {
        val GoDeviceId = AuthInterceptor.GoDeviceIdKey.get()
            ?: throw StatusException(Status.UNAUTHENTICATED.withDescription("missing bearer token"))
        val GoSession = GoStream.GoOpenSession(GoDeviceId)
        try {
            requests.collect { GoFrame ->
                when (GoFrame.payloadCase) {
                    StreamFrame.PayloadCase.AUDIO_PCM16_16K -> {
                        val GoResult = GoStream.GoPushAudio(
                            sessionId = GoSession.GoSessionId,
                            audioPcm16 = GoFrame.audioPcm1616K.toByteArray(),
                            sampleRateHz = StreamService.GoAudioSampleRateHz,
                        )
                        if (GoResult != null) emit(GoTranscriptFrame(GoResult.GoText))
                    }

                    StreamFrame.PayloadCase.VIDEO_H264_NAL ->
                        GoStream.GoPushVideo(GoSession.GoSessionId, GoFrame.videoH264Nal.toByteArray())

                    // Transcript/result frames are hub->peer output; a peer echoing
                    // them changes nothing, so they are accepted and ignored.
                    StreamFrame.PayloadCase.TRANSCRIPT,
                    StreamFrame.PayloadCase.RESULT,
                    StreamFrame.PayloadCase.PAYLOAD_NOT_SET,
                    null,
                    -> Unit
                }
            }
        } finally {
            GoStream.GoCloseSession(GoSession.GoSessionId)
        }
    }

    private fun GoTranscriptFrame(text: String): StreamFrame = StreamFrame.newBuilder()
        .setTranscript(text)
        .build()
}
