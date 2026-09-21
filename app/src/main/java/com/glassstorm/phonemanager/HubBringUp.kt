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
        val GoInfo: HotspotInfo,
    ) : HotspotBringUp

    /** An already-active system tether was detected and adopted. */
    data class ManualTether(
        val GoInfo: HotspotInfo,
    ) : HotspotBringUp

    /** No access point could be brought up; [GoReason] names the cause. */
    data class Unavailable(
        val GoReason: String,
    ) : HotspotBringUp
}

/** The observable result of one hub bring-up, kept for the UI and for tests. */
data class HubBringUpReport(
    val GoHotspot: HotspotBringUp,
    val GoPort: Int,
    val GoAdvertising: Boolean,
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
    private val GoCtx: Context,
    private val GoPermissionBlocker: () -> String?,
) {
    private var GoWasStarted: Boolean = false

    /** Bring the hub up in dependency order and report what actually came up. */
    fun GoBringUp(requestedPort: Int): HubBringUpReport {
        val GoHub =
            FromContextOrNull<HubServer>(GoCtx)
                ?: throw MissingComponentException("HubServer")

        val GoHotspot = GoBringUpHotspot()
        val GoPort = GoStartListener(GoHub, requestedPort)
        val GoAdvertising = GoAdvertiseDiscovery(GoPort)

        GoWasStarted = true
        return HubBringUpReport(GoHotspot, GoPort, GoAdvertising)
    }

    /** Tear the hub down in exact reverse order. Idempotent. */
    fun GoTearDown() {
        if (!GoWasStarted) return
        GoWasStarted = false
        GoStopAdvertisingDiscovery()
        GoStopListener()
        GoStopHotspot()
    }

    private fun GoBringUpHotspot(): HotspotBringUp {
        val GoController =
            FromContextOrNull<HotspotController>(GoCtx)
                ?: return HotspotBringUp.Unavailable("no HotspotController registered")

        GoDetectManualTether(GoController)?.let { return it }

        val GoBlocker = GoPermissionBlocker()
        if (GoBlocker != null) {
            return HotspotBringUp.Unavailable("missing runtime permission $GoBlocker")
        }

        return try {
            HotspotBringUp.Hotspot(GoController.GoStartHotspot())
        } catch (goRefused: HotspotUnavailableException) {
            HotspotBringUp.Unavailable(goRefused.GoFailure.toString())
        }
    }

    private fun GoDetectManualTether(GoController: HotspotController): HotspotBringUp? =
        runCatching { GoController.GoDetectManualTether() }
            .getOrNull()
            ?.let { HotspotBringUp.ManualTether(it) }

    private fun GoStartListener(
        GoHub: HubServer,
        requestedPort: Int,
    ): Int {
        if (!GoHub.GoIsRunning()) {
            GoHub.GoStart(requestedPort)
        }
        return GoHub.GoBoundPort()
    }

    private fun GoAdvertiseDiscovery(port: Int): Boolean {
        val GoDiscovery = FromContextOrNull<Discovery>(GoCtx) ?: return false
        if (port <= 0) return false
        return runCatching {
            GoDiscovery.GoAdvertise(GO_DISCOVERY_NAME, port)
            true
        }.getOrDefault(false)
    }

    private fun GoStopAdvertisingDiscovery() {
        FromContextOrNull<Discovery>(GoCtx)?.let { runCatching { it.GoStopAdvertise() } }
    }

    private fun GoStopListener() {
        FromContextOrNull<HubServer>(GoCtx)?.let { runCatching { it.GoStop() } }
    }

    private fun GoStopHotspot() {
        val GoController = FromContextOrNull<HotspotController>(GoCtx) ?: return
        if (!GoController.GoIsActive()) return
        runCatching { GoController.GoStopHotspot() }
    }

    companion object {
        /** The DNS-SD instance name this hub advertises. */
        const val GO_DISCOVERY_NAME: String = "phone-manager"
    }
}

/** Thrown when the hub cannot come up because a required port was never registered. */
class MissingComponentException(
    GoComponent: String,
) : IllegalStateException(
        "hub bring-up needs a registered $GoComponent",
    )
