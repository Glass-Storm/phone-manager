package com.glassstorm.phonemanager.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glassstorm.phonemanager.ui.AppTheme
import com.glassstorm.phonemanager.ui.HubTheme
import com.glassstorm.phonemanager.ui.components.Text

/**
 * The one screen top: an `h2` title with an optional `label1` subtitle. Exactly
 * one per screen, so every screen opens with the same weight of heading.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ScreenHeaderDefaults.gap),
    ) {
        Text(
            text = title,
            style = AppTheme.typography.h2,
            color = AppTheme.colors.text,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = AppTheme.typography.label1,
                color = AppTheme.colors.textSecondary,
            )
        }
    }
}

object ScreenHeaderDefaults {
    val gap: Dp = 4.dp
}

@Preview
@Composable
private fun ScreenHeaderPreview() {
    HubTheme {
        ScreenHeader(title = "系統總覽", subtitle = "硬體中樞狀態")
    }
}
