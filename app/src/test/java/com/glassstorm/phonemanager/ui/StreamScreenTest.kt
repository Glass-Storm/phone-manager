package com.glassstorm.phonemanager.ui

import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import com.glassstorm.phonemanager.core.model.LivePeer
import com.glassstorm.phonemanager.core.model.PeerRole
import com.glassstorm.phonemanager.core.model.RelayOutput
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Live (即時) screen behaviour on the JVM, asserted on text selectors (no
 * screenshots).
 *
 * Expected strings are LITERALS, never the screen's own constants: a tautological
 * assertion passes even when the UI renders the wrong text. `createComposeRule()`
 * permits a single `setContent` per test, so each case renders once.
 *
 * The page no longer starts or stops a session: it observes the always-on relay.
 * Snapshots come from a real `viewModelScope` poll loop, and Robolectric's paused
 * main looper does not fire a `delay` on its own — the clock is advanced
 * explicitly with `idleFor`, which makes the cadence deterministic.
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
    fun `renders the unavailable state when the relay stats read fails`() {
        composeRule.setStreamContent(FakeStreamService(failStats = true))

        composeRule.assertText("即時轉送")
        composeRule.assertText("無法使用")
    }

    @Test
    fun `the pre-read state is a quiet checking placeholder, not an error`() {
        val viewModel =
            StreamViewModel.forTesting(
                stream = FakeStreamService(),
                hub = runningHub(),
                refreshOnInit = false,
            )

        composeRule.setStreamContent(viewModel)
        composeRule.waitForIdle()

        // A cold start must not flash the red failure state before the first read.
        composeRule.assertText("檢查中")
        composeRule.onNodeWithText("無法使用").assertDoesNotExist()
    }

    @Test
    fun `an idle relay shows zero counters and every empty state`() {
        composeRule.setStreamContent(FakeStreamService())

        composeRule.assertText("尚無裝置")
        composeRule.assertText("音訊")
        composeRule.assertText("影像")
        composeRule.assertText("手勢")
        composeRule.assertText("字幕")
        composeRule.assertText("H.264")
        composeRule.assertText("尚無字幕")
        composeRule.assertText("尚無手勢")
        // Five counter tiles all read zero.
        composeRule.onAllNodesWithText("0").assertCountEquals(5)
    }

    @Test
    fun `live peers render with their role and device id`() {
        val peer = FakeStreamService()
        peer.reportLivePeers(
            listOf(
                LivePeer(deviceId = "glasses-1", role = PeerRole.GLASS),
                LivePeer(deviceId = "pi-1", role = PeerRole.DAEMON),
            ),
        )

        composeRule.setStreamContent(peer)

        composeRule.assertText("已連線")
        composeRule.assertText("GLASS · glasses-1")
        composeRule.assertText("DAEMON · pi-1")
        composeRule.onNodeWithText("尚無裝置").assertDoesNotExist()
    }

    @Test
    fun `counters update while the relay carries media`() {
        val peer = FakeStreamService()

        composeRule.setStreamContent(peer)
        peer.reportStats(
            audioFrames = 3L,
            videoFrames = 5L,
            videoDropped = 4L,
            transcripts = 2L,
            imageFrames = 7L,
            gestures = 4L,
        )
        composeRule.advancePollClock()

        composeRule.assertText("3")
        composeRule.assertText("7")
        composeRule.assertText("4")
        composeRule.assertText("2")
        composeRule.assertText("5")
        composeRule.onAllNodesWithText("0").assertCountEquals(0)
    }

    @Test
    fun `the latest transcript renders with its speaker label and received-at time`() {
        val peer = FakeStreamService()
        peer.reportLivePeers(glassesPeer())

        composeRule.setStreamContent(peer)

        composeRule.assertText("尚無字幕")

        peer.emitTranscript("hello world", speakerLabel = "Speaker 1")
        composeRule.advancePollClock()

        composeRule.assertText("hello world")
        composeRule.assertText("Speaker 1")
        composeRule.assertTextContaining("最後更新 ")
        composeRule.onNodeWithText("尚無字幕").assertDoesNotExist()
    }

    @Test
    fun `a later transcript replaces the earlier one`() {
        val peer = FakeStreamService()
        peer.reportLivePeers(glassesPeer())

        composeRule.setStreamContent(peer)

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
    fun `the latest gesture renders with its received-at time`() {
        val peer = FakeStreamService()
        peer.reportLivePeers(glassesPeer())

        composeRule.setStreamContent(peer)

        composeRule.assertText("尚無手勢")

        peer.emitOutput(
            RelayOutput.Gesture(text = "Yes", label = "Thumb_Up", confidence = 0.9f, ptsMs = 0L),
        )
        composeRule.advancePollClock()

        composeRule.assertText("Yes")
        composeRule.assertTextContaining("最後更新 ")
        composeRule.onNodeWithText("尚無手勢").assertDoesNotExist()
    }

    @Test
    fun `a result emitted before the ViewModel attaches is not replayed`() {
        val peer = FakeStreamService()
        peer.reportLivePeers(glassesPeer())
        // A previous, now-dead session's last output. The fresh ViewModel attaches
        // afterwards and must start empty.
        peer.emitTranscript("dead session", speakerLabel = "Ghost")

        composeRule.setStreamContent(peer)

        composeRule.onNodeWithText("dead session").assertDoesNotExist()
        composeRule.onNodeWithText("Ghost").assertDoesNotExist()
        composeRule.assertText("尚無字幕")
    }

    @Test
    fun `a blank transcript is rejected so no empty heading renders`() {
        val peer = FakeStreamService()
        peer.reportLivePeers(glassesPeer())

        composeRule.setStreamContent(peer)

        peer.emitTranscript("   ", speakerLabel = "Speaker 1")
        composeRule.advancePollClock()

        composeRule.assertText("尚無字幕")
        composeRule.onNodeWithText("   ").assertDoesNotExist()
    }

    @Test
    fun `disconnecting every peer clears the latest transcript and gesture`() {
        val peer = FakeStreamService()
        peer.reportLivePeers(glassesPeer())

        composeRule.setStreamContent(peer)

        peer.emitTranscript("live subtitle", speakerLabel = "Speaker 1")
        peer.emitOutput(RelayOutput.Gesture(text = "Yes", label = "Thumb_Up", confidence = 0.9f, ptsMs = 0L))
        composeRule.advancePollClock()
        composeRule.assertText("live subtitle")
        composeRule.assertText("Yes")

        peer.reportLivePeers(emptyList())
        composeRule.advancePollClock()

        composeRule.onNodeWithText("live subtitle").assertDoesNotExist()
        composeRule.onNodeWithText("Yes").assertDoesNotExist()
        composeRule.onNodeWithText("最後更新 ", substring = true).assertDoesNotExist()
        composeRule.assertText("尚無字幕")
        composeRule.assertText("尚無手勢")
        composeRule.assertText("待機")
    }

    @Test
    fun `stopping the hub clears the latest transcript and gesture`() {
        val peer = FakeStreamService()
        val hub = runningHub()
        peer.reportLivePeers(glassesPeer())

        composeRule.setStreamContent(peer, hub = hub)

        peer.emitTranscript("live subtitle", speakerLabel = "Speaker 1")
        peer.emitOutput(RelayOutput.Gesture(text = "Yes", label = "Thumb_Up", confidence = 0.9f, ptsMs = 0L))
        composeRule.advancePollClock()
        composeRule.assertText("live subtitle")

        hub.stop()
        composeRule.advancePollClock()

        composeRule.onNodeWithText("live subtitle").assertDoesNotExist()
        composeRule.onNodeWithText("Yes").assertDoesNotExist()
        composeRule.assertText("尚無字幕")
        composeRule.assertText("尚無手勢")
        composeRule.assertText("待機")
    }

    @Test
    fun `clearing the ViewModel mid-poll leaves no live coroutine behind`() {
        val peer = FakeStreamService()
        peer.reportLivePeers(glassesPeer())
        val store = ViewModelStore()
        val viewModel = StreamViewModel.forTesting(peer, runningHub())
        store.put("stream", viewModel)

        composeRule.advancePollClock()
        assertThat(viewModel.uiState.value.available).isTrue()

        store.clear()

        // No further poll may run after clear: the counters stay where clear left
        // them even as the relay keeps reporting new media.
        val frozen = viewModel.uiState.value
        peer.reportStats(
            audioFrames = 99L,
            videoFrames = 99L,
            videoDropped = 99L,
            transcripts = 99L,
            imageFrames = 99L,
            gestures = 99L,
        )
        composeRule.advancePollClock()
        assertThat(viewModel.uiState.value).isEqualTo(frozen)
    }

    private fun runningHub(): FakeHubServer = FakeHubServer(initiallyRunning = true)

    private fun glassesPeer(): List<LivePeer> = listOf(LivePeer(deviceId = "glasses-1", role = PeerRole.GLASS))

    private fun ComposeContentTestRule.setStreamContent(
        stream: FakeStreamService,
        hub: FakeHubServer = runningHub(),
    ) {
        setStreamContent(StreamViewModel.forTesting(stream, hub))
    }

    private fun ComposeContentTestRule.setStreamContent(viewModel: StreamViewModel) {
        setContent {
            HubTheme {
                StreamScreen(viewModelFactory = viewModelFactory(viewModel))
            }
        }
        waitForIdle()
        advancePollClock()
    }

    private fun ComposeContentTestRule.assertText(text: String) {
        onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun ComposeContentTestRule.assertTextContaining(text: String) {
        onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()
    }

    private fun ComposeContentTestRule.advancePollClock() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000L))
        waitForIdle()
    }
}
