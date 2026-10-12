package com.example.aidetest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Pengingat tagihan. Dipanggil oleh FinanceMaintenanceWorker (harian) dan memakai kanal
 * notifikasi yang sama dengan fitur Pengingat (ReminderReceiver: "scheduled_reminders", ikon lonceng).
 * Satu notifikasi per tagihan per status per bulan (tidak berulang setiap hari).
 */
object FinanceBillNotifier {
    private const val CHANNEL = "scheduled_reminders"
    private const val PREFS = "finance_prefs"
    private const val KEY_SENT = "bill_notified"

    /** Tagihan manual aktif yang perlu diingatkan hari ini (jatuh tempo dekat atau terlambat, belum lunas). */
    fun dueBills(db: FinanceDb, now: Long = System.currentTimeMillis(), includeSilent: Boolean = false): List<Pair<FinanceBill, FinBillDue>> {
        val today = FinDate.of(now)
        val month = today.finMonth
        val runs = db.billRuns(month)
        return db.bills().filter { it.enabled && !it.autoPost && (includeSilent || it.remindDays >= 0) }
            .map { it to FinCalc.billDue(it.dayOfMonth, month, today, runs.containsKey(it.id), it.remindDays) }
            .filter { it.second.state == FinBillState.DUE_SOON || it.second.state == FinBillState.OVERDUE }
    }

    fun notifyDue(context: Context, now: Long = System.currentTimeMillis()): Int {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return 0
        val db = FinanceDb(app)
        val due = try { dueBills(db, now) } finally { db.close() }
        if (due.isEmpty()) return 0
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val month = FinDate.of(now).finMonth.key
        // Hanya simpan penanda bulan berjalan supaya set tidak tumbuh tanpa batas.
        val sent = (prefs.getStringSet(KEY_SENT, emptySet()) ?: emptySet()).filter { it.contains("|$month|") }.toMutableSet()
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Pengingat GITLS", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pesan dan pengingat yang dijadwalkan pengguna"
            })
        }
        var posted = 0
        for ((bill, info) in due) {
            val tag = "${bill.id}|$month|${info.state.name}"
            if (tag in sent) continue
            val open = PendingIntent.getActivity(app, 710_000 + bill.id.toInt(),
                Intent(app, MainActivity::class.java).putExtra("open_finance", true).putExtra("finance_tab", FINANCE_TAB_PLAN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
            val whenText = when {
                info.daysUntil < 0 -> "terlambat ${-info.daysUntil} hari"
                info.daysUntil == 0 -> "jatuh tempo hari ini"
                else -> "jatuh tempo ${info.daysUntil} hari lagi"
            }
            val body = "${bill.title} • ${FinMoney.rp(bill.amount)} • $whenText"
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(app)
            builder.setSmallIcon(R.drawable.ic_bell)
                .setContentTitle("Tagihan: ${bill.title}")
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(open)
            runCatching { manager.notify(720_000 + bill.id.toInt(), builder.build()) }.onSuccess { sent.add(tag); posted++ }
        }
        prefs.edit().putStringSet(KEY_SENT, sent).apply()
        return posted
    }
}
