package com.glassstorm.phonemanager.core.domain.adapter.speech

/**
 * Port (interface) for text-to-speech.
 *
 * Owned by `:core:domain`; implemented in an adapter (the deterministic JVM fake
 * by default, the on-device ai-android TTS engine on a device). The hub — never
 * the Raspberry-Pi peer — synthesizes, then relays the PCM to the peer.
 *
 * The output is ALWAYS raw little-endian PCM16 mono at 16 kHz
 * ([com.glassstorm.phonemanager.core.model.AudioFormat.SAMPLE_RATE_HZ]); an
 * implementation whose model runs at another rate MUST resample.
 */
interface TtsPort {
    /**
     * Synthesizes [text] into PCM16 mono 16 kHz bytes.
     *
     * An empty [text] yields an empty array (an ordinary outcome, not an error).
     */
    suspend fun synthesize(text: String): ByteArray
}
