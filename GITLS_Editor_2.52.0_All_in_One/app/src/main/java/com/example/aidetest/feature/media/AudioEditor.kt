package com.example.aidetest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Audio Editor (lokal, tanpa scroll halaman):
 *  - Waveform dengan penggaris waktu, zoom (pinch / tombol), geser, playhead & seleksi rentang (pegangan)
 *  - Transport: putar dari playhead / putar rentang (loop), posisi & durasi presisi 0.1 dtk
 *  - Edit non-destruktif terhadap riwayat: trim, hapus rentang, split di playhead, salin rentang,
 *    bisukan, fade in/out (kurva halus), gain klip & gain rentang, normalisasi — semua dengan Undo/Redo
 *  - Klip: impor audio / ekstrak dari video, urutkan, duplikat, gabung (resample otomatis), mix di playhead
 *  - Rekam voice-over (mikrofon → klip baru)
 *  - Ekspor WAV 16-bit atau M4A AAC (bitrate dipilih) dari klip aktif atau gabungan semua klip
 *
 * MP3 encoder native tidak tersedia di Android → pakai WAV atau M4A/AAC.
 */

data class AeClip(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri?,
    var name: String,
    /** PCM 16-bit mono @ sampleRate. Tidak pernah diubah di tempat (lihat AudioDsp). */
    var pcm: ShortArray,
    val sampleRate: Int = 44100,
    var gainDb: Float = 0f
) {
    fun durationMs(): Long =
        if (sampleRate <= 0) 0L else (pcm.size * 1000L) / sampleRate
}

@Volatile
internal var audioEditorPickPending: ((Uri?) -> Unit)? = null

private const val AE_PICK_AUDIO = 1240
private const val AE_PICK_VIDEO = 1241
private const val AE_PERM_MIC = 1245
/** Batas data audio yang ditahan riwayat undo (sampel di luar state aktif) ≈ 32 MB. */
private const val AE_UNDO_BUDGET_SAMPLES = 16_000_000L
private const val AE_PEAK_BUCKET = 256

private class AeSnap(val clips: List<AeClip>, val selectedId: String?, val selStart: Int, val selEnd: Int)

/** State Audio Editor bertahan saat halaman dibangun ulang (Back, buka tool lain, ganti tema). */
internal object AeState {
    val clips = mutableListOf<AeClip>()
    var selectedId: String? = null
    var selStart = 0
    var selEnd = 0
    var playhead = 0
    /** Sampel per piksel; 0 = pas layar. */
    var spp = 0f
    var scroll = 0f
    var loop = false
    var tab = "edit"
    var fadeSec = 2f
    var rangeGainDb = 6f
    var exportFormat = "m4a"
    var exportScope = "aktif"
    var bitrate = 192_000
    var lastExport: File? = null
    var lastExportLabel: String? = null
    internal val undo = ArrayList<Any>()
    internal val redo = ArrayList<Any>()
    /** Cache puncak waveform per array PCM (kunci lemah: ikut hilang saat array tak dipakai). */
    val peaks = WeakHashMap<ShortArray, ShortArray>()
}

internal fun MainActivity.audioEditorTool() {
    clearPage("Audio Editor")
    topBarVisibility(false)
    content.setPadding(0, 0, 0, 0)
    val ui = AudioEditorUi(this)
    content.addView(ui.root, LinearLayout.LayoutParams(-1, 0, 1f))
}

@SuppressLint("ClickableViewAccessibility", "ViewConstructor", "SetTextI18n")
internal class AudioEditorUi(private val a: MainActivity) {
    private val kit = ImgKit(a)
    private val c = kit.c
    private val s = AeState
    private val handler = Handler(Looper.getMainLooper())

    val root = FrameLayout(a)
    private val column = LinearLayout(a)
    private val subtitle = TextView(a)
    private val undoBtn: MdiIconView
    private val redoBtn: MdiIconView
    private val stage = FrameLayout(a)
    private val wave: AeWaveView
    private val emptyState = LinearLayout(a)
    private val busyText = TextView(a)
    private val timeText = TextView(a)
    private val playBtn: MdiIconView
    private val loopBtn: MdiIconView
    private val clipStrip = LinearLayout(a)
    private val panel = LinearLayout(a)
    private val nav = LinearLayout(a)
    private val navCells = LinkedHashMap<String, LinearLayout>()
    private val sheet: ImgSheet

    // ---- pemutaran
    @Volatile private var playing = false
    /** Tanda berhenti per sesi putar — thread lama tidak bisa menghentikan sesi baru. */
    private var playStop = java.util.concurrent.atomic.AtomicBoolean(true)
    private var track: AudioTrack? = null
    private var playFrom = 0
    private var playTo = 0
    private var playLoop = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!playing) return
            val t = track
            val clip = selected()
            if (t != null && clip != null) {
                val len = (playTo - playFrom).coerceAtLeast(1)
                val head = runCatching { t.playbackHeadPosition }.getOrDefault(0).toLong() and 0xFFFFFFFFL
                val off = if (playLoop) (head % len).toInt() else head.toInt().coerceAtMost(len)
                s.playhead = (playFrom + off).coerceIn(0, clip.pcm.size)
                wave.followPlayhead()
                updateTime()
                wave.invalidate()
            }
            handler.postDelayed(this, 33L)
        }
    }

    // ---- rekam
    @Volatile private var recording = false
    private var recorder: AudioRecord? = null
    private val recordedChunks = ArrayList<ShortArray>()
    private var recordStart = 0L
    @Volatile private var recordLevel = 0
    private var recordLabel: TextView? = null
    private val recordTicker = object : Runnable {
        override fun run() {
            if (!recording) return
            recordLabel?.text = "● ${AudioDsp.fmtTime(System.currentTimeMillis() - recordStart)}  ·  level ${recordLevel * 100 / 32768}%"
            handler.postDelayed(this, 200L)
        }
    }

    private fun dp(v: Int) = kit.dp(v)

    init {
        column.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(kit.workspace)
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        // ---- top bar (ikon berdiri sendiri, tanpa kotak)
        val top = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(8), 0)
            background = c.shape(c.surface, 0)
        }
        top.addView(kit.icon("arrow-left", 23f, c.ink, "Kembali") { a.navigateBack() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), 0, dp(4), 0) }
        titles.addView(kit.text("Audio Editor", 15f, c.ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        subtitle.apply { textSize = 11f; setTextColor(c.muted); includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) }
        titles.addView(subtitle)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        undoBtn = kit.icon("undo", 22f, c.ink, "Undo") { undo() }
        redoBtn = kit.icon("redo", 22f, c.ink, "Redo") { redo() }
        top.addView(undoBtn, LinearLayout.LayoutParams(dp(42), dp(44)))
        top.addView(redoBtn, LinearLayout.LayoutParams(dp(42), dp(44)))
        top.addView(kit.icon("help-circle-outline", 21f, c.ink, "Bantuan") { showHelp() }, LinearLayout.LayoutParams(dp(40), dp(44)))
        top.addView(kit.primaryButton("Ekspor") { openExport() }, LinearLayout.LayoutParams(-2, dp(36)).apply { leftMargin = dp(4) })
        column.addView(top, LinearLayout.LayoutParams(-1, dp(52)))

        // ---- stage: waveform
        wave = AeWaveView(a)
        stage.addView(wave, FrameLayout.LayoutParams(-1, -1))
        buildEmpty()
        stage.addView(emptyState, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        busyText.apply {
            textSize = 12f
            setTextColor(c.ink)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = c.shape(c.surface, 16)
            visibility = View.GONE
        }
        stage.addView(busyText, FrameLayout.LayoutParams(-2, dp(32), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(30) })
        column.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- transport
        val transport = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            background = c.shape(c.surface, 0)
        }
        transport.addView(kit.icon("skip-previous", 24f, c.ink, "Ke awal") { stopPlayback(); s.playhead = 0; wave.followPlayhead(); refreshAll(false) },
            LinearLayout.LayoutParams(dp(44), dp(48)))
        playBtn = kit.icon("play", 26f, c.activeInk, "Putar") { togglePlay() }.apply { background = c.pressable(c.activeFill, 24) }
        transport.addView(playBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(2); rightMargin = dp(2) })
        transport.addView(kit.icon("stop", 24f, c.ink, "Berhenti") { stopPlayback() }, LinearLayout.LayoutParams(dp(44), dp(48)))
        timeText.apply {
            textSize = 13f
            setTextColor(c.ink)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            maxLines = 1
        }
        transport.addView(timeText, LinearLayout.LayoutParams(0, -2, 1f))
        loopBtn = kit.icon("repeat", 21f, c.ink, "Ulangi rentang") { s.loop = !s.loop; styleLoop(); a.toast(if (s.loop) "Putar rentang berulang" else "Ulang mati") }
        transport.addView(loopBtn, LinearLayout.LayoutParams(dp(40), dp(44)))
        transport.addView(kit.icon("magnify-minus-outline", 21f, c.ink, "Perkecil") { wave.zoomBy(1.6f) }, LinearLayout.LayoutParams(dp(40), dp(44)))
        transport.addView(kit.icon("magnify-plus-outline", 21f, c.ink, "Perbesar") { wave.zoomBy(1f / 1.6f) }, LinearLayout.LayoutParams(dp(40), dp(44)))
        column.addView(View(a).apply { setBackgroundColor(c.stroke) }, LinearLayout.LayoutParams(-1, 1))
        column.addView(transport, LinearLayout.LayoutParams(-1, dp(56)))

        // ---- klip (strip horizontal)
        clipStrip.orientation = LinearLayout.HORIZONTAL
        clipStrip.gravity = Gravity.CENTER_VERTICAL
        clipStrip.setPadding(dp(8), 0, dp(8), 0)
        column.addView(HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = c.shape(c.surface, 0)
            addView(clipStrip, FrameLayout.LayoutParams(-2, -1))
        }, LinearLayout.LayoutParams(-1, dp(52)))

        // ---- panel alat (tinggi tetap, tanpa scroll halaman)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(10), dp(6), dp(10), dp(4))
        panel.background = c.shape(c.surface, 0)
        column.addView(View(a).apply { setBackgroundColor(c.stroke) }, LinearLayout.LayoutParams(-1, 1))
        column.addView(panel, LinearLayout.LayoutParams(-1, dp(150)))

        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL
        nav.setPadding(dp(4), 0, dp(4), 0)
        nav.background = c.shape(c.surface, 0)
        for ((key, label, iconName) in listOf(
            Triple("edit", "Edit", "content-cut"),
            Triple("fade", "Fade", "chart-bell-curve"),
            Triple("volume", "Volume", "volume-high"),
            Triple("klip", "Klip", "playlist-music"),
            Triple("rekam", "Rekam", "microphone")
        )) {
            val cell = kit.navCell(iconName, label) { s.tab = key; renderPanel() }
            navCells[key] = cell
            nav.addView(cell, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        }
        column.addView(nav, LinearLayout.LayoutParams(-1, dp(58)))
        sheet = ImgSheet(kit, root)

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                stopPlayback()
                stopRecording(save = false)
                sheet.detach()
            }
        })
        styleLoop()
        refreshAll()
    }

    // ================================================================== model

    private fun selected(): AeClip? = s.clips.firstOrNull { it.id == s.selectedId } ?: s.clips.firstOrNull()?.also { s.selectedId = it.id }

    private fun selRange(clip: AeClip): Pair<Int, Int> {
        val a0 = s.selStart.coerceIn(0, clip.pcm.size)
        val b0 = s.selEnd.coerceIn(a0, clip.pcm.size)
        return if (b0 - a0 < 1) 0 to clip.pcm.size else a0 to b0
    }

    private fun hasPartialSelection(clip: AeClip): Boolean {
        val (x, y) = selRange(clip)
        return x > 0 || y < clip.pcm.size
    }

    private fun msOf(clip: AeClip, samples: Int): Long = if (clip.sampleRate <= 0) 0L else samples * 1000L / clip.sampleRate

    private fun selectClip(clip: AeClip?) {
        s.selectedId = clip?.id
        s.selStart = 0
        s.selEnd = clip?.pcm?.size ?: 0
        s.playhead = 0
        s.spp = 0f
        s.scroll = 0f
    }

    private fun snapshot() = AeSnap(s.clips.map { it.copy() }, s.selectedId, s.selStart, s.selEnd)

    private fun restore(snap: AeSnap) {
        s.clips.clear()
        s.clips.addAll(snap.clips.map { it.copy() })
        s.selectedId = snap.selectedId
        s.selStart = snap.selStart
        s.selEnd = snap.selEnd
        selected()?.let { s.playhead = s.playhead.coerceIn(0, it.pcm.size) }
    }

    /** Catat state sebelum perubahan. Riwayat dibatasi memori agar HP RAM kecil tetap aman. */
    private fun checkpoint() {
        s.undo.add(snapshot())
        s.redo.clear()
        trimHistory()
    }

    private fun trimHistory() {
        while (s.undo.size > 30) s.undo.removeAt(0)
        while (s.undo.size > 1 && retainedSamples() > AE_UNDO_BUDGET_SAMPLES) s.undo.removeAt(0)
    }

    private fun retainedSamples(): Long {
        val live = java.util.IdentityHashMap<ShortArray, Boolean>()
        s.clips.forEach { live[it.pcm] = true }
        val seen = java.util.IdentityHashMap<ShortArray, Boolean>()
        var total = 0L
        for (snap in s.undo + s.redo) for (cl in (snap as AeSnap).clips) {
            if (live.containsKey(cl.pcm) || seen.containsKey(cl.pcm)) continue
            seen[cl.pcm] = true
            total += cl.pcm.size
        }
        return total
    }

    private fun undo() {
        val last = s.undo.lastOrNull() as? AeSnap ?: run { a.toast("Tidak ada langkah untuk di-undo"); return }
        stopPlayback()
        s.undo.removeAt(s.undo.size - 1)
        s.redo.add(snapshot())
        restore(last)
        refreshAll()
    }

    private fun redo() {
        val last = s.redo.lastOrNull() as? AeSnap ?: run { a.toast("Tidak ada langkah untuk di-redo"); return }
        stopPlayback()
        s.redo.removeAt(s.redo.size - 1)
        s.undo.add(snapshot())
        restore(last)
        refreshAll()
    }

    /** Jalankan operasi PCM (di thread latar untuk audio panjang) lalu catat ke riwayat. */
    private fun edit(label: String, op: (AeClip) -> Unit) {
        val clip = selected() ?: run { a.toast("Impor audio dulu"); return }
        stopPlayback()
        checkpoint()
        op(clip)
        selected()?.let { cl ->
            s.selStart = s.selStart.coerceIn(0, cl.pcm.size)
            s.selEnd = s.selEnd.coerceIn(s.selStart, cl.pcm.size)
            s.playhead = s.playhead.coerceIn(0, cl.pcm.size)
        }
        refreshAll()
        a.toast(label)
    }

    // ================================================================== chrome

    private fun buildEmpty() {
        emptyState.orientation = LinearLayout.VERTICAL
        emptyState.gravity = Gravity.CENTER_HORIZONTAL
        emptyState.setPadding(dp(24), dp(20), dp(24), dp(20))
        emptyState.background = c.shape(c.surface, 22)
        emptyState.addView(kit.icon("waveform", 40f, c.ink), LinearLayout.LayoutParams(dp(64), dp(52)))
        emptyState.addView(kit.text("Edit audio dengan presisi", 16f, c.ink, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) })
        emptyState.addView(kit.text("Trim, fade, gain, gabung, dan rekam —\nsemua di HP, tanpa unggah.", 12f, c.muted).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) })
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(kit.primaryButton("Impor audio") { pickAudio(false) }, LinearLayout.LayoutParams(-2, dp(40)))
        row.addView(kit.secondaryButton("Dari video") { pickAudio(true) }, LinearLayout.LayoutParams(-2, dp(40)).apply { leftMargin = dp(8) })
        emptyState.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
    }

    private fun busy(msg: String?) {
        busyText.text = msg.orEmpty()
        busyText.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun styleLoop() {
        loopBtn.setTextColor(if (s.loop) c.activeInk else c.ink)
        loopBtn.background = if (s.loop) c.pressable(c.activeFill, 12) else c.pressable(Color.TRANSPARENT, 12)
    }

    private fun refreshAll(rebuildPanel: Boolean = true) {
        val clip = selected()
        emptyState.visibility = if (clip == null) View.VISIBLE else View.GONE
        subtitle.text = if (clip == null) "Belum ada audio" else
            "${clip.name} · ${AudioDsp.fmtTime(clip.durationMs(), false)} · ${clip.sampleRate} Hz" + if (clip.gainDb != 0f) String.format(Locale.US, " · %+.1f dB", clip.gainDb) else ""
        undoBtn.alpha = if (s.undo.isNotEmpty()) 1f else 0.35f
        redoBtn.alpha = if (s.redo.isNotEmpty()) 1f else 0.35f
        updateTime()
        renderClips()
        if (rebuildPanel) renderPanel()
        wave.invalidate()
    }

    private fun updateTime() {
        val clip = selected()
        if (clip == null) { timeText.text = "0:00.0 / 0:00.0"; return }
        val (x, y) = selRange(clip)
        val selInfo = if (hasPartialSelection(clip)) "\nrentang ${AudioDsp.fmtTime(msOf(clip, y - x))}" else ""
        timeText.text = "${AudioDsp.fmtTime(msOf(clip, s.playhead))} / ${AudioDsp.fmtTime(clip.durationMs())}$selInfo"
        timeText.textSize = if (selInfo.isEmpty()) 13f else 11.5f
    }

    private fun renderClips() {
        clipStrip.removeAllViews()
        for ((i, cl) in s.clips.withIndex()) {
            val on = cl.id == s.selectedId
            val chip = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                background = if (on) c.pressable(c.activeFill, 14) else c.pressable(c.surface2, 14)
                isClickable = true
                contentDescription = "Klip ${i + 1} ${cl.name}"
                setOnClickListener {
                    if (cl.id != s.selectedId) { stopPlayback(); selectClip(cl); refreshAll() }
                }
            }
            val ink = if (on) c.activeInk else c.ink
            chip.addView(kit.text("${i + 1}. ${cl.name}", 12f, ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(150) })
            chip.addView(kit.text(AudioDsp.fmtTime(cl.durationMs(), false), 10.5f, if (on) ink else c.muted).apply { setPadding(0, dp(2), 0, 0) })
            clipStrip.addView(chip, LinearLayout.LayoutParams(-2, dp(40)).apply { rightMargin = dp(6) })
        }
        clipStrip.addView(kit.pill("+ Impor", false) { pickAudio(false) }, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(6) })
        clipStrip.addView(kit.pill("+ Dari video", false) { pickAudio(true) }, LinearLayout.LayoutParams(-2, dp(36)))
    }

    private fun tiles(vararg t: View) {
        panel.addView(kit.tileRow(t.toList(), 58))
    }

    private fun renderPanel() {
        panel.removeAllViews()
        kit.styleNav(navCells, s.tab)
        val clip = selected()
        if (clip == null && s.tab != "rekam" && s.tab != "klip") {
            panel.addView(kit.text("Impor audio, ekstrak dari video, atau rekam suara untuk mulai.", 12f, c.muted).apply { setPadding(dp(4), dp(8), dp(4), 0) })
            tiles(
                kit.tile("music-note", "Impor audio") { pickAudio(false) },
                kit.tile("video-outline", "Dari video") { pickAudio(true) },
                kit.tile("microphone", "Rekam") { s.tab = "rekam"; renderPanel() }
            )
            return
        }
        when (s.tab) {
            "edit" -> renderEdit(clip!!)
            "fade" -> renderFade(clip!!)
            "volume" -> renderVolume(clip!!)
            "klip" -> renderClipPanel(clip)
            else -> renderRecord()
        }
    }

    private fun renderEdit(clip: AeClip) {
        val partial = hasPartialSelection(clip)
        tiles(
            kit.tile("content-cut", "Trim") {
                if (!partial) { a.toast("Pilih rentang dulu (tekan lama lalu geser di waveform)"); return@tile }
                edit("Trim: hanya rentang terpilih yang disimpan") { cl ->
                    val (x, y) = selRange(cl)
                    cl.pcm = AudioDsp.slice(cl.pcm, x, y)
                    s.selStart = 0; s.selEnd = cl.pcm.size; s.playhead = 0
                }
            },
            kit.tile("delete-outline", "Hapus rentang") {
                if (!partial) { a.toast("Pilih rentang yang akan dihapus"); return@tile }
                edit("Rentang dihapus") { cl ->
                    val (x, y) = selRange(cl)
                    cl.pcm = AudioDsp.deleteRange(cl.pcm, x, y)
                    s.selStart = 0; s.selEnd = cl.pcm.size; s.playhead = x.coerceAtMost(cl.pcm.size)
                }
            },
            kit.tile("call-split", "Split") {
                val cut = s.playhead
                if (cut <= 0 || cut >= clip.pcm.size) { a.toast("Taruh playhead di tengah klip untuk split"); return@tile }
                edit("Klip dibagi di ${AudioDsp.fmtTime(msOf(clip, cut))}") { cl ->
                    val right = AeClip(uri = cl.uri, name = cl.name + " (B)", pcm = AudioDsp.slice(cl.pcm, cut, cl.pcm.size), sampleRate = cl.sampleRate, gainDb = cl.gainDb)
                    cl.pcm = AudioDsp.slice(cl.pcm, 0, cut)
                    cl.name = cl.name + " (A)"
                    s.clips.add(s.clips.indexOfFirst { it.id == cl.id } + 1, right)
                    s.selStart = 0; s.selEnd = cl.pcm.size
                }
            },
            kit.tile("content-copy", "Salin rentang") {
                edit("Rentang disalin sebagai klip baru") { cl ->
                    val (x, y) = selRange(cl)
                    val copy = AeClip(uri = cl.uri, name = cl.name + " (salinan)", pcm = AudioDsp.slice(cl.pcm, x, y), sampleRate = cl.sampleRate, gainDb = cl.gainDb)
                    s.clips.add(s.clips.indexOfFirst { it.id == cl.id } + 1, copy)
                }
            }
        )
        tiles(
            kit.tile("volume-off", "Bisukan") {
                if (!partial) { a.toast("Pilih rentang yang akan dibisukan"); return@tile }
                edit("Rentang dibisukan") { cl -> val (x, y) = selRange(cl); cl.pcm = AudioDsp.silenceRange(cl.pcm, x, y) }
            },
            kit.tile("select-all", "Pilih semua") { s.selStart = 0; s.selEnd = clip.pcm.size; refreshAll() },
            kit.tile("ray-start-arrow", "Awal = ▶") {
                s.selStart = s.playhead.coerceAtMost(clip.pcm.size - 1)
                if (s.selEnd <= s.selStart) s.selEnd = clip.pcm.size
                refreshAll()
            },
            kit.tile("ray-end-arrow", "Akhir = ▶") {
                s.selEnd = s.playhead.coerceAtLeast(1)
                if (s.selStart >= s.selEnd) s.selStart = 0
                refreshAll()
            }
        )
    }

    private fun renderFade(clip: AeClip) {
        panel.addView(kit.slider("Durasi fade", 1, 100, (s.fadeSec * 10).roundToInt().coerceIn(1, 100), 20, { String.format(Locale.US, "%.1fs", it / 10f) }) { v -> s.fadeSec = v / 10f })
        val n = (s.fadeSec * clip.sampleRate).toInt().coerceIn(1, clip.pcm.size)
        tiles(
            kit.tile("arrow-top-right", "Fade in awal") { edit("Fade in ${String.format(Locale.US, "%.1f", s.fadeSec)} dtk") { cl -> cl.pcm = AudioDsp.fadeRange(cl.pcm, 0, n.coerceAtMost(cl.pcm.size), true) } },
            kit.tile("arrow-bottom-right", "Fade out akhir") { edit("Fade out ${String.format(Locale.US, "%.1f", s.fadeSec)} dtk") { cl -> cl.pcm = AudioDsp.fadeRange(cl.pcm, cl.pcm.size - n.coerceAtMost(cl.pcm.size), cl.pcm.size, false) } },
            kit.tile("chart-bell-curve", "Fade in rentang") {
                if (!hasPartialSelection(clip)) { a.toast("Pilih rentang dulu"); return@tile }
                edit("Fade in pada rentang") { cl -> val (x, y) = selRange(cl); cl.pcm = AudioDsp.fadeRange(cl.pcm, x, y, true) }
            },
            kit.tile("chart-bell-curve", "Fade out rentang") {
                if (!hasPartialSelection(clip)) { a.toast("Pilih rentang dulu"); return@tile }
                edit("Fade out pada rentang") { cl -> val (x, y) = selRange(cl); cl.pcm = AudioDsp.fadeRange(cl.pcm, x, y, false) }
            }
        )
    }

    private fun renderVolume(clip: AeClip) {
        var gainStartSnap = false
        panel.addView(kit.slider("Gain klip", -24, 24, clip.gainDb.roundToInt(), 0, { String.format(Locale.US, "%+d dB", it) },
            onStart = { if (!gainStartSnap) { checkpoint(); gainStartSnap = true } },
            onStop = { gainStartSnap = false; refreshAll(false) }
        ) { v -> clip.gainDb = v.toFloat(); wave.invalidate() })
        panel.addView(kit.slider("Gain rentang", -24, 24, s.rangeGainDb.roundToInt(), 6, { String.format(Locale.US, "%+d dB", it) }) { v -> s.rangeGainDb = v.toFloat() })
        val (x, y) = selRange(clip)
        tiles(
            kit.tile("tune-vertical", "Terapkan rentang") {
                edit(String.format(Locale.US, "Gain %+.0f dB diterapkan", s.rangeGainDb)) { cl -> val (p0, p1) = selRange(cl); cl.pcm = AudioDsp.gainRange(cl.pcm, p0, p1, s.rangeGainDb) }
            },
            kit.tile("chart-histogram", "Normalisasi") { edit("Dinormalisasi ke -0.2 dBFS") { cl -> cl.pcm = normalizePcm(applyGain(cl.pcm, cl.gainDb)); cl.gainDb = 0f } },
            // Info level rentang terpilih (puncak & RMS) agar keputusan gain terukur.
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = c.shape(c.surface2, 14)
                addView(kit.text(String.format(Locale.US, "Puncak %.1f dB", AudioDsp.peakDb(clip.pcm, x, y) + clip.gainDb), 10.5f, c.ink, true).apply { gravity = Gravity.CENTER })
                addView(kit.text(String.format(Locale.US, "RMS %.1f dB", AudioDsp.rmsDb(clip.pcm, x, y) + clip.gainDb), 10.5f, c.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
            }
        )
    }

    private fun renderClipPanel(clip: AeClip?) {
        tiles(
            kit.tile("music-note", "Impor audio") { pickAudio(false) },
            kit.tile("video-outline", "Dari video") { pickAudio(true) },
            kit.tile("playlist-plus", "Gabung semua") { mergeAll() },
            kit.tile("call-merge", "Mix di ▶") { mixNextAtPlayhead() }
        )
        if (clip == null) return
        val idx = s.clips.indexOfFirst { it.id == clip.id }
        tiles(
            kit.tile("chevron-left", "Geser kiri") {
                if (idx <= 0) { a.toast("Sudah paling awal"); return@tile }
                checkpoint(); s.clips.removeAt(idx); s.clips.add(idx - 1, clip); refreshAll()
            },
            kit.tile("chevron-right", "Geser kanan") {
                if (idx >= s.clips.size - 1) { a.toast("Sudah paling akhir"); return@tile }
                checkpoint(); s.clips.removeAt(idx); s.clips.add(idx + 1, clip); refreshAll()
            },
            kit.tile("content-duplicate", "Duplikat") {
                checkpoint(); s.clips.add(idx + 1, clip.copy(id = UUID.randomUUID().toString(), name = clip.name + " (2)")); refreshAll()
            },
            kit.tile("delete-outline", "Hapus klip") {
                stopPlayback(); checkpoint(); s.clips.removeAt(idx); selectClip(s.clips.getOrNull(idx.coerceAtMost(s.clips.size - 1))); refreshAll()
            }
        )
    }

    private fun renderRecord() {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), 0) }
        val label = kit.text(if (recording) "● Merekam…" else "Rekam voice-over dari mikrofon (44,1 kHz mono). Hasil menjadi klip baru.", 12.5f, if (recording) c.ink else c.muted, recording)
        recordLabel = label
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(row)
        val btn = if (recording) kit.primaryButton("■  Stop & simpan") { stopRecording(save = true) }
        else kit.primaryButton("●  Mulai rekam") { startRecording() }
        panel.addView(btn, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(14) })
        if (recording) panel.addView(kit.secondaryButton("Batalkan rekaman") { stopRecording(save = false) }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(6) })
    }

    // ================================================================== clips

    private fun pickAudio(fromVideo: Boolean) {
        audioEditorPickPending = pick@{ uri ->
            if (uri == null) return@pick
            busy(if (fromVideo) "Mengekstrak audio…" else "Memuat audio…")
            Thread {
                val r = runCatching { a.decodeUriToPcm(uri) }
                a.runOnUiThread {
                    busy(null)
                    r.onSuccess { (pcm, sr, name) ->
                        val clip = AeClip(uri = uri, name = if (fromVideo) "Audio · $name" else name, pcm = pcm, sampleRate = sr)
                        checkpoint()
                        s.clips.add(clip)
                        selectClip(clip)
                        refreshAll()
                        if (clip.durationMs() >= 10 * 60 * 1000L - 500) a.toast("Audio dibatasi 10 menit pertama agar memori aman")
                    }.onFailure { a.toast("Gagal memuat: ${it.message ?: it.javaClass.simpleName}") }
                }
            }.start()
        }
        @Suppress("DEPRECATION")
        a.startActivityForResult(
            android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = if (fromVideo) "video/*" else "audio/*"
            },
            if (fromVideo) AE_PICK_VIDEO else AE_PICK_AUDIO
        )
    }

    /** Gabung semua klip berurutan menjadi satu (sample rate disamakan, gain klip diterapkan). */
    private fun mergeAll() {
        if (s.clips.size < 2) { a.toast("Butuh minimal 2 klip"); return }
        stopPlayback()
        val sr = s.clips.first().sampleRate
        val parts = s.clips.toList()
        busy("Menggabungkan…")
        Thread {
            val merged = runCatching { AudioDsp.concat(parts.map { AudioDsp.resample(applyGain(it.pcm, it.gainDb), it.sampleRate, sr) }) }
            a.runOnUiThread {
                busy(null)
                merged.onSuccess { pcm ->
                    checkpoint()
                    s.clips.clear()
                    val clip = AeClip(uri = null, name = "Gabungan ${parts.size} klip", pcm = pcm, sampleRate = sr)
                    s.clips.add(clip)
                    selectClip(clip)
                    refreshAll()
                    a.toast("${parts.size} klip digabung")
                }.onFailure { a.toast("Gagal menggabung: ${if (it is OutOfMemoryError) "memori tidak cukup" else it.message}") }
            }
        }.start()
    }

    /** Campur klip berikutnya ke klip aktif mulai dari playhead (voice-over di atas musik, dsb.). */
    private fun mixNextAtPlayhead() {
        val clip = selected() ?: run { a.toast("Pilih klip dasar"); return }
        val idx = s.clips.indexOfFirst { it.id == clip.id }
        val over = s.clips.getOrNull(idx + 1) ?: run { a.toast("Taruh klip yang akan dicampur tepat setelah klip aktif"); return }
        edit("\"${over.name}\" dicampur di ${AudioDsp.fmtTime(msOf(clip, s.playhead))}") { cl ->
            val src = AudioDsp.resample(applyGain(over.pcm, over.gainDb), over.sampleRate, cl.sampleRate)
            cl.pcm = AudioDsp.mixAt(cl.pcm, src, s.playhead)
            s.clips.removeAll { it.id == over.id }
        }
    }

    // ================================================================== playback

    private fun togglePlay() {
        if (playing) { stopPlayback(); return }
        val clip = selected() ?: run { a.toast("Impor audio dulu"); return }
        val (x, y) = selRange(clip)
        val partial = hasPartialSelection(clip)
        // Putar rentang bila ada seleksi; jika tidak, dari playhead sampai akhir.
        val from = if (partial && (s.playhead < x || s.playhead >= y)) x else s.playhead.coerceIn(0, clip.pcm.size - 1)
        val to = if (partial) y else clip.pcm.size
        startPlayback(clip, from, if (to <= from) clip.pcm.size else to, partial && s.loop)
    }

    private fun startPlayback(clip: AeClip, from: Int, to: Int, loop: Boolean) {
        stopPlayback()
        val sr = clip.sampleRate
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { a.toast("Pemutar audio tidak tersedia"); return }
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse { a.toast("Gagal memutar: ${it.message}"); return }
        track = t
        playFrom = from
        playTo = to
        playLoop = loop
        playing = true
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        playStop = stop
        val pcm = clip.pcm
        val factor = if (abs(clip.gainDb) < 0.05f) 1f else Math.pow(10.0, clip.gainDb / 20.0).toFloat()
        Thread {
            val chunk = ShortArray(4096)
            runCatching {
                t.play()
                do {
                    var pos = from
                    while (pos < to && !stop.get()) {
                        val n = min(chunk.size, to - pos)
                        // Gain klip diterapkan per potongan — tanpa menyalin seluruh audio.
                        if (factor == 1f) System.arraycopy(pcm, pos, chunk, 0, n)
                        else for (i in 0 until n) chunk[i] = (pcm[pos + i] * factor).toInt().coerceIn(-32768, 32767).toShort()
                        val w = t.write(chunk, 0, n)
                        if (w < 0) { stop.set(true); break }
                        pos += n
                    }
                } while (loop && !stop.get())
                if (!stop.get()) {
                    // Tunggu sisa buffer selesai diputar.
                    val total = (to - from).toLong()
                    var guard = 0
                    while (!stop.get() && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < total && guard < 400) { Thread.sleep(20); guard++ }
                }
            }
            val ended = !stop.get()
            handler.post { if (track === t) finishPlayback(endReached = ended) }
        }.start()
        handler.post(ticker)
        playBtn.setIconName("pause")
        playBtn.contentDescription = "Jeda"
    }

    private fun finishPlayback(endReached: Boolean) {
        val t = track ?: return
        track = null
        playing = false
        playStop.set(true)
        runCatching { t.pause(); t.flush() }
        runCatching { t.stop() }
        runCatching { t.release() }
        handler.removeCallbacks(ticker)
        if (endReached) s.playhead = playTo.coerceAtMost(selected()?.pcm?.size ?: playTo)
        playBtn.setIconName("play")
        playBtn.contentDescription = "Putar"
        updateTime()
        wave.invalidate()
    }

    private fun stopPlayback() {
        if (track == null) return
        playStop.set(true)
        finishPlayback(endReached = false)
    }

    // ================================================================== recording

    private fun startRecording() {
        if (recording) return
        if (a.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), AE_PERM_MIC)
            a.toast("Izinkan mikrofon lalu ketuk Mulai rekam lagi")
            return
        }
        stopPlayback()
        val sr = 44100
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { a.toast("Mikrofon tidak tersedia"); return }
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        }.getOrElse { a.toast("Gagal membuka mikrofon: ${it.message}"); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); a.toast("Mikrofon sedang dipakai aplikasi lain"); return }
        recorder = rec
        synchronized(recordedChunks) { recordedChunks.clear() }
        runCatching { rec.startRecording() }.onFailure { rec.release(); recorder = null; a.toast("Gagal merekam: ${it.message}"); return }
        recording = true
        recordStart = System.currentTimeMillis()
        Thread {
            val buf = ShortArray(minBuf)
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    var peak = 0
                    for (i in 0 until n) peak = max(peak, abs(buf[i].toInt()))
                    recordLevel = peak
                    synchronized(recordedChunks) { recordedChunks.add(buf.copyOf(n)) }
                }
            }
        }.start()
        handler.post(recordTicker)
        renderPanel()
    }

    private fun stopRecording(save: Boolean) {
        if (!recording) return
        recording = false
        handler.removeCallbacks(recordTicker)
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        val chunks = synchronized(recordedChunks) { ArrayList(recordedChunks).also { recordedChunks.clear() } }
        if (save) {
            val pcm = AudioDsp.concat(chunks)
            if (pcm.size < 4410) a.toast("Rekaman terlalu pendek")
            else {
                checkpoint()
                val clip = AeClip(uri = null, name = "Voice-over ${s.clips.size + 1}", pcm = pcm, sampleRate = 44100)
                s.clips.add(clip)
                selectClip(clip)
                a.toast("Rekaman disimpan sebagai klip")
            }
        }
        if (root.isAttachedToWindow) refreshAll()
    }

    // ================================================================== export

    private fun exportPcm(): Pair<ShortArray, Int>? {
        val clip = selected() ?: return null
        return if (s.exportScope == "semua" && s.clips.size > 1) {
            val sr = s.clips.first().sampleRate
            AudioDsp.concat(s.clips.map { AudioDsp.resample(applyGain(it.pcm, it.gainDb), it.sampleRate, sr) }) to sr
        } else applyGain(clip.pcm, clip.gainDb) to clip.sampleRate
    }

    private fun openExport() {
        if (selected() == null) { a.toast("Belum ada audio untuk diekspor"); return }
        stopPlayback()
        sheet.open("Ekspor audio") { p -> renderExport(p) }
    }

    /** Isi lembar ekspor; anak pertama [p] adalah header lembar, sisanya dibangun ulang saat opsi berubah. */
    private fun renderExport(p: LinearLayout) {
        val again = { while (p.childCount > 1) p.removeViewAt(1); renderExport(p) }
        if (s.clips.size > 1) {
            p.addView(kit.segmented(listOf("aktif" to "Klip aktif", "semua" to "Gabung ${s.clips.size} klip"), s.exportScope) { k -> s.exportScope = k; again() },
                LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(8) })
        }
        p.addView(kit.segmented(listOf("m4a" to "M4A (AAC)", "wav" to "WAV (lossless)"), s.exportFormat) { k -> s.exportFormat = k; again() },
            LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(8) })
        val durMs = if (s.exportScope == "semua" && s.clips.size > 1) s.clips.sumOf { it.durationMs() } else selected()?.durationMs() ?: 0L
        val sr = if (s.exportScope == "semua") s.clips.firstOrNull()?.sampleRate ?: 44100 else selected()?.sampleRate ?: 44100
        if (s.exportFormat == "m4a") {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (br in listOf(96_000, 128_000, 192_000, 256_000)) {
                row.addView(kit.pill("${br / 1000} kbps", s.bitrate == br) { s.bitrate = br; again() }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            p.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            val outSr = AudioDsp.aacSampleRate(sr)
            p.addView(kit.text("AAC-LC mono ${outSr} Hz · perkiraan ${humanBytes((s.bitrate / 8L) * durMs / 1000L)} · ${AudioDsp.fmtTime(durMs, false)}" +
                if (outSr != sr) "\nSample rate ${sr} Hz disesuaikan ke ${outSr} Hz (batas AAC)." else "", 11.5f, c.muted).apply { setPadding(dp(4), 0, 0, dp(8)) })
        } else {
            p.addView(kit.text("PCM 16-bit mono ${sr} Hz · ${humanBytes(durMs * sr * 2 / 1000L)} · kualitas asli tanpa kompresi", 11.5f, c.muted).apply { setPadding(dp(4), 0, 0, dp(8)) })
        }
        p.addView(kit.primaryButton("Ekspor ${if (s.exportFormat == "m4a") "M4A" else "WAV"}") { doExport() }, LinearLayout.LayoutParams(-1, dp(44)))
        val last = s.lastExport
        if (last != null && last.exists()) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), 0, 0) }
            row.addView(kit.text("Terakhir: ${s.lastExportLabel ?: last.name} · ${humanBytes(last.length())}", 11.5f, c.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(kit.icon("share-variant", 19f, c.ink, "Bagikan") { runCatching { a.shareFile(last) }.onFailure { a.toast("Gagal membagikan: ${it.message}") } }
                .apply { background = c.pressable(c.surface2, 12) }, LinearLayout.LayoutParams(dp(44), dp(40)))
            p.addView(row)
        }
        p.addView(kit.text(
            (if (android.os.Build.VERSION.SDK_INT >= 29) "Disimpan di Music/GITLS (muncul di aplikasi musik & file manager), salinan juga di folder aplikasi."
            else "Android 9 ke bawah: disimpan di Music/GITLS bila izin penyimpanan diberikan, selain itu di folder aplikasi.") +
                " MP3 tidak tersedia (tidak ada encoder bawaan Android).", 11f, c.muted).apply { setPadding(dp(4), dp(8), 0, 0) })
    }

    private fun doExport() {
        val format = s.exportFormat
        val bitrate = s.bitrate
        val base = (selected()?.name ?: "audio").substringBeforeLast('.').ifBlank { "audio" }
        a.ensureLegacyStorageAccess() // OPEN-11
        busy("Mengekspor ${format.uppercase(Locale.ROOT)}…")
        sheet.close()
        Thread {
            val r = runCatching {
                val (pcm, sr) = exportPcm() ?: error("Tidak ada audio")
                if (format == "wav") a.writeWavFile(pcm, sr, base) else a.encodeAacM4a(pcm, sr, bitrate, base)
            }
            // Salin ke Music/GITLS agar terlihat di aplikasi musik & file manager (Android 10+).
            val published = r.getOrNull()?.let { f -> runCatching { VePublish.copy(a, f) }.getOrNull() }
            a.runOnUiThread {
                busy(null)
                r.onSuccess { f ->
                    s.lastExport = f
                    s.lastExportLabel = published?.label
                    a.toast("Tersimpan: ${published?.label ?: f.name} (${humanBytes(f.length())})")
                    if (root.isAttachedToWindow) sheet.open("Ekspor audio") { p -> renderExport(p) }
                }.onFailure { e -> a.toast("Ekspor gagal: ${if (e is OutOfMemoryError) "memori tidak cukup" else e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun showHelp() {
        a.themedDialog()
            .setTitle("Audio Editor")
            .setMessage(
                "• Ketuk waveform: pindahkan playhead. Tekan lama lalu geser: buat rentang. Tarik pegangan rentang untuk merapikan.\n" +
                    "• Dua jari atau tombol kaca pembesar: zoom; geser satu jari saat diperbesar. Ketuk dua kali: pas layar.\n" +
                    "• Putar: memutar rentang (bila ada) atau dari playhead. Ikon ulang: putar rentang berulang.\n" +
                    "• Edit: trim, hapus rentang, split di playhead, salin rentang, bisukan.\n" +
                    "• Fade & Volume: fade in/out halus, gain klip (non-destruktif), gain rentang, normalisasi.\n" +
                    "• Klip: urutkan, duplikat, gabung semua (sample rate disamakan), mix klip berikutnya di playhead.\n" +
                    "• Semua perubahan bisa di-Undo/Redo. Ekspor WAV (lossless) atau M4A AAC 96–256 kbps."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ================================================================== waveform view

    private inner class AeWaveView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rulerH = dp(24).toFloat()
        private var mode = 0 // 0 idle, 1 handle start, 2 handle end, 3 pan, 4 select, 5 pinch
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var moved = false
        private var anchor = 0
        private var startSpp = 0f
        private var startDist = 1f
        private var anchorSample = 0f
        private var lastTap = 0L
        private val longPress = Runnable {
            if (mode == 3 && !moved) {
                val clip = selected() ?: return@Runnable
                mode = 4
                anchor = sampleAt(downX, clip)
                s.selStart = anchor
                s.selEnd = anchor
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }

        init { textPaint.textSize = dp(10).toFloat() }

        private fun fitSpp(clip: AeClip): Float = (clip.pcm.size.toFloat() / width.coerceAtLeast(1)).coerceAtLeast(0.05f)
        private fun spp(clip: AeClip): Float = if (s.spp <= 0f) fitSpp(clip) else s.spp
        private fun maxScroll(clip: AeClip): Float = (clip.pcm.size - width * spp(clip)).coerceAtLeast(0f)
        private fun xOf(sample: Int, clip: AeClip): Float = (sample - s.scroll) / spp(clip)
        fun sampleAt(x: Float, clip: AeClip): Int = (s.scroll + x * spp(clip)).roundToInt().coerceIn(0, clip.pcm.size)

        fun zoomBy(f: Float, focusX: Float = width / 2f) {
            val clip = selected() ?: return
            val focus = s.scroll + focusX * spp(clip)
            val ns = (spp(clip) * f).coerceIn(0.05f, fitSpp(clip))
            s.spp = if (ns >= fitSpp(clip) * 0.999f) 0f else ns
            s.scroll = (focus - focusX * spp(clip)).coerceIn(0f, maxScroll(clip))
            invalidate()
        }

        /** Gulir otomatis agar playhead tetap terlihat saat pemutaran. */
        fun followPlayhead() {
            val clip = selected() ?: return
            if (s.spp <= 0f) return
            val x = xOf(s.playhead, clip)
            if (x < 0 || x > width * 0.9f) s.scroll = (s.playhead - width * 0.1f * spp(clip)).coerceIn(0f, maxScroll(clip))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(kit.workspace)
            val clip = selected() ?: return
            s.scroll = s.scroll.coerceIn(0f, maxScroll(clip))
            val w = width.toFloat()
            val h = height.toFloat()
            val top = rulerH
            val mid = top + (h - top) / 2f
            val amp = (h - top) * 0.44f
            val sp = spp(clip)
            val (sa, sb) = selRange(clip)
            val partial = hasPartialSelection(clip)
            // Area gelombang
            paint.style = Paint.Style.FILL
            paint.color = c.surface
            canvas.drawRect(0f, top, w, h, paint)
            if (partial) {
                paint.color = if (c.dark) Color.argb(46, 255, 255, 255) else Color.argb(26, 0, 0, 0)
                canvas.drawRect(xOf(sa, clip).coerceAtLeast(0f), top, xOf(sb, clip).coerceAtMost(w), h, paint)
            }
            // Garis tengah
            paint.color = c.stroke
            canvas.drawRect(0f, mid - 0.5f, w, mid + 0.5f, paint)
            // Waveform: puncak per piksel dari cache (cepat) atau langsung dari PCM saat zoom dekat.
            val gain = if (abs(clip.gainDb) < 0.05f) 1f else Math.pow(10.0, clip.gainDb / 20.0).toFloat()
            val peaks = if (sp >= AE_PEAK_BUCKET) s.peaks.getOrPut(clip.pcm) { AudioDsp.peaks(clip.pcm, AE_PEAK_BUCKET) } else null
            paint.strokeWidth = 1f
            val cols = width
            for (px in 0 until cols) {
                val s0 = (s.scroll + px * sp).toInt()
                val s1 = (s.scroll + (px + 1) * sp).toInt().coerceAtLeast(s0 + 1)
                if (s0 >= clip.pcm.size) break
                var peak = 0
                if (peaks != null) {
                    val b0 = s0 / AE_PEAK_BUCKET
                    val b1 = min(peaks.size, (s1 + AE_PEAK_BUCKET - 1) / AE_PEAK_BUCKET).coerceAtLeast(b0 + 1)
                    for (b in b0 until min(b1, peaks.size)) peak = max(peak, peaks[b].toInt())
                } else {
                    for (i in s0 until min(s1, clip.pcm.size)) peak = max(peak, abs(clip.pcm[i].toInt()))
                }
                val v = ((peak * gain) / 32768f).coerceAtMost(1f) * amp
                val inSel = !partial || (s0 in sa until sb)
                paint.color = if (inSel) c.ink else c.muted
                canvas.drawRect(px.toFloat(), mid - v - 0.5f, px + 1f, mid + v + 0.5f, paint)
            }
            drawRuler(canvas, clip, w)
            // Pegangan rentang
            if (partial) for (sample in intArrayOf(sa, sb)) {
                val x = xOf(sample, clip)
                if (x < -dp(12) || x > w + dp(12)) continue
                paint.color = c.ink
                canvas.drawRect(x - dp(1), top, x + dp(1), h, paint)
                val r = RectF(x - dp(7), h - dp(30), x + dp(7), h - dp(6))
                canvas.drawRoundRect(r, dp(4).toFloat(), dp(4).toFloat(), paint)
                paint.color = c.surface
                canvas.drawRect(x - dp(2), h - dp(24), x - dp(1), h - dp(12), paint)
                canvas.drawRect(x + dp(1), h - dp(24), x + dp(2), h - dp(12), paint)
            }
            // Playhead
            val px = xOf(s.playhead, clip)
            if (px >= -2f && px <= w + 2f) {
                paint.color = if (c.dark) Color.WHITE else Color.BLACK
                canvas.drawRect(px - dp(1), top - dp(4), px + dp(1), h, paint)
                val tri = Path().apply {
                    moveTo(px - dp(6), top - dp(10)); lineTo(px + dp(6), top - dp(10)); lineTo(px, top - dp(2)); close()
                }
                canvas.drawPath(tri, paint)
            }
            // Indikator zoom
            if (s.spp > 0f) {
                textPaint.color = c.muted
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(String.format(Locale.US, "%.0f×", fitSpp(clip) / sp), w - dp(6), h - dp(6), textPaint)
            }
        }

        private fun drawRuler(canvas: Canvas, clip: AeClip, w: Float) {
            paint.color = c.surface2
            canvas.drawRect(0f, 0f, w, rulerH, paint)
            val sr = clip.sampleRate.coerceAtLeast(1)
            val pxPerSec = sr / spp(clip)
            val steps = floatArrayOf(0.01f, 0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f, 600f)
            val step = steps.firstOrNull { it * pxPerSec >= dp(64) } ?: 600f
            val startSec = s.scroll / sr
            var t = (startSec / step).toInt() * step
            textPaint.color = c.muted
            textPaint.textAlign = Paint.Align.LEFT
            paint.color = c.muted
            while (true) {
                val x = (t * sr - s.scroll) / spp(clip)
                if (x > w) break
                if (x >= -dp(40)) {
                    canvas.drawRect(x, rulerH - dp(7), x + 1f, rulerH, paint)
                    val label = if (step < 1f) String.format(Locale.US, "%.2fs", t).replace(".00s", "s") else AudioDsp.fmtTime((t * 1000).toLong(), false)
                    canvas.drawText(label, x + dp(3), rulerH - dp(9), textPaint)
                }
                t += step
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val clip = selected() ?: return false
            parent?.requestDisallowInterceptTouchEvent(true)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; lastX = e.x; moved = false
                    val (sa, sb) = selRange(clip)
                    val partial = hasPartialSelection(clip)
                    val grab = dp(22)
                    mode = when {
                        partial && abs(e.x - xOf(sa, clip)) <= grab && abs(e.x - xOf(sa, clip)) <= abs(e.x - xOf(sb, clip)) -> 1
                        partial && abs(e.x - xOf(sb, clip)) <= grab -> 2
                        else -> 3
                    }
                    if (mode == 3) postDelayed(longPress, 420L)
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount >= 2) {
                    removeCallbacks(longPress)
                    mode = 5
                    startSpp = spp(clip)
                    startDist = abs(e.getX(0) - e.getX(1)).coerceAtLeast(dp(20).toFloat())
                    val fx = (e.getX(0) + e.getX(1)) / 2f
                    anchorSample = s.scroll + fx * startSpp
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && hypot(e.x - downX, e.y - downY) > dp(6)) { moved = true; if (mode == 3) removeCallbacks(longPress) }
                    when (mode) {
                        1 -> { s.selStart = sampleAt(e.x, clip).coerceAtMost(s.selEnd - 1); updateTime() }
                        2 -> { s.selEnd = sampleAt(e.x, clip).coerceAtLeast(s.selStart + 1); updateTime() }
                        3 -> if (moved) { s.scroll = (s.scroll - (e.x - lastX) * spp(clip)).coerceIn(0f, maxScroll(clip)) }
                        4 -> {
                            val cur = sampleAt(e.x, clip)
                            s.selStart = min(anchor, cur); s.selEnd = max(anchor, cur)
                            updateTime()
                        }
                        5 -> if (e.pointerCount >= 2) {
                            val dist = abs(e.getX(0) - e.getX(1)).coerceAtLeast(dp(20).toFloat())
                            val fx = (e.getX(0) + e.getX(1)) / 2f
                            val ns = (startSpp * startDist / dist).coerceIn(0.05f, fitSpp(clip))
                            s.spp = if (ns >= fitSpp(clip) * 0.999f) 0f else ns
                            s.scroll = (anchorSample - fx * spp(clip)).coerceIn(0f, maxScroll(clip))
                        }
                    }
                    lastX = e.x
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    if (e.actionMasked == MotionEvent.ACTION_UP && mode == 3 && !moved) {
                        val now = e.eventTime
                        if (now - lastTap < 280L) { lastTap = 0L; s.spp = 0f; s.scroll = 0f }
                        else {
                            lastTap = now
                            val wasPlaying = playing
                            stopPlayback()
                            s.playhead = sampleAt(e.x, clip)
                            if (wasPlaying) togglePlay()
                        }
                    }
                    if (mode == 4 && s.selEnd - s.selStart < clip.sampleRate / 50) { s.selStart = 0; s.selEnd = clip.pcm.size }
                    val changedSel = mode == 1 || mode == 2 || mode == 4
                    mode = 0
                    if (changedSel) refreshAll() else { updateTime(); invalidate() }
                }
                MotionEvent.ACTION_POINTER_UP -> if (mode == 5) mode = 0
            }
            return true
        }
    }
}

// ----- PCM / file helpers -----

/**
 * Enkode PCM 16-bit mono ke M4A (AAC-LC) dengan MediaCodec + MediaMuxer.
 * Sample rate di luar dukungan AAC disamakan otomatis (resample linear).
 */
internal fun MainActivity.encodeAacM4a(pcmIn: ShortArray, sampleRateIn: Int, bitrate: Int, baseName: String): File {
    val sr = AudioDsp.aacSampleRate(sampleRateIn)
    val pcm = AudioDsp.resample(pcmIn, sampleRateIn, sr)
    val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
    if (!dir.exists()) dir.mkdirs()
    val safe = baseName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").take(40).ifBlank { "audio" }
    val out = File(dir, "${safe}_${System.currentTimeMillis()}.m4a")
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate.coerceIn(32_000, 320_000))
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    var muxer: MediaMuxer? = null
    var muxerStarted = false
    var trackIndex = -1
    try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var inputPos = 0
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex) ?: error("Buffer encoder kosong")
                    buf.clear()
                    val samples = min(buf.remaining() / 2, pcm.size - inputPos)
                    val ptsUs = inputPos * 1_000_000L / sr
                    if (samples <= 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, inputPos, samples)
                        codec.queueInputBuffer(inIndex, 0, samples * 2, ptsUs, 0)
                        inputPos += samples
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) error("Format encoder berubah dua kali")
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val enc = codec.getOutputBuffer(outIndex) ?: error("Output encoder kosong")
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        enc.position(info.offset)
                        enc.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, enc, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
    } catch (e: Throwable) {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        out.delete()
        throw e
    }
    runCatching { codec.stop() }
    runCatching { codec.release() }
    runCatching { muxer?.stop() }
    runCatching { muxer?.release() }
    if (!out.exists() || out.length() == 0L) { out.delete(); error("Hasil M4A kosong") }
    return out
}

internal fun MainActivity.playPcm(pcm: ShortArray, sampleRate: Int, onCreate: (AudioTrack) -> Unit) {
    val minBuf = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    val track = AudioTrack(
        AudioManager.STREAM_MUSIC,
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        max(minBuf, pcm.size * 2),
        AudioTrack.MODE_STREAM
    )
    onCreate(track)
    track.play()
    var offset = 0
    val chunk = 2048
    while (offset < pcm.size) {
        val n = min(chunk, pcm.size - offset)
        track.write(pcm, offset, n)
        offset += n
    }
    // let drain
    try {
        Thread.sleep((pcm.size * 1000L / sampleRate).coerceAtMost(30_000L))
    } catch (_: Exception) {
    }
    runCatching { track.stop() }
    runCatching { track.release() }
}

/**
 * Decode audio track from audio/video URI to mono 16-bit PCM via MediaCodec.
 */
/** [failIfTooLong] = tolak (bukan potong diam-diam) audio > 10 menit; dipakai konversi batch. */
internal fun MainActivity.decodeUriToPcm(uri: Uri, failIfTooLong: Boolean = false): Triple<ShortArray, Int, String> {
    val name = runCatching { displayNameOf(uri) }.getOrDefault(uri.lastPathSegment ?: "audio")
    val extractor = MediaExtractor()
    contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        extractor.setDataSource(pfd.fileDescriptor)
    } ?: extractor.setDataSource(this, uri, null)

    var track = -1
    var format: MediaFormat? = null
    for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) {
            track = i
            format = f
            break
        }
    }
    if (track < 0 || format == null) {
        extractor.release()
        error("Tidak ada track audio")
    }
    extractor.selectTrack(track)
    val mime = format.getString(MediaFormat.KEY_MIME) ?: error("mime kosong")
    // OPEN-09: sampleRate/channelCount/encoding bisa berubah lewat INFO_OUTPUT_FORMAT_CHANGED
    // (HE-AAC/SBR menggandakan sample rate, PS membuat mono → stereo).
    var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var channelCount = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { 1 }
    var floatPcm = false

    val codec = MediaCodec.createDecoderByType(mime)
    codec.configure(format, null, null, 0)
    codec.start()

    val outChunks = ArrayList<ShortArray>()
    var total = 0
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var outputDone = false
    var maxSamples = sampleRate * 60 * 10 // 10 menit mono safety
    var truncated = false

    while (!outputDone) {
        if (!inputDone) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buf = codec.getInputBuffer(inIndex)!!
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val outIndex = codec.dequeueOutputBuffer(info, 10_000)
        when {
            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val of = codec.outputFormat
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channelCount = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                // KEY_PCM_ENCODING ada sejak API 24 (= minSdk). 4 = AudioFormat.ENCODING_PCM_FLOAT.
                floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                maxSamples = sampleRate * 60 * 10
            }
            outIndex >= 0 -> {
                val outBuf = codec.getOutputBuffer(outIndex)!!
                if (info.size > 0) {
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    val ordered = outBuf.order(ByteOrder.nativeOrder())
                    val tmp = if (floatPcm) {
                        val fb = ordered.asFloatBuffer()
                        ShortArray(fb.remaining()) { (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                    } else {
                        val sb = ordered.asShortBuffer()
                        ShortArray(sb.remaining()).also { sb.get(it) }
                    }
                    val shortCount = tmp.size
                    // downmix to mono if needed
                    val mono = if (channelCount > 1) {
                        val frames = shortCount / channelCount
                        ShortArray(frames).also { m ->
                            var i = 0
                            var f = 0
                            while (f < frames) {
                                var acc = 0
                                for (c in 0 until channelCount) {
                                    acc += tmp[i++]
                                }
                                m[f++] = (acc / channelCount).toShort()
                            }
                        }
                    } else tmp
                    outChunks.add(mono)
                    total += mono.size
                    if (total > maxSamples) {
                        outputDone = true
                        truncated = true
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }
    }
    codec.stop()
    codec.release()
    extractor.release()

    if (truncated && failIfTooLong) error("Audio lebih dari 10 menit — konversi WAV dibatasi 10 menit (mono). Pakai M4A bila sumbernya AAC.")
    if (total == 0) error("Dekode audio kosong")
    val pcm = ShortArray(total)
    var o = 0
    for (c in outChunks) {
        System.arraycopy(c, 0, pcm, o, c.size)
        o += c.size
    }
    return Triple(pcm, sampleRate, name)
}

internal fun MainActivity.writeWavFile(pcm: ShortArray, sampleRate: Int, baseName: String): File {
    val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
    if (!dir.exists()) dir.mkdirs()
    val safe = baseName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").take(40)
    val out = File(dir, "${safe}_${System.currentTimeMillis()}.wav")
    val dataSize = pcm.size * 2
    val fos = FileOutputStream(out)
    fos.use { outStream ->
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16) // PCM chunk
        header.putShort(1) // audio format PCM
        header.putShort(1) // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // byte rate
        header.putShort(2) // block align
        header.putShort(16) // bits
        header.put("data".toByteArray())
        header.putInt(dataSize)
        outStream.write(header.array())
        val bytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) bytes.putShort(s)
        outStream.write(bytes.array())
    }
    return out
}

/** Stream-copy audio track from media uri to M4A. */
internal fun MainActivity.extractAudioToM4a(uri: Uri, baseName: String): File {
    val extractor = MediaExtractor()
    contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        extractor.setDataSource(pfd.fileDescriptor)
    } ?: extractor.setDataSource(this, uri, null)

    var audioTrack = -1
    var format: MediaFormat? = null
    for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) {
            audioTrack = i
            format = f
            break
        }
    }
    if (audioTrack < 0 || format == null) {
        extractor.release()
        error("Tidak ada track audio")
    }
    extractor.selectTrack(audioTrack)
    val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
    if (!dir.exists()) dir.mkdirs()
    val safe = baseName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").take(40)
    val out = File(dir, "${safe}_${System.currentTimeMillis()}.m4a")
    val audioMime = format.getString(MediaFormat.KEY_MIME) ?: ""
    if (audioMime != "audio/mp4a-latm") {
        extractor.release()
        error("Suara berformat ${audioMime.removePrefix("audio/").uppercase()} — hanya AAC yang bisa disalin ke M4A. Pilih WAV.")
    }
    val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val outTrack = try {
        muxer.addTrack(format).also { muxer.start() }
    } catch (e: Exception) {
        runCatching { muxer.release() }
        extractor.release()
        out.delete()
        error("Track audio tidak bisa disalin ke M4A: ${e.message}")
    }
    val buffer = ByteBuffer.allocate(1024 * 1024)
    val info = MediaCodec.BufferInfo()
    try {
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.bufferFlags()
            muxer.writeSampleData(outTrack, buffer, info)
            extractor.advance()
        }
    } finally {
        try { muxer.stop() } catch (_: Exception) {}
        muxer.release()
        extractor.release()
    }
    if (!out.exists() || out.length() == 0L) {
        out.delete()
        error("M4A kosong")
    }
    return out
}

/** Memetakan flag MediaExtractor (SAMPLE_FLAG_*) ke flag MediaCodec (BUFFER_FLAG_*) agar lolos lint WrongConstant. */
internal fun android.media.MediaExtractor.bufferFlags(): Int =
    if ((sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
        android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
    else 0
