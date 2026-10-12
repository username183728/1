package com.example.aidetest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Base64
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Studio Kreatif — Photo Editor + Design Studio + Hapus Latar dalam satu dokumen berlapis.
 *
 *  - CreativeStudio.kt (file ini): pintu masuk tool + alias id lama, state proyek, template,
 *    penyimpanan proyek/mask, render layer ke Canvas, dan ekspor SVG.
 *  - CsModel / CsCodec / CsLogic: model, format JSON v3 + migrasi, matematika murni (teruji JVM).
 *  - CsPipeline.kt: render foto per layer di thread kerja (heal → potong latar → geometri →
 *    warna → detail) + render resolusi penuh untuk ekspor.
 *  - CreativeStudioUi.kt: kanvas (panduan pintar, snap, rata, rotasi, multi-pilih), dock dua
 *    tingkat, panel, proyek, ekspor.
 *  - CsPhotoTools.kt: panel layer foto (Atur, Filter, Kurva & HSL, Detail, Hapus Latar).
 *  - CsFocusEditor.kt: tampilan fokus layar penuh untuk Potong, Heal, dan Kuas mask.
 */

internal const val CREATIVE_STUDIO_PAGE = "Studio Kreatif"
internal const val CS_PICK_IMAGE = 1220
internal const val CS_UNDO_LIMIT = 40
/** Salinan sumber privat ≤ 3072 px: cukup untuk ekspor resolusi penuh (batas ekspor Photo Editor lama). */
internal const val CS_SOURCE_MAX = 3072

internal fun MainActivity.creativeStudioTool(entry: CsToolIds.Entry = CsToolIds.Entry.DEFAULT) {
    clearPage(CREATIVE_STUDIO_PAGE)
    // Kanvas memakai seluruh layar: top bar aplikasi diganti top bar studio sendiri.
    topBarVisibility(false)
    content.setPadding(0, 0, 0, 0)
    val ui = CreativeStudioUi(this, entry)
    content.addView(ui.root, LinearLayout.LayoutParams(-1, 0, 1f))
}

// Id/halaman lama tetap berfungsi (openTool, pemulihan back-stack, sesi ganti tema).
internal fun MainActivity.designStudioTool() = creativeStudioTool(CsToolIds.Entry.DEFAULT)
internal fun MainActivity.photoEditorTool() = creativeStudioTool(CsToolIds.Entry.PHOTO)
internal fun MainActivity.backgroundRemoverTool() = creativeStudioTool(CsToolIds.Entry.CUTOUT)

/** Id tool lama di favorit / terbaru / statistik pemakaian → creativestudio (idempoten, murah). */
internal fun MainActivity.migrateCreativeStudioIds() {
    val edit = prefs.edit()
    var dirty = false
    for (key in listOf("favorite_tools", "recent_tools")) {
        val v = prefs.getString(key, null)
        if (CsToolIds.hasLegacy(v)) { edit.putString(key, CsToolIds.migrateIdList(v)); dirty = true }
    }
    val usage = prefs.getString("tool_usage", null)
    if (CsToolIds.hasLegacy(usage)) { edit.putString("tool_usage", CsToolIds.migrateUsage(usage)); dirty = true }
    // Kustomisasi tool (pin / sembunyikan) disimpan sebagai StringSet; tipe lain dilewati tanpa
    // menggagalkan migrasi favorit/terbaru di atas.
    for (key in listOf("pinned_tools", "hidden_tools")) {
        val set = runCatching { prefs.getStringSet(key, null) }.getOrNull()
        CsToolIds.migrateIdSet(set)?.let { edit.putStringSet(key, it); dirty = true }
    }
    if (dirty) edit.apply()
}

// ====================================================================== state proyek

internal class CsSnapshot(val w: Int, val h: Int, val bg: CsBackground, val elements: List<CsElement>, val selection: List<String>)

/** Proyek bertahan saat halaman dibangun ulang (Back, buka tool lain, ganti tema). */
internal object CsProject {
    var canvasW = 1280
    var canvasH = 720
    var bg = CsBackground("solid", Color.rgb(20, 20, 24))
    val elements = mutableListOf<CsElement>()
    val selection = LinkedHashSet<String>()
    var multiSelect = false
    var showGrid = false
    var snap = false
    var smartGuides = true
    var showCenter = true
    var gridStep = 32
    var initialized = false
    var userScale = 1f
    var panX = 0f
    var panY = 0f
    var projectName = "Desain 1"
    var projectFile: String? = null
    // Ekspor
    var exportFormat = "png"
    var exportScale = 1f
    var exportCustom = 0
    var exportTransparent = false
    var exportQuality = 92
    var exportTrim = false
    var lastExport: File? = null
    var lastSaved: SavedImage? = null
    // Pilihan panel foto (diingat antar pembukaan)
    var photoSub = "cahaya"
    var detailSub = "tajam"
    var curveSub = "kurva"
    var curveCh = 0
    var hslRange = 0
    var cropSub = "luruskan"
    var healSize = 22
    var brushMode = "hapus"
    var brushSize = 28
    var brushHardness = 60
    var showOriginal = true
    val undo = ArrayList<CsSnapshot>()
    val redo = ArrayList<CsSnapshot>()

    fun doc(): CsDocData = CsDocData(projectName, canvasW, canvasH, bg, elements.map { it.copy() })

    fun imageCount(): Int = elements.count { it.type == CsType.IMAGE }

    fun clearForNew() {
        elements.clear()
        selection.clear()
        undo.clear()
        redo.clear()
        userScale = 1f
        panX = 0f
        panY = 0f
        multiSelect = false
    }
}

internal class CsTemplate(val name: String, val width: Int, val height: Int, val bg: Int, val seed: List<CsElement> = emptyList())

private fun tplText(x: Float, y: Float, w: Float, h: Float, text: String, size: Float, color: Int, align: CsAlign = CsAlign.LEFT, shadow: Boolean = false) =
    CsElement(type = CsType.TEXT, x = x, y = y, w = w, h = h, text = text, textSize = size, bold = true, color = color, align = align, shadow = shadow)

internal val CS_TEMPLATES: List<CsTemplate> = listOf(
    CsTemplate("Kosong", 1080, 1080, Color.WHITE),
    CsTemplate("YouTube", 1280, 720, Color.rgb(20, 20, 24), listOf(tplText(80f, 280f, 1120f, 120f, "Judul Thumbnail", 72f, Color.WHITE, shadow = true))),
    CsTemplate("IG Post", 1080, 1080, Color.rgb(245, 245, 247), listOf(tplText(80f, 480f, 920f, 100f, "Instagram Post", 56f, Color.rgb(30, 30, 34), CsAlign.CENTER))),
    CsTemplate("IG Story", 1080, 1920, Color.rgb(15, 15, 18), listOf(tplText(80f, 900f, 920f, 120f, "Story", 80f, Color.WHITE, CsAlign.CENTER, true))),
    CsTemplate("Poster A4", 1240, 1754, Color.WHITE, listOf(tplText(80f, 200f, 1080f, 100f, "POSTER", 96f, Color.rgb(20, 20, 20), CsAlign.CENTER))),
    CsTemplate("Banner", 1500, 500, Color.rgb(28, 28, 32), listOf(tplText(60f, 180f, 1380f, 100f, "Banner Promosi", 64f, Color.WHITE))),
    CsTemplate("Logo", 512, 512, Color.rgb(18, 18, 22), listOf(
        CsElement(type = CsType.ELLIPSE, x = 96f, y = 96f, w = 320f, h = 320f, color = Color.rgb(235, 235, 238)),
        tplText(96f, 220f, 320f, 80f, "LOGO", 48f, Color.rgb(18, 18, 22), CsAlign.CENTER)
    )),
    CsTemplate("Post X", 1200, 675, Color.rgb(12, 12, 16), listOf(tplText(60f, 280f, 1080f, 90f, "Post Image", 60f, Color.WHITE)))
)

/** Rasio siap pakai (ukuran piksel umum media sosial / cetak). */
internal val CS_RATIOS = listOf(
    Triple("1:1", 1080, 1080), Triple("4:5", 1080, 1350), Triple("9:16", 1080, 1920), Triple("16:9", 1920, 1080),
    Triple("4:3", 1440, 1080), Triple("3:4", 1080, 1440), Triple("3:2", 1620, 1080), Triple("A4", 1240, 1754)
)

// ====================================================================== penyimpanan

internal fun jsonToTree(o: JSONObject): Map<String, Any?> {
    val m = LinkedHashMap<String, Any?>()
    val keys = o.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        m[k] = jsonValue(o.opt(k))
    }
    return m
}

private fun jsonValue(v: Any?): Any? = when (v) {
    is JSONObject -> jsonToTree(v)
    is JSONArray -> List(v.length()) { i -> jsonValue(v.opt(i)) }
    JSONObject.NULL -> null
    else -> v
}

internal fun treeToJson(m: Map<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in m) o.put(k, treeValue(v))
    return o
}

private fun treeValue(v: Any?): Any? = when (v) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().also { o -> for ((k, x) in v) o.put(k.toString(), treeValue(x)) }
    is List<*> -> JSONArray().also { arr -> v.forEach { arr.put(treeValue(it)) } }
    is Float -> v.toDouble()
    else -> v
}

/**
 * Proyek JSON v3 + simpan otomatis. Folder sama dengan Design Studio lama (files/design) sehingga
 * simpan-otomatis dan proyek lama langsung terbaca. Gambar disalin ke folder privat; mask di "mask".
 */
internal object CsStore {
    fun dir(ctx: Context): File = File(ctx.filesDir, "design").apply { mkdirs() }
    fun imgDir(ctx: Context): File = File(dir(ctx), "img").apply { mkdirs() }
    fun maskDir(ctx: Context): File = File(dir(ctx), "mask").apply { mkdirs() }
    fun projectsDir(ctx: Context): File = File(dir(ctx), "projects").apply { mkdirs() }
    fun autosaveFile(ctx: Context): File = File(dir(ctx), "autosave.json")

    fun toJsonText(p: CsProject): String = treeToJson(CsCodec.encodeDoc(p.doc(), System.currentTimeMillis())).toString()

    /** Muat proyek ke [p] (v2 Design Studio atau v3); undo/redo dan seleksi dikosongkan. */
    fun load(ctx: Context, text: String, p: CsProject) {
        val img = imgDir(ctx)
        val d = CsCodec.decodeDoc(jsonToTree(JSONObject(text))) { name -> File(img, name).absolutePath }
        p.clearForNew()
        p.canvasW = d.w
        p.canvasH = d.h
        p.bg = d.bg
        p.projectName = d.name
        p.elements.addAll(d.elements)
    }

    private val writeLock = Any()

    /** Tulis lewat berkas sementara unik; penulisan diserialkan dan berkas lama tidak pernah dihapus bila rename gagal. */
    fun writeAtomic(file: File, text: String) = synchronized(writeLock) {
        file.parentFile?.mkdirs()
        val tmp = File.createTempFile(file.name, ".tmp", file.parentFile)
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                // Beberapa sistem berkas tidak menimpa lewat rename: salin isi, berkas lama tetap utuh bila gagal.
                file.writeText(text)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun safeName(name: String): String = name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim().take(40).ifBlank { "desain" }

    fun listProjects(ctx: Context): List<File> =
        projectsDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun nestedRefs(v: Any?, images: MutableSet<String>, masks: MutableSet<String>) {
        when (v) {
            is Map<*, *> -> {
                if (v["elements"] is List<*>) CsCodec.fileRefs(v as Map<String, Any?>, images, masks)
                v.values.forEach { if (it is Map<*, *> || it is List<*>) nestedRefs(it, images, masks) }
            }
            is List<*> -> v.forEach { if (it is Map<*, *> || it is List<*>) nestedRefs(it, images, masks) }
        }
    }

    /** Hapus gambar & mask yang tidak dirujuk proyek tersimpan, simpan otomatis, atau sesi aktif (undo/redo). */
    fun cleanup(ctx: Context, inUseImages: Set<String>, inUseMasks: Set<String>) {
        val images = HashSet<String>()
        val masks = HashSet<String>()
        inUseImages.forEach { images.add(File(it).name) }
        masks.addAll(inUseMasks)
        for (f in listProjects(ctx) + listOf(autosaveFile(ctx))) {
            if (!f.exists()) continue
            runCatching { CsCodec.fileRefs(jsonToTree(JSONObject(f.readText())), images, masks) }
        }
        // Animation Studio (anim/) & Presentation Studio (present/) memakai gambar & mask yang sama:
        // setiap dokumen bersarang ("elements") di proyek & simpanan otomatisnya ikut dihitung.
        for (sub in listOf("anim", "present")) {
            File(dir(ctx), sub).walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.forEach { f ->
                runCatching { nestedRefs(jsonToTree(JSONObject(f.readText())), images, masks) }
            }
        }
        val cutoff = System.currentTimeMillis() - 60_000L
        imgDir(ctx).listFiles()?.forEach { f -> if (f.name !in images && f.lastModified() < cutoff) f.delete() }
        maskDir(ctx).listFiles()?.forEach { f -> if (f.name.removeSuffix(".msk") !in masks && f.lastModified() < cutoff) f.delete() }
    }
}

/**
 * Mask subjek di ruang sumber, immutable: setiap perubahan disimpan sebagai id baru (file
 * terkompresi + cache memori kecil). Snapshot undo cukup menyimpan id.
 */
internal object CsMasks {
    private const val MAGIC = 0x43534D31 // "CSM1"
    private const val MAX_MEM = 6
    private val mem = LinkedHashMap<String, Triple<Int, Int, ByteArray>>(8, 0.75f, true)

    /** Simpan mask baru; [data] tidak boleh diubah lagi oleh pemanggil. */
    fun put(ctx: Context, w: Int, h: Int, data: ByteArray): String {
        val id = UUID.randomUUID().toString()
        synchronized(this) { cache(id, Triple(w, h, data)) }
        val file = File(CsStore.maskDir(ctx), "$id.msk")
        Thread { runCatching { write(file, w, h, data) } }.start()
        return id
    }

    fun get(ctx: Context, id: String): Triple<Int, Int, ByteArray>? {
        synchronized(this) { mem[id]?.let { return it } }
        val f = File(CsStore.maskDir(ctx), "$id.msk")
        if (!f.exists()) return null
        val r = runCatching { read(f) }.getOrNull() ?: return null
        synchronized(this) { cache(id, r) }
        return r
    }

    private fun cache(id: String, v: Triple<Int, Int, ByteArray>) {
        mem[id] = v
        while (mem.size > MAX_MEM) {
            val k = mem.keys.iterator().next()
            mem.remove(k)
        }
    }

    private fun write(file: File, w: Int, h: Int, data: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(DeflaterOutputStream(FileOutputStream(tmp)))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(w)
            out.writeInt(h)
            out.write(data)
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun read(f: File): Triple<Int, Int, ByteArray> =
        DataInputStream(BufferedInputStream(InflaterInputStream(FileInputStream(f)))).use { inp ->
            if (inp.readInt() != MAGIC) error("Format mask tidak dikenal")
            val w = inp.readInt()
            val h = inp.readInt()
            require(w in 1..8192 && h in 1..8192) { "Ukuran mask tidak valid" }
            val data = ByteArray(w * h)
            inp.readFully(data)
            Triple(w, h, data)
        }
}

// ====================================================================== render layer

/** Bayangan berbentuk subjek (alpha diburamkan) untuk layer foto berlatar transparan. */
internal class CsShadow(val bitmap: Bitmap, val pad: Int, val innerW: Int, val innerH: Int)

internal object CsRender {
    fun typeface(el: CsElement): Typeface {
        val base = PeLayerRender.typeface(el.font)
        val style = when {
            el.bold && el.italic -> Typeface.BOLD_ITALIC
            el.bold -> Typeface.BOLD
            el.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return if (style == Typeface.NORMAL) base else Typeface.create(base, style)
    }

    fun prepareText(el: CsElement, paint: Paint) {
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = el.textSize
        paint.typeface = typeface(el)
        paint.letterSpacing = el.letterSpacing
        paint.isUnderlineText = el.underline
        paint.textAlign = Paint.Align.LEFT
    }

    fun lines(el: CsElement): List<String> = el.text.ifEmpty { " " }.split("\n")

    /** Ukuran kotak teks (px kanvas) yang pas dengan isinya. */
    fun measureText(el: CsElement, paint: Paint): Pair<Float, Float> {
        prepareText(el, paint)
        val ls = lines(el)
        var w = 1f
        for (l in ls) w = max(w, paint.measureText(l))
        val fm = paint.fontMetrics
        val lineH = el.textSize * el.lineSpacing
        return w to (lineH * (ls.size - 1) + (fm.descent - fm.ascent))
    }

    /** Sesuaikan kotak teks dengan isi; jangkar mengikuti perataan, pusat vertikal tetap. */
    fun fitText(el: CsElement, paint: Paint) {
        if (el.type != CsType.TEXT) return
        val (w, h) = measureText(el, paint)
        val cy = el.y + el.h / 2f
        el.x = when (el.align) {
            CsAlign.CENTER -> el.x + el.w / 2f - w / 2f
            CsAlign.RIGHT -> el.x + el.w - w
            else -> el.x
        }
        el.y = cy - h / 2f
        el.w = w
        el.h = h
    }

    fun stickerGlyph(el: CsElement): String = if (el.icon.isNotEmpty()) MdiGlyphs.glyph(el.icon) else el.text.ifEmpty { "★" }

    fun porterDuff(blend: String): PorterDuff.Mode? = when (blend) {
        "multiply" -> PorterDuff.Mode.MULTIPLY
        "screen" -> PorterDuff.Mode.SCREEN
        "overlay" -> PorterDuff.Mode.OVERLAY
        "darken" -> PorterDuff.Mode.DARKEN
        "lighten" -> PorterDuff.Mode.LIGHTEN
        "add" -> PorterDuff.Mode.ADD
        else -> null
    }

    /** Batas layer (AABB setelah rotasi + ruang bayangan/garis) untuk saveLayer yang hemat. */
    private fun layerBounds(el: CsElement): RectF {
        val r = DsBox(el.x, el.y, el.w, el.h, el.rotation).bounds()
        var m = el.strokeWidth + 2f
        if (el.shadow) m += el.shadowBlur * 2f + max(abs(el.shadowDx), abs(el.shadowDy))
        if (el.type == CsType.TEXT && Color.alpha(el.textBg) > 0) m += el.textBgPad * el.textSize
        return RectF(r.left - m, r.top - m, r.right + m, r.bottom + m)
    }

    private fun fill(el: CsElement, paint: Paint) {
        paint.style = Paint.Style.FILL
        if (el.gradient) {
            val g = CsMath.gradientLine(el.w, el.h, el.gradAngle)
            paint.shader = LinearGradient(el.x + g[0], el.y + g[1], el.x + g[2], el.y + g[3], el.color, el.color2, Shader.TileMode.CLAMP)
            paint.color = Color.BLACK
        } else {
            paint.shader = null
            paint.color = el.color
        }
    }

    private fun shadowOn(el: CsElement, paint: Paint) {
        if (el.shadow) paint.setShadowLayer(el.shadowBlur.coerceAtLeast(1f), el.shadowDx, el.shadowDy, el.shadowColor)
        else paint.clearShadowLayer()
    }

    private fun stroke(el: CsElement, paint: Paint, draw: () -> Unit) {
        if (el.strokeWidth <= 0f || Color.alpha(el.strokeColor) == 0) return
        paint.clearShadowLayer()
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = el.strokeWidth
        paint.color = el.strokeColor
        draw()
    }

    /**
     * Gambar satu layer dalam koordinat kanvas. [bmp] = hasil render foto (layer IMAGE),
     * [shadow] = bayangan berbentuk subjek (null = bayangan kotak).
     */
    fun draw(ctx: Context, canvas: Canvas, el: CsElement, paint: Paint, bmp: Bitmap?, shadow: CsShadow?) {
        val mode = porterDuff(el.blend)
        val alpha = (el.opacity.coerceIn(0f, 1f) * 255).roundToInt()
        val save = if (mode != null || alpha < 255) {
            val lp = Paint()
            lp.alpha = alpha
            var whiteBase = false
            if (mode != null) {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    // Blend sungguhan (seperti desain/SVG): area transparan layer tidak menghapus isi di bawahnya.
                    lp.blendMode = when (el.blend) {
                        "multiply" -> android.graphics.BlendMode.MULTIPLY
                        "screen" -> android.graphics.BlendMode.SCREEN
                        "overlay" -> android.graphics.BlendMode.OVERLAY
                        "darken" -> android.graphics.BlendMode.DARKEN
                        "lighten" -> android.graphics.BlendMode.LIGHTEN
                        "add" -> android.graphics.BlendMode.PLUS
                        else -> android.graphics.BlendMode.SRC_OVER
                    }
                } else {
                    lp.xfermode = PorterDuffXfermode(mode)
                    // PorterDuff MULTIPLY mengalikan alpha: isi layer diberi dasar putih agar area kosong tidak menghapus latar.
                    whiteBase = el.blend == "multiply"
                }
            }
            canvas.saveLayer(layerBounds(el), lp).also { if (whiteBase) canvas.drawColor(Color.WHITE) }
        } else canvas.save()
        if (el.rotation != 0f) canvas.rotate(el.rotation, el.x + el.w / 2f, el.y + el.h / 2f)
        paint.reset()
        paint.isAntiAlias = true
        val rect = RectF(el.x, el.y, el.x + el.w, el.y + el.h)
        when (el.type) {
            CsType.RECT -> {
                val r = el.cornerRadius.coerceIn(0f, min(el.w, el.h) / 2f)
                fill(el, paint)
                shadowOn(el, paint)
                if (r > 0f) canvas.drawRoundRect(rect, r, r, paint) else canvas.drawRect(rect, paint)
                stroke(el, paint) { if (r > 0f) canvas.drawRoundRect(rect, r, r, paint) else canvas.drawRect(rect, paint) }
            }
            CsType.ELLIPSE -> {
                fill(el, paint)
                shadowOn(el, paint)
                canvas.drawOval(rect, paint)
                stroke(el, paint) { canvas.drawOval(rect, paint) }
            }
            CsType.TRIANGLE -> {
                val path = Path().apply {
                    moveTo(el.x + el.w / 2f, el.y)
                    lineTo(el.x + el.w, el.y + el.h)
                    lineTo(el.x, el.y + el.h)
                    close()
                }
                fill(el, paint)
                shadowOn(el, paint)
                canvas.drawPath(path, paint)
                stroke(el, paint) { canvas.drawPath(path, paint) }
            }
            CsType.LINE -> {
                // Garis: tebal = tinggi kotak, ujung membulat.
                val t = el.h.coerceAtLeast(1f)
                fill(el, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = t
                shadowOn(el, paint)
                val cy = el.y + el.h / 2f
                canvas.drawLine(el.x + t / 2f, cy, el.x + el.w - t / 2f, cy, paint)
            }
            CsType.TEXT -> drawText(canvas, el, paint)
            CsType.STICKER -> {
                val glyph = stickerGlyph(el)
                val isIcon = el.icon.isNotEmpty()
                paint.typeface = if (isIcon) PeLayerRender.mdi(ctx) ?: Typeface.DEFAULT else Typeface.DEFAULT
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = min(el.w, el.h) * (if (isIcon) 1f else 0.82f)
                val fm = paint.fontMetrics
                val cx = el.x + el.w / 2f
                val baseline = el.y + el.h / 2f - (fm.ascent + fm.descent) / 2f
                if (el.strokeWidth > 0f && Color.alpha(el.strokeColor) > 0) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeJoin = Paint.Join.ROUND
                    paint.strokeWidth = el.strokeWidth
                    paint.color = el.strokeColor
                    shadowOn(el, paint)
                    canvas.drawText(glyph, cx, baseline, paint)
                    paint.clearShadowLayer()
                    paint.style = Paint.Style.FILL
                    paint.color = el.color
                    canvas.drawText(glyph, cx, baseline, paint)
                } else {
                    paint.style = Paint.Style.FILL
                    paint.color = el.color
                    shadowOn(el, paint)
                    canvas.drawText(glyph, cx, baseline, paint)
                }
            }
            CsType.IMAGE -> {
                if (bmp != null && !bmp.isRecycled) {
                    val r = el.cornerRadius.coerceIn(0f, min(el.w, el.h) / 2f)
                    if (el.shadow) {
                        if (shadow != null) {
                            val sx = el.w / shadow.innerW.coerceAtLeast(1)
                            val sy = el.h / shadow.innerH.coerceAtLeast(1)
                            val dst = RectF(
                                el.x - shadow.pad * sx + el.shadowDx, el.y - shadow.pad * sy + el.shadowDy,
                                el.x + el.w + shadow.pad * sx + el.shadowDx, el.y + el.h + shadow.pad * sy + el.shadowDy
                            )
                            val sp = Paint(Paint.FILTER_BITMAP_FLAG)
                            sp.colorFilter = PorterDuffColorFilter(el.shadowColor, PorterDuff.Mode.SRC_IN)
                            canvas.drawBitmap(shadow.bitmap, null, dst, sp)
                        } else {
                            paint.style = Paint.Style.FILL
                            paint.color = Color.BLACK
                            shadowOn(el, paint)
                            canvas.drawRoundRect(rect, r, r, paint)
                            paint.clearShadowLayer()
                        }
                    }
                    paint.isFilterBitmap = true
                    if (r > 0f) {
                        canvas.save()
                        canvas.clipPath(Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) })
                        canvas.drawBitmap(bmp, null, rect, paint)
                        canvas.restore()
                    } else canvas.drawBitmap(bmp, null, rect, paint)
                    stroke(el, paint) { if (r > 0f) canvas.drawRoundRect(rect, r, r, paint) else canvas.drawRect(rect, paint) }
                } else {
                    // Foto belum selesai dirender: placeholder agar tata letak tetap terlihat.
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb(60, 128, 128, 128)
                    canvas.drawRect(rect, paint)
                }
            }
        }
        canvas.restoreToCount(save)
    }

    private fun drawText(canvas: Canvas, el: CsElement, paint: Paint) {
        val boxOn = Color.alpha(el.textBg) > 0
        if (boxOn) {
            // Kotak latar teks (bayangan ikut kotak, bukan huruf).
            val pad = el.textBgPad * el.textSize
            val r = el.cornerRadius.coerceAtLeast(0f)
            paint.style = Paint.Style.FILL
            paint.color = el.textBg
            shadowOn(el, paint)
            canvas.drawRoundRect(RectF(el.x - pad, el.y - pad * 0.5f, el.x + el.w + pad, el.y + el.h + pad * 0.5f), r, r, paint)
            paint.clearShadowLayer()
        }
        prepareText(el, paint)
        val ls = lines(el)
        val fm = paint.fontMetrics
        val lineH = el.textSize * el.lineSpacing
        val widths = ls.map { paint.measureText(it) }
        fun drawAll() {
            var baseline = el.y - fm.ascent
            for ((i, line) in ls.withIndex()) {
                val lx = when (el.align) {
                    CsAlign.CENTER -> el.x + (el.w - widths[i]) / 2f
                    CsAlign.RIGHT -> el.x + el.w - widths[i]
                    else -> el.x
                }
                canvas.drawText(line, lx, baseline, paint)
                baseline += lineH
            }
        }
        if (el.strokeWidth > 0f && Color.alpha(el.strokeColor) > 0) {
            // Outline di belakang isi agar huruf tetap tegas.
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = el.strokeWidth
            paint.color = el.strokeColor
            if (!boxOn) shadowOn(el, paint)
            drawAll()
            paint.clearShadowLayer()
            paint.style = Paint.Style.FILL
            paint.color = el.color
            drawAll()
        } else {
            paint.style = Paint.Style.FILL
            paint.color = el.color
            if (!boxOn) shadowOn(el, paint)
            drawAll()
        }
    }

    /**
     * Latar kanvas (solid / gradien) dalam kotak 0..w × 0..h; transparan tidak menggambar apa pun.
     * Selalu drawRect (bukan drawColor) agar di editor tidak membanjiri area kerja di luar kanvas.
     */
    fun drawBackground(canvas: Canvas, bg: CsBackground, w: Float, h: Float, paint: Paint) {
        when (bg.mode) {
            "transparent" -> Unit
            "gradient" -> {
                val g = CsMath.gradientLine(w, h, bg.angle)
                paint.reset()
                paint.shader = LinearGradient(g[0], g[1], g[2], g[3], bg.color, bg.color2, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, paint)
                paint.shader = null
            }
            else -> {
                paint.reset()
                paint.style = Paint.Style.FILL
                paint.color = bg.color or 0xFF000000.toInt()
                canvas.drawRect(0f, 0f, w, h, paint)
            }
        }
    }

    /**
     * Bayangan subjek: alpha hasil potongan diperkecil (≤ [maxSide] px), diberi margin lalu
     * diburamkan 2× box blur. Warna diberikan saat menggambar (ColorFilter SRC_IN).
     */
    fun makeShadow(src: Bitmap, blurCanvasPx: Float, elW: Float, maxSide: Int): CsShadow {
        val s = min(1f, maxSide.toFloat() / max(src.width, src.height))
        val sw = max(1, (src.width * s).roundToInt())
        val sh = max(1, (src.height * s).roundToInt())
        val small = if (sw == src.width && sh == src.height) src else Bitmap.createScaledBitmap(src, sw, sh, true)
        val blur = (blurCanvasPx * sw / elW.coerceAtLeast(1f) / 2f).roundToInt().coerceIn(0, 48)
        val pad = blur * 2 + 1
        val w = sw + pad * 2
        val h = sh + pad * 2
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        // Salinan kecil dibuat di sini saja; sumber (bisa pratinjau milik UI) tidak pernah dilepas.
        if (small !== src) small.recycle()
        val a = ByteArray(w * h)
        for (y in 0 until sh) {
            val row = (y + pad) * w + pad
            for (x in 0 until sw) a[row + x] = (px[y * sw + x] ushr 24).toByte()
        }
        val blurred = if (blur > 0) MaskOps.blur(MaskOps.blur(a, w, h, blur), w, h, blur) else a
        val out = IntArray(w * h) { ((blurred[it].toInt() and 0xFF) shl 24) or 0xFFFFFF }
        return CsShadow(Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888), pad, sw, sh)
    }

    /** Stiker sebagai bitmap (untuk SVG); tanpa rotasi/opasitas — itu diterapkan oleh grup SVG. */
    fun stickerBitmap(ctx: Context, el: CsElement, maxSide: Int): Bitmap {
        val s = min(1f, maxSide / max(el.w, el.h).coerceAtLeast(1f))
        val bw = max(1, (el.w * s).roundToInt())
        val bh = max(1, (el.h * s).roundToInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(s, s)
        cv.translate(-el.x, -el.y)
        draw(ctx, cv, el.copy(rotation = 0f, opacity = 1f, blend = "normal", shadow = false), Paint(Paint.ANTI_ALIAS_FLAG), null, null)
        return bmp
    }
}

// ====================================================================== SVG

private fun svgFont(font: String): String = when (font) {
    "serif", "serifb" -> "serif"
    "mono" -> "monospace"
    "script" -> "cursive"
    "condensed" -> "sans-serif-condensed, sans-serif"
    else -> "sans-serif"
}

/**
 * SVG: teks & bentuk tetap vektor (termasuk gradien, outline, kotak latar teks, mode campur);
 * foto dan stiker disematkan sebagai gambar. Bayangan tidak diekspor ke SVG.
 * [raster] memberi bitmap untuk layer IMAGE/STICKER.
 */
internal fun buildCsSvg(w: Int, h: Int, bg: CsBackground, elements: List<CsElement>, transparent: Boolean, raster: (CsElement) -> Bitmap?): String {
    fun hex(c: Int) = String.format(Locale.US, "#%06X", 0xFFFFFF and c)
    fun n(v: Float) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun opacityAttr(name: String, c: Int): String {
        val a = Color.alpha(c)
        return if (a < 255) " $name=\"${n(a / 255f)}\"" else ""
    }
    val defs = StringBuilder()
    var gradN = 0
    fun gradient(x: Float, y: Float, gw: Float, gh: Float, deg: Float, c1: Int, c2: Int): String {
        val id = "g${gradN++}"
        val g = CsMath.gradientLine(gw, gh, deg)
        defs.append("<linearGradient id=\"$id\" gradientUnits=\"userSpaceOnUse\" x1=\"${n(x + g[0])}\" y1=\"${n(y + g[1])}\" x2=\"${n(x + g[2])}\" y2=\"${n(y + g[3])}\">")
        defs.append("<stop offset=\"0\" stop-color=\"${hex(c1)}\"${opacityAttr("stop-opacity", c1)}/>")
        defs.append("<stop offset=\"1\" stop-color=\"${hex(c2)}\"${opacityAttr("stop-opacity", c2)}/></linearGradient>")
        return "fill=\"url(#$id)\""
    }
    fun fillOf(el: CsElement): String =
        if (el.gradient) gradient(el.x, el.y, el.w, el.h, el.gradAngle, el.color, el.color2)
        else "fill=\"${hex(el.color)}\"" + opacityAttr("fill-opacity", el.color)
    fun strokeAttr(el: CsElement): String =
        if (el.strokeWidth > 0f && Color.alpha(el.strokeColor) > 0) " stroke=\"${hex(el.strokeColor)}\" stroke-width=\"${n(el.strokeWidth)}\"" else ""
    fun image(el: CsElement, bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        val alpha = bmp.hasAlpha()
        bmp.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out)
        val data = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return "<image x=\"${n(el.x)}\" y=\"${n(el.y)}\" width=\"${n(el.w)}\" height=\"${n(el.h)}\" preserveAspectRatio=\"none\" xlink:href=\"data:${if (alpha) "image/png" else "image/jpeg"};base64,$data\"/>"
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val body = StringBuilder()
    if (!transparent) {
        when (bg.mode) {
            "transparent" -> Unit
            "gradient" -> body.append("<rect width=\"$w\" height=\"$h\" ${gradient(0f, 0f, w.toFloat(), h.toFloat(), bg.angle, bg.color, bg.color2)}/>")
            else -> body.append("<rect width=\"$w\" height=\"$h\" fill=\"${hex(bg.color)}\"/>")
        }
    }
    for (el in elements) {
        if (!el.visible) continue
        val cx = el.x + el.w / 2f
        val cy = el.y + el.h / 2f
        val attrs = StringBuilder()
        if (el.rotation != 0f) attrs.append(" transform=\"rotate(${n(el.rotation)} ${n(cx)} ${n(cy)})\"")
        if (el.opacity < 0.999f) attrs.append(" opacity=\"${n(el.opacity)}\"")
        if (el.blend != "normal") attrs.append(" style=\"mix-blend-mode:${if (el.blend == "add") "plus-lighter" else el.blend}\"")
        body.append("<g$attrs>")
        when (el.type) {
            CsType.RECT -> {
                val r = el.cornerRadius.coerceIn(0f, min(el.w, el.h) / 2f)
                body.append("<rect x=\"${n(el.x)}\" y=\"${n(el.y)}\" width=\"${n(el.w)}\" height=\"${n(el.h)}\"")
                if (r > 0f) body.append(" rx=\"${n(r)}\"")
                body.append(" ${fillOf(el)}${strokeAttr(el)}/>")
            }
            CsType.ELLIPSE ->
                body.append("<ellipse cx=\"${n(cx)}\" cy=\"${n(cy)}\" rx=\"${n(el.w / 2)}\" ry=\"${n(el.h / 2)}\" ${fillOf(el)}${strokeAttr(el)}/>")
            CsType.TRIANGLE ->
                body.append("<polygon points=\"${n(cx)},${n(el.y)} ${n(el.x + el.w)},${n(el.y + el.h)} ${n(el.x)},${n(el.y + el.h)}\" ${fillOf(el)}${strokeAttr(el)}/>")
            CsType.LINE -> {
                val t = el.h.coerceAtLeast(1f)
                body.append("<line x1=\"${n(el.x + t / 2)}\" y1=\"${n(cy)}\" x2=\"${n(el.x + el.w - t / 2)}\" y2=\"${n(cy)}\" stroke=\"${hex(el.color)}\" stroke-width=\"${n(t)}\" stroke-linecap=\"round\"/>")
            }
            CsType.TEXT -> {
                if (Color.alpha(el.textBg) > 0) {
                    val pad = el.textBgPad * el.textSize
                    body.append("<rect x=\"${n(el.x - pad)}\" y=\"${n(el.y - pad * 0.5f)}\" width=\"${n(el.w + pad * 2)}\" height=\"${n(el.h + pad)}\" rx=\"${n(el.cornerRadius)}\" fill=\"${hex(el.textBg)}\"${opacityAttr("fill-opacity", el.textBg)}/>")
                }
                CsRender.prepareText(el, paint)
                val fm = paint.fontMetrics
                val lineH = el.textSize * el.lineSpacing
                var baseline = el.y - fm.ascent
                val weight = if (el.bold || el.font == "bold" || el.font == "serifb" || el.font == "condensed") "bold" else "normal"
                val style = if (el.italic) " font-style=\"italic\"" else ""
                val deco = if (el.underline) " text-decoration=\"underline\"" else ""
                val spacing = if (el.letterSpacing != 0f) " letter-spacing=\"${n(el.letterSpacing * el.textSize)}\"" else ""
                val outline = if (el.strokeWidth > 0f && Color.alpha(el.strokeColor) > 0)
                    " stroke=\"${hex(el.strokeColor)}\" stroke-width=\"${n(el.strokeWidth)}\" paint-order=\"stroke\" stroke-linejoin=\"round\"" else ""
                for (line in CsRender.lines(el)) {
                    val lw = paint.measureText(line)
                    val lx = when (el.align) {
                        CsAlign.CENTER -> el.x + (el.w - lw) / 2f
                        CsAlign.RIGHT -> el.x + el.w - lw
                        else -> el.x
                    }
                    body.append("<text x=\"${n(lx)}\" y=\"${n(baseline)}\" fill=\"${hex(el.color)}\"${opacityAttr("fill-opacity", el.color)} font-size=\"${n(el.textSize)}\" font-family=\"${svgFont(el.font)}\" font-weight=\"$weight\"$style$deco$spacing$outline xml:space=\"preserve\">${esc(line)}</text>")
                    baseline += lineH
                }
            }
            CsType.IMAGE, CsType.STICKER -> raster(el)?.let { body.append(image(el, it)) }
        }
        body.append("</g>")
    }
    val sb = StringBuilder()
    sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
    sb.append("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="$w" height="$h" viewBox="0 0 $w $h">""")
    if (defs.isNotEmpty()) sb.append("<defs>").append(defs).append("</defs>")
    sb.append(body)
    sb.append("</svg>")
    return sb.toString()
}
