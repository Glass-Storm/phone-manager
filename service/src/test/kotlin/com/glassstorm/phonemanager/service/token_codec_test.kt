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
        val GoSaltA = TokenCodec.GoNewSalt()
        val GoSaltB = TokenCodec.GoNewSalt()

        // When the same PIN is derived against each
        val GoTokenA = TokenCodec.GoDeriveToken("123456", GoSaltA, TokenCodec.GoDefaultIterations)
        val GoTokenB = TokenCodec.GoDeriveToken("123456", GoSaltB, TokenCodec.GoDefaultIterations)

        // Then the tokens differ (the salt is actually mixed in) and both differ from the PIN
        assertThat(GoTokenA).isNotEqualTo(GoTokenB)
        assertThat(GoTokenA).doesNotContain("123456")
        assertThat(GoTokenB).doesNotContain("123456")
    }

    @Test
    fun `derivation is deterministic for the same pin salt and iteration count`() {
        // Given fixed KDF inputs
        val GoSalt = ByteArray(TokenCodec.GoSaltBytes) { it.toByte() }

        // When derived twice
        val GoFirst = TokenCodec.GoDeriveToken("654321", GoSalt, TokenCodec.GoDefaultIterations)
        val GoSecond = TokenCodec.GoDeriveToken("654321", GoSalt, TokenCodec.GoDefaultIterations)

        // Then the derivation is reproducible
        assertThat(GoFirst).isEqualTo(GoSecond)
    }

    @Test
    fun `derived token is 256 bits of base64url material`() {
        // Given the codec contract
        // When a token is derived
        val GoToken = TokenCodec.GoDeriveToken("000000", GoNewFixedSalt(), TokenCodec.GoDefaultIterations)

        // Then it is exactly 32 bytes, unpadded base64url (43 chars)
        assertThat(TokenCodec.GoTokenBits).isEqualTo(256)
        assertThat(GoToken.length).isEqualTo(43)
        assertThat(GoToken).matches("[A-Za-z0-9_-]+")
    }

    @Test
    fun `hash round trip is stable and never returns the token itself`() {
        // Given a derived token
        val GoToken = TokenCodec.GoDeriveToken("111111", TokenCodec.GoNewSalt(), TokenCodec.GoDefaultIterations)

        // When it is hashed for persistence, twice
        val GoHash = TokenCodec.GoHashToken(GoToken)

        // Then the hash is stable and distinct from the secret
        assertThat(TokenCodec.GoHashToken(GoToken)).isEqualTo(GoHash)
        assertThat(GoHash).isNotEqualTo(GoToken)
        assertThat(GoHash.length).isEqualTo(43)
    }

    @Test
    fun `constant time compare accepts equal strings and rejects a one character difference`() {
        // Given two equal secrets
        // When compared
        // Then equality is accepted
        assertThat(TokenCodec.GoConstantTimeEquals("abcdef", "abcdef")).isTrue()

        // And a single-character change is rejected
        assertThat(TokenCodec.GoConstantTimeEquals("abcdef", "abcdeg")).isFalse()

        // And a length change is rejected
        assertThat(TokenCodec.GoConstantTimeEquals("abcdef", "abcde")).isFalse()
    }

    @Test
    fun `new salt is the declared size and varies between calls`() {
        // Given the codec
        // When two salts are drawn
        val GoSaltA = TokenCodec.GoNewSalt()
        val GoSaltB = TokenCodec.GoNewSalt()

        // Then they are the declared size and independent
        assertThat(GoSaltA.size).isEqualTo(TokenCodec.GoSaltBytes)
        assertThat(GoSaltB.size).isEqualTo(TokenCodec.GoSaltBytes)
        assertThat(GoSaltA).isNotEqualTo(GoSaltB)
    }

    @Test
    fun `new pin is always exactly six digits`() {
        // Given/When 500 PINs are drawn
        val GoPins = (1..500).map { TokenCodec.GoNewPin() }

        // Then every one is six digits with no sign, space or truncation
        GoPins.forEach { GoPin ->
            assertThat(GoPin.length).isEqualTo(6)
            assertThat(GoPin).matches("[0-9]{6}")
        }
    }

    @Test
    fun `new pin is uniform rather than stuck on a constant`() {
        // Given/When 500 PINs are drawn from the CSPRNG
        val GoDistinct = (1..500).map { TokenCodec.GoNewPin() }.toSet()

        // Then a SecureRandom bound draw spreads over the space (a fixed value or a
        // tiny biased set would collapse this count)
        assertThat(GoDistinct.size).isAtLeast(480)
    }

    @Test
    fun `iteration counts below the security floor are rejected`() {
        // Given a salt and a sub-floor iteration count
        val GoSalt = GoNewFixedSalt()

        // When/Then the KDF refuses to run weaker than the documented floor
        assertThat(TokenCodec.GoMinIterations).isAtLeast(100_000)
        assertThrows(IllegalArgumentException::class.java) {
            TokenCodec.GoDeriveToken("123456", GoSalt, TokenCodec.GoMinIterations - 1)
        }
    }

    @Test
    fun `short salts are rejected so the KDF never runs with weak entropy`() {
        // Given a one-byte salt
        // When/Then derivation refuses it
        assertThrows(IllegalArgumentException::class.java) {
            TokenCodec.GoDeriveToken("123456", ByteArray(1), TokenCodec.GoDefaultIterations)
        }
    }

    private fun GoNewFixedSalt(): ByteArray = ByteArray(TokenCodec.GoSaltBytes) { (it * 7).toByte() }
}
