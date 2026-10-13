package com.example.aidetest

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.net.Uri
import android.os.Environment
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Auto Trace — Image to SVG (raster → vector), tata letak editor tanpa scroll:
 *  Top bar    : kembali · judul + status · bantuan · Simpan
 *  Stage      : pratinjau Asli / SVG (latar kotak-kotak), progres, antrean batch
 *  Preset     : kartu preset horizontal (AM Logo, AM Icon, AM Sticker, …)
 *  Pengaturan : segmen B&W | Color, slider ringkas satu baris, Invert — bisa dilipat
 *  Aksi bawah : Gambar · Batch · TRACE (utama) · Bagikan · Lainnya
 *
 * Semua proses lokal. SVG disimpan ke Pictures app.
 */

@Volatile
internal var autoTraceBatchPickPending: ((List<Uri>) -> Unit)? = null

internal data class TracePreset(
    val id: String,
    val label: String,
    val modeColor: Boolean,
    val threshold: Int,
    val colorCount: Int,
    val detail: Double,
    val maxSide: Int,
    val invert: Boolean = false
)

private val TRACE_PRESETS = listOf(
    TracePreset("am_logo_bw", "AM Logo B&W", false, 140, 2, 3.2, 768),
    TracePreset("am_icon_color", "AM Icon Color", true, 128, 6, 2.8, 768),
    TracePreset("am_sticker", "AM Sticker", true, 120, 8, 2.2, 896),
    TracePreset("anime_char", "Anime Character", true, 110, 10, 1.6, 1024),
    TracePreset("am_simple", "AM Simple (ringan)", false, 150, 2, 4.5, 640),
    TracePreset("detail_high", "Detail Tinggi", true, 128, 12, 1.0, 1024),
    TracePreset("custom", "Custom (slider)", false, 128, 8, 2.0, 1024)
)

private fun presetIcon(id: String): String = when (id) {
    "am_logo_bw" -> "vector-square"
    "am_icon_color" -> "palette-outline"
    "am_sticker" -> "image-outline"
    "anime_char" -> "face-woman-outline"
    "am_simple" -> "flash-outline"
    "detail_high" -> "magnify-plus-outline"
    else -> "tune-variant"
}

/** State Auto Trace bertahan saat halaman dibangun ulang (Back, buka tool lain). */
internal object AutoTraceState {
    var presetId = "am_icon_color"
    var modeColor = true
    var threshold = 128
    var colorCount = 6
    var detail = 2.8
    var maxSide = 768
    var invert = false
    var source: Bitmap? = null
    var sourceInfo = ""
    var lastSvg: String? = null
    var lastFile: File? = null
    val batchUris = mutableListOf<Uri>()
    @Volatile var batchCancel = false
    @Volatile var busy = false
    var showSvg = false
    var settingsOpen = true
}

internal fun MainActivity.autoTraceTool() {
    clearPage("Auto Trace")
    topBarVisibility(false)
    content.setPadding(0, 0, 0, 0)
    val ui = AutoTraceUi(this)
    content.addView(ui.root, LinearLayout.LayoutParams(-1, 0, 1f))
}

@SuppressLint("SetJavaScriptEnabled")
internal class AutoTraceUi(private val a: MainActivity) {
    private val c = EditorChrome(a)
    private val s = AutoTraceState

    val root = LinearLayout(a)
    private val statusText = TextView(a)
    private val stage = FrameLayout(a)
    private val original = ImageView(a)
    private val svgView = WebView(a)
    private val emptyState = LinearLayout(a)
    private val progressBox = LinearLayout(a)
    private val progressText = TextView(a)
    private val compareSeg = LinearLayout(a)
    private val batchChip = TextView(a)
    private val presetRow = LinearLayout(a)
    private val settingsBody = LinearLayout(a)
    private val settingsChevron = MdiIconView(a)
    private val settingsSummary = TextView(a)
    private val modeSeg = LinearLayout(a)
    private val traceBtn = LinearLayout(a)
    private val traceLabel = TextView(a)
    private val traceIcon = MdiIconView(a)
    private var thrRow: View? = null
    private var colorRow: View? = null
    private var thrBar: SeekBar? = null
    private var colorBar: SeekBar? = null
    private var detailBar: SeekBar? = null
    private var thrValue: TextView? = null
    private var colorValue: TextView? = null
    private var detailValue: TextView? = null
    private var invertSwitch: Switch? = null
    private var syncing = false

    private fun dp(v: Int) = a.dp(v)

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(if (c.dark) Color.rgb(10, 10, 12) else Color.rgb(244, 244, 246))
        root.addView(buildTopBar(), LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(buildStage(), LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(10), dp(8), dp(10), dp(8)) })
        root.addView(buildPresets(), LinearLayout.LayoutParams(-1, dp(70)))
        root.addView(buildSettings(), LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(6), dp(10), dp(6)) })
        root.addView(buildBottomBar(), LinearLayout.LayoutParams(-1, dp(72)))
        root.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
            val h = b - t
            // HP pendek: lipat pengaturan otomatis agar pratinjau tetap luas (sekali per ukuran).
            if (h != ob - ot && h > 0 && h < dp(620) && s.settingsOpen && s.source == null) {
                root.post { setSettingsOpen(false) }
            }
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { runCatching { svgView.destroy() } }
        })
        syncAll()
        restorePreview()
    }

    // ------------------------------------------------------------- small helpers

    private fun text(value: String, size: Float, color: Int = c.ink, bold: Boolean = false) = TextView(a).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun icon(name: String, size: Float, color: Int = c.ink, desc: String? = null, onClick: ((View) -> Unit)? = null) =
        MdiIconView(a).apply {
            setIconName(name)
            setIconSize(size)
            setTextColor(color)
            if (desc != null) contentDescription = desc
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                background = c.pressable(Color.TRANSPARENT, 12)
                setOnClickListener { v -> onClick(v) }
            }
        }

    private fun preset(): TracePreset = TRACE_PRESETS.firstOrNull { it.id == s.presetId } ?: TRACE_PRESETS.last()

    private fun detailName(d: Double) = when {
        d < 1.2 -> "Halus"
        d < 2.8 -> "Sedang"
        d < 5.0 -> "Sederhana"
        else -> "Minimal"
    }

    private fun setStatus(value: String) {
        statusText.text = value
    }

    // ------------------------------------------------------------- top bar

    private fun buildTopBar(): View {
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(8), 0)
            background = c.shape(c.surface, 0)
        }
        bar.addView(icon("arrow-left", 23f, c.ink, "Kembali") { a.navigateBack() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), 0, dp(4), 0) }
        titles.addView(text("Auto Trace", 15f, c.ink, true))
        statusText.apply {
            textSize = 11f
            setTextColor(c.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(3), 0, 0)
        }
        titles.addView(statusText)
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(icon("help-circle-outline", 21f, c.ink, "Bantuan") { showHelp() }, LinearLayout.LayoutParams(dp(40), dp(44)))
        bar.addView(TextView(a).apply {
            text = "Simpan"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(c.activeInk)
            setPadding(dp(14), 0, dp(14), 0)
            background = c.pressable(c.activeFill, 18)
            isClickable = true
            contentDescription = "Simpan SVG"
            setOnClickListener { saveSvg(share = false) }
        }, LinearLayout.LayoutParams(-2, dp(36)).apply { leftMargin = dp(4) })
        return bar
    }

    // ------------------------------------------------------------- stage

    private fun checkerDrawable(): ShapeDrawable {
        val cell = dp(10)
        val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val light = if (c.dark) Color.rgb(30, 30, 33) else Color.WHITE
        val darkCell = if (c.dark) Color.rgb(38, 38, 42) else Color.rgb(236, 236, 239)
        cv.drawColor(light)
        val paint = Paint().apply { color = darkCell }
        cv.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), paint)
        cv.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, paint)
        return ShapeDrawable(RectShape()).apply {
            this.paint.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }

    private fun buildStage(): View {
        val card = FrameLayout(a).apply {
            background = c.shape(c.surface, 20, c.stroke)
            setPadding(dp(1), dp(1), dp(1), dp(1))
            clipToOutline = true
        }
        stage.background = checkerDrawable()
        original.scaleType = ImageView.ScaleType.FIT_CENTER
        original.setPadding(dp(16), dp(52), dp(16), dp(16))
        stage.addView(original, FrameLayout.LayoutParams(-1, -1))
        svgView.apply {
            settings.javaScriptEnabled = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            setBackgroundColor(Color.TRANSPARENT)
            webViewClient = WebViewClient()
            visibility = View.GONE
        }
        stage.addView(svgView, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(44) })

        emptyState.orientation = LinearLayout.VERTICAL
        emptyState.gravity = Gravity.CENTER
        emptyState.addView(FrameLayout(a).apply {
            background = c.shape(c.surface2, 28, c.stroke)
            addView(icon("vector-polyline", 30f, c.ink), FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
        emptyState.addView(text("Ubah gambar menjadi SVG", 15f, c.ink, true).apply { setPadding(0, dp(14), 0, dp(4)) })
        emptyState.addView(text("Logo, ikon, dan ilustrasi flat kontras tinggi\nmemberi hasil terbaik.", 12f, c.muted).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) })
        emptyState.addView(TextView(a).apply {
            text = "Pilih gambar"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(c.activeInk)
            background = c.pressable(c.activeFill, 14)
            setPadding(dp(22), 0, dp(22), 0)
            isClickable = true
            setOnClickListener { pickSingle() }
        }, LinearLayout.LayoutParams(-2, dp(44)).apply { topMargin = dp(16) })
        stage.addView(emptyState, FrameLayout.LayoutParams(-1, -1))

        // Segmen Asli | SVG mengambang di atas pratinjau.
        compareSeg.orientation = LinearLayout.HORIZONTAL
        compareSeg.background = c.shape(c.surface, 18, c.stroke)
        compareSeg.setPadding(dp(3), dp(3), dp(3), dp(3))
        compareSeg.elevation = dp(2).toFloat()
        stage.addView(compareSeg, FrameLayout.LayoutParams(dp(176), dp(36), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })

        batchChip.apply {
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(c.ink)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = c.pressable(c.surface, 16, c.stroke)
            elevation = dp(2).toFloat()
            isClickable = true
            contentDescription = "Antrean batch, ketuk untuk mengosongkan"
            setOnClickListener {
                if (s.busy) return@setOnClickListener
                s.batchUris.clear()
                syncAll()
                a.toast("Antrean batch dikosongkan")
            }
        }
        stage.addView(batchChip, FrameLayout.LayoutParams(-2, dp(32), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(10); bottomMargin = dp(10) })

        progressBox.orientation = LinearLayout.VERTICAL
        progressBox.gravity = Gravity.CENTER
        progressBox.setBackgroundColor(Color.argb(if (c.dark) 150 else 120, if (c.dark) 0 else 255, if (c.dark) 0 else 255, if (c.dark) 0 else 255))
        progressBox.visibility = View.GONE
        progressBox.isClickable = true
        progressBox.addView(ProgressBar(a).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(40), dp(40)))
        progressText.apply { textSize = 13f; setTextColor(c.ink); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        progressBox.addView(progressText)
        stage.addView(progressBox, FrameLayout.LayoutParams(-1, -1))

        card.addView(stage, FrameLayout.LayoutParams(-1, -1))
        return card
    }

    private fun renderCompareSeg() {
        compareSeg.removeAllViews()
        val hasSvg = s.lastSvg != null
        for ((key, label) in listOf(false to "Asli", true to "SVG")) {
            val active = s.showSvg == key
            compareSeg.addView(TextView(a).apply {
                text = label
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(if (active) c.activeInk else if (key && !hasSvg) c.muted else c.ink)
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = if (active) c.shape(c.activeFill, 15) else null
                isClickable = true
                setOnClickListener {
                    if (key && !hasSvg) { a.toast("Jalankan Trace dulu"); return@setOnClickListener }
                    s.showSvg = key
                    restorePreview()
                }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        compareSeg.visibility = if (s.source != null) View.VISIBLE else View.GONE
    }

    private fun restorePreview() {
        val src = s.source
        emptyState.visibility = if (src == null) View.VISIBLE else View.GONE
        if (src != null) original.setImageBitmap(src)
        val svg = s.lastSvg
        if (s.showSvg && svg != null) {
            original.visibility = View.GONE
            svgView.visibility = View.VISIBLE
            loadSvg(svg)
        } else {
            original.visibility = if (src != null) View.VISIBLE else View.GONE
            svgView.visibility = View.GONE
        }
        renderCompareSeg()
    }

    private fun loadSvg(svg: String) {
        val html = """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1"/>
            <style>html,body{margin:0;height:100%;background:transparent;}
            body{display:flex;align-items:center;justify-content:center;padding:12px;box-sizing:border-box;}
            svg{max-width:100%;max-height:100%;width:auto;height:auto;}</style></head><body>$svg</body></html>
        """.trimIndent()
        svgView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun showProgress(textValue: String?) {
        if (textValue == null) {
            progressBox.visibility = View.GONE
        } else {
            progressText.text = textValue
            progressBox.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------- presets

    private fun buildPresets(): View {
        val scroll = HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(dp(10), 0, dp(10), 0)
        }
        presetRow.orientation = LinearLayout.HORIZONTAL
        presetRow.gravity = Gravity.CENTER_VERTICAL
        scroll.addView(presetRow, FrameLayout.LayoutParams(-2, -1))
        return scroll
    }

    private fun renderPresets() {
        presetRow.removeAllViews()
        for (p in TRACE_PRESETS) {
            val active = p.id == s.presetId
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(14), 0)
                background = c.pressable(if (active) c.activeFill else c.surface, 16, if (active) null else c.stroke)
                isClickable = true
                isFocusable = true
                contentDescription = "Preset ${p.label}"
                setOnClickListener { applyPreset(p) }
            }
            val fg = if (active) c.activeInk else c.ink
            val sub = if (active) Color.argb(170, Color.red(c.activeInk), Color.green(c.activeInk), Color.blue(c.activeInk)) else c.muted
            card.addView(FrameLayout(a).apply {
                background = c.shape(if (active) Color.argb(40, 128, 128, 128) else c.surface2, 10)
                addView(icon(presetIcon(p.id), 17f, fg), FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(8) })
            val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(p.label.replace(" (ringan)", "").replace(" (slider)", ""), 12.5f, fg, true).apply { maxLines = 1 })
            val desc = if (p.id == "custom") "Atur sendiri" else if (p.modeColor) "${p.colorCount} warna · ${detailName(p.detail)}" else "Hitam-putih · ${detailName(p.detail)}"
            texts.addView(text(desc, 10.5f, sub).apply { maxLines = 1; setPadding(0, dp(3), 0, 0) })
            card.addView(texts)
            presetRow.addView(card, LinearLayout.LayoutParams(-2, dp(52)).apply { rightMargin = dp(8) })
        }
    }

    private fun applyPreset(p: TracePreset) {
        s.presetId = p.id
        if (p.id != "custom") {
            s.modeColor = p.modeColor
            s.threshold = p.threshold
            s.colorCount = p.colorCount
            s.detail = p.detail
            s.maxSide = p.maxSide
            s.invert = p.invert
        }
        syncAll()
    }

    private fun markCustom() {
        if (s.presetId != "custom") {
            s.presetId = "custom"
            renderPresets()
        }
        updateSummary()
    }

    // ------------------------------------------------------------- settings card

    private fun buildSettings(): View {
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = c.shape(c.surface, 18, c.stroke)
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            contentDescription = "Pengaturan trace, ketuk untuk buka atau tutup"
            setOnClickListener { setSettingsOpen(!s.settingsOpen) }
        }
        head.addView(icon("tune-variant", 18f, c.ink), LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(6) })
        head.addView(text("Pengaturan", 13.5f, c.ink, true))
        settingsSummary.apply { textSize = 11.5f; setTextColor(c.muted); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.END }
        head.addView(settingsSummary, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10); rightMargin = dp(6) })
        settingsChevron.apply { setIconSize(20f); setTextColor(c.ink) }
        head.addView(settingsChevron, LinearLayout.LayoutParams(dp(28), dp(28)))
        card.addView(head, LinearLayout.LayoutParams(-1, dp(40)))

        settingsBody.orientation = LinearLayout.VERTICAL
        modeSeg.orientation = LinearLayout.HORIZONTAL
        modeSeg.background = c.shape(c.surface2, 12)
        modeSeg.setPadding(dp(3), dp(3), dp(3), dp(3))
        settingsBody.addView(modeSeg, LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(4) })

        fun sliderRow(label: String, max: Int, onChange: (Int) -> Unit): Triple<View, SeekBar, TextView> {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(label, 12.5f, c.ink), LinearLayout.LayoutParams(dp(78), -2))
            val bar = SeekBar(a).apply {
                this.max = max
                contentDescription = label
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) { if (!syncing) onChange(v); if (fromUser) markCustom() }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
            }
            row.addView(bar, LinearLayout.LayoutParams(0, dp(36), 1f))
            val value = text("", 12f, c.ink, true).apply {
                gravity = Gravity.CENTER
                background = c.shape(c.surface2, 8)
            }
            row.addView(value, LinearLayout.LayoutParams(dp(68), dp(26)))
            settingsBody.addView(row, LinearLayout.LayoutParams(-1, dp(40)))
            return Triple(row, bar, value)
        }
        sliderRow("Threshold", 250) { v -> s.threshold = v.coerceIn(10, 245); thrValue?.text = s.threshold.toString() }.let { (r, b, t) -> thrRow = r; thrBar = b; thrValue = t }
        sliderRow("Warna", 14) { v -> s.colorCount = (v + 2).coerceIn(2, 16); colorValue?.text = s.colorCount.toString() }.let { (r, b, t) -> colorRow = r; colorBar = b; colorValue = t }
        sliderRow("Detail", 40) { v -> s.detail = 0.4 + v * 0.19; detailValue?.text = detailName(s.detail) }.let { (_, b, t) -> detailBar = b; detailValue = t }

        val invRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        invRow.addView(text("Invert", 12.5f, c.ink), LinearLayout.LayoutParams(dp(78), -2))
        invRow.addView(text("Balik area gelap/terang", 11.5f, c.muted), LinearLayout.LayoutParams(0, -2, 1f))
        invertSwitch = Switch(a).apply {
            contentDescription = "Invert"
            setOnCheckedChangeListener { _, v -> if (!syncing) { s.invert = v; markCustom() } }
        }
        invRow.addView(invertSwitch)
        settingsBody.addView(invRow, LinearLayout.LayoutParams(-1, dp(40)))
        card.addView(settingsBody, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun renderModeSeg() {
        modeSeg.removeAllViews()
        for ((color, label) in listOf(false to "B&W", true to "Color")) {
            val active = s.modeColor == color
            modeSeg.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = if (active) c.shape(c.surface, 10, c.stroke) else null
                elevation = if (active) dp(1).toFloat() else 0f
                isClickable = true
                contentDescription = "Mode $label"
                setOnClickListener {
                    if (s.modeColor != color) {
                        s.modeColor = color
                        markCustom()
                        syncAll()
                    }
                }
                addView(icon(if (color) "palette-outline" else "circle-small", 16f, if (active) c.ink else c.muted), LinearLayout.LayoutParams(dp(22), dp(22)))
                addView(text(label, 12.5f, if (active) c.ink else c.muted, active))
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun setSettingsOpen(open: Boolean) {
        s.settingsOpen = open
        settingsBody.visibility = if (open) View.VISIBLE else View.GONE
        settingsChevron.setIconName(if (open) "chevron-down" else "chevron-up")
        if (open && a.animationsEnabled()) {
            settingsBody.alpha = 0f
            settingsBody.animate().alpha(1f).setDuration(150L).setInterpolator(DecelerateInterpolator()).start()
        }
        updateSummary()
    }

    private fun updateSummary() {
        settingsSummary.text = if (s.modeColor) "${preset().label.substringBefore(" (")} · Color ${s.colorCount} · ${detailName(s.detail)}"
        else "${preset().label.substringBefore(" (")} · B&W ${s.threshold} · ${detailName(s.detail)}"
    }

    private fun syncAll() {
        syncing = true
        renderPresets()
        renderModeSeg()
        thrRow?.visibility = if (s.modeColor) View.GONE else View.VISIBLE
        colorRow?.visibility = if (s.modeColor) View.VISIBLE else View.GONE
        thrBar?.progress = s.threshold
        thrValue?.text = s.threshold.toString()
        colorBar?.progress = (s.colorCount - 2).coerceIn(0, 14)
        colorValue?.text = s.colorCount.toString()
        detailBar?.progress = ((s.detail - 0.4) / 0.19).roundToInt().coerceIn(0, 40)
        detailValue?.text = detailName(s.detail)
        invertSwitch?.isChecked = s.invert
        syncing = false
        setSettingsOpen(s.settingsOpen)
        val batch = s.batchUris.size
        batchChip.text = "Batch · $batch file   ×"
        batchChip.visibility = if (batch > 0) View.VISIBLE else View.GONE
        traceLabel.text = when {
            s.busy -> "Batalkan"
            batch > 0 -> "Trace $batch file"
            else -> "Trace"
        }
        traceIcon.setIconName(if (s.busy) "stop" else "play")
        setStatus(when {
            s.busy -> "Memproses…"
            s.sourceInfo.isNotBlank() -> s.sourceInfo
            else -> "Raster → SVG · lokal & offline"
        })
        renderCompareSeg()
    }

    // ------------------------------------------------------------- bottom actions

    private fun buildBottomBar(): View {
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            background = c.shape(c.surface, 0, c.stroke)
        }
        fun side(iconName: String, label: String, onClick: (View) -> Unit): View {
            val cell = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = label
                background = c.pressable(Color.TRANSPARENT, 12)
                setOnClickListener { v -> onClick(v) }
            }
            cell.addView(icon(iconName, 21f, c.ink), LinearLayout.LayoutParams(-1, dp(24)))
            cell.addView(text(label, 10.5f, c.ink).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
            return cell
        }
        bar.addView(side("image-plus", "Gambar") { pickSingle() }, LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(side("image-multiple-outline", "Batch") { pickBatch() }, LinearLayout.LayoutParams(0, -1, 1f))
        traceBtn.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = c.pressable(c.activeFill, 16)
            isClickable = true
            isFocusable = true
            contentDescription = "Jalankan trace"
            setOnClickListener { onTrace() }
        }
        traceIcon.apply { setIconSize(20f); setTextColor(c.activeInk) }
        traceBtn.addView(traceIcon, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(4) })
        traceLabel.apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c.activeInk); maxLines = 1 }
        traceBtn.addView(traceLabel)
        bar.addView(traceBtn, LinearLayout.LayoutParams(0, dp(52), 1.8f).apply { leftMargin = dp(6); rightMargin = dp(6) })
        bar.addView(side("share-variant", "Bagikan") { saveSvg(share = true) }, LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(side("dots-vertical", "Lainnya") { v -> moreMenu(v) }, LinearLayout.LayoutParams(0, -1, 1f))
        return bar
    }

    private fun moreMenu(anchor: View) {
        val pm = PopupMenu(a, anchor)
        pm.menu.add(0, 1, 0, if (s.settingsOpen) "Lipat pengaturan" else "Buka pengaturan")
        pm.menu.add(0, 2, 1, "Kosongkan antrean batch")
        pm.menu.add(0, 3, 2, "Reset ke preset AM Icon Color")
        pm.menu.add(0, 5, 3, "Kirim ke Potong Manual")
        pm.menu.add(0, 4, 4, "Bantuan")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> setSettingsOpen(!s.settingsOpen)
                2 -> if (!s.busy) { s.batchUris.clear(); syncAll() }
                3 -> applyPreset(TRACE_PRESETS.first { it.id == "am_icon_color" })
                5 -> sendToPotong()
                4 -> showHelp()
            }
            true
        }
        pm.show()
    }

    /** Buka Foto Studio → tab Potong dengan gambar sumber saat ini (hybrid auto → manual). */
    private fun sendToPotong() {
        val bmp = s.source
        if (bmp == null) { a.toast("Pilih gambar dulu"); return }
        if (s.busy) { a.toast("Tunggu proses selesai"); return }
        // Simpan sementara ke cache supaya Cutout bisa memuat
        try {
            val dir = File(a.cacheDir, "autotrace_handoff").apply { mkdirs() }
            val f = File(dir, "source_${System.currentTimeMillis()}.png")
            java.io.FileOutputStream(f).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            a.getSharedPreferences("gitls_handoff", 0).edit()
                .putString("cutout_source_path", f.absolutePath)
                .apply()
            a.toast("Membuka Potong Manual…")
            // Buka Creative Studio / Foto Studio (yang berisi tab Potong)
            if (a.homeToolMap.containsKey("creativestudio") || a.homeToolMap.containsKey("imagestudio")) {
                val id = if (a.homeToolMap.containsKey("creativestudio")) "creativestudio" else "imagestudio"
                a.openTool(id)
            } else {
                a.toast("Foto Studio belum tersedia")
            }
        } catch (e: Exception) {
            a.toast("Gagal kirim: ${e.message}")
        }
    }

    private fun showHelp() {
        a.themedDialog()
            .setTitle("Auto Trace — Image to SVG")
            .setMessage(
                "Mengubah gambar raster menjadi SVG vector (lokal).\n\n" +
                    "• Gambar: pilih 1 gambar · Batch: pilih banyak gambar sekaligus\n" +
                    "• Preset Alight Motion + Anime Character (baru)\n" +
                    "• Mode B&W (threshold) & Color (median-cut)\n" +
                    "• Asli / SVG: bandingkan hasil di pratinjau\n" +
                    "• Simpan / Bagikan: file .svg di Pictures app\n" +
                    "• Kirim ke Potong Manual: lanjutkan editing manual di tab Potong\n\n" +
                    "Hybrid workflow: Auto Trace → refine di Potong Manual → Kirim ke Desain."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ------------------------------------------------------------- actions

    private fun pickSingle() {
        if (s.busy) { a.toast("Tunggu proses selesai"); return }
        a.pickImageForTool(1224) { uri ->
            if (uri == null) return@pickImageForTool
            a.persistUriPermissionsSafely(uri, wantWrite = false)
            s.batchUris.clear()
            showProgress("Memuat gambar…")
            Thread {
                try {
                    val bmp = a.decodeBitmapFromUri(uri, maxSide = s.maxSide)
                    a.runOnUiThread {
                        s.source?.takeIf { it !== bmp }?.recycle()
                        s.source = bmp
                        s.lastSvg = null
                        s.lastFile = null
                        s.showSvg = false
                        s.sourceInfo = "${bmp.width}×${bmp.height} px"
                        showProgress(null)
                        syncAll()
                        restorePreview()
                    }
                } catch (e: Exception) {
                    a.runOnUiThread { showProgress(null); a.toast("Gagal memuat: ${e.message}") }
                }
            }.start()
        }
    }

    private fun pickBatch() {
        if (s.busy) { a.toast("Tunggu proses selesai"); return }
        autoTraceBatchPickPending = { uris ->
            val images = uris.filter { u ->
                val t = a.contentResolver.getType(u)?.lowercase(Locale.US).orEmpty()
                t.startsWith("image/") || u.toString().lowercase(Locale.US).let {
                    it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp") || it.endsWith(".bmp")
                }
            }
            s.batchUris.clear()
            s.batchUris.addAll(images)
            if (images.isEmpty()) a.toast("Tidak ada file gambar valid")
            else {
                Thread {
                    val bmp = runCatching { a.decodeBitmapFromUri(images.first(), maxSide = s.maxSide) }.getOrNull()
                    a.runOnUiThread {
                        if (bmp != null) {
                            s.source?.takeIf { it !== bmp }?.recycle()
                            s.source = bmp
                            s.lastSvg = null
                            s.showSvg = false
                            s.sourceInfo = "Batch ${images.size} file · pratinjau item 1"
                        }
                        syncAll()
                        restorePreview()
                    }
                }.start()
            }
            syncAll()
        }
        @Suppress("DEPRECATION")
        a.startActivityForResult(
            android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
            },
            1225
        )
    }

    private fun onTrace() {
        if (s.busy) {
            s.batchCancel = true
            a.toast("Berhenti setelah item saat ini")
            return
        }
        if (s.batchUris.isNotEmpty()) runBatch() else runSingle()
    }

    private fun runSingle() {
        val src = s.source ?: run { a.toast("Pilih gambar dulu"); pickSingle(); return }
        val useColor = s.modeColor
        val thr = s.threshold
        val colors = s.colorCount
        val eps = s.detail
        val inv = s.invert
        s.busy = true
        syncAll()
        showProgress("Tracing…")
        Thread {
            val t0 = System.currentTimeMillis()
            val result = runCatching {
                if (useColor) AutoTracer.traceColor(src, colors, eps, inv) else AutoTracer.traceBw(src, thr, eps, inv)
            }
            val ms = System.currentTimeMillis() - t0
            a.runOnUiThread {
                s.busy = false
                showProgress(null)
                result.onSuccess { svg ->
                    s.lastSvg = svg
                    s.lastFile = null
                    s.showSvg = true
                    s.sourceInfo = "${src.width}×${src.height} · selesai ${ms} ms · ${svg.length / 1024} KB"
                }.onFailure { e -> a.toast("Gagal: ${e.message}") }
                if (root.isAttachedToWindow) { syncAll(); restorePreview() }
            }
        }.start()
    }

    private fun runBatch() {
        val uris = s.batchUris.toList()
        val useColor = s.modeColor
        val thr = s.threshold
        val colors = s.colorCount
        val eps = s.detail
        val inv = s.invert
        val side = s.maxSide
        s.batchCancel = false
        a.ensureLegacyStorageAccess() // OPEN-11
        s.busy = true
        syncAll()
        showProgress("Batch 0/${uris.size}…")
        Thread {
            val outDir = a.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: a.filesDir
            if (!outDir.exists()) outDir.mkdirs()
            var ok = 0
            var fail = 0
            for ((idx, uri) in uris.withIndex()) {
                if (s.batchCancel) break
                a.runOnUiThread { if (root.isAttachedToWindow) showProgress("Batch ${idx + 1}/${uris.size}…") }
                try {
                    val bmp = a.decodeBitmapFromUri(uri, maxSide = side)
                    val svg = if (useColor) AutoTracer.traceColor(bmp, colors, eps, inv) else AutoTracer.traceBw(bmp, thr, eps, inv)
                    val name = a.queryName(uri)?.substringBeforeLast('.')?.take(30) ?: "trace_${idx + 1}"
                    val safe = name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").ifBlank { "trace" }
                    val file = File(outDir, "batch_${safe}_${System.currentTimeMillis()}.svg")
                    file.writeText(svg)
                    runCatching { VePublish.copy(a, file) }
                    ok++
                    if (idx == 0) {
                        a.runOnUiThread {
                            s.source?.takeIf { it !== bmp }?.recycle()
                            s.source = bmp
                            s.lastSvg = svg
                            s.lastFile = file
                            s.showSvg = true
                        }
                    } else bmp.recycle()
                } catch (e: Exception) {
                    fail++
                    android.util.Log.w("GITLS", "batch trace fail: ${e.message}")
                }
            }
            a.runOnUiThread {
                s.busy = false
                s.batchUris.clear()
                s.sourceInfo = "Batch selesai · $ok SVG tersimpan${if (fail > 0) " · $fail gagal" else ""}"
                a.toast("Batch: $ok berhasil, $fail gagal" + if (ok > 0 && android.os.Build.VERSION.SDK_INT >= 29) " — Documents/GITLS" else "")
                if (root.isAttachedToWindow) { showProgress(null); syncAll(); restorePreview() }
            }
        }.start()
    }

    private fun saveSvg(share: Boolean) {
        val existing = s.lastFile
        if (existing != null && existing.exists()) {
            if (share) a.shareSvgFile(existing) else a.toast("Sudah tersimpan: ${existing.name}")
            return
        }
        val svg = s.lastSvg ?: run { a.toast("Jalankan Trace dulu"); return }
        a.ensureLegacyStorageAccess() // OPEN-11
        Thread {
            try {
                val dir = a.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: a.filesDir
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "autotrace_${System.currentTimeMillis()}.svg")
                file.writeText(svg)
                val published = runCatching { VePublish.copy(a, file) }.getOrNull()
                a.runOnUiThread {
                    s.lastFile = file
                    s.sourceInfo = "Tersimpan: ${published?.label ?: file.name} · ${a.formatImageBytes(file.length())}"
                    if (root.isAttachedToWindow) syncAll()
                    if (share) a.shareSvgFile(file) else a.toast("SVG disimpan: ${file.name}")
                }
            } catch (e: Exception) {
                a.runOnUiThread { a.toast("Gagal simpan: ${e.message}") }
            }
        }.start()
    }
}

internal fun MainActivity.shareSvgFile(file: File) {
    try {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/svg+xml"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "Bagikan SVG"))
    } catch (e: Exception) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, file.readText().take(50_000))
                putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
            }
            startActivity(android.content.Intent.createChooser(intent, "Bagikan SVG"))
        } catch (e2: Exception) {
            toast("Gagal bagikan: ${e2.message}")
        }
    }
}

// ---------------------------------------------------------------------------
// Core tracer — improved median-cut quantization + contour
// ---------------------------------------------------------------------------

internal object AutoTracer {

    data class Point(val x: Float, val y: Float)
    data class SvgLayer(val fill: String, val paths: List<List<Point>>)

    fun traceBw(src: Bitmap, threshold: Int, epsilon: Double, invert: Boolean): String {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val mask = BooleanArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val y = ((0.299 * ((c shr 16) and 0xFF) +
                0.587 * ((c shr 8) and 0xFF) +
                0.114 * (c and 0xFF))).roundToInt()
            val v = if (invert) 255 - y else y
            mask[i] = v < threshold
        }
        val paths = extractContours(mask, w, h, epsilon)
        return buildSvg(w, h, listOf(SvgLayer("#000000", paths)))
    }

    fun traceColor(src: Bitmap, colorCount: Int, epsilon: Double, invert: Boolean): String {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val k = colorCount.coerceIn(2, 16)
        val palette = medianCutQuantize(pixels, k)

        val layers = mutableListOf<SvgLayer>()
        val ordered = palette.sortedBy { luminance(it) }

        for (color in ordered) {
            val lum = luminance(color)
            // Skip near-white background when enough other colors exist
            if (lum > 248 && ordered.size > 2) continue

            val mask = BooleanArray(w * h)
            var count = 0
            for (i in pixels.indices) {
                val match = nearestColor(pixels[i], palette) == color
                mask[i] = if (invert) !match else match
                if (mask[i]) count++
            }
            if (count < (w * h) / 800) continue

            val paths = extractContours(mask, w, h, epsilon)
            if (paths.isEmpty()) continue
            layers.add(SvgLayer(toHex(color), paths))
        }

        if (layers.isEmpty()) return traceBw(src, 128, epsilon, invert)
        return buildSvg(w, h, layers)
    }

    // ---- Median-cut quantization (improved) ----

    private data class Box(
        val indices: IntArray,
        var rMin: Int, var rMax: Int,
        var gMin: Int, var gMax: Int,
        var bMin: Int, var bMax: Int
    ) {
        fun longestChannel(): Int {
            val rRange = rMax - rMin
            val gRange = gMax - gMin
            val bRange = bMax - bMin
            return when {
                rRange >= gRange && rRange >= bRange -> 0
                gRange >= rRange && gRange >= bRange -> 1
                else -> 2
            }
        }
    }

    /**
     * Median-cut on sampled pixels, then refine by assigning full image to nearest
     * and averaging (1 iteration) for more stable palette.
     */
    private fun medianCutQuantize(pixels: IntArray, k: Int): List<Int> {
        val step = max(1, pixels.size / 16_000)
        val samples = ArrayList<Int>(pixels.size / step + 1)
        var i = 0
        while (i < pixels.size) {
            samples.add(pixels[i] and 0x00FFFFFF)
            i += step
        }
        if (samples.isEmpty()) return listOf(Color.BLACK, Color.WHITE)

        val boxes = ArrayList<Box>()
        val initIdx = IntArray(samples.size) { it }
        boxes.add(makeBox(samples, initIdx))

        while (boxes.size < k) {
            var splitAt = -1
            var bestRange = -1
            for (bi in boxes.indices) {
                val b = boxes[bi]
                if (b.indices.size < 2) continue
                val range = max(b.rMax - b.rMin, max(b.gMax - b.gMin, b.bMax - b.bMin))
                if (range > bestRange) {
                    bestRange = range
                    splitAt = bi
                }
            }
            if (splitAt < 0 || bestRange <= 0) break
            val box = boxes.removeAt(splitAt)
            val (left, right) = splitBox(samples, box)
            boxes.add(left)
            boxes.add(right)
        }

        // Average color per box
        var palette = boxes.map { boxAverage(samples, it) }.distinct()

        // Refine: re-assign a sample of full pixels and re-average (1 pass)
        if (palette.size >= 2) {
            val sums = Array(palette.size) { LongArray(4) } // r,g,b,n
            i = 0
            val refineStep = max(1, pixels.size / 24_000)
            while (i < pixels.size) {
                val c = pixels[i]
                val ni = nearestColorIndex(c, palette)
                sums[ni][0] += ((c shr 16) and 0xFF).toLong()
                sums[ni][1] += ((c shr 8) and 0xFF).toLong()
                sums[ni][2] += (c and 0xFF).toLong()
                sums[ni][3]++
                i += refineStep
            }
            val refined = ArrayList<Int>(palette.size)
            for (s in sums) {
                if (s[3] == 0L) continue
                val r = (s[0] / s[3]).toInt().coerceIn(0, 255)
                val g = (s[1] / s[3]).toInt().coerceIn(0, 255)
                val b = (s[2] / s[3]).toInt().coerceIn(0, 255)
                refined.add((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
            if (refined.isNotEmpty()) palette = refined
        }

        while (palette.size < k) {
            palette = palette + Color.rgb(
                (palette.size * 41) % 256,
                (palette.size * 73) % 256,
                (palette.size * 17) % 256
            )
        }
        return palette.take(k)
    }

    private fun makeBox(samples: List<Int>, indices: IntArray): Box {
        var rMin = 255; var rMax = 0
        var gMin = 255; var gMax = 0
        var bMin = 255; var bMax = 0
        for (idx in indices) {
            val c = samples[idx]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r < rMin) rMin = r; if (r > rMax) rMax = r
            if (g < gMin) gMin = g; if (g > gMax) gMax = g
            if (b < bMin) bMin = b; if (b > bMax) bMax = b
        }
        return Box(indices, rMin, rMax, gMin, gMax, bMin, bMax)
    }

    private fun splitBox(samples: List<Int>, box: Box): Pair<Box, Box> {
        val ch = box.longestChannel()
        val sorted = box.indices.sortedBy { idx ->
            val c = samples[idx]
            when (ch) {
                0 -> (c shr 16) and 0xFF
                1 -> (c shr 8) and 0xFF
                else -> c and 0xFF
            }
        }
        val mid = sorted.size / 2
        val left = sorted.subList(0, mid).toIntArray()
        val right = sorted.subList(mid, sorted.size).toIntArray()
        return makeBox(samples, left) to makeBox(samples, right)
    }

    private fun boxAverage(samples: List<Int>, box: Box): Int {
        if (box.indices.isEmpty()) return Color.GRAY
        var r = 0L; var g = 0L; var b = 0L
        for (idx in box.indices) {
            val c = samples[idx]
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
        }
        val n = box.indices.size
        return (0xFF shl 24) or
            (((r / n).toInt()) shl 16) or
            (((g / n).toInt()) shl 8) or
            (b / n).toInt()
    }

    private fun nearestColor(pixel: Int, palette: List<Int>): Int {
        return palette[nearestColorIndex(pixel, palette)]
    }

    private fun nearestColorIndex(pixel: Int, palette: List<Int>): Int {
        var best = 0
        var bestD = Int.MAX_VALUE
        val pr = (pixel shr 16) and 0xFF
        val pg = (pixel shr 8) and 0xFF
        val pb = pixel and 0xFF
        for (i in palette.indices) {
            val c = palette[i]
            val dr = pr - ((c shr 16) and 0xFF)
            val dg = pg - ((c shr 8) and 0xFF)
            val db = pb - (c and 0xFF)
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun luminance(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).roundToInt()
    }

    private fun toHex(c: Int): String {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return String.format(Locale.US, "#%02X%02X%02X", r, g, b)
    }

    // ---- Contours ----

    private fun extractContours(
        mask: BooleanArray,
        w: Int,
        h: Int,
        epsilon: Double
    ): List<List<Point>> {
        val visited = BooleanArray(w * h)
        val contours = ArrayList<List<Point>>()
        val dx = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val dy = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

        fun idx(x: Int, y: Int) = y * w + x
        fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h && mask[idx(x, y)]

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = idx(x, y)
                if (!mask[i] || visited[i]) continue
                var isBoundary = false
                for (k in 0 until 8) {
                    if (!inside(x + dx[k], y + dy[k])) {
                        isBoundary = true
                        break
                    }
                }
                if (!isBoundary) {
                    visited[i] = true
                    continue
                }
                val path = followBorder(x, y, w, h, mask, visited, dx, dy)
                if (path.size >= 4) {
                    val simplified = douglasPeucker(path, epsilon)
                    if (simplified.size >= 3) contours.add(simplified)
                }
            }
        }
        return contours
    }

    private fun followBorder(
        startX: Int,
        startY: Int,
        w: Int,
        h: Int,
        mask: BooleanArray,
        visited: BooleanArray,
        dx: IntArray,
        dy: IntArray
    ): List<Point> {
        val path = ArrayList<Point>(128)
        var x = startX
        var y = startY
        var dir = 0
        val maxSteps = w * h * 2
        var steps = 0

        fun idx(px: Int, py: Int) = py * w + px
        fun isFg(px: Int, py: Int) =
            px in 0 until w && py in 0 until h && mask[idx(px, py)]

        do {
            path.add(Point(x.toFloat(), y.toFloat()))
            visited[idx(x, y)] = true
            var found = false
            for (t in 0 until 8) {
                val nd = (dir + 6 + t) % 8
                val nx = x + dx[nd]
                val ny = y + dy[nd]
                if (isFg(nx, ny)) {
                    x = nx
                    y = ny
                    dir = nd
                    found = true
                    break
                }
            }
            if (!found) break
            steps++
        } while ((x != startX || y != startY) && steps < maxSteps)

        return path
    }

    private fun douglasPeucker(points: List<Point>, epsilon: Double): List<Point> {
        if (points.size < 3) return points
        var maxDist = 0.0
        var index = 0
        val end = points.size - 1
        for (i in 1 until end) {
            val d = perpDist(points[i], points[0], points[end])
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }
        return if (maxDist > epsilon) {
            val left = douglasPeucker(points.subList(0, index + 1), epsilon)
            val right = douglasPeucker(points.subList(index, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(points.first(), points.last())
        }
    }

    private fun perpDist(p: Point, a: Point, b: Point): Double {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        if (dx == 0.0 && dy == 0.0) return hypot((p.x - a.x).toDouble(), (p.y - a.y).toDouble())
        val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        return hypot(p.x - projX, p.y - projY)
    }

    private fun buildSvg(w: Int, h: Int, layers: List<SvgLayer>): String {
        val sb = StringBuilder(layers.sumOf { it.paths.size } * 80 + 240)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append('\n')
        // Alight Motion-friendly: explicit viewBox, evenodd, no external refs
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h" fill-rule="evenodd">""")
        sb.append('\n')
        for (layer in layers) {
            for (path in layer.paths) {
                if (path.size < 3) continue
                sb.append("""<path fill="${layer.fill}" d="""")
                sb.append('M')
                sb.append(fmt(path[0].x))
                sb.append(' ')
                sb.append(fmt(path[0].y))
                for (i in 1 until path.size) {
                    sb.append('L')
                    sb.append(fmt(path[i].x))
                    sb.append(' ')
                    sb.append(fmt(path[i].y))
                }
                sb.append("Z\"/>\n")
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun fmt(v: Float): String {
        val r = (v * 10).roundToInt() / 10.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }
}
