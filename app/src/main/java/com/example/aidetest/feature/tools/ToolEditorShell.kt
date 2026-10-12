package com.example.aidetest

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Tool Editor Shell — perilaku bersama untuk SETIAP halaman tool, tanpa mengubah Beranda,
 * Tools, Favorit, dan Pengaturan.
 *
 * Disederhanakan (2.51): tidak ada lagi toolbar bawah (Laci/Undo/Redo/Salin/Favorit) maupun
 * laci tool dan menu ⋮. Setiap tool memakai SATU header ringkas di top bar: kembali · judul ·
 * ikon bantuan (?). Ikon bantuan membuka [showToolHelpSheet] yang memuat panduan dan aksi
 * sekunder (Tool terkait, Undo/Redo kolom, Bersihkan input, Buka/Tutup bagian, Riwayat).
 * Salin/Bagikan hasil tetap ada di kartu hasil (output()); Favorit dikelola dari Beranda/Semua
 * Tools (ikon ☆ atau tahan lama kartu tool).
 *
 * Yang tetap dipasang otomatis setelah renderer tool selesai (lihat clearPage / restoreSnapshot):
 *  - Bagian tool (toolSection) bisa dibuka-tutup (akordeon); status diingat per tool
 *  - Riwayat Undo/Redo untuk setiap kolom teks (EditorUndoHistory)
 *
 * Shell hanya menyembunyikan / menampilkan view yang sudah ada (tanpa memindah parent),
 * sehingga logika tool, harvest sesi form, dan snapshot Back tetap bekerja seperti biasa.
 */
internal const val TOOL_SECTION_TAG_PREFIX = "gitls:section|"
private const val TOOL_BAR_TAG = "gitls:toolEditorBar"
private const val TOOL_DRAWER_TAG = "gitls:toolEditorDrawer"

/** Halaman yang punya UI sendiri / bukan halaman tool. */
private val TOOL_SHELL_EXCLUDED = setOf(
    "home", "all", "favorites", "settings",
    "Editor", "Preview", "Console", "Bit Assistant", "Bit Room",
    CREATIVE_STUDIO_PAGE, "Video Editor", "Audio Editor", "Auto Trace", "Editing Studio",
    ANIM_STUDIO_PAGE, PRESENT_STUDIO_PAGE
)

internal class ToolSectionInfo(
    val page: String,
    val title: String,
    val labelRef: WeakReference<TextView>?,
    val baseText: String,
    val members: List<WeakReference<View>>
) {
    var collapsed = false
    val savedVisibility = WeakHashMap<View, Int>()
}

private object ToolShellRegistry {
    val sections = WeakHashMap<View, ToolSectionInfo>()
    val focusHooked = WeakHashMap<View, Boolean>()
    var lastFocusedEdit: WeakReference<EditText>? = null
    var drawerBackHandler: (() -> Boolean)? = null
}

internal fun MainActivity.isToolShellPage(name: String): Boolean {
    if (name in TOOL_SHELL_EXCLUDED) return false
    if (name.startsWith("Editor")) return false
    if (editorBottomBar.visibility == View.VISIBLE) return false
    return true
}

/** Dipanggil setiap ganti halaman: toolbar disembunyikan dan laci ditutup. */
internal fun MainActivity.hideToolEditorShell() {
    runCatching {
        mainContainer.findViewWithTag<View>(TOOL_BAR_TAG)?.visibility = View.GONE
        closeToolDrawer(false)
    }
}

/** Dipanggil (post) setelah halaman tool selesai dirender. */
internal fun MainActivity.applyToolEditorShell(name: String) {
    if (isFinishing || name != currentPage) return
    if (!isToolShellPage(name)) {
        hideToolEditorShell()
        return
    }
    runCatching {
        installToolFocusTracker()
        attachToolSections(name)
        attachFieldHistories(content)
        // Toolbar bawah per-tool dihapus (lihat dokumentasi file); pastikan sisa lama tersembunyi.
        mainContainer.findViewWithTag<View>(TOOL_BAR_TAG)?.visibility = View.GONE
    }.onFailure { android.util.Log.w("GITLS", "Tool editor shell skipped", it) }
}

/** Padding bawah toolbar mengikuti navigation bar / keyboard (dipanggil dari applyEdgeInsets). */
internal fun MainActivity.syncToolEditorBarInset(bottomSpace: Int) {
    runCatching {
        val bar = mainContainer.findViewWithTag<View>(TOOL_BAR_TAG) ?: return
        bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, dp(4) + bottomSpace)
    }
}

// ------------------------------------------------------------------ kolom teks

private fun MainActivity.installToolFocusTracker() {
    val root = rootFrame
    if (ToolShellRegistry.focusHooked[root] == true) return
    ToolShellRegistry.focusHooked[root] = true
    root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
        if (newFocus is EditText) ToolShellRegistry.lastFocusedEdit = WeakReference(newFocus)
    }
}

private fun MainActivity.activeToolEdit(): EditText? {
    val focused = currentFocus as? EditText
    if (focused != null && focused.isAttachedToWindow) return focused
    val last = ToolShellRegistry.lastFocusedEdit?.get()
    return if (last != null && last.isAttachedToWindow) last else null
}

private fun MainActivity.fieldUndoRedo(undo: Boolean) {
    val target = activeToolEdit()
    if (target == null) {
        toast("Ketuk kolom teks dulu untuk Undo/Redo")
        return
    }
    EditorUndoHistory.attach(target)
    if (undo) target.undoSafe() else target.redoSafe()
}

private fun attachFieldHistories(view: View) {
    if (view is EditText) {
        EditorUndoHistory.attach(view)
        return
    }
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) attachFieldHistories(view.getChildAt(i))
    }
}

// ------------------------------------------------------------------ collapsible sections

private fun isSectionHeader(v: View): Boolean =
    (v.tag as? String)?.startsWith(TOOL_SECTION_TAG_PREFIX) == true

private fun findHeaderLabel(header: View): TextView? {
    if (header is TextView && header !is MdiIconView) return header
    if (header is ViewGroup) {
        for (i in 0 until header.childCount) {
            val child = header.getChildAt(i)
            if (child is TextView && child !is MdiIconView) return child
        }
    }
    return null
}

private fun sectionPrefKey(info: ToolSectionInfo): String = "toolsec:${info.page}:${info.title}"

/** Kelompokkan view setelah setiap header bagian sampai header berikutnya. Idempoten. */
internal fun MainActivity.attachToolSections(page: String) {
    val children = (0 until content.childCount).map { content.getChildAt(it) }
    val headerIndexes = children.indices.filter { isSectionHeader(children[it]) }
    for ((n, hi) in headerIndexes.withIndex()) {
        val header = children[hi]
        val existing = ToolShellRegistry.sections[header]
        // Bagian yang sedang tertutup tidak dihitung ulang agar view baru tidak ikut tersembunyi.
        if (existing != null && existing.collapsed) continue
        val end = if (n + 1 < headerIndexes.size) headerIndexes[n + 1] else children.size
        val members = (hi + 1 until end).map { children[it] }.filter { it !== lastOutputCard }
        if (members.isEmpty()) continue
        val title = (header.tag as String).removePrefix(TOOL_SECTION_TAG_PREFIX)
        val label = existing?.labelRef?.get() ?: findHeaderLabel(header)
        val info = ToolSectionInfo(
            page,
            title,
            label?.let { WeakReference(it) },
            existing?.baseText ?: label?.text?.toString().orEmpty().ifBlank { title },
            members.map { WeakReference(it) }
        )
        ToolShellRegistry.sections[header] = info
        if (existing == null) {
            header.isClickable = true
            header.contentDescription = "Bagian $title. Ketuk untuk buka atau tutup"
            header.setOnClickListener { toggleSectionAccordion(header) }
            if (prefs.getBoolean(sectionPrefKey(info), false)) setSectionCollapsed(header, true)
        }
        updateSectionLabel(info)
    }
    applyCompactFirstVisit(page)
}

/**
 * Mode ringkas (akordeon): pada tool yang punya banyak bagian, hanya satu bagian terbuka
 * sehingga tombol tidak berserakan dan halaman hampir tidak perlu di-scroll.
 * Kunjungan pertama: bagian pertama terbuka, sisanya terlipat (status lalu diingat).
 */
private fun MainActivity.applyCompactFirstVisit(page: String) {
    val headers = currentSectionHeaders()
    if (headers.size < 2) return
    val key = "toolacc:$page"
    if (prefs.getBoolean(key, false)) return
    prefs.edit().putBoolean(key, true).apply()
    headers.drop(1).forEach { setSectionCollapsed(it, true) }
}

private fun MainActivity.toggleSectionAccordion(header: View) {
    val info = ToolShellRegistry.sections[header] ?: return
    val expand = info.collapsed
    setSectionCollapsed(header, !expand)
    if (expand) {
        // Buka satu, lipat yang lain.
        currentSectionHeaders().filter { it !== header }.forEach { setSectionCollapsed(it, true) }
        scrollToView(header)
    }
}

private fun updateSectionLabel(info: ToolSectionInfo) {
    val label = info.labelRef?.get() ?: return
    label.text = "${info.baseText}  ${if (info.collapsed) "▸" else "▾"}"
}

private fun MainActivity.setSectionCollapsed(header: View, collapsed: Boolean) {
    val info = ToolShellRegistry.sections[header] ?: return
    if (info.collapsed == collapsed) return
    info.collapsed = collapsed
    for (ref in info.members) {
        val v = ref.get() ?: continue
        if (collapsed) {
            info.savedVisibility[v] = v.visibility
            v.visibility = View.GONE
        } else {
            v.visibility = info.savedVisibility.remove(v) ?: View.VISIBLE
            if (animationsEnabled() && v.visibility == View.VISIBLE) {
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(140L).start()
            }
        }
    }
    prefs.edit().putBoolean(sectionPrefKey(info), collapsed).apply()
    updateSectionLabel(info)
}

private fun MainActivity.currentSectionHeaders(): List<View> =
    (0 until content.childCount).map { content.getChildAt(it) }
        .filter { isSectionHeader(it) && ToolShellRegistry.sections[it] != null }

private fun MainActivity.scrollToView(v: View) {
    scroll.post { scroll.smoothScrollTo(0, (v.top - dp(8)).coerceAtLeast(0)) }
}

// ------------------------------------------------------------------ bantuan

internal fun MainActivity.closeToolDrawer(animate: Boolean) {
    ToolShellRegistry.drawerBackHandler?.let { OverlayBack.remove(it) }
    ToolShellRegistry.drawerBackHandler = null
    val overlay = rootFrame.findViewWithTag<View>(TOOL_DRAWER_TAG) ?: return
    overlay.tag = null
    if (animate && animationsEnabled()) {
        overlay.animate().alpha(0f).setDuration(140L).withEndAction { rootFrame.removeView(overlay) }.start()
    } else {
        rootFrame.removeView(overlay)
    }
}

/**
 * Lembar bantuan tool (ikon ? di top bar): panduan singkat + aksi sekunder dalam satu dialog.
 * Menggantikan laci tool, toolbar bawah, dan menu ⋮ per-tool. Untuk halaman root (Semua Tools,
 * Favorit, Pengaturan) berisi tips memakai daftar tool + Tentang GITLS.
 */
internal fun MainActivity.showToolHelpSheet(name: String = currentPage) {
    val c = EditorChrome(this)
    val root = name == "all" || name == "favorites" || name == "settings" || name == "home"
    val toolId = toolIdForUx(name)?.takeIf { homeToolMap.containsKey(it) }
    var dialog: android.app.AlertDialog? = null

    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }
    val message = when {
        root -> "Ketuk kartu untuk membuka tool. Tahan lama kartu tool (atau ketuk ☆) untuk menambah/menghapus Favorit. " +
            "Tool favorit dan tool yang dipin tampil di Beranda."
        toolId != null -> {
            val help = toolHelp(toolId, homeToolMap[toolId] ?: name)
            "${help.purpose}\n\nKapan digunakan?\n${help.whenUse}\n\nContoh\n${help.example}"
        }
        else -> toolDescription(name)
    }
    body.addView(TextView(this).apply {
        text = message
        textSize = 13.5f
        setTextColor(c.ink)
        setLineSpacing(0f, 1.2f)
        setPadding(0, 0, 0, dp(10))
    })

    fun row(icon: String, textValue: String, onClick: () -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            background = c.pressable(c.surface2, 12)
            isClickable = true
            contentDescription = textValue
            setOnClickListener {
                dialog?.dismiss()
                runCatching { onClick() }.onFailure { toast("Aksi gagal: ${it.message ?: it.javaClass.simpleName}") }
            }
        }
        r.addView(MdiIconView(this).apply {
            setIconName(icon)
            setIconSize(19f)
            setTextColor(c.ink)
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(10) })
        r.addView(TextView(this).apply {
            text = textValue
            textSize = 14f
            setTextColor(c.ink)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(r, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(6) })
    }

    if (root) {
        row("history", "Riwayat aktivitas") { historyTool() }
        row("information-outline", "Tentang GITLS") { showAbout() }
    } else {
        if (name in rmAllPages) {
            // Dulu di menu ⋮ halaman Notifikasi.
            row("plus", "Tambah notifikasi") { showReminderEditor(null) }
            row("bell-outline", "Uji notifikasi") { sendTestNotification() }
            row("cog-outline", "Pengaturan notifikasi") { openNotificationSettings() }
        }
        if (toolId != null && relatedIdsForUx(toolId).isNotEmpty()) row("link-variant", "Tool terkait") { showRelatedTools(toolId) }
        val headers = currentSectionHeaders()
        if (headers.size >= 2) {
            val anyCollapsed = headers.any { ToolShellRegistry.sections[it]?.collapsed == true }
            row(if (anyCollapsed) "chevron-down" else "chevron-up", if (anyCollapsed) "Buka semua bagian" else "Tutup semua bagian") {
                headers.forEach { setSectionCollapsed(it, !anyCollapsed) }
                scroll.post { scroll.smoothScrollTo(0, 0) }
            }
        }
        if (activeToolEdit() != null) {
            row("undo", "Undo kolom teks terakhir") { fieldUndoRedo(true) }
            row("redo", "Redo kolom teks terakhir") { fieldUndoRedo(false) }
        }
        row("broom", "Bersihkan input") { confirmClearToolInputs() }
        row("history", "Riwayat") { historyTool() }
    }

    val scrollBody = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(body, FrameLayout.LayoutParams(-1, -2))
    }
    dialog = themedDialog()
        .setTitle(if (root) "Bantuan" else (homeToolMap[toolId ?: ""] ?: name))
        .setView(scrollBody)
        .setPositiveButton("Tutup", null)
        .show()
}

private fun MainActivity.confirmClearToolInputs() {
    val fields = ArrayList<EditText>()
    fun walk(v: View) {
        if (v is EditText) {
            if (v.isEnabled && v.visibility == View.VISIBLE && !v.text.isNullOrEmpty()) fields.add(v)
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(content)
    if (fields.isEmpty()) {
        toast("Tidak ada input yang perlu dibersihkan")
        return
    }
    themedDialog()
        .setTitle("Bersihkan input?")
        .setMessage("${fields.size} kolom akan dikosongkan. Bisa dikembalikan dengan Undo pada tiap kolom.")
        .setNegativeButton("Batal", null)
        .setPositiveButton("Bersihkan") { _, _ ->
            fields.forEach { f ->
                EditorUndoHistory.attach(f)
                f.setText("")
            }
            toast("Input dibersihkan")
        }
        .show()
}
