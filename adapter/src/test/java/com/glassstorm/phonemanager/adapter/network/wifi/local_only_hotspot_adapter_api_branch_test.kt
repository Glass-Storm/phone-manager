package com.glassstorm.phonemanager.adapter.network.wifi

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
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
 * Build a [SoftApConfiguration] with credentials.
 *
 * `SoftApConfiguration.Builder.setSsid/setPassphrase` are `@hide` in the public
 * SDK stub (only `setChannels`/`build` are exposed), so the test drives them
 * reflectively — exactly what the Robolectric android-all jar supports.
 */
private fun GoBuildSoftApConfig(
    ssid: String,
    passphrase: String,
): SoftApConfiguration {
    val GoBuilder = SoftApConfiguration.Builder()
    val GoBuilderClass = SoftApConfiguration.Builder::class.java
    GoBuilderClass.getMethod("setSsid", String::class.java).invoke(GoBuilder, ssid)
    GoBuilderClass
        .getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
        .invoke(GoBuilder, passphrase, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
    return GoBuilder.build()
}

/**
 * API-branch coverage for [LocalOnlyHotspotAdapter].
 *
 * On API 30+ the reservation exposes `getSoftApConfiguration()` (not
 * `getWifiConfiguration()`) and the runtime permission is still
 * `ACCESS_FINE_LOCATION`; from API 33 it becomes `NEARBY_WIFI_DEVICES`.
 * The project ships targetSdk 29, so these branches are defensive, but they are
 * still locked by tests so a future SDK bump cannot silently regress them.
 */
class LocalOnlyHotspotAdapterApiBranchTest {
    @RunWith(RobolectricTestRunner::class)
    @Config(sdk = [30])
    class OnApi30 {
        private val GoApp: Application = ApplicationProvider.getApplicationContext()
        private val GoWifi: WifiManager =
            GoApp.getSystemService(Context.WIFI_SERVICE) as WifiManager

        private fun GoBuildSoftApReservation(
            ssid: String,
            passphrase: String,
        ): WifiManager.LocalOnlyHotspotReservation {
            val GoConfig = GoBuildSoftApConfig(ssid, passphrase)
            val GoCtor =
                WifiManager.LocalOnlyHotspotReservation::class.java
                    .getDeclaredConstructor(WifiManager::class.java, SoftApConfiguration::class.java)
            GoCtor.isAccessible = true
            return GoCtor.newInstance(GoWifi, GoConfig)
        }

        @Test
        fun `reads the ssid and passphrase from the soft ap configuration`() {
            // Given API 30 with permission and location on
            Shadows.shadowOf(GoApp).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            val GoLoc = GoApp.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            Shadows.shadowOf(GoLoc).setLocationEnabled(true)
            val GoHotspot =
                LocalOnlyHotspotAdapter(
                    GoContext = GoApp,
                    GoLauncher =
                        HotspotLauncher {
                            HotspotLaunch.Granted(GoBuildSoftApReservation("Api30-AP", "api30-pass"))
                        },
                    GoTetherProbe = TetherProbe { emptyList() },
                )

            // When started
            val GoInfo = GoHotspot.GoStartHotspot()

            // Then the SoftApConfiguration branch supplied the credentials
            assertThat(GoInfo.GoSsid).isEqualTo("Api30-AP")
            assertThat(GoInfo.GoPassphrase).isEqualTo("api30-pass")
            assertThat(GoHotspot.GoState).isEqualTo(HotspotState.ACTIVE)
        }
    }

    @RunWith(RobolectricTestRunner::class)
    @Config(sdk = [33])
    class OnApi33 {
        private val GoApp: Application = ApplicationProvider.getApplicationContext()

        @Test
        fun `requires NEARBY_WIFI_DEVICES and fails into ERROR without it`() {
            // Given API 33 with neither permission granted
            val GoLoc = GoApp.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            Shadows.shadowOf(GoLoc).setLocationEnabled(true)
            val GoHotspot =
                LocalOnlyHotspotAdapter(
                    GoContext = GoApp,
                    GoLauncher = HotspotLauncher { HotspotLaunch.TimedOut },
                    GoTetherProbe = TetherProbe { emptyList() },
                )

            // When started
            assertThrows(HotspotUnavailableException::class.java) { GoHotspot.GoStartHotspot() }

            // Then the exact ERROR state is reached even though FINE_LOCATION was never the gate
            assertThat(GoHotspot.GoState).isEqualTo(HotspotState.ERROR)
            assertThat(GoHotspot.GoIsActive()).isFalse()
        }

        @Test
        fun `starts once NEARBY_WIFI_DEVICES is granted`() {
            // Given API 33 with the modern permission granted and location on
            Shadows.shadowOf(GoApp).grantPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
            val GoLoc = GoApp.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            Shadows.shadowOf(GoLoc).setLocationEnabled(true)
            val GoWifi = GoApp.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val GoConfig = GoBuildSoftApConfig("Api33-AP", "api33-pass")
            val GoCtor =
                WifiManager.LocalOnlyHotspotReservation::class.java
                    .getDeclaredConstructor(WifiManager::class.java, SoftApConfiguration::class.java)
            GoCtor.isAccessible = true
            val GoReservation = GoCtor.newInstance(GoWifi, GoConfig)
            val GoHotspot =
                LocalOnlyHotspotAdapter(
                    GoContext = GoApp,
                    GoLauncher = HotspotLauncher { HotspotLaunch.Granted(GoReservation) },
                    GoTetherProbe = TetherProbe { emptyList() },
                )

            // When started
            val GoInfo = GoHotspot.GoStartHotspot()

            // Then the NEARBY_WIFI_DEVICES branch is the one that granted access
            assertThat(GoInfo.GoSsid).isEqualTo("Api33-AP")
            assertThat(GoHotspot.GoState).isEqualTo(HotspotState.ACTIVE)
        }
    }
}
