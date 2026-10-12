package com.example.aidetest

import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.EditText
import com.example.aidetest.MainActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fitur IDE tambahan ala VS Code untuk editor HTML/CSS/JS/JSON:
 * format document, outline simbol, snippets, auto-pair, Emmet lanjutan.
 */
object EditorIdeExtras {

    data class Symbol(val label: String, val line: Int, val col: Int, val kind: String)

    // ---------- Format document ----------
    fun format(text: String, mode: String): String = when (mode.lowercase()) {
        "html", "htm", "xml" -> formatHtml(text)
        "css" -> formatCss(text)
        "js", "javascript", "ts" -> formatJs(text)
        "json" -> formatJson(text)
        else -> text
    }

    private fun formatJson(text: String): String = try {
        val t = text.trim()
        when {
            t.startsWith("[") -> JSONArray(t).toString(2)
            else -> JSONObject(t).toString(2)
        }
    } catch (_: Exception) { text }

    private fun formatHtml(src: String): String {
        val voidTags = setOf("area","base","br","col","embed","hr","img","input","link","meta","param","source","track","wbr")
        val sb = StringBuilder()
        var i = 0
        var indent = 0
        fun pad() = "  ".repeat(indent.coerceAtLeast(0))
        while (i < src.length) {
            if (src[i] == '<') {
                val end = src.indexOf('>', i)
                if (end < 0) { sb.append(src.substring(i)); break }
                val tag = src.substring(i, end + 1)
                val name = tag.removePrefix("</").removePrefix("<").substringBefore(' ').substringBefore('>').substringBefore('/').lowercase()
                val isClose = tag.startsWith("</")
                val isSelf = tag.endsWith("/>") || name in voidTags
                if (isClose) indent = (indent - 1).coerceAtLeast(0)
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                sb.append(pad()).append(tag.trim())
                if (!isClose && !isSelf) indent++
                i = end + 1
            } else {
                val next = src.indexOf('<', i).let { if (it < 0) src.length else it }
                val chunk = src.substring(i, next).trim()
                if (chunk.isNotEmpty()) {
                    if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                    sb.append(pad()).append(chunk)
                }
                i = next
            }
        }
        return sb.toString().trim() + "\n"
    }

    private fun formatCss(src: String): String {
        val sb = StringBuilder()
        var indent = 0
        var i = 0
        fun pad() = "  ".repeat(indent.coerceAtLeast(0))
        while (i < src.length) {
            when (val c = src[i]) {
                '{' -> {
                    sb.append(" {\n")
                    indent++
                    sb.append(pad())
                    i++
                }
                '}' -> {
                    indent = (indent - 1).coerceAtLeast(0)
                    // trim trailing spaces on line
                    while (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
                    if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                    sb.append(pad()).append("}\n")
                    if (indent > 0) sb.append(pad())
                    i++
                }
                ';' -> {
                    sb.append(";\n").append(pad())
                    i++
                }
                '\n', '\r', '\t' -> i++
                else -> {
                    if (c == ' ' && (sb.isEmpty() || sb.last() == ' ' || sb.last() == '\n')) { i++; continue }
                    sb.append(c); i++
                }
            }
        }
        return sb.toString().trim() + "\n"
    }

    private fun formatJs(src: String): String {
        // Lightweight pretty-print: braces/semicolons + indent (bukan full AST).
        val sb = StringBuilder()
        var indent = 0
        var i = 0
        var inStr: Char? = null
        fun pad() = "  ".repeat(indent.coerceAtLeast(0))
        while (i < src.length) {
            val c = src[i]
            if (inStr != null) {
                sb.append(c)
                if (c == inStr && src.getOrNull(i - 1) != '\\') inStr = null
                i++; continue
            }
            when (c) {
                '"', '\'', '`' -> { inStr = c; sb.append(c); i++ }
                '{' -> {
                    sb.append(" {\n"); indent++; sb.append(pad()); i++
                }
                '}' -> {
                    indent = (indent - 1).coerceAtLeast(0)
                    while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\n')) sb.deleteCharAt(sb.length - 1)
                    sb.append('\n').append(pad()).append('}')
                    i++
                    if (src.getOrNull(i) == ',') { sb.append(','); i++ }
                    sb.append('\n').append(pad())
                }
                ';' -> {
                    sb.append(";\n").append(pad()); i++
                }
                '\n', '\r', '\t' -> i++
                ' ' -> {
                    if (sb.isEmpty() || sb.last() == ' ' || sb.last() == '\n') { i++; continue }
                    sb.append(' '); i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString().trim() + "\n"
    }

    // ---------- Outline / symbols ----------
    fun symbols(text: String, mode: String): List<Symbol> {
        val lines = text.split('\n')
        val out = mutableListOf<Symbol>()
        when (mode.lowercase()) {
            "html", "htm", "xml" -> {
                val re = Regex("""<(?<!/)([a-zA-Z][\w:-]*)([^>]*)>""")
                lines.forEachIndexed { idx, line ->
                    re.findAll(line).forEach { m ->
                        val name = m.groupValues[1]
                        if (name.lowercase() in setOf("br","hr","img","meta","link","input","source")) return@forEach
                        val id = Regex("""id=["']([^"']+)["']""").find(m.value)?.groupValues?.get(1)
                        val cls = Regex("""class=["']([^"']+)["']""").find(m.value)?.groupValues?.get(1)
                        val label = buildString {
                            append("<$name>")
                            if (id != null) append(" #$id")
                            if (cls != null) append(" .${cls.split(" ").first()}")
                        }
                        out.add(Symbol(label, idx + 1, m.range.first + 1, "tag"))
                    }
                }
            }
            "css" -> {
                val re = Regex("""([^{}/]+)\{""")
                lines.forEachIndexed { idx, line ->
                    re.findAll(line).forEach { m ->
                        val sel = m.groupValues[1].trim()
                        if (sel.isNotEmpty()) out.add(Symbol(sel, idx + 1, m.range.first + 1, "selector"))
                    }
                }
            }
            "js", "javascript", "ts" -> {
                val reFn = Regex("""(?:function\s+(\w+)|(?:const|let|var)\s+(\w+)\s*=\s*(?:async\s*)?(?:function|\([^)]*\)\s*=>)|class\s+(\w+))""")
                lines.forEachIndexed { idx, line ->
                    reFn.findAll(line).forEach { m ->
                        val name = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: "anon"
                        val kind = if (line.contains("class ")) "class" else "function"
                        out.add(Symbol(name, idx + 1, m.range.first + 1, kind))
                    }
                }
            }
            "json" -> {
                val re = Regex(""""([^"]+)"\s*:""")
                lines.forEachIndexed { idx, line ->
                    re.findAll(line).forEach { m ->
                        out.add(Symbol(m.groupValues[1], idx + 1, m.range.first + 1, "key"))
                    }
                }
            }
        }
        return out.take(200)
    }

    // ---------- Emmet expansion ----------
    fun expandEmmet(abbr: String): String? {
        val a = abbr.trim()
        if (a.isEmpty()) return null
        if (a == "!") return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Document</title>
</head>
<body>
  
</body>
</html>"""
        // tag#id.class*n
        val mul = Regex("""^(.+)\*(\d+)$""").matchEntire(a)
        if (mul != null) {
            val base = expandEmmet(mul.groupValues[1]) ?: return null
            val n = mul.groupValues[2].toInt().coerceIn(1, 50)
            return (1..n).joinToString("\n") { base }
        }
        val m = Regex("""^([a-zA-Z][\w:-]*)(?:#([\w-]+))?(?:\.([\w.-]+))?(?:\[([^\]]+)\])?(?:\{([^}]*)\})?$""").matchEntire(a)
        if (m != null) {
            val tag = m.groupValues[1]
            val id = m.groupValues[2].ifBlank { null }
            val cls = m.groupValues[3].ifBlank { null }?.replace('.', ' ')
            val attrs = m.groupValues[4].ifBlank { null }
            val text = m.groupValues[5]
            val voidTags = setOf("img","br","hr","input","meta","link","source","area","base","col","embed","param","track","wbr")
            val attrStr = buildString {
                if (id != null) append(" id=\"$id\"")
                if (cls != null) append(" class=\"$cls\"")
                if (attrs != null) append(" $attrs")
            }
            return if (tag in voidTags) "<$tag$attrStr>"
            else "<$tag$attrStr>${text.ifBlank { "" }}</$tag>"
        }
        // chain: div>ul>li
        if (a.contains('>')) {
            val parts = a.split('>').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            fun nest(i: Int): String {
                val piece = expandEmmet(parts[i]) ?: return ""
                if (i == parts.lastIndex) return piece
                val openEnd = piece.indexOf('>')
                val closeStart = piece.lastIndexOf("</")
                if (openEnd < 0 || closeStart < 0) return piece
                val inner = nest(i + 1)
                return piece.substring(0, openEnd + 1) + "\n  " + inner.replace("\n", "\n  ") + "\n" + piece.substring(closeStart)
            }
            return nest(0)
        }
        // siblings: div+p
        if (a.contains('+')) {
            return a.split('+').mapNotNull { expandEmmet(it.trim()) }.joinToString("\n")
        }

        // CSS-ish Emmet shortcuts
        val cssMap = mapOf(
            "m" to "margin: ;", "mt" to "margin-top: ;", "mb" to "margin-bottom: ;",
            "ml" to "margin-left: ;", "mr" to "margin-right: ;",
            "p" to "padding: ;", "pt" to "padding-top: ;", "pb" to "padding-bottom: ;",
            "pl" to "padding-left: ;", "pr" to "padding-right: ;",
            "w" to "width: ;", "h" to "height: ;",
            "bg" to "background: ;", "c" to "color: ;",
            "d" to "display: ;", "fx" to "display: flex;",
            "jc" to "justify-content: ;", "ai" to "align-items: ;",
            "br" to "border-radius: ;", "fs" to "font-size: ;",
            "fw" to "font-weight: ;", "ta" to "text-align: ;"
        )
        val cssKey = a.lowercase()
        if (cssMap.containsKey(cssKey)) return cssMap[cssKey]
        val mNum = Regex("""^([mpwh])(\d+)$""").matchEntire(cssKey)
        if (mNum != null) {
            val prop = when (mNum.groupValues[1]) {
                "m" -> "margin"; "p" -> "padding"; "w" -> "width"; "h" -> "height"; else -> null
            }
            if (prop != null) return "$prop: ${mNum.groupValues[2]}px;"
        }

        return when (a) {
            "div" -> "<div></div>"
            "p" -> "<p></p>"
            "h1" -> "<h1></h1>"
            "h2" -> "<h2></h2>"
            "h3" -> "<h3></h3>"
            "button" -> "<button></button>"
            "img" -> "<img src=\"\" alt=\"\">"
            "a" -> "<a href=\"\"></a>"
            "ul" -> "<ul>\n  <li></li>\n</ul>"
            "ol" -> "<ol>\n  <li></li>\n</ol>"
            "table" -> "<table>\n  <tr>\n    <td></td>\n  </tr>\n</table>"
            "form" -> "<form action=\"\" method=\"post\">\n  \n</form>"
            "input" -> "<input type=\"text\" name=\"\">"
            "span" -> "<span></span>"
            "section" -> "<section></section>"
            "header" -> "<header></header>"
            "footer" -> "<footer></footer>"
            "nav" -> "<nav></nav>"
            "main" -> "<main></main>"
            "lorem" -> "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
            else -> null
        }
    }

    /** Expand abbreviation di kursor (kata sebelum kursor), bukan seluruh dokumen. */
    /**
     * Expand Emmet abbreviation immediately before the cursor.
     * Supports: !, div, div.card#id, ul>li*3, m10 (CSS), fx, etc.
     * @param endExclusive cursor / end of abbreviation (default = selectionStart)
     * @param stripTrailingNewline if a newline was just typed after the abbr, remove it
     */
    fun expandEmmetAtCursor(
        edit: EditText,
        endExclusive: Int? = null,
        stripTrailingNewline: Boolean = false
    ): Boolean {
        val text = edit.text ?: return false
        var sel = (endExclusive ?: edit.selectionStart).coerceIn(0, text.length)
        // If newline was just inserted after abbr, point sel at the char before \n
        if (stripTrailingNewline && sel > 0 && text[sel - 1] == '\n') {
            sel -= 1
        }
        if (sel == 0) return false
        var start = sel - 1
        while (start >= 0) {
            val c = text[start]
            // stop at whitespace / string / tag boundaries — keep ! . # * + > [ ] $ - : @ for Emmet
            if (c.isWhitespace() || c == '<' || c == '"' || c == '\'' || c == '`' ||
                c == ';' || c == '{' || c == '}' || c == '(' || c == ')' || c == '=') break
            start--
        }
        start++
        if (start >= sel) return false
        val abbr = text.subSequence(start, sel).toString().trim()
        if (abbr.isEmpty()) return false
        val expanded = expandEmmet(abbr) ?: return false
        val deleteEnd = if (stripTrailingNewline && sel < text.length && text[sel] == '\n') sel + 1 else sel
        text.replace(start, deleteEnd, expanded)
        // Place cursor inside empty tag pair if present, else at end
        val empty = expanded.indexOf("></")
        if (empty >= 0) {
            edit.setSelection(start + empty + 1)
        } else {
            // For CSS props ending with ": ;" put cursor between
            val colonSpace = expanded.indexOf(": ;")
            if (colonSpace >= 0) edit.setSelection(start + colonSpace + 2)
            else edit.setSelection((start + expanded.length).coerceAtMost(text.length))
        }
        return true
    }

    // ---------- Snippets ----------
    fun snippetsFor(mode: String): List<Pair<String, String>> = when (mode.lowercase()) {
        "html", "htm" -> listOf(
            "HTML5 boilerplate" to expandEmmet("!")!!,
            "div container" to "<div class=\"container\">\n  \n</div>",
            "link CSS" to "<link rel=\"stylesheet\" href=\"style.css\">",
            "script JS" to "<script src=\"app.js\"></script>",
            "form basic" to expandEmmet("form")!!,
            "nav + ul" to "<nav>\n  <ul>\n    <li><a href=\"#\">Home</a></li>\n  </ul>\n</nav>"
        )
        "css" -> listOf(
            "reset margin" to "* {\n  margin: 0;\n  padding: 0;\n  box-sizing: border-box;\n}\n",
            "flex center" to ".center {\n  display: flex;\n  align-items: center;\n  justify-content: center;\n}\n",
            "grid 3 col" to ".grid {\n  display: grid;\n  grid-template-columns: repeat(3, 1fr);\n  gap: 16px;\n}\n",
            "media query" to "@media (max-width: 768px) {\n  \n}\n",
            "button style" to "button {\n  padding: 10px 16px;\n  border: none;\n  border-radius: 8px;\n  background: #111;\n  color: #fff;\n  cursor: pointer;\n}\n"
        )
        "js", "javascript" -> listOf(
            "DOM ready" to "document.addEventListener('DOMContentLoaded', () => {\n  \n});\n",
            "fetch JSON" to "async function loadData(url) {\n  const res = await fetch(url);\n  if (!res.ok) throw new Error(res.statusText);\n  return res.json();\n}\n",
            "click handler" to "document.querySelector('button')?.addEventListener('click', (e) => {\n  \n});\n",
            "forEach" to "items.forEach((item, index) => {\n  \n});\n",
            "try/catch" to "try {\n  \n} catch (err) {\n  console.error(err);\n}\n"
        )
        "json" -> listOf(
            "object" to "{\n  \"key\": \"value\"\n}\n",
            "array" to "[\n  {\n    \"id\": 1\n  }\n]\n"
        )
        else -> listOf(
            "Lorem ipsum" to expandEmmet("lorem")!!
        )
    }

    // ---------- Auto-pair brackets / quotes + Emmet on Enter/Tab ----------
    /**
     * Auto-pair ()[]{} ""'' , auto-indent on Enter, and Emmet expand on Enter/Tab.
     * Typing `!` then Enter → HTML5 boilerplate (no need to press Emmet button).
     * Soft-keyboard safe: also intercepts newline insertion via TextWatcher.
     */
    fun attachAutoPair(edit: EditText, modeProvider: (() -> String)? = null) {
        val pairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')

        fun tryEmmet(): Boolean = expandEmmetAtCursor(edit)

        fun doAutoIndent(): Boolean {
            val text = edit.text ?: return false
            val sel = edit.selectionStart.coerceIn(0, text.length)
            val lineStart = text.lastIndexOf('\n', (sel - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val line = text.subSequence(lineStart, sel).toString()
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            val extra = if (line.trimEnd().endsWith("{") || line.trimEnd().endsWith(">")) "  " else ""
            text.insert(sel, "\n$indent$extra")
            edit.setSelection(sel + 1 + indent.length + extra.length)
            return true
        }

        edit.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    // Emmet first: ! / div.card / ul>li*3 / m10 / fx …
                    if (tryEmmet()) return@setOnKeyListener true
                    // else normal auto-indent newline
                    return@setOnKeyListener doAutoIndent()
                }
                KeyEvent.KEYCODE_TAB -> {
                    // Tab expands Emmet if possible, otherwise inserts 2 spaces
                    if (tryEmmet()) return@setOnKeyListener true
                    val text = edit.text ?: return@setOnKeyListener false
                    val sel = edit.selectionStart.coerceIn(0, text.length)
                    text.insert(sel, "  ")
                    edit.setSelection(sel + 2)
                    return@setOnKeyListener true
                }
            }
            false
        }

        // Soft keyboard often inserts '\n' without KEYCODE_ENTER reaching OnKeyListener.
        // Detect that case and expand Emmet abbreviation that was before the newline.
        edit.addTextChangedListener(object : TextWatcher {
            private var skip = false
            private var pendingEmmetCheck = false
            private var newlineAt = -1

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (skip || s == null) return
                // Detect single '\n' insertion
                if (count == 1 && before == 0 && s.getOrNull(start) == '\n') {
                    pendingEmmetCheck = true
                    newlineAt = start
                }
                // Auto-pair brackets
                if (count == 1 && before == 0) {
                    val c = s.getOrNull(start) ?: return
                    val close = pairs[c] ?: return
                    val next = s.getOrNull(start + 1)
                    if (next == close) return
                    skip = true
                    try {
                        val ed = edit.text ?: return
                        ed.insert(start + 1, close.toString())
                        edit.setSelection(start + 1)
                    } finally {
                        skip = false
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {
                if (skip || !pendingEmmetCheck) return
                pendingEmmetCheck = false
                val at = newlineAt
                newlineAt = -1
                if (at < 0) return
                skip = true
                try {
                    // endExclusive = position of the newline we just inserted
                    expandEmmetAtCursor(edit, endExclusive = at + 1, stripTrailingNewline = true)
                } finally {
                    skip = false
                }
            }
        })
    }


    // ---------- Context-aware suggestions ----------
    fun suggestionsAt(text: String, cursor: Int, mode: String): List<String> {
        val before = text.take(cursor.coerceIn(0, text.length))
        val word = before.takeLastWhile {
            it.isLetterOrDigit() || it == '-' || it == '_' || it == '@' || it == '.'
        }
        if (word.isEmpty()) return emptyList()
        val lower = word.lowercase()
        val pool = when (mode.lowercase()) {
            "html", "htm" -> htmlSuggestions()
            "css" -> cssSuggestions()
            "js", "javascript", "ts" -> jsSuggestions()
            else -> emptyList()
        }
        return pool.filter { it.startsWith(word, ignoreCase = true) || it.lowercase().contains(lower) }
            .distinct()
            .take(12)
    }

    private fun htmlSuggestions(): List<String> = listOf(
        "div", "span", "section", "header", "footer", "nav", "main", "article",
        "button", "input", "form", "label", "select", "option", "textarea",
        "img", "a", "ul", "ol", "li", "table", "tr", "td", "th", "thead", "tbody",
        "h1", "h2", "h3", "p", "br", "hr", "meta", "link", "script", "style",
        "class=\"\"", "id=\"\"", "href=\"\"", "src=\"\"", "alt=\"\"", "type=\"\"",
        "placeholder=\"\"", "required", "disabled", "readonly"
    )

    private fun cssSuggestions(): List<String> = listOf(
        "display", "flex", "grid", "block", "inline-block", "none",
        "position", "relative", "absolute", "fixed", "sticky",
        "margin", "padding", "width", "height", "min-width", "max-width",
        "color", "background", "background-color", "border", "border-radius",
        "font-size", "font-weight", "font-family", "line-height", "text-align",
        "justify-content", "align-items", "gap", "flex-direction", "flex-wrap",
        "overflow", "opacity", "z-index", "box-shadow", "transition", "transform",
        "@media (max-width: 768px)", ":hover", ":focus", ":active"
    )

    private fun jsSuggestions(): List<String> = listOf(
        "const", "let", "var", "function", "return", "if", "else", "for", "while",
        "async", "await", "try", "catch", "throw", "class", "import", "export",
        "document", "window", "console.log", "console.error", "querySelector",
        "querySelectorAll", "addEventListener", "getElementById", "createElement",
        "fetch", "JSON.parse", "JSON.stringify", "localStorage", "setTimeout",
        "setInterval", "Promise", "Array.from", "map", "filter", "forEach", "reduce"
    )

    /** Show suggestion popup and insert chosen item at cursor. */
    fun showSuggestionPopup(activity: MainActivity, edit: EditText, mode: String) {
        val cursor = edit.selectionStart.coerceAtLeast(0)
        val items = suggestionsAt(edit.text?.toString().orEmpty(), cursor, mode)
        if (items.isEmpty()) {
            activity.toast("Tidak ada saran")
            return
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle("Code Assist")
            .setItems(items.toTypedArray()) { _, which ->
                val chosen = items[which]
                val text = edit.text ?: return@setItems
                val before = text.toString().take(cursor)
                val wordLen = before.takeLastWhile {
                    it.isLetterOrDigit() || it == '-' || it == '_' || it == '@' || it == '.'
                }.length
                val wordStart = cursor - wordLen
                text.replace(wordStart, cursor, chosen)
                edit.setSelection(wordStart + chosen.length)
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

}

// ---------- MainActivity wiring ----------
internal fun MainActivity.formatEditorDocument() {
    val box = editorBox ?: return
    val mode = editorMode
    val formatted = EditorIdeExtras.format(box.text.toString(), mode)
    if (formatted == box.text.toString()) {
        toast("Sudah rapi / format tidak berubah")
        return
    }
    val sel = box.selectionStart
    box.setText(formatted)
    box.setSelection(sel.coerceIn(0, box.length()))
    toast("Document formatted")
}

internal fun MainActivity.showEditorOutline() {
    val box = editorBox ?: return
    val syms = EditorIdeExtras.symbols(box.text.toString(), editorMode)
    if (syms.isEmpty()) {
        toast("Tidak ada simbol di dokumen ini")
        return
    }
    val labels = syms.map {
        val icon = when (it.kind) {
            "function" -> "ƒ"
            "class" -> "C"
            "selector" -> "•"
            "key" -> ":"
            else -> "<>"
        }
        "$icon  ${it.label}   :${it.line}"
    }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle("Outline · ${editorModeName(editorMode)}")
        .setItems(labels) { _, which ->
            val s = syms[which]
            // pindah kursor ke baris
            val text = box.text.toString()
            var pos = 0
            var line = 1
            while (pos < text.length && line < s.line) {
                if (text[pos] == '\n') line++
                pos++
            }
            box.requestFocus()
            box.setSelection(pos.coerceIn(0, box.length()))
        }
        .setNegativeButton("Tutup", null)
        .show()
}

internal fun MainActivity.showEditorSnippets() {
    val box = editorBox ?: return
    val items = EditorIdeExtras.snippetsFor(editorMode)
    AlertDialog.Builder(this)
        .setTitle("Snippets · ${editorModeName(editorMode)}")
        .setItems(items.map { it.first }.toTypedArray()) { _, which ->
            val snippet = items[which].second
            val a = box.selectionStart.coerceAtLeast(0)
            val b = box.selectionEnd.coerceAtLeast(0)
            val start = minOf(a, b)
            val end = maxOf(a, b)
            box.text.replace(start, end, snippet)
            box.setSelection((start + snippet.length).coerceAtMost(box.length()))
        }
        .setNegativeButton("Batal", null)
        .show()
}

internal fun MainActivity.applyEditorEmmet() {
    val box = editorBox ?: return
    if (EditorIdeExtras.expandEmmetAtCursor(box)) {
        toast("Emmet expanded")
        return
    }
    // fallback: seluruh teks jika pendek
    val t = box.text.toString().trim()
    val x = EditorIdeExtras.expandEmmet(t)
    if (x != null) {
        box.setText(x)
        toast("Emmet expanded")
    } else {
        toast("Emmet: ketik singkatan di kursor, contoh div.card#main atau !")
    }
}
