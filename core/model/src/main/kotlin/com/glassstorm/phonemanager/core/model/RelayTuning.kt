package com.glassstorm.phonemanager.core.model

/**
 * Relay buffer depths for one hub process.
 *
 * The three media kinds have deliberately different backpressure policies, so they need different
 * depths: audio is never dropped, video drops its oldest frame, and images follow the recogniser's
 * shape.
 *
 * ### Why image depth is a choice, not a constant
 * The single-image MediaPipe recogniser wants ONE still ([singleImage]): every newer still evicts the
 * older one, so the recogniser always works on the newest frame and its lag stays bounded by one
 * inference. A sequence recogniser (the Shuwa 16-frame window) is the opposite: it needs the
 * **last N stills in order**, so the hub is configured with [sequence] while that port is bound.
 *
 * Choosing the depth at the composition root keeps the "no backlog" guarantee intact for the
 * single-image path instead of weakening it for everyone.
 *
 * @property audioCapacity PCM chunks buffered before the producer parks.
 * @property videoCapacity H.264 NALs buffered before the oldest is evicted.
 * @property imageCapacity JPEG stills buffered before the oldest is evicted.
 */
data class RelayTuning(
    val audioCapacity: Int = DEFAULT_AUDIO_CAPACITY,
    val videoCapacity: Int = DEFAULT_VIDEO_CAPACITY,
    val imageCapacity: Int = DEFAULT_IMAGE_CAPACITY,
) {
    init {
        require(audioCapacity > 0) { "audioCapacity must be positive" }
        require(videoCapacity > 0) { "videoCapacity must be positive" }
        require(imageCapacity > 0) { "imageCapacity must be positive" }
    }

    companion object {
        /** Audio buffer depth in frames. Generous: audio must never be dropped. */
        const val DEFAULT_AUDIO_CAPACITY: Int = 64

        /** Video buffer depth in NALs. Beyond this the oldest frame is evicted. */
        const val DEFAULT_VIDEO_CAPACITY: Int = 256

        /** Image depth for the single-image recogniser: one still, latest wins. */
        const val DEFAULT_IMAGE_CAPACITY: Int = 1

        /** Image depth for a sequence recogniser: the full Shuwa window of 16 stills. */
        const val SEQUENCE_IMAGE_CAPACITY: Int = 16

        /** The production default: single-image recognition, latest wins. */
        val singleImage: RelayTuning = RelayTuning()

        /** Tuning for a 16-frame sequence recogniser bound to the gesture port. */
        val sequence: RelayTuning = RelayTuning(imageCapacity = SEQUENCE_IMAGE_CAPACITY)
    }
}
