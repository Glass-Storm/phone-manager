package com.glassstorm.phonemanager.ui

import android.os.Looper
import com.glassstorm.phonemanager.core.model.HotspotInfo
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * Dashboard hotspot detection on the JVM.
 *
 * The product uses ONLY the user's manually-enabled Mobile Hotspot, so the
 * dashboard detects it and never creates or stops an access point. These tests pin
 * that contract: the detection is read during refresh, and no hotspot control
 * exists that could start or tear one down.
 */
@RunWith(RobolectricTestRunner::class)
class DashboardViewModelTest {
    @Test
    fun `the dashboard displays the detected manual tether without starting an access point`() {
        val manual =
            HotspotInfo(
                ssid = "manual-tether",
                passphrase = "",
                gatewayIp = "10.84.141.232",
            )
        val hotspot = FakeHotspotController(manualTetherInfo = manual)
        val viewModel =
            DashboardViewModel.forTesting(
                hub = FakeHubServer(),
                hotspot = hotspot,
                hubStarter = FakeHubStarter(),
                io = Dispatchers.Unconfined,
            )

        awaitState(viewModel) { it.hotspot == manual }

        val detected = checkNotNull(viewModel.uiState.value.hotspot)
        assertThat(viewModel.uiState.value.hotspotAvailable).isTrue()
        assertThat(detected.passphrase).isEmpty()
        assertThat(hotspot.startCalls).isEqualTo(0)
        assertThat(hotspot.stopCalls).isEqualTo(0)
    }

    @Test
    fun `availability is null until the first read resolves`() {
        val viewModel =
            DashboardViewModel.forTesting(
                hub = FakeHubServer(),
                hotspot = FakeHotspotController(failOnDetect = true),
                hubStarter = FakeHubStarter(),
                io = Dispatchers.Unconfined,
                refreshOnInit = false,
            )

        // "Not read yet" is distinct from "read failed": the screen renders a
        // placeholder, never the red unavailable state, for this value.
        assertThat(viewModel.uiState.value.hubAvailable).isNull()
        assertThat(viewModel.uiState.value.hotspotAvailable).isNull()
    }

    @Test
    fun `a failed detection degrades to the unavailable state instead of crashing`() {
        val viewModel =
            DashboardViewModel.forTesting(
                hub = FakeHubServer(),
                hotspot = FakeHotspotController(failOnDetect = true),
                hubStarter = FakeHubStarter(),
                io = Dispatchers.Unconfined,
            )

        awaitState(viewModel) { it.hotspotAvailable == false }

        assertThat(viewModel.uiState.value.hotspot).isNull()
    }

    private fun awaitState(
        viewModel: DashboardViewModel,
        predicate: (DashboardUiState) -> Boolean,
    ) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L)
        while (System.nanoTime() < deadlineNanos) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate(viewModel.uiState.value)) return
            Thread.sleep(5L)
        }
        throw AssertionError("dashboard never reached the expected state: ${viewModel.uiState.value}")
    }
}
