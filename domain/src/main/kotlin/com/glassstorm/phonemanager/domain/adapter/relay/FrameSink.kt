package com.glassstorm.phonemanager.domain.adapter.relay

/**
 * Port (interface) for the video half of the relay.
 *
 * Owned by `:domain`; the consumer (a recorder, a local preview, or a
 * pass-through to another peer) is implemented in `:adapter` and resolved
 * through the Context registry.
 *
 * Video payloads are OPAQUE H.264 NAL units: an implementation MUST NOT decode
 * and MUST NOT re-encode — it stores or forwards the exact bytes it is given.
 */
interface FrameSink {
    /** Accept one opaque H.264 NAL for [sessionId], byte-for-byte unchanged. */
    fun GoAcceptVideo(
        sessionId: String,
        h264Nal: ByteArray,
    )
}
