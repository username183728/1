package com.example.aidetest

import java.util.Locale

data class FinanceTx(
    val id: Long = 0,
    val timestamp: Long,
    val type: String, // "masuk" atau "keluar"
    val amount: Double,
    val category: String,
    val merchant: String,
    val sourceApp: String,
    val rawText: String,
    val manual: Boolean,
    val walletName: String = "Umum"
)

object FinanceCategories {
    val ALL = listOf("Makanan", "Transport", "Belanja", "Tagihan", "Hiburan", "Kesehatan", "Pendidikan", "Transfer", "Tabungan", "Lainnya")

    private val keywordMap = linkedMapOf(
        "Makanan" to listOf("resto", "restaurant", "cafe", "kopi", "coffee", "warung", "food", "makan", "gofood", "grabfood", "shopeefood", "kfc", "mcd", "burger", "pizza", "indomaret", "alfamart"),
        "Transport" to listOf("gojek", "grab", "gocar", "grabcar", "ojek", "taxi", "taksi", "parkir", "tol", "spbu", "pertamina", "shell", "bensin", "mrt", "krl", "transjakarta"),
        "Belanja" to listOf("shopee", "tokopedia", "lazada", "bukalapak", "blibli", "mall", "store", "shop", "market"),
        "Tagihan" to listOf("listrik", "pln", "pdam", "internet", "indihome", "wifi", "pulsa", "paket data", "bpjs", "asuransi", "cicilan", "angsuran", "kartu kredit"),
        "Hiburan" to listOf("netflix", "spotify", "disney", "youtube", "cinema", "xxi", "cgv", "game", "steam", "google play"),
        "Kesehatan" to listOf("apotek", "rumah sakit", "klinik", "dokter", "farmasi", "kimia farma", "guardian"),
        "Pendidikan" to listOf("sekolah", "kampus", "kuliah", "udemy", "kursus", "les"),
        "Transfer" to listOf("transfer", "tf ke", "kirim uang", "terima transfer"),
        "Tabungan" to listOf("tabungan", "deposito", "reksadana", "investasi", "emas")
    )

    fun guess(text: String): String {
        val low = text.toLowerCase(Locale.getDefault())
        for ((cat, keys) in keywordMap) if (keys.any { low.contains(it) }) return cat
        return "Lainnya"
    }
}
