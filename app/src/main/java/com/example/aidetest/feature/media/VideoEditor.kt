package com.example.aidetest

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Video Editor (lokal, mode potret) — tata letak ala editor video seluler:
 *   Top bar   : tutup · menu proyek · resolusi · Ekspor
 *   Pratinjau : bingkai sesuai rasio ekspor + teks/stiker yang bisa diseret langsung
 *   Transport : waktu · putar · magnet · undo/redo · layar penuh
 *   Timeline  : penggaris + playhead tengah, track utama (filmstrip), audio (waveform), teks/stiker/gambar, efek
 *   Dock      : kategori (Edit/Audio/Teks/Stiker/Overlay/Efek/Filter/Rasio/Auto-cut/Sampul) — berganti alat
 *               kontekstual bila item timeline dipilih; panel parameter muncul menggantikan dock
 *
 * Ekspor: Render penuh (Media3 Transformer, encoding ulang) atau Cepat (stream-copy MediaMuxer: trim + gabung).
 */
internal fun MainActivity.videoEditorTool() {
    clearPage("Video Editor")
    topBarVisibility(false)
    if (!VeProject.loaded) {
        VeProject.loaded = true
        prefs.getString(VE_PREFS_KEY, null)?.let { saved -> runCatching { VeProject.fromPrefs(saved) } }
    }
    content.setPadding(0, 0, 0, 0)
    val ui = VideoEditorUi(this)
    content.addView(ui.root, LinearLayout.LayoutParams(-1, 0, 1f))
}

@SuppressLint("ClickableViewAccessibility")
internal class VideoEditorUi(val a: MainActivity) {
    val kit = VeUiKit(a)
    val p = VeProject
    val handler = Handler(Looper.getMainLooper())
    fun dp(v: Int): Int = a.dp(v)

    val root: LinearLayout = object : LinearLayout(a) {
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            // Home / layar mati / membuka aplikasi lain: jeda agar suara pratinjau tidak jalan di latar.
            if (visibility != View.VISIBLE) runCatching { playback.pause() }
        }
    }
    private val topBar = LinearLayout(a)
    private val titleView = TextView(a)
    private val subtitleView = TextView(a)
    private val exportBtn = TextView(a)
    private val exportBar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
    private val stage = FrameLayout(a)
    private val frame = FrameLayout(a)
    private val mediaBox = FrameLayout(a)
    private val bgBlur = ImageView(a)
    private val texture = TextureView(a)
    private val imageView = ImageView(a)
    val overlayView = VePreviewOverlayView(a)
    private val emptyHint = LinearLayout(a)
    private val transport = FrameLayout(a)
    private val timeLabel = TextView(a)
    private val playBtn = MdiIconView(a)
    private val snapBtn = MdiIconView(a)
    private val undoBtn = MdiIconView(a)
    private val redoBtn = MdiIconView(a)
    private val fsBtn = MdiIconView(a)
    private val timelineBox = FrameLayout(a)
    val timeline = VeTimelineView(a)
    private val leftControls = LinearLayout(a)
    private val muteTile = MdiIconView(a)
    private val muteLabel = TextView(a)
    private val coverImage = ImageView(a)
    private val dock = FrameLayout(a)
    private val dockLine = View(a)

    private var dockGroup = ""
    var panelOpen = false
        private set
    private var panelOnClose: (() -> Unit)? = null
    private var fullscreen = false
    private var compact = false
    private var immersive = false
    private var scrubbing = false
    private var blurClipId: String? = null
    private var lastFxKey = ""
    private var editing = false
    private val saveRunnable = Runnable { save() }
    private val orig = EditOrig()

    private var pollerOn = false
    private var disposed = false
    private var freezeBusy = false

    private val exportPoller = object : Runnable {
        override fun run() {
            pollerOn = false
            if (!root.isAttachedToWindow) return
            exporter.refreshButton()
            if (p.exporting) { pollerOn = true; handler.postDelayed(this, 500L) }
        }
    }

    private val playListener = object : VePlayback.Listener {
        override fun onTime(ms: Long) { onPlayheadChanged() }

        override fun onPlayState(playing: Boolean) {
            playBtn.setIconName(if (playing) "pause" else "play")
            playBtn.contentDescription = if (playing) "Jeda" else "Putar"
            overlayView.playing = playing
            overlayView.invalidate()
        }

        override fun onMediaChanged() { layoutMedia() }

        override fun onError(message: String) { a.toast(message) }
    }

    val playback = VePlayback(a, texture, imageView, playListener)
    val panels = VePanels(this)
    val exporter = VeExportUi(this)

    private val backHandler: () -> Boolean = {
        when {
            panelOpen -> { closePanel(); pushBack(); true }
            fullscreen -> { toggleFullscreen(); pushBack(); true }
            dockGroup.isNotEmpty() -> { dockGroup = ""; refreshDock(); pushBack(); true }
            p.selKind.isNotEmpty() -> { select("", null); pushBack(); true }
            else -> false
        }
    }

    private fun pushBack() { OverlayBack.push(backHandler) }

    private val tlHost = object : VeTimelineView.Host {
        override fun onScrub(ms: Long) {
            playback.seek(ms)
            onPlayheadChanged()
        }

        override fun onScrubState(active: Boolean) {
            scrubbing = active
            if (active) playback.pause()
        }

        override fun onSelect(kind: String, id: String?) { select(kind, id) }
        override fun onEditStart(kind: String, id: String, mode: String) { beginEdit(kind, id, mode) }
        override fun onEdit(kind: String, id: String, mode: String, deltaMs: Long) { applyEdit(kind, id, mode, deltaMs) }
        override fun onEditEnd(kind: String, id: String, mode: String) { endEdit() }
        override fun onReorder(clipId: String, toIndex: Int) { reorderClip(clipId, toIndex) }
        override fun onAddMedia() { showAddMediaMenu(timeline) }

        override fun onHint(kind: String) {
            if (p.clips.isEmpty()) { a.toast("Impor video atau foto dulu"); return }
            if (kind == "audio") { dockGroup = "audio"; refreshDock() } else addText()
        }

        override fun onTransition(clipId: String) {
            select("clip", clipId)
            panels.transition()
        }
    }

    private val ovHost = object : VePreviewOverlayView.Host {
        override fun onSelectOverlay(id: String?) { if (id == null) select("", null) else select("overlay", id) }
        override fun onOverlayEditStart(id: String) { checkpoint(); editing = true }
        override fun onOverlayChanged(id: String) { saveSoon(); timeline.invalidate() }
        override fun onOverlayEditEnd(id: String) { editing = false; saveNow() }
        override fun onTapEmpty() { if (p.clips.isNotEmpty()) playback.togglePlay() }
        override fun onEditText(id: String) { select("overlay", id); panels.textEditor(focus = true) }

        override fun onDeleteOverlay(id: String) {
            checkpoint()
            p.overlays.removeAll { it.id == id }
            select("", null)
            afterChange(reload = false)
        }
    }

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(kit.bg0)
        buildTopBar()
        root.addView(topBar, LinearLayout.LayoutParams(-1, dp(52)))
        exportBar.max = 100
        exportBar.progressTintList = android.content.res.ColorStateList.valueOf(kit.ink)
        exportBar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(kit.bg2)
        exportBar.visibility = View.GONE
        root.addView(exportBar, LinearLayout.LayoutParams(-1, dp(3)))
        buildPreview()
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        buildTransport()
        root.addView(transport, LinearLayout.LayoutParams(-1, dp(46)))
        buildTimeline()
        root.addView(timelineBox, LinearLayout.LayoutParams(-1, timelineHeight()))
        dock.setBackgroundColor(kit.bg1)
        dockLine.setBackgroundColor(kit.stroke)
        root.addView(dockLine, LinearLayout.LayoutParams(-1, 1))
        root.addView(dock, LinearLayout.LayoutParams(-1, -2))
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { dispose() }
        })
        refreshDock()
        updateUndoButtons()
        updateSnapButton()
        updateMuteTile()
        onPlayheadChanged()
        regenerateMissingThumbs()
        exporter.refreshButton()
        if (p.exporting) { pollerOn = true; handler.postDelayed(exportPoller, 500L) }
        stage.post {
            playback.seek(p.playheadMs)
            layoutMedia()
        }
        pushBack()
    }

    // ------------------------------------------------------------------ persistence & undo

    fun save() {
        handler.removeCallbacks(saveRunnable)
        runCatching { a.prefs.edit().putString(VE_PREFS_KEY, p.toPrefs()).apply() }
    }

    fun saveSoon() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 600L)
    }

    private fun saveNow() { save() }

    fun checkpoint() {
        p.undo.add(p.snapshot())
        while (p.undo.size > 60) p.undo.removeAt(0)
        p.redo.clear()
        updateUndoButtons()
    }

    /** Terapkan perubahan data: simpan, segarkan timeline/pratinjau/dock. */
    fun afterChange(reload: Boolean = true) {
        p.playheadMs = p.playheadMs.coerceIn(0L, p.totalMs())
        p.validateSelection()
        save()
        // Hasil tugas latar yang selesai setelah editor ditutup: data tetap tersimpan, UI tidak disentuh.
        if (disposed) return
        if (reload) playback.refresh() else playback.applyVolume()
        layoutMedia()
        onPlayheadChanged()
        updateUndoButtons()
        updateMuteTile()
        refreshTitle()
        refreshDock()
    }

    /** Pembaruan ringan saat slider digeser (tanpa memuat ulang pemutar). */
    fun touch() {
        saveSoon()
        timeline.invalidate()
        overlayView.invalidate()
        applyPreviewFx()
        updateTimeLabel()
    }

    private fun undo() {
        if (p.undo.isEmpty()) { a.toast("Tidak ada langkah untuk di-undo"); return }
        playback.pause()
        p.redo.add(p.snapshot())
        p.restore(p.undo.removeAt(p.undo.size - 1))
        closePanelSilently()
        afterChange()
    }

    private fun redo() {
        if (p.redo.isEmpty()) { a.toast("Tidak ada langkah untuk di-redo"); return }
        playback.pause()
        p.undo.add(p.snapshot())
        p.restore(p.redo.removeAt(p.redo.size - 1))
        closePanelSilently()
        afterChange()
    }

    private fun updateUndoButtons() {
        undoBtn.alpha = if (p.undo.isNotEmpty()) 1f else 0.35f
        redoBtn.alpha = if (p.redo.isNotEmpty()) 1f else 0.35f
    }

    private fun updateSnapButton() {
        snapBtn.alpha = if (p.snapOn) 1f else 0.4f
        snapBtn.contentDescription = if (p.snapOn) "Magnet aktif" else "Magnet mati"
    }

    private fun updateMuteTile() {
        muteTile.setIconName(if (p.includeAudio) "volume-high" else "volume-off")
        muteLabel.text = if (p.includeAudio) "Suara klip" else "Senyap"
    }

    // ------------------------------------------------------------------ build: top bar

    /** Top bar sama dengan Foto Studio & Audio Editor: kembali · judul/subjudul · undo/redo · menu · Ekspor. */
    private fun buildTopBar() {
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(dp(2), 0, dp(8), 0)
        topBar.setBackgroundColor(kit.bg0)
        topBar.addView(kit.icon("arrow-left", 23f, kit.ink, "Kembali") { a.navigateBack() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), 0, dp(4), 0)
            isClickable = true
            contentDescription = "Pengaturan ekspor"
            background = kit.pressable(Color.TRANSPARENT, 10)
            setOnClickListener { exporter.openPanel() }
        }
        titleView.apply {
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(kit.ink)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        subtitleView.apply {
            textSize = 11f
            setTextColor(kit.muted)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(3), 0, 0)
        }
        titles.addView(titleView)
        titles.addView(subtitleView)
        topBar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        undoBtn.apply {
            setIconName("undo"); setIconSize(22f); setTextColor(kit.ink); contentDescription = "Undo"
            gravity = Gravity.CENTER
            isClickable = true
            background = kit.pressable(Color.TRANSPARENT, 12)
            setOnClickListener { undo() }
        }
        redoBtn.apply {
            setIconName("redo"); setIconSize(22f); setTextColor(kit.ink); contentDescription = "Redo"
            gravity = Gravity.CENTER
            isClickable = true
            background = kit.pressable(Color.TRANSPARENT, 12)
            setOnClickListener { redo() }
        }
        topBar.addView(undoBtn, LinearLayout.LayoutParams(dp(40), dp(44)))
        topBar.addView(redoBtn, LinearLayout.LayoutParams(dp(40), dp(44)))
        topBar.addView(kit.icon("dots-vertical", 22f, kit.ink, "Menu proyek") { v -> showProjectMenu(v) }, LinearLayout.LayoutParams(dp(38), dp(44)))
        exportBtn.apply {
            text = "Ekspor"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(kit.c.activeInk)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(14), 0, dp(14), 0)
            background = kit.pressable(kit.c.activeFill, 18)
            contentDescription = "Ekspor video"
            isClickable = true
            setOnClickListener { exporter.onExportClicked() }
        }
        topBar.addView(exportBtn, LinearLayout.LayoutParams(-2, dp(36)).apply { leftMargin = dp(4) })
        refreshTitle()
    }

    private fun aspectLabel(): String = if (p.aspect == "asli") "Rasio asli" else p.aspect

    /** Judul = nama proyek; subjudul = resolusi · rasio · durasi (ketuk untuk pengaturan ekspor). */
    private fun refreshTitle() {
        titleView.text = p.name.ifBlank { "Video Editor" }
        subtitleView.text = "${p.resolution}p · ${aspectLabel()} · ${VeTime.fmt(p.totalMs())} ▾"
    }

    /** Dipanggil [VeExportUi] untuk memperbarui tombol & bar progres. */
    fun bindExportViews(label: String, enabled: Boolean, progress: Int, resText: String) {
        exportBtn.text = label
        exportBtn.alpha = if (enabled) 1f else 0.85f
        refreshTitle()
        if (p.exporting) {
            exportBar.visibility = View.VISIBLE
            exportBar.isIndeterminate = progress < 0
            if (progress >= 0) exportBar.progress = progress.coerceIn(0, 100)
            if (!pollerOn) { pollerOn = true; handler.postDelayed(exportPoller, 500L) }
        } else {
            exportBar.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ build: preview

    private fun buildPreview() {
        stage.setBackgroundColor(kit.wsStage)
        stage.setPadding(dp(10), dp(6), dp(10), dp(6))
        frame.setBackgroundColor(Color.BLACK)
        frame.clipChildren = true
        mediaBox.clipChildren = true
        bgBlur.scaleType = ImageView.ScaleType.CENTER_CROP
        bgBlur.visibility = View.GONE
        mediaBox.addView(bgBlur, FrameLayout.LayoutParams(-1, -1))
        mediaBox.addView(texture, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        imageView.scaleType = ImageView.ScaleType.FIT_XY
        imageView.visibility = View.GONE
        mediaBox.addView(imageView, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        frame.addView(mediaBox, FrameLayout.LayoutParams(-1, -1))
        overlayView.host = ovHost
        frame.addView(overlayView, FrameLayout.LayoutParams(-1, -1))
        stage.addView(frame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        emptyHint.orientation = LinearLayout.VERTICAL
        emptyHint.gravity = Gravity.CENTER
        emptyHint.addView(kit.icon("movie-open-outline", 36f, kit.wsMuted), LinearLayout.LayoutParams(-1, dp(44)))
        emptyHint.addView(kit.text("Impor video atau foto untuk mulai", 12.5f, kit.wsMuted).apply { gravity = Gravity.CENTER })
        emptyHint.addView(kit.wsButton("Tambah media", "plus") { pickMedia() },
            LinearLayout.LayoutParams(dp(170), dp(42)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) })
        stage.addView(emptyHint, FrameLayout.LayoutParams(-1, -1))
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if (r - l != or2 - ol || b - t != ob - ot) stage.post { layoutMedia() }
        }
    }

    /** Transport bagian dari area kerja gelap: waktu · putar · magnet · layar penuh (undo/redo ada di top bar). */
    private fun buildTransport() {
        transport.setBackgroundColor(kit.ws)
        timeLabel.apply { textSize = 12.5f; setTextColor(kit.wsInk); typeface = android.graphics.Typeface.MONOSPACE; maxLines = 1 }
        transport.addView(timeLabel, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(14) })
        playBtn.apply {
            setIconName("play"); setIconSize(30f); setTextColor(kit.wsInk)
            contentDescription = "Putar"
            gravity = Gravity.CENTER
            isClickable = true
            background = kit.wsPressable(Color.TRANSPARENT, 24)
            setOnClickListener { playback.togglePlay() }
        }
        transport.addView(playBtn, FrameLayout.LayoutParams(dp(52), dp(44), Gravity.CENTER))
        val right = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        snapBtn.apply {
            setIconName("magnet"); setIconSize(20f); setTextColor(kit.wsInk)
            gravity = Gravity.CENTER
            isClickable = true
            background = kit.wsPressable(Color.TRANSPARENT, 12)
            setOnClickListener { p.snapOn = !p.snapOn; updateSnapButton(); saveSoon(); a.toast(if (p.snapOn) "Magnet aktif: item menempel ke playhead & tepi klip" else "Magnet mati") }
        }
        fsBtn.apply {
            setIconName("fullscreen"); setIconSize(21f); setTextColor(kit.wsInk); contentDescription = "Layar penuh pratinjau"
            gravity = Gravity.CENTER
            isClickable = true
            background = kit.wsPressable(Color.TRANSPARENT, 12)
            setOnClickListener { toggleFullscreen() }
        }
        right.addView(snapBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        right.addView(fsBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        transport.addView(right, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(6) })
    }

    private fun timelineHeight(): Int {
        val screenH = a.resources.displayMetrics.heightPixels
        return if (compact) dp(132) else (screenH * 0.30f).toInt().coerceIn(dp(184), dp(290))
    }

    private fun buildTimeline() {
        timeline.host = tlHost
        timelineBox.setBackgroundColor(kit.ws)
        timelineBox.addView(timeline, FrameLayout.LayoutParams(-1, -1))
        leftControls.orientation = LinearLayout.VERTICAL
        fun ctl(content: View, label: TextView?, desc: String, onClick: () -> Unit): LinearLayout = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = kit.shape(Color.argb(238, 30, 30, 34), 12, kit.wsStroke)
            contentDescription = desc
            isClickable = true
            setOnClickListener { onClick() }
            addView(content)
            if (label != null) addView(label)
        }
        muteTile.apply { setIconSize(18f); setTextColor(kit.wsInk) }
        muteLabel.apply { textSize = 9f; setTextColor(kit.wsMuted); gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false }
        leftControls.addView(
            ctl(muteTile, muteLabel, "Bisukan atau nyalakan suara klip video") {
                checkpoint()
                p.includeAudio = !p.includeAudio
                afterChange(reload = false)
                a.toast(if (p.includeAudio) "Suara klip dinyalakan" else "Suara klip dibisukan (juga di hasil ekspor)")
            },
            LinearLayout.LayoutParams(dp(50), dp(44)).apply { bottomMargin = dp(4) }
        )
        val autoIcon = MdiIconView(a).apply { setIconName("auto-fix"); setIconSize(18f); setTextColor(kit.wsInk) }
        val autoLabel = TextView(a).apply { text = "Auto-cut"; textSize = 9f; setTextColor(kit.wsMuted); gravity = Gravity.CENTER; includeFontPadding = false }
        leftControls.addView(ctl(autoIcon, autoLabel, "Auto-cut: hapus bagian hening atau bagi per adegan") { panels.autoCut() },
            LinearLayout.LayoutParams(dp(50), dp(44)).apply { bottomMargin = dp(4) })
        coverImage.scaleType = ImageView.ScaleType.CENTER_CROP
        val coverLabel = TextView(a).apply { text = "Sampul"; textSize = 9f; setTextColor(kit.wsMuted); gravity = Gravity.CENTER; includeFontPadding = false }
        leftControls.addView(ctl(coverImage.also { it.layoutParams = LinearLayout.LayoutParams(dp(28), dp(22)) }, coverLabel, "Pilih sampul video") { panels.cover() },
            LinearLayout.LayoutParams(dp(50), dp(44)))
        timelineBox.addView(leftControls, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(4); topMargin = dp(30) })
        refreshCoverThumb()
    }

    /** Thumbnail tombol Sampul: poster klip pertama (bingkai sampul sungguhan ditampilkan di panel Sampul). */
    fun refreshCoverThumb() {
        val first = p.clips.firstOrNull()
        coverImage.setImageBitmap(first?.let { p.thumbs[it.uri.toString()] })
    }

    // ------------------------------------------------------------------ preview layout & effects

    fun onPlayheadChanged() {
        updateTimeLabel()
        overlayView.timeMs = p.playheadMs
        timeline.invalidate()
        applyPreviewFx()
    }

    private fun updateTimeLabel() {
        timeLabel.text = "${VeTime.clock(p.playheadMs)} / ${VeTime.clock(p.totalMs())}"
    }

    /** Ukur bingkai (rasio ekspor) dan media di dalamnya. */
    fun layoutMedia() {
        val sw = stage.width - stage.paddingLeft - stage.paddingRight
        val sh = stage.height - stage.paddingTop - stage.paddingBottom
        if (sw <= 0 || sh <= 0) return
        val (ow, oh) = veOutputSize(p.aspect, p.resolution, p.clips)
        val s = min(sw / ow.toFloat(), sh / oh.toFloat())
        val fw = (ow * s).toInt().coerceAtLeast(1)
        val fh = (oh * s).toInt().coerceAtLeast(1)
        (frame.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.width != fw || lp.height != fh) {
                lp.width = fw
                lp.height = fh
                lp.gravity = Gravity.CENTER
                frame.layoutParams = lp
            }
        }
        refreshExportLabels()
        val clip = playback.currentClip()
        if (clip == null) {
            emptyHint.visibility = if (p.clips.isEmpty()) View.VISIBLE else View.GONE
            frame.visibility = if (p.clips.isEmpty()) View.INVISIBLE else View.VISIBLE
            return
        }
        emptyHint.visibility = View.GONE
        frame.visibility = View.VISIBLE
        var mw = clip.videoWidth
        var mh = clip.videoHeight
        if (clip.isImage) {
            val bmp = (imageView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bmp != null && (mw <= 0 || mh <= 0)) { mw = bmp.width; mh = bmp.height }
        } else {
            if (mw <= 0 || mh <= 0) {
                playback.videoSize()?.let { (vw, vh) -> mw = vw; mh = vh }
            }
            if (clip.sourceRotation % 180 != 0) { val t = mw; mw = mh; mh = t }
        }
        if (mw <= 0 || mh <= 0) { mw = 16; mh = 9 }
        val rot = ((clip.rotation % 360) + 360) % 360
        val odd = rot % 180 != 0
        val boxW = if (odd) mh else mw
        val boxH = if (odd) mw else mh
        val crop = p.fitMode == "isi"
        val scale = if (crop) max(fw / boxW.toFloat(), fh / boxH.toFloat()) else min(fw / boxW.toFloat(), fh / boxH.toFloat())
        val dw = (mw * scale).toInt().coerceAtLeast(1)
        val dh = (mh * scale).toInt().coerceAtLeast(1)
        val target: View = if (clip.isImage) imageView else texture
        val lp = target.layoutParams as FrameLayout.LayoutParams
        if (lp.width != dw || lp.height != dh) {
            lp.width = dw
            lp.height = dh
            lp.gravity = Gravity.CENTER
            target.layoutParams = lp
        }
        target.rotation = rot.toFloat()
        updateBlurBackground(clip, boxW, boxH, fw, fh)
        lastFxKey = ""
        applyPreviewFx()
    }

    private fun refreshExportLabels() { exporter.refreshButton() }

    /** Latar blur (perkiraan): poster klip diperkecil lalu diperbesar sehingga tampak buram. */
    private fun updateBlurBackground(clip: VeClip, boxW: Int, boxH: Int, fw: Int, fh: Int) {
        val needBlur = p.fitMode == "blur" && abs(boxW.toFloat() / boxH - fw.toFloat() / fh) > 0.01f
        if (!needBlur) { bgBlur.visibility = View.GONE; blurClipId = null; return }
        bgBlur.visibility = View.VISIBLE
        if (blurClipId == clip.id) return
        val src = p.thumbs[clip.uri.toString()] ?: return
        blurClipId = clip.id
        val tiny = Bitmap.createScaledBitmap(src, 16, max(1, 16 * src.height / max(1, src.width)), true)
        bgBlur.setImageBitmap(tiny)
        bgBlur.alpha = 0.85f
    }

    /** Transform klip (zoom/geser/cermin), efek berwaktu, dan filter warna pada pratinjau. */
    fun applyPreviewFx() {
        val clip = playback.currentClip()
        val t = p.playheadMs
        var cm: ColorMatrix? = clip?.let { VePreviewFx.clipMatrix(it) }
        var m = if (clip != null && clip.hasTransform()) VeFxCompose.clipTransform(clip.zoom, clip.flipH, clip.panX, clip.panY) else VeFxCompose.IDENTITY
        var blur = 0f
        for (e in p.effects) {
            if (t < e.startMs || t >= e.endMs()) continue
            val local = t - e.startMs
            val pr = (local.toFloat() / max(1L, e.durationMs)).coerceIn(0f, 1f)
            when {
                VeFxMath.isMotion(e.type) -> m = VeFxCompose.then(m, VeFxMath.motion(e.type, e.intensity, pr, local))
                VeFxMath.isColor(e.type) -> cm = VePreviewFx.concat(cm, VePreviewFx.fxMatrix(e.type, e.intensity, pr, local))
                VeFxMath.isBlur(e.type) -> blur = max(blur, VeFxMath.blurSigma(e.intensity, pr))
            }
        }
        val fw = frame.width.toFloat()
        val fh = frame.height.toFloat()
        mediaBox.scaleX = m[0]
        mediaBox.scaleY = m[1]
        mediaBox.translationX = m[2] * fw / 2f
        mediaBox.translationY = -m[3] * fh / 2f
        val key = (cm?.array?.contentHashCode() ?: 0).toString()
        if (key != lastFxKey) {
            lastFxKey = key
            val filter = cm?.let { ColorMatrixColorFilter(it) }
            texture.setLayerPaint(if (filter == null) null else Paint().apply { colorFilter = filter })
            imageView.colorFilter = filter
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val r = blur * (fh / 720f).coerceAtLeast(0.5f)
            mediaBox.setRenderEffect(if (r > 0.6f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null)
        }
    }

    // ------------------------------------------------------------------ timeline edits (trim / move)

    private class EditOrig {
        var trimStart = 0L
        var effectiveEnd = 0L
        var source = 0L
        var startMs = 0L
        var durationMs = 0L
        var srcStart = 0L
        var srcEnd = 0L
        var playhead = 0L
        var clipStart = 0L
    }

    private fun snapTargets(excludeKind: String, excludeId: String): LongArray {
        val out = ArrayList<Long>()
        out.add(0L)
        out.add(p.totalMs())
        out.add(orig.playhead)
        var s = 0L
        for (c in p.clips) { s += c.timelineMs(); out.add(s) }
        for (x in p.audios) if (!(excludeKind == "audio" && x.id == excludeId)) { out.add(x.startMs); out.add(x.endMs()) }
        for (x in p.overlays) if (!(excludeKind == "overlay" && x.id == excludeId)) { out.add(x.startMs); out.add(x.endMs()) }
        for (x in p.effects) if (!(excludeKind == "effect" && x.id == excludeId)) { out.add(x.startMs); out.add(x.endMs()) }
        return out.toLongArray()
    }

    private fun snapThreshold(): Long = (dp(9).toFloat() / timeline.pps() * 1000f).toLong().coerceAtLeast(20L)

    private fun beginEdit(kind: String, id: String, mode: String) {
        playback.pause()
        if (p.selKind != kind || p.selId != id) select(kind, id)
        checkpoint()
        editing = true
        orig.playhead = p.playheadMs
        when (kind) {
            "clip" -> p.clips.firstOrNull { it.id == id }?.let { c ->
                orig.trimStart = c.trimStartMs
                orig.effectiveEnd = c.effectiveEnd()
                orig.source = c.sourceDurationMs
                orig.clipStart = p.clipStart(c)
            }
            "audio" -> p.audios.firstOrNull { it.id == id }?.let { x ->
                orig.startMs = x.startMs
                orig.srcStart = x.srcStartMs
                orig.srcEnd = x.effectiveEnd()
                orig.source = x.sourceDurationMs
            }
            "overlay" -> p.overlays.firstOrNull { it.id == id }?.let { x -> orig.startMs = x.startMs; orig.durationMs = x.durationMs }
            "effect" -> p.effects.firstOrNull { it.id == id }?.let { x -> orig.startMs = x.startMs; orig.durationMs = x.durationMs }
        }
    }

    private fun applyEdit(kind: String, id: String, mode: String, deltaMs: Long) {
        if (!editing) return
        var delta = deltaMs
        val thr = if (p.snapOn) snapThreshold() else -1L
        var guide: Long? = null
        when (kind) {
            "clip" -> {
                val c = p.clips.firstOrNull { it.id == id } ?: return
                val speed = c.effectiveSpeed()
                if (mode == "trimR") {
                    if (c.isImage) {
                        c.sourceDurationMs = (orig.source + delta).coerceIn(500L, 600_000L)
                        c.trimStartMs = 0L
                        c.trimEndMs = 0L
                    } else {
                        c.trimEndMs = VeTrim.right(orig.trimStart, orig.effectiveEnd, c.sourceDurationMs, speed, delta)
                    }
                    val total = p.totalMs()
                    p.playheadMs = orig.playhead.coerceIn(0L, total)
                } else if (mode == "trimL") {
                    val applied: Long
                    if (c.isImage) {
                        val newDur = (orig.source - delta).coerceIn(500L, 600_000L)
                        applied = orig.source - newDur
                        c.sourceDurationMs = newDur
                        c.trimStartMs = 0L
                        c.trimEndMs = 0L
                    } else {
                        val ns = VeTrim.left(orig.trimStart, orig.effectiveEnd, speed, delta)
                        c.trimStartMs = ns
                        applied = ((ns - orig.trimStart) / speed).toLong()
                    }
                    // Gagang kiri mengikuti jari: playhead bergeser berlawanan agar tepi kiri tetap di bawah jari.
                    p.playheadMs = (orig.playhead - applied).coerceIn(0L, p.totalMs())
                }
                playback.seek(p.playheadMs)
            }
            "audio" -> {
                val x = p.audios.firstOrNull { it.id == id } ?: return
                when (mode) {
                    "trimL" -> {
                        val lo = -min(orig.srcStart, orig.startMs)
                        val hi = orig.srcEnd - VeTrim.MIN_MS - orig.srcStart
                        var d = delta.coerceIn(lo, max(lo, hi))
                        if (thr >= 0) VeSnapMath.nearest(orig.startMs + d, snapTargets("audio", id), thr)?.let { s -> d = (s - orig.startMs).coerceIn(lo, max(lo, hi)); guide = s }
                        x.srcStartMs = orig.srcStart + d
                        x.startMs = orig.startMs + d
                    }
                    "trimR" -> {
                        val minEnd = orig.srcStart + VeTrim.MIN_MS
                        var end = (orig.srcEnd + delta).coerceIn(min(minEnd, orig.source), orig.source)
                        if (thr >= 0) {
                            val timelineEnd = orig.startMs + (end - orig.srcStart)
                            VeSnapMath.nearest(timelineEnd, snapTargets("audio", id), thr)?.let { s ->
                                end = (orig.srcStart + (s - orig.startMs)).coerceIn(min(minEnd, orig.source), orig.source)
                                guide = s
                            }
                        }
                        x.srcEndMs = if (end >= orig.source) 0L else end
                    }
                    "move" -> {
                        var ns = max(0L, orig.startMs + delta)
                        if (thr >= 0) {
                            val targets = snapTargets("audio", id)
                            val off = VeSnapMath.snapMove(ns, ns + x.durationMs(), targets, thr)
                            if (off != 0L) ns = max(0L, ns + off)
                            guide = snapGuideFor(ns, ns + x.durationMs(), targets, thr)
                        }
                        x.startMs = ns
                    }
                }
                playback.applyVolume()
            }
            "overlay", "effect" -> {
                val ov = p.overlays.firstOrNull { it.id == id && kind == "overlay" }
                val fx = p.effects.firstOrNull { it.id == id && kind == "effect" }
                if (ov == null && fx == null) return
                val origEnd = orig.startMs + orig.durationMs
                var newStart = orig.startMs
                var newDur = orig.durationMs
                when (mode) {
                    "trimL" -> {
                        var ns = (orig.startMs + delta).coerceIn(0L, origEnd - 200L)
                        if (thr >= 0) VeSnapMath.nearest(ns, snapTargets(kind, id), thr)?.let { s -> ns = s.coerceIn(0L, origEnd - 200L); guide = s }
                        newStart = ns
                        newDur = origEnd - ns
                    }
                    "trimR" -> {
                        var end = max(orig.startMs + 200L, origEnd + delta)
                        if (thr >= 0) VeSnapMath.nearest(end, snapTargets(kind, id), thr)?.let { s -> end = max(orig.startMs + 200L, s); guide = s }
                        newDur = end - orig.startMs
                    }
                    "move" -> {
                        var ns = max(0L, orig.startMs + delta)
                        if (thr >= 0) {
                            val off = VeSnapMath.snapMove(ns, ns + orig.durationMs, snapTargets(kind, id), thr)
                            if (off != 0L) ns = max(0L, ns + off)
                            guide = snapGuideFor(ns, ns + orig.durationMs, snapTargets(kind, id), thr)
                        }
                        newStart = ns
                    }
                }
                if (ov != null) { ov.startMs = newStart; ov.durationMs = newDur } else if (fx != null) { fx.startMs = newStart; fx.durationMs = newDur }
            }
        }
        timeline.snapGuideMs = guide
        timeline.invalidate()
        overlayView.invalidate()
        onPlayheadChanged()
        saveSoon()
    }

    /** Waktu tepi yang menempel (bila salah satu tepi item tepat pada target), untuk garis panduan. */
    private fun snapGuideFor(start: Long, end: Long, targets: LongArray, thr: Long): Long? {
        if (thr < 0) return null
        for (t in targets) if (t == start || t == end) return t
        return null
    }

    private fun endEdit() {
        editing = false
        timeline.snapGuideMs = null
        afterChange(reload = true)
    }

    private fun reorderClip(clipId: String, toIndex: Int) {
        val from = p.clips.indexOfFirst { it.id == clipId }
        if (from < 0) return
        val clip = p.clips[from]
        val dest = toIndex.coerceIn(0, p.clips.size - 1)
        if (dest == from) return
        playback.pause()
        checkpoint()
        p.clips.removeAt(from)
        p.clips.add(dest, clip)
        p.playheadMs = p.clipStart(clip)
        afterChange()
    }

    // ------------------------------------------------------------------ selection & dock

    fun select(kind: String, id: String?) {
        val before = p.selKind + "|" + p.selId
        if (kind.isEmpty() || id == null) p.clearSelection() else { p.selKind = kind; p.selId = id }
        p.validateSelection()
        // Panel milik item lama: tutup bila pilihan berubah.
        if (panelOpen && before != p.selKind + "|" + p.selId) closePanelSilently()
        if (p.selKind.isNotEmpty()) dockGroup = ""
        timeline.invalidate()
        overlayView.invalidate()
        refreshDock()
    }

    fun selectedClip(): VeClip? = if (p.selKind == "clip") p.clips.firstOrNull { it.id == p.selId } else null
    fun selectedAudio(): VeAudioItem? = if (p.selKind == "audio") p.audios.firstOrNull { it.id == p.selId } else null
    fun selectedOverlay(): VeOverlay? = if (p.selKind == "overlay") p.overlays.firstOrNull { it.id == p.selId } else null
    fun selectedEffect(): VeEffect? = if (p.selKind == "effect") p.effects.firstOrNull { it.id == p.selId } else null

    /** Klip yang sedang dipilih, atau klip di bawah playhead bila tidak ada pilihan klip. */
    fun targetClip(): VeClip? {
        selectedClip()?.let { return it }
        var t = 0L
        for (c in p.clips) {
            val d = c.timelineMs()
            if (p.playheadMs < t + d) return c
            t += d
        }
        return p.clips.lastOrNull()
    }

    private class Tool(val icon: String, val label: String, val enabled: Boolean = true, val selected: Boolean = false, val action: () -> Unit)

    private fun strip(tools: List<Tool>, backAction: (() -> Unit)?): View {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        if (backAction != null) {
            row.addView(kit.tile("chevron-left", "Kembali") { backAction() }, LinearLayout.LayoutParams(dp(58), dp(54)).apply { leftMargin = dp(1); rightMargin = dp(1) })
            row.addView(View(a).apply { setBackgroundColor(kit.stroke) }, LinearLayout.LayoutParams(dp(1), dp(30)).apply { leftMargin = dp(4); rightMargin = dp(4) })
        }
        for (t in tools) row.addView(kit.tile(t.icon, t.label, t.enabled, t.selected, t.action), LinearLayout.LayoutParams(dp(66), dp(54)).apply { leftMargin = dp(1); rightMargin = dp(1) })
        return HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }
    }

    fun refreshDock() {
        if (panelOpen) return
        dock.removeAllViews()
        val view: View = when (p.selKind) {
            "clip" -> selectedClip()?.let { strip(clipTools(it)) { select("", null) } }
            "audio" -> selectedAudio()?.let { strip(audioTools(it)) { select("", null) } }
            "overlay" -> selectedOverlay()?.let { strip(overlayTools(it)) { select("", null) } }
            "effect" -> selectedEffect()?.let { strip(effectTools(it)) { select("", null) } }
            else -> null
        } ?: when (dockGroup) {
            "audio" -> strip(audioGroupTools()) { dockGroup = ""; refreshDock() }
            "text" -> strip(textGroupTools()) { dockGroup = ""; refreshDock() }
            else -> strip(rootTools(), null)
        }
        dock.addView(view, FrameLayout.LayoutParams(-1, dp(64)))
        setCompact(false)
    }

    private fun rootTools(): List<Tool> = listOf(
        Tool("content-cut", "Edit") { openClipTools() },
        Tool("music-note", "Audio") { dockGroup = "audio"; refreshDock() },
        Tool("format-text", "Teks") { dockGroup = "text"; refreshDock() },
        Tool("sticker-emoji", "Stiker") { requireMedia { panels.stickers() } },
        Tool("image-plus", "Overlay") { requireMedia { pickOverlayImage() } },
        Tool("shimmer", "Efek") { requireMedia { panels.effects() } },
        Tool("palette-outline", "Filter") { requireMedia { selectTargetClip(); panels.filter() } },
        Tool("aspect-ratio", "Rasio") { panels.aspect() },
        Tool("auto-fix", "Auto-cut") { requireMedia { panels.autoCut() } },
        Tool("image-outline", "Sampul") { requireMedia { panels.cover() } },
        Tool("folder-multiple-image", "Pustaka") { panels.library() },
        Tool("plus-box-outline", "Tambah") { showAddMediaMenu(dock) }
    )

    private fun audioGroupTools(): List<Tool> = listOf(
        Tool("music-note-plus", "Musik") { requireMedia { pickAudio() } },
        Tool("microphone", "Rekam") { requireMedia { panels.voice() } },
        Tool("account-voice", "Narasi") { requireMedia { panels.narration() } },
        Tool("music-box-outline", "Ekstrak") { requireMedia { selectTargetClip(); extractAudio() } },
        Tool(if (p.includeAudio) "volume-high" else "volume-off", if (p.includeAudio) "Suara klip" else "Senyap") {
            checkpoint(); p.includeAudio = !p.includeAudio; afterChange(reload = false)
        }
    )

    private fun textGroupTools(): List<Tool> = listOf(
        Tool("format-text", "Teks") { requireMedia { addText() } },
        Tool("subtitles-outline", "Keterangan") { requireMedia { addCaption() } },
        Tool("format-list-bulleted", "Daftar") { panels.captionList() },
        Tool("file-import-outline", "Impor SRT") { requireMedia { pickSrt() } },
        Tool("file-export-outline", "Ekspor SRT") { exportSrt() }
    )

    private fun clipTools(c: VeClip): List<Tool> {
        val idx = p.clips.indexOfFirst { it.id == c.id }
        return listOf(
            Tool("content-cut", "Bagi") { splitAtPlayhead() },
            Tool("arrow-collapse-left", "Pangkas kiri") { trimToPlayhead(true) },
            Tool("arrow-collapse-right", "Pangkas kanan") { trimToPlayhead(false) },
            Tool("speedometer", "Kecepatan", enabled = !c.isImage) { panels.speed() },
            Tool("timer-outline", "Durasi", enabled = c.isImage) { panels.photoDuration() },
            Tool("volume-high", "Volume", enabled = !c.isImage) { panels.volume() },
            Tool("palette-outline", "Filter") { panels.filter() },
            Tool("tune-vertical", "Atur") { panels.adjust() },
            Tool("transition", "Transisi") { panels.transition() },
            Tool("magnify-plus-outline", "Zoom") { panels.zoomPan() },
            Tool("rotate-right", "Putar") { rotateSelected() },
            Tool("flip-horizontal", "Cermin", selected = c.flipH) { flipSelected() },
            Tool("snowflake", "Bekukan", enabled = !c.isImage) { freezeFrame() },
            Tool("music-box-outline", "Ekstrak", enabled = !c.isImage) { extractAudio() },
            Tool("content-copy", "Duplikat") { duplicateSelected() },
            Tool("arrow-left-bold", "Mundur", enabled = idx > 0) { moveSelected(-1) },
            Tool("arrow-right-bold", "Maju", enabled = idx in 0 until p.clips.lastIndex) { moveSelected(1) },
            Tool("delete-outline", "Hapus") { deleteSelected() }
        )
    }

    private fun audioTools(x: VeAudioItem): List<Tool> = listOf(
        Tool("content-cut", "Bagi") { splitAudioAtPlayhead() },
        Tool("volume-high", "Volume") { panels.audioVolume() },
        Tool("gradient-horizontal", "Fade") { panels.audioFade() },
        Tool("map-marker-right", "Mulai di sini") { moveAudioToPlayhead() },
        Tool("content-copy", "Duplikat") { duplicateAudio() },
        Tool("delete-outline", "Hapus") { deleteAudio() }
    )

    private fun overlayTools(o: VeOverlay): List<Tool> {
        val textual = o.kind == "text" || o.kind == "caption"
        val tools = ArrayList<Tool>()
        if (textual) {
            tools.add(Tool("pencil-outline", "Edit") { panels.textEditor(focus = true) })
            tools.add(Tool("format-font", "Gaya") { panels.textStyle() })
        } else {
            tools.add(Tool("tune-vertical", "Atur") { panels.overlayAdjust() })
        }
        tools.add(Tool("animation-play-outline", "Animasi") { panels.animation() })
        tools.add(Tool("content-cut", "Bagi") { splitOverlayAtPlayhead() })
        tools.add(Tool("content-copy", "Duplikat") { duplicateOverlay() })
        tools.add(Tool("arrange-bring-to-front", "Ke depan") { reorderOverlay(true) })
        tools.add(Tool("arrange-send-to-back", "Ke belakang") { reorderOverlay(false) })
        tools.add(Tool("delete-outline", "Hapus") { deleteOverlay() })
        return tools
    }

    private fun effectTools(e: VeEffect): List<Tool> = listOf(
        Tool("tune-vertical", "Intensitas") { panels.effectAdjust() },
        Tool("shimmer", "Ganti efek") { panels.effects(replace = true) },
        Tool("content-copy", "Duplikat") { duplicateEffect() },
        Tool("delete-outline", "Hapus") { deleteEffect() }
    )

    private fun requireMedia(block: () -> Unit) {
        if (p.clips.isEmpty()) { a.toast("Impor video atau foto dulu"); return }
        block()
    }

    private fun selectTargetClip() {
        targetClip()?.let { if (p.selKind != "clip" || p.selId != it.id) { p.selKind = "clip"; p.selId = it.id; timeline.invalidate() } }
    }

    private fun openClipTools() {
        val c = targetClip()
        if (c == null) { a.toast("Impor video atau foto dulu"); return }
        select("clip", c.id)
    }

    // ------------------------------------------------------------------ panel container

    private fun maxPanelHeight(): Int = (a.resources.displayMetrics.heightPixels * 0.46f).toInt().coerceAtLeast(dp(240))

    private class MaxHeightScroll(ctx: android.content.Context, private val maxH: Int) : ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }
    }

    /**
     * Tampilkan panel parameter menggantikan dock. [onClose] dipanggil saat panel ditutup (✓/Back).
     * [hideTimeline] = true (panel teks): timeline disembunyikan agar pratinjau tetap luas saat keyboard terbuka.
     */
    fun openPanel(title: String, body: View, hideTimeline: Boolean = false, onClose: (() -> Unit)? = null) {
        panelOnClose?.invoke()
        panelOpen = true
        panelOnClose = onClose
        immersive = hideTimeline
        dock.removeAllViews()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        // Kepala panel sama dengan Foto Studio: judul 14 tebal · tutup (chevron bawah).
        val header = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(4), 0)
        }
        header.addView(kit.text(title, 14f, kit.ink, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.icon("chevron-down", 22f, kit.ink, "Tutup panel") { closePanel() }, LinearLayout.LayoutParams(dp(44), dp(40)))
        box.addView(header, LinearLayout.LayoutParams(-1, dp(42)))
        val scroll = MaxHeightScroll(a, maxPanelHeight()).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(12), 0, dp(12), dp(10))
            clipToPadding = false
            addView(body)
        }
        box.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        dock.addView(box, FrameLayout.LayoutParams(-1, -2))
        setCompact(true)
        pushBack()
    }

    fun closePanel() {
        if (!panelOpen) return
        val cb = panelOnClose
        panelOnClose = null
        panelOpen = false
        immersive = false
        kit.hideKeyboard(dock)
        cb?.invoke()
        saveNow()
        refreshDock()
        layoutMedia()
    }

    private fun closePanelSilently() {
        if (!panelOpen) return
        val cb = panelOnClose
        panelOnClose = null
        panelOpen = false
        immersive = false
        kit.hideKeyboard(dock)
        cb?.invoke()
    }

    private fun setCompact(value: Boolean) {
        val wantImmersive = value && immersive
        if (compact == value && timelineBox.visibility == (if (wantImmersive) View.GONE else timelineVisibility())) return
        compact = value
        if (fullscreen) return
        (timelineBox.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.height = timelineHeight()
            timelineBox.layoutParams = lp
        }
        timelineBox.visibility = if (wantImmersive) View.GONE else View.VISIBLE
        leftControls.visibility = if (value) View.GONE else View.VISIBLE
        stage.post { layoutMedia() }
    }

    private fun timelineVisibility(): Int = if (fullscreen) View.GONE else View.VISIBLE

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        fsBtn.setIconName(if (fullscreen) "fullscreen-exit" else "fullscreen")
        val vis = if (fullscreen) View.GONE else View.VISIBLE
        topBar.visibility = vis
        timelineBox.visibility = if (!fullscreen && panelOpen && immersive) View.GONE else vis
        dock.visibility = vis
        dockLine.visibility = vis
        if (fullscreen) pushBack()
        stage.post { layoutMedia() }
    }

    // ------------------------------------------------------------------ clip actions

    private fun requireClip(): VeClip? {
        val c = selectedClip()
        if (c == null) a.toast("Pilih klip di timeline dulu")
        return c
    }

    fun splitAtPlayhead() {
        var t = 0L
        var clip: VeClip? = null
        var local = 0L
        for (c in p.clips) {
            val d = c.timelineMs()
            if (p.playheadMs < t + d) { clip = c; local = p.playheadMs - t; break }
            t += d
        }
        val c = clip ?: run { a.toast("Timeline kosong"); return }
        if (p.selKind == "clip" && p.selId != c.id) {
            a.toast("Geser playhead ke dalam klip yang dipilih untuk membagi")
            return
        }
        if (local < 200L || local > c.timelineMs() - 200L) { a.toast("Geser playhead ke tengah klip untuk membagi"); return }
        playback.pause()
        checkpoint()
        val idx = p.clips.indexOfFirst { it.id == c.id }
        val first: VeClip
        val second: VeClip
        if (c.isImage) {
            first = c.copy(id = UUID.randomUUID().toString(), sourceDurationMs = local, trimStartMs = 0L, trimEndMs = 0L)
            second = c.copy(id = UUID.randomUUID().toString(), sourceDurationMs = c.durationMs() - local, trimStartMs = 0L, trimEndMs = 0L, transition = "none")
        } else {
            val cut = c.trimStartMs + (local * c.effectiveSpeed()).toLong()
            first = c.copy(id = UUID.randomUUID().toString(), trimEndMs = cut)
            second = c.copy(id = UUID.randomUUID().toString(), trimStartMs = cut, transition = "none")
        }
        p.clips[idx] = first
        p.clips.add(idx + 1, second)
        p.selKind = "clip"
        p.selId = second.id
        afterChange()
        a.toast("Klip dibagi")
    }

    private fun trimToPlayhead(left: Boolean) {
        val c = requireClip() ?: return
        val start = p.clipStart(c)
        val local = p.playheadMs - start
        if (local < 200L || local > c.timelineMs() - 200L) { a.toast("Geser playhead ke dalam klip (min. 0,2 dtk dari tepi)"); return }
        playback.pause()
        checkpoint()
        if (c.isImage) {
            c.sourceDurationMs = if (left) c.durationMs() - local else local
            c.trimStartMs = 0L
            c.trimEndMs = 0L
        } else {
            val cut = c.trimStartMs + (local * c.effectiveSpeed()).toLong()
            if (left) c.trimStartMs = cut else c.trimEndMs = cut
        }
        p.playheadMs = if (left) start else min(p.totalMs(), start + c.timelineMs())
        afterChange()
    }

    fun deleteSelected() {
        val c = requireClip() ?: return
        playback.pause()
        checkpoint()
        val idx = p.clips.indexOfFirst { it.id == c.id }
        p.clips.removeAll { it.id == c.id }
        if (p.clips.isEmpty()) p.clearSelection()
        else { val n = p.clips[idx.coerceAtMost(p.clips.lastIndex)]; p.selKind = "clip"; p.selId = n.id }
        afterChange()
    }

    private fun duplicateSelected() {
        val c = requireClip() ?: return
        checkpoint()
        val idx = p.clips.indexOfFirst { it.id == c.id }
        val copy = c.copy(id = UUID.randomUUID().toString(), transition = "none")
        p.clips.add(idx + 1, copy)
        p.selKind = "clip"
        p.selId = copy.id
        afterChange()
    }

    private fun moveSelected(dir: Int) {
        val c = requireClip() ?: return
        val idx = p.clips.indexOfFirst { it.id == c.id }
        val target = idx + dir
        if (target !in p.clips.indices) return
        playback.pause()
        checkpoint()
        p.clips.removeAt(idx)
        p.clips.add(target, c)
        p.playheadMs = p.clipStart(c)
        afterChange()
    }

    private fun rotateSelected() {
        val c = requireClip() ?: return
        checkpoint()
        c.rotation = (c.rotation + 90) % 360
        afterChange(reload = false)
    }

    private fun flipSelected() {
        val c = requireClip() ?: return
        checkpoint()
        c.flipH = !c.flipH
        afterChange(reload = false)
    }

    /** Bekukan frame di playhead: klip dibagi di sini dan foto 2 dtk disisipkan di antaranya. */
    private fun freezeFrame() {
        if (freezeBusy) { a.toast("Sedang mengambil frame…"); return }
        val c = requireClip() ?: return
        if (c.isImage) { a.toast("Bekukan hanya untuk klip video"); return }
        val start = p.clipStart(c)
        val local = (p.playheadMs - start).coerceIn(0L, c.timelineMs())
        // Sidik jari klip: bila klip diubah/dibagi/dihapus selama frame diambil, hasil dibuang (bukan menimpa data baru).
        val clipId = c.id
        val trimS = c.trimStartMs
        val trimE = c.trimEndMs
        val spd = c.speed
        val srcMs = c.trimStartMs + (local * c.effectiveSpeed()).toLong()
        val srcUri = c.uri
        playback.pause()
        freezeBusy = true
        a.toast("Mengambil frame…")
        Thread {
            var f: File? = null
            try {
                val r = MediaMetadataRetriever()
                val frame = try {
                    r.setDataSource(a, srcUri)
                    r.getFrameAtTime(srcMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                } finally {
                    runCatching { r.release() }
                }
                if (frame == null) error("Frame kosong")
                val dir = File(a.filesDir, "ve_media").apply { mkdirs() }
                val file = File(dir, "freeze_${System.currentTimeMillis()}.jpg")
                f = file
                FileOutputStream(file).use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                val uri = Uri.fromFile(file)
                val thumb = Bitmap.createScaledBitmap(frame, 160, (160f * frame.height / frame.width).toInt().coerceAtLeast(1), true)
                handler.post {
                    freezeBusy = false
                    val cur = p.clips.firstOrNull { it.id == clipId }
                    if (disposed || cur == null || cur.trimStartMs != trimS || cur.trimEndMs != trimE || cur.speed != spd) {
                        runCatching { file.delete() }
                        if (!disposed) a.toast("Klip berubah saat mengambil frame — bekukan dibatalkan")
                        return@post
                    }
                    checkpoint()
                    p.thumbs[uri.toString()] = thumb
                    val name = "Bekukan ${VeTime.fmt(srcMs)}"
                    val freeze = VeClip(uri = uri, name = name, isImage = true, sourceDurationMs = 2000L, videoWidth = frame.width, videoHeight = frame.height,
                        rotation = (cur.rotation) % 360, filter = cur.filter, brightness = cur.brightness, contrast = cur.contrast, saturation = cur.saturation, temperature = cur.temperature)
                    var idx = p.clips.indexOfFirst { it.id == cur.id }
                    if (local in 200L..(cur.timelineMs() - 200L)) {
                        val cut = srcMs
                        val first = cur.copy(id = UUID.randomUUID().toString(), trimEndMs = cut)
                        val second = cur.copy(id = UUID.randomUUID().toString(), trimStartMs = cut, transition = "none")
                        p.clips[idx] = first
                        p.clips.add(idx + 1, second)
                        p.clips.add(idx + 1, freeze)
                    } else if (local < 200L) {
                        p.clips.add(idx, freeze)
                    } else {
                        idx += 1
                        p.clips.add(idx.coerceAtMost(p.clips.size), freeze)
                    }
                    p.library.add(VeMedia(uri, name, true, 2000L, frame.width, frame.height, 0))
                    p.selKind = "clip"
                    p.selId = freeze.id
                    afterChange()
                    a.toast("Frame dibekukan 2 detik")
                }
            } catch (e: Throwable) {
                runCatching { f?.delete() }
                handler.post { freezeBusy = false; a.toast("Bekukan gagal: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    /** Salin suara klip ke jalur audio sendiri dan bisukan klipnya. */
    private fun extractAudio() {
        val c = targetClip() ?: return
        if (c.isImage) { a.toast("Foto tidak punya suara"); return }
        if (c.speed != 1f) { a.toast("Ekstrak audio hanya untuk klip berkecepatan 1× — atur kecepatan kembali ke 1×"); return }
        if (c.volume <= 0f) { a.toast("Suara klip ini sudah dibisukan atau diekstrak"); return }
        a.toast("Memeriksa suara klip…")
        Thread {
            val has = VeImport.hasAudioTrack(a, c.uri)
            handler.post {
                if (!has) { a.toast("Klip ini tidak punya suara"); return@post }
                if (disposed || c.volume <= 0f) return@post
                checkpoint()
                val item = VeAudioItem(
                    uri = c.uri, name = c.name.substringBeforeLast('.'), kind = "ekstrak", startMs = p.clipStart(c),
                    sourceDurationMs = c.sourceDurationMs, srcStartMs = c.trimStartMs, srcEndMs = c.trimEndMs, volume = c.volume
                )
                p.audios.add(item)
                c.volume = 0f
                p.selKind = "audio"
                p.selId = item.id
                afterChange()
                a.toast("Suara diekstrak ke jalur audio — klip dibisukan")
            }
        }.start()
    }

    // ------------------------------------------------------------------ audio / overlay / effect actions

    private fun splitAudioAtPlayhead() {
        val x = selectedAudio() ?: return
        val t = p.playheadMs
        if (t < x.startMs + 200L || t > x.endMs() - 200L) { a.toast("Geser playhead ke dalam item audio untuk membagi"); return }
        checkpoint()
        val cut = x.srcStartMs + (t - x.startMs)
        val second = x.copy(id = UUID.randomUUID().toString(), startMs = t, srcStartMs = cut, fadeInMs = 0L)
        x.srcEndMs = cut
        x.fadeOutMs = 0L
        p.audios.add(p.audios.indexOf(x) + 1, second)
        p.selKind = "audio"
        p.selId = second.id
        afterChange(reload = false)
    }

    private fun moveAudioToPlayhead() {
        val x = selectedAudio() ?: return
        checkpoint()
        x.startMs = p.playheadMs
        afterChange(reload = false)
    }

    private fun duplicateAudio() {
        val x = selectedAudio() ?: return
        checkpoint()
        val copy = x.copy(id = UUID.randomUUID().toString(), startMs = x.endMs())
        p.audios.add(p.audios.indexOf(x) + 1, copy)
        p.selKind = "audio"
        p.selId = copy.id
        afterChange(reload = false)
    }

    private fun deleteAudio() {
        val x = selectedAudio() ?: return
        checkpoint()
        p.audios.remove(x)
        p.clearSelection()
        afterChange(reload = false)
    }

    private fun splitOverlayAtPlayhead() {
        val o = selectedOverlay() ?: return
        val t = p.playheadMs
        if (t < o.startMs + 200L || t > o.endMs() - 200L) { a.toast("Geser playhead ke dalam item untuk membagi"); return }
        checkpoint()
        val second = o.copy(id = UUID.randomUUID().toString(), startMs = t, durationMs = o.endMs() - t, animIn = "none")
        o.durationMs = t - o.startMs
        o.animOut = "none"
        p.overlays.add(p.overlays.indexOf(o) + 1, second)
        p.selKind = "overlay"
        p.selId = second.id
        afterChange(reload = false)
    }

    private fun duplicateOverlay() {
        val o = selectedOverlay() ?: return
        checkpoint()
        val copy = o.copy(id = UUID.randomUUID().toString(), startMs = min(max(0L, p.totalMs() - 500L), o.endMs()), cy = (o.cy + 0.08f).coerceAtMost(0.95f))
        p.overlays.add(copy)
        p.selKind = "overlay"
        p.selId = copy.id
        afterChange(reload = false)
    }

    private fun reorderOverlay(front: Boolean) {
        val o = selectedOverlay() ?: return
        checkpoint()
        p.overlays.remove(o)
        if (front) p.overlays.add(o) else p.overlays.add(0, o)
        afterChange(reload = false)
    }

    private fun deleteOverlay() {
        val o = selectedOverlay() ?: return
        checkpoint()
        p.overlays.remove(o)
        p.clearSelection()
        afterChange(reload = false)
    }

    private fun duplicateEffect() {
        val e = selectedEffect() ?: return
        checkpoint()
        val copy = e.copy(id = UUID.randomUUID().toString(), startMs = e.endMs())
        p.effects.add(copy)
        p.selKind = "effect"
        p.selId = copy.id
        afterChange(reload = false)
    }

    private fun deleteEffect() {
        val e = selectedEffect() ?: return
        checkpoint()
        p.effects.remove(e)
        p.clearSelection()
        afterChange(reload = false)
    }

    // ------------------------------------------------------------------ add items

    /** Durasi default item baru agar tidak melewati akhir timeline. */
    private fun defaultSpan(start: Long, preferred: Long): Long {
        val total = p.totalMs()
        return min(preferred, max(500L, total - start)).coerceAtLeast(500L)
    }

    private fun startForNewItem(): Long {
        val total = p.totalMs()
        return if (p.playheadMs >= total) max(0L, total - 1500L) else p.playheadMs
    }

    fun addText() {
        val start = startForNewItem()
        checkpoint()
        val o = VeOverlay.text(start, "Teks baru")
        o.durationMs = defaultSpan(start, 3000L)
        p.overlays.add(o)
        p.selKind = "overlay"
        p.selId = o.id
        afterChange(reload = false)
        panels.textEditor(focus = true)
    }

    fun addCaption() {
        val start = startForNewItem()
        checkpoint()
        val o = VeOverlay.caption(start, defaultSpan(start, 2500L), "Keterangan")
        p.overlays.add(o)
        p.selKind = "overlay"
        p.selId = o.id
        afterChange(reload = false)
        panels.textEditor(focus = true)
    }

    fun addSticker(icon: String, emoji: String) {
        val start = startForNewItem()
        checkpoint()
        val o = VeOverlay.sticker(start, icon, emoji)
        o.durationMs = defaultSpan(start, 3000L)
        p.overlays.add(o)
        p.selKind = "overlay"
        p.selId = o.id
        afterChange(reload = false)
    }

    fun addEffect(type: String) {
        val start = startForNewItem()
        checkpoint()
        val e = VeEffect(type = type, startMs = start, durationMs = defaultSpan(start, 2000L))
        p.effects.add(e)
        p.selKind = "effect"
        p.selId = e.id
        afterChange(reload = false)
    }

    // ------------------------------------------------------------------ pickers & import

    private fun startPicker(type: String, mimes: Array<String>?, multiple: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = type
            if (mimes != null) putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            @Suppress("DEPRECATION")
            a.startActivityForResult(intent, VE_PICK_VIDEO)
        } catch (e: Exception) {
            videoEditorPickPending = null
            a.toast("Tidak ada aplikasi pemilih file: ${e.message}")
        }
    }

    fun pickMedia() {
        videoEditorPickPending = { uris -> addMedia(uris) }
        startPicker("*/*", arrayOf("video/*", "image/*"), true)
    }

    fun pickAudio() {
        videoEditorPickPending = { uris ->
            val uri = uris.firstOrNull()
            if (uri != null) addAudioFile(uri)
        }
        startPicker("audio/*", null, false)
    }

    fun pickOverlayImage() {
        if (p.clips.isEmpty()) { a.toast("Impor video atau foto dulu"); return }
        videoEditorPickPending = { uris ->
            val uri = uris.firstOrNull()
            if (uri != null) addOverlayImage(uri)
        }
        startPicker("image/*", null, false)
    }

    fun pickSrt() {
        videoEditorPickPending = { uris ->
            val uri = uris.firstOrNull()
            if (uri != null) importSrt(uri)
        }
        startPicker("*/*", arrayOf("application/x-subrip", "text/plain", "text/srt", "application/octet-stream", "text/*"), false)
    }

    private fun isImageUri(uri: Uri): Boolean {
        val mime = runCatching { a.contentResolver.getType(uri) }.getOrNull()
        if (mime != null) return mime.startsWith("image/")
        val name = uri.toString().lowercase()
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".bmp") || name.endsWith(".heic")
    }

    private fun addMedia(uris: List<Uri>) {
        a.toast("Memproses ${uris.size} media…")
        Thread {
            val added = ArrayList<VeProbe>()
            for (uri in uris) {
                runCatching {
                    a.persistUriPermissionsSafely(uri, wantWrite = false)
                    val name = runCatching { a.displayNameOf(uri) }.getOrDefault(uri.lastPathSegment ?: "media")
                    val probe = if (isImageUri(uri)) VeImport.probeImage(a, uri, name) else VeImport.probeVideo(a, uri, name)
                    if (probe != null) added.add(probe)
                }
            }
            handler.post {
                if (added.isEmpty()) { a.toast("Tidak ada media yang bisa dibaca"); return@post }
                val wasEmpty = p.clips.isEmpty()
                checkpoint()
                var firstNew: VeClip? = null
                for (pr in added) {
                    val m = pr.media
                    pr.poster?.let { p.thumbs[m.uri.toString()] = it }
                    if (p.library.none { it.uri == m.uri }) p.library.add(m)
                    val clip = clipFromMedia(m)
                    if (firstNew == null) firstNew = clip
                    p.clips.add(clip)
                }
                firstNew?.let { p.selKind = "clip"; p.selId = it.id; p.playheadMs = p.clipStart(it) }
                if (wasEmpty) p.zoom = min(timeline.fitZoom(), 3f)
                afterChange()
                refreshCoverThumb()
                a.toast("Ditambahkan ${added.size} media ke timeline")
            }
        }.start()
    }

    fun clipFromMedia(m: VeMedia): VeClip = VeClip(
        uri = m.uri, name = m.name, isImage = m.isImage, sourceDurationMs = m.durationMs,
        videoWidth = m.width, videoHeight = m.height, sourceRotation = m.rotation
    )

    fun addLibraryMedia(m: VeMedia) {
        checkpoint()
        val clip = clipFromMedia(m)
        p.clips.add(clip)
        p.selKind = "clip"
        p.selId = clip.id
        p.playheadMs = p.clipStart(clip)
        afterChange()
        a.toast("${m.name} ditambahkan")
    }

    private fun addAudioFile(uri: Uri) {
        a.toast("Membaca audio…")
        Thread {
            a.persistUriPermissionsSafely(uri, wantWrite = false)
            val name = runCatching { a.displayNameOf(uri) }.getOrDefault("Musik").substringBeforeLast('.')
            val dur = VeImport.audioDuration(a, uri)
            handler.post {
                if (dur < 300L) { a.toast("Audio tidak bisa dibaca"); return@post }
                checkpoint()
                val start = if (p.playheadMs >= p.totalMs()) 0L else p.playheadMs
                val item = VeAudioItem(uri = uri, name = name, kind = "musik", startMs = start, sourceDurationMs = dur)
                p.audios.add(item)
                p.selKind = "audio"
                p.selId = item.id
                afterChange(reload = false)
                a.toast("Musik ditambahkan di ${VeTime.fmt(start)}")
            }
        }.start()
    }

    private fun addOverlayImage(uri: Uri) {
        Thread {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val final = try {
                a.persistUriPermissionsSafely(uri, wantWrite = false)
                VeImport.normalizeImage(a, uri).also { f ->
                    runCatching { a.contentResolver.openInputStream(f)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) } }
                }
            } catch (t: Throwable) {
                uri
            }
            handler.post {
                if (disposed) return@post
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { a.toast("Gambar tidak bisa dibaca"); return@post }
                val start = startForNewItem()
                checkpoint()
                val o = VeOverlay.image(start, final, bounds.outWidth.toFloat() / bounds.outHeight)
                o.durationMs = defaultSpan(start, 3000L)
                p.overlays.add(o)
                p.selKind = "overlay"
                p.selId = o.id
                afterChange(reload = false)
            }
        }.start()
    }

    private fun importSrt(uri: Uri) {
        Thread {
            val text = runCatching { a.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
            val cues = if (text == null) emptyList() else VeSrt.parse(text)
            handler.post {
                if (cues.isEmpty()) { a.toast("File SRT kosong atau formatnya tidak dikenali"); return@post }
                checkpoint()
                val total = p.totalMs()
                var beyond = 0
                for (c in cues) {
                    if (c.startMs >= total) beyond++
                    p.overlays.add(VeOverlay.caption(c.startMs, c.endMs - c.startMs, c.text))
                }
                afterChange(reload = false)
                a.toast("${cues.size} keterangan diimpor" + if (beyond > 0) " ($beyond di luar durasi video)" else "")
            }
        }.start()
    }

    private fun exportSrt() {
        val cues = p.overlays.filter { it.kind == "caption" && it.text.isNotBlank() }.map { VeCue(it.startMs, it.endMs(), it.text) }
        if (cues.isEmpty()) { a.toast("Belum ada keterangan. Tambahkan lewat Teks → Keterangan atau impor SRT"); return }
        Thread {
            val result = runCatching { VePublish.text(a, VeSrt.format(cues), p.name.ifBlank { "keterangan" }, "srt", "application/x-subrip") }
            handler.post {
                result.onSuccess { pub ->
                    a.themedDialog()
                        .setTitle("SRT disimpan")
                        .setMessage("${cues.size} keterangan\n${pub.label}")
                        .setNegativeButton("Tutup", null)
                        .setPositiveButton("Bagikan") { _, _ ->
                            runCatching {
                                a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "application/x-subrip"
                                    putExtra(Intent.EXTRA_STREAM, pub.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "Bagikan SRT"))
                            }
                        }
                        .show()
                }.onFailure { a.toast("Gagal menyimpan SRT: ${it.message}") }
            }
        }.start()
    }

    /** Thumbnail tidak disimpan di prefs; buat ulang untuk proyek yang dipulihkan. */
    private fun regenerateMissingThumbs() {
        val items = (p.library.map { it.uri to it.isImage } + p.clips.map { it.uri to it.isImage })
            .distinctBy { it.first.toString() }
            .filter { !p.thumbs.containsKey(it.first.toString()) }
        if (items.isEmpty()) return
        Thread {
            for ((uri, isImage) in items) {
                val bmp = runCatching {
                    if (isImage) VeAssets.decodeFit(a, uri, 200)
                    else {
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(a, uri)
                            r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { f ->
                                Bitmap.createScaledBitmap(f, 160, (160f * f.height / f.width).toInt().coerceAtLeast(1), true)
                            }
                        } finally {
                            runCatching { r.release() }
                        }
                    }
                }.getOrNull() ?: continue
                handler.post { p.thumbs[uri.toString()] = bmp }
            }
            handler.post {
                if (root.isAttachedToWindow) {
                    timeline.invalidate()
                    refreshCoverThumb()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ menus

    /** Menu daftar bertema — sama dengan menu Foto Studio & Audio Editor. */
    private fun menu(title: String, items: List<Pair<String, () -> Unit>>) {
        a.themedDialog()
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun showAddMediaMenu(anchor: View) {
        menu("Tambah media", listOf(
            "Video & foto dari perangkat" to { pickMedia() },
            "Pustaka proyek (${p.library.size})" to { panels.library() },
            "Latar warna polos…" to { panels.colorBackground() }
        ))
    }

    /** Klip warna polos 3 detik (PNG kecil) — berguna sebagai latar teks atau jeda. */
    fun addColorClip(color: Int) {
        Thread {
            val dir = File(a.filesDir, "ve_media").apply { mkdirs() }
            val f = File(dir, "warna_" + String.format("%08X", color) + ".png")
            if (!f.exists()) {
                val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(color)
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val uri = Uri.fromFile(f)
            handler.post {
                val m = VeMedia(uri, "Warna", true, 3000L, 16, 16, 0)
                val poster = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }
                p.thumbs[uri.toString()] = poster
                if (p.library.none { it.uri == m.uri }) p.library.add(m)
                addLibraryMedia(m)
            }
        }.start()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun showProjectMenu(anchor: View) {
        menu("Proyek", listOf(
            "Ganti nama (${p.name})" to { renameProject() },
            "Pustaka media" to { panels.library() },
            "Impor SRT…" to { requireMedia { pickSrt() } },
            "Ekspor SRT" to { exportSrt() },
            (if (p.showText) "Sembunyikan teks & stiker (juga di ekspor)" else "Tampilkan teks & stiker") to { p.showText = !p.showText; afterChange(reload = false) },
            (if (overlayView.showGuides) "Sembunyikan garis panduan" else "Tampilkan garis panduan pratinjau") to { overlayView.showGuides = !overlayView.showGuides },
            "Kosongkan timeline" to { confirmClear() },
            "Bantuan" to { showHelp() }
        ))
    }

    private fun renameProject() {
        val input = kit.field("Nama proyek", p.name)
        val box = LinearLayout(a).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        a.themedDialog()
            .setTitle("Nama proyek")
            .setView(box)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) { p.name = n; save() }
            }
            .show()
    }

    private fun confirmClear() {
        if (p.clips.isEmpty() && p.audios.isEmpty() && p.overlays.isEmpty() && p.effects.isEmpty()) { a.toast("Timeline sudah kosong"); return }
        a.themedDialog()
            .setTitle("Kosongkan timeline?")
            .setMessage("Semua klip, audio, teks, dan efek dihapus dari timeline. Pustaka media tetap ada dan langkah ini bisa di-undo.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Kosongkan") { _, _ ->
                playback.pause()
                checkpoint()
                p.clips.clear(); p.audios.clear(); p.overlays.clear(); p.effects.clear()
                p.clearSelection()
                p.playheadMs = 0L
                afterChange()
            }
            .show()
    }

    private fun showHelp() {
        a.themedDialog()
            .setTitle("Cara memakai Video Editor")
            .setMessage(
                "• Geser timeline untuk memindah waktu; cubit untuk zoom. Playhead selalu di tengah.\n" +
                "• Ketuk item untuk memilih → alat khusus item muncul di bawah. Tarik gagang putih untuk memangkas.\n" +
                "• Tahan item lalu geser untuk memindahkan (klip utama: mengubah urutan). Magnet menempel ke playhead & tepi item.\n" +
                "• Teks/stiker: seret langsung di pratinjau; gagang kanan-bawah atau cubit 2 jari = ukuran + putar; ✕ hapus; T edit.\n" +
                "• Audio: Musik dari file, Rekam suara (mikrofon), Ekstrak suara dari klip. Atur volume & fade per item.\n" +
                "• Auto-cut: hapus bagian hening atau bagi video per adegan (analisis di perangkat).\n" +
                "• Ekspor: Render penuh (efek, teks, audio campur) atau Cepat (tanpa encoding ulang, hanya trim + gabung).\n\n" +
                "Catatan: transisi antar klip dan efek berwaktu tampil di hasil ekspor; pratinjau menampilkan perkiraannya."
            )
            .setPositiveButton("Mengerti", null)
            .show()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun dispose() {
        disposed = true
        playback.release()
        handler.removeCallbacks(exportPoller)
        handler.removeCallbacks(saveRunnable)
        OverlayBack.remove(backHandler)
        panels.dispose()
        exporter.detach()
        VeAssets.clear()
        save()
    }

}

/**
 * Ekspor klip video berurutan ke satu MP4 dengan MediaExtractor + MediaMuxer (stream copy, tanpa encoding ulang).
 * Hanya trim + gabung (filter, kecepatan, teks, audio tambahan tidak diterapkan).
 */
internal fun MainActivity.exportVideoProject(clips: List<VeClip>, includeAudio: Boolean = true, cancelled: () -> Boolean = { false }): File {
    val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
    if (!dir.exists()) dir.mkdirs()
    val outFile = File(dir, "ve_export_${System.currentTimeMillis()}.mp4")
    if (outFile.exists()) outFile.delete()

    // Pra-pindai: semua klip harus berformat sama agar bisa digabung tanpa encode ulang.
    class Info(val vMime: String, val w: Int, val h: Int, val rot: Int, val aMime: String?, val aRate: Int, val aCh: Int)
    fun scan(clip: VeClip): Info {
        val ex = MediaExtractor()
        try {
            contentResolver.openFileDescriptor(clip.uri, "r")?.use { pfd -> ex.setDataSource(pfd.fileDescriptor) } ?: ex.setDataSource(this, clip.uri, null)
            var vf: MediaFormat? = null
            var af: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && vf == null) vf = f
                else if (mime.startsWith("audio/") && af == null) af = f
            }
            if (vf == null) throw IllegalStateException("Klip ${clip.name} tidak punya track video")
            val srcRot = try { vf.getInteger("rotation-degrees") } catch (_: Exception) { 0 }
            val w = try { vf.getInteger(MediaFormat.KEY_WIDTH) } catch (_: Exception) { 0 }
            val h = try { vf.getInteger(MediaFormat.KEY_HEIGHT) } catch (_: Exception) { 0 }
            val aRate = try { af?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 0 } catch (_: Exception) { 0 }
            val aCh = try { af?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 0 } catch (_: Exception) { 0 }
            return Info(vf.getString(MediaFormat.KEY_MIME) ?: "", w, h, ((srcRot + clip.rotation) % 360 + 360) % 360, af?.getString(MediaFormat.KEY_MIME), aRate, aCh)
        } finally {
            ex.release()
        }
    }
    val infos = clips.map { scan(it) }
    val first = infos.first()
    for ((i, inf) in infos.withIndex()) {
        if (inf.vMime != first.vMime || inf.w != first.w || inf.h != first.h || inf.rot != first.rot) {
            throw IllegalStateException("Klip ${clips[i].name} berbeda format/resolusi/orientasi dari klip pertama — Mode Cepat hanya bisa menggabung klip yang sama persis. Pakai Render penuh.")
        }
    }
    val audioInfo = if (includeAudio) infos.firstOrNull { it.aMime != null } else null
    if (audioInfo != null) {
        for ((i, inf) in infos.withIndex()) {
            if (inf.aMime != null && (inf.aMime != audioInfo.aMime || inf.aRate != audioInfo.aRate || inf.aCh != audioInfo.aCh)) {
                throw IllegalStateException("Format suara klip ${clips[i].name} berbeda — Mode Cepat tidak bisa menggabungnya. Pakai Render penuh.")
            }
        }
    }

    val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var muxerStarted = false
    var outVideoTrack = -1
    var outAudioTrack = -1
    var timeUs = 0L
    var ok = false

    try {
        for (clip in clips) {
            val extractor = MediaExtractor()
            try {
                contentResolver.openFileDescriptor(clip.uri, "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                } ?: extractor.setDataSource(this, clip.uri, null)

                var videoTrack = -1
                var audioTrack = -1
                var videoFormat: MediaFormat? = null
                var audioFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    when {
                        mime.startsWith("video/") && videoTrack < 0 -> { videoTrack = i; videoFormat = f }
                        mime.startsWith("audio/") && audioTrack < 0 -> { audioTrack = i; audioFormat = f }
                    }
                }
                if (videoTrack < 0 || videoFormat == null) {
                    throw IllegalStateException("Klip ${clip.name} tidak punya track video")
                }

                if (!muxerStarted) {
                    outVideoTrack = muxer.addTrack(videoFormat)
                    if (audioInfo != null) {
                        // Format suara dari klip pertama yang punya suara (klip ini bisa tanpa suara).
                        val af = audioFormat ?: run {
                            val src = clips[infos.indexOfFirst { it.aMime != null }]
                            val ex = MediaExtractor()
                            try {
                                contentResolver.openFileDescriptor(src.uri, "r")?.use { pfd -> ex.setDataSource(pfd.fileDescriptor) } ?: ex.setDataSource(this, src.uri, null)
                                var f: MediaFormat? = null
                                for (i in 0 until ex.trackCount) { val tf = ex.getTrackFormat(i); if ((tf.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { f = tf; break } }
                                f
                            } finally { ex.release() }
                        }
                        if (af != null) outAudioTrack = muxer.addTrack(af)
                    }
                    if (first.rot != 0) runCatching { muxer.setOrientationHint(first.rot) }
                    muxer.start()
                    muxerStarted = true
                }

                val startUs = clip.trimStartMs * 1000L
                val endUs = clip.effectiveEnd() * 1000L
                val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()

                // Potong lossless hanya bisa dimulai di keyframe: mulai dari keyframe terakhir sebelum titik potong
                // (awal klip bisa sedikit lebih awal, tetapi tanpa frame rusak); suara disejajarkan ke titik yang sama.
                extractor.selectTrack(videoTrack)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val syncUs = extractor.sampleTime.let { if (it in 0L..startUs) it else startUs }
                while (true) {
                    if (cancelled()) throw IllegalStateException("Ekspor dibatalkan")
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val pts = extractor.sampleTime
                    if (pts > endUs) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = timeUs + max(0L, pts - syncUs)
                    info.flags = extractor.bufferFlags()
                    muxer.writeSampleData(outVideoTrack, buffer, info)
                    extractor.advance()
                }
                if (outAudioTrack >= 0 && audioTrack >= 0) {
                    extractor.unselectTrack(videoTrack)
                    extractor.selectTrack(audioTrack)
                    extractor.seekTo(syncUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    while (true) {
                        if (cancelled()) throw IllegalStateException("Ekspor dibatalkan")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val pts = extractor.sampleTime
                        if (pts < syncUs) { extractor.advance(); continue }
                        if (pts > endUs) break
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = timeUs + (pts - syncUs)
                        info.flags = extractor.bufferFlags()
                        muxer.writeSampleData(outAudioTrack, buffer, info)
                        extractor.advance()
                    }
                }
                timeUs += max(1L, endUs - syncUs)
            } finally {
                extractor.release()
            }
        }
        if (!muxerStarted) error("Tidak ada data untuk diekspor")
        ok = true
    } finally {
        if (muxerStarted) {
            try { muxer.stop() } catch (_: Exception) {}
        }
        muxer.release()
        if (!ok) outFile.delete()
    }

    if (!outFile.exists() || outFile.length() < 1000L) {
        outFile.delete()
        error("Hasil ekspor kosong atau terlalu kecil — codec klip mungkin tidak kompatibel untuk digabung")
    }
    return outFile
}
