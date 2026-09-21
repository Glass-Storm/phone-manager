package com.glassstorm.phonemanager.adapter.speech.mock

import com.glassstorm.phonemanager.adapter.speech.speechmatics.SAMPLE_RATE_HZ
import com.glassstorm.phonemanager.core.domain.adapter.speech.SttPort
import java.util.concurrent.ConcurrentHashMap

/**
 * The v1 DEFAULT speech engine: fully OFFLINE and DETERMINISTIC.
 *
 * ## Why a mock is the default
 *
 * The phone is the access point while it relays, so reaching a cloud recognizer
 * depends on the cellular uplink (issues.md R4). The hub must therefore be
 * demonstrable with no key, no network and no cost — and, critically, with
 * REPRODUCIBLE output: a determinism bug in the relay would otherwise be
 * indistinguishable from engine noise.
 *
 * ## The determinism contract
 *
 * The pseudo-transcript is a pure function of the AUDIO BYTES:
 * `mock:<byte-length>:<fnv1a64-hex>`. There is no clock, no `Random`, no
 * instance counter and no network, so the same chunk yields the same string on
 * any device, in any process, on any run. Byte content matters, not identity:
 * two equal-length payloads with different bytes hash differently.
 *
 * ## Session lifecycle
 *
 * A session is OPENED implicitly by the first [transcribe] and CLOSED by
 * [close]. [close] is idempotent, and a chunk arriving after close is
 * ignored (returns `null`) rather than silently reviving the session — the relay
 * guarantees it never transcribes after close (T14), so this is a defensive
 * no-op, not a reachable state.
 */
class MockSttAdapter : SttPort {
    /** `sessionId -> true` means open. A CLOSED session is tracked to reject late chunks. */
    private val openSessions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Sessions explicitly closed; a late chunk must not reopen one. */
    private val closedSessions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun transcribe(
        sessionId: String,
        audioPcm16: ByteArray,
        sampleRateHz: Int,
    ): String? {
        if (sessionId in closedSessions) return null
        openSessions.add(sessionId)
        // An empty chunk carries no utterance. This is an ordinary outcome, not an
        // error: the contract forbids throwing here.
        if (audioPcm16.isEmpty()) return null
        return pseudoTranscript(audioPcm16)
    }

    override suspend fun close(sessionId: String) {
        openSessions.remove(sessionId)
        closedSessions.add(sessionId)
    }

    /**
     * Adapter-local observability (mirrors T8/T11 adapter state accessors): true
     * while [sessionId] has been opened and not closed. Not part of the port.
     */
    fun isSessionOpen(sessionId: String): Boolean = sessionId in openSessions

    /** Adapter-local observability: the engine's declared input rate. */
    fun sampleRateHz(): Int = SAMPLE_RATE_HZ

    /**
     * The deterministic pseudo-transcript: length + content hash, both derived
     * from the bytes alone. FNV-1a 64-bit is used explicitly because
     * `ByteArray.hashCode()` is identity-based and would break determinism.
     */
    private fun pseudoTranscript(audioPcm16: ByteArray): String = "mock:${audioPcm16.size}:${fnv1a64Hex(audioPcm16)}"

    private fun fnv1a64Hex(bytes: ByteArray): String {
        var hash = FNV_OFFSET_BASIS
        for (byte in bytes) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= FNV_PRIME
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }

    // Public so ConstantValuesTest can assert the FNV literals directly.
    companion object {
        /** FNV-1a 64-bit constants (public domain). */
        const val FNV_OFFSET_BASIS: Long = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        const val FNV_PRIME: Long = 0x100000001b3L
    }
}
