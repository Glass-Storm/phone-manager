package com.glassstorm.phonemanager.adapter.jvm.ai

/**
 * FNV-1a 64-bit, the one hash the deterministic fakes share.
 *
 * FNV is used explicitly because `ByteArray.hashCode()` is identity-based and
 * would make the fakes non-reproducible across instances. Public domain.
 */
internal object Fnv1a {
    private const val OFFSET_BASIS: Long = -0x340d631b7bdddcdbL
    private const val PRIME: Long = 0x100000001b3L

    /** The 64-bit FNV-1a digest of [bytes]. */
    fun hash(bytes: ByteArray): Long {
        var hash = OFFSET_BASIS
        for (byte in bytes) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= PRIME
        }
        return hash
    }

    /** The digest as a 16-hex-digit lower-case string. */
    fun hex(bytes: ByteArray): String = hash(bytes).toULong().toString(16).padStart(16, '0')
}
