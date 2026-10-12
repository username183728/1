package com.example.aidetest

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max

/**
 * Tipografi & stiker bersama: daftar font, mode campur, stiker MDI/emoji, dan render PeLayer.
 * Dipindah utuh (API tidak berubah) dari PhotoEditorPro.kt saat Photo Editor digabung ke
 * Studio Kreatif. Dipakai Studio Kreatif dan Video Editor (VeOverlayRender).
 */
internal object PeLayerRender {
    val FONTS: List<Pair<String, String>> = listOf(
        "sans" to "Sans", "bold" to "Tebal", "serif" to "Serif", "serifb" to "Serif Tebal", "mono" to "Mono",
        "condensed" to "Ramping", "light" to "Tipis", "script" to "Skrip", "casual" to "Santai"
    )
    val BLENDS: List<Pair<String, String>> = listOf(
        "normal" to "Normal", "multiply" to "Multiply", "screen" to "Screen", "overlay" to "Overlay",
        "darken" to "Gelapkan", "lighten" to "Terangkan", "add" to "Tambah"
    )
    val STICKERS = listOf(
        "heart", "heart-outline", "star", "star-outline", "crown", "fire", "lightning-bolt", "music-note",
        "flower", "paw", "thumb-up", "check-decagram", "map-marker", "camera", "coffee", "emoticon-happy-outline"
    )
    val EMOJI = listOf("😀", "😍", "🔥", "✨", "❤️", "👍", "🎉", "🌸")

    private var mdiFace: Typeface? = null

    fun mdi(ctx: Context): Typeface? {
        mdiFace?.let { return it }
        return runCatching { Typeface.createFromAsset(ctx.assets, "fonts/materialdesignicons-webfont.ttf") }.getOrNull()?.also { mdiFace = it }
    }

    fun typeface(font: String): Typeface = when (font) {
        "bold" -> Typeface.DEFAULT_BOLD
        "serif" -> Typeface.SERIF
        "serifb" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        "mono" -> Typeface.MONOSPACE
        "condensed" -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        "light" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        "script" -> Typeface.create("cursive", Typeface.NORMAL)
        "casual" -> Typeface.create("casual", Typeface.NORMAL)
        else -> Typeface.DEFAULT
    }

    private fun blendMode(key: String): PorterDuff.Mode? = when (key) {
        "multiply" -> PorterDuff.Mode.MULTIPLY
        "screen" -> PorterDuff.Mode.SCREEN
        "overlay" -> PorterDuff.Mode.OVERLAY
        "darken" -> PorterDuff.Mode.DARKEN
        "lighten" -> PorterDuff.Mode.LIGHTEN
        "add" -> PorterDuff.Mode.ADD
        else -> null
    }

    private fun lines(l: PeLayer): List<String> = when (l.type) {
        "sticker" -> listOf(if (l.icon.isNotEmpty()) MdiGlyphs.glyph(l.icon) else l.text)
        else -> l.text.ifEmpty { " " }.split("\n")
    }

    private fun prepareText(ctx: Context, l: PeLayer, outW: Int, p: Paint) {
        p.reset()
        p.isAntiAlias = true
        p.textSize = l.size * outW
        p.typeface = if (l.type == "sticker" && l.icon.isNotEmpty()) mdi(ctx) else typeface(l.font)
        p.letterSpacing = if (l.type == "text") l.spacing else 0f
        p.textAlign = when (l.align) { "left" -> Paint.Align.LEFT; "right" -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
    }

    /** Ukuran kotak layer (px output) sebelum rotasi. */
    fun measure(ctx: Context, l: PeLayer, outW: Int, p: Paint): Pair<Float, Float> {
        if (l.type == "image") {
            val b = l.bitmap ?: return 1f to 1f
            val w = l.size * outW
            return w to w * b.height / b.width.coerceAtLeast(1)
        }
        prepareText(ctx, l, outW, p)
        val ls = lines(l)
        var w = 0f
        for (s in ls) w = max(w, p.measureText(s))
        val lineH = p.textSize * 1.22f
        return (w + p.textSize * 0.2f) to (lineH * ls.size)
    }

    fun draw(ctx: Context, canvas: Canvas, l: PeLayer, outW: Int, outH: Int, p: Paint) {
        val (bw, bh) = measure(ctx, l, outW, p)
        val cx = l.cx * outW
        val cy = l.cy * outH
        val mode = blendMode(l.blend)
        val layerPaint = Paint().apply {
            alpha = (l.opacity.coerceIn(0f, 1f) * 255).toInt()
            if (mode != null) xfermode = PorterDuffXfermode(mode)
        }
        val needLayer = mode != null || l.opacity < 0.999f
        val save = if (needLayer) canvas.saveLayer(null, layerPaint) else canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(l.rotation)
        if (l.type == "image") {
            val b = l.bitmap
            if (b != null) {
                val dst = RectF(-bw / 2f, -bh / 2f, bw / 2f, bh / 2f)
                if (l.shadow > 0f) {
                    val sp = Paint(Paint.ANTI_ALIAS_FLAG)
                    sp.color = l.shadowColor
                    sp.maskFilter = BlurMaskFilter(1f + l.shadow * bw * 0.04f, BlurMaskFilter.Blur.NORMAL)
                    val alpha = b.extractAlpha()
                    canvas.save()
                    canvas.translate(l.shadow * bw * 0.03f, l.shadow * bw * 0.03f)
                    canvas.drawBitmap(alpha, null, dst, sp)
                    canvas.restore()
                }
                canvas.drawBitmap(b, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
        } else {
            prepareText(ctx, l, outW, p)
            val ls = lines(l)
            val lineH = p.textSize * 1.22f
            val fm = p.fontMetrics
            val x = when (l.align) { "left" -> -bw / 2f + p.textSize * 0.1f; "right" -> bw / 2f - p.textSize * 0.1f; else -> 0f }
            var y = -bh / 2f + (lineH - (fm.descent - fm.ascent)) / 2f - fm.ascent
            for (s in ls) {
                if (l.outline > 0f) {
                    p.style = Paint.Style.STROKE
                    p.strokeJoin = Paint.Join.ROUND
                    p.strokeWidth = l.outline * p.textSize * 2f
                    p.color = l.outlineColor
                    if (l.shadow > 0f) p.setShadowLayer(1f + l.shadow * p.textSize * 0.22f, l.shadow * p.textSize * 0.06f, l.shadow * p.textSize * 0.06f, l.shadowColor)
                    canvas.drawText(s, x, y, p)
                    p.clearShadowLayer()
                }
                p.style = Paint.Style.FILL
                p.color = l.color
                if (l.shadow > 0f && l.outline <= 0f) p.setShadowLayer(1f + l.shadow * p.textSize * 0.22f, l.shadow * p.textSize * 0.06f, l.shadow * p.textSize * 0.06f, l.shadowColor)
                canvas.drawText(s, x, y, p)
                p.clearShadowLayer()
                y += lineH
            }
        }
        canvas.restoreToCount(save)
    }
}
