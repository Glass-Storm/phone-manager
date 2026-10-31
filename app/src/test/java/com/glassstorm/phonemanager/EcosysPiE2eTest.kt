package com.glassstorm.phonemanager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.model.AudioFormat
import com.glassstorm.phonemanager.core.model.GestureMeaningTable
import com.glassstorm.phonemanager.core.model.PeerRole
import com.glassstorm.phonemanager.core.service.StreamServiceImpl
import com.glassstorm.phonemanager.transport.grpc.HubServerAdapter
import com.glassstorm.phonemanager.transport.grpc.StreamGrpcService
import com.glassstorm.phonemanager.transport.grpc.peer.HubPeerClient
import com.glassstorm.phonemanager.transport.grpc.peer.PeerEvent
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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

/**
 * T23: the CROSS-REPO end-to-end harness.
 *
 * It drives the REAL Python `ecosys_pi` CLI (the necklace) against the REAL hub
 * (Robolectric-hosted, wired from the `:app` graph whose `DeviceAiModule` binds the
 * deterministic `:adapter:jvm` fakes), over a REAL loopback socket with NO token.
 * A second, in-process `ecosys.v1` peer — [HubPeerClient], supplied by
 * `:transport:grpc`'s test fixtures — plays the glasses and, later, the
 * subtitle sender/receiver.
 *
 * ## Two loops are asserted
 *
 *  * **Gesture loop**: the necklace (Pi) sends `image` (JPEG) frames. The hub
 *    decodes each still inside the gesture adapter and fans the result out by
 *    role: a `gesture` frame (with a non-empty spoken meaning) to the GLASSES
 *    session, and a `tts_audio` frame back to the NECKLACE session. The glasses
 *    half is observed through [HubPeerClient]; the necklace half through a tiny
 *    `aplay` shim on the Pi's `PATH`, which captures the PCM the hub told the
 *    necklace to play (the CLI plays hub-synthesized audio, it does not
 *    synthesize its own).
 *  * **Subtitle loop**: the glasses session sends `audio_pcm16_16k`; the hub
 *    transcribes it and returns a `StreamResult` on the SAME session. The default
 *    speech fake never invents a speaker, so an unregistered speaker yields the
 *    EMPTY `speaker_label`.
 *
 * ## Deliberate posture
 *
 * No device, no camera, no `nmcli`, no real AI models, no network/hotspot: the
 * Pi is pointed at `127.0.0.1` with its synthetic JPEG source and a recording
 * playback backend, and the hub's engines are the deterministic JVM fakes.
 *
 * ## Skip-vs-fail
 *
 * The ONLY permitted skip is a host without the Pi repo / `uv` / CPython 3.11+
 * ([ecosysPiUnavailableReason] names the reason and `Assume` skips LOUDLY). Every
 * other prerequisite — the hub graph, a bindable loopback port, the Pi CLI
 * exiting 0 — FAILS the test. The dedicated `:app:ecosysPiE2e` task runs only
 * this class; `test`, `testDebugUnitTest` and `e2e` exclude it, so the plain hub
 * suite stays green on a host without the Pi repo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EcosysPiE2eTest {
    @get:Rule
    val deadline: Timeout = Timeout.seconds(300)

    /** A [FrameSink] that simply discards the opaque NALs; video is not the point here. */
    private class DiscardingSink : FrameSink {
        override fun acceptVideo(
            sessionId: String,
            h264Nal: ByteArray,
        ) = Unit
    }

    private lateinit var hub: HubServer
    private var glasses: HubPeerClient? = null
    private lateinit var shimDir: File
    private lateinit var aplayEvidence: File

    @Before
    fun startHubAndShimAplay() {
        // The Pi repo / uv / python gate: skip LOUDLY only when genuinely absent.
        val unavailable = ecosysPiUnavailableReason()
        assumeTrue(unavailable, unavailable == null)

        val app: Application = ApplicationProvider.getApplicationContext()
        val component = (app as PhoneManagerApplication).component

        // The REAL hub graph, wired from the app component's DeviceAiModule fakes. Only
        // the listener (loopback, never the 0.0.0.0 production bind) and the
        // media sink are chosen for the test, exactly as the sibling FullE2eTest
        // composes them; the engines are the deterministic adapter:jvm fakes.
        val stream =
            StreamServiceImpl(
                speechPort = component.speechPort(),
                gesturePort = component.gesturePort(),
                ttsPort = component.ttsPort(),
                enrollmentStore = component.enrollmentStore(),
                frameSink = DiscardingSink(),
            )
        hub =
            HubServerAdapter(
                streamService = StreamGrpcService(stream),
                bindAddress = HubServerAdapter.LOOPBACK_ADDRESS,
            )
        hub.start(0)
        assertThat(hub.boundPort()).isGreaterThan(0)

        // A fake `aplay` on the Pi's PATH captures the PCM the necklace is told to
        // play, proving the hub-synthesized `tts_audio` reached the necklace. The
        // shim is authoritative only in the child's environment.
        shimDir = Files.createTempDirectory("ecosys-aplay").toFile()
        aplayEvidence = File.createTempFile("ecosys-aplay-evidence", ".pcm")
        aplayEvidence.delete()
        val shim = File(shimDir, "aplay")
        shim.writeText(
            "#!/bin/sh\n" +
                "cat > \"\$${EcosysPi.APLAY_EVIDENCE_ENV}\"\n" +
                "exit 0\n",
        )
        shim.setExecutable(true)
    }

    @After
    fun stopHubAndGlasses() {
        glasses?.close()
        glasses = null
        if (this::hub.isInitialized) hub.stop()
    }

    @Test
    fun `necklace gesture loop and glasses subtitle loop over one fake engined hub`() {
        val port = hub.boundPort()
        val hubAddress = "127.0.0.1:$port"

        // Given an in-process GLASSES session bound by its `stream_open` frame,
        // connected BEFORE the (slow-starting) Pi so the role is bound in time.
        val glassesClient = HubPeerClient(host = "127.0.0.1", port = port, deviceId = "e2e-glasses", role = PeerRole.GLASS)
        glassesClient.open()
        glasses = glassesClient

        // When the REAL necklace CLI streams synthetic JPEG stills at the hub
        val run =
            runEcosysPi(
                args = listOf("--hub", hubAddress, "--mode", "video", "--frames", NECKLACE_FRAMES.toString()),
                environment = piEnvironment(hubAddress),
            )
        println("[QA] ecosys_pi stdout:\n${run.stdout.trim()}")
        println("[QA] ecosys_pi stderr:\n${run.stderr.trim()}")

        // Then the client ran cleanly with no contract error
        assertWithMessage("ecosys_pi stderr:\n${run.stderr}")
            .that(run.exitCode)
            .isEqualTo(EcosysPi.EXIT_OK)
        assertThat(run.stdout).doesNotContain("error:")

        // ---- Loop 1a: the necklace image reached the GLASSES as a gesture frame.
        val gesture = glassesClient.await<PeerEvent.Gesture>(GESTURE_TIMEOUT_MS)
        assertWithMessage("no gesture frame reached the glasses session")
            .that(gesture)
            .isNotNull()
        val meaning = gesture!!
        assertWithMessage("the gesture text (spoken meaning) must be non-empty")
            .that(meaning.text)
            .isNotEmpty()
        assertWithMessage("the gesture label must be one of the canned recognizer labels")
            .that(GestureMeaningTable.cannedLabels)
            .contains(meaning.label)
        assertThat(meaning.confidence).isAtLeast(0.0f)
        assertThat(meaning.confidence).isAtMost(1.0f)

        // ---- Loop 1b: the same image synthesized TTS audio back to the NECKLACE.
        assertWithMessage("the necklace was never handed hub-synthesized TTS audio")
            .that(aplayEvidence.isFile && aplayEvidence.length() > 0L)
            .isTrue()

        // ---- Loop 2: the glasses audio produced a StreamResult on the SAME stream.
        repeat(SUBTITLE_FRAMES) { index ->
            glassesClient.sendAudio(pcmChunk(index), ptsMs = (index * AudioFormat.CHUNK_BYTES).toLong())
        }
        val subtitle = glassesClient.await<PeerEvent.Speech>(SUBTITLE_TIMEOUT_MS)
        assertWithMessage("no StreamResult reached the glasses session for its own audio")
            .that(subtitle)
            .isNotNull()
        val speech = subtitle!!
        assertThat(speech.text).isNotEmpty()
        // The default speech fake never invents a name: an unregistered speaker is
        // the EMPTY label, never a fabricated one.
        assertWithMessage("an unregistered speaker must carry the empty label")
            .that(speech.speakerLabel)
            .isEmpty()

        // Scenario 3: the glasses PLAY the synthesized audio, so the SAME image also
        // reaches the glasses session as a `tts_audio` frame (in addition to the
        // necklace, which played it through the shimmed `aplay`).
        assertWithMessage("the glasses session never received the hub-synthesized TTS audio")
            .that(glassesClient.drain().filterIsInstance<PeerEvent.TtsAudio>())
            .isNotEmpty()
    }

    private fun piEnvironment(hubAddress: String): Map<String, String> {
        val inheritedPath = System.getenv("PATH").orEmpty()
        return mapOf(
            EcosysPi.HUB_ENV to hubAddress,
            EcosysPi.VIDEO_SOURCE_ENV to EcosysPi.VIDEO_SOURCE_SYNTHETIC,
            EcosysPi.PLAYER_ENV to EcosysPi.PLAYER_APLAY,
            EcosysPi.APLAY_EVIDENCE_ENV to aplayEvidence.absolutePath,
            "PATH" to (shimDir.absolutePath + File.pathSeparator + inheritedPath),
        )
    }

    /** A deterministic, non-empty 20 ms PCM16 chunk (the canonical wire framing). */
    private fun pcmChunk(index: Int): ByteArray = ByteArray(AudioFormat.CHUNK_BYTES) { byte -> ((index * 31 + byte * 7) % 127).toByte() }

    private companion object {
        const val NECKLACE_FRAMES: Int = 3
        const val SUBTITLE_FRAMES: Int = 3
        const val GESTURE_TIMEOUT_MS: Long = 20_000
        const val SUBTITLE_TIMEOUT_MS: Long = 10_000
    }
}
