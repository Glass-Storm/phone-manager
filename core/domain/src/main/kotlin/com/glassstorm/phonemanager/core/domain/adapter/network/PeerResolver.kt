package com.glassstorm.phonemanager.core.domain.adapter.network

import com.glassstorm.phonemanager.core.model.PeerAddress

/**
 * Port (interface) for resolving a reachable peer address.
 *
 * Split out of the former fat `Discovery` port so each port owns ONE capability:
 * [Discovery] advertises this hub; [PeerResolver] resolves a peer. The Android
 * implementation (`NsdDiscoveryAdapter`) implements BOTH narrow ports over one
 * NsdManager + state machine.
 *
 * [resolveFirst] MUST NOT throw when mDNS misses or is unavailable: the direct-IP
 * fallback is a normal code path. It returns `null` only when neither mDNS nor a
 * configured gateway can produce an address.
 */
interface PeerResolver {
    fun resolveFirst(timeoutMs: Long): PeerAddress?
}
