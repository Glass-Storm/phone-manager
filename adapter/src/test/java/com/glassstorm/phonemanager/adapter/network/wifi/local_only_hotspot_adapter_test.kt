package com.glassstorm.phonemanager.adapter.network.wifi

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.domain.dto.HotspotInfo
import com.glassstorm.phonemanager.domain.network.HotspotState
import com.glassstorm.phonemanager.domain.network.HotspotUnavailableException
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [LocalOnlyHotspotAdapter] on the active target path (API 29).
 *
 * Robolectric's [org.robolectric.shadows.ShadowWifiManager] has NO shadow for
 * `startLocalOnlyHotspot`, so the adapter takes a narrow [HotspotLauncher] seam
 * around the platform call. Everything else is exercised for real: the runtime
 * permission branch via `ShadowApplication`, location services via
 * `ShadowLocationManager`, and SSID/passphrase parsing against a REAL
 * `WifiManager.LocalOnlyHotspotReservation` built through its platform constructor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LocalOnlyHotspotAdapterTest {
    private val GoApp: Application = ApplicationProvider.getApplicationContext()
    private val GoWifi: WifiManager = GoApp.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private fun GoBuildWifiReservation(
        ssid: String,
        passphrase: String,
    ): WifiManager.LocalOnlyHotspotReservation {
        val GoConfig =
            WifiConfiguration().apply {
                SSID = ssid
                preSharedKey = passphrase
            }
        val GoCtor =
            WifiManager.LocalOnlyHotspotReservation::class.java
                .getDeclaredConstructor(WifiManager::class.java, WifiConfiguration::class.java)
        GoCtor.isAccessible = true
        return GoCtor.newInstance(GoWifi, GoConfig)
    }

    private fun GoAdapter(
        launch: HotspotLaunch,
        candidates: List<TetherCandidate> = emptyList(),
    ): LocalOnlyHotspotAdapter =
        LocalOnlyHotspotAdapter(
            GoContext = GoApp,
            GoLauncher = HotspotLauncher { launch },
            GoTetherProbe = TetherProbe { candidates },
        )

    private fun GoGrantFineLocation() {
        Shadows.shadowOf(GoApp).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun GoSetLocationEnabled(enabled: Boolean) {
        val GoLoc = GoApp.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(GoLoc).setLocationEnabled(enabled)
    }

    @Test
    fun `start without the location permission fails into ERROR and never ACTIVE`() {
        // Given a fresh adapter with the grant withheld (Robolectric default) and location on
        GoSetLocationEnabled(true)
        val GoHotspot = GoAdapter(HotspotLaunch.Granted(GoBuildWifiReservation("ssid", "pass")))

        // When the hotspot is started
        val GoThrown =
            assertThrows(HotspotUnavailableException::class.java) {
                GoHotspot.GoStartHotspot()
            }

        // Then the typed cause is PermissionDenied, the state is exactly ERROR, and it is not active
        assertThat(GoThrown.GoFailure)
            .isEqualTo(com.glassstorm.phonemanager.domain.network.HotspotFailure.PermissionDenied)
        assertThat(GoHotspot.GoState).isEqualTo(HotspotState.ERROR)
        assertThat(GoHotspot.GoState).isNotEqualTo(HotspotState.ACTIVE)
        assertThat(GoHotspot.GoIsActive()).isFalse()
    }

    @Test
    fun `start with the permission granted but location services off fails into ERROR`() {
        // Given the runtime permission granted but location services switched off
        GoGrantFineLocation()
        GoSetLocationEnabled(false)
        val GoHotspot = GoAdapter(HotspotLaunch.Granted(GoBuildWifiReservation("ssid", "pass")))

        // When the hotspot is started
        val GoThrown =
            assertThrows(HotspotUnavailableException::class.java) {
                GoHotspot.GoStartHotspot()
            }

        // Then it fails with LocationServicesDisabled and lands in ERROR
        assertThat(GoThrown.GoFailure).isEqualTo(
            com.glassstorm.phonemanager.domain.network.HotspotFailure.LocationServicesDisabled,
        )
        assertThat(GoHotspot.GoState).isEqualTo(HotspotState.ERROR)
        assertThat(GoHotspot.GoIsActive()).isFalse()
    }

    @Test
    fun `start with permission and location on parses the SSID and passphrase and goes ACTIVE`() {
        // Given a fully-permitted device and a platform reservation carrying credentials
        GoGrantFineLocation()
        GoSetLocationEnabled(true)
        val GoReservation = GoBuildWifiReservation("GlassStorm-1234", "s3cret-pass")
        val GoHotspot = GoAdapter(HotspotLaunch.Granted(GoReservation))

        // When the hotspot is started
        val GoInfo: HotspotInfo = GoHotspot.GoStartHotspot()

        // Then the real reservation's credentials are parsed verbatim and the state is ACTIVE
        assertThat(GoInfo.GoSsid).isEqualTo("GlassStorm-1234")
        assertThat(GoInfo.GoPassphrase).isEqualTo("s3cret-pass")
        assertThat(GoInfo.GoGatewayIp).isNotEmpty()
        assertThat(GoHotspot.GoState).isEqualTo(HotspotState.ACTIVE)
        assertThat(GoHotspot.GoIsActive()).isTrue()
    }

    @Test
    fun `gateway ip falls back to the documented default when no tether interface exists`() {
        // Given a host with no tether-style network interface
        GoGrantFineLocation()
        GoSetLocationEnabled(true)
        val GoHotspot = GoAdapter(HotspotLaunch.Granted(GoBuildWifiReservation("ssid", "pass")))

        // When the hotspot is started
        val GoInfo = GoHotspot.GoStartHotspot()

        // Then the documented default gateway is reported
        assertThat(GoInfo.GoGatewayIp).isEqualTo(LocalOnlyHotspotAdapter.DEFAULT_GATEWAY_IP)
    }

    @Test
    fun `a platform start failure lands in ERROR with the typed reason`() {
        // Given a permitted device whose platform refuses to start the AP
        GoGrantFineLocation()
        GoSetLocationEnabled(true)
        val GoHotspot = GoAdapter(HotspotLaunch.Denied(GoReasonCode = 42))

        // When the hotspot is started
        val GoThrown =
            assertThrows(HotspotUnavailableException::class.java) {
                GoHotspot.GoStartHotspot()
            }

        // Then the state is ERROR (never ACTIVE) and the reason mentions the platform code
        assertThat(GoHotspot.GoState).isEqualTo(HotspotState.ERROR)
        assertThat(GoHotspot.GoIsActive()).isFalse()
        assertThat(
            (GoThrown.GoFailure as com.glassstorm.phonemanager.domain.network.HotspotFailure.StartFailed)
                .GoReason,
        ).contains("42")
    }

    @Test
    fun `a platform timeout lands in ERROR rather than staying in STARTING`() {
        // Given a permitted device whose platform never answers
        GoGrantFineLocation()
        GoSetLocationEnabled(true)
        val GoHotspot = GoAdapter(HotspotLaunch.TimedOut)

        // When the hotspot is started
        assertThrows(HotspotUnavailableException::class.java) { GoHotspot.GoStartHotspot() }

        // Then the machine is not left hanging in STARTING
        assertThat(GoHotspot.GoState).isEqualTo(HotspotState.ERROR)
    }

    @Test
    fun `stop closes the reservation idempotently`() {
        // Given a running hotspot holding a live reservation
        GoGrantFineLocation()
        GoSetLocationEnabled(true)
        val GoHotspot = GoAdapter(HotspotLaunch.Granted(GoBuildWifiReservation("ssid", "pass")))
        GoHotspot.GoStartHotspot()
        assertThat(GoHotspot.GoHasLiveReservation()).isTrue()

        // When stop is called twice
        GoHotspot.GoStopHotspot()
        GoHotspot.GoStopHotspot()

        // Then the reservation is gone, the state returned to IDLE and nothing threw
        assertThat(GoHotspot.GoHasLiveReservation()).isFalse()
        assertThat(GoHotspot.GoState).isEqualTo(HotspotState.IDLE)
        assertThat(GoHotspot.GoIsActive()).isFalse()
    }

    @Test
    fun `a repeated start while active reuses the live reservation and never leaks`() {
        // Given a permitted device and a launcher that records every platform launch,
        // so a leaked re-launch on a double start becomes observable
        GoGrantFineLocation()
        GoSetLocationEnabled(true)
        val GoLaunches = mutableListOf<WifiManager.LocalOnlyHotspotReservation>()
        val GoHotspot =
            LocalOnlyHotspotAdapter(
                GoContext = GoApp,
                GoLauncher =
                    HotspotLauncher {
                        val GoNext = GoBuildWifiReservation("ssid-${GoLaunches.size + 1}", "pass")
                        GoLaunches += GoNext
                        HotspotLaunch.Granted(GoNext)
                    },
                GoTetherProbe = TetherProbe { emptyList() },
            )
        val GoFirstInfo = GoHotspot.GoStartHotspot()
        assertThat(GoFirstInfo.GoSsid).isEqualTo("ssid-1")

        // When the hotspot is started AGAIN while already ACTIVE
        val GoSecondInfo = GoHotspot.GoStartHotspot()

        // Then the platform was launched exactly once, so no second reservation exists
        // to leak, and the live credentials from the first start are returned verbatim
        assertThat(GoLaunches).hasSize(1)
        assertThat(GoSecondInfo.GoSsid).isEqualTo(GoFirstInfo.GoSsid)
        assertThat(GoSecondInfo.GoPassphrase).isEqualTo(GoFirstInfo.GoPassphrase)
        assertThat(GoSecondInfo.GoGatewayIp).isEqualTo(GoFirstInfo.GoGatewayIp)

        // And the machine never left ACTIVE and still holds exactly its one reservation
        assertThat(GoHotspot.GoState).isEqualTo(HotspotState.ACTIVE)
        assertThat(GoHotspot.GoIsActive()).isTrue()
        assertThat(GoHotspot.GoHasLiveReservation()).isTrue()

        // And teardown afterwards still closes cleanly and stays idempotent
        GoHotspot.GoStopHotspot()
        GoHotspot.GoStopHotspot()
        assertThat(GoHotspot.GoHasLiveReservation()).isFalse()
        assertThat(GoHotspot.GoState).isEqualTo(HotspotState.IDLE)
        assertThat(GoHotspot.GoIsActive()).isFalse()
    }

    @Test
    fun `stop when never started is a no-op`() {
        // Given a never-started adapter
        val GoHotspot = GoAdapter(HotspotLaunch.TimedOut)

        // When stop is called
        GoHotspot.GoStopHotspot()

        // Then the state stays IDLE without throwing
        assertThat(GoHotspot.GoState).isEqualTo(HotspotState.IDLE)
    }

    @Test
    fun `a real reservation tolerates being closed twice`() {
        // Given a reservation built by the platform constructor
        val GoReservation = GoBuildWifiReservation("ssid", "pass")

        // When it is closed twice
        GoReservation.close()
        GoReservation.close()

        // Then neither call throws (the adapter relies on this for idempotent teardown)
    }

    @Test
    fun `manual tether detection reports the tether interface gateway`() {
        // Given a probe exposing a loopback, a station wlan0 and an AP interface
        val GoCandidates =
            listOf(
                TetherCandidate(GoName = "lo", GoIpv4 = "127.0.0.1"),
                TetherCandidate(GoName = "wlan0", GoIpv4 = "192.168.1.5"),
                TetherCandidate(GoName = "ap0", GoIpv4 = "192.168.43.1"),
            )
        val GoHotspot = GoAdapter(HotspotLaunch.TimedOut, GoCandidates)

        // When a manual tether is detected
        val GoInfo = GoHotspot.GoDetectManualTether()

        // Then the AP interface's address is returned, not the station or loopback
        assertThat(GoInfo).isNotNull()
        assertThat(GoInfo?.GoGatewayIp).isEqualTo("192.168.43.1")
    }

    @Test
    fun `manual tether detection returns null when no tether interface exists`() {
        // Given only a loopback and a station interface
        val GoHotspot =
            GoAdapter(
                HotspotLaunch.TimedOut,
                listOf(
                    TetherCandidate(GoName = "lo", GoIpv4 = "127.0.0.1"),
                    TetherCandidate(GoName = "wlan0", GoIpv4 = "192.168.1.5"),
                ),
            )

        // When a manual tether is detected
        val GoInfo = GoHotspot.GoDetectManualTether()

        // Then nothing is reported
        assertThat(GoInfo).isNull()
    }

    @Test
    fun `the tether selector prefers AP-style interface names over station ones`() {
        // Given several interfaces in an arbitrary order
        val GoChosen =
            GoPickTetherGateway(
                listOf(
                    TetherCandidate(GoName = "wlan0", GoIpv4 = "192.168.1.5"),
                    TetherCandidate(GoName = "lo", GoIpv4 = "127.0.0.1"),
                    TetherCandidate(GoName = "swlan0", GoIpv4 = "192.168.12.1"),
                ),
            )

        // Then the soft-AP interface wins
        assertThat(GoChosen?.GoIpv4).isEqualTo("192.168.12.1")
    }
}
