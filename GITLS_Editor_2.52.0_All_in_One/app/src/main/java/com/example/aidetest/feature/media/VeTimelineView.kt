package com.example.aidetest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Timeline potret ala editor video seluler: playhead tetap di tengah, isi bergeser.
 *  - Penggaris waktu, track utama (filmstrip), jalur audio (waveform), teks/stiker/gambar, efek
 *  - Seret = geser waktu (dengan fling), cubit = zoom, ketuk = pilih, gagang = pangkas,
 *    seret item terpilih / tekan-lama = pindah waktu / urutan klip
 * View ini hanya menggambar & menerjemahkan gestur; semua perubahan data dilakukan [Host].
 */
@SuppressLint("ClickableViewAccessibility")
internal class VeTimelineView(context: Context) : View(context) {

    interface Host {
        /** Playhead digeser pengguna (scrub/fling). */
        fun onScrub(ms: Long)
        fun onScrubState(active: Boolean)
        /** kind: clip | audio | overlay | effect | "" (batal pilih). */
        fun onSelect(kind: String, id: String?)
        /** mode: trimL | trimR | move. */
        fun onEditStart(kind: String, id: String, mode: String)
        /** [deltaMs] = pergeseran jari total (ms timeline) sejak onEditStart. */
        fun onEdit(kind: String, id: String, mode: String, deltaMs: Long)
        fun onEditEnd(kind: String, id: String, mode: String)
        fun onReorder(clipId: String, toIndex: Int)
        fun onAddMedia()
        /** "audio" | "teks" */
        fun onHint(kind: String)
        fun onTransition(clipId: String)
    }

    var host: Host? = null

    /** Waktu (ms) garis magnet yang sedang aktif; null = tidak ada. */
    var snapGuideMs: Long? = null
        set(value) { if (field != value) { field = value; invalidate() } }

    private val p = VeProject
    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val cBg = Color.rgb(14, 14, 16)
    private val cRulerText = Color.rgb(140, 140, 150)
    private val cTick = Color.rgb(78, 78, 88)
    private val cClip = Color.rgb(48, 48, 54)
    private val cText = Color.rgb(78, 78, 90)
    private val cCaption = Color.rgb(60, 60, 72)
    private val cSticker = Color.rgb(92, 92, 104)
    private val cImage = Color.rgb(68, 68, 80)
    private val cEffect = Color.rgb(116, 116, 128)
    private val cAudio = Color.rgb(40, 40, 48)
    private val cWave = Color.rgb(210, 210, 218)
    private val cHint = Color.rgb(32, 32, 38)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rectF = RectF()
    private val dstF = RectF()
    private val srcR = Rect()
    private val path = Path()
    private var wavePts = FloatArray(0)
    private val dash = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)

    private class Block(val kind: String, val id: String, val startMs: Long, val endMs: Long, val top: Float, val h: Float, val ref: Any)

    private val blocks = ArrayList<Block>()
    private var hintAudioTop = -1f
    private var hintTextTop = -1f
    private var contentH = 0f
    private var scrollY = 0f

    private val rulerH get() = dp(26f)
    private val mainTop get() = rulerH + dp(4f)
    private val mainH get() = dp(56f)
    private val lanesTop get() = mainTop + mainH + dp(8f)
    private val laneH get() = dp(28f)
    private val audioH get() = dp(36f)

    private enum class Mode { NONE, PENDING, SCRUB, VSCROLL, EDIT, REORDER, PINCH }

    private class Hit(val kind: String, val id: String, val part: Int)

    private var mode = Mode.NONE
    private var target: Hit? = null
    private var downX = 0f
    private var downY = 0f
    private var startPlayhead = 0L
    private var startScrollY = 0f
    private var editMode = ""
    private var editKind = ""
    private var editId = ""
    private var reorderX = 0f
    private var reorderIndex = 0
    private var velocity: VelocityTracker? = null
    private var flinging = false
    private var flingPps = 1f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val scroller = OverScroller(context)

    private val longPress = Runnable {
        val t = target ?: return@Runnable
        if (mode != Mode.PENDING) return@Runnable
        if (t.kind != "clip" && t.kind != "audio" && t.kind != "overlay" && t.kind != "effect") return@Runnable
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        host?.onSelect(t.kind, t.id)
        if (t.kind == "clip") {
            editKind = t.kind
            editId = t.id
            reorderX = downX
            reorderIndex = reorderIndexFor(downX)
            mode = Mode.REORDER
            invalidate()
        } else startEdit(Hit(t.kind, t.id, 0), "move")
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            p.zoom = (p.zoom * detector.scaleFactor).coerceIn(0.05f, 12f)
            invalidate()
            return true
        }
    }).also { it.isQuickScaleEnabled = false }

    fun pps(): Float = dp(40f) * p.zoom
    private fun centerX(): Float = width / 2f
    private fun xOf(ms: Long): Float = VeScale.xOf(ms, p.playheadMs, pps(), centerX())

    /** Zoom yang membuat seluruh durasi muat di lebar tampilan (dengan ruang tombol + di ujung). */
    fun fitZoom(): Float {
        val total = max(1000L, p.totalMs())
        val usable = max(dp(80f), width - dp(150f))
        return (usable / (total / 1000f) / dp(40f)).coerceIn(0.05f, 12f)
    }

    // ------------------------------------------------------------------ layout

    private fun buildLayout() {
        blocks.clear()
        hintAudioTop = -1f
        hintTextTop = -1f
        val gap = dp(4f)
        val sec = dp(8f)
        var y = 0f
        if (p.audios.isEmpty()) {
            hintAudioTop = y
            y += laneH + sec
        } else {
            val n = p.audios.size
            val st = LongArray(n) { p.audios[it].startMs }
            val en = LongArray(n) { p.audios[it].endMs() }
            val lanes = VeLanes.assign(st, en)
            var count = 0
            for (i in 0 until n) {
                blocks.add(Block("audio", p.audios[i].id, st[i], en[i], y + lanes[i] * (audioH + gap), audioH, p.audios[i]))
                count = max(count, lanes[i] + 1)
            }
            y += count * (audioH + gap) + sec - gap
        }
        if (p.overlays.isEmpty()) {
            hintTextTop = y
            y += laneH + sec
        } else {
            val n = p.overlays.size
            val st = LongArray(n) { p.overlays[it].startMs }
            val en = LongArray(n) { p.overlays[it].endMs() }
            val lanes = VeLanes.assign(st, en)
            var count = 0
            for (i in 0 until n) {
                blocks.add(Block("overlay", p.overlays[i].id, st[i], en[i], y + lanes[i] * (laneH + gap), laneH, p.overlays[i]))
                count = max(count, lanes[i] + 1)
            }
            y += count * (laneH + gap) + sec - gap
        }
        if (p.effects.isNotEmpty()) {
            val n = p.effects.size
            val st = LongArray(n) { p.effects[it].startMs }
            val en = LongArray(n) { p.effects[it].endMs() }
            val lanes = VeLanes.assign(st, en)
            var count = 0
            for (i in 0 until n) {
                blocks.add(Block("effect", p.effects[i].id, st[i], en[i], y + lanes[i] * (laneH + gap), laneH, p.effects[i]))
                count = max(count, lanes[i] + 1)
            }
            y += count * (laneH + gap) + sec - gap
        }
        contentH = y
    }

    private fun maxScrollY(): Float = max(0f, contentH + dp(10f) - (height - lanesTop))

    private fun clampScroll() {
        scrollY = scrollY.coerceIn(0f, maxScrollY())
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        buildLayout()
        clampScroll()
        canvas.drawColor(cBg)
        drawLanes(canvas)
        drawMain(canvas)
        drawRuler(canvas)
        drawSnapGuide(canvas)
        drawPlayhead(canvas)
        if (mode == Mode.REORDER) drawReorder(canvas)
    }

    private fun drawRuler(canvas: Canvas) {
        val pps = pps()
        val step = VeScale.rulerStepMs(pps, dp(56f))
        val minor = max(1L, step / 5)
        val minorPx = minor / 1000f * pps
        val t0 = VeScale.timeAt(0f, p.playheadMs, pps, centerX())
        val t1 = VeScale.timeAt(width.toFloat(), p.playheadMs, pps, centerX())
        paint.style = Paint.Style.FILL
        paint.color = cBg
        canvas.drawRect(0f, 0f, width.toFloat(), rulerH, paint)
        textPaint.textSize = dp(10f)
        textPaint.color = cRulerText
        textPaint.typeface = Typeface.DEFAULT
        paint.strokeWidth = dp(1f)
        val drawMinor = minorPx >= dp(5f)
        val unit = if (drawMinor) minor else step
        var t = (floor(max(0L, t0) / unit.toDouble()).toLong()) * unit
        var guard = 0
        while (t <= t1 && guard++ < 4000) {
            val x = xOf(t)
            val major = t % step == 0L
            paint.color = cTick
            if (major) {
                canvas.drawLine(x, rulerH - dp(9f), x, rulerH - dp(1f), paint)
                val label = if (step < 1000L) VeTime.fmt(t, tenths = true) else VeTime.clock(t)
                canvas.drawText(label, x + dp(3f), rulerH - dp(11f), textPaint)
            } else {
                canvas.drawLine(x, rulerH - dp(4f), x, rulerH - dp(1f), paint)
            }
            t += unit
        }
        textPaint.typeface = Typeface.DEFAULT_BOLD
    }

    private fun drawPlayhead(canvas: Canvas) {
        val cx = centerX()
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = Color.argb(110, 0, 0, 0)
        paint.strokeWidth = dp(4f)
        canvas.drawLine(cx, rulerH - dp(6f), cx, height.toFloat(), paint)
        paint.color = Color.WHITE
        paint.strokeWidth = dp(2f)
        canvas.drawLine(cx, rulerH - dp(6f), cx, height.toFloat(), paint)
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(cx - dp(6f), 0f)
        path.lineTo(cx + dp(6f), 0f)
        path.lineTo(cx, dp(9f))
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawSnapGuide(canvas: Canvas) {
        val ms = snapGuideMs ?: return
        val x = xOf(ms)
        paint.style = Paint.Style.STROKE
        paint.color = Color.argb(190, 255, 255, 255)
        paint.strokeWidth = dp(1.2f)
        paint.pathEffect = dash
        canvas.drawLine(x, rulerH, x, height.toFloat(), paint)
        paint.pathEffect = null
    }

    private fun drawMain(canvas: Canvas) {
        val top = mainTop
        val bottom = mainTop + mainH
        var start = 0L
        for ((i, clip) in p.clips.withIndex()) {
            val dur = clip.timelineMs()
            val x0 = xOf(start)
            val x1 = xOf(start + dur)
            val dragged = mode == Mode.REORDER && editKind == "clip" && editId == clip.id
            if (x1 >= -dp(60f) && x0 <= width + dp(60f)) {
                drawClip(canvas, clip, x0, x1, top, bottom, p.selKind == "clip" && p.selId == clip.id, dragged)
            }
            if (i > 0 && dur * pps() / 1000f >= dp(36f) && !dragged) drawTransitionMarker(canvas, x0, (top + bottom) / 2f, clip.transition != "none")
            start += dur
        }
        val px0 = xOf(start) + dp(10f)
        rectF.set(px0, top + dp(4f), px0 + dp(46f), bottom - dp(4f))
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(238, 238, 242)
        canvas.drawRoundRect(rectF, dp(9f), dp(9f), paint)
        paint.color = Color.rgb(20, 20, 24)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.4f)
        paint.strokeCap = Paint.Cap.ROUND
        val cx = rectF.centerX()
        val cy = rectF.centerY()
        canvas.drawLine(cx - dp(8f), cy, cx + dp(8f), cy, paint)
        canvas.drawLine(cx, cy - dp(8f), cx, cy + dp(8f), paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawClip(canvas: Canvas, clip: VeClip, x0: Float, x1: Float, top: Float, bottom: Float, selected: Boolean, ghosted: Boolean) {
        val w = x1 - x0
        if (w <= 0.5f) return
        val r = dp(7f)
        rectF.set(x0 + dp(0.5f), top, x1 - dp(0.5f), bottom)
        paint.style = Paint.Style.FILL
        paint.color = cClip
        canvas.save()
        path.reset()
        path.addRoundRect(rectF, r, r, Path.Direction.CW)
        canvas.clipPath(path)
        canvas.drawRect(rectF, paint)
        val tileW = mainH
        val pps = pps()
        val frameH = mainH.toInt()
        val speed = clip.effectiveSpeed()
        val n = max(1, ceil(w / tileW).toInt())
        val i0 = max(0, floor((max(x0, 0f) - x0) / tileW).toInt())
        val i1 = min(n - 1, floor((min(x1, width.toFloat()) - x0) / tileW).toInt())
        val poster = p.thumbs[clip.uri.toString()]
        for (i in i0..i1) {
            val tx0 = x0 + i * tileW
            var tMs = 0L
            if (!clip.isImage) {
                val offsetMs = ((i + 0.5f) * tileW / pps * 1000f * speed).toLong()
                tMs = (clip.trimStartMs + offsetMs).coerceIn(clip.trimStartMs, max(clip.trimStartMs, clip.effectiveEnd() - 1))
            }
            var bmp = VeAssets.cachedFrame(clip.uri, clip.isImage, tMs, frameH)
            if (bmp == null) {
                VeAssets.requestFrame(context, clip.uri, clip.isImage, tMs, frameH) { invalidate() }
                bmp = poster
            }
            canvas.save()
            canvas.clipRect(tx0, top, min(tx0 + tileW, x1), bottom)
            if (bmp != null && !bmp.isRecycled) {
                val bw = bmp.width
                val bh = bmp.height
                val side = min(bw, bh)
                srcR.set((bw - side) / 2, (bh - side) / 2, (bw + side) / 2, (bh + side) / 2)
                dstF.set(tx0, top, tx0 + tileW, bottom)
                paint.alpha = if (ghosted) 90 else 255
                canvas.drawBitmap(bmp, srcR, dstF, paint)
                paint.alpha = 255
            }
            canvas.restore()
        }
        // gradasi gelap tipis di bawah agar lencana terbaca
        paint.color = Color.argb(if (ghosted) 40 else 90, 0, 0, 0)
        canvas.drawRect(rectF.left, bottom - dp(18f), rectF.right, bottom, paint)
        val label = buildString {
            append(VeTime.badge(clip.timelineMs()))
            if (!clip.isImage && clip.speed != 1f) append("  ").append(fmtSpeed(clip.speed))
        }
        textPaint.textSize = dp(10f)
        textPaint.color = Color.WHITE
        val lx = max(x0, 0f) + dp(7f)
        canvas.save()
        canvas.clipRect(rectF)
        canvas.drawText(label, min(lx, x1 - dp(8f) - textPaint.measureText(label)).coerceAtLeast(x0 + dp(5f)), bottom - dp(5f), textPaint)
        canvas.restore()
        canvas.restore()

        paint.style = Paint.Style.STROKE
        if (selected && !ghosted) {
            paint.color = Color.WHITE
            paint.strokeWidth = dp(2f)
            rectF.set(x0 + dp(1f), top + dp(1f), x1 - dp(1f), bottom - dp(1f))
            canvas.drawRoundRect(rectF, r, r, paint)
            drawHandles(canvas, x0, x1, top, bottom)
        } else {
            paint.color = Color.argb(70, 255, 255, 255)
            paint.strokeWidth = dp(1f)
            rectF.set(x0 + dp(0.5f), top + dp(0.5f), x1 - dp(0.5f), bottom - dp(0.5f))
            canvas.drawRoundRect(rectF, r, r, paint)
        }
    }

    private fun fmtSpeed(v: Float): String =
        if (v == v.toInt().toFloat()) "${v.toInt()}×" else String.format(java.util.Locale.US, "%.2f×", v).replace(Regex("0+×$"), "×").replace(".×", "×")

    private fun drawHandles(canvas: Canvas, x0: Float, x1: Float, top: Float, bottom: Float) {
        val hw = dp(11f)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        rectF.set(x0 - hw, top, x0, bottom)
        canvas.drawRoundRect(rectF, dp(5f), dp(5f), paint)
        rectF.set(x1, top, x1 + hw, bottom)
        canvas.drawRoundRect(rectF, dp(5f), dp(5f), paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(40, 40, 46)
        paint.strokeWidth = dp(1.6f)
        paint.strokeCap = Paint.Cap.ROUND
        val my = (top + bottom) / 2f
        for (dx in floatArrayOf(-hw * 0.62f, -hw * 0.34f)) canvas.drawLine(x0 + dx, my - dp(7f), x0 + dx, my + dp(7f), paint)
        for (dx in floatArrayOf(hw * 0.34f, hw * 0.62f)) canvas.drawLine(x1 + dx, my - dp(7f), x1 + dx, my + dp(7f), paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawTransitionMarker(canvas: Canvas, x: Float, y: Float, set: Boolean) {
        val r = dp(if (set) 10f else 9f)
        paint.style = Paint.Style.FILL
        paint.color = if (set) Color.rgb(240, 240, 244) else Color.argb(235, 24, 24, 28)
        canvas.drawCircle(x, y, r, paint)
        if (!set) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.2f)
            paint.color = Color.argb(200, 255, 255, 255)
            canvas.drawCircle(x, y, r, paint)
        }
        // simpul-dasi ⋈
        paint.style = Paint.Style.FILL
        paint.color = if (set) Color.rgb(20, 20, 24) else Color.WHITE
        val a = dp(4.2f)
        path.reset()
        path.moveTo(x - a, y - a); path.lineTo(x, y); path.lineTo(x - a, y + a); path.close()
        path.moveTo(x + a, y - a); path.lineTo(x, y); path.lineTo(x + a, y + a); path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawLanes(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(0f, lanesTop, width.toFloat(), height.toFloat())
        canvas.translate(0f, lanesTop - scrollY)
        if (hintAudioTop >= 0f) drawHint(canvas, hintAudioTop, "+  Tambahkan audio")
        if (hintTextTop >= 0f) drawHint(canvas, hintTextTop, "+  Tambahkan teks")
        for (b in blocks) drawBlock(canvas, b)
        canvas.restore()
    }

    private fun hintRect(top: Float, label: String, out: RectF) {
        textPaint.textSize = dp(11.5f)
        val w = textPaint.measureText(label) + dp(28f)
        val x = max(xOf(0L), dp(64f))
        out.set(x, top, x + w, top + laneH)
    }

    private fun drawHint(canvas: Canvas, top: Float, label: String) {
        hintRect(top, label, rectF)
        paint.style = Paint.Style.FILL
        paint.color = cHint
        canvas.drawRoundRect(rectF, dp(8f), dp(8f), paint)
        textPaint.color = Color.rgb(170, 170, 180)
        textPaint.textSize = dp(11.5f)
        canvas.drawText(label, rectF.left + dp(14f), rectF.centerY() + dp(4f), textPaint)
    }

    private fun blockSelected(b: Block): Boolean = p.selKind == b.kind && p.selId == b.id

    private fun drawBlock(canvas: Canvas, b: Block) {
        val x0 = xOf(b.startMs)
        val x1 = max(xOf(b.endMs), x0 + dp(6f))
        if (x1 < -dp(40f) || x0 > width + dp(40f)) return
        val r = dp(6f)
        val selected = blockSelected(b)
        rectF.set(x0, b.top, x1, b.top + b.h)
        paint.style = Paint.Style.FILL
        val ref = b.ref
        paint.color = when {
            ref is VeAudioItem -> cAudio
            ref is VeEffect -> cEffect
            ref is VeOverlay -> when (ref.kind) { "caption" -> cCaption; "sticker" -> cSticker; "image" -> cImage; else -> cText }
            else -> cText
        }
        canvas.drawRoundRect(rectF, r, r, paint)
        canvas.save()
        canvas.clipRect(rectF)
        val labelX = max(x0, 0f) + dp(7f)
        textPaint.textSize = dp(10.5f)
        textPaint.color = Color.WHITE
        when (ref) {
            is VeAudioItem -> {
                drawWave(canvas, ref, x0, x1, b.top, b.h)
                textPaint.color = Color.argb(220, 255, 255, 255)
                textPaint.textSize = dp(9.5f)
                val tag = when (ref.kind) { "suara" -> "Suara · "; "ekstrak" -> "Ekstrak · "; else -> "" }
                canvas.drawText(tag + ref.name, labelX, b.top + dp(11f), textPaint)
            }
            is VeOverlay -> {
                var tx = labelX
                if (ref.kind == "image") {
                    val uri = ref.imageUri
                    if (uri != null) {
                        val fh = laneH.toInt()
                        val bmp = VeAssets.cachedFrame(uri, true, 0L, fh)
                        if (bmp == null) VeAssets.requestFrame(context, uri, true, 0L, fh) { invalidate() }
                        else if (!bmp.isRecycled) {
                            val side = min(bmp.width, bmp.height)
                            srcR.set((bmp.width - side) / 2, (bmp.height - side) / 2, (bmp.width + side) / 2, (bmp.height + side) / 2)
                            dstF.set(labelX - dp(5f), b.top, labelX - dp(5f) + laneH, b.top + b.h)
                            canvas.drawBitmap(bmp, srcR, dstF, null)
                            tx = dstF.right + dp(5f)
                        }
                    }
                }
                val label = when (ref.kind) {
                    "image" -> "Gambar"
                    "sticker" -> if (ref.icon.isNotEmpty()) "Stiker" else ref.text.ifEmpty { "Stiker" }
                    else -> ref.text.replace('\n', ' ').ifEmpty { "Teks" }
                }
                canvas.drawText(label, tx, b.top + b.h / 2f + dp(4f), textPaint)
            }
            is VeEffect -> canvas.drawText(VeFxMath.label(ref.type), labelX, b.top + b.h / 2f + dp(4f), textPaint.apply { color = Color.rgb(16, 16, 20) })
        }
        canvas.restore()
        paint.style = Paint.Style.STROKE
        if (selected) {
            paint.color = Color.WHITE
            paint.strokeWidth = dp(1.8f)
            rectF.set(x0 + dp(0.9f), b.top + dp(0.9f), x1 - dp(0.9f), b.top + b.h - dp(0.9f))
            canvas.drawRoundRect(rectF, r, r, paint)
            drawHandles(canvas, x0, x1, b.top, b.top + b.h)
        } else {
            paint.color = Color.argb(50, 255, 255, 255)
            paint.strokeWidth = dp(1f)
            canvas.drawRoundRect(rectF, r, r, paint)
        }
    }

    private fun drawWave(canvas: Canvas, item: VeAudioItem, x0: Float, x1: Float, top: Float, h: Float) {
        val peaks = VeAssets.cachedWave(item.uri)
        if (peaks == null) {
            VeAssets.requestWave(context, item.uri) { invalidate() }
            return
        }
        val pps = pps()
        val colW = dp(2.2f)
        val first = max(x0, 0f)
        val last = min(x1, width.toFloat())
        if (last <= first) return
        val cols = ceil((last - first) / colW).toInt() + 2
        if (wavePts.size < cols * 4) wavePts = FloatArray(cols * 4)
        val mid = top + h * 0.64f
        val maxH = h * 0.34f
        val mx = max(1, VeAssets.waveMax(item.uri)).toFloat()
        val dur = item.durationMs()
        var n = 0
        var x = first
        while (x < last) {
            val tMs = VeScale.timeAt(x, p.playheadMs, pps, centerX())
            val rel = tMs - item.startMs
            if (rel in 0 until dur) {
                val idx = ((item.srcStartMs + rel) / 50L).toInt().coerceIn(0, peaks.size - 1)
                var amp = peaks[idx] / mx
                amp *= min(1.5f, item.volume) * VeMixer.fadeGain(rel.toInt(), dur.toInt(), item.fadeInMs.toInt(), item.fadeOutMs.toInt())
                val hh = max(dp(1f), min(1f, amp) * maxH)
                wavePts[n++] = x; wavePts[n++] = mid - hh; wavePts[n++] = x; wavePts[n++] = mid + hh
            }
            x += colW
        }
        paint.style = Paint.Style.STROKE
        paint.color = cWave
        paint.strokeWidth = dp(1.4f)
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLines(wavePts, 0, n, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawReorder(canvas: Canvas) {
        var start = 0L
        var dragged: VeClip? = null
        for (c in p.clips) if (c.id == editId) dragged = c
        val dc = dragged ?: return
        val dw = dc.timelineMs() * pps() / 1000f
        // penanda sisip
        var idx = 0
        var markerX = xOf(0L)
        for (c in p.clips) {
            val d = c.timelineMs()
            if (c.id != editId) {
                if (idx == reorderIndex) markerX = xOf(start)
                idx++
            }
            start += d
        }
        if (reorderIndex >= idx) markerX = xOf(start)
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = dp(3f)
        canvas.drawLine(markerX, mainTop - dp(2f), markerX, mainTop + mainH + dp(2f), paint)
        // bayangan klip yang diseret
        val gx0 = reorderX - dw / 2f
        drawClip(canvas, dc, gx0, gx0 + dw, mainTop - dp(5f), mainTop + mainH - dp(5f), selected = false, ghosted = false)
    }

    // ------------------------------------------------------------------ hit testing

    private fun handleHit(x: Float, x0: Float, x1: Float): Int {
        val hw = dp(26f)
        val narrow = (x1 - x0) < dp(44f)
        val mid = (x0 + x1) / 2f
        val inLeft = if (narrow) (x >= x0 - hw && x <= mid) else (x >= x0 - dp(14f) && x <= x0 + dp(16f))
        val inRight = if (narrow) (x > mid && x <= x1 + hw) else (x >= x1 - dp(16f) && x <= x1 + dp(14f))
        return when {
            inLeft && inRight -> if (abs(x - x0) <= abs(x - x1)) 1 else 2
            inLeft -> 1
            inRight -> 2
            else -> 0
        }
    }

    private fun hitTest(x: Float, y: Float): Hit? {
        buildLayout()
        val pps = pps()
        if (y >= mainTop - dp(4f) && y <= mainTop + mainH + dp(4f)) {
            if (p.selKind == "clip") {
                val c = p.clips.firstOrNull { it.id == p.selId }
                if (c != null) {
                    val s = p.clipStart(c)
                    val part = handleHit(x, xOf(s), xOf(s + c.timelineMs()))
                    if (part != 0) return Hit("clip", c.id, part)
                }
            }
            val total = p.totalMs()
            val px0 = xOf(total) + dp(10f)
            if (x >= px0 && x <= px0 + dp(46f)) return Hit("plus", "", 0)
            var s = 0L
            for ((i, c) in p.clips.withIndex()) {
                val d = c.timelineMs()
                if (i > 0 && d * pps / 1000f >= dp(36f) && abs(x - xOf(s)) <= dp(13f)) return Hit("trans", c.id, 0)
                s += d
            }
            s = 0L
            for (c in p.clips) {
                val d = c.timelineMs()
                if (x >= xOf(s) && x <= xOf(s + d)) return Hit("clip", c.id, 0)
                s += d
            }
            return null
        }
        if (y >= lanesTop) {
            val yc = y - lanesTop + scrollY
            for (b in blocks.asReversed()) {
                if (yc < b.top - dp(2f) || yc > b.top + b.h + dp(2f)) continue
                val x0 = xOf(b.startMs)
                val x1 = max(xOf(b.endMs), x0 + dp(6f))
                if (blockSelected(b)) {
                    val part = handleHit(x, x0, x1)
                    if (part != 0) return Hit(b.kind, b.id, part)
                }
                if (x >= x0 && x <= x1) return Hit(b.kind, b.id, 0)
            }
            if (hintAudioTop >= 0f && yc >= hintAudioTop && yc <= hintAudioTop + laneH) {
                hintRect(hintAudioTop, "+  Tambahkan audio", dstF)
                if (x >= dstF.left && x <= dstF.right) return Hit("hintAudio", "", 0)
            }
            if (hintTextTop >= 0f && yc >= hintTextTop && yc <= hintTextTop + laneH) {
                hintRect(hintTextTop, "+  Tambahkan teks", dstF)
                if (x >= dstF.left && x <= dstF.right) return Hit("hintText", "", 0)
            }
        }
        return null
    }

    private fun reorderIndexFor(x: Float): Int {
        var s = 0L
        var idx = 0
        for (c in p.clips) {
            val d = c.timelineMs()
            if (c.id != editId) {
                val cx = (xOf(s) + xOf(s + d)) / 2f
                if (cx < x) idx++
            }
            s += d
        }
        return idx
    }

    // ------------------------------------------------------------------ touch

    private fun startEdit(t: Hit, modeName: String) {
        editKind = t.kind
        editId = t.id
        editMode = modeName
        mode = Mode.EDIT
        parent?.requestDisallowInterceptTouchEvent(true)
        host?.onEditStart(t.kind, t.id, modeName)
    }

    private fun isSelectedHit(t: Hit): Boolean = p.selKind == t.kind && p.selId == t.id

    private fun decideMode(dx: Float, dy: Float) {
        val t = target
        if (t != null && t.part != 0 && (t.kind == "clip" || t.kind == "audio" || t.kind == "overlay" || t.kind == "effect")) {
            startEdit(t, if (t.part == 1) "trimL" else "trimR")
            return
        }
        if (t != null && (t.kind == "audio" || t.kind == "overlay" || t.kind == "effect") && isSelectedHit(t)) {
            startEdit(t, "move")
            return
        }
        if (abs(dy) > abs(dx) * 1.2f && downY >= lanesTop && maxScrollY() > 0f) {
            mode = Mode.VSCROLL
            return
        }
        mode = Mode.SCRUB
        host?.onScrubState(true)
    }

    private fun performTap() {
        val t = target
        when {
            downY < rulerH -> host?.onScrub(VeScale.timeAt(downX, p.playheadMs, pps(), centerX()).coerceIn(0L, p.totalMs()))
            t == null -> host?.onSelect("", null)
            t.kind == "plus" -> host?.onAddMedia()
            t.kind == "trans" -> host?.onTransition(t.id)
            t.kind == "hintAudio" -> host?.onHint("audio")
            t.kind == "hintText" -> host?.onHint("teks")
            else -> host?.onSelect(t.kind, t.id)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (scaleDetector.isInProgress) {
            if (mode != Mode.PINCH) {
                removeCallbacks(longPress)
                finishGesture(cancel = true)
                mode = Mode.PINCH
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                if (flinging) { flinging = false; host?.onScrubState(false) }
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = e.x
                downY = e.y
                startPlayhead = p.playheadMs
                startScrollY = scrollY
                target = hitTest(e.x, e.y)
                mode = Mode.PENDING
                parent?.requestDisallowInterceptTouchEvent(true)
                postDelayed(longPress, 380L)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                if (mode == Mode.PENDING) mode = Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.PINCH) return true
                velocity?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                when (mode) {
                    Mode.PENDING -> if (abs(dx) > slop || abs(dy) > slop) {
                        removeCallbacks(longPress)
                        decideMode(dx, dy)
                    }
                    Mode.SCRUB -> {
                        val ms = startPlayhead - (dx / pps() * 1000f).toLong()
                        host?.onScrub(ms.coerceIn(0L, p.totalMs()))
                    }
                    Mode.VSCROLL -> {
                        scrollY = (startScrollY - dy).coerceIn(0f, maxScrollY())
                        invalidate()
                    }
                    Mode.EDIT -> host?.onEdit(editKind, editId, editMode, (dx / pps() * 1000f).toLong())
                    Mode.REORDER -> {
                        reorderX = e.x
                        val idx = reorderIndexFor(e.x)
                        if (idx != reorderIndex) { reorderIndex = idx; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                        invalidate()
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                velocity?.addMovement(e)
                when (mode) {
                    Mode.PENDING -> performTap()
                    Mode.SCRUB -> startFling()
                    else -> finishGesture(cancel = false)
                }
                mode = Mode.NONE
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                finishGesture(cancel = true)
                mode = Mode.NONE
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    private fun finishGesture(cancel: Boolean) {
        when (mode) {
            Mode.EDIT -> host?.onEditEnd(editKind, editId, editMode)
            Mode.REORDER -> {
                if (!cancel) host?.onReorder(editId, reorderIndex)
                invalidate()
            }
            Mode.SCRUB -> host?.onScrubState(false)
            else -> {}
        }
    }

    private fun startFling() {
        val vt = velocity
        if (vt != null) {
            vt.computeCurrentVelocity(1000, maxFling)
            val vx = vt.xVelocity
            if (abs(vx) > max(minFling, dp(200f))) {
                val pps = pps()
                val startX = (p.playheadMs * pps / 1000f).toInt()
                val maxX = (p.totalMs() * pps / 1000f).toInt()
                flingPps = pps
                flinging = true
                scroller.fling(startX, 0, (-vx).toInt(), 0, 0, max(0, maxX), 0, 0)
                postInvalidateOnAnimation()
                return
            }
        }
        host?.onScrubState(false)
    }

    override fun computeScroll() {
        if (flinging) {
            if (scroller.computeScrollOffset()) {
                host?.onScrub((scroller.currX * 1000f / flingPps).toLong().coerceIn(0L, p.totalMs()))
                postInvalidateOnAnimation()
            } else {
                flinging = false
                host?.onScrubState(false)
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        scroller.forceFinished(true)
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }
}
