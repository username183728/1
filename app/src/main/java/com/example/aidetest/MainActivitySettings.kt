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


internal fun MainActivity.selectBottomNav(name: String) {
        findViewById<View>(R.id.bottomNav).background = null
        findViewById<View>(R.id.bottomNav).elevation = 0f
        val active = if (isDarkTheme) Color.WHITE else Color.rgb(15, 15, 16)
        val inactive = if (isDarkTheme) Color.rgb(155, 155, 160) else Color.rgb(138, 150, 163)
        val navItems = listOf(
            R.id.navHome to (name == "home"),
            R.id.navTools to (name == "all"),
            R.id.navFavorite to (name == "favorites"),
            R.id.navSettings to (name == "settings")
        )
        navItems.forEach { (id, selected) ->
            val item = findViewById<View>(id)
            // Bottom navigation dibuat "ghost" seperti tombol Menu/Search/Profile:
            // tidak ada kartu/shape di belakang item aktif. Status aktif hanya ditunjukkan
            // lewat warna dan sedikit perubahan ukuran agar tampilan tetap bersih.
            item.background = null
            item.animate().alpha(if (selected) 1f else 0.7f)
                .scaleX(if (selected) 1.06f else 1f).scaleY(if (selected) 1.06f else 1f)
                .setDuration(Ds.ANIM_NORMAL).setInterpolator(Motion.EASE_OUT).start()
        }
        val labels = listOf(
            R.id.navHomeLabel to (name == "home"),
            R.id.navToolsLabel to (name == "all"),
            R.id.navFavoriteLabel to (name == "favorites"),
            R.id.navSettingsLabel to (name == "settings")
        )
        labels.forEach { (id, selected) -> findViewById<TextView>(id).setTextColor(if (selected) active else inactive) }
        findViewById<TextView>(R.id.navBotLabel).setTextColor(inactive)
        applyBitFaceTheme()
        navBot.alpha = 1f
        val iconMap = listOf(
            R.id.navHomeIcon to (name == "home"),
            R.id.navToolsIcon to (name == "all"),
            R.id.navFavoriteIcon to (name == "favorites"),
            R.id.navSettingsIcon to (name == "settings")
        )
        iconMap.forEach { (id, selected) ->
            (findViewById<ImageView>(id).drawable)?.setTint(if (selected) active else inactive)
        }
    }
internal fun MainActivity.categoryCard(iconName: String, name: String, desc: String, ids: List<String>) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
            minimumHeight = dp(72)
            contentDescription = "$name. ${ids.size} tools"
        }
        // Category cards memakai Material Design Icons dari font asset aplikasi,
        // bukan emoji sistem, sehingga bentuknya konsisten di semua HP/MIUI.
        applyInteractiveSurface(card, 17, 1)
        addPressFeedback(card)
        card.setOnClickListener { showCategory(name, ids) }

        val iconBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = bg(if (isDarkTheme) Color.rgb(42,42,46) else Color.rgb(242,245,247), 14)
        }
        iconBox.addView(MdiIconView(this).apply {
            setIconName(iconName)
            setIconSize(24f)
            setTextColor(if (isDarkTheme) Color.WHITE else Color.rgb(70,80,90))
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        card.addView(iconBox, LinearLayout.LayoutParams(dp(46), dp(46)))

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13),0,dp(8),0) }
        texts.addView(label(name, 14f, true))
        texts.addView(subLabel(desc, 11f))
        card.addView(texts, LinearLayout.LayoutParams(0,-2,1f))
        card.addView(MdiIconView(this).apply {
            setIconName("chevron-right")
            setIconSize(24f)
            setTextColor(textMuted)
            contentDescription = "Buka kategori $name"
        }, LinearLayout.LayoutParams(dp(30), dp(46)))
        content.addView(card, LinearLayout.LayoutParams(-1, dp(72)).apply { bottomMargin = dp(8) })
    }
internal fun MainActivity.showCategory(name: String, ids: List<String>) {
        // Semua pintu masuk kategori SYSTEM langsung menuju halaman gabungan.
        if (name.equals("SYSTEM", ignoreCase = true)) {
            deviceSystemCenterTool()
            return
        }
        clearPage(name, true)
        ids.mapNotNull { id -> homeToolMap[id]?.let { id to it } }.forEach { (id, n) ->
            content.addView(toolCard(id, n, iconFor(id)).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(70)).apply { bottomMargin = dp(7) }
                alpha = 1f
                translationY = 0f
                visibility = View.VISIBLE
            })
        }
        // Animasi pembuka hanya untuk halaman Beranda. Daftar Semua Tools harus
        // langsung terlihat saat halaman dibuka kembali.
    }
internal fun MainActivity.showAllTools(forceRebuild: Boolean = false) {
        // Tab Tools ditekan lagi saat sudah di halaman Tools → jangan rebuild (scroll tetap).
        if (!forceRebuild && currentPage == "all" && content.childCount > 0) {
            selectBottomNav("all")
            return
        }
        clearPage("all", true)
        suppressSearch = true
        search.setText("")
        suppressSearch = false

        content.addView(label("Semua Tools", 22f, true))
        content.addView(subLabel("Pilih kategori untuk membuka tool. Tampilan ini dibuat ringan agar tetap lancar di HP.", 12f).apply {
            setPadding(0, 0, 0, dp(8))
        })
        content.addView(button("Command Palette — cari cepat semua tools") {
            showCommandPalette()
        })
        val allQuickFilters = listOf("Semua", "Favorit", "Text & Dev", "Security", "Network", "Files", "System", "Media", "QR / OCR", "Finance", "Utility", "Studio")
        val quickRow = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val quickInner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(10)) }
        allQuickFilters.forEach { filter ->
            val chip = homeChip(filter, filter == "Semua") {
                when (filter) {
                    "Semua" -> showAllTools(forceRebuild = true)
                    "Favorit" -> showFavorites()
                    else -> {
                        val title = when (filter) {
                            "Text & Dev" -> "TEXT & DEV"
                            "Security" -> "SECURITY"
                            "Network" -> "NETWORK"
                            "Files" -> "FILE & APP"
                            "System" -> "SYSTEM"
                            "Media" -> "MEDIA & COLOR"
                            "QR / OCR" -> "QR / OCR"
                            "Finance" -> "FINANCE"
                            "Utility" -> "UTILITY"
                            "Studio" -> "STUDIO"
                            else -> "FILE & APP"
                        }
                        val ids = when (filter) {
                            "Text & Dev" -> listOf("editor","json","jsonformat","xmlformat","yaml","toml","sql","regex","textstat","case","compare","base64","hex","url","unicode","timestamp","uuid","urlparser","mime","uuidbatch","base64file","textreplace","wordfreq","markdown","cron")
                            "Security" -> listOf("securitycenter","hash","checksum","password","passwordstrength","hmac","jwt","totp","aes","fileencryption","securenotes","pgp","sshkeygen","certviewer","urlsafety","virusscanner","breachchecker","totpvault","passwordanalyzer","steganography")
                            "Network" -> listOf("network","dns","dohdns","rdns","ping","traceroute","whois","port","netscanner","publicip","ipinfo","ipintelligence","ssl","http","httpheaders","restclient","websocket","wifi","networkcenter","webhostwifi")
                            "System" -> listOf("devicecenter","deviceinfo","storage","apps","battery","clipboard")
                            "Media" -> listOf("color","imagestudio","creativestudio","presentationstudio","videoeditor","audioeditor","imagecompress","imageconvert","batchconvert","videotoaudio","videoframes","steganography")
                            "QR / OCR" -> listOf("qr","ocr")
                            "Finance" -> listOf("financereader","financedashboard","dcacalc","compoundcalc","margincalc","discountcalc","pphcalc","riskcalc")
                            "Utility" -> listOf("reminder","stopwatch","timer","unitconverter","number","history")
                            "Studio" -> listOf("workspace","plugincenter","customtools","studiocenter","webproject","networkstudio","developerstudio","filestudio","imagestudio","editingstudio","creativestudio","systemstudio","financestudio","utilitystudio","colorstudio")
                            else -> listOf("filemanager","zip","githubzip","fileconvert","filesearch","dedupe","storage","apps","apk","apkanalyzer","apkcompare","duplicatefinder","largefilefinder")
                        }
                        showCategory(title, ids)
                    }
                }
            }
            quickInner.addView(chip, LinearLayout.LayoutParams(dp(92), dp(38)).apply { rightMargin = dp(7) })
        }
        quickRow.addView(quickInner)
        content.addView(quickRow, LinearLayout.LayoutParams(-1, dp(48)))
        // Ruang akhir ekstra membuat kartu terakhir tetap bisa dinaikkan melewati
        // floating bottom navigation tanpa terasa tertutup.

        val groups = linkedMapOf(
            "folder-outline" to ("FILE & APP" to listOf(
                "filemanager", "recentfiles", "backuprestore", "zip", "githubzip", "fileconvert", "filesearch", "storage", "apps", "apk", "apkcompare", "duplicatefinder", "largefilefinder", "filehashcompare", "dedupe"
            )),
            "cellphone-cog" to ("SYSTEM" to listOf("devicecenter", "deviceinfo", "battery", "storage", "apps", "clipboard")),
            "calculator-variant-outline" to ("CALCULATOR" to listOf(
                "number", "pivotcalc", "dividercalc", "dcacalc", "pwmcalc", "spritecalc", "installcalc", "powercalc", "aspectcalc", "pphcalc", "riskcalc", "compoundcalc", "margincalc", "discountcalc", "datacalc", "pressurecalc", "worktimecalc", "basecalc", "equationcalc"
            )),
            "file-document-edit-outline" to ("TEXT & DEV" to listOf(
                "editor", "json", "base64", "url", "regex", "uuid", "textstat", "case", "compare", "slug", "token", "random", "hex", "base32", "timestamp", "unicode", "urlparser", "mime", "jsonformat", "xmlformat", "uuidbatch", "base64file", "textreplace", "wordfreq", "markdown", "sql", "yaml", "toml", "cron", "helpbot"
            )),
            "shield-check-outline" to ("SECURITY" to listOf(
                "hash", "password", "passwordstrength", "jwt", "hmac", "totp", "aes", "checksum", "securitycenter", "fileencryption", "steganography", "passwordanalyzer", "breachchecker", "securenotes", "totpvault", "pgp", "sshkeygen", "certviewer", "virusscanner", "urlsafety"
            )),
            "web" to ("NETWORK" to listOf(
                "dns", "rdns", "port", "publicip", "ping", "ipinfo", "ssl", "http", "httpheaders", "restclient", "websocket", "network", "networkcenter", "wifi", "webhostwifi", "whois", "traceroute", "subnetcalc", "netscanner"
            )),
            "palette-outline" to ("MEDIA & COLOR" to listOf("color", "imagestudio")),
            "qrcode" to ("QR / OCR" to listOf("qr", "ocr")),
            "cash-multiple" to ("FINANCE" to listOf("financereader", "financedashboard", "dcacalc", "compoundcalc", "margincalc", "discountcalc", "pphcalc", "riskcalc")),
            "toolbox-outline" to ("UTILITY" to listOf("reminder", "stopwatch", "timer", "unitconverter")),
            "view-grid-outline" to ("STUDIO" to listOf("webproject", "webeditor", "networkstudio", "developerstudio", "filestudio", "imagestudio", "colorstudio", "systemstudio", "financestudio", "utilitystudio", "workspace", "plugincenter", "customtools", "studiocenter"))
        )

        val used = mutableSetOf<String>()
        groups.forEach { (icon, pair) ->
            val available = pair.second.distinct().filter { id -> homeToolMap.containsKey(id) && used.add(id) }
            if (available.isNotEmpty()) {
                categoryCard(icon, pair.first, "${available.size} tools • Ketuk untuk membuka", available)
            }
        }

        val remaining = homeTools.map { it.first }.filter { it !in used }.distinct()
        if (remaining.isNotEmpty()) categoryCard("toolbox-outline", "LAINNYA", "${remaining.size} tools", remaining)
    }
internal fun MainActivity.addGroupedToolSection(titleText: String, ids: List<String>) {
        sectionTitle(titleText)
        val grid = GridLayout(this).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        ids.mapNotNull { id -> homeToolMap[id]?.let { id to it } }.forEach { (id, name) ->
            val card = mainPyToolCard(id, name)
            grid.addView(card, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(116)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        content.addView(grid, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(3)
        })
    }
internal fun MainActivity.renderToolList(items: List<Pair<String,String>>) {
        content.removeViews(if (currentPage == "all") 2 else 0, maxOf(0, content.childCount - if (currentPage == "all") 2 else 0))
        items.forEach { (id,name) ->
            content.addView(toolCard(id,name,iconFor(id)).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(70)).apply { bottomMargin = dp(7) }
                alpha = 1f
                translationY = 0f
                visibility = View.VISIBLE
            })
        }
    }
internal fun MainActivity.filterCurrent(q: String) {
        if (currentPage != "home" && currentPage != "favorites" && currentPage != "all" && currentPage != "Kalkulator Lengkap") return
        val query = q.trim().lowercase(Locale.getDefault())
        if (currentPage == "home") {
            // Do not call clearPage() here. It saves a navigation snapshot, changes
            // page state and rebuilds surrounding views while the IME is typing.
            // Only the content workspace is replaced. The search EditText therefore
            // keeps focus, composing state and cursor position.
            if (query.isEmpty()) { showHome(homeFilter); return }

            content.removeAllViews()
            content.setPadding(dp(Ds.HOME_H), dp(Ds.HOME_V), dp(Ds.HOME_H), dp(Ds.HOME_BOTTOM))
            content.addView(label("Hasil pencarian", 22f, true))
            val keepIds = homeToolSearchIndex.asSequence()
                .filter { (_, lowerName) -> lowerName.contains(query) }
                .map { it.first }
                .toList()
            val keep = keepIds.mapNotNull { id -> homeToolMap[id]?.let { id to it } }
            renderToolListHomeSearch(keep)
        } else if (currentPage == "favorites") {
            val favorites = favoriteToolIds().mapNotNull { id -> homeToolMap[id]?.let { id to it } }
            if (query.isEmpty()) {
                showFavorites(force = true)
                return
            }
            content.removeAllViews()
            content.setPadding(dp(Ds.HOME_H), dp(Ds.HOME_V), dp(Ds.HOME_H), dp(Ds.HOME_BOTTOM))
            content.addView(label("Hasil favorit", 22f, true))
            content.addView(subLabel("Mencari di ${favorites.size} tool favorit: $q", 12f))
            val keep = favorites.filter { (_, name) -> name.lowercase(Locale.getDefault()).contains(query) }
            if (keep.isEmpty()) {
                content.addView(subLabel("Tidak ada favorit yang cocok.", 13f).apply { setPadding(dp(2), dp(16), dp(2), 0) })
            } else {
                keep.forEach { (id, name) ->
                    content.addView(toolCard(id, name, iconFor(id)).apply {
                        layoutParams = LinearLayout.LayoutParams(-1, dp(70)).apply { bottomMargin = dp(8) }
                    })
                }
            }
        } else if (currentPage == "Kalkulator Lengkap") {
            if (query.isEmpty()) { calculatorHub(); return }
            content.removeAllViews()
            content.addView(label("Hasil kalkulator",22f,true))
            val allCalc=listOf("Dasar" to "basiccalc","Ilmiah" to "scicalc","Persentase" to "percentcalc","Pecahan" to "fractioncalc","Rasio & Proporsi" to "ratiocalc","Risk-Reward & Position Sizing" to "riskcalc","Compound Interest & Target Tabungan" to "compoundcalc","Margin & PPN/Pajak" to "margincalc","Diskon Bertingkat" to "discountcalc","Konverter Satuan" to "unitcalc","Ukuran Data Digital" to "datacalc","Kecepatan" to "speedcalc","Tekanan" to "pressurecalc","Selisih Tanggal & Umur" to "datecalc","Jam Kerja" to "worktimecalc","Luas & Keliling" to "areacalc","Volume" to "volumecalc","Durasi" to "timecalc","Basis Angka" to "basecalc","Persamaan" to "equationcalc","Cicilan Pinjaman" to "loancalc","Konsumsi BBM" to "fuelcalc",
                "Pivot Point" to "pivotcalc","Voltage Divider" to "dividercalc","Averaging Down & DCA" to "dcacalc","PWM & Duty Cycle" to "pwmcalc",
                "Sprite Sheet Grid" to "spritecalc","Flat vs Efektif/Anuitas" to "installcalc",
                "Konsumsi Listrik & Biaya" to "powercalc","Aspect Ratio" to "aspectcalc","PPN & PPh Final" to "pphcalc","Riwayat Perhitungan" to "history")
            allCalc.filter{it.first.lowercase(Locale.getDefault()).contains(query)}.forEach{content.addView(toolCard(it.second,it.first,iconFor(it.second)).apply{layoutParams=LinearLayout.LayoutParams(-1,dp(70)).apply{bottomMargin=dp(7)}})}
        } else {
            if (query.isEmpty()) { showAllTools(); return }
            content.removeViews(2, maxOf(0, content.childCount - 2))
            val keep = homeToolSearchIndex.asSequence()
                .filter { (_, lowerName) -> lowerName.contains(query) }
                .mapNotNull { (id, _) -> homeToolMap[id]?.let { id to it } }
                .toList()
            renderToolList(keep)
        }
    }
internal fun MainActivity.renderToolListHomeSearch(items: List<Pair<String,String>>) {
        items.forEach { (id,name) -> content.addView(toolCard(id,name,iconFor(id)).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(70)).apply { bottomMargin = dp(7) } }) }
        if (items.isEmpty()) content.addView(subLabel("Tidak ada tool yang cocok.", 13f))
    }

internal fun MainActivity.showThemeChooserDialog() {
    val modes = listOf(
        "system" to "Ikuti Sistem",
        "light" to "Terang",
        "dark" to "Gelap"
    )
    var selected = prefs.getString("theme_mode", "system") ?: "system"

    // Jangan gunakan AppCompat Light Dialog di sini. Dialog bawaan sebelumnya
    // selalu putih walaupun aplikasi sedang Gelap. Dialog ini mengikuti
    // warna tema aplikasi secara langsung dan tetap nyaman disentuh.
    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

    val bgColor = if (isDarkTheme) Color.rgb(24, 24, 27) else Color.WHITE
    val surfaceColor = if (isDarkTheme) Color.rgb(32, 32, 36) else Color.rgb(247, 248, 250)
    val textColor = if (isDarkTheme) Color.rgb(245, 245, 247) else Color.rgb(25, 28, 32)
    val mutedColor = if (isDarkTheme) Color.rgb(158, 158, 166) else Color.rgb(105, 112, 122)
    val strokeColor = if (isDarkTheme) Color.rgb(54, 54, 60) else Color.rgb(224, 228, 233)
    val accent = if (isDarkTheme) Color.rgb(238, 238, 242) else Color.rgb(28, 28, 32)

    fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(20), dp(22), dp(14))
        background = rounded(bgColor, 26, strokeColor)
    }

    root.addView(TextView(this).apply {
        text = "Tema Aplikasi"
        textSize = 22f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(textColor)
    }, LinearLayout.LayoutParams(-1, -2))

    root.addView(TextView(this).apply {
        text = "Pilih tampilan yang nyaman untuk GITLS Editor Light"
        textSize = 13f
        setTextColor(mutedColor)
        setPadding(0, dp(5), 0, dp(14))
    }, LinearLayout.LayoutParams(-1, -2))

    val options = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    root.addView(options, LinearLayout.LayoutParams(-1, -2))

    fun radioDrawable(active: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dp(if (active) 2 else 1), if (active) accent else mutedColor)
    }

    fun addOption(mode: String, title: String, subtitle: String, icon: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
        }
        val active = selected == mode
        row.background = rounded(if (active) surfaceColor else Color.TRANSPARENT, 18, if (active) strokeColor else null)

        val iconBox = MdiIconView(this).apply {
            setIconName(icon)
            setIconSize(21f)
            setTextColor(if (active) accent else mutedColor)
            background = rounded(if (active) bgColor else surfaceColor, 14, if (active) strokeColor else null)
        }
        row.addView(iconBox, LinearLayout.LayoutParams(dp(44), dp(44)))

        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, dp(8), 0)
        }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(textColor)
        })
        labels.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))

        val radio = FrameLayout(this).apply {
            background = radioDrawable(active)
            if (active) {
                addView(View(this@showThemeChooserDialog).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(accent)
                    }
                }, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
            }
        }
        row.addView(radio, LinearLayout.LayoutParams(dp(28), dp(28)))

        row.setOnClickListener {
            val previous = selected
            selected = mode
            dialog.dismiss()
            if (mode == previous) return@setOnClickListener
            // commit() (bukan apply()) agar pilihan pasti tersimpan sebelum Activity dibuat ulang.
            prefs.edit().putString("theme_mode", mode).commit()
            // Muat ulang UI seperti baru membuka app, tanpa menghilangkan pekerjaan yang sedang dibuka.
            restartForThemeChange()
        }
        options.addView(row, LinearLayout.LayoutParams(-1, dp(72)).apply {
            bottomMargin = dp(7)
        })
    }

    addOption("system", "Ikuti Sistem", "Gunakan tema Android saat ini", "theme-light-dark")
    addOption("light", "Terang", "Tampilan terang dan bersih", "weather-sunny")
    addOption("dark", "Gelap", "Lebih nyaman di lingkungan gelap", "weather-night")

    val cancel = TextView(this).apply {
        text = "BATAL"
        textSize = 13f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(accent)
        isClickable = true
        isFocusable = true
        setPadding(dp(18), dp(14), dp(18), dp(10))
        setOnClickListener { dialog.dismiss() }
    }
    root.addView(cancel, LinearLayout.LayoutParams(-1, dp(48)).apply {
        topMargin = dp(4)
    })

    dialog.setContentView(root)
    dialog.setCanceledOnTouchOutside(true)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.window?.setDimAmount(if (isDarkTheme) 0.62f else 0.32f)
    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    dialog.show()
    dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.90f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
}

internal fun MainActivity.showSettings() {
        // Always rebuild Settings when a preference changes. This is important for
        // theme and animation settings: the subtitle/label and every themed view
        // must update immediately without leaving the page or restarting the app.
        clearPage("settings", false)
        content.setPadding(dp(Ds.HOME_H), dp(Ds.HOME_V), dp(Ds.HOME_H), dp(Ds.HOME_BOTTOM))

        settingsSection("Tampilan")
        val themeMode = prefs.getString("theme_mode", "system") ?: "system"
        val themeLabel = when (themeMode) {
            "dark" -> "Gelap"
            "light" -> "Terang"
            else -> "Ikuti Sistem"
        }
        content.addView(settingRowClickable("Tema", themeLabel, "Pilih tampilan terang, gelap, atau mengikuti sistem", "theme-light-dark") {
            showThemeChooserDialog()
        })
        content.addView(settingRowClickable(
            "Animasi UI",
            Motion.label(Motion.readMode(prefs)),
            Motion.description(Motion.readMode(prefs)),
            "animation-outline"
        ) {
            val next = Motion.nextMode(Motion.readMode(prefs))
            Motion.writeMode(prefs, next)
            Motion.refresh(prefs, contentResolver)
            toast("Animasi UI: " + Motion.label(next))
            showSettings()
        })
        content.addView(settingRowClickable("Kolom Beranda", prefs.getInt("home_columns", 2).toString() + " kolom", "Jumlah kolom tool di Beranda", "view-grid-outline") {
            val next = if (prefs.getInt("home_columns", 2) == 2) 3 else 2
            prefs.edit().putInt("home_columns", next).apply()
            toast("Kolom Beranda: $next kolom")
            showSettings()
        })

        settingsSection("Beranda")
        content.addView(settingRowClickable("Aktivitas Terakhir", if (prefs.getBoolean("show_recent_activity", false)) "Ditampilkan" else "Disembunyikan", "Tampilkan tool yang terakhir dibuka di Beranda", "history") {
            prefs.edit().putBoolean("show_recent_activity", !prefs.getBoolean("show_recent_activity", false)).apply(); showSettings()
        })
        content.addView(settingRowClickable("Akses Cepat", if (prefs.getBoolean("show_quick_access", true)) "Ditampilkan" else "Disembunyikan", "Tampilkan akses cepat di Beranda", "view-grid-plus-outline") {
            prefs.edit().putBoolean("show_quick_access", !prefs.getBoolean("show_quick_access", true)).apply(); showSettings()
        })
        content.addView(settingRowClickable("File Terbaru", "Buka daftar file terakhir", "clock-outline") { editor(null) })

        settingsSection("Riwayat")
        content.addView(settingRowClickable("Kelola Riwayat", "Aktivitas tersimpan lokal", "history") { historyTool() })
        content.addView(settingRowClickable("Hapus Riwayat", "Hapus aktivitas dan file terbaru", "delete-outline") {
            AlertDialog.Builder(this).setTitle("Hapus Riwayat").setMessage("Hapus riwayat aktivitas lokal?")
                .setNegativeButton("Batal", null).setPositiveButton("Hapus") { _, _ ->
                    prefs.edit().remove("history").apply(); toast("Riwayat dihapus")
                }.show()
        })

        settingsSection("Data & Penyimpanan")
        content.addView(settingRowClickable("Backup & Restore", "Backup lokal ZIP", "backup-restore", "Simpan pengaturan dan data aplikasi ke file ZIP yang bisa dipulihkan nanti.") { openTool("backuprestore") })
        content.addView(settingRowClickable("Penyimpanan Aplikasi", appDataStorageText(), "database", "Lihat ukuran data GITLS dan cache yang digunakan.") { showAppDataStorageDialog() })
        content.addView(settingRowClickable("Bersihkan Cache", formatBytes(cacheDirSize()), "broom", "Hapus cache sementara tanpa menghapus pengaturan atau data penting.") { confirmClearAppCache() })
        content.addView(settingRowClickable("Reset Pengaturan", "Hanya preferensi", "restore", "Kembalikan preferensi GITLS ke kondisi awal tanpa menghapus file kerja.") { confirmResetPreferences() })

        settingsSection("Privasi & Keamanan")
        content.addView(settingRowClickable("Privasi & Data", "Data tetap di perangkat", "shield-lock-outline", "Lihat ringkasan bagaimana GITLS menangani data dan izin.") { showPrivacyInfo() })
        content.addView(settingRowClickable("Izin Aplikasi", "Kelola di pengaturan sistem", "cellphone-cog", "Buka halaman izin sistem untuk GITLS.") {
            runCatching {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }.onFailure { toast("Tidak bisa membuka pengaturan izin") }
        })

        settingsSection("Aplikasi")
        val appVersion = BuildConfig.VERSION_NAME
        content.addView(settingRowClickable("Pembaruan Aplikasi", "Versi $appVersion", "update", "Periksa pembaruan APK dari sistem update GITLS.") {
            runCatching { AppUpdateManager(this).checkForUpdate() }.onFailure { toast("Pemeriksaan update gagal: ${it.message}") }
        })
        content.addView(settingRowClickable("Tentang", "GITLS $appVersion", "information-outline") { showAbout() })
    }
internal fun MainActivity.appDataStorageText(): String = formatBytes(appDataSize())

internal fun MainActivity.appDataSize(): Long {
        return directorySize(filesDir) + directorySize(cacheDir) + directorySize(codeCacheDir)
    }
internal fun MainActivity.cacheDirSize(): Long = directorySize(cacheDir) + directorySize(codeCacheDir)

internal fun MainActivity.directorySize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return runCatching { file.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
    }
internal fun MainActivity.formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
internal fun MainActivity.showAppDataStorageDialog() {
        val data = directorySize(filesDir)
        val cache = cacheDirSize()
        val total = data + cache
        AlertDialog.Builder(this)
            .setTitle("Penyimpanan Aplikasi")
            .setMessage("Data aplikasi: ${formatBytes(data)}\nCache: ${formatBytes(cache)}\nTotal: ${formatBytes(total)}\n\nFile kerja di dalam penyimpanan aplikasi tidak akan dihapus dari menu ini.")
            .setPositiveButton("OK", null)
            .show()
    }
internal fun MainActivity.confirmClearAppCache() {
        val size = cacheDirSize()
        if (size <= 0L) { toast("Cache sudah kosong"); return }
        AlertDialog.Builder(this)
            .setTitle("Bersihkan Cache?")
            .setMessage("Hapus sekitar ${formatBytes(size)} cache sementara? Pengaturan dan file kerja tetap aman.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Bersihkan") { _, _ ->
                runCatching {
                    cacheDir.deleteRecursively()
                    codeCacheDir.deleteRecursively()
                    cacheDir.mkdirs()
                    codeCacheDir.mkdirs()
                }.onSuccess { toast("Cache dibersihkan"); showSettings() }
                 .onFailure { toast("Cache gagal dibersihkan: ${it.message}") }
            }.show()
    }
internal fun MainActivity.confirmResetPreferences() {
        AlertDialog.Builder(this)
            .setTitle("Reset Pengaturan?")
            .setMessage("Tema, layout Beranda, preferensi tampilan, dan pengaturan lokal akan dikembalikan ke awal. File kerja dan database keuangan tidak dihapus.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Reset") { _, _ ->
                // Data pengguna (bukan preferensi tampilan) tidak boleh ikut terhapus: pengingat, kategori keuangan, dll.
                val keepKeys = setOf("history", "recent_files", "recent_files_times", "favorite_tools", "home_tools", "latest_tool_updates", "recent_tools", "tool_update_versions",
                    "scheduled_reminders", "clipboard_history", "finance_custom_categories", "color_history", "last_workspace", "last_web_project",
                    "editor_source_uri", "gh_exclude_patterns", "pinned_tools", "hidden_tools", "gh_token_enc", "gh_save_token", "tool_usage")
                val current = prefs.all
                val editor = prefs.edit().clear()
                current.forEach { (key, value) ->
                    if (key in keepKeys || key.startsWith("bit_")) {
                        when (value) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Float -> editor.putFloat(key, value)
                            is String -> editor.putString(key, value)
                            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                }
                editor.apply()
                isDarkTheme = false
                applySystemTheme()
                toast("Pengaturan dikembalikan")
                showSettings()
            }.show()
    }
internal fun MainActivity.settingRowClickable(name: String, desc: String, iconName: String, action: () -> Unit): View {
        return settingRowClickable(name, "", desc, iconName, action)
    }
internal fun MainActivity.settingRowClickable(name: String, value: String, desc: String, iconName: String, action: () -> Unit): View {
        // Pengaturan memakai baris ringkas dengan ikon dalam tile rounded agar
        // daftar lebih bervariasi, mudah dipindai, dan tidak terasa seperti blok besar berulang.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = rippleBg(panel, Ds.RADIUS_LG, line)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        Motion.press(card, 0.985f)
        val iconTile = FrameLayout(this).apply {
            background = bg(panel2, Ds.RADIUS_MD)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(14) }
        }
        val icon = MdiIconView(this).apply {
            setIconName(iconName); setIconSize(23f); setTextColor(textMain)
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        iconTile.addView(icon)
        card.addView(iconTile)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        box.addView(label(name, 15f, true))
        if (value.isNotBlank()) {
            box.addView(label(value, 12f).apply {
                setTextColor(textMuted)
                setPadding(0, dp(2), 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        if (desc.isNotBlank()) {
            box.addView(subLabel(desc, 11f).apply {
                setPadding(0, dp(2), 0, 0)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        card.addView(box)
        card.addView(TextView(this).apply {
            text = "›"; textSize = 27f; setTextColor(textMuted); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(46)).apply { leftMargin = dp(5) }
        })
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            minimumHeight = dp(72)
        }
    }
internal fun MainActivity.settingsSection(text: String) {
        content.addView(TextView(this).apply {
            this.text = text.uppercase(Locale.getDefault())
            textSize = 11f
            letterSpacing = 0.06f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textMuted)
            setPadding(dp(6), dp(20), dp(4), dp(8))
        })
    }
internal fun MainActivity.settingRow(name: String, value: String, desc: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = bg(panel2, Ds.RADIUS_MD)
        }
        card.addView(label(name, 15f, true))
        card.addView(label(value, 14f).apply { setPadding(dp(2), dp(1), dp(2), dp(4)) })
        card.addView(subLabel(desc, 12f).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.minimumHeight = dp(96)
        card.layoutParams = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(7)
        }
        return card
    }
