package com.glassstorm.phonemanager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** Fixed dark palette based on the original INMO Link prototype. */
private val TranslationColors =
    HubDarkColors.copy(
        background = Color(0xFF0B1419),
        surface = Color(0xFF354853),
        primary = Color(0xFF72D3F6),
        onPrimary = Color(0xFF062B3B),
        secondary = Color(0xFF294779),
        onSecondary = Color(0xFFE4EDF5),
        outline = Color(0xFF647780),
    )

@Composable
internal fun TranslationTheme(content: @Composable () -> Unit) {
    AppTheme(isDarkTheme = true) {
        CompositionLocalProvider(
            LocalColors provides TranslationColors,
            LocalContentColor provides TranslationColors.text,
            content = content,
        )
    }
}
