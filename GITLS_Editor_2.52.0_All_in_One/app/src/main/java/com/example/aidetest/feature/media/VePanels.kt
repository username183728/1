package com.example.aidetest

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Panel parameter di dock bawah Video Editor (menggantikan dock saat dibuka). */
internal class VePanels(private val ui: VideoEditorUi) {
    private val a = ui.a
    private val kit = ui.kit
    private val p = ui.p
    private val handler = ui.handler
    private fun dp(v: Int): Int = ui.dp(v)

    private var recorder: VeVoiceRecorder? = null
    private var voiceTicker: Runnable? = null
    private var voiceStartMs = 0L
    private var voicePrevMuted = false

    @Volatile
    private var analysisCancelled = false
    private var analysisBusy = false

    private val palette = intArrayOf(
        Color.WHITE, Color.BLACK, Color.rgb(255, 59, 48), Color.rgb(255, 149, 0), Color.rgb(255, 204, 0),
        Color.rgb(52, 199, 89), Color.rgb(0, 199, 190), Color.rgb(0, 122, 255), Color.rgb(88, 86, 214),
        Color.rgb(175, 82, 222), Color.rgb(255, 45, 85), Color.rgb(142, 142, 147), Color.rgb(209, 209, 214)
    )

    private fun pct(v: Float): String = "${(v * 100f).roundToInt()}%"
    private fun secs(ms: Long): String = String.format(Locale.US, "%.1f dtk", ms / 1000f)

    private fun gap(h: Int = 10): View = View(a).also { it.minimumHeight = dp(h) }

    /** Slider yang langsung mengubah model: undo sekali per sentuhan, simpan saat dilepas. */
    private fun live(
        label: String, min: Float, max: Float, value: Float, fmt: (Float) -> String,
        reload: Boolean = false, apply: (Float) -> Unit
    ): View = kit.slider(
        label, min, max, value, fmt,
        onStart = { ui.checkpoint() },
        onChange = { v -> apply(v); ui.touch() },
        onEnd = { if (reload) ui.afterChange() else ui.save() }
    )

    private fun grid(cells: List<View>, cols: Int, cellH: Int, gapDp: Int = 8): View {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        var i = 0
        while (i < cells.size) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until cols) {
                val cell = cells.getOrNull(i + k)
                val lp = LinearLayout.LayoutParams(0, dp(cellH), 1f).apply { rightMargin = if (k < cols - 1) dp(gapDp) else 0; bottomMargin = dp(gapDp) }
                if (cell != null) row.addView(cell, lp) else row.addView(View(a), lp)
            }
            box.addView(row)
            i += cols
        }
        return box
    }

    // ================================================================== kecepatan / durasi / volume

    fun speed() {
        val c = ui.selectedClip() ?: return
        if (c.isImage) { a.toast("Kecepatan hanya untuk klip video"); return }
        val body = kit.vbox()
        val steps = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
        body.addView(kit.chips(steps.map { it.toString() to fmtSpeed(it) }, steps.firstOrNull { abs(it - c.speed) < 0.001f }?.toString() ?: "") { key ->
            ui.checkpoint()
            c.speed = key.toFloat()
            ui.afterChange()
            speed()
        })
        body.addView(gap(6))
        body.addView(live("Kecepatan", 0.25f, 4f, c.speed, { fmtSpeed(it) }, reload = true) { v -> c.speed = (v * 20f).roundToInt() / 20f })
        body.addView(kit.note("Durasi klip ikut berubah. Suara dipercepat/diperlambat bersama video saat ekspor render penuh."))
        ui.openPanel("Kecepatan", body)
    }

    private fun fmtSpeed(v: Float): String = if (v == v.toInt().toFloat()) "${v.toInt()}×" else String.format(Locale.US, "%.2f×", v).replace(Regex("0+×$"), "×")

    fun photoDuration() {
        val c = ui.selectedClip() ?: return
        if (!c.isImage) { a.toast("Durasi tampil hanya untuk foto"); return }
        val body = kit.vbox()
        val opts = listOf(1000L, 2000L, 3000L, 5000L, 8000L, 10000L)
        body.addView(kit.chips(opts.map { it.toString() to "${it / 1000} dtk" }, c.durationMs().toString()) { key ->
            ui.checkpoint()
            c.sourceDurationMs = key.toLong()
            c.trimStartMs = 0L
            c.trimEndMs = 0L
            ui.afterChange()
            photoDuration()
        })
        body.addView(gap(6))
        body.addView(live("Durasi tampil", 0.5f, 30f, c.durationMs() / 1000f, { String.format(Locale.US, "%.1f dtk", it) }, reload = true) { v ->
            c.sourceDurationMs = (v * 1000f).toLong().coerceAtLeast(500L)
            c.trimStartMs = 0L
            c.trimEndMs = 0L
        })
        ui.openPanel("Durasi foto", body)
    }

    fun volume() {
        val c = ui.selectedClip() ?: return
        if (c.isImage) { a.toast("Foto tidak punya suara"); return }
        val body = kit.vbox()
        body.addView(kit.switchRow("Bisukan klip ini", c.volume <= 0.001f) { muted ->
            ui.checkpoint()
            c.volume = if (muted) 0f else 1f
            ui.afterChange(reload = false)
            volume()
        })
        body.addView(live("Volume klip", 0f, 2f, c.volume, { pct(it) }) { v -> c.volume = v; ui.playback.applyVolume() })
        body.addView(kit.note("Di atas 100% suara dikuatkan saat ekspor (pratinjau maksimum 100%). Suara klip global dimatikan lewat tombol Suara di kiri timeline."))
        ui.openPanel("Volume", body)
    }

    // ================================================================== filter & penyesuaian

    private fun filterThumb(src: Bitmap?, c: VeClip, key: String): Bitmap? {
        if (src == null) return null
        val size = dp(64)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val side = min(src.width, src.height)
        val sr = Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        VePreviewFx.clipMatrix(c.copy(filter = key, brightness = 0f, contrast = 0f, saturation = 0f, temperature = 0f))?.let { paint.colorFilter = ColorMatrixColorFilter(it) }
        cv.drawBitmap(src, sr, Rect(0, 0, size, size), paint)
        return out
    }

    private val filterNames = listOf(
        "normal" to "Normal", "bw" to "Hitam putih", "warm" to "Hangat", "cool" to "Dingin",
        "vivid" to "Cerah", "fade" to "Pudar", "invert" to "Invert"
    )

    fun filter() {
        val c = ui.targetClip() ?: return
        val body = kit.vbox()
        val poster = p.thumbs[c.uri.toString()]
        val cells = filterNames.map { (key, label) ->
            val cell = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener {
                    ui.checkpoint()
                    c.filter = key
                    ui.afterChange(reload = false)
                    filter()
                }
            }
            val img = ImageView(a).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = kit.shape(kit.bg2, 10, if (c.filter == key) kit.ink else kit.stroke, if (c.filter == key) 3 else 1)
                setPadding(dp(2), dp(2), dp(2), dp(2))
                filterThumb(poster, c, key)?.let { setImageBitmap(it) }
            }
            cell.addView(img, LinearLayout.LayoutParams(dp(64), dp(64)))
            cell.addView(kit.text(label, 10.5f, if (c.filter == key) kit.ink else kit.muted, c.filter == key).apply { gravity = Gravity.CENTER; maxLines = 1 },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            cell
        }
        val scroll = android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        for (cell in cells) row.addView(cell, LinearLayout.LayoutParams(dp(76), -2))
        scroll.addView(row)
        body.addView(scroll)
        body.addView(gap(8))
        body.addView(kit.button("Terapkan filter ke semua klip", "check-all", false) {
            ui.checkpoint()
            for (k in p.clips) k.filter = c.filter
            ui.afterChange(reload = false)
            a.toast("Filter diterapkan ke ${p.clips.size} klip")
        }, LinearLayout.LayoutParams(-1, dp(44)))
        body.addView(kit.note("Pratinjau memperkirakan warna; hasil ekspor memakai efek warna Media3 yang serupa."))
        ui.openPanel("Filter", body)
    }

    fun adjust() {
        val c = ui.selectedClip() ?: return
        val body = kit.vbox()
        body.addView(live("Kecerahan", -50f, 50f, c.brightness * 100f, { "${it.roundToInt()}" }) { v -> c.brightness = v / 100f })
        body.addView(live("Kontras", -50f, 50f, c.contrast * 100f, { "${it.roundToInt()}" }) { v -> c.contrast = v / 100f })
        body.addView(live("Saturasi", -100f, 100f, c.saturation, { "${it.roundToInt()}" }) { v -> c.saturation = v })
        body.addView(live("Suhu warna", -100f, 100f, c.temperature * 100f, { "${it.roundToInt()}" }) { v -> c.temperature = v / 100f })
        body.addView(gap(6))
        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(kit.button("Reset", "restore", false) {
            ui.checkpoint()
            c.brightness = 0f; c.contrast = 0f; c.saturation = 0f; c.temperature = 0f
            ui.afterChange(reload = false)
            adjust()
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        btns.addView(kit.button("Terapkan ke semua", "check-all", false) {
            ui.checkpoint()
            for (k in p.clips) { k.brightness = c.brightness; k.contrast = c.contrast; k.saturation = c.saturation; k.temperature = c.temperature }
            ui.afterChange(reload = false)
            a.toast("Penyesuaian diterapkan ke ${p.clips.size} klip")
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        body.addView(btns)
        ui.openPanel("Atur warna", body)
    }

    // ================================================================== transisi / zoom

    fun transition() {
        val c = ui.selectedClip() ?: return
        val idx = p.clips.indexOfFirst { it.id == c.id }
        val body = kit.vbox()
        if (idx == 0) body.addView(kit.note("Transisi berlaku saat klip ini masuk, jadi klip pertama tidak memakainya. Pilih klip kedua dan seterusnya."))
        body.addView(kit.chips(VeTransMath.TYPES, c.transition) { key ->
            ui.checkpoint()
            c.transition = key
            ui.afterChange(reload = false)
            transition()
        })
        body.addView(gap(8))
        body.addView(kit.button("Terapkan ke semua klip (kecuali pertama)", "check-all", false) {
            ui.checkpoint()
            for ((i, k) in p.clips.withIndex()) k.transition = if (i == 0) "none" else c.transition
            ui.afterChange(reload = false)
        }, LinearLayout.LayoutParams(-1, dp(44)))
        body.addView(kit.note("Transisi terlihat pada hasil ekspor render penuh (pratinjau memotong langsung)."))
        ui.openPanel("Transisi", body)
    }

    fun zoomPan() {
        val c = ui.selectedClip() ?: return
        val body = kit.vbox()
        body.addView(live("Zoom", 100f, 400f, c.zoom * 100f, { "${it.roundToInt()}%" }) { v -> c.zoom = (v / 100f).coerceIn(1f, 4f) })
        body.addView(live("Geser horizontal", -100f, 100f, c.panX * 100f, { "${it.roundToInt()}" }) { v -> c.panX = v / 100f })
        body.addView(live("Geser vertikal", -100f, 100f, c.panY * 100f, { "${it.roundToInt()}" }) { v -> c.panY = v / 100f })
        body.addView(gap(6))
        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(kit.button("Putar 90°", "rotate-right", false) {
            ui.checkpoint(); c.rotation = (c.rotation + 90) % 360; ui.afterChange(reload = false)
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        btns.addView(kit.button(if (c.flipH) "Cermin: aktif" else "Cermin", "flip-horizontal", c.flipH) {
            ui.checkpoint(); c.flipH = !c.flipH; ui.afterChange(reload = false); zoomPan()
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        btns.addView(kit.button("Reset", "restore", false) {
            ui.checkpoint(); c.zoom = 1f; c.panX = 0f; c.panY = 0f; c.flipH = false; ui.afterChange(reload = false); zoomPan()
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        body.addView(btns)
        ui.openPanel("Zoom & posisi", body)
    }

    // ================================================================== audio item

    fun audioVolume() {
        val x = ui.selectedAudio() ?: return
        val body = kit.vbox()
        body.addView(live("Volume", 0f, 2f, x.volume, { pct(it) }) { v -> x.volume = v; ui.playback.applyVolume() })
        body.addView(kit.chips(listOf("0" to "Senyap", "0.5" to "50%", "1" to "100%", "1.5" to "150%"), "") { key ->
            ui.checkpoint(); x.volume = key.toFloat(); ui.afterChange(reload = false); audioVolume()
        })
        body.addView(kit.note("Di atas 100% dikuatkan saat ekspor; pratinjau maksimum 100%."))
        ui.openPanel("Volume audio", body)
    }

    fun audioFade() {
        val x = ui.selectedAudio() ?: return
        val body = kit.vbox()
        val maxFade = max(500f, x.durationMs() / 2f)
        body.addView(live("Fade masuk", 0f, maxFade / 1000f, x.fadeInMs / 1000f, { String.format(Locale.US, "%.1f dtk", it) }) { v -> x.fadeInMs = (v * 1000f).toLong() })
        body.addView(live("Fade keluar", 0f, maxFade / 1000f, x.fadeOutMs / 1000f, { String.format(Locale.US, "%.1f dtk", it) }) { v -> x.fadeOutMs = (v * 1000f).toLong() })
        body.addView(kit.note("Bentuk fade terlihat pada waveform di timeline."))
        ui.openPanel("Fade audio", body)
    }

    // ================================================================== overlay (teks, stiker, gambar)

    private var overlayTab = "teks"
    private var lastEditText: EditText? = null

    fun textEditor(focus: Boolean) { overlayPanel("teks", focus) }
    fun textStyle() { overlayPanel("gaya", false) }
    fun animation() { overlayPanel("anim", false) }
    fun overlayAdjust() { overlayPanel("atur", false) }

    private fun overlayPanel(initialTab: String, focus: Boolean) {
        val o = ui.selectedOverlay() ?: return
        val textual = o.kind == "text" || o.kind == "caption"
        val tabs = if (textual) listOf("teks" to "Teks", "gaya" to "Gaya", "garis" to "Garis & latar", "anim" to "Animasi", "waktu" to "Waktu")
        else listOf("atur" to "Atur", "anim" to "Animasi", "waktu" to "Waktu")
        var tab = if (tabs.any { it.first == initialTab }) initialTab else tabs.first().first
        val body = kit.vbox()
        val content = FrameLayout(a)
        val tabHolder = FrameLayout(a)
        fun render() {
            tabHolder.removeAllViews()
            tabHolder.addView(kit.chips(tabs, tab) { key -> tab = key; overlayTab = key; render() })
            content.removeAllViews()
            content.addView(when (tab) {
                "teks" -> tabText(o, focus && lastEditText == null)
                "gaya" -> tabStyle(o)
                "garis" -> tabLine(o)
                "anim" -> tabAnim(o)
                "waktu" -> tabTime(o)
                else -> tabAdjust(o)
            })
        }
        body.addView(tabHolder)
        body.addView(gap(6))
        body.addView(content)
        render()
        ui.openPanel(when (o.kind) { "text" -> "Teks"; "caption" -> "Keterangan"; "sticker" -> "Stiker"; else -> "Gambar" }, body, hideTimeline = textual) {
            lastEditText = null
        }
    }

    private val presets = listOf(
        "judul" to "Judul", "sub" to "Subjudul", "label" to "Label", "tipis" to "Tipis",
        "neon" to "Neon", "komik" to "Komik", "elegan" to "Elegan", "mesin" to "Mesin"
    )

    private fun applyPreset(o: VeOverlay, key: String) {
        o.bold = false
        o.italic = false
        when (key) {
            "judul" -> { o.font = "bold"; o.size = 0.09f; o.color = Color.WHITE; o.outlineColor = Color.BLACK; o.outlineWidth = 0.08f; o.shadow = 0.3f; o.bgOpacity = 0f }
            "sub" -> { o.font = "sans"; o.size = 0.05f; o.color = Color.WHITE; o.outlineColor = Color.BLACK; o.outlineWidth = 0.04f; o.shadow = 0.2f; o.bgOpacity = 0f }
            "label" -> { o.font = "bold"; o.size = 0.05f; o.color = Color.WHITE; o.outlineWidth = 0f; o.shadow = 0f; o.bgColor = Color.BLACK; o.bgOpacity = 0.65f }
            "tipis" -> { o.font = "light"; o.size = 0.07f; o.color = Color.WHITE; o.outlineWidth = 0f; o.shadow = 0.5f; o.bgOpacity = 0f }
            "neon" -> { o.font = "bold"; o.size = 0.08f; o.color = Color.rgb(160, 255, 220); o.outlineColor = Color.rgb(0, 184, 148); o.outlineWidth = 0.1f; o.shadow = 0.8f; o.bgOpacity = 0f }
            "komik" -> { o.font = "casual"; o.size = 0.085f; o.color = Color.rgb(255, 214, 10); o.outlineColor = Color.BLACK; o.outlineWidth = 0.12f; o.shadow = 0.2f; o.bgOpacity = 0f }
            "elegan" -> { o.font = "serifb"; o.size = 0.07f; o.color = Color.rgb(245, 230, 200); o.outlineWidth = 0f; o.shadow = 0.5f; o.bgOpacity = 0f }
            "mesin" -> { o.font = "mono"; o.size = 0.05f; o.color = Color.rgb(57, 255, 20); o.outlineWidth = 0f; o.shadow = 0f; o.bgColor = Color.BLACK; o.bgOpacity = 0.7f }
        }
    }

    private fun tabText(o: VeOverlay, requestFocus: Boolean): View {
        val box = kit.vbox()
        val input = kit.field("Ketik teks…", o.text, 3)
        lastEditText = input
        var checkpointed = false
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, aft: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!checkpointed) { checkpointed = true; ui.checkpoint() }
                o.text = s?.toString() ?: ""
                ui.touch()
            }
        })
        box.addView(input, LinearLayout.LayoutParams(-1, -2))
        if (requestFocus) {
            input.post {
                input.requestFocus()
                input.selectAll()
                runCatching {
                    val imm = a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(input, 0)
                }
            }
        }
        box.addView(kit.section("Gaya cepat"))
        box.addView(kit.chips(presets, "") { key ->
            ui.checkpoint()
            applyPreset(o, key)
            ui.afterChange(reload = false)
        })
        return box
    }

    private fun tabStyle(o: VeOverlay): View {
        val box = kit.vbox()
        box.addView(kit.section("Font"))
        box.addView(kit.chips(PeLayerRender.FONTS, o.font) { key -> ui.checkpoint(); o.font = key; ui.afterChange(reload = false); overlayPanel("gaya", false) })
        box.addView(gap(4))
        box.addView(kit.toggleRow(listOf(
            VeUiKit.Toggle("Tebal", o.bold) { ui.checkpoint(); o.bold = !o.bold; ui.afterChange(reload = false); overlayPanel("gaya", false) },
            VeUiKit.Toggle("Miring", o.italic) { ui.checkpoint(); o.italic = !o.italic; ui.afterChange(reload = false); overlayPanel("gaya", false) },
            VeUiKit.Toggle("Kiri", o.align == "left") { ui.checkpoint(); o.align = "left"; ui.afterChange(reload = false); overlayPanel("gaya", false) },
            VeUiKit.Toggle("Tengah", o.align == "center") { ui.checkpoint(); o.align = "center"; ui.afterChange(reload = false); overlayPanel("gaya", false) },
            VeUiKit.Toggle("Kanan", o.align == "right") { ui.checkpoint(); o.align = "right"; ui.afterChange(reload = false); overlayPanel("gaya", false) }
        )))
        box.addView(live("Ukuran huruf", 2f, 30f, o.size * 100f, { "${it.roundToInt()}%" }) { v -> o.size = v / 100f })
        box.addView(kit.section("Warna teks"))
        box.addView(kit.colorSwatches(palette, o.color) { c -> ui.checkpoint(); o.color = c; ui.afterChange(reload = false); overlayPanel("gaya", false) })
        box.addView(live("Opasitas", 10f, 100f, o.opacity * 100f, { "${it.roundToInt()}%" }) { v -> o.opacity = v / 100f })
        box.addView(live("Putar", -180f, 180f, o.rotation, { "${it.roundToInt()}°" }) { v -> o.rotation = v })
        return box
    }

    private fun tabLine(o: VeOverlay): View {
        val box = kit.vbox()
        box.addView(live("Tebal garis tepi", 0f, 25f, o.outlineWidth * 100f, { "${it.roundToInt()}%" }) { v -> o.outlineWidth = v / 100f })
        box.addView(kit.colorSwatches(palette, o.outlineColor) { c -> ui.checkpoint(); o.outlineColor = c; ui.afterChange(reload = false); overlayPanel("garis", false) })
        box.addView(live("Bayangan", 0f, 100f, o.shadow * 100f, { "${it.roundToInt()}%" }) { v -> o.shadow = v / 100f })
        box.addView(live("Kotak latar", 0f, 100f, o.bgOpacity * 100f, { "${it.roundToInt()}%" }) { v -> o.bgOpacity = v / 100f })
        box.addView(kit.colorSwatches(palette, o.bgColor) { c -> ui.checkpoint(); o.bgColor = c; ui.afterChange(reload = false); overlayPanel("garis", false) })
        return box
    }

    private fun tabAdjust(o: VeOverlay): View {
        val box = kit.vbox()
        box.addView(live("Ukuran", 3f, 150f, o.size * 100f, { "${it.roundToInt()}%" }) { v -> o.size = v / 100f })
        box.addView(live("Putar", -180f, 180f, o.rotation, { "${it.roundToInt()}°" }) { v -> o.rotation = v })
        box.addView(live("Opasitas", 10f, 100f, o.opacity * 100f, { "${it.roundToInt()}%" }) { v -> o.opacity = v / 100f })
        if (o.kind == "sticker") {
            if (o.icon.isNotEmpty()) {
                box.addView(kit.section("Warna ikon"))
                box.addView(kit.colorSwatches(palette, o.color) { c -> ui.checkpoint(); o.color = c; ui.afterChange(reload = false); overlayPanel("atur", false) })
            }
            box.addView(live("Bayangan", 0f, 100f, o.shadow * 100f, { "${it.roundToInt()}%" }) { v -> o.shadow = v / 100f })
        }
        box.addView(kit.note("Posisi: seret langsung di pratinjau. Gagang kanan-bawah atau cubit dua jari mengubah ukuran dan putaran."))
        return box
    }

    private fun tabAnim(o: VeOverlay): View {
        val box = kit.vbox()
        box.addView(kit.section("Masuk"))
        box.addView(kit.chips(VeAnimMath.IN, o.animIn) { key -> ui.checkpoint(); o.animIn = key; ui.afterChange(reload = false); overlayPanel("anim", false) })
        box.addView(kit.section("Keluar"))
        box.addView(kit.chips(VeAnimMath.IN, o.animOut) { key -> ui.checkpoint(); o.animOut = key; ui.afterChange(reload = false); overlayPanel("anim", false) })
        box.addView(live("Lama animasi", 0.1f, 2f, o.animMs / 1000f, { String.format(Locale.US, "%.1f dtk", it) }) { v -> o.animMs = (v * 1000f).toLong().coerceIn(100L, 2000L) })
        box.addView(kit.note("Putar video untuk melihat animasi masuk/keluar. Animasi yang sama dipakai di hasil ekspor."))
        return box
    }

    private fun tabTime(o: VeOverlay): View {
        val box = kit.vbox()
        val total = max(p.totalMs(), o.endMs())
        box.addView(live("Mulai", 0f, total / 1000f, o.startMs / 1000f, { String.format(Locale.US, "%.1f dtk", it) }) { v -> o.startMs = (v * 1000f).toLong().coerceAtLeast(0L) })
        box.addView(live("Durasi", 0.2f, max(1f, total / 1000f), o.durationMs / 1000f, { String.format(Locale.US, "%.1f dtk", it) }) { v -> o.durationMs = (v * 1000f).toLong().coerceAtLeast(200L) })
        box.addView(kit.button("Mulai di playhead", "map-marker-right", false) {
            ui.checkpoint(); o.startMs = p.playheadMs; ui.afterChange(reload = false); overlayPanel("waktu", false)
        }, LinearLayout.LayoutParams(-1, dp(44)))
        box.addView(kit.button("Sampai akhir video", "arrow-collapse-right", false) {
            ui.checkpoint(); o.durationMs = max(200L, p.totalMs() - o.startMs); ui.afterChange(reload = false); overlayPanel("waktu", false)
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        return box
    }

    // ================================================================== stiker & efek

    private val extraStickers = listOf(
        "rocket-launch", "party-popper", "gift", "balloon", "bell", "bookmark", "trophy", "diamond-stone", "cake-variant",
        "weather-sunny", "weather-night", "umbrella", "ghost", "robot", "glasses", "alert-circle", "check-circle", "arrow-right-bold"
    )
    private val moreEmoji = listOf("😂", "🥰", "😎", "🤩", "😭", "🙏", "💯", "🎬", "📍", "⭐", "🌈", "☀️", "🌙", "🍕", "⚽", "🎵")

    fun stickers() {
        val body = kit.vbox()
        body.addView(kit.section("Ikon"))
        val icons = (PeLayerRender.STICKERS + extraStickers).distinct().map { name ->
            MdiIconView(a).apply {
                setIconName(name)
                setIconSize(26f)
                setTextColor(kit.ink)
                background = kit.pressable(kit.bg2, 12)
                isClickable = true
                contentDescription = name
                setOnClickListener { ui.addSticker(name, ""); ui.closePanel() }
            } as View
        }
        body.addView(grid(icons, 6, 52))
        body.addView(kit.section("Emoji"))
        val emoji = (PeLayerRender.EMOJI + moreEmoji).distinct().map { e ->
            TextView(a).apply {
                text = e
                textSize = 24f
                gravity = Gravity.CENTER
                background = kit.pressable(kit.bg2, 12)
                isClickable = true
                setOnClickListener { ui.addSticker("", e); ui.closePanel() }
            } as View
        }
        body.addView(grid(emoji, 6, 52))
        ui.openPanel("Stiker", body)
    }

    fun effects(replace: Boolean = false) {
        val body = kit.vbox()
        val cur = if (replace) ui.selectedEffect() else null
        val cells = VeFxMath.TYPES.map { (key, label) ->
            TextView(a).apply {
                text = label
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(if (cur?.type == key) kit.accentInk else kit.ink)
                typeface = if (cur?.type == key) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = kit.pressable(if (cur?.type == key) kit.ink else kit.bg2, 12)
                isClickable = true
                setOnClickListener {
                    if (cur != null) {
                        ui.checkpoint()
                        cur.type = key
                        ui.afterChange(reload = false)
                        ui.closePanel()
                    } else {
                        ui.addEffect(key)
                        ui.closePanel()
                    }
                }
            } as View
        }
        body.addView(grid(cells, 3, 48))
        body.addView(kit.note(
            if (replace) "Pilih jenis efek pengganti untuk item terpilih."
            else "Efek ditambahkan di posisi playhead selama 2 detik. Tarik gagang di timeline untuk mengubah rentang; atur intensitas lewat alat Intensitas."
        ))
        ui.openPanel(if (replace) "Ganti efek" else "Efek", body)
    }

    fun effectAdjust() {
        val e = ui.selectedEffect() ?: return
        val body = kit.vbox()
        body.addView(kit.text(VeFxMath.label(e.type), 14f, kit.ink, true))
        body.addView(gap(4))
        body.addView(live("Intensitas", 0f, 100f, e.intensity * 100f, { "${it.roundToInt()}%" }) { v -> e.intensity = v / 100f })
        body.addView(live("Durasi", 0.2f, max(1f, p.totalMs() / 1000f), e.durationMs / 1000f, { String.format(Locale.US, "%.1f dtk", it) }) { v -> e.durationMs = (v * 1000f).toLong().coerceAtLeast(200L) })
        body.addView(kit.note("Efek gerak/warna/blur terlihat di pratinjau saat playhead berada di dalam rentang efek."))
        ui.openPanel("Intensitas efek", body)
    }

    // ================================================================== rasio

    fun aspect() {
        val body = kit.vbox()
        body.addView(kit.section("Rasio bingkai"))
        val labels = mapOf("asli" to "Asli", "16:9" to "16:9", "9:16" to "9:16", "1:1" to "1:1", "4:5" to "4:5", "4:3" to "4:3", "3:4" to "3:4")
        body.addView(kit.chips(VeProject.ASPECTS.map { it to (labels[it] ?: it) }, p.aspect) { key ->
            p.aspect = key
            ui.afterChange(reload = false)
            ui.save()
            aspect()
        })
        body.addView(kit.section("Isi bingkai"))
        body.addView(kit.chips(listOf("pas" to "Pas (bar hitam)", "isi" to "Isi (potong)", "blur" to "Latar blur"), p.fitMode) { key ->
            p.fitMode = key
            ui.afterChange(reload = false)
            ui.save()
            aspect()
        })
        body.addView(kit.note("9:16 untuk Reels/TikTok/Shorts, 1:1 dan 4:5 untuk feed, 16:9 untuk YouTube. Latar blur memperbesar video menjadi latar buram di balik video utuh."))
        ui.openPanel("Rasio & latar", body)
    }

    // ================================================================== sampul

    private var coverToken = 0

    fun cover() {
        val body = kit.vbox()
        val (ow, oh) = VeCover.size()
        val maxW = dp(150)
        val aspectWH = ow.toFloat() / oh
        val pw = if (aspectWH >= 1f) maxW else (maxW * aspectWH).toInt().coerceAtLeast(dp(60))
        val ph = (pw / aspectWH).toInt()
        val img = ImageView(a).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = kit.shape(Color.BLACK, 10, kit.stroke)
        }
        fun refreshThumb() {
            val token = ++coverToken
            val at = p.coverMs.coerceIn(0L, max(0L, p.totalMs() - 1))
            val s = min(1f, 360f / min(ow, oh))
            val w = max(2, (ow * s).toInt() / 2 * 2)
            val h = max(2, (oh * s).toInt() / 2 * 2)
            val cl = p.clips.map { it.copy() }
            val ov = p.overlays.map { it.copy() }
            val fit = p.fitMode
            val txt = p.showText
            Thread {
                val bmp = runCatching { VeCover.render(a, at, w, h, cl, ov, fit, txt) }.getOrNull()
                handler.post { if (token == coverToken && bmp != null) img.setImageBitmap(bmp) }
            }.start()
        }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(img, LinearLayout.LayoutParams(pw, ph))
        val col = kit.vbox()
        col.setPadding(dp(14), 0, 0, 0)
        val timeText = kit.text("Sampul pada ${VeTime.fmt(p.coverMs, tenths = true)}", 13f, kit.ink, true)
        col.addView(timeText)
        col.addView(kit.note("Bingkai ini dipakai sebagai gambar sampul (thumbnail). Hasilnya mengikuti rasio, filter, dan teks proyek."))
        head.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(head)
        body.addView(gap(8))
        body.addView(live("Waktu sampul", 0f, max(1f, p.totalMs() / 1000f), p.coverMs / 1000f, { String.format(Locale.US, "%.1f dtk", it) }) { v ->
            p.coverMs = (v * 1000f).toLong()
            timeText.text = "Sampul pada ${VeTime.fmt(p.coverMs, tenths = true)}"
            handler.removeCallbacksAndMessages(coverDebounceToken)
            handler.postAtTime({ refreshThumb() }, coverDebounceToken, android.os.SystemClock.uptimeMillis() + 220L)
        })
        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(kit.button("Pakai posisi playhead", "map-marker", false) {
            ui.checkpoint()
            p.coverMs = p.playheadMs
            ui.save()
            cover()
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        btns.addView(kit.button("Simpan JPG sekarang", "content-save-outline", false) { saveCoverNow() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        body.addView(btns)
        body.addView(gap(4))
        body.addView(kit.switchRow("Simpan sampul (JPG) otomatis saat ekspor", p.coverOn) { on -> p.coverOn = on; ui.save() })
        ui.openPanel("Sampul", body) { coverToken++ }
        refreshThumb()
    }

    private val coverDebounceToken = Any()

    private fun saveCoverNow() {
        a.toast("Menyimpan sampul…")
        val (ow, oh) = VeCover.size()
        val at = p.coverMs
        val cl = p.clips.map { it.copy() }
        val ov = p.overlays.map { it.copy() }
        val fit = p.fitMode
        val txt = p.showText
        Thread {
            val bmp = runCatching { VeCover.render(a, at, ow, oh, cl, ov, fit, txt) }.getOrNull()
            val saved = if (bmp != null) runCatching { a.saveImageToGallery(bmp, p.name.ifBlank { "sampul" } + "_sampul", "jpg", 92) } else null
            handler.post {
                if (saved != null && saved.isSuccess) a.toast("Sampul disimpan: ${saved.getOrNull()?.label}")
                else a.toast("Gagal menyimpan sampul")
            }
        }.start()
    }

    // ================================================================== pustaka & keterangan

    fun library() {
        val body = kit.vbox()
        if (p.library.isEmpty()) {
            body.addView(kit.note("Pustaka kosong. Media yang Anda impor tersimpan di sini agar mudah dipakai ulang."))
        } else {
            val cells = p.library.map { m ->
                FrameLayout(a).apply {
                    background = kit.shape(kit.bg2, 10)
                    isClickable = true
                    val iv = ImageView(a).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        p.thumbs[m.uri.toString()]?.let { setImageBitmap(it) }
                    }
                    addView(iv, FrameLayout.LayoutParams(-1, -1))
                    addView(kit.text(if (m.isImage) "Foto" else VeTime.badge(m.durationMs), 9.5f, Color.WHITE, true).apply {
                        setPadding(dp(5), dp(2), dp(5), dp(2))
                        background = kit.shape(Color.argb(170, 0, 0, 0), 6)
                    }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(4); bottomMargin = dp(4) })
                    setOnClickListener { ui.addLibraryMedia(m); ui.closePanel() }
                    setOnLongClickListener {
                        a.themedDialog()
                            .setTitle("Hapus dari pustaka?")
                            .setMessage(m.name)
                            .setNegativeButton("Batal", null)
                            .setPositiveButton("Hapus") { _, _ -> p.library.remove(m); ui.save(); library() }
                            .show()
                        true
                    }
                } as View
            }
            body.addView(grid(cells, 4, 76))
            body.addView(kit.note("Ketuk untuk menambahkan ke timeline, tahan untuk menghapus dari pustaka."))
        }
        body.addView(kit.button("Tambah media dari perangkat", "plus", true) { ui.closePanel(); ui.pickMedia() }, LinearLayout.LayoutParams(-1, dp(44)))
        ui.openPanel("Pustaka media", body)
    }

    fun captionList() {
        val body = kit.vbox()
        val caps = p.overlays.filter { it.kind == "caption" }.sortedBy { it.startMs }
        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(kit.button("Tambah", "plus", true) { ui.closePanel(); ui.addCaption() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        btns.addView(kit.button("Impor SRT", "file-import-outline", false) { ui.closePanel(); ui.pickSrt() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        body.addView(btns)
        body.addView(gap(6))
        if (caps.isEmpty()) body.addView(kit.note("Belum ada keterangan. Tambahkan satu di posisi playhead, atau impor file .srt."))
        for (c in caps) {
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = kit.pressable(kit.bg2, 10)
                setPadding(dp(12), 0, dp(4), 0)
                isClickable = true
                setOnClickListener {
                    ui.closePanel()
                    ui.playback.pause()
                    ui.playback.seek(c.startMs)
                    ui.onPlayheadChanged()
                    ui.select("overlay", c.id)
                    textEditor(focus = false)
                }
            }
            row.addView(kit.text(VeTime.fmt(c.startMs, tenths = true), 11.5f, kit.muted, true).apply { typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(dp(58), -2))
            row.addView(kit.text(c.text.replace('\n', ' '), 13f, kit.ink).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10); bottomMargin = dp(10) })
            row.addView(kit.icon("delete-outline", 20f, kit.muted, "Hapus keterangan") {
                ui.checkpoint()
                p.overlays.remove(c)
                ui.afterChange(reload = false)
                captionList()
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        if (caps.isNotEmpty()) {
            body.addView(kit.button("Hapus semua keterangan", "delete-sweep-outline", false) {
                a.themedDialog()
                    .setTitle("Hapus semua keterangan?")
                    .setMessage("${caps.size} keterangan akan dihapus. Bisa di-undo.")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Hapus") { _, _ ->
                        ui.checkpoint()
                        p.overlays.removeAll { it.kind == "caption" }
                        ui.afterChange(reload = false)
                        captionList()
                    }
                    .show()
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        }
        ui.openPanel("Keterangan (${caps.size})", body)
    }

    // ================================================================== rekam suara

    fun voice() {
        val body = kit.vbox()
        val status = kit.text("Siap merekam dari ${VeTime.fmt(p.playheadMs, tenths = true)}", 13f, kit.ink, true)
        val level = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(kit.ink)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(kit.bg3)
        }
        val recBtn = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
        }
        fun paintBtn(rec: Boolean) {
            recBtn.removeAllViews()
            recBtn.background = kit.pressable(if (rec) kit.ink else kit.bg2, 26)
            val c = if (rec) kit.accentInk else kit.ink
            recBtn.addView(kit.icon(if (rec) "stop" else "microphone", 22f, c), LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(8) })
            recBtn.addView(kit.text(if (rec) "Berhenti & simpan" else "Mulai rekam", 14f, c, true))
        }
        paintBtn(recorder?.recording == true)
        recBtn.setOnClickListener {
            val rec = recorder
            if (rec != null && rec.recording) {
                finishVoice(true)
                ui.closePanel()
                return@setOnClickListener
            }
            if (a.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                a.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1245)
                a.toast("Izinkan mikrofon lalu ketuk Mulai rekam lagi")
                return@setOnClickListener
            }
            val r = VeVoiceRecorder(a)
            val err = r.start()
            if (err != null) { a.toast("Tidak bisa merekam: $err"); return@setOnClickListener }
            recorder = r
            voiceStartMs = if (p.playheadMs >= p.totalMs()) 0L else p.playheadMs
            voicePrevMuted = p.previewMuted
            p.previewMuted = true
            ui.playback.pause()
            ui.playback.seek(voiceStartMs)
            ui.playback.play()
            paintBtn(true)
            val ticker = object : Runnable {
                override fun run() {
                    val rr = recorder ?: return
                    if (!rr.recording) return
                    status.text = "Merekam… ${VeTime.fmt(rr.elapsedMs(), tenths = true)}"
                    level.progress = (rr.level() * 100f).roundToInt()
                    if (!ui.playback.playing) {
                        // Video selesai diputar: simpan rekaman otomatis.
                        finishVoice(true)
                        ui.closePanel()
                        return
                    }
                    handler.postDelayed(this, 120L)
                }
            }
            voiceTicker = ticker
            handler.post(ticker)
        }
        body.addView(recBtn, LinearLayout.LayoutParams(-1, dp(52)))
        body.addView(gap(8))
        body.addView(status)
        body.addView(level, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8) })
        body.addView(kit.note("Rekaman dimulai dari posisi playhead sambil memutar video (tanpa suara pratinjau agar tidak ikut terekam). Hasilnya menjadi item audio yang bisa digeser, dipangkas, dan diatur volumenya."))
        // Menutup panel saat merekam menyimpan rekaman (bukan membuangnya diam-diam).
        ui.openPanel("Rekam suara", body) { finishVoice(true) }
    }

    private fun finishVoice(save: Boolean) {
        voiceTicker?.let { handler.removeCallbacks(it) }
        voiceTicker = null
        val r = recorder ?: return
        recorder = null
        p.previewMuted = voicePrevMuted
        ui.playback.pause()
        ui.playback.applyVolume()
        if (!save) { r.cancel(); return }
        val result = r.stop()
        if (result == null) { a.toast("Rekaman terlalu singkat"); return }
        val (file, dur) = result
        ui.checkpoint()
        val item = VeAudioItem(uri = Uri.fromFile(file), name = "Rekaman ${VeTime.fmt(voiceStartMs)}", kind = "suara", startMs = voiceStartMs, sourceDurationMs = dur)
        p.audios.add(item)
        p.selKind = "audio"
        p.selId = item.id
        ui.afterChange(reload = false)
        a.toast("Rekaman ditambahkan (${VeTime.fmt(dur, tenths = true)})")
    }

    // ================================================================== narasi (teks ke suara) & latar warna

    private var tts: VeTts? = null
    private var ttsText = ""
    private var ttsLang = "id"
    private var ttsRate = 1f
    private var ttsPitch = 1f

    private fun ttsLocale(key: String): java.util.Locale = when (key) {
        "en" -> java.util.Locale.US
        "ms" -> java.util.Locale("ms", "MY")
        "ar" -> java.util.Locale("ar")
        "ja" -> java.util.Locale.JAPAN
        "ko" -> java.util.Locale.KOREA
        "zh" -> java.util.Locale.CHINA
        "es" -> java.util.Locale("es", "ES")
        "fr" -> java.util.Locale.FRANCE
        "de" -> java.util.Locale.GERMANY
        else -> java.util.Locale("id", "ID")
    }

    fun narration() {
        val body = kit.vbox()
        val input = kit.field("Ketik narasi yang akan dibacakan…", ttsText, 4)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, aft: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { ttsText = s?.toString() ?: "" }
        })
        body.addView(input, LinearLayout.LayoutParams(-1, -2))
        body.addView(kit.section("Bahasa suara"))
        body.addView(kit.chips(
            listOf("id" to "Indonesia", "en" to "English", "ms" to "Melayu", "ar" to "Arab", "ja" to "Jepang", "ko" to "Korea", "zh" to "Mandarin", "es" to "Spanyol", "fr" to "Prancis", "de" to "Jerman"),
            ttsLang
        ) { key -> ttsLang = key; narration() })
        body.addView(kit.slider("Kecepatan bicara", 0.5f, 2f, ttsRate, { String.format(Locale.US, "%.1f×", it) }, onChange = { ttsRate = it }))
        body.addView(kit.slider("Nada suara", 0.5f, 1.5f, ttsPitch, { String.format(Locale.US, "%.1f", it) }, onChange = { ttsPitch = it }))
        val status = kit.note("Menyiapkan mesin suara…")
        var btnView: View? = null
        val engine = tts ?: VeTts(a) { ok ->
            status.text = if (ok) "Mesin suara siap. Hasil menjadi item audio di playhead." else "Mesin teks-ke-ucapan tidak tersedia di perangkat ini."
            btnView?.alpha = if (ok) 1f else 0.4f
        }.also { tts = it }
        if (engine.isReady) status.text = "Mesin suara siap. Hasil menjadi item audio di playhead."
        val btn = kit.button("Buat narasi", "account-voice", true) {
            val text = ttsText.trim()
            when {
                !engine.isReady -> a.toast("Mesin suara belum siap")
                text.isEmpty() -> a.toast("Ketik teks narasi dulu")
                text.length > engine.maxChars() -> a.toast("Teks terlalu panjang (maks ${engine.maxChars()} karakter) — bagi menjadi beberapa narasi")
                !engine.setLanguage(ttsLocale(ttsLang)) -> a.themedDialog()
                    .setTitle("Suara belum terpasang")
                    .setMessage("Data suara untuk bahasa ini belum terpasang. Pasang lewat Pengaturan Android → Bahasa & input → Output teks-ke-ucapan, atau pilih bahasa lain.")
                    .setPositiveButton("Mengerti", null)
                    .show()
                else -> {
                    status.text = "Membuat suara…"
                    val dir = java.io.File(a.filesDir, "ve_media").apply { mkdirs() }
                    val out = java.io.File(dir, "tts_${System.currentTimeMillis()}.wav")
                    val start = if (p.playheadMs >= p.totalMs()) 0L else p.playheadMs
                    engine.synthesize(text, ttsRate, ttsPitch, out) { ok ->
                        if (!ok) {
                            out.delete()
                            status.text = "Gagal membuat suara. Coba bahasa lain atau perpendek teks."
                            return@synthesize
                        }
                        Thread {
                            val dur = VeImport.audioDuration(a, Uri.fromFile(out))
                            handler.post {
                                if (dur < 300L) { out.delete(); status.text = "Mesin suara menghasilkan audio kosong."; return@post }
                                ui.checkpoint()
                                val item = VeAudioItem(uri = Uri.fromFile(out), name = "Narasi", kind = "suara", startMs = start, sourceDurationMs = dur)
                                p.audios.add(item)
                                p.selKind = "audio"
                                p.selId = item.id
                                ui.afterChange(reload = false)
                                a.toast("Narasi ditambahkan (${VeTime.fmt(dur, tenths = true)})")
                                ui.closePanel()
                            }
                        }.start()
                    }
                }
            }
        }
        btnView = btn
        body.addView(btn, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        body.addView(status)
        ui.openPanel("Narasi teks-ke-suara", body, hideTimeline = true)
    }

    fun colorBackground() {
        val body = kit.vbox()
        body.addView(kit.note("Buat klip warna polos 3 detik — berguna sebagai latar teks, jeda, atau penutup."))
        body.addView(kit.colorSwatches(palette, Color.BLACK) { c -> ui.addColorClip(c); ui.closePanel() })
        ui.openPanel("Latar warna", body)
    }

    // ================================================================== auto-cut

    private var silenceLevel = "sedang"
    private var sceneLevel = "sedang"
    private var cutScope = "klip"

    fun autoCut() {
        val body = kit.vbox()
        val videos = p.clips.filter { !it.isImage }
        if (videos.isEmpty()) {
            body.addView(kit.note("Auto-cut membutuhkan klip video di timeline."))
            ui.openPanel("Auto-cut", body)
            return
        }
        body.addView(kit.section("Cakupan"))
        body.addView(kit.chips(listOf("klip" to "Klip terpilih", "semua" to "Semua klip video"), cutScope) { key -> cutScope = key; autoCut() })
        body.addView(kit.section("Hapus bagian hening"))
        body.addView(kit.chips(listOf("lembut" to "Lembut", "sedang" to "Sedang", "agresif" to "Agresif"), silenceLevel) { key -> silenceLevel = key; autoCut() })
        val progress = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(kit.ink)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(kit.bg3)
        }
        val status = kit.note(
            when (silenceLevel) {
                "lembut" -> "Memotong jeda ≥ 0,8 dtk yang benar-benar senyap. Cocok untuk wawancara & vlog."
                "agresif" -> "Memotong jeda ≥ 0,35 dtk termasuk suara latar pelan. Hasil lebih padat."
                else -> "Memotong jeda ≥ 0,5 dtk di bawah -40 dB. Pilihan seimbang."
            }
        )
        val run = kit.button("Analisis & hapus hening", "content-cut", true) {
            if (analysisBusy) { a.toast("Analisis sedang berjalan"); return@button }
            runSilenceCut(progress, status)
        }
        body.addView(run, LinearLayout.LayoutParams(-1, dp(46)))
        body.addView(kit.section("Bagi per adegan"))
        body.addView(kit.chips(listOf("rendah" to "Sedikit potongan", "sedang" to "Sedang", "tinggi" to "Banyak potongan"), sceneLevel) { key -> sceneLevel = key; autoCut() })
        val scene = kit.button("Deteksi adegan & bagi", "movie-roll", false) {
            if (analysisBusy) { a.toast("Analisis sedang berjalan"); return@button }
            runSceneSplit(progress, status)
        }
        body.addView(scene, LinearLayout.LayoutParams(-1, dp(46)))
        body.addView(gap(6))
        body.addView(progress, LinearLayout.LayoutParams(-1, dp(6)))
        body.addView(status)
        body.addView(kit.note("Analisis berjalan di perangkat. Hasil memotong klip menjadi beberapa bagian di timeline — bisa di-undo."))
        ui.openPanel("Auto-cut", body) { analysisCancelled = true }
    }

    private fun targetVideoClips(): List<VeClip> =
        if (cutScope == "semua") p.clips.filter { !it.isImage }
        else listOfNotNull(ui.targetClip()?.takeIf { !it.isImage })

    private fun runSilenceCut(progress: ProgressBar, status: TextView) {
        val clips = targetVideoClips()
        if (clips.isEmpty()) { a.toast("Pilih klip video dulu"); return }
        val (thr, minSil, pad) = when (silenceLevel) {
            "lembut" -> Triple(-50f, 800L, 120L)
            "agresif" -> Triple(-32f, 350L, 60L)
            else -> Triple(-40f, 500L, 90L)
        }
        analysisBusy = true
        analysisCancelled = false
        ui.playback.pause()
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Menganalisis suara…"
        val appCtx = a.applicationContext
        Thread {
            // id klip, trim saat dianalisis (awal, akhir), rentang yang dipertahankan (ms sumber relatif trimStart)
            val plans = ArrayList<Pair<Triple<String, Long, Long>, List<LongRange>>>()
            var removedMs = 0L
            var failed = 0
            for ((i, c) in clips.withIndex()) {
                if (analysisCancelled) break
                val len = c.effectiveEnd() - c.trimStartMs
                val db = VeAnalysis.rmsProfile(appCtx, c.uri, c.trimStartMs, c.effectiveEnd(), { analysisCancelled }) { pr ->
                    handler.post { progress.progress = ((i * 100 + pr) / clips.size) }
                }
                if (db == null) { if (!analysisCancelled) failed++; continue }
                val sil = VeSilence.silences(db, 50, thr, minSil, pad)
                if (sil.isEmpty()) continue
                val keep = VeSilence.keepRanges(len, sil)
                if (keep.isEmpty()) continue
                removedMs += sil.sumOf { it.last - it.first + 1 }
                plans.add(Triple(c.id, c.trimStartMs, c.trimEndMs) to keep)
            }
            handler.post {
                analysisBusy = false
                progress.visibility = View.GONE
                if (analysisCancelled) { return@post }
                if (plans.isEmpty()) {
                    status.text = if (failed > 0) "Suara klip tidak bisa dianalisis ($failed klip)." else "Tidak ada bagian hening yang ditemukan dengan pengaturan ini."
                    return@post
                }
                ui.checkpoint()
                var pieces = 0
                var skipped = 0
                for ((key, keep) in plans) {
                    val (id, t0, t1) = key
                    val idx = p.clips.indexOfFirst { it.id == id }
                    if (idx < 0) continue
                    val c = p.clips[idx]
                    // Klip dipangkas saat analisis berjalan: rentang hasil analisis tidak lagi cocok — lewati.
                    if (c.trimStartMs != t0 || c.trimEndMs != t1) { skipped++; continue }
                    val newClips = VeCutPlan.fromKeep(c.trimStartMs, keep).mapIndexed { n, piece ->
                        c.copy(
                            id = UUID.randomUUID().toString(),
                            trimStartMs = piece.trimStartMs,
                            trimEndMs = piece.trimEndMs,
                            transition = if (n == 0) c.transition else "none"
                        )
                    }
                    p.clips.removeAt(idx)
                    p.clips.addAll(idx, newClips)
                    pieces += newClips.size
                }
                p.clearSelection()
                p.playheadMs = 0L
                ui.afterChange()
                status.text = "Selesai: ${VeTime.fmt(removedMs, tenths = true)} hening dihapus, ${pieces} potongan dibuat." +
                    if (skipped > 0) " $skipped klip dilewati karena dipangkas saat analisis." else ""
                a.toast("Hening dihapus: ${VeTime.fmt(removedMs, tenths = true)}")
            }
        }.start()
    }

    private fun runSceneSplit(progress: ProgressBar, status: TextView) {
        val clips = targetVideoClips()
        if (clips.isEmpty()) { a.toast("Pilih klip video dulu"); return }
        val (threshold, minScene) = when (sceneLevel) {
            "rendah" -> 0.45f to 2500L
            "tinggi" -> 0.22f to 1200L
            else -> 0.32f to 1800L
        }
        analysisBusy = true
        analysisCancelled = false
        ui.playback.pause()
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Menganalisis adegan…"
        val appCtx = a.applicationContext
        Thread {
            val plans = ArrayList<Pair<String, List<Long>>>()
            var failed = 0
            for ((i, c) in clips.withIndex()) {
                if (analysisCancelled) break
                val cuts = VeAnalysis.sceneCuts(appCtx, c.uri, c.trimStartMs, c.effectiveEnd(), threshold, minScene, { analysisCancelled }) { pr ->
                    handler.post { progress.progress = ((i * 100 + pr) / clips.size) }
                }
                if (cuts == null) { if (!analysisCancelled) failed++; continue }
                if (cuts.isNotEmpty()) plans.add(c.id to cuts)
            }
            handler.post {
                analysisBusy = false
                progress.visibility = View.GONE
                if (analysisCancelled) return@post
                if (plans.isEmpty()) {
                    status.text = if (failed > 0) "Frame video tidak bisa dibaca ($failed klip)." else "Tidak ada pergantian adegan yang jelas. Coba pilih \"Banyak potongan\"."
                    return@post
                }
                ui.checkpoint()
                var pieces = 0
                for ((id, cuts) in plans) {
                    val idx = p.clips.indexOfFirst { it.id == id }
                    if (idx < 0) continue
                    val c = p.clips[idx]
                    val newClips = VeCutPlan.fromCuts(c.trimStartMs, c.effectiveEnd(), c.trimEndMs, cuts).mapIndexed { n, piece ->
                        c.copy(
                            id = UUID.randomUUID().toString(),
                            trimStartMs = piece.trimStartMs,
                            trimEndMs = piece.trimEndMs,
                            transition = if (n == 0) c.transition else "none"
                        )
                    }
                    p.clips.removeAt(idx)
                    p.clips.addAll(idx, newClips)
                    pieces += newClips.size
                }
                p.clearSelection()
                ui.afterChange()
                status.text = "Selesai: dibagi menjadi $pieces adegan."
                a.toast("Dibagi menjadi $pieces adegan")
            }
        }.start()
    }

    fun dispose() {
        tts?.shutdown()
        tts = null
        analysisCancelled = true
        voiceTicker?.let { handler.removeCallbacks(it) }
        voiceTicker = null
        if (recorder != null) p.previewMuted = voicePrevMuted
        recorder?.cancel()
        recorder = null
    }
}
