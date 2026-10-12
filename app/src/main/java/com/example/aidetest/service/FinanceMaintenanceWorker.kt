package com.example.aidetest

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Periodic local maintenance: recurring transactions + bill reminders. */
class FinanceMaintenanceWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result = runCatching {
        val ctx = applicationContext
        val db = FinanceDb(ctx)
        db.processDueRecurring()
        db.close()
        FinanceBillNotifier.notifyDue(ctx)
        Result.success()
    }.getOrElse { Result.retry() }
}
