package com.example.aidetest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeMathTest {
    private val eps = 0.001f

    @Test
    fun timeFormats() {
        assertEquals("0:05", VeTime.fmt(5_400))
        assertEquals("0:05.4", VeTime.fmt(5_400, tenths = true))
        assertEquals("1:02:03", VeTime.fmt(3_723_000))
        assertEquals("00:23", VeTime.clock(23_900))
        assertEquals("1:00:00", VeTime.clock(3_600_000))
        assertEquals("5.2s", VeTime.badge(5_200))
        assertEquals("1:05", VeTime.badge(65_000))
        assertEquals("00:01:02,050", VeTime.srt(62_050))
    }

    @Test
    fun clipMathStartsAndIndex() {
        val d = longArrayOf(1000, 2000, 500)
        assertArrayEquals(longArrayOf(0, 1000, 3000), VeClipMath.starts(d))
        assertEquals(3500L, VeClipMath.total(d))
        assertEquals(0, VeClipMath.indexAt(d, 0))
        assertEquals(0, VeClipMath.indexAt(d, 999))
        assertEquals(1, VeClipMath.indexAt(d, 1000))
        assertEquals(2, VeClipMath.indexAt(d, 3499))
        assertEquals(2, VeClipMath.indexAt(d, 99_999)) // melewati akhir → klip terakhir
        assertEquals(-1, VeClipMath.indexAt(longArrayOf(), 10))
    }

    @Test
    fun lanesAvoidOverlap() {
        // A 0-4, B 2-6 (tabrakan dengan A), C 4-8 (muat di lane A setelah A selesai)
        val lanes = VeLanes.assign(longArrayOf(0, 2000, 4000), longArrayOf(4000, 6000, 8000))
        assertEquals(0, lanes[0])
        assertEquals(1, lanes[1])
        assertEquals(0, lanes[2])
        assertEquals(0, VeLanes.assign(longArrayOf(), longArrayOf()).size)
        // urutan input tidak mempengaruhi tabrakan
        val rev = VeLanes.assign(longArrayOf(4000, 2000, 0), longArrayOf(8000, 6000, 4000))
        assertTrue(rev[1] != rev[2])
    }

    @Test
    fun snapPicksNearestWithinThreshold() {
        val targets = longArrayOf(0, 1000, 5000)
        assertEquals(1000L, VeSnapMath.nearest(1080, targets, 100))
        assertNull(VeSnapMath.nearest(1500, targets, 100))
        // geser item 900–1900 → awal menempel ke 1000 (offset +100); akhir tidak punya target dekat
        assertEquals(100L, VeSnapMath.snapMove(900, 1900, targets, 150))
        // akhir lebih dekat daripada awal
        assertEquals(-20L, VeSnapMath.snapMove(3000, 5020, targets, 150))
        assertEquals(0L, VeSnapMath.snapMove(2000, 3000, targets, 100))
    }

    @Test
    fun scaleMapping() {
        assertEquals(500f, VeScale.xOf(2000, 2000, 80f, 500f), eps)
        assertEquals(580f, VeScale.xOf(3000, 2000, 80f, 500f), eps)
        assertEquals(3000L, VeScale.timeAt(580f, 2000, 80f, 500f))
        assertEquals(1000L, VeScale.rulerStepMs(80f, 64f))   // 80px/dtk: 1 dtk = 80 px >= 64
        assertEquals(2000L, VeScale.rulerStepMs(40f, 64f))   // 1 dtk = 40px < 64; 2 dtk = 80px
        assertEquals(600_000L, VeScale.rulerStepMs(0.01f, 64f))
    }

    @Test
    fun overlayAnimationsWindowAndPhases() {
        // Di luar jendela = tidak tampil
        assertFalse(VeAnimMath.state("fade", "fade", 400, 1000, 3000, 999).visible)
        assertFalse(VeAnimMath.state("fade", "fade", 400, 1000, 3000, 4000).visible)
        // Fade-in: separuh jalan = alpha 0.5
        val mid = VeAnimMath.state("fade", "none", 400, 1000, 3000, 1200)
        assertTrue(mid.visible)
        assertEquals(0.5f, mid.alpha, eps)
        // Tengah jendela: tampil penuh
        val full = VeAnimMath.state("fade", "fade", 400, 1000, 3000, 2500)
        assertEquals(1f, full.alpha, eps)
        assertEquals(1f, full.scale, eps)
        // Fade-out dekat akhir
        val out = VeAnimMath.state("fade", "fade", 400, 1000, 3000, 3800)
        assertEquals(0.5f, out.alpha, eps)
        // Tanpa animasi selalu penuh
        assertEquals(1f, VeAnimMath.state("none", "none", 400, 0, 1000, 10).alpha, eps)
        // Naik: awal bergeser ke bawah (dy > 0), akhir 0
        assertTrue(VeAnimMath.state("naik", "none", 400, 0, 2000, 0).dy > 0.07f)
        assertEquals(0f, VeAnimMath.state("naik", "none", 400, 0, 2000, 1000).dy, eps)
        // Durasi animasi dibatasi separuh durasi item
        val short = VeAnimMath.state("fade", "fade", 5000, 0, 1000, 250)
        assertEquals(0.5f, short.alpha, eps)
    }

    @Test
    fun fxMotionAndColor() {
        val id = VeFxMath.motion("tidakada", 1f, 0.5f, 0)
        assertArrayEquals(floatArrayOf(1f, 1f, 0f, 0f), id, 0f)
        assertEquals(-1f, VeFxMath.motion("cermin", 1f, 0f, 0)[0], 0f)
        val z0 = VeFxMath.motion("zoompelan", 0.5f, 0f, 0)[0]
        val z1 = VeFxMath.motion("zoompelan", 0.5f, 1f, 0)[0]
        assertEquals(1f, z0, eps)
        assertTrue(z1 > 1.2f)
        val zk0 = VeFxMath.motion("zoomkeluar", 0.5f, 0f, 0)[0]
        assertTrue(zk0 > 1.2f)
        assertEquals(1f, VeFxMath.motion("zoomkeluar", 0.5f, 1f, 0)[0], eps)
        // getar deterministik dan selalu memperbesar sedikit agar tepi tidak terlihat
        val a = VeFxMath.motion("getar", 0.7f, 0.2f, 120)
        val b = VeFxMath.motion("getar", 0.7f, 0.9f, 120)
        assertArrayEquals(a, b, 0f)
        assertTrue(a[0] > 1f)
        // denyut berosilasi
        val d0 = VeFxMath.motion("denyut", 1f, 0f, 0)[0]
        val d1 = VeFxMath.motion("denyut", 1f, 0f, 200)[0]
        assertEquals(1f, d0, eps)
        assertTrue(d1 > d0)

        assertNull(VeFxMath.colorMatrix("getar", 1f, 0f, 0))
        val bw = VeFxMath.colorMatrix("hitamputih", 1f, 0f, 0)!!
        // Semua baris sama (grayscale penuh): elemen [0],[1],[2] = bobot R untuk output R,G,B
        assertEquals(0.2126f, bw[0], eps); assertEquals(0.2126f, bw[1], eps); assertEquals(0.2126f, bw[2], eps)
        assertEquals(0.7152f, bw[4], eps); assertEquals(0.0722f, bw[8], eps)
        val inv = VeFxMath.colorMatrix("invert", 1f, 0f, 0)!!
        assertEquals(-1f, inv[0], eps); assertEquals(1f, inv[12], eps)
        val fadeIn = VeFxMath.colorMatrix("pudarmasuk", 1f, 0.25f, 0)!!
        assertEquals(0.25f, fadeIn[0], eps)
        val fadeOut = VeFxMath.colorMatrix("pudarkeluar", 1f, 0.25f, 0)!!
        assertEquals(0.75f, fadeOut[5], eps)
        assertTrue(VeFxMath.isMotion("denyut") && VeFxMath.isColor("sepia") && VeFxMath.isBlur("blur"))
        assertTrue(VeFxMath.TYPES.all { (k, _) -> VeFxMath.isMotion(k) || VeFxMath.isColor(k) || VeFxMath.isBlur(k) })
    }

    @Test
    fun blurSigmaRampsInAndOut() {
        assertEquals(0.3f, VeFxMath.blurSigma(1f, 0f), eps)
        assertTrue(VeFxMath.blurSigma(1f, 0.5f) > 25f)
        assertEquals(0.3f, VeFxMath.blurSigma(1f, 1f), eps)
        assertTrue(VeFxMath.blurSigma(0f, 0.5f) < VeFxMath.blurSigma(1f, 0.5f))
    }

    @Test
    fun transitionMath() {
        assertEquals(450_000L, VeTransMath.durationUs("fade", 5_000_000))
        assertEquals(250_000L, VeTransMath.durationUs("fade", 500_000)) // batas separuh klip
        assertEquals(0L, VeTransMath.durationUs("none", 5_000_000))
        // Geser dari kanan: awal bergeser +2 NDC, akhir 0
        assertEquals(2f, VeTransMath.motion("slide", 0f)[2], eps)
        assertEquals(0f, VeTransMath.motion("slide", 1f)[2], eps)
        assertEquals(-2f, VeTransMath.motion("slideleft", 0f)[2], eps)
        assertEquals(-2f, VeTransMath.motion("slideup", 0f)[3], eps)
        assertEquals(2f, VeTransMath.motion("slidedown", 0f)[3], eps)
        assertEquals(1.25f, VeTransMath.motion("zoom", 0f)[0], eps)
        assertEquals(1f, VeTransMath.motion("zoom", 1f)[0], eps)
        assertEquals(0.7f, VeTransMath.motion("zoomout", 0f)[0], eps)
        assertTrue(VeTransMath.TYPES.map { it.first }.containsAll(listOf("none", "fade", "zoom", "slide")))
    }

    @Test
    fun srtParseAndFormatRoundTrip() {
        val src = "\uFEFF1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Halo</i> dunia\r\nBaris dua\r\n\r\n" +
            "2\r\n00:00:04,000 --> 00:00:05,000\r\n{\\an8}Atas\r\n\r\n" +
            "3\r\n00:00:06.5 --> 00:00:05.0\r\nTerbalik diabaikan\r\n\r\n" +
            "teks tanpa waktu\r\n"
        val cues = VeSrt.parse(src)
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(3500L, cues[0].endMs)
        assertEquals("Halo dunia\nBaris dua", cues[0].text)
        assertEquals("Atas", cues[1].text)
        val out = VeSrt.format(cues)
        val again = VeSrt.parse(out)
        assertEquals(cues.size, again.size)
        assertEquals(cues[0].text, again[0].text)
        assertEquals(cues[1].startMs, again[1].startMs)
        assertTrue(out.startsWith("1\n00:00:01,000 --> 00:00:03,500\n"))
        // pecahan 1-2 digit dibaca sebagai persepuluh/perseratus
        val frac = VeSrt.parse("1\n00:00:01,5 --> 00:00:02,25\nx\n")
        assertEquals(1500L, frac[0].startMs)
        assertEquals(2250L, frac[0].endMs)
    }

    @Test
    fun silenceDetection() {
        // 1 dtk bunyi, 1.5 dtk senyap, 1 dtk bunyi (jendela 50 ms, 8 kHz untuk ringkas)
        val rate = 8000
        val loud = ShortArray(rate) { (if (it % 2 == 0) 12000 else -12000).toShort() }
        val quiet = ShortArray((rate * 1.5).toInt())
        val pcm = loud + quiet + loud
        val db = VeSilence.rmsDb(pcm, rate, 50)
        assertEquals(70, db.size)
        assertTrue(db[0] > -10f)
        assertTrue(db[35] < -90f)
        val sil = VeSilence.silences(db, 50, -40f, minSilenceMs = 500, padMs = 100)
        assertEquals(1, sil.size)
        // senyap sejati 1000..2500 ms, dipangkas pad 100 ms tiap sisi
        assertEquals(1100L, sil[0].first)
        assertEquals(2400L, sil[0].last + 1)
        val keep = VeSilence.keepRanges(3500, sil)
        assertEquals(2, keep.size)
        assertEquals(0L, keep[0].first)
        assertEquals(1100L, keep[0].last + 1)
        assertEquals(2400L, keep[1].first)
        assertEquals(3500L, keep[1].last + 1)
        // senyap terlalu pendek tidak dihitung
        assertTrue(VeSilence.silences(db, 50, -40f, minSilenceMs = 2000, padMs = 0).isEmpty())
        // semua senyap → tidak ada yang dipertahankan
        assertTrue(VeSilence.keepRanges(1000, listOf(0L until 1000L)).isEmpty())
    }

    @Test
    fun sceneCutsOnHistogramJumps() {
        fun hist(vararg v: Float) = FloatArray(4) { v[it] }
        val a = hist(1f, 0f, 0f, 0f)
        val b = hist(0f, 0f, 0f, 1f)
        val times = longArrayOf(0, 500, 1000, 1500, 2000, 2500)
        val hs = listOf(a, a, b, b, a, a)
        val cuts = VeScene.cuts(times, hs, 0.5f, 400)
        assertEquals(listOf(1000L, 2000L), cuts)
        // jarak minimum antar adegan: adegan pertama (0–1000) terlalu pendek → potongan 1000 ditolak,
        // potongan 2000 diterima karena adegan 0–2000 sudah ≥ 1500 ms
        assertEquals(listOf(2000L), VeScene.cuts(times, hs, 0.5f, 1500))
        assertEquals(0f, VeScene.distance(a, a), eps)
        assertEquals(1f, VeScene.distance(a, b), eps)
        assertTrue(VeScene.cuts(longArrayOf(0), listOf(a), 0.1f, 0).isEmpty())
        val h = VeScene.histogram(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        assertEquals(48, h.size)
        assertEquals(1f, h.sum(), 0.001f)
        assertTrue(VeScene.histogram(IntArray(0)).all { it == 0f })
    }

    @Test
    fun mixerBasics() {
        val st = VeMixer.monoToStereo(shortArrayOf(1, 2, 3))
        assertArrayEquals(shortArrayOf(1, 1, 2, 2, 3, 3), st)
        // resample jumlah frame
        val src = ShortArray(44100 * 2) { 1000 }
        assertEquals(48000, VeMixer.resampleStereo(src, 44100, 48000).size / 2)
        assertTrue(VeMixer.resampleStereo(src, 44100, 44100) === src)
        // gain fade: awal 0, akhir 0, tengah 1
        assertEquals(0f, VeMixer.fadeGain(0, 1000, 100, 100), eps)
        assertEquals(1f, VeMixer.fadeGain(500, 1000, 100, 100), eps)
        assertEquals(0f, VeMixer.fadeGain(999, 1000, 100, 100), eps)
        assertEquals(1f, VeMixer.fadeGain(0, 1000, 0, 0), eps)
        // mix di offset; bagian negatif/ melewati akhir diabaikan
        val out = ShortArray(10) // 5 frame
        val clip = shortArrayOf(100, 100, 200, 200, 300, 300) // 3 frame
        VeMixer.mixInto(out, clip, 3, 1f, 0, 0)
        assertArrayEquals(shortArrayOf(0, 0, 0, 0, 0, 0, 100, 100, 200, 200), out)
        val out2 = ShortArray(10)
        VeMixer.mixInto(out2, clip, -1, 1f, 0, 0)
        assertArrayEquals(shortArrayOf(200, 200, 300, 300, 0, 0, 0, 0, 0, 0), out2)
        // penjumlahan dibatasi
        val loud = shortArrayOf(30000, 30000)
        val o3 = shortArrayOf(30000, 30000)
        VeMixer.mixInto(o3, loud, 0, 1f, 0, 0)
        assertEquals(32767, o3[0].toInt())
        // gain
        val o4 = ShortArray(2)
        VeMixer.mixInto(o4, shortArrayOf(1000, 1000), 0, 0.5f, 0, 0)
        assertEquals(500, o4[0].toInt())
    }

    @Test
    fun wavHeaderIsValid() {
        val h = VeMixer.wavHeader(frames = 100, rate = 44100, channels = 2)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVE", String(h, 8, 4))
        val bb = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 400, bb.getInt(4))
        assertEquals(2, bb.getShort(22).toInt())
        assertEquals(44100, bb.getInt(24))
        assertEquals(400, bb.getInt(40))
    }

    @Test
    fun trimRespectsMinimumAndSpeed() {
        // gagang kiri: tidak boleh melewati (akhir - 200ms)
        assertEquals(800L, VeTrim.left(0, 1000, 1f, 5000))
        assertEquals(0L, VeTrim.left(0, 1000, 1f, -500))
        // dengan kecepatan 2×, 100 ms timeline = 200 ms sumber
        assertEquals(200L, VeTrim.left(0, 4000, 2f, 100))
        // gagang kanan: kembali 0 saat mencapai ujung sumber
        assertEquals(0L, VeTrim.right(0, 900, 1000, 1f, 500))
        assertEquals(700L, VeTrim.right(0, 900, 1000, 1f, -200))
        assertEquals(200L, VeTrim.right(0, 900, 1000, 1f, -5000)) // minimal 200 ms
        assertNotNull(VeTrim.MIN_MS)
    }

    @Test
    fun noiseIsStableAndSpread() {
        assertEquals(VeFxMath.noise(7), VeFxMath.noise(7), 0f)
        var lo = 1f; var hi = 0f
        for (i in 0 until 200) { val v = VeFxMath.noise(i); lo = minOf(lo, v); hi = maxOf(hi, v) }
        assertTrue(lo < 0.1f && hi > 0.9f)
    }

    @Test
    fun mixIntoFadesRelativeToWholeItem() {
        // Item 10 frame, fade-in 4 frame. Dicampur dalam dua blok: fade harus kontinu antar blok.
        val item = ShortArray(20) { 10000 }
        val whole = ShortArray(20)
        VeMixer.mixInto(whole, item, 0, 1f, 4, 0)
        val blocks = ShortArray(20)
        VeMixer.mixInto(blocks, item.copyOfRange(0, 12), 0, 1f, 4, 0, srcFrameOffset = 0, itemFrames = 10)
        VeMixer.mixInto(blocks, item.copyOfRange(12, 20), 6, 1f, 4, 0, srcFrameOffset = 6, itemFrames = 10)
        assertArrayEquals(whole, blocks)
        assertEquals(0, whole[0].toInt())
        assertEquals(10000, whole[10].toInt())
    }

    @Test
    fun resamplerStreamingMatchesLengthAndIsContinuous() {
        // Rampa stereo: frame i bernilai i (kedua kanal).
        val n = 200
        val ramp = ShortArray(n * 2) { (it / 2).toShort() }
        val rs = VeResampler(44100, 22050)
        val a = rs.process(ramp.copyOfRange(0, 2 * 77))
        val b = rs.process(ramp.copyOfRange(2 * 77, 2 * 150))
        val c = rs.process(ramp.copyOfRange(2 * 150, 2 * n))
        val joined = a + b + c
        val frames = joined.size / 2
        assertTrue("panjang ~ n/2: $frames", frames in 98..100)
        // Setiap keluaran ke-i berada di sumber 2*i → nilainya 2*i (kontinu lintas potongan)
        for (i in 0 until frames) assertEquals("frame $i", 2 * i, joined[i * 2].toInt())
        // Rasio sama = lewat langsung
        val same = ShortArray(10)
        assertTrue(VeResampler(44100, 44100).process(same) === same)
        // Naik rate: 22050 → 44100 menggandakan panjang
        val up = VeResampler(22050, 44100)
        val u = up.process(ShortArray(2 * 100) { (it / 2).toShort() }) + up.process(ShortArray(2 * 100) { ((it / 2) + 100).toShort() })
        assertTrue(u.size / 2 in 396..400)
        for (i in 1 until u.size / 2) assertTrue(u[i * 2] >= u[(i - 1) * 2])
    }

    @Test
    fun cutPlanFromKeepRangesUsesSourceTime() {
        // Klip dipangkas mulai 2000 ms; bagian yang dipertahankan relatif ke awal trim.
        val pieces = VeCutPlan.fromKeep(2000L, listOf(0L until 1500L, 2500L until 4000L))
        assertEquals(2, pieces.size)
        assertEquals(2000L, pieces[0].trimStartMs)
        assertEquals(3500L, pieces[0].trimEndMs)
        assertEquals(4500L, pieces[1].trimStartMs)
        assertEquals(6000L, pieces[1].trimEndMs)
    }

    @Test
    fun cutPlanFromCutsKeepsOriginalEndOnLastPiece() {
        // Klip 1000..9000 ms tanpa trimEnd eksplisit (0): potong di 2000 dan 5000 (relatif).
        val pieces = VeCutPlan.fromCuts(1000L, 9000L, 0L, listOf(5000L, 2000L, 2000L, 99_000L))
        assertEquals(3, pieces.size)
        assertEquals(1000L, pieces[0].trimStartMs); assertEquals(3000L, pieces[0].trimEndMs)
        assertEquals(3000L, pieces[1].trimStartMs); assertEquals(6000L, pieces[1].trimEndMs)
        assertEquals(6000L, pieces[2].trimStartMs); assertEquals(0L, pieces[2].trimEndMs)
        // Tanpa titik potong → satu potongan identik
        val one = VeCutPlan.fromCuts(0L, 4000L, 4000L, emptyList())
        assertEquals(1, one.size)
        assertEquals(0L, one[0].trimStartMs); assertEquals(4000L, one[0].trimEndMs)
    }

    @Test
    fun transformCompositionMatchesSequentialMatrices() {
        // Klip: zoom 2×, geser horizontal penuh ke kanan → tx = +1 (setengah lebar bingkai).
        val clip = VeFxCompose.clipTransform(2f, false, 1f, 0f)
        assertArrayEquals(floatArrayOf(2f, 2f, 1f, 0f), clip, eps)
        // Geser vertikal ke bawah = ty negatif (NDC +y ke atas)
        assertEquals(-1f, VeFxCompose.clipTransform(2f, false, 0f, 1f)[3], eps)
        // Cermin membalik skala-x
        assertEquals(-2f, VeFxCompose.clipTransform(2f, true, 0f, 0f)[0], eps)
        // Lalu efek zoom 1.5× di pusat: p' = 1.5·(2p + 1) → skala 3, geser 1.5
        val total = VeFxCompose.then(clip, floatArrayOf(1.5f, 1.5f, 0f, 0f))
        assertArrayEquals(floatArrayOf(3f, 3f, 1.5f, 0f), total, eps)
        // Efek geser murni
        val shifted = VeFxCompose.then(floatArrayOf(1f, 1f, 0f, 0f), floatArrayOf(1f, 1f, 0.1f, -0.2f))
        assertArrayEquals(floatArrayOf(1f, 1f, 0.1f, -0.2f), shifted, eps)
        // Identitas
        assertArrayEquals(VeFxCompose.IDENTITY, VeFxCompose.then(VeFxCompose.IDENTITY, VeFxCompose.IDENTITY), eps)
    }

    @Test
    fun overlaySlotsShareNonOverlappingAndKeepZOrder() {
        // 3 keterangan berurutan (tidak bertumpuk) → satu slot
        assertArrayEquals(intArrayOf(0, 0, 0), VeOverlaySlots.assign(longArrayOf(0, 1000, 2000), longArrayOf(1000, 2000, 3000)))
        // item ke-2 bertumpuk dengan ke-1 → slot lebih tinggi; ke-3 tidak bertumpuk dengan keduanya → slot 0
        assertArrayEquals(intArrayOf(0, 1, 0), VeOverlaySlots.assign(longArrayOf(0, 500, 3000), longArrayOf(1000, 1500, 4000)))
        // rantai z: C bertumpuk dengan B (slot 1) → slot 2 meski A sudah selesai
        assertArrayEquals(intArrayOf(0, 1, 2), VeOverlaySlots.assign(longArrayOf(0, 500, 1200), longArrayOf(1000, 2000, 3000)))
        // 300 keterangan SRT berurutan tetap 1 slot (batas Media3: 15 overlay per efek)
        val n = 300
        val lanes = VeOverlaySlots.assign(LongArray(n) { it * 1000L }, LongArray(n) { it * 1000L + 900L })
        assertEquals(0, lanes.maxOrNull()!!)
    }

    @Test
    fun resamplerKeepsLengthAndContinuity() {
        val r = VeResampler(48000, 44100)
        val a = ShortArray(2 * 4800) { (it / 2 % 100).toShort() }
        val out1 = r.process(a)
        val out2 = r.process(a)
        // 4800 frame @48k ≈ 4410 frame @44.1k per potongan (±1 frame)
        assertTrue(kotlin.math.abs(out1.size / 2 - 4410) <= 2)
        assertTrue(kotlin.math.abs((out1.size + out2.size) / 2 - 8820) <= 3)
    }
}
