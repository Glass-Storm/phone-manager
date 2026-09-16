package com.glassstorm.phonemanager

import com.glassstorm.phonemanager.domain.adapter.network.Discovery
import com.glassstorm.phonemanager.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.dto.HotspotInfo
import com.glassstorm.phonemanager.domain.dto.PeerAddress
import com.glassstorm.phonemanager.domain.network.HotspotFailure
import com.glassstorm.phonemanager.domain.network.HotspotUnavailableException
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The ordered bring-up and its reverse teardown, driven with test-local fakes that
 * implement the DOMAIN ports only — exactly like a real adapter.
 *
 * `HubBringUp` never names a concrete adapter, so these fakes bind under the port
 * types and the ordering is observed through recorded calls.
 */
class HubBringUpTest {
    /** Records the call order shared by every collaborator. */
    private class GoCallLog {
        val calls: MutableList<String> = mutableListOf()
    }

    private class RecordingHubServer(
        private val GoLog: GoCallLog,
        private val GoPort: Int = 40404,
    ) : HubServer {
        private var GoRunning = false

        override fun GoStart(port: Int) {
            GoLog.calls += "server.start"
            GoRunning = true
        }

        override fun GoStop() {
            GoLog.calls += "server.stop"
            GoRunning = false
        }

        override fun GoIsRunning(): Boolean = GoRunning

        override fun GoBoundPort(): Int = if (GoRunning) GoPort else 0
    }

    private class RecordingHotspot(
        private val GoLog: GoCallLog,
        private val GoManual: HotspotInfo? = null,
        private val GoFail: HotspotFailure? = null,
    ) : HotspotController {
        private var GoActive = false

        override fun GoStartHotspot(): HotspotInfo {
            GoLog.calls += "hotspot.start"
            GoFail?.let { throw HotspotUnavailableException(it) }
            GoActive = true
            return HotspotInfo("EcoSys-Phone", "hunter2", "192.168.43.1")
        }

        override fun GoStopHotspot() {
            GoLog.calls += "hotspot.stop"
            GoActive = false
        }

        override fun GoIsActive(): Boolean = GoActive

        override fun GoDetectManualTether(): HotspotInfo? = GoManual
    }

    private class RecordingDiscovery(
        private val GoLog: GoCallLog,
    ) : Discovery {
        var GoAdvertisedPort: Int? = null
            private set

        override fun GoAdvertise(
            name: String,
            port: Int,
        ) {
            GoLog.calls += "discovery.advertise:$port"
            GoAdvertisedPort = port
        }

        override fun GoStopAdvertise() {
            GoLog.calls += "discovery.stop"
        }

        override fun GoResolveFirst(timeoutMs: Long): PeerAddress? = null
    }

    private fun GoWired(): Triple<Context, GoCallLog, RecordingDiscovery> {
        val GoLog = GoCallLog()
        val GoCtx = Context()
        Register<HubServer>(GoCtx, RecordingHubServer(GoLog))
        Register<HotspotController>(GoCtx, RecordingHotspot(GoLog))
        val GoDiscovery = RecordingDiscovery(GoLog)
        Register<Discovery>(GoCtx, GoDiscovery)
        return Triple(GoCtx, GoLog, GoDiscovery)
    }

    @Test
    fun `bring-up orders hotspot then listener then discovery`() {
        // Given a fully wired Context
        val (GoCtx, GoLog, GoDiscovery) = GoWired()

        // When the hub is brought up
        val GoReport = HubBringUp(GoCtx) { null }.GoBringUp(requestedPort = 0)

        // Then the order respects the data dependencies (discovery advertises the
        // port the listener actually bound) and the report is honest
        assertThat(GoLog.calls)
            .containsExactly(
                "hotspot.start",
                "server.start",
                "discovery.advertise:40404",
            ).inOrder()
        assertThat(GoReport.GoHotspot).isInstanceOf(HotspotBringUp.Hotspot::class.java)
        assertThat(GoReport.GoPort).isEqualTo(40404)
        assertThat(GoReport.GoAdvertising).isTrue()
        assertThat(GoDiscovery.GoAdvertisedPort).isEqualTo(40404)
    }

    @Test
    fun `teardown runs in exact reverse order`() {
        // Given a running hub
        val (GoCtx, GoLog, _) = GoWired()
        val GoBringUp = HubBringUp(GoCtx) { null }
        GoBringUp.GoBringUp(requestedPort = 0)
        GoLog.calls.clear()

        // When the hub is torn down
        GoBringUp.GoTearDown()

        // Then discovery stops before the listener before the hotspot
        assertThat(GoLog.calls)
            .containsExactly(
                "discovery.stop",
                "server.stop",
                "hotspot.stop",
            ).inOrder()
    }

    @Test
    fun `a repeated start stop pair is idempotent and never double-starts`() {
        // Given a hub taken up and down twice
        val (GoCtx, GoLog, _) = GoWired()
        val GoBringUp = HubBringUp(GoCtx) { null }

        // When start/stop runs twice
        GoBringUp.GoBringUp(requestedPort = 0)
        GoBringUp.GoTearDown()
        GoBringUp.GoBringUp(requestedPort = 0)
        GoBringUp.GoTearDown()

        // Then the listener started exactly twice (once per start), never a
        // duplicate start within one cycle
        assertThat(GoLog.calls.count { it == "server.start" }).isEqualTo(2)
        assertThat(GoLog.calls.count { it == "server.stop" }).isEqualTo(2)
    }

    @Test
    fun `teardown before any start is a no-op`() {
        // Given a hub that was never started
        val (GoCtx, GoLog, _) = GoWired()

        // When teardown runs
        HubBringUp(GoCtx) { null }.GoTearDown()

        // Then nothing was touched
        assertThat(GoLog.calls).isEmpty()
    }

    @Test
    fun `a missing hotspot permission degrades to unavailable but still starts the listener`() {
        // Given the hotspot gate is blocked
        val (GoCtx, GoLog, GoDiscovery) = GoWired()

        // When the hub is brought up with a permission blocker
        val GoReport =
            HubBringUp(GoCtx) { "android.permission.ACCESS_FINE_LOCATION" }
                .GoBringUp(requestedPort = 0)

        // Then the hotspot never started, no ACTIVE claim is made, and the listener
        // + discovery still came up so the hub is reachable by wired peers
        assertThat(GoLog.calls).doesNotContain("hotspot.start")
        assertThat(GoReport.GoHotspot).isInstanceOf(HotspotBringUp.Unavailable::class.java)
        assertThat(
            (GoReport.GoHotspot as HotspotBringUp.Unavailable).GoReason,
        ).contains("ACCESS_FINE_LOCATION")
        assertThat(GoReport.GoPort).isEqualTo(40404)
        assertThat(GoDiscovery.GoAdvertisedPort).isEqualTo(40404)
    }

    @Test
    fun `a manual tether is adopted as a first-class success`() {
        // Given an OEM-blocked device whose user enabled the system hotspot
        val GoLog = GoCallLog()
        val GoCtx = Context()
        Register<HubServer>(GoCtx, RecordingHubServer(GoLog))
        Register<HotspotController>(
            GoCtx,
            RecordingHotspot(
                GoLog,
                GoManual = HotspotInfo("manual-tether", "", "192.168.43.1"),
            ),
        )
        Register<Discovery>(GoCtx, RecordingDiscovery(GoLog))

        // When the hub is brought up
        val GoReport = HubBringUp(GoCtx) { null }.GoBringUp(requestedPort = 0)

        // Then the manual tether is reported as the success it is, with no LOHS start
        assertThat(GoReport.GoHotspot).isInstanceOf(HotspotBringUp.ManualTether::class.java)
        assertThat(GoLog.calls).doesNotContain("hotspot.start")
    }

    @Test
    fun `a refused hotspot start is reported not thrown`() {
        // Given the platform refuses LocalOnlyHotspot
        val GoLog = GoCallLog()
        val GoCtx = Context()
        Register<HubServer>(GoCtx, RecordingHubServer(GoLog))
        Register<HotspotController>(
            GoCtx,
            RecordingHotspot(GoLog, GoFail = HotspotFailure.StartFailed("OEM refused")),
        )
        Register<Discovery>(GoCtx, RecordingDiscovery(GoLog))

        // When the hub is brought up
        val GoReport = HubBringUp(GoCtx) { null }.GoBringUp(requestedPort = 0)

        // Then the refusal is a reported Unavailable, not a crash, and the listener
        // still came up
        assertThat(GoReport.GoHotspot).isInstanceOf(HotspotBringUp.Unavailable::class.java)
        assertThat(GoReport.GoPort).isEqualTo(40404)
    }

    @Test
    fun `a missing hub server fails the bring-up loudly`() {
        // Given a Context with no HubServer registered
        val GoCtx = Context()

        // When the hub is brought up
        val GoThrown = runCatching { HubBringUp(GoCtx) { null }.GoBringUp(0) }.exceptionOrNull()

        // Then it fails with the typed missing-component error, never a silent no-hub
        assertThat(GoThrown).isInstanceOf(MissingComponentException::class.java)
    }

    @Test
    fun `a missing discovery port leaves the listener up and reports no advertising`() {
        // Given a Context without a Discovery adapter
        val GoLog = GoCallLog()
        val GoCtx = Context()
        Register<HubServer>(GoCtx, RecordingHubServer(GoLog))
        Register<HotspotController>(GoCtx, RecordingHotspot(GoLog))

        // When the hub is brought up
        val GoReport = HubBringUp(GoCtx) { null }.GoBringUp(requestedPort = 0)

        // Then the listener is up and advertising is honestly reported as false
        assertThat(GoReport.GoPort).isEqualTo(40404)
        assertThat(GoReport.GoAdvertising).isFalse()
    }
}
