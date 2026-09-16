package com.glassstorm.phonemanager.domain.dto

/**
 * The speech-recognition engines the hub can be configured to use.
 *
 * A DOMAIN vocabulary, not the adapter's persistence vocabulary: `:app` selects
 * an engine through this enum and never learns how `:adapter` spells it on disk.
 * [MOCK] is the offline default and the only engine that never needs a key or a
 * network, so it is the safe value a fresh install resolves to.
 */
enum class SttEngine {
    /** Deterministic, fully offline pseudo-transcription. The v1 DEFAULT. */
    MOCK,

    /** Speechmatics realtime cloud recognition. Requires an API key + uplink. */
    SPEECHMATICS,
}

/**
 * How the hub should treat the access point.
 *
 * [MANUAL] leaves the tethered hotspot to the user (the documented primary dev
 * path, since OEMs frequently block programmatic LocalOnlyHotspot); [AUTO] asks
 * the hub to bring its own LocalOnlyHotspot up.
 */
enum class HotspotMode {
    /** The user owns the access point; the hub detects and adopts it. */
    MANUAL,

    /** The hub starts its own LocalOnlyHotspot. */
    AUTO,
}
