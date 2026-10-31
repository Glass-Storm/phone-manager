package com.glassstorm.phonemanager

import android.util.Log
import com.glassstorm.phonemanager.core.domain.adapter.network.Discovery
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.model.HotspotInfo

/**
 * How the access point came up (or why it did not).
 *
 * The ONLY supported success is [ManualTether]: the hub adopts the user's
 * manually-enabled phone Mobile Hotspot. The hub never creates an access point of
 * its own, and there is no credential channel that could publish one.
 */
sealed interface HotspotBringUp {
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
 *   1. the user's manual hotspot (detected and adopted) — peers need a LAN to reach;
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
 * A missing manual hotspot is NOT fatal to the listener: the hub degrades to
 * "listener up, hotspot unavailable" and reports that honestly instead of claiming
 * an access point it never detected.
 */
class HubBringUp(
    private val hub: HubServer,
    private val hotspot: HotspotController,
    private val discovery: Discovery,
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

    /**
     * Adopt the user's manually-enabled Mobile Hotspot, or report it unavailable.
     *
     * There is no programmatic start and no runtime hotspot permission to check:
     * the product uses ONLY the hotspot the user turned on by hand. The interface
     * scan is the single source of truth, and its absence is reported with the
     * exact action the user must take.
     */
    private fun bringUpHotspot(): HotspotBringUp {
        val outcome =
            detectManualTether()
                ?: HotspotBringUp.Unavailable(
                    "no manual hotspot; enable the phone's Mobile Hotspot",
                )
        return loggedOutcome(outcome)
    }

    /**
     * Record WHICH path produced the outcome plus the SSID and whether a passphrase
     * is present. The passphrase itself is NEVER logged: it is a live credential.
     */
    private fun loggedOutcome(outcome: HotspotBringUp): HotspotBringUp {
        when (outcome) {
            is HotspotBringUp.ManualTether ->
                Log.i(
                    TAG,
                    "hotspot bring-up: path=manual-tether ssid=${outcome.info.ssid} " +
                        "hasPassphrase=${outcome.info.passphrase.isNotBlank()}",
                )

            is HotspotBringUp.Unavailable ->
                Log.i(TAG, "hotspot bring-up: path=unavailable reason=${outcome.reason}")
        }
        return outcome
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
        private const val TAG: String = "HubBringUp"

        /** The DNS-SD instance name this hub advertises. */
        const val DISCOVERY_NAME: String = "phone-manager"
    }
}
