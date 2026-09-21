package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.core.domain.context.Context
import com.glassstorm.phonemanager.core.domain.context.register
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.service.security.AuthInterceptor
import com.glassstorm.phonemanager.testkit.MockPeerDriver
import com.glassstorm.phonemanager.testkit.MockPeerTranscript
import com.glassstorm.phonemanager.testkit.goBinary
import com.glassstorm.phonemanager.testkit.repoRoot
import com.glassstorm.phonemanager.testkit.run
import com.glassstorm.phonemanager.testkit.syntheticVideoNal
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.net.InetSocketAddress

/**
 * T18 harness (a): the FULL scenario against the plain-JVM hub.
 *
 * Same real-socket posture as T6's [HubServerE2ETest] — a REAL netty-shaded
 * listener on `127.0.0.1:0` driven by the REAL Go peer — but now over the WHOLE
 * protocol, including the two legs T6 never covered:
 *
 *  1. the ordered success transcript `pair-ok` / `heartbeat-ok` /
 *     `session-ok frames=N` / `video-bytes-match`, where byte-equality is a REAL
 *     comparison of the recorded NALs against the Go generator's output;
 *  2. revocation: the token minted in leg 1 is revoked, and a second invocation
 *     using that token MUST be refused with `UNAUTHENTICATED`.
 *
 * The ordered lines are both ASSERTED here and appended to the shared evidence
 * file. The `:app` Robolectric harness writes its own block with the same
 * contract, so the two are directly comparable.
 */
class FullE2eTest {
    @get:Rule
    val deadline: Timeout = Timeout.seconds(300)

    private lateinit var hub: GrpcHubServer
    private lateinit var pairing: PairingServiceImpl
    private lateinit var repo: FakeDeviceRepository
    private lateinit var stt: FakeSttPort
    private lateinit var sink: FakeFrameSink
    private lateinit var ctx: Context
    private var driver: MockPeerDriver? = null
    private var port: Int = 0

    @Before
    fun startRealHub() {
        val probe = run(listOf(goBinary(), "version"), File(System.getProperty("user.dir")))
        assertWithMessage("Go toolchain unavailable; T18 cannot run: ${probe.stderr}")
            .that(probe.ok)
            .isTrue()

        ctx = Context()
        repo = FakeDeviceRepository()
        stt = FakeSttPort("full e2e utterance")
        sink = FakeFrameSink()
        pairing = PairingServiceImpl(ctx, clock = { System.currentTimeMillis() })
        register<DeviceRepository>(ctx, repo)
        register<PairingService>(ctx, pairing)
        register<StreamService>(ctx, StreamServiceImpl(ctx))
        register<SttPort>(ctx, stt)
        register<FrameSink>(ctx, sink)

        hub =
            GrpcHubServer { port ->
                NettyServerBuilder
                    .forAddress(InetSocketAddress("127.0.0.1", port))
                    .addService(PairingGrpcService(ctx))
                    .addService(StreamGrpcService(ctx))
                    .intercept(AuthInterceptor(pairing))
            }
        hub.start(0)
        port = hub.boundPort()
        assertThat(port).isGreaterThan(0)
    }

    @After
    fun stopRealHub() {
        driver?.reap()
        driver = null
        if (this::hub.isInitialized) hub.stop()
    }

    @Test
    fun `the full scenario produces the ordered transcript and byte exact video`() {
        // Given an open pairing window and a driver bound to the live hub
        driver =
            MockPeerDriver(
                port = port,
                openWindow = { pairing.openWindow(ttlMs = 120_000).pin },
                revokeAll = { pairing.listPaired().forEach { pairing.revoke(it.deviceId) } },
            )

        // When the peer pairs, heartbeats and streams 10 audio + 10 video frames
        val run = driver!!.pairHeartbeatStream(frames = 10)
        println("[QA] mockpeer full stdout:\n${run.stdout.trim()}")
        assertWithMessage("mockpeer stderr: ${run.stderr}")
            .that(run.exitCode)
            .isEqualTo(0)

        // Then its RAW stdout carries the ordered pair/heartbeat/session lines
        val lines =
            run.stdout
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        assertThat(lines).containsAtLeast("pair-ok", "heartbeat-ok").inOrder()
        assertThat(run.stdout).contains("session-ok frames=")
        assertThat(driver!!.fullLines(run))
            .containsExactly(
                "pair-ok",
                "heartbeat-ok",
                "session-ok frames=20",
            ).inOrder()

        // And the video sink recorded the EXACT bytes the Go peer generated —
        // a real equality check per index, not a length check.
        assertThat(sink.videoNals).hasSize(10)
        val bytesMatch =
            sink.videoNals.withIndex().all { (index, nal) ->
                nal.contentEquals(syntheticVideoNal(index))
            }
        sink.videoNals.forEachIndexed { index, nal ->
            assertWithMessage("video NAL #$index was mutated in transit")
                .that(nal)
                .isEqualTo(syntheticVideoNal(index))
        }
        assertThat(bytesMatch).isTrue()

        // And the audio really reached STT and produced transcripts on the wire
        assertThat(stt.audioFrameCount).isAtLeast(10)
        assertThat(run.stdout).contains("transcripts=")

        // When the paired device is revoked and the SAME token is replayed
        val revoked = driver!!.revokedLeg()

        // Then the hub refuses it with the machine-checkable rejection
        println("[QA] mockpeer revoked stdout: ${revoked.stdout.trim()} (exit=${revoked.exitCode})")
        assertThat(revoked.exitCode).isNotEqualTo(0)
        assertThat(revoked.stdout).contains("UNAUTHENTICATED")
        val revokedOk = revoked.exitCode != 0 && revoked.stdout.contains("UNAUTHENTICATED")

        // And the ordered evidence transcript is emitted exactly once
        val transcript =
            MockPeerTranscript.lines(
                pairOk = true,
                heartbeatOk = true,
                frames = 20,
                videoBytesMatch = bytesMatch,
                revokedUnauthenticated = revokedOk,
            )
        println("[QA] service evidence transcript: $transcript")
        MockPeerTranscript.write(evidencePath(), "service-full-e2e (jvm harness)", transcript)
        assertThat(transcript)
            .containsExactly(
                "pair-ok",
                "heartbeat-ok",
                "session-ok frames=20",
                "video-bytes-match",
                "revoked->UNAUTHENTICATED",
            ).inOrder()
    }

    @Test
    fun `a wrong pin still fails pairing with a reason and a non zero exit`() {
        // Given an open window whose PIN is NOT 000000
        val window = pairing.openWindow(ttlMs = 120_000)
        assertThat(window.pin).isNotEqualTo("000000")
        val driver =
            MockPeerDriver(
                port = port,
                openWindow = { window.pin },
                revokeAll = {},
            )
        this.driver = driver

        // When the peer pairs with the wrong PIN
        val run =
            run(
                listOf(goBinary(), "run", "./tools/mockpeer") +
                    listOf(
                        "--addr",
                        "127.0.0.1:$port",
                        "--pin",
                        "000000",
                        "--scenario",
                        "pair",
                        "--timeout",
                        "30",
                    ),
                repoRoot(),
            )
        run.process?.let { driver.registerForReaping(it) }

        // Then the hub rejects with a machine-checkable reason, never a success
        println("[QA] mockpeer wrong-pin stdout: ${run.stdout.trim()} (exit=${run.exitCode})")
        assertThat(run.exitCode).isNotEqualTo(0)
        assertThat(run.stdout).contains("pair-rejected reason=")
    }

    private fun evidencePath(): String = File(repoRoot(), EVIDENCE_RELATIVE).absolutePath

    private companion object {
        const val EVIDENCE_RELATIVE: String = ".omo/evidence/task-18-phone-manager-bootstrap.txt"
    }
}
