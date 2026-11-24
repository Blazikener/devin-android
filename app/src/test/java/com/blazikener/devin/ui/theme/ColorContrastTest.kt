package com.blazikener.devin.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ColorContrastTest {
    @Test fun readableRolesMeetTextAndControlContrastInBothSchemes() {
        listOf(DevinLightColors, DevinDarkColors).forEach { c ->
            val textPairs = listOf(
                c.onPrimary to c.primary, c.onPrimaryContainer to c.primaryContainer,
                c.onSecondary to c.secondary, c.onSecondaryContainer to c.secondaryContainer,
                c.onTertiary to c.tertiary, c.onTertiaryContainer to c.tertiaryContainer,
                c.onError to c.error, c.onErrorContainer to c.errorContainer,
                c.onSurface to c.surface, c.onSurfaceVariant to c.surfaceContainerHighest,
                c.primary to c.surface,
            )
            textPairs.forEach { (foreground, background) ->
                assertTrue("Text contrast must be >= 4.5:1", contrast(foreground, background) >= 4.5)
            }
            listOf(c.surface, c.surfaceContainer, c.surfaceContainerHighest).forEach { background ->
                assertTrue("Control boundary must be >= 3:1", contrast(c.outline, background) >= 3.0)
            }
        }
    }

    private fun luminance(color: Color): Double {
        fun linear(value: Float): Double = value.toDouble().let { if (it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4) }
        return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
}
