package com.glassstorm.phonemanager.adapter.transport.grpc

import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContext
import com.glassstorm.phonemanager.service.GrpcHubServer
import com.glassstorm.phonemanager.service.PairingGrpcService
import com.glassstorm.phonemanager.service.StreamGrpcService
import com.glassstorm.phonemanager.service.security.AuthInterceptor
import com.glassstorm.phonemanager.service.security.TokenVerifier
import io.grpc.ServerBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.net.InetSocketAddress

/**
 * Android [HubServer] adapter: the real gRPC listener, hosted inside the app.
 *
 * It composes — it does not reimplement — the lifecycle: [GrpcHubServer] from
 * `:service` owns start/stop, the bounded drain, and the actual bound-port
 * reporting, and only the TRANSPORT is supplied here, as a netty-shaded NIO
 * builder. That is exactly the builder the T6 spike proved GO with
 * (issues.md R1: `io.grpc:grpc-netty-shaded:1.84.0`, plaintext, IPv4 explicit).
 *
 * ## Bind-address decision (documented, deliberate)
 *
 * Two distinct binds exist and they are not interchangeable:
 *
 *  * **Loopback (`127.0.0.1`)** — the default and the only address used by tests.
 *    An IPv4 literal is passed EXPLICITLY because `InetSocketAddress(port)` and
 *    `ServerBuilder.forPort()` resolve to the IPv6 wildcard on some runtimes, and
 *    an IPv6-only listener is unreachable to the peers this project targets.
 *  * **All interfaces (`0.0.0.0`)** — used ONLY by [GoForLanPeers], the
 *    production start. It is required because the phone IS the hotspot: the
 *    glasses and the Ubuntu daemon connect to the phone across the LAN, so the
 *    hub must accept off-device connections. It is never selected in tests.
 *
 * No TLS: the hub is plaintext on a local-only LAN by design (the glasses never
 * reach the internet), and grpc-java's TLS path is broken on Android anyway. See
 * the frozen protocol contract for the documented upgrade path.
 *
 * @param GoCtx the composition root's registry, holding every domain port the two
 *        gRPC services and the [AuthInterceptor] resolve at [GoStart] time.
 * @param GoBindAddress the IPv4 bind address; see the bind-address decision above.
 */
class HubServerAdapter(
    private val GoCtx: Context,
    private val GoBindAddress: String = GO_LOOPBACK_ADDRESS,
) : HubServer {

    private val GoHub: GrpcHubServer = GrpcHubServer { GoNewBuilder(it) }

    override fun GoStart(port: Int) = GoHub.GoStart(port)

    override fun GoStop() = GoHub.GoStop()

    override fun GoIsRunning(): Boolean = GoHub.GoIsRunning()

    override fun GoBoundPort(): Int = GoHub.GoBoundPort()

    /** The interface this instance binds; exposed so the UI/logs can report it. */
    fun GoBindAddress(): String = GoBindAddress

    /**
     * Build a fresh, unstarted server for [port]. Invoked on every [GoStart], so
     * all collaborators are resolved lazily — a Context that is still being
     * populated when the adapter object is constructed is fine.
     */
    private fun GoNewBuilder(port: Int): ServerBuilder<*> =
        NettyServerBuilder.forAddress(InetSocketAddress(GoBindAddress, port))
            .addService(PairingGrpcService(GoCtx))
            .addService(StreamGrpcService(GoCtx))
            .intercept(AuthInterceptor(GoTokenVerifier()))

    /**
     * The [AuthInterceptor] collaborator. Resolved by its own type so no
     * downcast is needed: the composition root registers the pairing
     * implementation under BOTH [com.glassstorm.phonemanager.domain.service.PairingService]
     * and [TokenVerifier].
     */
    private fun GoTokenVerifier(): TokenVerifier = FromContext<TokenVerifier>(GoCtx)

    companion object {
        /** IPv4 loopback — tests only. */
        const val GO_LOOPBACK_ADDRESS: String = "127.0.0.1"

        /**
         * The IPv4 wildcard. Production only: the phone is the hotspot, and its
         * LAN peers (glasses, Ubuntu daemon) must be able to dial in.
         */
        const val GO_ALL_INTERFACES_ADDRESS: String = "0.0.0.0"

        /** The adapter the app uses: reachable by hotspot peers on the LAN. */
        fun GoForLanPeers(GoCtx: Context): HubServerAdapter =
            HubServerAdapter(GoCtx, GO_ALL_INTERFACES_ADDRESS)
    }
}
