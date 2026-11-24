package com.blazikener.devin.ui.components

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class LaunchTilesMeshTest {
    @Test fun roundedSolidsHaveFinitePositionsUnitNormalsAndActualDepth() {
        val mesh = launchTilesMesh()
        assertEquals(3 * 6 * 10 * 10 * 6 * 9, mesh.size)
        val depths = mutableSetOf<Float>()
        val layers = mutableSetOf<Int>()
        mesh.toList().chunked(9).forEach { vertex ->
            assertTrue(vertex.all { it.isFinite() })
            assertEquals(1f, sqrt(vertex[3] * vertex[3] + vertex[4] * vertex[4] + vertex[5] * vertex[5]), .0001f)
            assertTrue(vertex[0] in -1.01f..1.01f)
            assertTrue(vertex[1] in -.63f.. .63f)
            assertTrue(vertex[2] in -.65f.. .65f)
            depths += vertex[2]
            layers += when { vertex[1] < -.2f -> 0; vertex[1] > .2f -> 2; else -> 1 }
        }
        assertTrue(depths.size > 2) // Rounded geometry, not a tilted plane or flat box sides.
        assertEquals(setOf(0, 1, 2), layers)
    }
}
