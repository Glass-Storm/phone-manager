package com.glassstorm.phonemanager.ui.hub

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glassstorm.phonemanager.ui.AppTheme
import com.glassstorm.phonemanager.ui.HubTheme
import com.glassstorm.phonemanager.ui.components.Text

/**
 * A neutral metadata pill for a name or a role — a speaker tag beside a
 * transcript, a device's role beside its name. It is NOT a status: it never
 * carries state colour, so it can never be confused with [StatusChip]. It shares
 * [StatusChipDefaults.shape] so both pills have identical geometry.
 */
@Composable
fun TagChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier =
            modifier
                .clip(TagChipDefaults.shape)
                .background(AppTheme.colors.disabled)
                .padding(TagChipDefaults.contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TagChipDefaults.iconGap),
    ) {
        if (icon != null) {
            Image(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(TagChipDefaults.iconSize),
                colorFilter = ColorFilter.tint(AppTheme.colors.text),
            )
        }
        Text(
            text = label,
            style = AppTheme.typography.label2,
            color = AppTheme.colors.text,
        )
    }
}

object TagChipDefaults {
    val shape = StatusChipDefaults.shape
    val contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    val iconSize: Dp = 16.dp
    val iconGap: Dp = 6.dp
}

@Preview
@Composable
private fun TagChipPreview() {
    HubTheme {
        Row(
            modifier =
                Modifier
                    .background(AppTheme.colors.background)
                    .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TagChip(label = "Speaker 1", icon = Icons.Default.Person)
            TagChip(label = "GLASS")
        }
    }
}
