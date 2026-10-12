package com.example.aidetest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.widget.LinearLayout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Presentation Studio — bagian suite Studio Kreatif: dek slide, tiap slide = dokumen kanvas Studio
 * Kreatif (CsElement + CsRender + CsCodec). Transisi per slide (tanpa / fade / geser), animasi masuk per
 * objek (preset Animation Studio), mode presentasi layar penuh, simpan/buka proyek (files/design/present),
 * ekspor PDF (satu halaman per slide) dan MP4 slideshow (encoder Animation Studio).
 *  - PresentStudio.kt   : (file ini) model, penyimpanan, sumber frame slideshow, PDF
 *  - PresentStudioUi.kt : editor slide, daftar slide, mode presentasi
 */

internal fun MainActivity.presentationStudioTool() {
    clearPage(PRESENT_STUDIO_PAGE)
    topBarVisibility(false)
    content.setPadding(0, 0, 0, 0)
    val ui = PresentStudioUi(this)
    content.addView(ui.root, LinearLayout.LayoutParams(-1, 0, 1f))
}

internal class PrSlide(
    val id: String = UUID.randomUUID().toString(),
    var bg: CsBackground = CsBackground("solid", Color.WHITE),
    val elements: MutableList<CsElement> = mutableListOf(),
    /** none | fade | slide */
    var transition: String = "fade",
    /** id layer → preset animasi masuk (AnPresets). */
    val anims: MutableMap<String, String> = LinkedHashMap()
) {
    fun deepCopy(newIds: Boolean): PrSlide {
        val map = HashMap<String, String>()
        val els = elements.map { e ->
            val c = if (newIds) e.copy(id = UUID.randomUUID().toString()) else e.copy()
            map[e.id] = c.id
            c
        }.toMutableList()
        val an = LinkedHashMap<String, String>()
        for ((k, v) in anims) map[k]?.let { an[it] = v }
        return PrSlide(if (newIds) UUID.randomUUID().toString() else id, bg, els, transition, an)
    }
}

internal object PrDeck {
    val TRANSITIONS = listOf("none" to "Tanpa", "fade" to "Fade", "slide" to "Geser")
    const val TRANSITION_MS = 450L

    var name = "Presentasi 1"
    var sizeKey = "16:9"
    var w = 1920
    var h = 1080
    val slides = mutableListOf<PrSlide>()
    var current = 0
    var file: String? = null
    var loaded = false
    /** Detik per slide untuk MP4 slideshow (2–20). */
    var secondsPerSlide = 4
    var videoShort = 720
    var selectedId: String? = null
    val undo = ArrayList<String>()
    val redo = ArrayList<String>()
    var lastResult: AnResult? = null
    var job: AnJob? = null

    fun slide(): PrSlide? = slides.getOrNull(current)

    fun doc(s: PrSlide): CsDocData = CsDocData(name, w, h, s.bg, s.elements)

    fun reset() {
        name = "Presentasi 1"
        sizeKey = "16:9"
        w = 1920
        h = 1080
        slides.clear()
        current = 0
        file = null
        selectedId = null
        undo.clear()
        redo.clear()
    }

    /** Skala & geser layer agar konten fromW×fromH pas di tengah slide toW×toH. */
    fun fitElements(els: List<CsElement>, fromW: Int, fromH: Int, toW: Int, toH: Int): List<CsElement> {
        val f = AnLayout.fit(fromW.toFloat(), fromH.toFloat(), toW.toFloat(), toH.toFloat())
        val s = f[0]
        if (s == 1f && f[1] == 0f && f[2] == 0f) return els
        return els.map { e ->
            e.copy(
                x = e.x * s + f[1], y = e.y * s + f[2], w = e.w * s, h = e.h * s,
                textSize = e.textSize * s, strokeWidth = e.strokeWidth * s, cornerRadius = e.cornerRadius * s,
                shadowBlur = e.shadowBlur * s, shadowDx = e.shadowDx * s, shadowDy = e.shadowDy * s,
                fitCanvas = false
            )
        }
    }

    /** Ganti ukuran dek; konten semua slide diskalakan agar tetap utuh. */
    fun resize(key: String) {
        val (nw, nh) = AnLayout.SLIDE_SIZES[key] ?: return
        if (nw == w && nh == h) { sizeKey = key; return }
        for (s in slides) {
            val fitted = fitElements(s.elements, w, h, nw, nh)
            s.elements.clear()
            s.elements.addAll(fitted)
        }
        w = nw
        h = nh
        sizeKey = key
    }

    /** Tambah dokumen Studio Kreatif sebagai slide (dek kosong mengikuti rasio desain). */
    fun appendDoc(d: CsDocData): PrSlide {
        if (slides.isEmpty()) {
            val key = AnLayout.nearestSlideSize(d.w, d.h)
            val (nw, nh) = AnLayout.SLIDE_SIZES.getValue(key)
            sizeKey = key; w = nw; h = nh
            if (name == "Presentasi 1" && d.name.isNotBlank()) name = d.name.take(40)
        }
        val els = fitElements(d.elements, d.w, d.h, w, h).toMutableList()
        val bg = if (d.bg.mode == "transparent") CsBackground("solid", Color.WHITE) else d.bg
        val s = PrSlide(bg = bg, elements = els)
        slides.add(s)
        return s
    }

    fun blankSlide(): PrSlide {
        val title = CsElement(type = CsType.TEXT, x = w * 0.08f, y = h * 0.4f, w = w * 0.84f, h = h * 0.12f,
            text = "Judul slide", textSize = min(w, h) * 0.09f, bold = true, color = Color.rgb(20, 20, 24), align = CsAlign.CENTER)
        return PrSlide(bg = CsBackground("solid", Color.WHITE), elements = mutableListOf(title))
    }
}

internal object PrStore {
    fun dir(ctx: Context): File = File(CsStore.dir(ctx), "present").apply { mkdirs() }
    fun projectsDir(ctx: Context): File = File(dir(ctx), "projects").apply { mkdirs() }
    fun autosaveFile(ctx: Context): File = File(dir(ctx), "autosave.json")

    fun encode(includeFile: Boolean = false): String {
        val d = PrDeck
        val now = System.currentTimeMillis()
        val slides = d.slides.map { s ->
            val m = LinkedHashMap(CsCodec.encodeDoc(PrDeck.doc(s), now))
            m["id"] = s.id
            m["transition"] = s.transition
            m["anims"] = LinkedHashMap<String, Any?>(s.anims)
            m
        }
        val tree = linkedMapOf<String, Any?>(
            "v" to 1, "app" to PRESENT_STUDIO_ID, "name" to d.name, "size" to d.sizeKey, "w" to d.w, "h" to d.h,
            "sps" to d.secondsPerSlide, "current" to d.current, "savedAt" to now, "slides" to slides
        )
        if (includeFile) tree["file"] = d.file ?: ""
        return treeToJson(tree).toString()
    }

    /** Muat teks proyek ke PrDeck (undo/redo dikosongkan). */
    fun decode(ctx: Context, text: String) {
        val m = jsonToTree(JSONObject(text))
        val img = CsStore.imgDir(ctx)
        val key = (m["size"] as? String)?.takeIf { it in AnLayout.SLIDE_SIZES } ?: "16:9"
        val (w, h) = AnLayout.SLIDE_SIZES.getValue(key)
        val out = ArrayList<PrSlide>()
        (m["slides"] as? List<*>)?.forEach { raw ->
            @Suppress("UNCHECKED_CAST")
            val sm = raw as? Map<String, Any?> ?: return@forEach
            val doc = CsCodec.decodeDoc(sm) { n -> File(img, n).absolutePath }
            val ids = doc.elements.map { it.id }.toSet()
            val anims = LinkedHashMap<String, String>()
            (sm["anims"] as? Map<*, *>)?.forEach { (k, v) ->
                val id = k as? String
                val preset = v as? String
                if (id != null && id in ids && preset != null && preset in AnPresets.IDS) anims[id] = preset
            }
            // Dokumen lama berukuran lain (disunting manual) → pas ke ukuran dek.
            val els = PrDeck.fitElements(doc.elements, doc.w, doc.h, w, h).toMutableList()
            out.add(PrSlide(
                id = (sm["id"] as? String)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                bg = doc.bg, elements = els,
                transition = (sm["transition"] as? String)?.takeIf { t -> PrDeck.TRANSITIONS.any { it.first == t } } ?: "fade",
                anims = anims
            ))
        }
        val d = PrDeck
        d.name = (m["name"] as? String)?.ifBlank { null } ?: "Presentasi"
        d.sizeKey = key
        d.w = w
        d.h = h
        d.slides.clear()
        d.slides.addAll(out)
        d.secondsPerSlide = ((m["sps"] as? Number)?.toInt() ?: 4).coerceIn(2, 20)
        d.current = ((m["current"] as? Number)?.toInt() ?: 0).coerceIn(0, (out.size - 1).coerceAtLeast(0))
        d.selectedId = null
    }

    fun ensureLoaded(ctx: Context) {
        if (PrDeck.loaded) return
        PrDeck.loaded = true
        val f = autosaveFile(ctx)
        if (!f.exists()) return
        runCatching {
            val text = f.readText()
            decode(ctx, text)
            JSONObject(text).optString("file", "").takeIf { it.isNotBlank() && File(it).exists() }?.let { PrDeck.file = it }
        }
    }

    fun autosave(ctx: Context) {
        val text = runCatching { encode(includeFile = true) }.getOrNull() ?: return
        val f = autosaveFile(ctx)
        Thread { runCatching { CsStore.writeAtomic(f, text) } }.start()
    }

    fun listProjects(ctx: Context): List<File> =
        projectsDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Simpan dek (bila punya slide) sebelum diganti; @return nama berkas. */
    fun stashIfNeeded(ctx: Context): String? {
        if (PrDeck.slides.isEmpty()) return null
        val target = PrDeck.file?.let { File(it) } ?: AnStore.uniqueFile(projectsDir(ctx), PrDeck.name)
        return runCatching { CsStore.writeAtomic(target, encode()); target.nameWithoutExtension }.getOrNull()
    }
}

// ====================================================================== ekspor

/** Gambar satu slide pada waktu lokal [t] (ms sejak slide mulai) dengan animasi masuknya. */
internal object PrRender {
    fun entrance(s: PrSlide, w: Int, h: Int): Map<String, List<AnKey>> =
        AnPresets.entranceTracks(
            s.elements.filter { it.visible && s.anims[it.id] != null }.map { e ->
                AnPresets.EntranceSpec(e.id, s.anims[e.id] ?: AnPresets.NONE, e.x, e.y, e.w, e.h)
            }, w.toFloat(), h.toFloat()
        )

    fun drawSlide(ctx: Context, canvas: Canvas, s: PrSlide, tracks: Map<String, List<AnKey>>, t: Long, images: (CsElement) -> AnLayerImage?, paint: Paint) {
        val d = PrDeck.doc(s)
        AnDraw.drawDoc(ctx, canvas, d, { el -> tracks[el.id]?.let { AnMath.sample(it, t) } ?: AnState.IDENTITY }, images, paint, background = true)
    }

    /**
     * Komposisi transisi: slide lama [prev] (keadaan akhir) → slide baru pada progres [p] (0..1).
     * Kanvas dalam koordinat dek (w×h).
     */
    fun drawTransition(
        ctx: Context, canvas: Canvas, prev: PrSlide?, cur: PrSlide, curTracks: Map<String, List<AnKey>>, t: Long, p: Float,
        images: (CsElement) -> AnLayerImage?, paint: Paint
    ) {
        val w = PrDeck.w.toFloat()
        val h = PrDeck.h.toFloat()
        if (prev == null || p >= 1f || cur.transition == "none") {
            drawSlide(ctx, canvas, cur, curTracks, t, images, paint)
            return
        }
        val e = AnEase.apply(AnEase.IN_OUT, p)
        when (cur.transition) {
            "slide" -> {
                canvas.save()
                canvas.clipRect(0f, 0f, w, h)
                canvas.translate(-w * e, 0f)
                drawSlide(ctx, canvas, prev, emptyMap(), Long.MAX_VALUE, images, paint)
                canvas.translate(w, 0f)
                drawSlide(ctx, canvas, cur, curTracks, t, images, paint)
                canvas.restore()
            }
            else -> {
                drawSlide(ctx, canvas, prev, emptyMap(), Long.MAX_VALUE, images, paint)
                val lp = Paint().apply { alpha = (e * 255).roundToInt().coerceIn(0, 255) }
                val save = canvas.saveLayer(0f, 0f, w, h, lp)
                drawSlide(ctx, canvas, cur, curTracks, t, images, paint)
                canvas.restoreToCount(save)
            }
        }
    }

    /** PDF satu halaman per slide (sisi panjang 960 pt ≈ 13,3 inci seperti slide 16:9 standar). */
    fun writePdf(ctx: Context, slides: List<PrSlide>, file: File, cancelled: () -> Boolean, progress: (Int) -> Unit) {
        val s = 960f / max(PrDeck.w, PrDeck.h)
        val pw = (PrDeck.w * s).roundToInt()
        val ph = (PrDeck.h * s).roundToInt()
        val pdf = PdfDocument()
        try {
            for ((i, slide) in slides.withIndex()) {
                if (cancelled()) throw AnEncode.Cancelled()
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                page.canvas.scale(s, s)
                // Foto dirender pada resolusi kanvas slide (PDF tetap tajam saat diperbesar).
                CsPipeline.drawDoc(ctx, page.canvas, PrDeck.doc(slide), 1f, transparent = false)
                pdf.finishPage(page)
                progress(((i + 1) * 90 / slides.size.coerceAtLeast(1)))
            }
            FileOutputStream(file).use { pdf.writeTo(it) }
            progress(100)
        } catch (e: Throwable) {
            file.delete()
            throw e
        } finally {
            pdf.close()
        }
    }
}

/** MP4 slideshow: [secondsPerSlide] per slide, transisi di awal slide, animasi masuk per objek. */
internal class PrSlideshowSource(
    private val ctx: Context,
    private val slides: List<PrSlide>,
    secondsPerSlide: Int,
    override val width: Int,
    override val height: Int,
    private val images: Map<String, AnLayerImage>
) : AnFrameSource {
    override val fps: Int = 30
    private val slideMs = secondsPerSlide.coerceIn(1, 60) * 1000L
    override val frameCount: Int = AnTiming.frameCount(slideMs * slides.size.coerceAtLeast(1), fps)
    private val tracks = slides.map { PrRender.entrance(it, PrDeck.w, PrDeck.h) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawFrame(i: Int, canvas: Canvas) {
        val t = AnTiming.frameMs(i, fps)
        val idx = (t / slideMs).toInt().coerceIn(0, slides.size - 1)
        val local = t - idx * slideMs
        val save = canvas.save()
        canvas.scale(width / PrDeck.w.toFloat(), height / PrDeck.h.toFloat())
        val prev = if (idx > 0) slides[idx - 1] else null
        val p = (local.toFloat() / PrDeck.TRANSITION_MS).coerceIn(0f, 1f)
        PrRender.drawTransition(ctx, canvas, prev, slides[idx], tracks[idx], local, p, { el -> images[el.id] }, paint)
        canvas.restoreToCount(save)
    }
}
