package com.glassstorm.phonemanager.adapter.network.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import com.glassstorm.phonemanager.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.domain.dto.HotspotInfo
import com.glassstorm.phonemanager.domain.network.HotspotEvent
import com.glassstorm.phonemanager.domain.network.HotspotFailure
import com.glassstorm.phonemanager.domain.network.HotspotState
import com.glassstorm.phonemanager.domain.network.HotspotStateMachine
import com.glassstorm.phonemanager.domain.network.HotspotTransition
import com.glassstorm.phonemanager.domain.network.HotspotUnavailableException

/**
 * Android [HotspotController] backed by `LocalOnlyHotspot` with a manual-tether fallback.
 *
 * Lifecycle is delegated to the pure-JVM [HotspotStateMachine] so `ERROR` vs `ACTIVE`
 * is never ambiguous. The `LocalOnlyHotspotReservation` is always closed on teardown
 * and closing is idempotent.
 *
 * The platform call lives behind [HotspotLauncher] (see `hotspot_launcher.kt`) because
 * Robolectric's `ShadowWifiManager` has no shadow for `startLocalOnlyHotspot`.
 */
class LocalOnlyHotspotAdapter(
    private val context: Context,
    private val launcher: HotspotLauncher = PlatformHotspotLauncher(context),
    private val tetherProbe: TetherProbe = TetherProbe { enumerateInterfaces() },
) : HotspotController {
    private val machine = HotspotStateMachine()
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /** Current lifecycle state, exposed for the UI and for tests. */
    val state: HotspotState get() = machine.state

    /** True while a live reservation is held; teardown must clear it. */
    fun hasLiveReservation(): Boolean = reservation != null

    override fun startHotspot(): HotspotInfo {
        val transition = machine.accept(HotspotEvent.START_REQUESTED)
        if (transition is HotspotTransition.Rejected) {
            // Already ACTIVE (or STOPPING): a repeated start is a no-op that must NOT
            // transition state and must NOT launch again. Return the credentials of the
            // one live reservation so the caller is served without a second platform start.
            return infoFor(liveReservationOrFail(transition))
        }
        if (!hasRequiredPermission()) {
            fail(HotspotFailure.PermissionDenied)
        }
        if (!isLocationServicesEnabled()) {
            fail(HotspotFailure.LocationServicesDisabled)
        }
        return when (val launch = launcher.launch()) {
            is HotspotLaunch.Granted -> {
                // Defensive: never orphan a live reservation by overwriting the field.
                reservation?.let { closeQuietly(it) }
                reservation = launch.reservation
                val info = infoFor(launch.reservation)
                machine.accept(HotspotEvent.STARTED)
                info
            }

            is HotspotLaunch.Denied -> fail(failureForReason(launch.reasonCode))

            HotspotLaunch.TimedOut ->
                fail(
                    HotspotFailure.StartFailed("start timed out after ${DEFAULT_LAUNCH_TIMEOUT_MS}ms"),
                )
        }
    }

    /** Credentials of [reservation]; the single place SSID/passphrase/gateway are read. */
    private fun infoFor(reservation: WifiManager.LocalOnlyHotspotReservation): HotspotInfo =
        HotspotInfo(
            ssid = readSsid(reservation),
            passphrase = readPassphrase(reservation),
            gatewayIp = discoverGateway(),
        )

    /**
     * The live reservation if one is held, otherwise a typed failure.
     *
     * A rejected start while `ACTIVE` implies a live reservation, so the null branch is
     * only the (unreachable) invariant breach; it fails typed rather than returning stale data.
     */
    private fun liveReservationOrFail(rejection: HotspotTransition.Rejected): WifiManager.LocalOnlyHotspotReservation =
        reservation ?: fail(rejection.failure)

    /**
     * Maps a platform refusal reason to its typed failure.
     *
     * [REASON_PERMISSION_DENIED] is the synthetic code the launcher emits when the
     * platform throws [SecurityException] — the user revoked the grant between the
     * pre-flight check and the call — so it must surface as
     * [HotspotFailure.PermissionDenied] and land the machine in `ERROR`, never
     * `ACTIVE`. Every other code is a genuine platform refusal.
     */
    private fun failureForReason(goReasonCode: Int): HotspotFailure =
        if (goReasonCode == REASON_PERMISSION_DENIED) {
            HotspotFailure.PermissionDenied
        } else {
            HotspotFailure.StartFailed("platform refused, reason=$goReasonCode")
        }

    override fun stopHotspot() {
        machine.accept(HotspotEvent.STOP_REQUESTED)
        reservation?.let { closeQuietly(it) }
        reservation = null
        machine.accept(HotspotEvent.STOPPED)
    }

    override fun isActive(): Boolean = machine.state == HotspotState.ACTIVE

    override fun detectManualTether(): HotspotInfo? {
        val candidate = pickTetherGateway(tetherProbe.candidates()) ?: return null
        return HotspotInfo(
            ssid = MANUAL_TETHER_SSID,
            passphrase = "",
            gatewayIp = candidate.ipv4,
        )
    }

    private fun fail(failure: HotspotFailure): Nothing {
        machine.accept(HotspotEvent.START_FAILED, failure)
        throw HotspotUnavailableException(failure)
    }

    private fun hasRequiredPermission(): Boolean =
        context.checkSelfPermission(requiredPermission()) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            // targetSdk 29 on this project: ACCESS_FINE_LOCATION is the active path.
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    private fun isLocationServicesEnabled(): Boolean {
        val locations =
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return false
        return locations.isLocationEnabled
    }

    private fun readSsid(reservation: WifiManager.LocalOnlyHotspotReservation): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { reservation.softApConfiguration.ssid }
                .getOrNull()
                .orEmpty()
                .let(::normalizeSsid)
        } else {
            normalizeSsid(
                runCatching { reservation.wifiConfiguration?.SSID }.getOrNull().orEmpty(),
            )
        }

    private fun readPassphrase(reservation: WifiManager.LocalOnlyHotspotReservation): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { reservation.softApConfiguration.passphrase }.getOrNull().orEmpty()
        } else {
            runCatching { reservation.wifiConfiguration?.preSharedKey }.getOrNull().orEmpty()
        }

    private fun discoverGateway(): String = pickTetherGateway(tetherProbe.candidates())?.ipv4 ?: DEFAULT_GATEWAY_IP

    private fun closeQuietly(reservation: WifiManager.LocalOnlyHotspotReservation) {
        runCatching { reservation.close() }
    }

    companion object {
        /** Standard Android tether gateway; used when interface discovery finds nothing. */
        const val DEFAULT_GATEWAY_IP: String = "192.168.43.1"

        /** Placeholder SSID for a manually-enabled system hotspot (credentials are user-owned). */
        const val MANUAL_TETHER_SSID: String = "manual-tether"

        /** How long to wait for the platform callback before failing into ERROR. */
        const val DEFAULT_LAUNCH_TIMEOUT_MS: Long = 10_000L

        /** Local reason code used when the device exposes no WifiManager at all. */
        const val REASON_NO_WIFI_SERVICE: Int = -1

        /**
         * Local reason code used when the platform throws [SecurityException] at the
         * call site because the revocable Wi-Fi permission was withdrawn after the
         * pre-flight check. Distinct from [REASON_NO_WIFI_SERVICE] and from every
         * `LocalOnlyHotspotCallback.ERROR_*` code.
         */
        const val REASON_PERMISSION_DENIED: Int = -2

        /** Strips the surrounding quotes Android sometimes puts around a raw SSID. */
        internal fun normalizeSsid(raw: String): String = raw.removePrefix("\"").removeSuffix("\"")
    }
}
