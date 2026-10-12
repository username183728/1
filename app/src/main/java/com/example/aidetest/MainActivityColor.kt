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


internal fun MainActivity.colorTool() {
        clearPage("Color Tools")
        content.setPadding(dp(12), dp(8), dp(12), dp(16))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(8))
        }
        header.addView(subLabel("Pilih warna, ekstrak palet dari foto, atau ambil warna langsung dari layar.", 12f))
        content.addView(header)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = bg(if (isDarkTheme) panel2 else Color.rgb(244, 246, 248), 18, if (isDarkTheme) line else Color.rgb(226, 230, 234))
        }
        val tabPhoto = colorTab("Foto", true)
        val tabPicker = colorTab("Pipet Layar", false)
        val tabConvert = colorTab("Converter", false)
        tabs.addView(tabPhoto, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(3) })
        tabs.addView(tabPicker, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(3) })
        tabs.addView(tabConvert, LinearLayout.LayoutParams(0, dp(44), 1f))
        content.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val workspace = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(workspace, LinearLayout.LayoutParams(-1, -2))

        fun selectTab(selected: Int) {
            listOf(tabPhoto, tabPicker, tabConvert).forEachIndexed { i, v ->
                val active = i == selected
                v.setTextColor(if (active) Color.WHITE else textMain)
                v.background = bg(if (active) Color.rgb(15, 15, 16) else Color.TRANSPARENT, 14)
            }
            workspace.removeAllViews()
            when (selected) {
                0 -> buildPhotoColorWorkspace(workspace)
                1 -> buildScreenPickerWorkspace(workspace)
                else -> buildColorConverterWorkspace(workspace)
            }
        }
        tabPhoto.setOnClickListener { selectTab(0) }
        tabPicker.setOnClickListener { selectTab(1) }
        tabConvert.setOnClickListener { selectTab(2) }
        selectTab(0)
    }
internal fun MainActivity.colorTab(text: String, active: Boolean) = TextView(this).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(if (active) Color.WHITE else textMain)
        background = bg(if (active) Color.rgb(15, 15, 16) else Color.TRANSPARENT, 14)
        isClickable = true
    }

internal fun MainActivity.buildPhotoColorWorkspace(workspace: LinearLayout) {
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(colorActionButton("Galeri") { openColorPhotoGallery() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) })
        actionRow.addView(colorActionButton("Kamera") { openColorPhotoCamera() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) })
        workspace.addView(actionRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val photoFrame = FrameLayout(this).apply {
            background = bg(if (isDarkTheme) panel2 else Color.rgb(244, 246, 248), 22, if (isDarkTheme) line else Color.rgb(224, 229, 233))
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(if (isDarkTheme) panel2 else Color.rgb(239, 242, 245))
            contentDescription = "Foto untuk ekstraksi warna"
        }
        colorPhotoView = image
        photoFrame.addView(image, FrameLayout.LayoutParams(-1, dp(250)))

        val placeholder = FrameLayout(this).apply {
            background = ColorDrawable(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { openColorPhotoGallery() }
        }
        val plusButton = TextView(this).apply {
            text = "+"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(textMain)
            background = bg(Color.WHITE, 99, Color.rgb(205, 209, 214))
            elevation = dp(3).toFloat()
            contentDescription = "Pilih foto dari galeri"
            setOnClickListener { openColorPhotoGallery() }
        }
        placeholder.addView(plusButton, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
        colorPhotoPlaceholder = placeholder
        photoFrame.addView(placeholder, FrameLayout.LayoutParams(-1, dp(250)))

        val marker = View(this).apply {
            background = bg(colorPhotoSelected, 99, Color.WHITE)
            visibility = View.GONE
            elevation = dp(4).toFloat()
        }
        colorPhotoMarker = marker
        photoFrame.addView(marker, FrameLayout.LayoutParams(dp(28), dp(28)))
        workspace.addView(photoFrame, LinearLayout.LayoutParams(-1, dp(250)).apply { bottomMargin = dp(10) })

        val status = subLabel("Ketuk atau geser lingkaran pada foto untuk mengambil warna piksel.", 11f)
        colorPhotoStatus = status
        workspace.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val paletteMode = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val mainMode = colorActionButton("Utama 8") { extractPhotoPalette(colorPhotoBitmap, 8) }
        val extendedMode = colorActionButton("Detail 32") { extractPhotoPalette(colorPhotoBitmap, 32) }
        paletteMode.addView(mainMode, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
        paletteMode.addView(extendedMode, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4) })
        workspace.addView(paletteMode, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val paletteCard = colorSectionCard("Palet Warna", "Ekstraksi berbasis clustering warna: Utama 8 warna paling dominan, Detail sampai 32 warna yang lebih beragam.")

        // Header Palet Warna: tombol ">" membuka layer khusus yang menampilkan seluruh palet.
        val paletteTitle = paletteCard.getChildAt(0)
        paletteCard.removeViewAt(0)
        val paletteHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        paletteHeader.addView(paletteTitle, LinearLayout.LayoutParams(0, -2, 1f))
        paletteHeader.addView(TextView(this).apply {
            text = ">"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(textMain)
            background = bg(Color.TRANSPARENT, 99)
            isClickable = true
            isFocusable = true
            contentDescription = "Buka semua palet warna"
            setPadding(dp(10), 0, dp(4), 0)
            setOnClickListener { showFullPaletteLayer() }
        }, LinearLayout.LayoutParams(dp(44), dp(42)))
        paletteCard.addView(paletteHeader, 0)

        val palette = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        colorPhotoPalette = palette
        paletteCard.addView(palette, LinearLayout.LayoutParams(-1, -2))
        val exportRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        exportRow.addView(colorActionButton("Ekspor JSON") { requestColorPaletteExport("json") }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
        exportRow.addView(colorActionButton("Ekspor TXT") { requestColorPaletteExport("txt") }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4) })
        paletteCard.addView(exportRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        workspace.addView(paletteCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val selectedCard = colorSectionCard("Warna yang Dipilih", "HEX, RGB, HSL, HSV + kode Android/Flutter + pengecekan kontras.")
        val selectedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val selectedSwatch = View(this).apply { background = bg(colorPhotoSelected, 18) }
        selectedRow.addView(selectedSwatch, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(12) })
        val values = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hex = label("#19191B", 20f, true); colorPhotoHex = hex
        val rgb = subLabel("RGB 25, 25, 27", 12f); colorPhotoRgb = rgb
        val hsl = subLabel("HSL —", 12f); colorPhotoHsl = hsl
        val hsv = subLabel("HSV —", 12f)
        values.addView(hex); values.addView(rgb); values.addView(hsl); values.addView(hsv)
        selectedRow.addView(values, LinearLayout.LayoutParams(0, -2, 1f))
        selectedCard.addView(selectedRow)

        val contrast = subLabel("Kontras: pilih warna untuk melihat kecocokan teks hitam/putih.", 11f)
        selectedCard.addView(contrast, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val codeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val flutter = colorCodeChip("Flutter", "Color(0xFF19191B)")
        val android = colorCodeChip("Android", "0xFF19191B")
        codeRow.addView(flutter, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        codeRow.addView(android, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        selectedCard.addView(codeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val copyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val copyHex = colorActionButton("Salin HEX") { colorPhotoHex?.text?.toString()?.let { copyText(it) } }
        val copyAll = colorActionButton("Salin Semua") {
            val c = colorPhotoSelected; copyText(colorDetailsText(c))
        }
        val fav = colorActionButton("Simpan") { saveColorHistory(colorPhotoSelected); toast("Warna disimpan") }
        copyRow.addView(copyHex, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(3) })
        copyRow.addView(copyAll, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        copyRow.addView(fav, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(3) })
        selectedCard.addView(copyRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        workspace.addView(selectedCard)

        fun updateSelected(color: Int, x: Float? = null, y: Float? = null) {
            colorPhotoSelected = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
            selectedSwatch.background = bg(colorPhotoSelected, 18)
            val r = Color.red(colorPhotoSelected); val g = Color.green(colorPhotoSelected); val b = Color.blue(colorPhotoSelected)
            val hslValue = rgbToHsl(r, g, b)
            val hsvValue = FloatArray(3); Color.colorToHSV(colorPhotoSelected, hsvValue)
            val hx = "#%02X%02X%02X".format(Locale.US, r, g, b)
            hex.text = hx
            rgb.text = "RGB $r, $g, $b"
            hsl.text = "HSL ${fmt(hslValue[0])}°, ${fmt(hslValue[1])}%, ${fmt(hslValue[2])}%"
            hsv.text = "HSV ${fmt(hsvValue[0].toDouble())}°, ${fmt((hsvValue[1]*100).toDouble())}%, ${fmt((hsvValue[2]*100).toDouble())}%"
            flutter.text = "Flutter\nColor(0xFF${hx.removePrefix("#")})"
            android.text = "Android\n0xFF${hx.removePrefix("#")}"
            contrast.text = contrastSummary(colorPhotoSelected)
            marker.background = bg(colorPhotoSelected, 99, Color.WHITE)
            if (x != null && y != null) {
                marker.visibility = View.VISIBLE
                marker.x = x - dp(14); marker.y = y - dp(14)
                status.text = "Dipilih • $hx • pipet manual"
            }
        }

        image.setOnTouchListener { v, event ->
            val bitmap = colorPhotoBitmap ?: return@setOnTouchListener false
            if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE && event.action != MotionEvent.ACTION_UP) return@setOnTouchListener true
            val bw = bitmap.width.toFloat(); val bh = bitmap.height.toFloat()
            val vw = v.width.toFloat(); val vh = v.height.toFloat()
            if (vw <= 0f || vh <= 0f) return@setOnTouchListener true
            val scale = min(vw / bw, vh / bh)
            val drawW = bw * scale; val drawH = bh * scale
            val left = (vw - drawW) / 2f; val top = (vh - drawH) / 2f
            val px = ((event.x - left) / scale).toInt().coerceIn(0, bitmap.width - 1)
            val py = ((event.y - top) / scale).toInt().coerceIn(0, bitmap.height - 1)
            updateSelected(sampleBitmap(bitmap, px, py), event.x, event.y)
            true
        }
        image.tag = placeholder
        colorPhotoSelectionUpdater = { c -> updateSelected(c) }
    }
internal fun MainActivity.requestColorPaletteExport(format: String) {
        if (currentPhotoPalette.isEmpty()) { toast("Belum ada palet untuk diekspor"); return }
        pendingColorPaletteExportFormat = format
        val mime = if (format == "json") "application/json" else "text/plain"
        val ext = if (format == "json") "json" else "txt"
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = mime
            putExtra(Intent.EXTRA_TITLE, "mytools_palette.$ext")
            addCategory(Intent.CATEGORY_OPENABLE)
        }, COLOR_PALETTE_EXPORT_REQUEST)
    }
internal fun MainActivity.colorDetailsText(color: Int): String {
        val r=Color.red(color); val g=Color.green(color); val b=Color.blue(color)
        val h=rgbToHsl(r,g,b); val hsv=FloatArray(3); Color.colorToHSV(color,hsv)
        return "HEX #%02X%02X%02X\nRGB $r, $g, $b\nHSL ${fmt(h[0])}°, ${fmt(h[1])}%, ${fmt(h[2])}%\nHSV ${fmt(hsv[0].toDouble())}°, ${fmt((hsv[1]*100).toDouble())}%, ${fmt((hsv[2]*100).toDouble())}%\n${contrastSummary(color)}".format(Locale.US, r,g,b)
    }
internal fun MainActivity.relativeLuminance(color: Int): Double {
        fun channel(v: Int): Double { val x=v/255.0; return if(x<=0.03928) x/12.92 else Math.pow((x+0.055)/1.055,2.4) }
        return 0.2126*channel(Color.red(color)) + 0.7152*channel(Color.green(color)) + 0.0722*channel(Color.blue(color))
    }
internal fun MainActivity.contrastRatio(a: Int, b: Int): Double {
        val l1=relativeLuminance(a); val l2=relativeLuminance(b)
        val hi=maxOf(l1,l2); val lo=minOf(l1,l2); return (hi+0.05)/(lo+0.05)
    }
internal fun MainActivity.contrastSummary(color: Int): String {
        val black=contrastRatio(color, Color.BLACK); val white=contrastRatio(color, Color.WHITE)
        val blackOk=black>=4.5; val whiteOk=white>=4.5
        val blackText=if(blackOk) "COCOK" else "kurang"
        val whiteText=if(whiteOk) "COCOK" else "kurang"
        return "Kontras teks: Hitam ${String.format(Locale.US,"%.2f",black)}:1 ($blackText) • Putih ${String.format(Locale.US,"%.2f",white)}:1 ($whiteText)"
    }
internal fun MainActivity.colorSectionCard(titleText: String, subtitleText: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = bg(if (isDarkTheme) panel else Color.WHITE, 20, if (isDarkTheme) line else Color.rgb(226, 230, 234))
        addView(label(titleText, 16f, true))
        addView(subLabel(subtitleText, 11f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(8) })
    }

internal fun MainActivity.colorActionButton(textValue: String, onClick: () -> Unit) = Button(this).apply {
        text = textValue
        textSize = 12f
        setTextColor(Color.WHITE)
        background = bg(Color.rgb(15, 15, 16), 14)
        setStateListAnimator(null)
        setOnClickListener { scalePress(this); onClick() }
    }

internal fun MainActivity.scalePress(view: View) = Motion.tap(view, 0.97f)

internal fun MainActivity.colorCodeChip(titleText: String, code: String) = TextView(this).apply {
        text = "$titleText\n$code"
        textSize = 10f
        setTextColor(textMain)
        setPadding(dp(11), dp(8), dp(11), dp(8))
        background = bg(Color.rgb(245, 247, 249), 14, Color.rgb(230, 234, 238))
    }

internal fun MainActivity.buildScreenPickerWorkspace(workspace: LinearLayout) {
        val preview = FrameLayout(this).apply { background = bg(Color.rgb(244, 246, 248), 22, Color.rgb(224,229,233)) }
        val swatch = View(this).apply { background = bg(Color.rgb(120, 120, 124), 22) }
        val marker = TextView(this).apply { text = "•"; gravity = Gravity.CENTER; textSize = 28f; setTextColor(Color.WHITE); background = bg(Color.rgb(120, 120, 124), 30, Color.WHITE) }
        preview.addView(swatch, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER))
        preview.addView(marker, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END).apply { topMargin=dp(16); rightMargin=dp(16) })
        workspace.addView(preview, LinearLayout.LayoutParams(-1, dp(190)).apply { bottomMargin=dp(10) })
        val status = label("Mode aman: tanpa tangkapan layar", 15f, true).apply { gravity=Gravity.CENTER }
        workspace.addView(status, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin=dp(7) })
        workspace.addView(colorActionButton("INFO PIPET NONAKTIF") { activateColorPicker() }, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin=dp(10) })
        val hex = label("HEX  —  Belum ada warna", 16f, true)
        val rgb = subLabel("RGB  —  -", 13f)
        val hsl = subLabel("HSL  —  -", 13f)
        workspace.addView(hex); workspace.addView(rgb); workspace.addView(hsl)
        workspace.addView(colorActionButton("SALIN HEX") {
            val value=hex.text.toString().substringAfter("HEX  —  ").trim(); if(value.startsWith("#")) copyText(value) else toast("Belum ada warna")
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin=dp(8) })
        colorPickerUiUpdater = { color ->
            keepShapeFill(swatch, color); marker.background=bg(color,30,Color.WHITE)
            val r=Color.red(color); val g=Color.green(color); val b=Color.blue(color); val h=rgbToHsl(r,g,b); val hx="#%02X%02X%02X".format(Locale.US,r,g,b)
            hex.text="HEX  —  $hx"; rgb.text="RGB  —  $r, $g, $b"; hsl.text="HSL  —  ${fmt(h[0])}°, ${fmt(h[1])}%, ${fmt(h[2])}%"; status.text="Pipet aktif  •  $hx"
        }
    }
internal fun MainActivity.buildColorConverterWorkspace(workspace: LinearLayout) {
        val wheel = ColorWheelView(this)
        workspace.addView(wheel, LinearLayout.LayoutParams(-1, dp(220)).apply { bottomMargin=dp(10) })
        val preview = View(this).apply { background = bg(Color.rgb(23,32,42), 22) }
        wheel.onColorChanged = { keepShapeFill(preview, it) }
        workspace.addView(preview, LinearLayout.LayoutParams(-1, dp(72)).apply { bottomMargin=dp(10) })
        val input = edit("#RRGGBB"); workspace.addView(input)
        workspace.addView(colorActionButton("HEX → RGB / HSL") {
            val raw=input.text.toString().trim()
            runCatching {
                val h=raw.removePrefix("#"); require(h.length==6 || h.length==8); val off=if(h.length==8)2 else 0
                val c=Color.rgb(h.substring(off,off+2).toInt(16),h.substring(off+2,off+4).toInt(16),h.substring(off+4,off+6).toInt(16))
                keepShapeFill(preview, c); val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c); val hsl=rgbToHsl(r,g,b)
                output("HEX = #${h.uppercase(Locale.US)}\nRGB = $r, $g, $b\nHSL = ${fmt(hsl[0])}°, ${fmt(hsl[1])}%, ${fmt(hsl[2])}%\nFlutter = Color(0xFF${h.takeLast(6).uppercase(Locale.US)})\nAndroid = 0xFF${h.takeLast(6).uppercase(Locale.US)}")
            }.onFailure { output("HEX tidak valid") }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin=dp(8) })
        val rgb=edit("RGB: 255,255,255"); workspace.addView(rgb)
        workspace.addView(colorActionButton("RGB → HEX") {
            runCatching { val p=rgb.text.toString().split(",").map{it.trim().toInt()}; require(p.size==3 && p.all{it in 0..255}); output("#%02X%02X%02X".format(Locale.US,p[0],p[1],p[2])) }.onFailure { output("Format: 255,255,255") }
        }, LinearLayout.LayoutParams(-1, dp(50)))
    }
internal fun MainActivity.openColorPhotoGallery() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        startActivityForResult(intent, COLOR_PHOTO_PICK_REQUEST)
    }
internal fun MainActivity.openColorPhotoCamera() {
        // CAMERA dideklarasikan di manifest → ACTION_IMAGE_CAPTURE melempar SecurityException bila belum diizinkan.
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), COLOR_CAMERA_PERMISSION_REQUEST)
            return
        }
        val intent=Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if(intent.resolveActivity(packageManager)==null){ toast("Kamera tidak tersedia"); return }
        runCatching { startActivityForResult(intent, COLOR_PHOTO_CAMERA_REQUEST) }.onFailure { toast("Tidak bisa membuka kamera: ${it.message}") }
    }
internal fun MainActivity.loadColorPhoto(bitmap: Bitmap) {
        val max=1600
        val scaled=if(bitmap.width>max || bitmap.height>max){ val s=min(max.toFloat()/bitmap.width,max.toFloat()/bitmap.height); Bitmap.createScaledBitmap(bitmap,(bitmap.width*s).toInt(),(bitmap.height*s).toInt(),true) } else bitmap
        colorPhotoBitmap=scaled
        colorPhotoView?.setImageBitmap(scaled)
        colorPhotoPlaceholder?.visibility = View.GONE
        colorPhotoMarker?.visibility=View.GONE
        colorPhotoStatus?.text="Foto siap • 8 warna utama + detail 32 warna + pipet manual"
        extractPhotoPalette(scaled, 8)
    }
internal fun MainActivity.extractPhotoPalette(bitmap: Bitmap?, maxColors: Int) {
        val out = colorPhotoPalette ?: return
        if (bitmap == null) { toast("Pilih foto dulu"); return }
        out.removeAllViews()

        // Gunakan sampel terukur + K-Means RGB. Algoritma lama hanya memakai histogram
        // kuantisasi sehingga warna kecil tetapi jelas (mis. hijau) mudah tersisih.
        val workW = min(180, bitmap.width)
        val workH = maxOf(1, (bitmap.height.toFloat() * workW / bitmap.width).toInt())
        val thumb = Bitmap.createScaledBitmap(bitmap, workW, workH, true)
        val totalPixels = thumb.width * thumb.height
        val targetSamples = 5000
        val step = maxOf(1, kotlin.math.ceil(kotlin.math.sqrt(totalPixels / targetSamples.toDouble())).toInt())
        val samples = ArrayList<Int>(min(targetSamples, totalPixels))
        for (y in 0 until thumb.height step step) {
            for (x in 0 until thumb.width step step) {
                val c = thumb.getPixel(x, y)
                val a = Color.alpha(c)
                // Transparansi dibaurkan ke putih agar hasil JPG-like tidak menjadi hitam.
                val r = if (a == 255) Color.red(c) else (Color.red(c) * a + 255 * (255 - a)) / 255
                val g = if (a == 255) Color.green(c) else (Color.green(c) * a + 255 * (255 - a)) / 255
                val b = if (a == 255) Color.blue(c) else (Color.blue(c) * a + 255 * (255 - a)) / 255
                samples.add(Color.rgb(r, g, b))
            }
        }
        if (samples.isEmpty()) { thumb.recycle(); toast("Foto tidak memiliki piksel yang bisa dianalisis"); return }

        val k = min(maxColors.coerceAtLeast(1), samples.size)
        val centroids = ArrayList<FloatArray>(k)
        val used = HashSet<Int>()

        // Seed pertama = warna paling sering pada kuantisasi kasar.
        val coarse = HashMap<Int, Int>()
        samples.forEach { c ->
            val r = (Color.red(c) / 16) * 16 + 8
            val g = (Color.green(c) / 16) * 16 + 8
            val b = (Color.blue(c) / 16) * 16 + 8
            val q = Color.rgb(r.coerceAtMost(255), g.coerceAtMost(255), b.coerceAtMost(255))
            coarse[q] = (coarse[q] ?: 0) + 1
        }
        val first = coarse.maxByOrNull { it.value }?.key ?: samples[0]
        centroids.add(floatArrayOf(Color.red(first).toFloat(), Color.green(first).toFloat(), Color.blue(first).toFloat()))
        used.add(first)

        // Paksa satu seed dari warna paling jenuh agar warna aksen yang nyata
        // (misalnya hijau pada foto) tidak kalah oleh area abu-abu yang lebih luas.
        var accent = samples[0]
        var accentScore = -1f
        samples.forEach { c ->
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            if (hsv[1] > accentScore) { accentScore = hsv[1]; accent = c }
        }
        if (!used.contains(accent) && centroids.size < k) {
            centroids.add(floatArrayOf(Color.red(accent).toFloat(), Color.green(accent).toFloat(), Color.blue(accent).toFloat()))
            used.add(accent)
        }

        // Seed berikutnya memilih warna yang paling jauh dari centroid yang sudah ada.
        while (centroids.size < k) {
            var bestColor = samples[centroids.size % samples.size]
            var bestScore = -1.0
            for (c in samples) {
                if (used.contains(c)) continue
                var nearest = Double.MAX_VALUE
                for (m in centroids) {
                    val dr = Color.red(c).toDouble() - m[0].toDouble()
                    val dg = Color.green(c).toDouble() - m[1].toDouble()
                    val db = Color.blue(c).toDouble() - m[2].toDouble()
                    val d = dr * dr + dg * dg + db * db
                    if (d < nearest) nearest = d
                }
                if (nearest > bestScore) { bestScore = nearest; bestColor = c }
            }
            centroids.add(floatArrayOf(Color.red(bestColor).toFloat(), Color.green(bestColor).toFloat(), Color.blue(bestColor).toFloat()))
            used.add(bestColor)
        }

        val assignments = IntArray(samples.size)
        repeat(8) {
            val sumR = DoubleArray(k)
            val sumG = DoubleArray(k)
            val sumB = DoubleArray(k)
            val counts = IntArray(k)
            for (i in samples.indices) {
                val c = samples[i]
                var best = 0
                var bestDist = Double.MAX_VALUE
                for (j in 0 until k) {
                    val m = centroids[j]
                    val dr = Color.red(c).toDouble() - m[0].toDouble()
                    val dg = Color.green(c).toDouble() - m[1].toDouble()
                    val db = Color.blue(c).toDouble() - m[2].toDouble()
                    val d = dr * dr + dg * dg + db * db
                    if (d < bestDist) { bestDist = d; best = j }
                }
                assignments[i] = best
                sumR[best] += Color.red(c).toDouble()
                sumG[best] += Color.green(c).toDouble()
                sumB[best] += Color.blue(c).toDouble()
                counts[best]++
            }
            for (j in 0 until k) {
                if (counts[j] > 0) {
                    centroids[j][0] = (sumR[j] / counts[j]).toFloat()
                    centroids[j][1] = (sumG[j] / counts[j]).toFloat()
                    centroids[j][2] = (sumB[j] / counts[j]).toFloat()
                }
            }
        }

        val clusterCounts = IntArray(k)
        for (a in assignments) clusterCounts[a]++
        val chosen = ArrayList<Pair<Int, Int>>()
        val minDistance = if (maxColors <= 8) 22 else 10
        val ranked = (0 until k).sortedByDescending { clusterCounts[it] }
        for (idx in ranked) {
            if (clusterCounts[idx] <= 0) continue
            val c = Color.rgb(
                centroids[idx][0].roundToInt().coerceIn(0, 255),
                centroids[idx][1].roundToInt().coerceIn(0, 255),
                centroids[idx][2].roundToInt().coerceIn(0, 255)
            )
            if (chosen.all { colorDistance(it.first, c) >= minDistance }) chosen.add(c to clusterCounts[idx])
            if (chosen.size >= maxColors) break
        }

        val total = samples.size.coerceAtLeast(1)
        currentPhotoPalette.clear()
        chosen.forEach { (color, count) ->
            currentPhotoPalette.add(color to ((count * 100.0 / total).roundToInt().coerceAtLeast(1)))
        }

        // Palet dibuat satu baris horizontal agar semua warna dapat digeser kanan/kiri
        // dan tidak ada swatch yang terpotong di sisi layar. Berlaku untuk Utama 8 maupun Detail 32.
        val cellW = if (maxColors <= 8) dp(72) else dp(58)
        val swatch = if (maxColors <= 8) dp(46) else dp(36)
        val horizontal = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = true
            clipChildren = true
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        chosen.forEachIndexed { index, e ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2), dp(3), dp(2), dp(3))
                isClickable = true
                isFocusable = true
                contentDescription = "Warna ${index + 1}, #%02X%02X%02X".format(Locale.US, Color.red(e.first), Color.green(e.first), Color.blue(e.first))
                setOnClickListener { colorPhotoSelectionUpdater?.invoke(e.first) }
            }
            box.addView(View(this).apply { background = bg(e.first, 10) }, LinearLayout.LayoutParams(swatch, swatch))
            box.addView(TextView(this).apply {
                text = "#%02X%02X%02X".format(Locale.US, Color.red(e.first), Color.green(e.first), Color.blue(e.first))
                textSize = if (maxColors <= 8) 8f else 7f
                setTextColor(textMain); gravity = Gravity.CENTER; maxLines = 1
            })
            box.addView(TextView(this).apply {
                text = "${(e.second * 100.0 / total).roundToInt().coerceAtLeast(1)}%"
                textSize = 7f; setTextColor(textMuted); gravity = Gravity.CENTER
            })
            row.addView(box, LinearLayout.LayoutParams(cellW, -2).apply {
                if (index > 0) leftMargin = dp(3)
            })
        }
        horizontal.isFillViewport = false
        horizontal.setOnTouchListener { _, event ->
            // Pastikan gesture horizontal tidak diambil ScrollView vertikal induk.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> horizontal.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> horizontal.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        horizontal.addView(row, LinearLayout.LayoutParams(-2, -2))
        horizontal.post { horizontal.scrollTo(0, 0) }
        out.addView(horizontal, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(2)
            rightMargin = dp(2)
        })

        val swipeHint = subLabel("Geser kanan/kiri untuk melihat semua warna • ketuk warna untuk memilih", 10f)
        out.addView(swipeHint, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(3)
            bottomMargin = dp(2)
        })
        thumb.recycle()
        colorPhotoStatus?.text = "${chosen.size} warna terdeteksi • clustering detail aktif • ketuk warna untuk memilih"
    }
internal fun MainActivity.showFullPaletteLayer() {
        if (currentPhotoPalette.isEmpty()) {
            toast("Belum ada palet warna. Pilih foto dan lakukan ekstraksi dulu.")
            return
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (isDarkTheme) panel else Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(textMain)
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(46), dp(50)))
        header.addView(label("Semua Palet Warna", 20f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(subLabel("${currentPhotoPalette.size} warna", 11f), LinearLayout.LayoutParams(-2, -2))
        root.addView(header)

        root.addView(subLabel("Ketuk salah satu warna untuk menjadikannya warna terpilih dan melihat kode HEX/RGB/HSL/HSV.", 11f), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        })

        val scrollGrid = ScrollView(this).apply {
            isFillViewport = true
        }
        val grid = GridLayout(this).apply {
            columnCount = 2
            useDefaultMargins = false
        }

        currentPhotoPalette.forEachIndexed { index, pair ->
            val color = pair.first
            val percent = pair.second
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = bg(Color.rgb(247, 248, 249), 16, Color.rgb(225, 229, 233))
                isClickable = true
                isFocusable = true
                contentDescription = "Pilih warna #%02X%02X%02X".format(Locale.US, Color.red(color), Color.green(color), Color.blue(color))
                setOnClickListener {
                    // Satu sumber pemilihan warna: update kartu "Warna yang Dipilih"
                    // di layer utama, lalu kembali ke halaman Color Tools.
                    colorPhotoSelectionUpdater?.invoke(color)
                    dialog.dismiss()
                    toast("Warna dipilih #%02X%02X%02X".format(Locale.US, Color.red(color), Color.green(color), Color.blue(color)))
                }
            }
            card.addView(View(this).apply { background = bg(color, 12) }, LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(10) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(label("#%02X%02X%02X".format(Locale.US, Color.red(color), Color.green(color), Color.blue(color)), 14f, true))
            info.addView(subLabel("${percent}% • warna ${index + 1}", 10f))
            card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(78)
                columnSpec = GridLayout.spec(index % 2, 1f)
                rowSpec = GridLayout.spec(index / 2)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            grid.addView(card, params)
        }
        scrollGrid.addView(grid, FrameLayout.LayoutParams(-1, -2))
        root.addView(scrollGrid, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(colorActionButton("TUTUP") { dialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(if (isDarkTheme) panel else Color.WHITE))
        dialog.window?.setLayout(-1, -1)
        dialog.show()
        dialog.window?.setLayout(-1, -1)
    }
internal fun MainActivity.colorDistance(a:Int,b:Int):Int {
        val dr=Color.red(a)-Color.red(b); val dg=Color.green(a)-Color.green(b); val db=Color.blue(a)-Color.blue(b)
        return kotlin.math.sqrt((dr*dr+dg*dg+db*db).toDouble()).toInt()
    }
internal fun MainActivity.sampleBitmap(bitmap: Bitmap, x: Int, y: Int): Int {
        var sr=0; var sg=0; var sb=0; var count=0
        for(dy in -1..1) for(dx in -1..1){ val px=(x+dx).coerceIn(0,bitmap.width-1); val py=(y+dy).coerceIn(0,bitmap.height-1); val c=bitmap.getPixel(px,py); sr+=Color.red(c); sg+=Color.green(c); sb+=Color.blue(c); count++ }
        return Color.rgb(sr/count,sg/count,sb/count)
    }
internal fun MainActivity.saveColorHistory(color: Int) {
        val hx="#%02X%02X%02X".format(Locale.US,Color.red(color),Color.green(color),Color.blue(color))
        val old=prefs.getString("color_history","")?.split(",")?.filter{it.isNotBlank()}?:emptyList()
        prefs.edit().putString("color_history",(listOf(hx)+old.filter{it!=hx}).take(24).joinToString(",")).apply()
    }
internal fun MainActivity.calculatorHub(selected: String = calculatorSelectedMode) {
        calculatorSelectedMode = calculatorModes.firstOrNull { it.id == selected }?.id ?: "basiccalc"
        clearPage("Kalkulator", false)
        // Full-bleed body seperti kalkulator sistem; padding minimal.
        content.setPadding(dp(10), dp(4), dp(10), dp(10))

        // Mode cepat ringkas (bukan selector tebal). Semua mode lain lewat menu ⋮ / picker.
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        listOf(
            "basiccalc" to "Dasar",
            "scicalc" to "Ilmiah",
            "percentcalc" to "%",
            "unitcalc" to "Konversi"
        ).forEach { (id, label) ->
            val selected = id == calculatorSelectedMode
            val b = Button(this).apply {
                this.text = label
                textSize = 12.5f
                isAllCaps = false
                setTextColor(if (selected) Color.WHITE else textMain)
                background = bg(
                    if (selected) (if (isDarkTheme) Color.rgb(245, 245, 247) else Color.rgb(22, 22, 24))
                    else panel2,
                    20
                )
                if (selected && isDarkTheme) setTextColor(Color.BLACK)
                setStateListAnimator(null)
                setOnClickListener { calculatorHub(id) }
                setOnLongClickListener {
                    showCalculatorModePicker()
                    true
                }
            }
            quick.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
        }
        content.addView(quick, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(6) })

        // Body mengisi sisa tinggi layar agar display + keypad terasa “penuh”.
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        embeddedCalculatorRender = true
        val previousContent = content
        try {
            content = body
            when (calculatorSelectedMode) {
                "basiccalc" -> calculatorTool(false)
                "scicalc" -> calculatorTool(true)
                "percentcalc" -> percentCalculator()
                "fractioncalc" -> fractionCalculator()
                "ratiocalc" -> ratioCalculator()
                "unitcalc" -> unitCalculator()
                "areacalc" -> areaCalculator()
                "volumecalc" -> volumeCalculator()
                "speedcalc" -> speedCalculator()
                "timecalc" -> timeCalculator()
                "datecalc" -> dateCalculator()
                "loancalc" -> loanCalculator()
                "fuelcalc" -> fuelCalculator()
                "pivotcalc" -> pivotPointCalculator()
                "dividercalc" -> voltageDividerCalculator()
                "dcacalc" -> dcaCalculator()
                "pwmcalc" -> pwmCalculator()
                "spritecalc" -> spriteSheetCalculator()
                "installcalc" -> installmentComparisonCalculator()
                    "powercalc" -> powerConsumptionCalculator()
                "aspectcalc" -> aspectRatioCalculator()
                "pphcalc" -> ppnPphCalculator()
                "riskcalc" -> riskRewardCalculator()
                "compoundcalc" -> compoundCalculator()
                "margincalc" -> marginTaxCalculator()
                "discountcalc" -> tieredDiscountCalculator()
                "datacalc" -> dataUnitCalculator()
                "pressurecalc" -> pressureCalculator()
                "worktimecalc" -> workTimeCalculator()
                "basecalc" -> baseCalculator()
                "equationcalc" -> equationCalculator()
            }
        } finally {
            content = previousContent
            embeddedCalculatorRender = false
        }
    }
internal fun MainActivity.showCalculatorModePicker() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val groups = calculatorModes.groupBy { it.group }
        groups.forEach { (group, modes) ->
            val heading = TextView(this).apply {
                text = group.uppercase(Locale.getDefault())
                textSize = 11f
                setTextColor(textMuted)
                setPadding(dp(10), dp(10), dp(10), dp(6))
            }
            box.addView(heading)
            modes.forEach { mode ->
                val row = TextView(this).apply {
                    text = if (mode.id == calculatorSelectedMode) "✓  ${mode.name}" else "     ${mode.name}"
                    textSize = 15f
                    setTextColor(textMain)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(12), 0)
                    background = bg(if (mode.id == calculatorSelectedMode) panel2 else panel, 12, line)
                }
                box.addView(row, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(4) })
            }
        }
        val dialog = AlertDialog.Builder(this).setTitle("Pilih kalkulator").setView(box).setNegativeButton("Tutup", null).create()
        // Rows above need the dialog reference; rebind listeners after creation.
        dialog.setOnShowListener {
            var index = 1
            groups.forEach { (_, modes) ->
                index += 1
                modes.forEach { mode ->
                    val row = box.getChildAt(index) as? TextView
                    row?.setOnClickListener { dialog.dismiss(); calculatorHub(mode.id) }
                    index += 1
                }
            }
        }
        dialog.show()
    }

/** RGB (0-255) -> HSL. Hasil: [hue 0-360, saturation 0-100, lightness 0-100]. */
internal fun MainActivity.rgbToHsl(r: Int, g: Int, b: Int): DoubleArray {
    val rf = r.coerceIn(0, 255) / 255.0
    val gf = g.coerceIn(0, 255) / 255.0
    val bf = b.coerceIn(0, 255) / 255.0
    val max = maxOf(rf, gf, bf)
    val min = minOf(rf, gf, bf)
    val l = (max + min) / 2.0
    val d = max - min
    if (d == 0.0) return doubleArrayOf(0.0, 0.0, l * 100.0)
    val s = d / (1.0 - kotlin.math.abs(2.0 * l - 1.0))
    val sector = when {
        max == rf -> {
            val x = (gf - bf) / d
            if (x < 0.0) x + 6.0 else x
        }
        max == gf -> (bf - rf) / d + 2.0
        else -> (rf - gf) / d + 4.0
    }
    return doubleArrayOf(sector * 60.0, s * 100.0, l * 100.0)
}

/**
 * Pipet layar berbasis tangkapan layar sudah dihapus (kebijakan privasi/platform).
 * Fungsi ini dipertahankan agar tombol di tab "Pipet Layar" tidak error.
 */
internal fun MainActivity.activateColorPicker() {
    toast("Pipet layar tidak tersedia di versi ini. Gunakan tab Foto atau Converter.")
}

/** Entri tool "uicolorcalc": membuka Color Tools (pipet layar nonaktif, tab lain tetap bisa dipakai). */
internal fun MainActivity.uiColorPickerTool() {
    colorTool()
}
