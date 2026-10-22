package com.glassstorm.phonemanager.ui.hub

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glassstorm.phonemanager.ui.AppTheme
import com.glassstorm.phonemanager.ui.HubTheme
import com.glassstorm.phonemanager.ui.components.Text

/**
 * The four status values the hub can be in. Icon and colour are properties of the
 * enum, so the label alone always carries the meaning (greyscale- and
 * colour-blind-safe): the icon and colour are reinforcement, never the signal.
 */
enum class HubStatus {
    Live,
    Working,
    Idle,
    Error,
}

/**
 * The one status pill in the app: a leading Material icon plus a label, tinted by
 * [status]. The container colour eases between states; reduced motion snaps it.
 */
@Composable
fun StatusChip(
    status: HubStatus,
    label: String,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = status.containerColor(),
        animationSpec = tween(durationMillis = HubMotion.tweenMillis(), easing = FastOutSlowInEasing),
        label = "statusChipContainer",
    )
    val content by animateColorAsState(
        targetValue = status.contentColor(),
        animationSpec = tween(durationMillis = HubMotion.tweenMillis(), easing = FastOutSlowInEasing),
        label = "statusChipContent",
    )

    Row(
        modifier =
            modifier
                .clip(StatusChipDefaults.shape)
                .background(container)
                .padding(StatusChipDefaults.contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StatusChipDefaults.iconGap),
    ) {
        Image(
            imageVector = status.icon,
            contentDescription = null,
            modifier = Modifier.size(StatusChipDefaults.iconSize),
            colorFilter = ColorFilter.tint(content),
        )
        Text(
            text = label,
            style = AppTheme.typography.label1,
            color = content,
        )
    }
}

object StatusChipDefaults {
    val shape: Shape = RoundedCornerShape(50)
    val iconSize: Dp = 18.dp
    val iconGap: Dp = 6.dp
    val contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
}

private val HubStatus.icon: ImageVector
    get() =
        when (this) {
            HubStatus.Live -> Icons.Default.CheckCircle
            HubStatus.Working -> Icons.Default.Refresh
            HubStatus.Idle -> Icons.Default.Info
            HubStatus.Error -> Icons.Default.Warning
        }

@Composable
private fun HubStatus.containerColor(): Color =
    when (this) {
        HubStatus.Live -> AppTheme.colors.success
        HubStatus.Working -> AppTheme.colors.tertiary
        HubStatus.Idle -> AppTheme.colors.disabled
        HubStatus.Error -> AppTheme.colors.error
    }

@Composable
private fun HubStatus.contentColor(): Color =
    when (this) {
        HubStatus.Live -> AppTheme.colors.onSuccess
        HubStatus.Working -> AppTheme.colors.onTertiary
        // Idle is a neutral fill; `text` on `disabled` is a high-contrast pair and
        // keeps the label legible, unlike `onDisabled` which is only a 3:1 floor.
        HubStatus.Idle -> AppTheme.colors.text
        HubStatus.Error -> AppTheme.colors.onError
    }

@Preview
@Composable
private fun StatusChipPreview() {
    HubTheme {
        Row(
            modifier =
                Modifier
                    .background(AppTheme.colors.background)
                    .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(status = HubStatus.Live, label = "運作中")
            StatusChip(status = HubStatus.Working, label = "辨識中")
            StatusChip(status = HubStatus.Idle, label = "已停止")
            StatusChip(status = HubStatus.Error, label = "無法使用")
        }
    }
}
