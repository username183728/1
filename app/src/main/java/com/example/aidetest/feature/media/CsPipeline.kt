package com.example.aidetest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** Satu permintaan render layer foto (immutable; dibandingkan agar render ganda dilewati). */
internal data class CsJob(
    /** "el:<id>" untuk kanvas, "focus:<id>" untuk tampilan fokus. */
    val channel: String,
    /** Cache tahap dibagi per elemen (kanvas & fokus). */
    val cacheId: String,
    val path: String,
    val workSide: Int,
    val edit: PeEdit,
    val maskId: String?,
    val cut: CsCut,
    /** true = tanpa crop (bingkai penuh untuk alat Potong). */
    val cropMode: Boolean,
    /** true = setengah resolusi saat slider digeser (cepat), lalu render penuh saat dilepas. */
    val draft: Boolean
)

internal class CsRendered(
    val job: CsJob,
    /** Hasil akhir di ruang keluaran (setelah geometri, warna, detail). */
    val image: Bitmap,
    /** Sumber kerja sebelum heal/potong — dipakai tombol Bandingkan. */
    val src: Bitmap,
    /** Hasil punya area transparan (latar dihapus / sumber PNG beralpha). */
    val transparent: Boolean
)

/** Data kerja kuas mask (dibuat di thread kerja, diubah di thread UI oleh tampilan fokus). */
internal class CsMaskWork(val healed: Bitmap, val px: IntArray, val w: Int, val h: Int, val raw: ByteArray, var refined: ByteArray, var deco: IntArray?)

/**
 * Render foto per layer di SATU thread kerja (urut, hemat memori), non-destruktif:
 * sumber kerja → heal (ruang sumber) → potong latar (mask di ruang sumber, halus/geser/feather,
 * dekontaminasi) → geometri (orientasi, perspektif, luruskan, crop) → warna → detail.
 * Setiap tahap di-cache per elemen dengan kuncinya sendiri, jadi menggeser slider warna tidak
 * mengulang heal/potong/geometri. Permintaan yang menumpuk digabung: hanya yang terbaru dikerjakan.
 * Bitmap yang sudah diserahkan ke UI tidak pernah di-recycle manual (aman dari crash), cukup GC.
 */
internal object CsPipeline {
    private class Stages {
        var healSrc: Bitmap? = null
        var healKey: List<PeHeal>? = null
        var healed: Bitmap? = null
        var cutSrc: Bitmap? = null
        var cutKey: List<Any?>? = null
        var cut: Bitmap? = null
        var refined: ByteArray? = null
        var geomSrc: Bitmap? = null
        var geomKey: PeGeom? = null
        var base: Bitmap? = null
        var half: Bitmap? = null
        var maskOutKey: List<Any?>? = null
        var maskOut: ByteArray? = null
    }

    private val worker: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var appCtx: Context? = null

    // ---- hanya disentuh thread kerja
    private val srcCache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 4
    }
    private val stages = HashMap<String, Stages>()

    // ---- hanya disentuh thread UI
    private val results = HashMap<String, CsRendered>()
    private val running = HashSet<String>()
    private val queued = HashMap<String, Pair<CsJob, ((CsRendered?) -> Unit)?>>()

    /** Dipanggil (thread UI) setiap ada hasil baru untuk sebuah kanal. */
    var listener: ((String) -> Unit)? = null
    /** Dipanggil (thread UI) saat render gagal. */
    var errorListener: ((Throwable) -> Unit)? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun result(channel: String): CsRendered? = results[channel]

    fun isBusy(): Boolean = running.isNotEmpty()

    fun jobFor(el: CsElement, workSide: Int, channel: String = "el:${el.id}", cropMode: Boolean = false, draft: Boolean = false, edit: PeEdit = el.edit): CsJob? {
        val path = el.imagePath ?: return null
        return CsJob(channel, el.id, path, workSide, edit, el.maskId, el.cut, cropMode, draft)
    }

    /** Minta render. Hasil identik dengan yang terakhir langsung dikembalikan; saat sibuk, antrean diganti yang terbaru. */
    fun request(job: CsJob, onDone: ((CsRendered?) -> Unit)? = null) {
        val ch = job.channel
        if (!running.contains(ch)) {
            val cur = results[ch]
            if (cur != null && cur.job == job) { onDone?.invoke(cur); return }
            start(job, onDone)
            return
        }
        queued[ch] = job to onDone
    }

    private fun start(job: CsJob, onDone: ((CsRendered?) -> Unit)?) {
        val ch = job.channel
        running.add(ch)
        worker.execute {
            val r = runCatching { render(job) }
            if (r.exceptionOrNull() is OutOfMemoryError) dropCaches()
            main.post {
                running.remove(ch)
                val res = r.getOrNull()
                if (res != null) results[ch] = res
                val next = queued.remove(ch)
                if (next != null && next.first != job) {
                    start(next.first, next.second)
                } else {
                    onDone?.invoke(res)
                    next?.second?.invoke(res)
                }
                if (res != null) listener?.invoke(ch)
                r.exceptionOrNull()?.let { e -> errorListener?.invoke(e) }
            }
        }
    }

    /** Lepas hasil & cache elemen yang tidak lagi ada di dokumen (memori). */
    fun prune(keepIds: Set<String>) {
        val drop = results.keys.filter { ch -> ch.substringAfter(':') !in keepIds }
        drop.forEach { results.remove(it) }
        val keep = HashSet(keepIds)
        worker.execute { stages.keys.retainAll(keep) }
    }

    fun forget(channel: String) {
        results.remove(channel)
        queued.remove(channel)
    }

    /** Jalankan tugas berat (ekspor, analisis) di thread kerja yang sama — urut dengan render. */
    fun runOnWorker(task: () -> Unit) {
        worker.execute(task)
    }

    fun post(task: () -> Unit) {
        main.post(task)
    }

    private fun dropCaches() {
        stages.clear()
        srcCache.clear()
        System.gc()
    }

    // ------------------------------------------------------------------ tahap render (thread kerja)

    /** Dekode file dengan inSampleSize lalu perkecil tepat ke [maxSide] (tidak pernah memperbesar). */
    fun decodeSampled(path: String, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val b = BitmapFactory.decodeFile(path, opts) ?: return null
        val out = fitLongSide(b, maxSide)
        if (out !== b) b.recycle()
        return out
    }

    /** Ukuran file gambar tanpa mendekode piksel (proyek v2 belum menyimpan srcW/srcH). */
    fun boundsOf(path: String): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    private fun loadSource(path: String, side: Int): Bitmap? {
        val key = "$path@$side"
        srcCache[key]?.let { return it }
        val bmp = decodeSampled(path, side) ?: return null
        srcCache[key] = bmp
        return bmp
    }

    private fun healBitmap(src: Bitmap, heals: List<PeHeal>): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        PeHealing.applyAll(px, w, h, heals)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

    private fun healedFor(st: Stages, src: Bitmap, heals: List<PeHeal>): Bitmap {
        if (st.healSrc !== src || st.healKey != heals) {
            st.healed = if (heals.isEmpty()) src else healBitmap(src, heals)
            st.healKey = heals
            st.healSrc = src
            st.cutSrc = null
        }
        return st.healed ?: src
    }

    /** Mask mentah (ruang sumber, ukuran tersimpan) → ukuran [w]×[h] → halus/geser/feather. */
    private fun refinedMask(ctx: Context, maskId: String, w: Int, h: Int, cut: CsCut): ByteArray? {
        val stored = CsMasks.get(ctx, maskId) ?: return null
        val raw = if (stored.first == w && stored.second == h) stored.third else MaskOps.resize(stored.third, stored.first, stored.second, w, h)
        return MaskOps.refine(raw, w, h, cut.smooth / 100f, cut.shift, cut.feather)
    }

    private fun composeCutBitmap(src: Bitmap, refined: ByteArray, cut: CsCut, decoRadius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val deco = if (cut.decontam) MaskOps.decontaminate(px, refined, w, h, decoRadius) else null
        composeCut(px, deco, refined, px, 0, w * h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

    /** Mask (ruang sumber sw×sh) → ruang keluaran (geometri yang sama), ukuran w×h. */
    private fun maskToOutput(mask: ByteArray, sw: Int, sh: Int, g: PeGeom, w: Int, h: Int): ByteArray {
        val gray = IntArray(sw * sh) { val v = mask[it].toInt() and 0xFF; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val mb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        mb.setPixels(gray, 0, sw, 0, 0, sw, sh)
        val gb = PeGeometry.apply(mb, g, true)
        if (gb !== mb) mb.recycle()
        val sb = if (gb.width != w || gb.height != h) Bitmap.createScaledBitmap(gb, w, h, true) else gb
        if (sb !== gb) gb.recycle()
        val px = IntArray(w * h)
        sb.getPixels(px, 0, w, 0, 0, w, h)
        // Bitmap sementara milik fungsi ini saja (tidak pernah dipegang UI) — aman dilepas.
        sb.recycle()
        return ByteArray(w * h) { ((px[it] shr 16) and 0xFF).toByte() }
    }

    private fun render(job: CsJob): CsRendered {
        val ctx = appCtx ?: error("Studio belum siap")
        val src = loadSource(job.path, job.workSide) ?: error("Gambar tidak bisa dibaca")
        val st = stages.getOrPut(job.cacheId) { Stages() }
        // 1) heal (ruang sumber)
        val healed = healedFor(st, src, job.edit.heals)
        // 2) mask + potong latar
        val maskId = job.maskId
        val needMask = maskId != null && (job.cut.on || job.edit.adjust.bgBlur > 0f)
        val cutKey: List<Any?>? = if (needMask) listOf(maskId, job.cut.on, job.cut.smooth, job.cut.shift, job.cut.feather, job.cut.decontam) else null
        if (st.cutSrc !== healed || st.cutKey != cutKey) {
            var refined: ByteArray? = null
            var cut: Bitmap = healed
            if (needMask && maskId != null) {
                refined = refinedMask(ctx, maskId, healed.width, healed.height, job.cut)
                if (job.cut.on && refined != null) cut = composeCutBitmap(healed, refined, job.cut, 4 + job.cut.feather)
            }
            st.refined = refined
            st.cut = cut
            st.cutKey = cutKey
            st.cutSrc = healed
            st.geomSrc = null
        }
        val cutBmp = st.cut ?: healed
        // 3) geometri
        val g = if (job.cropMode) job.edit.geom.copy(cl = 0f, ct = 0f, cr = 1f, cb = 1f) else job.edit.geom
        val cachedBase = st.base
        val base: Bitmap = if (cachedBase != null && st.geomSrc === cutBmp && st.geomKey == g) cachedBase else {
            PeGeometry.apply(cutBmp, g, true).also {
                st.base = it
                st.geomKey = g
                st.geomSrc = cutBmp
                st.half = null
                st.maskOutKey = null
            }
        }
        val work = if (job.draft) {
            st.half ?: Bitmap.createScaledBitmap(base, max(1, base.width / 2), max(1, base.height / 2), true).also { st.half = it }
        } else base
        val transparent = (job.cut.on && st.refined != null) || src.hasAlpha()
        val adj = job.edit.adjust
        if (adj.colorIsIdentity() && adj.detailIsIdentity()) return CsRendered(job, work, src, transparent)
        // 4) warna + 5) detail
        val w = work.width
        val h = work.height
        val px = IntArray(w * h)
        work.getPixels(px, 0, w, 0, 0, w, h)
        PeColor.apply(px, adj)
        val refined = st.refined
        val mask = if (adj.bgBlur > 0f && refined != null) {
            val key = listOf(System.identityHashCode(refined), g, w, h)
            if (st.maskOutKey != key) {
                st.maskOut = maskToOutput(refined, healed.width, healed.height, g, w, h)
                st.maskOutKey = key
            }
            st.maskOut
        } else null
        PeDetail.apply(px, w, h, adj, mask)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return CsRendered(job, out, src, transparent)
    }

    // ------------------------------------------------------------------ data untuk alat foto

    /** Sumber kerja setelah heal (untuk AI segmentasi / warna latar), dikirim ke thread UI. */
    fun healedSource(el: CsElement, workSide: Int, onReady: (Bitmap?) -> Unit) {
        val path = el.imagePath ?: run { onReady(null); return }
        val heals = el.edit.heals
        val id = el.id
        worker.execute {
            val r = runCatching {
                val src = loadSource(path, workSide) ?: error("Gambar tidak bisa dibaca")
                healedFor(stages.getOrPut(id) { Stages() }, src, heals)
            }
            main.post { onReady(r.getOrNull()) }
        }
    }

    /** Siapkan data kuas mask: piksel setelah heal + mask mentah (salinan, boleh diubah) + mask halus. */
    fun prepareMaskWork(el: CsElement, workSide: Int, onReady: (CsMaskWork?) -> Unit) {
        val path = el.imagePath ?: run { onReady(null); return }
        val heals = el.edit.heals
        val maskId = el.maskId
        val cut = el.cut
        val id = el.id
        worker.execute {
            val r = runCatching {
                val ctx = appCtx ?: error("Studio belum siap")
                val src = loadSource(path, workSide) ?: error("Gambar tidak bisa dibaca")
                val healed = healedFor(stages.getOrPut(id) { Stages() }, src, heals)
                val w = healed.width
                val h = healed.height
                val px = IntArray(w * h)
                healed.getPixels(px, 0, w, 0, 0, w, h)
                val stored = maskId?.let { CsMasks.get(ctx, it) }
                val raw = when {
                    stored == null -> ByteArray(w * h) { 255.toByte() }
                    stored.first == w && stored.second == h -> stored.third.copyOf()
                    else -> MaskOps.resize(stored.third, stored.first, stored.second, w, h)
                }
                val refined = MaskOps.refine(raw, w, h, cut.smooth / 100f, cut.shift, cut.feather)
                val deco = if (cut.decontam) MaskOps.decontaminate(px, refined, w, h, 4 + cut.feather) else null
                CsMaskWork(healed, px, w, h, raw, refined, deco)
            }
            main.post { onReady(r.getOrNull()) }
        }
    }

    /** Haluskan ulang mask kuas setelah sapuan (thread kerja), hasil ke thread UI. */
    fun refineWork(work: CsMaskWork, raw: ByteArray, cut: CsCut, onReady: (ByteArray, IntArray?) -> Unit) {
        worker.execute {
            val r = runCatching {
                val refined = MaskOps.refine(raw, work.w, work.h, cut.smooth / 100f, cut.shift, cut.feather)
                val deco = if (cut.decontam) MaskOps.decontaminate(work.px, refined, work.w, work.h, 4 + cut.feather) else null
                refined to deco
            }
            main.post { r.getOrNull()?.let { (m, d) -> onReady(m, d) } }
        }
    }

    /** Cari sumber tekstur untuk satu sapuan heal (titik di piksel sumber kerja). */
    fun findHeal(el: CsElement, edit: PeEdit, workSide: Int, pts: FloatArray, radius: Float, onDone: (PeHeal?) -> Unit) {
        val path = el.imagePath ?: run { onDone(null); return }
        val id = el.id
        worker.execute {
            val r = runCatching {
                val src = loadSource(path, workSide) ?: error("Gambar tidak bisa dibaca")
                val healed = healedFor(stages.getOrPut(id) { Stages() }, src, edit.heals)
                val w = healed.width
                val h = healed.height
                val px = IntArray(w * h)
                healed.getPixels(px, 0, w, 0, 0, w, h)
                val (ox, oy) = PeHealing.findOffset(px, w, h, pts, radius)
                val long = max(w, h).toFloat()
                PeHeal(pts.mapIndexed { i, v -> if (i % 2 == 0) v / w else v / h }, radius / long, ox / long, oy / long)
            }
            main.post { onDone(r.getOrNull()) }
        }
    }

    /** Kotak subjek (mask halus > ambang) di ruang sumber kerja: l,t,r,b + ukuran sumber, atau null. */
    fun subjectBounds(el: CsElement, workSide: Int, onDone: (IntArray?, Int, Int) -> Unit) {
        val path = el.imagePath ?: run { onDone(null, 0, 0); return }
        val maskId = el.maskId ?: run { onDone(null, 0, 0); return }
        val heals = el.edit.heals
        val cut = el.cut
        val id = el.id
        worker.execute {
            val r = runCatching {
                val ctx = appCtx ?: error("Studio belum siap")
                val src = loadSource(path, workSide) ?: error("Gambar tidak bisa dibaca")
                val healed = healedFor(stages.getOrPut(id) { Stages() }, src, heals)
                val refined = refinedMask(ctx, maskId, healed.width, healed.height, cut) ?: error("Mask tidak ditemukan")
                Triple(MaskOps.bounds(refined, healed.width, healed.height, 10), healed.width, healed.height)
            }
            main.post {
                val v = r.getOrNull()
                if (v == null) onDone(null, 0, 0) else onDone(v.first, v.second, v.third)
            }
        }
    }

    // ------------------------------------------------------------------ ekspor (thread kerja)

    /**
     * Render layer foto resolusi tinggi untuk ekspor; sumber didekode ulang dari salinan privat.
     * @return bitmap + apakah hasilnya punya area transparan
     */
    fun renderForExport(el: CsElement, side: Int): Pair<Bitmap, Boolean>? {
        val ctx = appCtx ?: return null
        val path = el.imagePath ?: return null
        // Semua bitmap di fungsi ini baru dibuat untuk ekspor (bukan cache/pratinjau UI), jadi
        // tahap antara dilepas segera agar puncak memori ekspor resolusi tinggi tetap rendah.
        var src = decodeSampled(path, side) ?: return null
        val srcAlpha = src.hasAlpha()
        val edit = el.edit
        if (edit.heals.isNotEmpty()) {
            val healed = healBitmap(src, edit.heals)
            src.recycle()
            src = healed
        }
        val sw = src.width
        val sh = src.height
        var refined: ByteArray? = null
        var decoScale = 1f
        val maskId = el.maskId
        if (maskId != null && (el.cut.on || edit.adjust.bgBlur > 0f)) {
            // Haluskan pada resolusi mask (sama dengan pratinjau), lalu perbesar ke resolusi ekspor.
            val stored = CsMasks.get(ctx, maskId)
            if (stored != null) {
                val r = MaskOps.refine(stored.third, stored.first, stored.second, el.cut.smooth / 100f, el.cut.shift, el.cut.feather)
                refined = MaskOps.resize(r, stored.first, stored.second, sw, sh)
                decoScale = sw.toFloat() / stored.first
            }
        }
        val cutMask = refined
        val cutApplied = el.cut.on && cutMask != null
        val cutBmp = if (cutApplied && cutMask != null) composeCutBitmap(src, cutMask, el.cut, ((4 + el.cut.feather) * decoScale).roundToInt().coerceAtLeast(2)) else src
        if (cutBmp !== src) src.recycle()
        val geo = PeGeometry.apply(cutBmp, edit.geom, true)
        if (geo !== cutBmp) cutBmp.recycle()
        val transparent = cutApplied || srcAlpha
        val adj = edit.adjust
        if (adj.colorIsIdentity() && adj.detailIsIdentity()) return geo to transparent
        val w = geo.width
        val h = geo.height
        val px = IntArray(w * h)
        geo.getPixels(px, 0, w, 0, 0, w, h)
        geo.recycle()
        PeColor.apply(px, adj)
        val mask = if (adj.bgBlur > 0f && cutMask != null) maskToOutput(cutMask, sw, sh, edit.geom, w, h) else null
        PeDetail.apply(px, w, h, adj, mask)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) } to transparent
    }

    /** Sisi sumber yang didekode untuk sebuah layer pada skala ekspor [scale] (px ekspor per px kanvas). */
    fun exportSide(el: CsElement, scale: Float): Int {
        val (ow, oh) = CsMath.outputSize(el.srcW, el.srcH, el.edit.geom.rot90, el.edit.geom.cl, el.edit.geom.ct, el.edit.geom.cr, el.edit.geom.cb)
        return CsMath.exportSourceSide(el.w, el.h, scale, ow, oh, max(el.srcW, el.srcH).coerceAtLeast(256))
    }

    /**
     * Gambar seluruh dokumen ke [canvas] berskala (ekspor PNG/JPG/WebP/PDF).
     * [releaseLayers] = lepas bitmap foto per layer begitu selesai digambar. Hanya untuk kanvas
     * bitmap (software, langsung menggambar); kanvas PDF merekam dan baru membaca piksel saat ditulis.
     */
    fun drawDoc(ctx: Context, canvas: Canvas, doc: CsDocData, scale: Float, transparent: Boolean, releaseLayers: Boolean = false) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (!transparent) CsRender.drawBackground(canvas, doc.bg, doc.w.toFloat(), doc.h.toFloat(), paint)
        for (el in doc.elements) {
            if (!el.visible) continue
            if (el.type == CsType.IMAGE) {
                val rendered = try {
                    renderForExport(el, exportSide(el, scale))
                } catch (_: OutOfMemoryError) {
                    dropCaches()
                    renderForExport(el, 1600)
                }
                val shadow = if (rendered != null && el.shadow && rendered.second) CsRender.makeShadow(rendered.first, el.shadowBlur, el.w, 512) else null
                CsRender.draw(ctx, canvas, el, paint, rendered?.first, shadow)
                if (releaseLayers) {
                    shadow?.bitmap?.recycle()
                    rendered?.first?.recycle()
                }
            } else {
                CsRender.draw(ctx, canvas, el, paint, null, null)
            }
        }
    }

    /** Bitmap ekspor dokumen ukuran [outW]×[outH]; [flatten] = isi putih di bawah (JPG). */
    fun renderDoc(ctx: Context, doc: CsDocData, outW: Int, outH: Int, transparent: Boolean, flatten: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        try {
            val cv = Canvas(bmp)
            if (flatten) cv.drawColor(Color.WHITE)
            val sx = outW / doc.w.toFloat()
            val sy = outH / doc.h.toFloat()
            cv.scale(sx, sy)
            drawDoc(ctx, cv, doc, max(sx, sy), transparent, releaseLayers = true)
        } catch (e: Throwable) {
            // Gagal di tengah (mis. memori habis): lepas kanvas besar sebelum cadangan ukuran kecil dicoba.
            bmp.recycle()
            throw e
        }
        return bmp
    }

    /** PDF satu halaman seukuran kanvas: teks & bentuk tetap vektor, foto resolusi penuh. */
    fun writePdf(ctx: Context, doc: CsDocData, file: File) {
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(doc.w, doc.h, 1).create())
            drawDoc(ctx, page.canvas, doc, 1f, transparent = false)
            pdf.finishPage(page)
            FileOutputStream(file).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    /**
     * Potong margin transparan (opsi "Pangkas ke isi"). Dipindai per pita 64 baris agar tidak
     * butuh larik piksel seukuran gambar. Mengembalikan [bmp] sendiri bila tidak ada yang dipotong.
     */
    fun trimTransparent(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val band = 64
        val px = IntArray(w * minOf(band, h))
        var b: IntArray? = null
        var y = 0
        while (y < h) {
            val rows = minOf(band, h - y)
            bmp.getPixels(px, 0, w, 0, y, w, rows)
            CsMath.alphaBounds(px, w, rows, 8)?.let { r -> b = CsMath.unionBounds(b, intArrayOf(r[0], r[1] + y, r[2], r[3] + y)) }
            y += rows
        }
        val bounds = b ?: return bmp
        val p = CsMath.padRect(bounds, w, h, (max(w, h) * 0.01f).roundToInt())
        if (p[0] == 0 && p[1] == 0 && p[2] == w && p[3] == h) return bmp
        return Bitmap.createBitmap(bmp, p[0], p[1], p[2] - p[0], p[3] - p[1])
    }

    /**
     * Bitmap layer untuk SVG (thread kerja). [previews] = hasil pratinjau yang diambil di thread UI
     * sebelum tugas dimulai (peta hasil hanya boleh dibaca thread UI).
     */
    fun svgRaster(ctx: Context, el: CsElement, previews: Map<String, Bitmap>): Bitmap? = when (el.type) {
        CsType.IMAGE -> previews[el.id] ?: renderForExport(el, 1600)?.first
        CsType.STICKER -> CsRender.stickerBitmap(ctx, el, 1024)
        else -> null
    }

    /** Salinan peta pratinjau foto (thread UI) untuk dipakai tugas ekspor SVG. */
    fun previewSnapshot(elements: List<CsElement>): Map<String, Bitmap> {
        val out = HashMap<String, Bitmap>()
        val byId = elements.associateBy { it.id }
        for ((ch, r) in results) {
            if (!ch.startsWith("el:") || r.job.draft || r.job.cropMode) continue
            val el = byId[ch.removePrefix("el:")] ?: continue
            // Hanya pratinjau yang sesuai edit TERKINI; selain itu dirender ulang dari sumber (hindari SVG basi).
            if (r.job.edit != el.edit || r.job.maskId != el.maskId || r.job.cut != el.cut || r.job.path != el.imagePath) continue
            out[el.id] = r.image
        }
        return out
    }
}
