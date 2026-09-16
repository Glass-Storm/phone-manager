package com.glassstorm.phonemanager.service

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.glassstorm.phonemanager.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.service.PairingService
import com.glassstorm.phonemanager.domain.service.StreamService
import com.glassstorm.phonemanager.service.security.AuthInterceptor
import com.glassstorm.phonemanager.testkit.GoGoBinary
import com.glassstorm.phonemanager.testkit.GoRepoRoot
import com.glassstorm.phonemanager.testkit.GoRun
import com.glassstorm.phonemanager.testkit.GoSyntheticVideoNal
import com.glassstorm.phonemanager.testkit.MockPeerDriver
import com.glassstorm.phonemanager.testkit.MockPeerTranscript
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.io.File
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

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
    val GoDeadline: Timeout = Timeout.seconds(300)

    private lateinit var GoHub: GrpcHubServer
    private lateinit var GoPairing: PairingServiceImpl
    private lateinit var GoRepo: FakeDeviceRepository
    private lateinit var GoStt: FakeSttPort
    private lateinit var GoSink: FakeFrameSink
    private lateinit var GoCtx: Context
    private var GoDriver: MockPeerDriver? = null
    private var GoPort: Int = 0

    @Before
    fun GoStartRealHub() {
        val GoProbe = GoRun(listOf(GoGoBinary(), "version"), File(System.getProperty("user.dir")))
        assertWithMessage("Go toolchain unavailable; T18 cannot run: ${GoProbe.stderr}")
            .that(GoProbe.ok)
            .isTrue()

        GoCtx = Context()
        GoRepo = FakeDeviceRepository()
        GoStt = FakeSttPort("full e2e utterance")
        GoSink = FakeFrameSink()
        GoPairing = PairingServiceImpl(GoCtx, GoClock = { System.currentTimeMillis() })
        Register<DeviceRepository>(GoCtx, GoRepo)
        Register<PairingService>(GoCtx, GoPairing)
        Register<StreamService>(GoCtx, StreamServiceImpl(GoCtx))
        Register<SttPort>(GoCtx, GoStt)
        Register<FrameSink>(GoCtx, GoSink)

        GoHub = GrpcHubServer { port ->
            NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", port))
                .addService(PairingGrpcService(GoCtx))
                .addService(StreamGrpcService(GoCtx))
                .intercept(AuthInterceptor(GoPairing))
        }
        GoHub.GoStart(0)
        GoPort = GoHub.GoBoundPort()
        assertThat(GoPort).isGreaterThan(0)
    }

    @After
    fun GoStopRealHub() {
        GoDriver?.GoReap()
        GoDriver = null
        if (this::GoHub.isInitialized) GoHub.GoStop()
    }

    @Test
    fun `the full scenario produces the ordered transcript and byte exact video`() {
        // Given an open pairing window and a driver bound to the live hub
        GoDriver = MockPeerDriver(
            GoPort = GoPort,
            GoOpenWindow = { GoPairing.GoOpenWindow(ttlMs = 120_000).GoPin },
            GoRevokeAll = { GoPairing.GoListPaired().forEach { GoPairing.GoRevoke(it.GoDeviceId) } },
        )

        // When the peer pairs, heartbeats and streams 10 audio + 10 video frames
        val GoRun = GoDriver!!.GoPairHeartbeatStream(frames = 10)
        println("[QA] mockpeer full stdout:\n${GoRun.stdout.trim()}")
        assertWithMessage("mockpeer stderr: ${GoRun.stderr}")
            .that(GoRun.exitCode)
            .isEqualTo(0)

        // Then its RAW stdout carries the ordered pair/heartbeat/session lines
        val GoLines = GoRun.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertThat(GoLines).containsAtLeast("pair-ok", "heartbeat-ok").inOrder()
        assertThat(GoRun.stdout).contains("session-ok frames=")
        assertThat(GoDriver!!.GoFullLines(GoRun)).containsExactly(
            "pair-ok",
            "heartbeat-ok",
            "session-ok frames=20",
        ).inOrder()

        // And the video sink recorded the EXACT bytes the Go peer generated —
        // a real equality check per index, not a length check.
        assertThat(GoSink.GoVideoNals).hasSize(10)
        val GoBytesMatch = GoSink.GoVideoNals.withIndex().all { (index, nal) ->
            nal.contentEquals(GoSyntheticVideoNal(index))
        }
        GoSink.GoVideoNals.forEachIndexed { index, nal ->
            assertWithMessage("video NAL #$index was mutated in transit")
                .that(nal)
                .isEqualTo(GoSyntheticVideoNal(index))
        }
        assertThat(GoBytesMatch).isTrue()

        // And the audio really reached STT and produced transcripts on the wire
        assertThat(GoStt.GoAudioFrameCount).isAtLeast(10)
        assertThat(GoRun.stdout).contains("transcripts=")

        // When the paired device is revoked and the SAME token is replayed
        val GoRevoked = GoDriver!!.GoRevokedLeg()

        // Then the hub refuses it with the machine-checkable rejection
        println("[QA] mockpeer revoked stdout: ${GoRevoked.stdout.trim()} (exit=${GoRevoked.exitCode})")
        assertThat(GoRevoked.exitCode).isNotEqualTo(0)
        assertThat(GoRevoked.stdout).contains("UNAUTHENTICATED")
        val GoRevokedOk = GoRevoked.exitCode != 0 && GoRevoked.stdout.contains("UNAUTHENTICATED")

        // And the ordered evidence transcript is emitted exactly once
        val GoTranscript = MockPeerTranscript.GoLines(
            pairOk = true,
            heartbeatOk = true,
            frames = 20,
            videoBytesMatch = GoBytesMatch,
            revokedUnauthenticated = GoRevokedOk,
        )
        println("[QA] service evidence transcript: $GoTranscript")
        MockPeerTranscript.GoWrite(GoEvidencePath(), "service-full-e2e (jvm harness)", GoTranscript)
        assertThat(GoTranscript).containsExactly(
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
        val GoWindow = GoPairing.GoOpenWindow(ttlMs = 120_000)
        assertThat(GoWindow.GoPin).isNotEqualTo("000000")
        val GoDriver = MockPeerDriver(
            GoPort = GoPort,
            GoOpenWindow = { GoWindow.GoPin },
            GoRevokeAll = {},
        )
        this.GoDriver = GoDriver

        // When the peer pairs with the wrong PIN
        val GoRun = GoRun(
            listOf(GoGoBinary(), "run", "./tools/mockpeer") + listOf(
                "--addr", "127.0.0.1:$GoPort",
                "--pin", "000000",
                "--scenario", "pair",
                "--timeout", "30",
            ),
            GoRepoRoot(),
        )
        GoRun.process?.let { GoDriver.GoRegisterForReaping(it) }

        // Then the hub rejects with a machine-checkable reason, never a success
        println("[QA] mockpeer wrong-pin stdout: ${GoRun.stdout.trim()} (exit=${GoRun.exitCode})")
        assertThat(GoRun.exitCode).isNotEqualTo(0)
        assertThat(GoRun.stdout).contains("pair-rejected reason=")
    }

    private fun GoEvidencePath(): String = File(GoRepoRoot(), GO_EVIDENCE_RELATIVE).absolutePath

    private companion object {
        const val GO_EVIDENCE_RELATIVE: String = ".omo/evidence/task-18-phone-manager-bootstrap.txt"
    }
}
