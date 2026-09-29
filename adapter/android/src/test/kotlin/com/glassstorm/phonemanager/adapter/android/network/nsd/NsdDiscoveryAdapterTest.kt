package com.glassstorm.phonemanager.adapter.android.network.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.glassstorm.phonemanager.core.domain.network.DiscoveryState
import com.glassstorm.phonemanager.core.model.PeerAddress
import com.google.common.truth.Truth.assertThat
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
 * `usesServiceInfoCallback` and locked in the `:core:domain` predicate tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NsdDiscoveryAdapterTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val nsd: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi: WifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val shadowNsd = shadowOf(nsd) as ShadowNsdManager
    private val shadowWifi = shadowOf(wifi) as ShadowWifiManager

    private val serviceName = "phone-hub"
    private val serviceType = NsdDiscoveryAdapter.DEFAULT_SERVICE_TYPE
    private val gateway = PeerAddress("192.168.43.1", 9000, PeerAddress.SOURCE_GATEWAY)

    private fun adapter(): NsdDiscoveryAdapter = NsdDiscoveryAdapter(context, gateway)

    private fun registeredInfo(): NsdServiceInfo =
        NsdServiceInfo().apply {
            serviceName = this@NsdDiscoveryAdapterTest.serviceName
            serviceType = this@NsdDiscoveryAdapterTest.serviceType
        }

    @After
    fun resetShadows() {
        ShadowNsdManager.reset()
    }

    // ---- advertisement lifecycle -------------------------------------------------

    @Test
    fun `advertise registers the ecosys service on the hub port`() {
        // Given an adapter that is not advertising
        val discovery = adapter()

        // When the hub advertises itself
        discovery.advertise(serviceName, 9000)

        // Then the platform really holds a registration for `_ecosys._tcp`
        val listener = shadowNsd.getRegistrationListener(registeredInfo())
        assertThat(listener).isNotNull()
        val info = shadowNsd.getRegisteredServiceInfo(listener)!!
        assertThat(info.serviceName).isEqualTo(serviceName)
        assertThat(info.serviceType).isEqualTo("_ecosys._tcp")
        assertThat(info.port).isEqualTo(9000)
        assertThat(discovery.isAdvertising()).isTrue()
        assertThat(discovery.state).isEqualTo(DiscoveryState.ADVERTISING)
    }

    @Test
    fun `stop advertise unregisters the service`() {
        // Given an advertised service
        val discovery = adapter()
        discovery.advertise(serviceName, 9000)

        // When the hub stops advertising
        discovery.stopAdvertise()

        // Then the registration is gone from the platform
        assertThat(shadowNsd.getRegistrationListener(registeredInfo())).isNull()
        assertThat(discovery.isAdvertising()).isFalse()
        assertThat(discovery.state).isEqualTo(DiscoveryState.IDLE)
    }

    @Test
    fun `stop advertise is idempotent when never started`() {
        // Given an adapter that never advertised
        val discovery = adapter()

        // When teardown runs twice
        discovery.stopAdvertise()
        discovery.stopAdvertise()

        // Then nothing is registered and no exception escapes
        assertThat(shadowNsd.getRegistrationListener(registeredInfo())).isNull()
        assertThat(discovery.state).isEqualTo(DiscoveryState.IDLE)
    }

    @Test
    fun `advertise is idempotent and keeps a single registration`() {
        // Given an advertised service
        val discovery = adapter()
        discovery.advertise(serviceName, 9000)

        // When advertise is called again with the same name and port
        discovery.advertise(serviceName, 9000)

        // Then the platform still holds exactly one registration
        val listener = shadowNsd.getRegistrationListener(registeredInfo())
        assertThat(listener).isNotNull()
        assertThat(shadowNsd.getRegisteredServiceInfo(listener)!!.port).isEqualTo(9000)
    }

    // ---- multicast lock -----------------------------------------------------------

    @Test
    fun `multicast lock is acquired on advertise and released on teardown`() {
        // Given no active Wi-Fi locks
        assertThat(shadowWifi.activeLockCount).isEqualTo(0)

        // When the hub advertises on API 29 (below the API-33 multicast threshold)
        val discovery = adapter()
        discovery.advertise(serviceName, 9000)

        // Then the app holds the multicast lock so mDNS frames are not filtered
        assertThat(shadowWifi.activeLockCount).isEqualTo(1)

        // When the hub tears down
        discovery.stopAdvertise()

        // Then the lock is released
        assertThat(shadowWifi.activeLockCount).isEqualTo(0)
    }

    @Test
    fun `releasing the multicast lock twice does not throw`() {
        // Given an advertised service holding the lock
        val discovery = adapter()
        discovery.advertise(serviceName, 9000)

        // When teardown runs twice
        discovery.stopAdvertise()
        discovery.stopAdvertise()

        // Then the lock count never goes negative and no exception escapes
        assertThat(shadowWifi.activeLockCount).isEqualTo(0)
    }

    // ---- resolution + direct-IP fallback -------------------------------------------

    @Test
    fun `resolve failure falls back to the direct gateway ip within the timeout`() {
        // Given an advertised hub with a configured gateway fallback
        val discovery = adapter()
        discovery.advertise(serviceName, 9000)

        // When a peer is resolved on a worker thread and mDNS fails
        val result =
            resolveOnWorker(discovery, timeoutMs = 2_000) { _, listener, _ ->
                listener.onResolveFailed(listener.serviceInfo, 0)
            }

        // Then the returned peer is the gateway, not an exception
        assertThat(result.peer).isEqualTo(gateway)
        assertThat(result.peer!!.host).isEqualTo("192.168.43.1")
        assertThat(result.peer.port).isEqualTo(9000)
        assertThat(result.peer.source).isEqualTo(PeerAddress.SOURCE_GATEWAY)
        assertThat(result.elapsedMs).isLessThan(2_000)
        assertThat(discovery.state).isEqualTo(DiscoveryState.FALLBACK)
    }

    @Test
    fun `resolve timeout falls back to the direct gateway ip`() {
        // Given an advertised hub with a configured gateway fallback
        val discovery = adapter()
        discovery.advertise(serviceName, 9000)

        // When the peer never answers within the window
        val result = resolveOnWorker(discovery, timeoutMs = 200) { _, _, _ -> }

        // Then the gateway fallback is selected rather than throwing
        assertThat(result.peer).isEqualTo(gateway)
        assertThat(result.peer!!.source).isEqualTo(PeerAddress.SOURCE_GATEWAY)
        assertThat(discovery.state).isEqualTo(DiscoveryState.FALLBACK)
    }

    @Test
    fun `successful mdns resolution returns the discovered peer`() {
        // Given an advertised hub with a gateway fallback available
        val discovery = adapter()
        discovery.advertise(serviceName, 9000)

        // When the shadow reports a resolved peer with a real host and port
        val result =
            resolveOnWorker(discovery, timeoutMs = 2_000) { _, listener, _ ->
                val resolved =
                    NsdServiceInfo().apply {
                        serviceName = this@NsdDiscoveryAdapterTest.serviceName
                        serviceType = this@NsdDiscoveryAdapterTest.serviceType
                        setHost(java.net.InetAddress.getByName("192.168.43.7"))
                        port = 9100
                    }
                listener.onServiceResolved(resolved)
            }

        // Then that peer is returned with source mdns, and the fallback is not used
        assertThat(result.peer).isEqualTo(
            PeerAddress("192.168.43.7", 9100, PeerAddress.SOURCE_MDNS),
        )
        assertThat(discovery.state).isEqualTo(DiscoveryState.RESOLVED)
    }

    private class ResolveOutcome(
        val peer: PeerAddress?,
        val elapsedMs: Long,
    )

    private class ResolveListenerHandle(
        val serviceInfo: NsdServiceInfo,
        val listener: NsdManager.ResolveListener,
    ) {
        fun onResolveFailed(
            info: NsdServiceInfo,
            code: Int,
        ) = listener.onResolveFailed(info, code)

        fun onServiceResolved(info: NsdServiceInfo) = listener.onServiceResolved(info)
    }

    /**
     * Drives [NsdDiscoveryAdapter.resolveFirst] on a worker thread so the test can
     * poke the shadow-recorded NSD listeners — exactly as the Android framework would.
     */
    private fun resolveOnWorker(
        discovery: NsdDiscoveryAdapter,
        timeoutMs: Long,
        poke: (NsdDiscoveryAdapter, ResolveListenerHandle, NsdServiceInfo) -> Unit,
    ): ResolveOutcome {
        var peer: PeerAddress? = null
        var elapsed = 0L
        val thread =
            Thread {
                val started = System.nanoTime()
                peer = discovery.resolveFirst(timeoutMs)
                elapsed = (System.nanoTime() - started) / 1_000_000
            }
        thread.start()

        val discoveryListeners = awaitDiscoveryListener()
        val found =
            NsdServiceInfo().apply {
                serviceName = this@NsdDiscoveryAdapterTest.serviceName
                serviceType = this@NsdDiscoveryAdapterTest.serviceType
            }
        discoveryListeners.onServiceFound(found)

        val resolveListener = awaitResolveListener(found)
        poke(discovery, resolveListener, found)

        thread.join(10_000)
        if (thread.isAlive) fail("resolveFirst did not return within the test window")
        return ResolveOutcome(peer, elapsed)
    }

    private fun awaitDiscoveryListener(): NsdManager.DiscoveryListener =
        await("discovery listener") {
            shadowNsd.getDiscoveryListeners(serviceType)?.firstOrNull()
        }

    private fun awaitResolveListener(info: NsdServiceInfo): ResolveListenerHandle =
        await("resolve listener") {
            shadowNsd.getResolveListeners(info)?.firstOrNull()?.let {
                ResolveListenerHandle(info, it)
            }
        }

    private fun <T> await(
        label: String,
        probe: () -> T?,
    ): T {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            probe()?.let { return it }
            Thread.sleep(5)
        }
        fail("timed out waiting for the $label")
        error("unreachable")
    }
}
