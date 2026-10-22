package com.glassstorm.phonemanager.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A screen's content column: one column, scrollable, capped at
 * [HubContentColumnDefaults.maxWidth] and centred, so the phone layout does not
 * stretch on a tablet or an unfolded foldable.
 *
 * [sectionGap] is the gap between sections (24 dp by default), keeping the single
 * rhythm every screen shares.
 */
@Composable
fun HubContentColumn(
    modifier: Modifier = Modifier,
    sectionGap: Dp = HubContentColumnDefaults.sectionGap,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = HubContentColumnDefaults.maxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(HubContentColumnDefaults.screenPadding),
            verticalArrangement = Arrangement.spacedBy(sectionGap),
            content = content,
        )
    }
}

object HubContentColumnDefaults {
    val maxWidth: Dp = 560.dp
    val screenPadding: Dp = 16.dp
    val sectionGap: Dp = 24.dp
}
