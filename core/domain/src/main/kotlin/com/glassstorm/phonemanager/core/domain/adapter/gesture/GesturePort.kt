package com.glassstorm.phonemanager.core.domain.adapter.gesture

import com.glassstorm.phonemanager.core.model.GestureOutcome

/**
 * Port (interface) for static-gesture recognition.
 *
 * Owned by `:core:domain`; implemented in an adapter (the deterministic JVM fake
 * by default, the on-device ai-android engine on a device). Defining the seam
 * here keeps `:core:service` free of any concrete engine.
 *
 * The input is a complete **JPEG still** (the hub decodes it inside the adapter,
 * never in the relay's `FrameSink`). Implementations MUST return `null` when the
 * image carries no recognizable hand rather than throwing.
 */
interface GesturePort {
    /**
     * Recognizes the top static gesture in [jpeg].
     *
     * Returns the recognized [GestureOutcome], or `null` when no hand or no
     * gesture was detected. Implementations MUST NOT throw on an ordinary `null`
     * outcome.
     */
    suspend fun recognize(jpeg: ByteArray): GestureOutcome?

    /**
     * Optional warm-up: loads the engine's native models before the first still.
     *
     * The default is a no-op, so a fake or a single-image engine needs no change;
     * a sequence engine overrides it to move its cold-start load off the first
     * recognized still, where it would otherwise block the image pump.
     */
    suspend fun warmUp() {}

    /**
     * Whether the recogniser is currently mid-sign and needs every queued frame in
     * order. When `false`, the pump collapses any queued backlog to the newest
     * still so a sign never starts behind stale idle frames.
     */
    val retainsSequence: Boolean get() = false
}
