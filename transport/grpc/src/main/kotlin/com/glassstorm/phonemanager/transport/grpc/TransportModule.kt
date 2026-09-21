package com.glassstorm.phonemanager.transport.grpc

import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.security.TokenVerifier
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.transport.grpc.security.AuthInterceptor
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Dagger bindings for the gRPC transport layer.
 *
 * All four bindings are `@Provides`, because each target is a concrete class
 * constructed from OTHER graph nodes (the services take their use-case port, the
 * interceptor takes [TokenVerifier], the hub takes all three) and the providers
 * make the production choices explicit.
 *
 * ## Production bind address
 *
 * The phone IS the hotspot, so its LAN peers (glasses, Ubuntu daemon) dial in from
 * off-device; the wildcard is therefore the production bind and the app graph
 * always selects it. Tests never build this component — they bind loopback
 * explicitly through [HubServerAdapter]'s registry-compat constructor — so the
 * wildcard can never leak into a test.
 *
 * Every binding is `@Singleton` to mirror the registry: the foreground service and
 * the UI must observe the SAME listener and pairing/stream state, so re-resolving
 * a port must not build a second instance. Because the [PairingService] behind
 * [providePairingGrpcService] is the `@Singleton` `ServiceModule` also binds to
 * [TokenVerifier], the interceptor verifies against the very authority `Pair`
 * mints into.
 */
@Module
object TransportModule {
    @Provides
    @Singleton
    fun providePairingGrpcService(pairing: PairingService): PairingGrpcService = PairingGrpcService(pairing)

    @Provides
    @Singleton
    fun provideStreamGrpcService(stream: StreamService): StreamGrpcService = StreamGrpcService(stream)

    @Provides
    @Singleton
    fun provideAuthInterceptor(tokenVerifier: TokenVerifier): AuthInterceptor = AuthInterceptor(tokenVerifier)

    /** The hub listener, bound on all interfaces so hotspot peers can reach it. */
    @Provides
    @Singleton
    fun provideHubServer(
        pairingService: PairingGrpcService,
        streamService: StreamGrpcService,
        authInterceptor: AuthInterceptor,
    ): HubServer =
        HubServerAdapter(
            pairingService = pairingService,
            streamService = streamService,
            authInterceptor = authInterceptor,
            bindAddress = HubServerAdapter.ALL_INTERFACES_ADDRESS,
        )
}
