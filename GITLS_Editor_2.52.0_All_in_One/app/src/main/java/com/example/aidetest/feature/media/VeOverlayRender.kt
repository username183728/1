package com.example.aidetest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Menggambar overlay Video Editor (teks, keterangan, stiker, gambar).
 * Dipakai bersama oleh pratinjau (canvas langsung) dan ekspor (bitmap ketat per item) sehingga
 * tampilan identik. Ukuran dalam piksel bingkai; `size` item relatif sisi pendek bingkai.
 */
internal object VeOverlayRender {
    class Box(val w: Int, val h: Int)

    private class Built(val layout: StaticLayout?, val outline: StaticLayout?, val w: Int, val h: Int, val padX: Float, val padY: Float,
                        val margin: Float, val glyphPx: Float)

    private val imageCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Muat gambar overlay (diperkecil ke [maxSide]); di-cache agar pratinjau tidak mendekode ulang. */
    fun loadImage(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val key = "$uri|$maxSide"
        imageCache.get(key)?.let { if (!it.isRecycled) return it }
        val bmp = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
            val longest = max(raw.width, raw.height)
            if (longest > maxSide) {
                val s = maxSide.toFloat() / longest
                Bitmap.createScaledBitmap(raw, (raw.width * s).roundToInt().coerceAtLeast(1), (raw.height * s).roundToInt().coerceAtLeast(1), true)
                    .also { if (it !== raw) raw.recycle() }
            } else raw
        }.getOrNull()
        if (bmp != null) imageCache.put(key, bmp)
        return bmp
    }

    fun clearImages() { imageCache.evictAll() }

    fun typeface(ctx: Context, item: VeOverlay): Typeface {
        val base = PeLayerRender.typeface(item.font)
        val style = when {
            item.bold && item.italic -> Typeface.BOLD_ITALIC
            item.bold -> Typeface.BOLD
            item.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return if (style == Typeface.NORMAL) base else Typeface.create(base, style)
    }

    private fun alignment(item: VeOverlay): Layout.Alignment = when (item.align) {
        "left" -> Layout.Alignment.ALIGN_NORMAL
        "right" -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_CENTER
    }

    private fun build(ctx: Context, item: VeOverlay, frameW: Int, frameH: Int): Built {
        val minDim = min(frameW, frameH).toFloat()
        val px = max(6f, item.size * minDim * item.scale)
        val maxBox = max(frameW, frameH) * 2.5f
        when (item.kind) {
            "image" -> {
                val w = min(maxBox, max(8f, px))
                val h = min(maxBox, max(8f, w / item.imageAspect.coerceAtLeast(0.05f)))
                return Built(null, null, w.roundToInt(), h.roundToInt(), 0f, 0f, 0f, 0f)
            }
            "sticker" -> {
                val margin = if (item.shadow > 0f) px * 0.25f * item.shadow + 2f else 0f
                val side = ceil(px * 1.25f + margin * 2f).toInt()
                return Built(null, null, side, side, 0f, 0f, margin, px)
            }
        }
        val text = item.text.ifEmpty { " " }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px
            typeface = typeface(ctx, item)
            color = item.color
        }
        var natural = 0f
        for (line in text.split('\n')) natural = max(natural, paint.measureText(line))
        val maxW = (frameW * 0.92f).toInt().coerceAtLeast(16)
        val layoutW = min(ceil(natural).toInt() + 2, maxW).coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutW)
            .setAlignment(alignment(item))
            .setLineSpacing(0f, 1.08f)
            .setIncludePad(false)
            .build()
        var outline: StaticLayout? = null
        val strokePx = item.outlineWidth * px * 2f
        if (strokePx > 0.5f) {
            val op = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokePx
                strokeJoin = Paint.Join.ROUND
                color = item.outlineColor
            }
            outline = StaticLayout.Builder.obtain(text, 0, text.length, op, layoutW)
                .setAlignment(alignment(item)).setLineSpacing(0f, 1.08f).setIncludePad(false).build()
        }
        val boxed = item.bgOpacity > 0.01f
        val padX = if (boxed) px * 0.35f else 0f
        val padY = if (boxed) px * 0.18f else 0f
        val margin = strokePx + (if (item.shadow > 0f) px * 0.3f * item.shadow + 2f else 0f)
        val w = ceil(layoutW + 2 * (padX + margin)).toInt()
        val h = ceil(layout.height + 2 * (padY + margin)).toInt()
        return Built(layout, outline, min(w, maxBox.toInt()), min(h, maxBox.toInt()), padX, padY, margin, px)
    }

    /** Ukuran piksel kotak ketat item (tanpa rotasi/animasi). */
    fun measure(ctx: Context, item: VeOverlay, frameW: Int, frameH: Int): Box {
        val b = build(ctx, item, frameW, frameH)
        return Box(b.w, b.h)
    }

    /**
     * Gambar item berpusat di ([cx], [cy]). [alphaMul] dan [scaleMul] berasal dari animasi;
     * [rotationDeg] searah jarum jam.
     */
    fun draw(
        ctx: Context, canvas: Canvas, item: VeOverlay, frameW: Int, frameH: Int,
        cx: Float, cy: Float, alphaMul: Float, scaleMul: Float, rotationDeg: Float,
        imageMaxSide: Int = 720
    ) {
        val b = build(ctx, item, frameW, frameH)
        val alpha = (item.opacity * alphaMul).coerceIn(0f, 1f)
        if (alpha <= 0.003f) return
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rotationDeg)
        canvas.scale(scaleMul, scaleMul)
        val halfW = b.w / 2f
        val halfH = b.h / 2f
        when (item.kind) {
            "image" -> {
                val uri = item.imageUri
                val bmp = uri?.let { loadImage(ctx, it, imageMaxSide) }
                val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = (alpha * 255).roundToInt() }
                val dst = RectF(-halfW, -halfH, halfW, halfH)
                if (bmp != null) canvas.drawBitmap(bmp, null, dst, p)
                else {
                    p.color = Color.argb((60 * alpha).roundToInt(), 128, 128, 128)
                    canvas.drawRect(dst, p)
                }
            }
            "sticker" -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = b.glyphPx
                    textAlign = Paint.Align.CENTER
                    color = Color.argb((Color.alpha(item.color) * alpha).roundToInt(), Color.red(item.color), Color.green(item.color), Color.blue(item.color))
                    if (item.shadow > 0f) setShadowLayer(b.glyphPx * 0.12f * item.shadow + 1f, 0f, b.glyphPx * 0.04f, Color.argb((170 * alpha).roundToInt(), 0, 0, 0))
                }
                val glyph = if (item.icon.isNotEmpty()) {
                    p.typeface = PeLayerRender.mdi(ctx) ?: Typeface.DEFAULT
                    MdiGlyphs.glyph(item.icon)
                } else item.text.ifEmpty { "★" }
                val fm = p.fontMetrics
                canvas.drawText(glyph, 0f, -(fm.ascent + fm.descent) / 2f, p)
            }
            else -> {
                val layout = b.layout ?: run { canvas.restore(); return }
                if (item.bgOpacity > 0.01f) {
                    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = item.bgColor
                        this.alpha = (255 * item.bgOpacity * alpha).roundToInt()
                    }
                    val m = b.margin
                    canvas.drawRoundRect(RectF(-halfW + m, -halfH + m, halfW - m, halfH - m), b.glyphPx * 0.22f, b.glyphPx * 0.22f, bg)
                }
                canvas.translate(-layout.width / 2f, -layout.height / 2f)
                val layerSave = if (alpha < 0.995f) canvas.saveLayerAlpha(null, (alpha * 255).roundToInt()) else canvas.save()
                b.outline?.draw(canvas)
                layout.paint.apply {
                    if (item.shadow > 0f) setShadowLayer(b.glyphPx * 0.15f * item.shadow + 1f, b.glyphPx * 0.04f, b.glyphPx * 0.05f, Color.argb(190, 0, 0, 0))
                    else clearShadowLayer()
                }
                layout.draw(canvas)
                canvas.restoreToCount(layerSave)
            }
        }
        canvas.restore()
    }

    /** Bitmap ketat (ARGB) untuk ekspor: item digambar di tengah tanpa rotasi/animasi. */
    fun renderTight(ctx: Context, item: VeOverlay, frameW: Int, frameH: Int): Bitmap {
        val b = build(ctx, item, frameW, frameH)
        val bmp = Bitmap.createBitmap(max(1, b.w), max(1, b.h), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        draw(ctx, cv, item.copy(opacity = 1f), frameW, frameH, b.w / 2f, b.h / 2f, 1f, 1f, 0f, imageMaxSide = max(frameW, frameH))
        return bmp
    }
}
