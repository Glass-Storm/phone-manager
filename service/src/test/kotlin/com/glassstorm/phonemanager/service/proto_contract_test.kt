package com.glassstorm.phonemanager.service

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.GeneratedMessageLite
import ecosys.v1.DeviceRole
import ecosys.v1.PairRequest
import ecosys.v1.PairingServiceGrpc
import ecosys.v1.StreamFrame
import ecosys.v1.StreamKind
import ecosys.v1.StreamResult
import ecosys.v1.StreamServiceGrpc
import io.grpc.MethodDescriptor
import org.junit.Test

/**
 * Proves the `ecosys.v1` codegen actually ran and is LITE-compatible — no device
 * required. Everything asserted here is generated, never hand-written.
 */
class ProtoContractTest {

    @Test
    fun `StreamFrame result payload round trips through serialization`() {
        // Given a frame carrying a diarized result
        val GoResult = StreamResult.newBuilder()
            .setText("hello world")
            .setSpeakerLabel("spk-1")
            .setPtsMs(42L)
            .build()
        val GoFrame = StreamFrame.newBuilder().setResult(GoResult).build()

        // When it is serialized and parsed back
        val GoParsed = StreamFrame.parseFrom(GoFrame.toByteArray())

        // Then the oneof variant and every field survive the round trip
        assertThat(GoParsed.getPayloadCase()).isEqualTo(StreamFrame.PayloadCase.RESULT)
        assertThat(GoParsed.getResult().getText()).isEqualTo("hello world")
        assertThat(GoParsed.getResult().getSpeakerLabel()).isEqualTo("spk-1")
        assertThat(GoParsed.getResult().getPtsMs()).isEqualTo(42L)
    }

    @Test
    fun `StreamFrame transcript payload round trips through serialization`() {
        // Given a frame carrying a transcript string
        val GoFrame = StreamFrame.newBuilder().setTranscript("partial transcript").build()

        // When it is serialized and parsed back
        val GoParsed = StreamFrame.parseFrom(GoFrame.toByteArray())

        // Then the transcript variant is preserved
        assertThat(GoParsed.getPayloadCase()).isEqualTo(StreamFrame.PayloadCase.TRANSCRIPT)
        assertThat(GoParsed.getTranscript()).isEqualTo("partial transcript")
    }

    @Test
    fun `StreamFrame carries an opaque audio payload accessor`() {
        // Given the generated class
        val GoMethodNames = StreamFrame::class.java.methods.map { it.name.lowercase() }

        // When/Then an audio PCM16 accessor was generated (name derived by protoc;
        // matched loosely because the field mixes digits and underscores)
        assertThat(GoMethodNames.any { it.contains("audiopcm") }).isTrue()
        assertThat(StreamFrame.getDefaultInstance().getPayloadCase())
            .isEqualTo(StreamFrame.PayloadCase.PAYLOAD_NOT_SET)
    }

    @Test
    fun `PairRequest round trips with its role enum`() {
        // Given a pairing request from a glasses peer
        val GoRequest = PairRequest.newBuilder()
            .setPin("123456")
            .setDeviceName("glass-1")
            .setRole(DeviceRole.DEVICE_ROLE_GLASS)
            .build()

        // When serialized and parsed back
        val GoParsed = PairRequest.parseFrom(GoRequest.toByteArray())

        // Then the fields and the enum survive
        assertThat(GoParsed.getPin()).isEqualTo("123456")
        assertThat(GoParsed.getDeviceName()).isEqualTo("glass-1")
        assertThat(GoParsed.getRole()).isEqualTo(DeviceRole.DEVICE_ROLE_GLASS)
    }

    @Test
    fun `generated types are lite not full protobuf`() {
        // Given the generated lite runtime class
        // When/Then StreamFrame is a GeneratedMessageLite subtype (full protobuf
        // would extend GeneratedMessage and would not be on this classpath at all)
        assertThat(GeneratedMessageLite::class.java.isAssignableFrom(StreamFrame::class.java)).isTrue()
    }

    @Test
    fun `generated grpc service descriptors are present with the frozen method shapes`() {
        // Given the generated stubs
        val GoPairing = PairingServiceGrpc.getServiceDescriptor()
        val GoStream = StreamServiceGrpc.getServiceDescriptor()

        // Then the service names match the proto package
        assertThat(GoPairing.getName()).isEqualTo("ecosys.v1.PairingService")
        assertThat(GoStream.getName()).isEqualTo("ecosys.v1.StreamService")

        // And the method shapes are exactly Pair + Heartbeat, and one bidi OpenStream
        assertThat(GoPairing.getMethods().map { it.getBareMethodName() })
            .containsExactly("Pair", "Heartbeat")
        val GoOpenStream = GoStream.getMethods().single()
        assertThat(GoOpenStream.getType()).isEqualTo(MethodDescriptor.MethodType.BIDI_STREAMING)
        assertThat(GoPairing.getMethods().associate { it.getBareMethodName() to it.getType() })
            .containsExactly("Pair", MethodDescriptor.MethodType.UNARY, "Heartbeat", MethodDescriptor.MethodType.UNARY)
    }

    @Test
    fun `enum numeric values are frozen for the wire`() {
        // Given/When/Then the enum numbers peers will serialize forever
        assertThat(DeviceRole.DEVICE_ROLE_GLASS.getNumber()).isEqualTo(1)
        assertThat(DeviceRole.DEVICE_ROLE_DAEMON.getNumber()).isEqualTo(2)
        assertThat(StreamKind.STREAM_KIND_AUDIO.getNumber()).isEqualTo(1)
        assertThat(StreamKind.STREAM_KIND_VIDEO.getNumber()).isEqualTo(2)
        assertThat(DeviceRole.DEVICE_ROLE_UNSPECIFIED.getNumber()).isEqualTo(0)
    }

    @Test
    fun `generated kotlin coroutine stubs exist for both services`() {
        // Given/When/Then the grpckt plugin emitted the Kotlin coroutine stubs
        assertThat(Class.forName("ecosys.v1.PairingServiceGrpcKt")).isNotNull()
        assertThat(Class.forName("ecosys.v1.StreamServiceGrpcKt")).isNotNull()
    }
}
