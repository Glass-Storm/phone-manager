package com.glassstorm.phonemanager.core.model

/**
 * Everything a peer needs to join the phone-hosted access point.
 *
 * Pure data; no Android types. `gatewayIp` is the phone-as-gateway address on the
 * AP interface (typically `192.168.43.1`) — there is no SDK API that returns it, so
 * the adapter discovers it by enumerating network interfaces.
 */
data class HotspotInfo(
    val ssid: String,
    val passphrase: String,
    val gatewayIp: String,
)
