package com.glassstorm.phonemanager

import com.glassstorm.phonemanager.adapter.config.RuntimeConfigStore
import com.glassstorm.phonemanager.adapter.network.nsd.NsdDiscoveryAdapter
import com.glassstorm.phonemanager.adapter.network.wifi.LocalOnlyHotspotAdapter
import com.glassstorm.phonemanager.adapter.speech.mock.MockSttAdapter
import com.glassstorm.phonemanager.adapter.speech.speechmatics.ADD_TRANSCRIPT
import com.glassstorm.phonemanager.adapter.speech.speechmatics.DEFAULT_LANGUAGE
import com.glassstorm.phonemanager.adapter.speech.speechmatics.NORMAL_CLOSURE
import com.glassstorm.phonemanager.adapter.speech.speechmatics.SAMPLE_RATE_HZ
import com.glassstorm.phonemanager.adapter.transport.grpc.HubServerAdapter
import com.glassstorm.phonemanager.core.model.PairOutcome
import com.glassstorm.phonemanager.core.model.PeerAddress
import com.glassstorm.phonemanager.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.domain.service.PairingService
import com.glassstorm.phonemanager.domain.service.StreamService
import com.glassstorm.phonemanager.service.security.AuthInterceptor
import com.glassstorm.phonemanager.service.security.TokenCodec
import com.glassstorm.phonemanager.ui.PairingViewModel
import com.glassstorm.phonemanager.ui.ROUTES
import com.glassstorm.phonemanager.ui.ROUTE_DASHBOARD
import com.glassstorm.phonemanager.ui.ROUTE_DEVICES
import com.glassstorm.phonemanager.ui.ROUTE_PAIRING
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
        assertThat(LocalOnlyHotspotAdapter.DEFAULT_GATEWAY_IP).isEqualTo("192.168.43.1")
    }

    @Test
    fun `persisted runtime config identifiers are unchanged`() {
        assertThat(RuntimeConfigStore.PREFS_NAME).isEqualTo("runtime_config")
        assertThat(RuntimeConfigStore.KEY_STT_ADAPTER).isEqualTo("stt_adapter")
        assertThat(RuntimeConfigStore.KEY_API_KEY).isEqualTo("api_key")
        assertThat(RuntimeConfigStore.KEY_REGION).isEqualTo("region")
        assertThat(RuntimeConfigStore.KEY_HOTSPOT_MODE).isEqualTo("hotspot_mode")
        assertThat(RuntimeConfigStore.ADAPTER_MOCK).isEqualTo("mock")
        assertThat(RuntimeConfigStore.ADAPTER_SPEECHMATICS).isEqualTo("speechmatics")
        assertThat(RuntimeConfigStore.HOTSPOT_MANUAL).isEqualTo("manual")
        assertThat(RuntimeConfigStore.HOTSPOT_AUTO).isEqualTo("auto")
        assertThat(RuntimeConfigStore.DEFAULT_REGION).isEqualTo("us")
        assertThat(RuntimeConfigStore.PLACEHOLDER_API_KEY).isEqualTo("YOUR_SPEECHMATICS_API_KEY")
        assertThat(AppConfig.DEFAULT_REGION).isEqualTo("us")
    }

    @Test
    fun `protocol display string and bind addresses are unchanged`() {
        assertThat(SettingsViewModel.PROTOCOL).isEqualTo("ecosys.v1")
        assertThat(HubServerAdapter.LOOPBACK_ADDRESS).isEqualTo("127.0.0.1")
        assertThat(HubServerAdapter.ALL_INTERFACES_ADDRESS).isEqualTo("0.0.0.0")
        assertThat(AppComposition.DEFAULT_HUB_PORT).isEqualTo(9000)
    }

    @Test
    fun `the five routes and their order are unchanged`() {
        assertThat(ROUTE_DASHBOARD).isEqualTo("dashboard")
        assertThat(ROUTE_PAIRING).isEqualTo("pairing")
        assertThat(ROUTE_STREAM).isEqualTo("stream")
        assertThat(ROUTE_DEVICES).isEqualTo("devices")
        assertThat(ROUTE_SETTINGS).isEqualTo("settings")
        assertThat(ROUTES)
            .containsExactly("dashboard", "pairing", "stream", "devices", "settings")
            .inOrder()
    }

    @Test
    fun `the seven pairing reject reasons are unchanged`() {
        assertThat(PairOutcome.REASON_NO_WINDOW).isEqualTo("no-window")
        assertThat(PairOutcome.REASON_PIN_INVALID).isEqualTo("pin-invalid")
        assertThat(PairOutcome.REASON_PIN_EXPIRED).isEqualTo("pin-expired")
        assertThat(PairOutcome.REASON_PIN_CONSUMED).isEqualTo("pin-consumed")
        assertThat(PairOutcome.REASON_PIN_LOCKED).isEqualTo("pin-locked")
        assertThat(PairOutcome.REASON_PIN_MISSING).isEqualTo("pin-missing")
        assertThat(PairOutcome.REASON_NAME_MISSING).isEqualTo("name-missing")
    }

    @Test
    fun `peer source tags are unchanged`() {
        assertThat(PeerAddress.SOURCE_MDNS).isEqualTo("mdns")
        assertThat(PeerAddress.SOURCE_GATEWAY).isEqualTo("gateway")
    }

    @Test
    fun `the unauthenticated gRPC method allowlist entry is unchanged`() {
        assertThat(AuthInterceptor.PAIR_METHOD).isEqualTo("ecosys.v1.PairingService/Pair")
    }

    @Test
    fun `numeric protocol and policy constants are unchanged`() {
        assertThat(PairingService.MAX_PIN_ATTEMPTS).isEqualTo(5)
        assertThat(PairingViewModel.WINDOW_TTL_MS).isEqualTo(120_000L)
        assertThat(StreamViewModel.DEFAULT_POLL_INTERVAL_MS).isEqualTo(250L)
        assertThat(StreamViewModel.DEFAULT_PEER_ID).isEqualTo("local-peer")
        assertThat(StreamService.AUDIO_SAMPLE_RATE_HZ).isEqualTo(16_000)
        assertThat(TokenCodec.DEFAULT_ITERATIONS).isEqualTo(210_000)
        assertThat(TokenCodec.MIN_ITERATIONS).isEqualTo(100_000)
        assertThat(TokenCodec.TOKEN_BITS).isEqualTo(256)
        assertThat(TokenCodec.SALT_BYTES).isEqualTo(16)
    }

    @Test
    fun `speechmatics wire constants are unchanged`() {
        assertThat(SAMPLE_RATE_HZ).isEqualTo(16_000)
        assertThat(DEFAULT_LANGUAGE).isEqualTo("en")
        assertThat(ADD_TRANSCRIPT).isEqualTo("AddTranscript")
        assertThat(NORMAL_CLOSURE).isEqualTo(1000)
    }

    @Test
    fun `fnv constants used by the deterministic mock are unchanged`() {
        assertThat(MockSttAdapter.FNV_OFFSET_BASIS).isEqualTo(-0x340d631b7bdddcdbL)
        assertThat(MockSttAdapter.FNV_PRIME).isEqualTo(0x100000001b3L)
    }
}
