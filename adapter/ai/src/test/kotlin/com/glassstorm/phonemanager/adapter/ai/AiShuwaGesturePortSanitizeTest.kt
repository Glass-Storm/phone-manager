package com.glassstorm.phonemanager.adapter.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import hk.edu.hkmu.sign.landmark.Keypoint3D
import hk.edu.hkmu.sign.shuwa.ShuwaFrame
import hk.edu.hkmu.sign.shuwa.ShuwaPosePoint
import hk.edu.hkmu.sign.shuwa.ShuwaSpec
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Tests the phone-manager adaptation layer, not the ai-android engine.
 *
 * MediaPipe's 21-point hand landmarker can detect a hand while BlazePose's coarse wrist visibility is
 * low. The port must keep that detected hand; otherwise the upstream visibility filter discards the
 * most informative part of signs made near the face.
 */
@RunWith(RobolectricTestRunner::class)
class AiShuwaGesturePortSanitizeTest {
    private val port =
        AiShuwaGesturePort(
            context = ApplicationProvider.getApplicationContext<Context>(),
            modelDir = File("unused"),
        )

    @Test
    fun `a detected left hand survives low pose visibility`() {
        val sanitized = port.sanitizeForPreprocessor(frame(leftHand = true, rightHand = false))

        assertThat(sanitized.leftHand).hasSize(ShuwaSpec.HAND_POINTS)
        assertThat(sanitized.leftHand.first().x).isEqualTo(HAND_X)
        assertThat(sanitized.pose[LEFT_WRIST].visibility).isEqualTo(1f)
        assertThat(sanitized.rightHand.first().x).isEqualTo(ShuwaSpec.IGNORE_VALUE)
        assertThat(sanitized.pose[RIGHT_WRIST].x).isEqualTo(ShuwaSpec.IGNORE_VALUE)
    }

    @Test
    fun `an absent left hand is replaced with the sentinel`() {
        val sanitized = port.sanitizeForPreprocessor(frame(leftHand = false, rightHand = true))

        assertThat(sanitized.leftHand.first().x).isEqualTo(ShuwaSpec.IGNORE_VALUE)
        assertThat(sanitized.pose[LEFT_WRIST].x).isEqualTo(ShuwaSpec.IGNORE_VALUE)
        assertThat(sanitized.rightHand).hasSize(ShuwaSpec.HAND_POINTS)
        assertThat(sanitized.pose[RIGHT_WRIST].visibility).isEqualTo(1f)
    }

    private fun frame(
        leftHand: Boolean,
        rightHand: Boolean,
    ): ShuwaFrame =
        ShuwaFrame(
            pose =
                List(ShuwaSpec.POSE_POINTS) { index ->
                    ShuwaPosePoint(
                        x = 0.1f,
                        y = 0.2f,
                        z = 0f,
                        visibility = if (index in HAND_POINTS) 0.1f else 0.9f,
                    )
                },
            face = emptyList(),
            leftHand = if (leftHand) hand() else emptyList(),
            rightHand = if (rightHand) hand() else emptyList(),
            timestampMillis = 0L,
        )

    private fun hand(): List<Keypoint3D> = List(ShuwaSpec.HAND_POINTS) { Keypoint3D(HAND_X, HAND_Y, 0f) }

    private companion object {
        const val HAND_X = 0.4f
        const val HAND_Y = 0.5f
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
        val HAND_POINTS = intArrayOf(15, 17, 19, 21, 16, 18, 20, 22)
    }
}
