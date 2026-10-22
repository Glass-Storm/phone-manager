package com.glassstorm.phonemanager.ui

import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
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
    fun `renders the unavailable state when the stream port read fails`() {
        composeRule.setStreamContent(FakeStreamService(failStats = true))

        composeRule.assertText("即時串流")
        composeRule.assertText("無法使用")
    }

    @Test
    fun `a session starts idle with every counter at zero`() {
        composeRule.setStreamContent(FakeStreamService())

        composeRule.assertText("待機")
        composeRule.assertText("音訊輸入")
        composeRule.assertText("視訊輸入")
        composeRule.assertText("視訊捨棄")
        composeRule.assertText("字幕輸出")
        // Four counter tiles plus the "目前連線" row all read zero.
        composeRule.onAllNodesWithText("0").assertCountEquals(5)
    }

    @Test
    fun `counters update while a session is live`() {
        val peer = FakeStreamService()

        composeRule.setStreamContent(peer)
        composeRule.click("開始串流")

        composeRule.assertText("已連線")
        composeRule.assertText("目前連線")

        // The peer pushes media AFTER the session opened, so only the refresh loop
        // can surface these numbers.
        peer.reportStats(
            audioFrames = 3L,
            videoFrames = 5L,
            videoDropped = 4L,
            transcripts = 2L,
        )
        composeRule.advancePollClock()

        composeRule.assertText("3")
        composeRule.assertText("5")
        composeRule.assertText("4")
        composeRule.assertText("2")
        composeRule.onAllNodesWithText("0").assertCountEquals(0)
    }

    @Test
    fun `the latest transcript renders with its speaker label`() {
        val peer = FakeStreamService()

        composeRule.setStreamContent(peer)
        composeRule.click("開始串流")

        composeRule.assertText("尚無字幕")

        peer.emitTranscript("hello world", speakerLabel = "Speaker 1")
        composeRule.advancePollClock()

        composeRule.assertText("hello world")
        composeRule.assertText("Speaker 1")
        composeRule.onNodeWithText("尚無字幕").assertDoesNotExist()
    }

    @Test
    fun `a later transcript replaces the earlier one`() {
        val peer = FakeStreamService()

        composeRule.setStreamContent(peer)
        composeRule.click("開始串流")

        peer.emitTranscript("first", speakerLabel = "Speaker 1")
        composeRule.advancePollClock()
        composeRule.assertText("first")

        peer.emitTranscript("second", speakerLabel = "Speaker 2")
        composeRule.advancePollClock()

        composeRule.assertText("second")
        composeRule.assertText("Speaker 2")
        composeRule.onNodeWithText("first").assertDoesNotExist()
    }

    @Test
    fun `stopping the session zeroes the counters and drops the transcript`() {
        val peer = FakeStreamService()

        composeRule.setStreamContent(peer)
        composeRule.click("開始串流")

        peer.reportStats(
            audioFrames = 7L,
            videoFrames = 9L,
            videoDropped = 4L,
            transcripts = 2L,
        )
        peer.emitTranscript("seen while live", speakerLabel = "Speaker 1")
        composeRule.advancePollClock()
        composeRule.assertText("7")
        composeRule.assertText("seen while live")

        composeRule.click("停止串流")

        composeRule.assertText("待機")
        composeRule.assertText("尚無字幕")
        composeRule.onAllNodesWithText("0").assertCountEquals(5)
        composeRule.onNodeWithText("seen while live").assertDoesNotExist()
    }

    @Test
    fun `stopping without starting is a no-op and never crashes`() {
        val peer = FakeStreamService()
        val viewModel = StreamViewModel(peer)

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

        composeRule.setStreamContent(peer)
        composeRule.click("開始串流")
        composeRule.click("停止串流")

        peer.reportStats(
            audioFrames = 8L,
            videoFrames = 0L,
            videoDropped = 0L,
            transcripts = 0L,
        )
        composeRule.click("開始串流")

        composeRule.assertText("已連線")
        // The second open mints a DIFFERENT session id; the baseline taken at start
        // means the counters show THIS session, not the cumulative relay total.
        composeRule.onAllNodesWithText("0").assertCountEquals(4)
        assertThat(peer.openCount()).isEqualTo(2)
    }

    @Test
    fun `clearing the ViewModel mid-refresh leaves no live coroutine behind`() {
        val peer = FakeStreamService()
        val store = ViewModelStore()
        val viewModel = StreamViewModel(peer)
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

    private fun ComposeContentTestRule.setStreamContent(stream: StreamService) {
        val viewModel = StreamViewModel(stream)
        setContent {
            HubTheme {
                StreamScreen(viewModelFactory = viewModelFactory(viewModel))
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
