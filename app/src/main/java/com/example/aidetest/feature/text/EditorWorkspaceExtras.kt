package com.example.aidetest

import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * Lightweight editor-workspace features.
 * No terminal/runtime dependency: project search, go-to-line, and small code assist.
 */
internal object EditorWorkspaceExtras {
    data class SearchHit(val file: File, val line: Int, val column: Int, val text: String)

    fun suggestions(mode: String, prefix: String): List<String> {
        val p = prefix.lowercase(Locale.getDefault())
        val source = when (mode.lowercase()) {
            "html", "htm" -> listOf(
                "div", "span", "section", "header", "footer", "main", "nav", "button",
                "input", "textarea", "form", "label", "img", "a", "ul", "li", "table",
                "script", "style", "meta", "link", "title", "body", "head", "html"
            )
            "css" -> listOf(
                "display", "position", "width", "height", "margin", "padding", "color",
                "background", "font-size", "font-weight", "border", "border-radius",
                "box-shadow", "flex", "flex-direction", "justify-content", "align-items",
                "grid", "gap", "opacity", "transform", "transition", "z-index"
            )
            "js", "javascript", "ts" -> listOf(
                "const", "let", "var", "function", "return", "if", "else", "for", "while",
                "class", "new", "async", "await", "import", "export", "document",
                "window", "console", "querySelector", "getElementById", "addEventListener",
                "createElement", "setTimeout", "setInterval", "JSON", "Math", "Array",
                "Object", "Promise"
            )
            "json" -> listOf("true", "false", "null")
            else -> emptyList()
        }
        return source.filter { p.isBlank() || it.lowercase(Locale.getDefault()).startsWith(p) }.take(12)
    }

    fun search(root: File, query: String, maxFiles: Int = 300, maxHits: Int = 500): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty() || !root.exists()) return emptyList()
        val hits = ArrayList<SearchHit>()
        var scanned = 0
        fun walk(dir: File) {
            if (hits.size >= maxHits || scanned >= maxFiles) return
            val children = dir.listFiles()?.sortedBy { it.name.lowercase(Locale.getDefault()) } ?: return
            for (f in children) {
                if (hits.size >= maxHits || scanned >= maxFiles) return
                if (f.name.startsWith(".") || f.name == "build" || f.name == "node_modules") continue
                if (f.isDirectory) {
                    walk(f)
                } else if (f.isFile && f.length() <= 2_000_000L && isTextFile(f)) {
                    scanned++
                    runCatching {
                        f.useLines { lines ->
                            lines.forEachIndexed { index, line ->
                                if (hits.size >= maxHits) return@forEachIndexed
                                val col = line.indexOf(q, ignoreCase = true)
                                if (col >= 0) hits += SearchHit(f, index + 1, col + 1, line.trim().take(180))
                            }
                        }
                    }
                }
            }
        }
        walk(root)
        return hits
    }

    private fun isTextFile(f: File): Boolean {
        val ext = f.extension.lowercase(Locale.getDefault())
        return ext in setOf(
            "html", "htm", "css", "js", "mjs", "cjs", "ts", "tsx", "jsx", "json", "xml",
            "md", "txt", "csv", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties",
            "py", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "go", "rs", "php", "sh"
        )
    }
}

internal fun MainActivity.showEditorProjectSearch() {
    val root = editorProjectDir ?: editorFile?.parentFile
    if (root == null || !root.exists()) {
        toast("Buka project atau file terlebih dahulu")
        return
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(4), dp(18), 0)
    }
    val query = edit("Cari di seluruh project")
    box.addView(query)
    val info = TextView(this).apply {
        text = "Mencari file teks di: ${root.name}"
        textSize = 11f
        setTextColor(textMuted)
        setPadding(0, dp(8), 0, dp(4))
    }
    box.addView(info)
    val dialog = AlertDialog.Builder(this)
        .setTitle("Cari seluruh project")
        .setView(box)
        .setNegativeButton("Batal", null)
        .setPositiveButton("Cari", null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val q = query.text.toString().trim()
            if (q.isBlank()) { toast("Teks pencarian kosong"); return@setOnClickListener }
            info.text = "Mencari…"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            Thread {
                val hits = EditorWorkspaceExtras.search(root, q)
                runOnUiThread {
                    if (isFinishing || isDestroyed || currentPage != "Editor") return@runOnUiThread
                    dialog.dismiss()
                    showEditorProjectSearchResults(root, q, hits)
                }
            }.start()
        }
    }
    dialog.show()
}

private fun MainActivity.showEditorProjectSearchResults(root: File, query: String, hits: List<EditorWorkspaceExtras.SearchHit>) {
    if (hits.isEmpty()) {
        AlertDialog.Builder(this)
            .setTitle("Tidak ditemukan")
            .setMessage("Tidak ada hasil untuk \"$query\" di ${root.name}.")
            .setPositiveButton("OK", null)
            .show()
        return
    }
    val labels = hits.map {
        val rel = runCatching { it.file.relativeTo(root).path }.getOrDefault(it.file.name)
        "$rel:${it.line}:${it.column}  ${it.text}"
    }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle("${hits.size}${if (hits.size >= 500) "+" else ""} hasil · $query")
        .setItems(labels) { _, which ->
            val hit = hits[which]
            editor(hit.file)
            editorBox?.let { box ->
                val pos = positionForLineColumn(box.text.toString(), hit.line, hit.column)
                box.requestFocus()
                box.setSelection(pos.coerceIn(0, box.length()))
            }
        }
        .setNegativeButton("Tutup", null)
        .show()
}

internal fun MainActivity.showEditorGoToLine() {
    val box = editorBox ?: return
    val total = box.text.toString().count { it == '\n' } + 1
    val input = edit("Nomor baris (1-$total)").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
    AlertDialog.Builder(this)
        .setTitle("Ke baris")
        .setView(input)
        .setNegativeButton("Batal", null)
        .setPositiveButton("Pindah") { _, _ ->
            val line = input.text.toString().toIntOrNull()?.coerceIn(1, total) ?: run {
                toast("Nomor baris tidak valid")
                return@setPositiveButton
            }
            val pos = positionForLineColumn(box.text.toString(), line, 1)
            box.requestFocus()
            box.setSelection(pos.coerceIn(0, box.length()))
            box.post { box.scrollTo(0, line * (box.lineHeight.coerceAtLeast(1))) }
        }
        .show()
}

private fun positionForLineColumn(text: String, line: Int, column: Int): Int {
    if (line <= 1) return (column - 1).coerceAtLeast(0).coerceAtMost(text.length)
    var currentLine = 1
    var pos = 0
    while (pos < text.length && currentLine < line) {
        if (text[pos] == '\n') currentLine++
        pos++
    }
    return (pos + column - 1).coerceAtMost(text.length)
}

internal fun MainActivity.showEditorCodeAssist() {
    val box = editorBox ?: return
    val cursor = box.selectionStart.coerceAtLeast(0).coerceAtMost(box.length())
    val before = box.text.subSequence(0, cursor).toString()
    val prefix = Regex("[A-Za-z_][A-Za-z0-9_-]*$").find(before)?.value.orEmpty()
    val suggestions = EditorWorkspaceExtras.suggestions(editorMode, prefix)
    if (suggestions.isEmpty()) {
        toast("Tidak ada saran untuk \"$prefix\"")
        return
    }
    AlertDialog.Builder(this)
        .setTitle("Code Assist${if (prefix.isBlank()) "" else " · $prefix"}")
        .setItems(suggestions.toTypedArray()) { _, which ->
            val selected = suggestions[which]
            val start = (cursor - prefix.length).coerceAtLeast(0)
            box.text.replace(start, cursor, selected)
            box.setSelection((start + selected.length).coerceAtMost(box.length()))
        }
        .setNegativeButton("Batal", null)
        .show()
}
