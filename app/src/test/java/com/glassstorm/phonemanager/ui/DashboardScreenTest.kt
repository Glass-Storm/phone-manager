package com.glassstorm.phonemanager.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.network.HotspotFailure
import com.glassstorm.phonemanager.hub.HubStarter
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

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

        composeRule.onNodeWithText("系統總覽").assertIsDisplayed()
        // Both the hub card and the connection card degrade to the same calm status.
        composeRule.onAllNodesWithText("無法使用").assertCountEquals(2)
    }

    @Test
    fun `starting the hub shows the bound port`() {
        composeRule.setDashboardContent(
            hub = FakeHubServer(boundPortValue = 40404),
            hotspot = FakeHotspotController(failOnDetect = true),
        )
        composeRule.onNodeWithText("已停止").assertIsDisplayed()

        composeRule.clickButton("啟動中樞")

        composeRule.onNodeWithText("運作中").assertIsDisplayed()
        composeRule.onNodeWithText("40404").assertIsDisplayed()
        // The failing hotspot port degrades, it does not crash the shell.
        composeRule.onAllNodesWithText("無法使用").assertCountEquals(1)
    }

    @Test
    fun `starting the hub invokes the foreground-service seam exactly once`() {
        val hub = FakeHubServer()
        val starter = FakeHubStarter()

        composeRule.setDashboardContent(hub = hub, hubStarter = starter)
        composeRule.clickButton("啟動中樞")

        assertThat(starter.startCalls).isEqualTo(1)
        // The ViewModel must cross the seam rather than start the listener itself:
        // bring-up belongs to the foreground service, not the UI process.
        assertThat(hub.startCalls).isEqualTo(0)
    }

    @Test
    fun `stopping the hub crosses the same foreground-service seam`() {
        val hub = FakeHubServer()
        val starter = FakeHubStarter(onStart = { hub.start(0) }, onStop = { hub.stop() })

        composeRule.setDashboardContent(hub = hub, hubStarter = starter)
        composeRule.clickButton("啟動中樞")
        composeRule.onNodeWithText("運作中").assertIsDisplayed()

        composeRule.clickButton("停止中樞")

        // Teardown must be delegated to the service too: a direct hub.stop() would
        // leave the service's bring-up state stale and discovery advertising.
        assertThat(starter.stopCalls).isEqualTo(1)
        composeRule.onNodeWithText("已停止").assertIsDisplayed()
    }

    @Test
    fun `a failed start is surfaced instead of silently swallowed`() {
        val starter = FakeHubStarter(startFailure = IllegalStateException("background start denied"))

        composeRule.setDashboardContent(hub = FakeHubServer(), hubStarter = starter)
        composeRule.clickButton("啟動中樞")

        assertThat(starter.startCalls).isEqualTo(1)
        composeRule.onNodeWithText("Hub failed to start: background start denied").assertIsDisplayed()
    }

    @Test
    fun `the dashboard republishes the running state once the service binds the hub`() {
        val hub = FakeHubServer(boundPortValue = 40404)
        // The service does NOT bind synchronously; the poll is what must notice it.
        composeRule.setDashboardContent(hub = hub, hubStarter = FakeHubStarter())

        composeRule.clickButton("啟動中樞")
        composeRule.onNodeWithText("已停止").assertIsDisplayed()

        // The foreground service binds the listener on a LATER main-loop turn.
        hub.start(0)
        composeRule.advancePollClock()

        composeRule.onNodeWithText("運作中").assertIsDisplayed()
        composeRule.onNodeWithText("40404").assertIsDisplayed()
    }

    @Test
    fun `clearing the dashboard mid-poll leaves no live coroutine behind`() {
        val hub = FakeHubServer()
        val store = ViewModelStore()
        val viewModel = DashboardViewModel(hub, FakeHotspotController(), FakeHubStarter())
        store.put("dashboard", viewModel)
        assertThat(viewModel.uiState.value.running).isFalse()

        // The service never binds, so the poll stays pending until clear cancels it.
        viewModel.onStartHub()
        store.clear()

        // A cleared ViewModel must not keep polling: a later hub bind cannot reach it.
        hub.start(0)
        composeRule.advancePollClock()
        assertThat(viewModel.uiState.value.running).isFalse()
    }

    @Test
    fun `starting the hotspot reveals the credentials only on an explicit action`() {
        composeRule.setDashboardContent(hub = FakeHubServer(), hotspot = FakeHotspotController())

        composeRule.clickButton("啟動熱點")

        composeRule.onNodeWithText("EcoSys-Phone").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("192.168.43.1").performScrollTo().assertIsDisplayed()
        // The passphrase is masked by default: the raw value is nowhere on screen.
        composeRule.onNodeWithText("hunter2-phone").assertDoesNotExist()

        composeRule.clickButton("顯示密碼")

        composeRule.onNodeWithText("hunter2-phone").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a failing hotspot start renders the typed reason instead of crashing`() {
        composeRule.setDashboardContent(
            hub = FakeHubServer(),
            hotspot = FakeHotspotController(failWith = HotspotFailure.StartFailed(reason = "denied")),
        )

        composeRule.clickButton("啟動熱點")

        composeRule.onNodeWithText("Hotspot failed: denied").assertIsDisplayed()
    }

    private fun ComposeContentTestRule.setDashboardContent(
        hub: HubServer = FakeHubServer(),
        hotspot: HotspotController = FakeHotspotController(),
        hubStarter: HubStarter = FakeHubStarter { hub.start(0) },
    ) {
        val viewModel = DashboardViewModel(hub, hotspot, hubStarter)
        setContent {
            HubTheme {
                DashboardScreen(viewModelFactory = viewModelFactory(viewModel))
            }
        }
    }

    private fun ComposeContentTestRule.clickButton(text: String) {
        onNodeWithText(text).performScrollTo().performClick()
        waitForIdle()
    }

    private fun ComposeContentTestRule.advancePollClock() {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000L))
        waitForIdle()
    }
}
