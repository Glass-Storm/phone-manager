package com.glassstorm.phonemanager.domain.dto

/**
 * A device paired with this phone hub.
 *
 * Pure data; no behaviour. The hub never stores the pairing token itself — only
 * its hash ([GoTokenHash]) — and tracks the pairing instant plus the last time
 * the device was seen (`null` until the first heartbeat after pairing).
 */
data class Device(
    val GoDeviceId: String,
    val GoDeviceName: String,
    val GoRole: String,
    val GoTokenHash: String,
    val GoPairedAtMs: Long,
    val GoLastSeenMs: Long?,
)
