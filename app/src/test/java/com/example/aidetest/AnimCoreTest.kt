package com.example.aidetest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class AnimCoreTest {
    private val eps = 0.001f

    // ------------------------------------------------------------------ easing

    @Test
    fun easingEndpointsAndShape() {
        for (e in AnEase.ALL) {
            assertEquals(e, 0f, AnEase.apply(e, 0f), eps)
            assertEquals(e, 1f, AnEase.apply(e, 1f), eps)
            assertEquals(e, 0f, AnEase.apply(e, -3f), eps)
            assertEquals(e, 1f, AnEase.apply(e, 7f), eps)
            var prev = -1f
            for (i in 0..100) {
                val v = AnEase.apply(e, i / 100f)
                assertTrue("$e monoton", v >= prev - 1e-6f)
                prev = v
            }
        }
        assertEquals(0.5f, AnEase.apply(AnEase.LINEAR, 0.5f), eps)
        assertEquals(0.125f, AnEase.apply(AnEase.IN, 0.5f), eps)
        assertEquals(0.875f, AnEase.apply(AnEase.OUT, 0.5f), eps)
        assertEquals(0.5f, AnEase.apply(AnEase.IN_OUT, 0.5f), eps)
        assertTrue(AnEase.apply(AnEase.IN, 0.3f) < 0.3f)
        assertTrue(AnEase.apply(AnEase.OUT, 0.3f) > 0.3f)
        assertEquals(AnEase.IN_OUT, AnEase.valid("bogus"))
        assertEquals(AnEase.OUT, AnEase.valid("out"))
    }

    // ------------------------------------------------------------------ keyframe

    @Test
    fun sampleInterpolatesWithSegmentEase() {
        val keys = listOf(
            AnKey(0, dx = 0f, opacity = 0f, ease = AnEase.LINEAR),
            AnKey(1000, dx = 100f, rot = 90f, scale = 2f, opacity = 1f, ease = AnEase.IN),
            AnKey(2000, dx = 200f, rot = 90f, scale = 2f)
        )
        assertEquals(AnState.IDENTITY, AnMath.sample(emptyList(), 500))
        val s0 = AnMath.sample(keys, -10)
        assertEquals(0f, s0.opacity, eps)
        val s1 = AnMath.sample(keys, 500)
        assertEquals(50f, s1.dx, eps)
        assertEquals(45f, s1.rot, eps)
        assertEquals(1.5f, s1.scale, eps)
        assertEquals(0.5f, s1.opacity, eps)
        // Segmen kedua memakai ease-in milik keyframe awal segmen: 100 + 100 × 0.5³
        assertEquals(112.5f, AnMath.sample(keys, 1500).dx, eps)
        assertEquals(200f, AnMath.sample(keys, 99_999).dx, eps)
        assertEquals(100f, AnMath.sample(keys, 1000).dx, eps)
    }

    @Test
    fun upsertRemoveAndCurveKey() {
        var keys = listOf(AnKey(0, dx = 0f), AnKey(1000, dx = 100f))
        val mid = AnMath.keyFromCurve(keys, 500)
        assertEquals(500L, mid.t)
        assertEquals(AnMath.sample(keys, 500).dx, mid.dx, eps)
        keys = AnMath.upsert(keys, mid, 16)
        assertEquals(listOf(0L, 500L, 1000L), keys.map { it.t })
        // Menyisipkan di dekat keyframe lama menggantinya (bukan duplikat).
        keys = AnMath.upsert(keys, AnKey(510, dx = -5f), 16)
        assertEquals(listOf(0L, 510L, 1000L), keys.map { it.t })
        assertEquals(1, AnMath.keyAt(keys, 505, 16))
        assertEquals(-1, AnMath.keyAt(keys, 700, 16))
        keys = AnMath.removeAt(keys, 505, 16)
        assertEquals(listOf(0L, 1000L), keys.map { it.t })
        assertEquals(listOf(0L), AnMath.clip(keys, 999).map { it.t })
        assertEquals(listOf(200L, 1200L), AnMath.shift(keys, 200).map { it.t })
        assertEquals(1000L, AnMath.clampDuration(10))
        assertEquals(60_000L, AnMath.clampDuration(90_000))
        assertEquals(33L, AnMath.snapToFrame(40, 30))
        assertEquals(67L, AnMath.snapToFrame(60, 30))
    }

    @Test
    fun presetsEndAtDesignAndStartHidden() {
        for ((id, _) in AnPresets.ALL) {
            val k = AnPresets.keys(id, 200, 700, 100f, 50f, 300f, 200f, 1280f, 720f)
            assertTrue(id, k.size >= 2)
            assertEquals(id, 200L, k.first().t)
            assertEquals(id, 900L, k.last().t)
            assertEquals(id, AnState.IDENTITY, AnMath.sample(k, 900))
            assertEquals(id, AnState.IDENTITY, AnMath.sample(k, 5000))
            // Parallax adalah gerak ayun: mulai dan selesai di desain, jadi tidak disembunyikan saat mulai.
            if (id != "parallax") assertTrue(id, !AnMath.sample(k, 0).isIdentity())
        }
        // Masuk kiri: objek sepenuhnya di luar tepi kiri saat mulai.
        val l = AnMath.sample(AnPresets.keys("slide_l", 0, 700, 100f, 50f, 300f, 200f, 1280f, 720f), 0)
        assertTrue(100f + l.dx + 300f <= 0f)
        val r = AnMath.sample(AnPresets.keys("slide_r", 0, 700, 100f, 50f, 300f, 200f, 1280f, 720f), 0)
        assertTrue(100f + r.dx >= 1280f)
        val u = AnMath.sample(AnPresets.keys("slide_up", 0, 700, 100f, 50f, 300f, 200f, 1280f, 720f), 0)
        assertTrue(50f + u.dy >= 720f)
        assertTrue(AnPresets.keys("none", 0, 700, 0f, 0f, 1f, 1f, 1f, 1f).isEmpty())
        // Pop melewati skala > 1 sebelum kembali ke 1.
        val pop = AnPresets.keys("pop", 0, 500, 0f, 0f, 10f, 10f, 100f, 100f)
        assertTrue(pop.any { it.scale > 1f })
    }

    @Test
    fun presetReplacesOnlyItsRangeAndEntranceStagger() {
        val existing = listOf(AnKey(0, dx = 5f), AnKey(300, dx = 6f), AnKey(3000, rot = 30f))
        val preset = AnPresets.keys("fade", 200, 700, 0f, 0f, 10f, 10f, 100f, 100f)
        val merged = AnPresets.applyTo(existing, preset)
        assertEquals(listOf(0L, 200L, 900L, 3000L), merged.map { it.t })
        val tracks = AnPresets.entranceTracks(
            listOf(
                AnPresets.EntranceSpec("a", "fade", 0f, 0f, 10f, 10f),
                AnPresets.EntranceSpec("b", "none", 0f, 0f, 10f, 10f),
                AnPresets.EntranceSpec("c", "pop", 0f, 0f, 10f, 10f)
            ), 100f, 100f, stagger = 200
        )
        assertEquals(setOf("a", "c"), tracks.keys)
        assertEquals(0L, tracks["a"]!!.first().t)
        assertEquals(200L, tracks["c"]!!.first().t)
        assertEquals(700L, AnPresets.tracksEnd(tracks))
    }

    // ------------------------------------------------------------------ timing

    @Test
    fun timingAndSizes() {
        assertEquals(30, AnTiming.frameCount(1000, 30))
        assertEquals(31, AnTiming.frameCount(1001, 30))
        assertEquals(1, AnTiming.frameCount(1, 30))
        assertEquals(150, AnTiming.frameCount(5000, 30))
        assertEquals(33L, AnTiming.frameMs(1, 30))
        assertEquals(1000L, AnTiming.frameMs(30, 30))
        assertEquals(33_333L, AnTiming.frameUs(1, 30))
        assertEquals(1_000_000L, AnTiming.frameUs(30, 30))
        // Jeda GIF kumulatif: 15 fps × 15 frame = tepat 100 cs.
        assertEquals(100, (0 until 15).sumOf { AnTiming.gifDelayCs(it, 15) })
        assertEquals(10, AnTiming.gifDelayCs(0, 10))
        assertEquals(1280 to 720, AnTiming.fitEven(1280, 720, 1920))
        assertEquals(1080 to 1920, AnTiming.fitEven(1081, 1921, 1920))
        val (w, h) = AnTiming.fitEven(1001, 333, 1000)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
        val (w16, h16) = AnTiming.fitEven(1080, 1350, 1920, 16)
        assertEquals(0, w16 % 16)
        assertEquals(0, h16 % 16)
        assertTrue(AnTiming.bitrate(1920, 1080, 30) in 1_500_000..20_000_000)
        assertEquals("1:05.2", AnTiming.fmt(65_250))
    }

    // ------------------------------------------------------------------ tata letak

    @Test
    fun slideFitAndNearestSize() {
        val f = AnLayout.fit(1080f, 1080f, 1920f, 1080f)
        assertEquals(1f, f[0], eps)
        assertEquals(420f, f[1], eps)
        assertEquals(0f, f[2], eps)
        val g = AnLayout.fit(3840f, 2160f, 1920f, 1080f)
        assertEquals(0.5f, g[0], eps)
        assertEquals(0f, g[1], eps)
        assertEquals("16:9", AnLayout.nearestSlideSize(1280, 720))
        assertEquals("9:16", AnLayout.nearestSlideSize(1080, 1920))
        assertEquals("4:3", AnLayout.nearestSlideSize(1080, 1080))
        assertEquals("9:16", AnLayout.nearestSlideSize(1240, 1754))
        assertEquals(2, AnLayout.moveIndex(0, 9, 3))
        assertEquals(0, AnLayout.moveIndex(2, -1, 3))
    }

    // ------------------------------------------------------------------ YUV

    @Test
    fun yuvKnownColors() {
        val w = 4
        val h = 2
        val argb = IntArray(w * h)
        // Kiri 2×2 putih, kanan 2×2 hitam.
        for (y in 0 until h) for (x in 0 until w) argb[y * w + x] = if (x < 2) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val i420 = AnYuv.argbToI420(argb, w, h)
        assertEquals(8 + 2 + 2, i420.size)
        assertEquals(235, i420[0].toInt() and 0xFF)
        assertEquals(16, i420[2].toInt() and 0xFF)
        assertEquals(128, i420[8].toInt() and 0xFF)
        assertEquals(128, i420[9].toInt() and 0xFF)
        assertEquals(128, i420[10].toInt() and 0xFF)
        // Merah murni: Y 82, U 90, V 240 (BT.601 rentang terbatas).
        val red = IntArray(4) { 0xFFFF0000.toInt() }
        val r = AnYuv.argbToI420(red, 2, 2)
        assertEquals(82, r[0].toInt() and 0xFF)
        assertEquals(90, r[4].toInt() and 0xFF)
        assertEquals(240, r[5].toInt() and 0xFF)
        val nv12 = AnYuv.argbToNV12(red, 2, 2)
        assertArrayEquals(byteArrayOf(82, 82, 82, 82, 90, 240.toByte()), nv12)
        // Dimensi ganjil tetap aman (krominans dibulatkan ke atas).
        assertEquals(9 + 4 + 4, AnYuv.argbToI420(IntArray(9) { -1 }, 3, 3).size)
    }

    @Test
    fun scatterRespectsStridesAndSharedChroma() {
        val u = byteArrayOf(1, 2, 3, 4) // 2×2
        val v = byteArrayOf(5, 6, 7, 8)
        // Bidang semi-planar bersama: U di offset 0, V di offset 1, pixelStride 2, rowStride 6 (padding).
        val mem = ByteArray(AnYuv.planeLength(2, 2, 6, 2) + 1) { 99.toByte() }
        AnYuv.scatter(u, 2, 2, mem, rowStride = 6, pixelStride = 2, offset = 0)
        AnYuv.scatter(v, 2, 2, mem, rowStride = 6, pixelStride = 2, offset = 1)
        assertArrayEquals(byteArrayOf(1, 5, 2, 6, 99, 99, 3, 7, 4, 8), mem)
        // Planar dengan rowStride > lebar.
        val y = byteArrayOf(10, 11, 12, 13, 14, 15)
        val plane = ByteArray(AnYuv.planeLength(3, 2, 4, 1))
        AnYuv.scatter(y, 3, 2, plane, rowStride = 4, pixelStride = 1)
        assertArrayEquals(byteArrayOf(10, 11, 12, 0, 13, 14, 15), plane)
        assertEquals(7, AnYuv.planeLength(3, 2, 4, 1))
    }

    // ------------------------------------------------------------------ GIF

    /** Dekoder GIF minimal untuk uji: mengembalikan (palet, indeks, transIndex, delay) per frame. */
    private class Frame(val palette: IntArray, val indices: IntArray, val trans: Int, val delay: Int, val disposal: Int)

    private fun decodeGif(data: ByteArray): Triple<Int, Int, List<Frame>> {
        var p = 0
        fun u8() = data[p++].toInt() and 0xFF
        fun u16(): Int { val a = u8(); return a or (u8() shl 8) }
        assertEquals("GIF89a", String(data, 0, 6, Charsets.US_ASCII))
        p = 6
        val w = u16()
        val h = u16()
        val packed = u8()
        u8(); u8()
        assertEquals(0, packed and 0x80) // tanpa tabel global
        val frames = ArrayList<Frame>()
        var trans = -1
        var delay = 0
        var disposal = 0
        while (true) {
            when (u8()) {
                0x21 -> {
                    val label = u8()
                    if (label == 0xF9) {
                        assertEquals(4, u8())
                        val f = u8()
                        disposal = (f shr 2) and 7
                        delay = u16()
                        val ti = u8()
                        trans = if (f and 1 != 0) ti else -1
                        assertEquals(0, u8())
                    } else {
                        while (true) { val n = u8(); if (n == 0) break; p += n }
                    }
                }
                0x2C -> {
                    u16(); u16()
                    val fw = u16()
                    val fh = u16()
                    val ip = u8()
                    assertTrue(ip and 0x80 != 0)
                    val size = 1 shl ((ip and 7) + 1)
                    val pal = IntArray(size) { (0xFF shl 24) or (u8() shl 16) or (u8() shl 8) or u8() }
                    val minCode = u8()
                    val bytes = ByteArrayOutputStream()
                    while (true) { val n = u8(); if (n == 0) break; bytes.write(data, p, n); p += n }
                    val idx = lzwDecode(bytes.toByteArray(), minCode, fw * fh)
                    frames.add(Frame(pal, idx, trans, delay, disposal))
                }
                0x3B -> return Triple(w, h, frames)
                else -> error("blok tidak dikenal")
            }
        }
    }

    /** Dekoder LZW GIF standar (perilaku giflib: lebar kode naik saat kode berikut == 2^lebar). */
    private fun lzwDecode(d: ByteArray, minCode: Int, count: Int): IntArray {
        val clear = 1 shl minCode
        val eoi = clear + 1
        var size = minCode + 1
        var next = eoi + 1
        val prefix = IntArray(4096)
        val suffix = IntArray(4096)
        val lengths = IntArray(4096)
        val first = IntArray(4096)
        for (i in 0 until clear) { suffix[i] = i; lengths[i] = 1; first[i] = i }
        val out = IntArray(count)
        var o = 0
        var bitPos = 0
        var prev = -1
        fun read(): Int {
            var v = 0
            for (i in 0 until size) {
                val byte = bitPos ushr 3
                if (byte >= d.size) return eoi
                if ((d[byte].toInt() shr (bitPos and 7)) and 1 != 0) v = v or (1 shl i)
                bitPos++
            }
            return v
        }
        fun emit(code: Int) {
            val len = lengths[code]
            var c = code
            val start = o
            for (i in len - 1 downTo 0) {
                if (start + i < count) out[start + i] = suffix[c]
                c = prefix[c]
            }
            o += len
        }
        while (true) {
            val code = read()
            if (code == clear) { size = minCode + 1; next = eoi + 1; prev = -1; continue }
            if (code == eoi) break
            if (prev == -1) { emit(code); prev = code; continue }
            if (code < next) {
                emit(code)
                if (next < 4096) {
                    prefix[next] = prev; suffix[next] = first[code]; first[next] = first[prev]; lengths[next] = lengths[prev] + 1; next++
                }
            } else {
                assertEquals("kode KwKwK", next, code)
                val k = first[prev]
                prefix[next] = prev; suffix[next] = k; first[next] = first[prev]; lengths[next] = lengths[prev] + 1; next++
                emit(code)
            }
            prev = code
            if (next >= (1 shl size) && size < 12) size++
        }
        assertEquals("jumlah piksel", count, o)
        return out
    }

    private fun encode(w: Int, h: Int, frames: List<IntArray>, fps: Int = 10): ByteArray {
        val bos = ByteArrayOutputStream()
        val enc = AnGifEncoder(bos, w, h)
        frames.forEachIndexed { i, f -> enc.addFrame(f, AnTiming.gifDelayCs(i, fps)) }
        enc.finish()
        assertEquals(frames.size, enc.frames)
        return bos.toByteArray()
    }

    @Test
    fun gifRoundTripExactColors() {
        // ≤ 256 warna di bin 15-bit berbeda → palet median-cut persis, LZW lossless.
        val w = 64
        val h = 48
        val colors = IntArray(200) { i -> (0xFF shl 24) or (((i * 8) and 0xF8) shl 16) or (((i / 32) * 32) shl 8) or ((i % 7) * 32) }
        val frame = IntArray(w * h) { colors[(it * 7 + it / w) % colors.size] }
        val frame2 = IntArray(w * h) { colors[(it / 3) % colors.size] }
        val (gw, gh, frames) = decodeGif(encode(w, h, listOf(frame, frame2)))
        assertEquals(w, gw)
        assertEquals(h, gh)
        assertEquals(2, frames.size)
        for ((src, f) in listOf(frame, frame2).zip(frames)) {
            assertEquals(-1, f.trans)
            assertEquals(10, f.delay)
            for (i in src.indices) assertEquals("piksel $i", src[i], f.palette[f.indices[i]])
        }
    }

    @Test
    fun gifLargeNoisyFrameExercisesTableReset() {
        // Banyak pola unik → tabel kode penuh (4096) dan kode clear di tengah aliran.
        val w = 200
        val h = 150
        var seed = 12345
        val px = IntArray(w * h) {
            seed = seed * 1103515245 + 12345
            val v = (seed ushr 16) and 0x0F
            (0xFF shl 24) or ((v * 16) shl 16) or ((v * 8) shl 8) or (v * 4)
        }
        val (_, _, frames) = decodeGif(encode(w, h, listOf(px)))
        val f = frames[0]
        for (i in px.indices) assertEquals(px[i], f.palette[f.indices[i]])
    }

    @Test
    fun gifTransparencyAndQuantization() {
        val w = 16
        val h = 16
        // Gradien halus (> 256 warna) + area transparan.
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x < 4) 0x00000000 else (0xFF shl 24) or ((x * 16) shl 16) or ((y * 16) shl 8) or (((x + y) * 8) and 0xFF)
        }
        val (_, _, frames) = decodeGif(encode(w, h, listOf(px)))
        val f = frames[0]
        assertTrue(f.trans >= 0)
        assertEquals(2, f.disposal)
        for (i in px.indices) {
            val x = i % w
            if (x < 4) assertEquals(f.trans, f.indices[i])
            else {
                val c = f.palette[f.indices[i]]
                val dr = ((c shr 16) and 0xFF) - ((px[i] shr 16) and 0xFF)
                val dg = ((c shr 8) and 0xFF) - ((px[i] shr 8) and 0xFF)
                val db = (c and 0xFF) - (px[i] and 0xFF)
                assertTrue("warna dekat", kotlin.math.abs(dr) <= 24 && kotlin.math.abs(dg) <= 24 && kotlin.math.abs(db) <= 24)
            }
        }
        // Palet besar tetap dibatasi 256 entri (+ transparan).
        val many = IntArray(128 * 128) { (0xFF shl 24) or (it * 997 and 0xFFFFFF) }
        val pal = AnGifPalette.build(many, 255)
        assertTrue(pal.colors.size <= 255)
        assertTrue(pal.colors.size > 200)
    }

    @Test
    fun gifSinglePixelAndUniform() {
        val (_, _, a) = decodeGif(encode(1, 1, listOf(intArrayOf(0xFF123456.toInt()))))
        assertEquals(0xFF123456.toInt(), a[0].palette[a[0].indices[0]])
        val uniform = IntArray(300 * 10) { 0xFF808080.toInt() }
        val (_, _, b) = decodeGif(encode(300, 10, listOf(uniform)))
        assertTrue(b[0].indices.all { b[0].palette[it] == 0xFF808080.toInt() })
    }

    // ------------------------------------------------------------------ codec

    @Test
    fun trackCodecRoundTrip() {
        val tracks = mapOf(
            "a" to listOf(AnKey(0, 1f, 2f, 3f, 0.5f, 0.25f, AnEase.IN), AnKey(500, ease = AnEase.LINEAR)),
            "kosong" to emptyList()
        )
        val enc = AnCodec.encodeTracks(tracks)
        assertEquals(setOf("a"), enc.keys)
        val dec = AnCodec.decodeTracks(enc)
        assertEquals(tracks["a"], dec["a"])
        // Nilai rusak dibatasi / diabaikan, urutan dipulihkan, waktu ganda digabung.
        val raw = mapOf<String, Any?>(
            "b" to listOf(
                mapOf("t" to 900, "s" to 99.0, "o" to 3.0, "e" to "??"),
                mapOf("t" to 100, "dx" to Double.NaN),
                mapOf("dx" to 1.0),
                mapOf("t" to 100, "dx" to 7.0),
                "bukan peta"
            )
        )
        val b = AnCodec.decodeTracks(raw)["b"]!!
        assertEquals(listOf(100L, 900L), b.map { it.t })
        assertEquals(7f, b[0].dx, eps)
        assertEquals(AnMath.MAX_SCALE, b[1].scale, eps)
        assertEquals(1f, b[1].opacity, eps)
        assertEquals(AnEase.IN_OUT, b[1].ease)
        assertTrue(AnCodec.decodeTracks(null).isEmpty())
    }

    @Test
    fun parallaxPresetStartsAndEndsAtDesign() {
        val k = AnPresets.keys("parallax", 0L, 2000L, 0f, 0f, 100f, 100f, 1000f, 1000f, 1f)
        assertEquals(0f, k.first().dx, 0.001f)
        assertEquals(0f, k.last().dx, 0.001f)
        assertTrue(k.any { it.dx > 0f })
        assertTrue(k.any { it.dx < 0f })
    }

    @Test
    fun parallaxDeeperLayersMoveFurther() {
        val near = AnPresets.keys("parallax", 0L, 2000L, 0f, 0f, 100f, 100f, 1000f, 1000f, 1f)
        val far = AnPresets.keys("parallax", 0L, 2000L, 0f, 0f, 100f, 100f, 1000f, 1000f, 0f)
        val amp = { ks: List<AnKey> -> ks.maxOf { Math.abs(it.dx) } }
        assertTrue(amp(near) > amp(far))
    }
}
