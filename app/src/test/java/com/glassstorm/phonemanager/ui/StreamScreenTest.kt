package com.glassstorm.phonemanager.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.service.StreamService
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
        composeRule.GoSetStreamContent(Context())

        composeRule.GoAssertText("Stream")
        composeRule.GoAssertText("Stream service not available")
    }

    @Test
    fun `a session starts idle with every counter at zero`() {
        val GoCtx = Context().also { Register<StreamService>(it, FakeStreamService()) }

        composeRule.GoSetStreamContent(GoCtx)

        composeRule.GoAssertText("Session: idle")
        composeRule.GoAssertText("Audio frames in: 0")
        composeRule.GoAssertText("Video frames in: 0")
        composeRule.GoAssertText("Video dropped: 0")
        composeRule.GoAssertText("Transcripts out: 0")
        composeRule.GoAssertText("Live sessions: 0")
    }

    @Test
    fun `counters update while a session is live`() {
        val GoPeer = FakeStreamService()
        val GoCtx = Context().also { Register<StreamService>(it, GoPeer) }

        composeRule.GoSetStreamContent(GoCtx)
        composeRule.GoClick("Start session")

        composeRule.GoAssertText("Session: live")
        composeRule.GoAssertText("Live sessions: 1")

        // The peer pushes media AFTER the session opened, so only the refresh loop
        // can surface these numbers.
        GoPeer.GoReportStats(
            GoAudioFrames = 3L,
            GoVideoFrames = 5L,
            GoVideoDropped = 1L,
            GoTranscripts = 2L,
        )
        composeRule.GoAdvancePollClock()

        composeRule.GoAssertText("Audio frames in: 3")
        composeRule.GoAssertText("Video frames in: 5")
        composeRule.GoAssertText("Video dropped: 1")
        composeRule.GoAssertText("Transcripts out: 2")
        composeRule.onNodeWithText("Audio frames in: 0").assertDoesNotExist()
    }

    @Test
    fun `the latest transcript renders with its speaker label`() {
        val GoPeer = FakeStreamService()
        val GoCtx = Context().also { Register<StreamService>(it, GoPeer) }

        composeRule.GoSetStreamContent(GoCtx)
        composeRule.GoClick("Start session")

        composeRule.GoAssertText("No transcript yet")

        GoPeer.GoEmitTranscript("hello world", GoSpeakerLabel = "Speaker 1")
        composeRule.GoAdvancePollClock()

        composeRule.GoAssertText("hello world")
        composeRule.GoAssertText("Speaker: Speaker 1")
        composeRule.onNodeWithText("No transcript yet").assertDoesNotExist()
    }

    @Test
    fun `a later transcript replaces the earlier one`() {
        val GoPeer = FakeStreamService()
        val GoCtx = Context().also { Register<StreamService>(it, GoPeer) }

        composeRule.GoSetStreamContent(GoCtx)
        composeRule.GoClick("Start session")

        GoPeer.GoEmitTranscript("first", GoSpeakerLabel = "Speaker 1")
        composeRule.GoAdvancePollClock()
        composeRule.GoAssertText("first")

        GoPeer.GoEmitTranscript("second", GoSpeakerLabel = "Speaker 2")
        composeRule.GoAdvancePollClock()

        composeRule.GoAssertText("second")
        composeRule.GoAssertText("Speaker: Speaker 2")
        composeRule.onNodeWithText("first").assertDoesNotExist()
    }

    @Test
    fun `stopping the session zeroes the counters and drops the transcript`() {
        val GoPeer = FakeStreamService()
        val GoCtx = Context().also { Register<StreamService>(it, GoPeer) }

        composeRule.GoSetStreamContent(GoCtx)
        composeRule.GoClick("Start session")

        GoPeer.GoReportStats(
            GoAudioFrames = 9L,
            GoVideoFrames = 9L,
            GoVideoDropped = 4L,
            GoTranscripts = 2L,
        )
        GoPeer.GoEmitTranscript("seen while live", GoSpeakerLabel = "Speaker 1")
        composeRule.GoAdvancePollClock()
        composeRule.GoAssertText("Audio frames in: 9")
        composeRule.GoAssertText("seen while live")

        composeRule.GoClick("Stop session")

        composeRule.GoAssertText("Session: idle")
        composeRule.GoAssertText("Audio frames in: 0")
        composeRule.GoAssertText("Video frames in: 0")
        composeRule.GoAssertText("Video dropped: 0")
        composeRule.GoAssertText("Transcripts out: 0")
        composeRule.GoAssertText("Live sessions: 0")
        composeRule.GoAssertText("No transcript yet")
        composeRule.onNodeWithText("seen while live").assertDoesNotExist()
    }

    @Test
    fun `stopping without starting is a no-op and never crashes`() {
        val GoPeer = FakeStreamService()
        val GoCtx = Context().also { Register<StreamService>(it, GoPeer) }
        val GoViewModel = StreamViewModel(GoCtx)

        // The Stop control is disabled while idle, so the idempotent path is driven
        // at the ViewModel level: a stop with no session must not close anything.
        GoViewModel.GoOnStop()

        assertThat(GoPeer.GoClosedIds()).isEmpty()
        assertThat(GoViewModel.GoUiState.value.GoSessionId).isNull()
        assertThat(GoViewModel.GoUiState.value.GoAudioFrames).isEqualTo(0L)
    }

    @Test
    fun `a stopped session can be started again on a fresh session id`() {
        val GoPeer = FakeStreamService(GoSessionIds = listOf("s-1", "s-2"))
        val GoCtx = Context().also { Register<StreamService>(it, GoPeer) }

        composeRule.GoSetStreamContent(GoCtx)
        composeRule.GoClick("Start session")
        composeRule.GoClick("Stop session")

        GoPeer.GoReportStats(
            GoAudioFrames = 8L,
            GoVideoFrames = 0L,
            GoVideoDropped = 0L,
            GoTranscripts = 0L,
        )
        composeRule.GoClick("Start session")

        composeRule.GoAssertText("Session: live")
        // The second open mints a DIFFERENT session id; the baseline taken at start
        // means the counters show THIS session, not the cumulative relay total.
        composeRule.GoAssertText("Audio frames in: 0")
        assertThat(GoPeer.GoOpenCount()).isEqualTo(2)
    }

    @Test
    fun `clearing the ViewModel mid-refresh leaves no live coroutine behind`() {
        val GoPeer = FakeStreamService()
        val GoCtx = Context().also { Register<StreamService>(it, GoPeer) }
        val GoStore = ViewModelStore()
        val GoViewModel = StreamViewModel(GoCtx)
        GoStore.put("stream", GoViewModel)

        GoViewModel.GoOnStart()
        composeRule.GoAdvancePollClock()
        assertThat(GoViewModel.GoUiState.value.GoSessionId).isNotNull()

        GoStore.clear()

        // No further poll may run after clear: the counters stay where clear left
        // them even as the peer keeps reporting new frames.
        val GoFrozen = GoViewModel.GoUiState.value
        GoPeer.GoReportStats(
            GoAudioFrames = 99L,
            GoVideoFrames = 99L,
            GoVideoDropped = 99L,
            GoTranscripts = 99L,
        )
        composeRule.GoAdvancePollClock()
        assertThat(GoViewModel.GoUiState.value).isEqualTo(GoFrozen)
    }

    private fun ComposeContentTestRule.GoSetStreamContent(GoCtx: Context) {
        setContent {
            AppTheme {
                StreamScreen(GoContext = GoCtx)
            }
        }
    }

    private fun ComposeContentTestRule.GoAssertText(text: String) {
        onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun ComposeContentTestRule.GoClick(text: String) {
        onNodeWithText(text).performScrollTo().performClick()
        waitForIdle()
    }

    private fun ComposeContentTestRule.GoAdvancePollClock() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000L))
        waitForIdle()
    }
}
