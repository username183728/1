package com.example.aidetest

import android.graphics.Color
import android.os.Build
import android.text.Editable
import android.text.Spannable
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.widget.EditText

/** Garis bawah bergelombang-sederhana: merah untuk error, kuning untuk peringatan. */
class DiagnosticSpan(private val severity: CodeDiagnostics.Severity) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        val col = if (severity == CodeDiagnostics.Severity.ERROR) {
            Color.rgb(229, 57, 53)
        } else {
            Color.rgb(245, 158, 11)
        }
        tp.isUnderlineText = true
        tp.bgColor = (col and 0x00FFFFFF) or 0x22000000
        if (Build.VERSION.SDK_INT >= 29) {
            tp.underlineColor = col
            tp.underlineThickness = 3f
        }
    }
}

/**
 * Menjalankan [CodeDiagnostics] pada EditText secara debounce dan menandai baris bermasalah.
 * Tidak mengubah teks; hanya menambah/menghapus [DiagnosticSpan].
 */
object EditorDiagnostics {
    private const val DEBOUNCE_MS = 500L
    private const val MAX_MARKS = 100

    var lastIssues: List<CodeDiagnostics.Issue> = emptyList()
        private set
    private var lastSupported = false

    fun analyzeNow(text: String, mode: String): List<CodeDiagnostics.Issue> {
        val issues = CodeDiagnostics.analyze(text, mode)
        lastIssues = issues
        lastSupported = CodeDiagnostics.supports(mode)
        return issues
    }

    /** Potongan teks untuk label status editor. Kosong bila mode tidak didukung. */
    fun summaryTail(): String {
        if (!lastSupported) return ""
        val errors = lastIssues.count { it.severity == CodeDiagnostics.Severity.ERROR }
        val warns = lastIssues.size - errors
        return when {
            errors == 0 && warns == 0 -> "  |  Tidak ada error"
            warns == 0 -> "  |  $errors error"
            errors == 0 -> "  |  $warns peringatan"
            else -> "  |  $errors error, $warns peringatan"
        }
    }

    fun refresh(edit: EditText, mode: String) {
        val ed = edit.text ?: return
        clear(ed)
        if (!CodeDiagnostics.supports(mode)) {
            lastIssues = emptyList()
            lastSupported = false
            return
        }
        mark(ed, analyzeNow(ed.toString(), mode))
    }

    fun clear(ed: Editable) {
        for (sp in ed.getSpans(0, ed.length, DiagnosticSpan::class.java)) ed.removeSpan(sp)
    }

    private fun mark(ed: Editable, issues: List<CodeDiagnostics.Issue>) {
        val len = ed.length
        if (len == 0) return
        for (issue in issues.take(MAX_MARKS)) {
            var s = issue.start
            var e = issue.end
            if (e <= s) e = s + 1
            if (s >= len) s = len - 1
            if (e > len) e = len
            if (e > s) ed.setSpan(DiagnosticSpan(issue.severity), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Pasang pemeriksaan otomatis. [modeProvider] dibaca setiap kali agar mengikuti
     * perubahan mode editor. [onChanged] dipanggil setelah hasil baru siap.
     */
    fun attach(edit: EditText, modeProvider: () -> String, onChanged: () -> Unit = {}) {
        val run = Runnable {
            refresh(edit, modeProvider())
            onChanged()
        }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                edit.removeCallbacks(run)
                edit.postDelayed(run, DEBOUNCE_MS)
            }
        })
        edit.post(run)
    }
}
