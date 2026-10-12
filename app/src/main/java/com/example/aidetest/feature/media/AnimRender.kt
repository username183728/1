package com.example.aidetest

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.view.MotionEvent
import android.view.View
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Render & ekspor bersama Animation Studio dan Presentation Studio:
 *  - AnDraw: gambar dokumen Studio Kreatif (CsDocData) dengan keadaan animasi per layer
 *  - AnPreviewImages: bitmap layer foto untuk pratinjau (dirender di thread kerja CsPipeline)
 *  - AnStageView: kanvas pratinjau + pilih/seret layer
 *  - AnEncode: MP4 H.264 (MediaCodec input Image YUV420Flexible + MediaMuxer) dan GIF89a (AnGifEncoder)
 *  - AnShare: publikasi ke penyimpanan publik (VePublish) dan bagikan
 */

/** Bitmap layer foto siap gambar (+ bayangan berbentuk subjek bila transparan). */
internal class AnLayerImage(val bitmap: Bitmap, val shadow: CsShadow?)

internal object AnDraw {
    /** Salinan layer setelah offset animasi diterapkan (skala diterapkan lewat kanvas). */
    fun animated(el: CsElement, s: AnState): CsElement =
        if (s.dx == 0f && s.dy == 0f && s.rot == 0f && s.opacity == 1f) el
        else el.copy(x = el.x + s.dx, y = el.y + s.dy, rotation = el.rotation + s.rot, opacity = el.opacity * s.opacity)

    /** Gambar dokumen dalam koordinat kanvas dokumen. [background] = gambar latar kanvas. */
    fun drawDoc(
        ctx: Context,
        canvas: Canvas,
        doc: CsDocData,
        state: (CsElement) -> AnState,
        image: (CsElement) -> AnLayerImage?,
        paint: Paint,
        background: Boolean
    ) {
        if (background) CsRender.drawBackground(canvas, doc.bg, doc.w.toFloat(), doc.h.toFloat(), paint)
        for (el in doc.elements) {
            if (!el.visible) continue
            val s = state(el)
            if (s.opacity <= 0.002f || s.scale <= 0.002f) continue
            val e = animated(el, s)
            val save = canvas.save()
            if (s.scale != 1f) canvas.scale(s.scale, s.scale, e.x + e.w / 2f, e.y + e.h / 2f)
            val img = if (el.type == CsType.IMAGE) image(el) else null
            CsRender.draw(ctx, canvas, e, paint, img?.bitmap, img?.shadow)
            canvas.restoreToCount(save)
        }
    }

    /**
     * Render layer foto resolusi ekspor (thread pemanggil, bukan UI). [scale] = px keluaran per px kanvas.
     * [extraZoom] = skala animasi terbesar (agar zoom/pop tetap tajam).
     */
    fun exportImages(doc: CsDocData, scale: Float, extraZoom: Float = 1f, cap: Int = 2048): Map<String, AnLayerImage> {
        val out = HashMap<String, AnLayerImage>()
        for (el in doc.elements) {
            if (!el.visible || el.type != CsType.IMAGE) continue
            val side = min(cap, CsPipeline.exportSide(el, scale * extraZoom.coerceIn(1f, 2f)))
            val r = try {
                CsPipeline.renderForExport(el, side)
            } catch (_: OutOfMemoryError) {
                System.gc()
                CsPipeline.renderForExport(el, min(side, 1024))
            } ?: continue
            val shadow = if (el.shadow && r.second) runCatching { CsRender.makeShadow(r.first, el.shadowBlur, el.w, 512) }.getOrNull() else null
            out[el.id] = AnLayerImage(r.first, shadow)
        }
        return out
    }

    fun maxScale(tracks: Map<String, List<AnKey>>): Float = tracks.values.flatten().maxOfOrNull { it.scale }?.coerceAtLeast(1f) ?: 1f
}

/**
 * Bitmap layer foto untuk pratinjau: dirender sekali per kombinasi (foto, edit, mask, potong, ukuran)
 * di thread kerja CsPipeline, lalu [onReady] dipanggil di thread UI.
 */
internal class AnPreviewImages(private val onReady: () -> Unit) {
    private val cache = HashMap<String, Pair<String, AnLayerImage>>()
    private val pending = HashSet<String>()
    /** Kunci yang gagal dirender (berkas hilang / rusak) — tidak dicoba ulang terus-menerus. */
    private val failed = HashMap<String, String>()
    var alive = true

    private fun key(el: CsElement): String =
        "${el.imagePath}|${el.edit.hashCode()}|${el.maskId}|${el.cut}|${el.shadow}|${el.shadowBlur.roundToInt()}|${(max(el.w, el.h) / 64f).roundToInt()}"

    fun get(el: CsElement): AnLayerImage? {
        if (el.type != CsType.IMAGE || el.imagePath == null) return null
        val k = key(el)
        val cur = cache[el.id]
        if (cur?.first != k && failed[el.id] != k && pending.add(el.id)) {
            val copy = el.copy()
            val side = (max(el.w, el.h) * 1.25f).roundToInt().coerceIn(256, 1600)
            CsPipeline.runOnWorker {
                val img = runCatching {
                    CsPipeline.renderForExport(copy, side)?.let { (bmp, transparent) ->
                        val sh = if (copy.shadow && transparent) runCatching { CsRender.makeShadow(bmp, copy.shadowBlur, copy.w, 256) }.getOrNull() else null
                        AnLayerImage(bmp, sh)
                    }
                }.getOrNull()
                CsPipeline.post {
                    pending.remove(copy.id)
                    if (!alive) return@post
                    if (img != null) { cache[copy.id] = k to img; failed.remove(copy.id) } else failed[copy.id] = k
                    onReady()
                }
            }
        }
        return cur?.second
    }

    fun retain(ids: Set<String>) {
        cache.keys.retainAll(ids)
    }
}

// ====================================================================== kanvas pratinjau

/**
 * Kanvas pratinjau dokumen beranimasi. Ketuk layer = pilih, seret = [onDrag] (delta total px kanvas),
 * ketuk area kosong = lepas pilihan.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class AnStageView(ctx: Context, private val c: EditorChrome) : View(ctx) {
    var doc: CsDocData? = null
    var state: (CsElement) -> AnState = { AnState.IDENTITY }
    var images: AnPreviewImages? = null
    var selectedId: String? = null
    var interactive = true
    var showOutline = true
    var onSelect: ((String?) -> Unit)? = null
    var onDragStart: ((String) -> Unit)? = null
    var onDrag: ((String, Float, Float) -> Unit)? = null
    var onDragEnd: ((String) -> Unit)? = null
    var workspace: Int = if (c.dark) Color.rgb(10, 10, 12) else Color.rgb(226, 226, 230)
    var padDp = 14

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val checker: Paint = Paint().apply {
        val b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        cv.drawColor(Color.rgb(250, 250, 250))
        val p = Paint().apply { color = Color.rgb(214, 214, 218) }
        cv.drawRect(0f, 0f, 8f, 8f, p)
        cv.drawRect(8f, 8f, 16f, 16f, p)
        shader = BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val checkerMatrix = Matrix()
    private var dragId: String? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    private fun scale(): Float {
        val d = doc ?: return 1f
        val pad = c.dp(padDp) * 2f
        return max(0.01f, min((width - pad) / d.w, (height - pad) / d.h))
    }

    private fun originX(): Float = (width - (doc?.w ?: 0) * scale()) / 2f
    private fun originY(): Float = (height - (doc?.h ?: 0) * scale()) / 2f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(workspace)
        val d = doc ?: return
        val s = scale()
        canvas.save()
        canvas.translate(originX(), originY())
        canvas.scale(s, s)
        val cw = d.w.toFloat()
        val ch = d.h.toFloat()
        paint.reset()
        paint.color = Color.argb(if (c.dark) 120 else 50, 0, 0, 0)
        canvas.drawRect(4f / s, 6f / s, cw + 4f / s, ch + 6f / s, paint)
        if (d.bg.mode == "transparent") {
            checkerMatrix.setScale(1f / s, 1f / s)
            checker.shader?.setLocalMatrix(checkerMatrix)
            canvas.drawRect(0f, 0f, cw, ch, checker)
        }
        canvas.save()
        canvas.clipRect(0f, 0f, cw, ch)
        val imgs = images
        AnDraw.drawDoc(context, canvas, d, state, { el -> imgs?.get(el) }, paint, background = true)
        canvas.restore()
        if (showOutline && interactive) {
            val sel = selectedId?.let { id -> d.elements.firstOrNull { it.id == id } }
            if (sel != null) {
                val st = state(sel)
                val e = AnDraw.animated(sel, st)
                linePaint.style = Paint.Style.STROKE
                linePaint.strokeWidth = 2f / s
                linePaint.color = if (c.dark) Color.WHITE else Color.rgb(20, 20, 24)
                canvas.save()
                val cx = e.x + e.w / 2f
                val cy = e.y + e.h / 2f
                canvas.rotate(e.rotation, cx, cy)
                canvas.scale(st.scale.coerceAtLeast(0.05f), st.scale.coerceAtLeast(0.05f), cx, cy)
                canvas.drawRect(e.x, e.y, e.x + e.w, e.y + e.h, linePaint)
                linePaint.color = if (c.dark) Color.argb(160, 0, 0, 0) else Color.argb(170, 255, 255, 255)
                linePaint.strokeWidth = 1f / s
                canvas.drawRect(e.x - 2f / s, e.y - 2f / s, e.x + e.w + 2f / s, e.y + e.h + 2f / s, linePaint)
                canvas.restore()
            }
        }
        paint.reset()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f / s
        paint.color = if (c.dark) Color.argb(90, 255, 255, 255) else Color.argb(70, 0, 0, 0)
        canvas.drawRect(0f, 0f, cw, ch, paint)
        canvas.restore()
    }

    private fun hit(x: Float, y: Float): CsElement? {
        val d = doc ?: return null
        val s = scale()
        val cx = (x - originX()) / s
        val cy = (y - originY()) / s
        val tol = c.dp(8) / s
        return d.elements.asReversed().firstOrNull { el ->
            if (!el.visible || el.locked) return@firstOrNull false
            val st = state(el)
            if (st.opacity <= 0.02f || st.scale <= 0.02f) return@firstOrNull false
            val e = AnDraw.animated(el, st)
            val w = e.w * st.scale
            val h = e.h * st.scale
            val box = DsBox(e.x + e.w / 2f - w / 2f, e.y + e.h / 2f - h / 2f, w, h, e.rotation)
            val extra = if (el.type == CsType.LINE) max(tol, c.dp(14) / s) else tol
            DesignLayout.hit(box, cx, cy, extra)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive || doc == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                val h = hit(event.x, event.y)
                dragId = h?.id
                if (h?.id != selectedId) {
                    selectedId = h?.id
                    onSelect?.invoke(h?.id)
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val id = dragId ?: return true
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && abs(dx) + abs(dy) > c.dp(4)) {
                    dragging = true
                    onDragStart?.invoke(id)
                }
                if (dragging) {
                    val s = scale()
                    onDrag?.invoke(id, dx / s, dy / s)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val id = dragId
                if (dragging && id != null) onDragEnd?.invoke(id)
                dragging = false
                dragId = null
                return true
            }
        }
        return true
    }
}

// ====================================================================== ekspor

/** Sumber frame untuk encoder: menggambar frame ke-i pada kanvas [width]×[height]. */
internal interface AnFrameSource {
    val width: Int
    val height: Int
    val fps: Int
    val frameCount: Int
    fun drawFrame(i: Int, canvas: Canvas)
}

/** Animasi satu dokumen (Animation Studio). */
internal class AnDocSource(
    private val ctx: Context,
    private val doc: CsDocData,
    private val tracks: Map<String, List<AnKey>>,
    durationMs: Long,
    override val fps: Int,
    override val width: Int,
    override val height: Int,
    private val images: Map<String, AnLayerImage>,
    private val background: Boolean
) : AnFrameSource {
    override val frameCount: Int = AnTiming.frameCount(durationMs, fps)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawFrame(i: Int, canvas: Canvas) {
        val t = AnTiming.frameMs(i, fps)
        val save = canvas.save()
        canvas.scale(width / doc.w.toFloat(), height / doc.h.toFloat())
        AnDraw.drawDoc(ctx, canvas, doc, { el -> AnMath.sample(tracks[el.id] ?: emptyList(), t) }, { el -> images[el.id] }, paint, background)
        canvas.restoreToCount(save)
    }
}

internal object AnEncode {
    class Cancelled : Exception("Dibatalkan")

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT_US = 10_000L

    /** Folder hasil sementara sebelum dipublikasikan (Android 7–9: hasil tetap di sini). */
    fun outDir(ctx: Context, type: String): File {
        val dir = ctx.getExternalFilesDir(type) ?: File(ctx.filesDir, type.lowercase())
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Folder privat untuk klip yang diserahkan ke Video Editor (tidak dihapus). */
    fun veMediaDir(ctx: Context): File = File(ctx.filesDir, "ve_media").apply { mkdirs() }

    private fun configure(codec: MediaCodec, w: Int, h: Int, fps: Int) {
        val fmt = MediaFormat.createVideoFormat(MIME, w, h)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, AnTiming.bitrate(w, h, fps))
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    /** Ukuran kandidat: asli genap → kelipatan 16 → sisi panjang 1920/1280/720 (encoder HP lama). */
    private fun candidates(w: Int, h: Int): List<Pair<Int, Int>> {
        val list = listOf(
            AnTiming.fitEven(w, h, max(w, h), 2),
            AnTiming.fitEven(w, h, max(w, h), 16),
            AnTiming.fitEven(w, h, 1920, 16),
            AnTiming.fitEven(w, h, 1280, 16),
            AnTiming.fitEven(w, h, 720, 16)
        )
        return list.distinct()
    }

    /** Pasang encoder pada ukuran pertama yang didukung. @return (codec sudah start, w, h) */
    private fun openEncoder(w: Int, h: Int, fps: Int): Triple<MediaCodec, Int, Int> {
        var last: Throwable? = null
        for ((cw, ch) in candidates(w, h)) {
            val codec = MediaCodec.createEncoderByType(MIME)
            val supported = runCatching { codec.codecInfo.getCapabilitiesForType(MIME).videoCapabilities?.isSizeSupported(cw, ch) ?: true }.getOrDefault(true)
            if (!supported) { codec.release(); continue }
            try {
                configure(codec, cw, ch, fps)
                codec.start()
                return Triple(codec, cw, ch)
            } catch (e: Throwable) {
                last = e
                runCatching { codec.release() }
            }
        }
        throw IllegalStateException("Encoder H.264 tidak mendukung ukuran ${w}×${h}" + (last?.message?.let { ": $it" } ?: ""))
    }

    /** Tulis I420 padat ke Image input encoder sesuai rowStride/pixelStride tiap bidang. */
    private fun writeImage(image: Image, yb: ByteArray, ub: ByteArray, vb: ByteArray, w: Int, h: Int) {
        val planes = image.planes
        val cw = AnYuv.chromaW(w)
        val ch = AnYuv.chromaH(h)
        fun put(plane: Image.Plane, src: ByteArray, pw: Int, ph: Int) {
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            if (ps == 1) {
                val base = buf.position()
                for (row in 0 until ph) {
                    val pos = base + row * rs
                    if (pos >= buf.limit()) break
                    val dup = buf.duplicate()
                    dup.position(pos)
                    dup.put(src, row * pw, min(pw, dup.remaining()))
                }
            } else {
                // Bidang berselang (NV12/NV21 berbagi memori): baca-ubah-tulis agar byte bidang lain utuh.
                val dup = buf.duplicate()
                val tmp = ByteArray(dup.remaining())
                dup.get(tmp)
                AnYuv.scatter(src, pw, ph, tmp, rs, ps)
                val back = buf.duplicate()
                back.put(tmp)
            }
        }
        put(planes[0], yb, w, h)
        put(planes[1], ub, cw, ch)
        put(planes[2], vb, cw, ch)
    }

    /**
     * MP4 H.264 tanpa audio. Frame diratakan di atas putih (MP4 tidak mendukung transparansi).
     * Dijalankan di thread kerja; [cancelled] diperiksa setiap frame. Berkas dihapus bila gagal/batal.
     */
    fun mp4(src: AnFrameSource, out: File, cancelled: () -> Boolean, progress: (Int) -> Unit) {
        val fps = src.fps.coerceIn(1, 60)
        val (codec, w, h) = openEncoder(src.width, src.height, fps)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var track = -1
        val info = MediaCodec.BufferInfo()
        var ok = false
        try {
            val mx = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val argb = IntArray(w * h)
            val cs = AnYuv.chromaW(w) * AnYuv.chromaH(h)
            val yb = ByteArray(w * h)
            val ub = ByteArray(cs)
            val vb = ByteArray(cs)
            var sawEos = false

            fun drain(endOfStream: Boolean) {
                var idle = 0
                while (true) {
                    val o = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) return
                        if (++idle > 300) error("Encoder tidak menyelesaikan video")
                        continue
                    }
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxerStarted) { "Format encoder berubah dua kali" }
                        track = mx.addTrack(codec.outputFormat)
                        mx.start()
                        muxerStarted = true
                        continue
                    }
                    if (o < 0) continue
                    idle = 0
                    val buf = codec.getOutputBuffer(o)
                    val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!config && info.size > 0 && buf != null && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        mx.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { sawEos = true; return }
                }
            }

            fun inputIndex(): Int {
                var tries = 0
                while (true) {
                    if (cancelled()) throw Cancelled()
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) return idx
                    drain(false)
                    if (++tries > 1000) error("Encoder tidak menerima frame")
                }
            }

            val n = src.frameCount
            for (i in 0 until n) {
                if (cancelled()) throw Cancelled()
                bmp.eraseColor(Color.WHITE)
                val save = canvas.save()
                canvas.scale(w / src.width.toFloat(), h / src.height.toFloat())
                src.drawFrame(i, canvas)
                canvas.restoreToCount(save)
                bmp.getPixels(argb, 0, w, 0, 0, w, h)
                AnYuv.argbToI420(argb, w, h, yb, ub, vb)
                val idx = inputIndex()
                // Kapasitas dibaca dulu (getInputImage sesudahnya membatalkan ByteBuffer ini).
                val capacity = runCatching { codec.getInputBuffer(idx)?.capacity() ?: 0 }.getOrDefault(0)
                val frameBytes = if (capacity > 0) min(capacity, w * h + cs * 2) else w * h + cs * 2
                val image = codec.getInputImage(idx)
                if (image != null) {
                    writeImage(image, yb, ub, vb, w, h)
                } else {
                    // Cadangan: buffer planar I420 padat.
                    val buf = codec.getInputBuffer(idx) ?: error("Buffer input encoder kosong")
                    buf.clear()
                    buf.put(yb, 0, min(yb.size, buf.remaining()))
                    buf.put(ub, 0, min(ub.size, buf.remaining()))
                    buf.put(vb, 0, min(vb.size, buf.remaining()))
                }
                codec.queueInputBuffer(idx, 0, frameBytes, AnTiming.frameUs(i, src.fps), 0)
                drain(false)
                progress(((i + 1) * 100L / n).toInt().coerceIn(0, 99))
            }
            val idx = inputIndex()
            codec.queueInputBuffer(idx, 0, 0, AnTiming.frameUs(n, src.fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            bmp.recycle()
            check(sawEos && muxerStarted) { "Video kosong" }
            ok = true
            progress(100)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!ok) out.delete()
        }
    }

    /** GIF89a beranimasi; transparansi dipertahankan bila sumber tidak menggambar latar. */
    fun gif(src: AnFrameSource, out: File, cancelled: () -> Boolean, progress: (Int) -> Unit) {
        val w = src.width
        val h = src.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val argb = IntArray(w * h)
        var ok = false
        try {
            BufferedOutputStream(FileOutputStream(out), 64 * 1024).use { os ->
                val enc = AnGifEncoder(os, w, h)
                val n = src.frameCount
                for (i in 0 until n) {
                    if (cancelled()) throw Cancelled()
                    bmp.eraseColor(Color.TRANSPARENT)
                    src.drawFrame(i, canvas)
                    bmp.getPixels(argb, 0, w, 0, 0, w, h)
                    enc.addFrame(argb, AnTiming.gifDelayCs(i, src.fps))
                    progress(((i + 1) * 100L / n).toInt().coerceIn(0, 99))
                }
                enc.finish()
            }
            ok = true
            progress(100)
        } finally {
            bmp.recycle()
            if (!ok) out.delete()
        }
    }
}

// ====================================================================== publikasi & bagikan

internal class AnResult(val uri: Uri, val label: String, val mime: String, val bytes: Long)

internal object AnShare {
    /** Salin ke koleksi publik (Movies/Pictures/Documents › GITLS); Android 7–9 tetap di folder aplikasi. */
    fun publish(ctx: Context, file: File, mime: String): AnResult {
        val size = file.length()
        val pub = runCatching { VePublish.copy(ctx, file, deleteSource = true) }.getOrNull()
        if (pub != null) return AnResult(pub.uri, pub.label, pub.mime, pub.bytes)
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        return AnResult(uri, "Folder aplikasi/${file.parentFile?.name ?: ""}/${file.name}", mime, size)
    }

    fun share(a: MainActivity, r: AnResult) {
        runCatching {
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = r.mime
                putExtra(Intent.EXTRA_STREAM, r.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Bagikan"))
        }.onFailure { a.toast("Gagal membagikan: ${it.message}") }
    }

    fun movieFile(ctx: Context, base: String, ext: String): File =
        File(AnEncode.outDir(ctx, if (ext == "pdf") Environment.DIRECTORY_DOCUMENTS else if (ext == "gif") Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES),
            "${CsStore.safeName(base)}_${System.currentTimeMillis()}.$ext")
}
