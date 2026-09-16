package com.glassstorm.phonemanager.service

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.glassstorm.phonemanager.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.service.PairingService
import com.glassstorm.phonemanager.domain.service.StreamService
import com.glassstorm.phonemanager.service.security.AuthInterceptor
import ecosys.v1.DeviceRole
import ecosys.v1.PairRequest
import ecosys.v1.PairingServiceGrpcKt
import ecosys.v1.StreamFrame
import ecosys.v1.StreamServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Relay hardening suite: bounded queues, drop-oldest video, park-based audio,
 * drain-on-close, and exactly-once STT teardown.
 *
 * The queue-policy cases drive a [StreamServiceImpl] whose `GoScope` is a
 * `StandardTestDispatcher` the test controls, so the drop/backpressure outcomes are
 * race-free: the pumps are simply not scheduled until the test advances them.
 * The RPC-surface case uses T5's in-process gRPC pattern with `directExecutor`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamRelayTest {

    @get:Rule
    val GoDeadline: Timeout = Timeout.seconds(60)

    private val GoAuthKey: Metadata.Key<String> =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    // -------------------------------------------------------- queue policy cases

    @Test
    fun `twenty audio frames all reach the stt port and emit transcripts`() = runTest {
        // Given a relay with a controlled scope and 20 queued audio frames
        val GoWired = GoWiredContext()
        val GoScope = GoTestScope(testScheduler)
        val GoStream = StreamServiceImpl(GoWired.Ctx, GoScope = GoScope)
        val GoSession = GoStream.GoOpenSession("device-audio")
        val GoSeen = mutableListOf<String>()
        val GoCollector = launch { GoStream.GoResults(GoSession.GoSessionId).collect { GoSeen += it.GoText } }

        // When they are pushed and the pumps run
        repeat(20) { GoStream.GoPushAudio(GoSession.GoSessionId, GoPcm(it), StreamService.GoAudioSampleRateHz) }
        advanceUntilIdle()

        // Then nothing was dropped and every frame produced an utterance
        assertThat(GoStream.GoStats().GoAudioFrames).isEqualTo(20L)
        assertThat(GoStream.GoStats().GoTranscripts).isEqualTo(20L)
        assertThat(GoSeen).hasSize(20)

        // And the results flow completes once the session closes
        GoStream.GoCloseSession(GoSession.GoSessionId)
        advanceUntilIdle()
        GoCollector.join()
        GoScope.cancel()
    }

    @Test
    fun `twenty video nals arrive byte for byte identical and nothing is dropped`() = runTest {
        // Given 20 distinct opaque H.264 NALs
        val GoWired = GoWiredContext()
        val GoScope = GoTestScope(testScheduler)
        val GoStream = StreamServiceImpl(GoWired.Ctx, GoScope = GoScope)
        val GoSession = GoStream.GoOpenSession("device-video")
        val GoInputs = (0 until 20).map { GoNal(it) }

        // When they are pushed and the pump runs
        GoInputs.forEach { GoStream.GoPushVideo(GoSession.GoSessionId, it) }
        advanceUntilIdle()

        // Then the sink saw exactly those bytes, unchanged, and nothing was evicted:
        // offered == evicted-free, so GoVideoFrames is the delivered count here
        assertThat(GoStream.GoStats().GoVideoFrames).isEqualTo(20L)
        assertThat(GoStream.GoStats().GoVideoDropped).isEqualTo(0L)
        assertThat(GoWired.Sink.GoVideoNals).hasSize(20)
        GoWired.Sink.GoVideoNals.forEachIndexed { GoIndex, GoNal ->
            assertThat(GoNal).isEqualTo(GoInputs[GoIndex])
        }

        GoStream.GoCloseSession(GoSession.GoSessionId)
        advanceUntilIdle()
        GoScope.cancel()
    }

    @Test
    fun `full video queue evicts oldest while audio is never dropped`() = runTest {
        // Given a relay with a tiny video buffer and a PAUSED scope (no consumer yet)
        val GoWired = GoWiredContext()
        val GoScope = GoTestScope(testScheduler)
        val GoStream = StreamServiceImpl(
            GoCtx = GoWired.Ctx,
            GoAudioCapacity = 64,
            GoVideoCapacity = 4,
            GoScope = GoScope,
        )
        val GoSession = GoStream.GoOpenSession("device-pressure")

        // When 5 audio frames and 20 video NALs are pushed before any pump runs
        repeat(5) { GoStream.GoPushAudio(GoSession.GoSessionId, GoPcm(it), StreamService.GoAudioSampleRateHz) }
        repeat(20) { GoStream.GoPushVideo(GoSession.GoSessionId, GoNal(it)) }

        // Then GoVideoFrames counts every NAL OFFERED to the live session — all 20,
        // including the 16 that drop-oldest evicted (counted in GoVideoDropped)
        assertThat(GoStream.GoStats().GoAudioFrames).isEqualTo(5L)
        assertThat(GoStream.GoStats().GoVideoFrames).isEqualTo(20L)
        assertThat(GoStream.GoStats().GoVideoDropped).isEqualTo(16L)

        // And the survivors still in the queue are the LAST 4 offered, in order:
        // the pump then delivers exactly those, proving drop-oldest kept the edge
        assertThat(GoWired.Sink.GoVideoNals).isEmpty()
        advanceUntilIdle()
        assertThat(GoWired.Sink.GoVideoNals).hasSize(4)
        assertThat(GoWired.Sink.GoVideoNals.map { it.toList() })
            .containsExactlyElementsIn((16 until 20).map { GoNal(it).toList() })
            .inOrder()

        // The offered/evicted totals are unchanged by draining: they are offer-side
        assertThat(GoStream.GoStats().GoVideoFrames).isEqualTo(20L)
        assertThat(GoStream.GoStats().GoVideoDropped).isEqualTo(16L)

        GoScope.cancel()
    }

    @Test
    fun `closing drains already queued frames before returning`() = runTest {
        // Given frames enqueued while the pumps are not yet scheduled
        val GoWired = GoWiredContext()
        val GoScope = GoTestScope(testScheduler)
        val GoStream = StreamServiceImpl(GoCtx = GoWired.Ctx, GoScope = GoScope)
        val GoSession = GoStream.GoOpenSession("device-drain")
        repeat(8) { GoStream.GoPushAudio(GoSession.GoSessionId, GoPcm(it), StreamService.GoAudioSampleRateHz) }
        repeat(8) { GoStream.GoPushVideo(GoSession.GoSessionId, GoNal(it)) }

        // Then nothing has been consumed yet (proves the frames really were queued)
        assertThat(GoWired.Stt.GoAudioFrameCount).isEqualTo(0)
        assertThat(GoWired.Sink.GoVideoNals).isEmpty()

        // When the session is closed
        GoStream.GoCloseSession(GoSession.GoSessionId)

        // Then the queued frames were DRAINED (not discarded) before close returned
        assertThat(GoWired.Stt.GoAudioFrameCount).isEqualTo(8)
        assertThat(GoWired.Sink.GoVideoNals).hasSize(8)
        assertThat(GoStream.GoStats().GoAudioFrames).isEqualTo(8L)
        GoScope.cancel()
    }

    // ------------------------------------------------------- teardown / no-op cases

    @Test
    fun `closing twice is a no-op and the stt session is released exactly once`() = runTest {
        // Given an open session
        val GoWired = GoWiredContext()
        val GoScope = GoTestScope(testScheduler)
        val GoStream = StreamServiceImpl(GoCtx = GoWired.Ctx, GoScope = GoScope)
        val GoSession = GoStream.GoOpenSession("device-close")

        // When it is closed twice
        GoStream.GoCloseSession(GoSession.GoSessionId)
        GoStream.GoCloseSession(GoSession.GoSessionId)
        advanceUntilIdle()

        // Then the STT port was released exactly once and the session is gone
        assertThat(GoWired.Stt.GoClosedSessions).containsExactly(GoSession.GoSessionId)
        assertThat(GoStream.GoStats().GoLiveSessions).isEqualTo(0)
        GoScope.cancel()
    }

    @Test
    fun `pushing to an unknown session is a no-op and changes no counters`() = runTest {
        // Given a relay with no sessions
        val GoWired = GoWiredContext()
        val GoStream = StreamServiceImpl(GoWired.Ctx)
        val GoBefore = GoStream.GoStats()

        // When audio and video are pushed against an unknown id
        GoStream.GoPushAudio("no-such-session", GoPcm(1), StreamService.GoAudioSampleRateHz)
        GoStream.GoPushVideo("no-such-session", GoNal(1))
        val GoResults = GoStream.GoResults("no-such-session").toList()

        // Then nothing happened and nothing was counted
        assertThat(GoStream.GoStats()).isEqualTo(GoBefore)
        assertThat(GoStream.GoStats().GoLiveSessions).isEqualTo(0)
        assertThat(GoResults).isEmpty()
    }

    // ------------------------------------------------------------- RPC-surface case

    @Test
    fun `mid stream disconnect closes the session and leaves no live work`() {
        // Given a real in-process gRPC surface whose relay runs on an inspectable scope
        val GoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val GoCtx = Context()
        val GoStt = FakeSttPort("relay utterance")
        val GoSink = FakeFrameSink()
        val GoPairing = PairingServiceImpl(GoCtx, GoClock = { System.currentTimeMillis() })
        val GoStream = StreamServiceImpl(GoCtx, GoScope = GoScope)
        Register<DeviceRepository>(GoCtx, FakeDeviceRepository())
        Register<PairingService>(GoCtx, GoPairing)
        Register<StreamService>(GoCtx, GoStream)
        Register<SttPort>(GoCtx, GoStt)
        Register<FrameSink>(GoCtx, GoSink)

        val GoName = InProcessServerBuilder.generateName()
        val GoServer: Server = InProcessServerBuilder.forName(GoName)
            .directExecutor()
            .addService(PairingGrpcService(GoCtx))
            .addService(StreamGrpcService(GoCtx))
            .intercept(AuthInterceptor(GoPairing))
            .build()
            .start()
        val GoChannel: ManagedChannel = InProcessChannelBuilder.forName(GoName).directExecutor().build()

        try {
            val GoToken = runBlocking { GoPair(GoChannel, GoPairing) }

            // When the peer ends the stream early (two frames, then completion)
            runBlocking {
                StreamServiceGrpcKt.StreamServiceCoroutineStub(GoChannel)
                    .openStream(flowOf(GoAudioFrame(1), GoAudioFrame(2)), GoBearer(GoToken))
                    .toList()
            }

            // Then the session was torn down exactly once and no scope child is left active
            assertThat(GoStream.GoStats().GoLiveSessions).isEqualTo(0)
            assertThat(GoStt.GoClosedSessions).hasSize(1)
            val GoLive = GoScope.coroutineContext[Job]!!.children.toList().filter { it.isActive }
            assertThat(GoLive).isEmpty()
        } finally {
            GoChannel.shutdownNow()
            GoServer.shutdownNow()
            GoChannel.awaitTermination(5, TimeUnit.SECONDS)
            GoServer.awaitTermination(5, TimeUnit.SECONDS)
            GoScope.cancel()
        }
    }

    // ---------------------------------------------------------------------- helpers

    private class GoWired(val Ctx: Context, val Stt: FakeSttPort, val Sink: FakeFrameSink)

    private fun GoWiredContext(): GoWired {
        val GoCtx = Context()
        val GoStt = FakeSttPort("relay utterance")
        val GoSink = FakeFrameSink()
        Register<SttPort>(GoCtx, GoStt)
        Register<FrameSink>(GoCtx, GoSink)
        return GoWired(GoCtx, GoStt, GoSink)
    }

    private fun GoTestScope(scheduler: TestCoroutineScheduler): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(scheduler))

    private fun GoPcm(index: Int): ByteArray = ByteArray(4) { ((index + it) % 128).toByte() }

    private fun GoNal(index: Int): ByteArray =
        byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65) + ByteArray(16) { ((index * 13 + it) % 251).toByte() }

    private fun GoAudioFrame(index: Int): StreamFrame = StreamFrame.newBuilder()
        .setAudioPcm1616K(ByteString.copyFrom(GoPcm(index)))
        .build()

    private fun GoBearer(token: String): Metadata = Metadata().apply { put(GoAuthKey, "Bearer $token") }

    private suspend fun GoPair(channel: ManagedChannel, pairing: PairingServiceImpl): String {
        val GoWindow = pairing.GoOpenWindow(ttlMs = 60_000)
        val GoRequest = PairRequest.newBuilder()
            .setPin(GoWindow.GoPin)
            .setDeviceName("relay-peer")
            .setRole(DeviceRole.DEVICE_ROLE_GLASS)
            .build()
        val GoPaired = PairingServiceGrpcKt.PairingServiceCoroutineStub(channel).pair(GoRequest)
        check(GoPaired.ok) { "test setup failed to pair: ${GoPaired.rejectReason}" }
        return GoPaired.token
    }
}
