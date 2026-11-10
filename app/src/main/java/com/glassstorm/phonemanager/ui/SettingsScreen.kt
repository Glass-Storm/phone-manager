package com.glassstorm.phonemanager.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.glassstorm.phonemanager.ui.components.Button
import com.glassstorm.phonemanager.ui.components.ButtonVariant
import com.glassstorm.phonemanager.ui.components.Text
import com.glassstorm.phonemanager.ui.components.textfield.OutlinedTextField
import com.glassstorm.phonemanager.ui.hub.HubContentColumn
import com.glassstorm.phonemanager.ui.hub.HubStatus
import com.glassstorm.phonemanager.ui.hub.InfoRow
import com.glassstorm.phonemanager.ui.hub.ScreenHeader
import com.glassstorm.phonemanager.ui.hub.SectionCard
import com.glassstorm.phonemanager.ui.hub.StatusChip
import com.glassstorm.phonemanager.ui.hub.TagChip

/**
 * Hub configuration: the Doze exemption, the protocol/port the hub exposes, and
 * voiceprint enrollment.
 *
 * The hotspot mode selector is gone with the programmatic hotspot, and the live
 * relay diagnostics moved to the 即時 page, so this screen has no misleading
 * control. Speech-to-text is not configurable: the hub runs a single on-device
 * engine, so there is no engine selector and no cloud credential field.
 *
 * Enrollment is the ONE write on this screen: it records ~8 s from the phone's own
 * microphone, derives a voiceprint on-device and stores it under the typed name.
 * Without an enrollment every subtitle arrives with an empty speaker label, so this
 * is what makes speaker attribution visible in the 即時 page.
 */
@Composable
fun SettingsScreen(
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()

    // The exemption is granted in an EXTERNAL Android settings activity, so the
    // screen is backgrounded while it is granted. Refresh on return to reflect it.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onRefresh() }

    val context = LocalContext.current
    // Recording is the only runtime permission this screen needs; asking here keeps
    // the request next to the button that requires it.
    val recordPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.onRecordAndEnroll()
        }

    HubContentColumn(modifier = modifier) {
        ScreenHeader(title = "系統設定", subtitle = "中樞與連線偏好")

        batteryCard(state, viewModel)
        enrollmentCard(
            state = state,
            viewModel = viewModel,
            onRecord =
                {
                    val granted =
                        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        viewModel.onRecordAndEnroll()
                    } else {
                        recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
        )
        endpointCard(state)
    }
}

@Composable
private fun batteryCard(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    SectionCard(title = "電池") {
        if (!state.batteryAvailable) {
            StatusChip(status = HubStatus.Error, label = "無法使用")
            Text(
                text = "電池狀態暫時無法取得。",
                style = AppTheme.typography.body2,
                color = AppTheme.colors.textSecondary,
            )
            return@SectionCard
        }

        InfoRow(
            label = "最佳化狀態",
            value = if (state.batteryExempt) "已排除" else "未排除",
        )
        Button(
            text = "要求排除電池最佳化",
            enabled = !state.batteryExempt,
            onClick = viewModel::onRequestBatteryExemption,
        )
    }
}

@Composable
private fun enrollmentCard(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onRecord: () -> Unit,
) {
    val recording = state.enrollPhase == EnrollmentPhase.Recording

    SectionCard(title = "聲紋登記") {
        if (!state.enrollDeviceBacked) {
            // The in-memory fake is bound: enrolling would look successful and then
            // vanish on restart, so say so plainly instead of showing a form.
            StatusChip(status = HubStatus.Error, label = "無法使用")
            Text(
                text = "聲紋引擎未啟用：請先安裝語音模型，否則登記的聲紋不會保存。",
                style = AppTheme.typography.body2,
                color = AppTheme.colors.textSecondary,
            )
            return@SectionCard
        }

        Text(
            text = "登記後，字幕會顯示說話者名稱；未登記的說話者不會顯示名稱。",
            style = AppTheme.typography.body2,
            color = AppTheme.colors.textSecondary,
        )

        OutlinedTextField(
            value = state.enrollName,
            onValueChange = viewModel::onEnrollNameChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !recording,
            singleLine = true,
            label = { Text(text = "名稱") },
        )

        Text(
            text = "以相同名稱再錄音即可取代舊聲紋。",
            style = AppTheme.typography.body2,
            color = AppTheme.colors.textSecondary,
        )

        Button(
            text = if (recording) "錄音中…" else "錄音並登記",
            enabled = !recording && state.enrollName.isNotBlank(),
            onClick = onRecord,
        )

        enrollmentStatus(state)

        Text(
            text = "已登記聲紋",
            style = AppTheme.typography.label2,
            color = AppTheme.colors.textSecondary,
        )
        if (state.enrolledNames.isEmpty()) {
            Text(
                text = "尚未登記任何聲紋。",
                style = AppTheme.typography.body2,
                color = AppTheme.colors.textSecondary,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.enrolledNames.forEach { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TagChip(label = name, modifier = Modifier.weight(1f, fill = false))
                        Button(
                            text = "移除",
                            variant = ButtonVariant.DestructiveGhost,
                            onClick = { viewModel.onRemoveEnrollment(name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun enrollmentStatus(state: SettingsUiState) {
    when (state.enrollPhase) {
        EnrollmentPhase.Idle -> Unit

        EnrollmentPhase.Recording -> {
            StatusChip(status = HubStatus.Working, label = "錄音中")
            Text(
                text = "請向手機說話約 8 秒。",
                style = AppTheme.typography.body2,
                color = AppTheme.colors.textSecondary,
            )
        }

        EnrollmentPhase.Enrolled -> StatusChip(status = HubStatus.Live, label = "已登記")

        EnrollmentPhase.Rejected -> {
            StatusChip(status = HubStatus.Error, label = "無法登記")
            Text(
                text = enrollReasonText(state.enrollReason),
                style = AppTheme.typography.body2,
                color = AppTheme.colors.textSecondary,
            )
        }
    }
}

/** Plain-language wording for the machine-readable [EnrollOutcome] reasons. */
private fun enrollReasonText(reason: String?): String =
    when (reason) {
        EnrollOutcome.REASON_INVALID_NAME -> "名稱只可使用中英文字或數字，長度為 1 至 24 個字。"
        EnrollOutcome.REASON_AUDIO_TOO_SHORT -> "錄音太短，請再錄一次。"
        EnrollOutcome.REASON_VOICEPRINT_FAILED -> "無法建立聲紋，請再試一次。"
        SettingsViewModel.REASON_CAPTURE_FAILED -> "無法使用麥克風，請確認已授予錄音權限。"
        else -> "登記失敗，請再試一次。"
    }

@Composable
private fun endpointCard(state: SettingsUiState) {
    SectionCard(title = "端點") {
        InfoRow(label = "通訊協定", value = state.protocol)
        if (state.hubPort > 0) {
            InfoRow(label = "連接埠", value = state.hubPort.toString())
        }
    }
}
