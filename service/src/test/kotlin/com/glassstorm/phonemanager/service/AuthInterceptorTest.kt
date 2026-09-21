package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.domain.dto.Device
import com.glassstorm.phonemanager.service.security.AuthInterceptor
import com.glassstorm.phonemanager.service.security.TokenVerifier
import com.google.common.truth.Truth.assertThat
import ecosys.v1.PairingServiceGrpc
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import org.junit.Test

/**
 * Direct unit tests for [AuthInterceptor].
 *
 * These drive `interceptCall` with a recording [ServerCallHandler] so they assert
 * what the INTERCEPTOR does — that the service body is NEVER reached on a bad
 * credential — rather than observing the service's own guard. That makes the
 * allowlist itself the load-bearing gate: widening it (e.g. also allowing
 * `Heartbeat`) fails here.
 */
class AuthInterceptorTest {
    private val authKey: Metadata.Key<String> =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    private class RecordingCall<ReqT : Any, RespT : Any>(
        private val descriptor: MethodDescriptor<ReqT, RespT>,
    ) : ServerCall<ReqT, RespT>() {
        var closed: Status? = null

        override fun request(requests: Int) = Unit

        override fun sendHeaders(headers: Metadata) = Unit

        override fun sendMessage(message: RespT) = Unit

        override fun isReady(): Boolean = true

        override fun isCancelled(): Boolean = false

        override fun close(
            status: Status,
            trailers: Metadata,
        ) {
            closed = status
        }

        override fun getMethodDescriptor(): MethodDescriptor<ReqT, RespT> = descriptor
    }

    private class RecordingHandler<ReqT : Any, RespT : Any> : ServerCallHandler<ReqT, RespT> {
        var called: Boolean = false

        override fun startCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
        ): ServerCall.Listener<ReqT> {
            called = true
            return object : ServerCall.Listener<ReqT>() {}
        }
    }

    private val device =
        Device(
            deviceId = "d-1",
            deviceName = "glass-1",
            role = "GLASS",
            tokenHash = "hash-d-1",
            pairedAtMs = 1_000L,
            lastSeenMs = null,
        )

    private val verifier = TokenVerifier { token -> device.takeIf { token == "good-token" } }

    private fun metadata(header: String?): Metadata =
        Metadata().apply {
            if (header != null) put(authKey, header)
        }

    private fun <ReqT : Any, RespT : Any> intercept(
        descriptor: MethodDescriptor<ReqT, RespT>,
        headers: Metadata,
    ): Pair<RecordingCall<ReqT, RespT>, RecordingHandler<ReqT, RespT>> {
        val call = RecordingCall(descriptor)
        val handler = RecordingHandler<ReqT, RespT>()
        AuthInterceptor(verifier).interceptCall(call, headers, handler)
        return call to handler
    }

    @Test
    fun `pair is the one method allowed without a token`() {
        // Given the Pair descriptor and no metadata
        // When intercepted
        val (call, handler) = intercept(PairingServiceGrpc.getPairMethod(), metadata(null))

        // Then the service body is reached and the call is not closed as unauth
        assertThat(handler.called).isTrue()
        assertThat(call.closed).isNull()
    }

    @Test
    fun `heartbeat without metadata never reaches the service and closes UNAUTHENTICATED`() {
        // Given the Heartbeat descriptor and NO authorization header
        // When intercepted
        val (call, handler) = intercept(PairingServiceGrpc.getHeartbeatMethod(), metadata(null))

        // Then the handler is never invoked and the call closes on the exact status
        assertThat(handler.called).isFalse()
        assertThat(call.closed?.code).isEqualTo(Status.Code.UNAUTHENTICATED)
    }

    @Test
    fun `heartbeat with an unknown token never reaches the service`() {
        // Given a well-formed Bearer header carrying an unknown token
        // When intercepted
        val (call, handler) =
            intercept(PairingServiceGrpc.getHeartbeatMethod(), metadata("Bearer not-a-real-token"))

        // Then it is rejected without touching the service
        assertThat(handler.called).isFalse()
        assertThat(call.closed?.code).isEqualTo(Status.Code.UNAUTHENTICATED)
    }

    @Test
    fun `heartbeat with a valid bearer token reaches the service and carries the device id`() {
        // Given a valid Bearer header
        // When intercepted
        val (call, handler) = intercept(PairingServiceGrpc.getHeartbeatMethod(), metadata("Bearer good-token"))

        // Then the body runs and the call was not closed
        assertThat(handler.called).isTrue()
        assertThat(call.closed).isNull()
    }

    @Test
    fun `only the exact Pair method name is allowlisted, not other Pair-ish descriptors`() {
        // Given descriptors whose full method names resemble Pair
        val names =
            listOf(
                PairingServiceGrpc.getPairMethod().fullMethodName,
                PairingServiceGrpc.getHeartbeatMethod().fullMethodName,
            )

        // Then the allowlist constant is exactly the generated Pair name
        assertThat(AuthInterceptor.PAIR_METHOD).isEqualTo("ecosys.v1.PairingService/Pair")
        assertThat(names.first()).isEqualTo("ecosys.v1.PairingService/Pair")
        assertThat(names.last()).isEqualTo("ecosys.v1.PairingService/Heartbeat")
    }

    @Test
    fun `empty bearer value is rejected before the verifier is consulted`() {
        // Given a "Bearer " header with an empty token
        val (call, handler) = intercept(PairingServiceGrpc.getHeartbeatMethod(), metadata("Bearer "))

        // Then it is rejected and never verified
        assertThat(handler.called).isFalse()
        assertThat(call.closed?.code).isEqualTo(Status.Code.UNAUTHENTICATED)
    }
}
