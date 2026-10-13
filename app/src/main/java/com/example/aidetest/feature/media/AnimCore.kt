package com.example.aidetest

import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Logika murni Animation Studio & Presentation Studio — tanpa API Android, diuji di JVM
 * (AnimCoreTest): easing, keyframe & interpolasi, preset animasi, pewaktuan frame, konversi
 * ARGB → YUV420 (I420 / NV12 / bidang ber-stride), kuantisasi palet + LZW + encoder GIF89a,
 * serta codec JSON track keyframe.
 *
 * Model animasi: setiap layer (CsElement.id) punya track keyframe berisi OFFSET terhadap desain
 * aslinya (geser dx/dy px kanvas, putar derajat, skala ×, opasitas ×). Keadaan "diam" = desain
 * Foto Studio apa adanya, jadi preset tetap benar di mana pun objek berada.
 */

// ====================================================================== easing

internal object AnEase {
    const val LINEAR = "linear"
    const val IN = "in"
    const val OUT = "out"
    const val IN_OUT = "inout"
    val ALL = listOf(LINEAR, IN, OUT, IN_OUT)

    fun label(e: String): String = when (e) {
        IN -> "Ease-in"
        OUT -> "Ease-out"
        IN_OUT -> "Ease-in-out"
        LINEAR -> "Linear"
        else -> AnEasing.label(e)
    }

    /** Ease lama (4 nilai) atau bentuk lanjutan AnEasing (back/bounce/elastic/spring/bezier) → kanonik; rusak = in-out. */
    fun valid(e: String?): String = if (e != null && e in ALL) e else AnEasing.canonical(e) ?: IN_OUT

    /** Kurva kubik (4 nilai lama: 0 → 0, 1 → 1, monoton) atau kurva lanjutan AnEasing (boleh overshoot). */
    fun apply(e: String, p: Float): Float {
        val t = if (p.isNaN()) 0f else p.coerceIn(0f, 1f)
        return when (e) {
            IN -> t * t * t
            OUT -> { val u = 1f - t; 1f - u * u * u }
            IN_OUT -> if (t < 0.5f) 4f * t * t * t else { val u = -2f * t + 2f; 1f - u * u * u / 2f }
            LINEAR -> t
            else -> AnEasing.parse(e)?.let { AnEasing.apply(it, t) } ?: t
        }
    }
}

// ====================================================================== keyframe

/**
 * Satu keyframe pada waktu [t] (ms). [ease] = kurva segmen DARI keyframe ini menuju keyframe berikutnya.
 */
internal data class AnKey(
    val t: Long,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val rot: Float = 0f,
    val scale: Float = 1f,
    val opacity: Float = 1f,
    val ease: String = AnEase.IN_OUT
)

/** Keadaan animasi satu layer pada satu waktu (offset terhadap desain). */
internal data class AnState(val dx: Float, val dy: Float, val rot: Float, val scale: Float, val opacity: Float) {
    fun isIdentity(): Boolean = dx == 0f && dy == 0f && rot == 0f && scale == 1f && opacity == 1f

    companion object {
        val IDENTITY = AnState(0f, 0f, 0f, 1f, 1f)
    }
}

internal object AnMath {
    const val MIN_DURATION = 1_000L
    const val MAX_DURATION = 60_000L
    const val MIN_SCALE = 0f
    const val MAX_SCALE = 10f

    fun clampDuration(ms: Long): Long = ms.coerceIn(MIN_DURATION, MAX_DURATION)

    fun stateOf(k: AnKey): AnState = AnState(k.dx, k.dy, k.rot, k.scale, k.opacity.coerceIn(0f, 1f))

    private fun lerp(a: Float, b: Float, p: Float): Float = a + (b - a) * p

    /** Interpolasi keyframe (harus terurut menurut t). Kosong = identitas; di luar rentang = keyframe terdekat. */
    fun sample(keys: List<AnKey>, t: Long): AnState {
        if (keys.isEmpty()) return AnState.IDENTITY
        val first = keys[0]
        if (t <= first.t) return stateOf(first)
        val last = keys[keys.size - 1]
        if (t >= last.t) return stateOf(last)
        // Cari segmen [i, i+1] berisi t (biner: track bisa panjang setelah banyak preset).
        var lo = 0
        var hi = keys.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (keys[mid].t <= t) lo = mid else hi = mid
        }
        val a = keys[lo]
        val b = keys[hi]
        val span = (b.t - a.t).coerceAtLeast(1L)
        val p = AnEase.apply(a.ease, (t - a.t).toFloat() / span)
        return AnState(
            lerp(a.dx, b.dx, p), lerp(a.dy, b.dy, p), lerp(a.rot, b.rot, p),
            lerp(a.scale, b.scale, p), lerp(a.opacity, b.opacity, p).coerceIn(0f, 1f)
        )
    }

    /** Indeks keyframe dalam jarak [tol] ms dari [t] (terdekat), atau -1. */
    fun keyAt(keys: List<AnKey>, t: Long, tol: Long): Int {
        var best = -1
        var bestD = Long.MAX_VALUE
        for ((i, k) in keys.withIndex()) {
            val d = abs(k.t - t)
            if (d <= tol && d < bestD) { best = i; bestD = d }
        }
        return best
    }

    /** Sisipkan [key]; keyframe lain dalam jarak [tol] ms diganti. Hasil terurut. */
    fun upsert(keys: List<AnKey>, key: AnKey, tol: Long): List<AnKey> {
        val out = keys.filter { abs(it.t - key.t) > tol }.toMutableList()
        out.add(key)
        out.sortBy { it.t }
        return out
    }

    fun removeAt(keys: List<AnKey>, t: Long, tol: Long): List<AnKey> {
        val i = keyAt(keys, t, tol)
        if (i < 0) return keys
        return keys.filterIndexed { j, _ -> j != i }
    }

    /** Keyframe di playhead yang nilainya sama dengan kurva saat ini (menambah keyframe tidak mengubah animasi). */
    fun keyFromCurve(keys: List<AnKey>, t: Long, ease: String = AnEase.IN_OUT): AnKey {
        val s = sample(keys, t)
        val prev = keys.lastOrNull { it.t <= t }
        return AnKey(t, s.dx, s.dy, s.rot, s.scale, s.opacity, prev?.ease ?: ease)
    }

    /** Buang keyframe di luar durasi (setelah durasi diperpendek). */
    fun clip(keys: List<AnKey>, durationMs: Long): List<AnKey> = keys.filter { it.t in 0L..durationMs }

    /** Bulatkan waktu ke frame terdekat. */
    fun snapToFrame(ms: Long, fps: Int): Long {
        val f = fps.coerceAtLeast(1)
        val frame = (ms * f / 1000.0).roundToLong()
        return (frame * 1000.0 / f).roundToLong()
    }

    /** Geser semua keyframe sebesar [dt] ms (dipotong ke >= 0). */
    fun shift(keys: List<AnKey>, dt: Long): List<AnKey> = keys.map { it.copy(t = (it.t + dt).coerceAtLeast(0L)) }.sortedBy { it.t }
}

// ====================================================================== preset

internal object AnPresets {
    const val NONE = "none"
    val ALL = listOf(
        "fade" to "Fade in",
        "slide_l" to "Masuk kiri",
        "slide_r" to "Masuk kanan",
        "slide_up" to "Masuk bawah",
        "pop" to "Pop",
        "zoom" to "Zoom",
        "spin" to "Putar",
        "parallax" to "Parallax ayun"
    )
    val IDS: Set<String> = ALL.map { it.first }.toSet()

    fun label(id: String): String = ALL.firstOrNull { it.first == id }?.second ?: "Tanpa"

    /** Durasi bawaan (ms) tiap preset. */
    fun defaultMs(id: String): Long = when (id) {
        "pop" -> 500L
        "spin" -> 900L
        "parallax" -> 2400L
        else -> 700L
    }

    /**
     * Keyframe preset masuk mulai [start] ms selama [dur] ms untuk layer berkotak (x, y, w, h) di kanvas cw×ch.
     * Objek berada di luar layar / tak terlihat sebelum [start] dan kembali ke desain asli di akhir.
     */
    fun keys(
        id: String, start: Long, dur: Long, x: Float, y: Float, w: Float, h: Float, cw: Float, ch: Float,
        depth: Float = 0f
    ): List<AnKey> {
        val s = start.coerceAtLeast(0L)
        val d = dur.coerceAtLeast(100L)
        val e = s + d
        return when (id) {
            "fade" -> listOf(AnKey(s, opacity = 0f, ease = AnEase.OUT), AnKey(e))
            "slide_l" -> listOf(AnKey(s, dx = -(x + w) - cw * 0.02f, ease = AnEase.OUT), AnKey(e))
            "slide_r" -> listOf(AnKey(s, dx = (cw - x) + cw * 0.02f, ease = AnEase.OUT), AnKey(e))
            "slide_up" -> listOf(AnKey(s, dy = (ch - y) + ch * 0.02f, ease = AnEase.OUT), AnKey(e))
            "pop" -> listOf(
                AnKey(s, scale = 0f, opacity = 0f, ease = AnEase.OUT),
                AnKey(s + (d * 0.65f).roundToLong(), scale = 1.12f, opacity = 1f, ease = AnEase.IN_OUT),
                AnKey(e)
            )
            "zoom" -> listOf(AnKey(s, scale = 1.8f, opacity = 0f, ease = AnEase.OUT), AnKey(e))
            "spin" -> listOf(AnKey(s, rot = -360f, scale = 0.2f, opacity = 0f, ease = AnEase.OUT), AnKey(e))
            // Gerak ayun 2.5D: layer dengan kedalaman besar bergeser lebih jauh daripada yang di belakang.
            "parallax" -> {
                val amp = cw * 0.03f * (0.25f + 0.75f * depth.coerceIn(0f, 1f))
                val q = d / 4
                listOf(
                    AnKey(s),
                    AnKey(s + q, dx = amp),
                    AnKey(s + 3 * q, dx = -amp),
                    AnKey(e)
                )
            }
            else -> emptyList()
        }
    }

    /**
     * Terapkan preset ke track yang sudah ada: keyframe di rentang [start, start+dur] diganti preset,
     * keyframe lain dipertahankan.
     */
    fun applyTo(existing: List<AnKey>, preset: List<AnKey>): List<AnKey> {
        if (preset.isEmpty()) return existing
        val from = preset.first().t
        val to = preset.last().t
        val kept = existing.filter { it.t < from || it.t > to }
        return (kept + preset).sortedBy { it.t }
    }

    /**
     * Track masuk untuk sekumpulan layer (Presentasi): layer ke-i mulai i × [stagger] ms.
     * [boxes] = (id, preset, x, y, w, h).
     */
    fun entranceTracks(boxes: List<EntranceSpec>, cw: Float, ch: Float, stagger: Long = 180L): Map<String, List<AnKey>> {
        val out = LinkedHashMap<String, List<AnKey>>()
        var i = 0
        for (b in boxes) {
            if (b.preset !in IDS) continue
            out[b.id] = keys(b.preset, i * stagger, defaultMs(b.preset), b.x, b.y, b.w, b.h, cw, ch)
            i++
        }
        return out
    }

    /** Akhir animasi masuk (ms) — 0 bila tidak ada. */
    fun tracksEnd(tracks: Map<String, List<AnKey>>): Long = tracks.values.maxOfOrNull { k -> k.lastOrNull()?.t ?: 0L } ?: 0L

    class EntranceSpec(val id: String, val preset: String, val x: Float, val y: Float, val w: Float, val h: Float)
}

// ====================================================================== pewaktuan & ukuran

internal object AnTiming {
    fun frameCount(durationMs: Long, fps: Int): Int =
        max(1, ceil(durationMs.coerceAtLeast(1L) * fps.coerceAtLeast(1) / 1000.0 - 1e-9).toInt())

    /** Waktu frame ke-i (ms, dibulatkan) — sampel animasi. */
    fun frameMs(i: Int, fps: Int): Long = (i * 1000.0 / fps.coerceAtLeast(1)).roundToLong()

    /** Presentation time frame ke-i (µs) untuk MediaCodec. */
    fun frameUs(i: Int, fps: Int): Long = i * 1_000_000L / fps.coerceAtLeast(1)

    /**
     * Jeda frame GIF (centidetik) dengan pembulatan kumulatif sehingga total sama dengan durasi
     * (mis. 15 fps → 7,7,6,7,7,6…). Minimal 2 cs (browser menaikkan 0–1 cs menjadi 10 cs).
     */
    fun gifDelayCs(i: Int, fps: Int): Int {
        val f = fps.coerceAtLeast(1)
        val a = (i * 100.0 / f).roundToInt()
        val b = ((i + 1) * 100.0 / f).roundToInt()
        return max(2, b - a)
    }

    /** Skala w×h agar sisi terpanjang ≤ [maxSide], lalu bulatkan ke kelipatan [align] (≥ 2, genap untuk YUV420). */
    fun fitEven(w: Int, h: Int, maxSide: Int, align: Int = 2): Pair<Int, Int> {
        val a = max(2, align)
        val s = min(1.0, maxSide.toDouble() / max(1, max(w, h)))
        fun r(v: Int): Int = max(a, ((v * s) / a).roundToInt() * a)
        return r(w) to r(h)
    }

    /** Bitrate H.264 kira-kira (bit/s): ~0,14 bit per piksel per frame, dibatasi 1,5–20 Mbps. */
    fun bitrate(w: Int, h: Int, fps: Int): Int = (w.toLong() * h * fps * 0.14).toLong().coerceIn(1_500_000L, 20_000_000L).toInt()

    /** Waktu mm:ss.d */
    fun fmt(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        val s = t / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}.${(t % 1000) / 100}"
    }
}

// ====================================================================== tata letak slide

internal object AnLayout {
    /** Ukuran slide presentasi: kunci → (lebar, tinggi). */
    val SLIDE_SIZES = linkedMapOf("16:9" to (1920 to 1080), "4:3" to (1440 to 1080), "9:16" to (1080 to 1920))

    /** Preset slide dengan rasio terdekat untuk desain w×h (selisih log-rasio terkecil). */
    fun nearestSlideSize(w: Int, h: Int): String {
        val r = kotlin.math.ln(w.coerceAtLeast(1).toDouble() / h.coerceAtLeast(1))
        return SLIDE_SIZES.minByOrNull { (_, s) -> abs(kotlin.math.ln(s.first.toDouble() / s.second) - r) }?.key ?: "16:9"
    }

    /**
     * Skala seragam + offset agar konten fromW×fromH pas (letterbox, di tengah) di toW×toH.
     * @return [skala, offsetX, offsetY]
     */
    fun fit(fromW: Float, fromH: Float, toW: Float, toH: Float): FloatArray {
        val s = min(toW / fromW.coerceAtLeast(1f), toH / fromH.coerceAtLeast(1f))
        return floatArrayOf(s, (toW - fromW * s) / 2f, (toH - fromH * s) / 2f)
    }

    /** Indeks setelah memindahkan item [from] ke [to] dalam daftar berukuran [size] (dibatasi). */
    fun moveIndex(from: Int, to: Int, size: Int): Int = to.coerceIn(0, (size - 1).coerceAtLeast(0))
}

// ====================================================================== YUV

internal object AnYuv {
    private fun y(r: Int, g: Int, b: Int): Int = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
    private fun u(r: Int, g: Int, b: Int): Int = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
    private fun v(r: Int, g: Int, b: Int): Int = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

    fun chromaW(w: Int): Int = (w + 1) / 2
    fun chromaH(h: Int): Int = (h + 1) / 2

    /**
     * ARGB (alpha diabaikan — frame sudah diratakan) → I420 padat, BT.601 rentang terbatas.
     * Krominans = rata-rata blok 2×2. [yOut] w×h, [uOut]/[vOut] ceil(w/2)×ceil(h/2).
     */
    fun argbToI420(argb: IntArray, w: Int, h: Int, yOut: ByteArray, uOut: ByteArray, vOut: ByteArray) {
        require(argb.size >= w * h && yOut.size >= w * h) { "Ukuran buffer tidak cukup" }
        val cw = chromaW(w)
        val ch = chromaH(h)
        require(uOut.size >= cw * ch && vOut.size >= cw * ch) { "Ukuran buffer krominans tidak cukup" }
        for (i in 0 until w * h) {
            val c = argb[i]
            yOut[i] = y((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF).toByte()
        }
        for (cy in 0 until ch) {
            val y0 = cy * 2
            val y1 = min(y0 + 1, h - 1)
            for (cx in 0 until cw) {
                val x0 = cx * 2
                val x1 = min(x0 + 1, w - 1)
                val p0 = argb[y0 * w + x0]
                val p1 = argb[y0 * w + x1]
                val p2 = argb[y1 * w + x0]
                val p3 = argb[y1 * w + x1]
                val r = (((p0 shr 16) and 0xFF) + ((p1 shr 16) and 0xFF) + ((p2 shr 16) and 0xFF) + ((p3 shr 16) and 0xFF) + 2) shr 2
                val g = (((p0 shr 8) and 0xFF) + ((p1 shr 8) and 0xFF) + ((p2 shr 8) and 0xFF) + ((p3 shr 8) and 0xFF) + 2) shr 2
                val b = ((p0 and 0xFF) + (p1 and 0xFF) + (p2 and 0xFF) + (p3 and 0xFF) + 2) shr 2
                uOut[cy * cw + cx] = u(r, g, b).toByte()
                vOut[cy * cw + cx] = v(r, g, b).toByte()
            }
        }
    }

    /** I420 padat (satu larik: Y, U, V). */
    fun argbToI420(argb: IntArray, w: Int, h: Int): ByteArray {
        val cs = chromaW(w) * chromaH(h)
        val yb = ByteArray(w * h)
        val ub = ByteArray(cs)
        val vb = ByteArray(cs)
        argbToI420(argb, w, h, yb, ub, vb)
        return yb + ub + vb
    }

    /** NV12 padat: Y lalu UV berselang-seling. */
    fun argbToNV12(argb: IntArray, w: Int, h: Int): ByteArray {
        val cs = chromaW(w) * chromaH(h)
        val yb = ByteArray(w * h)
        val ub = ByteArray(cs)
        val vb = ByteArray(cs)
        argbToI420(argb, w, h, yb, ub, vb)
        val out = ByteArray(w * h + cs * 2)
        System.arraycopy(yb, 0, out, 0, yb.size)
        var o = w * h
        for (i in 0 until cs) {
            out[o++] = ub[i]
            out[o++] = vb[i]
        }
        return out
    }

    /**
     * Tulis bidang padat [src] (sw×sh) ke [dst] berlayout bidang Image: posisi (x, y) →
     * y × [rowStride] + x × [pixelStride] (+ [offset]). Byte lain di [dst] tidak disentuh, sehingga
     * bidang U & V yang berbagi memori (NV12/NV21, pixelStride 2) bisa ditulis bergantian.
     */
    fun scatter(src: ByteArray, sw: Int, sh: Int, dst: ByteArray, rowStride: Int, pixelStride: Int, offset: Int = 0) {
        require(pixelStride >= 1 && rowStride >= (sw - 1) * pixelStride + 1) { "Stride tidak valid" }
        for (yy in 0 until sh) {
            val s = yy * sw
            val d = offset + yy * rowStride
            if (pixelStride == 1) {
                val n = min(sw, dst.size - d)
                if (n > 0) System.arraycopy(src, s, dst, d, n)
            } else {
                var di = d
                for (xx in 0 until sw) {
                    if (di >= dst.size) break
                    dst[di] = src[s + xx]
                    di += pixelStride
                }
            }
        }
    }

    /** Panjang minimum buffer bidang ber-stride (baris terakhir tidak perlu padding penuh). */
    fun planeLength(w: Int, h: Int, rowStride: Int, pixelStride: Int): Int = (h - 1) * rowStride + (w - 1) * pixelStride + 1
}

// ====================================================================== GIF

/** Kuantisasi warna median-cut pada histogram RGB 15-bit. */
internal class AnGifPalette(val colors: IntArray, private val lookup: IntArray) {
    /** Indeks palet terdekat untuk warna RGB (alpha diabaikan). */
    fun index(rgb: Int): Int {
        val key = ((rgb shr 9) and 0x7C00) or ((rgb shr 6) and 0x03E0) or ((rgb shr 3) and 0x001F)
        var i = lookup[key]
        if (i < 0) {
            i = nearest(rgb)
            lookup[key] = i
        }
        return i
    }

    private fun nearest(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        var best = 0
        var bestD = Int.MAX_VALUE
        for ((i, c) in colors.withIndex()) {
            val dr = r - ((c shr 16) and 0xFF)
            val dg = g - ((c shr 8) and 0xFF)
            val db = b - (c and 0xFF)
            val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
            if (d < bestD) { bestD = d; best = i; if (d == 0) break }
        }
        return best
    }

    companion object {
        /** Palet ≤ [maxColors] dari piksel buram (alpha ≥ 128) di [argb]. */
        fun build(argb: IntArray, maxColors: Int): AnGifPalette {
            val n = maxColors.coerceIn(1, 256)
            val count = IntArray(32768)
            val sr = LongArray(32768)
            val sg = LongArray(32768)
            val sb = LongArray(32768)
            for (c in argb) {
                if ((c ushr 24) < 128) continue
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val k = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                count[k]++
                sr[k] += r.toLong()
                sg[k] += g.toLong()
                sb[k] += b.toLong()
            }
            val bins = ArrayList<Int>()
            for (k in 0 until 32768) if (count[k] > 0) bins.add(k)
            if (bins.isEmpty()) return AnGifPalette(intArrayOf(0), IntArray(32768) { -1 })
            fun ch(k: Int, axis: Int): Int = when (axis) { 0 -> (k shr 10) and 31; 1 -> (k shr 5) and 31; else -> k and 31 }
            // Statistik kotak dihitung sekali saat kotak dibuat (bukan setiap putaran pembelahan).
            class Box(val keys: IntArray) {
                var total = 0L
                var axis = 0
                var score = -1L
                init {
                    for (k in keys) total += count[k]
                    if (keys.size >= 2) for (ax in 0..2) {
                        var lo = 31
                        var hi = 0
                        for (k in keys) { val v = ch(k, ax); if (v < lo) lo = v; if (v > hi) hi = v }
                        val sc = (hi - lo).toLong() * total
                        if (hi > lo && sc > score) { score = sc; axis = ax }
                    }
                }
            }
            val boxes = ArrayList<Box>()
            boxes.add(Box(bins.toIntArray()))
            while (boxes.size < n) {
                // Kotak dengan rentang kanal terlebar (dibobot jumlah piksel) dibelah di median.
                var pick = -1
                var pickScore = -1L
                for ((bi, box) in boxes.withIndex()) if (box.score > pickScore) { pickScore = box.score; pick = bi }
                if (pick < 0 || pickScore < 0) break
                val box = boxes[pick]
                val axis = box.axis
                val sorted = box.keys.sortedBy { ch(it, axis) }
                var acc = 0L
                var cut = 1
                for ((i, k) in sorted.withIndex()) {
                    acc += count[k]
                    if (acc * 2 >= box.total) { cut = i + 1; break }
                }
                cut = cut.coerceIn(1, sorted.size - 1)
                boxes[pick] = Box(sorted.subList(0, cut).toIntArray())
                boxes.add(Box(sorted.subList(cut, sorted.size).toIntArray()))
            }
            val colors = IntArray(boxes.size)
            val lookup = IntArray(32768) { -1 }
            for ((i, box) in boxes.withIndex()) {
                var c = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                for (k in box.keys) { c += count[k]; r += sr[k]; g += sg[k]; b += sb[k]; lookup[k] = i }
                val cc = c.coerceAtLeast(1L)
                colors[i] = (0xFF shl 24) or (((r + cc / 2) / cc).toInt() shl 16) or (((g + cc / 2) / cc).toInt() shl 8) or ((b + cc / 2) / cc).toInt()
            }
            return AnGifPalette(colors, lookup)
        }
    }
}

/** Kompresi LZW varian GIF (kode 3–12 bit, kode clear saat tabel penuh), keluaran sub-blok ≤ 255 byte. */
internal object AnLzw {
    private const val HSIZE = 5003
    private const val HSHIFT = 4
    private const val MAX_CODES = 4096

    fun encode(px: ByteArray, minCodeSize: Int, out: OutputStream) {
        val init = minCodeSize.coerceIn(2, 8)
        out.write(init)
        val packer = BlockPacker(out)
        val clear = 1 shl init
        val eoi = clear + 1
        var codeSize = init + 1
        var next = eoi + 1
        var clearFlag = false
        val htab = IntArray(HSIZE) { -1 }
        val codetab = IntArray(HSIZE)

        fun emit(code: Int) {
            packer.bits(code, codeSize)
            if (clearFlag) {
                codeSize = init + 1
                clearFlag = false
            } else if (next > (1 shl codeSize) - 1 && codeSize < 12) {
                codeSize++
            }
        }

        emit(clear)
        if (px.isEmpty()) {
            emit(eoi)
            packer.finish()
            return
        }
        var ent = px[0].toInt() and 0xFF
        for (idx in 1 until px.size) {
            val c = px[idx].toInt() and 0xFF
            val fcode = (c shl 12) + ent
            var i = (c shl HSHIFT) xor ent
            if (htab[i] == fcode) { ent = codetab[i]; continue }
            var found = false
            if (htab[i] >= 0) {
                val disp = if (i == 0) 1 else HSIZE - i
                do {
                    i -= disp
                    if (i < 0) i += HSIZE
                    if (htab[i] == fcode) { ent = codetab[i]; found = true; break }
                } while (htab[i] >= 0)
            }
            if (found) continue
            emit(ent)
            ent = c
            if (next < MAX_CODES) {
                codetab[i] = next++
                htab[i] = fcode
            } else {
                htab.fill(-1)
                next = clear + 2
                clearFlag = true
                emit(clear)
            }
        }
        emit(ent)
        emit(eoi)
        packer.finish()
    }

    private class BlockPacker(private val out: OutputStream) {
        private val block = ByteArray(255)
        private var len = 0
        private var acc = 0
        private var nbits = 0

        fun bits(code: Int, size: Int) {
            acc = acc or (code shl nbits)
            nbits += size
            while (nbits >= 8) {
                byte(acc and 0xFF)
                acc = acc ushr 8
                nbits -= 8
            }
        }

        private fun byte(b: Int) {
            block[len++] = b.toByte()
            if (len == 255) flushBlock()
        }

        private fun flushBlock() {
            if (len == 0) return
            out.write(len)
            out.write(block, 0, len)
            len = 0
        }

        fun finish() {
            if (nbits > 0) { byte(acc and 0xFF); acc = 0; nbits = 0 }
            flushBlock()
            out.write(0)
        }
    }
}

/**
 * Encoder GIF89a beranimasi (streaming): tiap frame membawa palet lokal (median-cut ≤ 256 warna).
 * Piksel alpha < 128 → indeks transparan (disposal "kembalikan ke latar"), sehingga latar transparan
 * desain tetap transparan. [loop] 0 = ulang terus.
 */
internal class AnGifEncoder(private val out: OutputStream, val width: Int, val height: Int, loop: Int = 0) {
    private var finished = false
    var frames = 0
        private set

    init {
        require(width in 1..65535 && height in 1..65535) { "Ukuran GIF tidak valid" }
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(width)
        short(height)
        out.write(0x70) // tanpa tabel warna global, resolusi warna 8 bit
        out.write(0)
        out.write(0)
        // NETSCAPE2.0: jumlah ulang
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x03, 0x01))
        short(loop.coerceIn(0, 65535))
        out.write(0)
    }

    private fun short(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    /** Tambah satu frame penuh width×height (ARGB). [delayCs] dalam centidetik. */
    fun addFrame(argb: IntArray, delayCs: Int) {
        check(!finished) { "GIF sudah selesai" }
        require(argb.size >= width * height) { "Frame terlalu kecil" }
        val n = width * height
        var transparent = false
        for (i in 0 until n) if ((argb[i] ushr 24) < 128) { transparent = true; break }
        val palette = AnGifPalette.build(argb, if (transparent) 255 else 256)
        val count = palette.colors.size + if (transparent) 1 else 0
        var bits = 1
        while ((1 shl bits) < count) bits++
        val transIndex = if (transparent) palette.colors.size else 0
        val idx = ByteArray(n)
        for (i in 0 until n) {
            val c = argb[i]
            idx[i] = (if (transparent && (c ushr 24) < 128) transIndex else palette.index(c)).toByte()
        }
        // Graphic Control Extension
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04))
        val disposal = if (transparent) 2 else 1
        out.write((disposal shl 2) or (if (transparent) 1 else 0))
        short(delayCs.coerceIn(0, 65535))
        out.write(transIndex)
        out.write(0)
        // Image Descriptor + tabel warna lokal
        out.write(0x2C)
        short(0); short(0); short(width); short(height)
        out.write(0x80 or (bits - 1))
        val table = ByteArray(3 * (1 shl bits))
        for ((i, c) in palette.colors.withIndex()) {
            table[i * 3] = ((c shr 16) and 0xFF).toByte()
            table[i * 3 + 1] = ((c shr 8) and 0xFF).toByte()
            table[i * 3 + 2] = (c and 0xFF).toByte()
        }
        out.write(table)
        AnLzw.encode(idx, max(2, bits), out)
        frames++
    }

    fun finish() {
        if (finished) return
        finished = true
        out.write(0x3B)
        out.flush()
    }
}

// ====================================================================== codec track

/** Serialisasi track keyframe ke pohon Map/List (JSON lewat treeToJson). */
internal object AnCodec {
    private fun fin(v: Float): Double = if (v.isNaN() || v.isInfinite()) 0.0 else v.toDouble()

    fun encodeKey(k: AnKey): Map<String, Any?> = linkedMapOf(
        "t" to k.t, "dx" to fin(k.dx), "dy" to fin(k.dy), "r" to fin(k.rot),
        "s" to fin(k.scale), "o" to fin(k.opacity), "e" to k.ease
    )

    fun decodeKey(m: Map<String, Any?>): AnKey? {
        val t = (m["t"] as? Number)?.toLong() ?: return null
        fun f(key: String, def: Float): Float = (m[key] as? Number)?.toFloat()?.takeIf { !it.isNaN() && !it.isInfinite() } ?: def
        return AnKey(
            t = t.coerceIn(0L, AnMath.MAX_DURATION),
            dx = f("dx", 0f), dy = f("dy", 0f), rot = f("r", 0f),
            scale = f("s", 1f).coerceIn(AnMath.MIN_SCALE, AnMath.MAX_SCALE),
            opacity = f("o", 1f).coerceIn(0f, 1f),
            ease = AnEase.valid(m["e"] as? String)
        )
    }

    fun encodeTracks(tracks: Map<String, List<AnKey>>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((id, keys) in tracks) if (keys.isNotEmpty()) out[id] = keys.map { encodeKey(it) }
        return out
    }

    fun decodeTracks(m: Map<String, Any?>?): MutableMap<String, List<AnKey>> {
        val out = LinkedHashMap<String, List<AnKey>>()
        if (m == null) return out
        for ((id, raw) in m) {
            val list = raw as? List<*> ?: continue
            @Suppress("UNCHECKED_CAST")
            val keys = list.mapNotNull { (it as? Map<String, Any?>)?.let(::decodeKey) }.sortedBy { it.t }
            // Waktu ganda (berkas disunting manual) → yang terakhir menang.
            val dedup = LinkedHashMap<Long, AnKey>()
            keys.forEach { dedup[it.t] = it }
            if (dedup.isNotEmpty()) out[id] = dedup.values.toList()
        }
        return out
    }
}
