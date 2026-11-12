package com.glassstorm.phonemanager.ui

import android.os.Looper
import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * Enrollment behaviour of the Settings state holder.
 *
 * The capture and the store are DOMAIN-port fakes, so these tests pin the contract
 * the screen depends on: an invalid name never costs an 8 s recording, a rejected
 * capture never reaches the store, and the machine-readable reason is kept for the
 * screen to translate.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    @Test
    fun `an invalid name is rejected before the microphone is opened`() {
        val capture = FakeVoiceCapture()
        val viewModel = viewModel(enrollment = FakeEnrollmentStore(), capture = capture)

        viewModel.onEnrollNameChange("Alice Smith")
        viewModel.onRecordAndEnroll()

        assertThat(capture.recordCalls).isEqualTo(0)
        assertThat(viewModel.uiState.value.enrollPhase).isEqualTo(EnrollmentPhase.Rejected)
        assertThat(viewModel.uiState.value.enrollReason).isEqualTo(EnrollOutcome.REASON_INVALID_NAME)
    }

    @Test
    fun `a valid name stores the captured sample under that name`() {
        val enrollment = FakeEnrollmentStore()
        val capture = FakeVoiceCapture()
        val viewModel = viewModel(enrollment = enrollment, capture = capture)

        viewModel.onEnrollNameChange("Alice")
        viewModel.onRecordAndEnroll()

        awaitState(viewModel) { it.enrollPhase == EnrollmentPhase.Enrolled && it.enrolledNames == listOf("Alice") }
        assertThat(capture.recordCalls).isEqualTo(1)
        assertThat(enrollment.lastPcm16).isNotNull()
        assertThat(viewModel.uiState.value.enrollReason).isNull()
    }

    @Test
    fun `a failed capture reports the microphone reason and never enrolls`() {
        val enrollment = FakeEnrollmentStore()
        val viewModel = viewModel(enrollment = enrollment, capture = FakeVoiceCapture(pcm16 = null))

        viewModel.onEnrollNameChange("Alice")
        viewModel.onRecordAndEnroll()

        awaitState(viewModel) { it.enrollPhase == EnrollmentPhase.Rejected }
        assertThat(viewModel.uiState.value.enrollReason)
            .isEqualTo(SettingsViewModel.REASON_CAPTURE_FAILED)
        assertThat(enrollment.lastPcm16).isNull()
    }

    @Test
    fun `a store rejection keeps the machine reason for the screen to translate`() {
        val viewModel =
            viewModel(
                enrollment = FakeEnrollmentStore(rejectWith = EnrollOutcome.REASON_AUDIO_TOO_SHORT),
                capture = FakeVoiceCapture(),
            )

        viewModel.onEnrollNameChange("Alice")
        viewModel.onRecordAndEnroll()

        awaitState(viewModel) { it.enrollPhase == EnrollmentPhase.Rejected }
        assertThat(viewModel.uiState.value.enrollReason).isEqualTo(EnrollOutcome.REASON_AUDIO_TOO_SHORT)
    }

    @Test
    fun `editing the name clears the previous outcome`() {
        val viewModel = viewModel(enrollment = FakeEnrollmentStore(), capture = FakeVoiceCapture())

        viewModel.onEnrollNameChange("Alice")
        viewModel.onRecordAndEnroll()
        awaitState(viewModel) { it.enrollPhase == EnrollmentPhase.Enrolled }

        viewModel.onEnrollNameChange("Bob")

        assertThat(viewModel.uiState.value.enrollPhase).isEqualTo(EnrollmentPhase.Idle)
        assertThat(viewModel.uiState.value.enrollReason).isNull()
    }

    @Test
    fun `the state reports whether the bound store is the device implementation`() {
        val provisioned =
            viewModel(
                enrollment = FakeEnrollmentStore(deviceBackedValue = true),
                capture = FakeVoiceCapture(),
            )
        awaitState(provisioned) { it.enrollDeviceBacked }

        val unprovisioned =
            viewModel(
                enrollment = FakeEnrollmentStore(deviceBackedValue = false),
                capture = FakeVoiceCapture(),
            )

        assertThat(unprovisioned.uiState.value.enrollDeviceBacked).isFalse()
    }

    @Test
    fun `removing a speaker drops it from the directory`() {
        val enrollment = FakeEnrollmentStore()
        val viewModel = viewModel(enrollment = enrollment, capture = FakeVoiceCapture())

        viewModel.onEnrollNameChange("Alice")
        viewModel.onRecordAndEnroll()
        awaitState(viewModel) { it.enrolledNames == listOf("Alice") }

        viewModel.onRemoveEnrollment("Alice")

        awaitState(viewModel) { it.enrolledNames.isEmpty() }
    }

    private fun viewModel(
        enrollment: FakeEnrollmentStore,
        capture: FakeVoiceCapture,
    ): SettingsViewModel =
        SettingsViewModel.forTesting(
            hub = FakeHubServer(),
            battery = FakeBatteryExemption(),
            enrollment = enrollment,
            voiceCapture = capture,
            io = Dispatchers.Unconfined,
        )

    private fun awaitState(
        viewModel: SettingsViewModel,
        predicate: (SettingsUiState) -> Boolean,
    ) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L)
        while (System.nanoTime() < deadlineNanos) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate(viewModel.uiState.value)) return
            Thread.sleep(5L)
        }
        throw AssertionError("settings never reached the expected state: ${viewModel.uiState.value}")
    }
}
