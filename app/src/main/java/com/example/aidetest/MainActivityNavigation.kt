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


private data class ToolUiContext(val page: String, val generation: Long)
private val toolUiContext = ThreadLocal<ToolUiContext?>()

/**
 * Menjaga callback tool async agar hasil lama tidak masuk ke halaman yang sudah berubah.
 * Context hanya aktif di worker yang dibuat oleh toolThread().
 */
internal fun MainActivity.runOnToolUiThread(action: () -> Unit) {
    val ctx = toolUiContext.get()
    runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        if (ctx != null && (ctx.page != currentPage || ctx.generation != toolUiGeneration)) return@runOnUiThread
        action()
    }
}

internal fun MainActivity.ensureToolLoadingOverlay() {
        if (toolLoadingOverlay != null) return
        val rootFrameView = runCatching { rootFrame }.getOrNull() ?: return
        val overlay = FrameLayout(this).apply {
            setPadding(dp(14), dp(8), dp(16), dp(8))
            elevation = dp(10).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(if (isDarkTheme) 0xF21C1C1F.toInt() else 0xEEFFFFFF.toInt())
            }
        }
        val loadingTint = if (isDarkTheme) Color.WHITE else Color.rgb(32, 33, 36)
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            // Loading indicator mengikuti tema: gelap pada light mode, putih pada dark mode.
            indeterminateTintList = ColorStateList.valueOf(loadingTint)
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26)).apply {
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
        rootFrameView.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, dp(48)
        ).apply {
            // Letakkan loading dekat pusat layar, bukan menempel di bawah status/top bar.
            gravity = Gravity.CENTER
            topMargin = dp(18)
        })
        toolLoadingOverlay = overlay
        toolLoadingSpinner = spinner
        toolLoadingText = label
    }
internal fun MainActivity.showToolLoading() {
        if (isFinishing) return
        ensureToolLoadingOverlay()
        val overlay = toolLoadingOverlay ?: return
        // Batalkan fade-out yang masih berjalan agar tidak menyembunyikan overlay yang baru muncul.
        overlay.animate().cancel()
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.translationY = dp(-10).toFloat()
        overlay.scaleX = 0.96f
        overlay.scaleY = 0.96f
        overlay.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        toolLoadingSpinner?.let { Motion.startLoading(it) }
    }
internal fun MainActivity.hideToolLoading() {
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
internal fun MainActivity.toolThread(label: String = "Memproses…", showLoading: Boolean = true, block: () -> Unit) {
        activeToolTasks.incrementAndGet()
        val taskContext = ToolUiContext(currentPage, toolUiGeneration)
        val showRunnable = Runnable {
            if (activeToolTasks.get() > 0 && !isFinishing) {
                ensureToolLoadingOverlay()
                toolLoadingText?.text = label
                showToolLoading()
            }
        }
        if (showLoading) {
            toolLoadingHandler.postDelayed(showRunnable, 280L)
        }
        // Shared bounded pool — avoids creating unlimited threads under load.
        try {
            ToolPerformance.toolExecutor.execute {
                toolUiContext.set(taskContext)
                try {
                    block()
                } finally {
                    toolUiContext.remove()
                    toolLoadingHandler.removeCallbacks(showRunnable)
                    runOnUiThread {
                        if (activeToolTasks.decrementAndGet() <= 0) {
                            activeToolTasks.set(0)
                            hideToolLoading()
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            toolLoadingHandler.removeCallbacks(showRunnable)
            if (activeToolTasks.decrementAndGet() <= 0) activeToolTasks.set(0)
            toast("Terlalu banyak proses aktif. Coba lagi sebentar.")
            hideToolLoading()
        }
    }
internal fun MainActivity.isTouchInsideNonPagingArea(rawX: Float, rawY: Float): Boolean {
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
        // Saat laci overlay terbuka, geser horizontal tidak boleh memindah halaman root.
        if (OverlayBack.hasOpen()) return true

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
internal fun MainActivity.swipeRootPage(next: Boolean) {
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
internal fun MainActivity.isEditorWorkspace(): Boolean =
        !editorLanding && (currentPage == "Editor" || currentPage.startsWith("Editor - ")) && editorBox != null

internal fun MainActivity.syncEditorBottomBar() {
        val editorBottomBarView = runCatching { editorBottomBar }.getOrNull() ?: return
        val show = isEditorWorkspace()
        editorBottomBarView.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            editorBottomBarView.alpha = 1f
            editorBottomBarView.translationY = 0f
            editorBottomBarView.bringToFront()
            // Snapshot restore/theme refresh can clear the global bar. Rebuild it
            // from the active editor when that happens instead of showing a blank area.
            if (editorBottomBarView.childCount == 0) {
                editorBox?.let { renderEditorBottomBar(it) }
            }
        } else {
            editorBottomBarView.alpha = 1f
            editorBottomBarView.translationY = 0f
        }
    }
internal fun MainActivity.enableImmersiveFullscreen() {
        // oldt.py explicitly keeps Android system bars visible.
        // Full layar: konten digambar sampai belakang status bar & gesture bar.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = EDGE_FLAGS or
                (if (Build.VERSION.SDK_INT >= 23) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0)
    }
internal fun MainActivity.showStartupFallback(error: Throwable) {
        // Last-resort launch screen: if a future UI change breaks showHome(),
        // keep the APK open instead of letting the Activity crash back to launcher.
        runCatching {
            loginScreen.visibility = View.GONE
            mainContainer.visibility = View.VISIBLE
            bottomNav.visibility = View.GONE
            search.visibility = View.GONE
            content.removeAllViews()
            lastOutputCard = null
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
internal fun MainActivity.setupDynamicShortcuts() {
        if (Build.VERSION.SDK_INT < 25 || BuildConfig.STUDIO_ONLY) return
        val sm = getSystemService(ShortcutManager::class.java) ?: return
        val icon = android.graphics.drawable.Icon.createWithResource(this, R.mipmap.app_icon)
        val shortcuts = listOf(
            ShortcutInfo.Builder(this, "expense")
                .setShortLabel("+ Pengeluaran")
                .setLongLabel("Tambah pengeluaran")
                .setIcon(icon)
                .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("quick_expense", true))
                .build(),
            ShortcutInfo.Builder(this, "income")
                .setShortLabel("+ Pemasukan")
                .setLongLabel("Tambah pemasukan")
                .setIcon(icon)
                .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("quick_income", true))
                .build(),
            ShortcutInfo.Builder(this, "finance")
                .setShortLabel("Keuangan")
                .setLongLabel("Finance Dashboard")
                .setIcon(icon)
                .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("open_finance", true))
                .build(),
        ) + listOf(
            // Pintasan editing (dari Editing Edition 2.50.1), ditambahkan di samping pintasan keuangan.
            Triple("studio", "Foto Studio", "creativestudio"),
            Triple("photo", "Edit Foto", "imagestudio"),
            Triple("color", "Warna", "color"),
        ).filter { it.third in homeToolMap }.map { (id, label, tool) ->
            ShortcutInfo.Builder(this, id)
                .setShortLabel(label)
                .setLongLabel("Buka $label")
                .setIcon(icon)
                .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("open_tool", tool))
                .build()
        }
        // Launcher menolak daftar yang melebihi batasnya; sisakan yang muat sesuai urutan.
        sm.dynamicShortcuts = shortcuts.take(sm.maxShortcutCountPerActivity.coerceAtLeast(1))
    }
internal fun MainActivity.scheduleFinanceMaintenance() {
        val request = PeriodicWorkRequest.Builder(FinanceMaintenanceWorker::class.java, 24, java.util.concurrent.TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("mytools_finance_maintenance", ExistingPeriodicWorkPolicy.KEEP, request)
    }
internal fun MainActivity.applySystemTheme() {
        val forced = runCatching { prefs.getString("theme_mode", "system") ?: "system" }.getOrDefault("system")
        val ui = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isDarkTheme = when (forced) {
            "dark" -> true
            "light" -> false
            else -> ui != Configuration.UI_MODE_NIGHT_NO
        }
        if (!isDarkTheme) {
            // Light: soft warm-neutral canvas, near-white cards, hairline borders
            dark = Color.rgb(248, 248, 249)
            panel = Color.rgb(255, 255, 255)
            panel2 = Color.rgb(242, 242, 244)
            textMain = Color.rgb(20, 20, 22)
            textMuted = Color.rgb(104, 104, 112)
            line = Color.rgb(224, 224, 228)
        } else {
            // Dark: deep charcoal, elevated panels, soft dividers
            dark = Color.rgb(12, 12, 13)
            panel = Color.rgb(20, 20, 22)
            panel2 = Color.rgb(30, 30, 33)
            textMain = Color.rgb(245, 245, 247)
            textMuted = Color.rgb(158, 158, 164)
            line = Color.rgb(48, 48, 52)
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
        // Theme changes must propagate to every attached Bit instance immediately.
        bitAnim?.refreshTheme()
        // Repaint an already-open code editor with the correct syntax palette.
        editorBox?.let { box -> runCatching { EditorSyntax.apply(box.text, editorMode, isDarkTheme) } }
    }
internal fun MainActivity.applyBottomNavShape() {
        // Floating pill nav — soft elevation, hairline border
        val backgroundColor = if (isDarkTheme) Color.rgb(20, 20, 24) else Color.WHITE
        bottomNav.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(backgroundColor)
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1), if (isDarkTheme) Color.rgb(48, 48, 54) else Color.rgb(232, 234, 238))
        }
        bottomNav.elevation = dp(10).toFloat()
        bottomNav.clipToOutline = true
        if (Build.VERSION.SDK_INT >= 21) bottomNav.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(28).toFloat())
            }
        }
    }
internal fun MainActivity.applyUiColors() {
        mainContainer.setBackgroundColor(dark)
        content.setBackgroundColor(dark)
        // Global toolbar/editor surfaces must follow the active theme too.
        title.setTextColor(textMain)
        subtitle.setTextColor(textMuted)
        back.setTextColor(textMain)
        action.setTextColor(textMain)
        editorMore.setTextColor(textMain)
        editorBottomBar.setBackgroundColor(if (isDarkTheme) panel else Color.WHITE)

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
/** True bila pengguna sudah memilih Masuk/Daftar atau Lanjut tanpa akun sebelumnya. */
internal fun MainActivity.hasChosenAccessMode(): Boolean {
    val prefs = profilePrefs()
    return prefs.getBoolean("registered", false) || prefs.getBoolean("guest_chosen", false)
}
/** Tampilkan layar login hanya pada peluncuran pertama; selanjutnya langsung ke aplikasi. */
internal fun MainActivity.showLoginOrEnter() {
    if (hasChosenAccessMode() || BuildConfig.STUDIO_ONLY) { enterApp(); return }
    mainContainer.visibility = View.VISIBLE
    loginScreen.visibility = View.VISIBLE
    loginScreen.bringToFront()
}
internal fun MainActivity.enterApp() {
        loginScreen.visibility = View.GONE
        mainContainer.visibility = View.VISIBLE
        // Do not force Light here: the Theme setting must be respected on every
        // entry into the app, including after login, shortcuts, and deep links.
        applySystemTheme()
        applyUiColors()
        // APK Foto Studio: aplikasi = studio; tidak ada Beranda/daftar tool.
        if (BuildConfig.STUDIO_ONLY) { bottomNav.visibility = View.GONE; pendingDeepLink = null; creativeStudioTool(); return }
        showHome()
        appEntered = true
        applyPendingDeepLink()
    }

/** OPEN-01: tujuan deep link dari extra intent (widget, quick tile, shortcut peluncur, notifikasi). */
internal fun deepLinkTarget(intent: Intent?): String? {
    if (intent == null) return null
    return when {
        intent.getBooleanExtra("quick_expense", false) -> "quick_expense"
        intent.getBooleanExtra("quick_income", false) -> "quick_income"
        intent.getBooleanExtra("open_finance", false) -> "open_finance"
        intent.getBooleanExtra("open_github_upload", false) -> "open_github_upload"
        !intent.getStringExtra("open_tool").isNullOrBlank() -> "tool:" + intent.getStringExtra("open_tool")
        else -> null
    }
}

/** Hapus extra deep link agar rotasi/recreate tidak membuka tujuan yang sama dua kali. */
internal fun consumeDeepLinkExtras(intent: Intent?) {
    intent ?: return
    listOf("quick_expense", "quick_income", "open_finance", "open_github_upload", "open_tool").forEach { intent.removeExtra(it) }
}

/** Terapkan deep link tertunda. Dipanggil setelah Beranda tampil (splash & login selesai). */
internal fun MainActivity.applyPendingDeepLink() {
    val target = pendingDeepLink ?: return
    pendingDeepLink = null
    runCatching {
        when (target) {
            "open_finance" -> openFinanceFromIntent(android.content.Intent().putExtra("finance_tab", pendingFinanceTab))
            "quick_expense" -> { financeReaderTool(0); showAddTxDialog(FinanceDb(this), false) }
            "quick_income" -> { financeReaderTool(0); showAddTxDialog(FinanceDb(this), true) }
            "open_github_upload" -> openTool("githubzip")
            else -> if (target.startsWith("tool:")) {
                // Hanya id yang terdaftar (atau alias lama Foto Studio) yang boleh dibuka dari luar.
                val tool = target.removePrefix("tool:")
                if (CsToolIds.canonical(tool) in homeToolMap) openTool(tool)
            }
        }
    }.onFailure { android.util.Log.w("GITLS", "Deep link $target failed", it) }
}
internal fun MainActivity.applyLightAppTheme() {
        isDarkTheme = false
        dark = Color.rgb(248, 248, 249)
        panel = Color.rgb(255, 255, 255)
        panel2 = Color.rgb(242, 242, 244)
        textMain = Color.rgb(20, 20, 22)
        textMuted = Color.rgb(104, 104, 112)
        line = Color.rgb(224, 224, 228)
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
internal fun MainActivity.visualTheme(name: String = currentPage): ToolVisualTheme {
        // MyTools uses one consistent monochrome UI. Tool categories may still
        // have different labels, but never introduce colored buttons/accent panels.
        return ToolVisualTheme(
            textMain,
            panel2,
            line,
            when {
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
internal fun MainActivity.toolAccentStrip(name: String): View {
    val activity = this
    val t = visualTheme(name)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = bg(t.surface, 14, t.border)
        addView(View(activity).apply { background = bg(t.accent, 3) }, LinearLayout.LayoutParams(dp(5), dp(30)))
        addView(TextView(activity).apply {
            text = t.chip
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(t.accent)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(activity).apply {
            text = "● READY"
            textSize = 10f
            setTextColor(Color.rgb(105, 110, 116))
        })
    }
}
internal fun MainActivity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

internal fun MainActivity.animationsEnabled(): Boolean = Motion.enabled

internal fun MainActivity.systemReduceMotion(): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

internal fun MainActivity.animateEditorItem(view: View, delay: Long = 0L, distance: Float = 18f) =
        Motion.enter(view, delay, distance, 240L)

internal fun MainActivity.animateEditorPress(view: View) = Motion.tap(view, 0.96f)

internal fun MainActivity.animateEditorScreen() = Motion.enter(content, 0L, 8f, 220L)

internal fun MainActivity.navigateRoot(targetPage: String? = null, render: () -> Unit) {
        if (restoringSnapshot) return
        if (targetPage != null &&
            currentPage == targetPage &&
            content.childCount > 0 &&
            !resettingRootNavigation
        ) {
            selectBottomNav(targetPage)
            return
        }
        // SEBELUM clear stack: simpan data form tool yang sedang dibuka (data TERBARU).
        runCatching { harvestToolSessionFromContent(); persistToolSessionFields() }
        pageBackStack.clear()
        resettingRootNavigation = true
        try { render() } finally { resettingRootNavigation = false }
    }

/** Sembunyikan dan kosongkan dock aksi tool. Dipanggil saat ganti halaman / back. */
internal fun MainActivity.clearToolActionDock() {
    runCatching {
        toolActionDock.animate().cancel()
        toolActionDock.removeAllViews()
        toolActionDock.visibility = View.GONE
        toolActionDock.alpha = 1f
        toolActionDock.translationY = 0f
    }
}

/** Places a tool's primary action in the fixed dock below the scrollable form. */
internal fun MainActivity.pinToolAction(view: View) {
    (view.parent as? android.view.ViewGroup)?.removeView(view)
    // Tombol di dock bawah selalu aksi utama → gaya solid.
    if (view is TextView) styleAsPrimary(view)
    toolActionDock.animate().cancel()
    toolActionDock.removeAllViews()
    toolActionDock.addView(view, LinearLayout.LayoutParams(-1, -2))
    // The dock now provides the bottom breathing room; avoid keeping the old 104dp
    // scroll padding, which caused a large empty tail beneath the last form field.
    content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, dp(12))
    val wasVisible = toolActionDock.visibility == View.VISIBLE
    toolActionDock.visibility = View.VISIBLE
    if (animationsEnabled() && !wasVisible) {
        toolActionDock.alpha = 0f
        toolActionDock.translationY = dp(8).toFloat()
        toolActionDock.animate().alpha(1f).translationY(0f).setDuration(180L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    } else {
        toolActionDock.alpha = 1f
        toolActionDock.translationY = 0f
    }
}

internal fun MainActivity.clearPage(name: String = currentPage, pushBack: Boolean = true) {
        clearToolActionDock()
        // Semua halaman (termasuk Video Editor) berjalan di portrait; kembalikan bila ada sisa landscape.
        if (requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // Toolbar + laci Tool Editor Shell hanya untuk halaman tool; disembunyikan setiap ganti halaman.
        hideToolEditorShell()
        // Restore normal page padding when navigating away from a fixed-action form.
        content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, dp(104))
        // Invalidate async tool callbacks before rebuilding/replacing the page.
        toolUiGeneration++
        // Selalu harvest field form halaman lama → data terbaru, meski pindah ke Home.
        if (!restoringSnapshot) {
            runCatching { harvestToolSessionFromContent() }
        }
        // P0: hancurkan WebView SEBELUM snapshot/remove agar tidak bocor / tidak di-restore.
        if (!restoringSnapshot && (currentPage == "Preview" || editorLivePreviewWeb != null)) {
            runCatching { destroyEditorWebViews() }
        }
        // Jangan push back-stack jika hanya re-render halaman yang sama (hindari "kue lapis").
        // Contoh: ganti mode di dalam tool, refresh hasil, buka file lain di Editor, dll.
        val samePageRerender = pushBack && name == currentPage && content.childCount > 0
        if (!restoringSnapshot && pushBack && !resettingRootNavigation && !samePageRerender) {
            // Jangan simpan snapshot yang berisi WebView (Preview) — rebuild ringan saja
            if (currentPage != "Preview") {
                saveCurrentPageSnapshot()
            }
        }
        content.removeAllViews()
        content.minimumHeight = 0
        content.layoutParams?.let { if (it.height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) { it.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT; content.layoutParams = it } }
        lastOutputCard = null
        modernUiRunnable?.let { modernUiHandler.removeCallbacks(it) }
        modernUiRunnable = null
        currentPage = name
        toolFieldSeq = 0 // reset counter field untuk render halaman baru
        if (name != "Bit Assistant") {
            bitChatTopFace?.let { face ->
                (face.parent as? ViewGroup)?.removeView(face)
            }
            bitChatTopFace = null
        }
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
            "GitHub Publisher" -> "GITLS Publisher"
            else -> name
        }
        // Judul tool ditampilkan di top bar di sebelah tombol < (kembali),
        // bukan di kartu header terpisah di dalam konten.
        val showTitleInTopBar = !isHome
        findViewById<View>(R.id.headerTitleBox)?.let { header ->
            header.visibility = if (showTitleInTopBar) View.VISIBLE else View.GONE
        }
        if (showTitleInTopBar) {
            // Bit Room menyembunyikan title; kembalikan di setiap halaman lain
            // supaya judul selalu tampil di sebelah tombol < .
            title.visibility = View.VISIBLE
            title.alpha = 1f
            title.textSize = if (name == "home") 22f else 18f
            title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
            title.maxLines = 1
            title.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // Tombol kembali, judul, dan ikon bot berdiri sendiri (tanpa card/bar penopang, tanpa bayangan).
        // Hanya ada fade setipis warna latar di belakangnya supaya teks tidak bertabrakan dengan
        // konten yang di-scroll; saat di posisi atas fade ini menyatu dengan latar halaman.
        topBar.background = if (showTitleInTopBar) {
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(dark, (dark and 0x00FFFFFF) or (0xD0 shl 24), dark and 0x00FFFFFF)
            )
        } else null
        topBar.outlineProvider = null   // elevation tetap (urutan sentuhan), tapi tanpa bayangan
        // Subtitle hanya di beranda; di halaman tool top bar tetap ringkas.
        subtitle.visibility = if (isHome) View.VISIBLE else View.GONE
        homeMenu.visibility = if (isHome) View.VISIBLE else View.GONE
        homeSearch.visibility = if (showRootSearch) View.VISIBLE else View.GONE
        homeProfile.visibility = if (isHome) View.VISIBLE else View.GONE
        back.visibility = if (isHome) View.GONE else View.VISIBLE
        action.visibility = if (isHome) View.GONE else View.VISIBLE
        // Space filler hanya dipakai saat search/home; di halaman tool biarkan
        // judul memakai sisa ruang di sebelah tombol < .
        searchFill.visibility = if (showRootSearch || isHome) View.VISIBLE else View.GONE

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
        // Padding default: tool pages get consistent gutters; root/home set their own.
        if (!root && name != "Editor" && name != "Preview" && name != "Bit Assistant") {
            content.setPadding(dp(Ds.CONTENT_H), dp(Ds.CONTENT_V), dp(Ds.CONTENT_H), dp(Ds.CONTENT_BOTTOM))
        }
        // Normalize after the page renderer has finished adding its content.
        // This removes any page-local title that duplicates the global toolbar title.
        content.post { normalizeToolContentHeader() }
        // Pasang UI/UX editor (toolbar bawah, laci, bagian buka-tutup, undo/redo kolom)
        // setelah renderer tool selesai membangun kontennya.
        content.post { applyToolEditorShell(name) }
        scroll.post { scroll.scrollTo(0, 0) }
    }
internal fun MainActivity.addPressFeedback(view: View) = Motion.press(view)

internal fun MainActivity.applyInteractiveSurface(view: View, radius: Int = Ds.RADIUS_LG, elevationDp: Int = 1) {
        view.background = rippleBg(if (isDarkTheme) panel2 else Color.WHITE, radius, line)
        if (elevationDp > 0) view.elevation = dp(elevationDp).toFloat() * 0.5f
        view.clipToOutline = true
    }
internal fun MainActivity.label(text: String, sizeSp: Float = 15f, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(textMain)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            includeFontPadding = false
            letterSpacing = if (bold && sizeSp >= 17f) (-0.01f) else 0f
        }

internal fun MainActivity.subLabel(text: String, sizeSp: Float = 12f): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(textMuted)
            includeFontPadding = false
            setLineSpacing(0f, 1.2f)
        }

internal fun MainActivity.toolCard(id: String, name: String, iconName: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD))
            minimumHeight = dp(72)
            contentDescription = "$name. Buka tool"
            minimumHeight = dp(68)
        }
        applyInteractiveSurface(card, Ds.RADIUS_LG, 0)
        card.setOnClickListener { openToolWithPress(id, card) }
        // Favorit dikelola dari kartu (tahan lama), bukan dari dalam tool.
        bindFavoriteLongPress(card, id)

        val iconBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = bg(if (isDarkTheme) Color.rgb(42, 42, 46) else Color.rgb(238, 242, 245), Ds.RADIUS_SM)
        }
        iconBox.addView(MdiIconView(this).apply {
            setIconName(iconName)
            setIconSize(22f)
            setTextColor(if (isDarkTheme) Color.WHITE else Color.rgb(70, 80, 90))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(iconBox, LinearLayout.LayoutParams(dp(44), dp(44)))
        // Sentuhan kartu: scale kecil + shadow turun + ikon bereaksi sesuai karakter Tool.
        val toolCharacter = Motion.characterFor(id)
        Motion.press(card, 0.98f, shadowDp = -1f, onDown = {
            Motion.characterIcon(iconBox.getChildAt(0) ?: iconBox, toolCharacter)
        })

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(6), 0)
        }
        texts.addView(label(name, 14f, true))
        texts.addView(subLabel(toolHelp(id, name).purpose, 10.5f).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(MdiIconView(this).apply {
            setIconName("information-outline")
            setIconSize(18f)
            setTextColor(textMuted)
            alpha = 0.55f
            contentDescription = "Info $name"
            setOnClickListener { showToolHelpDialog(id) }
        }, LinearLayout.LayoutParams(dp(36), dp(48)))
        card.addView(TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            contentDescription = "Buka"
        }, LinearLayout.LayoutParams(dp(28), dp(48)))
        return card
    }
internal fun MainActivity.bg(color: Int, radius: Int = 16, stroke: Int? = null): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            // Legacy tools still contain many hard-coded light card colors.
            // Remap only neutral near-white fills/borders when dark mode is active.
            val fill = Ds.darkSurfaceFill(color, isDarkTheme, panel, panel2)
            val border = stroke?.let { Ds.darkBorder(it, isDarkTheme, line) }
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (border != null) setStroke(dp(1), border)
        }

internal fun MainActivity.keepShapeFill(v: View, color: Int) {
        val d = v.background
        if (d is android.graphics.drawable.GradientDrawable) { d.mutate(); (v.background as android.graphics.drawable.GradientDrawable).setColor(color) }
        else v.setBackgroundColor(color)
    }
internal fun MainActivity.rippleBg(fill: Int, radius: Int = Ds.RADIUS_MD, stroke: Int? = null): Drawable {
        val base = bg(fill, radius, stroke)
        val rippleColor = ColorStateList.valueOf(
            if (isDarkTheme) Color.argb(48, 255, 255, 255) else Color.argb(36, 0, 0, 0)
        )
        val mask = bg(Color.WHITE, radius)
        return RippleDrawable(rippleColor, base, mask)
    }
internal fun MainActivity.statusColor(state: Ds.State): Int = Ds.statusColor(state, isDarkTheme)

internal fun MainActivity.isSecondaryAction(text: String): Boolean {
        val t = text.trim().lowercase(Locale.ROOT)
        return listOf(
            "salin", "copy", "bagikan", "share", "bersihkan", "clear", "hapus", "reset",
            "batal", "cancel", "tutup", "close", "kembali", "back", "acak ulang"
        ).any { t == it || t.startsWith("$it ") }
    }
/** Aksi yang pantas solid: memproses, menyimpan, mengirim, atau mengekspor. */
internal fun MainActivity.isPrimaryAction(text: String): Boolean {
        val t = text.trim().lowercase(Locale.ROOT)
        if (isSecondaryAction(text)) return false
        return listOf(
            "simpan", "save", "ekspor", "export", "proses", "process", "jalankan", "run", "trace",
            "hitung", "calculate", "generate", "konversi", "convert", "mulai", "start", "kirim", "send",
            "enkripsi", "encrypt", "dekripsi", "decrypt", "upload", "unggah", "ekstrak", "extract",
            "kompres", "compress", "analisis", "analyze", "scan", "pindai", "terapkan", "apply",
            "publish", "terbitkan", "download", "unduh", "buat", "create", "cek", "check", "test", "uji"
        ).any { t == it || t.startsWith("$it ") }
    }
internal fun MainActivity.styleAsPrimary(b: TextView) {
        // Solid near-black / near-white — aksi utama saja. Sudut 14dp (bukan pil penuh)
        // memberi kesan alat profesional dan konsisten dengan panel editor.
        val fill = if (isDarkTheme) Color.rgb(245, 245, 247) else Color.rgb(17, 17, 20)
        val ink = if (isDarkTheme) Color.rgb(17, 17, 20) else Color.WHITE
        b.setTextColor(ink)
        b.background = rippleBg(fill, 14, fill)
        b.minHeight = dp(Ds.BUTTON_MIN)
        b.setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_MD), dp(Ds.SPACE_LG), dp(Ds.SPACE_MD))
        b.textSize = Ds.TEXT_BODY
        b.typeface = android.graphics.Typeface.create(b.typeface, android.graphics.Typeface.BOLD)
        b.letterSpacing = 0.015f
    }
internal fun MainActivity.styleAsSecondary(b: TextView) {
        // Tonal bergaris — pilihan & aksi pendukung (lebih tenang dari aksi utama).
        b.setTextColor(textMain)
        b.background = rippleBg(
            if (isDarkTheme) panel2 else Color.WHITE,
            14,
            if (isDarkTheme) line else Color.rgb(214, 214, 219)
        )
        b.minHeight = dp(Ds.BUTTON_MIN - 4)
        b.setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_SM + 2), dp(Ds.SPACE_LG), dp(Ds.SPACE_SM + 2))
        b.textSize = Ds.TEXT_BODY - 1f
        b.typeface = android.graphics.Typeface.create(b.typeface, android.graphics.Typeface.NORMAL)
        b.letterSpacing = 0.01f
    }
internal fun MainActivity.styleAsTertiary(b: TextView) {
        b.setTextColor(textMuted)
        b.background = rippleBg(Color.TRANSPARENT, Ds.RADIUS_PILL)
        b.minHeight = dp(Ds.BUTTON_MIN)
        b.letterSpacing = 0.01f
    }
internal fun MainActivity.styleAsDestructive(b: TextView) {
        val fill = if (isDarkTheme) Color.rgb(60, 24, 24) else Color.rgb(254, 226, 226)
        val ink = if (isDarkTheme) Color.rgb(255, 140, 140) else Color.rgb(153, 27, 27)
        b.setTextColor(ink)
        b.background = rippleBg(fill, Ds.RADIUS_PILL, if (isDarkTheme) Color.rgb(90, 40, 40) else Color.rgb(252, 165, 165))
        b.minHeight = dp(Ds.BUTTON_MIN)
        b.setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_MD), dp(Ds.SPACE_LG), dp(Ds.SPACE_MD))
        b.textSize = Ds.TEXT_BODY
    }
internal fun MainActivity.secondaryButton(text: String, onClick: () -> Unit): Button =
        button(text, onClick).also { styleAsSecondary(it) }

internal fun MainActivity.tertiaryButton(text: String, onClick: () -> Unit): Button =
        button(text, onClick).also { styleAsTertiary(it) }

internal fun MainActivity.stateCard(
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
internal fun MainActivity.addEmptyState(title: String = "Belum ada hasil", message: String = "Jalankan tool untuk melihat hasilnya") {
        content.addView(stateCard(Ds.State.EMPTY, title, message),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Ds.SPACE_MD); bottomMargin = dp(Ds.SPACE_MD) })
    }
internal fun MainActivity.saveCurrentPageSnapshot() {
        if (restoringSnapshot || content.childCount == 0) return

        val rootPage = currentPage == "home" || currentPage == "all" ||
                currentPage == "favorites" || currentPage == "settings"

        // Root pages can contain dozens/hundreds of Views. Moving the entire View tree
        // into the Back stack caused visible jank and retained a lot of memory. Keep only
        // the page identity for roots and rebuild them on demand when Back is pressed.
        // Replace consecutive same-name snapshot instead of stacking (anti kue-lapis)
        fun pushOrReplace(snap: PageSnapshot) {
            if (pageBackStack.isNotEmpty() && pageBackStack.last().name == snap.name) {
                pageBackStack.removeLast()
            }
            pageBackStack.addLast(snap)
            while (pageBackStack.size > 8) pageBackStack.removeFirst()
        }

        if (rootPage) {
            pushOrReplace(
                PageSnapshot(
                    name = currentPage,
                    children = mutableListOf(),
                    scrollY = scroll.scrollY,
                    searchText = search.text?.toString() ?: "",
                    searchVisible = if (searchOpen) View.VISIBLE else View.GONE,
                    lightweight = true
                )
            )
            return
        }

        // P2: halaman tool dengan banyak view → lightweight (rebuild via openTool),
        // hindari menahan ratusan View di back stack (RAM / jank).
        val childCount = content.childCount
        // Video Editor selalu lightweight: state proyek ada di VeProject, jadi halaman
        // dibangun ulang (top bar sendiri) alih-alih memulihkan view lama.
        if (childCount > 50 || currentPage == "Video Editor" || currentPage == "Video to Frames" || currentPage == CREATIVE_STUDIO_PAGE || currentPage == "Auto Trace" ||
            currentPage == ANIM_STUDIO_PAGE || currentPage == PRESENT_STUDIO_PAGE ||
            currentPage == "Design Studio" || currentPage == "Photo Editor" || currentPage == "Background Remover" || currentPage == "Audio Editor") {
            content.removeAllViews()
            pushOrReplace(
                PageSnapshot(
                    name = currentPage,
                    children = mutableListOf(),
                    scrollY = 0,
                    searchText = search.text?.toString() ?: "",
                    searchVisible = if (searchOpen) View.VISIBLE else View.GONE,
                    lightweight = true
                )
            )
            return
        }
        val children = ArrayList<View>(childCount)
        while (content.childCount > 0) {
            children.add(content.getChildAt(0))
            content.removeViewAt(0)
        }
        pushOrReplace(
            PageSnapshot(
                name = currentPage,
                children = children,
                scrollY = scroll.scrollY,
                searchText = search.text?.toString() ?: "",
                searchVisible = if (searchOpen) View.VISIBLE else View.GONE
            )
        )
    }
internal fun MainActivity.restoreSnapshot(snapshot: PageSnapshot) {
        restoringSnapshot = true
        try {
            // Dock aksi tool (tombol bawah) adalah view global — harus dibersihkan
            // setiap kali snapshot dipulihkan, supaya tombol tool sebelumnya
            // (mis. "Hapus duplikat", "Bandingkan File") tidak menempel di halaman kategori/home.
            clearToolActionDock()
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, dp(104))

            // Re-render root pages instead of restoring a huge retained View tree.
            // This keeps Back navigation smooth and prevents the app from accumulating
            // hundreds of detached card Views in memory.
            if (snapshot.lightweight) {
                when (snapshot.name) {
                    "home" -> showHome(homeFilter)
                    "all" -> showAllTools()
                    "favorites" -> showFavorites()
                    "settings" -> showSettings()
                    "Editor", "Console", "Preview" -> editor(editorFile, editorMode)
                    "Video Editor" -> videoEditorTool()
                    "Video to Frames" -> videoFramesTool()
                    CREATIVE_STUDIO_PAGE -> creativeStudioTool(restore = true)
                    ANIM_STUDIO_PAGE -> animationStudioTool()
                    PRESENT_STUDIO_PAGE -> presentationStudioTool()
                    "Auto Trace" -> autoTraceTool()
                    "Photo Editor" -> photoEditorTool()
                    "Background Remover" -> backgroundRemoverTool()
                    "Audio Editor" -> audioEditorTool()
                    else -> {
                        // Tool page disimpan lightweight: buka ulang lewat router jika ID dikenal
                        val toolId = lastOpenedToolId
                            ?: homeToolMap.entries.firstOrNull { it.value == snapshot.name }?.key
                        if (!toolId.isNullOrBlank()) openTool(toolId)
                        else showHome()
                    }
                }
                return
            }

            // Back from Preview/Live Preview must destroy WebViews before the detached
            // snapshot is restored. Otherwise the old renderer can remain alive while
            // the snapshot is attached again, causing WebView/Activity memory leaks.
            if (currentPage == "Preview" || editorLivePreviewWeb != null) {
                runCatching { destroyEditorWebViews() }
                runCatching { EditorLivePreview.detach() }
                runCatching { EditorLivePreview.stopBufferPolling() }
            }
            content.removeAllViews()
            snapshot.children.forEach { content.addView(it) }
            currentPage = snapshot.name
            if (snapshot.name == "Editor" && !editorLanding) {
                // Console/Preview temporarily shows the global toolbar so it has
                // its own Back/menu actions. When returning to the editor workspace,
                // those global actions must disappear again; otherwise the Console
                // toolbar remains visually stuck on top of the editor.
                topBarVisibility(false)
                subtitle.visibility = View.GONE
                homeMenu.visibility = View.GONE
                homeSearch.visibility = View.GONE
                homeProfile.visibility = View.GONE
                back.visibility = View.GONE
                action.visibility = View.GONE
                editorMore.visibility = View.GONE
            } else if (snapshot.name != "Editor" && topBar.visibility != View.VISIBLE) topBarVisibility(true)
            // Pastikan layar selalu kembali ke portrait saat berpindah halaman.
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            val root = snapshot.name == "home" || snapshot.name == "all" || snapshot.name == "favorites" || snapshot.name == "settings"
            title.text = when (snapshot.name) {
                "home" -> "GITLS"
                "all" -> "Semua Tools"
                "favorites" -> "Favorit"
                "settings" -> "Pengaturan"
                else -> snapshot.name
            }
            findViewById<View>(R.id.headerTitleBox)?.visibility = if (snapshot.name == "home") View.GONE else View.VISIBLE
            title.visibility = View.VISIBLE
            title.alpha = 1f
            if (snapshot.name != "home") title.textSize = 18f
            // Pastikan subtitle tool (deskripsi) tidak menempel di halaman kategori/list.
            if (snapshot.name == "home") {
                subtitle.visibility = View.VISIBLE
            } else {
                subtitle.text = ""
                subtitle.visibility = View.GONE
            }
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
            hideToolEditorShell()
            val restoredName = snapshot.name
            content.post { applyToolEditorShell(restoredName) }
            scroll.post { scroll.scrollTo(0, snapshot.scrollY) }
        } finally {
            restoringSnapshot = false
        }
    }
internal fun MainActivity.navigateBack() {
        if (drawerOpen) {
            closeDrawer()
            return
        }
        // Laci Workspace / laci Editor ditutup lebih dulu sebelum pindah halaman.
        if (OverlayBack.consume()) return
        // APK Foto Studio: Back dari studio = keluar aplikasi (tidak ada Beranda).
        if (BuildConfig.STUDIO_ONLY && currentPage == CREATIVE_STUDIO_PAGE) { finish(); return }
        // Simpan data form terbaru sebelum kembali (semua tools).
        runCatching { harvestToolSessionFromContent(); persistToolSessionFields() }
        if (currentPage == "Editor" && !editorLanding) {
            runCatching { flushEditorCurrentToBuffer(writeDisk = true) }
            val dirtyTabs = editorOpenTabs.filter { editorBuffers[it.key]?.dirty == true }
            if (dirtyTabs.isNotEmpty()) {
                val names = dirtyTabs.take(5).joinToString(", ") { it.label }
                val more = if (dirtyTabs.size > 5) " (+${dirtyTabs.size - 5} lagi)" else ""
                AlertDialog.Builder(this)
                    .setTitle("Keluar dari Editor?")
                    .setMessage("Ada perubahan yang belum disimpan:\n$names$more\n\nDraft tetap tersimpan di sesi ini. Keluar sekarang?")
                    .setPositiveButton("Keluar") { _, _ ->
                        editorExternalTarget = null
                        editorExternalMode = null
                        proceedNavigateBack()
                    }
                    .setNegativeButton("Tetap di Editor", null)
                    .show()
                return
            }
            editorExternalTarget = null
            editorExternalMode = null
        }
        proceedNavigateBack()
    }

/** Lanjutkan navigasi back setelah (opsional) konfirmasi dirty editor. */
internal fun MainActivity.proceedNavigateBack() {
        // Selalu bersihkan dock sebelum kembali — mencegah tombol tool menempel.
        clearToolActionDock()
        // Anti kue-lapis global: buang semua snapshot berurutan dengan nama halaman
        // yang sedang ditinggalkan (Editor, tool yang di-refresh, dll).
        val leaving = currentPage
        while (pageBackStack.isNotEmpty() && pageBackStack.last().name == leaving) {
            pageBackStack.removeLast()
        }
        // Rapikan juga duplikat berurutan di puncak stack (A,A,B → A,B)
        while (pageBackStack.size >= 2) {
            val top = pageBackStack.last().name
            val under = pageBackStack.elementAt(pageBackStack.size - 2).name
            if (top == under) pageBackStack.removeLast() else break
        }
        if (pageBackStack.isEmpty()) {
            if (currentPage != "home") {
                // Safety fallback for a page created before the stack was populated.
                showHome()
            } else {
                invokeSuperOnBackPressed()
            }
            return
        }
        val snapshot = pageBackStack.removeLast()
        restoreSnapshot(snapshot)
    }
internal fun MainActivity.handleBack() {
        // Layar login/guest berada di atas aplikasi; Back di sini keluar dari aplikasi,
        // bukan membuka halaman di belakangnya.
        if (loginScreen.visibility == View.VISIBLE) { moveTaskToBack(true); return }
        if (currentPage == "Konversi File" && convCategory != null) { convGoBackStage(); return }
        navigateBack()
    }
internal fun MainActivity.registerBackCallback() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                android.window.OnBackInvokedCallback { handleBack() }
            )
        }
    }
internal fun MainActivity.configureActionForPage(name: String) {
        when (name) {
            "Pengelola Keuangan" -> {
                action.text = "+"; action.textSize = 28f; action.setOnClickListener { showFinanceActions() }
                // Menu ⋮ per-tool diganti ikon bantuan (?) yang sama untuk semua tool.
                editorMore.visibility = View.VISIBLE; editorMore.text = "?"; editorMore.textSize = 20f
                editorMore.contentDescription = "Bantuan $name"
                editorMore.setOnClickListener { showToolHelpSheet(name) }
            }
            in rmAllPages -> {
                editorMore.visibility = View.GONE
                action.text = "?"; action.textSize = 20f
                action.contentDescription = "Bantuan Notifikasi"
                action.setOnClickListener { showToolHelpSheet(name) }
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
                    // Header tool ringkas & konsisten: kembali · judul · bantuan (?).
                    // Favorit/Terkait/Riwayat/Panduan/Info yang dulu ada di menu ⋮ kini di
                    // showToolHelpSheet() dan (Favorit) di kartu tool Beranda/Semua Tools.
                    editorMore.visibility = View.GONE
                    action.text = "?"
                    action.textSize = 20f
                    action.contentDescription = if (name == "all" || name == "favorites" || name == "settings") "Bantuan" else "Bantuan $name"
                    action.setOnClickListener { showToolHelpSheet(name) }
                }
            }
        }
    }
internal fun MainActivity.styleTopFabs() {
        val fill = if (isDarkTheme) panel else Color.WHITE
        val stroke = if (isDarkTheme) line else Color.rgb(232, 235, 239)
        val ink = if (isDarkTheme) textMain else Color.rgb(23, 32, 42)
        topBar.outlineProvider = null
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
internal fun MainActivity.applyGhostShadow(view: View) {
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
internal fun MainActivity.resetSearchSnap(show: Boolean) {
        searchSnapEnabled = show
        if (!show || (search.text.isNullOrEmpty() && !search.hasFocus())) {
            setSearchBoxOpen(false)
        } else {
            setSearchBoxOpen(true)
        }
    }
internal fun MainActivity.updateSearchClearButton() {
        val hasQuery = !search.text.isNullOrEmpty()
        val clearDrawable = if (hasQuery) {
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_clear_search)
        } else null
        search.setCompoundDrawablesWithIntrinsicBounds(null, null, clearDrawable, null)
        search.compoundDrawablePadding = dp(6)
        search.contentDescription = if (hasQuery) "Pencarian, hapus teks" else "Pencarian"
    }
internal fun MainActivity.setSearchBoxOpen(open: Boolean, focus: Boolean = false) {
        searchOpen = open
        searchFill.visibility = if (open) View.GONE else View.VISIBLE
        updateSearchClearButton()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
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
