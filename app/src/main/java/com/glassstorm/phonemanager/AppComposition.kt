package com.glassstorm.phonemanager

import android.content.Context as GoAndroidContext
import com.glassstorm.phonemanager.adapter.network.nsd.NsdDiscoveryAdapter
import com.glassstorm.phonemanager.adapter.network.wifi.LocalOnlyHotspotAdapter
import com.glassstorm.phonemanager.adapter.repository.memory.MemoryDeviceRepository
import com.glassstorm.phonemanager.adapter.transport.grpc.HubServerAdapter
import com.glassstorm.phonemanager.domain.adapter.network.Discovery
import com.glassstorm.phonemanager.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.service.DeviceService
import com.glassstorm.phonemanager.domain.service.PairingService
import com.glassstorm.phonemanager.domain.service.StreamService
import com.glassstorm.phonemanager.service.DeviceServiceImpl
import com.glassstorm.phonemanager.service.PairingServiceImpl
import com.glassstorm.phonemanager.service.StreamServiceImpl
import com.glassstorm.phonemanager.service.security.TokenVerifier

/**
 * Composition root. The ONLY place that knows every layer at once: it binds the
 * domain ports to concrete adapter/service implementations inside a [Context].
 *
 * This vertical slice is the template later todos follow:
 *   domain port  ->  `:adapter` implementation  ->  `:service` implementation,
 * all resolved elsewhere via `FromContext<Port>(ctx)`.
 *
 * The hub's two gRPC services and its [TokenVerifier] resolve their collaborators
 * EAGERLY when a server is built, so every port they need must be registered here
 * before [HubServer.GoStart] is ever called.
 */
object AppComposition {

    /**
     * The hub's default listening port. Reachable by hotspot peers via the
     * gateway address, and the port the discovery adapter advertises.
     */
    const val GO_DEFAULT_HUB_PORT: Int = 9000

    @Volatile
    private var GoAndroidAppContext: GoAndroidContext? = null

    @Volatile
    private var GoSingleton: Context? = null

    /**
     * Record the Android application context BEFORE the registry is first built.
     *
     * The network adapters need a real `WifiManager`/`NsdManager`, which only an
     * Android Context can supply, so the hub's own [HubForegroundService] calls
     * this in `onCreate` — before anything resolves the registry.
     */
    fun GoInitAndroid(goAndroidContext: GoAndroidContext) {
        GoAndroidAppContext = goAndroidContext.applicationContext
        // A registry built before the Android Context arrived (e.g. the UI resolved
        // first) would lack the platform-backed network ports. Register them into
        // the live singleton so the hub never comes up half-wired.
        GoSingleton?.let { GoRegisterNetwork(it, GoAndroidAppContext) }
    }

    /**
     * The process-wide registry, created once.
     *
     * A foreground service and the UI must resolve the SAME hub instance, or they
     * would observe different listeners and pairing state, so the app resolves
     * through here rather than rebuilding a Context per caller.
     */
    fun GoAppContext(): Context =
        GoSingleton ?: synchronized(this) {
            GoSingleton ?: GoBuildContext(GoAndroidAppContext).also { GoSingleton = it }
        }

    /**
     * Build a registry.
     *
     * [goAndroidContext] is optional: the pure-JVM/UI slice and the unit tests
     * build a registry without it, and the platform-backed network adapters are
     * registered only when one is present (they cannot be constructed without it).
     */
    fun GoBuildContext(goAndroidContext: GoAndroidContext? = null): Context {
        val GoCtx = Context()

        // Adapter layer: bind the concrete repository under the DOMAIN port type.
        Register<DeviceRepository>(GoCtx, MemoryDeviceRepository())

        GoRegisterNetwork(GoCtx, goAndroidContext)

        // Service layer: the service resolves its collaborator from the Context.
        Register<DeviceService>(GoCtx, DeviceServiceImpl(GoCtx))

        // Pairing: ONE instance satisfies both the service surface the gRPC
        // PairingService uses and the token check the AuthInterceptor uses, so the
        // interceptor and the service can never disagree about pairing state.
        val GoPairing = PairingServiceImpl(GoCtx)
        Register<PairingService>(GoCtx, GoPairing)
        Register<TokenVerifier>(GoCtx, GoPairing)

        Register<StreamService>(GoCtx, StreamServiceImpl(GoCtx))

        // The hub listener itself. Production binds all interfaces because the
        // phone IS the hotspot and its LAN peers must dial in; tests bind loopback.
        Register<HubServer>(GoCtx, HubServerAdapter.GoForLanPeers(GoCtx))

        return GoCtx
    }

    /**
     * Register the platform-backed network ports when an Android Context exists.
     *
     * The discovery adapter's gateway fallback is left unconfigured: it is a
     * settings-owned value (T17), and the hub's own use of the port is advertising,
     * which needs no fallback.
     */
    private fun GoRegisterNetwork(GoCtx: Context, goAndroidContext: GoAndroidContext?) {
        if (goAndroidContext == null) return
        Register<HotspotController>(GoCtx, LocalOnlyHotspotAdapter(goAndroidContext))
        Register<Discovery>(GoCtx, NsdDiscoveryAdapter(goAndroidContext, GoGatewayFallback = null))
    }
}
