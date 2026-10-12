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


internal fun MainActivity.showHome(filter: String = homeFilter) {
        homeFilter = filter
        clearPage("home", true)
        content.setPadding(dp(Ds.HOME_H), dp(Ds.HOME_V), dp(Ds.HOME_H), dp(Ds.HOME_BOTTOM))

        // Beranda dibuat sebagai ringkasan aplikasi: kategori Tools + daftar Studio.
        // Filter lama tetap dipakai untuk menjaga perilaku pencarian/favorit.
        if (filter != "Semua") {
            val title = when (filter) {
                "Favorit" -> "Favorit"
                "Terbaru" -> "Terbaru"
                "Populer" -> "Populer"
                else -> "Tools"
            }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(10)) }
            row.addView(label(title, 20f, true), LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(row)
            val items = filteredHomeItems(filter)
            if (items.isEmpty()) {
                content.addView(subLabel("Belum ada tool pada bagian ini.", 13f))
            } else {
                items.forEach { (id, name) ->
                    content.addView(toolCard(id, name, iconFor(id)).apply {
                        layoutParams = LinearLayout.LayoutParams(-1, dp(70)).apply { bottomMargin = dp(7) }
                    })
                }
            }
            return
        }

        // ==================== DASHBOARD ====================
        homeDashboardCard()
        // OPEN-02: baris Pengaturan › Beranda kini benar-benar dibaca di sini.
        if (prefs.getBoolean("show_quick_access", true)) homeQuickTools()
        if (prefs.getBoolean("show_recent_activity", false)) homeRecentTools()
        homeMostUsedTools()
        homePinnedTools()

        // ==================== TOOLS ====================
        homeSectionHeader("Tools", "Lihat Semua") { showAllTools() }
        val homeColumns = prefs.getInt("home_columns", 2).coerceIn(2, 3)
        val toolGrid = GridLayout(this).apply {
            columnCount = homeColumns
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }

        // Pengelompokan Beranda mengikuti fungsi tool yang sebenarnya.
        // Studio tetap dipisahkan dari Tools; kartu di bawah hanya berisi tool operasional.
        val toolCategories = listOf(
            HomeCategory("FILE & APP", "13 tools", "folder-outline", listOf(
                "filemanager", "recentfiles", "backuprestore", "zip", "githubzip", "fileconvert", "batchconvert", "filesearch",
                "apk", "apkcompare", "duplicatefinder", "largefilefinder", "filehashcompare", "dedupe"
            )),
            HomeCategory("SYSTEM", "", "cellphone-cog", listOf(
                "devicecenter", "deviceinfo", "battery", "storage", "apps", "clipboard"
            )),
            HomeCategory("CALCULATOR", "", "calculator-variant-outline", listOf(
                "number", "pivotcalc", "dividercalc", "dcacalc", "pwmcalc", "spritecalc", "installcalc", "powercalc", "aspectcalc", "pphcalc",
                "riskcalc", "compoundcalc", "margincalc", "discountcalc", "datacalc", "pressurecalc", "worktimecalc", "basecalc", "equationcalc"
            )),
            HomeCategory("TEXT & DEV", "31 tools", "file-document-edit-outline", listOf(
                "editor", "json", "packageinspector", "apiconnections", "base64", "url", "regex", "uuid", "textstat", "case", "compare", "slug", "token", "random",
                "hex", "base32", "timestamp", "unicode", "urlparser", "mime", "jsonformat", "xmlformat", "uuidbatch", "base64file",
                "textreplace", "wordfreq", "markdown", "sql", "yaml", "toml", "cron", "helpbot"
            )),
            HomeCategory("SECURITY", "", "shield-check-outline", listOf(
                "hash", "password", "passwordstrength", "jwt", "hmac", "totp", "aes", "checksum", "securitycenter", "fileencryption",
                "steganography", "passwordanalyzer", "breachchecker", "securenotes", "totpvault", "pgp", "sshkeygen", "certviewer", "virusscanner", "urlsafety"
            )),
            HomeCategory("NETWORK", "", "web", listOf(
                "dns", "dohdns", "rdns", "port", "publicip", "ping", "ipinfo", "ipintelligence", "ssl", "http", "httpheaders", "restclient", "websocket", "network", "networkcenter", "wifi", "webhostwifi", "whois", "traceroute", "subnetcalc", "netscanner"
            )),
            HomeCategory("MEDIA & COLOR", "13 tools", "palette-outline", listOf(
                "color", "imagestudio", "creativestudio", "presentationstudio", "videoeditor", "audioeditor", "imagecompress", "imageconvert", "autotrace", "batchconvert", "videotoaudio", "videoframes"
            )),
            HomeCategory("QR / OCR", "", "qrcode", listOf("qr", "ocr")),
            HomeCategory("FINANCE", "", "bank-outline", listOf("financereader", "financedashboard", "dcacalc", "compoundcalc", "margincalc", "discountcalc", "pphcalc", "riskcalc")),
            HomeCategory("UTILITY", "", "toolbox-outline", listOf("reminder", "stopwatch", "timer", "unitconverter")),
            HomeCategory("STUDIO", "", "view-grid-outline", listOf("webproject", "webeditor", "networkstudio", "developerstudio", "filestudio", "imagestudio", "editingstudio", "creativestudio", "colorstudio", "systemstudio", "financestudio", "utilitystudio", "workspace", "plugincenter", "customtools", "studiocenter"))
        )

        toolCategories.forEach { category ->
            val card = homeCategoryCard(category)
            toolGrid.addView(card, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(76)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        content.addView(toolGrid, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // ==================== STUDIO ====================
        homeSectionHeader("Studio", "Lihat Semua") { showAllStudios() }
        val studios = listOf(
            Triple("Web Project Builder", "web-box", "webproject"),
            Triple("Network Studio", "web", "networkstudio"),
            Triple("Developer Studio", "code-tags", "developerstudio"),
            Triple("File Studio", "folder-outline", "filestudio"),
            Triple("Image Studio", "image-outline", "imagestudio"),
            Triple("Editing Studio", "movie-edit-outline", "editingstudio"),
            Triple("Foto Studio", "palette-outline", "creativestudio"),
            Triple("Finance Studio", "bank-outline", "financestudio"),
            Triple("System Studio", "cog-outline", "systemstudio"),
            Triple("Utility Studio", "toolbox-outline", "utilitystudio")
        )
        val studioGrid = GridLayout(this).apply {
            columnCount = homeColumns
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        studios.forEach { (name, icon, id) ->
            studioGrid.addView(homeStudioCard(name, icon, id), GridLayout.LayoutParams().apply {
                width = 0
                height = dp(72)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        content.addView(studioGrid, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        content.post { Motion.stagger(content, 6, 36L) }
    }
internal fun MainActivity.homeQuickTools() {
        homeSectionHeader("Akses Cepat", "Semua Tools") { showAllTools() }
        val quick = listOf(
            "filemanager" to "File",
            "color" to "Warna",
            "qr" to "QR",
            "hash" to "Hash",
            "fileconvert" to "Convert",
            "videotoaudio" to "Video→Audio",
            "reminder" to "Reminder"
        )
        val row = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // Jangan clip sudut bawah kartu rounded.
            clipToPadding = false
            clipChildren = false
            setPadding(0, dp(2), 0, dp(4))
        }
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipToPadding = false
            clipChildren = false
            setPadding(0, 0, dp(4), 0)
        }
        quick.forEach { (id, name) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(12), dp(8), dp(10))
                background = rippleBg(panel, Ds.RADIUS_LG, line)
                isClickable = true
                setOnClickListener { openTool(id) }
                addPressFeedback(this)
                contentDescription = "Akses cepat $name"
            }
            card.addView(MdiIconView(this).apply {
                setIconName(iconFor(id))
                setIconSize(22f)
                setTextColor(textMain)
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { bottomMargin = dp(4) })
            card.addView(label(name, 10.5f, true).apply { gravity = Gravity.CENTER })
            // Tinggi cukup untuk ikon + label + radius, tidak terpotong.
            rail.addView(card, LinearLayout.LayoutParams(dp(78), dp(84)).apply { rightMargin = dp(8) })
        }
        row.addView(rail)
        content.addView(row, LinearLayout.LayoutParams(-1, dp(92)).apply { bottomMargin = dp(4) })
    }
internal fun MainActivity.homeRecentTools() {
        val recentIds = prefs.getString("recent_tools", "")?.split(',')
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.take(6) ?: emptyList()
        val recent = recentIds.mapNotNull { id -> homeToolMap[id]?.let { id to it } }
        if (recent.isEmpty()) return
        homeSectionHeader("Terakhir Digunakan", "Riwayat") { historyTool() }
        val row = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, dp(3))
        }
        val rail = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recent.forEach { (id, name) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), dp(8), dp(10), dp(8))
                background = bg(if (isDarkTheme) panel2 else Color.WHITE, 16, line)
                isClickable = true
                setOnClickListener { openTool(id) }
                contentDescription = "Buka $name dari riwayat"
            }
            card.addView(MdiIconView(this).apply {
                setIconName(iconFor(id))
                setIconSize(19f)
                setTextColor(textMain)
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(7) })
            card.addView(label(name, 11f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(dp(92), dp(34)))
            rail.addView(card, LinearLayout.LayoutParams(dp(135), dp(54)).apply { rightMargin = dp(7) })
        }
        row.addView(rail)
        content.addView(row, LinearLayout.LayoutParams(-1, dp(60)).apply { bottomMargin = dp(3) })
    }
internal fun MainActivity.homeMostUsedTools() {
        val usage = prefs.getString("tool_usage", "")
            ?.split(',')
            ?.mapNotNull { part ->
                val bits = part.split(':', limit = 2)
                if (bits.size == 2) bits[0].trim().takeIf { it.isNotEmpty() }?.let { id -> id to (bits[1].toIntOrNull() ?: 0) } else null
            }
            ?.filter { it.second > 0 }
            ?.sortedByDescending { it.second }
            ?: emptyList()
        val mostUsed = usage.mapNotNull { (id, count) -> homeToolMap[id]?.let { Triple(id, it, count) } }.take(6)
        if (mostUsed.isEmpty()) return

        // Pengguna dapat menutup section ini dengan X agar Beranda lebih ringkas.
        // Status disimpan sehingga section benar-benar hilang pada kunjungan berikutnya.
        if (prefs.getBoolean("hide_most_used_home", false)) return

        val row = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, dp(3))
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        header.addView(MdiIconView(this).apply {
            setIconName("view-grid")
            setIconSize(21f)
            setTextColor(textMain)
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(5) })
        header.addView(label("Paling Sering Digunakan", 19f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "×"
            textSize = 22f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(8), 0, dp(4), 0)
            minWidth = dp(40)
            minHeight = dp(40)
            contentDescription = "Sembunyikan Paling Sering Digunakan"
            setOnClickListener {
                prefs.edit().putBoolean("hide_most_used_home", true).apply()
                content.removeView(header)
                content.removeView(row)
            }
        })
        content.addView(header, LinearLayout.LayoutParams(-1, -2))

        val rail = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mostUsed.forEach { (id, name, count) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(8), dp(9), dp(7))
                background = bg(if (isDarkTheme) panel2 else Color.WHITE, 16, line)
                isClickable = true
                setOnClickListener { openTool(id) }
                addPressFeedback(this)
                contentDescription = "$name, digunakan $count kali"
            }
            card.addView(MdiIconView(this).apply {
                setIconName(iconFor(id)); setIconSize(21f); setTextColor(textMain)
            }, LinearLayout.LayoutParams(dp(30), dp(30)))
            card.addView(label(name, 10.5f, true).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(dp(94), dp(31)))
            card.addView(subLabel("$count× digunakan", 8.5f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(15)))
            rail.addView(card, LinearLayout.LayoutParams(dp(116), dp(82)).apply { rightMargin = dp(7) })
        }
        row.addView(rail)
        content.addView(row, LinearLayout.LayoutParams(-1, dp(86)).apply { bottomMargin = dp(3) })
    }
internal fun MainActivity.homePinnedTools() {
        // OPEN-02: tool yang dipin di Tool Customization ("pinned_tools") ikut tampil di sini.
        val pinnedIds = (prefs.getStringSet("pinned_tools", emptySet()) ?: emptySet()).sorted()
        val favorites = (pinnedIds + favoriteToolIds()).distinct().mapNotNull { id -> homeToolMap[id]?.let { id to it } }.take(8)
        if (favorites.isEmpty()) return
        homeSectionHeader("Pinned / Favorit", "Kelola") { showFavorites() }
        val row = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, dp(4))
        }
        val rail = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        favorites.forEach { (id, name) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = bg(if (isDarkTheme) panel2 else Color.WHITE, 16, line)
                isClickable = true
                setOnClickListener { openTool(id) }
                addPressFeedback(this)
                contentDescription = "Buka favorit $name. Tahan lama untuk hapus dari favorit"
            }
            bindFavoriteLongPress(card, id) { showHome(homeFilter) }
            card.addView(MdiIconView(this).apply { setIconName(iconFor(id)); setIconSize(21f); setTextColor(textMain) }, LinearLayout.LayoutParams(dp(30), dp(30)))
            card.addView(label(name, 10.5f, true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(dp(92), dp(30)))
            rail.addView(card, LinearLayout.LayoutParams(dp(112), dp(70)).apply { rightMargin = dp(7) })
        }
        row.addView(rail)
        content.addView(row, LinearLayout.LayoutParams(-1, dp(76)).apply { bottomMargin = dp(5) })
    }
internal fun MainActivity.homeDashboardCard() {
    val activity = this
    val historyCount = runCatching {
            JSONArray(prefs.getString("history", "[]") ?: "[]").length()
        }.getOrDefault(0)
        val favoriteCount = favoriteToolIds().size
        val reminderCount = runCatching {
            JSONArray(prefs.getString("reminders", "[]") ?: "[]").length()
        }.getOrDefault(0)
        val toolCount = homeToolMap.size

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            contentDescription = "Ringkasan GITLS"
        }
        applyInteractiveSurface(card, Ds.RADIUS_XL, 0)

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(MdiIconView(this).apply {
            setIconName("view-dashboard-outline")
            setIconSize(21f)
            setTextColor(textMain)
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(5) })
        header.addView(label("Ringkasan", 17f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "Detail  ›"
            textSize = 11.5f
            setTextColor(textMuted)
            setPadding(dp(5), dp(7), 0, dp(7))
            setOnClickListener { showSettings() }
            contentDescription = "Buka pengaturan GITLS"
        })
        card.addView(header)

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        fun stat(icon: String, value: String, title: String, click: (() -> Unit)? = null): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(5), dp(5), dp(5))
                contentDescription = "$title: $value"
                addView(MdiIconView(activity).apply {
                    setIconName(icon); setIconSize(19f); setTextColor(textMuted)
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(label(value, 16f, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
                addView(subLabel(title, 9.5f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
                if (click != null) setOnClickListener { click() }
            }
        }
        stats.addView(stat("star", favoriteCount.toString(), "Favorit") { showFavorites() }, LinearLayout.LayoutParams(0, -2, 1f))

        // Aktivitas menampilkan tool terakhir sebagai ikon kecil langsung di dalam slot statistik.
        // Ini menggantikan section "Terakhir Digunakan" yang sebelumnya memakan banyak ruang.
        val activityStat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(5), dp(2), dp(5))
            setOnClickListener { historyTool() }
            contentDescription = "Aktivitas, $historyCount aktivitas"
        }
        val recentIds = prefs.getString("recent_tools", "")?.split(',')
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.take(3) ?: emptyList()
        val recentIconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        if (recentIds.isEmpty()) {
            recentIconRow.addView(MdiIconView(this).apply {
                setIconName("history")
                setIconSize(19f)
                setTextColor(textMuted)
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
        } else {
            recentIds.forEachIndexed { index, id ->
                if (homeToolMap.containsKey(id)) {
                    recentIconRow.addView(MdiIconView(this).apply {
                        setIconName(iconFor(id))
                        setIconSize(17f)
                        setTextColor(textMain)
                        contentDescription = homeToolMap[id] ?: id
                    }, LinearLayout.LayoutParams(dp(22), dp(24)).apply {
                        if (index > 0) leftMargin = dp(2)
                    })
                }
            }
        }
        activityStat.addView(recentIconRow, LinearLayout.LayoutParams(-1, dp(24)))
        activityStat.addView(subLabel("Aktivitas", 9.5f).apply { gravity = Gravity.CENTER })
        stats.addView(activityStat, LinearLayout.LayoutParams(0, -2, 1f))

        stats.addView(stat("bell-outline", reminderCount.toString(), "Reminder") { openTool("reminder") }, LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(stat("tools", toolCount.toString(), "Tools") { showAllTools() }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(stats)

        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(4)
        })
    }
internal fun MainActivity.homeSectionHeader(titleText: String, actionText: String, actionClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            // Rapikan jarak antar section — tidak terlalu longgar di Beranda.
            setPadding(dp(Ds.SPACE_XS), dp(Ds.SPACE_SM), 0, dp(Ds.SPACE_XS))
            minimumHeight = dp(36)
        }
        val gridIcon = MdiIconView(this).apply {
            setIconName("view-grid-outline")
            setIconSize(Ds.ICON_MD)
            setTextColor(textMuted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(gridIcon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(Ds.SPACE_SM) })
        row.addView(label(titleText, Ds.TEXT_TITLE, true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = actionText + "  ›"
            textSize = Ds.TEXT_CAPTION_MIN
            setTextColor(textMuted)
            minHeight = dp(Ds.TOUCH_MIN)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ds.SPACE_SM), 0, dp(Ds.SPACE_XS), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { actionClick() }
            contentDescription = "$actionText $titleText"
        })
        content.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
internal fun MainActivity.homeCategoryCard(category: HomeCategory): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(8), dp(6))
            val availableCount = category.ids.count { homeToolMap.containsKey(it) }
            contentDescription = "${category.name}, $availableCount tools"
        }
        applyInteractiveSurface(card, Ds.RADIUS_LG, 0)
        Motion.press(card, 0.98f)
        card.setOnClickListener {
            // SYSTEM langsung membuka halaman gabungan Device Info + System Center + Battery Info.
            // Tidak perlu menampilkan tombol/menu perantara lagi.
            if (category.name.equals("SYSTEM", ignoreCase = true)) {
                deviceSystemCenterTool()
            } else {
                val ids = category.ids.filter { homeToolMap.containsKey(it) }
                showCategory(category.name, ids)
            }
        }

        // Ikon kategori dibuat langsung di atas card tanpa kotak/badge warna tambahan.
        // Hasilnya lebih ringan dan konsisten dengan ikon hitam pada screenshot referensi.
        card.addView(MdiIconView(this).apply {
            setIconName(category.icon)
            setIconSize(24f)
            setTextColor(textMain)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(4), 0)
        }
        texts.addView(label(category.name, 12.5f, true))
        val availableCount = category.ids.count { homeToolMap.containsKey(it) }
        texts.addView(subLabel("$availableCount tools", 10.5f))
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(TextView(this).apply {
            text = "›"
            textSize = 22f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(20), dp(42)))
        return card
    }
internal fun MainActivity.homeStudioCard(name: String, iconName: String, id: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            contentDescription = "$name. Buka Studio"
        }
        applyInteractiveSurface(card, 18, 1)
        card.setOnClickListener { openTool(id) }

        card.addView(MdiIconView(this).apply {
            setIconName(iconName)
            setIconSize(23f)
            setTextColor(textMain)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(6), 0)
        }
        texts.addView(label(name, 13.5f, false))
        texts.addView(subLabel("Buka Studio", 10.5f))
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(TextView(this).apply {
            text = "›"
            textSize = 22f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(22), dp(42)))
        return card
    }
internal fun MainActivity.showAllStudios() {
        clearPage("all", true)
        suppressSearch = true
        search.setText("")
        suppressSearch = false
        content.addView(label("Semua Studio", 22f, true))
        content.addView(subLabel("Ruang kerja untuk membuat dan mengelola proyek.", 12f).apply {
            setPadding(0, 0, 0, dp(10))
        })
        val studios = listOf(
            Triple("Web Project Builder", "web-box", "webproject"),
            Triple("Network Studio", "web", "networkstudio"),
            Triple("Developer Studio", "code-tags", "developerstudio"),
            Triple("File Studio", "folder-outline", "filestudio"),
            Triple("Image Studio", "image-outline", "imagestudio"),
            Triple("Editing Studio", "movie-edit-outline", "editingstudio"),
            Triple("Foto Studio", "palette-outline", "creativestudio"),
            Triple("Finance Studio", "bank-outline", "financestudio"),
            Triple("System Studio", "cog-outline", "systemstudio"),
            Triple("Utility Studio", "toolbox-outline", "utilitystudio")
        )
        studios.forEach { (name, icon, id) ->
            content.addView(homeStudioCard(name, icon, id), LinearLayout.LayoutParams(-1, dp(76)).apply {
                bottomMargin = dp(7)
            })
        }
    }
internal fun MainActivity.filteredHomeItems(filter: String): List<Pair<String, String>> {
        val base = homeToolMap
        return when (filter) {
            "Favorit" -> favoriteToolIds().mapNotNull { id -> base[id]?.let { id to it } }
            "Terbaru" -> {
                val updated = latestUpdatedToolIds()
                val recent = prefs.getString("recent_tools", "")
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                (updated + recent).distinct().mapNotNull { id -> base[id]?.let { id to it } }
                    .ifEmpty { defaultPreferredTools().take(12).mapNotNull { id -> base[id]?.let { id to it } } }
            }
            "Populer" -> defaultPreferredTools().take(20).mapNotNull { id -> base[id]?.let { id to it } }
            else -> defaultPreferredTools().mapNotNull { id -> base[id]?.let { id to it } }
        }
    }
internal fun MainActivity.defaultPreferredTools(): List<String> = listOf(
        "filemanager", "editor", "reminder", "zip", "githubzip", "http", "json", "hash", "base64", "url", "regex", "uuid", "color",
        "number", "textstat", "case", "dedupe", "compare", "slug", "password", "token", "jwt", "hmac", "totp", "aes",
        "random", "checksum", "hex", "base32", "dns", "dohdns", "rdns", "port", "publicip", "ping", "ipinfo", "ipintelligence", "ssl", "apk", "qr", "system",
        "timestamp", "unicode", "urlparser", "mime", "jsonformat", "xmlformat", "uuidbatch", "base64file", "httpheaders", "textreplace",
        "wordfreq", "devicecenter", "storage", "apps", "network", "filesearch", "pivotcalc", "dividercalc", "dcacalc",
        "pwmcalc", "spritecalc", "installcalc", "powercalc", "aspectcalc", "pphcalc", "financereader", "financedashboard",
        "securitycenter", "clipboard", "ocr", "unitconverter", "apkanalyzer", "netscanner",
        "webproject", "networkstudio", "developerstudio", "filestudio", "imagestudio",
        "systemstudio", "financestudio", "utilitystudio", "whois", "traceroute", "subnetcalc"
    )

internal fun MainActivity.favoriteToolIds(): List<String> = prefs.getString("favorite_tools", "")
        ?.split(',')?.map { CsToolIds.canonical(it.trim()) }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()

internal fun MainActivity.isFavorite(id: String): Boolean = favoriteToolIds().contains(id)

/**
 * Tahan lama kartu tool = tambah/hapus Favorit. Favorit tidak lagi diatur dari dalam tool
 * (menu ⋮ / toolbar bawah dihapus), jadi semua kartu tool di Beranda, Semua Tools, Favorit,
 * dan hasil pencarian memakai helper ini.
 */
internal fun MainActivity.bindFavoriteLongPress(view: View, id: String, onChanged: (() -> Unit)? = null) {
    if (!homeToolMap.containsKey(id)) return
    view.isLongClickable = true
    view.setOnLongClickListener { v ->
        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        toggleFavorite(id)
        onChanged?.invoke()
        if (currentPage == "favorites") showFavorites(force = true)
        true
    }
}

internal fun MainActivity.toggleFavorite(id: String) {
        val old = favoriteToolIds().toMutableList()
        if (old.contains(id)) old.remove(id) else old.add(0, id)
        val nowFavorite = old.contains(id)
        prefs.edit().putString("favorite_tools", old.distinct().take(30).joinToString(",")).apply()
        toast(if (nowFavorite) "Ditambahkan ke favorit" else "Dihapus dari favorit")
    }
internal fun MainActivity.toolCategory(id: String): String {
        val n = (homeToolMap[id] ?: id).lowercase(Locale.getDefault())
        return when {
            id in setOf("network", "networkcenter", "wifi", "dns", "rdns", "ping", "port", "publicip", "ipinfo", "ssl", "http", "httpheaders", "restclient", "websocket", "netscanner", "whois", "traceroute", "subnetcalc", "webhostwifi") -> "Network"
            id in setOf("password", "passwordstrength", "passwordanalyzer", "hash", "checksum", "filehashcompare", "token", "aes", "hmac", "jwt", "totp", "fileencryption", "steganography", "breachchecker", "securenotes", "totpvault", "pgp", "sshkeygen", "certviewer", "virusscanner", "urlsafety", "securitycenter") -> "Security"
            id in setOf("financereader", "financedashboard", "dcacalc", "riskcalc", "compoundcalc", "margincalc", "discountcalc", "pphcalc", "powercalc", "installcalc", "pivotcalc") || n.contains("finance") || n.contains("keuangan") || n.contains("pajak") || n.contains("margin") || n.contains("diskon") -> "Finance"
            id in setOf("filemanager", "recentfiles", "backuprestore", "zip", "githubzip", "fileconvert", "batchconvert", "filesearch", "storage", "apps", "apk", "apkcompare", "duplicatefinder", "largefilefinder", "fileencryption") || n.contains("file") || n.contains("zip") || n.contains("apk") || n.contains("storage") || n.contains("folder") -> "File"
            id in setOf("editor", "json", "jsonformat", "base64", "url", "regex", "uuid", "textstat", "case", "compare", "slug", "random", "hex", "base32", "timestamp", "unicode", "urlparser", "mime", "uuidbatch", "base64file", "textreplace", "wordfreq", "markdown", "sql", "yaml", "toml", "cron") || n.contains("text") || n.contains("json") || n.contains("xml") || n.contains("regex") || n.contains("base64") || n.contains("unicode") || n.contains("slug") -> "Developer"
            id in setOf("number", "basiccalc", "scicalc", "percentcalc", "fractioncalc", "ratiocalc", "unitcalc", "areacalc", "volumecalc", "speedcalc", "timecalc", "datecalc", "loancalc", "fuelcalc", "pivotcalc", "dividercalc", "dcacalc", "pwmcalc", "spritecalc", "installcalc", "powercalc", "aspectcalc", "pphcalc", "riskcalc", "compoundcalc", "margincalc", "discountcalc", "datacalc", "pressurecalc", "worktimecalc", "basecalc", "equationcalc") || n.contains("calc") || n.contains("kalkulator") || n.contains("converter") || n.contains("konversi") || n.contains("aspect") -> "Calculator"
            id in setOf("devicecenter", "deviceinfo", "battery", "apps", "storage", "clipboard") || n.contains("device") || n.contains("battery") || n.contains("baterai") -> "System"
            id in setOf("color", "imagestudio", "ocr", "qr", "colorstudio", "videotoaudio", "videoframes", "editingstudio", "creativestudio", "presentationstudio", "photoeditor", "videoeditor", "audioeditor", "designstudio", "bgremover", "imagecompress", "imageconvert", "autotrace", "batchconvert") -> "Media"
            else -> "Utility"
        }
    }
internal fun MainActivity.syncToolUpdates() {
        val stored = prefs.getString("tool_update_versions", "")
            ?.split("|")?.mapNotNull { part ->
                val pieces = part.split("=", limit = 2)
                if (pieces.size == 2 && pieces[0].isNotBlank()) pieces[0] to pieces[1] else null
            }?.toMap()?.toMutableMap() ?: mutableMapOf()
        val latest = prefs.getString("latest_tool_updates", "")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()

        var changed = false
        toolUpdateCatalog.forEach { (id, version) ->
            if (stored[id] != version) {
                latest.remove(id)
                latest.add(0, id)
                stored[id] = version
                changed = true
            }
        }
        if (changed) {
            prefs.edit()
                .putString("tool_update_versions", stored.entries.joinToString("|") { "${it.key}=${it.value}" })
                .putString("latest_tool_updates", latest.distinct().take(20).joinToString(","))
                .apply()
        }
    }
internal fun MainActivity.latestUpdatedToolIds(): List<String> {
        val ids = prefs.getString("latest_tool_updates", "")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        return ids.filter { id -> homeToolMap.containsKey(id) }.take(6)
    }
internal fun MainActivity.latestToolVersion(id: String): String = toolUpdateCatalog[id] ?: "2.19.8"

internal fun MainActivity.recordRecentTool(id: String) {
        val old = prefs.getString("recent_tools", "")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val updated = (listOf(id) + old.filter { it != id }).take(20)

        // Track lightweight local usage counts so the Home dashboard can surface
        // tools the user actually relies on. This stays on-device and never needs
        // an account or network connection.
        val usage = prefs.getString("tool_usage", "")
            ?.split(',')
            ?.mapNotNull { part ->
                val bits = part.split(':', limit = 2)
                if (bits.size == 2) bits[0].trim().takeIf { it.isNotEmpty() }?.let { key -> key to (bits[1].toIntOrNull() ?: 0) } else null
            }
            ?.toMap()
            ?.toMutableMap() ?: mutableMapOf()
        usage[id] = (usage[id] ?: 0) + 1
        val encodedUsage = usage.entries
            .sortedByDescending { it.value }
            .take(30)
            .joinToString(",") { "${it.key}:${it.value}" }

        prefs.edit()
            .putString("recent_tools", updated.joinToString(","))
            .putString("tool_usage", encodedUsage)
            .apply()
    }
internal fun MainActivity.latestToolCard(id: String, name: String): LinearLayout {
    val activity = this
    val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(9), dp(8), dp(7))
            contentDescription = "$name. Tool terbaru"
        }
        applyInteractiveSurface(card, 18, 2)
        addPressFeedback(card)
        card.setOnClickListener { openToolWithPress(id, card) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(MdiIconView(activity).apply {
            setIconName(iconFor(id)); setIconSize(21f); setTextColor(Color.WHITE)
            setPadding(dp(5), dp(5), dp(5), dp(5)); background = bg(Color.rgb(25,25,27), 11)
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        top.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(TextView(this).apply {
            text = "UPDATE"; textSize = 7.5f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(90,90,95)); gravity = Gravity.CENTER; setPadding(dp(5), dp(3), dp(5), dp(3))
            background = bg(Color.rgb(242,242,242), 8)
        }, LinearLayout.LayoutParams(-2, dp(24)))
        card.addView(top)
        card.addView(TextView(this).apply {
            text = name; textSize = 12.5f; setTextColor(Color.rgb(17,17,17))
            setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 2
            setPadding(0, dp(7), 0, 0)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        card.addView(TextView(this).apply {
            text = "Versi ${latestToolVersion(id)}"; textSize = 8.5f; setTextColor(Color.rgb(120,120,125))
        }, LinearLayout.LayoutParams(-1, dp(15)))
        return card
    }
internal fun MainActivity.mainPyToolCard(id: String, name: String): LinearLayout {
        val theme = visualTheme(name)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(9), dp(9))
            contentDescription = "$name. Tool"
        }
        applyInteractiveSurface(card, 18, 2)
        addPressFeedback(card)
        card.setOnClickListener { openToolWithPress(id, card) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val accent = View(this).apply { background = bg(if (isDarkTheme) Color.rgb(90,90,96) else Color.rgb(25,25,27), 3) }
        top.addView(accent, LinearLayout.LayoutParams(dp(4), dp(38)).apply { rightMargin = dp(8) })
        val icon = MdiIconView(this).apply {
            setIconName(iconFor(id))
            setIconSize(22f)
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = bg(if (isDarkTheme) Color.rgb(42,42,46) else Color.rgb(25,25,27), 12)
        }
        top.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
        top.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        val star = TextView(this).apply {
            text = if (isFavorite(id)) "★" else "☆"; textSize = 19f; gravity = Gravity.CENTER
            setTextColor(textMain); setPadding(dp(2),0,dp(2),0)
            contentDescription = if (isFavorite(id)) "Hapus $name dari favorit" else "Tambahkan $name ke favorit"
        }
        val refreshStar = {
            star.text = if (isFavorite(id)) "★" else "☆"
            star.contentDescription = if (isFavorite(id)) "Hapus $name dari favorit" else "Tambahkan $name ke favorit"
        }
        star.setOnClickListener { toggleFavorite(id); refreshStar() }
        bindFavoriteLongPress(card, id) { refreshStar() }
        top.addView(star, LinearLayout.LayoutParams(dp(30), dp(38)))
        top.addView(MdiIconView(this).apply {
            setIconName("information-outline")
            setIconSize(17f)
            setTextColor(textMuted)
            contentDescription = "Info $name"
            setOnClickListener { showToolHelpDialog(id) }
        }, LinearLayout.LayoutParams(dp(28), dp(38)))
        top.addView(TextView(this).apply {
            text = "›"
            textSize = 22f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(24), dp(38)))
        card.addView(top)
        card.addView(TextView(this).apply {
            text = name
            textSize = 13.5f
            setTextColor(textMain)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            setPadding(0, dp(8), 0, dp(2))
        }, LinearLayout.LayoutParams(-1, dp(40)))
        card.addView(TextView(this).apply {
            text = toolHelp(id, name).purpose
            textSize = 8.5f
            setTextColor(textMuted)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, dp(30)))
        return card
    }
internal fun MainActivity.homeHeroCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(18), dp(18))
            background = bg(Color.rgb(242, 245, 247), 22)
            setOnClickListener { showAllTools() }
        }
        val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textBox.addView(label("Tempat widget", 24f, true))
        textBox.addView(subLabel("Deskripsi singkat tentang\naplikasi atau fitur utama.", 14f).apply { setPadding(dp(2), 0, 0, 0) })
        val spacer = Space(this)
        val imageBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val image = MdiIconView(this).apply {
            setIconName("view-grid")
            setIconSize(34f)
            setTextColor(Color.rgb(80, 90, 100))
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = bg(Color.rgb(232, 236, 240), 18)
            alpha = 0.7f
        }
        imageBox.addView(image, LinearLayout.LayoutParams(dp(70), dp(70)))
        val dots = TextView(this).apply {
            text = "●  •  •"
            textSize = 11f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        imageBox.addView(dots, LinearLayout.LayoutParams(dp(76), dp(24)))
        card.addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(imageBox, LinearLayout.LayoutParams(dp(86), -1))
        return card
    }
internal fun MainActivity.homeChip(textValue: String, active: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = textValue
        textSize = 11f
        gravity = Gravity.CENTER
        minHeight = dp(38)
        setTextColor(if (active) Color.WHITE else textMain)
        val fill = if (active) (if (isDarkTheme) Color.WHITE else Color.rgb(15,15,16)) else (if (isDarkTheme) Color.rgb(42,42,46) else Color.rgb(241,244,246))
        background = bg(fill, 22)
        setTextColor(if (active) (if (isDarkTheme) Color.BLACK else Color.WHITE) else textMain)
        isClickable = true
        isFocusable = true
        contentDescription = "Filter $textValue${if (active) ", aktif" else ""}"
        setOnClickListener { onClick() }
    }

internal fun MainActivity.homeToolCard(id: String, name: String, desc: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = rippleBg(panel, Ds.RADIUS_LG, line)
            isClickable = true
            setOnClickListener { if (id == "settings") showSettings() else openTool(id) }
        }
        Motion.press(card, 0.98f)
        val iconRes = when (id) {
            "filemanager" -> R.drawable.ic_folder
            "number" -> R.drawable.ic_calculator
            "editor" -> R.drawable.ic_code
            "qr" -> R.drawable.ic_qr
            "reminder" -> R.drawable.ic_bell
            "zip" -> R.drawable.ic_archive
            else -> R.drawable.ic_settings
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = bg(panel2, Ds.RADIUS_MD)
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))
        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        textBox.addView(label(name, 15f, true).apply { setPadding(0, 0, 0, dp(2)) })
        textBox.addView(subLabel(desc, 11f).apply { setPadding(0, 0, 0, 0) })
        card.addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(ImageView(this).apply { setImageResource(R.drawable.ic_arrow_right) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        return card
    }
internal fun MainActivity.showFavorites(force: Boolean = false) {
        // force = true: bangun ulang setelah favorit berubah / pencarian dikosongkan
        // (sebelumnya early-return membuat daftar favorit tidak diperbarui).
        if (!force && currentPage == "favorites" && content.childCount > 0) { selectBottomNav("favorites"); return }
        clearPage("favorites", false)
        suppressSearch = true
        search.setText("")
        suppressSearch = false
        content.setPadding(dp(Ds.HOME_H), dp(Ds.HOME_V), dp(Ds.HOME_H), dp(Ds.HOME_BOTTOM))
        val favorites = favoriteToolIds().mapNotNull { id -> homeToolMap[id]?.let { id to it } }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(10)) }
        header.addView(label("Favorit", 22f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "${favorites.size}/30"
            textSize = 11f
            setTextColor(textMuted)
            background = bg(if (isDarkTheme) Color.rgb(42,42,46) else Color.rgb(241,244,246), 14)
            setPadding(dp(9), dp(5), dp(9), dp(5))
        })
        content.addView(header)
        content.addView(subLabel(if (favorites.isEmpty()) "Tool yang kamu tandai akan muncul di sini." else "Akses cepat ke tool yang paling sering kamu gunakan.", 12f).apply { setPadding(dp(2), 0, 0, dp(14)) })
        if (favorites.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(38), dp(24), dp(38))
                contentDescription = "Belum ada tool favorit"
            }
            applyInteractiveSurface(empty, 20, 1)
            empty.addView(MdiIconView(this).apply { setIconName("star"); setIconSize(38f); setTextColor(textMuted) }, LinearLayout.LayoutParams(-1, dp(50)))
            empty.addView(label("Belum ada favorit", 16f, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(2)) })
            empty.addView(subLabel("Tekan ☆ atau tahan lama kartu tool untuk menyimpannya.", 11f).apply { gravity = Gravity.CENTER })
            content.addView(empty, LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(6) })
            return
        }
        favorites.forEach { (id, name) ->
            content.addView(toolCard(id, name, iconFor(id)).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(70)).apply { bottomMargin = dp(8) }
            })
        }
    }
internal fun MainActivity.setupBitFace() {
        val host = findViewById<FrameLayout>(R.id.navBotAnimationContainer)
        val label = findViewById<TextView>(R.id.navBotLabel)
        label.setTextColor(if (isDarkTheme) Color.rgb(155, 155, 160) else Color.rgb(138, 150, 163))

        val view = LottieAnimationView(this).apply {
            // withGaze=true agar frame mata (1000+) tersedia saat hold/geser
            setBitAsset("bit/bit_idle.json", withGaze = true)
            repeatMode = LottieDrawable.RESTART
            repeatCount = LottieDrawable.INFINITE
            setMinAndMaxFrame(0, 108)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = false
            isFocusable = false
            contentDescription = "Bit"
        }
        host.removeAllViews()
        host.addView(view, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        bitFace = view
        applyBitFaceTheme()
        bitAnim?.attach(view)

        // Klik saja → buka Bit Room (tanpa hold / geser badan).
        navBot.setOnTouchListener(null)
        navBot.setOnLongClickListener(null)
        navBot.setOnClickListener {
            showBitRoomFromNav(reveal = true)
        }
        addPressFeedback(navBot)

        view.post { runCatching { bitAnim?.playIdleAll() } }
    }

/**
 * Opens Bit's dedicated virtual-pet room.
 *
 * The transition intentionally starts at the exact screen position of the
 * navbar Bit, so the companion feels like it expands out of the button.
 * The room is local/offline and does not contain the old AI/chat UI.
 */
internal fun MainActivity.showBitRoomFromNav(reveal: Boolean = true) {
    if (currentPage == "Bit Room") return

    clearPage("Bit Room", true)

    topBarVisibility(false)
    back.visibility = View.GONE
    title.visibility = View.GONE
    subtitle.visibility = View.GONE
    homeMenu.visibility = View.GONE
    homeSearch.visibility = View.GONE
    homeProfile.visibility = View.GONE
    action.visibility = View.GONE
    bottomNav.visibility = View.GONE

    content.setPadding(0, 0, 0, 0)
    scroll.setPadding(0, 0, 0, 0)
    val bgLight = Color.rgb(248, 249, 252)
    val bgDark = Color.rgb(16, 17, 22)
    scroll.setBackgroundColor(if (isDarkTheme) bgDark else bgLight)
    scroll.isVerticalScrollBarEnabled = false
    scroll.isHorizontalScrollBarEnabled = false
    scroll.scrollTo(0, 0)
    scroll.setOnTouchListener { _, e ->
        if (e.actionMasked == MotionEvent.ACTION_MOVE) {
            scroll.requestDisallowInterceptTouchEvent(true)
        }
        false
    }

    // Central pet system
    BitPetSystem.bind(this)
    BitPetSystem.startAutonomy()

    data class ActionUi(val action: BitPetAction, val label: String, val icon: String)
    val actionUi = listOf(
        ActionUi(BitPetAction.PET, "Elus", "heart"),
        ActionUi(BitPetAction.EAT, "Makan", "cake-variant-outline"),
        ActionUi(BitPetAction.PLAY, "Main", "dice-multiple"),
        ActionUi(BitPetAction.BATH, "Mandi", "water-outline"),
        ActionUi(BitPetAction.SLEEP, "Tidur", "sleep")
    )

    val room = FrameLayout(this).apply {
        setBackgroundColor(if (isDarkTheme) bgDark else bgLight)
        clipChildren = false
        clipToPadding = false
    }
    content.addView(room, LinearLayout.LayoutParams(-1, -1))

    val levelChip = TextView(this).apply {
        textSize = 12f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(if (isDarkTheme) Color.rgb(200, 205, 215) else Color.rgb(90, 96, 110))
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(9), dp(18), dp(9))
        background = bg(
            if (isDarkTheme) Color.rgb(28, 30, 36) else Color.WHITE,
            22,
            if (isDarkTheme) Color.rgb(48, 52, 60) else Color.rgb(228, 232, 238)
        )
        contentDescription = "Level Bit"
        setOnClickListener { showBitShapePicker() }
    }
    room.addView(levelChip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
        topMargin = sysTopInset + dp(18)
    })

    // Dedicated scene-prop layer: activity props (bed, tub, table, toys, hand) live
    // here so Bit stays visually centered/locked while the room itself remains stable.
    val sceneProps = FrameLayout(this).apply {
        tag = "bit_scene_props"
        clipChildren = false
        clipToPadding = false
        isClickable = false
        isFocusable = false
    }
    room.addView(sceneProps, FrameLayout.LayoutParams(-1, -1))

    val roomBit = LottieAnimationView(this).apply {
        setBitAsset("bit/bit_idle.json", withGaze = true)
        repeatMode = LottieDrawable.RESTART
        repeatCount = LottieDrawable.INFINITE
        setMinAndMaxFrame(0, 108)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = "Bit"
        isClickable = true
        isFocusable = false
    }
    room.addView(roomBit, FrameLayout.LayoutParams(dp(240), dp(240), Gravity.CENTER).apply {
        bottomMargin = dp(36)
    })
    applyBitFaceTheme(roomBit)
    bitAnim?.attach(roomBit)
    // Soft breathing while room visible (paused during actions via SoftMotion cancel)
    roomBit.post { BitSoftMotion.startBreathing(roomBit) }

    val statusCap = TextView(this).apply {
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(if (isDarkTheme) Color.rgb(165, 170, 180) else Color.rgb(110, 115, 125))
        setPadding(dp(24), 0, dp(24), 0)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    room.addView(statusCap, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
        bottomMargin = dp(128)
    })

    val actionBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = bg(
            if (isDarkTheme) Color.rgb(24, 26, 32) else Color.WHITE,
            28,
            if (isDarkTheme) Color.rgb(42, 46, 54) else Color.rgb(228, 232, 238)
        )
        elevation = dp(3).toFloat()
    }

    fun applySnap(s: BitPetSnapshot) {
        levelChip.text = "Lv ${s.level}  ·  ${s.xp} / ${s.xpToNext} XP"
        // Feedback singkat; idle default lebih subtle
        val idle = s.message == "Bit siap menemani"
        statusCap.animate().cancel()
        if (idle && !s.busy) {
            statusCap.alpha = 0.55f
            statusCap.text = s.message
        } else {
            statusCap.alpha = 1f
            statusCap.text = s.message
            if (!s.busy) {
                statusCap.animate().setStartDelay(1600L).alpha(0.55f).setDuration(400L).start()
            }
        }
    }

    val listener: (BitPetSnapshot) -> Unit = { snap ->
        if (currentPage == "Bit Room") {
            room.post { applySnap(snap) }
        }
    }
    BitPetSystem.addListener(listener)
    applySnap(BitPetSystem.snapshot())

    actionUi.forEach { ui ->
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            contentDescription = ui.label
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Color.argb(40, 0, 0, 0)),
                null,
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.WHITE)
                }
            )
            setOnClickListener {
                if (BitPetSystem.snapshot().busy) {
                    toast("Bit masih sibuk…")
                    return@setOnClickListener
                }
                BitSoftMotion.stopBreathing(roomBit)
                val resumeBreath = {
                    roomBit.postDelayed({
                        if (currentPage != "Bit Room" || !roomBit.isAttachedToWindow || isFinishing || isDestroyed) return@postDelayed
                        val snap = runCatching { BitPetSystem.snapshot() }.getOrNull() ?: return@postDelayed
                        if (!snap.busy) BitSoftMotion.startBreathing(roomBit)
                    }, 800L)
                }
                when (ui.action) {
                    BitPetAction.PET -> {
                        // Elus = short visual touch scene; Bit itself remains position-locked.
                        BitPetActivities.playPetScene(this@showBitRoomFromNav, room, roomBit)
                        val r = BitPetSystem.perform(BitPetAction.PET, roomBit)
                        applySnap(r.snapshot)
                        resumeBreath()
                    }
                    BitPetAction.EAT -> BitPetActivities.openFood(
                        this@showBitRoomFromNav, room, roomBit,
                        onStatus = { statusCap.text = it; statusCap.alpha = 1f },
                        onDone = { ok ->
                            applySnap(BitPetSystem.snapshot())
                            if (ok) resumeBreath() else resumeBreath()
                        }
                    )
                    BitPetAction.BATH -> BitPetActivities.openBath(
                        this@showBitRoomFromNav, room, roomBit,
                        onStatus = { statusCap.text = it; statusCap.alpha = 1f },
                        onDone = { _ -> applySnap(BitPetSystem.snapshot()); resumeBreath() }
                    )
                    BitPetAction.PLAY -> BitPetActivities.openPlay(
                        this@showBitRoomFromNav, room, roomBit,
                        onStatus = { statusCap.text = it; statusCap.alpha = 1f },
                        onDone = { _ -> applySnap(BitPetSystem.snapshot()); resumeBreath() }
                    )
                    BitPetAction.SLEEP -> BitPetActivities.openSleep(
                        this@showBitRoomFromNav, room, roomBit,
                        onStatus = { statusCap.text = it; statusCap.alpha = 1f },
                        onDone = { _ -> applySnap(BitPetSystem.snapshot()); resumeBreath() }
                    )
                }
            }
        }
        col.addView(MdiIconView(this).apply {
            setIconName(ui.icon)
            setIconSize(24f)
            setTextColor(if (isDarkTheme) Color.rgb(225, 229, 236) else Color.rgb(50, 56, 68))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(2))
        })
        col.addView(TextView(this).apply {
            text = ui.label
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(if (isDarkTheme) Color.rgb(190, 195, 205) else Color.rgb(80, 86, 98))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        actionBar.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    }
    room.addView(actionBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
        leftMargin = dp(20)
        rightMargin = dp(20)
        bottomMargin = dp(28) + sysBottomInset.coerceAtLeast(0)
    })

    // Tanpa hold/geser: ketuk Bit = Elus singkat (tombol Elus juga ada).
    // Posisi Bit dikunci permanen di tengah room. Bit tidak draggable.
    // Jangan konsumsi ACTION_UP tanpa performClick(), karena itu mematikan OnClickListener.
    var roomBitDownX = 0f
    var roomBitDownY = 0f
    roomBit.setOnTouchListener { v, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                roomBitDownX = event.x; roomBitDownY = event.y
                v.isPressed = true
                true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                true // locked position; do not drag
            }
            android.view.MotionEvent.ACTION_UP -> {
                v.isPressed = false
                val dx = event.x - roomBitDownX
                val dy = event.y - roomBitDownY
                if (dx * dx + dy * dy <= dp(18).toFloat() * dp(18).toFloat()) v.performClick()
                true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                true
            }
            else -> false
        }
    }
    roomBit.setOnClickListener {
        if (BitPetSystem.snapshot().busy) return@setOnClickListener
        BitSoftMotion.stopBreathing(roomBit)
        BitPetActivities.playPetScene(this, room, roomBit)
        val r = BitPetSystem.perform(BitPetAction.PET, roomBit)
        applySnap(r.snapshot)
        roomBit.postDelayed({
            if (currentPage != "Bit Room" || !roomBit.isAttachedToWindow || isFinishing || isDestroyed) return@postDelayed
            val snap = runCatching { BitPetSystem.snapshot() }.getOrNull() ?: return@postDelayed
            if (!snap.busy) BitSoftMotion.startBreathing(roomBit)
        }, 800L)
    }
    roomBit.post { bitAnim?.playIdle(roomBit) }

    room.post {
        if (!room.isAttachedToWindow || currentPage != "Bit Room") return@post
        room.visibility = View.VISIBLE
        roomBit.translationX = 0f
        roomBit.translationY = 0f
        roomBit.rotation = 0f
        if (Motion.enabled) {
            room.alpha = 0f
            roomBit.scaleX = 0.9f
            roomBit.scaleY = 0.9f
            room.animate().alpha(1f).setDuration(220L).start()
            roomBit.animate().scaleX(1f).scaleY(1f).setDuration(280L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.25f))
                .start()
        }
    }

    // Cleanup when leaving page — clearPage will rebuild content; stop autonomy on next open bind is ok,
    // but also stop when page changes via a one-shot check.
    room.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            BitPetSystem.removeListener(listener)
            BitPetSystem.stopAutonomy()
            BitSoftMotion.stopBreathing(roomBit)
            BitSoftMotion.resetPose(roomBit)
            room.removeOnAttachStateChangeListener(this)
        }
    })
}


internal fun MainActivity.showBitShapePicker() {
    val ids = BitShape.options.map { it.first }
    val labels = BitShape.options.map { it.second }.toTypedArray()
    val checked = ids.indexOf(BitShape.current).coerceAtLeast(0)
    AlertDialog.Builder(this)
        .setTitle("Bentuk Bit")
        .setSingleChoiceItems(labels, checked) { dialog, which ->
            applyBitShape(ids[which])
            dialog.dismiss()
        }
        .setNegativeButton("Batal", null)
        .show()
}

internal fun MainActivity.applyBitShape(shape: String) {
    if (shape == BitShape.current) return
    BitShape.current = shape
    prefs.edit().putString(BitShape.PREF_KEY, shape).apply()
    bitAnim?.reloadShape()
    (action as? BotMenuButton)?.refreshShape()
    (editorMore as? BotMenuButton)?.refreshShape()
    // Wajah di luar controller (mis. avatar di chat) ikut dimuat ulang.
    bitChatTopFace?.let { f -> f.setBitAsset("bit/bit_idle.json"); applyBitFaceTheme(f); f.playAnimation() }
    bitProcessFace?.let { f -> f.setBitAsset("bit/bit_idle.json"); applyBitFaceTheme(f); f.playAnimation() }
    toast("Bentuk Bit diganti")
}

internal fun MainActivity.showBitChat() {
    // Compatibility entry point for old callers. Bit no longer opens a chat;
    // it opens the companion interaction menu instead.
    showBitCompanionMenu()
}

internal fun MainActivity.showBitCompanionMenu() {
    BitPetSystem.bind(this)
    val s = BitPetSystem.snapshot()
    val actions = listOf(
        Triple("heart", "Elus Bit", BitPetAction.PET),
        Triple("cake-variant-outline", "Kasih makan", BitPetAction.EAT),
        Triple("dice-multiple", "Ajak main", BitPetAction.PLAY),
        Triple("water-outline", "Mandikan", BitPetAction.BATH),
        Triple("sleep", "Tidurkan", BitPetAction.SLEEP)
    )
    val rows = mutableListOf<Triple<String, String, () -> Unit>>()
    actions.forEach { (icon, label, act) ->
        rows.add(Triple(icon, label, {
            val r = BitPetSystem.perform(act, bitFace)
            toast(r.message)
        }))
    }
    rows.add(Triple("rocket-launch-outline", "Mini-game", { BitMiniGames.showPicker(this) }))
    rows.add(Triple("chart-line", "Perkembangan", {
        val n = BitPetSystem.snapshot()
        AlertDialog.Builder(this)
            .setTitle("Perkembangan Bit")
            .setMessage(
                "Level: ${n.level}\nXP: ${n.xp} / ${n.xpToNext}\nBond: ${n.bond}\n\n" +
                "Kenyang: ${n.hunger}\nSenang: ${n.happiness}\n" +
                "Bersih: ${n.clean}\nEnergi: ${n.energy}\n\n" +
                "State: ${n.state}\nMood: ${n.mood}\nFun: ${n.funLevel}\nKantuk: ${n.sleepiness}\nAffection: ${n.affection}\nHealth: ${n.health}\nRelationship: Lv ${n.relationshipLevel}\nFavorit: ${n.favoriteFood} / ${n.favoriteActivity}"
            )
            .setPositiveButton("Tutup", null)
            .show()
    }))
    showIconListDialog(
        "Bit • Lv ${s.level}",
        rows,
        message = "${s.message}\nXP ${s.xp}/${s.xpToNext}  ·  Bond ${s.bond}",
        negative = "Tutup"
    )
}


internal fun MainActivity.setupBitChatTopFace() {
    bitChatTopFace?.let { existing ->
        if (existing.parent != null) return
    }
    val top = topBar
    val view = LottieAnimationView(this).apply {
        setBitAsset("bit/bit_idle.json")
        repeatMode = LottieDrawable.RESTART
        repeatCount = LottieDrawable.INFINITE
        setMinAndMaxFrame(0, 108)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        isClickable = false
        isFocusable = false
        contentDescription = "Bit status"
    }
    top.addView(view, LinearLayout.LayoutParams(dp(46), dp(46)).apply {
        leftMargin = dp(2)
        rightMargin = dp(2)
    })
    bitChatTopFace = view
    applyBitFaceTheme(view)
    bitAnim?.attach(view)
    view.setOnClickListener { bitAnim?.onTap(view) }
}

internal fun MainActivity.setupBitChatInput() {
    editorBottomBar.removeAllViews()
    editorBottomBar.visibility = View.VISIBLE
    editorBottomBar.background = if (isDarkTheme) bg(panel, 0) else bg(Color.WHITE, 0)
    // Keep a small breathing space above the IME so the composer never looks
    // glued to the keyboard edge. This margin is preserved while the IME resizes
    // the window.
    editorBottomBar.setPadding(dp(8), dp(6), dp(8), dp(6))
    editorBottomBar.orientation = LinearLayout.VERTICAL
    editorBottomBar.layoutParams = (editorBottomBar.layoutParams as? LinearLayout.LayoutParams
        ?: LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)).apply {
        height = ViewGroup.LayoutParams.WRAP_CONTENT
        bottomMargin = dp(8)
    }

    val hasAttachment = bitAttachmentUri != null
    val attachmentRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = if (hasAttachment) View.VISIBLE else View.GONE
        setPadding(dp(10), dp(7), dp(6), dp(7))
        background = rippleBg(if (isDarkTheme) panel2 else Color.rgb(246,248,250), 16)
    }

    val fileIcon = MdiIconView(this).apply {
        setIconName(when {
            bitAttachmentMime.startsWith("image/") -> "file-outline"
            bitAttachmentMime.startsWith("text/") || bitAttachmentName.endsWith(".json", true) || bitAttachmentName.endsWith(".xml", true) -> "file-document-outline"
            bitAttachmentName.endsWith(".zip", true) || bitAttachmentName.endsWith(".apk", true) -> "folder-zip-outline"
            else -> "file-outline"
        })
        setIconSize(21f)
        setTextColor(if (isDarkTheme) Color.WHITE else Color.rgb(35,43,52))
        background = rippleBg(if (isDarkTheme) Color.rgb(63,65,70) else Color.WHITE, 12)
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(9) }
    }

    val attachmentText = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
    }
    val attachmentLabel = TextView(this).apply {
        text = bitAttachmentName.ifBlank { "Lampiran" }
        textSize = 12.5f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
    }
    val attachmentMeta = TextView(this).apply {
        val size = runCatching {
            contentResolver.query(bitAttachmentUri!!, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
        text = if (size >= 0) "${formatBitFileSize(size)} • siap dikirim" else "File dipilih • siap dikirim"
        textSize = 10.5f
        setTextColor(textMuted)
        maxLines = 1
    }
    attachmentText.addView(attachmentLabel)
    attachmentText.addView(attachmentMeta)

    val removeAttachment = MdiIconView(this).apply {
        setIconName("close")
        setIconSize(18f)
        setTextColor(textMuted)
        contentDescription = "Hapus lampiran"
        background = rippleBg(if (isDarkTheme) Color.rgb(63,65,70) else Color.WHITE, 16)
        setOnClickListener {
            bitAttachmentUri = null
            bitAttachmentName = ""
            bitAttachmentMime = ""
            setupBitChatInput()
        }
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(6) }
    }
    attachmentRow.addView(fileIcon)
    attachmentRow.addView(attachmentText)
    attachmentRow.addView(removeAttachment)
    editorBottomBar.addView(attachmentRow, LinearLayout.LayoutParams(-1, dp(56)).apply {
        bottomMargin = dp(6)
    })

    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val plus = MdiIconView(this).apply {
        setIconName("plus")
        setIconSize(21f)
        gravity = Gravity.CENTER
        setTextColor(if (isDarkTheme) Color.WHITE else Color.rgb(30, 40, 50))
        background = rippleBg(if (isDarkTheme) Color.rgb(55,55,60) else Color.rgb(235,238,242), 22)
        contentDescription = "Tambah lampiran"
        isClickable = true
        isFocusable = true
        setOnClickListener { showBitAttachmentMenu(this) }
    }

    val input = EditText(this).apply {
        hint = if (hasAttachment) "Tulis pesan tentang file ini..." else "Tulis pertanyaan..."
        textSize = 14f
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        setTextColor(textMain)
        setHintTextColor(textMuted)
        // Static fill: focus/press must not change the input color. The field
        // remains the same color before and after tapping it.
        background = bg(if (isDarkTheme) panel2 else Color.WHITE, 22)
        setPadding(dp(16), 0, dp(16), 0)
    }
    bitChatInput = input

    val send = MdiIconView(this).apply {
        setIconName("send")
        setIconSize(20f)
        gravity = Gravity.CENTER
        setTextColor(if (isDarkTheme) Color.WHITE else Color.rgb(30, 40, 50))
        background = rippleBg(if (isDarkTheme) Color.rgb(55,55,60) else Color.rgb(235,238,242), 22)
        contentDescription = "Kirim pertanyaan"
        isClickable = true
        isFocusable = true
    }

    row.addView(plus, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(6) })
    row.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
    row.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)))
    editorBottomBar.addView(row)

    // Keep the newest message and composer visible when the IME opens. The composer
    // itself is measured as WRAP_CONTENT, so adding an attachment never clips the input.
    input.setOnFocusChangeListener { _, focused ->
        if (focused) scroll.postDelayed({ scroll.fullScroll(View.FOCUS_DOWN) }, 120L)
    }

    fun submit() {
        val q = input.text.toString().trim()
        if (q.isEmpty() && bitAttachmentUri == null) return

        BitRuntimeContext.onCommand(q.ifBlank { "Lampiran: $bitAttachmentName" })
        input.setText("")

        if (bitAttachmentUri != null && q.contains("github", true) &&
            (q.contains("upload", true) || q.contains("unggah", true) || q.contains("kirim", true))) {
            val uri = bitAttachmentUri!!
            val name = bitAttachmentName.ifBlank { "upload.bin" }
            bitChatMessages.add(BitChatMessage(q.ifBlank { "Upload $name ke GitHub" }, true))
            addBitMessageView(bitChatMessages.last())
            bitPrepareGithubBotUpload(uri, name)
            return
        }

        if (q.isBlank() && bitAttachmentUri != null) {
            bitChatMessages.add(BitChatMessage("Lampiran: $bitAttachmentName", true))
            addBitMessageView(bitChatMessages.last())
            bitChatMessages.add(BitChatMessage("File sudah dipilih. Kamu bisa memberi perintah, misalnya: \"upload file ini ke GitHub\".", false))
            addBitMessageView(bitChatMessages.last())
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            return
        }

        bitChatMessages.add(BitChatMessage(q, true))
        addBitMessageView(bitChatMessages.last())
        val result = answerBitLocally(q)
        bitChatMessages.add(BitChatMessage(result.text, false, result.actions))
        addBitMessageView(bitChatMessages.last())
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        if (result.autoOpen && result.openToolId != null) {
            val toolId = result.openToolId
            input.postDelayed({
                if (!isFinishing && toolId != null) {
                    val action = BitActionBridge.forTool(toolId).copy(label = "Buka ${homeToolMap[toolId] ?: toolId}")
                    BitActionBridge.execute(this, action)
                }
            }, 180L)
        }

    }

    send.setOnClickListener { submit() }
    input.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
            submit()
            true
        } else false
    }
}

private fun formatBitFileSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    if (bytes < 1024L * 1024L) return "%.1f KB".format(java.util.Locale.US, bytes / 1024.0)
    if (bytes < 1024L * 1024L * 1024L) return "%.1f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))
    return "%.1f GB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
}

internal fun MainActivity.showBitAttachmentMenu(anchor: View) {
    val popup = PopupMenu(this, anchor)
    popup.menu.add(0, 1, 0, "Upload file")
    popup.menu.add(0, 2, 1, "Upload foto")
    popup.menu.add(0, 3, 2, "Upload text / dokumen")
    popup.setOnMenuItemClickListener {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when (it.itemId) {
                2 -> "image/*"
                3 -> "text/*"
                else -> "*/*"
            }
            if (it.itemId == 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("*/*"))
            } else if (it.itemId == 3) {
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/*", "application/json", "application/xml"))
            }
        }
        startActivityForResult(intent, BIT_ATTACHMENT_PICK_REQUEST)
        true
    }
    popup.show()
}

internal fun MainActivity.bitPrepareGithubBotUpload(uri: Uri, displayName: String) {
    // Bot sengaja memeriksa nilai yang benar-benar pernah disimpan pengguna.
    // Default UI seperti "username183728" / "B1" tidak dianggap sebagai credential pengguna.
    val owner = prefs.getString("gh_user", "").orEmpty().trim()
    val repo = prefs.getString("gh_repo", "").orEmpty().trim()
    val token = prefs.getString("gh_token_enc", null)?.let { GithubTokenVault.decrypt(it) }.orEmpty().trim()
    val missing = buildList {
        if (owner.isBlank()) add("nama akun GitHub")
        if (repo.isBlank()) add("nama repository")
        if (token.isBlank()) add("token GitHub")
    }

    if (missing.isEmpty()) {
        // Semua data GitHub sudah lengkap. Jangan upload diam-diam: Bot wajib
        // meminta konfirmasi eksplisit sebelum membuat perubahan di repository.
        val cleanName = displayName.trim('/').ifBlank { "upload.bin" }
        ghUserValue = owner
        ghRepoValue = repo
        ghTokenValue = token
        ghBranch = prefs.getString("gh_branch", null).orEmpty().ifBlank { "main" }
        bitPendingGithubUploadUri = uri
        bitPendingGithubUploadName = cleanName
        bitGithubUploadNeedsManualPublisher = false

        bitChatMessages.add(BitChatMessage(
            "Semua data GitHub sudah lengkap. Aku siap meng-upload `$cleanName` ke `$owner/$repo` pada branch `$ghBranch`.\n\nToken: sudah diisi (nilai token tidak ditampilkan).\n\nLanjutkan upload ke repository sekarang?",
            false,
            listOf(
                BitActionBridge.Action("GITHUB_CONFIRM_UPLOAD", "githubzip", "Lanjutkan"),
                BitActionBridge.Action("GITHUB_EDIT_UPLOAD", "githubzip", "Rubah")
            )
        ))
        addBitMessageView(bitChatMessages.last())
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return
    }

    // Simpan lampiran sebagai pending supaya tidak hilang ketika Publisher dibuka.
    bitPendingGithubUploadUri = uri
    bitPendingGithubUploadName = displayName
    bitGithubUploadNeedsManualPublisher = false
    bitChatMessages.add(BitChatMessage(
        "Kamu belum mengisi ${missing.joinToString(", ")}. Ketik **isi** untuk melengkapi data GitHub. Aku akan membuka GitHub Publisher dulu, lalu file ini bisa diteruskan ke GitHub.",
        false,
        listOf(BitActionBridge.Action("OPEN_TOOL", "githubzip", "Isi data GitHub"))
    ))
    addBitMessageView(bitChatMessages.last())
    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    window.decorView.postDelayed({
        if (!isFinishing && !isDestroyed) openTool("githubzip")
    }, 250L)
}

internal fun MainActivity.bitConfirmGithubBotUpload() {
    val uri = bitPendingGithubUploadUri ?: return
    val name = bitPendingGithubUploadName.ifBlank { "upload.bin" }
    val owner = prefs.getString("gh_user", "").orEmpty().trim()
    val repo = prefs.getString("gh_repo", "").orEmpty().trim()
    val token = prefs.getString("gh_token_enc", null)?.let { GithubTokenVault.decrypt(it) }.orEmpty().trim()
    if (owner.isBlank() || repo.isBlank() || token.isBlank()) {
        bitPrepareGithubBotUpload(uri, name)
        return
    }
    ghUserValue = owner
    ghRepoValue = repo
    ghTokenValue = token
    ghBranch = prefs.getString("gh_branch", null).orEmpty().ifBlank { "main" }
    bitPendingGithubUploadUri = null
    bitPendingGithubUploadName = ""
    bitGithubUploadNeedsManualPublisher = false
    bitChatMessages.add(BitChatMessage("Lanjutkan dipilih. Aku mulai upload `$name` ke `$owner/$repo`…", false))
    addBitMessageView(bitChatMessages.last())
    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    bitUploadAttachmentToGithub(uri, name)
}

internal fun MainActivity.bitEditGithubBotUpload() {
    val uri = bitPendingGithubUploadUri ?: return
    val name = bitPendingGithubUploadName.ifBlank { "upload.bin" }
    bitGithubUploadNeedsManualPublisher = true
    bitChatMessages.add(BitChatMessage("Membuka GitHub Publisher. Kamu bisa mengubah akun, repository, branch, opsi upload, atau pengaturan lainnya sebelum meng-upload `$name`.", false))
    addBitMessageView(bitChatMessages.last())
    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    window.decorView.postDelayed({
        if (!isFinishing && !isDestroyed) openTool("githubzip")
    }, 180L)
}

internal fun MainActivity.showBitGithubUploadDialog(uri: Uri, displayName: String) {
    // Dipertahankan untuk pemanggilan lama; Bot baru menggunakan bitPrepareGithubBotUpload().
    bitPrepareGithubBotUpload(uri, displayName)
}

internal fun MainActivity.bitRetryPendingGithubUploadIfReady(tokenOverride: String? = null) {
    val uri = bitPendingGithubUploadUri ?: return
    val owner = prefs.getString("gh_user", "").orEmpty().trim()
    val repo = prefs.getString("gh_repo", "").orEmpty().trim()
    val token = tokenOverride.orEmpty().trim().ifBlank {
        prefs.getString("gh_token_enc", null)?.let { GithubTokenVault.decrypt(it) }.orEmpty().trim()
    }
    if (owner.isBlank() || repo.isBlank() || token.isBlank()) return

    val name = bitPendingGithubUploadName.ifBlank { "upload.bin" }
    bitPendingGithubUploadUri = null
    bitPendingGithubUploadName = ""
    ghUserValue = owner
    ghRepoValue = repo
    ghTokenValue = token
    ghBranch = prefs.getString("gh_branch", null).orEmpty().ifBlank { "main" }
    bitChatMessages.add(BitChatMessage("Data GitHub sudah lengkap. Aku lanjutkan upload `$name` ke `$owner/$repo`…", false))
    addBitMessageView(bitChatMessages.last())
    bitUploadAttachmentToGithub(uri, name)
}

internal fun MainActivity.bitUploadAttachmentToGithub(uri: Uri, path: String) {
    val token = ghTokenValue.ifBlank {
        prefs.getString("gh_token_enc", null)?.let { GithubTokenVault.decrypt(it) }.orEmpty()
    }
    if (ghUserValue.isBlank() || ghRepoValue.isBlank() || token.isBlank()) {
        bitPrepareGithubBotUpload(uri, path)
        return
    }

    val face = bitChatTopFace
    runCatching { bitAnim?.playIdleAll() }
    bitChatMessages.add(BitChatMessage("Sedang meng-upload `$path` ke ${ghUserValue}/${ghRepoValue}…", false))
    addBitMessageView(bitChatMessages.last())
    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    toolThread {
        val result = runCatching { ghUploadSingleFileToGithub(uri, path, token) }
        runOnToolUiThread {
            if (result.isSuccess) {
                bitChatMessages.add(BitChatMessage("Upload berhasil. `$path` sudah dikirim ke GitHub.", false))
            } else {
                val error = result.exceptionOrNull()
                val safe = ghDeleteError(error ?: IOException("Upload gagal"))
                bitChatMessages.add(BitChatMessage("Upload GitHub gagal:\n$safe", false))
            }
            addBitMessageView(bitChatMessages.last())
            bitAttachmentUri = null
            bitAttachmentName = ""
            bitAttachmentMime = ""
            runCatching { bitAnim?.playIdleAll() }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            setupBitChatInput()
        }
    }
}

internal fun MainActivity.answerBitLocally(question: String): BitAnswer {
        val q = question.trim()
        if (q.isBlank()) return BitAnswer("Tulis pertanyaan atau perintah dulu.")

        val lower = q.lowercase(Locale.ROOT)

        // Step 12: jawab pertanyaan status berdasarkan konteks proses real-time.
        val asksProcessStatus = listOf("status", "proses", "berapa persen", "sampai mana", "sedang apa", "progress", "gimana proses", "kenapa gagal")
            .any { lower.contains(it) }
        if (asksProcessStatus) {
            return BitAnswer("${BitRuntimeContext.processSummary()}. Aku hanya melihat status proses yang aman; token dan credential tidak tersedia untukku.")
        }

        // Step 7: gunakan konteks runtime non-rahasia untuk memahami "yang tadi",
        // "buka lagi", "coba lagi", dan perintah lanjutan lainnya.
        val contextual = listOf("yang ini", "yang tadi", "buka lagi", "jalankan lagi", "coba lagi", "ulang", "lanjutkan")
            .any { lower.contains(it) }
        if (contextual) {
            val lastId = BitRuntimeContext.lastToolId
            if (lastId != null) {
                val lastName = homeToolMap[lastId] ?: BitRuntimeContext.lastToolName ?: lastId
                val status = BitRuntimeContext.lastStatus
                val result = BitRuntimeContext.lastResultSummary
                val detail = when {
                    status == "error" -> " Status terakhir tercatat gagal${result?.let { ": $it" } ?: "."}"
                    !result.isNullOrBlank() -> " Status terakhir: $status. $result"
                    !status.isNullOrBlank() -> " Status terakhir: $status."
                    else -> ""
                }
                return BitAnswer("Aku melanjutkan tool terakhir: $lastName.$detail",  lastId)
            }
        }

        // Step 19: diagnosis & perbaikan error kode di Text Editor (lokal, tanpa kirim kode).
        val asksEditorDiagnosis = listOf(
            "cek error", "cek kode", "periksa kode", "periksa error", "analisis kode", "analisa kode",
            "cari error", "error di editor", "error editor", "syntax error", "sintaks error",
            "kode error", "kode saya error", "perbaiki kode", "perbaiki error", "betulkan kode",
            "benerin kode", "auto fix", "autofix", "validasi json", "json error", "html error",
            "js error", "javascript error"
        ).any { lower.contains(it) }
        if (asksEditorDiagnosis) return bitEditorDiagnosis()

        // Step 6: Bit membaca registry kemampuan APK. Registry hanya berisi ID/nama tool.
        // Credential/token tetap berada di tool masing-masing dan tidak pernah dikirim ke Bit.
        val match = BitToolRegistry.find(q, homeTools)
        if (match != null) {
            val actionText = when (match.id) {
                "githubzip" -> "Aku akan membuka GitHub Publisher. Token GitHub tetap dikelola oleh fitur GitHub dan tidak ditampilkan ke Bit."
                else -> "Aku mengenali permintaan ini sebagai fitur ${match.name}. ${BitToolKnowledge.enrich(match.id, match.name)}"
            }
            // Step 11: Bit hanya mengeluarkan ID aksi. Credential tetap berada di tool.
            val action = BitActionBridge.forTool(match.id)
            val bridgeText = when (action.id) {
                "GITHUB_UPLOAD" -> " Aksi: upload GitHub."
                "ZIP" -> " Aksi: ZIP."
                "SCAN_QR" -> " Aksi: scan QR."
                else -> ""
            }
            val direct = listOf("buka", "open", "jalankan", "gunakan", "pakai", "mulai", "cek", "periksa", "scan", "pindai", "buat", "generate", "hitung", "konversi", "upload", "unggah")
                .any { lower.startsWith(it + " ") || lower == it }
            val safeAction = action.copy(label = "Buka ${match.name}")
            return BitAnswer(actionText + bridgeText,  match.id, listOf(safeAction), direct)
        }

        val error = listOf("error", "eror", "gagal", "tidak bisa", "nggak bisa", "gak bisa", "crash", "force close", "permission", "izin")
            .any { lower.contains(it) }

        return when {
            lower.contains("halo") || lower == "hai" || lower == "hi" ->
                BitAnswer("Halo. Aku Bit. Aku bisa membantu menjalankan fitur yang tersedia di MyTools.")
            error && lower.contains("github") -> {
                BitRuntimeContext.onToolError("Pengguna melaporkan masalah GitHub")
                BitAnswer("Kalau GitHub gagal, coba lagi dari GitHub Publisher. Bit tidak melihat token; fitur GitHub yang menangani credential.",  if (BitRuntimeContext.lastToolId == "githubzip") "githubzip" else null)
            }
            lower.contains("cara") && (lower.contains("tool") || lower.contains("fitur") || lower.contains("mytools")) ->
                BitAnswer("Sebutkan fitur yang ingin digunakan. Aku akan mencocokkannya dengan tool yang tersedia di APK.")
            lower.contains("apa itu") && (lower.contains("sha") || lower.contains("hash")) ->
                BitAnswer("Hash adalah nilai ringkas yang dihasilkan dari data. SHA-256 sering digunakan untuk memeriksa integritas file.")
            else ->
                BitAnswer("Aku belum menemukan jawaban lokal yang cocok. Coba sebutkan nama tool atau jelaskan tujuanmu, misalnya: cari file, scan Wi-Fi, hitung risiko, atau periksa JSON.")
        }
    }
internal fun MainActivity.bitEditorDiagnosis(): BitAnswer {
        val box = editorBox
            ?: return BitAnswer("Belum ada file terbuka di Text Editor. Buka file .js, .html, .css, atau .json dulu, lalu minta aku memeriksanya.",  "editor")
        val mode = editorMode
        if (!CodeDiagnostics.supports(mode)) {
            return BitAnswer("Editor sedang membuka file ${editorModeName(mode)}. Pemeriksaan error otomatis tersedia untuk JS, HTML, CSS, dan JSON.")
        }
        val issues = EditorDiagnostics.analyzeNow(box.text.toString(), mode)
        val errors = issues.count { it.severity == CodeDiagnostics.Severity.ERROR }
        val warns = issues.size - errors
        BitRuntimeContext.onToolResult(
            if (issues.isEmpty()) "ok" else "diagnosed",
            "Editor ${editorModeName(mode)}: $errors error, $warns peringatan"
        )
        if (issues.isEmpty()) {
            return BitAnswer("Aku memeriksa file ${editorModeName(mode)} di editor dan tidak menemukan error struktur. Ini pemeriksaan dasar (kurung, kutip, tag, koma), bukan pengganti menjalankan kodenya.")
        }
        val top = issues.take(5).joinToString("\n") { "• Baris ${it.line}: ${it.message}" }
        val more = if (issues.size > 5) "\n…dan ${issues.size - 5} lainnya." else ""
        val fixable = issues.count { it.fix != null }
        val fixNote = if (fixable > 0) "\n$fixable bisa diperbaiki otomatis. Kamu akan melihat pratinjau dulu sebelum perubahan diterapkan." else ""
        val actions = mutableListOf(BitActionBridge.editorDiagnose())
        if (fixable > 0) actions.add(BitActionBridge.editorFixPreview())
        return BitAnswer(
            "Aku menemukan $errors error dan $warns peringatan di file ${editorModeName(mode)}:\n$top$more$fixNote",
            null, actions
        )
    }
internal fun MainActivity.addBitMessage(text: String, fromUser: Boolean) {
        val message = BitChatMessage(text, fromUser)
        bitChatMessages.add(message)
        addBitMessageView(message)
    }
internal fun MainActivity.addBitMessageView(message: BitChatMessage) {
    val activity = this
    val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (message.fromUser) Gravity.END else Gravity.START
            setPadding(0, dp(3), 0, dp(3))
        }
        val bubble = TextView(this).apply {
            text = message.text
            textSize = 14f
            setTextColor(if (isDarkTheme) Color.WHITE else Color.rgb(35, 42, 48))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = bg(
                if (message.fromUser) {
                    if (isDarkTheme) Color.rgb(54, 54, 58) else Color.rgb(232, 234, 237)
                } else {
                    if (isDarkTheme) Color.rgb(42, 42, 46) else Color.rgb(245, 247, 249)
                }, 18
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).roundToInt()
        }
        val bubbleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bubbleColumn.addView(bubble, LinearLayout.LayoutParams(-2, -2))
        if (message.actions.isNotEmpty()) {
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            message.actions.forEach { act ->
                chips.addView(TextView(this).apply {
                    text = act.label ?: act.id
                    textSize = 12f
                    setTextColor(textMain)
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    background = bg(if (isDarkTheme) Color.rgb(58, 58, 63) else Color.rgb(238, 240, 243), 14)
                    isClickable = true
                    setOnClickListener { BitActionBridge.execute(activity, act) }
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6); topMargin = dp(6) })
            }
            bubbleColumn.addView(chips, LinearLayout.LayoutParams(-2, -2))
        }
        row.addView(bubbleColumn, LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = if (message.fromUser) dp(52) else 0
            rightMargin = if (message.fromUser) 0 else dp(52)
        })
        content.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
internal fun MainActivity.openBitGoogleSearch(question: String) {
        val encoded = java.net.URLEncoder.encode(question, StandardCharsets.UTF_8.name())
        val uri = Uri.parse("https://www.google.com/search?q=$encoded")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(this, "Tidak bisa membuka Google", Toast.LENGTH_SHORT).show() }
    }
internal fun MainActivity.applyBitFaceTheme(view: LottieAnimationView) {
        // Strict monochrome Bit: Light theme = black body / white eyes;
        // Dark theme = white body / black eyes. Keep the palette independent
        // from the rest of the UI so the assistant remains legible in either theme.
        val body = if (isDarkTheme) Color.WHITE else Color.BLACK
        val bodyEdge = body
        val eyes = if (isDarkTheme) Color.BLACK else Color.WHITE
        val zzz = body
        view.addValueCallback(KeyPath("BODY", "**"), LottieProperty.COLOR, LottieValueCallback(body))
        view.addValueCallback(KeyPath("BODY", "**"), LottieProperty.STROKE_COLOR, LottieValueCallback(bodyEdge))
        view.addValueCallback(KeyPath("EYES", "**"), LottieProperty.COLOR, LottieValueCallback(eyes))
        view.addValueCallback(KeyPath("EYES", "**"), LottieProperty.STROKE_COLOR, LottieValueCallback(eyes))
        view.addValueCallback(KeyPath("ZZ", "**"), LottieProperty.COLOR, LottieValueCallback(zzz))
        view.addValueCallback(KeyPath("ZZ", "**"), LottieProperty.STROKE_COLOR, LottieValueCallback(zzz))
    }
internal fun MainActivity.applyBitFaceTheme() {
        bitFace?.let { applyBitFaceTheme(it) }
    }
internal fun MainActivity.playBitThinking() {
    bitAnim?.playThinking(bitFace)
}

internal fun MainActivity.startBitAutonomousLoop() {
    bitAnim?.playIdleAll()
}
