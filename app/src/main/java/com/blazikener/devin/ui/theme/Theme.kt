package com.blazikener.devin.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.blazikener.devin.data.SessionState
import com.blazikener.devin.data.ThemeMode

private val Dark = darkColorScheme(
    primary = Color(0xFF6E9BFF),
    onPrimary = Color(0xFF001A43),
    primaryContainer = Color(0xFF1C3A73),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF8FD1C4),
    background = Color(0xFF0E1014),
    onBackground = Color(0xFFE3E4EA),
    surface = Color(0xFF0E1014),
    onSurface = Color(0xFFE3E4EA),
    surfaceVariant = Color(0xFF1C1F26),
    onSurfaceVariant = Color(0xFFB4B8C4),
    surfaceContainer = Color(0xFF171A20),
    surfaceContainerHigh = Color(0xFF1E2129),
    surfaceContainerHighest = Color(0xFF262A33),
    surfaceContainerLow = Color(0xFF13151A),
    outline = Color(0xFF3A3F4B),
    outlineVariant = Color(0xFF2A2E37),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2457D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF001A43),
    secondary = Color(0xFF2E6B60),
    background = Color(0xFFFAFAFC),
    surface = Color(0xFFFAFAFC),
    surfaceVariant = Color(0xFFEEF0F5),
    surfaceContainer = Color(0xFFF1F2F6),
    surfaceContainerHigh = Color(0xFFEBECF1),
    surfaceContainerHighest = Color(0xFFE4E6EC),
    surfaceContainerLow = Color(0xFFF6F7FA),
)

@Composable
fun DevinTheme(mode: ThemeMode = ThemeMode.System, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

fun SessionState.color(): Color = when (this) {
    SessionState.Working, SessionState.Starting -> Color(0xFF4C8DFF)
    SessionState.NeedsYou, SessionState.NeedsApproval -> Color(0xFFF2A93B)
    SessionState.Blocked, SessionState.Error -> Color(0xFFE5534B)
    SessionState.Finished -> Color(0xFF3FB27F)
    SessionState.Sleeping -> Color(0xFF8B8F9A)
}
