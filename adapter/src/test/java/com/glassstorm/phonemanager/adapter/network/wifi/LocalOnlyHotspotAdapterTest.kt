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
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val wifi: WifiManager = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private fun buildWifiReservation(
        ssid: String,
        passphrase: String,
    ): WifiManager.LocalOnlyHotspotReservation {
        val config =
            WifiConfiguration().apply {
                SSID = ssid
                preSharedKey = passphrase
            }
        val ctor =
            WifiManager.LocalOnlyHotspotReservation::class.java
                .getDeclaredConstructor(WifiManager::class.java, WifiConfiguration::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(wifi, config)
    }

    private fun adapter(
        launch: HotspotLaunch,
        candidates: List<TetherCandidate> = emptyList(),
    ): LocalOnlyHotspotAdapter =
        LocalOnlyHotspotAdapter(
            context = app,
            launcher = HotspotLauncher { launch },
            tetherProbe = TetherProbe { candidates },
        )

    private fun grantFineLocation() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun setLocationEnabled(enabled: Boolean) {
        val loc = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(loc).setLocationEnabled(enabled)
    }

    @Test
    fun `start without the location permission fails into ERROR and never ACTIVE`() {
        // Given a fresh adapter with the grant withheld (Robolectric default) and location on
        setLocationEnabled(true)
        val hotspot = adapter(HotspotLaunch.Granted(buildWifiReservation("ssid", "pass")))

        // When the hotspot is started
        val thrown =
            assertThrows(HotspotUnavailableException::class.java) {
                hotspot.startHotspot()
            }

        // Then the typed cause is PermissionDenied, the state is exactly ERROR, and it is not active
        assertThat(thrown.failure)
            .isEqualTo(com.glassstorm.phonemanager.domain.network.HotspotFailure.PermissionDenied)
        assertThat(hotspot.state).isEqualTo(HotspotState.ERROR)
        assertThat(hotspot.state).isNotEqualTo(HotspotState.ACTIVE)
        assertThat(hotspot.isActive()).isFalse()
    }

    @Test
    fun `start with the permission granted but location services off fails into ERROR`() {
        // Given the runtime permission granted but location services switched off
        grantFineLocation()
        setLocationEnabled(false)
        val hotspot = adapter(HotspotLaunch.Granted(buildWifiReservation("ssid", "pass")))

        // When the hotspot is started
        val thrown =
            assertThrows(HotspotUnavailableException::class.java) {
                hotspot.startHotspot()
            }

        // Then it fails with LocationServicesDisabled and lands in ERROR
        assertThat(thrown.failure).isEqualTo(
            com.glassstorm.phonemanager.domain.network.HotspotFailure.LocationServicesDisabled,
        )
        assertThat(hotspot.state).isEqualTo(HotspotState.ERROR)
        assertThat(hotspot.isActive()).isFalse()
    }

    @Test
    fun `start with permission and location on parses the SSID and passphrase and goes ACTIVE`() {
        // Given a fully-permitted device and a platform reservation carrying credentials
        grantFineLocation()
        setLocationEnabled(true)
        val reservation = buildWifiReservation("GlassStorm-1234", "s3cret-pass")
        val hotspot = adapter(HotspotLaunch.Granted(reservation))

        // When the hotspot is started
        val info: HotspotInfo = hotspot.startHotspot()

        // Then the real reservation's credentials are parsed verbatim and the state is ACTIVE
        assertThat(info.ssid).isEqualTo("GlassStorm-1234")
        assertThat(info.passphrase).isEqualTo("s3cret-pass")
        assertThat(info.gatewayIp).isNotEmpty()
        assertThat(hotspot.state).isEqualTo(HotspotState.ACTIVE)
        assertThat(hotspot.isActive()).isTrue()
    }

    @Test
    fun `gateway ip falls back to the documented default when no tether interface exists`() {
        // Given a host with no tether-style network interface
        grantFineLocation()
        setLocationEnabled(true)
        val hotspot = adapter(HotspotLaunch.Granted(buildWifiReservation("ssid", "pass")))

        // When the hotspot is started
        val info = hotspot.startHotspot()

        // Then the documented default gateway is reported
        assertThat(info.gatewayIp).isEqualTo(LocalOnlyHotspotAdapter.DEFAULT_GATEWAY_IP)
    }

    @Test
    fun `a platform start failure lands in ERROR with the typed reason`() {
        // Given a permitted device whose platform refuses to start the AP
        grantFineLocation()
        setLocationEnabled(true)
        val hotspot = adapter(HotspotLaunch.Denied(reasonCode = 42))

        // When the hotspot is started
        val thrown =
            assertThrows(HotspotUnavailableException::class.java) {
                hotspot.startHotspot()
            }

        // Then the state is ERROR (never ACTIVE) and the reason mentions the platform code
        assertThat(hotspot.state).isEqualTo(HotspotState.ERROR)
        assertThat(hotspot.isActive()).isFalse()
        assertThat(
            (thrown.failure as com.glassstorm.phonemanager.domain.network.HotspotFailure.StartFailed)
                .reason,
        ).contains("42")
    }

    @Test
    fun `a platform timeout lands in ERROR rather than staying in STARTING`() {
        // Given a permitted device whose platform never answers
        grantFineLocation()
        setLocationEnabled(true)
        val hotspot = adapter(HotspotLaunch.TimedOut)

        // When the hotspot is started
        assertThrows(HotspotUnavailableException::class.java) { hotspot.startHotspot() }

        // Then the machine is not left hanging in STARTING
        assertThat(hotspot.state).isEqualTo(HotspotState.ERROR)
    }

    @Test
    fun `stop closes the reservation idempotently`() {
        // Given a running hotspot holding a live reservation
        grantFineLocation()
        setLocationEnabled(true)
        val hotspot = adapter(HotspotLaunch.Granted(buildWifiReservation("ssid", "pass")))
        hotspot.startHotspot()
        assertThat(hotspot.hasLiveReservation()).isTrue()

        // When stop is called twice
        hotspot.stopHotspot()
        hotspot.stopHotspot()

        // Then the reservation is gone, the state returned to IDLE and nothing threw
        assertThat(hotspot.hasLiveReservation()).isFalse()
        assertThat(hotspot.state).isEqualTo(HotspotState.IDLE)
        assertThat(hotspot.isActive()).isFalse()
    }

    @Test
    fun `a repeated start while active reuses the live reservation and never leaks`() {
        // Given a permitted device and a launcher that records every platform launch,
        // so a leaked re-launch on a double start becomes observable
        grantFineLocation()
        setLocationEnabled(true)
        val launches = mutableListOf<WifiManager.LocalOnlyHotspotReservation>()
        val hotspot =
            LocalOnlyHotspotAdapter(
                context = app,
                launcher =
                    HotspotLauncher {
                        val next = buildWifiReservation("ssid-${launches.size + 1}", "pass")
                        launches += next
                        HotspotLaunch.Granted(next)
                    },
                tetherProbe = TetherProbe { emptyList() },
            )
        val firstInfo = hotspot.startHotspot()
        assertThat(firstInfo.ssid).isEqualTo("ssid-1")

        // When the hotspot is started AGAIN while already ACTIVE
        val secondInfo = hotspot.startHotspot()

        // Then the platform was launched exactly once, so no second reservation exists
        // to leak, and the live credentials from the first start are returned verbatim
        assertThat(launches).hasSize(1)
        assertThat(secondInfo.ssid).isEqualTo(firstInfo.ssid)
        assertThat(secondInfo.passphrase).isEqualTo(firstInfo.passphrase)
        assertThat(secondInfo.gatewayIp).isEqualTo(firstInfo.gatewayIp)

        // And the machine never left ACTIVE and still holds exactly its one reservation
        assertThat(hotspot.state).isEqualTo(HotspotState.ACTIVE)
        assertThat(hotspot.isActive()).isTrue()
        assertThat(hotspot.hasLiveReservation()).isTrue()

        // And teardown afterwards still closes cleanly and stays idempotent
        hotspot.stopHotspot()
        hotspot.stopHotspot()
        assertThat(hotspot.hasLiveReservation()).isFalse()
        assertThat(hotspot.state).isEqualTo(HotspotState.IDLE)
        assertThat(hotspot.isActive()).isFalse()
    }

    @Test
    fun `stop when never started is a no-op`() {
        // Given a never-started adapter
        val hotspot = adapter(HotspotLaunch.TimedOut)

        // When stop is called
        hotspot.stopHotspot()

        // Then the state stays IDLE without throwing
        assertThat(hotspot.state).isEqualTo(HotspotState.IDLE)
    }

    @Test
    fun `a real reservation tolerates being closed twice`() {
        // Given a reservation built by the platform constructor
        val reservation = buildWifiReservation("ssid", "pass")

        // When it is closed twice
        reservation.close()
        reservation.close()

        // Then neither call throws (the adapter relies on this for idempotent teardown)
    }

    @Test
    fun `manual tether detection reports the tether interface gateway`() {
        // Given a probe exposing a loopback, a station wlan0 and an AP interface
        val candidates =
            listOf(
                TetherCandidate(name = "lo", ipv4 = "127.0.0.1"),
                TetherCandidate(name = "wlan0", ipv4 = "192.168.1.5"),
                TetherCandidate(name = "ap0", ipv4 = "192.168.43.1"),
            )
        val hotspot = adapter(HotspotLaunch.TimedOut, candidates)

        // When a manual tether is detected
        val info = hotspot.detectManualTether()

        // Then the AP interface's address is returned, not the station or loopback
        assertThat(info).isNotNull()
        assertThat(info?.gatewayIp).isEqualTo("192.168.43.1")
    }

    @Test
    fun `manual tether detection returns null when no tether interface exists`() {
        // Given only a loopback and a station interface
        val hotspot =
            adapter(
                HotspotLaunch.TimedOut,
                listOf(
                    TetherCandidate(name = "lo", ipv4 = "127.0.0.1"),
                    TetherCandidate(name = "wlan0", ipv4 = "192.168.1.5"),
                ),
            )

        // When a manual tether is detected
        val info = hotspot.detectManualTether()

        // Then nothing is reported
        assertThat(info).isNull()
    }

    @Test
    fun `the tether selector prefers AP-style interface names over station ones`() {
        // Given several interfaces in an arbitrary order
        val chosen =
            pickTetherGateway(
                listOf(
                    TetherCandidate(name = "wlan0", ipv4 = "192.168.1.5"),
                    TetherCandidate(name = "lo", ipv4 = "127.0.0.1"),
                    TetherCandidate(name = "swlan0", ipv4 = "192.168.12.1"),
                ),
            )

        // Then the soft-AP interface wins
        assertThat(chosen?.ipv4).isEqualTo("192.168.12.1")
    }
}
