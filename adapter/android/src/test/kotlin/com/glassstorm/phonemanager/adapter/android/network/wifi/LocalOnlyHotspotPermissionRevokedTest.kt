package com.glassstorm.phonemanager.adapter.android.network.wifi

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Handler
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.core.domain.network.HotspotFailure
import com.glassstorm.phonemanager.core.domain.network.HotspotState
import com.glassstorm.phonemanager.core.domain.network.HotspotUnavailableException
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiManager

/**
 * A [ShadowWifiManager] whose `startLocalOnlyHotspot` throws [SecurityException],
 * standing in for the revocable-grant race: the permission passed the adapter's
 * pre-flight check and was withdrawn before the platform call.
 */
@Implements(WifiManager::class)
class SecurityExceptionShadowWifiManager : ShadowWifiManager() {
    @Implementation
    protected fun startLocalOnlyHotspot(
        callback: WifiManager.LocalOnlyHotspotCallback,
        handler: Handler?,
    ): Unit = throw SecurityException("permission revoked between check and call")
}

/**
 * The `SecurityException` leg of the hotspot permission defence.
 *
 * The adapter's pre-flight `hasRequiredPermission()` check cannot be atomic with
 * the platform call, so [PlatformHotspotLauncher] catches the platform's
 * [SecurityException] and reports a typed [HotspotLaunch.Denied]. These tests prove
 * that lands the machine in `ERROR` — never `ACTIVE` — and does not crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [SecurityExceptionShadowWifiManager::class])
class LocalOnlyHotspotPermissionRevokedTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun grantFineLocation() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun setLocationEnabled(enabled: Boolean) {
        val loc = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(loc).setLocationEnabled(enabled)
    }

    @Test
    fun `the launcher maps a thrown SecurityException to a typed denial`() {
        // Given a platform that throws SecurityException from the hotspot call
        val launcher = PlatformHotspotLauncher(context = app)

        // When the launcher is driven directly
        val launch = launcher.launch()

        // Then it is a typed denial with the dedicated permission reason, not a crash
        assertThat(launch).isInstanceOf(HotspotLaunch.Denied::class.java)
        assertThat((launch as HotspotLaunch.Denied).reasonCode)
            .isEqualTo(LocalOnlyHotspotAdapter.REASON_PERMISSION_DENIED)
    }

    @Test
    fun `a permission revoked between check and call lands in ERROR and never ACTIVE`() {
        // Given the pre-flight checks pass but the grant is gone by the platform call
        grantFineLocation()
        setLocationEnabled(true)
        val hotspot =
            LocalOnlyHotspotAdapter(
                context = app,
                tetherProbe = TetherProbe { emptyList() },
            )

        // When the hotspot start races the revocation
        val thrown =
            assertThrows(HotspotUnavailableException::class.java) {
                hotspot.startHotspot()
            }

        // Then the cause is the typed PermissionDenied and the state is exactly ERROR
        assertThat(thrown.failure).isEqualTo(HotspotFailure.PermissionDenied)
        assertThat(hotspot.state).isEqualTo(HotspotState.ERROR)
        assertThat(hotspot.state).isNotEqualTo(HotspotState.ACTIVE)
        assertThat(hotspot.isActive()).isFalse()
        assertThat(hotspot.hasLiveReservation()).isFalse()
    }

    @Test
    fun `a permission denial never leaves a live reservation behind`() {
        // Given a revoked-grant start failure
        grantFineLocation()
        setLocationEnabled(true)
        val hotspot =
            LocalOnlyHotspotAdapter(
                context = app,
                tetherProbe = TetherProbe { emptyList() },
            )
        assertThrows(HotspotUnavailableException::class.java) { hotspot.startHotspot() }

        // When the controller is stopped afterwards
        hotspot.stopHotspot()

        // Then teardown is clean and the state returns to IDLE
        assertThat(hotspot.hasLiveReservation()).isFalse()
        assertThat(hotspot.state).isEqualTo(HotspotState.IDLE)
    }
}
