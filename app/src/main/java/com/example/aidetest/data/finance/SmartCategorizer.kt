package com.example.aidetest

/** Local-first abstraction for future SLM/embedding integration. No network or model download. */
object SmartCategorizer {
    fun categorize(text:String):String = FinanceCategories.guess(text)
    fun explain(text:String):String = "Kategori lokal: ${categorize(text)}. Model SLM/embedding dapat dipasang sebagai provider berikutnya tanpa mengirim notifikasi keluar perangkat."
}
