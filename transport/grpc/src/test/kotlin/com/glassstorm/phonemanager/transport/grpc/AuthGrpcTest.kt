package com.glassstorm.phonemanager.transport.grpc

import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.core.domain.context.Context
import com.glassstorm.phonemanager.core.domain.context.register
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.model.PairOutcome
import com.glassstorm.phonemanager.core.service.PairingServiceImpl
import com.glassstorm.phonemanager.core.service.StreamServiceImpl
import com.glassstorm.phonemanager.transport.grpc.security.AuthInterceptor
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
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var pairing: PairingServiceImpl
    private lateinit var repo: FakeDeviceRepository
    private lateinit var stt: FakeSttPort
    private lateinit var sink: FakeFrameSink
    private var nowMs: Long = 1_000_000L

    private val authKey: Metadata.Key<String> =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    @Before
    fun startServer() {
        // Given a Context wired with domain-port fakes only
        val ctx = Context()
        repo = FakeDeviceRepository()
        stt = FakeSttPort("recognized utterance")
        sink = FakeFrameSink()
        pairing = PairingServiceImpl(ctx, clock = { nowMs })
        register<DeviceRepository>(ctx, repo)
        register<PairingService>(ctx, pairing)
        register<StreamService>(ctx, StreamServiceImpl(ctx))
        register<SttPort>(ctx, stt)
        register<FrameSink>(ctx, sink)

        val name = InProcessServerBuilder.generateName()
        server =
            InProcessServerBuilder
                .forName(name)
                .directExecutor()
                .addService(PairingGrpcService(ctx))
                .addService(StreamGrpcService(ctx))
                .intercept(AuthInterceptor(pairing))
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

    // ---------------------------------------------------------------- required cases

    @Test
    fun `pair with the open window pin issues a token and heartbeat with that token is ok`() {
        // Given an open pairing window
        val window = pairing.openWindow(ttlMs = 60_000)

        // When Pair is called with the window PIN (no token needed)
        val paired = runBlocking { stub().pair(pairRequest(window.pin)) }

        // Then a token and device id are issued
        assertThat(paired.ok).isTrue()
        assertThat(paired.token).isNotEmpty()
        assertThat(paired.deviceId).isNotEmpty()

        // And a Heartbeat carrying that token is accepted
        val beat = runBlocking { stub().heartbeat(HeartbeatRequest.getDefaultInstance(), bearer(paired.token)) }
        assertThat(beat.ok).isTrue()
        assertThat(beat.serverTimeMs).isGreaterThan(0L)
    }

    @Test
    fun `heartbeat with NO metadata is rejected with UNAUTHENTICATED`() {
        // Given a paired device and its token
        val token = pairOnce()

        // When Heartbeat is called without any authorization metadata
        val status = unauthenticatedStatus { runBlocking { blocking().heartbeat(HeartbeatRequest.getDefaultInstance()) } }

        // Then the exact gRPC status is UNAUTHENTICATED (captured raw for the evidence file)
        println("[QA] unauthenticated heartbeat raw status = $status")
        assertThat(status.code).isEqualTo(Status.Code.UNAUTHENTICATED)
        assertThat(status.code.name).isEqualTo("UNAUTHENTICATED")
        assertThat(token).isNotEmpty()
    }

    @Test
    fun `wrong pin is rejected with a reason and no token is issued`() {
        // Given an open window and a PIN that is not the window PIN
        val window = pairing.openWindow(ttlMs = 60_000)

        // When Pair is called with the wrong PIN
        val paired = runBlocking { stub().pair(pairRequest(wrongPin(window.pin))) }

        // Then it is rejected, with a machine-checkable reason and no secret material
        assertThat(paired.ok).isFalse()
        assertThat(paired.rejectReason).isEqualTo(PairOutcome.REASON_PIN_INVALID)
        assertThat(paired.token).isEmpty()
        assertThat(paired.deviceId).isEmpty()
    }

    @Test
    fun `expired pin is rejected`() {
        // Given a window with a 1s TTL and a clock advanced past it
        val window = pairing.openWindow(ttlMs = 1_000)
        nowMs += 5_000

        // When Pair is called with the (formerly valid) PIN
        val paired = runBlocking { stub().pair(pairRequest(window.pin)) }

        // Then it is rejected as expired and no token is issued
        assertThat(paired.ok).isFalse()
        assertThat(paired.rejectReason).isEqualTo(PairOutcome.REASON_PIN_EXPIRED)
        assertThat(paired.token).isEmpty()
    }

    @Test
    fun `replayed pin is rejected and issues no second token`() {
        // Given a PIN consumed by a successful Pair
        val window = pairing.openWindow(ttlMs = 60_000)
        val first = runBlocking { stub().pair(pairRequest(window.pin)) }
        assertThat(first.ok).isTrue()

        // When the same PIN is replayed
        val replay = runBlocking { stub().pair(pairRequest(window.pin)) }

        // Then the replay is rejected and no second token escapes
        assertThat(replay.ok).isFalse()
        assertThat(replay.rejectReason).isEqualTo(PairOutcome.REASON_PIN_CONSUMED)
        assertThat(replay.token).isEmpty()
    }

    @Test
    fun `tampered token is rejected with UNAUTHENTICATED`() {
        // Given a real token with a single character flipped
        val token = pairOnce()
        val tampered = tamper(token)

        // When/Then Heartbeat with the tampered token is UNAUTHENTICATED
        val status =
            unauthenticatedStatus {
                runBlocking { blocking().heartbeat(HeartbeatRequest.getDefaultInstance(), bearer(tampered)) }
            }
        println("[QA] tampered token raw status = $status")
        assertThat(status.code).isEqualTo(Status.Code.UNAUTHENTICATED)
    }

    @Test
    fun `revoked token is rejected with UNAUTHENTICATED`() {
        // Given a paired device that is then revoked
        val window = pairing.openWindow(ttlMs = 60_000)
        val paired = runBlocking { stub().pair(pairRequest(window.pin)) }
        pairing.revoke(paired.deviceId)

        // When/Then Heartbeat with the revoked token is UNAUTHENTICATED
        val status =
            unauthenticatedStatus {
                runBlocking { blocking().heartbeat(HeartbeatRequest.getDefaultInstance(), bearer(paired.token)) }
            }
        println("[QA] revoked token raw status = $status")
        assertThat(status.code).isEqualTo(Status.Code.UNAUTHENTICATED)
    }

    @Test
    fun `malformed authorization headers are rejected with UNAUTHENTICATED`() {
        // Given a token and a set of malformed header values (adversarial: malformed_input)
        val token = pairOnce()
        val malformed =
            listOf(
                "Bearer", // scheme with no value
                "Bearer ", // scheme with an empty token
                token, // token with no scheme at all
                "Basic $token", // wrong scheme
                "bearerx $token", // scheme that merely starts like Bearer
            )

        // When/Then each one is rejected on the exact status
        malformed.forEach { header ->
            val status =
                unauthenticatedStatus {
                    runBlocking {
                        blocking().heartbeat(HeartbeatRequest.getDefaultInstance(), rawAuth(header))
                    }
                }
            println("[QA] malformed header <$header> raw status = $status")
            assertWithMessage("malformed header <$header>")
                .that(status.code)
                .isEqualTo(Status.Code.UNAUTHENTICATED)
        }
    }

    @Test
    fun `audio frame round trips through the stt port and returns a transcript frame`() {
        // Given a paired device and an opaque PCM16 audio frame
        val token = pairOnce()
        val audio =
            StreamFrame
                .newBuilder()
                .setAudioPcm1616K(
                    com.google.protobuf.ByteString
                        .copyFrom(ByteArray(320) { 7 }),
                ).build()

        // When it is pushed through OpenStream
        val responses =
            runBlocking {
                StreamServiceGrpcKt
                    .StreamServiceCoroutineStub(channel)
                    .openStream(flowOf(audio), bearer(token))
                    .toList()
            }

        // Then the STT fake really saw the frame and a transcript came back
        assertThat(stt.audioFrameCount).isEqualTo(1)
        assertThat(responses).hasSize(1)
        assertThat(responses.single().payloadCase).isEqualTo(StreamFrame.PayloadCase.TRANSCRIPT)
        assertThat(responses.single().transcript).isEqualTo("recognized utterance")
    }

    @Test
    fun `stream without a token is rejected with UNAUTHENTICATED`() {
        // Given no token
        // When OpenStream is opened
        val status =
            unauthenticatedStatus {
                runBlocking {
                    StreamServiceGrpcKt
                        .StreamServiceCoroutineStub(channel)
                        .openStream(flowOf(StreamFrame.getDefaultInstance()))
                        .toList()
                }
            }

        // Then the stream itself is rejected on the exact status
        println("[QA] unauthenticated stream raw status = $status")
        assertThat(status.code).isEqualTo(Status.Code.UNAUTHENTICATED)
    }

    // ---------------------------------------------------------------- helpers

    private fun stub(): PairingServiceGrpcKt.PairingServiceCoroutineStub = PairingServiceGrpcKt.PairingServiceCoroutineStub(channel)

    /** Blocking surface: the coroutine stub driven synchronously, which takes metadata headers. */
    private fun blocking(): PairingServiceGrpcKt.PairingServiceCoroutineStub = stub()

    private fun bearer(token: String): Metadata = Metadata().apply { put(authKey, "Bearer $token") }

    private fun rawAuth(header: String): Metadata = Metadata().apply { put(authKey, header) }

    private fun pairRequest(pin: String): PairRequest =
        PairRequest
            .newBuilder()
            .setPin(pin)
            .setDeviceName("glass-1")
            .setRole(DeviceRole.DEVICE_ROLE_GLASS)
            .build()

    private fun pairOnce(): String {
        val window = pairing.openWindow(ttlMs = 60_000)
        val paired = runBlocking { stub().pair(pairRequest(window.pin)) }
        check(paired.ok) { "test setup failed to pair: ${paired.rejectReason}" }
        return paired.token
    }

    /**
     * Runs [call], requiring it to fail, and returns the raw [Status] it failed with.
     *
     * The coroutine stubs raise [StatusException]; the blocking/flow paths can also
     * surface [StatusRuntimeException]. Both carry the same [Status].
     */
    private fun unauthenticatedStatus(call: () -> Unit): Status {
        val failure =
            try {
                call()
                null
            } catch (exception: StatusException) {
                exception.status
            } catch (exception: StatusRuntimeException) {
                exception.status
            }
        assertThat(failure).isNotNull()
        return failure!!
    }

    private fun wrongPin(pin: String): String {
        val lastDigit = pin.last()
        val replacement = if (lastDigit == '0') '1' else '0'
        return pin.dropLast(1) + replacement
    }

    private fun tamper(token: String): String {
        val last = token.last()
        val replacement = if (last == 'A') 'B' else 'A'
        return token.dropLast(1) + replacement
    }
}
