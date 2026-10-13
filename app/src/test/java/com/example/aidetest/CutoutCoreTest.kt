package com.example.aidetest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CutoutCoreTest {
    private fun square(w: Int, h: Int, l: Int, t: Int, r: Int, b: Int): ByteArray {
        val m = ByteArray(w * h)
        for (y in t until b) for (x in l until r) m[y * w + x] = 255.toByte()
        return m
    }

    private fun v(m: ByteArray, w: Int, x: Int, y: Int) = m[y * w + x].toInt() and 0xFF

    // ------------------------------------------------------------------ poligon

    @Test
    fun pointInPolygonHandlesConvexAndConcave() {
        val xs = floatArrayOf(0f, 10f, 10f, 0f)
        val ys = floatArrayOf(0f, 0f, 10f, 10f)
        assertTrue(CoMask.pointInPolygon(5f, 5f, xs, ys, 4))
        assertFalse(CoMask.pointInPolygon(15f, 5f, xs, ys, 4))
        // Bentuk U: celah tengah di luar.
        val ux = floatArrayOf(0f, 9f, 9f, 6f, 6f, 3f, 3f, 0f)
        val uy = floatArrayOf(0f, 0f, 9f, 9f, 3f, 3f, 9f, 9f)
        assertTrue(CoMask.pointInPolygon(1.5f, 6f, ux, uy, 8))
        assertFalse(CoMask.pointInPolygon(4.5f, 6f, ux, uy, 8))
    }

    @Test
    fun rasterPolygonFillsInteriorWithAntialiasedEdge() {
        val w = 20; val h = 20
        val xs = floatArrayOf(4f, 14.5f, 14.5f, 4f)
        val ys = floatArrayOf(4f, 4f, 14f, 14f)
        val m = CoMask.rasterPolygon(xs, ys, 4, w, h)
        assertEquals(255, v(m, w, 8, 8))
        assertEquals(0, v(m, w, 2, 2))
        assertEquals(0, v(m, w, 16, 8))
        // Kolom 14 hanya tertutup separuh → nilai tengah.
        val edge = v(m, w, 14, 8)
        assertTrue("edge=$edge", edge in 100..160)
        // Luas ≈ 10,5 × 10 piksel.
        assertEquals(105.0 * 255, CoMask.coverage(m).toDouble(), 255.0)
    }

    @Test
    fun rasterPolygonAgreesWithPointInPolygonAtCenters() {
        val w = 32; val h = 32
        val xs = floatArrayOf(3f, 28f, 16f, 25f, 5f)
        val ys = floatArrayOf(2f, 6f, 15f, 29f, 27f)
        val m = CoMask.rasterPolygon(xs, ys, 5, w, h)
        var mismatch = 0
        for (y in 0 until h) for (x in 0 until w) {
            val inside = CoMask.pointInPolygon(x + 0.5f, y + 0.5f, xs, ys, 5)
            val mv = v(m, w, x, y)
            if (inside && mv < 60 || !inside && mv > 195) mismatch++
        }
        assertEquals(0, mismatch)
    }

    @Test
    fun polygonClosesNearFirstPointOnly() {
        val xs = floatArrayOf(0f, 10f, 10f)
        val ys = floatArrayOf(0f, 0f, 10f)
        assertTrue(CoMask.closesPolygon(1f, 1f, xs, ys, 3, 2f))
        assertFalse(CoMask.closesPolygon(5f, 5f, xs, ys, 3, 2f))
        assertFalse(CoMask.closesPolygon(0f, 0f, xs, ys, 2, 2f))
    }

    // ------------------------------------------------------------------ kuas & mode

    @Test
    fun brushAlphaRespectsHardness() {
        assertEquals(1f, CoMask.brushAlpha(0f, 10f, 0), 1e-4f)
        assertEquals(1f, CoMask.brushAlpha(4.9f, 10f, 50), 1e-4f)
        assertEquals(0f, CoMask.brushAlpha(10f, 10f, 100), 1e-4f)
        assertEquals(0.5f, CoMask.brushAlpha(7.5f, 10f, 50), 1e-3f)
        assertTrue(CoMask.brushAlpha(9f, 10f, 0) < CoMask.brushAlpha(3f, 10f, 0))
    }

    @Test
    fun stampAddsAndSubtracts() {
        val w = 30; val h = 30
        val m = ByteArray(w * h)
        val rc = CoMask.stamp(m, w, h, 15f, 15f, 5f, 100, add = true)
        assertFalse(rc.isEmpty)
        assertEquals(255, v(m, w, 15, 15))
        assertEquals(0, v(m, w, 25, 25))
        CoMask.stamp(m, w, h, 15f, 15f, 2f, 100, add = false)
        assertEquals(0, v(m, w, 15, 15))
        assertEquals(255, v(m, w, 18, 15))
    }

    @Test
    fun combineModes() {
        val a = byteArrayOf(0, 100, 255.toByte(), 200.toByte())
        val b = byteArrayOf(50, 50, 255.toByte(), 0)
        val add = a.copyOf(); CoMask.combine(add, b, CoSelMode.ADD)
        assertArrayEquals(byteArrayOf(50, 100, 255.toByte(), 200.toByte()), add)
        val sub = a.copyOf(); CoMask.combine(sub, b, CoSelMode.SUBTRACT)
        assertEquals(0, sub[0].toInt() and 0xFF)
        assertEquals(100 * 205 / 255, sub[1].toInt() and 0xFF)
        assertEquals(0, sub[2].toInt() and 0xFF)
        assertEquals(200, sub[3].toInt() and 0xFF)
        val rep = a.copyOf(); CoMask.combine(rep, b, CoSelMode.REPLACE)
        assertArrayEquals(b, rep)
    }

    @Test
    fun invertAndEmpty() {
        val m = ByteArray(4)
        assertTrue(CoMask.isEmpty(m))
        CoMask.invert(m)
        assertFalse(CoMask.isEmpty(m))
        assertEquals(255, m[2].toInt() and 0xFF)
    }

    // ------------------------------------------------------------------ feather & refine

    @Test
    fun gaussianKernelIsNormalisedAndSymmetric() {
        for (r in 1..4) {
            val k = CoMask.gaussianKernel(r)
            assertEquals(2 * r + 1, k.size)
            assertEquals(1f, k.sum(), 1e-4f)
            assertEquals(k.first(), k.last(), 1e-6f)
            assertTrue(k[r] >= k[0])
        }
    }

    @Test
    fun boxesApproximateGaussVariance() {
        val sigma = 6f
        val boxes = CoMask.boxesForGauss(sigma)
        assertEquals(3, boxes.size)
        boxes.forEach { assertEquals(1, it % 2) }
        // Varians kotak lebar w = (w²−1)/12; jumlah ≈ sigma².
        val variance = boxes.sumOf { (it * it - 1) / 12.0 }
        assertEquals(sigma * sigma.toDouble(), variance, sigma * 1.5)
    }

    @Test
    fun featherSoftensEdgeButKeepsArea() {
        val w = 40; val h = 40
        val m = square(w, h, 10, 10, 30, 30)
        for (rad in listOf(2f, 8f)) {
            val f = CoMask.feather(m, w, h, rad)
            assertTrue(v(f, w, 20, 20) >= 250)
            val e = v(f, w, 10, 20)
            assertTrue("rad=$rad e=$e", e in 60..230)
            assertTrue(v(f, w, 8, 20) > 0)
            assertEquals(CoMask.coverage(m).toDouble(), CoMask.coverage(f).toDouble(), CoMask.coverage(m) * 0.04)
        }
        assertArrayEquals(m, CoMask.feather(m, w, h, 0f))
    }

    @Test
    fun refineExpandsAndContracts() {
        val w = 50; val h = 50
        val m = square(w, h, 15, 15, 35, 35)
        val base = CoMask.coverage(m)
        val grow = CoMask.refine(m, w, h, 4, 0, 0f)
        val shrink = CoMask.refine(m, w, h, -4, 0, 0f)
        assertTrue(CoMask.coverage(grow) > base * 1.3)
        assertTrue(CoMask.coverage(shrink) < base * 0.75)
        assertEquals(255, v(grow, w, 12, 25))
        assertEquals(0, v(shrink, w, 17, 25))
        assertArrayEquals(m, CoMask.refine(m, w, h, 0, 0, 0f))
        // Halus: titik tunggal terisolasi (gerigi) hilang, isi utama tetap.
        val noisy = m.copyOf(); noisy[5 * w + 5] = 255.toByte()
        val smooth = CoMask.refine(noisy, w, h, 0, 60, 0f)
        assertEquals(0, v(smooth, w, 5, 5))
        assertEquals(255, v(smooth, w, 25, 25))
    }

    @Test
    fun edgeAwareSeparatesByColour() {
        val w = 24; val h = 8
        // Kiri merah (latar depan), kanan biru; mask kasar memotong di x=14 padahal warna berganti di x=12.
        val px = IntArray(w * h) { i -> if (i % w < 12) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val mask = square(w, h, 0, 0, 14, h)
        val out = CoMask.edgeAware(mask, px, w, h, 3, 1f)
        assertEquals(0, v(out, w, 13, 4))
        assertEquals(255, v(out, w, 11, 4))
        // Warna mirip → mask asli dipertahankan.
        val flat = IntArray(w * h) { 0xFF808080.toInt() }
        assertArrayEquals(mask, CoMask.edgeAware(mask, flat, w, h, 3, 1f))
    }

    // ------------------------------------------------------------------ piksel

    @Test
    fun extractAndCutSplitAlpha() {
        val px = intArrayOf(0xFF112233.toInt(), 0x80445566.toInt())
        val mask = byteArrayOf(255.toByte(), 128.toByte())
        val ex = CoMask.extract(px, mask)
        assertEquals(0xFF112233.toInt(), ex[0])
        assertEquals(0x40, ex[1] ushr 24)
        assertEquals(0x445566, ex[1] and 0xFFFFFF)
        val c = px.copyOf(); CoMask.cut(c, mask)
        assertEquals(0, c[0] ushr 24)
        assertEquals(0x40, c[1] ushr 24)
    }

    @Test
    fun alphaAndMaskBounds() {
        val w = 10; val h = 6
        val px = IntArray(w * h)
        px[2 * w + 3] = 0xFF000000.toInt(); px[4 * w + 7] = 0x10FFFFFF
        assertEquals(CoRect(3, 2, 8, 5), CoMask.alphaBounds(px, w, h))
        assertTrue(CoMask.alphaBounds(IntArray(4), 2, 2).isEmpty)
        assertEquals(CoRect(1, 1, 3, 4), CoMask.maskBounds(square(w, h, 1, 1, 3, 4), w, h))
    }

    @Test
    fun srcOverAndLerp() {
        assertEquals(0xFFFF0000.toInt(), CoPaint.srcOver(0xFF0000FF.toInt(), 0xFFFF0000.toInt(), 1f))
        assertEquals(0xFF0000FF.toInt(), CoPaint.srcOver(0xFF0000FF.toInt(), 0xFFFF0000.toInt(), 0f))
        val half = CoPaint.srcOver(0x00000000, 0xFFFFFFFF.toInt(), 0.5f)
        assertEquals(128, half ushr 24)
        assertEquals(0xFFFFFF, half and 0xFFFFFF)
        assertEquals(0x80808080.toInt(), CoPaint.lerp(0, 0xFFFFFFFF.toInt(), 0.5f + 0.5f / 255f))
    }

    @Test
    fun eraseRestorePaintCloneOnDab() {
        val w = 20; val h = 20
        val rc = CoRect.around(10f, 10f, 4f, w, h)
        val buf = IntArray(rc.w * rc.h) { 0xFF00FF00.toInt() }
        val center = (10 - rc.t) * rc.w + (10 - rc.l)
        CoPaint.erase(buf, rc, 10.5f, 10.5f, 4f, 100, 1f, null, w)
        assertEquals(0, buf[center] ushr 24)
        assertEquals(0xFF00FF00.toInt(), buf[0])
        val src = IntArray(buf.size) { 0xFF123456.toInt() }
        CoPaint.restore(buf, src, rc, 10.5f, 10.5f, 4f, 100, 1f, null, w)
        assertEquals(0xFF123456.toInt(), buf[center])
        CoPaint.paint(buf, 0xFFFF0000.toInt(), rc, 10.5f, 10.5f, 4f, 100, 1f, null, w)
        assertEquals(0xFFFF0000.toInt(), buf[center])
        val sample = IntArray(buf.size) { 0xFF0000FF.toInt() }
        CoPaint.clone(buf, sample, rc, 10.5f, 10.5f, 4f, 100, 1f, null, w)
        assertEquals(0xFF0000FF.toInt(), buf[center])
        // Batas seleksi: mask 0 di pusat → tidak berubah.
        val clip = ByteArray(w * h)
        CoPaint.paint(buf, 0xFFFFFFFF.toInt(), rc, 10.5f, 10.5f, 4f, 100, 1f, clip, w)
        assertEquals(0xFF0000FF.toInt(), buf[center])
    }

    @Test
    fun strokeStepsAreEvenlySpaced() {
        val steps = CoPaint.strokeSteps(0f, 0f, 10f, 0f, 2.5f)
        assertEquals(4, steps.size)
        assertEquals(10f, steps.last().first, 1e-4f)
        assertTrue(CoPaint.strokeSteps(0f, 0f, 1f, 0f, 2f).isEmpty())
    }

    @Test
    fun overlayMarksEdgesAndTint() {
        val w = 10; val h = 10
        val m = square(w, h, 3, 3, 7, 7)
        val rc = CoRect(0, 0, w, h)
        val out = IntArray(w * h)
        CoMask.overlay(m, w, h, rc, 0xFF3366FF.toInt(), out)
        assertEquals(0, out[0])
        val inner = out[5 * w + 5]
        assertEquals(0x3366FF, inner and 0xFFFFFF)
        assertEquals(110, inner ushr 24)
        assertEquals(255, out[3 * w + 4] ushr 24)
    }

    // ------------------------------------------------------------------ ubin, kompres, riwayat

    @Test
    fun tilesCoverRect() {
        val w = 300; val h = 200
        assertEquals(listOf(0), CoTiles.tilesFor(CoRect(0, 0, 10, 10), w, h))
        val t = CoTiles.tilesFor(CoRect(120, 120, 140, 140), w, h)
        assertEquals(listOf(0, 1, 3, 4), t)
        assertEquals(CoRect(256, 128, 300, 200), CoTiles.rectOf(5, w, h))
        assertTrue(CoTiles.tilesFor(CoRect(400, 0, 500, 10), w, h).isEmpty())
    }

    @Test
    fun packRoundTrip() {
        val px = IntArray(5000) { if (it % 7 == 0) 0x12345678 else -1 }
        val z = CoPack.packInts(px)
        assertTrue(z.size < px.size * 4 / 4)
        assertArrayEquals(px, CoPack.unpackInts(z, px.size))
        val b = ByteArray(1000) { (it % 3).toByte() }
        assertArrayEquals(b, CoPack.inflate(CoPack.deflate(b), b.size))
    }

    @Test
    fun historyBoundsCountAndBytes() {
        val h = CoHistory<Int>(3, 100) { it.toLong() }
        h.push(10); h.push(10); h.push(10); h.push(10)
        assertEquals(3, h.undoCount)
        assertEquals(10, h.undo())
        assertTrue(h.canRedo)
        assertEquals(10, h.redo())
        h.undo()
        h.push(20)
        assertFalse(h.canRedo)
        h.push(90)
        // 10 + 20 + 90 > 100 → yang tertua dibuang sampai muat.
        assertEquals(1, h.undoCount)
        val big = CoHistory<Int>(5, 50) { it.toLong() }
        big.push(500)
        assertEquals(1, big.undoCount)
        assertEquals(500, big.undo())
        assertNull(big.undo())
    }

    @Test
    fun cloneOffsetIsAligned() {
        val c = CoClone()
        assertFalse(c.begin(5f, 5f))
        c.setSource(10f, 20f)
        assertTrue(c.begin(50f, 50f))
        assertEquals(-40f, c.dx, 1e-5f)
        assertEquals(-30f, c.dy, 1e-5f)
        c.begin(80f, 10f)
        assertEquals(-40f, c.dx, 1e-5f)
        c.setSource(0f, 0f)
        c.begin(10f, 10f)
        assertEquals(-10f, c.dx, 1e-5f)
    }

    // ------------------------------------------------------------------ manifest & nama

    @Test
    fun manifestRoundTripAndTolerance() {
        val m = CoManifest(1, "Tokoh", "source.png", 800, 600, listOf(
            CoLayerMeta("a", "Badan", "layer_a.png"),
            CoLayerMeta("b", "Rambut", "layer_b.png", visible = false, locked = true, opacity = 0.5f)
        ), "b", 1234L)
        val d = CoManifestCodec.decode(CoManifestCodec.encode(m))
        assertEquals(m, d)
        val bad = mapOf<String, Any?>("w" to 10, "h" to 10, "source" to "../x", "layers" to listOf(
            mapOf("file" to "../evil.png"), mapOf("file" to "ok.png", "opacity" to 7), "junk"
        ))
        val t = CoManifestCodec.decode(bad)
        assertNotNull(t)
        assertEquals("", t!!.source)
        assertEquals(1, t.layers.size)
        assertEquals(1f, t.layers[0].opacity, 1e-6f)
        assertEquals("ok", t.layers[0].id)
        assertNull(CoManifestCodec.decode(mapOf("w" to 0, "h" to 5)))
    }

    @Test
    fun uniqueNames() {
        assertEquals("Mata", CoNames.unique("Mata", listOf("Rambut")))
        assertEquals("Mata 2", CoNames.unique("Mata", listOf("Mata")))
        assertEquals("Mata 3", CoNames.unique("Mata", listOf("Mata", "Mata 2")))
        assertEquals("Layer", CoNames.unique("  ", emptyList()))
        assertEquals(6, CoNames.PRESETS.size)
    }
}
