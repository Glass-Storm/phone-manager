package com.glassstorm.phonemanager.transport.grpc

import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.service.StreamService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Dagger bindings for the gRPC transport layer.
 *
 * Both bindings are `@Provides`, because each target is a concrete class
 * constructed from another graph node (the streaming service takes its use-case
 * port, the hub takes the service) and the providers make the production choices
 * explicit. Pairing (`Pair`/`Heartbeat`) and the bearer-token interceptor were
 * removed from the runtime, so the hub serves exactly ONE unauthenticated RPC.
 *
 * ## Production bind address
 *
 * The phone IS the hotspot, so its LAN peers (glasses, Ubuntu daemon) dial in from
 * off-device; the wildcard is therefore the production bind and the app graph
 * always selects it. Tests never build this component — they bind loopback
 * explicitly through [HubServerAdapter]'s constructor — so the wildcard can never
 * leak into a test.
 *
 * Every binding is `@Singleton` to mirror the registry: the foreground service and
 * the UI must observe the SAME listener and stream state, so re-resolving a port
 * must not build a second instance.
 */
@Module
object TransportModule {
    @Provides
    @Singleton
    fun provideStreamGrpcService(stream: StreamService): StreamGrpcService = StreamGrpcService(stream)

    /** The hub listener, bound on all interfaces so hotspot peers can reach it. */
    @Provides
    @Singleton
    fun provideHubServer(streamService: StreamGrpcService): HubServer =
        HubServerAdapter(
            streamService = streamService,
            bindAddress = HubServerAdapter.ALL_INTERFACES_ADDRESS,
        )
}
