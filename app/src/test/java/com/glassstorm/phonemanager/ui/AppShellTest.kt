package com.glassstorm.phonemanager.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
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
 * Every screen resolves its ViewModel through the passed factory, so the test builds
 * the five ViewModels from domain-port fakes.
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
    fun `navigating from dashboard renders the pairing screen`() {
        composeRule.setShellContent(startRoute = "dashboard")
        composeRule.onNodeWithText("Dashboard").assertIsDisplayed()

        composeRule.onNodeWithText("PAIRING").performClick()

        composeRule.onNodeWithText("Pairing window").assertIsDisplayed()
    }

    @Test
    fun `shell declares exactly the five required routes`() {
        composeRule.setShellContent(startRoute = "dashboard")
        assertThat(ROUTES)
            .containsExactly("dashboard", "pairing", "stream", "devices", "settings")
            .inOrder()
    }

    @Test
    fun `repeatedly tapping a tab never stacks duplicate destinations`() {
        lateinit var navController: NavHostController
        composeRule.setShellContent(startRoute = "dashboard") { navController = it }

        repeat(3) {
            composeRule.onNodeWithText("PAIRING").performClick()
            composeRule.waitForIdle()
        }

        // A bare navigate() would have pushed pairing three times; the tab contract
        // keeps exactly the start destination plus the one live tab (the NavGraph
        // root has a null route, so only leaf routes are compared).
        assertThat(navController.currentBackStack.value.mapNotNull { it.destination.route })
            .containsExactly("dashboard", "pairing")
            .inOrder()
    }

    private fun ComposeContentTestRule.setShellContent(
        startRoute: String,
        onController: ((NavHostController) -> Unit)? = null,
    ) {
        val pairing = FakePairingService()
        val dashboardHub = FakeHubServer()
        val factory: ViewModelProvider.Factory =
            viewModelFactory(
                DashboardViewModel(
                    dashboardHub,
                    FakeHotspotController(),
                    pairing,
                    FakeHubStarter { dashboardHub.start(0) },
                ),
                DevicesViewModel(FakeDeviceRepository()),
                PairingViewModel(pairing),
                SettingsViewModel(FakeAppConfig(), FakeHubServer(), FakeBatteryExemption()),
                StreamViewModel(FakeStreamService(), pairing),
            )
        setContent {
            val navController = rememberNavController()
            onController?.invoke(navController)
            AppTheme {
                AppShell(viewModelFactory = factory, startRoute = startRoute, navController = navController)
            }
        }
    }
}
