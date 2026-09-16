package com.glassstorm.phonemanager.service.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Pairing secret handling.
 *
 * The hub turns a low-entropy 6-digit PIN into a high-entropy bearer token with
 * a REAL password KDF — never a bare digest of the PIN.
 *
 * ## Documented KDF parameters
 *
 *  * Algorithm: `PBKDF2WithHmacSHA256` (`javax.crypto`, present on JVM 17).
 *  * Output: [GoTokenBits] = 256 bits, rendered as unpadded base64url (43 chars).
 *  * Iterations: [GoDefaultIterations] = 210_000 (OWASP-recommended floor for
 *    PBKDF2-HMAC-SHA256); [GoMinIterations] = 100_000 is the hard floor the codec
 *    refuses to go below, so a caller cannot silently weaken the work factor.
 *  * Salt: [GoSaltBytes] = 16 cryptographically random bytes per pairing
 *    ([GoNewSalt], drawn from [SecureRandom]), so the same PIN yields a
 *    different token on every pairing and rainbow tables are useless.
 *
 * ## Persisted form
 *
 * [GoHashToken] is what may be stored. Because the token is already a 256-bit
 * KDF output, a single SHA-256 is sufficient for the at-rest/at-rest lookup form
 * (there is no low-entropy secret to brute force). The plaintext token is
 * returned to the peer exactly once and never persisted.
 *
 * ## Comparison
 *
 * [GoConstantTimeEquals] uses [MessageDigest.isEqual] — NOT `String.equals` —
 * so a timing side channel cannot reveal how many characters of a guess matched.
 *
 * This object never logs, prints, or otherwise surfaces a PIN or token.
 */
object TokenCodec {
    /** 256-bit derived tokens. */
    const val GoTokenBits: Int = 256

    /** Per-pairing salt size in bytes. */
    const val GoSaltBytes: Int = 16

    /** Work factor used for a real pairing. */
    const val GoDefaultIterations: Int = 210_000

    /** Hard floor: derivations weaker than this are rejected. */
    const val GoMinIterations: Int = 100_000

    private const val GO_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val GO_TOKEN_BYTES = GoTokenBits / 8
    private const val GO_PIN_BOUND = 1_000_000

    private val GoEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val GoRandom: SecureRandom = SecureRandom()

    /** A fresh 16-byte cryptographically random salt. */
    fun GoNewSalt(): ByteArray = ByteArray(GoSaltBytes).also { GoRandom.nextBytes(it) }

    /**
     * Derive a token from [pin] using [salt] and [iterations].
     *
     * Deterministic for fixed inputs (unit-testable) and salted per pairing.
     * Throws [IllegalArgumentException] for a salt shorter than [GoSaltBytes] or
     * an iteration count below [GoMinIterations] — a caller must not weaken the KDF.
     */
    fun GoDeriveToken(
        pin: String,
        salt: ByteArray,
        iterations: Int,
    ): String {
        require(salt.size >= GoSaltBytes) {
            "salt must be at least $GoSaltBytes bytes, was ${salt.size}"
        }
        require(iterations >= GoMinIterations) {
            "iterations must be at least $GoMinIterations, was $iterations"
        }
        val GoSpec = PBEKeySpec(pin.toCharArray(), salt, iterations, GoTokenBits)
        try {
            val GoDerived = SecretKeyFactory.getInstance(GO_ALGORITHM).generateSecret(GoSpec).encoded
            return GoEncoder.encodeToString(GoDerived)
        } finally {
            GoSpec.clearPassword()
        }
    }

    /**
     * The persisted form of [token]: a deterministic SHA-256 rendered as
     * unpadded base64url. Stable across restarts and distinct from the token.
     */
    fun GoHashToken(token: String): String {
        val GoDigest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return GoEncoder.encodeToString(GoDigest)
    }

    /**
     * Constant-time equality for secrets.
     *
     * Uses [MessageDigest.isEqual]; a differing length returns `false` (lengths
     * are not secret here, and the comparison is still constant over equal-size
     * inputs).
     */
    fun GoConstantTimeEquals(
        left: String,
        right: String,
    ): Boolean = MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    /**
     * A uniform 6-digit PIN, drawn from [SecureRandom] (never `java.util.Random`).
     * Always exactly six characters; leading zeros are preserved.
     */
    fun GoNewPin(): String = String.format(Locale.ROOT, "%06d", GoRandom.nextInt(GO_PIN_BOUND))
}
