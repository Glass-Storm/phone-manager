package com.glassstorm.phonemanager.adapter.network.nsd

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import com.glassstorm.phonemanager.domain.adapter.network.Discovery
import com.glassstorm.phonemanager.domain.dto.PeerAddress
import com.glassstorm.phonemanager.domain.network.DiscoveryEvent
import com.glassstorm.phonemanager.domain.network.DiscoveryFailure
import com.glassstorm.phonemanager.domain.network.DiscoverySelection
import com.glassstorm.phonemanager.domain.network.DiscoveryState
import com.glassstorm.phonemanager.domain.network.DiscoveryStateMachine
import com.glassstorm.phonemanager.domain.network.needsMulticastLock
import com.glassstorm.phonemanager.domain.network.usesServiceInfoCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Android [Discovery] implementation over [NsdManager].
 *
 * Advertises `_ecosys._tcp` and resolves the first peer, with three platform
 * accommodations:
 *
 *  - **API branching**: `resolveService` is used below API 35 and the deprecated
 *    path is handled explicitly; from API 35 `registerServiceInfoCallback` is used.
 *  - **Multicast lock**: held while advertising on platforms whose Wi-Fi stack
 *    filters multicast frames (see `needsMulticastLock`), released on teardown.
 *  - **Direct-IP fallback**: an mDNS miss/timeout resolves to the configured
 *    gateway address instead of throwing. This is the primary path in the
 *    manual-tether development flow, so it is a normal outcome, not an error.
 */
class NsdDiscoveryAdapter(
    private val context: Context,
    private val gatewayFallback: PeerAddress?,
) : Discovery {
    private val nsd: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val wifi: WifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val machine = DiscoveryStateMachine(gatewayFallback)

    private var registrationListener: NsdManager.RegistrationListener? = null

    private var multicastLock: WifiManager.MulticastLock? = null

    val state: DiscoveryState
        get() = machine.state

    fun isAdvertising(): Boolean = registrationListener != null

    override fun advertise(
        name: String,
        port: Int,
    ) {
        if (registrationListener != null) return

        val info =
            NsdServiceInfo().apply {
                serviceName = name
                serviceType = DEFAULT_SERVICE_TYPE
                this.port = port
            }
        val listener = RegistrationListenerImpl()
        registrationListener = listener
        machine.accept(DiscoveryEvent.START_ADVERTISE)

        acquireMulticastLock()

        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (goFailure: RuntimeException) {
            machine.accept(
                DiscoveryEvent.ADVERTISE_FAILED,
                DiscoveryFailure.AdvertiseFailed(
                    goFailure.message ?: "registerService threw",
                ),
            )
            registrationListener = null
            releaseMulticastLock()
        }
    }

    override fun stopAdvertise() {
        val listener =
            registrationListener ?: run {
                releaseMulticastLock()
                machine.accept(DiscoveryEvent.STOP_REQUESTED)
                return
            }
        registrationListener = null
        try {
            nsd.unregisterService(listener)
        } catch (goFailure: RuntimeException) {
            registrationListener = listener
            throw goFailure
        }
        releaseMulticastLock()
        machine.accept(DiscoveryEvent.STOP_REQUESTED)
    }

    override fun resolveFirst(timeoutMs: Long): PeerAddress? {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val latch = CountDownLatch(1)
        val resolved = AtomicReference<PeerAddress?>(null)

        machine.accept(DiscoveryEvent.START_RESOLVE)

        val discoveryListener = DiscoveryListenerImpl(latch, resolved)
        val discovery =
            try {
                nsd.discoverServices(DEFAULT_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                true
            } catch (goFailure: RuntimeException) {
                false
            }

        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
        val answered =
            remainingMs > 0 &&
                latch.await(remainingMs, TimeUnit.MILLISECONDS)

        if (discovery) {
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        }

        if (answered) {
            val peer = resolved.get()
            if (peer != null) {
                machine.accept(DiscoveryEvent.MDNS_RESOLVED)
                return peer
            }
        }

        return fallBackToGateway()
    }

    private fun fallBackToGateway(): PeerAddress? {
        machine.accept(DiscoveryEvent.RESOLVE_TIMEOUT)
        val selection = machine.selectPeer(mdns = null, gateway = gatewayFallback)
        return when (selection) {
            is DiscoverySelection.Selected -> selection.peer
            DiscoverySelection.NoPeer -> null
        }
    }

    private fun acquireMulticastLock() {
        val needed =
            needsMulticastLock(Build.VERSION.SDK_INT) {
                tiramisuExtensionVersion()
            }
        if (!needed) return
        val lock = wifi.createMulticastLock(MULTICAST_LOCK_TAG)
        lock.setReferenceCounted(false)
        lock.acquire()
        multicastLock = lock
    }

    /**
     * Tiramisu SDK-extension version, with the API-30 guard the lint analysis can
     * follow.
     *
     * [SdkExtensions.getExtensionVersion] requires API 30. [needsMulticastLock]
     * only invokes the extension probe at `sdkInt == 33`, but that call crosses a
     * function boundary and lint cannot see it, so the guard is repeated here: on
     * API 29 (the app's targetSdk) the Tiramisu extension does not exist and `0`
     * is the safe, below-threshold fallback.
     */
    private fun tiramisuExtensionVersion(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU)
        } else {
            TIRAMISU_EXTENSION_ABSENT
        }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        multicastLock = null
        if (lock.isHeld) {
            lock.release()
        }
    }

    private inner class RegistrationListenerImpl : NsdManager.RegistrationListener {
        override fun onServiceRegistered(goInfo: NsdServiceInfo) {
            machine.accept(DiscoveryEvent.ADVERTISE_REGISTERED)
        }

        override fun onRegistrationFailed(
            goInfo: NsdServiceInfo,
            goCode: Int,
        ) {
            machine.accept(
                DiscoveryEvent.ADVERTISE_FAILED,
                DiscoveryFailure.AdvertiseFailed(
                    "onRegistrationFailed code=$goCode",
                ),
            )
        }

        override fun onServiceUnregistered(goInfo: NsdServiceInfo) = Unit

        override fun onUnregistrationFailed(
            goInfo: NsdServiceInfo,
            goCode: Int,
        ) = Unit
    }

    private inner class DiscoveryListenerImpl(
        private val latch: CountDownLatch,
        private val resolved: AtomicReference<PeerAddress?>,
    ) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(goServiceType: String) = Unit

        override fun onServiceFound(goInfo: NsdServiceInfo) {
            resolve(goInfo, latch, resolved)
        }

        override fun onServiceLost(goInfo: NsdServiceInfo) = Unit

        override fun onDiscoveryStopped(goServiceType: String) = Unit

        override fun onStartDiscoveryFailed(
            goServiceType: String,
            goCode: Int,
        ) {
            latch.countDown()
        }

        override fun onStopDiscoveryFailed(
            goServiceType: String,
            goCode: Int,
        ) = Unit
    }

    private fun resolve(
        goInfo: NsdServiceInfo,
        goLatch: CountDownLatch,
        goResolved: AtomicReference<PeerAddress?>,
    ) {
        if (usesServiceInfoCallback(Build.VERSION.SDK_INT)) {
            resolveViaServiceInfoCallback(goInfo, goLatch, goResolved)
        } else {
            resolveViaDeprecatedPath(goInfo, goLatch, goResolved)
        }
    }

    private fun resolveViaDeprecatedPath(
        goInfo: NsdServiceInfo,
        goLatch: CountDownLatch,
        goResolved: AtomicReference<PeerAddress?>,
    ) {
        @Suppress("DEPRECATION")
        val listener =
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(goResolvedInfo: NsdServiceInfo) {
                    publishResolved(goResolvedInfo, goResolved, goLatch)
                }

                override fun onResolveFailed(
                    goFailedInfo: NsdServiceInfo,
                    goCode: Int,
                ) {
                    goLatch.countDown()
                }
            }
        @Suppress("DEPRECATION")
        nsd.resolveService(goInfo, listener)
    }

    /**
     * Resolves a discovered service through the `ServiceInfoCallback` API.
     *
     * `@SuppressLint("NewApi")` is the annotation lint follows across the function
     * boundary: `resolve` only reaches this branch when
     * `usesServiceInfoCallback(Build.VERSION.SDK_INT)` is true, which holds only at
     * `sdkInt >= 35` — so `ServiceInfoCallback` (API 34) and
     * `registerServiceInfoCallback` (T-ext 7) are unreachable on API 29-34 at
     * runtime. The API-29-34 `resolveService` path in [resolveViaDeprecatedPath]
     * is untouched and remains the live path on this app's targetSdk 29.
     */
    @SuppressLint("NewApi") // Guarded by usesServiceInfoCallback(): only reachable at sdkInt >= 35.
    private fun resolveViaServiceInfoCallback(
        goInfo: NsdServiceInfo,
        goLatch: CountDownLatch,
        goResolved: AtomicReference<PeerAddress?>,
    ) {
        val callback =
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(goResolvedInfo: NsdServiceInfo) {
                    publishResolved(goResolvedInfo, goResolved, goLatch)
                }

                override fun onServiceLost() {
                    goLatch.countDown()
                }

                override fun onServiceInfoCallbackRegistrationFailed(goCode: Int) {
                    goLatch.countDown()
                }

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
        nsd.registerServiceInfoCallback(goInfo, context.mainExecutor, callback)
    }

    private fun publishResolved(
        goInfo: NsdServiceInfo,
        goResolved: AtomicReference<PeerAddress?>,
        goLatch: CountDownLatch,
    ) {
        val host = goInfo.host?.hostAddress ?: return
        goResolved.compareAndSet(
            null,
            PeerAddress(host, goInfo.port, PeerAddress.SOURCE_MDNS),
        )
        goLatch.countDown()
    }

    companion object {
        /** The DNS-SD service type every ecosys hub advertises. */
        const val DEFAULT_SERVICE_TYPE: String = "_ecosys._tcp"

        const val MULTICAST_LOCK_TAG: String = "phone-manager:mdns"

        /**
         * Extension version reported below API 30, where `SdkExtensions` does not
         * exist. `0` is below every meaningful Tiramisu extension, so the
         * multicast-lock predicate can never be weakened by the fallback.
         */
        const val TIRAMISU_EXTENSION_ABSENT: Int = 0
    }
}
