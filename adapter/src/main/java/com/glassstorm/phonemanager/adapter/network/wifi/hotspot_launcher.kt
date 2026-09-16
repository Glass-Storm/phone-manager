package com.glassstorm.phonemanager.adapter.network.wifi

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A candidate network interface address found by enumerating [NetworkInterface]. */
data class TetherCandidate(
    val GoName: String,
    val GoIpv4: String,
)

/**
 * Enumerates network interfaces. Seam so tests can supply a deterministic list
 * (the host JVM has no tether interface).
 */
fun interface TetherProbe {
    fun GoCandidates(): List<TetherCandidate>
}

/** Outcome of asking the platform to start a LocalOnlyHotspot. */
sealed interface HotspotLaunch {
    /** The platform granted a live reservation that MUST be closed on teardown. */
    data class Granted(val GoReservation: WifiManager.LocalOnlyHotspotReservation) : HotspotLaunch

    /** The platform refused; [GoReasonCode] is one of `LocalOnlyHotspotCallback.ERROR_*`. */
    data class Denied(val GoReasonCode: Int) : HotspotLaunch

    /** The platform never answered within the launch timeout. */
    data object TimedOut : HotspotLaunch
}

/**
 * The platform call itself, behind a seam.
 *
 * Robolectric's `ShadowWifiManager` does not shadow `startLocalOnlyHotspot`, so the
 * adapter cannot rely on the shadow to drive the callback. The interface keeps the
 * controller logic (permission gate, state machine, parsing, teardown) fully testable
 * while [PlatformHotspotLauncher] holds the one unshadowable line.
 */
fun interface HotspotLauncher {
    fun GoLaunch(): HotspotLaunch
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
fun GoPickTetherGateway(candidates: List<TetherCandidate>): TetherCandidate? =
    candidates.firstOrNull { GoIsAccessPointInterface(it.GoName) }

private fun GoIsAccessPointInterface(name: String): Boolean =
    AP_INTERFACE_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

/**
 * Real Android [HotspotLauncher] using `WifiManager.startLocalOnlyHotspot` ONLY.
 *
 * `startLocalOnlyHotspotWithConfiguration` / `SoftApConfiguration` mutation are
 * SYSTEM APIs and are deliberately never referenced here.
 */
class PlatformHotspotLauncher(
    private val GoContext: Context,
    private val GoTimeoutMs: Long = LocalOnlyHotspotAdapter.DEFAULT_LAUNCH_TIMEOUT_MS,
) : HotspotLauncher {

    override fun GoLaunch(): HotspotLaunch {
        val GoWifi = GoContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return HotspotLaunch.Denied(LocalOnlyHotspotAdapter.REASON_NO_WIFI_SERVICE)
        val GoLatch = CountDownLatch(1)
        val GoOutcome = AtomicReference<HotspotLaunch?>(null)
        val GoCallback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                GoOutcome.set(HotspotLaunch.Granted(reservation))
                GoLatch.countDown()
            }

            override fun onFailed(reason: Int) {
                GoOutcome.set(HotspotLaunch.Denied(reason))
                GoLatch.countDown()
            }

            override fun onStopped() {
                GoLatch.countDown()
            }
        }
        GoWifi.startLocalOnlyHotspot(GoCallback, null)
        if (!GoLatch.await(GoTimeoutMs, TimeUnit.MILLISECONDS)) {
            return HotspotLaunch.TimedOut
        }
        return GoOutcome.get() ?: HotspotLaunch.TimedOut
    }
}

/** Enumerate real interfaces for the AP gateway address. */
internal fun GoEnumerateInterfaces(): List<TetherCandidate> =
    runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { GoInterface ->
                GoInterface.inetAddresses
                    .toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .map { GoAddress ->
                        TetherCandidate(GoName = GoInterface.name, GoIpv4 = GoAddress.hostAddress.orEmpty())
                    }
            }
    }.getOrDefault(emptyList())
