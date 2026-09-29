package com.glassstorm.phonemanager

import com.glassstorm.phonemanager.core.domain.adapter.network.Discovery
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.network.HotspotFailure
import com.glassstorm.phonemanager.core.domain.network.HotspotUnavailableException
import com.glassstorm.phonemanager.core.model.HotspotInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The ordered bring-up and its reverse teardown, driven with test-local fakes that
 * implement the DOMAIN ports only — exactly like a real adapter.
 *
 * `HubBringUp` never names a concrete adapter; the collaborators arrive by
 * CONSTRUCTOR, so the ordering is observed through recorded calls. Under
 * compile-time DI an absent port is impossible, so the tests exercise only the
 * reachable failure paths (permission blocker, refused start, manual tether).
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
    }

    private fun wired(): Triple<CallLog, RecordingHubServer, RecordingDiscovery> {
        val log = CallLog()
        return Triple(log, RecordingHubServer(log), RecordingDiscovery(log))
    }

    private fun bringUp(
        log: CallLog,
        hub: HubServer,
        hotspot: HotspotController,
        discovery: Discovery,
        permissionBlocker: () -> String? = { null },
    ): HubBringUp =
        HubBringUp(
            hub = hub,
            hotspot = hotspot,
            discovery = discovery,
            permissionBlocker = permissionBlocker,
        )

    @Test
    fun `bring-up orders hotspot then listener then discovery`() {
        // Given fully wired collaborators
        val (log, hub, discovery) = wired()

        // When the hub is brought up
        val report =
            bringUp(log, hub, RecordingHotspot(log), discovery).bringUp(requestedPort = 0)

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
        val (log, hub, discovery) = wired()
        val bringUp = bringUp(log, hub, RecordingHotspot(log), discovery)
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
        val (log, hub, discovery) = wired()
        val bringUp = bringUp(log, hub, RecordingHotspot(log), discovery)

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
        val (log, hub, discovery) = wired()

        // When teardown runs
        bringUp(log, hub, RecordingHotspot(log), discovery).tearDown()

        // Then nothing was touched
        assertThat(log.calls).isEmpty()
    }

    @Test
    fun `a missing hotspot permission degrades to unavailable but still starts the listener`() {
        // Given the hotspot gate is blocked
        val (log, hub, discovery) = wired()

        // When the hub is brought up with a permission blocker
        val report =
            bringUp(
                log,
                hub,
                RecordingHotspot(log),
                discovery,
                permissionBlocker = { "android.permission.ACCESS_FINE_LOCATION" },
            ).bringUp(requestedPort = 0)

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
        val hotspot = RecordingHotspot(log, manual = HotspotInfo("manual-tether", "", "192.168.43.1"))

        // When the hub is brought up
        val report = bringUp(log, RecordingHubServer(log), hotspot, RecordingDiscovery(log)).bringUp(requestedPort = 0)

        // Then the manual tether is reported as the success it is, with no LOHS start
        assertThat(report.hotspot).isInstanceOf(HotspotBringUp.ManualTether::class.java)
        assertThat(log.calls).doesNotContain("hotspot.start")
    }

    @Test
    fun `a refused hotspot start is reported not thrown`() {
        // Given the platform refuses LocalOnlyHotspot
        val log = CallLog()
        val hotspot = RecordingHotspot(log, fail = HotspotFailure.StartFailed("OEM refused"))

        // When the hub is brought up
        val report = bringUp(log, RecordingHubServer(log), hotspot, RecordingDiscovery(log)).bringUp(requestedPort = 0)

        // Then the refusal is a reported Unavailable, not a crash, and the listener
        // still came up
        assertThat(report.hotspot).isInstanceOf(HotspotBringUp.Unavailable::class.java)
        assertThat(report.port).isEqualTo(40404)
    }

    @Test
    fun `a zero bound port reports no advertising`() {
        // Given a listener that never binds (boundPort stays 0)
        val log = CallLog()
        val hub =
            object : HubServer {
                override fun start(port: Int) = Unit

                override fun stop() = Unit

                override fun isRunning(): Boolean = false

                override fun boundPort(): Int = 0
            }
        val discovery = RecordingDiscovery(log)

        // When the hub is brought up
        val report = bringUp(log, hub, RecordingHotspot(log), discovery).bringUp(requestedPort = 0)

        // Then advertising is honestly reported as false and nothing was advertised
        assertThat(report.port).isEqualTo(0)
        assertThat(report.advertising).isFalse()
        assertThat(discovery.advertisedPort).isNull()
    }
}
