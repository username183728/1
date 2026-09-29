package com.example.aidetest

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            rescheduleAll(context)
            return
        }
        val id = intent.getIntExtra("id", 0)
        if (id == 0) return
        val prefs = context.getSharedPreferences("mytools_prefs", Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString("scheduled_reminders", "[]") ?: "[]") }.getOrElse { JSONArray() }
        var item: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o?.optInt("id") == id) { item = o; break }
        }
        val data = item ?: return
        if (!data.optBoolean("enabled", true)) return
        val message = data.optString("body").ifBlank { data.optString("note") }.ifBlank { "Pengingat MyTools" }
        val reminderName = data.optString("message").ifBlank { "Pengingat MyTools" }
        val hour = data.optInt("hour", 0)
        val minute = data.optInt("minute", 0)
        showNotification(context, id, data.optString("category", "kustom"), reminderName, message, hour, minute)
        scheduleNext(context, data)
    }

    private fun showNotification(context: Context, id: Int, category: String, titleText: String, message: String, hour: Int, minute: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "scheduled_reminders"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Pengingat MyTools", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pesan dan pengingat yang dijadwalkan pengguna"
            })
        }
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val title = titleText.ifBlank {
            when (category) {
                "obat" -> "Pengingat obat"
                "ulangtahun" -> "Ulang tahun"
                "kegiatan" -> "Pengingat kegiatan"
                else -> "Pengingat MyTools"
            }
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(context, channelId) else @Suppress("DEPRECATION") android.app.Notification.Builder(context)
        builder.setSmallIcon(com.example.aidetest.R.drawable.app_icon)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(android.app.Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(open)
        manager.notify(id, builder.build())
    }

    private fun scheduleNext(context: Context, data: JSONObject) {
        val repeat = data.optString("repeat", "daily")
        if (repeat == "date") return
        val next = nextTime(data, System.currentTimeMillis()) ?: return
        val id = data.optInt("id")
        val intent = Intent(context, ReminderReceiver::class.java).apply { putExtra("id", id) }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, id, intent, flags)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending) }
            .onFailure { alarm.set(AlarmManager.RTC_WAKEUP, next, pending) }
    }

    private fun nextTime(data: JSONObject, from: Long): Long? {
        val cal = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val hour = data.optInt("hour", 0); val minute = data.optInt("minute", 0)
        when (data.optString("repeat", "daily")) {
            "today" -> {
                cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
                if (cal.timeInMillis > from) return cal.timeInMillis
            }
            "yearly" -> {
                cal.set(Calendar.MONTH, data.optInt("month", 0)); cal.set(Calendar.DAY_OF_MONTH, data.optInt("dayOfMonth", 1)); cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
                if (cal.timeInMillis <= from) cal.add(Calendar.YEAR, 1); return cal.timeInMillis
            }
            "monthly" -> {
                cal.set(Calendar.DAY_OF_MONTH, data.optInt("dayOfMonth", 1).coerceIn(1, 28)); cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
                if (cal.timeInMillis <= from) cal.add(Calendar.MONTH, 1); return cal.timeInMillis
            }
            "interval" -> {
                val every = data.optInt("every", 1).coerceAtLeast(1)
                val start = Calendar.getInstance().apply {
                    timeInMillis = data.optLong("start", from)
                    set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                while (start.timeInMillis <= from) start.add(Calendar.DAY_OF_YEAR, every)
                return start.timeInMillis
            }
            "weekdays" -> {
                cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
                repeat(8) { if (cal.timeInMillis > from && cal.get(Calendar.DAY_OF_WEEK) in Calendar.MONDAY..Calendar.FRIDAY) return cal.timeInMillis; cal.add(Calendar.DAY_OF_YEAR, 1) }
            }
            "selected_days" -> {
                val days = data.optJSONArray("days") ?: return null
                cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
                repeat(8) { if (cal.timeInMillis > from && contains(days, cal.get(Calendar.DAY_OF_WEEK))) return cal.timeInMillis; cal.add(Calendar.DAY_OF_YEAR, 1) }
            }
            else -> {
                cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
                if (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_YEAR, 1); return cal.timeInMillis
            }
        }
        return null
    }

    private fun contains(arr: JSONArray, value: Int): Boolean { for (i in 0 until arr.length()) if (arr.optInt(i) == value) return true; return false }

    private fun rescheduleAll(context: Context) {
        val prefs = context.getSharedPreferences("mytools_prefs", Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString("scheduled_reminders", "[]") ?: "[]") }.getOrElse { JSONArray() }
        for (i in 0 until arr.length()) {
            val data = arr.optJSONObject(i) ?: continue
            if (data.optInt("id") != 0 && data.optString("message").isNotBlank()) scheduleNext(context, data)
        }
    }
}
