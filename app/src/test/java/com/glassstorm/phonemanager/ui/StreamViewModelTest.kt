package com.glassstorm.phonemanager.ui

import android.os.Looper
import com.glassstorm.phonemanager.core.model.LivePeer
import com.glassstorm.phonemanager.core.model.PeerRole
import com.glassstorm.phonemanager.core.model.RelayImage
import com.glassstorm.phonemanager.core.model.RelayOutput
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The 即時 page's output binding, asserted on the ViewModel alone.
 *
 * The relay can hand the ViewModel a replayed latest output the instant it
 * attaches. That output must be judged against the hub's REAL running state, not
 * the default `hubRunning = false` that only the first poll would correct: a poll
 * and the output collector start together, so a replayed transcript can arrive
 * before the poll has ever run. These tests pin both sides of the guard: a
 * running hub keeps the replayed transcript, a stopped hub still discards it.
 *
 * The poll is disabled (`refreshOnInit = false`) so nothing but the init-time
 * hub read can set `hubRunning`; that isolates the seeding from the poll.
 */
@RunWith(RobolectricTestRunner::class)
class StreamViewModelTest {
    @Test
    fun `a replayed transcript survives when the hub is already running`() {
        val stream = FakeStreamService(outputReplay = 1)
        stream.emitTranscript("replayed subtitle", speakerLabel = "Speaker 1")

        val viewModel =
            StreamViewModel.forTesting(
                stream = stream,
                hub = FakeHubServer(initiallyRunning = true),
                refreshOnInit = false,
            )
        idleMainLooper()

        assertThat(viewModel.uiState.value.latestTranscript).isEqualTo("replayed subtitle")
        assertThat(viewModel.uiState.value.latestSpeakerLabel).isEqualTo("Speaker 1")
        assertThat(viewModel.uiState.value.latestTranscriptAt).isNotNull()
    }

    @Test
    fun `a replayed transcript is dropped when the hub is stopped`() {
        val stream = FakeStreamService(outputReplay = 1)
        stream.emitTranscript("stale subtitle", speakerLabel = "Ghost")

        val viewModel =
            StreamViewModel.forTesting(
                stream = stream,
                hub = FakeHubServer(initiallyRunning = false),
                refreshOnInit = false,
            )
        idleMainLooper()

        assertThat(viewModel.uiState.value.latestTranscript).isNull()
        assertThat(viewModel.uiState.value.latestSpeakerLabel).isNull()
    }

    @Test
    fun `a daemon image is exposed to the live state`() {
        val stream = FakeStreamService()
        stream.reportLivePeers(listOf(LivePeer(deviceId = "pi-1", role = PeerRole.DAEMON)))
        val jpeg = byteArrayOf(1, 2, 3)
        stream.reportLatestImage(
            RelayImage(
                jpeg = jpeg,
                ptsMs = 42L,
                receivedAtMs = 7_000L,
                role = PeerRole.DAEMON,
                deviceId = "pi-1",
            ),
        )

        val viewModel = StreamViewModel.forTesting(stream, FakeHubServer(initiallyRunning = true))
        advancePoll()

        assertThat(checkNotNull(viewModel.uiState.value.latestImageJpeg)).isEqualTo(jpeg)
        assertThat(viewModel.uiState.value.latestImageDeviceId).isEqualTo("pi-1")
        assertThat(viewModel.uiState.value.latestImageAt).isEqualTo(7_000L)
    }

    @Test
    fun `a glasses image is not displayed`() {
        val stream = FakeStreamService()
        stream.reportLivePeers(listOf(LivePeer(deviceId = "glasses-1", role = PeerRole.GLASS)))
        stream.reportLatestImage(
            RelayImage(
                jpeg = byteArrayOf(9, 9, 9),
                ptsMs = 1L,
                receivedAtMs = 1_000L,
                role = PeerRole.GLASS,
                deviceId = "glasses-1",
            ),
        )

        val viewModel = StreamViewModel.forTesting(stream, FakeHubServer(initiallyRunning = true))
        advancePoll()

        assertThat(viewModel.uiState.value.latestImageJpeg).isNull()
    }

    @Test
    fun `a glasses gesture is not displayed`() {
        val stream = FakeStreamService()
        stream.reportLivePeers(listOf(LivePeer(deviceId = "glasses-1", role = PeerRole.GLASS)))
        val viewModel = StreamViewModel.forTesting(stream, FakeHubServer(initiallyRunning = true))

        stream.emitOutput(
            RelayOutput.Gesture(
                text = "glasses gesture",
                label = "Thumb_Up",
                confidence = 0.9f,
                ptsMs = 0L,
                sourceRole = PeerRole.GLASS,
            ),
        )
        idleMainLooper()

        assertThat(viewModel.uiState.value.latestGesture).isNull()
    }

    @Test
    fun `a later image replaces the earlier one immediately`() {
        val stream = FakeStreamService()
        stream.reportLivePeers(listOf(LivePeer(deviceId = "pi-1", role = PeerRole.DAEMON)))
        val viewModel = StreamViewModel.forTesting(stream, FakeHubServer(initiallyRunning = true))
        advancePoll()

        stream.reportLatestImage(
            RelayImage(
                jpeg = byteArrayOf(1, 1, 1),
                ptsMs = 1L,
                receivedAtMs = 1_000L,
                role = PeerRole.DAEMON,
                deviceId = "pi-1",
            ),
        )
        idleMainLooper()
        assertThat(checkNotNull(viewModel.uiState.value.latestImageJpeg)).isEqualTo(byteArrayOf(1, 1, 1))

        stream.reportLatestImage(
            RelayImage(
                jpeg = byteArrayOf(2, 2, 2),
                ptsMs = 2L,
                receivedAtMs = 2_000L,
                role = PeerRole.DAEMON,
                deviceId = "pi-1",
            ),
        )
        idleMainLooper()

        assertThat(checkNotNull(viewModel.uiState.value.latestImageJpeg)).isEqualTo(byteArrayOf(2, 2, 2))
        assertThat(viewModel.uiState.value.latestImageAt).isEqualTo(2_000L)
    }

    private fun advancePoll() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000L))
    }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
