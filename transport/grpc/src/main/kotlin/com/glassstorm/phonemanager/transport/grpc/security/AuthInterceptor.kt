package com.glassstorm.phonemanager.transport.grpc.security

import com.glassstorm.phonemanager.core.domain.security.TokenVerifier
import com.glassstorm.phonemanager.core.model.Device
import ecosys.v1.PairingServiceGrpc
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status

/**
 * Bearer-token authentication for every hub RPC.
 *
 * ## Policy
 *
 * Exactly ONE method is reachable without a token: the pairing bootstrap
 * `ecosys.v1.PairingService/Pair` (and only while a pairing window is open — the
 * service enforces that separately). EVERY other method requires a well-formed
 * `authorization: Bearer <token>` metadata header carrying a token the
 * [tokenVerifier] recognises.
 *
 * ## Mechanics
 *
 * The allowlist is matched against [io.grpc.MethodDescriptor.getFullMethodName]
 * (the `package.Service/Method` string), so it cannot be bypassed by casing or by
 * a bare-method collision across services. On success the resolved device id is
 * attached to the call's [Context] under [deviceIdKey] — never to a global or a
 * mutable field — and the call proceeds through [Contexts.interceptCall].
 *
 * On any failure the call is closed immediately with [Status.UNAUTHENTICATED] and
 * a no-op listener, so no service method body ever runs. The rejection
 * description is deliberately uniform: it never distinguishes "missing" from
 * "unknown" from "tampered", and it never echoes the token or the PIN.
 */
class AuthInterceptor(
    private val tokenVerifier: TokenVerifier,
) : ServerInterceptor {
    override fun <ReqT : Any, RespT : Any> interceptCall(
        method: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        // The one bootstrap method: reachable without a token by design.
        if (method.methodDescriptor.fullMethodName == PAIR_METHOD) {
            return next.startCall(method, headers)
        }

        val device =
            resolveDevice(headers)
                ?: return reject(method)

        // Attach the authenticated identity to THIS call's Context only.
        val authenticated = Context.current().withValue(deviceIdKey, device.deviceId)
        return Contexts.interceptCall(authenticated, method, headers, next)
    }

    /** Extract and verify `Bearer <token>`, or `null` for any malformed/unknown input. */
    private fun resolveDevice(headers: Metadata): Device? {
        val header = headers.get(AUTHORIZATION_KEY) ?: return null
        val token = bearerToken(header) ?: return null
        if (token.isEmpty()) return null
        return tokenVerifier.verifyToken(token)
    }

    /**
     * Parse ONLY the exact `Bearer <token>` form. Rejects a missing scheme, an
     * empty token, a different scheme, and any prefix that merely resembles
     * `Bearer` (the scheme name is case-sensitive and must be followed by a
     * single space).
     */
    private fun bearerToken(header: String): String? {
        if (!header.startsWith("$BEARER_PREFIX ")) return null
        return header.removePrefix("$BEARER_PREFIX ")
    }

    /** Close the call with UNAUTHENTICATED without invoking the service body. */
    private fun <ReqT : Any, RespT : Any> reject(call: ServerCall<ReqT, RespT>): ServerCall.Listener<ReqT> {
        call.close(UNAUTHENTICATED, Metadata())
        return object : ServerCall.Listener<ReqT>() {}
    }

    companion object {
        /** The sole unauthenticated method, taken from the generated descriptor. */
        val PAIR_METHOD: String = PairingServiceGrpc.getPairMethod().fullMethodName

        private const val BEARER_PREFIX = "Bearer"

        private val AUTHORIZATION_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

        /**
         * Carries the authenticated device id for the duration of one call.
         * Read it inside a service method with `AuthInterceptor.deviceIdKey.get()`.
         */
        val deviceIdKey: Context.Key<String> = Context.key("ecosys-device-id")

        /** Uniform rejection: never says WHY (no oracle), never echoes secrets. */
        private val UNAUTHENTICATED: Status =
            Status.UNAUTHENTICATED
                .withDescription("missing or invalid bearer token")
    }
}
