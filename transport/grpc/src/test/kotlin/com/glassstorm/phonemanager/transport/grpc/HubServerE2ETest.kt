package com.glassstorm.phonemanager.transport.grpc

import com.glassstorm.phonemanager.testing.testkit.ProcessRun
import com.glassstorm.phonemanager.testing.testkit.REAP_SECONDS
import com.glassstorm.phonemanager.testing.testkit.goBinary
import com.glassstorm.phonemanager.testing.testkit.repoRoot
import com.glassstorm.phonemanager.testing.testkit.run
import com.glassstorm.phonemanager.testing.testkit.syntheticVideoNal
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
import java.util.concurrent.TimeUnit

/**
 * The T6 transport spike: the REAL gRPC server on a REAL JVM socket, driven by
 * the REAL Go CLI peer `tools/mockpeer` over a REAL TCP connection.
 *
 * This is the project's single biggest risk gate (issues.md R1): a gRPC *server*
 * on Android is unofficial and only the netty-shaded NIO plaintext path is
 * expected to work. Nothing here is mocked — no in-process transport, no fake
 * channel, no faked stdout. The only fakes are the T5 domain-port doubles
 * ([FakeSpeechPort], [FakeFrameSink]), exactly as an Android composition would
 * supply real adapters. The stream is UNAUTHENTICATED: pairing/auth were removed
 * from the hub runtime, so the peer bootstraps with a `stream_open` frame.
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
    val deadline: Timeout = Timeout.seconds(180)

    private lateinit var hub: GrpcHubServer
    private lateinit var speech: FakeSpeechPort
    private lateinit var sink: FakeFrameSink
    private var port: Int = 0

    /** Every child process this test class launched, so [@After] can guarantee reaping. */
    private val children: MutableList<Process> = mutableListOf()

    @Before
    fun startRealHub() {
        // Fail LOUDLY when the Go toolchain is missing — never skip silently,
        // because a skipped transport spike would report a green build over an
        // unproven risk.
        val probe = run(listOf(goBinary(), "version"), File(System.getProperty("user.dir")))
        assertWithMessage("Go toolchain unavailable; T6 cannot run: ${probe.stderr}")
            .that(probe.ok)
            .isTrue()

        // Given a hand-wired graph over domain-port fakes only (no :adapter edge)
        speech = FakeSpeechPort("mockpeer recognized utterance")
        sink = FakeFrameSink()
        val stream = relayStream(speech = speech, sink = sink)

        // The transport under test: netty-shaded NIO, IPv4 explicit, ephemeral port.
        hub =
            GrpcHubServer { port ->
                NettyServerBuilder
                    .forAddress(InetSocketAddress("127.0.0.1", port))
                    .addService(StreamGrpcService(stream))
            }
        hub.start(0)
        port = hub.boundPort()
        assertThat(port).isGreaterThan(0)
    }

    @After
    fun stopRealHub() {
        // repeated_interruptions: reap every child, alive or not. flaky_tests:
        // releasing the server socket lets the next ephemeral bind never collide.
        children.forEach { child ->
            if (child.isAlive) child.destroyForcibly()
            child.waitFor(REAP_SECONDS, TimeUnit.SECONDS)
        }
        children.clear()
        if (this::hub.isInitialized) hub.stop()
    }

    // ---------------------------------------------------------------- required cases

    @Test
    fun `full scenario opens an unauthenticated stream and returns transcripts`() {
        // When the Go peer walks the full protocol over TCP with NO token
        val run =
            mockPeer(
                "--addr",
                "127.0.0.1:$port",
                "--scenario",
                "full",
                "--mode",
                "audio",
                "--frames",
                "10",
                "--timeout",
                "60",
            )

        // Then its RAW stdout carries the machine-checkable success line
        println("[QA] mockpeer full stdout: ${run.stdout.trim()}")
        assertWithMessage("mockpeer stderr: ${run.stderr}")
            .that(run.exitCode)
            .isEqualTo(0)

        val frames = count(run.stdout, "frames")
        assertThat(run.stdout).contains("session-ok frames=")
        assertWithMessage("raw stdout: ${run.stdout}")
            .that(frames)
            .isAtLeast(10)
        assertWithMessage("no transcript frame came back from the hub: ${run.stdout}")
            .that(count(run.stdout, "transcripts"))
            .isAtLeast(1)

        // And the audio really reached the speech port on the hub side
        assertThat(speech.audioFrameCount).isAtLeast(10)
    }

    @Test
    fun `full scenario with audio and video passes opaque video frames through byte for byte`() {
        // When the peer sends BOTH media kinds
        val run =
            mockPeer(
                "--addr",
                "127.0.0.1:$port",
                "--scenario",
                "full",
                "--mode",
                "both",
                "--frames",
                "10",
                "--timeout",
                "60",
            )

        // Then all 20 frames (10 audio + 10 video) were accepted and transcripts came back
        println("[QA] mockpeer both stdout: ${run.stdout.trim()}")
        assertWithMessage("mockpeer stderr: ${run.stderr}")
            .that(run.exitCode)
            .isEqualTo(0)
        assertThat(count(run.stdout, "frames")).isEqualTo(20)

        // And the video sink saw exactly the bytes the Go peer generated (opaque, unchanged)
        assertThat(sink.videoNals).hasSize(10)
        sink.videoNals.forEachIndexed { index, nal ->
            assertWithMessage("video NAL #$index was mutated in transit")
                .that(nal)
                .isEqualTo(syntheticVideoNal(index))
        }
    }

    // ---------------------------------------------------------------- harness helpers

    // `go run ./tools/mockpeer` resolves only from the repo root, via root go.work.
    private fun mockPeer(vararg args: String): ProcessRun {
        val command = listOf(goBinary(), "run", "./tools/mockpeer") + args
        val result = run(command, repoRoot())
        result.process?.let { children += it }
        return result
    }

    /** Parse `name=<int>` out of the CLI's stdout, failing loudly when absent. */
    private fun count(
        stdout: String,
        name: String,
    ): Int {
        val match = Regex("""$name=(\d+)""").find(stdout)
        assertWithMessage("stdout did not carry `$name=<int>`: $stdout").that(match).isNotNull()
        return match!!.groupValues[1].toInt()
    }
}
