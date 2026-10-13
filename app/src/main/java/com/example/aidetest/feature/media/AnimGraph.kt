package com.example.aidetest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Graph Editor Animation Studio: kurva nilai (tebal) & kecepatan (tipis) satu segmen keyframe,
 * sumbu x = waktu 0..1, sumbu y = nilai −0,5..1,5. Dua titik kendali bezier bisa diseret; menyeret
 * mengubah kurva segmen menjadi cubic-bezier (AnEasing).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class AnGraphView(ctx: Context, private val c: EditorChrome) : View(ctx) {
    var curve: AnCurve = AnCurve.InOut
    var onDragStart: (() -> Unit)? = null
    var onChange: ((AnCurve.Bezier) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var drag = -1
    private val yLo = -0.5f
    private val yHi = 1.5f

    init { contentDescription = "Graph Editor kurva easing" }

    private fun pad(): Float = c.dp(16).toFloat()
    private fun px(x: Float): Float = pad() + x * (width - 2 * pad())
    private fun py(y: Float): Float = height - pad() - (y.coerceIn(yLo, yHi) - yLo) / (yHi - yLo) * (height - 2 * pad())
    private fun vx(x: Float): Float = ((x - pad()) / (width - 2 * pad()).coerceAtLeast(1f)).coerceIn(0f, 1f)
    private fun vy(y: Float): Float = (yLo + (height - pad() - y) / (height - 2 * pad()).coerceAtLeast(1f) * (yHi - yLo)).coerceIn(yLo, yHi)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.reset()
        paint.isAntiAlias = true
        paint.color = c.surface2
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), c.dp(12).toFloat(), c.dp(12).toFloat(), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = c.stroke
        for (i in 0..4) { val x = px(i / 4f); canvas.drawLine(x, py(yLo), x, py(yHi), paint) }
        paint.strokeWidth = c.dp(1).toFloat()
        paint.color = c.muted
        canvas.drawLine(px(0f), py(0f), px(1f), py(0f), paint)
        canvas.drawLine(px(0f), py(1f), px(1f), py(1f), paint)
        // Kecepatan (dinormalisasi ke tinggi 0..1).
        val n = 96
        var maxSp = 0.001f
        val sp = FloatArray(n + 1) { i -> abs(AnEasing.speed(curve, i / n.toFloat())).also { maxSp = max(maxSp, it) } }
        path.reset()
        for (i in 0..n) {
            val x = px(i / n.toFloat())
            val y = py(sp[i] / maxSp)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
        // Nilai.
        path.reset()
        for (i in 0..n) {
            val x = px(i / n.toFloat())
            val y = py(AnEasing.apply(curve, i / n.toFloat()))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.color = c.ink
        paint.strokeWidth = c.dp(3).toFloat()
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(path, paint)
        // Titik kendali (abu-abu = perkiraan dari kurva non-bezier).
        val h = AnEasing.handlesOf(curve)
        val ink = if (curve is AnCurve.Bezier) c.ink else c.muted
        paint.strokeWidth = c.dp(1).toFloat()
        paint.color = ink
        canvas.drawLine(px(0f), py(0f), px(h.x1), py(h.y1), paint)
        canvas.drawLine(px(1f), py(1f), px(h.x2), py(h.y2), paint)
        val r = c.dp(8).toFloat()
        for ((x, y) in listOf(h.x1 to h.y1, h.x2 to h.y2)) {
            paint.style = Paint.Style.FILL
            paint.color = c.surface
            canvas.drawCircle(px(x), py(y), r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = c.dp(2).toFloat()
            paint.color = ink
            canvas.drawCircle(px(x), py(y), r, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val h = AnEasing.handlesOf(curve)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val d1 = abs(event.x - px(h.x1)) + abs(event.y - py(h.y1))
                val d2 = abs(event.x - px(h.x2)) + abs(event.y - py(h.y2))
                val tol = c.dp(36)
                drag = when {
                    d1 <= d2 && d1 < tol -> 0
                    d2 < tol -> 1
                    else -> -1
                }
                if (drag < 0) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                onDragStart?.invoke()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag < 0) return false
                val x = vx(event.x)
                val y = vy(event.y)
                val b = if (drag == 0) AnCurve.Bezier(x, y, h.x2, h.y2) else AnCurve.Bezier(h.x1, h.y1, x, y)
                // Presisi sama dengan string tersimpan (3 desimal) agar tampilan = data.
                val norm = AnEasing.parse(AnEasing.format(b)) as? AnCurve.Bezier ?: b
                curve = norm
                onChange?.invoke(norm)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag >= 0) { drag = -1; onDragEnd?.invoke() }
                return true
            }
        }
        return true
    }
}
