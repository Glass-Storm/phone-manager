package com.glassstorm.phonemanager.hub

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.HubForegroundService
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric test for the REAL foreground-service seam.
 *
 * The Dashboard is tested against a [HubStarter] fake, so without this test nothing
 * proves the production [AndroidHubStarter] actually drives
 * [HubForegroundService]. Robolectric records the service intents a context starts
 * and stops, so the delegation is asserted directly by component.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HubStarterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app: Application = context as Application

    private val serviceIntent = Intent(context, HubForegroundService::class.java)

    @Test
    fun `start requests the foreground service to start`() {
        // When the production seam is asked to start the hub
        AndroidHubStarter(context).start()

        // Then it targets the hub's foreground service component
        val started = shadowOf(app).nextStartedService
        assertThat(started).isNotNull()
        assertThat(started!!.component).isEqualTo(serviceIntent.component)
    }

    @Test
    fun `stop requests the same service to stop`() {
        // Given the seam started the service first
        val starter = AndroidHubStarter(context)
        starter.start()
        assertThat(shadowOf(app).nextStartedService).isNotNull()

        // When the seam is asked to stop the hub
        starter.stop()

        // Then it stops the SAME service component, so onDestroy runs tearDown
        val stopped = shadowOf(app).nextStoppedService
        assertThat(stopped).isNotNull()
        assertThat(stopped!!.component).isEqualTo(serviceIntent.component)
    }
}
