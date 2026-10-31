package com.glassstorm.phonemanager.transport.grpc

import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.model.PeerRole
import com.glassstorm.phonemanager.core.model.RelayOutput
import com.glassstorm.phonemanager.core.model.RelaySession
import com.google.protobuf.ByteString
import ecosys.v1.DeviceRole
import ecosys.v1.EnrollResponse
import ecosys.v1.GestureResult
import ecosys.v1.StreamFrame
import ecosys.v1.StreamResult
import ecosys.v1.StreamServiceGrpcKt
import ecosys.v1.TtsAudio
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bidirectional media relay RPC.
 *
 * Consumes inbound [StreamFrame]s and routes them to the relay use-case: audio to
 * transcription, video to the opaque sink, image to gesture recognition, and an
 * enrollment request to the hub-owned directory. The relay's own outputs
 * ([RelayOutput]) are mapped back onto result frames on the SAME or the
 * counterpart peer's stream. The stream is UNAUTHENTICATED by design: pairing,
 * PIN and bearer tokens were removed from the hub runtime.
 *
 * ## Session identity
 *
 * Identity comes from the explicit `stream_open` bootstrap frame (field 10): the
 * FIRST frame may declare `{device_id, role}`, which binds the session to that
 * role so the registry can route necklace images to the glasses. A peer that
 * sends media before any `stream_open` still gets a session (opened under
 * [UNKNOWN_DEVICE_ID]) and only its role binding is skipped. No frame is ever
 * refused for lack of a credential.
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
            var session: RelaySession? = null
            try {
                coroutineScope {
                    try {
                        requests.collect { frame ->
                            val active =
                                session ?: bindSession(frame).also { opened ->
                                    session = opened
                                    // Relayed outputs are forwarded as they are produced.
                                    launch {
                                        stream.results(opened.sessionId).collect { output ->
                                            send(outputFrame(output))
                                        }
                                    }
                                }
                            dispatch(active.sessionId, frame)
                        }
                    } finally {
                        // Closes the results channel -> the launched collector completes,
                        // so the coroutineScope below can return.
                        withContext(NonCancellable) { session?.let { stream.closeSession(it.sessionId) } }
                    }
                }
            } finally {
                // Idempotent safety net for the cancel/error paths that skip the inner
                // finally (e.g. cancellation while collecting the inbound flow).
                withContext(NonCancellable) { session?.let { stream.closeSession(it.sessionId) } }
            }
        }

    /**
     * Resolve the session identity from the bootstrap frame (no credential).
     *
     * A `stream_open` declares `{device_id, role}`; an empty device id falls back
     * to [UNKNOWN_DEVICE_ID]. Any other first frame still opens a session under
     * [UNKNOWN_DEVICE_ID], so the stream is never rejected for identity reasons.
     */
    private fun bindSession(frame: StreamFrame): RelaySession {
        val deviceId =
            if (frame.payloadCase == StreamFrame.PayloadCase.STREAM_OPEN) {
                frame.streamOpen.deviceId.ifEmpty { UNKNOWN_DEVICE_ID }
            } else {
                UNKNOWN_DEVICE_ID
            }
        val session = stream.openSession(deviceId)
        if (frame.payloadCase == StreamFrame.PayloadCase.STREAM_OPEN) {
            stream.bindRole(session.sessionId, frame.streamOpen.role.toPeerRole())
        }
        return session
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

            StreamFrame.PayloadCase.IMAGE ->
                stream.pushImage(
                    sessionId = sessionId,
                    jpeg = frame.image.jpeg.toByteArray(),
                    ptsMs = frame.image.ptsMs,
                )

            StreamFrame.PayloadCase.ENROLL_REQUEST ->
                stream.pushEnroll(
                    sessionId = sessionId,
                    name = frame.enrollRequest.name,
                    pcm16 = frame.enrollRequest.audioPcm1616K.toByteArray(),
                    ptsMs = frame.enrollRequest.ptsMs,
                )

            // A late `stream_open` re-binds the role; idempotent.
            StreamFrame.PayloadCase.STREAM_OPEN ->
                stream.bindRole(sessionId, frame.streamOpen.role.toPeerRole())

            // Hub->peer output and diagnostics. A peer echoing one of these back
            // changes nothing, so they are accepted and ignored.
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

    private fun outputFrame(output: RelayOutput): StreamFrame =
        when (output) {
            is RelayOutput.Speech ->
                StreamFrame
                    .newBuilder()
                    .setResult(
                        StreamResult
                            .newBuilder()
                            .setText(output.result.text)
                            .setSpeakerLabel(output.result.speakerLabel)
                            .setPtsMs(output.result.ptsMs),
                    ).build()

            is RelayOutput.Gesture ->
                StreamFrame
                    .newBuilder()
                    .setGesture(
                        GestureResult
                            .newBuilder()
                            .setText(output.text)
                            .setLabel(output.label)
                            .setConfidence(output.confidence)
                            .setPtsMs(output.ptsMs),
                    ).build()

            is RelayOutput.TtsAudio ->
                StreamFrame
                    .newBuilder()
                    .setTtsAudio(
                        TtsAudio
                            .newBuilder()
                            .setPcm1616K(ByteString.copyFrom(output.pcm16))
                            .setPtsMs(output.ptsMs),
                    ).build()

            is RelayOutput.EnrollResponse ->
                StreamFrame
                    .newBuilder()
                    .setEnrollResponse(
                        EnrollResponse
                            .newBuilder()
                            .setOk(output.ok)
                            .apply { output.reason?.let { setReason(it) } },
                    ).build()
        }

    private fun DeviceRole.toPeerRole(): PeerRole =
        when (this) {
            DeviceRole.DEVICE_ROLE_GLASS -> PeerRole.GLASS
            DeviceRole.DEVICE_ROLE_DAEMON -> PeerRole.DAEMON
            else -> PeerRole.UNSPECIFIED
        }

    private companion object {
        /** Device id for a `stream_open` that declares an empty id and has no token. */
        const val UNKNOWN_DEVICE_ID: String = "unknown-device"
    }
}
