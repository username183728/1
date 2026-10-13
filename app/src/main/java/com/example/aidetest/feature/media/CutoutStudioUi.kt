package com.example.aidetest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Request code pemilih foto Potong Manual (dirutekan MainActivity bersama 1220–1224). */
internal const val CO_PICK_IMAGE = 1221
/** Sisi terpanjang foto kerja: tiap layer = 1 bitmap ARGB penuh (2048² ≈ 16 MB). */
internal const val CO_MAX_SIDE = 2048
private const val CO_PREF_LAST = "cutout_last_project"
private const val CO_MASK = "mask"

/** Satu layer potongan: bitmap ARGB seukuran foto (mutable), urutan bawah → atas di daftar. */
internal class CoLayer(val id: String, var name: String, var bitmap: Bitmap, var visible: Boolean = true, var locked: Boolean = false, var opacity: Float = 1f) {
    /** Perlu ditulis ulang ke PNG saat simpan berikutnya. */
    @Volatile var dirty = true
}

/** Data satu ubin riwayat; mentah saat dibuat, dikompres di thread kerja. */
private class CoTileData(val rect: CoRect, @Volatile var before: ByteArray, @Volatile var after: ByteArray, val bpp: Int) {
    @Volatile var packed = false
    fun raw(undo: Boolean): ByteArray {
        val d = if (undo) before else after
        return if (packed) CoPack.inflate(d, rect.w * rect.h * bpp) else d
    }
    fun pack() {
        if (packed) return
        val b = CoPack.deflate(before)
        val a = CoPack.deflate(after)
        before = b; after = a; packed = true
    }
    fun bytes(): Long = (before.size + after.size).toLong()
}

private abstract class CoStep(val label: String) {
    abstract fun bytes(): Long
}

/** Perubahan piksel (layer) atau seleksi ([CO_MASK]) per ubin. */
private class CoTileStep(label: String, val target: String, val tiles: List<CoTileData>) : CoStep(label) {
    override fun bytes(): Long = tiles.sumOf { it.bytes() }
}

/** Layer dibuat ([created] = true) atau dihapus; piksel layer disimpan terkompres untuk dibuat ulang. */
private class CoLayerStep(label: String, val created: Boolean, val meta: CoLayerMeta, val index: Int) : CoStep(label) {
    @Volatile var pixels: ByteArray? = null
    override fun bytes(): Long = (pixels?.size ?: 0).toLong()
}

/** Urutan + atribut layer (nama, tampil, kunci, opasitas) sebelum/sesudah. */
private class CoOrderStep(label: String, val before: List<CoLayerMeta>, val after: List<CoLayerMeta>) : CoStep(label) {
    override fun bytes(): Long = 256L * (before.size + after.size)
}

private class CoGroupStep(label: String, val parts: List<CoStep>) : CoStep(label) {
    override fun bytes(): Long = parts.sumOf { it.bytes() }
}

/**
 * Foto Studio → tab Potong: editor potong manual untuk memisahkan karakter ke layer transparan
 * (rambut, kepala, mata, mulut, baju, badan). Seleksi kuas/laso/poligon, penghapus, pulihkan,
 * klon & kuas warna, feather & rapikan tepi, layer, undo/redo berbasis ubin, simpan proyek privat,
 * ekspor PNG, dan kirim ke Desain sebagai layer gambar. Logika piksel murni ada di CutoutCore.kt.
 */
internal class CutoutStudioUi(private val a: MainActivity, private val onSendToDesign: () -> Unit) {
    private enum class Tool(val icon: String, val label: String) {
        PAN("move-resize", "Geser"),
        SELECT("brush", "Seleksi"),
        WAND("magic-staff", "Magic Wand"),
        LASSO("selection-multiple", "Laso"),
        POLY("vector-polyline", "Poligon"),
        ERASE("broom", "Hapus"),
        RESTORE("restore", "Pulihkan"),
        CLONE("content-duplicate", "Klon"),
        PAINT("palette", "Kuas")
    }

    private val kit = ImgKit(a)
    private val c = kit.c
    val root = LinearLayout(a)
    private val topBar = LinearLayout(a)
    private val titleView = TextView(a)
    private val subtitleView = TextView(a)
    private val stage = FrameLayout(a)
    private val zoom = ZoomImageView(a, kit.workspace)
    private val busyChip = TextView(a)
    private val polyBar = LinearLayout(a)
    private val emptyCard = LinearLayout(a)
    private val options = LinearLayout(a)
    private val actions = LinearLayout(a)
    private val actionsScroll = HorizontalScrollView(a)
    private val dock = LinearLayout(a)
    private val dockScroll = HorizontalScrollView(a)
    private val sheet = ImgSheet(kit, stage)
    private val navCells = LinkedHashMap<String, LinearLayout>()
    private var undoBtn: View? = null
    private var redoBtn: View? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var alive = true
    private var busyCount = 0

    // ---- dokumen
    private var projDir: File? = null
    private var projName = "Potongan"
    private var sourceFile = ""
    private var source: Bitmap? = null
    private val layers = ArrayList<CoLayer>()
    private var activeId: String? = null
    private var imgW = 0
    private var imgH = 0
    private var mask = ByteArray(0)
    private var overlayBmp: Bitmap? = null
    private val history = CoHistory<CoStep>(40, 64L * 1024 * 1024) { it.bytes() }

    // ---- alat
    private var tool = Tool.SELECT
    private var selMode = CoSelMode.ADD
    private var brushSize = 40
    private var hardness = 70
    private var strength = 100
    private var wandTol = 32
    private var clipToSel = false
    private var paintColor = Color.rgb(239, 68, 68)
    private var cloneFromSource = false
    private var showSel = true
    private var ghost = false
    private var offsetOn = true
    private var pickingColor = false
    private var pickingCloneSrc = false
    private val clone = CoClone()

    // ---- sentuhan
    private var touching = false
    private var fingerX = 0f
    private var fingerY = 0f
    private var ptX = 0f
    private var ptY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var stroke: StrokeLog? = null
    private var strokeClip: ByteArray? = null
    private val pathXs = ArrayList<Float>()
    private val pathYs = ArrayList<Float>()
    private var downScreenX = 0f
    private var downScreenY = 0f
    private var movedFar = false
    private var cloneArmed = false
    private val longPress = Runnable { onLongPress() }
    private var bufA = IntArray(0)
    private var bufB = IntArray(0)
    private var ovBuf = IntArray(0)

    // ---- cat
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val loupePaint = Paint()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private var layerBody: LinearLayout? = null

    private fun dp(v: Int): Int = a.dp(v)

    private val gesture = object : ZoomImageView.Gesture {
        override fun onDown(x: Float, y: Float): Boolean {
            if (tool == Tool.PAN && !pickingColor && !pickingCloneSrc) return false
            if (!hasDoc() || busyCount > 0) return true
            touching = true
            effPoint(x, y)
            downScreenX = zoom.toScreenX(x); downScreenY = zoom.toScreenY(y)
            movedFar = false
            lastX = ptX; lastY = ptY
            if (pickingColor) return true
            if (pickingCloneSrc) return true
            when (tool) {
                Tool.LASSO -> { pathXs.clear(); pathYs.clear(); pathXs.add(ptX); pathYs.add(ptY) }
                Tool.POLY -> renderPolyBar()
                Tool.CLONE -> { cloneArmed = false; main.postDelayed(longPress, 480L) }
                Tool.WAND -> Unit // apply on up (tap)
                else -> beginStroke()
            }
            zoom.invalidate()
            return true
        }

        override fun onMove(x: Float, y: Float) {
            if (!touching) return
            effPoint(x, y)
            if (!movedFar && hypot(zoom.toScreenX(x) - downScreenX, zoom.toScreenY(y) - downScreenY) > dp(8)) {
                movedFar = true
                main.removeCallbacks(longPress)
                if (tool == Tool.CLONE && !cloneArmed && !pickingCloneSrc && !pickingColor && stroke == null) beginStroke()
            }
            if (pickingColor || pickingCloneSrc) { zoom.invalidate(); return }
            when (tool) {
                Tool.LASSO -> {
                    val lx = pathXs.last(); val ly = pathYs.last()
                    if (hypot(ptX - lx, ptY - ly) * zoom.scale() >= dp(3)) { pathXs.add(ptX); pathYs.add(ptY) }
                }
                Tool.POLY, Tool.PAN -> Unit
                else -> if (stroke != null) {
                    val spacing = max(1f, brushSize * 0.18f)
                    for ((sx, sy) in CoPaint.strokeSteps(lastX, lastY, ptX, ptY, spacing)) { dab(sx, sy); lastX = sx; lastY = sy }
                }
            }
            zoom.invalidate()
        }

        override fun onUp(x: Float, y: Float) {
            if (!touching) return
            effPoint(x, y)
            main.removeCallbacks(longPress)
            touching = false
            when {
                pickingColor -> { pickingColor = false; paintColor = sampleColor(ptX, ptY); renderOptions() }
                pickingCloneSrc -> { pickingCloneSrc = false; clone.setSource(ptX, ptY); a.toast("Titik sumber klon dipasang"); renderOptions() }
                tool == Tool.LASSO -> commitPath()
                tool == Tool.POLY -> polyTap()
                tool == Tool.WAND -> applyMagicWand()
                tool == Tool.CLONE && stroke == null -> if (!cloneArmed) { beginStroke(); endStroke() }
                else -> endStroke()
            }
            renderPolyBar()
            zoom.invalidate()
        }

        override fun onCancel() {
            main.removeCallbacks(longPress)
            touching = false
            stroke?.revert()
            stroke = null
            if (tool == Tool.LASSO) { pathXs.clear(); pathYs.clear() }
            renderPolyBar()
            zoom.invalidate()
        }
    }

    /** Riwayat sapuan: ubin disalin saat pertama kali tersentuh (sebelum), lalu dibaca lagi di akhir (sesudah). */
    private inner class StrokeLog(val target: String) {
        val before = LinkedHashMap<Int, ByteArray>()

        fun capture(rc: CoRect) {
            for (t in CoTiles.tilesFor(rc, imgW, imgH)) if (!before.containsKey(t)) before[t] = readTile(target, CoTiles.rectOf(t, imgW, imgH))
        }

        fun finish(label: String): CoTileStep? {
            if (before.isEmpty()) return null
            val bpp = if (target == CO_MASK) 1 else 4
            val tiles = before.map { (t, b) -> val rc = CoTiles.rectOf(t, imgW, imgH); CoTileData(rc, b, readTile(target, rc), bpp) }
            return CoTileStep(label, target, tiles)
        }

        /** Batalkan sapuan (mis. jari kedua menyentuh untuk zoom). */
        fun revert() {
            for ((t, b) in before) writeTile(target, CoTiles.rectOf(t, imgW, imgH), b)
        }
    }

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(kit.workspace)
        buildTopBar()
        root.addView(topBar, LinearLayout.LayoutParams(-1, dp(52)))
        zoom.checker = true
        zoom.padPx = dp(18).toFloat()
        zoom.drawer = { cv -> drawContent(cv, layerPaint) }
        zoom.overlayDrawer = { cv, s -> drawOverlay(cv, s) }
        zoom.gesture = gesture
        stage.addView(zoom, FrameLayout.LayoutParams(-1, -1))
        buildEmptyCard()
        stage.addView(emptyCard, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = dp(24); rightMargin = dp(24) })
        buildPolyBar()
        stage.addView(polyBar, FrameLayout.LayoutParams(-2, dp(44), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })
        busyChip.apply {
            textSize = 12f
            setTextColor(c.ink)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = c.shape(c.surface, 18, c.stroke)
            elevation = dp(4).toFloat()
            visibility = View.GONE
        }
        stage.addView(busyChip, FrameLayout.LayoutParams(-2, dp(34), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        options.orientation = LinearLayout.VERTICAL
        options.setPadding(dp(12), dp(6), dp(12), dp(2))
        options.background = c.shape(c.surface, 0)
        root.addView(options, LinearLayout.LayoutParams(-1, -2))
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER_VERTICAL
        actions.setPadding(dp(8), 0, dp(8), 0)
        actionsScroll.isHorizontalScrollBarEnabled = false
        actionsScroll.overScrollMode = View.OVER_SCROLL_NEVER
        actionsScroll.background = c.shape(c.surface, 0)
        actionsScroll.addView(actions, FrameLayout.LayoutParams(-2, -1))
        root.addView(actionsScroll, LinearLayout.LayoutParams(-1, dp(46)))
        root.addView(View(a).apply { setBackgroundColor(c.stroke) }, LinearLayout.LayoutParams(-1, 1))
        dock.orientation = LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER_VERTICAL
        dock.setPadding(dp(4), 0, dp(4), 0)
        dockScroll.isHorizontalScrollBarEnabled = false
        dockScroll.overScrollMode = View.OVER_SCROLL_NEVER
        dockScroll.background = c.shape(c.surface, 0)
        dockScroll.addView(dock, FrameLayout.LayoutParams(-2, -1))
        root.addView(dockScroll, LinearLayout.LayoutParams(-1, dp(64)))
        for (t in Tool.values()) {
            val cell = kit.navCell(t.icon, t.label) { selectTool(t) }
            navCells[t.name] = cell
            dock.addView(cell, LinearLayout.LayoutParams(dp(66), dp(54)))
        }
        val layerCell = kit.navCell("layers-outline", "Layer") { openLayers() }
        dock.addView(layerCell, LinearLayout.LayoutParams(dp(66), dp(54)))

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { shutdown() }
        })
        refreshAll()
        openLastProject()
    }

    // ================================================================== kerangka UI

    private fun buildTopBar() {
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(dp(2), 0, dp(8), 0)
        topBar.background = c.shape(c.surface, 0)
        topBar.addView(kit.icon("arrow-left", 23f, c.ink, "Kembali") { a.navigateBack() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), 0, dp(4), 0)
            isClickable = true
            contentDescription = "Proyek potongan"
            background = c.pressable(Color.TRANSPARENT, 10)
            setOnClickListener { openProjects() }
        }
        titleView.apply {
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c.ink)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
        }
        subtitleView.apply {
            textSize = 11f; setTextColor(c.muted); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false; setPadding(0, dp(3), 0, 0)
        }
        titles.addView(titleView)
        titles.addView(subtitleView)
        topBar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(kit.icon("fit-to-screen-outline", 21f, c.ink, "Pas layar") { zoom.resetView() }, LinearLayout.LayoutParams(dp(38), dp(44)))
        undoBtn = kit.icon("undo", 22f, c.ink, "Undo") { undo() }
        redoBtn = kit.icon("redo", 22f, c.ink, "Redo") { redo() }
        topBar.addView(undoBtn, LinearLayout.LayoutParams(dp(38), dp(44)))
        topBar.addView(redoBtn, LinearLayout.LayoutParams(dp(38), dp(44)))
        topBar.addView(kit.icon("dots-vertical", 22f, c.ink, "Menu potong") { showMenu() }, LinearLayout.LayoutParams(dp(36), dp(44)))
        topBar.addView(kit.primaryButton("Ekspor") { openExport() }.apply { contentDescription = "Ekspor potongan" },
            LinearLayout.LayoutParams(-2, dp(36)).apply { leftMargin = dp(4) })
    }

    private fun buildEmptyCard() {
        emptyCard.orientation = LinearLayout.VERTICAL
        emptyCard.gravity = Gravity.CENTER_HORIZONTAL
        emptyCard.setPadding(dp(18), dp(18), dp(18), dp(16))
        emptyCard.background = c.shape(c.surface, 20, c.stroke)
        emptyCard.elevation = dp(3).toFloat()
        emptyCard.addView(kit.icon("content-cut", 30f, c.ink), LinearLayout.LayoutParams(-1, dp(40)))
        emptyCard.addView(kit.text("Potong Manual", 16f, c.ink, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        emptyCard.addView(kit.text("Impor foto karakter, seleksi bagian (rambut, kepala, mata, mulut, baju, badan), lalu pisahkan ke layer transparan.", 12.5f, c.muted).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(kit.primaryButton("Impor foto") { importPhoto() }, LinearLayout.LayoutParams(-2, dp(40)))
        row.addView(kit.secondaryButton("Proyek tersimpan") { openProjects() }, LinearLayout.LayoutParams(-2, dp(40)).apply { leftMargin = dp(8) })
        emptyCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    }

    private fun buildPolyBar() {
        polyBar.orientation = LinearLayout.HORIZONTAL
        polyBar.gravity = Gravity.CENTER_VERTICAL
        polyBar.setPadding(dp(6), dp(4), dp(6), dp(4))
        polyBar.background = c.shape(c.surface, 22, c.stroke)
        polyBar.elevation = dp(4).toFloat()
        polyBar.visibility = View.GONE
    }

    private fun renderPolyBar() {
        polyBar.removeAllViews()
        val on = tool == Tool.POLY && pathXs.isNotEmpty() && !touching
        polyBar.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        polyBar.addView(kit.pill("Tutup (${pathXs.size})") { commitPath() }, LinearLayout.LayoutParams(-2, dp(34)))
        polyBar.addView(kit.pill("Hapus titik") {
            if (pathXs.isNotEmpty()) { pathXs.removeAt(pathXs.size - 1); pathYs.removeAt(pathYs.size - 1) }
            renderPolyBar(); zoom.invalidate()
        }, LinearLayout.LayoutParams(-2, dp(34)).apply { leftMargin = dp(6) })
        polyBar.addView(kit.pill("Batal") { pathXs.clear(); pathYs.clear(); renderPolyBar(); zoom.invalidate() },
            LinearLayout.LayoutParams(-2, dp(34)).apply { leftMargin = dp(6) })
    }

    private fun hasDoc(): Boolean = source != null || layers.isNotEmpty()
    private fun active(): CoLayer? = layers.firstOrNull { it.id == activeId }

    private fun refreshAll() {
        refreshChrome()
        renderOptions()
        renderActions()
        kit.styleNav(navCells, tool.name)
        renderPolyBar()
        zoom.invalidate()
    }

    private fun refreshChrome() {
        titleView.text = if (hasDoc()) projName else "Potong Manual"
        val act = active()
        subtitleView.text = if (!hasDoc()) "Belum ada foto" else "${imgW}×${imgH} · ${layers.size} layer · aktif: ${act?.name ?: "—"}"
        emptyCard.visibility = if (hasDoc() || busyCount > 0) View.GONE else View.VISIBLE
        undoBtn?.alpha = if (history.canUndo) 1f else 0.35f
        redoBtn?.alpha = if (history.canRedo) 1f else 0.35f
    }

    private fun setBusy(on: Boolean, label: String = "Memproses…") {
        busyCount = (busyCount + if (on) 1 else -1).coerceAtLeast(0)
        busyChip.text = label
        busyChip.visibility = if (busyCount > 0) View.VISIBLE else View.GONE
        refreshChrome()
    }

    /** Kerjakan [work] di thread kerja (urut), lalu [done] di thread UI bila tampilan masih hidup. */
    private fun <T> runBusy(label: String, work: () -> T, done: (T) -> Unit) {
        setBusy(true, label)
        worker.execute {
            val r = runCatching { work() }
            main.post {
                if (!alive) return@post
                setBusy(false)
                r.onSuccess(done).onFailure { e ->
                    a.toast(if (e is OutOfMemoryError) "Memori tidak cukup — kecilkan foto atau kurangi layer" else "Gagal: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun selectTool(t: Tool) {
        if (t != Tool.POLY) { pathXs.clear(); pathYs.clear() }
        pickingColor = false
        pickingCloneSrc = false
        tool = t
        refreshAll()
    }

    private fun renderOptions() {
        options.removeAllViews()
        if (!hasDoc()) { options.visibility = View.GONE; return }
        options.visibility = View.VISIBLE
        fun modeRow() = options.addView(kit.segmented(listOf("REPLACE" to "Ganti", "ADD" to "Tambah", "SUBTRACT" to "Kurangi"), selMode.name) { k ->
            selMode = CoSelMode.valueOf(k); renderOptions()
        }, LinearLayout.LayoutParams(-1, dp(36)).apply { bottomMargin = dp(4) })
        fun sizeRows(withStrength: Boolean) {
            options.addView(kit.slider("Ukuran", 2, 400, brushSize, 40, { "$it px" }) { brushSize = it; zoom.invalidate() }, LinearLayout.LayoutParams(-1, dp(36)))
            options.addView(kit.slider("Kekerasan", 0, 100, hardness, 70, { "$it%" }) { hardness = it }, LinearLayout.LayoutParams(-1, dp(36)))
            if (withStrength) options.addView(kit.slider("Kekuatan", 5, 100, strength, 100, { "$it%" }) { strength = it }, LinearLayout.LayoutParams(-1, dp(36)))
        }
        fun clipPill(row: LinearLayout) = row.addView(kit.pill(if (clipToSel) "Batas seleksi: aktif" else "Batas seleksi: mati") { clipToSel = !clipToSel; renderOptions() },
            LinearLayout.LayoutParams(-2, dp(32)).apply { rightMargin = dp(6) })
        fun pillRow(): LinearLayout = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        when (tool) {
            Tool.PAN -> options.addView(hint("Satu jari menggeser, dua jari zoom & geser, ketuk dua kali = pas layar. Alat lain: dua jari tetap zoom/geser."))
            Tool.SELECT -> { modeRow(); sizeRows(false) }
            Tool.WAND -> {
                modeRow()
                options.addView(kit.slider("Toleransi", 0, 128, wandTol, 32, { "$it" }) { wandTol = it }, LinearLayout.LayoutParams(-1, dp(36)))
                options.addView(hint("Ketuk warna di gambar. Area sewarna terhubung (4-arah) otomatis diseleksi. Naikkan toleransi untuk area lebih luas."))
            }
            Tool.LASSO -> { modeRow(); options.addView(hint("Gambar garis tertutup dengan satu jari; lepas untuk menutup otomatis.")) }
            Tool.POLY -> { modeRow(); options.addView(hint("Ketuk titik demi titik. Ketuk titik pertama atau tombol Tutup untuk menyelesaikan.")) }
            Tool.ERASE, Tool.RESTORE -> {
                val row = pillRow(); clipPill(row)
                if (tool == Tool.RESTORE && source == null) row.addView(kit.text("Foto asli tidak tersedia", 11.5f, c.muted))
                options.addView(row, LinearLayout.LayoutParams(-1, dp(38)))
                sizeRows(true)
            }
            Tool.CLONE -> {
                val row = pillRow()
                row.addView(kit.pill(if (pickingCloneSrc) "Ketuk titik sumber…" else "Pasang sumber", pickingCloneSrc) {
                    pickingCloneSrc = !pickingCloneSrc; renderOptions()
                }, LinearLayout.LayoutParams(-2, dp(32)).apply { rightMargin = dp(6) })
                row.addView(kit.pill(if (cloneFromSource) "Dari: foto asli" else "Dari: layer aktif") {
                    if (source == null && !cloneFromSource) { a.toast("Foto asli tidak tersedia"); return@pill }
                    cloneFromSource = !cloneFromSource; renderOptions()
                }, LinearLayout.LayoutParams(-2, dp(32)).apply { rightMargin = dp(6) })
                clipPill(row)
                options.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(-1, dp(38)))
                options.addView(hint(if (clone.hasSource) "Sumber terpasang. Offset sejajar dipakai sampai sumber diganti." else "Tahan lama di gambar (atau Pasang sumber) untuk memilih titik sumber."))
                sizeRows(false)
            }
            Tool.PAINT -> {
                val row = pillRow()
                row.addView(kit.swatchRow(kit.palette().take(9), paintColor) { col -> paintColor = col; renderOptions() }, LinearLayout.LayoutParams(0, dp(32), 1f))
                row.addView(kit.icon("crosshairs-gps", 20f, if (pickingColor) c.activeInk else c.ink, "Ambil warna dari gambar") {
                    pickingColor = !pickingColor; renderOptions()
                }.apply { if (pickingColor) background = c.shape(c.activeFill, 12) }, LinearLayout.LayoutParams(dp(38), dp(34)))
                row.addView(View(a).apply {
                    background = c.shape(paintColor, 10, c.stroke)
                    isClickable = true
                    contentDescription = "Warna khusus (hex)"
                    setOnClickListener { askHex() }
                }, LinearLayout.LayoutParams(dp(34), dp(28)).apply { leftMargin = dp(4) })
                options.addView(row, LinearLayout.LayoutParams(-1, dp(38)))
                sizeRows(true)
            }
        }
    }

    private fun hint(s: String): TextView = kit.text(s, 11.5f, c.muted).apply { setPadding(dp(2), dp(4), dp(2), dp(6)); setLineSpacing(0f, 1.15f) }

    private fun renderActions() {
        actions.removeAllViews()
        actionsScroll.visibility = if (hasDoc()) View.VISIBLE else View.GONE
        if (!hasDoc()) return
        fun add(label: String, on: Boolean = false, f: () -> Unit) =
            actions.addView(kit.pill(label, on) { f() }, LinearLayout.LayoutParams(-2, dp(32)).apply { rightMargin = dp(6) })
        add("Pisahkan ke layer", true) { openSeparate() }
        add("Semua") { selectAll() }
        add("Balik") { invertSelection() }
        add("Kosongkan") { clearSelection() }
        add("Feather") { openFeather() }
        add("Rapikan tepi") { openRefine() }
        add(if (showSel) "Overlay: tampil" else "Overlay: sembunyi") { showSel = !showSel; renderActions(); zoom.invalidate() }
    }

    private fun askHex() {
        val input = EditText(a).apply { setText(String.format("#%06X", 0xFFFFFF and paintColor)); setSelectAllOnFocus(true); setSingleLine(true) }
        val wrap = FrameLayout(a).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, FrameLayout.LayoutParams(-1, -2)) }
        a.themedDialog().setTitle("Warna kuas (hex)").setView(wrap)
            .setNegativeButton("Batal", null)
            .setPositiveButton("OK") { _, _ ->
                val col = runCatching { Color.parseColor(input.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }) }.getOrNull()
                if (col == null) a.toast("Format warna tidak dikenali") else { paintColor = col or 0xFF000000.toInt(); renderOptions() }
            }.show()
    }

    // ================================================================== gambar

    private fun drawContent(cv: Canvas, p: Paint) {
        val src = source
        if (ghost && src != null && !src.isRecycled) { p.alpha = 70; cv.drawBitmap(src, 0f, 0f, p) }
        for (l in layers) {
            if (!l.visible || l.bitmap.isRecycled) continue
            p.alpha = (l.opacity * 255).roundToInt().coerceIn(0, 255)
            cv.drawBitmap(l.bitmap, 0f, 0f, p)
        }
        p.alpha = 255
        val ov = overlayBmp
        if (showSel && ov != null && !ov.isRecycled) cv.drawBitmap(ov, 0f, 0f, p)
    }

    private fun brushTool(): Boolean = tool == Tool.SELECT || tool == Tool.ERASE || tool == Tool.RESTORE || tool == Tool.CLONE || tool == Tool.PAINT

    private fun drawOverlay(cv: Canvas, s: Float) {
        if (!hasDoc()) return
        val px1 = 1.5f / s
        // jalur laso / poligon
        if (pathXs.isNotEmpty()) {
            path.reset()
            path.moveTo(pathXs[0], pathYs[0])
            for (i in 1 until pathXs.size) path.lineTo(pathXs[i], pathYs[i])
            if (tool == Tool.POLY && touching) path.lineTo(ptX, ptY)
            if (tool == Tool.LASSO) path.close()
            ring.strokeWidth = px1 * 2.2f; ring.color = Color.argb(200, 0, 0, 0)
            cv.drawPath(path, ring)
            ring.strokeWidth = px1; ring.color = Color.WHITE
            cv.drawPath(path, ring)
            if (tool == Tool.POLY) {
                fill.color = Color.WHITE
                for (i in pathXs.indices) cv.drawCircle(pathXs[i], pathYs[i], (if (i == 0) 6f else 4f) / s, fill)
                ring.color = Color.BLACK
                cv.drawCircle(pathXs[0], pathYs[0], 6f / s, ring)
            }
        }
        // titik sumber klon
        if (tool == Tool.CLONE && clone.hasSource) {
            val sx = if (touching && stroke != null) ptX + clone.dx else clone.srcX
            val sy = if (touching && stroke != null) ptY + clone.dy else clone.srcY
            ring.strokeWidth = px1; ring.color = Color.WHITE
            cv.drawCircle(sx, sy, brushSize / 2f, ring)
            cv.drawLine(sx - 8f / s, sy, sx + 8f / s, sy, ring)
            cv.drawLine(sx, sy - 8f / s, sx, sy + 8f / s, ring)
        }
        if (!touching) return
        if (brushTool()) {
            ring.strokeWidth = px1 * 2.2f; ring.color = Color.argb(190, 0, 0, 0)
            cv.drawCircle(ptX, ptY, brushSize / 2f, ring)
            ring.strokeWidth = px1; ring.color = Color.WHITE
            cv.drawCircle(ptX, ptY, brushSize / 2f, ring)
        }
        if (offsetOn && tool != Tool.PAN) {
            ring.strokeWidth = px1; ring.color = Color.argb(160, 255, 255, 255)
            cv.drawLine(fingerX, fingerY, ptX, ptY, ring)
            fill.color = Color.WHITE
            cv.drawCircle(ptX, ptY, 2.5f / s, fill)
            cv.save()
            cv.scale(1f / s, 1f / s)
            cv.translate(-zoom.originX(), -zoom.originY())
            drawLoupe(cv, s)
            cv.restore()
        }
    }

    /** Lup bundar di pojok atas (pindah ke sisi lain bila jari di dekatnya): pembesaran titik kerja. */
    private fun drawLoupe(cv: Canvas, s: Float) {
        val rad = dp(58).toFloat()
        val m = dp(12).toFloat()
        val fsx = zoom.toScreenX(fingerX)
        val fsy = zoom.toScreenY(fingerY)
        val leftSide = !(fsx < rad * 2 + m * 3 && fsy < rad * 2 + m * 3)
        val cx = if (leftSide) m + rad else zoom.width - m - rad
        val cy = m + rad
        val mag = max(s * 3f, 2.5f)
        cv.save()
        path.reset()
        path.addCircle(cx, cy, rad, Path.Direction.CW)
        cv.clipPath(path)
        cv.drawColor(Color.rgb(236, 236, 240))
        cv.translate(cx, cy)
        cv.scale(mag, mag)
        cv.translate(-ptX, -ptY)
        drawContent(cv, loupePaint)
        if (brushTool()) {
            ring.strokeWidth = 1.5f / mag; ring.color = Color.WHITE
            cv.drawCircle(ptX, ptY, brushSize / 2f, ring)
        }
        if (pathXs.isNotEmpty()) {
            ring.strokeWidth = 1.5f / mag; ring.color = Color.WHITE
            val pth = Path()
            pth.moveTo(pathXs[0], pathYs[0])
            for (i in 1 until pathXs.size) pth.lineTo(pathXs[i], pathYs[i])
            pth.lineTo(ptX, ptY)
            cv.drawPath(pth, ring)
        }
        cv.restore()
        ring.strokeWidth = dp(1).toFloat(); ring.color = Color.BLACK
        cv.drawLine(cx - dp(9), cy, cx + dp(9), cy, ring)
        cv.drawLine(cx, cy - dp(9), cx, cy + dp(9), ring)
        ring.strokeWidth = dp(2).toFloat(); ring.color = c.ink
        cv.drawCircle(cx, cy, rad, ring)
    }

    // ================================================================== sentuhan

    private fun effPoint(ix: Float, iy: Float) {
        fingerX = ix; fingerY = iy
        ptX = ix
        ptY = if (offsetOn && tool != Tool.PAN) iy - dp(64) / zoom.scale() else iy
    }

    private fun onLongPress() {
        if (!touching || movedFar || tool != Tool.CLONE) return
        cloneArmed = true
        clone.setSource(ptX, ptY)
        zoom.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        a.toast("Titik sumber klon dipasang")
        renderOptions()
        zoom.invalidate()
    }

    private fun polyTap() {
        if (movedFar) return
        val tol = dp(22) / zoom.scale()
        val xs = pathXs.toFloatArray(); val ys = pathYs.toFloatArray()
        if (CoMask.closesPolygon(ptX, ptY, xs, ys, xs.size, tol)) { commitPath(); return }
        pathXs.add(ptX); pathYs.add(ptY)
    }

    /** Magic Wand: flood-fill dari titik ketuk berdasarkan toleransi warna. */
    private fun applyMagicWand() {
        if (movedFar) return
        val srcBmp = source ?: active()?.bitmap
        if (srcBmp == null) { a.toast("Impor foto dulu"); return }
        val sx = ptX.roundToInt().coerceIn(0, imgW - 1)
        val sy = ptY.roundToInt().coerceIn(0, imgH - 1)
        setBusy(true, "Magic Wand…")
        Thread {
            try {
                val n = imgW * imgH
                ensureBuf(n)
                srcBmp.getPixels(bufA, 0, imgW, 0, 0, imgW, imgH)
                val tmp = ByteArray(n)
                val box = CoMask.magicWand(bufA, imgW, imgH, sx, sy, wandTol, tmp)
                if (box.isEmpty) {
                    a.runOnUiThread { setBusy(false); a.toast("Tidak ada area sewarna") }
                    return@Thread
                }
                a.runOnUiThread {
                    val log = StrokeLog(CO_MASK)
                    if (selMode == CoSelMode.REPLACE) {
                        log.capture(CoRect(0, 0, imgW, imgH))
                        java.util.Arrays.fill(mask, 0.toByte())
                    } else {
                        log.capture(box.inset(2).clamp(imgW, imgH))
                    }
                    CoMask.combine(mask, tmp, selMode)
                    log.finish("Magic Wand")?.let { pushStep(it) }
                    updateOverlay(box.inset(2).clamp(imgW, imgH))
                    setBusy(false)
                    zoom.invalidate()
                }
            } catch (e: Exception) {
                a.runOnUiThread { setBusy(false); a.toast("Gagal: ${e.message}") }
            }
        }.start()
    }

    private fun beginStroke() {
        val target: String
        if (tool == Tool.SELECT) {
            target = CO_MASK
        } else {
            val l = active()
            if (l == null) { a.toast("Pilih atau tambah layer dulu"); touching = false; return }
            if (l.locked) { a.toast("Layer \"${l.name}\" terkunci"); touching = false; return }
            if (!l.visible) { a.toast("Layer \"${l.name}\" disembunyikan"); touching = false; return }
            if (tool == Tool.RESTORE && source == null) { a.toast("Foto asli tidak tersedia"); touching = false; return }
            if (tool == Tool.CLONE && !clone.begin(ptX, ptY)) { a.toast("Tahan lama di gambar untuk memasang titik sumber"); touching = false; return }
            if (tool == Tool.CLONE && cloneFromSource && source == null) { a.toast("Foto asli tidak tersedia"); touching = false; return }
            target = l.id
        }
        val log = StrokeLog(target)
        stroke = log
        strokeClip = if (tool != Tool.SELECT && clipToSel && !CoMask.isEmpty(mask)) mask else null
        if (tool == Tool.SELECT && selMode == CoSelMode.REPLACE) {
            log.capture(CoRect(0, 0, imgW, imgH))
            java.util.Arrays.fill(mask, 0.toByte())
            updateOverlay(CoRect(0, 0, imgW, imgH))
        }
        dab(ptX, ptY)
    }

    private fun endStroke() {
        val log = stroke ?: return
        stroke = null
        val label = when (tool) {
            Tool.SELECT -> "Seleksi kuas"
            Tool.ERASE -> "Hapus"
            Tool.RESTORE -> "Pulihkan"
            Tool.CLONE -> "Klon"
            else -> "Kuas"
        }
        log.finish(label)?.let { pushStep(it) }
    }

    private fun ensureBuf(n: Int) {
        if (bufA.size < n) bufA = IntArray(n)
        if (bufB.size < n) bufB = IntArray(n)
    }

    /** Satu cap kuas di (x,y) pada mask atau layer aktif. */
    private fun dab(x: Float, y: Float) {
        val log = stroke ?: return
        val rad = brushSize / 2f
        val rc = CoRect.around(x, y, rad, imgW, imgH)
        if (rc.isEmpty) return
        log.capture(rc)
        if (tool == Tool.SELECT) {
            CoMask.stamp(mask, imgW, imgH, x, y, rad, hardness, add = selMode != CoSelMode.SUBTRACT)
            updateOverlay(rc.inset(1).clamp(imgW, imgH))
            return
        }
        val l = active() ?: return
        val n = rc.w * rc.h
        ensureBuf(n)
        l.bitmap.getPixels(bufA, 0, rc.w, rc.l, rc.t, rc.w, rc.h)
        val st = strength / 100f
        when (tool) {
            Tool.ERASE -> CoPaint.erase(bufA, rc, x, y, rad, hardness, st, strokeClip, imgW)
            Tool.RESTORE -> {
                val src = source ?: return
                src.getPixels(bufB, 0, rc.w, rc.l, rc.t, rc.w, rc.h)
                CoPaint.restore(bufA, bufB, rc, x, y, rad, hardness, st, strokeClip, imgW)
            }
            Tool.PAINT -> CoPaint.paint(bufA, paintColor, rc, x, y, rad, hardness, st, strokeClip, imgW)
            Tool.CLONE -> {
                val from = if (cloneFromSource) source ?: return else l.bitmap
                sampleShifted(from, rc, clone.dx.roundToInt(), clone.dy.roundToInt())
                CoPaint.clone(bufA, bufB, rc, x, y, rad, hardness, 1f, strokeClip, imgW)
            }
            else -> return
        }
        l.bitmap.setPixels(bufA, 0, rc.w, rc.l, rc.t, rc.w, rc.h)
        l.dirty = true
    }

    /** Isi [bufB] (lokal rc) dengan piksel [from] digeser (dx,dy); di luar gambar = transparan. */
    private fun sampleShifted(from: Bitmap, rc: CoRect, dx: Int, dy: Int) {
        java.util.Arrays.fill(bufB, 0, rc.w * rc.h, 0)
        val sr = CoRect(rc.l + dx, rc.t + dy, rc.r + dx, rc.b + dy)
        val ir = sr.clamp(imgW, imgH)
        if (ir.isEmpty) return
        from.getPixels(bufB, (ir.t - sr.t) * rc.w + (ir.l - sr.l), rc.w, ir.l, ir.t, ir.w, ir.h)
    }

    /** Warna komposit layer tampil di titik (pipet); kosong → foto asli. */
    private fun sampleColor(x: Float, y: Float): Int {
        val ix = x.toInt().coerceIn(0, imgW - 1)
        val iy = y.toInt().coerceIn(0, imgH - 1)
        var col = 0
        for (l in layers) if (l.visible) col = CoPaint.srcOver(col, l.bitmap.getPixel(ix, iy), l.opacity)
        if ((col ushr 24) < 8) source?.let { col = it.getPixel(ix, iy) }
        return col or 0xFF000000.toInt()
    }

    // ================================================================== seleksi

    private fun updateOverlay(rc: CoRect) {
        val ov = overlayBmp ?: return
        if (rc.isEmpty) return
        // Per pita 128 baris: buffer tetap kecil walau seluruh mask diperbarui.
        var t = rc.t
        while (t < rc.b) {
            val strip = CoRect(rc.l, t, rc.r, min(rc.b, t + 128))
            if (ovBuf.size < strip.w * strip.h) ovBuf = IntArray(strip.w * strip.h)
            CoMask.overlay(mask, imgW, imgH, strip, Color.rgb(255, 64, 129), ovBuf)
            ov.setPixels(ovBuf, 0, strip.w, strip.l, strip.t, strip.w, strip.h)
            t = strip.b
        }
    }

    /** Ganti seluruh mask dengan [next] sebagai satu langkah riwayat. */
    private fun commitMask(label: String, next: ByteArray) {
        if (next.size != mask.size) return
        val log = StrokeLog(CO_MASK)
        log.capture(CoRect(0, 0, imgW, imgH))
        System.arraycopy(next, 0, mask, 0, mask.size)
        updateOverlay(CoRect(0, 0, imgW, imgH))
        log.finish(label)?.let { pushStep(it) }
        zoom.invalidate()
    }

    private fun commitPath() {
        val n = pathXs.size
        if (n < 3) { pathXs.clear(); pathYs.clear(); renderPolyBar(); zoom.invalidate(); return }
        val xs = pathXs.toFloatArray(); val ys = pathYs.toFloatArray()
        pathXs.clear(); pathYs.clear()
        renderPolyBar()
        val mode = selMode
        val cur = mask.copyOf()
        val w = imgW; val h = imgH
        runBusy("Menyeleksi…", {
            val shape = CoMask.rasterPolygon(xs, ys, n, w, h)
            CoMask.combine(cur, shape, mode)
            cur
        }) { next -> commitMask(if (tool == Tool.POLY) "Seleksi poligon" else "Seleksi laso", next) }
    }

    private fun selectAll() {
        if (!hasDoc()) return
        commitMask("Pilih semua", ByteArray(mask.size) { 255.toByte() })
    }

    private fun invertSelection() {
        if (!hasDoc()) return
        val next = mask.copyOf(); CoMask.invert(next)
        commitMask("Balik seleksi", next)
    }

    private fun clearSelection() {
        if (!hasDoc() || CoMask.isEmpty(mask, 1)) return
        commitMask("Kosongkan seleksi", ByteArray(mask.size))
    }

    private fun needSelection(): Boolean {
        if (CoMask.isEmpty(mask)) { a.toast("Belum ada seleksi — pakai Seleksi, Laso, atau Poligon"); return false }
        return true
    }

    private fun openFeather() {
        if (!hasDoc() || !needSelection()) return
        var radius = 6
        sheet.open("Feather seleksi") { panel ->
            panel.addView(hint("Melembutkan tepi seleksi (blur mask). Cocok untuk rambut dan tepi kulit."))
            panel.addView(kit.slider("Radius", 1, 80, radius, 6, { "$it px" }) { radius = it }, LinearLayout.LayoutParams(-1, dp(40)))
            panel.addView(kit.primaryButton("Terapkan") {
                sheet.close()
                val cur = mask.copyOf(); val w = imgW; val h = imgH; val r = radius
                runBusy("Feather…", { CoMask.feather(cur, w, h, r.toFloat()) }) { commitMask("Feather $r px", it) }
            }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(8) })
        }
    }

    private fun openRefine() {
        if (!hasDoc() || !needSelection()) return
        var shift = 0; var smooth = 20; var feather = 1; var edge = 0; var band = 4
        sheet.open("Rapikan tepi") { panel ->
            panel.addView(hint("Geser tepi (+ perluas / − persempit), haluskan gerigi, feather, dan opsional alfa sadar warna di sekitar tepi (memakai warna layer aktif)."))
            panel.addView(kit.slider("Geser tepi", -30, 30, shift, 0, { if (it > 0) "+$it px" else "$it px" }) { shift = it }, LinearLayout.LayoutParams(-1, dp(38)))
            panel.addView(kit.slider("Halus", 0, 100, smooth, 20) { smooth = it }, LinearLayout.LayoutParams(-1, dp(38)))
            panel.addView(kit.slider("Feather", 0, 40, feather, 1, { "$it px" }) { feather = it }, LinearLayout.LayoutParams(-1, dp(38)))
            panel.addView(kit.slider("Tepi warna", 0, 100, edge, 0, { if (it == 0) "mati" else "$it%" }) { edge = it }, LinearLayout.LayoutParams(-1, dp(38)))
            panel.addView(kit.slider("Lebar pita", 1, 16, band, 4, { "$it px" }) { band = it }, LinearLayout.LayoutParams(-1, dp(38)))
            panel.addView(kit.primaryButton("Terapkan") {
                sheet.close()
                val cur = mask.copyOf(); val w = imgW; val h = imgH
                val ref = if (edge > 0) (active()?.bitmap ?: source) else null
                val px = ref?.let { b -> IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) } }
                val sh = shift; val sm = smooth; val fe = feather; val ed = edge; val bd = band
                runBusy("Merapikan tepi…", {
                    var m = CoMask.refine(cur, w, h, sh, sm, 0f)
                    if (px != null && ed > 0) m = CoMask.edgeAware(m, px, w, h, bd, ed / 100f)
                    if (fe > 0) m = CoMask.feather(m, w, h, fe.toFloat())
                    m
                }) { commitMask("Rapikan tepi", it) }
            }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(8) })
        }
    }

    // ================================================================== layer

    private fun metaOf(l: CoLayer) = CoLayerMeta(l.id, l.name, "layer_${l.id}.png", l.visible, l.locked, l.opacity)
    private fun metas(): List<CoLayerMeta> = layers.map { metaOf(it) }

    private fun newBitmap(): Bitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)

    /** Sisipkan layer baru di atas layer aktif dan catat sebagai langkah riwayat. */
    private fun insertLayer(name: String, bmp: Bitmap, label: String, extra: CoStep? = null) {
        val idx = (layers.indexOfFirst { it.id == activeId } + 1).let { if (it <= 0) layers.size else it }
        val l = CoLayer(UUID.randomUUID().toString().substring(0, 8), CoNames.unique(name, layers.map { it.name }), bmp)
        layers.add(idx, l)
        activeId = l.id
        val step = CoLayerStep(label, true, metaOf(l), idx)
        pushStep(if (extra != null) CoGroupStep(label, listOf(extra, step)) else step)
        packLayerLater(step, bmp)
        refreshAll()
        renderLayerSheet()
    }

    /** Kompres piksel layer untuk riwayat di thread kerja (bitmap tidak diubah selama sibuk). */
    private fun packLayerLater(step: CoLayerStep, bmp: Bitmap, recycleAfter: Boolean = false) {
        val w = imgW; val h = imgH
        setBusy(true, "Menyimpan riwayat…")
        worker.execute {
            runCatching {
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                step.pixels = CoPack.packInts(px)
            }
            main.post {
                if (recycleAfter) bmp.recycle()
                if (alive) { setBusy(false); history.trim() }
            }
        }
    }

    private fun addEmptyLayer() {
        if (!hasDoc()) return
        runCatching { newBitmap() }.onSuccess { insertLayer("Layer", it, "Tambah layer") }
            .onFailure { a.toast("Memori tidak cukup untuk layer baru") }
    }

    private fun duplicateLayer(l: CoLayer) {
        val copy = runCatching { l.bitmap.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull()
        if (copy == null) { a.toast("Memori tidak cukup untuk duplikat"); return }
        insertLayer("${l.name} salinan", copy, "Duplikat layer")
    }

    private fun deleteLayer(l: CoLayer) {
        a.themedDialog().setTitle("Hapus layer?")
            .setMessage("Layer \"${l.name}\" akan dihapus. Bisa dibatalkan dengan Undo.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                val idx = layers.indexOf(l)
                if (idx < 0) return@setPositiveButton
                val step = CoLayerStep("Hapus layer", false, metaOf(l), idx)
                layers.removeAt(idx)
                if (activeId == l.id) activeId = layers.getOrNull((idx - 1).coerceAtLeast(0))?.id ?: layers.lastOrNull()?.id
                pushStep(step)
                packLayerLater(step, l.bitmap, recycleAfter = true)
                refreshAll()
                renderLayerSheet()
            }.show()
    }

    /** Ubah atribut/urutan layer lewat [change] sebagai satu langkah riwayat. */
    private fun metaChange(label: String, change: () -> Unit) {
        val before = metas()
        change()
        val after = metas()
        if (before != after) pushStep(CoOrderStep(label, before, after))
        refreshAll()
        renderLayerSheet()
    }

    private fun renameLayer(l: CoLayer) {
        val input = EditText(a).apply { setText(l.name); setSelectAllOnFocus(true); setSingleLine(true) }
        val wrap = FrameLayout(a).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, FrameLayout.LayoutParams(-1, -2)) }
        a.themedDialog().setTitle("Nama layer").setView(wrap)
            .setNegativeButton("Batal", null)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty().take(40)
                if (name.isNotEmpty()) metaChange("Ganti nama layer") { l.name = name }
            }.show()
    }

    private fun moveLayer(l: CoLayer, delta: Int) {
        val i = layers.indexOf(l)
        val j = i + delta
        if (i < 0 || j < 0 || j >= layers.size) return
        metaChange("Urutan layer") { layers.removeAt(i); layers.add(j, l) }
    }

    private fun openLayers() {
        if (!hasDoc()) { a.toast("Impor foto dulu"); return }
        sheet.open("Layer", onClosed = { layerBody = null }) { panel ->
            val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(kit.pill("+ Kosong") { addEmptyLayer() }, LinearLayout.LayoutParams(-2, dp(32)).apply { rightMargin = dp(6) })
            head.addView(kit.pill("+ Dari seleksi") { sheet.close(); openSeparate() }, LinearLayout.LayoutParams(-2, dp(32)).apply { rightMargin = dp(6) })
            panel.addView(head, LinearLayout.LayoutParams(-1, dp(40)))
            val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            layerBody = body
            panel.addView(body, LinearLayout.LayoutParams(-1, -2))
            renderLayerSheet()
        }
    }

    private fun renderLayerSheet() {
        val body = layerBody ?: return
        body.removeAllViews()
        if (layers.isEmpty()) { body.addView(hint("Belum ada layer. Tambah layer kosong atau pisahkan dari seleksi.")); return }
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        for (l in layers.asReversed()) {
            val on = l.id == activeId
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (on) c.pressable(c.surface2, 12, c.stroke) else c.pressable(Color.TRANSPARENT, 12)
                isClickable = true
                contentDescription = "Layer ${l.name}"
                setOnClickListener { activeId = l.id; refreshChrome(); renderLayerSheet() }
            }
            row.addView(kit.icon(if (l.visible) "eye-outline" else "eye-off-outline", 19f, c.ink, if (l.visible) "Sembunyikan" else "Tampilkan") {
                metaChange(if (l.visible) "Sembunyikan layer" else "Tampilkan layer") { l.visible = !l.visible }
            }, LinearLayout.LayoutParams(dp(40), dp(44)))
            row.addView(kit.icon(if (l.locked) "lock-outline" else "lock-open-outline", 18f, if (l.locked) c.ink else c.muted, if (l.locked) "Buka kunci" else "Kunci") {
                metaChange(if (l.locked) "Buka kunci layer" else "Kunci layer") { l.locked = !l.locked }
            }, LinearLayout.LayoutParams(dp(38), dp(44)))
            row.addView(kit.text(l.name + if (l.opacity < 0.999f) "  · ${(l.opacity * 100).roundToInt()}%" else "", 13f, c.ink, on).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(kit.icon("chevron-up", 20f, c.ink, "Naikkan") { moveLayer(l, 1) }, LinearLayout.LayoutParams(dp(38), dp(44)))
            row.addView(kit.icon("chevron-down", 20f, c.ink, "Turunkan") { moveLayer(l, -1) }, LinearLayout.LayoutParams(dp(38), dp(44)))
            list.addView(row, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(2) })
        }
        val scroll = ScrollView(a).apply { addView(list) }
        body.addView(scroll, LinearLayout.LayoutParams(-1, min(layers.size * dp(48), dp(240))))
        val act = active() ?: return
        var before: List<CoLayerMeta>? = null
        body.addView(kit.slider("Opasitas", 0, 100, (act.opacity * 100).roundToInt(), 100, { "$it%" },
            onStart = { before = metas() },
            onStop = { _ ->
                val b = before ?: metas()
                val after = metas()
                if (b != after) pushStep(CoOrderStep("Opasitas layer", b, after))
                refreshChrome()
            }) { v -> act.opacity = v / 100f; zoom.invalidate() },
            LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
        val acts = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        acts.addView(kit.pill("Duplikat") { duplicateLayer(act) }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(6) })
        acts.addView(kit.pill("Ganti nama") { renameLayer(act) }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(6) })
        acts.addView(kit.pill("Hapus") { deleteLayer(act) }, LinearLayout.LayoutParams(0, dp(34), 1f))
        body.addView(acts, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(4) })
    }

    // ================================================================== pisahkan ke layer

    private fun openSeparate() {
        if (!hasDoc() || !needSelection()) return
        var cut = false
        var fromSource = false
        val nameInput = EditText(a).apply {
            setSingleLine(true)
            hint = "Nama layer (mis. Rambut)"
            setText(CoNames.PRESETS.firstOrNull { p -> layers.none { it.name == p } } ?: "Bagian")
            setTextColor(c.ink)
            setHintTextColor(c.muted)
            textSize = 14f
        }
        sheet.open("Pisahkan ke layer") { panel ->
            val presets = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (p in CoNames.PRESETS) presets.addView(kit.pill(p) { nameInput.setText(p); nameInput.setSelection(p.length) },
                LinearLayout.LayoutParams(-2, dp(32)).apply { rightMargin = dp(6) })
            panel.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(presets) }, LinearLayout.LayoutParams(-1, dp(38)))
            panel.addView(nameInput, LinearLayout.LayoutParams(-1, dp(46)))
            val modeHost = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            fun renderModes() {
                modeHost.removeAllViews()
                modeHost.addView(kit.segmented(listOf("copy" to "Salin", "cut" to "Potong (hapus dari layer aktif)"), if (cut) "cut" else "copy") { k -> cut = k == "cut"; renderModes() },
                    LinearLayout.LayoutParams(-1, dp(36)).apply { bottomMargin = dp(6) })
                if (source != null) modeHost.addView(kit.segmented(listOf("layer" to "Dari layer aktif", "src" to "Dari foto asli"), if (fromSource) "src" else "layer") { k -> fromSource = k == "src"; renderModes() },
                    LinearLayout.LayoutParams(-1, dp(36)))
            }
            renderModes()
            panel.addView(modeHost, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            panel.addView(kit.primaryButton("Buat layer") {
                val name = nameInput.text?.toString()?.trim().orEmpty().ifBlank { "Bagian" }
                sheet.close()
                separate(name, cut, fromSource)
            }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(10) })
        }
    }

    private fun separate(name: String, cut: Boolean, fromSource: Boolean) {
        val act = active()
        val from = if (fromSource) source else act?.bitmap
        if (from == null) { a.toast("Tidak ada layer aktif"); return }
        if (cut && (act == null || act.locked)) { a.toast(if (act == null) "Tidak ada layer aktif untuk dipotong" else "Layer aktif terkunci — tidak bisa dipotong"); return }
        val w = imgW; val h = imgH
        val m = mask.copyOf()
        val actBmp = act?.bitmap
        runBusy("Memisahkan…", {
            val px = IntArray(w * h)
            from.getPixels(px, 0, w, 0, 0, w, h)
            val out = CoMask.extract(px, m)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(out, 0, w, 0, 0, w, h)
            var rest: IntArray? = null
            if (cut && actBmp != null) {
                val ap = if (actBmp === from) px else IntArray(w * h).also { actBmp.getPixels(it, 0, w, 0, 0, w, h) }
                CoMask.cut(ap, m)
                rest = ap
            }
            Triple(bmp, rest, CoMask.maskBounds(m, w, h, 1))
        }) { (bmp, rest, bounds) ->
            var cutStep: CoStep? = null
            if (rest != null && act != null && layers.contains(act) && !bounds.isEmpty) {
                val log = StrokeLog(act.id)
                log.capture(bounds)
                act.bitmap.setPixels(rest, bounds.t * w + bounds.l, w, bounds.l, bounds.t, bounds.w, bounds.h)
                act.dirty = true
                cutStep = log.finish("Potong dari ${act.name}")
            }
            insertLayer(name, bmp, "Pisahkan: $name", cutStep)
            a.toast("Layer \"${active()?.name ?: name}\" dibuat")
        }
    }

    // ================================================================== riwayat

    private fun pushStep(s: CoStep) {
        history.push(s)
        // Ubin mentah dikompres di thread kerja (urut sebelum undo berikutnya yang juga lewat thread kerja).
        val tiles = ArrayList<CoTileData>()
        fun collect(st: CoStep) { when (st) { is CoTileStep -> tiles.addAll(st.tiles); is CoGroupStep -> st.parts.forEach { collect(it) } } }
        collect(s)
        if (tiles.isNotEmpty()) worker.execute {
            tiles.forEach { runCatching { it.pack() } }
            main.post { if (alive) history.trim() }
        }
        refreshChrome()
    }

    private fun undo() {
        if (busyCount > 0 || touching) return
        val s = history.undo() ?: return
        applyStep(s, true)
    }

    private fun redo() {
        if (busyCount > 0 || touching) return
        val s = history.redo() ?: return
        applyStep(s, false)
    }

    private fun applyStep(s: CoStep, undo: Boolean) {
        runBusy(if (undo) "Undo…" else "Redo…", { prepare(s, undo) }) { fn ->
            fn()
            refreshAll()
            renderLayerSheet()
            a.toast((if (undo) "Undo: " else "Redo: ") + s.label)
        }
    }

    /** Dekompres di thread kerja; kembalikan aksi penerapan untuk thread UI. */
    private fun prepare(s: CoStep, undo: Boolean): () -> Unit = when (s) {
        is CoTileStep -> {
            val data = s.tiles.map { it.rect to it.raw(undo) }
            val f: () -> Unit = { data.forEach { (rc, b) -> writeTile(s.target, rc, b) } }
            f
        }
        is CoLayerStep -> {
            val create = s.created != undo
            if (create) {
                val z = s.pixels
                val px = if (z != null) CoPack.unpackInts(z, imgW * imgH) else IntArray(imgW * imgH)
                val f: () -> Unit = {
                    val bmp = newBitmap()
                    bmp.setPixels(px, 0, imgW, 0, 0, imgW, imgH)
                    val m = s.meta
                    val l = CoLayer(m.id, m.name, bmp, m.visible, m.locked, m.opacity)
                    layers.add(s.index.coerceIn(0, layers.size), l)
                    activeId = l.id
                }
                f
            } else {
                val f: () -> Unit = {
                    val l = layers.firstOrNull { it.id == s.meta.id }
                    if (l != null) {
                        layers.remove(l)
                        if (activeId == l.id) activeId = layers.lastOrNull()?.id
                        zoom.invalidate()
                        l.bitmap.recycle()
                    }
                }
                f
            }
        }
        is CoOrderStep -> {
            val target = if (undo) s.before else s.after
            val f: () -> Unit = {
                val byId = layers.associateBy { it.id }
                val ordered = target.mapNotNull { m -> byId[m.id]?.also { l -> l.name = m.name; l.visible = m.visible; l.locked = m.locked; l.opacity = m.opacity } }
                val rest = layers.filter { l -> target.none { it.id == l.id } }
                layers.clear(); layers.addAll(ordered); layers.addAll(rest)
            }
            f
        }
        is CoGroupStep -> {
            val fns = (if (undo) s.parts.asReversed() else s.parts).map { prepare(it, undo) }
            val f: () -> Unit = { fns.forEach { it() } }
            f
        }
        else -> { {} }
    }

    private fun readTile(target: String, rc: CoRect): ByteArray {
        if (target == CO_MASK) {
            val out = ByteArray(rc.w * rc.h)
            for (y in 0 until rc.h) System.arraycopy(mask, (rc.t + y) * imgW + rc.l, out, y * rc.w, rc.w)
            return out
        }
        val l = layers.firstOrNull { it.id == target } ?: return ByteArray(rc.w * rc.h * 4)
        val px = IntArray(rc.w * rc.h)
        l.bitmap.getPixels(px, 0, rc.w, rc.l, rc.t, rc.w, rc.h)
        return CoPack.intsToBytes(px)
    }

    private fun writeTile(target: String, rc: CoRect, data: ByteArray) {
        if (target == CO_MASK) {
            for (y in 0 until rc.h) System.arraycopy(data, y * rc.w, mask, (rc.t + y) * imgW + rc.l, rc.w)
            updateOverlay(rc.inset(1).clamp(imgW, imgH))
            return
        }
        val l = layers.firstOrNull { it.id == target } ?: return
        val px = CoPack.bytesToInts(data, rc.w * rc.h)
        l.bitmap.setPixels(px, 0, rc.w, rc.l, rc.t, rc.w, rc.h)
        l.dirty = true
    }

    // ================================================================== dokumen: impor, buka, simpan

    private fun projectsRoot(): File = File(a.filesDir, "cutout/projects").apply { mkdirs() }

    private fun importPhoto() {
        a.pickImageForTool(CO_PICK_IMAGE) { uri ->
            if (uri == null || !alive) return@pickImageForTool
            val name = runCatching { a.displayNameFor(uri) }.getOrDefault("foto").take(40)
            saveNow(quiet = true)
            runBusy("Memuat foto…", {
                val decoded = a.decodeOrientedBitmap(uri, CO_MAX_SIDE)
                val fitted = fitLongSide(decoded, CO_MAX_SIDE)
                if (fitted !== decoded) decoded.recycle()
                val src = ensureMutableArgb(fitted)
                val dir = File(projectsRoot(), "p_" + System.currentTimeMillis().toString(36) + "_" + UUID.randomUUID().toString().substring(0, 4))
                dir.mkdirs()
                val alpha = src.hasAlpha()
                val srcName = if (alpha) "source.png" else "source.jpg"
                writeImage(src, File(dir, srcName), alpha)
                val first = src.copy(Bitmap.Config.ARGB_8888, true) ?: error("Memori tidak cukup")
                LoadedDoc(dir, name.ifBlank { "Potongan" }, srcName, src, listOf(CoLayer(UUID.randomUUID().toString().substring(0, 8), "Foto", first)), null)
            }) { doc ->
                setDoc(doc)
                saveNow(quiet = true)
            }
        }
    }

    private class LoadedDoc(val dir: File, val name: String, val sourceFile: String, val source: Bitmap?, val layers: List<CoLayer>, val active: String?)

    private fun setDoc(d: LoadedDoc) {
        if (!alive) { d.source?.recycle(); d.layers.forEach { it.bitmap.recycle() }; return }
        val oldSource = source
        val oldLayers = ArrayList(layers)
        val oldOverlay = overlayBmp
        projDir = d.dir
        projName = d.name
        sourceFile = d.sourceFile
        source = d.source
        layers.clear(); layers.addAll(d.layers)
        activeId = d.active?.takeIf { id -> layers.any { it.id == id } } ?: layers.lastOrNull()?.id
        val ref = d.source ?: d.layers.firstOrNull()?.bitmap
        imgW = ref?.width ?: 0
        imgH = ref?.height ?: 0
        mask = ByteArray(imgW * imgH)
        overlayBmp = if (imgW > 0) runCatching { Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888) }.getOrNull() else null
        history.clear()
        clone.clear()
        pathXs.clear(); pathYs.clear()
        sheet.close(animate = false)
        zoom.setContentSize(imgW, imgH, true)
        refreshAll()
        oldSource?.let { if (it !== source) it.recycle() }
        oldLayers.forEach { if (it.bitmap !== source) it.bitmap.recycle() }
        oldOverlay?.recycle()
        runCatching { a.prefs.edit().putString(CO_PREF_LAST, d.dir.name).apply() }
    }

    private fun writeImage(b: Bitmap, f: File, png: Boolean) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            if (!b.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 95, out)) error("Gagal menulis ${f.name}")
        }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }

    private fun decodeFull(f: File, w: Int, h: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true }
        val b = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
        if (b.width == w && b.height == h) return ensureMutableArgb(b)
        val s = Bitmap.createScaledBitmap(b, w, h, true)
        if (s !== b) b.recycle()
        return ensureMutableArgb(s)
    }

    private fun openLastProject() {
        val name = runCatching { a.prefs.getString(CO_PREF_LAST, null) }.getOrNull() ?: return
        val dir = File(projectsRoot(), name)
        if (File(dir, "manifest.json").exists()) openProject(dir, quietFail = true)
    }

    private fun openProject(dir: File, quietFail: Boolean = false) {
        if (hasDoc() && projDir != dir) saveNow(quiet = true)
        setBusy(true, "Membuka proyek…")
        worker.execute {
            val r = runCatching {
                val m = CoManifestCodec.decode(jsonToTree(JSONObject(File(dir, "manifest.json").readText()))) ?: error("Manifest rusak")
                val w = m.w.coerceIn(1, CO_MAX_SIDE * 2); val h = m.h.coerceIn(1, CO_MAX_SIDE * 2)
                val src = if (m.source.isNotEmpty()) File(dir, m.source).takeIf { it.exists() }?.let { decodeFull(it, w, h) } else null
                val ls = ArrayList<CoLayer>()
                try {
                    for (meta in m.layers) {
                        val f = File(dir, meta.file)
                        val bmp = (if (f.exists()) decodeFull(f, w, h) else null) ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        ls.add(CoLayer(meta.id, meta.name, bmp, meta.visible, meta.locked, meta.opacity).also { it.dirty = !f.exists() })
                    }
                } catch (e: Throwable) {
                    src?.recycle(); ls.forEach { it.bitmap.recycle() }
                    throw e
                }
                LoadedDoc(dir, m.name, m.source, src, ls, m.active)
            }
            main.post {
                if (!alive) { r.getOrNull()?.let { d -> d.source?.recycle(); d.layers.forEach { it.bitmap.recycle() } }; return@post }
                setBusy(false)
                r.onSuccess { setDoc(it) }.onFailure { e ->
                    if (!quietFail) a.toast(if (e is OutOfMemoryError) "Memori tidak cukup untuk membuka proyek" else "Proyek tidak bisa dibuka: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private class SaveJob(val dir: File, val manifest: CoManifest, val layers: List<CoLayer>, val source: Bitmap?, val sourceFile: String)

    private fun saveJob(): SaveJob? {
        val dir = projDir ?: return null
        if (!hasDoc()) return null
        val m = CoManifest(CoManifestCodec.VERSION, projName, sourceFile, imgW, imgH, metas(), activeId, System.currentTimeMillis())
        return SaveJob(dir, m, ArrayList(layers), source, sourceFile)
    }

    /** Tulis proyek (thread kerja): hanya layer yang berubah, PNG lama yang tak dipakai dihapus, thumbnail kecil. */
    private fun writeProject(j: SaveJob) {
        j.dir.mkdirs()
        if (j.source != null && j.sourceFile.isNotEmpty() && !File(j.dir, j.sourceFile).exists()) writeImage(j.source, File(j.dir, j.sourceFile), j.sourceFile.endsWith(".png"))
        val keep = HashSet<String>()
        for ((i, l) in j.layers.withIndex()) {
            val meta = j.manifest.layers.getOrNull(i) ?: continue
            keep.add(meta.file)
            val f = File(j.dir, meta.file)
            if ((l.dirty || !f.exists()) && !l.bitmap.isRecycled) { l.dirty = false; writeImage(l.bitmap, f, true) }
        }
        j.dir.listFiles()?.forEach { f -> if (f.name.startsWith("layer_") && f.name.endsWith(".png") && f.name !in keep) f.delete() }
        runCatching {
            val side = 192
            val s = side.toFloat() / max(j.manifest.w, j.manifest.h)
            val tw = (j.manifest.w * s).toInt().coerceAtLeast(1); val th = (j.manifest.h * s).toInt().coerceAtLeast(1)
            val thumb = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val cv = Canvas(thumb)
            cv.drawColor(Color.rgb(236, 236, 240))
            cv.scale(s, s)
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            for ((i, l) in j.layers.withIndex()) {
                val meta = j.manifest.layers.getOrNull(i) ?: continue
                if (!meta.visible || l.bitmap.isRecycled) continue
                p.alpha = (meta.opacity * 255).roundToInt()
                cv.drawBitmap(l.bitmap, 0f, 0f, p)
            }
            writeImage(thumb, File(j.dir, "thumb.jpg"), false)
            thumb.recycle()
        }
        CsStore.writeAtomic(File(j.dir, "manifest.json"), treeToJson(CoManifestCodec.encode(j.manifest)).toString())
    }

    private fun saveNow(quiet: Boolean) {
        val j = saveJob() ?: run { if (!quiet) a.toast("Belum ada proyek"); return }
        if (quiet) {
            worker.execute { runCatching { writeProject(j) } }
            return
        }
        runBusy("Menyimpan…", { writeProject(j) }) { a.toast("Proyek disimpan: ${j.manifest.name}") }
    }

    private fun shutdown() {
        if (!alive) return
        alive = false
        main.removeCallbacks(longPress)
        sheet.detach()
        val j = saveJob()
        val ls = ArrayList(layers)
        val src = source
        val ov = overlayBmp
        layers.clear(); source = null; overlayBmp = null
        zoom.drawer = null
        zoom.overlayDrawer = null
        // Simpan otomatis saat keluar, lalu lepaskan bitmap setelah ditulis (memori untuk tab lain).
        worker.execute {
            if (j != null) runCatching { writeProject(j) }
            ls.forEach { it.bitmap.recycle() }
            src?.recycle()
            ov?.recycle()
        }
        worker.shutdown()
        history.clear()
    }

    private fun renameProject() {
        if (!hasDoc()) return
        val input = EditText(a).apply { setText(projName); setSelectAllOnFocus(true); setSingleLine(true) }
        val wrap = FrameLayout(a).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, FrameLayout.LayoutParams(-1, -2)) }
        a.themedDialog().setTitle("Nama proyek").setView(wrap)
            .setNegativeButton("Batal", null)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text?.toString()?.trim().orEmpty().take(40)
                if (n.isNotEmpty()) { projName = n; refreshChrome(); saveNow(quiet = true) }
            }.show()
    }

    private fun openProjects() {
        val dirs = projectsRoot().listFiles { f -> f.isDirectory && File(f, "manifest.json").exists() }
            ?.sortedByDescending { File(it, "manifest.json").lastModified() } ?: emptyList()
        sheet.open("Proyek potongan") { panel ->
            if (dirs.isEmpty()) { panel.addView(hint("Belum ada proyek tersimpan. Impor foto untuk memulai.")) }
            val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            val fmt = SimpleDateFormat("d MMM yyyy HH:mm", Locale("id"))
            for (d in dirs) {
                val m = runCatching { CoManifestCodec.decode(jsonToTree(JSONObject(File(d, "manifest.json").readText()))) }.getOrNull()
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = c.pressable(if (d == projDir) c.surface2 else Color.TRANSPARENT, 12)
                    isClickable = true
                    contentDescription = "Buka ${m?.name ?: d.name}"
                    setOnClickListener { sheet.close(); if (d != projDir) openProject(d) }
                }
                val thumb = File(d, "thumb.jpg")
                row.addView(ImageView(a).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = c.shape(c.surface2, 8)
                    if (thumb.exists()) runCatching { setImageBitmap(BitmapFactory.decodeFile(thumb.absolutePath)) }
                }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) })
                val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(kit.text(m?.name ?: d.name, 13.5f, c.ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                texts.addView(kit.text("${m?.layers?.size ?: 0} layer · ${m?.w ?: 0}×${m?.h ?: 0} · ${fmt.format(Date(File(d, "manifest.json").lastModified()))}", 11f, c.muted).apply {
                    setPadding(0, dp(3), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(kit.icon("delete-outline", 20f, c.ink, "Hapus proyek") { confirmDeleteProject(d, m?.name ?: d.name) }, LinearLayout.LayoutParams(dp(40), dp(44)))
                list.addView(row, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(2) })
            }
            panel.addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(-1, min(max(dirs.size, 1) * dp(58), dp(300))))
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(kit.primaryButton("Impor foto baru") { sheet.close(); importPhoto() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
            if (hasDoc()) row.addView(kit.secondaryButton("Simpan sekarang") { sheet.close(); saveNow(quiet = false) }, LinearLayout.LayoutParams(0, dp(40), 1f))
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    private fun confirmDeleteProject(dir: File, name: String) {
        a.themedDialog().setTitle("Hapus proyek?")
            .setMessage("\"$name\" beserta semua layernya akan dihapus permanen.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                sheet.close(animate = false)
                if (dir == projDir) {
                    val oldLayers = ArrayList(layers); val oldSrc = source; val oldOv = overlayBmp
                    layers.clear(); source = null; overlayBmp = null; projDir = null; activeId = null
                    imgW = 0; imgH = 0; mask = ByteArray(0); history.clear()
                    zoom.setContentSize(0, 0, true)
                    oldLayers.forEach { it.bitmap.recycle() }; oldSrc?.recycle(); oldOv?.recycle()
                    runCatching { a.prefs.edit().remove(CO_PREF_LAST).apply() }
                    refreshAll()
                }
                worker.execute { runCatching { dir.deleteRecursively() } }
                a.toast("Proyek dihapus")
            }.show()
    }

    // ================================================================== ekspor & kirim

    private fun showMenu() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("Impor foto karakter" to { importPhoto() })
        items.add("Proyek tersimpan…" to { openProjects() })
        if (hasDoc()) {
            items.add("Simpan proyek" to { saveNow(quiet = false) })
            items.add("Ganti nama proyek" to { renameProject() })
        }
        items.add((if (offsetOn) "Kursor offset & lup: aktif" else "Kursor offset & lup: mati") to { offsetOn = !offsetOn; zoom.invalidate() })
        items.add((if (ghost) "Bayangan foto asli: tampil" else "Bayangan foto asli: sembunyi") to { ghost = !ghost; zoom.invalidate() })
        kit.menu("Potong Manual", items)
    }

    private fun openExport() {
        if (!hasDoc() || layers.isEmpty()) { a.toast("Belum ada layer untuk diekspor"); return }
        sheet.open("Ekspor potongan") { panel ->
            panel.addView(hint("Layer diekspor seukuran foto (transparan) agar posisinya tetap pas saat disusun ulang."))
            panel.addView(kit.primaryButton("Kirim ke Desain (layer gambar)") { sheet.close(); sendToDesign() }, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(6) })
            panel.addView(kit.secondaryButton("Simpan tiap layer (PNG) ke Galeri") { sheet.close(); exportLayers() }, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(6) })
            panel.addView(kit.secondaryButton("Simpan gabungan (PNG) ke Galeri") { sheet.close(); exportMerged() }, LinearLayout.LayoutParams(-1, dp(40)))
        }
    }

    private fun exportLayers() {
        val ls = layers.filter { it.visible }.ifEmpty { layers.toList() }
        val base = projName
        runBusy("Mengekspor ${ls.size} layer…", {
            var n = 0
            for (l in ls) { if (!l.bitmap.isRecycled) { a.saveImageToGallery(l.bitmap, "${base}_${l.name}", "png", 100); n++ } }
            n
        }) { n -> a.toast("$n layer disimpan ke Pictures/GITLS") }
    }

    private fun composite(): Bitmap {
        val out = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (l in layers) {
            if (!l.visible || l.bitmap.isRecycled) continue
            p.alpha = (l.opacity * 255).roundToInt()
            cv.drawBitmap(l.bitmap, 0f, 0f, p)
        }
        return out
    }

    private fun exportMerged() {
        val base = projName
        runBusy("Menggabungkan…", {
            val bmp = composite()
            try { a.saveImageToGallery(bmp, "${base}_gabungan", "png", 100) } finally { bmp.recycle() }
        }) { saved -> a.toast("Disimpan: ${saved.label}") }
    }

    private class SentLayer(val file: File, val rect: CoRect, val name: String, val opacity: Float)

    /** Tiap layer tampil dipangkas ke area beralfa → PNG di folder gambar Desain → elemen IMAGE di CsProject. */
    private fun sendToDesign() {
        val ls = layers.filter { it.visible }
        if (ls.isEmpty()) { a.toast("Tidak ada layer yang tampil"); return }
        val w = imgW; val h = imgH
        val ctx = a.applicationContext
        val name = projName
        runBusy("Menyiapkan layer untuk Desain…", {
            val out = ArrayList<SentLayer>()
            val px = IntArray(w * h)
            for (l in ls) {
                if (l.bitmap.isRecycled) continue
                l.bitmap.getPixels(px, 0, w, 0, 0, w, h)
                val rc = CoMask.alphaBounds(px, w, h, 0)
                if (rc.isEmpty) continue
                val crop = Bitmap.createBitmap(l.bitmap, rc.l, rc.t, rc.w, rc.h)
                val f = File(CsStore.imgDir(ctx), "${UUID.randomUUID()}.png")
                try { writeImage(crop, f, true) } finally { if (crop !== l.bitmap) crop.recycle() }
                out.add(SentLayer(f, rc, l.name, l.opacity))
            }
            out
        }) { sent ->
            if (sent.isEmpty()) { a.toast("Semua layer kosong (transparan)"); return@runBusy }
            addToDesign(sent, w, h, name)
            saveNow(quiet = true)
            a.toast("${sent.size} layer dikirim ke Desain")
            onSendToDesign()
        }
    }

    private fun addToDesign(sent: List<SentLayer>, w: Int, h: Int, name: String) {
        val p = CsProject
        if (!p.initialized) {
            p.initialized = true
            runCatching {
                val f = CsStore.autosaveFile(a)
                if (f.exists()) CsStore.load(a, f.readText(), p)
            }
        }
        p.undo.add(CsSnapshot(p.canvasW, p.canvasH, p.bg, p.elements.map { it.copy() }, p.selection.toList()))
        while (p.undo.size > CS_UNDO_LIMIT) p.undo.removeAt(0)
        p.redo.clear()
        if (p.elements.isEmpty()) {
            // Desain kosong: kanvas seukuran foto, latar transparan.
            p.canvasW = w.coerceIn(16, CsMath.MAX_CANVAS)
            p.canvasH = h.coerceIn(16, CsMath.MAX_CANVAS)
            p.bg = CsBackground("transparent", Color.WHITE)
            p.projectName = name.take(40)
            p.projectFile = null
            p.userScale = 1f; p.panX = 0f; p.panY = 0f
        }
        val scale = min(p.canvasW * 0.9f / w, p.canvasH * 0.9f / h)
        val ox = (p.canvasW - w * scale) / 2f
        val oy = (p.canvasH - h * scale) / 2f
        p.selection.clear()
        for (s in sent) {
            val el = CsElement(type = CsType.IMAGE, x = ox + s.rect.l * scale, y = oy + s.rect.t * scale, w = s.rect.w * scale, h = s.rect.h * scale,
                opacity = s.opacity, imagePath = s.file.absolutePath, srcW = s.rect.w, srcH = s.rect.h, name = s.name.take(28))
            p.elements.add(el)
            p.selection.add(el.id)
        }
        val json = runCatching { CsStore.toJsonText(p) }.getOrNull()
        val file = CsStore.autosaveFile(a)
        if (json != null) Thread { runCatching { CsStore.writeAtomic(file, json) } }.start()
    }
}
