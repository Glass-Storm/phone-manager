package com.glassstorm.phonemanager

import com.glassstorm.phonemanager.core.domain.adapter.network.Discovery
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.model.HotspotInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The ordered bring-up and its reverse teardown, driven with test-local fakes that
 * implement the DOMAIN ports only — exactly like a real adapter.
 *
 * The product's only access point is the user's manually-enabled Mobile Hotspot,
 * so [HubBringUp] must never call `startHotspot`: it detects a tether or reports
 * the exact user action that is missing. Robolectric is required because
 * [HubBringUp] logs the decided path through `android.util.Log`.
 */
@RunWith(RobolectricTestRunner::class)
class HubBringUpTest {
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
        private val failDetection: Boolean = false,
    ) : HotspotController {
        override fun startHotspot(): HotspotInfo {
            log.calls += "hotspot.start"
            error("HubBringUp must never create an access point")
        }

        override fun stopHotspot() {
            log.calls += "hotspot.stop"
        }

        override fun isActive(): Boolean = manual != null

        override fun detectManualTether(): HotspotInfo? {
            log.calls += "hotspot.detectManual"
            if (failDetection) throw IllegalStateException("interface scan failed")
            return manual
        }
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

    private val manualTether = HotspotInfo(ssid = "manual-tether", passphrase = "", gatewayIp = "192.168.43.1")

    private fun bringUp(
        hub: HubServer,
        hotspot: HotspotController,
        discovery: Discovery,
    ): HubBringUp = HubBringUp(hub = hub, hotspot = hotspot, discovery = discovery)

    @Test
    fun `bring-up detects the manual tether then starts the listener then advertises`() {
        val log = CallLog()
        val discovery = RecordingDiscovery(log)

        val report =
            bringUp(RecordingHubServer(log), RecordingHotspot(log, manual = manualTether), discovery)
                .bringUp(requestedPort = 0)

        assertThat(log.calls)
            .containsExactly(
                "hotspot.detectManual",
                "server.start",
                "discovery.advertise:40404",
            ).inOrder()
        assertThat(log.calls).doesNotContain("hotspot.start")
        assertThat(report.hotspot).isInstanceOf(HotspotBringUp.ManualTether::class.java)
        assertThat((report.hotspot as HotspotBringUp.ManualTether).info).isEqualTo(manualTether)
        assertThat(report.port).isEqualTo(40404)
        assertThat(report.advertising).isTrue()
        assertThat(discovery.advertisedPort).isEqualTo(40404)
    }

    @Test
    fun `no manual hotspot is reported unavailable but the listener still starts`() {
        val log = CallLog()
        val discovery = RecordingDiscovery(log)

        val report =
            bringUp(RecordingHubServer(log), RecordingHotspot(log), discovery)
                .bringUp(requestedPort = 0)

        assertThat(log.calls).containsAtLeast("hotspot.detectManual", "server.start").inOrder()
        assertThat(log.calls).doesNotContain("hotspot.start")
        assertThat(report.hotspot).isInstanceOf(HotspotBringUp.Unavailable::class.java)
        assertThat((report.hotspot as HotspotBringUp.Unavailable).reason)
            .isEqualTo("no manual hotspot; enable the phone's Mobile Hotspot")
        assertThat(report.port).isEqualTo(40404)
        assertThat(discovery.advertisedPort).isEqualTo(40404)
    }

    @Test
    fun `an interface scan failure degrades to unavailable instead of crashing`() {
        val log = CallLog()

        val report =
            bringUp(
                RecordingHubServer(log),
                RecordingHotspot(log, failDetection = true),
                RecordingDiscovery(log),
            ).bringUp(requestedPort = 0)

        assertThat(report.hotspot).isInstanceOf(HotspotBringUp.Unavailable::class.java)
        assertThat(report.port).isEqualTo(40404)
    }

    @Test
    fun `teardown runs in exact reverse order`() {
        val log = CallLog()
        val bringUp = bringUp(RecordingHubServer(log), RecordingHotspot(log, manual = manualTether), RecordingDiscovery(log))
        bringUp.bringUp(requestedPort = 0)
        log.calls.clear()

        bringUp.tearDown()

        assertThat(log.calls)
            .containsExactly(
                "discovery.stop",
                "server.stop",
                "hotspot.stop",
            ).inOrder()
    }

    @Test
    fun `teardown never stops a hotspot that was not detected`() {
        val log = CallLog()
        val bringUp = bringUp(RecordingHubServer(log), RecordingHotspot(log), RecordingDiscovery(log))
        bringUp.bringUp(requestedPort = 0)
        log.calls.clear()

        bringUp.tearDown()

        assertThat(log.calls).containsExactly("discovery.stop", "server.stop").inOrder()
    }

    @Test
    fun `a repeated start stop pair is idempotent and never double-starts`() {
        val log = CallLog()
        val bringUp = bringUp(RecordingHubServer(log), RecordingHotspot(log, manual = manualTether), RecordingDiscovery(log))

        bringUp.bringUp(requestedPort = 0)
        bringUp.tearDown()
        bringUp.bringUp(requestedPort = 0)
        bringUp.tearDown()

        assertThat(log.calls.count { it == "server.start" }).isEqualTo(2)
        assertThat(log.calls.count { it == "server.stop" }).isEqualTo(2)
    }

    @Test
    fun `teardown before any start is a no-op`() {
        val log = CallLog()

        bringUp(RecordingHubServer(log), RecordingHotspot(log), RecordingDiscovery(log)).tearDown()

        assertThat(log.calls).isEmpty()
    }

    @Test
    fun `a zero bound port reports no advertising`() {
        val log = CallLog()
        val hub =
            object : HubServer {
                override fun start(port: Int) = Unit

                override fun stop() = Unit

                override fun isRunning(): Boolean = false

                override fun boundPort(): Int = 0
            }
        val discovery = RecordingDiscovery(log)

        val report = bringUp(hub, RecordingHotspot(log, manual = manualTether), discovery).bringUp(requestedPort = 0)

        assertThat(report.port).isEqualTo(0)
        assertThat(report.advertising).isFalse()
        assertThat(discovery.advertisedPort).isNull()
    }
}
