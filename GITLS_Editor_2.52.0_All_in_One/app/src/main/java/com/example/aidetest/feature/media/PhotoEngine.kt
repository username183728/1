package com.example.aidetest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Mesin Photo Editor non-destruktif. Semua parameter disimpan relatif (0..1 / ternormalisasi)
 * sehingga pratinjau (≤1080 px) dan ekspor resolusi penuh memberi hasil yang sama.
 * Urutan: heal (ruang sumber) → geometri (orientasi, perspektif, luruskan, crop)
 *         → warna (WB, exposure, highlight/shadow, kontras+kurva, saturasi/vibrance, HSL, preset)
 *         → detail (blur, blur latar AI, denoise, clarity, sharpen, vignette, grain) → layer.
 * Bagian piksel murni (IntArray) tidak memakai API Android agar bisa diuji di JVM.
 */

internal typealias CurvePts = List<Pair<Float, Float>>

internal val CURVE_IDENTITY: CurvePts = listOf(0f to 0f, 1f to 1f)

internal data class PeAdjust(
    val exposure: Float = 0f,     // EV, -2..2
    val contrast: Float = 0f,     // -1..1
    val highlights: Float = 0f,   // -1..1
    val shadows: Float = 0f,      // -1..1
    val temperature: Float = 0f,  // -1..1 (positif = hangat)
    val tint: Float = 0f,         // -1..1 (positif = magenta)
    val saturation: Float = 0f,   // -1..1
    val vibrance: Float = 0f,     // -1..1
    val curveM: CurvePts = CURVE_IDENTITY,
    val curveR: CurvePts = CURVE_IDENTITY,
    val curveG: CurvePts = CURVE_IDENTITY,
    val curveB: CurvePts = CURVE_IDENTITY,
    val hslHue: List<Float> = List(8) { 0f },   // -1..1 → ±30°
    val hslSat: List<Float> = List(8) { 0f },   // -1..1
    val hslLum: List<Float> = List(8) { 0f },   // -1..1
    val preset: String = "asli",
    val presetAmount: Float = 1f,  // 0..1
    val sharpen: Float = 0f,       // 0..1
    val denoise: Float = 0f,       // 0..1
    val clarity: Float = 0f,       // -1..1
    val vignette: Float = 0f,      // -1..1 (negatif = vignette putih)
    val grain: Float = 0f,         // 0..1
    val blur: Float = 0f,          // 0..1
    val bgBlur: Float = 0f         // 0..1 (butuh mask AI)
) {
    fun colorIsIdentity(): Boolean = exposure == 0f && contrast == 0f && highlights == 0f && shadows == 0f &&
        temperature == 0f && tint == 0f && saturation == 0f && vibrance == 0f &&
        PeCurves.isIdentity(curveM) && PeCurves.isIdentity(curveR) && PeCurves.isIdentity(curveG) && PeCurves.isIdentity(curveB) &&
        hslHue.all { it == 0f } && hslSat.all { it == 0f } && hslLum.all { it == 0f } &&
        (preset == "asli" || presetAmount <= 0f)

    fun detailIsIdentity(): Boolean = sharpen == 0f && denoise == 0f && clarity == 0f && vignette == 0f && grain == 0f && blur == 0f && bgBlur == 0f
}

internal data class PeGeom(
    val rot90: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val straighten: Float = 0f,   // derajat, -45..45
    val perspV: Float = 0f,       // -1..1
    val perspH: Float = 0f,       // -1..1
    val cl: Float = 0f, val ct: Float = 0f, val cr: Float = 1f, val cb: Float = 1f,
    val ratio: String = "bebas"
) {
    fun isIdentity() = rot90 % 4 == 0 && !flipH && !flipV && straighten == 0f && perspV == 0f && perspH == 0f &&
        cl == 0f && ct == 0f && cr == 1f && cb == 1f
}

/** Satu sapuan heal. Titik ternormalisasi ke lebar/tinggi sumber; radius & offset ke sisi terpanjang. */
internal data class PeHeal(val pts: List<Float>, val radius: Float, val offX: Float, val offY: Float)

internal data class PeLayer(
    val id: Long,
    val type: String,              // text | sticker | image
    val text: String = "",
    val font: String = "sans",
    val color: Int = -1,
    val size: Float = 0.08f,       // tinggi huruf (teks/stiker) atau lebar (gambar) relatif lebar output
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val blend: String = "normal",
    val outline: Float = 0f,       // 0..0.25 × ukuran huruf
    val outlineColor: Int = 0xFF000000.toInt(),
    val shadow: Float = 0f,        // 0..1
    val shadowColor: Int = 0xB4000000.toInt(),
    val spacing: Float = 0f,       // letter spacing (em)
    val align: String = "center",
    val icon: String = "",         // stiker MDI
    val bitmap: Bitmap? = null     // layer gambar (dibagi antar snapshot, tidak diubah)
)

internal data class PeEdit(
    val adjust: PeAdjust = PeAdjust(),
    val geom: PeGeom = PeGeom(),
    val heals: List<PeHeal> = emptyList(),
    val layers: List<PeLayer> = emptyList()
)

// ---------------------------------------------------------------- curves

internal object PeCurves {
    fun isIdentity(p: CurvePts): Boolean = p.size == 2 && p[0].first == 0f && p[0].second == 0f && p[1].first == 1f && p[1].second == 1f

    /** Spline kubik monoton (Fritsch–Carlson) → LUT [size] nilai 0..1. */
    fun lut(points: CurvePts, size: Int = 1024): FloatArray {
        val pts = points.sortedBy { it.first }.fold(ArrayList<Pair<Float, Float>>()) { acc, p ->
            if (acc.isEmpty() || p.first - acc.last().first > 1e-4f) acc.add(p) else acc[acc.size - 1] = p
            acc
        }
        val out = FloatArray(size)
        if (pts.size < 2) { for (i in 0 until size) out[i] = i / (size - 1f); return out }
        val n = pts.size
        val xs = FloatArray(n) { pts[it].first }
        val ys = FloatArray(n) { pts[it].second }
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (k in 1 until n - 1) m[k] = if (d[k - 1] * d[k] <= 0f) 0f else (d[k - 1] + d[k]) / 2f
        for (k in 0 until n - 1) {
            if (d[k] == 0f) { m[k] = 0f; m[k + 1] = 0f; continue }
            val a = m[k] / d[k]
            val b = m[k + 1] / d[k]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[k] = t * a * d[k]
                m[k + 1] = t * b * d[k]
            }
        }
        var seg = 0
        for (i in 0 until size) {
            val x = i / (size - 1f)
            val y = when {
                x <= xs[0] -> ys[0]
                x >= xs[n - 1] -> ys[n - 1]
                else -> {
                    while (seg < n - 2 && x > xs[seg + 1]) seg++
                    val h = xs[seg + 1] - xs[seg]
                    val t = (x - xs[seg]) / h
                    val t2 = t * t
                    val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * ys[seg] + (t3 - 2 * t2 + t) * h * m[seg] +
                        (-2 * t3 + 3 * t2) * ys[seg + 1] + (t3 - t2) * h * m[seg + 1]
                }
            }
            out[i] = y.coerceIn(0f, 1f)
        }
        return out
    }
}

// ---------------------------------------------------------------- presets

internal class PeLook(
    val bw: Boolean = false,
    val m: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
    val off: FloatArray = floatArrayOf(0f, 0f, 0f),
    val contrast: Float = 0f,
    val fade: Float = 0f,
    val sat: Float = 0f,
    val shadowTint: FloatArray = floatArrayOf(0f, 0f, 0f),
    val highTint: FloatArray = floatArrayOf(0f, 0f, 0f)
)

internal object PePresets {
    /** (kunci, label) dalam urutan tampil. */
    val list: List<Pair<String, String>> = listOf(
        "asli" to "Asli", "jernih" to "Jernih", "hangat" to "Hangat", "sejuk" to "Sejuk",
        "film" to "Film", "pudar" to "Pudar", "drama" to "Drama", "sinema" to "Sinema",
        "vintage" to "Vintage", "matte" to "Matte", "bw" to "Hitam Putih", "noir" to "Noir", "sepia" to "Sepia"
    )

    fun look(key: String): PeLook? = when (key) {
        "jernih" -> PeLook(contrast = 0.18f, sat = 0.28f)
        "hangat" -> PeLook(m = floatArrayOf(1.06f, 0f, 0f, 0f, 1.01f, 0f, 0f, 0f, 0.9f), off = floatArrayOf(0.02f, 0.01f, -0.01f), sat = 0.06f)
        "sejuk" -> PeLook(m = floatArrayOf(0.93f, 0f, 0f, 0f, 1.0f, 0f, 0f, 0f, 1.08f), off = floatArrayOf(-0.01f, 0.0f, 0.02f))
        "film" -> PeLook(contrast = 0.1f, fade = 0.07f, sat = -0.12f, shadowTint = floatArrayOf(-0.02f, 0.02f, 0.04f), highTint = floatArrayOf(0.04f, 0.02f, -0.03f))
        "pudar" -> PeLook(contrast = -0.18f, fade = 0.14f, sat = -0.22f)
        "drama" -> PeLook(contrast = 0.38f, sat = -0.18f, fade = 0f)
        "sinema" -> PeLook(contrast = 0.16f, sat = 0.05f, shadowTint = floatArrayOf(-0.04f, 0.02f, 0.07f), highTint = floatArrayOf(0.07f, 0.02f, -0.05f))
        "vintage" -> PeLook(m = floatArrayOf(1.0f, 0.06f, 0f, 0.04f, 0.94f, 0.02f, 0f, 0.06f, 0.82f), contrast = -0.06f, fade = 0.1f, sat = -0.2f, highTint = floatArrayOf(0.04f, 0.02f, -0.02f))
        "matte" -> PeLook(contrast = -0.08f, fade = 0.12f, sat = -0.06f)
        "bw" -> PeLook(bw = true, contrast = 0.08f)
        "noir" -> PeLook(bw = true, contrast = 0.5f)
        "sepia" -> PeLook(bw = true, m = floatArrayOf(1.07f, 0f, 0f, 0f, 0.93f, 0f, 0f, 0f, 0.72f), off = floatArrayOf(0.03f, 0.01f, 0f), fade = 0.04f)
        else -> null
    }
}

// ---------------------------------------------------------------- color

internal object PeColor {
    // Pusat rentang HSL: merah, oranye, kuning, hijau, aqua, biru, ungu, magenta.
    val HUE_CENTERS = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 280f, 320f)
    val HUE_LABELS = listOf("Merah", "Oranye", "Kuning", "Hijau", "Aqua", "Biru", "Ungu", "Magenta")
    val HUE_SWATCH = intArrayOf(
        0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
        0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFD81B60.toInt()
    )

    private fun contrastFn(x: Float, c: Float): Float {
        if (c == 0f) return x
        return if (c > 0f) {
            val s = x * x * (3f - 2f * x)
            (x + (s - x) * c * 1.6f).coerceIn(0f, 1f)
        } else {
            val flat = 0.5f + (x - 0.5f) * 0.4f
            x + (flat - x) * (-c)
        }
    }

    /** Bangun LUT gabungan kontras → kurva master → kurva kanal (1024 entri per kanal). */
    fun buildLuts(adj: PeAdjust): Array<FloatArray> {
        val master = if (PeCurves.isIdentity(adj.curveM)) null else PeCurves.lut(adj.curveM)
        val ch = arrayOf(adj.curveR, adj.curveG, adj.curveB).map { if (PeCurves.isIdentity(it)) null else PeCurves.lut(it) }
        return Array(3) { c ->
            FloatArray(1024) { i ->
                var x = contrastFn(i / 1023f, adj.contrast)
                if (master != null) x = master[(x * 1023f + 0.5f).toInt()]
                val cl = ch[c]
                if (cl != null) x = cl[(x * 1023f + 0.5f).toInt()]
                x
            }
        }
    }

    private const val LUT_N = 35

    /**
     * Terapkan seluruh penyesuaian warna in-place pada ARGB.
     * Seluruh rantai warna adalah fungsi murni dari (r,g,b), jadi untuk gambar besar dipakai
     * LUT 3D 35³ + interpolasi trilinear (seperti editor foto profesional) — beberapa kali lebih cepat.
     */
    fun apply(px: IntArray, adj: PeAdjust) {
        if (adj.colorIsIdentity()) return
        if (px.size < 120_000) applyDirect(px, adj) else applyLut3d(px, buildLut3d(adj))
    }

    fun buildLut3d(adj: PeAdjust): IntArray {
        val n = LUT_N
        val grid = IntArray(n * n * n)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val rv = (r * 255f / (n - 1) + 0.5f).toInt()
            val gv = (g * 255f / (n - 1) + 0.5f).toInt()
            val bv = (b * 255f / (n - 1) + 0.5f).toInt()
            grid[i++] = (0xFF shl 24) or (rv shl 16) or (gv shl 8) or bv
        }
        applyDirect(grid, adj)
        return grid
    }

    fun applyLut3d(px: IntArray, lut: IntArray) {
        val n = LUT_N
        val k = (n - 1) / 255f
        val nn = n * n
        for (i in px.indices) {
            val c = px[i]
            val fr = ((c shr 16) and 0xFF) * k
            val fg = ((c shr 8) and 0xFF) * k
            val fb = (c and 0xFF) * k
            val r0 = min(fr.toInt(), n - 2); val g0 = min(fg.toInt(), n - 2); val b0 = min(fb.toInt(), n - 2)
            val tr = fr - r0; val tg = fg - g0; val tb = fb - b0
            val base = b0 * nn + g0 * n + r0
            val c000 = lut[base]; val c100 = lut[base + 1]
            val c010 = lut[base + n]; val c110 = lut[base + n + 1]
            val c001 = lut[base + nn]; val c101 = lut[base + nn + 1]
            val c011 = lut[base + nn + n]; val c111 = lut[base + nn + n + 1]
            var out = c and 0xFF000000.toInt()
            var sh = 16
            while (sh >= 0) {
                val v00 = ((c000 shr sh) and 0xFF) + (((c100 shr sh) and 0xFF) - ((c000 shr sh) and 0xFF)) * tr
                val v10 = ((c010 shr sh) and 0xFF) + (((c110 shr sh) and 0xFF) - ((c010 shr sh) and 0xFF)) * tr
                val v01 = ((c001 shr sh) and 0xFF) + (((c101 shr sh) and 0xFF) - ((c001 shr sh) and 0xFF)) * tr
                val v11 = ((c011 shr sh) and 0xFF) + (((c111 shr sh) and 0xFF) - ((c011 shr sh) and 0xFF)) * tr
                val v0 = v00 + (v10 - v00) * tg
                val v1 = v01 + (v11 - v01) * tg
                val v = (v0 + (v1 - v0) * tb + 0.5f).toInt().coerceIn(0, 255)
                out = out or (v shl sh)
                sh -= 8
            }
            px[i] = out
        }
    }

    /** Evaluasi langsung per piksel (acuan; juga dipakai membangun LUT 3D). */
    fun applyDirect(px: IntArray, adj: PeAdjust) {
        val luts = buildLuts(adj)
        val lr = luts[0]; val lg = luts[1]; val lb = luts[2]
        val t = adj.temperature
        val ti = adj.tint
        val gainR = (1f + 0.22f * t + 0.08f * ti)
        val gainG = (1f + 0.03f * t - 0.18f * ti)
        val gainB = (1f - 0.22f * t + 0.08f * ti)
        val expo = 2f.pow(adj.exposure)
        val sh = adj.shadows
        val hi = adj.highlights
        val satF = 1f + adj.saturation
        val vib = adj.vibrance
        val hslOn = adj.hslHue.any { it != 0f } || adj.hslSat.any { it != 0f } || adj.hslLum.any { it != 0f }
        val hh = adj.hslHue.toFloatArray(); val hs = adj.hslSat.toFloatArray(); val hl = adj.hslLum.toFloatArray()
        val look = if (adj.presetAmount > 0f) PePresets.look(adj.preset) else null
        val la = adj.presetAmount.coerceIn(0f, 1f)
        val tmp = FloatArray(3)
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            var r = ((c shr 16) and 0xFF) / 255f * gainR * expo
            var g = ((c shr 8) and 0xFF) / 255f * gainG * expo
            var b = (c and 0xFF) / 255f * gainB * expo
            if (sh != 0f || hi != 0f) {
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                var nl = l
                if (sh != 0f) {
                    val w = (1f - l / 0.6f).coerceIn(0f, 1f).let { it * it }
                    nl += if (sh > 0f) sh * 0.45f * w * (1f - l).coerceAtLeast(0f) else sh * 0.45f * w * l
                }
                if (hi != 0f) {
                    val w = ((l - 0.4f) / 0.6f).coerceIn(0f, 1f).let { it * it }
                    nl += if (hi > 0f) hi * 0.35f * w * (1f - l).coerceAtLeast(0f) else hi * 0.5f * w * l
                }
                // Mencerahkan = aditif (warna gelap tidak meledak jenuh); menggelapkan = multiplikatif.
                if (nl >= l) { val d = nl - l; r += d; g += d; b += d }
                else if (l > 1e-5f) { val k = nl / l; r *= k; g *= k; b *= k }
            }
            r = lr[(r.coerceIn(0f, 1f) * 1023f + 0.5f).toInt()]
            g = lg[(g.coerceIn(0f, 1f) * 1023f + 0.5f).toInt()]
            b = lb[(b.coerceIn(0f, 1f) * 1023f + 0.5f).toInt()]
            if (satF != 1f || vib != 0f) {
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                val f = satF * (1f + vib * (1f - (mx - mn)).coerceIn(0f, 1f))
                r = l + (r - l) * f; g = l + (g - l) * f; b = l + (b - l) * f
            }
            if (hslOn) {
                tmp[0] = r.coerceIn(0f, 1f); tmp[1] = g.coerceIn(0f, 1f); tmp[2] = b.coerceIn(0f, 1f)
                hslAdjust(tmp, hh, hs, hl)
                r = tmp[0]; g = tmp[1]; b = tmp[2]
            }
            if (look != null) {
                tmp[0] = r; tmp[1] = g; tmp[2] = b
                applyLook(tmp, look)
                r += (tmp[0] - r) * la; g += (tmp[1] - g) * la; b += (tmp[2] - b) * la
            }
            val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            px[i] = (a shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
    }

    fun applyLook(v: FloatArray, lk: PeLook) {
        var r = v[0]; var g = v[1]; var b = v[2]
        if (lk.bw) { val y = 0.30f * r + 0.59f * g + 0.11f * b; r = y; g = y; b = y }
        val m = lk.m
        val nr = m[0] * r + m[1] * g + m[2] * b + lk.off[0]
        val ng = m[3] * r + m[4] * g + m[5] * b + lk.off[1]
        val nb = m[6] * r + m[7] * g + m[8] * b + lk.off[2]
        r = nr; g = ng; b = nb
        if (lk.sat != 0f) {
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val f = 1f + lk.sat
            r = l + (r - l) * f; g = l + (g - l) * f; b = l + (b - l) * f
        }
        if (lk.contrast != 0f) {
            r = contrastFn(r.coerceIn(0f, 1f), lk.contrast)
            g = contrastFn(g.coerceIn(0f, 1f), lk.contrast)
            b = contrastFn(b.coerceIn(0f, 1f), lk.contrast)
        }
        if (lk.shadowTint[0] != 0f || lk.shadowTint[1] != 0f || lk.shadowTint[2] != 0f || lk.highTint[0] != 0f || lk.highTint[1] != 0f || lk.highTint[2] != 0f) {
            val l = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
            val sw = 1f - l
            r += lk.shadowTint[0] * sw + lk.highTint[0] * l
            g += lk.shadowTint[1] * sw + lk.highTint[1] * l
            b += lk.shadowTint[2] * sw + lk.highTint[2] * l
        }
        if (lk.fade > 0f) {
            r = lk.fade + r * (1f - lk.fade)
            g = lk.fade + g * (1f - lk.fade)
            b = lk.fade + b * (1f - lk.fade)
        }
        v[0] = r; v[1] = g; v[2] = b
    }

    /** Bobot rentang hue (interpolasi segitiga antar pusat bertetangga). */
    fun hueWeights(h: Float, out: FloatArray) {
        out.fill(0f)
        val n = HUE_CENTERS.size
        for (k in 0 until n) {
            val c0 = HUE_CENTERS[k]
            val c1 = if (k == n - 1) HUE_CENTERS[0] + 360f else HUE_CENTERS[k + 1]
            var hh = h
            if (k == n - 1 && hh < c0) hh += 360f
            if (hh >= c0 && hh < c1) {
                val t = (hh - c0) / (c1 - c0)
                out[k] = 1f - t
                out[(k + 1) % n] = t
                return
            }
        }
        out[0] = 1f
    }

    // ThreadLocal.withInitial butuh API 26; minSdk 24 → subclass biasa.
    private val wBuf = object : ThreadLocal<FloatArray>() { override fun initialValue(): FloatArray = FloatArray(8) }

    fun hslAdjust(v: FloatArray, hue: FloatArray, sat: FloatArray, lum: FloatArray) {
        val r = v[0]; val g = v[1]; val b = v[2]
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d < 1e-4f) return
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        var h = when (mx) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        val w = wBuf.get()!!
        hueWeights(h, w)
        var dh = 0f; var ds = 0f; var dl = 0f
        for (k in 0 until 8) if (w[k] > 0f) { dh += w[k] * hue[k]; ds += w[k] * sat[k]; dl += w[k] * lum[k] }
        h = (h + dh * 30f + 360f) % 360f
        val ns = (s * (1f + ds)).coerceIn(0f, 1f)
        // Luminansi hanya memengaruhi piksel berwarna (sebanding saturasi) agar abu-abu aman.
        val nl = (l + dl * 0.3f * s * (if (dl > 0) (1f - l) else l) * 2f).coerceIn(0f, 1f)
        hslToRgb(h / 360f, ns, nl, v)
    }

    fun hslToRgb(h: Float, s: Float, l: Float, out: FloatArray) {
        if (s <= 0f) { out[0] = l; out[1] = l; out[2] = l; return }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        out[0] = hue2rgb(p, q, h + 1f / 3f)
        out[1] = hue2rgb(p, q, h)
        out[2] = hue2rgb(p, q, h - 1f / 3f)
    }

    private fun hue2rgb(p: Float, q: Float, tt: Float): Float {
        var t = tt
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    /** Histogram luminansi (atau kanal 0=R,1=G,2=B) 256 bin, dinormalisasi 0..1. */
    fun histogram(px: IntArray, channel: Int = -1): FloatArray {
        val bins = IntArray(256)
        val step = max(1, px.size / 200_000)
        var i = 0
        while (i < px.size) {
            val c = px[i]
            val v = when (channel) {
                0 -> (c shr 16) and 0xFF
                1 -> (c shr 8) and 0xFF
                2 -> c and 0xFF
                else -> ((((c shr 16) and 0xFF) * 54 + ((c shr 8) and 0xFF) * 183 + (c and 0xFF) * 19) shr 8)
            }
            bins[v]++
            i += step
        }
        val peak = bins.sortedArray()[250].coerceAtLeast(1) // abaikan puncak ekstrem
        return FloatArray(256) { (bins[it].toFloat() / peak).coerceAtMost(1f) }
    }
}

// ---------------------------------------------------------------- detail

internal object PeDetail {
    /** Box blur RGB O(n) terpisah; alpha dipertahankan. */
    fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int, out: IntArray = IntArray(src.size)): IntArray {
        if (radius <= 0) { System.arraycopy(src, 0, out, 0, src.size); return out }
        val tmp = IntArray(src.size)
        val win = radius * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (k in -radius..radius) { val c = src[row + k.coerceIn(0, w - 1)]; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF }
            for (x in 0 until w) {
                tmp[row + x] = (src[row + x] and 0xFF000000.toInt()) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
                val ca = src[row + (x + radius + 1).coerceAtMost(w - 1)]
                val cs = src[row + (x - radius).coerceAtLeast(0)]
                sr += ((ca shr 16) and 0xFF) - ((cs shr 16) and 0xFF)
                sg += ((ca shr 8) and 0xFF) - ((cs shr 8) and 0xFF)
                sb += (ca and 0xFF) - (cs and 0xFF)
            }
        }
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (k in -radius..radius) { val c = tmp[k.coerceIn(0, h - 1) * w + x]; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF }
            for (y in 0 until h) {
                val i = y * w + x
                out[i] = (src[i] and 0xFF000000.toInt()) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
                val ca = tmp[(y + radius + 1).coerceAtMost(h - 1) * w + x]
                val cs = tmp[(y - radius).coerceAtLeast(0) * w + x]
                sr += ((ca shr 16) and 0xFF) - ((cs shr 16) and 0xFF)
                sg += ((ca shr 8) and 0xFF) - ((cs shr 8) and 0xFF)
                sb += (ca and 0xFF) - (cs and 0xFF)
            }
        }
        return out
    }

    /** Mendekati Gaussian dengan 3× box blur. */
    fun gaussian(src: IntArray, w: Int, h: Int, radius: Float): IntArray {
        val r = max(1, (radius / 1.7f).toInt())
        var a = boxBlur(src, w, h, r)
        a = boxBlur(a, w, h, r)
        return boxBlur(a, w, h, r)
    }

    private inline fun mix(c0: Int, c1: Int, t: Float): Int {
        val r = (((c0 shr 16) and 0xFF) + ((((c1 shr 16) and 0xFF) - ((c0 shr 16) and 0xFF)) * t)).toInt().coerceIn(0, 255)
        val g = (((c0 shr 8) and 0xFF) + ((((c1 shr 8) and 0xFF) - ((c0 shr 8) and 0xFF)) * t)).toInt().coerceIn(0, 255)
        val b = ((c0 and 0xFF) + (((c1 and 0xFF) - (c0 and 0xFF)) * t)).toInt().coerceIn(0, 255)
        return (c0 and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    private fun lum(c: Int): Int = (((c shr 16) and 0xFF) * 54 + ((c shr 8) and 0xFF) * 183 + (c and 0xFF) * 19) shr 8

    /** Hash deterministik → 0..1 (grain sama di pratinjau & ekspor, relatif ukuran gambar). */
    fun hash(x: Int, y: Int): Float {
        var n = x * 374761393 + y * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xFFFF) / 65535f
    }

    /**
     * Terapkan detail in-place. [mask] (0..255 subjek) dipakai untuk blur latar; null = dilewati.
     * Semua radius relatif ke sisi terpanjang sehingga pratinjau = ekspor.
     */
    fun apply(px: IntArray, w: Int, h: Int, adj: PeAdjust, mask: ByteArray?) {
        if (adj.detailIsIdentity()) return
        val long = max(w, h).toFloat()
        if (adj.blur > 0f) {
            val b = gaussian(px, w, h, adj.blur * long * 0.025f)
            System.arraycopy(b, 0, px, 0, px.size)
        }
        if (adj.bgBlur > 0f && mask != null && mask.size == px.size) {
            val b = gaussian(px, w, h, adj.bgBlur * long * 0.03f)
            for (i in px.indices) {
                val m = (mask[i].toInt() and 0xFF) / 255f
                px[i] = mix(b[i], px[i], m)
            }
        }
        if (adj.denoise > 0f) {
            val b = gaussian(px, w, h, 1.5f + adj.denoise * long * 0.0025f)
            val k = adj.denoise
            for (i in px.indices) {
                val e = abs(lum(px[i]) - lum(b[i])) / 255f
                val edge = ((e - 0.02f) / 0.12f).coerceIn(0f, 1f)
                px[i] = mix(px[i], b[i], k * (1f - edge))
            }
        }
        if (adj.clarity != 0f) {
            val b = gaussian(px, w, h, long * 0.012f)
            val k = adj.clarity * 0.9f
            for (i in px.indices) {
                val l = lum(px[i]) / 255f
                val mid = 1f - abs(l - 0.5f) * 2f
                px[i] = addScaledDiff(px[i], b[i], k * (0.35f + 0.65f * mid))
            }
        }
        if (adj.sharpen > 0f) {
            val b = gaussian(px, w, h, max(1f, long * 0.0012f))
            val k = adj.sharpen * 1.6f
            for (i in px.indices) px[i] = addScaledDiff(px[i], b[i], k)
        }
        if (adj.vignette != 0f || adj.grain > 0f) {
            val cx = w / 2f; val cy = h / 2f
            val v = adj.vignette
            val g = adj.grain * 0.22f
            val gs = max(1, (long / 900f).toInt())
            for (y in 0 until h) {
                val dy = (y - cy) / cy
                for (x in 0 until w) {
                    val i = y * w + x
                    var c = px[i]
                    if (v != 0f) {
                        val dx = (x - cx) / cx
                        val d = sqrt(dx * dx * 0.85f + dy * dy * 0.85f)
                        val t = ((d - 0.45f) / 0.65f).coerceIn(0f, 1f)
                        val s = t * t * (3f - 2f * t)
                        c = if (v > 0f) mix(c, c and 0xFF000000.toInt(), v * 0.75f * s)
                        else mix(c, c or 0x00FFFFFF, -v * 0.75f * s)
                    }
                    if (g > 0f) {
                        val n = (hash(x / gs, y / gs) - 0.5f) * g * 255f
                        val r = (((c shr 16) and 0xFF) + n).toInt().coerceIn(0, 255)
                        val gg = (((c shr 8) and 0xFF) + n).toInt().coerceIn(0, 255)
                        val b = ((c and 0xFF) + n).toInt().coerceIn(0, 255)
                        c = (c and 0xFF000000.toInt()) or (r shl 16) or (gg shl 8) or b
                    }
                    px[i] = c
                }
            }
        }
    }

    private fun addScaledDiff(c: Int, blur: Int, k: Float): Int {
        val r = (((c shr 16) and 0xFF) + (((c shr 16) and 0xFF) - ((blur shr 16) and 0xFF)) * k).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 0xFF) + (((c shr 8) and 0xFF) - ((blur shr 8) and 0xFF)) * k).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) + ((c and 0xFF) - (blur and 0xFF)) * k).toInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }
}

// ---------------------------------------------------------------- healing

internal object PeHealing {
    private class Box(val l: Int, val t: Int, val r: Int, val b: Int) { val w get() = r - l; val h get() = b - t }

    private fun minDist(x: Float, y: Float, pts: FloatArray): Float {
        var best = Float.MAX_VALUE
        var i = 0
        while (i < pts.size) {
            val dx = x - pts[i]; val dy = y - pts[i + 1]
            val d = dx * dx + dy * dy
            if (d < best) best = d
            // segmen ke titik berikutnya agar sapuan cepat tetap menyambung
            if (i + 3 < pts.size) {
                val ax = pts[i]; val ay = pts[i + 1]; val bx = pts[i + 2]; val by = pts[i + 3]
                val vx = bx - ax; val vy = by - ay
                val len = vx * vx + vy * vy
                if (len > 0f) {
                    val t = (((x - ax) * vx + (y - ay) * vy) / len).coerceIn(0f, 1f)
                    val px = ax + vx * t - x; val py = ay + vy * t - y
                    val dd = px * px + py * py
                    if (dd < best) best = dd
                }
            }
            i += 2
        }
        return sqrt(best)
    }

    private fun box(pts: FloatArray, pad: Float, w: Int, h: Int): Box {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i < pts.size) { l = min(l, pts[i]); r = max(r, pts[i]); t = min(t, pts[i + 1]); b = max(b, pts[i + 1]); i += 2 }
        return Box(max(0, (l - pad).toInt()), max(0, (t - pad).toInt()), min(w, ceil(r + pad).toInt()), min(h, ceil(b + pad).toInt()))
    }

    /** Cari sumber terbaik: cocokkan cincin di sekitar sapuan dengan kandidat geseran. */
    fun findOffset(px: IntArray, w: Int, h: Int, pts: FloatArray, radius: Float): Pair<Int, Int> {
        val ring0 = radius * 1.3f
        val ring1 = radius * 1.85f
        val bx = box(pts, ring1, w, h)
        val ring = ArrayList<Int>()
        val step = max(1, (radius / 6f).toInt())
        var y = bx.t
        while (y < bx.b) {
            var x = bx.l
            while (x < bx.r) {
                val d = minDist(x + 0.5f, y + 0.5f, pts)
                if (d in ring0..ring1) ring.add(y * w + x)
                x += step
            }
            y += step
        }
        val extent = max(bx.w, bx.h).toFloat()
        var best = Long.MAX_VALUE
        var bestOff = (extent.toInt() + 1) to 0
        val dists = floatArrayOf(extent * 0.6f + radius * 0.6f, extent * 0.8f + radius, extent + radius * 1.6f, extent * 1.3f + radius * 2.5f)
        for (dist in dists) for (k in 0 until 16) {
            val ang = k * Math.PI / 8.0
            val ox = (cos(ang) * dist).toInt()
            val oy = (sin(ang) * dist).toInt()
            if (bx.l + ox < 0 || bx.r + ox > w || bx.t + oy < 0 || bx.b + oy > h) continue
            var ssd = 0L
            for (idx in ring) {
                val a = px[idx]
                val b = px[idx + oy * w + ox]
                val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
                val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
                val db = (a and 0xFF) - (b and 0xFF)
                ssd += (dr * dr + dg * dg + db * db).toLong()
                if (ssd >= best) break
            }
            // sedikit preferensi pada sumber yang lebih dekat (tekstur serupa)
            ssd += (dist * dist * 0.02f * ring.size / max(1f, radius)).toLong()
            if (ssd < best) { best = ssd; bestOff = ox to oy }
        }
        return bestOff
    }

    /**
     * Salin tekstur dari (x+ox, y+oy) dengan tepi lembut, lalu koreksi warna dengan membran
     * (interpolasi Laplace dari selisih di batas) agar menyatu mulus dengan sekitarnya.
     */
    fun apply(px: IntArray, w: Int, h: Int, pts: FloatArray, radius: Float, ox: Int, oy: Int) {
        val inner = radius * 0.9f
        val outer = radius * 1.25f
        val bx = box(pts, outer + 2f, w, h)
        val bw = bx.w; val bh = bx.h
        if (bw <= 2 || bh <= 2) return
        val m = FloatArray(bw * bh)
        for (yy in 0 until bh) for (xx in 0 until bw) {
            val d = minDist(bx.l + xx + 0.5f, bx.t + yy + 0.5f, pts)
            m[yy * bw + xx] = when {
                d <= inner -> 1f
                d >= outer -> 0f
                else -> { val t = 1f - (d - inner) / (outer - inner); t * t * (3f - 2f * t) }
            }
        }
        fun srcAt(x: Int, y: Int): Int = px[(y + oy).coerceIn(0, h - 1) * w + (x + ox).coerceIn(0, w - 1)]
        // Grid membran kasar (≤ 48 sel per sisi).
        val f = max(1, ceil(max(bw, bh) / 48f).toInt())
        val gw = ceil(bw / f.toFloat()).toInt() + 1
        val gh = ceil(bh / f.toFloat()).toInt() + 1
        val known = BooleanArray(gw * gh)
        val gr = FloatArray(gw * gh); val gg = FloatArray(gw * gh); val gb = FloatArray(gw * gh)
        var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val xx = min(bw - 1, gx * f); val yy = min(bh - 1, gy * f)
            val gi = gy * gw + gx
            val edge = gx == 0 || gy == 0 || gx == gw - 1 || gy == gh - 1
            if (m[yy * bw + xx] <= 0f || edge) {
                val x = bx.l + xx; val y = bx.t + yy
                val d = px[y * w + x]; val s = srcAt(x, y)
                gr[gi] = (((d shr 16) and 0xFF) - ((s shr 16) and 0xFF)).toFloat()
                gg[gi] = (((d shr 8) and 0xFF) - ((s shr 8) and 0xFF)).toFloat()
                gb[gi] = ((d and 0xFF) - (s and 0xFF)).toFloat()
                known[gi] = true
                sr += gr[gi]; sg += gg[gi]; sb += gb[gi]; n++
            }
        }
        if (n > 0) { sr /= n; sg /= n; sb /= n }
        for (gi in known.indices) if (!known[gi]) { gr[gi] = sr; gg[gi] = sg; gb[gi] = sb }
        repeat(220) {
            for (gy in 1 until gh - 1) for (gx in 1 until gw - 1) {
                val gi = gy * gw + gx
                if (known[gi]) continue
                // SOR (ω=1.85): konvergen jauh lebih cepat daripada Jacobi biasa.
                gr[gi] += 1.85f * ((gr[gi - 1] + gr[gi + 1] + gr[gi - gw] + gr[gi + gw]) * 0.25f - gr[gi])
                gg[gi] += 1.85f * ((gg[gi - 1] + gg[gi + 1] + gg[gi - gw] + gg[gi + gw]) * 0.25f - gg[gi])
                gb[gi] += 1.85f * ((gb[gi - 1] + gb[gi + 1] + gb[gi - gw] + gb[gi + gw]) * 0.25f - gb[gi])
            }
        }
        val out = IntArray(bw * bh)
        for (yy in 0 until bh) for (xx in 0 until bw) {
            val mv = m[yy * bw + xx]
            val x = bx.l + xx; val y = bx.t + yy
            val d = px[y * w + x]
            if (mv <= 0f) { out[yy * bw + xx] = d; continue }
            val fx = xx / f.toFloat(); val fy = yy / f.toFloat()
            val x0 = fx.toInt().coerceAtMost(gw - 2); val y0 = fy.toInt().coerceAtMost(gh - 2)
            val tx = fx - x0; val ty = fy - y0
            fun bil(a: FloatArray): Float {
                val i0 = y0 * gw + x0
                return (a[i0] * (1 - tx) + a[i0 + 1] * tx) * (1 - ty) + (a[i0 + gw] * (1 - tx) + a[i0 + gw + 1] * tx) * ty
            }
            val s = srcAt(x, y)
            val nr = (((s shr 16) and 0xFF) + bil(gr)).coerceIn(0f, 255f)
            val ng = (((s shr 8) and 0xFF) + bil(gg)).coerceIn(0f, 255f)
            val nb = ((s and 0xFF) + bil(gb)).coerceIn(0f, 255f)
            val r = (((d shr 16) and 0xFF) + (nr - ((d shr 16) and 0xFF)) * mv).toInt()
            val g = (((d shr 8) and 0xFF) + (ng - ((d shr 8) and 0xFF)) * mv).toInt()
            val b = ((d and 0xFF) + (nb - (d and 0xFF)) * mv).toInt()
            out[yy * bw + xx] = (d and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        for (yy in 0 until bh) System.arraycopy(out, yy * bw, px, (bx.t + yy) * w + bx.l, bw)
    }

    /** Terapkan daftar heal ternormalisasi pada gambar w×h. */
    fun applyAll(px: IntArray, w: Int, h: Int, heals: List<PeHeal>) {
        val long = max(w, h).toFloat()
        for (op in heals) {
            val pts = FloatArray(op.pts.size) { i -> if (i % 2 == 0) op.pts[i] * w else op.pts[i] * h }
            apply(px, w, h, pts, max(1.5f, op.radius * long), (op.offX * long).toInt(), (op.offY * long).toInt())
        }
    }
}

// ---------------------------------------------------------------- geometry

internal object PeGeometry {
    fun orientedSize(w: Int, h: Int, rot90: Int): Pair<Int, Int> = if (((rot90 % 4) + 4) % 2 == 1) h to w else w to h

    /** Faktor skala agar gambar yang diputar θ tetap menutupi bingkai (tanpa sudut kosong). */
    fun coverScale(w: Float, h: Float, deg: Float): Float {
        val t = Math.toRadians(abs(deg).toDouble())
        val c = cos(t).toFloat(); val s = sin(t).toFloat()
        return max((w * c + h * s) / w, (w * s + h * c) / h)
    }

    /**
     * Matriks sumber → output. [withCrop] false = ukuran bingkai penuh (untuk UI crop).
     * @return matriks, lebar, tinggi output
     */
    fun matrix(srcW: Int, srcH: Int, g: PeGeom, withCrop: Boolean): Triple<Matrix, Int, Int> {
        val m = Matrix()
        m.postTranslate(-srcW / 2f, -srcH / 2f)
        m.postRotate(((g.rot90 % 4) + 4) % 4 * 90f)
        // Flip SETELAH rotasi: "balik horizontal" selalu horizontal di layar, apa pun rotasinya.
        m.postScale(if (g.flipH) -1f else 1f, if (g.flipV) -1f else 1f)
        val (ow, oh) = orientedSize(srcW, srcH, g.rot90)
        m.postTranslate(ow / 2f, oh / 2f)
        if (g.perspV != 0f || g.perspH != 0f) {
            val tv = max(g.perspV, 0f) * 0.25f * ow
            val bv = max(-g.perspV, 0f) * 0.25f * ow
            val rh = max(g.perspH, 0f) * 0.25f * oh
            val lh = max(-g.perspH, 0f) * 0.25f * oh
            val src = floatArrayOf(0f, 0f, ow.toFloat(), 0f, ow.toFloat(), oh.toFloat(), 0f, oh.toFloat())
            // Sudut hanya digeser KELUAR sehingga bingkai tetap tertutup gambar.
            val dst = floatArrayOf(-tv, -lh, ow + tv, -rh, ow + bv, oh + rh, -bv, oh + lh)
            val poly = Matrix()
            poly.setPolyToPoly(src, 0, dst, 0, 4)
            m.postConcat(poly)
        }
        if (g.straighten != 0f) {
            val s = coverScale(ow.toFloat(), oh.toFloat(), g.straighten)
            m.postRotate(g.straighten, ow / 2f, oh / 2f)
            m.postScale(s, s, ow / 2f, oh / 2f)
        }
        if (!withCrop) return Triple(m, ow, oh)
        m.postTranslate(-g.cl * ow, -g.ct * oh)
        val outW = ((g.cr - g.cl) * ow).toInt().coerceAtLeast(1)
        val outH = ((g.cb - g.ct) * oh).toInt().coerceAtLeast(1)
        return Triple(m, outW, outH)
    }

    fun apply(src: Bitmap, g: PeGeom, withCrop: Boolean = true): Bitmap {
        if (g.isIdentity()) return src
        val (m, ow, oh) = matrix(src.width, src.height, g, withCrop)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Rasio crop → nilai w/h (null = bebas, -1 = rasio foto asli). */
    fun ratioValue(key: String): Float? = when (key) {
        "1:1" -> 1f
        "4:5" -> 4f / 5f
        "3:4" -> 3f / 4f
        "2:3" -> 2f / 3f
        "9:16" -> 9f / 16f
        "16:9" -> 16f / 9f
        "3:2" -> 3f / 2f
        "asli" -> -1f
        else -> null
    }

    /** Kotak crop terbesar di tengah dengan rasio [r] (dalam piksel bingkai fw×fh) → ternormalisasi. */
    fun centeredCrop(fw: Int, fh: Int, r: Float): FloatArray {
        var cw = fw.toFloat()
        var ch = cw / r
        if (ch > fh) { ch = fh.toFloat(); cw = ch * r }
        val l = (fw - cw) / 2f / fw
        val t = (fh - ch) / 2f / fh
        return floatArrayOf(l, t, l + cw / fw, t + ch / fh)
    }
}
