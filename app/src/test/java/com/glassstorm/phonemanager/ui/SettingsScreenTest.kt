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
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.context.Context
import com.glassstorm.phonemanager.core.domain.context.register
import com.glassstorm.phonemanager.core.model.HotspotMode
import com.glassstorm.phonemanager.core.model.SttEngine
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
        composeRule.setSettingsContent(Context())

        composeRule.assertText("Settings")
        composeRule.assertText("Configuration not available")
    }

    @Test
    fun `the configured engine and region render as the current selection`() {
        val config =
            FakeAppConfig(
                engine = SttEngine.SPEECHMATICS,
                regionValue = "eu",
            )
        val ctx = Context().also { register<AppConfig>(it, config) }

        composeRule.setSettingsContent(ctx)

        composeRule.assertText("Speech engine")
        composeRule.assertText("Current engine: Speechmatics")
        composeRule.assertText("Current region: eu")
    }

    @Test
    fun `selecting the cloud engine persists it through the port`() {
        val config = FakeAppConfig(engine = SttEngine.MOCK)
        val ctx = Context().also { register<AppConfig>(it, config) }

        composeRule.setSettingsContent(ctx)
        composeRule.assertText("Current engine: Mock (offline)")

        composeRule.click("Use Speechmatics")

        assertThat(config.sttEngine()).isEqualTo(SttEngine.SPEECHMATICS)
        composeRule.assertText("Current engine: Speechmatics")
    }

    @Test
    fun `selecting a region persists it through the port`() {
        val config = FakeAppConfig(regionValue = "us")
        val ctx = Context().also { register<AppConfig>(it, config) }

        composeRule.setSettingsContent(ctx)
        composeRule.assertText("Current region: us")

        composeRule.click("Region au")

        assertThat(config.region()).isEqualTo("au")
        composeRule.assertText("Current region: au")
    }

    @Test
    fun `selecting the automatic hotspot mode persists it through the port`() {
        val config = FakeAppConfig(mode = HotspotMode.MANUAL)
        val ctx = Context().also { register<AppConfig>(it, config) }

        composeRule.setSettingsContent(ctx)
        composeRule.assertText("Hotspot mode: Manual")

        composeRule.click("Hotspot mode Auto")

        assertThat(config.hotspotMode()).isEqualTo(HotspotMode.AUTO)
        composeRule.assertText("Hotspot mode: Auto")
    }

    @Test
    fun `a stored api key is never rendered in cleartext by default`() {
        // Given a store holding a recognizable key
        val config = FakeAppConfig(storedKey = "sk-SECRET-SENTINEL-1234")
        val ctx = Context().also { register<AppConfig>(it, config) }

        composeRule.setSettingsContent(ctx)

        // When the whole semantics tree is searched for the raw key
        // Then it is absent, and a masked password field is present instead
        assertThat(composeRule.maskedPasswordFieldCount()).isAtLeast(1)
        assertThat(composeRule.cleartextKeyNodeCount("sk-SECRET-SENTINEL-1234")).isEqualTo(0)
        composeRule.assertText("API key configured: yes")
    }

    @Test
    fun `a fresh install with no key renders the not-configured state and stays masked`() {
        val config = FakeAppConfig(storedKey = "")
        val ctx = Context().also { register<AppConfig>(it, config) }

        composeRule.setSettingsContent(ctx)

        composeRule.assertText("API key configured: no")
        // The input is still a masked password field even when empty, so the shape
        // never changes between configured and unconfigured states.
        assertThat(composeRule.maskedPasswordFieldCount()).isAtLeast(1)
        assertThat(composeRule.cleartextKeyNodeCount("sk-SECRET-SENTINEL-1234")).isEqualTo(0)
    }

    @Test
    fun `an empty api key submission never crashes and keeps the unconfigured state`() {
        val config = FakeAppConfig(storedKey = "")
        val ctx = Context().also { register<AppConfig>(it, config) }

        composeRule.setSettingsContent(ctx)
        composeRule.click("Set API key")

        assertThat(config.apiKey()).isEmpty()
        composeRule.assertText("API key configured: no")
    }

    @Test
    fun `a newly typed api key is persisted and reported as configured`() {
        val config = FakeAppConfig(storedKey = "")
        val ctx = Context().also { register<AppConfig>(it, config) }

        composeRule.setSettingsContent(ctx)
        composeRule.typeIntoField("sk-typed-777")
        composeRule.click("Set API key")

        assertThat(config.apiKey()).isEqualTo("sk-typed-777")
        composeRule.assertText("API key configured: yes")
        // The newly stored key is still not on screen in cleartext.
        assertThat(composeRule.cleartextKeyNodeCount("sk-typed-777")).isEqualTo(0)
    }

    @Test
    fun `revealing the api key takes an explicit action`() {
        val config = FakeAppConfig(storedKey = "sk-SECRET-SENTINEL-1234")
        val ctx = Context().also { register<AppConfig>(it, config) }

        composeRule.setSettingsContent(ctx)
        composeRule.assertText("Show API key")

        composeRule.click("Show API key")

        // Only after the explicit toggle may the cleartext be rendered.
        assertThat(composeRule.cleartextKeyNodeCount("sk-SECRET-SENTINEL-1234")).isAtLeast(1)
        composeRule.assertText("Hide API key")
    }

    @Test
    fun `the exemption button reflects the current state and requests when pressed`() {
        val config = FakeAppConfig()
        val battery = FakeBatteryExemption(exempt = false)
        val ctx =
            Context().also {
                register<AppConfig>(it, config)
                register<BatteryExemption>(it, battery)
            }

        composeRule.setSettingsContent(ctx)
        composeRule.assertText("Battery optimization: not exempt")

        composeRule.click("Request battery exemption")

        assertThat(battery.requestCalls).isEqualTo(1)
    }

    @Test
    fun `an already exempt app renders the exempt state`() {
        val ctx =
            Context().also {
                register<AppConfig>(it, FakeAppConfig())
                register<BatteryExemption>(it, FakeBatteryExemption(exempt = true))
            }

        composeRule.setSettingsContent(ctx)

        composeRule.assertText("Battery optimization: exempt")
    }

    @Test
    fun `the protocol and the bound hub port are displayed`() {
        val ctx =
            Context().also {
                register<AppConfig>(it, FakeAppConfig())
                register<HubServer>(it, FakeHubServer(boundPortValue = 40404).also { it.start(0) })
            }

        composeRule.setSettingsContent(ctx)

        composeRule.assertText("Protocol: ecosys.v1")
        composeRule.assertText("Hub port: 40404")
    }

    @Test
    fun `a stopped hub renders a zero port without crashing`() {
        val ctx =
            Context().also {
                register<AppConfig>(it, FakeAppConfig())
                register<HubServer>(it, FakeHubServer(boundPortValue = 40404))
            }

        composeRule.setSettingsContent(ctx)

        composeRule.assertText("Hub port: 0")
    }

    private fun ComposeContentTestRule.setSettingsContent(ctx: Context) {
        setContent {
            AppTheme {
                SettingsScreen(context = ctx)
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

    private fun ComposeContentTestRule.typeIntoField(text: String) {
        onNode(
            SemanticsMatcher("is the api key input") { node ->
                node.config.getOrNull(SemanticsProperties.Password) != null
            },
        ).performScrollTo().performTextInput(text)
        waitForIdle()
    }

    /** Nodes that are text INPUTS marked as password fields (the masked shape). */
    private fun ComposeContentTestRule.maskedPasswordFieldCount(): Int =
        onAllNodes(
            SemanticsMatcher("is a password input") { node ->
                node.config.getOrNull(SemanticsProperties.Password) != null
            },
        ).fetchSemanticsNodes().size

    /** Nodes whose rendered text CONTAINS [key]: 0 means the key is not on screen. */
    private fun ComposeContentTestRule.cleartextKeyNodeCount(key: String): Int =
        onAllNodes(
            SemanticsMatcher("renders the api key in cleartext") { node ->
                val text = node.config.getOrNull(SemanticsProperties.Text)
                val editable = node.config.getOrNull(SemanticsProperties.EditableText)
                text?.any { it.text.contains(key) } == true ||
                    editable?.text?.contains(key) == true
            },
        ).fetchSemanticsNodes().size
}
