package com.example.aidetest

import android.app.AlertDialog
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.widget.LinearLayout
import android.widget.Space
import java.text.SimpleDateFormat
import java.util.Comparator
import java.util.Date
import java.util.Locale
import java.util.PriorityQueue

/** Satu file hasil pindai folder pilihan. [category] = indeks di [storageCategoryLabels]. */
internal data class StorageScanFile(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val category: Int
)

/** Ringkasan satu kali pemindaian folder pilihan. Hanya mewakili folder itu, bukan seluruh HP. */
internal class StorageScanResult(
    val rootName: String,
    val fileCount: Int,
    val folderCount: Int,
    val totalBytes: Long,
    val largestFiles: List<StorageScanFile>,
    val folderSizes: List<Pair<String, Long>>,
    val categoryBytes: LongArray,
    val categoryCount: IntArray,
    val unreadableFolders: Int,
    val truncated: Boolean,
    val scannedAt: Long,
    val durationMs: Long
)

internal val storageCategoryLabels = arrayOf("Semua", "Gambar", "Video", "Audio", "Dokumen", "Arsip", "APK", "Lainnya")

private val storageImageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "raw", "dng")
private val storageVideoExt = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "flv", "wmv", "m4v")
private val storageAudioExt = setOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "amr", "wma")
private val storageDocExt = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "json", "xml", "html", "rtf", "odt")
private val storageArchiveExt = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "obb")

/** Indeks kategori (1..7). 7 = Lainnya. */
internal fun storageCategoryOf(name: String, mime: String? = null): Int {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        ext == "apk" || ext == "apks" || ext == "xapk" || ext == "aab" -> 6
        ext in storageImageExt -> 1
        ext in storageVideoExt -> 2
        ext in storageAudioExt -> 3
        ext in storageDocExt -> 4
        ext in storageArchiveExt -> 5
        mime != null && mime.startsWith("image/") -> 1
        mime != null && mime.startsWith("video/") -> 2
        mime != null && mime.startsWith("audio/") -> 3
        else -> 7
    }
}

private const val STORAGE_SCAN_FILE_LIMIT = 200_000
private const val STORAGE_KEEP_LARGEST = 2000
private const val STORAGE_LIST_SHOW = 30

/**
 * Memindai folder hasil pilihan pengguna (Storage Access Framework). Dipanggil dari thread latar.
 * Memakai query langsung ke DocumentsContract (satu query per folder) supaya jauh lebih cepat
 * daripada DocumentFile.listFiles().
 */
internal fun MainActivity.scanStorageTree(treeUri: Uri): StorageScanResult {
    val started = System.currentTimeMillis()
    val rootName = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri)?.name ?: "folder"
    val heap = PriorityQueue<StorageScanFile>(256, Comparator { a, b -> a.size.compareTo(b.size) })
    val topFolders = HashMap<String, Long>()
    val catBytes = LongArray(storageCategoryLabels.size)
    val catCount = IntArray(storageCategoryLabels.size)
    var rootFilesBytes = 0L
    var files = 0
    var folders = 0
    var total = 0L
    var unreadable = 0
    var truncated = false

    // Tiap item: (documentId, path relatif, nama folder tingkat pertama atau null untuk root)
    val stack = ArrayDeque<Triple<String, String, String?>>()
    stack.addLast(Triple(DocumentsContract.getTreeDocumentId(treeUri), "", null))
    val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )
    while (stack.isNotEmpty() && !truncated) {
        val (docId, prefix, top) = stack.removeLast()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val cursor = runCatching { contentResolver.query(childrenUri, columns, null, null, null) }.getOrNull()
        if (cursor == null) { unreadable++; continue }
        cursor.use { c ->
            while (c.moveToNext()) {
                if (files >= STORAGE_SCAN_FILE_LIMIT) { truncated = true; break }
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: "?"
                val mime = c.getString(2)
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    folders++
                    stack.addLast(Triple(id, rel, top ?: name))
                } else {
                    val size = if (c.isNull(3)) 0L else c.getLong(3).coerceAtLeast(0L)
                    val modified = if (c.isNull(4)) 0L else c.getLong(4)
                    val cat = storageCategoryOf(name, mime)
                    files++
                    total += size
                    catBytes[cat] += size
                    catCount[cat]++
                    if (top == null) rootFilesBytes += size else topFolders[top] = (topFolders[top] ?: 0L) + size
                    heap.add(StorageScanFile(name, rel, size, modified, cat))
                    if (heap.size > STORAGE_KEEP_LARGEST) heap.poll()
                }
            }
        }
    }
    val folderList = ArrayList<Pair<String, Long>>()
    topFolders.forEach { (k, v) -> folderList.add(k to v) }
    if (rootFilesBytes > 0) folderList.add("(file langsung di folder ini)" to rootFilesBytes)
    folderList.sortByDescending { it.second }
    val now = System.currentTimeMillis()
    return StorageScanResult(
        rootName = rootName,
        fileCount = files,
        folderCount = folders,
        totalBytes = total,
        largestFiles = heap.sortedByDescending { it.size },
        folderSizes = folderList,
        categoryBytes = catBytes,
        categoryCount = catCount,
        unreadableFolders = unreadable,
        truncated = truncated,
        scannedAt = now,
        durationMs = now - started
    )
}

internal fun MainActivity.storageAnalyzerTool() {
    clearPage("Storage Analyzer")
    toolWorkspace(
        "Storage Analyzer",
        "Kapasitas HP, data GITLS, dan isi folder pilihanmu ditampilkan terpisah supaya tidak tertukar.",
        "database"
    )

    // ── 1. Kapasitas perangkat ────────────────────────────────────────────
    toolWorkspaceSection("KAPASITAS PENYIMPANAN INTERNAL", "Angka dari sistem Android untuk seluruh penyimpanan internal.")
    var total = 0L
    var free = 0L
    var fromSystemStats = false
    if (Build.VERSION.SDK_INT >= 26) {
        runCatching {
            val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            total = ssm.getTotalBytes(StorageManager.UUID_DEFAULT)
            free = ssm.getFreeBytes(StorageManager.UUID_DEFAULT)
            fromSystemStats = total > 0
        }
    }
    if (!fromSystemStats) {
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            total = stat.totalBytes
            free = stat.availableBytes
        }
    }
    if (total > 0) {
        val used = (total - free).coerceAtLeast(0L)
        infoRow("Kapasitas total", bytesText(total))
        infoRow("Ruang terpakai", "${bytesText(used)} (${used * 100 / total}%)")
        infoRow("Ruang kosong", bytesText(free))
        content.addView(
            subLabel(
                if (fromSystemStats)
                    "Ruang terpakai mencakup sistem Android, semua aplikasi, dan semua file — bukan hanya data GITLS."
                else
                    "Angka ini dari partisi data saja, jadi bisa lebih kecil dari kapasitas yang tertera di kotak HP. Ruang terpakai mencakup semua aplikasi, bukan hanya GITLS.",
                11f
            ).apply { setPadding(dp(2), 0, 0, dp(10)) }
        )
    } else {
        infoRow("Kapasitas penyimpanan", "Tidak tersedia di perangkat ini")
    }

    // ── 2. Data GITLS ─────────────────────────────────────────────────────
    toolWorkspaceSection("DATA GITLS SAJA", "Hanya data milik aplikasi ini — bukan seluruh HP.")
    val gitlsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(gitlsBox)
    gitlsBox.addView(subLabel("Menghitung…", 12f))
    toolThread("Menghitung data GITLS…", showLoading = false) {
        val internal = runCatching { GitlsMaxEngine.folderSize(filesDir) }.getOrDefault(0L)
        val cache = runCatching { GitlsMaxEngine.folderSize(cacheDir) }.getOrDefault(0L)
        val external = runCatching { getExternalFilesDir(null)?.let { GitlsMaxEngine.folderSize(it) } }.getOrNull()
        runOnToolUiThread {
            gitlsBox.removeAllViews()
            val box = gitlsBox
            fun row(name: String, value: String) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = bg(panel2, 14)
                }
                card.addView(label(name, 12f, true))
                card.addView(label(value, 14f))
                box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
            }
            row("Data aplikasi (file internal)", bytesText(internal))
            row("Cache", bytesText(cache))
            row("File aplikasi di penyimpanan bersama", external?.let { bytesText(it) } ?: "Tidak tersedia")
            row("Total data GITLS", bytesText(internal + cache + (external ?: 0L)))
        }
    }

    // ── 3. Folder pilihan ─────────────────────────────────────────────────
    toolWorkspaceSection("PINDAI FOLDER PILIHAN", "Android hanya mengizinkan aplikasi membaca folder yang kamu pilih sendiri.")
    val picked = storageScanUri
    content.addView(
        toolStatus(
            if (picked != null)
                "Folder dipilih: ${androidx.documentfile.provider.DocumentFile.fromTreeUri(this, picked)?.name ?: "folder"}"
            else "Belum ada folder dipilih",
            picked != null
        )
    )
    content.addView(
        subLabel(
            "Hasil hanya mencakup folder pilihan yang bisa dibaca Android; beberapa folder sistem dibatasi.",
            11f
        ).apply { setPadding(dp(2), 0, 0, dp(8)) }
    )
    val resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val pick = button(if (picked != null) "Ganti folder" else "Pilih folder") {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, STORAGE_ANALYZER_FOLDER_PICK_REQUEST)
    }
    styleAsSecondary(pick)
    content.addView(pick, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    if (picked != null) {
        pinToolAction(button("Pindai folder ini") {
            resultBox.removeAllViews()
            resultBox.addView(subLabel("Memindai… folder besar bisa butuh beberapa saat.", 12f))
            toolThread("Memindai folder…") {
                val res = runCatching { scanStorageTree(picked) }
                runOnToolUiThread {
                    val r = res.getOrNull()
                    if (r == null) {
                        resultBox.removeAllViews()
                        resultBox.addView(infoCard("Pemindaian gagal", res.exceptionOrNull()?.message ?: "Folder tidak bisa dibaca. Coba pilih folder lain."))
                    } else {
                        storageScanResult = r
                        storageFilterCategory = 0
                        storageFilterMinMb = ""
                        storageFilterMaxMb = ""
                        renderStorageResult(resultBox)
                    }
                }
            }
        })
    }
    content.addView(resultBox)
    if (storageScanResult != null && picked != null) renderStorageResult(resultBox)
}

private fun MainActivity.renderStorageResult(box: LinearLayout) {
    box.removeAllViews()
    val r = storageScanResult ?: return
    val fmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

    box.addView(label("Hasil: folder \"${r.rootName}\" (bukan seluruh HP)", 15f, true).apply { setPadding(0, dp(10), 0, dp(6)) })
    box.addView(
        toolStatus(
            "Pemindaian terakhir: ${fmt.format(Date(r.scannedAt))} • ${String.format(Locale.getDefault(), "%.1f", r.durationMs / 1000.0)} dtk",
            true
        )
    )
    box.addView(infoCard("Isi folder ini", "${bytesText(r.totalBytes)} • ${r.fileCount} file • ${r.folderCount} subfolder"))
    if (r.truncated) {
        box.addView(subLabel("Pemindaian dihentikan di 200.000 file, jadi angka di bawah belum lengkap. Pilih folder yang lebih kecil.", 11f).apply { setPadding(dp(2), dp(6), 0, 0) })
    }
    if (r.unreadableFolders > 0) {
        box.addView(subLabel("${r.unreadableFolders} subfolder tidak bisa dibaca (dibatasi Android), jadi tidak ikut dihitung.", 11f).apply { setPadding(dp(2), dp(6), 0, 0) })
    }

    val catLines = (1 until storageCategoryLabels.size)
        .filter { r.categoryCount[it] > 0 }
        .sortedByDescending { r.categoryBytes[it] }
        .joinToString("\n") { "${storageCategoryLabels[it]}: ${bytesText(r.categoryBytes[it])} (${r.categoryCount[it]} file)" }
    if (catLines.isNotEmpty()) {
        box.addView(Space(this).apply { minimumHeight = dp(8) })
        box.addView(infoCard("Per kategori", catLines))
    }

    val folderLines = r.folderSizes.take(10).joinToString("\n") { "${bytesText(it.second)} • ${it.first}" }
    if (folderLines.isNotEmpty()) {
        box.addView(Space(this).apply { minimumHeight = dp(8) })
        box.addView(infoCard("Folder terbesar (tingkat pertama)", folderLines))
    }

    // ── Filter ────────────────────────────────────────────────────────────
    box.addView(label("File terbesar", 15f, true).apply { setPadding(0, dp(14), 0, dp(6)) })
    val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val minE = edit("Min (MB)").apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(storageFilterMinMb)
    }
    val maxE = edit("Maks (MB)").apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(storageFilterMaxMb)
    }
    val catBtn = button("Kategori: ${storageCategoryLabels[storageFilterCategory]}") {}
    styleAsSecondary(catBtn)
    fun applyFilter() {
        storageFilterMinMb = minE.text.toString()
        storageFilterMaxMb = maxE.text.toString()
        renderStorageFileList(listBox, r)
    }
    catBtn.setOnClickListener {
        AlertDialog.Builder(this)
            .setTitle("Filter kategori")
            .setItems(storageCategoryLabels) { _, which ->
                storageFilterCategory = which
                catBtn.text = "Kategori: ${storageCategoryLabels[which]}"
                applyFilter()
            }
            .setNegativeButton("Batal", null)
            .show()
    }
    box.addView(catBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    val rangeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    rangeRow.addView(minE, LinearLayout.LayoutParams(0, -2, 1f))
    rangeRow.addView(maxE, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
    box.addView(rangeRow)
    val apply = button("Terapkan rentang ukuran") { applyFilter() }
    styleAsSecondary(apply)
    box.addView(apply, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(8) })
    box.addView(listBox)
    renderStorageFileList(listBox, r)
}

private fun MainActivity.renderStorageFileList(listBox: LinearLayout, r: StorageScanResult) {
    listBox.removeAllViews()
    fun mb(s: String): Long? = s.trim().replace(',', '.').toDoubleOrNull()?.let { (it * 1024 * 1024).toLong() }
    val minB = mb(storageFilterMinMb) ?: 0L
    val maxB = mb(storageFilterMaxMb) ?: Long.MAX_VALUE
    if (minB > maxB) { toast("Ukuran min lebih besar dari maks"); return }
    val cat = storageFilterCategory
    val filtered = r.largestFiles.filter { (cat == 0 || it.category == cat) && it.size in minB..maxB }
    val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    listBox.addView(
        subLabel(
            "${filtered.size} cocok dari ${r.largestFiles.size} file terbesar yang disimpan. Ditampilkan ${minOf(filtered.size, STORAGE_LIST_SHOW)} teratas.",
            11f
        ).apply { setPadding(dp(2), 0, 0, dp(6)) }
    )
    if (filtered.isEmpty()) {
        listBox.addView(infoCard("Tidak ada file yang cocok", "Ubah kategori atau rentang ukuran."))
        return
    }
    filtered.take(STORAGE_LIST_SHOW).forEach { f ->
        val date = if (f.modified > 0) " • ${fmt.format(Date(f.modified))}" else ""
        listBox.addView(infoCard("${bytesText(f.size)} • ${f.name}", "${f.path}$date"))
        listBox.addView(Space(this).apply { minimumHeight = dp(6) })
    }
}
