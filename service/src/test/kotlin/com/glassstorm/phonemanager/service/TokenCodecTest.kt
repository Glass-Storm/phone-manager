package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.service.security.TokenCodec
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Proves the pairing secret handling: a REAL KDF (PBKDF2-HMAC-SHA256) with a
 * random per-pairing salt, a non-reversible persisted hash, a constant-time
 * comparison, and a uniform 6-digit PIN drawn from a CSPRNG.
 */
class TokenCodecTest {
    @Test
    fun `same pin with different random salts derives different tokens`() {
        // Given two independent per-pairing salts
        val saltA = TokenCodec.newSalt()
        val saltB = TokenCodec.newSalt()

        // When the same PIN is derived against each
        val tokenA = TokenCodec.deriveToken("123456", saltA, TokenCodec.DEFAULT_ITERATIONS)
        val tokenB = TokenCodec.deriveToken("123456", saltB, TokenCodec.DEFAULT_ITERATIONS)

        // Then the tokens differ (the salt is actually mixed in) and both differ from the PIN
        assertThat(tokenA).isNotEqualTo(tokenB)
        assertThat(tokenA).doesNotContain("123456")
        assertThat(tokenB).doesNotContain("123456")
    }

    @Test
    fun `derivation is deterministic for the same pin salt and iteration count`() {
        // Given fixed KDF inputs
        val salt = ByteArray(TokenCodec.SALT_BYTES) { it.toByte() }

        // When derived twice
        val first = TokenCodec.deriveToken("654321", salt, TokenCodec.DEFAULT_ITERATIONS)
        val second = TokenCodec.deriveToken("654321", salt, TokenCodec.DEFAULT_ITERATIONS)

        // Then the derivation is reproducible
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `derived token is 256 bits of base64url material`() {
        // Given the codec contract
        // When a token is derived
        val token = TokenCodec.deriveToken("000000", newFixedSalt(), TokenCodec.DEFAULT_ITERATIONS)

        // Then it is exactly 32 bytes, unpadded base64url (43 chars)
        assertThat(TokenCodec.TOKEN_BITS).isEqualTo(256)
        assertThat(token.length).isEqualTo(43)
        assertThat(token).matches("[A-Za-z0-9_-]+")
    }

    @Test
    fun `hash round trip is stable and never returns the token itself`() {
        // Given a derived token
        val token = TokenCodec.deriveToken("111111", TokenCodec.newSalt(), TokenCodec.DEFAULT_ITERATIONS)

        // When it is hashed for persistence, twice
        val hash = TokenCodec.hashToken(token)

        // Then the hash is stable and distinct from the secret
        assertThat(TokenCodec.hashToken(token)).isEqualTo(hash)
        assertThat(hash).isNotEqualTo(token)
        assertThat(hash.length).isEqualTo(43)
    }

    @Test
    fun `constant time compare accepts equal strings and rejects a one character difference`() {
        // Given two equal secrets
        // When compared
        // Then equality is accepted
        assertThat(TokenCodec.constantTimeEquals("abcdef", "abcdef")).isTrue()

        // And a single-character change is rejected
        assertThat(TokenCodec.constantTimeEquals("abcdef", "abcdeg")).isFalse()

        // And a length change is rejected
        assertThat(TokenCodec.constantTimeEquals("abcdef", "abcde")).isFalse()
    }

    @Test
    fun `new salt is the declared size and varies between calls`() {
        // Given the codec
        // When two salts are drawn
        val saltA = TokenCodec.newSalt()
        val saltB = TokenCodec.newSalt()

        // Then they are the declared size and independent
        assertThat(saltA.size).isEqualTo(TokenCodec.SALT_BYTES)
        assertThat(saltB.size).isEqualTo(TokenCodec.SALT_BYTES)
        assertThat(saltA).isNotEqualTo(saltB)
    }

    @Test
    fun `new pin is always exactly six digits`() {
        // Given/When 500 PINs are drawn
        val pins = (1..500).map { TokenCodec.newPin() }

        // Then every one is six digits with no sign, space or truncation
        pins.forEach { pin ->
            assertThat(pin.length).isEqualTo(6)
            assertThat(pin).matches("[0-9]{6}")
        }
    }

    @Test
    fun `new pin is uniform rather than stuck on a constant`() {
        // Given/When 500 PINs are drawn from the CSPRNG
        val distinct = (1..500).map { TokenCodec.newPin() }.toSet()

        // Then a SecureRandom bound draw spreads over the space (a fixed value or a
        // tiny biased set would collapse this count)
        assertThat(distinct.size).isAtLeast(480)
    }

    @Test
    fun `iteration counts below the security floor are rejected`() {
        // Given a salt and a sub-floor iteration count
        val salt = newFixedSalt()

        // When/Then the KDF refuses to run weaker than the documented floor
        assertThat(TokenCodec.MIN_ITERATIONS).isAtLeast(100_000)
        assertThrows(IllegalArgumentException::class.java) {
            TokenCodec.deriveToken("123456", salt, TokenCodec.MIN_ITERATIONS - 1)
        }
    }

    @Test
    fun `short salts are rejected so the KDF never runs with weak entropy`() {
        // Given a one-byte salt
        // When/Then derivation refuses it
        assertThrows(IllegalArgumentException::class.java) {
            TokenCodec.deriveToken("123456", ByteArray(1), TokenCodec.DEFAULT_ITERATIONS)
        }
    }

    private fun newFixedSalt(): ByteArray = ByteArray(TokenCodec.SALT_BYTES) { (it * 7).toByte() }
}
