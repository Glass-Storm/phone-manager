package com.glassstorm.phonemanager.battery

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Doze-exemption helper: the boolean state and the exact intent shape the
 * platform activity requires.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BatteryOptimizationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun shadowPower(): Pair<PowerManager, org.robolectric.shadows.ShadowPowerManager> {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power to shadowOf(power)
    }

    @Test
    fun `a non-exempt app reports not exempt`() {
        // Given the app is not on the exemption list
        val (_, shadowPower) = shadowPower()
        shadowPower.setIgnoringBatteryOptimizations(context.packageName, false)

        // When exemption is asked for
        // Then it is false
        assertThat(BatteryOptimization.isExempt(context)).isFalse()
    }

    @Test
    fun `an exempt app reports exempt`() {
        // Given the platform lists this package as exempt
        val (_, shadowPower) = shadowPower()
        shadowPower.setIgnoringBatteryOptimizations(context.packageName, true)

        // When exemption is asked for
        // Then it is true
        assertThat(BatteryOptimization.isExempt(context)).isTrue()
    }

    @Test
    fun `the request intent carries the platform action`() {
        // Given a built exemption intent
        val intent = BatteryOptimization.requestIntent(context)

        // When the action is inspected
        // Then it is exactly the platform's ignore-battery-optimizations action
        assertThat(intent.action)
            .isEqualTo(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    }

    @Test
    fun `the request intent targets this package through a package uri`() {
        // Given a built exemption intent
        val intent = BatteryOptimization.requestIntent(context)

        // When its data URI is inspected
        // Then the scheme is package and the opaque part is this applicationId, so
        // the system activity knows which app the request is about
        assertThat(intent.data?.scheme).isEqualTo("package")
        assertThat(intent.data?.schemeSpecificPart).isEqualTo(context.packageName)
        assertThat(intent.data.toString()).isEqualTo("package:${context.packageName}")
    }
}
