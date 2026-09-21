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
    private val GoAuthKey: Metadata.Key<String> =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    private class GoRecordingCall<ReqT : Any, RespT : Any>(
        private val GoDescriptor: MethodDescriptor<ReqT, RespT>,
    ) : ServerCall<ReqT, RespT>() {
        var GoClosed: Status? = null

        override fun request(requests: Int) = Unit

        override fun sendHeaders(headers: Metadata) = Unit

        override fun sendMessage(message: RespT) = Unit

        override fun isReady(): Boolean = true

        override fun isCancelled(): Boolean = false

        override fun close(
            status: Status,
            trailers: Metadata,
        ) {
            GoClosed = status
        }

        override fun getMethodDescriptor(): MethodDescriptor<ReqT, RespT> = GoDescriptor
    }

    private class GoRecordingHandler<ReqT : Any, RespT : Any> : ServerCallHandler<ReqT, RespT> {
        var GoCalled: Boolean = false

        override fun startCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
        ): ServerCall.Listener<ReqT> {
            GoCalled = true
            return object : ServerCall.Listener<ReqT>() {}
        }
    }

    private val GoDevice =
        Device(
            GoDeviceId = "d-1",
            GoDeviceName = "glass-1",
            GoRole = "GLASS",
            GoTokenHash = "hash-d-1",
            GoPairedAtMs = 1_000L,
            GoLastSeenMs = null,
        )

    private val GoVerifier = TokenVerifier { token -> GoDevice.takeIf { token == "good-token" } }

    private fun GoMetadata(header: String?): Metadata =
        Metadata().apply {
            if (header != null) put(GoAuthKey, header)
        }

    private fun <ReqT : Any, RespT : Any> GoIntercept(
        descriptor: MethodDescriptor<ReqT, RespT>,
        headers: Metadata,
    ): Pair<GoRecordingCall<ReqT, RespT>, GoRecordingHandler<ReqT, RespT>> {
        val GoCall = GoRecordingCall(descriptor)
        val GoHandler = GoRecordingHandler<ReqT, RespT>()
        AuthInterceptor(GoVerifier).interceptCall(GoCall, headers, GoHandler)
        return GoCall to GoHandler
    }

    @Test
    fun `pair is the one method allowed without a token`() {
        // Given the Pair descriptor and no metadata
        // When intercepted
        val (GoCall, GoHandler) = GoIntercept(PairingServiceGrpc.getPairMethod(), GoMetadata(null))

        // Then the service body is reached and the call is not closed as unauth
        assertThat(GoHandler.GoCalled).isTrue()
        assertThat(GoCall.GoClosed).isNull()
    }

    @Test
    fun `heartbeat without metadata never reaches the service and closes UNAUTHENTICATED`() {
        // Given the Heartbeat descriptor and NO authorization header
        // When intercepted
        val (GoCall, GoHandler) = GoIntercept(PairingServiceGrpc.getHeartbeatMethod(), GoMetadata(null))

        // Then the handler is never invoked and the call closes on the exact status
        assertThat(GoHandler.GoCalled).isFalse()
        assertThat(GoCall.GoClosed?.code).isEqualTo(Status.Code.UNAUTHENTICATED)
    }

    @Test
    fun `heartbeat with an unknown token never reaches the service`() {
        // Given a well-formed Bearer header carrying an unknown token
        // When intercepted
        val (GoCall, GoHandler) =
            GoIntercept(PairingServiceGrpc.getHeartbeatMethod(), GoMetadata("Bearer not-a-real-token"))

        // Then it is rejected without touching the service
        assertThat(GoHandler.GoCalled).isFalse()
        assertThat(GoCall.GoClosed?.code).isEqualTo(Status.Code.UNAUTHENTICATED)
    }

    @Test
    fun `heartbeat with a valid bearer token reaches the service and carries the device id`() {
        // Given a valid Bearer header
        // When intercepted
        val (GoCall, GoHandler) = GoIntercept(PairingServiceGrpc.getHeartbeatMethod(), GoMetadata("Bearer good-token"))

        // Then the body runs and the call was not closed
        assertThat(GoHandler.GoCalled).isTrue()
        assertThat(GoCall.GoClosed).isNull()
    }

    @Test
    fun `only the exact Pair method name is allowlisted, not other Pair-ish descriptors`() {
        // Given descriptors whose full method names resemble Pair
        val GoNames =
            listOf(
                PairingServiceGrpc.getPairMethod().fullMethodName,
                PairingServiceGrpc.getHeartbeatMethod().fullMethodName,
            )

        // Then the allowlist constant is exactly the generated Pair name
        assertThat(AuthInterceptor.GO_PAIR_METHOD).isEqualTo("ecosys.v1.PairingService/Pair")
        assertThat(GoNames.first()).isEqualTo("ecosys.v1.PairingService/Pair")
        assertThat(GoNames.last()).isEqualTo("ecosys.v1.PairingService/Heartbeat")
    }

    @Test
    fun `empty bearer value is rejected before the verifier is consulted`() {
        // Given a "Bearer " header with an empty token
        val (GoCall, GoHandler) = GoIntercept(PairingServiceGrpc.getHeartbeatMethod(), GoMetadata("Bearer "))

        // Then it is rejected and never verified
        assertThat(GoHandler.GoCalled).isFalse()
        assertThat(GoCall.GoClosed?.code).isEqualTo(Status.Code.UNAUTHENTICATED)
    }
}
