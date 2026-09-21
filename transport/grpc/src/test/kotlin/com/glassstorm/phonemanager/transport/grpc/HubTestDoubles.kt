package com.glassstorm.phonemanager.transport.grpc

import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.core.domain.security.TokenVerifier
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.service.PairingServiceImpl
import com.glassstorm.phonemanager.core.service.StreamServiceImpl

/**
 * The smallest hand-wired set of collaborators that can host a real hub, with no
 * dependency registry: the two gRPC services and the interceptor take their ports
 * by CONSTRUCTOR, so the test composes the graph directly.
 *
 * `PairingServiceImpl` is the real service (it is also the [TokenVerifier] the
 * `AuthInterceptor` needs) and is built once, so the service surface and the
 * verifier observe the SAME pairing state. Only the leaf ports are fakes, exactly
 * as an Android composition would swap in real adapters.
 */
class HubParts(
    val repo: FakeDeviceRepository,
    val stt: FakeSttPort,
    val sink: FakeFrameSink,
    val pairing: PairingServiceImpl,
    val stream: StreamService,
) {
    val tokenVerifier: TokenVerifier get() = pairing
}

fun hubParts(): HubParts {
    val repo = FakeDeviceRepository()
    val stt = FakeSttPort()
    val sink = FakeFrameSink()
    val pairing = PairingServiceImpl(repo)
    return HubParts(
        repo = repo,
        stt = stt,
        sink = sink,
        pairing = pairing,
        stream = StreamServiceImpl(stt, sink),
    )
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

/** A paired service surface with inert media ports; used where media never flows. */
fun inertStreamService(): StreamService = StreamServiceImpl(NoopSttPort(), NoopFrameSink())
