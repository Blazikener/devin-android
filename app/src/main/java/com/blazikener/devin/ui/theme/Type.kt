package com.blazikener.devin.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun type(size: Int, height: Int, tracking: Float, weight: Int) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight(weight),
    fontSize = size.sp, lineHeight = height.sp, letterSpacing = tracking.sp,
)

val DevinTypography = Typography(
    displayLarge = type(57, 64, 0f, 400),
    displayMedium = type(45, 52, 0f, 400),
    displaySmall = type(36, 44, 0f, 400),
    headlineLarge = type(32, 40, 0f, 400),
    headlineMedium = type(28, 36, 0f, 400),
    headlineSmall = type(24, 32, 0f, 400),
    titleLarge = type(20, 28, 0f, 500),
    titleMedium = type(16, 24, 0.15f, 500),
    titleSmall = type(14, 20, 0.1f, 500),
    bodyLarge = type(16, 25, 0.25f, 400),
    bodyMedium = type(14, 22, 0.25f, 400),
    bodySmall = type(12, 18, 0.2f, 400),
    labelLarge = type(14, 20, 0.1f, 500),
    labelMedium = type(12, 16, 0.3f, 500),
    labelSmall = type(12, 16, 0.3f, 500),
)

/** Serif is only for welcome/document hero text, never the general UI scale. */
object DevinTextStyles {
    val welcomeSerif = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal,
        fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp)
    val documentHero = welcomeSerif
    val codeBlock = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp)
}

object DevinDimens {
    val touchTarget = 48.dp
    val screenPadding = 20.dp
    val sectionGap = 24.dp
    val itemGap = 12.dp
    val outlineWidth = 1.dp
}
