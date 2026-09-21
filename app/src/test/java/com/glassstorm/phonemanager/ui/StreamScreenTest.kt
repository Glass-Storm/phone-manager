package com.glassstorm.phonemanager.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import com.glassstorm.phonemanager.core.domain.context.Context
import com.glassstorm.phonemanager.core.domain.context.register
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Stream screen behaviour on the JVM, asserted on text selectors (no screenshots).
 *
 * Expected strings are LITERALS, never the screen's own constants: a tautological
 * assertion passes even when the UI renders the wrong text. `createComposeRule()`
 * permits a single `setContent` per test, so each case renders once.
 *
 * The counters are driven by a real `viewModelScope` poll loop, and Robolectric's
 * paused main looper does not fire a `delay` on its own — the clock is advanced
 * explicitly with `idleFor`, which is also what makes the cadence deterministic
 * rather than a race against wall time.
 *
 * Every positive assertion scrolls the node into view first: the screen is a
 * scrolling column and the Robolectric viewport clips its lower cards, so a plain
 * `assertIsDisplayed` would fail on position rather than on content.
 */
@RunWith(RobolectricTestRunner::class)
class StreamScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders the unavailable state when the stream port is absent`() {
        composeRule.setStreamContent(Context())

        composeRule.assertText("Stream")
        composeRule.assertText("Stream service not available")
    }

    @Test
    fun `a session starts idle with every counter at zero`() {
        val ctx = Context().also { register<StreamService>(it, FakeStreamService()) }

        composeRule.setStreamContent(ctx)

        composeRule.assertText("Session: idle")
        composeRule.assertText("Audio frames in: 0")
        composeRule.assertText("Video frames in: 0")
        composeRule.assertText("Video dropped: 0")
        composeRule.assertText("Transcripts out: 0")
        composeRule.assertText("Live sessions: 0")
    }

    @Test
    fun `counters update while a session is live`() {
        val peer = FakeStreamService()
        val ctx = Context().also { register<StreamService>(it, peer) }

        composeRule.setStreamContent(ctx)
        composeRule.click("Start session")

        composeRule.assertText("Session: live")
        composeRule.assertText("Live sessions: 1")

        // The peer pushes media AFTER the session opened, so only the refresh loop
        // can surface these numbers.
        peer.reportStats(
            audioFrames = 3L,
            videoFrames = 5L,
            videoDropped = 1L,
            transcripts = 2L,
        )
        composeRule.advancePollClock()

        composeRule.assertText("Audio frames in: 3")
        composeRule.assertText("Video frames in: 5")
        composeRule.assertText("Video dropped: 1")
        composeRule.assertText("Transcripts out: 2")
        composeRule.onNodeWithText("Audio frames in: 0").assertDoesNotExist()
    }

    @Test
    fun `the latest transcript renders with its speaker label`() {
        val peer = FakeStreamService()
        val ctx = Context().also { register<StreamService>(it, peer) }

        composeRule.setStreamContent(ctx)
        composeRule.click("Start session")

        composeRule.assertText("No transcript yet")

        peer.emitTranscript("hello world", speakerLabel = "Speaker 1")
        composeRule.advancePollClock()

        composeRule.assertText("hello world")
        composeRule.assertText("Speaker: Speaker 1")
        composeRule.onNodeWithText("No transcript yet").assertDoesNotExist()
    }

    @Test
    fun `a later transcript replaces the earlier one`() {
        val peer = FakeStreamService()
        val ctx = Context().also { register<StreamService>(it, peer) }

        composeRule.setStreamContent(ctx)
        composeRule.click("Start session")

        peer.emitTranscript("first", speakerLabel = "Speaker 1")
        composeRule.advancePollClock()
        composeRule.assertText("first")

        peer.emitTranscript("second", speakerLabel = "Speaker 2")
        composeRule.advancePollClock()

        composeRule.assertText("second")
        composeRule.assertText("Speaker: Speaker 2")
        composeRule.onNodeWithText("first").assertDoesNotExist()
    }

    @Test
    fun `stopping the session zeroes the counters and drops the transcript`() {
        val peer = FakeStreamService()
        val ctx = Context().also { register<StreamService>(it, peer) }

        composeRule.setStreamContent(ctx)
        composeRule.click("Start session")

        peer.reportStats(
            audioFrames = 9L,
            videoFrames = 9L,
            videoDropped = 4L,
            transcripts = 2L,
        )
        peer.emitTranscript("seen while live", speakerLabel = "Speaker 1")
        composeRule.advancePollClock()
        composeRule.assertText("Audio frames in: 9")
        composeRule.assertText("seen while live")

        composeRule.click("Stop session")

        composeRule.assertText("Session: idle")
        composeRule.assertText("Audio frames in: 0")
        composeRule.assertText("Video frames in: 0")
        composeRule.assertText("Video dropped: 0")
        composeRule.assertText("Transcripts out: 0")
        composeRule.assertText("Live sessions: 0")
        composeRule.assertText("No transcript yet")
        composeRule.onNodeWithText("seen while live").assertDoesNotExist()
    }

    @Test
    fun `stopping without starting is a no-op and never crashes`() {
        val peer = FakeStreamService()
        val ctx = Context().also { register<StreamService>(it, peer) }
        val viewModel = StreamViewModel(ctx)

        // The Stop control is disabled while idle, so the idempotent path is driven
        // at the ViewModel level: a stop with no session must not close anything.
        viewModel.onStop()

        assertThat(peer.closedIds()).isEmpty()
        assertThat(viewModel.uiState.value.sessionId).isNull()
        assertThat(viewModel.uiState.value.audioFrames).isEqualTo(0L)
    }

    @Test
    fun `a stopped session can be started again on a fresh session id`() {
        val peer = FakeStreamService(sessionIds = listOf("s-1", "s-2"))
        val ctx = Context().also { register<StreamService>(it, peer) }

        composeRule.setStreamContent(ctx)
        composeRule.click("Start session")
        composeRule.click("Stop session")

        peer.reportStats(
            audioFrames = 8L,
            videoFrames = 0L,
            videoDropped = 0L,
            transcripts = 0L,
        )
        composeRule.click("Start session")

        composeRule.assertText("Session: live")
        // The second open mints a DIFFERENT session id; the baseline taken at start
        // means the counters show THIS session, not the cumulative relay total.
        composeRule.assertText("Audio frames in: 0")
        assertThat(peer.openCount()).isEqualTo(2)
    }

    @Test
    fun `clearing the ViewModel mid-refresh leaves no live coroutine behind`() {
        val peer = FakeStreamService()
        val ctx = Context().also { register<StreamService>(it, peer) }
        val store = ViewModelStore()
        val viewModel = StreamViewModel(ctx)
        store.put("stream", viewModel)

        viewModel.onStart()
        composeRule.advancePollClock()
        assertThat(viewModel.uiState.value.sessionId).isNotNull()

        store.clear()

        // No further poll may run after clear: the counters stay where clear left
        // them even as the peer keeps reporting new frames.
        val frozen = viewModel.uiState.value
        peer.reportStats(
            audioFrames = 99L,
            videoFrames = 99L,
            videoDropped = 99L,
            transcripts = 99L,
        )
        composeRule.advancePollClock()
        assertThat(viewModel.uiState.value).isEqualTo(frozen)
    }

    private fun ComposeContentTestRule.setStreamContent(ctx: Context) {
        setContent {
            AppTheme {
                StreamScreen(context = ctx)
            }
        }
    }

    private fun ComposeContentTestRule.assertText(text: String) {
        onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun ComposeContentTestRule.click(text: String) {
        onNodeWithText(text).performScrollTo().performClick()
        waitForIdle()
    }

    private fun ComposeContentTestRule.advancePollClock() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000L))
        waitForIdle()
    }
}
