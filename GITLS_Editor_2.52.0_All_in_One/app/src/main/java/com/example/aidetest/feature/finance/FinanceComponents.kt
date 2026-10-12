package com.example.aidetest

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Komponen UI keuangan (monokrom). Sasaran sentuh minimal 44dp sesuai pedoman EditorChrome.
 */

internal val financeLocale: Locale = Locale("id", "ID")

internal fun finDateText(ts: Long, pattern: String = "d MMM yyyy"): String = SimpleDateFormat(pattern, financeLocale).format(Date(ts))

/** Kartu dasar (panel2 + garis). */
internal fun MainActivity.finCard(padDp: Int = Ds.SPACE_MD): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    background = bg(panel2, Ds.RADIUS_MD, line)
}

internal fun MainActivity.finAddCard(card: View, bottomDp: Int = Ds.SPACE_SM) {
    content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottomDp) })
}

internal fun MainActivity.finText(value: String, size: Float, bold: Boolean = false, muted: Boolean = false): TextView = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(if (muted) textMuted else textMain)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

/** Ikon tombol 44×44dp. */
internal fun MainActivity.finIconButton(icon: String, desc: String, onClick: () -> Unit): View = MdiIconView(this).apply {
    setIconName(icon)
    setIconSize(Ds.ICON_MD)
    setTextColor(textMain)
    gravity = Gravity.CENTER
    contentDescription = desc
    background = rippleBg(Color.TRANSPARENT, Ds.RADIUS_SM)
    isClickable = true
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
}

/** Segmented control (tab) dengan tinggi 44dp. */
internal fun MainActivity.finSegmented(names: List<String>, active: Int, onSelect: (Int) -> Unit): View {
    val activeFill = if (isDarkTheme) Color.rgb(245, 245, 247) else Color.rgb(18, 18, 20)
    val activeInk = if (isDarkTheme) Color.rgb(18, 18, 20) else Color.WHITE
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(3), dp(3), dp(3), dp(3))
        background = bg(panel2, 14, line)
    }
    names.forEachIndexed { i, n ->
        val on = i == active
        row.addView(TextView(this).apply {
            text = n
            textSize = 12.5f
            gravity = Gravity.CENTER
            maxLines = 1
            setTypeface(typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (on) activeInk else textMuted)
            background = if (on) rippleBg(activeFill, 11) else rippleBg(Color.TRANSPARENT, 11)
            isClickable = true
            contentDescription = "Tab $n" + if (on) ", aktif" else ""
            setOnClickListener { if (!on) onSelect(i) }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
    }
    return row
}

/** Baris peringatan monokrom: ikon + teks tebal + garis tepi tinta. */
internal fun MainActivity.finWarning(textValue: String, icon: String = "alert-outline", onClick: (() -> Unit)? = null): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        setPadding(dp(Ds.SPACE_MD), dp(Ds.SPACE_SM), dp(Ds.SPACE_MD), dp(Ds.SPACE_SM))
        background = if (onClick != null) rippleBg(panel, Ds.RADIUS_SM, textMain) else bg(panel, Ds.RADIUS_SM, textMain)
        if (onClick != null) setOnClickListener { onClick() }
    }
    row.addView(MdiIconView(this).apply { setIconName(icon); setIconSize(Ds.ICON_SM); setTextColor(textMain) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(8) })
    row.addView(finText(textValue, 12.5f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
    return row
}

internal fun MainActivity.finProgress(permille: Int): View =
    uiProgressBar((permille.coerceIn(0, 1000) / 1000f), textMain, 7)

/** Tile metrik kecil: judul kecil + nilai. */
internal fun MainActivity.finMetric(title: String, value: String, caption: String? = null, onClick: (() -> Unit)? = null): View {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = if (onClick != null) rippleBg(panel, Ds.RADIUS_SM, line) else bg(panel, Ds.RADIUS_SM, line)
        minimumHeight = dp(44)
        if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
    }
    box.addView(finText(title, 11f, muted = true))
    box.addView(finText(value, 13.5f, bold = true).apply { setPadding(0, dp(3), 0, 0); maxLines = 1 })
    if (caption != null) box.addView(finText(caption, 10.5f, muted = true).apply { setPadding(0, dp(2), 0, 0) })
    contentDescriptionFor(box, "$title $value ${caption.orEmpty()}")
    return box
}

private fun contentDescriptionFor(v: View, d: String) { v.contentDescription = d.trim() }

internal fun MainActivity.finRow3(a: View, b: View, c: View?): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    row.addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
    row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4); if (c != null) rightMargin = dp(4) })
    if (c != null) row.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
    return row
}

/** Baris daftar generik yang dapat diketuk: ikon · judul/subjudul · nilai kanan. */
internal fun MainActivity.finListRow(icon: String, title: String, subtitle: String, value: String, valueCaption: String? = null,
                                    onLongClick: (() -> Unit)? = null, onClick: (() -> Unit)?): LinearLayout {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(Ds.SPACE_MD), dp(10), dp(Ds.SPACE_MD), dp(10))
        background = rippleBg(panel2, Ds.RADIUS_MD, line)
        if (onClick != null) setOnClickListener { onClick() }
        if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
    }
    card.addView(MdiIconView(this).apply {
        setIconName(icon); setIconSize(Ds.ICON_MD); setTextColor(textMain); gravity = Gravity.CENTER
        background = bg(panel, Ds.RADIUS_SM)
    }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) })
    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(finText(title, Ds.TEXT_SUBTITLE + 0.5f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    texts.addView(finText(subtitle, Ds.TEXT_CAPTION, muted = true).apply { maxLines = 2; setPadding(0, dp(2), 0, 0) })
    card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
    val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
    right.addView(finText(value, Ds.TEXT_SUBTITLE + 0.5f, bold = true).apply { gravity = Gravity.END })
    if (valueCaption != null) right.addView(finText(valueCaption, 10.5f, muted = true).apply { gravity = Gravity.END; setPadding(0, dp(2), 0, 0) })
    card.addView(right, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
    card.contentDescription = "$title, $subtitle, $value ${valueCaption.orEmpty()}".trim()
    card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
    return card
}

internal fun MainActivity.financeTxRow(db: FinanceDb, t: FinanceTx): View {
    val isIn = t.type == FinType.IN
    val sign = if (isIn) "+" else "−"
    val title = t.merchant.ifBlank { t.category }
    val sub = listOf(t.category, t.method, finDateText(t.timestamp, "d MMM yyyy, HH:mm")).joinToString(" · ") +
        (if (t.goalId != null) " · tabungan" else "")
    return finListRow(if (isIn) "cash-plus" else "cash-minus", title, sub, sign + FinMoney.rp(t.amount), null,
        onLongClick = { confirmDeleteTx(db, t) }) { showTxEditor(db, t, null) }
}

internal fun MainActivity.financeCategoryBar(cat: String, amount: Long, maxV: Long, caption: String? = null): View {
    val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(4), dp(2), dp(8)) }
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    row.addView(finText(cat, Ds.TEXT_CAPTION_MIN), LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(finText(FinMoney.rp(amount) + (caption?.let { " · $it" } ?: ""), Ds.TEXT_CAPTION_MIN, muted = true))
    wrap.addView(row)
    wrap.addView(uiProgressBar(if (maxV > 0) (amount.toDouble() / maxV.toDouble()).toFloat() else 0f, textMain, 7).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
    })
    return wrap
}

/** Kartu penggunaan anggaran dengan progres & peringatan teks. */
internal fun MainActivity.financeBudgetCard(title: String, u: FinBudgetUsage, onClick: () -> Unit): View {
    val card = finCard().apply { background = rippleBg(panel2, Ds.RADIUS_MD, if (u.level == FinBudgetLevel.OVER) textMain else line); setOnClickListener { onClick() } }
    val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    top.addView(finText(title, Ds.TEXT_SUBTITLE + 0.5f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
    top.addView(finText(FinMoney.percent(u.permille), Ds.TEXT_CAPTION_MIN, bold = true))
    card.addView(top)
    card.addView(finText("${FinMoney.rp(u.spent)} dari ${FinMoney.rp(u.limit)}", Ds.TEXT_CAPTION, muted = true).apply { setPadding(0, dp(3), 0, dp(6)) })
    card.addView(finProgress(u.permille))
    val status = when (u.level) {
        FinBudgetLevel.OVER -> "Melebihi anggaran ${FinMoney.rp(-u.remaining)}"
        FinBudgetLevel.WARN -> "Hampir habis · sisa ${FinMoney.rp(u.remaining)}"
        FinBudgetLevel.OK -> "Sisa ${FinMoney.rp(u.remaining)}"
    }
    card.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0)
        if (u.level != FinBudgetLevel.OK) addView(MdiIconView(this@financeBudgetCard).apply { setIconName("alert-outline"); setIconSize(14f); setTextColor(textMain) }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(4) })
        addView(finText(status, Ds.TEXT_CAPTION_MIN, bold = u.level != FinBudgetLevel.OK, muted = u.level == FinBudgetLevel.OK))
    })
    card.contentDescription = "$title, ${FinMoney.percent(u.permille)} terpakai. $status"
    card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(Ds.SPACE_SM) }
    return card
}

internal fun MainActivity.finSpinner(items: List<String>, selected: String? = null): Spinner = Spinner(this).apply {
    adapter = ArrayAdapter(hostActivity, android.R.layout.simple_spinner_dropdown_item, items.toTypedArray())
    if (selected != null) setSelection(items.indexOf(selected).coerceAtLeast(0))
    minimumHeight = dp(48)
}

internal fun MainActivity.finCaption(t: String): TextView = TextView(this).apply {
    text = t; textSize = 12f; setTextColor(textMuted); setPadding(0, dp(10), 0, dp(4))
}

internal fun MainActivity.finForm(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(18), dp(6), dp(18), dp(4))
}

/** Kelompokkan digit dengan titik ribuan: 1000000 -> 1.000.000 */
internal fun groupThousands(digits: String): String {
    val sb = StringBuilder()
    for ((i, ch) in digits.reversed().withIndex()) {
        if (i > 0 && i % 3 == 0) sb.append('.')
        sb.append(ch)
    }
    return sb.reverse().toString()
}

/** Titik ribuan otomatis saat mengetik nominal. */
internal fun EditText.attachMoneyFormat() {
    var busy = false
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            if (busy || s == null) return
            val raw = s.toString()
            var digits = raw.filter { it.isDigit() }.take(13)
            if (digits.isNotEmpty()) digits = digits.trimStart('0').ifEmpty { "0" }
            val formatted = groupThousands(digits)
            if (formatted != raw) {
                busy = true
                s.replace(0, s.length, formatted)
                busy = false
            }
            setSelection(text.length)
        }
    })
}

/** Nominal (Long) dari field yang diformat titik ribuan; null bila kosong/tidak valid. */
internal fun EditText.moneyValue(): Long? = FinMoney.parse(text.toString())

internal fun MainActivity.moneyEdit(hint: String, initial: Long? = null): EditText =
    edit(hint).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        attachMoneyFormat()
        if (initial != null && initial > 0) setText(initial.toString())
    }
