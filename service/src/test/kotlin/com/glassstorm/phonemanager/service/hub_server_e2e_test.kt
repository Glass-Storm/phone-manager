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
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The T6 transport spike: the REAL gRPC server on a REAL JVM socket, driven by
 * the REAL Go CLI peer `tools/mockpeer` over a REAL TCP connection.
 *
 * This is the project's single biggest risk gate (issues.md R1): a gRPC *server*
 * on Android is unofficial and only the netty-shaded NIO plaintext path is
 * expected to work. Nothing here is mocked — no in-process transport, no fake
 * channel, no faked stdout. The only fakes are the T5 domain-port doubles
 * ([FakeDeviceRepository], [FakeSttPort], [FakeFrameSink]), exactly as an
 * Android composition would supply real adapters.
 *
 * ## Hygiene (the adversarial classes)
 *
 *  * the listener binds `127.0.0.1:0` — ephemeral, IPv4-explicit, never `0.0.0.0`;
 *  * every child process runs under a bounded [Process.waitFor] deadline and is
 *    force-destroyed if it overruns, so a hung hub cannot hang the suite;
 *  * the server is stopped in [@After] and the child process is reaped there too,
 *    so nothing leaks across tests or into the daemon.
 */
class HubServerE2ETest {

    @get:Rule
    val GoDeadline: Timeout = Timeout.seconds(180)

    private lateinit var GoHub: GrpcHubServer
    private lateinit var GoPairing: PairingServiceImpl
    private lateinit var GoRepo: FakeDeviceRepository
    private lateinit var GoStt: FakeSttPort
    private lateinit var GoSink: FakeFrameSink
    private lateinit var GoCtx: Context
    private var GoPort: Int = 0

    /** Every child process this test class launched, so [@After] can guarantee reaping. */
    private val GoChildren: MutableList<Process> = mutableListOf()

    @Before
    fun GoStartRealHub() {
        // Fail LOUDLY when the Go toolchain is missing — never skip silently,
        // because a skipped transport spike would report a green build over an
        // unproven risk.
        val GoProbe = GoRun(listOf(GoGoBinary(), "version"), File(System.getProperty("user.dir")))
        assertWithMessage("Go toolchain unavailable; T6 cannot run: ${GoProbe.stderr}")
            .that(GoProbe.ok)
            .isTrue()

        // Given a Context wired with domain-port fakes only (no :adapter edge)
        GoCtx = Context()
        GoRepo = FakeDeviceRepository()
        GoStt = FakeSttPort("mockpeer recognized utterance")
        GoSink = FakeFrameSink()
        GoPairing = PairingServiceImpl(GoCtx, GoClock = { System.currentTimeMillis() })
        Register<DeviceRepository>(GoCtx, GoRepo)
        Register<PairingService>(GoCtx, GoPairing)
        Register<StreamService>(GoCtx, StreamServiceImpl(GoCtx))
        Register<SttPort>(GoCtx, GoStt)
        Register<FrameSink>(GoCtx, GoSink)

        // The transport under test: netty-shaded NIO, IPv4 explicit, ephemeral port.
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
        // repeated_interruptions: reap every child, alive or not. flaky_tests:
        // releasing the server socket lets the next ephemeral bind never collide.
        GoChildren.forEach { child ->
            if (child.isAlive) child.destroyForcibly()
            child.waitFor(GO_REAP_SECONDS, TimeUnit.SECONDS)
        }
        GoChildren.clear()
        if (this::GoHub.isInitialized) GoHub.GoStop()
    }

    // ---------------------------------------------------------------- required cases

    @Test
    fun `full scenario pairs heartbeats and streams ten audio frames to transcripts`() {
        // Given an open pairing window on the real hub
        val GoWindow = GoPairing.GoOpenWindow(ttlMs = 120_000)

        // When the Go peer walks the full protocol over TCP
        val GoRun = GoMockPeer(
            "--addr", "127.0.0.1:$GoPort",
            "--pin", GoWindow.GoPin,
            "--scenario", "full",
            "--mode", "audio",
            "--frames", "10",
            "--timeout", "60",
        )

        // Then its RAW stdout carries the machine-checkable success line
        println("[QA] mockpeer full stdout: ${GoRun.stdout.trim()}")
        assertWithMessage("mockpeer stderr: ${GoRun.stderr}")
            .that(GoRun.exitCode)
            .isEqualTo(0)

        val GoFrames = GoCount(GoRun.stdout, "frames")
        assertThat(GoRun.stdout).contains("session-ok frames=")
        assertWithMessage("raw stdout: ${GoRun.stdout}")
            .that(GoFrames)
            .isAtLeast(10)
        assertWithMessage("no transcript frame came back from the hub: ${GoRun.stdout}")
            .that(GoCount(GoRun.stdout, "transcripts"))
            .isAtLeast(1)

        // And the audio really reached the STT port on the hub side
        assertThat(GoStt.GoAudioFrameCount).isAtLeast(10)
    }

    @Test
    fun `full scenario with audio and video passes opaque video frames through byte for byte`() {
        // Given an open pairing window
        val GoWindow = GoPairing.GoOpenWindow(ttlMs = 120_000)

        // When the peer sends BOTH media kinds
        val GoRun = GoMockPeer(
            "--addr", "127.0.0.1:$GoPort",
            "--pin", GoWindow.GoPin,
            "--scenario", "full",
            "--mode", "both",
            "--frames", "10",
            "--timeout", "60",
        )

        // Then all 20 frames (10 audio + 10 video) were accepted and transcripts came back
        println("[QA] mockpeer both stdout: ${GoRun.stdout.trim()}")
        assertWithMessage("mockpeer stderr: ${GoRun.stderr}")
            .that(GoRun.exitCode)
            .isEqualTo(0)
        assertThat(GoCount(GoRun.stdout, "frames")).isEqualTo(20)

        // And the video sink saw exactly the bytes the Go peer generated (opaque, unchanged)
        assertThat(GoSink.GoVideoNals).hasSize(10)
        GoSink.GoVideoNals.forEachIndexed { index, nal ->
            assertWithMessage("video NAL #$index was mutated in transit")
                .that(nal)
                .isEqualTo(SyntheticVideoNal(index))
        }
    }

    @Test
    fun `heartbeat with no token is rejected as UNAUTHENTICATED with a non zero exit`() {
        // Given a paired hub and an open stream
        GoPairing.GoOpenWindow(ttlMs = 120_000)

        // When the peer deliberately sends NO bearer metadata
        val GoRun = GoMockPeer(
            "--addr", "127.0.0.1:$GoPort",
            "--no-token",
            "--timeout", "30",
        )

        // Then the hub refuses and the CLI says exactly so
        println("[QA] mockpeer no-token stdout: ${GoRun.stdout.trim()} (exit=${GoRun.exitCode})")
        assertThat(GoRun.exitCode).isNotEqualTo(0)
        assertThat(GoRun.stdout).contains("UNAUTHENTICATED")
    }

    @Test
    fun `pairing with a wrong pin is rejected with a reason and a non zero exit`() {
        // Given an open window whose PIN is NOT 000000
        val GoWindow = GoPairing.GoOpenWindow(ttlMs = 120_000)
        assertThat(GoWindow.GoPin).isNotEqualTo("000000")

        // When the peer pairs with the wrong PIN
        val GoRun = GoMockPeer(
            "--addr", "127.0.0.1:$GoPort",
            "--pin", "000000",
            "--scenario", "pair",
            "--timeout", "30",
        )

        // Then the hub rejects with a machine-checkable reason
        println("[QA] mockpeer wrong-pin stdout: ${GoRun.stdout.trim()} (exit=${GoRun.exitCode})")
        assertThat(GoRun.exitCode).isNotEqualTo(0)
        assertThat(GoRun.stdout).contains("pair-rejected reason=")
    }

    // ---------------------------------------------------------------- harness helpers

    // `go run ./tools/mockpeer` resolves only from the repo root, via root go.work.
    private fun GoMockPeer(vararg args: String): GoProcessRun {
        val GoCommand = listOf(GoGoBinary(), "run", "./tools/mockpeer") + args
        val GoResult = GoRun(GoCommand, GoRepoRoot())
        GoResult.process?.let { GoChildren += it }
        return GoResult
    }

    /** Parse `name=<int>` out of the CLI's stdout, failing loudly when absent. */
    private fun GoCount(stdout: String, name: String): Int {
        val GoMatch = Regex("""$name=(\d+)""").find(stdout)
        assertWithMessage("stdout did not carry `$name=<int>`: $stdout").that(GoMatch).isNotNull()
        return GoMatch!!.groupValues[1].toInt()
    }

    /** Re-derive the Go generator's deterministic video NAL to prove byte-exactness. */
    private fun SyntheticVideoNal(index: Int): ByteArray {
        val GoNal = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65)
        val GoBody = ByteArray(24) { i -> ((index * 17 + i) % 251).toByte() }
        return GoNal + GoBody
    }
}
