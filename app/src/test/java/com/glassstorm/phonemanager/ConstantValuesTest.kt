package com.glassstorm.phonemanager

import com.glassstorm.phonemanager.adapter.android.config.RuntimeConfigStore
import com.glassstorm.phonemanager.adapter.android.network.nsd.NsdDiscoveryAdapter
import com.glassstorm.phonemanager.adapter.android.network.wifi.ManualTetherHotspotAdapter
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.model.PeerAddress
import com.glassstorm.phonemanager.transport.grpc.HubServerAdapter
import com.glassstorm.phonemanager.ui.ROUTES
import com.glassstorm.phonemanager.ui.ROUTE_DASHBOARD
import com.glassstorm.phonemanager.ui.ROUTE_SETTINGS
import com.glassstorm.phonemanager.ui.ROUTE_STREAM
import com.glassstorm.phonemanager.ui.SettingsViewModel
import com.glassstorm.phonemanager.ui.StreamViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The value-preservation lock for the T3 constant rename.
 *
 * T3 renamed SYMBOLS only. Several of these constants are Android-visible
 * (notification channel id, Intent extra key, SharedPreferences file/keys, DNS-SD
 * service name/type) or wire-visible (gRPC method name, reject reasons, peer
 * source tags), and a value change is a silent behavioural regression that breaks
 * real devices and paired peers without failing any other test.
 *
 * Every assertion re-states the EXACT LITERAL from before the rename rather than
 * comparing a constant against its own definition: a tautology would pass even
 * when a value changed.
 */
class ConstantValuesTest {
    @Test
    fun `hub foreground notification identifiers are unchanged`() {
        assertThat(HubForegroundService.CHANNEL_ID).isEqualTo("hub-foreground")
        assertThat(HubForegroundService.EXTRA_PORT)
            .isEqualTo("com.glassstorm.phonemanager.extra.HUB_PORT")
        assertThat(HubForegroundService.NOTIFICATION_ID).isEqualTo(1)
    }

    @Test
    fun `discovery identifiers are unchanged`() {
        assertThat(HubBringUp.DISCOVERY_NAME).isEqualTo("phone-manager")
        assertThat(NsdDiscoveryAdapter.DEFAULT_SERVICE_TYPE).isEqualTo("_ecosys._tcp")
        assertThat(NsdDiscoveryAdapter.MULTICAST_LOCK_TAG).isEqualTo("phone-manager:mdns")
        assertThat(ManualTetherHotspotAdapter.MANUAL_TETHER_SSID).isEqualTo("manual-tether")
    }

    @Test
    fun `persisted runtime config identifiers are unchanged`() {
        assertThat(RuntimeConfigStore.PREFS_NAME).isEqualTo("runtime_config")
        assertThat(RuntimeConfigStore.KEY_HOTSPOT_MODE).isEqualTo("hotspot_mode")
        assertThat(RuntimeConfigStore.HOTSPOT_MANUAL).isEqualTo("manual")
        assertThat(RuntimeConfigStore.HOTSPOT_AUTO).isEqualTo("auto")
    }

    @Test
    fun `protocol display string and bind addresses are unchanged`() {
        assertThat(SettingsViewModel.PROTOCOL).isEqualTo("ecosys.v1")
        assertThat(HubServerAdapter.LOOPBACK_ADDRESS).isEqualTo("127.0.0.1")
        assertThat(HubServerAdapter.ALL_INTERFACES_ADDRESS).isEqualTo("0.0.0.0")
        assertThat(HubForegroundService.DEFAULT_HUB_PORT).isEqualTo(9000)
    }

    @Test
    fun `the three routes and their order are unchanged`() {
        assertThat(ROUTE_DASHBOARD).isEqualTo("dashboard")
        assertThat(ROUTE_STREAM).isEqualTo("stream")
        assertThat(ROUTE_SETTINGS).isEqualTo("settings")
        assertThat(ROUTES)
            .containsExactly("dashboard", "stream", "settings")
            .inOrder()
    }

    @Test
    fun `peer source tags are unchanged`() {
        assertThat(PeerAddress.SOURCE_MDNS).isEqualTo("mdns")
        assertThat(PeerAddress.SOURCE_GATEWAY).isEqualTo("gateway")
    }

    @Test
    fun `numeric protocol and policy constants are unchanged`() {
        assertThat(StreamViewModel.DEFAULT_POLL_INTERVAL_MS).isEqualTo(500L)
        assertThat(StreamService.AUDIO_SAMPLE_RATE_HZ).isEqualTo(16_000)
    }
}
