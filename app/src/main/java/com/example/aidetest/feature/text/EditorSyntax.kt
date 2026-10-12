package com.example.aidetest

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.widget.EditText

/**
 * Lightweight VS Code–style syntax highlighting for the built-in editor.
 * Works on EditText via color spans. Debounced so typing stays responsive.
 */
object EditorSyntax {
    private const val MAX_CHARS = 80_000
    private const val DEBOUNCE_MS = 90L

    // Light-theme syntax palette.
    private val TAG = Color.parseColor("#800000")
    private val ATTR = Color.parseColor("#E50000")
    private val STRING = Color.parseColor("#A31515")
    private val COMMENT = Color.parseColor("#008000")
    private val KEYWORD = Color.parseColor("#0000FF")
    private val KW2 = Color.parseColor("#AF00DB")
    private val NUMBER = Color.parseColor("#098658")
    private val FUNC = Color.parseColor("#795E26")
    private val PROP = Color.parseColor("#001080")
    private val PUNCT = Color.parseColor("#383A42")
    private val DEFAULT = Color.parseColor("#24292F")

    // Dark-theme syntax palette tuned for the app's #0A0A0B / #1C1C1F editor.
    private val DTAG = Color.parseColor("#FF7AB2")
    private val DATTR = Color.parseColor("#9CDCFE")
    private val DSTRING = Color.parseColor("#CE9178")
    private val DCOMMENT = Color.parseColor("#6A9955")
    private val DKEYWORD = Color.parseColor("#C586C0")
    private val DKW2 = Color.parseColor("#4FC1FF")
    private val DNUMBER = Color.parseColor("#B5CEA8")
    private val DFUNC = Color.parseColor("#DCDCAA")
    private val DPROP = Color.parseColor("#9CDCFE")
    private val DPUNCT = Color.parseColor("#D4D4D4")
    private val DDEFAULT = Color.parseColor("#E6E6E6")

    private val JS_KEYWORDS = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default",
        "delete", "do", "else", "export", "extends", "false", "finally", "for",
        "function", "if", "import", "in", "instanceof", "let", "new", "null",
        "return", "super", "switch", "this", "throw", "true", "try", "typeof",
        "var", "void", "while", "with", "yield", "async", "await", "of", "from",
        "static", "get", "set"
    )
    private val JS_TYPES = setOf(
        "undefined", "NaN", "Infinity", "Promise", "Array", "Object", "String",
        "Number", "Boolean", "Map", "Set", "JSON", "Math", "Date", "RegExp",
        "console", "window", "document"
    )
    private val CSS_KEYWORDS = setOf(
        "important", "from", "to", "and", "or", "not", "only"
    )

    fun attach(edit: EditText, mode: String, darkTheme: Boolean = false) {
        apply(edit.text, mode, darkTheme)
        val handler = Handler(Looper.getMainLooper())
        var pending: Runnable? = null
        var busy = false
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (busy) return
                pending?.let { handler.removeCallbacks(it) }
                val run = Runnable {
                    val ed = s ?: return@Runnable
                    busy = true
                    try { apply(ed, mode, darkTheme) } finally { busy = false }
                }
                pending = run
                handler.postDelayed(run, DEBOUNCE_MS)
            }
        })
    }

    fun apply(ed: Editable, mode: String, darkTheme: Boolean = false) {
        if (ed.length > MAX_CHARS) return
        val spans = ed.getSpans(0, ed.length, ForegroundColorSpan::class.java)
        for (sp in spans) ed.removeSpan(sp)
        ed.setSpan(ForegroundColorSpan(if (darkTheme) DDEFAULT else DEFAULT), 0, ed.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        when (mode.lowercase()) {
            "html", "htm", "xml" -> colorHtml(ed, darkTheme)
            "css" -> colorCss(ed, darkTheme)
            "js", "javascript", "ts" -> colorJs(ed, darkTheme)
            "json" -> colorJson(ed, darkTheme)
            else -> { /* keep default */ }
        }
    }

    private fun colorHtml(ed: Editable, darkTheme: Boolean) {
        val t = ed.toString()
        // comments
        markAll(ed, t, Regex("<!--[\\s\\S]*?-->"), if (darkTheme) DCOMMENT else COMMENT)
        // tags
        Regex("</?[A-Za-z][\\w:-]*").findAll(t).forEach { m ->
            set(ed, m.range.first, m.range.last + 1, if (darkTheme) DTAG else TAG)
        }
        // attributes
        Regex("\\s([A-Za-z_:][\\w:.-]*)\\s*=").findAll(t).forEach { m ->
            val g = m.groups[1] ?: return@forEach
            set(ed, g.range.first, g.range.last + 1, if (darkTheme) DATTR else ATTR)
        }
        // attribute strings
        markAll(ed, t, Regex("=\\s*(\"[^\"]*\"|'[^']*')"), if (darkTheme) DSTRING else STRING)
        // script / style inner
        Regex("(?is)<script[^>]*>([\\s\\S]*?)</script>").findAll(t).forEach { m ->
            val inner = m.groups[1] ?: return@forEach
            colorJsRange(ed, t, inner.range.first, inner.range.last + 1, darkTheme)
        }
        Regex("(?is)<style[^>]*>([\\s\\S]*?)</style>").findAll(t).forEach { m ->
            val inner = m.groups[1] ?: return@forEach
            colorCssRange(ed, t, inner.range.first, inner.range.last + 1, darkTheme)
        }
    }

    private fun colorCss(ed: Editable, darkTheme: Boolean) = colorCssRange(ed, ed.toString(), 0, ed.length, darkTheme)

    private fun colorCssRange(ed: Editable, t: String, from: Int, to: Int, darkTheme: Boolean) {
        val slice = t.substring(from, to.coerceAtMost(t.length))
        // comments
        Regex("/\\*[\\s\\S]*?\\*/").findAll(slice).forEach { set(ed, from + it.range.first, from + it.range.last + 1, if (darkTheme) DCOMMENT else COMMENT) }
        // selectors / tags at start of rule
        Regex("(?m)^\\s*([.#]?[A-Za-z][\\w-]*)").findAll(slice).forEach {
            val g = it.groups[1] ?: return@forEach
            set(ed, from + g.range.first, from + g.range.last + 1, if (darkTheme) DTAG else TAG)
        }
        // properties
        Regex("([A-Za-z-]+)\\s*:").findAll(slice).forEach {
            val g = it.groups[1] ?: return@forEach
            set(ed, from + g.range.first, from + g.range.last + 1, if (darkTheme) DPROP else PROP)
        }
        // values strings
        Regex("\"[^\"]*\"|'[^']*'").findAll(slice).forEach { set(ed, from + it.range.first, from + it.range.last + 1, if (darkTheme) DSTRING else STRING) }
        // colors / numbers
        Regex("#[0-9A-Fa-f]{3,8}|\\b\\d+(\\.\\d+)?(px|em|rem|%|vh|vw|s|ms)?\\b").findAll(slice).forEach {
            set(ed, from + it.range.first, from + it.range.last + 1, if (darkTheme) DNUMBER else NUMBER)
        }
        // !important
        Regex("!important").findAll(slice).forEach { set(ed, from + it.range.first, from + it.range.last + 1, if (darkTheme) DKW2 else KW2) }
    }

    private fun colorJs(ed: Editable, darkTheme: Boolean) = colorJsRange(ed, ed.toString(), 0, ed.length, darkTheme)

    private fun colorJsRange(ed: Editable, t: String, from: Int, to: Int, darkTheme: Boolean) {
        val slice = t.substring(from, to.coerceAtMost(t.length))
        Regex("//[^\\n]*").findAll(slice).forEach { set(ed, from + it.range.first, from + it.range.last + 1, if (darkTheme) DCOMMENT else COMMENT) }
        Regex("/\\*[\\s\\S]*?\\*/").findAll(slice).forEach { set(ed, from + it.range.first, from + it.range.last + 1, if (darkTheme) DCOMMENT else COMMENT) }
        Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`").findAll(slice).forEach {
            set(ed, from + it.range.first, from + it.range.last + 1, if (darkTheme) DSTRING else STRING)
        }
        Regex("\\b\\d+(\\.\\d+)?\\b").findAll(slice).forEach { set(ed, from + it.range.first, from + it.range.last + 1, if (darkTheme) DNUMBER else NUMBER) }
        Regex("\\b[A-Za-z_\$][\\w\$]*\\b").findAll(slice).forEach { m ->
            val word = m.value
            val color = when {
                word in JS_KEYWORDS -> if (darkTheme) DKEYWORD else KEYWORD
                word in JS_TYPES -> if (darkTheme) DKW2 else KW2
                else -> null
            }
            if (color != null) set(ed, from + m.range.first, from + m.range.last + 1, color)
        }
        Regex("\\b([A-Za-z_\$][\\w\$]*)\\s*\\(").findAll(slice).forEach {
            val g = it.groups[1] ?: return@forEach
            if (g.value !in JS_KEYWORDS) set(ed, from + g.range.first, from + g.range.last + 1, if (darkTheme) DFUNC else FUNC)
        }
    }

    private fun colorJson(ed: Editable, darkTheme: Boolean) {
        val t = ed.toString()
        Regex("\"(?:\\\\.|[^\"\\\\])*\"").findAll(t).forEach { set(ed, it.range.first, it.range.last + 1, if (darkTheme) DSTRING else STRING) }
        Regex("\"(?:\\\\.|[^\"\\\\])*\"\\s*:").findAll(t).forEach { set(ed, it.range.first, it.range.last, if (darkTheme) DPROP else PROP) }
        Regex("\\b(true|false|null)\\b").findAll(t).forEach { set(ed, it.range.first, it.range.last + 1, if (darkTheme) DKEYWORD else KEYWORD) }
        Regex("-?\\b\\d+(\\.\\d+)?([eE][+-]?\\d+)?\\b").findAll(t).forEach { set(ed, it.range.first, it.range.last + 1, if (darkTheme) DNUMBER else NUMBER) }
    }

    private fun markAll(ed: Editable, t: String, rx: Regex, color: Int) {
        rx.findAll(t).forEach { set(ed, it.range.first, it.range.last + 1, color) }
    }

    private fun set(ed: Editable, start: Int, end: Int, color: Int) {
        val s = start.coerceIn(0, ed.length)
        val e = end.coerceIn(s, ed.length)
        if (e > s) ed.setSpan(ForegroundColorSpan(color), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
