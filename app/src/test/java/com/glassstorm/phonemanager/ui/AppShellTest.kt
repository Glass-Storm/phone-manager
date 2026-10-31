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
import kotlinx.coroutines.Dispatchers
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
        composeRule.onNodeWithText("系統總覽").assertIsDisplayed()
    }

    @Test
    fun `stream route renders its screen`() {
        composeRule.setShellContent(startRoute = "stream")
        composeRule.onNodeWithText("即時轉送").assertIsDisplayed()
    }

    @Test
    fun `settings route renders its screen`() {
        composeRule.setShellContent(startRoute = "settings")
        composeRule.onNodeWithText("系統設定").assertIsDisplayed()
    }

    @Test
    fun `shell declares exactly the three required routes`() {
        composeRule.setShellContent(startRoute = "dashboard")
        assertThat(ROUTES)
            .containsExactly("dashboard", "stream", "settings")
            .inOrder()
    }

    @Test
    fun `the bottom bar labels every route`() {
        composeRule.setShellContent(startRoute = "dashboard")
        composeRule.onNodeWithText("總覽").assertIsDisplayed()
        composeRule.onNodeWithText("即時").assertIsDisplayed()
        composeRule.onNodeWithText("設定").assertIsDisplayed()
    }

    @Test
    fun `repeatedly tapping a tab never stacks duplicate destinations`() {
        lateinit var navController: NavHostController
        composeRule.setShellContent(startRoute = "dashboard") { navController = it }

        repeat(3) {
            composeRule.onNodeWithText("即時").performClick()
            composeRule.waitForIdle()
        }

        assertThat(navController.currentBackStack.value.mapNotNull { it.destination.route })
            .containsExactly("dashboard", "stream")
            .inOrder()
    }

    private fun ComposeContentTestRule.setShellContent(
        startRoute: String,
        onController: ((NavHostController) -> Unit)? = null,
    ) {
        val dashboardHub = FakeHubServer()
        val factory: ViewModelProvider.Factory =
            viewModelFactory(
                DashboardViewModel.forTesting(
                    dashboardHub,
                    FakeHotspotController(),
                    FakeHubStarter { dashboardHub.start(0) },
                    io = Dispatchers.Unconfined,
                ),
                SettingsViewModel(FakeHubServer(), FakeBatteryExemption()),
                StreamViewModel.forTesting(FakeStreamService(), FakeHubServer(initiallyRunning = true)),
            )
        setContent {
            val navController = rememberNavController()
            onController?.invoke(navController)
            HubTheme {
                AppShell(viewModelFactory = factory, startRoute = startRoute, navController = navController)
            }
        }
    }
}
