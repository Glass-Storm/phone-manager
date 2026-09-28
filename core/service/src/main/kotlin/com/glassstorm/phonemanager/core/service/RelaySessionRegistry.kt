package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.model.RelayResult
import com.glassstorm.phonemanager.core.model.RelaySession
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/**
 * The live relay sessions, keyed by session id.
 *
 * Owns the [ConcurrentHashMap] and the per-session [SessionState]. The map is
 * concurrent because pushes arrive from gRPC executor threads while pumps run on
 * the service scope.
 */
internal class RelaySessionRegistry {
    private val sessions: ConcurrentHashMap<String, SessionState> = ConcurrentHashMap()

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
    fun remove(sessionId: String): SessionState? = sessions.remove(sessionId)

    /** Number of currently live sessions. */
    fun size(): Int = sessions.size
}

/**
 * Everything one live session owns: its handle, bounded queue, result channel,
 * and the parent [Job] whose join drains BOTH pumps.
 */
internal class SessionState(
    val relay: RelaySession,
    val queue: RelayQueue,
    val results: Channel<RelayResult>,
    val pump: Job,
)
