package com.example.aidetest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.widget.LinearLayout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Animation Studio — bagian suite Foto Studio: menganimasikan desain/foto Foto Studio.
 *  - AnimCore.kt   : logika murni (keyframe, easing, preset, YUV, GIF) — teruji JVM
 *  - AnimRender.kt : render beranimasi, kanvas pratinjau, encoder MP4/GIF, publikasi
 *  - AnimStudio.kt : (file ini) state proyek, penyimpanan JSON (files/design/anim), serah-terima antar modul
 *  - AnimStudioUi.kt : editor layar penuh (timeline, keyframe, preset, ekspor)
 */

internal const val ANIM_STUDIO_ID = "animationstudio"
internal const val ANIM_STUDIO_PAGE = "Animasi"
internal const val PRESENT_STUDIO_ID = "presentationstudio"
internal const val PRESENT_STUDIO_PAGE = "Presentation Studio"

/** Animasi kini mode di dalam Foto Studio (tab "Animasi"). */
internal fun MainActivity.animationStudioTool() = creativeStudioTool(CsToolIds.Entry.ANIM)

/** Ekspor yang sedang berjalan (bertahan walau halaman dibangun ulang, mis. ganti tema). */
internal class AnJob(val label: String) {
    @Volatile var progress = 0
    val cancel = AtomicBoolean(false)
    @Volatile var listener: (() -> Unit)? = null
}

internal class AnSnap(val tracks: Map<String, List<AnKey>>, val durationMs: Long, val opts: Map<String, AnLayerOpt> = emptyMap())

/** Proyek animasi aktif (bertahan antar pembangunan ulang halaman). */
internal object AnProject {
    const val FPS = 30
    var doc: CsDocData? = null
    val tracks = LinkedHashMap<String, List<AnKey>>()
    /** Opsi per layer (rentang aktif, sembunyi/kunci, animasi teks); tidak ada = bawaan. */
    val opts = LinkedHashMap<String, AnLayerOpt>()
    var durationMs = 5_000L
    var name = "Animasi 1"
    var file: String? = null
    var playheadMs = 0L
    var selectedId: String? = null
    var loaded = false
    val undo = ArrayList<AnSnap>()
    val redo = ArrayList<AnSnap>()
    // ekspor
    var exportFormat = "mp4"
    /** Sisi pendek video: 480 | 720 | 1080 */
    var videoShort = 720
    /** Sisi panjang GIF: 320 | 480 | 640 */
    var gifLong = 480
    var gifFps = 15
    var gifTransparent = false
    var lastResult: AnResult? = null
    var job: AnJob? = null

    fun keyCount(): Int = tracks.values.sumOf { it.size }

    fun snapshot(): AnSnap = AnSnap(LinkedHashMap(tracks), durationMs, LinkedHashMap(opts))

    fun restore(s: AnSnap) {
        tracks.clear()
        tracks.putAll(s.tracks)
        opts.clear()
        opts.putAll(s.opts)
        durationMs = s.durationMs
        playheadMs = playheadMs.coerceIn(0L, durationMs)
    }

    /** Ganti dokumen; track untuk layer yang tidak ada dibuang. */
    fun setDoc(d: CsDocData, newName: String, keepTracks: Map<String, List<AnKey>> = emptyMap(), duration: Long = 5_000L, path: String? = null, keepOpts: Map<String, AnLayerOpt> = emptyMap()) {
        doc = d
        name = newName
        file = path
        tracks.clear()
        val ids = d.elements.map { it.id }.toSet()
        for ((id, k) in keepTracks) if (id in ids && k.isNotEmpty()) tracks[id] = k
        opts.clear()
        for ((id, o) in keepOpts) if (id in ids && !o.isDefault()) opts[id] = o
        durationMs = AnMath.clampDuration(duration)
        playheadMs = 0L
        selectedId = null
        undo.clear()
        redo.clear()
        loaded = true
    }

    fun opt(id: String): AnLayerOpt = opts[id] ?: AnLayerOpt.DEFAULT

    fun setOpt(id: String, o: AnLayerOpt) { if (o.isDefault()) opts.remove(id) else opts[id] = o }

    fun element(id: String?): CsElement? = id?.let { i -> doc?.elements?.firstOrNull { it.id == i } }
}

internal object AnStore {
    fun dir(ctx: Context): File = File(CsStore.dir(ctx), "anim").apply { mkdirs() }
    fun projectsDir(ctx: Context): File = File(dir(ctx), "projects").apply { mkdirs() }
    fun autosaveFile(ctx: Context): File = File(dir(ctx), "autosave.json")

    fun toJsonText(p: AnProject): String? {
        val d = p.doc ?: return null
        val now = System.currentTimeMillis()
        val tree = linkedMapOf<String, Any?>(
            "v" to 1,
            "app" to ANIM_STUDIO_ID,
            "name" to p.name,
            "duration" to p.durationMs,
            "fps" to AnProject.FPS,
            "savedAt" to now,
            "doc" to CsCodec.encodeDoc(d, now),
            "tracks" to AnCodec.encodeTracks(p.tracks),
            // Opsional (2.52+): proyek lama tanpa "layers" dimuat dengan rentang penuh.
            "layers" to AnLayerCodec.encode(p.opts)
        )
        return treeToJson(tree).toString()
    }

    class Loaded(val name: String, val doc: CsDocData, val tracks: Map<String, List<AnKey>>, val durationMs: Long, val opts: Map<String, AnLayerOpt> = emptyMap())

    fun parse(ctx: Context, text: String): Loaded {
        val m = jsonToTree(JSONObject(text))
        val img = CsStore.imgDir(ctx)
        @Suppress("UNCHECKED_CAST")
        val docMap = m["doc"] as? Map<String, Any?> ?: error("Berkas bukan proyek animasi")
        val d = CsCodec.decodeDoc(docMap) { n -> File(img, n).absolutePath }
        @Suppress("UNCHECKED_CAST")
        val tracks = AnCodec.decodeTracks(m["tracks"] as? Map<String, Any?>)
        val dur = (m["duration"] as? Number)?.toLong() ?: 5_000L
        @Suppress("UNCHECKED_CAST")
        val opts = AnLayerCodec.decode(m["layers"] as? Map<String, Any?>)
        return Loaded((m["name"] as? String)?.ifBlank { null } ?: d.name, d, tracks, dur, opts)
    }

    fun load(ctx: Context, f: File): Boolean = runCatching {
        val l = parse(ctx, f.readText())
        AnProject.setDoc(l.doc, l.name, l.tracks, l.durationMs, if (f == autosaveFile(ctx)) null else f.absolutePath, l.opts)
        true
    }.getOrDefault(false)

    /** Muat simpanan otomatis sekali per proses. */
    fun ensureLoaded(ctx: Context) {
        if (AnProject.loaded) return
        AnProject.loaded = true
        val f = autosaveFile(ctx)
        if (!f.exists()) return
        runCatching {
            val l = parse(ctx, f.readText())
            AnProject.setDoc(l.doc, l.name, l.tracks, l.durationMs, null, l.opts)
            // Path proyek asal disimpan terpisah agar Simpan menimpa berkas yang benar.
            val o = JSONObject(f.readText())
            o.optString("file", "").takeIf { it.isNotBlank() && File(it).exists() }?.let { AnProject.file = it }
        }
    }

    fun autosave(ctx: Context) {
        val text = toJsonText(AnProject) ?: return
        val withFile = runCatching { JSONObject(text).put("file", AnProject.file ?: "").toString() }.getOrDefault(text)
        val f = autosaveFile(ctx)
        Thread { runCatching { CsStore.writeAtomic(f, withFile) } }.start()
    }

    fun listProjects(ctx: Context): List<File> =
        projectsDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, CsStore.safeName(name) + ".json")
        var i = 2
        while (f.exists()) { f = File(dir, CsStore.safeName(name) + " $i.json"); i++ }
        return f
    }

    /** Simpan proyek saat ini ke [file] (atau berkas baru). @return berkas tujuan */
    fun save(ctx: Context, file: File?): File? {
        val text = toJsonText(AnProject) ?: return null
        val target = file ?: uniqueFile(projectsDir(ctx), AnProject.name)
        CsStore.writeAtomic(target, text)
        AnProject.file = target.absolutePath
        return target
    }

    /** Animasi yang akan diganti disimpan dulu bila punya keyframe (agar tidak hilang). */
    fun stashIfNeeded(ctx: Context): String? {
        if (AnProject.doc == null || (AnProject.keyCount() == 0 && AnProject.opts.isEmpty())) return null
        val file = AnProject.file?.let { File(it) }
        return runCatching { save(ctx, file)?.nameWithoutExtension }.getOrNull()
    }
}

/** Dokumen Foto Studio saat ini: sesi aktif, atau simpanan otomatisnya. */
internal fun currentStudioDoc(ctx: Context): CsDocData? {
    if (CsProject.initialized) return if (CsProject.elements.isNotEmpty()) CsProject.doc() else null
    val f = CsStore.autosaveFile(ctx)
    if (!f.exists()) return null
    return runCatching {
        val img = CsStore.imgDir(ctx)
        CsCodec.decodeDoc(jsonToTree(JSONObject(f.readText()))) { n -> File(img, n).absolutePath }
    }.getOrNull()?.takeIf { it.elements.isNotEmpty() }
}

internal fun loadStudioProject(ctx: Context, f: File): CsDocData? = runCatching {
    val img = CsStore.imgDir(ctx)
    CsCodec.decodeDoc(jsonToTree(JSONObject(f.readText()))) { n -> File(img, n).absolutePath }
}.getOrNull()

/** Salinan dokumen dengan id layer baru (dokumen yang sama bisa dipakai di beberapa slide). */
internal fun CsDocData.withFreshIds(): CsDocData =
    CsDocData(name, w, h, bg, elements.map { it.copy(id = UUID.randomUUID().toString()) })

/** Salin foto pilihan ke folder gambar Studio (≤ 3072 px, orientasi EXIF) → dokumen satu layer foto. */
internal fun MainActivity.importPhotoAsDoc(uri: Uri, maxCanvas: Int = 1920): CsDocData {
    val decoded = decodeOrientedBitmap(uri, CS_SOURCE_MAX)
    val bmp = fitLongSide(decoded, CS_SOURCE_MAX)
    if (bmp !== decoded) decoded.recycle()
    try {
        val alpha = bmp.hasAlpha()
        val file = File(CsStore.imgDir(this), "${UUID.randomUUID()}.${if (alpha) "png" else "jpg"}")
        FileOutputStream(file).use { out ->
            if (!bmp.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, out)) error("Gagal menyalin foto")
        }
        val (cw, ch) = AnTiming.fitEven(bmp.width, bmp.height, maxCanvas, 2)
        val name = runCatching { displayNameFor(uri) }.getOrDefault("Foto").substringBeforeLast('.').take(40).ifBlank { "Foto" }
        val el = CsElement(
            type = CsType.IMAGE, x = 0f, y = 0f, w = cw.toFloat(), h = ch.toFloat(),
            imagePath = file.absolutePath, srcW = bmp.width, srcH = bmp.height, fitCanvas = true, name = "Foto"
        )
        return CsDocData(name, cw, ch, CsBackground(if (alpha) "transparent" else "solid", Color.WHITE), listOf(el))
    } finally {
        bmp.recycle()
    }
}

// ====================================================================== serah-terima antar modul ("Kirim ke")

internal object StudioHandoff {
    /** Buka dokumen di Animation Studio (animasi lama disimpan dulu bila punya keyframe). */
    fun toAnimation(a: MainActivity, doc: CsDocData, name: String) {
        AnStore.ensureLoaded(a)
        val stashed = AnStore.stashIfNeeded(a)
        AnProject.setDoc(doc, name)
        AnStore.autosave(a)
        if (stashed != null) a.toast("Animasi sebelumnya disimpan: $stashed")
        a.openTool(ANIM_STUDIO_ID)
    }

    /** Tambah dokumen sebagai slide baru di Presentation Studio lalu buka. */
    fun toPresentation(a: MainActivity, doc: CsDocData) {
        PrStore.ensureLoaded(a)
        val slide = PrDeck.appendDoc(doc.withFreshIds())
        PrDeck.current = PrDeck.slides.indexOf(slide).coerceAtLeast(0)
        PrStore.autosave(a)
        a.toast("Ditambahkan sebagai slide ${PrDeck.slides.size}")
        a.openTool(PRESENT_STUDIO_ID)
    }

    /**
     * Tambah berkas (PNG/MP4 di folder privat) sebagai klip di akhir timeline Video Editor dan simpan
     * proyeknya (prefs). Probe berjalan di thread kerja.
     */
    fun toVideoEditor(a: MainActivity, file: File, name: String, isImage: Boolean, openAfter: Boolean = true) {
        Thread {
            val uri = Uri.fromFile(file)
            val probe = runCatching { if (isImage) VeImport.probeImage(a, uri, name) else VeImport.probeVideo(a, uri, name) }.getOrNull()
            a.runOnUiThread {
                if (probe == null) { a.toast("Berkas tidak bisa dibaca Video Editor"); return@runOnUiThread }
                val p = VeProject
                if (p.exporting || p.activeRender != null) { a.toast("Video Editor sedang mengekspor — coba lagi setelah selesai"); return@runOnUiThread }
                if (!p.loaded) {
                    p.loaded = true
                    a.prefs.getString(VE_PREFS_KEY, null)?.let { saved -> runCatching { p.fromPrefs(saved) } }
                }
                val m = probe.media
                probe.poster?.let { p.thumbs[m.uri.toString()] = it }
                if (p.library.none { it.uri == m.uri }) p.library.add(m)
                val clip = VeClip(
                    uri = m.uri, name = m.name, isImage = m.isImage, sourceDurationMs = m.durationMs,
                    videoWidth = m.width, videoHeight = m.height, sourceRotation = m.rotation
                )
                p.clips.add(clip)
                p.selKind = "clip"
                p.selId = clip.id
                p.playheadMs = p.clipStart(clip)
                runCatching { a.prefs.edit().putString(VE_PREFS_KEY, p.toPrefs()).apply() }
                a.toast("Ditambahkan ke Video Editor sebagai klip ${p.clips.size}")
                if (openAfter) a.openTool("videoeditor")
            }
        }.start()
    }

    /** Render desain Foto Studio ke PNG resolusi kanvas (transparansi dipertahankan) lalu kirim ke Video Editor. */
    fun designToVideoEditor(a: MainActivity, doc: CsDocData, onBusy: (Boolean) -> Unit) {
        onBusy(true)
        val ctx = a.applicationContext
        CsPipeline.init(a)
        CsPipeline.runOnWorker {
            val r = runCatching {
                val transparent = doc.bg.mode == "transparent"
                val bmp = try {
                    CsPipeline.renderDoc(ctx, doc, doc.w, doc.h, transparent, flatten = false)
                } catch (_: OutOfMemoryError) {
                    System.gc()
                    CsPipeline.renderDoc(ctx, doc, doc.w / 2, doc.h / 2, transparent, flatten = false)
                }
                val f = File(AnEncode.veMediaDir(ctx), "studio_${CsStore.safeName(doc.name)}_${System.currentTimeMillis()}.png")
                try {
                    FileOutputStream(f).use { out -> if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) error("Gagal menulis PNG") }
                } finally {
                    bmp.recycle()
                }
                f
            }
            CsPipeline.post {
                onBusy(false)
                r.onSuccess { f -> toVideoEditor(a, f, doc.name, isImage = true) }
                    .onFailure { e -> a.toast("Gagal merender desain: ${if (e is OutOfMemoryError) "memori tidak cukup" else e.message ?: e.javaClass.simpleName}") }
            }
        }
    }
}
