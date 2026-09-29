package com.glassstorm.phonemanager.transport.grpc.security

/**
 * Parses the exact `Bearer <token>` authorization header form.
 *
 * Owns ONE responsibility: extracting the credential from the raw header value.
 * It knows nothing about allowlists, gRPC calls, or verification — that policy
 * lives in [AuthInterceptor].
 */
internal object BearerTokenParser {
    const val BEARER_PREFIX: String = "Bearer"

    /**
     * Returns the token from `"Bearer <token>"`, or `null` when [header] is null,
     * lacks the exact `Bearer ` scheme (case-sensitive, single space), or carries
     * an empty token.
     */
    fun parse(header: String?): String? {
        if (header == null) return null
        if (!header.startsWith("$BEARER_PREFIX ")) return null
        return header.removePrefix("$BEARER_PREFIX ").ifEmpty { null }
    }
}
