package com.example.aidetest

import android.graphics.Color
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.widget.EditText

/**
 * Highlight matching bracket pairs when the cursor is on or next to a bracket.
 */
object EditorBracketMatch {

    private val pairs = mapOf('(' to ')', '[' to ']', '{' to '}', '<' to '>')
    private val openers = pairs.keys
    private val closers = pairs.values.toSet()

    /** Marker span so we only remove our own highlights. */
    private class BracketSpan(color: Int) : BackgroundColorSpan(color)

    fun attach(edit: EditText, darkTheme: Boolean) {
        val highlightColor = if (darkTheme)
            Color.argb(100, 100, 180, 255)
        else
            Color.argb(90, 0, 120, 255)

        fun refresh() {
            apply(edit, highlightColor)
        }

        edit.setOnClickListener { refresh() }
        // chain with existing key listener carefully: only post refresh
        val prev = edit.getTag(0x4B4D4154) // unused
        edit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) refresh()
        }
        // selection change via text watcher light
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                edit.post { refresh() }
            }
        })
        edit.post { refresh() }
    }

    private fun apply(edit: EditText, color: Int) {
        val text = edit.text ?: return
        // remove only our spans
        text.getSpans(0, text.length, BracketSpan::class.java).forEach { text.removeSpan(it) }

        val pos = edit.selectionStart.coerceIn(0, text.length)
        if (text.isEmpty()) return

        val candidates = listOfNotNull(
            if (pos < text.length) pos to text[pos] else null,
            if (pos > 0) (pos - 1) to text[pos - 1] else null
        )

        for ((idx, ch) in candidates) {
            when {
                ch in openers -> {
                    val match = findClosing(text, idx, ch, pairs[ch]!!)
                    if (match >= 0) {
                        paint(text, idx, color)
                        paint(text, match, color)
                        return
                    }
                }
                ch in closers -> {
                    val open = pairs.entries.firstOrNull { it.value == ch }?.key ?: continue
                    val match = findOpening(text, idx, open, ch)
                    if (match >= 0) {
                        paint(text, match, color)
                        paint(text, idx, color)
                        return
                    }
                }
            }
        }
    }

    private fun paint(text: Spannable, index: Int, color: Int) {
        if (index < 0 || index >= text.length) return
        text.setSpan(
            BracketSpan(color),
            index, index + 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun findClosing(text: CharSequence, from: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = from
        // skip string/comment roughly: simple scan
        while (i < text.length) {
            val c = text[i]
            when (c) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun findOpening(text: CharSequence, from: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = from
        while (i >= 0) {
            val c = text[i]
            when (c) {
                close -> depth++
                open -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i--
        }
        return -1
    }
}
