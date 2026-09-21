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
private fun buildSoftApConfig(
    ssid: String,
    passphrase: String,
): SoftApConfiguration {
    val builder = SoftApConfiguration.Builder()
    val builderClass = SoftApConfiguration.Builder::class.java
    builderClass.getMethod("setSsid", String::class.java).invoke(builder, ssid)
    builderClass
        .getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
        .invoke(builder, passphrase, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
    return builder.build()
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
        private val app: Application = ApplicationProvider.getApplicationContext()
        private val wifi: WifiManager =
            app.getSystemService(Context.WIFI_SERVICE) as WifiManager

        private fun buildSoftApReservation(
            ssid: String,
            passphrase: String,
        ): WifiManager.LocalOnlyHotspotReservation {
            val config = buildSoftApConfig(ssid, passphrase)
            val ctor =
                WifiManager.LocalOnlyHotspotReservation::class.java
                    .getDeclaredConstructor(WifiManager::class.java, SoftApConfiguration::class.java)
            ctor.isAccessible = true
            return ctor.newInstance(wifi, config)
        }

        @Test
        fun `reads the ssid and passphrase from the soft ap configuration`() {
            // Given API 30 with permission and location on
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            val loc = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            Shadows.shadowOf(loc).setLocationEnabled(true)
            val hotspot =
                LocalOnlyHotspotAdapter(
                    context = app,
                    launcher =
                        HotspotLauncher {
                            HotspotLaunch.Granted(buildSoftApReservation("Api30-AP", "api30-pass"))
                        },
                    tetherProbe = TetherProbe { emptyList() },
                )

            // When started
            val info = hotspot.startHotspot()

            // Then the SoftApConfiguration branch supplied the credentials
            assertThat(info.ssid).isEqualTo("Api30-AP")
            assertThat(info.passphrase).isEqualTo("api30-pass")
            assertThat(hotspot.state).isEqualTo(HotspotState.ACTIVE)
        }
    }

    @RunWith(RobolectricTestRunner::class)
    @Config(sdk = [33])
    class OnApi33 {
        private val app: Application = ApplicationProvider.getApplicationContext()

        @Test
        fun `requires NEARBY_WIFI_DEVICES and fails into ERROR without it`() {
            // Given API 33 with neither permission granted
            val loc = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            Shadows.shadowOf(loc).setLocationEnabled(true)
            val hotspot =
                LocalOnlyHotspotAdapter(
                    context = app,
                    launcher = HotspotLauncher { HotspotLaunch.TimedOut },
                    tetherProbe = TetherProbe { emptyList() },
                )

            // When started
            assertThrows(HotspotUnavailableException::class.java) { hotspot.startHotspot() }

            // Then the exact ERROR state is reached even though FINE_LOCATION was never the gate
            assertThat(hotspot.state).isEqualTo(HotspotState.ERROR)
            assertThat(hotspot.isActive()).isFalse()
        }

        @Test
        fun `starts once NEARBY_WIFI_DEVICES is granted`() {
            // Given API 33 with the modern permission granted and location on
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
            val loc = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            Shadows.shadowOf(loc).setLocationEnabled(true)
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val config = buildSoftApConfig("Api33-AP", "api33-pass")
            val ctor =
                WifiManager.LocalOnlyHotspotReservation::class.java
                    .getDeclaredConstructor(WifiManager::class.java, SoftApConfiguration::class.java)
            ctor.isAccessible = true
            val reservation = ctor.newInstance(wifi, config)
            val hotspot =
                LocalOnlyHotspotAdapter(
                    context = app,
                    launcher = HotspotLauncher { HotspotLaunch.Granted(reservation) },
                    tetherProbe = TetherProbe { emptyList() },
                )

            // When started
            val info = hotspot.startHotspot()

            // Then the NEARBY_WIFI_DEVICES branch is the one that granted access
            assertThat(info.ssid).isEqualTo("Api33-AP")
            assertThat(hotspot.state).isEqualTo(HotspotState.ACTIVE)
        }
    }
}
