package com.example.aidetest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pe3DTest {
    private val eps = 0.001f

    @Test
    fun noTiltKeepsRectangle() {
        val c = Pe3D.corners(200f, 100f, 0f, 0f)
        assertEquals(-100f, c[0], eps); assertEquals(-50f, c[1], eps)
        assertEquals(100f, c[2], eps); assertEquals(-50f, c[3], eps)
        assertEquals(100f, c[4], eps); assertEquals(50f, c[5], eps)
        assertEquals(-100f, c[6], eps); assertEquals(50f, c[7], eps)
    }

    @Test
    fun tiltXShrinksTopEdge() {
        // Sisi atas menjauh dari kamera → lebih kecil daripada sisi bawah.
        val c = Pe3D.corners(200f, 100f, 30f, 0f)
        val topWidth = c[2] - c[0]
        val bottomWidth = c[4] - c[6]
        assertTrue("top=$topWidth bottom=$bottomWidth", topWidth < bottomWidth)
    }

    @Test
    fun tiltYShrinksOneSide() {
        val c = Pe3D.corners(200f, 100f, 0f, 30f)
        val leftHeight = c[7] - c[1]
        val rightHeight = c[5] - c[3]
        assertTrue("left=$leftHeight right=$rightHeight", leftHeight != rightHeight)
    }

    @Test
    fun tiltIsClamped() {
        val a = Pe3D.corners(100f, 100f, 60f, 0f)
        val b = Pe3D.corners(100f, 100f, 90f, 0f)
        for (i in a.indices) assertEquals(a[i], b[i], eps)
    }

    @Test
    fun meshHasGridSize() {
        val m = Pe3D.mesh(100f, 100f, 20f, -10f, 16)
        assertEquals((17 * 17) * 2, m.size)
    }

    @Test
    fun meshCenterStaysAtOrigin() {
        val m = Pe3D.mesh(100f, 80f, 25f, 15f, 4)
        // Titik tengah kisi (2,2) = indeks (2*5+2)*2.
        assertEquals(0f, m[(2 * 5 + 2) * 2], eps)
        assertEquals(0f, m[(2 * 5 + 2) * 2 + 1], eps)
    }

    @Test
    fun scaleCapsLargeLayers() {
        assertEquals(1f, Pe3D.scaleFor(500f, 500f), eps)
        assertEquals(2048f / 4000f, Pe3D.scaleFor(4000f, 1000f), eps)
    }
}
