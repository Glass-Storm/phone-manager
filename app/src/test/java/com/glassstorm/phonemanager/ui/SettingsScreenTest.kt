package com.glassstorm.phonemanager.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.glassstorm.phonemanager.battery.BatteryExemption
import com.glassstorm.phonemanager.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.dto.HotspotMode
import com.glassstorm.phonemanager.domain.dto.SttEngine
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
 * The masking case is the security headline: a stored API key must NOT appear in
 * cleartext in the semantics tree by default. It is asserted with a MATCHER (not a
 * text lookup) so the check bites whether the field is masked or not — a masked
 * field reports `[Password]` and dots, while an unmasked one reports the raw key.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders the unavailable state when the config port is absent`() {
        composeRule.GoSetSettingsContent(Context())

        composeRule.GoAssertText("Settings")
        composeRule.GoAssertText("Configuration not available")
    }

    @Test
    fun `the configured engine and region render as the current selection`() {
        val GoConfig =
            FakeAppConfig(
                GoEngine = SttEngine.SPEECHMATICS,
                GoRegionValue = "eu",
            )
        val GoCtx = Context().also { Register<AppConfig>(it, GoConfig) }

        composeRule.GoSetSettingsContent(GoCtx)

        composeRule.GoAssertText("Speech engine")
        composeRule.GoAssertText("Current engine: Speechmatics")
        composeRule.GoAssertText("Current region: eu")
    }

    @Test
    fun `selecting the cloud engine persists it through the port`() {
        val GoConfig = FakeAppConfig(GoEngine = SttEngine.MOCK)
        val GoCtx = Context().also { Register<AppConfig>(it, GoConfig) }

        composeRule.GoSetSettingsContent(GoCtx)
        composeRule.GoAssertText("Current engine: Mock (offline)")

        composeRule.GoClick("Use Speechmatics")

        assertThat(GoConfig.GoSttEngine()).isEqualTo(SttEngine.SPEECHMATICS)
        composeRule.GoAssertText("Current engine: Speechmatics")
    }

    @Test
    fun `selecting a region persists it through the port`() {
        val GoConfig = FakeAppConfig(GoRegionValue = "us")
        val GoCtx = Context().also { Register<AppConfig>(it, GoConfig) }

        composeRule.GoSetSettingsContent(GoCtx)
        composeRule.GoAssertText("Current region: us")

        composeRule.GoClick("Region au")

        assertThat(GoConfig.GoRegion()).isEqualTo("au")
        composeRule.GoAssertText("Current region: au")
    }

    @Test
    fun `selecting the automatic hotspot mode persists it through the port`() {
        val GoConfig = FakeAppConfig(GoMode = HotspotMode.MANUAL)
        val GoCtx = Context().also { Register<AppConfig>(it, GoConfig) }

        composeRule.GoSetSettingsContent(GoCtx)
        composeRule.GoAssertText("Hotspot mode: Manual")

        composeRule.GoClick("Hotspot mode Auto")

        assertThat(GoConfig.GoHotspotMode()).isEqualTo(HotspotMode.AUTO)
        composeRule.GoAssertText("Hotspot mode: Auto")
    }

    @Test
    fun `a stored api key is never rendered in cleartext by default`() {
        // Given a store holding a recognizable key
        val GoConfig = FakeAppConfig(GoStoredKey = "sk-SECRET-SENTINEL-1234")
        val GoCtx = Context().also { Register<AppConfig>(it, GoConfig) }

        composeRule.GoSetSettingsContent(GoCtx)

        // When the whole semantics tree is searched for the raw key
        // Then it is absent, and a masked password field is present instead
        assertThat(composeRule.GoMaskedPasswordFieldCount()).isAtLeast(1)
        assertThat(composeRule.GoCleartextKeyNodeCount("sk-SECRET-SENTINEL-1234")).isEqualTo(0)
        composeRule.GoAssertText("API key configured: yes")
    }

    @Test
    fun `a fresh install with no key renders the not-configured state and stays masked`() {
        val GoConfig = FakeAppConfig(GoStoredKey = "")
        val GoCtx = Context().also { Register<AppConfig>(it, GoConfig) }

        composeRule.GoSetSettingsContent(GoCtx)

        composeRule.GoAssertText("API key configured: no")
        // The input is still a masked password field even when empty, so the shape
        // never changes between configured and unconfigured states.
        assertThat(composeRule.GoMaskedPasswordFieldCount()).isAtLeast(1)
        assertThat(composeRule.GoCleartextKeyNodeCount("sk-SECRET-SENTINEL-1234")).isEqualTo(0)
    }

    @Test
    fun `an empty api key submission never crashes and keeps the unconfigured state`() {
        val GoConfig = FakeAppConfig(GoStoredKey = "")
        val GoCtx = Context().also { Register<AppConfig>(it, GoConfig) }

        composeRule.GoSetSettingsContent(GoCtx)
        composeRule.GoClick("Set API key")

        assertThat(GoConfig.GoApiKey()).isEmpty()
        composeRule.GoAssertText("API key configured: no")
    }

    @Test
    fun `a newly typed api key is persisted and reported as configured`() {
        val GoConfig = FakeAppConfig(GoStoredKey = "")
        val GoCtx = Context().also { Register<AppConfig>(it, GoConfig) }

        composeRule.GoSetSettingsContent(GoCtx)
        composeRule.GoTypeIntoField("sk-typed-777")
        composeRule.GoClick("Set API key")

        assertThat(GoConfig.GoApiKey()).isEqualTo("sk-typed-777")
        composeRule.GoAssertText("API key configured: yes")
        // The newly stored key is still not on screen in cleartext.
        assertThat(composeRule.GoCleartextKeyNodeCount("sk-typed-777")).isEqualTo(0)
    }

    @Test
    fun `revealing the api key takes an explicit action`() {
        val GoConfig = FakeAppConfig(GoStoredKey = "sk-SECRET-SENTINEL-1234")
        val GoCtx = Context().also { Register<AppConfig>(it, GoConfig) }

        composeRule.GoSetSettingsContent(GoCtx)
        composeRule.GoAssertText("Show API key")

        composeRule.GoClick("Show API key")

        // Only after the explicit toggle may the cleartext be rendered.
        assertThat(composeRule.GoCleartextKeyNodeCount("sk-SECRET-SENTINEL-1234")).isAtLeast(1)
        composeRule.GoAssertText("Hide API key")
    }

    @Test
    fun `the exemption button reflects the current state and requests when pressed`() {
        val GoConfig = FakeAppConfig()
        val GoBattery = FakeBatteryExemption(GoExempt = false)
        val GoCtx =
            Context().also {
                Register<AppConfig>(it, GoConfig)
                Register<BatteryExemption>(it, GoBattery)
            }

        composeRule.GoSetSettingsContent(GoCtx)
        composeRule.GoAssertText("Battery optimization: not exempt")

        composeRule.GoClick("Request battery exemption")

        assertThat(GoBattery.GoRequestCalls).isEqualTo(1)
    }

    @Test
    fun `an already exempt app renders the exempt state`() {
        val GoCtx =
            Context().also {
                Register<AppConfig>(it, FakeAppConfig())
                Register<BatteryExemption>(it, FakeBatteryExemption(GoExempt = true))
            }

        composeRule.GoSetSettingsContent(GoCtx)

        composeRule.GoAssertText("Battery optimization: exempt")
    }

    @Test
    fun `the protocol and the bound hub port are displayed`() {
        val GoCtx =
            Context().also {
                Register<AppConfig>(it, FakeAppConfig())
                Register<HubServer>(it, FakeHubServer(GoBoundPortValue = 40404).also { it.GoStart(0) })
            }

        composeRule.GoSetSettingsContent(GoCtx)

        composeRule.GoAssertText("Protocol: ecosys.v1")
        composeRule.GoAssertText("Hub port: 40404")
    }

    @Test
    fun `a stopped hub renders a zero port without crashing`() {
        val GoCtx =
            Context().also {
                Register<AppConfig>(it, FakeAppConfig())
                Register<HubServer>(it, FakeHubServer(GoBoundPortValue = 40404))
            }

        composeRule.GoSetSettingsContent(GoCtx)

        composeRule.GoAssertText("Hub port: 0")
    }

    private fun ComposeContentTestRule.GoSetSettingsContent(GoCtx: Context) {
        setContent {
            AppTheme {
                SettingsScreen(GoContext = GoCtx)
            }
        }
    }

    private fun ComposeContentTestRule.GoAssertText(text: String) {
        onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun ComposeContentTestRule.GoClick(text: String) {
        onNodeWithText(text).performScrollTo().performClick()
        waitForIdle()
    }

    private fun ComposeContentTestRule.GoTypeIntoField(text: String) {
        onNode(
            SemanticsMatcher("is the api key input") { GoNode ->
                GoNode.config.getOrNull(SemanticsProperties.Password) != null
            },
        ).performScrollTo().performTextInput(text)
        waitForIdle()
    }

    /** Nodes that are text INPUTS marked as password fields (the masked shape). */
    private fun ComposeContentTestRule.GoMaskedPasswordFieldCount(): Int =
        onAllNodes(
            SemanticsMatcher("is a password input") { GoNode ->
                GoNode.config.getOrNull(SemanticsProperties.Password) != null
            },
        ).fetchSemanticsNodes().size

    /** Nodes whose rendered text CONTAINS [key]: 0 means the key is not on screen. */
    private fun ComposeContentTestRule.GoCleartextKeyNodeCount(key: String): Int =
        onAllNodes(
            SemanticsMatcher("renders the api key in cleartext") { GoNode ->
                val GoText = GoNode.config.getOrNull(SemanticsProperties.Text)
                val GoEditable = GoNode.config.getOrNull(SemanticsProperties.EditableText)
                GoText?.any { it.text.contains(key) } == true ||
                    GoEditable?.text?.contains(key) == true
            },
        ).fetchSemanticsNodes().size
}
