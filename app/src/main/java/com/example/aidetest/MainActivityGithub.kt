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


internal fun MainActivity.batteryStatusIntent(): Intent? {
        val now = SystemClock.elapsedRealtime()
        val cached = batteryCache
        if (cached != null && now - batteryCacheAt < 1000L) return cached
        val fresh = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryCache = fresh
        batteryCacheAt = now
        return fresh
    }
internal fun MainActivity.showGhBitProcessError(errorTitle: String, errorMessage: String) {
        val host = ghStage ?: return
        val assistant = bitProcessErrorView ?: return
        val face = bitProcessFace

        val safe = errorMessage.replace("\n", " ").trim().take(150)
        // Step 13: Bit receives only the sanitized process context, never credentials.
        val chatMessage = BitRuntimeContext.errorContextForBit()
            ?: "Proses GitHub gagal: $errorTitle. $safe"
        bitPendingChatMessage = chatMessage
        BitRuntimeContext.onToolError(chatMessage)

        // Error reaction uses the separate bit_error.json asset (720..770).
        bitAnim?.playError()

        val label = assistant.findViewWithTag<TextView>("bit_error_label")
        label?.text = "Error"
        label?.contentDescription = "Error — lihat detail"
        label?.visibility = View.VISIBLE
        assistant.layoutParams = (assistant.layoutParams ?: FrameLayout.LayoutParams(dp(104), dp(58))).apply { width = dp(104); height = dp(58) }
        assistant.background = rippleBg(if (isDarkTheme) Color.rgb(43,43,48) else Color.WHITE, 24)
        assistant.elevation = dp(4).toFloat()
        assistant.visibility = View.VISIBLE
        assistant.alpha = 0f
        assistant.translationX = dp(18).toFloat()
        assistant.translationY = dp(8).toFloat()
        assistant.animate()
            .alpha(1f)
            .translationX(-dp(4).toFloat())
            .translationY(-dp(4).toFloat())
            .setDuration(360L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        // Small one-time pulse draws attention without constantly covering the process UI.
        assistant.animate().setStartDelay(360L).scaleX(1.06f).scaleY(1.06f).setDuration(130L).withEndAction {
            assistant.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
        }.start()
    }
internal fun MainActivity.createGhBitProcessAssistant(): FrameLayout {
        val wrapper = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(0), dp(0), dp(0), dp(0))
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            elevation = 0f
            visibility = View.VISIBLE
        }
        val faceHost = ghBitProcessAssistant()
        wrapper.addView(faceHost, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))

        val label = TextView(this).apply {
            tag = "bit_error_label"
            text = ""
            contentDescription = "Error — tekan untuk bertanya kepada Bit"
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (isDarkTheme) Color.WHITE else Color.rgb(30, 35, 40))
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(2), 0, dp(10), 0)
        }
        wrapper.addView(label, FrameLayout.LayoutParams(dp(48), dp(52), Gravity.CENTER_VERTICAL or Gravity.END))
        wrapper.setOnClickListener {
            val message = bitPendingChatMessage?.takeIf { it.isNotBlank() }
                ?: BitRuntimeContext.errorContextForBit()
                ?: "Proses GitHub gagal. Periksa log proses untuk detail."
            AlertDialog.Builder(this)
                .setTitle("Bit menemukan error")
                .setMessage(message.replace("\n", " ").take(900))
                .setPositiveButton("Tutup", null)
                .show()
        }
        addPressFeedback(wrapper)
        bitProcessErrorView = wrapper
        return wrapper
    }
internal fun MainActivity.zipPath(src: File, out: File, onBytes: ((Long) -> Unit)? = null) {
        val srcCanonical = src.canonicalFile
        val outCanonical = out.canonicalFile
        if (srcCanonical == outCanonical) throw IOException("File sumber dan ZIP tujuan tidak boleh sama")
        val buffer = ByteArray(64 * 1024)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outCanonical))).use { zos ->
            fun addFile(f: File, name: String) {
                zos.putNextEntry(ZipEntry(name))
                f.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        zos.write(buffer, 0, n)
                        onBytes?.invoke(n.toLong())
                    }
                }
                zos.closeEntry()
            }
            if (src.isFile) {
                addFile(src, src.name)
            } else {
                val base = src.parentFile ?: src
                src.walkTopDown().filter { it.isFile }.forEach { f ->
                    if (f.canonicalFile == outCanonical) return@forEach
                    val name = f.relativeTo(base).path.replace(File.separatorChar, '/')
                    addFile(f, name)
                }
            }
        }
    }
internal fun MainActivity.unzipSafe(zip: File, dest: File, onBytes: ((Long) -> Unit)? = null) {
        val destCanonical = dest.canonicalFile
        var totalBytes = 0L
        var entries = 0
        val maxEntries = 5000
        val maxTotalBytes = 256L * 1024L * 1024L
        val maxEntryBytes = 64L * 1024L * 1024L
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                entries++
                if (entries > maxEntries) throw IOException("ZIP terlalu banyak entry")
                val target = File(destCanonical, e.name).canonicalFile
                if (!target.path.startsWith(destCanonical.path + File.separator)) throw SecurityException("ZIP entry di luar folder tujuan: ${e.name}")
                if (e.isDirectory) {
                    if (!target.mkdirs() && !target.isDirectory) throw IOException("Gagal membuat folder: ${e.name}")
                } else {
                    target.parentFile?.mkdirs()
                    var entryBytes = 0L
                    FileOutputStream(target).use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = zis.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            totalBytes += read
                            if (entryBytes > maxEntryBytes || totalBytes > maxTotalBytes) throw IOException("ZIP melebihi batas ekstraksi aman")
                            out.write(buffer, 0, read)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }
/**
 * Hitung baris (1-based) dan kolom (1-based) dari posisi karakter 0-based dalam teks.
 */
internal fun jsonLineCol(text: String, charIndex: Int): Pair<Int, Int> {
    if (charIndex <= 0) return 1 to 1
    val safe = charIndex.coerceAtMost(text.length)
    var line = 1
    var col = 1
    for (i in 0 until safe) {
        if (text[i] == '\n') {
            line++
            col = 1
        } else {
            col++
        }
    }
    return line to col
}

/**
 * Ambil posisi karakter dari pesan JSONException Android (sering: "… at character N of …").
 * Kembalikan -1 jika tidak ditemukan.
 */
internal fun jsonErrorCharIndex(message: String?): Int {
    if (message.isNullOrBlank()) return -1
    val m = Regex("""at character\s+(\d+)""", RegexOption.IGNORE_CASE).find(message)
        ?: Regex("""character\s+(\d+)""", RegexOption.IGNORE_CASE).find(message)
        ?: return -1
    return m.groupValues[1].toIntOrNull() ?: -1
}

/** Validasi JSON + pesan error dengan baris/kolom bila tersedia. */
internal fun MainActivity.validateJsonDetailed(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return "Input kosong. Tempel JSON terlebih dahulu."
    return try {
        when {
            s.startsWith("{") -> JSONObject(s)
            s.startsWith("[") -> JSONArray(s)
            else -> error("JSON harus dimulai dengan { (object) atau [ (array).")
        }
        buildString {
            appendLine("✓ JSON valid (sintaks).")
            appendLine()
            appendLine("Artinya: teks ini adalah JSON yang terbentuk benar — kurung, koma, string, dan nilai sesuai aturan JSON.")
            appendLine()
            appendLine("Catatan penting:")
            appendLine("• Valid secara sintaks ≠ data sesuai skema tertentu.")
            appendLine("• Skema (mis. OpenAPI, JSON Schema) memeriksa field wajib, tipe, enum, dll.")
            appendLine("• Tool ini hanya memeriksa sintaks JSON, bukan skema API/aplikasi Anda.")
        }
    } catch (e: Exception) {
        val msg = e.message ?: e.javaClass.simpleName
        val idx = jsonErrorCharIndex(msg)
        val loc = if (idx >= 0) {
            val (line, col) = jsonLineCol(raw, idx)
            "Baris $line, kolom $col (karakter ke-$idx)."
        } else {
            // Fallback: coba temukan karakter mencurigakan di sekitar parse gagal
            "Posisi baris/kolom tidak dilaporkan parser. Periksa kurung, koma, dan tanda kutip."
        }
        buildString {
            appendLine("✗ JSON tidak valid.")
            appendLine()
            appendLine("Lokasi: $loc")
            appendLine("Detail: $msg")
            appendLine()
            appendLine("Tips: pastikan string memakai \" (bukan '), tidak ada trailing comma, dan nilai null/true/false/angka tanpa tanda kutip yang salah.")
        }
    }
}

internal fun MainActivity.jsonTool() {
        clearPage("JSON Tools")
        toolWorkspace(
            "JSON Formatter / Tools",
            "Format, minify, validasi — input dan hasil dipisah. Input asli tidak diubah jika format gagal.",
            "code-json"
        )

        content.addView(
            infoCard(
                "Perilaku aman",
                "• Format hanya menambah/mengurangi spasi (indent) — nilai data tidak diubah.\n" +
                    "• Jika JSON tidak valid, kotak INPUT tetap utuh; error muncul di HASIL.\n" +
                    "• Validasi sintaks ≠ data sesuai skema API (JSON Schema / OpenAPI)."
            )
        )

        toolWorkspaceSection("INPUT (asli)", "Tempel JSON di sini. Tidak ditimpa otomatis saat format gagal.")
        val input = edit("Tempel JSON di sini…", true)
        input.minLines = 6
        content.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        toolWorkspaceSection("INDENTASI", "Hanya dipakai saat Format (pretty-print).")
        val indentSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                hostActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("2 spasi", "4 spasi (default)", "8 spasi")
            )
            setSelection(1)
        }
        content.addView(indentSpinner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        fun indentSpaces(): Int = when (indentSpinner.selectedItemPosition) {
            0 -> 2
            2 -> 8
            else -> 4
        }

        toolWorkspaceSection("HASIL", "Output format/minify/validasi. Salin dari sini tanpa mengubah input.")
        val resultBox = edit("Hasil muncul di sini…", true)
        resultBox.minLines = 5
        content.addView(resultBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        fun showResult(text: String, isError: Boolean = false) {
            resultBox.setText(text)
            if (isError) toast("Gagal — input tidak diubah")
        }

        fun copyText(label: String, text: String) {
            if (text.isBlank()) {
                toast("Tidak ada teks untuk disalin")
                return
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            toast("$label disalin (${text.length} karakter)")
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("Format") {
            val s = input.text.toString()
            if (s.isBlank()) {
                showResult("Input kosong.", true)
                return@button
            }
            runCatching { prettyJson(s, indentSpaces()) }
                .onSuccess { formatted ->
                    showResult(formatted, false)
                    toast("Format OK — input asli tetap ada")
                }
                .onFailure {
                    showResult(
                        validateJsonDetailed(s).replace(
                            "✗ JSON tidak valid.",
                            "✗ Format gagal — JSON tidak valid.\nInput asli tidak diubah."
                        ),
                        true
                    )
                }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        row1.addView(button("Minify") {
            val s = input.text.toString()
            if (s.isBlank()) {
                showResult("Input kosong.", true)
                return@button
            }
            runCatching { minifyJson(s) }
                .onSuccess { mini ->
                    showResult(mini, false)
                    toast("Minify OK — input asli tetap ada")
                }
                .onFailure {
                    showResult(
                        validateJsonDetailed(s).replace(
                            "✗ JSON tidak valid.",
                            "✗ Minify gagal — JSON tidak valid.\nInput asli tidak diubah."
                        ),
                        true
                    )
                }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(6) })
        content.addView(row1, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("Validate") {
            showResult(validateJsonDetailed(input.text.toString()))
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        row2.addView(button("Salin hasil") {
            copyText("JSON hasil", resultBox.text.toString())
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(6) })
        content.addView(row2, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(button("Salin input") {
            copyText("JSON input", input.text.toString())
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        row3.addView(button("Terapkan hasil → input") {
            val r = resultBox.text.toString()
            if (r.isBlank() || r.startsWith("✗") || r.startsWith("Input kosong") || r.startsWith("✓")) {
                toast("Hasil bukan JSON yang bisa diterapkan. Format/Minify dulu.")
                return@button
            }
            val ok = runCatching {
                val t = r.trim()
                when {
                    t.startsWith("{") -> JSONObject(t)
                    t.startsWith("[") -> JSONArray(t)
                    else -> error("bukan JSON")
                }
            }.isSuccess
            if (!ok) {
                toast("Hasil bukan JSON valid — input tidak diubah")
                return@button
            }
            input.setText(r)
            toast("Hasil diterapkan ke input (atas persetujuan Anda)")
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(6) })
        content.addView(row3, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        content.addView(button("Escape String") {
            showResult(JSONObjectLite.escape(input.text.toString()))
        })
        content.addView(button("Preview Lottie") {
            val s = input.text.toString()
            if (LottieJson.looksLikeLottie(s)) showLottiePreview(s) else toast("Ini bukan JSON animasi Lottie")
        })
        content.addView(button("Buka di Editor") {
            editor(null, "json")
            editorBox?.setText(input.text.toString())
        })
    }

