package com.glassstorm.phonemanager

import com.glassstorm.phonemanager.adapter.repository.memory.MemoryDeviceRepository
import com.glassstorm.phonemanager.adapter.transport.grpc.HubServerAdapter
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

    /**
     * The process-wide registry, created once.
     *
     * A foreground service and the UI must resolve the SAME hub instance, or they
     * would observe different listeners and pairing state, so the app resolves
     * through here rather than rebuilding a Context per caller.
     */
    private val GoSingletonContext: Context by lazy { GoBuildContext() }

    /** The process-wide composition registry. */
    fun GoAppContext(): Context = GoSingletonContext

    fun GoBuildContext(): Context {
        val GoCtx = Context()

        // Adapter layer: bind the concrete repository under the DOMAIN port type.
        Register<DeviceRepository>(GoCtx, MemoryDeviceRepository())

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
}
