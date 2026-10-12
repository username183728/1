package com.example.aidetest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsLogicTest {
    private val eps = 0.001f

    // ------------------------------------------------------------------ id & migrasi

    @Test
    fun legacyIdsAliasToCreativeStudioWithEntryAction() {
        assertEquals("creativestudio", CsToolIds.canonical("photoeditor"))
        assertEquals("creativestudio", CsToolIds.canonical("designstudio"))
        assertEquals("creativestudio", CsToolIds.canonical("bgremover"))
        assertEquals("zip", CsToolIds.canonical("zip"))
        assertEquals(CsToolIds.Entry.PHOTO, CsToolIds.entryFor("photoeditor"))
        assertEquals(CsToolIds.Entry.CUTOUT, CsToolIds.entryFor("bgremover"))
        assertEquals(CsToolIds.Entry.DEFAULT, CsToolIds.entryFor("designstudio"))
        assertEquals(CsToolIds.Entry.DEFAULT, CsToolIds.entryFor("creativestudio"))
    }

    @Test
    fun favoritesAndRecentsMigrateWithoutDuplicates() {
        assertEquals("creativestudio,zip,json", CsToolIds.migrateIdList("photoeditor,zip,designstudio,json,bgremover,creativestudio"))
        assertEquals("zip,creativestudio", CsToolIds.migrateIdList(" zip , , bgremover "))
        assertEquals("", CsToolIds.migrateIdList(""))
        assertEquals("", CsToolIds.migrateIdList(null))
        assertTrue(CsToolIds.hasLegacy("zip,photoeditor"))
        assertTrue(CsToolIds.hasLegacy("zip:3,bgremover:1"))
        assertFalse(CsToolIds.hasLegacy("zip,creativestudio"))
        assertFalse(CsToolIds.hasLegacy(null))
    }

    @Test
    fun usageCountsMergeIntoNewId() {
        assertEquals("creativestudio:8,zip:5", CsToolIds.migrateUsage("zip:5,photoeditor:3,designstudio:4,bgremover:1"))
        // Entri rusak dilewati; urutan stabil untuk jumlah sama.
        assertEquals("a:2,creativestudio:2", CsToolIds.migrateUsage("a:2,broken,photoeditor:x,bgremover:2"))
    }

    @Test
    fun pinnedSetMigratesOnlyWhenNeeded() {
        assertEquals(setOf("zip", "creativestudio"), CsToolIds.migrateIdSet(setOf("zip", "photoeditor", "bgremover")))
        assertNull(CsToolIds.migrateIdSet(setOf("zip", "creativestudio")))
        assertNull(CsToolIds.migrateIdSet(null))
    }

    // ------------------------------------------------------------------ ukuran

    @Test
    fun exportSizeRespectsScaleCustomAndCap() {
        assertEquals(2560 to 1440, CsMath.exportSize(1280, 720, 2f, 0))
        assertEquals(640 to 360, CsMath.exportSize(1280, 720, 0.5f, 0))
        assertEquals(1080 to 608, CsMath.exportSize(1280, 720, 1f, 1080))
        // 3000×2000 × 2 = 6000 → dibatasi 4096 pada sisi terpanjang.
        assertEquals(4096 to 2731, CsMath.exportSize(3000, 2000, 2f, 0))
        assertEquals(1280 to 720, CsMath.exportSize(1280, 720, Float.NaN, 0))
        assertEquals(1 to 1, CsMath.exportSize(1, 1, 0.01f, 0))
    }

    @Test
    fun workSideShrinksWithManyPhotos() {
        assertEquals(1600, CsMath.workLongSide(1))
        assertEquals(1600, CsMath.workLongSide(2))
        assertEquals(1280, CsMath.workLongSide(4))
        assertEquals(1024, CsMath.workLongSide(9))
    }

    @Test
    fun outputSizeFollowsRotationAndCrop() {
        assertEquals(400 to 300, CsMath.outputSize(400, 300, 0, 0f, 0f, 1f, 1f))
        assertEquals(300 to 400, CsMath.outputSize(400, 300, 1, 0f, 0f, 1f, 1f))
        assertEquals(150 to 200, CsMath.outputSize(400, 300, 3, 0f, 0f, 0.5f, 0.5f))
        assertEquals(1 to 1, CsMath.outputSize(0, 0, 0, 0f, 0f, 1f, 1f))
    }

    @Test
    fun fitInsideAndCenteredCrop() {
        val (w, h) = CsMath.fitInside(2000f, 1000f, 1000f, 1000f, 0.6f, noUpscale = true)
        assertEquals(600f, w, eps)
        assertEquals(300f, h, eps)
        val (w2, _) = CsMath.fitInside(100f, 100f, 1000f, 1000f, 0.6f, noUpscale = true)
        assertEquals(100f, w2, eps) // tidak diperbesar
        val c = CsMath.centeredCrop(400, 200, 1f)
        assertArrayEquals(floatArrayOf(0.25f, 0f, 0.75f, 1f), c, eps)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), CsMath.centeredCrop(400, 200, -1f), eps)
    }

    // ------------------------------------------------------------------ crop

    @Test
    fun cropMoveStaysInsideFrame() {
        val r = CsMath.cropDrag(8, floatArrayOf(0.2f, 0.2f, 0.6f, 0.6f), 0.9f, -0.5f, 1000f, 1000f, null)
        assertArrayEquals(floatArrayOf(0.6f, 0f, 1f, 0.4f), r, eps)
    }

    @Test
    fun cropCornerKeepsPixelRatio() {
        // Bingkai 1000×500, rasio 1:1, tarik sudut kanan-bawah.
        val r = CsMath.cropDrag(2, floatArrayOf(0f, 0f, 0.5f, 1f), 0.2f, 0f, 1000f, 500f, 1f)
        val pw = (r[2] - r[0]) * 1000f
        val ph = (r[3] - r[1]) * 500f
        assertEquals(1f, pw / ph, 0.01f)
        assertTrue(r[2] <= 1f + eps && r[3] <= 1f + eps)
    }

    @Test
    fun cropOnTinyRectDoesNotThrow() {
        val r = CsMath.cropDrag(4, floatArrayOf(0f, 0f, 0.001f, 0.001f), 0.5f, 0.5f, 100f, 100f, null)
        assertEquals(0f, r[1], eps)
        val e = CsMath.cropDrag(0, floatArrayOf(0.5f, 0.5f, 0.5005f, 0.5005f), -0.2f, -0.2f, 50f, 50f, 1.5f)
        assertTrue(e.all { !it.isNaN() })
    }

    @Test
    fun docCanvasKeepsDensityAndCap() {
        assertEquals(1500 to 1000, CsMath.docCanvasSize(1500, 1000, 1f))
        assertEquals(750 to 500, CsMath.docCanvasSize(1500, 1000, 0.5f))
        assertEquals(4096 to 2048, CsMath.docCanvasSize(3000, 1500, 2f))
        assertEquals(16 to 16, CsMath.docCanvasSize(2, 2, 1f))
    }

    @Test
    fun cropRemapKeepsContentInPlace() {
        // Kanvas 1000×500 = seluruh foto. Crop ke separuh kanan → kanvas 500×500.
        val old = floatArrayOf(0f, 0f, 1f, 1f)
        val new = floatArrayOf(0.5f, 0f, 1f, 1f)
        val (x, y) = CsMath.remapCrop(750f, 250f, 1000f, 500f, old, 500f, 500f, new)
        assertEquals(250f, x, eps)
        assertEquals(250f, y, eps)
        assertEquals(1f, CsMath.cropContentScale(1000f, old, 500f, new), eps)
    }

    @Test
    fun resizeForOutputKeepsCenterAndScale() {
        val r = CsMath.resizeForOutput(100f, 100f, 200f, 100f, 400, 200, 200, 200)
        assertArrayEquals(floatArrayOf(150f, 100f, 100f, 100f), r, eps)
    }

    @Test
    fun exportSourceSideMatchesLayerSize() {
        // Foto utama: kanvas = keluaran foto pada resolusi penuh → dekode resolusi penuh.
        assertEquals(3000, CsMath.exportSourceSide(3000f, 2000f, 1f, 3000, 2000, 3000))
        // Layer kecil (30%) → cukup ±900 px.
        assertEquals(900, CsMath.exportSourceSide(900f, 600f, 1f, 3000, 2000, 3000))
        // Ekspor 2× dibatasi 3072 dan ukuran sumber.
        assertEquals(2048, CsMath.exportSourceSide(4000f, 4000f, 2f, 2048, 2048, 2048))
        assertEquals(256, CsMath.exportSourceSide(10f, 10f, 1f, 3000, 2000, 3000))
    }

    // ------------------------------------------------------------------ gradien & alpha

    @Test
    fun gradientLineCoversBox() {
        assertArrayEquals(floatArrayOf(0f, 50f, 200f, 50f), CsMath.gradientLine(200f, 100f, 0f), 0.01f)
        assertArrayEquals(floatArrayOf(100f, 0f, 100f, 100f), CsMath.gradientLine(200f, 100f, 90f), 0.01f)
    }

    @Test
    fun alphaBoundsAndPad() {
        val w = 6
        val h = 4
        val px = IntArray(w * h)
        px[1 * w + 2] = 0xFF000000.toInt()
        px[2 * w + 4] = 0x80FFFFFF.toInt()
        px[3 * w + 0] = 0x05FFFFFF // di bawah ambang
        assertArrayEquals(intArrayOf(2, 1, 5, 3), CsMath.alphaBounds(px, w, h))
        assertNull(CsMath.alphaBounds(IntArray(4), 2, 2))
        assertArrayEquals(intArrayOf(0, 0, 6, 4), CsMath.padRect(intArrayOf(2, 1, 5, 3), w, h, 3))
    }

    @Test
    fun bandedAlphaBoundsMatchWholeImage() {
        // Sama seperti trimTransparent: pindai per pita (buffer pita bisa berisi sisa pita sebelumnya).
        val w = 7
        val h = 5
        val px = IntArray(w * h)
        px[0 * w + 3] = 0xFF000000.toInt()
        px[3 * w + 6] = 0xFF000000.toInt()
        px[4 * w + 1] = 0x40000000
        val band = 2
        val buf = IntArray(w * band)
        var acc: IntArray? = null
        var y = 0
        while (y < h) {
            val rows = minOf(band, h - y)
            java.util.Arrays.fill(buf, 0x7F000000) // sisa data lama di luar [rows] tidak boleh terbaca
            System.arraycopy(px, y * w, buf, 0, rows * w)
            CsMath.alphaBounds(buf, w, rows)?.let { r -> acc = CsMath.unionBounds(acc, intArrayOf(r[0], r[1] + y, r[2], r[3] + y)) }
            y += rows
        }
        assertArrayEquals(CsMath.alphaBounds(px, w, h), acc)
        assertArrayEquals(intArrayOf(1, 0, 7, 5), acc)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), CsMath.unionBounds(null, intArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun brushAndMaskHelpers() {
        assertEquals(1, CsMath.brushSteps(0f, 10f))
        assertEquals(4, CsMath.brushSteps(12f, 10f))
        val inv = CsMath.invertMask(byteArrayOf(0, 255.toByte(), 100))
        assertEquals(255, inv[0].toInt() and 0xFF)
        assertEquals(0, inv[1].toInt() and 0xFF)
        assertEquals(155, inv[2].toInt() and 0xFF)
        assertEquals(-170f, CsMath.normDeg(190f), eps)
        assertEquals(90f, CsMath.normDeg(-270f), eps)
    }
}
