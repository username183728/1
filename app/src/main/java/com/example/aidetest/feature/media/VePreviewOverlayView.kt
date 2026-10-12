package com.example.aidetest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lapisan di atas pratinjau: menggambar teks/keterangan/stiker/gambar pada waktu playhead
 * (rendering yang sama dengan ekspor) dan memungkinkan edit langsung —
 * seret = pindah (magnet ke tengah), gagang kanan-bawah atau cubit = skala + putar,
 * × = hapus, T = edit teks.
 */
@SuppressLint("ClickableViewAccessibility")
internal class VePreviewOverlayView(context: Context) : View(context) {

    interface Host {
        fun onSelectOverlay(id: String?)
        fun onOverlayEditStart(id: String)
        fun onOverlayChanged(id: String)
        fun onOverlayEditEnd(id: String)
        fun onTapEmpty()
        fun onEditText(id: String)
        fun onDeleteOverlay(id: String)
    }

    var host: Host? = null
    var timeMs: Long = 0L
        set(value) { field = value; invalidate() }
    var playing = false

    /** Garis panduan (sepertiga + tengah) hanya di pratinjau — tidak ikut diekspor. */
    var showGuides = false
        set(value) { field = value; invalidate() }

    private val p = VeProject
    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val dash = DashPathEffect(floatArrayOf(dp(5f), dp(5f)), 0f)
    private val box = RectF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private enum class Mode { NONE, MOVE, HANDLE, PINCH, DELETE, EDIT }

    private var mode = Mode.NONE
    private var activeId: String? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var startCx = 0f
    private var startCy = 0f
    private var startScale = 1f
    private var startRot = 0f
    private var startDist = 1f
    private var startAngle = 0f
    private var guideV = false
    private var guideH = false
    private var lastTapAt = 0L
    private var lastTapId: String? = null
    private var started = false

    private fun overlayAt(id: String?): VeOverlay? = if (id == null) null else p.overlays.firstOrNull { it.id == id }

    private fun selectedOverlay(): VeOverlay? =
        if (p.selKind == "overlay") overlayAt(p.selId) else null

    private fun visibleAt(o: VeOverlay): Boolean = timeMs >= o.startMs && timeMs < o.endMs()

    private fun stateOf(o: VeOverlay, settle: Boolean): VeOverlayState =
        if (settle) VeOverlayState(true, 1f, 1f, 0f, 0f)
        else VeAnimMath.state(o.animIn, o.animOut, o.animMs, o.startMs, o.durationMs, timeMs)

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (showGuides) drawGrid(canvas)
        if (!p.showText) return
        val w = width
        val h = height
        val sel = selectedOverlay()
        for (o in p.overlays) {
            if (!visibleAt(o)) continue
            val settle = !playing && o === sel
            val st = stateOf(o, settle)
            if (!st.visible) continue
            VeOverlayRender.draw(
                context, canvas, o, w, h,
                (o.cx + st.dx) * w, (o.cy + st.dy) * h,
                st.alpha, st.scale, o.rotation, imageMaxSide = max(w, h)
            )
        }
        if (!playing && sel != null && visibleAt(sel)) drawSelection(canvas, sel)
        if (mode == Mode.MOVE || mode == Mode.HANDLE || mode == Mode.PINCH) drawGuides(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = Color.argb(110, 255, 255, 255)
        for (i in 1..2) {
            val x = width * i / 3f
            val y = height * i / 3f
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }
        paint.color = Color.argb(150, 255, 255, 255)
        paint.pathEffect = dash
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, paint)
        paint.pathEffect = null
        // zona aman 5%
        paint.color = Color.argb(70, 255, 255, 255)
        canvas.drawRect(width * 0.05f, height * 0.05f, width * 0.95f, height * 0.95f, paint)
    }

    private class Corners(val cx: Float, val cy: Float, val bw: Float, val bh: Float, val rot: Float)

    private fun cornersOf(o: VeOverlay): Corners {
        val b = VeOverlayRender.measure(context, o, width, height)
        return Corners(o.cx * width, o.cy * height, b.w.toFloat(), b.h.toFloat(), o.rotation)
    }

    /** Titik lokal (relatif pusat, belum diputar) → koordinat layar. */
    private fun toScreen(c: Corners, lx: Float, ly: Float): FloatArray {
        val r = Math.toRadians(c.rot.toDouble())
        val cs = cos(r).toFloat()
        val sn = sin(r).toFloat()
        return floatArrayOf(c.cx + lx * cs - ly * sn, c.cy + lx * sn + ly * cs)
    }

    private fun drawSelection(canvas: Canvas, o: VeOverlay) {
        val c = cornersOf(o)
        val pad = dp(4f)
        val hw = c.bw / 2f + pad
        val hh = c.bh / 2f + pad
        canvas.save()
        canvas.translate(c.cx, c.cy)
        canvas.rotate(c.rot)
        box.set(-hw, -hh, hw, hh)
        paint.style = Paint.Style.STROKE
        paint.color = Color.argb(120, 0, 0, 0)
        paint.strokeWidth = dp(3.2f)
        canvas.drawRoundRect(box, dp(3f), dp(3f), paint)
        paint.color = Color.WHITE
        paint.strokeWidth = dp(1.5f)
        canvas.drawRoundRect(box, dp(3f), dp(3f), paint)
        canvas.restore()

        val tl = toScreen(c, -hw, -hh)
        val tr = toScreen(c, hw, -hh)
        val br = toScreen(c, hw, hh)
        drawControl(canvas, tl[0], tl[1], "x")
        if (o.kind == "text" || o.kind == "caption") drawControl(canvas, tr[0], tr[1], "t")
        drawControl(canvas, br[0], br[1], "scale")
    }

    private fun drawControl(canvas: Canvas, x: Float, y: Float, kind: String) {
        val r = dp(12f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(235, 255, 255, 255)
        canvas.drawCircle(x, y, r, paint)
        paint.color = Color.rgb(20, 20, 24)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.8f)
        paint.strokeCap = Paint.Cap.ROUND
        when (kind) {
            "x" -> {
                val a = dp(4.2f)
                canvas.drawLine(x - a, y - a, x + a, y + a, paint)
                canvas.drawLine(x - a, y + a, x + a, y - a, paint)
            }
            "t" -> {
                textPaint.color = Color.rgb(20, 20, 24)
                textPaint.textSize = dp(13f)
                canvas.drawText("T", x, y + dp(4.6f), textPaint)
            }
            else -> {
                val a = dp(4.4f)
                canvas.drawLine(x - a, y - a, x + a, y + a, paint)
                canvas.drawLine(x - a, y - a, x - a + dp(3f), y - a, paint)
                canvas.drawLine(x - a, y - a, x - a, y - a + dp(3f), paint)
                canvas.drawLine(x + a, y + a, x + a - dp(3f), y + a, paint)
                canvas.drawLine(x + a, y + a, x + a, y + a - dp(3f), paint)
            }
        }
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawGuides(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.2f)
        paint.pathEffect = dash
        paint.color = Color.argb(230, 255, 255, 255)
        if (guideV) canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
        if (guideH) canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, paint)
        paint.pathEffect = null
    }

    // ------------------------------------------------------------------ hit testing

    private fun hitOverlay(x: Float, y: Float): VeOverlay? {
        if (!p.showText) return null
        for (o in p.overlays.asReversed()) {
            if (!visibleAt(o)) continue
            val c = cornersOf(o)
            val r = Math.toRadians(-c.rot.toDouble())
            val dx = x - c.cx
            val dy = y - c.cy
            val lx = (dx * cos(r) - dy * sin(r)).toFloat()
            val ly = (dx * sin(r) + dy * cos(r)).toFloat()
            val tol = dp(10f)
            if (abs(lx) <= c.bw / 2f + tol && abs(ly) <= c.bh / 2f + tol) return o
        }
        return null
    }

    private fun near(x: Float, y: Float, px: Float, py: Float): Boolean = hypot(x - px, y - py) <= dp(22f)

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                moved = false
                started = false
                mode = Mode.NONE
                activeId = null
                if (playing) return true
                val sel = selectedOverlay()
                if (sel != null && visibleAt(sel)) {
                    val c = cornersOf(sel)
                    val pad = dp(4f)
                    val hw = c.bw / 2f + pad
                    val hh = c.bh / 2f + pad
                    val tl = toScreen(c, -hw, -hh)
                    val tr = toScreen(c, hw, -hh)
                    val br = toScreen(c, hw, hh)
                    when {
                        near(e.x, e.y, tl[0], tl[1]) -> { mode = Mode.DELETE; activeId = sel.id; return true }
                        (sel.kind == "text" || sel.kind == "caption") && near(e.x, e.y, tr[0], tr[1]) -> { mode = Mode.EDIT; activeId = sel.id; return true }
                        near(e.x, e.y, br[0], br[1]) -> {
                            mode = Mode.HANDLE
                            activeId = sel.id
                            startScale = sel.scale
                            startRot = sel.rotation
                            startDist = max(1f, hypot(e.x - c.cx, e.y - c.cy))
                            startAngle = Math.toDegrees(atan2((e.y - c.cy).toDouble(), (e.x - c.cx).toDouble())).toFloat()
                            return true
                        }
                    }
                }
                val hit = hitOverlay(e.x, e.y)
                if (hit != null) {
                    if (p.selKind != "overlay" || p.selId != hit.id) host?.onSelectOverlay(hit.id)
                    mode = Mode.MOVE
                    activeId = hit.id
                    startCx = hit.cx
                    startCy = hit.cy
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (playing) return true
                val o = selectedOverlay()
                if (o != null && e.pointerCount == 2) {
                    if (started) host?.onOverlayEditEnd(o.id)
                    mode = Mode.PINCH
                    activeId = o.id
                    startScale = o.scale
                    startRot = o.rotation
                    startDist = max(1f, hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)))
                    startAngle = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
                    started = false
                    host?.onOverlayEditStart(o.id)
                    started = true
                    moved = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val id = activeId
                val o = overlayAt(id)
                if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) moved = true
                if (o == null || id == null) return true
                when (mode) {
                    Mode.MOVE -> if (moved) {
                        if (!started) { started = true; host?.onOverlayEditStart(id) }
                        var nx = (startCx + (e.x - downX) / width).coerceIn(0f, 1f)
                        var ny = (startCy + (e.y - downY) / height).coerceIn(0f, 1f)
                        val thr = dp(6f)
                        val sv = abs(nx * width - width / 2f) < thr
                        val sh = abs(ny * height - height / 2f) < thr
                        if (sv) nx = 0.5f
                        if (sh) ny = 0.5f
                        if ((sv && !guideV) || (sh && !guideH)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        guideV = sv
                        guideH = sh
                        o.cx = nx
                        o.cy = ny
                        host?.onOverlayChanged(id)
                        invalidate()
                    }
                    Mode.HANDLE -> {
                        if (!started) { started = true; host?.onOverlayEditStart(id) }
                        val c = cornersOf(o)
                        val dist = max(1f, hypot(e.x - c.cx, e.y - c.cy))
                        val ang = Math.toDegrees(atan2((e.y - c.cy).toDouble(), (e.x - c.cx).toDouble())).toFloat()
                        o.scale = (startScale * dist / startDist).coerceIn(0.1f, 6f)
                        o.rotation = snapAngle(normAngle(startRot + (ang - startAngle)))
                        moved = true
                        host?.onOverlayChanged(id)
                        invalidate()
                    }
                    Mode.PINCH -> if (e.pointerCount >= 2) {
                        val dist = max(1f, hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)))
                        val ang = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
                        o.scale = (startScale * dist / startDist).coerceIn(0.1f, 6f)
                        o.rotation = snapAngle(normAngle(startRot + (ang - startAngle)))
                        host?.onOverlayChanged(id)
                        invalidate()
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.PINCH) {
                    activeId?.let { id -> if (started) host?.onOverlayEditEnd(id) }
                    started = false
                    mode = Mode.NONE
                    guideV = false
                    guideH = false
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val id = activeId
                when (mode) {
                    Mode.DELETE -> if (id != null && !moved) host?.onDeleteOverlay(id)
                    Mode.EDIT -> if (id != null && !moved) host?.onEditText(id)
                    Mode.MOVE, Mode.HANDLE -> {
                        if (id != null && started) host?.onOverlayEditEnd(id)
                        if (mode == Mode.MOVE && !moved && id != null) {
                            // ketuk dua kali pada teks = edit teks
                            val now = SystemClock.uptimeMillis()
                            val o = overlayAt(id)
                            if (lastTapId == id && now - lastTapAt < 350L && o != null && (o.kind == "text" || o.kind == "caption")) {
                                host?.onEditText(id)
                                lastTapId = null
                            } else { lastTapId = id; lastTapAt = now }
                        }
                    }
                    Mode.PINCH -> {}
                    Mode.NONE -> if (!moved) {
                        // Ketuk area kosong: batal pilih teks dulu; bila tidak ada pilihan, putar/jeda.
                        if (!playing && p.selKind == "overlay") host?.onSelectOverlay(null) else host?.onTapEmpty()
                    }
                }
                mode = Mode.NONE
                started = false
                guideV = false
                guideH = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                activeId?.let { id -> if (started) host?.onOverlayEditEnd(id) }
                mode = Mode.NONE
                started = false
                guideV = false
                guideH = false
                invalidate()
            }
        }
        return true
    }

    private fun normAngle(a: Float): Float {
        var v = a % 360f
        if (v > 180f) v -= 360f
        if (v < -180f) v += 360f
        return v
    }

    /** Magnet sudut ke kelipatan 45° dalam ±4°. */
    private fun snapAngle(a: Float): Float {
        for (t in floatArrayOf(-180f, -135f, -90f, -45f, 0f, 45f, 90f, 135f, 180f)) if (abs(a - t) < 4f) return if (t == 180f || t == -180f) 180f else t
        return a
    }
}
