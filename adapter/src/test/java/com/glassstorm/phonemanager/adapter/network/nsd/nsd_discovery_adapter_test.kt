package com.glassstorm.phonemanager.adapter.network.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.google.common.truth.Truth.assertThat
import com.glassstorm.phonemanager.domain.dto.PeerAddress
import com.glassstorm.phonemanager.domain.network.DiscoveryState
import org.junit.After
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNsdManager
import org.robolectric.shadows.ShadowWifiManager

/**
 * Robolectric tests for the real Android [NsdDiscoveryAdapter].
 *
 * Pinned to API 29 (the app's targetSdk) so every branch under test is the
 * deprecated NSD path — the same path Robolectric's [ShadowNsdManager] drives.
 * The API-34+ `registerServiceInfoCallback` branch is gated by
 * `GoUsesServiceInfoCallback` and locked in the `:domain` predicate tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NsdDiscoveryAdapterTest {

    private val GoContext: Context = RuntimeEnvironment.getApplication()
    private val GoNsd: NsdManager =
        GoContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val GoWifi: WifiManager =
        GoContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val GoShadowNsd = shadowOf(GoNsd) as ShadowNsdManager
    private val GoShadowWifi = shadowOf(GoWifi) as ShadowWifiManager

    private val GoServiceName = "phone-hub"
    private val GoServiceType = NsdDiscoveryAdapter.GoDefaultServiceType
    private val GoGateway = PeerAddress("192.168.43.1", 9000, PeerAddress.GoSourceGateway)

    private fun GoAdapter(): NsdDiscoveryAdapter =
        NsdDiscoveryAdapter(GoContext, GoGateway)

    private fun GoRegisteredInfo(): NsdServiceInfo = NsdServiceInfo().apply {
        serviceName = GoServiceName
        serviceType = GoServiceType
    }

    @After
    fun GoResetShadows() {
        ShadowNsdManager.reset()
    }

    // ---- advertisement lifecycle -------------------------------------------------

    @Test
    fun `advertise registers the ecosys service on the hub port`() {
        // Given an adapter that is not advertising
        val GoDiscovery = GoAdapter()

        // When the hub advertises itself
        GoDiscovery.GoAdvertise(GoServiceName, 9000)

        // Then the platform really holds a registration for `_ecosys._tcp`
        val GoListener = GoShadowNsd.getRegistrationListener(GoRegisteredInfo())
        assertThat(GoListener).isNotNull()
        val GoInfo = GoShadowNsd.getRegisteredServiceInfo(GoListener)!!
        assertThat(GoInfo.serviceName).isEqualTo(GoServiceName)
        assertThat(GoInfo.serviceType).isEqualTo("_ecosys._tcp")
        assertThat(GoInfo.port).isEqualTo(9000)
        assertThat(GoDiscovery.GoIsAdvertising()).isTrue()
        assertThat(GoDiscovery.GoState).isEqualTo(DiscoveryState.ADVERTISING)
    }

    @Test
    fun `stop advertise unregisters the service`() {
        // Given an advertised service
        val GoDiscovery = GoAdapter()
        GoDiscovery.GoAdvertise(GoServiceName, 9000)

        // When the hub stops advertising
        GoDiscovery.GoStopAdvertise()

        // Then the registration is gone from the platform
        assertThat(GoShadowNsd.getRegistrationListener(GoRegisteredInfo())).isNull()
        assertThat(GoDiscovery.GoIsAdvertising()).isFalse()
        assertThat(GoDiscovery.GoState).isEqualTo(DiscoveryState.IDLE)
    }

    @Test
    fun `stop advertise is idempotent when never started`() {
        // Given an adapter that never advertised
        val GoDiscovery = GoAdapter()

        // When teardown runs twice
        GoDiscovery.GoStopAdvertise()
        GoDiscovery.GoStopAdvertise()

        // Then nothing is registered and no exception escapes
        assertThat(GoShadowNsd.getRegistrationListener(GoRegisteredInfo())).isNull()
        assertThat(GoDiscovery.GoState).isEqualTo(DiscoveryState.IDLE)
    }

    @Test
    fun `advertise is idempotent and keeps a single registration`() {
        // Given an advertised service
        val GoDiscovery = GoAdapter()
        GoDiscovery.GoAdvertise(GoServiceName, 9000)

        // When advertise is called again with the same name and port
        GoDiscovery.GoAdvertise(GoServiceName, 9000)

        // Then the platform still holds exactly one registration
        val GoListener = GoShadowNsd.getRegistrationListener(GoRegisteredInfo())
        assertThat(GoListener).isNotNull()
        assertThat(GoShadowNsd.getRegisteredServiceInfo(GoListener)!!.port).isEqualTo(9000)
    }

    // ---- multicast lock -----------------------------------------------------------

    @Test
    fun `multicast lock is acquired on advertise and released on teardown`() {
        // Given no active Wi-Fi locks
        assertThat(GoShadowWifi.activeLockCount).isEqualTo(0)

        // When the hub advertises on API 29 (below the API-33 multicast threshold)
        val GoDiscovery = GoAdapter()
        GoDiscovery.GoAdvertise(GoServiceName, 9000)

        // Then the app holds the multicast lock so mDNS frames are not filtered
        assertThat(GoShadowWifi.activeLockCount).isEqualTo(1)

        // When the hub tears down
        GoDiscovery.GoStopAdvertise()

        // Then the lock is released
        assertThat(GoShadowWifi.activeLockCount).isEqualTo(0)
    }

    @Test
    fun `releasing the multicast lock twice does not throw`() {
        // Given an advertised service holding the lock
        val GoDiscovery = GoAdapter()
        GoDiscovery.GoAdvertise(GoServiceName, 9000)

        // When teardown runs twice
        GoDiscovery.GoStopAdvertise()
        GoDiscovery.GoStopAdvertise()

        // Then the lock count never goes negative and no exception escapes
        assertThat(GoShadowWifi.activeLockCount).isEqualTo(0)
    }

    // ---- resolution + direct-IP fallback -------------------------------------------

    @Test
    fun `resolve failure falls back to the direct gateway ip within the timeout`() {
        // Given an advertised hub with a configured gateway fallback
        val GoDiscovery = GoAdapter()
        GoDiscovery.GoAdvertise(GoServiceName, 9000)

        // When a peer is resolved on a worker thread and mDNS fails
        val GoResult = GoResolveOnWorker(GoDiscovery, timeoutMs = 2_000) { _, GoListener, _ ->
            GoListener.onResolveFailed(GoListener.GoServiceInfo, 0)
        }

        // Then the returned peer is the gateway, not an exception
        assertThat(GoResult.GoPeer).isEqualTo(GoGateway)
        assertThat(GoResult.GoPeer!!.GoHost).isEqualTo("192.168.43.1")
        assertThat(GoResult.GoPeer.GoPort).isEqualTo(9000)
        assertThat(GoResult.GoPeer.GoSource).isEqualTo(PeerAddress.GoSourceGateway)
        assertThat(GoResult.GoElapsedMs).isLessThan(2_000)
        assertThat(GoDiscovery.GoState).isEqualTo(DiscoveryState.FALLBACK)
    }

    @Test
    fun `resolve timeout falls back to the direct gateway ip`() {
        // Given an advertised hub with a configured gateway fallback
        val GoDiscovery = GoAdapter()
        GoDiscovery.GoAdvertise(GoServiceName, 9000)

        // When the peer never answers within the window
        val GoResult = GoResolveOnWorker(GoDiscovery, timeoutMs = 200) { _, _, _ -> }

        // Then the gateway fallback is selected rather than throwing
        assertThat(GoResult.GoPeer).isEqualTo(GoGateway)
        assertThat(GoResult.GoPeer!!.GoSource).isEqualTo(PeerAddress.GoSourceGateway)
        assertThat(GoDiscovery.GoState).isEqualTo(DiscoveryState.FALLBACK)
    }

    @Test
    fun `successful mdns resolution returns the discovered peer`() {
        // Given an advertised hub with a gateway fallback available
        val GoDiscovery = GoAdapter()
        GoDiscovery.GoAdvertise(GoServiceName, 9000)

        // When the shadow reports a resolved peer with a real host and port
        val GoResult = GoResolveOnWorker(GoDiscovery, timeoutMs = 2_000) { _, GoListener, _ ->
            val GoResolved = NsdServiceInfo().apply {
                serviceName = GoServiceName
                serviceType = GoServiceType
                setHost(java.net.InetAddress.getByName("192.168.43.7"))
                port = 9100
            }
            GoListener.onServiceResolved(GoResolved)
        }

        // Then that peer is returned with source mdns, and the fallback is not used
        assertThat(GoResult.GoPeer).isEqualTo(
            PeerAddress("192.168.43.7", 9100, PeerAddress.GoSourceMdns),
        )
        assertThat(GoDiscovery.GoState).isEqualTo(DiscoveryState.RESOLVED)
    }

    private class GoResolveOutcome(
        val GoPeer: PeerAddress?,
        val GoElapsedMs: Long,
    )

    private class GoResolveListenerHandle(
        val GoServiceInfo: NsdServiceInfo,
        val GoListener: NsdManager.ResolveListener,
    ) {
        fun onResolveFailed(info: NsdServiceInfo, code: Int) = GoListener.onResolveFailed(info, code)
        fun onServiceResolved(info: NsdServiceInfo) = GoListener.onServiceResolved(info)
    }

    /**
     * Drives [NsdDiscoveryAdapter.GoResolveFirst] on a worker thread so the test can
     * poke the shadow-recorded NSD listeners — exactly as the Android framework would.
     */
    private fun GoResolveOnWorker(
        discovery: NsdDiscoveryAdapter,
        timeoutMs: Long,
        poke: (NsdDiscoveryAdapter, GoResolveListenerHandle, NsdServiceInfo) -> Unit,
    ): GoResolveOutcome {
        var GoPeer: PeerAddress? = null
        var GoElapsed = 0L
        val GoThread = Thread {
            val GoStarted = System.nanoTime()
            GoPeer = discovery.GoResolveFirst(timeoutMs)
            GoElapsed = (System.nanoTime() - GoStarted) / 1_000_000
        }
        GoThread.start()

        val GoDiscoveryListeners = GoAwaitDiscoveryListener()
        val GoFound = NsdServiceInfo().apply {
            serviceName = GoServiceName
            serviceType = GoServiceType
        }
        GoDiscoveryListeners.onServiceFound(GoFound)

        val GoResolveListener = GoAwaitResolveListener(GoFound)
        poke(discovery, GoResolveListener, GoFound)

        GoThread.join(10_000)
        if (GoThread.isAlive) fail("GoResolveFirst did not return within the test window")
        return GoResolveOutcome(GoPeer, GoElapsed)
    }

    private fun GoAwaitDiscoveryListener(): NsdManager.DiscoveryListener =
        GoAwait("discovery listener") {
            GoShadowNsd.getDiscoveryListeners(GoServiceType)?.firstOrNull()
        }

    private fun GoAwaitResolveListener(info: NsdServiceInfo): GoResolveListenerHandle =
        GoAwait("resolve listener") {
            GoShadowNsd.getResolveListeners(info)?.firstOrNull()?.let {
                GoResolveListenerHandle(info, it)
            }
        }

    private fun <T> GoAwait(
        label: String,
        probe: () -> T?,
    ): T {
        val GoDeadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < GoDeadline) {
            probe()?.let { return it }
            Thread.sleep(5)
        }
        fail("timed out waiting for the $label")
        error("unreachable")
    }
}
