package com.blazikener.devin.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Approved round 3 roles. Keep explicit so platform defaults cannot change the palette.
val DevinLightColors = lightColorScheme(
    primary = Color(0xFF326451),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E8D9),
    onPrimaryContainer = Color(0xFF142B1D),
    inversePrimary = Color(0xFFAFD2BB),
    secondary = Color(0xFF52634F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E8CE),
    onSecondaryContainer = Color(0xFF152013),
    tertiary = Color(0xFF47626B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCEE8F1),
    onTertiaryContainer = Color(0xFF0A2027),
    error = Color(0xFF9B2F2F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F7F3),
    onBackground = Color(0xFF282B29),
    surface = Color(0xFFF8F7F3),
    onSurface = Color(0xFF282B29),
    surfaceVariant = Color(0xFFE1E5DC),
    onSurfaceVariant = Color(0xFF424A43),
    surfaceTint = Color(0xFF326451),
    inverseSurface = Color(0xFF2D322E),
    inverseOnSurface = Color(0xFFEEF1EA),
    outline = Color(0xFF717A71),
    outlineVariant = Color(0xFF747C73),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBFCF6),
    surfaceDim = Color(0xFFD8DCD4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4EC),
    surfaceContainer = Color(0xFFECEFE6),
    surfaceContainerHigh = Color(0xFFE5E9E0),
    surfaceContainerHighest = Color(0xFFDFE3D9),
)

val DevinDarkColors = darkColorScheme(
    primary = Color(0xFFAFD2BB),
    onPrimary = Color(0xFF173727),
    primaryContainer = Color(0xFF2D4E3A),
    onPrimaryContainer = Color(0xFFD4E8D9),
    inversePrimary = Color(0xFF326451),
    secondary = Color(0xFFBACCB2),
    onSecondary = Color(0xFF263423),
    secondaryContainer = Color(0xFF3C4B38),
    onSecondaryContainer = Color(0xFFD6E8CE),
    tertiary = Color(0xFFAACBD7),
    onTertiary = Color(0xFF17343E),
    tertiaryContainer = Color(0xFF2F4A54),
    onTertiaryContainer = Color(0xFFCEE8F1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF7F2223),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1C201E),
    onBackground = Color(0xFFECEEE8),
    surface = Color(0xFF1C201E),
    onSurface = Color(0xFFECEEE8),
    surfaceVariant = Color(0xFF414A43),
    onSurfaceVariant = Color(0xFFC1C9BD),
    surfaceTint = Color(0xFFAFD2BB),
    inverseSurface = Color(0xFFE1E5DC),
    inverseOnSurface = Color(0xFF2D322E),
    outline = Color(0xFF96A192),
    outlineVariant = Color(0xFF879183),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF373D37),
    surfaceDim = Color(0xFF101511),
    surfaceContainerLowest = Color(0xFF0B100C),
    surfaceContainerLow = Color(0xFF181E18),
    surfaceContainer = Color(0xFF1D231D),
    surfaceContainerHigh = Color(0xFF272D27),
    surfaceContainerHighest = Color(0xFF323832),
)

/** M3 fixed roles are not in the project’s Material 3 version yet; identical in both schemes. */
object DevinFixedColors {
    val primaryFixed = Color(0xFFD4E8D9)
    val primaryFixedDim = Color(0xFFAFD2BB)
    val onPrimaryFixed = Color(0xFF102417)
    val onPrimaryFixedVariant = Color(0xFF2D4E3A)
    val secondaryFixed = Color(0xFFD6E8CE)
    val secondaryFixedDim = Color(0xFFBACCB2)
    val onSecondaryFixed = Color(0xFF111E0F)
    val onSecondaryFixedVariant = Color(0xFF3C4B38)
    val tertiaryFixed = Color(0xFFCEE8F1)
    val tertiaryFixedDim = Color(0xFFAACBD7)
    val onTertiaryFixed = Color(0xFF071E26)
    val onTertiaryFixedVariant = Color(0xFF2F4A54)
}
