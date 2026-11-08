package com.glassstorm.phonemanager.ui.original

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
internal fun OriginalLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Color(0xFF72D3F6),
                onPrimary = Color(0xFF062B3B),
                background = Color(0xFF0B1419),
                surface = Color(0xFF0B1419),
                surfaceVariant = Color(0xFF3B464D),
                onSurface = Color(0xFFDCE5EA),
                onSurfaceVariant = Color(0xFFBFCAD1),
                secondaryContainer = Color(0xFF354853),
                onSecondaryContainer = Color(0xFFDCE5EA),
                primaryContainer = Color(0xFF164B65),
                onPrimaryContainer = Color(0xFFC6ECFF),
            ),
        content = content,
    )
}
