package com.example.aidetest

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Video to Frames — ekstrak frame video menjadi PNG/JPG lalu ekspor ke ZIP.
 *  - VfMath      : logika murni (rencana waktu frame, estimasi, resolusi) — teruji JVM
 *  - VfGrabber   : pembaca frame (MediaMetadataRetriever), satu bitmap sekali waktu
 *  - VideoFramesUi : halaman tool (pengaturan, estimasi, pratinjau, progres, hasil)
 *
 * Hemat RAM: frame didekode satu per satu pada ukuran keluaran (API 27+), langsung di-encode ke ZIP
 * yang ditulis streaming ke penyimpanan (tanpa menampung semua frame / ZIP di memori).
 */

@Volatile
internal var videoFramesPickPending: ((Uri?) -> Unit)? = null

internal object VfMath {
    const val MODE_COUNT = "count"
    const val MODE_FPS = "fps"
    const val MODE_INTERVAL = "interval"
    const val MODE_ALL = "all"
    const val MODE_CUSTOM = "custom"

    /** Batas jumlah frame per ekstraksi (di bawah batas 65.535 entri ZIP biasa). */
    const val MAX_FRAMES = 10_000
    /** Batas ukuran ZIP tanpa ZIP64 (4 GB) dengan sedikit ruang aman. */
    const val MAX_ZIP_BYTES = 3_800_000_000L

    class Plan(val count: Int, val rawCount: Int, val note: String?, private val timeFn: (Int) -> Long) {
        val capped: Boolean get() = rawCount > count
        fun timeMs(i: Int): Long = timeFn(i)
    }

    /** Jumlah frame asli dalam rentang [spanMs] pada [fps] (150 untuk 5 detik @ 30 FPS). */
    fun totalFrames(spanMs: Long, fps: Double): Int =
        max(1, (spanMs / 1000.0 * fps).roundToInt())

    fun plan(
        mode: String,
        startMs: Long,
        spanMs: Long,
        durationMs: Long,
        srcFps: Double,
        count: Int,
        fps: Double,
        intervalMs: Long,
        custom: List<Long>
    ): Plan {
        val dur = max(1L, durationMs)
        val src = if (srcFps > 0.0) srcFps else 30.0
        val start = startMs.coerceIn(0L, dur - 1)
        val span = spanMs.coerceIn(1L, dur - start)
        val lastT = dur - 1
        fun make(raw: Int, note: String?, fn: (Int) -> Long): Plan {
            val n = min(raw, MAX_FRAMES)
            val extra = if (raw > MAX_FRAMES) "Dibatasi $MAX_FRAMES frame pertama" else null
            return Plan(n, raw, listOfNotNull(note, extra).joinToString(" · ").ifEmpty { null }) { i ->
                fn(i).coerceIn(0L, lastT)
            }
        }
        val all = totalFrames(span, src)
        return when (mode) {
            MODE_COUNT -> {
                val want = max(1, count)
                val n = min(want, all)
                val note = if (want > all) "Jumlah dibatasi ke $all (frame asli dalam rentang)" else null
                make(n, note) { i -> start + (i.toDouble() * span / n).toLong() }
            }
            MODE_FPS -> {
                val f = if (fps <= 0.0) src else min(fps, src)
                val note = if (fps > src) "FPS dibatasi ke ${vfFps(src)} (FPS video)" else null
                val step = 1000.0 / f
                make(max(1, floor(span / step + 1e-6).toInt()), note) { i -> start + (i * step).toLong() }
            }
            MODE_INTERVAL -> {
                val minStep = max(1L, (1000.0 / src).toLong())
                val step = max(minStep, intervalMs)
                val note = if (intervalMs in 1 until minStep) "Interval dibatasi ke $minStep ms (1 frame)" else null
                make(max(1, ceil(span.toDouble() / step).toInt()), note) { i -> start + i * step }
            }
            MODE_ALL -> {
                val step = 1000.0 / src
                make(all, null) { i -> start + (i * step).toLong() }
            }
            else -> {
                val times = custom.filter { it in 0..lastT }.distinct().sorted()
                make(times.size, null) { i -> times[i] }
            }
        }
    }

    /** Satu waktu: "1.5", "1:05", "0:01:05.5" → detik. */
    fun parseTime(token: String): Double? {
        val t = token.trim()
        if (t.isEmpty()) return null
        val parts = t.split(':')
        if (parts.size > 3) return null
        var total = 0.0
        for (p in parts) {
            val v = p.toDoubleOrNull() ?: return null
            if (v < 0.0 || v.isNaN() || v.isInfinite()) return null
            total = total * 60.0 + v
        }
        return total
    }

    /** Daftar waktu mode custom (pemisah koma/titik koma/spasi/baris baru): (milidetik, jumlah token tak valid). */
    fun parseCustom(text: String, durationMs: Long): Pair<List<Long>, Int> {
        val out = ArrayList<Long>()
        var bad = 0
        for (tok in text.split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }) {
            val s = parseTime(tok)
            if (s == null) { bad++; continue }
            val ms = (s * 1000.0).toLong()
            if (ms >= durationMs) { bad++; continue }
            out.add(ms)
        }
        return out to bad
    }

    fun displaySize(w: Int, h: Int, rotation: Int): Pair<Int, Int> =
        if (rotation == 90 || rotation == 270) h to w else w to h

    /** Ukuran keluaran: "asli" | "1080"/"720"/"480"/"360" (sisi pendek) | "custom" (sisi terpanjang). Tidak pernah memperbesar. */
    fun outputSize(dw: Int, dh: Int, res: String, customLong: Int): Pair<Int, Int> {
        if (dw <= 0 || dh <= 0) return 0 to 0
        val scale = when (res) {
            "1080", "720", "480", "360" -> res.toInt().toDouble() / min(dw, dh)
            "custom" -> if (customLong > 0) customLong.toDouble() / max(dw, dh) else 1.0
            else -> 1.0
        }
        val s = min(1.0, scale)
        if (s >= 0.9999) return dw to dh
        return max(2, (dw * s).roundToInt()) to max(2, (dh * s).roundToInt())
    }

    /** Perkiraan kasar ukuran satu file hasil (byte). */
    fun frameBytes(w: Int, h: Int, format: String, quality: Int): Long {
        val px = w.toLong() * h
        val bpp = if (format == "png") 1.8 else 0.08 + 0.17 * ((quality - 60).coerceIn(0, 40) / 40.0)
        return (px * bpp).toLong().coerceAtLeast(2_000L)
    }

    fun digits(count: Int): Int = max(4, count.toString().length)
}

internal fun vfFps(f: Double): String =
    if (kotlin.math.abs(f - f.roundToInt()) < 0.05) f.roundToInt().toString() else String.format(Locale.US, "%.2f", f)

internal fun vfBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    b >= 1024 -> String.format(Locale.US, "%d KB", b / 1024)
    else -> "$b B"
}

internal fun vfDur(ms: Long): String {
    val s = (ms / 1000.0).roundToInt()
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

internal fun vfTime(ms: Long): String =
    String.format(Locale.US, "%d:%04.1f", ms / 60000, (ms % 60000) / 1000.0)

internal class VfBuf : ByteArrayOutputStream(1 shl 20) {
    fun raw(): ByteArray = buf
}

internal class VfInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val fps: Double,
    val fpsKnown: Boolean,
    val sizeBytes: Long
) {
    val dispW: Int get() = VfMath.displaySize(width, height, rotation).first
    val dispH: Int get() = VfMath.displaySize(width, height, rotation).second
}

/** Pembaca frame. Satu instance = satu thread (MediaMetadataRetriever tidak aman lintas thread). */
internal class VfGrabber(private val ctx: Context, private val uri: Uri) {
    private val r = MediaMetadataRetriever()
    var durationMs = 0L
    var width = 0
    var height = 0
    var rotation = 0
    var fps = 30.0
    var fpsKnown = false
    /** 0 = ambil frame penuh lalu skala; 1 = getScaledFrameAtTime langsung; 2 = dengan lebar/tinggi tertukar. */
    private var scaledMode = 0

    fun open(): Boolean {
        try {
            r.setDataSource(ctx, uri)
        } catch (e: RuntimeException) {
            return false
        }
        if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == null) return false
        durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        detectFps()
        if (width <= 0 || height <= 0 || durationMs <= 0L) return false
        probeScaled()
        return true
    }

    private fun sane(f: Double) = f in 1.0..240.0

    private fun detectFps() {
        var f = 0.0
        runCatching {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(ctx, uri, null)
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                        if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            f = runCatching { fmt.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                                .getOrElse { runCatching { fmt.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrDefault(0.0) }
                        }
                        break
                    }
                }
            } finally {
                ex.release()
            }
        }
        if (!sane(f) && Build.VERSION.SDK_INT >= 28) {
            val n = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
            if (n > 1 && durationMs > 0) f = n * 1000.0 / durationMs
        }
        if (!sane(f) && Build.VERSION.SDK_INT >= 23) {
            f = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull() ?: 0.0
        }
        if (sane(f)) { fps = f; fpsKnown = true } else { fps = 30.0; fpsKnown = false }
    }

    /** Uji sekali apakah getScaledFrameAtTime memakai ukuran tampilan (setelah rotasi) atau ukuran mentah. */
    private fun probeScaled() {
        scaledMode = 0
        if (Build.VERSION.SDK_INT < 27) return
        val (dw, dh) = VfMath.displaySize(width, height, rotation)
        val tw = 96
        val th = max(2, (tw.toDouble() * dh / dw).roundToInt())
        val opt = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val b1 = (try { r.getScaledFrameAtTime(0L, opt, tw, th) } catch (e: RuntimeException) { null }) ?: return
        val w1 = b1.width
        val h1 = b1.height
        b1.recycle()
        if (w1 == tw && h1 == th) { scaledMode = 1; return }
        if (w1 == th && h1 == tw) {
            val b2 = (try { r.getScaledFrameAtTime(0L, opt, th, tw) } catch (e: RuntimeException) { null }) ?: return
            if (b2.width == tw && b2.height == th) scaledMode = 2
            b2.recycle()
        }
    }

    /** Frame pada [timeMs] berukuran [outW]×[outH] (tegak). null bila tidak bisa didekode. */
    fun grab(timeMs: Long, outW: Int, outH: Int, precise: Boolean, retry: Boolean = true): Bitmap? {
        val opt = if (precise) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val tUs = timeMs.coerceAtLeast(0L) * 1000L
        val (dw, dh) = VfMath.displaySize(width, height, rotation)
        val native = outW == dw && outH == dh
        var bmp: Bitmap? = try {
            if (!native && scaledMode != 0 && Build.VERSION.SDK_INT >= 27) {
                if (scaledMode == 1) r.getScaledFrameAtTime(tUs, opt, outW, outH)
                else r.getScaledFrameAtTime(tUs, opt, outH, outW)
            } else {
                r.getFrameAtTime(tUs, opt)
            }
        } catch (e: RuntimeException) {
            null
        }
        if (bmp != null && (bmp.width != outW || bmp.height != outH)) {
            val s = Bitmap.createScaledBitmap(bmp, outW, outH, true)
            if (s !== bmp) bmp.recycle()
            bmp = s
        }
        if (bmp == null && retry && timeMs > 100L) return grab(timeMs - 100L, outW, outH, precise, false)
        return bmp
    }

    fun close() {
        runCatching { r.release() }
    }
}

internal class VideoFramesUi(private val a: MainActivity) {
    private val kit = ImgKit(a)
    private val c = kit.c
    private val handler = Handler(Looper.getMainLooper())
    val root = LinearLayout(a)

    private var uri: Uri? = null
    private var name = ""
    private var info: VfInfo? = null

    private var startSec = 0.0
    private var spanSec = 0.0 // 0 = sampai akhir
    private var mode = VfMath.MODE_COUNT
    private var count = 10
    private var fpsSel = 5.0
    private var intervalSec = 1.0
    private var customText = ""
    private var format = "png"
    private var quality = 90
    private var res = "asli"
    private var customLong = 1280
    private var precise = true

    private var alive = true
    private var previewGen = 0
    private var extracting = false
    private val cancel = AtomicBoolean(false)
    private val thumbs = ArrayList<Bitmap>()

    private var extractBtn: TextView? = null
    private val sourceBox = LinearLayout(a)
    private val settingsBox = LinearLayout(a)
    private val estimateTitle = kit.text("Pilih video untuk melihat estimasi", 15f, c.ink, true)
    private val estimateNote = kit.text("", 11.5f, c.muted)
    private val previewStatus = kit.text("", 11.5f, c.muted)
    private val previewRow = LinearLayout(a)
    private val actionBox = LinearLayout(a)
    private val previewTask = Runnable { loadPreview() }

    private fun dp(v: Int) = a.dp(v)

    init {
        root.orientation = LinearLayout.VERTICAL
        sourceBox.orientation = LinearLayout.VERTICAL
        settingsBox.orientation = LinearLayout.VERTICAL
        actionBox.orientation = LinearLayout.VERTICAL
        previewRow.orientation = LinearLayout.HORIZONTAL

        root.addView(sourceBox, LinearLayout.LayoutParams(-1, -2))
        root.addView(settingsBox, LinearLayout.LayoutParams(-1, -2))

        root.addView(section("ESTIMASI"))
        val est = card()
        est.addView(estimateTitle)
        est.addView(estimateNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        root.addView(est, LinearLayout.LayoutParams(-1, -2))

        root.addView(section("PRATINJAU"))
        val prev = card()
        prev.addView(previewStatus)
        val scroll = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
        scroll.addView(previewRow)
        prev.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(prev, LinearLayout.LayoutParams(-1, -2))

        root.addView(actionBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                alive = false
                cancel.set(true)
                handler.removeCallbacks(previewTask)
                clearThumbs()
                runCatching { a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        })

        buildSource()
        buildSettings()
        buildActions(null)
        refreshEstimate()
    }

    // ================================================================== UI helpers

    private fun section(title: String): TextView =
        kit.text(title, 10.5f, c.muted, true).apply {
            letterSpacing = 0.08f
            setPadding(dp(4), dp(14), 0, dp(6))
        }

    private fun card(): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = c.shape(c.surface, 16, c.stroke)
    }

    private fun pills(items: List<Triple<String, Boolean, () -> Unit>>): View {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, on, click) in items) {
            row.addView(kit.pill(label, on, click), LinearLayout.LayoutParams(-2, dp(34)).apply { rightMargin = dp(6) })
        }
        return HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun field(hint: String, value: String, decimal: Boolean, onText: (String) -> Unit): EditText =
        EditText(a).apply {
            setText(value)
            this.hint = hint
            textSize = 14f
            setTextColor(c.ink)
            setHintTextColor(c.muted)
            inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            background = c.shape(c.surface2, 12, c.stroke)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, cnt: Int, aft: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, cnt: Int) {}
                override fun afterTextChanged(s: Editable?) { onText(s?.toString() ?: "") }
            })
        }

    private fun labeled(label: String, view: View): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(kit.text(label, 11.5f, c.muted).apply { setPadding(dp(2), 0, 0, dp(4)) })
        addView(view, LinearLayout.LayoutParams(-1, dp(44)))
    }

    private fun twoCols(left: View, right: View): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
    }

    private fun num(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

    private fun fmtNum(v: Double): String =
        if (v == floor(v)) v.toLong().toString() else String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    // ================================================================== sumber

    private fun buildSource() {
        sourceBox.removeAllViews()
        val card = card().apply {
            isClickable = true
            isFocusable = true
            background = c.pressable(c.surface, 16, c.stroke)
            setOnClickListener { pickVideo() }
        }
        val i = info
        card.addView(kit.text("VIDEO", 10.5f, c.muted, true).apply { letterSpacing = 0.08f })
        card.addView(kit.text(if (i == null) "Ketuk untuk memilih video…" else name, 14.5f, c.ink, true).apply {
            maxLines = 2
            setPadding(0, dp(4), 0, dp(2))
        })
        if (i != null) {
            val total = VfMath.totalFrames(i.durationMs, i.fps)
            val fpsText = (if (i.fpsKnown) "" else "≈ ") + vfFps(i.fps) + " FPS"
            card.addView(kit.text(
                "${i.dispW}×${i.dispH} · ${vfDur(i.durationMs)} · $fpsText · ${vfBytes(i.sizeBytes)}",
                12f, c.muted
            ))
            card.addView(kit.text("Total ≈ $total frame", 12f, c.muted).apply { setPadding(0, dp(2), 0, 0) })
        }
        sourceBox.addView(card, LinearLayout.LayoutParams(-1, -2))
    }

    private fun pickVideo() {
        if (extracting) { a.toast("Tunggu ekstraksi selesai atau batalkan dulu"); return }
        videoFramesPickPending = { u -> if (u != null && alive) loadSource(u) }
        @Suppress("DEPRECATION")
        a.startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            },
            1270
        )
    }

    private fun querySize(u: Uri): Long = runCatching {
        a.contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun loadSource(u: Uri) {
        previewStatus.text = "Membaca video…"
        Thread {
            val g = VfGrabber(a, u)
            val ok = try { g.open() } catch (e: Throwable) { false }
            val loaded = if (ok) VfInfo(g.durationMs, g.width, g.height, g.rotation, g.fps, g.fpsKnown, querySize(u)) else null
            g.close()
            val nm = runCatching { a.displayNameOf(u) }.getOrDefault("video")
            a.runOnUiThread {
                if (!alive) return@runOnUiThread
                if (loaded == null) {
                    a.toast("Video tidak bisa dibaca")
                    previewStatus.text = "Video tidak bisa dibaca"
                    return@runOnUiThread
                }
                uri = u
                name = nm
                info = loaded
                startSec = 0.0
                spanSec = 0.0
                val all = VfMath.totalFrames(loaded.durationMs, loaded.fps)
                count = min(10, all)
                fpsSel = min(5.0, loaded.fps)
                intervalSec = 1.0
                buildSource()
                buildSettings()
                buildActions(null)
                refreshEstimate()
                loadPreview()
            }
        }.start()
    }

    // ================================================================== pengaturan

    private fun rangeMs(): Pair<Long, Long> {
        val i = info ?: return 0L to 1L
        val startMs = (startSec * 1000).toLong().coerceIn(0L, max(0L, i.durationMs - 1))
        val spanMs = if (spanSec <= 0.0) i.durationMs - startMs else (spanSec * 1000).toLong()
        return startMs to spanMs
    }

    private fun totalAll(): Int {
        val i = info ?: return 1
        val (s, sp) = rangeMs()
        return VfMath.totalFrames(sp.coerceIn(1L, max(1L, i.durationMs - s)), i.fps)
    }

    private fun plan(): VfMath.Plan? {
        val i = info ?: return null
        val (startMs, spanMs) = rangeMs()
        val custom = VfMath.parseCustom(customText, i.durationMs).first
        return VfMath.plan(mode, startMs, spanMs, i.durationMs, i.fps, count, fpsSel, (intervalSec * 1000).toLong().coerceAtLeast(1L), custom)
    }

    private fun outSize(): Pair<Int, Int> {
        val i = info ?: return 0 to 0
        return VfMath.outputSize(i.dispW, i.dispH, res, customLong)
    }

    private fun rebuildAndRefresh() {
        buildSettings()
        refreshEstimate()
        schedulePreview()
    }

    private fun buildSettings() {
        settingsBox.removeAllViews()
        val i = info
        if (i == null) return
        val dur = i.durationMs

        // ---- rentang
        settingsBox.addView(section("RENTANG"))
        val rangeCard = card()
        val rangePills = ArrayList<Triple<String, Boolean, () -> Unit>>()
        rangePills.add(Triple("Semua", spanSec <= 0.0) { spanSec = 0.0; rebuildAndRefresh() })
        for (s in listOf(5, 10, 30, 60)) {
            if (s * 1000L >= dur) continue
            rangePills.add(Triple("$s dtk", spanSec == s.toDouble()) { spanSec = s.toDouble(); rebuildAndRefresh() })
        }
        rangeCard.addView(pills(rangePills))
        val startField = field("0", if (startSec == 0.0) "" else fmtNum(startSec), true) { t ->
            startSec = (num(t) ?: 0.0).coerceAtLeast(0.0)
            refreshEstimate(); schedulePreview()
        }
        val spanField = field("Semua", if (spanSec <= 0.0) "" else fmtNum(spanSec), true) { t ->
            spanSec = (num(t) ?: 0.0).coerceAtLeast(0.0)
            refreshEstimate(); schedulePreview()
        }
        rangeCard.addView(
            twoCols(labeled("Mulai (detik)", startField), labeled("Durasi (detik)", spanField)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        )
        settingsBox.addView(rangeCard, LinearLayout.LayoutParams(-1, -2))

        // ---- mode
        settingsBox.addView(section("MODE"))
        val modeCard = card()
        modeCard.addView(
            kit.segmented(
                listOf(
                    VfMath.MODE_COUNT to "Jumlah", VfMath.MODE_FPS to "FPS", VfMath.MODE_INTERVAL to "Interval",
                    VfMath.MODE_ALL to "Semua", VfMath.MODE_CUSTOM to "Custom"
                ), mode
            ) { mode = it; rebuildAndRefresh() },
            LinearLayout.LayoutParams(-1, dp(40))
        )
        val modeBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        buildModeControls(modeBox, i)
        modeCard.addView(modeBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        settingsBox.addView(modeCard, LinearLayout.LayoutParams(-1, -2))

        // ---- hasil
        settingsBox.addView(section("HASIL"))
        val outCard = card()
        outCard.addView(
            kit.segmented(listOf("png" to "PNG", "jpg" to "JPG"), format) { format = it; rebuildAndRefresh() },
            LinearLayout.LayoutParams(-1, dp(40))
        )
        if (format == "jpg") {
            outCard.addView(
                kit.slider(label = "Kualitas", min = 60, max = 100, value = quality, default = 90, onChange = { v ->
                    quality = v
                    refreshEstimate()
                }),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            )
        }
        outCard.addView(
            kit.text("Resolusi", 11.5f, c.muted).apply { setPadding(dp(2), dp(12), 0, dp(4)) }
        )
        outCard.addView(
            kit.segmented(
                listOf("asli" to "Asli", "1080" to "1080p", "720" to "720p", "480" to "480p", "custom" to "Kustom"), res
            ) { res = it; rebuildAndRefresh() },
            LinearLayout.LayoutParams(-1, dp(40))
        )
        if (res == "custom") {
            outCard.addView(
                labeled("Sisi terpanjang (px)", field("1280", customLong.toString(), false) { t ->
                    customLong = (t.trim().toIntOrNull() ?: 0).coerceIn(0, 8192)
                    refreshEstimate(); schedulePreview()
                }),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            )
        }
        outCard.addView(
            kit.toggleRow("Akurat per frame", precise) { precise = it; schedulePreview() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        )
        outCard.addView(
            kit.text("Matikan untuk video besar: lebih cepat, tetapi frame bisa menyesuaikan keyframe terdekat.", 11f, c.muted)
        )
        settingsBox.addView(outCard, LinearLayout.LayoutParams(-1, -2))
    }

    private fun buildModeControls(box: LinearLayout, i: VfInfo) {
        when (mode) {
            VfMath.MODE_COUNT -> {
                val all = totalAll()
                val items = ArrayList<Triple<String, Boolean, () -> Unit>>()
                for (n in listOf(3, 10, 25, 50)) {
                    if (n >= all) continue
                    items.add(Triple("$n", count == n) { count = n; rebuildAndRefresh() })
                }
                items.add(Triple("Semua ($all)", count >= all) { count = all; rebuildAndRefresh() })
                box.addView(pills(items))
                box.addView(
                    labeled("Jumlah frame", field("10", count.toString(), false) { t ->
                        count = (t.trim().toIntOrNull() ?: 1).coerceIn(1, 1_000_000)
                        refreshEstimate(); schedulePreview()
                    }),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
                )
                box.addView(kit.text("Frame disebar merata di seluruh rentang.", 11f, c.muted).apply { setPadding(0, dp(6), 0, 0) })
            }
            VfMath.MODE_FPS -> {
                val items = ArrayList<Triple<String, Boolean, () -> Unit>>()
                for (f in listOf(1, 2, 5, 10, 15, 24, 30, 60)) {
                    if (f > i.fps + 0.5) continue
                    items.add(Triple("$f", fpsSel == f.toDouble()) { fpsSel = f.toDouble(); rebuildAndRefresh() })
                }
                items.add(Triple("Asli (${vfFps(i.fps)})", fpsSel >= i.fps) { fpsSel = i.fps; rebuildAndRefresh() })
                box.addView(pills(items))
                box.addView(
                    labeled("Frame per detik", field("5", fmtNum(fpsSel), true) { t ->
                        fpsSel = (num(t) ?: 1.0).coerceIn(0.01, 240.0)
                        refreshEstimate(); schedulePreview()
                    }),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
                )
            }
            VfMath.MODE_INTERVAL -> {
                val items = ArrayList<Triple<String, Boolean, () -> Unit>>()
                for (s in listOf(0.1, 0.25, 0.5, 1.0, 2.0, 5.0)) {
                    items.add(Triple("${fmtNum(s)} dtk", intervalSec == s) { intervalSec = s; rebuildAndRefresh() })
                }
                box.addView(pills(items))
                box.addView(
                    labeled("Interval (detik)", field("1", fmtNum(intervalSec), true) { t ->
                        intervalSec = (num(t) ?: 1.0).coerceAtLeast(0.001)
                        refreshEstimate(); schedulePreview()
                    }),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
                )
            }
            VfMath.MODE_ALL -> {
                box.addView(kit.text("Setiap frame video dalam rentang (${totalAll()} frame).", 12.5f, c.ink))
                box.addView(kit.text("Video panjang menghasilkan banyak file — perhatikan estimasi ukuran.", 11f, c.muted).apply { setPadding(0, dp(4), 0, 0) })
            }
            else -> {
                val et = EditText(a).apply {
                    setText(customText)
                    hint = "0, 1.5, 3.2, 1:05"
                    textSize = 14f
                    setTextColor(c.ink)
                    setHintTextColor(c.muted)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 3
                    gravity = Gravity.TOP
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = c.shape(c.surface2, 12, c.stroke)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, st: Int, cnt: Int, aft: Int) {}
                        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, cnt: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            customText = s?.toString() ?: ""
                            refreshEstimate(); schedulePreview()
                        }
                    })
                }
                box.addView(labeled2("Waktu frame (detik atau m:ss)", et))
                box.addView(
                    kit.text("Pisahkan dengan koma, spasi, atau baris baru; desimal pakai titik. Rentang diabaikan.", 11f, c.muted)
                        .apply { setPadding(0, dp(6), 0, 0) }
                )
            }
        }
    }

    private fun labeled2(label: String, view: View): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(kit.text(label, 11.5f, c.muted).apply { setPadding(dp(2), 0, 0, dp(4)) })
        addView(view, LinearLayout.LayoutParams(-1, -2))
    }

    // ================================================================== estimasi

    private fun freeBytes(): Long = runCatching {
        @Suppress("DEPRECATION")
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    private fun refreshEstimate() {
        val i = info
        val p = plan()
        if (i == null || p == null) {
            estimateTitle.text = "Pilih video untuk melihat estimasi"
            estimateNote.text = ""
            updateExtractEnabled()
            return
        }
        val (w, h) = outSize()
        val each = VfMath.frameBytes(w, h, format, quality)
        val total = each * p.count
        estimateTitle.text = "≈ ${p.count} foto · ${w}×${h} · ≈ ${vfBytes(total)}"
        val notes = ArrayList<String>()
        p.note?.let { notes.add(it) }
        if (mode == VfMath.MODE_CUSTOM) {
            val (_, bad) = VfMath.parseCustom(customText, i.durationMs)
            if (bad > 0) notes.add("$bad waktu tidak valid diabaikan")
            if (p.count == 0) notes.add("Isi minimal satu waktu frame")
        }
        if (total > VfMath.MAX_ZIP_BYTES) notes.add("Perkiraan melebihi 3,8 GB — ekstraksi berhenti otomatis di batas ZIP 4 GB")
        if (total * 1.1 > freeBytes()) notes.add("Ruang penyimpanan mungkin tidak cukup")
        notes.add("Ukuran perkiraan kasar; hasil bergantung isi video")
        estimateNote.text = notes.joinToString("\n")
        updateExtractEnabled()
    }

    // ================================================================== pratinjau

    private fun schedulePreview() {
        handler.removeCallbacks(previewTask)
        handler.postDelayed(previewTask, 450)
    }

    private fun clearThumbs() {
        previewRow.removeAllViews()
        for (b in thumbs) runCatching { b.recycle() }
        thumbs.clear()
    }

    private fun loadPreview() {
        val u = uri
        val i = info
        val p = plan()
        if (!alive || extracting || u == null || i == null || p == null) return
        val gen = ++previewGen
        clearThumbs()
        if (p.count <= 0) { previewStatus.text = "Tidak ada frame"; return }
        val n = min(12, p.count)
        val idx = IntArray(n) { k -> if (n == 1) 0 else (k.toDouble() * (p.count - 1) / (n - 1)).roundToInt() }
        val times = LongArray(n) { k -> p.timeMs(idx[k]) }
        val (ow, oh) = outSize()
        val tw = 320
        val th = max(2, (tw.toDouble() * oh / max(1, ow)).roundToInt())
        val prec = precise
        previewStatus.text = "Memuat pratinjau…"
        Thread {
            val g = VfGrabber(a, u)
            try {
                if (!g.open()) {
                    a.runOnUiThread { if (alive && gen == previewGen) previewStatus.text = "Pratinjau gagal dimuat" }
                    return@Thread
                }
                for (k in 0 until n) {
                    if (!alive || gen != previewGen) return@Thread
                    val bmp = try { g.grab(times[k], tw, th, prec) } catch (e: OutOfMemoryError) { null }
                    val label = "#${idx[k] + 1} · ${vfTime(times[k])}"
                    a.runOnUiThread {
                        if (!alive || gen != previewGen || bmp == null) { bmp?.recycle(); return@runOnUiThread }
                        addThumb(bmp, label)
                    }
                }
                a.runOnUiThread {
                    if (alive && gen == previewGen) previewStatus.text =
                        if (p.count > n) "Menampilkan $n dari ${p.count} foto (tersebar merata)" else "${p.count} foto"
                }
            } finally {
                g.close()
            }
        }.start()
    }

    private fun addThumb(bmp: Bitmap, label: String) {
        thumbs.add(bmp)
        val cell = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val iv = ImageView(a).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = c.shape(c.surface2, 10)
        }
        val wDp = 130
        val hDp = max(40, (wDp.toDouble() * bmp.height / max(1, bmp.width)).roundToInt()).coerceAtMost(180)
        cell.addView(iv, LinearLayout.LayoutParams(dp(wDp), dp(hDp)))
        cell.addView(kit.text(label, 10.5f, c.muted).apply { setPadding(dp(2), dp(4), 0, 0); maxLines = 1 })
        previewRow.addView(cell, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
    }

    // ================================================================== aksi / ekstraksi

    private fun updateExtractEnabled() {
        val ok = info != null && !extracting && (plan()?.count ?: 0) > 0
        extractBtn?.alpha = if (ok) 1f else 0.4f
        extractBtn?.isEnabled = ok
    }

    private fun buildActions(result: VePublished?, summary: String? = null) {
        actionBox.removeAllViews()
        val btn = kit.primaryButton("Ekstrak ke ZIP") { startExtraction() }
        extractBtn = btn
        actionBox.addView(btn, LinearLayout.LayoutParams(-1, dp(48)))
        if (result != null) {
            val card = card()
            card.addView(kit.text("Selesai", 14f, c.ink, true))
            if (summary != null) card.addView(kit.text(summary, 12f, c.muted).apply { setPadding(0, dp(4), 0, 0) })
            card.addView(kit.text("Lokasi: ${result.label}", 11.5f, c.muted).apply { setPadding(0, dp(4), 0, 0) })
            card.addView(kit.secondaryButton("Bagikan ZIP") { shareZip(result) }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
            actionBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        updateExtractEnabled()
    }

    private fun shareZip(r: VePublished) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, r.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            a.startActivity(Intent.createChooser(send, "Bagikan ZIP"))
        }.onFailure { a.toast("Tidak bisa membagikan: ${it.message}") }
    }

    private fun startExtraction() {
        val u = uri ?: run { a.toast("Pilih video dulu"); return }
        val i = info ?: return
        val p = plan() ?: return
        if (p.count <= 0) { a.toast("Tidak ada frame untuk diekstrak"); return }
        if (extracting) return
        val (ow, oh) = outSize()
        val fmt = format
        val q = quality
        val prec = precise
        val gen = a.toolUiGeneration
        val base = name.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9._\\-]"), "_").take(32).ifBlank { "video" }
        val ext = if (fmt == "png") "png" else "jpg"
        val compress = if (fmt == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG

        extracting = true
        cancel.set(false)
        previewGen++
        handler.removeCallbacks(previewTask)
        runCatching { a.ensureLegacyStorageAccess() }
        a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // UI progres
        actionBox.removeAllViews()
        val card = card()
        val statusText = kit.text("Menyiapkan…", 13f, c.ink, true)
        val detailText = kit.text("", 11.5f, c.muted).apply { setPadding(0, dp(4), 0, 0) }
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = p.count
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(c.ink)
        }
        card.addView(statusText)
        card.addView(bar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(8) })
        card.addView(detailText)
        card.addView(kit.secondaryButton("Batal") { cancel.set(true); statusText.text = "Membatalkan…" },
            LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
        actionBox.addView(card, LinearLayout.LayoutParams(-1, -2))
        extractBtn = null

        Thread {
            val grabber = VfGrabber(a, u)
            var written = 0
            var skipped = 0
            var stopNote: String? = null
            var published: VePublished? = null
            var error: String? = null
            var cancelled = false
            val t0 = SystemClock.elapsedRealtime()
            var lastUi = 0L
            try {
                if (!grabber.open()) throw IllegalStateException("Video tidak bisa dibuka")
                published = VePublish.zipStream(a, base + "_frames") { os ->
                    val zos = ZipOutputStream(BufferedOutputStream(os, 256 * 1024))
                    try {
                        zos.setLevel(Deflater.NO_COMPRESSION) // PNG/JPG sudah terkompresi
                        val buf = VfBuf()
                        val digits = VfMath.digits(p.count)
                        var bytes = 0L
                        for (idx in 0 until p.count) {
                            if (cancel.get() || !alive || gen != a.toolUiGeneration) throw CancellationException()
                            val bmp = grabber.grab(p.timeMs(idx), ow, oh, prec)
                            if (bmp == null) {
                                skipped++
                            } else {
                                buf.reset()
                                try { bmp.compress(compress, q, buf) } finally { bmp.recycle() }
                                written++
                                zos.putNextEntry(ZipEntry(String.format(Locale.US, "%s_%0${digits}d.%s", base, written, ext)))
                                zos.write(buf.raw(), 0, buf.size())
                                zos.closeEntry()
                                bytes += buf.size()
                            }
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUi > 150 || idx == p.count - 1) {
                                lastUi = now
                                val done = idx + 1
                                val etaMs = (now - t0) * (p.count - done) / max(1, done)
                                val wr = written
                                val sizeNow = bytes
                                a.runOnUiThread {
                                    if (!alive) return@runOnUiThread
                                    bar.progress = done
                                    statusText.text = "Mengekstrak $done / ${p.count}"
                                    detailText.text = "ZIP ${vfBytes(sizeNow)} · sisa ≈ ${vfDur(etaMs)} · tersimpan $wr"
                                }
                            }
                            if (bytes > VfMath.MAX_ZIP_BYTES) { stopNote = "Berhenti di batas ukuran ZIP 4 GB"; break }
                        }
                        if (written == 0) throw IllegalStateException("Tidak ada frame yang berhasil diekstrak")
                        zos.finish()
                        zos.flush()
                    } finally {
                        runCatching { zos.close() }
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
            } catch (e: OutOfMemoryError) {
                error = "Memori tidak cukup — turunkan resolusi"
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                grabber.close()
            }
            val pub = published
            a.runOnUiThread {
                runCatching { a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                extracting = false
                if (!alive) return@runOnUiThread
                when {
                    cancelled -> { buildActions(null); a.toast("Dibatalkan") }
                    error != null -> { buildActions(null); a.toast("Gagal: $error"); estimateNote.text = "Gagal: $error" }
                    pub != null -> {
                        val parts = ArrayList<String>()
                        parts.add("$written foto ${ext.uppercase(Locale.US)} · ${ow}×${oh} · ZIP ${vfBytes(pub.bytes)}")
                        if (skipped > 0) parts.add("$skipped frame dilewati (tidak terbaca)")
                        stopNote?.let { parts.add(it) }
                        buildActions(pub, parts.joinToString("\n"))
                        a.toast("ZIP tersimpan")
                    }
                    else -> buildActions(null)
                }
                loadPreview()
            }
        }.start()
    }
}

internal fun MainActivity.videoFramesTool() {
    clearPage("Video to Frames")
    title.text = "Video to Frames"
    action.text = "?"
    action.textSize = 20f
    action.isClickable = true
    action.setOnClickListener {
        themedDialog()
            .setTitle("Bantuan — Video to Frames")
            .setMessage(
                "Ekstrak frame video menjadi PNG/JPG, lihat pratinjau, lalu simpan sebagai ZIP.\n\n" +
                    "• Rentang: mulai & durasi (mis. 5 detik).\n" +
                    "• Mode: Jumlah (mis. 3 frame dalam 5 detik), FPS, Interval, Semua frame, atau Custom (daftar waktu).\n" +
                    "• Contoh: video 5 detik @ 30 FPS = 150 frame; pilih 3, 10, 25, atau semua 150.\n" +
                    "• Estimasi jumlah foto & ukuran tampil sebelum ekstraksi.\n" +
                    "• Hemat RAM: frame diproses satu per satu dan ditulis langsung ke ZIP, jadi video besar aman.\n" +
                    "• Hasil: Documents/GITLS (Android 9 ke bawah: perlu izin penyimpanan, tanpa izin di folder aplikasi).\n" +
                    "• Video asli tidak diubah. Keluar dari halaman membatalkan ekstraksi."
            )
            .setPositiveButton("OK", null)
            .show()
    }
    back.setOnClickListener { navigateBack() }
    val ui = VideoFramesUi(this)
    content.addView(ui.root, LinearLayout.LayoutParams(-1, -2))
}
