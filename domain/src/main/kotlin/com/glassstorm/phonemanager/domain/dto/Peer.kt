package com.glassstorm.phonemanager.domain.dto

/**
 * A reachable hub endpoint on the local network.
 *
 * Pure data; no Android types. [source] records HOW the address was found so
 * callers can tell a real mDNS discovery apart from the direct-IP gateway
 * fallback — the fallback is a first-class, expected outcome in the manual-tether
 * development flow, not an error.
 *
 * `source` is one of [SOURCE_MDNS] or [SOURCE_GATEWAY].
 */
data class PeerAddress(
    val host: String,
    val port: Int,
    val source: String,
) {
    companion object {
        /** The peer answered a real `_ecosys._tcp` mDNS resolution. */
        const val SOURCE_MDNS: String = "mdns"

        /** mDNS was unavailable or missed; the configured gateway IP was used instead. */
        const val SOURCE_GATEWAY: String = "gateway"
    }
}
