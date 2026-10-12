package com.example.aidetest

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Dialog keuangan: transaksi, filter, anggaran, tabungan, tagihan, utang/piutang, serta ekspor/impor.
 * Setiap penghapusan meminta konfirmasi.
 */

internal const val FINANCE_CSV_OPEN = 2006
private const val CSV_MAX_BYTES = 5 * 1024 * 1024

// ---------------------------------------------------------------- menu +

internal fun MainActivity.showFinanceActions() {
    val m = financeUi.month
    val items = arrayOf(
        "Catat transaksi", "Tagihan baru", "Target tabungan baru", "Catat utang/piutang", "Atur anggaran",
        "Ekspor CSV", "Impor CSV", "Backup JSON (semua data)", "Pulihkan backup JSON", "Laporan PDF ${m.label}",
        "Kategori kustom", "Dompet & saldo awal", "Privasi", "Hapus semua data"
    )
    AlertDialog.Builder(this).setTitle("Keuangan").setItems(items) { _, which ->
        val db = FinanceDb(this)
        when (which) {
            0 -> showTxEditor(db, null, null)
            1 -> showBillEditor(db, null)
            2 -> showGoalEditor(db, null)
            3 -> showDebtEditor(db, null)
            4 -> financeReaderTool(FINANCE_TAB_BUDGET)
            5 -> startFinanceCsvExport()
            6 -> startFinanceCsvImport()
            7 -> createFinanceBackup()
            8 -> openFinanceBackup()
            9 -> shareFileAsync("Laporan gagal") { FinanceReport.createPdf(this, db, m) }
            10 -> showCustomCategoryDialog()
            11 -> showWalletDialog(db)
            12 -> showFinancePrivacyGuide()
            13 -> confirmClearFinance(db)
        }
    }.setNegativeButton("Tutup", null).show()
}

// ---------------------------------------------------------------- helpers

internal fun MainActivity.finPickDate(initial: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(this, { _, y, mo, d ->
        val r = Calendar.getInstance().apply { timeInMillis = initial; set(Calendar.YEAR, y); set(Calendar.MONTH, mo); set(Calendar.DAY_OF_MONTH, d) }
        onPicked(r.timeInMillis)
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

/** Tombol pemilih tanggal (44dp+) yang menampilkan tanggal terpilih. */
private fun MainActivity.finDateButton(initial: Long, allowClear: Boolean = false, emptyLabel: String = "Pilih tanggal", onChange: (Long) -> Unit): Button {
    var value = initial
    lateinit var b: Button
    fun label() = if (value <= 0L) emptyLabel else finDateText(value, "EEEE, d MMM yyyy")
    b = button(label()) {
        val start = if (value > 0) value else System.currentTimeMillis()
        if (allowClear && value > 0) {
            AlertDialog.Builder(this).setItems(arrayOf("Ubah tanggal", "Hapus tanggal")) { _, w ->
                if (w == 0) finPickDate(start) { value = it; b.text = label(); onChange(it) } else { value = 0L; b.text = label(); onChange(0L) }
            }.show()
        } else finPickDate(start) { value = it; b.text = label(); onChange(it) }
    }
    styleAsSecondary(b)
    return b
}

/** Pilihan chip satu baris (bisa digulir), sasaran sentuh 44dp. */
private fun MainActivity.finChips(options: List<String>, selected: String, onChange: (String) -> Unit): View {
    val activeFill = if (isDarkTheme) Color.rgb(245, 245, 247) else Color.rgb(18, 18, 20)
    val activeInk = if (isDarkTheme) Color.rgb(18, 18, 20) else Color.WHITE
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    var current = selected
    val views = ArrayList<TextView>()
    fun paint() {
        views.forEachIndexed { i, v ->
            val on = options[i] == current
            v.setTextColor(if (on) activeInk else textMain)
            v.background = rippleBg(if (on) activeFill else panel2, 16, if (on) activeFill else line)
            v.setTypeface(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
            v.contentDescription = options[i] + if (on) ", dipilih" else ""
        }
    }
    options.forEach { o ->
        val v = TextView(this).apply {
            text = o; textSize = 13f; gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            minWidth = dp(44)
            setOnClickListener { current = o; paint(); onChange(o) }
        }
        views.add(v)
        row.addView(v, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(6) })
    }
    paint()
    return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
}

private fun MainActivity.finDialog(title: String, body: View, positive: String = "Simpan", neutral: String? = null): AlertDialog {
    val scroll = ScrollView(this).apply { addView(body) }
    val b = AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton(positive, null).setNegativeButton("Batal", null)
    if (neutral != null) b.setNeutralButton(neutral, null)
    return b.create()
}

private fun MainActivity.confirm(title: String, message: String, action: String = "Hapus", onYes: () -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setMessage(message)
        .setNegativeButton("Batal", null)
        .setPositiveButton(action) { _, _ -> onYes() }
        .show()
}

private inline fun MainActivity.finTry(failPrefix: String, block: () -> Unit): Boolean =
    try { block(); true } catch (e: Exception) { toast("$failPrefix: ${e.message ?: e.javaClass.simpleName}"); false }

internal fun MainActivity.financeCategories(): List<String> {
    val custom = runCatching { JSONArray(prefs.getString("finance_custom_categories", "[]") ?: "[]") }.getOrElse { JSONArray() }
    val out = FinanceCategories.ALL.toMutableList()
    for (i in 0 until custom.length()) { val v = custom.optString(i).trim(); if (v.isNotBlank() && !out.contains(v)) out.add(v) }
    return out
}

private fun MainActivity.allCategories(db: FinanceDb): List<String> =
    (financeCategories() + db.categoriesUsed()).filter { it.isNotBlank() && it != FinanceDb.TOTAL_BUDGET }.distinct()

private fun MainActivity.moneyField(form: LinearLayout, caption: String, initial: Long?): EditText {
    form.addView(finCaption(caption))
    val e = moneyEdit("0", initial)
    form.addView(e, LinearLayout.LayoutParams(-1, dp(52)))
    return e
}

private fun EditText.checkMoney(): Long? {
    val v = moneyValue()
    if (v == null || v <= 0L) { error = "Masukkan nominal lebih dari 0"; requestFocus(); return null }
    return v
}

// ---------------------------------------------------------------- transaksi

/**
 * Catat / ubah transaksi. [existing] null = baru. [forceIncome] menentukan tipe awal transaksi baru.
 * Field: tipe, nominal, kategori, metode pembayaran, tanggal, catatan (+ dompet bila >1).
 */
internal fun MainActivity.showTxEditor(db: FinanceDb, existing: FinanceTx?, forceIncome: Boolean?) {
    var type = existing?.type ?: if (forceIncome == true) FinType.IN else FinType.OUT
    var method = existing?.method ?: (prefs.getString("finance_last_method", "Tunai") ?: "Tunai")
    var ts = existing?.timestamp ?: System.currentTimeMillis()
    val form = finForm()

    val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val cats = allCategories(db)
    val catSpinner = finSpinner(cats, existing?.category ?: prefs.getString(if (type == FinType.IN) "finance_last_cat_in" else "finance_last_cat_out", if (type == FinType.IN) "Gaji" else "Makanan"))
    lateinit var inBtn: Button
    lateinit var outBtn: Button
    fun paintType() {
        if (type == FinType.IN) { styleAsPrimary(inBtn); styleAsSecondary(outBtn) } else { styleAsPrimary(outBtn); styleAsSecondary(inBtn) }
        inBtn.contentDescription = "Pemasukan" + if (type == FinType.IN) ", dipilih" else ""
        outBtn.contentDescription = "Pengeluaran" + if (type == FinType.OUT) ", dipilih" else ""
    }
    inBtn = button("+ Pemasukan") { type = FinType.IN; paintType() }
    outBtn = button("− Pengeluaran") { type = FinType.OUT; paintType() }
    typeRow.addView(inBtn, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(4) })
    typeRow.addView(outBtn, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4) })
    paintType()
    form.addView(typeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    form.addView(finCaption("Nominal (Rp)"))
    val amount = moneyEdit("0", existing?.amount).apply { textSize = 24f; gravity = Gravity.CENTER }
    form.addView(amount, LinearLayout.LayoutParams(-1, dp(56)))
    form.addView(finCaption("Kategori"))
    form.addView(catSpinner, LinearLayout.LayoutParams(-1, dp(48)))
    form.addView(finCaption("Metode pembayaran"))
    form.addView(finChips(FinMethods.ALL, if (method in FinMethods.ALL) method else FinMethods.DEFAULT) { method = it })
    form.addView(finCaption("Tanggal"))
    form.addView(finDateButton(ts) { ts = it }, LinearLayout.LayoutParams(-1, -2))
    val wallets = db.wallets()
    val walletSpinner = finSpinner(wallets, existing?.walletName ?: wallets.first())
    if (wallets.size > 1) { form.addView(finCaption("Dompet / rekening")); form.addView(walletSpinner, LinearLayout.LayoutParams(-1, dp(48))) }
    form.addView(finCaption("Catatan (opsional)"))
    val note = edit("mis. makan siang, gaji Oktober").apply { setText(existing?.merchant.orEmpty()) }
    form.addView(note, LinearLayout.LayoutParams(-1, dp(52)))

    val dialog = finDialog(if (existing == null) "Catat transaksi" else "Ubah transaksi", form, "Simpan", if (existing != null) "Hapus" else null)
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a = amount.checkMoney() ?: return@setOnClickListener
            val category = catSpinner.selectedItem?.toString() ?: "Lainnya"
            val wallet = if (wallets.size > 1) walletSpinner.selectedItem?.toString() ?: "Umum" else (existing?.walletName ?: wallets.first())
            val noteText = note.text.toString().trim()
            val ok = finTry("Gagal menyimpan") {
                if (existing == null) {
                    val id = db.insertTx(FinanceTx(timestamp = ts, type = type, amount = a, category = category, merchant = noteText,
                        sourceApp = "manual", rawText = "", manual = true, walletName = wallet, method = method))
                    check(id > 0) { "database menolak data" }
                    dialog.dismiss()
                    showFinanceUndoBar(db, id, "${FinType.label(type)} ${FinMoney.rp(a)} tersimpan")
                } else {
                    db.updateTx(existing.copy(timestamp = ts, type = type, amount = a, category = category, merchant = noteText, walletName = wallet, method = method))
                    dialog.dismiss()
                    toast("Transaksi diperbarui")
                    financeRefresh()
                }
            }
            if (ok) prefs.edit().putString("finance_last_method", method).putString(if (type == FinType.IN) "finance_last_cat_in" else "finance_last_cat_out", category).apply()
        }
        if (existing != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { confirmDeleteTx(db, existing) { dialog.dismiss() } }
    }
    if (existing == null) dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    dialog.show()
    if (existing == null) amount.requestFocus()
}

/** Kompatibilitas pemanggil lama (intent quick_income / quick_expense). */
internal fun MainActivity.showAddTxDialog(db: FinanceDb, forceIncome: Boolean? = null) = showTxEditor(db, null, forceIncome)

internal fun MainActivity.confirmDeleteTx(db: FinanceDb, t: FinanceTx, after: (() -> Unit)? = null) {
    val link = db.txLinkInfo(t.id)
    confirm("Hapus transaksi?", "${t.merchant.ifBlank { t.category }} · ${FinType.label(t.type)} ${FinMoney.rp(t.amount)}\n${finDateText(t.timestamp)}" +
        (if (link != null) "\n\n$link" else "") + "\n\nTindakan ini tidak bisa dibatalkan.") {
        if (finTry("Gagal menghapus") { db.deleteTx(t.id) }) {
            after?.invoke()
            toast("Transaksi dihapus")
            financeRefresh()
        }
    }
}

internal fun MainActivity.showFinanceUndoBar(db: FinanceDb, txId: Long, message: String) {
    financeRefresh()
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(6), dp(6), dp(6))
        background = bg(panel2, Ds.RADIUS_LG, textMain)
    }
    bar.addView(TextView(this).apply { text = message; textSize = 12.5f; setTextColor(textMain) }, LinearLayout.LayoutParams(0, -2, 1f))
    val undo = button("Batalkan") {
        db.deleteTx(txId)
        content.removeView(bar)
        toast("Dibatalkan")
        financeRefresh()
    }
    styleAsTertiary(undo)
    bar.addView(undo, LinearLayout.LayoutParams(-2, dp(44)))
    content.addView(bar, 0, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    bar.postDelayed({ if (bar.parent != null) bar.animate().alpha(0f).setDuration(160).withEndAction { content.removeView(bar) }.start() }, 6000L)
}

// ---------------------------------------------------------------- filter riwayat

internal fun MainActivity.showFinanceFilterDialog(db: FinanceDb) {
    val q = financeUi.query
    val form = finForm()
    val types = listOf("Semua", "Pemasukan", "Pengeluaran")
    val typeSp = finSpinner(types, when (q.type) { FinType.IN -> "Pemasukan"; FinType.OUT -> "Pengeluaran"; else -> "Semua" })
    val catItems = listOf("Semua") + allCategories(db)
    val catSp = finSpinner(catItems, q.category ?: "Semua")
    val methodItems = listOf("Semua") + FinMethods.ALL
    val methodSp = finSpinner(methodItems, q.method ?: "Semua")
    val wallets = listOf("Semua") + db.wallets()
    val walletSp = finSpinner(wallets, q.wallet ?: "Semua")
    val ranges = listOf("Semua waktu", "Bulan terpilih (${financeUi.month.label})", "Rentang tanggal…")
    val rangeSp = finSpinner(ranges, ranges[financeUi.rangeMode.coerceIn(0, 2)])
    val sortItems = FinanceSort.values().map { it.label }
    val sortSp = finSpinner(sortItems, q.sort.label)
    var from = q.from ?: financeUi.month.startMillis()
    var to = (q.to ?: financeUi.month.endMillis()) - 1
    val rangeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    rangeBox.addView(finCaption("Dari"))
    rangeBox.addView(finDateButton(from) { from = it }, LinearLayout.LayoutParams(-1, -2))
    rangeBox.addView(finCaption("Sampai"))
    rangeBox.addView(finDateButton(to) { to = it }, LinearLayout.LayoutParams(-1, -2))
    rangeBox.visibility = if (financeUi.rangeMode == 2) View.VISIBLE else View.GONE
    rangeSp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) { rangeBox.visibility = if (pos == 2) View.VISIBLE else View.GONE }
        override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
    }
    form.addView(finCaption("Tipe")); form.addView(typeSp)
    form.addView(finCaption("Kategori")); form.addView(catSp)
    form.addView(finCaption("Metode pembayaran")); form.addView(methodSp)
    if (wallets.size > 2) { form.addView(finCaption("Dompet")); form.addView(walletSp) }
    form.addView(finCaption("Periode")); form.addView(rangeSp)
    form.addView(rangeBox)
    form.addView(finCaption("Urutkan")); form.addView(sortSp)
    val dialog = finDialog("Filter & urutan", form, "Terapkan", "Reset")
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val mode = rangeSp.selectedItemPosition.coerceIn(0, 2)
            var f: Long? = null
            var t: Long? = null
            if (mode == 2) {
                val start = Calendar.getInstance().apply { timeInMillis = minOf(from, to); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                val end = Calendar.getInstance().apply { timeInMillis = maxOf(from, to); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_MONTH, 1) }
                f = start.timeInMillis; t = end.timeInMillis
            }
            financeUi.rangeMode = mode
            financeUi.query = q.copy(
                type = when (typeSp.selectedItemPosition) { 1 -> FinType.IN; 2 -> FinType.OUT; else -> null },
                category = catSp.selectedItem?.toString()?.takeIf { it != "Semua" },
                method = methodSp.selectedItem?.toString()?.takeIf { it != "Semua" },
                wallet = if (wallets.size > 2) walletSp.selectedItem?.toString()?.takeIf { it != "Semua" } else null,
                from = f, to = t,
                sort = FinanceSort.values()[sortSp.selectedItemPosition.coerceIn(0, FinanceSort.values().size - 1)]
            )
            dialog.dismiss()
            financeReaderTool(FINANCE_TAB_HISTORY)
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            financeUi.query = FinanceTxQuery(text = q.text); financeUi.rangeMode = 0
            dialog.dismiss(); financeReaderTool(FINANCE_TAB_HISTORY)
        }
    }
    dialog.show()
}

// ---------------------------------------------------------------- anggaran

/** [category] = null → pilih kategori; [FinanceDb.TOTAL_BUDGET] → anggaran total. */
internal fun MainActivity.showBudgetEditor(db: FinanceDb, category: String?, suggest: Long? = null) {
    val isTotal = category == FinanceDb.TOTAL_BUDGET
    val budgets = db.getBudgets()
    val existing = when {
        isTotal -> db.totalBudget()
        category != null -> budgets[category]
        else -> null
    }
    val form = finForm()
    val cats = allCategories(db).filter { it != "Gaji" && it != "Piutang" }
    val catSp = finSpinner(cats, cats.firstOrNull { it !in budgets } ?: cats.first())
    if (category == null) { form.addView(finCaption("Kategori")); form.addView(catSp, LinearLayout.LayoutParams(-1, dp(48))) }
    else form.addView(finText(if (isTotal) "Batas seluruh pengeluaran per bulan" else "Kategori: $category", 13f, muted = true).apply { setPadding(0, dp(8), 0, 0) })
    val amount = moneyField(form, "Batas per bulan (Rp)", existing ?: suggest)
    form.addView(finText("Berlaku setiap bulan. Peringatan muncul pada 80% dan saat terlampaui.", 11f, muted = true).apply { setPadding(0, dp(8), 0, 0) })
    val title = if (isTotal) "Anggaran total" else if (existing != null) "Ubah anggaran" else "Anggaran kategori"
    val dialog = finDialog(title, form, "Simpan", if (existing != null) "Hapus" else null)
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = amount.checkMoney() ?: return@setOnClickListener
            val cat = category ?: catSp.selectedItem?.toString() ?: return@setOnClickListener
            if (finTry("Gagal menyimpan") { db.setBudget(cat, v) }) { dialog.dismiss(); toast("Anggaran disimpan"); financeRefresh() }
        }
        if (existing != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val cat = category ?: return@setOnClickListener
            confirm("Hapus anggaran?", "Anggaran ${if (isTotal) "total" else cat} (${FinMoney.rp(existing)}/bulan) akan dihapus. Transaksi tidak terpengaruh.") {
                db.deleteBudget(cat); dialog.dismiss(); toast("Anggaran dihapus"); financeRefresh()
            }
        }
    }
    dialog.show()
}

// ---------------------------------------------------------------- tabungan

internal fun MainActivity.showGoalEditor(db: FinanceDb, existing: FinanceGoal?) {
    val form = finForm()
    form.addView(finCaption("Nama target"))
    val name = edit("mis. Dana darurat").apply { setText(existing?.name.orEmpty()) }
    form.addView(name, LinearLayout.LayoutParams(-1, dp(52)))
    val target = moneyField(form, "Target (Rp)", existing?.target)
    val initial = moneyField(form, "Sudah terkumpul sebelumnya (opsional)", existing?.initialSaved)
    var deadline = existing?.deadline ?: 0L
    form.addView(finCaption("Tanggal target (opsional)"))
    form.addView(finDateButton(deadline, allowClear = true, emptyLabel = "Tanpa tanggal target") { deadline = it }, LinearLayout.LayoutParams(-1, -2))
    val dialog = finDialog(if (existing == null) "Target tabungan baru" else "Ubah target", form, "Simpan", if (existing != null) "Hapus" else null)
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            if (n.isBlank()) { name.error = "Nama wajib diisi"; name.requestFocus(); return@setOnClickListener }
            val t = target.checkMoney() ?: return@setOnClickListener
            val init = if (initial.text.isBlank()) 0L else initial.moneyValue() ?: run { initial.error = "Nominal tidak valid"; return@setOnClickListener }
            if (finTry("Gagal menyimpan") {
                    if (existing == null) db.addGoal(n, t, init, deadline) else db.updateGoal(existing.copy(name = n, target = t, initialSaved = init, deadline = deadline))
                }) { dialog.dismiss(); toast("Target disimpan"); financeUi.planTab = 0; financeReaderTool(FINANCE_TAB_PLAN) }
        }
        if (existing != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            confirm("Hapus target \"${existing.name}\"?", "Target dihapus. Setoran yang sudah dicatat tetap ada sebagai transaksi biasa (kategori Tabungan).") {
                db.deleteGoal(existing.id); dialog.dismiss(); toast("Target dihapus"); financeRefresh()
            }
        }
    }
    dialog.show()
}

internal fun MainActivity.showGoalDetail(db: FinanceDb, g: FinanceGoal) {
    val p = db.goalProgress(g)
    val deposits = db.goalDeposits(g.id)
    val form = finForm()
    form.addView(finText("${FinMoney.rp(p.saved)} dari ${FinMoney.rp(g.target)}", 16f, bold = true).apply { setPadding(0, dp(8), 0, dp(4)) })
    form.addView(finText(if (p.done) "Target tercapai" else "${FinMoney.percent(p.permille)} · kurang ${FinMoney.rp(p.remaining)}", 12f, muted = true).apply { setPadding(0, 0, 0, dp(8)) })
    form.addView(finProgress(p.permille))
    if (g.initialSaved > 0) form.addView(finText("Termasuk saldo awal ${FinMoney.rp(g.initialSaved)}", 11f, muted = true).apply { setPadding(0, dp(6), 0, 0) })
    if (g.deadline > 0) form.addView(finText("Tanggal target ${finDateText(g.deadline)}", 11f, muted = true).apply { setPadding(0, dp(4), 0, 0) })
    form.addView(finCaption(if (deposits.isEmpty()) "Belum ada setoran" else "Setoran (${deposits.size}) · ketuk untuk menghapus"))
    lateinit var dialog: AlertDialog
    deposits.take(30).forEach { t ->
        form.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44)
            background = rippleBg(Color.TRANSPARENT, Ds.RADIUS_SM)
            addView(finText(finDateText(t.timestamp) + " · " + t.method, 12f, muted = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(finText(FinMoney.rp(t.amount), 13f, bold = true))
            setOnClickListener { confirmDeleteTx(db, t) { dialog.dismiss() } }
        })
    }
    dialog = AlertDialog.Builder(this).setTitle(g.name).setView(ScrollView(this).apply { addView(form) })
        .setPositiveButton("Setor") { _, _ -> showGoalDeposit(db, g) }
        .setNeutralButton("Ubah") { _, _ -> showGoalEditor(db, g) }
        .setNegativeButton("Tutup", null).create()
    dialog.show()
}

internal fun MainActivity.showGoalDeposit(db: FinanceDb, g: FinanceGoal) {
    val p = db.goalProgress(g)
    val form = finForm()
    val amount = moneyField(form, "Nominal setoran (Rp)", null)
    var method = prefs.getString("finance_last_method", "Transfer") ?: "Transfer"
    var ts = System.currentTimeMillis()
    form.addView(finCaption("Metode")); form.addView(finChips(FinMethods.ALL, if (method in FinMethods.ALL) method else FinMethods.DEFAULT) { method = it })
    form.addView(finCaption("Tanggal")); form.addView(finDateButton(ts) { ts = it }, LinearLayout.LayoutParams(-1, -2))
    val wallets = db.wallets()
    val walletSp = finSpinner(wallets)
    if (wallets.size > 1) { form.addView(finCaption("Dari dompet")); form.addView(walletSp, LinearLayout.LayoutParams(-1, dp(48))) }
    if (!p.done) form.addView(finText("Kurang ${FinMoney.rp(p.remaining)} untuk mencapai target.", 11f, muted = true).apply { setPadding(0, dp(8), 0, 0) })
    form.addView(finText("Setoran dicatat sebagai pengeluaran kategori Tabungan.", 11f, muted = true).apply { setPadding(0, dp(4), 0, 0) })
    val dialog = finDialog("Setor ke ${g.name}", form)
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = amount.checkMoney() ?: return@setOnClickListener
            if (finTry("Gagal menyimpan") {
                    db.insertTx(FinanceTx(timestamp = ts, type = FinType.OUT, amount = v, category = "Tabungan", merchant = "Setoran ${g.name}", sourceApp = "savings-goal",
                        rawText = "Kontribusi target tabungan", manual = true, walletName = if (wallets.size > 1) walletSp.selectedItem?.toString() ?: "Umum" else wallets.first(), goalId = g.id, method = method))
                }) { dialog.dismiss(); toast("Setoran ${FinMoney.rp(v)} tersimpan"); financeRefresh() }
        }
    }
    dialog.show()
}

// ---------------------------------------------------------------- tagihan

private val REMIND_LABELS = listOf("Tanpa pengingat", "Pada hari jatuh tempo", "H-1", "H-2", "H-3", "H-7")
private val REMIND_VALUES = listOf(-1, 0, 1, 2, 3, 7)

internal fun MainActivity.showBillEditor(db: FinanceDb, existing: FinanceBill?) {
    val form = finForm()
    form.addView(finCaption("Nama tagihan"))
    val title = edit("mis. Listrik PLN, Internet, Cicilan").apply { setText(existing?.title.orEmpty()) }
    form.addView(title, LinearLayout.LayoutParams(-1, dp(52)))
    val amount = moneyField(form, "Nominal (Rp)", existing?.amount)
    val typeSp = finSpinner(listOf("Pengeluaran", "Pemasukan"), if (existing?.type == FinType.IN) "Pemasukan" else "Pengeluaran")
    form.addView(finCaption("Tipe")); form.addView(typeSp, LinearLayout.LayoutParams(-1, dp(48)))
    val cats = allCategories(db)
    val catSp = finSpinner(cats, existing?.category ?: "Tagihan")
    form.addView(finCaption("Kategori")); form.addView(catSp, LinearLayout.LayoutParams(-1, dp(48)))
    var method = existing?.method ?: "Transfer"
    form.addView(finCaption("Metode pembayaran")); form.addView(finChips(FinMethods.ALL, method) { method = it })
    form.addView(finCaption("Tanggal jatuh tempo setiap bulan (1–31)"))
    val day = edit("mis. 20").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(existing?.dayOfMonth?.toString().orEmpty()) }
    form.addView(day, LinearLayout.LayoutParams(-1, dp(52)))
    val remindSp = finSpinner(REMIND_LABELS, REMIND_LABELS[REMIND_VALUES.indexOf(existing?.remindDays ?: 1).coerceAtLeast(0)])
    form.addView(finCaption("Pengingat notifikasi")); form.addView(remindSp, LinearLayout.LayoutParams(-1, dp(48)))
    val wallets = db.wallets()
    val walletSp = finSpinner(wallets, existing?.wallet ?: wallets.first())
    if (wallets.size > 1) { form.addView(finCaption("Dompet")); form.addView(walletSp, LinearLayout.LayoutParams(-1, dp(48))) }
    val auto = CheckBox(this).apply {
        text = "Catat otomatis saat jatuh tempo (tanpa tombol Bayar)"
        setTextColor(textMain); isChecked = existing?.autoPost ?: false; minHeight = dp(48)
    }
    form.addView(auto)
    val enabled = CheckBox(this).apply { text = "Aktif"; setTextColor(textMain); isChecked = existing?.enabled ?: true; minHeight = dp(48) }
    if (existing != null) form.addView(enabled)
    val dialog = finDialog(if (existing == null) "Tagihan baru" else "Ubah tagihan", form, "Simpan", if (existing != null) "Hapus" else null)
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val t = title.text.toString().trim()
            if (t.isBlank()) { title.error = "Nama wajib diisi"; title.requestFocus(); return@setOnClickListener }
            val a = amount.checkMoney() ?: return@setOnClickListener
            val d = day.text.toString().toIntOrNull()
            if (d == null || d !in 1..31) { day.error = "Isi tanggal 1–31"; day.requestFocus(); return@setOnClickListener }
            val bill = FinanceBill(
                id = existing?.id ?: 0, title = t, amount = a, type = if (typeSp.selectedItemPosition == 1) FinType.IN else FinType.OUT,
                category = catSp.selectedItem?.toString() ?: "Tagihan", wallet = if (wallets.size > 1) walletSp.selectedItem?.toString() ?: "Umum" else (existing?.wallet ?: wallets.first()),
                dayOfMonth = d, enabled = enabled.isChecked, autoPost = auto.isChecked,
                remindDays = REMIND_VALUES[remindSp.selectedItemPosition.coerceIn(0, REMIND_VALUES.size - 1)], method = method, lastRun = existing?.lastRun ?: 0
            )
            if (finTry("Gagal menyimpan") { if (existing == null) db.addBill(bill) else db.updateBill(bill) }) {
                dialog.dismiss(); toast("Tagihan disimpan"); financeUi.planTab = 1; financeReaderTool(FINANCE_TAB_PLAN)
                // Pengingat memakai notifikasi: minta izin (Android 13+) bila belum diberikan.
                if (bill.enabled && !bill.autoPost && bill.remindDays >= 0 && android.os.Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 3001) }
                }
            }
        }
        if (existing != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            confirm("Hapus tagihan \"${existing.title}\"?", "Tagihan dan status lunasnya dihapus. Transaksi pembayaran yang sudah tercatat tetap ada.") {
                db.deleteBill(existing.id); dialog.dismiss(); toast("Tagihan dihapus"); financeRefresh()
            }
        }
    }
    dialog.show()
}

internal fun MainActivity.showPayBill(db: FinanceDb, b: FinanceBill, month: FinMonth) {
    val now = System.currentTimeMillis()
    var ts = if (FinMonth.of(now) == month) now else Calendar.getInstance().apply {
        clear(); set(month.year, month.month - 1, FinCalc.effectiveDueDay(b.dayOfMonth, month), 12, 0)
    }.timeInMillis
    var method = b.method
    val form = finForm()
    form.addView(finText("${b.title} · ${month.label}", 13f, muted = true).apply { setPadding(0, dp(8), 0, 0) })
    val amount = moneyField(form, "Nominal dibayar (Rp)", b.amount)
    form.addView(finCaption("Metode")); form.addView(finChips(FinMethods.ALL, method) { method = it })
    form.addView(finCaption("Tanggal bayar")); form.addView(finDateButton(ts) { ts = it }, LinearLayout.LayoutParams(-1, -2))
    form.addView(finText("Dicatat sebagai ${FinType.label(b.type).lowercase(Locale.ROOT)} kategori ${b.category}.", 11f, muted = true).apply { setPadding(0, dp(8), 0, 0) })
    val dialog = finDialog(if (b.type == FinType.OUT) "Bayar tagihan" else "Terima pembayaran", form, "Tandai lunas")
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a = amount.checkMoney() ?: return@setOnClickListener
            if (finTry("Gagal") { db.markBillPaid(b.copy(method = method), month, ts, a) }) { dialog.dismiss(); toast("${b.title} lunas untuk ${month.label}"); financeRefresh() }
        }
    }
    dialog.show()
}

internal fun MainActivity.confirmUnpayBill(db: FinanceDb, b: FinanceBill, month: FinMonth) {
    confirm("Batalkan status lunas?", "${b.title} untuk ${month.label} akan ditandai belum dibayar dan transaksi pembayarannya dihapus.", "Batalkan lunas") {
        if (finTry("Gagal") { db.unmarkBillPaid(b.id, month) }) { toast("Status lunas dibatalkan"); financeRefresh() }
    }
}

// ---------------------------------------------------------------- utang / piutang

internal fun MainActivity.showDebtEditor(db: FinanceDb, existing: FinanceDebt?) {
    val form = finForm()
    val kinds = listOf("Utang saya (saya meminjam)", "Piutang (orang lain meminjam)")
    val kindSp = finSpinner(kinds, if (existing?.kind == FinanceDebt.KIND_RECEIVABLE) kinds[1] else kinds[0])
    form.addView(finCaption("Jenis")); form.addView(kindSp, LinearLayout.LayoutParams(-1, dp(48)))
    form.addView(finCaption("Nama orang / pihak"))
    val person = edit("mis. Budi, Bank X").apply { setText(existing?.person.orEmpty()) }
    form.addView(person, LinearLayout.LayoutParams(-1, dp(52)))
    val amount = moneyField(form, "Jumlah pinjaman (Rp)", existing?.amount)
    form.addView(finCaption("Catatan (opsional)"))
    val note = edit("mis. untuk biaya servis").apply { setText(existing?.note.orEmpty()) }
    form.addView(note, LinearLayout.LayoutParams(-1, dp(52)))
    var due = existing?.dueDate ?: 0L
    form.addView(finCaption("Jatuh tempo (opsional)"))
    form.addView(finDateButton(due, allowClear = true, emptyLabel = "Tanpa jatuh tempo") { due = it }, LinearLayout.LayoutParams(-1, -2))
    val record = CheckBox(this).apply {
        text = "Catat uang pinjaman sebagai transaksi (utang = pemasukan, piutang = pengeluaran)"
        setTextColor(textMain); minHeight = dp(48)
    }
    if (existing == null) form.addView(record)
    val dialog = finDialog(if (existing == null) "Catat utang/piutang" else "Ubah utang/piutang", form)
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val p = person.text.toString().trim()
            if (p.isBlank()) { person.error = "Nama wajib diisi"; person.requestFocus(); return@setOnClickListener }
            val a = amount.checkMoney() ?: return@setOnClickListener
            val kind = if (kindSp.selectedItemPosition == 1) FinanceDebt.KIND_RECEIVABLE else FinanceDebt.KIND_PAYABLE
            val ok = finTry("Gagal menyimpan") {
                if (existing == null) {
                    db.addDebt(FinanceDebt(kind = kind, person = p, amount = a, note = note.text.toString(), dueDate = due))
                    if (record.isChecked) db.insertTx(FinanceTx(timestamp = System.currentTimeMillis(), type = if (kind == FinanceDebt.KIND_PAYABLE) FinType.IN else FinType.OUT,
                        amount = a, category = if (kind == FinanceDebt.KIND_PAYABLE) "Utang" else "Piutang",
                        merchant = (if (kind == FinanceDebt.KIND_PAYABLE) "Pinjaman dari " else "Pinjaman ke ") + p, sourceApp = "debt", rawText = "", manual = true))
                } else {
                    val paid = db.debtPayments(existing.id).sumOf { it.amount }
                    db.updateDebt(existing.copy(kind = kind, person = p, amount = a, note = note.text.toString(), dueDate = due, settled = existing.settled || paid >= a))
                }
            }
            if (ok) { dialog.dismiss(); toast("Tersimpan"); financeUi.planTab = 2; financeReaderTool(FINANCE_TAB_PLAN) }
        }
    }
    dialog.show()
}

internal fun MainActivity.showDebtDetail(db: FinanceDb, d: FinanceDebt) {
    val s = db.debtStatus(d)
    val pays = db.debtPayments(d.id)
    val form = finForm()
    form.addView(finText(if (s.settled) "Lunas" else "Sisa ${FinMoney.rp(s.remaining)}", 18f, bold = true).apply { setPadding(0, dp(8), 0, dp(4)) })
    form.addView(finText("Dibayar ${FinMoney.rp(s.paid)} dari ${FinMoney.rp(d.amount)}" + if (s.overpaid > 0) " (lebih ${FinMoney.rp(s.overpaid)})" else "", 12f, muted = true))
    form.addView(finProgress(if (s.settled) 1000 else FinMoney.permille(s.paid, d.amount)).apply { setPadding(0, dp(8), 0, dp(4)) })
    if (d.dueDate > 0) form.addView(finText("Jatuh tempo ${finDateText(d.dueDate)}", 11.5f, muted = true))
    if (d.note.isNotBlank()) form.addView(finText(d.note, 12f).apply { setPadding(0, dp(6), 0, 0) })
    form.addView(finCaption(if (pays.isEmpty()) "Belum ada pembayaran" else "Riwayat pembayaran · ketuk untuk menghapus"))
    lateinit var dialog: AlertDialog
    pays.forEach { p ->
        form.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44)
            background = rippleBg(Color.TRANSPARENT, Ds.RADIUS_SM)
            addView(finText(finDateText(p.ts) + (if (p.note.isNotBlank()) " · ${p.note}" else "") + (if (p.txId == null) " · tanpa transaksi" else ""), 12f, muted = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(finText(FinMoney.rp(p.amount), 13f, bold = true))
            setOnClickListener {
                confirm("Hapus pembayaran?", "${FinMoney.rp(p.amount)} pada ${finDateText(p.ts)}." + (if (p.txId != null) " Transaksi kas terkait ikut dihapus." else "")) {
                    if (finTry("Gagal") { db.deleteDebtPayment(p) }) { dialog.dismiss(); toast("Pembayaran dihapus"); financeRefresh() }
                }
            }
        })
    }
    val payLabel = if (d.isPayable) "Bayar" else "Terima"
    val b = AlertDialog.Builder(this).setTitle((if (d.isPayable) "Utang ke " else "Piutang dari ") + d.person).setView(ScrollView(this).apply { addView(form) })
        .setNegativeButton("Tutup", null)
        .setNeutralButton("Lainnya…") { _, _ ->
            val opts = arrayOf("Ubah", if (s.settled) "Aktifkan lagi (belum lunas)" else "Tandai lunas", "Hapus")
            AlertDialog.Builder(this).setItems(opts) { _, w ->
                when (w) {
                    0 -> showDebtEditor(db, d)
                    1 -> if (s.settled) { db.setDebtSettled(d.id, false); financeRefresh() } else confirm("Tandai lunas?", "Sisa ${FinMoney.rp(s.remaining)} dianggap selesai tanpa mencatat pembayaran.", "Tandai lunas") {
                        db.setDebtSettled(d.id, true); toast("Ditandai lunas"); financeRefresh()
                    }
                    2 -> confirm("Hapus catatan ini?", "Catatan dan riwayat pembayarannya dihapus. Transaksi kas yang sudah tercatat tetap ada di Riwayat.") {
                        db.deleteDebt(d.id); toast("Dihapus"); financeRefresh()
                    }
                }
            }.show()
        }
    if (!s.settled) b.setPositiveButton(payLabel) { _, _ -> showDebtPayment(db, d) }
    dialog = b.create()
    dialog.show()
}

internal fun MainActivity.showDebtPayment(db: FinanceDb, d: FinanceDebt) {
    val s = db.debtStatus(d)
    val form = finForm()
    form.addView(finText("Sisa ${FinMoney.rp(s.remaining)}", 13f, muted = true).apply { setPadding(0, dp(8), 0, 0) })
    val amount = moneyField(form, "Nominal (Rp)", s.remaining)
    var ts = System.currentTimeMillis()
    form.addView(finCaption("Tanggal")); form.addView(finDateButton(ts) { ts = it }, LinearLayout.LayoutParams(-1, -2))
    form.addView(finCaption("Catatan (opsional)"))
    val note = edit("mis. cicilan ke-2")
    form.addView(note, LinearLayout.LayoutParams(-1, dp(52)))
    var method = "Transfer"
    form.addView(finCaption("Metode")); form.addView(finChips(FinMethods.ALL, method) { method = it })
    val wallets = db.wallets()
    val walletSp = finSpinner(wallets)
    if (wallets.size > 1) { form.addView(finCaption("Dompet")); form.addView(walletSp, LinearLayout.LayoutParams(-1, dp(48))) }
    val record = CheckBox(this).apply {
        text = if (d.isPayable) "Catat sebagai pengeluaran (kategori Utang)" else "Catat sebagai pemasukan (kategori Piutang)"
        isChecked = true; setTextColor(textMain); minHeight = dp(48)
    }
    form.addView(record)
    val dialog = finDialog(if (d.isPayable) "Bayar utang ke ${d.person}" else "Terima dari ${d.person}", form)
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a = amount.checkMoney() ?: return@setOnClickListener
            if (a > s.remaining) { amount.error = "Melebihi sisa ${FinMoney.rp(s.remaining)}"; amount.requestFocus(); return@setOnClickListener }
            if (finTry("Gagal menyimpan") {
                    db.addDebtPayment(d, a, ts, note.text.toString(), record.isChecked, if (wallets.size > 1) walletSp.selectedItem?.toString() ?: "Umum" else wallets.first(), method)
                }) {
                dialog.dismiss()
                toast(if (a == s.remaining) "Lunas" else "Tersimpan · sisa ${FinMoney.rp(s.remaining - a)}")
                financeRefresh()
            }
        }
    }
    dialog.show()
}

// ---------------------------------------------------------------- kategori & dompet

internal fun MainActivity.showCustomCategoryDialog() {
    val input = edit("Nama kategori baru")
    val form = finForm().apply { addView(input, LinearLayout.LayoutParams(-1, dp(52))) }
    AlertDialog.Builder(this).setTitle("Kategori kustom").setView(form).setPositiveButton("Simpan") { _, _ ->
        val name = input.text.toString().trim().take(40)
        if (name.isBlank() || name == FinanceDb.TOTAL_BUDGET) { toast("Nama kategori kosong"); return@setPositiveButton }
        val arr = runCatching { JSONArray(prefs.getString("finance_custom_categories", "[]") ?: "[]") }.getOrElse { JSONArray() }
        if ((0 until arr.length()).any { arr.optString(it).equals(name, true) } || FinanceCategories.ALL.any { it.equals(name, true) }) toast("Kategori sudah ada")
        else { arr.put(name); prefs.edit().putString("finance_custom_categories", arr.toString()).apply(); toast("Kategori ditambahkan") }
    }.setNegativeButton("Batal", null).show()
}

internal fun MainActivity.showWalletDialog(db: FinanceDb) {
    val ws = db.walletsWithBalances()
    val items = (ws.map { (n, _) -> "$n · saldo ${FinMoney.rp(db.walletBalance(n))}" } + "+ Tambah dompet").toTypedArray()
    AlertDialog.Builder(this).setTitle("Dompet & rekening").setItems(items) { _, which ->
        if (which == ws.size) {
            val form = finForm()
            form.addView(finCaption("Nama dompet"))
            val n = edit("BCA, GoPay, Tunai…")
            form.addView(n, LinearLayout.LayoutParams(-1, dp(52)))
            form.addView(finCaption("Saldo awal (Rp)"))
            val bal = moneyEdit("0")
            form.addView(bal, LinearLayout.LayoutParams(-1, dp(52)))
            AlertDialog.Builder(this).setTitle("Tambah dompet").setView(form).setPositiveButton("Simpan") { _, _ ->
                val name = n.text.toString().trim()
                if (name.isBlank()) toast("Nama kosong")
                else if (db.addWallet(name, bal.moneyValue() ?: 0L)) { toast("Dompet ditambahkan"); financeRefresh() } else toast("Dompet sudah ada")
            }.setNegativeButton("Batal", null).show()
        } else {
            val name = ws[which].first
            val count = db.transactionCountForWallet(name)
            if (name == "Umum" || count > 0) {
                AlertDialog.Builder(this).setTitle(name).setMessage(if (name == "Umum") "Dompet bawaan tidak dapat dihapus." else "Dompet dipakai oleh $count transaksi sehingga tidak dapat dihapus.").setPositiveButton("OK", null).show()
            } else confirm("Hapus dompet \"$name\"?", "Dompet tanpa transaksi akan dihapus beserta saldo awalnya.") {
                if (db.deleteWallet(name)) { toast("Dompet dihapus"); financeRefresh() } else toast("Gagal menghapus")
            }
        }
    }.setNegativeButton("Tutup", null).show()
}

internal fun MainActivity.showFinancePrivacyGuide() {
    AlertDialog.Builder(this).setTitle("Privasi Keuangan")
        .setMessage("GITLS tidak membaca notifikasi aplikasi lain dan tidak mengirim data keuangan ke server. Semua transaksi, anggaran, tagihan, dan utang disimpan di perangkat. Ekspor/backup hanya ke lokasi yang Anda pilih.")
        .setPositiveButton("OK", null).show()
}

internal fun MainActivity.confirmClearFinance(db: FinanceDb) {
    confirm("Hapus semua data keuangan?", "Semua transaksi, anggaran, target, tagihan, utang/piutang, dan dompet akan dihapus permanen. Buat backup JSON terlebih dahulu bila masih diperlukan.", "Hapus semua") {
        db.clearAll()
        prefs.edit().remove("finance_custom_categories").apply()
        toast("Data keuangan dihapus")
        financeRefresh()
    }
}

// ---------------------------------------------------------------- ekspor / impor / backup (SAF)

private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

internal fun MainActivity.startFinanceCsvExport() {
    pendingFinanceExportJson = false
    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = "text/csv"; putExtra(Intent.EXTRA_TITLE, "gitls_keuangan_${stamp()}.csv")
    }, FINANCE_EXPORT_CREATE)
}

internal fun MainActivity.startFinanceCsvImport() {
    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel"))
    }, FINANCE_CSV_OPEN)
}

internal fun MainActivity.createFinanceBackup() {
    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "gitls_keuangan_backup_${stamp()}.json")
    }, FINANCE_BACKUP_CREATE)
}

internal fun MainActivity.openFinanceBackup() {
    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE) }, FINANCE_BACKUP_OPEN)
}

internal fun MainActivity.financeJson(db: FinanceDb): JSONObject =
    db.backupJson().put("customCategories", runCatching { JSONArray(prefs.getString("finance_custom_categories", "[]") ?: "[]") }.getOrElse { JSONArray() })

internal fun MainActivity.financeCsv(db: FinanceDb): String = "\uFEFF" + FinCsv.format(db.exportRows())

internal fun MainActivity.restoreFinanceJson(db: FinanceDb, root: JSONObject) {
    runCatching {
        val count = db.restoreFromBackup(root)
        val cats = root.optJSONArray("customCategories") ?: JSONArray()
        prefs.edit().putString("finance_custom_categories", cats.toString()).apply()
        toast("Backup dipulihkan: $count transaksi")
        financeRefresh()
    }.onFailure { toast("Restore gagal: ${it.message}") }
}

/** Jalankan [work] di thread latar lalu [done] di UI thread. */
private fun <T> MainActivity.finBackground(work: () -> T, done: (Result<T>) -> Unit) {
    Thread {
        val r = runCatching(work)
        runOnUiThread { if (!isFinishing && !isDestroyed) done(r) }
    }.start()
}

private fun MainActivity.readTextLimited(uri: android.net.Uri): String {
    val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("File tidak dapat dibuka")
    input.use {
        val bytes = it.readBytes()
        require(bytes.size <= CSV_MAX_BYTES) { "File lebih dari 5 MB" }
        return String(bytes, StandardCharsets.UTF_8)
    }
}

/** Tangani hasil SAF untuk ekspor/impor keuangan. @return true bila requestCode milik keuangan. */
internal fun MainActivity.handleFinanceActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    if (requestCode != FINANCE_EXPORT_CREATE && requestCode != FINANCE_BACKUP_CREATE && requestCode != FINANCE_BACKUP_OPEN && requestCode != FINANCE_CSV_OPEN) return false
    val uri = data?.data
    if (resultCode != Activity.RESULT_OK || uri == null) { if (requestCode == FINANCE_EXPORT_CREATE) pendingFinanceExportJson = false; return true }
    val db = FinanceDb(this)
    when (requestCode) {
        FINANCE_EXPORT_CREATE -> {
            val json = pendingFinanceExportJson
            pendingFinanceExportJson = false
            finBackground({
                val bytes = (if (json) financeJson(db).toString(2) else financeCsv(db)).toByteArray(StandardCharsets.UTF_8)
                (contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Lokasi tidak dapat ditulis")).use { it.write(bytes) }
                db.allTx().size
            }) { r -> r.onSuccess { toast("Ekspor berhasil: $it transaksi") }.onFailure { toast("Ekspor gagal: ${it.message}") } }
        }
        FINANCE_BACKUP_CREATE -> finBackground({
            (contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Lokasi tidak dapat ditulis")).use { it.write(financeJson(db).toString(2).toByteArray(StandardCharsets.UTF_8)) }
        }) { r -> r.onSuccess { toast("Backup berhasil disimpan") }.onFailure { toast("Backup gagal: ${it.message}") } }
        FINANCE_BACKUP_OPEN -> finBackground({ JSONObject(readTextLimited(uri)) }) { r ->
            r.onSuccess { root ->
                val n = root.optJSONArray("transactions")?.length() ?: 0
                val created = root.optLong("createdAt", 0L)
                confirm("Ganti data keuangan?", "Backup berisi $n transaksi" + (if (created > 0) " (dibuat ${finDateText(created, "d MMM yyyy HH:mm")})" else "") +
                    ".\n\nSemua data keuangan saat ini (transaksi, anggaran, target, tagihan, utang) akan DIGANTI dengan isi backup. Buat backup data saat ini terlebih dahulu bila masih diperlukan.", "Pulihkan") {
                    restoreFinanceJson(db, root)
                }
            }.onFailure { toast("Backup tidak valid: ${it.message}") }
        }
        FINANCE_CSV_OPEN -> finBackground({ FinCsv.parse(readTextLimited(uri)) }) { r ->
            r.onSuccess { showCsvImportPreview(db, it) }.onFailure { toast("Impor gagal: ${it.message}") }
        }
    }
    return true
}

private fun MainActivity.showCsvImportPreview(db: FinanceDb, res: FinCsvImport) {
    val sb = StringBuilder()
    sb.append("${res.dataLines} baris data dibaca.\n")
    sb.append("${res.rows.size} baris valid, ${res.skipped.size} dilewati.\n")
    if (res.rows.isNotEmpty()) {
        val sum = FinCalc.summarize(res.rows.map { FinEntry(it.ts, it.type, it.amount, it.category) })
        sb.append("Total masuk ${FinMoney.rp(sum.income)}, keluar ${FinMoney.rp(sum.expense)}.\n")
    }
    if (res.skipped.isNotEmpty()) {
        sb.append("\nBaris yang dilewati:\n")
        res.skipped.take(30).forEach { sb.append("• Baris ${it.line}: ${it.reason}\n") }
        if (res.skipped.size > 30) sb.append("• … dan ${res.skipped.size - 30} lainnya\n")
    }
    sb.append("\nFormat kolom: tanggal (yyyy-MM-dd atau dd/MM/yyyy), tipe (masuk/keluar), nominal, kategori, metode, catatan, dompet. Transaksi yang sama persis (duplikat) tidak diimpor ulang.")
    val b = AlertDialog.Builder(this).setTitle("Impor CSV").setMessage(sb.toString()).setNegativeButton(if (res.rows.isEmpty()) "Tutup" else "Batal", null)
    if (res.rows.isNotEmpty()) b.setPositiveButton("Impor ${res.rows.size}") { _, _ ->
        finBackground({ db.importRows(res.rows) }) { r ->
            r.onSuccess { (added, dup) ->
                AlertDialog.Builder(this).setTitle("Impor selesai")
                    .setMessage("$added transaksi ditambahkan." + (if (dup > 0) "\n$dup duplikat dilewati." else "") + (if (res.skipped.isNotEmpty()) "\n${res.skipped.size} baris tidak valid dilewati." else ""))
                    .setPositiveButton("OK", null).show()
                if (currentPage == "Pengelola Keuangan") financeRefresh() else MyToolsWidget.update(this)
            }.onFailure { toast("Impor gagal: ${it.message}") }
        }
    }
    b.show()
}
