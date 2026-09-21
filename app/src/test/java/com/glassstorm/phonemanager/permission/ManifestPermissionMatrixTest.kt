package com.glassstorm.phonemanager.permission

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.HubForegroundService
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parses the PACKAGED manifest (via Robolectric's package info, i.e. the merged
 * manifest) to prove the permission matrix and the service declaration are real —
 * not merely present as XML comments.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ManifestPermissionMatrixTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun requestedPermissions(): List<String> =
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            ?: emptyList()

    @Test
    fun `the packaged manifest declares every hub permission`() {
        // Given the merged manifest
        val requested = requestedPermissions()

        // Then every permission the hub's code paths rely on is declared
        assertThat(requested).containsAtLeast(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @Test
    fun `the hub service stays unexported and connected-device typed`() {
        // Given the merged manifest's service entry
        val service =
            context.packageManager.getServiceInfo(
                ComponentName(context, HubForegroundService::class.java),
                0,
            )

        // Then it is not exported and declares the connectedDevice foreground type,
        // so only this app may start its hub and the OS accepts the type
        assertThat(service.exported).isFalse()
        assertThat(service.foregroundServiceType)
            .isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }
}
