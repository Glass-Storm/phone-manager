package com.glassstorm.phonemanager.adapter.android.network.wifi

import com.glassstorm.phonemanager.core.domain.network.HotspotFailure
import com.glassstorm.phonemanager.core.domain.network.HotspotUnavailableException
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Coverage for the manual-tether-only [ManualTetherHotspotAdapter].
 *
 * The adapter is pure JVM: it touches no Android type, so the host JVM exercises
 * the real interface-selection logic through the [TetherProbe] seam. The property
 * that matters is that the hub only ever DETECTS the user's hotspot; nothing here
 * creates or tears one down.
 */
class ManualTetherHotspotAdapterTest {
    private fun adapter(candidates: List<TetherCandidate>): ManualTetherHotspotAdapter =
        ManualTetherHotspotAdapter(tetherProbe = TetherProbe { candidates })

    @Test
    fun `startHotspot returns the detected tether gateway`() {
        val hotspot =
            adapter(
                listOf(
                    TetherCandidate(name = "lo", ipv4 = "127.0.0.1"),
                    TetherCandidate(name = "wlan0", ipv4 = "192.168.1.5"),
                    TetherCandidate(name = "ap0", ipv4 = "192.168.43.1"),
                ),
            )

        val info = hotspot.startHotspot()

        assertThat(info.ssid).isEqualTo(ManualTetherHotspotAdapter.MANUAL_TETHER_SSID)
        assertThat(info.passphrase).isEmpty()
        assertThat(info.gatewayIp).isEqualTo("192.168.43.1")
    }

    @Test
    fun `startHotspot fails typed when no manual hotspot is enabled`() {
        val hotspot = adapter(listOf(TetherCandidate(name = "wlan0", ipv4 = "192.168.1.5")))

        val thrown =
            assertThrows(HotspotUnavailableException::class.java) {
                hotspot.startHotspot()
            }

        assertThat(thrown.failure).isInstanceOf(HotspotFailure.StartFailed::class.java)
        assertThat((thrown.failure as HotspotFailure.StartFailed).reason)
            .isEqualTo("no manual hotspot; enable the phone's Mobile Hotspot")
    }

    @Test
    fun `detectManualTether is null when no tether interface exists`() {
        val hotspot =
            adapter(
                listOf(
                    TetherCandidate(name = "lo", ipv4 = "127.0.0.1"),
                    TetherCandidate(name = "wlan0", ipv4 = "192.168.1.5"),
                ),
            )

        assertThat(hotspot.detectManualTether()).isNull()
        assertThat(hotspot.isActive()).isFalse()
    }

    @Test
    fun `isActive follows the tether interface and never claims an app-owned AP`() {
        val hotspot = adapter(listOf(TetherCandidate(name = "swlan0", ipv4 = "10.84.141.232")))

        assertThat(hotspot.isActive()).isTrue()
        assertThat(hotspot.detectManualTether()?.gatewayIp).isEqualTo("10.84.141.232")
    }

    @Test
    fun `stopHotspot is a no-op and leaves the user's tether detected`() {
        val hotspot = adapter(listOf(TetherCandidate(name = "ap0", ipv4 = "192.168.43.1")))

        hotspot.stopHotspot()
        hotspot.stopHotspot()

        assertThat(hotspot.isActive()).isTrue()
        assertThat(hotspot.detectManualTether()?.gatewayIp).isEqualTo("192.168.43.1")
    }

    @Test
    fun `the tether selector prefers AP-style interface names over station ones`() {
        val chosen =
            pickTetherGateway(
                listOf(
                    TetherCandidate(name = "wlan0", ipv4 = "192.168.1.5"),
                    TetherCandidate(name = "lo", ipv4 = "127.0.0.1"),
                    TetherCandidate(name = "swlan0", ipv4 = "192.168.12.1"),
                ),
            )

        assertThat(chosen?.ipv4).isEqualTo("192.168.12.1")
    }

    @Test
    fun `AP interface matching is case insensitive`() {
        val chosen =
            pickTetherGateway(
                listOf(
                    TetherCandidate(name = "WLAN0", ipv4 = "192.168.1.5"),
                    TetherCandidate(name = "AP0", ipv4 = "192.168.43.1"),
                ),
            )

        assertThat(chosen?.ipv4).isEqualTo("192.168.43.1")
    }
}
