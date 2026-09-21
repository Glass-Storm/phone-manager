package com.glassstorm.phonemanager.adapter.relay

import com.glassstorm.phonemanager.domain.adapter.relay.FrameSink

/**
 * The v1 [FrameSink]: accept the opaque H.264 NAL and DROP it.
 *
 * ## Why "discard" is the correct v1 adapter, not a stub
 *
 * The hub's job in v1 is RELAY, not MEDIA PROCESSING. A NAL arriving from a glass
 * peer is routed to the sink, and the [FrameSink] contract forbids decoding and
 * re-encoding; there is no recorder, no preview and no second-hop forwarder in
 * scope. So the honest implementation of "nothing consumes video yet" is an
 * adapter that consumes it and keeps no state: it cannot leak memory on a long
 * session, cannot corrupt the payload, and cannot make a false claim that media is
 * being stored.
 *
 * The drop is deliberate and documented, never an accident: v1 does not persist
 * or display media, and a future recorder swaps in here WITHOUT touching
 * `:service` — the relay only ever sees the [FrameSink] port.
 */
class DiscardingFrameSink : FrameSink {
    /**
     * Accept and drop one opaque NAL.
     *
     * The parameter is intentionally untouched: no copy, no inspection, no
     * decode. The relay's byte-exactness guarantee is what the E2E asserts at the
     * port boundary.
     */
    override fun GoAcceptVideo(
        sessionId: String,
        h264Nal: ByteArray,
    ) = Unit
}
