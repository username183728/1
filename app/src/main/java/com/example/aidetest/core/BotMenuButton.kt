package com.example.aidetest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback

/**
 * Animated Bit button used for the top-right action button.
 * When its text is "⋮", the dots are replaced by the real Bit Lottie animation.
 * The animation is transparent and follows the same theme/background as the page.
 */
class BotMenuButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var botMode = false
    private var botDrawable: LottieDrawable? = null
    private var botLoaded = false
    private var reactionToken = 0

    override fun setText(text: CharSequence?, type: BufferType?) {
        val isBot = text?.toString()?.trim() == "⋮"
        botMode = isBot
        super.setText(if (isBot) "" else text, type)
        if (isBot) {
            ensureBotDrawable()
            post { playIdle() }
        } else {
            botDrawable?.cancelAnimation()
        }
        invalidate()
    }

    // LottieDrawable animates through Drawable.Callback.invalidateDrawable().
    // Because this class draws the drawable manually in onDraw(), Android
    // otherwise may reject the drawable invalidation and the face appears
    // frozen on the first frame. Explicitly accept and forward invalidations.
    override fun verifyDrawable(who: Drawable): Boolean {
        return who === botDrawable || super.verifyDrawable(who)
    }

    override fun invalidateDrawable(who: Drawable) {
        if (who === botDrawable) {
            postInvalidateOnAnimation()
        } else {
            super.invalidateDrawable(who)
        }
    }

    override fun onDetachedFromWindow() {
        botDrawable?.cancelAnimation()
        botDrawable?.callback = null
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        if (botMode) playIdle()
        return super.performClick()
    }

    override fun onDraw(canvas: Canvas) {
        if (!botMode) {
            super.onDraw(canvas)
            return
        }
        val d = botDrawable
        if (d == null || !botLoaded) {
            // Keep a clean fallback while the Lottie asset loads.
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) * 0.31f
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = currentTextColor
            canvas.drawCircle(cx, cy, r, paint)
            paint.color = if (currentTextColor == Color.WHITE) Color.rgb(20,20,26) else Color.WHITE
            canvas.drawRoundRect(cx-r*0.5f, cy-r*0.25f, cx-r*0.15f, cy+r*0.25f, r*0.12f, r*0.12f, paint)
            canvas.drawRoundRect(cx+r*0.15f, cy-r*0.25f, cx+r*0.5f, cy+r*0.25f, r*0.12f, r*0.12f, paint)
            return
        }
        val size = minOf(width, height)
        val left = (width - size) / 2
        val top = (height - size) / 2
        d.bounds = android.graphics.Rect(left, top, left + size, top + size)
        d.draw(canvas)
    }

    /** Muat ulang bentuk Bit setelah pengguna mengganti bentuk. */
    fun refreshShape() {
        if (!botMode) return
        botDrawable?.cancelAnimation()
        botDrawable = null
        botLoaded = false
        ensureBotDrawable()
        invalidate()
    }

    private fun ensureBotDrawable() {
        if (botDrawable != null) return
        val drawable = LottieDrawable().apply {
            repeatMode = LottieDrawable.RESTART
            repeatCount = LottieDrawable.INFINITE
            callback = this@BotMenuButton
        }
        botDrawable = drawable
        val task = if (com.example.aidetest.BitShape.current == "circle")
            LottieCompositionFactory.fromAsset(context, "bit/bit_idle.json")
        else LottieCompositionFactory.fromJsonString(
            com.example.aidetest.BitShape.jsonFor(context, "bit/bit_idle.json"),
            com.example.aidetest.BitShape.cacheKey("bit/bit_idle.json")
        )
        task
            .addListener { composition ->
                drawable.setComposition(composition)
                applyBotTheme(drawable)
                drawable.setMinAndMaxFrame(0, 108)
                botLoaded = true
                drawable.playAnimation()
                invalidate()
            }
            .addFailureListener {
                botLoaded = false
                invalidate()
            }
    }

    /**
     * Keep Bit strictly monochrome and theme-aware even when rendered through
     * LottieDrawable (the top-right navbar button).
     *
     * Light theme  -> black body, white eyes.
     * Dark theme   -> white body, black eyes.
     *
     * Do not tint the whole drawable: only the named BODY/EYES layers are
     * overridden so the eye animation and transparent background remain intact.
     */
    private fun applyBotTheme(drawable: LottieDrawable) {
        val dark = (context as? MainActivity)?.isDarkTheme == true
        val body = if (dark) Color.WHITE else Color.BLACK
        val eyes = if (dark) Color.BLACK else Color.WHITE

        drawable.addValueCallback(
            KeyPath("BODY", "**"),
            LottieProperty.COLOR,
            LottieValueCallback(body)
        )
        drawable.addValueCallback(
            KeyPath("BODY", "**"),
            LottieProperty.STROKE_COLOR,
            LottieValueCallback(body)
        )
        drawable.addValueCallback(
            KeyPath("EYES", "**"),
            LottieProperty.COLOR,
            LottieValueCallback(eyes)
        )
        drawable.addValueCallback(
            KeyPath("EYES", "**"),
            LottieProperty.STROKE_COLOR,
            LottieValueCallback(eyes)
        )
        drawable.invalidateSelf()
    }

    private fun playIdle() {
        if (!botMode) return
        ensureBotDrawable()
        val d = botDrawable ?: return
        if (!botLoaded) return
        reactionToken++
        d.removeAllAnimatorListeners()
        applyBotTheme(d)
        d.setMinAndMaxFrame(0, 108)
        d.repeatCount = LottieDrawable.INFINITE
        d.playAnimation()
        invalidate()
    }


}
