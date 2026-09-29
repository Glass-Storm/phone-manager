package com.glassstorm.phonemanager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.adapter.jvm.speech.SttFactory
import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.service.StreamServiceImpl
import com.glassstorm.phonemanager.testing.testkit.MockPeerDriver
import com.glassstorm.phonemanager.testing.testkit.MockPeerTranscript
import com.glassstorm.phonemanager.testing.testkit.goBinary
import com.glassstorm.phonemanager.testing.testkit.repoRoot
import com.glassstorm.phonemanager.testing.testkit.run
import com.glassstorm.phonemanager.testing.testkit.syntheticVideoNal
import com.glassstorm.phonemanager.transport.grpc.HubServerAdapter
import com.glassstorm.phonemanager.transport.grpc.PairingGrpcService
import com.glassstorm.phonemanager.transport.grpc.StreamGrpcService
import com.glassstorm.phonemanager.transport.grpc.security.AuthInterceptor
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/**
 * T18 harness (b): the FULL scenario against the **Robolectric-hosted `:app` hub**.
 *
 * This is the suite that proves the ANDROID WIRING, not just the protocol: the
 * graph comes from the real [PhoneManagerApplication] component (SQLite repository,
 * real `SttFactory` -> `MockSttAdapter`, `PairingServiceImpl`), and only two
 * collaborators are chosen for the test:
 *
 *  * [HubServer] — built with the loopback [HubServerAdapter]. The app's own bind
 *    is `0.0.0.0` (all interfaces), which is FORBIDDEN in tests; the explicit
 *    loopback + ephemeral port is what makes this safe.
 *  * [FrameSink] — a RECORDING sink, so byte-exactness of the video passthrough is
 *    assertable at the port boundary. The production `DiscardingFrameSink` cannot
 *    be observed, so the recording double is required to prove the relay handed the
 *    bytes over unchanged.
 *
 * ## The E2E override pattern (shared with the JVM harness, deliberately)
 *
 * The loopback [HubServerAdapter] + recording [FrameSink] composition above is the
 * same shape `:transport:grpc`'s `FullE2eTest` uses: both E2E suites inject the two
 * test-only collaborators (the safe bind and the observable sink) by CONSTRUCTION,
 * never by a component override (Dagger forbids per-instance binding overrides).
 * The production component still supplies every real port here; only the listener
 * and the sink are chosen for the test. Keep the two suites consistent.
 *
 * No emulator, no device: Robolectric only, as issues.md R2 mandates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FullE2eTest {
    @get:Rule
    val deadline: Timeout = Timeout.seconds(300)

    /** Records every opaque video NAL the relay hands over, across pump threads. */
    private class RecordingFrameSink : FrameSink {
        private val nals = Collections.synchronizedList(mutableListOf<ByteArray>())
        val videoNals: List<ByteArray> get() = synchronized(nals) { nals.toList() }

        override fun acceptVideo(
            sessionId: String,
            h264Nal: ByteArray,
        ) {
            nals += h264Nal
        }
    }

    private lateinit var pairing: PairingService
    private lateinit var hub: HubServer
    private lateinit var sink: RecordingFrameSink
    private var driver: MockPeerDriver? = null

    @Before
    fun startAppHub() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val probe = run(listOf(goBinary(), "version"), File(System.getProperty("user.dir")))
        assertWithMessage("Go toolchain unavailable; T18 cannot run: ${probe.stderr}")
            .that(probe.ok)
            .isTrue()

        // Given the REAL app graph for this Android context
        val component = (app as PhoneManagerApplication).component
        pairing = component.pairingService()

        // When the loopback listener and the recording sink are composed over it
        sink = RecordingFrameSink()
        val stt = SttFactory(component.appConfig()).createSttPort()
        val stream = StreamServiceImpl(stt, sink)
        hub =
            HubServerAdapter(
                pairingService = PairingGrpcService(pairing),
                streamService = StreamGrpcService(stream),
                authInterceptor = AuthInterceptor(component.tokenVerifier()),
                bindAddress = HubServerAdapter.LOOPBACK_ADDRESS,
            )
        hub.start(0)
        assertThat(hub.boundPort()).isGreaterThan(0)
    }

    @After
    fun stopAppHub() {
        driver?.reap()
        driver = null
        if (this::hub.isInitialized) hub.stop()
    }

    @Test
    fun `the full scenario passes against the robolectric app hub with byte exact video`() {
        // Given a driver bound to the app hub's ephemeral port
        driver =
            MockPeerDriver(
                port = hub.boundPort(),
                openWindow = { pairing.openWindow(ttlMs = 120_000).pin },
                revokeAll = { pairing.listPaired().forEach { pairing.revoke(it.deviceId) } },
            )

        // When the peer walks the full protocol against the Android wiring
        val run = driver!!.pairHeartbeatStream(frames = 10)
        println("[QA] app-hub mockpeer full stdout:\n${run.stdout.trim()}")
        assertWithMessage("mockpeer stderr: ${run.stderr}")
            .that(run.exitCode)
            .isEqualTo(0)

        // Then the ordered pair/heartbeat/session lines are on RAW stdout
        assertThat(run.stdout).contains("pair-ok")
        assertThat(run.stdout).contains("heartbeat-ok")
        assertThat(run.stdout).contains("session-ok frames=")
        assertThat(driver!!.fullLines(run))
            .containsExactly(
                "pair-ok",
                "heartbeat-ok",
                "session-ok frames=20",
            ).inOrder()

        // And the app-hub relay handed the sink the EXACT generator bytes —
        // a real per-index equality check, never a length check.
        assertThat(sink.videoNals).hasSize(10)
        val bytesMatch =
            sink.videoNals.withIndex().all { (index, nal) ->
                nal.contentEquals(syntheticVideoNal(index))
            }
        sink.videoNals.forEachIndexed { index, nal ->
            assertWithMessage("app-hub video NAL #$index was mutated in transit")
                .that(nal)
                .isEqualTo(syntheticVideoNal(index))
        }
        assertThat(bytesMatch).isTrue()

        // When the paired glass is revoked through the app's own PairingService
        val revoked = driver!!.revokedLeg()

        // Then the replay of that token is refused, with no token on stdout
        println("[QA] app-hub mockpeer revoked stdout: ${revoked.stdout.trim()} (exit=${revoked.exitCode})")
        assertThat(revoked.exitCode).isNotEqualTo(0)
        assertThat(revoked.stdout).contains("UNAUTHENTICATED")
        val revokedOk = revoked.exitCode != 0 && revoked.stdout.contains("UNAUTHENTICATED")

        // And the SAME five ordered evidence lines are emitted as the JVM harness
        val transcript =
            MockPeerTranscript.lines(
                pairOk = true,
                heartbeatOk = true,
                frames = 20,
                videoBytesMatch = bytesMatch,
                revokedUnauthenticated = revokedOk,
            )
        println("[QA] app evidence transcript: $transcript")
        MockPeerTranscript.write(
            File(repoRoot(), EVIDENCE_RELATIVE).absolutePath,
            "app-full-e2e (robolectric hub)",
            transcript,
        )
        assertThat(transcript)
            .containsExactly(
                "pair-ok",
                "heartbeat-ok",
                "session-ok frames=20",
                "video-bytes-match",
                "revoked->UNAUTHENTICATED",
            ).inOrder()
    }

    private companion object {
        const val EVIDENCE_RELATIVE: String = ".omo/evidence/task-18-phone-manager-bootstrap.txt"
    }
}
