package com.glassstorm.phonemanager.transport.grpc

import com.glassstorm.phonemanager.core.domain.adapter.enrollment.EnrollmentStore
import com.glassstorm.phonemanager.core.domain.adapter.gesture.GesturePort
import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.speech.SpeechPort
import com.glassstorm.phonemanager.core.domain.adapter.speech.TtsPort
import com.glassstorm.phonemanager.core.domain.security.TokenVerifier
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.glassstorm.phonemanager.core.model.EnrolledSpeaker
import com.glassstorm.phonemanager.core.model.GestureOutcome
import com.glassstorm.phonemanager.core.model.SpeechUtterance
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
    val speech: FakeSpeechPort,
    val sink: FakeFrameSink,
    val pairing: PairingServiceImpl,
    val stream: StreamService,
) {
    val tokenVerifier: TokenVerifier get() = pairing
}

fun hubParts(): HubParts {
    val repo = FakeDeviceRepository()
    val speech = FakeSpeechPort()
    val sink = FakeFrameSink()
    val pairing = PairingServiceImpl.withClock(repo, clock = { System.currentTimeMillis() })
    return HubParts(
        repo = repo,
        speech = speech,
        sink = sink,
        pairing = pairing,
        stream = relayStream(speech = speech, sink = sink),
    )
}

/** Compose a [StreamServiceImpl] over the given leaf ports (defaults: inert fakes). */
fun relayStream(
    speech: SpeechPort = FakeSpeechPort(),
    gesture: GesturePort = FakeGesturePort(),
    tts: TtsPort = FakeTtsPort(),
    enrollment: EnrollmentStore = FakeEnrollmentStore(),
    sink: FrameSink = FakeFrameSink(),
): StreamService = StreamServiceImpl(speech, gesture, tts, enrollment, sink)

private class NoopSpeechPort : SpeechPort {
    override suspend fun transcribe(
        sessionId: String,
        pcm16: ByteArray,
    ): SpeechUtterance? = null

    override suspend fun close(sessionId: String) = Unit
}

private class NoopGesturePort : GesturePort {
    override suspend fun recognize(jpeg: ByteArray): GestureOutcome? = null
}

private class NoopTtsPort : TtsPort {
    override suspend fun synthesize(text: String): ByteArray = ByteArray(0)
}

private class NoopEnrollmentStore : EnrollmentStore {
    override suspend fun enroll(
        name: String,
        pcm16: ByteArray,
    ): EnrollOutcome = EnrollOutcome.accepted

    override suspend fun names(): List<String> = emptyList()

    override suspend fun enrollments(): List<EnrolledSpeaker> = emptyList()
}

private class NoopFrameSink : FrameSink {
    override fun acceptVideo(
        sessionId: String,
        h264Nal: ByteArray,
    ) = Unit
}

/** A paired service surface with inert media ports; used where media never flows. */
fun inertStreamService(): StreamService =
    StreamServiceImpl(NoopSpeechPort(), NoopGesturePort(), NoopTtsPort(), NoopEnrollmentStore(), NoopFrameSink())
