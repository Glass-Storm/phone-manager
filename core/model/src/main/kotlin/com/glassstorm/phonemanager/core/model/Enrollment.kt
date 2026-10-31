package com.glassstorm.phonemanager.core.model

/**
 * The outcome of an enrollment attempt. Pure data.
 *
 * [ok] is the single success flag; [reason] is the machine-readable rejection
 * code (never a human sentence), and is `null` on success. The codes mirror the
 * `ecosys.v1.EnrollResponse.reason` vocabulary so the transport can pass them
 * through unchanged.
 */
data class EnrollOutcome(
    val ok: Boolean,
    val reason: String?,
) {
    companion object {
        /** The proposed name is empty, too long, or contains a forbidden character. */
        const val REASON_INVALID_NAME: String = "invalid-name"

        /** The supplied audio is shorter than the 8 s minimum. */
        const val REASON_AUDIO_TOO_SHORT: String = "audio-too-short"

        /** The voiceprint model could not produce an embedding for otherwise valid audio. */
        const val REASON_VOICEPRINT_FAILED: String = "voiceprint-failed"

        /** The success value (reason is always `null`). */
        val accepted: EnrollOutcome = EnrollOutcome(ok = true, reason = null)

        /** A rejection carrying [reason]. */
        fun rejected(reason: String): EnrollOutcome = EnrollOutcome(ok = false, reason = reason)
    }
}

/**
 * A speaker voiceprint: the raw embedding vector produced by the embedding model.
 * Pure data, JSON-free and engine-free, so the hub can persist and compare it
 * without depending on the ai-android types.
 *
 * The vector is model-specific and only comparable to another voiceprint from the
 * SAME model and dimension ([modelId] carries that provenance).
 */
data class Voiceprint(
    val embedding: FloatArray,
    val modelId: String? = null,
) {
    // FloatArray's identity equals/hashCode would break value semantics and make
    // any Set/Map usage subtly wrong, so they are overridden on the CONTENT.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Voiceprint) return false
        return embedding.contentEquals(other.embedding) && modelId == other.modelId
    }

    override fun hashCode(): Int = 31 * embedding.contentHashCode() + (modelId?.hashCode() ?: 0)

    override fun toString(): String = "Voiceprint(dim=${embedding.size}, modelId=$modelId)"
}

/**
 * One enrolled speaker: a display [name] bound to a [voiceprint]. Pure data.
 */
data class EnrolledSpeaker(
    val name: String,
    val voiceprint: Voiceprint,
)

/**
 * The HUB-OWNED enrollment-name policy: Han characters, ASCII letters and digits
 * only, with no whitespace or punctuation, 1–24 characters long.
 *
 * Kept as one pure object so the JVM fake, the device adapter and the transport's
 * request validation share exactly one rule. `\p{IsHan}` is used (not the bare
 * `\p{Han}`) because it is the spelling `java.util.regex` accepts; Android's ICU
 * regex accepts it too.
 */
object EnrollmentName {
    /** Maximum accepted name length. */
    const val MAX_LENGTH: Int = 24

    private val ALLOWED = Regex("^[\\p{IsHan}A-Za-z0-9]+$")

    /** Whether [name] is an acceptable display name. */
    fun isValid(name: String): Boolean = name.length in 1..MAX_LENGTH && ALLOWED.matches(name)
}
