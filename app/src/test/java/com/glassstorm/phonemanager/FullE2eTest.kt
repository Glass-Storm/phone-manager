package com.glassstorm.phonemanager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.adapter.transport.grpc.HubServerAdapter
import com.glassstorm.phonemanager.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContext
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.service.PairingService
import com.glassstorm.phonemanager.testkit.goBinary
import com.glassstorm.phonemanager.testkit.repoRoot
import com.glassstorm.phonemanager.testkit.run
import com.glassstorm.phonemanager.testkit.syntheticVideoNal
import com.glassstorm.phonemanager.testkit.MockPeerDriver
import com.glassstorm.phonemanager.testkit.MockPeerTranscript
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
 * Context comes from the real [AppComposition] (SQLite repository, real
 * `SttFactory` -> `MockSttAdapter`, `DiscardingFrameSink`, `PairingServiceImpl`)
 * and only two ports are overridden:
 *
 *  * [HubServer] — re-registered with the loopback [HubServerAdapter]. The app
 *    default is `forLanPeers` (`0.0.0.0`), which is FORBIDDEN in tests; the
 *    override is what makes the bind `127.0.0.1` + ephemeral.
 *  * [FrameSink] — re-registered with a RECORDING sink, so byte-exactness of the
 *    video passthrough is assertable at the port boundary. The production
 *    `DiscardingFrameSink` (asserted present in [AppCompositionTest]) cannot be
 *    observed, so the recording double is required to prove the relay handed the
 *    bytes over unchanged.
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

    private lateinit var ctx: Context
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

        // Given the REAL app composition for this Android context
        ctx = AppComposition.buildContext(app)

        // When the loopback listener and the recording sink are bound over it
        hub = HubServerAdapter(ctx)
        assertThat((hub as HubServerAdapter).bindAddress()).isEqualTo("127.0.0.1")
        Register<HubServer>(ctx, hub)
        sink = RecordingFrameSink()
        Register<FrameSink>(ctx, sink)
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
        val pairing = FromContext<PairingService>(ctx)
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
