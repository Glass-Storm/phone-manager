package com.glassstorm.phonemanager.core.model

/**
 * A device paired with this phone hub.
 *
 * Pure data; no behaviour. The hub never stores the pairing token itself — only
 * its hash ([tokenHash]) — and tracks the pairing instant plus the last time
 * the device was seen (`null` until the first heartbeat after pairing).
 */
data class Device(
    val deviceId: String,
    val deviceName: String,
    val role: String,
    val tokenHash: String,
    val pairedAtMs: Long,
    val lastSeenMs: Long?,
)
