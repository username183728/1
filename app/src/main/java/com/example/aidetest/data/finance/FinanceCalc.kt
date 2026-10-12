package com.example.aidetest

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/*
 * Logika keuangan murni (tanpa Android) — diuji di FinanceCalcTest.
 * Semua nominal memakai Long dalam satuan terkecil (rupiah utuh). Tidak ada Double untuk uang.
 */

object FinType {
    const val IN = "masuk"
    const val OUT = "keluar"
    fun label(type: String): String = if (type == IN) "Pemasukan" else "Pengeluaran"
}

object FinMethods {
    val ALL = listOf("Tunai", "Transfer", "E-wallet", "Kartu", "Lainnya")
    const val DEFAULT = "Lainnya"

    /** Normalisasi teks bebas (mis. dari CSV) ke salah satu metode baku. */
    fun normalize(raw: String?): String {
        val s = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (s.isEmpty()) return DEFAULT
        ALL.firstOrNull { it.lowercase(Locale.ROOT) == s }?.let { return it }
        return when {
            s == "cash" || s == "tunai" || s == "uang tunai" -> "Tunai"
            s.contains("wallet") || s in setOf("gopay", "ovo", "dana", "shopeepay", "linkaja") -> "E-wallet"
            s.contains("kartu") || s.contains("card") || s == "debit" || s == "kredit" -> "Kartu"
            s.contains("transfer") || s == "tf" || s == "bank" -> "Transfer"
            else -> DEFAULT
        }
    }
}

object FinMoney {
    /** Batas aman nominal (13 digit) supaya perkalian permil tidak overflow. */
    const val MAX = 9_999_999_999_999L

    fun group(v: Long): String {
        val neg = v < 0
        val digits = (if (neg) -v else v).toString()
        val sb = StringBuilder()
        for ((i, ch) in digits.reversed().withIndex()) {
            if (i > 0 && i % 3 == 0) sb.append('.')
            sb.append(ch)
        }
        return (if (neg) "-" else "") + sb.reverse().toString()
    }

    fun rp(v: Long): String = if (v < 0) "-Rp" + group(-v) else "Rp" + group(v)

    fun signed(v: Long): String = if (v > 0) "+" + rp(v) else rp(v)

    /** Label pendek untuk grafik: 950, 1,5rb, 350rb, 1,2jt, 3,4M, 1,2T. */
    fun compact(v: Long): String {
        val neg = v < 0
        val a = if (neg) -v else v
        val (unit, suffix) = when {
            a >= 1_000_000_000_000L -> 1_000_000_000_000L to "T"
            a >= 1_000_000_000L -> 1_000_000_000L to "M"
            a >= 1_000_000L -> 1_000_000L to "jt"
            a >= 1_000L -> 1_000L to "rb"
            else -> 1L to ""
        }
        val body = if (unit == 1L) a.toString() else {
            val tenths = a / (unit / 10)
            val whole = tenths / 10
            val frac = tenths % 10
            if (whole >= 100 || frac == 0L) "$whole$suffix" else "$whole,$frac$suffix"
        }
        return (if (neg) "-" else "") + body
    }

    /**
     * Nominal dari teks pengguna/CSV: "150000", "150.000", "Rp 1.500.000", "150000.00", "150000,0".
     * Pecahan rupiah selain nol ditolak. Hasil null bila tidak valid, negatif, atau di atas [MAX].
     */
    fun parse(raw: String?): Long? {
        var s = raw?.trim() ?: return null
        s = s.replace("Rp", "", ignoreCase = true).replace("IDR", "", ignoreCase = true)
            .replace(" ", "").replace("\u00A0", "")
        if (s.isEmpty() || s.startsWith("-")) return null
        if (s.startsWith("+")) s = s.substring(1)
        val grouped = Regex("^\\d{1,3}([.,]\\d{3})+$")
        val plain = Regex("^(\\d+)([.,](\\d{1,2}))?$")
        val digits: String = when {
            grouped.matches(s) -> {
                // Pemisah ribuan harus konsisten (semua titik atau semua koma).
                val seps = s.filter { it == '.' || it == ',' }.toSet()
                if (seps.size != 1) return null
                s.filter { it.isDigit() }
            }
            else -> {
                val m = plain.matchEntire(s) ?: return null
                val frac = m.groupValues[3]
                if (frac.isNotEmpty() && frac.any { it != '0' }) return null
                m.groupValues[1]
            }
        }
        if (digits.length > 13) return null
        val v = digits.toLongOrNull() ?: return null
        return if (v > MAX) null else v
    }

    /** part/whole dalam permil (1000 = 100%). whole <= 0 → 0. */
    fun permille(part: Long, whole: Long): Int {
        if (whole <= 0L || part <= 0L) return 0
        val p = part.coerceAtMost(MAX) * 1000L / whole
        return p.coerceAtMost(100_000L).toInt()
    }

    /** 125 → "12,5%", 120 → "12%", -50 → "-5%". */
    fun percent(permille: Int): String {
        val neg = permille < 0
        val a = if (neg) -permille else permille
        val whole = a / 10
        val frac = a % 10
        return (if (neg) "-" else "") + (if (frac == 0) "$whole%" else "$whole,$frac%")
    }
}

/** Bulan kalender (month 1..12). */
data class FinMonth(val year: Int, val month: Int) : Comparable<FinMonth> {
    init { require(month in 1..12) { "Bulan tidak valid: $month" } }

    fun plus(n: Int): FinMonth {
        val idx = year * 12 + (month - 1) + n
        return FinMonth(idx.floorDiv(12), idx.mod(12) + 1)
    }

    val key: String get() = String.format(Locale.US, "%04d-%02d", year, month)
    val label: String get() = "${NAMES[month - 1]} $year"
    val shortLabel: String get() = SHORT[month - 1]
    val days: Int get() = FinDate.daysInMonth(year, month)

    fun startMillis(tz: TimeZone = TimeZone.getDefault()): Long =
        GregorianCalendar(tz).apply { clear(); set(year, month - 1, 1, 0, 0, 0) }.timeInMillis

    fun endMillis(tz: TimeZone = TimeZone.getDefault()): Long = plus(1).startMillis(tz)

    override fun compareTo(other: FinMonth): Int = (year * 12 + month).compareTo(other.year * 12 + other.month)

    companion object {
        val NAMES = listOf("Januari", "Februari", "Maret", "April", "Mei", "Juni", "Juli", "Agustus", "September", "Oktober", "November", "Desember")
        val SHORT = listOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")

        fun of(millis: Long, tz: TimeZone = TimeZone.getDefault()): FinMonth {
            val c = GregorianCalendar(tz).apply { timeInMillis = millis }
            return FinMonth(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
        }

        fun parse(key: String?): FinMonth? {
            val m = Regex("^(\\d{4})-(\\d{2})$").matchEntire(key?.trim() ?: return null) ?: return null
            val mo = m.groupValues[2].toInt()
            return if (mo in 1..12) FinMonth(m.groupValues[1].toInt(), mo) else null
        }
    }
}

/** Tanggal kalender tanpa jam, untuk hitung jatuh tempo. */
data class FinDate(val year: Int, val month: Int, val day: Int) {
    val epochDay: Long get() = epochDay(year, month, day)
    val finMonth: FinMonth get() = FinMonth(year, month)

    companion object {
        fun isLeap(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        fun daysInMonth(y: Int, m: Int): Int = when (m) {
            2 -> if (isLeap(y)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

        /** Hari sejak 1970-01-01 (algoritma civil-from-days, H. Hinnant). */
        fun epochDay(y0: Int, m: Int, d: Int): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val mp = (m + 9) % 12
            val doy = (153 * mp + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era.toLong() * 146097L + doe - 719468L
        }

        fun of(millis: Long, tz: TimeZone = TimeZone.getDefault()): FinDate {
            val c = GregorianCalendar(tz).apply { timeInMillis = millis }
            return FinDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        }
    }
}

data class FinEntry(val ts: Long, val type: String, val amount: Long, val category: String, val method: String = FinMethods.DEFAULT)

data class FinSummary(val income: Long, val expense: Long) {
    val net: Long get() = income - expense
}

data class FinShare(val category: String, val amount: Long, val permille: Int)

data class FinMonthPoint(val month: FinMonth, val income: Long, val expense: Long)

enum class FinBudgetLevel { OK, WARN, OVER }

data class FinBudgetUsage(val limit: Long, val spent: Long, val remaining: Long, val permille: Int, val level: FinBudgetLevel)

data class FinGoalProgress(val target: Long, val saved: Long, val remaining: Long, val permille: Int, val done: Boolean)

data class FinDebtStatus(val principal: Long, val paid: Long, val remaining: Long, val settled: Boolean, val overpaid: Long)

data class FinDelta(val current: Long, val previous: Long) {
    val diff: Long get() = current - previous
    /** Perubahan relatif dalam permil, null bila periode lalu nol (tidak bisa dibandingkan). */
    val permilleChange: Int? get() = if (previous == 0L) null else (diff * 1000L / kotlin.math.abs(previous)).coerceIn(-1_000_000L, 1_000_000L).toInt()
}

data class FinComparison(val income: FinDelta, val expense: FinDelta, val net: FinDelta)

enum class FinBillState { PAID, OVERDUE, DUE_SOON, UPCOMING }

data class FinBillDue(val dueDate: FinDate, val daysUntil: Int, val state: FinBillState)

object FinCalc {
    fun summarize(entries: List<FinEntry>, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): FinSummary {
        var inc = 0L
        var exp = 0L
        for (e in entries) {
            if (e.ts < from || e.ts >= to) continue
            if (e.type == FinType.IN) inc += e.amount else if (e.type == FinType.OUT) exp += e.amount
        }
        return FinSummary(inc, exp)
    }

    /** Deret bulanan berurutan dari [last]-(count-1) sampai [last]. */
    fun monthlySeries(entries: List<FinEntry>, last: FinMonth, count: Int, tz: TimeZone = TimeZone.getDefault()): List<FinMonthPoint> {
        val months = (count - 1 downTo 0).map { last.plus(-it) }
        val inc = HashMap<FinMonth, Long>()
        val exp = HashMap<FinMonth, Long>()
        val first = months.first().startMillis(tz)
        val end = last.endMillis(tz)
        for (e in entries) {
            if (e.ts < first || e.ts >= end) continue
            val m = FinMonth.of(e.ts, tz)
            if (e.type == FinType.IN) inc[m] = (inc[m] ?: 0L) + e.amount
            else if (e.type == FinType.OUT) exp[m] = (exp[m] ?: 0L) + e.amount
        }
        return months.map { FinMonthPoint(it, inc[it] ?: 0L, exp[it] ?: 0L) }
    }

    fun categoryBreakdown(entries: List<FinEntry>, type: String = FinType.OUT, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): List<FinShare> {
        val sums = LinkedHashMap<String, Long>()
        for (e in entries) {
            if (e.type != type || e.ts < from || e.ts >= to) continue
            sums[e.category] = (sums[e.category] ?: 0L) + e.amount
        }
        val total = sums.values.sum()
        return sums.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .map { FinShare(it.key, it.value, FinMoney.permille(it.value, total)) }
    }

    fun budgetUsage(spent: Long, limit: Long, warnPermille: Int = 800): FinBudgetUsage {
        val p = FinMoney.permille(spent, limit)
        val level = when {
            limit <= 0L -> FinBudgetLevel.OK
            spent > limit -> FinBudgetLevel.OVER
            p >= warnPermille -> FinBudgetLevel.WARN
            else -> FinBudgetLevel.OK
        }
        return FinBudgetUsage(limit, spent, limit - spent, p, level)
    }

    /**
     * Sisa anggaran bulan: memakai anggaran total bila diatur; jika tidak, jumlah anggaran kategori
     * dibanding pengeluaran pada kategori tersebut. Null bila belum ada anggaran.
     */
    fun monthBudget(totalLimit: Long?, categoryLimits: Map<String, Long>, spentByCategory: Map<String, Long>, totalExpense: Long): FinBudgetUsage? {
        if (totalLimit != null && totalLimit > 0L) return budgetUsage(totalExpense, totalLimit)
        val limits = categoryLimits.filterValues { it > 0L }
        if (limits.isEmpty()) return null
        val spent = limits.keys.sumOf { spentByCategory[it] ?: 0L }
        return budgetUsage(spent, limits.values.sum())
    }

    fun goalProgress(target: Long, initialSaved: Long, deposits: List<Long>): FinGoalProgress {
        val saved = initialSaved.coerceAtLeast(0L) + deposits.sum()
        val remaining = (target - saved).coerceAtLeast(0L)
        val p = FinMoney.permille(saved, target).coerceAtMost(1000)
        return FinGoalProgress(target, saved, remaining, p, target > 0L && saved >= target)
    }

    fun debtStatus(principal: Long, payments: List<Long>, markedSettled: Boolean): FinDebtStatus {
        val paid = payments.sum()
        val remaining = (principal - paid).coerceAtLeast(0L)
        val over = (paid - principal).coerceAtLeast(0L)
        return FinDebtStatus(principal, paid, if (markedSettled) 0L else remaining, markedSettled || remaining == 0L, over)
    }

    fun compare(current: FinSummary, previous: FinSummary): FinComparison = FinComparison(
        FinDelta(current.income, previous.income),
        FinDelta(current.expense, previous.expense),
        FinDelta(current.net, previous.net)
    )

    /** Tanggal jatuh tempo efektif: tanggal 31 di bulan 30 hari → 30, Februari → 28/29. */
    fun effectiveDueDay(day: Int, month: FinMonth): Int = day.coerceIn(1, 31).coerceAtMost(month.days)

    fun billDue(dueDay: Int, month: FinMonth, today: FinDate, paid: Boolean, remindDays: Int): FinBillDue {
        val due = FinDate(month.year, month.month, effectiveDueDay(dueDay, month))
        val days = (due.epochDay - today.epochDay).toInt()
        val state = when {
            paid -> FinBillState.PAID
            days < 0 -> FinBillState.OVERDUE
            days <= remindDays.coerceAtLeast(0) -> FinBillState.DUE_SOON
            else -> FinBillState.UPCOMING
        }
        return FinBillDue(due, days, state)
    }

    /** Proyeksi pengeluaran sebulan dari laju harian (integer). */
    fun projectMonth(expenseSoFar: Long, dayOfMonth: Int, daysInMonth: Int): Long {
        val d = dayOfMonth.coerceIn(1, daysInMonth.coerceAtLeast(1))
        return expenseSoFar * daysInMonth / d
    }
}

data class FinCsvRow(
    val ts: Long,
    val type: String,
    val amount: Long,
    val category: String,
    val method: String,
    val note: String,
    val wallet: String = "Umum"
)

data class FinCsvSkip(val line: Int, val reason: String)

data class FinCsvImport(val rows: List<FinCsvRow>, val skipped: List<FinCsvSkip>, val dataLines: Int)

object FinCsv {
    val HEADER = listOf("tanggal", "tipe", "nominal", "kategori", "metode", "catatan", "dompet")
    const val MAX_ROWS = 50_000
    private const val MAX_TEXT = 500

    private val ALIASES: Map<String, String> = mapOf(
        "tanggal" to "tanggal", "date" to "tanggal", "timestamp" to "tanggal", "waktu" to "tanggal", "tgl" to "tanggal",
        "tipe" to "tipe", "type" to "tipe", "jenis" to "tipe",
        "nominal" to "nominal", "amount" to "nominal", "jumlah" to "nominal", "nilai" to "nominal",
        "kategori" to "kategori", "category" to "kategori",
        "metode" to "metode", "method" to "metode", "metode pembayaran" to "metode", "pembayaran" to "metode", "payment" to "metode",
        "catatan" to "catatan", "note" to "catatan", "notes" to "catatan", "keterangan" to "catatan", "merchant" to "catatan", "deskripsi" to "catatan",
        "dompet" to "dompet", "wallet" to "dompet", "rekening" to "dompet", "akun" to "dompet"
    )

    fun formatDate(ts: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val c = GregorianCalendar(tz).apply { timeInMillis = ts }
        return String.format(Locale.US, "%04d-%02d-%02d %02d:%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /** Teks aman untuk spreadsheet: kutip bila perlu, cegah formula injection (=, +, -, @). */
    fun quote(v: String): String {
        var s = v.replace("\r", " ").replace("\n", " ")
        if (s.isNotEmpty() && s[0] in "=+-@\t") s = "'$s"
        return if (s.any { it == ',' || it == '"' || it == ';' } || s != s.trim()) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun format(rows: List<FinCsvRow>, tz: TimeZone = TimeZone.getDefault()): String {
        val sb = StringBuilder(HEADER.joinToString(",")).append("\r\n")
        for (r in rows) {
            sb.append(formatDate(r.ts, tz)).append(',')
                .append(r.type).append(',')
                .append(r.amount).append(',')
                .append(quote(r.category)).append(',')
                .append(quote(r.method)).append(',')
                .append(quote(r.note)).append(',')
                .append(quote(r.wallet)).append("\r\n")
        }
        return sb.toString()
    }

    /** Pecah teks CSV menjadi record (mendukung kutip, "" escape, baris baru di dalam kutip, CRLF). */
    fun records(text: String, delimiter: Char): List<Pair<Int, List<String>>> {
        val out = ArrayList<Pair<Int, List<String>>>()
        var fields = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var line = 1
        var recordLine = 1
        var i = 0
        var any = false
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cur.append('"'); i++ } else inQuotes = false
                } else {
                    if (ch == '\n') line++
                    cur.append(ch)
                }
            } else when (ch) {
                '"' -> { inQuotes = true; any = true }
                delimiter -> { fields.add(cur.toString()); cur.setLength(0); any = true }
                '\r' -> {}
                '\n' -> {
                    fields.add(cur.toString()); cur.setLength(0)
                    if (any || fields.any { it.isNotBlank() }) out.add(recordLine to fields)
                    fields = ArrayList(); any = false
                    line++; recordLine = line
                }
                else -> { cur.append(ch); any = true }
            }
            i++
        }
        if (cur.isNotEmpty() || fields.isNotEmpty() || any) {
            fields.add(cur.toString())
            if (fields.any { it.isNotBlank() }) out.add(recordLine to fields)
        }
        return out
    }

    fun detectDelimiter(text: String): Char {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        val semi = first.count { it == ';' }
        val comma = first.count { it == ',' }
        val tab = first.count { it == '\t' }
        return when {
            tab > comma && tab > semi -> '\t'
            semi > comma -> ';'
            else -> ','
        }
    }

    fun parseType(raw: String?): String? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "masuk", "pemasukan", "income", "in", "+", "kredit masuk", "debit masuk" -> FinType.IN
        "keluar", "pengeluaran", "expense", "out", "-" -> FinType.OUT
        else -> null
    }

    /** yyyy-MM-dd[ HH:mm[:ss]], yyyy-MM-ddTHH:mm, dd/MM/yyyy[ HH:mm], dd-MM-yyyy, atau epoch milidetik. */
    fun parseDate(raw: String?, tz: TimeZone = TimeZone.getDefault()): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.length >= 10 && s.all { it.isDigit() }) {
            val v = s.toLongOrNull() ?: return null
            return if (v in 0L..4_102_444_800_000L) v else null
        }
        val iso = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?$").matchEntire(s)
        val dmy = Regex("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})(?: (\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?$").matchEntire(s)
        val (y, mo, d, rest) = when {
            iso != null -> listOf(iso.groupValues[1], iso.groupValues[2], iso.groupValues[3], "${iso.groupValues[4]}|${iso.groupValues[5]}|${iso.groupValues[6]}")
            dmy != null -> listOf(dmy.groupValues[3], dmy.groupValues[2], dmy.groupValues[1], "${dmy.groupValues[4]}|${dmy.groupValues[5]}|${dmy.groupValues[6]}")
            else -> return null
        }
        val parts = rest.split('|')
        val year = y.toInt(); val month = mo.toInt(); val day = d.toInt()
        val hour = parts[0].toIntOrNull() ?: 0
        val minute = parts[1].toIntOrNull() ?: 0
        val second = parts[2].toIntOrNull() ?: 0
        if (year !in 1970..2100 || month !in 1..12 || day !in 1..FinDate.daysInMonth(year, month)) return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        return GregorianCalendar(tz).apply { clear(); set(year, month - 1, day, hour, minute, second) }.timeInMillis
    }

    private fun cleanText(v: String?): String {
        var s = v?.trim().orEmpty()
        if (s.length >= 2 && s[0] == '\'' && s[1] in "=+-@") s = s.substring(1)
        return s.take(MAX_TEXT)
    }

    fun parse(rawText: String, tz: TimeZone = TimeZone.getDefault()): FinCsvImport {
        val text = rawText.removePrefix("\uFEFF")
        val recs = records(text, detectDelimiter(text))
        if (recs.isEmpty()) return FinCsvImport(emptyList(), emptyList(), 0)
        val firstNorm = recs[0].second.map { ALIASES[it.trim().lowercase(Locale.ROOT)] }
        val hasHeader = "tanggal" in firstNorm && "nominal" in firstNorm
        val index: Map<String, Int> = if (hasHeader) {
            val m = HashMap<String, Int>()
            firstNorm.forEachIndexed { i, k -> if (k != null && k !in m) m[k] = i }
            m
        } else HEADER.withIndex().associate { it.value to it.index }
        val data = if (hasHeader) recs.drop(1) else recs
        val rows = ArrayList<FinCsvRow>()
        val skipped = ArrayList<FinCsvSkip>()
        fun col(f: List<String>, key: String): String? = index[key]?.let { f.getOrNull(it) }
        for ((lineNo, f) in data) {
            if (rows.size >= MAX_ROWS) { skipped.add(FinCsvSkip(lineNo, "Melebihi batas $MAX_ROWS baris")); continue }
            val ts = parseDate(col(f, "tanggal"), tz)
            if (ts == null) { skipped.add(FinCsvSkip(lineNo, "Tanggal tidak valid: '${col(f, "tanggal").orEmpty().take(30)}'")); continue }
            val rawAmount = col(f, "nominal")?.trim().orEmpty()
            val typeRaw = col(f, "tipe")
            var amountText = rawAmount
            var type = parseType(typeRaw)
            if (type == null) {
                if (typeRaw.isNullOrBlank() && rawAmount.startsWith("-")) {
                    // Tanpa kolom tipe: nominal negatif dianggap pengeluaran.
                    type = FinType.OUT
                    amountText = rawAmount.removePrefix("-")
                } else {
                    val reason = if (typeRaw.isNullOrBlank()) "Tipe kosong" else "Tipe tidak dikenali: '${typeRaw.trim().take(20)}'"
                    skipped.add(FinCsvSkip(lineNo, reason)); continue
                }
            }
            val amount = FinMoney.parse(amountText)
            if (amount == null || amount <= 0L) { skipped.add(FinCsvSkip(lineNo, "Nominal tidak valid: '${rawAmount.take(30)}'")); continue }
            rows.add(FinCsvRow(
                ts = ts,
                type = type,
                amount = amount,
                category = cleanText(col(f, "kategori")).ifBlank { "Lainnya" }.take(60),
                method = FinMethods.normalize(cleanText(col(f, "metode"))),
                note = cleanText(col(f, "catatan")),
                wallet = cleanText(col(f, "dompet")).ifBlank { "Umum" }.take(60)
            ))
        }
        return FinCsvImport(rows, skipped, data.size)
    }
}
