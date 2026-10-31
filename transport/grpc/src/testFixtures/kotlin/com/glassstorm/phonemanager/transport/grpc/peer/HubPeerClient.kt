package com.glassstorm.phonemanager.transport.grpc.peer

import com.glassstorm.phonemanager.core.model.PeerRole
import com.google.protobuf.ByteString
import ecosys.v1.DeviceRole
import ecosys.v1.ImageFrame
import ecosys.v1.StreamFrame
import ecosys.v1.StreamOpenRequest
import ecosys.v1.StreamServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The typed view of one frame the hub emitted back to this peer.
 *
 * The wire (`ecosys.v1.*`) types stop HERE: a consumer — notably `:app`'s
 * cross-repo E2E — asserts on these plain values and never imports the frozen
 * contract (Konsist law b forbids `ecosys.v1.*` outside `:transport:grpc` /
 * `:contract`).
 */
sealed interface PeerEvent {
    /** A recognized gesture the hub routed toward the glasses session. */
    data class Gesture(
        val text: String,
        val label: String,
        val confidence: Float,
        val ptsMs: Long,
    ) : PeerEvent

    /** A recognized utterance the hub routed back to the sending (glasses) session. */
    data class Speech(
        val text: String,
        val speakerLabel: String,
        val ptsMs: Long,
    ) : PeerEvent

    /** Hub-synthesized PCM16 @ 16 kHz the hub routed toward a necklace/daemon session. */
    class TtsAudio(
        val pcm16: ByteArray,
        val ptsMs: Long,
    ) : PeerEvent

    /** The hub's reply to an enrollment request. */
    data class EnrollResponse(
        val ok: Boolean,
        val reason: String,
    ) : PeerEvent
}

/**
 * An in-process `ecosys.v1` PEER: opens the hub's UNAUTHENTICATED
 * `StreamService/OpenStream`, binds its identity with a `stream_open` frame, and
 * exposes the hub's replies as [PeerEvent]s.
 *
 * This is the reference client the cross-repo E2E drives as the glasses (and, if
 * needed, a daemon) session. It is a TEST FIXTURE, not production code: it lives
 * in `:transport:grpc`'s `testFixtures` source set precisely so it may name the
 * wire types while `:app` consumes only [PeerEvent].
 *
 * Threading: the stream is driven by one background coroutine; the public
 * `send*`/`await*` methods are blocking so a plain JUnit test can call them
 * directly. Every method is safe to call from the test thread.
 *
 * @param host the hub host (`127.0.0.1` in tests).
 * @param port the hub's bound loopback port.
 * @param deviceId the id bound by the `stream_open` bootstrap.
 * @param role the role bound by the `stream_open` bootstrap, which is what lets
 *   the hub route an image from a necklace to THIS glasses session.
 */
class HubPeerClient(
    private val host: String,
    private val port: Int,
    private val deviceId: String,
    private val role: PeerRole,
) : AutoCloseable {
    // `@PublishedApi internal` so the public inline `await` can drain it.
    @PublishedApi
    internal val events = LinkedBlockingQueue<PeerEvent>()
    private val outbound = Channel<StreamFrame>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var channel: ManagedChannel
    private var collector: Job? = null

    /**
     * Dial the hub, open the stream, and bind this peer's `{device_id, role}`.
     * Returns once the bootstrap frame has been queued; the server processes it
     * before any media that follows.
     */
    fun open() {
        // The netty-shaded builder is chosen EXPLICITLY (not via
        // `ManagedChannelBuilder.forAddress`) for the same reason the hub's
        // `HubServerAdapter` uses `NettyServerBuilder` directly: provider
        // discovery is unreliable under Robolectric's classloader, and the
        // shaded transport is the one the app already ships.
        channel = NettyChannelBuilder.forAddress(host, port).usePlaintext().build()
        val stub = StreamServiceGrpcKt.StreamServiceCoroutineStub(channel)
        collector =
            scope.launch {
                stub.openStream(outbound.receiveAsFlow()).collect { frame ->
                    frame.toPeerEvent()?.let(events::put)
                }
            }
        runBlocking { outbound.send(bootstrapFrame()) }
    }

    /** Queue one opaque PCM16 @ 16 kHz audio frame (the glasses subtitle path). */
    fun sendAudio(
        pcm16: ByteArray,
        ptsMs: Long = 0L,
    ) = send(
        StreamFrame
            .newBuilder()
            .setAudioPcm1616K(ByteString.copyFrom(pcm16))
            .build(),
    )

    /** Queue one JPEG still (the necklace gesture path). */
    fun sendImage(
        jpeg: ByteArray,
        ptsMs: Long = 0L,
        width: Int = 320,
        height: Int = 240,
    ) = send(
        StreamFrame
            .newBuilder()
            .setImage(
                ImageFrame
                    .newBuilder()
                    .setJpeg(ByteString.copyFrom(jpeg))
                    .setPtsMs(ptsMs)
                    .setWidth(width)
                    .setHeight(height),
            ).build(),
    )

    /** The next event of type [T], or `null` once [timeoutMs] elapses. */
    inline fun <reified T : PeerEvent> await(timeoutMs: Long): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            val event = events.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
            if (event is T) return event
        }
    }

    /** Every event currently buffered, draining the queue. */
    fun drain(): List<PeerEvent> {
        val drained = mutableListOf<PeerEvent>()
        events.drainTo(drained)
        return drained
    }

    private fun send(frame: StreamFrame) {
        runBlocking { outbound.send(frame) }
    }

    private fun bootstrapFrame(): StreamFrame =
        StreamFrame
            .newBuilder()
            .setStreamOpen(
                StreamOpenRequest
                    .newBuilder()
                    .setDeviceId(deviceId)
                    .setRole(role.toDeviceRole()),
            ).build()

    private fun StreamFrame.toPeerEvent(): PeerEvent? =
        when (payloadCase) {
            StreamFrame.PayloadCase.GESTURE ->
                PeerEvent.Gesture(
                    text = gesture.text,
                    label = gesture.label,
                    confidence = gesture.confidence,
                    ptsMs = gesture.ptsMs,
                )

            StreamFrame.PayloadCase.RESULT ->
                PeerEvent.Speech(
                    text = result.text,
                    speakerLabel = result.speakerLabel,
                    ptsMs = result.ptsMs,
                )

            StreamFrame.PayloadCase.TTS_AUDIO ->
                PeerEvent.TtsAudio(pcm16 = ttsAudio.pcm1616K.toByteArray(), ptsMs = ttsAudio.ptsMs)

            StreamFrame.PayloadCase.ENROLL_RESPONSE ->
                PeerEvent.EnrollResponse(ok = enrollResponse.ok, reason = enrollResponse.reason)

            // Echoed media / bootstrap frames are not results: ignore them.
            else -> null
        }

    private fun PeerRole.toDeviceRole(): DeviceRole =
        when (this) {
            PeerRole.GLASS -> DeviceRole.DEVICE_ROLE_GLASS
            PeerRole.DAEMON -> DeviceRole.DEVICE_ROLE_DAEMON
            PeerRole.UNSPECIFIED -> DeviceRole.DEVICE_ROLE_UNSPECIFIED
        }

    override fun close() {
        outbound.close()
        collector?.let { job ->
            runCatching { withTimeoutJoin(job) }
        }
        if (this::channel.isInitialized) {
            runCatching {
                channel.shutdownNow()
                channel.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        scope.cancel()
    }

    private fun withTimeoutJoin(job: Job) {
        runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { job.join() }
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MILLIS: Long = 2_000L
        const val CLOSE_TIMEOUT_SECONDS: Long = 5L
    }
}
