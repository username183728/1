package com.example.aidetest

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Hasil tampilan fokus yang diterapkan ke layer sebagai satu langkah undo. */
internal class CsFocusResult(
    val geom: PeGeom? = null,
    val heals: List<PeHeal>? = null,
    val mask: ByteArray? = null,
    val maskW: Int = 0,
    val maskH: Int = 0
)

/**
 * Tampilan fokus layar penuh untuk operasi presisi pada satu layer foto
 * (ZoomImageView: pinch & geser dua jari, ketuk dua kali = pas layar):
 *  - crop : potong bebas / rasio, putar 90°, balik, luruskan, perspektif V/H
 *  - heal : kuas penyembuh (tekstur sekitar + koreksi warna membran, sama dengan Photo Editor lama)
 *  - mask : kuas Hapus / Kembalikan latar dengan kekerasan; foto asli tipis sebagai panduan
 * Semua perubahan lokal sampai "Selesai" (satu langkah undo); Batal / Back membuangnya.
 * Mask & heal tetap di ruang sumber (non-destruktif); tampilan memakai matriks geometri layer.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class CsFocusEditor(
    private val kit: ImgKit,
    private val host: FrameLayout,
    private val el: CsElement,
    private val mode: String,
    private val workSide: Int,
    private val onApply: (CsFocusResult) -> Unit,
    private val onClosed: () -> Unit
) {
    private val a = kit.a
    private val c = kit.c
    private val p = CsProject
    private val overlay = FrameLayout(a)
    private val view = ZoomImageView(a, kit.workspace)
    private val controls = LinearLayout(a)
    private val subtitle = TextView(a)
    private val progress = ProgressBar(a)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ghostPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 80 }
    private val uiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val channel = "focus:${el.id}"

    private var geom: PeGeom = el.edit.geom
    private val heals = ArrayList<PeHeal>(el.edit.heals)
    private var frame: Bitmap? = null
    private var srcW = 0
    private var srcH = 0
    private var dirty = false
    private var alive = true
    private var waitJob: CsJob? = null
    private var healBusy = false
    private var maskBusy = false

    // crop
    private var cropDrag = -1
    private var cropStart = floatArrayOf(0f, 0f, 1f, 1f)
    private var dragX = 0f
    private var dragY = 0f
    // heal
    private val stroke = ArrayList<Float>()
    // mask
    private var work: CsMaskWork? = null
    private var cutBmp: Bitmap? = null
    private val maskUndo = ArrayList<ByteArray>()
    private var stroking = false
    private var lastX = 0f
    private var lastY = 0f
    private var refineGen = 0
    private var maskMatrix = Matrix()
    private var maskInverse = Matrix()

    private val back: () -> Boolean = { if (alive) { requestClose(); true } else false }

    // Didefinisikan SEBELUM init (urutan inisialisasi Kotlin): dipasang ke kanvas di build().
    private val gesture = object : ZoomImageView.Gesture {
        override fun onDown(x: Float, y: Float): Boolean = when (mode) {
            "crop" -> cropDown(x, y)
            "heal" -> healDown(x, y)
            else -> maskDown(x, y)
        }

        override fun onMove(x: Float, y: Float) {
            when (mode) {
                "crop" -> cropMove(x, y)
                "heal" -> healMove(x, y)
                else -> maskMove(x, y)
            }
        }

        override fun onUp(x: Float, y: Float) {
            when (mode) {
                "crop" -> cropDrag = -1
                "heal" -> healUp()
                else -> maskUp()
            }
        }

        override fun onCancel() {
            when (mode) {
                "crop" -> cropDrag = -1
                "heal" -> { stroke.clear(); view.invalidate() }
                else -> maskUp()
            }
        }
    }

    private fun dp(v: Int) = kit.dp(v)

    init {
        build()
        load()
    }

    private fun title(): String = when (mode) {
        "crop" -> "Potong & luruskan"
        "heal" -> "Heal"
        else -> "Kuas hapus latar"
    }

    // ------------------------------------------------------------------ tata letak

    private fun build() {
        overlay.setBackgroundColor(kit.workspace)
        overlay.isClickable = true
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        overlay.addView(col, FrameLayout.LayoutParams(-1, -1))
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(8), 0)
            background = c.shape(c.surface, 0)
        }
        head.addView(kit.icon("close", 22f, c.ink, "Batal") { requestClose() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), 0, dp(4), 0) }
        titles.addView(kit.text(title(), 15f, c.ink, true).apply { maxLines = 1 })
        subtitle.apply { textSize = 11f; setTextColor(c.muted); includeFontPadding = false; maxLines = 1; setPadding(0, dp(3), 0, 0) }
        titles.addView(subtitle)
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        progress.isIndeterminate = true
        progress.visibility = View.GONE
        head.addView(progress, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(8) })
        head.addView(kit.icon("fit-to-screen-outline", 21f, c.ink, "Pas layar") { view.resetView() }, LinearLayout.LayoutParams(dp(40), dp(44)))
        head.addView(kit.primaryButton("Selesai") { apply() }.apply { contentDescription = "Terapkan dan tutup" },
            LinearLayout.LayoutParams(-2, dp(36)).apply { leftMargin = dp(4) })
        col.addView(head, LinearLayout.LayoutParams(-1, dp(52)))
        view.padPx = dp(18).toFloat()
        view.drawer = { cv -> drawContent(cv) }
        view.overlayDrawer = { cv, s -> drawOverlay(cv, s) }
        view.gesture = gesture
        view.checker = mode == "mask"
        col.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(View(a).apply { setBackgroundColor(c.stroke) }, LinearLayout.LayoutParams(-1, 1))
        controls.orientation = LinearLayout.VERTICAL
        controls.setPadding(dp(12), dp(8), dp(12), dp(10))
        controls.background = c.shape(c.surface, 0)
        col.addView(controls, LinearLayout.LayoutParams(-1, -2))
        host.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        OverlayBack.push(back)
        renderControls()
        updateSubtitle()
        if (a.animationsEnabled()) {
            overlay.alpha = 0f
            overlay.animate().alpha(1f).setDuration(150L).start()
        }
    }

    private fun showProgress() {
        progress.visibility = if (waitJob != null || healBusy || maskBusy) View.VISIBLE else View.GONE
    }

    private fun updateSubtitle() {
        subtitle.text = when (mode) {
            "crop" -> {
                val (ow, oh) = CsMath.outputSize(el.srcW, el.srcH, geom.rot90, geom.cl, geom.ct, geom.cr, geom.cb)
                "$ow×$oh px · rasio ${ratioLabel(geom.ratio)}"
            }
            "heal" -> "${heals.size} sapuan · ukuran kuas ${p.healSize}"
            else -> "Kuas ${when (p.brushMode) { "kembalikan" -> "Kembalikan"; "geser" -> "Geser"; else -> "Hapus" }} · ${p.brushSize}px · dua jari = zoom"
        }
    }

    private fun ratioLabel(key: String) = when (key) { "bebas" -> "bebas"; "asli" -> "asli"; else -> key }

    // ------------------------------------------------------------------ muat & render

    private fun load() {
        if (mode == "mask") {
            maskBusy = true
            showProgress()
            CsPipeline.prepareMaskWork(el, workSide) { w ->
                maskBusy = false
                showProgress()
                if (!alive) return@prepareMaskWork
                if (w == null) { a.toast("Foto tidak bisa disiapkan untuk kuas"); close(); return@prepareMaskWork }
                work = w
                buildCut(w)
                val (ow, oh) = updateMaskMatrix(w)
                view.setContentSize(ow, oh, reset = true)
                renderControls()
            }
        } else requestFrame()
    }

    private fun requestFrame() {
        val job = CsPipeline.jobFor(el, workSide, channel, cropMode = mode == "crop", draft = false,
            edit = el.edit.copy(geom = geom, heals = heals.toList())) ?: return
        waitJob = job
        showProgress()
        CsPipeline.request(job) { r ->
            if (!alive) return@request
            if (r != null && r.job == waitJob) { waitJob = null; showProgress() }
            if (r == null) { waitJob = null; showProgress(); return@request }
            val old = frame
            frame = r.image
            srcW = r.src.width
            srcH = r.src.height
            view.setContentSize(r.image.width, r.image.height, reset = old == null || old.width != r.image.width || old.height != r.image.height)
            updateSubtitle()
        }
    }

    private fun drawContent(cv: Canvas) {
        if (mode == "mask") {
            val w = work ?: return
            if (p.showOriginal && p.brushMode == "kembalikan") cv.drawBitmap(w.healed, maskMatrix, ghostPaint)
            cutBmp?.let { cv.drawBitmap(it, maskMatrix, bmpPaint) }
            return
        }
        val f = frame ?: return
        cv.drawBitmap(f, 0f, 0f, bmpPaint)
    }

    private fun drawOverlay(cv: Canvas, scale: Float) {
        if (mode == "heal") {
            if (stroke.size < 2) return
            uiPaint.style = Paint.Style.FILL
            uiPaint.color = Color.argb(110, 255, 255, 255)
            val r = dp(p.healSize) / scale
            var i = 0
            while (i < stroke.size) { cv.drawCircle(stroke[i], stroke[i + 1], r, uiPaint); i += 2 }
            return
        }
        if (mode != "crop") return
        val f = frame ?: return
        val w = f.width.toFloat()
        val h = f.height.toFloat()
        val l = geom.cl * w
        val t = geom.ct * h
        val r = geom.cr * w
        val b = geom.cb * h
        uiPaint.style = Paint.Style.FILL
        uiPaint.color = Color.argb(150, 0, 0, 0)
        cv.drawRect(0f, 0f, w, t, uiPaint)
        cv.drawRect(0f, b, w, h, uiPaint)
        cv.drawRect(0f, t, l, b, uiPaint)
        cv.drawRect(r, t, w, b, uiPaint)
        uiPaint.style = Paint.Style.STROKE
        uiPaint.color = Color.argb(120, 255, 255, 255)
        uiPaint.strokeWidth = 1f / scale
        for (k in 1..2) {
            cv.drawLine(l + (r - l) * k / 3f, t, l + (r - l) * k / 3f, b, uiPaint)
            cv.drawLine(l, t + (b - t) * k / 3f, r, t + (b - t) * k / 3f, uiPaint)
        }
        uiPaint.color = Color.WHITE
        uiPaint.strokeWidth = 2f / scale
        cv.drawRect(RectF(l, t, r, b), uiPaint)
        uiPaint.strokeWidth = 5f / scale
        val hl = dp(18) / scale
        val corners = listOf(floatArrayOf(l, t, 1f, 1f), floatArrayOf(r, t, -1f, 1f), floatArrayOf(r, b, -1f, -1f), floatArrayOf(l, b, 1f, -1f))
        for (q in corners) {
            cv.drawLine(q[0], q[1], q[0] + hl * q[2], q[1], uiPaint)
            cv.drawLine(q[0], q[1], q[0], q[1] + hl * q[3], uiPaint)
        }
    }

    // ------------------------------------------------------------------ kontrol

    private fun renderControls() {
        controls.removeAllViews()
        view.cursorRadiusPx = when (mode) {
            "heal" -> dp(p.healSize).toFloat()
            "mask" -> if (p.brushMode == "geser") 0f else dp(p.brushSize).toFloat()
            else -> 0f
        }
        when (mode) {
            "crop" -> cropControls()
            "heal" -> healControls()
            else -> maskControls()
        }
        updateSubtitle()
    }

    private fun row(): LinearLayout = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun cropControls() {
        val ratios = listOf("bebas" to "Bebas", "asli" to "Asli", "1:1" to "1:1", "4:5" to "4:5", "3:4" to "3:4", "2:3" to "2:3", "9:16" to "9:16", "16:9" to "16:9")
        for (r in 0 until 2) {
            val line = row()
            for (k in 0 until 4) {
                val (key, label) = ratios[r * 4 + k]
                line.addView(kit.pill(label, geom.ratio == key) { applyRatio(key) }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            controls.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        }
        val tools = row()
        fun tool(icon: String, desc: String, on: () -> Unit) = tools.addView(kit.icon(icon, 21f, c.ink, desc) { on() }.apply { background = c.pressable(c.surface2, 12) },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        tool("rotate-left", "Putar kiri 90°") { rotate(-1) }
        tool("rotate-right", "Putar kanan 90°") { rotate(1) }
        tool("flip-horizontal", "Balik horizontal") { flip(true) }
        tool("flip-vertical", "Balik vertikal") { flip(false) }
        tool("restore", "Reset potong") { geom = PeGeom(); dirty = true; renderControls(); requestFrame() }
        controls.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        val line = row()
        line.addView(kit.segmented(listOf("luruskan" to "Lurus", "pv" to "Persp V", "ph" to "Persp H"), p.cropSub) { k -> p.cropSub = k; renderControls() },
            LinearLayout.LayoutParams(dp(176), dp(34)))
        val slider = when (p.cropSub) {
            "pv" -> kit.slider("", -100, 100, (geom.perspV * 100).roundToInt(), 0, format = { if (it > 0) "+$it" else "$it" }) { v -> geom = geom.copy(perspV = v / 100f); dirty = true; requestFrame() }
            "ph" -> kit.slider("", -100, 100, (geom.perspH * 100).roundToInt(), 0, format = { if (it > 0) "+$it" else "$it" }) { v -> geom = geom.copy(perspH = v / 100f); dirty = true; requestFrame() }
            else -> kit.slider("", -45, 45, geom.straighten.roundToInt(), 0, format = { "$it°" }) { v -> geom = geom.copy(straighten = v.toFloat()); dirty = true; requestFrame() }
        }
        (slider.getChildAt(0) as? TextView)?.visibility = View.GONE
        line.addView(slider, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        controls.addView(line)
    }

    private fun healControls() {
        controls.addView(kit.text("Sapukan kuas di atas noda, jerawat, atau objek kecil. Tekstur sekitar dipakai untuk menutupinya secara mulus. Dua jari = zoom.", 11.5f, c.muted).apply {
            setLineSpacing(0f, 1.2f); setPadding(dp(4), dp(2), dp(4), dp(8)); includeFontPadding = true
        })
        controls.addView(kit.slider("Ukuran kuas", 6, 80, p.healSize, 22) { v -> p.healSize = v; view.cursorRadiusPx = dp(v).toFloat(); updateSubtitle() })
        val line = row()
        line.addView(kit.secondaryButton("Urungkan sapuan") {
            if (heals.isEmpty()) return@secondaryButton
            heals.removeAt(heals.size - 1); dirty = true; renderControls(); requestFrame()
        }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) })
        line.addView(kit.secondaryButton("Hapus semua (${heals.size})") {
            if (heals.isEmpty()) return@secondaryButton
            heals.clear(); dirty = true; renderControls(); requestFrame()
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        controls.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    private fun maskControls() {
        controls.addView(kit.segmented(listOf("geser" to "Geser", "hapus" to "Hapus", "kembalikan" to "Kembalikan"), p.brushMode) { k ->
            p.brushMode = k; renderControls(); view.invalidate()
        }, LinearLayout.LayoutParams(-1, dp(36)).apply { bottomMargin = dp(4) })
        controls.addView(kit.slider("Ukuran", 4, 120, p.brushSize, 28) { v -> p.brushSize = v; if (p.brushMode != "geser") view.cursorRadiusPx = dp(v).toFloat(); updateSubtitle() })
        controls.addView(kit.slider("Kekerasan", 0, 100, p.brushHardness, 60, format = { "$it%" }) { v -> p.brushHardness = v })
        controls.addView(kit.toggleRow("Tampilkan foto asli tipis saat mengembalikan", p.showOriginal) { v -> p.showOriginal = v; view.invalidate() },
            LinearLayout.LayoutParams(-1, dp(36)))
        val line = row()
        line.addView(kit.secondaryButton("Urungkan sapuan") { undoMask() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) })
        line.addView(kit.secondaryButton("Balik mask") { invertMask() }, LinearLayout.LayoutParams(0, dp(38), 1f))
        controls.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    // ------------------------------------------------------------------ crop

    private fun ratioFor(key: String): Float? = PeGeometry.ratioValue(key)?.let { rv ->
        if (rv > 0f) rv else {
            val (ow, oh) = CsMath.orientedSize(el.srcW.coerceAtLeast(1), el.srcH.coerceAtLeast(1), geom.rot90)
            ow.toFloat() / oh.coerceAtLeast(1)
        }
    }

    private fun cropDown(x: Float, y: Float): Boolean {
        val f = frame ?: return false
        val w = f.width.toFloat()
        val h = f.height.toFloat()
        val l = geom.cl * w
        val t = geom.ct * h
        val r = geom.cr * w
        val b = geom.cb * h
        val tol = dp(26) / view.scale()
        val corners = listOf(l to t, r to t, r to b, l to b)
        var hit = corners.indexOfFirst { (cx, cy) -> hypot(x - cx, y - cy) <= tol }
        if (hit < 0 && ratioFor(geom.ratio) == null) {
            hit = when {
                abs(y - t) <= tol && x in l..r -> 4
                abs(x - r) <= tol && y in t..b -> 5
                abs(y - b) <= tol && x in l..r -> 6
                abs(x - l) <= tol && y in t..b -> 7
                else -> -1
            }
        }
        if (hit < 0 && x in l..r && y in t..b) hit = 8
        if (hit < 0) return false
        cropDrag = hit
        cropStart = floatArrayOf(geom.cl, geom.ct, geom.cr, geom.cb)
        dragX = x / w
        dragY = y / h
        return true
    }

    private fun cropMove(x: Float, y: Float) {
        val f = frame ?: return
        if (cropDrag < 0) return
        val w = f.width.toFloat()
        val h = f.height.toFloat()
        val r = CsMath.cropDrag(cropDrag, cropStart, x / w - dragX, y / h - dragY, w, h, ratioFor(geom.ratio))
        geom = geom.copy(cl = r[0], ct = r[1], cr = r[2], cb = r[3])
        dirty = true
        view.invalidate()
        updateSubtitle()
    }

    private fun applyRatio(key: String) {
        val f = frame ?: return
        val rv = ratioFor(key)
        geom = if (rv == null) geom.copy(ratio = key) else {
            val cr = CsMath.centeredCrop(f.width, f.height, rv)
            geom.copy(ratio = key, cl = cr[0], ct = cr[1], cr = cr[2], cb = cr[3])
        }
        dirty = true
        renderControls()
        view.invalidate()
    }

    private fun rotate(dir: Int) {
        // Bila dicerminkan sekali, arah putar sumber dibalik agar di layar sesuai tombol.
        val d = if (geom.flipH != geom.flipV) -dir else dir
        geom = geom.copy(rot90 = ((geom.rot90 + d) % 4 + 4) % 4, cl = 0f, ct = 0f, cr = 1f, cb = 1f, ratio = "bebas")
        dirty = true
        renderControls()
        requestFrame()
    }

    private fun flip(horizontal: Boolean) {
        val g = geom
        // Kotak crop ikut dicerminkan agar area terpilih tetap sama.
        geom = if (horizontal) g.copy(flipH = !g.flipH, cl = 1f - g.cr, cr = 1f - g.cl, straighten = -g.straighten, perspH = -g.perspH)
        else g.copy(flipV = !g.flipV, ct = 1f - g.cb, cb = 1f - g.ct, straighten = -g.straighten, perspV = -g.perspV)
        dirty = true
        renderControls()
        requestFrame()
    }

    // ------------------------------------------------------------------ heal

    private fun healDown(x: Float, y: Float): Boolean {
        val f = frame ?: return false
        if (healBusy || x < 0 || y < 0 || x > f.width || y > f.height) return false
        stroke.clear()
        stroke.add(x)
        stroke.add(y)
        view.cursorRadiusPx = dp(p.healSize).toFloat()
        view.invalidate()
        return true
    }

    private fun healMove(x: Float, y: Float) {
        if (stroke.isEmpty()) return
        val lx = stroke[stroke.size - 2]
        val ly = stroke[stroke.size - 1]
        if (hypot(x - lx, y - ly) < dp(p.healSize) / view.scale() * 0.35f) return
        stroke.add(x)
        stroke.add(y)
        view.invalidate()
    }

    private fun healUp() {
        if (stroke.isEmpty() || srcW <= 0 || srcH <= 0) { stroke.clear(); return }
        val pts = stroke.toFloatArray()
        stroke.clear()
        val radiusOut = dp(p.healSize) / view.scale()
        // Titik layar (ruang keluaran) → ruang sumber kerja lewat invers matriks geometri.
        val (m, _, _) = PeGeometry.matrix(srcW, srcH, geom, true)
        val inv = Matrix()
        if (!m.invert(inv)) return
        inv.mapPoints(pts)
        val unit = floatArrayOf(0f, 0f, 1f, 0f)
        inv.mapPoints(unit)
        val radiusSrc = max(2f, radiusOut * hypot(unit[2] - unit[0], unit[3] - unit[1]))
        healBusy = true
        showProgress()
        CsPipeline.findHeal(el, el.edit.copy(geom = geom, heals = heals.toList()), workSide, pts, radiusSrc) { op ->
            healBusy = false
            showProgress()
            if (!alive) return@findHeal
            view.invalidate()
            if (op == null) { a.toast("Heal gagal"); return@findHeal }
            heals.add(op)
            dirty = true
            renderControls()
            requestFrame()
        }
    }

    // ------------------------------------------------------------------ mask

    private fun buildCut(w: CsMaskWork) {
        val out = IntArray(w.w * w.h)
        composeCut(w.px, w.deco, w.refined, out, 0, w.w * w.h)
        val bmp = cutBmp?.takeIf { it.width == w.w && it.height == w.h && it.isMutable } ?: Bitmap.createBitmap(w.w, w.h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w.w, 0, 0, w.w, w.h)
        cutBmp = bmp
    }

    private fun updateMaskMatrix(w: CsMaskWork): Pair<Int, Int> {
        val (m, ow, oh) = PeGeometry.matrix(w.w, w.h, geom, true)
        maskMatrix = m
        maskInverse = Matrix().also { m.invert(it) }
        return ow to oh
    }

    private fun toSrc(x: Float, y: Float): FloatArray {
        val pt = floatArrayOf(x, y)
        maskInverse.mapPoints(pt)
        return pt
    }

    private fun srcRadius(): Float {
        val unit = floatArrayOf(0f, 0f, 1f, 0f)
        maskInverse.mapPoints(unit)
        return max(1f, dp(p.brushSize) / view.scale() * hypot(unit[2] - unit[0], unit[3] - unit[1]))
    }

    private fun maskDown(x: Float, y: Float): Boolean {
        val w = work ?: return false
        if (p.brushMode == "geser") return false
        maskUndo.add(w.raw.copyOf())
        while (maskUndo.size > 8) maskUndo.removeAt(0)
        stroking = true
        dirty = true
        refineGen++ // batalkan penghalusan yang sedang berjalan agar tidak menimpa sapuan
        view.cursorRadiusPx = dp(p.brushSize).toFloat()
        val s = toSrc(x, y)
        lastX = s[0]
        lastY = s[1]
        dab(w, s[0], s[1], srcRadius())
        return true
    }

    private fun maskMove(x: Float, y: Float) {
        val w = work ?: return
        if (!stroking) return
        val s = toSrc(x, y)
        val r = srcRadius()
        val n = CsMath.brushSteps(hypot(s[0] - lastX, s[1] - lastY), r)
        for (i in 1..n) {
            val t = i.toFloat() / n
            dab(w, lastX + (s[0] - lastX) * t, lastY + (s[1] - lastY) * t, r)
        }
        lastX = s[0]
        lastY = s[1]
    }

    private fun maskUp() {
        if (!stroking) return
        stroking = false
        scheduleRefine()
    }

    /** Satu dab kuas: mask mentah + mask tampilan diubah, piksel potongan diperbarui hanya di area kotor. */
    private fun dab(w: CsMaskWork, x: Float, y: Float, radius: Float) {
        val cut = cutBmp ?: return
        val hard = p.brushHardness / 100f
        val add = p.brushMode == "kembalikan"
        MaskOps.brush(w.raw, w.w, w.h, x, y, radius, hard, add)
        val r = MaskOps.brush(w.refined, w.w, w.h, x, y, radius, hard, add)
        val l = r[0]
        val t = r[1]
        val rr = r[2]
        val b = r[3]
        if (rr <= l || b <= t) return
        val rw = rr - l
        val buf = IntArray(rw)
        val deco = w.deco
        for (yy in t until b) {
            val off = yy * w.w + l
            for (i in 0 until rw) {
                val idx = off + i
                val m = w.refined[idx].toInt() and 0xFF
                val col = (deco ?: w.px)[idx]
                val srcA = (w.px[idx] ushr 24) and 0xFF
                buf[i] = ((srcA * m / 255) shl 24) or (col and 0xFFFFFF)
            }
            cut.setPixels(buf, 0, rw, l, yy, rw, 1)
        }
        view.invalidate()
    }

    /** Haluskan ulang tepi setelah sapuan (thread kerja) lalu susun ulang potongan. */
    private fun scheduleRefine() {
        val w = work ?: return
        val gen = ++refineGen
        maskBusy = true
        showProgress()
        CsPipeline.refineWork(w, w.raw.copyOf(), el.cut) { refined, deco ->
            if (!alive || gen != refineGen) return@refineWork
            maskBusy = false
            showProgress()
            w.refined = refined
            w.deco = deco
            buildCut(w)
            view.invalidate()
        }
    }

    private fun undoMask() {
        val w = work ?: return
        val prev = maskUndo.removeLastOrNull() ?: run { a.toast("Tidak ada sapuan untuk diurungkan"); return }
        System.arraycopy(prev, 0, w.raw, 0, w.raw.size)
        dirty = true
        scheduleRefine()
    }

    private fun invertMask() {
        val w = work ?: return
        maskUndo.add(w.raw.copyOf())
        for (i in w.raw.indices) w.raw[i] = (255 - (w.raw[i].toInt() and 0xFF)).toByte()
        dirty = true
        scheduleRefine()
    }

    // ------------------------------------------------------------------ selesai / batal

    private fun apply() {
        if (!dirty) { close(); return }
        val result = when (mode) {
            "crop" -> CsFocusResult(geom = geom)
            "heal" -> CsFocusResult(heals = heals.toList())
            else -> work?.let { CsFocusResult(mask = it.raw.copyOf(), maskW = it.w, maskH = it.h) }
        }
        close()
        if (result != null) onApply(result)
    }

    private fun requestClose() {
        if (!dirty) { close(); return }
        a.themedDialog()
            .setTitle("Terapkan perubahan?")
            .setMessage("Perubahan di ${title().lowercase()} belum diterapkan ke layer.")
            .setPositiveButton("Terapkan") { _, _ -> apply() }
            .setNegativeButton("Buang") { _, _ -> close() }
            .setNeutralButton("Lanjut edit", null)
            .setOnDismissListener { if (alive) OverlayBack.push(back) }
            .show()
    }

    fun close() {
        if (!alive) return
        alive = false
        OverlayBack.remove(back)
        CsPipeline.forget(channel)
        host.removeView(overlay)
        onClosed()
    }
}
