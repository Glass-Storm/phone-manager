package com.glassstorm.phonemanager

import com.glassstorm.phonemanager.domain.adapter.network.Discovery
import com.glassstorm.phonemanager.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.register
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
    private class CallLog {
        val calls: MutableList<String> = mutableListOf()
    }

    private class RecordingHubServer(
        private val log: CallLog,
        private val port: Int = 40404,
    ) : HubServer {
        private var running = false

        override fun start(port: Int) {
            log.calls += "server.start"
            running = true
        }

        override fun stop() {
            log.calls += "server.stop"
            running = false
        }

        override fun isRunning(): Boolean = running

        override fun boundPort(): Int = if (running) port else 0
    }

    private class RecordingHotspot(
        private val log: CallLog,
        private val manual: HotspotInfo? = null,
        private val fail: HotspotFailure? = null,
    ) : HotspotController {
        private var active = false

        override fun startHotspot(): HotspotInfo {
            log.calls += "hotspot.start"
            fail?.let { throw HotspotUnavailableException(it) }
            active = true
            return HotspotInfo("EcoSys-Phone", "hunter2", "192.168.43.1")
        }

        override fun stopHotspot() {
            log.calls += "hotspot.stop"
            active = false
        }

        override fun isActive(): Boolean = active

        override fun detectManualTether(): HotspotInfo? = manual
    }

    private class RecordingDiscovery(
        private val log: CallLog,
    ) : Discovery {
        var advertisedPort: Int? = null
            private set

        override fun advertise(
            name: String,
            port: Int,
        ) {
            log.calls += "discovery.advertise:$port"
            advertisedPort = port
        }

        override fun stopAdvertise() {
            log.calls += "discovery.stop"
        }

        override fun resolveFirst(timeoutMs: Long): PeerAddress? = null
    }

    private fun wired(): Triple<Context, CallLog, RecordingDiscovery> {
        val log = CallLog()
        val ctx = Context()
        register<HubServer>(ctx, RecordingHubServer(log))
        register<HotspotController>(ctx, RecordingHotspot(log))
        val discovery = RecordingDiscovery(log)
        register<Discovery>(ctx, discovery)
        return Triple(ctx, log, discovery)
    }

    @Test
    fun `bring-up orders hotspot then listener then discovery`() {
        // Given a fully wired Context
        val (ctx, log, discovery) = wired()

        // When the hub is brought up
        val report = HubBringUp(ctx) { null }.bringUp(requestedPort = 0)

        // Then the order respects the data dependencies (discovery advertises the
        // port the listener actually bound) and the report is honest
        assertThat(log.calls)
            .containsExactly(
                "hotspot.start",
                "server.start",
                "discovery.advertise:40404",
            ).inOrder()
        assertThat(report.hotspot).isInstanceOf(HotspotBringUp.Hotspot::class.java)
        assertThat(report.port).isEqualTo(40404)
        assertThat(report.advertising).isTrue()
        assertThat(discovery.advertisedPort).isEqualTo(40404)
    }

    @Test
    fun `teardown runs in exact reverse order`() {
        // Given a running hub
        val (ctx, log, _) = wired()
        val bringUp = HubBringUp(ctx) { null }
        bringUp.bringUp(requestedPort = 0)
        log.calls.clear()

        // When the hub is torn down
        bringUp.tearDown()

        // Then discovery stops before the listener before the hotspot
        assertThat(log.calls)
            .containsExactly(
                "discovery.stop",
                "server.stop",
                "hotspot.stop",
            ).inOrder()
    }

    @Test
    fun `a repeated start stop pair is idempotent and never double-starts`() {
        // Given a hub taken up and down twice
        val (ctx, log, _) = wired()
        val bringUp = HubBringUp(ctx) { null }

        // When start/stop runs twice
        bringUp.bringUp(requestedPort = 0)
        bringUp.tearDown()
        bringUp.bringUp(requestedPort = 0)
        bringUp.tearDown()

        // Then the listener started exactly twice (once per start), never a
        // duplicate start within one cycle
        assertThat(log.calls.count { it == "server.start" }).isEqualTo(2)
        assertThat(log.calls.count { it == "server.stop" }).isEqualTo(2)
    }

    @Test
    fun `teardown before any start is a no-op`() {
        // Given a hub that was never started
        val (ctx, log, _) = wired()

        // When teardown runs
        HubBringUp(ctx) { null }.tearDown()

        // Then nothing was touched
        assertThat(log.calls).isEmpty()
    }

    @Test
    fun `a missing hotspot permission degrades to unavailable but still starts the listener`() {
        // Given the hotspot gate is blocked
        val (ctx, log, discovery) = wired()

        // When the hub is brought up with a permission blocker
        val report =
            HubBringUp(ctx) { "android.permission.ACCESS_FINE_LOCATION" }
                .bringUp(requestedPort = 0)

        // Then the hotspot never started, no ACTIVE claim is made, and the listener
        // + discovery still came up so the hub is reachable by wired peers
        assertThat(log.calls).doesNotContain("hotspot.start")
        assertThat(report.hotspot).isInstanceOf(HotspotBringUp.Unavailable::class.java)
        assertThat(
            (report.hotspot as HotspotBringUp.Unavailable).reason,
        ).contains("ACCESS_FINE_LOCATION")
        assertThat(report.port).isEqualTo(40404)
        assertThat(discovery.advertisedPort).isEqualTo(40404)
    }

    @Test
    fun `a manual tether is adopted as a first-class success`() {
        // Given an OEM-blocked device whose user enabled the system hotspot
        val log = CallLog()
        val ctx = Context()
        register<HubServer>(ctx, RecordingHubServer(log))
        register<HotspotController>(
            ctx,
            RecordingHotspot(
                log,
                manual = HotspotInfo("manual-tether", "", "192.168.43.1"),
            ),
        )
        register<Discovery>(ctx, RecordingDiscovery(log))

        // When the hub is brought up
        val report = HubBringUp(ctx) { null }.bringUp(requestedPort = 0)

        // Then the manual tether is reported as the success it is, with no LOHS start
        assertThat(report.hotspot).isInstanceOf(HotspotBringUp.ManualTether::class.java)
        assertThat(log.calls).doesNotContain("hotspot.start")
    }

    @Test
    fun `a refused hotspot start is reported not thrown`() {
        // Given the platform refuses LocalOnlyHotspot
        val log = CallLog()
        val ctx = Context()
        register<HubServer>(ctx, RecordingHubServer(log))
        register<HotspotController>(
            ctx,
            RecordingHotspot(log, fail = HotspotFailure.StartFailed("OEM refused")),
        )
        register<Discovery>(ctx, RecordingDiscovery(log))

        // When the hub is brought up
        val report = HubBringUp(ctx) { null }.bringUp(requestedPort = 0)

        // Then the refusal is a reported Unavailable, not a crash, and the listener
        // still came up
        assertThat(report.hotspot).isInstanceOf(HotspotBringUp.Unavailable::class.java)
        assertThat(report.port).isEqualTo(40404)
    }

    @Test
    fun `a missing hub server fails the bring-up loudly`() {
        // Given a Context with no HubServer registered
        val ctx = Context()

        // When the hub is brought up
        val thrown = runCatching { HubBringUp(ctx) { null }.bringUp(0) }.exceptionOrNull()

        // Then it fails with the typed missing-component error, never a silent no-hub
        assertThat(thrown).isInstanceOf(MissingComponentException::class.java)
    }

    @Test
    fun `a missing discovery port leaves the listener up and reports no advertising`() {
        // Given a Context without a Discovery adapter
        val log = CallLog()
        val ctx = Context()
        register<HubServer>(ctx, RecordingHubServer(log))
        register<HotspotController>(ctx, RecordingHotspot(log))

        // When the hub is brought up
        val report = HubBringUp(ctx) { null }.bringUp(requestedPort = 0)

        // Then the listener is up and advertising is honestly reported as false
        assertThat(report.port).isEqualTo(40404)
        assertThat(report.advertising).isFalse()
    }
}
