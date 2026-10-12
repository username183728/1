package com.example.aidetest

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Layar Keuangan: satu halaman dengan tab segmented (bukan gulir panjang).
 * 0 Ringkas · 1 Riwayat · 2 Grafik · 3 Anggaran · 4 Rencana (Tabungan / Tagihan / Utang).
 */

internal const val FINANCE_TAB_HOME = 0
internal const val FINANCE_TAB_HISTORY = 1
internal const val FINANCE_TAB_CHARTS = 2
internal const val FINANCE_TAB_BUDGET = 3
internal const val FINANCE_TAB_PLAN = 4

/** Status UI keuangan (bertahan selama aktivitas hidup). */
internal class FinanceUiState {
    var month: FinMonth = FinMonth.of(System.currentTimeMillis())
    /** 0 Tabungan, 1 Tagihan, 2 Utang/Piutang. */
    var planTab: Int = 0
    var query: FinanceTxQuery = FinanceTxQuery()
    /** 0 semua waktu, 1 bulan terpilih, 2 rentang kustom (query.from/to). */
    var rangeMode: Int = 0
}

internal fun MainActivity.financeDashboardTool() {
    // Entry point lama "Finance Dashboard" kini membuka tab Ringkas yang sama.
    financeReaderTool(FINANCE_TAB_HOME)
}

/** Dipanggil dari intent widget/quick tile/shortcut/notifikasi tagihan. */
internal fun MainActivity.openFinanceFromIntent(intent: Intent?) {
    val tab = intent?.getIntExtra("finance_tab", FINANCE_TAB_HOME) ?: FINANCE_TAB_HOME
    if (tab == FINANCE_TAB_PLAN) { financeUi.planTab = 1; financeUi.month = FinMonth.of(System.currentTimeMillis()) }
    financeReaderTool(tab.coerceIn(0, 4))
}

/** Render ulang tanpa kehilangan posisi gulir (mis. setelah edit/hapus). */
internal fun MainActivity.financeRefresh() {
    val y = runCatching { scroll.scrollY }.getOrDefault(0)
    financeReaderTool()
    runCatching { scroll.post { scroll.scrollTo(0, y) } }
    MyToolsWidget.update(this)
}

internal fun MainActivity.financeReaderTool(tab: Int? = null) {
    if (tab != null) financeTab = tab.coerceIn(0, 4)
    clearPage("Pengelola Keuangan")
    val db = FinanceDb(this)
    runCatching { db.processDueRecurring() }
    content.addView(finSegmented(listOf("Ringkas", "Riwayat", "Grafik", "Anggaran", "Rencana"), financeTab) { financeReaderTool(it) },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(8) })
    val showMonth = financeTab == FINANCE_TAB_HOME || financeTab == FINANCE_TAB_CHARTS || financeTab == FINANCE_TAB_BUDGET ||
        (financeTab == FINANCE_TAB_PLAN && financeUi.planTab == 1)
    if (showMonth) content.addView(financeMonthBar(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    runCatching {
        when (financeTab) {
            FINANCE_TAB_HISTORY -> financeTabHistory(db)
            FINANCE_TAB_CHARTS -> financeTabCharts(db)
            FINANCE_TAB_BUDGET -> financeTabBudget(db)
            FINANCE_TAB_PLAN -> financeTabPlan(db)
            else -> financeTabHome(db)
        }
    }.onFailure {
        android.util.Log.w("GITLS", "Finance render failed", it)
        content.addView(uiResultCard("Gagal memuat data", it.message ?: it.javaClass.simpleName, Ds.State.ERROR))
    }
    content.addView(View(this), LinearLayout.LayoutParams(-1, dp(16)))
}

/** Pengalih bulan: ‹ Oktober 2026 › (ketuk label = kembali ke bulan ini). */
private fun MainActivity.financeMonthBar(): View {
    val now = FinMonth.of(System.currentTimeMillis())
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    row.addView(finIconButton("chevron-left", "Bulan sebelumnya") { financeUi.month = financeUi.month.plus(-1); financeReaderTool() })
    row.addView(TextView(this).apply {
        text = financeUi.month.label + if (financeUi.month == now) "" else "  ·  ke bulan ini"
        textSize = 14.5f
        gravity = Gravity.CENTER
        setTextColor(textMain)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        minHeight = dp(44)
        background = rippleBg(android.graphics.Color.TRANSPARENT, Ds.RADIUS_SM)
        contentDescription = "Bulan ${financeUi.month.label}. Ketuk untuk kembali ke bulan ini"
        setOnClickListener { if (financeUi.month != now) { financeUi.month = now; financeReaderTool() } }
    }, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(finIconButton("chevron-right", "Bulan berikutnya") { financeUi.month = financeUi.month.plus(1); financeReaderTool() })
    return row
}

// ---------------------------------------------------------------- Ringkas

private fun MainActivity.financeTabHome(db: FinanceDb) {
    val m = financeUi.month
    val from = m.startMillis(); val to = m.endMillis()
    val prev = m.plus(-1)
    val entries = db.entries(prev.startMillis(), to)
    val cur = FinCalc.summarize(entries, from, to)
    val last = FinCalc.summarize(entries, prev.startMillis(), from)
    val cats = FinCalc.categoryBreakdown(entries, FinType.OUT, from, to)
    val spentByCat = cats.associate { it.category to it.amount }
    val budget = FinCalc.monthBudget(db.totalBudget(), db.getBudgets(), spentByCat, cur.expense)
    val isCurrent = m == FinMonth.of(System.currentTimeMillis())

    val hero = finCard(14)
    hero.addView(finText(if (isCurrent) "Saldo saat ini" else "Saldo akhir ${m.label}", 12f, muted = true))
    val balance = if (isCurrent) db.netWorth() else db.balanceUntil(to)
    hero.addView(finText(FinMoney.rp(balance), 26f, bold = true).apply { setPadding(0, dp(2), 0, dp(10)) })
    val budgetValue = when {
        budget == null -> "Belum diatur"
        budget.remaining < 0 -> "−" + FinMoney.rp(-budget.remaining)
        else -> FinMoney.rp(budget.remaining)
    }
    hero.addView(finRow3(
        finMetric("Pemasukan", FinMoney.rp(cur.income)),
        finMetric("Pengeluaran", FinMoney.rp(cur.expense)),
        finMetric("Sisa anggaran", budgetValue, budget?.let { FinMoney.percent(it.permille) + " terpakai" }) { financeReaderTool(FINANCE_TAB_BUDGET) }
    ))
    val cmp = FinCalc.compare(cur, last)
    val delta = cmp.expense.permilleChange?.let { (if (it > 0) "+" else "") + FinMoney.percent(it) } ?: "—"
    hero.addView(finText("Selisih bulan ini ${FinMoney.signed(cur.net)} · pengeluaran vs ${prev.shortLabel}: $delta", 11.5f, muted = true).apply { setPadding(0, dp(10), 0, 0) })
    finAddCard(hero, 10)

    val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val btnIn = button("+ Pemasukan") { showTxEditor(db, null, true) }
    styleAsSecondary(btnIn)
    val btnOut = button("− Pengeluaran") { showTxEditor(db, null, false) }
    styleAsPrimary(btnOut)
    actions.addView(btnIn, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) })
    actions.addView(btnOut, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5) })
    content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

    // Peringatan: anggaran terlampaui & tagihan jatuh tempo.
    val warnings = ArrayList<View>()
    if (budget != null && budget.level == FinBudgetLevel.OVER) warnings.add(finWarning("Anggaran bulan ini terlampaui ${FinMoney.rp(-budget.remaining)}") { financeReaderTool(FINANCE_TAB_BUDGET) })
    db.getBudgets().forEach { (cat, limit) ->
        val u = FinCalc.budgetUsage(spentByCat[cat] ?: 0L, limit)
        if (u.level == FinBudgetLevel.OVER) warnings.add(finWarning("$cat melebihi anggaran ${FinMoney.rp(-u.remaining)}") { financeReaderTool(FINANCE_TAB_BUDGET) })
    }
    if (isCurrent) {
        FinanceBillNotifier.dueBills(db, includeSilent = true).take(3).forEach { (b, d) ->
            val whenText = if (d.daysUntil < 0) "terlambat ${-d.daysUntil} hari" else if (d.daysUntil == 0) "jatuh tempo hari ini" else "jatuh tempo ${d.daysUntil} hari lagi"
            warnings.add(finWarning("${b.title} ${FinMoney.rp(b.amount)} · $whenText", "calendar-alert") { financeUi.planTab = 1; financeReaderTool(FINANCE_TAB_PLAN) })
        }
    }
    if (warnings.isNotEmpty()) {
        sectionTitle("Perlu perhatian")
        warnings.take(5).forEach { content.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }) }
    }

    sectionTitle("Pengeluaran terbesar", "Grafik") { financeReaderTool(FINANCE_TAB_CHARTS) }
    if (cats.isEmpty()) content.addView(subLabel("Belum ada pengeluaran di ${m.label}.", 12f))
    else cats.take(3).forEach { content.addView(financeCategoryBar(it.category, it.amount, cats.first().amount, FinMoney.percent(it.permille))) }

    sectionTitle("Transaksi ${m.shortLabel} ${m.year}", "Semua") {
        financeUi.rangeMode = 1
        financeUi.query = financeUi.query.copy(from = m.startMillis(), to = m.endMillis())
        financeReaderTool(FINANCE_TAB_HISTORY)
    }
    val latest = db.queryTx(FinanceTxQuery(from = from, to = to), 5)
    if (latest.isEmpty()) {
        content.addView(uiEmptyState("Belum ada transaksi", "Catat pemasukan atau pengeluaran pertama untuk ${m.label}.", "plus-circle-outline", "Catat transaksi") { showTxEditor(db, null, null) })
    } else latest.forEach { content.addView(financeTxRow(db, it)) }
    content.addView(uiInfoNote("Ketuk transaksi untuk mengubah, tahan untuk menghapus. Menu + di kanan atas: tagihan, ekspor/impor CSV, backup."))
}

// ---------------------------------------------------------------- Riwayat

private fun MainActivity.financeQueryForUi(): FinanceTxQuery {
    val q = financeUi.query
    return when (financeUi.rangeMode) {
        0 -> q.copy(from = null, to = null)
        1 -> q.copy(from = financeUi.month.startMillis(), to = financeUi.month.endMillis())
        else -> q
    }
}

internal fun MainActivity.financeFilterSummary(): String {
    val q = financeUi.query
    val parts = ArrayList<String>()
    q.type?.let { parts.add(FinType.label(it)) }
    q.category?.takeIf { it != "Semua" }?.let { parts.add(it) }
    q.method?.takeIf { it != "Semua" }?.let { parts.add(it) }
    q.wallet?.takeIf { it != "Semua" }?.let { parts.add(it) }
    when (financeUi.rangeMode) {
        1 -> parts.add(financeUi.month.label)
        2 -> parts.add(listOfNotNull(q.from?.let { finDateText(it) }, q.to?.let { finDateText(it - 1) }).joinToString(" – "))
    }
    if (q.sort != FinanceSort.DATE_DESC) parts.add(q.sort.label)
    return parts.joinToString(" · ")
}

private fun MainActivity.financeTabHistory(db: FinanceDb) {
    val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val search = edit("Cari catatan atau kategori").apply {
        setText(financeUi.query.text)
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        contentDescription = "Cari transaksi"
    }
    fun apply() { financeUi.query = financeUi.query.copy(text = search.text.toString().trim()); financeReaderTool(FINANCE_TAB_HISTORY) }
    search.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_SEARCH) { apply(); true } else false }
    searchRow.addView(search, LinearLayout.LayoutParams(0, dp(48), 1f))
    searchRow.addView(finIconButton("magnify", "Cari") { apply() })
    searchRow.addView(finIconButton("filter-variant", "Filter & urutan") { showFinanceFilterDialog(db) })
    content.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

    val summary = financeFilterSummary()
    val filtered = summary.isNotEmpty() || financeUi.query.text.isNotBlank()
    if (filtered) {
        val fr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fr.addView(finText(if (summary.isEmpty()) "Pencarian aktif" else "Filter: $summary", 12f, muted = true), LinearLayout.LayoutParams(0, -2, 1f))
        val reset = button("Reset") { financeUi.query = FinanceTxQuery(); financeUi.rangeMode = 0; financeReaderTool(FINANCE_TAB_HISTORY) }
        styleAsTertiary(reset)
        fr.addView(reset, LinearLayout.LayoutParams(-2, dp(44)))
        content.addView(fr)
    }

    val q = financeQueryForUi()
    val (count, inc, exp) = db.queryTotals(q)
    val txs = db.queryTx(q, 300)
    if (txs.isEmpty()) {
        if (filtered) content.addView(uiEmptyState("Tidak ada hasil", "Tidak ada transaksi yang cocok dengan pencarian/filter.", "magnify", "Reset filter") {
            financeUi.query = FinanceTxQuery(); financeUi.rangeMode = 0; financeReaderTool(FINANCE_TAB_HISTORY)
        })
        else content.addView(uiEmptyState("Belum ada transaksi", "Catat transaksi pertama atau impor dari CSV.", "plus-circle-outline", "Catat transaksi") { showTxEditor(db, null, null) })
        return
    }
    content.addView(finText("$count transaksi · masuk ${FinMoney.rp(inc)} · keluar ${FinMoney.rp(exp)}", 12f, muted = true).apply { setPadding(dp(2), dp(4), 0, dp(8)) })
    val byDate = q.sort == FinanceSort.DATE_DESC || q.sort == FinanceSort.DATE_ASC
    var lastDay = ""
    txs.forEach { t ->
        if (byDate) {
            val day = finDateText(t.timestamp, "EEEE, d MMM yyyy")
            if (day != lastDay) {
                lastDay = day
                content.addView(finText(day, 11.5f, bold = true, muted = true).apply { setPadding(dp(2), dp(6), 0, dp(4)) })
            }
        }
        content.addView(financeTxRow(db, t))
    }
    if (count > txs.size) content.addView(uiInfoNote("Menampilkan ${txs.size} dari $count transaksi. Persempit dengan filter tanggal untuk melihat sisanya."))
}

// ---------------------------------------------------------------- Grafik

private fun MainActivity.financeChart(): FinanceChartView = FinanceChartView(this).apply {
    ink = textMain
    muted = textMuted
    grid = line
    secondFill = if (isDarkTheme) android.graphics.Color.rgb(96, 96, 102) else android.graphics.Color.rgb(196, 196, 202)
}

private fun MainActivity.financeTabCharts(db: FinanceDb) {
    val m = financeUi.month
    val first = m.plus(-5)
    val entries = db.entries(first.startMillis(), m.endMillis())
    val series = FinCalc.monthlySeries(entries, m, 6)

    // 1) Pemasukan vs pengeluaran 6 bulan
    val c1 = finCard()
    c1.addView(finText("Pemasukan vs pengeluaran · 6 bulan", 13.5f, bold = true))
    c1.addView(finText("${first.shortLabel} ${first.year} – ${m.shortLabel} ${m.year}", 11f, muted = true).apply { setPadding(0, dp(2), 0, dp(8)) })
    val chart1 = financeChart()
    chart1.setGroups(listOf("Pemasukan", "Pengeluaran"), series.map { FinanceChartView.Group(it.month.shortLabel, listOf(it.income, it.expense)) }, series.size - 1)
    chart1.contentDescription = "Grafik batang 6 bulan. " + series.joinToString("; ") { "${it.month.label}: masuk ${FinMoney.rp(it.income)}, keluar ${FinMoney.rp(it.expense)}" }
    c1.addView(chart1, LinearLayout.LayoutParams(-1, -2))
    // Tabel nilai agar grafik tetap terbaca tanpa warna.
    val table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
    fun tRow(a: String, b: String, c: String, d: String, bold: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf(a to 0.8f, b to 1.2f, c to 1.2f, d to 1.2f).forEachIndexed { i, (v, w) ->
            addView(finText(v, 11f, bold = bold, muted = !bold && i == 0).apply { gravity = if (i == 0) Gravity.START else Gravity.END; maxLines = 1 }, LinearLayout.LayoutParams(0, -2, w))
        }
        setPadding(0, dp(2), 0, dp(2))
    }
    table.addView(tRow("Bulan", "Masuk", "Keluar", "Selisih", true))
    series.forEach { table.addView(tRow(it.month.shortLabel, FinMoney.compact(it.income), FinMoney.compact(it.expense), FinMoney.compact(it.income - it.expense), it.month == m)) }
    c1.addView(table)
    finAddCard(c1, 10)

    // 2) Kategori pengeluaran bulan terpilih
    val c2 = finCard()
    c2.addView(finText("Kategori pengeluaran · ${m.label}", 13.5f, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
    val cats = FinCalc.categoryBreakdown(entries, FinType.OUT, m.startMillis(), m.endMillis())
    if (cats.isEmpty()) c2.addView(finText("Belum ada pengeluaran bulan ini.", 12f, muted = true))
    else {
        val top = cats.take(7).toMutableList()
        if (cats.size > 7) {
            val rest = cats.drop(7)
            top.add(FinShare("Lainnya (${rest.size} kategori)", rest.sumOf { it.amount }, rest.sumOf { it.permille }))
        }
        val chart2 = financeChart()
        chart2.setRows(top.map { FinanceChartView.Row(it.category, it.amount, "${FinMoney.rp(it.amount)} · ${FinMoney.percent(it.permille)}") })
        chart2.contentDescription = "Kategori pengeluaran: " + top.joinToString("; ") { "${it.category} ${FinMoney.rp(it.amount)}, ${FinMoney.percent(it.permille)}" }
        c2.addView(chart2, LinearLayout.LayoutParams(-1, -2))
        c2.addView(finText("Total ${FinMoney.rp(cats.sumOf { it.amount })}", 11.5f, muted = true).apply { setPadding(0, dp(4), 0, 0) })
    }
    finAddCard(c2, 10)

    // 3) Bulan ini vs bulan lalu
    val prev = m.plus(-1)
    val curS = series.last().let { FinSummary(it.income, it.expense) }
    val prevS = series[series.size - 2].let { FinSummary(it.income, it.expense) }
    val cmp = FinCalc.compare(curS, prevS)
    val c3 = finCard()
    c3.addView(finText("${m.shortLabel} ${m.year} vs ${prev.shortLabel} ${prev.year}", 13.5f, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
    val chart3 = financeChart()
    chart3.setGroups(listOf(m.shortLabel, prev.shortLabel), listOf(
        FinanceChartView.Group("Pemasukan", listOf(curS.income, prevS.income)),
        FinanceChartView.Group("Pengeluaran", listOf(curS.expense, prevS.expense))
    ))
    c3.addView(chart3, LinearLayout.LayoutParams(-1, -2))
    fun deltaLine(name: String, d: FinDelta): String {
        val pct = d.permilleChange?.let { " (" + (if (it > 0) "+" else "") + FinMoney.percent(it) + ")" } ?: ""
        return "$name: ${FinMoney.rp(d.current)} · ${if (d.diff == 0L) "sama" else FinMoney.signed(d.diff)}$pct"
    }
    listOf(deltaLine("Pemasukan", cmp.income), deltaLine("Pengeluaran", cmp.expense), deltaLine("Selisih", cmp.net)).forEach {
        c3.addView(finText(it, 12f).apply { setPadding(0, dp(4), 0, 0) })
    }
    chart3.contentDescription = "Perbandingan. " + listOf(deltaLine("Pemasukan", cmp.income), deltaLine("Pengeluaran", cmp.expense)).joinToString(". ")
    finAddCard(c3, 10)
}

// ---------------------------------------------------------------- Anggaran

private fun MainActivity.financeTabBudget(db: FinanceDb) {
    val m = financeUi.month
    val spent = db.sumByCategory(FinType.OUT, m.startMillis(), m.endMillis()).toMap()
    val expense = spent.values.sum()
    val total = db.totalBudget()

    sectionTitle("Anggaran total bulanan")
    if (total == null) {
        content.addView(finWarning("Belum ada anggaran total. Ketuk untuk mengatur batas pengeluaran per bulan.", "plus-circle-outline") { showBudgetEditor(db, FinanceDb.TOTAL_BUDGET) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    } else content.addView(financeBudgetCard("Total · ${m.label}", FinCalc.budgetUsage(expense, total)) { showBudgetEditor(db, FinanceDb.TOTAL_BUDGET) })

    val budgets = db.getBudgets()
    sectionTitle("Per kategori", "+ Kategori") { showBudgetEditor(db, null) }
    if (budgets.isEmpty()) {
        content.addView(uiEmptyState("Belum ada anggaran kategori", "Batasi pengeluaran tiap kategori, mis. Makanan Rp1.500.000/bulan.", "plus-circle-outline", "Atur anggaran") { showBudgetEditor(db, null) })
    } else {
        budgets.entries.sortedByDescending { FinMoney.permille(spent[it.key] ?: 0L, it.value) }.forEach { (cat, limit) ->
            content.addView(financeBudgetCard(cat, FinCalc.budgetUsage(spent[cat] ?: 0L, limit)) { showBudgetEditor(db, cat) })
        }
    }
    val unbudgeted = spent.filterKeys { it !in budgets }.entries.sortedByDescending { it.value }
    if (unbudgeted.isNotEmpty()) {
        sectionTitle("Pengeluaran tanpa anggaran")
        unbudgeted.take(8).forEach { (cat, v) ->
            content.addView(finListRow("plus-circle-outline", cat, "Ketuk untuk membuat anggaran", FinMoney.rp(v)) { showBudgetEditor(db, cat, suggest = v) })
        }
    }
}

// ---------------------------------------------------------------- Rencana

private fun MainActivity.financeTabPlan(db: FinanceDb) {
    content.addView(finSegmented(listOf("Tabungan", "Tagihan", "Utang/Piutang"), financeUi.planTab) { financeUi.planTab = it; financeReaderTool(FINANCE_TAB_PLAN) },
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    when (financeUi.planTab) {
        1 -> financePlanBills(db)
        2 -> financePlanDebts(db)
        else -> financePlanGoals(db)
    }
}

private fun MainActivity.financePlanGoals(db: FinanceDb) {
    val goals = db.goals()
    if (goals.isEmpty()) {
        content.addView(uiEmptyState("Belum ada target tabungan", "Buat target (mis. Dana darurat Rp10.000.000) lalu catat setoran.", "piggy-bank-outline", "Target baru") { showGoalEditor(db, null) })
        return
    }
    val progress = goals.map { it to db.goalProgress(it) }
    val saved = progress.sumOf { it.second.saved }
    val target = progress.sumOf { it.first.target }
    content.addView(finText("Terkumpul ${FinMoney.rp(saved)} dari ${FinMoney.rp(target)}", 12f, muted = true).apply { setPadding(dp(2), 0, 0, dp(8)) })
    progress.forEach { (g, p) ->
        val card = finCard().apply { background = rippleBg(panel2, Ds.RADIUS_MD, line); setOnClickListener { showGoalDetail(db, g) } }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(finText(g.name, 14f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(finText(if (p.done) "Tercapai" else FinMoney.percent(p.permille), 12f, bold = true))
        card.addView(top)
        card.addView(finText("${FinMoney.rp(p.saved)} / ${FinMoney.rp(g.target)}" + if (p.done) "" else " · kurang ${FinMoney.rp(p.remaining)}", 11.5f, muted = true).apply { setPadding(0, dp(3), 0, dp(6)) })
        card.addView(finProgress(p.permille))
        if (g.deadline > 0) card.addView(finText("Target tanggal ${finDateText(g.deadline)}", 11f, muted = true).apply { setPadding(0, dp(6), 0, 0) })
        card.contentDescription = "${g.name}, ${FinMoney.percent(p.permille)}, terkumpul ${FinMoney.rp(p.saved)} dari ${FinMoney.rp(g.target)}"
        finAddCard(card)
    }
    val add = button("+ Target baru") { showGoalEditor(db, null) }
    styleAsSecondary(add)
    content.addView(add, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
}

internal fun billStateText(d: FinBillDue): String = when (d.state) {
    FinBillState.PAID -> "Lunas"
    FinBillState.OVERDUE -> "Terlambat ${-d.daysUntil} hari"
    FinBillState.DUE_SOON -> if (d.daysUntil == 0) "Jatuh tempo hari ini" else "${d.daysUntil} hari lagi"
    FinBillState.UPCOMING -> "Tgl ${d.dueDate.day}"
}

private fun MainActivity.financePlanBills(db: FinanceDb) {
    val m = financeUi.month
    val bills = db.bills()
    if (bills.isEmpty()) {
        content.addView(uiEmptyState("Belum ada tagihan", "Tambahkan tagihan rutin (listrik, internet, cicilan) agar diingatkan sebelum jatuh tempo.", "calendar-month-outline", "Tagihan baru") { showBillEditor(db, null) })
        return
    }
    val runs = db.billRuns(m)
    val today = FinDate.of(System.currentTimeMillis())
    val active = bills.filter { it.enabled }
    val unpaidTotal = active.filter { it.type == FinType.OUT && !runs.containsKey(it.id) }.sumOf { it.amount }
    content.addView(finText("${active.size} aktif · belum dibayar ${FinMoney.rp(unpaidTotal)} di ${m.label}", 12f, muted = true).apply { setPadding(dp(2), 0, 0, dp(8)) })
    bills.forEach { b ->
        val due = FinCalc.billDue(b.dayOfMonth, m, today, runs.containsKey(b.id), b.remindDays)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ds.SPACE_MD), dp(10), dp(8), dp(10))
            background = rippleBg(panel2, Ds.RADIUS_MD, if (due.state == FinBillState.OVERDUE && b.enabled) textMain else line)
            minimumHeight = dp(60)
            setOnClickListener { showBillEditor(db, b) }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(finText(b.title, 14f, bold = true).apply { maxLines = 1 })
        val mode = when {
            b.autoPost -> "dicatat otomatis"
            b.remindDays > 0 -> "ingatkan H-${b.remindDays}"
            b.remindDays == 0 -> "ingatkan hari H"
            else -> "tanpa pengingat"
        }
        val sub = "${FinType.label(b.type)} ${FinMoney.rp(b.amount)} · tgl ${b.dayOfMonth} · $mode" + if (!b.enabled) " · nonaktif" else ""
        texts.addView(finText(sub, 11f, muted = true).apply { setPadding(0, dp(2), 0, dp(2)) })
        texts.addView(finText(if (b.enabled) billStateText(due) else "Nonaktif", 11.5f, bold = due.state != FinBillState.UPCOMING && b.enabled))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (b.enabled) {
            val paid = runs.containsKey(b.id)
            val act = button(if (paid) "Batal" else if (b.type == FinType.OUT) "Bayar" else "Terima") {
                if (paid) confirmUnpayBill(db, b, m) else showPayBill(db, b, m)
            }
            if (paid) styleAsTertiary(act) else styleAsPrimary(act)
            act.contentDescription = if (paid) "Batalkan status lunas ${b.title}" else "Tandai ${b.title} dibayar"
            row.addView(act, LinearLayout.LayoutParams(-2, dp(44)).apply { leftMargin = dp(6) })
        }
        row.contentDescription = "${b.title}. $sub. ${billStateText(due)}"
        finAddCard(row, 6)
    }
    val add = button("+ Tagihan baru") { showBillEditor(db, null) }
    styleAsSecondary(add)
    content.addView(add, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    content.addView(uiInfoNote("Pengingat dikirim lewat notifikasi (dicek sekali sehari, perlu izin notifikasi). Tagihan yang jatuh tempo juga muncul di tab Ringkas."))
}

private fun MainActivity.financePlanDebts(db: FinanceDb) {
    val debts = db.debts()
    if (debts.isEmpty()) {
        content.addView(uiEmptyState("Belum ada utang/piutang", "Catat pinjaman beserta cicilannya agar sisa selalu jelas.", "handshake-outline", "Catat baru") { showDebtEditor(db, null) })
        return
    }
    val status = debts.map { it to db.debtStatus(it) }
    val owe = status.filter { it.first.isPayable && !it.second.settled }.sumOf { it.second.remaining }
    val owed = status.filter { !it.first.isPayable && !it.second.settled }.sumOf { it.second.remaining }
    content.addView(finRow3(finMetric("Utang saya (sisa)", FinMoney.rp(owe)), finMetric("Piutang (sisa)", FinMoney.rp(owed)), null),
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    val todayEpoch = FinDate.of(System.currentTimeMillis()).epochDay
    status.forEach { (d, s) ->
        val kind = if (d.isPayable) "Utang ke" else "Piutang dari"
        val dueText = if (d.dueDate > 0 && !s.settled) {
            val left = FinDate.of(d.dueDate).epochDay - todayEpoch
            " · jatuh tempo ${finDateText(d.dueDate)}" + if (left < 0) " (lewat)" else ""
        } else ""
        val card = finCard().apply { background = rippleBg(panel2, Ds.RADIUS_MD, line); setOnClickListener { showDebtDetail(db, d) } }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(finText("$kind ${d.person}", 14f, bold = true).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(finText(if (s.settled) "Lunas" else "Sisa ${FinMoney.rp(s.remaining)}", 12f, bold = true))
        card.addView(top)
        card.addView(finText("Dibayar ${FinMoney.rp(s.paid)} dari ${FinMoney.rp(d.amount)}$dueText", 11.5f, muted = true).apply { setPadding(0, dp(3), 0, dp(6)) })
        card.addView(finProgress(if (s.settled) 1000 else FinMoney.permille(s.paid, d.amount)))
        card.contentDescription = "$kind ${d.person}. ${if (s.settled) "Lunas" else "Sisa " + FinMoney.rp(s.remaining)}"
        if (s.settled) card.alpha = 0.7f
        finAddCard(card)
    }
    val add = button("+ Catat utang/piutang") { showDebtEditor(db, null) }
    styleAsSecondary(add)
    content.addView(add, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
}
