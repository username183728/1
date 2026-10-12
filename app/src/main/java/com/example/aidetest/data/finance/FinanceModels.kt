package com.example.aidetest

import java.util.Locale

data class FinanceTx(
    val id: Long = 0,
    val timestamp: Long,
    val type: String, // "masuk" atau "keluar"
    /** Nominal IDR dalam rupiah utuh (Long, tanpa pecahan). */
    val amount: Long,
    val goalId: Long? = null,
    val category: String,
    /** Catatan / keterangan transaksi (kolom lama bernama merchant). */
    val merchant: String,
    val sourceApp: String,
    val rawText: String,
    val manual: Boolean,
    val walletName: String = "Umum",
    val method: String = FinMethods.DEFAULT
) {
    fun toEntry(): FinEntry = FinEntry(timestamp, type, amount, category, method)
}

data class FinanceGoal(val id: Long, val name: String, val target: Long, val deadline: Long, val initialSaved: Long)

/** Tagihan / pembayaran rutin. autoPost=true: dicatat otomatis saat jatuh tempo (perilaku "transaksi berulang" lama). */
data class FinanceBill(
    val id: Long = 0,
    val title: String,
    val amount: Long,
    val type: String = FinType.OUT,
    val category: String,
    val wallet: String = "Umum",
    val dayOfMonth: Int,
    val enabled: Boolean = true,
    val autoPost: Boolean = false,
    val remindDays: Int = 1,
    val method: String = FinMethods.DEFAULT,
    val lastRun: Long = 0
)

data class FinanceDebt(
    val id: Long = 0,
    /** "utang" = saya berutang; "piutang" = orang lain berutang ke saya. */
    val kind: String,
    val person: String,
    val amount: Long,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val dueDate: Long = 0,
    val settled: Boolean = false
) {
    val isPayable: Boolean get() = kind == KIND_PAYABLE

    companion object {
        const val KIND_PAYABLE = "utang"
        const val KIND_RECEIVABLE = "piutang"
    }
}

data class FinanceDebtPayment(val id: Long, val debtId: Long, val amount: Long, val ts: Long, val note: String, val txId: Long?)

enum class FinanceSort(val label: String) {
    DATE_DESC("Terbaru"), DATE_ASC("Terlama"), AMOUNT_DESC("Nominal terbesar"), AMOUNT_ASC("Nominal terkecil")
}

/** Filter riwayat transaksi. Null/"Semua" = tanpa filter. */
data class FinanceTxQuery(
    val text: String = "",
    val type: String? = null,
    val category: String? = null,
    val method: String? = null,
    val wallet: String? = null,
    val from: Long? = null,
    val to: Long? = null,
    val sort: FinanceSort = FinanceSort.DATE_DESC
)

object FinanceCategories {
    val ALL = listOf("Makanan", "Transport", "Belanja", "Tagihan", "Hiburan", "Kesehatan", "Pendidikan", "Transfer", "Tabungan", "Gaji", "Utang", "Piutang", "Lainnya")

    private val keywordMap = linkedMapOf(
        "Makanan" to listOf("resto", "restaurant", "cafe", "kopi", "coffee", "warung", "food", "makan", "gofood", "grabfood", "shopeefood", "kfc", "mcd", "burger", "pizza", "indomaret", "alfamart"),
        "Transport" to listOf("gojek", "grab", "gocar", "grabcar", "ojek", "taxi", "taksi", "parkir", "tol", "spbu", "pertamina", "shell", "bensin", "mrt", "krl", "transjakarta"),
        "Belanja" to listOf("shopee", "tokopedia", "lazada", "bukalapak", "blibli", "mall", "store", "shop", "market"),
        "Tagihan" to listOf("listrik", "pln", "pdam", "internet", "indihome", "wifi", "pulsa", "paket data", "bpjs", "asuransi", "cicilan", "angsuran", "kartu kredit"),
        "Hiburan" to listOf("netflix", "spotify", "disney", "youtube", "cinema", "xxi", "cgv", "game", "steam", "google play"),
        "Kesehatan" to listOf("apotek", "rumah sakit", "klinik", "dokter", "farmasi", "kimia farma", "guardian"),
        "Pendidikan" to listOf("sekolah", "kampus", "kuliah", "udemy", "kursus", "les"),
        "Transfer" to listOf("transfer", "tf ke", "kirim uang", "terima transfer"),
        "Tabungan" to listOf("tabungan", "deposito", "reksadana", "investasi", "emas"),
        "Gaji" to listOf("gaji", "salary", "thr", "bonus", "honor")
    )

    fun guess(text: String): String {
        val low = text.lowercase(Locale.getDefault())
        for ((cat, keys) in keywordMap) if (keys.any { low.contains(it) }) return cat
        return "Lainnya"
    }
}
