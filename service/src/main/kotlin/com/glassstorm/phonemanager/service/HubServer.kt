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
 * @param newBuilder builds an unstarted gRPC server for the two hub services
 *        plus the auth interceptor, already bound to [start]'s port. The port
 *        is a factory argument because `ServerBuilder.forPort` is static and the
 *        concrete builder differs per transport (netty vs in-process).
 */
class GrpcHubServer(
    private val newBuilder: (port: Int) -> ServerBuilder<*>,
) : HubServer {
    private var server: Server? = null

    override fun start(port: Int) {
        if (isRunning()) return
        val built = newBuilder(port).build().start()
        server = built
    }

    override fun stop() {
        val running = server ?: return
        server = null
        running.shutdown()
        // Bounded drain so a stuck handler cannot hang the hub's teardown.
        if (!running.awaitTermination(5, TimeUnit.SECONDS)) {
            running.shutdownNow()
            running.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    override fun isRunning(): Boolean = server?.isShutdown == false

    override fun boundPort(): Int = server?.port ?: 0
}
