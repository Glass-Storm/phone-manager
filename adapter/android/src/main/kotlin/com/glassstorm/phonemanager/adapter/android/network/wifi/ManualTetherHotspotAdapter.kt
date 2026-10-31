package com.glassstorm.phonemanager.adapter.android.network.wifi

import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.network.HotspotFailure
import com.glassstorm.phonemanager.core.domain.network.HotspotUnavailableException
import com.glassstorm.phonemanager.core.model.HotspotInfo
import java.net.Inet4Address
import java.net.NetworkInterface

/** A candidate network interface address found by enumerating [NetworkInterface]. */
data class TetherCandidate(
    val name: String,
    val ipv4: String,
)

/**
 * Enumerates network interfaces. Seam so tests can supply a deterministic list
 * (the host JVM has no tether interface).
 */
fun interface TetherProbe {
    fun candidates(): List<TetherCandidate>
}

/** Interface-name prefixes Android uses for soft-AP/tether interfaces. */
private val AP_INTERFACE_PREFIXES = listOf("ap", "swlan", "softap", "wlan1")

/**
 * Pick the tether interface from [candidates].
 *
 * There is no SDK API for the AP gateway address, so it is discovered structurally:
 * soft-AP interfaces (`ap0`, `swlan0`, `softap0`, `wlan1`) are matched by name.
 * Station (`wlan0`) and loopback interfaces are never treated as a tether.
 */
fun pickTetherGateway(candidates: List<TetherCandidate>): TetherCandidate? = candidates.firstOrNull { isAccessPointInterface(it.name) }

private fun isAccessPointInterface(name: String): Boolean = AP_INTERFACE_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

/**
 * [HotspotController] that ADOPTS the user's manually-enabled system hotspot.
 *
 * The hub never creates an access point: the product's only supported network
 * posture is the user turning on the phone's Mobile Hotspot by hand. This adapter
 * therefore discovers the soft-AP interface that hotspot already created and
 * reports its gateway address. It never starts, stops, or mutates the user's
 * hotspot.
 *
 * The user owns the hotspot credentials, so the SSID is reported as the
 * [MANUAL_TETHER_SSID] placeholder and the passphrase is empty: peers are expected
 * to be configured with the real SSID/passphrase the user chose, and the gateway
 * address is what they dial.
 */
class ManualTetherHotspotAdapter(
    private val tetherProbe: TetherProbe = TetherProbe { enumerateInterfaces() },
) : HotspotController {
    /**
     * Detect the manual tether and return it, or fail typed when none is active.
     *
     * This is deliberately NOT a platform start: `startHotspot` keeps the
     * [HotspotController] contract ("bring the access point up and return its
     * credentials") for callers, but the only thing it can bring up is the
     * already-enabled manual tether.
     */
    override fun startHotspot(): HotspotInfo =
        detectManualTether() ?: throw HotspotUnavailableException(
            HotspotFailure.StartFailed(NO_MANUAL_HOTSPOT_REASON),
        )

    /** Never touches the user's hotspot: teardown is always a no-op. */
    override fun stopHotspot() = Unit

    /** True only while a soft-AP interface is currently visible. */
    override fun isActive(): Boolean = detectManualTether() != null

    override fun detectManualTether(): HotspotInfo? {
        val candidate = pickTetherGateway(tetherProbe.candidates()) ?: return null
        return HotspotInfo(
            ssid = MANUAL_TETHER_SSID,
            passphrase = "",
            gatewayIp = candidate.ipv4,
        )
    }

    companion object {
        /** Placeholder SSID for a manually-enabled system hotspot (credentials are user-owned). */
        const val MANUAL_TETHER_SSID: String = "manual-tether"

        /** User-facing reason reported when no tether interface is present. */
        const val NO_MANUAL_HOTSPOT_REASON: String = "no manual hotspot; enable the phone's Mobile Hotspot"
    }
}

/** Enumerate real interfaces for the AP gateway address. */
internal fun enumerateInterfaces(): List<TetherCandidate> =
    runCatching {
        NetworkInterface
            .getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses
                    .toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .map { address ->
                        TetherCandidate(name = iface.name, ipv4 = address.hostAddress.orEmpty())
                    }
            }
    }.getOrDefault(emptyList())
