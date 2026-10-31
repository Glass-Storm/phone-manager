package com.glassstorm.phonemanager.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glassstorm.phonemanager.ui.AppTheme
import com.glassstorm.phonemanager.ui.HubTheme
import com.glassstorm.phonemanager.ui.components.Text
import com.glassstorm.phonemanager.ui.components.card.Card
import com.glassstorm.phonemanager.ui.components.card.CardDefaults

/**
 * The one card that carries a header: an `h4` title row with an optional trailing
 * slot, then a content column. Every grouped region on every screen is one of
 * these, so cards never drift apart.
 *
 * The card keeps the kit's `surface` fill and a single 2 dp shadow — no border —
 * so the tinted canvas plus the shadow is what separates it from the page.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = SectionCardDefaults.cardElevation),
    ) {
        Column(
            modifier = Modifier.padding(SectionCardDefaults.contentPadding),
            verticalArrangement = Arrangement.spacedBy(SectionCardDefaults.contentGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = title, style = AppTheme.typography.h4)
                trailing?.invoke()
            }
            content()
        }
    }
}

object SectionCardDefaults {
    val cardElevation: Dp = 2.dp
    val contentPadding: PaddingValues = PaddingValues(16.dp)
    val contentGap: Dp = 12.dp
}

@Preview
@Composable
private fun SectionCardPreview() {
    HubTheme {
        SectionCard(title = "連線", modifier = Modifier.padding(16.dp)) {
            Text(text = "內容區塊", style = AppTheme.typography.body1)
        }
    }
}
