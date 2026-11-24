package com.blazikener.devin.ui.components

import kotlin.math.sqrt

/** Designer's three closed rounded solids. Position, normal, colour; no textures or product artwork. */
internal fun launchTilesMesh(): FloatArray {
    val vertices = ArrayList<Float>(3 * 6 * 10 * 10 * 6 * 9)
    val palettes = arrayOf(floatArrayOf(.29f, .48f, .35f), floatArrayOf(.82f, .85f, .74f), floatArrayOf(.43f, .64f, .48f))
    val half = floatArrayOf(.95f, .16f, .64f)
    fun point(face: Int, u: Float, v: Float, layer: Int): FloatArray {
        val p = when (face) {
            0 -> floatArrayOf(.95f, u * .16f, v * .64f)
            1 -> floatArrayOf(-.95f, u * .16f, v * .64f)
            2 -> floatArrayOf(u * .95f, .16f, v * .64f)
            3 -> floatArrayOf(u * .95f, -.16f, v * .64f)
            4 -> floatArrayOf(u * .95f, v * .16f, .64f)
            else -> floatArrayOf(u * .95f, v * .16f, -.64f)
        }
        val q = FloatArray(3) { p[it].coerceIn(-half[it] + .1f, half[it] - .1f) }
        val normal = FloatArray(3) { p[it] - q[it] }
        val length = sqrt(normal.sumOf { (it * it).toDouble() }).toFloat()
        for (i in 0..2) normal[i] /= length
        return floatArrayOf(
            q[0] + normal[0] * .1f + (layer - 1) * .055f,
            q[1] + normal[1] * .1f + (layer - 1) * .46f,
            q[2] + normal[2] * .1f,
            normal[0], normal[1], normal[2],
            palettes[layer][0], palettes[layer][1], palettes[layer][2],
        )
    }
    for (layer in 0..2) for (face in 0..5) for (y in 0..9) for (x in 0..9) {
        val quad = arrayOf(x to y, x + 1 to y, x + 1 to y + 1, x to y + 1).map { (a, b) ->
            point(face, a / 10f * 2 - 1, b / 10f * 2 - 1, layer)
        }
        for (i in intArrayOf(0, 1, 2, 0, 2, 3)) vertices.addAll(quad[i].toList())
    }
    return vertices.toFloatArray()
}
