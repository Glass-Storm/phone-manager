package com.glassstorm.phonemanager.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glassstorm.phonemanager.ui.AppTheme
import com.glassstorm.phonemanager.ui.HubTheme
import com.glassstorm.phonemanager.ui.components.Button
import com.glassstorm.phonemanager.ui.components.ButtonVariant
import com.glassstorm.phonemanager.ui.components.Text

/**
 * The one key/value row: label in [AppTheme]'s `textSecondary` on the left, value
 * in `text` on the right. An optional [onCopy] appends a copy action, used for
 * values a user may need to hand to a peer (a passphrase, an address).
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfoRowDefaults.gap),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = AppTheme.typography.body2,
            color = AppTheme.colors.textSecondary,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = AppTheme.typography.body1,
            color = AppTheme.colors.text,
            textAlign = TextAlign.End,
        )
        if (onCopy != null) {
            Button(
                text = InfoRowDefaults.COPY_LABEL,
                // Bare-verb visible label: name the copied target for TalkBack ("複製熱點密碼").
                modifier = Modifier.semantics { contentDescription = "${InfoRowDefaults.COPY_LABEL}$label" },
                variant = ButtonVariant.Ghost,
                onClick = onCopy,
            )
        }
    }
}

object InfoRowDefaults {
    const val COPY_LABEL = "複製"
    val gap: Dp = 12.dp
}

@Preview
@Composable
private fun InfoRowPreview() {
    HubTheme {
        InfoRow(
            label = "閘道位址",
            value = "192.168.43.1",
            modifier = Modifier.padding(16.dp),
        )
    }
}
