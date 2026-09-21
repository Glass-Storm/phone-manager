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
 *
 * ## Test-component convention (deliberate, do not reinvent)
 *
 * Test integration sites in this module are HAND-WIRED: each suite constructs
 * `PairingServiceImpl`/`StreamServiceImpl` and the gRPC services by constructor,
 * or reuses a helper from this file ([hubParts] today; [inertStreamService] for a
 * site where media never flows). There is intentionally NO test
 * `@Component`/`@Subcomponent`.
 *
 * Why hand-wiring wins: it is simpler (no second Dagger graph to keep in sync),
 * it makes the EXACT collaborators visible at the call site (which is the point of
 * a test), and it avoids the trap the production graph cannot solve anyway —
 * Dagger forbids overriding a binding per-instance, so a test that needs a
 * different `bindAddress` or a recording `FrameSink` must compose at CONSTRUCTION
 * time regardless. A test component would only move that composition behind a
 * generated factory without removing it.
 *
 * The production graph (`AppComponent`) is exercised where its own wiring is the
 * thing under test: `:app`'s `di/AppComponentTest` and `di/AppComponentWiringTest`.
 * Test suites there reuse the production component for the REAL ports and override
 * only the listener/sink by composition (see `:app` `FullE2eTest`).
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
