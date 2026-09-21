package com.glassstorm.phonemanager

import com.glassstorm.phonemanager.domain.adapter.network.Discovery
import com.glassstorm.phonemanager.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContextOrNull
import com.glassstorm.phonemanager.domain.dto.HotspotInfo
import com.glassstorm.phonemanager.domain.network.HotspotUnavailableException

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
 * Every collaborator is resolved through the Context registry against a DOMAIN
 * port. This class never names a concrete adapter, so `:app` stays free of the
 * adapters' identities.
 *
 * A missing access point is NOT fatal to the listener: the hub degrades to
 * "listener up, hotspot unavailable" and reports that honestly instead of
 * claiming an access point it never brought up. A missing [HubServer] is fatal —
 * there is no hub without it.
 */
class HubBringUp(
    private val ctx: Context,
    private val permissionBlocker: () -> String?,
) {
    private var wasStarted: Boolean = false

    /** Bring the hub up in dependency order and report what actually came up. */
    fun bringUp(requestedPort: Int): HubBringUpReport {
        val hub =
            FromContextOrNull<HubServer>(ctx)
                ?: throw MissingComponentException("HubServer")

        val hotspot = bringUpHotspot()
        val port = startListener(hub, requestedPort)
        val advertising = advertiseDiscovery(port)

        wasStarted = true
        return HubBringUpReport(hotspot, port, advertising)
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
        val controller =
            FromContextOrNull<HotspotController>(ctx)
                ?: return HotspotBringUp.Unavailable("no HotspotController registered")

        detectManualTether(controller)?.let { return it }

        val blocker = permissionBlocker()
        if (blocker != null) {
            return HotspotBringUp.Unavailable("missing runtime permission $blocker")
        }

        return try {
            HotspotBringUp.Hotspot(controller.startHotspot())
        } catch (goRefused: HotspotUnavailableException) {
            HotspotBringUp.Unavailable(goRefused.failure.toString())
        }
    }

    private fun detectManualTether(controller: HotspotController): HotspotBringUp? =
        runCatching { controller.detectManualTether() }
            .getOrNull()
            ?.let { HotspotBringUp.ManualTether(it) }

    private fun startListener(
        hub: HubServer,
        requestedPort: Int,
    ): Int {
        if (!hub.isRunning()) {
            hub.start(requestedPort)
        }
        return hub.boundPort()
    }

    private fun advertiseDiscovery(port: Int): Boolean {
        val discovery = FromContextOrNull<Discovery>(ctx) ?: return false
        if (port <= 0) return false
        return runCatching {
            discovery.advertise(GO_DISCOVERY_NAME, port)
            true
        }.getOrDefault(false)
    }

    private fun stopAdvertisingDiscovery() {
        FromContextOrNull<Discovery>(ctx)?.let { runCatching { it.stopAdvertise() } }
    }

    private fun stopListener() {
        FromContextOrNull<HubServer>(ctx)?.let { runCatching { it.stop() } }
    }

    private fun stopHotspot() {
        val controller = FromContextOrNull<HotspotController>(ctx) ?: return
        if (!controller.isActive()) return
        runCatching { controller.stopHotspot() }
    }

    companion object {
        /** The DNS-SD instance name this hub advertises. */
        const val GO_DISCOVERY_NAME: String = "phone-manager"
    }
}

/** Thrown when the hub cannot come up because a required port was never registered. */
class MissingComponentException(
    component: String,
) : IllegalStateException(
        "hub bring-up needs a registered $component",
    )
