package com.example.aidetest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class FinanceCalcTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val jkt: TimeZone = TimeZone.getTimeZone("Asia/Jakarta")

    private fun ts(y: Int, m: Int, d: Int, tz: TimeZone = utc): Long = FinCsv.parseDate(String.format("%04d-%02d-%02d 10:00", y, m, d), tz)!!

    @Test
    fun moneyFormatAndParse() {
        assertEquals("1.234.567", FinMoney.group(1_234_567))
        assertEquals("0", FinMoney.group(0))
        assertEquals("-1.000", FinMoney.group(-1000))
        assertEquals("Rp1.500.000", FinMoney.rp(1_500_000))
        assertEquals("-Rp2.500", FinMoney.rp(-2500))
        assertEquals("+Rp10", FinMoney.signed(10))
        assertEquals(150_000L, FinMoney.parse("150000"))
        assertEquals(150_000L, FinMoney.parse("150.000"))
        assertEquals(150_000L, FinMoney.parse("150,000"))
        assertEquals(1_500_000L, FinMoney.parse("Rp 1.500.000"))
        assertEquals(150_000L, FinMoney.parse("150000.0"))
        assertEquals(150_000L, FinMoney.parse("150000,00"))
        assertEquals(1_500L, FinMoney.parse("1.500"))
        assertEquals(1_500L, FinMoney.parse("Rp\u00A01.500")) // spasi tak-terputus dari spreadsheet
        assertNull(FinMoney.parse("150000.50")) // pecahan rupiah ditolak
        assertNull(FinMoney.parse("1.500,000")) // pemisah campur
        assertNull(FinMoney.parse("-5000"))
        assertNull(FinMoney.parse("abc"))
        assertNull(FinMoney.parse(""))
        assertNull(FinMoney.parse("99999999999999")) // 14 digit
    }

    @Test
    fun compactAndPercent() {
        assertEquals("950", FinMoney.compact(950))
        assertEquals("1,5rb", FinMoney.compact(1_500))
        assertEquals("350rb", FinMoney.compact(350_000))
        assertEquals("1,2jt", FinMoney.compact(1_250_000))
        assertEquals("12jt", FinMoney.compact(12_000_000))
        assertEquals("3,4M", FinMoney.compact(3_400_000_000))
        assertEquals("-2jt", FinMoney.compact(-2_000_000))
        assertEquals(333, FinMoney.permille(1, 3))
        assertEquals(0, FinMoney.permille(5, 0))
        assertEquals("12,5%", FinMoney.percent(125))
        assertEquals("12%", FinMoney.percent(120))
        assertEquals("-5%", FinMoney.percent(-50))
    }

    @Test
    fun monthArithmetic() {
        val okt = FinMonth(2026, 10)
        assertEquals(FinMonth(2027, 1), okt.plus(3))
        assertEquals(FinMonth(2025, 12), FinMonth(2026, 1).plus(-1))
        assertEquals(FinMonth(2026, 5), okt.plus(-5))
        assertEquals("2026-10", okt.key)
        assertEquals("Oktober 2026", okt.label)
        assertEquals(29, FinMonth(2024, 2).days)
        assertEquals(28, FinMonth(2026, 2).days)
        assertEquals(FinMonth(2026, 3), FinMonth.parse("2026-03"))
        assertNull(FinMonth.parse("2026-13"))
        val start = okt.startMillis(utc)
        assertEquals(okt, FinMonth.of(start, utc))
        assertEquals(FinMonth(2026, 9), FinMonth.of(start - 1, utc))
        assertEquals(31L * 24 * 3600 * 1000, okt.endMillis(utc) - start)
    }

    @Test
    fun monthSummaryAndSeries() {
        val e = listOf(
            FinEntry(ts(2026, 10, 1), FinType.IN, 10_000_000, "Gaji"),
            FinEntry(ts(2026, 10, 2), FinType.OUT, 1_250_000, "Makanan"),
            FinEntry(ts(2026, 10, 31), FinType.OUT, 750_000, "Transport"),
            FinEntry(ts(2026, 9, 30), FinType.OUT, 500_000, "Makanan"),
            FinEntry(ts(2026, 11, 1), FinType.IN, 1, "Gaji")
        )
        val okt = FinMonth(2026, 10)
        val s = FinCalc.summarize(e, okt.startMillis(utc), okt.endMillis(utc))
        assertEquals(10_000_000L, s.income)
        assertEquals(2_000_000L, s.expense)
        assertEquals(8_000_000L, s.net)
        val series = FinCalc.monthlySeries(e, okt, 6, utc)
        assertEquals(6, series.size)
        assertEquals(FinMonth(2026, 5), series.first().month)
        assertEquals(okt, series.last().month)
        assertEquals(500_000L, series[4].expense)
        assertEquals(2_000_000L, series[5].expense)
        assertEquals(0L, series[0].income)
        val cats = FinCalc.categoryBreakdown(e, FinType.OUT, okt.startMillis(utc), okt.endMillis(utc))
        assertEquals(listOf("Makanan", "Transport"), cats.map { it.category })
        assertEquals(625, cats[0].permille)
        assertEquals(375, cats[1].permille)
    }

    @Test
    fun budgetUsageLevels() {
        val ok = FinCalc.budgetUsage(400_000, 1_000_000)
        assertEquals(FinBudgetLevel.OK, ok.level)
        assertEquals(600_000L, ok.remaining)
        assertEquals(400, ok.permille)
        assertEquals(FinBudgetLevel.WARN, FinCalc.budgetUsage(800_000, 1_000_000).level)
        assertEquals(FinBudgetLevel.WARN, FinCalc.budgetUsage(1_000_000, 1_000_000).level)
        val over = FinCalc.budgetUsage(1_200_000, 1_000_000)
        assertEquals(FinBudgetLevel.OVER, over.level)
        assertEquals(-200_000L, over.remaining)
        assertEquals(1200, over.permille)
        // Anggaran total diutamakan; tanpa total, pakai jumlah anggaran kategori.
        val spent = mapOf("Makanan" to 900_000L, "Hiburan" to 300_000L, "Lain" to 5_000_000L)
        val withTotal = FinCalc.monthBudget(3_000_000, mapOf("Makanan" to 1_000_000L), spent, 6_200_000)!!
        assertEquals(-3_200_000L, withTotal.remaining)
        val byCat = FinCalc.monthBudget(null, mapOf("Makanan" to 1_000_000L, "Hiburan" to 500_000L), spent, 6_200_000)!!
        assertEquals(1_500_000L, byCat.limit)
        assertEquals(1_200_000L, byCat.spent)
        assertEquals(300_000L, byCat.remaining)
        assertNull(FinCalc.monthBudget(null, emptyMap(), spent, 100))
    }

    @Test
    fun goalAndDebt() {
        val g = FinCalc.goalProgress(10_000_000, 1_000_000, listOf(2_000_000, 500_000))
        assertEquals(3_500_000L, g.saved)
        assertEquals(6_500_000L, g.remaining)
        assertEquals(350, g.permille)
        assertFalse(g.done)
        val done = FinCalc.goalProgress(1_000, 0, listOf(600, 600))
        assertTrue(done.done)
        assertEquals(0L, done.remaining)
        assertEquals(1000, done.permille)

        val d = FinCalc.debtStatus(5_000_000, listOf(1_000_000, 1_500_000), false)
        assertEquals(2_500_000L, d.remaining)
        assertFalse(d.settled)
        val paidOff = FinCalc.debtStatus(5_000_000, listOf(3_000_000, 2_500_000), false)
        assertTrue(paidOff.settled)
        assertEquals(0L, paidOff.remaining)
        assertEquals(500_000L, paidOff.overpaid)
        val manual = FinCalc.debtStatus(5_000_000, listOf(1_000), true)
        assertTrue(manual.settled)
        assertEquals(0L, manual.remaining)
    }

    @Test
    fun periodComparison() {
        val c = FinCalc.compare(FinSummary(5_000_000, 3_300_000), FinSummary(5_000_000, 3_000_000))
        assertEquals(300_000L, c.expense.diff)
        assertEquals(100, c.expense.permilleChange)
        assertEquals(0, c.income.permilleChange)
        assertEquals(-300_000L, c.net.diff)
        assertEquals(-150, c.net.permilleChange) // 1,7jt vs 2jt
        assertNull(FinCalc.compare(FinSummary(1, 1), FinSummary(0, 0)).income.permilleChange)
    }

    @Test
    fun billDueStates() {
        val feb = FinMonth(2026, 2)
        assertEquals(28, FinCalc.effectiveDueDay(31, feb))
        val today = FinDate(2026, 2, 25)
        val soon = FinCalc.billDue(31, feb, today, paid = false, remindDays = 3)
        assertEquals(FinDate(2026, 2, 28), soon.dueDate)
        assertEquals(3, soon.daysUntil)
        assertEquals(FinBillState.DUE_SOON, soon.state)
        assertEquals(FinBillState.UPCOMING, FinCalc.billDue(31, feb, today, false, 2).state)
        assertEquals(FinBillState.OVERDUE, FinCalc.billDue(10, feb, today, false, 3).state)
        assertEquals(FinBillState.PAID, FinCalc.billDue(10, feb, today, true, 3).state)
        assertEquals(-3, FinCalc.billDue(1, FinMonth(2026, 3), FinDate(2026, 3, 4), false, 1).daysUntil)
        // Lintas tahun: due 1 Jan dari 30 Des = 2 hari.
        assertEquals(2, FinCalc.billDue(1, FinMonth(2027, 1), FinDate(2026, 12, 30), false, 1).daysUntil)
        assertEquals(0L, FinDate(1970, 1, 1).epochDay)
        assertEquals(FinDate.of(ts(2026, 10, 12), utc).epochDay, FinDate(2026, 10, 12).epochDay)
    }

    @Test
    fun projection() {
        assertEquals(3_100_000L, FinCalc.projectMonth(1_000_000, 10, 31))
        assertEquals(0L, FinCalc.projectMonth(0, 1, 30))
    }

    @Test
    fun methodNormalize() {
        assertEquals("Tunai", FinMethods.normalize("cash"))
        assertEquals("E-wallet", FinMethods.normalize("GoPay"))
        assertEquals("E-wallet", FinMethods.normalize("e-wallet"))
        assertEquals("Kartu", FinMethods.normalize("Kartu Kredit"))
        assertEquals("Transfer", FinMethods.normalize("transfer bank"))
        assertEquals("Lainnya", FinMethods.normalize(""))
        assertEquals("Lainnya", FinMethods.normalize("barter"))
    }

    @Test
    fun csvRoundTrip() {
        val rows = listOf(
            FinCsvRow(ts(2026, 10, 1), FinType.IN, 10_000_000, "Gaji", "Transfer", "Gaji, Oktober", "BCA"),
            FinCsvRow(ts(2026, 10, 2), FinType.OUT, 25_000, "Makanan", "Tunai", "Kopi \"susu\"", "Umum"),
            FinCsvRow(ts(2026, 10, 3), FinType.OUT, 1_000, "Lainnya", "E-wallet", "=SUM(A1)", "Umum")
        )
        val csv = FinCsv.format(rows, utc)
        assertTrue(csv.startsWith("tanggal,tipe,nominal,kategori,metode,catatan,dompet\r\n"))
        assertTrue(csv.contains("2026-10-01 10:00,masuk,10000000,Gaji,Transfer,\"Gaji, Oktober\",BCA"))
        assertTrue(csv.contains("'=SUM(A1)"))
        val back = FinCsv.parse(csv, utc)
        assertEquals(0, back.skipped.size)
        assertEquals(rows, back.rows)
    }

    @Test
    fun csvImportValidatesAndReportsLines() {
        val text = "\uFEFFTanggal;Tipe;Nominal;Kategori;Metode;Catatan\n" +
            "2026-10-01;pemasukan;Rp 5.000.000;Gaji;transfer;ok\n" +
            "31/02/2026;keluar;10000;Makanan;;tanggal salah\n" +
            "2026-10-02;keluar;abc;Makanan;;nominal salah\n" +
            "2026-10-03;bonus;1000;Lain;;tipe salah\n" +
            "\n" +
            "03/10/2026 14:30;keluar;15.500;;GoPay;\"multi\nbaris\"\n" +
            "2026-10-04;keluar;1500,50;Makanan;;pecahan\n"
        val r = FinCsv.parse(text, jkt)
        assertEquals(6, r.dataLines)
        assertEquals(2, r.rows.size)
        assertEquals(5_000_000L, r.rows[0].amount)
        assertEquals("Transfer", r.rows[0].method)
        assertEquals(FinType.IN, r.rows[0].type)
        assertEquals(15_500L, r.rows[1].amount)
        assertEquals("Lainnya", r.rows[1].category)
        assertEquals("E-wallet", r.rows[1].method)
        assertEquals("multi\nbaris", r.rows[1].note)
        assertEquals(FinMonth(2026, 10), FinMonth.of(r.rows[1].ts, jkt))
        assertEquals(listOf(3, 4, 5, 9), r.skipped.map { it.line })
        assertTrue(r.skipped[0].reason.startsWith("Tanggal"))
        assertTrue(r.skipped[1].reason.startsWith("Nominal"))
        assertTrue(r.skipped[2].reason.startsWith("Tipe"))
    }

    @Test
    fun csvImportLegacyExport() {
        // Format ekspor lama: timestamp,type,amount,...; merchant = catatan.
        val legacy = "timestamp,type,amount,category,merchant,wallet,source_app,manual,raw_text\n" +
            "1790000000000,keluar,25000.0,\"Makanan\",\"Warung\",\"Umum\",\"manual\",true,\"\"\n" +
            "1790000000000,masuk,-1,\"Gaji\",\"x\",\"Umum\",\"manual\",true,\"\"\n"
        val r = FinCsv.parse(legacy, utc)
        assertEquals(1, r.rows.size)
        assertEquals(1_790_000_000_000L, r.rows[0].ts)
        assertEquals("Warung", r.rows[0].note)
        assertEquals(1, r.skipped.size)
        assertEquals(3, r.skipped[0].line)
    }

    @Test
    fun csvWithoutHeaderUsesDefaultOrderAndNegativeMeansExpense() {
        val r = FinCsv.parse("2026-01-05,keluar,1000,Makanan,Tunai,a,Umum", utc)
        assertEquals(1, r.rows.size)
        val noType = FinCsv.parse("tanggal,nominal,catatan\n2026-01-05,-2000,parkir\n2026-01-06,3000,?", utc)
        assertEquals(1, noType.rows.size)
        assertEquals(FinType.OUT, noType.rows[0].type)
        assertEquals(2_000L, noType.rows[0].amount)
        assertEquals("Tipe kosong", noType.skipped.single().reason)
        assertNotNull(FinCsv.parseDate("2026-01-05T08:15", utc))
        assertNull(FinCsv.parseDate("2026-1-32", utc))
        assertNull(FinCsv.parseDate("25:00", utc))
    }
}
