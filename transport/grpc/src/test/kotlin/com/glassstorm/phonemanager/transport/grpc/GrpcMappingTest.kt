package com.glassstorm.phonemanager.transport.grpc

import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.model.PeerRole
import com.glassstorm.phonemanager.core.model.RelayOutput
import com.glassstorm.phonemanager.core.model.RelayResult
import com.glassstorm.phonemanager.core.model.RelaySession
import com.glassstorm.phonemanager.core.model.RelayStats
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import ecosys.v1.DeviceRole
import ecosys.v1.EnrollRequest
import ecosys.v1.EnrollResponse
import ecosys.v1.GestureResult
import ecosys.v1.ImageFrame
import ecosys.v1.StreamFrame
import ecosys.v1.StreamKind
import ecosys.v1.StreamOpenRequest
import ecosys.v1.StreamResult
import ecosys.v1.StreamServiceGrpcKt
import ecosys.v1.TtsAudio
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The FOCUSED DTO <-> proto MAPPING suite for `:transport:grpc`.
 *
 * The relay suites prove behaviour; this one pins the CONVERSION at the boundary,
 * because that is the whole reason the contract is isolated to this module (D3/D4):
 * a proto change does not auto-propagate into the domain's DTOs, so the mapping
 * must be confronted by a test.
 *
 * Everything runs over an in-process gRPC server so the REAL mapping code runs
 * against the REAL generated `ecosys.v1` types, with only the domain-side
 * collaborators faked (exactly as a composition would supply real ones). The
 * stream is UNAUTHENTICATED; pairing/token mapping was removed with the runtime.
 *
 * ## What is pinned
 *
 *  * a `RelayOutput.Speech` -> a `result` [StreamFrame] carrying text + speaker;
 *  * audio (and video) payloads pass through to the relay OPAQUELY, byte for byte;
 *  * each additive payload variant (`image`, `gesture`, `tts_audio`,
 *    `enroll_request`, `enroll_response`, `stream_open`) round-trips, and fields
 *    1-4 keep their wire numbers so the frozen contract stays additive.
 *
 * Deliberately pinned: the bare `transcript` variant (field 3) is the frozen v1
 * shape and is NOT what the hub emits now. Speaker-tagged subtitles travel on the
 * richer `result` variant, so this test asserts `transcript` is NOT selected —
 * making any future, un-negotiated regression fail here.
 */
class GrpcMappingTest {
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var stream: RecordingStreamService

    @Before
    fun startServer() {
        stream = RecordingStreamService()

        val name = InProcessServerBuilder.generateName()
        server =
            InProcessServerBuilder
                .forName(name)
                .directExecutor()
                .addService(StreamGrpcService(stream))
                .build()
                .start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
    }

    @After
    fun stopServer() {
        channel.shutdownNow()
        server.shutdownNow()
        channel.awaitTermination(5, TimeUnit.SECONDS)
        server.awaitTermination(5, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------ Stream

    @Test
    fun `a relay speech result maps to a stream result carrying the text and speaker`() {
        // Given a paired peer and a domain utterance to relay
        val audio = audioFrame(ByteArray(8) { 3 })
        stream.result =
            RelayOutput.Speech(RelayResult(text = "mapped utterance", speakerLabel = "Speaker 1", ptsMs = 4_242L))

        // When one audio frame is streamed
        val responses = streamOnce(audio)

        // Then the domain result became a `result` frame with the SAME text + speaker
        assertThat(responses).hasSize(1)
        val frame = responses.single()
        assertThat(frame.payloadCase).isEqualTo(StreamFrame.PayloadCase.RESULT)
        assertThat(frame.result.text).isEqualTo("mapped utterance")
        assertThat(frame.result.speakerLabel).isEqualTo("Speaker 1")
        assertThat(frame.result.ptsMs).isEqualTo(4_242L)

        // And the frozen bare `transcript` variant is NOT selected: subtitles now
        // carry a speaker label, so the richer `result` variant is the one used.
        assertThat(frame.hasTranscript()).isFalse()
    }

    @Test
    fun `audio bytes pass through to the relay opaquely and unchanged`() {
        // Given a non-trivial opaque PCM16 payload
        val bytes = ByteArray(320) { ((it * 7) % 256).toByte() }

        // When it is streamed
        streamOnce(audioFrame(bytes))

        // Then the relay received the EXACT bytes and the frozen sample rate
        assertThat(stream.audio).hasSize(1)
        assertThat(stream.audio.single().contentEquals(bytes)).isTrue()
        assertThat(stream.audio.single()).isEqualTo(bytes)
        assertThat(stream.sampleRates).containsExactly(StreamService.AUDIO_SAMPLE_RATE_HZ)
    }

    @Test
    fun `video bytes pass through to the relay opaquely and unchanged`() {
        // Given a non-trivial opaque H.264 NAL
        val bytes = ByteArray(48) { ((it * 13) % 251).toByte() }

        // When it is streamed
        streamOnce(videoFrame(bytes))

        // Then the relay received the EXACT bytes
        assertThat(stream.video).hasSize(1)
        assertThat(stream.video.single()).isEqualTo(bytes)
    }

    @Test
    fun `the StreamResult variant carries the pts and speaker the transcript variant omits`() {
        // This pins the SHAPE of the two result-carrying variants so the transcript
        // choice above cannot silently migrate. `StreamResult` is the richer v1
        // variant; the hub maps `RelayResult` to the bare `transcript` one instead.
        val result =
            StreamResult
                .newBuilder()
                .setText("rich result")
                .setSpeakerLabel("Speaker 1")
                .setPtsMs(4_242L)
                .build()
        val frame = StreamFrame.newBuilder().setResult(result).build()

        // Then the richer variant retains everything, and selects a DIFFERENT case
        assertThat(frame.payloadCase).isEqualTo(StreamFrame.PayloadCase.RESULT)
        assertThat(frame.result.ptsMs).isEqualTo(4_242L)
        assertThat(frame.result.speakerLabel).isEqualTo("Speaker 1")
    }

    // ------------------------------------------------- Additive payloads (v3)

    @Test
    fun `every additive payload variant round trips through serialization`() {
        // Given one frame per additive oneof member, each with a non-trivial value
        val image =
            StreamFrame
                .newBuilder()
                .setImage(
                    ImageFrame
                        .newBuilder()
                        .setJpeg(ByteString.copyFrom(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
                        .setPtsMs(90L)
                        .setWidth(320)
                        .setHeight(240)
                        .build(),
                ).build()
        val gesture =
            StreamFrame
                .newBuilder()
                .setGesture(
                    GestureResult
                        .newBuilder()
                        .setText("thumbs up")
                        .setLabel("Thumb_Up")
                        .setConfidence(0.75f)
                        .setPtsMs(91L)
                        .build(),
                ).build()
        val tts =
            StreamFrame
                .newBuilder()
                .setTtsAudio(
                    TtsAudio
                        .newBuilder()
                        .setPcm1616K(ByteString.copyFrom(byteArrayOf(3, 4)))
                        .setPtsMs(92L)
                        .build(),
                ).build()
        val enrollRequest =
            StreamFrame
                .newBuilder()
                .setEnrollRequest(
                    EnrollRequest
                        .newBuilder()
                        .setName("Alice")
                        .setAudioPcm1616K(ByteString.copyFrom(byteArrayOf(5, 6)))
                        .setPtsMs(93L)
                        .build(),
                ).build()
        val enrollResponse =
            StreamFrame
                .newBuilder()
                .setEnrollResponse(EnrollResponse.newBuilder().setOk(true).build())
                .build()
        val streamOpen =
            StreamFrame
                .newBuilder()
                .setStreamOpen(
                    StreamOpenRequest
                        .newBuilder()
                        .setDeviceId("necklace")
                        .setKind(StreamKind.STREAM_KIND_VIDEO)
                        .setRole(DeviceRole.DEVICE_ROLE_DAEMON)
                        .build(),
                ).build()

        // When each is serialized and parsed back
        listOf(
            StreamFrame.PayloadCase.IMAGE to image,
            StreamFrame.PayloadCase.GESTURE to gesture,
            StreamFrame.PayloadCase.TTS_AUDIO to tts,
            StreamFrame.PayloadCase.ENROLL_REQUEST to enrollRequest,
            StreamFrame.PayloadCase.ENROLL_RESPONSE to enrollResponse,
            StreamFrame.PayloadCase.STREAM_OPEN to streamOpen,
        ).forEach { (expectedCase, frame) ->
            val parsed = StreamFrame.parseFrom(frame.toByteArray())

            // Then the variant is selected and the WHOLE frame survives
            assertThat(parsed.payloadCase).isEqualTo(expectedCase)
            assertThat(parsed).isEqualTo(frame)
        }

        // And the two relational members carry their identifying fields
        assertThat(streamOpen.streamOpen.role).isEqualTo(DeviceRole.DEVICE_ROLE_DAEMON)
        assertThat(gesture.gesture.text).isEqualTo("thumbs up")
    }

    @Test
    fun `dispatch accepts every additive frame without aborting the stream`() {
        // Given one frame per additive member, mixing peer->hub input with the
        // hub->peer output a peer may legitimately echo
        val frames =
            listOf(
                StreamFrame
                    .newBuilder()
                    .setImage(ImageFrame.newBuilder().setJpeg(ByteString.copyFrom(byteArrayOf(1))).build())
                    .build(),
                StreamFrame.newBuilder().setGesture(GestureResult.newBuilder().setText("wave").build()).build(),
                StreamFrame
                    .newBuilder()
                    .setTtsAudio(TtsAudio.newBuilder().setPcm1616K(ByteString.copyFrom(byteArrayOf(2))).build())
                    .build(),
                StreamFrame.newBuilder().setEnrollRequest(EnrollRequest.newBuilder().setName("Alice").build()).build(),
                StreamFrame.newBuilder().setEnrollResponse(EnrollResponse.newBuilder().setOk(true).build()).build(),
                StreamFrame.newBuilder().setStreamOpen(StreamOpenRequest.newBuilder().setDeviceId("necklace").build()).build(),
            )

        // When each is streamed through the real service
        val responseCounts = frames.map { streamOnce(it).size }

        // Then the session stays open for every one: the hub's result collector
        // completes normally because dispatch recognised the frame.
        assertThat(responseCounts).containsExactly(1, 1, 1, 1, 1, 1)
    }

    @Test
    fun `an unset payload is accepted and ignored by dispatch`() {
        // Given a frame with no variant selected
        // When it is streamed
        val responses = streamOnce(StreamFrame.getDefaultInstance())

        // Then the stream still completes normally
        assertThat(responses).hasSize(1)
    }

    @Test
    fun `the frozen payload fields keep their wire numbers and length-delimited type`() {
        // A length-delimited field's wire tag is (number shl 3) or 2, so a
        // single-field frame's first serialized byte is exactly that tag.
        fun tag(number: Int): Int = (number shl 3) or 2

        val frames =
            listOf(
                1 to StreamFrame.newBuilder().setAudioPcm1616K(ByteString.copyFrom(byteArrayOf(1, 2, 3))).build(),
                2 to StreamFrame.newBuilder().setVideoH264Nal(ByteString.copyFrom(byteArrayOf(1, 2, 3))).build(),
                3 to StreamFrame.newBuilder().setTranscript("x").build(),
                4 to StreamFrame.newBuilder().setResult(StreamResult.newBuilder().setText("x").build()).build(),
            )

        // Then each frozen field still serializes under its original number
        frames.forEach { (number, frame) ->
            assertThat(frame.toByteArray().first().toInt() and 0xFF).isEqualTo(tag(number))
        }
    }

    @Test
    fun `the additive payload fields occupy numbers five through ten`() {
        fun tag(number: Int): Int = (number shl 3) or 2

        val frames =
            listOf(
                5 to
                    StreamFrame
                        .newBuilder()
                        .setImage(
                            ImageFrame
                                .newBuilder()
                                .setJpeg(ByteString.copyFrom(byteArrayOf(1)))
                                .build(),
                        ).build(),
                6 to StreamFrame.newBuilder().setGesture(GestureResult.newBuilder().setText("x").build()).build(),
                7 to
                    StreamFrame
                        .newBuilder()
                        .setTtsAudio(
                            TtsAudio
                                .newBuilder()
                                .setPcm1616K(ByteString.copyFrom(byteArrayOf(1)))
                                .build(),
                        ).build(),
                8 to StreamFrame.newBuilder().setEnrollRequest(EnrollRequest.newBuilder().setName("x").build()).build(),
                9 to StreamFrame.newBuilder().setEnrollResponse(EnrollResponse.newBuilder().setOk(true).build()).build(),
                10 to StreamFrame.newBuilder().setStreamOpen(StreamOpenRequest.newBuilder().setDeviceId("x").build()).build(),
            )

        // Then the additive members sit on the exact numbers the peers generate
        frames.forEach { (number, frame) ->
            assertThat(frame.toByteArray().first().toInt() and 0xFF).isEqualTo(tag(number))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun streamOnce(frame: StreamFrame): List<StreamFrame> =
        runBlocking {
            StreamServiceGrpcKt
                .StreamServiceCoroutineStub(channel)
                .openStream(flowOf(frame))
                .toList()
        }

    private fun audioFrame(bytes: ByteArray): StreamFrame =
        StreamFrame
            .newBuilder()
            .setAudioPcm1616K(ByteString.copyFrom(bytes))
            .build()

    private fun videoFrame(bytes: ByteArray): StreamFrame =
        StreamFrame
            .newBuilder()
            .setVideoH264Nal(ByteString.copyFrom(bytes))
            .build()

    /**
     * [StreamService] fake that records the media the mapping handed to the relay
     * and emits one configurable [result]. `stt`/`sink` are unused: the relay's own
     * queue policy is covered by [StreamRelayTest], so this test stays on the
     * boundary conversion.
     */
    private class RecordingStreamService : StreamService {
        var result: RelayOutput =
            RelayOutput.Speech(RelayResult(text = "unset", speakerLabel = "", ptsMs = 0L))

        val audio: MutableList<ByteArray> = mutableListOf()
        val video: MutableList<ByteArray> = mutableListOf()
        val sampleRates: MutableList<Int> = mutableListOf()

        override fun openSession(deviceId: String): RelaySession = RelaySession(sessionId = "s-mapping", deviceId = deviceId)

        override fun bindRole(
            sessionId: String,
            role: PeerRole,
        ) = Unit

        override suspend fun pushAudio(
            sessionId: String,
            audioPcm16: ByteArray,
            sampleRateHz: Int,
        ) {
            audio += audioPcm16
            sampleRates += sampleRateHz
        }

        override fun pushVideo(
            sessionId: String,
            h264Nal: ByteArray,
        ) {
            video += h264Nal
        }

        override suspend fun pushImage(
            sessionId: String,
            jpeg: ByteArray,
            ptsMs: Long,
        ) = Unit

        override suspend fun pushEnroll(
            sessionId: String,
            name: String,
            pcm16: ByteArray,
            ptsMs: Long,
        ) = Unit

        override fun results(sessionId: String): Flow<RelayOutput> = flowOf(result)

        override suspend fun closeSession(sessionId: String) = Unit

        override fun stats(): RelayStats =
            RelayStats(
                audioFrames = 0L,
                videoFrames = 0L,
                videoDropped = 0L,
                transcripts = 0L,
                liveSessions = 0,
            )
    }
}
