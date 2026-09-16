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
    private val GoContext: Context,
    private val GoLauncher: HotspotLauncher = PlatformHotspotLauncher(GoContext),
    private val GoTetherProbe: TetherProbe = TetherProbe { GoEnumerateInterfaces() },
) : HotspotController {

    private val GoMachine = HotspotStateMachine()
    private var GoReservation: WifiManager.LocalOnlyHotspotReservation? = null

    /** Current lifecycle state, exposed for the UI and for tests. */
    val GoState: HotspotState get() = GoMachine.GoState

    /** True while a live reservation is held; teardown must clear it. */
    fun GoHasLiveReservation(): Boolean = GoReservation != null

    override fun GoStartHotspot(): HotspotInfo {
        val GoTransition = GoMachine.GoAccept(HotspotEvent.START_REQUESTED)
        if (GoTransition is HotspotTransition.Rejected) {
            // Already ACTIVE (or STOPPING): a repeated start is a no-op that must NOT
            // transition state and must NOT launch again. Return the credentials of the
            // one live reservation so the caller is served without a second platform start.
            return GoInfoFor(GoLiveReservationOrFail(GoTransition))
        }
        if (!GoHasRequiredPermission()) {
            GoFail(HotspotFailure.PermissionDenied)
        }
        if (!GoIsLocationServicesEnabled()) {
            GoFail(HotspotFailure.LocationServicesDisabled)
        }
        return when (val GoLaunch = GoLauncher.GoLaunch()) {
            is HotspotLaunch.Granted -> {
                // Defensive: never orphan a live reservation by overwriting the field.
                GoReservation?.let { GoCloseQuietly(it) }
                GoReservation = GoLaunch.GoReservation
                val GoInfo = GoInfoFor(GoLaunch.GoReservation)
                GoMachine.GoAccept(HotspotEvent.STARTED)
                GoInfo
            }

            is HotspotLaunch.Denied -> GoFail(GoFailureForReason(GoLaunch.GoReasonCode))

            HotspotLaunch.TimedOut -> GoFail(
                HotspotFailure.StartFailed("start timed out after ${DEFAULT_LAUNCH_TIMEOUT_MS}ms"),
            )
        }
    }

    /** Credentials of [reservation]; the single place SSID/passphrase/gateway are read. */
    private fun GoInfoFor(reservation: WifiManager.LocalOnlyHotspotReservation): HotspotInfo =
        HotspotInfo(
            GoSsid = GoReadSsid(reservation),
            GoPassphrase = GoReadPassphrase(reservation),
            GoGatewayIp = GoDiscoverGateway(),
        )

    /**
     * The live reservation if one is held, otherwise a typed failure.
     *
     * A rejected start while `ACTIVE` implies a live reservation, so the null branch is
     * only the (unreachable) invariant breach; it fails typed rather than returning stale data.
     */
    private fun GoLiveReservationOrFail(
        rejection: HotspotTransition.Rejected,
    ): WifiManager.LocalOnlyHotspotReservation =
        GoReservation ?: GoFail(rejection.GoFailure)

    /**
     * Maps a platform refusal reason to its typed failure.
     *
     * [REASON_PERMISSION_DENIED] is the synthetic code the launcher emits when the
     * platform throws [SecurityException] — the user revoked the grant between the
     * pre-flight check and the call — so it must surface as
     * [HotspotFailure.PermissionDenied] and land the machine in `ERROR`, never
     * `ACTIVE`. Every other code is a genuine platform refusal.
     */
    private fun GoFailureForReason(goReasonCode: Int): HotspotFailure =
        if (goReasonCode == REASON_PERMISSION_DENIED) {
            HotspotFailure.PermissionDenied
        } else {
            HotspotFailure.StartFailed("platform refused, reason=$goReasonCode")
        }

    override fun GoStopHotspot() {
        GoMachine.GoAccept(HotspotEvent.STOP_REQUESTED)
        GoReservation?.let { GoCloseQuietly(it) }
        GoReservation = null
        GoMachine.GoAccept(HotspotEvent.STOPPED)
    }

    override fun GoIsActive(): Boolean = GoMachine.GoState == HotspotState.ACTIVE

    override fun GoDetectManualTether(): HotspotInfo? {
        val GoCandidate = GoPickTetherGateway(GoTetherProbe.GoCandidates()) ?: return null
        return HotspotInfo(
            GoSsid = MANUAL_TETHER_SSID,
            GoPassphrase = "",
            GoGatewayIp = GoCandidate.GoIpv4,
        )
    }

    private fun GoFail(failure: HotspotFailure): Nothing {
        GoMachine.GoAccept(HotspotEvent.START_FAILED, failure)
        throw HotspotUnavailableException(failure)
    }

    private fun GoHasRequiredPermission(): Boolean =
        GoContext.checkSelfPermission(GoRequiredPermission()) == PackageManager.PERMISSION_GRANTED

    private fun GoRequiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            // targetSdk 29 on this project: ACCESS_FINE_LOCATION is the active path.
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    private fun GoIsLocationServicesEnabled(): Boolean {
        val GoLocations = GoContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return GoLocations.isLocationEnabled
    }

    private fun GoReadSsid(reservation: WifiManager.LocalOnlyHotspotReservation): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { reservation.softApConfiguration.ssid }
                .getOrNull()
                .orEmpty()
                .let(::GoNormalizeSsid)
        } else {
            GoNormalizeSsid(
                runCatching { reservation.wifiConfiguration?.SSID }.getOrNull().orEmpty(),
            )
        }

    private fun GoReadPassphrase(reservation: WifiManager.LocalOnlyHotspotReservation): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { reservation.softApConfiguration.passphrase }.getOrNull().orEmpty()
        } else {
            runCatching { reservation.wifiConfiguration?.preSharedKey }.getOrNull().orEmpty()
        }

    private fun GoDiscoverGateway(): String =
        GoPickTetherGateway(GoTetherProbe.GoCandidates())?.GoIpv4 ?: DEFAULT_GATEWAY_IP

    private fun GoCloseQuietly(reservation: WifiManager.LocalOnlyHotspotReservation) {
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
        internal fun GoNormalizeSsid(raw: String): String =
            raw.removePrefix("\"").removeSuffix("\"")
    }
}
