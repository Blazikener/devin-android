package com.blazikener.devin.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Round-three attention pair. A label accompanies colour everywhere it is used. */
val attentionContainer: Color
    @Composable get() = if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color(0xFF403725) else Color(0xFFF2E9D6)

val onAttentionContainer: Color
    @Composable get() = if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color(0xFFE4C48C) else Color(0xFF80591C)
