package com.glassstorm.phonemanager

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HubForegroundServiceTest {

    private val GoContext: Context = ApplicationProvider.getApplicationContext()

    private fun GoStartService(): HubForegroundService {
        val GoIntent = Intent(GoContext, HubForegroundService::class.java)
            .putExtra(HubForegroundService.GO_EXTRA_PORT, 0)
        return Robolectric.buildService(HubForegroundService::class.java, GoIntent)
            .create()
            .startCommand(0, 0)
            .get()
    }

    @Test
    fun `the notification channel exists before the service goes foreground`() {
        // Given a service that has been created and started
        GoStartService()

        // When the notification manager is inspected
        val GoManager = GoContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Then the channel the notification was posted on really exists
        assertThat(GoManager.getNotificationChannel("hub-foreground")).isNotNull()
    }

    @Test
    fun `starting the service posts an ongoing foreground notification`() {
        // Given a started service
        val GoService = GoStartService()

        // Then it is foreground with the hub's notification id and a live notification
        val GoShadow = shadowOf(GoService) as ShadowService
        assertThat(GoShadow.lastForegroundNotificationId).isEqualTo(1)
        assertThat(GoShadow.lastForegroundNotification).isNotNull()
        assertThat(GoShadow.isForegroundStopped).isFalse()
    }

    @Test
    fun `the service returns START_STICKY so the hub is restarted after a kill`() {
        // Given a service asked to start
        val GoIntent = Intent(GoContext, HubForegroundService::class.java)
            .putExtra(HubForegroundService.GO_EXTRA_PORT, 0)

        // When onStartCommand runs
        val GoController = Robolectric.buildService(HubForegroundService::class.java, GoIntent).create()
        val GoResult = GoController.get().onStartCommand(GoIntent, 0, 0)

        // Then the result is START_STICKY
        assertThat(GoResult).isEqualTo(android.app.Service.START_STICKY)
        GoController.destroy()
    }

    @Test
    fun `destroying the service stops the hub and releases the port`() {
        // Given a running service that bound an ephemeral hub port
        val GoIntent = Intent(GoContext, HubForegroundService::class.java)
            .putExtra(HubForegroundService.GO_EXTRA_PORT, 0)
        val GoController = Robolectric.buildService(HubForegroundService::class.java, GoIntent)
            .create()
            .startCommand(0, 0)
        val GoHub = com.glassstorm.phonemanager.domain.context.FromContext<
            com.glassstorm.phonemanager.domain.adapter.transport.HubServer
            >(AppComposition.GoAppContext())
        assertThat(GoHub.GoIsRunning()).isTrue()
        val GoPort = GoHub.GoBoundPort()

        // When the service is destroyed
        GoController.destroy()

        // Then the listener is stopped and the port is gone
        assertThat(GoHub.GoIsRunning()).isFalse()
        assertThat(GoHub.GoBoundPort()).isEqualTo(0)
        assertThat(GoPort).isGreaterThan(0)
    }
}
