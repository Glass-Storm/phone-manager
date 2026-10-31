package com.glassstorm.phonemanager

import java.io.File
import java.io.Reader
import java.util.concurrent.TimeUnit

/**
 * Child-process runner for the REAL Python `ecosys_pi` CLI (T23 cross-repo E2E).
 *
 * This mirrors the bounded `run()`/`drain` pattern of
 * `testing/testkit`'s `MockpeerRunner` (a verbose child can never deadlock the
 * suite, and an overrun is force-destroyed), and adds the two things the Python
 * client needs and the Go peer did not:
 *
 *  * a per-case ENVIRONMENT, so each case can point the client at the loopback
 *    hub and select the hermetic synthetic media/playback backends;
 *  * a configurable deadline — `uv run` has to resolve the project and start
 *    CPython, which is slower than the compiled Go peer.
 *
 * The Python CLI is launched from the Pi repo ROOT so `uv` resolves its
 * `pyproject.toml`, via the absolute `uv` path (the agent shell does not have
 * `~/.local/bin` reliably on PATH).
 *
 * ## The unauthenticated, device-free posture
 *
 * Pairing/PIN and bearer tokens were removed from the hub, so the CLI no longer
 * pairs. It dials an explicit `--hub` (loopback here) instead of discovering the
 * gateway, and the synthetic JPEG source plus the injected playback backend mean
 * no camera, no `nmcli` and no audio hardware are needed.
 */
data class CliRun(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == EcosysPi.EXIT_OK

    /** Every contract line on stdout, trimmed and non-empty, in order. */
    fun lines(): List<String> =
        stdout
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}

object EcosysPi {
    /** The Pi repo root (`uv` working directory). Overridable for other hosts. */
    val PROJECT_ROOT: File = File(System.getenv("PI_ECOSYS_REPO") ?: DEFAULT_PROJECT_ROOT)

    /** The `uv` binary. Overridable for other hosts. */
    val UV: File = File(System.getenv("UV_BIN") ?: DEFAULT_UV)

    /** `uv` binary's pinned install path (not on a non-login shell's PATH). */
    const val DEFAULT_UV: String = "/home/chaos/.local/bin/uv"

    /** Default Pi repo root (the `--hub` sibling repo this harness drives). */
    const val DEFAULT_PROJECT_ROOT: String = "/home/chaos/workplace/fyp/necklace-data-processing-raspberrypi-side-"

    /** The Pi entry point that must exist for the suite to run. */
    const val CLI_MODULE_RELATIVE: String = "src/ecosys_pi/cli.py"

    /** `PI_ECOSYS_HUB` — an explicit `host:port` that supersedes gateway discovery. */
    const val HUB_ENV: String = "PI_ECOSYS_HUB"

    /** `PI_ECOSYS_VIDEO_SOURCE` — the camera-free deterministic JPEG source. */
    const val VIDEO_SOURCE_ENV: String = "PI_ECOSYS_VIDEO_SOURCE"

    /** Value of [VIDEO_SOURCE_ENV] selecting the synthetic (no-camera) JPEG source. */
    const val VIDEO_SOURCE_SYNTHETIC: String = "synthetic"

    /** `PI_ECOSYS_PLAYER` — the playback backend selector. */
    const val PLAYER_ENV: String = "PI_ECOSYS_PLAYER"

    /** Value of [PLAYER_ENV] selecting the `aplay` backend (the one we shim). */
    const val PLAYER_APLAY: String = "aplay"

    /** The `aplay` shim's evidence sink: it writes the PCM it is handed to this path. */
    const val APLAY_EVIDENCE_ENV: String = "ECOSYS_APLAY_EVIDENCE"

    const val DEADLINE_SECONDS: Long = 120
    const val REAP_SECONDS: Long = 5
    const val DRAIN_JOIN_MS: Long = 2_000
    const val TIMED_OUT: Int = 124
    const val SPAWN_ERROR: Int = 1
    const val EXIT_OK: Int = 0
}

/**
 * Launch the CLI under a hard deadline, draining both pipes on daemon threads.
 *
 * @param args the CLI arguments (without `python -m ecosys_pi`).
 * @param environment extra variables merged over the inherited environment.
 * @param deadlineSeconds how long the child may run before it is force-destroyed.
 */
fun runEcosysPi(
    args: List<String>,
    environment: Map<String, String> = emptyMap(),
    deadlineSeconds: Long = EcosysPi.DEADLINE_SECONDS,
    workingDir: File = EcosysPi.PROJECT_ROOT,
): CliRun {
    val command = listOf(EcosysPi.UV.absolutePath, "run", "python", "-m", "ecosys_pi") + args
    val builder = ProcessBuilder(command).directory(workingDir).redirectErrorStream(false)
    builder.environment().putAll(environment)
    val process =
        try {
            builder.start()
        } catch (failure: Exception) {
            return CliRun(EcosysPi.SPAWN_ERROR, "", failure.message ?: "spawn failed")
        }
    return awaitAndDrain(process, deadlineSeconds)
}

/** A one-shot `uv run python <code>` probe used by the loud skip gate. */
fun runUvPython(
    code: String,
    deadlineSeconds: Long = 60,
): CliRun {
    val command = listOf(EcosysPi.UV.absolutePath, "run", "python", "-c", code)
    val process =
        try {
            ProcessBuilder(command).directory(EcosysPi.PROJECT_ROOT).start()
        } catch (failure: Exception) {
            return CliRun(EcosysPi.SPAWN_ERROR, "", failure.message ?: "spawn failed")
        }
    return awaitAndDrain(process, deadlineSeconds)
}

/** Drain both pipes and wait out [deadlineSeconds], force-destroying on overrun. */
private fun awaitAndDrain(
    process: Process,
    deadlineSeconds: Long,
): CliRun {
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val outThread = drain(process.inputStream.bufferedReader(), stdout)
    val errThread = drain(process.errorStream.bufferedReader(), stderr)

    val finished = process.waitFor(deadlineSeconds, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        process.waitFor(EcosysPi.REAP_SECONDS, TimeUnit.SECONDS)
    }
    outThread.join(EcosysPi.DRAIN_JOIN_MS)
    errThread.join(EcosysPi.DRAIN_JOIN_MS)

    val exit = if (finished) process.exitValue() else EcosysPi.TIMED_OUT
    val suffix =
        if (finished) {
            ""
        } else {
            "\n[harness] child exceeded ${deadlineSeconds}s and was force-destroyed"
        }
    return CliRun(exit, stdout.toString(), stderr.toString() + suffix)
}

/**
 * The named reason this host cannot run the Python E2E, or `null` when it can.
 *
 * The suite SKIPS loudly (JUnit `Assumptions`) when this returns non-null: a
 * missing Pi repo / `uv` / CPython 3.11+ must never be reported as a silent pass,
 * and it must never fail the hub suite. Every OTHER prerequisite (the hub graph,
 * a bindable loopback port) FAILS the test rather than skipping it.
 */
fun ecosysPiUnavailableReason(): String? {
    if (!EcosysPi.UV.canExecute()) {
        return "T23 SKIPPED: uv is not executable at '${EcosysPi.UV.absolutePath}' " +
            "(set UV_BIN to override)"
    }
    if (!EcosysPi.PROJECT_ROOT.isDirectory) {
        return "T23 SKIPPED: the Pi repo is not a directory at '${EcosysPi.PROJECT_ROOT.absolutePath}' " +
            "(set PI_ECOSYS_REPO to override)"
    }
    if (!File(EcosysPi.PROJECT_ROOT, EcosysPi.CLI_MODULE_RELATIVE).isFile) {
        return "T23 SKIPPED: the Python CLI is missing at " +
            "'${File(EcosysPi.PROJECT_ROOT, EcosysPi.CLI_MODULE_RELATIVE).absolutePath}'"
    }
    // The CLI declares requires-python >=3.11; prove the interpreter satisfies it.
    val probe =
        runUvPython("import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)")
    if (!probe.ok) {
        return "T23 SKIPPED: 'uv run python' could not run under the Pi repo " +
            "(exit=${probe.exitCode}; stderr=${probe.stderr.trim().take(300)})"
    }
    return null
}

/** Drain a reader into a sink on a daemon thread so a full pipe cannot block. */
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
