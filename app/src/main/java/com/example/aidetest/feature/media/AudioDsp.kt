package com.example.aidetest

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pemrosesan PCM 16-bit mono murni (tanpa Android, diuji di AudioDspTest).
 * Semua operasi di [AudioDsp] TIDAK mengubah array masukan — hasilnya array baru — sehingga
 * riwayat undo Audio Editor cukup menyimpan referensi array tanpa menyalin data audio.
 */

internal fun applyGain(pcm: ShortArray, gainDb: Float): ShortArray {
    if (abs(gainDb) < 0.05f) return pcm
    val factor = Math.pow(10.0, gainDb / 20.0).toFloat()
    val out = ShortArray(pcm.size)
    for (i in pcm.indices) out[i] = clip16(pcm[i] * factor)
    return out
}

internal fun normalizePcm(pcm: ShortArray): ShortArray {
    var peak = 1
    for (s in pcm) peak = max(peak, abs(s.toInt()))
    if (peak >= 32000) return pcm
    val factor = 32000f / peak
    val out = ShortArray(pcm.size)
    for (i in pcm.indices) out[i] = clip16(pcm[i] * factor)
    return out
}

/** Fade di tempat (dipertahankan untuk kompatibilitas pemanggil lama). */
internal fun applyFade(pcm: ShortArray, fadeIn: Boolean, fraction: Float) {
    if (pcm.isEmpty()) return
    val n = (pcm.size * fraction).toInt().coerceIn(1, pcm.size)
    if (fadeIn) {
        for (i in 0 until n) pcm[i] = (pcm[i] * (i.toFloat() / n)).toInt().toShort()
    } else {
        val start = pcm.size - n
        for (i in 0 until n) {
            val idx = start + i
            pcm[idx] = (pcm[idx] * (1f - i.toFloat() / n)).toInt().toShort()
        }
    }
}

internal fun mixPcm(base: ShortArray, over: ShortArray): ShortArray {
    val out = base.copyOf()
    val n = min(out.size, over.size)
    for (i in 0 until n) out[i] = ((out[i].toInt() + over[i].toInt()) / 2).toShort()
    return out
}

private fun clip16(v: Float): Short = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

internal object AudioDsp {

    private fun range(size: Int, from: Int, to: Int): Pair<Int, Int> {
        val a = from.coerceIn(0, size)
        val b = to.coerceIn(a, size)
        return a to b
    }

    fun slice(pcm: ShortArray, from: Int, to: Int): ShortArray {
        val (a, b) = range(pcm.size, from, to)
        return pcm.copyOfRange(a, b)
    }

    /** Buang rentang [from, to); sisi kiri & kanan disambung. */
    fun deleteRange(pcm: ShortArray, from: Int, to: Int): ShortArray {
        val (a, b) = range(pcm.size, from, to)
        val out = ShortArray(pcm.size - (b - a))
        System.arraycopy(pcm, 0, out, 0, a)
        System.arraycopy(pcm, b, out, a, pcm.size - b)
        return out
    }

    fun silenceRange(pcm: ShortArray, from: Int, to: Int): ShortArray {
        val (a, b) = range(pcm.size, from, to)
        val out = pcm.copyOf()
        java.util.Arrays.fill(out, a, b, 0.toShort())
        return out
    }

    fun gainRange(pcm: ShortArray, from: Int, to: Int, gainDb: Float): ShortArray {
        val (a, b) = range(pcm.size, from, to)
        val f = Math.pow(10.0, gainDb / 20.0).toFloat()
        val out = pcm.copyOf()
        for (i in a until b) out[i] = clip16(pcm[i] * f)
        return out
    }

    /**
     * Fade pada rentang [from, to): fade in naik 0→1, fade out turun 1→0.
     * Kurva setengah-kosinus terdengar lebih halus daripada linear.
     */
    fun fadeRange(pcm: ShortArray, from: Int, to: Int, fadeIn: Boolean): ShortArray {
        val (a, b) = range(pcm.size, from, to)
        val out = pcm.copyOf()
        val n = b - a
        if (n <= 0) return out
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val g = if (fadeIn) 0.5 - 0.5 * Math.cos(Math.PI * t) else 0.5 + 0.5 * Math.cos(Math.PI * t)
            out[a + i] = clip16((pcm[a + i] * g).toFloat())
        }
        return out
    }

    fun concat(parts: List<ShortArray>): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    /** Resample linear (cukup untuk menyamakan sample rate saat menggabung klip). */
    fun resample(pcm: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate || pcm.isEmpty()) return pcm
        val outLen = max(1L, pcm.size.toLong() * toRate / fromRate).toInt()
        val out = ShortArray(outLen)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(pcm.size - 1)
            val i1 = (i0 + 1).coerceAtMost(pcm.size - 1)
            val frac = pos - i0
            out[i] = clip16((pcm[i0] * (1 - frac) + pcm[i1] * frac).toFloat())
        }
        return out
    }

    /**
     * Campur [over] ke [base] mulai sampel [offset] (dijumlah + dibatasi, bukan dirata-rata, sehingga
     * volume asli tidak turun). Hasil diperpanjang bila [over] melewati akhir [base].
     */
    fun mixAt(base: ShortArray, over: ShortArray, offset: Int, overGainDb: Float = 0f): ShortArray {
        val off = offset.coerceAtLeast(0)
        val out = base.copyOf(max(base.size, off + over.size))
        val f = Math.pow(10.0, overGainDb / 20.0).toFloat()
        for (i in over.indices) {
            val j = off + i
            out[j] = clip16(out[j] + over[i] * f)
        }
        return out
    }

    /** Puncak absolut per kelompok [bucket] sampel — dipakai untuk menggambar waveform dengan cepat. */
    fun peaks(pcm: ShortArray, bucket: Int): ShortArray {
        val b = bucket.coerceAtLeast(1)
        val n = (pcm.size + b - 1) / b
        val out = ShortArray(n)
        for (k in 0 until n) {
            var peak = 0
            val end = min(pcm.size, (k + 1) * b)
            var i = k * b
            while (i < end) {
                val v = abs(pcm[i].toInt())
                if (v > peak) peak = v
                i++
            }
            out[k] = peak.coerceAtMost(Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /** Level puncak dalam dBFS (0 = maksimum, -inf ≈ -96). */
    fun peakDb(pcm: ShortArray, from: Int = 0, to: Int = pcm.size): Float {
        val (a, b) = range(pcm.size, from, to)
        var peak = 0
        for (i in a until b) peak = max(peak, abs(pcm[i].toInt()))
        return if (peak == 0) -96f else (20 * log10(peak / 32768.0)).toFloat()
    }

    fun rmsDb(pcm: ShortArray, from: Int = 0, to: Int = pcm.size): Float {
        val (a, b) = range(pcm.size, from, to)
        if (b <= a) return -96f
        var sum = 0.0
        for (i in a until b) { val v = pcm[i] / 32768.0; sum += v * v }
        val rms = sqrt(sum / (b - a))
        return if (rms <= 0.0) -96f else (20 * log10(rms)).toFloat().coerceAtLeast(-96f)
    }

    /** Format waktu m:ss.d (atau h:mm:ss untuk durasi panjang). */
    fun fmtTime(ms: Long, tenths: Boolean = true): String {
        val t = ms.coerceAtLeast(0L)
        val totalSec = t / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            tenths -> String.format(Locale.US, "%d:%02d.%d", m, s, (t % 1000) / 100)
            else -> String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    /** Sample rate yang didukung encoder AAC; sisanya disamakan ke yang terdekat. */
    fun aacSampleRate(sr: Int): Int {
        val ok = intArrayOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)
        if (sr in ok) return sr
        return ok.minByOrNull { abs(it - sr) } ?: 44100
    }
}
