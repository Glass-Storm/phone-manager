package com.glassstorm.phonemanager.core.domain.adapter.network

/**
 * Port (interface) for the hub-side ADVERTISE capability only.
 *
 * Owned by `:core:domain`; the Android implementation (NsdManager over
 * `_ecosys._tcp`) lives in `:adapter` and is resolved through the Context
 * registry. Peer resolution is a separate narrow port, [PeerResolver].
 */
interface Discovery {
    /** Advertise this hub under [name] on [port]. Idempotent for a repeating name/port. */
    fun advertise(
        name: String,
        port: Int,
    )

    /** Stop advertising. Idempotent: safe to call when never started. */
    fun stopAdvertise()
}
