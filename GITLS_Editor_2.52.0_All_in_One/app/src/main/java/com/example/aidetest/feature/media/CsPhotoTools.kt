package com.example.aidetest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Layanan studio yang dipakai panel foto & tampilan fokus (diimplementasikan CreativeStudioUi). */
internal interface CsHost {
    val kit: ImgKit
    fun body(): LinearLayout
    fun setPanelTitle(t: String)
    fun caption(value: String)
    fun note(value: String)
    fun tiles(vararg t: View, height: Int = 62)
    fun find(id: String): CsElement?
    fun checkpoint()
    fun changed(rerenderPanel: Boolean = true)
    fun renderImage(el: CsElement, draft: Boolean)
    fun rerenderPanel()
    fun busy(on: Boolean, msg: String = "")
    fun workSide(): Int
    fun openFocus(el: CsElement, mode: String)
    fun applyGeom(el: CsElement, g: PeGeom)
    fun commitMask(el: CsElement, data: ByteArray, w: Int, h: Int, method: String, cutOn: Boolean)
    fun replacementMode(el: CsElement): String
    fun setReplacement(el: CsElement, mode: String)
    fun backgroundLayer(el: CsElement): CsElement?
    fun canvasBackground(): CsBackground
    fun setCanvasBackground(bg: CsBackground)
    fun shadowControls(el: CsElement)
    fun liveSlider(label: String, min: Int, max: Int, value: Int, format: (Int) -> String, apply: (Int) -> Unit): View
}

/**
 * Panel layer foto Studio Kreatif — semua alat Photo Editor & Background Remover lama:
 *  - Atur: exposure, kontras, highlight, shadow · suhu, tint, saturasi, vibrance
 *  - Filter: 13 preset dengan thumbnail + intensitas
 *  - Kurva & HSL: kurva RGB/R/G/B dengan histogram, HSL 8 rentang warna
 *  - Detail: ketajaman, kurangi noise, clarity · vignette, grain · blur, blur latar (AI)
 *  - Hapus latar: AI subjek/orang, warna latar, kuas, balik, tepi (halus/geser/feather/dekontaminasi),
 *    latar pengganti (transparan/warna/gradien/gambar/blur), bayangan subjek, pangkas ke subjek
 * Potong/putar/luruskan/perspektif, Heal, dan Kuas mask dibuka di tampilan fokus (CsFocusEditor).
 */
internal class CsPhotoTools(private val h: CsHost) {
    private val p = CsProject
    private var curveView: CurveView? = null
    private var thumbs: List<Bitmap> = emptyList()
    private var thumbsKey: Any? = null
    private var thumbGen = 0
    private var aiBusy = false

    private val kit: ImgKit get() = h.kit
    private val a: MainActivity get() = h.kit.a
    private val c: EditorChrome get() = h.kit.c

    private fun dp(v: Int) = kit.dp(v)

    fun title(key: String): String = when (key) {
        "p_atur" -> "Atur cahaya & warna"
        "p_filter" -> "Filter"
        "p_kurva" -> "Kurva & HSL"
        "p_detail" -> "Detail & efek"
        else -> "Hapus latar"
    }

    fun render(key: String, el: CsElement) {
        curveView = null
        h.setPanelTitle(title(key))
        when (key) {
            "p_atur" -> atur(el)
            "p_filter" -> filter(el)
            "p_kurva" -> kurva(el)
            "p_detail" -> detail(el)
            else -> latar(el)
        }
    }

    /** Hasil render baru tersedia: histogram kurva ikut diperbarui. */
    fun onRendered() {
        curveView?.invalidate()
    }

    // ------------------------------------------------------------------ util

    private fun pct(v: Int) = if (v > 0) "+$v" else "$v"

    private fun seg(options: List<Pair<String, String>>, active: String, extra: View?, onPick: (String) -> Unit) {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(kit.segmented(options, active, onPick), LinearLayout.LayoutParams(0, dp(36), 1f))
        if (extra != null) row.addView(extra, LinearLayout.LayoutParams(-2, dp(34)).apply { leftMargin = dp(6) })
        h.body().addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(6) })
    }

    private fun resetPill(el: CsElement, reset: (PeAdjust) -> PeAdjust): View = kit.pill("Reset", false) {
        h.checkpoint()
        el.edit = el.edit.copy(adjust = reset(el.edit.adjust))
        h.changed()
    }

    /** Slider satu field PeAdjust: pratinjau setengah resolusi saat digeser, render penuh saat dilepas. */
    private fun adjSlider(el: CsElement, label: String, value: Float, min: Int = -100, scale: Float = 100f, fmt: (Int) -> String = { pct(it) }, set: (PeAdjust, Float) -> PeAdjust): View =
        kit.slider(label, min, 100, (value * scale).roundToInt().coerceIn(min, 100), 0, format = fmt,
            onStart = { h.checkpoint() },
            onStop = { h.changed(rerenderPanel = false) }) { v ->
            el.edit = el.edit.copy(adjust = set(el.edit.adjust, v / scale))
            h.renderImage(el, true)
        }

    // ------------------------------------------------------------------ Atur

    private fun atur(el: CsElement) {
        val adj = el.edit.adjust
        seg(listOf("cahaya" to "Cahaya", "warna" to "Warna"), p.photoSub, resetPill(el) { x ->
            if (p.photoSub == "cahaya") x.copy(exposure = 0f, contrast = 0f, highlights = 0f, shadows = 0f)
            else x.copy(temperature = 0f, tint = 0f, saturation = 0f, vibrance = 0f)
        }) { k -> p.photoSub = k; h.rerenderPanel() }
        val b = h.body()
        if (p.photoSub == "cahaya") {
            b.addView(kit.slider("Exposure", -100, 100, (adj.exposure * 50).roundToInt().coerceIn(-100, 100), 0,
                format = { String.format(Locale.US, "%+.1f EV", it / 50f) },
                onStart = { h.checkpoint() }, onStop = { h.changed(rerenderPanel = false) }) { v ->
                el.edit = el.edit.copy(adjust = el.edit.adjust.copy(exposure = v / 50f))
                h.renderImage(el, true)
            })
            b.addView(adjSlider(el, "Kontras", adj.contrast) { x, v -> x.copy(contrast = v) })
            b.addView(adjSlider(el, "Highlight", adj.highlights) { x, v -> x.copy(highlights = v) })
            b.addView(adjSlider(el, "Shadow", adj.shadows) { x, v -> x.copy(shadows = v) })
        } else {
            b.addView(adjSlider(el, "Suhu", adj.temperature) { x, v -> x.copy(temperature = v) })
            b.addView(adjSlider(el, "Tint", adj.tint) { x, v -> x.copy(tint = v) })
            b.addView(adjSlider(el, "Saturasi", adj.saturation) { x, v -> x.copy(saturation = v) })
            b.addView(adjSlider(el, "Vibrance", adj.vibrance) { x, v -> x.copy(vibrance = v) })
        }
        h.note("Ketuk angka di kanan slider untuk kembali ke 0. Tahan tombol Bandingkan (atas) untuk melihat foto asli.")
    }

    // ------------------------------------------------------------------ Filter

    private fun filter(el: CsElement) {
        val adj = el.edit.adjust
        val presets = PePresets.list
        val cols = 7
        val imgs = ArrayList<ImageView>()
        for (r in 0 until 2) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until cols) {
                val preset = presets.getOrNull(r * cols + col)
                if (preset == null) { row.addView(View(a), LinearLayout.LayoutParams(0, dp(76), 1f)); continue }
                val on = adj.preset == preset.first
                val cell = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    contentDescription = "Filter ${preset.second}"
                    setOnClickListener {
                        h.checkpoint()
                        val cur = el.edit.adjust
                        el.edit = el.edit.copy(adjust = cur.copy(preset = preset.first, presetAmount = if (cur.preset == preset.first) cur.presetAmount else 1f))
                        h.changed()
                    }
                }
                val img = ImageView(a).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = c.shape(c.surface2, 10, if (on) c.ink else c.stroke, if (on) 2 else 1)
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    clipToOutline = true
                }
                imgs.add(img)
                cell.addView(img, LinearLayout.LayoutParams(-1, dp(54)))
                cell.addView(kit.text(preset.second, 9.5f, if (on) c.ink else c.muted, on).apply {
                    gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(3), 0, 0)
                }, LinearLayout.LayoutParams(-1, -2))
                row.addView(cell, LinearLayout.LayoutParams(0, dp(76), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            h.body().addView(row)
        }
        if (adj.preset != "asli") {
            h.body().addView(kit.slider("Intensitas", 0, 100, (adj.presetAmount * 100).roundToInt(), 100, format = { "$it%" },
                onStart = { h.checkpoint() }, onStop = { h.changed(rerenderPanel = false) }) { v ->
                el.edit = el.edit.copy(adjust = el.edit.adjust.copy(presetAmount = v / 100f))
                h.renderImage(el, true)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        } else h.note("Pilih filter, lalu atur intensitasnya. Filter digabung dengan pengaturan Atur & Kurva.")
        loadThumbs(el, imgs)
    }

    private fun loadThumbs(el: CsElement, tiles: List<ImageView>) {
        val src = CsPipeline.result("el:${el.id}")?.src ?: return
        val base = el.edit.adjust.copy(preset = "asli", presetAmount = 1f, bgBlur = 0f, sharpen = 0f, denoise = 0f, clarity = 0f, blur = 0f, grain = 0f, vignette = 0f)
        val key = Pair(src, base)
        if (key == thumbsKey && thumbs.size == tiles.size) {
            tiles.forEachIndexed { i, iv -> iv.setImageBitmap(thumbs[i]) }
            return
        }
        val gen = ++thumbGen
        CsPipeline.runOnWorker {
            val r = runCatching {
                val small = fitLongSide(src, 120)
                val w = small.width
                val hh = small.height
                val px0 = IntArray(w * hh)
                small.getPixels(px0, 0, w, 0, 0, w, hh)
                // Salinan kecil milik tugas ini; sumber pratinjau (dipakai UI) tidak disentuh.
                if (small !== src) small.recycle()
                PeColor.apply(px0, base)
                PePresets.list.map { (k, _) ->
                    val px = px0.copyOf()
                    PeColor.apply(px, PeAdjust(preset = k))
                    Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, hh) }
                }
            }
            CsPipeline.post {
                if (gen != thumbGen) return@post
                r.onSuccess { list ->
                    thumbs = list
                    thumbsKey = key
                    tiles.forEachIndexed { i, iv -> list.getOrNull(i)?.let { iv.setImageBitmap(it) } }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Kurva & HSL

    private fun kurva(el: CsElement) {
        seg(listOf("kurva" to "Kurva", "hsl" to "HSL"), p.curveSub, resetPill(el) { x ->
            if (p.curveSub == "kurva") x.copy(curveM = CURVE_IDENTITY, curveR = CURVE_IDENTITY, curveG = CURVE_IDENTITY, curveB = CURVE_IDENTITY)
            else x.copy(hslHue = List(8) { 0f }, hslSat = List(8) { 0f }, hslLum = List(8) { 0f })
        }) { k -> p.curveSub = k; h.rerenderPanel() }
        val b = h.body()
        if (p.curveSub == "kurva") {
            val chips = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, label) in listOf("RGB", "Merah", "Hijau", "Biru").withIndex()) {
                chips.addView(kit.pill(label, p.curveCh == i) { p.curveCh = i; h.rerenderPanel() },
                    LinearLayout.LayoutParams(0, dp(32), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            b.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            val cv = CurveView(a, el)
            curveView = cv
            b.addView(cv, LinearLayout.LayoutParams(-1, dp(200)))
            h.note("Ketuk untuk menambah titik, seret untuk mengubah. Seret titik jauh keluar kotak untuk menghapusnya.")
        } else {
            val adj = el.edit.adjust
            val dots = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            for (k in 0 until 8) {
                val on = k == p.hslRange
                dots.addView(View(a).apply {
                    background = c.shape(PeColor.HUE_SWATCH[k], 14, if (on) c.ink else null, if (on) 3 else 1)
                    if (on) { scaleX = 1.15f; scaleY = 1.15f }
                    isClickable = true
                    contentDescription = PeColor.HUE_LABELS[k]
                    setOnClickListener { p.hslRange = k; h.rerenderPanel() }
                }, LinearLayout.LayoutParams(0, dp(28), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
            b.addView(dots, LinearLayout.LayoutParams(-1, dp(34)).apply { bottomMargin = dp(4) })
            val k = p.hslRange
            fun setAt(l: List<Float>, v: Float) = l.toMutableList().also { it[k] = v }
            b.addView(adjSlider(el, "Hue · ${PeColor.HUE_LABELS[k]}", adj.hslHue[k]) { x, v -> x.copy(hslHue = setAt(x.hslHue, v)) })
            b.addView(adjSlider(el, "Saturasi", adj.hslSat[k]) { x, v -> x.copy(hslSat = setAt(x.hslSat, v)) })
            b.addView(adjSlider(el, "Luminansi", adj.hslLum[k]) { x, v -> x.copy(hslLum = setAt(x.hslLum, v)) })
        }
    }

    // ------------------------------------------------------------------ Detail

    private fun detail(el: CsElement) {
        val adj = el.edit.adjust
        seg(listOf("tajam" to "Tajam", "efek" to "Efek", "blur" to "Blur"), p.detailSub, resetPill(el) { x ->
            when (p.detailSub) {
                "tajam" -> x.copy(sharpen = 0f, denoise = 0f, clarity = 0f)
                "efek" -> x.copy(vignette = 0f, grain = 0f)
                else -> x.copy(blur = 0f, bgBlur = 0f)
            }
        }) { k -> p.detailSub = k; h.rerenderPanel() }
        val b = h.body()
        val p100: (Int) -> String = { "$it" }
        when (p.detailSub) {
            "tajam" -> {
                b.addView(adjSlider(el, "Ketajaman", adj.sharpen, 0, fmt = p100) { x, v -> x.copy(sharpen = v) })
                b.addView(adjSlider(el, "Kurangi noise", adj.denoise, 0, fmt = p100) { x, v -> x.copy(denoise = v) })
                b.addView(adjSlider(el, "Clarity", adj.clarity) { x, v -> x.copy(clarity = v) })
            }
            "efek" -> {
                b.addView(adjSlider(el, "Vignette", adj.vignette) { x, v -> x.copy(vignette = v) })
                b.addView(adjSlider(el, "Grain", adj.grain, 0, fmt = p100) { x, v -> x.copy(grain = v) })
                h.note("Vignette negatif = tepi terang. Grain mengikuti ukuran foto saat ekspor.")
            }
            else -> {
                b.addView(adjSlider(el, "Blur", adj.blur, 0, fmt = p100) { x, v -> x.copy(blur = v) })
                b.addView(kit.slider("Blur latar", 0, 100, (adj.bgBlur * 100).roundToInt(), 0,
                    onStart = { h.checkpoint(); if (el.maskId == null) runCutout(el, "subjek", cutOn = false) },
                    onStop = { h.changed(rerenderPanel = false) }) { v ->
                    el.edit = el.edit.copy(adjust = el.edit.adjust.copy(bgBlur = v / 100f))
                    h.renderImage(el, true)
                })
                h.note(when {
                    aiBusy -> "AI sedang mendeteksi subjek…"
                    el.maskId != null -> "Subjek terdeteksi — hanya latar yang diburamkan (mask yang sama dengan Hapus latar)."
                    else -> "Geser 'Blur latar' untuk mendeteksi subjek dengan AI (ML Kit, di perangkat)."
                })
            }
        }
    }

    // ------------------------------------------------------------------ Hapus latar

    private fun methodLabel(m: String) = when (m) {
        "subjek" -> "AI subjek"
        "orang" -> "AI orang"
        "warna" -> "warna latar"
        "kuas" -> "manual"
        else -> "—"
    }

    private fun latar(el: CsElement) {
        val cut = el.cut
        val maskId = el.maskId
        val hasMask = maskId != null
        h.tiles(
            kit.tile("account-box-outline", "Subjek AI", hasMask && cut.method == "subjek") { runCutout(el, "subjek", cutOn = true) },
            kit.tile("account-outline", "Orang AI", hasMask && cut.method == "orang") { runCutout(el, "orang", cutOn = true) },
            kit.tile("format-color-fill", "Warna latar", hasMask && cut.method == "warna") { runCutout(el, "warna", cutOn = true) },
            kit.tile("brush", "Kuas") { h.openFocus(el, "mask") },
            height = 62
        )
        if (cut.method == "warna") {
            h.body().addView(kit.slider("Toleransi", 5, 120, cut.tolerance, 34,
                onStart = { h.checkpoint() },
                onStop = { runCutout(el, "warna", cutOn = true) }) { v -> el.cut = el.cut.copy(tolerance = v) })
            h.note("Untuk latar polos: hanya warna yang tersambung ke tepi foto yang dihapus.")
        }
        if (maskId == null) {
            h.note("Subjek AI: orang, hewan, produk (model Google Play Services, sekali unduh). Orang AI: offline. " +
                "Warna latar: untuk latar polos. Kuas: hapus/kembalikan manual dengan zoom.")
            return
        }
        h.body().addView(kit.toggleRow("Hapus latar (terapkan potongan)", cut.on) { on ->
            h.checkpoint(); el.cut = el.cut.copy(on = on); h.changed()
        }, LinearLayout.LayoutParams(-1, dp(40)))
        h.tiles(
            kit.tile("swap-horizontal", "Balik") { invert(el) },
            kit.tile("crop", "Pangkas ke subjek") { trim(el) },
            kit.tile("restore", "Reset") { h.checkpoint(); el.maskId = null; el.cut = el.cut.copy(on = false, method = ""); h.changed() },
            height = 58
        )
        h.caption("TEPI")
        val b = h.body()
        fun cutSlider(label: String, min: Int, max: Int, value: Int, def: Int, fmt: (Int) -> String, set: (CsCut, Int) -> CsCut) =
            b.addView(kit.slider(label, min, max, value.coerceIn(min, max), def, format = fmt,
                onStart = { h.checkpoint() }, onStop = { h.changed(rerenderPanel = false) }) { v -> el.cut = set(el.cut, v) })
        cutSlider("Halus", 0, 100, cut.smooth, 30, { "$it%" }) { x, v -> x.copy(smooth = v) }
        cutSlider("Geser tepi", -12, 12, cut.shift, 0, { if (it > 0) "+$it" else "$it" }) { x, v -> x.copy(shift = v) }
        cutSlider("Feather", 0, 24, cut.feather, 1, { "${it}px" }) { x, v -> x.copy(feather = v) }
        b.addView(kit.toggleRow("Dekontaminasi warna tepi (hapus halo latar lama)", cut.decontam) { on ->
            h.checkpoint(); el.cut = el.cut.copy(decontam = on); h.changed(rerenderPanel = false)
        }, LinearLayout.LayoutParams(-1, dp(40)))

        h.caption("LATAR PENGGANTI")
        val mode = h.replacementMode(el)
        b.addView(kit.segmented(listOf("transparan" to "Transparan", "warna" to "Warna", "gradien" to "Gradien", "gambar" to "Gambar", "blur" to "Blur"), mode) { k ->
            h.setReplacement(el, k)
        }, LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(6) })
        val bg = h.canvasBackground()
        when (mode) {
            "warna" -> b.addView(kit.swatchRow(kit.palette(), bg.color) { col -> h.setCanvasBackground(bg.copy(mode = "solid", color = col)) },
                LinearLayout.LayoutParams(-1, dp(32)))
            "gradien" -> {
                b.addView(kit.swatchRow(kit.palette(), bg.color) { col -> h.setCanvasBackground(bg.copy(mode = "gradient", color = col)) },
                    LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(6) })
                b.addView(kit.swatchRow(kit.palette(), bg.color2) { col -> h.setCanvasBackground(bg.copy(mode = "gradient", color2 = col)) },
                    LinearLayout.LayoutParams(-1, dp(32)))
            }
            "gambar" -> b.addView(kit.secondaryButton(if (h.backgroundLayer(el) == null) "Pilih gambar latar" else "Ganti gambar latar") { h.setReplacement(el, "gambar") },
                LinearLayout.LayoutParams(-1, dp(38)))
            "blur" -> h.backgroundLayer(el)?.let { layer ->
                b.addView(h.liveSlider("Kekuatan blur", 5, 100, (layer.edit.adjust.blur * 100).roundToInt(), { "$it" }) { v ->
                    layer.edit = layer.edit.copy(adjust = layer.edit.adjust.copy(blur = v / 100f))
                    h.renderImage(layer, true)
                })
            }
            else -> h.note("Kotak-kotak = transparan. Ekspor PNG atau WebP agar transparansi tetap ada.")
        }
        if (mode == "warna" || mode == "gradien") h.note("Warna & gradien memakai latar kanvas (di belakang semua layer).")
        if (mode == "gambar" || mode == "blur") h.note("Latar dibuat sebagai layer sendiri di bawah foto (bisa diedit seperti foto lain).")

        h.caption("BAYANGAN SUBJEK")
        h.shadowControls(el)
        val cov = CsMasks.get(a, maskId)?.let { MaskOps.coverage(it.third) }
        h.note("Metode: ${methodLabel(cut.method)}" + (cov?.let { " · subjek $it% dari foto" } ?: "") +
            ". Kuas membuka tampilan zoom untuk merapikan tepi.")
    }

    private fun invert(el: CsElement) {
        val id = el.maskId ?: return
        val m = CsMasks.get(a, id) ?: run { a.toast("Mask tidak ditemukan"); return }
        h.commitMask(el, CsMath.invertMask(m.third), m.first, m.second, el.cut.method.ifBlank { "kuas" }, cutOn = true)
    }

    /** Pangkas foto ke kotak subjek (+1%) lewat crop non-destruktif — bisa dibatalkan dengan Undo. */
    private fun trim(el: CsElement) {
        h.busy(true, "Mencari batas subjek…")
        CsPipeline.subjectBounds(el, h.workSide()) { b, sw, sh ->
            h.busy(false)
            val cur = h.find(el.id) ?: return@subjectBounds
            if (b == null || sw <= 0 || sh <= 0) { a.toast("Subjek tidak ditemukan"); return@subjectBounds }
            val pr = CsMath.padRect(b, sw, sh, (max(sw, sh) * 0.01f).roundToInt())
            // Sudut kotak di ruang sumber → bingkai (tanpa crop) lewat matriks geometri yang sama.
            val frame = cur.edit.geom.copy(cl = 0f, ct = 0f, cr = 1f, cb = 1f)
            val (m, fw, fh) = PeGeometry.matrix(sw, sh, frame, false)
            val pts = floatArrayOf(pr[0].toFloat(), pr[1].toFloat(), pr[2].toFloat(), pr[1].toFloat(), pr[2].toFloat(), pr[3].toFloat(), pr[0].toFloat(), pr[3].toFloat())
            m.mapPoints(pts)
            var l = 1f; var t = 1f; var r = 0f; var bt = 0f
            for (i in 0 until 4) {
                l = min(l, pts[i * 2] / fw); r = max(r, pts[i * 2] / fw)
                t = min(t, pts[i * 2 + 1] / fh); bt = max(bt, pts[i * 2 + 1] / fh)
            }
            l = l.coerceIn(0f, 1f); r = r.coerceIn(0f, 1f); t = t.coerceIn(0f, 1f); bt = bt.coerceIn(0f, 1f)
            if (r - l < 0.02f || bt - t < 0.02f) { a.toast("Area subjek terlalu kecil"); return@subjectBounds }
            h.checkpoint()
            h.applyGeom(cur, cur.edit.geom.copy(cl = l, ct = t, cr = r, cb = bt, ratio = "bebas"))
            h.changed()
            a.toast("Dipangkas ke subjek")
        }
    }

    /**
     * AI subjek / orang (ML Kit di perangkat) atau warna latar (flood-fill dari tepi) → mask baru.
     * [cutOn] false = hanya mendeteksi (untuk Blur latar) tanpa menghapus latar.
     */
    fun runCutout(el: CsElement, method: String, cutOn: Boolean) {
        if (aiBusy) { a.toast("Deteksi sedang berjalan…"); return }
        aiBusy = true
        val id = el.id
        val tolerance = el.cut.tolerance
        h.busy(true, when (method) {
            "orang" -> "AI mendeteksi orang…"
            "warna" -> "Mendeteksi warna latar…"
            else -> "AI mendeteksi subjek…"
        })
        CsPipeline.healedSource(el, h.workSide()) { src ->
            if (src == null) {
                finish(id, Result.failure(IllegalStateException("Foto tidak bisa dibaca")), 0, 0, method, cutOn)
                return@healedSource
            }
            val w = src.width
            val hh = src.height
            if (method == "warna") {
                Thread {
                    val r = runCatching {
                        val px = IntArray(w * hh)
                        src.getPixels(px, 0, w, 0, 0, w, hh)
                        MaskOps.colorKey(px, w, hh, tolerance, 18)
                    }
                    a.runOnUiThread { finish(id, r, w, hh, method, cutOn) }
                }.start()
                return@healedSource
            }
            val mode = if (method == "orang") AiSegmenter.Mode.PERSON else AiSegmenter.Mode.SUBJECT
            AiSegmenter.segment(a, src, mode) { result ->
                result.onSuccess { m ->
                    Thread {
                        val r = runCatching { MaskOps.fromConfidence(m, w, hh) }
                        a.runOnUiThread { finish(id, r, w, hh, method, cutOn) }
                    }.start()
                }.onFailure { e ->
                    a.runOnUiThread {
                        aiBusy = false
                        h.busy(false)
                        h.find(id)?.let { showAiError(it, method, e.message ?: "Segmentasi gagal", cutOn) }
                    }
                }
            }
        }
    }

    private fun finish(id: String, r: Result<ByteArray>, w: Int, hh: Int, method: String, cutOn: Boolean) {
        aiBusy = false
        h.busy(false)
        val el = h.find(id) ?: return
        r.onSuccess { mask ->
            if (mask.size != w * hh || w <= 0) { a.toast("Ukuran mask tidak cocok"); return@onSuccess }
            val cov = MaskOps.coverage(mask)
            h.commitMask(el, mask, w, hh, method, cutOn || el.cut.on)
            when {
                cov < 1 -> a.toast("Subjek tidak ditemukan. Coba metode lain atau pakai Kuas › Kembalikan.")
                cov > 97 -> a.toast("Hampir seluruh foto dianggap subjek. Coba metode lain atau Kuas › Hapus.")
            }
        }.onFailure { e -> a.toast("Gagal: ${e.message}") }
    }

    private fun showAiError(el: CsElement, method: String, msg: String, cutOn: Boolean) {
        a.themedDialog()
            .setTitle("AI belum bisa dipakai")
            .setMessage(msg)
            .setPositiveButton(if (method == "orang") "Coba Subjek AI" else "Coba Orang AI (offline)") { _, _ ->
                h.find(el.id)?.let { runCutout(it, if (method == "orang") "subjek" else "orang", cutOn) }
            }
            .setNeutralButton("Warna latar") { _, _ -> h.find(el.id)?.let { runCutout(it, "warna", cutOn) } }
            .setNegativeButton("Tutup", null)
            .show()
    }

    // ------------------------------------------------------------------ editor kurva

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class CurveView(ctx: Context, private val el: CsElement) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var dragIdx = -1
        private var hist: FloatArray? = null
        private var histKey: Any? = null

        private fun pts(): CurvePts = when (p.curveCh) {
            1 -> el.edit.adjust.curveR
            2 -> el.edit.adjust.curveG
            3 -> el.edit.adjust.curveB
            else -> el.edit.adjust.curveM
        }

        private fun setPts(v: CurvePts) {
            val sorted = v.sortedBy { it.first }
            val adj = el.edit.adjust
            el.edit = el.edit.copy(adjust = when (p.curveCh) {
                1 -> adj.copy(curveR = sorted)
                2 -> adj.copy(curveG = sorted)
                3 -> adj.copy(curveB = sorted)
                else -> adj.copy(curveM = sorted)
            })
            h.renderImage(el, true)
            invalidate()
        }

        private val inset get() = dp(8).toFloat()
        private fun gx(x: Float) = inset + x * (width - inset * 2)
        private fun gy(y: Float) = height - inset - y * (height - inset * 2)
        private fun vx(px: Float) = ((px - inset) / (width - inset * 2)).coerceIn(0f, 1f)
        private fun vy(py: Float) = ((height - inset - py) / (height - inset * 2)).coerceIn(0f, 1f)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val hh = height.toFloat()
            paint.style = Paint.Style.FILL
            paint.color = c.surface2
            canvas.drawRoundRect(RectF(0f, 0f, w, hh), dp(12).toFloat(), dp(12).toFloat(), paint)
            val src = CsPipeline.result("el:${el.id}")?.src
            if (src != null && histKey !== src) {
                histKey = src
                hist = runCatching {
                    val sm = fitLongSide(src, 256)
                    val px = IntArray(sm.width * sm.height)
                    sm.getPixels(px, 0, sm.width, 0, 0, sm.width, sm.height)
                    if (sm !== src) sm.recycle()
                    PeColor.histogram(px, if (p.curveCh == 0) -1 else p.curveCh - 1)
                }.getOrNull()
            }
            hist?.let { hs ->
                paint.color = if (c.dark) Color.argb(60, 255, 255, 255) else Color.argb(40, 0, 0, 0)
                val path = Path()
                path.moveTo(gx(0f), gy(0f))
                for (i in 0 until 256) path.lineTo(gx(i / 255f), gy(hs[i] * 0.9f))
                path.lineTo(gx(1f), gy(0f))
                path.close()
                canvas.drawPath(path, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = c.stroke
            for (k in 1..3) {
                canvas.drawLine(gx(k / 4f), gy(0f), gx(k / 4f), gy(1f), paint)
                canvas.drawLine(gx(0f), gy(k / 4f), gx(1f), gy(k / 4f), paint)
            }
            canvas.drawLine(gx(0f), gy(0f), gx(1f), gy(1f), paint)
            val lut = PeCurves.lut(pts(), 256)
            paint.strokeWidth = dp(2).toFloat()
            paint.color = when (p.curveCh) { 1 -> Color.rgb(229, 57, 53); 2 -> Color.rgb(67, 160, 71); 3 -> Color.rgb(30, 136, 229); else -> c.ink }
            val path = Path()
            for (i in 0 until 256) {
                val x = gx(i / 255f)
                val y = gy(lut[i])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
            for (pt in pts()) {
                paint.style = Paint.Style.FILL
                paint.color = c.surface
                canvas.drawCircle(gx(pt.first), gy(pt.second), dp(6).toFloat(), paint)
                paint.style = Paint.Style.STROKE
                paint.color = c.ink
                paint.strokeWidth = dp(2).toFloat()
                canvas.drawCircle(gx(pt.first), gy(pt.second), dp(6).toFloat(), paint)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            val list = pts().toMutableList()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    h.checkpoint()
                    dragIdx = list.indices.minByOrNull { hypot(gx(list[it].first) - e.x, gy(list[it].second) - e.y) }
                        ?.takeIf { hypot(gx(list[it].first) - e.x, gy(list[it].second) - e.y) < dp(24) } ?: -1
                    if (dragIdx < 0 && list.size < 12) {
                        val x = vx(e.x)
                        list.add(x to vy(e.y))
                        val sorted = list.sortedBy { it.first }
                        dragIdx = sorted.indexOfFirst { it.first == x }
                        setPts(sorted)
                    }
                }
                MotionEvent.ACTION_MOVE -> if (dragIdx in list.indices) {
                    val isEnd = dragIdx == 0 || dragIdx == list.size - 1
                    // Seret jauh keluar kotak = hapus titik (kecuali titik ujung).
                    if (!isEnd && (e.y < -dp(30) || e.y > height + dp(30))) {
                        list.removeAt(dragIdx)
                        dragIdx = -1
                        setPts(list)
                        return true
                    }
                    val x = if (isEnd) list[dragIdx].first
                    else CsMath.clampF(vx(e.x), list[dragIdx - 1].first + 0.01f, list[dragIdx + 1].first - 0.01f)
                    list[dragIdx] = x to vy(e.y)
                    setPts(list)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragIdx = -1
                    h.changed(rerenderPanel = false)
                }
            }
            return true
        }
    }
}
