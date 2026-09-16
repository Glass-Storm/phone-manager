package com.glassstorm.phonemanager.service

import java.io.File
import java.io.Reader
import java.util.concurrent.TimeUnit

/**
 * Bounded child-process runner for the Go reference peer.
 *
 * Shared by the T6 transport spike and the T18 full E2E, which both drive
 * `tools/mockpeer` over a real socket. Two hazards are handled here so no caller
 * has to:
 *
 *  * a hung child can never hang the suite — [GO_DEADLINE_SECONDS] is enforced
 *    and an overrun is force-destroyed and reported as [GO_TIMED_OUT];
 *  * a spawn failure (no Go toolchain) is a loud non-zero result, never a
 *    silent skip, because a skipped transport gate would report green over an
 *    unproven risk.
 */
data class GoProcessRun(
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
fun GoRun(command: List<String>, workingDir: File): GoProcessRun {
    val GoProcess = try {
        ProcessBuilder(command).directory(workingDir).redirectErrorStream(false).start()
    } catch (GoFailure: Exception) {
        return GoProcessRun(
            exitCode = GO_ERROR,
            stdout = "",
            stderr = GoFailure.message ?: "spawn failed",
            process = null,
        )
    }

    val GoStdout = StringBuilder()
    val GoStderr = StringBuilder()
    val GoOutThread = GoDrain(GoProcess.inputStream.bufferedReader(), GoStdout)
    val GoErrThread = GoDrain(GoProcess.errorStream.bufferedReader(), GoStderr)

    val GoFinished = GoProcess.waitFor(GO_DEADLINE_SECONDS, TimeUnit.SECONDS)
    if (!GoFinished) {
        GoProcess.destroyForcibly()
        GoProcess.waitFor(GO_REAP_SECONDS, TimeUnit.SECONDS)
    }
    GoOutThread.join(GO_DRAIN_JOIN_MS)
    GoErrThread.join(GO_DRAIN_JOIN_MS)

    val GoExit = if (GoFinished) GoProcess.exitValue() else GO_TIMED_OUT
    val GoSuffix = if (GoFinished) "" else "\n[harness] child exceeded ${GO_DEADLINE_SECONDS}s and was force-destroyed"
    return GoProcessRun(GoExit, GoStdout.toString(), GoStderr.toString() + GoSuffix, GoProcess)
}

/**
 * The Go toolchain binary: the pinned install first, then PATH.
 *
 * `/usr/local/go/bin/go` is not on a non-login shell's PATH, which is exactly
 * how the CI/agent shells here behave, so the pinned absolute path is preferred.
 */
fun GoGoBinary(): String {
    val GoPinned = File("/usr/local/go/bin/go")
    return if (GoPinned.canExecute()) GoPinned.absolutePath else "go"
}

/** Resolve the repo root by walking up until the mockpeer Go module is found. */
fun GoRepoRoot(): File {
    var GoDir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (GoDir != null) {
        if (File(GoDir, "tools/mockpeer/go.mod").isFile) return GoDir
        GoDir = GoDir.parentFile
    }
    error("could not locate the repo root (tools/mockpeer/go.mod) from ${System.getProperty("user.dir")}")
}

private fun GoDrain(reader: Reader, sink: StringBuilder): Thread {
    val GoThread = Thread {
        reader.use { GoSource ->
            val GoBuf = CharArray(4_096)
            while (true) {
                val GoRead = GoSource.read(GoBuf)
                if (GoRead < 0) break
                synchronized(sink) { sink.append(GoBuf, 0, GoRead) }
            }
        }
    }
    GoThread.isDaemon = true
    GoThread.start()
    return GoThread
}

const val GO_DEADLINE_SECONDS: Long = 90
const val GO_REAP_SECONDS: Long = 5
const val GO_DRAIN_JOIN_MS: Long = 2_000
const val GO_TIMED_OUT: Int = 124
const val GO_ERROR: Int = 1
