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

class MainActivity : Activity() {

    internal lateinit var content: LinearLayout
    internal lateinit var toolActionDock: LinearLayout

    // --- File tools state (Batch 2) ---
    internal var fileSortMode = 0
    internal var fileFilterText = ""


    internal val convCategories = listOf(
        ConvCategory(
            // OPEN-06: hanya GZ yang benar-benar tersedia di sini; RAR/7Z/TAR/ISO tidak didukung.
            "arsip", "◫", "Arsip & Kompresi", "GZ (satu file)",
            "Kompres satu file ke GZ. Untuk membuat/ekstrak ZIP pakai tool ZIP.",
            "Kompres satu file menjadi .gz. Membuat atau mengekstrak ZIP ada di tool ZIP.",
            listOf("GZ"), "*/*"
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
    internal lateinit var homeMenu: ImageButton
    internal lateinit var homeSearch: ImageButton
    internal lateinit var homeProfile: ImageButton
    internal lateinit var search: EditText
    internal lateinit var searchFill: View
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
    internal lateinit var bottomNav: LinearLayout
    internal lateinit var navFavorite: View
    internal lateinit var navBot: View
    internal var bitFace: LottieAnimationView? = null
    internal var bitInteractionToken = 0
    internal lateinit var bitAnimationController: BitAnimationController
    // `::lateinitProp.isInitialized` hanya boleh dipanggil di dalam class pemilik properti.
    // File extension (MainActivityHome/Github/GithubScreens) wajib lewat accessor aman ini.
    internal val bitAnim: BitAnimationController?
        get() = if (::bitAnimationController.isInitialized) bitAnimationController else null
    // Bit mini-assistant on process screens. It reuses the same Lottie face as the bottom nav.
    internal var bitProcessFace: LottieAnimationView? = null
    internal var bitProcessErrorView: View? = null
    internal var bitPendingChatMessage: String? = null
    internal lateinit var loginScreen: LinearLayout
    internal lateinit var mainContainer: LinearLayout
    internal lateinit var topBar: LinearLayout
    internal lateinit var rootFrame: FrameLayout

    // Loading proses panjang: muncul otomatis hanya jika background task > 280 ms.
    // Tidak mengunci UI; pengguna tetap bisa membatalkan/navigasi bila task memang mengizinkan.
    internal val activeToolTasks = AtomicInteger(0)
    internal val toolLoadingHandler = Handler(Looper.getMainLooper())
    internal var toolLoadingOverlay: FrameLayout? = null
    internal var toolLoadingSpinner: ProgressBar? = null
    internal var toolLoadingText: TextView? = null


    /**
     * Semua pekerjaan background memakai helper ini. Loading baru muncul setelah 280 ms,
     * sehingga operasi cepat tidak menampilkan "flash" spinner.
     */

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
    /** OPEN-01: tujuan deep link (widget/tile/shortcut) yang menunggu splash/login selesai. */
    internal var pendingDeepLink: String? = null
    /** Tab Keuangan tujuan (notifikasi tagihan dll.), dipakai bersama pendingDeepLink "open_finance". */
    internal var pendingFinanceTab: Int = 0
    /** True setelah enterApp() selesai sekali; deep link baru boleh langsung diterapkan. */
    internal var appEntered = false

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
                // 2.45.14: Bit Room dibuka lewat klik ikon (bukan hold-reveal).
                // Gaze tetap mengikuti titik sentuhan agar Bit terasa hidup.
                if (bitAnim?.isExclusiveDragging != true) {
                    bitAnim?.lookAt(event.rawX, event.rawY, if (currentPage == "Bit Room") 60_000L else 260L)
                }
                // Home search is on-demand: when there is no query, the first
                // touch anywhere outside the search controls dismisses the
                // expanded field. It never reopens automatically while scrolling.
                if (searchSnapEnabled && searchOpen && search.text.isNullOrEmpty()) {
                    fun inside(v: View?): Boolean {
                        if (v == null || v.visibility != View.VISIBLE) return false
                        val loc = IntArray(2)
                        v.getLocationOnScreen(loc)
                        return event.rawX >= loc[0] && event.rawX <= loc[0] + v.width &&
                                event.rawY >= loc[1] && event.rawY <= loc[1] + v.height
                    }
                    if (!inside(search) && !inside(homeSearch)) setSearchBoxOpen(false)
                }
                rootSwipeDownX = event.rawX
                rootSwipeDownY = event.rawY
                rootSwipeTracking = currentPage in rootPageOrder && !imeVisible
                rootSwipeBlocked = !rootSwipeTracking ||
                        isTouchInsideNonPagingArea(event.rawX, event.rawY)
                rootSwipePageAtDown = currentPage
                rootSwipeHandled = false
            }
            MotionEvent.ACTION_MOVE -> {
                // During scrolling/dragging the gaze follows the finger.
                // Controller throttles to keep UI-thread work bounded.
                if (bitAnim?.isExclusiveDragging != true) {
                    bitAnim?.lookAt(event.rawX, event.rawY, if (currentPage == "Bit Room") 60_000L else 120L)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (bitAnim?.isExclusiveDragging != true) {
                    bitAnim?.lookAt(event.rawX, event.rawY, if (currentPage == "Bit Room") 900L else 140L)
                }
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
                bitAnim?.releaseGaze()
                bitAnim?.playIdleAll()
                rootSwipeTracking = false
                rootSwipeHandled = false
            }
        }
        return super.dispatchTouchEvent(event)
    }


    internal var wifiHistory = mutableListOf<Int>()
    internal var batteryHistory = mutableListOf<Int>()

    // Navigation + UI state preservation. Each rendered page is kept as an actual View tree,
    // so EditText contents, selections, toggle states and ScrollView position survive Back.
    internal val pageBackStack = ArrayDeque<PageSnapshot>()
    internal var restoringSnapshot = false
    internal var resettingRootNavigation = false
    internal var editorFile: File? = null
    internal var editorSourceUri: Uri? = null   // file asli di HP (dibuka lewat Buka)
    internal var editorSaveAsText: String? = null
    @set:JvmName("setEditorModeValue") internal var editorMode = "text"
    internal var editorLastSelection = ""
    internal var editorBox: EditText? = null
    internal var editorNameLabel: TextView? = null
    internal var editorStatusLabel: TextView? = null
    internal var editorContextActions: LinearLayout? = null
    internal lateinit var editorBottomBar: LinearLayout
    internal var editorLanding = false

    /** Toolbar bawah editor hanya saat workspace edit aktif (bukan landing / preview / halaman lain). */

    /**
     * Multi-buffer editor: teks HTML/CSS/JS (dan file lain) tidak hilang saat ganti tab
     * atau buka file lain. Key = path absolut file, atau "mode:html" / "mode:css" dst
     * untuk draft tanpa file. Buffer juga di-persist ke disk agar tetap ada setelah keluar app.
     */
    internal data class EditorBuffer(
        var text: String = "",
        var filePath: String? = null,
        var mode: String = "text",
        var selectionStart: Int = 0,
        var selectionEnd: Int = 0,
        var dirty: Boolean = false,
        var lastDiskModified: Long = -1L,
        var lastDiskSize: Long = -1L
    )
    internal val editorBuffers = LinkedHashMap<String, EditorBuffer>()
    internal var editorCurrentBufferKey: String? = null
    /** Unique session key for a new unsaved editor document. Prevents draft buffer collisions. */
    internal var editorDraftSessionId: String? = null
    internal val editorAutoSaveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal var editorAutoSaveRunnable: Runnable? = null
    /** Monotonically increasing owner token for debounced editor autosave callbacks. */
    internal var editorAutoSaveGeneration: Long = 0L
    /** Generation used by toolThread() to reject stale UI callbacks after page changes. */
    internal var toolUiGeneration: Long = 0L
    /**
     * Folder project web aktif (berisi index.html + style.css + script.js yang saling terhubung).
     * Saat tab HTML/CSS/JS diganti, editor membuka file pasangan di folder yang sama.
     */
    internal var editorProjectDir: File? = null

    // Multi-file editor tabs + keyboard accessory (2.45.55 UX upgrade)
    internal val editorOpenTabs = mutableListOf<com.example.aidetest.EditorTabBar.Tab>()
    internal var editorActiveTabKey: String = ""
    internal var editorTabBarContainer: LinearLayout? = null
    internal var editorKeyboardBarContainer: LinearLayout? = null
    internal var editorLivePreviewWeb: android.webkit.WebView? = null
    internal var webBuildRunning: Boolean = false


    /**
     * Session state global semua tools: field form (EditText) disimpan per nama halaman.
     * Saat keluar tool / pindah Home / tutup app → data TERBARU yang disimpan.
     * Saat buka tool lagi → data terbaru yang dipulihkan (bukan data lama).
     * Key field: "hint|index" di dalam map per pageName.
     */
    internal val toolSessionFields = LinkedHashMap<String, LinkedHashMap<String, String>>()
    /** Counter field saat render halaman (di-reset di clearPage). */
    internal var toolFieldSeq = 0
    /** Nama halaman yang field-nya baru saja di-harvest (hindari double). */
    internal var toolSessionLastHarvestPage: String? = null

    internal var editorPendingTarget: EditText? = null
    internal var editorExternalTarget: EditText? = null
    internal var editorExternalMode: String? = null
    internal lateinit var editorMore: TextView
    // Semua kalkulator dirender dalam satu workspace; perpindahan mode tidak membuka halaman baru.
    internal var embeddedCalculatorRender = false
    internal var calculatorSelectedMode = "basiccalc"
    internal var homeFilter = "Semua"
    internal var server: ServerSocket? = null
    internal var hostingStatusView: TextView? = null
    internal var hostingUrlView: TextView? = null
    internal var hostingCredentialsView: TextView? = null
    internal var hostingQrView: ImageView? = null
    internal val HOTSPOT_PERMISSION_REQUEST = 9901
    internal var pendingHostingPort = 8080
    internal var pendingHostingRoot: File? = null
    internal var webImportTarget: EditText? = null
    /** Draft source untuk layar HTML Hosting Wi-Fi (bertahan antar-refresh UI). */
    internal var hostingDraftName: String = ""
    internal var hostingDraftHtml: String = ""
    internal var hostingDraftCss: String = ""
    internal var hostingDraftJs: String = ""
    internal var webBuildReady = false
    /** Buffer log console editor (JS console.log, error, status). */
    internal val editorConsoleLog = StringBuilder()
    internal var editorConsoleView: TextView? = null
    /** Panel Terminal editor (tombol ⋯): log error editor/preview. */
    internal var editorTerminalPanel: View? = null
    internal var editorTerminalBadge: View? = null
    internal var editorTerminalOpen = false
    internal var editorTerminalUnseenErrors = false
    /** Tanda tangan masalah terakhir per buffer agar error yang sama tidak dicatat berulang. */
    internal val editorDiagSeen = HashMap<String, Set<String>>()
    internal var webHostButton: Button? = null
    internal var webBuildStatusView: TextView? = null
    internal var webHostingToken = ""
    internal val webHostClientCount = AtomicInteger(0)
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


    internal val FINANCE_EXPORT_CREATE = 2003
    internal val FINANCE_BACKUP_CREATE = 2004
    internal val FINANCE_BACKUP_OPEN = 2005
    internal val GITHUB_ZIP_PICK_REQUEST = 12801
    internal val GITHUB_FOLDER_PICK_REQUEST = 12802
    internal val LARGE_FILE_FOLDER_PICK_REQUEST = 12830
    internal var largeFileFolderUri: Uri? = null
    internal val FILE_SEARCH_FOLDER_PICK_REQUEST = 12831
    internal val STORAGE_ANALYZER_FOLDER_PICK_REQUEST = 12832
    internal var storageScanUri: Uri? = null
    internal var storageScanResult: StorageScanResult? = null
    internal var storageFilterCategory = 0
    internal var storageFilterMinMb = ""
    internal var storageFilterMaxMb = ""
    internal var appManagerFilter = 0 // 0 = semua, 1 = aplikasi pengguna, 2 = aplikasi sistem
    internal var appManagerQuery = ""
    internal var fileSearchFolderUri: Uri? = null
    internal var fileSearchLocation = 0 // 0 = folder aplikasi, 1 = Download, 2 = folder pilihan
    internal var fileSearchDayIndex = 0
    internal var fileSearchQuery = ""
    internal var fileSearchExt = ""
    internal var fileSearchMinMb = ""
    internal var fileSearchMaxMb = ""
    internal val ZIP_SRC_FILE_PICK_REQUEST = 12840
    internal val ZIP_SRC_FOLDER_PICK_REQUEST = 12841
    internal val ZIP_ARCHIVE_PICK_REQUEST = 12842
    internal var zipSourceUri: Uri? = null
    internal var zipSourceIsFolder = false
    internal var zipArchiveUri: Uri? = null
    internal var zipLevelChoice = 1
    internal var backupSelected: MutableSet<String>? = null
    internal var backupPendingSel: Set<String>? = null
    internal val BIT_ATTACHMENT_PICK_REQUEST = 12901
    internal var bitAttachmentUri: Uri? = null
    internal var bitAttachmentName: String = ""
    internal var bitAttachmentMime: String = ""
    // Pending Bot -> GitHub upload. Disimpan sementara agar Publisher dapat melengkapi credential.
    internal var bitPendingGithubUploadUri: Uri? = null
    internal var bitPendingGithubUploadName: String = ""
    // True when the user chose "Rubah" from Bot confirmation. In this mode the
    // Publisher owns the final upload; saving settings must not auto-upload.
    internal var bitGithubUploadNeedsManualPublisher: Boolean = false
    internal var bitChatTopFace: com.airbnb.lottie.LottieAnimationView? = null
    internal val bitHoldReveal: BitHoldReveal by lazy { BitHoldReveal(this) }
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
    internal var colorPickerProjectionResultCode = 0
    internal var colorPickerProjectionData: Intent? = null


    internal val homeTools = listOf(
        "workspace" to "Workspace Center", "plugincenter" to "Plugin Center", "clipboard" to "Clipboard Hub", "ocr" to "OCR", "filemanager" to "File Manager", "recentfiles" to "Recent Files", "backuprestore" to "Backup / Restore", "editor" to "Editor", "reminder" to "Notifikasi", "zip" to "ZIP / UNZIP", "githubzip" to "GitHub Publisher",
        "json" to "JSON Tools", "hash" to "Hash Generator",
        "base64" to "Base64", "url" to "URL Tools", "regex" to "Regex Tester",
        "uuid" to "UUID Generator", "color" to "Color Tools", "number" to "Kalkulator Lengkap",
        "textstat" to "Statistik Teks", "case" to "Case Converter", "dedupe" to "Hapus Duplikat",
        "compare" to "Bandingkan Teks", "slug" to "Slug Generator",
        "password" to "Password Generator", "token" to "Token Acak", "jwt" to "JWT Decoder",
        "hmac" to "HMAC Generator", "totp" to "TOTP Generator", "aes" to "AES Encrypt / Decrypt",
        "random" to "Random Bytes", "checksum" to "Checksum File (1 file)", "hex" to "Hex Converter",
        "base32" to "Base32", "dns" to "DNS Lookup", "rdns" to "Reverse DNS",
        "port" to "Port Checker", "publicip" to "IP Publik", "ping" to "Ping",
        "ipinfo" to "IP Address Info", "ipintelligence" to "IP Intelligence", "dohdns" to "DNS over HTTPS", "packageinspector" to "Package Inspector", "apiconnections" to "API Connections", "ssl" to "SSL Certificate", "apk" to "APK Inspector",
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
        "filehashcompare" to "Bandingkan Hash (2 file)", "markdown" to "Markdown Viewer",
        "sql" to "SQL Tools", "yaml" to "YAML Formatter", "toml" to "TOML Inspector",
        "cron" to "Cron Helper", "passwordstrength" to "Password Strength",
        "fileencryption" to "File Encryption", "steganography" to "Steganography", "passwordanalyzer" to "Password Strength Analyzer", "breachchecker" to "Data Breach Checker", "securenotes" to "Secure Notes", "totpvault" to "2FA Manager (TOTP)", "pgp" to "PGP Encrypt / Decrypt", "sshkeygen" to "SSH Key Generator", "certviewer" to "Certificate Viewer", "virusscanner" to "Virus Scanner", "urlsafety" to "URL Safety Checker",
        "stopwatch" to "Stopwatch", "timer" to "Timer",
        "imagestudio" to "Image Studio",
        // Photo Editor + Design Studio + Background Remover digabung; id lama = alias (CsToolIds).
        "creativestudio" to "Foto Studio",
        // Suite Foto Studio: animasi & presentasi berbasis dokumen kanvas yang sama.
        "presentationstudio" to "Presentation Studio",
        "videoeditor" to "Video Editor",
        "videotoaudio" to "Video → Audio",
        "videoframes" to "Video to Frames",
        "editingstudio" to "Editing Studio",
        "audioeditor" to "Audio Editor",
        "batchconvert" to "Converter & Batch",
        "imagecompress" to "Image Compressor",
        "imageconvert" to "Image Converter",
        "autotrace" to "Auto Trace (Image → SVG)",
        "restclient" to "REST / API Client", "websocket" to "WebSocket Client",
        "networkcenter" to "Network Center",
        "apkcompare" to "APK Compare", "duplicatefinder" to "Duplicate Finder",
        "largefilefinder" to "Large File Finder",
        "deviceinfo" to "Device Info", "battery" to "Battery Info",
        "riskcalc" to "Risk-Reward & Position Sizing", "compoundcalc" to "Compound Interest & Target Tabungan",
        "margincalc" to "Margin & PPN / Pajak", "discountcalc" to "Diskon Bertingkat",
        "datacalc" to "Ukuran Data Digital", "pressurecalc" to "Pressure Calculator",
        "worktimecalc" to "Jam Kerja", "basecalc" to "Basis Angka", "equationcalc" to "Persamaan",
        "unitconverter" to "Unit Converter Pro", "whois" to "WHOIS Lookup", "traceroute" to "Traceroute",
        "subnetcalc" to "Subnet / CIDR Calculator", "netscanner" to "Network Scanner",
        "webproject" to "Web Project Builder", "webeditor" to "Web Editor",
        "networkstudio" to "Network Studio", "developerstudio" to "Developer Studio",
        "filestudio" to "File Studio", "colorstudio" to "Color Studio", "systemstudio" to "System Studio",
        "financestudio" to "Finance Studio", "utilitystudio" to "Utility Studio",
        "customtools" to "Tool Customization", "studiocenter" to "Studio Center"
    )

    // Cached indexes: avoid repeated O(n) scans/toMap() while the user scrolls/searches.
    // Registry invariant: one stable ID maps to one tool definition.
    // This prevents duplicate entries from leaking into search/customization even if a
    // future edit accidentally repeats an ID in homeTools.
    internal val homeToolMap: Map<String, String> by lazy(LazyThreadSafetyMode.NONE) { homeTools.associate { it.first to it.second } }
    internal val homeToolSearchIndex by lazy(LazyThreadSafetyMode.NONE) {
        homeTools.distinctBy { it.first }.map { it.first to it.second.lowercase(Locale.getDefault()) }
    }

    internal var dark = Color.rgb(10, 10, 11)
    internal var panel = Color.rgb(22, 22, 24)
    internal var panel2 = Color.rgb(28, 28, 31)
    internal var textMain = Color.rgb(245, 245, 247)
    internal var textMuted = Color.rgb(155, 155, 160)
    internal var line = Color.rgb(48, 48, 52)


    // Dipakai untuk memulihkan halaman/tool setelah ganti tema (lihat MainActivityThemeRestart.kt).
    internal var lastOpenedToolId: String? = null
    internal var lastOpenedToolPage: String? = null

    /**
     * MainActivity memakai Activity biasa, jadi resource XML (@color/mt_bg, windowBackground,
     * area status bar/header) otomatis mengikuti tema SISTEM lewat values-night, bukan pilihan
     * tema di app. Akibatnya saat HP gelap tetapi app dipilih Terang, area atas tetap hitam.
     * Di sini kita paksa mode malam resource sesuai pilihan "theme_mode" milik app.
     */
    override fun attachBaseContext(newBase: Context) {
        val mode = runCatching {
            newBase.getSharedPreferences("mytools_prefs", MODE_PRIVATE).getString("theme_mode", "system")
        }.getOrNull() ?: "system"
        val night = when (mode) {
            "dark" -> Configuration.UI_MODE_NIGHT_YES
            "light" -> Configuration.UI_MODE_NIGHT_NO
            else -> null
        }
        if (night == null) {
            super.attachBaseContext(newBase)
            return
        }
        val cfg = Configuration(newBase.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    /** SAF permission helper: provider may grant READ only, WRITE only, or both.
     *  Try each capability independently so one unsupported flag does not cancel all persistence.
     */
    internal fun persistUriPermissionsSafely(uri: Uri, wantWrite: Boolean = false): Int {
        var granted = 0
        if (runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            }.getOrDefault(false)) {
            granted = granted or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        if (wantWrite && runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                true
            }.getOrDefault(false)) {
            granted = granted or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        return granted
    }

    internal fun restoreEditorSourceUriIfUsable() {
        val raw = prefs.getString("editor_source_uri", null) ?: return
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        val writable = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (writable) {
            editorSourceUri = uri
        } else {
            prefs.edit().remove("editor_source_uri").apply()
        }
    }

    override fun onCreate(state: Bundle?) {
        // Splash screen resmi: harus dipasang sebelum super.onCreate().
        runCatching { installSplashScreen() }
        super.onCreate(state)
        runCatching { registerBackCallback() }
            .onFailure { android.util.Log.w("GITLS", "Back callback skipped", it) }
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("mytools_prefs", MODE_PRIVATE)
        restoreEditorSourceUriIfUsable()
        BitShape.load(prefs)
        Motion.refresh(prefs, contentResolver)
        runCatching { loadToolSessionFields() }
            .onFailure { android.util.Log.w("GITLS", "Tool session restore skipped", it) }
        // Startup maintenance must never be able to close the Activity.
        // A malformed old preference/history entry should not turn into a launch crash.
        runCatching { syncToolUpdates() }
            .onFailure { android.util.Log.w("GITLS", "Startup tool-sync skipped", it) }
        runCatching { migrateCreativeStudioIds() }
            .onFailure { android.util.Log.w("GITLS", "Studio id migration skipped", it) }
        // Android 13+ requires an explicit export flag; ContextCompat menanganinya di semua API.
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            colorPickerReceiver,
            IntentFilter(ColorPickerService.ACTION_COLOR_PICKED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
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
        toolActionDock = findViewById(R.id.toolActionDock)
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
        // Splash "GitLis": pada launch baru, animasi harus benar-benar selesai
        // sebelum Home ditampilkan. Sebelumnya enterApp() dipanggil lebih dulu sehingga
        // UI utama sudah aktif di belakang splash dan transisi dapat terlihat terpotong.
        // Untuk restore/rotasi, jangan mengulang splash.
        var startupUiShown = false
        fun showStartupUi() {
            if (startupUiShown) return
            startupUiShown = true
            runCatching { showLoginOrEnter() }
                .onFailure { error ->
                    android.util.Log.e("GITLS", "Startup UI failed; showing safe fallback", error)
                    showStartupFallback(error)
                }
            // Setelah ganti tema: kembalikan halaman, isi editor, dan posisi scroll.
            window.decorView.post {
                runCatching { restoreThemeRestartSession() }
                    .onFailure { android.util.Log.w("GITLS", "Theme session restore skipped", it) }
            }
        }

        // OPEN-01: tangkap tujuan deep link sebelum splash; diterapkan setelah enterApp().
        if (state == null) {
            pendingDeepLink = deepLinkTarget(intent)
            pendingFinanceTab = intent?.getIntExtra("finance_tab", 0) ?: 0
            if (pendingDeepLink != null) consumeDeepLinkExtras(intent)
        }
        if (state == null) {
            runCatching {
                val splash = GitlisSplashView(this)
                (findViewById<View>(android.R.id.content) as ViewGroup).addView(
                    splash, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                // enterApp() hanya dipanggil dari callback akhir animasi. Dengan begitu
                // splash tidak pernah menghilang di tengah animasi masuk.
                splash.start { showStartupUi() }
            }.onFailure {
                android.util.Log.w("GITLS", "Splash skipped; opening app immediately", it)
                showStartupUi()
            }
        } else {
            showStartupUi()
        }

        // Maintenance dijalankan secara defensif agar tidak pernah menggagalkan startup.
        runCatching { scheduleFinanceMaintenance() }
            .onFailure { android.util.Log.w("GITLS", "Finance maintenance skipped", it) }
        // Pengingat tagihan juga dicek saat aplikasi dibuka (worker harian bisa tertunda); notifikasi tidak berulang.
        // Ditunda agar tidak bersamaan dengan pembukaan/migrasi database oleh UI saat startup.
        val appCtx = applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Thread { runCatching { FinanceBillNotifier.notifyDue(appCtx) } }.start()
        }, 5000L)

        back.setOnClickListener { navigateBack() }
        action.setOnClickListener { showAbout() }
        // Search must never rebuild the whole page on every keystroke.
        // IME composition on Android can emit several text events per character;
        // debouncing keeps the EditText responsive and lets the keyboard finish its
        // composing transaction before the result list is rendered.
        search.addTextChangedListener(DebouncedSearchWatcher { query ->
            updateSearchClearButton()
            if (!suppressSearch) filterCurrent(query)
        })
        // Clear button berada langsung di sisi kanan kotak pencarian.
        // Saat ada teks, X muncul; menekannya menghapus query, menutup keyboard,
        // lalu mengembalikan workspace ke Beranda tanpa membangun ulang top bar.
        search.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP && search.compoundDrawables[2] != null) {
                val clear = search.compoundDrawables[2]
                val hitLeft = search.width - search.paddingEnd - clear.intrinsicWidth - dp(12)
                if (event.x >= hitLeft) {
                    search.setText("")
                    search.clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(search.windowToken, 0)
                    if (currentPage == "home") showHome(homeFilter)
                    return@setOnTouchListener true
                }
            }
            false
        }
        updateSearchClearButton()
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
        navBot = findViewById(R.id.navBot)
        navFavorite = findViewById(R.id.navFavorite)
        navFavorite.setOnClickListener { navigateRoot("favorites") { showFavorites() } }
        findViewById<View>(R.id.navSettings).setOnClickListener { navigateRoot("settings") { showSettings() } }
        listOf(R.id.navHome, R.id.navTools, R.id.navFavorite, R.id.navSettings).forEach { id ->
            addPressFeedback(findViewById(id))
        }
        bitAnimationController = BitAnimationController(this)
        bitAnimationController.setLifecycleActive(true)
        BitPetSystem.bind(this)
        setupBitFace()
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
        // Long-press search → Command Palette (cari 100+ tools dengan cepat)
        homeSearch.setOnLongClickListener {
            showCommandPalette()
            true
        }
        homeProfile.setOnClickListener { showProfile() }
        // Ghost button: tanpa container; ikon scale kecil + shadow tipis turun lalu kembali halus.
        Motion.press(homeMenu, 0.86f, shadowDp = -1f)
        Motion.press(homeSearch, 0.86f, shadowDp = -1f, onRelease = {
            homeSearch.animate().cancel()
            homeSearch.animate().rotationBy(360f).scaleX(1.06f).scaleY(1.06f).setDuration(260L).withEndAction {
                homeSearch.animate().scaleX(1f).scaleY(1f).setDuration(100L).start()
            }.start()
        })
        Motion.press(homeProfile, 0.86f, shadowDp = -1f)
        Motion.press(back, 0.88f)
        Motion.press(action, 0.88f)

        // Login actions: all visible controls now have a real action.
        // MASUK: profil lokal ada -> masuk; belum ada -> arahkan membuat profil (tidak diam-diam jadi guest).
        findViewById<Button>(R.id.btnMasuk).setOnClickListener {
            if (profilePrefs().getBoolean("registered", false)) enterApp() else showLocalRegistration()
        }
        findViewById<Button>(R.id.btnDaftar).setOnClickListener { showLocalRegistration() }
        findViewById<TextView>(R.id.tvLanjut).apply {
            // Target sentuh minimal 48dp agar mudah ditekan.
            minHeight = dp(48)
            gravity = Gravity.CENTER
            setOnClickListener {
                profilePrefs().edit().putBoolean("guest_chosen", true).apply()
                enterApp()
            }
        }
        addPressFeedback(homeMenu)
        addPressFeedback(homeSearch)
        addPressFeedback(homeProfile)
        styleTopFabs()
        // Fitur tambahan tidak boleh membuat aplikasi mental ke Home bila ada masalah device/API.
        runCatching { setupDynamicShortcuts() }
        runCatching { MyToolsWidget.update(this) }
        // OPEN-01: deep link (widget/tile/shortcut) TIDAK diterapkan di sini — splash masih
        // berjalan dan showStartupUi() → enterApp() → showHome() akan menimpanya. Tujuan
        // disimpan di pendingDeepLink (lihat atas) dan diterapkan oleh enterApp().
    }


    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent); setIntent(intent)
        val target = deepLinkTarget(intent) ?: return
        consumeDeepLinkExtras(intent)
        pendingDeepLink = target
        pendingFinanceTab = intent?.getIntExtra("finance_tab", 0) ?: 0
        // Aplikasi sudah tampil: buka langsung. Masih splash/login: enterApp() yang menerapkan.
        if (appEntered && loginScreen.visibility != View.VISIBLE) applyPendingDeepLink()
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_CANCELED) {
            when (requestCode) {
                1001 -> { editorExternalTarget = null; editorExternalMode = null }
                9811 -> editorPendingTarget = null
                1031 -> editorSaveAsText = null
                1022 -> pendingOcrSaveText = null
                SECURITY_OPEN_PICK -> lockedOpenPassword = null
                WEB_HTML_PICK_REQUEST, WEB_CSS_PICK_REQUEST, WEB_JS_PICK_REQUEST -> webImportTarget = null
                FINANCE_EXPORT_CREATE -> pendingFinanceExportJson = false
                COLOR_PALETTE_EXPORT_REQUEST -> pendingColorPaletteExportFormat = ""
                1220, 1221, 1222, 1223, 1224 -> imageToolPickPending = null
                1225 -> autoTraceBatchPickPending = null
                1230, 1231 -> videoEditorPickPending = null
                1240, 1241 -> audioEditorPickPending = null
                1260 -> batchConverterPickPending = null
                1270 -> videoFramesPickPending = null
            }
        }
        val selectedUri = data?.data
        // Multi-select (Video Editor / Batch Converter / Auto Trace Batch) can return only clipData
        if (resultCode == RESULT_OK && selectedUri == null &&
            (requestCode == 1230 || requestCode == 1231 || requestCode == 1260 || requestCode == 1225) &&
            data?.clipData != null
        ) {
            val uris = mutableListOf<Uri>()
            val clip = data.clipData!!
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { u ->
                    persistUriPermissionsSafely(u, wantWrite = false)
                    uris.add(u)
                }
            }
            when (requestCode) {
                1260 -> {
                    batchConverterPickPending?.invoke(uris)
                    batchConverterPickPending = null
                }
                1225 -> {
                    autoTraceBatchPickPending?.invoke(uris)
                    autoTraceBatchPickPending = null
                }
                else -> {
                    videoEditorPickPending?.invoke(uris)
                    videoEditorPickPending = null
                }
            }
            return
        }
        if (resultCode == RESULT_OK && selectedUri != null) {
            when (requestCode) {
                BIT_ATTACHMENT_PICK_REQUEST -> {
                    bitAttachmentUri = selectedUri
                    bitAttachmentName = queryName(selectedUri) ?: selectedUri.lastPathSegment ?: "attachment"
                    bitAttachmentMime = contentResolver.getType(selectedUri).orEmpty()
                    persistUriPermissionsSafely(selectedUri, wantWrite = false)
                    if (currentPage == "Bit Assistant") setupBitChatInput()
                    toast("Lampiran dipilih: $bitAttachmentName")
                    return
                }
                1212 -> {
                    imageStudioResult?.invoke(selectedUri)
                    return
                }
                1270 -> {
                    // Video to Frames picker
                    persistUriPermissionsSafely(selectedUri, wantWrite = false)
                    videoFramesPickPending?.invoke(selectedUri)
                    videoFramesPickPending = null
                    return
                }
                9911 -> {
                    // Video → Audio picker
                    persistUriPermissionsSafely(selectedUri, wantWrite = false)
                    videoToAudioPending?.invoke(selectedUri)
                    videoToAudioPending = null
                    return
                }
                1220, 1221, 1222, 1223, 1224 -> {
                    // Image tools picker (Photo Editor / BG Remover / Compressor / Converter / Auto Trace)
                    persistUriPermissionsSafely(selectedUri, wantWrite = false)
                    imageToolPickPending?.invoke(selectedUri)
                    imageToolPickPending = null
                    return
                }
                1225 -> {
                    // Auto Trace batch multi-select
                    val uris = mutableListOf<Uri>()
                    val clip = data?.clipData
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) {
                            clip.getItemAt(i)?.uri?.let { u ->
                                persistUriPermissionsSafely(u, wantWrite = false)
                                uris.add(u)
                            }
                        }
                    } else {
                        persistUriPermissionsSafely(selectedUri, wantWrite = false)
                        uris.add(selectedUri)
                    }
                    autoTraceBatchPickPending?.invoke(uris)
                    autoTraceBatchPickPending = null
                    return
                }
                1230, 1231 -> {
                    val uris = mutableListOf<Uri>()
                    val clip = data?.clipData
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) {
                            clip.getItemAt(i)?.uri?.let { u ->
                                persistUriPermissionsSafely(u, wantWrite = false)
                                uris.add(u)
                            }
                        }
                    } else {
                        persistUriPermissionsSafely(selectedUri, wantWrite = false)
                        uris.add(selectedUri)
                    }
                    videoEditorPickPending?.invoke(uris)
                    videoEditorPickPending = null
                    return
                }
                1240, 1241 -> {
                    persistUriPermissionsSafely(selectedUri, wantWrite = false)
                    audioEditorPickPending?.invoke(selectedUri)
                    audioEditorPickPending = null
                    return
                }
                1260 -> {
                    val uris = mutableListOf<Uri>()
                    val clip = data?.clipData
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) {
                            clip.getItemAt(i)?.uri?.let { u ->
                                persistUriPermissionsSafely(u, wantWrite = false)
                                uris.add(u)
                            }
                        }
                    } else {
                        persistUriPermissionsSafely(selectedUri, wantWrite = false)
                        uris.add(selectedUri)
                    }
                    batchConverterPickPending?.invoke(uris)
                    batchConverterPickPending = null
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
                9820 -> {
                    // Project Explorer: buka folder dari penyimpanan HP
                    persistUriPermissionsSafely(selectedUri, wantWrite = false)
                    importDeviceFolderAsProject(selectedUri)
                    return
                }
                ZIP_SRC_FILE_PICK_REQUEST, ZIP_SRC_FOLDER_PICK_REQUEST, ZIP_ARCHIVE_PICK_REQUEST -> {
                    zipToolPicked(requestCode, selectedUri)
                    return
                }
                LARGE_FILE_FOLDER_PICK_REQUEST -> {
                    persistUriPermissionsSafely(selectedUri, wantWrite = false)
                    largeFileFolderUri = selectedUri
                    largeFileFinderTool()
                    return
                }
                STORAGE_ANALYZER_FOLDER_PICK_REQUEST -> {
                    persistUriPermissionsSafely(selectedUri, wantWrite = false)
                    storageScanUri = selectedUri
                    storageScanResult = null
                    storageAnalyzerTool()
                    return
                }
                FILE_SEARCH_FOLDER_PICK_REQUEST -> {
                    persistUriPermissionsSafely(selectedUri, wantWrite = false)
                    fileSearchFolderUri = selectedUri
                    fileSearchLocation = 2
                    fileSearchTool()
                    return
                }
                GITHUB_FOLDER_PICK_REQUEST -> {
                    githubFolderUri = selectedUri
                    run {
                        val uri = selectedUri
                        persistUriPermissionsSafely(uri, wantWrite = false)
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
                            runOnToolUiThread {
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
                    persistUriPermissionsSafely(uri, wantWrite = false)
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
                        runOnToolUiThread {
                            result.onSuccess { text ->
                                target.setText(text)
                                webBuildReady = false
                                webBuildStatusView?.text = "BELUM BUILD • File berhasil dimuat, tekan Build untuk validasi"
                                webHostButton?.isEnabled = false
                                webImportTarget = null
                                toast("File web berhasil dimuat")
                                // Segarkan kartu di layar HTML Hosting Wi-Fi jika sedang dibuka.
                                if (currentPage == "HTML Hosting Wi-Fi") {
                                    when (requestCode) {
                                        WEB_HTML_PICK_REQUEST -> hostingDraftHtml = text
                                        WEB_CSS_PICK_REQUEST -> hostingDraftCss = text
                                        WEB_JS_PICK_REQUEST -> hostingDraftJs = text
                                    }
                                    wifiHtmlHostingTool()
                                }
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
                decodeSampledBitmap(uri, 2048) ?: error("Foto tidak dapat dibaca")
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
                JSONObject().apply { put("source", "GITLS Color Tools"); put("colors", arr) }.toString(2)
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
        // Ekspor/impor CSV, backup & restore JSON keuangan (SAF) → feature/finance/FinanceDialogs.kt
        if (handleFinanceActivityResult(requestCode, resultCode, data)) return
        if (requestCode == 3025) {
            if (resultCode == RESULT_OK) { val uri = data?.data ?: return; writeAppBackupV2(uri) } else backupPendingSel = null
            return
        }
        if (requestCode == 3026 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            startRestoreFlow(uri)
            return
        }
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val file = File(filesDir, "imports").apply { mkdirs() }
            val uriHash = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(16)
            val baseName = safeFileName(queryName(uri) ?: "import.txt")
            val out = File(file, "$uriHash-$baseName")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
            val granted = persistUriPermissionsSafely(uri, wantWrite = true)
            val canWrite = (granted and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
            editor(out)
            editorSourceUri = if (canWrite) uri else null
            if (canWrite) prefs.edit().putString("editor_source_uri", uri.toString()).apply()
            else prefs.edit().remove("editor_source_uri").apply()
        }
        if (requestCode == 1031 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val text = editorSaveAsText ?: editorBox?.text?.toString().orEmpty()
            runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
                    ?: error("Tidak bisa menulis file")
            }.onSuccess {
                val granted = persistUriPermissionsSafely(uri, wantWrite = true)
                val canWritePersistently = (granted and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                editorSourceUri = uri
                if (canWritePersistently) prefs.edit().putString("editor_source_uri", uri.toString()).apply()
                else prefs.edit().remove("editor_source_uri").apply()
                queryName(uri)?.let { editorNameLabel?.text = it }
                toast("Tersimpan di HP: ${queryName(uri) ?: "file"}")
            }.onFailure { toast("Gagal menyimpan: ${it.message}") }
            return
        }
        if (requestCode == 1002 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            inspectZipOrApk(uri)
        }
        if (requestCode == APK_COMPARE_A && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            apkCompareFirstUri = uri
            apkCompareTool()
            return
        }
        if (requestCode == APK_COMPARE_B && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            apkCompareSecondUri = uri
            apkCompareTool()
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
            ocrResultMeta?.text = "Belum ada hasil"
            ocrDetectedRow?.removeAllViews()
            ocrDetectedRow?.addView(subLabel("Belum ada data terdeteksi", 11f))
            ocrPreviewStatus?.text = "Gambar siap diproses"
            toast("Gambar dipilih")
            return
        }
        if (requestCode == OCR_CAMERA_REQUEST && resultCode == RESULT_OK) {
            val uri = pendingOcrCameraUri
            if (uri == null) { toast("Foto kamera tidak tersedia"); return }
            pendingOcrUri = uri
            pendingOcrPreview?.setImageURI(uri)
            pendingOcrView?.setText("")
            ocrResultMeta?.text = "Membaca foto…"
            ocrPreviewStatus?.text = "Foto kamera siap • OCR otomatis"
            toast("Foto diterima, sedang mengubah ke teks…")
            runOcr(uri) { text ->
                pendingOcrView?.setText(text)
                pendingOcrView?.setSelection(pendingOcrView?.text?.length ?: 0)
                val lines = text.lineSequence().count { it.isNotBlank() }
                val chars = text.length
                ocrResultMeta?.text = if (text.isBlank()) "Tidak ada teks terdeteksi" else "$lines baris • $chars karakter"
                updateOcrDetected(text, ocrDetectedRow, if (isDarkTheme) Color.WHITE else Color.rgb(18,18,20), if (isDarkTheme) Color.rgb(30,30,33) else Color.rgb(242,242,245), if (isDarkTheme) Color.rgb(62,62,66) else Color.rgb(210,210,214))
                if (text.isBlank()) toast("Tidak ada teks yang terdeteksi") else toast("Foto berhasil diubah menjadi teks")
            }
            return
        }
        if (requestCode == 1022 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val text = pendingOcrSaveText ?: return
            runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
                    ?: error("Tidak bisa menulis file")
            }.onSuccess {
                toast("TXT tersimpan")
            }.onFailure { toast("Gagal menyimpan TXT: ${it.message}") }
            pendingOcrSaveText = null
            return
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
                runOnToolUiThread { output(r) }
            }
        }
        if (requestCode == 1050 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            convPickedUri = uri
            convPickedName = queryName(uri) ?: "file"
            val ext = convPickedName?.substringAfterLast('.', "")?.uppercase(Locale.getDefault())
            convFromFormat = if (ext.isNullOrBlank()) "Otomatis terdeteksi" else ext
            // Format tujuan lama bisa jadi tidak valid untuk file baru (mis. sama dengan format asal).
            convCategories.find { it.id == convCategory }?.let { c ->
                val t = convToFormat
                if (t != null && convTargetSupport(c, t, convPickedName) != null) convToFormat = null
            }
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
            checksumUri = uri
            checksumTool(fromPicker = true)
        }
        if ((requestCode == 1201 || requestCode == 1202) && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            if (requestCode == 1201) {
                fileHashUriA = uri
                hashFileInfo(uri).let { fileHashCompareLabelA?.text = "File A: ${it.name}" + (if (it.size >= 0) " (${bytesText(it.size)})" else "") }
            } else {
                fileHashUriB = uri
                hashFileInfo(uri).let { fileHashCompareLabelB?.text = "File B: ${it.name}" + (if (it.size >= 0) " (${bytesText(it.size)})" else "") }
            }
            return
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }


    override fun onDestroy() {
        // Stop editor/live-preview callbacks before the Activity becomes unreachable.
        // EditorLivePreview is a singleton, so it must never retain this Activity/WebView.
        runCatching { EditorLivePreview.stopBufferPolling() }
        runCatching { EditorLivePreview.detach() }
        runCatching { destroyEditorWebViews() }
        runCatching { closeWebSocket() }
        runCatching { cancelEditorAutoSave() }
        BitPetSystem.unbind()
        runCatching { bitHoldReveal.release(force = true) }
        runCatching { if (::bitAnimationController.isInitialized) bitAnimationController.dispose() }
        // Bit (Lottie) cleanup: stop animator and release listeners.
        bitFace?.let {
            it.removeAllAnimatorListeners()
            it.cancelAnimation()
        }
        bitFace = null
        bitProcessFace?.let {
            it.removeAllAnimatorListeners()
            it.cancelAnimation()
        }
        bitProcessFace = null
        bitProcessErrorView = null
        runCatching { unregisterReceiver(colorPickerReceiver) }
        stopClipboardMonitor()
        modernUiRunnable?.let { modernUiHandler.removeCallbacks(it) }
        modernUiRunnable = null
        toolLoadingHandler.removeCallbacksAndMessages(null)
        ghHandler.removeCallbacksAndMessages(null)
        ghActionPoll?.removeCallbacksAndMessages(null)
        ghActionPoll = null
        ghActionRunnable = null
        ghActionPollWanted = false
        toolLoadingSpinner?.let { Motion.stopLoading(it) }
        networkScanStop.set(true)
        server?.close()
        server = null
        super.onDestroy()
    }


    // Lightweight native animations: no extra dependency, tuned for Android phones.

    // Hormati pengaturan sistem "Skala durasi animator" = 0 (Reduce Motion / hemat baterai).

    // Helper lama kini hanya mendelegasikan ke Motion agar hanya ada satu sistem animasi.


    // ---- Restored navigation / UI helpers ----
    // These small helpers are intentionally kept local to MainActivity so older
    // screens and newer GitHub screens can share the same rendering primitives.
    /**
     * Navigasi tab root (Beranda / Tools / Favorit / Settings).
     * Jika sudah di [targetPage] dan konten masih ada, jangan rebuild UI
     * (menghindari jank: removeAllViews → layout → scroll=0).
     */


    /** Ganti warna isi tanpa menghilangkan sudut tumpul (setBackgroundColor akan membuatnya kotak tajam). */


    // ---- Design system helpers (lihat DesignSystem.kt) ----


    /** Level 1: aksi utama (terisi). */


    /** Level 2: aksi pendukung (outline). */


    /** Level 3: aksi kecil (teks saja, tetap 48dp). */


    /**
     * Komponen state reusable: Loading / Success / Error / Empty / Info / Warning.
     * onRetry (opsional) menampilkan tombol "Coba lagi".
     */


    /** Logika Back tunggal: dipakai oleh onBackPressed (API < 33) dan OnBackInvokedCallback (API 33+). */


    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    @Suppress("DEPRECATION")
    internal fun invokeSuperOnBackPressed() {
        super.onBackPressed()
    }

    /** Android 16 (targetSdk 36) tidak lagi memanggil onBackPressed() untuk gestur/tombol Back sistem. */


    /** Ghost floating controls: no visible container, only a subtle icon shadow. */
    /**
     * Semua tombol top bar (menu, kembali, cari, profil, titik tiga/aksi) tampil sebagai
     * Floating Action Button mini: lingkaran, bayangan lembut, tanpa kotak pembungkus.
     */


    /**
     * Dipanggil setiap halaman baru dibuka. [show] = halaman ini punya tombol cari.
     * Kotak cari hanya muncul lewat tombol cari (di sebelahnya, di top bar).
     */


    /** Update ikon X di dalam search field hanya ketika ada query. */


    /** Tampilkan / sembunyikan kotak cari di samping tombol cari. */


    // Registry update tool: setiap kali versi tool berubah, tool otomatis masuk ke bagian "Terbaru".
    // Untuk rilis berikutnya cukup naikkan versi pada entry terkait.
    internal val toolUpdateCatalog = linkedMapOf(
        "githubzip" to "2.30.0",
        "creativestudio" to "2.50.0",
        "videoframes" to "2.51.3",
        "presentationstudio" to "2.51.0",
        "videoeditor" to "2.50.0",
        "webhostwifi" to "2.19.8",
        "webproject" to "2.19.8",
        "webeditor" to "2.19.8",
        "reminder" to "2.19.8",
        "networkstudio" to "2.19.8",
        "clipboard" to "2.19.8",
        "apkanalyzer" to "2.19.8",
        "fileencryption" to "2.20.0", "steganography" to "2.20.0", "passwordanalyzer" to "2.20.0", "breachchecker" to "2.20.0", "securenotes" to "2.20.0", "totpvault" to "2.20.0", "pgp" to "2.20.0", "sshkeygen" to "2.20.0", "certviewer" to "2.20.0", "virusscanner" to "2.20.0", "urlsafety" to "2.20.0"
    )


    /**
     * Bit is a small animated assistant placed between Tools and Favorit.
     * It is intentionally only visual for now; the real Q&A function can be wired
     * later without changing the navigation layout.
     */


    // ===== Bit Assistant / Local QA + Google fallback =====
    internal val bitChatMessages = mutableListOf<BitChatMessage>()
    internal var bitChatInput: EditText? = null


    /**
     * Warna Bit mengikuti tema aplikasi (tanpa kotak latar):
     * - Gelap : badan putih, mata gelap, Z putih
     * - Terang: badan gelap, mata putih, Z gelap
     */


    /**
     * User-facing micro documentation for every registered tool.
     * Keep this intentionally short: the card explains WHAT, the dialog explains WHEN/EXAMPLE.
     */


    // Shared full-width input used by the tools.
    // Normal fields are deliberately taller and multiline fields get substantially
    // more vertical space so text is edited in a real work area instead of a tiny box.


    // Shared modern utility layout. It keeps the monochrome identity while giving
    // each tool a clearer visual hierarchy instead of the old input-button-output stack.


    // Setiap tool wajib punya ikon: pakai nama ikon jika valid, kalau tidak cari dari nama tool.


    // V4: workspace helpers for complex tools. These keep domain logic untouched while
    // giving network/file/security/system tools a consistent mobile workspace hierarchy.


    // Shared controls for every tool: status, history and a contextual help panel.
    // Domain-specific controls remain inside each tool so the layout stays fast on mobile.


    // Ikon bantuan (?) kanan atas untuk semua tool: lihat showToolHelpSheet() (ToolEditorShell.kt).


    // ===================== 2.14 FINANCE DASHBOARD =====================

    // financeDashboardTool() moved to feature/finance/FinanceScreen.kt


    // ---------- V2.26: NETWORK / SYSTEM / APK / STORAGE TOOLS ----------






    // ---------- NEW TOOLS: CLIPBOARD / OCR / UNIT / APK / NETWORK SCANNER ----------


    /** Universal bridge: routes clipboard data into existing GITLS tools without duplicating data. */


    internal var pendingOcrView: EditText? = null
    internal var pendingOcrPreview: ImageView? = null
    internal var pendingOcrUri: Uri? = null
    internal var ocrPreviewStatus: TextView? = null
    internal var ocrResultMeta: TextView? = null
    internal var ocrDetectedRow: LinearLayout? = null
    internal var ocrTableMeta: TextView? = null
    internal var ocrTablePreview: LinearLayout? = null
    internal var ocrButtonTextState: Button? = null
    internal var pendingOcrSaveText: String? = null
    internal var pendingOcrCameraUri: Uri? = null
    internal val OCR_CAMERA_REQUEST = 1025
    internal val OCR_CAMERA_PERMISSION_REQUEST = 1026
    internal val COLOR_CAMERA_PERMISSION_REQUEST = 1027
    internal val QR_CAMERA_PERMISSION_REQUEST = 1028


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            OCR_CAMERA_PERMISSION_REQUEST -> if (granted) openOcrCamera() else toast("Izin kamera diperlukan untuk memotret dokumen")
            COLOR_CAMERA_PERMISSION_REQUEST -> if (granted) openColorPhotoCamera() else toast("Izin kamera diperlukan untuk memotret warna")
            QR_CAMERA_PERMISSION_REQUEST -> if (granted) scanQrWithCamera() else toast("Izin kamera diperlukan untuk memindai QR")
            LEGACY_STORAGE_PERMISSION_REQUEST -> toast(
                if (granted) "Izin penyimpanan diberikan. Jalankan/simpan lagi agar hasil masuk Galeri/Downloads."
                else "Tanpa izin penyimpanan, hasil tetap disimpan di folder aplikasi."
            )
        }
    }


    internal var pendingApkUri: Uri? = null
    internal var apkCompareFirstUri: Uri? = null
    internal var apkCompareSecondUri: Uri? = null
    internal var wsSocket: Socket? = null
    internal var wsInput: InputStream? = null
    internal var wsOutput: OutputStream? = null
    /** Guard + generation mencegah open/close WebSocket yang saling balapan. */
    internal val wsLock = Any()
    internal var wsGeneration: Long = 0L
    internal var pendingApkOutput: TextView? = null


    // ---------- NATIVE DEVICE / STORAGE / APP / NETWORK TOOLS ----------


    // ---------- FILE MANAGER / EDITOR ----------

    /** 0=skip, 1=replace, 2=rename, 3=cancel. Dialog runs on UI thread before worker. */


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


    // ===================== Navigasi internal Notifikasi =====================

    /** Kembali ke daftar Notifikasi (segar) tanpa menumpuk riwayat halaman. */


    /** Kembali ke form (Tambah/Edit) setelah memilih waktu/pengulangan/hari. */


    // ===================== 1. Daftar Notifikasi =====================


    // ===================== Kustom: pembuat percakapan notifikasi =====================


    


    // ===================== 2 / 6 / 7. Tambah, Tambah - Detail, Edit =====================


    // ===================== 5. Pilih Waktu =====================


    // ===================== 3. Pilih Tanggal / Pengulangan =====================


    // ===================== 4. Pilih Hari =====================


    // ===================== 8. Detail Notifikasi =====================


    /**
     * Layout full layar: header (menu/cari/profil) melayang DI ATAS konten, tidak lagi memakai
     * baris penampung sendiri. Konten ScrollView digambar sampai ke belakang status bar dan
     * gesture bar; padding atas/bawah hanya menjaga posisi awal agar item pertama tidak tertutup.
     */


    /** Simpan: timpa file asli di HP bila dibuka dari HP, selain itu simpan salinan internal. */


    /** Simpan sebagai: buka pemilih lokasi Android (ACTION_CREATE_DOCUMENT). */


    /** Daftar masalah di editor; ketuk satu untuk melompat ke barisnya. */


    /** Pratinjau perubahan; kode baru diterapkan hanya setelah pengguna menekan Terapkan. */


    // ---------- GITHUB ZIP PUBLISHER ----------
    // Tiga layar dalam satu halaman: Pengaturan -> Proses Upload -> Upload Selesai.


    internal var ghStage: FrameLayout? = null
    internal var ghSource = 0
    internal var ghBranch = "main"
    internal var ghSaveToken = true
    internal var ghPrivateRepo = true
    @Volatile internal var ghRunning = false
    internal val ghRunLock = Any()
    internal var ghStartedAt = 0L
    internal var ghCurrentStep = 0
    internal var ghFileTotal = 0
    internal var ghRetry: (() -> Unit)? = null
    internal var ghLastResult: GhResult? = null
    internal var ghActionPoll: Handler? = null
    internal var ghActionRunnable: Runnable? = null
    internal var ghActionLastFailedJob: GhActionJob? = null
    internal var ghUserValue = ""
    internal var ghRepoValue = ""
    internal var ghTokenValue = ""
    internal var ghCommitValue = ""
    @Volatile internal var ghCancelled = false
    internal var ghPendingResult: GhResult? = null
    internal var ghPendingError: Throwable? = null
    internal var ghFinishedSec = -1
    internal var ghPct = 0
    internal var ghAppForeground = true
    internal val ghStepLast = arrayOfNulls<GhProgress>(5)
    internal var ghCancelBtn: LinearLayout? = null
    internal var ghCancelLabel: TextView? = null
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

    internal val ghInk: Int get() = ghc(0xFFF4F4F6, 0xFF15161A)
    internal val ghOnInk: Int get() = ghc(0xFF15161A, 0xFFFFFFFF)
    internal val ghCard: Int get() = ghc(0xFF1B1D22, 0xFFFFFFFF)
    internal val ghStroke: Int get() = ghc(0xFF34373F, 0xFFE3E5EA)
    internal val ghMuted: Int get() = ghc(0xFF9DA0A9, 0xFF6C717C)
    internal val ghSoft: Int get() = ghc(0xFF23262C, 0xFFF0F1F4)
    internal val ghDanger: Int get() = 0xFFD9534F.toInt()

    // ----- komponen form -----

    override fun onStart() { super.onStart(); ghAppForeground = true }
    override fun onStop() { ghAppForeground = false; super.onStop() }

    // ----- Polling hanya berjalan saat Activity terlihat -----
    internal var batteryCache: Intent? = null
    internal var batteryCacheAt = 0L

    /** Intent baterai (sticky) di-cache 1 detik agar tidak registerReceiver berulang-ulang. */


    internal var ghActionPollWanted = false

    override fun onPause() {
        BitPetSystem.stopAutonomy()
        runCatching { if (::bitAnimationController.isInitialized) bitAnimationController.setLifecycleActive(false) }
        modernUiRunnable?.let { modernUiHandler.removeCallbacks(it) }
        ghHandler.removeCallbacks(ghTicker)
        ghActionRunnable?.let { ghActionPoll?.removeCallbacks(it) }
        // Pause WebView renderer/polling while the Activity is backgrounded (e.g. file picker).
        runCatching { pauseEditorWebViews() }
        runCatching { EditorLivePreview.stopBufferPolling() }
        // Auto-simpan SEMUA tool (editor + form tools lain) agar data terbaru tidak hilang.
        runCatching {
            harvestToolSessionFromContent()
            persistToolSessionFields()
            // Jangan biarkan debounce lama berjalan setelah Activity kehilangan fokus.
            cancelEditorAutoSave()
            flushEditorCurrentToBuffer(writeDisk = false)
            // Persist editor drafts off the main thread. onPause must remain fast on
            // low-end devices; the current EditText has already been copied to memory.
            ToolPerformance.toolExecutor.execute { runCatching { persistAllEditorBuffers() } }
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        runCatching { if (::bitAnimationController.isInitialized) bitAnimationController.setLifecycleActive(true) }
        if (currentPage == "Bit Room") BitPetSystem.startAutonomy()
        modernUiRunnable?.let { modernUiHandler.removeCallbacks(it); modernUiHandler.post(it) }
        runCatching { resumeEditorWebViews() }
        if (currentPage == "Editor" && editorLivePreviewWeb != null) {
            runCatching { EditorLivePreview.startBufferPolling(this, editorLivePreviewWeb!!, 900L) }
        }
        if (ghRunning) { ghHandler.removeCallbacks(ghTicker); ghHandler.post(ghTicker) }
        val poll = ghActionRunnable
        if (ghActionPollWanted && poll != null) { ghActionPoll?.removeCallbacks(poll); ghActionPoll?.post(poll) }
    }


    // ---------- ZIP ----------


    // ---------- SIMPLE TOOLS ----------


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


    /** Layer penuh untuk melihat seluruh warna hasil ekstraksi tanpa terpotong. */


    // ==================== CALCULATOR SUITE ====================


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


    // ==================== EXTRA CALCULATORS 2.4 ====================


    /**
     * Calculator hub entry for UI color conversion.
     * Reuses the screen color picker implementation to avoid duplicating
     * color parsing/conversion state and UI logic.
     */


    // ===================== Pengelola Keuangan =====================
    // All finance UI (reader, dialogs, components) extracted to:
    //   feature/finance/FinanceScreen.kt
    //   feature/finance/FinanceDialogs.kt
    //   feature/finance/FinanceComponents.kt
    // Vars kept here for shared state:
    /** Tab aktif layar Keuangan: 0 Ringkas, 1 Riwayat, 2 Grafik, 3 Anggaran, 4 Rencana. */
    internal var financeTab = 0
    /** Bulan terpilih, filter riwayat, dan sub-tab Rencana. */
    internal val financeUi = FinanceUiState()
    internal var pendingFinanceExportJson = false
    // ==============================================================




    // ---------- NETWORK ----------


    // ---------- APK / QR / SYSTEM ----------


    // ---------- QR SCANNER (tampilan 3 langkah: awal, pilih sumber, panel sumber) ----------

    internal var qrSourceExpanded = false
    internal var qrSelectedSource: String? = null // "file" | "foto" | "teks"
    internal var qrPickedUri: Uri? = null
    internal var qrPickedName: String? = null
    internal var qrScanBusy = false
    internal var qrScanResult: String? = null
    /** Kartu hasil terakhir dari output() — diganti, tidak menumpuk. */
    internal var lastOutputCard: android.view.View? = null
    internal var qrCameraOutUri: Uri? = null
    internal val qrSources = listOf(
        Triple("file", "▤", "File"),
        Triple("foto", "▧", "Foto & Scan"),
        Triple("teks", "✎", "Teks / Link")
    )


    // ---------- V2.27: WORKSPACE / PLUGIN / CUSTOMIZATION ----------


    // ---------- NEW TOOLS 2.0 ----------


    // ---------- CRYPTO / HELPERS ----------


    // ---------- COMPLETE TOOL EXPANSION ----------


    internal var fileHashUriA: Uri? = null
    internal var fileHashUriB: Uri? = null
    internal var checksumUri: Uri? = null
    internal var fileHashCompareLabelA: TextView? = null
    internal var fileHashCompareLabelB: TextView? = null


    internal var securityFileUri: Uri? = null
    internal var securityFileName: String? = "file"
    // State File Encryption (dipakai SecurityTools.kt)
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
    internal val APK_COMPARE_A = 1311
    internal val APK_COMPARE_B = 1312
    internal val SECURITY_OPEN_PICK = 1305
    internal var lockedOpenPassword: String? = null
    internal val STEGO_ENCODE_PICK = 1302
    internal val STEGO_DECODE_PICK = 1303
    internal val CERT_PICK = 1304

    /** Daftar file .mytools.enc hasil enkripsi di aplikasi + opsi memilih dari penyimpanan. */


    /** Dekripsi ke cache lalu buka dengan aplikasi yang cocok (tipe dari ekstensi asli). */


    /** Decode gambar dengan inSampleSize agar foto besar tidak memenuhi RAM. */


    // Steganografi harus memakai resolusi penuh (sampling akan merusak bit tersembunyi),
    // jadi optimasinya: decode langsung mutable (tanpa copy) dan proses piksel per baris.


    /** Baca header "MYTOOLS-STG1:<panjang>:" lalu tepat <panjang> karakter; berhenti begitu pesan lengkap. */


    internal var imageStudioResult: ((Uri)->Unit)? = null
}
