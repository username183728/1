package com.example.aidetest

import java.util.Calendar

object FinanceInsights {
    fun budgetForecastDaysLeft(db: FinanceDb, category: String, limit: Double): Int? {
        if (limit <= 0) return null
        val range=db.monthRange(); val spent=db.sumByCategory("keluar",range.first,range.second).find{it.first==category}?.second ?: 0.0
        val day=Calendar.getInstance().get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
        val daily=spent/day.toDouble()
        if(daily<=0) return null
        return kotlin.math.floor((limit-spent)/daily).toInt().coerceAtLeast(0)
    }
    fun anomaly(tx: FinanceTx, db: FinanceDb): Boolean = tx.type=="keluar" && db.recentExpenseAnomaly(tx)
}
