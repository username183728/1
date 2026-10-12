package com.example.aidetest

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Kontrol bersama untuk halaman tool (monokrom, sasaran sentuh 44 dp).
 *
 *  - [SegmentedChoice]: pengganti Spinner/dropdown untuk pilihan ≤ 5 opsi. Semua opsi terlihat
 *    sekaligus, satu ketukan untuk memilih (tanpa popup dropdown). API sengaja mirip Spinner
 *    (`selectedItem`, `selectedItemPosition`, `setSelection`) agar penggantian di tool minim risiko.
 *  - [setToolState]: SATU cara menampilkan status proses/sukses/gagal di baris status tool.
 */
internal class SegmentedChoice(
    private val a: MainActivity,
    val items: List<String>,
    initial: Int = 0
) : LinearLayout(a) {
    var selectedItemPosition: Int = initial.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        private set
    val selectedItem: String get() = items.getOrElse(selectedItemPosition) { "" }
    /** Dipanggil setiap pilihan berubah karena ketukan pengguna atau [setSelection]. */
    var onSelected: ((Int) -> Unit)? = null
    private val cells = ArrayList<TextView>()
    private val c = EditorChrome(a)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = a.dp(3)
        setPadding(pad, pad, pad, pad)
        background = c.shape(c.surface2, 14, c.stroke)
        items.forEachIndexed { i, label ->
            val cell = TextView(a).apply {
                text = label
                textSize = 12.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                isClickable = true
                isFocusable = true
                setPadding(a.dp(6), 0, a.dp(6), 0)
                setOnClickListener { setSelection(i) }
            }
            cells.add(cell)
            addView(cell, LayoutParams(0, a.dp(38), 1f))
        }
        layoutParams = LayoutParams(-1, a.dp(44)).apply { bottomMargin = a.dp(Ds.SPACE_SM) }
        render()
    }

    fun setSelection(index: Int) {
        if (index !in items.indices) return
        val changed = index != selectedItemPosition
        selectedItemPosition = index
        render()
        if (changed) onSelected?.invoke(index)
    }

    private fun render() {
        cells.forEachIndexed { i, cell ->
            val active = i == selectedItemPosition
            cell.background = if (active) c.shape(c.activeFill, 11) else c.pressable(c.surface2, 11)
            cell.setTextColor(if (active) c.activeInk else c.ink)
            cell.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            cell.contentDescription = items[i] + if (active) ", dipilih" else ""
        }
    }
}

/** Buat [SegmentedChoice] (pemanggil menambahkannya ke layout). */
internal fun MainActivity.segmentedChoice(items: List<String>, initial: Int = 0): SegmentedChoice =
    SegmentedChoice(this, items, initial)

/**
 * Status tool yang konsisten di semua tool: simbol + teks, monokrom.
 * LOADING "◌", SUCCESS "✓", ERROR "✕" (tebal), WARNING "!", INFO/EMPTY "•".
 */
internal fun MainActivity.setToolState(target: TextView, state: Ds.State, message: String) {
    val glyph = when (state) {
        Ds.State.LOADING -> "◌"
        Ds.State.SUCCESS -> "✓"
        Ds.State.ERROR -> "✕"
        Ds.State.WARNING -> "!"
        Ds.State.INFO, Ds.State.EMPTY -> "•"
    }
    target.text = "$glyph  $message"
    target.setTextColor(if (state == Ds.State.LOADING || state == Ds.State.INFO || state == Ds.State.EMPTY) textMuted else textMain)
    target.typeface = if (state == Ds.State.ERROR) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    target.contentDescription = when (state) {
        Ds.State.LOADING -> "Memproses: $message"
        Ds.State.SUCCESS -> "Berhasil: $message"
        Ds.State.ERROR -> "Gagal: $message"
        Ds.State.WARNING -> "Perhatian: $message"
        else -> message
    }
}
