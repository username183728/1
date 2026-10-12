package com.example.aidetest

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Registry id Foto Studio. Tiga tool lama digabung menjadi satu; id lama tetap berfungsi
 * (alias) dan id yang tersimpan (favorit, terbaru, statistik pemakaian) dimigrasi sekali.
 */
internal object CsToolIds {
    const val ID = "creativestudio"
    const val NAME = "Foto Studio"
    val LEGACY: Set<String> = setOf("photoeditor", "designstudio", "bgremover", "animationstudio")

    /** Aksi awal saat studio dibuka lewat id lama. */
    enum class Entry { DEFAULT, PHOTO, CUTOUT, ANIM }

    fun canonical(id: String): String = if (id in LEGACY) ID else id

    fun entryFor(id: String): Entry = when (id) {
        "photoeditor" -> Entry.PHOTO
        "bgremover" -> Entry.CUTOUT
        "animationstudio" -> Entry.ANIM
        else -> Entry.DEFAULT
    }

    fun hasLegacy(csv: String?): Boolean = csv != null && csv.split(',', ':').any { it.trim() in LEGACY }

    /** Daftar id dipisah koma (favorit / terbaru): alias → id baru, duplikat dibuang, urutan tetap. */
    fun migrateIdList(csv: String?): String {
        if (csv.isNullOrBlank()) return csv ?: ""
        val out = LinkedHashSet<String>()
        for (raw in csv.split(',')) {
            val id = raw.trim()
            if (id.isNotEmpty()) out.add(canonical(id))
        }
        return out.joinToString(",")
    }

    /** Himpunan id (mis. tool yang dipin): alias → id baru; null bila tidak ada yang berubah. */
    fun migrateIdSet(ids: Set<String>?): Set<String>? {
        if (ids == null || ids.none { it in LEGACY }) return null
        return ids.mapTo(LinkedHashSet()) { canonical(it) }
    }

    /** Statistik "id:jumlah,…": hitungan alias digabung ke id baru, urut menurun (stabil). */
    fun migrateUsage(csv: String?): String {
        if (csv.isNullOrBlank()) return csv ?: ""
        val counts = LinkedHashMap<String, Int>()
        for (part in csv.split(',')) {
            val bits = part.split(':', limit = 2)
            if (bits.size != 2) continue
            val id = bits[0].trim()
            if (id.isEmpty()) continue
            val key = canonical(id)
            counts[key] = (counts[key] ?: 0) + (bits[1].trim().toIntOrNull() ?: 0)
        }
        return counts.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" }
    }
}

/** Matematika murni Foto Studio (ukuran ekspor, geometri foto, crop, gradien, batas alpha). */
internal object CsMath {
    const val MAX_CANVAS = 4096
    /** Batas sisi terpanjang satu layer foto saat ekspor (sama dengan Photo Editor lama). */
    const val MAX_EXPORT_LAYER = 3072

    /** coerceIn yang tidak melempar bila batas terbalik (kotak sangat kecil). */
    fun clampF(v: Float, lo: Float, hi: Float): Float = if (hi < lo) lo else v.coerceIn(lo, hi)

    fun normDeg(d: Float): Float = ((d % 360f) + 540f) % 360f - 180f

    /**
     * Ukuran ekspor dari ukuran kanvas: [customLong] > 0 = sisi terpanjang kustom, selain itu [scale].
     * Dibatasi [maxSide] per sisi agar memori HP aman.
     */
    fun exportSize(cw: Int, ch: Int, scale: Float, customLong: Int, maxSide: Int = MAX_CANVAS): Pair<Int, Int> {
        val long = max(cw, ch).coerceAtLeast(1)
        var s = if (customLong > 0) customLong.toFloat() / long else scale
        if (s.isNaN() || s <= 0f) s = 1f
        if (long * s > maxSide) s = maxSide.toFloat() / long
        return (cw * s).roundToInt().coerceAtLeast(1) to (ch * s).roundToInt().coerceAtLeast(1)
    }

    /** Sisi terpanjang salinan kerja foto: makin banyak layer foto, makin kecil (hemat memori). */
    fun workLongSide(imageCount: Int): Int = when {
        imageCount <= 2 -> 1600
        imageCount <= 4 -> 1280
        else -> 1024
    }

    fun orientedSize(w: Int, h: Int, rot90: Int): Pair<Int, Int> = if (((rot90 % 4) + 4) % 2 == 1) h to w else w to h

    /** Ukuran keluaran foto setelah orientasi + crop (sama persis dengan PeGeometry.matrix). */
    fun outputSize(srcW: Int, srcH: Int, rot90: Int, cl: Float, ct: Float, cr: Float, cb: Float): Pair<Int, Int> {
        val (ow, oh) = orientedSize(srcW.coerceAtLeast(1), srcH.coerceAtLeast(1), rot90)
        return ((cr - cl) * ow).toInt().coerceAtLeast(1) to ((cb - ct) * oh).toInt().coerceAtLeast(1)
    }

    /** Ukuran agar sumber muat di kotak × [fraction]; [noUpscale] = tidak melebihi 1:1. */
    fun fitInside(srcW: Float, srcH: Float, boxW: Float, boxH: Float, fraction: Float, noUpscale: Boolean): Pair<Float, Float> {
        if (srcW <= 0f || srcH <= 0f) return boxW * fraction to boxH * fraction
        var s = min(boxW * fraction / srcW, boxH * fraction / srcH)
        if (noUpscale) s = min(s, 1f)
        if (s.isNaN() || s <= 0f) s = 1f
        return srcW * s to srcH * s
    }

    /** Kotak crop terbesar di tengah dengan rasio [r] (w/h piksel) → l,t,r,b ternormalisasi. */
    fun centeredCrop(fw: Int, fh: Int, r: Float): FloatArray {
        val w = fw.coerceAtLeast(1).toFloat()
        val h = fh.coerceAtLeast(1).toFloat()
        if (r.isNaN() || r <= 0f) return floatArrayOf(0f, 0f, 1f, 1f)
        var cw = w
        var ch = cw / r
        if (ch > h) { ch = h; cw = ch * r }
        val l = (w - cw) / 2f / w
        val t = (h - ch) / 2f / h
        return floatArrayOf(l, t, l + cw / w, t + ch / h)
    }

    /**
     * Seret pegangan crop. [handle]: 0..3 sudut (kiri-atas, kanan-atas, kanan-bawah, kiri-bawah),
     * 4..7 sisi (atas, kanan, bawah, kiri), 8 = geser kotak. [start] = l,t,r,b ternormalisasi,
     * [dx]/[dy] ternormalisasi, [ratio] = w/h piksel (null = bebas). Hasil selalu di dalam 0..1.
     */
    fun cropDrag(handle: Int, start: FloatArray, dx: Float, dy: Float, frameW: Float, frameH: Float, ratio: Float?, minPx: Float = 32f): FloatArray {
        var l = start[0]
        var t = start[1]
        var r = start[2]
        var b = start[3]
        val fw = frameW.coerceAtLeast(1f)
        val fh = frameH.coerceAtLeast(1f)
        val minW = (minPx / fw).coerceAtMost(1f)
        val minH = (minPx / fh).coerceAtMost(1f)
        when (handle) {
            8 -> {
                val cw = r - l
                val ch = b - t
                l = clampF(l + dx, 0f, 1f - cw)
                t = clampF(t + dy, 0f, 1f - ch)
                r = l + cw
                b = t + ch
            }
            4 -> t = clampF(t + dy, 0f, b - minH)
            5 -> r = clampF(r + dx, l + minW, 1f)
            6 -> b = clampF(b + dy, t + minH, 1f)
            7 -> l = clampF(l + dx, 0f, r - minW)
            else -> {
                val left = handle == 0 || handle == 3
                val top = handle == 0 || handle == 1
                if (left) l = clampF(l + dx, 0f, r - minW) else r = clampF(r + dx, l + minW, 1f)
                if (top) t = clampF(t + dy, 0f, b - minH) else b = clampF(b + dy, t + minH, 1f)
                if (ratio != null && ratio > 0f) {
                    // Rasio dijaga dalam piksel dengan sudut seberang sebagai jangkar.
                    var nw = (r - l) * fw
                    var ph = nw / ratio
                    val maxH = if (top) b * fh else (1f - t) * fh
                    if (ph > maxH) { ph = maxH; nw = ph * ratio }
                    val maxW = if (left) r * fw else (1f - l) * fw
                    if (nw > maxW) { nw = maxW; ph = nw / ratio }
                    if (left) l = r - nw / fw else r = l + nw / fw
                    if (top) t = b - ph / fh else b = t + ph / fh
                }
            }
        }
        return floatArrayOf(l, t, r, b)
    }

    /**
     * Kanvas baru untuk foto utama dokumen: kepadatan (px kanvas per px foto) dipertahankan
     * sehingga ekspor 1× tetap resolusi penuh; diperkecil bila melebihi [maxSide].
     */
    fun docCanvasSize(outW: Int, outH: Int, density: Float, maxSide: Int = MAX_CANVAS): Pair<Int, Int> {
        var d = if (density.isNaN() || density <= 0f) 1f else density
        val long = max(outW, outH).coerceAtLeast(1) * d
        if (long > maxSide) d *= maxSide / long
        return (outW * d).roundToInt().coerceIn(16, maxSide) to (outH * d).roundToInt().coerceIn(16, maxSide)
    }

    /**
     * Petakan titik kanvas saat crop foto utama berubah (orientasi sama): isi foto tetap di tempatnya,
     * jadi teks/stiker di atas foto ikut bergeser bersama kontennya.
     */
    fun remapCrop(x: Float, y: Float, oldW: Float, oldH: Float, old: FloatArray, newW: Float, newH: Float, new: FloatArray): Pair<Float, Float> {
        val fx = old[0] + (x / oldW) * (old[2] - old[0])
        val fy = old[1] + (y / oldH) * (old[3] - old[1])
        val nx = (fx - new[0]) / (new[2] - new[0]).coerceAtLeast(1e-4f) * newW
        val ny = (fy - new[1]) / (new[3] - new[1]).coerceAtLeast(1e-4f) * newH
        return nx to ny
    }

    /** Skala isi antara dua crop (1 = kepadatan piksel sama). */
    fun cropContentScale(oldW: Float, old: FloatArray, newW: Float, new: FloatArray): Float {
        val a = oldW / (old[2] - old[0]).coerceAtLeast(1e-4f)
        val b = newW / (new[2] - new[0]).coerceAtLeast(1e-4f)
        return if (a <= 0f) 1f else b / a
    }

    /**
     * Ukuran layer foto saat ukuran keluarannya berubah (crop/putar): skala px per px foto pada
     * tiap sumbu dipertahankan, pusat tetap. @return x, y, w, h
     */
    fun resizeForOutput(x: Float, y: Float, w: Float, h: Float, oldOw: Int, oldOh: Int, newOw: Int, newOh: Int): FloatArray {
        val sx = w / oldOw.coerceAtLeast(1)
        val sy = h / oldOh.coerceAtLeast(1)
        val nw = (newOw * sx).coerceAtLeast(1f)
        val nh = (newOh * sy).coerceAtLeast(1f)
        val cx = x + w / 2f
        val cy = y + h / 2f
        return floatArrayOf(cx - nw / 2f, cy - nh / 2f, nw, nh)
    }

    /**
     * Sisi terpanjang sumber yang perlu didekode agar layer foto tajam di ekspor:
     * ukuran layer di ekspor dibanding ukuran keluaran foto pada resolusi sumber penuh.
     */
    fun exportSourceSide(elW: Float, elH: Float, scale: Float, outW: Int, outH: Int, srcLong: Int, cap: Int = MAX_EXPORT_LAYER): Int {
        val need = max(elW * scale / outW.coerceAtLeast(1), elH * scale / outH.coerceAtLeast(1))
        val upper = min(cap, srcLong.coerceAtLeast(256))
        val side = if (need.isNaN() || need <= 0f) upper else (srcLong * need).roundToInt()
        return side.coerceIn(256, upper.coerceAtLeast(256))
    }

    /** Titik awal/akhir gradien linear yang menutupi kotak w×h; 0° = kiri→kanan, 90° = atas→bawah. */
    fun gradientLine(w: Float, h: Float, deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val dx = cos(r).toFloat()
        val dy = sin(r).toFloat()
        val half = (abs(w * dx) + abs(h * dy)) / 2f
        val cx = w / 2f
        val cy = h / 2f
        return floatArrayOf(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half)
    }

    /** Kotak pembatas piksel ARGB dengan alpha > [threshold]: [l, t, r, b) atau null bila kosong. */
    fun alphaBounds(px: IntArray, w: Int, h: Int, threshold: Int = 8): IntArray? {
        var l = w
        var t = h
        var r = -1
        var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > threshold) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        return if (r < 0) null else intArrayOf(l, t, r + 1, b + 1)
    }

    /** Gabungan dua kotak [l, t, r, b) — [a] null = belum ada kotak. */
    fun unionBounds(a: IntArray?, b: IntArray): IntArray =
        if (a == null) b.copyOf() else intArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))

    fun padRect(b: IntArray, w: Int, h: Int, pad: Int): IntArray =
        intArrayOf(max(0, b[0] - pad), max(0, b[1] - pad), min(w, b[2] + pad), min(h, b[3] + pad))

    /** Jumlah dab kuas di sepanjang sapuan agar garis tetap menyambung. */
    fun brushSteps(dist: Float, radius: Float): Int = ceil(dist / max(1f, radius * 0.3f)).toInt().coerceAtLeast(1)

    fun invertMask(m: ByteArray): ByteArray = ByteArray(m.size) { (255 - (m[it].toInt() and 0xFF)).toByte() }
}
