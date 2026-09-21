package com.glassstorm.phonemanager.adapter.network.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

/** Outcome of asking the platform to start a LocalOnlyHotspot. */
sealed interface HotspotLaunch {
    /** The platform granted a live reservation that MUST be closed on teardown. */
    data class Granted(
        val reservation: WifiManager.LocalOnlyHotspotReservation,
    ) : HotspotLaunch

    /** The platform refused; [reasonCode] is one of `LocalOnlyHotspotCallback.ERROR_*`. */
    data class Denied(
        val reasonCode: Int,
    ) : HotspotLaunch

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
    fun launch(): HotspotLaunch
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
fun pickTetherGateway(candidates: List<TetherCandidate>): TetherCandidate? =
    candidates.firstOrNull { isAccessPointInterface(it.name) }

private fun isAccessPointInterface(name: String): Boolean = AP_INTERFACE_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

/**
 * Real Android [HotspotLauncher] using `WifiManager.startLocalOnlyHotspot` ONLY.
 *
 * `startLocalOnlyHotspotWithConfiguration` / `SoftApConfiguration` mutation are
 * SYSTEM APIs and are deliberately never referenced here.
 */
class PlatformHotspotLauncher(
    private val context: Context,
    private val timeoutMs: Long = LocalOnlyHotspotAdapter.DEFAULT_LAUNCH_TIMEOUT_MS,
) : HotspotLauncher {
    /**
     * Starts the platform hotspot.
     *
     * `@SuppressLint("MissingPermission")` is load-bearing but honest: lint cannot
     * see across functions, so it cannot observe that the caller
     * [LocalOnlyHotspotAdapter.startHotspot] gates this with
     * `hasRequiredPermission()` (API-branched `ACCESS_FINE_LOCATION` below API 33,
     * `NEARBY_WIFI_DEVICES` from API 33) plus the location-services check before it
     * ever invokes this launcher. The grant is revocable, so it may be withdrawn
     * between that check and this call; the call is therefore ALSO defended at
     * runtime (below) rather than relying on the annotation alone.
     */
    @SuppressLint("MissingPermission") // Guarded by LocalOnlyHotspotAdapter.hasRequiredPermission(); SecurityException is handled below.
    override fun launch(): HotspotLaunch {
        val wifi =
            context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return HotspotLaunch.Denied(LocalOnlyHotspotAdapter.REASON_NO_WIFI_SERVICE)
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<HotspotLaunch?>(null)
        val callback =
            object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    outcome.set(HotspotLaunch.Granted(reservation))
                    latch.countDown()
                }

                override fun onFailed(reason: Int) {
                    outcome.set(HotspotLaunch.Denied(reason))
                    latch.countDown()
                }

                override fun onStopped() {
                    latch.countDown()
                }
            }
        try {
            wifi.startLocalOnlyHotspot(callback, null)
        } catch (denied: SecurityException) {
            outcome.set(HotspotLaunch.Denied(LocalOnlyHotspotAdapter.REASON_PERMISSION_DENIED))
            latch.countDown()
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return HotspotLaunch.TimedOut
        }
        return outcome.get() ?: HotspotLaunch.TimedOut
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
