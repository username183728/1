package com.example.aidetest

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Efek & transisi per layer Animation Studio — logika murni tanpa API Android, diuji di JVM (AnimFxTest).
 * Semua nilai hanya bergantung pada waktu frame t (ms) + seed, sehingga ekspor MP4/GIF identik dengan pratinjau.
 *  - AnEffect     : satu efek pada layer (jenis, mulai, lama, intensitas, varian, area kanvas)
 *  - AnTransition : transisi masuk layer (crossfade/geser/zoom/wipe/blur) terhadap layer yang berakhir di titik masuknya
 *  - AnFxMath     : selubung, noise ber-seed, guncangan, partikel, glitch, motion blur, kilat, cahaya
 *  - AnTransMath  : pasangan layer keluar/masuk & progres transisi
 *  - AnFxCodec    : codec JSON (field opsional "fx"/"tr" di opsi layer)
 */

internal data class AnEffect(
    val type: String,
    val startMs: Long = 0L,
    val durMs: Long = 1_000L,
    /** 0..2 (1 = standar). */
    val amount: Float = 1f,
    /** Varian: partikel 0 kilau / 1 konfeti / 2 salju; cahaya 0 light leak / 1 glow. */
    val variant: Int = 0,
    /** Partikel/cahaya di seluruh kanvas (bukan sekitar layer). */
    val global: Boolean = false,
    val seed: Int = 1
) {
    fun activeAt(t: Long): Boolean = t >= startMs && t <= startMs + durMs
}

internal data class AnTransition(val type: String, val durMs: Long = 600L)

internal object AnFxMath {
    const val MOTION_BLUR = "motion_blur"
    const val PARTICLES = "particles"
    const val LIGHT = "light"
    const val FLASH = "flash"
    const val SHAKE = "shake"
    const val GLITCH = "glitch"
    const val BLUR = "blur"

    val ALL = listOf(
        MOTION_BLUR to "Motion blur", PARTICLES to "Partikel", LIGHT to "Cahaya", FLASH to "Kilat",
        SHAKE to "Guncang", GLITCH to "Glitch", BLUR to "Blur dinamis"
    )
    val PARTICLE_KINDS = listOf("0" to "Kilau", "1" to "Konfeti", "2" to "Salju")
    val LIGHT_KINDS = listOf("0" to "Light leak", "1" to "Glow")

    const val MAX_PARTICLES = 80
    const val MAX_EFFECTS = 8
    const val MOTION_SAMPLES = 5

    fun label(type: String): String = ALL.firstOrNull { it.first == type }?.second ?: type
    fun validType(t: String?): String? = t?.takeIf { s -> ALL.any { it.first == s } }

    fun defaultMs(type: String): Long = when (type) {
        FLASH -> 400L
        SHAKE -> 800L
        GLITCH -> 1_000L
        else -> 1_500L
    }

    /** Selubung 0..1: naik/turun linear [ramp] ms di kedua ujung rentang; 0 di luar. */
    fun envelope(t: Long, start: Long, dur: Long, ramp: Long = 200L): Float {
        if (dur <= 0L || t < start || t > start + dur) return 0f
        val r = min(ramp.toFloat(), dur / 2f).coerceAtLeast(1f)
        return min(1f, min((t - start) / r, (start + dur - t) / r)).coerceIn(0f, 1f)
    }

    /** Hash integer → 0..1 (deterministik, tanpa Random global). */
    fun hash(seed: Int, a: Int, b: Int = 0): Float {
        var h = seed * 374761393 + a * 668265263 + b * 1274126177 + 0x27d4eb2d
        h = (h xor (h ushr 15)) * -2048144789
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7fffffff) / 2147483647f
    }

    /** Value noise 1D halus (−1..1) di posisi [x]. */
    fun noise(seed: Int, x: Float, ch: Int = 0): Float {
        val i = floor(x).toInt()
        val f = x - i
        val u = f * f * (3f - 2f * f)
        val a = hash(seed, i, ch) * 2f - 1f
        val b = hash(seed, i + 1, ch) * 2f - 1f
        return a + (b - a) * u
    }

    /** Guncangan kamera: (dx, dy, rot°) untuk kanvas cw×ch. Frekuensi ~12 Hz. */
    fun shake(e: AnEffect, t: Long, cw: Float, ch: Float): FloatArray {
        val env = envelope(t, e.startMs, e.durMs, 120L) * e.amount.coerceIn(0f, 2f)
        if (env <= 0f) return floatArrayOf(0f, 0f, 0f)
        val x = (t - e.startMs) / 1000f * 12f
        val amp = min(cw, ch) * 0.025f * env
        return floatArrayOf(noise(e.seed, x, 1) * amp, noise(e.seed, x, 2) * amp, noise(e.seed, x, 3) * 1.5f * env)
    }

    /** Alfa kilat putih: puncak cepat (20% pertama) lalu meluruh. */
    fun flash(e: AnEffect, t: Long): Float {
        if (!e.activeAt(t) || e.durMs <= 0L) return 0f
        val p = (t - e.startMs).toFloat() / e.durMs
        val v = if (p < 0.2f) p / 0.2f else 1f - (p - 0.2f) / 0.8f
        return (v * v * min(1f, e.amount.coerceIn(0f, 2f))).coerceIn(0f, 1f)
    }

    /** Besar blur dinamis 0..1: naik ke puncak di tengah rentang lalu turun (bentuk sinus). */
    fun blurAmount(e: AnEffect, t: Long): Float {
        if (!e.activeAt(t) || e.durMs <= 0L) return 0f
        val p = (t - e.startMs).toFloat() / e.durMs
        return (sin(PI * p).toFloat() * e.amount.coerceIn(0f, 2f) / 2f).coerceIn(0f, 1f)
    }

    /** Faktor perkecil untuk blur downscale-upscale: 1 (tajam) .. 1/24. */
    fun blurFactor(amount: Float): Float = 1f / (1f + 23f * amount.coerceIn(0f, 1f))

    /**
     * Motion blur: offset waktu (ms, ≤ 0 = ke belakang) & alfa tiap salinan bayangan. Bentang = 1 frame × intensitas.
     * Salinan lebih jauh = lebih pudar; total alfa ≤ ~0,9.
     */
    fun motionSamples(e: AnEffect, frameMs: Long): List<Pair<Long, Float>> {
        val span = frameMs * 1.5f * e.amount.coerceIn(0f, 2f)
        if (span < 1f) return emptyList()
        val out = ArrayList<Pair<Long, Float>>(MOTION_SAMPLES)
        for (i in 1..MOTION_SAMPLES) {
            val dt = -(span * i / MOTION_SAMPLES).toLong()
            out.add(dt to 0.35f * (1f - (i - 1f) / MOTION_SAMPLES))
        }
        return out
    }

    /** Kecepatan perpindahan (px/ms) dua keadaan — motion blur hanya digambar bila objek bergerak. */
    fun moving(a: AnState, b: AnState, eps: Float = 0.05f): Boolean =
        abs(a.dx - b.dx) + abs(a.dy - b.dy) > eps || abs(a.rot - b.rot) > eps || abs(a.scale - b.scale) > 0.001f

    /** Satu partikel: posisi (relatif area 0..1), ukuran (px kanvas), alfa, sudut, warna (indeks). */
    class Particle(val x: Float, val y: Float, val size: Float, val alpha: Float, val angle: Float, val color: Int)

    fun particleCount(e: AnEffect): Int = ((30f * e.amount.coerceIn(0f, 2f) + 6f) * (if (e.global) 1f else 0.6f)).toInt().coerceIn(0, MAX_PARTICLES)

    /**
     * Partikel pada waktu [t] untuk area berukuran [areaMin] (sisi terpendek, px kanvas).
     * Kilau: berkedip di tempat; konfeti: jatuh + berputar; salju: jatuh pelan + goyang. Posisi berulang (mod 1).
     */
    fun particles(e: AnEffect, t: Long, areaMin: Float): List<Particle> {
        val env = envelope(t, e.startMs, e.durMs, 250L)
        if (env <= 0f) return emptyList()
        val n = particleCount(e)
        val sec = (t - e.startMs) / 1000f
        val out = ArrayList<Particle>(n)
        for (i in 0 until n) {
            val r0 = hash(e.seed, i, 11)
            val r1 = hash(e.seed, i, 12)
            val r2 = hash(e.seed, i, 13)
            val r3 = hash(e.seed, i, 14)
            when (e.variant) {
                1 -> {
                    val speed = 0.25f + 0.35f * r2
                    val y = frac(r1 + sec * speed)
                    val x = frac(r0 + 0.04f * sin(sec * 3f + i))
                    out.add(Particle(x, y, areaMin * (0.012f + 0.012f * r3), env, (sec * 360f * (0.5f + r2) + r3 * 360f) % 360f, i % 5))
                }
                2 -> {
                    val speed = 0.06f + 0.1f * r2
                    val y = frac(r1 + sec * speed)
                    val x = frac(r0 + 0.03f * sin(sec * 1.5f + i * 0.7f))
                    out.add(Particle(x, y, areaMin * (0.004f + 0.008f * r3), env * (0.6f + 0.4f * r3), 0f, 0))
                }
                else -> {
                    val ph = sec * (1.5f + 2f * r2) + r3 * 6.2831855f
                    val tw = 0.5f + 0.5f * sin(ph)
                    out.add(Particle(r0, r1, areaMin * (0.01f + 0.02f * r3) * (0.4f + 0.6f * tw), env * tw, 45f * r2, 0))
                }
            }
        }
        return out
    }

    private fun frac(v: Float): Float = v - floor(v)

    /** Irisan glitch (berubah tiap ~66 ms): daftar [y0, y1 (0..1), offset x (× lebar)]. */
    fun glitchSlices(e: AnEffect, t: Long): List<FloatArray> {
        val env = envelope(t, e.startMs, e.durMs, 100L) * e.amount.coerceIn(0f, 2f)
        if (env <= 0f) return emptyList()
        val step = ((t - e.startMs) / 66L).toInt()
        val n = 3 + (hash(e.seed, step, 21) * 4f).toInt()
        val out = ArrayList<FloatArray>(n)
        for (i in 0 until n) {
            if (hash(e.seed, step, 30 + i) > 0.35f + 0.3f * env) continue
            val y0 = hash(e.seed, step, 40 + i)
            val h = 0.02f + 0.1f * hash(e.seed, step, 50 + i)
            val off = (hash(e.seed, step, 60 + i) * 2f - 1f) * 0.06f * env
            out.add(floatArrayOf(y0, min(1f, y0 + h), off))
        }
        return out
    }

    /** Geser RGB glitch (× lebar). */
    fun glitchShift(e: AnEffect, t: Long): Float {
        val env = envelope(t, e.startMs, e.durMs, 100L) * e.amount.coerceIn(0f, 2f)
        if (env <= 0f) return 0f
        val step = ((t - e.startMs) / 66L).toInt()
        return 0.006f * env * (0.4f + 0.6f * hash(e.seed, step, 70))
    }

    /** Light leak: pusat gradien bergerak (x, y relatif 0..1), radius relatif, alfa. */
    fun lightLeak(e: AnEffect, t: Long): FloatArray {
        val env = envelope(t, e.startMs, e.durMs, 300L)
        if (env <= 0f) return floatArrayOf(0f, 0f, 0f, 0f)
        val p = if (e.durMs <= 0L) 0f else (t - e.startMs).toFloat() / e.durMs
        val a = hash(e.seed, 1, 80) * 2f * PI.toFloat()
        val x = 0.5f + cos(a) * (0.7f - 1.4f * p)
        val y = 0.5f + sin(a) * (0.5f - 1.0f * p)
        return floatArrayOf(x, y, 0.6f + 0.3f * hash(e.seed, 2, 80), (0.55f * env * e.amount.coerceIn(0f, 2f)).coerceIn(0f, 1f))
    }

    /** Kekuatan glow 0..1 (berdenyut halus). */
    fun glow(e: AnEffect, t: Long): Float {
        val env = envelope(t, e.startMs, e.durMs, 250L)
        if (env <= 0f) return 0f
        val sec = (t - e.startMs) / 1000f
        return (env * (0.75f + 0.25f * sin(sec * 4f)) * e.amount.coerceIn(0f, 2f) / 2f * 1.4f).coerceIn(0f, 1f)
    }
}

// ====================================================================== transisi

internal object AnTransMath {
    const val CROSSFADE = "crossfade"
    const val SLIDE = "slide"
    const val ZOOM = "zoom"
    const val WIPE = "wipe"
    const val BLUR = "blur"

    val ALL = listOf(CROSSFADE to "Crossfade", SLIDE to "Geser", ZOOM to "Zoom", WIPE to "Wipe", BLUR to "Blur")
    fun label(type: String): String = ALL.firstOrNull { it.first == type }?.second ?: "Tanpa"
    fun validType(t: String?): String? = t?.takeIf { s -> ALL.any { it.first == s } }

    /** Toleransi (ms) titik keluar layer lain terhadap titik masuk agar dianggap bersambung. */
    const val JOIN_TOL = 150L

    /** Peran layer dalam transisi pada waktu tertentu. [incoming] true = layer masuk; [p] = progres 0..1. */
    class Role(val type: String, val incoming: Boolean, val p: Float)

    /** Pasangan transisi: [toId] masuk di [atMs], [fromId] (boleh null) keluar di sekitar atMs. */
    class Pair2(val fromId: String?, val toId: String, val atMs: Long, val type: String, val durMs: Long) {
        /** Jendela transisi: berpusat di atMs bila ada layer keluar; tanpa pasangan = atMs..atMs+dur. */
        val startMs: Long get() = if (fromId != null) atMs - durMs / 2 else atMs
        val endMs: Long get() = startMs + durMs
    }

    /** Bangun pasangan dari opsi layer ([order] = urutan id layer, bawah → atas). */
    fun pairs(order: List<String>, opts: Map<String, AnLayerOpt>, durationMs: Long): List<Pair2> {
        val out = ArrayList<Pair2>()
        for (id in order) {
            val o = opts[id] ?: continue
            val tr = o.trans ?: continue
            if (o.hidden || tr.durMs <= 0L) continue
            var best: String? = null
            var bestD = Long.MAX_VALUE
            for (other in order) {
                if (other == id) continue
                val oo = opts[other] ?: continue
                if (oo.hidden || oo.outMs < 0L) continue
                val d = abs(oo.outOr(durationMs) - o.inMs)
                if (d <= JOIN_TOL && d < bestD) { best = other; bestD = d }
            }
            out.add(Pair2(best, id, o.inMs, tr.type, tr.durMs.coerceIn(100L, 5_000L)))
        }
        return out
    }

    /** Peran [id] pada waktu [t] (null = tidak dalam transisi). */
    fun roleOf(pairs: List<Pair2>, id: String, t: Long): Role? {
        for (pr in pairs) {
            if (t < pr.startMs || t > pr.endMs) continue
            val p = ((t - pr.startMs).toFloat() / pr.durMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            if (pr.toId == id) return Role(pr.type, true, p)
            if (pr.fromId == id) return Role(pr.type, false, p)
        }
        return null
    }

    /** Ease transisi (in-out kubik). */
    fun ease(p: Float): Float = AnEase.apply(AnEase.IN_OUT, p)

    /**
     * Modifikasi gambar untuk peran: [alpha ×, dx (× lebar kanvas), skala ×, clipFrom, clipTo (× lebar), blur 0..1].
     */
    fun look(r: Role): FloatArray {
        val e = ease(r.p)
        return when (r.type) {
            CROSSFADE -> if (r.incoming) floatArrayOf(e, 0f, 1f, 0f, 1f, 0f) else floatArrayOf(1f - e, 0f, 1f, 0f, 1f, 0f)
            SLIDE -> if (r.incoming) floatArrayOf(1f, 1f - e, 1f, 0f, 1f, 0f) else floatArrayOf(1f, -e, 1f, 0f, 1f, 0f)
            ZOOM -> if (r.incoming) floatArrayOf(e, 0f, 0.6f + 0.4f * e, 0f, 1f, 0f) else floatArrayOf(1f - e, 0f, 1f + 0.5f * e, 0f, 1f, 0f)
            WIPE -> if (r.incoming) floatArrayOf(1f, 0f, 1f, 0f, e, 0f) else floatArrayOf(1f, 0f, 1f, e, 1f, 0f)
            BLUR -> if (r.incoming) floatArrayOf(e, 0f, 1f, 0f, 1f, 1f - e) else floatArrayOf(1f - e, 0f, 1f, 0f, 1f, e)
            else -> floatArrayOf(1f, 0f, 1f, 0f, 1f, 0f)
        }
    }
}

// ====================================================================== codec

internal object AnFxCodec {
    private fun num(v: Any?): Float? = (v as? Number)?.toFloat()?.takeIf { !it.isNaN() && !it.isInfinite() }

    fun encodeEffect(e: AnEffect): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "type" to e.type, "start" to e.startMs, "dur" to e.durMs,
            "amt" to (if (e.amount.isNaN()) 1.0 else e.amount.toDouble()), "seed" to e.seed
        )
        if (e.variant != 0) m["var"] = e.variant
        if (e.global) m["global"] = true
        return m
    }

    fun decodeEffect(m: Map<String, Any?>?): AnEffect? {
        if (m == null) return null
        val type = AnFxMath.validType(m["type"] as? String) ?: return null
        return AnEffect(
            type = type,
            startMs = ((m["start"] as? Number)?.toLong() ?: 0L).coerceIn(0L, AnMath.MAX_DURATION),
            durMs = ((m["dur"] as? Number)?.toLong() ?: AnFxMath.defaultMs(type)).coerceIn(0L, AnMath.MAX_DURATION),
            amount = (num(m["amt"]) ?: 1f).coerceIn(0f, 2f),
            variant = ((m["var"] as? Number)?.toInt() ?: 0).coerceIn(0, 2),
            global = m["global"] == true,
            seed = (m["seed"] as? Number)?.toInt() ?: 1
        )
    }

    fun encodeEffects(list: List<AnEffect>): List<Any?> = list.map { encodeEffect(it) }

    fun decodeEffects(v: Any?): List<AnEffect> {
        val l = v as? List<*> ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return l.mapNotNull { decodeEffect(it as? Map<String, Any?>) }.take(AnFxMath.MAX_EFFECTS)
    }

    fun encodeTrans(t: AnTransition): Map<String, Any?> = linkedMapOf("type" to t.type, "dur" to t.durMs)

    fun decodeTrans(m: Map<String, Any?>?): AnTransition? {
        if (m == null) return null
        val type = AnTransMath.validType(m["type"] as? String) ?: return null
        return AnTransition(type, ((m["dur"] as? Number)?.toLong() ?: 600L).coerceIn(100L, 5_000L))
    }
}
