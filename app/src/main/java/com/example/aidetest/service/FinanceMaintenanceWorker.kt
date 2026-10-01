package com.example.aidetest

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Periodic local maintenance for recurring transactions. */
class FinanceMaintenanceWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result = runCatching {
        val db = FinanceDb(applicationContext)
        db.processDueRecurring()
        db.close()
        Result.success()
    }.getOrElse { Result.retry() }
}
