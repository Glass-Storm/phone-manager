package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.dto.PairOutcome
import com.glassstorm.phonemanager.domain.service.PairingService
import com.glassstorm.phonemanager.domain.service.StreamService
import com.glassstorm.phonemanager.service.security.AuthInterceptor
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ecosys.v1.DeviceRole
import ecosys.v1.HeartbeatRequest
import ecosys.v1.PairRequest
import ecosys.v1.PairingServiceGrpcKt
import ecosys.v1.StreamFrame
import ecosys.v1.StreamServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * End-to-end in-process gRPC auth suite. No sockets, no device: an in-process
 * server carries the real [AuthInterceptor] plus both coroutine services.
 *
 * `directExecutor()` keeps the tests deterministic (no thread-pool timing), and
 * the server/channel are torn down with `awaitTermination` so no port or
 * coroutine leaks between tests.
 */
class AuthGrpcTest {
    private lateinit var GoServer: Server
    private lateinit var GoChannel: ManagedChannel
    private lateinit var GoPairing: PairingServiceImpl
    private lateinit var GoRepo: FakeDeviceRepository
    private lateinit var GoStt: FakeSttPort
    private lateinit var GoSink: FakeFrameSink
    private var GoNowMs: Long = 1_000_000L

    private val GoAuthKey: Metadata.Key<String> =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    @Before
    fun GoStartServer() {
        // Given a Context wired with domain-port fakes only
        val GoCtx = Context()
        GoRepo = FakeDeviceRepository()
        GoStt = FakeSttPort("recognized utterance")
        GoSink = FakeFrameSink()
        GoPairing = PairingServiceImpl(GoCtx, GoClock = { GoNowMs })
        Register<DeviceRepository>(GoCtx, GoRepo)
        Register<PairingService>(GoCtx, GoPairing)
        Register<StreamService>(GoCtx, StreamServiceImpl(GoCtx))
        Register<SttPort>(GoCtx, GoStt)
        Register<FrameSink>(GoCtx, GoSink)

        val GoName = InProcessServerBuilder.generateName()
        GoServer =
            InProcessServerBuilder
                .forName(GoName)
                .directExecutor()
                .addService(PairingGrpcService(GoCtx))
                .addService(StreamGrpcService(GoCtx))
                .intercept(AuthInterceptor(GoPairing))
                .build()
                .start()
        GoChannel = InProcessChannelBuilder.forName(GoName).directExecutor().build()
    }

    @After
    fun GoStopServer() {
        GoChannel.shutdownNow()
        GoServer.shutdownNow()
        GoChannel.awaitTermination(5, TimeUnit.SECONDS)
        GoServer.awaitTermination(5, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- required cases

    @Test
    fun `pair with the open window pin issues a token and heartbeat with that token is ok`() {
        // Given an open pairing window
        val GoWindow = GoPairing.GoOpenWindow(ttlMs = 60_000)

        // When Pair is called with the window PIN (no token needed)
        val GoPaired = runBlocking { GoStub().pair(GoPairRequest(GoWindow.GoPin)) }

        // Then a token and device id are issued
        assertThat(GoPaired.ok).isTrue()
        assertThat(GoPaired.token).isNotEmpty()
        assertThat(GoPaired.deviceId).isNotEmpty()

        // And a Heartbeat carrying that token is accepted
        val GoBeat = runBlocking { GoStub().heartbeat(HeartbeatRequest.getDefaultInstance(), GoBearer(GoPaired.token)) }
        assertThat(GoBeat.ok).isTrue()
        assertThat(GoBeat.serverTimeMs).isGreaterThan(0L)
    }

    @Test
    fun `heartbeat with NO metadata is rejected with UNAUTHENTICATED`() {
        // Given a paired device and its token
        val GoToken = GoPairOnce()

        // When Heartbeat is called without any authorization metadata
        val GoStatus = GoUnauthenticatedStatus { runBlocking { GoBlocking().heartbeat(HeartbeatRequest.getDefaultInstance()) } }

        // Then the exact gRPC status is UNAUTHENTICATED (captured raw for the evidence file)
        println("[QA] unauthenticated heartbeat raw status = $GoStatus")
        assertThat(GoStatus.code).isEqualTo(Status.Code.UNAUTHENTICATED)
        assertThat(GoStatus.code.name).isEqualTo("UNAUTHENTICATED")
        assertThat(GoToken).isNotEmpty()
    }

    @Test
    fun `wrong pin is rejected with a reason and no token is issued`() {
        // Given an open window and a PIN that is not the window PIN
        val GoWindow = GoPairing.GoOpenWindow(ttlMs = 60_000)

        // When Pair is called with the wrong PIN
        val GoPaired = runBlocking { GoStub().pair(GoPairRequest(GoWrongPin(GoWindow.GoPin))) }

        // Then it is rejected, with a machine-checkable reason and no secret material
        assertThat(GoPaired.ok).isFalse()
        assertThat(GoPaired.rejectReason).isEqualTo(PairOutcome.GoReasonPinInvalid)
        assertThat(GoPaired.token).isEmpty()
        assertThat(GoPaired.deviceId).isEmpty()
    }

    @Test
    fun `expired pin is rejected`() {
        // Given a window with a 1s TTL and a clock advanced past it
        val GoWindow = GoPairing.GoOpenWindow(ttlMs = 1_000)
        GoNowMs += 5_000

        // When Pair is called with the (formerly valid) PIN
        val GoPaired = runBlocking { GoStub().pair(GoPairRequest(GoWindow.GoPin)) }

        // Then it is rejected as expired and no token is issued
        assertThat(GoPaired.ok).isFalse()
        assertThat(GoPaired.rejectReason).isEqualTo(PairOutcome.GoReasonPinExpired)
        assertThat(GoPaired.token).isEmpty()
    }

    @Test
    fun `replayed pin is rejected and issues no second token`() {
        // Given a PIN consumed by a successful Pair
        val GoWindow = GoPairing.GoOpenWindow(ttlMs = 60_000)
        val GoFirst = runBlocking { GoStub().pair(GoPairRequest(GoWindow.GoPin)) }
        assertThat(GoFirst.ok).isTrue()

        // When the same PIN is replayed
        val GoReplay = runBlocking { GoStub().pair(GoPairRequest(GoWindow.GoPin)) }

        // Then the replay is rejected and no second token escapes
        assertThat(GoReplay.ok).isFalse()
        assertThat(GoReplay.rejectReason).isEqualTo(PairOutcome.GoReasonPinConsumed)
        assertThat(GoReplay.token).isEmpty()
    }

    @Test
    fun `tampered token is rejected with UNAUTHENTICATED`() {
        // Given a real token with a single character flipped
        val GoToken = GoPairOnce()
        val GoTampered = GoTamper(GoToken)

        // When/Then Heartbeat with the tampered token is UNAUTHENTICATED
        val GoStatus =
            GoUnauthenticatedStatus {
                runBlocking { GoBlocking().heartbeat(HeartbeatRequest.getDefaultInstance(), GoBearer(GoTampered)) }
            }
        println("[QA] tampered token raw status = $GoStatus")
        assertThat(GoStatus.code).isEqualTo(Status.Code.UNAUTHENTICATED)
    }

    @Test
    fun `revoked token is rejected with UNAUTHENTICATED`() {
        // Given a paired device that is then revoked
        val GoWindow = GoPairing.GoOpenWindow(ttlMs = 60_000)
        val GoPaired = runBlocking { GoStub().pair(GoPairRequest(GoWindow.GoPin)) }
        GoPairing.GoRevoke(GoPaired.deviceId)

        // When/Then Heartbeat with the revoked token is UNAUTHENTICATED
        val GoStatus =
            GoUnauthenticatedStatus {
                runBlocking { GoBlocking().heartbeat(HeartbeatRequest.getDefaultInstance(), GoBearer(GoPaired.token)) }
            }
        println("[QA] revoked token raw status = $GoStatus")
        assertThat(GoStatus.code).isEqualTo(Status.Code.UNAUTHENTICATED)
    }

    @Test
    fun `malformed authorization headers are rejected with UNAUTHENTICATED`() {
        // Given a token and a set of malformed header values (adversarial: malformed_input)
        val GoToken = GoPairOnce()
        val GoMalformed =
            listOf(
                "Bearer", // scheme with no value
                "Bearer ", // scheme with an empty token
                GoToken, // token with no scheme at all
                "Basic $GoToken", // wrong scheme
                "bearerx $GoToken", // scheme that merely starts like Bearer
            )

        // When/Then each one is rejected on the exact status
        GoMalformed.forEach { GoHeader ->
            val GoStatus =
                GoUnauthenticatedStatus {
                    runBlocking {
                        GoBlocking().heartbeat(HeartbeatRequest.getDefaultInstance(), GoRawAuth(GoHeader))
                    }
                }
            println("[QA] malformed header <$GoHeader> raw status = $GoStatus")
            assertWithMessage("malformed header <$GoHeader>")
                .that(GoStatus.code)
                .isEqualTo(Status.Code.UNAUTHENTICATED)
        }
    }

    @Test
    fun `audio frame round trips through the stt port and returns a transcript frame`() {
        // Given a paired device and an opaque PCM16 audio frame
        val GoToken = GoPairOnce()
        val GoAudio =
            StreamFrame
                .newBuilder()
                .setAudioPcm1616K(
                    com.google.protobuf.ByteString
                        .copyFrom(ByteArray(320) { 7 }),
                ).build()

        // When it is pushed through OpenStream
        val GoResponses =
            runBlocking {
                StreamServiceGrpcKt
                    .StreamServiceCoroutineStub(GoChannel)
                    .openStream(flowOf(GoAudio), GoBearer(GoToken))
                    .toList()
            }

        // Then the STT fake really saw the frame and a transcript came back
        assertThat(GoStt.GoAudioFrameCount).isEqualTo(1)
        assertThat(GoResponses).hasSize(1)
        assertThat(GoResponses.single().payloadCase).isEqualTo(StreamFrame.PayloadCase.TRANSCRIPT)
        assertThat(GoResponses.single().transcript).isEqualTo("recognized utterance")
    }

    @Test
    fun `stream without a token is rejected with UNAUTHENTICATED`() {
        // Given no token
        // When OpenStream is opened
        val GoStatus =
            GoUnauthenticatedStatus {
                runBlocking {
                    StreamServiceGrpcKt
                        .StreamServiceCoroutineStub(GoChannel)
                        .openStream(flowOf(StreamFrame.getDefaultInstance()))
                        .toList()
                }
            }

        // Then the stream itself is rejected on the exact status
        println("[QA] unauthenticated stream raw status = $GoStatus")
        assertThat(GoStatus.code).isEqualTo(Status.Code.UNAUTHENTICATED)
    }

    // ---------------------------------------------------------------- helpers

    private fun GoStub(): PairingServiceGrpcKt.PairingServiceCoroutineStub = PairingServiceGrpcKt.PairingServiceCoroutineStub(GoChannel)

    /** Blocking surface: the coroutine stub driven synchronously, which takes metadata headers. */
    private fun GoBlocking(): PairingServiceGrpcKt.PairingServiceCoroutineStub = GoStub()

    private fun GoBearer(token: String): Metadata = Metadata().apply { put(GoAuthKey, "Bearer $token") }

    private fun GoRawAuth(header: String): Metadata = Metadata().apply { put(GoAuthKey, header) }

    private fun GoPairRequest(pin: String): PairRequest =
        PairRequest
            .newBuilder()
            .setPin(pin)
            .setDeviceName("glass-1")
            .setRole(DeviceRole.DEVICE_ROLE_GLASS)
            .build()

    private fun GoPairOnce(): String {
        val GoWindow = GoPairing.GoOpenWindow(ttlMs = 60_000)
        val GoPaired = runBlocking { GoStub().pair(GoPairRequest(GoWindow.GoPin)) }
        check(GoPaired.ok) { "test setup failed to pair: ${GoPaired.rejectReason}" }
        return GoPaired.token
    }

    /**
     * Runs [GoCall], requiring it to fail, and returns the raw [Status] it failed with.
     *
     * The coroutine stubs raise [StatusException]; the blocking/flow paths can also
     * surface [StatusRuntimeException]. Both carry the same [Status].
     */
    private fun GoUnauthenticatedStatus(GoCall: () -> Unit): Status {
        val GoFailure =
            try {
                GoCall()
                null
            } catch (GoException: StatusException) {
                GoException.status
            } catch (GoException: StatusRuntimeException) {
                GoException.status
            }
        assertThat(GoFailure).isNotNull()
        return GoFailure!!
    }

    private fun GoWrongPin(pin: String): String {
        val GoLastDigit = pin.last()
        val GoReplacement = if (GoLastDigit == '0') '1' else '0'
        return pin.dropLast(1) + GoReplacement
    }

    private fun GoTamper(token: String): String {
        val GoLast = token.last()
        val GoReplacement = if (GoLast == 'A') 'B' else 'A'
        return token.dropLast(1) + GoReplacement
    }
}
