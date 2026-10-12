package com.example.aidetest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignLayoutTest {
    private val eps = 0.01f

    @Test
    fun snapsToCanvasCenterWithinThreshold() {
        // Kotak 100×50 di kanvas 1000×600, pusat di (495, 303): dekat tengah (500, 300).
        val box = DsRect(445f, 278f, 545f, 328f)
        val s = DesignLayout.snapMove(box, emptyList(), 1000f, 600f, threshold = 8f, smart = true, gridStep = 0f)
        assertEquals(5f, s.dx, eps)
        assertEquals(-3f, s.dy, eps)
        assertTrue(s.guides.any { it.vertical && it.kind == DsGuideKind.CANVAS_CENTER && it.pos == 500f })
        assertTrue(s.guides.any { !it.vertical && it.kind == DsGuideKind.CANVAS_CENTER && it.pos == 300f })
    }

    @Test
    fun noSnapOutsideThreshold() {
        val box = DsRect(400f, 100f, 450f, 150f)
        val s = DesignLayout.snapMove(box, emptyList(), 1000f, 600f, threshold = 8f, smart = true, gridStep = 0f)
        assertEquals(0f, s.dx, eps)
        assertEquals(0f, s.dy, eps)
        assertTrue(s.guides.isEmpty())
    }

    @Test
    fun snapsToOtherObjectEdge() {
        val other = DsRect(200f, 200f, 300f, 300f)
        val box = DsRect(304f, 50f, 354f, 100f) // tepi kiri 4px dari tepi kanan objek lain
        val s = DesignLayout.snapMove(box, listOf(other), 1000f, 600f, 8f, true, 0f)
        assertEquals(-4f, s.dx, eps)
        assertTrue(s.guides.any { it.vertical && it.kind == DsGuideKind.OBJECT && it.pos == 300f })
    }

    @Test
    fun gridSnapWhenSmartMisses() {
        val box = DsRect(70f, 90f, 120f, 140f)
        val s = DesignLayout.snapMove(box, emptyList(), 1000f, 600f, 8f, smart = false, gridStep = 32f)
        assertEquals(64f - 70f, s.dx, eps)
        assertEquals(96f - 90f, s.dy, eps)
        assertTrue(s.guides.isEmpty())
    }

    @Test
    fun alignSingleToCanvas() {
        val b = DsRect(10f, 20f, 110f, 70f)
        val (dx, dy) = DesignLayout.align(listOf(b), DsAlign.H_CENTER, 1000f, 600f).single()
        assertEquals(440f, dx, eps)
        assertEquals(0f, dy, eps)
        val right = DesignLayout.align(listOf(b), DsAlign.RIGHT, 1000f, 600f).single()
        assertEquals(890f, right.first, eps)
        val bottom = DesignLayout.align(listOf(b), DsAlign.BOTTOM, 1000f, 600f).single()
        assertEquals(530f, bottom.second, eps)
    }

    @Test
    fun alignMultipleToSelectionBounds() {
        val a = DsRect(10f, 10f, 60f, 60f)
        val b = DsRect(200f, 100f, 300f, 150f)
        val res = DesignLayout.align(listOf(a, b), DsAlign.LEFT, 1000f, 600f)
        assertEquals(0f, res[0].first, eps)
        assertEquals(-190f, res[1].first, eps)
        val top = DesignLayout.align(listOf(a, b), DsAlign.TOP, 1000f, 600f)
        assertEquals(-90f, top[1].second, eps)
    }

    @Test
    fun centerGroupMovesUnionToCanvasCenter() {
        val a = DsRect(0f, 0f, 100f, 100f)
        val b = DsRect(100f, 100f, 200f, 200f)
        val (dx, dy) = DesignLayout.centerGroup(listOf(a, b), 1000f, 600f, true, true)
        assertEquals(400f, dx, eps)
        assertEquals(200f, dy, eps)
    }

    @Test
    fun distributeEqualGaps() {
        val a = DsRect(0f, 0f, 100f, 10f)
        val b = DsRect(130f, 0f, 180f, 10f)
        val c = DsRect(400f, 0f, 500f, 10f)
        val d = DesignLayout.distribute(listOf(c, a, b), horizontal = true)
        // Lebar total 250, bentang 500 → celah (500-250)/2 = 125. b harus mulai di 225.
        assertEquals(0f, d[0], eps)
        assertEquals(0f, d[1], eps)
        assertEquals(95f, d[2], eps)
        assertTrue(DesignLayout.distribute(listOf(a, b), true).isEmpty())
    }

    @Test
    fun rotatedBoundsAndHitTest() {
        val box = DsBox(0f, 0f, 200f, 100f, 90f)
        val r = box.bounds()
        assertEquals(100f, r.width, 0.05f)
        assertEquals(200f, r.height, 0.05f)
        assertEquals(100f, r.cx, eps)
        // Titik (100, -40) berada di dalam kotak yang diputar 90°, tapi di luar kotak asli.
        assertTrue(DesignLayout.hit(box, 100f, -40f, 0f))
        assertFalse(DesignLayout.hit(box, 190f, 50f, 0f))
    }

    @Test
    fun resizeKeepsOppositeCornerFixed() {
        val start = DsBox(100f, 100f, 100f, 50f, 0f)
        val out = DesignLayout.resizeFromCorner(start, 1, 1, 260f, 180f, uniform = false, minSize = 8f)
        assertEquals(100f, out.x, eps)
        assertEquals(100f, out.y, eps)
        assertEquals(160f, out.w, eps)
        assertEquals(80f, out.h, eps)
        val uni = DesignLayout.resizeFromCorner(start, 1, 1, 300f, 200f, uniform = true, minSize = 8f)
        assertEquals(2f, uni.w / start.w, 0.01f)
        assertEquals(uni.w / uni.h, 2f, 0.01f)
        // Sudut kiri atas tetap untuk kotak yang diputar 90°.
        val rot = DsBox(0f, 0f, 100f, 50f, 90f)
        val fixedBefore = DesignLayout.toWorld(rot, -50f, -25f)
        val res = DesignLayout.resizeFromCorner(rot, 1, 1, 0f, 200f, uniform = false, minSize = 8f)
        val fixedAfter = DesignLayout.toWorld(res, -res.w / 2f, -res.h / 2f)
        assertEquals(fixedBefore.first, fixedAfter.first, 0.05f)
        assertEquals(fixedBefore.second, fixedAfter.second, 0.05f)
    }

    @Test
    fun rotationSnapsTo45() {
        val box = DsBox(0f, 0f, 100f, 100f)
        assertEquals(0f, DesignLayout.rotationTo(box, 52f, -100f, 4f), 0.01f) // ~1° dari atas → 0
        assertEquals(90f, DesignLayout.rotationTo(box, 200f, 50f, 4f), 0.01f)
        assertEquals(-90f, DesignLayout.rotationTo(box, -100f, 50f, 4f), 0.01f)
    }
}
