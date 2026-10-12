package com.example.aidetest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Presentation Studio — dek slide berbasis kanvas Foto Studio:
 *  - Daftar slide: tambah (kosong / desain Studio / proyek Studio), duplikat, hapus, geser urutan
 *  - Editor slide fokus: pilih & seret layer, teks, bentuk, foto, stiker, warna, putar, opasitas, urutan
 *  - Transisi per slide (tanpa/fade/geser), animasi masuk per objek (preset Animasi) + pratinjau
 *  - Ukuran 16:9 / 4:3 / 9:16, mode presentasi layar penuh (ketuk/geser untuk maju, Back untuk keluar)
 *  - Undo/redo, simpan otomatis + proyek (files/design/present), ekspor PDF & MP4 slideshow
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n", "ViewConstructor")
internal class PresentStudioUi(private val a: MainActivity) {
    private val kit = ImgKit(a)
    private val c = kit.c
    private val d = PrDeck
    private val handler = Handler(Looper.getMainLooper())
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    val root = FrameLayout(a)
    private val column = LinearLayout(a)
    private val topBar = LinearLayout(a)
    private val titleView = TextView(a)
    private val subtitleView = TextView(a)
    private val undoBtn: MdiIconView
    private val redoBtn: MdiIconView
    private val stageFrame = FrameLayout(a)
    private val stage = AnStageView(a, c)
    private val stripScroll = HorizontalScrollView(a)
    private val strip = LinearLayout(a)
    private val panelHost = LinearLayout(a)
    private val panelTitle = TextView(a)
    private val panelScroll = EdMaxScroll(a)
    private val panelBody = LinearLayout(a)
    private val dock = LinearLayout(a)
    private val dockCells = LinkedHashMap<String, LinearLayout>()
    private var panelKey: String? = null
    private var alive = true
    private var dragStart: Pair<Float, Float>? = null
    private var previewTracks: Map<String, List<AnKey>>? = null
    private var previewStart = 0L
    private var previewT = 0L
    private var progressView: View? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var presenter: PrPresentView? = null
    private var savedUiFlags = 0
    private val images = AnPreviewImages { if (alive) { stage.invalidate(); invalidateThumbs(); presenter?.invalidate() } }

    private val panelBack: () -> Boolean = { if (panelKey != null && root.isAttachedToWindow) { closePanel(); true } else false }
    private val presentBack: () -> Boolean = { if (presenter != null && root.isAttachedToWindow) { exitPresent(); true } else false }
    private val autosaveTask = Runnable { PrStore.autosave(a) }
    private val previewCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val tr = previewTracks ?: return
            if (!alive) return
            previewT = (frameTimeNanos - previewStart) / 1_000_000L
            stage.invalidate()
            if (previewT > AnPresets.tracksEnd(tr) + 300L) stopPreview() else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun dp(v: Int): Int = a.dp(v)

    init {
        CsPipeline.init(a)
        PrStore.ensureLoaded(a)
        if (d.slides.isEmpty()) d.slides.add(d.blankSlide())
        d.current = d.current.coerceIn(0, d.slides.size - 1)
        root.setBackgroundColor(stage.workspace)
        column.orientation = LinearLayout.VERTICAL
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        undoBtn = kit.icon("undo", 22f, c.ink, "Undo") { undo() }
        redoBtn = kit.icon("redo", 22f, c.ink, "Redo") { redo() }
        buildTopBar()
        column.addView(topBar, LinearLayout.LayoutParams(-1, dp(52)))

        stage.images = images
        stage.state = { el ->
            val tr = previewTracks
            if (tr != null) tr[el.id]?.let { AnMath.sample(it, previewT) } ?: AnState.IDENTITY else AnState.IDENTITY
        }
        stage.onSelect = { id -> d.selectedId = id; onSelectionChanged() }
        stage.onDragStart = { id -> stopPreview(); checkpoint(); find(id)?.let { dragStart = it.x to it.y } }
        stage.onDrag = { id, dx, dy ->
            val s = dragStart
            val el = find(id)
            if (s != null && el != null) { el.x = (s.first + dx).roundToInt().toFloat(); el.y = (s.second + dy).roundToInt().toFloat() }
        }
        stage.onDragEnd = { _ -> dragStart = null; changed() }
        stageFrame.addView(stage, FrameLayout.LayoutParams(-1, -1))
        column.addView(stageFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        stripScroll.isHorizontalScrollBarEnabled = false
        stripScroll.overScrollMode = View.OVER_SCROLL_NEVER
        stripScroll.background = c.shape(c.surface, 0)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        strip.setPadding(dp(8), 0, dp(8), 0)
        stripScroll.addView(strip, FrameLayout.LayoutParams(-2, -1))
        column.addView(View(a).apply { setBackgroundColor(c.stroke) }, LinearLayout.LayoutParams(-1, 1))
        column.addView(stripScroll, LinearLayout.LayoutParams(-1, dp(76)))

        buildPanelHost()
        column.addView(panelHost, LinearLayout.LayoutParams(-1, -2))
        column.addView(View(a).apply { setBackgroundColor(c.stroke) }, LinearLayout.LayoutParams(-1, 1))
        buildDock()

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                alive = false
                images.alive = false
                stopPreview()
                if (presenter != null) exitPresent()
                OverlayBack.remove(panelBack)
                OverlayBack.remove(presentBack)
                d.job?.listener = null
                handler.removeCallbacks(autosaveTask)
                PrStore.autosave(a)
            }
        })
        syncSlide()
        d.job?.let { attachJob(it) }
    }

    // ================================================================== dasar

    private fun slide(): PrSlide {
        if (d.slides.isEmpty()) d.slides.add(d.blankSlide())
        d.current = d.current.coerceIn(0, d.slides.size - 1)
        return d.slides[d.current]
    }

    private fun find(id: String?): CsElement? = id?.let { i -> slide().elements.firstOrNull { it.id == i } }

    private fun checkpoint() {
        d.undo.add(PrStore.encode())
        if (d.undo.size > 30) d.undo.removeAt(0)
        d.redo.clear()
    }

    private fun restoreFrom(text: String) {
        val sel = d.selectedId
        runCatching { PrStore.decode(a, text) }.onFailure { a.toast("Gagal memulihkan: ${it.message}") }
        d.selectedId = sel
        syncSlide()
        scheduleAutosave()
    }

    private fun undo() {
        if (d.undo.isEmpty()) return
        d.redo.add(PrStore.encode())
        restoreFrom(d.undo.removeAt(d.undo.size - 1))
    }

    private fun redo() {
        if (d.redo.isEmpty()) return
        d.undo.add(PrStore.encode())
        restoreFrom(d.redo.removeAt(d.redo.size - 1))
    }

    private fun scheduleAutosave() {
        handler.removeCallbacks(autosaveTask)
        handler.postDelayed(autosaveTask, 1000L)
    }

    /** Konten slide berubah (tanpa perubahan struktur daftar slide). */
    private fun changed(rerender: Boolean = true) {
        updateChrome()
        stage.invalidate()
        invalidateThumbs()
        if (rerender) panelKey?.let { renderPanel(it) }
        scheduleAutosave()
    }

    /** Slide aktif / daftar slide berubah. */
    private fun syncSlide() {
        val s = slide()
        stage.doc = d.doc(s)
        if (find(d.selectedId) == null) d.selectedId = null
        stage.selectedId = d.selectedId
        images.retain(d.slides.flatMap { sl -> sl.elements.map { it.id } }.toSet())
        rebuildStrip()
        updateChrome()
        stage.invalidate()
        panelKey?.let { renderPanel(it) }
    }

    private fun onSelectionChanged() {
        stage.selectedId = d.selectedId
        stage.invalidate()
        if (d.selectedId != null && (panelKey == null || panelKey == "slide" || panelKey == "ukuran")) openPanel("objek")
        else panelKey?.let { renderPanel(it) }
    }

    private fun updateChrome() {
        titleView.text = d.name
        subtitleView.text = "Slide ${d.current + 1}/${d.slides.size} · ${d.sizeKey} · ${d.w}×${d.h}"
        undoBtn.alpha = if (d.undo.isEmpty()) 0.35f else 1f
        redoBtn.alpha = if (d.redo.isEmpty()) 0.35f else 1f
    }

    // ================================================================== top bar

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
            contentDescription = "Ukuran slide"
            background = c.pressable(Color.TRANSPARENT, 10)
            setOnClickListener { openPanel("ukuran") }
        }
        titleView.apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c.ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false }
        subtitleView.apply { textSize = 11f; setTextColor(c.muted); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false; setPadding(0, dp(3), 0, 0) }
        titles.addView(titleView)
        titles.addView(subtitleView)
        topBar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(undoBtn, LinearLayout.LayoutParams(dp(40), dp(44)))
        topBar.addView(redoBtn, LinearLayout.LayoutParams(dp(40), dp(44)))
        topBar.addView(kit.icon("dots-vertical", 22f, c.ink, "Menu proyek") { showMenu() }, LinearLayout.LayoutParams(dp(38), dp(44)))
        topBar.addView(kit.primaryButton("Tampilkan") { startPresent() }.apply { contentDescription = "Mulai presentasi layar penuh" },
            LinearLayout.LayoutParams(-2, dp(36)).apply { leftMargin = dp(4) })
    }

    // ================================================================== daftar slide

    private fun rebuildStrip() {
        strip.removeAllViews()
        for ((i, s) in d.slides.withIndex()) {
            val th = PrThumb(a, s, i)
            strip.addView(th, LinearLayout.LayoutParams(thumbW(), dp(60)).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        val add = kit.icon("plus", 24f, c.ink, "Tambah slide") { addSlideMenu() }.apply { background = c.pressable(c.surface2, 12, c.stroke) }
        strip.addView(add, LinearLayout.LayoutParams(dp(52), dp(60)).apply { setMargins(dp(3), 0, dp(3), 0) })
        strip.post { strip.getChildAt(d.current)?.let { v -> stripScroll.smoothScrollTo(max(0, v.left - dp(40)), 0) } }
    }

    private fun thumbW(): Int = (dp(60) * d.w.toFloat() / d.h).roundToInt().coerceIn(dp(34), dp(120))

    private fun invalidateThumbs() {
        for (i in 0 until strip.childCount) strip.getChildAt(i).invalidate()
    }

    private fun selectSlide(i: Int) {
        if (i == d.current) return
        stopPreview()
        d.current = i.coerceIn(0, d.slides.size - 1)
        d.selectedId = null
        syncSlide()
        scheduleAutosave()
    }

    private fun addSlideMenu() {
        kit.menu("Tambah slide", listOf(
            "Slide kosong" to { insertSlide(d.blankSlide()) },
            "Desain Foto Studio saat ini" to {
                val doc = currentStudioDoc(a)
                if (doc == null) a.toast("Foto Studio belum punya desain") else insertDoc(doc)
            },
            "Proyek Foto Studio…" to { pickStudioProject() },
            "Foto sebagai slide…" to { pickPhoto(asSlide = true) }
        ))
    }

    private fun insertSlide(s: PrSlide) {
        stopPreview()
        checkpoint()
        val at = (d.current + 1).coerceIn(0, d.slides.size)
        d.slides.add(at, s)
        d.current = at
        d.selectedId = null
        syncSlide()
        scheduleAutosave()
    }

    private fun insertDoc(doc: CsDocData) {
        checkpoint()
        val s = d.appendDoc(doc.withFreshIds())
        // appendDoc menambah di akhir; pindahkan tepat setelah slide aktif.
        d.slides.remove(s)
        val at = (d.current + 1).coerceIn(0, d.slides.size)
        d.slides.add(at, s)
        d.current = at
        d.selectedId = null
        syncSlide()
        scheduleAutosave()
    }

    private fun pickStudioProject() {
        val files = CsStore.listProjects(a)
        if (files.isEmpty()) { a.toast("Belum ada proyek Foto Studio tersimpan"); return }
        a.themedDialog().setTitle("Proyek Foto Studio")
            .setItems(files.map { it.nameWithoutExtension }.toTypedArray()) { _, i ->
                val doc = loadStudioProject(a, files[i])
                if (doc == null) a.toast("Proyek rusak atau tidak bisa dibaca") else insertDoc(doc)
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun slideMenu(i: Int) {
        selectSlide(i)
        val items = mutableListOf<Pair<String, () -> Unit>>(
            "Duplikat" to { duplicateSlide() },
            "Pindah ke kiri" to { moveSlide(-1) },
            "Pindah ke kanan" to { moveSlide(1) },
            "Hapus" to { deleteSlide() }
        )
        kit.menu("Slide ${i + 1}", items)
    }

    private fun duplicateSlide() {
        insertSlide(slide().deepCopy(newIds = true))
    }

    private fun moveSlide(dir: Int) {
        val from = d.current
        val to = AnLayout.moveIndex(from, from + dir, d.slides.size)
        if (to == from) return
        checkpoint()
        val s = d.slides.removeAt(from)
        d.slides.add(to, s)
        d.current = to
        syncSlide()
        scheduleAutosave()
    }

    private fun deleteSlide() {
        if (d.slides.size <= 1) { a.toast("Presentasi minimal punya satu slide"); return }
        checkpoint()
        d.slides.removeAt(d.current)
        d.current = d.current.coerceAtMost(d.slides.size - 1)
        d.selectedId = null
        syncSlide()
        scheduleAutosave()
    }

    // ================================================================== panel

    private fun buildPanelHost() {
        panelHost.orientation = LinearLayout.VERTICAL
        panelHost.background = c.shape(c.surface, 0)
        panelHost.visibility = View.GONE
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(4), 0) }
        panelTitle.apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c.ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        head.addView(panelTitle, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(kit.icon("chevron-down", 22f, c.ink, "Tutup panel") { closePanel() }, LinearLayout.LayoutParams(dp(44), dp(40)))
        panelHost.addView(View(a).apply { setBackgroundColor(c.stroke) }, LinearLayout.LayoutParams(-1, 1))
        panelHost.addView(head, LinearLayout.LayoutParams(-1, dp(42)))
        panelBody.orientation = LinearLayout.VERTICAL
        panelBody.setPadding(dp(12), 0, dp(12), dp(12))
        panelScroll.isVerticalScrollBarEnabled = false
        panelScroll.addView(panelBody, FrameLayout.LayoutParams(-1, -2))
        panelHost.addView(panelScroll, LinearLayout.LayoutParams(-1, -2))
    }

    private fun openPanel(key: String) {
        panelKey = key
        panelHost.visibility = View.VISIBLE
        panelScroll.maxHeightPx = (a.resources.displayMetrics.heightPixels * 0.34f).roundToInt()
        OverlayBack.push(panelBack)
        renderPanel(key)
        kit.styleNav(dockCells, panelKey)
    }

    private fun closePanel() {
        panelKey = null
        panelHost.visibility = View.GONE
        panelBody.removeAllViews()
        OverlayBack.remove(panelBack)
        kit.styleNav(dockCells, null)
    }

    private fun togglePanel(key: String) { if (panelKey == key) closePanel() else openPanel(key) }

    private fun caption(t: String) {
        panelBody.addView(kit.text(t, 10.5f, c.muted, true).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(10), 0, dp(6)) })
    }

    private fun note(t: String) {
        panelBody.addView(kit.text(t, 11.5f, c.muted).apply { setPadding(dp(4), dp(8), dp(4), 0); setLineSpacing(0f, 1.15f) })
    }

    private fun renderPanel(key: String) {
        panelBody.removeAllViews()
        when (key) {
            "slide" -> renderSlidePanel()
            "bentuk" -> renderShapePanel()
            "stiker" -> renderStickerPanel()
            "objek" -> renderObjectPanel()
            "animasi" -> renderAnimPanel()
            "ukuran" -> renderSizePanel()
            "ekspor" -> renderExportPanel()
            else -> closePanel()
        }
    }

    private fun buttonRow(vararg items: Pair<String, () -> Unit>) {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, it) in items.withIndex()) {
            row.addView(kit.secondaryButton(it.first) { it.second() }.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        panelBody.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    private fun renderSlidePanel() {
        val s = slide()
        panelTitle.text = "Slide ${d.current + 1} dari ${d.slides.size}"
        buttonRow("+ Slide kosong" to { insertSlide(d.blankSlide()) }, "Duplikat" to { duplicateSlide() })
        buttonRow("◀ Pindah" to { moveSlide(-1) }, "Pindah ▶" to { moveSlide(1) }, "Hapus" to { deleteSlide() })
        buttonRow("Dari Foto Studio" to {
            val doc = currentStudioDoc(a)
            if (doc == null) a.toast("Foto Studio belum punya desain") else insertDoc(doc)
        }, "Proyek Studio…" to { pickStudioProject() })
        caption("TRANSISI MASUK")
        panelBody.addView(kit.segmented(PrDeck.TRANSITIONS, s.transition) { k ->
            checkpoint(); s.transition = k; changed()
        }, LinearLayout.LayoutParams(-1, dp(38)))
        caption("LATAR")
        val pal = kit.palette()
        panelBody.addView(kit.swatchRow(pal.take(7), s.bg.color) { col -> checkpoint(); s.bg = CsBackground("solid", col); syncSlide(); scheduleAutosave() })
        panelBody.addView(kit.swatchRow(pal.drop(7), s.bg.color) { col -> checkpoint(); s.bg = CsBackground("solid", col); syncSlide(); scheduleAutosave() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        note("Tekan lama gambar mini slide untuk duplikat/pindah/hapus. Transisi diputar saat presentasi & di MP4 slideshow.")
    }

    private fun addElement(el: CsElement) {
        stopPreview()
        checkpoint()
        slide().elements.add(el)
        d.selectedId = el.id
        stage.selectedId = el.id
        changed(rerender = false)
        openPanel("objek")
    }

    private fun addText() {
        val s = min(d.w, d.h)
        val el = CsElement(type = CsType.TEXT, x = d.w * 0.1f, y = d.h * 0.42f, w = d.w * 0.8f, h = s * 0.1f, text = "Teks baru",
            textSize = s * 0.07f, bold = true, color = inkFor(slide()), align = CsAlign.CENTER)
        CsRender.fitText(el, measurePaint)
        el.x = (d.w - el.w) / 2f
        addElement(el)
    }

    /** Warna isi bawaan yang kontras dengan latar slide. */
    private fun inkFor(s: PrSlide): Int {
        val col = s.bg.color
        val lum = (Color.red(col) * 299 + Color.green(col) * 587 + Color.blue(col) * 114) / 1000
        return if (s.bg.mode != "transparent" && lum < 128) Color.WHITE else Color.rgb(20, 20, 24)
    }

    private fun addShape(type: CsType, rounded: Boolean = false) {
        val side = min(d.w, d.h) * 0.3f
        val el = when (type) {
            CsType.LINE -> CsElement(type = CsType.LINE, x = d.w * 0.3f, y = d.h / 2f - 6f, w = d.w * 0.4f, h = max(6f, side * 0.04f), color = inkFor(slide()))
            else -> CsElement(type = type, x = (d.w - side) / 2f, y = (d.h - side) / 2f, w = side, h = side,
                color = Color.rgb(90, 90, 96), cornerRadius = if (rounded) side * 0.16f else 0f)
        }
        addElement(el)
    }

    private fun renderShapePanel() {
        panelTitle.text = "Bentuk"
        panelBody.addView(kit.tileRow(listOf(
            kit.tile("rectangle-outline", "Persegi") { addShape(CsType.RECT) },
            kit.tile("square-rounded-outline", "Bulat") { addShape(CsType.RECT, rounded = true) },
            kit.tile("shape-circle-plus", "Elips") { addShape(CsType.ELLIPSE) },
            kit.tile("triangle-outline", "Segitiga") { addShape(CsType.TRIANGLE) },
            kit.tile("vector-line", "Garis") { addShape(CsType.LINE) }
        )))
    }

    private fun renderStickerPanel() {
        panelTitle.text = "Stiker"
        val icons = listOf("star-outline", "heart-outline", "check-circle", "lightning-bolt", "arrow-right-bold", "trophy", "rocket-launch", "emoticon-happy-outline",
            "thumb-up", "fire", "crown", "map-marker", "bell-outline", "flower", "gift", "music-note")
        for (row in icons.chunked(8)) {
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (ic in row) {
                r.addView(kit.icon(ic, 24f, c.ink, "Stiker $ic") {
                    val side = min(d.w, d.h) * 0.18f
                    addElement(CsElement(type = CsType.STICKER, x = (d.w - side) / 2f, y = (d.h - side) / 2f, w = side, h = side, icon = ic, color = inkFor(slide())))
                }.apply { background = c.pressable(c.surface2, 12) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            panelBody.addView(r)
        }
    }

    private fun pickPhoto(asSlide: Boolean) {
        a.pickImageForTool(CS_PICK_IMAGE) { uri ->
            if (uri == null) return@pickImageForTool
            a.toast("Memuat foto…")
            Thread {
                val r = runCatching { a.importPhotoAsDoc(uri, maxCanvas = 2048) }
                a.runOnUiThread {
                    if (!alive) return@runOnUiThread
                    r.onSuccess { doc ->
                        if (asSlide) insertDoc(doc)
                        else {
                            val src = doc.elements.first()
                            val (ew, eh) = CsMath.fitInside(doc.w.toFloat(), doc.h.toFloat(), d.w.toFloat(), d.h.toFloat(), 0.6f, noUpscale = false)
                            addElement(src.copy(id = UUID.randomUUID().toString(), x = (d.w - ew) / 2f, y = (d.h - eh) / 2f, w = ew, h = eh, fitCanvas = false, name = doc.name.take(28)))
                        }
                    }.onFailure { e -> a.toast(if (e is OutOfMemoryError) "Foto terlalu besar untuk memori" else "Foto tidak bisa dibaca: ${e.message}") }
                }
            }.start()
        }
    }

    private fun editText(el: CsElement) {
        val input = EditText(a).apply {
            setText(el.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 6
        }
        val wrap = FrameLayout(a).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, FrameLayout.LayoutParams(-1, -2)) }
        val dlg = a.themedDialog().setTitle("Edit teks").setView(wrap)
            .setNegativeButton("Batal", null)
            .setPositiveButton("OK") { _, _ ->
                checkpoint()
                el.text = input.text?.toString().orEmpty().ifEmpty { " " }
                CsRender.fitText(el, measurePaint)
                changed()
            }.create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
    }

    private fun scaleElement(el: CsElement, k: Float) {
        val cx = el.x + el.w / 2f
        val cy = el.y + el.h / 2f
        el.w = (el.w * k).coerceIn(4f, d.w * 4f)
        el.h = (el.h * k).coerceIn(2f, d.h * 4f)
        if (el.type == CsType.TEXT) {
            el.textSize = (el.textSize * k).coerceIn(6f, 1200f)
            CsRender.fitText(el, measurePaint)
        }
        el.x = cx - el.w / 2f
        el.y = cy - el.h / 2f
    }

    private fun renderObjectPanel() {
        val el = find(d.selectedId)
        if (el == null) {
            panelTitle.text = "Objek"
            note("Ketuk objek di slide untuk mengeditnya, atau tambah Teks / Bentuk / Foto / Stiker dari dock.")
            return
        }
        panelTitle.text = when (el.type) {
            CsType.TEXT -> "Teks"
            CsType.IMAGE -> "Foto"
            CsType.STICKER -> "Stiker"
            else -> "Bentuk"
        }
        if (el.type == CsType.TEXT) {
            buttonRow("Edit teks…" to { editText(el) }, (if (el.bold) "Tebal: ya" else "Tebal: tidak") to {
                checkpoint(); el.bold = !el.bold; CsRender.fitText(el, measurePaint); changed()
            })
            panelBody.addView(kit.segmented(listOf("LEFT" to "Kiri", "CENTER" to "Tengah", "RIGHT" to "Kanan"), el.align.name) { k ->
                checkpoint(); el.align = CsAlign.valueOf(k); changed()
            }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(6) })
        }
        if (el.type != CsType.IMAGE) {
            caption("WARNA")
            val pal = kit.palette()
            panelBody.addView(kit.swatchRow(pal.take(7), el.color) { col -> checkpoint(); el.color = col; el.gradient = false; changed() })
            panelBody.addView(kit.swatchRow(pal.drop(7), el.color) { col -> checkpoint(); el.color = col; el.gradient = false; changed() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        caption("TRANSFORMASI")
        buttonRow("Perkecil" to { checkpoint(); scaleElement(el, 1f / 1.1f); changed() }, "Perbesar" to { checkpoint(); scaleElement(el, 1.1f); changed() },
            "Tengahkan" to { checkpoint(); el.x = (d.w - el.w) / 2f; el.y = (d.h - el.h) / 2f; changed() })
        panelBody.addView(kit.slider("Putar", -180, 180, CsMath.normDeg(el.rotation).roundToInt(), 0, format = { "$it°" },
            onStart = { checkpoint() }, onStop = { changed() }) { v -> el.rotation = v.toFloat(); stage.invalidate() })
        panelBody.addView(kit.slider("Opasitas", 0, 100, (el.opacity * 100).roundToInt(), 100, format = { "$it%" },
            onStart = { checkpoint() }, onStop = { changed() }) { v -> el.opacity = v / 100f; stage.invalidate() })
        caption("LAYER")
        buttonRow("Ke depan" to { reorder(el, 1) }, "Ke belakang" to { reorder(el, -1) })
        buttonRow("Duplikat" to {
            checkpoint()
            val copy = el.copy(id = UUID.randomUUID().toString(), x = el.x + 24f, y = el.y + 24f, fitCanvas = false)
            val s = slide()
            s.elements.add(copy)
            s.anims[el.id]?.let { s.anims[copy.id] = it }
            d.selectedId = copy.id
            stage.selectedId = copy.id
            changed()
        }, "Hapus" to {
            checkpoint()
            slide().elements.remove(el)
            slide().anims.remove(el.id)
            d.selectedId = null
            stage.selectedId = null
            changed()
        })
        val anim = slide().anims[el.id]
        note("Animasi masuk: ${if (anim == null) "tanpa" else AnPresets.label(anim)} (atur di Animasi). Seret objek di slide untuk memindahkan.")
    }

    private fun reorder(el: CsElement, dir: Int) {
        val list = slide().elements
        val i = list.indexOf(el)
        val j = (i + dir).coerceIn(0, list.size - 1)
        if (i < 0 || i == j) return
        checkpoint()
        list.removeAt(i)
        list.add(j, el)
        changed()
    }

    private fun renderAnimPanel() {
        val s = slide()
        val el = find(d.selectedId)
        panelTitle.text = "Animasi masuk"
        if (el == null) note("Pilih objek untuk memberi animasi masuk.")
        else {
            val cur = s.anims[el.id] ?: AnPresets.NONE
            val opts = listOf(AnPresets.NONE to "Tanpa") + AnPresets.ALL
            for (row in opts.chunked(4)) {
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                for ((id, label) in row) {
                    r.addView(kit.pill(label, id == cur) {
                        checkpoint()
                        if (id == AnPresets.NONE) s.anims.remove(el.id) else s.anims[el.id] = id
                        changed()
                        if (id != AnPresets.NONE) startPreview()
                    }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
                }
                while (r.childCount < 4) r.addView(View(a), LinearLayout.LayoutParams(0, dp(34), 1f))
                panelBody.addView(r)
            }
        }
        buttonRow("Pratinjau slide" to { startPreview() }, "Hapus semua animasi" to {
            if (s.anims.isNotEmpty()) { checkpoint(); s.anims.clear(); changed() }
        })
        note("Objek beranimasi muncul berurutan (jeda 180 ms) sesuai urutan layer. ${s.anims.size} objek beranimasi di slide ini.")
    }

    private fun startPreview() {
        val tr = PrRender.entrance(slide(), d.w, d.h)
        if (tr.isEmpty()) { a.toast("Slide ini belum punya animasi masuk"); return }
        previewTracks = tr
        previewStart = System.nanoTime()
        previewT = 0L
        Choreographer.getInstance().removeFrameCallback(previewCb)
        Choreographer.getInstance().postFrameCallback(previewCb)
    }

    private fun stopPreview() {
        if (previewTracks == null) return
        previewTracks = null
        Choreographer.getInstance().removeFrameCallback(previewCb)
        if (alive) stage.invalidate()
    }

    private fun renderSizePanel() {
        panelTitle.text = "Ukuran slide · ${d.w}×${d.h}"
        panelBody.addView(kit.segmented(AnLayout.SLIDE_SIZES.keys.map { it to it }, d.sizeKey) { k ->
            if (k != d.sizeKey) { checkpoint(); d.resize(k); syncSlide(); scheduleAutosave() }
        }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(6) })
        note("16:9 = 1920×1080 (layar lebar), 4:3 = 1440×1080, 9:16 = 1080×1920 (potret/story). Konten semua slide diskalakan agar tetap utuh.")
    }

    private fun videoSize(): Pair<Int, Int> {
        val s = d.videoShort.toFloat() / min(d.w, d.h)
        return AnTiming.fitEven((d.w * s).roundToInt(), (d.h * s).roundToInt(), 1920, 2)
    }

    private fun renderExportPanel() {
        panelTitle.text = "Ekspor · ${d.slides.size} slide"
        panelBody.addView(kit.primaryButton("Simpan PDF (1 halaman per slide)") { startExport("pdf") }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        caption("MP4 SLIDESHOW")
        panelBody.addView(kit.slider("Detik/slide", 2, 20, d.secondsPerSlide, 4, format = { "$it s" }) { v -> d.secondsPerSlide = v; scheduleAutosave() })
        panelBody.addView(kit.segmented(listOf("480" to "480p", "720" to "720p", "1080" to "1080p"), d.videoShort.toString()) { k ->
            d.videoShort = k.toInt(); renderPanel("ekspor")
        }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(4) })
        val (w, h) = videoSize()
        note("Hasil ${w}×${h} · 30 fps · transisi & animasi masuk ikut dirender.")
        buttonRow("Simpan MP4" to { startExport("mp4") }, "Ke Video Editor" to { startExport("ve") })
        val last = d.lastResult
        if (last != null) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), 0, 0) }
            row.addView(kit.text("Terakhir: ${last.label} · ${humanBytes(last.bytes)}", 11.5f, c.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE },
                LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(kit.secondaryButton("Bagikan") { AnShare.share(a, last) }, LinearLayout.LayoutParams(-2, dp(38)).apply { leftMargin = dp(6) })
            panelBody.addView(row)
        }
        note("PDF → Documents/GITLS, MP4 → Movies/GITLS (Android 10+); Android 7–9 di folder aplikasi lalu Bagikan.")
    }

    // ================================================================== ekspor

    private fun startExport(kind: String) {
        if (d.job != null) { a.toast("Ekspor lain sedang berjalan"); return }
        stopPreview()
        val slides = d.slides.map { it.deepCopy(newIds = false) }
        val name = d.name
        val sps = d.secondsPerSlide
        val (vw, vh) = videoSize()
        val ctx = a.applicationContext
        val job = AnJob(when (kind) { "pdf" -> "Membuat PDF…"; "ve" -> "Merender slideshow untuk Video Editor…"; else -> "Mengekspor MP4 slideshow…" })
        d.job = job
        attachJob(job)
        val main = Handler(Looper.getMainLooper())
        val notify = { main.post { job.listener?.invoke() }; Unit }
        Thread {
            val r: Result<Any> = runCatching {
                when (kind) {
                    "pdf" -> {
                        val f = AnShare.movieFile(ctx, name, "pdf")
                        PrRender.writePdf(ctx, slides, f, { job.cancel.get() }) { pr -> job.progress = pr; notify() }
                        AnShare.publish(ctx, f, "application/pdf")
                    }
                    else -> {
                        val scale = vw / PrDeck.w.toFloat()
                        val imgs = HashMap<String, AnLayerImage>()
                        for (s in slides) imgs.putAll(AnDraw.exportImages(PrDeck.doc(s), scale, 1.8f))
                        val src = PrSlideshowSource(ctx, slides, sps, vw, vh, imgs)
                        val f = if (kind == "ve") File(AnEncode.veMediaDir(ctx), "slides_${CsStore.safeName(name)}_${System.currentTimeMillis()}.mp4")
                        else AnShare.movieFile(ctx, name, "mp4")
                        AnEncode.mp4(src, f, { job.cancel.get() }) { pr -> job.progress = pr; notify() }
                        if (kind == "ve") f else AnShare.publish(ctx, f, "video/mp4")
                    }
                }
            }
            main.post {
                d.job = null
                if (alive) hideProgress()
                r.onSuccess { res ->
                    when (res) {
                        is AnResult -> { d.lastResult = res; a.toast("Tersimpan: ${res.label}") }
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
        job.listener = {
            if (alive) {
                progressBar?.progress = job.progress
                progressText?.text = "${job.progress}%"
            }
        }
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
        cell("slide", "presentation", "Slide") { togglePanel("slide") }
        cell("teks", "format-text", "Teks") { addText() }
        cell("bentuk", "shape-outline", "Bentuk") { togglePanel("bentuk") }
        cell("foto", "image-plus", "Foto") { pickPhoto(asSlide = false) }
        cell("stiker", "sticker-emoji", "Stiker") { togglePanel("stiker") }
        cell("objek", "tune-variant", "Objek") { togglePanel("objek") }
        cell("animasi", "animation-play-outline", "Animasi") { togglePanel("animasi") }
        cell("ukuran", "aspect-ratio", "Ukuran") { togglePanel("ukuran") }
        cell("ekspor", "export-variant", "Ekspor") { togglePanel("ekspor") }
        kit.styleNav(dockCells, panelKey)
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

    // ================================================================== proyek

    private fun showMenu() {
        kit.menu(d.name, listOf(
            "Presentasi baru" to { newDeck() },
            "Simpan proyek" to { saveDeck(overwrite = true) },
            "Simpan sebagai…" to { askName("Simpan sebagai", d.name + " salinan") { n -> d.name = n; saveDeck(overwrite = false) } },
            "Buka proyek…" to { openDeckDialog() },
            "Ganti nama…" to { askName("Nama presentasi", d.name) { n -> d.name = n; updateChrome(); scheduleAutosave() } },
            "Mulai presentasi" to { startPresent() },
            "Bantuan" to { showHelp() }
        ))
    }

    private fun newDeck() {
        val stashed = PrStore.stashIfNeeded(a)
        d.reset()
        d.slides.add(d.blankSlide())
        syncSlide()
        PrStore.autosave(a)
        if (stashed != null) a.toast("Presentasi sebelumnya disimpan: $stashed")
    }

    private fun saveDeck(overwrite: Boolean) {
        val target = if (overwrite) d.file?.let { File(it) } else null
        val file = target ?: AnStore.uniqueFile(PrStore.projectsDir(a), d.name)
        d.file = file.absolutePath
        val text = PrStore.encode()
        Thread {
            val r = runCatching { CsStore.writeAtomic(file, text) }
            a.runOnUiThread { r.onSuccess { a.toast("Proyek disimpan: ${file.nameWithoutExtension}") }.onFailure { a.toast("Gagal menyimpan: ${it.message}") } }
        }.start()
        updateChrome()
        PrStore.autosave(a)
    }

    private fun openDeckDialog() {
        val files = PrStore.listProjects(a)
        if (files.isEmpty()) { a.toast("Belum ada proyek presentasi tersimpan"); return }
        val fmt = java.text.SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())
        a.themedDialog().setTitle("Buka presentasi")
            .setItems(files.map { "${it.nameWithoutExtension}\n${fmt.format(java.util.Date(it.lastModified()))}" }.toTypedArray()) { _, i ->
                val text = runCatching { files[i].readText() }.getOrNull()
                if (text == null) { a.toast("Proyek tidak bisa dibaca"); return@setItems }
                if (d.file != files[i].absolutePath) PrStore.stashIfNeeded(a)
                runCatching { PrStore.decode(a, text) }.onSuccess {
                    d.file = files[i].absolutePath
                    d.undo.clear(); d.redo.clear()
                    if (d.slides.isEmpty()) d.slides.add(d.blankSlide())
                    syncSlide()
                    PrStore.autosave(a)
                    a.toast("Dibuka: ${d.name}")
                }.onFailure { a.toast("Proyek rusak: ${it.message}") }
            }
            .setNeutralButton("Hapus…") { _, _ -> deleteDecks(files) }
            .setNegativeButton("Batal", null).show()
    }

    private fun deleteDecks(files: List<File>) {
        val checked = BooleanArray(files.size)
        a.themedDialog().setTitle("Hapus presentasi")
            .setMultiChoiceItems(files.map { it.nameWithoutExtension }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                var n = 0
                files.forEachIndexed { i, f -> if (checked[i] && f.delete()) n++ }
                if (d.file != null && !File(d.file!!).exists()) d.file = null
                a.toast("$n proyek dihapus")
            }.show()
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(a).apply { setText(initial); setSelectAllOnFocus(true); setSingleLine(true) }
        val wrap = FrameLayout(a).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, FrameLayout.LayoutParams(-1, -2)) }
        val dlg = a.themedDialog().setTitle(title).setView(wrap)
            .setNegativeButton("Batal", null)
            .setPositiveButton("OK") { _, _ -> onOk(input.text?.toString()?.trim().orEmpty().take(40).ifBlank { "Presentasi" }) }
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
    }

    private fun showHelp() {
        a.themedDialog().setTitle(PRESENT_STUDIO_PAGE).setMessage(
            "• Tiap slide adalah kanvas Foto Studio. Ketuk objek untuk memilih, seret untuk memindahkan; panel Objek untuk teks, warna, ukuran, putar, opasitas, urutan.\n" +
                "• Tambah slide dari tombol + (kosong, desain Foto Studio saat ini, proyek Studio, atau foto). Dari Foto Studio: Ekspor › Kirim ke › Presentasi.\n" +
                "• Tekan lama gambar mini slide: duplikat, pindah, hapus. Transisi & latar di panel Slide.\n" +
                "• Animasi: animasi masuk per objek (preset Animasi), diputar berurutan.\n" +
                "• Tampilkan: layar penuh. Ketuk sisi kanan / geser ke kiri = maju, sisi kiri / geser ke kanan = mundur, Back = keluar.\n" +
                "• Ekspor PDF (satu halaman per slide) atau MP4 slideshow (detik per slide). Proyek tersimpan otomatis (files/design/present)."
        ).setPositiveButton("OK", null).show()
    }

    // ================================================================== mode presentasi

    private fun setImmersive(on: Boolean) {
        val w = a.window
        if (Build.VERSION.SDK_INT >= 30) {
            val ctl = w.insetsController
            if (on) {
                ctl?.hide(WindowInsets.Type.systemBars())
                ctl?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else ctl?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            if (on) {
                savedUiFlags = w.decorView.systemUiVisibility
                w.decorView.systemUiVisibility = savedUiFlags or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else w.decorView.systemUiVisibility = savedUiFlags
        }
        if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startPresent() {
        if (presenter != null || d.slides.isEmpty()) return
        stopPreview()
        closePanel()
        val v = PrPresentView(a, d.current)
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        presenter = v
        OverlayBack.push(presentBack)
        setImmersive(true)
    }

    private fun exitPresent() {
        val v = presenter ?: return
        presenter = null
        root.removeView(v)
        OverlayBack.remove(presentBack)
        setImmersive(false)
        if (alive) {
            d.current = v.index.coerceIn(0, d.slides.size - 1)
            syncSlide()
        }
    }

    /** Tampilan presentasi layar penuh dengan transisi & animasi masuk. */
    private inner class PrPresentView(ctx: Context, start: Int) : View(ctx) {
        var index = start
        private var prev: Int? = null
        private var startNanos = System.nanoTime()
        private var tracks = PrRender.entrance(d.slides[index], d.w, d.h)
        private var ended = false
        private var downX = 0f
        private var downY = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); textSize = dp(12).toFloat() }

        init {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            contentDescription = "Presentasi layar penuh. Ketuk kanan untuk maju, kiri untuk mundur."
        }

        private fun go(to: Int) {
            if (to < 0) return
            if (to >= d.slides.size) {
                if (ended) { exitPresent(); return }
                ended = true
                invalidate()
                return
            }
            ended = false
            prev = if (to == index + 1) index else null
            index = to
            tracks = PrRender.entrance(d.slides[index], d.w, d.h)
            startNanos = System.nanoTime()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK)
            if (ended) {
                label.textAlign = Paint.Align.CENTER
                canvas.drawText("Akhir presentasi — ketuk untuk keluar", width / 2f, height / 2f, label)
                label.textAlign = Paint.Align.LEFT
                return
            }
            val t = (System.nanoTime() - startNanos) / 1_000_000L
            val s = min(width / d.w.toFloat(), height / d.h.toFloat())
            val ox = (width - d.w * s) / 2f
            val oy = (height - d.h * s) / 2f
            canvas.save()
            canvas.translate(ox, oy)
            canvas.scale(s, s)
            canvas.clipRect(RectF(0f, 0f, d.w.toFloat(), d.h.toFloat()))
            val cur = d.slides.getOrNull(index) ?: return canvas.restore()
            val p = (t.toFloat() / PrDeck.TRANSITION_MS).coerceIn(0f, 1f)
            PrRender.drawTransition(context, canvas, prev?.let { d.slides.getOrNull(it) }, cur, tracks, t, p, { el -> images.get(el) }, paint)
            canvas.restore()
            canvas.drawText("${index + 1}/${d.slides.size}", dp(14).toFloat(), height - dp(14).toFloat(), label)
            val animEnd = max(PrDeck.TRANSITION_MS, AnPresets.tracksEnd(tracks)) + 100L
            if (t < animEnd) postInvalidateOnAnimation()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    when {
                        abs(dx) > dp(60) && abs(dx) > abs(dy) -> if (dx < 0) go(index + 1) else go(index - 1)
                        abs(dx) < dp(12) && abs(dy) < dp(12) -> if (event.x > width / 3f) go(index + 1) else go(index - 1)
                    }
                }
            }
            return true
        }
    }

    // ================================================================== gambar mini slide

    private inner class PrThumb(ctx: Context, private val s: PrSlide, private val i: Int) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10).toFloat(); typeface = Typeface.DEFAULT_BOLD }

        init {
            isClickable = true
            contentDescription = "Slide ${i + 1}"
            setOnClickListener { selectSlide(i) }
            setOnLongClickListener { slideMenu(i); true }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val sc = min(width / d.w.toFloat(), height / d.h.toFloat())
            canvas.save()
            canvas.translate((width - d.w * sc) / 2f, (height - d.h * sc) / 2f)
            canvas.scale(sc, sc)
            canvas.clipRect(0f, 0f, d.w.toFloat(), d.h.toFloat())
            AnDraw.drawDoc(context, canvas, d.doc(s), { AnState.IDENTITY }, { el -> images.get(el) }, paint, background = true)
            canvas.restore()
            val active = i == d.current
            border.strokeWidth = dp(if (active) 3 else 1).toFloat()
            border.color = if (active) c.ink else c.stroke
            val h = border.strokeWidth / 2f
            canvas.drawRect(h, h, width - h, height - h, border)
            num.color = if (active) c.activeInk else c.ink
            val tw = num.measureText("${i + 1}")
            paint.reset()
            paint.color = if (active) c.activeFill else c.surface
            canvas.drawRect(0f, 0f, tw + dp(8), dp(15).toFloat(), paint)
            canvas.drawText("${i + 1}", dp(4).toFloat(), dp(11).toFloat(), num)
        }
    }
}
