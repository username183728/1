package com.example.aidetest

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Soft keyboard accessory bar for HTML/CSS/JS editor.
 * Quick symbols, indent/unindent, undo/redo, format, preview, emmet, code assist.
 */
object EditorKeyboardBar {

    fun build(activity: MainActivity, edit: EditText): View {
        val dark = activity.isDarkTheme
        val bgColor = if (dark) Color.rgb(28, 28, 31) else Color.rgb(245, 246, 248)
        val btnBg = if (dark) Color.rgb(45, 45, 50) else Color.WHITE
        val textColor = if (dark) Color.rgb(230, 230, 230) else Color.rgb(30, 30, 30)
        val accent = if (dark) Color.rgb(100, 180, 255) else Color.rgb(0, 100, 220)
        val border = if (dark) Color.rgb(60, 60, 65) else Color.rgb(210, 212, 218)

        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(bgColor)
            setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
            elevation = activity.dp(4).toFloat()
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun makeBtnBg(): GradientDrawable = GradientDrawable().apply {
            setColor(btnBg)
            cornerRadius = activity.dp(8).toFloat()
            setStroke(activity.dp(1), border)
        }

        fun btn(label: String, wide: Boolean = false, accented: Boolean = false, action: () -> Unit): TextView {
            return TextView(activity).apply {
                text = label
                textSize = if (wide) 12f else 13f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setTextColor(if (accented) accent else textColor)
                background = makeBtnBg()
                setPadding(activity.dp(10), activity.dp(7), activity.dp(10), activity.dp(7))
                minWidth = activity.dp(if (wide) 48 else 34)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    action()
                    edit.requestFocus()
                }
                layoutParams = LinearLayout.LayoutParams(-2, activity.dp(34)).apply {
                    leftMargin = activity.dp(2)
                    rightMargin = activity.dp(2)
                }
            }
        }

        val symbols = listOf("{", "}", "(", ")", "[", "]", "<", ">", ";", ":", "=", "\"", "'", "/", "`")
        symbols.forEach { s ->
            row.addView(btn(s) {
                val start = edit.selectionStart.coerceAtLeast(0)
                val end = edit.selectionEnd.coerceAtLeast(0)
                val a = minOf(start, end)
                val b = maxOf(start, end)
                edit.text?.replace(a, b, s)
                edit.setSelection(a + s.length)
            })
        }

        row.addView(View(activity).apply {
            setBackgroundColor(border)
            layoutParams = LinearLayout.LayoutParams(activity.dp(1), activity.dp(22)).apply {
                leftMargin = activity.dp(5)
                rightMargin = activity.dp(5)
            }
        })

        row.addView(btn("Tab", wide = true) { insertIndent(edit, indent = true) })
        row.addView(btn("⇤", wide = true) { insertIndent(edit, indent = false) })
        row.addView(btn("↩", wide = true) { edit.undoSafe() })
        row.addView(btn("↪", wide = true) { edit.redoSafe() })
        row.addView(btn("Fmt", wide = true, accented = true) { activity.formatEditorDocument() })
        row.addView(btn("▶", wide = true, accented = true) { activity.previewUnifiedEditor(edit) })
        row.addView(btn("Emmet", wide = true) { activity.applyEditorEmmet() })
        row.addView(btn("Assist", wide = true, accented = true) {
            EditorIdeExtras.showSuggestionPopup(activity, edit, activity.editorMode)
        })

        scroll.addView(row)
        return scroll
    }

    private fun insertIndent(edit: EditText, indent: Boolean) {
        val text = edit.text ?: return
        val start = edit.selectionStart.coerceIn(0, text.length)
        val end = edit.selectionEnd.coerceIn(0, text.length)
        if (start == end) {
            if (indent) {
                text.insert(start, "  ")
                edit.setSelection(start + 2)
            } else {
                val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
                val prefix = text.subSequence(lineStart, start).toString()
                val remove = when {
                    prefix.endsWith("  ") -> 2
                    prefix.endsWith(" ") -> 1
                    else -> 0
                }
                if (remove > 0) {
                    text.delete(start - remove, start)
                    edit.setSelection(start - remove)
                }
            }
        } else {
            val selStart = minOf(start, end)
            val selEnd = maxOf(start, end)
            val full = text.toString()
            val lineStart = full.lastIndexOf('\n', (selStart - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val lines = full.substring(lineStart, selEnd).split('\n')
            val newLines = lines.map { line ->
                if (indent) "  $line"
                else when {
                    line.startsWith("  ") -> line.drop(2)
                    line.startsWith(" ") -> line.drop(1)
                    else -> line
                }
            }
            val replacement = newLines.joinToString("\n")
            text.replace(lineStart, selEnd, replacement)
            edit.setSelection(lineStart, lineStart + replacement.length)
        }
    }
}
