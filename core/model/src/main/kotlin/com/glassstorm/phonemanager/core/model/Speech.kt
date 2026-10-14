package com.glassstorm.phonemanager.core.model

/**
 * The canonical hub audio format. Every port that speaks PCM uses these values,
 * so a rate or width disagreement is a single constant to change, not a scatter
 * of magic numbers.
 */
object AudioFormat {
    /** The hub's only PCM sample rate: 16 kHz mono. */
    const val SAMPLE_RATE_HZ: Int = 16_000

    /** Bytes per PCM16 sample. */
    const val BYTES_PER_SAMPLE: Int = 2

    /** The canonical 20 ms chunk the relay frames audio at (320 samples, 640 bytes). */
    const val CHUNK_SAMPLES: Int = SAMPLE_RATE_HZ / 50

    /** The canonical chunk size in bytes (640). */
    const val CHUNK_BYTES: Int = CHUNK_SAMPLES * BYTES_PER_SAMPLE

    /** The minimum enrollment clip: 8 seconds. */
    const val ENROLLMENT_MIN_MILLIS: Long = 8_000L

    /** The minimum enrollment clip in samples (128 000). */
    const val ENROLLMENT_MIN_SAMPLES: Int =
        (SAMPLE_RATE_HZ.toLong() * ENROLLMENT_MIN_MILLIS / 1000L).toInt()

    /** The minimum enrollment clip in PCM16 bytes (256 000). */
    const val ENROLLMENT_MIN_BYTES: Int = ENROLLMENT_MIN_SAMPLES * BYTES_PER_SAMPLE
}

/**
 * One recognized utterance produced from an audio frame. Pure data; mirrors the
 * `ecosys.v1.StreamResult`/diarization surface without depending on the generated
 * types or on any engine type.
 *
 * [speakerName] is the hub-resolved display name of the speaker, or `null` when
 * the speaker is not enrolled. The adapter MUST NOT invent a name to fill it.
 */
data class SpeechUtterance(
    val text: String,
    val speakerName: String?,
)
