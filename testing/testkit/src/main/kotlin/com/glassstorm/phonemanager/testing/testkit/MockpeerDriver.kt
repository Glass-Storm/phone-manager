package com.glassstorm.phonemanager.testing.testkit

import java.io.File

/**
 * Drives the Go reference peer through the full scenario and renders the ordered,
 * machine-checkable evidence transcript.
 *
 * Shared by the `:transport:grpc` JVM harness and the `:app` Robolectric hub
 * suite so the two harnesses cannot drift: they differ ONLY in how the hub is
 * hosted, never in what the peer is asked to do or how the result is asserted.
 *
 * Dependency-free by design (no `:core:domain` edge), so an Android module can
 * consume it. The stream is UNAUTHENTICATED: the peer sends a `stream_open`
 * bootstrap frame rather than pairing and carrying a bearer token.
 *
 * @param port the bound hub port the peer dials on `127.0.0.1`.
 */
class MockPeerDriver(
    private val port: Int,
) {
    /** Every child process launched, so the caller can reap them in teardown. */
    private val children: MutableList<Process> = mutableListOf()

    /** The invocation's result, read by the caller for byte-level assertions. */
    lateinit var firstRun: ProcessRun
        private set

    /**
     * The full scenario: OpenStream (audio+video) -> session-ok. The stream is
     * opened with NO token; the `stream_open` frame binds the peer's identity.
     */
    fun openStream(frames: Int = 10): ProcessRun {
        firstRun =
            mockPeer(
                "--addr",
                "127.0.0.1:$port",
                "--scenario",
                "full",
                "--mode",
                "both",
                "--frames",
                "$frames",
                "--timeout",
                "60",
            )
        return firstRun
    }

    /** The two lines the full scenario prints, in order. */
    fun fullLines(run: ProcessRun = firstRun): List<String> =
        listOf(
            "stream-open-ok",
            "session-ok frames=${count(run.stdout, "frames")}",
        )

    /** Register an externally launched child so [reap] still guarantees reaping. */
    fun registerForReaping(process: Process) {
        children += process
    }

    /** Reap every child. Never throws. */
    fun reap() {
        children.forEach { child ->
            runCatching {
                if (child.isAlive) child.destroyForcibly()
                child.waitFor(REAP_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        children.clear()
    }

    private fun mockPeer(vararg args: String): ProcessRun {
        val command = listOf(goBinary(), "run", "./tools/mockpeer") + args
        val result = run(command, repoRoot())
        result.process?.let { children += it }
        return result
    }

    private fun count(
        stdout: String,
        name: String,
    ): Int {
        val match =
            Regex("""$name=(\d+)""").find(stdout)
                ?: error("mockpeer stdout did not carry `$name=<int>`: $stdout")
        return match.groupValues[1].toInt()
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
    fun lines(
        streamOpenOk: Boolean,
        frames: Int,
        videoBytesMatch: Boolean,
    ): List<String> =
        listOf(
            if (streamOpenOk) "stream-open-ok" else "stream-open-missing",
            "session-ok frames=$frames",
            if (videoBytesMatch) "video-bytes-match" else "video-bytes-mismatch",
        )

    /** Append the harness's transcript to the evidence file as ONE atomic append. */
    fun write(
        evidencePath: String,
        harness: String,
        lines: List<String>,
    ) {
        val file = File(evidencePath).absoluteFile
        file.parentFile?.mkdirs()
        val block =
            buildString {
                appendLine("=== $harness ===")
                lines.forEach { appendLine(it) }
            }
        file.appendBytes(block.toByteArray())
    }
}
