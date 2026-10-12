package com.example.aidetest

import java.text.SimpleDateFormat
import java.util.Date
import android.webkit.MimeTypeMap
import java.io.InterruptedIOException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.io.DataOutputStream
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
import android.text.InputType
import android.os.Environment
import android.os.StatFs
import android.widget.ProgressBar
import android.widget.HorizontalScrollView
import android.widget.Toast
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

/**
 * File tools extracted from MainActivity (Batch 2 modularization).
 * Extension receivers keep openTool() call sites unchanged.
 * State fields remain on MainActivity as internal.
 */

internal val FILE_SORT_LABELS = arrayOf(
    "Folder dulu, lalu nama A–Z", "Nama A–Z", "Nama Z–A", "Tanggal: terbaru", "Ukuran: terbesar",
    "Tanggal: terlama", "Ukuran: terkecil", "Tipe file (A–Z)"
)

internal fun MainActivity.fileManager(dir: File) {
        clearPage("File Manager")
        val filterFn: (File) -> Boolean = { fileFilterText.isBlank() || it.name.contains(fileFilterText, true) }
        val readable = runCatching { dir.canRead() && dir.listFiles() != null }.getOrDefault(false)
        val (rawListed, listTruncated) = ToolPerformance.listFilesCapped(dir, filterFn)
        val files = sortFiles(rawListed).let { if (it.size > ToolPerformance.FILE_LIST_UI_CAP) it.take(ToolPerformance.FILE_LIST_UI_CAP) else it }
        val folders = files.count { it.isDirectory }
        val regular = files.size - folders

        // Jalur navigasi (breadcrumb): ketuk bagian mana pun untuk melompat ke folder itu.
        val rootCanon = runCatching { filesDir.canonicalFile }.getOrElse { filesDir }
        val crumbs = ArrayList<Pair<String, File>>()
        run {
            var cur: File? = runCatching { dir.canonicalFile }.getOrElse { dir }
            val stack = ArrayList<Pair<String, File>>()
            while (cur != null && cur.path.startsWith(rootCanon.path) && cur != rootCanon.parentFile) {
                stack.add((if (cur == rootCanon) "Data aplikasi" else cur.name) to cur)
                if (cur == rootCanon) break
                cur = cur.parentFile
            }
            crumbs.addAll(stack.reversed())
        }
        val crumbRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(10), 0) }
        crumbs.forEachIndexed { i, (name, f) ->
            val last = i == crumbs.lastIndex
            if (i > 0) crumbRow.addView(TextView(this).apply { text = "›"; textSize = 14f; setTextColor(textMuted); setPadding(dp(4), 0, dp(4), 0) })
            crumbRow.addView(TextView(this).apply {
                text = name
                textSize = 12.5f
                setTypeface(typeface, if (last) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(if (last) textMain else textMuted)
                minHeight = dp(40)
                gravity = Gravity.CENTER_VERTICAL
                if (!last) { isClickable = true; setOnClickListener { fileManager(f) } }
            })
        }
        val crumbScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            background = bg(panel2, 12, line)
            addView(crumbRow)
            post { fullScroll(View.FOCUS_RIGHT) }
        }
        content.addView(crumbScroll, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(4) })
        content.addView(subLabel("Lokasi lengkap: ${dir.absolutePath}", 10f).apply { setPadding(dp(4), 0, dp(4), dp(8)); setTextIsSelectable(true) })

        // Aksi cepat dengan label teks (sebelumnya hanya ikon).
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun quickAction(text: String, icon: String, action: () -> Unit): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = bg(panel2, 12, line); isClickable = true; isFocusable = true; contentDescription = text
            setPadding(dp(2), dp(6), dp(2), dp(6)); setOnClickListener { action() }
            addView(MdiIconView(this@fileManager).apply { setIconName(icon); setIconSize(20f); setTextColor(textMain); layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)) })
            addView(TextView(this@fileManager).apply { this.text = text; textSize = 10f; setTextColor(textMain); gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false })
        }
        fun qlp(last: Boolean = false) = LinearLayout.LayoutParams(0, dp(54), 1f).apply { if (!last) rightMargin = dp(6) }
        quick.addView(quickAction("Folder baru", "folder-plus-outline") {
            val e = edit("nama folder")
            AlertDialog.Builder(this).setTitle("Folder Baru").setMessage("Folder dibuat di dalam:\n${dir.absolutePath}").setView(e)
                .setPositiveButton("Buat") { _, _ ->
                    safeChildFile(dir, e.text.toString())?.let { target ->
                        if (target.exists() || !target.mkdirs()) toast("Folder gagal dibuat (nama sudah ada atau akses ditolak)") else fileManager(dir)
                    } ?: toast("Nama folder tidak valid")
                }.setNegativeButton("Batal", null).show()
        }, qlp())
        quick.addView(quickAction("Urutkan", "sort-variant") { showFileSortDialog(dir) }, qlp())
        quick.addView(quickAction("Pilih banyak", "checkbox-multiple-marked-outline") { showMultiSelectDialog(dir) }, qlp())
        quick.addView(quickAction("Ambil file", "file-import-outline") { pickFileForEditor() }, qlp())
        quick.addView(quickAction("Analisis", "chart-box-outline") { showFolderAnalysis(dir) }, qlp(true))
        content.addView(quick, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val searchBox = edit("Filter nama file / folder").apply { setText(fileFilterText) }
        content.addView(searchBox, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(7) })
        content.addView(button("Terapkan Filter") { fileFilterText = searchBox.text.toString().trim(); fileManager(dir) })
        if (dir != filesDir) content.addView(button("←  Folder sebelumnya") { fileManager(dir.parentFile ?: filesDir) })

        if (!readable) {
            val denied = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(28), dp(20), dp(28)); background = bg(panel2, 18, line) }
            denied.addView(label("Folder tidak bisa dibaca", 16f, true).apply { gravity = Gravity.CENTER })
            denied.addView(subLabel("Android menolak akses ke folder ini.", 11f).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10)) })
            denied.addView(button("Cara memberi izin") { showFileAccessHelp(dir.absolutePath) })
            content.addView(denied)
            return
        }

        if (files.isEmpty()) {
            val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(32), dp(20), dp(32)); background = bg(panel2, 18, line) }
            empty.addView(MdiIconView(this).apply { setIconName("folder-open-outline"); setIconSize(38f); setTextColor(textMuted); layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER } })
            empty.addView(label("Folder kosong", 16f, true).apply { gravity = Gravity.CENTER })
            empty.addView(subLabel(if (fileFilterText.isBlank()) "Belum ada file atau folder di sini." else "Tidak ada item yang cocok dengan filter.", 11f).apply { gravity = Gravity.CENTER })
            content.addView(empty)
            return
        }

        val capNote = if (listTruncated || rawListed.size > ToolPerformance.FILE_LIST_UI_CAP) "  •  ditampilkan max ${ToolPerformance.FILE_LIST_UI_CAP} (filter untuk mempersempit)" else ""
        content.addView(subLabel("$folders folder  •  $regular file$capNote\nUrutan: ${FILE_SORT_LABELS.getOrElse(fileSortMode) { FILE_SORT_LABELS[0] }}  •  ketuk untuk membuka, ⋮ untuk aksi", 11f).apply { setPadding(dp(2), 0, 0, dp(6)) })
        files.forEach { f -> content.addView(fileManagerCard(f, dir)) }
    }

/** Panduan saat Android menolak akses ke file/folder. */
internal fun MainActivity.showFileAccessHelp(path: String) {
        AlertDialog.Builder(this)
            .setTitle("Akses ditolak oleh Android")
            .setMessage(
                "GITLS tidak bisa membaca atau mengubah:\n$path\n\n" +
                "Android hanya mengizinkan aplikasi mengakses foldernya sendiri. Cara memberi izin:\n\n" +
                "1. Tekan \"Ambil file\" lalu pilih file lewat pemilih file Android. Izin otomatis diberikan untuk file yang kamu pilih.\n\n" +
                "2. Atau buka Pengaturan aplikasi › Izin › File dan media (atau Penyimpanan), lalu izinkan.\n\n" +
                "3. File di Download atau Galeri bisa kamu ambil ke GITLS dulu, lalu dikelola di sini."
            )
            .setPositiveButton("Buka pengaturan aplikasi") { _, _ ->
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }.onFailure { toast("Tidak bisa membuka pengaturan") }
            }
            .setNeutralButton("Ambil file") { _, _ -> pickFileForEditor() }
            .setNegativeButton("Tutup", null)
            .show()
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
            runOnUiThread { if (dialog.isShowing) runCatching { dialog.dismiss() } }
            runOnToolUiThread { AlertDialog.Builder(this).setTitle("Analisis Folder").setMessage(result).setPositiveButton("OK", null).show() }
        }
    }

internal fun MainActivity.sortFiles(files: List<File>): List<File> = when (fileSortMode) {
        1 -> files.sortedBy { it.name.lowercase(Locale.getDefault()) }
        2 -> files.sortedByDescending { it.name.lowercase(Locale.getDefault()) }
        3 -> files.sortedByDescending { it.lastModified() }
        4 -> files.sortedByDescending { it.length() }
        5 -> files.sortedBy { it.lastModified() }
        6 -> files.sortedBy { it.length() }
        7 -> files.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.extension.lowercase(Locale.getDefault()) }.thenBy { it.name.lowercase(Locale.getDefault()) })
        else -> files.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.getDefault()) })
    }

internal fun MainActivity.showFileSortDialog(dir: File) {
        val items = FILE_SORT_LABELS
        AlertDialog.Builder(this).setTitle("Urutkan berdasarkan").setSingleChoiceItems(items, fileSortMode) { d, which -> fileSortMode = which; d.dismiss(); fileManager(dir) }.show()
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
            if (target == null) {
                toast("Nama tidak valid")
            } else if (target.exists()) {
                toast("Nama \"${target.name}\" sudah dipakai di folder ini")
            } else if (!file.renameTo(target)) {
                showFileAccessHelp(file.absolutePath)
            } else {
                // Keep the editor/tab/buffer state aligned with File Manager rename.
                // Without this, an open editor could continue saving to the old path.
                migrateEditorPath(file, target)
                recordRecentFile(target)
                fileManager(parent)
            }
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
        val labels = files.map { f -> (if (f.isDirectory) "📁 " else "") + f.name + "  •  " + (if (f.isDirectory) "folder" else bytesText(f.length())) }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Pilih item di ${dir.name.ifBlank { "folder ini" }}")
            .setMultiChoiceItems(labels, checked) { _, which, value -> checked[which] = value }
            .setNegativeButton("Batal", null)
            .setNeutralButton("Pilih semua", null)
            .setPositiveButton("Lanjut", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val selectAll = !checked.all { it }
                for (i in checked.indices) { checked[i] = selectAll; dialog.listView.setItemChecked(i, selectAll) }
            }
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
            "Salin ke folder lain  (file asli tetap ada)",
            "Pindahkan ke folder lain  (file asli ikut pindah)",
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
        val root = runCatching { filesDir.canonicalFile }.getOrElse { filesDir }
        fun canon(f: File) = runCatching { f.canonicalFile }.getOrElse { f.absoluteFile }
        val selCanon = selected.map { canon(it) }
        val parentCanon = canon(parent)
        val dirs = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            if (dirs.size >= 300 || depth > 6) return
            val c = canon(d)
            if (selCanon.any { c == it || c.path.startsWith(it.path + File.separator) }) return
            if (c != parentCanon) dirs.add(d)
            d.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase(Locale.getDefault()) }?.forEach { walk(it, depth + 1) }
        }
        walk(filesDir, 0)
        if (dirs.isEmpty()) { toast("Belum ada folder tujuan. Buat folder baru dulu."); return }
        fun labelOf(d: File): String {
            val rel = runCatching { canon(d).relativeTo(root).path }.getOrDefault(d.name)
            return if (rel.isEmpty()) "Data aplikasi  (folder utama)" else "Data aplikasi/$rel"
        }
        val verb = if (move) "Pindahkan" else "Salin"
        AlertDialog.Builder(this)
            .setTitle("$verb ${selected.size} item ke mana?")
            .setItems(dirs.map { labelOf(it) }.toTypedArray()) { _, which ->
                val destination = dirs[which]
                val note = if (move) "File asli akan DIPINDAH: hilang dari folder sekarang dan pindah ke tujuan."
                           else "File asli TETAP ADA di folder sekarang; tujuan mendapat salinan."
                AlertDialog.Builder(this)
                    .setTitle("$verb ke ${labelOf(destination)}?")
                    .setMessage("Dari:\n${parent.absolutePath}\n\nKe:\n${destination.absolutePath}\n\n$note")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton(verb) { _, _ -> batchCopyOrMove(selected, destination, move, parent) }
                    .show()
            }
            .setNegativeButton("Batal", null)
            .show()
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
        val cancelled = AtomicBoolean(false)
        val dialog = ProgressDialog(this).apply {
            setTitle(if (move) "Memindahkan item" else "Menyalin item")
            setMessage("Menghitung file...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = selected.size.coerceAtLeast(1)
            progress = 0
            setCancelable(true)
            setOnCancelListener { cancelled.set(true) }
            setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "Batalkan") { _, _ -> cancelled.set(true) }
            show()
        }
        val operationPage = currentPage
        val operationGeneration = toolUiGeneration
        toolThread {
            // OPEN-05: menghitung isi folder besar dilakukan di worker, bukan di thread UI.
            val total = selected.sumOf { countFilesForOperation(it) }.coerceAtLeast(selected.size)
            runOnUiThread { if (dialog.isShowing) dialog.max = total }
            var done = 0
            var failed = 0
            var skipped = 0
            var stopped = false
            for (source in selected) {
                if (cancelled.get()) { stopped = true; break }
                val sourceCanonical = runCatching { source.canonicalFile }.getOrElse { source.absoluteFile }
                val destinationCanonical = runCatching { destination.canonicalFile }.getOrElse { destination.absoluteFile }
                if (sourceCanonical == destinationCanonical || (source.isDirectory && destinationCanonical.path.startsWith(sourceCanonical.path + File.separator))) {
                    failed++
                    continue
                }
                val target = File(destination, source.name)
                if (runCatching { target.canonicalFile == sourceCanonical }.getOrDefault(false)) {
                    failed++
                    continue
                }
                val policy = resolveConflictPolicy(target, source.name, cancelled, operationPage, operationGeneration)
                if (policy == 0) { skipped++; continue }
                if (policy == 3) { stopped = true; break }
                val actualTarget = if (policy == 2) uniqueDestination(target) else target
                val result = runCatching {
                    copyOrMoveRecursive(source, actualTarget, move, cancelled) { currentName ->
                        done++
                        runOnToolUiThread {
                            if (dialog.isShowing) {
                                dialog.progress = done.coerceAtMost(dialog.max)
                                dialog.setMessage("$done/$total • $currentName")
                            }
                        }
                    }
                }.getOrDefault(false)
                if (result && move) {
                    // The filesystem path changed; migrate any open editor/tab/buffer
                    // state so a later Save cannot resurrect the old path.
                    runCatching { migrateEditorPath(source, actualTarget) }
                }
                if (!result && !cancelled.get()) failed++
            }
            runOnUiThread { if (dialog.isShowing) runCatching { dialog.dismiss() } }
            runOnToolUiThread {
                val status = when {
                    cancelled.get() || stopped -> "Dibatalkan: $done/$total"
                    failed > 0 -> "Selesai: $done/$total • gagal $failed • dilewati $skipped (cek izin & ruang penyimpanan)"
                    skipped > 0 -> "Selesai: $done/$total • dilewati $skipped"
                    else -> "Selesai: $done/$total"
                }
                toast(status)
                fileManager(parent)
            }
        }
    }

internal fun MainActivity.resolveConflictPolicy(
    target: File,
    name: String,
    cancelled: AtomicBoolean,
    expectedPage: String,
    expectedGeneration: Long
): Int {
        if (!target.exists()) return 1
        val lock = Object()
        var result = 0
        var decided = false
        // OPEN-05: WAJIB runOnUiThread biasa. runOnToolUiThread membuang aksi bila halaman
        // berganti, sehingga dialog tidak pernah tampil dan worker menunggu selamanya.
        // Pemeriksaan halaman basi dilakukan sendiri di bawah dan selalu memberi keputusan.
        runOnUiThread {
            val stale = isFinishing || isDestroyed ||
                expectedPage != currentPage || expectedGeneration != toolUiGeneration
            if (stale || cancelled.get()) {
                synchronized(lock) {
                    result = 3
                    decided = true
                    lock.notifyAll()
                }
                return@runOnUiThread
            }
            AlertDialog.Builder(this)
                .setTitle("File sudah ada")
                .setMessage(name)
                .setItems(arrayOf("Lewati", "Ganti", "Buat nama baru", "Batalkan operasi")) { _, which ->
                    synchronized(lock) {
                        result = when (which) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 3 }
                        decided = true
                        lock.notifyAll()
                    }
                }
                .setOnCancelListener {
                    synchronized(lock) {
                        result = 3
                        decided = true
                        lock.notifyAll()
                    }
                }
                .setOnDismissListener {
                    synchronized(lock) {
                        if (!decided) {
                            result = 3
                            decided = true
                            lock.notifyAll()
                        }
                    }
                }
                .show()
        }
        synchronized(lock) {
            while (!decided && !cancelled.get()) lock.wait(250)
            if (cancelled.get() && !decided) { result = 3; decided = true }
        }
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
        // Destructive move is deliberately two-phase: copy the complete source tree first,
        // then remove the original only after every child was copied successfully.
        // This prevents a cancelled/failed move from leaving the source half-deleted.
        if (cancelled.get() || !source.exists()) return false
        val sourceCanonical = runCatching { source.canonicalFile }.getOrElse { source.absoluteFile }
        val targetCanonical = runCatching { target.canonicalFile }.getOrElse { target.absoluteFile }
        if (sourceCanonical == targetCanonical || (source.isDirectory && targetCanonical.path.startsWith(sourceCanonical.path + File.separator))) return false

        // Replacing an existing destination is transactional: preserve the old item
        // until the new copy is completely verified. Never delete the user's old data
        // merely because a later copy operation failed.
        val backup = if (target.exists()) File(target.parentFile, ".${target.name}.gitls-backup-${java.util.UUID.randomUUID()}") else null
        if (backup != null && !target.renameTo(backup)) return false
        val copied = runCatching { copyTreeForMove(source, target, cancelled, onFile) }.getOrDefault(false)
        if (!copied || cancelled.get()) {
            runCatching { if (target.exists()) target.deleteRecursively() }
            if (backup != null && backup.exists()) runCatching { backup.renameTo(target) }
            return false
        }
        if (move) {
            // Salinan di tujuan sudah lengkap: JANGAN dihapus walau sebagian sumber gagal dihapus
            // (deleteRecursively bisa sudah menghapus sebagian sumber — tujuan adalah satu-satunya salinan utuh).
            runCatching { source.deleteRecursively() }
        }
        if (backup != null && backup.exists()) runCatching { backup.deleteRecursively() }
        return true
    }

private fun MainActivity.copyTreeForMove(source: File, target: File, cancelled: AtomicBoolean, onFile: (String) -> Unit): Boolean {
        if (cancelled.get()) return false
        if (source.isDirectory) {
            if (target.exists() && target.isFile) return false
            if (!target.exists() && !target.mkdirs()) return false
            val children = source.listFiles() ?: emptyArray()
            for (child in children) {
                if (!copyTreeForMove(child, File(target, child.name), cancelled, onFile)) return false
            }
            onFile(source.name)
            return true
        }
        target.parentFile?.mkdirs()
        if (target.exists()) return false
        FileInputStream(source).use { input -> FileOutputStream(target).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (!cancelled.get()) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
            }
            output.fd.sync()
        }}
        if (cancelled.get()) { target.delete(); return false }
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
                    if (target == null || target.exists() || !f.renameTo(target)) failed++ else {
                        // Batch rename must migrate an open editor exactly like single-file rename.
                        runCatching { migrateEditorPath(f, target) }
                        recordRecentFile(target)
                        used.add(target.name)
                    }
                    n++
                }
                toast(if (failed == 0) "${used.size} item berhasil diganti nama" else "Berhasil ${used.size}, gagal $failed")
                fileManager(parent)
            }.show()
    }

internal fun MainActivity.confirmBatchDelete(selected: List<File>, parent: File) {
        val names = selected.take(8).joinToString("\n") { (if (it.isDirectory) "📁 " else "• ") + it.name }
        val more = if (selected.size > 8) "\n… dan ${selected.size - 8} item lainnya" else ""
        val hasDir = selected.any { it.isDirectory }
        AlertDialog.Builder(this).setTitle("Hapus ${selected.size} item?")
            .setMessage("Lokasi:\n${parent.absolutePath}\n\nItem yang dihapus:\n$names$more" +
                (if (hasDir) "\n\nFolder yang dipilih beserta SEMUA isinya ikut dihapus." else "") +
                "\n\nPenghapusan tidak dapat dibatalkan.")
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
        // OPEN-05: ukuran folder dihitung di worker.
        toolThread("Menghitung ukuran…") {
            val size = files.sumOf { if (it.isFile) it.length() else directorySize(it) }
            runOnToolUiThread {
                AlertDialog.Builder(this).setTitle("Detail pilihan")
                    .setMessage("Item: $count\nFolder: $folders\nUkuran: ${bytesText(size)}\nLokasi: ${files.firstOrNull()?.parent ?: "-"}")
                    .setPositiveButton("OK", null).show()
            }
        }
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
            setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "Batalkan") { _, _ -> cancelled.set(true) }
            show()
        }
        toolThread {
            var done = 0
            var failed = 0
            for (f in files) {
                if (cancelled.get()) break
                val ok = runCatching { deleteRecursivelySafe(f) }.getOrDefault(false)
                if (ok) runCatching { invalidateEditorForDeletedPath(f) }
                if (!ok && f.exists()) failed++
                done++
                val current = f.name
                runOnToolUiThread {
                    if (dialog.isShowing) {
                        dialog.progress = done
                        dialog.setMessage("$done/${files.size} • $current")
                    }
                }
            }
            val wasCancelled = cancelled.get()
            runOnUiThread { if (dialog.isShowing) runCatching { dialog.dismiss() } }
            runOnToolUiThread {
                val status = when {
                    wasCancelled -> "Dibatalkan: $done/${files.size}"
                    failed > 0 -> "Selesai: $done/${files.size} • gagal $failed (cek izin akses)"
                    else -> "Selesai: $done/${files.size}"
                }
                toast(status)
                fileManager(parent)
            }
        }
    }

internal fun MainActivity.collectFilesForOperation(src: File): List<File> =
        if (src.isFile) listOf(src) else ToolPerformance.walkFilesCapped(src, ToolPerformance.FILE_WALK_CAP)

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
            setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "Batalkan") { _, _ -> cancelled.set(true) }
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
        // Simpan juga waktu terakhir dibuka (path -> epoch ms).
        val times = runCatching { JSONObject(prefs.getString("recent_files_times", "{}") ?: "{}") }.getOrElse { JSONObject() }
        times.put(file.absolutePath, System.currentTimeMillis())
        val pruned = JSONObject()
        next.forEach { path -> if (times.has(path)) pruned.put(path, times.getLong(path)) }
        prefs.edit().putString("recent_files", next.joinToString("\n")).putString("recent_files_times", pruned.toString()).apply()
    }

/** Waktu terakhir file dibuka lewat GITLS, atau null bila tidak tercatat. */
internal fun MainActivity.recentOpenedAt(file: File): Long? = runCatching {
        JSONObject(prefs.getString("recent_files_times", "{}") ?: "{}").optLong(file.absolutePath, 0L).takeIf { it > 0L }
    }.getOrNull()

/** Menghapus satu entri dari RIWAYAT saja. File aslinya tidak disentuh. */
internal fun MainActivity.removeRecentEntry(file: File) {
        val old = prefs.getString("recent_files", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        val times = runCatching { JSONObject(prefs.getString("recent_files_times", "{}") ?: "{}") }.getOrElse { JSONObject() }
        times.remove(file.absolutePath)
        prefs.edit().putString("recent_files", old.filter { it != file.absolutePath }.joinToString("\n")).putString("recent_files_times", times.toString()).apply()
    }

internal fun MainActivity.recentFilesTool() {
        clearPage("Recent Files")

        // Sumber riwayat dijelaskan di awal supaya tidak disangka "semua file terbaru di HP".
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = bg(panel2, 14, line)
        }
        info.addView(label("Dari mana daftar ini?", 13f, true))
        info.addView(subLabel("Hanya file yang kamu buka, edit, atau ubah namanya lewat GITLS (File Manager dan Editor), maksimal 20 file. Ini BUKAN semua file terbaru di HP.", 11.5f).apply { setPadding(0, dp(3), 0, 0) })
        info.addView(subLabel("Menghapus dari daftar ini hanya membersihkan riwayat. File aslinya tetap ada.", 11.5f).apply { setPadding(0, dp(3), 0, 0) })
        content.addView(info, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val rawPaths = prefs.getString("recent_files", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        val paths = rawPaths
            .map { File(it) }
            .filter { it.exists() && it.isFile }
            .distinctBy { it.absolutePath }
        val missing = rawPaths.distinct().size - paths.size

        // Bersihkan entri yang sudah tidak ada agar daftar tidak berisi file mati.
        val validPaths = paths.map { it.absolutePath }
        if (validPaths.isEmpty()) {
            prefs.edit().remove("recent_files").remove("recent_files_times").apply()
        } else {
            prefs.edit().putString("recent_files", validPaths.joinToString("\n")).apply()
        }

        if (paths.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(20), dp(32), dp(20), dp(32)); background = bg(panel2, 18, line)
            }
            empty.addView(MdiIconView(this).apply {
                setIconName("history"); setIconSize(38f); setTextColor(textMuted)
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER }
            })
            empty.addView(label("Belum ada riwayat file", 16f, true).apply { gravity = Gravity.CENTER })
            empty.addView(subLabel(
                (if (missing > 0) "$missing file di riwayat sudah tidak ada dan dibersihkan.\n" else "") +
                "File akan muncul di sini setelah kamu membukanya lewat File Manager atau Editor GITLS.", 11.5f
            ).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10)) })
            empty.addView(button("Buka File Manager") { fileManager(filesDir) })
            content.addView(empty)
            return
        }

        val searchBox = EditText(this).apply {
            hint = "Cari nama file atau lokasi"
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(16), dp(4), dp(16), dp(4))
            background = bg(if (isDarkTheme) panel2 else Color.rgb(246,246,247), 18, Color.rgb(220,220,223))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        content.addView(searchBox, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })

        val stats = subLabel("", 11f)
        content.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        fun render(filter: String = "") {
            list.removeAllViews()
            val q = filter.trim()
            val shown = paths.filter { q.isBlank() || it.name.contains(q, true) || it.absolutePath.contains(q, true) }
            stats.text = (if (q.isBlank()) "${paths.size} file di riwayat" else "${shown.size} dari ${paths.size} file cocok") +
                (if (missing > 0) "  •  $missing file yang sudah hilang dibersihkan" else "") + "  •  terbaru di atas"
            if (shown.isEmpty()) {
                list.addView(subLabel("Tidak ada file yang cocok dengan \"$q\". Coba kata lain.", 13f))
                return
            }
            shown.forEach { f -> list.addView(recentFileCard(f)) }
        }
        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString() ?: "") }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val clearButton = button("Hapus semua riwayat") {
            AlertDialog.Builder(this)
                .setTitle("Hapus semua riwayat?")
                .setMessage("${paths.size} entri akan dihapus dari daftar ini.\n\nYang dihapus hanya RIWAYAT. Semua file aslinya tetap ada di tempatnya.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus riwayat") { _, _ -> prefs.edit().remove("recent_files").remove("recent_files_times").apply(); recentFilesTool() }
                .show()
        }
        styleAsSecondary(clearButton)
        content.addView(clearButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        render()
    }

internal fun MainActivity.folderSize(f: File): Long {
        if (!f.exists()) return 0L
        if (f.isFile) return f.length()
        var total = 0L
        f.listFiles()?.forEach { total += folderSize(it) }
        return total
    }

internal data class FsHit(
    val name: String, val path: String, val size: Long, val modified: Long,
    val file: File?, val uri: Uri?, val parentUri: Uri?
)

internal val fileSearchDayValues = intArrayOf(0, 1, 7, 30, 365)
internal val fileSearchDayLabels = arrayOf("Kapan saja", "24 jam terakhir", "7 hari terakhir", "30 hari terakhir", "1 tahun terakhir")

internal fun MainActivity.fileSearchLocationLabel(): String = when (fileSearchLocation) {
    1 -> "Download (penyimpanan bersama)"
    2 -> fileSearchFolderUri?.let {
        "Folder pilihan: " + (androidx.documentfile.provider.DocumentFile.fromTreeUri(this, it)?.name ?: "folder")
    } ?: "Folder pilihan (belum dipilih)"
    else -> "Folder aplikasi (data GITLS)"
}

internal fun MainActivity.fileSearchCopyPath(path: String) {
    runCatching {
        (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("lokasi", path))
    }
    toast("Lokasi disalin: $path")
}

internal fun MainActivity.fileSearchOpenHit(hit: FsHit) {
    val f = hit.file
    val u = hit.uri
    when {
        f != null -> openRecentFile(f)
        u != null -> {
            val ext = hit.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            runCatching {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(u, mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Buka dengan"))
            }.onFailure { toast("Tidak ada aplikasi untuk membuka file ini") }
        }
    }
}

internal fun MainActivity.fileSearchOpenFolder(hit: FsHit) {
    val f = hit.file
    val pu = hit.parentUri
    if (f != null) {
        val parent = f.parentFile
        val inApp = parent != null && runCatching { parent.canonicalPath.startsWith(filesDir.canonicalPath) }.getOrDefault(false)
        if (parent != null && inApp) fileManager(parent) else fileSearchCopyPath(f.parent ?: hit.path)
    } else if (pu != null) {
        val ok = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(pu, "vnd.android.document/directory"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }); true
        }.getOrDefault(false)
        if (!ok) fileSearchCopyPath(hit.path.substringBeforeLast('/', hit.path))
    } else fileSearchCopyPath(hit.path.substringBeforeLast('/', hit.path))
}

internal fun MainActivity.fileSearchHitCard(hit: FsHit): View {
    val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(10))
        background = bg(panel2, Ds.RADIUS_MD, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
    }
    card.addView(label(hit.name, 13f, true))
    card.addView(subLabel(hit.path, 11f))
    card.addView(subLabel("${bytesText(hit.size)} • ${if (hit.modified > 0) fmt.format(Date(hit.modified)) else "tanggal tidak diketahui"}", 11f))
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val open = button("Buka") { fileSearchOpenHit(hit) }
    val folder = button("Buka folder") { fileSearchOpenFolder(hit) }
    styleAsSecondary(folder)
    row.addView(open, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(folder, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
    card.addView(row)
    return card
}

internal fun MainActivity.fileSearchTool() {
    clearPage("File Search")
    toolWorkspace("File Search", "Cari file berdasarkan NAMA (bukan isi file) di lokasi yang kamu pilih.", "file-search")
    toolWorkspaceSection("LOKASI", "Hanya lokasi di bawah ini yang diperiksa, bukan seluruh penyimpanan HP.")
    val locStatus = toolStatus(fileSearchLocationLabel(), true)
    content.addView(locStatus)
    val locRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val bApp = button("Folder aplikasi") { fileSearchLocation = 0; locStatus.text = fileSearchLocationLabel() }
    val bDl = button("Download") { fileSearchLocation = 1; locStatus.text = fileSearchLocationLabel() }
    val bPick = button("Pilih folder") {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, FILE_SEARCH_FOLDER_PICK_REQUEST)
    }
    styleAsSecondary(bDl); styleAsSecondary(bPick)
    locRow.addView(bApp, LinearLayout.LayoutParams(0, -2, 1f))
    locRow.addView(bDl, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
    locRow.addView(bPick, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
    content.addView(locRow)
    content.addView(subLabel("Jika hasil kosong, pilih folder secara manual; akses Download bisa dibatasi Android.", 11f))

    toolWorkspaceSection("FILTER", "Semua filter bersifat opsional, tetapi isi nama atau ekstensi.")
    val q = edit("Nama mengandung… (contoh: config)").apply { setText(fileSearchQuery) }
    val ext = edit("Ekstensi: apk, zip, json, kt").apply { setText(fileSearchExt) }
    content.addView(q); content.addView(ext)
    val dec = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    val minE = edit("Ukuran min (MB)").apply { inputType = dec; setText(fileSearchMinMb) }
    val maxE = edit("Ukuran maks (MB)").apply { inputType = dec; setText(fileSearchMaxMb) }
    val sizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    sizeRow.addView(minE, LinearLayout.LayoutParams(0, -2, 1f))
    sizeRow.addView(maxE, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
    content.addView(sizeRow)
    val dateBtn = button("Diubah: ${fileSearchDayLabels[fileSearchDayIndex]}") {}
    styleAsSecondary(dateBtn)
    dateBtn.setOnClickListener {
        AlertDialog.Builder(this).setTitle("Terakhir diubah")
            .setSingleChoiceItems(fileSearchDayLabels, fileSearchDayIndex) { d, which ->
                fileSearchDayIndex = which
                dateBtn.text = "Diubah: ${fileSearchDayLabels[which]}"
                d.dismiss()
            }.setNegativeButton("Batal", null).show()
    }
    content.addView(dateBtn)

    val status = toolStatus("Siap mencari", false)
    val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    pinToolAction(button("Cari") {
        val term = q.text.toString().trim().lowercase(Locale.getDefault())
        val exts = ext.text.toString().split(',', ' ', ';').map { it.trim().trimStart('.').lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        val minMb = minE.text.toString().trim().replace(',', '.').toDoubleOrNull()
        val maxMb = maxE.text.toString().trim().replace(',', '.').toDoubleOrNull()
        fileSearchQuery = q.text.toString(); fileSearchExt = ext.text.toString()
        fileSearchMinMb = minE.text.toString(); fileSearchMaxMb = maxE.text.toString()
        if (term.isEmpty() && exts.isEmpty()) { toast("Isi nama file atau ekstensi"); return@button }
        if (minMb != null && maxMb != null && minMb > maxMb) { toast("Ukuran min lebih besar dari maks"); return@button }
        if (fileSearchLocation == 2 && fileSearchFolderUri == null) { toast("Pilih folder dulu"); return@button }
        val minBytes = minMb?.let { (it * 1024 * 1024).toLong() } ?: 0L
        val maxBytes = maxMb?.let { (it * 1024 * 1024).toLong() } ?: Long.MAX_VALUE
        val days = fileSearchDayValues[fileSearchDayIndex]
        val cutoff = if (days > 0) System.currentTimeMillis() - days * 86_400_000L else 0L
        val cap = ToolPerformance.SEARCH_RESULT_CAP
        val loc = fileSearchLocation
        val tree = fileSearchFolderUri
        status.text = "Mencari…"
        results.removeAllViews()
        toolThread("Mencari file…") {
            val hits = ArrayList<FsHit>()
            val checked = ArrayList<String>()
            val unreadable = ArrayList<String>()
            var visited = 0
            var capped = false
            fun accept(name: String, size: Long, modified: Long): Boolean {
                if (term.isNotEmpty() && !name.lowercase(Locale.getDefault()).contains(term)) return false
                if (exts.isNotEmpty() && name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in exts) return false
                if (size < minBytes || size > maxBytes) return false
                if (cutoff > 0 && modified < cutoff) return false
                return true
            }
            fun walkFile(dir: File) {
                val list = runCatching { dir.listFiles() }.getOrNull() ?: return
                for (f in list) {
                    if (hits.size >= cap) { capped = true; return }
                    if (visited >= 200_000) { capped = true; return }
                    if (f.isDirectory) walkFile(f)
                    else {
                        visited++
                        if (accept(f.name, f.length(), f.lastModified())) hits.add(FsHit(f.name, f.absolutePath, f.length(), f.lastModified(), f, null, null))
                    }
                }
            }
            fun walkTree(d: androidx.documentfile.provider.DocumentFile, prefix: String) {
                for (c in d.listFiles()) {
                    if (hits.size >= cap) { capped = true; return }
                    if (visited >= 200_000) { capped = true; return }
                    val n = c.name ?: continue
                    val rel = if (prefix.isEmpty()) n else "$prefix/$n"
                    if (c.isDirectory) walkTree(c, rel)
                    else {
                        visited++
                        val len = c.length(); val mod = c.lastModified()
                        if (accept(n, len, mod)) hits.add(FsHit(n, rel, len, mod, null, c.uri, c.parentFile?.uri))
                    }
                }
            }
            val failure = runCatching {
                when (loc) {
                    2 -> {
                        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, tree!!)
                        checked += "Folder pilihan: ${root?.name ?: "folder"}"
                        if (root == null) unreadable += "Folder pilihan" else walkTree(root, "")
                    }
                    1 -> {
                        val dl = File(Environment.getExternalStorageDirectory().path, "Download")
                        checked += dl.absolutePath
                        if (!dl.exists() || dl.listFiles() == null) unreadable += dl.absolutePath else walkFile(dl)
                    }
                    else -> listOfNotNull(filesDir, getExternalFilesDir(null)).distinctBy { it.absolutePath }.forEach { r ->
                        checked += r.absolutePath
                        if (r.listFiles() == null) unreadable += r.absolutePath else walkFile(r)
                    }
                }
                Unit
            }.exceptionOrNull()
            val sorted = hits.sortedByDescending { it.modified }
            runOnToolUiThread {
                status.text = "${sorted.size} hasil" + (if (capped) " (dibatasi)" else "") + " • $visited file diperiksa"
                if (failure != null) results.addView(subLabel("Sebagian lokasi tidak bisa dibaca: ${failure.message ?: "akses ditolak"}", 11f))
                if (capped) results.addView(subLabel("Hasil/pemindaian dibatasi (maks $cap hasil, 200.000 file). Persempit filter atau pilih folder lebih kecil.", 11f))
                if (sorted.isEmpty()) {
                    val filters = buildList {
                        if (term.isNotEmpty()) add("nama mengandung \"$term\"")
                        if (exts.isNotEmpty()) add("ekstensi ${exts.joinToString(", ") { ".$it" }}")
                        if (minMb != null) add("min $minMb MB")
                        if (maxMb != null) add("maks $maxMb MB")
                        if (days > 0) add(fileSearchDayLabels[fileSearchDayIndex].lowercase(Locale.getDefault()))
                    }
                    results.addView(infoCard("Tidak ada file yang cocok",
                        "Lokasi yang diperiksa:\n" + checked.joinToString("\n") { "• $it" } +
                            (if (unreadable.isNotEmpty()) "\n\nTidak bisa dibaca:\n" + unreadable.joinToString("\n") { "• $it" } else "") +
                            "\n\nFilter: ${filters.joinToString("; ")}\n$visited file diperiksa. Pencarian hanya berdasarkan nama file, bukan isinya. File di lokasi lain tidak ikut dicari — ganti lokasi atau pilih folder."))
                } else {
                    results.addView(label("${sorted.size} file ditemukan", 15f, true).apply { setPadding(0, dp(10), 0, dp(6)) })
                    sorted.forEach { results.addView(fileSearchHitCard(it)) }
                }
            }
        }
    })
    content.addView(status)
    content.addView(results)
}

internal fun MainActivity.fileStudioTool(){ studioHub("File Studio","Kelola, cari dan analisis file.",listOf("File Manager" to "filemanager","File Search" to "filesearch","Duplicate Finder" to "duplicatefinder","ZIP / UNZIP" to "zip","File Converter" to "fileconvert","Checksum File" to "checksum","Storage Analyzer" to "storage")) }

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
            runOnToolUiThread {
                exportBusy = false
                if (isFinishing) return@runOnToolUiThread
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
            runOnToolUiThread { output(result) }
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
            action.text = "?"; action.textSize = 20f; action.setOnClickListener { showToolHelpSheet("Konversi File") }
            back.setOnClickListener { navigateBack() }
            renderConvHome()
            return
        }
        back.setOnClickListener { convGoBackStage() }
        when (convStage) {
            "pickfile" -> {
                title.text = "Pilih File"
                action.text = "?"; action.textSize = 20f; action.isClickable = true; action.setOnClickListener { convShowSupportDialog(cat) }
                renderConvPickFile(cat)
            }
            "progress" -> {
                title.text = cat.title
                action.text = "?"; action.textSize = 20f; action.isClickable = true; action.setOnClickListener { convShowSupportDialog(cat) }
                renderConvProgress(cat)
            }
            "done" -> {
                title.text = "Selesai"
                action.text = "?"; action.textSize = 20f; action.setOnClickListener { showToolHelpSheet("Konversi File") }
                renderConvDone(cat)
            }
            else -> {
                title.text = cat.title
                action.text = "?"; action.textSize = 20f; action.isClickable = true; action.setOnClickListener { convShowSupportDialog(cat) }
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
            val q = query.trim().lowercase(Locale.getDefault())
            // OPEN-06: kategori tanpa satu pun target yang berfungsi tidak ditampilkan sebagai
            // konverter. Audio & Video diarahkan ke tool yang benar-benar bisa mengonversi.
            val filtered = convCategories.filter { convSupportedFormats(it).isNotEmpty() }
                .filter { q.isEmpty() || it.title.lowercase(Locale.getDefault()).contains(q) || it.desc.lowercase(Locale.getDefault()).contains(q) }
            val shortcuts = convShortcutCategories().filter { (t, d, _) ->
                q.isEmpty() || t.lowercase(Locale.getDefault()).contains(q) || d.lowercase(Locale.getDefault()).contains(q)
            }
            if (filtered.isEmpty() && shortcuts.isEmpty()) listBox.addView(subLabel("Tidak ada kategori yang cocok.", 13f))
            filtered.forEach { c ->
                listBox.addView(convCategoryCard(c).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } })
            }
            if (shortcuts.isNotEmpty()) {
                listBox.addView(subLabel("Dikerjakan tool lain", 11f).apply { setPadding(dp(2), dp(6), dp(2), dp(6)) })
                shortcuts.forEach { (t, d, id) ->
                    listBox.addView(settingRowClickable(t, d, iconFor(id)) { openTool(id) })
                }
            }
            if (q.isEmpty()) listBox.addView(subLabel("Belum didukung: konversi dokumen (PDF/DOCX/XLSX), MP3, RAR/7Z.", 10.5f).apply { setPadding(dp(2), dp(10), dp(2), 0) })
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
        addView(TextView(hostActivity).apply {
            text = c.icon; textSize = 20f; gravity = Gravity.CENTER; setTextColor(textMain)
            background = bg(if (isDarkTheme) panel2 else Color.rgb(235, 236, 239), 12)
        }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(14) })
        addView(LinearLayout(hostActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(c.title, 15f, true).apply { setPadding(dp(2), 0, dp(2), dp(1)) })
            addView(subLabel(c.desc, 11f))
            addView(subLabel(convAvailabilityLabel(c), 10.5f))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(hostActivity).apply { text = "›"; textSize = 22f; setTextColor(textMuted) })
    }

internal fun MainActivity.renderConvForm(cat: ConvCategory) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = bg(panel2, 16)
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(66)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) }
            addView(TextView(hostActivity).apply { text = cat.icon; textSize = 26f; setTextColor(textMuted) })
        })
        content.addView(subLabel(cat.formDesc, 12f).apply { gravity = Gravity.CENTER; setPadding(dp(2), 0, dp(2), dp(4)) })
        content.addView(subLabel(convExampleFor(cat), 11f).apply { gravity = Gravity.CENTER; setPadding(dp(2), 0, dp(2), dp(4)) })
        content.addView(subLabel(convAvailabilityLabel(cat), 11f).apply { gravity = Gravity.CENTER; setPadding(dp(2), 0, dp(2), dp(16)) })

        content.addView(label("Pilih file", 12f, true).apply { setPadding(dp(2), 0, dp(2), dp(6)) })
        content.addView(convFileBox(cat))

        content.addView(subLabel("Format Asal", 11f).apply { setPadding(dp(2), dp(14), dp(2), dp(6)) })
        content.addView(convStaticRow(convFromFormat))

        content.addView(subLabel("Ubah Ke", 11f).apply { setPadding(dp(2), dp(14), dp(2), dp(6)) })
        content.addView(convToDropdownRow(cat))
        if (convToExpanded) content.addView(convToOptionsBox(cat))

        val chosen = convToFormat
        val chosenOk = chosen != null && convTargetSupport(cat, chosen, convPickedName) == null
        if (convPickedUri != null && chosen != null && chosenOk) content.addView(convPlanCard(cat))
        if (chosen != null && !chosenOk) {
            content.addView(subLabel(convTargetSupport(cat, chosen, convPickedName) ?: "", 11f).apply { setPadding(dp(2), 0, dp(2), dp(8)) })
        }
        val canStart = convPickedUri != null && chosenOk
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
            addView(TextView(hostActivity).apply { text = "✓"; textSize = 22f; setTextColor(textMain); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
            addView(label(convPickedName ?: "File terpilih", 13f, true).apply { gravity = Gravity.CENTER })
        } else {
            addView(TextView(hostActivity).apply { text = "▤"; textSize = 26f; setTextColor(textMuted); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
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
        addView(TextView(hostActivity).apply {
            text = if (convToExpanded) "⌃" else "⌄"; textSize = 13f; setTextColor(textMuted)
        })
    }

internal fun MainActivity.convToOptionsBox(cat: ConvCategory): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = bg(panel2, 14, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
        cat.formats.forEachIndexed { i, fmt ->
            val reason = convTargetSupport(cat, fmt, convPickedName)
            addView(LinearLayout(hostActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                if (reason == null) {
                    isClickable = true
                    setOnClickListener { convToFormat = fmt; convToExpanded = false; renderConv() }
                } else {
                    isClickable = false
                    alpha = 0.45f
                }
                addView(LinearLayout(hostActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(fmt, 14f))
                    if (reason != null) addView(subLabel(reason, 10.5f))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(hostActivity).apply {
                    text = if (reason != null) "—" else if (convToFormat == fmt) "●" else "○"
                    textSize = 14f; setTextColor(if (convToFormat == fmt && reason == null) textMain else textMuted)
                })
            })
            if (i != cat.formats.lastIndex) addView(View(hostActivity).apply {
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
                addView(TextView(hostActivity).apply { text = icon; textSize = 16f; setTextColor(textMuted) }, LinearLayout.LayoutParams(dp(28), -2))
                addView(LinearLayout(hostActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(name, 14f, true).apply { setPadding(dp(4), 0, dp(4), 0) })
                    addView(subLabel(sub, 11f).apply { setPadding(dp(4), 0, dp(4), 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(hostActivity).apply { text = "›"; textSize = 20f; setTextColor(textMuted) })
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
                addView(TextView(hostActivity).apply {
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
            addView(TextView(hostActivity).apply { text = "▤"; textSize = 18f; setTextColor(textMuted) }, LinearLayout.LayoutParams(dp(30), -2))
            addView(LinearLayout(hostActivity).apply {
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
        else -> format.lowercase(Locale.getDefault()).replace(" ", "").replace("(", "").replace(")", "")
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
        // Foto kamera sering menyimpan orientasi di EXIF: putar agar hasil konversi tidak miring.
        return VeAssets.applyExif(this, uri, bitmap)
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
        convTargetSupport(cat, targetFormat, srcName)?.let { reason ->
            toast("Tidak bisa mengonversi: $reason")
            return
        }
        convStage = "progress"
        convStepIndex = 0
        renderConv()

        toolThread {
            var tempFile: File? = null
            try {
                runOnToolUiThread { convStepIndex = 1; renderConv() }

                val baseName = srcName.substringBeforeLast('.', srcName).ifBlank { "hasil" }
                val ext = convExtensionFor(targetFormat)
                val outFile = File(convOutputDir(), "${baseName}_converted_${System.currentTimeMillis()}.$ext")
                tempFile = File(outFile.parentFile, ".${outFile.name}.tmp")
                tempFile?.let { if (it.exists()) it.delete() }

                runOnToolUiThread { convStepIndex = 2; renderConv() }

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
                            error("Konversi $sourceExt → ${targetFormat.lowercase(Locale.ROOT)} belum didukung oleh encoder aplikasi")
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

                runOnToolUiThread { convStepIndex = 3; renderConv() }
                Thread.sleep(200)

                val finalFile = outFile
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", finalFile)
                convResultUri = uri
                convResultName = finalFile.name
                convResultSizeText = convFormatSize(finalFile.length())
                runOnToolUiThread {
                    convStage = "done"
                    renderConv()
                }
            } catch (oom: OutOfMemoryError) {
                tempFile?.delete()
                System.gc()
                runOnToolUiThread {
                    toast("File terlalu besar untuk diproses di perangkat ini")
                    convStage = "form"
                    renderConv()
                }
            } catch (e: Exception) {
                tempFile?.delete()
                runOnToolUiThread {
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
    name.substringAfterLast('.', "").trim().lowercase(Locale.ROOT)


// =====================================================================
// File Converter: dukungan format nyata, contoh, perkiraan ukuran
// =====================================================================
internal val convImageSourceExts = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")

private fun convNormalizeExt(ext: String): String = when (ext.trim().lowercase(Locale.ROOT)) {
    "jpeg" -> "jpg"
    else -> ext.trim().lowercase(Locale.ROOT)
}

/** null = format tujuan didukung; selain itu berisi alasan singkat yang tampil di daftar. */
internal fun MainActivity.convTargetSupport(cat: ConvCategory, fmt: String, sourceName: String?): String? {
    val src = convNormalizeExt(sourceName?.let { convSourceExtension(it) }.orEmpty())
    return when (cat.id) {
        "gambar" -> when {
            fmt !in listOf("PNG", "JPG", "WEBP", "BMP") -> "Belum didukung"
            src.isEmpty() -> null
            convImageSourceExts.none { convNormalizeExt(it) == src } -> "Sumber bukan gambar yang didukung"
            src == convNormalizeExt(fmt) -> "Sama dengan format asal"
            else -> null
        }
        "arsip" -> when {
            fmt == "GZ" -> if (src == "gz") "File sudah berformat GZ" else null
            fmt == "Folder Normal (Extract)" -> "Belum didukung — pakai ZIP / UNZIP"
            else -> "Belum didukung"
        }
        else -> "Belum didukung"
    }
}

/** OPEN-06: kategori yang konversinya ada di tool lain (judul, keterangan, id tool). */
internal fun convShortcutCategories(): List<Triple<String, String, String>> = listOf(
    Triple("Audio → WAV / M4A", "Converter & Batch: audio ke WAV atau M4A (AAC)", "batchconvert"),
    Triple("Video → Audio", "Ambil suara AAC dari video menjadi M4A", "videotoaudio"),
    Triple("ZIP / Ekstrak", "Membuat dan mengekstrak arsip ZIP", "zip")
)

internal fun MainActivity.convSupportedFormats(cat: ConvCategory): List<String> =
    cat.formats.filter { convTargetSupport(cat, it, null) == null }

internal fun MainActivity.convAvailabilityLabel(cat: ConvCategory): String {
    val ok = convSupportedFormats(cat)
    return when {
        ok.isEmpty() -> "Belum tersedia di aplikasi"
        ok.size == cat.formats.size -> "Siap: ${ok.joinToString(", ")}"
        else -> "Tersedia: ${ok.joinToString(", ")} saja"
    }
}

internal fun MainActivity.convExampleFor(cat: ConvCategory): String = when (cat.id) {
    "gambar" -> "Contoh: PNG → JPG, JPG → WEBP, WEBP → PNG"
    "arsip" -> "Contoh: LOG → GZ (kompres satu file). ZIP: pakai tool ZIP."
    else -> "Kategori ini belum punya konverter di aplikasi."
}

internal fun MainActivity.convShowSupportDialog(cat: ConvCategory) {
    val ok = convSupportedFormats(cat)
    val msg = buildString {
        appendLine(cat.formDesc)
        appendLine()
        if (ok.isEmpty()) {
            appendLine("Format tujuan yang bisa dipakai: belum ada.")
            appendLine("Format yang direncanakan: ${cat.formats.joinToString(", ")}.")
        } else {
            appendLine("Format tujuan yang bisa dipakai: ${ok.joinToString(", ")}.")
            val off = cat.formats - ok.toSet()
            if (off.isNotEmpty()) appendLine("Belum didukung: ${off.joinToString(", ")}.")
        }
        appendLine()
        appendLine(convExampleFor(cat))
        if (cat.id == "gambar") { appendLine(); append("Gambar yang lebih besar dari 2048 px diperkecil agar memori HP aman. Transparansi PNG menjadi latar putih saat ke JPG/BMP.") }
    }
    AlertDialog.Builder(this).setTitle("Format yang didukung").setMessage(msg).setPositiveButton("Mengerti", null).show()
}

internal fun MainActivity.convQuerySize(uri: Uri): Long = runCatching {
    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
    } ?: -1L
}.getOrDefault(-1L)

internal fun MainActivity.convOutputLocationText(): String =
    convOutputDir().absolutePath.removePrefix("/storage/emulated/0/").removePrefix("/sdcard/")

internal fun MainActivity.convEstimateText(cat: ConvCategory, target: String, srcName: String?, uri: Uri, srcSize: Long): String? {
    val src = convNormalizeExt(srcName?.let { convSourceExtension(it) }.orEmpty())
    return when {
        cat.id == "gambar" && target == "BMP" -> runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching null
            val sample = convImageSampleSize(uri)
            val w = opts.outWidth / sample; val h = opts.outHeight / sample
            val rowSize = ((24 * w + 31) / 32) * 4
            "± ${convFormatSize(54L + rowSize.toLong() * h)} (BMP tidak dikompres)"
        }.getOrNull()
        cat.id == "gambar" && target == "PNG" -> "Bisa lebih besar dari file asal"
        cat.id == "gambar" && (target == "JPG" || target == "WEBP") ->
            if (src in setOf("png", "bmp", "gif")) "Biasanya lebih kecil dari file asal" else "Mirip ukuran file asal"
        cat.id == "arsip" && target == "GZ" -> "Teks/log mengecil banyak; file yang sudah terkompresi (JPG, MP4, ZIP) hampir tidak berubah"
        else -> null
    }
}

internal fun MainActivity.convPlanCard(cat: ConvCategory): LinearLayout {
    val base = (convPickedName ?: "file").substringBeforeLast('.', convPickedName ?: "file").ifBlank { "hasil" }
    val target = convToFormat ?: ""
    val ext = convExtensionFor(target)
    val uri = convPickedUri
    val srcSize = if (uri != null) convQuerySize(uri) else -1L
    val est = if (uri != null) convEstimateText(cat, target, convPickedName, uri, srcSize) else null
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = bg(panel2, 14, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
        addView(label("Hasil konversi", 12f, true))
        addView(subLabel("Nama: ${base}_converted_[waktu].$ext", 11f).apply { setPadding(0, dp(4), 0, 0) })
        addView(subLabel("Lokasi: ${convOutputLocationText()}", 11f).apply { setPadding(0, dp(2), 0, 0) })
        if (srcSize >= 0) addView(subLabel("Ukuran asal: ${convFormatSize(srcSize)}", 11f).apply { setPadding(0, dp(2), 0, 0) })
        if (est != null) addView(subLabel("Perkiraan hasil: $est", 11f).apply { setPadding(0, dp(2), 0, 0) })
        addView(subLabel("File asal tidak diubah; hasil disimpan sebagai file baru.", 11f).apply { setPadding(0, dp(2), 0, 0) })
    }
}


/** Video → Audio: ekstrak track audio dari video (MP4/MKV/WebM/3GP) ke file M4A (AAC).
 *  Android tidak menyediakan encoder MP3 bawaan; M4A/AAC adalah format yang didukung native
 *  dan kompatibel luas (bisa diputar di hampir semua pemutar yang mendukung "MP3"). */
internal fun MainActivity.videoToAudioTool() {
    clearPage("Video → Audio")
    title.text = "Video → Audio"
    action.text = "?"
    action.textSize = 20f
    action.isClickable = true
    action.setOnClickListener {
        AlertDialog.Builder(this)
            .setTitle("Bantuan — Video → Audio")
            .setMessage(
                "Ekstrak suara dari video (MP4, 3GP, MOV; MKV/WebM bila suaranya AAC) menjadi file audio M4A (AAC) tanpa encode ulang. Suara Opus/Vorbis/AC3/MP3 belum didukung.\n\n" +
                "• File video asli tidak diubah.\n" +
                "• Hasil disimpan sebagai file baru di Music/GITLS (Android 9 ke bawah: perlu izin penyimpanan, tanpa izin di folder aplikasi).\n" +
                "• Format keluaran: M4A (AAC) — format native Android yang setara praktis dengan MP3 untuk pemutaran.\n" +
                "• Encoder MP3 murni tidak tersedia tanpa library tambahan."
            )
            .setPositiveButton("OK", null)
            .show()
    }
    back.setOnClickListener { navigateBack() }

    var pickedUri: android.net.Uri? = null
    var pickedName: String? = null
    val status = subLabel("Belum ada file dipilih.", 12f)
    val resultBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = bg(panel2, 12, line)
    }

    content.addView(label("Ubah video menjadi audio", 14f, true).apply {
        setPadding(0, 0, 0, dp(4))
    })
    content.addView(subLabel(
        "Pilih file video (MP4/MKV/WebM/dll.), lalu ekstrak track audio ke M4A. " +
        "Hasil bisa diputar seperti MP3 di hampir semua pemutar.",
        12f
    ).apply { setPadding(0, 0, 0, dp(12)) })

    val fileCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = bg(panel2, 14, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }
    fileCard.addView(label("File video", 12f, true))
    val fileName = subLabel("Ketuk untuk memilih…", 12f).apply { setPadding(0, dp(6), 0, 0) }
    fileCard.addView(fileName)
    fileCard.isClickable = true
    fileCard.setOnClickListener {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "video/mp4", "video/x-matroska", "video/webm", "video/3gpp", "video/quicktime"))
        }
        // Reuse existing document picker pattern if available; fallback to startActivityForResult style via ActivityResult if present
        try {
            // Prefer existing helper if any
            val method = this::class.java.methods.firstOrNull { it.name == "pickDocument" || it.name == "openDocumentPicker" }
            if (method != null) {
                // skip - use intent via known pattern
            }
        } catch (_: Exception) {}
        // Use common pattern in this app: store callback on activity
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 9911)
        // Store pending via a simple field pattern used elsewhere - we'll use a Runnable holder
        videoToAudioPending = pending@{ uri ->
            if (uri == null) return@pending
            pickedUri = uri
            val name = displayNameOf(uri) ?: "video.mp4"
            pickedName = name
            fileName.text = name
            setToolState(status, Ds.State.INFO, "Siap diekstrak. Ketuk tombol di bawah.")
            resultBox.visibility = View.GONE
        }
    }
    content.addView(fileCard)
    content.addView(status.apply { setPadding(0, 0, 0, dp(12)) })
    content.addView(resultBox)

    val extractBtn = android.widget.Button(this).apply {
        text = "Ekstrak Audio"
        setOnClickListener {
            val uri = pickedUri
            if (uri == null) {
                toast("Pilih file video dulu")
                return@setOnClickListener
            }
            setToolState(status, Ds.State.LOADING, "Memproses…")
            isEnabled = false
            ensureLegacyStorageAccess() // OPEN-11
            Thread {
                try {
                    val out = extractAudioFromVideo(uri, pickedName ?: "video.mp4")
                    // Salin ke Music/GITLS agar muncul di aplikasi musik & file manager (Android 10+).
                    val published = runCatching { VePublish.copy(this@videoToAudioTool, java.io.File(out.first), deleteSource = true) }.getOrNull()
                    runOnUiThread {
                        isEnabled = true
                        setToolState(status, Ds.State.SUCCESS, "Selesai")
                        resultBox.removeAllViews()
                        resultBox.visibility = View.VISIBLE
                        resultBox.addView(label("Berhasil", 13f, true))
                        resultBox.addView(subLabel("File: ${out.second}", 12f).apply { setPadding(0, dp(4), 0, 0) })
                        resultBox.addView(subLabel("Lokasi: ${published?.label ?: out.first}", 11f).apply { setPadding(0, dp(2), 0, 0) })
                        resultBox.addView(subLabel("Format: M4A (AAC) — bisa diputar seperti audio/MP3", 11f).apply { setPadding(0, dp(2), 0, 0) })
                        val openBtn = android.widget.Button(this@videoToAudioTool).apply {
                            text = "Bagikan hasil"
                            setOnClickListener {
                                try {
                                    val f = java.io.File(out.first)
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        this@videoToAudioTool,
                                        packageName + ".fileprovider",
                                        f
                                    )
                                    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "audio/mp4"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    startActivity(android.content.Intent.createChooser(share, "Bagikan audio"))
                                } catch (e: Exception) {
                                    // Fallback: salin path
                                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("audio", out.first))
                                    toast("Path disalin: ${out.first}")
                                }
                            }
                        }
                        resultBox.addView(openBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                        toast("Audio berhasil diekstrak")
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        isEnabled = true
                        setToolState(status, Ds.State.ERROR, "Gagal: ${e.message}")
                        toast("Gagal ekstrak: ${e.message}")
                    }
                }
            }.start()
        }
    }
    content.addView(extractBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    content.addView(subLabel(
        "Catatan: hanya track audio yang disalin (tanpa re-encode). Video tanpa audio akan gagal.",
        11f
    ).apply { setPadding(0, dp(10), 0, 0) })
}

/** Pending callback untuk document picker Video→Audio (requestCode 9911). */
@Volatile
internal var videoToAudioPending: ((android.net.Uri?) -> Unit)? = null

/**
 * Ekstrak track audio dari [uri] video ke file M4A di cache/output app.
 * @return Pair(absolutePathOrUriString, displayName)
 */
internal fun MainActivity.extractAudioFromVideo(uri: android.net.Uri, sourceName: String): Pair<String, String> {
    val extractor = android.media.MediaExtractor()
    try {
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            extractor.setDataSource(pfd.fileDescriptor)
        } ?: extractor.setDataSource(this, uri, null)
    } catch (e: Exception) {
        extractor.release()
        throw IllegalStateException("Tidak bisa membuka video: ${e.message}")
    }

    var audioTrack = -1
    var format: android.media.MediaFormat? = null
    for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) {
            audioTrack = i
            format = f
            break
        }
    }
    if (audioTrack < 0 || format == null) {
        extractor.release()
        throw IllegalStateException("Video tidak memiliki track audio")
    }
    extractor.selectTrack(audioTrack)

    val base = sourceName.substringBeforeLast('.').ifBlank { "audio" }
        .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
        .take(48)
    val outName = "${base}_audio_${System.currentTimeMillis()}.m4a"
    val outDir = getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
        ?: filesDir
    if (!outDir.exists()) outDir.mkdirs()
    val outFile = java.io.File(outDir, outName)

    // Salin tanpa encode ulang hanya bisa untuk AAC; Opus/Vorbis/AC3/MP3 tidak diterima kontainer M4A.
    val audioMime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
    if (audioMime != "audio/mp4a-latm") {
        extractor.release()
        throw IllegalStateException("Suara video ini berformat ${audioMime.removePrefix("audio/").uppercase()} — hanya suara AAC yang bisa disalin ke M4A tanpa encode ulang.")
    }
    val muxer = android.media.MediaMuxer(outFile.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val outTrack = try {
        muxer.addTrack(format).also { muxer.start() }
    } catch (e: Exception) {
        runCatching { muxer.release() }
        extractor.release()
        outFile.delete()
        throw IllegalStateException("Track audio tidak bisa disalin ke M4A: ${e.message}")
    }

    val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
    val info = android.media.MediaCodec.BufferInfo()
    try {
        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.bufferFlags()
            muxer.writeSampleData(outTrack, buffer, info)
            extractor.advance()
        }
    } finally {
        try { muxer.stop() } catch (_: Exception) {}
        muxer.release()
        extractor.release()
    }
    if (!outFile.exists() || outFile.length() == 0L) {
        outFile.delete()
        throw IllegalStateException("Hasil audio kosong")
    }
    return outFile.absolutePath to outName
}
