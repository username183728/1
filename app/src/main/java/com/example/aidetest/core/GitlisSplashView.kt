package com.example.aidetest

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Text-only GitLis startup screen.
 * No icon, spinner, progress indicator, image, or loading graphic.
 *
 * Huruf disusun horizontal dalam satu LinearLayout sehingga kata "GitLis" terbaca utuh
 * (versi sebelumnya menumpuk semua huruf di titik yang sama).
 * Splash menyerap sentuhan agar tap tidak tembus ke UI di bawahnya, dan melewati animasi
 * saat mode Motion = OFF.
 */
class GitlisSplashView(context: Context) : FrameLayout(context) {

    private val word = "GitLis"
    private val letterViews = mutableListOf<TextView>()
    private var finished = false
    private var runningAnimation: AnimatorSet? = null

    init {
        setBackgroundColor(Color.WHITE)
        isClickable = true
        isFocusable = true
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (letter in word) {
            val tv = TextView(context).apply {
                text = letter.toString()
                textSize = 42f
                setTextColor(Color.BLACK)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                alpha = 0f
            }
            row.addView(tv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            letterViews += tv
        }
        addView(row, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
    }

    fun start(onDone: () -> Unit) {
        if (finished) return

        // Motion OFF: tanpa animasi, splash langsung dilepas.
        if (!Motion.enabled) {
            finish(onDone)
            return
        }

        val step = if (Motion.mode == Motion.Mode.HIGH) 110L else 70L

        val show = AnimatorSet()
        show.playSequentially(
            letterViews.map { v ->
                ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f).apply { duration = step }
            }
        )

        val hide = AnimatorSet()
        hide.playSequentially(
            letterViews.reversed().map { v ->
                ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0f).apply { duration = step }
            }
        )

        val all = AnimatorSet()
        all.playSequentially(show, hide)
        all.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { finish(onDone) }
        })
        runningAnimation = all
        all.start()
    }

    private fun finish(onDone: () -> Unit) {
        if (finished) return
        finished = true
        onDone()
        (parent as? ViewGroup)?.removeView(this)
    }

    override fun onDetachedFromWindow() {
        // Hentikan animator bila Activity ditutup saat splash masih tampil.
        runningAnimation?.removeAllListeners()
        runningAnimation?.cancel()
        runningAnimation = null
        super.onDetachedFromWindow()
    }
}
