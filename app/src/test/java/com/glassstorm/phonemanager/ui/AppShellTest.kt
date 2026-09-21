package com.glassstorm.phonemanager.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves the Compose app shell renders on the JVM (no emulator exists on this host).
 *
 * The shell is rendered through the generated Lumo AppTheme, so a green run also
 * proves the generated theme and component sources compile and compose together.
 *
 * Expected labels are LITERALS, never the shell's own constants: asserting against a
 * shared constant is tautological and passes even when the UI renders the wrong text.
 *
 * One route per test: `createComposeRule()` permits a single `setContent` per test.
 */
@RunWith(RobolectricTestRunner::class)
class AppShellTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `dashboard is the start destination`() {
        composeRule.setShellContent(startRoute = "dashboard")
        composeRule.onNodeWithText("Dashboard").assertIsDisplayed()
    }

    @Test
    fun `pairing route renders its placeholder`() {
        composeRule.setShellContent(startRoute = "pairing")
        composeRule.onNodeWithText("Pairing").assertIsDisplayed()
    }

    @Test
    fun `stream route renders its placeholder`() {
        composeRule.setShellContent(startRoute = "stream")
        composeRule.onNodeWithText("Stream").assertIsDisplayed()
    }

    @Test
    fun `devices route renders its placeholder`() {
        composeRule.setShellContent(startRoute = "devices")
        composeRule.onNodeWithText("Devices").assertIsDisplayed()
    }

    @Test
    fun `settings route renders its placeholder`() {
        composeRule.setShellContent(startRoute = "settings")
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun `shell declares exactly the five required routes`() {
        composeRule.setShellContent(startRoute = "dashboard")
        assertThat(ROUTES)
            .containsExactly("dashboard", "pairing", "stream", "devices", "settings")
            .inOrder()
    }

    private fun ComposeContentTestRule.setShellContent(startRoute: String) {
        setContent {
            AppTheme {
                AppShell(startRoute = startRoute)
            }
        }
    }
}
