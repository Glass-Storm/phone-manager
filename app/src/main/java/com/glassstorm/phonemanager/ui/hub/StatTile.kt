package com.glassstorm.phonemanager.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glassstorm.phonemanager.ui.AppTheme
import com.glassstorm.phonemanager.ui.HubTheme
import com.glassstorm.phonemanager.ui.components.Text

/**
 * The one counter tile: a big `h1` numeral over a `label2` caption. Relay
 * counters are read at a glance, so the numeral carries the weight and the
 * caption names it.
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(StatTileDefaults.gap),
    ) {
        Text(
            text = value,
            style = AppTheme.typography.h1,
            color = AppTheme.colors.text,
        )
        Text(
            text = label,
            style = AppTheme.typography.label2,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

object StatTileDefaults {
    val gap: Dp = 4.dp
}

@Preview
@Composable
private fun StatTilePreview() {
    HubTheme {
        StatTile(value = "40404", label = "連接埠", modifier = Modifier.padding(16.dp))
    }
}
