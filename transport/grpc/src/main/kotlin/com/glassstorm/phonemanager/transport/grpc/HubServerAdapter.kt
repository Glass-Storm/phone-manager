package com.glassstorm.phonemanager.transport.grpc

import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.context.Context
import com.glassstorm.phonemanager.core.domain.context.fromContext
import com.glassstorm.phonemanager.core.domain.security.TokenVerifier
import com.glassstorm.phonemanager.transport.grpc.security.AuthInterceptor
import io.grpc.ServerBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.net.InetSocketAddress

/**
 * Android [HubServer] adapter: the real gRPC listener, hosted inside the app.
 *
 * It composes — it does not reimplement — the lifecycle: [GrpcHubServer] owns
 * start/stop, the bounded drain, and the actual bound-port reporting, and only the
 * TRANSPORT is supplied here, as a netty-shaded NIO builder. That is exactly the
 * builder the T6 spike proved GO with (issues.md R1: `io.grpc:grpc-netty-shaded`,
 * plaintext, IPv4 explicit).
 *
 * ## Collaborators are CONSTRUCTOR dependencies
 *
 * The two gRPC services and the [AuthInterceptor] arrive ready-built, so this
 * adapter NEVER reads the registry. Dagger supplies them through `TransportModule`,
 * which builds the production bind with [ALL_INTERFACES_ADDRESS]; the
 * registry-compat constructor keeps the [LOOPBACK_ADDRESS] default the tests rely
 * on and exists only until T16 deletes the `Context` registry.
 *
 * ## Bind-address decision (documented, deliberate)
 *
 * Two distinct binds exist and they are not interchangeable:
 *
 *  * **Loopback (`127.0.0.1`)** — the default and the only address used by tests.
 *    An IPv4 literal is passed EXPLICITLY because `InetSocketAddress(port)` and
 *    `ServerBuilder.forPort()` resolve to the IPv6 wildcard on some runtimes, and
 *    an IPv6-only listener is unreachable to the peers this project targets.
 *  * **All interfaces (`0.0.0.0`)** — the production bind. It is required because
 *    the phone IS the hotspot: the glasses and the Ubuntu daemon connect to the
 *    phone across the LAN, so the hub must accept off-device connections. It is
 *    never selected in tests.
 *
 * No TLS: the hub is plaintext on a local-only LAN by design (the glasses never
 * reach the internet), and grpc-java's TLS path is broken on Android anyway. See
 * the frozen protocol contract for the documented upgrade path.
 */
class HubServerAdapter(
    private val pairingService: PairingGrpcService,
    private val streamService: StreamGrpcService,
    private val authInterceptor: AuthInterceptor,
    private val bindAddress: String,
) : HubServer {
    /** Registry-compat constructor; T16 removes it with the registry. */
    constructor(
        ctx: Context,
        bindAddress: String = LOOPBACK_ADDRESS,
    ) : this(
        PairingGrpcService(ctx),
        StreamGrpcService(ctx),
        AuthInterceptor(fromContext<TokenVerifier>(ctx)),
        bindAddress,
    )

    private val hub: GrpcHubServer = GrpcHubServer { newBuilder(it) }

    override fun start(port: Int) = hub.start(port)

    override fun stop() = hub.stop()

    override fun isRunning(): Boolean = hub.isRunning()

    override fun boundPort(): Int = hub.boundPort()

    /** The interface this instance binds; exposed so the UI/logs can report it. */
    fun bindAddress(): String = bindAddress

    /** Build a fresh, unstarted server for [port]. Invoked on every [start]. */
    private fun newBuilder(port: Int): ServerBuilder<*> =
        NettyServerBuilder
            .forAddress(InetSocketAddress(bindAddress, port))
            .addService(pairingService)
            .addService(streamService)
            .intercept(authInterceptor)

    companion object {
        /** IPv4 loopback — tests only. */
        const val LOOPBACK_ADDRESS: String = "127.0.0.1"

        /**
         * The IPv4 wildcard. Production only: the phone is the hotspot, and its
         * LAN peers (glasses, Ubuntu daemon) must be able to dial in.
         */
        const val ALL_INTERFACES_ADDRESS: String = "0.0.0.0"

        /** Registry-compat factory the app composition uses; T16 removes it. */
        fun forLanPeers(ctx: Context): HubServerAdapter = HubServerAdapter(ctx, ALL_INTERFACES_ADDRESS)
    }
}
