package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import io.grpc.Server
import io.grpc.ServerBuilder
import java.util.concurrent.TimeUnit

/**
 * A [HubServer] over any `io.grpc.ServerBuilder`.
 *
 * The transport is INJECTED as a builder factory rather than constructed here, so
 * `:service` stays Android-free and transport-free: `:app` supplies a
 * netty-shaded builder (T7), and tests supply an in-process builder. This file
 * depends only on `grpc-api`, which is already on the compile classpath.
 *
 * @param GoNewBuilder builds an unstarted gRPC server for the two hub services
 *        plus the auth interceptor, already bound to [GoStart]'s port. The port
 *        is a factory argument because `ServerBuilder.forPort` is static and the
 *        concrete builder differs per transport (netty vs in-process).
 */
class GrpcHubServer(
    private val GoNewBuilder: (port: Int) -> ServerBuilder<*>,
) : HubServer {
    private var GoServer: Server? = null

    override fun GoStart(port: Int) {
        if (GoIsRunning()) return
        val GoBuilt = GoNewBuilder(port).build().start()
        GoServer = GoBuilt
    }

    override fun GoStop() {
        val GoRunning = GoServer ?: return
        GoServer = null
        GoRunning.shutdown()
        // Bounded drain so a stuck handler cannot hang the hub's teardown.
        if (!GoRunning.awaitTermination(5, TimeUnit.SECONDS)) {
            GoRunning.shutdownNow()
            GoRunning.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    override fun GoIsRunning(): Boolean = GoServer?.isShutdown == false

    override fun GoBoundPort(): Int = GoServer?.port ?: 0
}
