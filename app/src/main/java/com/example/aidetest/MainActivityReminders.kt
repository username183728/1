package com.example.aidetest

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import java.util.concurrent.RejectedExecutionException
import android.Manifest
import android.app.*
import android.app.usage.StorageStatsManager
import android.os.StatFs
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.*
import android.provider.Settings
import android.provider.MediaStore
import android.media.ImageReader
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.text.InputType
import android.view.*
import android.widget.*
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.MultiFormatReader
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.common.HybridBinarizer
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.*
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.roundToInt
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback
import com.airbnb.lottie.LottieListener


internal fun MainActivity.rmIc(name: String, sp: Float = 20f, color: Int = rmDark): MdiIconView =
        MdiIconView(this).apply { setIconName(name); setIconSize(sp); setTextColor(color) }

internal fun MainActivity.rmIconBox(iconName: String, size: Int = 46): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        background = bg(Color.WHITE, size / 2, rmCardLine)
        addView(rmIc(iconName, 22f))
    }

internal fun MainActivity.rmSwitch(checked: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(this).apply {
        isChecked = checked
        if (Build.VERSION.SDK_INT >= 23) {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(Color.WHITE, Color.rgb(250, 251, 252)))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(rmDark, Color.rgb(222, 228, 232)))
        }
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

internal fun MainActivity.rmButton(caption: String, icon: String, primary: Boolean, onClick: () -> Unit): LinearLayout {
    val activity = this
    val fg = if (primary) Color.WHITE else rmDark
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = if (primary) bg(rmDark, 16) else bg(if (isDarkTheme) panel2 else Color.rgb(245, 247, 248), 16, rmCardLine)
        isClickable = true
        isFocusable = true
        addView(rmIc(icon, 18f, fg), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        addView(TextView(activity).apply {
            text = caption; textSize = 14f; setTextColor(fg)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        setOnClickListener { onClick() }
    }
}
internal fun MainActivity.rmSection(titleText: String, hint: String? = null): TextView = TextView(this).apply {
        val sb = android.text.SpannableStringBuilder(titleText)
        if (hint != null) {
            val start = sb.length
            sb.append(" ").append(hint)
            sb.setSpan(android.text.style.ForegroundColorSpan(textMuted), start, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text = sb
        textSize = 13f
        setTextColor(textMain)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(2), dp(14), 0, dp(7))
    }

internal fun MainActivity.rmRow(iconName: String, value: String, onClick: () -> Unit): LinearLayout = run {
    val activity = this
 LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(10), 0)
        background = bg(rmCardBg, 14, rmCardLine)
        isClickable = true
        addView(rmIc(iconName, 21f), LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(10) })
        addView(TextView(activity).apply {
            text = value; textSize = 13.5f; setTextColor(textMain); maxLines = 2
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(rmIc("chevron-right", 22f, rmGray), LinearLayout.LayoutParams(dp(28), dp(30)))
        setOnClickListener { onClick() }
    }

}

internal fun MainActivity.rmChoiceRow(iconName: String, titleText: String, sub: String?, trailing: View, onClick: () -> Unit): LinearLayout = run {
    val activity = this

    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = bg(rmCardBg, 14, rmCardLine)
        isClickable = true
        addView(rmIc(iconName, 21f), LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(12) })
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(activity).apply {
            text = titleText; textSize = 13.5f; setTextColor(textMain)
            if (sub != null) setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (sub != null) texts.addView(TextView(activity).apply { text = sub; textSize = 11f; setTextColor(textMuted) })
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        addView(trailing, LinearLayout.LayoutParams(dp(22), dp(22)))
        setOnClickListener { onClick() }
    }

}

internal fun MainActivity.rmCheck(on: Boolean): FrameLayout {
        val f = FrameLayout(this)
        f.addView(rmIc("check", 15f, Color.WHITE), FrameLayout.LayoutParams(-1, -1))
        rmSetCheck(f, on)
        return f
    }
internal fun MainActivity.rmSetCheck(f: FrameLayout, on: Boolean) {
        f.background = if (on) bg(rmDark, 6) else bg(Color.WHITE, 6, Color.rgb(205, 212, 218))
        f.getChildAt(0).visibility = if (on) View.VISIBLE else View.INVISIBLE
    }
internal fun MainActivity.rmRadio(on: Boolean): FrameLayout {
        val f = FrameLayout(this)
        val dot = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(rmDark)
            }
        }
        f.addView(dot, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
        rmSetRadio(f, on)
        return f
    }
internal fun MainActivity.rmSetRadio(f: FrameLayout, on: Boolean) {
        f.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(dp(if (on) 2 else 1), if (on) rmDark else Color.rgb(205, 212, 218))
        }
        f.getChildAt(0).visibility = if (on) View.VISIBLE else View.INVISIBLE
    }
internal fun MainActivity.rmDeriveCategory(name: String, birthday: Boolean): String {
        val n = name.lowercase(Locale.getDefault())
        return when {
            birthday || n.contains("ulang tahun") || n.contains("ultah") -> "ulangtahun"
            n.contains("obat") || n.contains("vitamin") || n.contains("suplemen") -> "obat"
            else -> "kegiatan"
        }
    }
internal fun MainActivity.rmItemCategory(o: JSONObject): String = when {
        o.optString("category") == "kustom" || o.optBoolean("customConversation") -> "kustom"
        o.optString("category") == "ulangtahun" || o.optBoolean("birthday") -> "ulangtahun"
        o.optString("category") == "obat" -> "kustom"
        o.optString("category") == "kegiatan" -> "kegiatan"
        else -> rmDeriveCategory(o.optString("message"), false)
    }

internal fun MainActivity.rmIconName(category: String, message: String): String {
        val n = message.lowercase(Locale.getDefault())
        return when {
            category == "obat" -> "pill"
            category == "kustom" -> "message-text-outline"
            category == "ulangtahun" -> "cake-variant-outline"
            Regex("\\bair\\b").containsMatchIn(n) -> "water-outline"
            n.contains("tidur") -> "sleep"
            else -> "calendar-blank-outline"
        }
    }
internal fun MainActivity.rmTopIn(names: Set<String>): Boolean = pageBackStack.lastOrNull()?.let { it.name in names } == true

internal fun MainActivity.rmPopToList() {
        while (rmTopIn(rmSubPages)) pageBackStack.removeLast()
        if (rmTopIn(setOf("Notifikasi"))) pageBackStack.removeLast()
        content.removeAllViews()
        reminderTool()
    }
internal fun MainActivity.rmPopToEditor() {
        while (rmTopIn(rmPickerPages)) pageBackStack.removeLast()
        if (rmTopIn(rmEditorPages)) pageBackStack.removeLast()
        content.removeAllViews()
        renderReminderEditor()
    }
internal fun MainActivity.rmRerenderEditor() { content.removeAllViews(); renderReminderEditor() }
internal fun MainActivity.rmRerenderList() { content.removeAllViews(); reminderTool() }
internal fun MainActivity.rmPromptText(titleText: String, hint: String, current: String, multiline: Boolean, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            this.hint = hint
            setTextColor(textMain)
            if (multiline) { setSingleLine(false); minLines = 3; gravity = Gravity.TOP } else setSingleLine(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setView(input)
            .setPositiveButton("Simpan") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("Batal", null)
            .show()
    }
internal fun MainActivity.reminderTool() {
    val activity = this
    clearPage("Notifikasi")

        val filters = listOf("all" to "Semua", "kustom" to "Kustom", "kegiatan" to "Kegiatan", "ulangtahun" to "Ulang Tahun")
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = bg(Color.rgb(240, 243, 245), 22)
        }
        for ((key, name) in filters) {
            val sel = reminderFilter == key
            val chip = TextView(this).apply {
                text = name
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(typeface, if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(if (sel) Color.WHITE else Color.rgb(74, 86, 96))
                if (sel) background = bg(rmDark, 18)
                setOnClickListener { reminderFilter = key; rmRerenderList() }
            }
            seg.addView(chip, LinearLayout.LayoutParams(0, dp(38), 1f))
        }
        content.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(14) })

        val arr = readReminders()
        var shown = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cat = rmItemCategory(o)
            if (reminderFilter != "all" && cat != reminderFilter) continue
            shown++
            val id = o.optInt("id")
            val msg = o.optString("message", "Tanpa judul")
            val time = "%02d:%02d".format(o.optInt("hour", 13), o.optInt("minute", 0))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(12), dp(12))
                background = bg(rmCardBg, 16, rmCardLine)
                isClickable = true
                setOnClickListener { showReminderDetail(o) }
            }
            row.addView(rmIconBox(rmIconName(cat, msg)), LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(12) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = msg; textSize = 14.5f; setTextColor(textMain)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            texts.addView(TextView(this).apply {
                text = "${repeatLabel(o.optString("repeat", "daily"), o)} • $time"
                textSize = 11.5f; setTextColor(textMuted); setPadding(0, dp(2), 0, 0)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(rmSwitch(o.optBoolean("enabled", true)) { on ->
                o.put("enabled", on)
                updateReminderObject(o)
                if (on) scheduleReminderData(activity, o) else cancelReminderAlarm(id)
            }, LinearLayout.LayoutParams(-2, -2))
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        if (shown == 0) {
            val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(28), dp(20), dp(20)) }
            empty.addView(rmIc("bell-outline", 40f, rmGray), LinearLayout.LayoutParams(-2, -2))
            empty.addView(TextView(this).apply {
                text = "Belum ada notifikasi."
                textSize = 12.5f; setTextColor(textMuted); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
            })
            content.addView(empty, LinearLayout.LayoutParams(-1, -2))
        }
        val addCaption = if (reminderFilter == "kustom") "Buat Kustom" else "Tambah notifikasi"
        content.addView(rmButton(addCaption, "plus", false) {
            if (reminderFilter == "kustom") showCustomEditor(null) else showReminderEditor(null)
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })
    }
internal fun MainActivity.showCustomEditor(existing: JSONObject?) {
        reminderEditing = existing
        reminderDraftMessage = existing?.optString("senderName", "") ?: ""
        reminderDraftBody = existing?.optString("openingMessage", "") ?: ""
        reminderDraftNote = existing?.optString("note", "") ?: ""
        reminderDraftHour = existing?.optInt("hour", 13) ?: 13
        reminderDraftMinute = existing?.optInt("minute", 0) ?: 0
        reminderDraftRepeat = existing?.optString("repeat", "today") ?: "today"
        reminderDraftEnabled = existing?.optBoolean("enabled", true) ?: true
        reminderDraftPayload = if (existing != null) JSONObject(existing.toString()) else JSONObject()
        reminderDraftPayload.put("category", "kustom")
        reminderDraftPayload.put("customConversation", true)
        renderCustomEditor()
    }
internal fun MainActivity.renderCustomEditor() {
    val activity = this
    clearPage(if (reminderEditing == null) "Buat Kustom" else "Edit Kustom")
        val rowLp = { LinearLayout.LayoutParams(-1, dp(54)) }
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = bg(rmCardBg, 16, rmCardLine)
        }
        preview.addView(TextView(this).apply {
            text = reminderDraftMessage.ifBlank { "Nama pengirim" }
            textSize = 15f; setTextColor(textMain); setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        preview.addView(TextView(this).apply {
            text = reminderDraftBody.ifBlank { "Pesan pertama akan muncul di notifikasi" }
            textSize = 12f; setTextColor(textMuted); setPadding(0, dp(4), 0, 0)
        })
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(78)).apply { topMargin = dp(4) })

        content.addView(rmSection("Nama pengirim"))
        val sender = EditText(this).apply {
            hint = "Contoh: Drfa"; textSize = 13f; setHintTextColor(rmGray); setTextColor(textMain)
            background = bg(rmCardBg, 14, rmCardLine); setPadding(dp(14), dp(12), dp(14), dp(12)); setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(reminderDraftMessage)
            addTextChangedListener(SimpleTextWatcher { reminderDraftMessage = it; (preview.getChildAt(0) as TextView).text = it.ifBlank { "Nama pengirim" } })
        }
        content.addView(sender, LinearLayout.LayoutParams(-1, dp(54)))

        content.addView(rmSection("Pesan pertama"))
        val opening = EditText(this).apply {
            hint = "Contoh: Hello"; textSize = 13f; setHintTextColor(rmGray); setTextColor(textMain)
            background = bg(rmCardBg, 14, rmCardLine); setPadding(dp(14), dp(12), dp(14), dp(12))
            gravity = Gravity.TOP or Gravity.START; minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(reminderDraftBody)
            addTextChangedListener(SimpleTextWatcher { reminderDraftBody = it; (preview.getChildAt(1) as TextView).text = it.ifBlank { "Pesan pertama akan muncul di notifikasi" } })
        }
        content.addView(opening, LinearLayout.LayoutParams(-1, dp(88)))

        content.addView(rmSection("Aturan balasan", "Atur jawaban otomatis berdasarkan teks yang kamu kirim dari notifikasi"))
        val rulesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refreshRules() {
            rulesBox.removeAllViews()
            val rules = reminderDraftPayload.optJSONArray("customRules") ?: JSONArray()
            if (rules.length() == 0) {
                rulesBox.addView(TextView(this).apply { text = "Belum ada aturan.\nContoh: kalau kamu balas \"baik\" / \"baik banget\", pengirim membalas \"Bagus kalau baik!\""; textSize = 12f; setTextColor(textMuted); setPadding(dp(4), dp(4), dp(4), dp(10)) })
            }
            for (i in 0 until rules.length()) {
                val rule = rules.optJSONObject(i) ?: continue
                val triggers = rule.optString("input", "")
                val reply = rule.optString("reply", "")
                val delay = rule.optInt("delayMinutes", 0)
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(10), dp(10)); background = bg(Color.WHITE, 14, rmCardLine); isClickable = true
                    setOnClickListener { showCustomRuleDialog(i, ::refreshRules) }
                }
                val triggerLabel = triggers.replace("|", " / ").replace(",", " / ")
                row.addView(TextView(activity).apply {
                    text = "Kamu bilang: $triggerLabel"
                    textSize = 12.5f; setTextColor(textMain); setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                row.addView(TextView(activity).apply {
                    val who = reminderDraftMessage.ifBlank { "Pengirim" }
                    text = "$who membalas: $reply${if (delay > 0) "  ·  jeda ${delay} mnt" else "  ·  langsung"}"
                    textSize = 11.5f; setTextColor(textMuted); setPadding(0, dp(3), 0, 0)
                })
                rulesBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
            }
        }
        refreshRules()
        content.addView(rulesBox)
        content.addView(rmButton("Tambah aturan balasan", "plus", false) { showCustomRuleDialog(-1, ::refreshRules) }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(2) })

        content.addView(rmSection("Balasan default", "opsional"))
        val fallback = EditText(this).apply {
            hint = "Jika tidak ada kata yang cocok"; textSize = 13f; setHintTextColor(rmGray); setTextColor(textMain)
            background = bg(rmCardBg, 14, rmCardLine); setPadding(dp(14), dp(12), dp(14), dp(12)); setSingleLine(false); minLines = 2
            setText(reminderDraftPayload.optString("fallbackReply", ""))
            addTextChangedListener(SimpleTextWatcher { reminderDraftPayload.put("fallbackReply", it) })
        }
        content.addView(fallback, LinearLayout.LayoutParams(-1, dp(70)))

        content.addView(rmSection("Waktu mulai"))
        content.addView(rmRow("clock-outline", "%02d:%02d".format(reminderDraftHour, reminderDraftMinute)) { showReminderTimePickerPage() }, rowLp())
        content.addView(rmSection("Tanggal / Pengulangan"))
        content.addView(rmRow("calendar-blank-outline", repeatLabel(reminderDraftRepeat, reminderDraftPayload)) { showRepeatPickerPage() }, rowLp())
        content.addView(rmSection("Status"))
        content.addView(rmRow("bell-outline", if (reminderDraftEnabled) "Aktif" else "Nonaktif") { reminderDraftEnabled = !reminderDraftEnabled; renderCustomEditor() }, rowLp())
        content.addView(rmSection("Catatan", "opsional"))
        val note = EditText(this).apply {
            hint = "Contoh: pesan untuk diri sendiri 1 bulan lagi"; textSize = 13f; setHintTextColor(rmGray); setTextColor(textMain)
            background = bg(rmCardBg, 14, rmCardLine); setPadding(dp(14), dp(12), dp(14), dp(12)); minLines = 2
            setText(reminderDraftNote); addTextChangedListener(SimpleTextWatcher { reminderDraftNote = it })
        }
        content.addView(note, LinearLayout.LayoutParams(-1, dp(76)))
        content.addView(rmButton("Simpan Kustom", "content-save-outline", true) { saveCustomDraft() }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(20); bottomMargin = dp(8) })
    }
internal fun MainActivity.showCustomRuleDialog(index: Int, after: () -> Unit) {
        val old = reminderDraftPayload.optJSONArray("customRules") ?: JSONArray()
        val existing = if (index >= 0) old.optJSONObject(index) else null
        val sender = reminderDraftMessage.ifBlank { "Pengirim" }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(4))
        }

        // Penjelasan alur
        val help = TextView(this).apply {
            text = "Alur percakapan:\n" +
                "1. Notifikasi dari “$sender” muncul.\n" +
                "2. Kamu membalas dari notifikasi.\n" +
                "3. Jika balasanmu cocok dengan kata di bawah,\n" +
                "   “$sender” membalas otomatis."
            textSize = 12f
            setTextColor(textMuted)
            setPadding(0, 0, 0, dp(12))
        }
        box.addView(help)

        box.addView(TextView(this).apply {
            text = "Jika kamu membalas dengan kata ini"
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textMain)
            setPadding(0, 0, 0, dp(4))
        })
        box.addView(TextView(this).apply {
            text = "Bisa beberapa pilihan, pisahkan dengan koma atau |  ·  Contoh: baik, baik banget, oke"
            textSize = 11f
            setTextColor(textMuted)
            setPadding(0, 0, 0, dp(4))
        })
        val trigger = EditText(this).apply {
            hint = "baik, baik banget, oke"
            setText(existing?.optString("input", "") ?: "")
            setSingleLine(false)
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(textMain)
            setHintTextColor(rmGray)
            background = bg(rmCardBg, 12, rmCardLine)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(trigger, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        box.addView(TextView(this).apply {
            text = "Maka “$sender” membalas"
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textMain)
            setPadding(0, 0, 0, dp(4))
        })
        box.addView(TextView(this).apply {
            text = "Tulis satu pesan balasan otomatis. Contoh: Bagus kalau baik!"
            textSize = 11f
            setTextColor(textMuted)
            setPadding(0, 0, 0, dp(4))
        })
        val reply = EditText(this).apply {
            hint = "Bagus kalau baik!"
            setText(existing?.optString("reply", "") ?: "")
            minLines = 3
            setTextColor(textMain)
            setHintTextColor(rmGray)
            background = bg(rmCardBg, 12, rmCardLine)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            gravity = Gravity.TOP or Gravity.START
        }
        box.addView(reply, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        box.addView(TextView(this).apply {
            text = "Jeda sebelum membalas (menit)"
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textMain)
            setPadding(0, 0, 0, dp(4))
        })
        box.addView(TextView(this).apply {
            text = "0 = langsung. Contoh: 1 = tunggu 1 menit."
            textSize = 11f
            setTextColor(textMuted)
            setPadding(0, 0, 0, dp(4))
        })
        val delay = EditText(this).apply {
            hint = "0"
            setText((existing?.optInt("delayMinutes", 0) ?: 0).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(textMain)
            setHintTextColor(rmGray)
            background = bg(rmCardBg, 12, rmCardLine)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setSingleLine(true)
        }
        box.addView(delay)

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (index >= 0) "Edit aturan balasan" else "Tambah aturan balasan")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val input = trigger.text.toString().trim()
                val out = reply.text.toString().trim()
                if (input.isBlank() || out.isBlank()) {
                    toast("Isi kata balasan kamu dan jawaban $sender")
                    return@setPositiveButton
                }
                // Normalize separators to |
                val normalizedInput = input
                    .split(Regex("[,|\\n;]+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("|")
                if (normalizedInput.isBlank()) {
                    toast("Minimal satu kata pemicu")
                    return@setPositiveButton
                }
                val rule = JSONObject()
                    .put("input", normalizedInput)
                    .put("reply", out)
                    .put("delayMinutes", (delay.text.toString().toIntOrNull() ?: 0).coerceIn(0, 1440))
                // Optional triggers array for clearer storage
                val triggers = JSONArray()
                normalizedInput.split("|").forEach { triggers.put(it) }
                rule.put("triggers", triggers)
                val next = JSONArray()
                for (i in 0 until old.length()) if (i != index) next.put(old.optJSONObject(i))
                next.put(rule)
                reminderDraftPayload.put("customRules", next)
                after()
                toast("Aturan disimpan")
            }
            .setNegativeButton("Batal", null)
        if (index >= 0) {
            dlg.setNeutralButton("Hapus") { _, _ ->
                val next = JSONArray()
                for (i in 0 until old.length()) if (i != index) next.put(old.optJSONObject(i))
                reminderDraftPayload.put("customRules", next)
                after()
                toast("Aturan dihapus")
            }
        }
        dlg.show()
    }
internal fun MainActivity.saveCustomDraft() {
        val sender = reminderDraftMessage.trim()
        val opening = reminderDraftBody.trim()
        if (sender.isBlank()) { toast("Nama pengirim wajib diisi"); return }
        if (opening.isBlank()) { toast("Pesan pertama wajib diisi"); return }
        val data = JSONObject(reminderDraftPayload.toString())
        data.put("message", sender)
        data.put("body", opening)
        data.put("senderName", sender)
        data.put("openingMessage", opening)
        data.put("note", reminderDraftNote.trim())
        data.put("hour", reminderDraftHour); data.put("minute", reminderDraftMinute)
        data.put("category", "kustom"); data.put("customConversation", true)
        data.put("repeat", reminderDraftRepeat); data.put("enabled", reminderDraftEnabled)
        if (!data.has("customRules")) data.put("customRules", JSONArray())
        saveReminder(data, reminderEditing)
        reminderEditing = null
        rmPopToList()
    }
internal fun MainActivity.showReminderEditor(existing: JSONObject?) {
        if (existing?.optString("category") == "kustom") { showCustomEditor(existing); return }
        reminderEditing = existing
        reminderDraftMessage = existing?.optString("message", "") ?: ""
        reminderDraftBody = existing?.optString("body", "") ?: ""
        reminderDraftNote = existing?.optString("note", "") ?: ""
        reminderDraftHour = existing?.optInt("hour", 13) ?: 13
        reminderDraftMinute = existing?.optInt("minute", 0) ?: 0
        reminderDraftCategory = existing?.optString("category", "kegiatan") ?: "kegiatan"
        reminderDraftRepeat = existing?.optString("repeat", "today") ?: "today"
        reminderDraftEnabled = existing?.optBoolean("enabled", true) ?: true
        reminderDraftPayload = if (existing != null) JSONObject(existing.toString()) else JSONObject()
        renderReminderEditor()
    }
internal fun MainActivity.renderReminderEditor() {
        val editing = reminderEditing != null
        val detailed = editing || reminderDraftMessage.isNotBlank() || reminderDraftBody.isNotBlank()
        clearPage(when { editing -> "Edit Notifikasi"; detailed -> "Tambah Notifikasi - Detail"; else -> "Tambah Notifikasi" })

        val timeText = "%02d:%02d".format(reminderDraftHour, reminderDraftMinute)
        val repeatText = repeatLabel(reminderDraftRepeat, reminderDraftPayload)
        val cat = rmDeriveCategory(reminderDraftMessage, reminderDraftPayload.optBoolean("birthday"))
        val rowLp = { LinearLayout.LayoutParams(-1, dp(54)) }

        // Preview selalu mengikuti judul dan pesan yang sedang diketik.
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10))
            background = bg(rmCardBg, 14, rmCardLine)
        }
        preview.addView(rmIconBox(rmIconName(cat, reminderDraftMessage)), LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(12) })
        val previewText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        previewText.addView(TextView(this).apply {
            text = reminderDraftMessage.ifBlank { "Contoh notifikasi" }
            textSize = 14.5f; setTextColor(textMain); setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        previewText.addView(TextView(this).apply {
            text = reminderDraftBody.ifBlank { "Isi pesan notifikasi akan tampil di sini" }
            textSize = 11.5f; setTextColor(textMuted); maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0)
        })
        preview.addView(previewText, LinearLayout.LayoutParams(0, -2, 1f))
        if (detailed) preview.addView(rmSwitch(reminderDraftEnabled) { reminderDraftEnabled = it }, LinearLayout.LayoutParams(-2, -2))
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(if (detailed) 78 else 72)).apply { topMargin = dp(4) })

        content.addView(rmSection("Judul"))
        val titleInput = EditText(this).apply {
            hint = "Contoh: Minum obat"; textSize = 13f; setHintTextColor(rmGray); setTextColor(textMain)
            background = bg(rmCardBg, 14, rmCardLine); setPadding(dp(14), dp(12), dp(14), dp(12)); setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(reminderDraftMessage)
            addTextChangedListener(SimpleTextWatcher {
                reminderDraftMessage = it
                (previewText.getChildAt(0) as TextView).text = it.ifBlank { "Contoh notifikasi" }
            })
        }
        content.addView(titleInput, LinearLayout.LayoutParams(-1, dp(54)))

        content.addView(rmSection("Pesan"))
        val bodyInput = EditText(this).apply {
            hint = "Isi pesan yang akan muncul saat notifikasi"; textSize = 13f; setHintTextColor(rmGray); setTextColor(textMain)
            background = bg(rmCardBg, 14, rmCardLine); setPadding(dp(14), dp(12), dp(14), dp(12))
            gravity = Gravity.TOP or Gravity.START; minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(reminderDraftBody)
            addTextChangedListener(SimpleTextWatcher {
                reminderDraftBody = it
                (previewText.getChildAt(1) as TextView).text = it.ifBlank { "Isi pesan notifikasi akan tampil di sini" }
            })
        }
        content.addView(bodyInput, LinearLayout.LayoutParams(-1, dp(92)))

        content.addView(rmSection("Waktu"))
        content.addView(rmRow("clock-outline", timeText) { showReminderTimePickerPage() }, rowLp())
        content.addView(rmSection("Tanggal / Pengulangan"))
        content.addView(rmRow("calendar-blank-outline", repeatText) { showRepeatPickerPage() }, rowLp())
        content.addView(rmSection("Notifikasi"))
        content.addView(rmRow("bell-outline", if (detailed) "Uji notifikasi" else "Tepat waktu · ketuk untuk uji") { sendTestNotification() }, rowLp())
        content.addView(rmSection("Catatan", "(opsional)"))
        val note = EditText(this).apply {
            hint = "Tambahkan catatan jika perlu…"; textSize = 13f; setHintTextColor(rmGray); setTextColor(textMain)
            background = bg(rmCardBg, 14, rmCardLine); setPadding(dp(14), dp(12), dp(14), dp(12))
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; setText(reminderDraftNote); addTextChangedListener(SimpleTextWatcher { reminderDraftNote = it })
        }
        content.addView(note, LinearLayout.LayoutParams(-1, dp(104)))

        content.addView(rmButton("Simpan", "content-save-outline", true) { saveReminderDraft() },
            LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(22); bottomMargin = dp(8) })
    }
internal fun MainActivity.saveReminderDraft() {
        val msg = reminderDraftMessage.trim()
        if (msg.isBlank()) { toast("Isi nama pesan terlebih dahulu"); return }
        val data = JSONObject(reminderDraftPayload.toString())
        data.put("message", msg)
        data.put("body", reminderDraftBody.trim())
        data.put("note", reminderDraftNote.trim())
        data.put("hour", reminderDraftHour); data.put("minute", reminderDraftMinute)
        data.put("category", rmDeriveCategory(msg, data.optBoolean("birthday")))
        data.put("repeat", reminderDraftRepeat)
        data.put("enabled", reminderDraftEnabled)
        if (reminderDraftRepeat != "selected_days") data.remove("days")
        saveReminder(data, reminderEditing)
        reminderEditing = null
        rmPopToList()
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.rmStepBtn(icon: String, step: () -> Unit): View {
        val v = LinearLayout(this).apply { gravity = Gravity.CENTER; addView(rmIc(icon, 26f, rmGray)) }
        val handler = Handler(Looper.getMainLooper())
        val repeater = object : Runnable { override fun run() { step(); handler.postDelayed(this, 90) } }
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { step(); handler.postDelayed(repeater, 400); view.isPressed = true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(repeater); view.isPressed = false }
            }
            true
        }
        return v
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.rmTimeBox(tv: TextView, isHour: Boolean, onValue: (Int) -> Unit): View {
        var downY = 0f
        tv.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = ev.y; true }
                MotionEvent.ACTION_UP -> {
                    val dy = ev.y - downY
                    if (kotlin.math.abs(dy) >= dp(18)) {
                        val steps = (kotlin.math.abs(dy) / dp(28)).toInt().coerceAtLeast(1)
                        val delta = if (dy < 0) steps else -steps
                        val current = tv.text.toString().toIntOrNull() ?: 0
                        val max = if (isHour) 23 else 59
                        onValue((current + delta + max + 1) % (max + 1))
                    } else {
                        rmPromptNumber(if (isHour) "Jam" else "Menit", if (isHour) "00–23" else "00–59", tv.text.toString().toIntOrNull() ?: 0, 0, if (isHour) 23 else 59, onValue)
                    }
                    true
                }
                else -> true
            }
        }
        return tv
    }
internal fun MainActivity.rmPromptNumber(title: String, hint: String, current: Int, min: Int, max: Int, onValue: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; this.hint = hint; setText("%02d".format(current)); setSelectAllOnFocus(true)
            setTextColor(textMain); setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this).setTitle("$title (ketik)").setView(input)
            .setPositiveButton("Pilih") { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n == null || n !in min..max) toast("$title harus $hint") else onValue(n)
            }.setNegativeButton("Batal", null).show()
    }
internal fun MainActivity.showReminderTimePickerPage() {
        clearPage("Pilih Waktu")
        var h = reminderDraftHour
        var m = reminderDraftMinute

        fun bigNumber(): TextView = TextView(this).apply {
            textSize = 34f; gravity = Gravity.CENTER; setTextColor(textMain); setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = bg(if (isDarkTheme) panel2 else Color.rgb(242, 245, 247), 16); isClickable = true
        }
        val hourTv = bigNumber(); val minTv = bigNumber()
        val periodViews = ArrayList<Triple<LinearLayout, TextView, TextView>>()
        fun periodOf(hh: Int): Int = if (hh in 6..11) 0 else if (hh in 12..17) 1 else 2
        fun refresh() {
            hourTv.text = "%02d".format(h); minTv.text = "%02d".format(m)
            val p = periodOf(h)
            for ((i, t) in periodViews.withIndex()) {
                val on = i == p
                t.first.background = if (on) bg(rmDark, 14) else bg(if (isDarkTheme) panel2 else Color.rgb(244, 246, 248), 14, rmCardLine)
                t.second.setTextColor(if (on) Color.WHITE else textMain); t.third.setTextColor(if (on) Color.rgb(200, 208, 214) else textMuted)
            }
        }
        rmTimeBox(hourTv, true) { h = it; refresh() }; rmTimeBox(minTv, false) { m = it; refresh() }
        fun stepCol(tv: TextView): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(tv, LinearLayout.LayoutParams(dp(88), dp(78)))
        }
        val wheel = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        wheel.addView(stepCol(hourTv))
        wheel.addView(TextView(this).apply { text = ":"; textSize = 34f; gravity = Gravity.CENTER; setTextColor(textMain); setTypeface(typeface, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(dp(30), dp(78)))
        wheel.addView(stepCol(minTv))
        content.addView(wheel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(40) })
        content.addView(View(this).apply { setBackgroundColor(rmCardLine) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(30); bottomMargin = dp(22) })
        val periods = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ranges = listOf(Triple("Pagi", "06:00 - 11:59", 8), Triple("Siang", "12:00 - 17:59", 13), Triple("Malam", "18:00 - 23:59", 20))
        for ((idx, r) in ranges.withIndex()) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; setOnClickListener { h = r.third; refresh() } }
            val t1 = TextView(this).apply { text = r.first; textSize = 12.5f; gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD) }
            val t2 = TextView(this).apply { text = r.second; textSize = 10f; gravity = Gravity.CENTER }
            box.addView(t1); box.addView(t2); periodViews.add(Triple(box, t1, t2)); periods.addView(box, LinearLayout.LayoutParams(0, dp(60), 1f).apply { if (idx > 0) leftMargin = dp(8) })
        }
        content.addView(periods, LinearLayout.LayoutParams(-1, dp(60))); refresh()
        content.addView(rmButton("Simpan", "content-save-outline", true) { reminderDraftHour = h; reminderDraftMinute = m; rmPopToEditor() }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(48); bottomMargin = dp(8) })
    }
internal fun MainActivity.showRepeatPickerPage() {
        clearPage("Pilih Tanggal / Pengulangan")
        val options = listOf(
            listOf("today", "Hari ini", "Jadwalkan hanya untuk hari ini", "calendar-today-outline"),
            listOf("daily", "Setiap hari", "Setiap hari pada waktu yang sama", "calendar-sync-outline"),
            listOf("selected_days", "Hari tertentu", "Pilih hari dalam seminggu", "calendar-clock-outline"),
            listOf("date", "Tanggal tertentu", "Pilih tanggal di kalender", "calendar-blank-outline"),
            listOf("monthly", "Setiap bulan", "Pada tanggal yang sama setiap bulan", "calendar-month-outline"),
            listOf("yearly", "Setiap tahun", "Pada tanggal dan bulan yang sama", "calendar-refresh-outline"),
            listOf("birthday", "Ulang tahun", "Peringatan pada tanggal lahir", "cake-variant-outline"),
            listOf("custom", "Kustom", "Atur sendiri", "tune-variant")
        )
        var sel = when {
            reminderDraftPayload.optBoolean("birthday") -> "birthday"
            reminderDraftRepeat == "weekdays" -> "selected_days"
            reminderDraftRepeat == "interval" -> "custom"
            else -> reminderDraftRepeat
        }
        val radios = HashMap<String, FrameLayout>()
        for (opt in options) {
            val id = opt[0]
            val radio = rmRadio(sel == id)
            radios[id] = radio
            val row = rmChoiceRow(opt[3], opt[1], opt[2], radio) {
                sel = id
                for ((k, v) in radios) rmSetRadio(v, k == sel)
            }
            content.addView(row, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(8) })
        }
        content.addView(rmButton("Pilih", "check", true) {
            when (sel) {
                "today" -> { rmSetRepeat("today"); rmPopToEditor() }
                "selected_days" -> showWeekdayPickerPage()
                "date", "monthly", "yearly", "birthday" -> showDatePickerPage(sel)
                "custom" -> showCustomIntervalDialog()
                else -> { rmSetRepeat("daily"); rmPopToEditor() }
            }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14); bottomMargin = dp(8) })
    }
internal fun MainActivity.rmSetRepeat(repeat: String, birthday: Boolean = false) {
        reminderDraftRepeat = repeat
        reminderDraftPayload.remove("birthday")
        if (birthday) reminderDraftPayload.put("birthday", true)
        if (repeat != "selected_days") reminderDraftPayload.remove("days")
    }
internal fun MainActivity.showDatePickerPage(mode: String) {
        val now = Calendar.getInstance()
        val y = reminderDraftPayload.optInt("year", now.get(Calendar.YEAR))
        val m = reminderDraftPayload.optInt("month", now.get(Calendar.MONTH))
        val d = reminderDraftPayload.optInt("dayOfMonth", now.get(Calendar.DAY_OF_MONTH))
        DatePickerDialog(this, { _, yy, mm, dd ->
            when (mode) {
                "date" -> {
                    rmSetRepeat("date")
                    reminderDraftPayload.put("year", yy); reminderDraftPayload.put("month", mm); reminderDraftPayload.put("dayOfMonth", dd)
                }
                "monthly" -> { rmSetRepeat("monthly"); reminderDraftPayload.put("dayOfMonth", dd) }
                "birthday" -> {
                    rmSetRepeat("yearly", true)
                    reminderDraftPayload.put("month", mm); reminderDraftPayload.put("dayOfMonth", dd)
                }
                else -> {
                    rmSetRepeat("yearly")
                    reminderDraftPayload.put("month", mm); reminderDraftPayload.put("dayOfMonth", dd)
                }
            }
            rmPopToEditor()
        }, y, m, d).also { dp0 -> dp0.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(bg(Color.WHITE, 24), dp(16))) }.show()
    }
internal fun MainActivity.showCustomIntervalDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(reminderDraftPayload.optInt("every", 2).toString())
            setTextColor(textMain)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Ulangi setiap berapa hari?")
            .setView(input)
            .setPositiveButton("Pilih") { _, _ ->
                val n = (input.text.toString().toIntOrNull() ?: 2).coerceIn(1, 365)
                rmSetRepeat("interval")
                reminderDraftPayload.put("every", n)
                reminderDraftPayload.put("start", System.currentTimeMillis())
                rmPopToEditor()
            }
            .setNegativeButton("Batal", null)
            .show()
    }
internal fun MainActivity.showWeekdayPickerPage() {
        clearPage("Pilih Hari")
        val names = arrayOf("Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu")
        val days = intArrayOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
        val selected = BooleanArray(7)
        val old = reminderDraftPayload.optJSONArray("days")
        for (i in 0 until (old?.length() ?: 0)) { val idx = days.indexOf(old?.optInt(i) ?: 0); if (idx >= 0) selected[idx] = true }
        if (reminderDraftRepeat == "weekdays") for (i in 0..4) selected[i] = true
        if (!selected.any { it }) { selected[0] = true; selected[2] = true; selected[4] = true }

        for (i in names.indices) {
            val box = rmCheck(selected[i])
            val row = rmChoiceRow("calendar-blank-outline", names[i], null, box) {
                selected[i] = !selected[i]
                rmSetCheck(box, selected[i])
            }
            content.addView(row, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(8) })
        }
        content.addView(rmButton("Simpan", "content-save-outline", true) {
            val arr = JSONArray()
            for (i in selected.indices) if (selected[i]) arr.put(days[i])
            if (arr.length() == 0) { toast("Pilih minimal satu hari"); return@rmButton }
            rmSetRepeat("selected_days")
            reminderDraftPayload.put("days", arr)
            rmPopToEditor()
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14); bottomMargin = dp(8) })
    }
internal fun MainActivity.showReminderDetail(existing: JSONObject) {
    val activity = this
    clearPage("Detail Notifikasi")
        val msg = existing.optString("message", "Notifikasi")
        val cat = rmItemCategory(existing)
        val id = existing.optInt("id")
        val timeText = "%02d:%02d".format(existing.optInt("hour", 13), existing.optInt("minute", 0))
        val repeatText = repeatLabel(existing.optString("repeat", "daily"), existing)

        val circle = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = bg(if (isDarkTheme) panel2 else Color.rgb(241, 244, 246), 38)
            addView(rmIc(rmIconName(cat, msg), 32f))
        }
        content.addView(circle, LinearLayout.LayoutParams(dp(76), dp(76)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10); bottomMargin = dp(12) })
        content.addView(TextView(this).apply {
            text = msg; textSize = 17f; gravity = Gravity.CENTER; setTextColor(textMain)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(TextView(this).apply {
            text = "$repeatText • $timeText"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(textMuted)
            setPadding(0, dp(3), 0, dp(12))
        }, LinearLayout.LayoutParams(-1, -2))
        val toggleWrap = LinearLayout(this).apply { gravity = Gravity.CENTER }
        toggleWrap.addView(rmSwitch(existing.optBoolean("enabled", true)) { on ->
            existing.put("enabled", on)
            updateReminderObject(existing)
            if (on) scheduleReminderData(activity, existing) else cancelReminderAlarm(id)
        })
        content.addView(toggleWrap, LinearLayout.LayoutParams(-1, dp(48)))

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = bg(rmCardBg, 16, rmCardLine)
        }
        fun addDetail(iconName: String, titleText: String, valueText: String) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
            r.addView(rmIc(iconName, 21f), LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(12) })
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(TextView(this).apply { text = titleText; textSize = 12.5f; setTextColor(textMain); setTypeface(typeface, android.graphics.Typeface.BOLD) })
            t.addView(TextView(this).apply { text = valueText; textSize = 11.5f; setTextColor(textMuted) })
            r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            detail.addView(r)
        }
        addDetail("clock-outline", "Waktu", timeText)
        addDetail("calendar-blank-outline", "Pengulangan", repeatText)
        addDetail("bell-outline", "Notifikasi", "Tepat waktu")
        val noteText = existing.optString("note").ifBlank { existing.optString("body") }
        if (noteText.isNotBlank()) addDetail("note-text-outline", "Catatan", noteText)
        content.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(16) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(rmButton("Edit", "pencil-outline", true) { showReminderEditor(existing) },
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(7) })
        actions.addView(rmButton("Hapus", "delete-outline", false) { cancelReminder(id); rmPopToList() },
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(7) })
        content.addView(actions, LinearLayout.LayoutParams(-1, -2))
    }
internal fun MainActivity.sendTestNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "scheduled_reminders"

        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Pengingat MyTools", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pesan dan pengingat yang dijadwalkan pengguna"
            })
        }

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3001)
            toast("Izinkan notifikasi, lalu coba lagi")
            return
        }
        val open = PendingIntent.getActivity(
            this, 99001, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, channelId) else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        builder.setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("GITLS")
            .setContentText("Notifikasi berhasil bekerja")
            .setAutoCancel(true)
            .setContentIntent(open)
        manager.notify(99001, builder.build())
        toast("Notifikasi uji dikirim")
    }
internal fun MainActivity.openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }.onFailure { toast("Tidak dapat membuka pengaturan alarm") }
    }
internal fun MainActivity.openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply { putExtra(Settings.EXTRA_APP_PACKAGE, packageName) }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }.onFailure { toast("Tidak dapat membuka pengaturan notifikasi") }
    }
internal fun MainActivity.saveReminder(data: JSONObject, existing: JSONObject?) {
        // Android 13+: tanpa izin notifikasi, pengingat berbunyi diam-diam (tidak tampil) — minta sekarang.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3001)
            toast("Izinkan notifikasi agar pengingat bisa tampil")
        }
        val id = existing?.optInt("id", 0)?.takeIf { it != 0 } ?: (System.currentTimeMillis() and 0x7fffffff).toInt()
        data.put("id", id)
        if (existing != null) cancelReminderAlarm(id)
        val arr = readReminders()
        val next = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optInt("id") != id) next.put(o)
        }
        if (!data.has("enabled")) data.put("enabled", true)
        next.put(data)
        prefs.edit().putString("scheduled_reminders", next.toString()).apply()
        if (data.optBoolean("enabled", true)) scheduleReminderData(this, data)
        toast(if (existing == null) "Pengingat disimpan" else "Pengingat diperbarui")
    }
internal fun MainActivity.scheduleReminderData(context: Context, data: JSONObject) {
        val id = data.optInt("id")
        val next = nextReminderTime(data, System.currentTimeMillis()) ?: return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("id", id)
            putExtra("message", data.optString("message"))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, id, intent, flags)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        if (canExact) {
            runCatching { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending) }
                .onFailure { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending) }
        } else {
            // Tanpa izin alarm tepat: tetap boleh berbunyi saat Doze (bisa bergeser beberapa menit).
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        }
    }
internal fun MainActivity.nextReminderTime(data: JSONObject, from: Long): Long? {
        val cal = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val hour = data.optInt("hour", 0); val minute = data.optInt("minute", 0)
        val repeat = data.optString("repeat", "daily")
        when (repeat) {
            "today" -> {
                cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
                return if (cal.timeInMillis > from) cal.timeInMillis else null
            }
            "date" -> {
                cal.set(Calendar.YEAR, data.optInt("year", cal.get(Calendar.YEAR)))
                cal.set(Calendar.MONTH, data.optInt("month", cal.get(Calendar.MONTH)))
                cal.set(Calendar.DAY_OF_MONTH, data.optInt("dayOfMonth", cal.get(Calendar.DAY_OF_MONTH)))
                cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
                return if (cal.timeInMillis > from) cal.timeInMillis else null
            }
            "yearly" -> {
                cal.set(Calendar.MONTH, data.optInt("month", 0)); cal.set(Calendar.DAY_OF_MONTH, data.optInt("dayOfMonth", 1)); cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
                if (cal.timeInMillis <= from) cal.add(Calendar.YEAR, 1)
                return cal.timeInMillis
            }
            "monthly" -> {
                cal.set(Calendar.DAY_OF_MONTH, data.optInt("dayOfMonth", 1).coerceIn(1, 28)); cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
                if (cal.timeInMillis <= from) cal.add(Calendar.MONTH, 1)
                return cal.timeInMillis
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
                for (i in 0..7) { if (cal.timeInMillis > from && cal.get(Calendar.DAY_OF_WEEK) in Calendar.MONDAY..Calendar.FRIDAY) return cal.timeInMillis; cal.add(Calendar.DAY_OF_YEAR, 1) }
                return null
            }
            "selected_days" -> {
                val days = data.optJSONArray("days") ?: return null
                cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
                for (i in 0..7) { if (cal.timeInMillis > from && containsJsonInt(days, cal.get(Calendar.DAY_OF_WEEK))) return cal.timeInMillis; cal.add(Calendar.DAY_OF_YEAR, 1) }
                return null
            }
            else -> {
                cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
                if (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_YEAR, 1)
                return cal.timeInMillis
            }
        }
    }
internal fun MainActivity.containsJsonInt(arr: JSONArray, value: Int): Boolean {
        for (i in 0 until arr.length()) if (arr.optInt(i) == value) return true
        return false
    }
internal fun MainActivity.readReminders(): JSONArray = runCatching { JSONArray(prefs.getString("scheduled_reminders", "[]") ?: "[]") }.getOrElse { JSONArray() }

internal fun MainActivity.reminderCategoryLabel(key: String): String = when (key) {
        "obat" -> "Obat"; "kegiatan" -> "Kegiatan"; "ulangtahun" -> "Ulang Tahun"; else -> "Kustom"
    }

internal fun MainActivity.rmMonthShort(m: Int): String =
        arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")[m.coerceIn(0, 11)]

internal fun MainActivity.repeatLabel(repeat: String, data: JSONObject?): String = when (repeat) {
        "today" -> "Hari ini"
        "weekdays" -> "Senin, Selasa, Rabu, Kamis, Jumat"
        "selected_days" -> {
            val arr = data?.optJSONArray("days")
            if (arr == null || arr.length() == 0) "Hari tertentu" else {
                val order = intArrayOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
                val names = arrayOf("Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu")
                val picked = order.indices.filter { containsJsonInt(arr, order[it]) }
                if (picked.size == 7) "Setiap hari" else picked.joinToString(", ") { names[it] }
            }
        }
        "date" -> if (data == null) "Tanggal tertentu" else
            "${data.optInt("dayOfMonth", 1)} ${rmMonthShort(data.optInt("month", 0))} ${data.optInt("year", Calendar.getInstance().get(Calendar.YEAR))}"
        "monthly" -> "Setiap bulan, tgl ${data?.optInt("dayOfMonth", 1) ?: 1}"
        "yearly" -> if (data == null) "Setiap tahun" else {
            val s = "${data.optInt("dayOfMonth", 1)} ${rmMonthShort(data.optInt("month", 0))}"
            if (data.optBoolean("birthday")) s else "Setiap tahun, $s"
        }
        "interval" -> "Setiap ${data?.optInt("every", 2) ?: 2} hari"
        else -> "Setiap hari"
    }

internal fun MainActivity.renderReminderList() {
    val activity = this
    val arr = readReminders()
    var shown = 0
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (reminderFilter != "all" && o.optString("category", "kustom") != reminderFilter) continue
        shown++
        val id = o.optInt("id")
        val msg = o.optString("message")
        val time = "%02d:%02d".format(o.optInt("hour"), o.optInt("minute"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(8), dp(10)); background = bg(panel2, 16, line) }
        val icon = ImageView(this).apply {
            setImageResource(when (o.optString("category")) {
                "obat" -> R.drawable.ic_medical
                "kegiatan" -> R.drawable.ic_calendar
                "ulangtahun" -> R.drawable.ic_cake
                else -> R.drawable.ic_bell
            })
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = bg(if (isDarkTheme) panel2 else Color.rgb(242,244,246), 14)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(10) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(msg, 13f, true))
        texts.addView(subLabel("${repeatLabel(o.optString("repeat", "daily"), o)} • $time", 11f))
        if (o.optString("note").isNotBlank()) texts.addView(subLabel(o.optString("note"), 10f))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this).apply {
            isChecked = o.optBoolean("enabled", true)
            setOnClickListener {
                o.put("enabled", isChecked)
                updateReminderObject(o)
                if (!isChecked) cancelReminderAlarm(id) else scheduleReminderData(activity, o)
            }
        }
        row.addView(toggle, LinearLayout.LayoutParams(dp(54), dp(48)))
        row.setOnClickListener { showReminderEditor(o) }
        row.setOnLongClickListener { cancelReminder(id); reminderTool(); true }
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    if (shown == 0) content.addView(subLabel(if (arr.length() == 0) "Belum ada pengingat. Tekan + untuk membuat pesan baru." else "Belum ada pengingat di kategori ini.", 12f))
}
internal fun MainActivity.updateReminderObject(updated: JSONObject) {
        val old = readReminders(); val next = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            next.put(if (o.optInt("id") == updated.optInt("id")) updated else o)
        }
        prefs.edit().putString("scheduled_reminders", next.toString()).apply()
    }
internal fun MainActivity.cancelReminderAlarm(id: Int) {
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ReminderReceiver::class.java)
        val flags = PendingIntent.FLAG_NO_CREATE or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        PendingIntent.getBroadcast(this, id, intent, flags)?.let { alarm.cancel(it); it.cancel() }
    }
internal fun MainActivity.cancelReminder(id: Int) {
        cancelReminderAlarm(id)
        val old = readReminders(); val next = JSONArray()
        for (i in 0 until old.length()) if (old.optJSONObject(i)?.optInt("id") != id) next.put(old.optJSONObject(i))
        prefs.edit().putString("scheduled_reminders", next.toString()).apply()
        toast("Pengingat dihapus")
    }
