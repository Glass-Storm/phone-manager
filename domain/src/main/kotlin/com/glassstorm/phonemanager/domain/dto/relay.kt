package com.glassstorm.phonemanager.domain.dto

/**
 * A live media relay session bound to one paired device. Pure data.
 *
 * [GoSessionId] is minted by the hub per inbound stream so audio and video
 * frames from the same peer can be correlated and torn down together.
 */
data class RelaySession(
    val GoSessionId: String,
    val GoDeviceId: String,
)

/**
 * One recognized utterance produced from an audio frame. Pure data; mirrors the
 * `ecosys.v1.StreamResult` message without depending on the generated types.
 */
data class RelayResult(
    val GoText: String,
    val GoSpeakerLabel: String,
    val GoPtsMs: Long,
)
