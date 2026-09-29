package com.glassstorm.phonemanager.testing.testkit

import java.io.File
import java.io.Reader
import java.util.concurrent.TimeUnit

/**
 * Bounded child-process runner for the Go reference peer.
 *
 * Lives in `:testing:testkit` (pure JVM, no dependencies) because `:app` cannot
 * see `:core:service`'s test sources: the T6 transport spike runs in
 * `:transport:grpc`, the T18 full E2E runs in BOTH `:transport:grpc` (JVM harness)
 * and `:app` (Robolectric hub), and all of them must spawn the child the same way.
 *
 * Two hazards are handled here so no caller has to:
 *
 *  * a hung child can never hang the suite — [DEADLINE_SECONDS] is enforced
 *    and an overrun is force-destroyed and reported as [TIMED_OUT];
 *  * a spawn failure (no Go toolchain) is a loud non-zero result, never a
 *    silent skip, because a skipped transport gate would report green over an
 *    unproven risk.
 */
data class ProcessRun(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val process: Process?,
) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Launch [command] in [workingDir] under a hard deadline, draining both pipes on
 * daemon threads so a verbose child cannot deadlock on a full OS pipe buffer.
 */
fun run(
    command: List<String>,
    workingDir: File,
): ProcessRun {
    val process =
        try {
            ProcessBuilder(command).directory(workingDir).redirectErrorStream(false).start()
        } catch (failure: Exception) {
            return ProcessRun(
                exitCode = ERROR,
                stdout = "",
                stderr = failure.message ?: "spawn failed",
                process = null,
            )
        }

    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val outThread = drain(process.inputStream.bufferedReader(), stdout)
    val errThread = drain(process.errorStream.bufferedReader(), stderr)

    val finished = process.waitFor(DEADLINE_SECONDS, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        process.waitFor(REAP_SECONDS, TimeUnit.SECONDS)
    }
    outThread.join(DRAIN_JOIN_MS)
    errThread.join(DRAIN_JOIN_MS)

    val exit = if (finished) process.exitValue() else TIMED_OUT
    val suffix = if (finished) "" else "\n[harness] child exceeded ${DEADLINE_SECONDS}s and was force-destroyed"
    return ProcessRun(exit, stdout.toString(), stderr.toString() + suffix, process)
}

/**
 * The Go toolchain binary: the pinned install first, then PATH.
 *
 * `/usr/local/go/bin/go` is not on a non-login shell's PATH, which is exactly
 * how the CI/agent shells here behave, so the pinned absolute path is preferred.
 */
fun goBinary(): String {
    val pinned = File("/usr/local/go/bin/go")
    return if (pinned.canExecute()) pinned.absolutePath else "go"
}

/** Resolve the repo root by walking up until the mockpeer Go module is found. */
fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        if (File(dir, "tools/mockpeer/go.mod").isFile) return dir
        dir = dir.parentFile
    }
    error("could not locate the repo root (tools/mockpeer/go.mod) from ${System.getProperty("user.dir")}")
}

private fun drain(
    reader: Reader,
    sink: StringBuilder,
): Thread {
    val thread =
        Thread {
            reader.use { source ->
                val buf = CharArray(4_096)
                while (true) {
                    val read = source.read(buf)
                    if (read < 0) break
                    synchronized(sink) { sink.append(buf, 0, read) }
                }
            }
        }
    thread.isDaemon = true
    thread.start()
    return thread
}

const val DEADLINE_SECONDS: Long = 90
const val REAP_SECONDS: Long = 5
const val DRAIN_JOIN_MS: Long = 2_000
const val TIMED_OUT: Int = 124
const val ERROR: Int = 1
