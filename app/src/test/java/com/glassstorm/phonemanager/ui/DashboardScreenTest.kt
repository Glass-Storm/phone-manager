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
import com.glassstorm.phonemanager.core.model.HotspotInfo
import com.glassstorm.phonemanager.hub.HubStarter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
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
 * There is no programmatic hotspot and no credential display any more: the
 * dashboard keeps the hub start/stop controls and reports the detected gateway
 * only. Under compile-time DI a port cannot be ABSENT, so the "not available"
 * rendering is driven by a port that FAILS instead.
 */
@RunWith(RobolectricTestRunner::class)
class DashboardScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders the unavailable state when the hub and hotspot ports fail`() {
        composeRule.setDashboardContent(FakeHubServer(failOnRead = true), FakeHotspotController(failOnDetect = true))

        composeRule.onNodeWithText("系統總覽").assertIsDisplayed()
        // Both the hub card and the hotspot card degrade to the same calm status.
        composeRule.onAllNodesWithText("無法使用").assertCountEquals(2)
    }

    @Test
    fun `the pre-read state is a quiet checking placeholder, not an error`() {
        composeRule.setDashboardContent(refreshOnInit = false)

        // A cold start must not flash the red failure state before the first read.
        composeRule.onAllNodesWithText("檢查中").assertCountEquals(2)
        composeRule.onNodeWithText("無法使用").assertDoesNotExist()
    }

    @Test
    fun `a stopped hub does not display a zero port`() {
        composeRule.setDashboardContent(
            hub = FakeHubServer(boundPortValue = 40404),
            hotspot = FakeHotspotController(failOnDetect = true),
        )

        composeRule.onNodeWithText("已停止").assertIsDisplayed()
        composeRule.onNodeWithText("0").assertDoesNotExist()
        composeRule.onNodeWithText("未啟動").assertIsDisplayed()
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
        val viewModel = DashboardViewModel.forTesting(hub, FakeHotspotController(), FakeHubStarter(), io = Dispatchers.Unconfined)
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
    fun `a detected manual tether shows only its gateway address`() {
        val manual =
            HotspotInfo(
                ssid = "EcoSys-Phone",
                passphrase = "hunter2-phone",
                gatewayIp = "192.168.43.1",
            )

        composeRule.setDashboardContent(
            hub = FakeHubServer(),
            hotspot = FakeHotspotController(manualTetherInfo = manual),
        )

        composeRule.onNodeWithText("閘道位址").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("192.168.43.1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("熱點已開啟").performScrollTo().assertIsDisplayed()
        // The hotspot credentials belong to the system settings, not this screen.
        composeRule.onNodeWithText("EcoSys-Phone").assertDoesNotExist()
        composeRule.onNodeWithText("hunter2-phone").assertDoesNotExist()
        // An access point is not a connected peer.
        composeRule.onNodeWithText("已連線").assertDoesNotExist()
    }

    @Test
    fun `an absent hotspot shows actionable copy and never claims a connection`() {
        composeRule.setDashboardContent(
            hub = FakeHubServer(),
            hotspot = FakeHotspotController(manualTetherInfo = null),
        )

        composeRule.onNodeWithText("熱點未開啟").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText("請到 Android 設定開啟行動熱點，再回到此頁。")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("已連線").assertDoesNotExist()
        composeRule.onNodeWithText("未偵測到手機熱點。").assertDoesNotExist()
    }

    private fun ComposeContentTestRule.setDashboardContent(
        hub: HubServer = FakeHubServer(),
        hotspot: HotspotController = FakeHotspotController(),
        hubStarter: HubStarter = FakeHubStarter { hub.start(0) },
        refreshOnInit: Boolean = true,
    ) {
        val viewModel =
            DashboardViewModel.forTesting(
                hub,
                hotspot,
                hubStarter,
                io = Dispatchers.Unconfined,
                refreshOnInit = refreshOnInit,
            )
        setContent {
            HubTheme {
                DashboardScreen(viewModelFactory = viewModelFactory(viewModel))
            }
        }
        waitForIdle()
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
