package com.glassstorm.phonemanager.domain.adapter.transport

/**
 * Port (interface) for the hub's network listener lifecycle.
 *
 * Owned by `:domain` and deliberately Android-free: the JVM/in-process
 * implementation used by tests and the Android implementation used by the app
 * both satisfy this shape, and `:app` resolves whichever one it composed through
 * the Context registry.
 *
 * [GoStop] MUST be idempotent, and [GoBoundPort] reports the port actually bound
 * (`0` when stopped) — callers use it after a `GoStart(0)` ephemeral-port bind.
 */
interface HubServer {
    /** Start listening on [port] (`0` = ephemeral). Idempotent while already running. */
    fun GoStart(port: Int)

    /** Stop listening and release the port. Idempotent when already stopped. */
    fun GoStop()

    /** Whether the listener is currently accepting connections. */
    fun GoIsRunning(): Boolean

    /** The bound TCP port, or `0` when not running. */
    fun GoBoundPort(): Int
}
