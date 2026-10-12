package com.example.aidetest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Riwayat Undo/Redo milik GITLS untuk EditText editor.
 *
 * Versi lama memakai refleksi ke field "mEditor" pada EditText — field itu ada di
 * TextView (bukan EditText) dan termasuk non-SDK API, sehingga Undo/Redo selalu gagal
 * dengan toast "tidak tersedia". Kelas ini merekam setiap perubahan sebagai segmen
 * (start, teks lama, teks baru) sehingga hemat memori bahkan untuk file besar.
 * Ketikan beruntun digabung menjadi satu langkah agar Undo terasa natural.
 */
internal class EditorUndoHistory private constructor(edit: EditText) : TextWatcher {

    // WeakReference: registry memakai WeakHashMap<EditText, EditorUndoHistory>; bila nilai
    // memegang EditText secara kuat, kunci tidak pernah dilepas (bocor seluruh halaman).
    private val editRef = WeakReference(edit)

    private class Step(var start: Int, var oldText: String, var newText: String, var time: Long)

    private val undoStack = ArrayList<Step>()
    private val redoStack = ArrayList<Step>()
    private val pendingOld = ArrayList<String>()
    private var restoring = false
    private var totalChars = 0L
    var onStateChanged: (() -> Unit)? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    companion object {
        private const val MAX_STEPS = 300
        private const val MAX_CHARS = 3_000_000L
        private const val MERGE_WINDOW_MS = 1_000L
        private val registry = WeakHashMap<EditText, EditorUndoHistory>()

        fun attach(edit: EditText): EditorUndoHistory {
            registry[edit]?.let { return it }
            val history = EditorUndoHistory(edit)
            edit.addTextChangedListener(history)
            registry[edit] = history
            return history
        }

        fun of(edit: EditText): EditorUndoHistory? = registry[edit]
    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        if (restoring || s == null) return
        val end = (start + count).coerceAtMost(s.length)
        pendingOld.add(if (start in 0..end) s.subSequence(start, end).toString() else "")
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        if (restoring || s == null) return
        val old = if (pendingOld.isNotEmpty()) pendingOld.removeAt(pendingOld.size - 1) else ""
        val end = (start + count).coerceAtMost(s.length)
        val inserted = if (start in 0..end) s.subSequence(start, end).toString() else ""
        if (old == inserted) return
        record(start, old, inserted)
    }

    override fun afterTextChanged(s: Editable?) {}

    private fun record(start: Int, old: String, inserted: String) {
        val now = SystemClock.uptimeMillis()
        val last = undoStack.lastOrNull()
        val recent = last != null && now - last.time <= MERGE_WINDOW_MS
        if (last != null && recent) {
            val typing = old.isEmpty() && last.oldText.isEmpty() &&
                    last.start + last.newText.length == start &&
                    inserted.length <= 2 && !inserted.contains('\n')
            val backspace = inserted.isEmpty() && last.newText.isEmpty() &&
                    start + old.length == last.start && !old.contains('\n')
            // Keyboard dengan composing text mengganti kata yang sedang diketik setiap huruf
            // (mengganti ekor teks yang baru saja disisipkan oleh langkah sebelumnya).
            val lastEnd = last.start + last.newText.length
            val composing = old.isNotEmpty() && start >= last.start &&
                    start + old.length == lastEnd && last.newText.endsWith(old) &&
                    !inserted.contains('\n')
            when {
                typing -> {
                    totalChars += inserted.length
                    last.newText += inserted
                    last.time = now
                    clearRedo()
                    return
                }
                backspace -> {
                    totalChars += old.length
                    last.start = start
                    last.oldText = old + last.oldText
                    last.time = now
                    clearRedo()
                    return
                }
                composing -> {
                    totalChars += inserted.length
                    last.newText = last.newText.substring(0, last.newText.length - old.length) + inserted
                    last.time = now
                    clearRedo()
                    return
                }
            }
        }
        undoStack.add(Step(start, old, inserted, now))
        totalChars += (old.length + inserted.length).toLong()
        clearRedo()
        trim()
        onStateChanged?.invoke()
    }

    private fun clearRedo() {
        if (redoStack.isEmpty()) return
        redoStack.forEach { totalChars -= (it.oldText.length + it.newText.length).toLong() }
        redoStack.clear()
        onStateChanged?.invoke()
    }

    private fun trim() {
        while (undoStack.size > 1 && (undoStack.size > MAX_STEPS || totalChars > MAX_CHARS)) {
            val dropped = undoStack.removeAt(0)
            totalChars -= (dropped.oldText.length + dropped.newText.length).toLong()
        }
    }

    /** Hentikan penggabungan, misalnya setelah simpan atau aksi besar. */
    fun seal() {
        undoStack.lastOrNull()?.time = 0L
    }

    fun undo(): Boolean {
        val step = undoStack.lastOrNull() ?: return false
        if (!apply(step.start, step.newText, step.oldText)) {
            reset()
            return false
        }
        undoStack.removeAt(undoStack.size - 1)
        step.time = 0L
        redoStack.add(step)
        onStateChanged?.invoke()
        return true
    }

    fun redo(): Boolean {
        val step = redoStack.lastOrNull() ?: return false
        if (!apply(step.start, step.oldText, step.newText)) {
            reset()
            return false
        }
        redoStack.removeAt(redoStack.size - 1)
        step.time = 0L
        undoStack.add(step)
        onStateChanged?.invoke()
        return true
    }

    fun reset() {
        undoStack.clear()
        redoStack.clear()
        pendingOld.clear()
        totalChars = 0L
        onStateChanged?.invoke()
    }

    /** Ganti [expected] di posisi [start] dengan [replacement]; gagal aman jika teks tidak sinkron. */
    private fun apply(start: Int, expected: String, replacement: String): Boolean {
        val edit = editRef.get() ?: return false
        val text = edit.text ?: return false
        val end = start + expected.length
        if (start < 0 || end > text.length) return false
        if (!TextUtils.regionMatches(text, start, expected, 0, expected.length)) return false
        restoring = true
        try {
            text.replace(start, end, replacement)
        } finally {
            restoring = false
        }
        val cursor = (start + replacement.length).coerceIn(0, text.length)
        runCatching { edit.setSelection(cursor) }
        return true
    }
}

/** Satu aksi di laci Editor. */
internal class EditorDrawerAction(
    val icon: String,
    val label: String,
    val run: (View) -> Unit
)

/**
 * Laci Tools Editor: panel buka-tutup yang muncul DI ATAS area kode (overlay),
 * menggantikan daftar menu panjang. Tools dikelompokkan per kategori; tab terakhir
 * berubah mengikuti mode file aktif (JSON, CSV, XML, …) sebagai panel kontekstual.
 */
internal class EditorToolDrawer private constructor(
    private val a: MainActivity,
    private val host: FrameLayout,
    private val work: EditText
) {
    private val c = EditorChrome(a)
    private val scrim = View(a)
    private val panel = LinearLayout(a)
    private val tabsRow = LinearLayout(a)
    private val gridHolder = LinearLayout(a)
    private val gridScroll = ScrollView(a)
    private val tabViews = LinkedHashMap<String, TextView>()
    private var isOpen = false
    private var selectedTab: String = a.prefs.getString("editor_drawer_tab", "file") ?: "file"
    private val backHandler: () -> Boolean = {
        if (isOpen && panel.isAttachedToWindow) {
            close(true)
            true
        } else false
    }

    companion object {
        private var current: WeakReference<EditorToolDrawer>? = null

        fun install(a: MainActivity, host: FrameLayout, work: EditText): EditorToolDrawer {
            current?.get()?.dispose()
            val drawer = EditorToolDrawer(a, host, work)
            current = WeakReference(drawer)
            return drawer
        }

        fun toggle(a: MainActivity) {
            val drawer = current?.get()
            if (drawer == null || !drawer.panel.isAttachedToWindow) {
                a.toast("Laci tools tersedia saat file terbuka di editor")
                return
            }
            if (drawer.isOpen) drawer.close(true) else drawer.open()
        }

        fun close() {
            current?.get()?.close(false)
        }
    }

    init {
        scrim.setBackgroundColor(c.scrim)
        scrim.visibility = View.GONE
        scrim.isClickable = true
        scrim.contentDescription = "Tutup laci tools"
        scrim.setOnClickListener { close(true) }

        panel.orientation = LinearLayout.VERTICAL
        panel.background = c.shape(c.surface, 18, c.stroke)
        panel.elevation = c.dp(10).toFloat()
        panel.setPadding(c.dp(10), c.dp(6), c.dp(10), c.dp(8))
        panel.visibility = View.GONE
        panel.isClickable = true

        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(4), 0, 0, 0)
        }
        head.addView(MdiIconView(a).apply {
            setIconName("tools")
            setIconSize(17f)
            setTextColor(c.ink)
        }, LinearLayout.LayoutParams(c.dp(24), c.dp(24)).apply { rightMargin = c.dp(6) })
        head.addView(TextView(a).apply {
            text = "Tools Editor"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(c.ink)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(MdiIconView(a).apply {
            setIconName("close")
            setIconSize(19f)
            setTextColor(c.ink)
            background = c.pressable(c.surface, 12, c.stroke)
            contentDescription = "Tutup laci tools"
            isClickable = true
            setOnClickListener { close(true) }
        }, LinearLayout.LayoutParams(c.dp(36), c.dp(36)))
        panel.addView(head, LinearLayout.LayoutParams(-1, c.dp(40)))

        val tabsScroll = HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        tabsRow.orientation = LinearLayout.HORIZONTAL
        tabsRow.gravity = Gravity.CENTER_VERTICAL
        tabsScroll.addView(tabsRow)
        panel.addView(tabsScroll, LinearLayout.LayoutParams(-1, c.dp(40)).apply {
            topMargin = c.dp(4)
            bottomMargin = c.dp(4)
        })

        gridHolder.orientation = LinearLayout.VERTICAL
        gridScroll.isVerticalScrollBarEnabled = false
        gridScroll.overScrollMode = View.OVER_SCROLL_NEVER
        gridScroll.isNestedScrollingEnabled = true
        gridScroll.addView(gridHolder, FrameLayout.LayoutParams(-1, -2))
        panel.addView(gridScroll, LinearLayout.LayoutParams(-1, -2))

        host.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        host.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = c.dp(4)
            rightMargin = c.dp(4)
            bottomMargin = c.dp(4)
        })
        panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { dispose() }
        })
    }

    private fun tabs(): List<Pair<String, String>> {
        val list = arrayListOf("file" to "File", "edit" to "Edit", "code" to "Kode", "run" to "Jalankan")
        list.add("mode" to a.editorModeName(a.editorMode))
        return list
    }

    private fun actionsFor(tab: String): List<EditorDrawerAction> {
        val w = work
        return when (tab) {
            "file" -> listOf(
                EditorDrawerAction("file-tree-outline", "Explorer") { a.showEditorProjectExplorer(host) },
                EditorDrawerAction("file-plus-outline", "File baru") { a.showEditorCreateFilePicker() },
                EditorDrawerAction("folder-plus-outline", "Project") { a.showEditorModePicker() },
                EditorDrawerAction("folder-open-outline", "Buka") { a.pickFileForEditor() },
                EditorDrawerAction("content-save-outline", "Simpan") { a.saveEditorCurrent() },
                EditorDrawerAction("file-document-edit-outline", "Simpan sbg") { a.saveEditorAs() },
                EditorDrawerAction("pencil-outline", "Ganti nama") { a.renameEditorFile() },
                EditorDrawerAction("share-variant", "Bagikan") { a.shareText(w.text?.toString().orEmpty()) }
            )
            "edit" -> listOf(
                EditorDrawerAction("undo", "Undo") { w.undoSafe() },
                EditorDrawerAction("redo", "Redo") { w.redoSafe() },
                EditorDrawerAction("magnify", "Cari") { a.showEditorSearchDialog(w, false) },
                EditorDrawerAction("find-replace", "Ganti") { a.showEditorSearchDialog(w, true) },
                EditorDrawerAction("format-list-numbered", "Ke baris") { a.showEditorGoToLine() },
                EditorDrawerAction("text-search", "Cari project") { a.showEditorProjectSearch() },
                EditorDrawerAction("select-all", "Pilih semua") { w.requestFocus(); w.selectAll() },
                EditorDrawerAction("content-copy", "Salin semua") { copyAll() },
                EditorDrawerAction("format-letter-case", "Huruf") { a.showEditorCaseDialog() },
                EditorDrawerAction("counter", "Hitung") { a.showTextCount() }
            )
            "code" -> listOf(
                EditorDrawerAction("code-tags", "Emmet") { a.applyEditorEmmet() },
                EditorDrawerAction("format-align-left", "Format") { a.formatEditorDocument() },
                EditorDrawerAction("note-text-outline", "Snippet") { a.showEditorSnippets() },
                EditorDrawerAction("file-tree-outline", "Outline") { a.showEditorOutline() },
                EditorDrawerAction("creation", "Assist") { a.showEditorCodeAssist() },
                EditorDrawerAction("code-braces", "Saran") { EditorIdeExtras.showSuggestionPopup(a, w, a.editorMode) },
                EditorDrawerAction("bug-outline", "Cek kode") { a.showEditorDiagnostics() },
                EditorDrawerAction("wrench", "Perbaiki") { a.showEditorFixPreview() }
            )
            "run" -> listOf(
                EditorDrawerAction("play", "Preview") { a.showEditorLivePreview() },
                EditorDrawerAction("console", "Terminal") { a.toggleEditorTerminal() },
                EditorDrawerAction("content-save-outline", "Simpan") { a.saveEditorCurrent() },
                EditorDrawerAction("share-variant", "Bagikan") { a.shareText(w.text?.toString().orEmpty()) }
            )
            else -> modeActions()
        }
    }

    /** Panel kontekstual: berubah sesuai mode file aktif. */
    private fun modeActions(): List<EditorDrawerAction> = when (a.editorMode) {
        "json" -> listOf(
            EditorDrawerAction("format-align-left", "Rapikan") { a.transformEditorJson(true) },
            EditorDrawerAction("flash-outline", "Minify") { a.transformEditorJson(false) },
            EditorDrawerAction("check-circle", "Validasi") { a.validateEditorJson() },
            EditorDrawerAction("swap-horizontal", "Ke CSV") { a.jsonToCsvEditor() }
        )
        "csv" -> listOf(
            EditorDrawerAction("format-align-left", "Rapikan") { a.normalizeCsvEditor() },
            EditorDrawerAction("code-json", "Ke JSON") { a.csvToJsonEditor() },
            EditorDrawerAction("grid", "Info tabel") { a.showCsvInfo() }
        )
        "xml" -> listOf(
            EditorDrawerAction("check-circle", "Validasi") { a.validateXmlEditor() },
            EditorDrawerAction("format-align-left", "Format") { a.formatXmlEditor() }
        )
        "config" -> listOf(
            EditorDrawerAction("format-align-left", "Format") { a.formatConfigEditor() }
        )
        "base64" -> listOf(
            EditorDrawerAction("arrow-up", "Encode") { a.encodeBase64Editor() },
            EditorDrawerAction("download", "Decode") { a.decodeBase64Editor() }
        )
        "html", "css", "js" -> listOf(
            EditorDrawerAction("play", "Preview") { a.showEditorLivePreview() },
            EditorDrawerAction("code-tags", "Emmet") { a.applyEditorEmmet() },
            EditorDrawerAction("format-align-left", "Format") { a.formatEditorDocument() },
            EditorDrawerAction("file-tree-outline", "Explorer") { a.showEditorProjectExplorer(host) },
            EditorDrawerAction("bug-outline", "Cek kode") { a.showEditorDiagnostics() }
        )
        else -> listOf(
            EditorDrawerAction("format-letter-case", "Huruf") { a.showEditorCaseDialog() },
            EditorDrawerAction("pound", "Base64") { a.showEditorBase64Dialog() },
            EditorDrawerAction("counter", "Hitung") { a.showTextCount() },
            EditorDrawerAction("content-copy", "Salin semua") { copyAll() }
        )
    }

    private fun copyAll() {
        val clip = a.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clip?.setPrimaryClip(ClipData.newPlainText("Editor", work.text?.toString().orEmpty()))
        a.toast("Teks disalin")
    }

    private fun renderTabs() {
        tabsRow.removeAllViews()
        tabViews.clear()
        val keys = tabs().map { it.first }
        if (selectedTab !in keys) selectedTab = "file"
        for ((key, label) in tabs()) {
            val tv = TextView(a).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
                setPadding(c.dp(14), 0, c.dp(14), 0)
                isClickable = true
                isFocusable = true
                contentDescription = "Kategori $label"
                setOnClickListener {
                    selectedTab = key
                    a.prefs.edit().putString("editor_drawer_tab", key).apply()
                    styleTabs()
                    renderGrid()
                }
            }
            tabViews[key] = tv
            tabsRow.addView(tv, LinearLayout.LayoutParams(-2, c.dp(34)).apply { rightMargin = c.dp(6) })
        }
        styleTabs()
    }

    private fun styleTabs() {
        for ((key, tv) in tabViews) {
            val active = key == selectedTab
            tv.background = c.pressable(if (active) c.activeFill else c.surface, 17, if (active) null else c.stroke)
            tv.setTextColor(if (active) c.activeInk else c.ink)
            tv.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun renderGrid() {
        gridHolder.removeAllViews()
        val actions = actionsFor(selectedTab)
        val hostWidth = if (host.width > 0) host.width else a.resources.displayMetrics.widthPixels
        val cols = ((hostWidth - c.dp(28)) / c.dp(76)).coerceIn(4, 7)
        var row: LinearLayout? = null
        for ((i, action) in actions.withIndex()) {
            if (i % cols == 0) {
                val newRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                gridHolder.addView(newRow, LinearLayout.LayoutParams(-1, c.dp(66)))
                row = newRow
            }
            row?.addView(actionTile(action), LinearLayout.LayoutParams(0, -1, 1f).apply {
                setMargins(c.dp(2), c.dp(2), c.dp(2), c.dp(2))
            })
        }
        val remainder = actions.size % cols
        if (remainder != 0) repeat(cols - remainder) { row?.addView(View(a), LinearLayout.LayoutParams(0, -1, 1f)) }
    }

    private fun actionTile(action: EditorDrawerAction): View {
        val tile = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = c.pressable(c.surface2, 14)
            isClickable = true
            isFocusable = true
            contentDescription = action.label
            setOnClickListener { v ->
                // Laci tertutup otomatis setelah tool dipilih, lalu aksi berjalan.
                close(false)
                runCatching { action.run(v) }.onFailure { a.toast("Aksi gagal: ${it.message ?: it.javaClass.simpleName}") }
            }
        }
        tile.addView(MdiIconView(a).apply {
            setIconName(action.icon)
            setIconSize(20f)
            setTextColor(c.ink)
        }, LinearLayout.LayoutParams(-1, c.dp(26)))
        tile.addView(TextView(a).apply {
            text = action.label
            textSize = 10.5f
            setTextColor(c.ink)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(c.dp(2), c.dp(4), c.dp(2), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        return tile
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        renderTabs()
        renderGrid()
        scrim.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        scrim.bringToFront()
        panel.bringToFront()
        OverlayBack.push(backHandler)
        if (a.animationsEnabled()) {
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(140L).start()
            panel.alpha = 0f
            panel.translationY = c.dp(40).toFloat()
            panel.animate().alpha(1f).translationY(0f).setDuration(180L)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            scrim.alpha = 1f
            panel.alpha = 1f
            panel.translationY = 0f
        }
    }

    fun close(animate: Boolean) {
        if (!isOpen) return
        isOpen = false
        OverlayBack.remove(backHandler)
        if (animate && a.animationsEnabled()) {
            scrim.animate().alpha(0f).setDuration(120L).start()
            panel.animate().alpha(0f).translationY(c.dp(30).toFloat()).setDuration(140L).withEndAction {
                if (!isOpen) {
                    scrim.visibility = View.GONE
                    panel.visibility = View.GONE
                    panel.translationY = 0f
                    panel.alpha = 1f
                }
            }.start()
        } else {
            scrim.animate().cancel()
            panel.animate().cancel()
            scrim.visibility = View.GONE
            panel.visibility = View.GONE
            panel.translationY = 0f
            panel.alpha = 1f
        }
    }

    fun dispose() {
        OverlayBack.remove(backHandler)
        isOpen = false
    }
}

/** Undo: riwayat GITLS lebih dulu, lalu API publik TextView (API 23+) sebagai cadangan. */
internal fun EditText.undoSafe() {
    val history = EditorUndoHistory.of(this)
    if (history != null) {
        if (!history.undo()) Toast.makeText(context, "Tidak ada perubahan untuk di-undo", Toast.LENGTH_SHORT).show()
        return
    }
    val ok = runCatching { onTextContextMenuItem(android.R.id.undo) }.getOrDefault(false)
    if (!ok) Toast.makeText(context, "Tidak ada perubahan untuk di-undo", Toast.LENGTH_SHORT).show()
}

/** Redo: riwayat GITLS lebih dulu, lalu API publik TextView (API 23+) sebagai cadangan. */
internal fun EditText.redoSafe() {
    val history = EditorUndoHistory.of(this)
    if (history != null) {
        if (!history.redo()) Toast.makeText(context, "Tidak ada langkah untuk di-redo", Toast.LENGTH_SHORT).show()
        return
    }
    val ok = runCatching { onTextContextMenuItem(android.R.id.redo) }.getOrDefault(false)
    if (!ok) Toast.makeText(context, "Tidak ada langkah untuk di-redo", Toast.LENGTH_SHORT).show()
}
