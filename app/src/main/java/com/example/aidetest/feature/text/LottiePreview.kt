package com.example.aidetest

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import org.json.JSONObject
import java.util.Locale

/**
 * Pratinjau animasi Lottie (file JSON dari LottieFiles / After Effects + Bodymovin)
 * untuk tool JSON. Berjalan lokal di perangkat memakai library Lottie yang sudah ada.
 */
object LottieJson {
    private const val MAX_CHARS = 8_000_000

    /** Cek ringan: objek JSON dengan versi, frame rate, ukuran, dan daftar layers. */
    fun looksLikeLottie(text: String): Boolean {
        val t = text.trim()
        if (t.length < 20 || t.length > MAX_CHARS || t[0] != '{') return false
        if (!t.contains("\"layers\"")) return false
        return runCatching {
            val o = JSONObject(t)
            o.optJSONArray("layers") != null && o.has("w") && o.has("h") && (o.has("fr") || o.has("op"))
        }.getOrDefault(false)
    }
}

private class CheckerDrawable(private val a: Int, private val b: Int, private val cell: Float) : Drawable() {
    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        paint.color = a
        canvas.drawRect(bounds, paint)
        paint.color = b
        var row = 0
        var y = bounds.top.toFloat()
        while (y < bounds.bottom) {
            var x = bounds.left + if (row % 2 == 0) 0f else cell
            while (x < bounds.right) {
                canvas.drawRect(x, y, minOf(x + cell, bounds.right.toFloat()), minOf(y + cell, bounds.bottom.toFloat()), paint)
                x += cell * 2
            }
            y += cell
            row++
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** Buka layar pratinjau Lottie untuk [json]. */
fun MainActivity.showLottiePreview(json: String) {
    clearPage("Lottie Preview")
    content.setPadding(dp(12), dp(8), dp(12), dp(12))
    editorBottomBar.visibility = View.GONE

    val info = TextView(this).apply {
        text = "Memuat animasi…"
        textSize = 12.5f
        setTextColor(textMuted)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = bg(panel2, 14, line)
    }
    content.addView(info, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

    // The preview selector explicitly starts on a WHITE canvas; this is content, not a UI card.
    val whiteStage = {
        android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(dp(1), if (isDarkTheme) line else Color.rgb(225, 229, 234))
            cornerRadius = dp(18).toFloat()
        }
    }
    val stage = FrameLayout(this).apply { background = whiteStage() }
    val anim = LottieAnimationView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        repeatCount = LottieDrawable.INFINITE
        repeatMode = LottieDrawable.RESTART
        contentDescription = "Pratinjau animasi Lottie"
    }
    stage.addView(anim, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
    content.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(8) })

    val seek = SeekBar(this).apply { max = 1000; isEnabled = false }
    val timeText = TextView(this).apply {
        text = "0.00 s"
        textSize = 11f
        setTextColor(textMuted)
        gravity = Gravity.END
    }
    val seekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    seekRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
    seekRow.addView(timeText, LinearLayout.LayoutParams(dp(64), -2))
    content.addView(seekRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

    fun chip(text: String, click: (TextView) -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(textMain)
        background = bg(if (isDarkTheme) panel2 else Color.rgb(242, 244, 246), 12, line)
        isClickable = true
        isFocusable = true
        setOnClickListener { click(this) }
    }

    var durationSec = 0f
    var userSeeking = false
    var wasPlaying = false
    var speedIndex = 1
    val speeds = floatArrayOf(0.5f, 1f, 1.5f, 2f)
    var bgIndex = 0
    var loop = true

    val playBtn = chip("Jeda") { v ->
        if (anim.isAnimating) { anim.pauseAnimation(); v.text = "Putar" }
        else { anim.resumeAnimation(); v.text = "Jeda" }
    }
    val restartBtn = chip("Ulang") {
        anim.progress = 0f
        anim.playAnimation()
        playBtn.text = "Jeda"
    }
    val loopBtn = chip("Loop: ya") { v ->
        loop = !loop
        anim.repeatCount = if (loop) LottieDrawable.INFINITE else 0
        v.text = if (loop) "Loop: ya" else "Loop: tidak"
        if (loop && !anim.isAnimating) { anim.resumeAnimation(); playBtn.text = "Jeda" }
    }
    val speedBtn = chip("1x") { v ->
        speedIndex = (speedIndex + 1) % speeds.size
        anim.speed = speeds[speedIndex]
        v.text = String.format(Locale.US, "%sx", if (speeds[speedIndex] % 1f == 0f) speeds[speedIndex].toInt().toString() else speeds[speedIndex].toString())
    }
    val bgBtn = chip("Latar: putih") { v ->
        bgIndex = (bgIndex + 1) % 3
        when (bgIndex) {
            0 -> { stage.background = whiteStage(); v.text = "Latar: putih" }
            1 -> { stage.background = bg(Color.rgb(24, 28, 36), 18, line); v.text = "Latar: gelap" }
            else -> { stage.background = CheckerDrawable(Color.WHITE, Color.rgb(228, 230, 234), dp(10).toFloat()); v.text = "Latar: kotak" }
        }
    }

    val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    listOf(playBtn, restartBtn, loopBtn, speedBtn, bgBtn).forEachIndexed { i, b ->
        controls.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i > 0) leftMargin = dp(4) })
    }
    content.addView(controls, LinearLayout.LayoutParams(-1, -2))

    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
            if (!fromUser) return
            anim.progress = value / 1000f
            timeText.text = String.format(Locale.US, "%.2f s", durationSec * value / 1000f)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {
            userSeeking = true
            wasPlaying = anim.isAnimating
            anim.pauseAnimation()
        }
        override fun onStopTrackingTouch(sb: SeekBar?) {
            userSeeking = false
            if (wasPlaying) { anim.resumeAnimation(); playBtn.text = "Jeda" } else playBtn.text = "Putar"
        }
    })
    anim.addAnimatorUpdateListener {
        if (userSeeking) return@addAnimatorUpdateListener
        val p = anim.progress
        seek.progress = (p * 1000).toInt()
        timeText.text = String.format(Locale.US, "%.2f s", durationSec * p)
    }

    fun onLoaded(comp: LottieComposition) {
        if (isFinishing || currentPage != "Lottie Preview") return
        durationSec = comp.duration / 1000f
        // LottieFiles/Bodymovin JSON dapat memiliki durasi sangat pendek atau 0
        // pada metadata tertentu; tetap biarkan preview berjalan tanpa seek error.
        seek.max = 1000
        val b = comp.bounds
        val name = runCatching { JSONObject(json).optString("nm") }.getOrDefault("").ifBlank { "Tanpa nama" }
        val layers = runCatching { JSONObject(json).optJSONArray("layers")?.length() ?: 0 }.getOrDefault(0)
        info.text = String.format(
            Locale.US,
            "%s\n%d × %d px  •  %.0f fps  •  %.2f detik  •  %d layer",
            name, b.width(), b.height(), comp.frameRate, durationSec, layers
        )
        info.setTextColor(textMain)
        anim.setComposition(comp)
        seek.isEnabled = true
        anim.speed = speeds[speedIndex]
        anim.repeatCount = if (loop) LottieDrawable.INFINITE else 0
        anim.playAnimation()
        appendEditorConsole("Lottie dimuat: ${b.width()}x${b.height()}, ${"%.2f".format(Locale.US, durationSec)}s")
    }

    fun onFailed(t: Throwable) {
        if (isFinishing || currentPage != "Lottie Preview") return
        val reason = t.message?.lineSequence()?.firstOrNull().orEmpty().take(160)
        info.text = "Animasi tidak bisa dimuat. File ini bukan Lottie yang valid atau memakai fitur yang belum didukung.\n$reason"
        info.setTextColor(Color.rgb(229, 57, 53))
        stage.visibility = View.GONE
        seekRow.visibility = View.GONE
        controls.visibility = View.GONE
        appendEditorConsole("ERROR Lottie: $reason")
    }

    LottieCompositionFactory.fromJsonString(json, "editor_preview_${json.hashCode()}")
        .addListener { comp -> onLoaded(comp) }
        .addFailureListener { t -> onFailed(t) }
}
