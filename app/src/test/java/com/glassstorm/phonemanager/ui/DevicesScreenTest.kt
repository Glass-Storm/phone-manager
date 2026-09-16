package com.glassstorm.phonemanager.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.Register
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
 * The screen must resolve its data through the `DeviceRepository` DOMAIN port, so
 * these tests drive a fake repository (seeded rows, revoke, and a store that throws)
 * rather than a concrete `:adapter` class.
 */
@RunWith(RobolectricTestRunner::class)
class DevicesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders the unavailable state when the repository port is absent`() {
        composeRule.GoSetDevicesContent(Context())

        composeRule.GoAssertText("Devices")
        composeRule.GoAssertText("Device store not available")
    }

    @Test
    fun `an empty store shows the no-devices line`() {
        val GoCtx = Context().also { Register<DeviceRepository>(it, FakeDeviceRepository()) }

        composeRule.GoSetDevicesContent(GoCtx)

        composeRule.GoAssertText("No paired devices")
    }

    @Test
    fun `a seeded device list renders names roles and last-seen`() {
        val GoRepo =
            FakeDeviceRepository().also {
                it.GoSeedDevice("d-1", "Glass One", role = "GLASS", lastSeenMs = 1_700_000_000_000L)
                it.GoSeedDevice("d-2", "Ubuntu Daemon", role = "DAEMON")
            }
        val GoCtx = Context().also { Register<DeviceRepository>(it, GoRepo) }

        composeRule.GoSetDevicesContent(GoCtx)

        composeRule.GoAssertText("Glass One")
        composeRule.GoAssertText("Role: GLASS")
        composeRule.GoAssertText("Ubuntu Daemon")
        composeRule.GoAssertText("Role: DAEMON")
        composeRule.GoAssertText("Last seen: 2023-11-14T22:13:20Z")
    }

    @Test
    fun `a device never seen since pairing shows the never-seen line`() {
        val GoRepo =
            FakeDeviceRepository().also {
                it.GoSeedDevice("d-1", "Glass One", lastSeenMs = null)
            }
        val GoCtx = Context().also { Register<DeviceRepository>(it, GoRepo) }

        composeRule.GoSetDevicesContent(GoCtx)

        composeRule.GoAssertText("Last seen: never")
    }

    @Test
    fun `revoking a device removes its row from the list`() {
        val GoRepo =
            FakeDeviceRepository().also {
                it.GoSeedDevice("d-1", "Glass One", role = "GLASS")
                it.GoSeedDevice("d-2", "Ubuntu Daemon", role = "DAEMON")
            }
        val GoCtx = Context().also { Register<DeviceRepository>(it, GoRepo) }

        composeRule.GoSetDevicesContent(GoCtx)
        composeRule.GoAssertText("Glass One")

        composeRule.GoClick("Revoke Glass One")

        composeRule.onNodeWithText("Glass One").assertDoesNotExist()
        composeRule.GoAssertText("Ubuntu Daemon")
        assertThat(GoRepo.GoDeletedIds).containsExactly("d-1")
    }

    @Test
    fun `revoking the last device falls back to the empty line`() {
        val GoRepo = FakeDeviceRepository().also { it.GoSeedDevice("d-1", "Glass One") }
        val GoCtx = Context().also { Register<DeviceRepository>(it, GoRepo) }

        composeRule.GoSetDevicesContent(GoCtx)
        composeRule.GoClick("Revoke Glass One")

        composeRule.GoAssertText("No paired devices")
        composeRule.onNodeWithText("Glass One").assertDoesNotExist()
    }

    @Test
    fun `a failing repository renders unavailable instead of crashing`() {
        // The port exists but its list read throws, which is exactly what a broken
        // database would do: the screen must degrade, not take the shell down.
        val GoCtx =
            Context().also {
                Register<DeviceRepository>(it, FakeDeviceRepository(GoFailOnList = true))
            }

        composeRule.GoSetDevicesContent(GoCtx)

        composeRule.GoAssertText("Device store not available")
    }

    @Test
    fun `revoking the same device twice is idempotent`() {
        // A double tap (or a revoke that races another) must not crash and must
        // leave the store in the same shape: the second delete is a harmless no-op.
        val GoRepo = FakeDeviceRepository().also { it.GoSeedDevice("d-1", "Glass One") }
        val GoCtx = Context().also { Register<DeviceRepository>(it, GoRepo) }
        val GoViewModel = DevicesViewModel(GoCtx)

        GoViewModel.GoOnRevoke("d-1")
        GoViewModel.GoOnRevoke("d-1")

        assertThat(GoViewModel.GoUiState.value.GoAvailable).isTrue()
        assertThat(GoViewModel.GoUiState.value.GoDevices).isEmpty()
        assertThat(GoRepo.GoDeletedIds).containsExactly("d-1", "d-1")
    }

    private fun ComposeContentTestRule.GoSetDevicesContent(GoCtx: Context) {
        setContent {
            AppTheme {
                DevicesScreen(GoContext = GoCtx)
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
}
