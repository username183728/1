package com.example.aidetest

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout

/**
 * Circular reveal yang dikendalikan oleh tahan (hold) pada ikon Bit di navbar.
 *
 * - Saat ikon ditahan, lingkaran tumbuh perlahan dari pusat ikon dan menutupi
 *   layar sedikit demi sedikit (disertai getaran halus per tahap).
 * - Jika jari dilepas sebelum penuh, lingkaran menyusut kembali ke ikon.
 * - Jika layar tertutup penuh, Bit Room dibuka di bawah lingkaran, lalu
 *   lingkaran memudar sehingga transisi terasa menyatu.
 */
internal class BitHoldReveal(private val activity: MainActivity) {

    companion object {
        private const val ARM_DELAY_MS = 160L      // tap singkat tidak memicu animasi
        private const val GROW_MS = 1900L          // total waktu menahan sampai penuh
        private const val FADE_OUT_MS = 460L
    }

    private var overlay: RevealView? = null
    private var grow: ValueAnimator? = null
    private var shrink: ValueAnimator? = null
    private var armed: Runnable? = null
    private var anchorView: View? = null
    private var onComplete: (() -> Unit)? = null
    private var lastTick = 0

    /** true bila gesture ini sudah memulai/menyelesaikan reveal (supaya klik biasa diabaikan). */
    var consumedGesture = false
        private set
    private var completed = false

    private var downX = 0f
    private var downY = 0f

    fun arm(anchor: View, rawX: Float, rawY: Float, onComplete: () -> Unit) {
        release(force = true)
        downX = rawX
        downY = rawY
        consumedGesture = false
        completed = false
        lastTick = 0
        anchorView = anchor
        this.onComplete = onComplete
        val r = Runnable { begin() }
        armed = r
        anchor.postDelayed(r, ARM_DELAY_MS)
    }

    private fun begin() {
        val anchor = anchorView ?: return
        armed = null
        consumedGesture = true
        anchor.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

        if (!Motion.enabled) {
            // Gerakan dimatikan: tanpa overlay, buka setelah jeda singkat.
            val r = Runnable { finishHold() }
            armed = r
            anchor.postDelayed(r, 350L)
            return
        }

        val decor = activity.window.decorView as ViewGroup
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val decorLoc = IntArray(2)
        decor.getLocationOnScreen(decorLoc)
        val cx = loc[0] - decorLoc[0] + anchor.width / 2f
        val cy = loc[1] - decorLoc[1] + anchor.height / 2f
        val w = decor.width.toFloat()
        val h = decor.height.toFloat()
        val maxR = kotlin.math.hypot(maxOf(cx, w - cx).toDouble(), maxOf(cy, h - cy).toDouble()).toFloat()

        val dark = activity.isDarkTheme
        val view = RevealView(
            activity,
            fill = if (dark) Color.rgb(10, 11, 14) else Color.rgb(248, 249, 251),
            ring = if (dark) Color.rgb(120, 124, 140) else Color.rgb(176, 180, 196),
            stroke = activity.dp(2).toFloat()
        ).apply {
            this.cx = cx; this.cy = cy; this.maxR = maxR
            isClickable = false
            isFocusable = false
            translationZ = activity.dp(40).toFloat()
        }
        overlay = view
        decor.addView(view, FrameLayout.LayoutParams(-1, -1))

        grow = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GROW_MS
            // Awal pelan, lalu melaju stabil — terasa "menyebar sedikit demi sedikit".
            interpolator = AccelerateInterpolator(0.65f)
            addUpdateListener {
                val p = it.animatedValue as Float
                view.progress = p
                val stage = (p * 4f).toInt()
                if (stage > lastTick && stage < 4) {
                    lastTick = stage
                    anchor.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) { canceled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) finishHold()
                }
            })
            start()
        }
    }

    private fun finishHold() {
        if (completed) return
        completed = true
        armed = null
        anchorView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val cb = onComplete
        onComplete = null
        cb?.invoke()
        val v = overlay
        if (v == null) return
        // Biarkan Bit Room tampil di bawah, lalu lingkaran memudar.
        v.animate().alpha(0f).setStartDelay(80L).setDuration(FADE_OUT_MS)
            .setInterpolator(LinearInterpolator())
            .withEndAction { removeOverlay(v) }
            .start()
    }

    /** Jari bergeser terlalu jauh dari ikon -> batalkan (toleransi getar jari 28dp). */
    fun onMove(rawX: Float, rawY: Float) {
        if (completed || (armed == null && overlay == null)) return
        val d = kotlin.math.hypot((rawX - downX).toDouble(), (rawY - downY).toDouble())
        if (d > activity.dp(28)) release()
    }

    /** Dipanggil saat jari dilepas / gesture berubah jadi drag. */
    fun release(force: Boolean = false) {
        if (completed) return
        armed?.let { anchorView?.removeCallbacks(it) }
        armed = null
        grow?.let { it.cancel() }
        grow = null
        val v = overlay ?: return
        val from = v.progress
        if (force || from <= 0.001f) { removeOverlay(v); return }
        // Menyusut kembali ke ikon: makin besar, makin lama, tapi tetap ringkas.
        shrink?.cancel()
        shrink = ValueAnimator.ofFloat(from, 0f).apply {
            duration = (260L + (from * 420L)).toLong()
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { v.progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { removeOverlay(v) }
                override fun onAnimationCancel(animation: Animator) { removeOverlay(v) }
            })
            start()
        }
    }

    private fun removeOverlay(v: RevealView) {
        v.animate().cancel()
        (v.parent as? ViewGroup)?.removeView(v)
        if (overlay === v) overlay = null
    }

    private class RevealView(
        context: Context,
        fill: Int,
        ring: Int,
        private val stroke: Float
    ) : View(context) {
        var cx = 0f
        var cy = 0f
        var maxR = 0f
        var progress = 0f
            set(value) { field = value; invalidate() }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = Paint.Style.FILL }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ring; style = Paint.Style.STROKE; strokeWidth = stroke
        }

        override fun onDraw(canvas: Canvas) {
            if (progress <= 0f) return
            val r = maxR * progress
            // Awalnya agak transparan, makin pekat saat makin besar.
            fillPaint.alpha = (150 + 105 * progress).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, r, fillPaint)
            ringPaint.alpha = (200 * (1f - progress)).toInt().coerceIn(0, 255)
            if (ringPaint.alpha > 0) canvas.drawCircle(cx, cy, r, ringPaint)
        }

        override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false
    }
}
