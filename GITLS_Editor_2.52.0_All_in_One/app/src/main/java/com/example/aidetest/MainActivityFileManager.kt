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


internal fun MainActivity.findFiles(dir: File, term: String, out: MutableList<File>, limit: Int) {
        if (out.size >= limit) return
        dir.listFiles()?.forEach { f ->
            if (out.size >= limit) return
            if (f.name.lowercase(Locale.getDefault()).contains(term)) out.add(f)
            if (f.isDirectory) findFiles(f, term, out, limit)
        }
    }
internal fun MainActivity.recentFileCard(file: File): View {
        val type = recentFileType(file)
        val modified = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(8), dp(12))
            background = bg(if (isDarkTheme) panel2 else Color.rgb(248,248,249), 20, Color.rgb(220,220,223))
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
            background = bg(if (isDarkTheme) panel2 else Color.rgb(238,238,240), 16)
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(12) })

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        box.addView(label(file.name, 15f, false))
        box.addView(subLabel("$type • ${bytesText(file.length())}", 11f).apply { setPadding(0, dp(3), 0, 0) })
        val opened = recentOpenedAt(file)
        val openedText = if (opened != null) {
            "Terakhir dibuka: " + android.text.format.DateUtils.getRelativeTimeSpanString(opened, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS) +
                " (" + SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(opened)) + ")"
        } else "Terakhir diubah: $modified"
        box.addView(subLabel(openedText, 10.5f).apply { setPadding(0, dp(3), 0, 0) })
        box.addView(subLabel("Lokasi: ${file.parent ?: "-"}", 10f).apply { setPadding(0, dp(2), 0, 0) })
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
internal fun MainActivity.recentFileType(file: File): String {
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
internal fun MainActivity.openRecentFile(file: File) {
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
internal fun MainActivity.showRecentFileActions(file: File) {
        val actions = arrayOf("Buka kembali", "Buka folder lokasi", "Bagikan", "Detail", "Hapus dari riwayat (file aman)", "Hapus FILE ASLI…")
        AlertDialog.Builder(this).setTitle(file.name).setItems(actions) { _, which ->
            when (which) {
                0 -> openRecentFile(file)
                1 -> {
                    val parent = file.parentFile
                    val inApp = parent != null && runCatching { parent.canonicalPath.startsWith(filesDir.canonicalPath) }.getOrDefault(false)
                    if (parent != null && inApp) fileManager(parent) else toast("Lokasi: ${file.parent}")
                }
                2 -> shareFile(file)
                3 -> showFileDetail(file)
                4 -> {
                    AlertDialog.Builder(this).setTitle("Hapus dari riwayat?")
                        .setMessage("${file.name}\nLokasi: ${file.parent}\n\nHanya entri di daftar Recent yang dihapus. File aslinya TIDAK dihapus.")
                        .setNegativeButton("Batal", null)
                        .setPositiveButton("Hapus riwayat") { _, _ -> removeRecentEntry(file); toast("Dihapus dari riwayat. File asli tetap ada."); recentFilesTool() }
                        .show()
                }
                5 -> confirmDeleteFile(file, file.parentFile ?: filesDir)
            }
        }.show()
    }
internal fun MainActivity.createAppBackup() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"; putExtra(Intent.EXTRA_TITLE, "mytools_backup_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.zip") }
        startActivityForResult(i, 3025)
    }
internal fun MainActivity.restoreAppBackup() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")); addCategory(Intent.CATEGORY_OPENABLE) }, 3026)
    }
internal fun MainActivity.writeAppBackup(uri: Uri) {
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
internal fun MainActivity.readAppBackup(uri: Uri) {
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
        // Merge restore instead of clearing the entire preference domain. This keeps
        // Bit life state when an older backup predates companion preferences.
        val existingBit = prefs.all.filterKeys { it.startsWith("bit_") }
        val backupHasBit = settings.keys().asSequence().any { it.startsWith("bit_") }
        val editor = prefs.edit()
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
        if (!backupHasBit) {
            existingBit.forEach { (k, v) ->
                when (v) {
                    is Boolean -> editor.putBoolean(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Float -> editor.putFloat(k, v)
                    is String -> editor.putString(k, v)
                    is Set<*> -> editor.putStringSet(k, v.filterIsInstance<String>().toSet())
                }
            }
        }
        if (!editor.commit()) error("Gagal menyimpan hasil restore")
    }
internal fun MainActivity.confirmDeleteFile(file: File, parent: File) {
        val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val openPath = editorFile?.let { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        val isOpenFile = openPath == path
        val isOpenInside = file.isDirectory && openPath?.startsWith(path + File.separator) == true
        val warning = if (isOpenFile || isOpenInside) {
            "\n\nFile ini sedang digunakan editor. Tab/buffer terkait akan ditutup sebelum penghapusan."
        } else ""
        // OPEN-05: hitung isi folder & hapus folder besar di worker (toolThread), bukan di thread UI.
        toolThread("Menghitung isi…", showLoading = file.isDirectory) {
            val kind = if (file.isDirectory) {
                val n = ToolPerformance.walkFilesCapped(file, 5000).size
                "Jenis: folder (berisi ${if (n >= 5000) "5000+" else n.toString()} file)"
            } else "Jenis: file • ${bytesText(file.length())}"
            runOnToolUiThread {
                AlertDialog.Builder(this)
                    .setTitle(if (file.isDirectory) "Hapus folder?" else "Hapus file?")
                    .setMessage("Nama: ${file.name}\nLokasi: ${file.parent ?: "-"}\n" + kind + warning + "\n\nPenghapusan tidak dapat dibatalkan.")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Hapus") { _, _ ->
                        // Flush terakhir sebelum menghapus agar ketikan terbaru tidak tertinggal
                        // sebagai buffer orphan, lalu invalidasi semua state editor terkait.
                        if (isOpenFile || isOpenInside) {
                            runCatching { flushEditorCurrentToBuffer(writeDisk = true) }
                        }
                        toolThread("Menghapus…") {
                            val deleted = runCatching { if (file.isDirectory) file.deleteRecursively() else file.delete() }.getOrDefault(false)
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                if (deleted) runCatching { invalidateEditorForDeletedPath(file) }
                                if (deleted) { toast("Terhapus: ${file.name}"); if (currentPage == "File Manager") fileManager(parent) } else showFileAccessHelp(file.absolutePath)
                            }
                        }
                    }.show()
            }
        }
    }
internal fun MainActivity.pickFileForEditor() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, 1001)
    }
internal fun MainActivity.queryName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex("_display_name")
            if (c.moveToFirst() && idx >= 0) return c.getString(idx)
        }
        return null
    }
