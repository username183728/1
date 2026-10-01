package com.example.aidetest

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Finance main screens.
 */

internal fun MainActivity.financeDashboardTool() {
    clearPage("Finance Dashboard")
    val db = FinanceDb(this)
    db.processDueRecurring()
    content.addView(label("Finance Dashboard", 24f, true))
    content.addView(subLabel("Ringkasan cepat tanpa membaca notifikasi. Semua data keuangan berasal dari input yang disimpan lokal.", 12f))

    val (from, to) = db.monthRange()
    val income = db.totalByType("masuk", from, to)
    val expense = db.totalByType("keluar", from, to)
    val net = income - expense
    val summary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    summary.addView(financeSummaryBox("Masuk", income, Ds.moneyIn(isDarkTheme)), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) })
    summary.addView(financeSummaryBox("Keluar", expense, Ds.moneyOut(isDarkTheme)), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5) })
    content.addView(summary)
    content.addView(label("Saldo bersih bulan ini: ${MoneyFormatter.format(net)}", 15f, true).apply { setPadding(dp(2), dp(12), dp(2), dp(4)) })

    sectionTitle("Insight lokal")
    val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
    val daily = expense / day
    val cal = Calendar.getInstance()
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val forecast = daily * daysInMonth
    content.addView(settingRow("Laju pengeluaran", MoneyFormatter.format(daily) + " / hari", "Perkiraan sederhana berdasarkan pengeluaran bulan berjalan."))
    content.addView(settingRow("Proyeksi bulan", MoneyFormatter.format(forecast), "Bukan prediksi pasti; hanya extrapolasi rata-rata harian."))

    val categories = db.sumByCategory("keluar", from, to)
    if (categories.isNotEmpty()) {
        sectionTitle("Kategori terbesar")
        val top = categories.first()
        content.addView(settingRow(top.first, MoneyFormatter.format(top.second), "Kategori dengan pengeluaran terbesar bulan ini."))
        val max = categories.maxOf { it.second }
        categories.take(6).forEach { (cat, amount) -> content.addView(financeCategoryBar(cat, amount, max)) }
    }

    sectionTitle("Anggaran")
    val budgets = db.getBudgets()
    if (budgets.isEmpty()) {
        content.addView(subLabel("Belum ada anggaran. Gunakan + → Atur anggaran.", 12f))
    } else {
        budgets.forEach { (cat, limit) ->
            val spent = categories.find { it.first == cat }?.second ?: 0.0
            val left = (limit - spent).coerceAtLeast(0.0)
            val ratio = if (limit > 0) (spent / limit).coerceIn(0.0, 1.5) else 0.0
            val daysLeft = FinanceInsights.budgetForecastDaysLeft(db, cat, limit)
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = bg(panel2, 16, line) }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(label(cat, 14f, true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label("${MoneyFormatter.format(spent)} / ${MoneyFormatter.format(limit)}", 12f, true).apply { setTextColor(textMuted) })
            box.addView(row)
            val track = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = (ratio * 1000.0).toInt()
                progressTintList = ColorStateList.valueOf(if (ratio >= 1.0) Color.rgb(190, 70, 70) else if (ratio >= 0.8) Color.rgb(190, 150, 55) else Color.rgb(85, 150, 105))
                progressBackgroundTintList = ColorStateList.valueOf(if (isDarkTheme) Color.rgb(55,55,60) else Color.rgb(225,228,232))
            }
            box.addView(track, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8); bottomMargin = dp(5) })
            box.addView(subLabel("Sisa ${MoneyFormatter.format(left)}${if (daysLeft != null) " • estimasi ${daysLeft} hari" else ""}", 10.5f))
            box.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
            content.addView(box)
        }
    }

    sectionTitle("Anomali")
    val anomaly = db.listTx(100).firstOrNull { FinanceInsights.anomaly(it, db) }
    content.addView(subLabel(anomaly?.let { "Pengeluaran tinggi terdeteksi: ${it.merchant.ifBlank { it.category }} • ${MoneyFormatter.format(it.amount)}" } ?: "Tidak ada anomali sederhana yang terdeteksi.", 12f))

    sectionTitle("Target tabungan")
    val goals = db.goals()
    if (goals.isEmpty()) content.addView(subLabel("Belum ada target tabungan.", 12f))
    goals.take(5).forEach { g ->
        val name = g[1] as String
        val target = g[2] as Double
        val current = db.goalProgress(name)
        val pct = if (target > 0) (current / target * 100.0).coerceIn(0.0, 100.0) else 0.0
        content.addView(settingRow(name, "${MoneyFormatter.format(current)} / ${MoneyFormatter.format(target)}", "Progress ${pct.toInt()}%"))
    }

    sectionTitle("Laporan")
    content.addView(button("Buat & Bagikan PDF Bulanan") {
        shareFileAsync("PDF gagal") { FinanceReport.createPdf(this, db) }
    })
    content.addView(button("Buka Pengelola Keuangan") { financeReaderTool() })
}

// ===================== 2.14 SECURITY CENTER =====================

// ===================== Pengelola Keuangan Berbasis Pembaca Notifikasi =====================


internal fun MainActivity.financeReaderTool(){
    clearPage("Pengelola Keuangan")
    val db=FinanceDb(this)
    db.processDueRecurring()

    val (from,to)=db.monthRange()
    val income=db.totalByType("masuk",from,to)
    val expense=db.totalByType("keluar",from,to)
    val net=income-expense
    val byCat=db.sumByCategory("keluar",from,to)
    val budgets=db.getBudgets()
    val txs=db.searchTx(financeSearchQuery,financeCategoryFilter,financeWalletFilter).take(5)
    val monthLabel=SimpleDateFormat("MMMM yyyy",Locale("id","ID")).format(Date())

    // Compact dashboard header: fewer competing elements and clear hierarchy.
    content.addView(label("Keuangan",22f,true))
    content.addView(subLabel("Ringkasan sederhana • data tersimpan lokal",12f))

    val period=TextView(this).apply{
        text=monthLabel.replaceFirstChar{it.uppercase()}
        textSize=12f
        setTextColor(textMain)
        setTypeface(typeface,android.graphics.Typeface.BOLD)
        gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(12),0,dp(12),0)
        background=bg(panel2,12,line)
    }
    content.addView(period,LinearLayout.LayoutParams(-1,dp(42)).apply{topMargin=dp(10);bottomMargin=dp(10)})

    // Hero balance card.
    val hero=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL
        setPadding(dp(16),dp(14),dp(16),dp(14))
        background=bg(panel,18,line)
    }
    hero.addView(subLabel("Saldo bersih bulan ini",12f))
    hero.addView(label("Rp${fmtRupiah(net)}",24f,true).apply{setPadding(0,dp(3),0,0); setTextColor(if(net>=0) Ds.moneyIn(isDarkTheme) else Ds.moneyOut(isDarkTheme))})
    hero.addView(subLabel(if(net>=0) "Arus kas masih positif" else "Pengeluaran lebih besar dari pemasukan",11f))
    content.addView(hero,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})

    val metrics=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
    metrics.addView(financeMetricCard("Pemasukan",income,"arrow-up",true),LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=dp(5)})
    metrics.addView(financeMetricCard("Pengeluaran",expense,"arrow-down",false),LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(5)})
    content.addView(metrics,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})

    // Cash-flow comparison without a heavy chart; optimized for narrow phone screens.
    sectionTitle("Arus Kas")
    val totalFlow=(income+expense).coerceAtLeast(1.0)
    val incomeRatio=(income/totalFlow).coerceIn(0.0,1.0)
    content.addView(financeFlowRow("Pemasukan",income,incomeRatio,"arrow-up",true))
    content.addView(financeFlowRow("Pengeluaran",expense,(expense/totalFlow).coerceIn(0.0,1.0),"arrow-down",false))

    sectionTitle("Pengeluaran per Kategori","Semua"){showFinanceFilterDialog(db)}
    if(byCat.isEmpty()) {
        content.addView(subLabel("Belum ada pengeluaran bulan ini.",12f))
    } else {
        val maxV=byCat.maxOf{it.second}
        byCat.take(4).forEach{(cat,amt)->content.addView(financeCategoryBar(cat,amt,maxV))}
        if(byCat.size>4) content.addView(subLabel("+ ${byCat.size-4} kategori lainnya",11f))
    }

    sectionTitle("Anggaran Bulan Ini","Kelola"){showBudgetDialog(db)}
    if(budgets.isEmpty()) {
        content.addView(financeEmptyAction("Belum ada anggaran","Atur batas pengeluaran per kategori"){showBudgetDialog(db)})
    } else {
        budgets.take(4).forEach{(cat,limit)->content.addView(financeBudgetRow(cat,byCat.find{it.first==cat}?.second?:0.0,limit))}
    }

    sectionTitle("Target Tabungan","Kelola"){showSavingsGoalDialog(db)}
    val goals=db.goals()
    if(goals.isEmpty()) {
        content.addView(financeEmptyAction("Belum ada target","Buat target tabungan agar progres mudah dipantau"){showSavingsGoalDialog(db)})
    } else {
        goals.take(2).forEach{g->
            val current=db.goalProgress(g[1] as String)
            val target=g[2] as Double
            content.addView(financeGoalRow(g[1] as String,current,target))
        }
    }

    sectionTitle("Dompet & Rekening","Kelola"){showWalletDialog(db)}
    db.wallets().take(4).forEach{w->
        val bal=db.totalByTypeForWallet("masuk",w)-db.totalByTypeForWallet("keluar",w)
        content.addView(financeWalletRow(w,bal))
    }

    sectionTitle("Transaksi Terbaru","Semua"){showFinanceFilterDialog(db)}
    if(txs.isEmpty()) {
        content.addView(financeEmptyAction("Belum ada transaksi","Tekan + untuk menambahkan pemasukan atau pengeluaran"){showAddTxDialog(db)})
    } else {
        txs.forEach{content.addView(financeTxRow(db,it))}
    }

    val anomalyHint=txsForInsight(db)
    if(anomalyHint.isNotBlank()) {
        sectionTitle("Insight")
        content.addView(subLabel(anomalyHint,11f))
    }
    content.addView(subLabel("Gunakan + untuk aksi cepat. MyTools tidak membaca notifikasi aplikasi lain.",11f).apply{setPadding(dp(2),dp(10),dp(2),dp(18))})
}

