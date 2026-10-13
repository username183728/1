package com.example.aidetest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** ScrollView dengan tinggi maksimum — laci panel tidak pernah memakan seluruh kanvas. */
private class CsMaxScroll(ctx: Context) : ScrollView(ctx) {
    var maxHeightPx = 0
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeightPx > 0) View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, spec)
    }
}

/**
 * Foto Studio — satu editor berlapis untuk desain, edit foto, dan hapus latar (hitam-putih):
 *  - Kanvas: preset & ukuran kustom, rasio, orientasi, latar solid/gradien/transparan, zoom & geser 2 jari
 *  - Panduan pintar: tengah/tepi kanvas & objek lain + magnet, titik pusat, grid; Pusat sekali ketuk,
 *    rata & distribusi, rotasi (snap 45°), multi-pilih (tekan lama), grup, kunci, sembunyikan
 *  - Layer foto non-destruktif: Atur, Filter, Kurva & HSL, Detail, Potong/luruskan/perspektif,
 *    Heal, Hapus latar AI + kuas + tepi + latar pengganti + bayangan + pangkas ke subjek
 *  - Teks (font, gaya, perataan, spasi, outline, bayangan, kotak latar), bentuk (isi solid/gradien),
 *    stiker ikon & emoji, mode campur & opasitas per layer
 *  - Dock bawah dua tingkat: kategori (tanpa seleksi) ↔ alat layer terpilih; panel sebagai laci
 *  - Undo/redo, tahan Bandingkan, proyek tersimpan otomatis + simpan/buka, ekspor PNG/JPG/WebP
 *    (kualitas, ukuran kustom, transparan, pangkas) ke Galeri + PDF/SVG, bagikan
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor", "SetTextI18n")
internal class CreativeStudioUi(private val a: MainActivity, private val entry: CsToolIds.Entry) : CsHost {
    override val kit = ImgKit(a)
    private val c = kit.c
    private val p = CsProject
    private val workspaceBg = if (c.dark) Color.rgb(10, 10, 12) else Color.rgb(226, 226, 230)
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())

    val root = FrameLayout(a)
    private val column = LinearLayout(a)
    private val topBar = LinearLayout(a)
    private val titleView = TextView(a)
    private val subtitleView = TextView(a)
    private val undoBtn: MdiIconView
    private val redoBtn: MdiIconView
    private val stageFrame = FrameLayout(a)
    private val canvasView: CsCanvasView
    private val busyChip = LinearLayout(a)
    private val busyText = TextView(a)
    private var startCard: View? = null
    private val panelHost = LinearLayout(a)
    private val panelScroll = CsMaxScroll(a)
    private val panelBody = LinearLayout(a)
    private val panelTitle = TextView(a)
    private val dockScroll = HorizontalScrollView(a)
    private val dock = LinearLayout(a)
    private val dockCells = LinkedHashMap<String, LinearLayout>()
    private var dockSignature = "?"
    private var panelKey: String? = null
    private var colorTarget = "isi"
    private var nudgeStep = 1f
    private var busyCount = 0
    private var comparing = false
    private var alive = true
    private var focus: CsFocusEditor? = null
    private var stickerReplace = false
    private var selBackRegistered = false
    private val photo: CsPhotoTools
    private val palette = listOf(
        Color.WHITE, Color.rgb(230, 230, 232), Color.rgb(190, 190, 194), Color.rgb(140, 140, 146), Color.rgb(90, 90, 96), Color.rgb(50, 50, 54), Color.rgb(24, 24, 27), Color.BLACK,
        Color.rgb(239, 68, 68), Color.rgb(249, 115, 22), Color.rgb(234, 179, 8), Color.rgb(34, 197, 94), Color.rgb(20, 184, 166), Color.rgb(59, 130, 246), Color.rgb(139, 92, 246), Color.rgb(236, 72, 153)
    )
    /** Bayangan subjek per layer: (bitmap hasil render, kunci blur/lebar, bayangan). */
    private val shadowCache = HashMap<String, Triple<Bitmap, Int, CsShadow>>()
    private var parallaxLive = false
    private var tiltNx = 0f
    private var tiltNy = 0f
    private var gravitySensor: SensorEventListener? = null
    private val renderListener: (String) -> Unit = { _ -> if (alive) { canvasView.invalidate(); photo.onRendered() } }
    private val errorListener: (Throwable) -> Unit = { e ->
        if (alive) a.toast(if (e is OutOfMemoryError) "Memori tidak cukup untuk pratinjau foto — kurangi jumlah foto" else "Render foto gagal: ${e.message ?: e.javaClass.simpleName}")
    }
    private val panelBack: () -> Boolean = {
        if (panelKey != null && root.isAttachedToWindow) { closePanel(); true } else false
    }
    private val selectionBack: () -> Boolean = {
        selBackRegistered = false
        if (p.selection.isNotEmpty() && root.isAttachedToWindow && focus == null) {
            selectOnly(null)
            p.multiSelect = false
            changed()
            true
        } else false
    }
    private val autosaveTask = Runnable { autosaveNow() }

    private fun dp(v: Int): Int = a.dp(v)

    init {
        CsPipeline.init(a)
        if (!p.initialized) {
            p.initialized = true
            if (!restoreAutosave()) applyTemplate(CS_TEMPLATES[1], record = false)
        }
        photo = CsPhotoTools(this)
        root.setBackgroundColor(workspaceBg)
        column.orientation = LinearLayout.VERTICAL
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        undoBtn = kit.icon("undo", 22f, c.ink, "Undo") { undo() }
        redoBtn = kit.icon("redo", 22f, c.ink, "Redo") { redo() }
        buildTopBar()
        column.addView(topBar, LinearLayout.LayoutParams(-1, dp(52)))

        canvasView = CsCanvasView(a)
        stageFrame.addView(canvasView, FrameLayout.LayoutParams(-1, -1))
        buildBusy()
        stageFrame.addView(busyChip, FrameLayout.LayoutParams(-2, dp(36), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })
        column.addView(stageFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        buildPanelHost()
        column.addView(panelHost, LinearLayout.LayoutParams(-1, -2))
        column.addView(View(a).apply { setBackgroundColor(c.stroke) }, LinearLayout.LayoutParams(-1, 1))
        dock.orientation = LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER_VERTICAL
        dock.setPadding(dp(4), 0, dp(4), 0)
        dockScroll.isHorizontalScrollBarEnabled = false
        dockScroll.overScrollMode = View.OVER_SCROLL_NEVER
        dockScroll.background = c.shape(c.surface, 0)
        dockScroll.addView(dock, FrameLayout.LayoutParams(-2, -1))
        column.addView(dockScroll, LinearLayout.LayoutParams(-1, dp(64)))

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                alive = false
                setParallaxLive(false)
                OverlayBack.remove(panelBack)
                OverlayBack.remove(selectionBack)
                focus?.close()
                if (CsPipeline.listener === renderListener) CsPipeline.listener = null
                if (CsPipeline.errorListener === errorListener) CsPipeline.errorListener = null
                handler.removeCallbacks(autosaveTask)
                autosaveNow()
            }
        })
        CsPipeline.listener = renderListener
        CsPipeline.errorListener = errorListener
        fillMissingSourceSizes()
        onSelectionChanged()
        updateChrome()
        ensureRenders()
        cleanupFilesLater()
        when (entry) {
            CsToolIds.Entry.PHOTO -> showStartCard(cutout = false)
            CsToolIds.Entry.CUTOUT -> showStartCard(cutout = true)
            CsToolIds.Entry.DEFAULT, CsToolIds.Entry.ANIM -> Unit
        }
    }

    // ================================================================== CsHost

    override fun body(): LinearLayout = panelBody
    override fun setPanelTitle(t: String) { panelTitle.text = t }

    override fun caption(value: String) {
        panelBody.addView(kit.text(value, 10.5f, c.muted, true).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(10), 0, dp(6)) })
    }

    override fun note(value: String) {
        panelBody.addView(kit.text(value, 11.5f, c.muted).apply { setPadding(dp(4), dp(6), dp(4), dp(4)); includeFontPadding = true; setLineSpacing(0f, 1.15f) })
    }

    override fun tiles(vararg t: View, height: Int) {
        panelBody.addView(kit.tileRow(t.toList(), height))
    }

    override fun find(id: String): CsElement? = p.elements.firstOrNull { it.id == id }

    override fun workSide(): Int = CsMath.workLongSide(p.imageCount())

    override fun renderImage(el: CsElement, draft: Boolean) {
        CsPipeline.jobFor(el, workSide(), draft = draft)?.let { CsPipeline.request(it) }
    }

    override fun rerenderPanel() {
        panelKey?.let { renderPanel(it) }
    }

    override fun busy(on: Boolean, msg: String) {
        busyCount = max(0, busyCount + if (on) 1 else -1)
        if (on && msg.isNotEmpty()) busyText.text = msg
        busyChip.visibility = if (busyCount > 0) View.VISIBLE else View.GONE
    }

    override fun canvasBackground(): CsBackground = p.bg

    override fun setCanvasBackground(bg: CsBackground) {
        checkpoint()
        p.bg = bg
        changed()
    }

    /** Slider yang langsung menggambar ulang; checkpoint satu kali per geseran untuk undo. */
    override fun liveSlider(label: String, min: Int, max: Int, value: Int, format: (Int) -> String, apply: (Int) -> Unit): View =
        kit.slider(label, min, max, value.coerceIn(min, max), value.coerceIn(min, max), format,
            onStart = { checkpoint() },
            onStop = { changed(rerenderPanel = false) }
        ) { v -> apply(v); canvasView.invalidate() }

    override fun openFocus(el: CsElement, mode: String) {
        if (focus != null || el.type != CsType.IMAGE) return
        if (el.locked) { a.toast("Layer terkunci. Buka kunci dulu."); return }
        val id = el.id
        focus = CsFocusEditor(kit, root, el, mode, workSide(),
            onApply = { r -> applyFocus(id, r) },
            onClosed = { focus = null; if (alive) canvasView.invalidate() })
    }

    private fun applyFocus(id: String, r: CsFocusResult) {
        val el = find(id) ?: return
        checkpoint()
        r.geom?.let { applyGeom(el, it) }
        r.heals?.let { el.edit = el.edit.copy(heals = it) }
        r.mask?.let { m ->
            el.maskId = CsMasks.put(a, r.maskW, r.maskH, m)
            el.cut = el.cut.copy(on = true, method = el.cut.method.ifBlank { "kuas" })
        }
        changed()
    }

    private fun outSize(el: CsElement, g: PeGeom): Pair<Int, Int> =
        CsMath.outputSize(el.srcW, el.srcH, g.rot90, g.cl, g.ct, g.cr, g.cb)

    /**
     * Terapkan geometri baru ke layer foto. Foto utama (pas kanvas): kanvas ikut berubah dengan
     * kepadatan piksel tetap dan layer lain dipetakan agar tetap di atas konten yang sama.
     * Foto biasa: skala px per px foto dipertahankan, pusat tetap.
     */
    override fun applyGeom(el: CsElement, g: PeGeom) {
        val old = el.edit.geom
        val (ow0, oh0) = outSize(el, old)
        val (ow1, oh1) = outSize(el, g)
        el.edit = el.edit.copy(geom = g)
        if (el.fitCanvas) {
            val density = p.canvasW / ow0.coerceAtLeast(1).toFloat()
            val (nw, nh) = CsMath.docCanvasSize(ow1, oh1, density)
            val oldW = p.canvasW.toFloat()
            val oldH = p.canvasH.toFloat()
            val sameFrame = old.rot90 == g.rot90 && old.flipH == g.flipH && old.flipV == g.flipV
            val oldCrop = floatArrayOf(old.cl, old.ct, old.cr, old.cb)
            val newCrop = floatArrayOf(g.cl, g.ct, g.cr, g.cb)
            val k = if (sameFrame) CsMath.cropContentScale(oldW, oldCrop, nw.toFloat(), newCrop) else nw / oldW
            for (o in p.elements) {
                if (o === el || o.fitCanvas || o.role.startsWith("latar:${el.id}:")) continue
                val cx = o.x + o.w / 2f
                val cy = o.y + o.h / 2f
                val (nx, ny) = if (sameFrame) CsMath.remapCrop(cx, cy, oldW, oldH, oldCrop, nw.toFloat(), nh.toFloat(), newCrop)
                else (cx * nw / oldW) to (cy * nh / oldH)
                if (abs(k - 1f) > 0.01f) scaleElement(o, k)
                o.x = nx - o.w / 2f
                o.y = ny - o.h / 2f
            }
            p.canvasW = nw
            p.canvasH = nh
            el.x = 0f
            el.y = 0f
            el.w = nw.toFloat()
            el.h = nh.toFloat()
            el.rotation = 0f
            for (o in p.elements) if (o !== el && o.fitCanvas) { o.x = 0f; o.y = 0f; o.w = nw.toFloat(); o.h = nh.toFloat() }
            canvasView.fit()
        } else {
            val r = CsMath.resizeForOutput(el.x, el.y, el.w, el.h, ow0, oh0, ow1, oh1)
            el.x = r[0]; el.y = r[1]; el.w = r[2]; el.h = r[3]
        }
        syncBackgroundLayers(el)
    }

    private fun scaleElement(o: CsElement, k: Float) {
        val cx = o.x + o.w / 2f
        val cy = o.y + o.h / 2f
        if (o.type == CsType.TEXT) {
            o.textSize = (o.textSize * k).coerceIn(4f, 2000f)
            o.strokeWidth *= k
            CsRender.fitText(o, measurePaint)
        } else {
            o.w = (o.w * k).coerceAtLeast(1f)
            o.h = (o.h * k).coerceAtLeast(1f)
            o.strokeWidth *= k
            o.cornerRadius *= k
        }
        o.x = cx - o.w / 2f
        o.y = cy - o.h / 2f
    }

    override fun commitMask(el: CsElement, data: ByteArray, w: Int, h: Int, method: String, cutOn: Boolean) {
        checkpoint()
        el.maskId = CsMasks.put(a, w, h, data)
        el.cut = el.cut.copy(on = cutOn, method = method)
        changed()
    }

    // ---- latar pengganti (panel Hapus latar)

    private fun bgLayersOf(el: CsElement): List<CsElement> = p.elements.filter { it.role.startsWith("latar:${el.id}:") }

    override fun backgroundLayer(el: CsElement): CsElement? = bgLayersOf(el).firstOrNull()

    override fun replacementMode(el: CsElement): String {
        val layer = backgroundLayer(el)
        return when {
            layer != null -> if (layer.role.endsWith(":blur")) "blur" else "gambar"
            p.bg.mode == "transparent" -> "transparan"
            p.bg.mode == "gradient" -> "gradien"
            else -> "warna"
        }
    }

    override fun setReplacement(el: CsElement, mode: String) {
        when (mode) {
            "transparan", "warna", "gradien" -> {
                checkpoint()
                val ids = bgLayersOf(el).map { it.id }.toSet()
                p.elements.removeAll { it.id in ids }
                p.selection.removeAll(ids)
                p.bg = p.bg.copy(mode = when (mode) { "transparan" -> "transparent"; "gradien" -> "gradient"; else -> "solid" })
                changed()
            }
            "gambar" -> pickBackgroundImage(el)
            "blur" -> {
                if (el.imagePath == null) return
                checkpoint()
                val ids = bgLayersOf(el).map { it.id }.toSet()
                p.elements.removeAll { it.id in ids }
                val group = el.groupId ?: UUID.randomUUID().toString().also { el.groupId = it }
                val copy = el.copy(
                    id = UUID.randomUUID().toString(), role = "latar:${el.id}:blur", name = "Latar blur", groupId = group,
                    maskId = null, cut = CsCut(), shadow = false, locked = false, fitCanvas = el.fitCanvas,
                    edit = el.edit.copy(adjust = el.edit.adjust.copy(blur = 0.45f, bgBlur = 0f))
                )
                p.elements.add(p.elements.indexOf(el).coerceAtLeast(0), copy)
                if (p.bg.mode == "transparent") p.bg = p.bg.copy(mode = "solid")
                changed()
            }
        }
    }

    private fun pickBackgroundImage(el: CsElement) {
        val id = el.id
        importPhoto { file, w, h, _ ->
            val photoEl = find(id) ?: return@importPhoto
            checkpoint()
            val ids = bgLayersOf(photoEl).map { it.id }.toSet()
            p.elements.removeAll { it.id in ids }
            val group = photoEl.groupId ?: UUID.randomUUID().toString().also { photoEl.groupId = it }
            val crop = CsMath.centeredCrop(w, h, photoEl.w / photoEl.h.coerceAtLeast(1f))
            val layer = CsElement(
                type = CsType.IMAGE, x = photoEl.x, y = photoEl.y, w = photoEl.w, h = photoEl.h, rotation = photoEl.rotation,
                imagePath = file.absolutePath, srcW = w, srcH = h, name = "Latar gambar", role = "latar:${photoEl.id}:img",
                groupId = group, fitCanvas = photoEl.fitCanvas,
                edit = PeEdit(geom = PeGeom(cl = crop[0], ct = crop[1], cr = crop[2], cb = crop[3]))
            )
            p.elements.add(p.elements.indexOf(photoEl).coerceAtLeast(0), layer)
            if (p.bg.mode == "transparent") p.bg = p.bg.copy(mode = "solid")
            changed()
        }
    }

    /** Layer latar pengganti mengikuti ukuran & geometri foto pemiliknya. */
    private fun syncBackgroundLayers(el: CsElement) {
        for (o in bgLayersOf(el)) {
            o.x = el.x; o.y = el.y; o.w = el.w; o.h = el.h; o.rotation = el.rotation
            if (o.role.endsWith(":blur")) {
                // Latar blur memakai foto yang sama: ikut diganti saat "Ganti foto".
                o.imagePath = el.imagePath; o.srcW = el.srcW; o.srcH = el.srcH
                o.edit = o.edit.copy(geom = el.edit.geom)
            }
            else if (o.srcW > 0 && o.srcH > 0) {
                val crop = CsMath.centeredCrop(o.srcW, o.srcH, el.w / el.h.coerceAtLeast(1f))
                o.edit = o.edit.copy(geom = PeGeom(cl = crop[0], ct = crop[1], cr = crop[2], cb = crop[3]))
            }
        }
    }

    override fun shadowControls(el: CsElement) {
        panelBody.addView(kit.toggleRow("Bayangan", el.shadow) { on ->
            checkpoint()
            el.shadow = on
            if (on && el.shadowBlur < 1f) el.shadowBlur = 12f
            changed()
        }, LinearLayout.LayoutParams(-1, dp(40)))
        if (!el.shadow) return
        panelBody.addView(liveSlider("Blur", 1, 120, el.shadowBlur.roundToInt(), { "${it}px" }) { v -> el.shadowBlur = v.toFloat() })
        panelBody.addView(liveSlider("Pekat", 10, 255, Color.alpha(el.shadowColor), { "${it * 100 / 255}%" }) { v ->
            el.shadowColor = Color.argb(v, Color.red(el.shadowColor), Color.green(el.shadowColor), Color.blue(el.shadowColor))
        })
        val range = (max(p.canvasW, p.canvasH) / 20).coerceIn(20, 200)
        panelBody.addView(liveSlider("Geser X", -range, range, el.shadowDx.roundToInt(), { "${it}px" }) { v -> el.shadowDx = v.toFloat() })
        panelBody.addView(liveSlider("Geser Y", -range, range, el.shadowDy.roundToInt(), { "${it}px" }) { v -> el.shadowDy = v.toFloat() })
        panelBody.addView(kit.swatchRow(listOf(Color.BLACK, Color.rgb(60, 60, 64), Color.WHITE, Color.rgb(239, 68, 68), Color.rgb(59, 130, 246), Color.rgb(234, 179, 8)),
            el.shadowColor) { col ->
            checkpoint()
            el.shadowColor = Color.argb(Color.alpha(el.shadowColor).coerceAtLeast(40), Color.red(col), Color.green(col), Color.blue(col))
            changed()
        }, LinearLayout.LayoutParams(-1, dp(32)).apply { topMargin = dp(4) })
    }

    // ================================================================== model helpers

    private fun primary(): CsElement? = p.selection.lastOrNull()?.let { id -> p.elements.firstOrNull { it.id == id } }
    private fun selectedList(): List<CsElement> = p.elements.filter { it.id in p.selection }
    private fun editable(): List<CsElement> = selectedList().filter { !it.locked }
    private fun movable(): List<CsElement> = editable().filter { !it.fitCanvas }
    private fun primaryImage(): CsElement? = primary()?.takeIf { it.type == CsType.IMAGE && p.selection.size == 1 }
    private fun boxOf(el: CsElement) = DsBox(el.x, el.y, el.w, el.h, el.rotation)
    private fun boundsOf(el: CsElement) = boxOf(el).bounds()

    private fun groupMembers(el: CsElement): List<CsElement> {
        val g = el.groupId ?: return listOf(el)
        return p.elements.filter { it.groupId == g }
    }

    private fun selectOnly(el: CsElement?) {
        p.selection.clear()
        if (el != null) {
            groupMembers(el).forEach { p.selection.add(it.id) }
            // Elemen yang diketuk menjadi elemen utama (terakhir).
            p.selection.remove(el.id)
            p.selection.add(el.id)
        }
    }

    private fun toggleSelect(el: CsElement) {
        val members = groupMembers(el)
        if (el.id in p.selection) members.forEach { p.selection.remove(it.id) }
        else members.forEach { p.selection.add(it.id) }
    }

    private fun snapshot(): CsSnapshot = CsSnapshot(p.canvasW, p.canvasH, p.bg, p.elements.map { it.copy() }, p.selection.toList())

    private fun restore(s: CsSnapshot) {
        p.canvasW = s.w
        p.canvasH = s.h
        p.bg = s.bg
        p.elements.clear()
        p.elements.addAll(s.elements.map { it.copy() })
        p.selection.clear()
        s.selection.filter { id -> p.elements.any { it.id == id } }.forEach { p.selection.add(it) }
    }

    /** Catat state sebelum perubahan agar bisa di-undo. */
    override fun checkpoint() {
        p.undo.add(snapshot())
        while (p.undo.size > CS_UNDO_LIMIT) p.undo.removeAt(0)
        p.redo.clear()
        updateChrome()
    }

    private fun undo() {
        val last = p.undo.removeLastOrNull() ?: run { a.toast("Tidak ada langkah untuk di-undo"); return }
        p.redo.add(snapshot())
        restore(last)
        changed()
    }

    private fun redo() {
        val last = p.redo.removeLastOrNull() ?: run { a.toast("Tidak ada langkah untuk di-redo"); return }
        p.undo.add(snapshot())
        restore(last)
        changed()
    }

    private fun updateChrome() {
        undoBtn.alpha = if (p.undo.isNotEmpty()) 1f else 0.35f
        redoBtn.alpha = if (p.redo.isNotEmpty()) 1f else 0.35f
        titleView.text = p.projectName
        subtitleView.text = "${p.canvasW}×${p.canvasH} · ${(canvasView.displayScale() * 100).roundToInt()}% · ${p.elements.size} layer"
    }

    /** Setelah perubahan model: gambar ulang, dock + panel, render foto, dan simpan otomatis. */
    override fun changed(rerenderPanel: Boolean) {
        canvasView.invalidate()
        updateChrome()
        onSelectionChanged(rerenderPanel)
        ensureRenders()
        scheduleAutosave()
    }

    private fun ensureRenders() {
        val ws = workSide()
        val ids = HashSet<String>()
        for (el in p.elements) {
            ids.add(el.id)
            if (el.type != CsType.IMAGE || !el.visible) continue
            CsPipeline.jobFor(el, ws)?.let { CsPipeline.request(it) }
        }
        CsPipeline.prune(ids)
        shadowCache.keys.retainAll(ids)
    }

    /** Proyek v2 belum menyimpan ukuran sumber; baca header file (cepat, tanpa dekode piksel). */
    private fun fillMissingSourceSizes() {
        for (el in p.elements) {
            if (el.type != CsType.IMAGE || (el.srcW > 0 && el.srcH > 0)) continue
            val path = el.imagePath ?: continue
            CsPipeline.boundsOf(path)?.let { (w, h) -> el.srcW = w; el.srcH = h }
        }
    }

    private fun scheduleAutosave() {
        handler.removeCallbacks(autosaveTask)
        handler.postDelayed(autosaveTask, 1200L)
    }

    private fun autosaveNow() {
        val json = runCatching { CsStore.toJsonText(p) }.getOrNull() ?: return
        val file = CsStore.autosaveFile(a)
        Thread { runCatching { CsStore.writeAtomic(file, json) } }.start()
    }

    private fun restoreAutosave(): Boolean = runCatching {
        val f = CsStore.autosaveFile(a)
        if (!f.exists()) return@runCatching false
        CsStore.load(a, f.readText(), p)
        true
    }.getOrDefault(false)

    private fun cleanupFilesLater() {
        val images = HashSet<String>()
        val masks = HashSet<String>()
        fun scan(list: List<CsElement>) = list.forEach { e ->
            e.imagePath?.let { images.add(it) }
            e.maskId?.let { masks.add(it) }
        }
        scan(p.elements)
        (p.undo + p.redo).forEach { scan(it.elements) }
        val ctx = a.applicationContext
        Thread { runCatching { CsStore.cleanup(ctx, images, masks) } }.start()
    }

    private fun applyTemplate(t: CsTemplate, record: Boolean = true) {
        if (record) checkpoint()
        p.canvasW = t.width
        p.canvasH = t.height
        p.bg = CsBackground("solid", t.bg)
        p.elements.clear()
        t.seed.forEach { p.elements.add(it.copy(id = UUID.randomUUID().toString()).also { e -> CsRender.fitText(e, measurePaint) }) }
        selectOnly(p.elements.firstOrNull())
        p.userScale = 1f
        p.panX = 0f
        p.panY = 0f
    }

    private fun onLightBg(): Boolean {
        if (p.bg.mode == "transparent") return true
        val col = p.bg.color
        return (Color.red(col) * 299 + Color.green(col) * 587 + Color.blue(col) * 114) / 1000 > 140
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
            contentDescription = "Kanvas dan proyek"
            background = c.pressable(Color.TRANSPARENT, 10)
            setOnClickListener { openPanel("kanvas") }
        }
        titleView.apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(c.ink)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        subtitleView.apply {
            textSize = 11f
            setTextColor(c.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(3), 0, 0)
        }
        titles.addView(titleView)
        titles.addView(subtitleView)
        topBar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(compareButton(), LinearLayout.LayoutParams(dp(40), dp(44)))
        topBar.addView(undoBtn, LinearLayout.LayoutParams(dp(40), dp(44)))
        topBar.addView(redoBtn, LinearLayout.LayoutParams(dp(40), dp(44)))
        topBar.addView(kit.icon("dots-vertical", 22f, c.ink, "Menu proyek") { showMenu() }, LinearLayout.LayoutParams(dp(38), dp(44)))
        topBar.addView(kit.primaryButton("Ekspor") { openPanel("ekspor") }.apply { contentDescription = "Ekspor desain" },
            LinearLayout.LayoutParams(-2, dp(36)).apply { leftMargin = dp(4) })
    }

    /** Tahan untuk melihat foto sebelum diedit (tanpa heal, potong latar, warna, dan detail). */
    private fun compareButton(): View = kit.icon("compare", 21f, c.ink, "Tahan untuk melihat foto asli").apply {
        background = c.pressable(Color.TRANSPARENT, 12)
        isClickable = true
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { comparing = true; v.isPressed = true; canvasView.invalidate() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { comparing = false; v.isPressed = false; canvasView.invalidate() }
            }
            true
        }
    }

    private fun buildBusy() {
        busyChip.orientation = LinearLayout.HORIZONTAL
        busyChip.gravity = Gravity.CENTER_VERTICAL
        busyChip.setPadding(dp(12), 0, dp(14), 0)
        busyChip.background = c.shape(c.surface, 18, c.stroke)
        busyChip.elevation = dp(4).toFloat()
        busyChip.addView(ProgressBar(a).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(18), dp(18)))
        busyText.apply { textSize = 12f; setTextColor(c.ink); setPadding(dp(8), 0, 0, 0) }
        busyChip.addView(busyText)
        busyChip.visibility = View.GONE
    }

    // ================================================================== kartu awal (dibuka lewat id lama)

    private fun showStartCard(cutout: Boolean) {
        dismissStartCard()
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = c.shape(c.surface, 22, c.stroke)
            elevation = dp(6).toFloat()
            isClickable = true
        }
        card.addView(kit.icon(if (cutout) "image-remove" else "image-edit-outline", 40f, c.ink), LinearLayout.LayoutParams(dp(64), dp(56)))
        card.addView(kit.text(if (cutout) "Hapus latar dengan AI" else "Edit foto", 16f, c.ink, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4))
        })
        card.addView(kit.text(
            if (cutout) "Orang, hewan, dan produk terdeteksi otomatis.\nRapikan dengan kuas, ganti latar, lalu ekspor PNG transparan."
            else "Cahaya, warna, kurva & HSL, filter, potong, heal —\nnon-destruktif. Tambah teks, stiker, dan bentuk di atasnya.",
            12f, c.muted
        ).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) })
        card.addView(kit.primaryButton("Pilih foto") { openPhotoDocument(cutout) }, LinearLayout.LayoutParams(-2, dp(40)).apply { topMargin = dp(14) })
        val sel = primaryImage()
        if (sel != null) {
            card.addView(kit.secondaryButton(if (cutout) "Hapus latar foto terpilih" else "Edit foto terpilih") {
                dismissStartCard()
                openPanel(if (cutout) "p_latar" else "p_atur")
                if (cutout && sel.maskId == null) photo.runCutout(sel, "subjek", cutOn = true)
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { topMargin = dp(8) })
        }
        if (p.elements.isNotEmpty()) {
            card.addView(kit.secondaryButton("Lanjutkan \"${p.projectName.take(24)}\"") { dismissStartCard() },
                LinearLayout.LayoutParams(-2, dp(38)).apply { topMargin = dp(8) })
        }
        val wrap = FrameLayout(a).apply {
            setBackgroundColor(Color.argb(if (c.dark) 120 else 70, 0, 0, 0))
            isClickable = true
            setOnClickListener { if (p.elements.isNotEmpty()) dismissStartCard() }
        }
        wrap.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { leftMargin = dp(20); rightMargin = dp(20) })
        stageFrame.addView(wrap, FrameLayout.LayoutParams(-1, -1))
        startCard = wrap
    }

    private fun dismissStartCard() {
        startCard?.let { stageFrame.removeView(it) }
        startCard = null
    }

    // ================================================================== foto: impor

    /** Pilih foto, salin ke penyimpanan privat (≤ 3072 px, orientasi EXIF diterapkan). */
    private fun importPhoto(onReady: (file: File, w: Int, h: Int, name: String) -> Unit) {
        a.pickImageForTool(CS_PICK_IMAGE) { uri ->
            if (uri == null) return@pickImageForTool
            busy(true, "Memuat foto…")
            val ctx = a.applicationContext
            Thread {
                val r = runCatching {
                    val decoded = a.decodeOrientedBitmap(uri, CS_SOURCE_MAX)
                    val bmp = fitLongSide(decoded, CS_SOURCE_MAX)
                    if (bmp !== decoded) decoded.recycle()
                    // Bitmap impor hanya dipakai untuk menulis salinan privat; pratinjau didekode ulang oleh pipeline.
                    try {
                        val alpha = bmp.hasAlpha()
                        val file = File(CsStore.imgDir(ctx), "${UUID.randomUUID()}.${if (alpha) "png" else "jpg"}")
                        FileOutputStream(file).use { out ->
                            if (!bmp.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, out)) error("Gagal menyalin foto")
                        }
                        Triple(file, bmp.width, bmp.height)
                    } finally {
                        bmp.recycle()
                    }
                }
                val name = runCatching { a.displayNameFor(uri) }.getOrDefault("foto")
                a.runOnUiThread {
                    busy(false)
                    if (!alive) return@runOnUiThread
                    r.onSuccess { (file, w, h) -> onReady(file, w, h, name) }
                        .onFailure { e -> a.toast(if (e is OutOfMemoryError) "Foto terlalu besar untuk memori" else "Foto tidak bisa dibaca: ${e.message ?: e.javaClass.simpleName}") }
                }
            }.start()
        }
    }

    /** Alur Photo Editor / Hapus Latar: foto dibuka sebagai dokumen baru seukuran foto. */
    private fun openPhotoDocument(cutout: Boolean) {
        importPhoto { file, w, h, name ->
            dismissStartCard()
            stashCurrentProject()
            p.clearForNew()
            p.canvasW = w.coerceIn(16, CsMath.MAX_CANVAS)
            p.canvasH = h.coerceIn(16, CsMath.MAX_CANVAS)
            p.bg = CsBackground(if (cutout) "transparent" else "solid", Color.WHITE)
            p.projectName = name.take(40).ifBlank { "Foto" }
            p.projectFile = null
            val el = CsElement(type = CsType.IMAGE, x = 0f, y = 0f, w = p.canvasW.toFloat(), h = p.canvasH.toFloat(),
                imagePath = file.absolutePath, srcW = w, srcH = h, fitCanvas = true, name = "Foto")
            p.elements.add(el)
            selectOnly(el)
            canvasView.fit()
            changed()
            openPanel(if (cutout) "p_latar" else "p_atur")
            if (cutout) photo.runCutout(el, "subjek", cutOn = true)
        }
    }

    /** Tambah foto sebagai layer di desain saat ini; [cutout] = langsung hapus latarnya (AI subjek). */
    private fun addPhotoLayer(cutout: Boolean) {
        importPhoto { file, w, h, name ->
            dismissStartCard()
            val (ew, eh) = CsMath.fitInside(w.toFloat(), h.toFloat(), p.canvasW.toFloat(), p.canvasH.toFloat(), 0.6f, noUpscale = true)
            val el = CsElement(type = CsType.IMAGE, x = (p.canvasW - ew) / 2f, y = (p.canvasH - eh) / 2f, w = ew, h = eh,
                imagePath = file.absolutePath, srcW = w, srcH = h, name = name.take(28))
            addElement(el)
            if (cutout) {
                openPanel("p_latar")
                photo.runCutout(el, "subjek", cutOn = true)
            }
        }
    }

    private fun replacePhoto(el: CsElement) {
        val id = el.id
        importPhoto { file, w, h, name ->
            val cur = find(id) ?: return@importPhoto
            checkpoint()
            val (ow0, oh0) = outSize(cur, cur.edit.geom)
            cur.imagePath = file.absolutePath
            cur.srcW = w
            cur.srcH = h
            cur.maskId = null
            cur.cut = CsCut()
            cur.name = name.take(28)
            // Heal & crop milik foto lama tidak berlaku untuk foto baru; pengaturan warna tetap dipakai.
            cur.edit = cur.edit.copy(heals = emptyList(), geom = PeGeom())
            val (ow1, oh1) = outSize(cur, cur.edit.geom)
            if (cur.fitCanvas) {
                val (nw, nh) = CsMath.docCanvasSize(ow1, oh1, p.canvasW / ow0.coerceAtLeast(1).toFloat())
                resizeCanvasTo(nw, nh, record = false)
                cur.x = 0f; cur.y = 0f; cur.w = p.canvasW.toFloat(); cur.h = p.canvasH.toFloat()
                cur.edit = cur.edit.copy(geom = PeGeom())
            } else {
                // Lebar tampilan dipertahankan (pusat tetap), tinggi mengikuti rasio foto baru.
                val cx = cur.x + cur.w / 2f
                val cy = cur.y + cur.h / 2f
                cur.h = cur.w * oh1 / ow1.coerceAtLeast(1)
                cur.x = cx - cur.w / 2f
                cur.y = cy - cur.h / 2f
            }
            syncBackgroundLayers(cur)
            changed()
        }
    }

    /** Simpan desain yang akan diganti agar tidak hilang (ke file proyeknya, atau proyek baru). */
    private fun stashCurrentProject() {
        if (p.elements.isEmpty()) return
        val dir = CsStore.projectsDir(a)
        var file = p.projectFile?.let { File(it) }
        if (file == null) {
            file = File(dir, CsStore.safeName(p.projectName) + ".json")
            var i = 2
            while (file != null && file.exists()) { file = File(dir, CsStore.safeName(p.projectName) + " $i.json"); i++ }
        }
        val target = file ?: return
        val json = runCatching { CsStore.toJsonText(p) }.getOrNull() ?: return
        Thread { runCatching { CsStore.writeAtomic(target, json) } }.start()
        a.toast("Desain sebelumnya disimpan di Proyek: ${target.nameWithoutExtension}")
    }

    // ================================================================== dock (dua tingkat)

    private fun dockCell(key: String?, iconName: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
        val cell = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            alpha = if (enabled) 1f else 0.45f
            setOnClickListener { onClick() }
        }
        cell.addView(kit.icon(iconName, 21f, c.ink), LinearLayout.LayoutParams(-1, dp(25)))
        cell.addView(kit.text(label, 10f, c.ink).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), dp(3), dp(2), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        dock.addView(cell, LinearLayout.LayoutParams(dp(66), dp(54)).apply { leftMargin = dp(1); rightMargin = dp(1) })
        if (key != null) dockCells[key] = cell
        else cell.background = c.pressable(Color.TRANSPARENT, 14)
    }

    private fun panelCell(key: String, iconName: String, label: String) = dockCell(key, iconName, label) { togglePanel(key) }

    private fun dockDivider() {
        dock.addView(View(a).apply { setBackgroundColor(c.stroke) }, LinearLayout.LayoutParams(1, dp(30)).apply { leftMargin = dp(4); rightMargin = dp(4) })
    }

    private fun styleDock() {
        kit.styleNav(dockCells, panelKey)
    }

    /** Tanpa seleksi: kategori. Dengan seleksi: alat layer tersebut (dock "bertukar"). */
    private fun renderDock() {
        val sel = selectedList()
        val sig = sel.joinToString("|") { "${it.id}:${it.type}:${it.locked}:${it.groupId}:${it.fitCanvas}" } + "#" + p.multiSelect
        if (sig == dockSignature) { styleDock(); return }
        dockSignature = sig
        dock.removeAllViews()
        dockCells.clear()
        val el = primary()
        if (el == null) {
            panelCell("tambah", "shape-plus", "Tambah")
            panelCell("foto", "image-plus", "Foto")
            dockCell(null, "format-text", "Teks") { addText("Teks baru", 64f, true) }
            panelCell("bentuk", "shape-outline", "Bentuk")
            panelCell("stiker", "sticker-outline", "Stiker")
            dockDivider()
            panelCell("kanvas", "aspect-ratio", "Kanvas")
            panelCell("latarkanvas", "format-color-fill", "Latar")
            panelCell("layer", "layers-outline", "Layer")
            panelCell("tampilan", "grid", "Tampilan")
            styleDock()
            dockScroll.scrollTo(0, 0)
            return
        }
        dockCell(null, "check", "Selesai") { selectOnly(null); p.multiSelect = false; closePanel(); changed() }
        dockDivider()
        if (sel.all { it.locked }) {
            dockCell(null, "lock-open-outline", "Buka kunci") { checkpoint(); sel.forEach { it.locked = false }; changed() }
            panelCell("layer", "layers-outline", "Layer")
            styleDock()
            dockScroll.scrollTo(0, 0)
            return
        }
        if (sel.size == 1) {
            when (el.type) {
                CsType.IMAGE -> {
                    panelCell("p_atur", "tune", "Atur")
                    panelCell("p_filter", "image-filter-vintage", "Filter")
                    panelCell("p_kurva", "chart-bell-curve", "Kurva")
                    panelCell("p_detail", "shimmer", "Detail")
                    dockCell(null, "crop-rotate", "Potong") { openFocus(el, "crop") }
                    dockCell(null, "bandage", "Heal") { openFocus(el, "heal") }
                    panelCell("p_latar", "image-remove", "Hapus latar")
                    panelCell("gaya", "palette-outline", "Gaya")
                }
                CsType.TEXT -> {
                    dockCell(null, "pencil-outline", "Edit") { editText(el) }
                    panelCell("teks", "format-font", "Teks")
                    panelCell("gaya", "palette-outline", "Gaya")
                }
                CsType.STICKER -> {
                    dockCell(null, "sticker-outline", "Ganti") { stickerReplace = true; openPanel("stiker") }
                    panelCell("gaya", "palette-outline", "Gaya")
                }
                else -> panelCell("gaya", "palette-outline", "Gaya")
            }
        } else {
            panelCell("gaya", "palette-outline", "Gaya")
        }
        panelCell("posisi", "move-resize", "Posisi")
        dockCell(null, "crosshairs-gps", "Pusat") { centerSelection(true, true) }
        dockCell(null, "content-duplicate", "Duplikat") { duplicateSelection() }
        dockCell(null, "arrange-bring-forward", "Depan") { moveLayers(1) }
        dockCell(null, "arrange-send-backward", "Belakang") { moveLayers(-1) }
        if (sel.size > 1) {
            val grouped = sel.map { it.groupId }.distinct().let { it.size == 1 && it[0] != null }
            if (grouped) dockCell(null, "ungroup", "Lepas grup") { ungroup() } else dockCell(null, "group", "Grup") { group() }
        }
        dockCell(null, "lock-outline", "Kunci") { checkpoint(); editable().forEach { it.locked = true }; changed() }
        dockCell(null, "delete-outline", "Hapus") { deleteSelection() }
        dockDivider()
        dockCell(null, "selection-multiple", if (p.multiSelect) "Pilih: aktif" else "Pilih banyak") { toggleMultiSelect() }
        styleDock()
        dockScroll.scrollTo(0, 0)
    }

    private fun syncSelectionBack() {
        if (p.selection.isNotEmpty()) {
            if (!selBackRegistered) { OverlayBack.push(selectionBack); selBackRegistered = true }
        } else if (selBackRegistered) {
            OverlayBack.remove(selectionBack)
            selBackRegistered = false
        }
    }

    private fun onSelectionChanged(rerenderPanel: Boolean = true) {
        renderDock()
        syncSelectionBack()
        val key = panelKey ?: return
        if (!panelValid(key)) { closePanel(); return }
        if (rerenderPanel && key != "ekspor" && key != "tambah" && key != "foto" && key != "bentuk" && key != "stiker") renderPanel(key)
    }

    /** Panel khusus jenis layer ditutup saat seleksi tidak lagi cocok. */
    private fun panelValid(key: String): Boolean = when {
        key.startsWith("p_") -> primaryImage() != null
        key == "gaya" || key == "posisi" -> p.selection.isNotEmpty()
        else -> true
    }

    private fun toggleMultiSelect() {
        p.multiSelect = !p.multiSelect
        dockSignature = "?"
        renderDock()
        a.toast(if (p.multiSelect) "Pilih banyak: ketuk objek untuk menambah/mengurangi" else "Pilih banyak mati")
    }

    private fun selectAll() {
        p.selection.clear()
        p.elements.filter { it.visible }.forEach { p.selection.add(it.id) }
        changed()
    }

    // ================================================================== aksi seleksi

    /** Satu ketukan: taruh seleksi tepat di tengah kanvas (horizontal, vertikal, atau keduanya). */
    private fun centerSelection(horizontal: Boolean, vertical: Boolean) {
        val els = movable()
        if (els.isEmpty()) { a.toast(if (selectedList().any { it.fitCanvas }) "Foto utama selalu memenuhi kanvas" else "Pilih objek yang tidak terkunci"); return }
        checkpoint()
        val (dx, dy) = DesignLayout.centerGroup(els.map { boundsOf(it) }, p.canvasW.toFloat(), p.canvasH.toFloat(), horizontal, vertical)
        els.forEach { it.x += dx; it.y += dy }
        canvasView.flashCenter(horizontal, vertical)
        changed()
    }

    private fun alignSelection(mode: DsAlign) {
        val els = movable()
        if (els.isEmpty()) { a.toast("Pilih objek dulu"); return }
        checkpoint()
        val moves = DesignLayout.align(els.map { boundsOf(it) }, mode, p.canvasW.toFloat(), p.canvasH.toFloat())
        els.forEachIndexed { i, e -> e.x += moves[i].first; e.y += moves[i].second }
        changed()
    }

    private fun distributeSelection(horizontal: Boolean) {
        val els = movable()
        if (els.size < 3) { a.toast("Distribusi butuh minimal 3 objek terpilih"); return }
        checkpoint()
        val moves = DesignLayout.distribute(els.map { boundsOf(it) }, horizontal)
        els.forEachIndexed { i, e -> if (horizontal) e.x += moves[i] else e.y += moves[i] }
        changed()
    }

    private fun nudge(dx: Float, dy: Float) {
        val els = movable()
        if (els.isEmpty()) return
        checkpoint()
        els.forEach { it.x += dx; it.y += dy }
        changed()
    }

    private fun group() {
        val els = selectedList()
        if (els.size < 2) return
        checkpoint()
        val g = UUID.randomUUID().toString()
        els.forEach { it.groupId = g }
        a.toast("${els.size} objek digrup")
        changed()
    }

    private fun ungroup() {
        checkpoint()
        selectedList().forEach { it.groupId = null }
        a.toast("Grup dilepas")
        changed()
    }

    /** Pindah urutan layer seleksi satu langkah (1 = ke depan, -1 = ke belakang). */
    private fun moveLayers(dir: Int) {
        val ids = p.selection.toSet()
        if (ids.isEmpty()) return
        val idx = p.elements.indices.filter { p.elements[it].id in ids }
        if (idx.isEmpty()) return
        val blocked = if (dir > 0) idx.last() >= p.elements.size - 1 else idx.first() <= 0
        if (blocked) { a.toast(if (dir > 0) "Sudah paling depan" else "Sudah paling belakang"); return }
        checkpoint()
        val order = if (dir > 0) idx.reversed() else idx
        for (i in order) {
            val t = i + dir
            if (t !in p.elements.indices || p.elements[t].id in ids) continue
            val tmp = p.elements[t]
            p.elements[t] = p.elements[i]
            p.elements[i] = tmp
        }
        changed()
    }

    /** Pindahkan seleksi ke paling depan / paling belakang. */
    private fun moveLayersToEdge(front: Boolean) {
        val ids = p.selection.toSet()
        if (ids.isEmpty()) return
        checkpoint()
        val picked = p.elements.filter { it.id in ids }
        p.elements.removeAll { it.id in ids }
        if (front) p.elements.addAll(picked) else p.elements.addAll(0, picked)
        changed()
    }

    private fun duplicateSelection() {
        val els = selectedList()
        if (els.isEmpty()) return
        checkpoint()
        val groupMap = HashMap<String, String>()
        val copies = els.map { e ->
            e.copy(
                id = UUID.randomUUID().toString(),
                x = if (e.fitCanvas) e.x else e.x + 24f, y = if (e.fitCanvas) e.y else e.y + 24f,
                locked = false, fitCanvas = false, role = "",
                groupId = e.groupId?.let { g -> groupMap.getOrPut(g) { UUID.randomUUID().toString() } }
            )
        }
        p.elements.addAll(copies)
        p.selection.clear()
        copies.forEach { p.selection.add(it.id) }
        changed()
    }

    private fun deleteSelection() {
        val ids = editable().map { it.id }.toSet()
        if (ids.isEmpty()) return
        checkpoint()
        p.elements.removeAll { it.id in ids }
        p.selection.removeAll(ids)
        changed()
    }

    private fun editText(el: CsElement) {
        val input = EditText(a).apply {
            setText(el.text)
            setSelection(el.text.length)
            textSize = 15f
            minLines = 2
            maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val wrap = FrameLayout(a).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, FrameLayout.LayoutParams(-1, -2)) }
        val dialog = a.themedDialog()
            .setTitle("Edit teks")
            .setView(wrap)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Terapkan") { _, _ ->
                checkpoint()
                el.text = input.text?.toString().orEmpty().ifBlank { "Teks" }
                CsRender.fitText(el, measurePaint)
                changed()
            }
            .create()
        // Keyboard muncul langsung dan dialog naik di atasnya.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        input.requestFocus()
    }

    // ================================================================== laci panel

    private fun buildPanelHost() {
        panelHost.orientation = LinearLayout.VERTICAL
        panelHost.background = c.shape(c.surface, 0)
        panelHost.visibility = View.GONE
        panelHost.isClickable = true
        panelHost.addView(View(a).apply { setBackgroundColor(c.stroke) }, LinearLayout.LayoutParams(-1, 1))
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(4), 0)
        }
        panelTitle.apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c.ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        head.addView(panelTitle, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(kit.icon("chevron-down", 22f, c.ink, "Tutup panel") { closePanel() }, LinearLayout.LayoutParams(dp(44), dp(40)))
        panelHost.addView(head, LinearLayout.LayoutParams(-1, dp(42)))
        panelBody.orientation = LinearLayout.VERTICAL
        panelBody.setPadding(dp(12), 0, dp(12), dp(10))
        panelScroll.isVerticalScrollBarEnabled = false
        panelScroll.overScrollMode = View.OVER_SCROLL_NEVER
        panelScroll.addView(panelBody, FrameLayout.LayoutParams(-1, -2))
        panelHost.addView(panelScroll, LinearLayout.LayoutParams(-1, -2))
    }

    private fun togglePanel(key: String) {
        if (panelKey == key) closePanel() else openPanel(key)
    }

    private fun openPanel(key: String) {
        if (!panelValid(key)) return
        val wasOpen = panelKey != null
        panelKey = key
        // Laci maksimal ±45% tinggi studio agar kanvas tetap terlihat dan bisa disentuh.
        val h = if (root.height > 0) root.height else a.resources.displayMetrics.heightPixels
        panelScroll.maxHeightPx = (h * 0.44f).toInt().coerceAtLeast(dp(190))
        panelHost.visibility = View.VISIBLE
        renderPanel(key)
        panelScroll.scrollTo(0, 0)
        OverlayBack.push(panelBack)
        styleDock()
        if (!wasOpen && a.animationsEnabled()) {
            panelHost.alpha = 0f
            panelHost.translationY = dp(40).toFloat()
            panelHost.animate().alpha(1f).translationY(0f).setDuration(160L).start()
        }
    }

    private fun closePanel() {
        if (panelKey == null) return
        panelKey = null
        stickerReplace = false
        OverlayBack.remove(panelBack)
        hideKeyboard()
        panelHost.visibility = View.GONE
        styleDock()
    }

    private fun hideKeyboard() {
        val imm = a.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun renderPanel(key: String) {
        panelBody.removeAllViews()
        if (key.startsWith("p_")) {
            val el = primaryImage()
            if (el == null) { panelTitle.text = "Foto"; note("Pilih satu layer foto untuk memakai alat ini."); return }
            photo.render(key, el)
            return
        }
        when (key) {
            "tambah" -> renderAddPanel()
            "foto" -> renderPhotoPanel()
            "bentuk" -> renderShapePanel()
            "stiker" -> renderStickerPanel()
            "teks" -> renderTextPanel()
            "posisi" -> renderArrangePanel()
            "gaya" -> renderStylePanel()
            "layer" -> renderLayerPanel()
            "kanvas" -> renderCanvasPanel()
            "latarkanvas" -> renderBackgroundPanel()
            "tampilan" -> renderViewPanel()
            else -> renderExportPanel()
        }
    }

    private fun hScroll(build: (LinearLayout) -> Unit): View {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        build(row)
        return HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }
    }

    // ---- Tambah

    private fun addElement(el: CsElement) {
        checkpoint()
        p.elements.add(el)
        selectOnly(el)
        changed()
    }

    /** Teks baru langsung ditaruh tepat di tengah kanvas. */
    private fun addText(value: String, size: Float, bold: Boolean) {
        val el = CsElement(type = CsType.TEXT, x = 0f, y = 0f, w = 10f, h = 10f, text = value,
            textSize = size * max(p.canvasW, p.canvasH) / 1280f, bold = bold, align = CsAlign.CENTER,
            color = if (onLightBg()) Color.rgb(20, 20, 22) else Color.WHITE)
        CsRender.fitText(el, measurePaint)
        el.x = (p.canvasW - el.w) / 2f
        el.y = (p.canvasH - el.h) / 2f
        addElement(el)
        openPanel("teks")
        editText(el)
    }

    private fun addShape(type: CsType, rounded: Boolean = false) {
        val base = min(p.canvasW, p.canvasH) * 0.4f
        val (w, h) = when (type) {
            CsType.LINE -> p.canvasW * 0.5f to max(4f, min(p.canvasW, p.canvasH) / 120f)
            CsType.RECT -> base * 1.3f to base * 0.8f
            else -> base to base
        }
        val ink = if (onLightBg()) Color.rgb(30, 30, 34) else Color.rgb(235, 235, 238)
        addElement(CsElement(type = type, x = (p.canvasW - w) / 2f, y = (p.canvasH - h) / 2f, w = w, h = h, color = ink,
            cornerRadius = if (rounded) min(w, h) * 0.2f else 0f))
    }

    private fun addSticker(icon: String, emoji: String) {
        val side = min(p.canvasW, p.canvasH) * 0.25f
        val cur = primary()
        if (stickerReplace && cur != null && cur.type == CsType.STICKER) {
            checkpoint()
            cur.icon = icon
            cur.text = emoji
            stickerReplace = false
            changed()
            return
        }
        addElement(CsElement(type = CsType.STICKER, x = (p.canvasW - side) / 2f, y = (p.canvasH - side) / 2f, w = side, h = side,
            icon = icon, text = emoji, color = if (onLightBg()) Color.rgb(20, 20, 22) else Color.WHITE))
    }

    private fun textTiles() = tiles(
        kit.tile("format-size", "Judul") { addText("Judul", 96f, true) },
        kit.tile("format-text", "Subjudul") { addText("Subjudul", 56f, true) },
        kit.tile("text-box-outline", "Isi teks") { addText("Tulis teks di sini", 36f, false) }
    )

    private fun shapeTiles() {
        tiles(
            kit.tile("rectangle-outline", "Kotak") { addShape(CsType.RECT) },
            kit.tile("square-rounded-outline", "Kotak bulat") { addShape(CsType.RECT, rounded = true) },
            kit.tile("shape-circle-plus", "Oval") { addShape(CsType.ELLIPSE) },
            kit.tile("triangle-outline", "Segitiga") { addShape(CsType.TRIANGLE) }
        )
        tiles(
            kit.tile("vector-line", "Garis") { addShape(CsType.LINE) },
            View(a), View(a), View(a)
        )
    }

    private fun renderAddPanel() {
        panelTitle.text = "Tambah elemen"
        caption("TEKS")
        textTiles()
        caption("FOTO")
        tiles(
            kit.tile("image-plus", "Foto") { addPhotoLayer(false) },
            kit.tile("image-remove", "Foto tanpa latar") { addPhotoLayer(true) },
            kit.tile("sticker-outline", "Stiker") { stickerReplace = false; openPanel("stiker") }
        )
        caption("BENTUK")
        shapeTiles()
        note("Objek baru selalu muncul tepat di tengah kanvas. Tekan lama objek untuk memilih banyak.")
    }

    private fun renderPhotoPanel() {
        panelTitle.text = "Foto"
        caption("PROYEK BARU DARI FOTO")
        tiles(
            kit.tile("image-edit-outline", "Edit foto") { openPhotoDocument(false) },
            kit.tile("image-remove", "Hapus latar") { openPhotoDocument(true) }
        )
        note("Kanvas mengikuti ukuran foto (resolusi penuh s.d. 3072 px). Desain yang sedang dibuka disimpan dulu ke Proyek.")
        caption("TAMBAH KE DESAIN INI")
        tiles(
            kit.tile("image-plus", "Foto") { addPhotoLayer(false) },
            kit.tile("image-remove", "Tanpa latar (AI)") { addPhotoLayer(true) }
        )
        note("Pilih layer foto untuk Atur, Filter, Kurva, Detail, Potong, Heal, dan Hapus latar.")
    }

    private fun renderShapePanel() {
        panelTitle.text = "Bentuk"
        shapeTiles()
        note("Isi bisa solid atau gradien (panel Gaya). Kotak bisa bersudut bulat.")
    }

    private fun renderStickerPanel() {
        val replacing = stickerReplace && primary()?.type == CsType.STICKER
        panelTitle.text = if (replacing) "Ganti stiker" else "Stiker"
        val all: List<Pair<String, String>> = PeLayerRender.STICKERS.map { it to "" } + PeLayerRender.EMOJI.map { "" to it }
        val cols = 8
        for (r in 0 until (all.size + cols - 1) / cols) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until cols) {
                val item = all.getOrNull(r * cols + k)
                if (item == null) { row.addView(View(a), LinearLayout.LayoutParams(0, dp(46), 1f)); continue }
                val (icon, emoji) = item
                val v: View = if (icon.isNotEmpty()) kit.icon(icon, 24f, c.ink, icon) { addSticker(icon, "") }
                else kit.text(emoji, 24f, c.ink).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    background = c.pressable(Color.TRANSPARENT, 12)
                    contentDescription = "Emoji $emoji"
                    setOnClickListener { addSticker("", emoji) }
                }
                row.addView(v, LinearLayout.LayoutParams(0, dp(46), 1f))
            }
            panelBody.addView(row)
        }
        note("Stiker ikon bisa diwarnai, diberi garis & bayangan (panel Gaya). Emoji memakai warna aslinya.")
    }

    // ---- Teks

    private fun renderTextPanel() {
        panelTitle.text = "Teks"
        val el = primary()?.takeIf { it.type == CsType.TEXT && selectedList().size == 1 }
        if (el == null) {
            note("Pilih satu objek teks, atau tambah teks baru.")
            textTiles()
            return
        }
        if (el.locked) { note("Teks terkunci. Buka kunci untuk mengubah."); return }
        panelBody.addView(kit.secondaryButton("Edit isi teks: \"${el.text.replace("\n", " ").take(24)}\"") { editText(el) },
            LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(4) })
        caption("FONT")
        panelBody.addView(hScroll { row ->
            for ((key, label) in PeLayerRender.FONTS) {
                row.addView(kit.pill(label, el.font == key) { checkpoint(); el.font = key; CsRender.fitText(el, measurePaint); changed() }.apply {
                    typeface = PeLayerRender.typeface(key)
                }, LinearLayout.LayoutParams(-2, dp(34)).apply { rightMargin = dp(6) })
            }
        })
        caption("GAYA")
        tiles(
            kit.tile("format-bold", "Tebal", el.bold) { checkpoint(); el.bold = !el.bold; CsRender.fitText(el, measurePaint); changed() },
            kit.tile("format-italic", "Miring", el.italic) { checkpoint(); el.italic = !el.italic; CsRender.fitText(el, measurePaint); changed() },
            kit.tile("format-underline", "Garis bawah", el.underline) { checkpoint(); el.underline = !el.underline; changed() },
            height = 56
        )
        panelBody.addView(kit.segmented(listOf("LEFT" to "Kiri", "CENTER" to "Tengah", "RIGHT" to "Kanan"), el.align.name) { k ->
            checkpoint(); el.align = CsAlign.valueOf(k); changed()
        }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(4); bottomMargin = dp(4) })
        caption("WARNA")
        for (rowIdx in 0 until 2) {
            panelBody.addView(kit.swatchRow(palette.subList(rowIdx * 8, rowIdx * 8 + 8), el.color) { col -> checkpoint(); el.color = col or 0xFF000000.toInt(); changed() },
                LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(6) })
        }
        caption("UKURAN & SPASI")
        panelBody.addView(liveSlider("Ukuran", 8, 400, el.textSize.roundToInt(), { "${it}px" }) { v ->
            val cx = el.x + el.w / 2f
            val cy = el.y + el.h / 2f
            el.textSize = v.toFloat()
            CsRender.fitText(el, measurePaint)
            el.x = cx - el.w / 2f
            el.y = cy - el.h / 2f
        })
        panelBody.addView(liveSlider("Spasi huruf", -10, 50, (el.letterSpacing * 100).roundToInt(), { String.format(Locale.US, "%.2f", it / 100f) }) { v ->
            el.letterSpacing = v / 100f; CsRender.fitText(el, measurePaint)
        })
        panelBody.addView(liveSlider("Spasi baris", 80, 250, (el.lineSpacing * 100).roundToInt(), { String.format(Locale.US, "%.1f×", it / 100f) }) { v ->
            el.lineSpacing = v / 100f; CsRender.fitText(el, measurePaint)
        })
        caption("OUTLINE")
        panelBody.addView(liveSlider("Outline", 0, 40, el.strokeWidth.roundToInt(), { "${it}px" }) { v ->
            el.strokeWidth = v.toFloat()
            if (v > 0 && Color.alpha(el.strokeColor) == 0) el.strokeColor = if (onLightBg()) Color.WHITE else Color.BLACK
        })
        panelBody.addView(kit.swatchRow(listOf(Color.BLACK, Color.WHITE, Color.rgb(128, 128, 132), Color.rgb(239, 68, 68), Color.rgb(234, 179, 8), Color.rgb(59, 130, 246)),
            el.strokeColor.takeIf { el.strokeWidth > 0f }) { col ->
            checkpoint(); el.strokeColor = col; if (el.strokeWidth <= 0f) el.strokeWidth = 4f; changed()
        }, LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(4) })
        caption("KOTAK LATAR TEKS")
        val boxColors = listOf(Color.TRANSPARENT, Color.BLACK, Color.WHITE, Color.rgb(24, 24, 27), Color.rgb(239, 68, 68), Color.rgb(234, 179, 8), Color.rgb(59, 130, 246))
        panelBody.addView(kit.swatchRow(boxColors, el.textBg.takeIf { Color.alpha(it) > 0 } ?: Color.TRANSPARENT) { col ->
            checkpoint()
            el.textBg = if (col == Color.TRANSPARENT) 0 else Color.argb(Color.alpha(el.textBg).takeIf { it > 0 } ?: 230, Color.red(col), Color.green(col), Color.blue(col))
            changed()
        }, LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(4) })
        if (Color.alpha(el.textBg) > 0) {
            panelBody.addView(liveSlider("Padding", 0, 150, (el.textBgPad * 100).roundToInt(), { String.format(Locale.US, "%.2f em", it / 100f) }) { v -> el.textBgPad = v / 100f })
            panelBody.addView(liveSlider("Sudut", 0, 200, el.cornerRadius.roundToInt(), { "${it}px" }) { v -> el.cornerRadius = v.toFloat() })
            panelBody.addView(liveSlider("Pekat", 20, 255, Color.alpha(el.textBg), { "${it * 100 / 255}%" }) { v ->
                el.textBg = Color.argb(v, Color.red(el.textBg), Color.green(el.textBg), Color.blue(el.textBg))
            })
        }
        caption("BAYANGAN")
        shadowControls(el)
    }

    // ---- Posisi (atur & perataan)

    private fun renderArrangePanel() {
        panelTitle.text = "Posisi & perataan"
        val els = movable()
        if (els.isEmpty()) {
            note(when {
                selectedList().any { it.fitCanvas } -> "Foto utama selalu memenuhi kanvas. Matikan \"Kunci ke kanvas\" di panel Gaya untuk memindahkannya."
                selectedList().isNotEmpty() -> "Objek terkunci. Buka kunci untuk memindahkan."
                else -> "Pilih objek di kanvas untuk mengatur posisi. Titik pusat kanvas: ${p.canvasW / 2}, ${p.canvasH / 2}."
            })
            return
        }
        val multi = els.size > 1
        caption(if (multi) "PUSATKAN ${els.size} OBJEK" else "PUSATKAN KE KANVAS")
        tiles(
            kit.tile("align-horizontal-center", "Tengah H") { centerSelection(true, false) },
            kit.tile("align-vertical-center", "Tengah V") { centerSelection(false, true) },
            kit.tile("crosshairs-gps", "Pusat", true) { centerSelection(true, true) },
            height = 60
        )
        caption(if (multi) "RATAKAN KE SELEKSI" else "RATAKAN KE TEPI KANVAS")
        tiles(
            kit.tile("align-horizontal-left", "Kiri") { alignSelection(DsAlign.LEFT) },
            kit.tile("align-horizontal-right", "Kanan") { alignSelection(DsAlign.RIGHT) },
            kit.tile("align-vertical-top", "Atas") { alignSelection(DsAlign.TOP) },
            kit.tile("align-vertical-bottom", "Bawah") { alignSelection(DsAlign.BOTTOM) },
            height = 56
        )
        if (multi) {
            tiles(
                kit.tile("align-horizontal-center", "Rata tengah") { alignSelection(DsAlign.H_CENTER) },
                kit.tile("align-vertical-center", "Rata tengah V") { alignSelection(DsAlign.V_CENTER) },
                kit.tile("distribute-horizontal-center", "Distribusi H") { distributeSelection(true) },
                kit.tile("distribute-vertical-center", "Distribusi V") { distributeSelection(false) },
                height = 56
            )
        }
        caption("POSISI & UKURAN (PX)")
        val el = primary()?.takeIf { !it.locked && !it.fitCanvas } ?: els.last()
        if (!multi) {
            val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            fun pair(l1: String, v1: Float, s1: (Float) -> Unit, l2: String, v2: Float, s2: (Float) -> Unit) {
                val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(numField(l1, v1, s1), LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
                row.addView(numField(l2, v2, s2), LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4) })
                grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
            pair("X", el.x, { v -> el.x = v }, "Y", el.y, { v -> el.y = v })
            pair("W", el.w, { v -> setSize(el, v, null) }, "H", el.h, { v -> setSize(el, null, v) })
            val rotRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            rotRow.addView(numField("Rotasi °", el.rotation, { v -> el.rotation = CsMath.normDeg(v) }), LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
            rotRow.addView(kit.icon("rotate-left", 21f, c.ink, "Putar -90°") { checkpoint(); el.rotation = CsMath.normDeg(el.rotation - 90f); changed() }.apply { background = c.pressable(c.surface2, 12) },
                LinearLayout.LayoutParams(dp(48), dp(44)).apply { leftMargin = dp(4) })
            rotRow.addView(kit.icon("rotate-right", 21f, c.ink, "Putar +90°") { checkpoint(); el.rotation = CsMath.normDeg(el.rotation + 90f); changed() }.apply { background = c.pressable(c.surface2, 12) },
                LinearLayout.LayoutParams(dp(48), dp(44)).apply { leftMargin = dp(6) })
            rotRow.addView(kit.icon("refresh", 21f, c.ink, "Reset rotasi") { checkpoint(); el.rotation = 0f; changed() }.apply { background = c.pressable(c.surface2, 12) },
                LinearLayout.LayoutParams(dp(48), dp(44)).apply { leftMargin = dp(6) })
            grid.addView(rotRow)
            val tiltRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            tiltRow.addView(numField("Miring X°", el.tiltX, { v -> el.tiltX = v.coerceIn(-Pe3D.MAX_TILT, Pe3D.MAX_TILT) }), LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
            tiltRow.addView(numField("Miring Y°", el.tiltY, { v -> el.tiltY = v.coerceIn(-Pe3D.MAX_TILT, Pe3D.MAX_TILT) }), LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
            tiltRow.addView(kit.icon("refresh", 21f, c.ink, "Reset miring 3D") { checkpoint(); el.tiltX = 0f; el.tiltY = 0f; changed() }.apply { background = c.pressable(c.surface2, 12) },
                LinearLayout.LayoutParams(dp(48), dp(44)).apply { leftMargin = dp(6) })
            grid.addView(tiltRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            val depthRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            depthRow.addView(numField("Kedalaman %", el.depth * 100f, { v -> el.depth = (v / 100f).coerceIn(0f, 1f) }), LinearLayout.LayoutParams(0, dp(44), 1f))
            grid.addView(depthRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            grid.addView(kit.toggleRow("Parallax ikut gerak HP (pratinjau)", parallaxLive) { v -> setParallaxLive(v) }, LinearLayout.LayoutParams(-1, dp(40)))
            panelBody.addView(grid)
        } else {
            val u = els.map { boundsOf(it) }.reduce { acc, r -> acc.union(r) }
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(numField("X", u.left, { v -> val d = v - u.left; els.forEach { it.x += d } }), LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
            row.addView(numField("Y", u.top, { v -> val d = v - u.top; els.forEach { it.y += d } }), LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4) })
            panelBody.addView(row)
            note("Seleksi ${u.width.roundToInt()}×${u.height.roundToInt()} px.")
        }
        caption("GESER PRESISI")
        val pad = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun arrow(iconName: String, desc: String, dx: Float, dy: Float) =
            pad.addView(kit.icon(iconName, 22f, c.ink, desc) { nudge(dx * nudgeStep, dy * nudgeStep) }.apply { background = c.pressable(c.surface2, 12) },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        arrow("arrow-left-bold", "Geser kiri", -1f, 0f)
        arrow("arrow-up-bold", "Geser atas", 0f, -1f)
        arrow("arrow-down-bold", "Geser bawah", 0f, 1f)
        arrow("arrow-right-bold", "Geser kanan", 1f, 0f)
        pad.addView(kit.segmented(listOf("1" to "1px", "10" to "10px"), if (nudgeStep >= 10f) "10" else "1") { k ->
            nudgeStep = k.toFloat(); renderPanel("posisi")
        }, LinearLayout.LayoutParams(dp(104), dp(40)).apply { leftMargin = dp(4) })
        panelBody.addView(pad)
        if (els.size > 1) {
            val grouped = els.map { it.groupId }.distinct().let { it.size == 1 && it[0] != null }
            panelBody.addView(kit.secondaryButton(if (grouped) "Lepas grup" else "Grup ${els.size} objek") { if (grouped) ungroup() else group() },
                LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(10) })
        }
    }

    private fun setSize(el: CsElement, w: Float?, h: Float?) {
        val cx = el.x + el.w / 2f
        val cy = el.y + el.h / 2f
        if (el.type == CsType.TEXT) {
            // Teks diskalakan seragam lewat ukuran huruf agar tetap proporsional.
            val k = when {
                w != null && el.w > 0f -> w / el.w
                h != null && el.h > 0f -> h / el.h
                else -> 1f
            }
            el.textSize = (el.textSize * k).coerceIn(4f, 2000f)
            CsRender.fitText(el, measurePaint)
        } else {
            if (w != null) el.w = w.coerceIn(1f, CsMath.MAX_CANVAS * 4f)
            if (h != null) el.h = h.coerceIn(1f, CsMath.MAX_CANVAS * 4f)
        }
        el.x = cx - el.w / 2f
        el.y = cy - el.h / 2f
    }

    /** Kolom angka berlabel; nilai diterapkan saat tombol Selesai keyboard ditekan atau fokus pindah. */
    /** Parallax pratinjau: layer bergeser mengikuti gravitasi HP. Ekspor memakai preset Parallax di Animasi. */
    private fun setParallaxLive(on: Boolean) {
        val sm = a.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        gravitySensor?.let { sm?.unregisterListener(it) }
        gravitySensor = null
        tiltNx = 0f
        tiltNy = 0f
        parallaxLive = on && sensor != null
        if (on && sensor == null) a.toast("HP ini tidak punya sensor gravitasi")
        if (parallaxLive && sm != null && sensor != null) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    tiltNx = (e.values[0] / 9.8f).coerceIn(-1f, 1f)
                    tiltNy = (e.values[1] / 9.8f).coerceIn(-1f, 1f)
                    if (alive) canvasView.invalidate()
                }
                override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            gravitySensor = listener
        }
        canvasView.invalidate()
    }

    private fun numField(label: String, value: Float, apply: (Float) -> Unit): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = c.shape(c.surface2, 12)
            setPadding(dp(10), 0, dp(4), 0)
        }
        box.addView(kit.text(label, 11.5f, c.muted, true), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        val start = fmtNum(value)
        val et = EditText(a).apply {
            setText(start)
            textSize = 14f
            setTextColor(c.ink)
            background = null
            setSingleLine(true)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = label
            setPadding(0, 0, 0, 0)
        }
        var committed = start
        // Selesai (keyboard) → terapkan + segarkan panel; pindah fokus → terapkan tanpa membangun ulang.
        fun commit(refresh: Boolean) {
            val txt = et.text?.toString()?.trim().orEmpty().replace(',', '.')
            if (txt == committed) { if (refresh) renderPanel("posisi"); return }
            val v = txt.toFloatOrNull() ?: run { et.setText(committed); return }
            committed = txt
            checkpoint()
            apply(v)
            changed(rerenderPanel = refresh)
        }
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { hideKeyboard(); commit(true); true } else false
        }
        et.setOnFocusChangeListener { _, has -> if (!has) commit(false) }
        box.addView(et, LinearLayout.LayoutParams(0, -1, 1f))
        return box
    }

    private fun fmtNum(v: Float): String =
        if (abs(v - v.roundToInt()) < 0.05f) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)

    // ---- Gaya (warna, gradien, garis, sudut, opasitas, mode campur, bayangan)

    private fun hexRow(current: Int, onApply: (Int) -> Unit) {
        val hexRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val hex = EditText(a).apply {
            hint = "#RRGGBB"
            setText(if (Color.alpha(current) > 0) String.format(Locale.US, "#%06X", 0xFFFFFF and current) else "")
            textSize = 13f
            setTextColor(c.ink)
            setHintTextColor(c.muted)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = c.shape(c.surface2, 10)
            setPadding(dp(10), 0, dp(10), 0)
        }
        fun applyHex() {
            val parsed = runCatching { Color.parseColor(hex.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }) }.getOrNull()
            if (parsed == null) a.toast("Format warna: #RRGGBB") else { hideKeyboard(); onApply(parsed) }
        }
        hex.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { applyHex(); true } else false }
        hexRow.addView(hex, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
        hexRow.addView(kit.primaryButton("Pakai") { applyHex() }, LinearLayout.LayoutParams(dp(76), dp(40)))
        panelBody.addView(hexRow)
    }

    private fun renderStylePanel() {
        panelTitle.text = "Gaya"
        val els = editable()
        val el = primary()?.takeIf { !it.locked }
        if (el == null) { note(if (selectedList().isNotEmpty()) "Objek terkunci. Buka kunci untuk mengubah gaya." else "Pilih objek untuk mengatur gaya."); return }
        if (el.type == CsType.IMAGE && els.size == 1) { renderImageStyle(el); return }
        val targets = listOf("isi" to "Isi", "garis" to "Garis")
        if (colorTarget !in setOf("isi", "garis")) colorTarget = "isi"
        panelBody.addView(kit.segmented(targets, colorTarget) { k -> colorTarget = k; renderPanel("gaya") },
            LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(4); bottomMargin = dp(8) })
        val current = if (colorTarget == "garis") el.strokeColor else el.color
        for (rowIdx in 0 until 2) {
            panelBody.addView(kit.swatchRow(palette.subList(rowIdx * 8, rowIdx * 8 + 8), current.takeIf { Color.alpha(it) > 0 }) { col -> applyColor(col) },
                LinearLayout.LayoutParams(-1, dp(34)).apply { bottomMargin = dp(6) })
        }
        hexRow(current) { applyColor(it) }
        val shape = el.type == CsType.RECT || el.type == CsType.ELLIPSE || el.type == CsType.TRIANGLE || el.type == CsType.LINE
        if (shape && colorTarget == "isi") {
            caption("GRADIEN")
            panelBody.addView(kit.toggleRow("Isi gradien (warna 1 → warna 2)", el.gradient) { on ->
                checkpoint(); els.forEach { it.gradient = on }; changed()
            }, LinearLayout.LayoutParams(-1, dp(40)))
            if (el.gradient) {
                panelBody.addView(kit.swatchRow(palette.subList(0, 8), el.color2) { col -> checkpoint(); els.forEach { it.color2 = col or 0xFF000000.toInt() }; changed() },
                    LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(6) })
                panelBody.addView(kit.swatchRow(palette.subList(8, 16), el.color2) { col -> checkpoint(); els.forEach { it.color2 = col or 0xFF000000.toInt() }; changed() },
                    LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(4) })
                panelBody.addView(liveSlider("Sudut", 0, 359, el.gradAngle.roundToInt(), { "$it°" }) { v -> els.forEach { it.gradAngle = v.toFloat() } })
            }
        }
        caption("OPASITAS & CAMPUR")
        panelBody.addView(liveSlider("Opasitas", 0, 100, (el.opacity * 100).roundToInt(), { "$it%" }) { v -> els.forEach { it.opacity = v / 100f } })
        blendRow(els, el)
        when (el.type) {
            CsType.LINE -> {
                caption("GARIS")
                panelBody.addView(liveSlider("Tebal", 1, 120, el.h.roundToInt(), { "${it}px" }) { v ->
                    val cy = el.y + el.h / 2f; el.h = v.toFloat(); el.y = cy - el.h / 2f
                })
            }
            CsType.TEXT, CsType.STICKER -> {
                caption("OUTLINE")
                panelBody.addView(liveSlider("Outline", 0, 40, el.strokeWidth.roundToInt(), { "${it}px" }) { v ->
                    el.strokeWidth = v.toFloat()
                    if (v > 0 && Color.alpha(el.strokeColor) == 0) el.strokeColor = Color.BLACK
                })
            }
            else -> {
                caption("GARIS TEPI")
                panelBody.addView(liveSlider("Tebal garis", 0, 60, el.strokeWidth.roundToInt(), { "${it}px" }) { v ->
                    el.strokeWidth = v.toFloat()
                    if (v > 0 && Color.alpha(el.strokeColor) == 0) el.strokeColor = if (onLightBg()) Color.BLACK else Color.WHITE
                })
                if (el.type == CsType.RECT) {
                    val maxR = (min(el.w, el.h) / 2f).roundToInt().coerceAtLeast(1)
                    panelBody.addView(liveSlider("Sudut", 0, maxR, el.cornerRadius.roundToInt().coerceAtMost(maxR), { "${it}px" }) { v -> el.cornerRadius = v.toFloat() })
                }
            }
        }
        caption("BAYANGAN")
        shadowControls(el)
    }

    private fun blendRow(els: List<CsElement>, el: CsElement) {
        panelBody.addView(hScroll { row ->
            for ((key, label) in PeLayerRender.BLENDS) {
                row.addView(kit.pill(label, el.blend == key) { checkpoint(); els.forEach { it.blend = key }; changed() },
                    LinearLayout.LayoutParams(-2, dp(34)).apply { rightMargin = dp(6) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    private fun renderImageStyle(el: CsElement) {
        caption("OPASITAS & CAMPUR")
        panelBody.addView(liveSlider("Opasitas", 0, 100, (el.opacity * 100).roundToInt(), { "$it%" }) { v -> el.opacity = v / 100f })
        blendRow(listOf(el), el)
        caption("BINGKAI")
        panelBody.addView(liveSlider("Tebal garis", 0, 60, el.strokeWidth.roundToInt(), { "${it}px" }) { v ->
            el.strokeWidth = v.toFloat()
            if (v > 0 && Color.alpha(el.strokeColor) == 0) el.strokeColor = Color.WHITE
        })
        if (el.strokeWidth > 0f) {
            panelBody.addView(kit.swatchRow(palette.subList(0, 8), el.strokeColor) { col -> checkpoint(); el.strokeColor = col or 0xFF000000.toInt(); changed() },
                LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(4) })
        }
        val maxR = (min(el.w, el.h) / 2f).roundToInt().coerceAtLeast(1)
        panelBody.addView(liveSlider("Sudut", 0, maxR, el.cornerRadius.roundToInt().coerceAtMost(maxR), { "${it}px" }) { v -> el.cornerRadius = v.toFloat() })
        caption("BAYANGAN")
        shadowControls(el)
        caption("FOTO")
        panelBody.addView(kit.toggleRow("Kunci ke kanvas (foto utama: crop/putar mengubah kanvas)", el.fitCanvas) { on -> setFitCanvas(el, on) },
            LinearLayout.LayoutParams(-1, dp(44)))
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(kit.secondaryButton("Ganti foto") { replacePhoto(el) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
        row.addView(kit.secondaryButton("Reset edit foto") {
            checkpoint()
            // Geometri dikembalikan dulu (butuh geometri lama untuk menghitung ukuran kotak), baru edit lain direset.
            applyGeom(el, PeGeom())
            el.edit = PeEdit()
            el.maskId = null
            el.cut = CsCut()
            changed()
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        panelBody.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        note("Sumber ${el.srcW}×${el.srcH} px. Semua edit foto non-destruktif — file asli tidak pernah diubah.")
    }

    /** Foto utama ↔ foto bebas. Mengaktifkan = kanvas mengikuti ukuran keluaran foto. */
    private fun setFitCanvas(el: CsElement, on: Boolean) {
        checkpoint()
        if (!on) {
            el.fitCanvas = false
            changed()
            return
        }
        val (ow, oh) = outSize(el, el.edit.geom)
        val density = (el.w / ow.coerceAtLeast(1)).coerceAtLeast(0.05f)
        val (nw, nh) = CsMath.docCanvasSize(ow, oh, density)
        // Geser semua layer agar sudut kiri-atas foto menjadi titik nol kanvas baru.
        val dx = el.x
        val dy = el.y
        for (o in p.elements) { o.x -= dx; o.y -= dy }
        p.canvasW = nw
        p.canvasH = nh
        el.x = 0f; el.y = 0f; el.w = nw.toFloat(); el.h = nh.toFloat(); el.rotation = 0f
        el.fitCanvas = true
        canvasView.fit()
        changed()
    }

    private fun applyColor(col: Int) {
        val els = editable().ifEmpty { a.toast("Pilih objek dulu"); return }
        checkpoint()
        if (colorTarget == "garis") {
            els.forEach { it.strokeColor = col or 0xFF000000.toInt(); if (it.strokeWidth <= 0f) it.strokeWidth = if (it.type == CsType.TEXT) 4f else 6f }
        } else {
            els.forEach { it.color = col or 0xFF000000.toInt() }
        }
        changed()
    }

    // ---- Latar kanvas

    private fun renderBackgroundPanel() {
        panelTitle.text = "Latar kanvas"
        val bg = p.bg
        panelBody.addView(kit.segmented(listOf("solid" to "Warna", "gradient" to "Gradien", "transparent" to "Transparan"), bg.mode) { k ->
            setCanvasBackground(p.bg.copy(mode = k))
        }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(4); bottomMargin = dp(8) })
        when (bg.mode) {
            "gradient" -> {
                caption("WARNA 1")
                for (rowIdx in 0 until 2) panelBody.addView(kit.swatchRow(palette.subList(rowIdx * 8, rowIdx * 8 + 8), bg.color) { col -> setCanvasBackground(p.bg.copy(color = col or 0xFF000000.toInt())) },
                    LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(6) })
                caption("WARNA 2")
                for (rowIdx in 0 until 2) panelBody.addView(kit.swatchRow(palette.subList(rowIdx * 8, rowIdx * 8 + 8), bg.color2) { col -> setCanvasBackground(p.bg.copy(color2 = col or 0xFF000000.toInt())) },
                    LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(6) })
                panelBody.addView(kit.slider("Sudut", 0, 359, bg.angle.roundToInt(), 90, format = { "$it°" },
                    onStart = { checkpoint() }, onStop = { changed(rerenderPanel = false) }) { v -> p.bg = p.bg.copy(angle = v.toFloat()); canvasView.invalidate() })
            }
            "transparent" -> note("Kotak-kotak = transparan. Ekspor PNG, WebP, atau SVG untuk mempertahankannya (JPG/PDF diberi latar putih).")
            else -> {
                for (rowIdx in 0 until 2) panelBody.addView(kit.swatchRow(palette.subList(rowIdx * 8, rowIdx * 8 + 8), bg.color) { col -> setCanvasBackground(p.bg.copy(color = col or 0xFF000000.toInt())) },
                    LinearLayout.LayoutParams(-1, dp(34)).apply { bottomMargin = dp(6) })
                hexRow(bg.color) { col -> setCanvasBackground(p.bg.copy(mode = "solid", color = col or 0xFF000000.toInt())) }
            }
        }
    }

    // ---- Layer

    private fun layerName(el: CsElement): String = el.name.ifBlank {
        when (el.type) {
            CsType.TEXT -> el.text.replace("\n", " ").take(28).ifBlank { "Teks" }
            CsType.RECT -> if (el.cornerRadius > 0f) "Kotak bulat" else "Kotak"
            CsType.ELLIPSE -> "Oval"
            CsType.IMAGE -> if (el.fitCanvas) "Foto utama" else "Foto"
            CsType.LINE -> "Garis"
            CsType.TRIANGLE -> "Segitiga"
            CsType.STICKER -> if (el.icon.isNotEmpty()) "Stiker" else "Emoji ${el.text}"
        }
    }

    private fun layerIcon(el: CsElement): String = when (el.type) {
        CsType.TEXT -> "format-text"
        CsType.RECT -> "rectangle-outline"
        CsType.ELLIPSE -> "shape-circle-plus"
        CsType.IMAGE -> "image-outline"
        CsType.LINE -> "vector-line"
        CsType.TRIANGLE -> "triangle-outline"
        CsType.STICKER -> "sticker-outline"
    }

    private fun renameLayer(el: CsElement) {
        val input = EditText(a).apply { setText(layerName(el)); setSelectAllOnFocus(true); setSingleLine(true) }
        val wrap = FrameLayout(a).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, FrameLayout.LayoutParams(-1, -2)) }
        a.themedDialog().setTitle("Nama layer").setView(wrap)
            .setNegativeButton("Batal", null)
            .setPositiveButton("OK") { _, _ -> checkpoint(); el.name = input.text?.toString()?.trim().orEmpty().take(40); changed() }
            .show()
    }

    private fun renderLayerPanel() {
        panelTitle.text = "Layer · ${p.elements.size}"
        if (p.elements.isEmpty()) {
            note("Belum ada layer. Tambah teks, foto, bentuk, atau stiker dari menu Tambah.")
            return
        }
        val sel = selectedList()
        if (sel.isNotEmpty()) {
            tiles(
                kit.tile("arrange-bring-to-front", "Paling depan") { moveLayersToEdge(true) },
                kit.tile("arrange-bring-forward", "Naik") { moveLayers(1) },
                kit.tile("arrange-send-backward", "Turun") { moveLayers(-1) },
                kit.tile("arrange-send-to-back", "Paling belakang") { moveLayersToEdge(false) },
                height = 56
            )
        }
        for (el in p.elements.asReversed()) {
            val on = el.id in p.selection
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(2), 0)
                background = c.pressable(if (on) c.surface2 else c.surface, 12, if (on) c.ink else c.stroke)
                isClickable = true
                isLongClickable = true
                contentDescription = "Layer ${layerName(el)}"
                setOnClickListener {
                    if (p.multiSelect) toggleSelect(el) else selectOnly(el)
                    changed()
                }
                setOnLongClickListener { renameLayer(el); true }
            }
            val swatch = if (el.type == CsType.IMAGE) c.surface2 else el.color or 0xFF000000.toInt()
            row.addView(View(a).apply { background = c.shape(swatch, 6, c.stroke) }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(8) })
            row.addView(kit.icon(layerIcon(el), 17f, c.ink), LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(6) })
            val tags = buildList {
                if (el.groupId != null) add("grup")
                if (el.type == CsType.IMAGE && el.cut.on && el.maskId != null) add("tanpa latar")
                if (el.blend != "normal") add(el.blend)
                if (el.role.isNotEmpty()) add("latar")
            }
            val label = layerName(el) + if (tags.isNotEmpty()) "  · ${tags.joinToString(" · ")}" else ""
            row.addView(kit.text(label, 13f, if (el.visible) c.ink else c.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(kit.icon(if (el.visible) "eye-outline" else "eye-off-outline", 18f, if (el.visible) c.ink else c.muted, if (el.visible) "Sembunyikan" else "Tampilkan") {
                checkpoint(); el.visible = !el.visible; changed()
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            row.addView(kit.icon(if (el.locked) "lock-outline" else "lock-open-outline", 18f, if (el.locked) c.ink else c.muted, if (el.locked) "Buka kunci" else "Kunci") {
                checkpoint(); el.locked = !el.locked; changed()
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            row.addView(kit.icon("delete-outline", 18f, c.muted, "Hapus layer") {
                checkpoint(); p.elements.remove(el); p.selection.remove(el.id); changed()
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            panelBody.addView(row, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(4) })
        }
        note("Tekan lama nama layer untuk mengganti nama.")
    }

    // ---- Kanvas

    private fun resizeCanvasTo(w: Int, h: Int, record: Boolean = true) {
        val nw = w.coerceIn(16, CsMath.MAX_CANVAS)
        val nh = h.coerceIn(16, CsMath.MAX_CANVAS)
        if (nw == p.canvasW && nh == p.canvasH) return
        if (record) checkpoint()
        // Posisi pusat tiap objek dipetakan proporsional; ukuran objek tidak berubah.
        val sx = nw.toFloat() / p.canvasW
        val sy = nh.toFloat() / p.canvasH
        for (e in p.elements) {
            if (e.fitCanvas) {
                // Foto utama tetap memenuhi kanvas: kanvas baru = crop tengah foto dengan rasio baru.
                e.x = 0f; e.y = 0f; e.w = nw.toFloat(); e.h = nh.toFloat()
                continue
            }
            val cx = (e.x + e.w / 2f) * sx
            val cy = (e.y + e.h / 2f) * sy
            e.x = cx - e.w / 2f
            e.y = cy - e.h / 2f
        }
        p.canvasW = nw
        p.canvasH = nh
        // Foto utama: crop ulang ke rasio kanvas agar tidak gepeng.
        for (e in p.elements) if (e.fitCanvas && e.srcW > 0) {
            val g = e.edit.geom
            val (ow, oh) = CsMath.orientedSize(e.srcW, e.srcH, g.rot90)
            val crop = CsMath.centeredCrop(ow, oh, nw / nh.toFloat())
            e.edit = e.edit.copy(geom = g.copy(cl = crop[0], ct = crop[1], cr = crop[2], cb = crop[3], ratio = "bebas"))
            syncBackgroundLayers(e)
        }
        canvasView.fit()
        if (record) {
            changed()
            a.toast("Kanvas ${nw}×${nh}")
        }
    }

    private fun renderCanvasPanel() {
        panelTitle.text = "Kanvas · ${p.canvasW}×${p.canvasH}"
        caption("TAMPILAN")
        tiles(
            kit.tile("fit-to-screen-outline", "Pas layar") { canvasView.fit() },
            kit.tile("magnify-scan", "100%") { canvasView.actualSize() },
            kit.tile("phone-rotate-landscape", if (p.canvasW >= p.canvasH) "Potret" else "Lanskap") { resizeCanvasTo(p.canvasH, p.canvasW) },
            height = 56
        )
        caption("RASIO")
        panelBody.addView(hScroll { row ->
            for ((label, w, h) in CS_RATIOS) {
                val on = p.canvasW == w && p.canvasH == h
                row.addView(kit.pill("$label · ${w}×${h}", on) { resizeCanvasTo(w, h) }, LinearLayout.LayoutParams(-2, dp(34)).apply { rightMargin = dp(6) })
            }
        })
        caption("UKURAN KUSTOM")
        val sizeRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        var wVal = p.canvasW.toFloat()
        var hVal = p.canvasH.toFloat()
        sizeRow.addView(rawNumField("Lebar", p.canvasW) { wVal = it }, LinearLayout.LayoutParams(0, dp(44), 1f))
        sizeRow.addView(kit.text("×", 14f, c.muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(22), -2))
        sizeRow.addView(rawNumField("Tinggi", p.canvasH) { hVal = it }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        sizeRow.addView(kit.primaryButton("Terapkan") { hideKeyboard(); resizeCanvasTo(wVal.roundToInt(), hVal.roundToInt()) }, LinearLayout.LayoutParams(-2, dp(40)))
        panelBody.addView(sizeRow)
        note("Maksimal ${CsMath.MAX_CANVAS}px per sisi. Objek tetap pada posisi relatifnya; foto utama dipotong ke rasio baru.")
        primaryImage()?.takeIf { !it.fitCanvas }?.let { photoEl ->
            panelBody.addView(kit.secondaryButton("Sesuaikan kanvas ke foto terpilih") { setFitCanvas(photoEl, true) },
                LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
        }
        caption("TEMPLATE")
        panelBody.addView(hScroll { row ->
            for (t in CS_TEMPLATES) {
                val box = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = c.pressable(c.surface2, 12)
                    isClickable = true
                    contentDescription = "Template ${t.name} ${t.width}×${t.height}"
                    setOnClickListener {
                        a.themedDialog()
                            .setTitle("Pakai template ${t.name}?")
                            .setMessage("Isi kanvas diganti template ${t.width}×${t.height}. Bisa dikembalikan dengan Undo.")
                            .setNegativeButton("Batal", null)
                            .setPositiveButton("Pakai") { _, _ -> applyTemplate(t); canvasView.fit(); changed() }
                            .show()
                    }
                }
                val ratio = t.width.toFloat() / t.height
                val pw = if (ratio >= 1f) dp(42) else (dp(42) * ratio).toInt()
                val ph = if (ratio >= 1f) (dp(42) / ratio).toInt() else dp(42)
                box.addView(View(a).apply { background = c.shape(t.bg, 3, c.stroke) }, LinearLayout.LayoutParams(pw, ph))
                box.addView(kit.text(t.name, 11f, c.ink, true).apply { setPadding(0, dp(5), 0, 0) })
                box.addView(kit.text("${t.width}×${t.height}", 9.5f, c.muted).apply { setPadding(0, dp(2), 0, 0) })
                row.addView(box, LinearLayout.LayoutParams(dp(88), dp(92)).apply { rightMargin = dp(6) })
            }
        })
        caption("LATAR")
        val latar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isClickable = true
            background = c.pressable(Color.TRANSPARENT, 10)
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { openPanel("latarkanvas") }
        }
        latar.addView(kit.icon("format-color-fill", 18f, c.ink), LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(6) })
        latar.addView(kit.text("Latar kanvas · ${when (p.bg.mode) { "gradient" -> "gradien"; "transparent" -> "transparan"; else -> "warna" }}", 13f, c.ink), LinearLayout.LayoutParams(0, -2, 1f))
        latar.addView(View(a).apply { background = c.shape(if (p.bg.mode == "transparent") c.surface2 else p.bg.color or 0xFF000000.toInt(), 8, c.stroke) },
            LinearLayout.LayoutParams(dp(32), dp(24)).apply { rightMargin = dp(8) })
        panelBody.addView(latar, LinearLayout.LayoutParams(-1, dp(42)))
    }

    private fun rawNumField(label: String, value: Int, onChange: (Float) -> Unit): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = c.shape(c.surface2, 12)
            setPadding(dp(10), 0, dp(4), 0)
        }
        box.addView(kit.text(label, 11f, c.muted, true), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        box.addView(EditText(a).apply {
            setText(value.toString())
            textSize = 14f
            setTextColor(c.ink)
            background = null
            setSingleLine(true)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = label
            setPadding(0, 0, 0, 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, cn: Int, af: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, bf: Int, cn: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) { s?.toString()?.toFloatOrNull()?.let(onChange) }
            })
        }, LinearLayout.LayoutParams(0, -1, 1f))
        return box
    }

    // ---- Tampilan & panduan

    private fun renderViewPanel() {
        panelTitle.text = "Tampilan & panduan"
        tiles(
            kit.tile("fit-to-screen-outline", "Pas layar") { canvasView.fit() },
            kit.tile("magnify-scan", "100%") { canvasView.actualSize() },
            kit.tile("magnify-minus-outline", "Perkecil") { canvasView.zoomBy(1f / 1.25f) },
            kit.tile("magnify-plus-outline", "Perbesar") { canvasView.zoomBy(1.25f) },
            height = 56
        )
        caption("PANDUAN & GRID")
        panelBody.addView(kit.toggleRow("Panduan pintar + magnet (tengah, tepi, objek)", p.smartGuides) { v -> p.smartGuides = v }, LinearLayout.LayoutParams(-1, dp(40)))
        panelBody.addView(kit.toggleRow("Tampilkan titik pusat kanvas", p.showCenter) { v -> p.showCenter = v; canvasView.invalidate() }, LinearLayout.LayoutParams(-1, dp(40)))
        panelBody.addView(kit.toggleRow("Tampilkan grid", p.showGrid) { v -> p.showGrid = v; canvasView.invalidate() }, LinearLayout.LayoutParams(-1, dp(40)))
        panelBody.addView(kit.toggleRow("Snap ke grid", p.snap) { v -> p.snap = v }, LinearLayout.LayoutParams(-1, dp(40)))
        panelBody.addView(kit.segmented(listOf("8" to "Grid 8", "16" to "16", "32" to "32", "64" to "64"), p.gridStep.toString()) { k ->
            p.gridStep = k.toInt(); canvasView.invalidate(); renderPanel("tampilan")
        }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(4) })
        caption("PILIH")
        tiles(
            kit.tile("selection-multiple", "Pilih banyak", p.multiSelect) { toggleMultiSelect(); renderPanel("tampilan") },
            kit.tile("select-group", "Pilih semua") { selectAll() },
            height = 56
        )
        note("Panduan, grid, titik pusat, dan seleksi tidak ikut diekspor.")
    }

    // ---- Ekspor

    private fun exportDims(): Pair<Int, Int> = CsMath.exportSize(p.canvasW, p.canvasH, p.exportScale, p.exportCustom)

    private fun renderExportPanel() {
        val fmt = p.exportFormat
        val raster = fmt == "png" || fmt == "jpg" || fmt == "webp"
        val (ew, eh) = exportDims()
        panelTitle.text = "Ekspor · " + if (raster) "${ew}×${eh}" else "${p.canvasW}×${p.canvasH}"
        caption("FORMAT")
        panelBody.addView(kit.segmented(listOf("png" to "PNG", "jpg" to "JPG", "webp" to "WebP", "pdf" to "PDF", "svg" to "SVG"), fmt) { k ->
            p.exportFormat = k; renderPanel("ekspor")
        }, LinearLayout.LayoutParams(-1, dp(38)))
        if (raster) {
            caption("UKURAN")
            val sizeKey = if (p.exportCustom > 0) "kustom" else String.format(Locale.US, "%.1f", p.exportScale)
            panelBody.addView(kit.segmented(listOf("0.5" to "0.5×", "1.0" to "1× asli", "2.0" to "2×", "kustom" to "Kustom"), sizeKey) { k ->
                if (k == "kustom") { if (p.exportCustom <= 0) p.exportCustom = max(p.canvasW, p.canvasH) }
                else { p.exportCustom = 0; p.exportScale = k.toFloat() }
                renderPanel("ekspor")
            }, LinearLayout.LayoutParams(-1, dp(38)))
            if (p.exportCustom > 0) {
                val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                var v = p.exportCustom.toFloat()
                row.addView(rawNumField("Sisi terpanjang (px)", p.exportCustom) { v = it }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
                row.addView(kit.primaryButton("Terapkan") {
                    hideKeyboard(); p.exportCustom = v.roundToInt().coerceIn(64, CsMath.MAX_CANVAS); renderPanel("ekspor")
                }, LinearLayout.LayoutParams(-2, dp(40)))
                panelBody.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                // Ukuran cepat sisi terpanjang (preset Photo Editor 1080/2160 & Hapus Latar 512/1080/2048 lama).
                val quick = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                for (side in listOf(512, 1080, 2048, 2160, CsMath.MAX_CANVAS)) {
                    quick.addView(kit.pill("$side", p.exportCustom == side) { hideKeyboard(); p.exportCustom = side; renderPanel("ekspor") },
                        LinearLayout.LayoutParams(0, dp(32), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
                }
                panelBody.addView(quick, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            val wanted = if (p.exportCustom > 0) p.exportCustom.toFloat() else max(p.canvasW, p.canvasH) * p.exportScale
            if (wanted > CsMath.MAX_CANVAS) note("Dibatasi ${CsMath.MAX_CANVAS}px per sisi agar memori HP aman.")
            if (fmt != "png") panelBody.addView(kit.slider("Kualitas", 40, 100, p.exportQuality, 92, format = { "$it%" }) { q -> p.exportQuality = q })
        }
        if (fmt != "jpg" && fmt != "pdf") {
            if (p.bg.mode == "transparent") note("Latar kanvas transparan — transparansi ikut diekspor.")
            else panelBody.addView(kit.toggleRow("Latar transparan (lewati latar kanvas)", p.exportTransparent) { v -> p.exportTransparent = v; renderPanel("ekspor") },
                LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(4) })
            if ((fmt == "png" || fmt == "webp") && (p.bg.mode == "transparent" || p.exportTransparent)) {
                panelBody.addView(kit.toggleRow("Pangkas ke isi (buang margin transparan)", p.exportTrim) { v -> p.exportTrim = v },
                    LinearLayout.LayoutParams(-1, dp(40)))
            }
        } else if (p.bg.mode == "transparent") note("${fmt.uppercase(Locale.ROOT)} tidak mendukung transparansi — latar diisi putih.")
        val action = when (fmt) { "pdf" -> "Simpan PDF"; "svg" -> "Simpan SVG"; else -> "Simpan ke Galeri" }
        panelBody.addView(kit.primaryButton(action) { doExport() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
        val saved = p.lastSaved
        val last = p.lastExport
        if (saved != null || (last != null && last.exists())) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), 0, 0) }
            val label = if (saved != null) "${saved.label} · ${humanBytes(saved.bytes)}" else last?.let { "${it.name} · ${a.formatImageBytes(it.length())}" } ?: ""
            row.addView(kit.text("Terakhir: $label", 11.5f, c.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(kit.secondaryButton("Bagikan") {
                if (saved != null) a.shareImageUri(saved)
                else if (last != null) runCatching { a.shareFile(last) }.onFailure { a.toast("Gagal membagikan: ${it.message}") }
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { leftMargin = dp(6) })
            panelBody.addView(row)
        }
        caption("KIRIM KE")
        fun send(label: String, iconName: String, action: () -> Unit) {
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0)
                background = c.pressable(c.surface2, 14, c.stroke)
                isClickable = true
                contentDescription = label
                setOnClickListener {
                    if (p.elements.isEmpty()) a.toast("Desain masih kosong") else action()
                }
            }
            row.addView(kit.icon(iconName, 20f, c.ink), LinearLayout.LayoutParams(dp(30), dp(30)))
            row.addView(kit.text(label, 13f, c.ink).apply { setPadding(dp(8), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(kit.icon("chevron-right", 18f, c.muted), LinearLayout.LayoutParams(dp(24), dp(24)))
            panelBody.addView(row, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(6) })
        }
        send("Ke Animasi", "motion-play-outline") { autosaveNow(); StudioHandoff.toAnimation(a, p.doc(), p.projectName) }
        send("Ke Presentasi (slide baru)", "presentation") { autosaveNow(); StudioHandoff.toPresentation(a, p.doc()) }
        send("Ke Video Editor (klip gambar PNG)", "movie-open-plus-outline") {
            StudioHandoff.designToVideoEditor(a, p.doc()) { on -> if (alive) busy(on, "Merender PNG untuk Video Editor…") }
        }
        note("PNG/JPG/WebP tersimpan di Galeri (Pictures/GITLS). PDF & SVG di folder Dokumen aplikasi, lalu bisa dibagikan. " +
            "Foto dirender ulang dari sumber resolusi penuh. Panduan & seleksi tidak ikut diekspor.")
    }

    private fun doExport() {
        val fmt = p.exportFormat
        val doc = p.doc()
        val (outW, outH) = exportDims()
        val transparent = (p.bg.mode == "transparent" || p.exportTransparent) && fmt != "jpg" && fmt != "pdf"
        val trim = p.exportTrim && transparent && (fmt == "png" || fmt == "webp")
        val quality = if (fmt == "png") 100 else p.exportQuality
        val name = CsStore.safeName(p.projectName)
        val previews = CsPipeline.previewSnapshot(p.elements.toList())
        val ctx = a.applicationContext
        busy(true, "Mengekspor ${fmt.uppercase(Locale.ROOT)}…")
        CsPipeline.runOnWorker {
            val result: Result<Any> = runCatching {
                when (fmt) {
                    "pdf" -> {
                        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir
                        if (!dir.exists()) dir.mkdirs()
                        val file = File(dir, "${name}_${System.currentTimeMillis()}.pdf")
                        CsPipeline.writePdf(ctx, doc, file)
                        file
                    }
                    "svg" -> {
                        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir
                        if (!dir.exists()) dir.mkdirs()
                        val file = File(dir, "${name}_${System.currentTimeMillis()}.svg")
                        file.writeText(buildCsSvg(doc.w, doc.h, doc.bg, doc.elements, transparent) { el -> CsPipeline.svgRaster(ctx, el, previews) })
                        file
                    }
                    else -> {
                        var bmp = try {
                            CsPipeline.renderDoc(ctx, doc, outW, outH, transparent, flatten = fmt == "jpg")
                        } catch (_: OutOfMemoryError) {
                            System.gc()
                            // Cadangan: setengah ukuran bila memori tidak cukup.
                            CsPipeline.renderDoc(ctx, doc, max(1, outW / 2), max(1, outH / 2), transparent, flatten = fmt == "jpg")
                        }
                        if (trim) {
                            val trimmed = CsPipeline.trimTransparent(bmp)
                            if (trimmed !== bmp) bmp.recycle()
                            bmp = trimmed
                        }
                        // Bitmap ekspor milik tugas ini saja — dilepas setelah ditulis ke Galeri.
                        try {
                            a.saveImageToGallery(bmp, name, fmt, quality) to "${bmp.width}×${bmp.height}"
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
            }
            CsPipeline.post {
                busy(false)
                result.onSuccess { r ->
                    when (r) {
                        is File -> { p.lastExport = r; p.lastSaved = null; a.toast("Tersimpan: ${r.name}") }
                        is Pair<*, *> -> {
                            val saved = r.first as? SavedImage
                            if (saved != null) { p.lastSaved = saved; p.lastExport = null; a.toast("Tersimpan ${r.second}: ${saved.label}") }
                        }
                    }
                    if (alive && panelKey == "ekspor") renderPanel("ekspor")
                }.onFailure { e ->
                    val msg = if (e is OutOfMemoryError) "memori tidak cukup — pilih ukuran lebih kecil" else (e.message ?: e.javaClass.simpleName)
                    a.toast("Ekspor gagal: $msg")
                }
            }
        }
    }

    // ================================================================== proyek

    private fun showMenu() {
        val items = arrayOf("Proyek baru…", "Edit foto baru…", "Hapus latar foto baru…", "Simpan proyek", "Simpan sebagai…", "Buka proyek…", "Kanvas & panduan", "Pas layar", "Bantuan")
        a.themedDialog()
            .setTitle(p.projectName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> newProject()
                    1 -> openPhotoDocument(false)
                    2 -> openPhotoDocument(true)
                    3 -> saveProject(p.projectName, overwrite = true)
                    4 -> askName("Simpan sebagai", p.projectName + " salinan") { n -> saveProject(n, overwrite = false) }
                    5 -> openProjectDialog()
                    6 -> openPanel("kanvas")
                    7 -> canvasView.fit()
                    else -> showHelp()
                }
            }
            .show()
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(a).apply { setText(initial); setSelectAllOnFocus(true); setSingleLine(true) }
        val wrap = FrameLayout(a).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, FrameLayout.LayoutParams(-1, -2)) }
        val d = a.themedDialog().setTitle(title).setView(wrap)
            .setNegativeButton("Batal", null)
            .setPositiveButton("OK") { _, _ -> onOk(input.text?.toString()?.trim().orEmpty().ifBlank { "Desain" }) }
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        d.show()
        input.requestFocus()
    }

    private fun newProject() {
        val names = CS_TEMPLATES.map { "${it.name} · ${it.width}×${it.height}" }.toTypedArray()
        a.themedDialog()
            .setTitle("Proyek baru")
            .setItems(names) { _, which ->
                stashCurrentProject()
                p.clearForNew()
                applyTemplate(CS_TEMPLATES[which], record = false)
                p.projectName = "Desain ${CsStore.listProjects(a).size + 1}"
                p.projectFile = null
                canvasView.fit()
                changed()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun saveProject(name: String, overwrite: Boolean) {
        val dir = CsStore.projectsDir(a)
        // Menimpa hanya berkas proyek INI; proyek baru tanpa berkas tidak boleh menimpa proyek lain yang kebetulan senama.
        val overwriteOwn = overwrite && p.projectFile != null
        var file = if (overwriteOwn) File(p.projectFile!!) else File(dir, CsStore.safeName(name) + ".json")
        if (!overwriteOwn) {
            var i = 2
            while (file.exists()) { file = File(dir, CsStore.safeName(name) + " $i.json"); i++ }
        }
        p.projectName = name
        p.projectFile = file.absolutePath
        val json = CsStore.toJsonText(p)
        val target = file
        Thread {
            val r = runCatching { CsStore.writeAtomic(target, json) }
            a.runOnUiThread {
                r.onSuccess { a.toast("Proyek disimpan: ${target.nameWithoutExtension}"); if (alive) updateChrome() }
                    .onFailure { a.toast("Gagal menyimpan: ${it.message}") }
            }
        }.start()
        autosaveNow()
    }

    private fun openProjectDialog() {
        val files = CsStore.listProjects(a)
        if (files.isEmpty()) { a.toast("Belum ada proyek tersimpan. Pakai menu Simpan proyek."); return }
        val fmt = java.text.SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())
        val labels = files.map { "${it.nameWithoutExtension}\n${fmt.format(java.util.Date(it.lastModified()))} · ${humanBytes(it.length())}" }.toTypedArray()
        a.themedDialog()
            .setTitle("Buka proyek")
            .setItems(labels) { _, which -> openProject(files[which]) }
            .setNeutralButton("Hapus…") { _, _ -> deleteProjectDialog(files) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun openProject(f: File) {
        val text = runCatching { f.readText().also { JSONObject(it) } }.getOrNull() ?: run { a.toast("Proyek rusak atau tidak bisa dibaca"); return }
        if (p.projectFile != f.absolutePath) stashCurrentProject()
        runCatching {
            CsStore.load(a, text, p)
            p.projectFile = f.absolutePath
        }.onFailure { a.toast("Gagal membuka: ${it.message}"); return }
        dismissStartCard()
        fillMissingSourceSizes()
        canvasView.fit()
        changed()
        a.toast("Dibuka: ${p.projectName}")
    }

    private fun deleteProjectDialog(files: List<File>) {
        val labels = files.map { it.nameWithoutExtension }.toTypedArray()
        val checked = BooleanArray(files.size)
        a.themedDialog()
            .setTitle("Hapus proyek")
            .setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                var n = 0
                files.forEachIndexed { i, f -> if (checked[i] && f.delete()) n++ }
                val cur = p.projectFile
                if (cur != null && !File(cur).exists()) p.projectFile = null
                a.toast("$n proyek dihapus")
                cleanupFilesLater()
            }
            .show()
    }

    private fun showHelp() {
        a.themedDialog()
            .setTitle(CsToolIds.NAME)
            .setMessage(
                "• Satu studio untuk desain, edit foto, dan hapus latar. Dock bawah berisi kategori; pilih layer dan dock berganti menjadi alat layer itu (Selesai = kembali).\n" +
                    "• Foto: Atur, Filter, Kurva & HSL, Detail, Potong (rasio, putar, balik, luruskan, perspektif), Heal, dan Hapus latar (AI subjek/orang, warna latar, kuas, tepi, latar pengganti, bayangan, pangkas). Semua non-destruktif.\n" +
                    "• Edit foto baru / Hapus latar foto baru (menu ⋮ atau Foto): kanvas mengikuti ukuran foto; potong & putar ikut mengubah kanvas.\n" +
                    "• Geser objek dengan satu jari; garis panduan muncul di tengah/tepi kanvas & objek lain dan objek menempel. Pusat menaruh objek tepat di tengah.\n" +
                    "• Sudut seleksi: ubah ukuran. Bulatan di atas objek: putar (menempel tiap 45°). Tekan lama objek untuk memilih banyak → Grup, rata, distribusi.\n" +
                    "• Dua jari: zoom & geser kanvas. Ketuk dua kali: teks → edit, foto → Atur, area kosong → pas layar.\n" +
                    "• Tahan ikon Bandingkan di atas untuk melihat foto sebelum diedit.\n" +
                    "• Proyek tersimpan otomatis. Menu ⋮: proyek baru, simpan, buka. Ekspor PNG/JPG/WebP ke Galeri, PDF/SVG ke Dokumen."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ================================================================== kanvas

    private inner class CsCanvasView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dash = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        private val accent = Color.rgb(255, 45, 120)
        private val checkerMatrix = Matrix()
        private val checkerPaint: Paint by lazy { buildChecker() }
        private var fitScale = 1f

        private val MODE_IDLE = 0
        private val MODE_MOVE = 1
        private val MODE_RESIZE = 2
        private val MODE_ROTATE = 3
        private val MODE_PINCH = 4
        private val MODE_PAN = 5
        private var mode = MODE_IDLE

        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var moved = false
        private var recorded = false
        private var resizeSx = 1
        private var resizeSy = 1
        private var startBox = DsBox(0f, 0f, 1f, 1f)
        private var startTextSize = 0f
        private val startPos = HashMap<String, Pair<Float, Float>>()
        private var startUnion: DsRect? = null
        private var guides: List<DsGuide> = emptyList()
        private var snappedX = false
        private var snappedY = false
        private var flashH = false
        private var flashV = false
        private var lastTapTime = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f
        private var tapHitId: String? = null

        private var startDist = 1f
        private var startScale = 1f
        private var startMidX = 0f
        private var startMidY = 0f
        private var startPanX = 0f
        private var startPanY = 0f

        private val clearFlash = Runnable { flashH = false; flashV = false; invalidate() }
        private val longPress = Runnable {
            if (mode == MODE_MOVE && !moved) {
                val hit = tapHitId?.let { id -> p.elements.firstOrNull { it.id == id } } ?: return@Runnable
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (!p.multiSelect) {
                    p.multiSelect = true
                    a.toast("Pilih banyak aktif — ketuk objek lain untuk menambah")
                }
                if (hit.id !in p.selection) toggleSelect(hit)
                mode = MODE_IDLE
                changed()
            }
        }

        init {
            guidePaint.style = Paint.Style.STROKE
            labelPaint.textSize = dp(12).toFloat()
            labelPaint.typeface = Typeface.DEFAULT_BOLD
        }

        private fun buildChecker(): Paint {
            val cell = dp(8).coerceAtLeast(4)
            val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val pp = Paint()
            pp.color = Color.rgb(252, 252, 252)
            cv.drawRect(0f, 0f, cell * 2f, cell * 2f, pp)
            pp.color = Color.rgb(210, 210, 214)
            cv.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), pp)
            cv.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, pp)
            return Paint().apply { shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        }

        fun displayScale(): Float = fitScale * p.userScale
        private fun scale(): Float = displayScale()
        private fun originX(): Float = (width - p.canvasW * scale()) / 2f + p.panX
        private fun originY(): Float = (height - p.canvasH * scale()) / 2f + p.panY
        private fun toCanvasX(x: Float) = (x - originX()) / scale()
        private fun toCanvasY(y: Float) = (y - originY()) / scale()

        private fun computeFit() {
            val pad = dp(20) * 2f
            if (width <= 0 || height <= 0) return
            fitScale = min((width - pad).coerceAtLeast(1f) / p.canvasW, (height - pad).coerceAtLeast(1f) / p.canvasH)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            computeFit()
            updateChrome()
        }

        fun fit() {
            p.userScale = 1f; p.panX = 0f; p.panY = 0f
            invalidate(); updateChrome()
        }

        /** 1 piksel kanvas = 1 piksel layar. */
        fun actualSize() {
            computeFit()
            p.userScale = (1f / fitScale).coerceIn(0.1f, 16f)
            p.panX = 0f; p.panY = 0f
            invalidate(); updateChrome()
        }

        fun zoomBy(f: Float) {
            computeFit()
            val cxCanvas = toCanvasX(width / 2f)
            val cyCanvas = toCanvasY(height / 2f)
            p.userScale = (p.userScale * f).coerceIn(0.1f, 16f)
            val s = scale()
            p.panX = width / 2f - cxCanvas * s - (width - p.canvasW * s) / 2f
            p.panY = height / 2f - cyCanvas * s - (height - p.canvasH * s) / 2f
            invalidate(); updateChrome()
        }

        /** Tampilkan garis tengah sebentar setelah tombol Pusat ditekan sebagai konfirmasi visual. */
        fun flashCenter(h: Boolean, v: Boolean) {
            flashH = h; flashV = v
            removeCallbacks(clearFlash)
            postDelayed(clearFlash, 900L)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            computeFit()
            canvas.drawColor(workspaceBg)
            val s = scale()
            canvas.save()
            canvas.translate(originX(), originY())
            canvas.scale(s, s)
            val cw = p.canvasW.toFloat()
            val ch = p.canvasH.toFloat()
            // Bayangan + batas kanvas tipis agar tepi jelas tanpa menutupi objek.
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(if (c.dark) 120 else 50, 0, 0, 0)
            canvas.drawRect(4f / s, 6f / s, cw + 4f / s, ch + 6f / s, paint)
            if (p.bg.mode == "transparent") {
                checkerMatrix.setScale(1f / s, 1f / s)
                checkerPaint.shader?.setLocalMatrix(checkerMatrix)
                canvas.drawRect(0f, 0f, cw, ch, checkerPaint)
            } else CsRender.drawBackground(canvas, p.bg, cw, ch, paint)
            if (p.showGrid) {
                val light = p.bg.mode == "transparent" || Color.red(p.bg.color) > 140
                paint.reset()
                paint.color = if (light) Color.argb(28, 0, 0, 0) else Color.argb(36, 255, 255, 255)
                paint.strokeWidth = 1f / s
                val step = p.gridStep.toFloat().coerceAtLeast(4f)
                // Grid terlalu rapat pada zoom kecil dilewati agar tidak membebani GPU.
                if (step * s >= 4f) {
                    var gx = 0f
                    while (gx <= cw) { canvas.drawLine(gx, 0f, gx, ch, paint); gx += step }
                    var gy = 0f
                    while (gy <= ch) { canvas.drawLine(0f, gy, cw, gy, paint); gy += step }
                }
            }
            canvas.save()
            canvas.clipRect(0f, 0f, cw, ch)
            for (el in p.elements) if (el.visible) drawLayer(canvas, el)
            canvas.restore()
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f / s
            paint.color = if (c.dark) Color.argb(90, 255, 255, 255) else Color.argb(70, 0, 0, 0)
            canvas.drawRect(0f, 0f, cw, ch, paint)

            drawCenterMark(canvas, s, cw, ch)
            drawGuides(canvas, s, cw, ch)
            if (!comparing) drawSelection(canvas, s)
            canvas.restore()
            drawGestureLabel(canvas)
        }

        private fun drawLayer(canvas: Canvas, el: CsElement) {
            if (parallaxLive && el.depth > 0f) {
                // Kedalaman besar = ikut bergeser lebih jauh (efek 2.5D).
                val amp = CsProject.canvasW * 0.03f * el.depth
                canvas.save()
                canvas.translate(tiltNx * amp, tiltNy * amp)
                drawLayerContent(canvas, el)
                canvas.restore()
            } else drawLayerContent(canvas, el)
        }

        private fun drawLayerContent(canvas: Canvas, el: CsElement) {
            if (el.type != CsType.IMAGE) {
                CsRender.draw(a, canvas, el, paint, null, null)
                return
            }
            val r = CsPipeline.result("el:${el.id}")
            if (comparing && r != null) { drawOriginal(canvas, el, r); return }
            val bmp = r?.image
            val shadow = if (el.shadow && r != null && r.transparent && bmp != null) shadowFor(el, bmp) else null
            CsRender.draw(a, canvas, el, paint, bmp, shadow)
        }

        /** Bandingkan: sumber asli (tanpa heal/potong latar/warna) dengan geometri yang sama. */
        private fun drawOriginal(canvas: Canvas, el: CsElement, r: CsRendered) {
            val src = r.src
            val (m, ow, oh) = PeGeometry.matrix(src.width, src.height, el.edit.geom, true)
            canvas.save()
            if (el.rotation != 0f) canvas.rotate(el.rotation, el.x + el.w / 2f, el.y + el.h / 2f)
            canvas.clipRect(el.x, el.y, el.x + el.w, el.y + el.h)
            canvas.translate(el.x, el.y)
            canvas.scale(el.w / ow.coerceAtLeast(1), el.h / oh.coerceAtLeast(1))
            canvas.drawBitmap(src, m, bmpPaint)
            canvas.restore()
        }

        private fun shadowFor(el: CsElement, bmp: Bitmap): CsShadow? {
            val key = el.shadowBlur.roundToInt() * 100_000 + el.w.roundToInt().coerceIn(0, 99_999)
            val cached = shadowCache[el.id]
            if (cached != null && cached.first === bmp && cached.second == key) return cached.third
            val made = runCatching { CsRender.makeShadow(bmp, el.shadowBlur, el.w, 256) }.getOrNull() ?: return null
            shadowCache[el.id] = Triple(bmp, key, made)
            return made
        }

        private fun drawCenterMark(canvas: Canvas, s: Float, cw: Float, ch: Float) {
            val cx = cw / 2f
            val cy = ch / 2f
            val dragging = mode == MODE_MOVE && moved
            if (!p.showCenter && !dragging && !flashH && !flashV) return
            // Penanda pusat kanvas: salib kecil + lingkaran, kontras ganda agar terlihat di latar apa pun.
            val arm = dp(9) / s
            val r = dp(3) / s
            for (pass in 0..1) {
                guidePaint.pathEffect = null
                guidePaint.strokeWidth = (if (pass == 0) 3f else 1.2f) / s
                guidePaint.color = if (pass == 0) Color.argb(110, 0, 0, 0) else Color.argb(220, 255, 255, 255)
                canvas.drawLine(cx - arm, cy, cx + arm, cy, guidePaint)
                canvas.drawLine(cx, cy - arm, cx, cy + arm, guidePaint)
                canvas.drawCircle(cx, cy, r, guidePaint)
            }
            if (flashH || flashV) {
                guidePaint.color = accent
                guidePaint.strokeWidth = 1.5f / s
                if (flashH) canvas.drawLine(cx, 0f, cx, ch, guidePaint)
                if (flashV) canvas.drawLine(0f, cy, cw, cy, guidePaint)
            }
        }

        private fun drawGuides(canvas: Canvas, s: Float, cw: Float, ch: Float) {
            if (guides.isEmpty() || mode != MODE_MOVE) return
            for (g in guides) {
                guidePaint.color = accent
                guidePaint.strokeWidth = (if (g.kind == DsGuideKind.CANVAS_CENTER) 1.6f else 1.2f) / s
                guidePaint.pathEffect = if (g.kind == DsGuideKind.OBJECT) DashPathEffect(floatArrayOf(8f / s, 6f / s), 0f) else null
                if (g.vertical) canvas.drawLine(g.pos, -dp(12) / s, g.pos, ch + dp(12) / s, guidePaint)
                else canvas.drawLine(-dp(12) / s, g.pos, cw + dp(12) / s, g.pos, guidePaint)
            }
            guidePaint.pathEffect = null
            if (guides.any { it.kind == DsGuideKind.CANVAS_CENTER }) {
                paint.reset(); paint.isAntiAlias = true
                paint.style = Paint.Style.FILL
                paint.color = accent
                canvas.drawCircle(cw / 2f, ch / 2f, dp(4) / s, paint)
            }
        }

        private fun outline(canvas: Canvas, el: CsElement, s: Float, strong: Boolean) {
            canvas.save()
            if (el.rotation != 0f) canvas.rotate(el.rotation, el.x + el.w / 2f, el.y + el.h / 2f)
            selPaint.style = Paint.Style.STROKE
            selPaint.pathEffect = null
            selPaint.strokeWidth = (if (strong) 3.5f else 3f) / s
            selPaint.color = Color.argb(200, 0, 0, 0)
            canvas.drawRect(el.x, el.y, el.x + el.w, el.y + el.h, selPaint)
            selPaint.strokeWidth = 1.6f / s
            selPaint.color = if (el.locked) Color.rgb(170, 170, 176) else Color.WHITE
            selPaint.pathEffect = if (strong) null else DashPathEffect(floatArrayOf(8f / s, 6f / s), 0f)
            canvas.drawRect(el.x, el.y, el.x + el.w, el.y + el.h, selPaint)
            selPaint.pathEffect = null
            canvas.restore()
        }

        private fun handlePoints(): List<Pair<Int, Int>> = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)

        private fun rotateHandleLocal(el: CsElement, s: Float): Pair<Float, Float> = 0f to (-el.h / 2f - dp(30) / s)

        private fun drawSelection(canvas: Canvas, s: Float) {
            val sel = selectedList().filter { it.visible }
            if (sel.isEmpty()) return
            val single = sel.size == 1
            for (el in sel) outline(canvas, el, s, single)
            if (!single) {
                val u = sel.map { boundsOf(it) }.reduce { acc, r -> acc.union(r) }
                selPaint.style = Paint.Style.STROKE
                selPaint.strokeWidth = 1.2f / s
                selPaint.color = accent
                selPaint.pathEffect = dash
                canvas.drawRect(u.left, u.top, u.right, u.bottom, selPaint)
                selPaint.pathEffect = null
                return
            }
            val el = sel[0]
            // Foto utama tidak punya pegangan: ukurannya selalu kanvas.
            if (el.locked || el.fitCanvas) return
            val box = boxOf(el)
            val hr = dp(7) / s
            for ((sx, sy) in handlePoints()) {
                val (wx, wy) = DesignLayout.toWorld(box, sx * el.w / 2f, sy * el.h / 2f)
                selPaint.style = Paint.Style.FILL
                selPaint.color = Color.WHITE
                canvas.drawCircle(wx, wy, hr, selPaint)
                selPaint.style = Paint.Style.STROKE
                selPaint.strokeWidth = 1.6f / s
                selPaint.color = Color.argb(220, 20, 20, 22)
                canvas.drawCircle(wx, wy, hr, selPaint)
            }
            // Pegangan rotasi di atas objek, dihubungkan garis tipis.
            val (rlx, rly) = rotateHandleLocal(el, s)
            val top = DesignLayout.toWorld(box, 0f, -el.h / 2f)
            val rh = DesignLayout.toWorld(box, rlx, rly)
            selPaint.style = Paint.Style.STROKE
            selPaint.strokeWidth = 1.4f / s
            selPaint.color = Color.WHITE
            canvas.drawLine(top.first, top.second, rh.first, rh.second, selPaint)
            selPaint.style = Paint.Style.FILL
            selPaint.color = if (mode == MODE_ROTATE) accent else Color.WHITE
            canvas.drawCircle(rh.first, rh.second, dp(8) / s, selPaint)
            selPaint.style = Paint.Style.STROKE
            selPaint.color = Color.argb(220, 20, 20, 22)
            canvas.drawCircle(rh.first, rh.second, dp(8) / s, selPaint)
            canvas.drawCircle(rh.first, rh.second, dp(3) / s, selPaint)
        }

        /** Label info selama gestur (posisi, ukuran, sudut) di bagian atas area kerja. */
        private fun drawGestureLabel(canvas: Canvas) {
            if (!moved) return
            val el = primary() ?: return
            val label = when (mode) {
                MODE_MOVE -> {
                    val u = movable().map { boundsOf(it) }.reduceOrNull { acc, r -> acc.union(r) } ?: return
                    "X ${u.left.roundToInt()}  Y ${u.top.roundToInt()}" + when {
                        guides.any { it.vertical && it.kind == DsGuideKind.CANVAS_CENTER } && guides.any { !it.vertical && it.kind == DsGuideKind.CANVAS_CENTER } -> "  · Pusat"
                        guides.any { it.kind == DsGuideKind.CANVAS_CENTER } -> "  · Tengah"
                        else -> ""
                    }
                }
                MODE_RESIZE -> "${el.w.roundToInt()} × ${el.h.roundToInt()}"
                MODE_ROTATE -> "${el.rotation.roundToInt()}°"
                else -> return
            }
            val tw = labelPaint.measureText(label)
            val padH = dp(10).toFloat()
            val bh = dp(26).toFloat()
            val left = (width - tw) / 2f - padH
            val top = dp(10).toFloat()
            paint.reset(); paint.isAntiAlias = true
            paint.color = Color.argb(225, 18, 18, 20)
            canvas.drawRoundRect(RectF(left, top, left + tw + padH * 2, top + bh), bh / 2f, bh / 2f, paint)
            labelPaint.color = Color.WHITE
            canvas.drawText(label, left + padH, top + bh / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint)
        }

        private fun hitElement(cx: Float, cy: Float): CsElement? {
            val tol = dp(6) / scale()
            return p.elements.asReversed().firstOrNull { el ->
                if (!el.visible) return@firstOrNull false
                val extra = if (el.type == CsType.LINE) max(tol, dp(14) / scale() - el.h / 2f) else tol
                DesignLayout.hit(boxOf(el), cx, cy, extra)
            }
        }

        private fun beginMove() {
            startPos.clear()
            val els = movable()
            els.forEach { startPos[it.id] = it.x to it.y }
            startUnion = els.map { boundsOf(it) }.reduceOrNull { acc, r -> acc.union(r) }
            guides = emptyList()
            snappedX = false
            snappedY = false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> onDown(event)
                MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                    removeCallbacks(longPress)
                    mode = MODE_PINCH
                    guides = emptyList()
                    startDist = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1)).coerceAtLeast(1f)
                    startScale = p.userScale
                    startMidX = (event.getX(0) + event.getX(1)) / 2f
                    startMidY = (event.getY(0) + event.getY(1)) / 2f
                    startPanX = p.panX
                    startPanY = p.panY
                }
                MotionEvent.ACTION_MOVE -> onMove(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onUp(event)
                MotionEvent.ACTION_POINTER_UP -> {
                    // Setelah pinch, jangan langsung menggeser objek dengan jari yang tersisa.
                    if (mode == MODE_PINCH) mode = MODE_IDLE
                }
            }
            return true
        }

        private fun onDown(event: MotionEvent) {
            val cx = toCanvasX(event.x)
            val cy = toCanvasY(event.y)
            downX = event.x; downY = event.y
            lastX = event.x; lastY = event.y
            moved = false
            recorded = false
            tapHitId = null
            val sel = selectedList()
            val single = sel.singleOrNull()?.takeIf { !it.locked && it.visible && !it.fitCanvas }
            if (single != null) {
                val s = scale()
                val box = boxOf(single)
                val touch = dp(22) / s
                val (rlx, rly) = rotateHandleLocal(single, s)
                val rh = DesignLayout.toWorld(box, rlx, rly)
                if (hypot(cx - rh.first, cy - rh.second) <= touch) {
                    mode = MODE_ROTATE
                    startBox = box
                    invalidate()
                    return
                }
                for ((sx, sy) in handlePoints()) {
                    val (wx, wy) = DesignLayout.toWorld(box, sx * single.w / 2f, sy * single.h / 2f)
                    if (hypot(cx - wx, cy - wy) <= touch) {
                        mode = MODE_RESIZE
                        resizeSx = sx; resizeSy = sy
                        startBox = box
                        startTextSize = single.textSize
                        invalidate()
                        return
                    }
                }
            }
            val hit = hitElement(cx, cy)
            if (hit != null) {
                tapHitId = hit.id
                val changedSel = when {
                    p.multiSelect -> { toggleSelect(hit); true }
                    hit.id !in p.selection -> { selectOnly(hit); true }
                    else -> false
                }
                if (changedSel) onSelectionChanged()
                // Foto utama tidak digeser: satu jari di atasnya menggeser tampilan kanvas.
                mode = if (movable().isNotEmpty() && hit.id in p.selection && !hit.fitCanvas) MODE_MOVE else MODE_PAN
                if (mode == MODE_MOVE) {
                    beginMove()
                    if (!p.multiSelect) postDelayed(longPress, 520L)
                }
            } else {
                if (!p.multiSelect && p.selection.isNotEmpty()) { p.selection.clear(); onSelectionChanged() }
                mode = MODE_PAN
            }
            invalidate()
        }

        private fun onMove(event: MotionEvent) {
            val dxScreen = event.x - downX
            val dyScreen = event.y - downY
            if (!moved && hypot(dxScreen, dyScreen) > dp(5)) {
                moved = true
                removeCallbacks(longPress)
            }
            when (mode) {
                MODE_MOVE -> if (moved) {
                    val union = startUnion ?: return
                    if (!recorded) { checkpoint(); recorded = true }
                    val s = scale()
                    var dx = dxScreen / s
                    var dy = dyScreen / s
                    val moving = DsRect(union.left + dx, union.top + dy, union.right + dx, union.bottom + dy)
                    val ids = startPos.keys
                    val others = p.elements.filter { it.visible && it.id !in ids }.map { boundsOf(it) }
                    val snap = DesignLayout.snapMove(moving, others, p.canvasW.toFloat(), p.canvasH.toFloat(),
                        threshold = dp(8) / s, smart = p.smartGuides, gridStep = if (p.snap) p.gridStep.toFloat() else 0f)
                    dx += snap.dx
                    dy += snap.dy
                    // Getar ringan saat objek baru menempel ke panduan.
                    if ((snap.snappedX && !snappedX) || (snap.snappedY && !snappedY)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    snappedX = snap.snappedX
                    snappedY = snap.snappedY
                    guides = snap.guides
                    for (el in p.elements) {
                        val st = startPos[el.id] ?: continue
                        el.x = st.first + dx
                        el.y = st.second + dy
                    }
                    invalidate()
                }
                MODE_RESIZE -> if (moved) {
                    val el = primary() ?: return
                    if (!recorded) { checkpoint(); recorded = true }
                    val uniform = el.type == CsType.TEXT || el.type == CsType.IMAGE || el.type == CsType.STICKER
                    val nb = DesignLayout.resizeFromCorner(startBox, resizeSx, resizeSy, toCanvasX(event.x), toCanvasY(event.y), uniform, 8f)
                    el.x = nb.x; el.y = nb.y; el.w = nb.w; el.h = nb.h
                    if (el.type == CsType.TEXT && startBox.w > 0f) el.textSize = (startTextSize * nb.w / startBox.w).coerceIn(4f, 2000f)
                    invalidate()
                }
                MODE_ROTATE -> {
                    val el = primary() ?: return
                    if (!recorded) { checkpoint(); recorded = true }
                    moved = true
                    val old = el.rotation
                    el.rotation = DesignLayout.rotationTo(startBox, toCanvasX(event.x), toCanvasY(event.y), 4f)
                    if (old != el.rotation && el.rotation % 45f == 0f) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    invalidate()
                }
                MODE_PINCH -> if (event.pointerCount >= 2) {
                    val dist = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    // Zoom berpusat di titik antara dua jari.
                    val s0 = fitScale * startScale
                    val cx0 = (startMidX - ((width - p.canvasW * s0) / 2f + startPanX)) / s0
                    val cy0 = (startMidY - ((height - p.canvasH * s0) / 2f + startPanY)) / s0
                    p.userScale = (startScale * dist / startDist).coerceIn(0.1f, 16f)
                    val s1 = scale()
                    p.panX = midX - cx0 * s1 - (width - p.canvasW * s1) / 2f
                    p.panY = midY - cy0 * s1 - (height - p.canvasH * s1) / 2f
                    invalidate()
                    updateChrome()
                }
                MODE_PAN -> if (moved) {
                    p.panX += event.x - lastX
                    p.panY += event.y - lastY
                    invalidate()
                }
            }
            lastX = event.x
            lastY = event.y
        }

        private fun onUp(event: MotionEvent) {
            removeCallbacks(longPress)
            val wasMode = mode
            mode = MODE_IDLE
            guides = emptyList()
            if (event.actionMasked == MotionEvent.ACTION_UP && !moved && (wasMode == MODE_MOVE || wasMode == MODE_PAN || wasMode == MODE_IDLE)) {
                // Ketuk dua kali: teks → edit, foto → Atur, area kosong → pas layar.
                val now = event.eventTime
                val dbl = now - lastTapTime < 300L && hypot(event.x - lastTapX, event.y - lastTapY) < dp(24)
                lastTapTime = now; lastTapX = event.x; lastTapY = event.y
                if (dbl) {
                    lastTapTime = 0L
                    val hit = tapHitId?.let { id -> p.elements.firstOrNull { it.id == id } }
                    when {
                        hit == null -> fit()
                        hit.locked -> Unit
                        hit.type == CsType.TEXT -> editText(hit)
                        hit.type == CsType.IMAGE -> { selectOnly(hit); onSelectionChanged(); openPanel("p_atur") }
                    }
                }
            }
            if (moved && (wasMode == MODE_MOVE || wasMode == MODE_RESIZE || wasMode == MODE_ROTATE)) {
                if (wasMode == MODE_RESIZE) primary()?.let { if (it.type == CsType.TEXT) CsRender.fitText(it, measurePaint) }
                changed()
            } else if (moved && wasMode == MODE_PAN) {
                updateChrome()
            }
            moved = false
            invalidate()
        }
    }
}
