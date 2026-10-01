package com.example.aidetest

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.io.DataOutputStream
import java.util.Date
import java.text.SimpleDateFormat
import android.os.Environment
import android.os.StatFs
import android.text.InputType
import java.io.InterruptedIOException
import org.json.JSONObject
import java.security.MessageDigest
import android.webkit.MimeTypeMap
import android.widget.ProgressBar
import java.nio.charset.StandardCharsets
import android.widget.Toast
import com.example.aidetest.MainActivity.ConvCategory

/**
 * File tools extracted from MainActivity (Batch 2 modularization).
 * Extension receivers keep openTool() call sites unchanged.
 * State fields remain on MainActivity as internal.
 */

internal fun MainActivity.fileManager(dir: File) {
        clearPage("File Manager")
        val files = sortFiles(dir.listFiles()?.filter { fileFilterText.isBlank() || it.name.contains(fileFilterText, true) } ?: emptyList())
        val folders = files.count { it.isDirectory }
        val regular = files.size - folders

        val path = TextView(this).apply {
            text = "⌂  ${dir.absolutePath}"
            textSize = 11f
            setTextColor(textMuted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = bg(panel2, 12, line)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        content.addView(path, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(8) })

        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun quickAction(text: String, icon: String, action: () -> Unit) = MdiIconView(this).apply {
            setIconName(icon); setIconSize(20f); setTextColor(textMain); contentDescription = text
            background = bg(panel2, 12, line); isClickable = true; isFocusable = true
            setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { action() }
        }
        quick.addView(quickAction("Folder baru", "folder-plus-outline") {
            val e = edit("nama folder")
            AlertDialog.Builder(this).setTitle("Folder Baru").setView(e)
                .setPositiveButton("Buat") { _, _ ->
                    safeChildFile(dir, e.text.toString())?.let { target ->
                        if (target.exists() || !target.mkdirs()) toast("Folder gagal dibuat") else fileManager(dir)
                    } ?: toast("Nama folder tidak valid")
                }.setNegativeButton("Batal", null).show()
        }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(6) })
        quick.addView(quickAction("Urutkan", "sort-variant") { showFileSortDialog(dir) }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(6) })
        quick.addView(quickAction("Pilih banyak", "checkbox-multiple-marked-outline") { showMultiSelectDialog(dir) }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(6) })
        quick.addView(quickAction("File Android", "file-import-outline") { pickFileForEditor() }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(10) })
        quick.addView(quickAction("Analisis folder", "chart-box-outline") { showFolderAnalysis(dir) }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(10) })
        val count = TextView(this).apply {
            text = "$folders folder  •  $regular file"
            textSize = 11f; setTextColor(textMuted); gravity = Gravity.CENTER_VERTICAL
        }
        quick.addView(count, LinearLayout.LayoutParams(0, dp(46), 1f))
        content.addView(quick, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) })

        val searchBox = edit("Filter nama file / folder").apply { setText(fileFilterText) }
        content.addView(searchBox, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(7) })
        content.addView(button("Terapkan Filter") { fileFilterText = searchBox.text.toString().trim(); fileManager(dir) })
        if (dir != filesDir) content.addView(button("←  Folder sebelumnya") { fileManager(dir.parentFile ?: filesDir) })

        if (files.isEmpty()) {
            val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(32), dp(20), dp(32)); background = bg(panel2, 18, line) }
            empty.addView(MdiIconView(this).apply { setIconName("folder-open-outline"); setIconSize(38f); setTextColor(textMuted); layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER } })
            empty.addView(label("Folder kosong", 16f, true).apply { gravity = Gravity.CENTER })
            empty.addView(subLabel(if (fileFilterText.isBlank()) "Belum ada file atau folder di sini." else "Tidak ada item yang cocok dengan filter.", 11f).apply { gravity = Gravity.CENTER })
            content.addView(empty)
            return
        }

        content.addView(subLabel("${files.size} item  •  ketuk untuk membuka, tekan ⋮ untuk aksi", 11f))
        files.forEach { f -> content.addView(fileManagerCard(f, dir)) }
    }

internal fun MainActivity.fileManagerCard(f: File, parent: File): View {
        val isDir = f.isDirectory
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(6), dp(8)); background = bg(panel2, 16, line)
            isClickable = true; isFocusable = true; contentDescription = if (isDir) "Folder ${f.name}" else "File ${f.name}"
        }
        val icon = MdiIconView(this).apply {
            setIconName(if (isDir) "folder-outline" else fileIconForExtension(f.extension)); setIconSize(25f); setTextColor(textMain)
            background = bg(panel, 13, line); setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(10) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(label(f.name, 14f, true))
        info.addView(subLabel(if (isDir) "Folder" else "${bytesText(f.length())}  •  ${SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(f.lastModified()))}", 10f))
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        val more = TextView(this).apply { text = "⋮"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(textMuted); contentDescription = "Aksi ${f.name}"; isClickable = true; isFocusable = true; setPadding(dp(8), 0, dp(8), 0); setOnClickListener { showFileActions(f, parent) } }
        card.addView(more, LinearLayout.LayoutParams(dp(42), dp(48)))
        card.setOnClickListener { if (isDir) fileManager(f) else showFileActions(f, parent) }
        return card.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(66)).apply { bottomMargin = dp(7) } }
    }

internal fun MainActivity.fileIconForExtension(ext: String): String = when (ext.lowercase(Locale.getDefault())) {
        "kt", "java", "py", "js", "ts", "html", "css", "json", "xml", "yaml", "yml" -> "code-tags"
        "png", "jpg", "jpeg", "webp", "gif" -> "file-image-outline"
        "mp3", "wav", "ogg" -> "file-music-outline"
        "mp4", "mkv", "webm" -> "file-video-outline"
        "zip", "rar", "7z" -> "zip-box-outline"
        "pdf" -> "file-pdf-box"
        "txt", "md" -> "file-document-outline"
        else -> "file-outline"
    }

internal fun MainActivity.showFolderAnalysis(dir: File) {
        val dialog = ProgressDialog(this).apply { setTitle("Menganalisis folder"); setMessage("Menghitung ukuran dan file terbesar..."); setProgressStyle(ProgressDialog.STYLE_SPINNER); setCancelable(false); show() }
        toolThread {
            val result = runCatching {
                val size = GitlsMaxEngine.folderSize(dir)
                val largest = GitlsMaxEngine.largestFiles(dir, 10)
                buildString {
                    appendLine("Folder: ${dir.absolutePath}")
                    appendLine("Ukuran total: ${bytesText(size)}")
                    appendLine("Item: ${dir.listFiles()?.size ?: 0}")
                    appendLine("\n10 file terbesar:")
                    largest.forEachIndexed { i, stat -> appendLine("${i + 1}. ${stat.file.name} — ${bytesText(stat.size)}") }
                }
            }.getOrElse { "Analisis gagal: ${it.message}" }
            runOnUiThread { if (dialog.isShowing) dialog.dismiss(); AlertDialog.Builder(this).setTitle("Analisis Folder").setMessage(result).setPositiveButton("OK", null).show() }
        }
    }

internal fun MainActivity.sortFiles(files: List<File>): List<File> = when (fileSortMode) {
        1 -> files.sortedBy { it.name.lowercase(Locale.getDefault()) }
        2 -> files.sortedByDescending { it.name.lowercase(Locale.getDefault()) }
        3 -> files.sortedByDescending { it.lastModified() }
        4 -> files.sortedByDescending { it.length() }
        else -> files.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.getDefault()) })
    }

internal fun MainActivity.showFileSortDialog(dir: File) {
        val items = arrayOf("Folder dulu + nama", "Nama A–Z", "Nama Z–A", "Terbaru diubah", "Ukuran terbesar")
        AlertDialog.Builder(this).setTitle("Urutkan file").setSingleChoiceItems(items, fileSortMode) { d, which -> fileSortMode = which; d.dismiss(); fileManager(dir) }.show()
    }

internal fun MainActivity.showFileActions(f: File, parent: File) {
        val actions = if (f.isDirectory) arrayOf("Buka", "Ganti nama", "Bagikan", "Hapus") else arrayOf("Buka Editor", "Ganti nama", "Bagikan", "Hapus", "Detail")
        AlertDialog.Builder(this).setTitle(f.name).setItems(actions) { _, which ->
            when (actions[which]) {
                "Buka" -> fileManager(f)
                "Buka Editor" -> { recordRecentFile(f); editor(f) }
                "Ganti nama" -> renameManagedFile(f, parent)
                "Bagikan" -> shareFile(f)
                "Hapus" -> confirmDeleteFile(f, parent)
                "Detail" -> showFileDetail(f)
            }
        }.show()
    }

internal fun MainActivity.renameManagedFile(file: File, parent: File) {
        val e = edit("Nama baru").apply { setText(file.name) }
        AlertDialog.Builder(this).setTitle("Ganti nama").setView(e).setNegativeButton("Batal", null).setPositiveButton("Simpan") { _, _ ->
            val target = safeChildFile(parent, e.text.toString())
            if (target == null || target.exists() || !file.renameTo(target)) toast("Gagal mengganti nama") else { recordRecentFile(target); fileManager(parent) }
        }.show()
    }

internal fun MainActivity.showFileDetail(file: File) {
        val type = if (file.isDirectory) "Folder" else MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.getDefault())) ?: "File"
        AlertDialog.Builder(this).setTitle(file.name).setMessage("Tipe: $type\nUkuran: ${bytesText(file.length())}\nLokasi: ${file.absolutePath}\nDiubah: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))}").setPositiveButton("OK", null).show()
    }

internal fun MainActivity.showMultiSelectDialog(dir: File) {
        val files = sortFiles(dir.listFiles()?.filter { fileFilterText.isBlank() || it.name.contains(fileFilterText, true) } ?: emptyList())
        if (files.isEmpty()) { toast("Tidak ada item"); return }
        val checked = BooleanArray(files.size)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Pilih banyak item")
            .setMultiChoiceItems(files.map { it.name }.toTypedArray(), checked) { _, which, value -> checked[which] = value }
            .setNegativeButton("Batal", null)
            .setPositiveButton("Lanjut", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = files.indices.filter { checked[it] }.map { files[it] }
                if (selected.isEmpty()) { toast("Belum ada item dipilih"); return@setOnClickListener }
                dialog.dismiss()
                showBatchFileActions(selected, dir)
            }
        }
        dialog.show()
    }

internal fun MainActivity.showBatchFileActions(selected: List<File>, parent: File) {
        val labels = arrayOf(
            "Salin ke folder",
            "Pindahkan ke folder",
            "Ganti nama batch",
            "Hapus semua",
            "Bagikan",
            "Detail"
        )
        AlertDialog.Builder(this)
            .setTitle("${selected.size} item dipilih")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> chooseBatchDestination(selected, parent, false)
                    1 -> chooseBatchDestination(selected, parent, true)
                    2 -> batchRenameDialog(selected, parent)
                    3 -> confirmBatchDelete(selected, parent)
                    4 -> shareMultipleFiles(selected)
                    5 -> showBatchDetail(selected)
                }
            }.show()
    }

internal fun MainActivity.chooseBatchDestination(selected: List<File>, parent: File, move: Boolean) {
        val input = edit("Folder tujuan, contoh: ${parent.absolutePath}").apply { setText(parent.absolutePath) }
        AlertDialog.Builder(this)
            .setTitle(if (move) "Pindahkan ke folder" else "Salin ke folder")
            .setView(input)
            .setMessage("Folder tujuan harus berada di ruang data aplikasi.")
            .setNegativeButton("Batal", null)
            .setPositiveButton(if (move) "Pindahkan" else "Salin") { _, _ ->
                val raw = input.text.toString().trim()
                val destination = resolveManagedDirectory(raw)
                if (destination == null) {
                    toast("Folder tujuan tidak valid")
                } else if (selected.any { destination.canonicalFile == it.canonicalFile || destination.canonicalFile.path.startsWith(it.canonicalFile.path + File.separator) }) {
                    toast("Tujuan tidak boleh berada di dalam item yang dipilih")
                } else {
                    batchCopyOrMove(selected, destination, move, parent)
                }
            }.show()
    }

internal fun MainActivity.resolveManagedDirectory(path: String): File? {
        if (path.isBlank()) return null
        return runCatching {
            val root = filesDir.canonicalFile
            val target = File(path).canonicalFile
            if (target == root || target.path.startsWith(root.path + File.separator)) {
                if (target.exists() && target.isDirectory) target else null
            } else null
        }.getOrNull()
    }

internal fun MainActivity.batchCopyOrMove(selected: List<File>, destination: File, move: Boolean, parent: File) {
        val total = selected.sumOf { countFilesForOperation(it) }.coerceAtLeast(selected.size)
        val cancelled = AtomicBoolean(false)
        val dialog = ProgressDialog(this).apply {
            setTitle(if (move) "Memindahkan item" else "Menyalin item")
            setMessage("Menyiapkan...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = total.coerceAtMost(Int.MAX_VALUE)
            progress = 0
            setCancelable(true)
            setOnCancelListener { cancelled.set(true) }
            show()
        }
        toolThread {
            var done = 0
            var failed = 0
            var skipped = 0
            var stopped = false
            for (source in selected) {
                if (cancelled.get()) { stopped = true; break }
                val target = File(destination, source.name)
                val policy = resolveConflictPolicy(target, source.name, cancelled)
                if (policy == 0) { skipped++; continue }
                if (policy == 3) { stopped = true; break }
                val actualTarget = if (policy == 2) uniqueDestination(target) else target
                val result = runCatching {
                    copyOrMoveRecursive(source, actualTarget, move, cancelled) { currentName ->
                        done++
                        runOnUiThread {
                            if (dialog.isShowing) {
                                dialog.progress = done.coerceAtMost(dialog.max)
                                dialog.setMessage("$done/$total • $currentName")
                            }
                        }
                    }
                }.getOrDefault(false)
                if (!result && !cancelled.get()) failed++
            }
            runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                val status = when {
                    cancelled.get() || stopped -> "Dibatalkan: $done/$total"
                    failed > 0 -> "Selesai: $done/$total • gagal $failed • dilewati $skipped"
                    skipped > 0 -> "Selesai: $done/$total • dilewati $skipped"
                    else -> "Selesai: $done/$total"
                }
                toast(status)
                fileManager(parent)
            }
        }
    }

internal fun MainActivity.resolveConflictPolicy(target: File, name: String, cancelled: AtomicBoolean): Int {
        if (!target.exists()) return 1
        val lock = Object()
        var result = 0
        var decided = false
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("File sudah ada")
                .setMessage(name)
                .setItems(arrayOf("Lewati", "Ganti", "Buat nama baru", "Batalkan operasi")) { _, which ->
                    synchronized(lock) { result = when (which) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 3 }; decided = true; lock.notifyAll() }
                }
                .setOnCancelListener { synchronized(lock) { result = 0; decided = true; lock.notifyAll() } }
                .show()
        }
        synchronized(lock) { while (!decided && !cancelled.get()) lock.wait(250) }
        return result
    }

internal fun MainActivity.uniqueDestination(target: File): File {
        if (!target.exists()) return target
        val base = target.nameWithoutExtension
        val ext = if (target.extension.isBlank()) "" else ".${target.extension}"
        var i = 1
        var candidate: File
        do { candidate = File(target.parentFile, "$base ($i)$ext"); i++ } while (candidate.exists())
        return candidate
    }

internal fun MainActivity.countFilesForOperation(file: File): Int {
        if (file.isFile) return 1
        var count = 1
        file.listFiles()?.forEach { count += countFilesForOperation(it) }
        return count
    }

internal fun MainActivity.copyOrMoveRecursive(source: File, target: File, move: Boolean, cancelled: AtomicBoolean, onFile: (String) -> Unit): Boolean {
        if (cancelled.get()) return false
        if (source.isDirectory) {
            if (target.exists() && target.isFile) return false
            if (!target.exists() && !target.mkdirs()) return false
            source.listFiles()?.forEach { child ->
                if (!copyOrMoveRecursive(child, File(target, child.name), move, cancelled, onFile)) return false
            }
            if (move && !cancelled.get()) source.delete()
            onFile(source.name)
            return true
        }
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        FileInputStream(source).use { input -> FileOutputStream(target).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (!cancelled.get()) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
            }
            output.flush()
        }}
        if (cancelled.get()) { target.delete(); return false }
        if (move) source.delete()
        onFile(source.name)
        return true
    }

internal fun MainActivity.batchRenameDialog(selected: List<File>, parent: File) {
        val prefix = edit("Awalan nama, contoh: Project_")
        val start = edit("Nomor awal, contoh: 1").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("1") }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0); addView(prefix); addView(start) }
        AlertDialog.Builder(this).setTitle("Ganti nama batch").setView(layout)
            .setMessage("Contoh hasil: Project_1, Project_2, Project_3...")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Ganti nama") { _, _ ->
                val p = prefix.text.toString().trim()
                var n = start.text.toString().toIntOrNull() ?: 1
                if (p.isBlank()) { toast("Awalan nama wajib diisi"); return@setPositiveButton }
                val used = mutableSetOf<String>()
                var failed = 0
                selected.forEach { f ->
                    val ext = if (f.isFile && f.extension.isNotBlank()) ".${f.extension}" else ""
                    var target = safeChildFile(parent, "$p$n$ext")
                    while (target != null && target.exists() && target.canonicalFile != f.canonicalFile) { n++; target = safeChildFile(parent, "$p$n$ext") }
                    if (target == null || target.exists() || !f.renameTo(target)) failed++ else used.add(target.name)
                    n++
                }
                toast(if (failed == 0) "${used.size} item berhasil diganti nama" else "Berhasil ${used.size}, gagal $failed")
                fileManager(parent)
            }.show()
    }

internal fun MainActivity.confirmBatchDelete(selected: List<File>, parent: File) {
        AlertDialog.Builder(this).setTitle("Hapus ${selected.size} item?")
            .setMessage("Folder yang dipilih beserta isinya juga akan dihapus. Operasi ini tidak dapat dibatalkan.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ -> batchDeleteFiles(selected, parent) }.show()
    }

internal fun MainActivity.shareMultipleFiles(files: List<File>) {
        if (files.isEmpty()) return
        if (files.size == 1) { shareFile(files.first()); return }
        val uris = ArrayList<Uri>()
        files.forEach { f -> runCatching { uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", f)) } }
        if (uris.isEmpty()) { toast("File tidak dapat dibagikan"); return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Bagikan ${uris.size} file"))
    }

internal fun MainActivity.showBatchDetail(files: List<File>) {
        val count = files.size
        val folders = files.count { it.isDirectory }
        val size = files.sumOf { if (it.isFile) it.length() else directorySize(it) }
        AlertDialog.Builder(this).setTitle("Detail pilihan")
            .setMessage("Item: $count\nFolder: $folders\nUkuran: ${bytesText(size)}\nLokasi: ${files.firstOrNull()?.parent ?: "-"}")
            .setPositiveButton("OK", null).show()
    }

internal fun MainActivity.batchDeleteFiles(files: List<File>, parent: File) {
        val cancelled = AtomicBoolean(false)
        val dialog = ProgressDialog(this).apply {
            setTitle("Menghapus file")
            setMessage("Menyiapkan...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = files.size.coerceAtLeast(1)
            progress = 0
            setCancelable(true)
            setOnCancelListener { cancelled.set(true) }
            show()
        }
        toolThread {
            var done = 0
            var failed = 0
            for (f in files) {
                if (cancelled.get()) break
                val ok = runCatching { deleteRecursivelySafe(f) }.getOrDefault(false)
                if (!ok && f.exists()) failed++
                done++
                val current = f.name
                runOnUiThread {
                    if (dialog.isShowing) {
                        dialog.progress = done
                        dialog.setMessage("$done/${files.size} • $current")
                    }
                }
            }
            val wasCancelled = cancelled.get()
            runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                val status = when {
                    wasCancelled -> "Dibatalkan: $done/${files.size}"
                    failed > 0 -> "Selesai: $done/${files.size} • gagal $failed"
                    else -> "Selesai: $done/${files.size}"
                }
                toast(status)
                fileManager(parent)
            }
        }
    }

internal fun MainActivity.collectFilesForOperation(src: File): List<File> =
        if (src.isFile) listOf(src) else src.walkTopDown().filter { it.isFile }.toList()

internal fun MainActivity.showByteProgressDialog(titleText: String, totalBytes: Long): Pair<ProgressDialog, AtomicBoolean> {
        val cancelled = AtomicBoolean(false)
        val dialog = ProgressDialog(this).apply {
            setTitle(titleText)
            setMessage("Menyiapkan...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 1000
            progress = 0
            setCancelable(true)
            setOnCancelListener { cancelled.set(true) }
            show()
        }
        return dialog to cancelled
    }

internal fun MainActivity.deleteRecursivelySafe(file: File): Boolean {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursivelySafe(it) }
        return file.delete()
    }

internal fun MainActivity.recordRecentFile(file: File) {
        val old = prefs.getString("recent_files", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        val next = (listOf(file.absolutePath) + old.filter { it != file.absolutePath }).take(20)
        prefs.edit().putString("recent_files", next.joinToString("\n")).apply()
    }

internal fun MainActivity.recentFilesTool() {
        clearPage("Recent Files")

        content.addView(subLabel("File yang baru digunakan di GITLS. Ketuk file untuk membuka, atau gunakan menu ⋮ untuk tindakan lain.", 12f))

        val paths = prefs.getString("recent_files", "")
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?.map { File(it) }
            ?.filter { it.exists() && it.isFile }
            ?.distinctBy { it.absolutePath }
            ?: emptyList()

        // Bersihkan entri yang sudah tidak ada agar daftar tidak berisi file mati.
        val validPaths = paths.map { it.absolutePath }
        if (validPaths.isEmpty()) {
            prefs.edit().remove("recent_files").apply()
        } else {
            prefs.edit().putString("recent_files", validPaths.joinToString("\n")).apply()
        }

        val searchBox = EditText(this).apply {
            hint = "Cari file terbaru"
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(16), dp(4), dp(16), dp(4))
            background = bg(Color.rgb(246,246,247), 18, Color.rgb(220,220,223))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        content.addView(searchBox, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) })

        val stats = subLabel("${paths.size} file tersimpan • maksimal 20 file", 11f)
        content.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        fun render(filter: String = "") {
            list.removeAllViews()
            val q = filter.trim()
            val shown = paths.filter { q.isBlank() || it.name.contains(q, true) || it.absolutePath.contains(q, true) }
            if (shown.isEmpty()) {
                list.addView(subLabel(if (paths.isEmpty()) "Belum ada file terbaru." else "File tidak ditemukan.", 13f))
                return
            }
            shown.forEach { f ->
                list.addView(recentFileCard(f))
            }
        }
        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString() ?: "") }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val clearButton = button("Bersihkan Recent Files") {
            if (paths.isEmpty()) { toast("Recent Files sudah kosong"); return@button }
            AlertDialog.Builder(this)
                .setTitle("Bersihkan Recent Files?")
                .setMessage("Daftar shortcut file akan dihapus. File aslinya tidak ikut dihapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Bersihkan") { _, _ -> prefs.edit().remove("recent_files").apply(); recentFilesTool() }
                .show()
        }
        actionRow.addView(clearButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(5) })
        val refreshButton = button("Refresh") { recentFilesTool() }
        actionRow.addView(refreshButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(5) })
        content.addView(actionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        render()
    }

internal fun MainActivity.storageAnalyzerTool() {
        clearPage("Storage Analyzer")
        toolWorkspace("Storage Analyzer", "Pantau penggunaan penyimpanan dan ukuran data MyTools.", "database")
        toolWorkspaceSection("STORAGE", "Ukuran filesystem utama dan folder aplikasi.")
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes
        val free = stat.availableBytes
        val used = total - free
        infoRow("Internal", "${bytesText(used)} digunakan dari ${bytesText(total)} (${if (total > 0) used * 100 / total else 0}%)")
        infoRow("Tersedia", bytesText(free))
        val app = filesDir
        val appSize = GitlsMaxEngine.folderSize(app)
        infoRow("Data MyTools", bytesText(appSize))
        content.addView(button("Hitung ulang") { storageAnalyzerTool() })
    }

internal fun MainActivity.folderSize(f: File): Long {
        if (!f.exists()) return 0L
        if (f.isFile) return f.length()
        var total = 0L
        f.listFiles()?.forEach { total += folderSize(it) }
        return total
    }

internal fun MainActivity.fileSearchTool() {
        clearPage("File Search")
        toolWorkspace("File Search", "Cari file berdasarkan nama di ruang data aplikasi.", "file-search")
        toolWorkspaceSection("SEARCH", "Pencarian dibatasi ke folder data aplikasi agar cepat.")
        val q = edit("contoh: config.json")
        content.addView(q)
        content.addView(button("Cari") {
            val term = q.text.toString().trim().toLowerCase(Locale.getDefault())
            if (term.isEmpty()) { Toast.makeText(this, "Masukkan nama file", Toast.LENGTH_SHORT).show(); return@button }
            val results = mutableListOf<File>()
            findFiles(filesDir, term, results, 200)
            content.addView(label("Hasil: ${results.size}", 14f, true))
            results.forEach { f -> content.addView(button(f.absolutePath) { editor(f) }) }
        })
    }

internal fun MainActivity.zipTool() {
        clearPage("ZIP / UNZIP")
        toolWorkspace("ZIP / UNZIP", "Kompres atau ekstrak file di penyimpanan aplikasi dengan batas aman.", "zip-box")
        toolWorkspaceSection("COMPRESS", "Masukkan nama file atau folder yang akan dibuat ZIP.")
        val src = edit("Nama file/folder di app storage")
        content.addView(src)
        content.addView(button("Buat ZIP") {
            val f = File(filesDir, src.text.toString().trim())
            if (!f.exists()) toast("File tidak ditemukan") else {
                val out = File(filesDir, f.nameWithoutExtension + ".zip")
                val total = collectFilesForOperation(f).sumOf { it.length().coerceAtLeast(0L) }
                val (dialog, cancelled) = showByteProgressDialog("Membuat ZIP", total)
                toolThread {
                    var processed = 0L
                    val result = runCatching {
                        zipPath(f, out) { bytes ->
                            processed += bytes
                            val pct = if (total > 0) ((processed.toDouble() / total) * 1000).toInt().coerceIn(0, 1000) else 0
                            runOnUiThread {
                                if (dialog.isShowing) {
                                    dialog.progress = pct
                                    dialog.setMessage("${(pct / 10)}% • ${bytesText(processed)} / ${bytesText(total)}")
                                }
                            }
                            if (cancelled.get()) throw InterruptedIOException("Operasi dibatalkan")
                        }
                        "ZIP: ${out.absolutePath}"
                    }.getOrElse { "ZIP error: ${it.message}" }
                    runOnUiThread {
                        if (dialog.isShowing) dialog.dismiss()
                        output(result)
                    }
                }
            }
        })
        toolWorkspaceSection("EXTRACT", "Masukkan nama ZIP yang berada di app storage.")
        val zip = edit("Nama .zip")
        content.addView(zip)
        content.addView(button("Ekstrak ZIP") {
            val f = File(filesDir, zip.text.toString().trim())
            if (!f.exists()) toast("ZIP tidak ditemukan") else {
                val dest = File(filesDir, f.nameWithoutExtension).apply { mkdirs() }
                val total = f.length().coerceAtLeast(0L)
                val (dialog, cancelled) = showByteProgressDialog("Mengekstrak ZIP", total)
                toolThread {
                    var processed = 0L
                    val result = runCatching {
                        unzipSafe(f, dest) { bytes ->
                            processed += bytes
                            val pct = if (total > 0) ((processed.toDouble() / total) * 1000).toInt().coerceIn(0, 1000) else 0
                            runOnUiThread {
                                if (dialog.isShowing) {
                                    dialog.progress = pct
                                    dialog.setMessage("${(pct / 10)}% • ${bytesText(processed)} / ${bytesText(total)}")
                                }
                            }
                            if (cancelled.get()) throw InterruptedIOException("Operasi dibatalkan")
                        }
                        "Extracted: ${dest.absolutePath}"
                    }.getOrElse { "Extract error: ${it.message}" }
                    runOnUiThread {
                        if (dialog.isShowing) dialog.dismiss()
                        output(result)
                    }
                }
            }
        })
        content.addView(button("Lihat isi ZIP / Verifikasi") {
            val f = File(filesDir, zip.text.toString().trim())
            if (!f.exists() || !f.isFile) { toast("ZIP tidak ditemukan"); return@button }
            toolThread {
                val result = runCatching {
                    val entries = GitlsMaxEngine.listArchive(f)
                    val total = entries.sumOf { it.size }
                    val shown = entries.take(250).joinToString("\n") { e ->
                        "${if (e.directory) "📁" else "📄"} ${e.name}  •  ${bytesText(e.size)}"
                    }
                    "Entry: ${entries.size}\nUkuran isi: ${bytesText(total)}\nSHA-256: ${GitlsMaxEngine.sha256(f)}\n\n$shown" + if (entries.size > 250) "\n… ${entries.size - 250} entry lainnya" else ""
                }.getOrElse { "Archive error: ${it.message}" }
                runOnUiThread { AlertDialog.Builder(this).setTitle("Archive Inspector").setMessage(result).setPositiveButton("OK", null).show() }
            }
        })
    }

internal fun MainActivity.largeFileFinderTool() {
        clearPage("Large File Finder")
        content.addView(label("Large File Finder", 22f, true))
        content.addView(subLabel("Cari file terbesar pada penyimpanan yang dapat diakses aplikasi.", 12f))
        val min = edit("Batas minimum MB, default 50")
        content.addView(min)
        val out = toolStatus("Siap", false)
        content.addView(out)
        content.addView(button("Scan Large Files") {
            val minBytes = (min.text.toString().toLongOrNull() ?: 50L) * 1024L * 1024L
            out.text = "Memindai…"
            toolThread {
                val top = runCatching { scanRoots().flatMap { GitlsMaxEngine.largestFiles(it, 100) }.distinctBy { it.file.absolutePath }.filter { it.size >= minBytes }.sortedByDescending { it.size }.take(100) }.getOrElse { emptyList() }
                runOnUiThread {
                    out.text = "Selesai • ${top.size} file"
                    content.addView(label("File terbesar", 15f, true))
                    top.forEach { content.addView(infoCard(bytesText(it.size), it.file.absolutePath)) }
                }
            }
        })
    }

internal fun MainActivity.duplicateFinderTool() {
        clearPage("Duplicate Finder")
        content.addView(label("Duplicate Finder", 22f, true))
        content.addView(subLabel("Mencari file duplikat berdasarkan ukuran + SHA-256. Scan berjalan di background.", 12f))
        val min = edit("Ukuran minimum (KB), default 1")
        content.addView(min)
        val out = toolStatus("Siap", false)
        content.addView(out)
        content.addView(button("Scan Duplicate") {
            val minBytes = (min.text.toString().toLongOrNull() ?: 1L) * 1024L
            out.text = "Memindai…"
            toolThread {
                val groups = runCatching { scanRoots().flatMap { GitlsMaxEngine.findDuplicates(it, minBytes) } }.getOrElse { emptyList() }
                runOnUiThread {
                    out.text = "Selesai • ${groups.size} grup"
                    content.addView(label("${groups.size} grup duplikat", 15f, true))
                    groups.take(100).forEach { group ->
                        content.addView(infoCard(bytesText(group.firstOrNull()?.size ?: 0L), group.joinToString("\n") { it.file.absolutePath }))
                    }
                }
            }
        })
    }

internal fun MainActivity.backupRestoreTool() {
        clearPage("Backup / Restore")
        content.addView(label("Backup / Restore", 22f, true))
        content.addView(subLabel("Backup data MyTools ke satu file ZIP lokal. Backup tidak dikirim ke server.", 12f))
        content.addView(button("Buat Backup") { createAppBackup() })
        content.addView(button("Restore Backup") { restoreAppBackup() })
        content.addView(subLabel("Isi: preferences aplikasi, riwayat, recent files, dan data lokal yang aman untuk dipulihkan.", 11f))
    }

internal fun MainActivity.fileStudioTool(){ studioHub("File Studio","Kelola, cari dan analisis file.",listOf("File Manager" to "filemanager","File Search" to "filesearch","Duplicate Finder" to "dedupe","ZIP / UNZIP" to "zip","File Converter" to "fileconvert","Checksum File" to "checksum","Storage Analyzer" to "storage")) }

internal fun MainActivity.workspaceCenterTool() {
        clearPage("Workspace Center")
        val dirs = workspaceRoot().listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: emptyList()
        content.addView(subLabel("Satu tempat untuk project Web, kode, data, dan file kerja.", 11f))

        val create = button("+  Workspace Baru") {
            val name = edit("Nama workspace", false).apply { hint = "contoh: GameProject" }
            AlertDialog.Builder(this).setTitle("Workspace Baru").setView(name)
                .setNegativeButton("Batal", null).setPositiveButton("Buat") { _, _ ->
                    val n = name.text.toString().trim()
                    if (n.isBlank()) { toast("Masukkan nama workspace"); return@setPositiveButton }
                    val safe = n.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().replace(" ", "_")
                    val dir = File(workspaceRoot(), safe)
                    if (!dir.mkdirs() && !dir.isDirectory) { toast("Workspace gagal dibuat"); return@setPositiveButton }
                    File(dir, "workspace.json").writeText(JSONObject().apply { put("name", n); put("createdAt", System.currentTimeMillis()); put("version", 1) }.toString(2), StandardCharsets.UTF_8)
                    prefs.edit().putString("last_workspace", dir.absolutePath).apply(); toast("Workspace dibuat: $safe"); workspaceCenterTool()
                }.show()
        }
        content.addView(create)

        if (dirs.isEmpty()) {
            val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(30), dp(20), dp(30)); background = bg(panel2, 18, line) }
            empty.addView(MdiIconView(this).apply { setIconName("folder-plus-outline"); setIconSize(38f); setTextColor(textMuted); layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER } })
            empty.addView(label("Belum ada workspace", 16f, true).apply { gravity = Gravity.CENTER })
            empty.addView(subLabel("Buat project pertama untuk mulai bekerja.", 11f).apply { gravity = Gravity.CENTER })
            content.addView(empty)
            return
        }

        content.addView(toolSection("PROJECTS", "Workspace terbaru muncul di atas."))
        dirs.forEach { dir -> content.addView(workspaceCard(dir)) }
    }

internal fun MainActivity.safeChildFile(parent: File, name: String): File? {
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('\\') || clean.contains('/') || clean == "." || clean == "..") return null
        val root = filesDir.canonicalFile
        val base = parent.canonicalFile
        if (!base.path.startsWith(root.path + File.separator) && base != root) return null
        val target = File(base, clean).canonicalFile
        return if (target.path.startsWith(root.path + File.separator) || target == root) target else null
    }

internal fun MainActivity.safeFileName(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), "_").trim()
        return cleaned.take(120).ifEmpty { "untitled.txt" }
    }

internal fun MainActivity.scanFiles(roots:List<File>,out:MutableList<File>,limit:Int){for(root in roots){scanFiles(root,out,limit);if(out.size>=limit)return}}

internal fun MainActivity.shareFileAsync(errorPrefix: String, make: () -> java.io.File) {
        if (exportBusy) { toast("Masih memproses, tunggu sebentar..."); return }
        exportBusy = true
        toast("Membuat PDF...")
        Thread {
            val result = runCatching { make() }
            runOnUiThread {
                exportBusy = false
                if (isFinishing) return@runOnUiThread
                result.onSuccess { file -> runCatching { FinanceReport.share(this, file) }.onFailure { toast("$errorPrefix: ${it.message}") } }
                    .onFailure { toast("$errorPrefix: ${it.message}") }
            }
        }.start()
    }

internal fun MainActivity.showFileChecksum(file:File) {
        toolThread {
            val result=runCatching {
                val md5=MessageDigest.getInstance("MD5"); val sha1=MessageDigest.getInstance("SHA-1"); val sha256=MessageDigest.getInstance("SHA-256")
                val buf=ByteArray(8192); file.inputStream().buffered().use { input -> var n=input.read(buf); while(n!=-1){ md5.update(buf,0,n); sha1.update(buf,0,n); sha256.update(buf,0,n); n=input.read(buf) } }
                "MD5 ${md5.digest().joinToString("") { "%02x".format(it) }}\nSHA-1 ${sha1.digest().joinToString("") { "%02x".format(it) }}\nSHA-256 ${sha256.digest().joinToString("") { "%02x".format(it) }}"
            }.getOrElse { "Checksum error: ${it.message}" }
            runOnUiThread { output(result) }
        }
    }

internal fun MainActivity.fileConvertTool() {
        clearPage("Konversi File")
        convCategory = null
        convStage = "form"
        convResetSelection()
        renderConv()
    }

internal fun MainActivity.convResetSelection() {
        convToExpanded = false
        convPickedUri = null
        convPickedName = null
        convFromFormat = "Otomatis terdeteksi"
        convToFormat = null
        convStepIndex = 0
        convResultUri = null
        convResultName = null
        convResultSizeText = null
    }

internal fun MainActivity.convGoBackStage() {
        when (convStage) {
            "pickfile" -> convStage = "form"
            "progress" -> convStage = "form"
            "done" -> { convCategory = null; convStage = "form"; convResetSelection() }
            else -> { convCategory = null; convStage = "form"; convResetSelection() }
        }
        renderConv()
    }

internal fun MainActivity.renderConv() {
        content.removeAllViews()
        val cat = convCategories.find { it.id == convCategory }
        if (cat == null) {
            title.text = "Konversi File"
            action.text = "⋮"; action.textSize = 25f; action.setOnClickListener { showAbout() }
            back.setOnClickListener { navigateBack() }
            renderConvHome()
            return
        }
        back.setOnClickListener { convGoBackStage() }
        when (convStage) {
            "pickfile" -> {
                title.text = "Pilih File"
                action.text = cat.icon; action.textSize = 17f; action.setOnClickListener {}
                renderConvPickFile(cat)
            }
            "progress" -> {
                title.text = cat.title
                action.text = cat.icon; action.textSize = 17f; action.setOnClickListener {}
                renderConvProgress(cat)
            }
            "done" -> {
                title.text = "Selesai"
                action.text = "⋮"; action.textSize = 25f; action.setOnClickListener { showAbout() }
                renderConvDone(cat)
            }
            else -> {
                title.text = cat.title
                action.text = cat.icon; action.textSize = 17f; action.setOnClickListener {}
                renderConvForm(cat)
            }
        }
    }

internal fun MainActivity.renderConvHome() {
        content.addView(label("Konversi File", 22f, true))
        content.addView(subLabel("Pilih kategori konversi yang kamu butuhkan.", 12f).apply { setPadding(dp(2), 0, dp(2), dp(10)) })
        val searchBox = edit("Cari kategori…")
        content.addView(searchBox)
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listBox)
        fun renderList(query: String) {
            listBox.removeAllViews()
            val q = query.trim().toLowerCase(Locale.getDefault())
            val filtered = convCategories.filter { q.isEmpty() || it.title.toLowerCase(Locale.getDefault()).contains(q) || it.desc.toLowerCase(Locale.getDefault()).contains(q) }
            if (filtered.isEmpty()) listBox.addView(subLabel("Tidak ada kategori yang cocok.", 13f))
            filtered.forEach { c ->
                listBox.addView(convCategoryCard(c).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } })
            }
        }
        renderList("")
        searchBox.addTextChangedListener(SimpleTextWatcher { renderList(it) })
    }

internal fun MainActivity.convCategoryCard(c: ConvCategory): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = bg(panel2, 16)
        isClickable = true
        setOnClickListener {
            convCategory = c.id; convStage = "form"; convResetSelection(); renderConv()
        }
        addView(TextView(this@MainActivity).apply {
            text = c.icon; textSize = 20f; gravity = Gravity.CENTER; setTextColor(textMain)
            background = bg(Color.rgb(235, 236, 239), 12)
        }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(14) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(c.title, 15f, true).apply { setPadding(dp(2), 0, dp(2), dp(1)) })
            addView(subLabel(c.desc, 11f))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@MainActivity).apply { text = "›"; textSize = 22f; setTextColor(textMuted) })
    }

internal fun MainActivity.renderConvForm(cat: ConvCategory) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = bg(panel2, 16)
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(66)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) }
            addView(TextView(this@MainActivity).apply { text = cat.icon; textSize = 26f; setTextColor(textMuted) })
        })
        content.addView(subLabel(cat.formDesc, 12f).apply { gravity = Gravity.CENTER; setPadding(dp(2), 0, dp(2), dp(16)) })

        content.addView(label("Pilih file", 12f, true).apply { setPadding(dp(2), 0, dp(2), dp(6)) })
        content.addView(convFileBox(cat))

        content.addView(subLabel("Format Asal", 11f).apply { setPadding(dp(2), dp(14), dp(2), dp(6)) })
        content.addView(convStaticRow(convFromFormat))

        content.addView(subLabel("Ubah Ke", 11f).apply { setPadding(dp(2), dp(14), dp(2), dp(6)) })
        content.addView(convToDropdownRow(cat))
        if (convToExpanded) content.addView(convToOptionsBox(cat))

        val canStart = convPickedUri != null && convToFormat != null
        content.addView(convPrimaryButton("Mulai Konversi", canStart) { startConversion(cat) })
    }

internal fun MainActivity.convFileBox(cat: ConvCategory): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(20), dp(18), dp(20))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(panel2); cornerRadius = dp(14).toFloat(); setStroke(dp(1), line)
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        isClickable = true
        setOnClickListener { convStage = "pickfile"; renderConv() }
        if (convPickedUri != null) {
            addView(TextView(this@MainActivity).apply { text = "✓"; textSize = 22f; setTextColor(textMain); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
            addView(label(convPickedName ?: "File terpilih", 13f, true).apply { gravity = Gravity.CENTER })
        } else {
            addView(TextView(this@MainActivity).apply { text = "▤"; textSize = 26f; setTextColor(textMuted); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
            addView(subLabel("Ketuk untuk memilih file", 13f).apply { gravity = Gravity.CENTER })
            addView(subLabel(cat.hint, 11f).apply { gravity = Gravity.CENTER })
        }
    }

internal fun MainActivity.convStaticRow(text: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = bg(panel2, 14, line)
        addView(label(text, 13f).apply { setTextColor(textMuted) }, LinearLayout.LayoutParams(0, -2, 1f))
    }

internal fun MainActivity.convToDropdownRow(cat: ConvCategory): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = bg(panel2, 14, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = if (convToExpanded) dp(6) else dp(16) }
        isClickable = true
        setOnClickListener { convToExpanded = !convToExpanded; renderConv() }
        addView(label(convToFormat ?: "Pilih format tujuan", 13f).apply {
            setTextColor(if (convToFormat == null) textMuted else textMain)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@MainActivity).apply {
            text = if (convToExpanded) "⌃" else "⌄"; textSize = 13f; setTextColor(textMuted)
        })
    }

internal fun MainActivity.convToOptionsBox(cat: ConvCategory): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = bg(panel2, 14, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
        cat.formats.forEachIndexed { i, fmt ->
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                setOnClickListener { convToFormat = fmt; convToExpanded = false; renderConv() }
                addView(label(fmt, 14f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = if (convToFormat == fmt) "●" else "○"
                    textSize = 14f; setTextColor(if (convToFormat == fmt) textMain else textMuted)
                })
            })
            if (i != cat.formats.lastIndex) addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(10), 0, dp(10), 0) }
                setBackgroundColor(line)
            })
        }
    }

internal fun MainActivity.convPrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(if (enabled) Color.WHITE else textMuted)
        background = bg(if (enabled) Color.rgb(17, 17, 19) else panel2, 14, if (enabled) null else line)
        minHeight = dp(54)
        isEnabled = enabled
        setStateListAnimator(null)
        setOnClickListener { if (enabled) onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(6) }
    }

internal fun MainActivity.renderConvPickFile(cat: ConvCategory) {
        content.addView(label("Pilih File", 20f, true).apply { setPadding(dp(2), 0, dp(2), dp(2)) })
        content.addView(subLabel("Pilih file dari penyimpanan.", 12f).apply { setPadding(dp(2), 0, dp(2), dp(14)) })
        val stat = runCatching {
            val sfs = StatFs(Environment.getExternalStorageDirectory().path)
            val total = sfs.totalBytes; val free = sfs.availableBytes
            "${convFormatSize(total - free)} / ${convFormatSize(total)}"
        }.getOrElse { "" }
        val rows = listOf(
            Triple("▥", "Penyimpanan Internal", stat),
            Triple("▤", "Dokumen", "Ketuk untuk memilih file"),
            Triple("▾", "Download", "Ketuk untuk memilih file"),
            Triple("▧", "Gambar", "Ketuk untuk memilih file"),
            Triple("♪", "Musik", "Ketuk untuk memilih file"),
            Triple("▶", "Video", "Ketuk untuk memilih file")
        )
        rows.forEach { (icon, name, sub) ->
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = bg(panel2, 14)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                isClickable = true
                setOnClickListener { pickConvFile(cat) }
                addView(TextView(this@MainActivity).apply { text = icon; textSize = 16f; setTextColor(textMuted) }, LinearLayout.LayoutParams(dp(28), -2))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(name, 14f, true).apply { setPadding(dp(4), 0, dp(4), 0) })
                    addView(subLabel(sub, 11f).apply { setPadding(dp(4), 0, dp(4), 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@MainActivity).apply { text = "›"; textSize = 20f; setTextColor(textMuted) })
            })
        }
    }

internal fun MainActivity.renderConvProgress(cat: ConvCategory) {
        content.addView(ProgressBar(this).apply {
            isIndeterminate = true
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(24); bottomMargin = dp(16) })
        content.addView(label("Mengonversi…", 16f, true).apply { gravity = Gravity.CENTER })
        content.addView(subLabel("Jangan tutup aplikasi.", 12f).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(18)) })

        val pct = (convStepIndex * 100 / 3).coerceIn(0, 100)
        val barRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        barRow.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = pct
        }, LinearLayout.LayoutParams(0, dp(10), 1f))
        barRow.addView(subLabel("$pct%", 11f).apply { setPadding(dp(8), 0, 0, 0) })
        content.addView(barRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })

        val steps = listOf("Membaca file", "Memproses data", "Menyimpan hasil")
        steps.forEachIndexed { i, s ->
            val active = convStepIndex == i + 1
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(6), dp(2), dp(6))
                addView(TextView(this@MainActivity).apply {
                    text = if (convStepIndex > i) "✓" else if (active) "◍" else "○"
                    textSize = 14f
                    setTextColor(if (convStepIndex > i) textMain else textMuted)
                }, LinearLayout.LayoutParams(dp(24), -2))
                addView(label(s, 13f).apply { setTextColor(if (convStepIndex >= i + 1) textMain else textMuted) })
            })
        }
    }

internal fun MainActivity.renderConvDone(cat: ConvCategory) {
        content.addView(TextView(this).apply {
            text = "✓"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = bg(Color.rgb(17, 17, 19), 40)
        }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(20); bottomMargin = dp(14) })
        content.addView(label("Konversi Berhasil", 18f, true).apply { gravity = Gravity.CENTER })
        content.addView(subLabel("File telah berhasil dikonversi.", 12f).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(20)) })

        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = bg(panel2, 14)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
            addView(TextView(this@MainActivity).apply { text = "▤"; textSize = 18f; setTextColor(textMuted) }, LinearLayout.LayoutParams(dp(30), -2))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(convResultName ?: "hasil", 14f, true).apply { setPadding(dp(4), 0, dp(4), 0) })
                addView(subLabel(convResultSizeText ?: "", 11f).apply { setPadding(dp(4), 0, dp(4), 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })

        content.addView(convPrimaryButton("Buka File", true) {
            val uri = convResultUri ?: return@convPrimaryButton
            val mime = contentResolver.getType(uri) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(convResultName?.substringAfterLast('.', "") ?: "") ?: "*/*"
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }.onFailure { toast("Tidak ada aplikasi untuk membuka file ini") }
        })
        content.addView(button("Bagikan") {
            val uri = convResultUri ?: return@button
            val mime = contentResolver.getType(uri) ?: "*/*"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Bagikan hasil konversi"))
        })
        content.addView(button("Konversi Lagi") {
            convStage = "form"; convResetSelection(); renderConv()
        })
    }

internal fun MainActivity.pickConvFile(cat: ConvCategory) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = cat.mime; addCategory(Intent.CATEGORY_OPENABLE)
        }, 1050)
    }

internal fun MainActivity.convOutputDir(): File = File(getExternalFilesDir(null) ?: filesDir, "conversions").apply { mkdirs() }

internal fun MainActivity.convExtensionFor(format: String): String = when (format) {
        "Folder Normal (Extract)" -> "zip"
        else -> format.toLowerCase(Locale.getDefault()).replace(" ", "").replace("(", "").replace(")", "")
    }

internal fun MainActivity.convFormatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble(); var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

internal fun MainActivity.convCopyStream(uri: Uri, outFile: File): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    total += n
                }
                output.fd.sync()
            }
        } ?: error("Gagal membaca file")
        return total
    }

internal fun MainActivity.convDecodeBitmapSafely(uri: Uri): Bitmap {
        val sample = convImageSampleSize(uri)
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        val bitmap = contentResolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, opts)
        } ?: error("Gagal membaca gambar")
        return bitmap ?: error("Gambar tidak dapat diproses")
    }

internal fun MainActivity.writeBitmapAsBmp(bitmap: Bitmap, outFile: File) {
        val width = bitmap.width
        val height = bitmap.height
        val rowSize = ((24 * width + 31) / 32) * 4
        val pixelDataSize = rowSize * height
        val fileSize = 54 + pixelDataSize
        DataOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { out ->
            fun le16(v: Int) { out.writeByte(v and 0xFF); out.writeByte((v ushr 8) and 0xFF) }
            fun le32(v: Int) {
                out.writeByte(v and 0xFF); out.writeByte((v ushr 8) and 0xFF)
                out.writeByte((v ushr 16) and 0xFF); out.writeByte((v ushr 24) and 0xFF)
            }
            // BITMAPFILEHEADER
            le16(0x4D42); le32(fileSize); le16(0); le16(0); le32(54)
            // BITMAPINFOHEADER
            le32(40); le32(width); le32(height); le16(1); le16(24)
            le32(0); le32(pixelDataSize); le32(2835); le32(2835); le32(0); le32(0)

            val row = ByteArray(rowSize)
            for (y in height - 1 downTo 0) {
                var p = 0
                for (x in 0 until width) {
                    val c = bitmap.getPixel(x, y)
                    val a = Color.alpha(c)
                    val r = if (a == 255) Color.red(c) else (Color.red(c) * a + 255 * (255 - a)) / 255
                    val g = if (a == 255) Color.green(c) else (Color.green(c) * a + 255 * (255 - a)) / 255
                    val b = if (a == 255) Color.blue(c) else (Color.blue(c) * a + 255 * (255 - a)) / 255
                    row[p++] = b.toByte(); row[p++] = g.toByte(); row[p++] = r.toByte()
                }
                while (p < row.size) row[p++] = 0
                out.write(row)
            }
        }
    }

internal fun MainActivity.startConversion(cat: ConvCategory) {
        val srcUri = convPickedUri ?: return
        val srcName = convPickedName ?: "file"
        val targetFormat = convToFormat ?: return
        convStage = "progress"
        convStepIndex = 0
        renderConv()

        toolThread {
            var tempFile: File? = null
            try {
                runOnUiThread { convStepIndex = 1; renderConv() }

                val baseName = srcName.substringBeforeLast('.', srcName).ifBlank { "hasil" }
                val ext = convExtensionFor(targetFormat)
                val outFile = File(convOutputDir(), "${baseName}_converted_${System.currentTimeMillis()}.$ext")
                tempFile = File(outFile.parentFile, ".${outFile.name}.tmp")
                tempFile?.let { if (it.exists()) it.delete() }

                runOnUiThread { convStepIndex = 2; renderConv() }

                when {
                    cat.id == "arsip" && targetFormat == "GZ" -> {
                        val bytesBuffer = ByteArray(64 * 1024)
                        contentResolver.openInputStream(srcUri)?.use { input ->
                            FileOutputStream(tempFile ?: return@toolThread).use { fos ->
                                GZIPOutputStream(BufferedOutputStream(fos)).use { gz ->
                                    while (true) {
                                        val n = input.read(bytesBuffer)
                                        if (n < 0) break
                                        gz.write(bytesBuffer, 0, n)
                                    }
                                }
                            }
                        } ?: error("Gagal membaca file")
                    }

                    cat.id == "gambar" && targetFormat in listOf("PNG", "JPG", "WEBP", "BMP") -> {
                        // Konversi gambar benar-benar melakukan encode ulang, bukan sekadar mengganti ekstensi.
                        // Ukuran gambar dibatasi agar foto besar tidak membuat heap Android penuh.
                        val decoded = convDecodeBitmapSafely(srcUri)
                        var bmp: Bitmap? = decoded
                        try {
                            when (targetFormat) {
                                "BMP" -> writeBitmapAsBmp(decoded, tempFile ?: return@toolThread)
                                else -> {
                                    // JPG tidak mendukung transparansi. Gunakan latar putih supaya PNG transparan
                                    // tidak berubah menjadi area hitam saat dikonversi ke JPG/WEBP lossy.
                                    if (targetFormat == "JPG") {
                                        val rgb = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
                                        Canvas(rgb).apply {
                                            drawColor(Color.WHITE)
                                            drawBitmap(decoded, 0f, 0f, null)
                                        }
                                        bmp = rgb
                                    }
                                    val format = when (targetFormat) {
                                        "PNG" -> Bitmap.CompressFormat.PNG
                                        "JPG" -> Bitmap.CompressFormat.JPEG
                                        else -> if (Build.VERSION.SDK_INT >= 30) {
                                            Bitmap.CompressFormat.WEBP_LOSSY
                                        } else {
                                            @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                                        }
                                    }
                                    FileOutputStream(tempFile ?: return@toolThread).use { fos ->
                                        val ok = bmp?.compress(format, if (targetFormat == "PNG") 100 else 92, fos) == true
                                        if (!ok) error("Gagal menyimpan gambar hasil konversi")
                                    }
                                }
                            }
                        } finally {
                            if (bmp !== decoded) bmp?.recycle()
                            decoded.recycle()
                        }
                    }

                    else -> {
                        // Untuk format yang belum mempunyai encoder native di aplikasi,
                        // jangan mengganti ekstensi file lalu mengklaim berhasil. Salin hanya
                        // jika format sumber dan tujuan memang sama; selain itu tampilkan error
                        // yang aman tanpa membuat aplikasi keluar.
                        val sourceExt = convSourceExtension(srcName)
                        if (sourceExt.isNotEmpty() && sourceExt.equals(ext, ignoreCase = true)) {
                            convCopyStream(srcUri, tempFile ?: return@toolThread)
                        } else {
                            error("Konversi $sourceExt → ${targetFormat.toLowerCase(Locale.ROOT)} belum didukung oleh encoder aplikasi")
                        }
                    }
                }

                val tf = tempFile; if (tf == null || !tf.exists() || tf.length() <= 0L) {
                    error("File hasil kosong")
                }
                if (outFile.exists()) outFile.delete()
                val tf2 = tempFile
                if (tf2 != null && !tf2.renameTo(outFile)) {
                    tf2.copyTo(outFile, overwrite = true)
                    tf2.delete()
                }
                tempFile = null

                runOnUiThread { convStepIndex = 3; renderConv() }
                Thread.sleep(200)

                val finalFile = outFile
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", finalFile)
                convResultUri = uri
                convResultName = finalFile.name
                convResultSizeText = convFormatSize(finalFile.length())
                runOnUiThread {
                    convStage = "done"
                    renderConv()
                }
            } catch (oom: OutOfMemoryError) {
                tempFile?.delete()
                System.gc()
                runOnUiThread {
                    toast("File terlalu besar untuk diproses di perangkat ini")
                    convStage = "form"
                    renderConv()
                }
            } catch (e: Exception) {
                tempFile?.delete()
                runOnUiThread {
                    toast("Konversi gagal: ${e.message ?: "format/file tidak valid"}")
                    convStage = "form"
                    renderConv()
                }
            }
        }
    }


internal fun MainActivity.convImageSampleSize(uri: Uri, maxDimension: Int = 2048): Int {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, opts)
    } ?: error("Gagal membaca gambar")
    if (opts.outWidth <= 0 || opts.outHeight <= 0) error("Gambar tidak valid")
    var sample = 1
    while (opts.outWidth / sample > maxDimension || opts.outHeight / sample > maxDimension) {
        sample *= 2
    }
    return sample
}

internal fun MainActivity.convSourceExtension(name: String): String =
    name.substringAfterLast('.', "").trim().toLowerCase(Locale.ROOT)

