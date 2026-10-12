package com.example.aidetest

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Editing Studio — launcher profesional tanpa scroll:
 *  - Kartu "Lanjutkan" (proyek Video Editor tersimpan / proyek Studio Kreatif)
 *  - Editor utama: Studio Kreatif (foto + desain + hapus latar) selebar layar, lalu Video & Audio
 *  - Tab kategori (Gambar · Teks & Kode · Konversi) berisi grid tool pendukung
 */
private object EditingStudioState {
    var tab = "gambar"
}

internal fun MainActivity.editingStudioTool() {
    clearPage("Editing Studio")
    content.setPadding(dp(12), dp(6), dp(12), dp(10))
    content.addView(EditingStudioHub(this).root, LinearLayout.LayoutParams(-1, 0, 1f))
}

internal class EditingStudioHub(private val a: MainActivity) {
    private val c = EditorChrome(a)
    val root = LinearLayout(a)
    private val tabsRow = LinearLayout(a)
    private val gridBox = LinearLayout(a)

    private val featured = listOf(
        Triple(CsToolIds.ID, CsToolIds.NAME, "Foto · desain · hapus latar AI · layer"),
        Triple("videoeditor", "Video Editor", "Potret · multi-track · ekspor MP4"),
        Triple("audioeditor", "Audio Editor", "Waveform · fade · AAC"),
        Triple(ANIM_STUDIO_ID, "Animation Studio", "Keyframe · preset · MP4/GIF"),
        Triple(PRESENT_STUDIO_ID, "Presentation Studio", "Slide · transisi · PDF/MP4")
    )
    private val tabs = linkedMapOf(
        "gambar" to ("Gambar" to listOf("imagestudio", "imagecompress", "imageconvert", "autotrace", "color", "ocr")),
        "kode" to ("Teks & Kode" to listOf("editor", "webeditor")),
        "konversi" to ("Konversi" to listOf("batchconvert", "videotoaudio", "fileconvert"))
    )

    private fun dp(v: Int) = a.dp(v)

    init {
        root.orientation = LinearLayout.VERTICAL
        resumeCard()?.let { root.addView(it, LinearLayout.LayoutParams(-1, dp(68)).apply { bottomMargin = dp(10) }) }
        root.addView(caption("EDITOR UTAMA"))
        root.addView(featuredGrid(), LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(10) })
        tabsRow.orientation = LinearLayout.HORIZONTAL
        tabsRow.background = c.shape(c.surface2, 14)
        tabsRow.setPadding(dp(3), dp(3), dp(3), dp(3))
        root.addView(tabsRow, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(8) })
        gridBox.orientation = LinearLayout.VERTICAL
        root.addView(gridBox, LinearLayout.LayoutParams(-1, -2))
        renderTabs()
    }

    private fun text(value: String, size: Float, color: Int = c.ink, bold: Boolean = false) = TextView(a).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun caption(value: String) = text(value, 11f, c.muted, true).apply {
        letterSpacing = 0.08f
        setPadding(dp(4), 0, 0, dp(6))
    }

    private fun iconBox(name: String, size: Int, iconSize: Float, solid: Boolean): View = FrameLayout(a).apply {
        background = c.shape(if (solid) c.activeFill else c.surface2, (size / 3.2f).toInt().coerceAtLeast(10))
        addView(MdiIconView(a).apply {
            setIconName(name)
            setIconSize(iconSize)
            setTextColor(if (solid) c.activeInk else c.ink)
        }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun open(id: String, v: View) {
        if (!a.homeToolMap.containsKey(id)) { a.toast("Tool belum tersedia"); return }
        a.openToolWithPress(id, v)
    }

    /** Proyek Studio Kreatif aktif (sesi ini) atau simpanan otomatis terakhir: (nama, ukuran, jumlah layer). */
    private fun studioProject(): Triple<String, String, Int>? {
        if (CsProject.initialized) {
            return if (CsProject.elements.isEmpty()) null
            else Triple(CsProject.projectName, "${CsProject.canvasW}×${CsProject.canvasH}", CsProject.elements.size)
        }
        return runCatching {
            val f = CsStore.autosaveFile(a)
            if (!f.exists() || f.length() > 4_000_000L) return@runCatching null
            val o = org.json.JSONObject(f.readText())
            val n = o.optJSONArray("elements")?.length() ?: 0
            if (n == 0) null else Triple(o.optString("name", "Desain"), "${o.optInt("w")}×${o.optInt("h")}", n)
        }.getOrNull()
    }

    /** Kartu lanjutkan proyek: Video Editor (tersimpan permanen) atau Studio Kreatif. */
    private fun resumeCard(): View? {
        val savedVideo = runCatching {
            val json = a.prefs.getString("ve_project_v2", null) ?: return@runCatching null
            val o = org.json.JSONObject(json)
            val project = org.json.JSONObject(o.optString("project", "{}"))
            val clips = project.optJSONArray("clips")
            var total = 0L
            if (clips != null) for (i in 0 until clips.length()) {
                val cl = clips.getJSONObject(i)
                val end = cl.optLong("te", 0L).takeIf { it > 0L } ?: cl.optLong("src", 0L)
                total += (end - cl.optLong("ts", 0L)).coerceAtLeast(100L)
            }
            val count = clips?.length() ?: 0
            if (count == 0) null else Triple(o.optString("name", "Proyek 1"), count, total)
        }.getOrNull()
        val studio = if (savedVideo == null) studioProject() else null
        val (iconName, titleText, subText, toolId) = when {
            savedVideo != null -> {
                val s = (savedVideo.third / 1000).toInt()
                Quad("movie-open-outline", savedVideo.first, "Video · ${savedVideo.second} klip · ${String.format(Locale.US, "%d:%02d", s / 60, s % 60)}", "videoeditor")
            }
            studio != null ->
                Quad("palette-outline", studio.first, "${CsToolIds.NAME} · ${studio.second} · ${studio.third} layer", CsToolIds.ID)
            else -> return null
        }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            background = c.pressable(c.activeFill, 18)
            isClickable = true
            contentDescription = "Lanjutkan $titleText"
            setOnClickListener { v -> open(toolId, v) }
        }
        card.addView(FrameLayout(a).apply {
            background = c.shape(Color.argb(40, 128, 128, 128), 12)
            addView(MdiIconView(a).apply { setIconName(iconName); setIconSize(20f); setTextColor(c.activeInk) }, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text("LANJUTKAN", 10f, Color.argb(170, Color.red(c.activeInk), Color.green(c.activeInk), Color.blue(c.activeInk)), true).apply { letterSpacing = 0.1f })
        texts.addView(text(titleText, 14.5f, c.activeInk, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, dp(2)) })
        texts.addView(text(subText, 11.5f, Color.argb(190, Color.red(c.activeInk), Color.green(c.activeInk), Color.blue(c.activeInk))).apply { maxLines = 1 })
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(MdiIconView(a).apply { setIconName("chevron-right"); setIconSize(22f); setTextColor(c.activeInk) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        return card
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    private fun featuredGrid(): View {
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val items = featured.filter { a.homeToolMap.containsKey(it.first) }
        // Studio Kreatif (foto + desain + hapus latar dalam satu editor) selebar layar; editor lain berpasangan.
        val hero = items.firstOrNull()
        if (hero != null) grid.addView(featuredCard(hero.first, hero.second, hero.third), LinearLayout.LayoutParams(-1, 0, 1f))
        val rest = items.drop(1)
        for (r in 0 until (rest.size + 1) / 2) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0..1) {
                val item = rest.getOrNull(r * 2 + col)
                if (item == null) { row.addView(View(a), LinearLayout.LayoutParams(0, -1, 1f)); continue }
                row.addView(featuredCard(item.first, item.second, item.third), LinearLayout.LayoutParams(0, -1, 1f).apply {
                    if (col == 0) rightMargin = dp(5) else leftMargin = dp(5)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f).apply { if (hero != null || r > 0) topMargin = dp(10) })
        }
        return grid
    }

    private fun featuredCard(id: String, name: String, desc: String): View {
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            background = c.pressable(c.surface, 20, c.stroke)
            minimumHeight = dp(96)
            isClickable = true
            isFocusable = true
            contentDescription = "$name. $desc"
            setOnClickListener { v -> open(id, v) }
        }
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(iconBox(a.iconFor(id), 44, 22f, solid = true), LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(MdiIconView(a).apply { setIconName("arrow-right"); setIconSize(18f); setTextColor(c.muted) }, LinearLayout.LayoutParams(dp(24), dp(24)))
        card.addView(top)
        card.addView(View(a), LinearLayout.LayoutParams(1, 0, 1f))
        card.addView(text(name, 15f, c.ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(10), 0, dp(3)) })
        card.addView(text(desc, 11.5f, c.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        return card
    }

    private fun renderTabs() {
        tabsRow.removeAllViews()
        for ((key, pair) in tabs) {
            val active = key == EditingStudioState.tab
            tabsRow.addView(TextView(a).apply {
                text = pair.first
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(if (active) c.ink else c.muted)
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = if (active) c.shape(c.surface, 11, c.stroke) else null
                isClickable = true
                contentDescription = "Kategori ${pair.first}"
                setOnClickListener { EditingStudioState.tab = key; renderTabs() }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        renderGrid()
    }

    private fun renderGrid() {
        gridBox.removeAllViews()
        val ids = (tabs[EditingStudioState.tab]?.second ?: emptyList()).filter { a.homeToolMap.containsKey(it) }
        val cols = 4
        for (r in 0 until 2) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until cols) {
                val id = ids.getOrNull(r * cols + col)
                if (id == null) { row.addView(View(a), LinearLayout.LayoutParams(0, -1, 1f)); continue }
                row.addView(smallTile(id), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            // Baris kosong tetap dijaga tingginya agar tata letak tidak melompat saat ganti tab.
            gridBox.addView(row, LinearLayout.LayoutParams(-1, dp(82)))
        }
    }

    private fun smallTile(id: String): View {
        val name = (a.homeToolMap[id] ?: id)
        val tile = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = c.pressable(c.surface, 16, c.stroke)
            isClickable = true
            isFocusable = true
            contentDescription = name
            setOnClickListener { v -> open(id, v) }
        }
        tile.addView(iconBox(a.iconFor(id), 36, 18f, solid = false), LinearLayout.LayoutParams(dp(36), dp(36)))
        tile.addView(text(name, 10.5f, c.ink).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(3), dp(6), dp(3), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        return tile
    }
}
