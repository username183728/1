package com.example.aidetest

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FinanceReport {
    /** Laporan PDF satu bulan: ringkasan, kategori pengeluaran, dan daftar transaksi. */
    fun createPdf(context: Context, db: FinanceDb, month: FinMonth = FinMonth.of(System.currentTimeMillis())): File {
        val doc = PdfDocument()
        val from = month.startMillis(); val to = month.endMillis()
        val txs = db.queryTx(FinanceTxQuery(from = from, to = to, sort = FinanceSort.DATE_ASC), 2000)
        val sum = FinCalc.summarize(txs.map { it.toEntry() })
        val cats = FinCalc.categoryBreakdown(txs.map { it.toEntry() })
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create())
        var c = page.canvas
        p.textSize = 18f; p.isFakeBoldText = true
        c.drawText("GITLS — Laporan Keuangan ${month.label}", 36f, 42f, p)
        p.textSize = 11f; p.isFakeBoldText = false
        c.drawText("Dibuat: ${SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID")).format(Date())}", 36f, 62f, p)
        c.drawText("Pemasukan: ${FinMoney.rp(sum.income)}   Pengeluaran: ${FinMoney.rp(sum.expense)}   Selisih: ${FinMoney.rp(sum.net)}", 36f, 84f, p)
        var y = 108f
        if (cats.isNotEmpty()) {
            p.isFakeBoldText = true; c.drawText("Pengeluaran per kategori", 36f, y, p); p.isFakeBoldText = false; y += 18f
            for (s in cats.take(8)) { c.drawText("${s.category.take(24)}  ${FinMoney.rp(s.amount)}  (${FinMoney.percent(s.permille)})", 48f, y, p); y += 16f }
            y += 8f
        }
        fun header() {
            p.isFakeBoldText = true
            c.drawText("Tanggal", 36f, y, p); c.drawText("Catatan", 100f, y, p); c.drawText("Kategori", 300f, y, p); c.drawText("Metode", 400f, y, p); c.drawText("Nominal", 480f, y, p)
            p.isFakeBoldText = false; y += 18f
        }
        header()
        val df = SimpleDateFormat("dd/MM", Locale.getDefault())
        for (tx in txs) {
            if (y > 805f) {
                doc.finishPage(page); pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create()); c = page.canvas; y = 42f; header()
            }
            c.drawText(df.format(Date(tx.timestamp)), 36f, y, p)
            c.drawText(tx.merchant.ifBlank { "-" }.take(30), 100f, y, p)
            c.drawText(tx.category.take(14), 300f, y, p)
            c.drawText(tx.method.take(10), 400f, y, p)
            c.drawText((if (tx.type == FinType.IN) "+" else "-") + FinMoney.group(tx.amount), 480f, y, p)
            y += 16f
        }
        doc.finishPage(page)
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "laporan_keuangan_${month.key}_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun createReceipt(context: Context, tx: FinanceTx): File {
        val pdf = PdfDocument(); val page = pdf.startPage(PdfDocument.PageInfo.Builder(420, 600, 1).create()); val c = page.canvas; val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = 20f; p.isFakeBoldText = true; c.drawText("GITLS — BUKTI TRANSAKSI", 24f, 42f, p); p.textSize = 13f; p.isFakeBoldText = false
        val lines = listOf("Catatan: ${tx.merchant}", "Kategori: ${tx.category}", "Metode: ${tx.method}", "Dompet: ${tx.walletName}",
            "Tanggal: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(tx.timestamp))}", "Jenis: ${FinType.label(tx.type)}", "Nominal: ${FinMoney.rp(tx.amount)}")
        var y = 85f; for (line in lines) { c.drawText(line.take(52), 24f, y, p); y += 28f }
        pdf.finishPage(page); val dir = File(context.cacheDir, "receipts").apply { mkdirs() }; val file = File(dir, "receipt_${tx.id}_${System.currentTimeMillis()}.pdf"); FileOutputStream(file).use { pdf.writeTo(it) }; pdf.close(); return file
    }

    fun share(context: Context, file: File, mime: String = "application/pdf") {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
    }
}
