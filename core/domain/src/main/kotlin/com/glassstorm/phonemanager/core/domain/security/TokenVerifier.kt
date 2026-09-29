package com.glassstorm.phonemanager.core.domain.security

import com.glassstorm.phonemanager.core.model.Device

/**
 * The one collaborator the bearer-token interceptor needs: resolve a bearer token
 * to the device that owns it, or `null` when the token is unknown, tampered, or
 * revoked.
 *
 * A separate port (rather than depending on the whole `PairingService`) keeps the
 * interceptor testable in isolation and lets the implementation live anywhere.
 *
 * It lives in `:core:domain`, NOT in the transport module that consumes it: the
 * implementation is a use-case (`:core:service`), so a declaration in
 * `:transport:grpc` would force `:core:service` to depend on `:transport:grpc` to
 * implement it — a cycle. Both sides already depend on `:core:domain`.
 */
fun interface TokenVerifier {
    fun verifyToken(token: String): Device?
}
