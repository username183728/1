package com.example.aidetest

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Komponen bersama editor gambar (Photo Editor & Background Remover):
 *  - [ImgKit]          : tombol ikon, slider berlabel dengan reset, chip/segmented, top bar
 *  - [ImgSheet]        : lembar bawah (bottom sheet) di atas kanvas, terhubung tombol Back
 *  - [ZoomImageView]   : kanvas zoom/geser 2 jari, papan catur transparan, gestur 1 jari kustom
 *  - decodeOrientedBitmap / saveImageToGallery / shareImageUri
 */

/** Serah-terima gambar antar tool (mis. hasil hapus latar → Photo Editor). */
internal object ImageHandoff {
    var bitmap: Bitmap? = null
    var name: String = "gambar"
}

internal class ImgKit(val a: MainActivity) {
    val c = EditorChrome(a)
    val workspace: Int = if (c.dark) Color.rgb(12, 12, 14) else Color.rgb(222, 222, 226)

    fun dp(v: Int): Int = a.dp(v)

    fun icon(name: String, size: Float, color: Int = c.ink, desc: String? = null, onClick: ((View) -> Unit)? = null): MdiIconView =
        MdiIconView(a).apply {
            setIconName(name)
            setIconSize(size)
            setTextColor(color)
            gravity = Gravity.CENTER
            if (desc != null) contentDescription = desc
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                background = c.pressable(Color.TRANSPARENT, 12)
                setOnClickListener { v -> onClick(v) }
            }
        }

    fun text(value: String, size: Float, color: Int = c.ink, bold: Boolean = false): TextView = TextView(a).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** Tombol teks kecil berbentuk pil. */
    fun pill(label: String, active: Boolean = false, onClick: () -> Unit): TextView = TextView(a).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(if (active) c.activeInk else c.ink)
        typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        background = if (active) c.pressable(c.activeFill, 16) else c.pressable(c.surface2, 16)
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { onClick() }
    }

    /** Baris segmen sama lebar: pasangan (kunci, label). */
    fun segmented(options: List<Pair<String, String>>, active: String, onPick: (String) -> Unit): LinearLayout {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            background = c.shape(c.surface2, 14)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        for ((key, label) in options) {
            val on = key == active
            row.addView(TextView(a).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (on) c.ink else c.muted)
                typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = if (on) c.shape(c.surface, 11, c.stroke) else null
                isClickable = true
                contentDescription = label
                setOnClickListener { onPick(key) }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        return row
    }

    /** Ubin ikon + label (untuk panel alat). */
    fun tile(iconName: String, label: String, active: Boolean = false, onClick: () -> Unit): LinearLayout {
        val t = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = if (active) c.pressable(c.activeFill, 14) else c.pressable(c.surface2, 14)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
        }
        val ink = if (active) c.activeInk else c.ink
        t.addView(icon(iconName, 21f, ink), LinearLayout.LayoutParams(-1, dp(26)))
        t.addView(text(label, 10.5f, ink).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(3), dp(2), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        return t
    }

    fun tileRow(tiles: List<View>, heightDp: Int = 64): LinearLayout {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.forEach { row.addView(it, LinearLayout.LayoutParams(0, dp(heightDp), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }) }
        return row
    }

    /**
     * Slider satu baris: label · SeekBar · nilai. Ketuk nilai untuk kembali ke [default].
     * [onChange] dipanggil saat digeser (live), [onStop] saat jari diangkat.
     */
    fun slider(
        label: String,
        min: Int,
        max: Int,
        value: Int,
        default: Int = value,
        format: (Int) -> String = { it.toString() },
        onStart: () -> Unit = {},
        onStop: (Int) -> Unit = {},
        onChange: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(label, 11.5f, c.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(dp(84), -2))
        val valueView = text(format(value), 11.5f, c.ink, true).apply {
            gravity = Gravity.CENTER
            background = c.pressable(Color.TRANSPARENT, 8)
            isClickable = true
            contentDescription = "$label, ketuk untuk reset"
        }
        val seek = SeekBar(a).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            contentDescription = label
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(s: SeekBar?) { onStart() }
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + min
                valueView.text = format(v)
                if (fromUser) onChange(v)
            }
            override fun onStopTrackingTouch(s: SeekBar?) { onStop(seek.progress + min) }
        })
        valueView.setOnClickListener {
            onStart()
            seek.progress = (default - min).coerceIn(0, max - min)
            onChange(default)
            onStop(default)
        }
        row.addView(seek, LinearLayout.LayoutParams(0, dp(36), 1f))
        row.addView(valueView, LinearLayout.LayoutParams(dp(52), dp(30)))
        return row
    }

    /** Baris sakelar geser — sama dengan Video Editor (label kiri, sakelar kanan). */
    fun toggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            background = c.pressable(Color.TRANSPARENT, 10)
            isClickable = true
            contentDescription = label
        }
        row.addView(text(label, 12.5f, c.ink), LinearLayout.LayoutParams(0, -2, 1f))
        val track = FrameLayout(a)
        val knob = View(a)
        fun paint(on: Boolean) {
            track.background = c.shape(if (on) c.activeFill else c.stroke, 14)
            knob.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) c.activeInk else c.surface) }
            (knob.layoutParams as? FrameLayout.LayoutParams)?.let { it.gravity = (if (on) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL; knob.layoutParams = it }
        }
        track.addView(knob, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(4); rightMargin = dp(4) })
        var state = checked
        paint(state)
        row.setOnClickListener {
            state = !state
            paint(state)
            onChange(state)
        }
        row.addView(track, LinearLayout.LayoutParams(dp(48), dp(28)))
        return row
    }

    fun primaryButton(label: String, onClick: () -> Unit): TextView = TextView(a).apply {
        text = label
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(c.activeInk)
        setPadding(dp(14), 0, dp(14), 0)
        background = c.pressable(c.activeFill, 18)
        isClickable = true
        contentDescription = label
        setOnClickListener { onClick() }
    }

    fun secondaryButton(label: String, onClick: () -> Unit): TextView = TextView(a).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(c.ink)
        setPadding(dp(14), 0, dp(14), 0)
        background = c.pressable(c.surface2, 18, c.stroke)
        isClickable = true
        contentDescription = label
        setOnClickListener { onClick() }
    }

    /** Sel navigasi bawah (ikon + label); gaya aktif diatur lewat [styleNav]. */
    fun navCell(iconName: String, label: String, onClick: () -> Unit): LinearLayout {
        val cell = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
        }
        cell.addView(icon(iconName, 21f, c.ink), LinearLayout.LayoutParams(-1, dp(25)))
        cell.addView(text(label, 10f, c.ink).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(2), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        return cell
    }

    fun styleNav(cells: Map<String, LinearLayout>, active: String?) {
        for ((key, cell) in cells) {
            val on = key == active
            cell.background = if (on) c.pressable(c.activeFill, 14) else c.pressable(Color.TRANSPARENT, 14)
            val col = if (on) c.activeInk else c.ink
            (cell.getChildAt(0) as? TextView)?.setTextColor(col)
            (cell.getChildAt(1) as? TextView)?.setTextColor(col)
        }
    }

    /** Menu sederhana (dialog bertema) untuk aksi sekunder top bar — menjaga top bar tetap lega. */
    fun menu(title: String, items: List<Pair<String, () -> Unit>>) {
        a.themedDialog()
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    fun palette(): List<Int> = listOf(
        Color.WHITE, Color.rgb(242, 242, 242), Color.rgb(200, 200, 204), Color.rgb(128, 128, 132), Color.rgb(60, 60, 64), Color.BLACK,
        Color.rgb(239, 68, 68), Color.rgb(249, 115, 22), Color.rgb(234, 179, 8), Color.rgb(34, 197, 94), Color.rgb(59, 130, 246), Color.rgb(139, 92, 246), Color.rgb(236, 72, 153)
    )

    /** Deretan contoh warna dalam satu baris. */
    fun swatchRow(colors: List<Int>, selected: Int?, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (col in colors) {
            val on = selected != null && (selected or 0xFF000000.toInt()) == (col or 0xFF000000.toInt())
            row.addView(View(a).apply {
                background = c.shape(col, 14, if (on) c.ink else c.stroke)
                if (on) { scaleX = 1.12f; scaleY = 1.12f }
                isClickable = true
                contentDescription = String.format("Warna #%06X", 0xFFFFFF and col)
                setOnClickListener { onPick(col) }
            }, LinearLayout.LayoutParams(0, dp(28), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        return row
    }
}

/** Lembar bawah sederhana di atas area kanvas; tombol Back menutupnya lebih dulu. */
internal class ImgSheet(private val kit: ImgKit, private val host: FrameLayout) {
    private var overlay: FrameLayout? = null
    private var onClose: (() -> Unit)? = null
    private val back: () -> Boolean = { if (overlay?.isAttachedToWindow == true) { close(); true } else false }
    val isOpen: Boolean get() = overlay != null

    fun open(title: String, onClosed: (() -> Unit)? = null, build: (LinearLayout) -> Unit) {
        close(animate = false)
        onClose = onClosed
        val c = kit.c
        val ov = FrameLayout(kit.a).apply {
            setBackgroundColor(Color.argb(if (c.dark) 110 else 60, 0, 0, 0))
            isClickable = true
            setOnClickListener { close() }
        }
        val panel = LinearLayout(kit.a).apply {
            orientation = LinearLayout.VERTICAL
            background = c.shape(c.surface, 20, c.stroke)
            elevation = kit.dp(10).toFloat()
            setPadding(kit.dp(14), kit.dp(8), kit.dp(14), kit.dp(12))
            isClickable = true
        }
        val head = LinearLayout(kit.a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(kit.text(title, 15f, c.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(kit.icon("chevron-down", 22f, c.ink, "Tutup") { close() }, LinearLayout.LayoutParams(kit.dp(44), kit.dp(40)))
        panel.addView(head, LinearLayout.LayoutParams(-1, kit.dp(40)).apply { bottomMargin = kit.dp(6) })
        build(panel)
        ov.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = kit.dp(6); rightMargin = kit.dp(6); bottomMargin = kit.dp(6); topMargin = kit.dp(40)
        })
        host.addView(ov, FrameLayout.LayoutParams(-1, -1))
        overlay = ov
        OverlayBack.push(back)
        if (kit.a.animationsEnabled()) {
            panel.translationY = kit.dp(60).toFloat()
            panel.alpha = 0f
            panel.animate().translationY(0f).alpha(1f).setDuration(170L).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    fun close(animate: Boolean = true) {
        val ov = overlay ?: return
        overlay = null
        OverlayBack.remove(back)
        val cb = onClose
        onClose = null
        if (animate && kit.a.animationsEnabled()) ov.animate().alpha(0f).setDuration(110L).withEndAction { host.removeView(ov) }.start()
        else host.removeView(ov)
        cb?.invoke()
    }

    fun detach() {
        OverlayBack.remove(back)
        overlay?.let { host.removeView(it) }
        overlay = null
    }
}

/**
 * Kanvas gambar: konten digambar dalam koordinat gambar (0..contentW, 0..contentH).
 * Dua jari = zoom + geser (selalu). Satu jari = [gesture] bila diklaim, jika tidak = geser.
 * Ketuk dua kali = pas layar.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class ZoomImageView(ctx: Context, private val workspace: Int) : View(ctx) {
    interface Gesture {
        /** @return true bila sentuhan ini diambil alih (tidak menggeser kanvas). */
        fun onDown(x: Float, y: Float): Boolean
        fun onMove(x: Float, y: Float) {}
        fun onUp(x: Float, y: Float) {}
        fun onCancel() {}
    }

    var contentW = 0
    var contentH = 0
    var drawer: ((Canvas) -> Unit)? = null
    /** Digambar setelah konten, tanpa clip; menerima skala layar/gambar. */
    var overlayDrawer: ((Canvas, Float) -> Unit)? = null
    var checker = false
    var gesture: Gesture? = null
    /** Radius kursor kuas dalam piksel layar; 0 = tidak tampil. */
    var cursorRadiusPx = 0f
    var onViewChanged: (() -> Unit)? = null
    var onTap: ((Float, Float) -> Unit)? = null
    var userScale = 1f
    var panX = 0f
    var panY = 0f
    var padPx = 0f

    private var fitScale = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val checkerPaint: Paint by lazy { buildChecker() }

    private var mode = 0 // 0 none, 1 gesture, 2 pan, 3 pinch, 4 ignore
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapTime = 0L
    private var startDist = 1f
    private var startScale = 1f
    private var anchorIx = 0f
    private var anchorIy = 0f
    private var cursorX = -1f
    private var cursorY = -1f

    private fun buildChecker(): Paint {
        val cell = (8 * resources.displayMetrics.density).toInt().coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint()
        p.color = Color.rgb(252, 252, 252)
        cv.drawRect(0f, 0f, cell * 2f, cell * 2f, p)
        p.color = Color.rgb(210, 210, 214)
        cv.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
        cv.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, p)
        return Paint().apply { shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }

    private fun computeFit() {
        if (contentW <= 0 || contentH <= 0 || width <= 0 || height <= 0) return
        fitScale = min((width - padPx * 2).coerceAtLeast(1f) / contentW, (height - padPx * 2).coerceAtLeast(1f) / contentH)
    }

    fun scale(): Float = fitScale * userScale
    fun originX(): Float = (width - contentW * scale()) / 2f + panX
    fun originY(): Float = (height - contentH * scale()) / 2f + panY
    fun toImageX(x: Float): Float = (x - originX()) / scale()
    fun toImageY(y: Float): Float = (y - originY()) / scale()
    fun toScreenX(ix: Float): Float = originX() + ix * scale()
    fun toScreenY(iy: Float): Float = originY() + iy * scale()

    fun setContentSize(w: Int, h: Int, reset: Boolean) {
        contentW = w
        contentH = h
        if (reset) { userScale = 1f; panX = 0f; panY = 0f }
        computeFit()
        invalidate()
    }

    fun resetView() {
        userScale = 1f
        panX = 0f
        panY = 0f
        invalidate()
        onViewChanged?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeFit()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(workspace)
        if (contentW <= 0 || contentH <= 0) return
        computeFit()
        val s = scale()
        val left = originX()
        val top = originY()
        val right = left + contentW * s
        val bottom = top + contentH * s
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(70, 0, 0, 0)
        canvas.drawRect(left + 4f, top + 5f, right + 4f, bottom + 5f, paint)
        if (checker) canvas.drawRect(left, top, right, bottom, checkerPaint)
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        canvas.translate(left, top)
        canvas.scale(s, s)
        drawer?.invoke(canvas)
        canvas.restore()
        overlayDrawer?.let {
            canvas.save()
            canvas.translate(left, top)
            canvas.scale(s, s)
            it(canvas, s)
            canvas.restore()
        }
        if (cursorRadiusPx > 0f && cursorX >= 0f && mode == 1) {
            cursorPaint.strokeWidth = 3f
            cursorPaint.color = Color.argb(200, 0, 0, 0)
            canvas.drawCircle(cursorX, cursorY, cursorRadiusPx, cursorPaint)
            cursorPaint.strokeWidth = 1.5f
            cursorPaint.color = Color.WHITE
            canvas.drawCircle(cursorX, cursorY, cursorRadiusPx, cursorPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                computeFit()
                lastX = event.x; lastY = event.y
                downX = event.x; downY = event.y
                moved = false
                cursorX = event.x; cursorY = event.y
                val g = gesture
                mode = if (g != null && g.onDown(toImageX(event.x), toImageY(event.y))) 1 else 2
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                if (mode == 1) gesture?.onCancel()
                mode = 3
                startDist = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1)).coerceAtLeast(1f)
                startScale = userScale
                val mx = (event.getX(0) + event.getX(1)) / 2f
                val my = (event.getY(0) + event.getY(1)) / 2f
                anchorIx = toImageX(mx)
                anchorIy = toImageY(my)
                cursorX = -1f
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && hypot(event.x - downX, event.y - downY) > 6f * resources.displayMetrics.density) moved = true
                when (mode) {
                    1 -> {
                        cursorX = event.x; cursorY = event.y
                        gesture?.onMove(toImageX(event.x), toImageY(event.y))
                        invalidate()
                    }
                    2 -> if (moved) {
                        panX += event.x - lastX
                        panY += event.y - lastY
                        invalidate()
                        onViewChanged?.invoke()
                    }
                    3 -> if (event.pointerCount >= 2) {
                        val dist = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                        userScale = (startScale * dist / startDist).coerceIn(0.5f, 16f)
                        val mx = (event.getX(0) + event.getX(1)) / 2f
                        val my = (event.getY(0) + event.getY(1)) / 2f
                        val s = scale()
                        // Titik gambar di bawah jari tetap di bawah jari (zoom berjangkar).
                        panX = mx - anchorIx * s - (width - contentW * s) / 2f
                        panY = my - anchorIy * s - (height - contentH * s) / 2f
                        invalidate()
                        onViewChanged?.invoke()
                    }
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == 3) mode = 4
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    1 -> gesture?.onUp(toImageX(event.x), toImageY(event.y))
                    2 -> if (!moved) {
                        val now = event.eventTime
                        if (now - lastTapTime < 300L) { lastTapTime = 0L; resetView() }
                        else { lastTapTime = now; onTap?.invoke(toImageX(event.x), toImageY(event.y)) }
                    }
                }
                mode = 0
                cursorX = -1f
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == 1) gesture?.onCancel()
                mode = 0
                cursorX = -1f
                invalidate()
            }
        }
        return true
    }
}

/** Gambar bitmap secara center-crop ke persegi (0,0,w,h). */
internal fun drawCenterCrop(canvas: Canvas, bmp: Bitmap, w: Float, h: Float, paint: Paint) {
    val s = max(w / bmp.width, h / bmp.height)
    val dw = bmp.width * s
    val dh = bmp.height * s
    canvas.drawBitmap(bmp, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), paint)
}

/** Blur murah untuk latar: perkecil → box blur → digambar diperbesar dengan filter bilinear. */
internal fun makeSoftBlur(src: Bitmap, amount: Int): Bitmap {
    val factor = (4 + amount / 4).coerceIn(4, 32)
    val sw = (src.width / factor).coerceAtLeast(2)
    val sh = (src.height / factor).coerceAtLeast(2)
    val small = Bitmap.createScaledBitmap(src, sw, sh, true)
    return boxBlur(small, 2)
}

/** Decode dengan orientasi EXIF diterapkan (foto kamera tidak lagi miring). */
internal fun MainActivity.decodeOrientedBitmap(uri: Uri, maxSide: Int): Bitmap {
    val bmp = decodeBitmapFromUri(uri, maxSide)
    val orientation = runCatching {
        contentResolver.openInputStream(uri)?.use {
            android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        } ?: android.media.ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
    val m = Matrix()
    when (orientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        android.media.ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
        android.media.ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        else -> return ensureMutableArgb(bmp)
    }
    val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    if (out !== bmp) bmp.recycle()
    return ensureMutableArgb(out)
}

internal fun ensureMutableArgb(b: Bitmap): Bitmap {
    if (b.config == Bitmap.Config.ARGB_8888 && b.isMutable) return b
    val out = b.copy(Bitmap.Config.ARGB_8888, true) ?: b
    if (out !== b) b.recycle()
    return out
}

/** Perkecil agar sisi terpanjang ≤ [maxSide] (tanpa memperbesar). */
internal fun fitLongSide(b: Bitmap, maxSide: Int): Bitmap {
    val long = max(b.width, b.height)
    if (long <= maxSide) return b
    val s = maxSide.toFloat() / long
    return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
}

/** Nama file nyaman dibaca untuk sebuah Uri gambar. */
internal fun MainActivity.displayNameFor(uri: Uri): String = runCatching {
    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) cur.getString(0) else null
    }
}.getOrNull()?.substringBeforeLast('.')?.ifBlank { null } ?: "foto"

internal data class SavedImage(val uri: Uri, val label: String, val bytes: Long, val mime: String)

/**
 * Simpan ke Galeri (Pictures/GITLS) — Android 10+ lewat MediaStore; Android 7–9 ke folder aplikasi
 * (tetap bisa dibagikan). [format] = "png" | "jpg" | "webp".
 */
internal fun MainActivity.saveImageToGallery(bitmap: Bitmap, baseName: String, format: String, quality: Int): SavedImage {
    val q = quality.coerceIn(1, 100)
    val (ext, mime) = when (format) {
        "png" -> "png" to "image/png"
        "webp" -> "webp" to "image/webp"
        else -> "jpg" to "image/jpeg"
    }
    val safe = baseName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").take(40).ifBlank { "gambar" }
    val name = "${safe}_${System.currentTimeMillis()}.$ext"
    fun write(out: java.io.OutputStream): Boolean = when (format) {
        "png" -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        "webp" -> if (Build.VERSION.SDK_INT >= 30) {
            bitmap.compress(if (q >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY, q, out)
        } else {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, q, out)
        }
        else -> bitmap.compress(Bitmap.CompressFormat.JPEG, q, out)
    }
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GITLS")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Penyimpanan galeri tidak tersedia")
        var bytes = 0L
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                val counting = CountingStream(os)
                if (!write(counting)) error("Gagal menulis gambar")
                counting.flush()
                bytes = counting.count
            } ?: error("Tidak bisa membuka penyimpanan")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Throwable) {
            runCatching { contentResolver.delete(uri, null, null) }
            throw e
        }
        return SavedImage(uri, "Pictures/GITLS/$name", bytes, mime)
    }
    val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, name)
    FileOutputStream(file).use { if (!write(it)) error("Gagal menulis gambar") }
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    return SavedImage(uri, "Folder aplikasi/Pictures/$name", file.length(), mime)
}

private class CountingStream(private val inner: java.io.OutputStream) : java.io.OutputStream() {
    var count = 0L
    override fun write(b: Int) { inner.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); count += len }
    override fun flush() { inner.flush() }
}

internal fun MainActivity.shareImageUri(saved: SavedImage) {
    runCatching {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = saved.mime
            putExtra(Intent.EXTRA_STREAM, saved.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Bagikan gambar"))
    }.onFailure { toast("Tidak bisa membagikan: ${it.message}") }
}

internal fun humanBytes(n: Long): String = when {
    n >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", n / 1048576.0)
    n >= 1024L -> String.format(Locale.US, "%.0f KB", n / 1024.0)
    else -> "$n B"
}

/** Ukuran target dengan sisi terpanjang [longSide] (null = asli), menjaga rasio. */
internal fun targetSize(w: Int, h: Int, longSide: Int?): Pair<Int, Int> {
    if (longSide == null || longSide <= 0) return w to h
    val s = longSide.toFloat() / max(w, h)
    return (w * s).toInt().coerceAtLeast(1) to (h * s).toInt().coerceAtLeast(1)
}
