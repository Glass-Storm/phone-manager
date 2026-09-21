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
 *  * Output: [TOKEN_BITS] = 256 bits, rendered as unpadded base64url (43 chars).
 *  * Iterations: [DEFAULT_ITERATIONS] = 210_000 (OWASP-recommended floor for
 *    PBKDF2-HMAC-SHA256); [MIN_ITERATIONS] = 100_000 is the hard floor the codec
 *    refuses to go below, so a caller cannot silently weaken the work factor.
 *  * Salt: [SALT_BYTES] = 16 cryptographically random bytes per pairing
 *    ([newSalt], drawn from [SecureRandom]), so the same PIN yields a
 *    different token on every pairing and rainbow tables are useless.
 *
 * ## Persisted form
 *
 * [hashToken] is what may be stored. Because the token is already a 256-bit
 * KDF output, a single SHA-256 is sufficient for the at-rest/at-rest lookup form
 * (there is no low-entropy secret to brute force). The plaintext token is
 * returned to the peer exactly once and never persisted.
 *
 * ## Comparison
 *
 * [constantTimeEquals] uses [MessageDigest.isEqual] — NOT `String.equals` —
 * so a timing side channel cannot reveal how many characters of a guess matched.
 *
 * This object never logs, prints, or otherwise surfaces a PIN or token.
 */
object TokenCodec {
    /** 256-bit derived tokens. */
    const val TOKEN_BITS: Int = 256

    /** Per-pairing salt size in bytes. */
    const val SALT_BYTES: Int = 16

    /** Work factor used for a real pairing. */
    const val DEFAULT_ITERATIONS: Int = 210_000

    /** Hard floor: derivations weaker than this are rejected. */
    const val MIN_ITERATIONS: Int = 100_000

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val TOKEN_BYTES = TOKEN_BITS / 8
    private const val PIN_BOUND = 1_000_000

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val random: SecureRandom = SecureRandom()

    /** A fresh 16-byte cryptographically random salt. */
    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    /**
     * Derive a token from [pin] using [salt] and [iterations].
     *
     * Deterministic for fixed inputs (unit-testable) and salted per pairing.
     * Throws [IllegalArgumentException] for a salt shorter than [SALT_BYTES] or
     * an iteration count below [MIN_ITERATIONS] — a caller must not weaken the KDF.
     */
    fun deriveToken(
        pin: String,
        salt: ByteArray,
        iterations: Int,
    ): String {
        require(salt.size >= SALT_BYTES) {
            "salt must be at least $SALT_BYTES bytes, was ${salt.size}"
        }
        require(iterations >= MIN_ITERATIONS) {
            "iterations must be at least $MIN_ITERATIONS, was $iterations"
        }
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, TOKEN_BITS)
        try {
            val derived = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
            return encoder.encodeToString(derived)
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * The persisted form of [token]: a deterministic SHA-256 rendered as
     * unpadded base64url. Stable across restarts and distinct from the token.
     */
    fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return encoder.encodeToString(digest)
    }

    /**
     * Constant-time equality for secrets.
     *
     * Uses [MessageDigest.isEqual]; a differing length returns `false` (lengths
     * are not secret here, and the comparison is still constant over equal-size
     * inputs).
     */
    fun constantTimeEquals(
        left: String,
        right: String,
    ): Boolean = MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    /**
     * A uniform 6-digit PIN, drawn from [SecureRandom] (never `java.util.Random`).
     * Always exactly six characters; leading zeros are preserved.
     */
    fun newPin(): String = String.format(Locale.ROOT, "%06d", random.nextInt(PIN_BOUND))
}
