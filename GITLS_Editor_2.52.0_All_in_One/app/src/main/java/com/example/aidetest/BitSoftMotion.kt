package com.example.aidetest

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import java.util.WeakHashMap

/**
 * Soft / mochi motion helpers for Bit.
 * Anticipation → Action → Follow-through → Settle.
 * Never fights exclusive drag; cancels cleanly on new scene.
 */
object BitSoftMotion {
    private val softEase = PathInterpolator(0.22f, 0.61f, 0.36f, 1f)
    private val softIn = DecelerateInterpolator(1.6f)
    private val softOut = AccelerateDecelerateInterpolator()
    private val overshootSoft = OvershootInterpolator(1.15f)

    private val running = WeakHashMap<View, Animator>()

    fun cancel(view: View?) {
        view ?: return
        running.remove(view)?.cancel()
        view.animate().cancel()
    }

    fun resetPose(view: View?) {
        view ?: return
        cancel(view)
        view.scaleX = 1f
        view.scaleY = 1f
        view.rotation = 0f
        view.translationX = 0f
        view.translationY = 0f
        view.alpha = 1f
    }

    private fun track(view: View, anim: Animator) {
        cancel(view)
        running[view] = anim
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (running[view] === animation) running.remove(view)
            }
            override fun onAnimationCancel(animation: Animator) {
                if (running[view] === animation) running.remove(view)
            }
        })
        anim.start()
    }

    private fun dens(view: View) = view.resources.displayMetrics.density

    /** Soft breathing loop (scale only). Call stopBreathing to end. */
    fun startBreathing(view: View?, periodMs: Long = 2800L) {
        view ?: return
        if (!Motion.enabled) return
        cancel(view)
        val dens = dens(view)
        view.cameraDistance = dens * 12000f
        val sx = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.025f, 1f).apply {
            duration = periodMs
            repeatCount = ObjectAnimator.INFINITE
            interpolator = softOut
        }
        val sy = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.978f, 1f).apply {
            duration = periodMs
            repeatCount = ObjectAnimator.INFINITE
            interpolator = softOut
        }
        // Soft 3D drift (subtle) so Bit feels dimensional while idle
        val ry = ObjectAnimator.ofFloat(view, "rotationY", 0f, 3.5f, -2.5f, 0f).apply {
            duration = periodMs * 2
            repeatCount = ObjectAnimator.INFINITE
            interpolator = softOut
        }
        val rx = ObjectAnimator.ofFloat(view, "rotationX", 0f, 2f, -1.5f, 0f).apply {
            duration = periodMs * 2
            repeatCount = ObjectAnimator.INFINITE
            interpolator = softOut
        }
        val set = AnimatorSet().apply { playTogether(sx, sy, rx, ry) }
        track(view, set)
    }

    fun stopBreathing(view: View?) {
        view ?: return
        cancel(view)
        view.scaleX = 1f
        view.scaleY = 1f
        view.rotationX = 0f
        view.rotationY = 0f
        view.rotation = 0f
    }

    /** Subtle body sway while idle. */
    fun softSway(view: View?, deg: Float = 2.2f, ms: Long = 2200L, onEnd: (() -> Unit)? = null) {
        view ?: return onEnd?.invoke() ?: Unit
        if (!Motion.enabled) { onEnd?.invoke(); return }
        cancel(view)
        val a = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, deg, -deg * 0.7f, 0f).apply {
            duration = ms
            interpolator = softEase
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { onEnd?.invoke() }
        })
        track(view, a)
    }

    /** Soft squash that always returns to a neutral pose so callers cannot leave Bit distorted. */
    fun softSquash(view: View?, amount: Float = 0.06f, ms: Long = 220L, onEnd: (() -> Unit)? = null) {
        view ?: return onEnd?.invoke() ?: Unit
        if (!Motion.enabled) { onEnd?.invoke(); return }
        cancel(view)
        val half = (ms / 2L).coerceAtLeast(50L)
        val down = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, view.scaleX, 1f + amount),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, view.scaleY, 1f - amount * 0.85f)
            )
            duration = half
            interpolator = softIn
        }
        val recover = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f + amount, 1f + amount * 0.16f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f - amount * 0.85f, 1f + amount * 0.08f, 1f)
            )
            duration = (ms - half).coerceAtLeast(80L)
            interpolator = overshootSoft
        }
        val seq = AnimatorSet().apply {
            playSequentially(down, recover)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.scaleX = 1f
                    view.scaleY = 1f
                    onEnd?.invoke()
                }
            })
        }
        track(view, seq)
    }

    /** Soft stretch with a gentle return/overshoot. */
    fun softStretch(view: View?, amount: Float = 0.05f, ms: Long = 240L, onEnd: (() -> Unit)? = null) {
        view ?: return onEnd?.invoke() ?: Unit
        if (!Motion.enabled) { onEnd?.invoke(); return }
        cancel(view)
        val half = (ms / 2L).coerceAtLeast(60L)
        val stretch = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, view.scaleX, 1f - amount),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, view.scaleY, 1f + amount)
            )
            duration = half
            interpolator = softEase
        }
        val settle = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f - amount, 1f + amount * 0.08f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f + amount, 1f - amount * 0.06f, 1f)
            )
            duration = (ms - half).coerceAtLeast(90L)
            interpolator = overshootSoft
        }
        val seq = AnimatorSet().apply {
            playSequentially(stretch, settle)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.scaleX = 1f
                    view.scaleY = 1f
                    onEnd?.invoke()
                }
            })
        }
        track(view, seq)
    }

    fun softBounce(view: View?, peak: Float = 1.07f, ms: Long = 380L, onEnd: (() -> Unit)? = null) {
        view ?: return onEnd?.invoke() ?: Unit
        if (!Motion.enabled) { onEnd?.invoke(); return }
        cancel(view)
        val sx = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, peak, 1f)
        val sy = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, peak, 1f)
        val set = AnimatorSet().apply {
            playTogether(sx, sy)
            duration = ms
            interpolator = overshootSoft
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { onEnd?.invoke() }
            })
        }
        track(view, set)
    }

    fun softJump(view: View?, dpUp: Float = 14f, ms: Long = 480L, onEnd: (() -> Unit)? = null) {
        view ?: return onEnd?.invoke() ?: Unit
        if (!Motion.enabled) { onEnd?.invoke(); return }
        cancel(view)
        val up = -dpUp * dens(view)
        // Anticipation squash → jump → land squash → settle
        val antiX = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.06f).setDuration(90)
        val antiY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.94f).setDuration(90)
        val jump = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, up, 0f).setDuration(ms - 140)
        jump.interpolator = softEase
        val landX = ObjectAnimator.ofFloat(view, View.SCALE_X, 1.06f, 0.96f, 1f).setDuration(160)
        val landY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.94f, 1.05f, 1f).setDuration(160)
        val set = AnimatorSet()
        val anti = AnimatorSet().apply { playTogether(antiX, antiY) }
        val land = AnimatorSet().apply { playTogether(landX, landY) }
        set.playSequentially(anti, jump, land)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
                onEnd?.invoke()
            }
        })
        track(view, set)
    }

    fun softShake(view: View?, deg: Float = 7f, ms: Long = 420L, onEnd: (() -> Unit)? = null) {
        view ?: return onEnd?.invoke() ?: Unit
        if (!Motion.enabled) { onEnd?.invoke(); return }
        cancel(view)
        val a = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, deg, -deg, deg * 0.5f, 0f).apply {
            duration = ms
            interpolator = softEase
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { onEnd?.invoke() }
            })
        }
        track(view, a)
    }

    fun softSettle(view: View?, ms: Long = 280L, onEnd: (() -> Unit)? = null) {
        view ?: return onEnd?.invoke() ?: Unit
        if (!Motion.enabled) {
            resetPose(view)
            onEnd?.invoke()
            return
        }
        cancel(view)
        val sx = ObjectAnimator.ofFloat(view, View.SCALE_X, view.scaleX, 1f)
        val sy = ObjectAnimator.ofFloat(view, View.SCALE_Y, view.scaleY, 1f)
        val r = ObjectAnimator.ofFloat(view, View.ROTATION, view.rotation, 0f)
        val tx = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, view.translationX, 0f)
        val ty = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, view.translationY, 0f)
        val a = ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, 1f)
        val set = AnimatorSet().apply {
            playTogether(sx, sy, r, tx, ty, a)
            duration = ms
            interpolator = softEase
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { onEnd?.invoke() }
            })
        }
        track(view, set)
    }

    /** Mochi wake-up: squash → stretch → overshoot → blink-settle. */
    fun softWakeUp(view: View?, onEnd: (() -> Unit)? = null) {
        view ?: return onEnd?.invoke() ?: Unit
        if (!Motion.enabled) {
            resetPose(view)
            onEnd?.invoke()
            return
        }
        cancel(view)
        val d = dens(view)
        val sleepy = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.97f, 0.99f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.94f, 0.93f),
                ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha.coerceAtMost(0.92f), 0.9f)
            )
            duration = 160
            interpolator = softIn
        }
        val startled = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.99f, 1.09f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.93f, 0.88f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, -5f * d),
                ObjectAnimator.ofFloat(view, View.ROTATION, 0f, -3.5f)
            )
            duration = 150
            interpolator = softIn
        }
        val stretch = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1.09f, 0.94f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.88f, 1.10f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, -5f * d, -8f * d),
                ObjectAnimator.ofFloat(view, View.ROTATION, -3.5f, 2.2f)
            )
            duration = 210
            interpolator = softEase
        }
        val overshoot = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.94f, 1.045f, 0.985f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1.10f, 0.97f, 1.02f, 1f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, -8f * d, 2f * d, -1f * d, 0f),
                ObjectAnimator.ofFloat(view, View.ROTATION, 2.2f, -1.4f, 0f),
                ObjectAnimator.ofFloat(view, View.ALPHA, 0.9f, 1f)
            )
            duration = 360
            interpolator = overshootSoft
        }
        val seq = AnimatorSet().apply {
            playSequentially(sleepy, startled, stretch, overshoot)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    resetPose(view)
                    onEnd?.invoke()
                }
            })
        }
        track(view, seq)
    }

    /** Tiny anticipation used when Bit notices an object getting close. */
    fun softAnticipate(view: View?, amount: Float = 0.035f, ms: Long = 180L, onEnd: (() -> Unit)? = null) {
        view ?: return onEnd?.invoke() ?: Unit
        if (!Motion.enabled) { onEnd?.invoke(); return }
        cancel(view)
        val anti = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1f + amount),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1f - amount * 0.65f),
                ObjectAnimator.ofFloat(view, View.ROTATION, 0f, -1.8f)
            )
            duration = (ms * 0.58f).toLong().coerceAtLeast(70L)
            interpolator = softIn
        }
        val ready = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f + amount, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f - amount * 0.65f, 1f),
                ObjectAnimator.ofFloat(view, View.ROTATION, -1.8f, 0f)
            )
            duration = (ms * 0.42f).toLong().coerceAtLeast(70L)
            interpolator = softEase
        }
        val seq = AnimatorSet().apply {
            playSequentially(anti, ready)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.rotation = 0f
                    onEnd?.invoke()
                }
            })
        }
        track(view, seq)
    }

    /** Small directional lean so Bit feels aware of the object being dragged. */
    fun softLeanToward(view: View?, nx: Float, ny: Float = 0f, ms: Long = 190L) {
        view ?: return
        if (!Motion.enabled) return
        cancel(view)
        val x = nx.coerceIn(-1f, 1f)
        val y = ny.coerceIn(-1f, 1f)
        val dens = dens(view)
        val tilt = x * 5f
        val tx = x * 8f * dens
        val ty = y * 4f * dens
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.ROTATION, 0f, tilt, 0f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, tx, 0f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, ty, 0f),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.025f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.985f, 1f)
            )
            duration = ms
            interpolator = softEase
        }
        track(view, set)
    }

    /** Scrub reaction: the body yields in the same direction as the sponge. */
    fun reactToScrub(view: View?, dx: Float, dy: Float, ms: Long = 150L) {
        view ?: return
        if (!Motion.enabled) return
        cancel(view)
        val mag = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceIn(0f, 1f)
        val dir = if (dx >= 0f) 1f else -1f
        val amount = 0.025f + 0.035f * mag
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1f + amount, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1f - amount * 0.75f, 1f),
                ObjectAnimator.ofFloat(view, View.ROTATION, 0f, dir * 3.2f, -dir * 1.4f, 0f)
            )
            duration = ms
            interpolator = softEase
        }
        track(view, set)
    }

    /** Full pet/elus mini-scene. */
    fun scenePet(view: View?, onEnd: (() -> Unit)? = null) {
        view ?: return onEnd?.invoke() ?: Unit
        if (!Motion.enabled) { onEnd?.invoke(); return }
        cancel(view)
        val anti = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 0.965f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.035f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, 2f * dens(view))
            )
            duration = 110
            interpolator = softIn
        }
        val melt = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.965f, 1.075f, 1.045f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1.035f, 0.93f, 0.975f),
                ObjectAnimator.ofFloat(view, View.ROTATION, 0f, -2.8f, 2.1f)
            )
            duration = 260
            interpolator = softEase
        }
        val content = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1.045f, 1.07f, 1.035f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.975f, 0.95f, 1.025f),
                ObjectAnimator.ofFloat(view, View.ROTATION, 2.1f, -1.5f, 0f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 2f * dens(view), -1f * dens(view), 0f)
            )
            duration = 360
            interpolator = overshootSoft
        }
        val settle = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1.035f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1.025f, 1f)
            )
            duration = 150
            interpolator = softEase
        }
        val seq = AnimatorSet().apply {
            playSequentially(anti, melt, content, settle)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { resetPose(view); onEnd?.invoke() }
            })
        }
        track(view, seq)
    }

    /** Eat mini-scene: lean → nibble x2 → satisfied bounce. */
    fun sceneEat(view: View?, onEnd: (() -> Unit)? = null) {
        view ?: return onEnd?.invoke() ?: Unit
        if (!Motion.enabled) { onEnd?.invoke(); return }
        cancel(view)
        val d = dens(view)
        val notice = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.ROTATION, 0f, -3.5f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, -2.5f * d),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.02f)
            )
            duration = 160
            interpolator = softIn
        }
        val ready = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1.02f, 0.965f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.04f),
                ObjectAnimator.ofFloat(view, View.ROTATION, -3.5f, 0f)
            )
            duration = 130
            interpolator = softEase
        }
        fun bite(extra: Float): AnimatorSet = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.965f, 1.055f + extra, 0.985f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1.04f, 0.90f, 1.015f),
                ObjectAnimator.ofFloat(view, View.ROTATION, 0f, if (extra > 0f) -1.5f else 1.5f, 0f)
            )
            duration = 210
            interpolator = softEase
        }
        val chew = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.985f, 1.045f, 0.99f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1.015f, 0.955f, 1.01f)
            )
            duration = 230
            interpolator = softEase
        }
        val satisfied = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.99f, 1.07f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1.01f, 0.95f, 1f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, -2.5f * d, 0f),
                ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 2.4f, 0f)
            )
            duration = 360
            interpolator = overshootSoft
        }
        val seq = AnimatorSet().apply {
            playSequentially(notice, ready, bite(0f), chew, bite(0.012f), satisfied)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { resetPose(view); onEnd?.invoke() }
            })
        }
        track(view, seq)
    }

    /** Bath mini-scene: hesitate → dip → wiggle wash → shake dry. */
    fun sceneBath(view: View?, onEnd: (() -> Unit)? = null) {
        view ?: return onEnd?.invoke() ?: Unit
        if (!Motion.enabled) { onEnd?.invoke(); return }
        cancel(view)
        val d = dens(view)
        val hesitate = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.ROTATION, 0f, -4f, 2f, 0f),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.025f, 1f)
            )
            duration = 300
            interpolator = softEase
        }
        val dip = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, 6f * d, 2f * d),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.07f, 1.04f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.94f, 0.98f)
            )
            duration = 260
            interpolator = softIn
        }
        val wash = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 4f, -5f, 3f, 0f),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1.04f, 1.025f, 1.045f, 1.02f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.98f, 1.01f, 0.97f, 1.015f, 1f)
            )
            duration = 620
            interpolator = softEase
        }
        val rise = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 2f * d, 0f),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1.02f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.98f, 1f)
            )
            duration = 240
            interpolator = softEase
        }
        val dry = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 7f, -7f, 4f, 0f),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.055f, 0.965f, 1.025f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.95f, 1.045f, 0.98f, 1f)
            )
            duration = 440
            interpolator = overshootSoft
        }
        val seq = AnimatorSet().apply {
            playSequentially(hesitate, dip, wash, rise, dry)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { resetPose(view); onEnd?.invoke() }
            })
        }
        track(view, seq)
    }

    /** Play mini-scene: excite → jump ×2 → tire settle. */
    fun scenePlay(view: View?, onEnd: (() -> Unit)? = null) {
        view ?: return onEnd?.invoke() ?: Unit
        if (!Motion.enabled) { onEnd?.invoke(); return }
        cancel(view)
        val d = dens(view)
        val excite = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 0.96f, 1.06f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.035f, 0.95f),
                ObjectAnimator.ofFloat(view, View.ROTATION, 0f, -2f, 2f)
            )
            duration = 220
            interpolator = softEase
        }
        val pounce1 = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, -15f * d, 0f),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1.06f, 0.96f, 1.03f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.95f, 1.08f, 0.98f)
            )
            duration = 340
            interpolator = softEase
        }
        val pounce2 = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, -10f * d, 0f),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1.03f, 0.975f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.98f, 1.045f, 1f)
            )
            duration = 280
            interpolator = softEase
        }
        val happy = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.06f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.955f, 1f),
                ObjectAnimator.ofFloat(view, View.ROTATION, 0f, -3f, 3f, 0f)
            )
            duration = 300
            interpolator = overshootSoft
        }
        val seq = AnimatorSet().apply {
            playSequentially(excite, pounce1, pounce2, happy)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { resetPose(view); onEnd?.invoke() }
            })
        }
        track(view, seq)
    }

    /** Sleep enter: slow blink feel via alpha + sink + soft squash. */
    fun sceneSleepEnter(view: View?, onEnd: (() -> Unit)? = null) {
        view ?: return onEnd?.invoke() ?: Unit
        if (!Motion.enabled) {
            view.alpha = 0.88f
            view.scaleX = 0.94f
            view.scaleY = 0.94f
            onEnd?.invoke()
            return
        }
        cancel(view)
        val dens = dens(view)
        val drowsy = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.96f),
                ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.92f)
            )
            duration = 400
            interpolator = softIn
        }
        val sink = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, 6f * dens),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.05f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.96f, 0.92f),
                ObjectAnimator.ofFloat(view, View.ALPHA, 0.92f, 0.86f)
            )
            duration = 520
            interpolator = softEase
        }
        val seq = AnimatorSet()
        seq.playSequentially(drowsy, sink)
        seq.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { onEnd?.invoke() }
        })
        track(view, seq)
    }

    /** Level-up celebration. */
    fun sceneLevelUp(view: View?, onEnd: (() -> Unit)? = null) {
        view ?: return onEnd?.invoke() ?: Unit
        if (!Motion.enabled) { onEnd?.invoke(); return }
        cancel(view)
        val dens = dens(view)
        val anti = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 0.94f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.94f)
            )
            duration = 140
        }
        val pop = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.94f, 1.14f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.94f, 1.14f, 1f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, -10f * dens, 0f)
            )
            duration = 520
            interpolator = overshootSoft
        }
        val seq = AnimatorSet()
        seq.playSequentially(anti, pop)
        seq.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                resetPose(view)
                onEnd?.invoke()
            }
        })
        track(view, seq)
    }

    /**
     * Soft 3D depth pulse — cameraDistance + slight rotationX/Y.
     * Gives Lottie Bit a "model" feel without a GLB asset.
     */
    fun applySoft3DPulse(view: View?, tiltDeg: Float = 8f, ms: Long = 420L) {
        view ?: return
        if (!Motion.enabled) return
        val dens = dens(view)
        view.cameraDistance = dens * 12000f
        cancel(view)
        val rx = ObjectAnimator.ofFloat(view, "rotationX", 0f, -tiltDeg * 0.6f, 0f)
        val ry = ObjectAnimator.ofFloat(view, "rotationY", 0f, tiltDeg, 0f)
        val sx = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.04f, 1f)
        val sy = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.04f, 1f)
        val set = AnimatorSet().apply {
            playTogether(rx, ry, sx, sy)
            duration = ms
            interpolator = softEase
        }
        track(view, set)
    }

    /** Continuous subtle 3D idle tilt (call once; cancels previous). */
    fun soft3DIdleDrift(view: View?, ms: Long = 3200L) {
        view ?: return
        if (!Motion.enabled) return
        val dens = dens(view)
        view.cameraDistance = dens * 12000f
        cancel(view)
        val ry = ObjectAnimator.ofFloat(view, "rotationY", 0f, 4f, -3f, 0f).apply {
            duration = ms
            repeatCount = ObjectAnimator.INFINITE
            interpolator = softOut
        }
        val rx = ObjectAnimator.ofFloat(view, "rotationX", 0f, 2f, -1.5f, 0f).apply {
            duration = ms
            repeatCount = ObjectAnimator.INFINITE
            interpolator = softOut
        }
        val set = AnimatorSet().apply { playTogether(rx, ry) }
        track(view, set)
    }
}
