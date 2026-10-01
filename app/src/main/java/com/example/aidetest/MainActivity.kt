package com.example.aidetest

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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

class MainActivity : Activity() {

    internal lateinit var content: LinearLayout

    // --- File tools state (Batch 2) ---
    internal var fileSortMode = 0
    internal var fileFilterText = ""

    internal data class ConvCategory(
        val id: String,
        val icon: String,
        val title: String,
        val hint: String,
        val desc: String,
        val formDesc: String,
        val formats: List<String>,
        val mime: String
    )

    internal val convCategories = listOf(
        ConvCategory(
            "arsip", "◫", "Arsip & Kompresi", "ZIP, RAR, 7Z, TAR, dll.",
            "Kompresi dan ekstrak file arsip dengan mudah.",
            "Kompresi dan ekstrak file arsip dengan mudah.",
            listOf("RAR", "7Z", "TAR", "GZ", "ISO", "Folder Normal (Extract)"), "*/*"
        ),
        ConvCategory(
            "dokumen", "▤", "Dokumen & Teks", "DOCX, PDF, XLSX, dll.",
            "Word, PDF, Excel, TXT, dll.",
            "Konversi dokumen, lembar kerja, presentasi, dan e-book.",
            listOf("PDF", "DOCX", "XLSX", "PPTX", "TXT", "RTF"), "*/*"
        ),
        ConvCategory(
            "gambar", "▧", "Gambar & Desain", "PNG, JPG, SVG, PSD, dll.",
            "PNG, JPG, JPEG, WEBP, BMP, GIF, HEIC, dll.",
            "Konversi gambar langsung di HP ke PNG, JPG, WEBP, atau BMP.",
            listOf("PNG", "JPG", "WEBP", "BMP"), "image/*"
        ),
        ConvCategory(
            "audio", "♪", "Audio & Musik", "MP3, WAV, FLAC, dll.",
            "MP3, WAV, FLAC, dll.",
            "Konversi antar format audio serta ekstrak kualitas.",
            listOf("MP3", "WAV", "OGG", "M4A"), "audio/*"
        ),
        ConvCategory(
            "video", "▶", "Video", "MP4, MKV, AVI, GIF, dll.",
            "MP4, MKV, AVI, GIF, dll.",
            "Konversi format video atau ekstrak audio.",
            listOf("MP4", "MKV", "AVI", "WEBM"), "video/*"
        )
    )

    internal var convCategory: String? = null
    internal var convStage = "form" // "form" | "pickfile" | "progress" | "done"
    internal var convToExpanded = false
    internal var convPickedUri: Uri? = null
    internal var convPickedName: String? = null
    internal var convFromFormat = "Otomatis terdeteksi"
    internal var convToFormat: String? = null
    internal var convStepIndex = 0
    internal var convResultUri: Uri? = null
    internal var convResultName: String? = null
    internal var convResultSizeText: String? = null

    internal lateinit var scroll: ScrollView
    internal lateinit var title: TextView
    internal lateinit var subtitle: TextView
    internal lateinit var back: TextView
    internal lateinit var action: TextView
    private lateinit var homeMenu: ImageButton
    private lateinit var homeSearch: ImageButton
    private lateinit var homeProfile: ImageButton
    private lateinit var search: EditText
    private lateinit var searchFill: View
    internal var searchOpen = false
    // Search bar "Snap / Enter Always": a small scroll gesture is enough to hide/show it.
    internal var searchSnapEnabled = false          // halaman aktif memang punya search bar
    internal var searchSnapHidden = false           // sedang disembunyikan oleh snap
    internal var searchSnapFraction = 1f            // 1 = tampil penuh, 0 = tertutup
    internal var searchSnapAnimator: android.animation.ValueAnimator? = null
    internal var searchSnapTouchY = 0f
    internal var searchSnapTracking = false
    internal var searchSnapGesture = 0f
    internal var searchSnapGestureTriggered = false
    internal var lastToolOpenId: String? = null
    internal var lastToolOpenAt = 0L
    private lateinit var bottomNav: LinearLayout
    private lateinit var navFavorite: View
    private lateinit var loginScreen: LinearLayout
    private lateinit var mainContainer: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var rootFrame: FrameLayout

    // Loading proses panjang: muncul otomatis hanya jika background task > 280 ms.
    // Tidak mengunci UI; pengguna tetap bisa membatalkan/navigasi bila task memang mengizinkan.
    internal val activeToolTasks = AtomicInteger(0)
    internal val toolLoadingHandler = Handler(Looper.getMainLooper())
    internal var toolLoadingOverlay: FrameLayout? = null
    internal var toolLoadingSpinner: ProgressBar? = null
    internal var toolLoadingText: TextView? = null

    internal fun ensureToolLoadingOverlay() {
        if (toolLoadingOverlay != null || !::rootFrame.isInitialized) return
        val overlay = FrameLayout(this).apply {
            setPadding(dp(14), dp(8), dp(16), dp(8))
            elevation = dp(10).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(if (isDarkTheme) 0xEE202124.toInt() else 0xEEFFFFFF.toInt())
            }
        }
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24)).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dp(2)
            }
        }
        val label = TextView(this).apply {
            text = "Memproses…"
            textSize = 13f
            setTextColor(if (isDarkTheme) Color.WHITE else Color.BLACK)
            maxLines = 1
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(24)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dp(36)
                rightMargin = dp(2)
            }
        }
        overlay.addView(spinner)
        overlay.addView(label)
        overlay.visibility = View.GONE
        rootFrame.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, dp(40)
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(72)
        })
        toolLoadingOverlay = overlay
        toolLoadingSpinner = spinner
        toolLoadingText = label
    }

    internal fun showToolLoading() {
        if (isFinishing) return
        ensureToolLoadingOverlay()
        val overlay = toolLoadingOverlay ?: return
        // Batalkan fade-out yang masih berjalan agar tidak menyembunyikan overlay yang baru muncul.
        overlay.animate().cancel()
        overlay.visibility = View.VISIBLE
        overlay.alpha = 1f
        toolLoadingSpinner?.let { Motion.startLoading(it) }
    }

    internal fun hideToolLoading() {
        val overlay = toolLoadingOverlay ?: return
        if (overlay.visibility != View.VISIBLE) return
        toolLoadingSpinner?.let { Motion.stopLoading(it) }
        overlay.animate().cancel()
        overlay.animate().alpha(0f).setDuration(120L).withEndAction {
            // Jangan sembunyikan bila ada task baru yang sudah menampilkan loading lagi.
            if (activeToolTasks.get() <= 0) {
                overlay.visibility = View.GONE
                overlay.alpha = 1f
            }
        }.start()
    }

    /**
     * Semua pekerjaan background memakai helper ini. Loading baru muncul setelah 280 ms,
     * sehingga operasi cepat tidak menampilkan "flash" spinner.
     */
    internal fun toolThread(label: String = "Memproses…", block: () -> Unit) {
        activeToolTasks.incrementAndGet()
        val showRunnable = Runnable {
            if (activeToolTasks.get() > 0 && !isFinishing) {
                ensureToolLoadingOverlay()
                toolLoadingText?.text = label
                showToolLoading()
            }
        }
        toolLoadingHandler.postDelayed(showRunnable, 280L)
        // Shared bounded pool — avoids creating unlimited threads under load.
        ToolPerformance.toolExecutor.execute {
            try {
                block()
            } finally {
                toolLoadingHandler.removeCallbacks(showRunnable)
                runOnUiThread {
                    if (activeToolTasks.decrementAndGet() <= 0) {
                        activeToolTasks.set(0)
                        hideToolLoading()
                    }
                }
            }
        }
    }
    internal var drawerOverlay: FrameLayout? = null
    internal var drawerOpen = false
    // Saat keyboard/IME terbuka, bottom navigation disembunyikan agar tidak
    // ikut naik dan menempel di atas keyboard. Setelah keyboard ditutup, nav
    // kembali ke posisi bawah seperti semula.
    internal var imeVisible = false
    internal var imeBottomInset = 0
    internal var sysTopInset = 0
    internal var sysBottomInset = 0
    internal val EDGE_FLAGS = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

    internal var currentPage = "home"

    // Horizontal root-page navigation: Home <-> Tools <-> Favorit <-> Pengaturan.
    // Vertical swipes remain owned by the ScrollView; only a clear horizontal
    // gesture changes the root page.
    internal var rootSwipeDownX = 0f
    internal var rootSwipeDownY = 0f
    internal var rootSwipeTracking = false
    internal var rootSwipeBlocked = false
    internal var rootSwipePageAtDown = "home"
    internal var rootSwipeHandled = false
    internal val rootPageOrder = listOf("home", "all", "favorites", "settings")

    internal var modernUiHandler = Handler(Looper.getMainLooper())
    internal var modernUiRunnable: Runnable? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Let Android/ScrollView handle the gesture normally first. We only
        // observe it and schedule a root-page change after ACTION_UP, so a
        // horizontal swipe never breaks ordinary vertical scrolling/clicks.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rootSwipeDownX = event.rawX
                rootSwipeDownY = event.rawY
                rootSwipeTracking = currentPage in rootPageOrder && !imeVisible
                rootSwipeBlocked = !rootSwipeTracking ||
                        isTouchInsideNonPagingArea(event.rawX, event.rawY)
                rootSwipePageAtDown = currentPage
                rootSwipeHandled = false
            }
            MotionEvent.ACTION_UP -> {
                if (rootSwipeTracking && !rootSwipeBlocked && !rootSwipeHandled) {
                    val dx = event.rawX - rootSwipeDownX
                    val dy = event.rawY - rootSwipeDownY
                    val distance = kotlin.math.abs(dx)
                    val vertical = kotlin.math.abs(dy)
                    if (distance >= dp(72) && distance > vertical * 1.25f) {
                        rootSwipeHandled = true
                        val page = rootSwipePageAtDown
                        window.decorView.post {
                            if (!isFinishing && currentPage == page && currentPage in rootPageOrder) {
                                swipeRootPage(dx < 0f)
                            }
                        }
                    }
                }
                rootSwipeTracking = false
            }
            MotionEvent.ACTION_CANCEL -> {
                rootSwipeTracking = false
                rootSwipeHandled = false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    internal fun isTouchInsideNonPagingArea(rawX: Float, rawY: Float): Boolean {
        // Do not hijack swipes that start on the fixed toolbar/bottom navigation
        // or inside a horizontal filter row (for example the category chips).
        fun contains(v: View): Boolean {
            if (v.visibility != View.VISIBLE) return false
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            return rawX >= loc[0] && rawX <= loc[0] + v.width &&
                    rawY >= loc[1] && rawY <= loc[1] + v.height
        }

        if (contains(topBar) || contains(bottomNav)) return true

        fun hasHorizontalScroller(v: View): Boolean {
            if (v.visibility != View.VISIBLE) return false
            if (v is HorizontalScrollView && contains(v)) return true
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    if (hasHorizontalScroller(v.getChildAt(i))) return true
                }
            }
            return false
        }
        return hasHorizontalScroller(rootFrame)
    }

    internal fun swipeRootPage(next: Boolean) {
        val index = rootPageOrder.indexOf(currentPage)
        if (index < 0) return
        val targetIndex = if (next) index + 1 else index - 1
        if (targetIndex !in rootPageOrder.indices) {
            // Small edge feedback instead of silently doing nothing.
            if (animationsEnabled()) {
                content.animate().cancel()
                content.animate().translationX(if (next) -dp(10).toFloat() else dp(10).toFloat())
                    .setDuration(70L)
                    .withEndAction {
                        content.animate().translationX(0f).setDuration(120L).start()
                    }.start()
            }
            return
        }

        val target = rootPageOrder[targetIndex]
        val render: () -> Unit = {
            when (target) {
                "home" -> showHome()
                "all" -> showAllTools()
                "favorites" -> showFavorites()
                "settings" -> showSettings()
            }
        }

        // Re-render first, then slide the new page in from the swipe direction.
        // This keeps the existing navigation/state code as the single source of truth.
        navigateRoot(render = render)
        if (!animationsEnabled()) {
            content.translationX = 0f
            content.alpha = 1f
            return
        }
        Motion.pageSlide(content, if (next) 42f else -42f)
    }
    internal var wifiHistory = mutableListOf<Int>()
    internal var batteryHistory = mutableListOf<Int>()

    // Navigation + UI state preservation. Each rendered page is kept as an actual View tree,
    // so EditText contents, selections, toggle states and ScrollView position survive Back.
    internal data class PageSnapshot(
        val name: String,
        val children: MutableList<View>,
        val scrollY: Int,
        val searchText: String,
        val searchVisible: Int,
        val lightweight: Boolean = false
    )
    internal val pageBackStack = ArrayDeque<PageSnapshot>()
    internal var restoringSnapshot = false
    internal var resettingRootNavigation = false
    internal var editorFile: File? = null
    internal var editorMode = "text"
    internal var editorLastSelection = ""
    internal var editorBox: EditText? = null
    internal var editorNameLabel: TextView? = null
    internal var editorStatusLabel: TextView? = null
    internal var editorContextActions: LinearLayout? = null
    private lateinit var editorBottomBar: LinearLayout
    internal var editorLanding = false

    /** Toolbar bawah editor hanya saat workspace edit aktif (bukan landing / preview / halaman lain). */
    internal fun isEditorWorkspace(): Boolean =
        !editorLanding && (currentPage == "Editor" || currentPage.startsWith("Editor - ")) && editorBox != null

    internal fun syncEditorBottomBar() {
        if (!::editorBottomBar.isInitialized) return
        val show = isEditorWorkspace()
        editorBottomBar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            editorBottomBar.alpha = 1f
            editorBottomBar.translationY = 0f
        }
    }
    internal var editorPendingTarget: EditText? = null
    internal var editorExternalTarget: EditText? = null
    internal var editorExternalMode: String? = null
    private lateinit var editorMore: TextView
    // Semua kalkulator dirender dalam satu workspace; perpindahan mode tidak membuka halaman baru.
    internal var embeddedCalculatorRender = false
    internal var calculatorSelectedMode = "basiccalc"
    internal var homeFilter = "Semua"
    internal var server: ServerSocket? = null
    internal var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    internal var hostingStatusView: TextView? = null
    internal var hostingUrlView: TextView? = null
    internal var hostingCredentialsView: TextView? = null
    internal var hostingQrView: ImageView? = null
    internal val HOTSPOT_PERMISSION_REQUEST = 9901
    internal var pendingHostingPort = 8080
    internal var pendingHostingRoot: File? = null
    internal var webImportTarget: EditText? = null
    internal var webBuildReady = false
    /** Buffer log console editor (JS console.log, error, status). */
    internal val editorConsoleLog = StringBuilder()
    internal var editorConsoleView: TextView? = null
    internal var webHostButton: Button? = null
    internal var webBuildStatusView: TextView? = null
    internal var webHostingToken = ""
    internal val WEB_HTML_PICK_REQUEST = 9821
    internal val WEB_CSS_PICK_REQUEST = 9822
    internal val WEB_JS_PICK_REQUEST = 9823
    internal var suppressSearch = false
    // Animasi daftar tool hanya diputar sekali saat sesi aplikasi dimulai.
    // Setelah pengguna masuk ke tool lalu kembali ke Beranda, daftar tetap stabil tanpa replay.
    internal var initialToolAnimationPlayed = false
    internal lateinit var prefs: android.content.SharedPreferences

    internal var isDarkTheme = false
    internal var clipboardManager: android.content.ClipboardManager? = null
    internal var clipboardListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null
    internal var networkScanStop = AtomicBoolean(false)
    internal val COLOR_PICKER_CAPTURE_REQUEST = 7421
    internal val COLOR_PHOTO_PICK_REQUEST = 7422
    internal val COLOR_PHOTO_CAMERA_REQUEST = 7423
    internal val COLOR_PALETTE_EXPORT_REQUEST = 7424
    internal var pendingColorPaletteExportFormat = "json"
    internal var currentPhotoPalette = mutableListOf<Pair<Int, Int>>()
    internal var colorPhotoView: ImageView? = null
    internal var colorPhotoBitmap: Bitmap? = null
    internal var colorPhotoMarker: View? = null
    internal var colorPhotoSelected = Color.rgb(25, 25, 27)
    internal var colorPhotoStatus: TextView? = null
    internal var colorPhotoHex: TextView? = null
    internal var colorPhotoRgb: TextView? = null
    internal var colorPhotoHsl: TextView? = null
    internal var colorPhotoPalette: LinearLayout? = null
    internal var colorPhotoCameraUri: Uri? = null
    internal var colorPhotoSelectionUpdater: ((Int) -> Unit)? = null
    internal var colorPhotoPlaceholder: View? = null
    internal var colorPickerUiUpdater: ((Int) -> Unit)? = null
    internal val colorPickerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ColorPickerService.ACTION_COLOR_PICKED) return
            val color = intent.getIntExtra(ColorPickerService.EXTRA_COLOR, Color.WHITE)
            colorPickerUiUpdater?.invoke(color)
        }
    }


    internal var pendingOtaEndpoint = ""
    internal val FINANCE_EXPORT_CREATE = 2003
    internal val FINANCE_BACKUP_CREATE = 2004
    internal val FINANCE_BACKUP_OPEN = 2005
    internal val GITHUB_ZIP_PICK_REQUEST = 12801
    internal val GITHUB_FOLDER_PICK_REQUEST = 12802
    internal var githubFolderUri: Uri? = null
    internal var githubFolderLabel: TextView? = null
    internal var githubFolderPreview: TextView? = null
    internal var githubZipUri: Uri? = null
    internal var githubZipLabel: TextView? = null
    internal var githubUploadStatus: TextView? = null
    internal var githubZipRoot = ""
    internal val githubZipExcluded = linkedSetOf<String>()
    internal var githubZipPreviewFiles = emptyList<String>()
    internal var githubZipPreviewDirs = emptyList<String>()
    internal var espSensorPolling = false
    internal var espSensorHandler: Handler? = null
    internal var espSensorRunnable: Runnable? = null
    internal var nsdDiscoveryManager: NsdManager? = null
    internal var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    internal var colorPickerProjectionResultCode = 0
    internal var colorPickerProjectionData: Intent? = null


    internal val homeTools = listOf(
        "workspace" to "Workspace Center", "plugincenter" to "Plugin Center", "filemanager" to "File Manager", "recentfiles" to "Recent Files", "backuprestore" to "Backup / Restore", "editor" to "Editor", "reminder" to "Notifikasi", "zip" to "ZIP / UNZIP", "githubzip" to "GitHub Publisher",
        "json" to "JSON Tools", "hash" to "Hash Generator",
        "base64" to "Base64", "url" to "URL Tools", "regex" to "Regex Tester",
        "uuid" to "UUID Generator", "color" to "Color Tools", "number" to "Kalkulator Lengkap",
        "textstat" to "Statistik Teks", "case" to "Case Converter", "dedupe" to "Hapus Duplikat",
        "compare" to "Bandingkan Teks", "slug" to "Slug Generator", "lorem" to "Lorem Ipsum",
        "password" to "Password Generator", "token" to "Token Acak", "jwt" to "JWT Decoder",
        "hmac" to "HMAC Generator", "totp" to "TOTP Generator", "aes" to "AES Encrypt / Decrypt",
        "random" to "Random Bytes", "checksum" to "Checksum File", "hex" to "Hex Converter",
        "base32" to "Base32", "dns" to "DNS Lookup", "rdns" to "Reverse DNS",
        "port" to "Port Checker", "publicip" to "IP Publik", "ping" to "Ping",
        "ipinfo" to "IP Address Info", "ssl" to "SSL Certificate", "apk" to "APK Inspector",
        "qr" to "QR Scanner", "http" to "HTTP Server", "webhostwifi" to "HTML Hosting Wi-Fi",
        "fileconvert" to "Konversi File",
        "timestamp" to "Timestamp Converter", "unicode" to "Unicode Inspector",
        "urlparser" to "URL Parser", "mime" to "MIME Type Lookup", "jsonformat" to "JSON Formatter",
        "xmlformat" to "XML Formatter", "uuidbatch" to "UUID Batch Generator", "base64file" to "Base64 File Tool",
        "httpheaders" to "HTTP Headers", "textreplace" to "Find & Replace", "wordfreq" to "Word Frequency",
        "devicecenter" to "Device & System", "storage" to "Storage Analyzer", "apps" to "App Manager",
        "network" to "Network Info", "filesearch" to "File Search",
        "pivotcalc" to "Pivot Point", "dividercalc" to "Voltage Divider", "dcacalc" to "Averaging Down & DCA",
        "pwmcalc" to "PWM & Duty Cycle", "spritecalc" to "Sprite Sheet Grid", "installcalc" to "Bunga Flat vs Anuitas",
        "powercalc" to "Konsumsi Listrik", "aspectcalc" to "Aspect Ratio", "pphcalc" to "PPN & PPh Final",
        "financereader" to "Pengelola Keuangan", "financedashboard" to "Finance Dashboard", "securitycenter" to "Security Center", "helpbot" to "HelpBot Offline",
        "filehashcompare" to "File Hash Compare", "markdown" to "Markdown Viewer",
        "sql" to "SQL Tools", "yaml" to "YAML Formatter", "toml" to "TOML Inspector",
        "cron" to "Cron Helper", "passwordstrength" to "Password Strength",
        "fileencryption" to "File Encryption", "steganography" to "Steganography", "passwordanalyzer" to "Password Strength Analyzer", "breachchecker" to "Data Breach Checker", "securenotes" to "Secure Notes", "totpvault" to "2FA Manager (TOTP)", "pgp" to "PGP Encrypt / Decrypt", "sshkeygen" to "SSH Key Generator", "certviewer" to "Certificate Viewer", "virusscanner" to "Virus Scanner", "urlsafety" to "URL Safety Checker",
        "stopwatch" to "Stopwatch", "timer" to "Timer",
        "imagestudio" to "Image Studio",
        "restclient" to "REST / API Client", "websocket" to "WebSocket Client",
        "networkcenter" to "Network Center",
        "apkcompare" to "APK Compare", "duplicatefinder" to "Duplicate Finder",
        "largefilefinder" to "Large File Finder",
        "customtools" to "Tool Customization", "studiocenter" to "Studio Center"
    )

    // Cached indexes: avoid repeated O(n) scans/toMap() while the user scrolls/searches.
    // Registry invariant: one stable ID maps to one tool definition.
    // This prevents duplicate entries from leaking into search/customization even if a
    // future edit accidentally repeats an ID in homeTools.
    internal val homeToolMap by lazy(LazyThreadSafetyMode.NONE) { homeTools.associate { it.first to it.second } }
    internal val homeToolSearchIndex by lazy(LazyThreadSafetyMode.NONE) {
        homeTools.distinctBy { it.first }.map { it.first to it.second.lowercase(Locale.getDefault()) }
    }

    internal var dark = Color.rgb(10, 10, 11)
    internal var panel = Color.rgb(22, 22, 24)
    internal var panel2 = Color.rgb(28, 28, 31)
    internal var textMain = Color.rgb(245, 245, 247)
    internal var textMuted = Color.rgb(155, 155, 160)
    internal var line = Color.rgb(48, 48, 52)

    internal fun enableImmersiveFullscreen() {
        // oldt.py explicitly keeps Android system bars visible.
        // Full layar: konten digambar sampai belakang status bar & gesture bar.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = EDGE_FLAGS or
                (if (Build.VERSION.SDK_INT >= 23) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0)
    }

    override fun onCreate(state: Bundle?) {
        // Splash screen resmi: harus dipasang sebelum super.onCreate().
        runCatching { installSplashScreen() }
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("mytools_prefs", MODE_PRIVATE)
        Motion.refresh(prefs, contentResolver)
        // Startup maintenance must never be able to close the Activity.
        // A malformed old preference/history entry should not turn into a launch crash.
        runCatching { syncToolUpdates() }
            .onFailure { android.util.Log.w("GITLS", "Startup tool-sync skipped", it) }
        // Android 13+ requires an explicit export flag for dynamically registered receivers.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                colorPickerReceiver,
                IntentFilter(ColorPickerService.ACTION_COLOR_PICKED),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(colorPickerReceiver, IntentFilter(ColorPickerService.ACTION_COLOR_PICKED))
        }
        runCatching { sanitizeSensitiveHistory() }
            .onFailure { android.util.Log.w("GITLS", "History cleanup skipped", it) }
        runCatching { applySystemTheme() }
            .onFailure { android.util.Log.w("GITLS", "Theme restore skipped", it) }
        runCatching { enableImmersiveFullscreen() }
            .onFailure { android.util.Log.w("GITLS", "System-bar setup skipped", it) }
        window.decorView.postDelayed({
            runCatching { AppUpdateManager(this).checkForUpdate() }
                .onFailure { android.util.Log.w("GITLS", "Update check skipped", it) }
        }, 900L)

        rootFrame = findViewById(android.R.id.content)
        content = findViewById(R.id.content)
        scroll = findViewById(R.id.scroll)
        // Semua Tools berisi banyak kartu kategori. Gunakan ScrollView (sesuai layout) agar
        // gesture vertikal tetap diteruskan dengan mulus dan tidak mudah tersangkut
        // oleh HorizontalScrollView filter di bagian atas.
        scroll.isNestedScrollingEnabled = true
        scroll.isSmoothScrollingEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        title = findViewById(R.id.tvTitle)
        subtitle = findViewById(R.id.tvSubtitle)
        back = findViewById(R.id.btnBack)
        action = findViewById(R.id.btnAction)
        homeMenu = findViewById(R.id.homeMenu)
        homeSearch = findViewById(R.id.homeSearch)
        homeProfile = findViewById(R.id.homeProfile)
        search = findViewById(R.id.searchBox)
        searchFill = findViewById(R.id.searchFill)
        bottomNav = findViewById(R.id.bottomNav)
        editorBottomBar = findViewById(R.id.editorBottomBar)
        editorMore = findViewById(R.id.editorMore)
        loginScreen = findViewById(R.id.loginScreen)
        mainContainer = findViewById(R.id.mainContainer)
        topBar = findViewById(R.id.topBar)

        // Keyboard-safe bottom navigation: keep the nav anchored below the keyboard
        // instead of letting it float directly above the IME when adjustResize runs.
        ViewCompat.setOnApplyWindowInsetsListener(mainContainer) { _, insets ->
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            sysTopInset = sys.top
            sysBottomInset = sys.bottom
            applyEdgeInsets(if (imeVisible) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0)
            val rootPage = currentPage == "home" || currentPage == "all" ||
                    currentPage == "favorites" || currentPage == "settings"

            // Keep the navigation bar anchored to the app's bottom. When the keyboard
            // opens, adjustResize moves the parent bottom upward; translating the nav
            // by the IME inset pushes it back down behind the keyboard instead of making
            // it float directly above the keyboard.
            imeBottomInset = if (imeVisible) {
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            } else 0
            bottomNav.translationY = 0f
            bottomNav.visibility = if (rootPage && !imeVisible) View.VISIBLE else View.GONE
            insets
        }
        ViewCompat.requestApplyInsets(mainContainer)
        runCatching { applyUiColors() }
            .onFailure { android.util.Log.w("GITLS", "Initial colors skipped", it) }
        runCatching { enterApp() }
            .onFailure { error ->
                android.util.Log.e("GITLS", "Startup UI failed; showing safe fallback", error)
                showStartupFallback(error)
            }
        // Maintenance dijalankan secara defensif agar tidak pernah menggagalkan startup.
        runCatching { scheduleFinanceMaintenance() }
            .onFailure { android.util.Log.w("GITLS", "Finance maintenance skipped", it) }

        // Splash "GitLis": hanya saat peluncuran baru (bukan rotasi/restore), tanpa mengubah alur login/home.
        if (state == null) {
            runCatching {
                val splash = GitlisSplashView(this)
                (findViewById<View>(android.R.id.content) as ViewGroup).addView(
                    splash, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                splash.start { }
            }.onFailure { android.util.Log.w("GITLS", "Splash skipped", it) }
        }

        back.setOnClickListener { navigateBack() }
        action.setOnClickListener { showAbout() }
        // Search must never rebuild the whole page on every keystroke.
        // IME composition on Android can emit several text events per character;
        // debouncing keeps the EditText responsive and lets the keyboard finish its
        // composing transaction before the result list is rendered.
        search.addTextChangedListener(DebouncedSearchWatcher { query ->
            if (!suppressSearch) filterCurrent(query)
        })
        // Keyboard-safe root navigation: the bottom navigation must never become a
        // second toolbar above the keyboard while the user is typing/searching.
        search.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                bottomNav.visibility = View.GONE
            } else {
                search.postDelayed({
                    val rootPage = currentPage == "home" || currentPage == "all" ||
                            currentPage == "favorites" || currentPage == "settings"
                    if (rootPage && !imeVisible) bottomNav.visibility = View.VISIBLE
                }, 120L)
            }
        }
        search.setOnEditorActionListener { _, actionId, event ->
            val submit = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER)
            if (submit) {
                search.clearFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(search.windowToken, 0)
                true
            } else false
        }
        // Header fades with normal scrolling. Snap Search is handled separately
        // from the scroll position so a tiny finger gesture can show/hide it even
        // when the ScrollView is far from the top.
        scroll.setOnScrollChangeListener { _, scrollY, _, _, _ ->
            val progress = (scrollY / dp(220).toFloat()).coerceIn(0f, 1f)
            title.alpha = 1f - (progress * 0.08f)
            subtitle.alpha = 1f - (progress * 0.18f)
            homeMenu.alpha = 1f
            homeSearch.alpha = 1f
            homeProfile.alpha = 1f
        }

        // Snap / Enter Always: read the user's finger movement directly. Do not
        // wait for scrollY to change by a large amount and do not require reaching
        // scrollY == 0. Returning false keeps normal ScrollView scrolling intact.
        findViewById<View>(R.id.navHome).setOnClickListener { navigateRoot("home") { showHome() } }
        findViewById<View>(R.id.navTools).setOnClickListener { navigateRoot("all") { showAllTools() } }
        navFavorite = findViewById(R.id.navFavorite)
        navFavorite.setOnClickListener { navigateRoot("favorites") { showFavorites() } }
        findViewById<View>(R.id.navSettings).setOnClickListener { navigateRoot("settings") { showSettings() } }
        listOf(R.id.navHome, R.id.navTools, R.id.navFavorite, R.id.navSettings).forEach { id ->
            addPressFeedback(findViewById(id))
        }
        homeMenu.setOnClickListener { toggleDrawer() }
        homeSearch.setOnClickListener {
            if (!searchSnapEnabled) return@setOnClickListener
            if (searchOpen) {
                // Tekan lagi: kosongkan pencarian lalu sembunyikan kotak.
                if (search.text.isNotEmpty()) search.setText("")
                setSearchBoxOpen(false)
            } else {
                setSearchBoxOpen(true, focus = true)
            }
        }
        homeProfile.setOnClickListener { showProfile() }
        // Ghost button: tanpa container; ikon scale kecil + shadow tipis turun lalu kembali halus.
        Motion.press(homeMenu, 0.86f, shadowDp = -1f)
        Motion.press(homeSearch, 0.86f, shadowDp = -1f, onRelease = { Motion.icon(homeSearch, Motion.Icon.SEARCH) })
        Motion.press(homeProfile, 0.86f, shadowDp = -1f)
        Motion.press(back, 0.88f)
        Motion.press(action, 0.88f)

        // Login actions: all visible controls now have a real action.
        findViewById<Button>(R.id.btnMasuk).setOnClickListener { enterApp() }
        findViewById<Button>(R.id.btnDaftar).setOnClickListener { showLocalRegistration() }
        findViewById<TextView>(R.id.tvLanjut).setOnClickListener { enterApp() }
        addPressFeedback(homeMenu)
        addPressFeedback(homeSearch)
        addPressFeedback(homeProfile)
        styleTopFabs()
        // Fitur tambahan tidak boleh membuat aplikasi mental ke Home bila ada masalah device/API.
        runCatching { setupDynamicShortcuts() }
        runCatching { MyToolsWidget.update(this) }
        if (intent?.getBooleanExtra("open_finance", false) == true) { enterApp(); financeReaderTool() }
        else if (intent?.getBooleanExtra("open_iot", false) == true) { enterApp(); openTool("espstudio") }
        else if (intent?.getBooleanExtra("open_github_upload", false) == true) { enterApp(); openTool("githubzip") }
        else if (intent?.getBooleanExtra("quick_expense", false) == true) { enterApp(); financeReaderTool(); showAddTxDialog(FinanceDb(this), false) }
        else if (intent?.getBooleanExtra("quick_income", false) == true) { enterApp(); financeReaderTool(); showAddTxDialog(FinanceDb(this), true) }
    }

    internal fun showStartupFallback(error: Throwable) {
        // Last-resort launch screen: if a future UI change breaks showHome(),
        // keep the APK open instead of letting the Activity crash back to launcher.
        runCatching {
            loginScreen.visibility = View.GONE
            mainContainer.visibility = View.VISIBLE
            bottomNav.visibility = View.GONE
            search.visibility = View.GONE
            content.removeAllViews()
            content.setPadding(dp(20), dp(24), dp(20), dp(24))
            content.addView(label("GITLS", 26f, true), LinearLayout.LayoutParams(-1, -2))
            content.addView(subLabel("Aplikasi berhasil dibuka, tetapi halaman utama gagal dimuat.", 14f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            content.addView(subLabel("Coba buka ulang aplikasi. Data lokal tidak dihapus.", 12f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            val retry = Button(this).apply {
                text = "Coba Muat Ulang"
                setOnClickListener {
                    runCatching { enterApp() }
                        .onFailure { android.util.Log.e("GITLS", "Retry startup failed", it) }
                }
            }
            content.addView(retry, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(20) })
        }.onFailure {
            android.util.Log.e("GITLS", "Startup fallback also failed", it)
            Toast.makeText(this, "GITLS gagal memuat tampilan utama.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent?.getBooleanExtra("open_finance", false) == true) {
            enterApp(); financeReaderTool()
        } else if (intent?.getBooleanExtra("open_iot", false) == true) {
            enterApp(); openTool("iotdashboard")
        } else if (intent?.getBooleanExtra("open_github_upload", false) == true) {
            enterApp(); openTool("githubzip")
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val selectedUri = data?.data
        if (resultCode == RESULT_OK && selectedUri != null) {
            when (requestCode) {
                1212 -> {
                    imageStudioResult?.invoke(selectedUri)
                    return
                }
                SECURITY_FILE_PICK -> {
                    securityFileUri = selectedUri
                    securityFileName = queryName(selectedUri) ?: selectedUri.lastPathSegment ?: "file"
                    if (currentPage == "Enkripsi File" || currentPage == "File Encryption") renderFileEncryptionUi()
                    else toast("File dipilih")
                    return
                }
                SECURITY_OPEN_PICK -> {
                    val u = selectedUri
                    val pw = lockedOpenPassword; lockedOpenPassword = null
                    if (pw.isNullOrEmpty()) toast("Password wajib diisi")
                    else openLockedFile(displayNameOf(u), pw) { contentResolver.openInputStream(u) }
                    return
                }
                STEGO_ENCODE_PICK -> { stegoImageUri = selectedUri; toast("Gambar dipilih untuk encode"); return }
                STEGO_DECODE_PICK -> { stegoImageUri = selectedUri; decodeStegoFromUri(selectedUri); return }
                CERT_PICK -> { certFileUri = selectedUri; viewCertificate(selectedUri); return }
                GITHUB_FOLDER_PICK_REQUEST -> {
                    githubFolderUri = selectedUri
                    run {
                        val uri = selectedUri
                        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
                        githubFolderLabel?.text = doc?.name ?: "Folder dipilih"
                        githubFolderPreview?.text = "Memindai isi folder…"
                        toolThread {
                            val names = mutableListOf<String>()
                            fun scan(d: androidx.documentfile.provider.DocumentFile, prefix: String) {
                                d.listFiles().forEach { child ->
                                    val n = child.name ?: return@forEach
                                    if (n == ".git" || n == "__MACOSX" || n == ".DS_Store" || n == "Thumbs.db") return@forEach
                                    val rel = if (prefix.isBlank()) n else "$prefix/$n"
                                    if (child.isDirectory) scan(child, rel) else if (child.isFile) names.add(rel)
                                }
                            }
                            runCatching { if (doc != null) scan(doc, "") }
                            runOnUiThread {
                                val shown = names.take(12).joinToString("\n")
                                githubFolderPreview?.text = "${names.size} file ditemukan" + if (shown.isNotBlank()) "\n$shown" + if (names.size > 12) "\n… dan ${names.size - 12} file lainnya" else "" else "\nFolder kosong atau tidak bisa dibaca"
                                githubUploadStatus?.text = "Folder dipilih. Periksa daftar file, lalu tekan Simpan & Upload."
                            }
                        }
                    }
                    return
                }
                GITHUB_ZIP_PICK_REQUEST -> {
                    val uri = selectedUri
                    githubZipUri = uri
                    // Simpan URI + nama agar pilihan ZIP tidak hilang ketika Activity
                    // direcreate (misalnya setelah file picker menutup atau konfigurasi berubah).
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    val name = queryName(uri)
                        ?: androidx.documentfile.provider.DocumentFile.fromSingleUri(this, uri)?.name
                        ?: uri.lastPathSegment?.substringAfterLast('/')
                        ?: "ZIP dipilih"
                    val zipSize = runCatching {
                        contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                    }.getOrNull() ?: -1L
                    prefs.edit()
                        .putString("gh_selected_zip_uri", uri.toString())
                        .putString("gh_selected_zip_name", name)
                        .apply()

                    githubZipExcluded.clear()
                    githubZipRoot = ""
                    githubZipPreviewFiles = emptyList()
                    githubZipPreviewDirs = emptyList()
                    githubZipLabel?.text = if (zipSize > 0) {
                        "$name • ${ghFormatBytes(zipSize)}"
                    } else name
                    githubUploadStatus?.text = "Menganalisis struktur ZIP..."
                    prepareGithubZipPreview(uri)
                    return
                }
                WEB_HTML_PICK_REQUEST, WEB_CSS_PICK_REQUEST, WEB_JS_PICK_REQUEST -> {
                    val uri = selectedUri
                    val target = webImportTarget
                    if (target == null) {
                        toast("Target editor tidak tersedia")
                        return
                    }
                    toolThread("Membaca file web…") {
                        val result = runCatching {
                            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                                ?: error("File tidak dapat dibaca")
                        }
                        runOnUiThread {
                            result.onSuccess { text ->
                                target.setText(text)
                                webBuildReady = false
                                webBuildStatusView?.text = "BELUM BUILD • File berhasil dimuat, tekan Build untuk validasi"
                                webHostButton?.isEnabled = false
                                webImportTarget = null
                                toast("File web berhasil dimuat")
                            }.onFailure { toast("File web gagal dibaca: ${it.message}") }
                        }
                    }
                    return
                }
            }
        }
        if (requestCode == COLOR_PICKER_CAPTURE_REQUEST) {
            // Screen capture flow disabled for privacy/policy compliance.
            toast("Pipet layar tidak tersedia di versi ini")
            return
        }
        if (requestCode == COLOR_PHOTO_PICK_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: error("Foto tidak dapat dibaca")
            }.onSuccess { loadColorPhoto(it) }
             .onFailure { toast("Foto gagal dibaca: ${it.message}") }
            return
        }
        if (requestCode == COLOR_PALETTE_EXPORT_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val palette = currentPhotoPalette.toList()
            val text = if (pendingColorPaletteExportFormat == "json") {
                val arr = JSONArray()
                palette.forEach { (color, percent) ->
                    val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
                    arr.put(JSONObject().apply {
                        put("hex", "#%02X%02X%02X".format(Locale.US, r, g, b))
                        put("rgb", JSONArray().put(r).put(g).put(b))
                        put("percent", percent)
                    })
                }
                JSONObject().apply { put("source", "MyTools Color Tools"); put("colors", arr) }.toString(2)
            } else {
                palette.joinToString("\n") { (color, percent) ->
                    "#%02X%02X%02X\t$percent%%".format(Locale.US, Color.red(color), Color.green(color), Color.blue(color))
                }
            }
            runCatching { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) } }
                .onSuccess { toast("Palet berhasil diekspor") }
                .onFailure { toast("Ekspor palet gagal: ${it.message}") }
            return
        }
        if (requestCode == COLOR_PHOTO_CAMERA_REQUEST && resultCode == RESULT_OK) {
            val bitmap = data?.extras?.get("data") as? Bitmap
            if (bitmap != null) loadColorPhoto(bitmap) else toast("Foto kamera tidak tersedia")
            return
        }
        if (requestCode == 1030 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            uploadOtaUri(uri, pendingOtaEndpoint)
            return
        }
        if (requestCode == FINANCE_EXPORT_CREATE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching { contentResolver.openOutputStream(uri)?.use { out ->
                val db = FinanceDb(this)
                val bytes = if (pendingFinanceExportJson) financeJson(db).toString(2).toByteArray(StandardCharsets.UTF_8) else financeCsv(db).toByteArray(StandardCharsets.UTF_8)
                out.write(bytes)
            } }.onSuccess { toast("Ekspor berhasil") }.onFailure { toast("Ekspor gagal: ${it.message}") }
            return
        }
        if (requestCode == FINANCE_BACKUP_CREATE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching { contentResolver.openOutputStream(uri)?.use { out -> out.write(financeJson(FinanceDb(this)).toString(2).toByteArray(StandardCharsets.UTF_8)) } }
                .onSuccess { toast("Backup berhasil disimpan") }.onFailure { toast("Backup gagal: ${it.message}") }
            return
        }
        if (requestCode == FINANCE_BACKUP_OPEN && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching { contentResolver.openInputStream(uri)?.bufferedReader(StandardCharsets.UTF_8)?.use { JSONObject(it.readText()) } }
                .onSuccess { root ->
                    if (root == null) { toast("Backup kosong"); return@onSuccess }
                    AlertDialog.Builder(this).setTitle("Ganti data keuangan?")
                        .setMessage("Restore akan mengganti data keuangan lokal saat ini dengan isi backup. Buat backup saat ini terlebih dahulu jika masih diperlukan.")
                        .setNegativeButton("Batal", null)
                        .setPositiveButton("Restore") { _, _ -> restoreFinanceJson(FinanceDb(this), root) }.show()
                }.onFailure { toast("Restore gagal: ${it.message}") }
            return
        }
        if (requestCode == 3025 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching { writeAppBackup(uri) }
                .onSuccess { toast("Backup V2.32 berhasil disimpan") }
                .onFailure { toast("Backup gagal: ${it.message}") }
            return
        }
        if (requestCode == 3026 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            AlertDialog.Builder(this).setTitle("Restore Backup V2.32?")
                .setMessage("Pengaturan, history, dan Recent Files dari backup akan diterapkan. File kerja tidak dihapus otomatis.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Restore") { _, _ ->
                    runCatching { readAppBackup(uri) }
                        .onSuccess { toast("Restore selesai. Buka ulang tool jika diperlukan.") }
                        .onFailure { toast("Restore gagal: ${it.message}") }
                }.show()
            return
        }
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val file = File(filesDir, "imports").apply { mkdirs() }
            val out = File(file, safeFileName(queryName(uri) ?: "import.txt"))
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
            editor(out)
        }
        if (requestCode == 1002 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            inspectZipOrApk(uri)
        }
        if (requestCode == 1301 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            apkCompareFirstUri = uri
            toast("APK A dipilih. Pilih APK B.")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/vnd.android.package-archive"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 1302)
            return
        }
        if (requestCode == 1302 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val first = apkCompareFirstUri
            if (first == null) { toast("APK A belum dipilih"); return }
            compareApks(first, uri)
            return
        }
        if (requestCode == 9811 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }
                .onSuccess { editorPendingTarget?.setText(it) }
                .onFailure { toast("File gagal dibuka: ${it.message}") }
            editorPendingTarget = null
            return
        }
        if (requestCode == 1020 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            pendingOcrUri = uri
            pendingOcrPreview?.setImageURI(uri)
            pendingOcrView?.setText("")
            toast("Gambar dipilih. Tekan OCR Gambar Terpilih.")
        }
        if (requestCode == 1021 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            pendingApkUri = uri
            analyzeApk(uri)
        }
        if (requestCode == 1010 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            toolThread {
                val r = runCatching {
                    val size = contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
                    require(size <= 8L * 1024 * 1024 || size < 0) { "File terlalu besar. Batas 8 MB." }
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Tidak bisa membaca file")
                    "Base64:\n" + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }.getOrElse { "Base64 file error: ${it.message}" }
                runOnUiThread { output(r) }
            }
        }
        if (requestCode == 1050 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            convPickedUri = uri
            convPickedName = queryName(uri) ?: "file"
            val ext = convPickedName?.substringAfterLast('.', "")?.toUpperCase(Locale.getDefault())
            convFromFormat = if (ext.isNullOrBlank()) "Otomatis terdeteksi" else ext
            convStage = "form"
            renderConv()
        }
        if (requestCode == 1041 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            qrPickedName = queryName(uri)
            decodeQrFromUri(uri)
        }
        if (requestCode == 1042 && resultCode == RESULT_OK) {
            // Gallery pick returns data.data; camera capture writes to qrCameraOutUri instead.
            val uri = data?.data ?: qrCameraOutUri ?: return
            qrPickedName = if (data?.data != null) queryName(uri) else "Foto kamera"
            decodeQrFromUri(uri)
        }
        if (requestCode == 1043 && resultCode == RESULT_OK) {
            val uri = qrCameraOutUri ?: return
            qrPickedName = "Hasil scan kamera"
            decodeQrFromUri(uri)
        }
        if (requestCode == 1003 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            toolThread {
                val r = runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val md5 = MessageDigest.getInstance("MD5")
                        val sha1 = MessageDigest.getInstance("SHA-1")
                        val sha256 = MessageDigest.getInstance("SHA-256")
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            md5.update(buf,0,n); sha1.update(buf,0,n); sha256.update(buf,0,n)
                        }
                        "MD5  ${md5.digest().joinToString("") { "%02x".format(it) }}\n" +
                        "SHA1 ${sha1.digest().joinToString("") { "%02x".format(it) }}\n" +
                        "SHA256 ${sha256.digest().joinToString("") { "%02x".format(it) }}"
                    } ?: "Tidak bisa membaca file"
                }.getOrElse { "Error: ${it.message}" }
                runOnUiThread { output(r) }
            }
        }
        if ((requestCode == 1201 || requestCode == 1202) && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            if (requestCode == 1201) {
                fileHashUriA = uri
                fileHashCompareLabelA?.text = "File A: ${queryName(uri) ?: uri.lastPathSegment ?: uri}"
            } else {
                fileHashUriB = uri
                fileHashCompareLabelB?.text = "File B: ${queryName(uri) ?: uri.lastPathSegment ?: uri}"
            }
            return
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // IoT Dynamic tetap berada di halaman yang sama saat HP berputar ke landscape.
        // Activity tidak dibuat ulang, jadi canvas, posisi widget, dan koneksi tidak hilang.
        if (currentPage == "IoT Dynamic Topology") {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            content.post {
                studioCanvas?.requestLayout()
                studioCanvas?.invalidate()
            }
        }
    }

    internal fun setupDynamicShortcuts() {
        if (Build.VERSION.SDK_INT < 25) return
        val sm = getSystemService(ShortcutManager::class.java) ?: return
        val icon = android.graphics.drawable.Icon.createWithResource(this, R.mipmap.app_icon)
        val shortcuts = listOf(
            ShortcutInfo.Builder(this, "expense")
                .setShortLabel("+ Pengeluaran")
                .setLongLabel("Tambah pengeluaran")
                .setIcon(icon)
                .setIntent(Intent(this, MainActivity::class.java).putExtra("quick_expense", true))
                .build(),
            ShortcutInfo.Builder(this, "income")
                .setShortLabel("+ Pemasukan")
                .setLongLabel("Tambah pemasukan")
                .setIcon(icon)
                .setIntent(Intent(this, MainActivity::class.java).putExtra("quick_income", true))
                .build(),
            ShortcutInfo.Builder(this, "finance")
                .setShortLabel("Keuangan")
                .setLongLabel("Finance Dashboard")
                .setIcon(icon)
                .setIntent(Intent(this, MainActivity::class.java).putExtra("open_finance", true))
                .build(),
            ShortcutInfo.Builder(this, "iot")
                .setShortLabel("ESP Studio")
                .setLongLabel("ESP Studio & Visual Wiring")
                .setIcon(icon)
                .setIntent(Intent(this, MainActivity::class.java).putExtra("open_iot", true))
                .build()
        )
        sm.dynamicShortcuts = shortcuts
    }

    internal fun scheduleFinanceMaintenance() {
        val request = PeriodicWorkRequest.Builder(FinanceMaintenanceWorker::class.java, 24, java.util.concurrent.TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("mytools_finance_maintenance", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    internal fun applySystemTheme() {
        val forced = if (::prefs.isInitialized) prefs.getString("theme_mode", "system") ?: "system" else "system"
        val ui = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isDarkTheme = when (forced) {
            "dark" -> true
            "light" -> false
            else -> ui != Configuration.UI_MODE_NIGHT_NO
        }
        if (!isDarkTheme) {
            dark = Color.rgb(248, 248, 250); panel = Color.rgb(255, 255, 255); panel2 = Color.rgb(242, 242, 246)
            textMain = Color.rgb(24, 24, 28); textMuted = Color.rgb(100, 100, 108); line = Color.rgb(215, 215, 222)
        } else {
            dark = Color.rgb(10, 10, 11); panel = Color.rgb(22, 22, 24); panel2 = Color.rgb(28, 28, 31)
            textMain = Color.rgb(245, 245, 247); textMuted = Color.rgb(155, 155, 160); line = Color.rgb(48, 48, 52)
        }

        // Apply the selected theme to Android system bars immediately.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 23) {
            var flags = EDGE_FLAGS or (if (isDarkTheme) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
            if (Build.VERSION.SDK_INT >= 26 && !isDarkTheme) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    internal fun applyBottomNavShape() {
        // Keep the navigation container rounded. Calling setBackgroundColor() here
        // would replace the rounded drawable with a sharp rectangle.
        val backgroundColor = if (isDarkTheme) panel else Color.WHITE
        bottomNav.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(backgroundColor)
            cornerRadius = dp(30).toFloat()
            setStroke(dp(1), if (isDarkTheme) line else Color.rgb(225, 230, 235))
        }
        bottomNav.clipToOutline = true
        if (Build.VERSION.SDK_INT >= 21) bottomNav.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(30).toFloat())
            }
        }
    }

    internal fun applyUiColors() {
        mainContainer.setBackgroundColor(dark)
        content.setBackgroundColor(dark)

        // Search bar harus hanya punya SATU background berbentuk pill.
        // Jangan gunakan setBackgroundColor() karena itu mengganti drawable
        // rounded menjadi kotak biasa.
        search.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(panel)
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), line)
        }
        search.setTextColor(textMain)
        search.setHintTextColor(textMuted)
        styleTopFabs()
        search.setPadding(dp(16), 0, dp(16), 0)

        applyBottomNavShape()
    }

    internal fun enterApp() {
        loginScreen.visibility = View.GONE
        mainContainer.visibility = View.VISIBLE
        // Do not force Light here: the Theme setting must be respected on every
        // entry into the app, including after login, shortcuts, and deep links.
        applySystemTheme()
        applyUiColors()
        showHome()
    }

    internal fun applyLightAppTheme() {
        isDarkTheme = false
        dark = Color.rgb(255, 255, 255)
        panel = Color.rgb(248, 250, 252)
        panel2 = Color.rgb(244, 246, 248)
        textMain = Color.rgb(15, 15, 16)
        textMuted = Color.rgb(123, 135, 148)
        line = Color.rgb(225, 230, 235)
        mainContainer.setBackgroundColor(Color.WHITE)
        content.setBackgroundColor(Color.WHITE)
        applyBottomNavShape()
        search.setBackgroundResource(com.example.aidetest.R.drawable.bg_search_light)
        search.setTextColor(textMain)
        search.setHintTextColor(textMuted)
        styleTopFabs()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility = EDGE_FLAGS or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(colorPickerReceiver) }
        stopClipboardMonitor()
        modernUiRunnable?.let { modernUiHandler.removeCallbacks(it) }
        modernUiRunnable = null
        toolLoadingHandler.removeCallbacksAndMessages(null)
        toolLoadingSpinner?.let { Motion.stopLoading(it) }
        networkScanStop.set(true)
        stopEspDiscovery()
        stopEspSensorPolling()
        stopLedPlayback()
        server?.close()
        server = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { hotspotReservation?.close() }
        }
        hotspotReservation = null
        super.onDestroy()
    }

    internal data class ToolVisualTheme(
        val accent: Int,
        val surface: Int,
        val border: Int,
        val chip: String,
        val button: Int,
        val onButton: Int = Color.WHITE
    )

    internal fun visualTheme(name: String = currentPage): ToolVisualTheme {
        // MyTools uses one consistent monochrome UI. Tool categories may still
        // have different labels, but never introduce colored buttons/accent panels.
        return ToolVisualTheme(
            textMain,
            panel2,
            line,
            when {
                name.contains("esp", true) || name.contains("iot", true) -> "HARDWARE"
                name.contains("jaringan", true) || name.contains("network", true) || name.contains("dns", true) || name.contains("ping", true) || name.contains("port", true) || name.contains("http", true) || name.contains("ssl", true) -> "NETWORK"
                name.contains("security", true) || name.contains("password", true) || name.contains("token", true) || name.contains("aes", true) || name.contains("hmac", true) || name.contains("jwt", true) || name.contains("hash", true) -> "SECURITY"
                name.contains("keuangan", true) || name.contains("finance", true) || name.contains("dca", true) || name.contains("loan", true) || name.contains("margin", true) || name.contains("discount", true) || name.contains("bunga", true) -> "FINANCE"
                name.contains("file", true) || name.contains("zip", true) || name.contains("apk", true) || name.contains("storage", true) || name.contains("folder", true) -> "FILES"
                name.contains("color", true) || name.contains("pipet", true) || name.contains("sprite", true) || name.contains("qr", true) || name.contains("ocr", true) -> "VISUAL"
                name.contains("editor", true) || name.contains("json", true) || name.contains("xml", true) || name.contains("regex", true) || name.contains("base64", true) || name.contains("text", true) || name.contains("unicode", true) -> "DEVELOPER"
                name.contains("battery", true) || name.contains("device", true) || name.contains("system", true) -> "SYSTEM"
                else -> "UTILITY"
            },
            textMain,
            if (isDarkTheme) Color.rgb(15, 15, 16) else Color.WHITE
        )
    }

    internal fun toolAccentStrip(name: String): View {
        val t = visualTheme(name)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = bg(t.surface, 14, t.border)
            addView(View(this@MainActivity).apply { background = bg(t.accent, 3) }, LinearLayout.LayoutParams(dp(5), dp(30)))
            addView(TextView(this@MainActivity).apply {
                text = t.chip
                textSize = 10f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(t.accent)
                setPadding(dp(10), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "● READY"
                textSize = 10f
                setTextColor(Color.rgb(105, 110, 116))
            })
        }
    }

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Lightweight native animations: no extra dependency, tuned for Android phones.
    internal fun animationsEnabled(): Boolean = Motion.enabled

    // Hormati pengaturan sistem "Skala durasi animator" = 0 (Reduce Motion / hemat baterai).
    internal fun systemReduceMotion(): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    // Helper lama kini hanya mendelegasikan ke Motion agar hanya ada satu sistem animasi.
    internal fun animateEditorItem(view: View, delay: Long = 0L, distance: Float = 18f) =
        Motion.enter(view, delay, distance, 240L)

    internal fun animateEditorPress(view: View) = Motion.tap(view, 0.96f)

    internal fun animateEditorScreen() = Motion.enter(content, 0L, 8f, 220L)


    // ---- Restored navigation / UI helpers ----
    // These small helpers are intentionally kept local to MainActivity so older
    // screens and newer GitHub screens can share the same rendering primitives.
    /**
     * Navigasi tab root (Beranda / Tools / Favorit / Settings).
     * Jika sudah di [targetPage] dan konten masih ada, jangan rebuild UI
     * (menghindari jank: removeAllViews → layout → scroll=0).
     */
    internal fun navigateRoot(targetPage: String? = null, render: () -> Unit) {
        if (restoringSnapshot) return
        if (targetPage != null &&
            currentPage == targetPage &&
            content.childCount > 0 &&
            !resettingRootNavigation
        ) {
            selectBottomNav(targetPage)
            return
        }
        pageBackStack.clear()
        resettingRootNavigation = true
        try { render() } finally { resettingRootNavigation = false }
    }

    internal fun clearPage(name: String = currentPage, pushBack: Boolean = true) {
        if (!restoringSnapshot && pushBack && !resettingRootNavigation) {
            saveCurrentPageSnapshot()
        }
        content.removeAllViews()
        modernUiRunnable?.let { modernUiHandler.removeCallbacks(it) }
        modernUiRunnable = null
        currentPage = name
        // Top bar (hamburger, cari, profil) disembunyikan oleh workspace Editor lewat
        // topBarVisibility(false). Kembalikan setiap halaman baru dibuka; renderEditorPage()
        // akan menyembunyikannya lagi sendiri bila memang perlu.
        if (topBar.visibility != View.VISIBLE) topBarVisibility(true)

        val root = name == "home" || name == "all" || name == "favorites" || name == "settings"
        val isHome = name == "home"
        // Halaman "Semua Tools" tidak memakai search bar. Search di sini dulu
        // ikut terhubung ke listener scroll sehingga saat pengguna naik-turun
        // halaman, bar bisa snap berulang dan membuat tampilan terasa meloncat.
        // Search tersedia di Beranda dan Favorit. Semua Tools memakai filter chip
        // sendiri agar tidak membuat dua sistem pencarian saling bertabrakan.
        val showRootSearch = name == "home" || name == "favorites"

        title.text = when (name) {
            "home" -> "GITLS"
            "all" -> "Semua Tools"
            "favorites" -> "Favorit"
            "settings" -> "Pengaturan"
            else -> name
        }
        subtitle.visibility = if (isHome) View.VISIBLE else View.GONE
        homeMenu.visibility = if (isHome) View.VISIBLE else View.GONE
        homeSearch.visibility = if (showRootSearch) View.VISIBLE else View.GONE
        homeProfile.visibility = if (isHome) View.VISIBLE else View.GONE
        back.visibility = if (isHome) View.GONE else View.VISIBLE
        action.visibility = if (isHome) View.GONE else View.VISIBLE

        searchSnapEnabled = showRootSearch
        resetSearchSnap(showRootSearch)
        // Toolbar aksi editor hanya boleh hidup ketika benar-benar berada di editor.
        // Sebelumnya toolbar tetap terlihat setelah keluar dari Preview karena
        // editorBottomBar adalah view global di layout utama dan clearPage()
        // tidak mengubah visibility-nya.
        // Landing Editor juga bukan workspace edit — toolbar bawah harus hilang.
        if (name != "Editor" || editorLanding) {
            editorBottomBar.visibility = View.GONE
            editorBottomBar.alpha = 1f
            editorBottomBar.translationY = 0f
        }
        bottomNav.visibility = if (root && !imeVisible) View.VISIBLE else View.GONE
        bottomNav.translationY = 0f
        if (root) selectBottomNav(name)
        configureActionForPage(name)
        // Normalize after the page renderer has finished adding its content.
        // This removes any page-local title that duplicates the global toolbar title.
        content.post { normalizeToolContentHeader() }
        scroll.post { scroll.scrollTo(0, 0) }
    }

    internal fun addPressFeedback(view: View) = Motion.press(view)

    internal fun applyInteractiveSurface(view: View, radius: Int = Ds.RADIUS_LG, elevationDp: Int = 1) {
        view.background = rippleBg(if (isDarkTheme) panel2 else Color.WHITE, radius, line)
        if (elevationDp > 0) view.elevation = dp(elevationDp).toFloat()
        view.clipToOutline = true
    }

    internal fun label(text: String, sizeSp: Float = 15f, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(textMain)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            includeFontPadding = true
        }

    internal fun subLabel(text: String, sizeSp: Float = 12f): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(textMuted)
            includeFontPadding = true
        }

    internal fun toolCard(id: String, name: String, iconName: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            contentDescription = "$name. Buka tool"
            minimumHeight = dp(64)
        }
        applyInteractiveSurface(card, Ds.RADIUS_LG, 1)
        card.setOnClickListener { openToolWithPress(id, card) }

        val iconBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = bg(if (isDarkTheme) Color.rgb(42, 42, 46) else Color.rgb(238, 242, 245), 13)
        }
        iconBox.addView(MdiIconView(this).apply {
            setIconName(iconName)
            setIconSize(21f)
            setTextColor(if (isDarkTheme) Color.WHITE else Color.rgb(70, 80, 90))
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        card.addView(iconBox, LinearLayout.LayoutParams(dp(42), dp(42)))
        // Sentuhan kartu: scale kecil + shadow turun + ikon bereaksi sesuai karakter Tool.
        val toolCharacter = Motion.characterFor(id)
        Motion.press(card, 0.98f, shadowDp = -1f, onDown = {
            Motion.characterIcon(iconBox.getChildAt(0) ?: iconBox, toolCharacter)
        })

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(6), 0)
        }
        texts.addView(label(name, 13.5f, true))
        texts.addView(subLabel("Buka tool", 10.5f))
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            contentDescription = "Buka"
        }, LinearLayout.LayoutParams(dp(28), dp(48)))
        return card
    }

    internal fun bg(color: Int, radius: Int = 16, stroke: Int? = null): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    /** Ganti warna isi tanpa menghilangkan sudut tumpul (setBackgroundColor akan membuatnya kotak tajam). */
    internal fun keepShapeFill(v: View, color: Int) {
        val d = v.background
        if (d is android.graphics.drawable.GradientDrawable) { d.mutate(); (v.background as android.graphics.drawable.GradientDrawable).setColor(color) }
        else v.setBackgroundColor(color)
    }

    // ---- Design system helpers (lihat DesignSystem.kt) ----
    internal fun rippleBg(fill: Int, radius: Int = Ds.RADIUS_MD, stroke: Int? = null): Drawable {
        val base = bg(fill, radius, stroke)
        val rippleColor = ColorStateList.valueOf(
            if (isDarkTheme) Color.argb(48, 255, 255, 255) else Color.argb(36, 0, 0, 0)
        )
        val mask = bg(Color.WHITE, radius)
        return RippleDrawable(rippleColor, base, mask)
    }

    internal fun statusColor(state: Ds.State): Int = Ds.statusColor(state, isDarkTheme)

    internal fun isSecondaryAction(text: String): Boolean {
        val t = text.trim().lowercase(Locale.ROOT)
        return listOf(
            "salin", "copy", "bagikan", "share", "bersihkan", "clear", "hapus", "reset",
            "batal", "cancel", "tutup", "close", "kembali", "back", "acak ulang"
        ).any { t == it || t.startsWith("$it ") }
    }

    /** Level 1: aksi utama (terisi). */
    internal fun styleAsPrimary(b: TextView) {
        val theme = visualTheme()
        b.setTextColor(theme.onButton)
        b.background = rippleBg(theme.button, Ds.RADIUS_MD, theme.button)
    }

    /** Level 2: aksi pendukung (outline). */
    internal fun styleAsSecondary(b: TextView) {
        b.setTextColor(textMain)
        b.background = rippleBg(panel, Ds.RADIUS_MD, line)
    }

    /** Level 3: aksi kecil (teks saja, tetap 48dp). */
    internal fun styleAsTertiary(b: TextView) {
        b.setTextColor(textMain)
        b.background = rippleBg(Color.TRANSPARENT, Ds.RADIUS_SM)
    }

    internal fun secondaryButton(text: String, onClick: () -> Unit): Button =
        button(text, onClick).also { styleAsSecondary(it) }

    internal fun tertiaryButton(text: String, onClick: () -> Unit): Button =
        button(text, onClick).also { styleAsTertiary(it) }

    /**
     * Komponen state reusable: Loading / Success / Error / Empty / Info / Warning.
     * onRetry (opsional) menampilkan tombol "Coba lagi".
     */
    internal fun stateCard(
        state: Ds.State,
        title: String,
        message: String = "",
        onRetry: (() -> Unit)? = null
    ): LinearLayout {
        val tint = statusColor(state)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_XL), dp(Ds.SPACE_LG), dp(Ds.SPACE_XL))
            background = bg(panel2, Ds.RADIUS_LG, line)
            contentDescription = if (message.isBlank()) title else "$title. $message"
        }
        if (state == Ds.State.LOADING) {
            card.addView(ProgressBar(this).apply { isIndeterminate = true },
                LinearLayout.LayoutParams(dp(Ds.TOUCH_MIN), dp(Ds.TOUCH_MIN)))
        } else {
            card.addView(MdiIconView(this).apply {
                setIconName(Ds.stateIcon(state)); setIconSize(28f); setTextColor(tint)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        card.addView(label(title, 15f, true).apply { gravity = Gravity.CENTER; setTextColor(tint) })
        if (message.isNotBlank()) card.addView(subLabel(message, 13f).apply { gravity = Gravity.CENTER })
        if (onRetry != null && state == Ds.State.ERROR) {
            val retry = secondaryButton("Coba lagi", onRetry)
            card.addView(retry, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Ds.SPACE_MD) })
        }
        return card
    }

    internal fun addEmptyState(title: String = "Belum ada hasil", message: String = "Jalankan tool untuk melihat hasilnya") {
        content.addView(stateCard(Ds.State.EMPTY, title, message),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Ds.SPACE_MD); bottomMargin = dp(Ds.SPACE_MD) })
    }

    internal fun saveCurrentPageSnapshot() {
        if (restoringSnapshot || content.childCount == 0) return

        val rootPage = currentPage == "home" || currentPage == "all" ||
                currentPage == "favorites" || currentPage == "settings"

        // Root pages can contain dozens/hundreds of Views. Moving the entire View tree
        // into the Back stack caused visible jank and retained a lot of memory. Keep only
        // the page identity for roots and rebuild them on demand when Back is pressed.
        if (rootPage) {
            pageBackStack.addLast(
                PageSnapshot(
                    name = currentPage,
                    children = mutableListOf(),
                    scrollY = scroll.scrollY,
                    searchText = search.text?.toString() ?: "",
                    searchVisible = if (searchOpen) View.VISIBLE else View.GONE,
                    lightweight = true
                )
            )
            while (pageBackStack.size > 8) pageBackStack.removeFirst()
            return
        }

        val children = ArrayList<View>(content.childCount)
        while (content.childCount > 0) {
            children.add(content.getChildAt(0))
            content.removeViewAt(0)
        }
        pageBackStack.addLast(
            PageSnapshot(
                name = currentPage,
                children = children,
                scrollY = scroll.scrollY,
                searchText = search.text?.toString() ?: "",
                searchVisible = if (searchOpen) View.VISIBLE else View.GONE
            )
        )
        while (pageBackStack.size > 8) pageBackStack.removeFirst()
    }

    internal fun restoreSnapshot(snapshot: PageSnapshot) {
        restoringSnapshot = true
        try {
            // Re-render root pages instead of restoring a huge retained View tree.
            // This keeps Back navigation smooth and prevents the app from accumulating
            // hundreds of detached card Views in memory.
            if (snapshot.lightweight) {
                when (snapshot.name) {
                    "home" -> showHome(homeFilter)
                    "all" -> showAllTools()
                    "favorites" -> showFavorites()
                    "settings" -> showSettings()
                    else -> showHome()
                }
                return
            }

            content.removeAllViews()
            snapshot.children.forEach { content.addView(it) }
            currentPage = snapshot.name
            if (snapshot.name != "Editor" && topBar.visibility != View.VISIBLE) topBarVisibility(true)
            // ESP Studio memakai landscape. Saat tombol kembali ditekan, kembalikan
            // orientasi ke portrait agar layar benar-benar kembali ke posisi semula.
            if (snapshot.name != "IoT Dynamic Topology") {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            val root = snapshot.name == "home" || snapshot.name == "all" || snapshot.name == "favorites" || snapshot.name == "settings"
            title.text = when (snapshot.name) {
                "home" -> "GITLS"
                "all" -> "Semua Tools"
                "favorites" -> "Favorit"
                "settings" -> "Pengaturan"
                else -> snapshot.name
            }
            subtitle.visibility = if (snapshot.name == "home") View.VISIBLE else View.GONE
            homeMenu.visibility = if (snapshot.name == "home") View.VISIBLE else View.GONE
            homeSearch.visibility = if (snapshot.name == "home" || snapshot.name == "favorites") View.VISIBLE else View.GONE
            homeProfile.visibility = if (snapshot.name == "home") View.VISIBLE else View.GONE
            action.visibility = if (snapshot.name == "home") View.GONE else View.VISIBLE
            back.visibility = if (snapshot.name == "home") View.GONE else View.VISIBLE
            resetSearchSnap(snapshot.name == "home" || snapshot.name == "favorites")
            suppressSearch = true
            search.setText(snapshot.searchText)
            suppressSearch = false
            if (searchSnapEnabled && (snapshot.searchVisible == View.VISIBLE || snapshot.searchText.isNotEmpty())) setSearchBoxOpen(true)
            bottomNav.visibility = if (root && !imeVisible) View.VISIBLE else View.GONE
            bottomNav.translationY = 0f
            // Toolbar aksi editor hanya tampil saat benar-benar berada di mode edit.
            // Saat halaman Preview dibuka toolbar disembunyikan, lalu dimunculkan lagi
            // ketika snapshot editor dipulihkan lewat tombol kembali.
            editorBottomBar.visibility = if (snapshot.name == "Editor" && !editorLanding) View.VISIBLE else View.GONE
            if (root) selectBottomNav(snapshot.name)
            configureActionForPage(snapshot.name)
            scroll.post { scroll.scrollTo(0, snapshot.scrollY) }
        } finally {
            restoringSnapshot = false
        }
    }

    internal fun navigateBack() {
        if (drawerOpen) {
            closeDrawer()
            return
        }
        if (currentPage == "Editor" && !editorLanding) {
            editorExternalTarget = null
            editorExternalMode = null
        }
        if (pageBackStack.isEmpty()) {
            if (currentPage != "home") {
                // Safety fallback for a page created before the stack was populated.
                showHome()
            } else {
                super.onBackPressed()
            }
            return
        }
        val snapshot = pageBackStack.removeLast()
        restoreSnapshot(snapshot)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (currentPage == "Konversi File" && convCategory != null) { convGoBackStage(); return }
        navigateBack()
    }

    internal fun configureActionForPage(name: String) {
        when (name) {
            "IoT Dynamic Topology" -> {
                action.text = "+"; action.textSize = 28f; action.setOnClickListener { showStudioWidgetPicker() }
                editorMore.visibility = View.VISIBLE; editorMore.text = "⋮"; editorMore.textSize = 25f
                editorMore.setOnClickListener { showToolMenu(editorMore) }
            }
            "Pengelola Keuangan" -> {
                action.text = "+"; action.textSize = 28f; action.setOnClickListener { showFinanceActions() }
                editorMore.visibility = View.VISIBLE; editorMore.text = "⋮"; editorMore.textSize = 25f
                editorMore.setOnClickListener { showToolMenu(editorMore) }
            }
            in rmAllPages -> {
                editorMore.visibility = View.GONE
                action.text = "⋮"; action.textSize = 25f
                action.setOnClickListener { rmMenu() }
            }
            else -> {
                if (name == "Editor" || name.startsWith("Editor - ")) {
                    action.text = "+"
                    action.textSize = 28f
                    action.setOnClickListener { showEditorModePicker() }
                    editorMore.visibility = if (name == "Editor" && !editorLanding) View.VISIBLE else View.GONE
                    editorMore.text = "⋮"
                    editorMore.textSize = 25f
                    editorMore.setOnClickListener { showEditorMoreMenu() }
                } else {
                    editorMore.visibility = View.GONE
                    action.text = "⋮"
                    action.textSize = 25f
                    action.setOnClickListener { showToolMenu(action) }
                }
            }
        }
    }

    /** Ghost floating controls: no visible container, only a subtle icon shadow. */
    /**
     * Semua tombol top bar (menu, kembali, cari, profil, titik tiga/aksi) tampil sebagai
     * Floating Action Button mini: lingkaran, bayangan lembut, tanpa kotak pembungkus.
     */
    internal fun styleTopFabs() {
        val fill = if (isDarkTheme) panel else Color.WHITE
        val stroke = if (isDarkTheme) line else Color.rgb(232, 235, 239)
        val ink = if (isDarkTheme) textMain else Color.rgb(23, 32, 42)
        topBar.setBackgroundColor(Color.TRANSPARENT)
        // Wajib di atas ScrollView (z lebih tinggi) agar header yang menimpa konten tetap menerima sentuhan.
        topBar.elevation = dp(8).toFloat()
        topBar.translationZ = dp(2).toFloat()
        listOf<View>(homeMenu, back, homeSearch, homeProfile, action, editorMore).forEach { v ->
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                setStroke(dp(1), stroke)
            }
            v.setLayerType(View.LAYER_TYPE_NONE, null)
            v.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            v.clipToOutline = false
            v.elevation = dp(4).toFloat()
            v.translationY = 0f
            when (v) {
                is ImageButton -> { v.scaleType = ImageView.ScaleType.CENTER_INSIDE; v.setColorFilter(ink) }
                is TextView -> { v.setTextColor(ink); v.includeFontPadding = false; v.gravity = Gravity.CENTER }
            }
        }
    }

    internal fun applyGhostShadow(view: View) {
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        view.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                val inset = dp(8)
                outline.setOval(inset, inset, v.width - inset, v.height - inset)
                outline.alpha = 0.12f
            }
        }
        view.clipToOutline = false
        view.elevation = dp(5).toFloat()
    }

    /**
     * Dipanggil setiap halaman baru dibuka. [show] = halaman ini punya tombol cari.
     * Kotak cari hanya muncul lewat tombol cari (di sebelahnya, di top bar).
     */
    internal fun resetSearchSnap(show: Boolean) {
        searchSnapEnabled = show
        if (!show || (search.text.isNullOrEmpty() && !search.hasFocus())) {
            setSearchBoxOpen(false)
        } else {
            setSearchBoxOpen(true)
        }
    }

    /** Tampilkan / sembunyikan kotak cari di samping tombol cari. */
    internal fun setSearchBoxOpen(open: Boolean, focus: Boolean = false) {
        searchOpen = open
        searchFill.visibility = if (open) View.GONE else View.VISIBLE
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        if (open) {
            search.visibility = View.VISIBLE
            search.alpha = 1f
            if (focus) {
                search.post {
                    search.requestFocus()
                    search.setSelection(search.text.length)
                    imm?.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
        } else {
            if (search.hasFocus()) search.clearFocus()
            imm?.hideSoftInputFromWindow(search.windowToken, 0)
            search.visibility = View.GONE
        }
    }

    internal fun showHome(filter: String = homeFilter) {
        homeFilter = filter
        clearPage("home", true)
        content.setPadding(dp(12), dp(8), dp(12), dp(18))

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
        homePinnedTools()

        // ==================== TOOLS ====================
        homeSectionHeader("Tools", "Lihat Semua") { showAllTools() }
        val toolGrid = GridLayout(this).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }

        // Pengelompokan Beranda mengikuti fungsi tool yang sebenarnya.
        // Studio tetap dipisahkan dari Tools; kartu di bawah hanya berisi tool operasional.
        val toolCategories = listOf(
            HomeCategory("FILE & APP", "15 tools", "folder-outline", listOf(
                "filemanager", "recentfiles", "backuprestore", "zip", "githubzip", "fileconvert", "filesearch", "storage",
                "apps", "apk", "apkcompare", "duplicatefinder", "largefilefinder", "filehashcompare", "dedupe"
            )),
            HomeCategory("SYSTEM", "3 info gabungan", "cellphone-cog", listOf(
                "devicecenter"
            )),
            HomeCategory("CALCULATOR", "10 tools", "calculator-variant-outline", listOf(
                "number", "pivotcalc", "dividercalc", "dcacalc", "pwmcalc", "spritecalc", "installcalc", "powercalc", "aspectcalc", "pphcalc"
            )),
            HomeCategory("TEXT & DEV", "31 tools", "file-document-edit-outline", listOf(
                "editor", "json", "base64", "url", "regex", "uuid", "textstat", "case", "compare", "slug", "lorem", "token", "random",
                "hex", "base32", "timestamp", "unicode", "urlparser", "mime", "jsonformat", "xmlformat", "uuidbatch", "base64file",
                "textreplace", "wordfreq", "markdown", "sql", "yaml", "toml", "cron", "helpbot"
            )),
            HomeCategory("SECURITY", "20 tools", "shield-check-outline", listOf(
                "hash", "password", "passwordstrength", "jwt", "hmac", "totp", "aes", "checksum", "securitycenter", "fileencryption",
                "steganography", "passwordanalyzer", "breachchecker", "securenotes", "totpvault", "pgp", "sshkeygen", "certviewer", "virusscanner", "urlsafety"
            )),
            HomeCategory("NETWORK", "15 tools", "web", listOf(
                "dns", "rdns", "port", "publicip", "ping", "ipinfo", "ssl", "http", "httpheaders", "restclient", "websocket", "network", "networkcenter", "wifi", "webhostwifi"
            )),
            HomeCategory("MEDIA & COLOR", "2 tools", "palette-outline", listOf(
                "color", "imagestudio"
            )),
            HomeCategory("QR / OCR", "1 tool", "qrcode", listOf("qr")),
            HomeCategory("FINANCE", "2 tools", "bank-outline", listOf("financereader", "financedashboard")),
            HomeCategory("UTILITY", "3 tools", "toolbox-outline", listOf("reminder", "stopwatch", "timer")),
            HomeCategory("LAINNYA", "4 tools", "view-grid-outline", listOf("workspace", "plugincenter", "customtools", "studiocenter"))
        )

        toolCategories.forEach { category ->
            val card = homeCategoryCard(category)
            toolGrid.addView(card, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(82)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        content.addView(toolGrid, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // ==================== STUDIO ====================
        homeSectionHeader("Studio", "Lihat Semua") { showAllStudios() }
        val studios = listOf(
            Triple("Web Project Builder", "web-box", "webproject"),
            Triple("Network Studio", "web", "networkstudio"),
            Triple("Developer Studio", "code-tags", "developerstudio"),
            Triple("File Studio", "folder-outline", "filestudio"),
            Triple("Image Studio", "image-outline", "imagestudio"),
            Triple("Finance Studio", "bank-outline", "financestudio"),
            Triple("System Studio", "cog-outline", "systemstudio"),
            Triple("Utility Studio", "toolbox-outline", "utilitystudio"),
            Triple("ESP / IoT Studio", "chip", "espstudio")
        )
        studios.forEach { (name, icon, id) ->
            content.addView(homeStudioCard(name, icon, id), LinearLayout.LayoutParams(-1, dp(76)).apply {
                bottomMargin = dp(7)
            })
        }
    }

    internal fun homePinnedTools() {
        val favorites = favoriteToolIds().mapNotNull { id -> homeToolMap[id]?.let { id to it } }.take(6)
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
                contentDescription = "Buka favorit $name"
            }
            card.addView(MdiIconView(this).apply { setIconName(iconFor(id)); setIconSize(21f); setTextColor(textMain) }, LinearLayout.LayoutParams(dp(30), dp(30)))
            card.addView(label(name, 10.5f, true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(dp(92), dp(30)))
            rail.addView(card, LinearLayout.LayoutParams(dp(112), dp(70)).apply { rightMargin = dp(7) })
        }
        row.addView(rail)
        content.addView(row, LinearLayout.LayoutParams(-1, dp(76)).apply { bottomMargin = dp(5) })
    }

    internal fun homeDashboardCard() {
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
            setPadding(dp(12), dp(12), dp(12), dp(10))
            contentDescription = "Ringkasan MyTools"
        }
        applyInteractiveSurface(card, 18, 1)

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
            contentDescription = "Buka pengaturan MyTools"
        })
        card.addView(header)

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, 0)
        }
        fun stat(icon: String, value: String, title: String, click: (() -> Unit)? = null): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(5), dp(5), dp(5))
                contentDescription = "$title: $value"
                addView(MdiIconView(this@MainActivity).apply {
                    setIconName(icon); setIconSize(19f); setTextColor(textMuted)
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(label(value, 16f, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
                addView(subLabel(title, 9.5f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
                if (click != null) setOnClickListener { click() }
            }
        }
        stats.addView(stat("star", favoriteCount.toString(), "Favorit") { showFavorites() }, LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(stat("history", historyCount.toString(), "Aktivitas") { historyTool() }, LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(stat("bell-outline", reminderCount.toString(), "Reminder") { openTool("reminder") }, LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(stat("tools", toolCount.toString(), "Tools") { showAllTools() }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(stats)

        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(7)
        })
    }

    internal data class HomeCategory(
        val name: String,
        val count: String,
        val icon: String,
        val ids: List<String>
    )

    internal fun homeSectionHeader(titleText: String, actionText: String, actionClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        val gridIcon = MdiIconView(this).apply {
            setIconName("view-grid")
            setIconSize(21f)
            setTextColor(textMain)
        }
        row.addView(gridIcon, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(5) })
        row.addView(label(titleText, 19f, true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = actionText + "  ›"
            textSize = 12f
            setTextColor(textMuted)
            setPadding(dp(6), dp(8), 0, dp(8))
            setOnClickListener { actionClick() }
            contentDescription = "$actionText $titleText"
        })
        content.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    internal fun homeCategoryCard(category: HomeCategory): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            contentDescription = "${category.name}, ${category.count}"
        }
        applyInteractiveSurface(card, 18, 1)
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

        val iconBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = bg(if (isDarkTheme) Color.rgb(42, 47, 52) else Color.rgb(232, 241, 247), 14)
        }
        iconBox.addView(MdiIconView(this).apply {
            setIconName(category.icon)
            setIconSize(23f)
            setTextColor(if (isDarkTheme) Color.WHITE else Color.rgb(82, 96, 112))
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        card.addView(iconBox, LinearLayout.LayoutParams(dp(42), dp(42)))

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(4), 0)
        }
        texts.addView(label(category.name, 12.5f, true))
        texts.addView(subLabel(category.count, 10.5f))
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(TextView(this).apply {
            text = "›"
            textSize = 22f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(20), dp(42)))
        return card
    }

    internal fun homeStudioCard(name: String, iconName: String, id: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            contentDescription = "$name. Buka Studio"
        }
        applyInteractiveSurface(card, 18, 1)
        card.setOnClickListener { openTool(id) }

        val iconBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = bg(if (isDarkTheme) Color.rgb(42, 47, 52) else Color.rgb(232, 241, 247), 13)
        }
        iconBox.addView(MdiIconView(this).apply {
            setIconName(iconName)
            setIconSize(21f)
            setTextColor(if (isDarkTheme) Color.WHITE else Color.rgb(82, 96, 112))
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        card.addView(iconBox, LinearLayout.LayoutParams(dp(42), dp(42)))

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

    internal fun showAllStudios() {
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
            Triple("Finance Studio", "bank-outline", "financestudio"),
            Triple("System Studio", "cog-outline", "systemstudio"),
            Triple("Utility Studio", "toolbox-outline", "utilitystudio"),
            Triple("ESP / IoT Studio", "chip", "espstudio")
        )
        studios.forEach { (name, icon, id) ->
            content.addView(homeStudioCard(name, icon, id), LinearLayout.LayoutParams(-1, dp(76)).apply {
                bottomMargin = dp(7)
            })
        }
    }

    internal fun filteredHomeItems(filter: String): List<Pair<String, String>> {
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

    internal fun defaultPreferredTools(): List<String> = listOf(
        "filemanager", "editor", "reminder", "zip", "githubzip", "http", "json", "hash", "base64", "url", "regex", "uuid", "color",
        "number", "textstat", "case", "dedupe", "compare", "slug", "lorem", "password", "token", "jwt", "hmac", "totp", "aes",
        "random", "checksum", "hex", "base32", "dns", "rdns", "port", "publicip", "ping", "ipinfo", "ssl", "apk", "qr", "system",
        "timestamp", "unicode", "urlparser", "mime", "jsonformat", "xmlformat", "uuidbatch", "base64file", "httpheaders", "textreplace",
        "wordfreq", "devicecenter", "storage", "apps", "network", "filesearch", "pivotcalc", "dividercalc", "dcacalc",
        "pwmcalc", "spritecalc", "installcalc", "powercalc", "aspectcalc", "pphcalc", "financereader", "financedashboard",
        "securitycenter", "clipboard", "ocr", "unitconverter", "apkanalyzer", "netscanner",
        "webproject", "networkstudio", "developerstudio", "filestudio", "imagestudio",
        "systemstudio", "financestudio", "utilitystudio", "espstudio", "whois", "traceroute", "subnetcalc"
    )

    internal fun favoriteToolIds(): List<String> = prefs.getString("favorite_tools", "")
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    internal fun isFavorite(id: String): Boolean = favoriteToolIds().contains(id)

    internal fun toggleFavorite(id: String) {
        val old = favoriteToolIds().toMutableList()
        if (old.contains(id)) old.remove(id) else old.add(0, id)
        val nowFavorite = old.contains(id)
        prefs.edit().putString("favorite_tools", old.distinct().take(30).joinToString(",")).apply()
        toast(if (nowFavorite) "Ditambahkan ke favorit" else "Dihapus dari favorit")
    }

    internal fun toolCategory(id: String): String {
        val n = (homeToolMap[id] ?: id).lowercase(Locale.getDefault())
        return when {
            n.contains("esp") || n.contains("iot") || n.contains("gpio") || n.contains("sensor") -> "ESP / IoT"
            n.contains("network") || n.contains("wifi") || n.contains("dns") || n.contains("ping") || n.contains("http") || n.contains("ssl") || n.contains("port") || n.contains("ip ") -> "Network"
            n.contains("password") || n.contains("hash") || n.contains("token") || n.contains("aes") || n.contains("hmac") || n.contains("jwt") || n.contains("totp") || n.contains("encryption") || n.contains("steganography") || n.contains("breach") || n.contains("secure notes") || n.contains("pgp") || n.contains("ssh key") || n.contains("certificate") || n.contains("virus scanner") || n.contains("url safety") -> "Security"
            n.contains("finance") || n.contains("keuangan") || n.contains("dca") || n.contains("bunga") || n.contains("pajak") || n.contains("margin") || n.contains("diskon") -> "Finance"
            n.contains("file") || n.contains("zip") || n.contains("apk") || n.contains("storage") || n.contains("folder") -> "File"
            n.contains("text") || n.contains("json") || n.contains("xml") || n.contains("regex") || n.contains("base64") || n.contains("unicode") || n.contains("slug") -> "Developer"
            n.contains("calc") || n.contains("kalkulator") || n.contains("converter") || n.contains("konversi") || n.contains("aspect") -> "Calculator"
            else -> "Utility"
        }
    }

    // Registry update tool: setiap kali versi tool berubah, tool otomatis masuk ke bagian "Terbaru".
    // Untuk rilis berikutnya cukup naikkan versi pada entry terkait.
    internal val toolUpdateCatalog = linkedMapOf(
        "githubzip" to "2.30.0",
        "webhostwifi" to "2.19.8",
        "webproject" to "2.19.8",
        "webeditor" to "2.19.8",
        "reminder" to "2.19.8",
        "espstudio" to "2.19.8",
        "networkstudio" to "2.19.8",
        "clipboard" to "2.19.8",
        "apkanalyzer" to "2.19.8",
        "fileencryption" to "2.20.0", "steganography" to "2.20.0", "passwordanalyzer" to "2.20.0", "breachchecker" to "2.20.0", "securenotes" to "2.20.0", "totpvault" to "2.20.0", "pgp" to "2.20.0", "sshkeygen" to "2.20.0", "certviewer" to "2.20.0", "virusscanner" to "2.20.0", "urlsafety" to "2.20.0"
    )

    internal fun syncToolUpdates() {
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

    internal fun latestUpdatedToolIds(): List<String> {
        val ids = prefs.getString("latest_tool_updates", "")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        return ids.filter { id -> homeToolMap.containsKey(id) }.take(6)
    }

    internal fun latestToolVersion(id: String): String = toolUpdateCatalog[id] ?: "2.19.8"

    internal fun recordRecentTool(id: String) {
        val old = prefs.getString("recent_tools", "")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val updated = (listOf(id) + old.filter { it != id }).take(20)
        prefs.edit().putString("recent_tools", updated.joinToString(",")).apply()
    }

    internal fun latestToolCard(id: String, name: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(9), dp(8), dp(7))
            contentDescription = "$name. Tool terbaru"
        }
        applyInteractiveSurface(card, 18, 2)
        addPressFeedback(card)
        card.setOnClickListener { openToolWithPress(id, card) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(MdiIconView(this@MainActivity).apply {
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

    internal fun mainPyToolCard(id: String, name: String): LinearLayout {
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
        top.addView(TextView(this).apply {
            text = if (isFavorite(id)) "★" else "☆"; textSize = 19f; gravity = Gravity.CENTER
            setTextColor(textMain); setPadding(dp(2),0,dp(2),0)
            contentDescription = if (isFavorite(id)) "Hapus $name dari favorit" else "Tambahkan $name ke favorit"
            setOnClickListener { toggleFavorite(id); text = if (isFavorite(id)) "★" else "☆"; contentDescription = if (isFavorite(id)) "Hapus $name dari favorit" else "Tambahkan $name ke favorit" }
        }, LinearLayout.LayoutParams(dp(30), dp(38)))
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
            setPadding(0, dp(8), 0, 0)
        }, LinearLayout.LayoutParams(-1, dp(40)))
        card.addView(TextView(this).apply {
            text = theme.chip
            textSize = 8.5f
            setTextColor(textMuted)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(-1, dp(15)))
        return card
    }

    internal fun homeHeroCard(): LinearLayout {
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

    internal fun homeChip(textValue: String, active: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
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

    internal fun homeToolCard(id: String, name: String, desc: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
            background = bg(Color.WHITE, 18, Color.rgb(238, 241, 244))
            isClickable = true
            setOnClickListener { if (id == "settings") showSettings() else openTool(id) }
        }
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
            background = bg(Color.rgb(242, 245, 247), 14)
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

    internal fun showFavorites() {
        if (currentPage == "favorites" && content.childCount > 0) { selectBottomNav("favorites"); return }
        clearPage("favorites", false)
        suppressSearch = true
        search.setText("")
        suppressSearch = false
        content.setPadding(dp(12), dp(8), dp(12), dp(18))
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
            empty.addView(subLabel("Tekan ☆ pada kartu tool untuk menyimpannya.", 11f).apply { gravity = Gravity.CENTER })
            content.addView(empty, LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(6) })
            return
        }
        favorites.forEach { (id, name) ->
            content.addView(toolCard(id, name, iconFor(id)).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(70)).apply { bottomMargin = dp(8) }
            })
        }
    }

    internal fun selectBottomNav(name: String) {
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
            item.background = if (selected) bg(if (isDarkTheme) Color.rgb(42,42,46) else Color.rgb(241,244,246), 20) else null
            item.alpha = if (selected) 1f else 0.86f
            item.animate().scaleX(if (selected) 1.02f else 1f).scaleY(if (selected) 1.02f else 1f).setDuration(140).start()
        }
        val labels = listOf(
            R.id.navHomeLabel to (name == "home"),
            R.id.navToolsLabel to (name == "all"),
            R.id.navFavoriteLabel to (name == "favorites"),
            R.id.navSettingsLabel to (name == "settings")
        )
        labels.forEach { (id, selected) -> findViewById<TextView>(id).setTextColor(if (selected) active else inactive) }
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

    internal fun categoryCard(iconName: String, name: String, desc: String, ids: List<String>) {
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

    internal fun showCategory(name: String, ids: List<String>) {
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

    internal fun showAllTools(forceRebuild: Boolean = false) {
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
        val allQuickFilters = listOf("Semua", "Favorit", "Text & Dev", "Security", "Network", "Files")
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
                        val title = when (filter) { "Text & Dev" -> "TEXT & DEV"; "Security" -> "SECURITY"; "Network" -> "NETWORK"; else -> "FILE & APP" }
                        val ids = when (filter) {
                            "Text & Dev" -> listOf("editor","json","jsonformat","xmlformat","yaml","toml","sql","regex","textstat","case","compare","base64","hex","url","unicode","timestamp","uuid")
                            "Security" -> listOf("securitycenter","hash","checksum","password","passwordstrength","hmac","jwt","totp","aes","fileencryption","securenotes","pgp")
                            "Network" -> listOf("network","networkstudio","dns","rdns","ping","traceroute","whois","port","netscanner","publicip","ipinfo","ssl","http","httpheaders","restclient","websocket","wifi")
                            else -> listOf("filemanager","filestudio","zip","githubzip","fileconvert","filesearch","dedupe","storage","apps","apk","apkanalyzer","apkcompare","duplicatefinder","largefilefinder")
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
            "cellphone-cog" to ("SYSTEM" to listOf("devicecenter")),
            "calculator-variant-outline" to ("CALCULATOR" to listOf(
                "number", "pivotcalc", "dividercalc", "dcacalc", "pwmcalc", "spritecalc", "installcalc", "powercalc", "aspectcalc", "pphcalc"
            )),
            "file-document-edit-outline" to ("TEXT & DEV" to listOf(
                "editor", "json", "base64", "url", "regex", "uuid", "textstat", "case", "compare", "slug", "lorem", "token", "random", "hex", "base32", "timestamp", "unicode", "urlparser", "mime", "jsonformat", "xmlformat", "uuidbatch", "base64file", "textreplace", "wordfreq", "markdown", "sql", "yaml", "toml", "cron", "helpbot"
            )),
            "shield-check-outline" to ("SECURITY" to listOf(
                "hash", "password", "passwordstrength", "jwt", "hmac", "totp", "aes", "checksum", "securitycenter", "fileencryption", "steganography", "passwordanalyzer", "breachchecker", "securenotes", "totpvault", "pgp", "sshkeygen", "certviewer", "virusscanner", "urlsafety"
            )),
            "web" to ("NETWORK" to listOf(
                "dns", "rdns", "port", "publicip", "ping", "ipinfo", "ssl", "http", "httpheaders", "restclient", "websocket", "network", "networkcenter", "wifi", "webhostwifi"
            )),
            "palette-outline" to ("MEDIA & COLOR" to listOf("color", "imagestudio")),
            "qrcode" to ("QR / OCR" to listOf("qr")),
            "cash-multiple" to ("FINANCE" to listOf("financereader", "financedashboard")),
            "toolbox-outline" to ("UTILITY" to listOf("reminder", "stopwatch", "timer")),
            "view-grid-outline" to ("LAINNYA" to listOf("workspace", "plugincenter", "customtools", "studiocenter"))
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

    internal fun addGroupedToolSection(titleText: String, ids: List<String>) {
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

    internal fun renderToolList(items: List<Pair<String,String>>) {
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

    internal fun filterCurrent(q: String) {
        if (currentPage != "home" && currentPage != "favorites" && currentPage != "all" && currentPage != "Kalkulator Lengkap") return
        val query = q.trim().toLowerCase(Locale.getDefault())
        if (currentPage == "home") {
            // Do not call clearPage() here. It saves a navigation snapshot, changes
            // page state and rebuilds surrounding views while the IME is typing.
            // Only the content workspace is replaced. The search EditText therefore
            // keeps focus, composing state and cursor position.
            if (query.isEmpty()) { showHome(homeFilter); return }

            content.removeAllViews()
            content.setPadding(dp(12), dp(8), dp(12), dp(18))
            content.addView(label("Hasil pencarian", 22f, true))
            content.addView(subLabel("Mencari: $q", 12f))
            val keepIds = homeToolSearchIndex.asSequence()
                .filter { (_, lowerName) -> lowerName.contains(query) }
                .map { it.first }
                .toList()
            val keep = keepIds.mapNotNull { id -> homeToolMap[id]?.let { id to it } }
            renderToolListHomeSearch(keep)
        } else if (currentPage == "favorites") {
            val favorites = favoriteToolIds().mapNotNull { id -> homeToolMap[id]?.let { id to it } }
            if (query.isEmpty()) {
                showFavorites()
                return
            }
            content.removeAllViews()
            content.setPadding(dp(12), dp(8), dp(12), dp(18))
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
            allCalc.filter{it.first.toLowerCase(Locale.getDefault()).contains(query)}.forEach{content.addView(toolCard(it.second,it.first,iconFor(it.second)).apply{layoutParams=LinearLayout.LayoutParams(-1,dp(70)).apply{bottomMargin=dp(7)}})}
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

    internal fun renderToolListHomeSearch(items: List<Pair<String,String>>) {
        items.forEach { (id,name) -> content.addView(toolCard(id,name,iconFor(id)).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(70)).apply { bottomMargin = dp(7) } }) }
        if (items.isEmpty()) content.addView(subLabel("Tidak ada tool yang cocok.", 13f))
    }

    internal fun showSettings() {
        if (currentPage == "settings" && content.childCount > 0) { selectBottomNav("settings"); return }
        clearPage("settings", false)
        content.setPadding(dp(12), dp(8), dp(12), dp(18))

        settingsSection("Tampilan")
        val themeMode = prefs.getString("theme_mode", "system") ?: "system"
        val themeLabel = when (themeMode) {
            "dark" -> "Gelap"
            "light" -> "Terang"
            else -> "Ikuti Sistem"
        }
        content.addView(settingRowClickable("Tema", themeLabel, "Pilih tampilan terang, gelap, atau mengikuti sistem", "theme-light-dark") {
            val modes = listOf("system" to "Ikuti Sistem", "light" to "Terang", "dark" to "Gelap")
            val current = modes.indexOfFirst { it.first == (prefs.getString("theme_mode", "system") ?: "system") }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("Tema Aplikasi")
                .setSingleChoiceItems(modes.map { it.second }.toTypedArray(), current) { dialog, which ->
                    prefs.edit().putString("theme_mode", modes[which].first).apply()
                    applySystemTheme()
                    applyUiColors()
                    dialog.dismiss()
                    // Re-render the current page so cards, text, buttons, and
                    // backgrounds change immediately instead of only the toolbar.
                    showSettings()
                }.setNegativeButton("Batal", null).show()
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
        content.addView(settingRowClickable("Aktivitas Terakhir", if (prefs.getBoolean("show_recent_activity", false)) "Ditampilkan" else "Disembunyikan", "Tampilkan aktivitas terbaru di Beranda", "history") {
            prefs.edit().putBoolean("show_recent_activity", !prefs.getBoolean("show_recent_activity", false)).apply(); showSettings()
        })
        content.addView(settingRowClickable("Akses Cepat", if (prefs.getBoolean("show_quick_access", false)) "Ditampilkan" else "Disembunyikan", "Tampilkan akses cepat di Beranda", "view-grid-plus-outline") {
            prefs.edit().putBoolean("show_quick_access", !prefs.getBoolean("show_quick_access", false)).apply(); showSettings()
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
        content.addView(settingRowClickable("Penyimpanan Aplikasi", appDataStorageText(), "database", "Lihat ukuran data MyTools dan cache yang digunakan.") { showAppDataStorageDialog() })
        content.addView(settingRowClickable("Bersihkan Cache", formatBytes(cacheDirSize()), "broom-outline", "Hapus cache sementara tanpa menghapus pengaturan atau data penting.") { confirmClearAppCache() })
        content.addView(settingRowClickable("Reset Pengaturan", "Hanya preferensi", "restore-settings", "Kembalikan preferensi MyTools ke kondisi awal tanpa menghapus file kerja.") { confirmResetPreferences() })

        settingsSection("Aplikasi")
        val appVersion = BuildConfig.VERSION_NAME
        content.addView(settingRowClickable("Pembaruan Aplikasi", "Versi $appVersion", "update", "Periksa pembaruan APK dari sistem update MyTools.") {
            runCatching { AppUpdateManager(this).checkForUpdate() }.onFailure { toast("Pemeriksaan update gagal: ${it.message}") }
        })
        content.addView(settingRowClickable("Tentang", "GITLS $appVersion", "information-outline") {
            AlertDialog.Builder(this).setTitle("GITLS $appVersion").setMessage("Utility Suite • Web Hosting Wi-Fi • Network • Developer • ESP • Finance • Image • Color\n\nPembaruan ini menambahkan browser repository GitHub, tampilan folder/file, penghapusan dengan verifikasi ganda, dan tombol hapus semua file.").setPositiveButton("OK", null).show()
        })
    }

    internal fun appDataStorageText(): String = formatBytes(appDataSize())

    internal fun appDataSize(): Long {
        return directorySize(filesDir) + directorySize(cacheDir) + directorySize(codeCacheDir)
    }

    internal fun cacheDirSize(): Long = directorySize(cacheDir) + directorySize(codeCacheDir)

    internal fun directorySize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return runCatching { file.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
    }

    internal fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    internal fun showAppDataStorageDialog() {
        val data = directorySize(filesDir)
        val cache = cacheDirSize()
        val total = data + cache
        AlertDialog.Builder(this)
            .setTitle("Penyimpanan Aplikasi")
            .setMessage("Data aplikasi: ${formatBytes(data)}\nCache: ${formatBytes(cache)}\nTotal: ${formatBytes(total)}\n\nFile kerja di dalam penyimpanan aplikasi tidak akan dihapus dari menu ini.")
            .setPositiveButton("OK", null)
            .show()
    }

    internal fun confirmClearAppCache() {
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

    internal fun confirmResetPreferences() {
        AlertDialog.Builder(this)
            .setTitle("Reset Pengaturan?")
            .setMessage("Tema, layout Beranda, preferensi tampilan, dan pengaturan lokal akan dikembalikan ke awal. File kerja dan database keuangan tidak dihapus.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Reset") { _, _ ->
                val keepKeys = setOf("history", "recent_files", "favorite_tools", "home_tools", "latest_tool_updates", "recent_tools", "tool_update_versions")
                val current = prefs.all
                val editor = prefs.edit().clear()
                current.forEach { (key, value) ->
                    if (key in keepKeys) {
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

    internal fun settingRowClickable(name: String, desc: String, iconName: String, action: () -> Unit): View {
        return settingRowClickable(name, "", desc, iconName, action)
    }

    internal fun settingRowClickable(name: String, value: String, desc: String, iconName: String, action: () -> Unit): View {
        // Pengaturan memakai baris ringkas dengan ikon dalam tile rounded agar
        // daftar lebih bervariasi, mudah dipindai, dan tidak terasa seperti blok besar berulang.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = bg(if (isDarkTheme) panel2 else Color.rgb(250,250,251), 20, line)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        val iconTile = FrameLayout(this).apply {
            background = bg(if (isDarkTheme) panel else Color.rgb(239,242,245), 15, line)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(13) }
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
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
            minimumHeight = dp(76)
        }
    }

    internal fun settingsSection(text: String) {
        content.addView(TextView(this).apply {
            this.text = text.toUpperCase(Locale.getDefault())
            textSize = 11f
            letterSpacing = 0.06f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textMuted)
            setPadding(dp(4), dp(18), dp(4), dp(8))
        })
    }

    internal fun settingRow(name: String, value: String, desc: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = bg(panel2, 16)
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

    internal fun iconFor(id: String): String = when (id) {
        "filemanager" -> "folder-outline"
        "recentfiles" -> "history"
        "backuprestore" -> "backup-restore"
        "workspace" -> "view-dashboard-outline"
        "plugincenter" -> "puzzle-outline"
        "customtools" -> "tune-variant"
        "studiocenter" -> "palette-swatch-outline"
        "editor" -> "file-document-edit-outline"
        "reminder" -> "bell-outline"
        "zip" -> "folder-zip-outline"
        "githubzip" -> "rocket-launch-outline"
        "wifi" -> "wifi"
        "devicecenter" -> "cellphone-cog"
        "json" -> "code-json"
        "hash" -> "pound"
        "base64" -> "numeric-4-box-outline"
        "url" -> "link-variant"
        "regex" -> "regex"
        "uuid" -> "identifier"
        "color" -> "palette-outline"
        "number" -> "calculator-variant-outline"
        "history" -> "history"
        "uicolorcalc" -> "palette-swatch-variant"
        "basiccalc" -> "calculator"
        "scicalc" -> "function-variant"
        "percentcalc" -> "percent-outline"
        "fractioncalc" -> "division"
        "ratiocalc" -> "scale-balance"
        "unitcalc" -> "ruler"
        "areacalc" -> "vector-square"
        "volumecalc" -> "cube-outline"
        "speedcalc" -> "speedometer"
        "timecalc" -> "clock-outline"
        "datecalc" -> "calendar-range-outline"
        "loancalc" -> "bank-outline"
        "fuelcalc" -> "gas-station-outline"
        "pivotcalc" -> "chart-areaspline"
        "dividercalc" -> "sine-wave"
        "dcacalc" -> "finance"
        "pwmcalc" -> "pulse"
        "spritecalc" -> "grid"
        "installcalc" -> "cash-multiple"
        "powercalc" -> "flash-outline"
        "aspectcalc" -> "aspect-ratio"
        "pphcalc" -> "percent"
        "financereader" -> "wallet-outline"
        "financedashboard" -> "chart-line"
        "securitycenter" -> "shield-check-outline"
        "helpbot" -> "robot-outline"
        "riskcalc" -> "scale-balance"
        "compoundcalc" -> "chart-timeline-variant"
        "margincalc" -> "cash-register"
        "discountcalc" -> "tag-outline"
        "datacalc" -> "database-outline"
        "pressurecalc" -> "gauge"
        "worktimecalc" -> "briefcase-clock-outline"
        "basecalc" -> "numeric"
        "equationcalc" -> "sigma"
        "textstat" -> "format-list-numbered"
        "case" -> "format-letter-case"
        "dedupe" -> "content-duplicate"
        "compare" -> "compare"
        "slug" -> "link-box-variant-outline"
        "lorem" -> "format-align-left"
        "password" -> "form-textbox-password"
        "token" -> "key-variant"
        "jwt" -> "badge-account-outline"
        "hmac" -> "shield-key-outline"
        "totp" -> "clock-check-outline"
        "aes" -> "lock-outline"
        "random" -> "dice-multiple"
        "checksum" -> "file-check-outline"
        "hex" -> "hexadecimal"
        "base32" -> "numeric"
        "dns" -> "dns"
        "rdns" -> "lan"
        "port" -> "lan-connect"
        "publicip" -> "ip-outline"
        "ping" -> "access-point-network"
        "ipinfo" -> "ip-network"
        "ssl" -> "certificate-outline"
        "apk" -> "android"
        "qr" -> "qrcode"
        "fileconvert" -> "swap-horizontal"
        "system" -> "cog-outline"
        "http" -> "web"
        "webhostwifi" -> "wifi-star"
        "filehashcompare" -> "file-compare"
        "markdown" -> "language-markdown-outline"
        "sql" -> "database-search-outline"
        "yaml" -> "file-code-outline"
        "toml" -> "file-cog-outline"
        "cron" -> "calendar-clock-outline"
        "passwordstrength" -> "shield-lock-outline"
        "fileencryption" -> "file-lock-outline"
        "steganography" -> "image-lock-outline"
        "passwordanalyzer" -> "shield-search"
        "breachchecker" -> "shield-alert-outline"
        "securenotes" -> "note-edit-outline"
        "totpvault" -> "shield-key-outline"
        "pgp" -> "key-chain-variant"
        "sshkeygen" -> "key-plus"
        "certviewer" -> "certificate-outline"
        "virusscanner" -> "bug-outline"
        "urlsafety" -> "link-lock"
        "stopwatch" -> "timer-outline"
        "timer" -> "timer-sand"
        "imagestudio" -> "image-multiple-outline"
        "timestamp" -> "clock-time-four-outline"
        "unicode" -> "format-letter-case-upper"
        "urlparser" -> "link-variant"
        "mime" -> "file-document-outline"
        "jsonformat" -> "code-braces"
        "xmlformat" -> "xml"
        "uuidbatch" -> "identifier"
        "base64file" -> "file-code-outline"
        "httpheaders" -> "format-header-1"
        "textreplace" -> "find-replace"
        "wordfreq" -> "counter"
        "deviceinfo" -> "cellphone-information"
        "storage" -> "database"
        "apps" -> "apps"
        "network" -> "network"
        "battery" -> "battery-high"
        "filesearch" -> "file-search"
        "clipboard" -> "clipboard-text-outline"
        "esp" -> "chip"
        "espdiscover" -> "radar"
        "ledstudio" -> "led-strip"
        "espdevice" -> "devices"
        "espgpio" -> "expansion-card-variant"
        "espsensor" -> "thermometer"
        "espwifi" -> "router-wireless"
        "espota" -> "upload-network"
        "esphttp" -> "web-box"
        "espmqtt" -> "message-cog-outline"
        "espusb" -> "usb-port"
        "espserial" -> "serial-port"
        "iotdashboard" -> "view-dashboard-outline"
        "espstudio" -> "tools"
        "visualwiring" -> "vector-polyline"
        "ocr" -> "ocr"
        "unitconverter" -> "swap-horizontal-bold"
        "apkanalyzer" -> "android-studio"
        "netscanner" -> "magnify-scan"
        "webproject" -> "web-plus"
        "webeditor" -> "language-html5"
        "networkstudio" -> "lan"
        "developerstudio" -> "code-tags"
        "filestudio" -> "folder-multiple-outline"
        "imagestudio" -> "image-multiple-outline"
        "colorstudio" -> "palette"
        "systemstudio" -> "cellphone-cog"
        "financestudio" -> "cash-multiple"
        "utilitystudio" -> "toolbox-outline"
        "whois" -> "account-search-outline"
        "traceroute" -> "routes"
        "subnetcalc" -> "ip-network-outline"
        "restclient" -> "api"
        "websocket" -> "connection"
        "networkcenter" -> "lan-connect"
        "systemcenter" -> "view-dashboard-outline"
        "apkcompare" -> "compare-horizontal"
        "duplicatefinder" -> "file-multiple-outline"
        "largefilefinder" -> "file-search-outline"
        else -> "tools"
    }
    // Shared full-width input used by the tools.
    // Normal fields are deliberately taller and multiline fields get substantially
    // more vertical space so text is edited in a real work area instead of a tiny box.
    internal fun edit(hint: String = "", multiline: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        textSize = 16f
        setTextColor(textMain)
        setHintTextColor(textMuted)
        setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_MD), dp(Ds.SPACE_LG), dp(Ds.SPACE_MD))
        val theme = visualTheme()
        background = bg(theme.surface, Ds.RADIUS_MD, theme.border)
        isSingleLine = !multiline
        isFocusable = true
        isFocusableInTouchMode = true
        if (hint.isNotBlank()) contentDescription = hint
        // Focus state jelas: border 2dp memakai warna teks utama.
        setOnFocusChangeListener { view, focused ->
            view.background = bg(
                theme.surface, Ds.RADIUS_MD,
                if (focused) theme.button else theme.border
            ).also { d -> if (focused) d.setStroke(dp(2), theme.button) }
        }
        if (multiline) {
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        } else {
            minLines = 1
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT
        }
        // Tinggi responsif: ikut tinggi layar (HP kecil s/d besar, portrait/landscape).
        val multilineHeight = (resources.displayMetrics.heightPixels * 0.30f).toInt()
            .coerceIn(dp(160), dp(300))
        layoutParams = LinearLayout.LayoutParams(
            -1,
            if (multiline) multilineHeight else -2
        ).apply { bottomMargin = dp(Ds.SPACE_MD) }
        if (!multiline) minHeight = dp(56)
    }

    internal fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 14f
        minHeight = dp(Ds.TOUCH_MIN)
        setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_XS), dp(Ds.SPACE_LG), dp(Ds.SPACE_XS))
        setStateListAnimator(null)
        isAllCaps = false
        letterSpacing = 0.01f
        contentDescription = text
        isFocusable = true
        // Hierarki otomatis: aksi utama terisi, aksi pendukung (Salin/Hapus/Reset...) outline.
        if (isSecondaryAction(text)) styleAsSecondary(this) else styleAsPrimary(this)
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (animationsEnabled()) {
                animate().scaleX(0.98f).scaleY(0.98f).setDuration(Ds.ANIM_FAST / 2)
                    .withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(Ds.ANIM_FAST).start() }.start()
            }
            onClick()
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(Ds.SPACE_SM) }
    }

    // Shared modern utility layout. It keeps the monochrome identity while giving
    // each tool a clearer visual hierarchy instead of the old input-button-output stack.
    internal fun toolHeader(titleText: String, description: String, icon: String = "•"): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = bg(panel2, 18, line)
        }
        val iconView = MdiIconView(this).apply {
            setIconName(resolveToolIcon(titleText, icon)); setIconSize(22f); setTextColor(textMain)
            background = bg(panel, 14, line)
        }
        box.addView(iconView, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(titleText, 18f, true))
        texts.addView(subLabel(description, 11f))
        box.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return box
    }

    // Setiap tool wajib punya ikon: pakai nama ikon jika valid, kalau tidak cari dari nama tool.
    internal fun resolveToolIcon(titleText: String, icon: String): String {
        if (MdiGlyphs.has(icon)) return icon
        val exact = homeTools.firstOrNull { it.second.equals(titleText, true) }?.first
        if (exact != null) return iconFor(exact)
        val fuzzy = homeTools.firstOrNull { titleText.contains(it.second, true) || it.second.contains(titleText, true) }?.first
        return if (fuzzy != null) iconFor(fuzzy) else "tools"
    }

    internal fun toolSection(titleText: String, subtitle: String = ""): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(8), dp(2), dp(5)) }
        box.addView(label(titleText, 12f, true))
        if (subtitle.isNotBlank()) box.addView(subLabel(subtitle, 10f))
        return box
    }

    internal fun toolStatus(textValue: String, positive: Boolean = false): TextView = TextView(this).apply {
        val dotColor = statusColor(if (positive) Ds.State.SUCCESS else Ds.State.INFO)
        val sb = android.text.SpannableStringBuilder("●  $textValue")
        sb.setSpan(android.text.style.ForegroundColorSpan(dotColor), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text = sb
        textSize = 13f
        setTextColor(textMain)
        minHeight = dp(Ds.TOUCH_MIN)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_MD), dp(Ds.SPACE_LG), dp(Ds.SPACE_MD))
        background = bg(if (positive) panel else panel2, Ds.RADIUS_MD, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(Ds.SPACE_SM) }
    }

    internal fun addToolHeader(titleText: String, description: String, icon: String = "•") {
        // Intro card bersama: tidak mengulang judul toolbar, tetapi memberi konteks
        // sehingga halaman tool tidak terasa seperti form kosong.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = bg(panel2, Ds.RADIUS_LG, line)
            contentDescription = "Tentang $titleText"
        }
        val iconView = MdiIconView(this).apply {
            setIconName(resolveToolIcon(titleText, icon))
            setIconSize(21f)
            setTextColor(textMain)
            background = bg(panel, Ds.RADIUS_MD, line)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(iconView, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textBox.addView(subLabel(description, 12f).apply { setTextColor(textMain) })
        textBox.addView(subLabel("Lokal di perangkat • Hasil dapat diproses kembali", 10f))
        card.addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    // V4: workspace helpers for complex tools. These keep domain logic untouched while
    // giving network/file/security/system tools a consistent mobile workspace hierarchy.
    internal fun toolWorkspace(titleText: String, description: String, icon: String = "tools") {
        // Header & control bar dihapus; Riwayat/Info ada di menu titik tiga.
    }

    internal fun toolWorkspaceSection(titleText: String, subtitle: String = "") {
        content.addView(toolSection(titleText, subtitle))
    }

    internal fun compactButtonRow(vararg items: Pair<String, () -> Unit>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        items.forEachIndexed { index, item ->
            val b = button(item.first, item.second)
            if (index == 0) styleAsPrimary(b) else styleAsSecondary(b)
            row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) leftMargin = dp(Ds.SPACE_SM)
            })
        }
        return row
    }

    // Shared controls for every tool: status, history and a contextual help panel.
    // Domain-specific controls remain inside each tool so the layout stays fast on mobile.
    internal fun toolControlBar(name: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = bg(panel2, 14, line)
        }
        val status = TextView(this).apply {
            text = "●  Siap digunakan"
            textSize = 11f
            setTextColor(textMain)
            gravity = Gravity.CENTER_VERTICAL
        }
        box.addView(status, LinearLayout.LayoutParams(0, dp(40), 1f))
        fun actionText(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
            this.text = text
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(textMain)
            background = bg(panel, 10, line)
            setPadding(dp(9), 0, dp(9), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        box.addView(actionText("Riwayat") { historyTool() }, LinearLayout.LayoutParams(dp(76), dp(36)).apply { rightMargin = dp(5) })
        box.addView(actionText("Info") {
            AlertDialog.Builder(this)
                .setTitle(name)
                .setMessage(toolDescription(name))
                .setPositiveButton("OK", null)
                .show()
        }, LinearLayout.LayoutParams(dp(54), dp(36)))
        return box
    }

    // Menu titik tiga kanan atas untuk semua tool: Riwayat, Info, dan Tentang.
    internal fun showToolMenu(anchor: View) {
        val name = currentPage
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Riwayat")
        pm.menu.add(0, 2, 1, "Info")
        pm.menu.add(0, 3, 2, "Tentang GITLS")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> historyTool()
                2 -> AlertDialog.Builder(this)
                    .setTitle(name)
                    .setMessage(toolDescription(name))
                    .setPositiveButton("OK", null)
                    .show()
                3 -> showAbout()
            }
            true
        }
        pm.show()
    }

    internal fun toolDescription(name: String): String = when {
        name.contains("JSON", true) -> "Validasi, rapikan, kecilkan, dan proses JSON. Hasil dapat disalin atau dibagikan."
        name.contains("Converter", true) -> "Pilih input, tentukan format tujuan, proses file, lalu buka atau bagikan hasil."
        name.contains("Network", true) || name in setOf("Ping", "DNS Lookup", "Reverse DNS", "Port Checker", "SSL Certificate", "REST / API Client", "WebSocket Client", "Network Center") -> "Tool jaringan untuk diagnosis, validasi koneksi, API, WebSocket, dan pemeriksaan host."
        name.contains("ESP", true) || name.contains("IoT", true) -> "Koneksi, kontrol, monitoring, diagnosis, dan pengujian perangkat ESP/IoT."
        name.contains("Finance", true) || name.contains("Keuangan", true) -> "Pencatatan lokal, transaksi, ringkasan, anggaran, insight, dan ekspor."
        name.contains("Calculator", true) || name.contains("Kalkulator", true) || name in setOf("Voltage Divider", "Pivot Point", "PWM & Duty Cycle") -> "Masukkan parameter, hitung, lalu salin atau bagikan hasil. Input divalidasi sebelum perhitungan."
        name.contains("File", true) || name.contains("ZIP", true) -> "Kelola, baca, konversi, kompres, atau ekstrak file dengan hasil yang dapat diproses kembali."
        else -> "Tool MyTools dengan fungsi utama, validasi input, hasil, salin, bagikan, dan riwayat lokal bila relevan."
    }

    internal fun sanitizeSensitiveHistory() {
        val arr = runCatching { JSONArray(prefs.getString("history", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val cleaned = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (!isSensitiveTool(item.optString("tool"))) cleaned.put(item)
        }
        if (cleaned.length() != arr.length()) prefs.edit().putString("history", cleaned.toString()).apply()
    }

    internal fun isSensitiveTool(name: String = currentPage): Boolean = name in setOf(
        "Password Generator", "AES Encrypt / Decrypt", "HMAC Generator", "TOTP Generator",
        "Token Acak", "Random Bytes", "JWT Decoder"
    )

    internal fun output(text: String) {
        val safe = text.ifBlank { "(kosong)" }
        if (!isSensitiveTool()) saveHistory(currentPage, safe)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_MD), dp(Ds.SPACE_LG), dp(Ds.SPACE_SM))
            background = bg(panel2, Ds.RADIUS_LG, line)
            contentDescription = "Hasil $currentPage"
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = MdiIconView(this).apply {
            setIconName(Ds.stateIcon(Ds.State.SUCCESS))
            setIconSize(18f)
            setTextColor(statusColor(Ds.State.SUCCESS))
            background = bg(panel, Ds.RADIUS_SM, line)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        heading.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(Ds.SPACE_SM) })
        val headingText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headingText.addView(label("Hasil", 14f, true))
        headingText.addView(subLabel("${currentPage} • selesai", 12f))
        heading.addView(headingText, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)

        // Output panjang/berbaris banyak (kode, log, JSON) memakai monospace agar rapi.
        val looksLikeCode = safe.contains('\n') || safe.startsWith("{") || safe.startsWith("[")
        val result = TextView(this).apply {
            this.text = safe
            textSize = if (looksLikeCode) 13f else 14f
            if (looksLikeCode) typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(textMain)
            setPadding(dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD))
            background = bg(panel, Ds.RADIUS_MD, line)
            setTextIsSelectable(true)
            gravity = Gravity.TOP or Gravity.START
        }
        card.addView(result, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Ds.SPACE_SM); bottomMargin = dp(Ds.SPACE_SM) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun resultAction(textValue: String, iconName: String, primary: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
            this.text = textValue
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            minHeight = dp(Ds.TOUCH_MIN)
            isClickable = true
            isFocusable = true
            contentDescription = textValue
            if (primary) styleAsPrimary(this) else styleAsSecondary(this)
            setOnClickListener { onClick() }
        }
        actions.addView(resultAction("Salin", "content-copy", true) { copyText(safe) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(Ds.SPACE_XS) })
        actions.addView(resultAction("Bagikan", "share-variant", false) { shareText(safe) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(Ds.SPACE_XS); rightMargin = dp(Ds.SPACE_XS) })
        actions.addView(resultAction("Bersihkan", "delete-outline", false) { content.removeView(card) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(Ds.SPACE_XS) })
        card.addView(actions)
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Ds.SPACE_SM); bottomMargin = dp(Ds.SPACE_SM) })
    }

    internal fun copyText(value:String) {
        val cm=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("MyTools",value)); toast("Hasil disalin")
    }

    internal fun shareText(value:String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,value) },"Bagikan hasil"))
    }

    internal fun saveHistory(tool:String, result:String) {
        val arr=runCatching { JSONArray(prefs.getString("history","[]") ?: "[]") }.getOrElse { JSONArray() }
        val item=JSONObject().apply { put("time",System.currentTimeMillis()); put("tool",tool); put("result",result.take(2000)) }
        val next=JSONArray(); next.put(item)
        for(i in 0 until minOf(arr.length(),49)) next.put(arr.getJSONObject(i))
        prefs.edit().putString("history",next.toString()).apply()
    }

    internal fun historyTool() {
        clearPage("History Center")
        content.addView(label("History Center",22f,true))
        content.addView(subLabel("Riwayat tool, hasil, dan aktivitas lokal • maksimal 50 hasil",12f))
        val arr=runCatching { JSONArray(prefs.getString("history","[]") ?: "[]") }.getOrElse { JSONArray() }
        if(arr.length()==0) { content.addView(subLabel("Belum ada riwayat.",13f)); return }
        for(i in 0 until arr.length()) {
            val o=arr.getJSONObject(i); val whenText=SimpleDateFormat("dd/MM HH:mm",Locale.getDefault()).format(Date(o.optLong("time")))
            val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=bg(panel2,14,line);setOnClickListener{copyText(o.optString("result"))}}
            card.addView(label("${o.optString("tool")} • $whenText",12f,true)); card.addView(subLabel(o.optString("result"),13f))
            content.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)})
        }
    }



    // ===================== 2.14 FINANCE DASHBOARD =====================

    // financeDashboardTool() moved to feature/finance/FinanceScreen.kt

    internal fun profilePrefs() = getSharedPreferences("gitls_profile", MODE_PRIVATE)

    internal fun showLocalRegistration() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        val name = EditText(this).apply {
            hint = "Nama"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val username = EditText(this).apply {
            hint = "Username (opsional)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        box.addView(name, LinearLayout.LayoutParams(-1, dp(52)))
        box.addView(username, LinearLayout.LayoutParams(-1, dp(52)))
        AlertDialog.Builder(this)
            .setTitle("Buat profil lokal")
            .setMessage("Profil ini disimpan di perangkat. Tidak membuat akun online.")
            .setView(box)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val n = name.text.toString().trim()
                val u = username.text.toString().trim()
                if (n.isEmpty()) {
                    toast("Nama belum diisi")
                    return@setPositiveButton
                }
                profilePrefs().edit()
                    .putString("name", n)
                    .putString("username", u)
                    .putBoolean("registered", true)
                    .apply()
                toast("Profil lokal dibuat")
                enterApp()
            }.show()
    }

    internal fun showProfile() {
        closeDrawer()
        clearPage("profile", true)
        content.setPadding(dp(16), dp(8), dp(16), dp(28))
        val prefs = profilePrefs()
        val name = prefs.getString("name", "Pengguna GITLS") ?: "Pengguna GITLS"
        val username = prefs.getString("username", "") ?: ""
        val registered = prefs.getBoolean("registered", false)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, dp(18))
        }
        header.addView(TextView(this).apply {
            text = if (registered) name.take(1).uppercase(Locale.getDefault()) else "G"
            gravity = Gravity.CENTER
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(25, 25, 28))
            }
        }, LinearLayout.LayoutParams(dp(76), dp(76)))
        header.addView(label(name, 21f, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        header.addView(subLabel(if (registered && username.isNotEmpty()) "@$username" else "Guest Account", 12f).apply { gravity = Gravity.CENTER })
        content.addView(header)

        val edit = button(if (registered) "Edit Profil" else "Buat Profil Lokal") {
            showLocalRegistration()
        }
        content.addView(edit)

        sectionTitle("Akun")
        content.addView(settingRow("Status", if (registered) "Profil lokal aktif" else "Guest", "Data profil tersimpan hanya di perangkat."))
        content.addView(settingRow("Username", if (username.isEmpty()) "Belum diatur" else "@$username", "Nama pengguna lokal."))

        sectionTitle("Aktivitas")
        content.addView(settingRow("Tools", "Tersedia", "Semua tool aplikasi tetap dapat digunakan."))
        content.addView(settingRow("Favorit", "Tersimpan lokal", "Daftar favorit mengikuti penyimpanan aplikasi."))
        content.addView(settingRow("Riwayat", "Tersedia", "Riwayat mengikuti tool yang menyediakannya."))

        sectionTitle("Preferensi")
        content.addView(settingRowClickable("Pengaturan", "Tema, animasi, dan opsi aplikasi", "", "cog-outline") { showSettings() })
        content.addView(settingRowClickable("Backup & Restore", "Kelola data aplikasi yang mendukung backup", "", "backup-restore") { showSettings() })

        sectionTitle("Data Profil")
        content.addView(button("Hapus Profil Lokal") {
            AlertDialog.Builder(this).setTitle("Hapus profil lokal?")
                .setMessage("Profil lokal akan dihapus dari perangkat. Tool dan aplikasi tidak ikut dihapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus") { _, _ ->
                    profilePrefs().edit().clear().apply()
                    toast("Profil lokal dihapus")
                    showProfile()
                }.show()
        })
    }

    internal fun toggleDrawer() {
        if (drawerOpen) closeDrawer() else openDrawer()
    }

    internal fun closeDrawer() {
        drawerOverlay?.let { rootFrame.removeView(it) }
        drawerOverlay = null
        drawerOpen = false
    }

    internal fun openDrawer() {
        if (drawerOpen || mainContainer.visibility != View.VISIBLE) return
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            isClickable = true
            setOnClickListener { closeDrawer() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28) + sysTopInset, dp(14), dp(18) + sysBottomInset)
            background = GradientDrawable().apply {
                setColor(if (isDarkTheme) Color.rgb(20, 21, 24) else Color.WHITE)
                cornerRadii = floatArrayOf(0f,0f,dp(22).toFloat(),dp(22).toFloat(),dp(22).toFloat(),dp(22).toFloat(),0f,0f)
            }
            elevation = dp(12).toFloat()
            isClickable = true
        }
        overlay.addView(panel, FrameLayout.LayoutParams(dp(310), -1).apply { gravity = Gravity.START })
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0,0,0,dp(20)) }
        head.addView(TextView(this).apply { text = "G"; gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.WHITE); background = GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(Color.rgb(30,30,34)) } }, LinearLayout.LayoutParams(dp(48),dp(48)))
        head.addView(LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),0,0,0); addView(label("GITLS",18f,true)); addView(subLabel("Semua alat dalam satu aplikasi",11f)) }, LinearLayout.LayoutParams(0,-2,1f))
        head.addView(TextView(this).apply { text="×"; textSize=28f; gravity=Gravity.CENTER; setTextColor(textMain); setOnClickListener{closeDrawer()} }, LinearLayout.LayoutParams(dp(42),dp(42)))
        panel.addView(head)

        val items = listOf(
            Triple("Beranda", "home", "home-outline"),
            Triple("Semua Tools", "all", "view-grid"),
            Triple("Favorit", "favorites", "star"),
            Triple("Riwayat", "history", "history"),
            Triple("Profil", "profile", "account-outline"),
            Triple("Pengaturan", "settings", "cog-outline")
        )
        items.forEachIndexed { index, (name, page, icon) ->
            val row = drawerRow(name, icon) {
                closeDrawer()
                when(page) {
                    "home" -> navigateRoot("home") { showHome() }
                    "all" -> navigateRoot("all") { showAllTools() }
                    "favorites" -> navigateRoot("favorites") { showFavorites() }
                    "history" -> showHistoryPage()
                    "profile" -> showProfile()
                    "settings" -> navigateRoot("settings") { showSettings() }
                }
            }
            // Beri ruang antar-item agar drawer tidak terasa padat.
            panel.addView(row, LinearLayout.LayoutParams(-1, dp(58)).apply {
                bottomMargin = if (index == items.lastIndex) dp(14) else dp(10)
            })
        }

        // Spacer fleksibel menjaga kelompok bantuan tetap berada di bagian bawah drawer.
        panel.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Kelompok sekunder dipisahkan dari navigasi utama.
        panel.addView(drawerRow("Tentang GITLS", "information-outline") { closeDrawer(); showAbout() },
            LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(10) })
        panel.addView(drawerRow("Bantuan", "help-circle-outline") { closeDrawer(); showHelpPage() },
            LinearLayout.LayoutParams(-1, dp(58)))
        rootFrame.addView(overlay, FrameLayout.LayoutParams(-1,-1))
        drawerOverlay = overlay
        drawerOpen = true
    }

    internal fun drawerRow(textValue: String, icon: String, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            minimumHeight = dp(58)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            background = rippleBg(if (isDarkTheme) panel2 else Color.WHITE, 16, line)
        }
        row.addView(
            MdiIconView(this).apply {
                setIconName(icon)
                setIconSize(22f)
                setTextColor(textMain)
            },
            LinearLayout.LayoutParams(dp(40), dp(58)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        row.addView(
            label(textValue, 15f, false),
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dp(6)
            }
        )
        return row
    }

    internal fun showHistoryPage() {
        clearPage("Riwayat", true)
        content.addView(label("Riwayat", 22f, true))
        content.addView(subLabel("Aktivitas terbaru dari tool yang menyimpan riwayat.", 12f))
        content.addView(settingRow("Status", "Lokal", "Riwayat hanya ditampilkan untuk fitur yang memang menyimpannya."))
        content.addView(button("Kembali ke Beranda") { showHome() })
    }

    internal fun showHelpPage() {
        clearPage("Bantuan", true)
        content.addView(label("Bantuan", 22f, true))
        content.addView(subLabel("Panduan singkat penggunaan GITLS.", 12f))
        sectionTitle("Navigasi")
        content.addView(settingRow("☰ Menu", "Navigasi utama", "Gunakan menu kiri atas untuk membuka Profil, Pengaturan, Favorit, dan halaman lainnya."))
        content.addView(settingRow("👤 Profil", "Akun lokal", "Kelola profil lokal perangkat tanpa membuat akun online."))
        content.addView(settingRow("🔎 Search", "Cari tools", "Gunakan pencarian dan geser sedikit untuk Snap Search."))
        content.addView(settingRow("+", "Aksi cepat", "Buka daftar tools yang dapat digunakan."))
        sectionTitle("Masalah umum")
        content.addView(settingRow("Icon kosong", "Perbarui aplikasi", "Versi ini memperbaiki beberapa nama glyph yang tidak tersedia."))
        content.addView(settingRow("Keyboard", "Otomatis", "Bottom navigation disembunyikan ketika keyboard terbuka."))
    }

    internal fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("GITLS ${BuildConfig.VERSION_NAME}")
            .setMessage("Native Android utility suite.\n\nUpdate ${BuildConfig.VERSION_NAME}: transaksi berulang tidak lagi terduplikasi, Color Picker diperbaiki untuk Android 14+, dan splash GitLis tampil utuh.\n\nVersion code: ${BuildConfig.VERSION_CODE}")
            .setPositiveButton("OK", null)
            .show()
    }

    internal fun sectionTitle(textValue: String): View {
        val v = TextView(this).apply {
            text = textValue
            textSize = 13f
            setTextColor(textMain)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(14), dp(4), dp(8))
        }
        content.addView(v)
        return v
    }

    internal fun sectionTitle(textValue: String, actionText: String, onAction: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(14), dp(4), dp(8))
        }
        row.addView(TextView(this).apply {
            text = textValue
            textSize = 13f
            setTextColor(textMain)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = actionText
            textSize = 12f
            setTextColor(textMain)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = bg(panel2, 10, line)
            setOnClickListener { onAction() }
        })
        content.addView(row)
        return row
    }

    internal fun openToolWithPress(id: String, view: View) {
        // Debounce rapid repeated taps so a single card cannot queue the same tool
        // multiple times while the previous transition is still starting.
        addPressFeedback(view)
        val now = android.os.SystemClock.uptimeMillis()
        if (id == lastToolOpenId && now - lastToolOpenAt < 350L) return
        lastToolOpenId = id
        lastToolOpenAt = now
        view.postDelayed({
            if (id == lastToolOpenId) openTool(id)
        }, 70L)
    }

    internal fun openTool(id: String) {
        recordRecentTool(id)
        when (id) {
            "filemanager" -> fileManager(filesDir)
            "recentfiles" -> recentFilesTool()
            "backuprestore" -> backupRestoreTool()
            "workspace" -> workspaceCenterTool()
            "plugincenter" -> pluginCenterTool()
            "customtools" -> toolCustomizationTool()
            "studiocenter" -> studioCenterTool()
            "editor" -> editor(null)
            "reminder" -> reminderTool()
            "zip" -> zipTool()
            "githubzip" -> githubZipTool()
            "wifi" -> wifiInfo()
            "devicecenter" -> deviceSystemCenterTool()
            "json" -> editor(null, "json")
            "hash" -> hashTool()
            "base64" -> simpleTransform("Base64", "Encode", "Decode")
            "url" -> urlTool()
            "regex" -> regexTool()
            "uuid" -> simpleResultTool("UUID Generator") { UUID.randomUUID().toString() }
            "color" -> colorTool()
            "number" -> calculatorHub()
            "history" -> historyTool()
            // UI Color adalah pipet layar global, bukan mode kalkulator.
            "uicolorcalc" -> uiColorPickerTool()
            // Semua kalkulator tetap berada di satu layar. Mode hanya mengganti isi workspace.
            "basiccalc", "scicalc", "percentcalc", "fractioncalc", "ratiocalc",
            "unitcalc", "areacalc", "volumecalc", "speedcalc", "timecalc", "datecalc",
            "loancalc", "fuelcalc", "pivotcalc", "dividercalc", "dcacalc", "pwmcalc",
            "spritecalc", "installcalc", "powercalc", "aspectcalc", "pphcalc" -> calculatorHub(id)
            "financereader" -> financeReaderTool()
            "financedashboard" -> financeDashboardTool()
            "securitycenter" -> securityCenterTool()
            "helpbot" -> helpBotTool()
            "riskcalc" -> riskRewardCalculator()
            "compoundcalc" -> compoundCalculator()
            "margincalc" -> marginTaxCalculator()
            "discountcalc" -> tieredDiscountCalculator()
            "datacalc" -> dataUnitCalculator()
            "pressurecalc" -> pressureCalculator()
            "worktimecalc" -> workTimeCalculator()
            "basecalc" -> baseCalculator()
            "equationcalc" -> equationCalculator()
            "textstat" -> textStatTool()
            "case" -> caseTool()
            "dedupe" -> dedupeTool()
            "compare" -> compareTool()
            "slug" -> slugTool()
            "lorem" -> loremTool()
            "password" -> passwordTool()
            "token" -> tokenTool()
            "jwt" -> jwtTool()
            "hmac" -> hmacTool()
            "totp" -> totpTool()
            "aes" -> aesTool()
            "random" -> randomTool()
            "checksum" -> checksumTool()
            "hex" -> hexTool()
            "base32" -> base32Tool()
            "dns" -> dnsTool()
            "rdns" -> reverseDnsTool()
            "port" -> portTool()
            "publicip" -> publicIpTool()
            "ping" -> pingTool()
            "ipinfo" -> ipInfoTool()
            "ssl" -> sslTool()
            "apk" -> apkInspector()
            "qr" -> qrTool()
            "fileconvert" -> fileConvertTool()
            "system" -> systemInfo()
            "http" -> httpServer()
            "webhostwifi" -> wifiHtmlHostingTool()
            "filehashcompare" -> fileHashCompareTool()
            "markdown" -> markdownViewerTool()
            "sql" -> sqlToolsTool()
            "yaml" -> yamlFormatterTool()
            "toml" -> tomlInspectorTool()
            "cron" -> cronHelperTool()
            "passwordstrength" -> passwordStrengthTool()
            "fileencryption" -> fileEncryptionTool()
            "steganography" -> steganographyTool()
            "passwordanalyzer" -> passwordStrengthAnalyzerTool()
            "breachchecker" -> dataBreachCheckerTool()
            "securenotes" -> secureNotesTool()
            "totpvault" -> totpVaultTool()
            "pgp" -> pgpTool()
            "sshkeygen" -> sshKeyGeneratorTool()
            "certviewer" -> certificateViewerTool()
            "virusscanner" -> virusScannerTool()
            "urlsafety" -> urlSafetyTool()
            "stopwatch" -> stopwatchTool()
            "timer" -> timerTool()
            "imagestudio" -> imageStudioTool()
            "timestamp" -> timestampTool()
            "unicode" -> unicodeTool()
            "urlparser" -> urlParserTool()
            "mime" -> mimeTool()
            "jsonformat" -> editor(null, "json")
            "xmlformat" -> xmlFormatTool()
            "uuidbatch" -> uuidBatchTool()
            "base64file" -> base64FileTool()
            "httpheaders" -> httpHeadersTool()
            "textreplace" -> textReplaceTool()
            "wordfreq" -> wordFrequencyTool()
            "deviceinfo" -> deviceInfoTool()
            "storage" -> storageAnalyzerTool()
            "apps" -> appManagerTool()
            "network" -> networkInfoTool()
            "battery" -> batteryInfoTool()
            "filesearch" -> fileSearchTool()
            "clipboard" -> clipboardManagerTool()
            "esp" -> espTools()
            "espdiscover" -> espAutoDiscovery()
            "ledstudio" -> espLedStudio()
            "espdevice" -> espDeviceManager()
            "espgpio" -> espGpioController()
            "espsensor" -> espSensorDashboard()
            "espwifi" -> espWifiManager()
            "espota" -> espOtaFirmware()
            "esphttp" -> espHttpApiTester()
            "espmqtt" -> espMqttClient()
            "espusb" -> espUsbInfo()
            "espserial" -> espTcpSerialMonitor()
            "iotdashboard","espstudio","visualwiring" -> modularIotDashboard()
            "ocr" -> ocrTool()
            "unitconverter" -> unitConverterProTool()
            "apkanalyzer" -> apkAnalyzerTool()
            "netscanner" -> networkScannerTool()
            "webproject" -> webProjectBuilder()
            "webeditor" -> editor(null)
            "networkstudio" -> networkStudioTool()
            "developerstudio" -> developerStudioTool()
            "filestudio" -> fileStudioTool()
            "imagestudio" -> imageStudioTool()
            "colorstudio" -> colorTool()
            "systemstudio" -> systemStudioTool()
            "financestudio" -> financeStudioTool()
            "utilitystudio" -> utilityStudioTool()
            "whois" -> whoisTool()
            "traceroute" -> tracerouteTool()
            "subnetcalc" -> subnetCalculatorTool()
            "restclient" -> restApiClientTool()
            "websocket" -> webSocketClientTool()
            "networkcenter" -> networkCenterTool()
            "systemcenter" -> systemCenterTool()
            "apkcompare" -> apkCompareTool()
            "duplicatefinder" -> duplicateFinderTool()
            "largefilefinder" -> largeFileFinderTool()
        }
        // Global UI normalization: the toolbar is the single page title.
        // Older tools sometimes still add the same title (and subtitle) into the
        // scroll content, which makes the page look duplicated/overlapped.
        content.post { normalizeToolContentHeader() }
        if (currentPage != "Editor" && !currentPage.startsWith("Editor - ")) {
            content.post { Motion.openWorkspace(content, Motion.characterFor(id)) }
        }
    }

    internal fun normalizeToolContentHeader() {
        // The toolbar is the single source of truth for the current page title.
        // A number of older/newer screens still add their own title at the top of
        // the scroll content, producing the exact duplicated-heading effect seen
        // on pages such as History Center. Remove only a leading duplicate so
        // real content/section headings further down are never touched.
        if (content.childCount == 0) return

        val toolbarTitle = title.text?.toString()?.trim().orEmpty()
        if (toolbarTitle.isEmpty()) return

        val first = content.getChildAt(0) as? TextView
        if (first != null) {
            val firstText = first.text?.toString()?.trim().orEmpty()
            if (firstText.equals(toolbarTitle, ignoreCase = true)) {
                content.removeViewAt(0)
                val next = content.getChildAt(0) as? TextView
                if (next != null && next.text?.isNotBlank() == true && next.currentTextColor == textMuted) {
                    content.removeViewAt(0)
                }
            }
        }
    }


    // ---------- V2.26: NETWORK / SYSTEM / APK / STORAGE TOOLS ----------

    internal fun webSocketClientTool() {
        clearPage("WebSocket Client")
        toolWorkspace("WebSocket Client", "Hubungkan endpoint WebSocket, kirim pesan, terima frame, dan pantau log.", "connection")
        toolWorkspaceSection("CONNECTION", "Gunakan ws:// atau wss:// lalu kontrol koneksi dari bawah.")
        val url = edit("ws://echo.websocket.events")
        val message = edit("Pesan")
        val log = edit("Log")
        log.isEnabled = false; log.minLines = 8; log.setTextIsSelectable(true)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val connect = button("Connect") {}; val send = button("Send") {}; val receive = button("Receive") {}; val close = button("Close") {}
        row.addView(connect, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin=dp(3) })
        row.addView(send, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin=dp(2); rightMargin=dp(2) })
        row.addView(receive, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin=dp(2); rightMargin=dp(2) })
        row.addView(close, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin=dp(2) })
        content.addView(url); content.addView(message); content.addView(row); content.addView(log)
        fun appendLog(t:String) { log.append((if(log.text.isNotEmpty()) "\n" else "") + t) }
        connect.setOnClickListener {
            val raw=url.text.toString().trim(); if(!raw.startsWith("ws://") && !raw.startsWith("wss://")){toast("Gunakan ws:// atau wss://");return@setOnClickListener}
            connect.isEnabled=false
            toolThread {
                val r=runCatching{ openWebSocket(raw) }.getOrElse{"ERROR: ${it.message}"}
                runOnUiThread{appendLog(r); connect.isEnabled=true}
            }
        }
        send.setOnClickListener {
            val msg=message.text.toString(); if(msg.isBlank()){toast("Pesan kosong");return@setOnClickListener}
            toolThread {val r=runCatching{writeWsText(msg); "TX: $msg"}.getOrElse{"ERROR: ${it.message}"};runOnUiThread{appendLog(r)}}
        }
        receive.setOnClickListener { toolThread { val r=runCatching{readWsText()}.getOrElse{"ERROR: ${it.message}"}; runOnUiThread{appendLog(if(r.startsWith("ERROR")) r else "RX: $r")} } }
        close.setOnClickListener { toolThread { runCatching{closeWebSocket()}; runOnUiThread{appendLog("Closed")} } }
    }

    internal fun openWebSocket(raw:String):String {
        closeWebSocket()
        val u=URI(raw); val secure=u.scheme.equals("wss",true); val port=if(u.port>0)u.port else if(secure)443 else 80
        val s:Socket = if(secure) javax.net.ssl.SSLSocketFactory.getDefault().createSocket() else Socket()
        s.connect(InetSocketAddress(u.host,port),8000); s.soTimeout=12000
        val out=s.getOutputStream(); val input=s.getInputStream()
        val key=Base64.getEncoder().encodeToString(ByteArray(16).also{SecureRandom().nextBytes(it)})
        val path=(if(u.rawPath.isNullOrBlank()) "/" else u.rawPath)+(u.rawQuery?.let{"?$it"} ?: "")
        out.write(("GET $path HTTP/1.1\r\nHost: ${u.host}:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)); out.flush()
        val header=readHttpHeader(input); if(!header.startsWith("HTTP/1.1 101") && !header.startsWith("HTTP/1.0 101")) throw IOException("Handshake gagal: ${header.lines().firstOrNull()}")
        wsSocket=s; wsInput=input; wsOutput=out
        return "Connected: $raw"
    }

    internal fun readHttpHeader(input:InputStream):String { val b=ByteArrayOutputStream(); var state=0; while(true){val c=input.read();if(c<0)break;b.write(c);state=if(state==0&&c==13)1 else if(state==1&&c==10)2 else if(state==2&&c==13)3 else if(state==3&&c==10)4 else 0;if(state==4)break;if(b.size()>16000)throw IOException("Header terlalu besar")};return b.toString("ISO-8859-1") }
    internal fun writeWsText(text:String){ val out=wsOutput ?: throw IOException("Belum terhubung"); val data=text.toByteArray(StandardCharsets.UTF_8); val mask=ByteArray(4).also{SecureRandom().nextBytes(it)}; val first=0x81; out.write(first); when { data.size<126 -> out.write(0x80 or data.size); data.size<=65535 -> {out.write(0x80 or 126);out.write(data.size shr 8);out.write(data.size and 255)} else -> throw IOException("Pesan terlalu besar") }; out.write(mask); for(i in data.indices) out.write(data[i].toInt() xor mask[i%4].toInt()); out.flush() }
    internal fun readWsText():String{
        val input=wsInput ?: throw IOException("Belum terhubung")
        val h1=input.read(); val h2=input.read(); if(h1<0||h2<0)throw IOException("Koneksi ditutup")
        val opcode=h1 and 0x0f; var len=(h2 and 0x7f).toLong(); val masked=(h2 and 0x80)!=0
        if(len==126L){len=((input.read() shl 8) or input.read()).toLong()} else if(len==127L){len=0;repeat(8){len=(len shl 8) or input.read().toLong()}}
        if(len>1024*1024)throw IOException("Frame terlalu besar")
        val mask=if(masked)ByteArray(4).also{readFullyWs(input,it)} else null
        val data=ByteArray(len.toInt());readFullyWs(input,data);if(mask!=null)for(i in data.indices)data[i]=(data[i].toInt() xor mask[i%4].toInt()).toByte()
        return when(opcode){1->String(data,StandardCharsets.UTF_8);8->"[CLOSE]";9->"[PING]";10->"[PONG]";else->"[opcode=$opcode, ${data.size} bytes]"}
    }
    internal fun readFullyWs(input: InputStream, b: ByteArray) { var p = 0; while (p < b.size) { val n = input.read(b, p, b.size - p); if (n < 0) throw EOFException(); p += n } }
    internal fun closeWebSocket(){runCatching{wsSocket?.close()};wsSocket=null;wsInput=null;wsOutput=null}

    internal fun systemCenterTool() {
        clearPage("System Center")
        val am=getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mem=ActivityManager.MemoryInfo().also{am.getMemoryInfo(it)}
        val stat=StatFs(Environment.getDataDirectory().path)
        val ramPct=((mem.totalMem-mem.availMem).toDouble()/mem.totalMem*100).roundToInt().coerceIn(0,100)
        val storagePct=((stat.totalBytes-stat.availableBytes).toDouble()/stat.totalBytes*100).roundToInt().coerceIn(0,100)
        modernProgressCard("CPU", cpuLoadPercent(), "Penggunaan & suhu prosesor", "${Runtime.getRuntime().availableProcessors()} core")
        modernProgressCard("RAM", ramPct, "Penggunaan memori", "${bytesText(mem.totalMem-mem.availMem)} / ${bytesText(mem.totalMem)}")
        modernProgressCard("Storage", storagePct, "Penyimpanan internal", "${bytesText(stat.totalBytes-stat.availableBytes)} / ${bytesText(stat.totalBytes)}")
        val processes=am.runningAppProcesses?.size ?: 0
        modernProgressCard("Layanan Sistem", processes.coerceAtMost(100), "Layanan & proses aktif", "$processes aktif")
        modernActionRow("Pengaturan Cepat", "flash") { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    internal fun formatDuration(ms:Long):String { var s=ms/1000; val d=s/86400;s%=86400;val h=s/3600;s%=3600;val m=s/60;s%=60;return "${d}d ${h}h ${m}m ${s}s" }

    internal fun apkCompareTool(){
        clearPage("APK Compare"); content.addView(label("APK Compare",22f,true)); content.addView(subLabel("Bandingkan metadata dan isi dua APK tanpa menginstalnya.",12f))
        content.addView(button("Pilih APK A") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="application/vnd.android.package-archive";addCategory(Intent.CATEGORY_OPENABLE)},1301) })
        content.addView(button("Pilih APK B") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="application/vnd.android.package-archive";addCategory(Intent.CATEGORY_OPENABLE)},1302) })
    }

    internal fun compareApks(a:Uri,b:Uri){toolThread {val r=runCatching{
        val fa=uriToCacheFile(a,"apk_a.apk");val fb=uriToCacheFile(b,"apk_b.apk");val pa=packageArchiveInfo(fa);val pb=packageArchiveInfo(fb)
        val sa=fa.length();val sb=fb.length();val ha=sha256(fa);val hb=sha256(fb)
        val ea=zipSummary(fa);val eb=zipSummary(fb)
        "APK A\nPackage: ${pa.first}\nVersion: ${pa.second}\nSize: ${bytesText(sa)}\nSHA-256: $ha\nZIP entries: ${ea.first}\n\nAPK B\nPackage: ${pb.first}\nVersion: ${pb.second}\nSize: ${bytesText(sb)}\nSHA-256: $hb\nZIP entries: ${eb.first}\n\nMetadata package sama: ${pa.first==pb.first}\nVersion sama: ${pa.second==pb.second}\nHash sama: ${ha.equals(hb,true)}\nUkuran beda: ${bytesText(kotlin.math.abs(sa-sb))}\nEntry beda: ${kotlin.math.abs(ea.first-eb.first)}"
    }.getOrElse{"APK Compare gagal: ${it.message}"};runOnUiThread{clearPage("APK Compare");output(r)}}}

    internal fun uriToCacheFile(uri:Uri,name:String):File{val f=File(cacheDir,name);contentResolver.openInputStream(uri)?.use{input->FileOutputStream(f).use{input.copyTo(it)}}?:throw IOException("File tidak dapat dibaca");return f}
    internal fun packageArchiveInfo(f:File):Pair<String,String>{val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else 0;val p=packageManager.getPackageArchiveInfo(f.absolutePath,flags)?:throw IOException("APK tidak valid");return p.packageName to (if(Build.VERSION.SDK_INT>=28)p.longVersionCode.toString() else p.versionCode.toString())}
    internal fun sha256(f:File):String{val md=MessageDigest.getInstance("SHA-256");FileInputStream(f).use{inp->val buf=ByteArray(8192);while(true){val n=inp.read(buf);if(n<0)break;md.update(buf,0,n)}};return md.digest().joinToString(""){String.format("%02x",it)} }
    internal fun zipSummary(f:File):Pair<Int,Long>{var c=0;var total=0L;ZipInputStream(BufferedInputStream(FileInputStream(f))).use{z->while(true){val e=z.nextEntry?:break;c++;if(!e.isDirectory)total+=e.size.coerceAtLeast(0)}};return c to total}

    internal fun scanRoots():List<File> = listOfNotNull(filesDir, getExternalFilesDir(null), File(Environment.getExternalStorageDirectory().path,"Download")).distinctBy{it.absolutePath}.filter{it.exists()}

    internal fun scanFiles(dir:File,out:MutableList<File>,limit:Int){if(out.size>=limit)return;val list=runCatching{dir.listFiles()}.getOrNull()?:return;for(f in list){if(out.size>=limit)return;if(f.isFile)out.add(f) else if(f.isDirectory)scanFiles(f,out,limit)}}
    internal fun infoCard(titleText:String,bodyText:String):View{val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=bg(panel2,14,line)};c.addView(label(titleText,13f,true));c.addView(subLabel(bodyText,11f));c.layoutParams=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)};return c}

    // ---------- CUSTOM DASHBOARD BUILDER / STUDIO MODE ----------
    // Studio landscape: canvas hitam, widget bebas diposisikan, tersimpan lokal.
    internal data class StudioWidget(
        val id: String,
        val type: String,
        var label: String,
        var gpio: Int,
        var x: Int,
        var y: Int,
        var value: Int = 0,
        var checked: Boolean = false
    )

    internal data class StudioLink(val fromId: String, val toId: String)

    internal var studioWidgets = ArrayList<StudioWidget>()
    internal var studioLinks = ArrayList<StudioLink>()
    internal var studioSelectedLinkId: String? = null
    internal var studioCanvas: StudioCanvasView? = null
    internal val studioPrefsKey = "studio_widgets_v1"
    internal val studioLinksPrefsKey = "studio_links_v1"
    internal var studioNextId = 1

    internal fun modularIotDashboard() {
        // Kunci landscape sebelum membangun canvas agar tidak sempat kembali ke Home
        // saat Activity menerima perubahan orientasi.
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        clearPage("IoT Dynamic Topology")
        action.text = "+"
        action.textSize = 28f
        action.setOnClickListener { showStudioWidgetPicker() }
        title.text = "IOT STUDIO"
        content.setBackgroundColor(Color.BLACK)
        content.setPadding(0, 0, 0, 0)
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        content.layoutParams = content.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }

        studioWidgets = loadStudioWidgets()
        studioLinks = loadStudioLinks()
        studioSelectedLinkId = null
        studioCanvas = StudioCanvasView(this)
        studioCanvas?.setBackgroundColor(Color.BLACK)
        content.removeAllViews()
        content.addView(studioCanvas, LinearLayout.LayoutParams(-1, -1))
        studioCanvas?.setLinks(studioLinks)
        studioCanvas?.setWidgets(studioWidgets)
    }

    internal fun showStudioWidgetPicker() {
        val options = arrayOf(
            "🔌 Relay — ON / OFF",
            "🔘 Push — tekan & tahan",
            "💡 Slider / PWM Dimmer",
            "⚙️ Atur MQTT Studio"
        )
        AlertDialog.Builder(this)
            .setTitle("Tambah Widget")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showStudioConfig("RELAY_TOGGLE")
                    1 -> showStudioConfig("PUSH_MOMENTARY")
                    2 -> showStudioConfig("PWM_SLIDER")
                    3 -> showStudioMqttConfig()
                }
            }
            .setNegativeButton("BATAL", null)
            .show()
    }

    internal fun showStudioConfig(type: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val name = edit(if (type == "RELAY_TOGGLE") "Nama tombol, contoh Lampu Teras" else "Nama widget")
        val gpio = edit("GPIO / Relay, contoh 2")
        gpio.inputType = InputType.TYPE_CLASS_NUMBER
        box.addView(name)
        box.addView(gpio)
        if (type == "PWM_SLIDER") {
            box.addView(subLabel("Nilai PWM 0–255. Geser untuk mengatur kecerahan/kecepatan.", 11f))
        } else if (type == "PUSH_MOMENTARY") {
            box.addView(subLabel("Perintah ON dikirim saat ditekan, OFF saat dilepas.", 11f))
        } else {
            box.addView(subLabel("Tap sekali untuk ON/OFF.", 11f))
        }
        AlertDialog.Builder(this)
            .setTitle(when (type) {
                "RELAY_TOGGLE" -> "Tambah Tombol Relay"
                "PUSH_MOMENTARY" -> "Tambah Tombol Push"
                else -> "Tambah PWM Dimmer"
            })
            .setView(box)
            .setNegativeButton("BATAL", null)
            .setPositiveButton("TAMBAH") { _, _ ->
                val labelText = name.text.toString().trim().ifBlank { "GPIO" }
                val pin = gpio.text.toString().toIntOrNull()?.coerceIn(0, 99) ?: 2
                val widget = StudioWidget(
                    id = "btn_${studioNextId++}",
                    type = type,
                    label = labelText,
                    gpio = pin,
                    x = dp(24),
                    y = dp(24) + studioWidgets.size * dp(18)
                )
                studioWidgets.add(widget)
                saveStudioWidgets()
                studioCanvas?.setWidgets(studioWidgets)
            }
            .show()
    }

    internal fun showStudioMqttConfig() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val host = edit("Broker host, contoh 192.168.1.10")
        host.setText(prefs.getString("studio_mqtt_host", "") ?: "")
        val port = edit("Port")
        port.setText(prefs.getString("studio_mqtt_port", "1883") ?: "1883")
        port.inputType = InputType.TYPE_CLASS_NUMBER
        val topic = edit("Topic, contoh esp32/gpio")
        topic.setText(prefs.getString("studio_mqtt_topic", "esp32/gpio") ?: "esp32/gpio")
        box.addView(host); box.addView(port); box.addView(topic)
        AlertDialog.Builder(this)
            .setTitle("MQTT Studio")
            .setView(box)
            .setNegativeButton("BATAL", null)
            .setPositiveButton("SIMPAN") { _, _ ->
                prefs.edit()
                    .putString("studio_mqtt_host", host.text.toString().trim())
                    .putString("studio_mqtt_port", port.text.toString().trim())
                    .putString("studio_mqtt_topic", topic.text.toString().trim())
                    .apply()
                toast("Konfigurasi MQTT Studio disimpan")
            }
            .show()
    }

    internal fun saveStudioWidgets() {
        val arr = JSONArray()
        studioWidgets.forEach { w ->
            arr.put(JSONObject().apply {
                put("id", w.id); put("type", w.type); put("label", w.label); put("gpio", w.gpio)
                put("posX", w.x); put("posY", w.y); put("value", w.value); put("checked", w.checked)
            })
        }
        prefs.edit().putString(studioPrefsKey, arr.toString()).apply()
    }

    internal fun saveStudioLinks() {
        val arr = JSONArray()
        studioLinks.forEach { lk ->
            arr.put(JSONObject().apply { put("from", lk.fromId); put("to", lk.toId) })
        }
        prefs.edit().putString(studioLinksPrefsKey, arr.toString()).apply()
    }

    internal fun loadStudioLinks(): ArrayList<StudioLink> {
        val result = ArrayList<StudioLink>()
        val raw = prefs.getString(studioLinksPrefsKey, "[]") ?: "[]"
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val from = o.optString("from", ""); val to = o.optString("to", "")
                if (from.isNotBlank() && to.isNotBlank()) result.add(StudioLink(from, to))
            }
        }
        return result
    }

    // Tap satu widget untuk memilihnya (menyala), lalu tap widget lain untuk menyambung.
    // Tap widget yang sama lagi untuk membatalkan pilihan.
    internal fun onStudioLinkTap(id: String) {
        val sel = studioSelectedLinkId
        studioSelectedLinkId = when {
            sel == null -> id
            sel == id -> null
            else -> {
                val exists = studioLinks.any { (it.fromId == sel && it.toId == id) || (it.fromId == id && it.toId == sel) }
                if (!exists) { studioLinks.add(StudioLink(sel, id)); saveStudioLinks() }
                null
            }
        }
        studioCanvas?.setLinks(studioLinks)
        studioCanvas?.setWidgets(studioWidgets)
    }

    internal fun loadStudioWidgets(): ArrayList<StudioWidget> {
        val result = ArrayList<StudioWidget>()
        val raw = prefs.getString(studioPrefsKey, "[]") ?: "[]"
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(StudioWidget(
                    id = o.optString("id", "btn_${i + 1}"),
                    type = o.optString("type", "RELAY_TOGGLE"),
                    label = o.optString("label", "Widget ${i + 1}"),
                    gpio = o.optInt("gpio", 2),
                    x = o.optInt("posX", dp(24)),
                    y = o.optInt("posY", dp(24)),
                    value = o.optInt("value", 0),
                    checked = o.optBoolean("checked", false)
                ))
            }
        }
        studioNextId = result.mapNotNull { it.id.substringAfter("btn_", "").toIntOrNull() }.maxOrNull()?.plus(1) ?: 1
        return result
    }

    internal fun sendStudioCommand(widget: StudioWidget, command: String) {
        val host = prefs.getString("studio_mqtt_host", "")?.trim().orEmpty()
        val port = prefs.getString("studio_mqtt_port", "1883")?.toIntOrNull() ?: 1883
        val topic = prefs.getString("studio_mqtt_topic", "esp32/gpio")?.trim().orEmpty()
        val payload = JSONObject().apply {
            put("device", widget.label)
            put("gpio", widget.gpio)
            put("command", command)
            put("value", widget.value)
        }.toString()
        if (host.isBlank()) {
            toast("Widget ${widget.label}: ${command} • MQTT belum dikonfigurasi")
            return
        }
        toolThread {
            val result = runCatching {
                mqttPublish(host, port, "MyTools-Studio-${System.currentTimeMillis() % 100000}", topic, payload)
            }.getOrElse { "MQTT gagal: ${it.message}" }
            runOnUiThread { if (result.startsWith("MQTT gagal")) toast(result) }
        }
    }

    internal inner class StudioCanvasView(context: Context) : ViewGroup(context) {
        internal var widgets: List<StudioWidget> = emptyList()
        internal var links: List<StudioLink> = emptyList()
        internal val cardWidth = dp(170)
        internal val cardHeight = dp(92)
        internal val linePaint = Paint().apply {
            color = Color.rgb(120, 120, 128)
            strokeWidth = dp(2).toFloat()
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        // Grid titik dibuat sengaja sangat samar agar canvas tidak terasa polos,
        // tetapi tetap nyaman untuk melihat widget dan garis koneksi.
        internal val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(58, 115, 115, 120)
            style = Paint.Style.FILL
        }
        internal val dotSpacing = dp(34).coerceAtLeast(dp(20))
        internal val dotRadius = 1.35f * resources.displayMetrics.density

        init {
            setWillNotDraw(false)
            setBackgroundColor(Color.BLACK)
        }

        fun setWidgets(list: List<StudioWidget>) {
            widgets = list.toList()
            removeAllViews()
            widgets.forEach { addView(createWidgetView(it)) }
            requestLayout()
            invalidate()
        }

        fun setLinks(list: List<StudioLink>) {
            links = list.toList()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Pola titik gelap-samar seperti canvas desain/ESP Studio.
            // Digambar sebelum link supaya garis koneksi tetap jelas.
            var y = dotSpacing / 2f
            while (y < height) {
                var x = dotSpacing / 2f
                while (x < width) {
                    canvas.drawCircle(x.toFloat(), y.toFloat(), dotRadius, dotPaint)
                    x += dotSpacing
                }
                y += dotSpacing
            }

            links.forEach { lk ->
                val a = widgets.find { it.id == lk.fromId } ?: return@forEach
                val b = widgets.find { it.id == lk.toId } ?: return@forEach
                val ax = a.x + cardWidth.toFloat()
                val ay = a.y + cardHeight / 2f
                val bx = b.x.toFloat()
                val by = b.y + cardHeight / 2f
                canvas.drawLine(ax, ay, bx, by, linePaint)
            }
        }

        internal fun createWidgetView(widget: StudioWidget): View {
            val outer = FrameLayout(context)
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = bg(Color.rgb(28, 28, 30), 14, Color.rgb(65, 65, 70))
            }
            val title = TextView(context).apply {
                text = widget.label
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val dragRow = FrameLayout(context).apply {
                setPadding(0, 0, 0, 0)
            }
            dragRow.addView(title, FrameLayout.LayoutParams(-1, dp(36)))
            root.addView(dragRow, LinearLayout.LayoutParams(-1, dp(36)))
            // Area geser dibuat lebih besar supaya widget mudah dipindahkan di layar HP.
            // Kontrol ON/OFF, TEKAN, dan slider tetap bisa disentuh normal.
            dragRow.setOnTouchListener(object : View.OnTouchListener {
                var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
                var moved = false
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX; downY = event.rawY
                            startX = widget.x; startY = widget.y
                            moved = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                            if (kotlin.math.abs(dx) > dp(4) || kotlin.math.abs(dy) > dp(4)) moved = true
                            val maxX = (width - cardWidth).coerceAtLeast(0)
                            val maxY = (height - cardHeight).coerceAtLeast(0)
                            widget.x = (startX + dx).coerceIn(0, maxX)
                            widget.y = (startY + dy).coerceIn(0, maxY)
                            root.x = widget.x.toFloat(); root.y = widget.y.toFloat()
                            invalidate()
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            saveStudioWidgets()
                            if (!moved) onStudioLinkTap(widget.id)
                            return true
                        }
                    }
                    return true
                }
            })

            when (widget.type) {
                "RELAY_TOGGLE" -> {
                    val toggle = Switch(context).apply {
                        isChecked = widget.checked
                        text = if (widget.checked) "ON" else "OFF"
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        setOnCheckedChangeListener { _, checked ->
                            widget.checked = checked
                            text = if (checked) "ON" else "OFF"
                            sendStudioCommand(widget, if (checked) "ON" else "OFF")
                            saveStudioWidgets()
                        }
                    }
                    root.addView(toggle, LinearLayout.LayoutParams(-1, dp(42)))
                }
                "PUSH_MOMENTARY" -> {
                    val push = TextView(context).apply {
                        text = "TEKAN"
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        background = bg(Color.rgb(55, 55, 58), 10)
                        isClickable = true
                        setOnTouchListener { v, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> { sendStudioCommand(widget, "ON"); v.performClick() }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sendStudioCommand(widget, "OFF")
                            }
                            true
                        }
                    }
                    root.addView(push, LinearLayout.LayoutParams(-1, dp(38)))
                }
                "PWM_SLIDER" -> {
                    val slider = SeekBar(context).apply {
                        max = 255
                        progress = widget.value.coerceIn(0, 255)
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                                widget.value = progress
                                if (fromUser) sendStudioCommand(widget, "PWM:$progress")
                            }
                            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                            override fun onStopTrackingTouch(seekBar: SeekBar?) { saveStudioWidgets() }
                        })
                    }
                    root.addView(slider, LinearLayout.LayoutParams(-1, dp(40)))
                }
            }

            root.setOnLongClickListener {
                showStudioEditDialog(widget)
                true
            }
            outer.addView(root, FrameLayout.LayoutParams(-1, -1))

            // Titik sambung: tap satu widget lalu tap widget lain untuk menghubungkan.
            val selected = studioSelectedLinkId == widget.id
            val linkDot = TextView(context).apply {
                text = "\u2295"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (selected) Color.BLACK else Color.WHITE)
                background = bg(if (selected) Color.WHITE else Color.rgb(45, 45, 47), 20, Color.rgb(95, 95, 100))
                setOnClickListener { onStudioLinkTap(widget.id) }
            }
            val dotSize = dp(26)
            val dotLp = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(2); rightMargin = dp(2)
            }
            outer.addView(linkDot, dotLp)
            return outer
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
            for (i in 0 until childCount) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cardHeight, MeasureSpec.EXACTLY))
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val w = widgets.getOrNull(i) ?: continue
                val child = getChildAt(i)
                val x = w.x.coerceIn(0, (width - cardWidth).coerceAtLeast(0))
                val y = w.y.coerceIn(0, (height - cardHeight).coerceAtLeast(0))
                child.layout(x, y, x + cardWidth, y + cardHeight)
            }
        }
    }

    internal fun showStudioEditDialog(widget: StudioWidget) {
        val options = arrayOf("Ubah nama / GPIO", "Hapus widget")
        AlertDialog.Builder(this)
            .setTitle(widget.label)
            .setItems(options) { _, which ->
                if (which == 0) {
                    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
                    val name = edit("Nama"); name.setText(widget.label)
                    val gpio = edit("GPIO"); gpio.inputType = InputType.TYPE_CLASS_NUMBER; gpio.setText(widget.gpio.toString())
                    box.addView(name); box.addView(gpio)
                    AlertDialog.Builder(this).setTitle("Edit Widget").setView(box)
                        .setNegativeButton("BATAL", null)
                        .setPositiveButton("SIMPAN") { _, _ ->
                            widget.label = name.text.toString().trim().ifBlank { widget.label }
                            widget.gpio = gpio.text.toString().toIntOrNull()?.coerceIn(0, 99) ?: widget.gpio
                            saveStudioWidgets(); studioCanvas?.setWidgets(studioWidgets)
                        }.show()
                } else {
                    studioWidgets.removeAll { it.id == widget.id }
                    studioLinks.removeAll { it.fromId == widget.id || it.toId == widget.id }
                    if (studioSelectedLinkId == widget.id) studioSelectedLinkId = null
                    saveStudioWidgets(); saveStudioLinks()
                    studioCanvas?.setLinks(studioLinks); studioCanvas?.setWidgets(studioWidgets)
                }
            }
            .setNegativeButton("BATAL", null)
            .show()
    }

    // ---------- ESP LED STUDIO ----------
    // Editor visual LED addressable. Layout selector dibuat ringkas/tersembunyi
    // di dalam kartu dan seluruh pengaturan tetap berada pada satu halaman.
    internal data class LedFrameData(var states: BooleanArray, var durationMs: Long)

    internal var ledCount = 10
    internal var ledLayout = "Grid"
    internal val ledFrames = ArrayList<LedFrameData>()
    internal var ledFrameIndex = 0
    internal var ledCanvas: LedCanvasView? = null
    internal var ledFrameStrip: LinearLayout? = null
    internal var ledFrameInfo: TextView? = null
    internal var ledSpeedInfo: TextView? = null
    internal var ledSpeedSeek: SeekBar? = null
    internal var ledNameEdit: EditText? = null
    internal var ledEndpointEdit: EditText? = null
    internal var ledGapSeek: SeekBar? = null
    internal var ledGapDp = 0
    internal var ledPlaying = false
    internal var ledLayoutLabel: TextView? = null
    internal var ledCountLabel: TextView? = null
    internal val ledPlayHandler = Handler(Looper.getMainLooper())
    internal var ledPlayRunnable: Runnable? = null

    internal fun ledSectionCard(title: String, subtitleText: String, icon: String = "•"): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = bg(panel2, 18, line)
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = icon; textSize = 21f; gravity = Gravity.CENTER; setTextColor(textMain)
            background = bg(panel, 12, line)
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(label(title, 15f, true))
        labels.addView(subLabel(subtitleText, 11f))
        top.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(top)
        return box
    }

    internal fun espLedStudio() {
        stopLedPlayback()
        clearPage("ESP LED Studio")
        content.addView(label("ESP LED Studio", 22f, true))
        content.addView(subLabel("Buat pola LED, pilih susunan, atur jarak dan animasi, lalu kirim langsung ke ESP32.", 12f))

        // Jumlah LED — kontrol +/− lebih cepat daripada spinner.
        val countCard = ledSectionCard("Jumlah LED", "Maksimum 50 LED", "💡")
        val countRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val minus = Button(this).apply {
            text = "−"; textSize = 22f; setTextColor(textMain); background = bg(panel, 14, line); setStateListAnimator(null)
            setOnClickListener { setLedCount(ledCount - 1) }
        }
        ledCountLabel = TextView(this).apply {
            text = ledCount.toString(); textSize = 22f; gravity = Gravity.CENTER; setTextColor(textMain)
        }
        val plus = Button(this).apply {
            text = "+"; textSize = 22f; setTextColor(textMain); background = bg(panel, 14, line); setStateListAnimator(null)
            setOnClickListener { setLedCount(ledCount + 1) }
        }
        countRow.addView(minus, LinearLayout.LayoutParams(dp(52), dp(48)))
        countRow.addView(ledCountLabel, LinearLayout.LayoutParams(0, dp(48), 1f))
        countRow.addView(plus, LinearLayout.LayoutParams(dp(52), dp(48)))
        countCard.addView(countRow)
        content.addView(countCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Bentuk/susunan tidak lagi mengambil ruang besar. Tap kartu untuk membuka pilihan.
        val layoutCard = ledSectionCard("Bentuk / Susunan LED", "Tap untuk memilih pola susunan", "▦")
        ledLayoutLabel = TextView(this).apply {
            text = "Grid"
            textSize = 14f
            setTextColor(textMain)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = bg(panel, 13, line)
        }
        val layoutRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(ledLayoutLabel, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(TextView(this@MainActivity).apply {
                text = "›"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(textMuted)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            setOnClickListener { showLedLayoutPicker() }
        }
        layoutCard.addView(layoutRow)
        content.addView(layoutCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Preview utama.
        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = bg(panel2, 18, line)
        }
        val previewTitle = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        previewTitle.addView(label("Preview LED", 15f, true), LinearLayout.LayoutParams(0, dp(38), 1f))
        previewTitle.addView(TextView(this).apply {
            text = "LIVE"; textSize = 11f; gravity = Gravity.CENTER; setTextColor(textMain); background = bg(panel, 12, line)
            setPadding(dp(12), 0, dp(12), 0)
        }, LinearLayout.LayoutParams(dp(64), dp(34)))
        previewCard.addView(previewTitle)
        ledCanvas = LedCanvasView(this).apply {
            setLedConfig(ledCount, ledLayout)
            onLedClicked = { index ->
                val frame = ledFrames.getOrNull(ledFrameIndex)
                if (frame != null && index in frame.states.indices) {
                    frame.states[index] = !frame.states[index]
                    setStates(frame.states)
                    updateLedFrameInfo()
                    renderLedFrames()
                }
            }
        }
        previewCard.addView(ledCanvas, LinearLayout.LayoutParams(-1, dp(330)).apply { topMargin = dp(6) })
        content.addView(previewCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Jarak LED.
        val gapCard = ledSectionCard("Jarak antar LED", "0 dp = paling rapat", "↔")
        ledGapSeek = SeekBar(this).apply {
            max = 20; progress = ledGapDp
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    ledGapDp = progress; ledCanvas?.setLedGap(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        gapCard.addView(ledGapSeek, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })
        content.addView(gapCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Pattern.
        val patternCard = ledSectionCard("Pattern", "Simpan pola dan gunakan lagi kapan saja", "◉")
        ledNameEdit = edit("Nama pattern, contoh LOVE")
        patternCard.addView(ledNameEdit, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        ledFrameInfo = label("Frame 1 / 1 • 0/${ledCount} LED menyala", 12f, true)
        patternCard.addView(ledFrameInfo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(patternCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Timeline frame dan durasi.
        val animCard = ledSectionCard("Animasi", "Buat beberapa frame dan atur kecepatan", "◷")
        val frameActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        frameActions.addView(button("+ Frame") { addLedFrame() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(4) })
        frameActions.addView(button("Duplikat") { duplicateLedFrame() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        frameActions.addView(button("Hapus") { deleteLedFrame() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4) })
        animCard.addView(frameActions)
        ledFrameStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val frameScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(ledFrameStrip)
        }
        animCard.addView(frameScroll, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(6) })
        ledSpeedInfo = subLabel("Durasi frame: 300 ms", 11f)
        animCard.addView(ledSpeedInfo)
        ledSpeedSeek = SeekBar(this).apply {
            max = 1950; progress = 250
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val ms = (progress + 50).toLong()
                    ledFrames.getOrNull(ledFrameIndex)?.durationMs = ms
                    ledSpeedInfo?.text = "Durasi frame: ${ms} ms"
                    updateLedFrameInfo()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        animCard.addView(ledSpeedSeek, LinearLayout.LayoutParams(-1, dp(42)))
        val playRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playRow.addView(button("▶ Putar") { playLedAnimation() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) })
        playRow.addView(button("■ Stop") { stopLedPlayback(); updateLedFrameInfo() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(4) })
        animCard.addView(playRow)
        content.addView(animCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Simpan / reset.
        val saveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveRow.addView(button("Simpan Pattern") { saveLedPattern() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(4) })
        saveRow.addView(button("Reset") { resetLedFrames(); renderLedFrames() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(4) })
        content.addView(saveRow)

        content.addView(label("Pattern tersimpan", 15f, true).apply { setPadding(0, dp(14), 0, dp(6)) })
        renderSavedLedPatterns()

        // Upload tetap pada halaman yang sama.
        val uploadTitle = label("Upload ke ESP32", 15f, true).apply {
            setPadding(0, dp(14), 0, dp(5)); tag = "led_upload_title"
        }
        content.addView(uploadTitle)
        content.addView(subLabel("Endpoint HTTP POST ESP32. Contoh: http://192.168.4.1/api/led/pattern", 11f))
        ledEndpointEdit = edit("URL endpoint ESP32")
        ledEndpointEdit?.setText(prefs.getString("led_endpoint", "http://192.168.4.1/api/led/pattern") ?: "")
        content.addView(ledEndpointEdit)
        content.addView(button("Upload Pattern") { uploadLedPattern() })
        content.addView(button("Salin JSON Pattern") { copyText(buildLedPatternJson().toString(2)) })

        resetLedFrames()
        renderLedFrames()
    }

    internal fun setLedCount(value: Int) {
        val newCount = value.coerceIn(1, 50)
        if (newCount == ledCount) return
        ledCount = newCount
        ledCountLabel?.text = ledCount.toString()
        ledFrames.forEach { frame ->
            val oldStates = frame.states
            frame.states = BooleanArray(ledCount).also { next ->
                for (i in 0 until minOf(oldStates.size, next.size)) next[i] = oldStates[i]
            }
        }
        if (ledFrames.isEmpty()) resetLedFrames()
        ledCanvas?.setLedConfig(ledCount, ledLayout)
        ledCanvas?.setStates(ledFrames.getOrNull(ledFrameIndex)?.states ?: BooleanArray(ledCount))
        updateLedFrameInfo()
        renderLedFrames()
    }

    internal fun showLedLayoutPicker() {
        val values = arrayOf("Grid", "Lingkaran", "Strip", "Spiral")
        val current = values.indexOf(ledLayout).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Bentuk / Susunan LED")
            .setSingleChoiceItems(values, current) { dialog, which ->
                ledLayout = values[which]
                ledLayoutLabel?.text = ledLayout
                ledCanvas?.setLedConfig(ledCount, ledLayout)
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    internal fun resetLedFrames() {
        stopLedPlayback()
        ledFrames.clear()
        ledFrames.add(LedFrameData(BooleanArray(ledCount), 300L))
        ledFrameIndex = 0
        ledCanvas?.setLedConfig(ledCount, ledLayout)
        ledCanvas?.setLedGap(ledGapDp)
        ledCanvas?.setStates(ledFrames[0].states)
        ledSpeedSeek?.progress = 250
        ledSpeedInfo?.text = "Durasi frame: 300 ms"
        updateLedFrameInfo()
    }

    internal fun addLedFrame() {
        if (ledFrames.isEmpty()) resetLedFrames()
        val source = ledFrames[ledFrameIndex]
        ledFrames.add(LedFrameData(source.states.copyOf(), source.durationMs))
        ledFrameIndex = ledFrames.lastIndex
        selectLedFrame(ledFrameIndex)
    }

    internal fun duplicateLedFrame() {
        if (ledFrames.isEmpty()) resetLedFrames()
        val source = ledFrames[ledFrameIndex]
        ledFrames.add(ledFrameIndex + 1, LedFrameData(source.states.copyOf(), source.durationMs))
        ledFrameIndex += 1
        selectLedFrame(ledFrameIndex)
    }

    internal fun deleteLedFrame() {
        if (ledFrames.size <= 1) { toast("Minimal harus ada 1 frame"); return }
        ledFrames.removeAt(ledFrameIndex)
        ledFrameIndex = ledFrameIndex.coerceAtMost(ledFrames.lastIndex)
        selectLedFrame(ledFrameIndex)
    }

    internal fun selectLedFrame(index: Int) {
        if (ledFrames.isEmpty()) return
        ledFrameIndex = index.coerceIn(0, ledFrames.lastIndex)
        val frame = ledFrames[ledFrameIndex]
        ledCanvas?.setStates(frame.states)
        ledSpeedSeek?.progress = (frame.durationMs.coerceIn(50L, 2000L) - 50L).toInt()
        ledSpeedInfo?.text = "Durasi frame: ${frame.durationMs} ms"
        updateLedFrameInfo()
        renderLedFrames()
    }

    internal fun updateLedFrameInfo() {
        val frame = ledFrames.getOrNull(ledFrameIndex) ?: return
        val on = frame.states.count { it }
        ledFrameInfo?.text = "Frame ${ledFrameIndex + 1} / ${ledFrames.size} • $on/${ledCount} LED menyala${if (ledPlaying) " • Playing" else ""}"
    }

    internal fun renderLedFrames() {
        val strip = ledFrameStrip ?: return
        strip.removeAllViews()
        ledFrames.forEachIndexed { index, frame ->
            val b = Button(this).apply {
                text = "${index + 1}\n${frame.states.count { it }} ON"
                textSize = 10f
                setTextColor(textMain)
                background = bg(if (index == ledFrameIndex) panel else panel2, 12, if (index == ledFrameIndex) textMain else line)
                setOnClickListener { selectLedFrame(index) }
                setStateListAnimator(null)
            }
            strip.addView(b, LinearLayout.LayoutParams(dp(78), dp(54)).apply { rightMargin = dp(5) })
        }
        ledCanvas?.setStates(ledFrames.getOrNull(ledFrameIndex)?.states ?: BooleanArray(ledCount))
        updateLedFrameInfo()
    }

    internal fun playLedAnimation() {
        if (ledFrames.isEmpty()) return
        stopLedPlayback()
        ledPlaying = true
        var index = ledFrameIndex
        val run = object : Runnable {
            override fun run() {
                if (!ledPlaying || ledFrames.isEmpty()) return
                index %= ledFrames.size
                ledFrameIndex = index
                val frame = ledFrames[index]
                ledCanvas?.setStates(frame.states)
                ledSpeedSeek?.progress = (frame.durationMs.coerceIn(50L, 2000L) - 50L).toInt()
                ledSpeedInfo?.text = "Durasi frame: ${frame.durationMs} ms"
                renderLedFrames()
                index++
                ledPlayHandler.postDelayed(this, frame.durationMs.coerceIn(50L, 10000L))
            }
        }
        ledPlayRunnable = run
        ledPlayHandler.post(run)
    }

    internal fun stopLedPlayback() {
        ledPlaying = false
        ledPlayRunnable?.let { ledPlayHandler.removeCallbacks(it) }
        ledPlayRunnable = null
    }

    internal fun buildLedPatternJson(name: String? = null): JSONObject {
        val root = JSONObject()
        root.put("type", "mytools_esp_led_pattern")
        root.put("version", 2)
        root.put("name", name ?: ledNameEdit?.text?.toString()?.trim().orEmpty().ifBlank { "Untitled" })
        root.put("led_count", ledCount)
        root.put("layout", ledLayout)
        root.put("gap_dp", ledGapDp)
        val framesJson = JSONArray()
        ledFrames.forEachIndexed { index, frame ->
            val f = JSONObject()
            f.put("frame", index + 1)
            f.put("duration_ms", frame.durationMs)
            val states = JSONArray()
            frame.states.forEach { states.put(if (it) 1 else 0) }
            f.put("leds", states)
            framesJson.put(f)
        }
        root.put("frames", framesJson)
        root.put("loop", true)
        return root
    }

    internal fun saveLedPattern() {
        val name = ledNameEdit?.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) { toast("Masukkan nama pattern"); return }
        val saved = runCatching { JSONArray(prefs.getString("led_patterns", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val item = buildLedPatternJson(name).apply { put("saved_at", System.currentTimeMillis()) }
        val next = JSONArray(); next.put(item)
        for (i in 0 until saved.length()) {
            val old = saved.optJSONObject(i) ?: continue
            if (!old.optString("name").equals(name, true)) next.put(old)
        }
        while (next.length() > 30) next.remove(next.length() - 1)
        prefs.edit().putString("led_patterns", next.toString()).apply()
        toast("Pattern \"$name\" disimpan")
        renderSavedLedPatterns()
    }

    internal fun renderSavedLedPatterns() {
        val marker = content.findViewWithTag<View>("led_saved_container")
        if (marker != null) (marker.parent as? ViewGroup)?.removeView(marker)
        val box = LinearLayout(this).apply { tag = "led_saved_container"; orientation = LinearLayout.VERTICAL }
        val saved = runCatching { JSONArray(prefs.getString("led_patterns", "[]") ?: "[]") }.getOrElse { JSONArray() }
        if (saved.length() == 0) {
            box.addView(subLabel("Belum ada pattern tersimpan.", 12f))
        } else {
            for (i in 0 until saved.length()) {
                val obj = saved.optJSONObject(i) ?: continue
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(6), dp(8)); background = bg(panel2, 14, line) }
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val count = obj.optInt("led_count", 0)
                info.addView(label(obj.optString("name", "Pattern"), 14f, true))
                info.addView(subLabel("$count LED • ${obj.optString("layout", "Grid")}", 11f))
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(Button(this).apply {
                    text = "LOAD"; setTextColor(textMain); background = bg(panel, 10, line); setStateListAnimator(null); setOnClickListener { loadLedPattern(obj) }
                }, LinearLayout.LayoutParams(dp(78), dp(44)).apply { rightMargin = dp(4) })
                row.addView(Button(this).apply {
                    text = "×"; textSize = 18f; setTextColor(textMain); background = bg(panel, 10, line); setStateListAnimator(null); setOnClickListener { deleteLedPattern(obj.optString("name")) }
                }, LinearLayout.LayoutParams(dp(48), dp(44)))
                box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }
        val uploadIndex = findContentChildIndexByTag("led_upload_title")
        if (uploadIndex >= 0) content.addView(box, uploadIndex) else content.addView(box)
    }

    internal fun findContentChildIndexByTag(tagValue: String): Int {
        for (i in 0 until content.childCount) if (content.getChildAt(i).tag == tagValue) return i
        return -1
    }

    internal fun deleteLedPattern(name: String) {
        val saved = runCatching { JSONArray(prefs.getString("led_patterns", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val next = JSONArray()
        for (i in 0 until saved.length()) {
            val obj = saved.optJSONObject(i) ?: continue
            if (!obj.optString("name").equals(name, true)) next.put(obj)
        }
        prefs.edit().putString("led_patterns", next.toString()).apply()
        toast("Pattern dihapus")
        renderSavedLedPatterns()
    }

    internal fun loadLedPattern(obj: JSONObject) {
        stopLedPlayback()
        ledCount = obj.optInt("led_count", 10).coerceIn(1, 50)
        ledLayout = when (obj.optString("layout", "Grid")) {
            "Kotak" -> "Grid"
            else -> obj.optString("layout", "Grid")
        }.let { if (it in arrayOf("Grid", "Lingkaran", "Strip", "Spiral")) it else "Grid" }
        ledGapDp = obj.optInt("gap_dp", 0).coerceIn(0, 20)
        ledFrames.clear()
        val frames = obj.optJSONArray("frames")
        if (frames != null) for (i in 0 until frames.length()) {
            val f = frames.optJSONObject(i) ?: continue
            val arr = f.optJSONArray("leds")
            val states = BooleanArray(ledCount)
            if (arr != null) for (j in 0 until minOf(ledCount, arr.length())) states[j] = arr.optInt(j, 0) != 0
            ledFrames.add(LedFrameData(states, f.optLong("duration_ms", 300L).coerceIn(50L, 10000L)))
        }
        if (ledFrames.isEmpty()) ledFrames.add(LedFrameData(BooleanArray(ledCount), 300L))
        ledFrameIndex = 0
        ledNameEdit?.setText(obj.optString("name", "Pattern"))
        ledCountLabel?.text = ledCount.toString()
        ledLayoutLabel?.text = ledLayout
        ledCanvas?.setLedConfig(ledCount, ledLayout)
        ledCanvas?.setLedGap(ledGapDp)
        ledGapSeek?.progress = ledGapDp
        renderLedFrames()
        toast("Pattern dimuat")
    }

    internal fun uploadLedPattern() {
        if (ledFrames.isEmpty()) { toast("Belum ada frame"); return }
        val endpoint = ledEndpointEdit?.text?.toString()?.trim().orEmpty()
        if (endpoint.isBlank()) { toast("Masukkan URL endpoint ESP"); return }
        runCatching {
            val uri = Uri.parse(endpoint)
            if (uri.scheme != "http" && uri.scheme != "https") error("URL harus http:// atau https://")
        }.onFailure { toast(it.message ?: "URL tidak valid"); return }
        prefs.edit().putString("led_endpoint", endpoint).apply()
        val json = buildLedPatternJson()
        toast("Mengirim pattern ke ESP…")
        toolThread {
            val result = runCatching {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 7000; readTimeout = 7000; doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8"); setRequestProperty("Accept", "application/json")
                }
                conn.outputStream.use { it.write(json.toString().toByteArray(StandardCharsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }?.take(500).orEmpty()
                conn.disconnect()
                if (code !in 200..299) error("HTTP $code ${body.ifBlank { "ESP menolak request" }}")
                "Berhasil • HTTP $code${if (body.isBlank()) "" else "\nESP: $body"}"
            }.getOrElse { "Upload gagal: ${it.message ?: it.javaClass.simpleName}" }
            runOnUiThread {
                if (result.startsWith("Berhasil")) toast(result) else AlertDialog.Builder(this).setTitle("Upload ESP").setMessage(result).setPositiveButton("OK", null).show()
            }
        }
    }

    internal inner class LedCanvasView(context: Context) : View(context) {
        internal var count = 10
        internal var layoutMode = "Grid"
        internal var states = BooleanArray(count)
        internal val positions = ArrayList<android.graphics.PointF>()
        internal var gapDp = 0
        internal val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        internal val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        var onLedClicked: ((Int) -> Unit)? = null

        init { setLayerType(View.LAYER_TYPE_SOFTWARE, null); isClickable = true }

        fun setLedConfig(newCount: Int, newLayout: String) {
            count = newCount.coerceIn(1, 50)
            layoutMode = when (newLayout) { "Kotak" -> "Grid"; "Lingkaran", "Strip", "Spiral" -> newLayout; else -> "Grid" }
            if (states.size != count) {
                val next = BooleanArray(count)
                for (i in 0 until minOf(states.size, count)) next[i] = states[i]
                states = next
            }
            recalcPositions(width, height); invalidate()
        }

        fun setStates(newStates: BooleanArray) { states = newStates.copyOf(count); invalidate() }
        fun setLedGap(gap: Int) { gapDp = gap.coerceIn(0, 20); recalcPositions(width, height); invalidate() }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { recalcPositions(w, h) }

        internal fun recalcPositions(w: Int, h: Int) {
            positions.clear()
            if (w <= 0 || h <= 0) return
            val cx = w / 2f; val cy = h / 2f
            val margin = dp(12).toFloat(); val extra = dp(gapDp).toFloat()
            when (layoutMode) {
                "Grid" -> {
                    val cols = min(10, Math.ceil(Math.sqrt(count.toDouble())).toInt().coerceAtLeast(1))
                    val rows = Math.ceil(count.toDouble() / cols).toInt().coerceAtLeast(1)
                    val stepX = ((w - margin * 2f - extra * (cols - 1)) / cols.coerceAtLeast(1)).coerceAtLeast(dp(26).toFloat())
                    val stepY = ((h - margin * 2f - extra * (rows - 1)) / rows.coerceAtLeast(1)).coerceAtLeast(dp(26).toFloat())
                    val totalW = (cols - 1) * (stepX + extra); val totalH = (rows - 1) * (stepY + extra)
                    val sx = cx - totalW / 2f; val sy = cy - totalH / 2f
                    for (i in 0 until count) {
                        val row = i / cols; val col = i % cols
                        positions.add(android.graphics.PointF(sx + col * (stepX + extra), sy + row * (stepY + extra)))
                    }
                }
                "Lingkaran" -> {
                    val r = (min(w, h) / 2f - dp(34)).coerceAtLeast(dp(24).toFloat())
                    if (count == 1) positions.add(android.graphics.PointF(cx, cy)) else for (i in 0 until count) {
                        val a = -Math.PI / 2 + i * (2 * Math.PI / count)
                        positions.add(android.graphics.PointF(cx + Math.cos(a).toFloat() * r, cy + Math.sin(a).toFloat() * r))
                    }
                }
                "Strip" -> {
                    val step = ((w - margin * 2f - extra * (count - 1)) / count.coerceAtLeast(1)).coerceAtLeast(dp(26).toFloat())
                    val total = (count - 1) * (step + extra)
                    val sx = cx - total / 2f
                    for (i in 0 until count) positions.add(android.graphics.PointF(sx + i * (step + extra), cy))
                }
                "Spiral" -> {
                    val maxR = (min(w, h) / 2f - dp(24)).coerceAtLeast(dp(20).toFloat())
                    for (i in 0 until count) {
                        val t = if (count <= 1) 0f else i.toFloat() / (count - 1).toFloat()
                        val r = maxR * t
                        val a = -Math.PI / 2 + i * (Math.PI * 2.2 / 10.0)
                        positions.add(android.graphics.PointF(cx + Math.cos(a).toFloat() * r, cy + Math.sin(a).toFloat() * r))
                    }
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(panel)
            if (positions.size != count) recalcPositions(width, height)
            val radius = (min(width, height) * 0.04f).coerceIn(dp(10).toFloat(), dp(17).toFloat())
            positions.forEachIndexed { index, p ->
                val on = states.getOrNull(index) == true
                if (on) {
                    glowPaint.color = Color.rgb(120, 120, 120)
                    glowPaint.setShadowLayer(radius * 0.9f, 0f, 0f, Color.argb(150, 52, 132, 255))
                    if (layoutMode == "Grid") canvas.drawRoundRect(p.x-radius, p.y-radius, p.x+radius, p.y+radius, radius*.25f, radius*.25f, glowPaint)
                    else canvas.drawCircle(p.x, p.y, radius*1.05f, glowPaint)
                    glowPaint.clearShadowLayer()
                    paint.color = Color.rgb(170, 170, 170)
                } else paint.color = Color.rgb(65, 70, 78)
                if (layoutMode == "Grid") canvas.drawRoundRect(p.x-radius*.82f, p.y-radius*.82f, p.x+radius*.82f, p.y+radius*.82f, radius*.22f, radius*.22f, paint)
                else canvas.drawCircle(p.x, p.y, radius, paint)
                paint.color = if (on) Color.WHITE else Color.rgb(165, 170, 178)
                paint.textSize = dp(8).toFloat(); paint.textAlign = Paint.Align.CENTER
                canvas.drawText((index + 1).toString(), p.x, p.y + dp(3), paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (positions.size != count) recalcPositions(width, height)
            if (event.action == MotionEvent.ACTION_DOWN) {
                var nearest = -1; var dist = Float.MAX_VALUE
                val hit = (min(width, height) * .04f).coerceIn(dp(12).toFloat(), dp(20).toFloat()) * 2f
                positions.forEachIndexed { i, p ->
                    val dx = event.x-p.x; val dy = event.y-p.y; val d = Math.sqrt((dx*dx+dy*dy).toDouble()).toFloat()
                    if (d <= hit && d < dist) { nearest=i; dist=d }
                }
                if (nearest >= 0) onLedClicked?.invoke(nearest)
                performClick(); return true
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }


    // ---------- ESP / IoT TOOLKIT ----------

    internal fun espTools() {
        clearPage("ESP Tools")
        content.addView(label("ESP Tools", 22f, true))
        content.addView(subLabel("Toolkit untuk ESP32 / ESP8266: hitung nilai, cek pin, dan siapkan parameter proyek.", 12f))

        sectionTitle("Hardware")
        content.addView(button("GPIO Reference") {
            output("ESP32 GPIO reference:\n\nGPIO 0  • Boot/strapping\nGPIO 1  • UART0 TX\nGPIO 3  • UART0 RX\nGPIO 6–11 • Umumnya terhubung flash internal — hindari\nGPIO 34–39 • Input only\n\nCatatan: fungsi pin dapat berbeda menurut board. Periksa pinout board sebelum memasang hardware.")
        })
        content.addView(button("Pinout ESP32 / ESP8266") {
            output("ESP32 umum: GPIO0–39 (beberapa GPIO tidak tersedia pada semua board).\nESP8266 NodeMCU: D0=GPIO16, D1=GPIO5, D2=GPIO4, D3=GPIO0, D4=GPIO2, D5=GPIO14, D6=GPIO12, D7=GPIO13, D8=GPIO15.\n\nBoot pins dan pin flash memiliki batasan khusus.")
        })
        content.addView(button("LED Resistor Calculator") {
            espLedResistorCalculator()
        })
        content.addView(button("💡 LED Canvas + Animation Studio") {
            espLedStudio()
        })
        content.addView(button("Voltage Divider Calculator") {
            openTool("dividercalc")
        })

        sectionTitle("ADC / PWM")
        content.addView(button("ADC → Voltage") {
            espAdcCalculator()
        })
        content.addView(button("PWM / Duty Cycle") {
            openTool("pwmcalc")
        })

        sectionTitle("Serial / Network")
        content.addView(button("UART / Serial Settings") {
            output("Baud rate umum:\n9600 • 19200 • 38400 • 57600 • 115200\n\nFormat umum: 8 data bit, No parity, 1 stop bit (8N1).\n\nPastikan baud rate ESP dan perangkat lawan sama.")
        })
        content.addView(button("Wi-Fi Info") {
            openTool("wifi")
        })
        content.addView(button("Power / Current Helper") {
            openTool("powercalc")
        })

        sectionTitle("ESP Control & Network")
        content.addView(button("🔎 Auto-Discovery ESP (mDNS/NSD)") { espAutoDiscovery() })
        content.addView(button("📡 Device Manager") { espDeviceManager() })
        content.addView(button("🎛 GPIO Controller") { espGpioController() })
        content.addView(button("📊 Sensor Dashboard") { espSensorDashboard() })
        content.addView(button("📶 Wi-Fi Manager") { espWifiManager() })
        content.addView(button("🔄 OTA Firmware") { espOtaFirmware() })
        content.addView(button("🌐 HTTP / API Tester") { espHttpApiTester() })
        content.addView(button("📬 MQTT Client") { espMqttClient() })
        content.addView(button("🔌 USB / OTG Info") { espUsbInfo() })
        content.addView(button("🖥 TCP Serial Monitor") { espTcpSerialMonitor() })

        sectionTitle("Quick Notes")
        val notes = listOf(
            "⚠ 3.3V logic: jangan langsung memberi 5V ke GPIO ESP32.",
            "⚠ GPIO 34–39 pada ESP32 klasik adalah input-only.",
            "⚠ Hindari GPIO strapping saat boot jika rangkaian eksternal mengubah levelnya.",
            "✓ Gunakan resistor seri untuk LED dan pembagi tegangan untuk input analog yang melebihi batas ADC."
        )
        notes.forEach { content.addView(subLabel(it, 13f).apply { setPadding(dp(6), dp(5), dp(6), dp(5)) }) }
    }

    internal fun espLedResistorCalculator() {
        clearPage("LED Resistor")
        content.addView(label("LED Resistor Calculator", 22f, true))
        content.addView(subLabel("R = (Vsupply − Vled) / Iled", 12f))
        val vs = calcDisplay("Tegangan supply, contoh 3.3")
        val vf = calcDisplay("Forward voltage LED, contoh 2.0")
        val ma = calcDisplay("Arus LED (mA), contoh 10")
        content.addView(vs); content.addView(vf); content.addView(ma)
        content.addView(button("Hitung Resistor") {
            val supply = vs.numberValue()
            val led = vf.numberValue()
            val currentMa = ma.numberValue()
            if (supply == null || led == null || currentMa == null || currentMa <= 0.0) {
                toast("Masukkan angka yang valid")
            } else {
                val r = (supply - led) / (currentMa / 1000.0)
                if (r <= 0.0) output("VLED harus lebih kecil dari Vsupply")
                else output("Resistor ideal ≈ ${"%.1f".format(Locale.US, r)} Ω\nNilai praktis terdekat: ${preferredResistor(r)} Ω")
            }
        })
    }

    internal fun espAdcCalculator() {
        clearPage("ESP ADC")
        content.addView(label("ADC → Voltage", 22f, true))
        content.addView(subLabel("V = ADC / (2^bits − 1) × Vref", 12f))
        val adc = calcDisplay("Nilai ADC")
        val bits = calcDisplay("Resolusi bit, contoh 12")
        val vref = calcDisplay("Vref, contoh 3.3")
        content.addView(adc); content.addView(bits); content.addView(vref)
        content.addView(button("Hitung Tegangan") {
            val a = adc.numberValue(); val b = bits.numberValue(); val v = vref.numberValue()
            if (a == null || b == null || v == null || b <= 0.0) toast("Masukkan angka yang valid")
            else {
                val max = Math.pow(2.0, b) - 1.0
                output("Tegangan ≈ ${"%.4f".format(Locale.US, a / max * v)} V")
            }
        })
    }

    internal fun EditText.numberValue(): Double? = text.toString().trim().replace(',', '.').toDoubleOrNull()

    internal fun preferredResistor(value: Double): Int {
        val e24 = doubleArrayOf(10.0, 11.0, 12.0, 13.0, 15.0, 16.0, 18.0, 20.0, 22.0, 24.0, 27.0, 30.0, 33.0, 36.0, 39.0, 43.0, 47.0, 51.0, 56.0, 62.0, 68.0, 75.0, 82.0, 91.0)
        if (value <= 0.0) return 0
        val decade = Math.pow(10.0, Math.floor(Math.log10(value)))
        val normalized = value / decade
        val nearest = e24.minByOrNull { Math.abs(it - normalized) } ?: normalized
        return Math.round(nearest * decade).toInt()
    }

    // ---------- NEW TOOLS: CLIPBOARD / OCR / UNIT / APK / NETWORK SCANNER ----------

    internal fun clipboardManagerTool() {
        clearPage("Clipboard Manager")
        content.addView(label("Clipboard Manager", 22f, true))
        content.addView(subLabel("Riwayat clipboard disimpan lokal di perangkat. Android membatasi akses clipboard di background; monitor aktif hanya saat tool ini dibuka.", 12f))
        val current = clipboardText()
        if (current != null) {
            content.addView(label("Clipboard saat ini", 13f, true))
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = bg(panel2, 14, line) }
            card.addView(label(current.take(4000), 14f))
            card.addView(button("Salin lagi") { copyText(current) })
            content.addView(card)
            saveClipboard(current)
        }
        content.addView(button("Ambil Clipboard Sekarang") {
            val value = clipboardText()
            if (value == null) toast("Clipboard kosong atau bukan teks") else { saveClipboard(value); clipboardManagerTool() }
        })
        content.addView(button("Hapus Riwayat Clipboard") { prefs.edit().remove("clipboard_history").apply(); clipboardManagerTool() })
        content.addView(label("Riwayat", 15f, true))
        val arr = runCatching { JSONArray(prefs.getString("clipboard_history", "[]") ?: "[]") }.getOrElse { JSONArray() }
        if (arr.length() == 0) content.addView(subLabel("Belum ada riwayat clipboard.", 12f))
        for (i in 0 until arr.length()) {
            val value = arr.optString(i)
            if (value.isBlank()) continue
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = bg(panel2, 14, line)
                setOnClickListener { copyText(value) }
            }
            card.addView(label(value.take(700), 13f))
            card.addView(subLabel("Tap untuk menyalin • ${value.length} karakter", 10f))
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        startClipboardMonitor()
    }

    internal fun clipboardText(): String? {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        if (!cm.hasPrimaryClip()) return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    }

    internal fun saveClipboard(value: String) {
        val clean = value.trim()
        if (clean.isEmpty()) return
        val arr = runCatching { JSONArray(prefs.getString("clipboard_history", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val next = JSONArray()
        next.put(clean.take(10000))
        for (i in 0 until arr.length()) {
            val old = arr.optString(i)
            if (old.isNotBlank() && old != clean && next.length() < 50) next.put(old)
        }
        prefs.edit().putString("clipboard_history", next.toString()).apply()
    }

    internal fun startClipboardMonitor() {
        stopClipboardMonitor()
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
            runOnUiThread { clipboardText()?.let { saveClipboard(it) } }
        }
        clipboardManager = cm
        clipboardListener = listener
        cm.addPrimaryClipChangedListener(listener)
    }

    internal fun stopClipboardMonitor() {
        val cm = clipboardManager
        val listener = clipboardListener
        if (cm != null && listener != null) runCatching { cm.removePrimaryClipChangedListener(listener) }
        clipboardManager = null
        clipboardListener = null
    }

    internal fun ocrTool() {
        clearPage("OCR Text Scanner")
        addToolHeader("OCR Text Scanner", "Ambil teks dari gambar lalu edit, salin, atau bagikan hasilnya.", "OCR")
        content.addView(toolSection("PREVIEW"))
        val preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = bg(panel2, 14, line)
            layoutParams = LinearLayout.LayoutParams(-1, dp(230)).apply { bottomMargin = dp(8) }
        }
        content.addView(preview)
        val resultBox = edit("Hasil OCR", true)
        content.addView(resultBox)
        content.addView(button("Pilih Gambar") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
            }, 1020)
        })
        content.addView(button("OCR Gambar Terpilih") {
            val uri = pendingOcrUri
            if (uri == null) { toast("Pilih gambar terlebih dahulu"); return@button }
            runOcr(uri) { text ->
                resultBox.setText(text)
                if (text.isBlank()) toast("Tidak ada teks yang terdeteksi")
            }
        })
        // Keep a lightweight callback reference for the ActivityResult handler.
        pendingOcrView = resultBox
        pendingOcrPreview = preview
    }

    internal var pendingOcrView: EditText? = null
    internal var pendingOcrPreview: ImageView? = null
    internal var pendingOcrUri: Uri? = null

    internal fun runOcr(uri: Uri, onResult: (String) -> Unit) {
        toolThread {
            runCatching {
                val image = InputImage.fromFilePath(this, uri)
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
                    .addOnSuccessListener { result -> runOnUiThread { onResult(result.text) } }
                    .addOnFailureListener { err -> runOnUiThread { toast("OCR gagal: ${err.message}") } }
            }.onFailure { err -> runOnUiThread { toast("Gambar tidak bisa dibuka: ${err.message}") } }
        }
    }

    internal fun unitConverterProTool() {
        clearPage("Unit Converter")
        addToolHeader("Unit Converter", "Konversi nilai dengan pasangan satuan yang jelas dan cepat.", "↔")
        val categories = arrayOf("Panjang", "Berat", "Suhu", "Luas", "Volume", "Waktu", "Kecepatan", "Tekanan", "Data", "Energi")
        val from = Spinner(this); val to = Spinner(this); val value = edit("Nilai")
        val category = Spinner(this)
        category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        content.addView(category, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(7) })
        content.addView(value)
        content.addView(from, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(7) })
        content.addView(to, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(7) })
        val result = label("Hasil akan tampil di sini", 17f, true)
        content.addView(result)
        fun setUnits(index: Int) {
            val units = when(index) {
                0 -> arrayOf("Meter (m)", "Kilometer (km)", "Centimeter (cm)", "Millimeter (mm)", "Inch (in)", "Feet (ft)", "Yard (yd)", "Mile (mi)")
                1 -> arrayOf("Gram (g)", "Kilogram (kg)", "Milligram (mg)", "Pound (lb)", "Ounce (oz)")
                2 -> arrayOf("Celsius (°C)", "Fahrenheit (°F)", "Kelvin (K)")
                3 -> arrayOf("m²", "km²", "cm²", "ft²", "acre")
                4 -> arrayOf("Liter (L)", "Milliliter (mL)", "m³", "cm³", "gallon US")
                5 -> arrayOf("Second", "Minute", "Hour", "Day")
                6 -> arrayOf("m/s", "km/h", "mph", "knot")
                7 -> arrayOf("Pa", "kPa", "bar", "psi", "atm")
                8 -> arrayOf("Byte", "KB", "MB", "GB", "TB")
                else -> arrayOf("Joule (J)", "Kilojoule (kJ)", "calorie (cal)", "kWh", "Wh")
            }
            from.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units)
            to.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units)
            if (units.size > 1) to.setSelection(1)
        }
        setUnits(0)
        category.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { setUnits(position) }
        }
        content.addView(button("Konversi") {
            val x = value.text.toString().replace(',', '.').toDoubleOrNull()
            if (x == null) { result.text = "Nilai tidak valid"; return@button }
            val cat = category.selectedItemPosition
            val a = from.selectedItemPosition; val b = to.selectedItemPosition
            val out = convertUnits(x, cat, a, b)
            result.text = "${fmt(out)} ${to.selectedItem}"
        })
        content.addView(button("Tukar Satuan") {
            val old = from.selectedItemPosition; from.setSelection(to.selectedItemPosition); to.setSelection(old)
        })
    }

    internal fun convertUnits(x: Double, cat: Int, a: Int, b: Int): Double {
        if (a == b) return x
        return when(cat) {
            0 -> { val f = doubleArrayOf(1.0,1000.0,0.01,0.001,0.0254,0.3048,0.9144,1609.344); x*f[a]/f[b] }
            1 -> { val f = doubleArrayOf(0.001,1.0,0.000001,0.45359237,0.028349523125); x*f[a]/f[b] }
            2 -> { val c = when(a) {0->x;1->(x-32)*5/9;else->x-273.15}; when(b){0->c;1->c*9/5+32;else->c+273.15} }
            3 -> { val f=doubleArrayOf(1.0,1e6,1e-4,0.09290304,4046.8564224); x*f[a]/f[b] }
            4 -> { val f=doubleArrayOf(1.0,0.001,1000.0,0.001,3.785411784); x*f[a]/f[b] }
            5 -> { val f=doubleArrayOf(1.0,60.0,3600.0,86400.0); x*f[a]/f[b] }
            6 -> { val f=doubleArrayOf(1.0,0.2777777778,0.44704,0.5144444444); x*f[a]/f[b] }
            7 -> { val f=doubleArrayOf(1.0,1000.0,100000.0,6894.757293,101325.0); x*f[a]/f[b] }
            8 -> { val f=doubleArrayOf(1.0,1024.0,1048576.0,1073741824.0,1099511627776.0); x*f[a]/f[b] }
            else -> { val f=doubleArrayOf(1.0,1000.0,4.184,3600000.0,3600.0); x*f[a]/f[b] }
        }
    }

    internal fun apkAnalyzerTool() {
        clearPage("APK Analyzer Lengkap")
        toolWorkspace("APK Analyzer Lengkap", "Periksa package, SDK, permission, DEX, native library, signature, dan isi ZIP.", "android-studio")
        toolWorkspaceSection("ANALYSIS", "Pilih APK lalu jalankan analisis. Hasil muncul di bawah.")
        content.addView(button("Pilih APK") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/vnd.android.package-archive"; addCategory(Intent.CATEGORY_OPENABLE)
            }, 1021)
        })
        content.addView(button("Analisis APK terakhir") { pendingApkUri?.let { analyzeApk(it) } ?: toast("Pilih APK terlebih dahulu") })
        pendingApkOutput?.let { content.addView(it) }
    }

    internal var pendingApkUri: Uri? = null
    internal var apkCompareFirstUri: Uri? = null
    internal var wsSocket: Socket? = null
    internal var wsInput: InputStream? = null
    internal var wsOutput: OutputStream? = null
    internal var pendingApkOutput: TextView? = null

    internal fun analyzeApk(uri: Uri) {
        pendingApkUri = uri
        val box = label("Menganalisis...", 13f)
        pendingApkOutput = box
        content.addView(box)
        toolThread {
            val result = runCatching { buildApkReport(uri) }.getOrElse { "APK Analyzer error: ${it.message}" }
            runOnUiThread { box.text = result }
        }
    }

    internal fun buildApkReport(uri: Uri): String {
        val temp = File(cacheDir, "analyzer_${System.currentTimeMillis()}.apk")
        val maxApkBytes = 100L * 1024L * 1024L
        val afdLength = contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
        if (afdLength > maxApkBytes) error("APK terlalu besar. Batas analisis adalah 100 MB.")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxApkBytes) error("APK terlalu besar. Batas analisis adalah 100 MB.")
                    output.write(buffer, 0, n)
                }
            }
        } ?: error("Tidak bisa membaca APK")
        val pm = packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(temp.absolutePath, flags)
        val sb = StringBuilder()
        sb.append("=== PACKAGE ===\n")
        sb.append("File: ${queryName(uri) ?: temp.name}\nSize: ${bytesText(temp.length())}\n")
        if (info != null) {
            sb.append("Package: ${info.packageName}\nVersion: ${info.versionName} (${info.versionCode})\n")
            val appInfo = info.applicationInfo
            if (appInfo != null) {
                if (Build.VERSION.SDK_INT >= 24) sb.append("Min SDK: ${appInfo.minSdkVersion}\nTarget SDK: ${appInfo.targetSdkVersion}\n")
                sb.append("Label: ${pm.getApplicationLabel(appInfo)}\n")
            }
            info.requestedPermissions?.let { p -> sb.append("Permissions (${p.size}):\n"); p.forEach { sb.append("  • $it\n") } }
            info.activities?.let { a -> sb.append("Activities: ${a.size}\n"); a.forEach { sb.append("  • ${it.name}\n") } }
            info.services?.let { a -> sb.append("Services: ${a.size}\n"); a.forEach { sb.append("  • ${it.name}\n") } }
            info.receivers?.let { a -> sb.append("Receivers: ${a.size}\n"); a.forEach { sb.append("  • ${it.name}\n") } }
            info.providers?.let { a -> sb.append("Providers: ${a.size}\n"); a.forEach { sb.append("  • ${it.authority}\n") } }
            sb.append("\n=== SIGNATURE ===\n")
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            signatures?.forEachIndexed { index, sig ->
                val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                sb.append("Signer ${index + 1} SHA-256: ${digest.joinToString(":") { "%02X".format(it) }}\n")
            }
        } else sb.append("PackageManager tidak dapat membaca manifest APK.\n")
        sb.append("\n=== ZIP / DEX / NATIVE ===\n")
        ZipFile(temp).use { zip ->
            var files = 0; var totalUncompressed = 0L; var dex = 0; var native = 0; var resources = false; var manifest = false
            val top = mutableListOf<String>()
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement(); if (e.isDirectory) continue
                files++; totalUncompressed += e.size.coerceAtLeast(0)
                if (e.name.endsWith(".dex")) dex++
                if (e.name.startsWith("lib/") && e.name.endsWith(".so")) native++
                if (e.name == "resources.arsc") resources = true
                if (e.name == "AndroidManifest.xml") manifest = true
                if (top.size < 80) top.add("${e.name}  ${bytesText(e.size.coerceAtLeast(0))}")
            }
            sb.append("Entries: $files\nUncompressed total: ${bytesText(totalUncompressed)}\nDEX files: $dex\nNative .so: $native\nresources.arsc: $resources\nAndroidManifest.xml: $manifest\n\nTop entries:\n")
            top.forEach { sb.append("  • $it\n") }
        }
        temp.delete()
        return sb.toString()
    }

    // ---------- NATIVE DEVICE / STORAGE / APP / NETWORK TOOLS ----------

    internal fun accentTeal(): Int = Color.rgb(0, 137, 123)

    internal fun modernPageTitle(titleText:String, subtitleText:String, icon:String){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(2),dp(4),dp(2),dp(14))}
        val backBtn=TextView(this).apply{text="‹";textSize=38f;gravity=Gravity.CENTER;colorize(textMain);setOnClickListener{onBackPressed()}}
        row.addView(backBtn,LinearLayout.LayoutParams(dp(42),dp(48)))
        val texts=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};texts.addView(label(titleText,22f,true));texts.addView(subLabel(subtitleText,12f));row.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
        val iv=MdiIconView(this).apply{setIconName(icon);setIconSize(22f);setTextColor(textMain);background=bg(Color.rgb(245,247,249),18)};row.addView(iv,LinearLayout.LayoutParams(dp(48),dp(48)));content.addView(row)
    }
    internal fun TextView.colorize(c:Int){setTextColor(c)}
    internal fun modernToolListRow(titleText:String,sub:String,icon:String,onClick:()->Unit){val card=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(12),dp(12),dp(12));background=rippleBg(Color.WHITE,24,line);elevation=dp(1).toFloat();setOnClickListener{onClick()}};val ib=MdiIconView(this).apply{setIconName(icon);setIconSize(22f);setTextColor(Color.rgb(73,83,94));background=bg(Color.rgb(239,243,246),17)};card.addView(ib,LinearLayout.LayoutParams(dp(56),dp(56)).apply{rightMargin=dp(14)});val tx=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};tx.addView(label(titleText,16f,true));tx.addView(subLabel(sub,12f));card.addView(tx,LinearLayout.LayoutParams(0,-2,1f));card.addView(label("›",34f).apply{setTextColor(Color.rgb(100,110,120))},LinearLayout.LayoutParams(dp(30),dp(50)));content.addView(card,LinearLayout.LayoutParams(-1,dp(96)).apply{bottomMargin=dp(10);leftMargin=dp(2);rightMargin=dp(2)})}
    internal fun modernMetricGrid(items:List<Pair<String,String>>){val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};items.chunked(2).forEach{pair->val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};pair.forEachIndexed{idx,(a,b)->val c=modernSmallMetric(a,b,"information-outline");row.addView(c,LinearLayout.LayoutParams(0,-2,1f).apply{bottomMargin=dp(6);if(idx==1)leftMargin=dp(6)})};if(pair.size==1)row.addView(View(this),LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(6)});grid.addView(row)};content.addView(grid,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(2)})}
    internal fun modernSmallMetric(name:String,value:String,icon:String):LinearLayout{val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=bg(Color.WHITE,16,line)};val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};val iv=MdiIconView(this).apply{setIconName(icon);setIconSize(17f);setTextColor(textMain)};top.addView(iv,LinearLayout.LayoutParams(dp(26),dp(26)));top.addView(subLabel(name,11f),LinearLayout.LayoutParams(0,-2,1f));box.addView(top);box.addView(label(value,15f,true));return box}
    internal fun setMetricBox(box:LinearLayout,name:String,value:String,sub:String){if(box.childCount>1)(box.getChildAt(1) as? TextView)?.text=value;if(box.childCount>2)(box.getChildAt(2) as? TextView)?.text=sub else box.addView(subLabel(sub,10f))}
    internal fun modernInfoCard(titleText:String,valueText:String){val box=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(11),dp(14),dp(11));background=bg(Color.WHITE,16,line)};val tx=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};tx.addView(label(titleText,12f,true));tx.addView(subLabel(valueText,11f));box.addView(tx,LinearLayout.LayoutParams(0,-2,1f));box.addView(label("›",28f).apply{setTextColor(Color.rgb(80,90,100))},LinearLayout.LayoutParams(dp(26),dp(40)));content.addView(box,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})}
    internal fun modernActionRow(titleText:String,icon:String,onClick:()->Unit){content.addView(modernActionButton(titleText,icon,onClick),LinearLayout.LayoutParams(-1,dp(58)).apply{bottomMargin=dp(8)})}
    internal fun modernActionButton(titleText:String,icon:String,onClick:()->Unit):TextView{return TextView(this).apply{text=titleText;textSize=13f;gravity=Gravity.CENTER;setTypeface(typeface,android.graphics.Typeface.BOLD);setTextColor(textMain);background=rippleBg(Color.WHITE,16,line);setOnClickListener{onClick()};isClickable=true;minHeight=dp(52)}}
    internal fun weightLp(weight:Float,gap:Int)=LinearLayout.LayoutParams(0,-2,weight).apply{bottomMargin=dp(6);if(gap>0)leftMargin=dp(gap)}
    internal data class BatteryHero(val percent:TextView,val status:TextView,val icon:MdiIconView)
    internal fun modernBatteryHero():BatteryHero{val card=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(14),dp(14),dp(14));background=bg(Color.WHITE,18,line)};val iv=MdiIconView(this).apply{setIconName("battery-high");setIconSize(46f);setTextColor(Color.rgb(244,67,54));background=bg(Color.rgb(255,245,245),18)};card.addView(iv,LinearLayout.LayoutParams(dp(76),dp(76)).apply{rightMargin=dp(14)});val tx=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val pct=label("--%",28f,true);val st=label("Baterai lemah",12f,true);tx.addView(pct);tx.addView(st);card.addView(tx,LinearLayout.LayoutParams(0,-2,1f));content.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)});return BatteryHero(pct,st,iv)}
    internal data class WifiHero(val ssid:TextView,val status:TextView,val rssi:TextView,val icon:MdiIconView,val linkBox:LinearLayout,val freqBox:LinearLayout,val channelBox:LinearLayout)
    internal fun modernWifiHero():WifiHero{val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(14),dp(14),dp(12));background=bg(Color.WHITE,18,line)};val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};val iv=MdiIconView(this).apply{setIconName("wifi");setIconSize(34f);setTextColor(accentTeal());background=bg(Color.rgb(238,249,246),22)};top.addView(iv,LinearLayout.LayoutParams(dp(66),dp(66)).apply{rightMargin=dp(12)});val tx=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val ssid=label("<unknown ssid>",18f,true);val con=label("●  Terhubung",12f,true);con.setTextColor(accentTeal());tx.addView(ssid);tx.addView(con);top.addView(tx,LinearLayout.LayoutParams(0,-2,1f));val rssi=label("-81 dBm",17f,true);top.addView(rssi);card.addView(top);val metrics=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};val a=modernSmallMetric("Link speed","-","speedometer");val b=modernSmallMetric("Frekuensi","-","sine-wave");val c=modernSmallMetric("Channel","-","access-point");metrics.addView(a,weightLp(1f,0));metrics.addView(b,weightLp(1f,6));metrics.addView(c,weightLp(1f,6));card.addView(metrics);content.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)});return WifiHero(ssid,con,rssi,iv,a,b,c)}
    internal fun setWifiMetric(box:LinearLayout,name:String,value:String,sub:String){if(box.childCount>=2)(box.getChildAt(1) as? TextView)?.text=value;if(box.childCount>=3)(box.getChildAt(2) as? TextView)?.text=sub else box.addView(subLabel(sub,9f))}
    internal fun modernChartCard(titleText:String,chart:MiniLineChart){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(8));background=bg(Color.WHITE,18,line)};box.addView(label(titleText,13f,true));box.addView(chart,LinearLayout.LayoutParams(-1,dp(145)));content.addView(box,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})}
    internal fun modernDeviceHero(){val card=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12));background=bg(Color.WHITE,18,line)};val iv=MdiIconView(this).apply{setIconName("cellphone-information");setIconSize(30f);setTextColor(textMain);background=bg(Color.rgb(239,243,246),18)};card.addView(iv,LinearLayout.LayoutParams(dp(66),dp(66)).apply{rightMargin=dp(12)});val tx=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};tx.addView(label(Build.MODEL,18f,true));tx.addView(subLabel("Android ${Build.VERSION.RELEASE}",11f));tx.addView(subLabel(Build.MANUFACTURER,11f));card.addView(tx);content.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})}
    internal fun modernProgressCard(name:String,pct:Int,sub:String,value:String){val box=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=bg(Color.WHITE,16,line)};val tx=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};tx.addView(label(name,14f,true));tx.addView(subLabel(sub,10f));box.addView(tx,LinearLayout.LayoutParams(0,-2,1f));val right=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.RIGHT};right.addView(label("$pct%",12f,true).apply{gravity=Gravity.RIGHT});val track=FrameLayout(this);track.background=bg(Color.rgb(232,237,241),6);val fill=View(this);fill.background=bg(if(pct>=80)Color.rgb(244,67,54) else accentTeal(),6);track.addView(fill,FrameLayout.LayoutParams((dp(105)*pct/100).coerceAtLeast(dp(4)),dp(6)));right.addView(track,LinearLayout.LayoutParams(dp(105),dp(6)));right.addView(subLabel(value,9f));box.addView(right);content.addView(box,LinearLayout.LayoutParams(-1,dp(72)).apply{bottomMargin=dp(7)})}
    internal fun cpuLoadPercent():Int{val cores=Runtime.getRuntime().availableProcessors().coerceAtLeast(1);return runCatching{val s=File("/proc/loadavg").readText().trim().split(" ")[0].toFloat();(s/cores*100f).roundToInt().coerceIn(0,100)}.getOrDefault(0)}
    internal fun infoLine(k:String,v:String):LinearLayout{val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(5),0,dp(5))};row.addView(subLabel(k,10f),LinearLayout.LayoutParams(0,-2,1f));row.addView(label(v,10f,false));return row}
    internal fun wifiChannel(freq:Int):Int=when{freq==2484->14;freq in 2412..2472->((freq-2407)/5);freq in 5170..5895->((freq-5000)/5);freq in 5955..7115->((freq-5950)/5);else->0}
    internal fun ipv4MaskFromPrefix(prefix:Int):String {
        if(prefix !in 0..32) return "-"
        val mask = if(prefix == 0) 0L else (0xffffffffL shl (32-prefix)) and 0xffffffffL
        return listOf((mask shr 24) and 255,(mask shr 16) and 255,(mask shr 8) and 255,mask and 255).joinToString(".")
    }
    internal fun wifiLinkProperties():android.net.LinkProperties? {
        val cm=getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val n=cm.activeNetwork ?: return null
        val caps=cm.getNetworkCapabilities(n) ?: return null
        return if(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) cm.getLinkProperties(n) else null
    }
    internal fun wifiIpText():String {
        val lp=wifiLinkProperties() ?: return "Tidak terhubung"
        return lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress ?: "-"
    }
    internal fun requestWifiPermissions(){
        val req=mutableListOf<String>()
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if(req.isNotEmpty()) ActivityCompat.requestPermissions(this,req.toTypedArray(),2001)
    }

    internal fun testWifiConnection(){toolThread {val r=runCatching{val cm=getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager;val n=cm.activeNetwork;val caps=if(n!=null)cm.getNetworkCapabilities(n)else null;val validated=caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true;val host=InetAddress.getByName("1.1.1.1");val t=System.currentTimeMillis();val ok=host.isReachable(1800);val ms=System.currentTimeMillis()-t;"Latency: ${ms} ms\\nInternet: ${if(ok||validated)"Terhubung" else "Tidak terhubung"}"}.getOrElse{"Test gagal: ${it.message}"};runOnUiThread{if(currentPage=="Wi-Fi Info")modernInfoCard("Hasil Test Koneksi",r)}}}
    internal fun scanNearbyWifi(){
        if(Build.VERSION.SDK_INT>=23&&ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){toast("Izinkan lokasi untuk scan Wi-Fi");return}
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED){toast("Izinkan Nearby Wi-Fi untuk scan jaringan");return}
        clearPage("Wi-Fi Scan")
        val status=label("Memindai Wi-Fi…",15f,true)
        content.addView(status,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        val wm=getSystemService(WIFI_SERVICE) as WifiManager
        val started=runCatching{@Suppress("DEPRECATION") wm.startScan()}.getOrDefault(false)
        modernUiHandler.postDelayed({
            if(currentPage!="Wi-Fi Scan") return@postDelayed
            val results=runCatching{@Suppress("DEPRECATION") wm.scanResults}.getOrDefault(emptyList())
            content.removeAllViews()
            if(!started){
                modernInfoCard("Scan tidak dimulai","Android menolak atau membatasi pemindaian. Coba lagi beberapa saat.")
            } else if(results.isEmpty()){
                modernInfoCard("Tidak ada hasil","Scan bisa dibatasi Android/perangkat atau belum mendapat hasil baru.")
            } else {
                results.sortedByDescending{it.level}.distinctBy{it.BSSID}.take(20).forEach{
                    val ssid=it.SSID.ifBlank{"<hidden>"}
                    modernInfoCard(ssid,"${it.level} dBm • ${it.frequency} MHz • channel ${wifiChannel(it.frequency)}")
                }
            }
        },1200L)
    }
    internal class MiniLineChart(ctx:Context):View(ctx){var values:List<Float> = emptyList();var lineColor=Color.DKGRAY;var fillColor=Color.TRANSPARENT;var minValue=0f;var maxValue=100f;private val p=Paint(Paint.ANTI_ALIAS_FLAG);override fun onDraw(c:Canvas){super.onDraw(c);val w=width.toFloat();val h=height.toFloat();val den=resources.displayMetrics.density;p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=Color.rgb(232,236,239);for(i in 1..4){val y=h*i/5f;c.drawLine(0f,y,w,y,p)};if(values.isEmpty())return;val path=android.graphics.Path();values.forEachIndexed{i,v->val x=if(values.size==1)w/2 else i.toFloat()/(values.size-1)*w;val y=h-((v-minValue)/(maxValue-minValue).coerceAtLeast(1f)).coerceIn(0f,1f)*h;if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};p.color=fillColor;p.style=Paint.Style.FILL;val fill=android.graphics.Path(path);fill.lineTo(w,h);fill.lineTo(0f,h);fill.close();c.drawPath(fill,p);p.color=lineColor;p.style=Paint.Style.STROKE;p.strokeWidth=2f*den;c.drawPath(path,p);val last=values.last();val x=if(values.size==1)w/2 else w;val y=h-((last-minValue)/(maxValue-minValue).coerceAtLeast(1f)).coerceIn(0f,1f)*h;p.style=Paint.Style.FILL;c.drawCircle(x,y,4f*den,p)}}

    internal fun infoRow(name: String, value: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = bg(panel2, 14)
        }
        card.addView(label(name, 12f, true))
        card.addView(label(value.ifBlank { "Tidak tersedia" }, 14f))
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
    }

    internal fun bytesText(v: Long): String {
        if (v < 1024) return "$v B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var n = v.toDouble()
        var i = -1
        while (n >= 1024 && i < units.lastIndex) { n /= 1024.0; i++ }
        return String.format(Locale.getDefault(), "%.2f %s", n, units[i])
    }

    internal fun deviceSystemCenterTool() {
        clearPage("Device & System")
        val dm = resources.displayMetrics
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().path)
        val ramPct = ((mem.totalMem - mem.availMem).toDouble() / mem.totalMem * 100).roundToInt().coerceIn(0, 100)
        val storagePct = ((stat.totalBytes - stat.availableBytes).toDouble() / stat.totalBytes * 100).roundToInt().coerceIn(0, 100)

        content.addView(label("Device & System", 22f, true))
        content.addView(subLabel("Device Info, System Center, dan Battery Info dalam satu halaman.", 12f))
        modernDeviceHero()
        modernMetricGrid(listOf(
            "Model" to Build.MODEL,
            "RAM" to String.format(Locale.getDefault(), "%.1f GB", mem.totalMem / 1024.0 / 1024.0 / 1024.0),
            "CPU" to Build.HARDWARE.ifBlank { Build.BOARD },
            "Layar" to "${dm.widthPixels} × ${dm.heightPixels}\n${String.format(Locale.getDefault(), "%.2f", dm.density)}x",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "ABI" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "-")
        ))

        toolWorkspaceSection("SYSTEM STATUS", "Ringkasan penggunaan perangkat saat ini.")
        modernProgressCard("CPU", cpuLoadPercent(), "Penggunaan prosesor", "${Runtime.getRuntime().availableProcessors()} core")
        modernProgressCard("RAM", ramPct, "Penggunaan memori", "${bytesText(mem.totalMem - mem.availMem)} / ${bytesText(mem.totalMem)}")
        modernProgressCard("Storage", storagePct, "Penyimpanan internal", "${bytesText(stat.totalBytes - stat.availableBytes)} / ${bytesText(stat.totalBytes)}")
        val processes = am.runningAppProcesses?.size ?: 0
        modernProgressCard("Layanan Sistem", processes.coerceAtMost(100), "Layanan & proses aktif", "$processes aktif")
        modernInfoCard("Build", Build.DISPLAY)
        modernInfoCard("Kernel", System.getProperty("os.version") ?: "Tidak tersedia")

        toolWorkspaceSection("BATTERY", "Status baterai diperbarui otomatis.")
        modernUiRunnable?.let { modernUiHandler.removeCallbacks(it) }
        batteryHistory.clear()
        val hero = modernBatteryHero()
        val chart = MiniLineChart(this).apply {
            lineColor = Color.rgb(244, 67, 54)
            fillColor = Color.argb(48, 244, 67, 54)
            minValue = 0f
            maxValue = 100f
        }
        modernChartCard("Level baterai", chart)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val statusBox = modernSmallMetric("Status", "-", "battery-charging")
        val healthBox = modernSmallMetric("Kesehatan", "-", "heart-pulse")
        val tempBox = modernSmallMetric("Suhu", "-", "thermometer")
        val voltBox = modernSmallMetric("Tegangan", "-", "flash")
        r1.addView(statusBox, weightLp(1f, 0)); r1.addView(healthBox, weightLp(1f, 6))
        r2.addView(tempBox, weightLp(1f, 0)); r2.addView(voltBox, weightLp(1f, 6))
        grid.addView(r1); grid.addView(r2)
        content.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        fun updateBattery() {
            if (currentPage != "Device & System") return
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = (level * 100 / scale).coerceIn(0, 100)
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            val voltage = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            val stText = when (st) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Mengisi"
                BatteryManager.BATTERY_STATUS_FULL -> "Penuh"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Menggunakan baterai"
                else -> "Tidak diketahui"
            }
            batteryHistory.add(pct); if (batteryHistory.size > 40) batteryHistory.removeAt(0)
            chart.values = batteryHistory.map { it.toFloat() }; chart.invalidate()
            hero.percent.text = "$pct%"
            hero.status.text = if (pct <= 20) "Baterai lemah" else if (charging) "Sedang mengisi" else "Normal"
            hero.icon.setIconName(if (pct <= 20) "battery-high" else if (charging) "battery-charging" else "battery-high")
            hero.icon.setTextColor(if (pct <= 20) Color.rgb(244, 67, 54) else accentTeal())
            hero.status.setTextColor(if (pct <= 20) Color.rgb(211, 47, 47) else accentTeal())
            setMetricBox(statusBox, "Status", stText, if (charging) "Mengisi daya" else "Menggunakan baterai")
            setMetricBox(healthBox, "Kesehatan", if (pct <= 20) "Perlu diisi" else "Baik", if (pct <= 20) "Level rendah" else "Normal")
            setMetricBox(tempBox, "Suhu", String.format(Locale.getDefault(), "%.1f°C", temp), if (temp > 40) "Tinggi" else "Normal")
            setMetricBox(voltBox, "Tegangan", "${voltage} mV", if (voltage > 0) "Normal" else "Tidak tersedia")
        }
        val r = object : Runnable {
            override fun run() {
                updateBattery()
                modernUiHandler.postDelayed(this, 1500)
            }
        }
        modernUiRunnable = r
        r.run()
    }

    internal fun deviceInfoTool() {
        clearPage("Device Info")
        val dm=resources.displayMetrics
        val am=getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mem=ActivityManager.MemoryInfo().also{am.getMemoryInfo(it)}
        modernDeviceHero()
        modernMetricGrid(listOf(
            "Model" to Build.MODEL,
            "RAM" to String.format(Locale.getDefault(), "%.1f GB", mem.totalMem/1024.0/1024.0/1024.0),
            "CPU" to Build.HARDWARE.ifBlank{Build.BOARD},
            "Layar" to "${dm.widthPixels} × ${dm.heightPixels}\n${String.format(Locale.getDefault(), "%.2f", dm.density)}x",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "ABI" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "-")
        ))
        modernInfoCard("Build", Build.DISPLAY)
        modernInfoCard("Kernel", System.getProperty("os.version") ?: "Tidak tersedia")
    }

    internal fun appManagerTool() {
        clearPage("App Manager", true)
        content.addView(label("Aplikasi terpasang", 22f, true))
        content.addView(subLabel("Pilih aplikasi untuk membuka halaman App Info Android.", 12f))
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .sortedBy { pm.getApplicationLabel(it).toString().toLowerCase(Locale.getDefault()) }
        apps.forEach { app ->
            val name = pm.getApplicationLabel(app).toString()
            val pkg = app.packageName
            val b = button("$name\n$pkg") {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                startActivity(i)
            }
            content.addView(b)
        }
    }

    internal fun batteryInfoTool() {
        clearPage("Battery Info")
        modernUiRunnable?.let{modernUiHandler.removeCallbacks(it)}
        batteryHistory.clear()
        val hero=modernBatteryHero()
        val chart=MiniLineChart(this).apply{lineColor=Color.rgb(244,67,54);fillColor=Color.argb(48,244,67,54);minValue=0f;maxValue=100f}
        modernChartCard("Level baterai (24 jam terakhir)",chart)
        val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val r1=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val r2=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val statusBox=modernSmallMetric("Status","-","battery-charging")
        val healthBox=modernSmallMetric("Kesehatan baterai","Baik","heart-pulse")
        val tempBox=modernSmallMetric("Suhu","-","thermometer")
        val voltBox=modernSmallMetric("Tegangan","-","flash")
        r1.addView(statusBox,weightLp(1f,0));r1.addView(healthBox,weightLp(1f,6))
        r2.addView(tempBox,weightLp(1f,0));r2.addView(voltBox,weightLp(1f,6))
        grid.addView(r1);grid.addView(r2);content.addView(grid,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        modernInfoCard("Tips hemat baterai","Kurangi kecerahan layar dan tutup aplikasi yang tidak digunakan.")
        fun update(){
            if(currentPage!="Battery Info")return
            val i=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?:return
            val level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);val scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,100).coerceAtLeast(1);val pct=(level*100/scale).coerceIn(0,100)
            val temp=i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10.0;val voltage=i.getIntExtra(BatteryManager.EXTRA_VOLTAGE,0);val st=i.getIntExtra(BatteryManager.EXTRA_STATUS,-1)
            val charging=st==BatteryManager.BATTERY_STATUS_CHARGING||st==BatteryManager.BATTERY_STATUS_FULL
            val stText=when(st){BatteryManager.BATTERY_STATUS_CHARGING->"Mengisi";BatteryManager.BATTERY_STATUS_FULL->"Penuh";BatteryManager.BATTERY_STATUS_DISCHARGING->"Discharging";else->"Tidak diketahui"}
            batteryHistory.add(pct);if(batteryHistory.size>40)batteryHistory.removeAt(0);chart.values=batteryHistory.map{it.toFloat()};chart.invalidate()
            hero.percent.text="$pct%";hero.status.text=if(pct<=20)"Baterai lemah" else if(charging)"Sedang mengisi" else "Normal";hero.icon.setIconName(if(pct<=20)"battery-high" else if(charging)"battery-charging" else "battery-high");hero.icon.setTextColor(if(pct<=20)Color.rgb(244,67,54) else accentTeal());hero.status.setTextColor(if(pct<=20)Color.rgb(211,47,47) else accentTeal())
            setMetricBox(statusBox,"Status",stText,if(charging)"Mengisi daya" else "Menggunakan baterai")
            setMetricBox(healthBox,"Kesehatan baterai",if(pct<=20)"Perlu diisi" else "Baik",if(pct<=20)"Level rendah" else "Normal")
            setMetricBox(tempBox,"Suhu",String.format(Locale.getDefault(),"%.1f°C",temp),if(temp>40)"Tinggi" else "Normal")
            setMetricBox(voltBox,"Tegangan","${voltage} mV",if(voltage>0)"Normal" else "Tidak tersedia")
        }
        val r=object:Runnable{override fun run(){update();modernUiHandler.postDelayed(this,1500)}};modernUiRunnable=r;r.run()
    }

    internal fun findFiles(dir: File, term: String, out: MutableList<File>, limit: Int) {
        if (out.size >= limit) return
        dir.listFiles()?.forEach { f ->
            if (out.size >= limit) return
            if (f.name.toLowerCase(Locale.getDefault()).contains(term)) out.add(f)
            if (f.isDirectory) findFiles(f, term, out, limit)
        }
    }

    // ---------- FILE MANAGER / EDITOR ----------

    /** 0=skip, 1=replace, 2=rename, 3=cancel. Dialog runs on UI thread before worker. */
    internal fun recentFileCard(file: File): View {
        val type = recentFileType(file)
        val modified = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(8), dp(12))
            background = bg(Color.rgb(248,248,249), 20, Color.rgb(220,220,223))
            isClickable = true
            setOnClickListener { openRecentFile(file) }
        }
        val icon = TextView(this).apply {
            text = when (type) {
                "Gambar" -> "▧"
                "Video" -> "▶"
                "Audio" -> "♪"
                "Arsip" -> "▣"
                "Dokumen" -> "▤"
                "Kode" -> "</>"
                else -> "□"
            }
            textSize = if (text == "</>") 14f else 25f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(35,35,38))
            background = bg(Color.rgb(238,238,240), 16)
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(12) })

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        box.addView(label(file.name, 15f, false))
        box.addView(subLabel("$type • ${bytesText(file.length())} • $modified", 11f).apply { setPadding(0, dp(3), 0, 0) })
        box.addView(subLabel(file.parent ?: "", 10f).apply { setPadding(0, dp(3), 0, 0) })
        card.addView(box)

        val more = TextView(this).apply {
            text = "⋮"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(70,70,74))
            contentDescription = "Tindakan ${file.name}"
            setOnClickListener { showRecentFileActions(file) }
        }
        card.addView(more, LinearLayout.LayoutParams(dp(42), dp(54)))
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
    }

    internal fun recentFileType(file: File): String {
        val ext = file.extension.lowercase(Locale.getDefault())
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> "Gambar"
            "mp4", "mkv", "webm", "3gp", "mov" -> "Video"
            "mp3", "wav", "m4a", "flac", "ogg", "aac" -> "Audio"
            "zip", "rar", "7z", "tar", "gz", "bz2" -> "Arsip"
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf" -> "Dokumen"
            "kt", "java", "py", "js", "ts", "html", "css", "xml", "json", "md", "c", "cpp", "h", "sh" -> "Kode"
            else -> "File"
        }
    }

    internal fun openRecentFile(file: File) {
        recordRecentFile(file)
        val ext = file.extension.lowercase(Locale.getDefault())
        val textExt = setOf("txt", "md", "json", "xml", "html", "htm", "css", "js", "ts", "kt", "java", "py", "c", "cpp", "h", "sh", "gradle", "properties", "csv")
        if (ext in textExt) {
            editor(file)
            return
        }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Buka dengan"))
        }.onFailure { toast("Tidak ada aplikasi untuk membuka file ini") }
    }

    internal fun showRecentFileActions(file: File) {
        val actions = arrayOf("Buka", "Bagikan", "Hapus dari Recent", "Detail")
        AlertDialog.Builder(this).setTitle(file.name).setItems(actions) { _, which ->
            when (which) {
                0 -> openRecentFile(file)
                1 -> shareFile(file)
                2 -> {
                    val old = prefs.getString("recent_files", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
                    prefs.edit().putString("recent_files", old.filter { it != file.absolutePath }.joinToString("\n")).apply()
                    recentFilesTool()
                    toast("Dihapus dari Recent Files")
                }
                3 -> showFileDetail(file)
            }
        }.show()
    }

    internal fun createAppBackup() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"; putExtra(Intent.EXTRA_TITLE, "mytools_backup_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.zip") }
        startActivityForResult(i, 3025)
    }

    internal fun restoreAppBackup() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/zip"; addCategory(Intent.CATEGORY_OPENABLE) }, 3026)
    }

    internal fun writeAppBackup(uri: Uri) {
        val root = JSONObject().apply {
            put("format", "mytools-app-backup")
            put("version", 1)
            put("createdAt", System.currentTimeMillis())
            put("appVersion", "2.32.0")
            val settings = JSONObject()
            prefs.all.forEach { (k, v) ->
                when (v) {
                    is Boolean -> settings.put(k, v)
                    is Int -> settings.put(k, v)
                    is Long -> settings.put(k, v)
                    is Float -> settings.put(k, v)
                    is String -> settings.put(k, v)
                    is Set<*> -> settings.put(k, JSONArray(v.toList()))
                }
            }
            put("preferences", settings)
        }
        contentResolver.openOutputStream(uri)?.use { out ->
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                val bytes = root.toString(2).toByteArray(StandardCharsets.UTF_8)
                zip.putNextEntry(ZipEntry("backup.json")); zip.write(bytes); zip.closeEntry()
            }
        } ?: error("Tidak bisa menulis file backup")
    }

    internal fun readAppBackup(uri: Uri) {
        var root: JSONObject? = null
        contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name == "backup.json") {
                        root = JSONObject(zip.readBytes().toString(StandardCharsets.UTF_8)); break
                    }
                }
            }
        } ?: error("Tidak bisa membaca backup")
        val data = root ?: error("backup.json tidak ditemukan")
        if (data.optString("format") != "mytools-app-backup") error("Format backup tidak dikenali")
        val settings = data.optJSONObject("preferences") ?: JSONObject()
        val editor = prefs.edit().clear()
        val keys = settings.keys()
        while (keys.hasNext()) {
            val k = keys.next(); val v = settings.get(k)
            when (v) {
                is Boolean -> editor.putBoolean(k, v)
                is Int -> editor.putInt(k, v)
                is Long -> editor.putLong(k, v)
                is Double -> editor.putFloat(k, v.toFloat())
                is String -> editor.putString(k, v)
                is JSONArray -> { val set = mutableSetOf<String>(); for (i in 0 until v.length()) set.add(v.optString(i)); editor.putStringSet(k, set) }
            }
        }
        if (!editor.commit()) error("Gagal menyimpan hasil restore")
    }

    internal fun confirmDeleteFile(file: File, parent: File) {
        AlertDialog.Builder(this)
            .setTitle("Hapus file?")
            .setMessage(file.name)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                val ok = runCatching { file.delete() }.getOrDefault(false)
                if (ok) { toast("File dihapus"); fileManager(parent) } else toast("Gagal menghapus file")
            }.show()
    }

    internal fun pickFileForEditor() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(i, 1001)
    }

    internal fun queryName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex("_display_name")
            if (c.moveToFirst() && idx >= 0) return c.getString(idx)
        }
        return null
    }

    // ===================== Notifikasi / Pengingat Terpadu =====================
    internal var reminderFilter = "all"
    internal var reminderEditing: JSONObject? = null
    internal var reminderDraftMessage = ""
    internal var reminderDraftBody = ""
    internal var reminderDraftNote = ""
    internal var reminderDraftHour = 13
    internal var reminderDraftMinute = 0
    internal var reminderDraftCategory = "kegiatan"
    internal var reminderDraftRepeat = "daily"
    internal var reminderDraftEnabled = true
    internal var reminderDraftPayload = JSONObject()

    // ---- Nama halaman Notifikasi (dipakai clearPage untuk menyembunyikan strip bawaan tool) ----
    internal val rmPickerPages = setOf("Pilih Waktu", "Pilih Tanggal / Pengulangan", "Pilih Hari")
    internal val rmEditorPages = setOf("Tambah Notifikasi", "Tambah Notifikasi - Detail", "Edit Notifikasi")
    internal val rmSubPages = rmPickerPages + rmEditorPages + "Detail Notifikasi"
    internal val rmAllPages = rmSubPages + "Notifikasi"

    // ---- Warna sesuai desain ----
    internal val rmDark = Color.rgb(38, 51, 61)
    internal val rmCardBg = Color.rgb(247, 249, 250)
    internal val rmCardLine = Color.rgb(229, 234, 238)
    internal val rmGray = Color.rgb(145, 154, 161)

    // ===================== Helper tampilan =====================
    internal fun rmIc(name: String, sp: Float = 20f, color: Int = rmDark): MdiIconView =
        MdiIconView(this).apply { setIconName(name); setIconSize(sp); setTextColor(color) }

    internal fun rmIconBox(iconName: String, size: Int = 46): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        background = bg(Color.WHITE, size / 2, rmCardLine)
        addView(rmIc(iconName, 22f))
    }

    internal fun rmSwitch(checked: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(this).apply {
        isChecked = checked
        if (Build.VERSION.SDK_INT >= 23) {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(Color.WHITE, Color.rgb(250, 251, 252)))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(rmDark, Color.rgb(222, 228, 232)))
        }
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    internal fun rmButton(caption: String, icon: String, primary: Boolean, onClick: () -> Unit): LinearLayout {
        val fg = if (primary) Color.WHITE else rmDark
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = if (primary) bg(rmDark, 16) else bg(Color.rgb(245, 247, 248), 16, rmCardLine)
            isClickable = true
            isFocusable = true
            addView(rmIc(icon, 18f, fg), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            addView(TextView(this@MainActivity).apply {
                text = caption; textSize = 14f; setTextColor(fg)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            setOnClickListener { onClick() }
        }
    }

    internal fun rmSection(titleText: String, hint: String? = null): TextView = TextView(this).apply {
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

    internal fun rmRow(iconName: String, value: String, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(10), 0)
        background = bg(rmCardBg, 14, rmCardLine)
        isClickable = true
        addView(rmIc(iconName, 21f), LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(10) })
        addView(TextView(this@MainActivity).apply {
            text = value; textSize = 13.5f; setTextColor(textMain); maxLines = 2
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(rmIc("chevron-right", 22f, rmGray), LinearLayout.LayoutParams(dp(28), dp(30)))
        setOnClickListener { onClick() }
    }

    internal fun rmChoiceRow(iconName: String, titleText: String, sub: String?, trailing: View, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = bg(rmCardBg, 14, rmCardLine)
            isClickable = true
            addView(rmIc(iconName, 21f), LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(12) })
            val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this@MainActivity).apply {
                text = titleText; textSize = 13.5f; setTextColor(textMain)
                if (sub != null) setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            if (sub != null) texts.addView(TextView(this@MainActivity).apply { text = sub; textSize = 11f; setTextColor(textMuted) })
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(trailing, LinearLayout.LayoutParams(dp(22), dp(22)))
            setOnClickListener { onClick() }
        }

    internal fun rmCheck(on: Boolean): FrameLayout {
        val f = FrameLayout(this)
        f.addView(rmIc("check", 15f, Color.WHITE), FrameLayout.LayoutParams(-1, -1))
        rmSetCheck(f, on)
        return f
    }

    internal fun rmSetCheck(f: FrameLayout, on: Boolean) {
        f.background = if (on) bg(rmDark, 6) else bg(Color.WHITE, 6, Color.rgb(205, 212, 218))
        f.getChildAt(0).visibility = if (on) View.VISIBLE else View.INVISIBLE
    }

    internal fun rmRadio(on: Boolean): FrameLayout {
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

    internal fun rmSetRadio(f: FrameLayout, on: Boolean) {
        f.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(dp(if (on) 2 else 1), if (on) rmDark else Color.rgb(205, 212, 218))
        }
        f.getChildAt(0).visibility = if (on) View.VISIBLE else View.INVISIBLE
    }

    internal fun rmDeriveCategory(name: String, birthday: Boolean): String {
        val n = name.toLowerCase(Locale.getDefault())
        return when {
            birthday || n.contains("ulang tahun") || n.contains("ultah") -> "ulangtahun"
            n.contains("obat") || n.contains("vitamin") || n.contains("suplemen") -> "obat"
            else -> "kegiatan"
        }
    }

    internal fun rmItemCategory(o: JSONObject): String = when {
        o.optString("category") == "kustom" || o.optBoolean("customConversation") -> "kustom"
        o.optString("category") == "ulangtahun" || o.optBoolean("birthday") -> "ulangtahun"
        o.optString("category") == "obat" -> "kustom"
        o.optString("category") == "kegiatan" -> "kegiatan"
        else -> rmDeriveCategory(o.optString("message"), false)
    }

    internal fun rmIconName(category: String, message: String): String {
        val n = message.toLowerCase(Locale.getDefault())
        return when {
            category == "obat" -> "pill"
            category == "kustom" -> "message-text-outline"
            category == "ulangtahun" -> "cake-variant-outline"
            Regex("\\bair\\b").containsMatchIn(n) -> "water-outline"
            n.contains("tidur") -> "sleep"
            else -> "calendar-blank-outline"
        }
    }

    // ===================== Navigasi internal Notifikasi =====================
    internal fun rmTopIn(names: Set<String>): Boolean = pageBackStack.lastOrNull()?.let { it.name in names } == true

    /** Kembali ke daftar Notifikasi (segar) tanpa menumpuk riwayat halaman. */
    internal fun rmPopToList() {
        while (rmTopIn(rmSubPages)) pageBackStack.removeLast()
        if (rmTopIn(setOf("Notifikasi"))) pageBackStack.removeLast()
        content.removeAllViews()
        reminderTool()
    }

    /** Kembali ke form (Tambah/Edit) setelah memilih waktu/pengulangan/hari. */
    internal fun rmPopToEditor() {
        while (rmTopIn(rmPickerPages)) pageBackStack.removeLast()
        if (rmTopIn(rmEditorPages)) pageBackStack.removeLast()
        content.removeAllViews()
        renderReminderEditor()
    }

    internal fun rmRerenderEditor() { content.removeAllViews(); renderReminderEditor() }
    internal fun rmRerenderList() { content.removeAllViews(); reminderTool() }

    internal fun rmMenu() {
        val pm = PopupMenu(this, action)
        pm.menu.add(0, 1, 0, "Tambah notifikasi")
        pm.menu.add(0, 2, 1, "Uji notifikasi")
        pm.menu.add(0, 3, 2, "Pengaturan notifikasi")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showReminderEditor(null)
                2 -> sendTestNotification()
                3 -> openNotificationSettings()
            }
            true
        }
        pm.show()
    }

    internal fun rmPromptText(titleText: String, hint: String, current: String, multiline: Boolean, onOk: (String) -> Unit) {
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

    // ===================== 1. Daftar Notifikasi =====================
    internal fun reminderTool() {
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
                if (on) scheduleReminderData(this@MainActivity, o) else cancelReminderAlarm(id)
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

    // ===================== Kustom: pembuat percakapan notifikasi =====================
    internal fun showCustomEditor(existing: JSONObject?) {
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

    internal fun renderCustomEditor() {
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
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(10), dp(10)); background = bg(Color.WHITE, 14, rmCardLine); isClickable = true
                    setOnClickListener { showCustomRuleDialog(i, ::refreshRules) }
                }
                val triggerLabel = triggers.replace("|", " / ").replace(",", " / ")
                row.addView(TextView(this@MainActivity).apply {
                    text = "Kamu bilang: $triggerLabel"
                    textSize = 12.5f; setTextColor(textMain); setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                row.addView(TextView(this@MainActivity).apply {
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

    
    internal fun showCustomRuleDialog(index: Int, after: () -> Unit) {
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


    internal fun saveCustomDraft() {
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

    internal fun showReminderEditor(existing: JSONObject?) {
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

    // ===================== 2 / 6 / 7. Tambah, Tambah - Detail, Edit =====================
    internal fun renderReminderEditor() {
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
        content.addView(rmRow("bell-outline", if (detailed) "Uji notifikasi" else "10 menit sebelum") { sendTestNotification() }, rowLp())
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

    internal fun saveReminderDraft() {
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

    // ===================== 5. Pilih Waktu =====================
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    internal fun rmStepBtn(icon: String, step: () -> Unit): View {
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
    internal fun rmTimeBox(tv: TextView, isHour: Boolean, onValue: (Int) -> Unit): View {
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

    internal fun rmPromptNumber(title: String, hint: String, current: Int, min: Int, max: Int, onValue: (Int) -> Unit) {
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

    internal fun showReminderTimePickerPage() {
        clearPage("Pilih Waktu")
        var h = reminderDraftHour
        var m = reminderDraftMinute

        fun bigNumber(): TextView = TextView(this).apply {
            textSize = 34f; gravity = Gravity.CENTER; setTextColor(textMain); setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = bg(Color.rgb(242, 245, 247), 16); isClickable = true
        }
        val hourTv = bigNumber(); val minTv = bigNumber()
        val periodViews = ArrayList<Triple<LinearLayout, TextView, TextView>>()
        fun periodOf(hh: Int): Int = if (hh in 6..11) 0 else if (hh in 12..17) 1 else 2
        fun refresh() {
            hourTv.text = "%02d".format(h); minTv.text = "%02d".format(m)
            val p = periodOf(h)
            for ((i, t) in periodViews.withIndex()) {
                val on = i == p
                t.first.background = if (on) bg(rmDark, 14) else bg(Color.rgb(244, 246, 248), 14, rmCardLine)
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

    // ===================== 3. Pilih Tanggal / Pengulangan =====================
    internal fun showRepeatPickerPage() {
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

    internal fun rmSetRepeat(repeat: String, birthday: Boolean = false) {
        reminderDraftRepeat = repeat
        reminderDraftPayload.remove("birthday")
        if (birthday) reminderDraftPayload.put("birthday", true)
        if (repeat != "selected_days") reminderDraftPayload.remove("days")
    }

    internal fun showDatePickerPage(mode: String) {
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

    internal fun showCustomIntervalDialog() {
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

    // ===================== 4. Pilih Hari =====================
    internal fun showWeekdayPickerPage() {
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

    // ===================== 8. Detail Notifikasi =====================
    internal fun showReminderDetail(existing: JSONObject) {
        clearPage("Detail Notifikasi")
        val msg = existing.optString("message", "Notifikasi")
        val cat = rmItemCategory(existing)
        val id = existing.optInt("id")
        val timeText = "%02d:%02d".format(existing.optInt("hour", 13), existing.optInt("minute", 0))
        val repeatText = repeatLabel(existing.optString("repeat", "daily"), existing)

        val circle = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = bg(Color.rgb(241, 244, 246), 38)
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
            if (on) scheduleReminderData(this@MainActivity, existing) else cancelReminderAlarm(id)
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
        addDetail("bell-outline", "Notifikasi", "10 menit sebelum")
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

    internal fun sendTestNotification() {
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
            .setContentTitle("MyTools")
            .setContentText("Notifikasi berhasil bekerja")
            .setAutoCancel(true)
            .setContentIntent(open)
        manager.notify(99001, builder.build())
        toast("Notifikasi uji dikirim")
    }

    internal fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply { putExtra(Settings.EXTRA_APP_PACKAGE, packageName) }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }.onFailure { toast("Tidak dapat membuka pengaturan notifikasi") }
    }

    internal fun saveReminder(data: JSONObject, existing: JSONObject?) {
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

    internal fun scheduleReminderData(context: Context, data: JSONObject) {
        val id = data.optInt("id")
        val next = nextReminderTime(data, System.currentTimeMillis()) ?: return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("id", id)
            putExtra("message", data.optString("message"))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, id, intent, flags)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending) }
            .onFailure { alarm.set(AlarmManager.RTC_WAKEUP, next, pending) }
    }

    internal fun nextReminderTime(data: JSONObject, from: Long): Long? {
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

    internal fun containsJsonInt(arr: JSONArray, value: Int): Boolean {
        for (i in 0 until arr.length()) if (arr.optInt(i) == value) return true
        return false
    }

    internal fun readReminders(): JSONArray = runCatching { JSONArray(prefs.getString("scheduled_reminders", "[]") ?: "[]") }.getOrElse { JSONArray() }

    internal fun reminderCategoryLabel(key: String): String = when (key) {
        "obat" -> "Obat"; "kegiatan" -> "Kegiatan"; "ulangtahun" -> "Ulang Tahun"; else -> "Kustom"
    }

    internal fun rmMonthShort(m: Int): String =
        arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")[m.coerceIn(0, 11)]

    internal fun repeatLabel(repeat: String, data: JSONObject?): String = when (repeat) {
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

    internal fun renderReminderList() {
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
                background = bg(Color.rgb(242,244,246), 14)
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
                    if (!isChecked) cancelReminderAlarm(id) else scheduleReminderData(this@MainActivity, o)
                }
            }
            row.addView(toggle, LinearLayout.LayoutParams(dp(54), dp(48)))
            row.setOnClickListener { showReminderEditor(o) }
            row.setOnLongClickListener { cancelReminder(id); reminderTool(); true }
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        if (shown == 0) content.addView(subLabel(if (arr.length() == 0) "Belum ada pengingat. Tekan + untuk membuat pesan baru." else "Belum ada pengingat di kategori ini.", 12f))
    }

    internal fun updateReminderObject(updated: JSONObject) {
        val old = readReminders(); val next = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            next.put(if (o.optInt("id") == updated.optInt("id")) updated else o)
        }
        prefs.edit().putString("scheduled_reminders", next.toString()).apply()
    }

    internal fun cancelReminderAlarm(id: Int) {
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ReminderReceiver::class.java)
        val flags = PendingIntent.FLAG_NO_CREATE or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        PendingIntent.getBroadcast(this, id, intent, flags)?.let { alarm.cancel(it); it.cancel() }
    }

    internal fun cancelReminder(id: Int) {
        cancelReminderAlarm(id)
        val old = readReminders(); val next = JSONArray()
        for (i in 0 until old.length()) if (old.optJSONObject(i)?.optInt("id") != id) next.put(old.optJSONObject(i))
        prefs.edit().putString("scheduled_reminders", next.toString()).apply()
        toast("Pengingat dihapus")
    }

    internal fun editor(file: File?, forcedMode: String? = null) {
        editorLanding = file == null && forcedMode == null && editorExternalTarget == null
        clearPage("Editor")
        editorFile = file
        file?.let { recordRecentFile(it) }
        editorMode = forcedMode ?: detectEditorMode(file?.name)
        if (editorLanding) renderEditorHome() else renderEditorPage()
    }

    internal fun detectEditorMode(name: String?): String {
        val ext = name?.substringAfterLast('.', "")?.toLowerCase(Locale.getDefault()) ?: ""
        return when (ext) {
            "html", "htm" -> "html"
            "css" -> "css"
            "js", "mjs", "cjs" -> "js"
            "json" -> "json"
            "csv", "tsv" -> "csv"
            "base64", "b64" -> "base64"
            "py", "kt", "kts", "java", "ts", "c", "cpp", "h", "hpp", "cs", "go", "rs", "php", "sh" -> "code"
            "ini", "cfg", "conf", "properties", "yaml", "yml", "toml" -> "config"
            "xml" -> "xml"
            else -> "text"
        }
    }

    internal fun editorModeName(mode: String): String = when (mode) {
        "html" -> "HTML"
        "css" -> "CSS"
        "js" -> "JavaScript"
        "json" -> "JSON"
        "csv" -> "CSV"
        "base64" -> "Base64"
        "utility" -> "Utilitas"
        "code" -> "Kode"
        "config" -> "Konfig"
        "xml" -> "XML"
        else -> "Teks"
    }

    internal fun editorDefaultName(mode: String): String = when (mode) {
        "html" -> "index.html"
        "css" -> "style.css"
        "js" -> "script.js"
        "json" -> "untitled.json"
        "csv" -> "untitled.csv"
        "base64" -> "untitled.txt"
        "utility" -> "untitled.txt"
        "code" -> "untitled.py"
        "config" -> "config.ini"
        "xml" -> "untitled.xml"
        else -> "untitled.txt"
    }

    internal fun editorModeDescription(mode: String): String = when (mode) {
        "html" -> "Edit HTML dan preview halaman web"
        "css" -> "Edit stylesheet CSS"
        "js" -> "Edit JavaScript"
        "json" -> "Edit, validasi, format, dan konversi JSON"
        "csv" -> "Lihat dan konversi data tabel"
        "base64" -> "Encode dan decode Base64"
        "utility" -> "Utilitas teks dan perhitungan"
        "code" -> "Edit kode program"
        "config" -> "Edit file konfigurasi"
        "xml" -> "Edit dan rapikan XML"
        else -> "Edit teks dan catatan"
    }

    internal fun renderEditorHome() {
        // Landing tetap memakai navigasi utama. Hanya tiga mode web-code yang tampil
        // langsung; format lain dipindahkan ke tombol + agar layar tetap bersih.
        title.text = "Editor"
        subtitle.visibility = View.GONE
        action.visibility = View.VISIBLE
        action.text = "+"
        action.textSize = 28f
        back.visibility = View.VISIBLE
        configureActionForPage("Editor")
        editorBox = null
        editorLanding = true
        editorBottomBar.visibility = View.GONE
        editorBottomBar.removeAllViews()
        editorMore.visibility = View.GONE
        topBarVisibility(true)
        content.post { syncEditorBottomBar() }

        content.setPadding(dp(10), dp(4), dp(10), dp(12))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = bg(Color.rgb(246, 248, 250), 18, Color.rgb(231, 235, 239))
        }
        hero.addView(MdiIconView(this).apply {
            setIconName("file-document-edit-outline")
            setIconSize(38f)
            setTextColor(textMain)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { bottomMargin = dp(8) }
        })
        hero.addView(TextView(this).apply {
            text = "Pilih mode editor"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textMain)
            gravity = Gravity.CENTER
        })
        hero.addView(TextView(this).apply {
            text = "HTML, CSS, dan JavaScript dalam satu editor. Format lain ada di (+)."
            textSize = 12f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        })
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(136)).apply { bottomMargin = dp(12) })
        animateEditorItem(hero, 0L, 10f)

        val modes = listOf(
            Triple("language-html5", "HTML", "Edit halaman HTML dan preview web.") to "html",
            Triple("language-css3", "CSS", "Edit stylesheet dan tampilan web.") to "css",
            Triple("language-javascript", "JavaScript", "Edit logic dan interaksi halaman web.") to "js"
        )
        modes.forEachIndexed { index, pair ->
            val (item, mode) = pair
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(7), dp(10), dp(7))
                background = bg(Color.WHITE, 16, Color.rgb(226, 231, 235))
                isClickable = true
                setOnClickListener {
                    animateEditorPress(this)
                    postDelayed({ editorExternalTarget = null; editorExternalMode = null; editor(null, mode) }, 70L)
                }
            }
            val (iconName, name, desc) = item
            row.addView(MdiIconView(this).apply {
                setIconName(iconName)
                setIconSize(24f)
                setTextColor(textMain)
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(40)).apply { rightMargin = dp(8) }
            })
            val textBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            textBox.addView(TextView(this).apply {
                text = name
                textSize = 14f
                setTextColor(textMain)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            textBox.addView(TextView(this).apply {
                text = desc
                textSize = 11f
                setTextColor(textMuted)
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(textBox)
            row.addView(TextView(this).apply {
                text = "›"
                textSize = 25f
                setTextColor(textMuted)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(42))
            })
            content.addView(row, LinearLayout.LayoutParams(-1, dp(66)).apply { bottomMargin = dp(7) })
            animateEditorItem(row, 80L + index * 45L, 12f)
        }
        animateEditorScreen()
    }

    internal fun topBarVisibility(visible: Boolean) {
        topBar.visibility = if (visible) View.VISIBLE else View.GONE
        applyEdgeInsets(imeBottomInset)
    }

    /**
     * Layout full layar: header (menu/cari/profil) melayang DI ATAS konten, tidak lagi memakai
     * baris penampung sendiri. Konten ScrollView digambar sampai ke belakang status bar dan
     * gesture bar; padding atas/bawah hanya menjaga posisi awal agar item pertama tidak tertutup.
     */
    internal fun applyEdgeInsets(imeBottom: Int) {
        if (!::topBar.isInitialized || !::scroll.isInitialized) return
        val barH = dp(60) + sysTopInset
        val tp = topBar.layoutParams as LinearLayout.LayoutParams
        tp.height = barH
        tp.bottomMargin = -barH
        topBar.layoutParams = tp
        topBar.setPadding(topBar.paddingLeft, sysTopInset, topBar.paddingRight, 0)
        topBar.elevation = dp(8).toFloat()
        topBar.translationZ = dp(2).toFloat()

        val topSpace = if (topBar.visibility == View.VISIBLE) barH else sysTopInset
        val bottomSpace = if (imeBottom > 0) 0 else sysBottomInset
        scroll.clipToPadding = false
        scroll.setPadding(0, topSpace, 0, bottomSpace)

        // Keyboard: sebelumnya ditangani adjustResize; di mode full layar diganti padding manual.
        mainContainer.setPadding(0, 0, 0, imeBottom)

        val nav = bottomNav.layoutParams as LinearLayout.LayoutParams
        nav.bottomMargin = dp(10) + sysBottomInset
        bottomNav.layoutParams = nav

        val eb = editorBottomBar.layoutParams
        eb.height = dp(56) + bottomSpace
        editorBottomBar.layoutParams = eb
        editorBottomBar.setPadding(editorBottomBar.paddingLeft, 0, editorBottomBar.paddingRight, bottomSpace)

        loginScreen.setPadding(dp(28), dp(24) + sysTopInset, dp(28), dp(36) + sysBottomInset)
    }

    internal fun showEditorModePicker() {
        var menuDialog: AlertDialog? = null
        // Tampilan menu sengaja dibuat seperti sheet pada screenshot: tiga kartu besar
        // untuk operasi file, lalu pilihan format berada di dalam "Buat file".
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(4), dp(28), dp(18))
            background = bg(Color.WHITE, 28, Color.TRANSPARENT)
        }
        panel.addView(TextView(this).apply {
            text = "Tambah"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(textMain)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(14))
        })

        fun sheetRow(iconName: String, titleText: String, action: () -> Unit): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(8), dp(16), dp(8))
                background = bg(Color.rgb(247, 247, 248), 18, Color.TRANSPARENT)
                isClickable = true
                setOnClickListener { action() }
                addView(MdiIconView(this@MainActivity).apply {
                    setIconName(iconName)
                    setIconSize(24f)
                    setTextColor(textMain)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(48)).apply { rightMargin = dp(8) }
                })
                addView(TextView(this@MainActivity).apply {
                    text = titleText
                    textSize = 15f
                    setTextColor(textMain)
                    gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
        }

        panel.addView(sheetRow("file-outline", "Buat file") {
            menuDialog?.dismiss()
            showEditorCreateFilePicker()
        }, LinearLayout.LayoutParams(-1, dp(72)).apply { bottomMargin = dp(12) })
        panel.addView(sheetRow("folder-plus-outline", "Buat folder") {
            menuDialog?.dismiss()
            createEditorFolder()
        }, LinearLayout.LayoutParams(-1, dp(72)).apply { bottomMargin = dp(12) })
        panel.addView(sheetRow("folder-open-outline", "Buka file") {
            menuDialog?.dismiss()
            editorExternalTarget = null
            editorExternalMode = null
            pickFileForEditor()
        }, LinearLayout.LayoutParams(-1, dp(72)))

        val dialog = AlertDialog.Builder(this).setView(panel).create()
        menuDialog = dialog
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnShowListener {
            dialog.window?.setDimAmount(0.46f)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes = dialog.window?.attributes?.apply {
            width = (resources.displayMetrics.widthPixels - dp(32))
        }
    }

    internal fun showEditorCreateFilePicker() {
        val labels = arrayOf("HTML (.html)", "CSS (.css)", "JavaScript (.js)", "JSON (.json)", "CSV (.csv)", "Base64 (.txt)", "Teks (.txt)", "Konfigurasi (.ini)", "XML (.xml)", "Utilitas Teks")
        val keys = arrayOf("html", "css", "js", "json", "csv", "base64", "text", "config", "xml", "utility")
        AlertDialog.Builder(this)
            .setTitle("Buat file")
            .setItems(labels) { _, which ->
                editorExternalTarget = null
                editorExternalMode = null
                editorFile = null
                editor(null, keys[which])
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    internal fun createEditorFolder() {
        val name = edit("Nama folder")
        AlertDialog.Builder(this)
            .setTitle("Buat folder")
            .setView(name)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Buat") { _, _ ->
                val folder = safeChildFile(filesDir, name.text.toString())
                if (folder == null) toast("Nama folder tidak valid")
                else if (folder.exists() || !folder.mkdirs()) toast("Folder gagal dibuat")
                else toast("Folder dibuat: ${folder.name}")
            }.show()
    }

    internal fun showEditorMoreMenu() {
        showEditorModePicker()
    }

    internal fun renderEditorPage() {
        // Saat sudah masuk workspace editor, sembunyikan AppBar agar area kode bersih
        // seperti editor pada screenshot. Tombol + dipindah ke kartu nama file.
        topBarVisibility(false)
        subtitle.visibility = View.GONE
        homeMenu.visibility = View.GONE
        homeProfile.visibility = View.GONE
        action.visibility = View.GONE
        back.visibility = View.GONE
        editorMore.visibility = View.GONE
        editorBottomBar.visibility = View.GONE

        content.setPadding(dp(8), dp(6), dp(8), dp(4))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val fileCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = bg(Color.rgb(246, 246, 247), 20, Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, dp(66), 1f)
        }
        fileCard.addView(MdiIconView(this).apply {
            setIconName(if (editorMode == "html") "language-html5" else if (editorMode == "css") "language-css3" else if (editorMode == "js") "language-javascript" else "file-document-outline")
            setIconSize(25f)
            setTextColor(textMain)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(48)).apply { rightMargin = dp(8) }
        })
        editorNameLabel = TextView(this).apply {
            text = editorFile?.name ?: if (editorExternalMode != null) editorDefaultName(editorMode) else "Tanpa judul"
            textSize = 15f
            setTextColor(textMain)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        fileCard.addView(editorNameLabel)
        fileCard.addView(TextView(this).apply {
            text = "✎"
            textSize = 22f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(48))
            setOnClickListener { renameEditorFile() }
        })
        header.addView(fileCard)

        // Hamburger/project-tree sengaja tidak ditampilkan di workspace editor.
        // Menu drawer (homeMenu) tetap tersedia di Beranda saja.

        val console = TextView(this).apply {
            text = "›_"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(textMain)
            background = bg(if (isDarkTheme) panel2 else Color.rgb(242,244,246), 16, Color.TRANSPARENT)
            contentDescription = "Console"
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(5) }
            setOnClickListener { showEditorConsole() }
        }
        header.addView(console)

        val add = TextView(this).apply {
            text = "+"
            textSize = 27f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bg(Color.rgb(16,16,16), 16, Color.TRANSPARENT)
            contentDescription = "New file"
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(44)).apply { leftMargin = dp(5) }
            elevation = dp(3).toFloat()
            setOnClickListener { showEditorModePicker() }
        }
        header.addView(add)
        content.addView(header, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(5) })

        // File tabs: compact, horizontally scrollable, and visually closer to a mobile IDE.
        val tabsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(4))
        }
        val modeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tabModes = listOf("HTML" to "html", "CSS" to "css", "JS" to "js")
        if (editorMode !in tabModes.map { it.second }) {
            tabModes.plus(editorModeName(editorMode) to editorMode).forEach { (labelText, mode) ->
                modeBar.addView(editorTab(labelText, mode), LinearLayout.LayoutParams(-2, dp(38)).apply { rightMargin = dp(5) })
            }
        } else {
            tabModes.forEach { (labelText, mode) ->
                modeBar.addView(editorTab(labelText, mode), LinearLayout.LayoutParams(-2, dp(38)).apply { rightMargin = dp(5) })
            }
        }
        tabsScroll.addView(modeBar)
        content.addView(tabsScroll, LinearLayout.LayoutParams(-1, dp(42)))

        val work = edit(when (editorMode) {
            "html" -> "Ketik HTML...   ! + Tab/Enter = Emmet"
            "css" -> "Ketik CSS..."
            "js" -> "Ketik JavaScript..."
            "json" -> "Ketik JSON di sini..."
            "csv" -> "Ketik data CSV di sini..."
            "base64" -> "Masukkan teks atau Base64..."
            else -> "Ketik teks atau kode di sini..."
        }, true).apply {
            minLines = 1
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(3) }
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = bg(Color.WHITE, 18, Color.rgb(225, 225, 225))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        editorBox = work

        // Quick actions: copy/share + search & replace.
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun quickButton(textValue: String, click: () -> Unit): TextView = TextView(this).apply {
            text = textValue; textSize = 11f; gravity = Gravity.CENTER; setTextColor(textMain)
            background = bg(if (isDarkTheme) panel2 else Color.rgb(242,244,246), 12, line)
            isClickable = true; setOnClickListener { click() }
        }
        quick.addView(quickButton("Copy") { val clip = android.content.ClipData.newPlainText("Editor", work.text.toString()); (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip); toast("Teks disalin") }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(3) })
        quick.addView(quickButton("Share") { shareText(work.text.toString()) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        quick.addView(quickButton("Find") { showEditorSearchDialog(work, false) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        quick.addView(quickButton("Replace") { showEditorSearchDialog(work, true) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(3) })
        content.addView(quick, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(4) })

        // Nomor baris tetap sinkron dengan jumlah baris teks.
        val lineNumbers = TextView(this).apply {
            text = "1"; textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setTextColor(textMuted); setPadding(dp(4), dp(18), dp(4), dp(18)); background = bg(if (isDarkTheme) panel2 else Color.rgb(246,247,248), 18, Color.TRANSPARENT)
        }
        val externalText = editorExternalTarget?.text?.toString()
        when {
            editorFile != null -> work.setText(runCatching { editorFile?.readText().orEmpty() }.getOrDefault(""))
            externalText != null -> work.setText(externalText)
        }
        val initialText = work.text.toString()
        lineNumbers.text = (1..(initialText.count { it == '\n' } + 1)).joinToString("\n")
        val workRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        workRow.addView(lineNumbers, LinearLayout.LayoutParams(dp(38), -1).apply { rightMargin = dp(4) })
        workRow.addView(work, LinearLayout.LayoutParams(0, -1, 1f))
        content.addView(workRow, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(3) })

        editorStatusLabel = TextView(this).apply {
            text = "Baris 1, Kolom 1  |  ${work.text.length} karakter"
            textSize = 11f
            setTextColor(textMuted)
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        content.addView(editorStatusLabel)
        work.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                val txt = s?.toString().orEmpty()
                val lines = txt.count { it == '\n' } + 1
                lineNumbers.text = (1..lines).joinToString("\n")
                editorStatusLabel?.text = "Baris $lines, Kolom ${txt.substringAfterLast('\n').length + 1}  |  ${txt.length} karakter"
            }
            override fun afterTextChanged(e: android.text.Editable?) {}
        })
        // Pewarnaan sintaks (HTML / CSS / JS / JSON) ala VS Code.
        EditorSyntax.attach(work, editorMode)
        work.setTextColor(android.graphics.Color.parseColor("#24292F"))

        // Tool-specific actions tetap bisa dipanggil dari toolbar bawah, tetapi daftar
        // mode/file tambahan tidak lagi memenuhi area editor.
        editorContextActions = null
        renderEditorBottomBar(work)
        animateEditorScreen()
    }

    internal fun showEditorSearchDialog(work: EditText, replaceMode: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), 0) }
        val find = edit("Cari teks")
        val start = work.selectionStart.coerceAtLeast(0).coerceAtMost(work.length())
        val end = work.selectionEnd.coerceAtLeast(0).coerceAtMost(work.length())
        if (start < end) find.setText(work.text.subSequence(start, end).toString())
        box.addView(find)
        val replace = if (replaceMode) edit("Ganti dengan") else null
        replace?.let { box.addView(it) }
        AlertDialog.Builder(this)
            .setTitle(if (replaceMode) "Cari & Ganti" else "Cari")
            .setView(box)
            .setNegativeButton("Batal", null)
            .setPositiveButton(if (replaceMode) "Ganti Semua" else "Cari") { _, _ ->
                val q = find.text.toString()
                if (q.isBlank()) { toast("Teks pencarian kosong"); return@setPositiveButton }
                val source = work.text.toString()
                if (replaceMode) {
                    val r = replace?.text?.toString().orEmpty()
                    val result = source.replace(q, r)
                    work.setText(result)
                    work.setSelection(result.length)
                    toast("${source.split(q).size - 1} kemunculan diganti")
                } else {
                    val index = source.indexOf(q, work.selectionStart.coerceAtLeast(0))
                    if (index >= 0) { work.requestFocus(); work.setSelection(index, index + q.length) } else toast("Teks tidak ditemukan")
                }
            }.show()
    }

    internal fun editorTab(labelText: String, mode: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(10), 0)
        background = bg(
            if (editorMode == mode) (if (isDarkTheme) panel2 else Color.rgb(232,236,240)) else Color.TRANSPARENT,
            12,
            if (editorMode == mode) (if (isDarkTheme) line else Color.rgb(215,220,224)) else Color.TRANSPARENT
        )
        isClickable = true
        isFocusable = true
        contentDescription = "Buka tab $labelText"
        setOnClickListener {
            if (editorMode != mode) {
                editorExternalTarget = null
                editorExternalMode = mode
                editor(null, mode)
            }
        }
        addView(MdiIconView(this@MainActivity).apply {
            setIconName(when (mode) {
                "html" -> "language-html5"
                "css" -> "language-css3"
                "js" -> "language-javascript"
                else -> "file-document-outline"
            })
            setIconSize(16f)
            setTextColor(if (editorMode == mode) textMain else textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(24)).apply { rightMargin = dp(5) }
        })
        addView(TextView(this@MainActivity).apply {
            text = labelText
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (editorMode == mode) textMain else textMuted)
            includeFontPadding = false
        })
    }

    internal fun showEditorProjectTree() {
        val root = editorFile?.parentFile ?: prefs.getString("last_workspace", null)?.let { File(it) }
        val files = root?.listFiles()?.filter { it.isFile && it.name != "workspace.json" }?.sortedBy { it.name.lowercase(Locale.getDefault()) } ?: emptyList()
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Project Files")
                .setMessage("Belum ada file di workspace ini. Buat file baru dari tombol + di editor.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val names = files.map { if (it == editorFile) "✓  ${it.name}" else it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Project Files • ${files.size}")
            .setItems(names) { _, which -> editor(files[which]) }
            .setNegativeButton("Tutup", null)
            .show()
    }

    internal fun appendEditorConsole(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        synchronized(editorConsoleLog) {
            if (editorConsoleLog.length > 120_000) {
                editorConsoleLog.delete(0, editorConsoleLog.length - 80_000)
            }
            editorConsoleLog.append('[').append(stamp).append("] ").append(line).append('\n')
        }
        runOnUiThread {
            editorConsoleView?.text = synchronized(editorConsoleLog) { editorConsoleLog.toString() }
        }
    }

    internal fun clearEditorConsole() {
        synchronized(editorConsoleLog) { editorConsoleLog.setLength(0) }
        editorConsoleView?.text = "Console kosong. Jalankan Preview untuk menangkap console.log / error JS.\n"
    }

    internal fun showEditorConsole() {
        clearPage("Console")
        content.setPadding(dp(12), dp(8), dp(12), dp(16))
        content.addView(label("Console", 22f, true))
        content.addView(subLabel("Menampilkan log JavaScript (console.log, warn, error) dari Preview, plus status editor.", 12f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Clear") { clearEditorConsole() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        row.addView(button("Salin") {
            val t = synchronized(editorConsoleLog) { editorConsoleLog.toString() }
            if (t.isBlank()) toast("Console kosong") else copyText(t)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        row.addView(button("Preview+Log") {
            val src = editorBox?.text?.toString().orEmpty()
            if (src.isBlank()) toast("Editor kosong") else {
                appendEditorConsole("Menjalankan Preview…")
                previewUnifiedEditor(editorBox ?: return@button)
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        if (editorMode == "js") {
            val jsInput = edit("Jalankan ekspresi JS (opsional)", true)
            content.addView(jsInput)
            content.addView(button("Eval JS") {
                val code = jsInput.text.toString().trim()
                if (code.isBlank()) { toast("Ketik kode JS"); return@button }
                appendEditorConsole("> $code")
                val encoded = android.util.Base64.encodeToString(code.toByteArray(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)
                val wrapped = """
                    <!DOCTYPE html><html><head><meta charset="UTF-8"></head><body>
                    <script>
                    (function(){
                      try {
                        var src = atob("$encoded");
                        var __r = (0, eval)(src);
                        if (typeof __r !== "undefined") console.log(String(__r));
                        else console.log("(undefined)");
                      } catch(e) {
                        console.error(e && e.stack ? e.stack : e);
                      }
                    })();
                    </script>
                    <pre>Eval selesai — buka kembali Console untuk melihat log</pre>
                    </body></html>
                """.trimIndent()
                previewHtmlText(wrapped, "HTML")
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6); bottomMargin = dp(10) })
        }

        val logView = TextView(this).apply {
            text = synchronized(editorConsoleLog) {
                if (editorConsoleLog.isEmpty()) {
                    "Console kosong.\n• Tekan Preview di editor untuk menangkap console.log\n• Error JS akan muncul di sini\n• Mode JS: gunakan Eval JS di atas"
                } else editorConsoleLog.toString()
            }
            textSize = 12f
            setTextColor(if (isDarkTheme) Color.rgb(200, 220, 200) else Color.rgb(30, 40, 30))
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = bg(if (isDarkTheme) Color.rgb(18, 20, 22) else Color.rgb(248, 250, 248), 14, line)
            minimumHeight = dp(280)
        }
        editorConsoleView = logView
        content.addView(logView, LinearLayout.LayoutParams(-1, -2))

        content.addView(subLabel("Kembali dengan tombol ‹ di toolbar. Hamburger menu hanya di Beranda.", 11f).apply {
            setPadding(0, dp(12), 0, 0)
        })
    }

    internal fun renameEditorFile() {
        val input = edit("Nama file")
        input.setText(editorNameLabel?.text?.toString()?.removePrefix("Tanpa judul") ?: editorDefaultName(editorMode))
        AlertDialog.Builder(this).setTitle("Nama file").setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("OK") { _, _ ->
                val n = safeFileName(input.text.toString())
                editorNameLabel?.text = n
                val currentFile = editorFile
                if (currentFile != null && currentFile.name != n) {
                    val next = safeChildFile(currentFile.parentFile ?: filesDir, n)
                    if (next != null) runCatching { currentFile.renameTo(next); editorFile = next }
                }
            }.show()
    }

    internal fun renderEditorBottomBar(work: EditText) {
        val bar = editorBottomBar
        bar.removeAllViews()
        bar.visibility = View.VISIBLE
        bar.alpha = 1f
        bar.translationY = 0f
        val actions = listOf(
            "file-plus-outline" to ("Baru" to { showEditorCreateFilePicker() }),
            "folder-open-outline" to ("Buka" to { pickFileForEditor() }),
            "content-save-outline" to ("Simpan" to { saveEditorCurrent() }),
            "undo" to ("Undo" to { work.undoSafe() }),
            "redo" to ("Redo" to { work.redoSafe() }),
            "magnify" to ("Cari" to { showEditorFindDialog(false) }),
            "web" to ("Preview" to { previewUnifiedEditor(work) }),
            "code-tags" to ("Emmet" to { applySimpleEmmet(work) }),
            "select-all" to ("Pilih" to { work.selectAll() })
        )
        actions.forEach { (iconName, item) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { animateEditorPress(this); postDelayed({ item.second() }, 40L) }
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                setPadding(dp(1), dp(3), dp(1), dp(3))
            }
            cell.addView(MdiIconView(this).apply {
                setIconName(iconName)
                setIconSize(21f)
                setTextColor(textMain)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(27))
            })
            cell.addView(TextView(this).apply {
                text = item.first
                textSize = 10f
                setTextColor(textMain)
                gravity = Gravity.CENTER
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(-2, dp(18))
            })
            bar.addView(cell)
        }
    }

    internal fun previewUnifiedEditor(work: EditText) {
        // Preview adalah mode tampilan penuh: toolbar aksi (Baru, Buka, Simpan,
        // Undo, Redo, Cari, Preview, Emmet, Pilih) hanya diperlukan saat mengedit.
        // Sembunyikan sebelum halaman Preview dibuat agar tidak tetap menempel di bawah.
        editorBottomBar.visibility = View.GONE
        val text = work.text.toString()
        when (editorMode) {
            "html" -> previewHtmlText(text, "HTML")
            "css" -> previewHtmlText("<style>${text.htmlEsc()}</style><body><h3>CSS Preview</h3><p>Gunakan HTML untuk melihat hasil styling secara langsung.</p></body>", "HTML")
            "js" -> previewHtmlText("<script>${text}</script><body><h3>JavaScript Preview</h3></body>", "HTML")
            else -> output(text)
        }
    }

    internal fun setEditorMode(mode: String) {
        val currentFile = editorFile
        editorExternalTarget = null
        editorExternalMode = null
        editor(currentFile, mode)
    }

    internal fun refreshEditorContextActions() {
        val row = editorContextActions ?: return
        row.removeAllViews()
        val actions: List<Pair<String, () -> Unit>> = when (editorMode) {
            "json" -> listOf(
                "✦\nFormat" to { transformEditorJson(true) },
                "ϟ\nMinify" to { transformEditorJson(false) },
                "✓\nValidasi" to { validateEditorJson() },
                "▦\nKe CSV" to { jsonToCsvEditor() },
                "▱\nBuka" to { pickFileForEditor() }
            )
            "csv" -> listOf(
                "✦\nRapikan" to { normalizeCsvEditor() },
                "{}\nKe JSON" to { csvToJsonEditor() },
                "▦\nTabel" to { showCsvInfo() },
                "▱\nBuka" to { pickFileForEditor() },
                "▣\nSimpan" to { saveEditorCurrent() }
            )
            "base64" -> listOf(
                "↑\nEncode" to { encodeBase64Editor() },
                "↓\nDecode" to { decodeBase64Editor() },
                "⌫\nBersihkan" to { editorBox?.setText("") },
                "▣\nSimpan" to { saveEditorCurrent() },
                "▱\nBuka" to { pickFileForEditor() }
            )
            "utility" -> listOf(
                "Aa\nCase" to { showEditorCaseDialog() },
                "64\nBase64" to { showEditorBase64Dialog() },
                "#\nHitung" to { showTextCount() },
                "⌕\nCari" to { showEditorFindDialog(false) },
                "↔\nGanti" to { showEditorFindDialog(true) }
            )
            "code" -> listOf(
                "⌕\nCari" to { showEditorFindDialog(false) },
                "↔\nGanti" to { showEditorFindDialog(true) },
                "Aa\nCase" to { showEditorCaseDialog() },
                "64\nBase64" to { showEditorBase64Dialog() },
                "▱\nBuka" to { pickFileForEditor() }
            )
            "config" -> listOf(
                "≡\nFormat" to { formatConfigEditor() },
                "⌕\nCari" to { showEditorFindDialog(false) },
                "↔\nGanti" to { showEditorFindDialog(true) },
                "↺\nReset" to { editorBox?.setText("") },
                "▣\nSimpan" to { saveEditorCurrent() }
            )
            "xml" -> listOf(
                "✓\nValidasi" to { validateXmlEditor() },
                "≡\nFormat" to { formatXmlEditor() },
                "⌕\nCari" to { showEditorFindDialog(false) },
                "↔\nGanti" to { showEditorFindDialog(true) },
                "▣\nSimpan" to { saveEditorCurrent() }
            )
            else -> listOf(
                "▱\nBuka" to { pickFileForEditor() },
                "▣\nSimpan" to { saveEditorCurrent() },
                "Aa\nCase" to { showEditorCaseDialog() },
                "64\nBase64" to { showEditorBase64Dialog() },
                "▢\nCopy" to { copyEditorText() }
            )
        }
        actions.forEach { (txt, click) ->
            val parts = txt.split("\n")
            val v = TextView(this).apply {
                text = "${parts[0]}\n${parts[1]}"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(textMain)
                background = bg(panel2, 13, line)
                setOnClickListener { click() }
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) }
            }
            row.addView(v)
        }
    }

    internal fun copyEditorText() {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clip.setPrimaryClip(android.content.ClipData.newPlainText("Editor", editorBox?.text?.toString().orEmpty()))
        toast("Teks disalin")
    }

    internal fun selectedOrAllText(): String {
        val box = editorBox ?: return ""
        val a = minOf(box.selectionStart, box.selectionEnd)
        val b = maxOf(box.selectionStart, box.selectionEnd)
        return if (a != b) box.text.substring(a, b) else box.text.toString()
    }

    internal fun replaceSelectedOrAll(value: String) {
        val box = editorBox ?: return
        val a = minOf(box.selectionStart, box.selectionEnd)
        val b = maxOf(box.selectionStart, box.selectionEnd)
        if (a != b) {
            box.text.replace(a, b, value)
            box.setSelection(a + value.length)
        } else {
            box.setText(value)
            box.setSelection(box.length())
        }
    }

    internal fun showEditorCaseDialog() {
        val items = arrayOf("UPPERCASE", "lowercase", "Title Case", "Slug / URL", "Hitung kata & karakter")
        AlertDialog.Builder(this).setTitle("Text Case & Utility").setItems(items) { _, which ->
            when (which) {
                0 -> replaceSelectedOrAll(selectedOrAllText().toUpperCase(Locale.getDefault()))
                1 -> replaceSelectedOrAll(selectedOrAllText().toLowerCase(Locale.getDefault()))
                2 -> replaceSelectedOrAll(selectedOrAllText().toLowerCase(Locale.getDefault()).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { word -> if (word.isEmpty()) word else word.substring(0, 1).toUpperCase(Locale.getDefault()) + word.substring(1) })
                3 -> replaceSelectedOrAll(selectedOrAllText().trim().toLowerCase(Locale.getDefault()).replace(Regex("[^a-z0-9]+"), "-").trim('-'))
                4 -> showTextCount()
            }
        }.setNegativeButton("Batal", null).show()
    }

    internal fun showTextCount() {
        val s = selectedOrAllText()
        val words = s.trim().let { if (it.isEmpty()) 0 else it.split(Regex("\\s+")).size }
        toast("$words kata • ${s.length} karakter")
    }

    internal fun showEditorBase64Dialog() {
        AlertDialog.Builder(this).setTitle("Base64").setItems(arrayOf("Encode", "Decode")) { _, which ->
            if (which == 0) encodeBase64Editor() else decodeBase64Editor()
        }.setNegativeButton("Batal", null).show()
    }

    internal fun encodeBase64Editor() {
        val bytes = selectedOrAllText().toByteArray(StandardCharsets.UTF_8)
        replaceSelectedOrAll(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
    }

    internal fun decodeBase64Editor() {
        runCatching { String(Base64.getDecoder().decode(selectedOrAllText().trim()), StandardCharsets.UTF_8) }
            .onSuccess { replaceSelectedOrAll(it) }
            .onFailure { toast("Base64 tidak valid") }
    }

    internal fun normalizeCsvEditor() {
        val lines = editorBox?.text?.toString()?.lines()?.filter { it.isNotBlank() }.orEmpty()
        if (lines.isEmpty()) return
        val delim = if (lines.first().count { it == ';' } > lines.first().count { it == ',' }) ';' else ','
        val out = lines.joinToString("\n") { csvParseLine(it, delim).joinToString(",") { cell -> csvEscape(cell.trim()) } }
        editorBox?.setText(out)
        editorBox?.setSelection(editorBox?.length() ?: 0)
    }

    internal fun csvParseLine(line: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (quoted && i + 1 < line.length && line[i + 1] == '"') { cur.append('"'); i++ } else quoted = !quoted
            } else if (c == delimiter && !quoted) { out.add(cur.toString()); cur.setLength(0) } else cur.append(c)
            i++
        }
        out.add(cur.toString()); return out
    }

    internal fun csvEscape(s: String): String = if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"${s.replace("\"", "\"\"")}\"" else s

    internal fun csvToJsonEditor() {
        val lines = editorBox?.text?.toString()?.lines()?.filter { it.isNotBlank() }.orEmpty()
        if (lines.size < 1) return
        val delimiter = if (lines.first().count { it == ';' } > lines.first().count { it == ',' }) ';' else ','
        val headers = csvParseLine(lines.first(), delimiter)
        val arr = JSONArray()
        lines.drop(1).forEach { line ->
            val cells = csvParseLine(line, delimiter); val obj = JSONObject()
            headers.forEachIndexed { i, h -> obj.put(h.trim(), cells.getOrElse(i) { "" }) }
            arr.put(obj)
        }
        editorBox?.setText(prettyJson(arr.toString()))
        editorBox?.setSelection(editorBox?.length() ?: 0)
    }

    internal fun jsonToCsvEditor() {
        val s = editorBox?.text?.toString()?.trim().orEmpty()
        runCatching {
            val arr = if (s.startsWith("[")) JSONArray(s) else JSONArray().put(JSONObject(s))
            if (arr.length() == 0) return@runCatching ""
            val keys = linkedSetOf<String>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.keys()?.forEach { keys.add(it) }
            val header = keys.joinToString(",") { csvEscape(it) }
            val rows = (0 until arr.length()).map { i ->
                val o = arr.optJSONObject(i) ?: JSONObject()
                keys.joinToString(",") { k -> csvEscape(o.opt(k)?.toString() ?: "") }
            }
            (listOf(header) + rows).joinToString("\n")
        }.onSuccess { editorBox?.setText(it); editorBox?.setSelection(editorBox?.length() ?: 0) }
            .onFailure { toast("JSON tidak valid: ${it.message}") }
    }

    internal fun showCsvInfo() {
        val lines = editorBox?.text?.toString()?.lines()?.filter { it.isNotBlank() }.orEmpty()
        val delimiter = lines.firstOrNull()?.let { if (it.count { c -> c == ';' } > it.count { c -> c == ',' }) ';' else ',' } ?: ','
        val cols = lines.firstOrNull()?.let { csvParseLine(it, delimiter).size } ?: 0
        toast("${lines.size} baris • $cols kolom")
    }

    internal fun saveEditorCurrent() {
        val box = editorBox ?: return
        editorExternalTarget?.let {
            it.setText(box.text.toString())
            toast("Diterapkan ke ${editorMode.uppercase(Locale.getDefault())}")
            return
        }
        val name = safeFileName((editorNameLabel?.text?.toString() ?: "").trim().ifEmpty { editorDefaultName(editorMode) })
        val target = editorFile ?: safeChildFile(filesDir, name)
        if (target == null) { toast("Nama file tidak valid"); return }
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(box.text.toString())
            editorFile = target
            editorNameLabel?.text = target.name
        }.onSuccess { toast("Tersimpan: ${target.name}") }
            .onFailure { toast("Gagal menyimpan: ${it.message}") }
    }

    internal fun validateEditorJson() {
        val s = editorBox?.text?.toString()?.trim().orEmpty()
        val result = runCatching {
            if (s.startsWith("{")) JSONObject(s) else if (s.startsWith("[")) JSONArray(s) else error("JSON harus dimulai dengan { atau [")
            "JSON valid"
        }.getOrElse { "JSON tidak valid: ${it.message}" }
        toast(result)
    }

    internal fun transformEditorJson(pretty: Boolean) {
        val box = editorBox ?: return
        runCatching {
            box.setText(if (pretty) prettyJson(box.text.toString()) else minifyJson(box.text.toString()))
            box.setSelection(box.length())
        }.onFailure { toast("JSON tidak valid: ${it.message}") }
    }

    internal fun showEditorFindDialog(replace: Boolean) {
        val find = edit("Cari")
        val repl = if (replace) edit("Ganti dengan") else null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        box.addView(find); repl?.let { box.addView(it) }
        AlertDialog.Builder(this).setTitle(if (replace) "Cari & Ganti" else "Cari")
            .setView(box)
            .setNegativeButton("Batal", null)
            .setPositiveButton(if (replace) "Ganti" else "Cari") { _, _ ->
                val source = editorBox?.text?.toString().orEmpty()
                val q = find.text.toString()
                if (q.isEmpty()) { toast("Teks pencarian kosong"); return@setPositiveButton }
                if (replace) editorBox?.setText(source.replace(q, repl?.text?.toString().orEmpty()))
                else toast(if (source.contains(q)) "Ditemukan" else "Tidak ditemukan")
            }.show()
    }

    internal fun formatConfigEditor() {
        val box = editorBox ?: return
        val out = box.text.toString().lines().joinToString("\n") { line ->
            line.trim().replace(Regex("\\s*=\\s*"), " = ")
        }.trim()
        box.setText(out)
    }

    internal fun validateXmlEditor() {
        val s = editorBox?.text?.toString().orEmpty()
        runCatching {
            val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            f.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(s)))
            "XML valid"
        }.onSuccess { toast(it) }.onFailure { toast("XML tidak valid: ${it.message}") }
    }

    internal fun formatXmlEditor() {
        val box = editorBox ?: return
        runCatching {
            val f = javax.xml.transform.TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            }
            val sw = StringWriter()
            f.transform(javax.xml.transform.stream.StreamSource(StringReader(box.text.toString())), javax.xml.transform.stream.StreamResult(sw))
            box.setText(sw.toString())
        }.onFailure { toast("XML tidak valid: ${it.message}") }
    }

    internal fun EditText.undoSafe() {
        runCatching {
            val m = java.lang.reflect.Method::class
            val field = EditText::class.java.getDeclaredField("mEditor")
            field.isAccessible = true
            val editorObj = field.get(this)
            val undo = editorObj.javaClass.getMethod("undo")
            undo.invoke(editorObj)
        }.onFailure { toast("Undo tidak tersedia pada perangkat ini") }
    }

    internal fun EditText.redoSafe() {
        runCatching {
            val field = EditText::class.java.getDeclaredField("mEditor")
            field.isAccessible = true
            val editorObj = field.get(this)
            val redo = editorObj.javaClass.getMethod("redo")
            redo.invoke(editorObj)
        }.onFailure { toast("Redo tidak tersedia pada perangkat ini") }
    }

    internal fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "*/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Bagikan file"))
    }

    // ---------- GITHUB ZIP PUBLISHER ----------
    // Tiga layar dalam satu halaman: Pengaturan -> Proses Upload -> Upload Selesai.

    internal data class GhProgress(val step: Int, val detail: String, val current: Int = 0, val total: Int = 0)

    internal data class GhResult(
        val owner: String, val repo: String, val branch: String,
        val files: Int, val bytes: Long, val commitSha: String,
        val url: String, val createdRepo: Boolean, val createdPrivate: Boolean
    )

    internal var ghStage: FrameLayout? = null
    internal var ghSource = 0
    internal var ghBranch = "main"
    internal var ghSaveToken = true
    internal var ghPrivateRepo = true
    internal var ghRunning = false
    internal var ghStartedAt = 0L
    internal var ghCurrentStep = 0
    internal var ghFileTotal = 0
    internal var ghRetry: (() -> Unit)? = null
    internal var ghLastResult: GhResult? = null
    internal var ghUserValue = ""
    internal var ghRepoValue = ""
    internal var ghTokenValue = ""
    internal var ghCommitValue = ""
    @Volatile private var ghCancelled = false
    internal var ghPendingResult: GhResult? = null
    internal var ghPendingError: Throwable? = null
    internal var ghFinishedSec = -1
    internal var ghPct = 0
    internal var ghAppForeground = true
    internal val ghStepLast = arrayOfNulls<GhProgress>(5)
    internal var ghCancelBtn: LinearLayout? = null
    internal var ghCancelLabel: TextView? = null
    internal class GhCancelException : RuntimeException("Upload dibatalkan")
    internal var ghRing: ProgressRingView? = null
    internal var ghPercentText: TextView? = null
    internal var ghElapsedText: TextView? = null
    internal var ghNoteBox: LinearLayout? = null
    internal var ghErrorHost: LinearLayout? = null
    internal val ghStepViews = ArrayList<StepStateView>()
    internal val ghStepTitles = ArrayList<TextView>()
    internal val ghStepDetails = ArrayList<TextView>()
    internal val ghHandler = Handler(Looper.getMainLooper())
    internal val ghTicker = object : Runnable {
        override fun run() {
            if (!ghRunning) return
            val sec = ((SystemClock.elapsedRealtime() - ghStartedAt) / 1000L).toInt()
            ghElapsedText?.text = "Berjalan %02d:%02d".format(sec / 60, sec % 60)
            ghHandler.postDelayed(this, 1000L)
        }
    }

    // ----- warna & helper kecil (mengikuti tema terang/gelap aplikasi) -----
    internal fun ghc(dark: Long, light: Long): Int = (if (isDarkTheme) dark else light).toInt()
    internal val ghInk: Int get() = ghc(0xFFF4F4F6, 0xFF15161A)
    internal val ghOnInk: Int get() = ghc(0xFF15161A, 0xFFFFFFFF)
    internal val ghCard: Int get() = ghc(0xFF1B1D22, 0xFFFFFFFF)
    internal val ghStroke: Int get() = ghc(0xFF34373F, 0xFFE3E5EA)
    internal val ghMuted: Int get() = ghc(0xFF9DA0A9, 0xFF6C717C)
    internal val ghSoft: Int get() = ghc(0xFF23262C, 0xFFF0F1F4)
    internal val ghDanger: Int get() = 0xFFD9534F.toInt()

    internal fun ghRound(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(strokeDp), stroke)
        }

    /** Terapkan sudut tumpul konsisten pada dialog GitHub, termasuk panel dan inset konten. */
    internal fun ghStyleRoundedDialog(dialog: AlertDialog, radiusDp: Int = 24): AlertDialog {
        dialog.window?.setBackgroundDrawable(ghRound(ghCard, radiusDp, ghStroke))
        dialog.window?.decorView?.setPadding(dp(2), dp(2), dp(2), dp(2))
        return dialog
    }

    internal fun ghText(text: String, sp: Float, color: Int = ghInk, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    internal fun ghIcon(name: String, sp: Float, color: Int = ghInk): MdiIconView =
        MdiIconView(this).apply { setIconName(name); setIconSize(sp); setTextColor(color) }

    internal fun ghLogo(sizeDp: Int, color: Int): PublishLogoView =
        PublishLogoView(this).apply { this.color = color; layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)) }

    internal fun ghFormatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> "%.2f GB".format(Locale.US, bytes / gb)
            bytes >= mb -> "%.1f MB".format(Locale.US, bytes / mb)
            bytes >= kb -> "%.1f KB".format(Locale.US, bytes / kb)
            else -> "$bytes B"
        }
    }

    internal fun ghHideKeyboard(v: View) {
        runCatching {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(v.windowToken, 0)
        }
    }

    internal fun ghWatch(edit: EditText, onChange: () -> Unit) {
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { onChange() }
        })
    }

    // ----- komponen form -----
    internal class GhField(val root: LinearLayout, val edit: EditText, val error: TextView, val row: LinearLayout)

    internal fun ghFieldBg(focused: Boolean, error: Boolean): GradientDrawable =
        ghRound(ghCard, 16, if (error) ghDanger else if (focused) ghInk else ghStroke, if (focused || error) 2 else 1)

    internal fun ghField(labelText: String, iconName: String, hint: String, initial: String, password: Boolean = false): GhField {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ghText(labelText, 13f, ghInk, true), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(7); leftMargin = dp(2) })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(10), 0)
            background = ghFieldBg(false, false)
        }
        row.addView(ghIcon(iconName, 20f, ghMuted), LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) })
        val edit = EditText(this).apply {
            this.hint = hint
            setText(initial)
            textSize = 15f
            setTextColor(ghInk)
            setHintTextColor(ghMuted)
            background = null
            setPadding(0, 0, 0, 0)
            maxLines = 1
            setSingleLine(true)
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        row.addView(edit, LinearLayout.LayoutParams(0, dp(54), 1f))
        if (password) {
            val eye = ghIcon("eye-outline", 20f, ghMuted).apply {
                isClickable = true
                setOnClickListener {
                    val visible = edit.transformationMethod == null
                    val cursor = edit.selectionStart
                    if (visible) {
                        edit.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                        setIconName("eye-outline")
                    } else {
                        edit.transformationMethod = null
                        setIconName("eye-off-outline")
                    }
                    edit.setSelection(cursor.coerceAtLeast(0).coerceAtMost(edit.text.length))
                }
            }
            row.addView(eye, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        root.addView(row, LinearLayout.LayoutParams(-1, -2))
        val error = ghText("", 12f, ghDanger).apply { visibility = View.GONE; setPadding(dp(4), dp(6), 0, 0) }
        root.addView(error, LinearLayout.LayoutParams(-1, -2))
        root.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
        val field = GhField(root, edit, error, row)
        edit.setOnFocusChangeListener { _, hasFocus -> row.background = ghFieldBg(hasFocus, error.visibility == View.VISIBLE) }
        ghWatch(edit) { ghClearError(field) }
        return field
    }

    internal fun ghSetError(field: GhField, message: String) {
        field.error.text = message
        field.error.visibility = View.VISIBLE
        field.row.background = ghFieldBg(field.edit.hasFocus(), true)
        Motion.error(field.row)
    }

    internal fun ghClearError(field: GhField) {
        if (field.error.visibility == View.VISIBLE) {
            field.error.visibility = View.GONE
            field.row.background = ghFieldBg(field.edit.hasFocus(), false)
        }
    }

    internal fun ghPressable(view: View, onClick: () -> Unit) {
        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            Motion.tap(view, 0.98f)
            onClick()
        }
    }

    internal fun ghPrimaryButton(text: String, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = ghRound(ghInk, 18)
        addView(ghLogo(22, ghOnInk), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) })
        addView(ghText(text, 15f, ghOnInk, true))
        addView(ghIcon("arrow-right", 18f, ghOnInk), LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(10) })
        layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(6); bottomMargin = dp(10) }
        ghPressable(this, onClick)
    }

    internal fun ghOutlineButton(text: String, iconName: String, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = ghRound(ghCard, 18, ghStroke)
        addView(ghIcon(iconName, 19f, ghInk), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
        addView(ghText(text, 15f, ghInk, true))
        layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) }
        ghPressable(this, onClick)
    }

    internal fun ghSmallButton(text: String, iconName: String, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = ghRound(ghSoft, 12)
        setPadding(dp(12), 0, dp(12), 0)
        addView(ghIcon(iconName, 17f, ghInk), LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(6) })
        addView(ghText(text, 13f, ghInk, true))
        ghPressable(this, onClick)
    }

    internal fun ghSwitchRow(iconName: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), 0, dp(2))
        }
        row.addView(ghIcon(iconName, 18f, ghMuted), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
        row.addView(ghText(text, 13.5f, ghMuted), LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply {
            isChecked = checked
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(ghInk, ghStroke))
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(ghOnInk, ghCard))
            setOnCheckedChangeListener { v, value -> v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onChange(value) }
        }
        row.addView(sw, LinearLayout.LayoutParams(-2, dp(40)))
        return row
    }

    internal fun ghSectionTitle(text: String): TextView =
        ghText(text, 12.5f, ghMuted, true).apply { setPadding(dp(2), dp(6), 0, dp(10)) }

    // ----- pergantian layar -----
    internal fun ghShow(screen: View, titleText: String) {
        val stage = ghStage ?: return
        title.text = titleText
        stage.removeAllViews()
        stage.addView(screen, FrameLayout.LayoutParams(-1, -2))
        screen.alpha = 0f
        screen.translationY = dp(14).toFloat()
        screen.animate().alpha(1f).translationY(0f).setDuration(260).start()
        scroll.post { scroll.smoothScrollTo(0, 0) }
    }

    // =====================================================================
    // Layar 1: Pengaturan GitHub
    // =====================================================================
    internal fun githubZipTool() {
        clearPage("GitHub Publisher")
        ghUserValue = prefs.getString("gh_user", null) ?: "username183728"
        ghRepoValue = prefs.getString("gh_repo", null) ?: "B1"
        // Pulihkan ZIP terakhir supaya nama file yang sudah dipilih tetap tampil.
        githubZipUri = prefs.getString("gh_selected_zip_uri", null)?.let {
            runCatching { Uri.parse(it) }.getOrNull()
        }
        ghBranch = prefs.getString("gh_branch", null) ?: "main"
        ghSaveToken = prefs.getBoolean("gh_save_token", true)
        ghPrivateRepo = prefs.getBoolean("gh_private", true)
        ghTokenValue = if (ghSaveToken) prefs.getString("gh_token_enc", null)?.let { GithubTokenVault.decrypt(it) }.orEmpty() else ""
        ghCommitValue = prefs.getString("gh_commit", null) ?: "Upload project via GITLS"
        ghStage = FrameLayout(this)
        content.addView(ghStage, LinearLayout.LayoutParams(-1, -2))
        val pendingResult = ghPendingResult
        val pendingError = ghPendingError
        when {
            ghRunning -> {
                // Upload masih berjalan: kembali ke layar proses, bukan halaman pengaturan.
                ghShow(ghProgressScreen("", ghUserValue, ghRepoValue, ghBranch), "Proses Upload")
                ghReplayProgress()
                ghHandler.removeCallbacks(ghTicker)
                ghHandler.post(ghTicker)
            }
            pendingResult != null -> { ghPendingResult = null; ghShowResult(pendingResult) }
            pendingError != null -> {
                ghPendingError = null
                ghShow(ghProgressScreen("", ghUserValue, ghRepoValue, ghBranch), "Proses Upload")
                ghReplayProgress()
                ghShowFailure(pendingError)
            }
            else -> {
                ghShow(ghSettingsScreen(), "Pengaturan GitHub")
                // Pulihkan preview ZIP terakhir bila URI masih bisa dibaca.
                githubZipUri?.let { uri ->
                    if (prefs.getString("gh_selected_zip_name", null).orEmpty().isNotBlank()) {
                        githubUploadStatus?.text = "Memulihkan ZIP terakhir…"
                        prepareGithubZipPreview(uri)
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); ghAppForeground = true }
    override fun onStop() { ghAppForeground = false; super.onStop() }

    internal fun ghSettingsScreen(): LinearLayout {
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(4), dp(2), dp(24)) }

        // kepala: logo + penjelasan singkat
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ghRound(ghSoft, 20)
        }
        head.addView(ghLogo(38, ghInk), LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(14) })
        val headTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headTexts.addView(ghText("GITLS Publisher", 16f, ghInk, true))
        headTexts.addView(ghText("Kirim ZIP atau folder project ke repository GitHub tanpa perintah Git.", 12f, ghMuted).apply { setPadding(0, dp(4), 0, 0) })
        head.addView(headTexts, LinearLayout.LayoutParams(0, -2, 1f))
        screen.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        val user = ghField("Username", "account-outline", "username GitHub", ghUserValue)
        val repo = ghField("Repository", "source-repository", "nama repository", ghRepoValue)
        screen.addView(user.root)
        screen.addView(repo.root)

        // branch (dropdown)
        screen.addView(ghText("Branch", 13f, ghInk, true), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(7); leftMargin = dp(2) })
        val branchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = ghFieldBg(false, false)
        }
        branchRow.addView(ghIcon("source-branch", 20f, ghMuted), LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) })
        val branchText = ghText(ghBranch, 15f, ghInk)
        branchRow.addView(branchText, LinearLayout.LayoutParams(0, -2, 1f))
        branchRow.addView(ghIcon("chevron-down", 20f, ghMuted), LinearLayout.LayoutParams(dp(24), dp(24)))
        ghPressable(branchRow) {
            ghHideKeyboard(branchRow)
            val options = listOf("main", "master", "develop", "Lainnya…")
            AlertDialog.Builder(this)
                .setTitle("Pilih branch")
                .setItems(options.toTypedArray()) { _, which ->
                    if (which < options.size - 1) {
                        ghBranch = options[which]
                        branchText.text = ghBranch
                    } else {
                        val input = EditText(this).apply { setText(ghBranch); setSingleLine(true); setPadding(dp(20), dp(14), dp(20), dp(14)) }
                        AlertDialog.Builder(this)
                            .setTitle("Nama branch")
                            .setView(input)
                            .setNegativeButton("Batal", null)
                            .setPositiveButton("Pakai") { _, _ ->
                                val value = input.text.toString().trim()
                                if (value.matches(Regex("[A-Za-z0-9._/-]+"))) { ghBranch = value; branchText.text = value }
                                else toast("Nama branch tidak valid")
                            }.show()
                    }
                }.show()
        }
        screen.addView(branchRow, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(16) })

        val token = ghField("Token (Personal Access Token)", "key-variant", "ghp_••••••••••••", ghTokenValue, password = true)
        token.root.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        screen.addView(token.root)
        screen.addView(ghSwitchRow("information-outline", "Simpan token (terenkripsi)", ghSaveToken) { ghSaveToken = it })
        screen.addView(ghSwitchRow("lock-outline", "Repository baru dibuat private", ghPrivateRepo) { ghPrivateRepo = it })

        val commit = ghField("Pesan commit", "text-box-outline", "Upload project via GITLS", ghCommitValue)
        commit.root.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(8) }
        screen.addView(commit.root)

        // sumber project
        screen.addView(ghSectionTitle("Sumber project"))
        screen.addView(ghSourcePicker())

        // status
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(14))
        }
        statusRow.addView(ghIcon("information-outline", 16f, ghMuted), LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
        val statusText = ghText("Pilih ZIP atau folder untuk memulai.", 12f, ghMuted)
        githubUploadStatus = statusText
        statusRow.addView(statusText, LinearLayout.LayoutParams(0, -2, 1f))
        screen.addView(statusRow)

        screen.addView(ghPrimaryButton("Simpan & Upload") {
            ghHideKeyboard(screen)
            val owner = user.edit.text.toString().trim()
            val repository = repo.edit.text.toString().trim()
            val pat = token.edit.text.toString().trim()
            val message = commit.edit.text.toString().trim()
            var valid = true
            if (!owner.matches(Regex("[A-Za-z0-9_.-]+"))) { ghSetError(user, "Username hanya boleh huruf, angka, titik, garis"); valid = false }
            if (!repository.matches(Regex("[A-Za-z0-9_.-]+"))) { ghSetError(repo, "Nama repository tidak valid"); valid = false }
            if (pat.isBlank()) { ghSetError(token, "Token wajib diisi"); valid = false }
            if (!valid) return@ghPrimaryButton
            if (!validateGithubInputs(owner, repository, pat, ghBranch)) return@ghPrimaryButton
            val commitMessage = message.ifBlank { "Upload project via GITLS" }
            val zipUri = githubZipUri
            val folderUri = githubFolderUri
            if (ghSource == 0 && zipUri == null) { toast("Pilih file ZIP terlebih dahulu"); return@ghPrimaryButton }
            if (ghSource == 0 && githubZipPreviewFiles.isEmpty()) { toast("Tunggu analisis ZIP selesai atau pilih ZIP lagi"); return@ghPrimaryButton }
            if (ghSource == 1 && folderUri == null) { toast("Pilih folder project terlebih dahulu"); return@ghPrimaryButton }

            ghUserValue = owner; ghRepoValue = repository; ghTokenValue = pat; ghCommitValue = commitMessage
            ghSaveSettings(owner, repository, pat, commitMessage)

            val branch = ghBranch
            val makePrivate = ghPrivateRepo
            if (ghSource == 0 && zipUri != null) {
                val root = githubZipRoot
                val excluded = githubZipExcluded.toSet()
                ghStartUpload("ZIP", owner, repository, branch) { p -> uploadZipToGitHub(zipUri, owner, repository, pat, branch, commitMessage, root, excluded, makePrivate, p) }
            } else if (folderUri != null) {
                ghStartUpload("Folder", owner, repository, branch) { p -> uploadFolderToGitHub(folderUri, owner, repository, pat, branch, commitMessage, makePrivate, p) }
            }
        })

        screen.addView(
            ghText("Token dipakai langsung untuk request ke GitHub. Bila \"Simpan token\" mati, token tidak disimpan sama sekali. Butuh izin Contents read/write (classic: scope repo).", 11f, ghMuted)
                .apply { setPadding(dp(4), dp(4), dp(4), 0); setLineSpacing(0f, 1.15f) }
        )

        screen.addView(ghOutlineButton("Lihat Repository di GitHub", "folder-open-outline") {
            val owner = user.edit.text.toString().trim()
            val repository = repo.edit.text.toString().trim()
            val pat = token.edit.text.toString().trim()
            if (!owner.matches(Regex("[A-Za-z0-9_.-]+")) || !repository.matches(Regex("[A-Za-z0-9_.-]+")) || pat.isBlank()) {
                toast("Username, repository, dan token wajib diisi")
                return@ghOutlineButton
            }
            ghUserValue = owner; ghRepoValue = repository; ghTokenValue = pat
            ghSaveSettings(owner, repository, pat, commit.edit.text.toString().trim().ifBlank { "Upload project via GITLS" })
            ghShow(ghRepositoryBrowserScreen(""), "Isi Repository")
        })
        return screen
    }

    // ----- browser repository GitHub: lihat folder/file + hapus dengan verifikasi ganda -----
    internal fun ghRepositoryBrowserScreen(path: String): LinearLayout {
        val owner = ghUserValue.trim()
        val repo = ghRepoValue.trim()
        val token = ghTokenValue.trim().ifBlank {
            prefs.getString("gh_token_enc", null)?.let { GithubTokenVault.decrypt(it) }.orEmpty()
        }
        val branch = ghBranch.ifBlank { "main" }
        val cleanPath = path.trim('/').replace("//", "/")
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(24))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = ghRound(ghSoft, 16)
        }
        val back = ghIcon("arrow-left", 20f, ghInk).apply {
            visibility = if (cleanPath.isBlank()) View.GONE else View.VISIBLE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            setOnClickListener {
                val parent = cleanPath.substringBeforeLast('/', "")
                ghShow(ghRepositoryBrowserScreen(parent), "Isi Repository")
            }
        }
        top.addView(back, LinearLayout.LayoutParams(dp(42), dp(42)))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(ghText(if (cleanPath.isBlank()) "$owner/$repo" else cleanPath, 15f, ghInk, true))
        titleBox.addView(ghText("Branch: $branch", 11f, ghMuted).apply { setPadding(0, dp(3), 0, 0) })
        top.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        val refresh = ghIcon("refresh", 20f, ghInk).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            setOnClickListener { ghShow(ghRepositoryBrowserScreen(cleanPath), "Isi Repository") }
        }
        top.addView(refresh, LinearLayout.LayoutParams(dp(42), dp(42)))
        screen.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val info = ghText("Memuat isi repository…", 12f, ghMuted).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        screen.addView(info)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        if (token.isBlank()) {
            info.text = "Token GitHub belum tersedia."
            return screen
        }

        toolThread {
            val result = runCatching {
                val endpoint = "https://api.github.com/repos/${Uri.encode(owner)}/${Uri.encode(repo)}/contents/${if (cleanPath.isBlank()) "" else encodePath(cleanPath)}?ref=${Uri.encode(branch)}"
                val json = githubRequestRaw(endpoint, token)
                if (json.trimStart().startsWith("[")) {
                    val arr = JSONArray(json)
                    val items = (0 until arr.length()).map { arr.getJSONObject(it) }
                        .sortedWith(compareBy<JSONObject>({ it.optString("type") != "dir" }, { it.optString("name").lowercase(Locale.getDefault()) }))
                    items
                } else {
                    listOf(JSONObject(json))
                }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                list.removeAllViews()
                result.onSuccess { items ->
                    info.text = if (items.isEmpty()) "Folder kosong." else "${items.size} item"
                    if (items.isEmpty()) return@onSuccess
                    items.forEach { item ->
                        val itemPath = item.optString("path")
                        val isDir = item.optString("type") == "dir"
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(12), dp(8), dp(4), dp(8))
                            background = ghRound(ghCard, 18, ghStroke)
                        }
                        val icon = ghIcon(if (isDir) "folder-outline" else "file-outline", 21f, ghInk)
                        row.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(8) })
                        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                        texts.addView(ghText(item.optString("name"), 14f, ghInk, true))
                        texts.addView(ghText(if (isDir) "Folder" else ghFormatBytes(item.optLong("size", 0L)), 11f, ghMuted).apply { setPadding(0, dp(3), 0, 0) })
                        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                        val trash = ghIcon("delete-outline", 20f, ghDanger).apply {
                            setPadding(dp(8), dp(8), dp(8), dp(8))
                            isClickable = true
                            contentDescription = "Hapus ${item.optString("name")}"
                            setOnClickListener {
                                val target = item.optString("name")
                                ghConfirmGithubDelete(itemPath, isDir, target, token) {
                                    ghShow(ghRepositoryBrowserScreen(cleanPath), "Isi Repository")
                                }
                            }
                        }
                        row.addView(trash, LinearLayout.LayoutParams(dp(44), dp(44)))
                        row.setOnClickListener {
                            if (isDir) ghShow(ghRepositoryBrowserScreen(itemPath), "Isi Repository")
                            else ghOpenUrl(item.optString("html_url").ifBlank { "https://github.com/$owner/$repo/blob/$branch/${encodePath(itemPath)}" })
                        }
                        list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
                    }
                    val deleteAllLabel = if (cleanPath.isBlank()) "Hapus Semua File Repository" else "Hapus Semua File di Folder Ini"
                    val deleteAll = ghOutlineButton(deleteAllLabel, "delete-sweep-outline") {
                        ghConfirmGithubDeleteAll(token, cleanPath) {
                            ghShow(ghRepositoryBrowserScreen(cleanPath), "Isi Repository")
                        }
                    }
                    // Samakan tinggi dan sudut tombol dengan kartu/file lain agar benar-benar tumpul.
                    deleteAll.layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply {
                        topMargin = dp(10)
                        bottomMargin = dp(2)
                    }
                    list.addView(deleteAll)
                }.onFailure { e ->
                    info.text = "Gagal memuat repository."
                    toast(e.message ?: "Gagal membaca GitHub")
                }
            }
        }
        return screen
    }

    internal fun ghConfirmGithubDelete(path: String, isDir: Boolean, name: String, token: String, onDone: () -> Unit) {
        val kind = if (isDir) "folder" else "file"
        val deleteLabel = "Hapus $kind $name"

        // Verifikasi pertama: cukup pilih Batal atau tombol hapus dengan nama item.
        AlertDialog.Builder(this)
            .setTitle("Hapus $kind?")
            .setMessage("$name\n\nItem ini akan dihapus dari branch $ghBranch dan perubahan akan disimpan ke GitHub.")
            .setNegativeButton("Batal", null)
            .setPositiveButton(deleteLabel) { _, _ ->
                // Verifikasi kedua: tidak perlu mengetik HAPUS.
                AlertDialog.Builder(this)
                    .setTitle("Konfirmasi sekali lagi")
                    .setMessage("Yakin ingin menghapus $kind ini?\n\n$name")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton(deleteLabel) { _, _ ->
                        ghRunDelete(path, isDir, token, onDone)
                    }
                    .show().also { ghStyleRoundedDialog(it) }
            }
            .show().also { ghStyleRoundedDialog(it) }
    }

    internal fun ghConfirmGithubDeleteAll(token: String, prefix: String, onDone: () -> Unit) {
        val scope = prefix.trim('/').let { if (it.isBlank()) "seluruh repository" else "folder $it" }

        // Hapus semua memakai pola konfirmasi yang sama dengan hapus satu file/folder:
        // tidak ada input teks "HAPUS SEMUA" dan tetap ada konfirmasi kedua.
        AlertDialog.Builder(this)
            .setTitle("Hapus semua?")
            .setMessage("Semua file di $scope pada branch $ghBranch akan dihapus. Folder kosong juga akan hilang karena GitHub tidak menyimpan folder kosong.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus Semua") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Konfirmasi sekali lagi")
                    .setMessage("Yakin ingin menghapus semua file?\n\n$scope")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Hapus Semua") { _, _ ->
                        ghRunDeleteAll(token, prefix, onDone)
                    }
                    .show().also { ghStyleRoundedDialog(it) }
            }
            .show().also { ghStyleRoundedDialog(it) }
    }

    /** Pesan error khusus penghapusan (tambahan 409, rate limit, branch protection). */
    internal fun ghDeleteError(e: Throwable): String {
        val raw = e.message ?: "unknown error"
        val m = Regex("GitHub HTTP (\\d+): (.*)", RegexOption.DOT_MATCHES_ALL).find(raw) ?: return raw
        val code = m.groupValues[1].toInt()
        val detail = m.groupValues[2].trim()
        val hint = when {
            code == 401 -> "Token tidak valid atau kedaluwarsa. Buat token baru lalu simpan lagi."
            code == 403 && detail.contains("rate limit", true) -> "Terkena batas kecepatan GitHub. Tunggu beberapa menit lalu coba lagi."
            code == 403 -> "Token tidak punya izin tulis. Classic PAT butuh scope repo; fine-grained butuh Contents: Read and write. Branch yang diproteksi juga menolak penghapusan langsung."
            code == 404 -> "Repo, branch, atau file tidak ditemukan. Untuk repo private, pastikan token punya akses ke repo tersebut."
            code == 409 -> "Terjadi konflik commit (branch berubah saat proses). Coba lagi."
            code == 422 -> "File berubah di server (SHA tidak cocok) atau data tidak valid. Muat ulang daftar file lalu coba lagi."
            else -> ""
        }
        return if (hint.isBlank()) raw else "$hint\n\n(HTTP $code: $detail)"
    }

    internal fun ghShowError(title: String, e: Throwable) {
        if (isFinishing) return
        AlertDialog.Builder(this).setTitle(title).setMessage(ghDeleteError(e)).setPositiveButton("OK", null)
            .show().also { ghStyleRoundedDialog(it) }
    }

    internal fun ghRunDelete(path: String, isDir: Boolean, token: String, onDone: () -> Unit) {
        AlertDialog.Builder(this).setTitle("Menghapus…").setMessage("Menyiapkan commit penghapusan.").setCancelable(false).create().also { dialog ->
            dialog.show()
            toolThread {
                val result = runCatching {
                    if (isDir) ghDeleteTreePrefix(path, token) else ghDeleteSingleFile(path, token)
                }
                runOnUiThread {
                    dialog.dismiss()
                    result.onSuccess { toast("$path berhasil dihapus"); onDone() }
                        .onFailure { ghShowError("Gagal menghapus", it) }
                }
            }
        }
    }

    internal fun startGithubDeleteService(token: String, prefix: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9107)
        }
        val intent = Intent(this, GithubDeleteService::class.java).apply {
            putExtra(GithubDeleteService.EXTRA_TOKEN, token)
            putExtra(GithubDeleteService.EXTRA_OWNER, ghUserValue)
            putExtra(GithubDeleteService.EXTRA_REPO, ghRepoValue)
            putExtra(GithubDeleteService.EXTRA_BRANCH, ghBranch)
            putExtra(GithubDeleteService.EXTRA_PREFIX, prefix)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    internal fun ghRunDeleteAll(token: String, prefix: String, onDone: () -> Unit) {
        // Penghapusan dijalankan oleh ForegroundService supaya tetap hidup ketika
        // dialog ditutup, Activity dipindah ke background, atau pengguna keluar
        // dari halaman. Progress juga tetap terlihat di notification shade.
        startGithubDeleteService(token, prefix)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
        }
        val status = TextView(this).apply {
            text = "Menyiapkan penghapusan…"
            textSize = 17f
            setTextColor(ghInk)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val percent = TextView(this).apply {
            text = "0%"
            textSize = 14f
            setTextColor(ghMuted)
            gravity = Gravity.END
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
        }
        val detail = TextView(this).apply {
            text = "Proses berjalan di latar belakang…"
            textSize = 12f
            setTextColor(ghMuted)
            setPadding(0, dp(8), 0, 0)
        }
        val tip = TextView(this).apply {
            text = "Sembunyikan aman. Proses tetap berjalan dan bisa dipantau dari notifikasi."
            textSize = 12f
            setTextColor(ghMuted)
            setPadding(0, dp(12), 0, 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(percent, LinearLayout.LayoutParams(dp(52), -2))
        box.addView(row)
        box.addView(progress, LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(12) })
        box.addView(detail)
        box.addView(tip)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Menghapus semua…")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Sembunyikan") { d, _ -> d.dismiss() }
            .setPositiveButton("Tutup") { d, _ -> d.dismiss() }
            .create()
        dialog.show()
        ghStyleRoundedDialog(dialog)

        val handler = Handler(Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                val prefsState = getSharedPreferences("gh_delete_state", MODE_PRIVATE)
                val activeId = prefsState.getString("job_id", "")
                val myId = intentJobId(token, prefix)
                if (activeId == myId) {
                    val pct = prefsState.getInt("progress", 0).coerceIn(0, 100)
                    progress.progress = pct
                    percent.text = "$pct%"
                    status.text = prefsState.getString("title", "Menghapus…")
                    detail.text = prefsState.getString("detail", "Proses berjalan di latar belakang…")
                    if (prefsState.getBoolean("finished", false)) {
                        dialog.dismiss()
                        val ok = prefsState.getBoolean("success", false)
                        toast(prefsState.getString("result", if (ok) "Semua file berhasil dihapus" else "Penghapusan gagal") ?: "Selesai")
                        onDone()
                        return
                    }
                }
                handler.postDelayed(this, 350L)
            }
        }
        handler.post(poll)
    }

    internal fun intentJobId(token: String, prefix: String): String =
        "${ghUserValue}/${ghRepoValue}/${ghBranch}/${prefix.trim('/')}"

    internal fun ghDeleteSingleFile(path: String, token: String) {
        val clean = path.trim('/')
        val base = "https://api.github.com/repos/${Uri.encode(ghUserValue)}/${Uri.encode(ghRepoValue)}"
        val url = "$base/contents/${encodePath(clean)}"
        val info = JSONObject(githubRequestRaw("$url?ref=${Uri.encode(ghBranch)}", token))
        val sha = info.optString("sha")
        require(sha.isNotBlank()) { "SHA file tidak ditemukan: $clean" }
        val body = JSONObject()
            .put("message", "Delete $clean via GITLS")
            .put("sha", sha)
            .put("branch", ghBranch)
        githubRequestRetry("DELETE", url, body, ghHeaders(token))
    }

    internal fun ghDeleteTreePrefix(
        prefix: String,
        token: String,
        onProgress: (Int, String, String) -> Unit = { _, _, _ -> }
    ) {
        try {
            ghDeleteTreePrefixGitData(prefix, token, onProgress)
        } catch (e: IOException) {
            if (e.message.orEmpty().contains("GitHub HTTP 404")) {
                onProgress(8, "Menggunakan mode kompatibilitas", "Git Data API tidak tersedia. Beralih ke penghapusan per file…")
                ghDeleteTreePrefixViaContents(prefix, token, onProgress)
            } else {
                throw e
            }
        }
    }

    internal fun ghDeleteTreePrefixGitData(
        prefix: String,
        token: String,
        onProgress: (Int, String, String) -> Unit = { _, _, _ -> }
    ) {
        onProgress(5, "Menghubungkan ke GitHub", "Membaca branch ${ghBranch}…")
        val base = "https://api.github.com/repos/${Uri.encode(ghUserValue)}/${Uri.encode(ghRepoValue)}"
        val ref = githubRequestRetry("GET", "$base/git/ref/heads/${encodePath(ghBranch)}", null, ghHeaders(token))
        val parentSha = ref.optJSONObject("object")?.optString("sha").orEmpty()
        require(parentSha.isNotBlank()) { "Branch $ghBranch tidak ditemukan" }
        onProgress(15, "Membaca repository", "Mendapatkan commit terbaru…")
        val commit = githubRequestRetry("GET", "$base/git/commits/$parentSha", null, ghHeaders(token))
        val baseTree = commit.optJSONObject("tree")?.optString("sha").orEmpty()
        require(baseTree.isNotBlank()) { "Tree repository tidak ditemukan" }
        // Endpoint /git/trees membutuhkan SHA tree, bukan SHA commit.
        // Sebelumnya parentSha dipakai di sini sehingga penghapusan massal dapat berakhir HTTP 404.
        onProgress(25, "Membaca daftar file", "Menghitung semua file yang akan dihapus…")
        val treeJson = JSONObject(githubRequestRaw("$base/git/trees/${Uri.encode(baseTree)}?recursive=1", token))
        // Repo sangat besar membuat listing rekursif terpotong; menghapus dari listing parsial
        // akan meninggalkan file. Lempar 404 sintetis agar ghDeleteTreePrefix memakai jalur Contents API.
        if (treeJson.optBoolean("truncated", false)) throw IOException("GitHub HTTP 404: daftar file terlalu besar (terpotong), memakai jalur Contents API")
        val tree = treeJson.optJSONArray("tree") ?: JSONArray()
        val normalized = prefix.trim('/').let { if (it.isBlank()) "" else "$it/" }
        val deletes = JSONArray()
        for (i in 0 until tree.length()) {
            val obj = tree.getJSONObject(i)
            val path = obj.optString("path")
            if (obj.optString("type") == "blob" && (normalized.isBlank() || path.startsWith(normalized))) {
                deletes.put(JSONObject()
                    .put("path", path)
                    .put("mode", obj.optString("mode", "100644"))
                    .put("type", "blob")
                    .put("sha", JSONObject.NULL))
            }
        }
        require(deletes.length() > 0) { "Tidak ada file untuk dihapus" }
        onProgress(42, "Menyiapkan penghapusan", "${deletes.length()} file ditemukan. Membuat perubahan…")
        val newTree = githubRequestRetry("POST", "$base/git/trees", JSONObject().put("base_tree", baseTree).put("tree", deletes), ghHeaders(token)).optString("sha")
        require(newTree.isNotBlank()) { "Gagal membuat tree penghapusan" }
        onProgress(65, "Membuat commit", "Menyimpan penghapusan ke GitHub…")
        val newCommit = githubRequestRetry("POST", "$base/git/commits", JSONObject().put("message", if (prefix.isBlank()) "Delete all files via GITLS" else "Delete $prefix via GITLS").put("tree", newTree).put("parents", JSONArray().put(parentSha)), ghHeaders(token)).optString("sha")
        require(newCommit.isNotBlank()) { "Gagal membuat commit penghapusan" }
        onProgress(82, "Memperbarui branch", "Menerapkan commit ke ${ghBranch}…")
        githubRequestRetry("PATCH", "$base/git/refs/heads/${encodePath(ghBranch)}", JSONObject().put("sha", newCommit).put("force", false), ghHeaders(token))
        onProgress(94, "Memverifikasi", "Memastikan commit sudah masuk ke branch…")
        val verify = githubRequestRetry("GET", "$base/git/ref/heads/${encodePath(ghBranch)}", null, ghHeaders(token))
        require(verify.optJSONObject("object")?.optString("sha") == newCommit) { "Verifikasi commit penghapusan gagal" }
        onProgress(100, "Selesai", "Semua file berhasil dihapus dan commit sudah diverifikasi.")
    }

    /**
     * Fallback untuk token/repository yang tidak menerima Git Data API.
     * Contents API dihapus satu per satu menggunakan SHA yang didapat dari
     * listing awal. Folder GitHub tidak mempunyai object tersendiri, sehingga
     * folder akan hilang otomatis setelah seluruh blob di dalamnya terhapus.
     */
    internal fun ghDeleteTreePrefixViaContents(
        prefix: String,
        token: String,
        onProgress: (Int, String, String) -> Unit = { _, _, _ -> }
    ) {
        onProgress(10, "Mencari file", "Membaca isi repository secara bertahap…")
        val files = mutableListOf<Pair<String, String>>()
        ghCollectGithubContentFiles(prefix.trim('/'), token, files)

        require(files.isNotEmpty()) { "Tidak ada file untuk dihapus" }
        onProgress(18, "File ditemukan", "${files.size} file siap dihapus.")

        val base = "https://api.github.com/repos/${Uri.encode(ghUserValue)}/${Uri.encode(ghRepoValue)}"
        files.forEachIndexed { index, pair ->
            val (path, sha) = pair
            if (ghCancelled) throw GhCancelException()
            val url = "$base/contents/${encodePath(path)}"
            val body = JSONObject()
                .put("message", "Delete $path via GITLS")
                .put("sha", sha)
                .put("branch", ghBranch)

            githubRequestRetry("DELETE", url, body, ghHeaders(token))
            val pct = 18 + (((index + 1).toDouble() / files.size.toDouble()) * 78.0).roundToInt()
            onProgress(pct, "Menghapus file", "${index + 1} dari ${files.size}: $path")
            Thread.sleep(250L) // hindari secondary rate limit GitHub pada penghapusan beruntun
        }
        onProgress(100, "Selesai", "${files.size} file berhasil dihapus.")
    }

    /**
     * Mengumpulkan semua blob/symlink di sebuah folder secara rekursif.
     */
    internal fun ghCollectGithubContentFiles(
        prefix: String,
        token: String,
        out: MutableList<Pair<String, String>>
    ) {
        val base = "https://api.github.com/repos/${Uri.encode(ghUserValue)}/${Uri.encode(ghRepoValue)}"
        val endpoint = "$base/contents/${if (prefix.isBlank()) "" else encodePath(prefix)}?ref=${Uri.encode(ghBranch)}"
        val json = githubRequestRaw(endpoint, token)

        val root = json.trimStart()
        if (!root.startsWith("[")) {
            val item = JSONObject(json)
            val itemPath = item.optString("path").ifBlank { prefix }
            val type = item.optString("type")
            val sha = item.optString("sha")
            if (type == "dir") {
                ghCollectGithubContentFiles(itemPath, token, out)
            } else if (sha.isNotBlank()) {
                out += itemPath to sha
            }
            return
        }

        val items = JSONArray(json)
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val itemPath = item.optString("path")
            if (itemPath.isBlank()) continue

            when (item.optString("type")) {
                "dir" -> ghCollectGithubContentFiles(itemPath, token, out)
                else -> {
                    val sha = item.optString("sha")
                    if (sha.isNotBlank()) out += itemPath to sha
                }
            }
        }
    }

    internal fun githubRequestRaw(url: String, token: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "GITLS-Android")
        }
        try {
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(response).optString("message") }.getOrDefault(response.take(240))
                throw IOException("GitHub HTTP $code: ${message.ifBlank { "Request gagal" }}")
            }
            return response
        } finally { connection.disconnect() }
    }

    internal fun ghSaveSettings(owner: String, repository: String, pat: String, commitMessage: String) {
        val editor = prefs.edit()
            .putString("gh_user", owner).putString("gh_repo", repository).putString("gh_branch", ghBranch)
            .putString("gh_commit", commitMessage)
            .putBoolean("gh_save_token", ghSaveToken).putBoolean("gh_private", ghPrivateRepo)
        if (ghSaveToken) {
            val enc = GithubTokenVault.encrypt(pat)
            if (enc != null) editor.putString("gh_token_enc", enc) else editor.remove("gh_token_enc")
        } else {
            editor.remove("gh_token_enc")
        }
        editor.apply()
    }

    // ----- pemilih sumber: ZIP / Folder -----
    internal fun ghSourcePicker(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = ghRound(ghSoft, 16)
        }
        val zipPanel = ghZipPanel()
        val folderPanel = ghFolderPanel()
        val tabViews = ArrayList<LinearLayout>()
        fun paintTabs() {
            tabViews.forEachIndexed { index, tab ->
                val selected = index == ghSource
                tab.background = if (selected) ghRound(ghCard, 12, ghStroke) else null
                for (i in 0 until tab.childCount) {
                    val child = tab.getChildAt(i)
                    if (child is MdiIconView) child.setTextColor(if (selected) ghInk else ghMuted)
                    if (child is TextView && child !is MdiIconView) child.setTextColor(if (selected) ghInk else ghMuted)
                }
            }
            zipPanel.visibility = if (ghSource == 0) View.VISIBLE else View.GONE
            folderPanel.visibility = if (ghSource == 1) View.VISIBLE else View.GONE
        }
        fun tab(label: String, iconName: String, index: Int): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(ghIcon(iconName, 18f, ghMuted), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(8) })
            addView(ghText(label, 14f, ghMuted, true))
            isClickable = true
            setOnClickListener {
                if (ghSource != index) {
                    it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    ghSource = index
                    paintTabs()
                    val panel = if (index == 0) zipPanel else folderPanel
                    panel.alpha = 0f
                    panel.animate().alpha(1f).setDuration(200).start()
                }
            }
        }
        tabViews.add(tab("File ZIP", "folder-zip-outline", 0))
        tabViews.add(tab("Folder", "folder-open-outline", 1))
        tabViews.forEach { tabs.addView(it, LinearLayout.LayoutParams(0, dp(42), 1f)) }
        box.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        box.addView(zipPanel)
        box.addView(folderPanel)
        paintTabs()
        return box
    }

    internal fun ghSourceCard(iconName: String, titleView: TextView, hintText: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = ghRound(ghCard, 18, ghStroke)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val badge = ghIcon(iconName, 22f, ghInk).apply { background = ghRound(ghSoft, 14) }
        top.addView(badge, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(14) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(titleView.apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        texts.addView(ghText(hintText, 12f, ghMuted).apply { setPadding(0, dp(4), 0, 0) })
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(top)
        return card
    }

    internal fun ghZipPanel(): LinearLayout {
        val restoredName = prefs.getString("gh_selected_zip_name", null)
        val zipTitleText = restoredName?.takeIf { it.isNotBlank() }
            ?: githubZipUri?.let { queryName(it) }
            ?: "Belum ada ZIP dipilih"
        val zipTitle = ghText(zipTitleText, 14.5f, ghInk, true)
        githubZipLabel = zipTitle
        // Jika URI masih valid, tampilkan ukuran tanpa menunggu pengguna memilih ulang.
        githubZipUri?.let { uri ->
            val size = runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
            if (size > 0 && zipTitleText != "Belum ada ZIP dipilih") {
                zipTitle.text = "$zipTitleText • ${ghFormatBytes(size)}"
            }
        }
        val card = ghSourceCard("folder-zip-outline", zipTitle, "Preview isi, atur root, dan kecualikan file.")
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        actions.addView(ghSmallButton("Pilih ZIP", "folder-open-outline") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                addCategory(Intent.CATEGORY_OPENABLE)
            }, GITHUB_ZIP_PICK_REQUEST)
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(8) })
        actions.addView(ghSmallButton("Kelola isi", "file-tree-outline") {
            val uri = githubZipUri
            if (uri == null) toast("Pilih ZIP terlebih dahulu") else showGithubZipPreview(uri)
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        card.addView(actions)
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(card) }
    }

    internal fun ghFolderPanel(): LinearLayout {
        val folderTitle = ghText("Belum ada folder dipilih", 14.5f, ghInk, true)
        githubFolderLabel = folderTitle
        val card = ghSourceCard("folder-outline", folderTitle, "Semua file dan subfolder ikut terkirim.")
        val previewText = ghText("Isi folder akan tampil di sini.", 11.5f, ghMuted).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = ghRound(ghSoft, 12)
            setLineSpacing(0f, 1.2f)
            maxLines = 9
        }
        githubFolderPreview = previewText
        card.addView(previewText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        actions.addView(ghSmallButton("Pilih folder", "folder-open-outline") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, GITHUB_FOLDER_PICK_REQUEST)
        }, LinearLayout.LayoutParams(-1, dp(40)))
        card.addView(actions)
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(card) }
    }

    internal fun validateGithubInputs(owner: String, repository: String, pat: String, branch: String): Boolean {
        if (!owner.matches(Regex("[A-Za-z0-9_.-]+"))) { toast("Username GitHub tidak valid"); return false }
        if (!repository.matches(Regex("[A-Za-z0-9_.-]+"))) { toast("Nama repository tidak valid"); return false }
        if (pat.isBlank()) { toast("Masukkan GitHub token"); return false }
        if (!branch.matches(Regex("[A-Za-z0-9._/-]+"))) { toast("Nama branch tidak valid"); return false }
        return true
    }

    // =====================================================================
    // Layar 2: Proses Upload (animasi)
    // =====================================================================
    internal fun ghStepTitle(index: Int, branch: String): String = when (index) {
        0 -> "Menghubungkan ke GitHub"
        1 -> "Membuat repository (jika belum ada)"
        2 -> "Mengunggah file"
        3 -> "Push ke branch $branch"
        else -> "Verifikasi hasil upload"
    }

    internal fun ghProgressScreen(kind: String, owner: String, repo: String, branch: String): LinearLayout {
        ghStepViews.clear(); ghStepTitles.clear(); ghStepDetails.clear()
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), dp(8), dp(2), dp(24)) }

        val ringBox = FrameLayout(this)
        val ring = ProgressRingView(this).apply {
            ringColor = ghInk
            trackColor = ghSoft
            indeterminate = true
        }
        ghRing = ring
        ringBox.addView(ring, FrameLayout.LayoutParams(-1, -1))
        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        center.addView(ghLogo(54, ghInk), LinearLayout.LayoutParams(dp(54), dp(54)).apply { bottomMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL })
        ghPercentText = ghText("$ghPct%", 28f, ghInk, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }
        center.addView(ghPercentText, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        ringBox.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        screen.addView(ringBox, LinearLayout.LayoutParams(dp(210), dp(210)).apply { topMargin = dp(6); bottomMargin = dp(14) })

        val target = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = ghRound(ghSoft, 14)
        }
        target.addView(ghIcon("source-repository", 15f, ghMuted), LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(6) })
        target.addView(ghText("$owner/$repo", 12.5f, ghInk, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        target.addView(ghIcon("source-branch", 15f, ghMuted), LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(12); rightMargin = dp(4) })
        target.addView(ghText(branch, 12.5f, ghMuted))
        screen.addView(target, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(22) })

        val steps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, dp(6), 0) }
        for (i in 0..4) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(9), 0, dp(9)) }
            val state = StepStateView(this).apply {
                inkColor = ghInk; onInkColor = ghOnInk; mutedColor = ghStroke; dangerColor = ghDanger
                setState(StepStateView.PENDING, false)
            }
            ghStepViews.add(state)
            row.addView(state, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(14); topMargin = dp(1) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val stepTitle = ghText(ghStepTitle(i, branch), 14.5f, ghMuted)
            val stepDetail = ghText("", 11.5f, ghMuted).apply { visibility = View.GONE; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, dp(4), 0, 0) }
            ghStepTitles.add(stepTitle); ghStepDetails.add(stepDetail)
            col.addView(stepTitle); col.addView(stepDetail)
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            steps.addView(row)
        }
        screen.addView(steps, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        val note = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = ghRound(ghSoft, 16)
        }
        val spinner = StepStateView(this).apply {
            inkColor = ghInk; onInkColor = ghOnInk; mutedColor = ghStroke; dangerColor = ghDanger
            setState(StepStateView.ACTIVE, false)
        }
        note.addView(spinner, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(12) })
        val noteTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        noteTexts.addView(ghText("Mohon tunggu, proses ini mungkin memakan waktu…", 12.5f, ghMuted).apply { setLineSpacing(0f, 1.1f) })
        ghElapsedText = ghText("Berjalan 00:00", 11.5f, ghMuted).apply { setPadding(0, dp(4), 0, 0) }
        noteTexts.addView(ghElapsedText)
        note.addView(noteTexts, LinearLayout.LayoutParams(0, -2, 1f))
        ghNoteBox = note
        screen.addView(note, LinearLayout.LayoutParams(-1, -2))

        ghErrorHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(ghErrorHost, LinearLayout.LayoutParams(-1, -2))

        val cancel = ghOutlineButton("Batal", "close-circle") { ghConfirmCancel() }
        (cancel.layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
        ghCancelBtn = cancel
        ghCancelLabel = cancel.getChildAt(1) as? TextView
        screen.addView(cancel)
        return screen
    }

    internal fun ghConfirmCancel() {
        if (!ghRunning || ghCancelled) return
        AlertDialog.Builder(this)
            .setTitle("Batalkan upload?")
            .setMessage("Proses upload ke GitHub akan dihentikan. File yang belum ter-push tidak akan masuk ke repository.")
            .setNegativeButton("Lanjutkan", null)
            .setPositiveButton("Batalkan") { _, _ ->
                if (!ghRunning) return@setPositiveButton
                ghCancelled = true
                ghCancelLabel?.text = "Membatalkan…"
                ghCancelBtn?.alpha = 0.5f
                ghCancelBtn?.isEnabled = false
                ghCancelBtn?.isClickable = false
            }
            .show()
    }

    /** Menggambar ulang layar proses dari status terakhir (dipakai saat masuk kembali ke halaman). */
    internal fun ghReplayProgress() {
        ghCurrentStep = 0
        for (i in 0..4) ghStepLast[i]?.let { ghOnProgress(it, store = false) }
        if (ghCancelled) {
            ghCancelLabel?.text = "Membatalkan…"
            ghCancelBtn?.alpha = 0.5f
            ghCancelBtn?.isEnabled = false
            ghCancelBtn?.isClickable = false
        }
    }

    internal fun ghNotifyDone(success: Boolean, text: String) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "github_upload"
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel(channelId, "Upload GitHub", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Status selesai atau gagal proses upload ke GitHub"
                })
            }
            val openIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("open_github_upload", true)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val open = PendingIntent.getActivity(
                this, 99002, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            )
            val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, channelId) else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
            builder.setSmallIcon(R.drawable.ic_archive)
                .setContentTitle(if (success) "Upload GitHub selesai" else "Upload GitHub gagal")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(open)
            manager.notify(99002, builder.build())
        }
    }

    internal fun ghStartUpload(kind: String, owner: String, repo: String, branch: String, task: ((GhProgress) -> Unit) -> GhResult) {
        if (ghRunning) { toast("Upload sedang berjalan"); return }
        ghRetry = { ghStartUpload(kind, owner, repo, branch, task) }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            runCatching { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3001) }
        }
        ghCancelled = false
        ghPendingResult = null
        ghPendingError = null
        ghFinishedSec = -1
        ghPct = 0
        for (i in 0..4) ghStepLast[i] = null
        ghShow(ghProgressScreen(kind, owner, repo, branch), "Proses Upload")
        ghRunning = true
        ghCurrentStep = 0
        ghFileTotal = 0
        ghStartedAt = SystemClock.elapsedRealtime()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ghHandler.removeCallbacks(ghTicker)
        ghHandler.postDelayed(ghTicker, 1000L)
        ghOnProgress(GhProgress(0, "Menyiapkan $kind…"))
        toolThread {
            val result = runCatching {
                task { p ->
                    if (ghCancelled) throw GhCancelException()
                    runOnUiThread { ghOnProgress(p) }
                }
            }
            runOnUiThread {
                ghRunning = false
                ghHandler.removeCallbacks(ghTicker)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                ghFinishedSec = ((SystemClock.elapsedRealtime() - ghStartedAt) / 1000L).toInt()
                val visible = ghStage?.isAttachedToWindow == true
                val cancelled = ghCancelled || result.exceptionOrNull() is GhCancelException
                ghCancelled = false
                when {
                    cancelled -> {
                        ghPendingResult = null; ghPendingError = null
                        if (visible) { toast("Upload dibatalkan"); ghShow(ghSettingsScreen(), "Pengaturan GitHub") }
                    }
                    result.isSuccess -> {
                        val r = result.getOrThrow()
                        if (!visible || !ghAppForeground) ghNotifyDone(true, "${r.owner}/${r.repo} • ${r.files} file berhasil diunggah ke ${r.branch}")
                        if (visible) ghShowResult(r) else ghPendingResult = r
                    }
                    else -> {
                        val e = result.exceptionOrNull() ?: IOException("Upload gagal")
                        if (!visible || !ghAppForeground) ghNotifyDone(false, ghFriendlyError(e).take(120))
                        if (visible) ghShowFailure(e) else ghPendingError = e
                    }
                }
            }
        }
    }

    internal fun ghOnProgress(p: GhProgress, store: Boolean = true) {
        if (store && p.step >= ghCurrentStep) {
            ghStepLast[p.step.coerceIn(0, 4)] = p
            ghPct = when (p.step) {
                0 -> 4
                1 -> 12
                2 -> if (p.total > 0) 15 + (70 * (p.current - 1).coerceAtLeast(0)) / p.total else 15
                3 -> 88
                else -> 96
            }
            ghCurrentStep = maxOf(ghCurrentStep, p.step)
        }
        if (ghStage?.isAttachedToWindow != true || ghStepViews.size < 5) return
        if (p.step < ghCurrentStep && store) return
        ghCurrentStep = p.step
        // Setelah tahap "Push", pembatalan tidak lagi aman/berguna.
        if (p.step >= 3 && !ghCancelled) {
            ghCancelBtn?.alpha = 0.4f
            ghCancelBtn?.isEnabled = false
            ghCancelBtn?.isClickable = false
        }
        for (i in 0..4) {
            val state = when {
                i < p.step -> StepStateView.DONE
                i == p.step -> StepStateView.ACTIVE
                else -> StepStateView.PENDING
            }
            ghStepViews[i].setState(state)
            ghStepTitles[i].setTextColor(if (i <= p.step) ghInk else ghMuted)
            if (i == p.step) ghStepTitles[i].setTypeface(null, android.graphics.Typeface.BOLD)
            else ghStepTitles[i].setTypeface(null, android.graphics.Typeface.NORMAL)
        }
        if (p.step == 2 && p.total > 0) {
            ghFileTotal = p.total
            ghStepTitles[2].text = "Mengunggah file (${p.current}/${p.total})"
        }
        if (p.step > 2 && ghFileTotal > 0) {
            ghStepTitles[2].text = "Mengunggah file ($ghFileTotal/$ghFileTotal)"
            ghStepDetails[2].text = "$ghFileTotal file terunggah"
            ghStepDetails[2].visibility = View.VISIBLE
        }
        if (p.detail.isNotBlank() && !(p.step == 2 && p.total == 0)) {
            ghStepDetails[p.step].text = p.detail
            ghStepDetails[p.step].visibility = View.VISIBLE
        }
        val pct = when (p.step) {
            0 -> 4
            1 -> 12
            2 -> if (p.total > 0) 15 + (70 * (p.current - 1).coerceAtLeast(0)) / p.total else 15
            3 -> 88
            else -> 96
        }
        ghRing?.let { it.indeterminate = false; it.setProgress(pct.toFloat()) }
        ghPercentText?.text = "$pct%"
    }

    internal fun ghShowFailure(error: Throwable) {
        if (ghStage?.isAttachedToWindow != true) return
        val failedStep = ghCurrentStep.coerceIn(0, 4)
        if (ghStepViews.size == 5) {
            ghStepViews[failedStep].setState(StepStateView.FAILED)
            ghStepTitles[failedStep].setTextColor(ghDanger)
        }
        ghRing?.let { it.indeterminate = false; it.ringColor = ghDanger }
        ghNoteBox?.visibility = View.GONE
        val host = ghErrorHost ?: return
        host.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ghRound(ghCard, 16, ghDanger)
        }
        card.addView(ghText("Upload gagal", 15f, ghDanger, true))
        card.addView(ghText(ghFriendlyError(error), 12.5f, ghMuted).apply { setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1.15f) })
        host.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        host.addView(ghPrimaryButton("Coba lagi") { ghRetry?.invoke() })
        host.addView(ghOutlineButton("Ubah pengaturan", "cog-outline") { ghShow(ghSettingsScreen(), "Pengaturan GitHub") })
        host.alpha = 0f
        host.animate().alpha(1f).setDuration(240).start()
        toast("Upload GitHub gagal")
    }

    internal fun ghFriendlyError(error: Throwable): String {
        val raw = error.message ?: error.javaClass.simpleName
        return when {
            raw.contains("HTTP 401") -> "Token ditolak GitHub. Periksa token atau masa berlakunya. ($raw)"
            raw.contains("HTTP 403") -> "Akses ditolak. Pastikan token punya izin Contents read/write untuk repository ini. ($raw)"
            raw.contains("HTTP 404") -> "Repository atau branch tidak ditemukan, atau token tidak punya akses. ($raw)"
            raw.contains("HTTP 422") -> "GitHub menolak data yang dikirim. ($raw)"
            error is java.net.UnknownHostException || error is java.net.SocketTimeoutException -> "Tidak bisa menjangkau GitHub. Periksa koneksi internet lalu coba lagi."
            else -> raw
        }
    }

    // =====================================================================
    // Layar 3: Upload Selesai
    // =====================================================================
    internal fun ghShowResult(result: GhResult) {
        if (ghStage?.isAttachedToWindow != true) return
        ghLastResult = result
        val elapsed = if (ghFinishedSec >= 0) ghFinishedSec else ((SystemClock.elapsedRealtime() - ghStartedAt) / 1000L).toInt()
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), dp(14), dp(2), dp(24)) }

        val badge = SuccessBadgeView(this).apply { inkColor = ghInk; onInkColor = ghOnInk }
        screen.addView(badge, LinearLayout.LayoutParams(dp(92), dp(92)).apply { bottomMargin = dp(12) })

        // Success title: always centered directly beneath the check badge.
        screen.addView(ghText("Upload Berhasil!", 24f, ghInk, true).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        val subtitle = if (result.createdRepo) "Repository baru dibuat (${if (result.createdPrivate) "private" else "public"}) dan project berhasil diunggah."
        else "Project berhasil diunggah ke GitHub"
        screen.addView(ghText(subtitle, 13f, ghMuted).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(dp(20), dp(6), dp(20), 0)
            setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = ghRound(ghCard, 20, ghStroke)
        }
        fun infoRow(iconName: String, labelText: String, valueText: String, trailing: String? = null, onClick: (() -> Unit)? = null, last: Boolean = false) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(13), 0, dp(13))
            }
            row.addView(ghIcon(iconName, 22f, ghInk), LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(14) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(ghText(labelText, 11.5f, ghMuted))
            col.addView(ghText(valueText, 15f, ghInk, true).apply { setPadding(0, dp(3), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            if (trailing != null) row.addView(ghIcon(trailing, 19f, ghMuted), LinearLayout.LayoutParams(dp(26), dp(26)))
            if (onClick != null) ghPressable(row, onClick)
            card.addView(row)
            if (!last) card.addView(View(this).apply { setBackgroundColor(ghStroke) }, LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(44) })
        }
        infoRow("source-repository", "Repository", "${result.owner}/${result.repo}", "open-in-new", { ghOpenUrl(result.url) })
        infoRow("source-branch", "Branch", result.branch)
        infoRow("file-tree-outline", "Total File", "${result.files} file")
        infoRow("harddisk", "Ukuran", ghFormatBytes(result.bytes))
        infoRow("source-commit", "Commit", result.commitSha.take(7), "content-copy", {
            ghCopy("Commit SHA", result.commitSha); toast("SHA commit disalin")
        })
        infoRow("timer-outline", "Durasi", if (elapsed >= 60) "${elapsed / 60} mnt ${elapsed % 60} dtk" else "$elapsed dtk", last = true)
        screen.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(18) })

        screen.addView(ghPrimaryButton("Lihat di GitHub") { ghOpenUrl(result.url + "/tree/" + result.branch) })
        screen.addView(ghOutlineButton("Upload Lagi", "upload-network") { ghShow(ghSettingsScreen(), "Pengaturan GitHub") })
        screen.addView(ghOutlineButton("Salin tautan repository", "link-variant") { ghCopy("Repository", result.url); toast("Tautan disalin") })

        ghShow(screen, "Upload Selesai")
        badge.postDelayed({ badge.play() }, 120L)
        screen.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        toast("Upload GitHub selesai")
    }

    internal fun ghOpenUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("Tidak ada aplikasi untuk membuka tautan") }
    }

    internal fun ghCopy(labelText: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(labelText, value))
    }

    // =====================================================================
    // Mesin upload: satu jalur untuk ZIP dan folder
    // =====================================================================
    internal fun ghHeaders(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
        "User-Agent" to "GITLS-Android"
    )

    /** Request dengan percobaan ulang untuk gangguan jaringan (bukan untuk error 4xx). */
    internal fun githubRequestRetry(method: String, url: String, body: JSONObject?, headers: Map<String, String>, attempts: Int = 3): JSONObject {
        var last: IOException? = null
        for (i in 1..attempts) {
            if (ghCancelled) throw GhCancelException()
            try {
                return githubRequest(method, url, body, headers)
            } catch (e: IOException) {
                val message = e.message.orEmpty()
                // Secondary rate limit (403/429) dan konflik commit beruntun (409 pada DELETE/PUT
                // Contents API) bersifat sementara, jadi dicoba ulang dengan jeda bertahap.
                val transient4xx = message.startsWith("GitHub HTTP 429") ||
                    (message.startsWith("GitHub HTTP 403") && message.contains("rate limit", ignoreCase = true)) ||
                    (message.startsWith("GitHub HTTP 409") && (method == "DELETE" || method == "PUT"))
                if (message.startsWith("GitHub HTTP 4") && !transient4xx) throw e
                last = e
                if (i < attempts) Thread.sleep(if (transient4xx) 4000L * i else 700L * i)
            }
        }
        throw last ?: IOException("Request gagal")
    }

    internal fun pushFilesToGitHub(
        files: List<Pair<String, File>>, owner: String, repo: String, token: String, branch: String,
        commitMessage: String, createPrivate: Boolean, progress: (GhProgress) -> Unit
    ): GhResult {
        require(files.isNotEmpty()) { "Tidak ada file yang dapat di-upload" }
        require(files.size <= 3000) { "Maksimal 3000 file per upload" }
        val base = "https://api.github.com/repos/${Uri.encode(owner)}/${Uri.encode(repo)}"
        val headers = ghHeaders(token)

        // 1. hubungkan & periksa token
        progress(GhProgress(0, "Memeriksa token…"))
        val me = try {
            githubRequestRetry("GET", "https://api.github.com/user", null, headers)
        } catch (e: IOException) {
            if (e.message.orEmpty().contains("HTTP 401")) throw e else JSONObject()
        }
        val login = me.optString("login")
        progress(GhProgress(0, if (login.isNotBlank()) "Terhubung sebagai $login" else "Terhubung"))

        // 2. repository
        progress(GhProgress(1, "Memeriksa $owner/$repo…"))
        val repoInfo: JSONObject? = try {
            githubRequestRetry("GET", base, null, headers)
        } catch (e: IOException) {
            if (e.message.orEmpty().contains("HTTP 404")) null else throw e
        }
        var created = false
        var htmlUrl = repoInfo?.optString("html_url").orEmpty()
        if (repoInfo == null) {
            require(login.equals(owner, ignoreCase = true)) {
                "Repository $owner/$repo belum ada, dan token milik ${login.ifBlank { "akun lain" }} sehingga tidak bisa membuatnya otomatis."
            }
            progress(GhProgress(1, "Membuat repository ${if (createPrivate) "private" else "public"}…"))
            val made = githubRequestRetry("POST", "https://api.github.com/user/repos",
                JSONObject().put("name", repo).put("private", createPrivate).put("auto_init", true)
                    .put("description", "Dibuat lewat GITLS Publisher"), headers)
            created = true
            htmlUrl = made.optString("html_url")
            Thread.sleep(800L)
        } else {
            val empty = try {
                githubRequest("GET", "$base/git/trees/HEAD", null, headers); false
            } catch (e: IOException) {
                e.message.orEmpty().contains("HTTP 409")
            }
            if (empty) {
                progress(GhProgress(1, "Repository masih kosong, membuat commit awal…"))
                val readme = android.util.Base64.encodeToString("# $repo\n".toByteArray(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)
                githubRequestRetry("PUT", "$base/contents/README.md", JSONObject().put("message", "Initial commit").put("content", readme), headers)
            } else {
                progress(GhProgress(1, "Repository ditemukan"))
            }
        }
        if (htmlUrl.isBlank()) htmlUrl = "https://github.com/$owner/$repo"

        val ref = runCatching { githubRequest("GET", "$base/git/ref/heads/${encodePath(branch)}", null, headers) }.getOrNull()
        val parentSha = ref?.optJSONObject("object")?.optString("sha").orEmpty()
        var baseTree = ""
        if (parentSha.isNotBlank()) {
            val parent = githubRequestRetry("GET", "$base/git/commits/$parentSha", null, headers)
            baseTree = parent.optJSONObject("tree")?.optString("sha").orEmpty()
        }

        // 3. unggah file
        val entries = JSONArray()
        var totalBytes = 0L
        files.forEachIndexed { index, (rel, file) ->
            val size = file.length()
            require(size <= 90L * 1024L * 1024L) { "File terlalu besar untuk upload API: $rel" }
            progress(GhProgress(2, rel, index + 1, files.size))
            val blobBody = JSONObject()
                .put("content", android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP))
                .put("encoding", "base64")
            val blob = githubRequestRetry("POST", "$base/git/blobs", blobBody, headers)
            val sha = blob.optString("sha")
            require(sha.isNotBlank()) { "Gagal membuat blob untuk $rel" }
            totalBytes += size
            entries.put(JSONObject().put("path", rel).put("mode", "100644").put("type", "blob").put("sha", sha))
        }

        // 4. tree, commit, push
        progress(GhProgress(3, "Membuat Git tree…", files.size, files.size))
        val treeBody = JSONObject().put("tree", entries)
        if (baseTree.isNotBlank()) treeBody.put("base_tree", baseTree)
        val treeSha = githubRequestRetry("POST", "$base/git/trees", treeBody, headers).optString("sha")
        require(treeSha.isNotBlank()) { "Gagal membuat Git tree" }
        progress(GhProgress(3, "Membuat commit…", files.size, files.size))
        val commitBody = JSONObject().put("message", commitMessage).put("tree", treeSha)
        if (parentSha.isNotBlank()) commitBody.put("parents", JSONArray().put(parentSha))
        val newSha = githubRequestRetry("POST", "$base/git/commits", commitBody, headers).optString("sha")
        require(newSha.isNotBlank()) { "Gagal membuat commit" }
        progress(GhProgress(3, "Memperbarui branch $branch…", files.size, files.size))
        if (parentSha.isBlank()) {
            githubRequestRetry("POST", "$base/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", newSha), headers)
        } else {
            githubRequestRetry("PATCH", "$base/git/refs/heads/${encodePath(branch)}", JSONObject().put("sha", newSha).put("force", false), headers)
        }

        // 5. verifikasi
        progress(GhProgress(4, "Memeriksa commit di GitHub…", files.size, files.size))
        val check = githubRequestRetry("GET", "$base/git/ref/heads/${encodePath(branch)}", null, headers)
        val remoteSha = check.optJSONObject("object")?.optString("sha").orEmpty()
        require(remoteSha == newSha) { "Verifikasi gagal: branch $branch belum menunjuk ke commit terbaru" }
        progress(GhProgress(4, "Commit ${newSha.take(7)} terverifikasi", files.size, files.size))

        return GhResult(owner, repo, branch, files.size, totalBytes, newSha, htmlUrl.trimEnd('/'), created, createPrivate)
    }

    internal fun uploadFolderToGitHub(
        treeUri: Uri, owner: String, repo: String, token: String, branch: String,
        commitMessage: String, createPrivate: Boolean, progress: (GhProgress) -> Unit
    ): GhResult {
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri) ?: error("Folder tidak dapat dibuka")
        val workDir = File(cacheDir, "github_folder_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val localRoot = File(workDir, "project").apply { mkdirs() }
            var count = 0
            fun copyTree(dir: androidx.documentfile.provider.DocumentFile, target: File) {
                dir.listFiles().forEach { child ->
                    if (ghCancelled) throw GhCancelException()
                    val name = child.name ?: return@forEach
                    if (name == ".git" || name == "__MACOSX" || name == ".DS_Store" || name == "Thumbs.db") return@forEach
                    val out = File(target, name)
                    if (child.isDirectory) { out.mkdirs(); copyTree(child, out) }
                    else if (child.isFile) {
                        out.parentFile?.mkdirs()
                        contentResolver.openInputStream(child.uri)?.use { input -> FileOutputStream(out).use { output -> input.copyTo(output) } } ?: error("Tidak bisa membaca $name")
                        count++
                        if (count % 10 == 0) progress(GhProgress(0, "Membaca folder… $count file"))
                    }
                }
            }
            progress(GhProgress(0, "Membaca isi folder yang dipilih…"))
            copyTree(root, localRoot)
            require(count > 0) { "Folder tidak berisi file yang bisa di-upload" }
            val files = localRoot.walkTopDown().filter { it.isFile }.map { f ->
                localRoot.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/') to f
            }.filter { (rel, _) -> !rel.startsWith(".git/") && rel != ".git" && !rel.startsWith("__MACOSX/") && !rel.endsWith(".DS_Store") && !rel.endsWith("Thumbs.db") }.toList()
            return pushFilesToGitHub(files, owner, repo, token, branch, commitMessage, createPrivate, progress)
        } finally { workDir.deleteRecursively() }
    }

    internal data class GithubZipAnalysis(
        val files: List<String>,
        val dirs: List<String>,
        val suggestedRoot: String,
        val excludedDefaults: Set<String>
    )

    internal fun prepareGithubZipPreview(uri: Uri) {
        toolThread {
            val result = runCatching {
                val workDir = File(cacheDir, "github_preview_${System.currentTimeMillis()}").apply { mkdirs() }
                try {
                    val zipFile = File(workDir, "preview.zip")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(zipFile).use { output -> input.copyTo(output) }
                    } ?: error("ZIP tidak dapat dibaca")
                    val extracted = File(workDir, "src").apply { mkdirs() }
                    unzipSafeForGithub(zipFile, extracted)
                    analyzeGithubZip(extracted)
                } finally {
                    workDir.deleteRecursively()
                }
            }
            runOnUiThread {
                result.onSuccess { analysis ->
                    githubZipPreviewFiles = analysis.files
                    githubZipPreviewDirs = analysis.dirs
                    githubZipExcluded.clear()
                    githubZipExcluded.addAll(analysis.excludedDefaults)
                    githubZipRoot = analysis.suggestedRoot
                    githubZipUploadSummary()
                    githubUploadStatus?.text = "ZIP dianalisis: ${analysis.files.size} file. Root: ${if (analysis.suggestedRoot.isBlank()) "/" else analysis.suggestedRoot + "/"}"
                }.onFailure { e ->
                    githubUploadStatus?.text = "Analisis ZIP gagal: ${e.message ?: "Unknown error"}"
                }
            }
        }
    }

    internal fun analyzeGithubZip(extracted: File): GithubZipAnalysis {
        val files = extracted.walkTopDown()
            .filter { it.isFile }
            .map { extracted.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/') }
            .filter { it.isNotBlank() }
            .sorted()
            .toList()
        require(files.isNotEmpty()) { "ZIP tidak berisi file yang bisa di-upload" }

        val dirs = extracted.walkTopDown()
            .filter { it.isDirectory && it != extracted }
            .map { extracted.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/') }
            .filter { it.isNotBlank() }
            .sorted()
            .toList()

        val excluded = files.filter {
            it == ".git" || it.startsWith(".git/") ||
            it == "__MACOSX" || it.startsWith("__MACOSX/") ||
            it == ".DS_Store" || it.endsWith("/.DS_Store") ||
            it == "Thumbs.db" || it.endsWith("/Thumbs.db")
        }.toSet()

        val top = extracted.listFiles()?.toList().orEmpty()
        val topDirs = top.filter { it.isDirectory }.map { it.name }.sorted()
        val topFiles = top.filter { it.isFile }
        var suggested = ""
        if (topDirs.size == 1 && topFiles.isEmpty()) {
            val wrapper = topDirs.first()
            val wrapperDir = File(extracted, wrapper)
            val children = wrapperDir.listFiles()?.toList().orEmpty()
            val childDirs = children.filter { it.isDirectory }.map { it.name }.sorted()
            val childFiles = children.filter { it.isFile }
            suggested = if (childDirs.size == 1 && childFiles.isEmpty() && childDirs.first().equals("web", true)) {
                "$wrapper/${childDirs.first()}"
            } else wrapper
        }
        return GithubZipAnalysis(files, dirs, suggested, excluded)
    }

    internal fun githubZipUploadSummary() {
        val root = githubZipRoot.trim('/').trim()
        val count = githubZipPreviewFiles.count { path ->
            !githubZipExcluded.any { excluded -> path == excluded || path.startsWith("$excluded/") } &&
            (root.isBlank() || path == root || path.startsWith("$root/"))
        }
        githubUploadStatus?.text = "Siap: $count file akan di-upload • Root: ${if (root.isBlank()) "/" else root + "/"} • Exclude: ${githubZipExcluded.size}"
    }

    internal fun showGithubZipPreview(uri: Uri) {
        if (githubZipPreviewFiles.isEmpty()) {
            githubUploadStatus?.text = "Menganalisis ZIP..."
            prepareGithubZipPreview(uri)
            return
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 8, 28, 8)
        }
        val rootLabel = TextView(this).apply {
            text = "Repository root"
            textSize = 13f
            setTextColor(textMuted)
        }
        box.addView(rootLabel)

        val rootSpinner = Spinner(this).apply {
            background = ghRound(ghSoft, 14, ghStroke)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val roots = listOf("/ (root ZIP)") + githubZipPreviewDirs.map { "$it/" }
        val currentRoot = githubZipRoot.trim('/').let { if (it.isBlank()) "/ (root ZIP)" else "$it/" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, roots)
        rootSpinner.adapter = adapter
        rootSpinner.setSelection(maxOf(0, roots.indexOf(currentRoot)))
        rootSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                githubZipRoot = if (position == 0) "" else roots[position].trimEnd('/')
                githubZipUploadSummary()
            }
        }
        box.addView(rootSpinner)
        box.addView(subLabel("Folder pembungkus otomatis dideteksi. Pilih folder yang akan menjadi root repository. Tombol × mengecualikan file/folder dari upload.", 11f))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        githubZipPreviewFiles.forEach { path ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(5), dp(8), dp(5))
                background = ghRound(ghCard, 12, ghStroke)
            }
            val label = TextView(this).apply {
                text = if (githubZipExcluded.any { ex -> path == ex || path.startsWith("$ex/") }) "⊘ $path" else "• $path"
                textSize = 12f
                setTextColor(if (githubZipExcluded.any { ex -> path == ex || path.startsWith("$ex/") }) textMuted else textMain)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val remove = TextView(this).apply {
                text = if (githubZipExcluded.contains(path)) "✓" else "×"
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(18, 8, 12, 8)
            }
            remove.setOnClickListener {
                if (githubZipExcluded.contains(path)) githubZipExcluded.remove(path) else githubZipExcluded.add(path)
                label.text = if (githubZipExcluded.any { ex -> path == ex || path.startsWith("$ex/") }) "⊘ $path" else "• $path"
                label.setTextColor(if (githubZipExcluded.any { ex -> path == ex || path.startsWith("$ex/") }) textMuted else textMain)
                remove.text = if (githubZipExcluded.contains(path)) "✓" else "×"
                githubZipUploadSummary()
            }
            row.addView(label)
            row.addView(remove)
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        scroll.addView(list)
        val previewHeight = (420 * resources.displayMetrics.density).roundToInt()
        box.addView(scroll, LinearLayout.LayoutParams(-1, previewHeight))

        AlertDialog.Builder(this)
            .setTitle("Isi ZIP • ${githubZipPreviewFiles.size} file")
            .setView(box)
            .setNegativeButton("Tutup", null)
            .setPositiveButton("Simpan Pilihan", null)
            .show().also { ghStyleRoundedDialog(it, 24) }
    }

    internal fun uploadZipToGitHub(
        uri: Uri,
        owner: String,
        repo: String,
        token: String,
        branch: String,
        commitMessage: String,
        uploadRoot: String,
        excludedPaths: Set<String>,
        createPrivate: Boolean,
        progress: (GhProgress) -> Unit
    ): GhResult {
        val workDir = File(cacheDir, "github_zip_${System.currentTimeMillis()}").apply { mkdirs() }
        val zipFile = File(workDir, "upload.zip")
        try {
            progress(GhProgress(0, "Membaca file ZIP…"))
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(zipFile).use { output -> input.copyTo(output) }
            } ?: error("ZIP tidak dapat dibaca")
            progress(GhProgress(0, "Mengekstrak ZIP…"))
            val extracted = File(workDir, "src").apply { mkdirs() }
            unzipSafeForGithub(zipFile, extracted)

            val rootPath = uploadRoot.trim('/').trim()
            val files = extracted.walkTopDown()
                .filter { it.isFile }
                .map { f -> extracted.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/') to f }
                .filter { (rel, _) ->
                    val inRoot = rootPath.isBlank() || rel == rootPath || rel.startsWith("$rootPath/")
                    val relativeForExclude = if (rootPath.isBlank()) rel else rel.removePrefix("$rootPath/")
                    val excluded = excludedPaths.any { ex ->
                        val normalized = ex.trim('/').replace('\\', '/')
                        rel == normalized || rel.startsWith("$normalized/") ||
                            relativeForExclude == normalized || relativeForExclude.startsWith("$normalized/")
                    }
                    inRoot && !excluded &&
                        !rel.startsWith(".git/") && !rel.startsWith("__MACOSX/") &&
                        rel != ".git" && rel != "__MACOSX" &&
                        rel != ".DS_Store" && !rel.endsWith("/.DS_Store") &&
                        rel != "Thumbs.db" && !rel.endsWith("/Thumbs.db")
                }
                .map { (rel, f) -> (if (rootPath.isBlank()) rel else rel.removePrefix("$rootPath/")) to f }
                .toList()
            require(files.isNotEmpty()) { "Tidak ada file yang tersisa untuk di-upload dari root yang dipilih" }
            require(files.size <= 3000) { "ZIP terlalu banyak file (maksimal 3000)" }
            return pushFilesToGitHub(files, owner, repo, token, branch, commitMessage, createPrivate, progress)
        } finally {
            workDir.deleteRecursively()
        }
    }

    internal fun githubRequest(method: String, url: String, body: JSONObject?, headers: Map<String, String>): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            doInput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(response).optString("message") }.getOrDefault(response.take(240))
                throw IOException("GitHub HTTP $code: ${message.ifBlank { "Request gagal" }}")
            }
            return if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    internal fun encodePath(path: String): String = path.split('/').joinToString("/") { Uri.encode(it) }

    internal fun unzipSafeForGithub(zip: File, dest: File, onBytes: ((Long) -> Unit)? = null) {
        val destCanonical = dest.canonicalFile
        var totalBytes = 0L
        var entries = 0
        val maxEntries = 5000
        val maxTotalBytes = 256L * 1024L * 1024L
        val maxEntryBytes = 64L * 1024L * 1024L
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entries++
                require(entries <= maxEntries) { "ZIP terlalu banyak entry" }
                val normalized = entry.name.replace('\\', '/')
                if (normalized.startsWith("/") || normalized.split('/').any { it == ".." }) {
                    throw SecurityException("ZIP entry tidak aman: ${entry.name}")
                }
                val target = File(destCanonical, normalized).canonicalFile
                require(target.path.startsWith(destCanonical.path + File.separator)) { "ZIP entry di luar folder tujuan" }
                if (entry.isDirectory) {
                    target.mkdirs()
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
                            require(entryBytes <= maxEntryBytes && totalBytes <= maxTotalBytes) { "ZIP melebihi batas aman 256 MB" }
                            out.write(buffer, 0, read)
                            onBytes?.invoke(read.toLong())
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }

    // ---------- ZIP ----------

    internal fun zipPath(src: File, out: File, onBytes: ((Long) -> Unit)? = null) {
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
                val base = src.parentFile?.toPath() ?: src.toPath()
                src.walkTopDown().filter { it.isFile }.forEach { f ->
                    if (f.canonicalFile == outCanonical) return@forEach
                    val name = base.relativize(f.toPath()).toString().replace(File.separatorChar, '/')
                    addFile(f, name)
                }
            }
        }
    }

    internal fun unzipSafe(zip: File, dest: File, onBytes: ((Long) -> Unit)? = null) {
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

    // ---------- SIMPLE TOOLS ----------

    internal fun jsonTool() {
        clearPage("JSON Tools")
        addToolHeader("JSON Tools", "Validasi, rapikan, kecilkan, atau escape JSON.", "{}")
        content.addView(toolSection("INPUT", "Tempel JSON yang ingin diproses."))
        val e = edit("Tempel JSON di sini", true); content.addView(e)
        content.addView(toolSection("ACTIONS"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Validasi", "Pretty", "Minify").forEachIndexed { i, txt ->
            val b = button(txt) {
                val s=e.text.toString().trim()
                when(i) {
                    0 -> output(runCatching {
                        if (s.startsWith("{")) JSONObject(s) else if (s.startsWith("[")) JSONArray(s) else error("JSON harus dimulai dengan { atau [")
                        "JSON valid."
                    }.getOrElse { "JSON tidak valid: ${it.message}" })
                    1 -> output(runCatching { prettyJson(e.text.toString()) }.getOrElse { "JSON tidak valid: ${it.message}" })
                    else -> output(runCatching { minifyJson(e.text.toString()) }.getOrElse { "JSON tidak valid: ${it.message}" })
                }
            }
            row.addView(b, LinearLayout.LayoutParams(0, dp(50), 1f).apply { if(i>0) leftMargin=dp(5) })
        }
        content.addView(row)
        content.addView(button("Escape String") { output(JSONObjectLite.escape(e.text.toString())) })
    }

    internal data class PhotoColor(val color: Int, val percent: Int)

    internal fun colorTool() {
        clearPage("Color Tools")
        content.setPadding(dp(12), dp(8), dp(12), dp(16))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(8))
        }
        header.addView(label("Color Tools", 24f, true))
        header.addView(subLabel("Pilih warna, ekstrak palet dari foto, atau ambil warna langsung dari layar.", 12f))
        content.addView(header)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = bg(Color.rgb(244, 246, 248), 18, Color.rgb(226, 230, 234))
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

    internal fun colorTab(text: String, active: Boolean) = TextView(this).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(if (active) Color.WHITE else textMain)
        background = bg(if (active) Color.rgb(15, 15, 16) else Color.TRANSPARENT, 14)
        isClickable = true
    }

    internal fun buildPhotoColorWorkspace(workspace: LinearLayout) {
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(colorActionButton("Galeri") { openColorPhotoGallery() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) })
        actionRow.addView(colorActionButton("Kamera") { openColorPhotoCamera() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) })
        workspace.addView(actionRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val photoFrame = FrameLayout(this).apply {
            background = bg(Color.rgb(244, 246, 248), 22, Color.rgb(224, 229, 233))
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(239, 242, 245))
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

    internal fun requestColorPaletteExport(format: String) {
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

    internal fun colorDetailsText(color: Int): String {
        val r=Color.red(color); val g=Color.green(color); val b=Color.blue(color)
        val h=rgbToHsl(r,g,b); val hsv=FloatArray(3); Color.colorToHSV(color,hsv)
        return "HEX #%02X%02X%02X\nRGB $r, $g, $b\nHSL ${fmt(h[0])}°, ${fmt(h[1])}%, ${fmt(h[2])}%\nHSV ${fmt(hsv[0].toDouble())}°, ${fmt((hsv[1]*100).toDouble())}%, ${fmt((hsv[2]*100).toDouble())}%\n${contrastSummary(color)}".format(Locale.US, r,g,b)
    }

    internal fun relativeLuminance(color: Int): Double {
        fun channel(v: Int): Double { val x=v/255.0; return if(x<=0.03928) x/12.92 else Math.pow((x+0.055)/1.055,2.4) }
        return 0.2126*channel(Color.red(color)) + 0.7152*channel(Color.green(color)) + 0.0722*channel(Color.blue(color))
    }

    internal fun contrastRatio(a: Int, b: Int): Double {
        val l1=relativeLuminance(a); val l2=relativeLuminance(b)
        val hi=maxOf(l1,l2); val lo=minOf(l1,l2); return (hi+0.05)/(lo+0.05)
    }

    internal fun contrastSummary(color: Int): String {
        val black=contrastRatio(color, Color.BLACK); val white=contrastRatio(color, Color.WHITE)
        val blackOk=black>=4.5; val whiteOk=white>=4.5
        val blackText=if(blackOk) "COCOK" else "kurang"
        val whiteText=if(whiteOk) "COCOK" else "kurang"
        return "Kontras teks: Hitam ${String.format(Locale.US,"%.2f",black)}:1 ($blackText) • Putih ${String.format(Locale.US,"%.2f",white)}:1 ($whiteText)"
    }

    internal fun colorSectionCard(titleText: String, subtitleText: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = bg(Color.WHITE, 20, Color.rgb(226, 230, 234))
        addView(label(titleText, 16f, true))
        addView(subLabel(subtitleText, 11f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(8) })
    }

    internal fun colorActionButton(textValue: String, onClick: () -> Unit) = Button(this).apply {
        text = textValue
        textSize = 12f
        setTextColor(Color.WHITE)
        background = bg(Color.rgb(15, 15, 16), 14)
        setStateListAnimator(null)
        setOnClickListener { scalePress(this); onClick() }
    }

    internal fun scalePress(view: View) = Motion.tap(view, 0.97f)

    internal fun colorCodeChip(titleText: String, code: String) = TextView(this).apply {
        text = "$titleText\n$code"
        textSize = 10f
        setTextColor(textMain)
        setPadding(dp(11), dp(8), dp(11), dp(8))
        background = bg(Color.rgb(245, 247, 249), 14, Color.rgb(230, 234, 238))
    }

    internal fun buildScreenPickerWorkspace(workspace: LinearLayout) {
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

    internal fun buildColorConverterWorkspace(workspace: LinearLayout) {
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
                output("HEX = #${h.toUpperCase(Locale.US)}\nRGB = $r, $g, $b\nHSL = ${fmt(hsl[0])}°, ${fmt(hsl[1])}%, ${fmt(hsl[2])}%\nFlutter = Color(0xFF${h.takeLast(6).toUpperCase(Locale.US)})\nAndroid = 0xFF${h.takeLast(6).toUpperCase(Locale.US)}")
            }.onFailure { output("HEX tidak valid") }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin=dp(8) })
        val rgb=edit("RGB: 255,255,255"); workspace.addView(rgb)
        workspace.addView(colorActionButton("RGB → HEX") {
            runCatching { val p=rgb.text.toString().split(",").map{it.trim().toInt()}; require(p.size==3 && p.all{it in 0..255}); output("#%02X%02X%02X".format(Locale.US,p[0],p[1],p[2])) }.onFailure { output("Format: 255,255,255") }
        }, LinearLayout.LayoutParams(-1, dp(50)))
    }

    internal inner class ColorWheelView(context: Context) : View(context) {
        internal val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        internal var selectedHue = 0f
        var onColorChanged: ((Int) -> Unit)? = null
        init { isClickable = true; setLayerType(View.LAYER_TYPE_SOFTWARE, null) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx=width/2f; val cy=height/2f; val radius=(min(width,height)*.39f).coerceAtLeast(dp(55).toFloat())
            for (i in 0 until 360) {
                paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(18).toFloat(); paint.color=Color.HSVToColor(floatArrayOf(i.toFloat(),1f,1f))
                canvas.drawArc(cx-radius,cy-radius,cx+radius,cy+radius,i.toFloat(),1.4f,false,paint)
            }
            paint.style=Paint.Style.FILL; paint.color=Color.WHITE; paint.setShadowLayer(dp(3).toFloat(),0f,dp(1).toFloat(),0x55000000)
            canvas.drawCircle(cx,cy,dp(38).toFloat(),paint); paint.clearShadowLayer()
            val center=Color.HSVToColor(floatArrayOf(selectedHue,1f,1f)); paint.color=center; canvas.drawCircle(cx,cy,dp(30).toFloat(),paint)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(3).toFloat(); paint.color=Color.WHITE
            val a=Math.toRadians(selectedHue.toDouble()); val sx=cx+Math.cos(a).toFloat()*radius; val sy=cy+Math.sin(a).toFloat()*radius
            canvas.drawCircle(sx,sy,dp(11).toFloat(),paint)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if(event.action!=MotionEvent.ACTION_DOWN && event.action!=MotionEvent.ACTION_MOVE && event.action!=MotionEvent.ACTION_UP) return true
            val cx=width/2f; val cy=height/2f; val dx=event.x-cx; val dy=event.y-cy; val d=Math.sqrt((dx*dx+dy*dy).toDouble()).toFloat(); val r=(min(width,height)*.39f).coerceAtLeast(dp(55).toFloat())
            if(d >= r-dp(24) && d <= r+dp(24)) { selectedHue=((Math.toDegrees(Math.atan2(dy.toDouble(),dx.toDouble()))+360)%360).toFloat(); onColorChanged?.invoke(Color.HSVToColor(floatArrayOf(selectedHue,1f,1f))); invalidate() }
            performClick(); return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }

    internal fun openColorPhotoGallery() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        startActivityForResult(intent, COLOR_PHOTO_PICK_REQUEST)
    }

    internal fun openColorPhotoCamera() {
        val intent=Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if(intent.resolveActivity(packageManager)==null){ toast("Kamera tidak tersedia"); return }
        startActivityForResult(intent, COLOR_PHOTO_CAMERA_REQUEST)
    }

    internal fun loadColorPhoto(bitmap: Bitmap) {
        val max=1600
        val scaled=if(bitmap.width>max || bitmap.height>max){ val s=min(max.toFloat()/bitmap.width,max.toFloat()/bitmap.height); Bitmap.createScaledBitmap(bitmap,(bitmap.width*s).toInt(),(bitmap.height*s).toInt(),true) } else bitmap
        colorPhotoBitmap=scaled
        colorPhotoView?.setImageBitmap(scaled)
        colorPhotoPlaceholder?.visibility = View.GONE
        colorPhotoMarker?.visibility=View.GONE
        colorPhotoStatus?.text="Foto siap • 8 warna utama + detail 32 warna + pipet manual"
        extractPhotoPalette(scaled, 8)
    }

    internal fun extractPhotoPalette(bitmap: Bitmap?, maxColors: Int) {
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

    /** Layer penuh untuk melihat seluruh warna hasil ekstraksi tanpa terpotong. */
    internal fun showFullPaletteLayer() {
        if (currentPhotoPalette.isEmpty()) {
            toast("Belum ada palet warna. Pilih foto dan lakukan ekstraksi dulu.")
            return
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
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
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        dialog.window?.setLayout(-1, -1)
        dialog.show()
        dialog.window?.setLayout(-1, -1)
    }

    internal fun colorDistance(a:Int,b:Int):Int {
        val dr=Color.red(a)-Color.red(b); val dg=Color.green(a)-Color.green(b); val db=Color.blue(a)-Color.blue(b)
        return kotlin.math.sqrt((dr*dr+dg*dg+db*db).toDouble()).toInt()
    }

    internal fun sampleBitmap(bitmap: Bitmap, x: Int, y: Int): Int {
        var sr=0; var sg=0; var sb=0; var count=0
        for(dy in -1..1) for(dx in -1..1){ val px=(x+dx).coerceIn(0,bitmap.width-1); val py=(y+dy).coerceIn(0,bitmap.height-1); val c=bitmap.getPixel(px,py); sr+=Color.red(c); sg+=Color.green(c); sb+=Color.blue(c); count++ }
        return Color.rgb(sr/count,sg/count,sb/count)
    }

    internal fun saveColorHistory(color: Int) {
        val hx="#%02X%02X%02X".format(Locale.US,Color.red(color),Color.green(color),Color.blue(color))
        val old=prefs.getString("color_history","")?.split(",")?.filter{it.isNotBlank()}?:emptyList()
        prefs.edit().putString("color_history",(listOf(hx)+old.filter{it!=hx}).take(24).joinToString(",")).apply()
    }

    // ==================== CALCULATOR SUITE ====================

    internal data class CalculatorMode(val id: String, val name: String, val group: String)

    internal val calculatorModes = listOf(
        CalculatorMode("basiccalc", "Dasar", "Utama"),
        CalculatorMode("scicalc", "Ilmiah", "Utama"),
        CalculatorMode("percentcalc", "Persentase", "Matematika"),
        CalculatorMode("fractioncalc", "Pecahan", "Matematika"),
        CalculatorMode("ratiocalc", "Rasio & Proporsi", "Matematika"),
        CalculatorMode("equationcalc", "Persamaan", "Matematika"),
        CalculatorMode("basecalc", "Basis Angka", "Matematika"),
        CalculatorMode("unitcalc", "Konverter Satuan", "Konversi"),
        CalculatorMode("datacalc", "Ukuran Data", "Konversi"),
        CalculatorMode("speedcalc", "Kecepatan", "Konversi"),
        CalculatorMode("pressurecalc", "Tekanan", "Konversi"),
        CalculatorMode("timecalc", "Durasi", "Tanggal & Waktu"),
        CalculatorMode("datecalc", "Tanggal & Umur", "Tanggal & Waktu"),
        CalculatorMode("worktimecalc", "Jam Kerja", "Tanggal & Waktu"),
        CalculatorMode("areacalc", "Luas & Keliling", "Geometri"),
        CalculatorMode("volumecalc", "Volume", "Geometri"),
        CalculatorMode("riskcalc", "Risk-Reward", "Finansial"),
        CalculatorMode("compoundcalc", "Compound & Tabungan", "Finansial"),
        CalculatorMode("margincalc", "Margin & Pajak", "Finansial"),
        CalculatorMode("discountcalc", "Diskon Bertingkat", "Finansial"),
        CalculatorMode("loancalc", "Cicilan Pinjaman", "Finansial"),
        CalculatorMode("fuelcalc", "Konsumsi BBM", "Finansial"),
        CalculatorMode("pivotcalc", "Pivot Point", "Trading"),
        CalculatorMode("dcacalc", "Averaging / DCA", "Trading"),
        CalculatorMode("installcalc", "Flat vs Anuitas", "Finansial"),
        CalculatorMode("pphcalc", "PPN & PPh", "Finansial"),
        CalculatorMode("dividercalc", "Voltage Divider", "Teknik"),
        CalculatorMode("pwmcalc", "PWM & Duty Cycle", "Teknik"),
        CalculatorMode("powercalc", "Konsumsi Listrik", "Teknik"),
        CalculatorMode("aspectcalc", "Aspect Ratio", "Developer"),
        CalculatorMode("spritecalc", "Sprite Sheet Grid", "Developer")
    )

    internal fun calculatorHub(selected: String = calculatorSelectedMode) {
        calculatorSelectedMode = calculatorModes.firstOrNull { it.id == selected }?.id ?: "basiccalc"
        clearPage("Kalkulator", false)
        content.setPadding(dp(12), dp(8), dp(12), dp(12))

        // UI sengaja dibuat ringkas: judul halaman sudah ada di toolbar, jadi
        // tidak perlu mengulang teks "Kalkulator" dan deskripsi di dalam halaman.

        val selector = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(10), 0)
            background = bg(panel2, 16, line)
            isClickable = true
        }
        val selectedLabel = TextView(this).apply {
            text = calculatorModes.first { it.id == calculatorSelectedMode }.name
            textSize = 16f
            setTextColor(textMain)
            gravity = Gravity.CENTER_VERTICAL
        }
        val selectedGroup = TextView(this).apply {
            text = "  •  ${calculatorModes.first { it.id == calculatorSelectedMode }.group}"
            textSize = 12f
            setTextColor(textMuted)
            gravity = Gravity.CENTER_VERTICAL
        }
        val arrow = TextView(this).apply {
            text = "⌄"
            textSize = 22f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
        }
        selector.addView(selectedLabel, LinearLayout.LayoutParams(0, dp(58), 1f))
        selector.addView(selectedGroup, LinearLayout.LayoutParams(0, dp(58), 1f))
        selector.addView(arrow, LinearLayout.LayoutParams(dp(36), dp(58)))
        selector.setOnClickListener { showCalculatorModePicker() }
        content.addView(selector, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(10) })

        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(
            "basiccalc" to "Dasar",
            "scicalc" to "Ilmiah",
            "percentcalc" to "%",
            "unitcalc" to "Konversi"
        ).forEach { (id, text) ->
            val b = Button(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(if (id == calculatorSelectedMode) Color.WHITE else textMain)
                background = bg(if (id == calculatorSelectedMode) Color.rgb(35,35,39) else panel2, 14, line)
                setStateListAnimator(null)
                setOnClickListener { calculatorHub(id) }
            }
            quick.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        content.addView(quick, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), 0, dp(2), dp(20))
        }
        content.addView(body, LinearLayout.LayoutParams(-1, -2))

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

    internal fun showCalculatorModePicker() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val groups = calculatorModes.groupBy { it.group }
        groups.forEach { (group, modes) ->
            val heading = TextView(this).apply {
                text = group.toUpperCase(Locale.getDefault())
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

    internal fun calcDisplay(hint: String = "0"): EditText = EditText(this).apply {
        setTextColor(textMain)
        setHintTextColor(textMuted)
        textSize = 28f
        gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
        setSingleLine(true)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = bg(panel2, 16, line)
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT
        layoutParams = LinearLayout.LayoutParams(-1, dp(72)).apply { bottomMargin = dp(10) }
    }

    internal fun calcButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(textMain)
        background = bg(panel2, 12, line)
        setOnClickListener { onClick() }
        setStateListAnimator(null)
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = dp(58)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
    }

    internal fun calculatorTool(scientific: Boolean) {
        // Saat ditampilkan di dalam hub, toolbar + selector sudah menjadi konteks
        // kalkulator. Jangan ulangi judul/deskripsi di area isi.
        if (!embeddedCalculatorRender) {
            clearPage(if (scientific) "Kalkulator Ilmiah" else "Kalkulator Dasar")
            val calcTitle = if (scientific) "Kalkulator Ilmiah" else "Kalkulator Dasar"
            content.setPadding(dp(8), dp(4), dp(8), dp(10))
            addToolHeader(calcTitle, if (scientific) "Perhitungan ilmiah dengan keypad responsif." else "Perhitungan cepat dengan keypad yang nyaman di layar sentuh.", "calculator")
            content.setPadding(0, 0, 0, dp(10))
        } else {
            content.removeAllViews()
            content.setPadding(0, dp(2), 0, dp(14))
        }

        val display = calcDisplay()
        display.layoutParams = LinearLayout.LayoutParams(-1, dp(118)).apply {
            setMargins(dp(10), dp(4), dp(10), dp(8))
        }
        display.textSize = if (scientific) 30f else 36f
        display.setPadding(dp(18), dp(10), dp(18), dp(10))
        content.addView(display)

        val grid = GridLayout(this).apply {
            columnCount = if (scientific) 5 else 4
            useDefaultMargins = false
            setPadding(dp(8), 0, dp(8), 0)
        }

        // Basic mode follows the reference calculator layout:
        // AC, +/-, %, ÷
        // 7, 8, 9, ×
        // 4, 5, 6, −
        // 1, 2, 3, =
        // 0, ., DEL, C
        //
        // Most importantly, "=" is a real button and is wired to evaluateExpression().
        val keys = if (scientific) {
            listOf(
                "sin","cos","tan","log","ln",
                "√","x²","xʸ","(",")",
                "7","8","9","÷","DEL",
                "4","5","6","×","C",
                "1","2","3","−","=",
                "0",".","%","+","π"
            )
        } else {
            listOf(
                "AC","±","%","÷",
                "7","8","9","×",
                "4","5","6","−",
                "1","2","3","=",
                "0",".","DEL","C"
            )
        }

        keys.forEach { key ->
            val keyButton = calcButton(key) {
                when (key) {
                    "C", "AC" -> display.setText("")

                    "DEL" -> {
                        if (display.text.isNotEmpty()) {
                            display.setText(display.text.dropLast(1))
                            display.setSelection(display.text.length)
                        }
                    }

                    "±" -> {
                        val current = display.text.toString()
                        if (current.isBlank()) {
                            display.setText("-")
                        } else if (current.startsWith("-")) {
                            display.setText(current.substring(1))
                        } else {
                            display.setText("-$current")
                        }
                        display.setSelection(display.text.length)
                    }

                    "=" -> {
                        val result = runCatching {
                            evaluateExpression(display.text.toString(), scientific)
                        }.getOrElse {
                            "Error: ${it.message ?: "input"}"
                        }
                        display.setText(result)
                        display.setSelection(display.text.length)
                    }

                    "sin","cos","tan","log","ln","x²" -> display.append(key + "(")
                    "√" -> display.append("sqrt(")
                    "xʸ" -> display.append("^")
                    "×" -> display.append("*")
                    "÷" -> display.append("/")
                    "−" -> display.append("-")
                    "π" -> display.append("pi")
                    else -> display.append(key)
                }
            }

            // Operator keys harus selalu terbaca. Sebelumnya teks operator
            // dibuat terang tetapi background tetap terang sehingga tombol
            // terlihat kosong pada perangkat tertentu.
            if (!scientific && (key == "÷" || key == "×" || key == "−" || key == "%")) {
                keyButton.setTextColor(Color.WHITE)
                keyButton.background = bg(Color.rgb(65, 65, 70), 14)
            } else if (!scientific && key == "=") {
                keyButton.setTextColor(Color.BLACK)
                keyButton.background = bg(Color.rgb(245, 245, 247), 14)
            }

            grid.addView(keyButton)
        }

        content.addView(grid, LinearLayout.LayoutParams(-1, if (embeddedCalculatorRender) -2 else 0).apply {
            if (!embeddedCalculatorRender) weight = 1f
        })

        if (scientific) {
            content.addView(
                subLabel(
                    "Mendukung + − × ÷ %, kurung, pangkat, √, sin, cos, tan, log, ln, π.",
                    11f
                ).apply {
                    setPadding(dp(14), dp(6), dp(14), 0)
                }
            )
        }
    }

    internal class ExprParser(private val source: String, private val scientific: Boolean) {
        internal var pos = 0
        internal val s = source.replace("×", "*").replace("÷", "/").replace("−", "-").replace(" ", "")
        fun parse(): Double { val v = expression(); if (pos != s.length) error("Karakter tidak dikenal") ; return v }
        internal fun expression(): Double { var v = term(); while (pos < s.length) { when(s[pos]) { '+' -> {pos++; v += term()} ; '-' -> {pos++; v -= term()} ; else -> return v } }; return v }
        internal fun term(): Double { var v = power(); while (pos < s.length) { when(s[pos]) { '*' -> {pos++; v *= power()} ; '/' -> {pos++; val d=power(); if (d==0.0) error("Tidak bisa dibagi 0"); v /= d} ; '%' -> {pos++; v %= power()} ; else -> return v } }; return v }
        internal fun power(): Double { var v = unary(); if (pos < s.length && s[pos]=='^') {pos++; v = Math.pow(v, power())}; return v }
        internal fun unary(): Double {
            if (pos < s.length && s[pos]=='+') {pos++; return unary()}
            if (pos < s.length && s[pos]=='-') {pos++; return -unary()}
            if (pos < s.length && s[pos]=='(') {pos++; val v=expression(); if(pos>=s.length||s[pos]!=')') error("Kurung belum lengkap"); pos++; return v}
            if (pos < s.length && s[pos].isLetter()) {
                val start=pos; while(pos<s.length && s[pos].isLetter()) pos++
                val name=s.substring(start,pos).toLowerCase(Locale.getDefault())
                if(name=="pi") return Math.PI
                if(pos>=s.length || s[pos]!='(') error("Gunakan kurung setelah $name")
                pos++; val x=expression(); if(pos>=s.length||s[pos]!=')') error("Kurung belum lengkap"); pos++
                return when(name) {
                    "sqrt" -> Math.sqrt(x)
                    "sin" -> Math.sin(Math.toRadians(x))
                    "cos" -> Math.cos(Math.toRadians(x))
                    "tan" -> Math.tan(Math.toRadians(x))
                    "log" -> Math.log10(x)
                    "ln" -> Math.log(x)
                    else -> error("Fungsi $name tidak didukung")
                }
            }
            val start=pos; while(pos<s.length && (s[pos].isDigit()||s[pos]=='.')) pos++
            if(start==pos) error("Angka diharapkan")
            return s.substring(start,pos).toDouble()
        }
    }

    internal fun evaluateExpression(expr: String, scientific: Boolean): String {
        if (expr.isBlank()) return "0"
        val v = ExprParser(expr, scientific).parse()
        if (!v.isFinite()) error("Hasil tidak valid")
        return if (kotlin.math.abs(v - v.toLong()) < 1e-10) v.toLong().toString() else String.format(Locale.US, "%.10f", v).trimEnd('0').trimEnd('.')
    }

    internal fun twoFields(titleText: String, aHint: String, bHint: String, actionText: String, calc: (Double,Double)->String) {
        clearPage(titleText)
        content.addView(label(titleText,22f,true))
        val a=edit(aHint); val b=edit(bHint); content.addView(a); content.addView(b)
        content.addView(button(actionText) {
            val x=runCatching{a.text.toString().replace(",",".").toDouble()}.getOrNull()
            val y=runCatching{b.text.toString().replace(",",".").toDouble()}.getOrNull()
            output(if(x==null||y==null) "Masukkan angka yang valid." else calc(x,y))
        })
    }

    internal fun percentCalculator() {
        clearPage("Persentase")
        content.addView(label("Kalkulator Persentase",22f,true))
        val a=edit("Nilai"); val p=edit("Persen (%)"); content.addView(a); content.addView(p)
        content.addView(button("Hitung X% dari nilai") { val x=a.num(); val y=p.num(); output(if(x==null||y==null) "Input tidak valid" else "${fmt(x*y/100)}") })
        content.addView(button("Berapa % X dari Y") { val x=a.num(); val y=p.num(); output(if(x==null||y==null||y==0.0) "Input tidak valid" else "${fmt(x/y*100)}%") })
        content.addView(button("Tambah X% ke nilai") { val x=a.num(); val y=p.num(); output(if(x==null||y==null) "Input tidak valid" else "${fmt(x*(1+y/100))}") })
        content.addView(button("Kurangi X% dari nilai") { val x=a.num(); val y=p.num(); output(if(x==null||y==null) "Input tidak valid" else "${fmt(x*(1-y/100))}") })
    }

    internal fun fractionCalculator() {
        clearPage("Pecahan")
        content.addView(label("Operasi Pecahan",22f,true))
        val a=edit("Pecahan A, contoh 3/4"); val b=edit("Pecahan B, contoh 1/2"); content.addView(a); content.addView(b)
        listOf("+","−","×","÷").forEach { op -> content.addView(button("A $op B") { output(fractionOp(a.text.toString(), b.text.toString(), op)) }) }
    }

    internal fun fractionOp(a:String,b:String,op:String):String { return runCatching { val x=frac(a); val y=frac(b); val n=when(op){"+"->x.first*y.second+y.first*x.second;"−"->x.first*y.second-y.first*x.second;"×"->x.first*y.first;else->x.first*y.second}; val d=when(op){"+","−"->x.second*y.second;"×"->x.second*y.second;else->{if(y.first==0L) error("Pembagi 0");x.second*y.first}}; val g=gcd(kotlin.math.abs(n),kotlin.math.abs(d)); "${n/g}/${d/g} = ${fmt(n.toDouble()/d)}" }.getOrElse{"Format harus seperti 3/4"} }
    internal fun frac(s:String):Pair<Long,Long>{ val p=s.trim().split("/"); if(p.size!=2) error("pecahan"); val n=p[0].trim().toLong(); val d=p[1].trim().toLong(); if(d==0L) error("0"); return if(d<0) -n to -d else n to d }
    internal fun gcd(a0:Long,b0:Long):Long { var a=a0; var b=b0; while(b!=0L){val t=a%b;a=b;b=t};return if(a==0L)1L else a }

    internal fun ratioCalculator() { twoFields("Rasio & Proporsi","A","B","Sederhanakan rasio") { a,b -> val scale=1000000.0; val ai=kotlin.math.round(a*scale).toLong(); val bi=kotlin.math.round(b*scale).toLong(); val g=gcd(kotlin.math.abs(ai),kotlin.math.abs(bi)); "${ai/g} : ${bi/g}" } }

    internal fun unitCalculator() {
        clearPage("Konverter Satuan")
        content.addView(label("Konverter Satuan",22f,true))
        val input=edit("Nilai"); content.addView(input)
        val from=Spinner(this); val to=Spinner(this)
        val units=arrayOf("meter","kilometer","centimeter","milimeter","inch","feet","yard","mile","gram","kilogram","pound","celsius","fahrenheit","kelvin","reamur","mps","kmh","mph","pascal","kpa","bar","psi")
        listOf(from,to).forEach { it.adapter=ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units); content.addView(it, LinearLayout.LayoutParams(-1,dp(50)).apply{bottomMargin=dp(7)}) }
        content.addView(button("Konversi") { val v=input.num(); output(if(v==null)"Input tidak valid" else "${fmt(convertUnit(v,from.selectedItem.toString(),to.selectedItem.toString()))} ${to.selectedItem}") })
    }

    internal fun convertUnit(v:Double,from:String,to:String):Double {
        val temps=setOf("celsius","fahrenheit","kelvin","reamur")
        if(from in temps || to in temps){
            val c=when(from){"celsius"->v;"fahrenheit"->(v-32)*5/9;"kelvin"->v-273.15;"reamur"->v*5/4;else->v}
            return when(to){"celsius"->c;"fahrenheit"->c*9/5+32;"kelvin"->c+273.15;"reamur"->c*4/5;else->error("Temperatur") }
        }
        val speedBase=mapOf("mps" to 1.0,"kmh" to 1.0/3.6,"mph" to 0.44704)
        if(from in speedBase || to in speedBase){ return v*speedBase.getValue(from)/speedBase.getValue(to) }
        val pressureBase=mapOf("pascal" to 1.0,"kpa" to 1000.0,"bar" to 100000.0,"psi" to 6894.757293)
        if(from in pressureBase || to in pressureBase){ return v*pressureBase.getValue(from)/pressureBase.getValue(to) }
        val factors=mapOf("meter" to 1.0,"kilometer" to 1000.0,"centimeter" to .01,"milimeter" to .001,"inch" to .0254,"feet" to .3048,"yard" to .9144,"mile" to 1609.344,"gram" to .001,"kilogram" to 1.0,"pound" to .45359237)
        if(from !in factors || to !in factors) error("Satuan tidak sejenis")
        return v*factors.getValue(from)/factors.getValue(to)
    }

    internal fun riskRewardCalculator() {
        clearPage("Risk-Reward & Position Sizing")
        content.addView(label("Risk-Reward & Position Sizing",22f,true))
        val capital=edit("Total modal"); val risk=edit("Risiko (%) contoh 1-2"); val entry=edit("Harga entry"); val stop=edit("Stop Loss"); val target=edit("Target harga (opsional)"); val lot=edit("Ukuran 1 lot (opsional, default 1)")
        listOf(capital,risk,entry,stop,target,lot).forEach{content.addView(it)}
        content.addView(button("Hitung posisi") {
            val c=capital.num();val r=risk.num();val e=entry.num();val sl=stop.num();val t=target.num();val ls=lot.num()?:1.0
            if(c==null||r==null||e==null||sl==null||r<=0||e==sl||ls<=0) output("Input tidak valid.") else {
                val riskMoney=c*r/100; val riskUnit=kotlin.math.abs(e-sl); val qty=riskMoney/riskUnit; val lots=qty/ls
                val rr=if(t==null) null else kotlin.math.abs(t-e)/riskUnit
                output("Modal risiko: ${fmt(riskMoney)}\nRisiko/unit: ${fmt(riskUnit)}\nUkuran posisi: ${fmt(qty)} unit\nLot: ${fmt(lots)}${if(rr!=null) "\nRisk-Reward: 1 : ${fmt(rr)}" else ""}")
            }
        })
    }

    internal fun compoundCalculator() {
        clearPage("Compound & Target Tabungan")
        content.addView(label("Compound Interest & Target Tabungan",22f,true))
        val initial=edit("Modal awal");val contribution=edit("Setoran berkala");val rate=edit("Bunga/return tahunan (%)");val periods=edit("Jumlah periode (bulan)")
        listOf(initial,contribution,rate,periods).forEach{content.addView(it)}
        val freq=Spinner(this);freq.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,arrayOf("Bulanan","Mingguan"));content.addView(freq)
        content.addView(button("Proyeksikan") {
            val p=initial.num();val add=contribution.num();val annual=rate.num();val months=periods.num()
            if(p==null||add==null||annual==null||months==null||months<0) output("Input tidak valid.") else {
                val n=if(freq.selectedItemPosition==0) months.toInt() else kotlin.math.round(months*52.0/12.0).toInt(); val ratePer=if(freq.selectedItemPosition==0) annual/100/12 else annual/100/52
                val fv=if(ratePer==0.0) p+add*n else p*Math.pow(1.0+ratePer,n.toDouble())+add*((Math.pow(1.0+ratePer,n.toDouble())-1.0)/ratePer)
                output("Periode: $n\nProyeksi akhir: ${fmt(fv)}\nTotal setoran: ${fmt(p+add*n)}\nPertumbuhan: ${fmt(fv-(p+add*n))}")
            }
        })
        val target=edit("Target nominal (opsional)"); content.addView(target)
        content.addView(button("Hitung setoran bulanan ke target") {
            val tar=target.num();val p=initial.num();val annual=rate.num();val m=periods.num()
            if(tar==null||p==null||annual==null||m==null||m<=0) output("Isi target, modal awal, return tahunan, dan periode.") else {
                val rr=annual/100/12; val n=m.toInt(); val need=if(rr==0.0)(tar-p)/n else (tar-p*Math.pow(1.0+rr,n.toDouble()))*rr/(Math.pow(1.0+rr,n.toDouble())-1.0); output("Setoran bulanan yang diperlukan: ${fmt(kotlin.math.max(0.0,need))}")
            }
        })
    }

    internal fun marginTaxCalculator() {
        clearPage("Margin & PPN/Pajak")
        content.addView(label("Harga Jual • Margin • Pajak",22f,true))
        val cogs=edit("COGS / modal barang");val margin=edit("Target margin (%)");val tax=edit("PPN / pajak (%)")
        listOf(cogs,margin,tax).forEach{content.addView(it)}
        content.addView(button("Hitung harga jual") {
            val c=cogs.num();val m=margin.num();val t=tax.num()?:0.0
            if(c==null||m==null||m<0||m>=100||t<0) output("Input tidak valid. Margin harus 0-99.99%.") else {
                val before=c/(1-m/100); val taxMoney=before*t/100; output("Harga sebelum pajak: ${fmt(before)}\nPajak: ${fmt(taxMoney)}\nHarga akhir: ${fmt(before+taxMoney)}\nLaba kotor: ${fmt(before-c)}")
            }
        })
    }

    internal fun tieredDiscountCalculator() {
        clearPage("Diskon Bertingkat")
        content.addView(label("Diskon Bertingkat",22f,true))
        val price=edit("Harga awal");val d1=edit("Diskon 1 (%)");val d2=edit("Diskon 2 (%)");val d3=edit("Diskon 3 (%) opsional");listOf(price,d1,d2,d3).forEach{content.addView(it)}
        content.addView(button("Hitung harga akhir") {
            val p=price.num();val a=d1.num();val b=d2.num();val c=d3.num()?:0.0
            if(p==null||a==null||b==null||a<0||b<0||c<0||a>100||b>100||c>100) output("Input diskon tidak valid.") else { val end=p*(1-a/100)*(1-b/100)*(1-c/100); output("Harga akhir: ${fmt(end)}\nTotal diskon efektif: ${fmt((1-end/p)*100)}%\nHemat: ${fmt(p-end)}") }
        })
    }

    internal fun dataUnitCalculator() {
        clearPage("Ukuran Data Digital")
        content.addView(label("Byte • KB • MB • GB • TB",22f,true))
        val input=edit("Nilai");content.addView(input);val from=Spinner(this);val to=Spinner(this);val units=arrayOf("Byte","KB","MB","GB","TB");from.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,units);to.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,units);content.addView(from);content.addView(to)
        content.addView(button("Konversi") { val v=input.num(); output(if(v==null)"Input tidak valid" else "${fmt(v*Math.pow(1024.0,from.selectedItemPosition-to.selectedItemPosition.toDouble()))} ${to.selectedItem}") })
    }

    internal fun pressureCalculator() {
        clearPage("Konverter Tekanan")
        content.addView(label("Konverter Tekanan",22f,true))
        val input=edit("Nilai"); content.addView(input)
        val units=arrayOf("Pa","kPa","bar","psi")
        val from=Spinner(this); val to=Spinner(this)
        from.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,units); to.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,units)
        content.addView(from); content.addView(to)
        content.addView(button("Konversi") {
            val v=input.num(); output(if(v==null) "Input tidak valid" else {
                val base=v*when(from.selectedItemPosition){0->1.0;1->1000.0;2->100000.0;else->6894.757293}
                val result=base/when(to.selectedItemPosition){0->1.0;1->1000.0;2->100000.0;else->6894.757293}
                "${fmt(result)} ${to.selectedItem}"
            })
        })
    }

    internal fun workTimeCalculator() {
        clearPage("Jam Kerja")
        content.addView(label("Durasi Jam Kerja",22f,true))
        val start=edit("Mulai HH:mm");val end=edit("Selesai HH:mm");val breakMin=edit("Istirahat (menit)",false);listOf(start,end,breakMin).forEach{content.addView(it)}
        content.addView(button("Hitung durasi") {
            output(runCatching { val f=SimpleDateFormat("HH:mm",Locale.US).apply{isLenient=false}; val s=f.parse(start.text.toString())?.time ?: error("mulai"); var e=f.parse(end.text.toString())?.time ?: error("selesai"); if(e<s)e+=86400000; val br=breakMin.num()?:0.0; val mins=((e-s)/60000.0-br).coerceAtLeast(0.0); "Durasi kerja: ${fmt(mins/60)} jam\n${mins.toLong()} menit" }.getOrElse{"Format waktu harus HH:mm"})
        })
    }

    internal fun areaCalculator() {
        clearPage("Luas & Keliling")
        content.addView(label("Luas & Keliling",22f,true))
        val shape=Spinner(this); val shapes=arrayOf("Persegi","Persegi panjang","Segitiga","Lingkaran")
        shape.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,shapes); content.addView(shape)
        val a=edit("Sisi / panjang"); val b=edit("Lebar / tinggi (jika perlu)"); content.addView(a); content.addView(b)
        content.addView(button("Hitung") { val x=a.num(); val y=b.num(); if(x==null) output("Input tidak valid") else when(shape.selectedItemPosition){0->output("Luas=${fmt(x*x)} • Keliling=${fmt(4*x)}");1->if(y==null)output("Masukkan lebar")else output("Luas=${fmt(x*y)} • Keliling=${fmt(2*(x+y))}");2->if(y==null)output("Masukkan tinggi")else output("Luas=${fmt(.5*x*y)}");3->output("Luas=${fmt(Math.PI*x*x)} • Keliling=${fmt(2*Math.PI*x)}") } })
    }

    internal fun volumeCalculator() {
        clearPage("Volume")
        content.addView(label("Kalkulator Volume",22f,true))
        val shape = Spinner(this)
        shape.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("Kubus","Balok","Tabung","Bola"))
        content.addView(shape)
        val a = edit("Ukuran / radius")
        val b = edit("Lebar / tinggi")
        val c = edit("Panjang / tinggi")
        content.addView(a)
        content.addView(b)
        content.addView(c)
        content.addView(button("Hitung Volume") {
            val x = a.num()
            val y = b.num()
            val z = c.num()
            val result = when (shape.selectedItemPosition) {
                0 -> if (x == null) "Input tidak valid" else fmt(x * x * x)
                1 -> if (x == null || y == null || z == null) "Butuh 3 ukuran" else fmt(x * y * z)
                2 -> if (x == null || y == null) "Butuh radius + tinggi" else fmt(Math.PI * x * x * y)
                else -> if (x == null) "Input tidak valid" else fmt(4.0 / 3.0 * Math.PI * x * x * x)
            }
            output(result)
        })
    }

    internal fun speedCalculator() {
        clearPage("Kecepatan")
        content.addView(label("Jarak • Waktu • Kecepatan",22f,true))
        val d = edit("Jarak")
        val t = edit("Waktu (jam)")
        content.addView(d)
        content.addView(t)
        content.addView(button("Hitung kecepatan") {
            val x = d.num()
            val y = t.num()
            output(if (x == null || y == null || y == 0.0) "Input tidak valid" else "Kecepatan = ${fmt(x / y)} unit/jam")
        })
        content.addView(button("Hitung jarak dari kecepatan × waktu") {
            val x = d.num()
            val y = t.num()
            output(if (x == null || y == null) "Input tidak valid" else "Jarak = ${fmt(x * y)} unit")
        })
    }

    internal fun timeCalculator() {
        clearPage("Waktu & Durasi")
        content.addView(label("Konversi Durasi",22f,true))
        val v = edit("Detik")
        content.addView(v)
        content.addView(button("Konversi") {
            val x = v.num()
            if (x == null || x < 0) {
                output("Input tidak valid")
            } else {
                val sec = x.toLong()
                val h = sec / 3600
                val m = (sec % 3600) / 60
                val ss = sec % 60
                output("$h jam $m menit $ss detik")
            }
        })
    }

    internal fun dateCalculator() {
        clearPage("Selisih Tanggal")
        content.addView(label("Selisih dua tanggal",22f,true))
        val a=edit("Tanggal 1: YYYY-MM-DD"); val b=edit("Tanggal 2: YYYY-MM-DD")
        content.addView(a); content.addView(b)
        content.addView(button("Hitung hari") {
            output(runCatching {
                val f=SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient=false }
                val d1=f.parse(a.text.toString().trim()) ?: error("tanggal")
                val d2=f.parse(b.text.toString().trim()) ?: error("tanggal")
                "${kotlin.math.abs((d2.time-d1.time)/86400000L)} hari"
            }.getOrElse{"Format tanggal: YYYY-MM-DD"})
        })
        content.addView(button("Hitung umur dari Tanggal 1") {
            output(runCatching {
                val f=SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient=false }; val birth=f.parse(a.text.toString().trim()) ?: error("tanggal"); val now=Calendar.getInstance(); val dob=Calendar.getInstance().apply{time=birth}; var years=now.get(Calendar.YEAR)-dob.get(Calendar.YEAR); if(now.get(Calendar.DAY_OF_YEAR)<dob.get(Calendar.DAY_OF_YEAR)) years--; val days=((now.timeInMillis-birth.time)/86400000L).coerceAtLeast(0); "Umur sekitar $years tahun\nTotal hari hidup: $days"
            }.getOrElse{"Format tanggal: YYYY-MM-DD"})
        })
    }

    internal fun loanCalculator() { clearPage("Cicilan Pinjaman"); content.addView(label("Kalkulator cicilan",22f,true)); val principal=edit("Pokok pinjaman"); val rate=edit("Bunga tahunan (%)"); val months=edit("Tenor (bulan)"); content.addView(principal);content.addView(rate);content.addView(months); content.addView(button("Hitung cicilan") {val p=principal.num();val r=rate.num();val n=months.num(); if(p==null||r==null||n==null||n<=0)output("Input tidak valid")else{val m=r/100/12; val pay=if(m==0.0)p/n else p*m*Math.pow(1+m,n)/(Math.pow(1+m,n)-1); output("Cicilan ≈ ${fmt(pay)} per bulan\nTotal ≈ ${fmt(pay*n)}")}}) }

    internal fun fuelCalculator() {
        clearPage("Konsumsi BBM")
        content.addView(label("Konsumsi BBM", 22f, true))
        val distance = edit("Jarak (km)")
        val fuel = edit("BBM (liter)")
        val price = edit("Harga per liter (opsional)")
        content.addView(distance)
        content.addView(fuel)
        content.addView(price)
        content.addView(button("Hitung") {
            val d = distance.num()
            val f = fuel.num()
            val p = price.num()
            if (d == null || f == null || f <= 0) {
                output("Input tidak valid")
            } else {
                val kmpl = d / f
                val l100 = f / d * 100
                val cost = if (p == null) "" else "\nBiaya ≈ ${fmt(f * p)}"
                output("${fmt(kmpl)} km/l\n${fmt(l100)} L/100 km$cost")
            }
        })
    }

    internal fun baseCalculator() {
        clearPage("Basis Angka")
        val e = edit("Masukkan angka, mis. 101101 atau FF")
        content.addView(e)
        val from = Spinner(this)
        from.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("2", "8", "10", "16"))
        content.addView(from)
        content.addView(button("Konversi ke semua basis") {
            try {
                val radix = from.selectedItem.toString().toInt()
                val raw = e.text.toString().trim()
                val n = raw.toLong(radix)
                val result = "BIN  " + n.toString(2) + "\n" +
                        "OCT  " + n.toString(8) + "\n" +
                        "DEC  " + n.toString(10) + "\n" +
                        "HEX  " + n.toString(16).toUpperCase(Locale.getDefault())
                output(result)
            } catch (ex: Exception) {
                output("Angka tidak valid untuk basis yang dipilih.")
            }
        })
    }

    internal fun equationCalculator() { clearPage("Persamaan Linear"); content.addView(label("ax + b = c",22f,true)); val a=edit("a"); val b=edit("b"); val c=edit("c"); content.addView(a);content.addView(b);content.addView(c); content.addView(button("Cari x") {val aa=a.num();val bb=b.num();val cc=c.num();output(if(aa==null||bb==null||cc==null||aa==0.0)"Input tidak valid / a tidak boleh 0" else "x = ${fmt((cc-bb)/aa)}")}) }

    internal fun EditText.num(): Double? = text.toString().trim().replace(",",".").toDoubleOrNull()
    internal fun fmt(v:Double):String = if(v.isFinite() && kotlin.math.abs(v-v.toLong())<1e-10) v.toLong().toString() else String.format(Locale.US,"%.8f",v).trimEnd('0').trimEnd('.')


    // ==================== EXTRA CALCULATORS 2.4 ====================

    internal fun pivotPointCalculator() {
        clearPage("Pivot Point")
        content.addView(label("Pivot Point • Standard",22f,true))
        content.addView(subLabel("Level Support S1-S3 dan Resistance R1-R3 dari High, Low, Close.",12f))
        val h=edit("High"); val l=edit("Low"); val c=edit("Close")
        content.addView(h); content.addView(l); content.addView(c)
        content.addView(button("Hitung Pivot") {
            val high=h.num(); val low=l.num(); val close=c.num()
            if(high==null||low==null||close==null||high<low) output("High/Low tidak valid.") else {
                val p=(high+low+close)/3.0
                val r1=2*p-low; val s1=2*p-high
                val r2=p+(high-low); val s2=p-(high-low)
                val r3=high+2*(p-low); val s3=low-2*(high-p)
                output("Pivot P = ${fmt(p)}\nS1 = ${fmt(s1)}\nS2 = ${fmt(s2)}\nS3 = ${fmt(s3)}\nR1 = ${fmt(r1)}\nR2 = ${fmt(r2)}\nR3 = ${fmt(r3)}")
            }
        })
    }

    internal fun voltageDividerCalculator() {
        clearPage("Voltage Divider")
        content.addView(label("Pembagi Tegangan",22f,true))
        content.addView(subLabel("Vout = Vin × R2 / (R1 + R2)",12f))
        val vin=edit("Vin (V)"); val r1=edit("R1 (ohm)"); val r2=edit("R2 (ohm)"); val target=edit("Target Vout (V)")
        listOf(vin,r1,r2,target).forEach{content.addView(it)}
        content.addView(button("Hitung Vout") {
            val v=vin.num(); val a=r1.num(); val b=r2.num()
            if(v==null||a==null||b==null||v<0||a<=0||b<=0) output("Input tidak valid.")
            else output("Vout = ${fmt(v*b/(a+b))} V\nArus divider = ${fmt(v/(a+b)*1000)} mA")
        })
        content.addView(button("Cari R2 untuk Target Vout") {
            val v=vin.num(); val a=r1.num(); val t=target.num()
            if(v==null||a==null||t==null||a<=0||t<=0||t>=v) output("Vin, R1 dan target Vout tidak valid.")
            else output("R2 ≈ ${fmt(t*a/(v-t))} ohm")
        })
        content.addView(button("Cari R1 untuk Target Vout") {
            val v=vin.num(); val b=r2.num(); val t=target.num()
            if(v==null||b==null||t==null||b<=0||t<=0||t>=v) output("Vin, R2 dan target Vout tidak valid.")
            else output("R1 ≈ ${fmt(b*(v/t-1))} ohm")
        })
    }

    internal fun dcaCalculator() {
        clearPage("Averaging Down & DCA")
        content.addView(label("Averaging Down & DCA",22f,true))
        content.addView(subLabel("Hitung harga rata-rata dan tambahan modal untuk target rata-rata.",12f))
        val oldPrice=edit("Harga posisi lama"); val oldQty=edit("Jumlah/unit lama"); val newPrice=edit("Harga pembelian baru"); val newQty=edit("Jumlah/unit baru")
        listOf(oldPrice,oldQty,newPrice,newQty).forEach{content.addView(it)}
        content.addView(button("Hitung rata-rata baru") {
            val p1=oldPrice.num(); val q1=oldQty.num(); val p2=newPrice.num(); val q2=newQty.num()
            if(p1==null||q1==null||p2==null||q2==null||q1<=0||q2<0) output("Input tidak valid.")
            else { val avg=(p1*q1+p2*q2)/(q1+q2); output("Total unit = ${fmt(q1+q2)}\nModal total = ${fmt(p1*q1+p2*q2)}\nHarga rata-rata = ${fmt(avg)}") }
        })
        val target=edit("Target harga rata-rata"); content.addView(target)
        content.addView(button("Cari tambahan unit & modal") {
            val p1=oldPrice.num(); val q1=oldQty.num(); val p2=newPrice.num(); val t=target.num()
            if(p1==null||q1==null||p2==null||t==null||q1<=0||p2<=0) output("Input tidak valid.")
            else {
                val denom=t-p2
                if(kotlin.math.abs(denom)<1e-12) output("Target sama dengan harga pembelian baru; jumlah unit teoritis tidak terbatas.")
                else { val q2=(p1-t)*q1/denom; if(q2<0) output("Target tidak dapat dicapai dengan harga pembelian baru ini.") else output("Tambahan unit ≈ ${fmt(q2)}\nTambahan modal ≈ ${fmt(q2*p2)}\nRata-rata target = ${fmt(t)}") }
            }
        })
    }

    internal fun pwmCalculator() {
        clearPage("PWM & Duty Cycle")
        content.addView(label("PWM & Duty Cycle",22f,true))
        val supply=edit("Tegangan supply (V)"); val duty=edit("Duty cycle (%)"); val freq=edit("Frekuensi (Hz)")
        listOf(supply,duty,freq).forEach{content.addView(it)}
        content.addView(button("Hitung PWM") {
            val v=supply.num(); val d=duty.num(); val f=freq.num()
            if(v==null||d==null||f==null||d<0||d>100||f<=0) output("Input tidak valid.")
            else { val avg=v*d/100; val periodUs=1_000_000.0/f; output("Tegangan rata-rata ≈ ${fmt(avg)} V\nFrekuensi = ${fmt(f)} Hz\nPeriode ≈ ${fmt(periodUs)} µs\nHIGH time ≈ ${fmt(periodUs*d/100)} µs") }
        })
        val target=edit("Target tegangan rata-rata (V)"); content.addView(target)
        content.addView(button("Hitung Duty dari Target") { val v=supply.num(); val t=target.num(); if(v==null||t==null||v<=0||t<0||t>v) output("Target harus 0 sampai Vin.") else output("Duty cycle ≈ ${fmt(t/v*100)}%") })
    }

    internal fun spriteSheetCalculator() {
        clearPage("Sprite Sheet Grid")
        content.addView(label("Sprite Sheet / Grid",22f,true))
        content.addView(subLabel("Hitung ukuran frame dan jumlah baris/kolom secara tepat.",12f))
        val sheetW=edit("Lebar sprite sheet (px)"); val sheetH=edit("Tinggi sprite sheet (px)"); val cols=edit("Jumlah kolom"); val rows=edit("Jumlah baris")
        listOf(sheetW,sheetH,cols,rows).forEach{content.addView(it)}
        content.addView(button("Hitung frame") { val w=sheetW.num();val h=sheetH.num();val c=cols.num();val r=rows.num(); if(w==null||h==null||c==null||r==null||w<=0||h<=0||c<=0||r<=0) output("Input tidak valid.") else output("Frame = ${fmt(w/c)} × ${fmt(h/r)} px\nTotal frame = ${fmt(c*r)}\nGrid = ${fmt(c)} kolom × ${fmt(r)} baris") })
        val frameW=edit("Lebar frame (px)"); val frameH=edit("Tinggi frame (px)"); content.addView(frameW);content.addView(frameH)
        content.addView(button("Hitung grid dari frame") { val w=sheetW.num();val h=sheetH.num();val fw=frameW.num();val fh=frameH.num(); if(w==null||h==null||fw==null||fh==null||fw<=0||fh<=0) output("Input tidak valid.") else output("Kolom = ${fmt(w/fw)}\nBaris = ${fmt(h/fh)}\nTotal frame = ${fmt(w/fw*h/fh)}") })
    }

    internal fun installmentComparisonCalculator() {
        clearPage("Flat vs Efektif / Anuitas")
        content.addView(label("Bunga Flat vs Efektif/Anuitas",22f,true))
        val principal=edit("Pokok pinjaman"); val rate=edit("Bunga tahunan (%)"); val months=edit("Tenor (bulan)")
        listOf(principal,rate,months).forEach{content.addView(it)}
        content.addView(button("Bandingkan") { val p=principal.num();val annual=rate.num();val n=months.num(); if(p==null||annual==null||n==null||p<=0||n<=0||annual<0) output("Input tidak valid.") else { val flatInterest=p*(annual/100)/12; val flatPay=p/n+flatInterest; val flatTotal=flatPay*n; val m=annual/100/12; val annPay=if(m==0.0)p/n else p*m*Math.pow(1+m,n)/(Math.pow(1+m,n)-1); val annTotal=annPay*n; output("FLAT\nCicilan/bulan ≈ ${fmt(flatPay)}\nTotal bayar ≈ ${fmt(flatTotal)}\nTotal bunga ≈ ${fmt(flatTotal-p)}\n\nEFEKTIF/ANUITAS\nCicilan bulanan ≈ ${fmt(annPay)}\nTotal bayar ≈ ${fmt(annTotal)}\nTotal bunga ≈ ${fmt(annTotal-p)}") } })
    }

    /**
     * Calculator hub entry for UI color conversion.
     * Reuses the screen color picker implementation to avoid duplicating
     * color parsing/conversion state and UI logic.
     */
    internal fun uiColorConverterCalculator() {
        uiColorPickerTool()
    }

    internal fun uiColorPickerTool() {
        clearPage("UI Color")
        content.addView(label("Pipet Warna Layar", 24f, true))
        content.addView(subLabel("Konversi warna HEX/RGB. Pipet tangkapan layar dinonaktifkan demi privasi dan kebijakan platform.", 12f))

        val preview = FrameLayout(this).apply {
            background = bg(Color.rgb(235, 238, 242), 24, Color.rgb(220, 225, 230))
        }
        val swatch = View(this).apply { background = bg(Color.rgb(90, 120, 220), 22) }
        val marker = TextView(this).apply {
            text = "•"
            gravity = Gravity.CENTER
            textSize = 28f
            setTextColor(Color.WHITE)
            background = bg(Color.rgb(90, 120, 220), 30, Color.WHITE)
        }
        preview.addView(swatch, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER))
        preview.addView(marker, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END).apply { topMargin = dp(16); rightMargin = dp(16) })
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(190)).apply { bottomMargin = dp(12) })

        val status = label("Mode aman: tanpa tangkapan layar", 15f, true)
        status.gravity = Gravity.CENTER
        content.addView(status, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })

        val activate = button("INFO: PIPET LAYAR NONAKTIF") { activateColorPicker() }
        activate.background = bg(Color.rgb(25, 25, 27), 16)
        activate.setTextColor(Color.WHITE)
        content.addView(activate, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) })

        val hex = label("HEX  —  Belum ada warna", 16f, true)
        val rgb = subLabel("RGB  —  -", 13f)
        val hsl = subLabel("HSL  —  -", 13f)
        val copy = button("SALIN HEX") {
            val value = hex.text.toString().substringAfter("HEX  —  ").trim()
            if (value.startsWith("#")) copyText(value) else toast("Belum ada warna")
        }
        copy.background = bg(panel2, 14, line)
        content.addView(hex)
        content.addView(rgb)
        content.addView(hsl)
        content.addView(copy, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })

        val note = subLabel("Cara kerja: aktifkan pipet → izinkan tangkapan layar → buka aplikasi apa pun → geser lingkaran pipet ke warna yang diinginkan → tekan tombol pipet.", 11f)
        note.setPadding(0, dp(14), 0, 0)
        content.addView(note)

        colorPickerUiUpdater = { color ->
            keepShapeFill(swatch, color)
            marker.setTextColor(Color.WHITE)
            marker.background = bg(color, 30, Color.WHITE)
            val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
            val hsv = FloatArray(3); Color.colorToHSV(color, hsv)
            val hslValue = rgbToHsl(r, g, b)
            val hx = "#%02X%02X%02X".format(Locale.US, r, g, b)
            hex.text = "HEX  —  $hx"
            rgb.text = "RGB  —  $r, $g, $b"
            hsl.text = "HSL  —  ${fmt(hslValue[0])}°, ${fmt(hslValue[1])}%, ${fmt(hslValue[2])}%"
            status.text = "Pipet aktif  •  $hx"
        }
    }

    internal fun activateColorPicker() {
        // Screen capture / MediaProjection eyedropper removed for policy & privacy compliance.
        // Users can still convert colors via HEX/RGB input or photo palette tools.
        toast("Pipet layar dinonaktifkan demi privasi. Gunakan input HEX/RGB atau ambil warna dari foto.")
    }

    internal fun startColorPickerService() {
        // No-op: ColorPickerService and SYSTEM_ALERT_WINDOW / MediaProjection removed.
        toast("Layanan pipet layar tidak tersedia di versi ini.")
    }

    internal fun colorFromHex(raw:String):String { var h=raw.trim().removePrefix("#"); if(h.length==3) h=h.map{"$it$it"}.joinToString(""); if(h.length!=6&&h.length!=8) error("HEX"); val a=if(h.length==8) h.substring(0,2).toInt(16) else 255; val off=if(h.length==8)2 else 0; val r=h.substring(off,off+2).toInt(16); val g=h.substring(off+2,off+4).toInt(16); val b=h.substring(off+4,off+6).toInt(16); val hsv=FloatArray(3); Color.colorToHSV(Color.rgb(r,g,b),hsv); val hsl=rgbToHsl(r,g,b); return "HEX = #${h.toUpperCase(Locale.US)}\nARGB = $a,$r,$g,$b\nRGB = $r,$g,$b\nHSL = ${fmt(hsl[0])}°, ${fmt(hsl[1])}%, ${fmt(hsl[2])}%\nHSV = ${fmt(hsv[0].toDouble())}°, ${fmt((hsv[1]*100).toDouble())}%, ${fmt((hsv[2]*100).toDouble())}%" }
    internal fun colorFromRgb(raw:String):String { val p=raw.split(",").map{it.trim().toInt()}; if(p.size!=3||p.any{it !in 0..255}) error("RGB"); return colorFromHex("#%02X%02X%02X".format(Locale.US,p[0],p[1],p[2])) }
    internal fun colorFromArgb(raw:String):String { val p=raw.split(",").map{it.trim().toInt()}; if(p.size!=4||p.any{it !in 0..255}) error("ARGB"); return colorFromHex("#%02X%02X%02X%02X".format(Locale.US,p[0],p[1],p[2],p[3])) }
    internal fun rgbToHsl(r:Int,g:Int,b:Int):DoubleArray { val rr=r/255.0; val gg=g/255.0; val bb=b/255.0; val max=maxOf(rr,gg,bb); val min=minOf(rr,gg,bb); var h=0.0; val l=(max+min)/2; var sat=0.0; val d=max-min; if(d!=0.0){sat=if(l>0.5)d/(2-max-min) else d/(max+min); h=when(max){rr->(gg-bb)/d+(if(gg<bb)6 else 0);gg->(bb-rr)/d+2;else->(rr-gg)/d+4};h/=6}; return doubleArrayOf(h*360,sat*100,l*100) }

    internal fun powerConsumptionCalculator() {
        clearPage("Konsumsi Listrik")
        content.addView(label("Konsumsi Listrik & Biaya",22f,true))
        val watts=edit("Daya perangkat (W) total"); val hours=edit("Jam pemakaian per hari"); val days=edit("Hari per bulan"); val tariff=edit("Tarif listrik per kWh")
        listOf(watts,hours,days,tariff).forEach{content.addView(it)}
        content.addView(button("Hitung") { val w=watts.num();val h=hours.num();val d=days.num();val t=tariff.num(); if(w==null||h==null||d==null||t==null||w<0||h<0||d<0||t<0) output("Input tidak valid.") else { val kwhDay=w*h/1000; val kwhMonth=kwhDay*d; output("Energi/hari = ${fmt(kwhDay)} kWh\nEnergi/bulan = ${fmt(kwhMonth)} kWh\nBiaya/hari ≈ ${fmt(kwhDay*t)}\nBiaya/bulan ≈ ${fmt(kwhMonth*t)}") } })
    }

    internal fun aspectRatioCalculator() {
        clearPage("Aspect Ratio")
        content.addView(label("Aspect Ratio & Skala Resolusi",22f,true))
        val w=edit("Lebar (px)"); val h=edit("Tinggi (px)"); content.addView(w);content.addView(h)
        content.addView(button("Hitung rasio") { val a=w.num();val b=h.num(); if(a==null||b==null||a<=0||b<=0) output("Input tidak valid.") else { val ai=kotlin.math.round(a).toLong();val bi=kotlin.math.round(b).toLong();val g=gcd(kotlin.math.abs(ai),kotlin.math.abs(bi)); output("Aspect ratio ≈ ${fmt(a/b)}\nRasio sederhana = ${ai/g}:${bi/g}") } })
        val ratioW=edit("Rasio lebar, contoh 16"); val ratioH=edit("Rasio tinggi, contoh 9"); val known=edit("Ukuran yang diketahui (px)"); content.addView(ratioW);content.addView(ratioH);content.addView(known)
        val mode=Spinner(this); mode.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,arrayOf("Diketahui lebar → cari tinggi","Diketahui tinggi → cari lebar")); content.addView(mode,LinearLayout.LayoutParams(-1,dp(50)).apply{bottomMargin=dp(7)})
        content.addView(button("Hitung ukuran proporsional") { val rw=ratioW.num();val rh=ratioH.num();val k=known.num(); if(rw==null||rh==null||k==null||rw<=0||rh<=0||k<=0) output("Input tidak valid.") else if(mode.selectedItemPosition==0) output("Resolusi = ${fmt(k)} × ${fmt(k*rh/rw)} px") else output("Resolusi = ${fmt(k*rw/rh)} × ${fmt(k)} px") })
    }

    internal fun ppnPphCalculator() {
        clearPage("PPN & PPh Final")
        content.addView(label("PPN & PPh Final",22f,true))
        content.addView(subLabel("Masukkan tarif pajak sendiri agar sesuai aturan/kontrak yang berlaku.",12f))
        val gross=edit("Nilai bruto / DPP"); val ppn=edit("PPN (%)"); val pph=edit("PPh Final (%)")
        listOf(gross,ppn,pph).forEach{content.addView(it)}
        content.addView(button("Hitung invoice") { val g=gross.num();val pv=ppn.num();val ph=pph.num(); if(g==null||pv==null||ph==null||g<0||pv<0||ph<0) output("Input tidak valid.") else { val ppnVal=g*pv/100; val pphVal=g*ph/100; val invoice=g+ppnVal; val nett=g+ppnVal-pphVal; output("DPP = ${fmt(g)}\nPPN = ${fmt(ppnVal)}\nTotal invoice = ${fmt(invoice)}\nPPh Final = ${fmt(pphVal)}\nNett setelah PPh = ${fmt(nett)}") } })
    }


    // ===================== Pengelola Keuangan =====================
    // All finance UI (reader, dialogs, components) extracted to:
    //   feature/finance/FinanceScreen.kt
    //   feature/finance/FinanceDialogs.kt
    //   feature/finance/FinanceComponents.kt
    // Vars kept here for shared state:
    internal var financeSearchQuery = ""
    internal var financeCategoryFilter = "Semua"
    internal var financeWalletFilter = "Semua"
    internal var pendingFinanceExportJson = false
    // ==============================================================



    // ---------- ESP DEVICE / CONTROL TOOLKIT ----------

    internal fun espBaseUrlField(defaultUrl: String = "http://192.168.4.1"): EditText {
        val e = edit("ESP base URL, contoh http://192.168.4.1")
        e.setText(prefs.getString("esp_base_url", defaultUrl) ?: defaultUrl)
        return e
    }

    internal fun normalizeEspUrl(raw: String): String {
        var v = raw.trim()
        if (v.isBlank()) v = "http://192.168.4.1"
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://$v"
        return v.trimEnd('/')
    }

    internal fun httpRequest(method: String, url: String, body: String? = null, contentType: String = "application/json", timeout: Int = 7000): Pair<Int, String> {
        val parsed = URL(url)
        require(parsed.protocol.equals("http", true) || parsed.protocol.equals("https", true)) { "URL harus menggunakan http:// atau https://" }
        require(parsed.host.isNotBlank()) { "Host URL kosong" }
        require(parsed.userInfo == null) { "URL dengan userinfo tidak didukung" }
        require(timeout in 1000..30000) { "Timeout di luar batas aman" }
        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = method.toUpperCase(Locale.US)
            connectTimeout = timeout
            readTimeout = timeout
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
            }
        }
        if (body != null) conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..399) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }?.take(12000).orEmpty()
        conn.disconnect()
        return code to text
    }

    internal fun espAutoDiscovery() {
        clearPage("ESP Auto Discovery")
        addToolHeader("ESP Auto Discovery", "Temukan perangkat ESP yang mengiklankan layanan mDNS di jaringan lokal.", "⌁")
        content.addView(toolSection("DISCOVERY", "Hasil perangkat akan muncul di bawah."))
        val result = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(result)
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { runOnUiThread { result.addView(subLabel("Memindai $serviceType …", 12f)) } }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        runOnUiThread {
                            val host = info.host?.hostAddress ?: "?"
                            result.addView(label("${info.serviceName} • $host:${info.port}", 13f, true))
                        }
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { runOnUiThread { result.addView(subLabel("Discovery gagal: $errorCode", 12f)) } }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsdDiscoveryManager = nsd
        nsdDiscoveryListener = listener
        content.addView(button("Mulai scan HTTP") { runCatching { nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { toast("NSD tidak tersedia: ${it.message}") } })
        content.addView(subLabel("ESP yang menjalankan mDNS/Bonjour HTTP dapat muncul otomatis. Perangkat tanpa mDNS tetap dapat dimasukkan melalui Device Manager.", 11f))
    }

    internal fun stopEspDiscovery() {
        val manager = nsdDiscoveryManager
        val listener = nsdDiscoveryListener
        if (manager != null && listener != null) runCatching { manager.stopServiceDiscovery(listener) }
        nsdDiscoveryManager = null
        nsdDiscoveryListener = null
    }

    internal fun espDeviceManager() {
        clearPage("ESP Device Manager")
        addToolHeader("ESP Device Manager", "Simpan endpoint ESP dan cek status perangkat dari satu panel.", "ESP")
        content.addView(toolSection("DEVICE"))
        val base = espBaseUrlField(); content.addView(base)
        val info = label("Belum dicek", 13f); info.setPadding(dp(10), dp(12), dp(10), dp(12)); info.background = bg(panel2, 14, line); content.addView(info)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("🔎 CHECK STATUS") {
            val url = normalizeEspUrl(base.text.toString()) + "/status"
            prefs.edit().putString("esp_base_url", normalizeEspUrl(base.text.toString())).apply()
            info.text = "Menghubungkan…"
            toolThread {
                val r = runCatching { httpRequest("GET", url) }.getOrElse { -1 to (it.message ?: "error") }
                runOnUiThread {
                    info.text = if (r.first in 200..299) formatEspJson(r.second) else "HTTP ${r.first}\n${r.second.ifBlank { "Tidak ada response" }}"
                }
            }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(4) })
        row.addView(button("COPY URL") { copyText(normalizeEspUrl(base.text.toString())) }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(4) })
        content.addView(row)
        content.addView(button("GET /health") { espSimpleGet(base.text.toString(), "/health") })
        content.addView(button("GET /info") { espSimpleGet(base.text.toString(), "/info") })
        content.addView(subLabel("ESP dapat mengembalikan JSON seperti {\"chip\":\"ESP32\",\"ip\":\"192.168.4.1\",\"rssi\":-48,\"uptime\":1234,\"free_heap\":200000,\"firmware\":\"1.0.0\"}.", 11f))
    }

    internal fun formatEspJson(raw: String): String = runCatching {
        JSONObject(raw).toString(2)
    }.getOrElse { raw.ifBlank { "ESP terhubung, response kosong." } }

    internal fun espSimpleGet(baseRaw: String, path: String) {
        val url = normalizeEspUrl(baseRaw) + path
        toolThread {
            val r = runCatching { httpRequest("GET", url) }.getOrElse { -1 to (it.message ?: "error") }
            runOnUiThread { output("$url\nHTTP ${r.first}\n${formatEspJson(r.second)}") }
        }
    }

    internal fun espGpioController() {
        clearPage("ESP GPIO Controller")
        addToolHeader("ESP GPIO Controller", "Kontrol pin ESP dengan panel HIGH/LOW yang lebih jelas.", "GPIO")
        content.addView(toolSection("CONNECTION"))
        val base = espBaseUrlField(); content.addView(base)
        val pinEdit = edit("GPIO, contoh 2"); pinEdit.setText("2"); content.addView(pinEdit)
        content.addView(toolSection("MODE & PWM"))
        val mode = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("OUTPUT", "INPUT", "PWM")) }
        content.addView(mode, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
        val pwm = edit("PWM duty 0-255 (untuk PWM)"); pwm.setText("128"); content.addView(pwm)
        val state = toolStatus("Siap"); content.addView(state)
        content.addView(toolSection("ACTIONS"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("HIGH / ON") { sendGpio(base.text.toString(), pinEdit.text.toString(), "HIGH", mode.selectedItem.toString(), pwm.text.toString(), state) }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(4) })
        row.addView(button("LOW / OFF") { sendGpio(base.text.toString(), pinEdit.text.toString(), "LOW", mode.selectedItem.toString(), pwm.text.toString(), state) }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(4) })
        content.addView(row)
        content.addView(button("READ GPIO STATUS") { espSimpleGet(base.text.toString(), "/gpio") })
        content.addView(subLabel("Request JSON: {gpio:2, mode:\"OUTPUT\", state:\"HIGH\", pwm:128}", 11f))
    }

    internal fun sendGpio(baseRaw: String, pinRaw: String, stateRaw: String, mode: String, pwmRaw: String, stateView: TextView) {
        val pin = pinRaw.toIntOrNull()
        if (pin == null || pin !in 0..39) { toast("GPIO tidak valid"); return }
        val json = JSONObject().apply { put("gpio", pin); put("mode", mode); put("state", stateRaw); put("pwm", pwmRaw.toIntOrNull()?.coerceIn(0,255) ?: 0) }
        stateView.text = "Mengirim GPIO $pin…"
        toolThread {
            val r = runCatching { httpRequest("POST", normalizeEspUrl(baseRaw) + "/gpio", json.toString()) }.getOrElse { -1 to (it.message ?: "error") }
            runOnUiThread { stateView.text = "HTTP ${r.first}\n${r.second.ifBlank { "OK" }}" }
        }
    }

    internal fun espSensorDashboard() {
        clearPage("ESP Sensor Dashboard")
        addToolHeader("ESP Sensor Dashboard", "Pantau data sensor ESP secara berkala tanpa mengubah tema aplikasi.", "◌")
        content.addView(toolSection("DEVICE"))
        val base = espBaseUrlField(); content.addView(base)
        val box = label("Belum ada data", 14f); box.setPadding(dp(14), dp(16), dp(14), dp(16)); box.background = bg(panel2, 16, line); content.addView(box)
        content.addView(toolSection("POLLING"))
        val interval = edit("Interval polling (ms)"); interval.setText("1000"); content.addView(interval)
        content.addView(button("▶ START / REFRESH") {
            val delay = (interval.text.toString().toLongOrNull() ?: 1000L).coerceIn(250L, 60000L)
            startEspSensorPolling(base, box, delay)
        })
        content.addView(button("■ STOP") { stopEspSensorPolling() })
        content.addView(subLabel("Gunakan minimal 250 ms agar ESP tidak dibanjiri request.", 11f))
    }

    internal fun startEspSensorPolling(base: EditText, box: TextView, delay: Long) {
        stopEspSensorPolling()
        espSensorPolling = true
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!espSensorPolling || currentPage != "ESP Sensor Dashboard") return
                val url = normalizeEspUrl(base.text.toString()) + "/sensors"
                toolThread {
                    val res = runCatching { httpRequest("GET", url, timeout = 5000) }.getOrElse { -1 to (it.message ?: "error") }
                    runOnUiThread {
                        if (espSensorPolling) box.text = if (res.first in 200..299) formatEspJson(res.second) else "HTTP ${res.first}\n${res.second}"
                    }
                }
                handler.postDelayed(this, delay)
            }
        }
        espSensorHandler = handler
        espSensorRunnable = runnable
        handler.post(runnable)
    }

    internal fun stopEspSensorPolling() {
        espSensorPolling = false
        val handler = espSensorHandler
        val runnable = espSensorRunnable
        if (handler != null && runnable != null) handler.removeCallbacks(runnable)
        espSensorHandler = null
        espSensorRunnable = null
    }

    internal fun espWifiManager() {
        clearPage("ESP Wi-Fi Manager")
        addToolHeader("ESP Wi-Fi Manager", "Kelola konfigurasi Wi-Fi ESP dengan status koneksi yang jelas.", "WiFi")
        content.addView(toolSection("CONNECTION"))
        val base = espBaseUrlField(); content.addView(base)
        val ssid = edit("SSID Wi-Fi"); content.addView(ssid)
        val pass = edit("Password Wi-Fi"); pass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; content.addView(pass)
        val result = label("Siap", 13f); content.addView(result)
        content.addView(button("📶 SEND WI-FI CONFIG") {
            if (ssid.text.toString().isBlank()) { toast("SSID kosong"); return@button }
            val json = JSONObject().apply { put("ssid", ssid.text.toString()); put("password", pass.text.toString()) }
            result.text = "Mengirim…"
            toolThread {
                val r = runCatching { httpRequest("POST", normalizeEspUrl(base.text.toString()) + "/wifi/config", json.toString()) }.getOrElse { -1 to (it.message ?: "error") }
                runOnUiThread { result.text = "HTTP ${r.first}\n${r.second.ifBlank { "OK" }}" }
            }
        })
        content.addView(button("GET CURRENT STATUS") { espSimpleGet(base.text.toString(), "/wifi/status") })
    }

    internal fun espOtaFirmware() {
        clearPage("ESP OTA Firmware")
        content.addView(label("ESP OTA Firmware", 22f, true))
        content.addView(subLabel("Pilih firmware .bin lalu upload sebagai raw application/octet-stream ke endpoint OTA. Default /update.", 12f))
        val base = espBaseUrlField(); content.addView(base)
        val endpoint = edit("OTA endpoint path, contoh /update"); endpoint.setText("/update"); content.addView(endpoint)
        val status = label("Belum ada firmware", 13f); content.addView(status)
        content.addView(button("📦 PILIH .BIN & UPLOAD") {
            pendingOtaEndpoint = normalizeEspUrl(base.text.toString()) + (endpoint.text.toString().trim().let { if (it.startsWith("/")) it else "/$it" })
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/octet-stream"; addCategory(Intent.CATEGORY_OPENABLE) }, 1030)
            status.text = "Menunggu file…"
        })
        content.addView(button("GET /version") { espSimpleGet(base.text.toString(), "/version") })
        content.addView(subLabel("Implementasi ini memakai POST raw. Endpoint ESP harus menerima body binary dan melakukan validasi firmware sebelum reboot.", 11f))
    }

    internal fun uploadOtaUri(uri: Uri, endpoint: String) {
        toolThread {
            val result = runCatching {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 10000; readTimeout = 30000; doOutput = true
                    setRequestProperty("Content-Type", "application/octet-stream")
                    setRequestProperty("X-Firmware-Name", queryName(uri) ?: "firmware.bin")
                }
                contentResolver.openInputStream(uri)?.use { input -> conn.outputStream.use { out -> input.copyTo(out, 8192) } } ?: error("File tidak bisa dibaca")
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }?.take(2000).orEmpty()
                conn.disconnect()
                if (code !in 200..299) error("HTTP $code ${body.ifBlank { "OTA ditolak" }}")
                "OTA berhasil • HTTP $code\n$body"
            }.getOrElse { "OTA gagal: ${it.message}" }
            runOnUiThread { output(result) }
        }
    }

    internal fun espHttpApiTester() {
        clearPage("ESP HTTP/API Tester")
        addToolHeader("ESP HTTP / API Tester", "Uji endpoint ESP dengan method, body, dan response dalam satu workspace.", "API")
        content.addView(toolSection("REQUEST"))
        val method = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("GET", "POST", "PUT", "DELETE", "PATCH")) }
        content.addView(method, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
        val url = edit("http://192.168.4.1/status"); content.addView(url)
        val body = edit("JSON body (opsional)", true); content.addView(body)
        content.addView(button("SEND REQUEST") {
            val u = url.text.toString().trim(); if (u.isBlank()) { toast("URL kosong"); return@button }
            toolThread {
                val start = System.currentTimeMillis()
                val r = runCatching { httpRequest(method.selectedItem.toString(), u, body.text.toString().takeIf { it.isNotBlank() }) }.getOrElse { -1 to (it.message ?: "error") }
                val ms = System.currentTimeMillis() - start
                runOnUiThread { output("${method.selectedItem} $u\nHTTP ${r.first}\nResponse time: ${ms} ms\n\n${r.second}") }
            }
        })
    }

    internal fun espMqttClient() {
        clearPage("ESP MQTT Client")
        addToolHeader("ESP MQTT Client", "Publish atau subscribe pesan MQTT dengan panel koneksi yang ringkas.", "MQ")
        content.addView(toolSection("BROKER"))
        val host = edit("Broker host, contoh 192.168.1.10"); content.addView(host)
        val port = edit("Port"); port.setText("1883"); content.addView(port)
        val clientId = edit("Client ID"); clientId.setText("MyTools-" + (System.currentTimeMillis() % 100000)); content.addView(clientId)
        val topic = edit("Topic, contoh esp32/led"); content.addView(topic)
        val message = edit("Message"); content.addView(message)
        val result = label("Disconnected", 13f); content.addView(result)
        content.addView(button("PUBLISH") {
            val h = host.text.toString().trim(); val p = port.text.toString().toIntOrNull() ?: 1883
            toolThread {
                val r = runCatching { mqttPublish(h, p, clientId.text.toString(), topic.text.toString(), message.text.toString()) }.getOrElse { it.message ?: "MQTT error" }
                runOnUiThread { result.text = r }
            }
        })
        content.addView(button("SUBSCRIBE (5 detik)") {
            val h = host.text.toString().trim(); val p = port.text.toString().toIntOrNull() ?: 1883
            toolThread {
                val r = runCatching { mqttSubscribe(h, p, clientId.text.toString(), topic.text.toString()) }.getOrElse { it.message ?: "MQTT error" }
                runOnUiThread { output(r) }
            }
        })
        content.addView(subLabel("Broker tanpa TLS/auth didukung pada mode dasar ini. Jangan mengirim kredensial sensitif melalui jaringan terbuka.", 11f))
    }

    internal fun mqttEncodeRemainingLength(length: Int): ByteArray {
        var x = length
        val out = ByteArrayOutputStream()
        do { var digit = x % 128; x /= 128; if (x > 0) digit = digit or 128; out.write(digit) } while (x > 0)
        return out.toByteArray()
    }

    internal fun mqttUtf8(s: String): ByteArray {
        val b = s.toByteArray(StandardCharsets.UTF_8); val out = ByteArrayOutputStream(); out.write((b.size shr 8) and 255); out.write(b.size and 255); out.write(b); return out.toByteArray()
    }

    internal fun mqttPacket(typeFlags: Int, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply { write(typeFlags); write(mqttEncodeRemainingLength(payload.size)); write(payload) }.toByteArray()

    internal fun mqttConnect(clientId: String): ByteArray {
        val payload = ByteArrayOutputStream(); payload.write(mqttUtf8("MQTT")); payload.write(4); payload.write(2); payload.write(0); payload.write(30); payload.write(mqttUtf8(clientId)); return mqttPacket(0x10, payload.toByteArray())
    }

    internal fun mqttPublish(topic: String, message: String): ByteArray = mqttPacket(0x30, mqttUtf8(topic) + message.toByteArray(StandardCharsets.UTF_8))

    internal fun mqttSubscribe(topic: String, packetId: Int = 1): ByteArray {
        val p = ByteArrayOutputStream(); p.write((packetId shr 8) and 255); p.write(packetId and 255); p.write(mqttUtf8(topic)); p.write(0); return mqttPacket(0x82, p.toByteArray())
    }

    internal fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) return false
            offset += n
        }
        return true
    }

    internal fun mqttPublish(host: String, port: Int, clientId: String, topic: String, message: String): String {
        require(host.isNotBlank() && topic.isNotBlank()) { "Host dan topic wajib diisi" }
        require(port in 1..65535) { "Port MQTT tidak valid" }
        Socket(host, port).use { socket ->
            socket.soTimeout = 5000
            val out = socket.getOutputStream(); val input = socket.getInputStream()
            out.write(mqttConnect(clientId)); out.flush()
            val connAck = ByteArray(4); require(readFully(input, connAck)) { "Broker menutup koneksi sebelum CONNACK" }
            require((connAck[0].toInt() and 0xFF) == 0x20 && (connAck[3].toInt() and 0xFF) == 0) { "CONNACK ditolak" }
            out.write(mqttPublish(topic, message)); out.flush()
            return "PUBLISH berhasil • $topic"
        }
    }

    internal fun mqttReadPacket(input: InputStream): ByteArray? {
        val first = input.read()
        if (first < 0) return null
        var multiplier = 1
        var remaining = 0
        var count = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            remaining += (b and 127) * multiplier
            count++
            if ((b and 128) == 0) break
            require(count < 4) { "MQTT remaining length tidak valid" }
            multiplier *= 128
        }
        val body = ByteArray(remaining)
        require(readFully(input, body)) { "Paket MQTT terpotong" }
        return byteArrayOf(first.toByte()) + body
    }

    internal fun mqttPublishInfo(packet: ByteArray): String? {
        if (packet.isEmpty() || ((packet[0].toInt() ushr 4) != 3)) return null
        if (packet.size < 3) return null
        val topicLen = ((packet[1].toInt() and 0xFF) shl 8) or (packet[2].toInt() and 0xFF)
        if (topicLen < 0 || packet.size < 3 + topicLen) return null
        val topic = String(packet, 3, topicLen, StandardCharsets.UTF_8)
        var pos = 3 + topicLen
        val qos = (packet[0].toInt() ushr 1) and 3
        if (qos > 0) {
            if (packet.size < pos + 2) return null
            pos += 2
        }
        val payload = if (pos < packet.size) String(packet, pos, packet.size - pos, StandardCharsets.UTF_8) else ""
        return "PUBLISH\nTopic: $topic\nPayload: $payload"
    }

    internal fun mqttSubscribe(host: String, port: Int, clientId: String, topic: String): String {
        require(host.isNotBlank() && topic.isNotBlank()) { "Host dan topic wajib diisi" }
        require(port in 1..65535) { "Port MQTT tidak valid" }
        Socket(host, port).use { socket ->
            socket.soTimeout = 1000
            val out = socket.getOutputStream(); val input = socket.getInputStream()
            out.write(mqttConnect(clientId)); out.flush()
            val connAck = mqttReadPacket(input) ?: error("Broker menutup koneksi sebelum CONNACK")
            require(connAck.size >= 4 && (connAck[0].toInt() and 0xF0) == 0x20 && (connAck.last().toInt() and 0xFF) == 0) { "CONNACK ditolak" }
            val packetId = 1
            out.write(mqttSubscribe(topic, packetId)); out.flush()
            val started = System.currentTimeMillis()
            var subAckOk = false
            val messages = StringBuilder("Menunggu SUBACK untuk: $topic\n")
            while (System.currentTimeMillis() - started < 5000) {
                try {
                    val packet = mqttReadPacket(input) ?: break
                    if (packet.isEmpty()) continue
                    when (packet[0].toInt() and 0xF0) {
                        0x90 -> {
                            if (packet.size >= 5) {
                                val id = ((packet[1].toInt() and 0xFF) shl 8) or (packet[2].toInt() and 0xFF)
                                val rc = packet[3].toInt() and 0xFF
                                require(id == packetId) { "SUBACK packet ID tidak cocok" }
                                require(rc == 0) { "SUBSCRIBE ditolak, return code=$rc" }
                                subAckOk = true
                                messages.append("SUBACK OK\n")
                            }
                        }
                        0x30 -> mqttPublishInfo(packet)?.let { messages.append(it).append("\n") }
                    }
                } catch (_: SocketTimeoutException) { }
            }
            require(subAckOk) { "SUBACK tidak diterima dalam 5 detik" }
            return messages.toString()
        }
    }

    internal fun espUsbInfo() {
        clearPage("ESP USB / OTG Info")
        content.addView(label("ESP USB / OTG Info", 22f, true))
        content.addView(subLabel("Membaca perangkat USB yang terdeteksi Android. Untuk serial USB, Android harus mengizinkan akses perangkat terlebih dahulu.", 12f))
        val usb = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val devices = usb.deviceList.values.toList()
        if (devices.isEmpty()) content.addView(subLabel("Tidak ada perangkat USB yang terdeteksi. Sambungkan ESP melalui OTG.", 13f))
        devices.forEach { d ->
            val info = "${d.deviceName}\nVID: ${d.vendorId} • PID: ${d.productId}\nInterfaces: ${d.interfaceCount}\nClass: ${d.deviceClass}"
            content.addView(label(info, 13f).apply { setPadding(dp(12), dp(12), dp(12), dp(12)); background = bg(panel2, 14, line) })
        }
        content.addView(button("REFRESH") { espUsbInfo() })
    }

    internal fun espTcpSerialMonitor() {
        clearPage("ESP TCP Serial Monitor")
        content.addView(label("ESP TCP Serial Monitor", 22f, true))
        content.addView(subLabel("Monitor serial yang diekspos ESP sebagai TCP socket, misalnya firmware bridge di port 23/3333. Ini bukan driver USB serial.", 12f))
        val host = edit("ESP IP / host"); content.addView(host)
        val port = edit("TCP port"); port.setText("3333"); content.addView(port)
        val command = edit("Kirim command (opsional)"); content.addView(command)
        val log = edit("Log", true); log.isEnabled = false; content.addView(log)
        content.addView(button("CONNECT / READ 5 DETIK") {
            val h = host.text.toString().trim(); val p = port.text.toString().toIntOrNull() ?: 3333
            toolThread {
                val r = runCatching {
                    Socket(h, p).use { socket ->
                        socket.soTimeout = 1000
                        if (command.text.toString().isNotBlank()) socket.getOutputStream().apply { write((command.text.toString() + "\n").toByteArray(StandardCharsets.UTF_8)); flush() }
                        val start = System.currentTimeMillis(); val bytes = ByteArrayOutputStream(); val buf = ByteArray(1024)
                        while (System.currentTimeMillis() - start < 5000) { try { val n = socket.getInputStream().read(buf); if (n > 0) bytes.write(buf, 0, n) } catch (_: SocketTimeoutException) { /* read timeout window */ } }
                        bytes.toString("UTF-8")
                    }
                }.getOrElse { "Serial TCP error: ${it.message}" }
                runOnUiThread { log.isEnabled = true; log.setText(r.ifBlank { "Tidak ada data selama 5 detik." }); log.isEnabled = false }
            }
        })
    }

    // ---------- NETWORK ----------

    internal fun wifiInfo() {
        clearPage("Wi-Fi Info")
        val wifiLocationGranted=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val wifiNearbyGranted=Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(this,Manifest.permission.NEARBY_WIFI_DEVICES)==PackageManager.PERMISSION_GRANTED
        if(!wifiLocationGranted || !wifiNearbyGranted){
            modernInfoCard("Izin diperlukan","Izinkan Lokasi dan Nearby Wi-Fi agar SSID, sinyal, dan scan jaringan dapat dibaca.")
            modernActionRow("Izinkan akses Wi-Fi","map-marker"){requestWifiPermissions()}
            return
        }
        modernUiRunnable?.let{modernUiHandler.removeCallbacks(it)}
        wifiHistory.clear()
        val hero=modernWifiHero()
        val chart=MiniLineChart(this).apply{lineColor=accentTeal();fillColor=Color.argb(42,0,137,123);minValue=-90f;maxValue=-50f}
        modernChartCard("Grafik Kekuatan Sinyal",chart)
        val details=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(8));background=bg(Color.WHITE,16,line)}
        details.addView(label("Informasi Jaringan",13f,true));details.addView(subLabel("Alamat lokal dan jalur koneksi perangkat",10f))
        val rows=listOf("IP Address","Gateway","Subnet","DNS","IPv6").map{infoLine(it,"-")};rows.forEach{details.addView(it)}
        content.addView(details,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        actions.addView(modernActionButton("Test Koneksi","wifi-check"){testWifiConnection()},weightLp(1f,0))
        actions.addView(modernActionButton("Scan Wi-Fi","access-point"){scanNearbyWifi()},weightLp(1f,6))
        content.addView(actions)
        fun update(){
            if(currentPage!="Wi-Fi Info")return
            val wm=applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION") val i=wm.connectionInfo
            val cm=getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val n=cm.activeNetwork
            val caps=if(n!=null)cm.getNetworkCapabilities(n)else null
            val wifiConnected=caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)==true
            val lp=if(wifiConnected && n!=null) cm.getLinkProperties(n) else null
            val rawSsid=i.ssid?.trim().orEmpty()
            val ssid=when {
                rawSsid.isBlank() || rawSsid=="<unknown ssid>" -> if(wifiConnected) "Wi-Fi terhubung" else "<unknown ssid>"
                else -> rawSsid.trim('\"')
            }
            val rssi=i.rssi
            val freq=i.frequency
            val band=when{freq in 2400..2500->"2.4 GHz";freq in 4900..5900->"5 GHz";freq>=5925->"6 GHz";else->"-"}
            val ch=wifiChannel(freq)
            val ip=lp?.linkAddresses?.firstOrNull{it.address is Inet4Address}?.address?.hostAddress ?: "-"
            val gateway=lp?.routes?.firstOrNull{it.isDefaultRoute && it.gateway?.isAnyLocalAddress == false}
                ?.gateway?.hostAddress ?: "-"
            val subnetPrefix=lp?.linkAddresses?.firstOrNull{it.address is Inet4Address}?.prefixLength ?: -1
            val subnet=ipv4MaskFromPrefix(subnetPrefix)
            val dns=lp?.dnsServers?.joinToString(", "){it.hostAddress ?: "-"}.orEmpty().ifBlank{"-"}
            val ipv6=lp?.linkAddresses?.firstOrNull{it.address is Inet6Address && !it.address.isLinkLocalAddress}
                ?.address?.hostAddress ?: "-"
            val vals=listOf(ip,gateway,subnet,dns,ipv6)
            rows.forEachIndexed{k,row->(row.getChildAt(1) as? TextView)?.text=vals[k]}
            hero.ssid.text=ssid
            hero.status.text=if(wifiConnected)"●  Terhubung" else "●  Tidak terhubung"
            hero.rssi.text=if(rssi in -100..-1)"$rssi dBm" else "- dBm"
            hero.status.setTextColor(if(wifiConnected)accentTeal() else Color.rgb(211,47,47))
            hero.rssi.setTextColor(if(rssi>=-60)accentTeal() else if(rssi>=-75)Color.rgb(245,166,35) else Color.rgb(211,47,47))
            val linkSpeed=if(i.linkSpeed>=0)"${i.linkSpeed} Mbps" else "-"
            setWifiMetric(hero.linkBox,"Link speed",linkSpeed,"")
            setWifiMetric(hero.freqBox,"Frekuensi",if(freq>0)"$freq MHz" else "-",band)
            setWifiMetric(hero.channelBox,"Channel",if(ch>0)ch.toString() else "-",band)
            if(wifiConnected && rssi in -100..-1){
                wifiHistory.add(rssi.coerceIn(-90,-50))
                if(wifiHistory.size>40)wifiHistory.removeAt(0)
            }
            chart.values=wifiHistory.map{it.toFloat()}
            chart.invalidate()
        }
        val r=object:Runnable{override fun run(){update();modernUiHandler.postDelayed(this,1500)}}
        modernUiRunnable=r
        r.run()
    }

    // ---------- APK / QR / SYSTEM ----------

    internal fun apkInspector() {
        clearPage("APK Inspector")
        content.addView(button("Pilih APK") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type="application/vnd.android.package-archive"; addCategory(Intent.CATEGORY_OPENABLE)
            },1002)
        })
        content.addView(label("Menampilkan daftar isi APK/ZIP. Parsing AndroidManifest binary XML penuh memerlukan parser tambahan."))
    }

    internal fun inspectZipOrApk(uri: Uri) {
        toolThread {
            val sb=StringBuilder()
            contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zis ->
                    var count=0
                    while(true) {
                        val e=zis.nextEntry ?: break
                        sb.append(e.name).append('\n')
                        if(++count>=300) { sb.append("..."); break }
                    }
                }
            }
            runOnUiThread { clearPage("APK Inspector"); output(sb.toString()) }
        }
    }

    // ---------- QR SCANNER (tampilan 3 langkah: awal, pilih sumber, panel sumber) ----------

    internal var qrSourceExpanded = false
    internal var qrSelectedSource: String? = null // "file" | "foto" | "teks"
    internal var qrPickedUri: Uri? = null
    internal var qrPickedName: String? = null
    internal var qrScanBusy = false
    internal var qrScanResult: String? = null
    internal var qrCameraOutUri: Uri? = null
    internal val qrSources = listOf(
        Triple("file", "▤", "File"),
        Triple("foto", "▧", "Foto & Scan"),
        Triple("teks", "✎", "Teks / Link")
    )
    internal fun qrSourceLabel(id: String) = when (id) { "file" -> "File"; "foto" -> "Foto & Scan"; else -> "Teks / Link" }
    internal fun qrSourceIcon(id: String) = when (id) { "file" -> "▤"; "foto" -> "▧"; else -> "✎" }

    internal fun qrTool() {
        clearPage("QR Scanner")
        qrSourceExpanded = false
        qrSelectedSource = null
        qrPickedUri = null
        qrPickedName = null
        qrScanBusy = false
        qrScanResult = null
        renderQrScanner()
    }

    internal fun renderQrScanner() {
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

    internal fun qrHeroBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(34), dp(24), dp(30))
        background = bg(panel2, 18)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        addView(TextView(this@MainActivity).apply {
            text = "⛶"; textSize = 32f; gravity = Gravity.CENTER; setTextColor(textMuted)
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(14) })
        addView(label("QR Scanner", 17f, true).apply { gravity = Gravity.CENTER })
        addView(subLabel("Scan kode QR atau buat QR sendiri.", 12f).apply { gravity = Gravity.CENTER })
    }

    internal fun qrSourceSelectorRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = bg(panel2, 14, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = if (qrSourceExpanded) dp(6) else dp(14) }
        isClickable = true
        setOnClickListener { qrSourceExpanded = !qrSourceExpanded; renderQrScanner() }
        addView(TextView(this@MainActivity).apply {
            text = if (qrSelectedSource == null) "▦" else qrSourceIcon(qrSelectedSource ?: "")
            textSize = 15f; setTextColor(textMuted)
        }, LinearLayout.LayoutParams(dp(24), -2))
        addView(label(if (qrSelectedSource == null) "Pilih sumber input" else qrSourceLabel(qrSelectedSource ?: ""), 14f).apply {
            setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@MainActivity).apply {
            text = if (qrSourceExpanded) "⌃" else "⌄"; textSize = 13f; setTextColor(textMuted)
        })
    }

    internal fun qrSourceOptionsBox(): LinearLayout = LinearLayout(this).apply {
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
            if (i != qrSources.lastIndex) addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(10), dp(2), dp(10), dp(2)) }
                setBackgroundColor(line)
            })
        }
    }

    internal fun qrOptionRow(id: String, icon: String, titleText: String, desc: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isClickable = true
        setOnClickListener {
            qrSelectedSource = id; qrSourceExpanded = false
            qrPickedUri = null; qrPickedName = null; qrScanResult = null; qrScanBusy = false
            renderQrScanner()
        }
        addView(TextView(this@MainActivity).apply {
            text = icon; textSize = 16f; gravity = Gravity.CENTER; setTextColor(textMain)
            background = bg(Color.rgb(235, 236, 239), 10)
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(titleText, 14f, true).apply { setPadding(dp(2), 0, dp(2), dp(1)) })
            addView(subLabel(desc, 11f))
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    internal fun qrSourcePanel(source: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (source == "teks") {
            val input = edit("Masukkan teks atau link…", true)
            addView(input)
            addView(qrDarkButton("Buat QR") {
                val text = input.text.toString().trim()
                if (text.isEmpty()) { toast("Teks / link tidak boleh kosong"); return@qrDarkButton }
                generateQr(this@MainActivity, text)?.let { bmp ->
                    addView(qrResultCard(bmp = bmp, resultText = null, onShareText = { text }))
                } ?: toast("Gagal membuat QR")
            })
        } else {
            addView(qrUploadBox(source))
            addView(TextView(this@MainActivity).apply {
                text = "ⓘ  Format yang didukung: JPG, PNG, WEBP\n    Maksimal ukuran: 10 MB"
                textSize = 11f; setTextColor(textMuted)
                setPadding(dp(2), dp(10), dp(2), dp(4))
            })
            if (qrScanBusy) {
                addView(subLabel("Memindai QR…", 12f))
            } else if (qrPickedUri != null) {
                addView(qrResultCard(bmp = null, resultText = qrScanResult, onShareText = { qrScanResult ?: "" }))
            }
        }
    }

    internal fun qrUploadBox(source: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(30), dp(20), dp(26))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(panel2); cornerRadius = dp(16).toFloat()
            setStroke(dp(1), line)
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        if (qrPickedUri != null) {
            addView(ImageView(this@MainActivity).apply {
                setImageURI(qrPickedUri)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(140), dp(140)).apply { bottomMargin = dp(10) })
            addView(subLabel(qrPickedName ?: "Gambar terpilih", 11f).apply { gravity = Gravity.CENTER })
        } else {
            addView(TextView(this@MainActivity).apply {
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

    internal fun qrDarkButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
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

    internal fun qrResultCard(bmp: Bitmap?, resultText: String?, onShareText: () -> String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = bg(panel2, 14, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        addView(label(if (bmp != null) "QR berhasil dibuat" else if (resultText != null) "Hasil pindaian" else "Tidak terdeteksi kode QR", 14f, true))
        if (bmp != null) {
            // Preview QR mengikuti referensi: kotak putih untuk QR, teks/link tepat di bawahnya.
            val preview = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = bg(Color.WHITE, 2)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(10); bottomMargin = dp(12)
                }
                addView(ImageView(this@MainActivity).apply {
                    setImageBitmap(bmp); adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(250), dp(250)).apply { gravity = Gravity.CENTER })
                addView(subLabel(onShareText(), 13f).apply {
                    setTextColor(Color.rgb(45, 45, 48)); gravity = Gravity.CENTER
                    setPadding(dp(8), dp(10), dp(8), dp(4))
                })
            }
            addView(preview)
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Download") { saveQrBitmap(bmp) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
            row.addView(button("Bagikan") { shareQrBitmap(bmp, onShareText()) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
            addView(row)
        } else if (resultText != null) {
            addView(subLabel(resultText, 13f).apply { setTextColor(textMain); setPadding(dp(2), dp(10), dp(2), dp(10)) })
            addView(qrDarkButton("Bagikan") { shareText(onShareText()) })
        }
    }

    internal fun saveQrBitmap(bitmap: Bitmap) {
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

    internal fun shareQrBitmap(bitmap: Bitmap, text: String) {
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

    internal fun generateQr(ctx: Context, text: String): Bitmap? = runCatching {
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 600, 600)
        val bmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        for (x in 0 until 600) for (y in 0 until 600) bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        bmp
    }.getOrNull()

    internal fun scanQrWithCamera() {
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

    internal fun pickQrFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
        }, 1041)
    }

    internal fun pickQrPhoto() {
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

    internal fun decodeQrFromUri(uri: Uri) {
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
            runOnUiThread {
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

    internal fun webProjectBuilderTool() {
        // ---------- WEB PROJECT BUILDER ----------

        fun sectionTitle(textValue: String, icon: String): View = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(8))
            addView(MdiIconView(this@MainActivity).apply {
                setIconName(icon); setIconSize(20f); setTextColor(textMain)
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(6) }
            })
            addView(TextView(this@MainActivity).apply {
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
        introRow.addView(MdiIconView(this@MainActivity).apply {
            setIconName("web"); setIconSize(30f); setTextColor(textMain)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) }
        })
        val introText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        introText.addView(TextView(this@MainActivity).apply {
            text = "Web Project Builder"; textSize = 17f; setTextColor(textMain)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        introText.addView(TextView(this@MainActivity).apply {
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

    internal fun pickWebFile(target: EditText, requestCode: Int) {
        webImportTarget = target
        val type = when(requestCode) { WEB_HTML_PICK_REQUEST -> "text/html"; WEB_CSS_PICK_REQUEST -> "text/css"; else -> "text/javascript" }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { this.type=type; addCategory(Intent.CATEGORY_OPENABLE) }, requestCode)
    }

    internal fun webCodeEditor(mode: String = "HTML", target: EditText? = null) {
        val normalized = when (mode.uppercase(Locale.getDefault())) {
            "HTML" -> "html"
            "CSS" -> "css"
            "JAVASCRIPT", "JS" -> "js"
            else -> "code"
        }
        editorExternalTarget = target
        editorExternalMode = normalized
        editorFile = null
        editor(null, normalized)
    }

    internal fun buildWebProject(projectName:String, html:String, css:String, js:String) {
        val h=html.trim(); val c=css.trim(); val j=js.trim()
        webBuildReady=false
        webHostButton?.isEnabled=false; webHostButton?.alpha=0.45f
        webBuildStatusView?.text="MEMERIKSA FILE…"
        if (h.isBlank()) { webBuildStatusView?.text="GAGAL • HTML wajib diisi"; toast("HTML wajib diisi"); return }
        if (h.isBlank() && (c.isNotBlank() || j.isNotBlank())) { webBuildStatusView?.text="GAGAL • HTML wajib ada"; return }

        val localCss=Regex("""(?i)(?:href|src)\s*=\s*[\"']([^\"']+\.css(?:\?[^\"']*)?)[\"']""").findAll(h).map { it.groupValues[1].substringBefore('?') }.filter { !it.startsWith("http://") && !it.startsWith("https://") && !it.startsWith("//") && !it.startsWith("data:") }.toList()
        val localJs=Regex("""(?i)<script[^>]+src\s*=\s*[\"']([^\"']+\.js(?:\?[^\"']*)?)[\"']""").findAll(h).map { it.groupValues[1].substringBefore('?') }.filter { !it.startsWith("http://") && !it.startsWith("https://") && !it.startsWith("//") && !it.startsWith("data:") }.toList()
        val warnings=mutableListOf<String>()
        if (localCss.isNotEmpty() && c.isBlank()) warnings.add("HTML memanggil CSS lokal: ${localCss.joinToString(", ")}, tetapi file CSS belum diisi.")
        if (localJs.isNotEmpty() && j.isBlank()) warnings.add("HTML memanggil JavaScript lokal: ${localJs.joinToString(", ")}, tetapi file JS belum diisi.")
        if (warnings.isNotEmpty()) {
            webBuildStatusView?.text="GAGAL • Dependency belum lengkap"
            AlertDialog.Builder(this).setTitle("Project belum lengkap").setMessage(warnings.joinToString("\n\n") + "\n\nIsi file yang kurang lalu Build lagi.").setPositiveButton("OK",null).show()
            return
        }

        val safe=(projectName.trim().ifBlank{"website"}).replace(Regex("[^A-Za-z0-9_-]"),"_")
        val dir=File(filesDir,"web_projects/$safe").apply { mkdirs() }
        runCatching {
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
            File(dir,"index.html").writeText(htmlBody, StandardCharsets.UTF_8)
            if (c.isNotBlank()) {
                File(dir,"style.css").writeText(c, StandardCharsets.UTF_8)
                localCss.map { File(it).name }.filter { it.isNotBlank() && it != "style.css" }.distinct().forEach { File(dir,it).writeText(c, StandardCharsets.UTF_8) }
            } else File(dir,"style.css").delete()
            if (j.isNotBlank()) {
                File(dir,"script.js").writeText(j, StandardCharsets.UTF_8)
                localJs.map { File(it).name }.filter { it.isNotBlank() && it != "script.js" }.distinct().forEach { File(dir,it).writeText(j, StandardCharsets.UTF_8) }
            } else File(dir,"script.js").delete()
            prefs.edit().putString("last_web_project",dir.absolutePath).putBoolean("last_web_build_ok",true).apply()
            webBuildReady=true
            webBuildStatusView?.text="SUCCESS • BUILD BERHASIL • ${dir.name}"
            webHostButton?.isEnabled=true; webHostButton?.alpha=0.98f
            toast("Build berhasil: ${dir.name}")
        }.onFailure {
            prefs.edit().putBoolean("last_web_build_ok",false).apply()
            webBuildStatusView?.text="GAGAL • ${it.message ?: "kesalahan build"}"
            toast("Build gagal: ${it.message ?: "kesalahan file"}")
        }
    }

    internal fun previewWebProject() {
        val path=prefs.getString("last_web_project","") ?: ""
        if(path.isBlank() || !File(path,"index.html").isFile || !prefs.getBoolean("last_web_build_ok",false)){toast("Build website dulu sampai SUCCESS");return}
        previewHtmlText(File(path,"index.html").readText(StandardCharsets.UTF_8),"HTML")
    }
    internal fun previewHtmlText(html: String, mode: String) {
        clearPage("Preview")
        content.setPadding(0, 0, 0, 0)
        editorBottomBar.visibility = View.GONE
        appendEditorConsole("Preview dibuka (${mode})")
        val w = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
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
                    appendEditorConsole("ERROR: page $description ($errorCode)")
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    appendEditorConsole("Page finished")
                }
            }
            val body = if (mode == "HTML") html else "<pre>${html.htmlEsc()}</pre>"
            loadDataWithBaseURL(null, body, "text/html", "UTF-8", null)
        }
        content.addView(w, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(button("Buka Console") { showEditorConsole() }, LinearLayout.LayoutParams(-1, dp(48)).apply {
            setMargins(dp(12), dp(8), dp(12), dp(12))
        })
    }
    internal fun hostWebProject() = hostHomeWifiProject()
    internal fun hostHomeWifiProject() {
        val path=prefs.getString("last_web_project","") ?: ""
        if(path.isBlank() || !File(path,"index.html").isFile || !prefs.getBoolean("last_web_build_ok",false)) { toast("Build harus SUCCESS sebelum hosting"); return }
        wifiHtmlHostingTool()
    }
    internal fun openWebFolder() { val p=prefs.getString("last_web_project","") ?: ""; if(p.isBlank()){toast("Belum ada project");return}; clearPage("Project Files"); File(p).listFiles()?.forEach{content.addView(settingRowClickable(it.name, "${it.length()} bytes", "File project", "file-outline"){ if(it.extension.equals("html",true)||it.extension.equals("htm",true)) previewHtmlText(it.readText(StandardCharsets.UTF_8),"HTML") else output(it.readText(StandardCharsets.UTF_8)) })} }
    internal fun saveWebEditor(mode:String,text:String){ editorPendingTarget?.setText(text); val ext=when(mode){"HTML"->"html";"CSS"->"css";"JavaScript"->"js";else->"txt"}; val f=File(filesDir,"web_editor");f.mkdirs();File(f,"untitled.$ext").writeText(text);toast("Disimpan: untitled.$ext") }
    internal fun findInEditor(e:EditText){ val q=EditText(this); q.hint="Cari"; AlertDialog.Builder(this).setTitle("Cari").setView(q).setPositiveButton("Cari"){_,_->val i=e.text.toString().indexOf(q.text.toString()); if(i>=0){e.requestFocus();e.setSelection(i,i+q.text.length)}else toast("Tidak ditemukan")}.setNegativeButton("Batal",null).show() }
    internal fun applySimpleEmmet(e: EditText) {
        val t = e.text.toString().trim()
        val x = when (t) {
            "!" -> "<!doctype html>\n<html>\n<head><meta charset=\"UTF-8\"></head>\n<body>\n</body>\n</html>"
            "div" -> "<div></div>"
            "p" -> "<p></p>"
            "h1" -> "<h1></h1>"
            "h2" -> "<h2></h2>"
            "button" -> "<button></button>"
            "img" -> "<img src=\"\" alt=\"\">"
            "a" -> "<a href=\"\"></a>"
            "ul" -> "<ul>\n  <li></li>\n</ul>"
            else -> null
        }
        if (x != null) e.setText(x) else toast("Emmet: gunakan !, div, p, h1, h2, button, img, a, ul")
    }
    internal fun openTextFileIntoEditor(e:EditText){ val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="text/*";addCategory(Intent.CATEGORY_OPENABLE)}; startActivityForResult(i,9811); editorPendingTarget=e }
    internal fun String.htmlEsc()=replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")

    // ---------- V2.27: WORKSPACE / PLUGIN / CUSTOMIZATION ----------
    internal fun workspaceRoot(): File = File(filesDir, "workspaces").apply { mkdirs() }

    internal fun workspaceCard(dir: File): View {
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

    internal fun workspaceDetailTool(dir: File) {
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

    internal fun pluginCenterTool() {
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

    internal fun toolCustomizationTool() {
        clearPage("Tool Customization")
        content.addView(label("Tool Customization", 22f, true))
        content.addView(subLabel("Pin tool ke Beranda atau sembunyikan tool tertentu. Pengaturan disimpan lokal.", 12f))
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

    internal fun studioCenterTool() {
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

    internal fun studioHub(titleText:String, subtitleText:String, tools:List<Pair<String,String>>){ clearPage(titleText); content.addView(subLabel(subtitleText,13f)); tools.forEach{(n,id)->content.addView(settingRowClickable(n,"Buka tool", "", iconFor(id)){openTool(id)})} }
    internal fun networkStudioTool(){ studioHub("Network Studio","Semua alat jaringan dalam satu workspace.",listOf("Ping" to "ping","Port Checker" to "port","DNS Lookup" to "dns","Reverse DNS" to "rdns","Whois" to "whois","Traceroute" to "traceroute","HTTP Headers" to "httpheaders","SSL Certificate" to "ssl","Network Scanner" to "netscanner","Subnet Calculator" to "subnetcalc")) }
    internal fun developerStudioTool(){ studioHub("Developer Studio","Editor dan formatter untuk developer.",listOf("Web Project Builder" to "webproject","JSON Formatter" to "jsonformat","XML Formatter" to "xmlformat","Regex Tester" to "regex","Timestamp Converter" to "timestamp","Base64" to "base64","JWT Decoder" to "jwt","UUID Generator" to "uuid","Hash Generator" to "hash")) }
    internal fun systemStudioTool(){ studioHub("System Studio","Informasi perangkat dan sistem.",listOf("Device & System" to "devicecenter","Storage Analyzer" to "storage","Network Info" to "network","App Manager" to "apps")) }
    internal fun financeStudioTool(){ studioHub("Finance Studio","Kalkulator dan dashboard keuangan.",listOf("Kalkulator Lengkap" to "number","Finance Dashboard" to "financedashboard","Pengelola Keuangan" to "financereader","Pivot Point" to "pivotcalc","Averaging Down & DCA" to "dcacalc","Voltage Divider" to "dividercalc","PWM" to "pwmcalc","Konsumsi Listrik" to "powercalc")) }
    internal fun utilityStudioTool(){ studioHub("Utility Studio","Utilitas sehari-hari.",listOf("Calculator" to "number","Clipboard Manager" to "clipboard","Unit Converter" to "unitconverter","QR Scanner" to "qr","OCR" to "ocr","Password Generator" to "password","Notes / Notifikasi" to "reminder")) }
    internal fun systemInfo() {
        clearPage("Sistem")
        modernToolListRow("Sistem", "Informasi dan pengaturan sistem", "cog-outline") { showSystemDetailMenu() }
        modernToolListRow("System Center", "Kontrol layanan sistem", "view-dashboard-outline") { systemCenterTool() }
        modernToolListRow("Device Info", "Informasi perangkat lengkap", "cellphone-information") { deviceInfoTool() }
        modernToolListRow("Battery Info", "Status dan statistik baterai", "battery-high") { batteryInfoTool() }
        modernToolListRow("Wi-Fi Info", "Jaringan dan koneksi Wi-Fi", "wifi") { wifiInfo() }
    }

    internal fun showSystemDetailMenu() {
        // clearPage() already owns the global toolbar/back navigation.
        // Do not add another page title with a second back button here;
        // that created a duplicated/nested header on the System detail screen.
        clearPage("Sistem")
        val headerSpacer = Space(this)
        content.addView(headerSpacer, LinearLayout.LayoutParams(1, dp(10)))
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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

    internal fun httpServer() {
        clearPage("HTTP Server")
        addToolHeader("HTTP Server", "Server HTTP lokal untuk file/project web.", "WEB")
        val root = File(prefs.getString("last_web_project", "") ?: "")
        val port = edit("Port", false).apply { setText("8080") }
        content.addView(port)
        val path = label(if (root.isDirectory) "Root: ${root.absolutePath}" else "Root belum dipilih", 12f)
        content.addView(path)
        val status = label(if (server != null && !(server?.isClosed ?: true)) "RUNNING" else "STOPPED", 16f, true)
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
            startStaticWebServer(p, prt, status)
        })
        content.addView(button("Stop Server") {
            stopStaticWebServer()
            status.text = "STOPPED"
        })
        content.addView(subLabel("Melayani index.html, CSS, JS, gambar, font, JSON, SVG, dan file project lain. Path traversal di luar folder project ditolak.", 11f))
    }

    internal fun startStaticWebServer(root: File, port: Int, status: TextView? = null): Boolean {
        if (server != null && !(server?.isClosed ?: true)) {
            toast("Server sudah berjalan")
            return false
        }
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: run {
            toast("Folder project tidak valid")
            return false
        }
        val socket = runCatching { ServerSocket(port) }.getOrElse {
            toast("Port $port gagal dibuka: ${it.message}")
            return false
        }
        server = socket
        status?.text = "RUNNING :$port"
        thread(name = "mytools-http-$port") {
            try {
                while (!socket.isClosed) {
                    val client = socket.accept()
                    thread(name = "mytools-http-client") { serveStaticClient(client, canonicalRoot) }
                }
            } catch (_: SocketException) {
                // Normal when Stop closes the ServerSocket.
            } catch (t: Throwable) {
                runOnUiThread { status?.text = "ERROR: ${t.message}" }
            } finally {
                runOnUiThread {
                    if (server === socket) {
                        server = null
                        if (status != null) status.text = "STOPPED"
                    }
                }
                runCatching { socket.close() }
            }
        }
        return true
    }

    internal fun serveStaticClient(socket: Socket, root: File) {
        socket.soTimeout = 8000
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            var headerCount = 0
            while (headerCount++ < 100) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeHttpResponse(socket, 400, "text/plain; charset=utf-8", "Bad Request")
                return
            }
            val method = parts[0].toUpperCase(Locale.US)
            if (method != "GET" && method != "HEAD") {
                writeHttpResponse(socket, 405, "text/plain; charset=utf-8", "Method Not Allowed", method == "HEAD")
                return
            }
            val rawPath = runCatching { URLDecoder.decode(parts[1].substringBefore('?'), "UTF-8") }.getOrElse { "/" }
            val relative = rawPath.removePrefix("/").ifBlank { "index.html" }
            val requested = File(root, relative).canonicalFile
            if (requested != root && !requested.path.startsWith(root.path + File.separator)) {
                writeHttpResponse(socket, 403, "text/plain; charset=utf-8", "Forbidden", method == "HEAD")
                return
            }
            val file = if (requested.isDirectory) File(requested, "index.html") else requested
            if (!file.isFile) {
                writeHttpResponse(socket, 404, "text/plain; charset=utf-8", "Not Found", method == "HEAD")
                return
            }
            val bytes = file.readBytes()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.toLowerCase(Locale.US))
                ?: when (file.extension.toLowerCase(Locale.US)) {
                    "html", "htm" -> "text/html"
                    "css" -> "text/css"
                    "js", "mjs" -> "text/javascript"
                    "json" -> "application/json"
                    "svg" -> "image/svg+xml"
                    "wasm" -> "application/wasm"
                    else -> "application/octet-stream"
                }
            writeHttpResponse(socket, 200, "$mime; charset=utf-8", bytes, method == "HEAD")
        } catch (_: Throwable) {
            runCatching { writeHttpResponse(socket, 500, "text/plain; charset=utf-8", "Server Error") }
        } finally {
            runCatching { socket.close() }
        }
    }

    internal fun writeHttpResponse(socket: Socket, code: Int, contentType: String, body: String, headOnly: Boolean = false) =
        writeHttpResponse(socket, code, contentType, body.toByteArray(StandardCharsets.UTF_8), headOnly)

    internal fun writeHttpResponse(socket: Socket, code: Int, contentType: String, body: ByteArray, headOnly: Boolean = false) {
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Internal Server Error"
        }
        val out = socket.getOutputStream()
        val header = "HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        if (!headOnly) out.write(body)
        out.flush()
    }

    internal fun stopStaticWebServer() {
        val old = server
        server = null
        runCatching { old?.close() }
    }

    internal fun wifiHtmlHostingTool() {
        clearPage("HTML Hosting Wi-Fi")
        addToolHeader("HTML Hosting Wi-Fi", "Host website di Wi-Fi rumah yang sedang dipakai HP.", "WiFi")
        val project = File(prefs.getString("last_web_project", "") ?: "")
        content.addView(label(if (project.isDirectory && File(project, "index.html").isFile) "Project: ${project.name}" else "Belum ada project", 13f, true))
        val network = label("Jaringan: ${currentWifiSsid()}", 13f)
        content.addView(network)
        val port = edit("Port", false).apply { setText("8080") }
        content.addView(port)
        val status = label(if (server != null && !(server?.isClosed ?: true)) "RUNNING" else "STOPPED", 16f, true)
        hostingStatusView=status; content.addView(status)
        val credentials=label("SSID: ${currentWifiSsid()}\nPassword Wi-Fi: tidak diperlukan oleh server",13f); hostingCredentialsView=credentials; content.addView(credentials)
        val url=label("URL: -",13f,true); hostingUrlView=url; content.addView(url)
        val qr=ImageView(this).apply { background = bg(Color.WHITE, 16); clipToOutline = true; visibility=View.GONE; scaleType=ImageView.ScaleType.CENTER_INSIDE; layoutParams=LinearLayout.LayoutParams(dp(220),dp(220)).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=dp(10);bottomMargin=dp(10)} }; hostingQrView=qr; content.addView(qr)
        content.addView(button("START HOSTING WI-FI RUMAH") {
            val p=File(prefs.getString("last_web_project","") ?: "")
            if(!p.isDirectory || !File(p,"index.html").isFile || !prefs.getBoolean("last_web_build_ok",false)){toast("Build website sampai SUCCESS dulu");return@button}
            val prt=port.text.toString().toIntOrNull()?.takeIf{it in 1024..65535} ?: run{toast("Port harus 1024-65535");return@button}
            pendingHostingPort=prt; pendingHostingRoot=p; startHomeWifiHosting()
        })
        content.addView(button("STOP HOSTING") { stopWifiHtmlHosting() })
        content.addView(button("COPY URL") { val text=hostingUrlView?.text?.toString()?.substringAfter("URL: ")?.lineSequence()?.firstOrNull()?.trim().orEmpty(); if(text.isBlank()||text=="-") toast("Hosting belum aktif") else copyText(text) })
        content.addView(subLabel("Semua perangkat harus terhubung ke Wi-Fi rumah yang sama. Password Wi-Fi rumah tetap dikelola router/Android dan tidak disimpan MyTools. Hanya file project hasil Build yang dilayani.",11f))
    }

    internal fun startHomeWifiHosting() {
        val root=pendingHostingRoot ?: File(prefs.getString("last_web_project","") ?: "")
        val port=pendingHostingPort
        if(!root.isDirectory || !File(root,"index.html").isFile){toast("Project tidak valid");return}
        stopWifiHtmlHosting()
        hostingStatusView?.text="MEMULAI SERVER…"
        if(!startStaticWebServer(root,port,hostingStatusView)){return}
        val addresses=localIpv4Addresses()
        val host=wifiIpv4Address() ?: addresses.firstOrNull { !it.startsWith("127.") } ?: ""
        if(host.isBlank()) {
            stopStaticWebServer(); hostingStatusView?.text="GAGAL • HP tidak terhubung ke Wi-Fi"; toast("Hubungkan HP ke Wi-Fi rumah dulu"); return
        }
        val link="http://$host:$port/"
        hostingUrlView?.text="URL: $link\nAlamat lain: ${addresses.drop(1).joinToString(", ").ifBlank{"-"}}"
        hostingCredentialsView?.text="SSID: ${currentWifiSsid()}\nPassword Wi-Fi: perangkat lain harus sudah terhubung ke Wi-Fi yang sama"
        hostingStatusView?.text="SUCCESS • HOSTING AKTIF"
        hostingQrView?.visibility=View.VISIBLE
        generateHostingQr(link)
        toast("Hosting berhasil • buka URL dari perangkat lain")
    }

    internal fun wifiIpv4Address(): String? {
        return runCatching {
            val all=NetworkInterface.getNetworkInterfaces()
            while(all.hasMoreElements()) {
                val ni=all.nextElement()
                val name=ni.name?.lowercase(Locale.US).orEmpty()
                if(!ni.isUp || ni.isLoopback || !(name.contains("wlan") || name.contains("wifi"))) continue
                val addrs=ni.inetAddresses
                while(addrs.hasMoreElements()) {
                    val a=addrs.nextElement()
                    if(a is Inet4Address && !a.isLoopbackAddress) return@runCatching a.hostAddress
                }
            }
            null
        }.getOrNull()
    }

    internal fun currentWifiSsid(): String {
        return runCatching {
            val wm=applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION") val ssid=wm.connectionInfo?.ssid?.trim('"')
            if(ssid.isNullOrBlank() || ssid=="<unknown ssid>") "SSID tidak tersedia" else ssid
        }.getOrDefault("SSID tidak tersedia")
    }

    internal fun ensureHotspotPermissionAndStart() { startHomeWifiHosting() }

    internal fun startWifiHtmlHosting() { startHomeWifiHosting() }

    internal fun stopWifiHtmlHosting() {
        runCatching { hotspotReservation?.close() }; hotspotReservation=null
        stopStaticWebServer()
        hostingStatusView?.text="STOPPED"
        hostingUrlView?.text="URL: -"
        hostingCredentialsView?.text="SSID: -\nPassword Wi-Fi: -"
        hostingQrView?.visibility=View.GONE
    }

    internal fun localIpv4Addresses(): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            val all = NetworkInterface.getNetworkInterfaces()
            while (all.hasMoreElements()) {
                val ni = all.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        if (!out.contains(ip)) out.add(ip)
                    }
                }
            }
        }
        return out
    }

    internal fun generateHostingQr(url: String) {
        runCatching {
            val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 600, 600)
            val bmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
            for (x in 0 until 600) for (y in 0 until 600) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
            hostingQrView?.setImageBitmap(bmp)
        }
    }

    // ---------- NEW TOOLS 2.0 ----------

    internal fun timestampTool() {
        clearPage("Timestamp Converter")
        val e=edit("Unix timestamp atau tanggal ISO"); content.addView(e)
        content.addView(button("Sekarang") { output("Unix seconds: ${System.currentTimeMillis()/1000}\nUnix millis: ${System.currentTimeMillis()}\nLocal: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}") })
        content.addView(button("Timestamp → Tanggal") {
            output(runCatching {
                val raw=e.text.toString().trim(); val ms=if(raw.length>10) raw.toLong() else raw.toLong()*1000
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(ms))
            }.getOrElse { "Timestamp tidak valid" })
        })
        content.addView(button("Tanggal → Timestamp") {
            output(runCatching {
                val formats=listOf("yyyy-MM-dd HH:mm:ss","yyyy-MM-dd HH:mm:ss.SSS","yyyy-MM-dd'T'HH:mm:ss","yyyy-MM-dd")
                val d=formats.asSequence().mapNotNull { f -> runCatching { SimpleDateFormat(f, Locale.getDefault()).apply { isLenient=false }.parse(e.text.toString().trim()) }.getOrNull() }.firstOrNull() ?: error("Format tidak dikenali")
                "Unix seconds: ${d.time/1000}\nUnix millis: ${d.time}"
            }.getOrElse { "Tanggal tidak valid: ${it.message}" })
        })
    }

    internal fun unicodeTool() {
        clearPage("Unicode Inspector")
        val e=edit("Teks", true); content.addView(e)
        content.addView(button("Inspect") {
            val s=e.text.toString(); val sb=StringBuilder()
            var offset = 0
            var index = 0
            while (offset < s.length) {
                val cp = s.codePointAt(offset)
                val ch = String(Character.toChars(cp))
                sb.append(index).append("  ").append(ch).append("  U+")
                    .append(cp.toString(16).toUpperCase(Locale.getDefault()).padStart(4,'0'))
                    .append("  ").append(Character.getName(cp) ?: "UNKNOWN").append('\n')
                offset += Character.charCount(cp)
                index++
            }
            output(if(sb.isEmpty()) "Tidak ada karakter." else sb.toString())
        })
        content.addView(button("Text → \\uXXXX") {
            val s = e.text.toString()
            val sb = StringBuilder()
            var offset = 0
            while (offset < s.length) {
                val cp = s.codePointAt(offset)
                sb.append("\\u").append(String.format(Locale.US, "%04X", cp))
                offset += Character.charCount(cp)
            }
            output(sb.toString())
        })
    }

    internal fun urlParserTool() {
        clearPage("URL Parser")
        val e=edit("https://example.com/path?a=1#section"); content.addView(e)
        content.addView(button("Parse") {
            output(runCatching {
                val u=URL(e.text.toString().trim())
                "Protocol: ${u.protocol}\nHost: ${u.host}\nPort: ${if(u.port==-1) "default" else u.port}\nPath: ${u.path}\nQuery: ${u.query ?: ""}\nFragment: ${u.ref ?: ""}\nUserInfo: ${u.userInfo ?: ""}"
            }.getOrElse { "URL tidak valid: ${it.message}" })
        })
    }

    internal fun mimeTool() {
        clearPage("MIME Type Lookup")
        val e=edit("nama file, contoh photo.png"); content.addView(e)
        content.addView(button("Lookup") {
            val ext=e.text.toString().substringAfterLast('.',"").toLowerCase(Locale.getDefault())
            output(if(ext.isEmpty()) "Ekstensi tidak ditemukan" else "Extension: .$ext\nMIME: ${MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"}")
        })
    }

    internal fun prettyJson(s:String):String {
        val t=s.trim()
        return if(t.startsWith("{")) JSONObject(t).toString(4) else JSONArray(t).toString(4)
    }

    internal fun minifyJson(s:String):String {
        val t=s.trim()
        return if(t.startsWith("{")) JSONObject(t).toString() else JSONArray(t).toString()
    }

    internal fun jsonFormatTool() {
        clearPage("JSON Formatter")
        val e=edit("JSON",true); content.addView(e)
        content.addView(button("Pretty") { output(runCatching { prettyJson(e.text.toString()) }.getOrElse { "JSON error: ${it.message}" }) })
        content.addView(button("Minify") { output(runCatching { minifyJson(e.text.toString()) }.getOrElse { "JSON error: ${it.message}" }) })
    }

    internal fun xmlFormatTool() {
        clearPage("XML Formatter")
        val e=edit("XML",true); content.addView(e)
        content.addView(button("Format XML") {
            output(runCatching {
                val f=javax.xml.transform.TransformerFactory.newInstance().newTransformer().apply {
                    setOutputProperty(javax.xml.transform.OutputKeys.INDENT,"yes")
                    setOutputProperty("{http://xml.apache.org/xslt}indent-amount","2")
                }
                val sw=StringWriter(); f.transform(javax.xml.transform.stream.StreamSource(StringReader(e.text.toString())), javax.xml.transform.stream.StreamResult(sw)); sw.toString()
            }.getOrElse { "XML error: ${it.message}" })
        })
    }

    internal fun uuidBatchTool() {
        clearPage("UUID Batch Generator")
        val n=edit("Jumlah UUID (1-100)"); n.setText("10"); content.addView(n)
        content.addView(button("Generate") {
            val count=(n.text.toString().toIntOrNull() ?: 10).coerceIn(1,100)
            output((1..count).joinToString("\n") { UUID.randomUUID().toString() })
        })
    }

    internal fun base64FileTool() {
        clearPage("Base64 File Tool")
        content.addView(button("Encode File → Base64") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 1010)
        })
        content.addView(label("Pilih file untuk membaca Base64. File besar diproses dengan batas 8 MB untuk menjaga RAM."))
    }

    internal fun httpHeadersTool() {
        clearPage("HTTP Headers")
        val e=edit("https://example.com"); content.addView(e)
        content.addView(button("GET Headers") {
            toolThread {
                val result=runCatching {
                    val c=(URL(e.text.toString()).openConnection() as HttpURLConnection).apply { requestMethod="HEAD"; connectTimeout=7000; readTimeout=7000; instanceFollowRedirects=true }
                    c.connect(); val sb=StringBuilder("Status: ${c.responseCode} ${c.responseMessage}\n")
                    c.headerFields.forEach { (k,v) -> if(k!=null) sb.append(k).append(": ").append(v.joinToString(", ")).append('\n') }
                    c.disconnect(); sb.toString()
                }.getOrElse { "HTTP error: ${it.message}" }
                runOnUiThread { output(result) }
            }
        })
    }

    internal fun textReplaceTool() {
        clearPage("Find & Replace")
        val text=edit("Teks",true); val find=edit("Cari"); val repl=edit("Ganti dengan")
        content.addView(text); content.addView(find); content.addView(repl)
        content.addView(button("Replace All") { output(text.text.toString().replace(find.text.toString(),repl.text.toString())) })
    }

    internal fun wordFrequencyTool() {
        clearPage("Word Frequency")
        val e=edit("Teks",true); content.addView(e)
        content.addView(button("Analyze") {
            val map=e.text.toString().toLowerCase(Locale.getDefault()).split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            output(if(map.isEmpty()) "Tidak ada kata." else map.take(100).joinToString("\n") { "${it.key}: ${it.value}" })
        })
    }

    // ---------- CRYPTO / HELPERS ----------

    internal fun digest(alg:String, bytes:ByteArray):String =
        MessageDigest.getInstance(alg).digest(bytes).joinToString("") { "%02x".format(it) }

    internal fun randomString(n:Int, chars:String):String {
        val r=SecureRandom(); return buildString { repeat(n) { append(chars[r.nextInt(chars.length)]) } }
    }

    internal fun randomBytes(n:Int):String {
        val b=ByteArray(n); SecureRandom().nextBytes(b); return b.joinToString("") { "%02x".format(it) }
    }

    internal fun decodeB64Url(s:String):String =
        runCatching { String(Base64.getUrlDecoder().decode(s.padEnd((s.length+3)/4*4,'=')), StandardCharsets.UTF_8) }.getOrElse { "decode error" }

    internal fun totp(secret:String,counter:Long):String {
        val key=Base32.decode(secret)
        val data=ByteArray(8)
        for(i in 7 downTo 0) data[i]=(counter ushr (8*(7-i))).toByte()
        val mac=Mac.getInstance("HmacSHA1"); mac.init(SecretKeySpec(key,"HmacSHA1"))
        val h=mac.doFinal(data); val o=h.last().toInt() and 15
        var v=0
        for(i in 0..3) v=(v shl 8) or (h[o+i].toInt() and 255)
        return "%06d".format((v and 0x7fffffff)%1000000)
    }

    internal fun aesKey(pass:String):ByteArray =
        MessageDigest.getInstance("SHA-256").digest(pass.toByteArray(StandardCharsets.UTF_8))

    internal fun aesKeyV2(pass:String, salt:ByteArray):ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(pass.toCharArray(), salt, 120_000, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    internal fun aesEncrypt(pass:String, plain:String):String {
        require(pass.isNotEmpty()) { "Password kosong" }
        val salt=ByteArray(16); val iv=ByteArray(12); SecureRandom().nextBytes(salt); SecureRandom().nextBytes(iv)
        val c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyV2(pass,salt),"AES"), GCMParameterSpec(128,iv))
        val enc=c.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        return "MYTOOLS-AES2:" + Base64.getEncoder().encodeToString(salt+iv+enc)
    }

    internal fun aesDecrypt(pass:String, encoded:String):String {
        if (encoded.startsWith("MYTOOLS-AES2:")) {
            val all=Base64.getDecoder().decode(encoded.removePrefix("MYTOOLS-AES2:"))
            require(all.size > 28) { "Data AES2 tidak lengkap" }
            val salt=all.copyOfRange(0,16); val iv=all.copyOfRange(16,28); val enc=all.copyOfRange(28,all.size)
            val c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyV2(pass,salt),"AES"), GCMParameterSpec(128,iv))
            return String(c.doFinal(enc), StandardCharsets.UTF_8)
        }
        // Compatibility with the previous MyTools AES format.
        val all=Base64.getDecoder().decode(encoded)
        require(all.size > 12) { "Data AES lama tidak lengkap" }
        val iv=all.copyOfRange(0,12); val enc=all.copyOfRange(12,all.size)
        val c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, SecretKeySpec(aesKey(pass),"AES"), GCMParameterSpec(128,iv))
        return String(c.doFinal(enc), StandardCharsets.UTF_8)
    }


    // ---------- COMPLETE TOOL EXPANSION ----------

    internal fun filePickCard(titleText: String, actionText: String, onClick: () -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = bg(panel2, Ds.RADIUS_LG, line)
        }
        val icon = MdiIconView(this).apply {
            setIconName("file-outline"); setIconSize(22f); setTextColor(textMain)
            background = bg(panel, Ds.RADIUS_MD, line)
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        val labelView = label(titleText, 14f, true).apply { tag = "fileLabel" }
        card.addView(labelView, LinearLayout.LayoutParams(0, -2, 1f))
        val action = TextView(this).apply {
            text = actionText; textSize = 12f; gravity = Gravity.CENTER; minHeight = dp(40); setPadding(dp(10), 0, dp(10), 0)
            styleAsSecondary(this); isClickable = true; setOnClickListener { onClick() }
        }
        card.addView(action, LinearLayout.LayoutParams(dp(110), -2))
        card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        return card
    }

    internal var fileHashUriA: Uri? = null
    internal var fileHashUriB: Uri? = null
    internal var fileHashCompareLabelA: TextView? = null
    internal var fileHashCompareLabelB: TextView? = null

    internal fun digestStream(input: InputStream, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            md.update(buffer, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    internal var securityFileUri: Uri? = null
    internal var securityFileName: String? = null

    // State enkripsi file (dipakai SecurityTools.kt)
    internal var encStage: String = "form"
    internal var encProgressPct: Int = 0
    internal var encResultPath: String? = null
    internal var encResultSize: Long = 0L
    internal var encErrorMessage: String = ""
    internal val encCancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    internal var encModeEncrypt: Boolean = true
    internal var encPassword: String = ""
    internal var encShowPassword: Boolean = false
    internal var encOutSameAsSource: Boolean = true
    internal var encOverwrite: Boolean = false
    internal var encDeleteSource: Boolean = false
    internal var encCompress: Boolean = false
    internal var stegoImageUri: Uri? = null
    internal var certFileUri: Uri? = null
    internal val SECURITY_FILE_PICK = 1301
    internal val SECURITY_OPEN_PICK = 1305
    internal var lockedOpenPassword: String? = null
    internal val STEGO_ENCODE_PICK = 1302
    internal val STEGO_DECODE_PICK = 1303
    internal val CERT_PICK = 1304

    /** Daftar file .mytools.enc hasil enkripsi di aplikasi + opsi memilih dari penyimpanan. */
    internal fun showLockedFilePicker(password: String) {
        val local = filesDir.listFiles { f -> f.isFile && f.name.endsWith(".mytools.enc") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val items = arrayOf("Pilih dari penyimpanan...") + local.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Buka file terkunci")
            .setItems(items) { _, i ->
                if (i == 0) {
                    lockedOpenPassword = password
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    }, SECURITY_OPEN_PICK)
                } else {
                    val f = local[i - 1]
                    openLockedFile(f.name, password) { FileInputStream(f) }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    internal fun displayNameOf(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    /** Dekripsi ke cache lalu buka dengan aplikasi yang cocok (tipe dari ekstensi asli). */
    internal fun openLockedFile(displayName: String, password: String, source: () -> InputStream?) {
        toast("Membuka file...")
        toolThread {
            val result = runCatching {
                val all = (source() ?: error("File tidak bisa dibuka")).use { it.readBytes() }
                val head = "MYTOOLS-FILE-AES2".toByteArray(StandardCharsets.US_ASCII)
                require(all.size > head.size + 28 && all.copyOfRange(0, head.size).contentEquals(head)) { "Format file tidak dikenali" }
                val salt = all.copyOfRange(head.size, head.size + 16)
                val iv = all.copyOfRange(head.size + 16, head.size + 28)
                val enc = all.copyOfRange(head.size + 28, all.size)
                val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                c.init(javax.crypto.Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyV2(password, salt), "AES"), GCMParameterSpec(128, iv))
                val plain = c.doFinal(enc)
                val base = displayName.substringAfterLast('/')
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .removeSuffix(".mytools.enc").ifBlank { "file" }
                val dir = File(cacheDir, "opened").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                File(dir, base).also { f -> FileOutputStream(f).use { it.write(plain) } }
            }
            runOnUiThread {
                result.onSuccess { launchDecryptedViewer(it) }
                    .onFailure { output("Gagal: password salah atau file rusak (${it.message})") }
            }
        }
    }

    internal fun launchDecryptedViewer(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(view)
            output("File berhasil dibuka\n${file.name} (${file.length()} byte)")
        } catch (e: ActivityNotFoundException) {
            output("Dekripsi berhasil, tapi tidak ada aplikasi yang bisa membuka ${file.name}.\nGunakan tombol Dekripsi untuk menyimpan hasilnya.")
        }
    }

    internal fun helpBotTool() {
        clearPage("HelpBot Offline")
        addToolHeader("HelpBot Offline", "Chat bantuan lokal untuk memahami fungsi, permission, dan cara memakai tool MyTools. Tidak membutuhkan API/AI.", "HELP")
        val q = edit("Contoh: apa fungsi pipet warna?", false)
        content.addView(q)
        val out = label("Tanyakan fungsi atau cara memakai tool.", 14f)
        content.addView(out)

        val answers = listOf(
            Triple(listOf("pipet", "eyedropper", "warna layar", "ambil warna"), "Konverter Warna (HEX/RGB)", "Konversi warna HEX/RGB. Pipet tangkapan layar telah dinonaktifkan demi privasi."),
            Triple(listOf("wifi", "wi-fi", "wlan"), "Wi-Fi Info", "Menampilkan informasi jaringan Wi-Fi yang tersedia bagi aplikasi. Pada Android modern, beberapa operasi Wi-Fi membutuhkan izin Nearby Wi-Fi dan/atau lokasi tergantung API yang digunakan."),
            Triple(listOf("hash", "sha256", "checksum"), "Hash / Checksum", "Menghasilkan sidik jari data seperti SHA-256. Berguna untuk memverifikasi apakah file yang diterima sama dengan file sumber."),
            Triple(listOf("apk analyzer", "apk", "aplikasi analyzer"), "APK Analyzer", "Membaca metadata APK seperti package, versi, SDK, permission, sertifikat, dan komponen yang dapat membantu pemeriksaan teknis."),
            Triple(listOf("encrypt", "enkripsi", "file encryption"), "File Encryption", "Mengenkripsi file agar isi tidak mudah dibaca tanpa kunci. Jangan menghapus file asli sebelum memastikan hasil enkripsi dapat dibuka kembali."),
            Triple(listOf("totp", "2fa", "otp"), "2FA Manager", "Membuat kode OTP berbasis waktu untuk akun yang mendukung TOTP. Secret harus dijaga seperti password."),
            Triple(listOf("server", "hosting", "wifi hosting"), "HTTP Server / HTML Hosting", "Membuat server lokal di jaringan perangkat. Gunakan hanya pada jaringan yang dipercaya dan hentikan server setelah selesai."),
            Triple(listOf("url safety", "url", "phishing"), "URL Safety Checker", "Memeriksa beberapa indikator heuristik seperti HTTPS, punycode, userinfo, dan pola URL. Hasil 'tidak ada indikator' bukan jaminan bahwa situs aman."),
            Triple(listOf("network scanner", "scanner jaringan", "lan scanner"), "Network Scanner", "Mendeteksi host/port pada jaringan yang sedang digunakan. Gunakan hanya pada jaringan/perangkat yang kamu miliki atau punya izin untuk diuji."),
            Triple(listOf("help", "bantuan", "cara", "fungsi"), "HelpBot", "Saya bisa menjelaskan fungsi tool, permission yang dibutuhkan, contoh penggunaan, dan masalah umum secara offline." )
        )

        fun answer(raw: String): String {
            val text = raw.trim().lowercase(Locale.getDefault())
            if (text.isBlank()) return "Tulis pertanyaan terlebih dahulu."
            val hit = answers.firstOrNull { row -> row.first.any { key -> text.contains(key) } }
            return if (hit != null) "${hit.second}\n\n${hit.third}" else "Tool belum cocok dengan pertanyaan itu. Coba sebut nama tool, misalnya: pipet warna, APK Analyzer, hash, Wi-Fi, TOTP, enkripsi, atau Network Scanner."
        }

        content.addView(button("Tanya") { out.text = answer(q.text.toString()) })
        content.addView(button("Apa fungsi MyTools?") { out.text = "MyTools adalah kumpulan utility untuk file, jaringan, developer, keamanan, warna, perangkat, dan produktivitas. HelpBot menjelaskan fungsi tool secara offline." })
        content.addView(button("Cara aman memakai Security Tools") { out.text = "Gunakan tool jaringan hanya pada jaringan yang kamu miliki/izinkan. Jangan membagikan password, token, secret TOTP, atau API key. Untuk server lokal, hentikan server setelah selesai." })
    }

    internal fun encodeStego(uri:Uri,text:String):String {
        val src=contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it)}?:error("Gambar tidak dapat dibaca")
        val bmp=src.copy(Bitmap.Config.ARGB_8888,true); val payload="MYTOOLS-STG1:${text.length}:$text".toByteArray(StandardCharsets.UTF_8); val bits=payload.flatMap{b->(7 downTo 0).map{i->(b.toInt() shr i) and 1}}
        require(bits.size<=bmp.width*bmp.height*3){"Pesan terlalu panjang untuk gambar ini"}; var k=0
        loop@for(y in 0 until bmp.height) for(x in 0 until bmp.width){ val p=bmp.getPixel(x,y); var r=Color.red(p);var g=Color.green(p);var b=Color.blue(p); if(k<bits.size)r=(r and 254) or bits[k++] else break@loop; if(k<bits.size)g=(g and 254) or bits[k++] else break@loop; if(k<bits.size)b=(b and 254) or bits[k++] else break@loop; bmp.setPixel(x,y,Color.argb(Color.alpha(p),r,g,b)) }
        val out=File(filesDir,"stego_${System.currentTimeMillis()}.png");FileOutputStream(out).use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)};return "Pesan disembunyikan.\n${out.absolutePath}"
    }

    internal fun decodeStegoFromUri(uri:Uri){ toolThread {val result=runCatching{val bmp=contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it)}?:error("Gambar tidak bisa dibaca");val bytes=ByteArrayOutputStream();var cur=0;var n=0; outer@for(y in 0 until bmp.height)for(x in 0 until bmp.width){val p=bmp.getPixel(x,y);for(v in intArrayOf(Color.red(p),Color.green(p),Color.blue(p))){cur=(cur shl 1) or (v and 1);n++;if(n==8){bytes.write(cur);val a=bytes.toByteArray();val s=String(a,StandardCharsets.UTF_8);if(s.contains("MYTOOLS-STG1:")){val body=s.substringAfter("MYTOOLS-STG1:");val idx=body.indexOf(':');if(idx>0)return@runCatching body.substring(idx+1)};cur=0;n=0}}};error("Pesan tersembunyi tidak ditemukan")}.getOrElse{"Gagal: ${it.message}"};runOnUiThread{output(result)}} }

    internal fun sshKeyGeneratorTool(){
        clearPage("SSH Key Generator");addToolHeader("SSH Key Generator","Generate RSA atau Ed25519 key pair lokal.","SSH");val type=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("RSA 3072","Ed25519"))};content.addView(type);val out=label("Belum dibuat",13f);content.addView(out);content.addView(button("Generate") {toolThread {val r=runCatching{val alg=if(type.selectedItem.toString().startsWith("RSA"))"RSA" else "Ed25519";val gen=KeyPairGenerator.getInstance(alg);if(alg=="RSA")gen.initialize(3072);val kp=gen.generateKeyPair();val priv=Base64.getMimeEncoder(64,"\n".toByteArray()).encodeToString(kp.private.encoded);val pub=Base64.getMimeEncoder(64,"\n".toByteArray()).encodeToString(kp.public.encoded);"PRIVATE KEY (PKCS#8):\n-----BEGIN PRIVATE KEY-----\n$priv\n-----END PRIVATE KEY-----\n\nPUBLIC KEY (X.509):\n-----BEGIN PUBLIC KEY-----\n$pub\n-----END PUBLIC KEY-----"}.getOrElse{"Gagal: ${it.message}"};runOnUiThread{out.text=r}}})}

    internal fun certificateViewerTool(){clearPage("Certificate Viewer");addToolHeader("Certificate Viewer","Lihat detail sertifikat X.509 dari file.","CERT");content.addView(button("Pilih Sertifikat") {startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="application/x-x509-ca-cert";addCategory(Intent.CATEGORY_OPENABLE)},CERT_PICK)});content.addView(label("Mendukung sertifikat X.509/DER/PEM yang dapat diparse Android."))}
    internal fun viewCertificate(uri:Uri){toolThread {val r=runCatching{val raw=contentResolver.openInputStream(uri)?:error("File tidak bisa dibaca");val cert=raw.use{CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate};"Subject: ${cert.subjectX500Principal.name}\nIssuer: ${cert.issuerX500Principal.name}\nSerial: ${cert.serialNumber.toString(16)}\nValid dari: ${cert.notBefore}\nValid sampai: ${cert.notAfter}\nSignature: ${cert.sigAlgName}\nPublic key: ${cert.publicKey.algorithm}"}.getOrElse{"Gagal parse sertifikat: ${it.message}"};runOnUiThread{output(r)}}}

    internal fun imageStudioTool() {
        clearPage("Image Studio")

        // Header is supplied by the app toolbar. Keep the workspace title-free to avoid
        // duplicate headings and use the same monochrome DesignSystem as the rest of GITLS.
        val intro = subLabel("Resize, kompres, konversi, putar, flip, dan simpan gambar dengan cepat.", 12f)
        intro.setPadding(dp(2), dp(4), dp(2), dp(10))
        content.addView(intro)

        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        applyInteractiveSurface(previewCard, Ds.RADIUS_LG, 1)

        val preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(190))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = bg(if (isDarkTheme) Color.rgb(32,32,35) else Color.rgb(244,246,248), 16, line)
            visibility = View.GONE
        }
        previewCard.addView(preview)

        val status = label("Belum ada gambar", 13f)
        status.setPadding(dp(4), dp(8), dp(4), dp(2))
        previewCard.addView(status)
        content.addView(previewCard)

        var selectedUri: Uri? = null
        var originalBitmap: Bitmap? = null
        var workingBitmap: Bitmap? = null
        var keepRatio = true
        var lastOutput: File? = null
        var outputMime = "image/jpeg"
        var rotation = 0
        var flipHorizontal = false
        var flipVertical = false

        content.addView(label("Resize & Preset", 15f, true).apply {
            setPadding(dp(2), dp(14), dp(2), dp(6))
        })

        lateinit var width: EditText
        lateinit var height: EditText
        lateinit var ratioButton: Button
        lateinit var downloadBtn: Button
        lateinit var shareBtn: Button
        lateinit var outputInfo: TextView

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val presetData = listOf(
            "Asli" to null,
            "HD" to 1920,
            "Story" to 1080,
            "Square" to 1080,
            "Thumb" to 512
        )
        presetData.forEachIndexed { index, pair ->
            val b = button(pair.first) {
                val bmp = originalBitmap ?: run { toast("Pilih gambar terlebih dahulu"); return@button }
                when (pair.first) {
                    "Asli" -> { width.setText(bmp.width.toString()); height.setText(bmp.height.toString()) }
                    "Square" -> { width.setText("1080"); height.setText("1080"); keepRatio = false; ratioButton.text = "🔓 Rasio bebas" }
                    else -> {
                        val target = pair.second ?: bmp.width
                        val w = target.coerceAtMost(8000)
                        val h = (bmp.height.toDouble() * w / bmp.width).roundToInt().coerceAtLeast(1)
                        width.setText(w.toString()); height.setText(h.toString()); keepRatio = true; ratioButton.text = "🔒 Pertahankan rasio"
                    }
                }
            }
            styleAsSecondary(b)
            presetRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) leftMargin = dp(5)
            })
        }
        content.addView(presetRow)

        width = edit("Lebar (px)", false)
        height = edit("Tinggi (px)", false)
        width.hint = "Lebar"
        height.hint = "Tinggi"
        width.visibility = View.GONE
        height.visibility = View.GONE
        content.addView(width); content.addView(height)

        ratioButton = button("🔒 Pertahankan rasio") {
            keepRatio = !keepRatio
            ratioButton.text = if (keepRatio) "🔒 Pertahankan rasio" else "🔓 Rasio bebas"
        }
        ratioButton.visibility = View.GONE
        content.addView(ratioButton)

        val formatLabel = label("Format output", 13f, true)
        formatLabel.visibility = View.GONE
        content.addView(formatLabel)
        val formatRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val formatButtons = listOf("JPG" to "image/jpeg", "PNG" to "image/png", "WebP" to "image/webp")
        val formatButtonList = mutableListOf<Button>()
        formatButtons.forEachIndexed { index, pair ->
            lateinit var formatButton: Button
            formatButton = button(pair.first) {
                outputMime = pair.second
                formatButtonList.forEach { styleAsSecondary(it) }
                styleAsPrimary(formatButton)
                formatLabel.text = "Format output: ${pair.first}"
            }
            val b = formatButton
            if (index == 0) styleAsPrimary(b) else styleAsSecondary(b)
            formatButtonList.add(b)
            formatRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(5) })
        }
        formatRow.visibility = View.GONE
        content.addView(formatRow)

        val qualityLabel = label("Kualitas: 85%", 13f)
        val quality = SeekBar(this).apply { max = 100; progress = 85 }
        quality.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                qualityLabel.text = "Kualitas: ${progress.coerceAtLeast(1)}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        qualityLabel.visibility = View.GONE; quality.visibility = View.GONE
        content.addView(qualityLabel); content.addView(quality)

        val editLabel = label("Edit cepat", 15f, true)
        editLabel.visibility = View.GONE
        content.addView(editLabel)
        val editRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var rotateBtn: Button
        lateinit var flipHBtn: Button
        lateinit var flipVBtn: Button
        rotateBtn = button("↻ Putar") {
            rotation = (rotation + 90) % 360
            workingBitmap?.let { preview.setImageBitmap(transformImage(it, rotation, flipHorizontal, flipVertical)) }
        }
        flipHBtn = button("↔ Flip H") {
            flipHorizontal = !flipHorizontal
            workingBitmap?.let { preview.setImageBitmap(transformImage(it, rotation, flipHorizontal, flipVertical)) }
        }
        flipVBtn = button("↕ Flip V") {
            flipVertical = !flipVertical
            workingBitmap?.let { preview.setImageBitmap(transformImage(it, rotation, flipHorizontal, flipVertical)) }
        }
        listOf(rotateBtn, flipHBtn, flipVBtn).forEachIndexed { i, b ->
            styleAsSecondary(b)
            editRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(5) })
        }
        editRow.visibility = View.GONE
        content.addView(editRow)

        content.addView(button("Pilih Gambar") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, 1212)
        })

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var processBtn: Button
        processBtn = button("Proses Gambar") {
            val source = originalBitmap ?: run { toast("Pilih gambar terlebih dahulu"); return@button }
            resizeImageAdvanced(source, width, height, keepRatio, rotation, flipHorizontal, flipVertical,
                outputMime, quality.progress, preview, status) { file ->
                lastOutput = file
                processBtn.text = "Proses Lagi"
                downloadBtn.visibility = View.VISIBLE
                shareBtn.visibility = View.VISIBLE
                outputInfo.visibility = View.VISIBLE
                outputInfo.text = "Hasil: ${formatImageBytes(file.length())} • ${file.name}"
            }
        }
        processBtn.visibility = View.GONE

        downloadBtn = button("Download") {
            val file = lastOutput ?: run { toast("Belum ada hasil"); return@button }
            downloadImageToDownloads(file)
        }
        shareBtn = button("Bagikan") {
            val file = lastOutput ?: run { toast("Belum ada hasil"); return@button }
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = outputMime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Bagikan gambar"))
        }
        downloadBtn.visibility = View.GONE
        shareBtn.visibility = View.GONE
        actionRow.addView(processBtn, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(actionRow)

        val resultRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        resultRow.addView(downloadBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        resultRow.addView(shareBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        content.addView(resultRow)

        outputInfo = label("", 12f)
        outputInfo.visibility = View.GONE
        outputInfo.setPadding(dp(4), dp(6), dp(4), dp(8))
        content.addView(outputInfo)

        val info = label("", 13f)
        info.visibility = View.GONE
        content.addView(info)

        imageStudioResult = { uri ->
            selectedUri = uri
            toolThread {
                val loaded = runCatching {
                    val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: error("Gambar tidak bisa dibuka")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }.takeIf { it != null && it >= 0 } ?: -1L
                    Triple(bmp, bounds.outMimeType ?: "image/*", size)
                }
                runOnUiThread {
                    loaded.onSuccess { (bmp, mime, size) ->
                        originalBitmap = bmp
                        workingBitmap = bmp
                        rotation = 0; flipHorizontal = false; flipVertical = false
                        preview.setImageBitmap(bmp); preview.visibility = View.VISIBLE
                        width.setText(bmp.width.toString()); height.setText(bmp.height.toString())
                        width.visibility = View.VISIBLE; height.visibility = View.VISIBLE; ratioButton.visibility = View.VISIBLE
                        formatLabel.visibility = View.VISIBLE; formatRow.visibility = View.VISIBLE
                        qualityLabel.visibility = View.VISIBLE; quality.visibility = View.VISIBLE
                        editLabel.visibility = View.VISIBLE; editRow.visibility = View.VISIBLE
                        processBtn.visibility = View.VISIBLE
                        info.visibility = View.VISIBLE
                        outputInfo.visibility = View.GONE
                        downloadBtn.visibility = View.GONE; shareBtn.visibility = View.GONE
                        outputMime = when {
                            mime.contains("png", true) -> "image/png"
                            mime.contains("webp", true) -> "image/webp"
                            else -> "image/jpeg"
                        }
                        formatButtons.forEachIndexed { index, pair ->
                            if (pair.second == outputMime) styleAsPrimary(formatButtonList[index]) else styleAsSecondary(formatButtonList[index])
                        }
                        formatLabel.text = "Format output: ${outputMime.substringAfter('/') .uppercase(Locale.US)}"
                        val sizeText = if (size >= 0) formatImageBytes(size) else "-"
                        info.text = "Asli: ${bmp.width} × ${bmp.height}px • $sizeText • $mime"
                        status.text = "Gambar siap diproses"
                    }.onFailure { status.text = "Gagal membaca gambar: ${it.message}" }
                }
            }
        }
    }

    internal var imageStudioResult: ((Uri)->Unit)? = null

    internal fun transformImage(source: Bitmap, rotation: Int, flipH: Boolean, flipV: Boolean): Bitmap {
        val matrix = android.graphics.Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    internal fun resizeImageAdvanced(
        source: Bitmap?, widthView: EditText, heightView: EditText, keepRatio: Boolean,
        rotation: Int, flipH: Boolean, flipV: Boolean, mime: String, quality: Int,
        preview: ImageView, status: TextView, onSaved: (File) -> Unit
    ) {
        val bmp = source ?: run { toast("Pilih gambar terlebih dahulu"); return }
        var w = widthView.text.toString().toIntOrNull()?.coerceIn(1, 8000) ?: bmp.width
        var h = heightView.text.toString().toIntOrNull()?.coerceIn(1, 8000) ?: bmp.height
        if (keepRatio) {
            if (w != bmp.width) h = (bmp.height.toDouble() * w / bmp.width).roundToInt().coerceIn(1, 8000)
            else if (h != bmp.height) w = (bmp.width.toDouble() * h / bmp.height).roundToInt().coerceIn(1, 8000)
        }
        toolThread {
            val result = runCatching {
                val transformed = transformImage(bmp, rotation, flipH, flipV)
                val outBmp = Bitmap.createScaledBitmap(transformed, w, h, true)
                val dir = File(filesDir, "image_exports").apply { mkdirs() }
                val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                val f = File(dir, "gitls_image_${System.currentTimeMillis()}.$ext")
                FileOutputStream(f).use { out ->
                    val format = when (mime) {
                        "image/png" -> Bitmap.CompressFormat.PNG
                        "image/webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                        else -> Bitmap.CompressFormat.JPEG
                    }
                    outBmp.compress(format, quality.coerceIn(1,100), out)
                }
                Pair(outBmp, f)
            }
            runOnUiThread {
                result.onSuccess { (outBmp, f) ->
                    preview.setImageBitmap(outBmp)
                    status.text = "Selesai • ${outBmp.width}×${outBmp.height}px • ${formatImageBytes(f.length())}"
                    onSaved(f)
                }.onFailure { status.text = "Gagal memproses: ${it.message}" }
            }
        }
    }

    internal fun downloadImageToDownloads(file: File) {
        if (!file.exists()) { toast("File hasil tidak ditemukan"); return }
        toolThread {
            val result = runCatching {
                val mime = when (file.extension.lowercase(Locale.US)) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    else -> "image/jpeg"
                }
                val safeName = "GITLS_${System.currentTimeMillis()}.${file.extension.lowercase(Locale.US)}"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GITLS")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Tidak bisa membuat file Downloads")
                    try {
                        contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                            ?: error("Tidak bisa menulis file")
                        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        contentResolver.delete(uri, null, null)
                        throw e
                    }
                    "Downloads/GITLS/$safeName"
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
                    val target = File(dir, safeName)
                    file.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                    target.absolutePath
                }
            }
            runOnUiThread {
                result.onSuccess { path -> toast("Berhasil disimpan ke $path") }
                    .onFailure { toast("Download gagal: ${it.message}") }
            }
        }
    }

    internal fun formatImageBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }

    internal fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    @Volatile internal var exportBusy = false

    /**
     * Search-specific watcher. A short debounce prevents UI rebuilds from competing
     * with Android's IME composition/cursor updates. This is intentionally separate
     * from SimpleTextWatcher because the latter is used by live-preview tools where
     * immediate updates are required.
     */
    internal class DebouncedSearchWatcher(
        internal val fn: (String) -> Unit
    ) : android.text.TextWatcher {
        internal val handler = Handler(Looper.getMainLooper())
        internal var pending: Runnable? = null

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            val value = s?.toString().orEmpty()
            pending?.let(handler::removeCallbacks)
            val task = Runnable { fn(value) }
            pending = task
            handler.postDelayed(task, 110L)
        }

        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }

    internal class SimpleTextWatcher(val fn:(String)->Unit): android.text.TextWatcher {
        override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){}
        override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){fn(s?.toString()?:"")}
        override fun afterTextChanged(s:android.text.Editable?){}
    }


    internal fun webProjectBuilder() {
        clearPage("Web Project Builder")
        content.addView(label("Web Project Builder", 22f, true))
        content.addView(subLabel("Modul builder dipulihkan sebagai stub setelah ekstraksi Batch 2. Bangun ulang UI di batch berikutnya jika diperlukan.", 12f))
        content.addView(button("Buka Editor") { editor(null) })
        content.addView(button("Buka HTML Hosting") { openTool("webhostwifi") })
    }

    internal object JSONObjectLite {
        fun escape(s:String)=s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")
    }

    internal object Base32 {
        private const val ALPH="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        fun encode(data:ByteArray):String {
            var buffer=0; var bits=0; val out=StringBuilder()
            for(b in data) {
                buffer=(buffer shl 8) or (b.toInt() and 255); bits+=8
                while(bits>=5){ bits-=5; out.append(ALPH[(buffer shr bits) and 31]) }
            }
            if(bits>0) out.append(ALPH[(buffer shl (5-bits)) and 31])
            return out.toString()
        }
        fun decode(s:String):ByteArray {
            var buffer=0; var bits=0; val out=ByteArrayOutputStream()
            for(ch in s.toUpperCase(Locale.getDefault()).replace("=","").filter { !it.isWhitespace() }) {
                val v=ALPH.indexOf(ch); require(v>=0)
                buffer=(buffer shl 5) or v; bits+=5
                if(bits>=8){bits-=8; out.write((buffer shr bits) and 255)}
            }
            return out.toByteArray()
        }
    }
}
