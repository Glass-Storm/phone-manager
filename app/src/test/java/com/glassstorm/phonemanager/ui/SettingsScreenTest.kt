package com.glassstorm.phonemanager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.glassstorm.phonemanager.battery.BatteryExemption
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings screen behaviour on the JVM, asserted on text selectors (no screenshots).
 *
 * Expected strings are LITERALS, never the screen's own constants. The screen talks
 * only to the domain `HubServer` / `BatteryExemption` ports, so these tests drive
 * fakes and never name an `:adapter` class.
 *
 * There is no hotspot mode selector and no debug section any more: there is no
 * programmatic hotspot, and the live relay diagnostics moved to the 即時 page. The
 * screen exposes only the Doze exemption and the endpoint.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the exemption button reflects the current state and requests when pressed`() {
        val battery = FakeBatteryExemption(exempt = false)

        composeRule.setSettingsContent(battery = battery)
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
    fun `a stopped hub does not display a port`() {
        composeRule.setSettingsContent(hub = FakeHubServer(boundPortValue = 40404))

        composeRule.onNodeWithText("連接埠").assertDoesNotExist()
        composeRule.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun `returning to settings reflects a newly granted exemption`() {
        val battery = FakeBatteryExemption(exempt = false)
        val owner = ResumedLifecycleOwner()

        composeRule.setSettingsContent(battery = battery, lifecycleOwner = owner)
        composeRule.assertText("未排除")

        // The exemption is granted while the screen is in the external settings app.
        battery.setExempt(true)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        composeRule.waitForIdle()

        composeRule.assertText("已排除")
    }

    @Test
    fun `an empty enrollment directory invites the first voice`() {
        composeRule.setSettingsContent()

        composeRule.assertText("聲紋登記")
        composeRule.assertText("尚未登記任何聲紋。")
    }

    @Test
    fun `a recorded speaker is listed in the directory`() {
        val enrollment = FakeEnrollmentStore()

        composeRule.setSettingsContent(enrollment = enrollment) { viewModel ->
            viewModel.onEnrollNameChange("Alice")
            viewModel.onRecordAndEnroll()
        }

        composeRule.assertText("已登記聲紋")
        composeRule.assertText("Alice")
    }

    @Test
    fun `a rejected enrollment explains the reason in plain language`() {
        composeRule.setSettingsContent(capture = FakeVoiceCapture(pcm16 = null)) { viewModel ->
            viewModel.onEnrollNameChange("Alice")
            viewModel.onRecordAndEnroll()
        }

        composeRule.assertText("無法登記")
        composeRule.assertText("無法使用麥克風，請確認已授予錄音權限。")
    }

    @Test
    fun `an unprovisioned device reports the engine instead of a misleading form`() {
        composeRule.setSettingsContent(enrollment = FakeEnrollmentStore(deviceBackedValue = false))

        composeRule.assertText("無法使用")
        composeRule.assertText("聲紋引擎未啟用：請先安裝語音模型，否則登記的聲紋不會保存。")
        composeRule.onNodeWithText("錄音並登記").assertDoesNotExist()
    }

    @Test
    fun `a speaker can be removed from the directory`() {
        composeRule.setSettingsContent { viewModel ->
            viewModel.onEnrollNameChange("Alice")
            viewModel.onRecordAndEnroll()
        }

        composeRule.assertText("Alice")
        composeRule.click("移除")

        composeRule.onNodeWithText("Alice").assertDoesNotExist()
        composeRule.assertText("尚未登記任何聲紋。")
    }

    private class ResumedLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)

        init {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        override val lifecycle: Lifecycle get() = registry
    }

    private fun ComposeContentTestRule.setSettingsContent(
        hub: HubServer = FakeHubServer(),
        battery: BatteryExemption = FakeBatteryExemption(),
        enrollment: FakeEnrollmentStore = FakeEnrollmentStore(),
        capture: FakeVoiceCapture = FakeVoiceCapture(),
        lifecycleOwner: LifecycleOwner? = null,
        before: (SettingsViewModel) -> Unit = {},
    ) {
        val viewModel =
            SettingsViewModel.forTesting(
                hub = hub,
                battery = battery,
                enrollment = enrollment,
                voiceCapture = capture,
                io = Dispatchers.Unconfined,
            )
        before(viewModel)
        setContent {
            val owner = lifecycleOwner ?: LocalLifecycleOwner.current
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HubTheme {
                    SettingsScreen(viewModelFactory = viewModelFactory(viewModel))
                }
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
