package com.blazikener.devin.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.blazikener.devin.data.SessionState
import com.blazikener.devin.data.ThemeMode


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
        dark -> DevinDarkColors
        else -> DevinLightColors
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
        colorScheme = scheme, typography = DevinTypography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)),
        content = { NoFullscreenKeyboard(content) },
    )
}

@Composable
fun SessionState.color(): Color = when (this) {
    SessionState.Working, SessionState.Starting -> MaterialTheme.colorScheme.tertiary
    SessionState.NeedsYou, SessionState.NeedsApproval ->
        if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFE4C48C) else Color(0xFF80591C)
    SessionState.Blocked, SessionState.Error -> MaterialTheme.colorScheme.error
    SessionState.Finished -> MaterialTheme.colorScheme.primary
    SessionState.Sleeping -> MaterialTheme.colorScheme.onSurfaceVariant
}
