package com.blazikener.devin.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer

/** A vector-drawn dimensional identity; no downloaded art, video, or rendering engine. */
@Composable
fun DevinOrb(modifier: Modifier = Modifier, animated: Boolean = false, spin: Boolean = false) {
    val turn = if (spin) {
        val transition = rememberInfiniteTransition(label = "orb spin")
        val angle by transition.animateFloat(
            0f, 360f, infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart), label = "orb spin",
        )
        angle
    } else if (animated) {
        val transition = rememberInfiniteTransition(label = "orb")
        val angle by transition.animateFloat(
            -16f, 16f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse), label = "orb tilt",
        )
        angle
    } else -8f
    // Compose's animation clock respects Android's animator duration scale (including zero).
    Canvas(modifier.graphicsLayer { rotationZ = turn; rotationY = turn * 0.6f; cameraDistance = 16 * density }) {
        val r = size.minDimension * 0.31f
        drawCircle(Brush.radialGradient(listOf(Color(0xFF58D9E1).copy(alpha = 0.18f), Color.Transparent)), radius = size.minDimension / 2)
        rotate(-28f) {
            drawOval(Color(0xFF61C7D8).copy(alpha = 0.4f), topLeft = Offset(center.x - r * 1.45f, center.y - r * 0.48f), size = Size(r * 2.9f, r * 0.96f), style = Stroke(r * 0.035f))
        }
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFFE3FCFD), Color(0xFF74DCE8), Color(0xFF25879F), Color(0xFF183B65)),
                center = center - Offset(r * 0.4f, r * 0.55f), radius = r * 1.9f,
            ), radius = r,
        )
        rotate(35f) {
            drawOval(Color.White.copy(alpha = 0.34f), topLeft = Offset(center.x - r * 0.48f, center.y - r), size = Size(r * 0.96f, r * 2), style = Stroke(r * 0.025f))
        }
        rotate(-28f) {
            drawArc(
                Color(0xFFA9F0F6), startAngle = 0f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(center.x - r * 1.45f, center.y - r * 0.48f), size = Size(r * 2.9f, r * 0.96f), style = Stroke(r * 0.045f),
            )
            drawCircle(Color(0xFFD6FAFC), radius = r * 0.09f, center = Offset(center.x + r * 1.44f, center.y))
        }
    }
}
