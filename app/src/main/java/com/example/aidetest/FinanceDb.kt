package com.example.aidetest

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/** Local finance database. v5 removes legacy notification-reader data. */
class FinanceDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "mytools_finance.db", null, 6) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tx (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, type TEXT NOT NULL, amount REAL NOT NULL, category TEXT NOT NULL, merchant TEXT, source_app TEXT, raw_text TEXT, manual INTEGER NOT NULL DEFAULT 0, wallet TEXT NOT NULL DEFAULT 'Umum')")
        db.execSQL("CREATE TABLE budget (category TEXT PRIMARY KEY, limit_amount REAL NOT NULL)")
        db.execSQL("CREATE TABLE wallet (name TEXT PRIMARY KEY, opening_balance REAL NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE recurring (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, amount REAL NOT NULL, type TEXT NOT NULL, category TEXT NOT NULL, wallet TEXT NOT NULL, day_of_month INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, last_run INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE savings_goal (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, target REAL NOT NULL, deadline INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE split_tx (id INTEGER PRIMARY KEY AUTOINCREMENT, tx_id INTEGER NOT NULL, category TEXT NOT NULL, amount REAL NOT NULL)")
        db.execSQL("CREATE INDEX idx_split_tx_parent ON split_tx(tx_id)")
        db.execSQL("CREATE INDEX idx_tx_ts ON tx(ts DESC)")
        db.execSQL("CREATE INDEX idx_tx_wallet_ts ON tx(wallet, ts DESC)")
        db.execSQL("CREATE INDEX idx_tx_type_ts ON tx(type, ts DESC)")
        db.execSQL("CREATE INDEX idx_tx_category_ts ON tx(category, ts DESC)")
        db.execSQL("INSERT INTO wallet(name, opening_balance) VALUES('Umum',0)")
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
    }

    fun walletsWithBalances(): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>()
        readableDatabase.rawQuery("SELECT name,opening_balance FROM wallet ORDER BY name", null).use {
            while (it.moveToNext()) out.add(it.getString(0) to it.getDouble(1))
        }
        if (out.isEmpty()) out.add("Umum" to 0.0)
        return out
    }

    fun transactionCountForWallet(name: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM tx WHERE wallet=?", arrayOf(name)).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun recurringForBackup(): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        readableDatabase.rawQuery("SELECT id,title,amount,type,category,wallet,day_of_month,enabled,last_run FROM recurring ORDER BY id", null).use { c ->
            while (c.moveToNext()) out.add(JSONObject().apply {
                put("id", c.getLong(0)); put("title", c.getString(1)); put("amount", c.getDouble(2));
                put("type", c.getString(3)); put("category", c.getString(4)); put("wallet", c.getString(5));
                put("dayOfMonth", c.getInt(6)); put("enabled", c.getInt(7) == 1); put("lastRun", c.getLong(8))
            })
        }
        return out
    }

    fun goalsForBackup(): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        readableDatabase.rawQuery("SELECT id,name,target,deadline FROM savings_goal ORDER BY id", null).use { c ->
            while (c.moveToNext()) out.add(JSONObject().apply {
                put("id", c.getLong(0)); put("name", c.getString(1)); put("target", c.getDouble(2)); put("deadline", c.getLong(3))
            })
        }
        return out
    }

    fun splitsForBackup(): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        readableDatabase.rawQuery("SELECT id,tx_id,category,amount FROM split_tx ORDER BY id", null).use { c ->
            while (c.moveToNext()) out.add(JSONObject().apply {
                put("id", c.getLong(0)); put("txId", c.getLong(1)); put("category", c.getString(2)); put("amount", c.getDouble(3))
            })
        }
        return out
    }

    fun insertTx(t: FinanceTx): Long {
        require(t.amount.isFinite() && t.amount >= 0.0) { "Nominal transaksi tidak valid" }
        require(t.type == "masuk" || t.type == "keluar") { "Tipe transaksi tidak valid" }
        require(t.category.isNotBlank()) { "Kategori transaksi kosong" }
        return writableDatabase.insert("tx", null, ContentValues().apply {
        put("ts", t.timestamp); put("type", t.type); put("amount", t.amount); put("category", t.category)
        put("merchant", t.merchant); put("source_app", t.sourceApp); put("raw_text", t.rawText)
        put("manual", if (t.manual) 1 else 0); put("wallet", t.walletName.ifBlank { "Umum" })
    })
    }

    fun deleteTx(id: Long) {
        if (id <= 0) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("split_tx", "tx_id=?", arrayOf(id.toString()))
            db.delete("tx", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun readTx(c: android.database.Cursor): FinanceTx = FinanceTx(
        id=c.getLong(0), timestamp=c.getLong(1), type=c.getString(2), amount=c.getDouble(3), category=c.getString(4),
        merchant=c.getString(5) ?: "", sourceApp=c.getString(6) ?: "", rawText=c.getString(7) ?: "", manual=c.getInt(8)==1,
        walletName=if (c.columnCount > 9) c.getString(9) ?: "Umum" else "Umum"
    )

    fun listTx(limit: Int = 200): List<FinanceTx> {
        val safeLimit = limit.coerceIn(1, 1000)
        val out=ArrayList<FinanceTx>(); val c=readableDatabase.rawQuery("SELECT id,ts,type,amount,category,merchant,source_app,raw_text,manual,wallet FROM tx ORDER BY ts DESC LIMIT ?", arrayOf(safeLimit.toString()))
        c.use { while(it.moveToNext()) out.add(readTx(it)) }; return out
    }

    fun searchTx(query: String, category: String? = null, wallet: String? = null, minAmount: Double? = null, maxAmount: Double? = null, from: Long? = null, to: Long? = null): List<FinanceTx> {
        val where=ArrayList<String>(); val args=ArrayList<String>(); val q=query.trim()
        if(q.isNotEmpty()){ where.add("(merchant LIKE ? OR raw_text LIKE ? OR category LIKE ? OR source_app LIKE ?)"); repeat(4){args.add("%$q%")}}
        if(!category.isNullOrBlank() && category!="Semua"){where.add("category=?");args.add(category)}
        if(!wallet.isNullOrBlank() && wallet!="Semua"){where.add("wallet=?");args.add(wallet)}
        minAmount?.let{where.add("amount>=?");args.add(it.toString())}; maxAmount?.let{where.add("amount<=?");args.add(it.toString())}
        from?.let{where.add("ts>=?");args.add(it.toString())}; to?.let{where.add("ts<?");args.add(it.toString())}
        val sql="SELECT id,ts,type,amount,category,merchant,source_app,raw_text,manual,wallet FROM tx"+(if(where.isEmpty())"" else " WHERE "+where.joinToString(" AND "))+" ORDER BY ts DESC LIMIT 1000"
        val out=ArrayList<FinanceTx>(); readableDatabase.rawQuery(sql,args.toTypedArray()).use{while(it.moveToNext())out.add(readTx(it))}; return out
    }

    fun monthRange(): Pair<Long,Long>{val cal=Calendar.getInstance();cal.set(Calendar.DAY_OF_MONTH,1);cal.set(Calendar.HOUR_OF_DAY,0);cal.set(Calendar.MINUTE,0);cal.set(Calendar.SECOND,0);cal.set(Calendar.MILLISECOND,0);val s=cal.timeInMillis;cal.add(Calendar.MONTH,1);return s to cal.timeInMillis}
    fun sumByCategory(type:String,from:Long,to:Long):List<Pair<String,Double>>{val out=ArrayList<Pair<String,Double>>();readableDatabase.rawQuery("SELECT category,SUM(amount) FROM tx WHERE type=? AND ts>=? AND ts<? GROUP BY category ORDER BY 2 DESC",arrayOf(type,from.toString(),to.toString())).use{while(it.moveToNext())out.add(it.getString(0) to it.getDouble(1))};return out}
    fun totalByType(type:String,from:Long,to:Long):Double{readableDatabase.rawQuery("SELECT COALESCE(SUM(amount),0) FROM tx WHERE type=? AND ts>=? AND ts<?",arrayOf(type,from.toString(),to.toString())).use{return if(it.moveToFirst())it.getDouble(0) else 0.0}}
    fun totalCategory(category:String):Double{readableDatabase.rawQuery("SELECT COALESCE(SUM(amount),0) FROM tx WHERE category=?",arrayOf(category)).use{return if(it.moveToFirst())it.getDouble(0) else 0.0}}
    fun setBudget(category:String,limit:Double){writableDatabase.insertWithOnConflict("budget",null,ContentValues().apply{put("category",category);put("limit_amount",limit)},SQLiteDatabase.CONFLICT_REPLACE)}
    fun getBudgets():Map<String,Double>{val out=LinkedHashMap<String,Double>();readableDatabase.rawQuery("SELECT category,limit_amount FROM budget",null).use{while(it.moveToNext())out[it.getString(0)]=it.getDouble(1)};return out}
    fun totalByTypeForWallet(type:String,wallet:String):Double{readableDatabase.rawQuery("SELECT COALESCE(SUM(amount),0) FROM tx WHERE type=? AND wallet=?",arrayOf(type,wallet)).use{return if(it.moveToFirst())it.getDouble(0) else 0.0}}
    fun wallets():List<String>{val out=ArrayList<String>();readableDatabase.rawQuery("SELECT name FROM wallet ORDER BY name",null).use{while(it.moveToNext())out.add(it.getString(0))};if(out.isEmpty())out.add("Umum");return out}
    fun addWallet(name:String,balance:Double=0.0):Boolean{return writableDatabase.insertWithOnConflict("wallet",null,ContentValues().apply{put("name",name);put("opening_balance",balance)},SQLiteDatabase.CONFLICT_IGNORE)!=-1L}
    fun deleteWallet(name:String):Boolean{if(name=="Umum" || transactionCountForWallet(name)>0)return false; return writableDatabase.delete("wallet","name=?",arrayOf(name))>0}
    fun addRecurring(title:String,amount:Double,type:String,category:String,wallet:String,day:Int){writableDatabase.insert("recurring",null,ContentValues().apply{put("title",title);put("amount",amount);put("type",type);put("category",category);put("wallet",wallet);put("day_of_month",day.coerceIn(1,28));put("enabled",1)})}
    fun recurring():List<Array<Any>>{val out=ArrayList<Array<Any>>();readableDatabase.rawQuery("SELECT id,title,amount,type,category,wallet,day_of_month,enabled,last_run FROM recurring ORDER BY day_of_month",null).use{while(it.moveToNext())out.add(arrayOf(it.getLong(0),it.getString(1),it.getDouble(2),it.getString(3),it.getString(4),it.getString(5),it.getInt(6),it.getInt(7),it.getLong(8)))};return out}
    fun processDueRecurring(now:Long=System.currentTimeMillis()):Int{
        val db=writableDatabase; val cal=Calendar.getInstance(); cal.timeInMillis=now; val day=cal.get(Calendar.DAY_OF_MONTH);
        cal.set(Calendar.HOUR_OF_DAY,0); cal.set(Calendar.MINUTE,0); cal.set(Calendar.SECOND,0); cal.set(Calendar.MILLISECOND,0); val today=cal.timeInMillis;
        var count=0; db.beginTransaction();
        try {
            db.rawQuery("SELECT id,title,amount,type,category,wallet,day_of_month,last_run FROM recurring WHERE enabled=1 AND day_of_month<=?",arrayOf(day.toString())).use{
                while(it.moveToNext()){ val id=it.getLong(0); val last=it.getLong(7)
                    // Jatuh tempo bulan ini = awal hari pada tanggal day_of_month. Hanya jalan sekali per bulan.
                    val dueCal=Calendar.getInstance().apply{timeInMillis=today;set(Calendar.DAY_OF_MONTH,it.getInt(6).coerceIn(1,28))}
                    val dueTs=dueCal.timeInMillis
                    if(last<dueTs){
                    val inserted=db.insert("tx",null,ContentValues().apply{put("ts",now);put("type",it.getString(3));put("amount",it.getDouble(2));put("category",it.getString(4));put("merchant",it.getString(1));put("source_app","recurring");put("raw_text","Transaksi berulang");put("manual",1);put("wallet",it.getString(5))});
                    if(inserted==-1L) throw IllegalStateException("Gagal membuat transaksi berulang")
                    db.update("recurring",ContentValues().apply{put("last_run",today)},"id=?",arrayOf(id.toString())); count++
                }}
            }; db.setTransactionSuccessful()
        } finally { db.endTransaction() }; return count
    }
    fun addGoal(name:String,target:Double,deadline:Long=0){writableDatabase.insert("savings_goal",null,ContentValues().apply{put("name",name);put("target",target);put("deadline",deadline)})}
    fun goals():List<Array<Any>>{val out=ArrayList<Array<Any>>();readableDatabase.rawQuery("SELECT id,name,target,deadline FROM savings_goal ORDER BY id DESC",null).use{while(it.moveToNext())out.add(arrayOf(it.getLong(0),it.getString(1),it.getDouble(2),it.getLong(3)))};return out}
    fun goalProgress(name:String):Double{readableDatabase.rawQuery("SELECT COALESCE(SUM(amount),0) FROM tx WHERE type='keluar' AND category='Tabungan' AND merchant=?",arrayOf(name)).use{return if(it.moveToFirst())it.getDouble(0) else 0.0}}
    fun addSplit(txId:Long, category:String, amount:Double):Long {
        require(txId > 0) { "ID transaksi tidak valid" }
        require(category.isNotBlank()) { "Kategori split kosong" }
        require(amount.isFinite() && amount > 0.0) { "Nominal split tidak valid" }
        readableDatabase.rawQuery("SELECT 1 FROM tx WHERE id=? LIMIT 1", arrayOf(txId.toString())).use {
            require(it.moveToFirst()) { "Transaksi induk tidak ditemukan" }
        }
        return writableDatabase.insert("split_tx", null, ContentValues().apply { put("tx_id", txId); put("category", category.trim()); put("amount", amount) })
    }
    fun splits(txId:Long):List<Pair<String,Double>> { val out=ArrayList<Pair<String,Double>>(); readableDatabase.rawQuery("SELECT category,amount FROM split_tx WHERE tx_id=? ORDER BY id", arrayOf(txId.toString())).use { while(it.moveToNext()) out.add(it.getString(0) to it.getDouble(1)) }; return out }
    fun netWorth():Double {
        var total=0.0
        readableDatabase.rawQuery("SELECT w.opening_balance + COALESCE((SELECT SUM(CASE WHEN type='masuk' THEN amount ELSE -amount END) FROM tx t WHERE t.wallet=w.name),0) FROM wallet w",null).use { while(it.moveToNext()) total += it.getDouble(0) }
        return total
    }
    fun averageExpense(category:String?=null):Double { val sql=if(category.isNullOrBlank()) "SELECT AVG(amount) FROM tx WHERE type='keluar'" else "SELECT AVG(amount) FROM tx WHERE type='keluar' AND category=?"; readableDatabase.rawQuery(sql, if(category.isNullOrBlank()) null else arrayOf(category)).use { return if(it.moveToFirst()) it.getDouble(0) else 0.0 } }
    fun recentExpenseAnomaly(tx:FinanceTx, multiplier:Double=3.0):Boolean { val avg=averageExpense(tx.category); return avg>0 && tx.amount >= avg*multiplier }
    /** Restores a version 2+ backup atomically. Older v2 backups simply lack newer tables. */
    fun restoreFromBackup(root: JSONObject): Int {
        require(root.optString("format") == "mytools-finance-backup") { "Format backup tidak dikenali" }
        val version = root.optInt("version", 0)
        require(version in 2..3) { "Versi backup tidak didukung: $version" }
        val db = writableDatabase
        var restored = 0
        db.beginTransaction()
        try {
            db.delete("split_tx",null,null); db.delete("tx",null,null); db.delete("budget",null,null);
            db.delete("recurring",null,null); db.delete("savings_goal",null,null); db.delete("wallet",null,null)
            val ws=root.optJSONArray("wallets") ?: JSONArray()
            if(ws.length()==0) db.insert("wallet",null,ContentValues().apply{put("name","Umum");put("opening_balance",0.0)})
            for(i in 0 until ws.length()){
                val item=ws.optJSONObject(i)
                val name=if(item!=null)item.optString("name","Umum") else ws.optString(i,"Umum")
                val opening=if(item!=null)item.optDouble("openingBalance",0.0) else 0.0
                if(name.isNotBlank()) db.insertWithOnConflict("wallet",null,ContentValues().apply{put("name",name);put("opening_balance",opening)},SQLiteDatabase.CONFLICT_IGNORE)
            }
            val idMap=HashMap<Long,Long>()
            val txs=root.optJSONArray("transactions") ?: JSONArray()
            for(i in 0 until txs.length()){ val o=txs.optJSONObject(i)?:continue; val id=db.insert("tx",null,ContentValues().apply{
                put("ts",o.optLong("timestamp",System.currentTimeMillis()));put("type",o.optString("type","keluar"));put("amount",o.optDouble("amount",0.0));
                put("category",o.optString("category","Lainnya"));put("merchant",o.optString("merchant"));put("source_app",o.optString("sourceApp","restore"));
                put("raw_text",o.optString("rawText"));put("manual",if(o.optBoolean("manual",true))1 else 0);put("wallet",o.optString("wallet","Umum").ifBlank{"Umum"})
            }); if(id>0){o.optLong("id",0).takeIf{it>0}?.let{old->idMap[old]=id};restored++} }
            val budgets=root.optJSONObject("budgets"); if(budgets!=null) budgets.keys().forEach{k->db.insertWithOnConflict("budget",null,ContentValues().apply{put("category",k);put("limit_amount",budgets.optDouble(k,0.0))},SQLiteDatabase.CONFLICT_REPLACE)}
            val recurring=root.optJSONArray("recurring")?:JSONArray(); for(i in 0 until recurring.length()){val o=recurring.optJSONObject(i)?:continue;db.insert("recurring",null,ContentValues().apply{put("title",o.optString("title"));put("amount",o.optDouble("amount",0.0));put("type",o.optString("type","keluar"));put("category",o.optString("category","Lainnya"));put("wallet",o.optString("wallet","Umum"));put("day_of_month",o.optInt("dayOfMonth",1).coerceIn(1,28));put("enabled",if(o.optBoolean("enabled",true))1 else 0);put("last_run",o.optLong("lastRun",0))})}
            val goals=root.optJSONArray("goals")?:JSONArray(); for(i in 0 until goals.length()){val o=goals.optJSONObject(i)?:continue;db.insert("savings_goal",null,ContentValues().apply{put("name",o.optString("name"));put("target",o.optDouble("target",0.0));put("deadline",o.optLong("deadline",0))})}
            val splits=root.optJSONArray("splits")?:JSONArray(); for(i in 0 until splits.length()){val o=splits.optJSONObject(i)?:continue;val parent=idMap[o.optLong("txId",0)]?:continue;db.insert("split_tx",null,ContentValues().apply{put("tx_id",parent);put("category",o.optString("category","Lainnya"));put("amount",o.optDouble("amount",0.0))})}
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return restored
    }

    fun clearAll(){val db=writableDatabase;db.beginTransaction();try{db.delete("split_tx",null,null);db.delete("tx",null,null);db.delete("budget",null,null);db.delete("recurring",null,null);db.delete("savings_goal",null,null);db.delete("wallet",null,null);db.insert("wallet",null,ContentValues().apply{put("name","Umum");put("opening_balance",0.0)});db.setTransactionSuccessful()}finally{db.endTransaction()}}
}
