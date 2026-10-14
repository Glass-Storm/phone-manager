package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.model.PeerRole
import com.glassstorm.phonemanager.core.model.RelayOutput
import com.glassstorm.phonemanager.core.model.RelaySession
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/**
 * The live relay sessions, keyed by session id AND by the peer role each session
 * bound from its `stream_open` frame.
 *
 * The role index is what lets the hub address the OTHER peer's session: an image
 * arriving on the necklace session resolves the glasses session by
 * [sessionFor], and vice versa. The maps are concurrent because pushes arrive
 * from gRPC executor threads while pumps run on the service scope.
 */
internal class RelaySessionRegistry {
    private val sessions: ConcurrentHashMap<String, SessionState> = ConcurrentHashMap()
    private val roleSessions: ConcurrentHashMap<PeerRole, String> = ConcurrentHashMap()

    /** Track [state] under [sessionId], replacing any existing entry. */
    fun register(
        sessionId: String,
        state: SessionState,
    ) {
        sessions[sessionId] = state
    }

    /** The state for [sessionId], or `null` when no session is live. */
    fun find(sessionId: String): SessionState? = sessions[sessionId]

    /** Remove and return the state for [sessionId], or `null` when absent. */
    fun remove(sessionId: String): SessionState? {
        val removed = sessions.remove(sessionId) ?: return null
        roleSessions.entries.removeIf { it.value == sessionId }
        return removed
    }

    /** Number of currently live sessions. */
    fun size(): Int = sessions.size

    /** Bind [role] to [sessionId] so a later [sessionFor] resolves it. No-op for [PeerRole.UNSPECIFIED]. */
    fun bindRole(
        sessionId: String,
        role: PeerRole,
    ) {
        if (role == PeerRole.UNSPECIFIED) return
        roleSessions[role] = sessionId
    }

    /** The session id currently bound to [role], or `null` when none is. */
    fun sessionFor(role: PeerRole): String? = roleSessions[role]
}

/**
 * Everything one live session owns: its handle, bounded queue, result channel,
 * and the parent [Job] whose join drains ALL pumps.
 */
internal class SessionState(
    val relay: RelaySession,
    val queue: RelayQueue,
    val results: Channel<RelayOutput>,
    val pump: Job,
)
