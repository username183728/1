package com.example.aidetest

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Komponen UI Video Editor — sistem desain yang sama dengan Foto Studio & Audio Editor
 * ([EditorChrome]/[ImgKit]): top bar 52 dp, panel & dock monokrom yang ikut tema terang/gelap,
 * sasaran sentuh ≥ 44 dp. Area kerja video (pratinjau, transport, timeline) tetap gelap di kedua tema
 * agar warna video dan thumbnail terbaca akurat — token `ws*`.
 */
internal class VeUiKit(val a: MainActivity) {
    val c = EditorChrome(a)
    // Chrome (top bar, dock, panel) — ikut tema
    val bg0 = c.surface
    val bg1 = c.surface
    val bg2 = c.surface2
    val bg3 = c.stroke
    val stroke = c.stroke
    val ink = c.ink
    val muted = c.muted
    val accentInk = c.activeInk
    // Area kerja video — selalu gelap
    val ws = Color.rgb(12, 12, 14)
    val wsStage = Color.rgb(8, 8, 9)
    val wsInk = Color.rgb(244, 244, 246)
    val wsMuted = Color.rgb(150, 150, 160)
    val wsChip = Color.rgb(30, 30, 34)
    val wsStroke = Color.rgb(56, 56, 62)

    fun dp(v: Int): Int = a.dp(v)

    fun shape(fill: Int, radius: Int, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = dp(radius).toFloat()
        if (strokeColor != null) d.setStroke(dp(strokeDp), strokeColor)
        return d
    }

    fun pressable(fill: Int, radius: Int, strokeColor: Int? = null): android.graphics.drawable.Drawable = c.pressable(fill, radius, strokeColor)

    /** Ripple terang untuk kontrol di area kerja gelap. */
    fun wsPressable(fill: Int, radius: Int): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), shape(fill, radius), shape(Color.BLACK, radius))

    fun text(value: String, size: Float, color: Int = ink, bold: Boolean = false): TextView = TextView(a).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun icon(name: String, size: Float, color: Int = ink, desc: String? = null, onClick: ((View) -> Unit)? = null): MdiIconView =
        MdiIconView(a).apply {
            setIconName(name)
            setIconSize(size)
            setTextColor(color)
            if (desc != null) contentDescription = desc
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                background = pressable(Color.TRANSPARENT, 12)
                setOnClickListener { v -> onClick(v) }
            }
        }

    /** Tombol ikon bulat sentuh 44 dp. */
    fun iconButton(name: String, desc: String, size: Float = 22f, onClick: (View) -> Unit): MdiIconView =
        icon(name, size, ink, desc, onClick)

    fun section(label: String): TextView = text(label.uppercase(), 10.5f, muted, true).apply {
        letterSpacing = 0.08f
        setPadding(dp(2), dp(10), 0, dp(4))
    }

    fun note(value: String): TextView = text(value, 11.5f, muted).apply { setPadding(dp(2), dp(2), dp(2), dp(4)) }

    fun hline(): View = View(a).apply { setBackgroundColor(stroke) }

    /** Sel alat di dock bawah — sama dengan dock Foto Studio: ikon 21 di atas label 10, aktif = terisi. */
    fun tile(iconName: String, label: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit): LinearLayout =
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = pressable(if (selected) c.activeFill else Color.TRANSPARENT, 14)
            alpha = if (enabled) 1f else 0.45f
            isClickable = true
            isFocusable = true
            contentDescription = label
            val col = if (selected) c.activeInk else ink
            addView(icon(iconName, 21f, col), LinearLayout.LayoutParams(-1, dp(25)))
            addView(text(label, 10f, col).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(3), dp(2), 0)
            }, LinearLayout.LayoutParams(-1, -2))
            setOnClickListener { if (enabled) onClick() }
        }

    /** Pil pilihan (chip). */
    fun pill(label: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(a).apply {
        text = label
        textSize = 12f
        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (selected) c.activeInk else ink)
        gravity = Gravity.CENTER
        maxLines = 1
        minHeight = dp(36)
        setPadding(dp(14), 0, dp(14), 0)
        contentDescription = label
        background = pressable(if (selected) c.activeFill else bg2, 16)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Baris pil yang bisa digulir mendatar. */
    fun chips(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit): HorizontalScrollView {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), dp(8), dp(2))
        }
        for ((key, label) in options) {
            row.addView(pill(label, key == current) { onPick(key) }, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(8) })
        }
        return HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }
    }

    class Toggle(val label: String, val on: Boolean, val action: () -> Unit)

    /** Baris pil toggle (tiap pil punya aksi sendiri). */
    fun toggleRow(items: List<Toggle>): HorizontalScrollView {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), dp(8), dp(2)) }
        for (t in items) row.addView(pill(t.label, t.on, t.action), LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(8) })
        return HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    fun colorSwatches(colors: IntArray, current: Int, onPick: (Int) -> Unit): HorizontalScrollView {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), dp(8), dp(4)) }
        for (col in colors) {
            val selected = col == current
            val sw = View(a).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(col)
                    setStroke(dp(if (selected) 3 else 1), if (selected) ink else stroke)
                }
                isClickable = true
                contentDescription = String.format("Warna #%06X", 0xFFFFFF and col)
                setOnClickListener { onPick(col) }
            }
            row.addView(sw, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) })
        }
        return HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    /**
     * Slider satu baris (sama dengan Foto Studio/Audio Editor): label · bar · nilai.
     * Ketuk nilai untuk kembali ke [default] (bila ada). [onStart] sekali saat disentuh (undo),
     * [onChange] tiap perubahan oleh pengguna, [onEnd] saat dilepas (simpan).
     */
    fun slider(
        label: String, min: Float, max: Float, value: Float, fmt: (Float) -> String,
        onStart: () -> Unit = {}, onChange: (Float) -> Unit, onEnd: () -> Unit = {}, default: Float? = null
    ): View {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(40) }
        row.addView(text(label, 11.5f, muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(dp(84), -2))
        val valueView = text(fmt(value), 11.5f, ink, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            if (default != null) {
                background = pressable(Color.TRANSPARENT, 8)
                isClickable = true
                contentDescription = "$label, ketuk untuk reset"
            }
        }
        val steps = 1000
        val bar = SeekBar(a).apply {
            this.max = steps
            progress = (((value - min) / (max - min)) * steps).toInt().coerceIn(0, steps)
            progressTintList = ColorStateList.valueOf(ink)
            progressBackgroundTintList = ColorStateList.valueOf(bg3)
            thumbTintList = ColorStateList.valueOf(ink)
            contentDescription = label
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val v = min + (max - min) * progress / steps
                valueView.text = fmt(v)
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(sb: SeekBar) { onStart() }
            override fun onStopTrackingTouch(sb: SeekBar) { onEnd() }
        })
        if (default != null) valueView.setOnClickListener {
            onStart()
            bar.progress = (((default - min) / (max - min)) * steps).toInt().coerceIn(0, steps)
            onChange(default)
            onEnd()
        }
        row.addView(bar, LinearLayout.LayoutParams(0, dp(36), 1f))
        row.addView(valueView, LinearLayout.LayoutParams(dp(52), dp(30)))
        return row
    }

    /** Sakelar geser sederhana. */
    fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            isClickable = true
        }
        row.addView(text(label, 12.5f, ink), LinearLayout.LayoutParams(0, -2, 1f))
        val track = FrameLayout(a)
        val knob = View(a)
        fun paint(on: Boolean) {
            track.background = shape(if (on) c.activeFill else bg3, 14)
            knob.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) c.activeInk else c.surface) }
            (knob.layoutParams as? FrameLayout.LayoutParams)?.let { it.gravity = (if (on) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL; knob.layoutParams = it }
        }
        track.addView(knob, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(4); rightMargin = dp(4) })
        var state = checked
        paint(state)
        row.setOnClickListener { state = !state; paint(state); onChange(state) }
        row.addView(track, LinearLayout.LayoutParams(dp(48), dp(28)))
        row.contentDescription = label
        return row
    }

    fun button(label: String, iconName: String?, filled: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = if (filled) pressable(c.activeFill, 18) else pressable(bg2, 18, stroke)
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
        }
        val col = if (filled) c.activeInk else ink
        if (iconName != null) row.addView(icon(iconName, 18f, col), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(8) })
        row.addView(text(label, 13f, col, filled).apply { maxLines = 1 })
        return row
    }

    /** Tombol utama di area kerja gelap (terang di atas gelap, di kedua tema). */
    fun wsButton(label: String, iconName: String?, onClick: () -> Unit): View {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = wsPressable(wsInk, 18)
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
        }
        if (iconName != null) row.addView(icon(iconName, 18f, ws), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(8) })
        row.addView(text(label, 13f, ws, true).apply { maxLines = 1 })
        return row
    }

    fun field(hintText: String, value: String, lines: Int = 1): EditText = EditText(a).apply {
        setText(value)
        setHint(hintText)
        setTextColor(ink)
        setHintTextColor(muted)
        textSize = 14f
        background = shape(bg2, 12, stroke)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        if (lines > 1) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
        } else {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
    }

    fun hideKeyboard(v: View) {
        runCatching {
            val imm = a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
        }
    }

    fun vbox(pad: Int = 0): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }
}
