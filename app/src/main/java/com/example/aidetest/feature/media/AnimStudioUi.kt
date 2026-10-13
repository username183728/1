package com.example.aidetest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** ScrollView dengan tinggi maksimum (laci panel editor). */
internal class EdMaxScroll(ctx: Context) : ScrollView(ctx) {
    var maxHeightPx = 0
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeightPx > 0) View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, spec)
    }
}

/**
 * Animation Studio — animasikan desain Foto Studio:
 *  - Sumber: desain Foto Studio saat ini, proyek Studio tersimpan, atau foto (satu layer)
 *  - Keyframe per layer: geser X/Y, putar, skala, opasitas + easing (linear / in / out / in-out)
 *  - Timeline multi-layer: satu baris per layer (keyframe bisa diseret, snap 50 ms), rentang aktif in/out,
 *    sembunyi/kunci per track, zoom (cubit / tombol) + gulir; putar/jeda 30 fps (Choreographer)
 *  - Animasi teks per huruf (ketik, fade/pop, pantul, gelombang, tracking, jalur, judul, subjudul)
 *  - Seret layer di kanvas = keyframe posisi di playhead; preset masuk (fade, geser, pop, zoom, putar)
 *  - Undo/redo, simpan otomatis + proyek (files/design/anim), ekspor MP4 H.264 / GIF + Kirim ke Video Editor
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
internal class AnimStudioUi(private val a: MainActivity) {
    private val kit = ImgKit(a)
    private val c = kit.c
    private val p = AnProject
    private val handler = Handler(Looper.getMainLooper())
    private val frameTol = 1000L / AnProject.FPS / 2 + 1

    val root = FrameLayout(a)
    private val column = LinearLayout(a)
    private val topBar = LinearLayout(a)
    private val titleView = TextView(a)
    private val subtitleView = TextView(a)
    private val undoBtn: MdiIconView
    private val redoBtn: MdiIconView
    private val stageFrame = FrameLayout(a)
    private val stage = AnStageView(a, c)
    private val transport = LinearLayout(a)
    private val playBtn: MdiIconView
    private val timeText = TextView(a)
    private val timeline: AnTracksView
    private val panelHost = LinearLayout(a)
    private val panelTitle = TextView(a)
    private val panelScroll = EdMaxScroll(a)
    private val panelBody = LinearLayout(a)
    private val dock = LinearLayout(a)
    private val dockCells = LinkedHashMap<String, LinearLayout>()
    private var panelKey: String? = null
    private var overlay: View? = null
    private var progressView: View? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var alive = true
    private var playing = false
    private var playStartNanos = 0L
    private var playStartMs = 0L
    private var dragBase: AnState? = null
    private var presetMs = 700L
    private var presetAll = false
    private var graphView: AnGraphView? = null
    private var effSel = 0
    private val images = AnPreviewImages { if (alive) stage.invalidate() }

    private val panelBack: () -> Boolean = { if (panelKey != null && root.isAttachedToWindow) { closePanel(); true } else false }
    private val overlayBack: () -> Boolean = {
        if (overlay != null && root.isAttachedToWindow && p.doc != null) { dismissOverlay(); true } else false
    }
    private val autosaveTask = Runnable { AnStore.autosave(a) }
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!playing || !alive) return
            var t = playStartMs + (frameTimeNanos - playStartNanos) / 1_000_000L
            if (t > p.durationMs) {
                // Ulang dari awal (loop pratinjau).
                playStartMs = 0L
                playStartNanos = frameTimeNanos
                t = 0L
            }
            val snapped = AnMath.snapToFrame(t, AnProject.FPS).coerceAtMost(p.durationMs)
            if (snapped != p.playheadMs) {
                p.playheadMs = snapped
                refreshTime()
                stage.invalidate()
                timeline.invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun dp(v: Int): Int = a.dp(v)

    init {
        CsPipeline.init(a)
        AnStore.ensureLoaded(a)
        root.setBackgroundColor(stage.workspace)
        column.orientation = LinearLayout.VERTICAL
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        undoBtn = kit.icon("undo", 22f, c.ink, "Undo") { undo() }
        redoBtn = kit.icon("redo", 22f, c.ink, "Redo") { redo() }
        buildTopBar()
        column.addView(topBar, LinearLayout.LayoutParams(-1, dp(52)))

        stage.images = images
        stage.state = { el -> AnMath.sample(p.tracks[el.id] ?: emptyList(), p.playheadMs) }
        stage.timeMs = { p.playheadMs }
        stage.stateAt = { el, t -> AnMath.sample(p.tracks[el.id] ?: emptyList(), t) }
        stage.opts = p.opts
        stage.onSelect = { id -> p.selectedId = id; onSelectionChanged() }
        stage.onDragStart = { id -> beginDrag(id) }
        stage.onDrag = { id, dx, dy -> dragTo(id, dx, dy) }
        stage.onDragEnd = { _ -> dragBase = null; changed() }
        stageFrame.addView(stage, FrameLayout.LayoutParams(-1, -1))
        column.addView(stageFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        playBtn = kit.icon("play", 24f, c.ink, "Putar") { togglePlay() }
        buildTransport()
        column.addView(transport, LinearLayout.LayoutParams(-1, dp(44)))
        timeline = AnTracksView(a)
        column.addView(timeline, LinearLayout.LayoutParams(-1, dp(78)))

        buildPanelHost()
        column.addView(panelHost, LinearLayout.LayoutParams(-1, -2))
        column.addView(View(a).apply { setBackgroundColor(c.stroke) }, LinearLayout.LayoutParams(-1, 1))
        buildDock()

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                alive = false
                images.alive = false
                stopPlay()
                OverlayBack.remove(panelBack)
                OverlayBack.remove(overlayBack)
                p.job?.listener = null
                handler.removeCallbacks(autosaveTask)
                AnStore.autosave(a)
            }
        })
        syncDoc()
        p.job?.let { attachJob(it) }
        if (p.doc == null) showStartCard()
    }

    // ================================================================== top bar & transport

    private fun buildTopBar() {
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(dp(2), 0, dp(8), 0)
        topBar.background = c.shape(c.surface, 0)
        topBar.addView(kit.icon("arrow-left", 23f, c.ink, "Kembali") { a.navigateBack() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), 0, dp(4), 0)
            isClickable = true
            contentDescription = "Durasi animasi"
            background = c.pressable(Color.TRANSPARENT, 10)
            setOnClickListener { openPanel("durasi") }
        }
        titleView.apply {
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c.ink)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
        }
        subtitleView.apply {
            textSize = 11f; setTextColor(c.muted); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false; setPadding(0, dp(3), 0, 0)
        }
        titles.addView(titleView)
        titles.addView(subtitleView)
        topBar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(undoBtn, LinearLayout.LayoutParams(dp(40), dp(44)))
        topBar.addView(redoBtn, LinearLayout.LayoutParams(dp(40), dp(44)))
        topBar.addView(kit.icon("dots-vertical", 22f, c.ink, "Menu proyek") { showMenu() }, LinearLayout.LayoutParams(dp(38), dp(44)))
        topBar.addView(kit.primaryButton("Ekspor") { openPanel("ekspor") }.apply { contentDescription = "Ekspor animasi" },
            LinearLayout.LayoutParams(-2, dp(36)).apply { leftMargin = dp(4) })
    }

    private fun buildTransport() {
        transport.orientation = LinearLayout.HORIZONTAL
        transport.gravity = Gravity.CENTER_VERTICAL
        transport.setPadding(dp(4), 0, dp(4), 0)
        transport.background = c.shape(c.surface, 0)
        transport.addView(playBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        transport.addView(kit.icon("skip-previous", 21f, c.ink, "Keyframe sebelumnya") { jumpKey(-1) }, LinearLayout.LayoutParams(dp(40), dp(44)))
        transport.addView(kit.icon("chevron-left", 22f, c.ink, "Mundur satu frame") { stepFrame(-1) }, LinearLayout.LayoutParams(dp(36), dp(44)))
        transport.addView(kit.icon("chevron-right", 22f, c.ink, "Maju satu frame") { stepFrame(1) }, LinearLayout.LayoutParams(dp(36), dp(44)))
        transport.addView(kit.icon("skip-next", 21f, c.ink, "Keyframe berikutnya") { jumpKey(1) }, LinearLayout.LayoutParams(dp(40), dp(44)))
        timeText.apply {
            textSize = 12f; setTextColor(c.ink); typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            maxLines = 1
        }
        transport.addView(timeText, LinearLayout.LayoutParams(0, -2, 1f))
        transport.addView(kit.icon("key-plus", 21f, c.ink, "Tambah keyframe di playhead") { addKeyAtPlayhead() }, LinearLayout.LayoutParams(dp(42), dp(44)))
        transport.addView(kit.icon("key-remove", 21f, c.ink, "Hapus keyframe di playhead") { deleteKeyAtPlayhead() }, LinearLayout.LayoutParams(dp(42), dp(44)))
    }

    private fun refreshTime() {
        timeText.text = "${AnTiming.fmt(p.playheadMs)} / ${AnTiming.fmt(p.durationMs)}"
    }

    private fun updateChrome() {
        titleView.text = p.name
        val d = p.doc
        subtitleView.text = if (d == null) "Belum ada desain" else
            "${d.w}×${d.h} · ${String.format(Locale.US, "%.1f", p.durationMs / 1000f)} s · ${p.keyCount()} keyframe · 30 fps"
        undoBtn.alpha = if (p.undo.isEmpty()) 0.35f else 1f
        redoBtn.alpha = if (p.redo.isEmpty()) 0.35f else 1f
        playBtn.setIconName(if (playing) "pause" else "play")
        playBtn.contentDescription = if (playing) "Jeda" else "Putar"
        refreshTime()
    }

    // ================================================================== dok & seleksi

    private fun syncDoc() {
        stage.doc = p.doc
        stage.durationMs = p.durationMs
        // Tinggi timeline: penggaris + maks. 4 baris layer terlihat (sisanya digulir).
        val rows = (p.doc?.elements?.size ?: 0).coerceIn(1, 4)
        timeline.layoutParams = timeline.layoutParams.apply { height = dp(24) + dp(30) * rows + dp(4) }
        if (p.element(p.selectedId) == null) p.selectedId = null
        stage.selectedId = p.selectedId
        p.doc?.let { d -> images.retain(d.elements.map { it.id }.toSet()) }
        p.playheadMs = p.playheadMs.coerceIn(0L, p.durationMs)
        updateChrome()
        stage.invalidate()
        timeline.invalidate()
        renderDock()
        panelKey?.let { renderPanel(it) }
    }

    private fun onSelectionChanged() {
        stage.selectedId = p.selectedId
        stage.invalidate()
        timeline.invalidate()
        val k = panelKey
        if (k != null) renderPanel(k)
        else if (p.selectedId != null && p.doc != null) openPanel("kunci")
    }

    private fun checkpoint() {
        p.undo.add(p.snapshot())
        if (p.undo.size > 60) p.undo.removeAt(0)
        p.redo.clear()
    }

    private fun changed(rerender: Boolean = true) {
        stage.durationMs = p.durationMs
        updateChrome()
        stage.invalidate()
        timeline.invalidate()
        if (rerender) panelKey?.let { renderPanel(it) }
        handler.removeCallbacks(autosaveTask)
        handler.postDelayed(autosaveTask, 1000L)
    }

    private fun undo() {
        if (p.undo.isEmpty()) return
        p.redo.add(p.snapshot())
        p.restore(p.undo.removeAt(p.undo.size - 1))
        changed()
    }

    private fun redo() {
        if (p.redo.isEmpty()) return
        p.undo.add(p.snapshot())
        p.restore(p.redo.removeAt(p.redo.size - 1))
        changed()
    }

    // ================================================================== playback

    private fun togglePlay() { if (playing) stopPlay() else startPlay() }

    private fun startPlay() {
        if (p.doc == null) return
        if (p.playheadMs >= p.durationMs) p.playheadMs = 0L
        playing = true
        playStartMs = p.playheadMs
        playStartNanos = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(frameCb)
        updateChrome()
    }

    private fun stopPlay() {
        if (!playing) return
        playing = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
        if (alive) {
            updateChrome()
            panelKey?.let { renderPanel(it) }
        }
    }

    private fun seek(ms: Long, rerender: Boolean = true) {
        p.playheadMs = AnMath.snapToFrame(ms.coerceIn(0L, p.durationMs), AnProject.FPS).coerceIn(0L, p.durationMs)
        if (playing) { playStartMs = p.playheadMs; playStartNanos = System.nanoTime() }
        refreshTime()
        stage.invalidate()
        timeline.invalidate()
        if (rerender && panelKey == "kunci") renderPanel("kunci")
    }

    private fun stepFrame(dir: Int) {
        stopPlay()
        seek(p.playheadMs + dir * 1000L / AnProject.FPS + if (dir > 0) 1 else -1)
    }

    /** Lompat ke keyframe sebelum/berikut (layer terpilih, atau semua layer). */
    private fun jumpKey(dir: Int) {
        stopPlay()
        val keys = (p.selectedId?.let { p.tracks[it] } ?: p.tracks.values.flatten()).map { it.t }.distinct().sorted()
        val t = p.playheadMs
        val target = if (dir < 0) keys.lastOrNull { it < t - frameTol } else keys.firstOrNull { it > t + frameTol }
        if (target == null) { a.toast(if (keys.isEmpty()) "Belum ada keyframe" else "Tidak ada keyframe ${if (dir < 0) "sebelumnya" else "berikutnya"}"); return }
        seek(target)
    }

    // ================================================================== keyframe

    private fun selectedOrToast(): CsElement? {
        val el = p.element(p.selectedId)
        if (el == null) a.toast("Pilih layer dulu (ketuk objek di kanvas atau panel Layer)")
        return el
    }

    private fun keysOf(id: String): List<AnKey> = p.tracks[id] ?: emptyList()

    private fun setKeys(id: String, keys: List<AnKey>) {
        if (keys.isEmpty()) p.tracks.remove(id) else p.tracks[id] = keys
    }

    private fun addKeyAtPlayhead() {
        val el = selectedOrToast() ?: return
        stopPlay()
        val keys = keysOf(el.id)
        if (AnMath.keyAt(keys, p.playheadMs, frameTol) >= 0) { a.toast("Sudah ada keyframe di ${AnTiming.fmt(p.playheadMs)}"); return }
        checkpoint()
        setKeys(el.id, AnMath.upsert(keys, AnMath.keyFromCurve(keys, p.playheadMs), frameTol))
        changed()
        if (panelKey != "kunci") openPanel("kunci")
    }

    private fun deleteKeyAtPlayhead() {
        val el = selectedOrToast() ?: return
        stopPlay()
        val keys = keysOf(el.id)
        if (AnMath.keyAt(keys, p.playheadMs, frameTol) < 0) { a.toast("Tidak ada keyframe di playhead"); return }
        checkpoint()
        setKeys(el.id, AnMath.removeAt(keys, p.playheadMs, frameTol))
        changed()
    }

    /** Ubah keyframe di playhead (dibuat dari kurva bila belum ada — auto-key). */
    private fun editKey(id: String, change: (AnKey) -> AnKey) {
        val keys = keysOf(id)
        val i = AnMath.keyAt(keys, p.playheadMs, frameTol)
        val base = if (i >= 0) keys[i] else AnMath.keyFromCurve(keys, p.playheadMs)
        setKeys(id, AnMath.upsert(keys, change(base).copy(t = base.t), frameTol))
    }

    private fun beginDrag(id: String) {
        stopPlay()
        checkpoint()
        p.selectedId = id
        dragBase = AnMath.sample(keysOf(id), p.playheadMs)
    }

    private fun dragTo(id: String, dx: Float, dy: Float) {
        val base = dragBase ?: return
        editKey(id) { it.copy(dx = (base.dx + dx).roundToInt().toFloat(), dy = (base.dy + dy).roundToInt().toFloat()) }
        timeline.invalidate()
        updateChrome()
    }

    // ================================================================== panel

    private fun buildPanelHost() {
        panelHost.orientation = LinearLayout.VERTICAL
        panelHost.background = c.shape(c.surface, 0)
        panelHost.visibility = View.GONE
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(4), 0)
        }
        panelTitle.apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c.ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        head.addView(panelTitle, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(kit.icon("chevron-down", 22f, c.ink, "Tutup panel") { closePanel() }, LinearLayout.LayoutParams(dp(44), dp(40)))
        panelHost.addView(View(a).apply { setBackgroundColor(c.stroke) }, LinearLayout.LayoutParams(-1, 1))
        panelHost.addView(head, LinearLayout.LayoutParams(-1, dp(42)))
        panelBody.orientation = LinearLayout.VERTICAL
        panelBody.setPadding(dp(12), 0, dp(12), dp(12))
        panelScroll.addView(panelBody, FrameLayout.LayoutParams(-1, -2))
        panelScroll.isVerticalScrollBarEnabled = false
        panelHost.addView(panelScroll, LinearLayout.LayoutParams(-1, -2))
    }

    private fun openPanel(key: String) {
        if (p.doc == null && key != "sumber") { showStartCard(); return }
        panelKey = key
        panelHost.visibility = View.VISIBLE
        panelScroll.maxHeightPx = (a.resources.displayMetrics.heightPixels * 0.36f).roundToInt()
        OverlayBack.push(panelBack)
        renderPanel(key)
        renderDock()
    }

    private fun closePanel() {
        panelKey = null
        panelHost.visibility = View.GONE
        panelBody.removeAllViews()
        OverlayBack.remove(panelBack)
        renderDock()
    }

    private fun caption(t: String) {
        panelBody.addView(kit.text(t, 10.5f, c.muted, true).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(10), 0, dp(6)) })
    }

    private fun note(t: String) {
        panelBody.addView(kit.text(t, 11.5f, c.muted).apply { setPadding(dp(4), dp(8), dp(4), 0); setLineSpacing(0f, 1.15f) })
    }

    private fun renderPanel(key: String) {
        panelBody.removeAllViews()
        when (key) {
            "layer" -> renderLayerPanel()
            "kunci" -> renderKeyPanel()
            "preset" -> renderPresetPanel()
            "teks" -> renderTextPanel()
            "grafik" -> renderGraphPanel()
            "efek" -> renderEffectPanel()
            "durasi" -> renderDurationPanel()
            "ekspor" -> renderExportPanel()
            else -> closePanel()
        }
    }

    private fun layerName(el: CsElement): String = el.name.ifBlank {
        when (el.type) {
            CsType.TEXT -> el.text.lineSequence().firstOrNull()?.take(24)?.ifBlank { "Teks" } ?: "Teks"
            CsType.IMAGE -> "Foto"
            CsType.STICKER -> "Stiker"
            CsType.RECT -> "Persegi"
            CsType.ELLIPSE -> "Elips"
            CsType.TRIANGLE -> "Segitiga"
            CsType.LINE -> "Garis"
        }
    }

    private fun layerIcon(el: CsElement): String = when (el.type) {
        CsType.TEXT -> "format-text"
        CsType.IMAGE -> "image-outline"
        CsType.STICKER -> "sticker-emoji"
        CsType.RECT -> "square-rounded-outline"
        CsType.ELLIPSE -> "shape-circle-plus"
        CsType.TRIANGLE -> "triangle-outline"
        CsType.LINE -> "vector-line"
    }

    private fun renderLayerPanel() {
        val d = p.doc ?: return
        panelTitle.text = "Layer · ${d.elements.size}"
        if (d.elements.isEmpty()) { note("Desain tidak punya layer."); return }
        for (el in d.elements.asReversed()) {
            val sel = el.id == p.selectedId
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                background = if (sel) c.pressable(c.activeFill, 12) else c.pressable(c.surface2, 12)
                isClickable = true
                contentDescription = "Pilih layer ${layerName(el)}"
                setOnClickListener { p.selectedId = el.id; onSelectionChanged() }
            }
            val ink = if (sel) c.activeInk else c.ink
            row.addView(kit.icon(layerIcon(el), 19f, ink), LinearLayout.LayoutParams(dp(30), dp(30)))
            row.addView(kit.text(layerName(el) + if (!el.visible) " (tersembunyi)" else "", 13f, ink).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(6), 0, dp(6), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val n = keysOf(el.id).size
            val o = p.opt(el.id)
            val tags = listOfNotNull(if (n == 0) "diam" else "$n kunci", if (o.hidden) "sembunyi" else null, if (o.locked) "kunci" else null, o.text?.let { "teks" }, if (o.effects.isNotEmpty()) "${o.effects.size} efek" else null, o.trans?.let { "transisi" })
            row.addView(kit.text(tags.joinToString(" · "), 11f, if (sel) c.activeInk else c.muted))
            panelBody.addView(row, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(4) })
        }
        val el = p.element(p.selectedId)
        if (el != null && keysOf(el.id).isNotEmpty()) {
            panelBody.addView(kit.secondaryButton("Hapus semua keyframe \"${layerName(el).take(18)}\"") {
                checkpoint(); p.tracks.remove(el.id); changed()
            }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
        }
        if (el != null) renderRangeControls(el)
        note("Layer terkunci di Foto Studio tidak bisa diseret di kanvas, tetapi tetap bisa dianimasikan dari panel ini. Di timeline: ketuk ikon mata/gembok untuk sembunyi/kunci track, seret ujung bilah untuk rentang aktif.")
    }

    /** Rentang aktif + sembunyi/kunci layer terpilih (alternatif tombol untuk seret di timeline). */
    private fun renderRangeControls(el: CsElement) {
        val o = p.opt(el.id)
        caption("RENTANG AKTIF · ${AnTiming.fmt(o.inMs)} – ${AnTiming.fmt(o.outOr(p.durationMs))}")
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(kit.secondaryButton("Masuk = playhead") { editOpt(el.id) { AnTimeline.withIn(it, p.playheadMs, p.durationMs) } },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
        row.addView(kit.secondaryButton("Keluar = playhead") { editOpt(el.id) { AnTimeline.withOut(it, p.playheadMs, p.durationMs) } },
            LinearLayout.LayoutParams(0, dp(40), 1f))
        panelBody.addView(row)
        if (o.hasRange(p.durationMs)) {
            panelBody.addView(kit.secondaryButton("Rentang penuh") { editOpt(el.id) { it.copy(inMs = 0L, outMs = -1L) } },
                LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
        }
        panelBody.addView(kit.toggleRow("Sembunyikan di animasi", o.hidden) { v -> editOpt(el.id) { it.copy(hidden = v) } },
            LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(4) })
        panelBody.addView(kit.toggleRow("Kunci track (tidak bisa diseret)", o.locked) { v -> editOpt(el.id) { it.copy(locked = v) } },
            LinearLayout.LayoutParams(-1, dp(40)))
    }

    /** Ubah opsi layer dengan checkpoint undo. */
    private fun editOpt(id: String, change: (AnLayerOpt) -> AnLayerOpt) {
        stopPlay()
        val cur = p.opt(id)
        val next = change(cur)
        if (next == cur) return
        checkpoint()
        p.setOpt(id, next)
        changed()
    }

    private fun textAnimIcon(type: String): String = when (type) {
        AnTextMath.TYPEWRITER -> "format-text"
        AnTextMath.FADE -> "opacity"
        AnTextMath.POP -> "party-popper"
        AnTextMath.BOUNCE -> "chart-bell-curve"
        AnTextMath.WAVE -> "sine-wave"
        AnTextMath.TRACKING -> "format-letter-case"
        AnTextMath.PATH -> "vector-polyline"
        AnTextMath.TITLE -> "arrow-up-bold"
        AnTextMath.SUBTITLE -> "subtitles-outline"
        else -> "close"
    }

    /** Animasi teks per huruf untuk layer TEXT terpilih (mulai, lama, intensitas, bentuk jalur). */
    private fun renderTextPanel() {
        val el = p.element(p.selectedId)
        if (el == null || el.type != CsType.TEXT) {
            panelTitle.text = "Animasi teks"
            note("Pilih layer teks di kanvas atau panel Layer. Animasi teks berjalan per huruf dan ikut diekspor ke MP4/GIF.")
            return
        }
        val cur = p.opt(el.id).text
        panelTitle.text = "Teks · ${layerName(el).take(20)}"
        val tiles = ArrayList<View>()
        tiles.add(kit.tile("close", "Tanpa", cur == null) { editOpt(el.id) { it.copy(text = null) } })
        for ((id, label) in AnTextMath.ALL) {
            tiles.add(kit.tile(textAnimIcon(id), label, cur?.type == id) {
                editOpt(el.id) { o ->
                    val base = o.text
                    // Ganti jenis mempertahankan waktu; baru = mulai di playhead.
                    o.copy(text = base?.copy(type = id) ?: AnTextAnim(id, startMs = AnTimeline.snap(p.playheadMs).coerceAtMost(p.durationMs), durMs = defaultTextMs(id)))
                }
                ensureDurationCovers(el.id)
            })
        }
        for (r in tiles.chunked(4)) {
            val list = r.toMutableList()
            while (list.size < 4) list.add(View(a))
            panelBody.addView(kit.tileRow(list))
        }
        if (cur == null) {
            note("Ketik: huruf muncul satu per satu · Fade/Pop: per huruf · Pantul & Gelombang: gerak vertikal per huruf · Tracking: jarak huruf menyempit · Jalur: teks di lengkung/busur/lingkaran · Judul: naik + tracking · Subjudul: fade-in dari bawah.")
            return
        }
        if (cur.type == AnTextMath.PATH) {
            caption("BENTUK JALUR")
            panelBody.addView(kit.segmented(AnTextMath.PATHS, cur.path) { k -> editOpt(el.id) { o -> o.copy(text = o.text?.copy(path = k)) } },
                LinearLayout.LayoutParams(-1, dp(38)))
        }
        fun textSlider(label: String, min: Int, max: Int, value: Int, def: Int, fmt: (Int) -> String, apply: (AnTextAnim, Int) -> AnTextAnim) =
            panelBody.addView(kit.slider(label, min, max, value.coerceIn(min, max), def, format = fmt, onStart = { stopPlay(); checkpoint() }, onStop = { ensureDurationCovers(el.id); changed() }) { v ->
                val o = p.opt(el.id)
                o.text?.let { t -> p.setOpt(el.id, o.copy(text = apply(t, v))) }
                changed(rerender = false)
            })
        textSlider("Mulai", 0, (p.durationMs / 50).toInt(), (cur.startMs / 50).toInt(), 0, { AnTiming.fmt(it * 50L) }) { t, v -> t.copy(startMs = v * 50L) }
        textSlider("Lama", 0, 200, (cur.durMs / 50).toInt(), (defaultTextMs(cur.type) / 50).toInt(), { String.format(Locale.US, "%.2f s", it * 50 / 1000f) }) { t, v -> t.copy(durMs = v * 50L) }
        textSlider("Intensitas", 0, 200, (cur.amount * 100).roundToInt(), 100, { "$it%" }) { t, v -> t.copy(amount = v / 100f) }
        panelBody.addView(kit.secondaryButton("Mulai di playhead (${AnTiming.fmt(p.playheadMs)})") {
            editOpt(el.id) { o -> o.copy(text = o.text?.copy(startMs = AnTimeline.snap(p.playheadMs).coerceAtMost(p.durationMs))) }
            ensureDurationCovers(el.id)
        }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(8) })
        note("Animasi teks digabung dengan keyframe layer (geser/putar/skala/opasitas). Selama animasi teks aktif, miring 3D & mode campur layer tidak diterapkan.")
    }

    private fun defaultTextMs(type: String): Long = when (type) {
        AnTextMath.TYPEWRITER, AnTextMath.PATH -> 1_500L
        AnTextMath.WAVE -> 3_000L
        AnTextMath.SUBTITLE -> 800L
        else -> 1_200L
    }

    /** Perpanjang durasi animasi bila animasi teks melewati akhir. */
    private fun ensureDurationCovers(id: String) {
        val t = p.opt(id).text ?: return
        val end = t.startMs + t.durMs
        if (end > p.durationMs) {
            p.durationMs = AnMath.clampDuration(((end + 999) / 1000) * 1000)
            a.toast("Durasi diperpanjang menjadi ${p.durationMs / 1000} s")
            changed()
        }
    }

    private fun renderKeyPanel() {
        val d = p.doc ?: return
        val el = p.element(p.selectedId)
        if (el == null) {
            panelTitle.text = "Keyframe"
            note("Ketuk objek di kanvas (atau pilih di panel Layer) untuk mengatur keyframe-nya.")
            return
        }
        val keys = keysOf(el.id)
        val idx = AnMath.keyAt(keys, p.playheadMs, frameTol)
        val cur = if (idx >= 0) keys[idx] else AnMath.keyFromCurve(keys, p.playheadMs)
        panelTitle.text = "${layerName(el).take(22)} · ${AnTiming.fmt(p.playheadMs)}"
        panelBody.addView(kit.text(
            if (idx >= 0) "Keyframe ${idx + 1} dari ${keys.size} di playhead." else if (keys.isEmpty()) "Belum ada keyframe — mengubah nilai membuat keyframe di playhead." else "Di antara keyframe — mengubah nilai menambah keyframe di playhead.",
            11.5f, c.muted
        ).apply { setPadding(dp(4), dp(8), dp(4), dp(4)) })
        fun slider(label: String, min: Int, max: Int, value: Int, def: Int, fmt: (Int) -> String, apply: (AnKey, Int) -> AnKey) =
            panelBody.addView(kit.slider(label, min, max, value.coerceIn(min, max), def, format = fmt, onStart = { stopPlay(); checkpoint() }, onStop = { changed() }) { v ->
                editKey(el.id) { k -> apply(k, v) }
                changed(rerender = false)
            })
        val mw = d.w * 2
        val mh = d.h * 2
        slider("Geser X", -mw, mw, cur.dx.roundToInt(), 0, { "$it" }) { k, v -> k.copy(dx = v.toFloat()) }
        slider("Geser Y", -mh, mh, cur.dy.roundToInt(), 0, { "$it" }) { k, v -> k.copy(dy = v.toFloat()) }
        slider("Putar", -720, 720, cur.rot.roundToInt(), 0, { "$it°" }) { k, v -> k.copy(rot = v.toFloat()) }
        slider("Skala", 0, 400, (cur.scale * 100).roundToInt(), 100, { "$it%" }) { k, v -> k.copy(scale = v / 100f) }
        slider("Opasitas", 0, 100, (cur.opacity * 100).roundToInt(), 100, { "$it%" }) { k, v -> k.copy(opacity = v / 100f) }
        caption("EASING KE KEYFRAME BERIKUTNYA")
        panelBody.addView(kit.segmented(AnEase.ALL.map { it to AnEase.label(it).replace("Ease-", "") }, cur.ease) { e ->
            stopPlay(); checkpoint()
            editKey(el.id) { it.copy(ease = e) }
            changed()
        }, LinearLayout.LayoutParams(-1, dp(38)))
        if (keys.size >= 2) {
            panelBody.addView(kit.secondaryButton("Graph Editor (back, pantul, elastis, pegas, bezier)") { openPanel("grafik") },
                LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
        }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(kit.secondaryButton(if (idx >= 0) "Hapus keyframe" else "Tambah keyframe") { if (idx >= 0) deleteKeyAtPlayhead() else addKeyAtPlayhead() },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
        row.addView(kit.secondaryButton("Reset ke desain") {
            stopPlay(); checkpoint()
            editKey(el.id) { it.copy(dx = 0f, dy = 0f, rot = 0f, scale = 1f, opacity = 1f) }
            changed()
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        panelBody.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        note("Nilai adalah offset dari desain asli. Seret objek di kanvas untuk mengatur posisi di playhead. Ketuk angka untuk reset.")
    }

    // ================================================================== graph editor


    /** Segmen (indeks keyframe awal) yang memuat playhead untuk layer [id], atau -1. */
    private fun segmentAt(id: String): Int {
        val keys = keysOf(id)
        if (keys.size < 2) return -1
        val i = keys.indexOfLast { it.t <= p.playheadMs }
        return i.coerceIn(0, keys.size - 2)
    }

    /** Ganti ease keyframe awal segmen [i] (tanpa checkpoint — pemanggil yang mengatur). */
    private fun setSegEase(id: String, i: Int, ease: String) {
        val keys = keysOf(id)
        if (i !in keys.indices) return
        val e = AnEase.valid(ease)
        setKeys(id, keys.mapIndexed { j, k -> if (j == i) k.copy(ease = e) else k })
    }

    private fun easeFamily(e: String): String = e.substringBefore(':')

    private fun renderGraphPanel() {
        graphView = null
        val el = p.element(p.selectedId)
        if (el == null) {
            panelTitle.text = "Graph Editor"
            note("Pilih layer yang punya minimal dua keyframe. Graph Editor mengatur kurva easing segmen di playhead.")
            return
        }
        val keys = keysOf(el.id)
        val i = segmentAt(el.id)
        panelTitle.text = "Grafik · ${layerName(el).take(20)}"
        if (i < 0) {
            note("Layer ini butuh minimal dua keyframe. Tambahkan keyframe di panel Keyframe, lalu kembali ke sini.")
            return
        }
        val k0 = keys[i]
        val k1 = keys[i + 1]
        val curve = AnEasing.parse(k0.ease) ?: AnCurve.InOut
        caption("SEGMEN ${i + 1} → ${i + 2} · ${AnTiming.fmt(k0.t)} – ${AnTiming.fmt(k1.t)} · ${AnEase.label(k0.ease).uppercase(Locale.ROOT)}")
        val g = AnGraphView(a, c)
        g.curve = curve
        g.onDragStart = { stopPlay(); checkpoint() }
        g.onChange = { b ->
            setSegEase(el.id, i, AnEasing.format(b))
            changed(rerender = false)
        }
        g.onDragEnd = { changed() }
        graphView = g
        panelBody.addView(g, LinearLayout.LayoutParams(-1, dp(190)))
        panelBody.addView(kit.text("Garis tebal = nilai (0 → 1), garis tipis = kecepatan. Seret dua titik untuk kurva bezier khusus.", 11f, c.muted).apply {
            setPadding(dp(4), dp(4), dp(4), dp(2))
        })
        caption("PRESET")
        val canon = AnEase.valid(k0.ease)
        val active = AnEasing.PRESETS.firstOrNull { (key, _) ->
            if (easeFamily(key) == AnEasing.BEZIER) AnEase.valid(key) == canon else easeFamily(key) == easeFamily(canon)
        }?.first ?: ""
        fun pick(key: String) { stopPlay(); checkpoint(); setSegEase(el.id, i, key); changed() }
        for (row in AnEasing.PRESETS.chunked(5)) {
            panelBody.addView(kit.segmented(row, active) { pick(it) }, LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(4) })
        }
        fun live(label: String, min: Int, max: Int, value: Int, def: Int, fmt: (Int) -> String, make: (Int) -> AnCurve) =
            panelBody.addView(kit.slider(label, min, max, value.coerceIn(min, max), def, format = fmt, onStart = { stopPlay(); checkpoint() }, onStop = { changed() }) { v ->
                val c2 = make(v)
                setSegEase(el.id, i, AnEasing.format(c2))
                graphView?.let { it.curve = AnEasing.parse(keysOf(el.id).getOrNull(i)?.ease) ?: c2; it.invalidate() }
                changed(rerender = false)
            })
        caption("AKSELERASI")
        val (ai, ao) = AnEasing.accelOf(curve)
        var inS = ai
        var outS = ao
        live("Ease-in", 0, 100, (ai * 100).roundToInt(), 0, { "$it%" }) { v -> inS = v / 100f; AnEasing.accel(inS, outS) }
        live("Ease-out", 0, 100, (ao * 100).roundToInt(), 0, { "$it%" }) { v -> outS = v / 100f; AnEasing.accel(inS, outS) }
        val ov = AnEasing.overshootOf(curve)
        if (ov != null) {
            caption("OVERSHOOT")
            live("Jumlah", 0, 100, (ov * 100).roundToInt(), if (curve is AnCurve.Back) 43 else 29, { "$it%" }) { v -> AnEasing.withOvershoot(curve, v / 100f) }
        }
        if (curve is AnCurve.Spring) {
            caption("PEGAS")
            var k = curve.stiffness
            var d = curve.damping
            live("Kekakuan", 10, 1000, k.roundToInt(), AnEasing.SPRING_K.roundToInt(), { "$it" }) { v -> k = v.toFloat(); AnCurve.Spring(k, d) }
            live("Redaman", 1, 100, d.roundToInt(), AnEasing.SPRING_D.roundToInt(), { "$it" }) { v -> d = v.toFloat(); AnCurve.Spring(k, d) }
        }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(kit.secondaryButton("Semua segmen layer") {
            stopPlay(); checkpoint()
            val e = keysOf(el.id).getOrNull(i)?.ease ?: AnEase.IN_OUT
            setKeys(el.id, keysOf(el.id).map { it.copy(ease = e) })
            changed()
            a.toast("Kurva diterapkan ke semua segmen")
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
        row.addView(kit.secondaryButton("Segmen berikutnya") {
            val nk = keysOf(el.id).getOrNull(i + 1)
            if (nk != null && i + 2 < keysOf(el.id).size) seek(nk.t) else a.toast("Ini segmen terakhir")
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        panelBody.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        note("Back/Elastis/Pegas melewati nilai tujuan lalu kembali (overshoot). Opasitas tetap dibatasi 0–100%. Disimpan sebagai: ${keysOf(el.id).getOrNull(i)?.ease ?: canon}")
    }

    // ================================================================== efek & transisi


    private fun effectIcon(type: String): String = when (type) {
        AnFxMath.MOTION_BLUR -> "motion-play-outline"
        AnFxMath.PARTICLES -> "creation"
        AnFxMath.LIGHT -> "white-balance-sunny"
        AnFxMath.FLASH -> "flash"
        AnFxMath.SHAKE -> "move-resize"
        AnFxMath.GLITCH -> "shimmer"
        AnFxMath.BLUR -> "blur"
        else -> "auto-fix"
    }

    private fun editEffect(id: String, index: Int, change: (AnEffect) -> AnEffect) {
        val o = p.opt(id)
        if (index !in o.effects.indices) return
        p.setOpt(id, o.copy(effects = o.effects.mapIndexed { j, e -> if (j == index) change(e) else e }))
    }

    private fun renderEffectPanel() {
        val el = p.element(p.selectedId)
        if (el == null) {
            panelTitle.text = "Efek"
            note("Pilih layer di kanvas atau panel Layer. Efek (motion blur, partikel, cahaya, kilat, guncang, glitch, blur) punya waktu mulai, lama & intensitas, dan ikut diekspor ke MP4/GIF.")
            return
        }
        val o = p.opt(el.id)
        panelTitle.text = "Efek · ${layerName(el).take(20)}"
        caption("TAMBAH EFEK")
        val tiles = AnFxMath.ALL.map { (type, label) ->
            kit.tile(effectIcon(type), label) {
                if (p.opt(el.id).effects.size >= AnFxMath.MAX_EFFECTS) {
                    a.toast("Maksimal ${AnFxMath.MAX_EFFECTS} efek per layer")
                } else {
                    val start = AnTimeline.snap(p.playheadMs).coerceIn(0L, max(0L, p.durationMs - AnTimeline.MIN_RANGE))
                    val dur = min(AnFxMath.defaultMs(type), p.durationMs - start).coerceAtLeast(AnTimeline.MIN_RANGE)
                    val seed = (el.id.hashCode() * 31 + p.opt(el.id).effects.size * 7919 + start.toInt()) and 0x7fffffff
                    effSel = p.opt(el.id).effects.size
                    editOpt(el.id) { it.copy(effects = it.effects + AnEffect(type, start, dur, 1f, 0, type == AnFxMath.FLASH, seed)) }
                }
            }
        }
        for (r in tiles.chunked(4)) {
            val list = r.toMutableList<View>()
            while (list.size < 4) list.add(View(a))
            panelBody.addView(kit.tileRow(list))
        }
        if (o.effects.isNotEmpty()) {
            caption("EFEK LAYER · ${o.effects.size}")
            effSel = effSel.coerceIn(0, o.effects.size - 1)
            for ((j, e) in o.effects.withIndex()) {
                val sel = j == effSel
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(2), 0)
                    background = if (sel) c.pressable(c.activeFill, 12) else c.pressable(c.surface2, 12)
                    isClickable = true
                    contentDescription = "Pilih efek ${AnFxMath.label(e.type)}"
                    setOnClickListener { effSel = j; renderPanel("efek") }
                }
                val ink = if (sel) c.activeInk else c.ink
                row.addView(kit.icon(effectIcon(e.type), 18f, ink), LinearLayout.LayoutParams(dp(28), dp(28)))
                row.addView(kit.text("${AnFxMath.label(e.type)} · ${AnTiming.fmt(e.startMs)} – ${AnTiming.fmt(e.startMs + e.durMs)}", 12.5f, ink).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(6), 0, dp(6), 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(kit.icon("delete-outline", 19f, ink, "Hapus efek") {
                    editOpt(el.id) { it.copy(effects = it.effects.filterIndexed { x, _ -> x != j }) }
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                panelBody.addView(row, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(4) })
            }
            val cur = o.effects[effSel]
            fun fxSlider(label: String, min: Int, max: Int, value: Int, def: Int, fmt: (Int) -> String, apply: (AnEffect, Int) -> AnEffect) =
                panelBody.addView(kit.slider(label, min, max, value.coerceIn(min, max), def, format = fmt, onStart = { stopPlay(); checkpoint() }, onStop = { changed() }) { v ->
                    editEffect(el.id, effSel) { apply(it, v) }
                    changed(rerender = false)
                })
            caption(AnFxMath.label(cur.type).uppercase(Locale.ROOT))
            fxSlider("Mulai", 0, (p.durationMs / 50).toInt(), (cur.startMs / 50).toInt(), 0, { AnTiming.fmt(it * 50L) }) { e, v -> e.copy(startMs = v * 50L) }
            fxSlider("Lama", 2, 400, (cur.durMs / 50).toInt(), (AnFxMath.defaultMs(cur.type) / 50).toInt(), { String.format(Locale.US, "%.2f s", it * 50 / 1000f) }) { e, v -> e.copy(durMs = v * 50L) }
            fxSlider("Intensitas", 0, 200, (cur.amount * 100).roundToInt(), 100, { "$it%" }) { e, v -> e.copy(amount = v / 100f) }
            fun fxEdit(change: (AnEffect) -> AnEffect) { stopPlay(); checkpoint(); editEffect(el.id, effSel, change); changed() }
            if (cur.type == AnFxMath.PARTICLES || cur.type == AnFxMath.LIGHT) {
                val kinds = if (cur.type == AnFxMath.PARTICLES) AnFxMath.PARTICLE_KINDS else AnFxMath.LIGHT_KINDS
                panelBody.addView(kit.segmented(kinds, cur.variant.toString()) { k -> fxEdit { it.copy(variant = k.toInt()) } },
                    LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(4) })
                if (!(cur.type == AnFxMath.LIGHT && cur.variant == 1)) {
                    panelBody.addView(kit.toggleRow("Seluruh kanvas", cur.global) { v -> fxEdit { it.copy(global = v) } },
                        LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(4) })
                }
            }
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(kit.secondaryButton("Mulai di playhead") {
                fxEdit { it.copy(startMs = AnTimeline.snap(p.playheadMs).coerceIn(0L, p.durationMs)) }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
            row.addView(kit.secondaryButton("Acak pola") { fxEdit { it.copy(seed = (it.seed * 1103515245 + 12345) and 0x7fffffff) } },
                LinearLayout.LayoutParams(0, dp(40), 1f))
            panelBody.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            note(when (cur.type) {
                AnFxMath.MOTION_BLUR -> "Motion blur hanya terlihat saat layer bergerak (dari keyframe)."
                AnFxMath.SHAKE -> "Guncang menggeser seluruh kanvas (kanvas sedikit diperbesar agar tepi tidak terbuka)."
                AnFxMath.FLASH -> "Kilat putih menutup seluruh kanvas."
                AnFxMath.BLUR -> "Blur naik ke puncak di tengah rentang lalu kembali tajam."
                else -> "Efek deterministik: pratinjau dan hasil ekspor sama."
            })
        }
        renderTransitionControls(el)
    }

    private fun renderTransitionControls(el: CsElement) {
        val o = p.opt(el.id)
        caption("TRANSISI MASUK")
        val opts = listOf("none" to "Tanpa") + AnTransMath.ALL
        panelBody.addView(kit.segmented(opts.take(3), o.trans?.type ?: "none") { k -> setTrans(el.id, k) }, LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(4) })
        panelBody.addView(kit.segmented(opts.drop(3), o.trans?.type ?: "none") { k -> setTrans(el.id, k) }, LinearLayout.LayoutParams(-1, dp(38)))
        val tr = o.trans
        if (tr != null) {
            panelBody.addView(kit.slider("Lama", 2, 60, (tr.durMs / 50).toInt(), 12, format = { String.format(Locale.US, "%.2f s", it * 50 / 1000f) },
                onStart = { stopPlay(); checkpoint() }, onStop = { changed() }) { v ->
                val cur = p.opt(el.id)
                cur.trans?.let { p.setOpt(el.id, cur.copy(trans = it.copy(durMs = v * 50L))) }
                changed(rerender = false)
            })
            val pr = AnTransMath.pairs(p.doc?.elements?.filter { it.visible }?.map { it.id } ?: emptyList(), p.opts, p.durationMs).firstOrNull { it.toId == el.id }
            val from = pr?.fromId?.let { p.element(it) }
            note(if (from != null) "Bersambung dengan \"${layerName(from).take(20)}\" yang keluar di ${AnTiming.fmt(o.inMs)}."
            else "Belum ada layer yang keluar di titik masuk (${AnTiming.fmt(o.inMs)}) — transisi berlaku sebagai animasi masuk saja. Atur titik keluar layer lain = titik masuk layer ini (panel Layer / seret ujung bilah timeline).")
        } else {
            note("Transisi berjalan di titik masuk layer ini. Bila layer lain keluar di titik yang sama (±150 ms), keduanya dipadukan (crossfade, geser, zoom, wipe, blur).")
        }
    }

    private fun setTrans(id: String, k: String) {
        editOpt(id) { it.copy(trans = if (k == "none") null else AnTransition(k, it.trans?.durMs ?: 600L)) }
    }

    private fun renderPresetPanel() {
        val el = p.element(p.selectedId)
        panelTitle.text = "Preset · mulai ${AnTiming.fmt(p.playheadMs)}"
        panelBody.addView(kit.segmented(listOf("satu" to "Layer terpilih", "semua" to "Semua layer"), if (presetAll) "semua" else "satu") { k ->
            presetAll = k == "semua"; renderPanel("preset")
        }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(6) })
        if (!presetAll && el == null) note("Pilih layer di kanvas, atau pakai \"Semua layer\" (berurutan, jeda 180 ms).")
        panelBody.addView(kit.slider("Lama", 200, 3000, presetMs.toInt(), 700, format = { String.format(Locale.US, "%.1f s", it / 1000f) }) { v ->
            presetMs = (v / 50L * 50L).toLong().coerceAtLeast(200L)
        })
        val tiles = AnPresets.ALL.map { (id, label) ->
            kit.tile(presetIcon(id), label) { applyPreset(id) }
        }
        for (r in tiles.chunked(4)) {
            val list = r.toMutableList<View>()
            while (list.size < 4) list.add(View(a))
            panelBody.addView(kit.tileRow(list))
        }
        note("Preset membuat keyframe mulai dari playhead; objek kembali tepat ke posisi desain di akhir. Keyframe lain di luar rentang preset dipertahankan.")
    }

    private fun presetIcon(id: String): String = when (id) {
        "fade" -> "opacity"
        "slide_l" -> "arrow-right-bold"
        "slide_r" -> "arrow-left-bold"
        "slide_up" -> "arrow-up-bold"
        "pop" -> "party-popper"
        "zoom" -> "magnify-plus-outline"
        else -> "rotate-right"
    }

    private fun applyPreset(id: String) {
        val d = p.doc ?: return
        stopPlay()
        val targets = if (presetAll) d.elements.filter { it.visible } else listOfNotNull(p.element(p.selectedId))
        if (targets.isEmpty()) { a.toast("Pilih layer dulu"); return }
        checkpoint()
        val start = p.playheadMs
        val dur = presetMs
        var lastEnd = start + dur
        for ((i, el) in targets.withIndex()) {
            val s = start + if (presetAll) i * 180L else 0L
            val k = AnPresets.keys(id, s, dur, el.x, el.y, el.w, el.h, d.w.toFloat(), d.h.toFloat(), el.depth)
            setKeys(el.id, AnPresets.applyTo(keysOf(el.id), k))
            lastEnd = max(lastEnd, s + dur)
        }
        if (lastEnd > p.durationMs) {
            p.durationMs = AnMath.clampDuration(((lastEnd + 999) / 1000) * 1000)
            a.toast("Durasi diperpanjang menjadi ${p.durationMs / 1000} s")
        }
        changed()
        a.toast("Preset ${AnPresets.label(id)} diterapkan ke ${targets.size} layer")
    }

    private fun renderDurationPanel() {
        panelTitle.text = "Durasi · ${String.format(Locale.US, "%.1f", p.durationMs / 1000f)} s"
        panelBody.addView(kit.slider("Durasi", 1, 60, (p.durationMs / 1000).toInt(), 5, format = { "$it s" },
            onStart = { stopPlay(); checkpoint() }, onStop = { changed() }) { v ->
            setDuration(v * 1000L)
            changed(rerender = false)
        })
        val quick = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        for (s in listOf(3, 5, 10, 15, 30)) {
            quick.addView(kit.pill("$s s", p.durationMs == s * 1000L) { stopPlay(); checkpoint(); setDuration(s * 1000L); changed() },
                LinearLayout.LayoutParams(0, dp(32), 1f).apply { setMargins(dp(2), dp(6), dp(2), 0) })
        }
        panelBody.addView(quick)
        val lastKey = p.tracks.values.flatten().maxOfOrNull { it.t } ?: 0L
        if (lastKey in 1L until p.durationMs) {
            panelBody.addView(kit.secondaryButton("Pas ke keyframe terakhir (${AnTiming.fmt(lastKey)})") {
                stopPlay(); checkpoint(); setDuration(max(1000L, lastKey)); changed()
            }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(10) })
        }
        note("1–60 detik, 30 fps. Memperpendek durasi membuang keyframe setelah akhir (bisa di-undo).")
    }

    private fun setDuration(ms: Long) {
        p.durationMs = AnMath.clampDuration(ms)
        for (id in p.tracks.keys.toList()) setKeys(id, AnMath.clip(keysOf(id), p.durationMs))
        // Rentang aktif yang melewati durasi baru dipangkas (out di akhir = ikut durasi).
        for ((id, o) in p.opts.entries.toList()) {
            if (o.outMs >= p.durationMs) p.setOpt(id, o.copy(outMs = -1L))
            if (p.opt(id).inMs >= p.durationMs - AnTimeline.MIN_RANGE) p.setOpt(id, p.opt(id).copy(inMs = 0L))
        }
        if (p.playheadMs > p.durationMs) p.playheadMs = p.durationMs
    }

    private fun renderExportPanel() {
        val d = p.doc ?: return
        panelTitle.text = "Ekspor"
        panelBody.addView(kit.segmented(listOf("mp4" to "MP4 (H.264)", "gif" to "GIF"), p.exportFormat) { k -> p.exportFormat = k; renderPanel("ekspor") },
            LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(6) })
        if (p.exportFormat == "mp4") {
            caption("RESOLUSI (SISI PENDEK)")
            panelBody.addView(kit.segmented(listOf("480" to "480p", "720" to "720p", "1080" to "1080p"), p.videoShort.toString()) { k ->
                p.videoShort = k.toInt(); renderPanel("ekspor")
            }, LinearLayout.LayoutParams(-1, dp(38)))
            val (w, h) = videoSize(d)
            note("Hasil ${w}×${h} · 30 fps · ${AnTiming.frameCount(p.durationMs, AnProject.FPS)} frame." +
                if (d.bg.mode == "transparent") " MP4 tidak mendukung transparansi — latar diisi putih." else "")
            panelBody.addView(kit.primaryButton("Simpan MP4 ke Galeri") { startExport("mp4") }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
            panelBody.addView(kit.secondaryButton("Ke Video Editor (tambah sebagai klip)") { startExport("ve") }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
        } else {
            caption("UKURAN (SISI PANJANG)")
            panelBody.addView(kit.segmented(listOf("320" to "320", "480" to "480", "640" to "640"), p.gifLong.toString()) { k ->
                p.gifLong = k.toInt(); renderPanel("ekspor")
            }, LinearLayout.LayoutParams(-1, dp(38)))
            caption("FRAME RATE")
            panelBody.addView(kit.segmented(listOf("10" to "10 fps", "15" to "15 fps", "20" to "20 fps"), p.gifFps.toString()) { k ->
                p.gifFps = k.toInt(); renderPanel("ekspor")
            }, LinearLayout.LayoutParams(-1, dp(38)))
            if (d.bg.mode == "transparent") note("Latar kanvas transparan — transparansi ikut diekspor (GIF 1-bit).")
            else panelBody.addView(kit.toggleRow("Latar transparan (lewati latar kanvas)", p.gifTransparent) { v -> p.gifTransparent = v },
                LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(4) })
            val (w, h) = AnTiming.fitEven(d.w, d.h, p.gifLong, 2)
            note("Hasil ${w}×${h} · ${AnTiming.frameCount(p.durationMs, p.gifFps)} frame · palet 256 warna per frame, berulang terus.")
            panelBody.addView(kit.primaryButton("Simpan GIF ke Galeri") { startExport("gif") }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
        }
        val last = p.lastResult
        if (last != null) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), 0, 0) }
            row.addView(kit.text("Terakhir: ${last.label} · ${humanBytes(last.bytes)}", 11.5f, c.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE },
                LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(kit.secondaryButton("Bagikan") { AnShare.share(a, last) }, LinearLayout.LayoutParams(-2, dp(38)).apply { leftMargin = dp(6) })
            panelBody.addView(row)
        }
        note("MP4 → Movies/GITLS, GIF → Pictures/GITLS (Android 10+). Android 7–9: folder aplikasi, lalu Bagikan. Foto dirender ulang dari sumber.")
    }

    private fun videoSize(d: CsDocData): Pair<Int, Int> {
        val s = p.videoShort.toFloat() / min(d.w, d.h).coerceAtLeast(1)
        return AnTiming.fitEven((d.w * s).roundToInt(), (d.h * s).roundToInt(), 1920, 2)
    }

    // ================================================================== ekspor

    private fun startExport(kind: String) {
        val d = p.doc ?: return
        if (p.job != null) { a.toast("Ekspor lain sedang berjalan"); return }
        stopPlay()
        val tracks = HashMap(p.tracks)
        val opts = HashMap(p.opts)
        val dur = p.durationMs
        val name = p.name
        val ctx = a.applicationContext
        val job = AnJob(when (kind) { "gif" -> "Mengekspor GIF…"; "ve" -> "Merender untuk Video Editor…"; else -> "Mengekspor MP4…" })
        p.job = job
        attachJob(job)
        val (vw, vh) = videoSize(d)
        val gifSize = AnTiming.fitEven(d.w, d.h, p.gifLong, 2)
        val gifFps = p.gifFps
        val gifTransparent = d.bg.mode == "transparent" || p.gifTransparent
        val zoom = AnDraw.maxScale(tracks)
        val main = Handler(Looper.getMainLooper())
        val notify = { main.post { job.listener?.invoke() }; Unit }
        Thread {
            val r: Result<Any> = runCatching {
                when (kind) {
                    "gif" -> {
                        val (w, h) = gifSize
                        val imgs = AnDraw.exportImages(d, w / d.w.toFloat(), zoom, cap = 1024)
                        val src = AnDocSource(ctx, d, tracks, dur, gifFps, w, h, imgs, background = !gifTransparent, opts = opts)
                        val f = AnShare.movieFile(ctx, name, "gif")
                        AnEncode.gif(src, f, { job.cancel.get() }) { pr -> job.progress = pr; notify() }
                        AnShare.publish(ctx, f, "image/gif")
                    }
                    else -> {
                        val imgs = AnDraw.exportImages(d, vw / d.w.toFloat(), zoom)
                        val src = AnDocSource(ctx, d, tracks, dur, AnProject.FPS, vw, vh, imgs, background = true, opts = opts)
                        val f = if (kind == "ve") File(AnEncode.veMediaDir(ctx), "anim_${CsStore.safeName(name)}_${System.currentTimeMillis()}.mp4")
                        else AnShare.movieFile(ctx, name, "mp4")
                        AnEncode.mp4(src, f, { job.cancel.get() }) { pr -> job.progress = pr; notify() }
                        if (kind == "ve") f else AnShare.publish(ctx, f, "video/mp4")
                    }
                }
            }
            AnFxDraw.releaseThread()
            main.post {
                p.job = null
                if (alive) hideProgress()
                r.onSuccess { res ->
                    when (res) {
                        is AnResult -> { p.lastResult = res; a.toast("Tersimpan: ${res.label}") }
                        is File -> StudioHandoff.toVideoEditor(a, res, name, isImage = false, openAfter = alive)
                    }
                    if (alive && panelKey == "ekspor") renderPanel("ekspor")
                }.onFailure { e ->
                    when (e) {
                        is AnEncode.Cancelled -> a.toast("Ekspor dibatalkan")
                        is OutOfMemoryError -> a.toast("Ekspor gagal: memori tidak cukup — pilih resolusi lebih kecil")
                        else -> a.toast("Ekspor gagal: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }.start()
    }

    private fun attachJob(job: AnJob) {
        showProgress(job)
        job.listener = {
            if (alive) {
                progressBar?.progress = job.progress
                progressText?.text = "${job.progress}%"
            }
        }
    }

    private fun showProgress(job: AnJob) {
        hideProgress()
        val wrap = FrameLayout(a).apply { setBackgroundColor(c.scrim); isClickable = true }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = c.shape(c.surface, 20, c.stroke)
            elevation = dp(6).toFloat()
        }
        card.addView(kit.text(job.label, 14f, c.ink, true))
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = job.progress }
        card.addView(bar, LinearLayout.LayoutParams(-1, dp(20)).apply { topMargin = dp(12) })
        val pct = kit.text("${job.progress}%", 12f, c.muted).apply { setPadding(0, dp(6), 0, 0) }
        card.addView(pct)
        card.addView(kit.secondaryButton("Batal") { job.cancel.set(true); pct.text = "Membatalkan…" },
            LinearLayout.LayoutParams(-2, dp(38)).apply { topMargin = dp(10); gravity = Gravity.END })
        wrap.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = dp(28); rightMargin = dp(28) })
        root.addView(wrap, FrameLayout.LayoutParams(-1, -1))
        progressView = wrap
        progressBar = bar
        progressText = pct
    }

    private fun hideProgress() {
        progressView?.let { root.removeView(it) }
        progressView = null
        progressBar = null
        progressText = null
    }

    // ================================================================== dock

    private fun buildDock() {
        val scroll = HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = c.shape(c.surface, 0)
        }
        dock.orientation = LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER_VERTICAL
        dock.setPadding(dp(4), 0, dp(4), 0)
        scroll.addView(dock, FrameLayout.LayoutParams(-2, -1))
        column.addView(scroll, LinearLayout.LayoutParams(-1, dp(64)))
        cell("sumber", "folder-open-outline", "Sumber") { showSourceMenu() }
        cell("layer", "layers-outline", "Layer") { togglePanel("layer") }
        cell("kunci", "rhombus-outline", "Keyframe") { togglePanel("kunci") }
        cell("preset", "animation-play-outline", "Preset") { togglePanel("preset") }
        cell("teks", "format-text", "Teks") { togglePanel("teks") }
        cell("grafik", "chart-bell-curve", "Grafik") { togglePanel("grafik") }
        cell("efek", "auto-fix", "Efek") { togglePanel("efek") }
        cell("durasi", "timer-outline", "Durasi") { togglePanel("durasi") }
        cell("ekspor", "export-variant", "Ekspor") { togglePanel("ekspor") }
        renderDock()
    }

    private fun cell(key: String, iconName: String, label: String, onClick: () -> Unit) {
        val v = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
        }
        v.addView(kit.icon(iconName, 21f, c.ink), LinearLayout.LayoutParams(-1, dp(25)))
        v.addView(kit.text(label, 10f, c.ink).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(2), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        dock.addView(v, LinearLayout.LayoutParams(dp(66), dp(54)).apply { setMargins(dp(1), 0, dp(1), 0) })
        dockCells[key] = v
    }

    private fun renderDock() {
        kit.styleNav(dockCells, panelKey)
        val has = p.doc != null
        for ((k, v) in dockCells) v.alpha = if (has || k == "sumber") 1f else 0.4f
    }

    private fun togglePanel(key: String) { if (panelKey == key) closePanel() else openPanel(key) }

    // ================================================================== sumber & proyek

    private fun showSourceMenu() {
        kit.menu("Animasikan", listOf(
            "Desain Foto Studio saat ini" to { openCurrentStudio() },
            "Proyek Foto Studio tersimpan…" to { openStudioProjectDialog() },
            "Foto (satu layer)…" to { pickPhoto() },
            "Buka proyek animasi…" to { openAnimProjectDialog() }
        ))
    }

    private fun showMenu() {
        val items = mutableListOf<Pair<String, () -> Unit>>(
            "Sumber desain…" to { showSourceMenu() },
            "Simpan proyek" to { saveProject(overwrite = true) },
            "Simpan sebagai…" to { askName("Simpan sebagai", p.name + " salinan") { n -> p.name = n; saveProject(overwrite = false) } },
            "Buka proyek animasi…" to { openAnimProjectDialog() },
            "Ganti nama…" to { askName("Nama animasi", p.name) { n -> p.name = n; changed() } }
        )
        if (p.keyCount() > 0) items.add("Hapus semua keyframe" to { checkpoint(); p.tracks.clear(); changed() })
        if (p.opts.isNotEmpty()) items.add("Reset rentang, sembunyi/kunci & animasi teks" to { checkpoint(); p.opts.clear(); changed() })
        items.add("Bantuan" to { showHelp() })
        kit.menu(p.name, items)
    }

    private fun replaceDoc(d: CsDocData, name: String, keep: Map<String, List<AnKey>> = emptyMap(), duration: Long = 5_000L, path: String? = null, opts: Map<String, AnLayerOpt> = emptyMap()) {
        stopPlay()
        val stashed = AnStore.stashIfNeeded(a)
        p.setDoc(d, name, keep, duration, path, opts)
        dismissOverlay()
        syncDoc()
        AnStore.autosave(a)
        if (stashed != null) a.toast("Animasi sebelumnya disimpan: $stashed")
    }

    private fun openCurrentStudio() {
        val d = currentStudioDoc(a)
        if (d == null) { a.toast("Foto Studio belum punya desain — buat dulu atau pilih foto"); return }
        replaceDoc(d, d.name)
    }

    private fun openStudioProjectDialog() {
        val files = CsStore.listProjects(a)
        if (files.isEmpty()) { a.toast("Belum ada proyek Foto Studio tersimpan"); return }
        a.themedDialog().setTitle("Proyek Foto Studio")
            .setItems(files.map { it.nameWithoutExtension }.toTypedArray()) { _, i ->
                val d = loadStudioProject(a, files[i])
                if (d == null || d.elements.isEmpty()) a.toast("Proyek kosong atau rusak") else replaceDoc(d, d.name)
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun pickPhoto() {
        a.pickImageForTool(CS_PICK_IMAGE) { uri ->
            if (uri == null) return@pickImageForTool
            a.toast("Memuat foto…")
            Thread {
                val r = runCatching { a.importPhotoAsDoc(uri) }
                a.runOnUiThread {
                    if (!alive) return@runOnUiThread
                    r.onSuccess { d -> replaceDoc(d, d.name) }
                        .onFailure { e -> a.toast(if (e is OutOfMemoryError) "Foto terlalu besar untuk memori" else "Foto tidak bisa dibaca: ${e.message}") }
                }
            }.start()
        }
    }

    private fun openAnimProjectDialog() {
        val files = AnStore.listProjects(a)
        if (files.isEmpty()) { a.toast("Belum ada proyek animasi tersimpan"); return }
        val fmt = java.text.SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())
        a.themedDialog().setTitle("Proyek animasi")
            .setItems(files.map { "${it.nameWithoutExtension}\n${fmt.format(java.util.Date(it.lastModified()))}" }.toTypedArray()) { _, i ->
                val l = runCatching { AnStore.parse(a, files[i].readText()) }.getOrNull()
                if (l == null) a.toast("Proyek rusak atau tidak bisa dibaca")
                else replaceDoc(l.doc, l.name, l.tracks, l.durationMs, files[i].absolutePath, l.opts)
            }
            .setNeutralButton("Hapus…") { _, _ -> deleteAnimProjects(files) }
            .setNegativeButton("Batal", null).show()
    }

    private fun deleteAnimProjects(files: List<File>) {
        val checked = BooleanArray(files.size)
        a.themedDialog().setTitle("Hapus proyek animasi")
            .setMultiChoiceItems(files.map { it.nameWithoutExtension }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                var n = 0
                files.forEachIndexed { i, f -> if (checked[i] && f.delete()) n++ }
                if (p.file != null && !File(p.file!!).exists()) p.file = null
                a.toast("$n proyek dihapus")
            }.show()
    }

    private fun saveProject(overwrite: Boolean) {
        if (p.doc == null) return
        val target = if (overwrite) p.file?.let { File(it) } else null
        val file = target ?: AnStore.uniqueFile(AnStore.projectsDir(a), p.name)
        val text = AnStore.toJsonText(p) ?: return
        p.file = file.absolutePath
        Thread {
            val r = runCatching { CsStore.writeAtomic(file, text) }
            a.runOnUiThread {
                r.onSuccess { a.toast("Proyek disimpan: ${file.nameWithoutExtension}") }.onFailure { a.toast("Gagal menyimpan: ${it.message}") }
            }
        }.start()
        AnStore.autosave(a)
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(a).apply { setText(initial); setSelectAllOnFocus(true); setSingleLine(true) }
        val wrap = FrameLayout(a).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, FrameLayout.LayoutParams(-1, -2)) }
        val dlg = a.themedDialog().setTitle(title).setView(wrap)
            .setNegativeButton("Batal", null)
            .setPositiveButton("OK") { _, _ -> onOk(input.text?.toString()?.trim().orEmpty().take(40).ifBlank { "Animasi" }) }
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
    }

    private fun showHelp() {
        a.themedDialog().setTitle(ANIM_STUDIO_PAGE).setMessage(
            "• Sumber: desain Foto Studio saat ini, proyek Studio tersimpan, atau satu foto. Dari Foto Studio: Ekspor › Kirim ke › Animation Studio.\n" +
                "• Ketuk objek untuk memilih. Seret objek = keyframe posisi di playhead. Panel Keyframe: geser X/Y, putar, skala, opasitas, easing.\n" +
                "• Nilai keyframe adalah offset dari desain asli: semua nol / 100% = persis seperti desain.\n" +
                "• Timeline: seret untuk menggeser playhead. Belah ketupat = keyframe layer terpilih; garis kecil = keyframe layer lain.\n" +
                "• Preset (fade, geser masuk, pop, zoom, putar) membuat keyframe mulai dari playhead.\n" +
                "• Ekspor MP4 H.264 30 fps (Movies/GITLS) atau GIF (Pictures/GITLS); \"Ke Video Editor\" menambahkan hasil MP4 sebagai klip.\n" +
                "• Proyek tersimpan otomatis (files/design/anim). Menu ⋮ untuk simpan/buka proyek."
        ).setPositiveButton("OK", null).show()
    }

    // ================================================================== kartu awal

    private fun showStartCard() {
        dismissOverlay()
        val wrap = FrameLayout(a).apply { setBackgroundColor(c.scrim); isClickable = true }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = c.shape(c.surface, 22, c.stroke)
            elevation = dp(6).toFloat()
            isClickable = true
        }
        card.addView(kit.icon("motion-play-outline", 40f, c.ink), LinearLayout.LayoutParams(dp(64), dp(56)))
        card.addView(kit.text("Animasikan desain", 16f, c.ink, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) })
        card.addView(kit.text("Keyframe posisi, putar, skala, dan opasitas per layer.\nEkspor MP4 atau GIF.", 12f, c.muted).apply { gravity = Gravity.CENTER })
        val hasStudio = currentStudioDoc(a) != null
        if (hasStudio) card.addView(kit.primaryButton("Desain Foto Studio saat ini") { openCurrentStudio() }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(14) })
        card.addView(kit.secondaryButton("Proyek Foto Studio…") { openStudioProjectDialog() }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(8) })
        card.addView((if (hasStudio) kit.secondaryButton("Pilih foto…") { pickPhoto() } else kit.primaryButton("Pilih foto…") { pickPhoto() }),
            LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(8) })
        if (AnStore.listProjects(a).isNotEmpty()) {
            card.addView(kit.secondaryButton("Buka proyek animasi…") { openAnimProjectDialog() }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(8) })
        }
        wrap.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = dp(24); rightMargin = dp(24) })
        stageFrame.addView(wrap, FrameLayout.LayoutParams(-1, -1))
        overlay = wrap
        OverlayBack.push(overlayBack)
    }

    private fun dismissOverlay() {
        overlay?.let { stageFrame.removeView(it) }
        overlay = null
        OverlayBack.remove(overlayBack)
    }

    // ================================================================== timeline

    /**
     * Timeline multi-layer: penggaris + satu baris per layer (atas = layer teratas). Kolom kiri:
     * mata (sembunyi), gembok (kunci), nama layer; di penggaris kolom kiri: tombol zoom −/+.
     * Area waktu: seret penggaris = playhead; seret belah ketupat = pindah keyframe (snap 50 ms);
     * seret ujung bilah = rentang aktif in/out; seret area kosong = gulir; cubit = zoom.
     */
    private inner class AnTracksView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(9).toFloat(); color = c.muted }
        private val nameText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11).toFloat(); color = c.ink }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(15).toFloat(); textAlign = Paint.Align.CENTER
            PeLayerRender.mdi(ctx)?.let { typeface = it }
        }
        private val diamond = Path()
        private val headerW = dp(96).toFloat()
        private val rulerH = dp(24).toFloat()
        private val rowH = dp(30).toFloat()
        private val padL = dp(10).toFloat()
        private val padR = dp(14).toFloat()
        private var zoom = 1f
        private var scrollXPx = 0f
        private var scrollYPx = 0f

        // Gestur
        private val modeNone = 0
        private val modeScrub = 1
        private val modeKey = 2
        private val modeIn = 3
        private val modeOut = 4
        private val modePan = 5
        private val modeHeader = 6
        private var mode = modeNone
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var moved = false
        private var dragId: String? = null
        private var dragKeys: List<AnKey> = emptyList()
        private var dragIndex = -1
        private var dragOpt: AnLayerOpt = AnLayerOpt.DEFAULT
        private var dragLastT = -1L
        private var checkpointed = false
        private val slop = dp(6).toFloat()

        private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomAround(zoom * detector.scaleFactor, detector.focusX)
                return true
            }
        })

        init {
            setBackgroundColor(c.surface)
            contentDescription = "Timeline animasi multi-layer"
        }

        private fun rows(): List<CsElement> = p.doc?.elements?.asReversed() ?: emptyList()
        private fun areaW(): Float = (width - headerW - padL - padR).coerceAtLeast(1f)
        private fun pxPerMs(): Float = AnTimeline.pxPerMs(areaW(), p.durationMs, zoom)
        private fun maxScrollX(): Float = max(0f, p.durationMs * pxPerMs() - areaW())
        private fun maxScrollY(): Float = max(0f, rows().size * rowH - (height - rulerH))
        private fun xOf(t: Long): Float = headerW + padL + t * pxPerMs() - scrollXPx
        private fun tOf(x: Float): Long = ((x - headerW - padL + scrollXPx) / pxPerMs()).roundToLong().coerceIn(0L, p.durationMs)
        private fun rowAt(y: Float): Int = if (y < rulerH) -1 else ((y - rulerH + scrollYPx) / rowH).toInt()
        private fun rowTop(i: Int): Float = rulerH + i * rowH - scrollYPx

        private fun clampScroll() {
            scrollXPx = scrollXPx.coerceIn(0f, maxScrollX())
            scrollYPx = scrollYPx.coerceIn(0f, maxScrollY())
        }

        /** Zoom dengan waktu di bawah [focusX] tetap di tempat. */
        fun zoomAround(z: Float, focusX: Float) {
            val fx = focusX.coerceIn(headerW + padL, width - padR)
            val tf = (fx - headerW - padL + scrollXPx) / pxPerMs()
            zoom = z.coerceIn(1f, 40f)
            scrollXPx = tf * pxPerMs() - (fx - headerW - padL)
            clampScroll()
            invalidate()
        }

        /** Gulir agar playhead terlihat (mis. saat diputar). */
        private fun followPlayhead() {
            val x = xOf(p.playheadMs)
            if (x < headerW + padL) scrollXPx -= headerW + padL - x
            else if (x > width - padR) scrollXPx += x - (width - padR)
            clampScroll()
        }

        private fun drawGlyph(canvas: Canvas, name: String, x: Float, cy: Float, color: Int) {
            glyph.color = color
            val fm = glyph.fontMetrics
            canvas.drawText(MdiGlyphs.glyph(name), x, cy - (fm.ascent + fm.descent) / 2f, glyph)
        }

        private fun drawDiamond(canvas: Canvas, x: Float, cy: Float, r: Float) {
            diamond.reset()
            diamond.moveTo(x, cy - r); diamond.lineTo(x + r, cy); diamond.lineTo(x, cy + r); diamond.lineTo(x - r, cy); diamond.close()
            canvas.drawPath(diamond, paint)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = p.doc
            clampScroll()
            if (playing) followPlayhead()
            val dur = p.durationMs.coerceAtLeast(1L)
            val w = width.toFloat()
            val h = height.toFloat()
            paint.style = Paint.Style.FILL
            // ---- baris layer
            canvas.save()
            canvas.clipRect(0f, rulerH, w, h)
            val list = rows()
            for ((i, el) in list.withIndex()) {
                val top = rowTop(i)
                if (top > h || top + rowH < rulerH) continue
                val cy = top + rowH / 2f
                val o = p.opt(el.id)
                val sel = el.id == p.selectedId
                val dim = o.hidden || !el.visible
                if (sel) {
                    paint.color = c.surface2
                    canvas.drawRect(0f, top, w, top + rowH, paint)
                }
                paint.color = c.stroke
                paint.strokeWidth = 1f
                canvas.drawLine(0f, top + rowH, w, top + rowH, paint)
                // Kolom kiri: mata, gembok, nama.
                drawGlyph(canvas, if (o.hidden) "eye-off-outline" else "eye-outline", dp(14).toFloat(), cy, if (o.hidden) c.muted else c.ink)
                drawGlyph(canvas, if (o.locked) "lock-outline" else "lock-open-outline", dp(36).toFloat(), cy, if (o.locked) c.ink else c.muted)
                nameText.color = if (dim) c.muted else c.ink
                nameText.isFakeBoldText = sel
                val nm = TextUtils.ellipsize(layerName(el), nameText, headerW - dp(52), TextUtils.TruncateAt.END)
                canvas.drawText(nm, 0, nm.length, dp(50).toFloat(), cy + dp(4), nameText)
                // Area waktu.
                canvas.save()
                canvas.clipRect(headerW, top, w, top + rowH)
                val x0 = xOf(o.inMs)
                val x1 = xOf(o.outOr(dur))
                paint.style = Paint.Style.FILL
                paint.color = c.stroke
                paint.alpha = if (dim) 90 else 255
                canvas.drawRoundRect(x0, cy - dp(9), x1, cy + dp(9), dp(5).toFloat(), dp(5).toFloat(), paint)
                // Pegangan rentang (in/out).
                paint.color = if (o.locked) c.muted else c.ink
                paint.alpha = if (dim) 120 else 255
                canvas.drawRoundRect(x0, cy - dp(9), x0 + dp(4), cy + dp(9), dp(2).toFloat(), dp(2).toFloat(), paint)
                canvas.drawRoundRect(x1 - dp(4), cy - dp(9), x1, cy + dp(9), dp(2).toFloat(), dp(2).toFloat(), paint)
                // Rentang animasi teks (garis tipis di bawah bilah).
                o.text?.let { ta ->
                    paint.color = c.ink
                    paint.alpha = 150
                    canvas.drawRect(xOf(ta.startMs), cy + dp(10), xOf(min(dur, ta.startMs + ta.durMs)).coerceAtLeast(xOf(ta.startMs) + dp(2)), cy + dp(12), paint)
                }
                val keys = p.tracks[el.id] ?: emptyList()
                if (keys.size >= 2) {
                    paint.color = c.muted
                    paint.alpha = if (dim) 120 else 255
                    paint.strokeWidth = dp(2).toFloat()
                    canvas.drawLine(xOf(keys.first().t), cy, xOf(keys.last().t), cy, paint)
                }
                for (k in keys) {
                    val onHead = sel && abs(k.t - p.playheadMs) <= frameTol
                    paint.style = Paint.Style.FILL
                    paint.color = if (onHead) c.activeFill else c.ink
                    paint.alpha = if (dim) 120 else 255
                    drawDiamond(canvas, xOf(k.t), cy, dp(if (sel) 6 else 5).toFloat())
                    if (onHead) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = dp(2).toFloat()
                        paint.color = c.surface
                        drawDiamond(canvas, xOf(k.t), cy, dp(6).toFloat())
                        paint.style = Paint.Style.FILL
                    }
                }
                paint.alpha = 255
                canvas.restore()
            }
            if (d == null || list.isEmpty()) canvas.drawText("Belum ada layer", headerW + padL, rulerH + dp(18), text)
            canvas.restore()
            // ---- penggaris
            paint.style = Paint.Style.FILL
            paint.color = c.surface
            canvas.drawRect(0f, 0f, w, rulerH, paint)
            paint.color = c.stroke
            paint.strokeWidth = 1f
            canvas.drawLine(0f, rulerH, w, rulerH, paint)
            canvas.drawLine(headerW, 0f, headerW, h, paint)
            drawGlyph(canvas, "magnify-minus-outline", dp(20).toFloat(), rulerH / 2f, if (zoom > 1f) c.ink else c.muted)
            drawGlyph(canvas, "magnify-plus-outline", dp(48).toFloat(), rulerH / 2f, if (zoom < 40f) c.ink else c.muted)
            canvas.drawText("${(zoom * 10).roundToInt() / 10f}×", dp(64).toFloat(), rulerH / 2f + dp(3), text)
            canvas.save()
            canvas.clipRect(headerW, 0f, w, h)
            // Label tiap 0,1/0,5/1/2/5/10 s sesuai kerapatan; garis kecil tiap langkah.
            val pxPerS = pxPerMs() * 1000f
            val stepMs = when {
                pxPerS >= dp(400) -> 100L; pxPerS >= dp(120) -> 500L; pxPerS >= dp(40) -> 1000L
                pxPerS >= dp(20) -> 2000L; pxPerS >= dp(8) -> 5000L; else -> 10_000L
            }
            val tick = if (stepMs <= 500L) stepMs / 5 else stepMs / 2
            var tt = (tOf(headerW) / tick) * tick
            while (tt <= dur) {
                val x = xOf(tt)
                if (x > w) break
                paint.color = c.muted
                val major = tt % stepMs == 0L
                canvas.drawLine(x, rulerH - dp(if (major) 7 else 3), x, rulerH, paint)
                if (major) canvas.drawText(if (stepMs < 1000L) String.format(Locale.US, "%.1fs", tt / 1000f) else "${tt / 1000}s", x + dp(2), rulerH - dp(9), text)
                tt += tick
            }
            // Playhead
            val px = xOf(p.playheadMs)
            paint.color = c.ink
            paint.strokeWidth = dp(2).toFloat()
            canvas.drawLine(px, 0f, px, h, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(px, dp(4).toFloat(), dp(4).toFloat(), paint)
            canvas.restore()
        }

        /** Keyframe terdekat pada baris [i] dalam radius sentuh: indeks atau −1. */
        private fun keyHit(el: CsElement, x: Float): Int {
            val keys = p.tracks[el.id] ?: return -1
            var best = -1
            var bestD = dp(14).toFloat()
            for ((k, key) in keys.withIndex()) {
                val dd = abs(xOf(key.t) - x)
                if (dd <= bestD) { best = k; bestD = dd }
            }
            return best
        }

        private fun tick() { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

        private fun startDragCheckpoint() {
            if (!checkpointed) { stopPlay(); checkpoint(); checkpointed = true }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (p.doc == null) return false
            scaler.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopPlay()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                    moved = false
                    checkpointed = false
                    dragId = null
                    dragIndex = -1
                    dragLastT = -1L
                    val list = rows()
                    val ri = rowAt(event.y)
                    mode = when {
                        event.x < headerW -> modeHeader
                        ri < 0 -> { seek(tOf(event.x), rerender = false); modeScrub }
                        ri >= list.size -> modePan
                        else -> {
                            val el = list[ri]
                            val o = p.opt(el.id)
                            dragId = el.id
                            dragOpt = o
                            val k = keyHit(el, event.x)
                            when {
                                k >= 0 -> { dragKeys = p.tracks[el.id] ?: emptyList(); dragIndex = k; modeKey }
                                abs(event.x - xOf(o.inMs)) <= dp(10) -> modeIn
                                abs(event.x - xOf(o.outOr(p.durationMs))) <= dp(10) -> modeOut
                                else -> modePan
                            }
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Dua jari = zoom: batalkan seret yang sedang berjalan.
                    if (checkpointed) changed()
                    mode = modeNone
                    moved = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1 || mode == modeNone) return true
                    if (!moved && abs(event.x - downX) + abs(event.y - downY) > slop) moved = true
                    if (!moved) return true
                    val id = dragId
                    val locked = id != null && p.opt(id).locked
                    if (locked && (mode == modeKey || mode == modeIn || mode == modeOut)) {
                        a.toast("Track terkunci — ketuk ikon gembok untuk membuka")
                        mode = modePan
                    }
                    when (mode) {
                        modeScrub -> seek(tOf(event.x), rerender = false)
                        modeKey -> if (id != null) {
                            val next = AnTimeline.moveKey(dragKeys, dragIndex, tOf(event.x), p.durationMs)
                            val nt = AnTimeline.snap(tOf(event.x)).coerceIn(0L, p.durationMs)
                            if (next != null && nt != dragLastT) {
                                startDragCheckpoint()
                                setKeys(id, next)
                                dragLastT = nt
                                tick()
                                seek(nt, rerender = false)
                                updateChrome()
                            }
                        }
                        modeIn, modeOut -> if (id != null) {
                            val n = if (mode == modeIn) AnTimeline.withIn(dragOpt, tOf(event.x), p.durationMs)
                            else AnTimeline.withOut(dragOpt, tOf(event.x), p.durationMs)
                            val nt = if (mode == modeIn) n.inMs else n.outOr(p.durationMs)
                            if (nt != dragLastT) {
                                startDragCheckpoint()
                                p.setOpt(id, n)
                                dragLastT = nt
                                tick()
                                seek(nt, rerender = false)
                            }
                        }
                        modePan, modeHeader -> {
                            if (mode == modePan) scrollXPx -= event.x - lastX
                            scrollYPx -= event.y - lastY
                            clampScroll()
                            invalidate()
                        }
                    }
                    lastX = event.x; lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap(event.x, event.y)
                    else if (checkpointed) changed()
                    else if (panelKey == "kunci" || panelKey == "preset") renderPanel(panelKey!!)
                    mode = modeNone
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (checkpointed) changed()
                    mode = modeNone
                }
            }
            return true
        }

        private fun onTap(x: Float, y: Float) {
            val list = rows()
            val ri = rowAt(y)
            if (ri < 0) {
                if (x < headerW) {
                    if (x < dp(34)) zoomAround(zoom / 1.6f, (headerW + width) / 2f)
                    else if (x < dp(62)) zoomAround(zoom * 1.6f, xOf(p.playheadMs))
                } else if (panelKey == "kunci" || panelKey == "preset") renderPanel(panelKey!!)
                return
            }
            if (ri >= list.size) return
            val el = list[ri]
            if (x < headerW) {
                when {
                    x < dp(25) -> { editOpt(el.id) { it.copy(hidden = !it.hidden) }; tick() }
                    x < dp(47) -> { editOpt(el.id) { it.copy(locked = !it.locked) }; tick() }
                    else -> { p.selectedId = el.id; onSelectionChanged() }
                }
                return
            }
            // Ketuk dekat belah ketupat = lompat tepat ke keyframe itu; selain itu pilih layer + geser playhead.
            val k = keyHit(el, x)
            val keys = p.tracks[el.id] ?: emptyList()
            if (p.selectedId != el.id) { p.selectedId = el.id; onSelectionChanged() }
            seek(if (k >= 0) keys[k].t else tOf(x))
        }
    }
}
