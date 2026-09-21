package com.glassstorm.phonemanager.adapter.transport.grpc

import com.glassstorm.phonemanager.adapter.repository.memory.MemoryDeviceRepository
import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.core.domain.context.Context
import com.glassstorm.phonemanager.core.domain.context.register
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.service.PairingServiceImpl
import com.glassstorm.phonemanager.service.StreamServiceImpl
import com.glassstorm.phonemanager.service.security.TokenVerifier

/**
 * The smallest Context that can host a real hub: the two gRPC services resolve
 * their collaborators eagerly in their constructors, so all five ports must be
 * registered before the adapter is built.
 *
 * `PairingServiceImpl` is the real service (it is also the `TokenVerifier` the
 * `AuthInterceptor` needs); only the leaf ports are fakes, exactly as an Android
 * composition would swap in real adapters.
 */
fun hubContext(): Context {
    val ctx = Context()
    // ONE pairing instance, registered twice: the service surface and the
    // interceptor's token verifier must observe the same pairing state.
    val pairing = PairingServiceImpl(ctx)
    register<DeviceRepository>(ctx, MemoryDeviceRepository())
    register<PairingService>(ctx, pairing)
    register<TokenVerifier>(ctx, pairing)
    register<StreamService>(ctx, StreamServiceImpl(ctx))
    register<SttPort>(ctx, NoopSttPort())
    register<FrameSink>(ctx, NoopFrameSink())
    return ctx
}

private class NoopSttPort : SttPort {
    override suspend fun transcribe(
        sessionId: String,
        audioPcm16: ByteArray,
        sampleRateHz: Int,
    ): String? = null

    override suspend fun close(sessionId: String) = Unit
}

private class NoopFrameSink : FrameSink {
    override fun acceptVideo(
        sessionId: String,
        h264Nal: ByteArray,
    ) = Unit
}
