package com.glassstorm.phonemanager.transport.grpc

import com.glassstorm.phonemanager.transport.grpc.security.BearerTokenParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Direct unit tests for [BearerTokenParser].
 *
 * These pin the parser's exact contract: it recognises ONLY the literal
 * `Bearer ` scheme (case-sensitive, a single space) and returns the remainder
 * untouched, including any leading whitespace after the scheme.
 */
class BearerTokenParserTest {
    @Test
    fun `extracts the token from a well-formed bearer header`() {
        // Given the canonical header form
        // When parsed
        val token = BearerTokenParser.parse("Bearer abc123")

        // Then the credential is returned verbatim
        assertThat(token).isEqualTo("abc123")
    }

    @Test
    fun `returns null for a null header`() {
        // Given no header at all
        // When parsed
        val token = BearerTokenParser.parse(null)

        // Then there is no token
        assertThat(token).isNull()
    }

    @Test
    fun `returns null when the scheme is missing`() {
        // Given a bare token with no scheme
        // When parsed
        val token = BearerTokenParser.parse("abc123")

        // Then it is rejected
        assertThat(token).isNull()
    }

    @Test
    fun `returns null for a lowercase scheme because the match is case-sensitive`() {
        // Given the scheme spelled in lowercase
        // When parsed
        val token = BearerTokenParser.parse("bearer abc123")

        // Then it is rejected
        assertThat(token).isNull()
    }

    @Test
    fun `returns null when the scheme is not followed by a space`() {
        // Given the scheme with no separator
        // When parsed
        val token = BearerTokenParser.parse("Bearer")

        // Then it is rejected
        assertThat(token).isNull()
    }

    @Test
    fun `returns null for an empty token`() {
        // Given the scheme followed by a single space but nothing else
        // When parsed
        val token = BearerTokenParser.parse("Bearer ")

        // Then it is rejected rather than yielding an empty string
        assertThat(token).isNull()
    }

    @Test
    fun `strips the scheme exactly once and preserves the remainder`() {
        // Given a double space after the scheme
        // When parsed
        val token = BearerTokenParser.parse("Bearer  x")

        // Then only the scheme and its single space are removed
        assertThat(token).isEqualTo(" x")
    }

    @Test
    fun `returns null for a different scheme`() {
        // Given a Basic authorization header
        // When parsed
        val token = BearerTokenParser.parse("Basic abc")

        // Then it is rejected
        assertThat(token).isNull()
    }
}
