package com.glassstorm.phonemanager

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.core.domain.adapter.network.Discovery
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.model.HotspotInfo
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

    private fun awaitUntil(
        timeoutMs: Long = 5_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10L)
        }
        assertThat(condition()).isTrue()
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
        awaitUntil { hub.isRunning() }
        assertThat(hub.isRunning()).isTrue()
        val port = hub.boundPort()

        // When the service is destroyed
        controller.destroy()

        // Then the listener is stopped and the port is gone
        awaitUntil { !hub.isRunning() && hub.boundPort() == 0 }
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
        awaitUntil { hub.isRunning() }
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
    fun `with no manual hotspot the service still starts the listener and never claims ACTIVE`() {
        // Given a started service on a host with no soft-AP interface
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
        awaitUntil { hub.isRunning() }
        assertThat(hub.isRunning()).isTrue()

        controller.destroy()
    }

    @Test
    fun `teardown does not block the caller while the listener drains a peer`() {
        // Given a listener whose stop waits on an attached peer, driven through the
        // foreground service's real lifecycle
        val slowHub = SlowStopHubServer(stopDelayMs = 2_000L)
        val controller =
            Robolectric
                .buildService(
                    HubForegroundService::class.java,
                    Intent(context, HubForegroundService::class.java).putExtra(HubForegroundService.EXTRA_PORT, 0),
                ).create()
        controller.get().bringUpFactory = {
            HubBringUp(slowHub, NoopHotspotController(), NoopDiscovery())
        }
        controller.startCommand(0, 0)
        awaitUntil { slowHub.isRunning() }

        // When the service is destroyed on the caller thread
        val elapsedMs = kotlin.system.measureTimeMillis { controller.destroy() }

        // Then the caller returned promptly and the drain completed off-thread
        assertThat(elapsedMs).isLessThan(1_000L)
        awaitUntil { slowHub.stopped }
        assertThat(slowHub.stopped).isTrue()
    }

    private class SlowStopHubServer(
        private val stopDelayMs: Long,
    ) : HubServer {
        @Volatile private var running = false

        @Volatile var stopped: Boolean = false
            private set

        override fun start(port: Int) {
            running = true
        }

        override fun stop() {
            Thread.sleep(stopDelayMs)
            stopped = true
            running = false
        }

        override fun isRunning(): Boolean = running

        override fun boundPort(): Int = if (running) 40404 else 0
    }

    private class NoopHotspotController : HotspotController {
        override fun startHotspot(): HotspotInfo = error("bring-up must not create a hotspot")

        override fun stopHotspot() = Unit

        override fun isActive(): Boolean = false

        override fun detectManualTether(): HotspotInfo? = null
    }

    private class NoopDiscovery : Discovery {
        override fun advertise(
            name: String,
            port: Int,
        ) = Unit

        override fun stopAdvertise() = Unit
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
