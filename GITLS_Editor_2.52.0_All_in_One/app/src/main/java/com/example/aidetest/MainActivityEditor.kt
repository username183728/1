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
import java.security.MessageDigest
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


internal fun MainActivity.editor(file: File?, forcedMode: String? = null, sourceUriOverride: Uri? = null) {
        // Batalkan callback autosave lama sebelum berpindah file/mode.
        // Callback lama tidak boleh menyimpan editor baru setelah jeda 800ms.
        cancelEditorAutoSave()
        // Simpan buffer saat ini dulu sebelum pindah file/mode agar teks tidak hilang.
        flushEditorCurrentToBuffer(writeDisk = true)
        val wasInEditorWorkspace = currentPage == "Editor" && !editorLanding
        editorLanding = file == null && forcedMode == null && editorExternalTarget == null
        // Saat ganti HTML↔CSS↔JS di project yang sama, JANGAN push ke back stack.
        // Kalau di-push, tombol Back jadi "kue lapis" banyak layer editor bekas.
        val pushBack = !(wasInEditorWorkspace && !editorLanding)
        clearPage("Editor", pushBack = pushBack)
        if (sourceUriOverride != null) {
            editorSourceUri = sourceUriOverride
        } else if (file?.absolutePath != editorFile?.absolutePath) {
            editorSourceUri = null
        }
        editorFile = file
        // Every new unsaved document gets its own stable session key. This prevents
        // two simultaneous HTML/JSON drafts from sharing the old mode-only buffer.
        if (file == null && forcedMode != null && editorExternalTarget == null) {
            editorDraftSessionId = java.util.UUID.randomUUID().toString()
        } else if (file != null || editorLanding) {
            editorDraftSessionId = null
        }
        file?.let {
            recordRecentFile(it)
            syncEditorProjectFromFile(it)
            it.parentFile?.let { dir -> recoverEditorTempFiles(dir) }
        }
        recoverEditorTempFiles(editorDraftsDir())
        editorMode = forcedMode ?: detectEditorMode(file?.name)
        if (editorLanding) {
            editorCurrentBufferKey = null
            renderEditorHome()
        } else {
            renderEditorPage()
        }
    }

    /** Key buffer: absolute file path, atau unique draft session + mode untuk file baru. */
    internal fun MainActivity.editorBufferKey(file: File?, mode: String): String {
        val path = file?.absolutePath
        if (!path.isNullOrBlank()) return path
        val draft = editorDraftSessionId
        return if (!draft.isNullOrBlank()) "draft:$draft:$mode" else "draft:${java.util.UUID.randomUUID()}:$mode"
    }

    /** Ambil atau buat buffer untuk key. */
    internal fun MainActivity.getOrCreateEditorBuffer(key: String, mode: String, file: File?): MainActivity.EditorBuffer {
        return editorBuffers.getOrPut(key) {
            val fromDisk = loadPersistedEditorBuffer(key)?.takeUnless { d ->
                // Draft bersih (tidak dirty) yang metadata disknya sudah tidak cocok = basi
                // (file dihapus/dibuat ulang atau diubah dari luar). Pakai isi disk yang sebenarnya.
                file != null && !d.dirty && (!file.exists() ||
                    (d.lastDiskModified >= 0L && (file.lastModified() != d.lastDiskModified || file.length() != d.lastDiskSize)))
            }
            if (fromDisk != null) fromDisk
            else {
                val diskText = file?.let { runCatching { GitlsMaxEngine.readUtf8Limited(it) }.getOrNull() }.orEmpty()
                MainActivity.EditorBuffer(
                    text = diskText, filePath = file?.absolutePath, mode = mode, dirty = false,
                    lastDiskModified = file?.takeIf { it.exists() }?.lastModified() ?: -1L,
                    lastDiskSize = file?.takeIf { it.exists() }?.length() ?: -1L
                )
            }
        }
    }

    /** Tulis isi EditText aktif ke buffer (dan opsional ke file/disk). */
    internal fun MainActivity.flushEditorCurrentToBuffer(writeDisk: Boolean = false) {
        val box = editorBox ?: return
        val key = editorCurrentBufferKey ?: editorBufferKey(editorFile, editorMode)
        val buf = editorBuffers.getOrPut(key) {
            MainActivity.EditorBuffer(filePath = editorFile?.absolutePath, mode = editorMode)
        }
        val text = box.text?.toString().orEmpty()
        val selStart = box.selectionStart.coerceAtLeast(0)
        val selEnd = box.selectionEnd.coerceAtLeast(0)
        // Always sync box → buffer (prevents silent loss when switching files)
        if (buf.text != text) {
            buf.text = text
            buf.dirty = true
        } else {
            buf.text = text
        }
        buf.selectionStart = selStart
        buf.selectionEnd = selEnd
        buf.mode = editorMode
        buf.filePath = editorFile?.absolutePath
        editorCurrentBufferKey = key
        if (writeDisk) {
            // Keep the buffer dirty until every relevant persistence target succeeds.
            // This prevents the UI from claiming "saved" when only the internal copy
            // was updated but the user's original SAF document failed to write.
            var diskOk = true
            editorFile?.let { f ->
                runCatching {
                    if (!f.exists()) error("File sudah dihapus dari storage; gunakan Simpan sebagai untuk membuat file baru")
                    if (buf.lastDiskModified >= 0L && (f.lastModified() != buf.lastDiskModified || f.length() != buf.lastDiskSize)) {
                        error("File berubah di luar GITLS; simpan dibatalkan agar perubahan eksternal tidak tertimpa")
                    }
                    writeTextAtomically(f, text)
                    buf.lastDiskModified = f.lastModified()
                    buf.lastDiskSize = f.length()
                }.onFailure {
                    diskOk = false
                    editorErrorToast("Autosave gagal: ${it.message}")
                }
            }
            editorSourceUri?.let { src ->
                // Simpan draft lebih dulu: "wt" mengosongkan dokumen asli sebelum menulis ulang.
                buf.dirty = true
                persistEditorBuffer(key, buf)
                runCatching {
                    writeSafDocumentVerified(src, text)
                }.onFailure {
                    diskOk = false
                    editorErrorToast("Autosave file asli gagal: ${it.message}")
                }
            }
            buf.dirty = !diskOk
            persistEditorBuffer(key, buf)
        }
    }

    /** Batalkan autosave yang masih menunggu dan invalidate owner callback sebelumnya. */
    internal fun MainActivity.cancelEditorAutoSave() {
        editorAutoSaveGeneration++
        editorAutoSaveRunnable?.let { editorAutoSaveHandler.removeCallbacks(it) }
        editorAutoSaveRunnable = null
    }

    /** Jadwalkan auto-save debounce ~800ms setelah user berhenti mengetik.
     * Callback membawa identitas buffer/file yang menjadwalkannya sehingga tidak
     * mungkin menulis file lain setelah pengguna berpindah tab.
     */
    internal fun MainActivity.scheduleEditorAutoSave() {
        editorAutoSaveRunnable?.let { editorAutoSaveHandler.removeCallbacks(it) }
        val generation = ++editorAutoSaveGeneration
        val key = editorCurrentBufferKey ?: editorBufferKey(editorFile, editorMode)
        val filePath = editorFile?.absolutePath
        val mode = editorMode
        val r = Runnable {
            if (generation != editorAutoSaveGeneration) return@Runnable
            if (editorCurrentBufferKey != key) return@Runnable
            if (editorFile?.absolutePath != filePath) return@Runnable
            if (editorMode != mode) return@Runnable
            editorAutoSaveRunnable = null
            flushEditorCurrentToBuffer(writeDisk = true)
        }
        editorAutoSaveRunnable = r
        editorAutoSaveHandler.postDelayed(r, 800L)
    }

    private fun MainActivity.editorDraftsDir(): File {
        val dir = File(filesDir, "editor_drafts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    internal fun MainActivity.draftFileForKey(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(StandardCharsets.UTF_8))
        val hash = digest.joinToString("") { "%02x".format(it) }
        return File(editorDraftsDir(), "$hash.draft")
    }

    private var editorErrToastAt = 0L
    private var editorErrToastMsg = ""

    /** Toast error editor yang tidak berulang-ulang: pesan sama ditahan 15 detik (mis. autosave gagal terus). */
    internal fun MainActivity.editorErrorToast(msg: String) {
        val now = SystemClock.elapsedRealtime()
        if (msg == editorErrToastMsg && now - editorErrToastAt < 15_000L) return
        editorErrToastMsg = msg
        editorErrToastAt = now
        toast(msg)
    }

    /**
     * Timpa dokumen SAF lalu verifikasi ukurannya. Mode "wt" mengosongkan dokumen lebih dulu,
     * jadi tulis yang terputus harus terdeteksi (salinan aman tetap ada di draft editor).
     */
    internal fun MainActivity.writeSafDocumentVerified(src: Uri, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        contentResolver.openOutputStream(src, "wt")?.use {
            it.write(bytes)
            it.flush()
        } ?: error("Tidak bisa menulis file asli")
        val size = runCatching {
            contentResolver.openInputStream(src)?.use { ins ->
                var n = 0L
                val chunk = ByteArray(8192)
                while (true) {
                    val r = ins.read(chunk)
                    if (r < 0) break
                    n += r
                }
                n
            }
        }.getOrNull()
        if (size != null && size != bytes.size.toLong()) {
            error("Verifikasi gagal: ukuran file asli $size dari ${bytes.size} byte. Salinan aman ada di draft editor")
        }
    }

    /**
     * Bersihkan sisa penulisan yang terputus (proses mati saat tulis) di sebuah folder.
     * Cadangan .gitls-bak dipulihkan bila file aslinya hilang; temp lama (>10 menit) dihapus.
     */
    internal fun recoverEditorTempFiles(dir: File) {
        runCatching {
            val now = System.currentTimeMillis()
            val files = dir.listFiles() ?: return@runCatching
            for (f in files) {
                if (!f.isFile || !f.name.startsWith(".")) continue
                val name = f.name
                if (name.endsWith(".gitls-bak")) {
                    val original = File(dir, name.removePrefix(".").removeSuffix(".gitls-bak"))
                    if (!original.exists()) {
                        f.renameTo(original)
                    } else if (now - f.lastModified() > 600_000L) {
                        f.delete()
                    }
                } else if ((name.contains(".tmp-") || name.contains(".gitls-tmp-")) && now - f.lastModified() > 600_000L) {
                    f.delete()
                }
            }
        }
    }

    /**
     * Tulis teks ke file secara atomic (temp file + fsync + rename) supaya crash/kill proses
     * di tengah penulisan tidak memotong atau mengosongkan file asli pengguna.
     */
    internal fun writeTextAtomically(file: File, text: String) {
        atomicWriteUtf8(file, text)
    }

    private fun atomicWriteUtf8(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp-${java.util.UUID.randomUUID()}")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(StandardCharsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                // Fallback untuk filesystem yang tidak menimpa saat rename.
                if (file.exists() && !file.delete()) throw IOException("Tidak bisa mengganti file")
                if (!tmp.renameTo(file)) throw IOException("Tidak bisa memasang file atomic")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    internal fun MainActivity.persistEditorBuffer(key: String, buf: MainActivity.EditorBuffer) {
        runCatching {
            val meta = JSONObject()
                .put("key", key)
                .put("mode", buf.mode)
                .put("filePath", buf.filePath ?: JSONObject.NULL)
                .put("selectionStart", buf.selectionStart)
                .put("selectionEnd", buf.selectionEnd)
                .put("dirty", buf.dirty)
                .put("lastDiskModified", buf.lastDiskModified)
                .put("lastDiskSize", buf.lastDiskSize)
                .put("text", buf.text)
            atomicWriteUtf8(draftFileForKey(key), meta.toString())
        }.onFailure { editorErrorToast("Draft editor tidak bisa disimpan: ${it.message}") }
    }

    internal fun MainActivity.loadPersistedEditorBuffer(key: String): MainActivity.EditorBuffer? {
        return runCatching {
            val f = draftFileForKey(key)
            if (!f.exists()) return null
            val obj = JSONObject(f.readText())
            MainActivity.EditorBuffer(
                text = obj.optString("text", ""),
                filePath = obj.optString("filePath", null)?.takeIf { it.isNotBlank() && it != "null" },
                mode = obj.optString("mode", "text"),
                selectionStart = obj.optInt("selectionStart", 0),
                selectionEnd = obj.optInt("selectionEnd", 0),
                dirty = obj.optBoolean("dirty", false),
                lastDiskModified = obj.optLong("lastDiskModified", -1L),
                lastDiskSize = obj.optLong("lastDiskSize", -1L)
            )
        }.getOrNull()
    }

    internal fun MainActivity.persistAllEditorBuffers() {
        editorBuffers.forEach { (key, buf) -> persistEditorBuffer(key, buf) }
    }

    /** Muat semua draft dari disk saat app mulai (dipanggil dari renderEditorPage jika perlu). */
    internal fun MainActivity.ensureEditorBuffersHydrated() {
        if (editorBuffers.isNotEmpty()) return
        runCatching {
            editorDraftsDir().listFiles()?.filter { it.name.endsWith(".draft") }?.forEach { f ->
                runCatching {
                    val obj = JSONObject(f.readText())
                    val key = obj.optString("key", f.nameWithoutExtension)
                    if (key.isNotBlank() && !editorBuffers.containsKey(key)) {
                        editorBuffers[key] = MainActivity.EditorBuffer(
                            text = obj.optString("text", ""),
                            filePath = obj.optString("filePath", null)?.takeIf { it.isNotBlank() && it != "null" },
                            mode = obj.optString("mode", "text"),
                            selectionStart = obj.optInt("selectionStart", 0),
                            selectionEnd = obj.optInt("selectionEnd", 0),
                            dirty = obj.optBoolean("dirty", false),
                            lastDiskModified = obj.optLong("lastDiskModified", -1L),
                            lastDiskSize = obj.optLong("lastDiskSize", -1L)
                        )
                    }
                }
            }
        }
    }
internal fun MainActivity.detectEditorMode(name: String?): String {
        val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.getDefault()) ?: ""
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
internal fun MainActivity.editorModeName(mode: String): String = when (mode) {
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

internal fun MainActivity.editorDefaultName(mode: String): String = when (mode) {
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

internal fun MainActivity.editorModeDescription(mode: String): String = when (mode) {
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

internal fun MainActivity.renderEditorHome() {
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
            text = "HTML, CSS, dan JavaScript dalam satu folder project. Format lain ada di (+)."
            textSize = 12f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        })
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(136)).apply { bottomMargin = dp(12) })
        animateEditorItem(hero, 0L, 10f)

        // Tombol utama: buat project web lengkap (HTML+CSS+JS saling terhubung)
        val projectBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = bg(Color.rgb(14, 165, 233), 16, Color.rgb(2, 132, 199))
            isClickable = true
            setOnClickListener {
                animateEditorPress(this)
                it.postDelayed({ createEditorWebProject() }, 70L)
            }
        }
        projectBtn.addView(MdiIconView(this).apply {
            setIconName("folder-plus-outline")
            setIconSize(24f)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(40)).apply { rightMargin = dp(8) }
        })
        val projTextBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        projTextBox.addView(TextView(this).apply {
            text = "Project Web (HTML + CSS + JS)"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        projTextBox.addView(TextView(this).apply {
            text = "Buat folder berisi index.html, style.css, script.js yang saling terhubung."
            textSize = 11f
            setTextColor(Color.argb(220, 255, 255, 255))
            setPadding(0, dp(2), 0, 0)
        })
        projectBtn.addView(projTextBox)
        projectBtn.addView(TextView(this).apply {
            text = "›"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(42))
        })
        content.addView(projectBtn, LinearLayout.LayoutParams(-1, dp(70)).apply { bottomMargin = dp(10) })
        animateEditorItem(projectBtn, 40L, 12f)

        // Buka project terakhir jika ada
        val lastProj = prefs.getString("last_editor_project", null)?.let { File(it) }
        if (lastProj != null && lastProj.isDirectory) {
            val reopen = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(10), dp(10))
                background = bg(Color.WHITE, 16, Color.rgb(226, 231, 235))
                isClickable = true
                setOnClickListener {
                    animateEditorPress(this)
                    it.postDelayed({ openEditorWebProject(lastProj) }, 70L)
                }
            }
            reopen.addView(MdiIconView(this).apply {
                setIconName("folder-open-outline")
                setIconSize(22f)
                setTextColor(textMain)
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(40)).apply { rightMargin = dp(8) }
            })
            val reopenText = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            reopenText.addView(TextView(this).apply {
                text = "Lanjutkan: ${lastProj.name}"
                textSize = 14f
                setTextColor(textMain)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            reopenText.addView(TextView(this).apply {
                text = "Buka project web terakhir"
                textSize = 11f
                setTextColor(textMuted)
            })
            reopen.addView(reopenText)
            content.addView(reopen, LinearLayout.LayoutParams(-1, dp(62)).apply { bottomMargin = dp(10) })
            animateEditorItem(reopen, 60L, 12f)
        }

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
                    it.postDelayed({ editorExternalTarget = null; editorExternalMode = null; editor(null, mode) }, 70L)
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
internal fun MainActivity.topBarVisibility(visible: Boolean) {
        topBar.visibility = if (visible) View.VISIBLE else View.GONE
        applyEdgeInsets(imeBottomInset)
    }
internal fun MainActivity.applyEdgeInsets(imeBottom: Int) {
        val topBarView = runCatching { topBar }.getOrNull() ?: return
        val scrollView = runCatching { scroll }.getOrNull() ?: return
        val barH = dp(60) + sysTopInset
        val tp = topBarView.layoutParams as LinearLayout.LayoutParams
        tp.height = barH
        tp.bottomMargin = -barH
        topBarView.layoutParams = tp
        topBarView.setPadding(topBarView.paddingLeft, sysTopInset, topBarView.paddingRight, 0)
        topBarView.elevation = dp(8).toFloat()
        topBarView.translationZ = dp(2).toFloat()

        val topSpace = if (topBarView.visibility == View.VISIBLE) barH else sysTopInset
        val bottomSpace = if (imeBottom > 0) 0 else sysBottomInset
        scrollView.clipToPadding = false
        scrollView.setPadding(0, topSpace, 0, bottomSpace)

        // Keyboard: sebelumnya ditangani adjustResize; di mode full layar diganti padding manual.
        mainContainer.setPadding(0, 0, 0, imeBottom)

        val nav = bottomNav.layoutParams as LinearLayout.LayoutParams
        nav.bottomMargin = dp(10) + sysBottomInset
        bottomNav.layoutParams = nav

        val eb = editorBottomBar.layoutParams
        // Bit chat owns a two-row composer when a file is attached. Do not force the
        // global editor toolbar back to 56dp here: doing so clips the attachment card
        // and lets the IME cover the text field. Other pages keep the legacy 56dp bar.
        if (currentPage == "Bit Assistant") {
            eb.height = ViewGroup.LayoutParams.WRAP_CONTENT
            editorBottomBar.layoutParams = eb
            editorBottomBar.setPadding(editorBottomBar.paddingLeft, dp(6), editorBottomBar.paddingRight, bottomSpace)
        } else {
            eb.height = dp(56) + bottomSpace
            editorBottomBar.layoutParams = eb
            editorBottomBar.setPadding(editorBottomBar.paddingLeft, 0, editorBottomBar.paddingRight, bottomSpace)
        }

        loginScreen.setPadding(dp(28), dp(24) + sysTopInset, dp(28), dp(36) + sysBottomInset)
        // Toolbar Tool Editor Shell mengikuti navigation bar / keyboard.
        syncToolEditorBarInset(bottomSpace)
    }
internal fun MainActivity.showEditorModePicker() {
    val activity = this
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
                addView(MdiIconView(activity).apply {
                    setIconName(iconName)
                    setIconSize(24f)
                    setTextColor(textMain)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(48)).apply { rightMargin = dp(8) }
                })
                addView(TextView(activity).apply {
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
        panel.addView(sheetRow("language-html5", "Project Web (HTML+CSS+JS)") {
            menuDialog?.dismiss()
            createEditorWebProject()
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
internal fun MainActivity.showEditorCreateFilePicker() {
        val labels = arrayOf("HTML (.html)", "CSS (.css)", "JavaScript (.js)", "JSON (.json)", "CSV (.csv)", "Base64 (.txt)", "Teks (.txt)", "Konfigurasi (.ini)", "XML (.xml)", "Utilitas Teks")
        val keys = arrayOf("html", "css", "js", "json", "csv", "base64", "text", "config", "xml", "utility")
        AlertDialog.Builder(this)
            .setTitle("Buat file")
            .setItems(labels) { _, which ->
                editorExternalTarget = null
                editorExternalMode = null
                editorFile = null
                editorSourceUri = null
                editor(null, keys[which])
            }
            .setNegativeButton("Batal", null)
            .show()
    }
internal fun MainActivity.createEditorFolder() {
        val name = edit("Nama folder")
        themedDialog()
            .setTitle("Buat folder")
            .setView(name)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Buat") { _, _ ->
                val folder = safeChildFile(filesDir, name.text.toString())
                if (folder == null) toast("Nama folder tidak valid")
                else if (folder.exists() || !folder.mkdirs()) toast("Folder gagal dibuat")
                else {
                    prefs.edit().putString("last_workspace", folder.absolutePath).apply()
                    toast("Folder dibuat: ${folder.name}")
                }
            }.show()
    }

    /**
     * Buat project web lengkap: satu folder berisi index.html, style.css, script.js
     * yang sudah saling terhubung (link + script). Langsung buka di editor.
     */
    
internal fun MainActivity.createEditorWebProjectWithName(forcedName: String) {
    val safe = safeFileName(forcedName).ifBlank { "web_project" }
    val root = File(filesDir, "web_projects").apply { mkdirs() }
    val dir = File(root, safe)
    if (dir.exists()) { toast("Nama masih bentrok: $safe"); return }
    runCatching {
        if (!dir.mkdirs()) error("Gagal membuat folder")
        File(dir, "index.html").writeText(
            """<!DOCTYPE html><html lang="id"><head><meta charset="UTF-8"/><meta name="viewport" content="width=device-width,initial-scale=1.0"/><title>$safe</title><link rel="stylesheet" href="style.css"/></head><body><main class="container"><h1>Halo, $safe!</h1><p>Edit <code>index.html</code>, <code>style.css</code>, dan <code>script.js</code>.</p><button id="btn" type="button">Klik saya</button><p id="msg"></p></main><script src="script.js"></script></body></html>"""
        )
        File(dir, "style.css").writeText(
            """*{box-sizing:border-box}body{margin:0;font-family:system-ui,sans-serif;background:linear-gradient(160deg,#0f172a,#1e293b);color:#e2e8f0;min-height:100vh;display:flex;align-items:center;justify-content:center}.container{max-width:480px;padding:28px;background:rgba(30,41,59,.9);border-radius:16px;border:1px solid #334155;text-align:center}h1{color:#38bdf8}#btn{margin-top:16px;padding:12px 22px;border:0;border-radius:10px;background:#38bdf8;color:#0f172a;font-weight:700}#msg{margin-top:14px;color:#4ade80;min-height:1.4em}"""
        )
        File(dir, "script.js").writeText(
            """document.addEventListener('DOMContentLoaded',()=>{const b=document.getElementById('btn'),m=document.getElementById('msg');let c=0;if(b)b.addEventListener('click',()=>{c++;if(m)m.textContent='Klik ke-'+c;console.log('Klik ke-'+c)});console.log('Project "$safe" siap.')});"""
        )
        File(dir, "workspace.json").writeText(
            org.json.JSONObject().put("name", safe).put("type", "web").put("createdAt", System.currentTimeMillis()).put("version", 1).toString(2)
        )
        openEditorWebProject(dir)
        toast("Salinan project dibuat: $safe")
    }.onFailure { toast("Gagal: ${it.message}") }
}

internal fun MainActivity.createEditorWebProject() {
        val name = edit("Nama project").apply {
            hint = "contoh: portofolio, landing, game"
            setText("")
        }
        themedDialog()
            .setTitle("Project Web (HTML + CSS + JS)")
            .setMessage("Folder baru berisi index.html, style.css, dan script.js yang saling terhubung. Ganti tab HTML/CSS/JS untuk edit file pasangan di folder yang sama.")
            .setView(name)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Buat & Buka") { _, _ ->
                val raw = name.text?.toString()?.trim().orEmpty()
                if (raw.isBlank()) { toast("Masukkan nama project"); return@setPositiveButton }
                val safe = safeFileName(raw).ifBlank { "web_project" }
                val root = File(filesDir, "web_projects").apply { mkdirs() }
                val dir = File(root, safe)
                if (dir.exists()) {
                    // P3: Buka existing ATAU buat salinan unik (tidak overwrite)
                    themedDialog()
                        .setTitle("Project sudah ada")
                        .setMessage("Folder \"$safe\" sudah ada.\n\n• Buka = pakai project yang ada\n• Buat salinan = folder baru tanpa menimpa")
                        .setPositiveButton("Buka") { _, _ -> openEditorWebProject(dir) }
                        .setNeutralButton("Buat salinan") { _, _ ->
                            var n = 2
                            var copyName = "${safe}_$n"
                            while (File(root, copyName).exists() && n < 100) {
                                n++
                                copyName = "${safe}_$n"
                            }
                            if (File(root, copyName).exists()) {
                                toast("Terlalu banyak salinan untuk nama ini")
                            } else {
                                createEditorWebProjectWithName(copyName)
                            }
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                    return@setPositiveButton
                }
                runCatching {
                    if (!dir.mkdirs()) error("Gagal membuat folder")
                    val html = """<!DOCTYPE html>
<html lang="id">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>$safe</title>
  <link rel="stylesheet" href="style.css" />
</head>
<body>
  <main class="container">
    <h1>Halo, $safe!</h1>
    <p>Edit <code>index.html</code>, <code>style.css</code>, dan <code>script.js</code> di folder project yang sama. Ketiganya saling terhubung.</p>
    <button id="btn" type="button">Klik saya</button>
    <p id="msg"></p>
  </main>
  <script src="script.js"></script>
</body>
</html>
"""
                    val css = """/* style.css — stylesheet project $safe */
* { box-sizing: border-box; }
body {
  margin: 0;
  font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
  background: linear-gradient(160deg, #0f172a, #1e293b);
  color: #e2e8f0;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
.container {
  max-width: 480px;
  padding: 28px;
  background: rgba(30, 41, 59, 0.9);
  border-radius: 16px;
  border: 1px solid #334155;
  box-shadow: 0 12px 40px rgba(0,0,0,.35);
  text-align: center;
}
h1 { margin: 0 0 12px; font-size: 1.6rem; color: #38bdf8; }
p { line-height: 1.5; color: #94a3b8; }
code {
  background: #0f172a;
  padding: 2px 6px;
  border-radius: 4px;
  color: #fbbf24;
  font-size: 0.9em;
}
#btn {
  margin-top: 16px;
  padding: 12px 22px;
  border: none;
  border-radius: 10px;
  background: #38bdf8;
  color: #0f172a;
  font-weight: 700;
  font-size: 1rem;
  cursor: pointer;
}
#btn:active { transform: scale(0.97); }
#msg { margin-top: 14px; color: #4ade80; font-weight: 600; min-height: 1.4em; }
"""
                    val js = """// script.js — logic project $safe
document.addEventListener('DOMContentLoaded', function () {
  var btn = document.getElementById('btn');
  var msg = document.getElementById('msg');
  var count = 0;
  if (btn) {
    btn.addEventListener('click', function () {
      count += 1;
      if (msg) msg.textContent = 'Tombol diklik ' + count + 'x — JS aktif!';
      console.log('Klik ke-' + count);
    });
  }
  console.log('Project "$safe" siap.');
});
"""
                    File(dir, "index.html").writeText(html)
                    File(dir, "style.css").writeText(css)
                    File(dir, "script.js").writeText(js)
                    File(dir, "workspace.json").writeText(
                        JSONObject()
                            .put("name", raw)
                            .put("type", "web")
                            .put("createdAt", System.currentTimeMillis())
                            .put("version", 1)
                            .toString(2)
                    )
                    openEditorWebProject(dir)
                    toast("Project dibuat: $safe")
                }.onFailure { toast("Gagal: ${it.message}") }
            }.show()
    }

    /** Buka project web: set folder aktif, load buffer ketiga file, buka index.html. */
    internal fun MainActivity.openEditorWebProject(dir: File) {
        if (!dir.isDirectory) { toast("Folder project tidak valid"); return }
        editorProjectDir = dir
        prefs.edit()
            .putString("last_workspace", dir.absolutePath)
            .putString("last_editor_project", dir.absolutePath)
            .apply()
        // Preload buffer untuk html/css/js agar tab switch instan.
        listOf(
            "html" to "index.html",
            "css" to "style.css",
            "js" to "script.js"
        ).forEach { (mode, fileName) ->
            val f = File(dir, fileName)
            if (f.isFile) {
                val key = editorBufferKey(f, mode)
                val text = runCatching { GitlsMaxEngine.readUtf8Limited(f) }.getOrDefault("")
                editorBuffers[key] = MainActivity.EditorBuffer(
                    text = text,
                    filePath = f.absolutePath,
                    mode = mode,
                    dirty = false,
                    lastDiskModified = f.lastModified(),
                    lastDiskSize = f.length()
                )
                persistEditorBuffer(key, editorBuffers[key]!!)
            }
        }
        val htmlFile = File(dir, "index.html").takeIf { it.isFile }
            ?: dir.listFiles()?.firstOrNull { it.extension.equals("html", true) || it.extension.equals("htm", true) }
        if (htmlFile != null) editor(htmlFile, "html")
        else editor(null, "html")
    }

    /** Cari file pasangan di project folder berdasarkan mode (html/css/js). */
    internal fun MainActivity.resolveProjectFile(mode: String, projectDir: File? = editorProjectDir): File? {
        val dir = projectDir ?: return null
        if (!dir.isDirectory) return null
        val preferred = when (mode) {
            "html" -> listOf("index.html", "index.htm", "main.html")
            "css" -> listOf("style.css", "styles.css", "main.css")
            "js" -> listOf("script.js", "main.js", "app.js", "index.js")
            else -> emptyList()
        }
        preferred.forEach { name ->
            val f = File(dir, name)
            if (f.isFile) return f
        }
        val ext = when (mode) {
            "html" -> listOf("html", "htm")
            "css" -> listOf("css")
            "js" -> listOf("js", "mjs")
            else -> emptyList()
        }
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.getDefault()) in ext }
            ?.sortedBy { it.name.lowercase(Locale.getDefault()) }
            ?.firstOrNull()
    }

    /** Pastikan editorProjectDir mengikuti parent file yang sedang dibuka (jika folder web). */
    internal fun MainActivity.syncEditorProjectFromFile(file: File?) {
        if (file == null) return
        val parent = file.parentFile ?: return
        val hasHtml = parent.listFiles()?.any {
            it.isFile && (it.extension.equals("html", true) || it.extension.equals("htm", true))
        } == true
        val hasCssOrJs = parent.listFiles()?.any {
            it.isFile && (it.extension.equals("css", true) || it.extension.equals("js", true))
        } == true
        if (hasHtml && hasCssOrJs) {
            editorProjectDir = parent
            prefs.edit().putString("last_editor_project", parent.absolutePath).apply()
        }
    }

    /** Flush semua buffer file di project ke disk (untuk preview yang akurat). */
    internal fun MainActivity.flushProjectBuffersToDisk() {
        flushEditorCurrentToBuffer(writeDisk = true)
        val dir = editorProjectDir ?: return
        dir.listFiles()?.filter { it.isFile }?.forEach { f ->
            val mode = detectEditorMode(f.name)
            val key = editorBufferKey(f, mode)
            val buf = editorBuffers[key] ?: return@forEach
            if (buf.dirty) {
                runCatching {
                    if (buf.lastDiskModified >= 0L && (f.lastModified() != buf.lastDiskModified || f.length() != buf.lastDiskSize)) {
                        error("${f.name} berubah di luar GITLS; tidak ditimpa")
                    }
                    writeTextAtomically(f, buf.text)
                    buf.lastDiskModified = f.lastModified()
                    buf.lastDiskSize = f.length()
                    buf.dirty = false
                    persistEditorBuffer(key, buf)
                }.onFailure { toast("Gagal menyimpan ${f.name}: ${it.message}") }
            }
        }
    }

    /**
     * Bangun HTML preview yang menggabungkan CSS + JS dari project folder / buffer,
     * supaya tiga file benar-benar "terhubung" saat Preview.
     */
    internal fun MainActivity.buildLinkedWebPreviewHtml(): String {
        // Preview reads current in-memory buffers; never force a disk write just to preview.
        // This avoids UI stalls and prevents clean buffers from overwriting external edits.
        flushEditorCurrentToBuffer(writeDisk = false)
        val dir = editorProjectDir
        val htmlFile = when {
            editorMode == "html" && editorFile != null -> editorFile
            else -> resolveProjectFile("html", dir) ?: editorFile
        }
        var html = when {
            editorMode == "html" && editorBox != null -> editorBox!!.text.toString()
            htmlFile != null -> {
                val key = editorBufferKey(htmlFile, "html")
                editorBuffers[key]?.text ?: runCatching { htmlFile.readText() }.getOrDefault("")
            }
            else -> editorBox?.text?.toString().orEmpty()
        }
        if (html.isBlank()) {
            html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><title>Preview</title></head><body><p>Belum ada konten HTML.</p></body></html>"
        }

        val cssFile = resolveProjectFile("css", dir)
        val jsFile = resolveProjectFile("js", dir)
        val cssText = when {
            editorMode == "css" && editorBox != null -> editorBox!!.text.toString()
            cssFile != null -> {
                val key = editorBufferKey(cssFile, "css")
                editorBuffers[key]?.text ?: runCatching { cssFile.readText() }.getOrDefault("")
            }
            else -> ""
        }
        val jsText = when {
            editorMode == "js" && editorBox != null -> editorBox!!.text.toString()
            jsFile != null -> {
                val key = editorBufferKey(jsFile, "js")
                editorBuffers[key]?.text ?: runCatching { jsFile.readText() }.getOrDefault("")
            }
            else -> ""
        }

        // Selalu injeksi CSS/JS versi terbaru dari buffer agar preview sinkron dengan editor.
        if (cssText.isNotBlank()) {
            val styleTag = "<style id=\"gitls-preview-css\">\n$cssText\n</style>"
            html = if (html.contains("</head>", ignoreCase = true)) {
                html.replace(Regex("</head>", RegexOption.IGNORE_CASE)) { "$styleTag\n</head>" }
            } else {
                "<head>$styleTag</head>\n$html"
            }
        }
        if (jsText.isNotBlank()) {
            val scriptTag = "<script id=\"gitls-preview-js\">\n$jsText\n</script>"
            html = if (html.contains("</body>", ignoreCase = true)) {
                html.replace(Regex("</body>", RegexOption.IGNORE_CASE)) { "$scriptTag\n</body>" }
            } else {
                "$html\n$scriptTag"
            }
        }
        return html
    }
internal fun MainActivity.showEditorMoreMenu() {
        showEditorModePicker()
    }
internal fun MainActivity.renderEditorPage() {
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
        // Kartu nama file dibuat ringkas (wrap content) supaya tidak memenuhi lebar layar.
        val fileCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(2), 0)
            background = bg(Color.rgb(246, 246, 247), 14, Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(-2, dp(34))
        }
        fileCard.addView(MdiIconView(this).apply {
            setIconName(if (editorMode == "html") "language-html5" else if (editorMode == "css") "language-css3" else if (editorMode == "js") "language-javascript" else "file-document-outline")
            setIconSize(18f)
            setTextColor(textMain)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(32)).apply { rightMargin = dp(4) }
        })
        editorNameLabel = TextView(this).apply {
            val baseName = editorFile?.name ?: if (editorExternalMode != null) editorDefaultName(editorMode) else "Tanpa judul"
            val dirtyNow = editorBuffers[editorBufferKey(editorFile, editorMode)]?.dirty == true
            text = if (dirtyNow) "• $baseName" else baseName
            textSize = 13f
            setTextColor(if (dirtyNow) Color.rgb(230, 126, 34) else textMain)
            maxLines = 1
            maxWidth = dp(160)
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(-2, -2)
            contentDescription = if (dirtyNow) "$baseName, belum disimpan" else baseName
        }
        fileCard.addView(editorNameLabel)
        fileCard.addView(MdiIconView(this).apply {
            setIconName("pencil-outline")
            setIconSize(16f)
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(32))
            contentDescription = "Ganti nama file"
            setOnClickListener { renameEditorFile() }
        })
        header.addView(fileCard)
        // Ruang kosong mendorong tombol ⋯ dan + ke kanan.
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        // ⋯ = Terminal. Error editor / preview muncul di panel terminal.
        // Titik merah kecil menandakan ada error baru saat terminal tertutup.
        val terminalBtn = MdiIconView(this).apply {
            setIconName("console")
            setIconSize(19f)
            gravity = Gravity.CENTER
            setTextColor(textMain)
            background = bg(if (isDarkTheme) panel2 else Color.rgb(242,244,246), 14, Color.TRANSPARENT)
            contentDescription = "Terminal"
            setOnClickListener { toggleEditorTerminal() }
        }
        val errorDot = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.rgb(229, 57, 53))
            }
            visibility = if (editorTerminalUnseenErrors && !editorTerminalOpen) View.VISIBLE else View.GONE
        }
        editorTerminalBadge = errorDot
        val terminalWrap = FrameLayout(this).apply {
            addView(terminalBtn, FrameLayout.LayoutParams(-1, -1))
            addView(errorDot, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(5); rightMargin = dp(5)
            })
        }
        header.addView(terminalWrap, LinearLayout.LayoutParams(dp(36), dp(34)).apply { leftMargin = dp(5) })

        // + = Project Explorer (folder), file baru, buka file, dan semua alat editor.
        val add = MdiIconView(this).apply {
            setIconName("plus")
            setIconSize(21f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bg(Color.rgb(16,16,16), 14, Color.TRANSPARENT)
            contentDescription = "Laci tools editor. Tekan lama untuk menu lengkap"
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(34)).apply { leftMargin = dp(5) }
            elevation = dp(2).toFloat()
            // Ketuk: laci tools berkategori. Tekan lama: menu lengkap lama (tetap dipertahankan).
            setOnClickListener { EditorToolDrawer.toggle(this@renderEditorPage) }
            setOnLongClickListener { showEditorAddMenu(it); true }
        }
        header.addView(add)
        content.addView(header, LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(4) })

        // Mode tabs HTML/CSS/JS dihapus — ganti file lewat Project Explorer / multi-file tab.
        // Multi-file tab bar (open buffers)
        val fileTabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        editorTabBarContainer = fileTabContainer
        content.addView(fileTabContainer)
        ensureEditorTabForCurrent()
        refreshEditorTabBar()

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
            background = ColorDrawable(Color.TRANSPARENT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        editorBox = work

        // 2.45.15: Copy/Share/Find/Replace/Cek dipindah ke menu ⋯ header
        // agar editor tidak makan ruang vertikal.

        // Nomor baris + area kode berada dalam SATU kotak editor.
        // Gutter dibuat transparan agar border/background hanya menggambar satu
        // permukaan bersama, sehingga area kode terasa lebih lebar dan rapi.
        val gutterColor = if (isDarkTheme) Color.rgb(140, 145, 155) else Color.rgb(120, 126, 138)
        val lineNumbers = TextView(this).apply {
            text = "1"
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.END
            setTextColor(gutterColor)
            // Gutter transparan (sewarna kotak editor) dan dirapatkan ke kiri.
            setPadding(dp(2), dp(18), dp(4), dp(18))
            background = ColorDrawable(Color.TRANSPARENT)
            includeFontPadding = false
            setLineSpacing(0f, 1f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // Multi-buffer: prioritaskan buffer (memory / draft disk) agar HTML/CSS/JS
        // tidak hilang saat ganti tab atau buka file lain. Fallback ke file / external.
        ensureEditorBuffersHydrated()
        val bufferKey = editorBufferKey(editorFile, editorMode)
        editorCurrentBufferKey = bufferKey
        val buf = getOrCreateEditorBuffer(bufferKey, editorMode, editorFile)
        val externalText = editorExternalTarget?.text?.toString()
        when {
            externalText != null -> {
                work.setText(externalText)
                buf.text = externalText
            }
            // Prefer in-memory / draft buffer always (keeps HTML when switching CSS→HTML)
            editorBuffers.containsKey(bufferKey) -> {
                work.setText(buf.text)
            }
            editorFile != null -> {
                val disk = runCatching { editorFile?.let { GitlsMaxEngine.readUtf8Limited(it) }.orEmpty() }.getOrDefault("")
                work.setText(disk)
                buf.text = disk
            }
            else -> {
                work.setText(buf.text)
            }
        }
        // P2: soft-warn file/buffer besar (editor tetap bisa, tapi user sadar risiko lag)
        runCatching {
            val chars = work.text?.length ?: 0
            val diskBytes = editorFile?.takeIf { it.isFile }?.length() ?: -1L
            when {
                diskBytes > 8L * 1024L * 1024L || chars > 1_500_000 ->
                    toast("File sangat besar — editor bisa lambat. Pertimbangkan memecah file.")
                diskBytes > 2L * 1024L * 1024L || chars > 400_000 ->
                    toast("File besar (~${if (diskBytes > 0) "${diskBytes / 1024} KB" else "$chars karakter"}). Beberapa fitur mungkin lebih lambat.")
            }
        }
        // Pulihkan posisi kursor bila masih valid.
        val len = work.text?.length ?: 0
        if (len > 0 && (buf.selectionStart > 0 || buf.selectionEnd > 0)) {
            val s = buf.selectionStart.coerceIn(0, len)
            val e = buf.selectionEnd.coerceIn(s, len)
            runCatching { work.setSelection(s, e) }
        }
        // Gutter + kode dalam satu baris horizontal di dalam ScrollView
        // supaya nomor baris selalu ikut teks (tidak hilang saat scroll).
        work.onFocusChangeListener = null
        work.background = ColorDrawable(Color.TRANSPARENT)
        work.textSize = 14f
        work.typeface = android.graphics.Typeface.MONOSPACE
        work.includeFontPadding = false
        work.setLineSpacing(0f, 1f)
        work.setPadding(dp(4), dp(18), dp(12), dp(18))
        work.isVerticalScrollBarEnabled = false
        work.overScrollMode = View.OVER_SCROLL_NEVER
        work.minHeight = dp(280)
        work.minLines = 12
        // 1 baris logika = 1 nomor baris (geser horizontal, tanpa soft-wrap)
        work.setHorizontallyScrolling(true)
        work.setOnFocusChangeListener { v, _ ->
            v.background = ColorDrawable(Color.TRANSPARENT)
        }

        val codeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val gutterWidth = dp(34)
        codeRow.addView(lineNumbers, LinearLayout.LayoutParams(gutterWidth, -2))
        codeRow.addView(work, LinearLayout.LayoutParams(0, -2, 1f))

        val hScroll = android.widget.HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            // P2: kurangi konflik gesture vertikal vs horizontal
            isNestedScrollingEnabled = true
            addView(codeRow, LinearLayout.LayoutParams(-2, -2))
        }
        val editorScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = true
            setPadding(0, 0, 0, 0)
            isNestedScrollingEnabled = true
            addView(hScroll, LinearLayout.LayoutParams(-1, -2))
        }
        // Saat user menggeser horizontal, minta parent jangan merebut gesture
        hScroll.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        val editorSurface = FrameLayout(this).apply {
            background = bg(
                if (isDarkTheme) Color.rgb(25, 27, 31) else Color.rgb(250, 250, 252),
                18,
                if (isDarkTheme) Color.rgb(55, 58, 64) else Color.rgb(220, 224, 230)
            )
            clipChildren = true
            clipToPadding = true
            addView(editorScroll, FrameLayout.LayoutParams(-1, -1))
        }
        content.addView(editorSurface, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            bottomMargin = dp(3)
        })
        // Laci Tools Editor: overlay di atas area kode (bukan menu panjang / halaman lebih panjang).
        EditorToolDrawer.install(this, editorSurface, work)

        // Teks "Baris, Kolom, karakter | status error" dihapus. Error tampil di Terminal (tombol ⋯).
        editorStatusLabel = null
        content.addView(buildEditorTerminalPanel())
        fun refreshGutterAndStatus() {
            val txt = work.text?.toString().orEmpty()
            val totalLines = if (txt.isEmpty()) 1 else txt.count { it == '\n' } + 1
            val n = totalLines.coerceIn(1, 50_000)
            val sb = StringBuilder(n * 4)
            for (i in 1..n) {
                if (i > 1) sb.append('\n')
                sb.append(i)
            }
            lineNumbers.text = sb.toString()
            lineNumbers.requestLayout()
        }
        work.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                refreshGutterAndStatus()
            }
            override fun afterTextChanged(e: android.text.Editable?) {
                // Auto-simpan ke buffer + disk (debounce) setiap ketikan.
                val key = editorCurrentBufferKey ?: editorBufferKey(editorFile, editorMode)
                val buf2 = editorBuffers.getOrPut(key) {
                    MainActivity.EditorBuffer(filePath = editorFile?.absolutePath, mode = editorMode)
                }
                val wasClean = !buf2.dirty
                buf2.text = e?.toString().orEmpty()
                buf2.dirty = true
                buf2.mode = editorMode
                buf2.filePath = editorFile?.absolutePath
                editorCurrentBufferKey = key
                scheduleEditorAutoSave()
                // Update indikator dirty di nama file dan tab bar (hanya saat baru menjadi dirty).
                if (wasClean) {
                    val base = editorFile?.name ?: editorDefaultName(editorMode)
                    editorNameLabel?.text = "• $base"
                    editorNameLabel?.setTextColor(Color.rgb(230, 126, 34))
                    refreshEditorTabBar()
                }
            }
        })
        // Update status saat kursor digeser (tap / selection)
        work.setOnClickListener { refreshGutterAndStatus() }
        // Pastikan nomor baris lengkap setelah buka file / tempel kode
        work.post { refreshGutterAndStatus() }
        // Pewarnaan sintaks (HTML / CSS / JS / JSON) ala VS Code.
        EditorSyntax.attach(work, editorMode, isDarkTheme)
        // Step 19: deteksi error (garis bawah merah/kuning) untuk JS / HTML / CSS / JSON.
        EditorDiagnostics.attach(work, { editorMode }) { refreshEditorStatus(work) }
        // Auto-pair + auto-indent + Emmet on Enter/Tab (ketik ! lalu Enter → expand)
        EditorIdeExtras.attachAutoPair(work) { editorMode }
        // Bracket pair highlight
        EditorBracketMatch.attach(work, isDarkTheme)
        // Riwayat Undo/Redo andal (dipasang SETELAH teks awal dimuat agar isi file
        // tidak tercatat sebagai satu langkah undo).
        EditorUndoHistory.attach(work)
        work.setTextColor(if (isDarkTheme) textMain else android.graphics.Color.parseColor("#24292F"))

        // Keyboard accessory bar (symbols + Tab/Fmt/Preview/Assist)
        val kbContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) }
        }
        editorKeyboardBarContainer = kbContainer
        val kbBar = EditorKeyboardBar.build(this, work)
        var kbHidden = runCatching { prefs.getBoolean("editor_symbol_bar_hidden", false) }.getOrDefault(false)
        val kbChevron = MdiIconView(this).apply {
            setIconSize(18f)
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(22))
        }
        fun applyKbState() {
            kbBar.visibility = if (kbHidden) View.GONE else View.VISIBLE
            kbChevron.setIconName(if (kbHidden) "chevron-up" else "chevron-down")
            kbChevron.contentDescription = if (kbHidden) "Tampilkan bar simbol" else "Sembunyikan bar simbol"
        }
        applyKbState()
        val kbToggle = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(-1, dp(22))
            addView(kbChevron)
            setOnClickListener {
                kbHidden = !kbHidden
                applyKbState()
                runCatching { prefs.edit().putBoolean("editor_symbol_bar_hidden", kbHidden).apply() }
            }
        }
        kbContainer.addView(kbToggle)
        kbContainer.addView(kbBar)
        content.addView(kbContainer)

        // Tool-specific actions tetap bisa dipanggil dari toolbar bawah, tetapi daftar
        // mode/file tambahan tidak lagi memenuhi area editor.
        editorContextActions = null

        // Editor action bar is a global view outside the ScrollView. Re-render it
        // on the next UI pass as well, so page normalization/snapshot restoration
        // cannot leave a blank white bar after the editor content is rebuilt.
        renderEditorBottomBar(work)
        editorBottomBar.post {
            if (isEditorWorkspace()) {
                renderEditorBottomBar(work)
                editorBottomBar.visibility = View.VISIBLE
                editorBottomBar.alpha = 1f
                editorBottomBar.translationY = 0f
                editorBottomBar.bringToFront()
            }
        }
        animateEditorScreen()
    }
internal fun MainActivity.showEditorSearchDialog(work: EditText, replaceMode: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), 0) }
        val find = edit("Cari teks")
        val start = work.selectionStart.coerceAtLeast(0).coerceAtMost(work.length())
        val end = work.selectionEnd.coerceAtLeast(0).coerceAtMost(work.length())
        if (start < end) find.setText(work.text.subSequence(start, end).toString())
        box.addView(find)
        val replace = if (replaceMode) edit("Ganti dengan") else null
        replace?.let { box.addView(it) }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (replaceMode) "Cari & Ganti" else "Cari")
            .setView(box)
            .setNegativeButton("Batal", null)
        if (replaceMode) {
            builder.setNeutralButton("Ganti Satu") { _, _ ->
                val q = find.text.toString()
                if (q.isBlank()) { toast("Teks pencarian kosong"); return@setNeutralButton }
                val source = work.text.toString()
                val from = work.selectionStart.coerceAtLeast(0)
                val index = source.indexOf(q, from).let { if (it < 0 && from > 0) source.indexOf(q) else it }
                if (index < 0) { toast("Teks tidak ditemukan"); return@setNeutralButton }
                val r = replace?.text?.toString().orEmpty()
                work.text?.replace(index, index + q.length, r)
                work.setSelection(index, index + r.length)
                toast("1 kemunculan diganti")
            }
            builder.setPositiveButton("Ganti Semua") { _, _ ->
                val q = find.text.toString()
                if (q.isBlank()) { toast("Teks pencarian kosong"); return@setPositiveButton }
                val source = work.text.toString()
                val r = replace?.text?.toString().orEmpty()
                val count = source.split(q).size - 1
                if (count <= 0) { toast("Teks tidak ditemukan"); return@setPositiveButton }
                val result = source.replace(q, r)
                work.setText(result)
                work.setSelection(result.length.coerceAtMost(work.length()))
                toast("$count kemunculan diganti")
            }
        } else {
            builder.setNeutralButton("Cari Lagi") { _, _ ->
                val q = find.text.toString()
                if (q.isBlank()) { toast("Teks pencarian kosong"); return@setNeutralButton }
                val source = work.text.toString()
                val from = (work.selectionEnd).coerceAtLeast(0)
                var index = source.indexOf(q, from)
                if (index < 0 && from > 0) index = source.indexOf(q) // wrap
                if (index >= 0) {
                    work.requestFocus()
                    work.setSelection(index, index + q.length)
                } else toast("Teks tidak ditemukan")
            }
            builder.setPositiveButton("Cari") { _, _ ->
                val q = find.text.toString()
                if (q.isBlank()) { toast("Teks pencarian kosong"); return@setPositiveButton }
                val source = work.text.toString()
                val from = work.selectionStart.coerceAtLeast(0)
                val index = source.indexOf(q, from).let { if (it < 0 && from > 0) source.indexOf(q) else it }
                if (index >= 0) {
                    work.requestFocus()
                    work.setSelection(index, index + q.length)
                } else toast("Teks tidak ditemukan")
            }
        }
        builder.show()
    }
internal fun MainActivity.editorTab(labelText: String, mode: String): View = run {
    val activity = this
 LinearLayout(this).apply {
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
                // Simpan dulu buffer tab aktif (HTML/CSS/JS) sebelum pindah.
                flushEditorCurrentToBuffer(writeDisk = true)
                editorExternalTarget = null
                editorExternalMode = mode
                // Jika ada project folder aktif, buka file pasangan (index.html / style.css / script.js).
                // Kalau tidak, buffer mode-only tetap dipakai (HTML/CSS/JS independen).
                val projectFile = resolveProjectFile(mode, editorProjectDir)
                val keepFile = projectFile
                    ?: editorFile?.takeIf { detectEditorMode(it.name) == mode }
                editor(keepFile, mode)
            }
        }
        addView(MdiIconView(activity).apply {
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
        addView(TextView(activity).apply {
            text = labelText
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (editorMode == mode) textMain else textMuted)
            includeFontPadding = false
        })
    }

}

internal fun MainActivity.showEditorProjectTree() {
        val root = editorProjectDir
            ?: editorFile?.parentFile
            ?: prefs.getString("last_editor_project", null)?.let { File(it) }
            ?: prefs.getString("last_workspace", null)?.let { File(it) }
        val files = root?.listFiles()?.filter { it.isFile && it.name != "workspace.json" }?.sortedBy { it.name.lowercase(Locale.getDefault()) } ?: emptyList()
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Project Files")
                .setMessage("Belum ada file di project. Buat Project Web (HTML+CSS+JS) dari tombol + di editor.")
                .setPositiveButton("Buat Project") { _, _ -> createEditorWebProject() }
                .setNegativeButton("Tutup", null)
                .show()
            return
        }
        val names = files.map { f ->
            val mark = if (f.absolutePath == editorFile?.absolutePath) "✓  " else "    "
            val kind = when (detectEditorMode(f.name)) {
                "html" -> "HTML"
                "css" -> "CSS"
                "js" -> "JS"
                else -> f.extension.uppercase(Locale.getDefault()).ifBlank { "FILE" }
            }
            "$mark${f.name}  · $kind"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${root?.name ?: "Project"} • ${files.size} file")
            .setItems(names) { _, which ->
                val f = files[which]
                editorProjectDir = root
                editor(f, detectEditorMode(f.name))
            }
            .setNeutralButton("+ Project") { _, _ -> createEditorWebProject() }
            .setNegativeButton("Tutup", null)
            .show()
    }
internal fun MainActivity.appendEditorConsole(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        synchronized(editorConsoleLog) {
            if (editorConsoleLog.length > 120_000) {
                editorConsoleLog.delete(0, editorConsoleLog.length - 80_000)
            }
            editorConsoleLog.append('[').append(stamp).append("] ").append(line).append('\n')
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            editorConsoleView?.text = synchronized(editorConsoleLog) { editorConsoleLog.toString() }
        }
    }
internal fun MainActivity.clearEditorConsole() {
        synchronized(editorConsoleLog) { editorConsoleLog.setLength(0) }
        editorConsoleView?.text = "Console kosong. Jalankan Preview untuk menangkap console.log / error JS.\n"
    }
internal fun MainActivity.showEditorConsole() {
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
/** Menu tombol +: Project Explorer, file baru, buka file, dan seluruh alat editor. */
internal fun MainActivity.showEditorAddMenu(anchor: View) {
        val w = editorBox ?: return
        val popup = android.widget.PopupMenu(anchor.context, anchor)
        popup.menu.add(0, 20, 0, "Project Explorer")
        popup.menu.add(0, 12, 1, "File baru")
        popup.menu.add(0, 18, 2, "Tambah folder / project…")
        popup.menu.add(0, 13, 3, "Buka file")
        popup.menu.add(0, 14, 4, "Live preview")
        popup.menu.add(0, 15, 5, "Emmet")
        popup.menu.add(0, 1, 6, "Salin teks")
        popup.menu.add(0, 2, 7, "Bagikan")
        popup.menu.add(0, 3, 8, "Cari")
        popup.menu.add(0, 4, 9, "Ganti")
        popup.menu.add(0, 5, 10, "Cek kode")
        popup.menu.add(0, 6, 11, "Snippets")
        popup.menu.add(0, 7, 12, "Format document")
        popup.menu.add(0, 8, 13, "Outline")
        popup.menu.add(0, 9, 14, "Cari seluruh project")
        popup.menu.add(0, 10, 15, "Ke baris…")
        popup.menu.add(0, 11, 16, "Code Assist")
        popup.menu.add(0, 17, 17, "Saran kode")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val clip = android.content.ClipData.newPlainText("Editor", w.text.toString())
                    (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                    toast("Teks disalin")
                }
                2 -> shareText(w.text.toString())
                3 -> showEditorSearchDialog(w, false)
                4 -> showEditorSearchDialog(w, true)
                5 -> showEditorDiagnostics()
                6 -> showEditorSnippets()
                7 -> formatEditorDocument()
                8 -> showEditorOutline()
                9 -> showEditorProjectSearch()
                10 -> showEditorGoToLine()
                11 -> showEditorCodeAssist()
                12 -> showEditorCreateFilePicker()
                13 -> pickFileForEditor()
                14 -> showEditorLivePreview()
                15 -> applyEditorEmmet()
                17 -> EditorIdeExtras.showSuggestionPopup(this, w, editorMode)
                18 -> showEditorModePicker()
                20 -> showEditorProjectExplorer(anchor)
            }
            true
        }
        popup.show()
    }

/** Panel Terminal di dalam editor: log error editor, console.log / error Preview, dan status simpan. */
internal fun MainActivity.buildEditorTerminalPanel(): View {
        val fg = if (isDarkTheme) Color.rgb(200, 220, 200) else Color.rgb(30, 40, 30)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(if (isDarkTheme) Color.rgb(18, 20, 22) else Color.rgb(248, 250, 248), 14, line)
            visibility = if (editorTerminalOpen) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, dp(170)).apply { bottomMargin = dp(3) }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
        }
        bar.addView(MdiIconView(this).apply {
            setIconName("console")
            setIconSize(15f)
            setTextColor(textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(30))
        })
        bar.addView(TextView(this).apply {
            text = "Terminal"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textMain)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        fun chipBtn(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 11.5f
            setTextColor(textMain)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            minHeight = dp(30)
            isClickable = true
            setOnClickListener { action() }
        }
        bar.addView(chipBtn("Bersihkan") { clearEditorConsole() })
        bar.addView(chipBtn("Salin") {
            val txt = synchronized(editorConsoleLog) { editorConsoleLog.toString() }
            if (txt.isBlank()) toast("Terminal kosong") else copyText(txt)
        })
        bar.addView(chipBtn("Penuh") { showEditorConsole() })
        bar.addView(MdiIconView(this).apply {
            setIconName("close")
            setIconSize(16f)
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            contentDescription = "Tutup terminal"
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(30))
            setOnClickListener { toggleEditorTerminal(false) }
        })
        panel.addView(bar, LinearLayout.LayoutParams(-1, dp(30)))

        val logView = TextView(this).apply {
            text = synchronized(editorConsoleLog) {
                if (editorConsoleLog.isEmpty()) "$ siap. Error dari editor dan Preview akan muncul di sini.\n" else editorConsoleLog.toString()
            }
            textSize = 11.5f
            setTextColor(fg)
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextIsSelectable(true)
            setPadding(dp(10), dp(4), dp(10), dp(8))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(logView) }
        // Selalu gulir ke baris terbaru saat ada log baru.
        logView.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
        })
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        editorConsoleView = logView
        editorTerminalPanel = panel
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return panel
    }

internal fun MainActivity.toggleEditorTerminal(open: Boolean? = null) {
        val panel = editorTerminalPanel ?: return
        val show = open ?: (panel.visibility != View.VISIBLE)
        editorTerminalOpen = show
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            editorTerminalUnseenErrors = false
            editorTerminalBadge?.visibility = View.GONE
        }
    }
internal fun MainActivity.renameEditorFile() {
        val input = edit("Nama file")
        input.setText(editorNameLabel?.text?.toString()?.removePrefix("Tanpa judul") ?: editorDefaultName(editorMode))
        themedDialog().setTitle("Nama file").setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("OK") { _, _ ->
                val n = safeFileName(input.text.toString()).ifBlank {
                    toast("Nama file tidak valid"); return@setPositiveButton
                }
                val currentFile = editorFile
                if (currentFile == null) {
                    editorNameLabel?.text = n
                    toast("Nama ditampilkan: $n (belum ada file di disk)")
                    return@setPositiveButton
                }
                if (currentFile.name == n) return@setPositiveButton
                val next = safeChildFile(currentFile.parentFile ?: filesDir, n)
                if (next == null) { toast("Nama file tidak valid"); return@setPositiveButton }
                if (next.exists()) { toast("Nama sudah dipakai: $n"); return@setPositiveButton }
                flushEditorCurrentToBuffer(writeDisk = true)
                val oldPath = currentFile.absolutePath
                val oldKey = editorBufferKey(currentFile, editorMode)
                val ok = runCatching { currentFile.renameTo(next) }.getOrDefault(false)
                if (!ok || !next.exists()) {
                    toast("Gagal rename file")
                    return@setPositiveButton
                }
                editorFile = next
                editorNameLabel?.text = next.name
                // Migrasi buffer + draft + tab ke path baru (P0)
                val newKey = editorBufferKey(next, editorMode)
                val buf = editorBuffers.remove(oldKey)
                if (buf != null) {
                    buf.filePath = next.absolutePath
                    buf.mode = editorMode
                    editorBuffers[newKey] = buf
                    persistEditorBuffer(newKey, buf)
                } else {
                    val textNow = editorBox?.text?.toString().orEmpty()
                    val nb = MainActivity.EditorBuffer(
                        text = textNow,
                        filePath = next.absolutePath,
                        mode = editorMode,
                        dirty = false
                    )
                    editorBuffers[newKey] = nb
                    persistEditorBuffer(newKey, nb)
                }
                // Hapus draft lama
                runCatching { draftFileForKey(oldKey).delete() }
                editorCurrentBufferKey = newKey
                // Update tab list
                val tabIdx = editorOpenTabs.indexOfFirst { it.key == oldKey || it.file?.absolutePath == oldPath }
                if (tabIdx >= 0) {
                    editorOpenTabs[tabIdx] = EditorTabBar.Tab(newKey, next.name, next, editorMode, editorSourceUri?.toString())
                }
                editorActiveTabKey = newKey
                refreshEditorTabBar()
                toast("Rename: ${currentFile.name} → ${next.name}")
            }.show()
    }
internal fun MainActivity.renderEditorBottomBar(work: EditText) {
        val bar = editorBottomBar

        // The editor toolbar is a sticky global view, not part of the ScrollView.
        // Always restore its surface/geometry explicitly: otherwise a previous page
        // (or a theme refresh) can leave a plain white area with no visible actions.
        bar.removeAllViews()
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.visibility = View.VISIBLE
        bar.alpha = 1f
        bar.translationY = 0f
        bar.setPadding(0, 0, 0, 0)
        bar.background = bg(if (isDarkTheme) panel else Color.WHITE, 0, if (isDarkTheme) line else Color.rgb(220, 223, 228))
        bar.elevation = dp(8).toFloat()
        bar.minimumHeight = dp(60)
        bar.bringToFront()
        // Hanya aksi yang paling sering dipakai; sisanya (File baru, Buka, Live, Emmet, Format,
        // Outline, Saran kode, Console) ada di menu ⋯ pada header. Lebar tombol dibagi rata
        // sehingga semuanya muat tanpa scroll dan tanpa label terpotong.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        }
        val actions = listOf(
            "content-save-outline" to ("Simpan" to { saveEditorCurrent() }),
            "undo" to ("Undo" to { work.undoSafe() }),
            "redo" to ("Redo" to { work.redoSafe() }),
            "magnify" to ("Cari" to { val w = editorBox ?: work; showEditorSearchDialog(w, false) }),
            "web" to ("Preview" to { showEditorLivePreview() }),
            "tools" to ("Tools" to { EditorToolDrawer.toggle(this) })
        )
        val actionCells = HashMap<String, View>()
        actions.forEach { (iconName, item) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = item.first
                setOnClickListener { animateEditorPress(it); it.postDelayed({ item.second() }, 40L) }
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply {
                    leftMargin = dp(2)
                    rightMargin = dp(2)
                }
                setPadding(dp(2), dp(2), dp(2), dp(2))
                background = rippleBg(Color.TRANSPARENT, 12, Color.TRANSPARENT)
            }
            cell.addView(MdiIconView(this).apply {
                setIconName(iconName)
                setIconSize(22f)
                setTextColor(textMain)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            })
            cell.addView(TextView(this).apply {
                text = item.first
                textSize = 10.5f
                setTextColor(textMain)
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(-1, dp(16))
            })
            row.addView(cell)
            actionCells[item.first] = cell
        }
        bar.addView(row)
        // Undo/Redo meredup saat tidak ada langkah, mengikuti riwayat editor.
        val history = EditorUndoHistory.of(work)
        if (history != null) {
            val sync = {
                actionCells["Undo"]?.alpha = if (history.canUndo) 1f else 0.38f
                actionCells["Redo"]?.alpha = if (history.canRedo) 1f else 0.38f
            }
            history.onStateChanged = { sync() }
            sync()
        }
    }
internal fun MainActivity.previewUnifiedEditor(work: EditText) {
        // Preview adalah mode tampilan penuh: toolbar aksi (Baru, Buka, Simpan,
        // Undo, Redo, Cari, Preview, Emmet, Pilih) hanya diperlukan saat mengedit.
        // Sembunyikan sebelum halaman Preview dibuat agar tidak tetap menempel di bawah.
        editorBottomBar.visibility = View.GONE
        val text = work.text.toString()
        when (editorMode) {
            "html", "css", "js" -> {
                // Gabungkan HTML + CSS + JS dari project folder yang sama (atau buffer).
                val linked = buildLinkedWebPreviewHtml()
                val base = editorProjectDir?.takeIf { it.isDirectory }?.toURI()?.toString()
                previewHtmlText(linked, "HTML", baseUrl = base)
            }
            "json" -> if (LottieJson.looksLikeLottie(text)) showLottiePreview(text) else output(text)
            else -> output(text)
        }
    }
internal fun MainActivity.setEditorMode(mode: String) {
        flushEditorCurrentToBuffer(writeDisk = true)
        val currentFile = editorFile
        editorExternalTarget = null
        editorExternalMode = null
        editor(currentFile, mode)
    }
internal fun MainActivity.refreshEditorContextActions() {
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
internal fun MainActivity.copyEditorText() {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clip.setPrimaryClip(android.content.ClipData.newPlainText("Editor", editorBox?.text?.toString().orEmpty()))
        toast("Teks disalin")
    }
internal fun MainActivity.selectedOrAllText(): String {
        val box = editorBox ?: return ""
        val a = minOf(box.selectionStart, box.selectionEnd)
        val b = maxOf(box.selectionStart, box.selectionEnd)
        return if (a != b) box.text.substring(a, b) else box.text.toString()
    }
internal fun MainActivity.replaceSelectedOrAll(value: String) {
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
internal fun MainActivity.showEditorCaseDialog() {
        val items = arrayOf("UPPERCASE", "lowercase", "Title Case", "Slug / URL", "Hitung kata & karakter")
        AlertDialog.Builder(this).setTitle("Text Case & Utility").setItems(items) { _, which ->
            when (which) {
                0 -> replaceSelectedOrAll(selectedOrAllText().uppercase(Locale.getDefault()))
                1 -> replaceSelectedOrAll(selectedOrAllText().lowercase(Locale.getDefault()))
                2 -> replaceSelectedOrAll(selectedOrAllText().lowercase(Locale.getDefault()).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { word -> if (word.isEmpty()) word else word.substring(0, 1).uppercase(Locale.getDefault()) + word.substring(1) })
                3 -> replaceSelectedOrAll(selectedOrAllText().trim().lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]+"), "-").trim('-'))
                4 -> showTextCount()
            }
        }.setNegativeButton("Batal", null).show()
    }
internal fun MainActivity.showTextCount() {
        val s = selectedOrAllText()
        val words = s.trim().let { if (it.isEmpty()) 0 else it.split(Regex("\\s+")).size }
        toast("$words kata • ${s.length} karakter")
    }
internal fun MainActivity.showEditorBase64Dialog() {
        AlertDialog.Builder(this).setTitle("Base64").setItems(arrayOf("Encode", "Decode")) { _, which ->
            if (which == 0) encodeBase64Editor() else decodeBase64Editor()
        }.setNegativeButton("Batal", null).show()
    }
internal fun MainActivity.encodeBase64Editor() {
        val bytes = selectedOrAllText().toByteArray(StandardCharsets.UTF_8)
        replaceSelectedOrAll(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
    }
internal fun MainActivity.decodeBase64Editor() {
        runCatching { String(android.util.Base64.decode(selectedOrAllText().trim(), android.util.Base64.DEFAULT), StandardCharsets.UTF_8) }
            .onSuccess { replaceSelectedOrAll(it) }
            .onFailure { toast("Base64 tidak valid") }
    }
internal fun MainActivity.normalizeCsvEditor() {
        val lines = editorBox?.text?.toString()?.lines()?.filter { it.isNotBlank() }.orEmpty()
        if (lines.isEmpty()) return
        val delim = if (lines.first().count { it == ';' } > lines.first().count { it == ',' }) ';' else ','
        val out = lines.joinToString("\n") { csvParseLine(it, delim).joinToString(",") { cell -> csvEscape(cell.trim()) } }
        editorBox?.setText(out)
        editorBox?.setSelection(editorBox?.length() ?: 0)
    }
internal fun MainActivity.csvParseLine(line: String, delimiter: Char): List<String> {
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
internal fun MainActivity.csvEscape(s: String): String = if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"${s.replace("\"", "\"\"")}\"" else s

internal fun MainActivity.csvToJsonEditor() {
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
internal fun MainActivity.jsonToCsvEditor() {
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
internal fun MainActivity.showCsvInfo() {
        val lines = editorBox?.text?.toString()?.lines()?.filter { it.isNotBlank() }.orEmpty()
        val delimiter = lines.firstOrNull()?.let { if (it.count { c -> c == ';' } > it.count { c -> c == ',' }) ';' else ',' } ?: ','
        val cols = lines.firstOrNull()?.let { csvParseLine(it, delimiter).size } ?: 0
        toast("${lines.size} baris • $cols kolom")
    }
/**
 * Migrasikan seluruh state editor dari path lama ke path baru.
 * Dipakai oleh File Manager maupun Editor agar Rename/Move tidak meninggalkan
 * tab, buffer, autosave key, atau editorFile yang masih menunjuk ke path lama.
 */
internal fun MainActivity.migrateEditorPath(oldFile: File, newFile: File) {
    val oldPath = runCatching { oldFile.canonicalPath }.getOrDefault(oldFile.absolutePath)
    val newPath = runCatching { newFile.canonicalPath }.getOrDefault(newFile.absolutePath)
    if (oldPath == newPath) return

    fun remap(path: String?): String? {
        if (path == null) return null
        return when {
            path == oldPath -> newPath
            (oldFile.isDirectory || newFile.isDirectory) && path.startsWith(oldPath + File.separator) ->
                newPath + path.removePrefix(oldPath)
            else -> null
        }
    }

    // Re-key buffers first. Destination names are checked by the caller, but if a
    // stale buffer already exists at the destination, prefer the actively migrated
    // buffer rather than silently losing the current text.
    val affected = editorBuffers.entries
        .mapNotNull { (key, buffer) ->
            val mapped = remap(buffer.filePath ?: key)
            if (mapped != null) Triple(key, mapped, buffer) else null
        }
    affected.forEach { (oldKey, newKey, buffer) ->
        if (oldKey != newKey) editorBuffers.remove(oldKey)
        buffer.filePath = newKey
        editorBuffers[newKey] = buffer
    }

    // Rebuild tab objects because Tab fields are immutable.
    for (i in editorOpenTabs.indices) {
        val tab = editorOpenTabs[i]
        val mapped = remap(tab.file?.absolutePath)
        if (mapped != null) {
            val nf = File(mapped)
            val nk = remap(tab.key) ?: tab.key
            editorOpenTabs[i] = EditorTabBar.Tab(nk, nf.name, nf, tab.mode, tab.sourceUri)
        }
    }

    editorCurrentBufferKey = remap(editorCurrentBufferKey) ?: editorCurrentBufferKey
    editorActiveTabKey = remap(editorActiveTabKey) ?: editorActiveTabKey

    editorFile?.let { current ->
        val mapped = remap(current.absolutePath)
        if (mapped != null) {
            editorFile = File(mapped)
            editorNameLabel?.text = editorFile?.name ?: "Tanpa judul"
        }
    }
    editorProjectDir?.let { project ->
        val mapped = remap(project.absolutePath)
        if (mapped != null) editorProjectDir = File(mapped)
    }

    // A pending autosave captured the old identity. Cancel it and let the next edit
    // schedule a new callback using the new path/key.
    if (editorCurrentBufferKey != null && remap(oldPath) != null) cancelEditorAutoSave()
    refreshEditorTabBar()
}

/**
 * Hapus seluruh state editor yang menunjuk ke path yang sudah dihapus.
 * Dipakai oleh semua jalur penghapusan agar tidak meninggalkan tab/buffer orphan.
 */
internal fun MainActivity.invalidateEditorForDeletedPath(file: File) {
    val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    val openPath = editorFile?.let { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    val affectsOpenEditor = openPath == path || (file.isDirectory && openPath?.startsWith(path + File.separator) == true)
    val doomedKeys = editorBuffers.keys.filter { key ->
        key == path || key.startsWith(path + File.separator) || key.startsWith(path + "/")
    }.toList()
    doomedKeys.forEach { editorBuffers.remove(it) }
    editorOpenTabs.removeAll { tab ->
        val tabPath = tab.file?.let { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        tab.key in doomedKeys || tabPath == path || (file.isDirectory && tabPath?.startsWith(path + File.separator) == true)
    }
    if (affectsOpenEditor) {
        cancelEditorAutoSave()
        editorFile = null
        editorExternalTarget = null
        editorExternalMode = null
        editorCurrentBufferKey = null
        editorActiveTabKey = ""
        editorNameLabel?.text = "Tanpa judul"
        editorBox?.setText("")
        refreshEditorTabBar()
    }
}

internal fun MainActivity.saveEditorCurrent() {
        val box = editorBox ?: return
        // Manual save wins over any pending debounce callback.
        cancelEditorAutoSave()
        editorExternalTarget?.let {
            it.setText(box.text.toString())
            toast("Diterapkan ke ${editorMode.uppercase(Locale.getDefault())}")
            return
        }

        // Prioritas simpan:
        // 1) URI SAF (file dibuka dari storage) → timpa
        // 2) File project internal (web_projects / editorFile) → tulis langsung
        // 3) Belum ada file → dialog Simpan sebagai
        flushEditorCurrentToBuffer(writeDisk = false)
        when {
            editorSourceUri != null -> saveEditorQuick()
            editorFile != null && editorFile!!.exists() -> saveEditorInternal()
            editorFile != null -> saveEditorInternal() // path sudah ditentukan (project baru)
            else -> saveEditorAs()
        }
    }
internal fun MainActivity.saveEditorQuick() {
        val box = editorBox ?: return
        val src = editorSourceUri
        if (src != null) {
            val quickText = box.text.toString()
            val quickKey = editorCurrentBufferKey ?: editorBufferKey(editorFile, editorMode)
            // Salinan aman di draft sebelum dokumen asli dikosongkan oleh mode "wt".
            editorBuffers[quickKey]?.let { it.text = quickText; it.dirty = true; persistEditorBuffer(quickKey, it) }
            runCatching {
                writeSafDocumentVerified(src, quickText)
            }.onSuccess {
                val key = quickKey
                editorBuffers[key]?.let { it.text = quickText; it.dirty = false }
                editorBuffers[key]?.let { persistEditorBuffer(key, it) }
                toast("Tersimpan: ${queryName(src) ?: "file"}")
            }.onFailure { err ->
                // A revoked SAF grant must not leave a dead URI attached to the editor.
                if (err is SecurityException || err is java.io.FileNotFoundException) {
                    editorSourceUri = null
                    prefs.edit().remove("editor_source_uri").apply()
                }
                toast("Gagal menimpa file asli: ${err.message}. Coba Simpan sebagai.")
            }
            return
        }
        saveEditorInternal()
    }
internal fun MainActivity.saveEditorAs() {
        val box = editorBox ?: return
        editorSaveAsText = box.text.toString()
        val name = safeFileName((editorNameLabel?.text?.toString() ?: "").trim().ifEmpty { editorDefaultName(editorMode) })
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "text/plain"
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }, 1031)
    }
internal fun MainActivity.saveEditorInternal() {
        val box = editorBox ?: return
        val name = safeFileName((editorNameLabel?.text?.toString() ?: "").trim().ifEmpty { editorDefaultName(editorMode) })
        val target = editorFile ?: safeChildFile(filesDir, name)
        if (target == null) { toast("Nama file tidak valid"); return }
        runCatching {
            target.parentFile?.mkdirs()
            val text = box.text.toString()
            val existingBuf = editorBuffers[editorCurrentBufferKey ?: editorBufferKey(target, editorMode)]
            if (target.exists() && existingBuf != null && existingBuf.lastDiskModified >= 0L &&
                (target.lastModified() != existingBuf.lastDiskModified || target.length() != existingBuf.lastDiskSize)) {
                error("File berubah di luar GITLS. Muat ulang atau bandingkan perubahan sebelum menyimpan.")
            }
            val temp = File(target.parentFile, ".${target.name}.gitls-tmp-${java.util.UUID.randomUUID()}")
            try {
                temp.writeText(text, StandardCharsets.UTF_8)
                if (target.exists()) {
                    val backup = File(target.parentFile, ".${target.name}.gitls-bak")
                    if (backup.exists()) backup.delete()
                    if (!target.renameTo(backup)) error("Tidak bisa menyiapkan file lama")
                    if (!temp.renameTo(target)) {
                        backup.renameTo(target)
                        error("Tidak bisa memasang file baru")
                    }
                    backup.delete()
                } else if (!temp.renameTo(target)) {
                    error("Tidak bisa memasang file baru")
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
            editorFile = target
            editorNameLabel?.text = target.name
            // Sinkronkan buffer & draft disk setelah simpan manual.
            val key = editorBufferKey(target, editorMode)
            val buf = editorBuffers.getOrPut(key) {
                MainActivity.EditorBuffer(filePath = target.absolutePath, mode = editorMode)
            }
            buf.text = box.text.toString()
            buf.filePath = target.absolutePath
            buf.mode = editorMode
            buf.dirty = false
            buf.lastDiskModified = target.lastModified()
            buf.lastDiskSize = target.length()
            editorCurrentBufferKey = key
            persistEditorBuffer(key, buf)
        }.onSuccess {
            toast("Tersimpan: ${target.name}")
            appendEditorConsole("Tersimpan ${target.name}")
            editorNameLabel?.text = target.name
            editorNameLabel?.setTextColor(textMain)
            refreshEditorTabBar()
        }
            .onFailure { toast("Gagal menyimpan: ${it.message}") }
    }
internal fun MainActivity.validateEditorJson() {
        val s = editorBox?.text?.toString()?.trim().orEmpty()
        val result = runCatching {
            if (s.startsWith("{")) JSONObject(s) else if (s.startsWith("[")) JSONArray(s) else error("JSON harus dimulai dengan { atau [")
            "JSON valid"
        }.getOrElse { "JSON tidak valid: ${it.message}" }
        toast(result)
    }
internal fun MainActivity.refreshEditorStatus(work: EditText) {
        // Tidak ada lagi indikator di layar editor; hasil pemeriksaan dikirim ke Terminal.
        if (!CodeDiagnostics.supports(editorMode)) return
        val issues = EditorDiagnostics.lastIssues
        val key = editorCurrentBufferKey ?: editorBufferKey(editorFile, editorMode)
        val fileName = editorFile?.name ?: editorNameLabel?.text?.toString() ?: editorDefaultName(editorMode)
        fun sig(i: CodeDiagnostics.Issue) = "${i.severity}:${i.line}:${i.column}:${i.message}"
        val now = issues.map { sig(it) }.toSet()
        val prev = editorDiagSeen[key]
        if (prev == now) return
        editorDiagSeen[key] = now
        if (now.isEmpty()) {
            if (prev != null) appendEditorConsole("✓ $fileName — tidak ada error")
            return
        }
        val fresh = issues.filter { sig(it) !in (prev ?: emptySet()) }
        fresh.take(20).forEach { i ->
            val tag = if (i.severity == CodeDiagnostics.Severity.ERROR) "ERROR" else "WARN"
            appendEditorConsole("$tag $fileName:${i.line}:${i.column} — ${i.message}")
        }
        if (fresh.size > 20) appendEditorConsole("… dan ${fresh.size - 20} masalah lain (buka ＋ › Cek kode)")
        if (fresh.any { it.severity == CodeDiagnostics.Severity.ERROR } && !editorTerminalOpen) {
            editorTerminalUnseenErrors = true
            runOnUiThread { editorTerminalBadge?.visibility = View.VISIBLE }
        }
    }
internal fun MainActivity.showEditorDiagnostics() {
        val box = editorBox
        if (box == null) { toast("Buka file di Text Editor dulu"); return }
        if (!CodeDiagnostics.supports(editorMode)) {
            toast("Pemeriksaan error tersedia untuk JS, HTML, CSS, dan JSON")
            return
        }
        val issues = EditorDiagnostics.analyzeNow(box.text.toString(), editorMode)
        refreshEditorStatus(box)
        if (issues.isEmpty()) { toast("Tidak ada error terdeteksi"); return }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        var dialog: AlertDialog? = null
        issues.take(50).forEach { issue ->
            list.addView(TextView(this).apply {
                val mark = if (issue.severity == CodeDiagnostics.Severity.ERROR) "Error" else "Peringatan"
                text = "$mark • Baris ${issue.line}:${issue.column} — ${issue.message}" +
                    (issue.fix?.let { "\n   ↳ ${it.label}" } ?: "")
                textSize = 13f
                setTextColor(textMain)
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener {
                    jumpToEditorIssue(box, issue)
                    dialog?.dismiss()
                }
            })
        }
        val fixable = issues.count { it.fix != null }
        val builder = AlertDialog.Builder(this)
            .setTitle("${issues.size} masalah ditemukan")
            .setView(android.widget.ScrollView(this).apply { addView(list) })
            .setNegativeButton("Tutup", null)
        if (fixable > 0) builder.setPositiveButton("Perbaiki ($fixable)") { _, _ -> showEditorFixPreview() }
        dialog = builder.show()
    }
internal fun MainActivity.jumpToEditorIssue(box: EditText, issue: CodeDiagnostics.Issue) {
        val len = box.text.length
        val s = issue.start.coerceIn(0, len)
        box.requestFocus()
        box.setSelection(s, issue.end.coerceIn(s, len))
    }
internal fun MainActivity.showEditorFixPreview() {
        val box = editorBox
        if (box == null) { toast("Buka file di Text Editor dulu"); return }
        if (!isEditorWorkspace() && editorFile == null) {
            toast("Buka Text Editor dulu untuk menerapkan perbaikan")
            return
        }
        val original = box.text.toString()
        val issues = EditorDiagnostics.analyzeNow(original, editorMode)
        val fixable = issues.filter { it.fix != null }
        if (fixable.isEmpty()) { toast("Tidak ada perbaikan otomatis yang tersedia"); return }
        val sb = StringBuilder()
        fixable.take(30).forEach { issue ->
            sb.append("Baris ${issue.line} — ${issue.fix?.label}\n")
            CodeDiagnostics.previewOf(original, issue)?.let { (before, after) ->
                sb.append("  − $before\n  + $after\n")
            }
            sb.append('\n')
        }
        if (fixable.size > 30) sb.append("…dan ${fixable.size - 30} perbaikan lainnya.\n")
        val tv = TextView(this).apply {
            text = sb.toString().trimEnd()
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(textMain)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("Pratinjau ${fixable.size} perbaikan")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Terapkan") { _, _ -> applyEditorFixes(box, issues) }
            .setNegativeButton("Batal", null)
            .show()
    }
internal fun MainActivity.applyEditorFixes(box: EditText, issues: List<CodeDiagnostics.Issue>) {
        val original = box.text.toString()
        val (fixed, _) = CodeDiagnostics.applyAllFixes(original, issues)
        if (fixed == original) { toast("Tidak ada perubahan"); return }
        val caret = box.selectionStart.coerceAtLeast(0)
        box.text.replace(0, box.text.length, fixed)
        box.setSelection(caret.coerceAtMost(box.text.length))
        // Dari layar selain editor (mis. chat Bit), simpan ke file agar tidak hilang saat editor dibuka ulang.
        var saved = ""
        if (!isEditorWorkspace()) {
            editorFile?.let { f ->
                val ok = runCatching { writeTextAtomically(f, fixed) }.isSuccess
                if (ok) {
                    saved = " dan disimpan ke ${f.name}"
                    // Sinkronkan metadata buffer agar autosave berikutnya tidak salah mendeteksi "berubah di luar GITLS".
                    editorBuffers[editorCurrentBufferKey ?: editorBufferKey(f, editorMode)]?.let { b ->
                        b.text = fixed
                        b.dirty = false
                        b.lastDiskModified = f.lastModified()
                        b.lastDiskSize = f.length()
                    }
                }
            }
        }
        val remaining = EditorDiagnostics.analyzeNow(fixed, editorMode)
        refreshEditorStatus(box)
        BitRuntimeContext.onToolResult("fixed", "Perbaikan diterapkan, sisa ${remaining.size} masalah")
        toast(if (remaining.isEmpty()) "Perbaikan diterapkan$saved" else "Diterapkan$saved; ${remaining.size} masalah perlu dicek manual")
    }
internal fun MainActivity.transformEditorJson(pretty: Boolean) {
        val box = editorBox ?: return
        runCatching {
            box.setText(if (pretty) prettyJson(box.text.toString()) else minifyJson(box.text.toString()))
            box.setSelection(box.length())
        }.onFailure { toast("JSON tidak valid: ${it.message}") }
    }
internal fun MainActivity.showEditorFindDialog(replace: Boolean) {
        val find = edit("Cari")
        val repl = if (replace) edit("Ganti dengan") else null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        box.addView(find); repl?.let { box.addView(it) }
        themedDialog().setTitle(if (replace) "Cari & Ganti" else "Cari")
            .setView(box)
            .setNegativeButton("Batal", null)
            .setPositiveButton(if (replace) "Ganti semua" else "Cari") { _, _ ->
                val box = editorBox ?: return@setPositiveButton
                val source = box.text?.toString().orEmpty()
                val q = find.text.toString()
                if (q.isEmpty()) { toast("Teks pencarian kosong"); return@setPositiveButton }
                if (replace) {
                    val replText = repl?.text?.toString().orEmpty()
                    val count = source.split(q).size - 1
                    box.setText(source.replace(q, replText))
                    toast(if (count > 0) "Diganti $count kemunculan" else "Tidak ditemukan")
                } else {
                    val from = box.selectionEnd.coerceIn(0, source.length)
                    var idx = source.indexOf(q, from, ignoreCase = true)
                    if (idx < 0 && from > 0) idx = source.indexOf(q, 0, ignoreCase = true) // wrap
                    if (idx >= 0) {
                        box.requestFocus()
                        box.setSelection(idx, idx + q.length)
                        toast("Ditemukan di posisi $idx")
                    } else toast("Tidak ditemukan")
                }
            }.show()
    }
internal fun MainActivity.formatConfigEditor() {
        val box = editorBox ?: return
        val out = box.text.toString().lines().joinToString("\n") { line ->
            line.trim().replace(Regex("\\s*=\\s*"), " = ")
        }.trim()
        box.setText(out)
    }
internal fun MainActivity.validateXmlEditor() {
        val s = editorBox?.text?.toString().orEmpty()
        runCatching {
            val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            f.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(s)))
            "XML valid"
        }.onSuccess { toast(it) }.onFailure { toast("XML tidak valid: ${it.message}") }
    }
internal fun MainActivity.formatXmlEditor() {
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
// undoSafe()/redoSafe() dipindah ke feature/text/EditorToolDrawer.kt dan kini memakai
// EditorUndoHistory (riwayat milik GITLS). Versi refleksi lama selalu gagal karena field
// "mEditor" berada di TextView, bukan EditText, dan diblokir sebagai non-SDK API.
internal fun MainActivity.shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "*/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Bagikan file"))
    }

// ---------- Multi-file tabs + Live Preview (2.45.55) ----------

internal fun MainActivity.ensureEditorTabForCurrent() {
    val key = editorBufferKey(editorFile, editorMode)
    editorCurrentBufferKey = key
    val label = editorFile?.name ?: editorDefaultName(editorMode)
    val existing = editorOpenTabs.indexOfFirst { it.key == key }
    if (existing >= 0) {
        // Update label/file/mode in case they changed
        editorOpenTabs[existing] = EditorTabBar.Tab(key, label, editorFile, editorMode, editorSourceUri?.toString())
    } else {
        editorOpenTabs.add(EditorTabBar.Tab(key, label, editorFile, editorMode, editorSourceUri?.toString()))
    }
    editorActiveTabKey = key
}

internal fun MainActivity.refreshEditorTabBar() {
    val container = editorTabBarContainer ?: return
    container.removeAllViews()
    if (editorOpenTabs.isEmpty()) return
    val bar = EditorTabBar.build(
        this,
        editorOpenTabs.toList(),
        editorActiveTabKey,
        onSelect = { tab ->
            if (tab.key == editorActiveTabKey) return@build
            // Selalu rebuild penuh agar mode/syntax/header/buffer selaras
            // (hindari bug: tab JS menampilkan isi CSS)
            flushEditorCurrentToBuffer(writeDisk = true)
            editorActiveTabKey = tab.key
            val tabUri = tab.sourceUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            editorSourceUri = tabUri
            editor(tab.file, tab.mode, tabUri)
        },
        onClose = { tab ->
            flushEditorCurrentToBuffer(writeDisk = true)
            val dirty = editorBuffers[tab.key]?.dirty == true
            fun doClose() {
                editorOpenTabs.removeAll { it.key == tab.key }
                if (editorActiveTabKey == tab.key) {
                    val next = editorOpenTabs.lastOrNull()
                    if (next != null) {
                        editorActiveTabKey = next.key
                        val nextUri = next.sourceUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                        editorSourceUri = nextUri
                        editor(next.file, next.mode, nextUri)
                    } else {
                        editorActiveTabKey = ""
                        refreshEditorTabBar()
                    }
                } else {
                    refreshEditorTabBar()
                }
            }
            if (dirty) {
                AlertDialog.Builder(this)
                    .setTitle("Tutup tab?")
                    .setMessage("\"${tab.label}\" punya perubahan yang belum disimpan.\n\nTutup tanpa menyimpan, atau batalkan?")
                    .setPositiveButton("Tutup tanpa simpan") { _, _ -> doClose() }
                    .setNeutralButton("Simpan lalu tutup") { _, _ ->
                        // Paksa flush ke disk jika ada file, lalu tutup
                        val buf = editorBuffers[tab.key]
                        if (buf != null) {
                            runCatching {
                                val f = tab.file
                                if (f != null) {
                                    writeTextAtomically(f, buf.text)
                                    buf.dirty = false
                                    persistEditorBuffer(tab.key, buf)
                                } else {
                                    // Tanpa file: tetap simpan draft, tandai bersih relatif ke draft
                                    persistEditorBuffer(tab.key, buf)
                                    buf.dirty = false
                                }
                            }
                        }
                        doClose()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            } else {
                doClose()
            }
        }
    )
    container.addView(bar)
}

/**
 * Full-screen live preview: WebView updates as you type (debounced).
 * Console lines go to the existing editor console buffer.
 */
internal fun MainActivity.showEditorLivePreview() {
    val work = editorBox ?: return
    if (editorMode !in listOf("html", "css", "js")) {
        toast("Live Preview hanya untuk HTML / CSS / JS")
        return
    }
    editorBottomBar.visibility = View.GONE
    EditorLivePreview.detach()

    topBarVisibility(false)
    content.removeAllViews()
    content.setPadding(dp(0), dp(0), dp(0), dp(0))

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -1)
    }
    val toolbar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setBackgroundColor(if (isDarkTheme) panel else android.graphics.Color.WHITE)
    }
    toolbar.addView(TextView(this).apply {
        text = "← Editor"
        textSize = 14f
        setTextColor(textMain)
        setPadding(dp(8), dp(6), dp(12), dp(6))
        isClickable = true
        setOnClickListener {
            destroyEditorWebViews()
            EditorLivePreview.detach()
            EditorLivePreview.stopBufferPolling()
            editorLivePreviewWeb = null
            editor(editorFile, editorMode)
        }
    })
    toolbar.addView(TextView(this).apply {
        text = "Live Preview"
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    })
    toolbar.addView(TextView(this).apply {
        text = "Console"
        textSize = 13f
        setTextColor(textMuted)
        setPadding(dp(10), dp(6), dp(8), dp(6))
        isClickable = true
        setOnClickListener { showEditorConsole() }
    })
    root.addView(toolbar)

    val web = android.webkit.WebView(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        setBackgroundColor(android.graphics.Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }
    editorLivePreviewWeb = web
    root.addView(web)
    content.addView(root, LinearLayout.LayoutParams(-1, -1))

    web.webChromeClient = object : android.webkit.WebChromeClient() {
        override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
            if (msg == null) return true
            val level = when (msg.messageLevel()) {
                android.webkit.ConsoleMessage.MessageLevel.ERROR -> "ERR"
                android.webkit.ConsoleMessage.MessageLevel.WARNING -> "WARN"
                else -> "LOG"
            }
            appendEditorConsole("[$level] ${msg.message()}  (${msg.sourceId()}:${msg.lineNumber()})")
            return true
        }
    }
    web.webViewClient = object : android.webkit.WebViewClient() {
        override fun onRenderProcessGone(
            view: android.webkit.WebView?,
            detail: android.webkit.RenderProcessGoneDetail?
        ): Boolean {
            val didCrash = if (android.os.Build.VERSION.SDK_INT >= 26) {
                detail?.didCrash() == true
            } else true
            appendEditorConsole(
                if (didCrash) "ERROR: Live Preview renderer crash"
                else "WARNING: Live Preview renderer dihentikan sistem"
            )
            EditorLivePreview.stopBufferPolling()
            EditorLivePreview.detach()
            runOnUiThread {
                if (isFinishing || isDestroyed || currentPage != "Editor") return@runOnUiThread
                val parent = view?.parent as? android.view.ViewGroup
                parent?.removeView(view)
                runCatching { view?.destroy() }
                if (editorLivePreviewWeb === view) editorLivePreviewWeb = null
                toast(
                    if (didCrash) "Live Preview dihentikan (JS/renderer crash). Kembali ke Editor."
                    else "Live Preview dihentikan sistem."
                )
                editor(editorFile, editorMode)
            }
            return true
        }
    }
    try {
        EditorLivePreview.refreshNow(this, web)
        EditorLivePreview.startBufferPolling(this, web, 900L)
    } catch (e: Exception) {
        appendEditorConsole("ERROR: Live Preview gagal: ${e.message}")
        toast("Live Preview gagal: ${e.message}")
    }
    toast("Live Preview: refresh otomatis dari buffer project")
}


/** Pause WebViews while Activity is backgrounded; prevents JS/render work during pickers or transient stops. */
internal fun MainActivity.pauseEditorWebViews() {
    runCatching {
        fun walk(v: android.view.View?) {
            if (v == null) return
            if (v is android.webkit.WebView) { v.onPause(); return }
            if (v is android.view.ViewGroup) {
                val kids = (0 until v.childCount).map { v.getChildAt(it) }
                kids.forEach(::walk)
            }
        }
        walk(content)
    }
}

/** Resume WebViews after Activity returns to foreground. */
internal fun MainActivity.resumeEditorWebViews() {
    runCatching {
        fun walk(v: android.view.View?) {
            if (v == null) return
            if (v is android.webkit.WebView) { v.onResume(); return }
            if (v is android.view.ViewGroup) {
                val kids = (0 until v.childCount).map { v.getChildAt(it) }
                kids.forEach(::walk)
            }
        }
        walk(content)
    }
}

/** Destroy every WebView under content (and tracked live preview) to avoid leaks / zombie renderers. */
internal fun MainActivity.destroyEditorWebViews() {
    runCatching {
        editorLivePreviewWeb?.let { w ->
            runCatching {
                w.stopLoading()
                (w.parent as? android.view.ViewGroup)?.removeView(w)
                w.destroy()
            }
            editorLivePreviewWeb = null
        }
        fun walk(v: android.view.View?) {
            if (v == null) return
            if (v is android.webkit.WebView) {
                runCatching {
                    v.stopLoading()
                    (v.parent as? android.view.ViewGroup)?.removeView(v)
                    v.destroy()
                }
                return
            }
            if (v is android.view.ViewGroup) {
                // Copy children list — destroy mutates hierarchy
                val kids = (0 until v.childCount).map { v.getChildAt(it) }
                kids.forEach { walk(it) }
            }
        }
        walk(content)
    }
}
