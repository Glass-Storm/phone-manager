package com.glassstorm.phonemanager.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.network.HotspotFailure
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.hub.HubStarter
import com.google.common.truth.Truth.assertThat
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
 * Under compile-time DI a port cannot be ABSENT, so the "not available" rendering
 * is driven by a port that FAILS instead. The doubles implement the DOMAIN ports
 * only; `:app`'s test sources never touch a concrete `:adapter` class.
 */
@RunWith(RobolectricTestRunner::class)
class DashboardScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders the unavailable state when the hub and hotspot ports fail`() {
        composeRule.setDashboardContent(FakeHubServer(failOnRead = true), FakeHotspotController(failOnDetect = true))

        composeRule.onNodeWithText("Dashboard").assertIsDisplayed()
        composeRule.onNodeWithText("Hub server not available").assertIsDisplayed()
        composeRule.onNodeWithText("Hotspot not available").assertIsDisplayed()
    }

    @Test
    fun `starting the hub shows the bound port and the paired device count`() {
        val pairing =
            FakePairingService().also {
                it.seedDevice(deviceId = "d-1", deviceName = "Glass One")
                it.seedDevice(deviceId = "d-2", deviceName = "Ubuntu Daemon", role = "DAEMON")
            }

        composeRule.setDashboardContent(
            hub = FakeHubServer(boundPortValue = 40404),
            hotspot = FakeHotspotController(failOnDetect = true),
            pairing = pairing,
        )
        composeRule.onNodeWithText("Hub: stopped").assertIsDisplayed()

        composeRule.onNodeWithText("Start hub").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Hub: running").assertIsDisplayed()
        composeRule.onNodeWithText("Port: 40404").assertIsDisplayed()
        composeRule.onNodeWithText("Paired devices: 2").assertIsDisplayed()
        // The failing hotspot port degrades, it does not crash the shell.
        composeRule.onNodeWithText("Hotspot not available").assertIsDisplayed()
    }

    @Test
    fun `starting the hub invokes the foreground-service seam exactly once`() {
        val hub = FakeHubServer()
        val starter = FakeHubStarter()

        composeRule.setDashboardContent(hub = hub, hubStarter = starter)
        composeRule.onNodeWithText("Start hub").performClick()
        composeRule.waitForIdle()

        assertThat(starter.startCalls).isEqualTo(1)
        // The ViewModel must cross the seam rather than start the listener itself:
        // bring-up belongs to the foreground service, not the UI process.
        assertThat(hub.startCalls).isEqualTo(0)
    }

    @Test
    fun `starting the hotspot shows the credentials and the gateway ip`() {
        composeRule.setDashboardContent(hub = FakeHubServer(), hotspot = FakeHotspotController())

        composeRule.onNodeWithText("Start hotspot").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("SSID: EcoSys-Phone").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Passphrase: hunter2-phone").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Gateway: 192.168.43.1").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a failing hotspot start renders the typed reason instead of crashing`() {
        composeRule.setDashboardContent(
            hub = FakeHubServer(),
            hotspot = FakeHotspotController(failWith = HotspotFailure.StartFailed(reason = "denied")),
        )

        composeRule.onNodeWithText("Start hotspot").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Hotspot failed: denied").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setDashboardContent(
        hub: HubServer = FakeHubServer(),
        hotspot: HotspotController = FakeHotspotController(),
        pairing: PairingService = FakePairingService(),
        hubStarter: HubStarter = FakeHubStarter { hub.start(0) },
    ) {
        val viewModel = DashboardViewModel(hub, hotspot, pairing, hubStarter)
        setContent {
            AppTheme {
                DashboardScreen(viewModelFactory = viewModelFactory(viewModel))
            }
        }
    }
}
