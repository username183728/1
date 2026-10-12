package com.example.aidetest

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * Database keuangan lokal.
 *
 * Kolom nominal lama bertipe REAL dipertahankan (tidak ada tabel berisi data yang di-drop);
 * semua nilai ditulis sebagai bilangan bulat dan dibaca kembali sebagai Long lewat ROUND,
 * sehingga perhitungan di aplikasi tetap memakai Long.
 *
 * v9: metode pembayaran per transaksi, saldo awal target tabungan, tagihan manual + pengingat,
 *     status bayar tagihan (recurring_run.tx_id), serta tabel utang/piutang.
 */
class FinanceDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "mytools_finance.db", null, 9) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tx (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, type TEXT NOT NULL, amount REAL NOT NULL, category TEXT NOT NULL, merchant TEXT, source_app TEXT, raw_text TEXT, manual INTEGER NOT NULL DEFAULT 0, wallet TEXT NOT NULL DEFAULT 'Umum', goal_id INTEGER, method TEXT NOT NULL DEFAULT 'Lainnya')")
        db.execSQL("CREATE TABLE budget (category TEXT PRIMARY KEY, limit_amount REAL NOT NULL)")
        db.execSQL("CREATE TABLE wallet (name TEXT PRIMARY KEY, opening_balance REAL NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE recurring (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, amount REAL NOT NULL, type TEXT NOT NULL, category TEXT NOT NULL, wallet TEXT NOT NULL, day_of_month INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, last_run INTEGER NOT NULL DEFAULT 0, auto_post INTEGER NOT NULL DEFAULT 1, remind_days INTEGER NOT NULL DEFAULT 1, method TEXT NOT NULL DEFAULT 'Lainnya')")
        db.execSQL("CREATE TABLE savings_goal (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, target REAL NOT NULL, deadline INTEGER NOT NULL DEFAULT 0, initial_saved REAL NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE split_tx (id INTEGER PRIMARY KEY AUTOINCREMENT, tx_id INTEGER NOT NULL, category TEXT NOT NULL, amount REAL NOT NULL)")
        db.execSQL("CREATE TABLE recurring_run (recurring_id INTEGER NOT NULL, month_key TEXT NOT NULL, executed_at INTEGER NOT NULL, tx_id INTEGER, PRIMARY KEY(recurring_id, month_key))")
        createDebtTables(db)
        db.execSQL("CREATE INDEX idx_split_tx_parent ON split_tx(tx_id)")
        db.execSQL("CREATE INDEX idx_tx_goal ON tx(goal_id)")
        db.execSQL("CREATE INDEX idx_tx_ts ON tx(ts DESC)")
        db.execSQL("CREATE INDEX idx_tx_wallet_ts ON tx(wallet, ts DESC)")
        db.execSQL("CREATE INDEX idx_tx_type_ts ON tx(type, ts DESC)")
        db.execSQL("CREATE INDEX idx_tx_category_ts ON tx(category, ts DESC)")
        db.execSQL("INSERT INTO wallet(name, opening_balance) VALUES('Umum',0)")
    }

    private fun createDebtTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS debt (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, person TEXT NOT NULL, amount INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, due_date INTEGER NOT NULL DEFAULT 0, settled INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS debt_payment (id INTEGER PRIMARY KEY AUTOINCREMENT, debt_id INTEGER NOT NULL, amount INTEGER NOT NULL, ts INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', tx_id INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_debt_payment_debt ON debt_payment(debt_id)")
    }

    /** Downgrade (mis. memasang versi lama di atas yang baru) tidak boleh membuat app crash atau menghapus data. */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { /* data dipertahankan apa adanya */ }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) if (idx >= 0 && c.getString(idx).equals(column, true)) return true
        }
        return false
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, ddl: String) {
        if (!hasColumn(db, table, column)) db.execSQL("ALTER TABLE $table ADD COLUMN $column $ddl")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) db.execSQL("ALTER TABLE tx ADD COLUMN wallet TEXT NOT NULL DEFAULT 'Umum'")
        if (oldV < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS wallet (name TEXT PRIMARY KEY, opening_balance REAL NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE IF NOT EXISTS recurring (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, amount REAL NOT NULL, type TEXT NOT NULL, category TEXT NOT NULL, wallet TEXT NOT NULL, day_of_month INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, last_run INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE IF NOT EXISTS savings_goal (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, target REAL NOT NULL, deadline INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT OR IGNORE INTO wallet(name, opening_balance) VALUES('Umum',0)")
        }
        if (oldV < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS split_tx (id INTEGER PRIMARY KEY AUTOINCREMENT, tx_id INTEGER NOT NULL, category TEXT NOT NULL, amount REAL NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_split_tx_parent ON split_tx(tx_id)")
        }
        if (oldV < 5) {
            // Permanently remove legacy notification-reader payloads from existing installs.
            db.execSQL("DROP TABLE IF EXISTS unparsed")
        }
        if (oldV < 6) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_ts ON tx(ts DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_wallet_ts ON tx(wallet, ts DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_type_ts ON tx(type, ts DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_category_ts ON tx(category, ts DESC)")
        }
        if (oldV < 7) {
            db.execSQL("CREATE TABLE IF NOT EXISTS recurring_run (recurring_id INTEGER NOT NULL, month_key TEXT NOT NULL, executed_at INTEGER NOT NULL, PRIMARY KEY(recurring_id, month_key))")
        }
        if (oldV < 8) {
            addColumnIfMissing(db, "tx", "goal_id", "INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_goal ON tx(goal_id)")
        }
        if (oldV < 9) {
            // Hanya ADD COLUMN + CREATE TABLE IF NOT EXISTS: seluruh baris lama dipertahankan.
            addColumnIfMissing(db, "tx", "method", "TEXT NOT NULL DEFAULT 'Lainnya'")
            addColumnIfMissing(db, "savings_goal", "initial_saved", "REAL NOT NULL DEFAULT 0")
            // Transaksi berulang lama tetap dicatat otomatis (auto_post=1).
            addColumnIfMissing(db, "recurring", "auto_post", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing(db, "recurring", "remind_days", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing(db, "recurring", "method", "TEXT NOT NULL DEFAULT 'Lainnya'")
            addColumnIfMissing(db, "recurring_run", "tx_id", "INTEGER")
            createDebtTables(db)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun money(c: Cursor, i: Int): Long = if (c.isNull(i)) 0L else Math.round(c.getDouble(i))
    private fun scalarLong(sql: String, args: Array<String>? = null): Long =
        readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L }

    private inline fun <T> tx(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val r = block(db)
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    private fun validAmount(v: Long): Boolean = v in 1..FinMoney.MAX

    // ---------------------------------------------------------------- transaksi

    private val txCols = "id,ts,type,amount,category,merchant,source_app,raw_text,manual,wallet,goal_id,method"

    private fun readTx(c: Cursor): FinanceTx = FinanceTx(
        id = c.getLong(0), timestamp = c.getLong(1), type = c.getString(2), amount = money(c, 3), category = c.getString(4),
        merchant = c.getString(5) ?: "", sourceApp = c.getString(6) ?: "", rawText = c.getString(7) ?: "", manual = c.getInt(8) == 1,
        walletName = c.getString(9) ?: "Umum", goalId = if (c.isNull(10)) null else c.getLong(10),
        method = c.getString(11) ?: FinMethods.DEFAULT
    )

    private fun txValues(t: FinanceTx): ContentValues = ContentValues().apply {
        put("ts", t.timestamp); put("type", t.type); put("amount", t.amount); put("category", t.category.trim())
        put("merchant", t.merchant); put("source_app", t.sourceApp); put("raw_text", t.rawText)
        put("manual", if (t.manual) 1 else 0); put("wallet", t.walletName.ifBlank { "Umum" })
        if (t.goalId != null) put("goal_id", t.goalId) else putNull("goal_id")
        put("method", if (t.method in FinMethods.ALL) t.method else FinMethods.DEFAULT)
    }

    private fun validateTx(t: FinanceTx) {
        require(validAmount(t.amount)) { "Nominal transaksi tidak valid" }
        require(t.type == FinType.IN || t.type == FinType.OUT) { "Tipe transaksi tidak valid" }
        require(t.category.isNotBlank()) { "Kategori transaksi kosong" }
    }

    fun insertTx(t: FinanceTx): Long {
        validateTx(t)
        return writableDatabase.insert("tx", null, txValues(t))
    }

    fun updateTx(t: FinanceTx): Boolean {
        require(t.id > 0) { "ID transaksi tidak valid" }
        validateTx(t)
        val old = getTx(t.id) ?: return false
        return tx { db ->
            val ok = db.update("tx", txValues(t), "id=?", arrayOf(t.id.toString())) > 0
            // Rincian split tidak lagi cocok bila nominal/tipe berubah.
            if (old.amount != t.amount || old.type != t.type) db.delete("split_tx", "tx_id=?", arrayOf(t.id.toString()))
            // Pembayaran utang yang terkait ikut menyesuaikan nominal.
            db.update("debt_payment", ContentValues().apply { put("amount", t.amount); put("ts", t.timestamp) }, "tx_id=?", arrayOf(t.id.toString()))
            linkedDebt(db, t.id)?.let { resyncDebtSettled(db, it) }
            ok
        }
    }

    /** Hapus transaksi beserta rinciannya; status bayar tagihan manual / pembayaran utang yang terkait ikut dibatalkan. */
    fun deleteTx(id: Long) {
        if (id <= 0) return
        tx { db ->
            val a = arrayOf(id.toString())
            db.delete("split_tx", "tx_id=?", a)
            db.delete("recurring_run", "tx_id=? AND recurring_id IN (SELECT id FROM recurring WHERE auto_post=0)", a)
            db.update("recurring_run", ContentValues().apply { putNull("tx_id") }, "tx_id=?", a)
            val debtId = linkedDebt(db, id)
            db.delete("debt_payment", "tx_id=?", a)
            debtId?.let { resyncDebtSettled(db, it) }
            db.delete("tx", "id=?", a)
        }
    }

    /** Info keterkaitan untuk pesan konfirmasi hapus. */
    fun txLinkInfo(id: Long): String? {
        val a = arrayOf(id.toString())
        if (scalarLong("SELECT COUNT(*) FROM debt_payment WHERE tx_id=?", a) > 0) return "Pembayaran utang/piutang yang terkait ikut dihapus."
        if (scalarLong("SELECT COUNT(*) FROM recurring_run r JOIN recurring b ON b.id=r.recurring_id WHERE r.tx_id=? AND b.auto_post=0", a) > 0) return "Status lunas tagihan bulan itu ikut dibatalkan."
        if (scalarLong("SELECT COUNT(*) FROM tx WHERE id=? AND goal_id IS NOT NULL", a) > 0) return "Setoran target tabungan ikut berkurang."
        return null
    }

    fun getTx(id: Long): FinanceTx? = readableDatabase.rawQuery("SELECT $txCols FROM tx WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) readTx(it) else null }

    /** Seluruh transaksi tanpa batas (untuk backup/ekspor). */
    fun allTx(): List<FinanceTx> {
        val out = ArrayList<FinanceTx>()
        readableDatabase.rawQuery("SELECT $txCols FROM tx ORDER BY ts DESC, id DESC", null).use { while (it.moveToNext()) out.add(readTx(it)) }
        return out
    }

    fun listTx(limit: Int = 200): List<FinanceTx> {
        val out = ArrayList<FinanceTx>()
        readableDatabase.rawQuery("SELECT $txCols FROM tx ORDER BY ts DESC, id DESC LIMIT ?", arrayOf(limit.coerceIn(1, 1000).toString())).use { while (it.moveToNext()) out.add(readTx(it)) }
        return out
    }

    private fun where(q: FinanceTxQuery): Pair<String, Array<String>> {
        val w = ArrayList<String>()
        val a = ArrayList<String>()
        val text = q.text.trim()
        if (text.isNotEmpty()) {
            val like = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            w.add("(merchant LIKE ? ESCAPE '\\' OR raw_text LIKE ? ESCAPE '\\' OR category LIKE ? ESCAPE '\\')"); repeat(3) { a.add(like) }
        }
        q.type?.takeIf { it == FinType.IN || it == FinType.OUT }?.let { w.add("type=?"); a.add(it) }
        q.category?.takeIf { it.isNotBlank() && it != "Semua" }?.let { w.add("category=?"); a.add(it) }
        q.method?.takeIf { it.isNotBlank() && it != "Semua" }?.let { w.add("method=?"); a.add(it) }
        q.wallet?.takeIf { it.isNotBlank() && it != "Semua" }?.let { w.add("wallet=?"); a.add(it) }
        q.from?.let { w.add("ts>=?"); a.add(it.toString()) }
        q.to?.let { w.add("ts<?"); a.add(it.toString()) }
        return (if (w.isEmpty()) "" else " WHERE " + w.joinToString(" AND ")) to a.toTypedArray()
    }

    fun queryTx(q: FinanceTxQuery, limit: Int = 300): List<FinanceTx> {
        val (w, a) = where(q)
        val order = when (q.sort) {
            FinanceSort.DATE_DESC -> "ts DESC, id DESC"
            FinanceSort.DATE_ASC -> "ts ASC, id ASC"
            FinanceSort.AMOUNT_DESC -> "amount DESC, ts DESC"
            FinanceSort.AMOUNT_ASC -> "amount ASC, ts DESC"
        }
        val out = ArrayList<FinanceTx>()
        readableDatabase.rawQuery("SELECT $txCols FROM tx$w ORDER BY $order LIMIT ${limit.coerceIn(1, 5000)}", a).use { while (it.moveToNext()) out.add(readTx(it)) }
        return out
    }

    /** Jumlah baris & total masuk/keluar untuk filter riwayat (tanpa batas baris). */
    fun queryTotals(q: FinanceTxQuery): Triple<Int, Long, Long> {
        val (w, a) = where(q)
        readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(CASE WHEN type='masuk' THEN CAST(ROUND(amount) AS INTEGER) ELSE 0 END),0), COALESCE(SUM(CASE WHEN type='keluar' THEN CAST(ROUND(amount) AS INTEGER) ELSE 0 END),0) FROM tx$w", a).use {
            return if (it.moveToFirst()) Triple(it.getInt(0), it.getLong(1), it.getLong(2)) else Triple(0, 0L, 0L)
        }
    }

    /** Entri ringan untuk perhitungan murni (ringkasan, grafik). */
    fun entries(from: Long, to: Long): List<FinEntry> {
        val out = ArrayList<FinEntry>()
        readableDatabase.rawQuery("SELECT ts,type,amount,category,method FROM tx WHERE ts>=? AND ts<?", arrayOf(from.toString(), to.toString())).use {
            while (it.moveToNext()) out.add(FinEntry(it.getLong(0), it.getString(1), money(it, 2), it.getString(3), it.getString(4) ?: FinMethods.DEFAULT))
        }
        return out
    }

    fun categoriesUsed(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT DISTINCT category FROM tx ORDER BY category", null).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }

    fun monthRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val s = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        return s to cal.timeInMillis
    }

    fun sumByCategory(type: String, from: Long, to: Long): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        readableDatabase.rawQuery("SELECT category,SUM(CAST(ROUND(amount) AS INTEGER)) FROM tx WHERE type=? AND ts>=? AND ts<? GROUP BY category ORDER BY 2 DESC", arrayOf(type, from.toString(), to.toString())).use {
            while (it.moveToNext()) out.add(it.getString(0) to it.getLong(1))
        }
        return out
    }

    fun totalByType(type: String, from: Long, to: Long): Long =
        scalarLong("SELECT COALESCE(SUM(CAST(ROUND(amount) AS INTEGER)),0) FROM tx WHERE type=? AND ts>=? AND ts<?", arrayOf(type, from.toString(), to.toString()))

    /** Saldo semua dompet (saldo awal + transaksi) sampai sebelum [to]. */
    fun balanceUntil(to: Long): Long {
        val opening = scalarLong("SELECT COALESCE(SUM(CAST(ROUND(opening_balance) AS INTEGER)),0) FROM wallet")
        val flow = scalarLong("SELECT COALESCE(SUM(CASE WHEN type='masuk' THEN CAST(ROUND(amount) AS INTEGER) ELSE -CAST(ROUND(amount) AS INTEGER) END),0) FROM tx WHERE ts<?", arrayOf(to.toString()))
        return opening + flow
    }

    fun netWorth(): Long = balanceUntil(Long.MAX_VALUE)

    fun averageExpense(category: String?): Long =
        if (category.isNullOrBlank()) scalarLong("SELECT CAST(COALESCE(AVG(amount),0) AS INTEGER) FROM tx WHERE type='keluar'")
        else scalarLong("SELECT CAST(COALESCE(AVG(amount),0) AS INTEGER) FROM tx WHERE type='keluar' AND category=?", arrayOf(category))

    fun recentExpenseAnomaly(t: FinanceTx, multiplier: Long = 3L): Boolean {
        val avg = averageExpense(t.category)
        return avg > 0 && t.amount >= avg * multiplier
    }

    // ---------------------------------------------------------------- anggaran

    fun getBudgets(): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        readableDatabase.rawQuery("SELECT category,limit_amount FROM budget WHERE category<>? ORDER BY category", arrayOf(TOTAL_BUDGET)).use { while (it.moveToNext()) out[it.getString(0)] = money(it, 1) }
        return out
    }

    fun totalBudget(): Long? = readableDatabase.rawQuery("SELECT limit_amount FROM budget WHERE category=?", arrayOf(TOTAL_BUDGET)).use { if (it.moveToFirst()) money(it, 0) else null }

    fun setBudget(category: String, limit: Long) {
        require(category.isNotBlank() && validAmount(limit)) { "Anggaran tidak valid" }
        writableDatabase.insertWithOnConflict("budget", null, ContentValues().apply { put("category", category); put("limit_amount", limit) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteBudget(category: String) { writableDatabase.delete("budget", "category=?", arrayOf(category)) }

    // ---------------------------------------------------------------- dompet

    fun walletsWithBalances(): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        readableDatabase.rawQuery("SELECT name,opening_balance FROM wallet ORDER BY name", null).use { while (it.moveToNext()) out.add(it.getString(0) to money(it, 1)) }
        if (out.isEmpty()) out.add("Umum" to 0L)
        return out
    }

    fun wallets(): List<String> = walletsWithBalances().map { it.first }

    fun transactionCountForWallet(name: String): Int = scalarLong("SELECT COUNT(*) FROM tx WHERE wallet=?", arrayOf(name)).toInt()

    fun walletBalance(wallet: String): Long {
        val opening = scalarLong("SELECT COALESCE(CAST(ROUND(opening_balance) AS INTEGER),0) FROM wallet WHERE name=?", arrayOf(wallet))
        val flow = scalarLong("SELECT COALESCE(SUM(CASE WHEN type='masuk' THEN CAST(ROUND(amount) AS INTEGER) ELSE -CAST(ROUND(amount) AS INTEGER) END),0) FROM tx WHERE wallet=?", arrayOf(wallet))
        return opening + flow
    }

    fun addWallet(name: String, balance: Long = 0L): Boolean =
        writableDatabase.insertWithOnConflict("wallet", null, ContentValues().apply { put("name", name); put("opening_balance", balance) }, SQLiteDatabase.CONFLICT_IGNORE) != -1L

    fun deleteWallet(name: String): Boolean {
        if (name == "Umum" || transactionCountForWallet(name) > 0) return false
        return writableDatabase.delete("wallet", "name=?", arrayOf(name)) > 0
    }

    // ---------------------------------------------------------------- target tabungan

    fun goals(): List<FinanceGoal> {
        val out = ArrayList<FinanceGoal>()
        readableDatabase.rawQuery("SELECT id,name,target,deadline,initial_saved FROM savings_goal ORDER BY id DESC", null).use {
            while (it.moveToNext()) out.add(FinanceGoal(it.getLong(0), it.getString(1), money(it, 2), it.getLong(3), money(it, 4)))
        }
        return out
    }

    fun addGoal(name: String, target: Long, initialSaved: Long = 0L, deadline: Long = 0L): Long {
        require(name.isNotBlank() && validAmount(target) && initialSaved in 0..FinMoney.MAX) { "Target tidak valid" }
        return writableDatabase.insert("savings_goal", null, ContentValues().apply { put("name", name.trim()); put("target", target); put("deadline", deadline); put("initial_saved", initialSaved) })
    }

    fun updateGoal(g: FinanceGoal): Boolean {
        require(g.name.isNotBlank() && validAmount(g.target) && g.initialSaved in 0..FinMoney.MAX) { "Target tidak valid" }
        return writableDatabase.update("savings_goal", ContentValues().apply { put("name", g.name.trim()); put("target", g.target); put("deadline", g.deadline); put("initial_saved", g.initialSaved) }, "id=?", arrayOf(g.id.toString())) > 0
    }

    /** Hapus target; setoran tetap tercatat sebagai transaksi biasa. */
    fun deleteGoal(id: Long) = tx { db ->
        db.update("tx", ContentValues().apply { putNull("goal_id") }, "goal_id=?", arrayOf(id.toString()))
        db.delete("savings_goal", "id=?", arrayOf(id.toString()))
    }

    fun goalDeposits(goalId: Long): List<FinanceTx> {
        val out = ArrayList<FinanceTx>()
        readableDatabase.rawQuery("SELECT $txCols FROM tx WHERE goal_id=? ORDER BY ts DESC, id DESC", arrayOf(goalId.toString())).use { while (it.moveToNext()) out.add(readTx(it)) }
        return out
    }

    fun goalProgress(g: FinanceGoal): FinGoalProgress = FinCalc.goalProgress(g.target, g.initialSaved, goalDeposits(g.id).map { it.amount })

    // ---------------------------------------------------------------- tagihan / transaksi berulang

    private fun readBill(c: Cursor) = FinanceBill(
        id = c.getLong(0), title = c.getString(1), amount = money(c, 2), type = c.getString(3), category = c.getString(4), wallet = c.getString(5),
        dayOfMonth = c.getInt(6), enabled = c.getInt(7) == 1, lastRun = c.getLong(8), autoPost = c.getInt(9) == 1, remindDays = c.getInt(10),
        method = c.getString(11) ?: FinMethods.DEFAULT
    )

    private val billCols = "id,title,amount,type,category,wallet,day_of_month,enabled,last_run,auto_post,remind_days,method"

    fun bills(): List<FinanceBill> {
        val out = ArrayList<FinanceBill>()
        readableDatabase.rawQuery("SELECT $billCols FROM recurring ORDER BY day_of_month, id", null).use { while (it.moveToNext()) out.add(readBill(it)) }
        return out
    }

    private fun billValues(b: FinanceBill) = ContentValues().apply {
        put("title", b.title.trim()); put("amount", b.amount); put("type", b.type); put("category", b.category); put("wallet", b.wallet.ifBlank { "Umum" })
        put("day_of_month", b.dayOfMonth.coerceIn(1, 31)); put("enabled", if (b.enabled) 1 else 0); put("auto_post", if (b.autoPost) 1 else 0)
        put("remind_days", b.remindDays.coerceIn(-1, 14)); put("method", b.method)
    }

    private fun validateBill(b: FinanceBill) {
        require(b.title.isNotBlank()) { "Nama tagihan kosong" }
        require(validAmount(b.amount)) { "Nominal tagihan tidak valid" }
        require(b.type == FinType.IN || b.type == FinType.OUT) { "Tipe tidak valid" }
    }

    fun addBill(b: FinanceBill): Long { validateBill(b); return writableDatabase.insert("recurring", null, billValues(b)) }

    fun updateBill(b: FinanceBill): Boolean { validateBill(b); return writableDatabase.update("recurring", billValues(b), "id=?", arrayOf(b.id.toString())) > 0 }

    /** Hapus tagihan; transaksi yang sudah tercatat tetap ada. */
    fun deleteBill(id: Long) = tx { db ->
        db.delete("recurring_run", "recurring_id=?", arrayOf(id.toString()))
        db.delete("recurring", "id=?", arrayOf(id.toString()))
    }

    /** billId → txId (atau null) untuk tagihan yang sudah dibayar/dicatat pada bulan tsb. */
    fun billRuns(month: FinMonth): Map<Long, Long?> {
        val out = HashMap<Long, Long?>()
        readableDatabase.rawQuery("SELECT recurring_id, tx_id FROM recurring_run WHERE month_key=?", arrayOf(month.key)).use {
            while (it.moveToNext()) out[it.getLong(0)] = if (it.isNull(1)) null else it.getLong(1)
        }
        return out
    }

    /** Tandai tagihan lunas untuk [month] dan catat transaksinya (atomik). */
    fun markBillPaid(b: FinanceBill, month: FinMonth, ts: Long, amount: Long = b.amount): Long = tx { db ->
        require(validAmount(amount)) { "Nominal tidak valid" }
        val txId = db.insert("tx", null, txValues(FinanceTx(timestamp = ts, type = b.type, amount = amount, category = b.category, merchant = b.title,
            sourceApp = "bill", rawText = "Tagihan ${month.label}", manual = true, walletName = b.wallet, method = b.method)))
        check(txId > 0) { "Gagal mencatat transaksi" }
        val claimed = db.insertWithOnConflict("recurring_run", null, ContentValues().apply {
            put("recurring_id", b.id); put("month_key", month.key); put("executed_at", ts); put("tx_id", txId)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        check(claimed != -1L) { "Tagihan bulan ini sudah ditandai lunas" }
        txId
    }

    /** Batalkan status lunas; transaksi pembayaran ikut dihapus bila ada. */
    fun unmarkBillPaid(billId: Long, month: FinMonth) {
        val txId = billRuns(month)[billId]
        tx { db ->
            db.delete("recurring_run", "recurring_id=? AND month_key=?", arrayOf(billId.toString(), month.key))
            if (txId != null) { db.delete("split_tx", "tx_id=?", arrayOf(txId.toString())); db.delete("tx", "id=?", arrayOf(txId.toString())) }
        }
    }

    /** Catat otomatis tagihan/transaksi berulang (auto_post=1) yang sudah jatuh tempo bulan ini. */
    fun processDueRecurring(now: Long = System.currentTimeMillis()): Int {
        val today = FinDate.of(now)
        val month = today.finMonth
        var count = 0
        tx { db ->
            db.rawQuery("SELECT $billCols FROM recurring WHERE enabled=1 AND auto_post=1", null).use { c ->
                while (c.moveToNext()) {
                    val b = readBill(c)
                    if (FinCalc.effectiveDueDay(b.dayOfMonth, month) > today.day) continue
                    if (!validAmount(b.amount)) continue
                    val claim = ContentValues().apply { put("recurring_id", b.id); put("month_key", month.key); put("executed_at", now) }
                    if (db.insertWithOnConflict("recurring_run", null, claim, SQLiteDatabase.CONFLICT_IGNORE) == -1L) continue // sudah dicatat bulan ini
                    val inserted = db.insert("tx", null, txValues(FinanceTx(timestamp = now, type = b.type, amount = b.amount, category = b.category, merchant = b.title,
                        sourceApp = "recurring", rawText = "Transaksi berulang", manual = true, walletName = b.wallet, method = b.method)))
                    if (inserted == -1L) throw IllegalStateException("Gagal membuat transaksi berulang")
                    db.update("recurring_run", ContentValues().apply { put("tx_id", inserted) }, "recurring_id=? AND month_key=?", arrayOf(b.id.toString(), month.key))
                    db.update("recurring", ContentValues().apply { put("last_run", now) }, "id=?", arrayOf(b.id.toString()))
                    count++
                }
            }
        }
        return count
    }

    // ---------------------------------------------------------------- utang / piutang

    private fun readDebt(c: Cursor) = FinanceDebt(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4) ?: "", c.getLong(5), c.getLong(6), c.getInt(7) == 1)

    fun debts(): List<FinanceDebt> {
        val out = ArrayList<FinanceDebt>()
        readableDatabase.rawQuery("SELECT id,kind,person,amount,note,created_at,due_date,settled FROM debt ORDER BY settled ASC, CASE WHEN due_date=0 THEN 1 ELSE 0 END, due_date ASC, id DESC", null).use { while (it.moveToNext()) out.add(readDebt(it)) }
        return out
    }

    private fun debtValues(d: FinanceDebt) = ContentValues().apply {
        put("kind", d.kind); put("person", d.person.trim()); put("amount", d.amount); put("note", d.note.trim())
        put("created_at", d.createdAt); put("due_date", d.dueDate); put("settled", if (d.settled) 1 else 0)
    }

    private fun validateDebt(d: FinanceDebt) {
        require(d.kind == FinanceDebt.KIND_PAYABLE || d.kind == FinanceDebt.KIND_RECEIVABLE) { "Jenis tidak valid" }
        require(d.person.isNotBlank()) { "Nama kosong" }
        require(validAmount(d.amount)) { "Nominal tidak valid" }
    }

    fun addDebt(d: FinanceDebt): Long { validateDebt(d); return writableDatabase.insert("debt", null, debtValues(d)) }

    fun updateDebt(d: FinanceDebt): Boolean { validateDebt(d); return writableDatabase.update("debt", debtValues(d), "id=?", arrayOf(d.id.toString())) > 0 }

    fun setDebtSettled(id: Long, settled: Boolean) { writableDatabase.update("debt", ContentValues().apply { put("settled", if (settled) 1 else 0) }, "id=?", arrayOf(id.toString())) }

    /** Hapus catatan utang beserta riwayat pembayarannya; transaksi kas yang sudah tercatat dipertahankan. */
    fun deleteDebt(id: Long) = tx { db ->
        db.delete("debt_payment", "debt_id=?", arrayOf(id.toString()))
        db.delete("debt", "id=?", arrayOf(id.toString()))
    }

    fun debtPayments(debtId: Long): List<FinanceDebtPayment> {
        val out = ArrayList<FinanceDebtPayment>()
        readableDatabase.rawQuery("SELECT id,debt_id,amount,ts,note,tx_id FROM debt_payment WHERE debt_id=? ORDER BY ts DESC, id DESC", arrayOf(debtId.toString())).use {
            while (it.moveToNext()) out.add(FinanceDebtPayment(it.getLong(0), it.getLong(1), it.getLong(2), it.getLong(3), it.getString(4) ?: "", if (it.isNull(5)) null else it.getLong(5)))
        }
        return out
    }

    fun debtStatus(d: FinanceDebt): FinDebtStatus = FinCalc.debtStatus(d.amount, debtPayments(d.id).map { it.amount }, d.settled)

    /**
     * Catat pembayaran sebagian/penuh. Bila [recordTx], arus kas ikut dicatat: membayar utang = pengeluaran,
     * menerima piutang = pemasukan. Status lunas diperbarui otomatis bila sisa = 0.
     */
    fun addDebtPayment(d: FinanceDebt, amount: Long, ts: Long, note: String, recordTx: Boolean, wallet: String, method: String): Long = tx { db ->
        require(validAmount(amount)) { "Nominal tidak valid" }
        var txId: Long? = null
        if (recordTx) {
            val t = FinanceTx(timestamp = ts, type = if (d.isPayable) FinType.OUT else FinType.IN, amount = amount,
                category = if (d.isPayable) "Utang" else "Piutang", merchant = (if (d.isPayable) "Bayar utang ke " else "Terima piutang dari ") + d.person,
                sourceApp = "debt", rawText = note, manual = true, walletName = wallet, method = method)
            txId = db.insert("tx", null, txValues(t)).also { check(it > 0) { "Gagal mencatat transaksi" } }
        }
        val id = db.insert("debt_payment", null, ContentValues().apply {
            put("debt_id", d.id); put("amount", amount); put("ts", ts); put("note", note.trim()); if (txId != null) put("tx_id", txId) else putNull("tx_id")
        })
        val paid = db.rawQuery("SELECT COALESCE(SUM(amount),0) FROM debt_payment WHERE debt_id=?", arrayOf(d.id.toString())).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        if (paid >= d.amount) db.update("debt", ContentValues().apply { put("settled", 1) }, "id=?", arrayOf(d.id.toString()))
        id
    }

    fun deleteDebtPayment(p: FinanceDebtPayment) = tx { db ->
        db.delete("debt_payment", "id=?", arrayOf(p.id.toString()))
        p.txId?.let { db.delete("split_tx", "tx_id=?", arrayOf(it.toString())); db.delete("tx", "id=?", arrayOf(it.toString())) }
        resyncDebtSettled(db, p.debtId)
    }

    private fun linkedDebt(db: SQLiteDatabase, txId: Long): Long? =
        db.rawQuery("SELECT debt_id FROM debt_payment WHERE tx_id=? LIMIT 1", arrayOf(txId.toString())).use { if (it.moveToFirst()) it.getLong(0) else null }

    /** Status lunas mengikuti total pembayaran: lunas bila dibayar >= pokok, aktif kembali bila kurang. */
    private fun resyncDebtSettled(db: SQLiteDatabase, debtId: Long) {
        val amount = db.rawQuery("SELECT amount FROM debt WHERE id=?", arrayOf(debtId.toString())).use { if (it.moveToFirst()) it.getLong(0) else return }
        val paid = db.rawQuery("SELECT COALESCE(SUM(amount),0) FROM debt_payment WHERE debt_id=?", arrayOf(debtId.toString())).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        db.update("debt", ContentValues().apply { put("settled", if (paid >= amount) 1 else 0) }, "id=?", arrayOf(debtId.toString()))
    }

    // ---------------------------------------------------------------- impor / ekspor CSV

    fun exportRows(): List<FinCsvRow> = allTx().map { FinCsvRow(it.timestamp, it.type, it.amount, it.category, it.method, it.merchant, it.walletName) }

    /** Simpan baris CSV yang sudah tervalidasi. @return (ditambahkan, duplikat dilewati). */
    fun importRows(rows: List<FinCsvRow>): Pair<Int, Int> = tx { db ->
        var added = 0
        var dup = 0
        for (r in rows) {
            val exists = db.rawQuery("SELECT 1 FROM tx WHERE ts=? AND type=? AND CAST(ROUND(amount) AS INTEGER)=? AND category=? AND COALESCE(merchant,'')=? LIMIT 1",
                arrayOf(r.ts.toString(), r.type, r.amount.toString(), r.category, r.note)).use { it.moveToFirst() }
            if (exists) { dup++; continue }
            db.insertWithOnConflict("wallet", null, ContentValues().apply { put("name", r.wallet); put("opening_balance", 0) }, SQLiteDatabase.CONFLICT_IGNORE)
            val id = db.insert("tx", null, txValues(FinanceTx(timestamp = r.ts, type = r.type, amount = r.amount, category = r.category, merchant = r.note,
                sourceApp = "csv", rawText = "", manual = true, walletName = r.wallet, method = r.method)))
            if (id > 0) added++
        }
        added to dup
    }

    // ---------------------------------------------------------------- backup JSON

    fun backupJson(): JSONObject {
        val root = JSONObject().apply { put("format", "mytools-finance-backup"); put("version", 5); put("createdAt", System.currentTimeMillis()) }
        root.put("transactions", JSONArray().apply {
            allTx().forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id); put("timestamp", t.timestamp); put("type", t.type); put("amount", t.amount); put("category", t.category)
                    put("merchant", t.merchant); put("sourceApp", t.sourceApp); put("rawText", t.rawText); put("manual", t.manual)
                    put("wallet", t.walletName); put("method", t.method); t.goalId?.let { put("goalId", it) }
                })
            }
        })
        root.put("budgets", JSONObject().apply {
            readableDatabase.rawQuery("SELECT category,limit_amount FROM budget", null).use { while (it.moveToNext()) put(it.getString(0), money(it, 1)) }
        })
        root.put("wallets", JSONArray().apply { walletsWithBalances().forEach { (n, b) -> put(JSONObject().apply { put("name", n); put("openingBalance", b) }) } })
        root.put("recurring", JSONArray().apply {
            bills().forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id); put("title", b.title); put("amount", b.amount); put("type", b.type); put("category", b.category); put("wallet", b.wallet)
                    put("dayOfMonth", b.dayOfMonth); put("enabled", b.enabled); put("lastRun", b.lastRun); put("autoPost", b.autoPost); put("remindDays", b.remindDays); put("method", b.method)
                })
            }
        })
        root.put("recurringRuns", JSONArray().apply {
            readableDatabase.rawQuery("SELECT recurring_id,month_key,executed_at,tx_id FROM recurring_run", null).use { c ->
                while (c.moveToNext()) put(JSONObject().apply { put("recurringId", c.getLong(0)); put("monthKey", c.getString(1)); put("executedAt", c.getLong(2)); if (!c.isNull(3)) put("txId", c.getLong(3)) })
            }
        })
        root.put("goals", JSONArray().apply { goals().forEach { g -> put(JSONObject().apply { put("id", g.id); put("name", g.name); put("target", g.target); put("deadline", g.deadline); put("initialSaved", g.initialSaved) }) } })
        root.put("splits", JSONArray().apply {
            readableDatabase.rawQuery("SELECT id,tx_id,category,amount FROM split_tx ORDER BY id", null).use { c ->
                while (c.moveToNext()) put(JSONObject().apply { put("id", c.getLong(0)); put("txId", c.getLong(1)); put("category", c.getString(2)); put("amount", money(c, 3)) })
            }
        })
        root.put("debts", JSONArray().apply {
            debts().forEach { d -> put(JSONObject().apply { put("id", d.id); put("kind", d.kind); put("person", d.person); put("amount", d.amount); put("note", d.note); put("createdAt", d.createdAt); put("dueDate", d.dueDate); put("settled", d.settled) }) }
        })
        root.put("debtPayments", JSONArray().apply {
            readableDatabase.rawQuery("SELECT id,debt_id,amount,ts,note,tx_id FROM debt_payment ORDER BY id", null).use { c ->
                while (c.moveToNext()) put(JSONObject().apply { put("id", c.getLong(0)); put("debtId", c.getLong(1)); put("amount", c.getLong(2)); put("ts", c.getLong(3)); put("note", c.getString(4) ?: ""); if (!c.isNull(5)) put("txId", c.getLong(5)) })
            }
        })
        return root
    }

    private fun jsonMoney(o: JSONObject, key: String): Long? {
        val v = o.optDouble(key, Double.NaN)
        if (!v.isFinite() || v < 0.0 || v > FinMoney.MAX.toDouble()) return null
        return Math.round(v)
    }

    /** Pulihkan backup v2–v5 secara atomik (data lama diganti hanya bila seluruh proses berhasil). */
    fun restoreFromBackup(root: JSONObject): Int {
        require(root.optString("format") == "mytools-finance-backup") { "Format backup tidak dikenali" }
        val version = root.optInt("version", 0)
        require(version in 2..5) { "Versi backup tidak didukung: $version" }
        val txs = root.optJSONArray("transactions")
        require(txs != null) { "Backup tidak berisi daftar transaksi; data lama tidak diubah" }
        var restored = 0
        tx { db ->
            for (t in listOf("split_tx", "debt_payment", "debt", "recurring_run", "tx", "budget", "recurring", "savings_goal", "wallet")) db.delete(t, null, null)
            val ws = root.optJSONArray("wallets") ?: JSONArray()
            for (i in 0 until ws.length()) {
                val item = ws.optJSONObject(i)
                val name = if (item != null) item.optString("name", "Umum") else ws.optString(i, "Umum")
                val opening = if (item != null) Math.round(item.optDouble("openingBalance", 0.0).takeIf { it.isFinite() } ?: 0.0) else 0L
                if (name.isNotBlank()) db.insertWithOnConflict("wallet", null, ContentValues().apply { put("name", name); put("opening_balance", opening) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.insertWithOnConflict("wallet", null, ContentValues().apply { put("name", "Umum"); put("opening_balance", 0) }, SQLiteDatabase.CONFLICT_IGNORE)

            val goalIdMap = HashMap<Long, Long>()
            val goals = root.optJSONArray("goals") ?: JSONArray()
            for (i in 0 until goals.length()) {
                val o = goals.optJSONObject(i) ?: continue
                val target = jsonMoney(o, "target") ?: continue
                val newId = db.insert("savings_goal", null, ContentValues().apply {
                    put("name", o.optString("name")); put("target", target); put("deadline", o.optLong("deadline", 0)); put("initial_saved", jsonMoney(o, "initialSaved") ?: 0L)
                })
                if (newId > 0) goalIdMap[o.optLong("id", 0)] = newId
            }

            val idMap = HashMap<Long, Long>()
            for (i in 0 until txs.length()) {
                val o = txs.optJSONObject(i) ?: continue
                val type = o.optString("type", FinType.OUT)
                val amount = jsonMoney(o, "amount") ?: continue
                if (type != FinType.IN && type != FinType.OUT) continue
                val id = db.insert("tx", null, ContentValues().apply {
                    put("ts", o.optLong("timestamp", System.currentTimeMillis())); put("type", type); put("amount", amount)
                    put("category", o.optString("category", "Lainnya").ifBlank { "Lainnya" }); put("merchant", o.optString("merchant")); put("source_app", o.optString("sourceApp", "restore"))
                    put("raw_text", o.optString("rawText")); put("manual", if (o.optBoolean("manual", true)) 1 else 0); put("wallet", o.optString("wallet", "Umum").ifBlank { "Umum" })
                    put("method", FinMethods.normalize(o.optString("method", FinMethods.DEFAULT)))
                    goalIdMap[o.optLong("goalId", 0)]?.let { put("goal_id", it) }
                })
                if (id > 0) { o.optLong("id", 0).takeIf { it > 0 }?.let { old -> idMap[old] = id }; restored++ }
            }

            val budgets = root.optJSONObject("budgets")
            budgets?.keys()?.forEach { k ->
                val v = budgets.optDouble(k, 0.0)
                if (v.isFinite() && v > 0) db.insertWithOnConflict("budget", null, ContentValues().apply { put("category", k); put("limit_amount", Math.round(v)) }, SQLiteDatabase.CONFLICT_REPLACE)
            }

            val billIdMap = HashMap<Long, Long>()
            val recurring = root.optJSONArray("recurring") ?: JSONArray()
            for (i in 0 until recurring.length()) {
                val o = recurring.optJSONObject(i) ?: continue
                val amount = jsonMoney(o, "amount") ?: continue
                val newId = db.insert("recurring", null, ContentValues().apply {
                    put("title", o.optString("title")); put("amount", amount); put("type", o.optString("type", FinType.OUT)); put("category", o.optString("category", "Lainnya"))
                    put("wallet", o.optString("wallet", "Umum")); put("day_of_month", o.optInt("dayOfMonth", 1).coerceIn(1, 31)); put("enabled", if (o.optBoolean("enabled", true)) 1 else 0)
                    put("last_run", o.optLong("lastRun", 0)); put("auto_post", if (o.optBoolean("autoPost", true)) 1 else 0); put("remind_days", o.optInt("remindDays", 1).coerceIn(-1, 14))
                    put("method", FinMethods.normalize(o.optString("method", FinMethods.DEFAULT)))
                })
                if (newId > 0) billIdMap[o.optLong("id", 0)] = newId
            }
            val runs = root.optJSONArray("recurringRuns") ?: JSONArray()
            for (i in 0 until runs.length()) {
                val o = runs.optJSONObject(i) ?: continue
                val bill = billIdMap[o.optLong("recurringId", 0)] ?: continue
                val month = FinMonth.parse(o.optString("monthKey")) ?: continue
                db.insertWithOnConflict("recurring_run", null, ContentValues().apply {
                    put("recurring_id", bill); put("month_key", month.key); put("executed_at", o.optLong("executedAt", 0))
                    idMap[o.optLong("txId", 0)]?.let { put("tx_id", it) }
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }

            val splits = root.optJSONArray("splits") ?: JSONArray()
            for (i in 0 until splits.length()) {
                val o = splits.optJSONObject(i) ?: continue
                val parent = idMap[o.optLong("txId", 0)] ?: continue
                val amount = jsonMoney(o, "amount") ?: continue
                db.insert("split_tx", null, ContentValues().apply { put("tx_id", parent); put("category", o.optString("category", "Lainnya")); put("amount", amount) })
            }

            val debtIdMap = HashMap<Long, Long>()
            val debts = root.optJSONArray("debts") ?: JSONArray()
            for (i in 0 until debts.length()) {
                val o = debts.optJSONObject(i) ?: continue
                val amount = jsonMoney(o, "amount") ?: continue
                val kind = o.optString("kind")
                if (kind != FinanceDebt.KIND_PAYABLE && kind != FinanceDebt.KIND_RECEIVABLE) continue
                val newId = db.insert("debt", null, ContentValues().apply {
                    put("kind", kind); put("person", o.optString("person").ifBlank { "?" }); put("amount", amount); put("note", o.optString("note"))
                    put("created_at", o.optLong("createdAt", 0)); put("due_date", o.optLong("dueDate", 0)); put("settled", if (o.optBoolean("settled", false)) 1 else 0)
                })
                if (newId > 0) debtIdMap[o.optLong("id", 0)] = newId
            }
            val pays = root.optJSONArray("debtPayments") ?: JSONArray()
            for (i in 0 until pays.length()) {
                val o = pays.optJSONObject(i) ?: continue
                val debt = debtIdMap[o.optLong("debtId", 0)] ?: continue
                val amount = jsonMoney(o, "amount") ?: continue
                db.insert("debt_payment", null, ContentValues().apply {
                    put("debt_id", debt); put("amount", amount); put("ts", o.optLong("ts", 0)); put("note", o.optString("note"))
                    idMap[o.optLong("txId", 0)]?.let { put("tx_id", it) }
                })
            }
        }
        return restored
    }

    fun clearAll() = tx { db ->
        for (t in listOf("split_tx", "debt_payment", "debt", "recurring_run", "tx", "budget", "recurring", "savings_goal", "wallet")) db.delete(t, null, null)
        db.insert("wallet", null, ContentValues().apply { put("name", "Umum"); put("opening_balance", 0) })
    }

    companion object {
        /** Kunci baris anggaran total bulanan di tabel budget. */
        const val TOTAL_BUDGET = "__total__"
    }
}
