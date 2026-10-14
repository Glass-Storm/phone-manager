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
    fun `shell declares exactly the four required routes`() {
        composeRule.setShellContent(startRoute = "dashboard")
        assertThat(ROUTES)
            .containsExactly("dashboard", "stream", "devices", "settings")
            .inOrder()
    }

    @Test
    fun `repeatedly tapping a tab never stacks duplicate destinations`() {
        lateinit var navController: NavHostController
        composeRule.setShellContent(startRoute = "dashboard") { navController = it }

        repeat(3) {
            composeRule.onNodeWithText("DEVICES").performClick()
            composeRule.waitForIdle()
        }

        assertThat(navController.currentBackStack.value.mapNotNull { it.destination.route })
            .containsExactly("dashboard", "devices")
            .inOrder()
    }

    private fun ComposeContentTestRule.setShellContent(
        startRoute: String,
        onController: ((NavHostController) -> Unit)? = null,
    ) {
        val dashboardHub = FakeHubServer()
        val factory: ViewModelProvider.Factory =
            viewModelFactory(
                DashboardViewModel(
                    dashboardHub,
                    FakeHotspotController(),
                    FakeHubStarter { dashboardHub.start(0) },
                ),
                DevicesViewModel(FakeDeviceRepository()),
                SettingsViewModel(FakeAppConfig(), FakeHubServer(), FakeBatteryExemption()),
                StreamViewModel(FakeStreamService()),
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
