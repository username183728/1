package com.example.aidetest

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*

/**
 * Finance UI building blocks — upgraded for clearer hierarchy & semantic colors.
 */

internal fun MainActivity.fmtRupiah(v: Double): String {
    val neg = v < 0
    val s = kotlin.math.abs(kotlin.math.round(v).toLong()).toString()
    val sb = StringBuilder()
    for ((i, c) in s.reversed().withIndex()) {
        if (i > 0 && i % 3 == 0) sb.append('.')
        sb.append(c)
    }
    return (if (neg) "-" else "") + sb.reverse().toString()
}

internal fun MainActivity.financeMetricCard(title: String, amount: Double, icon: String, isIncome: Boolean? = null): View {
    val accent = when (isIncome) {
        true -> Ds.moneyIn(isDarkTheme)
        false -> Ds.moneyOut(isDarkTheme)
        null -> textMain
    }
    val soft = Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent))
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD))
        background = bg(panel2, Ds.RADIUS_LG, line)
    }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    top.addView(MdiIconView(this).apply {
        setIconName(icon)
        setIconSize(Ds.ICON_SM)
        setTextColor(accent)
        background = bg(soft, Ds.RADIUS_SM)
    }, LinearLayout.LayoutParams(dp(32), dp(32)))
    top.addView(TextView(this).apply {
        text = title
        textSize = Ds.TEXT_CAPTION
        setTextColor(textMuted)
        setPadding(dp(Ds.SPACE_SM), 0, 0, 0)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    box.addView(top)
    box.addView(TextView(this).apply {
        text = "Rp${fmtRupiah(amount)}"
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent)
        setPadding(0, dp(Ds.SPACE_SM), 0, 0)
    })
    return box
}

internal fun MainActivity.financeFlowRow(title: String, amount: Double, ratio: Double, icon: String, isIncome: Boolean): View {
    val accent = if (isIncome) Ds.moneyIn(isDarkTheme) else Ds.moneyOut(isDarkTheme)
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(4), dp(2), dp(8))
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    row.addView(MdiIconView(this).apply {
        setIconName(icon)
        setIconSize(Ds.ICON_SM)
        setTextColor(accent)
    }, LinearLayout.LayoutParams(dp(22), dp(22)))
    row.addView(TextView(this).apply {
        text = title
        textSize = Ds.TEXT_CAPTION_MIN
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textMain)
        setPadding(dp(6), 0, 0, 0)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(TextView(this).apply {
        text = "Rp${fmtRupiah(amount)}"
        textSize = Ds.TEXT_CAPTION_MIN
        setTextColor(accent)
        setTypeface(typeface, Typeface.BOLD)
    })
    wrap.addView(row)
    wrap.addView(uiProgressBar(ratio.toFloat(), accent, 7).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) }
    })
    return wrap
}

internal fun MainActivity.financeEmptyAction(title: String, detail: String, onClick: () -> Unit): View {
    return uiEmptyState(title, detail, "plus-circle-outline", "Tambah") { onClick() }
}

internal fun MainActivity.financeWalletRow(wallet: String, balance: Double): View {
    val positive = balance >= 0
    val accent = if (positive) Ds.moneyIn(isDarkTheme) else Ds.moneyOut(isDarkTheme)
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD))
        background = bg(panel2, Ds.RADIUS_MD, line)
    }
    card.addView(MdiIconView(this).apply {
        setIconName("wallet-outline")
        setIconSize(Ds.ICON_MD)
        setTextColor(textMain)
        background = bg(panel, Ds.RADIUS_SM)
    }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(Ds.SPACE_SM) })
    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(TextView(this).apply {
        text = wallet
        textSize = Ds.TEXT_SUBTITLE
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textMain)
    })
    texts.addView(TextView(this).apply {
        text = "Saldo"
        textSize = Ds.TEXT_CAPTION
        setTextColor(textMuted)
    })
    card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
    card.addView(TextView(this).apply {
        text = "Rp${fmtRupiah(balance)}"
        textSize = Ds.TEXT_SUBTITLE
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent)
    })
    card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(Ds.SPACE_SM) }
    return card
}

internal fun MainActivity.financeGoalRow(name: String, current: Double, target: Double): View {
    val pct = if (target > 0) (current / target).coerceIn(0.0, 1.0) else 0.0
    val done = pct >= 1.0
    val accent = if (done) Ds.moneyIn(isDarkTheme) else Ds.statusColor(Ds.State.INFO, isDarkTheme)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD))
        background = bg(panel2, Ds.RADIUS_MD, line)
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    row.addView(TextView(this).apply {
        text = name
        textSize = Ds.TEXT_SUBTITLE
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textMain)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(TextView(this).apply {
        text = "${(pct * 100).toInt()}%"
        textSize = Ds.TEXT_CAPTION
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent)
    })
    box.addView(row)
    box.addView(TextView(this).apply {
        text = "Rp${fmtRupiah(current)} / Rp${fmtRupiah(target)}"
        textSize = Ds.TEXT_CAPTION
        setTextColor(textMuted)
        setPadding(0, dp(3), 0, dp(6))
    })
    box.addView(uiProgressBar(pct.toFloat(), accent, 7))
    box.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(Ds.SPACE_SM) }
    return box
}

internal fun MainActivity.txsForInsight(db: FinanceDb): String {
    val t = db.listTx(20).firstOrNull { it.type == "keluar" && FinanceInsights.anomaly(it, db) } ?: return ""
    return "Perhatian: ${t.merchant} ${MoneyFormatter.format(t.amount)} jauh di atas rata-rata kategori ${t.category}."
}

internal fun MainActivity.financeSummaryBox(title: String, amount: Double, color: Int): View {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD))
        background = bg(panel2, Ds.RADIUS_LG)
    }
    box.addView(TextView(this).apply {
        text = title
        textSize = Ds.TEXT_CAPTION
        setTextColor(textMuted)
    })
    box.addView(TextView(this).apply {
        text = "Rp${fmtRupiah(amount)}"
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color)
        setPadding(0, dp(4), 0, 0)
    })
    return box
}

internal fun MainActivity.financeCategoryBar(cat: String, amount: Double, maxV: Double): View {
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(4), dp(2), dp(8))
    }
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    row.addView(TextView(this).apply {
        text = cat
        textSize = Ds.TEXT_CAPTION_MIN
        setTextColor(textMain)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(TextView(this).apply {
        text = "Rp${fmtRupiah(amount)}"
        textSize = Ds.TEXT_CAPTION_MIN
        setTextColor(textMuted)
    })
    wrap.addView(row)
    val ratio = (if (maxV > 0) amount / maxV else 0.02).coerceIn(0.02, 1.0).toFloat()
    wrap.addView(uiProgressBar(ratio, Ds.moneyNeutral(isDarkTheme), 8).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
    })
    return wrap
}

internal fun MainActivity.financeBudgetRow(cat: String, spent: Double, limit: Double): View {
    val over = spent >= limit
    val pct = if (limit > 0) (spent / limit).coerceIn(0.0, 1.2) else 0.0
    val accent = when {
        over -> Ds.statusColor(Ds.State.ERROR, isDarkTheme)
        pct >= 0.85 -> Ds.statusColor(Ds.State.WARNING, isDarkTheme)
        else -> Ds.statusColor(Ds.State.SUCCESS, isDarkTheme)
    }
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD))
        background = bg(panel2, Ds.RADIUS_MD, line)
    }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    top.addView(TextView(this).apply {
        text = cat
        textSize = Ds.TEXT_SUBTITLE
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textMain)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    if (over) {
        top.addView(uiStatusBadge("Terlampaui", Ds.State.ERROR))
    }
    wrap.addView(top)
    wrap.addView(TextView(this).apply {
        text = "Rp${fmtRupiah(spent)} / Rp${fmtRupiah(limit)}"
        textSize = Ds.TEXT_CAPTION_MIN
        setTextColor(textMuted)
        setPadding(0, dp(3), 0, dp(6))
    })
    wrap.addView(uiProgressBar(pct.toFloat().coerceAtMost(1f), accent, 7))
    wrap.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(Ds.SPACE_SM) }
    return wrap
}

internal fun MainActivity.financeTxRow(db: FinanceDb, t: FinanceTx): View {
    val whenText = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(t.timestamp))
    val isIn = t.type == "masuk"
    val sign = if (isIn) "+" else "−"
    val color = if (isIn) Ds.moneyIn(isDarkTheme) else Ds.moneyOut(isDarkTheme)
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD))
        background = bg(panel2, Ds.RADIUS_MD, line)
    }
    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(TextView(this).apply {
        text = t.merchant.ifBlank { t.category }
        textSize = Ds.TEXT_SUBTITLE
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textMain)
    })
    texts.addView(TextView(this).apply {
        text = "${t.category} • ${t.walletName} • $whenText"
        textSize = Ds.TEXT_CAPTION
        setTextColor(textMuted)
    })
    card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
    card.addView(TextView(this).apply {
        text = "$sign Rp${fmtRupiah(t.amount)}"
        setTextColor(color)
        textSize = Ds.TEXT_SUBTITLE
        setTypeface(typeface, Typeface.BOLD)
    })
    card.addView(TextView(this).apply {
        text = "✕"
        setTextColor(textMuted)
        textSize = 15f
        setPadding(dp(Ds.SPACE_MD), 0, 0, 0)
        contentDescription = "Hapus transaksi"
        setOnClickListener {
            db.deleteTx(t.id)
            MyToolsWidget.update(this@financeTxRow)
            toast("Transaksi dihapus")
            financeReaderTool()
        }
    })
    card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(Ds.SPACE_SM) }
    return card
}
