package com.glassstorm.phonemanager.transport.grpc

import com.glassstorm.phonemanager.core.domain.security.TokenVerifier
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.model.Device
import com.glassstorm.phonemanager.core.model.PairOutcome
import com.glassstorm.phonemanager.core.model.Pairing
import com.glassstorm.phonemanager.core.model.RelayResult
import com.glassstorm.phonemanager.core.model.RelaySession
import com.glassstorm.phonemanager.core.model.RelayStats
import com.glassstorm.phonemanager.transport.grpc.security.AuthInterceptor
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import ecosys.v1.DeviceRole
import ecosys.v1.PairRequest
import ecosys.v1.PairingServiceGrpcKt
import ecosys.v1.StreamFrame
import ecosys.v1.StreamResult
import ecosys.v1.StreamServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.Metadata
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
 * The relay/auth suites prove behaviour; this one pins the CONVERSION at the
 * boundary, because that is the whole reason the contract is isolated to this
 * module (D3/D4): a proto change does not auto-propagate into the domain's DTOs,
 * so the mapping must be confronted by a test.
 *
 * Everything runs over an in-process gRPC server so the REAL mapping code runs
 * against the REAL generated `ecosys.v1` types, with only the domain-side
 * collaborators faked (exactly as a composition would supply real ones).
 *
 * ## What is pinned
 *
 *  * `PairOutcome.Ok` -> `PairResponse{ok=true, token, device_id}`;
 *  * `PairOutcome.Rejected` -> `PairResponse{ok=false, reject_reason=<exact>}`;
 *  * the `PairRequest` role/name -> the domain `pair()` arguments;
 *  * a `RelayResult` -> a `transcript` [StreamFrame] carrying the same text;
 *  * audio (and video) payloads pass through to the relay OPAQUELY, byte for byte.
 *
 * Deliberately NOT pinned as present: `RelayResult.ptsMs` / `speakerLabel` are
 * NOT carried on the v1 transcript frame (the proto's `transcript` variant is a
 * bare string). That is the frozen behaviour, so the test asserts the `result`
 * variant is NOT selected — making any future, un-negotiated change fail here.
 */
class GrpcMappingTest {
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var pairing: RecordingPairingService
    private lateinit var stream: RecordingStreamService

    private val authKey: Metadata.Key<String> =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    /** The exact device the fake token resolves to. */
    private val device =
        Device(
            deviceId = "d-mapping",
            deviceName = "glass-mapping",
            role = "GLASS",
            tokenHash = "hash-d-mapping",
            pairedAtMs = 1_000L,
            lastSeenMs = null,
        )

    @Before
    fun startServer() {
        pairing = RecordingPairingService()
        stream = RecordingStreamService()

        val name = InProcessServerBuilder.generateName()
        server =
            InProcessServerBuilder
                .forName(name)
                .directExecutor()
                .addService(PairingGrpcService(pairing))
                .addService(StreamGrpcService(stream))
                .intercept(AuthInterceptor(TokenVerifier { token -> device.takeIf { token == GOOD_TOKEN } }))
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

    // ------------------------------------------------------------------ Pair

    @Test
    fun `a successful pair maps to ok true with the token and device id`() {
        // Given the domain pairing will accept
        pairing.outcome = PairOutcome.Ok(deviceId = "d-issued", token = "tok-issued")

        // When Pair is called over the wire
        val response = runBlocking { pairingStub().pair(pairRequest()) }

        // Then the domain value maps field-for-field
        assertThat(response.ok).isTrue()
        assertThat(response.token).isEqualTo("tok-issued")
        assertThat(response.deviceId).isEqualTo("d-issued")
        // ...and a rejection reason is NOT smuggled onto a success
        assertThat(response.rejectReason).isEmpty()

        // And the request fields reached the domain port unchanged
        assertThat(pairing.lastPin).isEqualTo(PIN)
        assertThat(pairing.lastDeviceName).isEqualTo("glass-1")
        assertThat(pairing.lastRole).isEqualTo(DeviceRole.DEVICE_ROLE_GLASS.name)
    }

    @Test
    fun `a rejection maps to ok false with the exact reject reason and no token`() {
        // Given the domain pairing will reject with a specific reason
        pairing.outcome = PairOutcome.Rejected(reason = PairOutcome.REASON_PIN_INVALID)

        // When Pair is called
        val response = runBlocking { pairingStub().pair(pairRequest()) }

        // Then the reason string crosses the boundary byte-for-byte
        assertThat(response.ok).isFalse()
        assertThat(response.rejectReason).isEqualTo(PairOutcome.REASON_PIN_INVALID)
        assertThat(response.token).isEmpty()
        assertThat(response.deviceId).isEmpty()
    }

    @Test
    fun `every domain reject reason maps to its identical wire string`() {
        // Given each frozen reason in turn
        val reasons =
            listOf(
                PairOutcome.REASON_NO_WINDOW,
                PairOutcome.REASON_PIN_INVALID,
                PairOutcome.REASON_PIN_EXPIRED,
                PairOutcome.REASON_PIN_CONSUMED,
                PairOutcome.REASON_PIN_LOCKED,
                PairOutcome.REASON_PIN_MISSING,
                PairOutcome.REASON_NAME_MISSING,
            )

        reasons.forEach { reason ->
            // When the domain rejects with it
            pairing.outcome = PairOutcome.Rejected(reason = reason)
            val response = runBlocking { pairingStub().pair(pairRequest()) }

            // Then the wire carries the SAME string, never a re-mapped one
            assertThat(response.rejectReason).isEqualTo(reason)
            assertThat(response.ok).isFalse()
        }
    }

    // ------------------------------------------------------------------ Stream

    @Test
    fun `a relay result maps to a transcript frame carrying the same text`() {
        // Given a paired peer and a domain utterance to relay
        val audio = audioFrame(ByteArray(8) { 3 })
        stream.result = RelayResult(text = "mapped utterance", speakerLabel = "Speaker 1", ptsMs = 4_242L)

        // When one audio frame is streamed
        val responses = streamOnce(audio)

        // Then the domain result became a transcript frame with the SAME text
        assertThat(responses).hasSize(1)
        val frame = responses.single()
        assertThat(frame.payloadCase).isEqualTo(StreamFrame.PayloadCase.TRANSCRIPT)
        assertThat(frame.transcript).isEqualTo("mapped utterance")

        // And the v1 transcript variant does NOT carry pts_ms / speaker_label:
        // those stay on the `result` variant, which this mapping deliberately
        // does not select. Pinned so an accidental change fails loudly.
        assertThat(frame.hasResult()).isFalse()
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

    // ------------------------------------------------------------------ helpers

    private fun pairingStub(): PairingServiceGrpcKt.PairingServiceCoroutineStub = PairingServiceGrpcKt.PairingServiceCoroutineStub(channel)

    private fun streamOnce(frame: StreamFrame): List<StreamFrame> =
        runBlocking {
            StreamServiceGrpcKt
                .StreamServiceCoroutineStub(channel)
                .openStream(flowOf(frame), bearer())
                .toList()
        }

    private fun bearer(): Metadata = Metadata().apply { put(authKey, "Bearer $GOOD_TOKEN") }

    private fun pairRequest(): PairRequest =
        PairRequest
            .newBuilder()
            .setPin(PIN)
            .setDeviceName("glass-1")
            .setRole(DeviceRole.DEVICE_ROLE_GLASS)
            .build()

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
     * [PairingService] fake that records the arguments the mapping passed and
     * returns a configurable [outcome]. Only the two methods the RPC surface uses
     * are exercised; the rest are inert.
     */
    private class RecordingPairingService : PairingService {
        var outcome: PairOutcome = PairOutcome.Rejected(reason = PairOutcome.REASON_NO_WINDOW)

        var lastPin: String? = null
        var lastDeviceName: String? = null
        var lastRole: String? = null

        override fun openWindow(ttlMs: Long): Pairing = Pairing(pin = "000000", expiresAtMs = ttlMs)

        override fun stopWindow() = Unit

        override fun pair(
            pin: String,
            deviceName: String,
            role: String,
        ): PairOutcome {
            lastPin = pin
            lastDeviceName = deviceName
            lastRole = role
            return outcome
        }

        override fun touchLastSeen(
            deviceId: String,
            seenAtMs: Long,
        ) = Unit

        override fun revoke(deviceId: String) = Unit

        override fun listPaired(): List<Device> = emptyList()
    }

    /**
     * [StreamService] fake that records the media the mapping handed to the relay
     * and emits one configurable [result]. `stt`/`sink` are unused: the relay's own
     * queue policy is covered by [StreamRelayTest], so this test stays on the
     * boundary conversion.
     */
    private class RecordingStreamService : StreamService {
        var result: RelayResult = RelayResult(text = "unset", speakerLabel = "", ptsMs = 0L)

        val audio: MutableList<ByteArray> = mutableListOf()
        val video: MutableList<ByteArray> = mutableListOf()
        val sampleRates: MutableList<Int> = mutableListOf()

        override fun openSession(deviceId: String): RelaySession = RelaySession(sessionId = "s-mapping", deviceId = deviceId)

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

        override fun results(sessionId: String): Flow<RelayResult> = flowOf(result)

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

    private companion object {
        const val PIN: String = "428193"
        const val GOOD_TOKEN: String = "good-token"
    }
}
