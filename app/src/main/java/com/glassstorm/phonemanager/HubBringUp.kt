package com.glassstorm.phonemanager

import com.glassstorm.phonemanager.core.domain.adapter.network.Discovery
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.network.HotspotUnavailableException
import com.glassstorm.phonemanager.core.model.HotspotInfo

/**
 * How the access point came up (or why it did not).
 *
 * A `manual-tether` outcome is a first-class SUCCESS: OEMs (MIUI/Knox/EMUI)
 * frequently block programmatic LocalOnlyHotspot, so "the user turned the system
 * hotspot on" is the documented primary development path — not an error.
 */
sealed interface HotspotBringUp {
    /** The programmatic LocalOnlyHotspot reservation is live. */
    data class Hotspot(
        val info: HotspotInfo,
    ) : HotspotBringUp

    /** An already-active system tether was detected and adopted. */
    data class ManualTether(
        val info: HotspotInfo,
    ) : HotspotBringUp

    /** No access point could be brought up; [reason] names the cause. */
    data class Unavailable(
        val reason: String,
    ) : HotspotBringUp
}

/** The observable result of one hub bring-up, kept for the UI and for tests. */
data class HubBringUpReport(
    val hotspot: HotspotBringUp,
    val port: Int,
    val advertising: Boolean,
)

/**
 * Brings the whole hub up in a fixed order and tears it down in reverse.
 *
 * The order is forced by the data dependencies:
 *   1. the access point (or an adopted manual tether) — peers need a LAN to reach;
 *   2. the gRPC listener — it produces the bound port;
 *   3. discovery advertising — it advertises THAT bound port.
 *
 * Teardown runs 3 → 2 → 1. Every step is guarded so a repeated start/stop pair is
 * a no-op rather than a double-start (the service may be re-created by the OS at
 * any time).
 *
 * Every collaborator is a CONSTRUCTOR dependency typed as a DOMAIN port. This
 * class never names a concrete adapter, so `:app` stays free of the adapters'
 * identities, and Dagger supplies the ports from the compile-time graph — a
 * missing port is a build failure, not a runtime branch.
 *
 * A refused or permission-blocked access point is NOT fatal to the listener: the
 * hub degrades to "listener up, hotspot unavailable" and reports that honestly
 * instead of claiming an access point it never brought up.
 */
class HubBringUp(
    private val hub: HubServer,
    private val hotspot: HotspotController,
    private val discovery: Discovery,
    private val permissionBlocker: () -> String?,
) {
    private var wasStarted: Boolean = false

    /** Bring the hub up in dependency order and report what actually came up. */
    fun bringUp(requestedPort: Int): HubBringUpReport {
        val hotspotOutcome = bringUpHotspot()
        val port = startListener(requestedPort)
        val advertising = advertiseDiscovery(port)

        wasStarted = true
        return HubBringUpReport(hotspotOutcome, port, advertising)
    }

    /** Tear the hub down in exact reverse order. Idempotent. */
    fun tearDown() {
        if (!wasStarted) return
        wasStarted = false
        stopAdvertisingDiscovery()
        stopListener()
        stopHotspot()
    }

    private fun bringUpHotspot(): HotspotBringUp {
        detectManualTether()?.let { return it }

        val blocker = permissionBlocker()
        if (blocker != null) {
            return HotspotBringUp.Unavailable("missing runtime permission $blocker")
        }

        return try {
            HotspotBringUp.Hotspot(hotspot.startHotspot())
        } catch (refused: HotspotUnavailableException) {
            HotspotBringUp.Unavailable(refused.failure.toString())
        }
    }

    private fun detectManualTether(): HotspotBringUp? =
        runCatching { hotspot.detectManualTether() }
            .getOrNull()
            ?.let { HotspotBringUp.ManualTether(it) }

    private fun startListener(requestedPort: Int): Int {
        if (!hub.isRunning()) {
            hub.start(requestedPort)
        }
        return hub.boundPort()
    }

    private fun advertiseDiscovery(port: Int): Boolean {
        if (port <= 0) return false
        return runCatching {
            discovery.advertise(DISCOVERY_NAME, port)
            true
        }.getOrDefault(false)
    }

    private fun stopAdvertisingDiscovery() {
        runCatching { discovery.stopAdvertise() }
    }

    private fun stopListener() {
        runCatching { hub.stop() }
    }

    private fun stopHotspot() {
        if (!hotspot.isActive()) return
        runCatching { hotspot.stopHotspot() }
    }

    companion object {
        /** The DNS-SD instance name this hub advertises. */
        const val DISCOVERY_NAME: String = "phone-manager"
    }
}
