package com.glassstorm.phonemanager.transport.grpc

import com.google.protobuf.ByteString
import ecosys.v1.DeviceRole
import ecosys.v1.ImageFrame
import ecosys.v1.StreamFrame
import ecosys.v1.StreamKind
import ecosys.v1.StreamOpenRequest
import ecosys.v1.StreamResult
import ecosys.v1.StreamServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress

/** UI link packets keep generated protocol types inside the transport module. */
data class ConversationPacket(
    val text: String? = null,
    val subtitle: String? = null,
    val pcm16: ByteArray? = null,
    val jpeg: ByteArray? = null,
    val ready: Boolean = false,
)

/** Dedicated same-APK conversation endpoint using the existing frozen OpenStream RPC.
 * It does not register PairingService or modify the ecosystem hub's relay semantics.
 */
class ConversationHost {
    private val peers = java.util.concurrent.ConcurrentHashMap<String, Channel<ConversationPacket>>()
    private val count = MutableStateFlow(0)
    val peerCount = count.asStateFlow()
    private var server: Server? = null
    val port: Int get() = checkNotNull(server).port

    fun start(
        port: Int,
        onPacket: suspend (String, ConversationPacket) -> Unit,
        onClosed: suspend (String) -> Unit,
    ) {
        check(server == null) { "Server already running" }
        server =
            NettyServerBuilder
                .forAddress(InetSocketAddress("0.0.0.0", port))
                .addService(
                    object : StreamServiceGrpcKt.StreamServiceCoroutineImplBase() {
                        override fun openStream(requests: Flow<StreamFrame>): Flow<StreamFrame> =
                            channelFlow {
                                val id =
                                    java.util.UUID
                                        .randomUUID()
                                        .toString()
                                val outbound = Channel<ConversationPacket>(64)
                                try {
                                    coroutineScope {
                                        var opened = false
                                        requests.collect { frame ->
                                            if (!opened) {
                                                require(frame.hasStreamOpen()) { "First frame must be stream_open" }
                                                opened = true
                                                peers[id] = outbound
                                                count.value = peers.size
                                                send(toFrame(ConversationPacket(ready = true)))
                                                launch { for (packet in outbound) send(toFrame(packet)) }
                                            } else {
                                                onPacket(id, fromFrame(frame))
                                            }
                                        }
                                        outbound.close()
                                    }
                                } finally {
                                    peers.remove(id)
                                    count.value = peers.size
                                    outbound.close()
                                    onClosed(id)
                                }
                            }
                    },
                ).build()
                .start()
    }

    fun send(packet: ConversationPacket): Boolean {
        if (peers.isEmpty()) return false
        var delivered = true
        peers.values.forEach { if (!it.trySend(packet).isSuccess) delivered = false }
        return delivered
    }

    fun stop() {
        peers.values.forEach { it.close() }
        peers.clear()
        count.value = 0
        server?.shutdownNow()
        server = null
    }
}

class ConversationClient(
    host: String,
    port: Int,
) {
    private val channel: ManagedChannel = NettyChannelBuilder.forAddress(host, port).usePlaintext().build()
    private val outbound = Channel<ConversationPacket>(64)

    fun events(): Flow<ConversationPacket> {
        val requests =
            flow {
                emit(
                    StreamFrame
                        .newBuilder()
                        .setStreamOpen(
                            StreamOpenRequest
                                .newBuilder()
                                .setDeviceId(
                                    "inmo-${java.util.UUID.randomUUID()}",
                                ).setRole(DeviceRole.DEVICE_ROLE_GLASS)
                                .setKind(StreamKind.STREAM_KIND_AUDIO),
                        ).build(),
                )
                outbound.receiveAsFlow().collect { emit(toFrame(it)) }
            }
        return StreamServiceGrpcKt.StreamServiceCoroutineStub(channel).openStream(requests).map(::fromFrame)
    }

    suspend fun send(packet: ConversationPacket) {
        outbound.send(packet)
    }

    fun close() {
        outbound.close()
        channel.shutdownNow()
    }
}

private fun toFrame(packet: ConversationPacket): StreamFrame =
    StreamFrame
        .newBuilder()
        .apply {
            when {
                packet.ready -> setStreamOpen(StreamOpenRequest.newBuilder().setDeviceId("phone-hub"))
                packet.text != null -> transcript = packet.text
                packet.subtitle != null -> setResult(StreamResult.newBuilder().setText(packet.subtitle))
                packet.pcm16 != null -> audioPcm1616K = ByteString.copyFrom(packet.pcm16)
                packet.jpeg != null -> setImage(ImageFrame.newBuilder().setJpeg(ByteString.copyFrom(packet.jpeg)))
            }
        }.build()

private fun fromFrame(frame: StreamFrame): ConversationPacket =
    when (frame.payloadCase) {
        StreamFrame.PayloadCase.STREAM_OPEN -> ConversationPacket(ready = true)
        StreamFrame.PayloadCase.TRANSCRIPT -> ConversationPacket(text = frame.transcript)
        StreamFrame.PayloadCase.RESULT -> ConversationPacket(subtitle = frame.result.text)
        StreamFrame.PayloadCase.AUDIO_PCM16_16K -> ConversationPacket(pcm16 = frame.audioPcm1616K.toByteArray())
        StreamFrame.PayloadCase.IMAGE -> ConversationPacket(jpeg = frame.image.jpeg.toByteArray())
        else -> ConversationPacket()
    }
