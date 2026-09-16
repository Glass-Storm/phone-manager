package com.glassstorm.phonemanager.domain.adapter.network

import com.glassstorm.phonemanager.domain.dto.PeerAddress

/**
 * Port (interface) for local-network peer discovery.
 *
 * Owned by `:domain`; the Android implementation (NsdManager over
 * `_ecosys._tcp`, with a first-class direct-IP gateway fallback) lives in
 * `:adapter` and is resolved through the Context registry.
 *
 * [GoResolveFirst] MUST NOT throw when mDNS misses or is unavailable: the
 * direct-IP fallback is a normal code path. It returns `null` only when neither
 * mDNS nor a configured gateway can produce an address.
 */
interface Discovery {
    /** Advertise this hub under [name] on [port]. Idempotent for a repeating name/port. */
    fun GoAdvertise(
        name: String,
        port: Int,
    )

    /** Stop advertising. Idempotent: safe to call when never started. */
    fun GoStopAdvertise()

    /**
     * Resolve the first reachable peer, giving mDNS at most [timeoutMs].
     *
     * On an mDNS miss/timeout this falls back to the configured gateway address,
     * so the returned [PeerAddress.GoSource] is `"gateway"` rather than `"mdns"`.
     * Returns `null` when no fallback is configured either.
     */
    fun GoResolveFirst(timeoutMs: Long): PeerAddress?
}
