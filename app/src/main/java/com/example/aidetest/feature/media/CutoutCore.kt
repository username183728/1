package com.example.aidetest

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Potong Manual (Foto Studio → tab Potong) — logika murni tanpa API Android, diuji JVM:
 *  - [CoMask]    : mask seleksi (ByteArray 0..255): kuas, poligon/laso, feather, rapikan tepi, overlay
 *  - [CoPaint]   : operasi piksel per sapuan kuas pada buffer ARGB (hapus, pulihkan, kuas warna, klon)
 *  - [CoTiles]   : pembagian ubin untuk riwayat undo berbasis wilayah
 *  - [CoPack]    : kompres/dekompres piksel riwayat (Deflater)
 *  - [CoHistory] : tumpukan undo/redo dengan batas jumlah & byte
 *  - [CoClone]   : titik sumber klon (offset sejajar)
 *  - [CoManifestCodec] : manifest proyek (pohon Map ↔ JSON di sisi UI)
 * Semua koordinat dalam piksel gambar; Rect memakai batas kanan/bawah eksklusif.
 */

internal data class CoRect(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w: Int get() = r - l
    val h: Int get() = b - t
    val isEmpty: Boolean get() = r <= l || b <= t

    fun union(o: CoRect): CoRect = when {
        isEmpty -> o
        o.isEmpty -> this
        else -> CoRect(min(l, o.l), min(t, o.t), max(r, o.r), max(b, o.b))
    }

    fun inset(d: Int): CoRect = CoRect(l - d, t - d, r + d, b + d)
    fun clamp(w: Int, h: Int): CoRect = CoRect(l.coerceIn(0, w), t.coerceIn(0, h), r.coerceIn(0, w), b.coerceIn(0, h))
    fun intersects(o: CoRect): Boolean = !isEmpty && !o.isEmpty && l < o.r && o.l < r && t < o.b && o.t < b

    companion object {
        val EMPTY = CoRect(0, 0, 0, 0)

        /** Kotak sapuan lingkaran (pusat [cx],[cy], radius [rad]) dipotong ke gambar w×h. */
        fun around(cx: Float, cy: Float, rad: Float, w: Int, h: Int): CoRect =
            CoRect(floor(cx - rad).toInt(), floor(cy - rad).toInt(), ceil(cx + rad).toInt() + 1, ceil(cy + rad).toInt() + 1).clamp(w, h)
    }
}

/** Mode gabung seleksi baru dengan seleksi yang ada. */
internal enum class CoSelMode { REPLACE, ADD, SUBTRACT }

internal object CoMask {
    /**
     * Opasitas kuas pada jarak [d] dari pusat: penuh sampai radius×kekerasan, lalu turun halus
     * (smoothstep) sampai 0 di tepi. [hardness] 0..100.
     */
    fun brushAlpha(d: Float, rad: Float, hardness: Int): Float {
        if (rad <= 0f || d >= rad) return 0f
        val core = rad * hardness.coerceIn(0, 100) / 100f
        if (d <= core) return 1f
        val t = ((d - core) / (rad - core).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    /** Kuas seleksi: [add] = tambah (maks), selain itu kurangi (min). @return wilayah yang berubah. */
    fun stamp(mask: ByteArray, w: Int, h: Int, cx: Float, cy: Float, rad: Float, hardness: Int, add: Boolean, strength: Float = 1f): CoRect {
        val rc = CoRect.around(cx, cy, rad, w, h)
        if (rc.isEmpty) return rc
        for (y in rc.t until rc.b) {
            val dy = y + 0.5f - cy
            for (x in rc.l until rc.r) {
                val dx = x + 0.5f - cx
                val a = brushAlpha(sqrt(dx * dx + dy * dy), rad, hardness) * strength
                if (a <= 0f) continue
                val i = y * w + x
                val v = mask[i].toInt() and 0xFF
                val nv = if (add) max(v, (a * 255f).roundToInt()) else min(v, ((1f - a) * 255f).roundToInt())
                mask[i] = nv.coerceIn(0, 255).toByte()
            }
        }
        return rc
    }

    /** Gabungkan [src] ke [dst] (ukuran sama) sesuai [mode]. */
    fun combine(dst: ByteArray, src: ByteArray, mode: CoSelMode) {
        when (mode) {
            CoSelMode.REPLACE -> System.arraycopy(src, 0, dst, 0, min(dst.size, src.size))
            CoSelMode.ADD -> for (i in dst.indices) {
                val s = src[i].toInt() and 0xFF
                if (s > (dst[i].toInt() and 0xFF)) dst[i] = s.toByte()
            }
            CoSelMode.SUBTRACT -> for (i in dst.indices) {
                val s = src[i].toInt() and 0xFF
                if (s == 0) continue
                val d = dst[i].toInt() and 0xFF
                dst[i] = (d * (255 - s) / 255).toByte()
            }
        }
    }

    fun invert(mask: ByteArray) {
        for (i in mask.indices) mask[i] = (255 - (mask[i].toInt() and 0xFF)).toByte()
    }

    /**
     * Magic Wand / flood-fill berbasis warna (ARGB).
     * [px] = piksel sumber (ukuran w×h), [sx]/[sy] titik seed, [tolerance] 0..255 (jarak RGB).
     * Mengisi [out] dengan 255 di area terhubung 4-arah yang mirip warna seed.
     * @return bounding box area yang diisi, atau CoRect kosong jika gagal.
     */
    fun magicWand(px: IntArray, w: Int, h: Int, sx: Int, sy: Int, tolerance: Int, out: ByteArray): CoRect {
        if (sx !in 0 until w || sy !in 0 until h || out.size < w * h) return CoRect(0, 0, 0, 0)
        java.util.Arrays.fill(out, 0.toByte())
        val seed = px[sy * w + sx]
        val sr = (seed shr 16) and 0xFF
        val sg = (seed shr 8) and 0xFF
        val sb = seed and 0xFF
        val tol = tolerance.coerceIn(0, 255)
        val tol2 = tol * tol * 3 // approx for RGB distance
        val visited = BooleanArray(w * h)
        val queue = java.util.ArrayDeque<Int>(w * h / 8)
        val start = sy * w + sx
        queue.add(start)
        visited[start] = true
        var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
        var filled = 0
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % w
            val y = i / w
            val c = px[i]
            val cr = (c shr 16) and 0xFF
            val cg = (c shr 8) and 0xFF
            val cb = c and 0xFF
            val dr = cr - sr; val dg = cg - sg; val db = cb - sb
            if (dr * dr + dg * dg + db * db > tol2) continue
            out[i] = 255.toByte()
            filled++
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            // 4-connected
            if (x > 0) { val n = i - 1; if (!visited[n]) { visited[n] = true; queue.add(n) } }
            if (x < w - 1) { val n = i + 1; if (!visited[n]) { visited[n] = true; queue.add(n) } }
            if (y > 0) { val n = i - w; if (!visited[n]) { visited[n] = true; queue.add(n) } }
            if (y < h - 1) { val n = i + w; if (!visited[n]) { visited[n] = true; queue.add(n) } }
        }
        return if (filled == 0) CoRect(0, 0, 0, 0) else CoRect(minX, minY, maxX + 1, maxY + 1)
    }

    /** Seleksi dianggap kosong bila tak ada piksel ≥ [min] (sisa feather sangat tipis diabaikan). */
    fun isEmpty(mask: ByteArray, min: Int = 8): Boolean {
        for (b in mask) if ((b.toInt() and 0xFF) >= min) return false
        return true
    }

    fun coverage(mask: ByteArray): Long {
        var s = 0L
        for (b in mask) s += b.toInt() and 0xFF
        return s
    }

    /** Titik di dalam poligon (aturan genap-ganjil, sinar ke kanan). */
    fun pointInPolygon(x: Float, y: Float, xs: FloatArray, ys: FloatArray, n: Int): Boolean {
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val yi = ys[i]
            val yj = ys[j]
            if ((yi > y) != (yj > y)) {
                val xc = xs[i] + (y - yi) / (yj - yi) * (xs[j] - xs[i])
                if (x < xc) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Rasterisasi poligon tertutup (laso / poligon) ke mask w×h, genap-ganjil, anti-alias:
     * 4 sub-baris per baris piksel dan cakupan horizontal pecahan di ujung rentang.
     */
    fun rasterPolygon(xs: FloatArray, ys: FloatArray, n: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        if (n < 3 || w <= 0 || h <= 0) return out
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) { minY = min(minY, ys[i]); maxY = max(maxY, ys[i]) }
        val y0 = floor(minY).toInt().coerceIn(0, h)
        val y1 = ceil(maxY).toInt().coerceIn(0, h)
        val acc = FloatArray(w)
        val cross = FloatArray(n + 1)
        for (y in y0 until y1) {
            java.util.Arrays.fill(acc, 0f)
            for (s in 0 until 4) {
                val sy = y + (s + 0.5f) / 4f
                var c = 0
                var j = n - 1
                for (i in 0 until n) {
                    val yi = ys[i]
                    val yj = ys[j]
                    if ((yi <= sy && yj > sy) || (yj <= sy && yi > sy)) {
                        cross[c++] = xs[i] + (sy - yi) / (yj - yi) * (xs[j] - xs[i])
                    }
                    j = i
                }
                if (c < 2) continue
                java.util.Arrays.sort(cross, 0, c)
                var k = 0
                while (k + 1 < c) {
                    addSpan(acc, cross[k], cross[k + 1], 0.25f, w)
                    k += 2
                }
            }
            val row = y * w
            for (x in 0 until w) {
                val v = acc[x]
                if (v > 0f) out[row + x] = (v.coerceAtMost(1f) * 255f).roundToInt().toByte()
            }
        }
        return out
    }

    private fun addSpan(acc: FloatArray, a0: Float, b0: Float, wgt: Float, w: Int) {
        val a = a0.coerceIn(0f, w.toFloat())
        val b = b0.coerceIn(0f, w.toFloat())
        if (b <= a) return
        val ia = floor(a).toInt()
        val ib = floor(b).toInt()
        if (ia == ib) {
            if (ia < w) acc[ia] += (b - a) * wgt
            return
        }
        acc[ia] += (ia + 1 - a) * wgt
        for (i in ia + 1 until min(ib, w)) acc[i] += wgt
        if (ib < w) acc[ib] += (b - ib) * wgt
    }

    /** Poligon ditutup bila titik baru dekat titik pertama (≥ 3 titik). */
    fun closesPolygon(x: Float, y: Float, xs: FloatArray, ys: FloatArray, n: Int, tol: Float): Boolean {
        if (n < 3) return false
        val dx = x - xs[0]
        val dy = y - ys[0]
        return dx * dx + dy * dy <= tol * tol
    }

    /** Kernel Gauss 1D ternormalisasi (sigma = radius/2) — dipakai feather radius kecil (≤ 4 px). */
    fun gaussianKernel(radius: Int): FloatArray {
        val r = radius.coerceAtLeast(0)
        if (r == 0) return floatArrayOf(1f)
        val sigma = max(0.5f, r / 2f)
        val k = FloatArray(r * 2 + 1) { i -> val d = (i - r).toFloat(); exp(-(d * d) / (2f * sigma * sigma)) }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        return k
    }

    /** Lebar 3 kotak yang mendekati Gauss sigma [sigma] (blur kotak berulang; O(n) per lintasan). */
    fun boxesForGauss(sigma: Float, n: Int = 3): IntArray {
        val wIdeal = sqrt(12.0 * sigma * sigma / n + 1.0)
        var wl = floor(wIdeal).toInt()
        if (wl % 2 == 0) wl--
        if (wl < 1) wl = 1
        val wu = wl + 2
        val mIdeal = (12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4.0)
        val m = mIdeal.roundToInt()
        return IntArray(n) { if (it < m) wl else wu }
    }

    /** Feather: blur mask dengan radius [radius] px (Gauss pas untuk ≤ 4 px, kotak ×3 di atasnya). */
    fun feather(mask: ByteArray, w: Int, h: Int, radius: Float): ByteArray {
        if (radius <= 0.25f || w <= 0 || h <= 0) return mask.copyOf()
        val ri = radius.roundToInt()
        if (ri <= 4) {
            val k = gaussianKernel(ri.coerceAtLeast(1))
            val tmp = ByteArray(mask.size)
            val out = ByteArray(mask.size)
            convolveH(mask, tmp, w, h, k)
            convolveV(tmp, out, w, h, k)
            return out
        }
        var a = mask.copyOf()
        var b = ByteArray(mask.size)
        for (box in boxesForGauss(radius / 2f)) {
            val r = (box - 1) / 2
            if (r <= 0) continue
            boxH(a, b, w, h, r); boxV(b, a, w, h, r)
        }
        return a
    }

    private fun convolveH(src: ByteArray, dst: ByteArray, w: Int, h: Int, k: FloatArray) {
        val r = k.size / 2
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var s = 0f
                for (i in k.indices) {
                    val xx = (x + i - r).coerceIn(0, w - 1)
                    s += (src[row + xx].toInt() and 0xFF) * k[i]
                }
                dst[row + x] = s.roundToInt().coerceIn(0, 255).toByte()
            }
        }
    }

    private fun convolveV(src: ByteArray, dst: ByteArray, w: Int, h: Int, k: FloatArray) {
        val r = k.size / 2
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                for (i in k.indices) {
                    val yy = (y + i - r).coerceIn(0, h - 1)
                    s += (src[yy * w + x].toInt() and 0xFF) * k[i]
                }
                dst[y * w + x] = s.roundToInt().coerceIn(0, 255).toByte()
            }
        }
    }

    /** Blur kotak horizontal radius [r] (tepi diulang). */
    fun boxH(src: ByteArray, dst: ByteArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (i in -r..r) sum += src[row + i.coerceIn(0, w - 1)].toInt() and 0xFF
            for (x in 0 until w) {
                dst[row + x] = ((sum + div / 2) / div).toByte()
                val add = src[row + (x + r + 1).coerceAtMost(w - 1)].toInt() and 0xFF
                val sub = src[row + (x - r).coerceAtLeast(0)].toInt() and 0xFF
                sum += add - sub
            }
        }
    }

    /** Blur kotak vertikal radius [r] (tepi diulang). */
    fun boxV(src: ByteArray, dst: ByteArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += src[i.coerceIn(0, h - 1) * w + x].toInt() and 0xFF
            for (y in 0 until h) {
                dst[y * w + x] = ((sum + div / 2) / div).toByte()
                val add = src[(y + r + 1).coerceAtMost(h - 1) * w + x].toInt() and 0xFF
                val sub = src[(y - r).coerceAtLeast(0) * w + x].toInt() and 0xFF
                sum += add - sub
            }
        }
    }

    /** Ambang pergeseran tepi untuk blur kotak: perluas → ambang rendah, persempit → ambang tinggi. */
    fun shiftThreshold(shift: Int): Float = when {
        shift > 0 -> 0.06f
        shift < 0 -> 0.94f
        else -> 0.5f
    }

    /** Rampa ambang halus (lebar 0,07) di sekitar [t] — nilai 0..255 → 0..255. */
    fun ramp(v: Int, t: Float): Int {
        val x = ((v / 255f) - (t - 0.035f)) / 0.07f
        return (x.coerceIn(0f, 1f) * 255f).roundToInt()
    }

    /**
     * Rapikan tepi: [smooth] 0..100 (bulatkan sudut & gerigi), [shift] px (+ perluas / − persempit),
     * lalu [feather] px. Tepi digeser dengan blur kotak radius |shift| lalu diambang dekat 0 atau 1.
     */
    fun refine(mask: ByteArray, w: Int, h: Int, shift: Int, smooth: Int, feather: Float): ByteArray {
        var m = mask.copyOf()
        if (smooth > 0) {
            m = feather(m, w, h, 1f + smooth / 20f)
            // Kurva smoothstep 0,25..0,75 mengembalikan ketajaman tepi tanpa gerigi lama (sudut membulat).
            for (i in m.indices) {
                val t = (((m[i].toInt() and 0xFF) / 255f - 0.25f) / 0.5f).coerceIn(0f, 1f)
                m[i] = (t * t * (3f - 2f * t) * 255f).roundToInt().toByte()
            }
        }
        if (shift != 0) {
            val r = abs(shift)
            val tmp = ByteArray(m.size)
            boxH(m, tmp, w, h, r)
            boxV(tmp, m, w, h, r)
            val t = shiftThreshold(shift)
            for (i in m.indices) m[i] = ramp(m[i].toInt() and 0xFF, t).toByte()
        }
        if (feather > 0f) m = feather(m, w, h, feather)
        return m
    }

    /**
     * Alfa sadar warna di pita tepi: untuk piksel yang mask-nya di dekat batas, warna latar depan F dan
     * latar B diambil rata-rata di jendela sekitar (mask ≥ 240 / ≤ 15), lalu alfa = proyeksi (C−B)·(F−B)/|F−B|².
     * Bila F dan B terlalu mirip, mask asli dipakai. [strength] 0..1 = campuran dengan mask asli.
     */
    fun edgeAware(mask: ByteArray, px: IntArray, w: Int, h: Int, band: Int, strength: Float): ByteArray {
        val out = mask.copyOf()
        if (band <= 0 || strength <= 0f) return out
        val near = ByteArray(mask.size)
        val tmp = ByteArray(mask.size)
        boxH(mask, tmp, w, h, band)
        boxV(tmp, near, w, h, band)
        val win = band + 2
        val step = max(1, win / 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val nb = near[i].toInt() and 0xFF
                if (nb <= 6 || nb >= 249) continue
                val c = px[i]
                if ((c ushr 24) < 8) continue
                var fr = 0L; var fg = 0L; var fb = 0L; var fn = 0
                var br = 0L; var bg = 0L; var bb = 0L; var bn = 0
                var yy = max(0, y - win)
                while (yy <= min(h - 1, y + win)) {
                    var xx = max(0, x - win)
                    while (xx <= min(w - 1, x + win)) {
                        val j = yy * w + xx
                        val m = mask[j].toInt() and 0xFF
                        val p = px[j]
                        if (m >= 240) { fr += (p shr 16) and 0xFF; fg += (p shr 8) and 0xFF; fb += p and 0xFF; fn++ }
                        else if (m <= 15) { br += (p shr 16) and 0xFF; bg += (p shr 8) and 0xFF; bb += p and 0xFF; bn++ }
                        xx += step
                    }
                    yy += step
                }
                if (fn == 0 || bn == 0) continue
                val dR = fr.toFloat() / fn - br.toFloat() / bn
                val dG = fg.toFloat() / fn - bg.toFloat() / bn
                val dB = fb.toFloat() / fn - bb.toFloat() / bn
                val den = dR * dR + dG * dG + dB * dB
                if (den < 300f) continue
                val cR = ((c shr 16) and 0xFF) - br.toFloat() / bn
                val cG = ((c shr 8) and 0xFF) - bg.toFloat() / bn
                val cB = (c and 0xFF) - bb.toFloat() / bn
                val alpha = ((cR * dR + cG * dG + cB * dB) / den).coerceIn(0f, 1f) * 255f
                val orig = mask[i].toInt() and 0xFF
                out[i] = (orig + (alpha - orig) * strength.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** Ambil isi seleksi: alfa piksel × mask (ukuran sama). */
    fun extract(px: IntArray, mask: ByteArray): IntArray = IntArray(px.size) { i ->
        val m = mask[i].toInt() and 0xFF
        val p = px[i]
        if (m == 0) 0 else {
            val a = ((p ushr 24) * m + 127) / 255
            (a shl 24) or (p and 0xFFFFFF)
        }
    }

    /** Buang isi seleksi dari piksel (alfa × (1 − mask)), di tempat. */
    fun cut(px: IntArray, mask: ByteArray) {
        for (i in px.indices) {
            val m = mask[i].toInt() and 0xFF
            if (m == 0) continue
            val p = px[i]
            val a = ((p ushr 24) * (255 - m) + 127) / 255
            px[i] = (a shl 24) or (p and 0xFFFFFF)
        }
    }

    /** Kotak terkecil yang memuat piksel beralfa > [threshold]; EMPTY bila semua transparan. */
    fun alphaBounds(px: IntArray, w: Int, h: Int, threshold: Int = 0): CoRect {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > threshold) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    b = y
                }
            }
        }
        return if (r < 0) CoRect.EMPTY else CoRect(l, t, r + 1, b + 1)
    }

    /** Kotak terkecil yang memuat mask ≥ [min]. */
    fun maskBounds(mask: ByteArray, w: Int, h: Int, min: Int = 8): CoRect {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((mask[row + x].toInt() and 0xFF) >= min) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    b = y
                }
            }
        }
        return if (r < 0) CoRect.EMPTY else CoRect(l, t, r + 1, b + 1)
    }

    /**
     * Piksel overlay seleksi untuk wilayah [rc] (buffer [out] lokal rc.w×rc.h): isi berwarna [tint]
     * transparan sebanding mask, garis tepi (batas 50%) putus-putus hitam/putih agar terlihat di warna apa pun.
     */
    fun overlay(mask: ByteArray, w: Int, h: Int, rc: CoRect, tint: Int, out: IntArray) {
        val tr = tint and 0xFFFFFF
        for (y in rc.t until rc.b) {
            for (x in rc.l until rc.r) {
                val i = y * w + x
                val v = mask[i].toInt() and 0xFF
                val inside = v >= 128
                val edge = (x > 0 && ((mask[i - 1].toInt() and 0xFF) >= 128) != inside) ||
                    (x < w - 1 && ((mask[i + 1].toInt() and 0xFF) >= 128) != inside) ||
                    (y > 0 && ((mask[i - w].toInt() and 0xFF) >= 128) != inside) ||
                    (y < h - 1 && ((mask[i + w].toInt() and 0xFF) >= 128) != inside)
                out[(y - rc.t) * rc.w + (x - rc.l)] = when {
                    edge -> if (((x + y) / 4) % 2 == 0) 0xFF000000.toInt() else -1
                    v > 0 -> ((v * 110 / 255) shl 24) or tr
                    else -> 0
                }
            }
        }
    }
}

/**
 * Operasi kuas pada buffer ARGB lokal (wilayah [rc] dalam gambar w×h). [clip] = mask seleksi
 * gambar penuh (null = tanpa batas seleksi). Semua alfa tidak dipremultiplikasi.
 */
internal object CoPaint {
    inline fun forDab(rc: CoRect, cx: Float, cy: Float, rad: Float, hardness: Int, strength: Float, clip: ByteArray?, w: Int, op: (Int, Float) -> Unit) {
        for (y in rc.t until rc.b) {
            val dy = y + 0.5f - cy
            for (x in rc.l until rc.r) {
                val dx = x + 0.5f - cx
                var a = CoMask.brushAlpha(sqrt(dx * dx + dy * dy), rad, hardness) * strength
                if (a <= 0f) continue
                if (clip != null) a *= (clip[y * w + x].toInt() and 0xFF) / 255f
                if (a <= 0f) continue
                op((y - rc.t) * rc.w + (x - rc.l), a)
            }
        }
    }

    fun lerp(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * u)).roundToInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Komposit "source-over" warna [src] (alfa × [mul]) di atas [dst], tanpa premultiplikasi. */
    fun srcOver(dst: Int, src: Int, mul: Float): Int {
        val sa = ((src ushr 24) / 255f) * mul.coerceIn(0f, 1f)
        if (sa <= 0f) return dst
        val da = (dst ushr 24) / 255f
        val oa = sa + da * (1f - sa)
        if (oa <= 0f) return 0
        fun ch(s: Int): Int {
            val sc = (src shr s) and 0xFF
            val dc = (dst shr s) and 0xFF
            return ((sc * sa + dc * da * (1f - sa)) / oa).roundToInt().coerceIn(0, 255)
        }
        return ((oa * 255f).roundToInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Penghapus: alfa × (1 − a). */
    fun erase(buf: IntArray, rc: CoRect, cx: Float, cy: Float, rad: Float, hardness: Int, strength: Float, clip: ByteArray?, w: Int) =
        forDab(rc, cx, cy, rad, hardness, strength, clip, w) { i, a ->
            val p = buf[i]
            val na = ((p ushr 24) * (1f - a)).roundToInt().coerceIn(0, 255)
            buf[i] = (na shl 24) or (p and 0xFFFFFF)
        }

    /** Pulihkan: dekati piksel sumber asli [src] (buffer lokal sama ukuran). */
    fun restore(buf: IntArray, src: IntArray, rc: CoRect, cx: Float, cy: Float, rad: Float, hardness: Int, strength: Float, clip: ByteArray?, w: Int) =
        forDab(rc, cx, cy, rad, hardness, strength, clip, w) { i, a -> buf[i] = lerp(buf[i], src[i], a) }

    /** Kuas warna di atas layer. */
    fun paint(buf: IntArray, color: Int, rc: CoRect, cx: Float, cy: Float, rad: Float, hardness: Int, strength: Float, clip: ByteArray?, w: Int) =
        forDab(rc, cx, cy, rad, hardness, strength, clip, w) { i, a -> buf[i] = srcOver(buf[i], color, a) }

    /** Klon: [sample] = piksel sumber yang sudah digeser offset (buffer lokal sama ukuran). */
    fun clone(buf: IntArray, sample: IntArray, rc: CoRect, cx: Float, cy: Float, rad: Float, hardness: Int, strength: Float, clip: ByteArray?, w: Int) =
        forDab(rc, cx, cy, rad, hardness, strength, clip, w) { i, a -> buf[i] = srcOver(buf[i], sample[i], a) }

    /** Titik-titik sapuan dari (x0,y0) ke (x1,y1) berjarak [spacing], tanpa titik awal. */
    fun strokeSteps(x0: Float, y0: Float, x1: Float, y1: Float, spacing: Float): List<Pair<Float, Float>> {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        val sp = spacing.coerceAtLeast(0.5f)
        if (len < sp) return emptyList()
        val n = (len / sp).toInt()
        return (1..n).map { k -> val t = k * sp / len; (x0 + dx * t) to (y0 + dy * t) }
    }
}

/** Ubin riwayat: sapuan hanya menyimpan ubin yang tersentuh (sebelum & sesudah). */
internal object CoTiles {
    const val SIZE = 128

    fun cols(w: Int): Int = (w + SIZE - 1) / SIZE
    fun rows(h: Int): Int = (h + SIZE - 1) / SIZE

    fun tilesFor(rc: CoRect, w: Int, h: Int): List<Int> {
        val c = rc.clamp(w, h)
        if (c.isEmpty) return emptyList()
        val cols = cols(w)
        val out = ArrayList<Int>()
        for (ty in c.t / SIZE..(c.b - 1) / SIZE) for (tx in c.l / SIZE..(c.r - 1) / SIZE) out.add(ty * cols + tx)
        return out
    }

    fun rectOf(index: Int, w: Int, h: Int): CoRect {
        val cols = cols(w)
        val tx = index % cols
        val ty = index / cols
        return CoRect(tx * SIZE, ty * SIZE, min(w, (tx + 1) * SIZE), min(h, (ty + 1) * SIZE))
    }
}

internal object CoPack {
    fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_SPEED)
        d.setInput(data)
        d.finish()
        val bos = ByteArrayOutputStream(max(64, data.size / 4))
        val buf = ByteArray(16 * 1024)
        while (!d.finished()) {
            val n = d.deflate(buf)
            bos.write(buf, 0, n)
        }
        d.end()
        return bos.toByteArray()
    }

    fun inflate(data: ByteArray, size: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArray(size)
        var off = 0
        while (off < size && !inf.finished()) {
            val n = inf.inflate(out, off, size - off)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            off += n
        }
        inf.end()
        return out
    }

    fun intsToBytes(px: IntArray): ByteArray {
        val out = ByteArray(px.size * 4)
        var j = 0
        for (p in px) {
            out[j++] = (p ushr 24).toByte(); out[j++] = (p ushr 16).toByte(); out[j++] = (p ushr 8).toByte(); out[j++] = p.toByte()
        }
        return out
    }

    fun bytesToInts(b: ByteArray, count: Int): IntArray {
        val out = IntArray(count)
        var j = 0
        for (i in 0 until count) {
            out[i] = ((b[j].toInt() and 0xFF) shl 24) or ((b[j + 1].toInt() and 0xFF) shl 16) or ((b[j + 2].toInt() and 0xFF) shl 8) or (b[j + 3].toInt() and 0xFF)
            j += 4
        }
        return out
    }

    fun packInts(px: IntArray): ByteArray = deflate(intsToBytes(px))
    fun unpackInts(z: ByteArray, count: Int): IntArray = bytesToInts(inflate(z, count * 4), count)
}

/**
 * Riwayat undo/redo terbatas: maks. [maxCount] langkah dan [maxBytes] byte (ukuran dihitung
 * [sizeOf] saat dipangkas, jadi entri yang dikompres belakangan ikut menyusut). Langkah tertua dibuang dulu.
 */
internal class CoHistory<T>(private val maxCount: Int, private val maxBytes: Long, private val sizeOf: (T) -> Long) {
    private val undo = ArrayList<T>()
    private val redo = ArrayList<T>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val undoCount: Int get() = undo.size
    val redoCount: Int get() = redo.size

    fun push(e: T) {
        undo.add(e)
        redo.clear()
        trim()
    }

    fun undo(): T? {
        val e = undo.removeLastOrNull() ?: return null
        redo.add(e)
        return e
    }

    fun redo(): T? {
        val e = redo.removeLastOrNull() ?: return null
        undo.add(e)
        return e
    }

    fun clear() { undo.clear(); redo.clear() }

    fun totalBytes(): Long = undo.sumOf(sizeOf) + redo.sumOf(sizeOf)

    fun trim() {
        while (undo.size > maxCount) undo.removeAt(0)
        while (undo.size > 1 && totalBytes() > maxBytes) undo.removeAt(0)
        // Satu langkah raksasa (mis. layer penuh 3000 px) tetap disimpan agar undo terakhir selalu bisa.
    }
}

/** Titik sumber klon: offset dihitung saat sapuan pertama setelah sumber dipasang, lalu tetap (sejajar). */
internal class CoClone {
    var srcX = 0f; private set
    var srcY = 0f; private set
    var hasSource = false; private set
    var dx = 0f; private set
    var dy = 0f; private set
    private var aligned = false

    fun setSource(x: Float, y: Float) {
        srcX = x; srcY = y
        hasSource = true
        aligned = false
    }

    /** Awal sapuan di (x,y). @return false bila sumber belum dipasang. */
    fun begin(x: Float, y: Float): Boolean {
        if (!hasSource) return false
        if (!aligned) {
            dx = srcX - x
            dy = srcY - y
            aligned = true
        }
        return true
    }

    fun clear() { hasSource = false; aligned = false }
}

internal data class CoLayerMeta(
    val id: String,
    val name: String,
    val file: String,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Float = 1f
)

internal data class CoManifest(
    val version: Int,
    val name: String,
    /** Nama file foto sumber di folder proyek. */
    val source: String,
    val w: Int,
    val h: Int,
    /** Urutan bawah → atas. */
    val layers: List<CoLayerMeta>,
    val active: String?,
    val modified: Long
)

internal object CoManifestCodec {
    const val VERSION = 1

    fun encode(m: CoManifest): Map<String, Any?> = linkedMapOf(
        "type" to "gitls-cutout",
        "version" to VERSION,
        "name" to m.name,
        "source" to m.source,
        "w" to m.w,
        "h" to m.h,
        "active" to m.active,
        "modified" to m.modified,
        "layers" to m.layers.map { l ->
            linkedMapOf<String, Any?>("id" to l.id, "name" to l.name, "file" to l.file, "visible" to l.visible, "locked" to l.locked, "opacity" to l.opacity.toDouble())
        }
    )

    private fun num(v: Any?): Number? = v as? Number ?: (v as? String)?.toDoubleOrNull()

    /** Toleran: field hilang memakai bawaan; layer tanpa file dibuang. Null bila bukan manifest valid. */
    fun decode(t: Map<String, Any?>): CoManifest? {
        val w = num(t["w"])?.toInt() ?: return null
        val h = num(t["h"])?.toInt() ?: return null
        if (w <= 0 || h <= 0) return null
        val layers = (t["layers"] as? List<*>).orEmpty().mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            val file = m["file"] as? String ?: return@mapNotNull null
            if (file.isBlank() || file.contains('/') || file.contains("..")) return@mapNotNull null
            CoLayerMeta(
                id = (m["id"] as? String)?.ifBlank { null } ?: file.substringBeforeLast('.'),
                name = (m["name"] as? String) ?: "Layer",
                file = file,
                visible = m["visible"] as? Boolean ?: true,
                locked = m["locked"] as? Boolean ?: false,
                opacity = (num(m["opacity"])?.toFloat() ?: 1f).coerceIn(0f, 1f)
            )
        }
        val src = (t["source"] as? String).orEmpty()
        return CoManifest(
            version = num(t["version"])?.toInt() ?: 1,
            name = (t["name"] as? String)?.ifBlank { null } ?: "Potongan",
            source = if (src.contains('/') || src.contains("..")) "" else src,
            w = w, h = h,
            layers = layers,
            active = t["active"] as? String,
            modified = num(t["modified"])?.toLong() ?: 0L
        )
    }
}

internal object CoNames {
    val PRESETS = listOf("Rambut", "Kepala", "Mata", "Mulut", "Baju", "Badan")

    /** "Mata" → "Mata 2", "Mata 3"… bila nama sudah dipakai. */
    fun unique(base: String, existing: Collection<String>): String {
        val b = base.trim().ifBlank { "Layer" }.take(40)
        if (b !in existing) return b
        var n = 2
        while ("$b $n" in existing) n++
        return "$b $n"
    }
}
