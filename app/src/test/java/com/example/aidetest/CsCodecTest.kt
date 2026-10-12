package com.example.aidetest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsCodecTest {
    private val eps = 0.0001f
    private val resolve: (String) -> String = { "/data/design/img/$it" }

    private fun richEdit() = PeEdit(
        adjust = PeAdjust(
            exposure = 0.5f, contrast = -0.25f, temperature = 0.3f, saturation = 0.2f,
            curveM = listOf(0f to 0.05f, 0.5f to 0.6f, 1f to 1f),
            hslHue = listOf(0.1f, 0f, 0f, 0f, 0f, -0.2f, 0f, 0f),
            preset = "film", presetAmount = 0.7f, sharpen = 0.4f, vignette = -0.3f, bgBlur = 0.5f
        ),
        geom = PeGeom(rot90 = 1, flipH = true, straighten = 3.5f, perspV = 0.2f, cl = 0.1f, ct = 0.2f, cr = 0.9f, cb = 0.8f, ratio = "4:5"),
        heals = listOf(PeHeal(listOf(0.1f, 0.2f, 0.15f, 0.25f), 0.02f, 0.05f, -0.01f))
    )

    @Test
    fun imageElementRoundTripsLosslessly() {
        val el = CsElement(
            type = CsType.IMAGE, x = 10f, y = 20f, w = 300f, h = 200f, rotation = 15f, opacity = 0.8f, blend = "multiply",
            locked = true, groupId = "g1", name = "Produk", strokeColor = 0xFF112233.toInt(), strokeWidth = 4f, cornerRadius = 12f,
            shadow = true, shadowBlur = 30f, shadowDx = -6f, shadowDy = 9f,
            imagePath = "/data/design/img/abc.jpg", srcW = 3000, srcH = 2000, edit = richEdit(), maskId = "m-123",
            cut = CsCut(on = true, method = "subjek", smooth = 50, shift = -3, feather = 6, decontam = false, tolerance = 40),
            fitCanvas = true, role = "latar:xyz"
        )
        val back = CsCodec.decodeElement(CsCodec.encodeElement(el), CsCodec.VERSION, resolve)
        assertEquals(el, back)
    }

    @Test
    fun textAndStickerRoundTrip() {
        val t = CsElement(type = CsType.TEXT, x = 1f, y = 2f, w = 3f, h = 4f, text = "Halo\nDunia", textSize = 72f, font = "serifb",
            bold = true, italic = true, underline = true, align = CsAlign.RIGHT, letterSpacing = 0.1f, lineSpacing = 1.5f,
            textBg = 0x80000000.toInt(), textBgPad = 0.5f, color = 0xFFFF0000.toInt(), gradient = true, color2 = 0xFF0000FF.toInt(), gradAngle = 45f)
        assertEquals(t, CsCodec.decodeElement(CsCodec.encodeElement(t), CsCodec.VERSION, resolve))
        val s = CsElement(type = CsType.STICKER, x = 0f, y = 0f, w = 100f, h = 100f, icon = "heart", color = 0xFFEF4444.toInt())
        assertEquals(s, CsCodec.decodeElement(CsCodec.encodeElement(s), CsCodec.VERSION, resolve))
    }

    @Test
    fun documentRoundTripKeepsBackgroundAndOrder() {
        val a = CsElement(type = CsType.RECT, x = 0f, y = 0f, w = 10f, h = 10f)
        val b = CsElement(type = CsType.ELLIPSE, x = 5f, y = 5f, w = 10f, h = 10f)
        val doc = CsDocData("Poster", 1240, 1754, CsBackground("gradient", 0xFFFFFFFF.toInt(), 0xFF101010.toInt(), 30f), listOf(a, b))
        val tree = CsCodec.encodeDoc(doc, 123L)
        assertEquals(3, tree["v"])
        val back = CsCodec.decodeDoc(tree, resolve)
        assertEquals("Poster", back.name)
        assertEquals(1240, back.w)
        assertEquals(1754, back.h)
        assertEquals(doc.bg, back.bg)
        assertEquals(listOf(a.id, b.id), back.elements.map { it.id })
    }

    /** Bentuk persis yang ditulis Design Studio v2 (DsStore.toJson) — harus tetap terbuka. */
    @Test
    fun loadsDesignStudioV2Project() {
        val v2 = mapOf<String, Any?>(
            "v" to 2, "name" to "Desain 1", "w" to 1280, "h" to 720, "bg" to -15461352, "savedAt" to 1700000000000L,
            "elements" to listOf(
                mapOf<String, Any?>(
                    "id" to "t1", "type" to "TEXT", "x" to 80.0, "y" to 280.0, "w" to 1120.0, "h" to 120.0,
                    "text" to "Judul", "textSize" to 72.0, "color" to -1, "strokeColor" to 0, "strokeWidth" to 0.0,
                    "shadow" to true, "align" to "CENTER", "bold" to true, "visible" to true, "locked" to false,
                    "rotation" to 0.0, "opacity" to 1.0, "font" to "sans", "italic" to false, "underline" to false,
                    "letterSpacing" to 0.0, "lineSpacing" to 1.2, "cornerRadius" to 0.0, "shadowBlur" to 30.0,
                    "shadowColor" to -1778384896
                ),
                mapOf<String, Any?>("id" to "i1", "type" to "IMAGE", "x" to 0.0, "y" to 0.0, "w" to 640.0, "h" to 360.0, "imagePath" to "foto.jpg"),
                mapOf<String, Any?>("id" to "s1", "type" to "TRIANGLE", "x" to 1, "y" to 2, "w" to 3, "h" to 4, "groupId" to "g")
            )
        )
        val d = CsCodec.decodeDoc(v2, resolve)
        assertEquals("Desain 1", d.name)
        assertEquals("solid", d.bg.mode)
        assertEquals(-15461352, d.bg.color)
        val t = d.elements[0]
        assertEquals(CsType.TEXT, t.type)
        assertEquals(CsAlign.CENTER, t.align)
        assertEquals("normal", t.blend)
        // Offset bayangan lama = blur / 3.
        assertEquals(10f, t.shadowDx, eps)
        assertEquals(10f, t.shadowDy, eps)
        val img = d.elements[1]
        assertEquals("/data/design/img/foto.jpg", img.imagePath)
        assertEquals(0, img.srcW)
        assertEquals(PeEdit(), img.edit)
        assertNull(img.maskId)
        assertFalse(img.cut.on)
        assertFalse(img.fitCanvas)
        assertEquals(CsType.TRIANGLE, d.elements[2].type)
        assertEquals("g", d.elements[2].groupId)
    }

    @Test
    fun missingVersionAndBadValuesAreSanitized() {
        val m = mapOf<String, Any?>(
            "w" to 99999, "h" to 0, "bgMode" to "plaid",
            "elements" to listOf(
                mapOf<String, Any?>("id" to "a", "type" to "HEXAGON", "w" to 0.0, "opacity" to 3.0, "blend" to "weird", "align" to "JUSTIFY", "maskId" to "../../etc/passwd"),
                mapOf<String, Any?>("id" to "a", "type" to "RECT"),
                "bukan objek"
            )
        )
        val d = CsCodec.decodeDoc(m, resolve)
        assertEquals(4096, d.w)
        assertEquals(16, d.h)
        assertEquals("solid", d.bg.mode)
        assertEquals(2, d.elements.size)
        val e = d.elements[0]
        assertEquals(CsType.RECT, e.type)
        assertEquals(1f, e.w, eps)
        assertEquals(1f, e.opacity, eps)
        assertEquals("normal", e.blend)
        assertEquals(CsAlign.LEFT, e.align)
        assertNull(e.maskId)
        // Id ganda diganti agar seleksi tetap unik.
        assertNotEquals(d.elements[0].id, d.elements[1].id)
    }

    @Test
    fun editDecodingIsDefensive() {
        val m = mapOf<String, Any?>(
            "adjust" to mapOf<String, Any?>("exposure" to 9.0, "curveM" to listOf(0.0, 0.0, 0.5), "hslSat" to listOf(1.0, 2.0), "preset" to "tidakada", "grain" to "0.5"),
            "geom" to mapOf<String, Any?>("rot90" to -1, "cl" to 0.5, "cr" to 0.5, "straighten" to 99.0),
            "heals" to listOf(mapOf<String, Any?>("pts" to listOf(0.1), "r" to 0.1), mapOf<String, Any?>("pts" to listOf(0.1, 0.2), "r" to 0.02))
        )
        val e = CsCodec.decodeEdit(m)
        assertEquals(2f, e.adjust.exposure, eps)
        assertEquals(CURVE_IDENTITY, e.adjust.curveM)
        assertEquals(List(8) { 0f }, e.adjust.hslSat)
        assertEquals("asli", e.adjust.preset)
        assertEquals(0.5f, e.adjust.grain, eps)
        assertEquals(3, e.geom.rot90)
        assertEquals(0f, e.geom.cl, eps)
        assertEquals(1f, e.geom.cr, eps)
        assertEquals(45f, e.geom.straighten, eps)
        assertEquals(1, e.heals.size)
        assertEquals(PeEdit(), CsCodec.decodeEdit(null))
    }

    @Test
    fun encodingNeverEmitsNaNAndOmitsIdentityEdit() {
        val el = CsElement(type = CsType.IMAGE, x = Float.NaN, y = Float.POSITIVE_INFINITY, w = 10f, h = 10f, imagePath = "/x/a.png")
        val m = CsCodec.encodeElement(el)
        assertEquals(0.0, m["x"])
        assertEquals(0.0, m["y"])
        assertEquals("a.png", m["imagePath"])
        assertFalse(m.containsKey("edit"))
        val withEdit = CsCodec.encodeElement(el.copy(edit = richEdit()))
        assertTrue(withEdit.containsKey("edit"))
    }

    @Test
    fun numbersFromJsonInAnyShapeDecode() {
        // org.json bisa mengembalikan Long/Integer/Double, atau String bila disunting manual.
        val m = mapOf<String, Any?>("color" to 4294967295L, "x" to 12, "y" to "7.5", "visible" to "false")
        val e = CsCodec.decodeElement(m, 3, resolve)
        assertEquals(-1, e.color)
        assertEquals(12f, e.x, eps)
        assertEquals(7.5f, e.y, eps)
        assertFalse(e.visible)
    }

    @Test
    fun fileRefsCollectImagesAndSafeMasks() {
        val m = mapOf<String, Any?>("elements" to listOf(
            mapOf<String, Any?>("imagePath" to "a.jpg", "maskId" to "m-1"),
            mapOf<String, Any?>("imagePath" to "/old/abs/b.png", "maskId" to "../evil"),
            mapOf<String, Any?>("type" to "RECT")
        ))
        val images = HashSet<String>()
        val masks = HashSet<String>()
        CsCodec.fileRefs(m, images, masks)
        assertEquals(setOf("a.jpg", "b.png"), images)
        assertEquals(setOf("m-1"), masks)
    }
}
