package com.example.aidetest

import java.util.Calendar

object FinanceInsights {
    /** Perkiraan sisa hari sebelum anggaran kategori habis pada laju pengeluaran bulan berjalan. */
    fun budgetForecastDaysLeft(db: FinanceDb, category: String, limit: Long): Int? {
        if (limit <= 0) return null
        val range = db.monthRange()
        val spent = db.sumByCategory(FinType.OUT, range.first, range.second).find { it.first == category }?.second ?: 0L
        val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
        if (spent <= 0L) return null
        // (limit - spent) / (spent / day) dengan aritmetika Long.
        return ((limit - spent) * day / spent).toInt().coerceAtLeast(0)
    }

    fun anomaly(tx: FinanceTx, db: FinanceDb): Boolean = tx.type == FinType.OUT && db.recentExpenseAnomaly(tx)
}
