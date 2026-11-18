package com.blazikener.devin.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.blazikener.devin.data.SessionState
import com.blazikener.devin.data.ThemeMode

private val Dark = darkColorScheme(
    primary = Color(0xFF87DFE5),
    onPrimary = Color(0xFF00363A),
    primaryContainer = Color(0xFF16464D),
    onPrimaryContainer = Color(0xFFB3F1F3),
    secondary = Color(0xFF8FD1C4),
    background = Color(0xFF0B1118),
    onBackground = Color(0xFFEDF2F7),
    surface = Color(0xFF0B1118),
    onSurface = Color(0xFFEDF2F7),
    surfaceVariant = Color(0xFF1C1F26),
    onSurfaceVariant = Color(0xFFB4B8C4),
    surfaceContainer = Color(0xFF151E29),
    surfaceContainerHigh = Color(0xFF1C2835),
    surfaceContainerHighest = Color(0xFF273544),
    surfaceContainerLow = Color(0xFF101923),
    outline = Color(0xFF3A3F4B),
    outlineVariant = Color(0xFF2A2E37),
)

private val Light = lightColorScheme(
    primary = Color(0xFF006970),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2F0F1),
    onPrimaryContainer = Color(0xFF00363A),
    secondary = Color(0xFF2E6B60),
    background = Color(0xFFF5F7FA),
    surface = Color(0xFFF5F7FA),
    onBackground = Color(0xFF142431),
    onSurface = Color(0xFF142431),
    onSurfaceVariant = Color(0xFF526271),
    surfaceVariant = Color(0xFFEEF0F5),
    surfaceContainer = Color(0xFFF1F2F6),
    surfaceContainerHigh = Color(0xFFEBECF1),
    surfaceContainerHighest = Color(0xFFE4E6EC),
    surfaceContainerLow = Color.White,
)

private val DevinType = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-1.4).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.8).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 29.sp, letterSpacing = (-0.6).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
)

@Composable
fun DevinTheme(mode: ThemeMode = ThemeMode.System, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val ctx = LocalContext.current
    val view = LocalView.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    SideEffect {
        if (!view.isInEditMode) {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = scheme, typography = DevinType,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)),
        content = { NoFullscreenKeyboard(content) },
    )
}

fun SessionState.color(): Color = when (this) {
    SessionState.Working, SessionState.Starting -> Color(0xFF4C8DFF)
    SessionState.NeedsYou, SessionState.NeedsApproval -> Color(0xFFF2A93B)
    SessionState.Blocked, SessionState.Error -> Color(0xFFE5534B)
    SessionState.Finished -> Color(0xFF3FB27F)
    SessionState.Sleeping -> Color(0xFF8B8F9A)
}
