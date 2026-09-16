package com.glassstorm.phonemanager.permission

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The permission matrix, asserted at the exact API levels the platform branches on.
 *
 * `@Config(sdk = [29])` is the app's real target: `ACCESS_FINE_LOCATION` is the
 * active LocalOnlyHotspot gate there. `@Config(sdk = [33])` proves the platform
 * swap to `NEARBY_WIFI_DEVICES` plus the notification grant, so the branch is real
 * rather than dormant defensive code.
 */
@RunWith(RobolectricTestRunner::class)
class HubPermissionsTest {

    @Test
    @Config(sdk = [29])
    fun `api 29 demands fine location and never the notification permission`() {
        // Given a device at the app's target API level
        // When the required set is asked for
        val GoRequired = HubPermissions.GoRequiredFor(29)

        // Then the hotspot gate is the location permission, and the notification is
        // not demanded (the platform grants it at install below API 33)
        assertThat(GoRequired).containsExactly(Manifest.permission.ACCESS_FINE_LOCATION)
        assertThat(GoRequired).doesNotContain(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    @Config(sdk = [33])
    fun `api 33 swaps to nearby-wifi-devices and adds the notification grant`() {
        // Given a device at API 33
        // When the required set is asked for
        val GoRequired = HubPermissions.GoRequiredFor(33)

        // Then the Wi-Fi gate is NEARBY_WIFI_DEVICES and notifications are required
        assertThat(GoRequired).containsExactly(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        assertThat(GoRequired).doesNotContain(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @Test
    @Config(sdk = [29])
    fun `missing lists every ungranted permission and empty means ready`() {
        // Given a context with the location permission denied
        val GoContext: Context = ApplicationProvider.getApplicationContext()
        shadowOf(GoContext as android.app.Application).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        // When the missing set is computed
        val GoMissing = HubPermissions.GoMissing(GoContext)

        // Then the location permission is named as the blocker
        assertThat(GoMissing).containsExactly(Manifest.permission.ACCESS_FINE_LOCATION)
        assertThat(HubPermissions.GoHasAll(GoContext)).isFalse()
    }

    @Test
    @Config(sdk = [29])
    fun `a granted location permission leaves nothing missing`() {
        // Given the location permission granted
        val GoContext: Context = ApplicationProvider.getApplicationContext()
        shadowOf(GoContext as android.app.Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        // When the matrix is consulted
        // Then nothing is missing and the gate reports ready
        assertThat(HubPermissions.GoMissing(GoContext)).isEmpty()
        assertThat(HubPermissions.GoHasAll(GoContext)).isTrue()
    }

    @Test
    @Config(sdk = [33])
    fun `api 33 reports the notification grant as a blocker`() {
        // Given API 33 with Wi-Fi granted but notifications denied
        val GoContext: Context = ApplicationProvider.getApplicationContext()
        shadowOf(GoContext as android.app.Application).grantPermissions(
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )

        // When the matrix is consulted
        // Then exactly the notification permission is missing
        assertThat(HubPermissions.GoMissing(GoContext))
            .containsExactly(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    @Config(sdk = [29])
    fun `the blocking hotspot permission names location on api 29`() {
        // Given the hotspot gate is denied
        val GoContext: Context = ApplicationProvider.getApplicationContext()
        shadowOf(GoContext as android.app.Application).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        // When the blocking permission is asked for
        // Then it names the location permission, not a boolean
        assertThat(HubPermissions.GoBlockingHotspotPermission(GoContext))
            .isEqualTo(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @Test
    @Config(sdk = [33])
    fun `the hotspot gate ignores a missing notification permission`() {
        // Given API 33 with Wi-Fi granted but notifications denied
        val GoContext: Context = ApplicationProvider.getApplicationContext()
        shadowOf(GoContext as android.app.Application).grantPermissions(
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )

        // When the hotspot gate is asked for
        // Then it is satisfied: the notification grant must not block the hotspot
        assertThat(HubPermissions.GoBlockingHotspotPermission(GoContext)).isNull()
    }
}
