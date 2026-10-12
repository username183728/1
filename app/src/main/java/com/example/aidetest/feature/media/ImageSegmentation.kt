package com.example.aidetest

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Mask kepercayaan foreground 0..1 hasil model AI. */
internal class FloatMask(val data: FloatArray, val width: Int, val height: Int)

/**
 * Segmentasi AI on-device memakai model ML Kit yang SUNGGUHAN (bukan sekadar nama tombol):
 *  - SUBJECT : ML Kit Subject Segmentation — subjek utama (orang, hewan, produk, benda).
 *              Model dipasok Google Play Services (diunduh otomatis, lihat meta-data di Manifest).
 *  - PERSON  : ML Kit Selfie Segmentation — khusus orang, model dibundel di APK (offline).
 */
internal object AiSegmenter {
    enum class Mode { SUBJECT, PERSON }

    private const val MAX_SIDE = 1024

    fun segment(context: Context, source: Bitmap, mode: Mode, onResult: (Result<FloatMask>) -> Unit) {
        val scaled = try {
            downscale(source)
        } catch (e: Throwable) {
            onResult(Result.failure(e))
            return
        }
        val image = InputImage.fromBitmap(scaled, 0)
        when (mode) {
            Mode.SUBJECT -> runSubject(image, scaled, onResult)
            Mode.PERSON -> runPerson(image, scaled, onResult)
        }
    }

    private fun downscale(src: Bitmap): Bitmap {
        val longSide = max(src.width, src.height)
        val base = if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)
        if (longSide <= MAX_SIDE) return base
        val s = MAX_SIDE.toFloat() / longSide
        return Bitmap.createScaledBitmap(base, (base.width * s).toInt().coerceAtLeast(1), (base.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun runSubject(image: InputImage, scaled: Bitmap, onResult: (Result<FloatMask>) -> Unit) {
        val segmenter = try {
            SubjectSegmentation.getClient(
                SubjectSegmenterOptions.Builder()
                    .enableForegroundConfidenceMask()
                    .build()
            )
        } catch (e: Throwable) {
            onResult(Result.failure(IllegalStateException(friendly(e), e)))
            return
        }
        segmenter.process(image)
            .addOnSuccessListener { result ->
                val buffer = result.foregroundConfidenceMask
                if (buffer == null) {
                    onResult(Result.failure(IllegalStateException("Model tidak mengembalikan mask")))
                } else {
                    val arr = FloatArray(scaled.width * scaled.height)
                    buffer.rewind()
                    val n = min(arr.size, buffer.remaining())
                    buffer.get(arr, 0, n)
                    onResult(Result.success(FloatMask(arr, scaled.width, scaled.height)))
                }
                runCatching { segmenter.close() }
            }
            .addOnFailureListener { e ->
                runCatching { segmenter.close() }
                onResult(Result.failure(IllegalStateException(friendly(e), e)))
            }
    }

    private fun runPerson(image: InputImage, scaled: Bitmap, onResult: (Result<FloatMask>) -> Unit) {
        val segmenter = try {
            Segmentation.getClient(
                SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                    .build()
            )
        } catch (e: Throwable) {
            onResult(Result.failure(IllegalStateException(friendly(e), e)))
            return
        }
        segmenter.process(image)
            .addOnSuccessListener { mask ->
                val w = mask.width
                val h = mask.height
                val arr = FloatArray(w * h)
                val buffer = mask.buffer
                buffer.rewind()
                val fb = buffer.asFloatBuffer()
                val n = min(arr.size, fb.remaining())
                fb.get(arr, 0, n)
                runCatching { segmenter.close() }
                onResult(Result.success(FloatMask(arr, w, h)))
            }
            .addOnFailureListener { e ->
                runCatching { segmenter.close() }
                onResult(Result.failure(IllegalStateException(friendly(e), e)))
            }
    }

    private fun friendly(e: Throwable): String {
        val msg = (e.message ?: e.javaClass.simpleName)
        val lower = msg.lowercase()
        return when {
            "download" in lower || "unavailable" in lower || "not available" in lower || "module" in lower ->
                "Model AI sedang disiapkan Google Play Services. Pastikan internet aktif lalu coba lagi beberapa saat, atau pakai mode Orang (offline)."
            "play services" in lower || "google play" in lower ->
                "Mode Subjek butuh Google Play Services. Pakai mode Orang (offline) atau Warna latar."
            else -> "Segmentasi gagal: $msg"
        }
    }
}

/**
 * Operasi mask murni (tanpa Android API) supaya bisa diuji di JVM.
 * Mask = ByteArray alpha 0..255 (dibaca sebagai unsigned).
 */
internal object MaskOps {

    fun u(b: Byte): Int = b.toInt() and 0xFF

    /** Ubah confidence float (ukuran model) → mask byte (ukuran kerja), bilinear. */
    fun fromConfidence(m: FloatMask, dw: Int, dh: Int): ByteArray {
        val out = ByteArray(dw * dh)
        val sx = m.width.toFloat() / dw
        val sy = m.height.toFloat() / dh
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (m.height - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, m.height - 1)
            val ty = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (m.width - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, m.width - 1)
                val tx = fx - x0
                val a = m.data[y0 * m.width + x0]
                val b = m.data[y0 * m.width + x1]
                val c = m.data[y1 * m.width + x0]
                val d = m.data[y1 * m.width + x1]
                val v = (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
                out[y * dw + x] = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            }
        }
        return out
    }

    /** Resize mask byte bilinear (untuk ekspor resolusi penuh). */
    fun resize(mask: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        if (sw == dw && sh == dh) return mask.copyOf()
        val f = FloatArray(sw * sh) { u(mask[it]) / 255f }
        return fromConfidence(FloatMask(f, sw, sh), dw, dh)
    }

    /** Box blur terpisah O(n) per arah. */
    fun blur(mask: ByteArray, w: Int, h: Int, radius: Int): ByteArray {
        if (radius <= 0) return mask.copyOf()
        val tmp = IntArray(w * h)
        val out = ByteArray(w * h)
        val win = radius * 2 + 1
        for (y in 0 until h) {
            var sum = 0
            val row = y * w
            for (i in -radius..radius) sum += u(mask[row + i.coerceIn(0, w - 1)])
            for (x in 0 until w) {
                tmp[row + x] = sum / win
                val add = row + (x + radius + 1).coerceIn(0, w - 1)
                val sub = row + (x - radius).coerceIn(0, w - 1)
                sum += u(mask[add]) - u(mask[sub])
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (i in -radius..radius) sum += tmp[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = (sum / win).toByte()
                val add = (y + radius + 1).coerceIn(0, h - 1) * w + x
                val sub = (y - radius).coerceIn(0, h - 1) * w + x
                sum += tmp[add] - tmp[sub]
            }
        }
        return out
    }

    /** Geser tepi: px > 0 melebar, px < 0 menyempit (dilasi/erosi lembut). */
    fun shiftEdge(mask: ByteArray, w: Int, h: Int, px: Int): ByteArray {
        if (px == 0) return mask.copyOf()
        val b = blur(mask, w, h, kotlin.math.abs(px))
        val out = ByteArray(w * h)
        for (i in b.indices) {
            val v = u(b[i])
            val r = if (px > 0) min(255, v * 2) else max(0, (v - 128) * 2)
            out[i] = r.toByte()
        }
        return out
    }

    /** Pertegas tepi (0 = tanpa perubahan, 1 = sangat tegas) setelah blur ringan → tepi halus tanpa gerigi. */
    fun smooth(mask: ByteArray, w: Int, h: Int, amount: Float): ByteArray {
        if (amount <= 0f) return mask.copyOf()
        val r = (1 + amount * 3).toInt()
        val b = blur(mask, w, h, r)
        val k = 1f + amount * 3f
        val out = ByteArray(w * h)
        for (i in b.indices) {
            val v = ((u(b[i]) - 128) * k + 128).toInt().coerceIn(0, 255)
            out[i] = v.toByte()
        }
        return out
    }

    /** Pipeline penyempurnaan non-destruktif: halus → geser tepi → feather. */
    fun refine(base: ByteArray, w: Int, h: Int, smooth: Float, shift: Int, feather: Int): ByteArray {
        var m = base
        if (smooth > 0f) m = smooth(m, w, h, smooth)
        if (shift != 0) m = shiftEdge(m, w, h, shift)
        if (feather > 0) m = blur(m, w, h, feather)
        return if (m === base) base.copyOf() else m
    }

    private fun dist2(c1: Int, c2: Int): Int {
        val dr = ((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF)
        val dg = ((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF)
        val db = (c1 and 0xFF) - (c2 and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    /** Warna latar dominan dari tepi gambar (rata-rata klaster terbesar). */
    fun borderColor(px: IntArray, w: Int, h: Int): Int {
        val samples = ArrayList<Int>()
        val step = max(1, (w + h) / 400)
        for (x in 0 until w step step) { samples.add(px[x]); samples.add(px[(h - 1) * w + x]) }
        for (y in 0 until h step step) { samples.add(px[y * w]); samples.add(px[y * w + w - 1]) }
        var best = samples.first()
        var bestCount = -1
        val probe = samples.filterIndexed { i, _ -> i % max(1, samples.size / 48) == 0 }
        for (c in probe) {
            val n = samples.count { dist2(it, c) < 900 }
            if (n > bestCount) { bestCount = n; best = c }
        }
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (c in samples) if (dist2(c, best) < 900) {
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
        }
        if (n == 0) return best
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /**
     * Hapus latar berwarna seragam dengan flood-fill DARI TEPI gambar: hanya area latar yang
     * tersambung ke tepi yang dihapus, jadi warna serupa di dalam objek tetap aman.
     * Tepi diberi transisi lembut sesuai [softness].
     */
    fun colorKey(px: IntArray, w: Int, h: Int, tolerance: Int, softness: Int): ByteArray {
        val bg = borderColor(px, w, h)
        val tol2 = tolerance * tolerance * 3
        val soft = (tolerance + softness).let { it * it * 3 }
        val mask = ByteArray(w * h) { 255.toByte() }
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        fun seed(i: Int) {
            if (!visited[i] && dist2(px[i], bg) <= tol2) { visited[i] = true; queue[tail++] = i }
        }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]
            mask[i] = 0
            val x = i % w
            val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        // Tepi lembut: piksel foreground yang bertetangga dengan latar & warnanya mendekati latar.
        if (softness > 0) {
            val out = mask.copyOf()
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                if (mask[i].toInt() == 0) continue
                if (mask[i - 1].toInt() != 0 && mask[i + 1].toInt() != 0 && mask[i - w].toInt() != 0 && mask[i + w].toInt() != 0) continue
                val d = dist2(px[i], bg)
                if (d < soft) {
                    val t = ((sqrt(d.toDouble()) - sqrt(tol2.toDouble())) / (sqrt(soft.toDouble()) - sqrt(tol2.toDouble()))).coerceIn(0.0, 1.0)
                    out[i] = (t * 255).toInt().toByte()
                }
            }
            return out
        }
        return mask
    }

    /**
     * Sapuan kuas lingkaran. [add] = kembalikan (opaque), selain itu hapus.
     * [hardness] 0..1: bagian tengah penuh, sisanya melandai ke tepi.
     * @return area kotor [left, top, right, bottom] (inklusif-eksklusif).
     */
    fun brush(mask: ByteArray, w: Int, h: Int, cx: Float, cy: Float, radius: Float, hardness: Float, add: Boolean): IntArray {
        val l = max(0, (cx - radius).toInt())
        val t = max(0, (cy - radius).toInt())
        val r = min(w, (cx + radius).toInt() + 1)
        val b = min(h, (cy + radius).toInt() + 1)
        val hard = hardness.coerceIn(0f, 0.99f)
        for (y in t until b) for (x in l until r) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            val d = sqrt(dx * dx + dy * dy) / radius
            if (d > 1f) continue
            val wgt = if (d <= hard) 1f else 1f - (d - hard) / (1f - hard)
            val i = y * w + x
            val cur = u(mask[i])
            val v = if (add) max(cur, (255 * wgt).toInt()) else min(cur, (255 * (1f - wgt)).toInt())
            mask[i] = v.toByte()
        }
        return intArrayOf(l, t, r, b)
    }

    /**
     * Dekontaminasi warna: piksel tepi semi-transparan sering membawa warna latar lama (halo).
     * Warna mereka ditarik ke rata-rata warna foreground solid di sekitarnya (integral image, O(n)).
     */
    fun decontaminate(px: IntArray, mask: ByteArray, w: Int, h: Int, radius: Int): IntArray {
        val out = px.copyOf()
        val stride = w + 1
        val sr = LongArray(stride * (h + 1))
        val sg = LongArray(stride * (h + 1))
        val sb = LongArray(stride * (h + 1))
        val sn = IntArray(stride * (h + 1))
        for (y in 0 until h) {
            var rr = 0L; var gg = 0L; var bb = 0L; var nn = 0
            for (x in 0 until w) {
                val i = y * w + x
                if (u(mask[i]) >= 248) {
                    val c = px[i]
                    rr += (c shr 16) and 0xFF; gg += (c shr 8) and 0xFF; bb += c and 0xFF; nn++
                }
                val o = (y + 1) * stride + x + 1
                sr[o] = sr[o - stride] + rr; sg[o] = sg[o - stride] + gg; sb[o] = sb[o - stride] + bb; sn[o] = sn[o - stride] + nn
            }
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val a = u(mask[i])
            if (a < 8 || a >= 248) continue
            val x0 = max(0, x - radius); val y0 = max(0, y - radius)
            val x1 = min(w, x + radius + 1); val y1 = min(h, y + radius + 1)
            fun s(arr: LongArray) = arr[y1 * stride + x1] - arr[y0 * stride + x1] - arr[y1 * stride + x0] + arr[y0 * stride + x0]
            val n = sn[y1 * stride + x1] - sn[y0 * stride + x1] - sn[y1 * stride + x0] + sn[y0 * stride + x0]
            if (n <= 0) continue
            val fr = (s(sr) / n).toInt(); val fg = (s(sg) / n).toInt(); val fb = (s(sb) / n).toInt()
            val k = (1f - a / 255f) * 0.85f
            val c = px[i]
            val r = (((c shr 16) and 0xFF) * (1 - k) + fr * k).toInt()
            val g = (((c shr 8) and 0xFF) * (1 - k) + fg * k).toInt()
            val b = ((c and 0xFF) * (1 - k) + fb * k).toInt()
            out[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /** Kotak pembatas area mask > threshold, atau null bila kosong. [l, t, r, b] */
    fun bounds(mask: ByteArray, w: Int, h: Int, threshold: Int = 8): IntArray? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) if (u(mask[y * w + x]) > threshold) {
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        return if (r < 0) null else intArrayOf(l, t, r + 1, b + 1)
    }

    /** Persentase area yang dipertahankan (untuk status). */
    fun coverage(mask: ByteArray): Int {
        var s = 0L
        for (v in mask) s += u(v)
        return if (mask.isEmpty()) 0 else (s * 100 / (mask.size * 255L)).toInt()
    }
}

/**
 * Gabungkan warna sumber (atau hasil dekontaminasi) dengan mask menjadi ARGB non-premultiplied.
 * [out] boleh sama dengan [px] (in-place).
 */
internal fun composeCut(px: IntArray, deco: IntArray?, mask: ByteArray, out: IntArray, from: Int, to: Int) {
    for (i in from until to) {
        val col = (deco ?: px)[i]
        val srcA = (px[i] ushr 24) and 0xFF
        val m = mask[i].toInt() and 0xFF
        out[i] = ((srcA * m / 255) shl 24) or (col and 0xFFFFFF)
    }
}
