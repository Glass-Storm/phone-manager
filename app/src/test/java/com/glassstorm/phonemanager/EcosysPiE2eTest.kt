package com.glassstorm.phonemanager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.adapter.jvm.speech.SttFactory
import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.service.PairingServiceImpl
import com.glassstorm.phonemanager.core.service.StreamServiceImpl
import com.glassstorm.phonemanager.core.service.security.TokenCodec
import com.glassstorm.phonemanager.transport.grpc.HubServerAdapter
import com.glassstorm.phonemanager.transport.grpc.PairingGrpcService
import com.glassstorm.phonemanager.transport.grpc.StreamGrpcService
import com.glassstorm.phonemanager.transport.grpc.security.AuthInterceptor
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.grpc.CallOptions
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * T14 harness: the REAL Python `ecosys_pi` CLI driven against the REAL hub.
 *
 * This is the only automated proof that the out-of-scope Pi client pairs,
 * heartbeats and streams against the ACTUAL hub: there is no emulator on this
 * host and the pairing window is not an RPC, so the composition mirrors the
 * Robolectric `:app` E2E harness exactly —
 *
 *  * the graph comes from the real [PhoneManagerApplication] component
 *    (SQLite repository, real `SttFactory` -> `MockSttAdapter`, real
 *    `PairingServiceImpl`), with [PairingServiceImpl.withClock] NOT needed
 *    because the app component already supplies the real service;
 *  * the listener is the production [HubServerAdapter] bound to
 *    [HubServerAdapter.LOOPBACK_ADDRESS] on an ephemeral port (the wildcard
 *    production bind is forbidden in tests);
 *  * the [FrameSink] is a RECORDING sink (video is dropped in production, so the
 *    observable double is the only way to see the relayed bytes).
 *
 * The CHILD is the frozen CLI (`uv run python -m ecosys_pi`) launched from the Pi
 * repo root, non-interactively with `--pin` (ONE Pair attempt, no re-prompt). Its
 * stdout is a frozen contract: `pair-ok device=<id>`, `heartbeat-ok`,
 * `transcript <text>`, `pair-rejected reason=<r>`, `re-pair-requested`,
 * `error: <detail>`; exit 0 success / 2 hub refusal / 1 other.
 *
 * ## Determinism
 *
 * There is no mic, so the CLI's audio source falls back to `SyntheticAudioSource`
 * whose frames are BYTE-IDENTICAL to the hub's Go/mockpeer generator; the hub's
 * `MockSttAdapter` answers each 640-byte chunk with its content hash, so the
 * transcript per frame index is a constant (see [EcosysPi.EXPECTED_TRANSCRIPTS]).
 *
 * ## Gate
 *
 * If this host lacks the Pi repo / `uv` / Python 3.11+ the whole class SKIPS
 * loudly with a named reason ([ecosysPiUnavailableReason]) — never a silent pass,
 * and never a failure of the hub suite. This class is run ONLY by the dedicated
 * `:app:ecosysPiE2e` task; [`testDebugUnitTest`] excludes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EcosysPiE2eTest {
    @get:Rule
    val deadline: Timeout = Timeout.seconds(600)

    /** Records every opaque video NAL the relay hands over, across pump threads. */
    private class RecordingFrameSink : FrameSink {
        private val nals = Collections.synchronizedList(mutableListOf<ByteArray>())
        val videoNals: List<ByteArray> get() = synchronized(nals) { nals.toList() }

        override fun acceptVideo(
            sessionId: String,
            h264Nal: ByteArray,
        ) {
            nals += h264Nal
        }
    }

    private lateinit var pairing: PairingServiceImpl
    private lateinit var hub: HubServer
    private lateinit var sink: RecordingFrameSink
    private var port: Int = 0
    private val tempDirs = mutableListOf<File>()
    private var channel: ManagedChannel? = null

    private val authKey: Metadata.Key<String> =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    /**
     * A raw protobuf method descriptor, so this `:app` suite names `ecosys.v1`
     * ONLY as a string (the frozen full-method name) rather than importing the
     * generated types — the architecture law confines `ecosys.v1.*` imports to
     * `:transport:grpc` and `:contract`, and `AuthInterceptor`'s own
     * `PAIR_METHOD` constant is likewise asserted as a string elsewhere.
     */
    private val heartbeatMethod: MethodDescriptor<ByteArray, ByteArray> =
        MethodDescriptor
            .newBuilder<ByteArray, ByteArray>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("ecosys.v1.PairingService/Heartbeat")
            .setRequestMarshaller(BYTES_MARSHALLER)
            .setResponseMarshaller(BYTES_MARSHALLER)
            .build()

    @Before
    fun startAppHub() {
        val reason = ecosysPiUnavailableReason()
        if (reason != null) {
            // Mandated LOUD skip: never a silent pass on a host without uv/the Pi repo.
            println("[task-14 SKIP] $reason")
        }
        assumeTrue(reason ?: "task-14: the Python E2E host gate passed", reason == null)

        val app: Application = ApplicationProvider.getApplicationContext()
        val component = (app as PhoneManagerApplication).component
        // The app component binds PairingService to ONE @Singleton
        // PairingServiceImpl, shared with the AuthInterceptor; the concrete type
        // is what exposes revoke/listPaired for the negative cases.
        pairing = component.pairingService() as PairingServiceImpl

        sink = RecordingFrameSink()
        val stt = SttFactory(component.appConfig()).createSttPort()
        val stream = StreamServiceImpl(stt, sink)
        hub =
            HubServerAdapter(
                pairingService = PairingGrpcService(pairing),
                streamService = StreamGrpcService(stream),
                authInterceptor = AuthInterceptor(component.tokenVerifier()),
                bindAddress = HubServerAdapter.LOOPBACK_ADDRESS,
            )
        hub.start(0)
        port = hub.boundPort()
        assertThat(port).isGreaterThan(0)
    }

    @After
    fun stopAppHub() {
        channel?.shutdownNow()
        channel = null
        if (this::hub.isInitialized) hub.stop()
        tempDirs.forEach { dir -> dir.deleteRecursively() }
        tempDirs.clear()
    }

    // ---------------------------------------------------------------- happy path

    @Test
    fun `the python CLI pairs heartbeats and streams the deterministic mock transcript`() {
        // Given the hub with an open single-use pairing window
        val pin = pairing.openWindow(ttlMs = 120_000).pin
        val xdg = newTempConfigHome()

        // When the REAL CLI pairs non-interactively and streams 3 audio frames
        val run =
            runEcosysPi(
                args = cliArgs(pin, frames = EcosysPi.HAPPY_FRAMES),
                environment = syntheticEnv(xdg),
            )
        printRun("happy", run)

        // Then it exits 0 and its RAW stdout carries the frozen contract lines
        assertWithMessage("CLI stderr: ${run.stderr}")
            .that(run.exitCode)
            .isEqualTo(EcosysPi.EXIT_OK)

        val lines = run.lines()
        val pairLine = lines.firstOrNull { it.startsWith(EcosysPi.PAIR_OK_PREFIX) }
        assertWithMessage("no 'pair-ok device=<id>' line in stdout: $lines")
            .that(pairLine)
            .isNotNull()
        val deviceId = pairLine!!.removePrefix(EcosysPi.PAIR_OK_PREFIX)
        assertThat(deviceId).matches("[0-9a-f]{32}")

        assertThat(lines).contains("heartbeat-ok")

        // And the transcript text EXACTLY equals the deterministic mock value per
        // frame index — a real equality, never a bare "contains transcript".
        assertThat(run.transcripts())
            .containsExactlyElementsIn(EcosysPi.EXPECTED_TRANSCRIPTS.take(EcosysPi.HAPPY_FRAMES))
            .inOrder()

        // And the hub really paired the device that the CLI reported.
        val paired = pairing.listPaired()
        assertThat(paired.map { it.deviceId }).contains(deviceId)
    }

    // ------------------------------------------------------ negative (i) wrong PIN

    @Test
    fun `a wrong pin is refused with the frozen reason and exit 2`() {
        // Given an open window and a PIN that is NOT the window's
        val window = pairing.openWindow(ttlMs = 120_000)
        val wrongPin = flipLastDigit(window.pin)
        val xdg = newTempConfigHome()

        // When the CLI pairs with the wrong PIN (ONE attempt, non-interactive)
        val run =
            runEcosysPi(
                args = cliArgs(wrongPin, frames = 1),
                environment = syntheticEnv(xdg),
            )
        printRun("wrong-pin", run)

        // Then the hub's typed refusal is on stdout and the exit is 2
        assertThat(run.exitCode).isEqualTo(EcosysPi.EXIT_REFUSED)
        assertThat(run.lines()).contains(EcosysPi.PAIR_REJECTED_PIN_INVALID)
        // A rejection must not have persisted a device or minted a token.
        assertThat(File(xdg, "ecosys-pi/credentials.json").exists()).isFalse()
    }

    // --------------------------------------------- negative (ii) revoked token

    @Test
    fun `a revoked cached token yields re-pair-requested and exit 2`() {
        // Given ONE shared config home for both runs, so the first run's cached
        // token is reused by the second (the plan's deliberate shared-temp case).
        val sharedXdg = newTempConfigHome()

        // When the CLI pairs once and caches the token
        val first =
            runEcosysPi(
                args = cliArgs(pairing.openWindow(ttlMs = 120_000).pin, frames = 1),
                environment = syntheticEnv(sharedXdg),
            )
        printRun("revoked-first", first)
        assertThat(first.exitCode).isEqualTo(EcosysPi.EXIT_OK)

        // And the hub revokes the exact device that token belongs to
        val token = readCachedToken(sharedXdg)
        val device = pairing.listPaired().firstOrNull { it.tokenHash == TokenCodec.hashToken(token) }
        assertWithMessage("no paired device owns the cached token")
            .that(device)
            .isNotNull()
        pairing.revoke(device!!.deviceId)

        // When the CLI runs again WITHOUT a PIN, reusing the cached (now revoked) token
        val second =
            runEcosysPi(
                args = listOf("--hub", "127.0.0.1:$port", "--mode", "audio", "--frames", "1"),
                environment = syntheticEnv(sharedXdg),
            )
        printRun("revoked-second", second)

        // Then the hub refuses the token and the CLI requests a re-pair, exit 2
        assertThat(second.exitCode).isEqualTo(EcosysPi.EXIT_REFUSED)
        assertThat(second.lines()).contains(EcosysPi.RE_PAIR_REQUESTED)
    }

    // ------------------------------------- negative (iii) bare auth scheme (gRPC)

    @Test
    fun `a bare token without the Bearer scheme is refused while the Bearer form is accepted`() {
        // Given a device paired through the REAL CLI, so the token is genuine
        val xdg = newTempConfigHome()
        val pairRun =
            runEcosysPi(
                args = cliArgs(pairing.openWindow(ttlMs = 120_000).pin, frames = 1),
                environment = syntheticEnv(xdg),
            )
        printRun("bare-token-setup", pairRun)
        assertThat(pairRun.exitCode).isEqualTo(EcosysPi.EXIT_OK)
        val token = readCachedToken(xdg)

        channel =
            NettyChannelBuilder
                .forAddress("127.0.0.1", port)
                .usePlaintext()
                .build()

        // When the SAME token is presented WITH the Bearer scheme, the hub accepts
        val withBearer = callHeartbeat("Bearer $token")

        // Then that call really reached the service (a non-empty HeartbeatResponse)
        assertWithMessage("bearer-authenticated Heartbeat failed: ${withBearer.failure}")
            .that(withBearer.status)
            .isNull()
        assertThat(withBearer.response).isNotNull()
        assertThat(withBearer.response!!).isNotEmpty()

        // And when the token is presented BARE (no scheme), the hub refuses it on
        // the exact strict-scheme status — never an oracle for why.
        val bare = callHeartbeat(token)
        println("[QA] bare-token raw status = ${bare.status}")

        // Prove the two are discriminated by the scheme, then assert the refusal
        assertThat(withBearer.status).isNull()
        assertThat(bare.status?.code).isEqualTo(Status.Code.UNAUTHENTICATED)
        assertThat(bare.status?.description).isEqualTo("missing or invalid bearer token")
    }

    // ---------------------------------------------------------------- helpers

    private fun cliArgs(
        pin: String,
        frames: Int,
    ): List<String> =
        listOf(
            "--hub",
            "127.0.0.1:$port",
            "--pin",
            pin,
            "--mode",
            "audio",
            "--frames",
            frames.toString(),
        )

    /**
     * The environment every case runs with: the per-case XDG config home for the
     * token cache, and the deterministic synthetic audio source (no mic host).
     * `--no-cache` is deliberately absent from the happy path.
     */
    private fun syntheticEnv(xdg: File): Map<String, String> =
        mapOf(
            "XDG_CONFIG_HOME" to xdg.absolutePath,
            EcosysPi.AUDIO_SOURCE_ENV to "synthetic",
        )

    private fun newTempConfigHome(): File {
        val dir = Files.createTempDirectory("ecosys-pi-e2e-").toFile()
        tempDirs += dir
        return dir
    }

    /** Read `credentials.json` and return its `token` field. */
    private fun readCachedToken(xdg: File): String {
        val file = File(xdg, "ecosys-pi/credentials.json")
        assertWithMessage("the CLI did not cache a token at ${file.absolutePath}")
            .that(file.isFile)
            .isTrue()
        val token = JSONObject(file.readText()).getString("token")
        assertThat(token).isNotEmpty()
        return token
    }

    private data class HeartbeatCall(
        val response: ByteArray?,
        val status: Status?,
    ) {
        val failure: String? get() = status?.let { "${it.code}: ${it.description}" }
    }

    /**
     * Call `PairingService/Heartbeat` over the real socket with [rawHeader] as the
     * raw `authorization` metadata (exactly [AuthGrpcTest]'s strict-scheme case).
     */
    private fun callHeartbeat(rawHeader: String): HeartbeatCall {
        val metadata = Metadata().apply { put(authKey, rawHeader) }
        val intercepted =
            ClientInterceptors.intercept(
                channel,
                MetadataUtils.newAttachHeadersInterceptor(metadata),
            )
        return try {
            val response =
                ClientCalls.blockingUnaryCall(
                    intercepted,
                    heartbeatMethod,
                    CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS),
                    ByteArray(0),
                )
            HeartbeatCall(response, null)
        } catch (failure: StatusRuntimeException) {
            HeartbeatCall(null, failure.status)
        }
    }

    private fun printRun(
        label: String,
        run: CliRun,
    ) {
        println(
            "[QA] ecosys-pi $label: exit=${run.exitCode}\n" +
                "----- RAW stdout -----\n${run.stdout.trim()}\n" +
                "----- end stdout -----\n" +
                "[QA] ecosys-pi $label stderr:\n${run.stderr.trim()}",
        )
    }

    private fun flipLastDigit(pin: String): String {
        val last = pin.last()
        val replacement = if (last == '0') '1' else '0'
        return pin.dropLast(1) + replacement
    }

    private companion object {
        /** Raw-bytes marshaller: the CLI/hub protobuf is irrelevant for auth tests. */
        val BYTES_MARSHALLER: MethodDescriptor.Marshaller<ByteArray> =
            object : MethodDescriptor.Marshaller<ByteArray> {
                override fun stream(value: ByteArray) = java.io.ByteArrayInputStream(value)

                override fun parse(stream: java.io.InputStream) = stream.readBytes()
            }
    }
}
