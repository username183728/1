package com.example.aidetest

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.RemoteInput
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Handles inline replies for user-created Kustom notification conversations. */
class CustomNotificationReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REPLY = "com.example.aidetest.KUSTOM_REPLY"
        const val ACTION_DELAYED = "com.example.aidetest.KUSTOM_DELAYED_REPLY"
        const val EXTRA_ID = "kustom_id"
        const val EXTRA_REPLY = "kustom_reply"
        const val REMOTE_KEY = "kustom_reply_text"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, 0)
        if (id == 0) return
        val reply = if (intent.action == ACTION_REPLY) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTE_KEY)?.toString()?.trim().orEmpty()
        } else intent.getStringExtra(EXTRA_REPLY).orEmpty()
        if (reply.isBlank()) return

        val prefs = context.getSharedPreferences("mytools_prefs", Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString("scheduled_reminders", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val item = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optInt("id") == id } ?: return
        if (!item.optBoolean("enabled", true) || item.optString("category") != "kustom") return

        if (intent.action == ACTION_DELAYED) {
            postReply(context, item, id, reply)
            return
        }

        val rules = item.optJSONArray("customRules") ?: JSONArray()
        val normalized = normalize(reply)
        var response: String? = null
        var delay = 0
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val triggers = rule.optJSONArray("triggers")
            var matched = false
            if (triggers != null) {
                for (j in 0 until triggers.length()) {
                    if (normalize(triggers.optString(j)) == normalized) { matched = true; break }
                }
            } else {
                val inputs = rule.optString("input").split(Regex("[|,;:]")).map { normalize(it) }.filter { it.isNotBlank() }
                matched = inputs.any { it == normalized }
            }
            if (matched) {
                response = rule.optString("reply").trim().ifBlank { null }
                delay = rule.optInt("delayMinutes", 0).coerceIn(0, 1440)
                break
            }
        }
        if (response == null) {
            response = item.optString("fallbackReply").trim().ifBlank { null }
            delay = item.optInt("fallbackDelayMinutes", 0).coerceIn(0, 1440)
        }
        if (response == null) return

        if (delay > 0) {
            val delayed = Intent(context, CustomNotificationReceiver::class.java).apply {
                action = ACTION_DELAYED
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_REPLY, response)
            }
            val pi = PendingIntent.getBroadcast(context, id + 700000, delayed,
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = System.currentTimeMillis() + delay * 60_000L
            runCatching { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                .onFailure { alarm.set(AlarmManager.RTC_WAKEUP, at, pi) }
        } else {
            postReply(context, item, id, response)
        }
    }

    private fun postReply(context: Context, item: JSONObject, id: Int, response: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "kustom_conversations"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Kustom", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Percakapan notifikasi Kustom yang dibuat pengguna"
            })
        }
        val open = PendingIntent.getActivity(context, id + 900000,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())
        val replyIntent = Intent(context, CustomNotificationReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_ID, id)
        }
        val replyPending = PendingIntent.getBroadcast(context, id + 600001, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag())
        val remote = android.app.RemoteInput.Builder(REMOTE_KEY).setLabel("Tulis balasan…").build()
        val replyAction = android.app.Notification.Action.Builder(
            com.example.aidetest.R.drawable.ic_bell, "Balas", replyPending).addRemoteInput(remote).build()
        val title = item.optString("senderName").ifBlank { item.optString("message").ifBlank { "Kustom" } }
        val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(context, channelId)
        else @Suppress("DEPRECATION") android.app.Notification.Builder(context)
        builder.setSmallIcon(com.example.aidetest.R.drawable.ic_bell)
            .setContentTitle(title)
            .setContentText(response)
            .setStyle(android.app.Notification.BigTextStyle().bigText(response))
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(replyAction)
        manager.notify(id + 100000, builder.build())
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.getDefault()).replace(Regex("\\s+"), " ")
    private fun immutableFlag(): Int = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
    private fun mutableFlag(): Int = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
}
