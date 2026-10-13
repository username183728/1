package com.example.aidetest

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Logika murni timeline multi-layer & animasi teks Animation Studio — tanpa API Android, diuji di JVM
 * (AnimTextTest):
 *  - AnLayerOpt  : pengaturan animasi per layer (rentang aktif in/out, sembunyi, kunci, animasi teks)
 *  - AnTextAnim  : animasi teks per huruf (ketik, fade/pop per huruf, pantul, gelombang, tracking, jalur, judul, subjudul)
 *  - AnTextMath  : pewaktuan per huruf (progres, jumlah huruf tampak, offset) — dipakai AnTextDraw (AnimRender.kt)
 *  - AnTimeline  : snap 50 ms, geser keyframe, ubah rentang aktif
 *  - AnLayerCodec: codec JSON "layers" (opsional; proyek lama tanpa field ini = rentang penuh)
 */

// ====================================================================== animasi teks

/** Animasi teks per huruf untuk layer TEXT. [amount] = intensitas 0..2 (1 = standar). */
internal data class AnTextAnim(
    val type: String,
    val startMs: Long = 0L,
    val durMs: Long = 1_200L,
    val amount: Float = 1f,
    /** Bentuk jalur untuk [AnTextMath.PATH]: curve | arc | circle. */
    val path: String = AnTextMath.PATH_CURVE
)

internal object AnTextMath {
    const val TYPEWRITER = "typewriter"
    const val FADE = "fade_letters"
    const val POP = "pop_letters"
    const val BOUNCE = "bounce"
    const val WAVE = "wave"
    const val TRACKING = "tracking"
    const val PATH = "path"
    const val TITLE = "title"
    const val SUBTITLE = "subtitle"

    const val PATH_CURVE = "curve"
    const val PATH_ARC = "arc"
    const val PATH_CIRCLE = "circle"

    val ALL = listOf(
        TYPEWRITER to "Ketik", FADE to "Fade huruf", POP to "Pop huruf", BOUNCE to "Pantul",
        WAVE to "Gelombang", TRACKING to "Tracking", PATH to "Jalur", TITLE to "Judul", SUBTITLE to "Subjudul"
    )
    val PATHS = listOf(PATH_CURVE to "Lengkung", PATH_ARC to "Busur", PATH_CIRCLE to "Lingkaran")

    fun label(type: String): String = ALL.firstOrNull { it.first == type }?.second ?: "Tanpa"
    fun validType(t: String?): String? = t?.takeIf { s -> ALL.any { it.first == s } }
    fun validPath(p: String?): String = if (p == PATH_ARC || p == PATH_CIRCLE) p else PATH_CURVE

    /** Periode gelombang (ms) & porsi durasi yang dipakai untuk jeda antarhuruf. */
    const val WAVE_PERIOD = 1_200L
    const val SPREAD = 0.6f

    /** Rentang indeks per glyph (code point; pasangan surrogate tidak dipecah). */
    fun glyphs(s: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < s.length) {
            val n = Character.charCount(s.codePointAt(i))
            out.add(i until i + n)
            i += n
        }
        return out
    }

    /** Progres keseluruhan 0..1 animasi di waktu [t]. */
    fun overall(t: Long, start: Long, dur: Long): Float =
        if (dur <= 0L) (if (t >= start) 1f else 0f) else ((t - start).toFloat() / dur).coerceIn(0f, 1f)

    /**
     * Progres 0..1 huruf ke-[i] dari [n]: huruf mulai berurutan dalam [SPREAD]×durasi pertama,
     * masing-masing berjalan (1−SPREAD)×durasi. Huruf terakhir selesai tepat di start+dur.
     */
    fun charProgress(i: Int, n: Int, t: Long, start: Long, dur: Long, spread: Float = SPREAD): Float {
        if (dur <= 0L || n <= 0) return if (t >= start) 1f else 0f
        val sp = if (n <= 1) 0f else spread.coerceIn(0f, 0.95f)
        val window = (dur * (1f - sp)).coerceAtLeast(1f)
        val offset = if (n <= 1) 0f else dur * sp * i / (n - 1)
        return ((t - start - offset) / window).coerceIn(0f, 1f)
    }

    /** Jumlah huruf tampak untuk efek ketik: huruf pertama muncul tepat di [start], semua di start+dur. */
    fun visibleCount(n: Int, t: Long, start: Long, dur: Long): Int {
        if (n <= 0 || t < start) return 0
        if (dur <= 0L || t >= start + dur) return n
        return min(n, floor((t - start).toDouble() * n / dur).toInt() + 1)
    }

    fun easeOutCubic(p: Float): Float { val q = 1f - p.coerceIn(0f, 1f); return 1f - q * q * q }

    /** Overshoot ("back"): 0 → ±1.1 → 1. */
    fun easeOutBack(p: Float): Float {
        val x = p.coerceIn(0f, 1f) - 1f
        val c1 = 1.70158f
        return 1f + (c1 + 1f) * x * x * x + c1 * x * x
    }

    /** Pantulan standar (Penner): 0 → 1 dengan tiga pantulan mengecil. */
    fun easeOutBounce(p: Float): Float {
        var x = p.coerceIn(0f, 1f)
        val n1 = 7.5625f
        val d1 = 2.75f
        return when {
            x < 1f / d1 -> n1 * x * x
            x < 2f / d1 -> { x -= 1.5f / d1; n1 * x * x + 0.75f }
            x < 2.5f / d1 -> { x -= 2.25f / d1; n1 * x * x + 0.9375f }
            else -> { x -= 2.625f / d1; n1 * x * x + 0.984375f }
        }
    }

    /** Efek satu huruf: geser (px kanvas), skala di pusat huruf, opasitas ×. */
    class Fx(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f, val alpha: Float = 1f)

    val NONE = Fx()

    /**
     * Efek huruf ke-[i] dari [n] untuk animasi [a] pada waktu [t]; [size] = ukuran huruf (px kanvas).
     * Teks diam (sebelum mulai efek berkelanjutan / setelah selesai) = [NONE].
     */
    fun fx(a: AnTextAnim, i: Int, n: Int, t: Long, size: Float): Fx {
        val amt = a.amount.coerceIn(0f, 2f)
        return when (a.type) {
            TYPEWRITER, PATH -> if (i < visibleCount(n, t, a.startMs, a.durMs)) NONE else Fx(alpha = 0f)
            FADE -> Fx(alpha = easeOutCubic(charProgress(i, n, t, a.startMs, a.durMs)))
            POP -> {
                val p = charProgress(i, n, t, a.startMs, a.durMs)
                Fx(scale = 1f + (easeOutBack(p) - 1f) * amt.coerceAtLeast(0.2f), alpha = min(1f, p * 3f))
            }
            BOUNCE -> {
                val p = charProgress(i, n, t, a.startMs, a.durMs)
                Fx(dy = -size * 1.2f * amt * (1f - easeOutBounce(p)), alpha = min(1f, p * 4f))
            }
            WAVE -> Fx(dy = waveOffset(i, t, a.startMs, a.durMs, size * 0.25f * amt))
            TITLE -> {
                val p = charProgress(i, n, t, a.startMs, a.durMs, 0.45f)
                Fx(dy = size * 0.8f * amt * (1f - easeOutCubic(p)), alpha = easeOutCubic(p))
            }
            SUBTITLE -> {
                val p = overall(t, a.startMs, a.durMs)
                Fx(dy = size * 0.5f * amt * (1f - easeOutCubic(p)), alpha = easeOutCubic(p))
            }
            TRACKING -> Fx(alpha = min(1f, overall(t, a.startMs, a.durMs) * 2f))
            else -> NONE
        }
    }

    /**
     * Gelombang sinus per huruf (fase bergeser 0.6 rad/huruf) selama [start]..start+dur;
     * amplitudo naik/turun 250 ms di kedua ujung agar tidak melompat.
     */
    fun waveOffset(i: Int, t: Long, start: Long, dur: Long, amp: Float): Float {
        if (t < start || t > start + dur || dur <= 0L) return 0f
        val ramp = min(250f, dur / 2f).coerceAtLeast(1f)
        val env = min(1f, min((t - start) / ramp, (start + dur - t) / ramp))
        val ph = 2.0 * PI * (t - start) / WAVE_PERIOD - i * 0.6
        return (amp * env * sin(ph)).toFloat()
    }

    /** Tambahan letterSpacing (em) untuk efek tracking/judul: lebar → normal. */
    fun extraSpacing(a: AnTextAnim, t: Long): Float {
        val amt = a.amount.coerceIn(0f, 2f)
        val p = overall(t, a.startMs, a.durMs)
        return when (a.type) {
            TRACKING -> 0.5f * amt * (1f - easeOutCubic(p))
            TITLE -> 0.25f * amt * (1f - easeOutCubic(p))
            else -> 0f
        }
    }

    /** Awal x baris teks berdasarkan perataan (0 kiri, 1 tengah, 2 kanan). */
    fun lineX(boxX: Float, boxW: Float, lineW: Float, align: Int): Float = when (align) {
        1 -> boxX + (boxW - lineW) / 2f
        2 -> boxX + boxW - lineW
        else -> boxX
    }

    /** Offset horizontal teks di jalur sepanjang [pathLen] (perataan kiri/tengah/kanan, tak pernah negatif). */
    fun pathOffset(pathLen: Float, textW: Float, align: Int): Float = when (align) {
        1 -> max(0f, (pathLen - textW) / 2f)
        2 -> max(0f, pathLen - textW)
        else -> 0f
    }
}

// ====================================================================== opsi layer & timeline

/**
 * Pengaturan animasi per layer. [outMs] < 0 = sampai akhir animasi. Layer tidak digambar di luar
 * [inMs]..out. [hidden]/[locked] hanya berlaku di Animasi (desain Foto Studio tidak berubah).
 */
internal data class AnLayerOpt(
    val inMs: Long = 0L,
    val outMs: Long = -1L,
    val hidden: Boolean = false,
    val locked: Boolean = false,
    val text: AnTextAnim? = null,
    /** Efek berwaktu (AnimFx.kt); kosong = tanpa efek. */
    val effects: List<AnEffect> = emptyList(),
    /** Transisi masuk terhadap layer yang berakhir di titik masuk layer ini. */
    val trans: AnTransition? = null
) {
    fun isDefault(): Boolean = inMs <= 0L && outMs < 0L && !hidden && !locked && text == null && effects.isEmpty() && trans == null
    fun outOr(durationMs: Long): Long = if (outMs < 0L) durationMs else min(outMs, durationMs)
    fun activeAt(t: Long, durationMs: Long): Boolean = !hidden && t >= inMs && t <= outOr(durationMs)
    fun hasRange(durationMs: Long): Boolean = inMs > 0L || (outMs in 0L until durationMs)

    companion object { val DEFAULT = AnLayerOpt() }
}

internal object AnTimeline {
    const val SNAP = 50L
    /** Rentang aktif minimum. */
    const val MIN_RANGE = 100L

    fun snap(ms: Long, step: Long = SNAP): Long = Math.floorDiv(ms + step / 2, step) * step

    /**
     * Pindahkan keyframe ke-[index] ke [newT] (snap 50 ms, dibatasi 0..durasi).
     * @return daftar baru terurut, atau null bila waktu tujuan sudah dipakai keyframe lain.
     */
    fun moveKey(keys: List<AnKey>, index: Int, newT: Long, durationMs: Long): List<AnKey>? {
        if (index !in keys.indices) return null
        val t = snap(newT).coerceIn(0L, durationMs)
        if (keys.withIndex().any { (i, k) -> i != index && abs(k.t - t) < SNAP / 2 }) return null
        return keys.mapIndexed { i, k -> if (i == index) k.copy(t = t) else k }.sortedBy { it.t }
    }

    /** Ubah titik masuk (snap, ≥ 0, ≤ out − MIN_RANGE). */
    fun withIn(o: AnLayerOpt, ms: Long, durationMs: Long): AnLayerOpt {
        val out = o.outOr(durationMs)
        return o.copy(inMs = snap(ms).coerceIn(0L, max(0L, out - MIN_RANGE)))
    }

    /** Ubah titik keluar (snap, ≥ in + MIN_RANGE); di akhir durasi = −1 (ikut durasi). */
    fun withOut(o: AnLayerOpt, ms: Long, durationMs: Long): AnLayerOpt {
        val v = snap(ms).coerceIn(min(durationMs, o.inMs + MIN_RANGE), durationMs)
        return o.copy(outMs = if (v >= durationMs) -1L else v)
    }

    /** Lebar 1 detik (px) setelah zoom; [fitPx] = lebar yang memuat seluruh durasi. */
    fun pxPerMs(fitPx: Float, durationMs: Long, zoom: Float): Float = fitPx / durationMs.coerceAtLeast(1L) * zoom.coerceIn(1f, 40f)
}

// ====================================================================== codec

internal object AnLayerCodec {
    fun encodeText(a: AnTextAnim): Map<String, Any?> = linkedMapOf(
        "type" to a.type, "start" to a.startMs, "dur" to a.durMs,
        "amt" to (if (a.amount.isNaN()) 1.0 else a.amount.toDouble()), "path" to a.path
    )

    fun decodeText(m: Map<String, Any?>?): AnTextAnim? {
        if (m == null) return null
        val type = AnTextMath.validType(m["type"] as? String) ?: return null
        return AnTextAnim(
            type = type,
            startMs = ((m["start"] as? Number)?.toLong() ?: 0L).coerceIn(0L, AnMath.MAX_DURATION),
            durMs = ((m["dur"] as? Number)?.toLong() ?: 1_200L).coerceIn(0L, AnMath.MAX_DURATION),
            amount = ((m["amt"] as? Number)?.toFloat()?.takeIf { !it.isNaN() && !it.isInfinite() } ?: 1f).coerceIn(0f, 2f),
            path = AnTextMath.validPath(m["path"] as? String)
        )
    }

    fun encode(opts: Map<String, AnLayerOpt>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((id, o) in opts) {
            if (o.isDefault()) continue
            val m = linkedMapOf<String, Any?>("in" to o.inMs, "out" to o.outMs)
            if (o.hidden) m["hidden"] = true
            if (o.locked) m["locked"] = true
            o.text?.let { m["text"] = encodeText(it) }
            if (o.effects.isNotEmpty()) m["fx"] = AnFxCodec.encodeEffects(o.effects)
            o.trans?.let { m["tr"] = AnFxCodec.encodeTrans(it) }
            out[id] = m
        }
        return out
    }

    /** Tanpa field / rusak → kosong (semua layer rentang penuh, tanpa animasi teks). */
    fun decode(m: Map<String, Any?>?): MutableMap<String, AnLayerOpt> {
        val out = LinkedHashMap<String, AnLayerOpt>()
        if (m == null) return out
        for ((id, raw) in m) {
            @Suppress("UNCHECKED_CAST")
            val o = raw as? Map<String, Any?> ?: continue
            val inMs = ((o["in"] as? Number)?.toLong() ?: 0L).coerceIn(0L, AnMath.MAX_DURATION)
            var outMs = (o["out"] as? Number)?.toLong() ?: -1L
            if (outMs >= 0L && outMs <= inMs) outMs = -1L
            @Suppress("UNCHECKED_CAST")
            val opt = AnLayerOpt(
                inMs = inMs, outMs = if (outMs < 0L) -1L else outMs.coerceAtMost(AnMath.MAX_DURATION),
                hidden = o["hidden"] == true, locked = o["locked"] == true,
                text = decodeText(o["text"] as? Map<String, Any?>),
                effects = AnFxCodec.decodeEffects(o["fx"]),
                trans = AnFxCodec.decodeTrans(o["tr"] as? Map<String, Any?>)
            )
            if (!opt.isDefault()) out[id] = opt
        }
        return out
    }
}
