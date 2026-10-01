package com.example.aidetest

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Logo Publisher milik GITLS: kubus heksagon dengan panah naik dari dasar.
 * Sengaja bukan logo GitHub.
 */
class PublishLogoView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val hex = Path()
    private val arrow = Path()

    var color: Int = Color.BLACK
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = minOf(w, h) / 2f * 0.88f
        val cx = w / 2f
        val cy = h / 2f
        paint.color = color
        paint.strokeWidth = r * 0.17f

        paint.pathEffect = CornerPathEffect(r * 0.2f)
        hex.reset()
        for (i in 0 until 6) {
            val a = Math.toRadians((-90 + 60 * i).toDouble())
            val x = cx + r * Math.cos(a).toFloat()
            val y = cy + r * Math.sin(a).toFloat()
            if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
        }
        hex.close()
        canvas.drawPath(hex, paint)
        paint.pathEffect = null

        arrow.reset()
        arrow.moveTo(cx, cy + r * 0.26f)
        arrow.lineTo(cx, cy - r * 0.34f)
        arrow.moveTo(cx - r * 0.3f, cy - r * 0.04f)
        arrow.lineTo(cx, cy - r * 0.36f)
        arrow.lineTo(cx + r * 0.3f, cy - r * 0.04f)
        canvas.drawPath(arrow, paint)

        paint.strokeWidth = r * 0.13f
        canvas.drawLine(cx - r * 0.24f, cy + r * 0.6f, cx + r * 0.24f, cy + r * 0.6f, paint)
    }
}

/** Cincin progres dengan animasi halus. Mode indeterminate memutar busur pendek. */
class ProgressRingView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private var shown = 0f
    private var progressAnim: ValueAnimator? = null
    private var spin = 0f
    private var spinAnim: ValueAnimator? = null

    var ringColor: Int = Color.BLACK
        set(value) { field = value; invalidate() }
    var trackColor: Int = Color.LTGRAY
        set(value) { field = value; invalidate() }

    var indeterminate: Boolean = false
        set(value) {
            field = value
            if (value) startSpin() else stopSpin()
            invalidate()
        }

    fun setProgress(percent: Float) {
        val target = percent.coerceIn(0f, 100f)
        progressAnim?.cancel()
        progressAnim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 380
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun startSpin() {
        if (spinAnim != null) return
        spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1100
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { spin = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopSpin() {
        spinAnim?.cancel()
        spinAnim = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (indeterminate) startSpin()
    }

    override fun onDetachedFromWindow() {
        progressAnim?.cancel()
        stopSpin()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = minOf(width, height) * 0.055f
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        trackPaint.color = trackColor
        arcPaint.color = ringColor
        val inset = stroke / 2f + 1f
        rect.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)
        if (indeterminate) {
            canvas.drawArc(rect, spin - 90f, 96f, false, arcPaint)
        } else if (shown > 0.5f) {
            canvas.drawArc(rect, -90f, 360f * shown / 100f, false, arcPaint)
        }
    }
}

/** Ikon status satu langkah: menunggu, berjalan, selesai, atau gagal. */
class StepStateView(context: Context) : View(context) {
    companion object {
        const val PENDING = 0
        const val ACTIVE = 1
        const val DONE = 2
        const val FAILED = 3
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val mark = Path()
    private var state = PENDING
    private var pop = 1f
    private var popAnim: ValueAnimator? = null
    private var spin = 0f
    private var spinAnim: ValueAnimator? = null

    var inkColor = Color.BLACK
    var onInkColor = Color.WHITE
    var mutedColor = Color.LTGRAY
    var dangerColor = Color.RED

    fun setState(newState: Int, animate: Boolean = true) {
        if (newState == state) return
        state = newState
        if (newState == ACTIVE) startSpin() else stopSpin()
        popAnim?.cancel()
        if (animate && (newState == DONE || newState == FAILED)) {
            popAnim = ValueAnimator.ofFloat(0.55f, 1f).apply {
                duration = 340
                interpolator = OvershootInterpolator(2.2f)
                addUpdateListener { pop = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            pop = 1f
        }
        invalidate()
    }

    private fun startSpin() {
        if (spinAnim != null) return
        spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { spin = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopSpin() {
        spinAnim?.cancel()
        spinAnim = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (state == ACTIVE) startSpin()
    }

    override fun onDetachedFromWindow() {
        popAnim?.cancel()
        stopSpin()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 1.5f
        val stroke = r * 0.2f
        when (state) {
            PENDING -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke
                paint.color = mutedColor
                canvas.drawCircle(cx, cy, r - stroke / 2f, paint)
            }
            ACTIVE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = mutedColor
                canvas.drawCircle(cx, cy, r - stroke / 2f, paint)
                paint.color = inkColor
                val inset = stroke / 2f + 1.5f
                rect.set(inset, inset, width - inset, height - inset)
                canvas.drawArc(rect, spin - 90f, 100f, false, paint)
            }
            DONE, FAILED -> {
                val rr = r * pop
                paint.style = Paint.Style.FILL
                paint.color = if (state == DONE) inkColor else dangerColor
                canvas.drawCircle(cx, cy, rr, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = rr * 0.17f
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = if (state == DONE) onInkColor else Color.WHITE
                mark.reset()
                if (state == DONE) {
                    mark.moveTo(cx - rr * 0.36f, cy + rr * 0.02f)
                    mark.lineTo(cx - rr * 0.08f, cy + rr * 0.3f)
                    mark.lineTo(cx + rr * 0.4f, cy - rr * 0.26f)
                } else {
                    mark.moveTo(cx - rr * 0.28f, cy - rr * 0.28f)
                    mark.lineTo(cx + rr * 0.28f, cy + rr * 0.28f)
                    mark.moveTo(cx + rr * 0.28f, cy - rr * 0.28f)
                    mark.lineTo(cx - rr * 0.28f, cy + rr * 0.28f)
                }
                canvas.drawPath(mark, paint)
            }
        }
    }
}

/** Badge sukses besar: lingkaran muncul membesar lalu tanda centang tergambar. */
class SuccessBadgeView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val check = Path()
    private val partial = Path()
    private var t = 0f
    private var anim: ValueAnimator? = null

    var inkColor = Color.BLACK
    var onInkColor = Color.WHITE

    fun play() {
        anim?.cancel()
        t = 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 820
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        val circleT = (t / 0.5f).coerceIn(0f, 1f)
        val scale = OvershootInterpolator(1.8f).getInterpolation(circleT)
        fill.color = inkColor
        canvas.drawCircle(cx, cy, r * scale, fill)

        val checkT = ((t - 0.4f) / 0.6f).coerceIn(0f, 1f)
        if (checkT <= 0f) return
        check.reset()
        check.moveTo(cx - r * 0.3f, cy + r * 0.02f)
        check.lineTo(cx - r * 0.08f, cy + r * 0.24f)
        check.lineTo(cx + r * 0.32f, cy - r * 0.2f)
        val measure = PathMeasure(check, false)
        partial.reset()
        measure.getSegment(0f, measure.length * checkT, partial, true)
        stroke.color = onInkColor
        stroke.strokeWidth = r * 0.13f
        canvas.drawPath(partial, stroke)
    }
}
