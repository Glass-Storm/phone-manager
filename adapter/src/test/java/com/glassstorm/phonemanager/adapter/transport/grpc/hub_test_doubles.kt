package com.glassstorm.phonemanager.adapter.transport.grpc

import com.glassstorm.phonemanager.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.service.PairingService
import com.glassstorm.phonemanager.domain.service.StreamService
import com.glassstorm.phonemanager.adapter.repository.memory.MemoryDeviceRepository
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
fun GoHubContext(): Context {
    val GoCtx = Context()
    // ONE pairing instance, registered twice: the service surface and the
    // interceptor's token verifier must observe the same pairing state.
    val GoPairing = PairingServiceImpl(GoCtx)
    Register<DeviceRepository>(GoCtx, MemoryDeviceRepository())
    Register<PairingService>(GoCtx, GoPairing)
    Register<TokenVerifier>(GoCtx, GoPairing)
    Register<StreamService>(GoCtx, StreamServiceImpl(GoCtx))
    Register<SttPort>(GoCtx, GoNoopSttPort())
    Register<FrameSink>(GoCtx, GoNoopFrameSink())
    return GoCtx
}

private class GoNoopSttPort : SttPort {
    override suspend fun GoTranscribe(sessionId: String, audioPcm16: ByteArray, sampleRateHz: Int): String? = null

    override suspend fun GoClose(sessionId: String) = Unit
}

private class GoNoopFrameSink : FrameSink {
    override fun GoAcceptVideo(sessionId: String, h264Nal: ByteArray) = Unit
}
