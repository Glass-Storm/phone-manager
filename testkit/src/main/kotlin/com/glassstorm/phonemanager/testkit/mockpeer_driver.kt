package com.glassstorm.phonemanager.testkit

import java.io.File

/**
 * Drives the Go reference peer through the full T18 scenario and renders the
 * ordered, machine-checkable evidence transcript.
 *
 * Shared by the `:service` JVM harness and the `:app` Robolectric hub suite so the
 * two harnesses cannot drift: they differ ONLY in how the hub is hosted, never in
 * what the peer is asked to do or how the result is asserted.
 *
 * Dependency-free by design (no `:domain` edge), so an Android module can consume
 * it: the caller supplies closures for its own pairing surface.
 *
 * ## Token handling
 *
 * The bearer token is an ephemeral loopback secret. It is passed peer-to-peer via
 * `--token-out` / `--token-file` (0600) and NEVER appears on stdout, in a log, or
 * in the evidence transcript.
 *
 * @param GoPort the bound hub port the peer dials on `127.0.0.1`.
 * @param GoOpenWindow opens a pairing window and returns its PIN.
 * @param GoRevokeAll revokes every paired device (the post-revoke leg).
 */
class MockPeerDriver(
    private val GoPort: Int,
    private val GoOpenWindow: () -> String,
    private val GoRevokeAll: () -> Unit,
) {

    /** Every child process launched, so the caller can reap them in teardown. */
    private val GoChildren: MutableList<Process> = mutableListOf()

    /** Per-driver token path: unique, so concurrent suites can never collide. */
    private val GoTokenFile: File by lazy {
        File(
            File(System.getProperty("java.io.tmpdir"), "mockpeer-token").apply { mkdirs() },
            "revoked-leg-${java.util.UUID.randomUUID()}.token",
        )
    }

    /** The first invocation's result, read by the caller for byte-level assertions. */
    lateinit var GoFirstRun: GoProcessRun
        private set

    /** The post-revoke invocation's result. */
    lateinit var GoRevokedRun: GoProcessRun
        private set

    /**
     * Invocation 1: pair -> heartbeat -> OpenStream (audio+video) -> session-ok.
     * The freshly issued token is written to a 0600 temp file, never to stdout.
     */
    fun GoPairHeartbeatStream(frames: Int = 10): GoProcessRun {
        GoFirstRun = GoMockPeer(
            "--addr", "127.0.0.1:$GoPort",
            "--pin", GoOpenWindow(),
            "--scenario", "full",
            "--mode", "both",
            "--frames", "$frames",
            "--token-out", GoTokenFile.absolutePath,
            "--timeout", "60",
        )
        return GoFirstRun
    }

    /**
     * Invocation 2: the SAME token, after every device has been revoked. The hub
     * MUST now refuse, and the peer MUST say `UNAUTHENTICATED` with a non-zero exit.
     */
    fun GoRevokedLeg(): GoProcessRun {
        GoRevokeAll()
        GoRevokedRun = GoMockPeer(
            "--addr", "127.0.0.1:$GoPort",
            "--scenario", "full",
            "--token-file", GoTokenFile.absolutePath,
            "--expect-unauthenticated",
            "--timeout", "30",
        )
        return GoRevokedRun
    }

    /** The three media lines the full scenario prints, in order. */
    fun GoFullLines(run: GoProcessRun = GoFirstRun): List<String> = listOf(
        "pair-ok",
        "heartbeat-ok",
        "session-ok frames=${GoCount(run.stdout, "frames")}",
    )

    /** Register an externally launched child so [GoReap] still guarantees reaping. */
    fun GoRegisterForReaping(process: Process) {
        GoChildren += process
    }

    /** Delete the ephemeral token file and reap every child. Never throws. */
    fun GoReap() {
        runCatching { GoTokenFile.delete() }
        GoChildren.forEach { child ->
            runCatching {
                if (child.isAlive) child.destroyForcibly()
                child.waitFor(GO_REAP_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        GoChildren.clear()
    }

    private fun GoMockPeer(vararg args: String): GoProcessRun {
        val GoCommand = listOf(GoGoBinary(), "run", "./tools/mockpeer") + args
        val GoResult = GoRun(GoCommand, GoRepoRoot())
        GoResult.process?.let { GoChildren += it }
        return GoResult
    }

    private fun GoCount(stdout: String, name: String): Int {
        val GoMatch = Regex("""$name=(\d+)""").find(stdout)
            ?: error("mockpeer stdout did not carry `$name=<int>`: $stdout")
        return GoMatch.groupValues[1].toInt()
    }
}

/**
 * The ONE evidence contract both harnesses emit.
 *
 * Writes a single O_APPEND blob per harness, so two JVMs appending to the same
 * evidence file cannot interleave: the ordered lines are asserted by the caller
 * and written here verbatim.
 */
object MockPeerTranscript {

    /** The frozen ordered sequence. */
    fun GoLines(
        pairOk: Boolean,
        heartbeatOk: Boolean,
        frames: Int,
        videoBytesMatch: Boolean,
        revokedUnauthenticated: Boolean,
    ): List<String> = listOf(
        if (pairOk) "pair-ok" else "pair-missing",
        if (heartbeatOk) "heartbeat-ok" else "heartbeat-missing",
        "session-ok frames=$frames",
        if (videoBytesMatch) "video-bytes-match" else "video-bytes-mismatch",
        if (revokedUnauthenticated) "revoked->UNAUTHENTICATED" else "revoked->ACCEPTED",
    )

    /** Append the harness's transcript to the evidence file as ONE atomic append. */
    fun GoWrite(evidencePath: String, harness: String, lines: List<String>) {
        val GoFile = File(evidencePath).absoluteFile
        GoFile.parentFile?.mkdirs()
        val GoBlock = buildString {
            appendLine("=== $harness ===")
            lines.forEach { appendLine(it) }
        }
        GoFile.appendBytes(GoBlock.toByteArray())
    }
}
