package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.model.LivePeer
import com.glassstorm.phonemanager.core.model.PeerRole
import com.glassstorm.phonemanager.core.model.RelayOutput
import com.glassstorm.phonemanager.core.model.RelaySession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The live relay sessions, keyed by session id AND by the peer role each session
 * bound from its `stream_open` frame.
 *
 * The role index is what lets the hub address the OTHER peer's session: an image
 * arriving on the necklace session resolves the glasses session by
 * [sessionFor], and vice versa. The maps are concurrent because pushes arrive
 * from gRPC executor threads while pumps run on the service scope.
 *
 * The role index keeps a LIFO history of the live sessions bound to each role
 * instead of a single last-write-wins entry. Android opens transient GLASS
 * sessions (the debug and enrollment screens) alongside the standing one; when a
 * newer session closes, [remove] pops it and the most recent SURVIVING session
 * becomes the holder again, so the live glasses never silently loses routing
 * until it reconnects.
 */
internal class RelaySessionRegistry {
    private val sessions: ConcurrentHashMap<String, SessionState> = ConcurrentHashMap()
    private val roleLock = Any()
    private val roleHistory: MutableMap<PeerRole, ArrayDeque<String>> = mutableMapOf()

    /** Track [state] under [sessionId], replacing any existing entry. */
    fun register(
        sessionId: String,
        state: SessionState,
    ) {
        sessions[sessionId] = state
    }

    /** The state for [sessionId], or `null` when no session is live. */
    fun find(sessionId: String): SessionState? = sessions[sessionId]

    /**
     * Mark [sessionId] as CLOSING and return its state, or `null` when it is
     * unknown or already closing.
     *
     * The session STAYS in the registry (and keeps its role binding) for the whole
     * drain window: a half-closed peer is still reading, so the TTS produced by a
     * still drained at close must still resolve to it. Only the first caller wins
     * the clear-to-CLOSING transition, so repeated closes stay a no-op.
     */
    fun markClosing(sessionId: String): SessionState? {
        val state = sessions[sessionId] ?: return null
        return if (state.tryMarkClosing()) state else null
    }

    /**
     * Remove and return the state for [sessionId], or `null` when absent.
     *
     * Pops the session from its role's LIFO history; the next-most-recent
     * surviving session becomes that role's holder, or the role is cleared when
     * none survive.
     */
    fun remove(sessionId: String): SessionState? {
        val removed = sessions.remove(sessionId) ?: return null
        synchronized(roleLock) {
            val iterator = roleHistory.entries.iterator()
            while (iterator.hasNext()) {
                val history = iterator.next().value
                history.remove(sessionId)
                if (history.isEmpty()) iterator.remove()
            }
        }
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
        synchronized(roleLock) {
            val history = roleHistory.getOrPut(role) { ArrayDeque() }
            // Re-binding an already-tracked session moves it to the top instead of
            // duplicating it, so a single remove pops it exactly once.
            history.remove(sessionId)
            history.addLast(sessionId)
        }
    }

    /** The session id currently bound to [role], or `null` when none is. */
    fun sessionFor(role: PeerRole): String? = synchronized(roleLock) { roleHistory[role]?.lastOrNull() }

    /**
     * Snapshot of every live session as a [LivePeer], resolving the role each one
     * currently holds through the top of [roleHistory]. A session with no bound
     * role is reported as [PeerRole.UNSPECIFIED], never omitted.
     */
    fun livePeers(): List<LivePeer> {
        val rolesBySession =
            synchronized(roleLock) {
                roleHistory.entries
                    .mapNotNull { (role, history) -> history.lastOrNull()?.let { it to role } }
                    .toMap()
            }
        return sessions.values
            .map { it.relay }
            .sortedBy { it.deviceId }
            .map { LivePeer(deviceId = it.deviceId, role = rolesBySession[it.sessionId] ?: PeerRole.UNSPECIFIED) }
    }
}

/**
 * Everything one live session owns: its handle, bounded queue, result channel,
 * the latest-wins TTS handoff, the signal that the still queue is drained, and
 * the parent [Job] whose join drains ALL pumps.
 *
 * [isClosing] is the half-close latch: `closeSession` flips it ONCE, which makes
 * every new push a no-op while the session stays resolvable for delivery during
 * the drain window.
 */
internal class SessionState(
    val relay: RelaySession,
    val queue: RelayQueue,
    val results: Channel<RelayOutput>,
    val tts: Channel<String>,
    val imageDrained: CompletableDeferred<Unit>,
    val pump: Job,
) {
    private val closing = AtomicBoolean(false)

    /** Whether [closeSession] has started draining this session. */
    val isClosing: Boolean get() = closing.get()

    /** Flip CLOSING on exactly once: `true` for the single caller that owns the drain. */
    fun tryMarkClosing(): Boolean = closing.compareAndSet(false, true)
}
