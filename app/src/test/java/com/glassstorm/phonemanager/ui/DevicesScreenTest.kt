package com.glassstorm.phonemanager.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Devices screen behaviour on the JVM, asserted on text selectors (no screenshots).
 *
 * Expected strings are LITERALS, never the screen's own constants: asserting a
 * shared constant is tautological and passes even when the UI renders the wrong
 * text. `createComposeRule()` permits a single `setContent` per test.
 *
 * Under compile-time DI a port cannot be ABSENT, so the "unavailable" rendering is
 * driven by a repository whose read THROWS — the genuine failure path. The screen
 * still resolves its data through the `DeviceRepository` DOMAIN port only.
 */
@RunWith(RobolectricTestRunner::class)
class DevicesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders the unavailable state when the repository read fails`() {
        composeRule.setDevicesContent(FakeDeviceRepository(failOnList = true))

        composeRule.assertText("Devices")
        composeRule.assertText("Device store not available")
    }

    @Test
    fun `an empty store shows the no-devices line`() {
        composeRule.setDevicesContent(FakeDeviceRepository())

        composeRule.assertText("No paired devices")
    }

    @Test
    fun `a seeded device list renders names roles and last-seen`() {
        val repo =
            FakeDeviceRepository().also {
                it.seedDevice("d-1", "Glass One", role = "GLASS", lastSeenMs = 1_700_000_000_000L)
                it.seedDevice("d-2", "Ubuntu Daemon", role = "DAEMON")
            }

        composeRule.setDevicesContent(repo)

        composeRule.assertText("Glass One")
        composeRule.assertText("Role: GLASS")
        composeRule.assertText("Ubuntu Daemon")
        composeRule.assertText("Role: DAEMON")
        composeRule.assertText("Last seen: 2023-11-14T22:13:20Z")
    }

    @Test
    fun `a device never seen since pairing shows the never-seen line`() {
        val repo =
            FakeDeviceRepository().also {
                it.seedDevice("d-1", "Glass One", lastSeenMs = null)
            }

        composeRule.setDevicesContent(repo)

        composeRule.assertText("Last seen: never")
    }

    @Test
    fun `revoking a device removes its row from the list`() {
        val repo =
            FakeDeviceRepository().also {
                it.seedDevice("d-1", "Glass One", role = "GLASS")
                it.seedDevice("d-2", "Ubuntu Daemon", role = "DAEMON")
            }

        composeRule.setDevicesContent(repo)
        composeRule.assertText("Glass One")

        composeRule.click("Revoke Glass One")

        composeRule.onNodeWithText("Glass One").assertDoesNotExist()
        composeRule.assertText("Ubuntu Daemon")
        assertThat(repo.deletedIds).containsExactly("d-1")
    }

    @Test
    fun `revoking the last device falls back to the empty line`() {
        val repo = FakeDeviceRepository().also { it.seedDevice("d-1", "Glass One") }

        composeRule.setDevicesContent(repo)
        composeRule.click("Revoke Glass One")

        composeRule.assertText("No paired devices")
        composeRule.onNodeWithText("Glass One").assertDoesNotExist()
    }

    @Test
    fun `revoking the same device twice is idempotent`() {
        // A double tap (or a revoke that races another) must not crash and must
        // leave the store in the same shape: the second delete is a harmless no-op.
        val repo = FakeDeviceRepository().also { it.seedDevice("d-1", "Glass One") }
        val viewModel = DevicesViewModel(repo)

        viewModel.onRevoke("d-1")
        viewModel.onRevoke("d-1")

        assertThat(viewModel.uiState.value.available).isTrue()
        assertThat(viewModel.uiState.value.devices).isEmpty()
        assertThat(repo.deletedIds).containsExactly("d-1", "d-1")
    }

    private fun ComposeContentTestRule.setDevicesContent(repo: DeviceRepository) {
        val viewModel = DevicesViewModel(repo)
        setContent {
            AppTheme {
                DevicesScreen(viewModelFactory = viewModelFactory(viewModel))
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
