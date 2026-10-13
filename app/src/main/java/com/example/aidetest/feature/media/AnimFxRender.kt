package com.example.aidetest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Render efek & transisi layer Animation Studio (dipanggil dari AnDraw.drawDoc → pratinjau dan ekspor
 * MP4/GIF memakai jalur yang sama). Pewaktuan/noise dari AnFxMath/AnTransMath (murni, deterministik).
 * Blur memakai perkecil-perbesar bertahap (bilinear) — tanpa RenderEffect, jalan di API 24.
 * Bitmap kerja dipakai ulang per thread (pratinjau UI dan thread ekspor tidak berbagi bitmap).
 */

/** Rencana efek satu frame: transisi + efek global (guncang, kilat, partikel/cahaya kanvas). */
internal class AnFxPlan(
    val pairs: List<AnTransMath.Pair2>,
    val shake: FloatArray?,
    val globals: List<AnEffect>
) {
    fun role(id: String, t: Long): AnTransMath.Role? = if (pairs.isEmpty()) null else AnTransMath.roleOf(pairs, id, t)
}

internal object AnFxDraw {
    /** Sisi terpanjang bitmap kerja (px). Sama untuk pratinjau & ekspor agar hasil identik. */
    private const val WORK_SIDE = 960
    /** Jarak frame acuan motion blur (ms) — tetap, tidak bergantung fps ekspor. */
    private const val MOTION_FRAME_MS = 33L

    private val confetti = intArrayOf(
        Color.rgb(255, 82, 82), Color.rgb(255, 196, 0), Color.rgb(64, 196, 255), Color.rgb(105, 240, 174), Color.rgb(234, 128, 252)
    )

    private class Scratch {
        var full: Bitmap? = null
        var tmp: Bitmap? = null
        var half: Bitmap? = null
        var quarter: Bitmap? = null
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val src = Rect()
        val dst = RectF()

        fun ensure(w: Int, h: Int) {
            val f = full
            if (f != null && f.width == w && f.height == h) return
            full?.recycle(); tmp?.recycle(); half?.recycle(); quarter?.recycle()
            full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            half = Bitmap.createBitmap(max(1, w / 2), max(1, h / 2), Bitmap.Config.ARGB_8888)
            quarter = Bitmap.createBitmap(max(1, w / 4), max(1, h / 4), Bitmap.Config.ARGB_8888)
        }
    }

    private val local = object : ThreadLocal<Scratch>() { override fun initialValue() = Scratch() }

    /** Lepas bitmap kerja thread ini (dipanggil setelah ekspor selesai). */
    fun releaseThread() {
        val s = local.get() ?: return
        s.full?.recycle(); s.tmp?.recycle(); s.half?.recycle(); s.quarter?.recycle()
        s.full = null; s.tmp = null; s.half = null; s.quarter = null
    }

    /** null bila tidak ada efek/transisi sama sekali (jalur cepat lama). */
    fun plan(doc: CsDocData, opts: Map<String, AnLayerOpt>, t: Long, durationMs: Long): AnFxPlan? {
        if (opts.values.none { it.effects.isNotEmpty() || it.trans != null }) return null
        val order = doc.elements.filter { it.visible }.map { it.id }
        val pairs = AnTransMath.pairs(order, opts, durationMs)
        var sx = 0f; var sy = 0f; var sr = 0f; var shaking = false
        val globals = ArrayList<AnEffect>()
        for (id in order) {
            val o = opts[id] ?: continue
            if (o.hidden) continue
            for (e in o.effects) {
                if (!e.activeAt(t)) continue
                when (e.type) {
                    AnFxMath.SHAKE -> {
                        val v = AnFxMath.shake(e, t, doc.w.toFloat(), doc.h.toFloat())
                        sx += v[0]; sy += v[1]; sr += v[2]; shaking = true
                    }
                    AnFxMath.FLASH -> globals.add(e)
                    AnFxMath.PARTICLES, AnFxMath.LIGHT -> if (e.global) globals.add(e)
                }
            }
        }
        return AnFxPlan(pairs, if (shaking) floatArrayOf(sx, sy, sr) else null, globals)
    }

    fun applyShake(canvas: Canvas, doc: CsDocData, v: FloatArray) {
        val cx = doc.w / 2f
        val cy = doc.h / 2f
        canvas.translate(v[0], v[1])
        canvas.rotate(v[2], cx, cy)
        // Perbesar sedikit agar tepi kanvas tidak terbuka saat bergeser.
        canvas.scale(1.06f, 1.06f, cx, cy)
    }

    /** Gambar satu layer dengan transisi & efek lokal. */
    fun drawLayer(
        ctx: android.content.Context, canvas: Canvas, doc: CsDocData, el: CsElement, s: AnState, o: AnLayerOpt?,
        role: AnTransMath.Role?, t: Long, paint: Paint, img: AnLayerImage?, stateAt: ((CsElement, Long) -> AnState)?
    ) {
        val cw = doc.w.toFloat()
        val ch = doc.h.toFloat()
        val look = role?.let { AnTransMath.look(it) }
        fun mod(st: AnState, alpha: Float = 1f): AnState = if (look == null && alpha == 1f) st else st.copy(
            dx = st.dx + (look?.get(1) ?: 0f) * cw,
            scale = st.scale * (look?.get(2) ?: 1f),
            opacity = (st.opacity * (look?.get(0) ?: 1f) * alpha).coerceIn(0f, 1f)
        )
        val s2 = mod(s)
        if (s2.opacity <= 0.002f || s2.scale <= 0.002f) return
        val active = o?.effects?.filter { it.activeAt(t) } ?: emptyList()
        val save = canvas.save()
        if (look != null && (look[3] > 0f || look[4] < 1f)) canvas.clipRect(look[3] * cw, 0f, look[4] * cw, ch)

        // Motion blur: salinan pose sub-frame sebelumnya dengan alfa rendah (hanya bila bergerak).
        if (stateAt != null) {
            for (e in active) {
                if (e.type != AnFxMath.MOTION_BLUR) continue
                val env = AnFxMath.envelope(t, e.startMs, e.durMs, 150L)
                for ((dt, a) in AnFxMath.motionSamples(e, MOTION_FRAME_MS)) {
                    val g = stateAt(el, (t + dt).coerceAtLeast(0L))
                    if (!AnFxMath.moving(g, s)) continue
                    val gs = mod(g, a * env)
                    if (gs.opacity > 0.004f) AnDraw.drawLayer(ctx, canvas, el, gs, img, o, t, paint)
                }
            }
        }

        var blur = look?.get(5) ?: 0f
        var glow = 0f
        var glitch: AnEffect? = null
        for (e in active) {
            when (e.type) {
                AnFxMath.BLUR -> blur = max(blur, AnFxMath.blurAmount(e, t))
                AnFxMath.GLITCH -> if (glitch == null) glitch = e
                AnFxMath.LIGHT -> if (e.variant == 1 && !e.global) glow = max(glow, AnFxMath.glow(e, t))
            }
        }
        if (blur > 0.01f || glow > 0.01f || glitch != null) {
            val sc = local.get()!!
            val k = min(1f, WORK_SIDE / max(cw, ch))
            sc.ensure(max(2, (cw * k).roundToInt()), max(2, (ch * k).roundToInt()))
            val full = sc.full!!
            full.eraseColor(Color.TRANSPARENT)
            val fc = Canvas(full)
            fc.scale(k, k)
            AnDraw.drawLayer(ctx, fc, el, s2, img, o, t, paint)
            sc.dst.set(0f, 0f, cw, ch)
            if (glow > 0.01f) {
                sc.p.reset(); sc.p.isFilterBitmap = true
                sc.p.alpha = (255 * glow).roundToInt()
                sc.p.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                drawBlurred(canvas, sc, full, 0.35f, sc.p)
                sc.p.xfermode = null
            }
            if (glitch != null) {
                if (blur > 0.01f) {
                    // Blur dulu ke tmp, lalu glitch dari tmp.
                    val tmp = sc.tmp!!
                    tmp.eraseColor(Color.TRANSPARENT)
                    sc.p.reset(); sc.p.isFilterBitmap = true
                    val tc = Canvas(tmp)
                    tc.scale(k, k)
                    drawBlurred(tc, sc, full, blur, sc.p)
                    full.eraseColor(Color.TRANSPARENT)
                    Canvas(full).drawBitmap(tmp, 0f, 0f, null)
                }
                drawGlitch(canvas, sc, full, glitch, t, cw, ch)
            } else if (blur > 0.01f) {
                sc.p.reset(); sc.p.isFilterBitmap = true
                drawBlurred(canvas, sc, full, blur, sc.p)
            } else {
                sc.p.reset(); sc.p.isFilterBitmap = true
                canvas.drawBitmap(full, null, sc.dst, sc.p)
            }
        } else {
            AnDraw.drawLayer(ctx, canvas, el, s2, img, o, t, paint)
        }

        // Partikel & light leak di sekitar layer.
        for (e in active) {
            if (e.global) continue
            if (e.type == AnFxMath.PARTICLES || (e.type == AnFxMath.LIGHT && e.variant == 0)) {
                val an = AnDraw.animated(el, s2)
                val w = an.w * s2.scale
                val h = an.h * s2.scale
                val pad = 0.2f * max(w, h)
                val box = RectF(an.x + an.w / 2f - w / 2f - pad, an.y + an.h / 2f - h / 2f - pad, an.x + an.w / 2f + w / 2f + pad, an.y + an.h / 2f + h / 2f + pad)
                if (e.type == AnFxMath.PARTICLES) drawParticles(canvas, e, t, box, paint, s2.opacity) else drawLeak(canvas, e, t, box, paint)
            }
        }
        canvas.restoreToCount(save)
    }

    /** Efek global di atas semua layer (kilat, partikel/cahaya seluruh kanvas). */
    fun overlays(canvas: Canvas, doc: CsDocData, plan: AnFxPlan, t: Long, paint: Paint) {
        val box = RectF(0f, 0f, doc.w.toFloat(), doc.h.toFloat())
        for (e in plan.globals) {
            when (e.type) {
                AnFxMath.PARTICLES -> drawParticles(canvas, e, t, box, paint, 1f)
                AnFxMath.LIGHT -> drawLeak(canvas, e, t, box, paint)
            }
        }
        for (e in plan.globals) {
            if (e.type != AnFxMath.FLASH) continue
            val a = AnFxMath.flash(e, t)
            if (a <= 0.004f) continue
            paint.reset()
            paint.color = Color.argb((255 * a).roundToInt(), 255, 255, 255)
            // Sedikit melebihi kanvas agar tetap menutup saat guncang.
            canvas.drawRect(-box.width() * 0.1f, -box.height() * 0.1f, box.width() * 1.1f, box.height() * 1.1f, paint)
        }
    }

    private fun drawParticles(canvas: Canvas, e: AnEffect, t: Long, box: RectF, paint: Paint, alphaMul: Float) {
        val list = AnFxMath.particles(e, t, min(box.width(), box.height()).coerceAtLeast(1f))
        if (list.isEmpty()) return
        paint.reset()
        paint.isAntiAlias = true
        for (q in list) {
            val x = box.left + q.x * box.width()
            val y = box.top + q.y * box.height()
            val a = (255 * q.alpha * alphaMul).roundToInt().coerceIn(0, 255)
            if (a <= 1) continue
            when (e.variant) {
                1 -> {
                    paint.color = (confetti[q.color % confetti.size] and 0x00FFFFFF) or (a shl 24)
                    canvas.save()
                    canvas.rotate(q.angle, x, y)
                    canvas.drawRect(x - q.size, y - q.size * 0.45f, x + q.size, y + q.size * 0.45f, paint)
                    canvas.restore()
                }
                2 -> {
                    paint.color = Color.argb(a, 255, 255, 255)
                    canvas.drawCircle(x, y, q.size, paint)
                }
                else -> {
                    // Kilau bintang 4 sudut: dua belah ketupat tipis bersilang + titik terang.
                    paint.color = Color.argb(a, 255, 255, 240)
                    canvas.save()
                    canvas.rotate(q.angle, x, y)
                    canvas.drawRect(x - q.size, y - q.size * 0.08f, x + q.size, y + q.size * 0.08f, paint)
                    canvas.drawRect(x - q.size * 0.08f, y - q.size, x + q.size * 0.08f, y + q.size, paint)
                    canvas.restore()
                    canvas.drawCircle(x, y, q.size * 0.22f, paint)
                }
            }
        }
    }

    private fun drawLeak(canvas: Canvas, e: AnEffect, t: Long, box: RectF, paint: Paint) {
        val v = AnFxMath.lightLeak(e, t)
        if (v[3] <= 0.004f) return
        val cx = box.left + v[0] * box.width()
        val cy = box.top + v[1] * box.height()
        val r = (v[2] * max(box.width(), box.height())).coerceAtLeast(1f)
        val a = (255 * v[3]).roundToInt().coerceIn(0, 255)
        paint.reset()
        paint.isAntiAlias = true
        paint.shader = RadialGradient(cx, cy, r, intArrayOf(Color.argb(a, 255, 190, 110), Color.argb(a / 2, 255, 90, 60), Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        canvas.save()
        canvas.clipRect(box)
        canvas.drawRect(box, paint)
        canvas.restore()
        paint.shader = null
        paint.xfermode = null
    }

    /**
     * Gambar [src] (bitmap kerja seukuran kanvas) ke [target] dalam koordinat kanvas, diburamkan [amount] 0..1
     * dengan memperkecil bertahap ke bitmap setengah/seperempat lalu memperbesar (bilinear).
     */
    private fun drawBlurred(target: Canvas, sc: Scratch, src: Bitmap, amount: Float, p: Paint) {
        val f = AnFxMath.blurFactor(amount)
        val half = sc.half!!
        val quarter = sc.quarter!!
        val fp = Paint(Paint.FILTER_BITMAP_FLAG)
        // Tahap 1: src → half (setengah atau lebih kecil bila f ≥ 0.5 tidak perlu).
        val w1 = max(1, (src.width * max(f, 0.5f)).roundToInt()).coerceAtMost(half.width)
        val h1 = max(1, (src.height * max(f, 0.5f)).roundToInt()).coerceAtMost(half.height)
        half.eraseColor(Color.TRANSPARENT)
        Canvas(half).drawBitmap(src, null, Rect(0, 0, w1, h1), fp)
        var cur = half
        var cw = w1
        var chh = h1
        // Tahap lanjutan: ping-pong half ↔ quarter, tiap tahap paling banyak setengah.
        val steps = if (f >= 0.5f) 0 else ceil(ln(0.5f / f) / ln(2f)).toInt().coerceIn(0, 6)
        var target2 = quarter
        for (i in 0 until steps) {
            val nw = max(1, (cw / 2f).roundToInt()).coerceAtMost(target2.width)
            val nh = max(1, (chh / 2f).roundToInt()).coerceAtMost(target2.height)
            target2.eraseColor(Color.TRANSPARENT)
            sc.src.set(0, 0, cw, chh)
            Canvas(target2).drawBitmap(cur, sc.src, Rect(0, 0, nw, nh), fp)
            val prev = cur
            cur = target2
            target2 = prev
            cw = nw
            chh = nh
        }
        sc.src.set(0, 0, cw, chh)
        p.isFilterBitmap = true
        target.drawBitmap(cur, sc.src, sc.dst, p)
    }

    /** Glitch: geser kanal merah/sian (aditif) + irisan horizontal bergeser. */
    private fun drawGlitch(target: Canvas, sc: Scratch, src: Bitmap, e: AnEffect, t: Long, cw: Float, ch: Float) {
        val tmp = sc.tmp!!
        tmp.eraseColor(Color.TRANSPARENT)
        val tc = Canvas(tmp)
        val shift = AnFxMath.glitchShift(e, t) * src.width
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        p.colorFilter = LightingColorFilter(0xFF0000, 0)
        tc.drawBitmap(src, -shift, 0f, p)
        p.colorFilter = LightingColorFilter(0x00FFFF, 0)
        tc.drawBitmap(src, shift, 0f, p)
        val slices = AnFxMath.glitchSlices(e, t).sortedBy { it[0] }
        val dp = Paint(Paint.FILTER_BITMAP_FLAG)
        val sx = cw / tmp.width
        val sy = ch / tmp.height
        // Pita tanpa irisan digambar apa adanya; irisan digambar bergeser.
        var y = 0
        for (sl in slices) {
            val y0 = (sl[0] * tmp.height).roundToInt().coerceIn(0, tmp.height)
            val y1 = (sl[1] * tmp.height).roundToInt().coerceIn(0, tmp.height)
            if (y0 < y) continue
            if (y0 > y) band(target, tmp, y, y0, 0f, sx, sy, dp)
            if (y1 > y0) band(target, tmp, y0, y1, sl[2] * cw, sx, sy, dp)
            y = y1
        }
        if (y < tmp.height) band(target, tmp, y, tmp.height, 0f, sx, sy, dp)
    }

    private fun band(target: Canvas, b: Bitmap, y0: Int, y1: Int, dx: Float, sx: Float, sy: Float, p: Paint) {
        target.drawBitmap(b, Rect(0, y0, b.width, y1), RectF(dx, y0 * sy, dx + b.width * sx, y1 * sy), p)
    }
}
