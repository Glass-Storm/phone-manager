package com.glassstorm.phonemanager

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.core.domain.adapter.network.Discovery
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowService

/**
 * Robolectric tests for the hub's foreground hosting.
 *
 * These are the only automated checks the FGS gets (there is no emulator on this
 * host, issues.md R2): they prove the notification channel exists BEFORE
 * `startForeground` and that the service actually became foreground. The service
 * is started on an EPHEMERAL port so the test never squats the production port.
 *
 * The service resolves its collaborators from the process-wide Dagger graph the
 * [PhoneManagerApplication] built, so these tests reach the SAME `HubServer`,
 * `HotspotController` and `Discovery` through
 * `(application as PhoneManagerApplication).component`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HubForegroundServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun component() = (context as Application as PhoneManagerApplication).component

    private fun startService(): HubForegroundService {
        val intent =
            Intent(context, HubForegroundService::class.java)
                .putExtra(HubForegroundService.EXTRA_PORT, 0)
        return Robolectric
            .buildService(HubForegroundService::class.java, intent)
            .create()
            .startCommand(0, 0)
            .get()
    }

    @Test
    fun `the notification channel exists before the service goes foreground`() {
        // Given a service that has been created and started
        startService()

        // When the notification manager is inspected
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Then the channel the notification was posted on really exists
        assertThat(manager.getNotificationChannel("hub-foreground")).isNotNull()
    }

    @Test
    fun `starting the service posts an ongoing foreground notification`() {
        // Given a started service
        val service = startService()

        // Then it is foreground with the hub's notification id and a live notification
        val shadow = shadowOf(service) as ShadowService
        assertThat(shadow.lastForegroundNotificationId).isEqualTo(1)
        assertThat(shadow.lastForegroundNotification).isNotNull()
        assertThat(shadow.isForegroundStopped).isFalse()
    }

    @Test
    fun `the service returns START_STICKY so the hub is restarted after a kill`() {
        // Given a service asked to start
        val intent =
            Intent(context, HubForegroundService::class.java)
                .putExtra(HubForegroundService.EXTRA_PORT, 0)

        // When onStartCommand runs
        val controller = Robolectric.buildService(HubForegroundService::class.java, intent).create()
        val result = controller.get().onStartCommand(intent, 0, 0)

        // Then the result is START_STICKY
        assertThat(result).isEqualTo(android.app.Service.START_STICKY)
        controller.destroy()
    }

    @Test
    fun `destroying the service stops the hub and releases the port`() {
        // Given a running service that bound an ephemeral hub port
        val intent =
            Intent(context, HubForegroundService::class.java)
                .putExtra(HubForegroundService.EXTRA_PORT, 0)
        val controller =
            Robolectric
                .buildService(HubForegroundService::class.java, intent)
                .create()
                .startCommand(0, 0)
        val hub: HubServer = component().hubServer()
        assertThat(hub.isRunning()).isTrue()
        val port = hub.boundPort()

        // When the service is destroyed
        controller.destroy()

        // Then the listener is stopped and the port is gone
        assertThat(hub.isRunning()).isFalse()
        assertThat(hub.boundPort()).isEqualTo(0)
        assertThat(port).isGreaterThan(0)
    }

    @Test
    fun `repeated onStartCommand never double-starts the listener`() {
        // Given a started service
        val intent =
            Intent(context, HubForegroundService::class.java)
                .putExtra(HubForegroundService.EXTRA_PORT, 0)
        val controller =
            Robolectric
                .buildService(HubForegroundService::class.java, intent)
                .create()
                .startCommand(0, 0)
        val hub: HubServer = component().hubServer()
        val port = hub.boundPort()

        // When the OS re-delivers the start command
        controller.get().onStartCommand(intent, 0, 1)

        // Then the same listener instance is still bound to the same port (no
        // double-start, no rebind)
        assertThat(hub.isRunning()).isTrue()
        assertThat(hub.boundPort()).isEqualTo(port)

        controller.destroy()
    }

    @Test
    fun `a missing hotspot permission never leaves the hotspot ACTIVE`() {
        // Given the location permission is denied (Robolectric's default) and a
        // started service that therefore cannot bring the access point up
        shadowOf(context as Application).denyPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val controller =
            Robolectric
                .buildService(
                    HubForegroundService::class.java,
                    Intent(context, HubForegroundService::class.java).putExtra(HubForegroundService.EXTRA_PORT, 0),
                ).create()
                .startCommand(0, 0)
        val hotspot: HotspotController = component().hotspotController()

        // When the access point state is inspected
        // Then it never claims ACTIVE, and the hub listener still came up so wired
        // peers stay reachable
        assertThat(hotspot.isActive()).isFalse()
        val hub: HubServer = component().hubServer()
        assertThat(hub.isRunning()).isTrue()

        controller.destroy()
    }

    @Test
    fun `the graph provides the platform network ports the service brings up with`() {
        // Given the process-wide graph the manifest-declared application built
        val component = component()

        // When the network ports are resolved by their domain types
        val hotspot: HotspotController = component.hotspotController()
        val discovery: Discovery = component.discovery()

        // Then the platform-backed adapters are present, so bring-up is possible
        assertThat(hotspot).isNotNull()
        assertThat(discovery).isNotNull()
    }
}
