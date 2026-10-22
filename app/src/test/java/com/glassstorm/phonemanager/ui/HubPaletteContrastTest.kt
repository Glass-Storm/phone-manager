package com.glassstorm.phonemanager.ui

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The hub palette's WCAG 2.1 AA proof.
 *
 * Contrast is computed (relative luminance, WCAG formula) and asserted rather than
 * eyeballed — a palette that "looks fine" but fails AA on one pair is exactly the
 * failure this app's hearing-impaired primary user cannot afford.
 *
 * Thresholds: body/secondary text >= 4.5:1; large text and non-text UI boundaries
 * (the `outline` colour, which is what delineates controls) >= 3:1.
 */
class HubPaletteContrastTest {
    private val bodyMinimum = 4.5
    private val uiMinimum = 3.0

    @Test
    fun `light palette meets WCAG AA`() = assertPalette(HubLightColors, "light")

    @Test
    fun `dark palette meets WCAG AA`() = assertPalette(HubDarkColors, "dark")

    private fun assertPalette(
        colors: Colors,
        name: String,
    ) {
        val textPairs =
            listOf(
                "text on background" to (colors.text to colors.background),
                "text on surface" to (colors.text to colors.surface),
                "textSecondary on background" to (colors.textSecondary to colors.background),
                "textSecondary on surface" to (colors.textSecondary to colors.surface),
                "onPrimary on primary" to (colors.onPrimary to colors.primary),
                "onSecondary on secondary" to (colors.onSecondary to colors.secondary),
                "onTertiary on tertiary" to (colors.onTertiary to colors.tertiary),
                "onError on error" to (colors.onError to colors.error),
                "onSuccess on success" to (colors.onSuccess to colors.success),
                "primary on surface (outlined button label)" to (colors.primary to colors.surface),
                "primary on background (ghost button label)" to (colors.primary to colors.background),
            )

        textPairs.forEach { (label, pair) ->
            val ratio = contrast(pair.first, pair.second)
            assertWithMessage("$name: $label — expected >= $bodyMinimum, got ${"%.2f".format(ratio)}:1")
                .that(ratio)
                .isAtLeast(bodyMinimum)
        }

        val uiPairs =
            listOf(
                "outline on surface (control boundary)" to (colors.outline to colors.surface),
                "outline on background (control boundary)" to (colors.outline to colors.background),
                // WCAG 1.4.3 exempts inactive controls from 4.5:1 (low contrast IS the
                // disabled signal); we still assert a 3:1 legibility floor.
                "onDisabled on disabled (legibility floor)" to (colors.onDisabled to colors.disabled),
            )

        uiPairs.forEach { (label, pair) ->
            val ratio = contrast(pair.first, pair.second)
            assertWithMessage("$name: $label — expected >= $uiMinimum, got ${"%.2f".format(ratio)}:1")
                .that(ratio)
                .isAtLeast(uiMinimum)
        }
    }

    private fun contrast(
        foreground: Color,
        background: Color,
    ): Double {
        val first = luminance(foreground)
        val second = luminance(background)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }

        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }
}
