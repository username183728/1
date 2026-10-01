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
 * Finance dialogs and export/backup actions.
 */

internal fun MainActivity.showFinanceActions() {
    val items = arrayOf(
        "Tambah transaksi", "Finance Dashboard", "Cari & filter transaksi", "Kelola rekening / wallet", "Atur anggaran",
        "Transaksi berulang", "Target tabungan", "Tambah kategori kustom", "Ekspor CSV", "Ekspor JSON",
        "Backup data", "Restore backup", "Laporan PDF", "Bukti transaksi terakhir", "Izin & privasi", "Hapus semua data"
    )
    AlertDialog.Builder(this).setTitle("Keuangan").setItems(items) { _, which ->
        val db = FinanceDb(this)
        when (which) {
            0 -> showAddTxDialog(db)
            1 -> financeDashboardTool()
            2 -> showFinanceFilterDialog(db)
            3 -> showWalletDialog(db)
            4 -> showBudgetDialog(db)
            5 -> showRecurringDialog(db)
            6 -> showSavingsGoalDialog(db)
            7 -> showCustomCategoryDialog()
            8 -> createFinanceExport(db, false)
            9 -> createFinanceExport(db, true)
            10 -> createFinanceBackup(db)
            11 -> openFinanceBackup()
            12 -> shareFileAsync("Laporan gagal") { FinanceReport.createPdf(this, db) }
            13 -> db.listTx(1).firstOrNull()?.let { tx -> shareFileAsync("Struk gagal") { FinanceReport.createReceipt(this, tx) } } ?: toast("Belum ada transaksi")
            14 -> showFinancePrivacyGuide()
            15 -> confirmClearFinance(db)
        }
    }.show()
}

internal fun MainActivity.confirmClearFinance(db: FinanceDb) {
    AlertDialog.Builder(this).setTitle("Hapus semua data?")
        .setMessage("Semua transaksi, anggaran, target dan transaksi berulang akan dihapus permanen.")
        .setPositiveButton("Hapus") { _, _ ->
            db.clearAll()
            prefs.edit().remove("finance_custom_categories").apply()
            toast("Data keuangan dihapus")
            financeReaderTool()
        }
        .setNegativeButton("Batal", null).show()
}

internal fun MainActivity.financeCategories(): List<String> {
    val custom = runCatching { JSONArray(prefs.getString("finance_custom_categories", "[]") ?: "[]") }.getOrElse { JSONArray() }
    val out = FinanceCategories.ALL.toMutableList()
    for (i in 0 until custom.length()) { val v=custom.optString(i).trim(); if(v.isNotBlank()&&!out.contains(v))out.add(v) }
    return out
}

internal fun MainActivity.showCustomCategoryDialog() {
    val input=edit("Nama kategori baru")
    AlertDialog.Builder(this).setTitle("Kategori Kustom").setView(input).setPositiveButton("Simpan"){_,_->
        val name=input.text.toString().trim(); if(name.isBlank()){toast("Nama kategori kosong");return@setPositiveButton}
        val arr=runCatching{JSONArray(prefs.getString("finance_custom_categories","[]")?:"[]")}.getOrElse{JSONArray()}
        if((0 until arr.length()).any{arr.optString(it).equals(name,true)}||FinanceCategories.ALL.any{it.equals(name,true)})toast("Kategori sudah ada")
        else{arr.put(name);prefs.edit().putString("finance_custom_categories",arr.toString()).apply();toast("Kategori ditambahkan")}
    }.setNegativeButton("Batal",null).show()
}

internal fun MainActivity.showFinanceFilterDialog(db: FinanceDb) {
    val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),0)}
    val q=edit("Merchant, catatan, bank/e-wallet"); q.setText(financeSearchQuery)
    val cat=Spinner(this).apply{adapter=ArrayAdapter(this@showFinanceFilterDialog,android.R.layout.simple_spinner_dropdown_item,(listOf("Semua")+financeCategories()).toTypedArray())}
    val wallets=listOf("Semua")+db.wallets(); val wal=Spinner(this).apply{adapter=ArrayAdapter(this@showFinanceFilterDialog,android.R.layout.simple_spinner_dropdown_item,wallets.toTypedArray())}
    val min=edit("Nominal minimum (Rp)"); val max=edit("Nominal maksimum (Rp)")
    box.addView(q);box.addView(cat,LinearLayout.LayoutParams(-1,dp(48)));box.addView(wal,LinearLayout.LayoutParams(-1,dp(48)));box.addView(min);box.addView(max)
    AlertDialog.Builder(this).setTitle("Cari & Filter").setView(box).setPositiveButton("Terapkan"){_,_->
        financeSearchQuery=q.text.toString();financeCategoryFilter=cat.selectedItem?.toString() ?: "Semua";financeWalletFilter=wal.selectedItem?.toString() ?: "Semua"
        val mi=min.num();val ma=max.num();renderFinanceTransactions(db,db.searchTx(financeSearchQuery,financeCategoryFilter,financeWalletFilter,mi,ma))
    }.setNeutralButton("Reset"){_,_->financeSearchQuery="";financeCategoryFilter="Semua";financeWalletFilter="Semua";financeReaderTool()}.setNegativeButton("Batal",null).show()
}

internal fun MainActivity.renderFinanceTransactions(db: FinanceDb, txs: List<FinanceTx>) {
    var header = -1
    for (i in 0 until content.childCount) {
        val v = content.getChildAt(i)
        if (v is TextView && v.text.toString().startsWith("TRANSAKSI")) { header = i; break }
    }
    if (header >= 0) {
        content.removeViews(header + 1, content.childCount - header - 1)
        content.addView(subLabel(if (txs.isEmpty()) "Tidak ada transaksi sesuai filter." else "${txs.size} transaksi ditemukan."), header + 1)
        txs.forEach { content.addView(financeTxRow(db, it)) }
    }
}

internal fun MainActivity.showWalletDialog(db: FinanceDb) {
    val names=db.wallets(); val items=(names+"+ Tambah wallet").toTypedArray()
    AlertDialog.Builder(this).setTitle("Rekening / Wallet").setItems(items){_,which->
        if(which==names.size){val n=edit("Nama wallet (BCA, Mandiri, GoPay, Cash…)");AlertDialog.Builder(this).setTitle("Tambah wallet").setView(n).setPositiveButton("Simpan"){_,_->if(n.text.toString().trim().isNotBlank()){db.addWallet(n.text.toString().trim());toast("Wallet ditambahkan");financeReaderTool()}}.setNegativeButton("Batal",null).show()}
        else showWalletDetailDialog(db,names[which])
    }.setPositiveButton("Tutup",null).show()
}

internal fun MainActivity.showWalletDetailDialog(db: FinanceDb,name:String){AlertDialog.Builder(this).setTitle(name).setMessage("Wallet aktif. Transaksi baru dapat diarahkan ke wallet ini saat pencatatan manual.").setNeutralButton("Hapus"){_,_->db.deleteWallet(name);financeReaderTool()}.setPositiveButton("OK",null).show()}

internal fun MainActivity.showRecurringDialog(db: FinanceDb){
    val existing=db.recurring(); val labels=existing.map{"${it[1]} • Rp${fmtRupiah(it[2] as Double)} • tanggal ${it[6]}"}.toMutableList(); labels.add("+ Tambah transaksi berulang");
    AlertDialog.Builder(this)
        .setTitle("Transaksi Berulang")
        .setItems(labels.toTypedArray()) { _, which ->
            if (which == existing.size) {
                showAddRecurring(db)
            } else {
                db.processDueRecurring()
                toast("Transaksi berulang diperiksa")
                financeReaderTool()
            }
        }
        .setPositiveButton("Proses yang jatuh tempo") { _, _ ->
            val n = db.processDueRecurring()
            toast(if (n > 0) "$n transaksi dibuat" else "Tidak ada transaksi jatuh tempo")
            financeReaderTool()
        }
        .show()
}

internal fun MainActivity.showAddRecurring(db: FinanceDb){
    val titleIn=edit("Nama tagihan / transaksi");val amount=edit("Nominal (Rp)");val day=edit("Tanggal setiap bulan (1-28)");val cat=Spinner(this).apply{adapter=ArrayAdapter(this@showAddRecurring,android.R.layout.simple_spinner_dropdown_item,financeCategories().toTypedArray())};val type=Spinner(this).apply{adapter=ArrayAdapter(this@showAddRecurring,android.R.layout.simple_spinner_dropdown_item,arrayOf("Pengeluaran","Pemasukan"))};val wallet=Spinner(this).apply{adapter=ArrayAdapter(this@showAddRecurring,android.R.layout.simple_spinner_dropdown_item,db.wallets().toTypedArray())}
    val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),0)};box.addView(titleIn);box.addView(amount);box.addView(day);box.addView(type);box.addView(cat);box.addView(wallet)
    AlertDialog.Builder(this).setTitle("Tambah transaksi berulang").setView(box).setPositiveButton("Simpan"){_,_->val a=amount.num();val d=day.text.toString().toIntOrNull();if(a!=null&&a>0&&d!=null){db.addRecurring(titleIn.text.toString(),a,if(type.selectedItemPosition==0)"keluar" else "masuk",cat.selectedItem.toString(),wallet.selectedItem.toString(),d);toast("Transaksi berulang disimpan");financeReaderTool()}else toast("Data tidak valid")}.setNegativeButton("Batal",null).show()
}

internal fun MainActivity.showSavingsGoalDialog(db: FinanceDb){
    val goals=db.goals();val labels=goals.map{"${it[1]} • target Rp${fmtRupiah(it[2] as Double)}"}.toMutableList();labels.add("+ Tambah target tabungan")
    AlertDialog.Builder(this).setTitle("Target Tabungan").setItems(labels.toTypedArray()){_,which->if(which==goals.size){val n=edit("Nama target");val a=edit("Target (Rp)");AlertDialog.Builder(this).setTitle("Target baru").setView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),0);addView(n);addView(a)}).setPositiveButton("Simpan"){_,_->val v=a.num();if(v!=null&&v>0){db.addGoal(n.text.toString(),v);toast("Target dibuat");financeReaderTool()}}.setNegativeButton("Batal",null).show()}else{val g=goals[which];val name=g[1] as String;val current=db.goalProgress(name);val target=g[2] as Double;val pct=(current/target*100).coerceIn(0.0,100.0);AlertDialog.Builder(this).setTitle(name).setMessage("Progress: Rp${fmtRupiah(current)} / Rp${fmtRupiah(target)} (${pct.toInt()}%)").setNeutralButton("Tambah kontribusi"){_,_->val a=edit("Nominal kontribusi (Rp)");AlertDialog.Builder(this).setTitle("Kontribusi $name").setView(a).setPositiveButton("Simpan"){_,_->val v=a.num();if(v!=null&&v>0){db.insertTx(FinanceTx(timestamp=System.currentTimeMillis(),type="keluar",amount=v,category="Tabungan",merchant=name,sourceApp="savings-goal",rawText="Kontribusi target tabungan",manual=true));toast("Kontribusi disimpan");financeReaderTool()}}.setNegativeButton("Batal",null).show()}.setPositiveButton("OK",null).show()}}.setPositiveButton("Tutup",null).show()
}


internal fun MainActivity.createFinanceExport(db: FinanceDb,json:Boolean){val ext=if(json)"json" else "csv";val mime=if(json)"application/json" else "text/csv";startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type=mime;putExtra(Intent.EXTRA_TITLE,"mytools_transaksi_${SimpleDateFormat("yyyyMMdd_HHmm",Locale.US).format(Date())}.$ext")},FINANCE_EXPORT_CREATE);pendingFinanceExportJson=json}
// (var moved / kept on MainActivity) internal var pendingFinanceExportJson=false
internal fun MainActivity.createFinanceBackup(db:FinanceDb){startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/json";putExtra(Intent.EXTRA_TITLE,"mytools_finance_backup_${SimpleDateFormat("yyyyMMdd_HHmm",Locale.US).format(Date())}.json")},FINANCE_BACKUP_CREATE)}
internal fun MainActivity.openFinanceBackup(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="application/json";addCategory(Intent.CATEGORY_OPENABLE)},FINANCE_BACKUP_OPEN)}

internal fun MainActivity.financeJson(db:FinanceDb):JSONObject{
    val root=JSONObject().apply{put("format","mytools-finance-backup");put("version",3);put("createdAt",System.currentTimeMillis())}
    val txs=JSONArray();db.listTx(100000).forEach{t->txs.put(JSONObject().apply{put("id",t.id);put("timestamp",t.timestamp);put("type",t.type);put("amount",t.amount);put("category",t.category);put("merchant",t.merchant);put("sourceApp",t.sourceApp);put("rawText",t.rawText);put("manual",t.manual);put("wallet",t.walletName)})};root.put("transactions",txs)
    val budgets=JSONObject();db.getBudgets().forEach{(k,v)->budgets.put(k,v)};root.put("budgets",budgets)
    val wallets=JSONArray();db.walletsWithBalances().forEach{(name,balance)->wallets.put(JSONObject().apply{put("name",name);put("openingBalance",balance)})};root.put("wallets",wallets)
    root.put("recurring",JSONArray(db.recurringForBackup()));root.put("goals",JSONArray(db.goalsForBackup()));root.put("splits",JSONArray(db.splitsForBackup()))
    root.put("customCategories",JSONArray(prefs.getString("finance_custom_categories","[]")?:"[]"));return root
}
internal fun MainActivity.financeCsv(db: FinanceDb): String {
    val sb = StringBuilder("timestamp,type,amount,category,merchant,wallet,source_app,manual,raw_text\n")
    fun q(v: String): String = "\"" + v.replace("\"", "\"\"").replace("\n", " ") + "\""
    db.listTx(100000).forEach { t ->
        sb.append(t.timestamp).append(',')
            .append(t.type).append(',')
            .append(t.amount).append(',')
            .append(q(t.category)).append(',')
            .append(q(t.merchant)).append(',')
            .append(q(t.walletName)).append(',')
            .append(q(t.sourceApp)).append(',')
            .append(t.manual).append(',')
            .append(q(t.rawText)).append('\n')
    }
    return sb.toString()
}

internal fun MainActivity.restoreFinanceJson(db:FinanceDb,root:JSONObject){
    runCatching {
        val count=db.restoreFromBackup(root)
        val cats=root.optJSONArray("customCategories")?:JSONArray()
        prefs.edit().putString("finance_custom_categories",cats.toString()).apply()
        toast("Backup dipulihkan: $count transaksi")
        financeReaderTool()
    }.onFailure { toast("Restore gagal: ${it.message}") }
}

internal fun MainActivity.showFinancePrivacyGuide() {
    AlertDialog.Builder(this).setTitle("Privasi Keuangan")
        .setMessage("MyTools tidak membaca notifikasi aplikasi lain dan tidak meminta akses Notification Listener. Pencatatan keuangan dilakukan manual atau melalui data yang Anda masukkan sendiri.")
        .setPositiveButton("OK", null).show()
}

internal fun MainActivity.showBudgetDialog(db:FinanceDb){val cat=Spinner(this).apply{adapter=ArrayAdapter(this@showBudgetDialog,android.R.layout.simple_spinner_dropdown_item,financeCategories().toTypedArray())};val limit=edit("Batas anggaran per bulan (Rp)");val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(4));addView(cat);addView(limit)};AlertDialog.Builder(this).setTitle("Atur Anggaran Kategori").setView(box).setPositiveButton("Simpan"){_,_->val v=limit.num();if(v!=null&&v>0){db.setBudget(cat.selectedItem.toString(),v);toast("Anggaran disimpan");financeReaderTool()}else toast("Nominal tidak valid")}.setNegativeButton("Batal",null).show()}
internal fun MainActivity.showAddTxDialog(db:FinanceDb, forceIncome: Boolean? = null){val type=Spinner(this).apply{adapter=ArrayAdapter(this@showAddTxDialog,android.R.layout.simple_spinner_dropdown_item,arrayOf("Pengeluaran","Pemasukan")); if(forceIncome != null) setSelection(if(forceIncome) 1 else 0)};val cat=Spinner(this).apply{adapter=ArrayAdapter(this@showAddTxDialog,android.R.layout.simple_spinner_dropdown_item,financeCategories().toTypedArray())};val wallet=Spinner(this).apply{adapter=ArrayAdapter(this@showAddTxDialog,android.R.layout.simple_spinner_dropdown_item,db.wallets().toTypedArray())};val amount=edit("Nominal (Rp)");val merchant=edit("Keterangan / merchant");val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(4));addView(type);addView(cat);addView(wallet);addView(amount);addView(merchant)};AlertDialog.Builder(this).setTitle("Tambah Transaksi Manual").setView(box).setPositiveButton("Simpan"){_,_->val a=amount.num();if(a==null||a<=0)toast("Nominal tidak valid")else{db.insertTx(FinanceTx(timestamp=System.currentTimeMillis(),type=if(type.selectedItemPosition==0)"keluar" else "masuk",amount=a,category=cat.selectedItem.toString(),merchant=merchant.text.toString(),sourceApp="manual",rawText="",manual=true,walletName=wallet.selectedItem.toString()));MyToolsWidget.update(this);toast("Transaksi ditambahkan");financeReaderTool()}}.setNegativeButton("Batal",null).show()}

// ===================== akhir Pengelola Keuangan =====================
