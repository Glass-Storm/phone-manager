package com.glassstorm.phonemanager.adapter.network.nsd

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
import com.glassstorm.phonemanager.domain.network.GoNeedsMulticastLock
import com.glassstorm.phonemanager.domain.network.GoUsesServiceInfoCallback
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
 *    filters multicast frames (see `GoNeedsMulticastLock`), released on teardown.
 *  - **Direct-IP fallback**: an mDNS miss/timeout resolves to the configured
 *    gateway address instead of throwing. This is the primary path in the
 *    manual-tether development flow, so it is a normal outcome, not an error.
 */
class NsdDiscoveryAdapter(
    private val GoContext: Context,
    private val GoGatewayFallback: PeerAddress?,
) : Discovery {

    private val GoNsd: NsdManager =
        GoContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val GoWifi: WifiManager =
        GoContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val GoMachine = DiscoveryStateMachine(GoGatewayFallback)

    private var GoRegistrationListener: NsdManager.RegistrationListener? = null

    private var GoMulticastLock: WifiManager.MulticastLock? = null

    val GoState: DiscoveryState
        get() = GoMachine.GoState

    fun GoIsAdvertising(): Boolean = GoRegistrationListener != null

    override fun GoAdvertise(name: String, port: Int) {
        if (GoRegistrationListener != null) return

        val GoInfo = NsdServiceInfo().apply {
            serviceName = name
            serviceType = GoDefaultServiceType
            this.port = port
        }
        val GoListener = GoRegistrationListenerImpl()
        GoRegistrationListener = GoListener
        GoMachine.GoAccept(DiscoveryEvent.START_ADVERTISE)

        GoAcquireMulticastLock()

        try {
            GoNsd.registerService(GoInfo, NsdManager.PROTOCOL_DNS_SD, GoListener)
        } catch (goFailure: RuntimeException) {
            GoMachine.GoAccept(
                DiscoveryEvent.ADVERTISE_FAILED,
                DiscoveryFailure.AdvertiseFailed(
                    goFailure.message ?: "registerService threw",
                ),
            )
            GoRegistrationListener = null
            GoReleaseMulticastLock()
        }
    }

    override fun GoStopAdvertise() {
        val GoListener = GoRegistrationListener ?: run {
            GoReleaseMulticastLock()
            GoMachine.GoAccept(DiscoveryEvent.STOP_REQUESTED)
            return
        }
        GoRegistrationListener = null
        try {
            GoNsd.unregisterService(GoListener)
        } catch (goFailure: RuntimeException) {
            GoRegistrationListener = GoListener
            throw goFailure
        }
        GoReleaseMulticastLock()
        GoMachine.GoAccept(DiscoveryEvent.STOP_REQUESTED)
    }

    override fun GoResolveFirst(timeoutMs: Long): PeerAddress? {
        val GoDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val GoLatch = CountDownLatch(1)
        val GoResolved = AtomicReference<PeerAddress?>(null)

        GoMachine.GoAccept(DiscoveryEvent.START_RESOLVE)

        val GoDiscoveryListener = GoDiscoveryListenerImpl(GoLatch, GoResolved)
        val GoDiscovery = try {
            GoNsd.discoverServices(GoDefaultServiceType, NsdManager.PROTOCOL_DNS_SD, GoDiscoveryListener)
            true
        } catch (goFailure: RuntimeException) {
            false
        }

        val GoRemainingMs = TimeUnit.NANOSECONDS.toMillis(GoDeadlineNanos - System.nanoTime())
        val GoAnswered = GoRemainingMs > 0 &&
            GoLatch.await(GoRemainingMs, TimeUnit.MILLISECONDS)

        if (GoDiscovery) {
            runCatching { GoNsd.stopServiceDiscovery(GoDiscoveryListener) }
        }

        if (GoAnswered) {
            val GoPeer = GoResolved.get()
            if (GoPeer != null) {
                GoMachine.GoAccept(DiscoveryEvent.MDNS_RESOLVED)
                return GoPeer
            }
        }

        return GoFallBackToGateway()
    }

    private fun GoFallBackToGateway(): PeerAddress? {
        GoMachine.GoAccept(DiscoveryEvent.RESOLVE_TIMEOUT)
        val GoSelection = GoMachine.GoSelectPeer(mdns = null, gateway = GoGatewayFallback)
        return when (GoSelection) {
            is DiscoverySelection.Selected -> GoSelection.GoPeer
            DiscoverySelection.NoPeer -> null
        }
    }

    private fun GoAcquireMulticastLock() {
        val GoNeeded = GoNeedsMulticastLock(Build.VERSION.SDK_INT) {
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU)
        }
        if (!GoNeeded) return
        val GoLock = GoWifi.createMulticastLock(GoMulticastLockTag)
        GoLock.setReferenceCounted(false)
        GoLock.acquire()
        GoMulticastLock = GoLock
    }

    private fun GoReleaseMulticastLock() {
        val GoLock = GoMulticastLock ?: return
        GoMulticastLock = null
        if (GoLock.isHeld) {
            GoLock.release()
        }
    }

    private inner class GoRegistrationListenerImpl : NsdManager.RegistrationListener {
        override fun onServiceRegistered(goInfo: NsdServiceInfo) {
            GoMachine.GoAccept(DiscoveryEvent.ADVERTISE_REGISTERED)
        }

        override fun onRegistrationFailed(goInfo: NsdServiceInfo, goCode: Int) {
            GoMachine.GoAccept(
                DiscoveryEvent.ADVERTISE_FAILED,
                DiscoveryFailure.AdvertiseFailed(
                    "onRegistrationFailed code=$goCode",
                ),
            )
        }

        override fun onServiceUnregistered(goInfo: NsdServiceInfo) = Unit

        override fun onUnregistrationFailed(goInfo: NsdServiceInfo, goCode: Int) = Unit
    }

    private inner class GoDiscoveryListenerImpl(
        private val GoLatch: CountDownLatch,
        private val GoResolved: AtomicReference<PeerAddress?>,
    ) : NsdManager.DiscoveryListener {

        override fun onDiscoveryStarted(goServiceType: String) = Unit

        override fun onServiceFound(goInfo: NsdServiceInfo) {
            GoResolve(goInfo, GoLatch, GoResolved)
        }

        override fun onServiceLost(goInfo: NsdServiceInfo) = Unit

        override fun onDiscoveryStopped(goServiceType: String) = Unit

        override fun onStartDiscoveryFailed(goServiceType: String, goCode: Int) {
            GoLatch.countDown()
        }

        override fun onStopDiscoveryFailed(goServiceType: String, goCode: Int) = Unit
    }

    private fun GoResolve(
        goInfo: NsdServiceInfo,
        goLatch: CountDownLatch,
        goResolved: AtomicReference<PeerAddress?>,
    ) {
        if (GoUsesServiceInfoCallback(Build.VERSION.SDK_INT)) {
            GoResolveViaServiceInfoCallback(goInfo, goLatch, goResolved)
        } else {
            GoResolveViaDeprecatedPath(goInfo, goLatch, goResolved)
        }
    }

    private fun GoResolveViaDeprecatedPath(
        goInfo: NsdServiceInfo,
        goLatch: CountDownLatch,
        goResolved: AtomicReference<PeerAddress?>,
    ) {
        @Suppress("DEPRECATION")
        val GoListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(goResolvedInfo: NsdServiceInfo) {
                GoPublishResolved(goResolvedInfo, goResolved, goLatch)
            }

            override fun onResolveFailed(goFailedInfo: NsdServiceInfo, goCode: Int) {
                goLatch.countDown()
            }
        }
        @Suppress("DEPRECATION")
        GoNsd.resolveService(goInfo, GoListener)
    }

    private fun GoResolveViaServiceInfoCallback(
        goInfo: NsdServiceInfo,
        goLatch: CountDownLatch,
        goResolved: AtomicReference<PeerAddress?>,
    ) {
        val GoCallback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(goResolvedInfo: NsdServiceInfo) {
                GoPublishResolved(goResolvedInfo, goResolved, goLatch)
            }

            override fun onServiceLost() {
                goLatch.countDown()
            }

            override fun onServiceInfoCallbackRegistrationFailed(goCode: Int) {
                goLatch.countDown()
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        GoNsd.registerServiceInfoCallback(goInfo, GoContext.mainExecutor, GoCallback)
    }

    private fun GoPublishResolved(
        goInfo: NsdServiceInfo,
        goResolved: AtomicReference<PeerAddress?>,
        goLatch: CountDownLatch,
    ) {
        val GoHost = goInfo.host?.hostAddress ?: return
        goResolved.compareAndSet(
            null,
            PeerAddress(GoHost, goInfo.port, PeerAddress.GoSourceMdns),
        )
        goLatch.countDown()
    }

    companion object {
        /** The DNS-SD service type every ecosys hub advertises. */
        const val GoDefaultServiceType: String = "_ecosys._tcp"

        const val GoMulticastLockTag: String = "phone-manager:mdns"
    }
}
