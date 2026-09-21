package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.core.domain.context.Context
import com.glassstorm.phonemanager.core.domain.context.register
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.service.PairingServiceImpl
import com.glassstorm.phonemanager.core.service.StreamServiceImpl
import com.glassstorm.phonemanager.service.security.AuthInterceptor
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.util.concurrent.TimeUnit

/**
 * Relay hardening suite: bounded queues, drop-oldest video, park-based audio,
 * drain-on-close, and exactly-once STT teardown.
 *
 * The queue-policy cases drive a [StreamServiceImpl] whose `scope` is a
 * `StandardTestDispatcher` the test controls, so the drop/backpressure outcomes are
 * race-free: the pumps are simply not scheduled until the test advances them.
 * The RPC-surface case uses T5's in-process gRPC pattern with `directExecutor`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamRelayTest {
    @get:Rule
    val deadline: Timeout = Timeout.seconds(60)

    private val authKey: Metadata.Key<String> =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    // -------------------------------------------------------- queue policy cases

    @Test
    fun `twenty audio frames all reach the stt port and emit transcripts`() =
        runTest {
            // Given a relay with a controlled scope and 20 queued audio frames
            val wired = wiredContext()
            val scope = testScope(testScheduler)
            val stream = StreamServiceImpl(wired.Ctx, scope = scope)
            val session = stream.openSession("device-audio")
            val seen = mutableListOf<String>()
            val collector = launch { stream.results(session.sessionId).collect { seen += it.text } }

            // When they are pushed and the pumps run
            repeat(20) { stream.pushAudio(session.sessionId, pcm(it), StreamService.AUDIO_SAMPLE_RATE_HZ) }
            advanceUntilIdle()

            // Then nothing was dropped and every frame produced an utterance
            assertThat(stream.stats().audioFrames).isEqualTo(20L)
            assertThat(stream.stats().transcripts).isEqualTo(20L)
            assertThat(seen).hasSize(20)

            // And the results flow completes once the session closes
            stream.closeSession(session.sessionId)
            advanceUntilIdle()
            collector.join()
            scope.cancel()
        }

    @Test
    fun `twenty video nals arrive byte for byte identical and nothing is dropped`() =
        runTest {
            // Given 20 distinct opaque H.264 NALs
            val wired = wiredContext()
            val scope = testScope(testScheduler)
            val stream = StreamServiceImpl(wired.Ctx, scope = scope)
            val session = stream.openSession("device-video")
            val inputs = (0 until 20).map { nal(it) }

            // When they are pushed and the pump runs
            inputs.forEach { stream.pushVideo(session.sessionId, it) }
            advanceUntilIdle()

            // Then the sink saw exactly those bytes, unchanged, and nothing was evicted:
            // offered == evicted-free, so videoFrames is the delivered count here
            assertThat(stream.stats().videoFrames).isEqualTo(20L)
            assertThat(stream.stats().videoDropped).isEqualTo(0L)
            assertThat(wired.Sink.videoNals).hasSize(20)
            wired.Sink.videoNals.forEachIndexed { index, nal ->
                assertThat(nal).isEqualTo(inputs[index])
            }

            stream.closeSession(session.sessionId)
            advanceUntilIdle()
            scope.cancel()
        }

    @Test
    fun `full video queue evicts oldest while audio is never dropped`() =
        runTest {
            // Given a relay with a tiny video buffer and a PAUSED scope (no consumer yet)
            val wired = wiredContext()
            val scope = testScope(testScheduler)
            val stream =
                StreamServiceImpl(
                    ctx = wired.Ctx,
                    audioCapacity = 64,
                    videoCapacity = 4,
                    scope = scope,
                )
            val session = stream.openSession("device-pressure")

            // When 5 audio frames and 20 video NALs are pushed before any pump runs
            repeat(5) { stream.pushAudio(session.sessionId, pcm(it), StreamService.AUDIO_SAMPLE_RATE_HZ) }
            repeat(20) { stream.pushVideo(session.sessionId, nal(it)) }

            // Then videoFrames counts every NAL OFFERED to the live session — all 20,
            // including the 16 that drop-oldest evicted (counted in videoDropped)
            assertThat(stream.stats().audioFrames).isEqualTo(5L)
            assertThat(stream.stats().videoFrames).isEqualTo(20L)
            assertThat(stream.stats().videoDropped).isEqualTo(16L)

            // And the survivors still in the queue are the LAST 4 offered, in order:
            // the pump then delivers exactly those, proving drop-oldest kept the edge
            assertThat(wired.Sink.videoNals).isEmpty()
            advanceUntilIdle()
            assertThat(wired.Sink.videoNals).hasSize(4)
            assertThat(wired.Sink.videoNals.map { it.toList() })
                .containsExactlyElementsIn((16 until 20).map { nal(it).toList() })
                .inOrder()

            // The offered/evicted totals are unchanged by draining: they are offer-side
            assertThat(stream.stats().videoFrames).isEqualTo(20L)
            assertThat(stream.stats().videoDropped).isEqualTo(16L)

            scope.cancel()
        }

    @Test
    fun `closing drains already queued frames before returning`() =
        runTest {
            // Given frames enqueued while the pumps are not yet scheduled
            val wired = wiredContext()
            val scope = testScope(testScheduler)
            val stream = StreamServiceImpl(ctx = wired.Ctx, scope = scope)
            val session = stream.openSession("device-drain")
            repeat(8) { stream.pushAudio(session.sessionId, pcm(it), StreamService.AUDIO_SAMPLE_RATE_HZ) }
            repeat(8) { stream.pushVideo(session.sessionId, nal(it)) }

            // Then nothing has been consumed yet (proves the frames really were queued)
            assertThat(wired.Stt.audioFrameCount).isEqualTo(0)
            assertThat(wired.Sink.videoNals).isEmpty()

            // When the session is closed
            stream.closeSession(session.sessionId)

            // Then the queued frames were DRAINED (not discarded) before close returned
            assertThat(wired.Stt.audioFrameCount).isEqualTo(8)
            assertThat(wired.Sink.videoNals).hasSize(8)
            assertThat(stream.stats().audioFrames).isEqualTo(8L)
            scope.cancel()
        }

    // ------------------------------------------------------- teardown / no-op cases

    @Test
    fun `closing twice is a no-op and the stt session is released exactly once`() =
        runTest {
            // Given an open session
            val wired = wiredContext()
            val scope = testScope(testScheduler)
            val stream = StreamServiceImpl(ctx = wired.Ctx, scope = scope)
            val session = stream.openSession("device-close")

            // When it is closed twice
            stream.closeSession(session.sessionId)
            stream.closeSession(session.sessionId)
            advanceUntilIdle()

            // Then the STT port was released exactly once and the session is gone
            assertThat(wired.Stt.closedSessions).containsExactly(session.sessionId)
            assertThat(stream.stats().liveSessions).isEqualTo(0)
            scope.cancel()
        }

    @Test
    fun `pushing to an unknown session is a no-op and changes no counters`() =
        runTest {
            // Given a relay with no sessions
            val wired = wiredContext()
            val stream = StreamServiceImpl(wired.Ctx)
            val before = stream.stats()

            // When audio and video are pushed against an unknown id
            stream.pushAudio("no-such-session", pcm(1), StreamService.AUDIO_SAMPLE_RATE_HZ)
            stream.pushVideo("no-such-session", nal(1))
            val results = stream.results("no-such-session").toList()

            // Then nothing happened and nothing was counted
            assertThat(stream.stats()).isEqualTo(before)
            assertThat(stream.stats().liveSessions).isEqualTo(0)
            assertThat(results).isEmpty()
        }

    // ------------------------------------------------------------- RPC-surface case

    @Test
    fun `mid stream disconnect closes the session and leaves no live work`() {
        // Given a real in-process gRPC surface whose relay runs on an inspectable scope
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ctx = Context()
        val stt = FakeSttPort("relay utterance")
        val sink = FakeFrameSink()
        val pairing = PairingServiceImpl(ctx, clock = { System.currentTimeMillis() })
        val stream = StreamServiceImpl(ctx, scope = scope)
        register<DeviceRepository>(ctx, FakeDeviceRepository())
        register<PairingService>(ctx, pairing)
        register<StreamService>(ctx, stream)
        register<SttPort>(ctx, stt)
        register<FrameSink>(ctx, sink)

        val name = InProcessServerBuilder.generateName()
        val server: Server =
            InProcessServerBuilder
                .forName(name)
                .directExecutor()
                .addService(PairingGrpcService(ctx))
                .addService(StreamGrpcService(ctx))
                .intercept(AuthInterceptor(pairing))
                .build()
                .start()
        val channel: ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()

        try {
            val token = runBlocking { pair(channel, pairing) }

            // When the peer ends the stream early (two frames, then completion)
            runBlocking {
                StreamServiceGrpcKt
                    .StreamServiceCoroutineStub(channel)
                    .openStream(flowOf(audioFrame(1), audioFrame(2)), bearer(token))
                    .toList()
            }

            // Then the session was torn down exactly once and no scope child is left active
            assertThat(stream.stats().liveSessions).isEqualTo(0)
            assertThat(stt.closedSessions).hasSize(1)
            val live =
                scope.coroutineContext[Job]!!
                    .children
                    .toList()
                    .filter { it.isActive }
            assertThat(live).isEmpty()
        } finally {
            channel.shutdownNow()
            server.shutdownNow()
            channel.awaitTermination(5, TimeUnit.SECONDS)
            server.awaitTermination(5, TimeUnit.SECONDS)
            scope.cancel()
        }
    }

    // ---------------------------------------------------------------------- helpers

    private class Wired(
        val Ctx: Context,
        val Stt: FakeSttPort,
        val Sink: FakeFrameSink,
    )

    private fun wiredContext(): Wired {
        val ctx = Context()
        val stt = FakeSttPort("relay utterance")
        val sink = FakeFrameSink()
        register<SttPort>(ctx, stt)
        register<FrameSink>(ctx, sink)
        return Wired(ctx, stt, sink)
    }

    private fun testScope(scheduler: TestCoroutineScheduler): CoroutineScope = CoroutineScope(StandardTestDispatcher(scheduler))

    private fun pcm(index: Int): ByteArray = ByteArray(4) { ((index + it) % 128).toByte() }

    private fun nal(index: Int): ByteArray =
        byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65) + ByteArray(16) { ((index * 13 + it) % 251).toByte() }

    private fun audioFrame(index: Int): StreamFrame =
        StreamFrame
            .newBuilder()
            .setAudioPcm1616K(ByteString.copyFrom(pcm(index)))
            .build()

    private fun bearer(token: String): Metadata = Metadata().apply { put(authKey, "Bearer $token") }

    private suspend fun pair(
        channel: ManagedChannel,
        pairing: PairingServiceImpl,
    ): String {
        val window = pairing.openWindow(ttlMs = 60_000)
        val request =
            PairRequest
                .newBuilder()
                .setPin(window.pin)
                .setDeviceName("relay-peer")
                .setRole(DeviceRole.DEVICE_ROLE_GLASS)
                .build()
        val paired = PairingServiceGrpcKt.PairingServiceCoroutineStub(channel).pair(request)
        check(paired.ok) { "test setup failed to pair: ${paired.rejectReason}" }
        return paired.token
    }
}
