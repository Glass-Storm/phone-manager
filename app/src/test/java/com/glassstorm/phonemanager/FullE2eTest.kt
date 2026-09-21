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
import com.glassstorm.phonemanager.testkit.GoGoBinary
import com.glassstorm.phonemanager.testkit.GoRepoRoot
import com.glassstorm.phonemanager.testkit.GoRun
import com.glassstorm.phonemanager.testkit.GoSyntheticVideoNal
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
 *    default is `GoForLanPeers` (`0.0.0.0`), which is FORBIDDEN in tests; the
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
    val GoDeadline: Timeout = Timeout.seconds(300)

    /** Records every opaque video NAL the relay hands over, across pump threads. */
    private class GoRecordingFrameSink : FrameSink {
        private val GoNals = Collections.synchronizedList(mutableListOf<ByteArray>())
        val GoVideoNals: List<ByteArray> get() = synchronized(GoNals) { GoNals.toList() }

        override fun GoAcceptVideo(
            sessionId: String,
            h264Nal: ByteArray,
        ) {
            GoNals += h264Nal
        }
    }

    private lateinit var GoCtx: Context
    private lateinit var GoHub: HubServer
    private lateinit var GoSink: GoRecordingFrameSink
    private var GoDriver: MockPeerDriver? = null

    @Before
    fun GoStartAppHub() {
        val GoApp: Application = ApplicationProvider.getApplicationContext()
        val GoProbe = GoRun(listOf(GoGoBinary(), "version"), File(System.getProperty("user.dir")))
        assertWithMessage("Go toolchain unavailable; T18 cannot run: ${GoProbe.stderr}")
            .that(GoProbe.ok)
            .isTrue()

        // Given the REAL app composition for this Android context
        GoCtx = AppComposition.GoBuildContext(GoApp)

        // When the loopback listener and the recording sink are bound over it
        GoHub = HubServerAdapter(GoCtx)
        assertThat((GoHub as HubServerAdapter).GoBindAddress()).isEqualTo("127.0.0.1")
        Register<HubServer>(GoCtx, GoHub)
        GoSink = GoRecordingFrameSink()
        Register<FrameSink>(GoCtx, GoSink)
        GoHub.GoStart(0)
        assertThat(GoHub.GoBoundPort()).isGreaterThan(0)
    }

    @After
    fun GoStopAppHub() {
        GoDriver?.GoReap()
        GoDriver = null
        if (this::GoHub.isInitialized) GoHub.GoStop()
    }

    @Test
    fun `the full scenario passes against the robolectric app hub with byte exact video`() {
        // Given a driver bound to the app hub's ephemeral port
        val GoPairing = FromContext<PairingService>(GoCtx)
        GoDriver =
            MockPeerDriver(
                GoPort = GoHub.GoBoundPort(),
                GoOpenWindow = { GoPairing.GoOpenWindow(ttlMs = 120_000).GoPin },
                GoRevokeAll = { GoPairing.GoListPaired().forEach { GoPairing.GoRevoke(it.GoDeviceId) } },
            )

        // When the peer walks the full protocol against the Android wiring
        val GoRun = GoDriver!!.GoPairHeartbeatStream(frames = 10)
        println("[QA] app-hub mockpeer full stdout:\n${GoRun.stdout.trim()}")
        assertWithMessage("mockpeer stderr: ${GoRun.stderr}")
            .that(GoRun.exitCode)
            .isEqualTo(0)

        // Then the ordered pair/heartbeat/session lines are on RAW stdout
        assertThat(GoRun.stdout).contains("pair-ok")
        assertThat(GoRun.stdout).contains("heartbeat-ok")
        assertThat(GoRun.stdout).contains("session-ok frames=")
        assertThat(GoDriver!!.GoFullLines(GoRun))
            .containsExactly(
                "pair-ok",
                "heartbeat-ok",
                "session-ok frames=20",
            ).inOrder()

        // And the app-hub relay handed the sink the EXACT generator bytes —
        // a real per-index equality check, never a length check.
        assertThat(GoSink.GoVideoNals).hasSize(10)
        val GoBytesMatch =
            GoSink.GoVideoNals.withIndex().all { (index, nal) ->
                nal.contentEquals(GoSyntheticVideoNal(index))
            }
        GoSink.GoVideoNals.forEachIndexed { index, nal ->
            assertWithMessage("app-hub video NAL #$index was mutated in transit")
                .that(nal)
                .isEqualTo(GoSyntheticVideoNal(index))
        }
        assertThat(GoBytesMatch).isTrue()

        // When the paired glass is revoked through the app's own PairingService
        val GoRevoked = GoDriver!!.GoRevokedLeg()

        // Then the replay of that token is refused, with no token on stdout
        println("[QA] app-hub mockpeer revoked stdout: ${GoRevoked.stdout.trim()} (exit=${GoRevoked.exitCode})")
        assertThat(GoRevoked.exitCode).isNotEqualTo(0)
        assertThat(GoRevoked.stdout).contains("UNAUTHENTICATED")
        val GoRevokedOk = GoRevoked.exitCode != 0 && GoRevoked.stdout.contains("UNAUTHENTICATED")

        // And the SAME five ordered evidence lines are emitted as the JVM harness
        val GoTranscript =
            MockPeerTranscript.GoLines(
                pairOk = true,
                heartbeatOk = true,
                frames = 20,
                videoBytesMatch = GoBytesMatch,
                revokedUnauthenticated = GoRevokedOk,
            )
        println("[QA] app evidence transcript: $GoTranscript")
        MockPeerTranscript.GoWrite(
            File(GoRepoRoot(), GO_EVIDENCE_RELATIVE).absolutePath,
            "app-full-e2e (robolectric hub)",
            GoTranscript,
        )
        assertThat(GoTranscript)
            .containsExactly(
                "pair-ok",
                "heartbeat-ok",
                "session-ok frames=20",
                "video-bytes-match",
                "revoked->UNAUTHENTICATED",
            ).inOrder()
    }

    private companion object {
        const val GO_EVIDENCE_RELATIVE: String = ".omo/evidence/task-18-phone-manager-bootstrap.txt"
    }
}
