package com.glassstorm.phonemanager.transport.grpc

import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.testing.testkit.MockPeerDriver
import com.glassstorm.phonemanager.testing.testkit.MockPeerTranscript
import com.glassstorm.phonemanager.testing.testkit.goBinary
import com.glassstorm.phonemanager.testing.testkit.repoRoot
import com.glassstorm.phonemanager.testing.testkit.run
import com.glassstorm.phonemanager.testing.testkit.syntheticVideoNal
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File

/**
 * T18 harness (a): the FULL scenario against the plain-JVM hub.
 *
 * Same real-socket posture as T6's [HubServerE2ETest] — a REAL netty-shaded
 * listener on `127.0.0.1:0` driven by the REAL Go peer — but now over the WHOLE
 * protocol. The stream is UNAUTHENTICATED: pairing, PIN and bearer tokens were
 * removed from the hub runtime, so there is no pair/heartbeat/revoke leg and the
 * peer bootstraps with a `stream_open` frame.
 *
 * ## The E2E override pattern (shared with the `:app` harness, deliberately)
 *
 * Both E2E suites construct the listener through the production [HubServerAdapter]
 * with an explicit [HubServerAdapter.LOOPBACK_ADDRESS] bind, and hand it a
 * RECORDING [FrameSink]. This is the correct Dagger approach: a component binding
 * can never be overridden per-instance, so the two test-only collaborators (the
 * wildcard-vs-loopback bind and the observable sink) are injected by COMPOSITION,
 * not by a second graph. The production component supplies the real ports on the
 * `:app` side; this module hand-wires the same ports. `:app`'s `FullE2eTest` uses
 * the identical shape over its component, so the two are directly comparable.
 *
 * The ordered success transcript `stream-open-ok` / `session-ok frames=N` /
 * `video-bytes-match` is both ASSERTED here and appended to the shared evidence
 * file. Byte-equality is a REAL comparison of the recorded NALs against the Go
 * generator's output. The `:app` Robolectric harness writes its own block with the
 * same contract, so the two are directly comparable.
 */
class FullE2eTest {
    @get:Rule
    val deadline: Timeout = Timeout.seconds(300)

    private lateinit var hub: HubServer
    private lateinit var speech: FakeSpeechPort
    private lateinit var sink: FakeFrameSink
    private var driver: MockPeerDriver? = null
    private var port: Int = 0

    @Before
    fun startRealHub() {
        val probe = run(listOf(goBinary(), "version"), File(System.getProperty("user.dir")))
        assertWithMessage("Go toolchain unavailable; T18 cannot run: ${probe.stderr}")
            .that(probe.ok)
            .isTrue()

        speech = FakeSpeechPort("full e2e utterance")
        sink = FakeFrameSink()
        val stream = relayStream(speech = speech, sink = sink)

        // The PRODUCTION adapter with an explicit loopback bind, exactly as the
        // `:app` harness composes it. The wildcard production bind is never used
        // in a test; only the bind address differs from `TransportModule`.
        hub =
            HubServerAdapter(
                streamService = StreamGrpcService(stream),
                bindAddress = HubServerAdapter.LOOPBACK_ADDRESS,
            )
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
        // Given a driver bound to the live hub
        driver = MockPeerDriver(port = port)

        // When the peer opens the unauthenticated stream and sends 10 audio + 10 video frames
        val run = driver!!.openStream(frames = 10)
        println("[QA] mockpeer full stdout:\n${run.stdout.trim()}")
        assertWithMessage("mockpeer stderr: ${run.stderr}")
            .that(run.exitCode)
            .isEqualTo(0)

        // Then its RAW stdout carries the ordered bootstrap/session lines
        assertThat(run.stdout).contains("stream-open-ok")
        assertThat(run.stdout).contains("session-ok frames=")
        assertThat(driver!!.fullLines(run))
            .containsExactly(
                "stream-open-ok",
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

        // And the audio really reached speech and produced results on the wire
        assertThat(speech.audioFrameCount).isAtLeast(10)
        assertThat(run.stdout).contains("transcripts=")

        // And the ordered evidence transcript is emitted
        val transcript =
            MockPeerTranscript.lines(
                streamOpenOk = true,
                frames = 20,
                videoBytesMatch = bytesMatch,
            )
        println("[QA] service evidence transcript: $transcript")
        MockPeerTranscript.write(evidencePath(), "service-full-e2e (jvm harness)", transcript)
        assertThat(transcript)
            .containsExactly(
                "stream-open-ok",
                "session-ok frames=20",
                "video-bytes-match",
            ).inOrder()
    }

    private fun evidencePath(): String = File(repoRoot(), EVIDENCE_RELATIVE).absolutePath

    private companion object {
        const val EVIDENCE_RELATIVE: String = ".omo/evidence/task-18-phone-manager-bootstrap.txt"
    }
}
