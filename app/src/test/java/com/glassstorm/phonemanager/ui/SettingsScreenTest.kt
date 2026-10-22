package com.glassstorm.phonemanager.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.glassstorm.phonemanager.battery.BatteryExemption
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.model.HotspotMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings screen behaviour on the JVM, asserted on text selectors (no screenshots).
 *
 * Expected strings are LITERALS, never the screen's own constants. The screen talks
 * only to the domain `AppConfig` / `HubServer` / `BatteryExemption` ports, so these
 * tests drive fakes and never name an `:adapter` class.
 *
 * There is no speech-engine selector and no API-key field any more: the hub runs a
 * single on-device engine with no cloud path, so the screen exposes only the hotspot
 * mode, the Doze exemption and the endpoint.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders the unavailable state when the config port read fails`() {
        composeRule.setSettingsContent(config = FakeAppConfig(failReads = true))

        composeRule.assertText("系統設定")
        composeRule.assertText("無法使用")
    }

    @Test
    fun `the hotspot card renders the current mode`() {
        composeRule.setSettingsContent(config = FakeAppConfig(mode = HotspotMode.MANUAL))

        composeRule.assertText("手動模式")
    }

    @Test
    fun `selecting the automatic hotspot mode persists it through the port`() {
        val config = FakeAppConfig(mode = HotspotMode.MANUAL)

        composeRule.setSettingsContent(config = config)
        composeRule.assertText("手動模式")

        composeRule.click("自動")

        assertThat(config.hotspotMode()).isEqualTo(HotspotMode.AUTO)
        composeRule.assertText("自動模式")
    }

    @Test
    fun `the exemption button reflects the current state and requests when pressed`() {
        val config = FakeAppConfig()
        val battery = FakeBatteryExemption(exempt = false)

        composeRule.setSettingsContent(config = config, battery = battery)
        composeRule.assertText("未排除")

        composeRule.click("要求排除電池最佳化")

        assertThat(battery.requestCalls).isEqualTo(1)
    }

    @Test
    fun `an already exempt app renders the exempt state`() {
        composeRule.setSettingsContent(battery = FakeBatteryExemption(exempt = true))

        composeRule.assertText("已排除")
    }

    @Test
    fun `the protocol and the bound hub port are displayed`() {
        composeRule.setSettingsContent(
            hub = FakeHubServer(boundPortValue = 40404).also { it.start(0) },
        )

        composeRule.assertText("ecosys.v1")
        composeRule.assertText("40404")
    }

    @Test
    fun `a stopped hub renders a zero port without crashing`() {
        composeRule.setSettingsContent(hub = FakeHubServer(boundPortValue = 40404))

        composeRule.assertText("0")
    }

    private fun ComposeContentTestRule.setSettingsContent(
        config: AppConfig = FakeAppConfig(),
        hub: HubServer = FakeHubServer(),
        battery: BatteryExemption = FakeBatteryExemption(),
    ) {
        val viewModel = SettingsViewModel(config, hub, battery)
        setContent {
            HubTheme {
                SettingsScreen(viewModelFactory = viewModelFactory(viewModel))
            }
        }
    }

    private fun ComposeContentTestRule.assertText(text: String) {
        onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun ComposeContentTestRule.click(text: String) {
        onNodeWithText(text).performScrollTo().performClick()
        waitForIdle()
    }
}
