package com.example.aidetest

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Easing lanjutan Animation Studio (Graph Editor) — logika murni tanpa API Android, diuji di JVM
 * (AnimEaseTest). Disimpan di AnKey.ease sebagai string yang kompatibel mundur:
 *   "linear" | "in" | "out" | "inout"         (lama, tetap valid)
 *   "back" | "back:1.70"                       overshoot (jumlah overshoot 0..4)
 *   "bounce"                                   pantulan (Penner, ease-out)
 *   "elastic" | "elastic:1.0"                  elastis (amplitudo 0.2..3)
 *   "spring" | "spring:170,26"                 pegas teredam (stiffness 10..1000, damping 1..100, massa 1)
 *   "bezier:x1,y1,x2,y2"                       cubic-bezier CSS (x ∈ 0..1, y ∈ −1..2)
 * String rusak → AnEase.valid() mengembalikan "inout" seperti sebelumnya.
 */

internal sealed class AnCurve {
    object Linear : AnCurve()
    object In : AnCurve()
    object Out : AnCurve()
    object InOut : AnCurve()
    data class Back(val s: Float) : AnCurve()
    object Bounce : AnCurve()
    data class Elastic(val amp: Float) : AnCurve()
    data class Spring(val stiffness: Float, val damping: Float) : AnCurve()
    data class Bezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : AnCurve()
}

internal object AnEasing {
    const val BACK = "back"
    const val BOUNCE = "bounce"
    const val ELASTIC = "elastic"
    const val SPRING = "spring"
    const val BEZIER = "bezier"

    const val BACK_DEFAULT = 1.70158f
    const val ELASTIC_DEFAULT = 1f
    const val SPRING_K = 170f
    const val SPRING_D = 14f

    /** Preset tombol Graph Editor: (string ease, label). */
    val PRESETS = listOf(
        "linear" to "Linear", "in" to "In", "out" to "Out", "inout" to "In-out",
        "back" to "Back", "bounce" to "Pantul", "elastic" to "Elastis", "spring" to "Pegas",
        "bezier:0.25,0.1,0.25,1" to "Ease", "bezier:0.68,-0.6,0.32,1.6" to "Antisipasi"
    )

    private val cache = ConcurrentHashMap<String, AnCurve>()

    private fun f(v: Float): String {
        val s = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }

    private fun nums(s: String): List<Float>? {
        if (s.isBlank()) return emptyList()
        val out = s.split(',').map { it.trim().toFloatOrNull() ?: return null }
        return if (out.any { it.isNaN() || it.isInfinite() }) null else out
    }

    /** Parse string ease; null bila tidak dikenal. */
    fun parse(e: String?): AnCurve? {
        if (e == null) return null
        cache[e]?.let { return it }
        val r = parseRaw(e.trim()) ?: return null
        if (cache.size > 256) cache.clear()
        cache[e] = r
        return r
    }

    private fun parseRaw(e: String): AnCurve? {
        when (e) {
            AnEase.LINEAR -> return AnCurve.Linear
            AnEase.IN -> return AnCurve.In
            AnEase.OUT -> return AnCurve.Out
            AnEase.IN_OUT -> return AnCurve.InOut
            BOUNCE -> return AnCurve.Bounce
        }
        val name = e.substringBefore(':')
        val args = nums(if (':' in e) e.substringAfter(':') else "") ?: return null
        return when (name) {
            BACK -> if (args.size <= 1) AnCurve.Back((args.getOrNull(0) ?: BACK_DEFAULT).coerceIn(0f, 4f)) else null
            ELASTIC -> if (args.size <= 1) AnCurve.Elastic((args.getOrNull(0) ?: ELASTIC_DEFAULT).coerceIn(0.2f, 3f)) else null
            SPRING -> when (args.size) {
                0 -> AnCurve.Spring(SPRING_K, SPRING_D)
                2 -> AnCurve.Spring(args[0].coerceIn(10f, 1000f), args[1].coerceIn(1f, 100f))
                else -> null
            }
            BEZIER -> if (args.size == 4) AnCurve.Bezier(args[0].coerceIn(0f, 1f), args[1].coerceIn(-1f, 2f), args[2].coerceIn(0f, 1f), args[3].coerceIn(-1f, 2f)) else null
            else -> null
        }
    }

    fun format(c: AnCurve): String = when (c) {
        AnCurve.Linear -> AnEase.LINEAR
        AnCurve.In -> AnEase.IN
        AnCurve.Out -> AnEase.OUT
        AnCurve.InOut -> AnEase.IN_OUT
        AnCurve.Bounce -> BOUNCE
        is AnCurve.Back -> "$BACK:${f(c.s)}"
        is AnCurve.Elastic -> "$ELASTIC:${f(c.amp)}"
        is AnCurve.Spring -> "$SPRING:${f(c.stiffness)},${f(c.damping)}"
        is AnCurve.Bezier -> "$BEZIER:${f(c.x1)},${f(c.y1)},${f(c.x2)},${f(c.y2)}"
    }

    /** Bentuk kanonik (terbatas) atau null bila tidak valid. */
    fun canonical(e: String?): String? = parse(e)?.let { format(it) }

    fun label(e: String): String = when (val c = parse(e)) {
        is AnCurve.Back -> "Back"
        AnCurve.Bounce -> "Pantul"
        is AnCurve.Elastic -> "Elastis"
        is AnCurve.Spring -> "Pegas"
        is AnCurve.Bezier -> "Bezier"
        null -> "Ease-in-out"
        else -> AnEase.label(format(c))
    }

    /** Nilai kurva di progres [p] (0..1). 0 → 0 dan 1 → 1 tepat; di tengah boleh melewati 0..1 (overshoot). */
    fun apply(c: AnCurve, p: Float): Float {
        val t = if (p.isNaN()) 0f else p.coerceIn(0f, 1f)
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        return when (c) {
            AnCurve.Linear -> t
            AnCurve.In -> t * t * t
            AnCurve.Out -> { val u = 1f - t; 1f - u * u * u }
            AnCurve.InOut -> if (t < 0.5f) 4f * t * t * t else { val u = -2f * t + 2f; 1f - u * u * u / 2f }
            is AnCurve.Back -> back(t, c.s)
            AnCurve.Bounce -> bounce(t)
            is AnCurve.Elastic -> elastic(t, c.amp)
            is AnCurve.Spring -> spring(t, c.stiffness, c.damping)
            is AnCurve.Bezier -> bezier(t, c.x1, c.y1, c.x2, c.y2)
        }
    }

    /** Ease-out back: melewati 1 lalu kembali. [s] = jumlah overshoot (1.70158 ≈ 10%). */
    fun back(t: Float, s: Float): Float {
        val x = t - 1f
        return 1f + (s + 1f) * x * x * x + s * x * x
    }

    /** Pantulan Penner (ease-out). */
    fun bounce(t: Float): Float {
        var x = t
        val n1 = 7.5625f
        val d1 = 2.75f
        return when {
            x < 1f / d1 -> n1 * x * x
            x < 2f / d1 -> { x -= 1.5f / d1; n1 * x * x + 0.75f }
            x < 2.5f / d1 -> { x -= 2.25f / d1; n1 * x * x + 0.9375f }
            else -> { x -= 2.625f / d1; n1 * x * x + 0.984375f }
        }
    }

    /** Ease-out elastic: osilasi meluruh menuju 1; [amp] = skala amplitudo overshoot. */
    fun elastic(t: Float, amp: Float): Float {
        val c4 = 2.0 * PI / 3.0
        val base = 2.0.pow(-10.0 * t) * sin((t * 10.0 - 0.75) * c4)
        return (1.0 + amp * base).toFloat()
    }

    /**
     * Pegas teredam (massa 1) dari 0 menuju 1 dengan kecepatan awal 0. Waktu dinormalisasi sehingga
     * segmen = waktu menetap (selubung < 0,1%), lalu dikoreksi linear agar p=1 tepat 1.
     */
    fun spring(t: Float, stiffness: Float, damping: Float): Float {
        val w0 = sqrt(stiffness.toDouble().coerceAtLeast(1e-3))
        val zeta = damping / (2.0 * w0)
        val decay = if (zeta < 1.0) (zeta * w0).coerceAtLeast(0.15 * w0) else w0 * (zeta - sqrt(zeta * zeta - 1.0)).coerceAtLeast(1e-3)
        val settle = ln(1000.0) / decay
        fun x(tau: Double): Double = when {
            zeta < 1.0 -> {
                val wd = w0 * sqrt(1.0 - zeta * zeta)
                1.0 - exp(-zeta * w0 * tau) * (cos(wd * tau) + zeta * w0 / wd * sin(wd * tau))
            }
            abs(zeta - 1.0) < 1e-6 -> 1.0 - exp(-w0 * tau) * (1.0 + w0 * tau)
            else -> {
                val s = sqrt(zeta * zeta - 1.0)
                val r1 = -w0 * (zeta - s)
                val r2 = -w0 * (zeta + s)
                1.0 - (r2 * exp(r1 * tau) - r1 * exp(r2 * tau)) / (r2 - r1)
            }
        }
        val end = x(settle)
        return (x(t * settle) + (1.0 - end) * t).toFloat()
    }

    /** cubic-bezier(x1,y1,x2,y2): cari parameter u dengan x(u)=t (Newton + bagi dua), lalu kembalikan y(u). */
    fun bezier(t: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val u = solveU(t.toDouble(), x1.toDouble(), x2.toDouble())
        return cubic(u, y1.toDouble(), y2.toDouble()).toFloat()
    }

    private fun cubic(u: Double, a: Double, b: Double): Double {
        val v = 1.0 - u
        return 3.0 * v * v * u * a + 3.0 * v * u * u * b + u * u * u
    }

    private fun dCubic(u: Double, a: Double, b: Double): Double {
        val v = 1.0 - u
        return 3.0 * v * v * a + 6.0 * v * u * (b - a) + 3.0 * u * u * (1.0 - b)
    }

    fun solveU(x: Double, x1: Double, x2: Double): Double {
        var u = x
        repeat(8) {
            val e = cubic(u, x1, x2) - x
            if (abs(e) < 1e-7) return u
            val d = dCubic(u, x1, x2)
            if (abs(d) < 1e-6) return@repeat
            u = (u - e / d).coerceIn(0.0, 1.0)
        }
        var lo = 0.0
        var hi = 1.0
        u = x
        repeat(40) {
            val e = cubic(u, x1, x2) - x
            if (abs(e) < 1e-7) return u
            if (e > 0) hi = u else lo = u
            u = (lo + hi) / 2.0
        }
        return u
    }

    /** Kecepatan relatif (turunan numerik) kurva di [p] — untuk garis kecepatan Graph Editor. */
    fun speed(c: AnCurve, p: Float, h: Float = 0.01f): Float {
        val a = (p - h).coerceIn(0f, 1f)
        val b = (p + h).coerceIn(0f, 1f)
        if (b <= a) return 0f
        return (apply(c, b) - apply(c, a)) / (b - a)
    }

    /**
     * Akselerasi: kekuatan ease-in [inS] & ease-out [outS] (0..1) → bezier. 0,0 = linear;
     * 1,1 ≈ ease-in-out kuat.
     */
    fun accel(inS: Float, outS: Float): AnCurve.Bezier =
        AnCurve.Bezier(0.9f * inS.coerceIn(0f, 1f), 0f, 1f - 0.9f * outS.coerceIn(0f, 1f), 1f)

    /** Kebalikan [accel] untuk kurva apa pun (perkiraan untuk kurva non-bezier). */
    fun accelOf(c: AnCurve): Pair<Float, Float> = when (c) {
        AnCurve.Linear -> 0f to 0f
        AnCurve.In -> 0.7f to 0f
        AnCurve.Out -> 0f to 0.7f
        AnCurve.InOut -> 0.7f to 0.7f
        is AnCurve.Bezier -> (c.x1 / 0.9f).coerceIn(0f, 1f) to ((1f - c.x2) / 0.9f).coerceIn(0f, 1f)
        else -> 0f to 0.5f
    }

    /** Titik kendali bezier untuk kurva (non-bezier → perkiraan agar handle bisa diseret dari kurva lama). */
    fun handlesOf(c: AnCurve): AnCurve.Bezier = when (c) {
        is AnCurve.Bezier -> c
        AnCurve.Linear -> AnCurve.Bezier(0f, 0f, 1f, 1f)
        AnCurve.In -> AnCurve.Bezier(0.55f, 0.055f, 0.675f, 0.19f)
        AnCurve.Out -> AnCurve.Bezier(0.215f, 0.61f, 0.355f, 1f)
        AnCurve.InOut -> AnCurve.Bezier(0.645f, 0.045f, 0.355f, 1f)
        is AnCurve.Back -> AnCurve.Bezier(0.175f, 0.885f, 0.32f, 1f + 0.16f * c.s)
        else -> AnCurve.Bezier(0.25f, 0.1f, 0.25f, 1f)
    }

    /** Overshoot yang bisa diatur (back/elastic): nilai 0..1 untuk slider. */
    fun overshootOf(c: AnCurve): Float? = when (c) {
        is AnCurve.Back -> c.s / 4f
        is AnCurve.Elastic -> (c.amp - 0.2f) / 2.8f
        else -> null
    }

    fun withOvershoot(c: AnCurve, v: Float): AnCurve = when (c) {
        is AnCurve.Back -> AnCurve.Back(4f * v.coerceIn(0f, 1f))
        is AnCurve.Elastic -> AnCurve.Elastic(0.2f + 2.8f * v.coerceIn(0f, 1f))
        else -> c
    }

    /** Rentang y (min, max) kurva untuk menggambar grafik (selalu mencakup 0..1). */
    fun range(c: AnCurve, steps: Int = 64): Pair<Float, Float> {
        var lo = 0f
        var hi = 1f
        for (i in 0..steps) {
            val v = apply(c, i / steps.toFloat())
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return lo to hi
    }
}
