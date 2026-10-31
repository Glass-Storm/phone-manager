package com.glassstorm.phonemanager.ui

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
