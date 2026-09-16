package com.glassstorm.phonemanager.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.glassstorm.phonemanager.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.network.HotspotFailure
import com.glassstorm.phonemanager.domain.service.PairingService
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Dashboard screen behaviour on the JVM (no emulator exists on this host).
 *
 * Expected strings are LITERALS, never the screen's own constants: asserting a
 * shared constant is tautological and passes even when the UI renders the wrong
 * text. One `setContent` per test method — `createComposeRule()` allows no more.
 *
 * The doubles implement the DOMAIN ports only; `:app`'s test sources never touch
 * a concrete `:adapter` class, which is what proves the screen stays behind the
 * interface.
 */
@RunWith(RobolectricTestRunner::class)
class DashboardScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders the unavailable state when no ports are registered`() {
        composeRule.GoSetDashboardContent(Context())

        composeRule.onNodeWithText("Dashboard").assertIsDisplayed()
        composeRule.onNodeWithText("Hub server not available").assertIsDisplayed()
        composeRule.onNodeWithText("Hotspot not available").assertIsDisplayed()
    }

    @Test
    fun `starting the hub shows the bound port and the paired device count`() {
        val GoPairing =
            FakePairingService().also {
                it.GoSeedDevice(deviceId = "d-1", deviceName = "Glass One")
                it.GoSeedDevice(deviceId = "d-2", deviceName = "Ubuntu Daemon", role = "DAEMON")
            }
        val GoCtx =
            Context().also {
                Register<HubServer>(it, FakeHubServer(GoBoundPortValue = 40404))
                Register<PairingService>(it, GoPairing)
            }

        composeRule.GoSetDashboardContent(GoCtx)
        composeRule.onNodeWithText("Hub: stopped").assertIsDisplayed()

        composeRule.onNodeWithText("Start hub").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Hub: running").assertIsDisplayed()
        composeRule.onNodeWithText("Port: 40404").assertIsDisplayed()
        composeRule.onNodeWithText("Paired devices: 2").assertIsDisplayed()
        // Only some ports are registered on this Context: the absent one degrades, not crashes.
        composeRule.onNodeWithText("Hotspot not available").assertIsDisplayed()
    }

    @Test
    fun `starting the hotspot shows the credentials and the gateway ip`() {
        val GoCtx =
            Context().also {
                Register<HotspotController>(it, FakeHotspotController())
            }

        composeRule.GoSetDashboardContent(GoCtx)
        composeRule.onNodeWithText("Start hotspot").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("SSID: EcoSys-Phone").assertIsDisplayed()
        composeRule.onNodeWithText("Passphrase: hunter2-phone").assertIsDisplayed()
        composeRule.onNodeWithText("Gateway: 192.168.43.1").assertIsDisplayed()
    }

    @Test
    fun `a failing hotspot start renders the typed reason instead of crashing`() {
        val GoCtx =
            Context().also {
                Register<HotspotController>(
                    it,
                    FakeHotspotController(GoFailWith = HotspotFailure.StartFailed(GoReason = "denied")),
                )
            }

        composeRule.GoSetDashboardContent(GoCtx)
        composeRule.onNodeWithText("Start hotspot").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Hotspot failed: denied").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.GoSetDashboardContent(GoCtx: Context) {
        setContent {
            AppTheme {
                DashboardScreen(GoContext = GoCtx)
            }
        }
    }
}
