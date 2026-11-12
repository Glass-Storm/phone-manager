package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glassstorm.phonemanager.battery.BatteryExemption
import com.glassstorm.phonemanager.core.domain.adapter.enrollment.EnrollmentStore
import com.glassstorm.phonemanager.core.domain.adapter.enrollment.VoiceCapturePort
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.glassstorm.phonemanager.core.model.EnrollmentName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Settings state holder.
 *
 * Every capability is a CONSTRUCTOR dependency on a domain port: `HubServer`,
 * `BatteryExemption`, `EnrollmentStore` and `VoiceCapturePort`. A FAILING port
 * degrades to the "not available" rendering instead of a crash; a missing port is
 * impossible under compile-time DI.
 *
 * Enrollment is the one action here that creates data: the operator speaks ~8 s
 * into the phone, the hub derives a voiceprint and stores it under the typed name.
 * Nothing leaves the device, and the hub never invents a name for an unenrolled
 * speaker, so this screen is what turns an empty `speaker_label` into a real one.
 */
class SettingsViewModel
    private constructor(
        private val hub: HubServer,
        private val battery: BatteryExemption,
        private val enrollment: EnrollmentStore,
        private val voiceCapture: VoiceCapturePort,
        private val io: CoroutineDispatcher,
    ) : ViewModel() {
        @Inject
        constructor(
            hub: HubServer,
            battery: BatteryExemption,
            enrollment: EnrollmentStore,
            voiceCapture: VoiceCapturePort,
        ) : this(hub, battery, enrollment, voiceCapture, Dispatchers.IO)

        private val state = MutableStateFlow(SettingsUiState())

        val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

        init {
            refresh()
        }

        /** Ask the platform for the Doze exemption. */
        fun onRequestBatteryExemption() {
            runCatching { battery.requestExemption() }
            refresh()
        }

        fun onRefresh() {
            refresh()
        }

        /** Edits the name to enroll. Any pending outcome is cleared with it. */
        fun onEnrollNameChange(name: String) {
            state.update { it.copy(enrollName = name, enrollPhase = EnrollmentPhase.Idle, enrollReason = null) }
        }

        /**
         * Records the enrollment sample and stores the voiceprint.
         *
         * The name is validated BEFORE the 8 s recording, so a typo never costs the
         * operator a capture; the store then re-checks the same rule.
         */
        fun onRecordAndEnroll() {
            val current = state.value
            if (current.enrollPhase == EnrollmentPhase.Recording) return

            val name = current.enrollName.trim()
            if (!EnrollmentName.isValid(name)) {
                reject(EnrollOutcome.REASON_INVALID_NAME)
                return
            }

            state.update { it.copy(enrollPhase = EnrollmentPhase.Recording, enrollReason = null) }

            viewModelScope.launch {
                val pcm16 =
                    withContext(io) {
                        runCatching { voiceCapture.record(ENROLL_DURATION_MILLIS) }.getOrNull()
                    }
                if (pcm16 == null) {
                    reject(REASON_CAPTURE_FAILED)
                    return@launch
                }

                val outcome =
                    withContext(io) {
                        runCatching { enrollment.enroll(name, pcm16) }.getOrNull()
                    }
                if (outcome == null) {
                    reject(EnrollOutcome.REASON_VOICEPRINT_FAILED)
                    return@launch
                }
                if (!outcome.ok) {
                    reject(outcome.reason ?: EnrollOutcome.REASON_VOICEPRINT_FAILED)
                    return@launch
                }

                state.update {
                    it.copy(enrollName = "", enrollPhase = EnrollmentPhase.Enrolled, enrollReason = null)
                }
                refreshEnrollments()
            }
        }

        private fun reject(reason: String) {
            state.update { it.copy(enrollPhase = EnrollmentPhase.Rejected, enrollReason = reason) }
        }

        /** Deletes a speaker's voiceprint and republishes the directory. */
        fun onRemoveEnrollment(name: String) {
            viewModelScope.launch {
                withContext(io) { runCatching { enrollment.remove(name) } }
                state.update { it.copy(enrollPhase = EnrollmentPhase.Idle, enrollReason = null) }
                refreshEnrollments()
            }
        }

        private fun refresh() {
            val exempt = runCatching { battery.isExempt() }
            val port = runCatching { hub.boundPort() }
            val deviceBacked = runCatching { enrollment.deviceBacked }.getOrDefault(false)

            state.update {
                it.copy(
                    batteryExempt = exempt.getOrDefault(false),
                    batteryAvailable = exempt.isSuccess,
                    protocol = PROTOCOL,
                    hubPort = port.getOrDefault(0),
                    enrollDeviceBacked = deviceBacked,
                )
            }
            refreshEnrollments()
        }

        private fun refreshEnrollments() {
            viewModelScope.launch {
                val names = withContext(io) { runCatching { enrollment.names() }.getOrDefault(emptyList()) }
                state.update { it.copy(enrolledNames = names) }
            }
        }

        companion object {
            /** The frozen wire protocol the hub speaks. */
            const val PROTOCOL: String = "ecosys.v1"

            /**
             * Capture length: the store's 8 s minimum plus a margin, so a slightly
             * short device buffer still clears the gate.
             */
            const val ENROLL_DURATION_MILLIS: Long = 8_500L

            /** The microphone could not be opened or granted; not a wire reason. */
            const val REASON_CAPTURE_FAILED: String = "capture-failed"

            /**
             * Deterministic construction seam for tests: an explicit dispatcher, so a
             * capture and its enrollment finish before the assertion runs.
             */
            internal fun forTesting(
                hub: HubServer,
                battery: BatteryExemption,
                enrollment: EnrollmentStore,
                voiceCapture: VoiceCapturePort,
                io: CoroutineDispatcher,
            ): SettingsViewModel = SettingsViewModel(hub, battery, enrollment, voiceCapture, io)
        }
    }
