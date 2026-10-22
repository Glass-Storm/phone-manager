package com.glassstorm.phonemanager.ui.hub

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glassstorm.phonemanager.ui.AppTheme
import com.glassstorm.phonemanager.ui.HubTheme
import com.glassstorm.phonemanager.ui.components.Text

/**
 * The one empty state: a muted icon, a short title and a one-line hint, centred.
 * It says what is missing and what will fill it, never a blank region.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(EmptyStateDefaults.padding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(EmptyStateDefaults.gap),
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(EmptyStateDefaults.iconSize),
            colorFilter = ColorFilter.tint(AppTheme.colors.textDisabled),
        )
        Text(
            text = title,
            style = AppTheme.typography.h4,
            color = AppTheme.colors.text,
            textAlign = TextAlign.Center,
        )
        Text(
            text = hint,
            style = AppTheme.typography.body2,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

object EmptyStateDefaults {
    val iconSize: Dp = 48.dp
    val gap: Dp = 12.dp
    val padding: PaddingValues = PaddingValues(24.dp)
}

@Preview
@Composable
private fun EmptyStatePreview() {
    HubTheme {
        EmptyState(
            icon = Icons.Default.Notifications,
            title = "尚無字幕",
            hint = "對方說話時，字幕會顯示在這裡",
        )
    }
}
