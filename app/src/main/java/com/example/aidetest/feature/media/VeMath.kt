package com.example.aidetest

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Logika murni Video Editor (tanpa API Android) agar bisa diuji di JVM (VeMathTest):
 * format waktu, jalur (lane) timeline, snap, animasi overlay, efek berwaktu, transisi, SRT,
 * deteksi hening & adegan, mixer audio PCM, header WAV.
 *
 * Semua waktu dalam milidetik kecuali disebut lain.
 */

internal object VeTime {
    /** "0:05", "1:02", "1:02:03" (opsional 1 desimal: "0:05.3"). */
    fun fmt(ms: Long, tenths: Boolean = false): String {
        val t = ms.coerceAtLeast(0L)
        val total = t / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            tenths -> String.format(Locale.US, "%d:%02d.%d", m, s, (t % 1000) / 100)
            else -> String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    /** Gaya jam editor: "00:05", "01:02:03". */
    fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** Durasi ringkas untuk lencana klip: "5.2s", "1:05". */
    fun badge(ms: Long): String =
        if (ms < 60_000L) String.format(Locale.US, "%.1fs", ms / 1000f) else fmt(ms)

    fun srt(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", t / 3_600_000, (t / 60_000) % 60, (t / 1000) % 60, t % 1000)
    }
}

/** Posisi klip di timeline utama (urut, tanpa celah). */
internal object VeClipMath {
    fun starts(durations: LongArray): LongArray {
        val out = LongArray(durations.size)
        var t = 0L
        for (i in durations.indices) { out[i] = t; t += durations[i] }
        return out
    }

    fun total(durations: LongArray): Long = durations.sum()

    /** Indeks klip pada waktu [t]; t >= total → klip terakhir; kosong → -1. */
    fun indexAt(durations: LongArray, t: Long): Int {
        if (durations.isEmpty()) return -1
        var acc = 0L
        for (i in durations.indices) {
            if (t < acc + durations[i]) return i
            acc += durations[i]
        }
        return durations.lastIndex
    }
}

/** Jalur (lane) untuk item yang boleh tumpang-tindih: item yang bertabrakan dipindah ke baris lain. */
internal object VeLanes {
    fun assign(starts: LongArray, ends: LongArray): IntArray {
        val n = starts.size
        val out = IntArray(n)
        if (n == 0) return out
        val order = (0 until n).sortedWith(compareBy({ starts[it] }, { ends[it] }, { it }))
        val laneEnd = ArrayList<Long>()
        for (i in order) {
            var lane = -1
            for (k in laneEnd.indices) if (laneEnd[k] <= starts[i]) { lane = k; break }
            if (lane < 0) { lane = laneEnd.size; laneEnd.add(ends[i]) } else laneEnd[lane] = ends[i]
            out[i] = lane
        }
        return out
    }
}

internal object VeSnapMath {
    /** Target terdekat dalam [threshold], atau null. */
    fun nearest(value: Long, targets: LongArray, threshold: Long): Long? {
        var best: Long? = null
        var bestD = threshold + 1
        for (t in targets) {
            val d = abs(t - value)
            if (d < bestD) { bestD = d; best = t }
        }
        return best
    }

    /** Snap awal & akhir item sekaligus (geser): pilih yang jaraknya paling kecil. Hasil = offset yang ditambahkan. */
    fun snapMove(start: Long, end: Long, targets: LongArray, threshold: Long): Long {
        val a = nearest(start, targets, threshold)?.let { it - start }
        val b = nearest(end, targets, threshold)?.let { it - end }
        return when {
            a != null && b != null -> if (abs(a) <= abs(b)) a else b
            a != null -> a
            b != null -> b
            else -> 0L
        }
    }
}

/** Pemetaan waktu ↔ piksel timeline dengan playhead tetap di tengah. */
internal object VeScale {
    fun xOf(timeMs: Long, playheadMs: Long, pxPerSec: Float, centerX: Float): Float =
        centerX + (timeMs - playheadMs) / 1000f * pxPerSec

    fun timeAt(x: Float, playheadMs: Long, pxPerSec: Float, centerX: Float): Long =
        playheadMs + ((x - centerX) / pxPerSec * 1000f).toLong()

    /** Langkah label penggaris yang jaraknya ≥ [minPx] piksel. */
    fun rulerStepMs(pxPerSec: Float, minPx: Float): Long {
        val steps = longArrayOf(100, 200, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000)
        for (s in steps) if (s / 1000f * pxPerSec >= minPx) return s
        return steps.last()
    }
}

// ============================================================================ animasi overlay

internal class VeOverlayState(val visible: Boolean, val alpha: Float, val scale: Float, val dx: Float, val dy: Float)

/**
 * Penempatan overlay ke "slot" ekspor. Satu OverlayEffect Media3 maksimal 15 overlay dan tiap efek
 * memakai tekstur penuh, jadi item yang waktunya tidak bertumpuk berbagi satu slot (mis. ratusan
 * keterangan SRT = 1 slot). Urutan tumpukan (z) dijaga: item yang bertumpuk dengan item di bawahnya
 * selalu berada di slot yang lebih tinggi.
 */
internal object VeOverlaySlots {
    /** [starts]/[ends] dalam urutan z (indeks 0 = paling belakang). @return nomor slot per item. */
    fun assign(starts: LongArray, ends: LongArray): IntArray {
        val n = starts.size
        val lane = IntArray(n)
        for (i in 0 until n) {
            var l = 0
            for (j in 0 until i) {
                if (starts[i] < ends[j] && starts[j] < ends[i] && lane[j] + 1 > l) l = lane[j] + 1
            }
            lane[i] = l
        }
        return lane
    }
}

internal object VeAnimMath {
    /** (kunci, label) */
    val IN: List<Pair<String, String>> = listOf(
        "none" to "Tanpa", "fade" to "Pudar", "pop" to "Pop", "zoom" to "Zoom", "naik" to "Naik",
        "turun" to "Turun", "kiri" to "Dari kiri", "kanan" to "Dari kanan"
    )

    private fun easeOut(p: Float): Float = 1f - (1f - p) * (1f - p)

    private fun easeOutBack(p: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = p - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    /** Keadaan fase masuk/keluar pada progres [p] (0 = tersembunyi, 1 = tampil penuh). */
    private fun phase(kind: String, p: Float): VeOverlayState = when (kind) {
        "fade" -> VeOverlayState(true, p, 1f, 0f, 0f)
        "pop" -> VeOverlayState(true, min(1f, p * 3f), (0.5f + 0.5f * easeOutBack(p)).coerceAtLeast(0f), 0f, 0f)
        "zoom" -> VeOverlayState(true, p, 1.6f - 0.6f * easeOut(p), 0f, 0f)
        "naik" -> VeOverlayState(true, p, 1f, 0f, 0.08f * (1f - easeOut(p)))
        "turun" -> VeOverlayState(true, p, 1f, 0f, -0.08f * (1f - easeOut(p)))
        "kiri" -> VeOverlayState(true, p, 1f, -0.12f * (1f - easeOut(p)), 0f)
        "kanan" -> VeOverlayState(true, p, 1f, 0.12f * (1f - easeOut(p)), 0f)
        else -> VeOverlayState(true, 1f, 1f, 0f, 0f)
    }

    /**
     * Keadaan overlay pada waktu [tMs] untuk jendela [startMs, startMs+durationMs).
     * dx/dy = pergeseran sebagai pecahan lebar/tinggi bingkai (dy positif = turun).
     */
    fun state(animIn: String, animOut: String, animMs: Long, startMs: Long, durationMs: Long, tMs: Long): VeOverlayState {
        val end = startMs + durationMs
        if (tMs < startMs || tMs >= end) return VeOverlayState(false, 0f, 1f, 0f, 0f)
        val am = min(animMs.coerceAtLeast(1L), durationMs / 2L).coerceAtLeast(1L)
        val pIn = if (animIn == "none") 1f else ((tMs - startMs).toFloat() / am).coerceIn(0f, 1f)
        val pOut = if (animOut == "none") 1f else ((end - tMs).toFloat() / am).coerceIn(0f, 1f)
        return when {
            pIn < 1f -> phase(animIn, pIn)
            pOut < 1f -> phase(animOut, pOut)
            else -> VeOverlayState(true, 1f, 1f, 0f, 0f)
        }
    }
}

// ============================================================================ efek berwaktu

internal object VeFxMath {
    /** (kunci, label) */
    val TYPES: List<Pair<String, String>> = listOf(
        "getar" to "Getar", "denyut" to "Denyut", "zoompelan" to "Zoom masuk", "zoomkeluar" to "Zoom keluar",
        "cermin" to "Cermin", "kilat" to "Kilat", "hitamputih" to "Hitam putih", "sepia" to "Sepia",
        "invert" to "Invert", "pudarmasuk" to "Pudar masuk", "pudarkeluar" to "Pudar keluar", "blur" to "Blur"
    )

    fun label(type: String): String = TYPES.firstOrNull { it.first == type }?.second ?: type
    fun isMotion(type: String) = type in setOf("getar", "denyut", "zoompelan", "zoomkeluar", "cermin")
    fun isColor(type: String) = type in setOf("kilat", "hitamputih", "sepia", "invert", "pudarmasuk", "pudarkeluar")
    fun isBlur(type: String) = type == "blur"

    /** Hash deterministik 0..1 (kebisingan getar yang sama di pratinjau dan ekspor). */
    fun noise(seed: Int): Float {
        var h = seed * -1640531527
        h = h xor (h ushr 15)
        h *= -2048144789
        h = h xor (h ushr 13)
        h *= -1028477387
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }

    private fun smooth(p: Float): Float = p * p * (3f - 2f * p)

    /** [sx, sy, tx, ty] dalam NDC (tx,ty: +y ke atas). Identitas bila bukan efek gerak. */
    fun motion(type: String, intensity: Float, p: Float, tlMs: Long): FloatArray {
        val i = intensity.coerceIn(0f, 1f)
        return when (type) {
            "getar" -> {
                val amp = 0.012f + 0.03f * i
                val frame = floor(tlMs / 40f).toInt()
                val tx = (noise(frame * 2 + 1) - 0.5f) * 2f * amp
                val ty = (noise(frame * 2 + 2) - 0.5f) * 2f * amp
                val s = 1f + amp * 2.6f
                floatArrayOf(s, s, tx, ty)
            }
            "denyut" -> {
                val k = 0.03f + 0.10f * i
                val wave = 0.5f + 0.5f * sin((2.0 * PI * 2.5 * tlMs / 1000.0 - PI / 2.0)).toFloat()
                val s = 1f + k * wave
                floatArrayOf(s, s, 0f, 0f)
            }
            "zoompelan" -> {
                val s = 1f + (0.08f + 0.35f * i) * smooth(p.coerceIn(0f, 1f))
                floatArrayOf(s, s, 0f, 0f)
            }
            "zoomkeluar" -> {
                val s = 1f + (0.08f + 0.35f * i) * (1f - smooth(p.coerceIn(0f, 1f)))
                floatArrayOf(s, s, 0f, 0f)
            }
            "cermin" -> floatArrayOf(-1f, 1f, 0f, 0f)
            else -> floatArrayOf(1f, 1f, 0f, 0f)
        }
    }

    private fun diag(sr: Float, sg: Float, sb: Float, add: Float): FloatArray {
        val m = FloatArray(16)
        m[0] = sr; m[5] = sg; m[10] = sb; m[15] = 1f
        m[12] = add; m[13] = add; m[14] = add
        return m
    }

    /** Matriks RGB 4×4 kolom-utama (affine: kolom ke-4 = penambahan). Null bila bukan efek warna. */
    fun colorMatrix(type: String, intensity: Float, p: Float, tlMs: Long): FloatArray? {
        val i = intensity.coerceIn(0f, 1f)
        return when (type) {
            "kilat" -> {
                val pulse = max(0f, sin((2.0 * PI * 3.0 * tlMs / 1000.0)).toFloat()).pow(3)
                diag(1f, 1f, 1f, (0.3f + 0.6f * i) * pulse)
            }
            "hitamputih" -> mixMatrix(floatArrayOf(0.2126f, 0.7152f, 0.0722f, 0.2126f, 0.7152f, 0.0722f, 0.2126f, 0.7152f, 0.0722f), i.coerceAtLeast(0.05f))
            "sepia" -> mixMatrix(floatArrayOf(0.393f, 0.769f, 0.189f, 0.349f, 0.686f, 0.168f, 0.272f, 0.534f, 0.131f), i.coerceAtLeast(0.05f))
            "invert" -> {
                val a = i.coerceAtLeast(0.05f)
                diag(1f - 2f * a, 1f - 2f * a, 1f - 2f * a, a)
            }
            "pudarmasuk" -> { val s = p.coerceIn(0f, 1f); diag(s, s, s, 0f) }
            "pudarkeluar" -> { val s = 1f - p.coerceIn(0f, 1f); diag(s, s, s, 0f) }
            else -> null
        }
    }

    /** Campuran identitas dan matriks 3×3 baris-utama [m] sebesar [a] → 4×4 kolom-utama. */
    private fun mixMatrix(m: FloatArray, a: Float): FloatArray {
        val out = FloatArray(16)
        for (row in 0 until 3) for (col in 0 until 3) {
            val identity = if (row == col) 1f else 0f
            out[col * 4 + row] = (1f - a) * identity + a * m[row * 3 + col]
        }
        out[15] = 1f
        return out
    }

    /** Sigma blur (piksel pada bingkai 720p) dengan ramp masuk/keluar; skala sendiri ke ukuran output. */
    fun blurSigma(intensity: Float, p: Float): Float {
        val base = 2f + 28f * intensity.coerceIn(0f, 1f)
        val ramp = min(1f, min(p / 0.12f, (1f - p) / 0.12f)).coerceIn(0f, 1f)
        return max(0.3f, base * ramp)
    }
}

// ============================================================================ transisi

internal object VeTransMath {
    val TYPES: List<Pair<String, String>> = listOf(
        "none" to "Potong", "fade" to "Pudar", "zoom" to "Zoom masuk", "zoomout" to "Zoom keluar",
        "slide" to "Geser kiri", "slideleft" to "Geser kanan", "slideup" to "Geser atas", "slidedown" to "Geser bawah"
    )

    /** Lama transisi (µs) untuk klip berdurasi [clipUs]. */
    fun durationUs(type: String, clipUs: Long): Long = when (type) {
        "fade" -> min(450_000L, clipUs / 2)
        "zoom", "zoomout" -> min(700_000L, clipUs)
        "slide", "slideleft", "slideup", "slidedown" -> min(500_000L, clipUs)
        else -> 0L
    }

    /** [sx, sy, tx, ty] NDC pada progres [p] 0..1 sejak awal klip (klip masuk). */
    fun motion(type: String, p: Float): FloatArray {
        val e = 1f - (1f - p.coerceIn(0f, 1f)).let { it * it }
        return when (type) {
            "zoom" -> { val s = 1.25f - 0.25f * e; floatArrayOf(s, s, 0f, 0f) }
            "zoomout" -> { val s = 0.7f + 0.3f * e; floatArrayOf(s, s, 0f, 0f) }
            "slide" -> floatArrayOf(1f, 1f, (1f - e) * 2f, 0f)
            "slideleft" -> floatArrayOf(1f, 1f, -(1f - e) * 2f, 0f)
            "slideup" -> floatArrayOf(1f, 1f, 0f, -(1f - e) * 2f)
            "slidedown" -> floatArrayOf(1f, 1f, 0f, (1f - e) * 2f)
            else -> floatArrayOf(1f, 1f, 0f, 0f)
        }
    }
}

// ============================================================================ SRT

internal class VeCue(val startMs: Long, val endMs: Long, val text: String)

internal object VeSrt {
    private val TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val TAGS = Regex("""<[^>]+>|\{\\[^}]*\}""")

    private fun ms(h: String, m: String, s: String, f: String): Long {
        val frac = (f + "00").take(3).toLong()
        return h.toLong() * 3_600_000L + m.toLong() * 60_000L + s.toLong() * 1000L + frac
    }

    fun parse(raw: String): List<VeCue> {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = ArrayList<VeCue>()
        for (block in text.split(Regex("\n\\s*\n"))) {
            val lines = block.trim('\n').split('\n')
            val ti = lines.indexOfFirst { TIME.containsMatchIn(it) }
            if (ti < 0) continue
            val m = TIME.find(lines[ti]) ?: continue
            val g = m.groupValues
            val start = ms(g[1], g[2], g[3], g[4])
            val end = ms(g[5], g[6], g[7], g[8])
            val body = lines.drop(ti + 1).joinToString("\n") { TAGS.replace(it, "").trim() }.trim()
            if (body.isEmpty() || end <= start) continue
            cues.add(VeCue(start, end, body))
        }
        return cues.sortedBy { it.startMs }
    }

    fun format(cues: List<VeCue>): String {
        val sb = StringBuilder()
        cues.sortedBy { it.startMs }.forEachIndexed { i, c ->
            sb.append(i + 1).append('\n')
                .append(VeTime.srt(c.startMs)).append(" --> ").append(VeTime.srt(c.endMs)).append('\n')
                .append(c.text.trim()).append("\n\n")
        }
        return sb.toString()
    }
}

// ============================================================================ deteksi hening & adegan

internal object VeSilence {
    /** Energi RMS dB (0 = penuh, -96 = senyap) per jendela [windowMs]. */
    fun rmsDb(pcm: ShortArray, sampleRate: Int, windowMs: Int): FloatArray {
        val win = max(1, sampleRate * windowMs / 1000)
        val n = (pcm.size + win - 1) / win
        val out = FloatArray(n)
        for (k in 0 until n) {
            val from = k * win
            val to = min(pcm.size, from + win)
            var sum = 0.0
            for (i in from until to) { val v = pcm[i] / 32768.0; sum += v * v }
            val rms = sqrt(sum / max(1, to - from))
            out[k] = if (rms <= 1e-5) -96f else (20 * log10(rms)).toFloat().coerceAtLeast(-96f)
        }
        return out
    }

    /**
     * Rentang hening ≥ [minSilenceMs] di bawah [thresholdDb]. Tiap sisi dipotong [padMs]
     * (jeda napas) agar hasil potongan tidak terasa kasar.
     */
    fun silences(db: FloatArray, windowMs: Int, thresholdDb: Float, minSilenceMs: Long, padMs: Long): List<LongRange> {
        val out = ArrayList<LongRange>()
        var runStart = -1
        fun close(endIdx: Int) {
            if (runStart < 0) return
            val s = runStart * windowMs.toLong()
            val e = endIdx * windowMs.toLong()
            if (e - s >= minSilenceMs) {
                val ps = s + padMs
                val pe = e - padMs
                if (pe - ps >= 100L) out.add(ps until pe)
            }
            runStart = -1
        }
        for (i in db.indices) {
            if (db[i] < thresholdDb) { if (runStart < 0) runStart = i } else close(i)
        }
        close(db.size)
        return out
    }

    /** Bagian yang dipertahankan setelah membuang [silences] dari [0, totalMs). */
    fun keepRanges(totalMs: Long, silences: List<LongRange>, minKeepMs: Long = 150L): List<LongRange> {
        val out = ArrayList<LongRange>()
        var cursor = 0L
        for (s in silences.sortedBy { it.first }) {
            if (s.first - cursor >= minKeepMs) out.add(cursor until s.first)
            cursor = max(cursor, s.last + 1)
        }
        if (totalMs - cursor >= minKeepMs) out.add(cursor until totalMs)
        return out
    }
}

internal object VeScene {
    /** Jarak L1 setengah antar dua histogram ternormalisasi (0..1). */
    fun distance(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d += abs(a[i] - b[i])
        return d / 2f
    }

    /**
     * Waktu potong (ms) pada sampel dengan lonjakan perbedaan > [threshold] dan maksimum lokal;
     * jarak antar potongan ≥ [minSceneMs]. [times] = waktu tiap histogram.
     */
    fun cuts(times: LongArray, hists: List<FloatArray>, threshold: Float, minSceneMs: Long): List<Long> {
        if (hists.size < 2) return emptyList()
        val diff = FloatArray(hists.size)
        for (i in 1 until hists.size) diff[i] = distance(hists[i - 1], hists[i])
        val out = ArrayList<Long>()
        var last = times[0]
        for (i in 1 until hists.size) {
            val localMax = diff[i] >= diff[i - 1] && (i == hists.lastIndex || diff[i] > diff[i + 1])
            if (diff[i] > threshold && localMax && times[i] - last >= minSceneMs) {
                out.add(times[i])
                last = times[i]
            }
        }
        return out
    }

    /** Histogram 48 bin (16 luma + 16 R + 16 B, dinormalisasi) dari piksel ARGB. */
    fun histogram(px: IntArray): FloatArray {
        val h = FloatArray(48)
        if (px.isEmpty()) return h
        for (c in px) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            h[y shr 4] += 1f
            h[16 + (r shr 4)] += 1f
            h[32 + (b shr 4)] += 1f
        }
        val norm = px.size * 3f
        for (i in h.indices) h[i] /= norm
        return h
    }
}

// ============================================================================ mixer audio

/** Campur audio stereo-interleaved 16-bit ke satu buffer; kurva fade setengah-kosinus. */
internal object VeMixer {
    const val RATE = 44100

    fun monoToStereo(mono: ShortArray): ShortArray {
        val out = ShortArray(mono.size * 2)
        for (i in mono.indices) { out[2 * i] = mono[i]; out[2 * i + 1] = mono[i] }
        return out
    }

    /** Resample linear stereo-interleaved. */
    fun resampleStereo(src: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate || src.isEmpty()) return src
        val frames = src.size / 2
        val outFrames = max(1L, frames.toLong() * toRate / fromRate).toInt()
        val out = ShortArray(outFrames * 2)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outFrames) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val f = pos - i0
            for (ch in 0..1) {
                val a = src[i0 * 2 + ch].toInt()
                val b = src[i1 * 2 + ch].toInt()
                out[i * 2 + ch] = (a + (b - a) * f).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }

    /** Gain linear pada frame [frame] dari [total] dengan fade masuk/keluar (dalam frame). */
    fun fadeGain(frame: Int, total: Int, fadeIn: Int, fadeOut: Int): Float {
        var g = 1f
        if (fadeIn > 0 && frame < fadeIn) g *= (0.5 - 0.5 * cos(PI * frame / fadeIn)).toFloat()
        val fromEnd = total - 1 - frame
        if (fadeOut > 0 && fromEnd < fadeOut) g *= (0.5 - 0.5 * cos(PI * fromEnd.coerceAtLeast(0) / fadeOut)).toFloat()
        return g
    }

    /**
     * Tambahkan [src] (stereo) ke [out] mulai frame [outStartFrame] (boleh negatif = terpotong di depan).
     * Bagian di luar [out] diabaikan. Hasil dijumlah lalu dibatasi (clip).
     * Fade dihitung terhadap seluruh item: [srcFrameOffset] = indeks frame pertama [src] di dalam item,
     * [itemFrames] = jumlah frame item (default = panjang [src]); dipakai saat item dicampur per blok.
     */
    fun mixInto(
        out: ShortArray, src: ShortArray, outStartFrame: Int, gain: Float, fadeInFrames: Int, fadeOutFrames: Int,
        srcFrameOffset: Int = 0, itemFrames: Int = src.size / 2
    ) {
        val srcFrames = src.size / 2
        val outFrames = out.size / 2
        for (f in 0 until srcFrames) {
            val o = outStartFrame + f
            if (o < 0) continue
            if (o >= outFrames) break
            val g = gain * fadeGain(srcFrameOffset + f, itemFrames, fadeInFrames, fadeOutFrames)
            for (ch in 0..1) {
                val v = out[o * 2 + ch] + (src[f * 2 + ch] * g).toInt()
                out[o * 2 + ch] = v.coerceIn(-32768, 32767).toShort()
            }
        }
    }

    /** Header WAV PCM 16-bit (44 byte). */
    fun wavHeader(frames: Long, rate: Int, channels: Int): ByteArray {
        val dataSize = frames * channels * 2
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataSize).toInt())
        b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray()); b.putInt(16)
        b.putShort(1); b.putShort(channels.toShort()); b.putInt(rate); b.putInt(rate * channels * 2)
        b.putShort((channels * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataSize.toInt())
        return b.array()
    }
}

/** Batas trim (ms sumber) untuk gagang kiri/kanan klip video. */
internal object VeTrim {
    const val MIN_MS = 200L

    /** Trim awal baru (ms sumber) setelah gagang kiri digeser [deltaTimelineMs] (kecepatan diperhitungkan). */
    fun left(trimStart: Long, effectiveEnd: Long, speed: Float, deltaTimelineMs: Long): Long {
        val d = (deltaTimelineMs * speed).toLong()
        val maxStart = (effectiveEnd - (MIN_MS * speed).toLong()).coerceAtLeast(0L)
        return (trimStart + d).coerceIn(0L, maxStart)
    }

    /** Trim akhir baru; mengembalikan 0 bila mencapai ujung sumber. */
    fun right(trimStart: Long, effectiveEnd: Long, sourceDur: Long, speed: Float, deltaTimelineMs: Long): Long {
        val d = (deltaTimelineMs * speed).toLong()
        val minEnd = trimStart + (MIN_MS * speed).toLong()
        val e = (effectiveEnd + d).coerceIn(min(minEnd, sourceDur), sourceDur)
        return if (e >= sourceDur) 0L else e
    }
}


/**
 * Resampler linear streaming stereo-interleaved: dipanggil berulang dengan potongan berurutan
 * dan menghasilkan keluaran kontinu (tanpa celah di batas potongan).
 */
internal class VeResampler(private val fromRate: Int, private val toRate: Int) {
    private val step = fromRate.toDouble() / toRate
    private var pos = 0.0
    private var carryL = 0
    private var carryR = 0
    private var started = false

    fun process(src: ShortArray): ShortArray {
        if (src.isEmpty()) return src
        if (fromRate == toRate) return src
        val n = src.size / 2
        // Penyangga primitif (tanpa boxing): batas atas jumlah frame keluaran.
        val out = ShortArray((((n - pos) / step).toInt() + 3) * 2)
        var o = 0
        var p = pos
        while (p < n - 1) {
            val i0 = floor(p).toInt()
            val f = p - i0
            val l0: Int; val r0: Int
            if (i0 < 0) { l0 = carryL; r0 = carryR } else { l0 = src[i0 * 2].toInt(); r0 = src[i0 * 2 + 1].toInt() }
            val i1 = i0 + 1
            val l1 = src[i1 * 2].toInt()
            val r1 = src[i1 * 2 + 1].toInt()
            out[o++] = (l0 + (l1 - l0) * f).toInt().coerceIn(-32768, 32767).toShort()
            out[o++] = (r0 + (r1 - r0) * f).toInt().coerceIn(-32768, 32767).toShort()
            p += step
        }
        pos = p - n
        carryL = src[(n - 1) * 2].toInt()
        carryR = src[(n - 1) * 2 + 1].toInt()
        started = true
        return out.copyOf(o)
    }
}

// ============================================================================ rencana potong & komposisi transform

/** Hasil pemotongan klip menjadi potongan: [start, end) dalam ms SUMBER; end == 0 berarti "sampai habis". */
internal class VePiece(val trimStartMs: Long, val trimEndMs: Long)

internal object VeCutPlan {
    /** Potongan dari rentang yang dipertahankan (relatif ke awal trim klip). */
    fun fromKeep(trimStartMs: Long, keep: List<LongRange>): List<VePiece> =
        keep.map { VePiece(trimStartMs + it.first, trimStartMs + it.last + 1) }

    /**
     * Potongan dari titik potong [cuts] (ms relatif ke awal trim, menaik). Potongan terakhir mempertahankan
     * [originalTrimEndMs] (0 = sampai habis) agar tidak ada perubahan yang tak perlu di ujung sumber.
     */
    fun fromCuts(trimStartMs: Long, effectiveEndMs: Long, originalTrimEndMs: Long, cuts: List<Long>): List<VePiece> {
        val len = effectiveEndMs - trimStartMs
        val bounds = ArrayList<Long>()
        bounds.add(0L)
        for (c in cuts.sorted()) if (c > bounds.last() && c < len) bounds.add(c)
        bounds.add(len)
        val out = ArrayList<VePiece>()
        for (i in 0 until bounds.size - 1) {
            val last = i == bounds.size - 2
            out.add(VePiece(trimStartMs + bounds[i], if (last) originalTrimEndMs else trimStartMs + bounds[i + 1]))
        }
        return out
    }
}

/** Komposisi transform [sx, sy, tx, ty] (NDC, +y ke atas) yang sama dipakai pratinjau dan ekspor. */
internal object VeFxCompose {
    /** Transform [a] lalu [b]: p' = Sb·(Sa·p + Ta) + Tb. */
    fun then(a: FloatArray, b: FloatArray): FloatArray =
        floatArrayOf(b[0] * a[0], b[1] * a[1], b[0] * a[2] + b[2], b[1] * a[3] + b[3])

    /** Zoom (1..4) di sekitar pusat + geser aman (-1..1 × (zoom-1)); +panX ke kanan, +panY ke bawah; cermin horizontal. */
    fun clipTransform(zoom: Float, flipH: Boolean, panX: Float, panY: Float): FloatArray {
        val z = zoom.coerceIn(1f, 4f)
        val flip = if (flipH) -1f else 1f
        return floatArrayOf(z * flip, z, panX.coerceIn(-1f, 1f) * (z - 1f), -panY.coerceIn(-1f, 1f) * (z - 1f))
    }

    val IDENTITY: FloatArray get() = floatArrayOf(1f, 1f, 0f, 0f)
}
