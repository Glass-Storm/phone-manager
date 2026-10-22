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
        composeRule.onNodeWithText("系統總覽").assertIsDisplayed()
    }

    @Test
    fun `stream route renders its screen`() {
        composeRule.setShellContent(startRoute = "stream")
        composeRule.onNodeWithText("即時串流").assertIsDisplayed()
    }

    @Test
    fun `devices route renders its screen`() {
        composeRule.setShellContent(startRoute = "devices")
        composeRule.onNodeWithText("已連線裝置").assertIsDisplayed()
    }

    @Test
    fun `settings route renders its screen`() {
        composeRule.setShellContent(startRoute = "settings")
        composeRule.onNodeWithText("系統設定").assertIsDisplayed()
    }

    @Test
    fun `shell declares exactly the four required routes`() {
        composeRule.setShellContent(startRoute = "dashboard")
        assertThat(ROUTES)
            .containsExactly("dashboard", "stream", "devices", "settings")
            .inOrder()
    }

    @Test
    fun `the bottom bar labels every route`() {
        composeRule.setShellContent(startRoute = "dashboard")
        composeRule.onNodeWithText("總覽").assertIsDisplayed()
        composeRule.onNodeWithText("串流").assertIsDisplayed()
        composeRule.onNodeWithText("裝置").assertIsDisplayed()
        composeRule.onNodeWithText("設定").assertIsDisplayed()
    }

    @Test
    fun `repeatedly tapping a tab never stacks duplicate destinations`() {
        lateinit var navController: NavHostController
        composeRule.setShellContent(startRoute = "dashboard") { navController = it }

        repeat(3) {
            composeRule.onNodeWithText("裝置").performClick()
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
            HubTheme {
                AppShell(viewModelFactory = factory, startRoute = startRoute, navController = navController)
            }
        }
    }
}
