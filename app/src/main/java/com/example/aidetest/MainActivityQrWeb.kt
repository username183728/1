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


internal fun MainActivity.qrSourceLabel(id: String) = when (id) { "file" -> "File"; "foto" -> "Foto & Scan"; else -> "Teks / Link" }

internal fun MainActivity.qrSourceIcon(id: String) = when (id) { "file" -> "▤"; "foto" -> "▧"; else -> "✎" }

internal fun MainActivity.qrTool() {
        clearPage("QR Scanner")
        qrSourceExpanded = false
        qrSelectedSource = null
        qrPickedUri = null
        qrPickedName = null
        qrScanBusy = false
        qrScanResult = null
        renderQrScanner()
    }
internal fun MainActivity.renderQrScanner() {
        content.removeAllViews()
        // Tombol kanan atas khusus untuk langsung membuka pemindai QR kamera.
        action.text = "⌗"
        action.textSize = 21f
        action.contentDescription = "Scan QR"
        action.setOnClickListener { scanQrWithCamera() }
        if (qrSelectedSource == null) content.addView(qrHeroBox())
        content.addView(qrSourceSelectorRow())
        if (qrSourceExpanded) {
            content.addView(qrSourceOptionsBox())
        } else if (qrSelectedSource != null) {
            qrSelectedSource?.let { content.addView(qrSourcePanel(it)) }
        }
    }
internal fun MainActivity.qrHeroBox(): LinearLayout = run {
    val activity = this
 LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(34), dp(24), dp(30))
        background = bg(panel2, 18)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        addView(TextView(activity).apply {
            text = "⛶"; textSize = 32f; gravity = Gravity.CENTER; setTextColor(textMuted)
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(14) })
        addView(label("QR Scanner", 17f, true).apply { gravity = Gravity.CENTER })
        addView(subLabel("Scan kode QR atau buat QR sendiri.", 12f).apply { gravity = Gravity.CENTER })
    }

}

internal fun MainActivity.qrSourceSelectorRow(): LinearLayout = run {
    val activity = this
 LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = bg(panel2, 14, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = if (qrSourceExpanded) dp(6) else dp(14) }
        isClickable = true
        setOnClickListener { qrSourceExpanded = !qrSourceExpanded; renderQrScanner() }
        addView(TextView(activity).apply {
            text = if (qrSelectedSource == null) "▦" else qrSourceIcon(qrSelectedSource ?: "")
            textSize = 15f; setTextColor(textMuted)
        }, LinearLayout.LayoutParams(dp(24), -2))
        addView(label(if (qrSelectedSource == null) "Pilih sumber input" else qrSourceLabel(qrSelectedSource ?: ""), 14f).apply {
            setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(activity).apply {
            text = if (qrSourceExpanded) "⌃" else "⌄"; textSize = 13f; setTextColor(textMuted)
        })
    }

}

internal fun MainActivity.qrSourceOptionsBox(): LinearLayout = run {
    val activity = this
 LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = bg(panel2, 14, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        qrSources.forEachIndexed { i, (id, icon, name) ->
            addView(qrOptionRow(id, icon, name, when (id) {
                "file" -> "Pilih file gambar (PNG, JPG, dll)."
                "foto" -> "Ambil foto langsung dari kamera atau galeri."
                else -> "Masukkan teks atau link untuk dibuat QR."
            }))
            if (i != qrSources.lastIndex) addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(10), dp(2), dp(10), dp(2)) }
                setBackgroundColor(line)
            })
        }
    }

}

internal fun MainActivity.qrOptionRow(id: String, icon: String, titleText: String, desc: String): LinearLayout = run {
    val activity = this
 LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isClickable = true
        setOnClickListener {
            qrSelectedSource = id; qrSourceExpanded = false
            qrPickedUri = null; qrPickedName = null; qrScanResult = null; qrScanBusy = false
            renderQrScanner()
        }
        addView(TextView(activity).apply {
            text = icon; textSize = 16f; gravity = Gravity.CENTER; setTextColor(textMain)
            background = bg(Color.rgb(235, 236, 239), 10)
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(titleText, 14f, true).apply { setPadding(dp(2), 0, dp(2), dp(1)) })
            addView(subLabel(desc, 11f))
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

}

internal fun MainActivity.qrSourcePanel(source: String): LinearLayout = run {
    val activity = this
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (source == "teks") {
            val generator = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = bg(panel2, Ds.RADIUS_LG, line)
            }
            val typeButton = qrDarkButton("Jenis data: Teks / URL") {}
            val formatButton = qrDarkButton("Format: QR Code") {}
            styleAsSecondary(typeButton)
            styleAsSecondary(formatButton)
            typeButton.setTextColor(textMain)
            formatButton.setTextColor(textMain)
            val fields = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            // Satu ruang hasil: generate berikutnya mengganti QR sebelumnya (tidak menumpuk).
            val resultHost = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            generator.addView(label("Buat QR / Barcode", 15f, true))
            generator.addView(subLabel("Pilih jenis data, isi, lalu buat kode.", 11.5f).apply {
                setPadding(0, dp(2), 0, dp(8))
            })
            generator.addView(typeButton, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(6) })
            generator.addView(formatButton, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) })
            generator.addView(fields)
            generator.addView(resultHost)

            var dataMode = "Teks / URL"
            var selectedFormat = BarcodeFormat.QR_CODE
            val inputs = linkedMapOf<String, EditText>()
            var hiddenWifi: CheckBox? = null
            var wifiSecurity = "WPA"

            fun addField(key: String, hintText: String, multiLine: Boolean = false): EditText {
                val e = edit(hintText, multiLine)
                if (!multiLine) e.maxLines = 1
                inputs[key] = e
                fields.addView(e, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
                return e
            }

            fun rebuildFields() {
                fields.removeAllViews()
                inputs.clear()
                hiddenWifi = null
                when (dataMode) {
                    "Teks / URL" -> addField("text", "Teks, URL, kode, atau isi lainnya…", true)
                    "Wi-Fi" -> {
                        addField("ssid", "Nama Wi-Fi / SSID")
                        addField("password", "Password Wi-Fi")
                        val security = qrDarkButton("Keamanan: WPA/WPA2") {}
                        security.setOnClickListener {
                            val items = arrayOf("WPA/WPA2", "WEP", "Tanpa password")
                            AlertDialog.Builder(activity)
                                .setTitle("Keamanan Wi-Fi")
                                .setSingleChoiceItems(items, when (wifiSecurity) {
                                    "WEP" -> 1
                                    "nopass" -> 2
                                    else -> 0
                                }) { dialog, which ->
                                    wifiSecurity = when (which) { 1 -> "WEP"; 2 -> "nopass"; else -> "WPA" }
                                    security.text = "Keamanan: " + when (wifiSecurity) {
                                        "WEP" -> "WEP"
                                        "nopass" -> "Tanpa password"
                                        else -> "WPA/WPA2"
                                    }
                                    dialog.dismiss()
                                }.show()
                        }
                        fields.addView(security, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(7) })
                        hiddenWifi = CheckBox(activity).apply {
                            text = "Jaringan tersembunyi"
                            setTextColor(textMain)
                        }
                        fields.addView(hiddenWifi)
                    }
                    "Kontak" -> {
                        addField("name", "Nama kontak")
                        addField("phone", "Nomor telepon")
                        addField("email", "Email")
                        addField("org", "Organisasi")
                    }
                    "Email" -> {
                        addField("to", "Alamat email tujuan")
                        addField("subject", "Subjek")
                        addField("body", "Isi email", true)
                    }
                    "Telepon" -> addField("phone", "Nomor telepon")
                    "SMS" -> {
                        addField("phone", "Nomor tujuan")
                        addField("body", "Pesan SMS", true)
                    }
                    "Lokasi" -> {
                        addField("lat", "Latitude, contoh -6.200000")
                        addField("lon", "Longitude, contoh 106.816666")
                        addField("label", "Nama lokasi (opsional)")
                    }
                }
            }

            fun encodedData(): String? {
                fun value(key: String) = inputs[key]?.text?.toString()?.trim().orEmpty()
                return when (dataMode) {
                    "Teks / URL" -> value("text")
                    "Wi-Fi" -> {
                        val ssid = value("ssid")
                        val password = value("password")
                        if (ssid.isEmpty()) return null
                        val escapedSsid = ssid.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:")
                        val escapedPassword = password.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:")
                        val hidden = if (hiddenWifi?.isChecked == true) "true" else "false"
                        "WIFI:T:$wifiSecurity;S:$escapedSsid;P:$escapedPassword;H:$hidden;;"
                    }
                    "Kontak" -> {
                        val name = value("name")
                        if (name.isEmpty()) return null
                        buildString {
                            append("BEGIN:VCARD\nVERSION:3.0\n")
                            append("FN:").append(name).append("\n")
                            if (value("phone").isNotEmpty()) append("TEL:").append(value("phone")).append("\n")
                            if (value("email").isNotEmpty()) append("EMAIL:").append(value("email")).append("\n")
                            if (value("org").isNotEmpty()) append("ORG:").append(value("org")).append("\n")
                            append("END:VCARD")
                        }
                    }
                    "Email" -> {
                        val to = value("to")
                        if (to.isEmpty()) return null
                        "mailto:$to?subject=${Uri.encode(value("subject"))}&body=${Uri.encode(value("body"))}"
                    }
                    "Telepon" -> {
                        val phone = value("phone")
                        if (phone.isEmpty()) return null
                        "tel:$phone"
                    }
                    "SMS" -> {
                        val phone = value("phone")
                        if (phone.isEmpty()) return null
                        "SMSTO:$phone:${value("body")}"
                    }
                    "Lokasi" -> {
                        val lat = value("lat")
                        val lon = value("lon")
                        if (lat.isEmpty() || lon.isEmpty()) return null
                        "geo:$lat,$lon" + if (value("label").isNotEmpty()) "?q=$lat,$lon(${Uri.encode(value("label"))})" else ""
                    }
                    else -> null
                }?.takeIf { it.isNotBlank() }
            }

            typeButton.setOnClickListener {
                val items = arrayOf("Teks / URL", "Wi-Fi", "Kontak", "Email", "Telepon", "SMS", "Lokasi")
                AlertDialog.Builder(activity)
                    .setTitle("Jenis data")
                    .setSingleChoiceItems(items, items.indexOf(dataMode)) { dialog, which ->
                        dataMode = items[which]
                        typeButton.text = "Jenis data: $dataMode"
                        rebuildFields()
                        dialog.dismiss()
                    }.show()
            }

            val formatEntries = listOf(
                "QR Code" to BarcodeFormat.QR_CODE,
                "Data Matrix" to BarcodeFormat.DATA_MATRIX,
                "Aztec" to BarcodeFormat.AZTEC,
                "PDF417" to BarcodeFormat.PDF_417,
                "Code 128" to BarcodeFormat.CODE_128,
                "Code 39" to BarcodeFormat.CODE_39,
                "Code 93" to BarcodeFormat.CODE_93,
                "Codabar" to BarcodeFormat.CODABAR,
                "EAN-13" to BarcodeFormat.EAN_13,
                "EAN-8" to BarcodeFormat.EAN_8,
                "ITF" to BarcodeFormat.ITF,
                "UPC-A" to BarcodeFormat.UPC_A,
                "UPC-E" to BarcodeFormat.UPC_E
            )
            formatButton.setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("Format kode")
                    .setSingleChoiceItems(formatEntries.map { it.first }.toTypedArray(), formatEntries.indexOfFirst { it.second == selectedFormat }) { dialog, which ->
                        selectedFormat = formatEntries[which].second
                        formatButton.text = "Format: ${formatEntries[which].first}"
                        dialog.dismiss()
                    }.show()
            }

            rebuildFields()

            // Hasil di atas tombol Buat Kode; hanya 1 slot (replace, bukan stack).
            val makeBtn = qrDarkButton("Buat Kode") {
                val payload = encodedData()
                if (payload.isNullOrBlank()) {
                    toast("Data belum lengkap")
                    return@qrDarkButton
                }
                generateQr(activity, payload, selectedFormat)?.let { bmp ->
                    resultHost.removeAllViews()
                    resultHost.visibility = View.VISIBLE
                    resultHost.addView(
                        qrResultCard(
                            bmp = bmp,
                            resultText = null,
                            codeLabel = formatEntries.first { it.second == selectedFormat }.first,
                            onShareText = { payload }
                        )
                    )
                    // Scroll agar hasil terlihat tanpa menambah tinggi berlebih.
                    content.post {
                        resultHost.requestFocus()
                        scroll.smoothScrollTo(0, (resultHost.parent as? View)?.top?.coerceAtLeast(0) ?: 0)
                    }
                } ?: toast("Format barcode menolak data ini. Periksa isi dan format.")
            }
            generator.addView(makeBtn, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
            addView(generator)
        } else {
            addView(qrUploadBox(source))
            addView(TextView(activity).apply {
                text = "ⓘ  Format gambar: JPG, PNG, WEBP\n    Pemindaian mendukung QR dan barcode umum."
                textSize = 11f; setTextColor(textMuted)
                setPadding(dp(2), dp(10), dp(2), dp(4))
            })
            if (qrScanBusy) {
                addView(subLabel("Memindai QR / barcode…", 12f))
            } else if (qrPickedUri != null) {
                addView(qrResultCard(bmp = null, resultText = qrScanResult, onShareText = { qrScanResult ?: "" }))
            }
        }
    }
}
internal fun MainActivity.qrUploadBox(source: String): LinearLayout = run {
    val activity = this
 LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(30), dp(20), dp(26))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(panel2); cornerRadius = dp(16).toFloat()
            setStroke(dp(1), line)
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        if (qrPickedUri != null) {
            addView(ImageView(activity).apply {
                setImageURI(qrPickedUri)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(140), dp(140)).apply { bottomMargin = dp(10) })
            addView(subLabel(qrPickedName ?: "Gambar terpilih", 11f).apply { gravity = Gravity.CENTER })
        } else {
            addView(TextView(activity).apply {
                text = "▧"; textSize = 30f; setTextColor(textMuted); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })
            addView(subLabel(
                if (source == "foto") "Pilih foto atau scan QR dengan kamera" else "Ketuk untuk memilih file gambar",
                12f
            ).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(14)) })
        }
        addView(qrDarkButton(if (source == "foto") "Foto & Scan" else "Pilih File") {
            if (source == "foto") pickQrPhoto() else pickQrFile()
        })
    }

}

internal fun MainActivity.qrDarkButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        background = bg(Color.rgb(17, 17, 19), 24)
        minHeight = dp(46)
        setPadding(dp(24), dp(2), dp(24), dp(2))
        setStateListAnimator(null)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(46)).apply { gravity = Gravity.CENTER; bottomMargin = dp(6) }
    }

internal fun MainActivity.qrResultCard(bmp: Bitmap?, resultText: String?, codeLabel: String = "QR", onShareText: () -> String): LinearLayout = run {
    val activity = this
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = bg(if (isDarkTheme) panel else Color.rgb(250, 251, 252), Ds.RADIUS_MD, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(Ds.SPACE_SM)
            bottomMargin = dp(Ds.SPACE_SM)
        }
        addView(label(
            when {
                bmp != null -> codeLabel
                resultText != null -> "Hasil pindaian"
                else -> "Tidak terdeteksi"
            },
            13f,
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })
        if (bmp != null) {
            // Satu preview ringkas — tidak menumpuk, tidak memenuhi layar.
            val preview = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = bg(Color.WHITE, Ds.RADIUS_SM)
            }
            preview.addView(ImageView(activity).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = "Preview $codeLabel"
            }, LinearLayout.LayoutParams(dp(168), dp(168)).apply { gravity = Gravity.CENTER })
            val payload = onShareText().trim()
            if (payload.isNotBlank()) {
                preview.addView(TextView(activity).apply {
                    text = payload
                    textSize = 12f
                    setTextColor(Color.rgb(50, 50, 54))
                    gravity = Gravity.CENTER
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(6), dp(8), dp(6), 0)
                })
            }
            addView(preview, LinearLayout.LayoutParams(-1, -2))
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            }
            val dl = button("Simpan") { saveQrBitmap(bmp) }
            styleAsSecondary(dl)
            val sh = button("Bagikan") { shareQrBitmap(bmp, onShareText()) }
            styleAsSecondary(sh)
            row.addView(dl, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            row.addView(sh, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
            addView(row)
        } else if (resultText != null) {
            addView(TextView(activity).apply {
                text = resultText
                textSize = 13f
                setTextColor(textMain)
                setPadding(dp(4), dp(6), dp(4), dp(8))
                setTextIsSelectable(true)
            })
            val share = button("Salin") { copyText(onShareText()) }
            styleAsSecondary(share)
            addView(share, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
    }
}

internal fun MainActivity.saveQrBitmap(bitmap: Bitmap) {
        runCatching {
            val name = "MyTools_QR_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.png"
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MyTools")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Penyimpanan tidak tersedia")
            contentResolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) error("Gagal menulis QR")
            } ?: error("Tidak bisa membuka penyimpanan")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
            toast("QR disimpan ke Pictures/MyTools")
        }.onFailure { toast("Download QR gagal: ${it.message}") }
    }
internal fun MainActivity.shareQrBitmap(bitmap: Bitmap, text: String) {
        runCatching {
            val file = File(cacheDir, "MyTools_QR_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Bagikan QR"))
        }.onFailure { toast("Gagal membagikan QR: ${it.message}") }
    }
internal fun MainActivity.generateQr(ctx: Context, text: String, format: BarcodeFormat = BarcodeFormat.QR_CODE): Bitmap? = runCatching {
    val matrix = MultiFormatWriter().encode(
        text,
        format,
        700,
        700,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 2, com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8")
    )
    val bmp = Bitmap.createBitmap(700, 700, Bitmap.Config.ARGB_8888)
    for (x in 0 until 700) for (y in 0 until 700) {
        bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
    }
    bmp
}.getOrNull()

internal fun MainActivity.scanQrWithCamera() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), QR_CAMERA_PERMISSION_REQUEST)
            return
        }
        runCatching {
            val photoFile = File(cacheDir, "qr_scan_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
            qrCameraOutUri = uri
            qrSelectedSource = "foto"
            qrSourceExpanded = false
            qrPickedUri = null
            qrPickedName = null
            qrScanResult = null
            qrScanBusy = false

            val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (camera.resolveActivity(packageManager) == null) {
                toast("Kamera tidak tersedia")
                return
            }
            startActivityForResult(camera, 1043)
        }.onFailure {
            toast("Tidak bisa membuka kamera: ${it.message}")
        }
    }
internal fun MainActivity.pickQrFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
        }, 1041)
    }
internal fun MainActivity.pickQrPhoto() {
        val gallery = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        val chooser = Intent.createChooser(gallery, "Pilih sumber foto")
        runCatching {
            val photoFile = File(cacheDir, "qr_capture_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
            qrCameraOutUri = uri
            val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            if (camera.resolveActivity(packageManager) != null) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
            }
        }
        startActivityForResult(chooser, 1042)
    }
internal fun MainActivity.decodeQrFromUri(uri: Uri) {
        qrScanBusy = true; qrPickedUri = uri; qrScanResult = null
        renderQrScanner()
        toolThread {
            val text = runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                    ?: error("Gambar tidak bisa dibuka")
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Gambar tidak valid")
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                    ?: error("Gambar tidak bisa dibuka")
                try {
                    val pixels = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    val source = RGBLuminanceSource(bmp.width, bmp.height, pixels)
                    val binary = BinaryBitmap(HybridBinarizer(source))
                    MultiFormatReader().decode(binary).text
                } finally {
                    bmp.recycle()
                }
            }
            runOnToolUiThread {
                qrScanBusy = false
                qrScanResult = text.getOrNull() ?: run {
                    if (text.exceptionOrNull() !is NotFoundException) toast("Gagal membaca gambar")
                    null
                }
                if (qrScanResult == null) toast("Tidak ditemukan kode QR pada gambar ini")
                renderQrScanner()
            }
        }
    }
internal fun MainActivity.webProjectBuilderTool() {
    val activity = this
    // ---------- WEB PROJECT BUILDER ----------

        fun sectionTitle(textValue: String, icon: String): View = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(8))
            addView(MdiIconView(activity).apply {
                setIconName(icon); setIconSize(20f); setTextColor(textMain)
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(6) }
            })
            addView(TextView(activity).apply {
                text = textValue; textSize = 13f; setTextColor(textMain)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }

        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = bg(if (isDarkTheme) panel else Color.rgb(246,248,250), 18, if (isDarkTheme) line else Color.rgb(225,230,234))
        }
        val introRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        introRow.addView(MdiIconView(activity).apply {
            setIconName("web"); setIconSize(30f); setTextColor(textMain)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) }
        })
        val introText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        introText.addView(TextView(activity).apply {
            text = "Web Project Builder"; textSize = 17f; setTextColor(textMain)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        introText.addView(TextView(activity).apply {
            text = "HTML + CSS + JavaScript → Build → Preview → Host"
            textSize = 11.5f; setTextColor(textMuted); setPadding(0, dp(3), 0, 0)
        })
        introRow.addView(introText, LinearLayout.LayoutParams(0,-2,1f))
        intro.addView(introRow)
        content.addView(intro, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val name = edit("Nama Project", false).apply { hint = "my-website" }
        content.addView(sectionTitle("PROJECT", "folder-outline"))
        content.addView(name)

        val html = edit("HTML", true)
        val css = edit("CSS", true)
        val js = edit("JavaScript", true)
        val files = listOf(
            Triple("HTML", "language-html5", html),
            Triple("CSS", "language-css3", css),
            Triple("JavaScript", "language-javascript", js)
        )
        files.forEach { (labelText, iconName, editorTarget) ->
            editorTarget.visibility = View.GONE
            val row = settingRowClickable(labelText, "Editor ${labelText.lowercase()}", "Edit source $labelText", iconName) {
                webCodeEditor(labelText, editorTarget)
            }
            content.addView(row, LinearLayout.LayoutParams(-1, dp(62)).apply { bottomMargin = dp(6) })
        }

        content.addView(sectionTitle("IMPORT", "file-import-outline"))
        val importRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
        listOf("HTML" to WEB_HTML_PICK_REQUEST, "CSS" to WEB_CSS_PICK_REQUEST, "JS" to WEB_JS_PICK_REQUEST).forEach { (labelText, request) ->
            val b = button(labelText) {
                webImportTarget = when (request) {
                    WEB_HTML_PICK_REQUEST -> html
                    WEB_CSS_PICK_REQUEST -> css
                    else -> js
                }
                val type = when (request) {
                    WEB_HTML_PICK_REQUEST -> "text/html"
                    WEB_CSS_PICK_REQUEST -> "text/css"
                    else -> "text/javascript"
                }
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { this.type = type; addCategory(Intent.CATEGORY_OPENABLE) }, request)
            }
            importRow.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        content.addView(importRow)

        content.addView(sectionTitle("BUILD PIPELINE", "source-branch-check"))
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = bg(if (isDarkTheme) panel2 else Color.WHITE, 16, if (isDarkTheme) line else Color.rgb(225,230,234))
        }
        val statusIcon = MdiIconView(this).apply {
            setIconName("circle-small"); setIconSize(24f); setTextColor(textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) }
        }
        statusCard.addView(statusIcon)
        val status = TextView(this).apply {
            text = "BELUM BUILD"
            textSize = 13f; setTextColor(textMain); setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        webBuildStatusView = status
        statusCard.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(statusCard, LinearLayout.LayoutParams(-1, dp(58)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val buildButton = button("Build") {
            buildWebProject(name.text.toString(), html.text.toString(), css.text.toString(), js.text.toString())
        }
        webHostButton = button("Host Wi-Fi") { hostHomeWifiProject() }.apply { isEnabled = false; alpha = 0.45f }
        actions.addView(buildButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(5); topMargin = dp(8) })
        actions.addView(webHostButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(5); topMargin = dp(8) })
        content.addView(actions)

        content.addView(sectionTitle("OUTPUT", "monitor-dashboard"))
        val outputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        outputRow.addView(button("Preview") { previewWebProject() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(5) })
        outputRow.addView(button("Project Files") { openWebFolder() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(5) })
        content.addView(outputRow)
        content.addView(subLabel("Build membuat folder project lokal. Setelah status SUCCESS, Preview dan Host Wi-Fi dapat digunakan.", 11f).apply { setPadding(dp(3), dp(7), dp(3), 0) })
    }
internal fun MainActivity.pickWebFile(target: EditText, requestCode: Int) {
        webImportTarget = target
        val type = when(requestCode) { WEB_HTML_PICK_REQUEST -> "text/html"; WEB_CSS_PICK_REQUEST -> "text/css"; else -> "text/javascript" }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { this.type=type; addCategory(Intent.CATEGORY_OPENABLE) }, requestCode)
    }
internal fun MainActivity.webCodeEditor(mode: String = "HTML", target: EditText? = null) {
        val normalized = when (mode.uppercase(Locale.getDefault())) {
            "HTML" -> "html"
            "CSS" -> "css"
            "JAVASCRIPT", "JS" -> "js"
            else -> "code"
        }
        editorExternalTarget = target
        editorExternalMode = normalized
        editorFile = null
        editorSourceUri = null
        editor(null, normalized)
    }
internal fun MainActivity.buildWebProject(
    projectName:String,
    html:String,
    css:String,
    js:String,
    onComplete: ((Boolean, File?) -> Unit)? = null
) {
    if (webBuildRunning) { toast("Build masih berjalan"); return }
    val h=html.trim(); val c=css.trim(); val j=js.trim()
    webBuildReady=false
    webHostButton?.isEnabled=false; webHostButton?.alpha=0.45f
    webBuildStatusView?.text="MEMERIKSA FILE…"
    if (h.isBlank()) { webBuildStatusView?.text="GAGAL • HTML wajib diisi"; toast("HTML wajib diisi"); onComplete?.invoke(false, null); return }

    val localCss=Regex("""(?i)(?:href|src)\s*=\s*[\"']([^\"']+\.css(?:\?[^\"']*)?)[\"']""").findAll(h).map { it.groupValues[1].substringBefore('?') }.filter { !it.startsWith("http://") && !it.startsWith("https://") && !it.startsWith("//") && !it.startsWith("data:") }.toList()
    val localJs=Regex("""(?i)<script[^>]+src\s*=\s*[\"']([^\"']+\.js(?:\?[^\"']*)?)[\"']""").findAll(h).map { it.groupValues[1].substringBefore('?') }.filter { !it.startsWith("http://") && !it.startsWith("https://") && !it.startsWith("//") && !it.startsWith("data:") }.toList()
    val warnings=mutableListOf<String>()
    if (localCss.isNotEmpty() && c.isBlank()) warnings.add("HTML memanggil CSS lokal: ${localCss.joinToString(", ")}, tetapi file CSS belum diisi.")
    if (localJs.isNotEmpty() && j.isBlank()) warnings.add("HTML memanggil JavaScript lokal: ${localJs.joinToString(", ")}, tetapi file JS belum diisi.")
    if (warnings.isNotEmpty()) {
        webBuildStatusView?.text="GAGAL • Dependency belum lengkap"
        AlertDialog.Builder(this).setTitle("Project belum lengkap").setMessage(warnings.joinToString("\n\n") + "\n\nIsi file yang kurang lalu Build lagi.").setPositiveButton("OK",null).show()
        onComplete?.invoke(false, null)
        return
    }

    val safe=(projectName.trim().ifBlank{"website"}).replace(Regex("[^A-Za-z0-9_-]"),"_")
    val root = File(filesDir, "web_projects").apply { mkdirs() }
    var dir = File(root, safe)
    if (dir.exists()) {
        var index = 2
        while (File(root, "${safe}_$index").exists()) index++
        dir = File(root, "${safe}_$index")
    }
    val buildDir = dir
    webBuildRunning = true
    prefs.edit().putBoolean("last_web_build_ok", false).apply()
    webBuildStatusView?.text="BUILDING…"
    toolThread {
        var ok = false
        try {
            if (buildDir.exists()) error("Nama project sudah dipakai")
            var htmlBody=h
            val full=h.contains("<html",true)
            if (!full) {
                htmlBody="<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><link rel=\"stylesheet\" href=\"style.css\"></head><body>$h<script src=\"script.js\"></script></body></html>"
            } else {
                if (c.isNotBlank() && !Regex("(?i)<link[^>]+href\\s*=\\s*[\"'](?:./)?style\\.css").containsMatchIn(h)) {
                    htmlBody=if (Regex("(?i)</head>").containsMatchIn(htmlBody)) htmlBody.replace(Regex("(?i)</head>"),"<link rel=\"stylesheet\" href=\"style.css\"></head>") else "<link rel=\"stylesheet\" href=\"style.css\">"+htmlBody
                }
                if (j.isNotBlank() && !Regex("(?i)<script[^>]+src\\s*=\\s*[\"'](?:./)?script\\.js").containsMatchIn(h)) {
                    htmlBody=if (Regex("(?i)</body>").containsMatchIn(htmlBody)) htmlBody.replace(Regex("(?i)</body>"),"<script src=\"script.js\"></script></body>") else htmlBody+"<script src=\"script.js\"></script>"
                }
            }
            val tempRoot = File(root, ".${buildDir.name}.gitls-build-${java.util.UUID.randomUUID()}").apply { mkdirs() }
            try {
                File(tempRoot,"index.html").writeText(htmlBody, StandardCharsets.UTF_8)
                if (c.isNotBlank()) {
                    File(tempRoot,"style.css").writeText(c, StandardCharsets.UTF_8)
                    localCss.map { File(it).name }.filter { it.isNotBlank() && it != "style.css" }.distinct().forEach { File(tempRoot,it).writeText(c, StandardCharsets.UTF_8) }
                }
                if (j.isNotBlank()) {
                    File(tempRoot,"script.js").writeText(j, StandardCharsets.UTF_8)
                    localJs.map { File(it).name }.filter { it.isNotBlank() && it != "script.js" }.distinct().forEach { File(tempRoot,it).writeText(j, StandardCharsets.UTF_8) }
                }
                // Install only after all output files were written successfully.
                buildDir.deleteRecursively()
                if (!tempRoot.renameTo(buildDir)) error("Tidak bisa memasang hasil build")
            } finally {
                if (tempRoot.exists()) tempRoot.deleteRecursively()
            }
            prefs.edit().putString("last_web_project",buildDir.absolutePath).putBoolean("last_web_build_ok",true).apply()
            ok=true
            runOnToolUiThread {
                webBuildReady=true
                webBuildStatusView?.text="SUCCESS • ${buildDir.name}"
                webHostButton?.isEnabled=true; webHostButton?.alpha=1f
                toast("Build SUCCESS: ${buildDir.name}")
                onComplete?.invoke(true, buildDir)
            }
        } catch (e: Exception) {
            runCatching { if (buildDir.exists() && !ok) buildDir.deleteRecursively() }
            prefs.edit().putBoolean("last_web_build_ok",false).apply()
            runOnToolUiThread {
                webBuildStatusView?.text="GAGAL • ${e.message ?: "Build gagal"}"
                toast("Build gagal: ${e.message ?: "kesalahan tidak diketahui"}")
                onComplete?.invoke(false, null)
            }
        } finally {
            webBuildRunning=false
        }
    }
}

internal fun MainActivity.previewWebProject() {
        val path=prefs.getString("last_web_project","") ?: ""
        if(path.isBlank() || !File(path,"index.html").isFile || !prefs.getBoolean("last_web_build_ok",false)){toast("Build website dulu sampai SUCCESS");return}
        val index = File(path, "index.html")
        if (index.length() > 8L * 1024L * 1024L) {
            toast("Preview terlalu besar (maks 8 MB)")
            return
        }
        previewHtmlText(index.readText(StandardCharsets.UTF_8), "HTML")
    }
internal fun MainActivity.previewHtmlText(html: String, mode: String, baseUrl: String? = null) {
        if (html.toByteArray(StandardCharsets.UTF_8).size > 8L * 1024L * 1024L) {
            toast("Preview terlalu besar (maks 8 MB)")
            appendEditorConsole("ERROR: Preview dibatalkan karena ukuran HTML melebihi 8 MB")
            return
        }
        clearPage("Preview")
        // Preview polos: hanya output HTML, tanpa tombol mengganggu.
        content.setPadding(0, 0, 0, 0)
        editorBottomBar.visibility = View.GONE
        editorMore.visibility = View.GONE
        appendEditorConsole("Preview dibuka (${mode})")
        val w = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowContentAccess = false
            // Izinkan file access jika preview project lokal (baseUrl file://...)
            val localProject = !baseUrl.isNullOrBlank() && baseUrl.startsWith("file:")
            // JavaScript halaman TIDAK boleh membaca file lain lewat file:// (mis. data privat aplikasi);
            // berkas proyek tetap dimuat lewat <script>/<link>/<img> karena allowFileAccess.
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.domStorageEnabled = true
            settings.allowFileAccess = localProject
            setBackgroundColor(if (isDarkTheme) Color.rgb(18, 18, 20) else Color.WHITE)
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    val msg = consoleMessage ?: return true
                    val level = when (msg.messageLevel()) {
                        android.webkit.ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                        android.webkit.ConsoleMessage.MessageLevel.WARNING -> "WARN"
                        android.webkit.ConsoleMessage.MessageLevel.DEBUG -> "DEBUG"
                        else -> "LOG"
                    }
                    appendEditorConsole("$level: ${msg.message()}  (${msg.sourceId()}:${msg.lineNumber()})")
                    return true
                }
            }
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    appendEditorConsole("ERROR: page ${description ?: "unknown"} ($errorCode)")
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && request?.isForMainFrame == true) {
                        appendEditorConsole("ERROR: HTTP ${errorResponse?.statusCode ?: "?"} ${errorResponse?.reasonPhrase ?: ""}".trim())
                    }
                }

                // Android WebView memakai renderer terpisah. Jika HTML/JS atau GPU renderer
                // membuat renderer mati, tanpa callback ini Android dapat menutup seluruh APK.
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    // didCrash() requires API 26 (O); onRenderProcessGone itself is API 26+.
                    val didCrash = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        detail?.didCrash() == true
                    } else {
                        true
                    }
                    appendEditorConsole(if (didCrash) {
                        "ERROR: WebView renderer crash — Preview dihentikan dengan aman"
                    } else {
                        "WARNING: WebView renderer dihentikan sistem — Preview dihentikan dengan aman"
                    })
                    runOnToolUiThread {
                        val parent = view?.parent as? ViewGroup
                        parent?.removeView(view)
                        view?.destroy()
                        content.addView(TextView(this@previewHtmlText).apply {
                            text = if (didCrash) {
                                "Preview dihentikan karena renderer WebView bermasalah.\n\nCoba buka Preview lagi. Jika hanya project tertentu yang crash, periksa JavaScript/GPU pada halaman tersebut."
                            } else {
                                "Preview dihentikan oleh sistem. Coba buka Preview lagi."
                            }
                            setTextColor(textMain)
                            textSize = 14f
                            setPadding(dp(20), dp(24), dp(20), dp(24))
                        }, LinearLayout.LayoutParams(-1, -2))
                    }
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    appendEditorConsole("Page finished")
                }
            }
            val body = if (mode == "HTML") html else "<pre>${html.htmlEsc()}</pre>"
            try {
                loadDataWithBaseURL(baseUrl, body, "text/html", "UTF-8", null)
            } catch (t: Throwable) {
                appendEditorConsole("ERROR: Preview gagal dimuat: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
                content.addView(TextView(this@previewHtmlText).apply {
                    text = "Preview gagal dimuat.\n\n${t.message ?: t.javaClass.simpleName}"
                    setTextColor(textMain)
                    textSize = 14f
                    setPadding(dp(20), dp(24), dp(20), dp(24))
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        // Full-bleed WebView — tidak ada tombol di bawah.
        content.addView(w, LinearLayout.LayoutParams(-1, -1))
        // Console tetap bisa dibuka lewat long-press judul / Bit di pojok (opsional).
        title.setOnLongClickListener {
            showEditorConsole()
            true
        }
    }
internal fun MainActivity.hostWebProject() = hostHomeWifiProject()

internal fun MainActivity.hostHomeWifiProject() {
        val path=prefs.getString("last_web_project","") ?: ""
        if(path.isBlank() || !File(path,"index.html").isFile || !prefs.getBoolean("last_web_build_ok",false)) { toast("Build harus SUCCESS sebelum hosting"); return }
        wifiHtmlHostingTool()
    }
internal fun MainActivity.openWebFolder() { val p=prefs.getString("last_web_project","") ?: ""; if(p.isBlank()){toast("Belum ada project");return}; clearPage("Project Files"); File(p).listFiles()?.forEach{content.addView(settingRowClickable(it.name, "${it.length()} bytes", "File project", "file-outline"){ if(it.extension.equals("html",true)||it.extension.equals("htm",true)) previewHtmlText(it.readText(StandardCharsets.UTF_8),"HTML") else output(it.readText(StandardCharsets.UTF_8)) })} }
internal fun MainActivity.saveWebEditor(mode:String,text:String){ editorPendingTarget?.setText(text); val ext=when(mode){"HTML"->"html";"CSS"->"css";"JavaScript"->"js";else->"txt"}; val f=File(filesDir,"web_editor");f.mkdirs();File(f,"untitled.$ext").writeText(text);toast("Disimpan: untitled.$ext") }
internal fun MainActivity.findInEditor(e:EditText){ val q=EditText(this); q.hint="Cari"; AlertDialog.Builder(this).setTitle("Cari").setView(q).setPositiveButton("Cari"){_,_->val i=e.text.toString().indexOf(q.text.toString()); if(i>=0){e.requestFocus();e.setSelection(i,i+q.text.length)}else toast("Tidak ditemukan")}.setNegativeButton("Batal",null).show() }
internal fun MainActivity.applySimpleEmmet(e: EditText) {
        // Delegate ke Emmet lanjutan (cursor-aware).
        editorBox = e
        applyEditorEmmet()
    }
internal fun MainActivity.openTextFileIntoEditor(e:EditText){ val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="text/*";addCategory(Intent.CATEGORY_OPENABLE)}; startActivityForResult(i,9811); editorPendingTarget=e }
internal fun String.htmlEsc()=replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")

internal fun MainActivity.workspaceRoot(): File = File(filesDir, "workspaces").apply { mkdirs() }

internal fun MainActivity.workspaceCard(dir: File): View {
        val files = dir.listFiles()?.filter { it.name != "workspace.json" } ?: emptyList()
        val modified = dir.lastModified()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(12), dp(13), dp(10)); background = bg(panel2, 18, line)
            isClickable = true; isFocusable = true; contentDescription = "Workspace ${dir.name}"
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(MdiIconView(this).apply { setIconName("folder-outline"); setIconSize(25f); setTextColor(textMain); background = bg(panel, 13, line); setPadding(dp(9), dp(9), dp(9), dp(9)) }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(10) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(dir.name, 15f, true))
        texts.addView(subLabel("${files.size} item  •  ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(modified))}", 10f))
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(TextView(this).apply { text = "›"; textSize = 27f; setTextColor(textMuted); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(34), dp(44)) })
        card.addView(top)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        fun small(text: String, action: () -> Unit) = TextView(this).apply { this.text = text; textSize = 11f; gravity = Gravity.CENTER; setTextColor(textMain); background = bg(panel, 10, line); isClickable = true; isFocusable = true; setPadding(dp(10), 0, dp(10), 0); setOnClickListener { action() } }
        actions.addView(small("Buka") { prefs.edit().putString("last_workspace", dir.absolutePath).apply(); workspaceDetailTool(dir) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(5) })
        actions.addView(small("Editor") { dir.listFiles()?.firstOrNull { it.isFile && it.name != "workspace.json" }?.let { editor(it) } ?: toast("Belum ada file") }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(5) })
        card.addView(actions)
        card.setOnClickListener { prefs.edit().putString("last_workspace", dir.absolutePath).apply(); workspaceDetailTool(dir) }
        return card.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } }
    }
internal fun MainActivity.workspaceDetailTool(dir: File) {
        clearPage("Workspace: ${dir.name}")
        val files = dir.listFiles()?.filter { it.name != "workspace.json" }?.sortedBy { it.name.lowercase(Locale.getDefault()) } ?: emptyList()
        content.addView(compactButtonRow(
            "+ File" to {
                val n = edit("Nama file", false)
                AlertDialog.Builder(this).setTitle("File baru").setView(n).setNegativeButton("Batal", null).setPositiveButton("Buat") { _, _ ->
                    val name = n.text.toString().trim()
                    if (name.isBlank()) return@setPositiveButton
                    runCatching { File(dir, name).writeText("", StandardCharsets.UTF_8); workspaceDetailTool(dir) }.onFailure { toast("Gagal: ${it.message}") }
                }.show()
            },
            "File Manager" to { fileManager(dir) }
        ))
        content.addView(toolSection("PROJECT FILES", "Ketuk file untuk membuka editor."))
        if (files.isEmpty()) content.addView(subLabel("Belum ada file. Buat file pertama dari tombol + File.", 12f))
        files.forEach { f ->
            val row = fileManagerCard(f, dir)
            content.addView(row)
        }
        content.addView(button("←  Kembali ke Workspace") { workspaceCenterTool() })
    }
internal fun MainActivity.pluginCenterTool() {
        clearPage("Plugin Center")
        content.addView(label("Plugin Center", 22f, true))
        content.addView(subLabel("Plugin lokal berbasis manifest JSON. Plugin tidak dijalankan otomatis dan tidak diberi akses khusus.", 12f))
        val root = File(filesDir, "plugins").apply { mkdirs() }
        content.addView(button("Buat Template Plugin") {
            val f = File(root, "plugin_${System.currentTimeMillis()}.json")
            f.writeText(JSONObject().apply {
                put("id", f.nameWithoutExtension); put("name", "My Plugin"); put("version", "1.0");
                put("description", "Local tool manifest"); put("enabled", false); put("entry", "")
            }.toString(2), StandardCharsets.UTF_8)
            toast("Template plugin dibuat"); pluginCenterTool()
        })
        val files = root.listFiles()?.filter { it.extension.equals("json", true) } ?: emptyList()
        if (files.isEmpty()) content.addView(subLabel("Belum ada manifest plugin.", 13f))
        files.forEach { f ->
            val j = runCatching { JSONObject(f.readText(StandardCharsets.UTF_8)) }.getOrNull()
            val name = j?.optString("name", f.nameWithoutExtension) ?: f.nameWithoutExtension
            val ver = j?.optString("version", "?") ?: "?"
            content.addView(settingRowClickable(name, "v$ver", f.absolutePath, "tools") { output(f.readText(StandardCharsets.UTF_8)) })
        }
    }
internal fun MainActivity.toolCustomizationTool() {
        clearPage("Tool Customization")
        content.addView(label("Tool Customization", 22f, true))
        content.addView(subLabel("Tool yang dipin tampil di bagian \"Pinned / Favorit\" Beranda. Pengaturan disimpan lokal.", 12f))
        val pinned = prefs.getStringSet("pinned_tools", emptySet()) ?: emptySet()
        content.addView(button("Reset Kustomisasi") { prefs.edit().remove("pinned_tools").remove("hidden_tools").apply(); toolCustomizationTool() })
        homeTools.forEach { (id, name) ->
            val isPinned = pinned.contains(id)
            val row = settingRowClickable(name, if (isPinned) "Pinned" else "Tidak dipin", "ID: $id", "tools") {
                val now = prefs.getStringSet("pinned_tools", emptySet())?.toMutableSet() ?: mutableSetOf()
                if (now.contains(id)) now.remove(id) else now.add(id)
                prefs.edit().putStringSet("pinned_tools", now).apply(); toolCustomizationTool()
            }
            content.addView(row)
        }
    }
internal fun MainActivity.studioCenterTool() {
        clearPage("Studio Center")
        content.addView(label("Studio Center", 22f, true))
        content.addView(subLabel("Workspace terpadu untuk File, Network, Developer, System, Finance, Utility, dan Web.", 12f))
        val studios = listOf(
            "File Studio" to "filestudio", "Network Studio" to "networkstudio", "Developer Studio" to "developerstudio",
            "System Studio" to "systemstudio", "Finance Studio" to "financestudio", "Utility Studio" to "utilitystudio",
            "Web Project Builder" to "webproject", "Workspace Center" to "workspace"
        )
        studios.forEach { (n,id) -> content.addView(settingRowClickable(n, "Buka workspace", "Studio terpadu", iconFor(id)) { openTool(id) }) }
    }
internal fun MainActivity.studioHub(titleText:String, subtitleText:String, tools:List<Pair<String,String>>){ clearPage(titleText); content.addView(subLabel(subtitleText,13f)); tools.forEach{(n,id)->content.addView(settingRowClickable(n,"Buka tool", "", iconFor(id)){openTool(id)})} }
internal fun MainActivity.networkStudioTool(){ studioHub("Network Studio","Semua alat jaringan dalam satu workspace.",listOf("Ping" to "ping","Port Checker" to "port","DNS Lookup" to "dns","Reverse DNS" to "rdns","Whois" to "whois","Traceroute" to "traceroute","HTTP Headers" to "httpheaders","SSL Certificate" to "ssl","Network Scanner" to "netscanner","Subnet Calculator" to "subnetcalc")) }
internal fun MainActivity.developerStudioTool(){ studioHub("Developer Studio","Editor dan formatter untuk developer.",listOf("Web Project Builder" to "webproject","JSON Formatter" to "jsonformat","XML Formatter" to "xmlformat","Regex Tester" to "regex","Timestamp Converter" to "timestamp","Base64" to "base64","JWT Decoder" to "jwt","UUID Generator" to "uuid","Hash Generator" to "hash")) }
internal fun MainActivity.systemStudioTool(){ studioHub("System Studio","Informasi perangkat dan sistem.",listOf("Device & System" to "devicecenter","Storage Analyzer" to "storage","Network Info" to "network","App Manager" to "apps")) }
internal fun MainActivity.financeStudioTool(){ studioHub("Finance Studio","Kalkulator dan dashboard keuangan.",listOf("Kalkulator Lengkap" to "number","Finance Dashboard" to "financedashboard","Pengelola Keuangan" to "financereader","Pivot Point" to "pivotcalc","Averaging Down & DCA" to "dcacalc","Voltage Divider" to "dividercalc","PWM" to "pwmcalc","Konsumsi Listrik" to "powercalc")) }
internal fun MainActivity.utilityStudioTool(){ studioHub("Utility Studio","Utilitas sehari-hari.",listOf("Calculator" to "number","Clipboard Manager" to "clipboard","Unit Converter" to "unitconverter","QR Scanner" to "qr","OCR" to "ocr","Password Generator" to "password","Notes / Notifikasi" to "reminder")) }
// editingStudioTool() dipindah ke feature/media/EditingStudioHub.kt (launcher tanpa scroll).
internal fun MainActivity.systemInfo() {
        clearPage("Sistem")
        modernToolListRow("Sistem", "Informasi dan pengaturan sistem", "cog-outline") { showSystemDetailMenu() }
        modernToolListRow("System Center", "Kontrol layanan sistem", "view-dashboard-outline") { systemCenterTool() }
        modernToolListRow("Device Info", "Informasi perangkat lengkap", "cellphone-information") { deviceInfoTool() }
        modernToolListRow("Battery Info", "Status dan statistik baterai", "battery-high") { batteryInfoTool() }
        modernToolListRow("Wi-Fi Info", "Jaringan dan koneksi Wi-Fi", "wifi") { wifiInfo() }
    }
internal fun MainActivity.showSystemDetailMenu() {
        // clearPage() already owns the global toolbar/back navigation.
        // Do not add another page title with a second back button here;
        // that created a duplicated/nested header on the System detail screen.
        clearPage("Sistem")
        val headerSpacer = Space(this)
        content.addView(headerSpacer, LinearLayout.LayoutParams(1, dp(10)))
        val b = batteryStatusIntent()
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (scale > 0 && level >= 0) level * 100 / scale else -1
        modernMetricGrid(
            listOf(
                "Android" to "${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}",
                "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "CPU cores" to Runtime.getRuntime().availableProcessors().toString(),
                "Battery" to if (pct >= 0) "$pct%" else "Tidak tersedia"
            )
        )
        modernActionRow("Buka Pengaturan Aplikasi", "cog-outline") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
internal fun MainActivity.httpServer() {
        clearPage("HTTP Server")
        addToolHeader("HTTP Server", "Server HTTP lokal untuk file/project web.", "WEB")
        val root = File(prefs.getString("last_web_project", "") ?: "")
        val port = edit("Port", false).apply { setText("8080") }
        content.addView(port)
        val path = label(if (root.isDirectory) "Root: ${root.absolutePath}" else "Root belum dipilih", 12f)
        content.addView(path)
        val status = label("", 14f, true)
        if (server != null && !(server?.isClosed ?: true)) setToolState(status, Ds.State.SUCCESS, "Berjalan") else setToolState(status, Ds.State.INFO, "Berhenti")
        content.addView(status)
        content.addView(button("Gunakan Project Web terakhir") {
            val p = File(prefs.getString("last_web_project", "") ?: "")
            if (!p.isDirectory || !File(p, "index.html").isFile) {
                toast("Build Web Project dulu")
                return@button
            }
            path.text = "Root: ${p.absolutePath}"
        })
        content.addView(button("Start Static Server") {
            val p = File(prefs.getString("last_web_project", "") ?: "")
            val prt = port.text.toString().toIntOrNull()
            if (!p.isDirectory || !File(p, "index.html").isFile) {
                toast("Build Web Project dulu")
                return@button
            }
            if (prt == null || prt !in 1024..65535) {
                toast("Port harus 1024-65535")
                return@button
            }
            startStaticWebServer(p, prt, status, null, null)
        })
        content.addView(button("Stop Server") {
            stopStaticWebServer()
            setToolState(status, Ds.State.INFO, "Berhenti")
        })
        content.addView(subLabel("Hanya bisa diakses dari HP ini: http://127.0.0.1:<port>. Untuk dibuka dari perangkat lain di Wi-Fi, pakai Web Hosting Wi-Fi (dilindungi token). Melayani index.html, CSS, JS, gambar, font, JSON, SVG, dan file project lain. Path traversal di luar folder project ditolak.", 11f))
    }
internal fun MainActivity.startStaticWebServer(
    root: File,
    port: Int,
    status: TextView? = null,
    bindAddress: String? = null,
    authToken: String? = null
): Boolean {
        if (server != null && !(server?.isClosed ?: true)) {
            toast("Server sudah berjalan")
            return false
        }
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: run {
            toast("Folder project tidak valid")
            return false
        }
        val bind = bindAddress?.takeIf { it.isNotBlank() }?.let {
            runCatching { InetAddress.getByName(it) }.getOrNull()
        }
        if (bindAddress != null && bind == null) {
            toast("Interface Wi-Fi tidak valid")
            return false
        }
        // OPEN-03: tanpa alamat eksplisit (HTTP Server biasa) hanya dengarkan 127.0.0.1 —
        // server tanpa autentikasi tidak boleh terbuka ke seluruh jaringan Wi-Fi.
        // Hosting Wi-Fi memberi alamat interface + token akses secara eksplisit.
        val effectiveBind = bind ?: InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val socket = runCatching {
            ServerSocket(port, 24, effectiveBind)
        }.getOrElse {
            toast("Port $port gagal dibuka: ${it.message}")
            return false
        }
        server = socket
        status?.let { setToolState(it, Ds.State.SUCCESS, "Berjalan di ${effectiveBind.hostAddress}:$port") }
        thread(name = "mytools-http-$port") {
            try {
                while (!socket.isClosed) {
                    val client = socket.accept()
                    thread(name = "mytools-http-client") { serveStaticClient(client, canonicalRoot, authToken) }
                }
            } catch (_: SocketException) {
                // Normal when Stop closes the ServerSocket.
            } catch (t: Throwable) {
                runOnToolUiThread { status?.let { setToolState(it, Ds.State.ERROR, "Error: ${t.message}") } }
            } finally {
                runOnToolUiThread {
                    if (server === socket) {
                        server = null
                        if (status != null) setToolState(status, Ds.State.INFO, "Berhenti")
                    }
                }
                runCatching { socket.close() }
            }
        }
        return true
    }
/**
 * Baca satu baris HTTP (diakhiri LF, CR dibuang) sebagai ISO-8859-1, maksimal [maxBytes].
 * @return null bila stream habis sebelum ada data, atau baris melebihi batas.
 */
internal fun readBoundedHttpLine(input: java.io.InputStream, maxBytes: Int = 8192): String? {
    val buf = java.io.ByteArrayOutputStream(128)
    while (true) {
        val b = input.read()
        if (b < 0) return if (buf.size() == 0) null else String(buf.toByteArray(), StandardCharsets.ISO_8859_1)
        if (b == '\n'.code) break
        if (b == '\r'.code) continue
        if (buf.size() >= maxBytes) return null
        buf.write(b)
    }
    return String(buf.toByteArray(), StandardCharsets.ISO_8859_1)
}
internal fun MainActivity.serveStaticClient(socket: Socket, root: File, authToken: String? = null) {
        if (webHostClientCount.incrementAndGet() > 8) {
            webHostClientCount.decrementAndGet()
            runCatching { socket.close() }
            return
        }
        socket.soTimeout = 8000
        try {
            // OPEN-03: baris request/header dibaca dengan batas panjang (8 KB per baris,
            // maks 100 header) agar klien tidak bisa menghabiskan memori dengan baris tanpa akhir.
            val input = java.io.BufferedInputStream(socket.getInputStream())
            val requestLine = readBoundedHttpLine(input) ?: run {
                runCatching { writeHttpResponse(socket, 431, "text/plain; charset=utf-8", "Request line too long") }
                return
            }
            val requestHeaders = LinkedHashMap<String, String>()
            var headerCount = 0
            while (headerCount++ < 100) {
                val h = readBoundedHttpLine(input) ?: break
                if (h.isEmpty()) break
                val colon = h.indexOf(':')
                if (colon > 0) requestHeaders[h.substring(0, colon).trim().lowercase(Locale.US)] = h.substring(colon + 1).trim()
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeHttpResponse(socket, 400, "text/plain; charset=utf-8", "Bad Request")
                return
            }
            val method = parts[0].uppercase(Locale.US)
            if (method != "GET" && method != "HEAD") {
                writeHttpResponse(socket, 405, "text/plain; charset=utf-8", "Method Not Allowed", method == "HEAD")
                return
            }
            val rawTarget = parts[1]
            val query = rawTarget.substringAfter('?', "")
            val rawPath = runCatching { URLDecoder.decode(rawTarget.substringBefore('?'), "UTF-8") }.getOrElse { "/" }
            if (authToken != null) {
                val queryToken = query.split('&').asSequence()
                    .mapNotNull { part -> part.substringBefore('=', "").takeIf { it == "gitls_token" }?.let { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") } }
                    .firstOrNull()
                val cookieToken = requestHeaders["cookie"]?.split(';')?.asSequence()
                    ?.map { it.trim() }
                    ?.firstOrNull { it.startsWith("GITLS_TOKEN=") }
                    ?.substringAfter('=')
                val supplied = queryToken ?: cookieToken
                if (supplied != authToken) {
                    writeHttpResponse(socket, 401, "text/plain; charset=utf-8", "Unauthorized", method == "HEAD")
                    return
                }
            }
            if (rawPath.length > 2048) {
                writeHttpResponse(socket, 414, "text/plain; charset=utf-8", "URI Too Long", method == "HEAD")
                return
            }
            val relative = rawPath.removePrefix("/").ifBlank { "index.html" }
            val requested = File(root, relative).canonicalFile
            if (requested != root && !requested.path.startsWith(root.path + File.separator)) {
                writeHttpResponse(socket, 403, "text/plain; charset=utf-8", "Forbidden", method == "HEAD")
                return
            }
            val file = if (requested.isDirectory) File(requested, "index.html") else requested
            val relativeParts = file.relativeTo(root).path.split(File.separatorChar)
            val sensitiveName = file.name.equals("local.properties", true) ||
                file.extension.lowercase(Locale.US) in setOf("keystore", "jks", "pem", "key", "p12", "pfx") ||
                relativeParts.any { it.startsWith(".") } ||
                file.name.startsWith(".env", true)
            if (sensitiveName) {
                writeHttpResponse(socket, 403, "text/plain; charset=utf-8", "Forbidden", method == "HEAD")
                return
            }
            if (!file.isFile) {
                writeHttpResponse(socket, 404, "text/plain; charset=utf-8", "Not Found", method == "HEAD")
                return
            }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.US))
                ?: when (file.extension.lowercase(Locale.US)) {
                    "html", "htm" -> "text/html"
                    "css" -> "text/css"
                    "js", "mjs" -> "text/javascript"
                    "json" -> "application/json"
                    "svg" -> "image/svg+xml"
                    "wasm" -> "application/wasm"
                    else -> "application/octet-stream"
                }
            val setCookie = if (authToken != null && query.contains("gitls_token="))
                "Set-Cookie: GITLS_TOKEN=$authToken; Path=/; HttpOnly; SameSite=Strict\r\n" else ""
            writeHttpFileResponse(socket, "$mime; charset=utf-8", file, method == "HEAD", setCookie)
        } catch (_: Throwable) {
            runCatching { writeHttpResponse(socket, 500, "text/plain; charset=utf-8", "Server Error") }
        } finally {
            runCatching { socket.close() }
            webHostClientCount.decrementAndGet()
        }
    }
internal fun MainActivity.writeHttpResponse(socket: Socket, code: Int, contentType: String, body: String, headOnly: Boolean = false) =
        writeHttpResponse(socket, code, contentType, body.toByteArray(StandardCharsets.UTF_8), headOnly)

internal fun MainActivity.writeHttpResponse(socket: Socket, code: Int, contentType: String, body: ByteArray, headOnly: Boolean = false) {
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 414 -> "URI Too Long"; else -> "Internal Server Error"
        }
        val out = socket.getOutputStream()
        val header = "HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        if (!headOnly) out.write(body)
        out.flush()
    }
internal fun MainActivity.writeHttpFileResponse(socket: Socket, contentType: String, file: File, headOnly: Boolean = false, extraHeaders: String = "") {
        val out = socket.getOutputStream()
        val header = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${file.length()}\r\nCache-Control: no-cache\r\n$extraHeaders" + "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        if (!headOnly) FileInputStream(file).use { it.copyTo(out, 16 * 1024) }
        out.flush()
    }
internal fun MainActivity.stopStaticWebServer() {
        val old = server
        server = null
        runCatching { old?.close() }
    }
internal fun MainActivity.wifiHtmlHostingTool() {
        clearPage("HTML Hosting Wi-Fi")
        // Tampilan diselaraskan dengan Web Project Builder: kartu project + aksi Build / Host Wi-Fi.
        content.addView(subLabel(
            "Gabungkan HTML + CSS + JavaScript menjadi satu project web siap preview dan hosting di Wi-Fi rumah.",
            13f
        ).apply { setPadding(dp(2), dp(2), dp(2), dp(10)) })

        val lastPath = prefs.getString("last_web_project", "") ?: ""
        val lastDir = File(lastPath)
        val hasProject = lastDir.isDirectory && File(lastDir, "index.html").isFile
        val lastName = if (hasProject) lastDir.name else ""

        // Editor tersembunyi; draft activity-level agar tetap ada setelah refresh UI / file pick.
        if (hostingDraftName.isBlank() && lastName.isNotBlank()) hostingDraftName = lastName
        if (hostingDraftHtml.isBlank() && hasProject) {
            runCatching { hostingDraftHtml = File(lastDir, "index.html").readText(StandardCharsets.UTF_8) }
            runCatching {
                val style = File(lastDir, "style.css")
                if (style.isFile) hostingDraftCss = style.readText(StandardCharsets.UTF_8)
            }
            runCatching {
                val script = File(lastDir, "script.js")
                if (script.isFile) hostingDraftJs = script.readText(StandardCharsets.UTF_8)
            }
        }
        val nameField = edit("Nama Project", false).apply {
            visibility = View.GONE
            setText(hostingDraftName.ifBlank { lastName.ifBlank { "my-website" } })
        }
        val htmlField = edit("HTML", true).apply {
            visibility = View.GONE
            setText(hostingDraftHtml)
        }
        val cssField = edit("CSS", true).apply {
            visibility = View.GONE
            setText(hostingDraftCss)
        }
        val jsField = edit("JavaScript", true).apply {
            visibility = View.GONE
            setText(hostingDraftJs)
        }
        content.addView(nameField)
        content.addView(htmlField)
        content.addView(cssField)
        content.addView(jsField)

        fun persistDrafts() {
            hostingDraftName = nameField.text?.toString().orEmpty()
            hostingDraftHtml = htmlField.text?.toString().orEmpty()
            hostingDraftCss = cssField.text?.toString().orEmpty()
            hostingDraftJs = jsField.text?.toString().orEmpty()
        }

        fun fileSubtitle(field: EditText, emptyHint: String): String {
            val t = field.text?.toString()?.trim().orEmpty()
            return if (t.isBlank()) emptyHint else "${t.length} karakter siap"
        }

        val projectCard = settingRowClickable(
            "Nama Project",
            hostingDraftName.ifBlank { if (hasProject) lastName else "Belum dibuat" },
            "folder-edit-outline"
        ) {
            val input = EditText(this).apply {
                setText(nameField.text)
                hint = "my-website"
                setSingleLine(true)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            AlertDialog.Builder(this)
                .setTitle("Nama Project")
                .setView(input)
                .setPositiveButton("Simpan") { _, _ ->
                    val n = input.text?.toString()?.trim().orEmpty().ifBlank { "my-website" }
                    nameField.setText(n)
                    hostingDraftName = n
                    wifiHtmlHostingTool()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        content.addView(projectCard)

        fun pickOrEdit(labelText: String, field: EditText, request: Int, mime: String, emptyHint: String) {
            val row = settingRowClickable(
                labelText,
                fileSubtitle(field, emptyHint),
                when (labelText) {
                    "HTML" -> "language-html5"
                    "CSS" -> "language-css3"
                    else -> "language-javascript"
                }
            ) {
                val options = arrayOf("Pilih file", "Ketik / tempel kode", "Kosongkan")
                AlertDialog.Builder(this)
                    .setTitle(labelText)
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> {
                                webImportTarget = field
                                startActivityForResult(
                                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                        type = mime
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                    },
                                    request
                                )
                            }
                            1 -> {
                                val input = EditText(this).apply {
                                    setText(field.text)
                                    minLines = 8
                                    gravity = Gravity.TOP or Gravity.START
                                    setPadding(dp(14), dp(12), dp(14), dp(12))
                                    typeface = android.graphics.Typeface.MONOSPACE
                                    textSize = 13f
                                }
                                val scroll = android.widget.ScrollView(this).apply {
                                    addView(input)
                                    layoutParams = LinearLayout.LayoutParams(-1, dp(280))
                                }
                                AlertDialog.Builder(this)
                                    .setTitle("Edit $labelText")
                                    .setView(scroll)
                                    .setPositiveButton("Simpan") { _, _ ->
                                        field.setText(input.text)
                                        persistDrafts()
                                        wifiHtmlHostingTool()
                                    }
                                    .setNegativeButton("Batal", null)
                                    .show()
                            }
                            2 -> {
                                field.setText("")
                                persistDrafts()
                                wifiHtmlHostingTool()
                            }
                        }
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
            content.addView(row)
        }

        pickOrEdit("HTML", htmlField, WEB_HTML_PICK_REQUEST, "text/html", "Pilih file .html")
        pickOrEdit("CSS", cssField, WEB_CSS_PICK_REQUEST, "text/css", "Pilih file .css")
        pickOrEdit("JavaScript", jsField, WEB_JS_PICK_REQUEST, "text/javascript", "Pilih file .js")

        // ---- Aksi: Build + Host Wi-Fi ----
        content.addView(TextView(this).apply {
            text = "Aksi"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textMain)
            setPadding(dp(4), dp(14), dp(4), dp(8))
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val buildBtn = button("Build") {
            showWifiHostingBuildDialog(nameField, htmlField, cssField, jsField)
        }
        styleAsPrimary(buildBtn)
        val hostBtn = button("Host Wi-Fi") {
            val p = File(prefs.getString("last_web_project", "") ?: "")
            if (!p.isDirectory || !File(p, "index.html").isFile || !prefs.getBoolean("last_web_build_ok", false)) {
                toast("Build website sampai SUCCESS dulu")
                return@button
            }
            val savedPort = prefs.getInt("last_hosting_port", 8080)
            pendingHostingPort = savedPort
            pendingHostingRoot = p
            startHomeWifiHosting()
            // Refresh status di layar ini
            wifiHtmlHostingTool()
        }
        styleAsSecondary(hostBtn)
        actions.addView(buildBtn, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) })
        actions.addView(hostBtn, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(6) })
        content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        content.addView(
            settingRowClickable(
                "Buka Folder Project",
                if (hasProject) lastDir.absolutePath else "Lihat hasil project",
                "folder-outline"
            ) { openWebFolder() }
        )
        content.addView(
            settingRowClickable(
                "Preview / Hosting",
                if (prefs.getBoolean("last_web_build_ok", false)) "Siap preview & host" else "Setelah build, jalankan Hosting Wi-Fi",
                "web"
            ) {
                if (prefs.getBoolean("last_web_build_ok", false)) previewWebProject()
                else toast("Build dulu sampai SUCCESS")
            }
        )

        // ---- Status hosting ----
        val running = server != null && !(server?.isClosed ?: true)
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = bg(if (isDarkTheme) panel2 else Color.rgb(246, 248, 250), 16, line)
        }
        val status = label(
            if (running) "HOSTING AKTIF" else "STOPPED",
            15f,
            true
        )
        hostingStatusView = status
        statusCard.addView(status)
        val network = label("Jaringan: ${currentWifiSsid()}", 12f)
        statusCard.addView(network)
        val credentials = label(
            "SSID: ${currentWifiSsid()}\nPassword Wi-Fi: ${"tidak disimpan (masukkan saat Build bila diperlukan)"}",
            12f
        )
        hostingCredentialsView = credentials
        statusCard.addView(credentials)
        val url = label(
            if (running) (hostingUrlView?.text?.toString() ?: "URL: -") else "URL: -",
            12f,
            true
        )
        hostingUrlView = url
        statusCard.addView(url)
        content.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })

        val qr = ImageView(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
            }
            clipToOutline = true
            visibility = if (running) View.VISIBLE else View.GONE
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(200)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }
        hostingQrView = qr
        content.addView(qr)
        if (running) {
            val link = url.text?.toString()?.substringAfter("URL: ")?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            if (link.isNotBlank() && link != "-") generateHostingQr(link)
        }

        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val stopBtn = button("Stop Hosting") { stopWifiHtmlHosting(); wifiHtmlHostingTool() }
        styleAsSecondary(stopBtn)
        val copyBtn = button("Copy URL") {
            val text = hostingUrlView?.text?.toString()
                ?.substringAfter("URL: ")
                ?.lineSequence()
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isBlank() || text == "-") toast("Hosting belum aktif") else copyText(text)
        }
        styleAsSecondary(copyBtn)
        controlRow.addView(stopBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        controlRow.addView(copyBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        content.addView(controlRow)

        content.addView(subLabel(
            "Semua perangkat harus terhubung ke Wi-Fi rumah yang sama. Password Wi-Fi rumah tetap dikelola router/Android dan tidak dipakai server GITLS. Hanya file project hasil Build yang dilayani.",
            11f
        ).apply { setPadding(dp(2), dp(10), dp(2), dp(4)) })
    }

    /** Dialog Build: isi SSID, password Wi-Fi (opsional), port, lalu build project. */
    internal fun MainActivity.showWifiHostingBuildDialog(
        nameField: EditText,
        htmlField: EditText,
        cssField: EditText,
        jsField: EditText
    ) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        fun field(hintText: String, initial: String, password: Boolean = false): EditText {
            val e = EditText(this).apply {
                hint = hintText
                setText(initial)
                setSingleLine(true)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                if (password) inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            form.addView(e, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            return e
        }
        form.addView(TextView(this).apply {
            text = "Isi data jaringan sebelum build & host. Password hanya untuk catatan/berbagi — tidak disimpan permanen ke server."
            textSize = 12f
            setTextColor(textMuted)
            setPadding(0, 0, 0, dp(10))
        })
        val ssidField = field("SSID Wi-Fi rumah", currentWifiSsid().takeIf { it != "SSID tidak tersedia" } ?: "")
        val passField = field("Password Wi-Fi (opsional)", "", password = true)
        val portField = field("Port (1024–65535)", prefs.getInt("last_hosting_port", 8080).toString())
        val projectNameField = field("Nama Project", nameField.text?.toString()?.ifBlank { "my-website" } ?: "my-website")

        AlertDialog.Builder(this)
            .setTitle("Build & Siapkan Hosting")
            .setView(form)
            .setPositiveButton("Build") { _, _ ->
                val ssid = ssidField.text?.toString()?.trim().orEmpty()
                val pass = passField.text?.toString().orEmpty()
                val port = portField.text?.toString()?.toIntOrNull()
                val projName = projectNameField.text?.toString()?.trim().orEmpty().ifBlank { "my-website" }
                if (ssid.isBlank()) {
                    toast("SSID wajib diisi")
                    return@setPositiveButton
                }
                if (port == null || port !in 1024..65535) {
                    toast("Port harus 1024–65535")
                    return@setPositiveButton
                }
                nameField.setText(projName)
                hostingDraftName = projName
                hostingDraftHtml = htmlField.text?.toString().orEmpty()
                hostingDraftCss = cssField.text?.toString().orEmpty()
                hostingDraftJs = jsField.text?.toString().orEmpty()
                prefs.edit()
                    .putString("last_hosting_ssid", ssid)
                    .remove("last_hosting_wifi_password")
                    .putInt("last_hosting_port", port)
                    .apply()
                pendingHostingPort = port
                buildWebProject(
                    projName,
                    hostingDraftHtml,
                    hostingDraftCss,
                    hostingDraftJs
                ) { success, path ->
                    if (success && path != null) {
                        pendingHostingRoot = path
                        toast("Build SUCCESS • SSID: $ssid • Port: $port")
                    }
                    wifiHtmlHostingTool()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }
