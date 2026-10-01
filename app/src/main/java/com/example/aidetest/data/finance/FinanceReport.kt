package com.example.aidetest

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FinanceReport {
    fun createPdf(context: Context, db: FinanceDb): File {
        val doc=PdfDocument(); val page=doc.startPage(PdfDocument.PageInfo.Builder(595,842,1).create()); val c=page.canvas; val p=Paint(Paint.ANTI_ALIAS_FLAG); p.textSize=18f; p.isFakeBoldText=true
        c.drawText("MyTools — Laporan Keuangan",36f,42f,p); p.textSize=11f; p.isFakeBoldText=false
        val range=db.monthRange(); c.drawText("Dibuat: ${SimpleDateFormat("dd MMM yyyy HH:mm",Locale.getDefault()).format(Date())}",36f,62f,p)
        c.drawText("Pemasukan: ${MoneyFormatter.format(db.totalByType("masuk",range.first,range.second))}",36f,84f,p)
        c.drawText("Pengeluaran: ${MoneyFormatter.format(db.totalByType("keluar",range.first,range.second))}",36f,102f,p)
        var y=130f; p.isFakeBoldText=true; c.drawText("Tanggal",36f,y,p); c.drawText("Merchant",120f,y,p); c.drawText("Kategori",320f,y,p); c.drawText("Nominal",460f,y,p); p.isFakeBoldText=false; y+=20f
        for(tx in db.listTx(30)){ if(y>805) break; c.drawText(SimpleDateFormat("dd/MM",Locale.getDefault()).format(Date(tx.timestamp)),36f,y,p); c.drawText(tx.merchant.take(28),120f,y,p); c.drawText(tx.category.take(18),320f,y,p); c.drawText(MoneyFormatter.format(tx.amount),460f,y,p); y+=18f }
        doc.finishPage(page)
        val dir=File(context.cacheDir,"reports").apply{mkdirs()}; val file=File(dir,"laporan_keuangan_${System.currentTimeMillis()}.pdf"); FileOutputStream(file).use{doc.writeTo(it)}; doc.close(); return file
    }
    fun createReceipt(context: Context, tx: FinanceTx): File {
        val pdf=PdfDocument(); val page=pdf.startPage(PdfDocument.PageInfo.Builder(420,600,1).create()); val c=page.canvas; val p=Paint(Paint.ANTI_ALIAS_FLAG); p.textSize=20f; p.isFakeBoldText=true; c.drawText("MYTOOLS — BUKTI TRANSAKSI",24f,42f,p); p.textSize=13f; p.isFakeBoldText=false
        val lines=listOf("Merchant: ${tx.merchant}","Kategori: ${tx.category}","Wallet: ${tx.walletName}","Tanggal: ${SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(Date(tx.timestamp))}","Jenis: ${tx.type}","Nominal: ${MoneyFormatter.format(tx.amount)}")
        var y=85f; for(line in lines){c.drawText(line.take(52),24f,y,p);y+=28f}; pdf.finishPage(page); val dir=File(context.cacheDir,"receipts").apply{mkdirs()}; val file=File(dir,"receipt_${tx.id}_${System.currentTimeMillis()}.pdf"); FileOutputStream(file).use{pdf.writeTo(it)}; pdf.close(); return file
    }
    fun share(context: Context, file: File, mime:String="application/pdf") { val uri=FileProvider.getUriForFile(context, context.packageName+".fileprovider",file); context.startActivity(Intent(Intent.ACTION_SEND).apply{type=mime;putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}) }
}
