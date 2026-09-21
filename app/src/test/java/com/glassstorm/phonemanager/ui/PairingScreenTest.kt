package com.glassstorm.phonemanager.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.register
import com.glassstorm.phonemanager.domain.service.PairingService
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pairing screen behaviour on the JVM, asserted on text selectors (no screenshots).
 *
 * Expected strings are LITERALS so a wrong render cannot pass. `createComposeRule()`
 * permits a single `setContent` per test, so there is one route per test method.
 */
@RunWith(RobolectricTestRunner::class)
class PairingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders the unavailable state when the pairing port is absent`() {
        composeRule.setPairingContent(Context())

        composeRule.onNodeWithText("Pairing").assertIsDisplayed()
        composeRule.onNodeWithText("Pairing service not available").assertIsDisplayed()
    }

    @Test
    fun `no pin is shown until the window is opened`() {
        val pairing = FakePairingService(pinSequence = listOf("428193"))
        val ctx = Context().also { register<PairingService>(it, pairing) }

        composeRule.setPairingContent(ctx)

        composeRule.onNodeWithText("No pairing window open").assertIsDisplayed()
        assertThat(pairing.openCalls).isEqualTo(0)
    }

    @Test
    fun `the pin renders after the window opens`() {
        val pairing = FakePairingService(pinSequence = listOf("428193"))
        val ctx = Context().also { register<PairingService>(it, pairing) }

        composeRule.setPairingContent(ctx)
        composeRule.onNodeWithText("Open pairing window").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("428193").assertIsDisplayed()
        composeRule.onNodeWithText("No pairing window open").assertDoesNotExist()
    }

    @Test
    fun `opening the window twice does not leave two live pins`() {
        // The fake replaces its window and returns a DIFFERENT pin the second time,
        // exactly like the real service. Two live PINs would therefore be visible.
        val pairing = FakePairingService(pinSequence = listOf("428193", "999999"))
        val ctx = Context().also { register<PairingService>(it, pairing) }

        composeRule.setPairingContent(ctx)
        composeRule.onNodeWithText("Open pairing window").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reopen pairing window").performClick()
        composeRule.waitForIdle()

        val pinNodes = composeRule.onAllNodesWithText("428193").fetchSemanticsNodes()
        assertThat(pinNodes).isEmpty()
        composeRule.onNodeWithText("999999").assertIsDisplayed()
        assertThat(pairing.openCalls).isEqualTo(2)
        assertThat(composeRule.countSixDigitPinNodes()).isEqualTo(1)
    }

    @Test
    fun `a revoked device disappears from the paired list`() {
        val pairing =
            FakePairingService().also {
                it.seedDevice(deviceId = "d-1", deviceName = "Glass One")
                it.seedDevice(deviceId = "d-2", deviceName = "Ubuntu Daemon", role = "DAEMON")
            }
        val ctx = Context().also { register<PairingService>(it, pairing) }

        composeRule.setPairingContent(ctx)
        composeRule.onNodeWithText("Glass One").assertIsDisplayed()
        composeRule.onNodeWithText("Ubuntu Daemon").assertIsDisplayed()

        composeRule.onNodeWithText("Revoke Glass One").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Glass One").assertDoesNotExist()
        composeRule.onNodeWithText("Ubuntu Daemon").assertIsDisplayed()
    }

    @Test
    fun `closing the window hides the pin again`() {
        val pairing = FakePairingService(pinSequence = listOf("428193"))
        val ctx = Context().also { register<PairingService>(it, pairing) }

        composeRule.setPairingContent(ctx)
        composeRule.onNodeWithText("Open pairing window").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("428193").assertIsDisplayed()

        composeRule.onNodeWithText("Close pairing window").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("428193").assertDoesNotExist()
        composeRule.onNodeWithText("No pairing window open").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setPairingContent(ctx: Context) {
        setContent {
            AppTheme {
                PairingScreen(context = ctx)
            }
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.countSixDigitPinNodes(): Int =
        onAllNodes(
            SemanticsMatcher("is a 6-digit PIN") { node ->
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    ?.any { it.text.matches(Regex("\\d{6}")) } == true
            },
        ).fetchSemanticsNodes().size
}
