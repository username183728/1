package com.example.aidetest

import android.app.AlertDialog
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.LinearLayout
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Satu file yang akan dimasukkan ke ZIP. Sumbernya bisa File (app storage) atau Uri (pemilih Android). */
internal class ZipItem(val rel: String, val uri: Uri?, val file: File?, val size: Long)

private const val ZIP_MAX_ITEMS = 20000
private const val ZIP_EXTRACT_MAX_ENTRIES = 5000
private const val ZIP_EXTRACT_MAX_TOTAL = 256L * 1024L * 1024L
private const val ZIP_EXTRACT_MAX_ENTRY = 64L * 1024L * 1024L
private const val ZIP_SPACE_MARGIN = 8L * 1024L * 1024L
private const val ZIP_MAX_RATIO = 100L

private val ZIP_LEVEL_LABELS = listOf("Cepat", "Normal", "Maksimal", "Tanpa kompres")
private val ZIP_LEVEL_VALUES = intArrayOf(Deflater.BEST_SPEED, Deflater.DEFAULT_COMPRESSION, Deflater.BEST_COMPRESSION, Deflater.NO_COMPRESSION)

private fun cleanFileName(raw: String, fallback: String): String {
    val n = raw.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ')
    return n.ifBlank { fallback }
}

/** Nama yang belum dipakai: "a.zip" -> "a (1).zip" -> "a (2).zip". Tidak pernah menimpa file lama. */
private fun uniqueChild(parent: File, name: String): File {
    var f = File(parent, name)
    if (!f.exists()) return f
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (f.exists() && i < 10000) { f = File(parent, "$base ($i)$ext"); i++ }
    return f
}

internal fun MainActivity.uriSize(uri: Uri): Long = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
    } ?: 0L
}.getOrDefault(0L)

internal fun friendlyZipError(t: Throwable): String = when {
    t is InterruptedIOException -> "Dibatalkan. File sementara dihapus."
    t is ZipException -> "ZIP rusak atau bukan file ZIP yang valid (${t.message})."
    t is SecurityException -> "Ditolak demi keamanan: ${t.message}"
    t is IOException && (t.message ?: "").contains("ENOSPC", true) -> "Ruang penyimpanan tidak cukup."
    t is IOException && (t.message ?: "").contains("No space", true) -> "Ruang penyimpanan tidak cukup."
    else -> t.message ?: t.javaClass.simpleName
}

internal fun MainActivity.freeSpaceCheck(needed: Long) {
    val free = filesDir.usableSpace
    if (free < needed + ZIP_SPACE_MARGIN) {
        throw IOException("Ruang penyimpanan tidak cukup: butuh sekitar ${bytesText(needed + ZIP_SPACE_MARGIN)}, tersedia ${bytesText(free)}.")
    }
}

/** Kumpulkan daftar file dari pilihan pengguna (file/folder lewat pemilih) atau nama di app storage. */
private fun MainActivity.collectZipItems(): Triple<List<ZipItem>, String, Boolean>? {
    val uri = zipSourceUri
    if (uri != null) {
        if (zipSourceIsFolder) {
            val root = DocumentFile.fromTreeUri(this, uri) ?: return null
            val rootName = cleanFileName(root.name ?: "folder", "folder")
            val items = ArrayList<ZipItem>()
            var capped = false
            fun walk(d: DocumentFile, prefix: String) {
                for (c in d.listFiles()) {
                    if (items.size >= ZIP_MAX_ITEMS) { capped = true; return }
                    val n = c.name ?: continue
                    val rel = "$prefix/$n"
                    if (c.isDirectory) walk(c, rel) else if (c.isFile) items.add(ZipItem(rel, c.uri, null, c.length().coerceAtLeast(0L)))
                }
            }
            walk(root, rootName)
            return Triple(items, rootName, capped)
        }
        val name = cleanFileName(displayNameOf(uri), "file")
        return Triple(listOf(ZipItem(name, uri, null, uriSize(uri))), name.substringBeforeLast('.', name), false)
    }
    return null
}

private fun MainActivity.collectLegacyItems(src: File): List<ZipItem> {
    val base = src.parentFile ?: src
    return collectFilesForOperation(src).map { f ->
        val rel = if (src.isFile) src.name else f.relativeTo(base).path.replace(File.separatorChar, '/')
        ZipItem(rel, null, f, f.length().coerceAtLeast(0L))
    }
}

/** Tulis ZIP ke file sementara lalu pindahkan ke tujuan, jadi ZIP setengah jadi tidak pernah muncul. */
private fun MainActivity.writeZip(
    items: List<ZipItem>, out: File, level: Int, cancelled: AtomicBoolean, onProgress: (Long, Int) -> Unit
) {
    val tmp = File(out.parentFile, ".${out.name}.part")
    val used = HashSet<String>()
    var bytes = 0L
    var done = 0
    try {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zos ->
            zos.setLevel(level)
            val buf = ByteArray(64 * 1024)
            for (item in items) {
                if (cancelled.get()) throw InterruptedIOException("Operasi dibatalkan")
                var name = item.rel
                if (!used.add(name)) {
                    val dot = name.lastIndexOf('.'); val slash = name.lastIndexOf('/')
                    val b = if (dot > slash + 1) name.substring(0, dot) else name
                    val e = if (dot > slash + 1) name.substring(dot) else ""
                    var i = 1
                    while (!used.add("$b ($i)$e")) i++
                    name = "$b ($i)$e"
                }
                zos.putNextEntry(ZipEntry(name))
                val input = if (item.file != null) item.file.inputStream() else contentResolver.openInputStream(item.uri!!) ?: throw IOException("Tidak bisa membaca ${item.rel}")
                input.use { s ->
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        zos.write(buf, 0, n)
                        bytes += n
                        onProgress(bytes, done)
                        if (cancelled.get()) throw InterruptedIOException("Operasi dibatalkan")
                    }
                }
                zos.closeEntry()
                done++
                onProgress(bytes, done)
            }
        }
        if (!tmp.renameTo(out)) throw IOException("Gagal memindahkan hasil ZIP ke ${out.name}")
    } catch (t: Throwable) {
        tmp.delete()
        throw t
    }
}

/** Salin ZIP pilihan pengguna ke cache supaya bisa dibaca acak (ZipFile) dan diperiksa dulu. */
internal fun MainActivity.stageArchive(uri: Uri): File {
    val size = uriSize(uri)
    val cache = cacheDir
    if (size > 0 && cache.usableSpace < size + ZIP_SPACE_MARGIN) throw IOException("Ruang penyimpanan tidak cukup untuk membuka ZIP (${bytesText(size)}).")
    val tmp = File(cache, "zip_stage_${System.nanoTime()}.zip")
    try {
        (contentResolver.openInputStream(uri) ?: throw IOException("ZIP tidak bisa dibaca")).use { input ->
            FileOutputStream(tmp).use { out -> input.copyTo(out, 64 * 1024) }
        }
    } catch (t: Throwable) { tmp.delete(); throw t }
    return tmp
}

private class ZipScan(val entries: Int, val files: Int, val bytes: Long)

/** Periksa isi ZIP sebelum ekstraksi: valid/rusak, jumlah, ukuran, dan path berbahaya. */
private fun scanZipForExtract(zip: File, dest: File): ZipScan {
    val destCanonical = dest.canonicalFile
    var entries = 0; var files = 0; var total = 0L
    ZipFile(zip).use { z ->
        val en = z.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            entries++
            if (entries > ZIP_EXTRACT_MAX_ENTRIES) throw SecurityException("ZIP punya terlalu banyak entry (maks $ZIP_EXTRACT_MAX_ENTRIES).")
            val target = File(destCanonical, e.name).canonicalFile
            if (target.path != destCanonical.path && !target.path.startsWith(destCanonical.path + File.separator))
                throw SecurityException("entry '${e.name}' mencoba keluar dari folder tujuan.")
            if (!e.isDirectory) {
                files++
                val sz = e.size.coerceAtLeast(0L)
                if (sz > ZIP_EXTRACT_MAX_ENTRY) throw SecurityException("entry '${e.name}' terlalu besar.")
                total += sz
                if (total > ZIP_EXTRACT_MAX_TOTAL) throw SecurityException("total isi ZIP melebihi batas aman ${ZIP_EXTRACT_MAX_TOTAL / (1024 * 1024)} MB.")
            }
        }
    }
    return ZipScan(entries, files, total)
}

private fun extractVerified(
    zip: File, dest: File, scan: ZipScan, cancelled: AtomicBoolean, onProgress: (Long, Int) -> Unit
) {
    val destCanonical = dest.canonicalFile
    var written = 0L; var done = 0
    val compressed = zip.length().coerceAtLeast(1L)
    ZipFile(zip).use { z ->
        val buf = ByteArray(64 * 1024)
        val en = z.entries()
        while (en.hasMoreElements()) {
            if (cancelled.get()) throw InterruptedIOException("Operasi dibatalkan")
            val e = en.nextElement()
            var target = File(destCanonical, e.name).canonicalFile
            if (target.path != destCanonical.path && !target.path.startsWith(destCanonical.path + File.separator))
                throw SecurityException("entry '${e.name}' mencoba keluar dari folder tujuan.")
            if (e.isDirectory) { target.mkdirs(); continue }
            target.parentFile?.mkdirs()
            if (target.exists()) target = uniqueChild(target.parentFile ?: destCanonical, target.name) // nama bentrok di dalam arsip
            var entryBytes = 0L
            BufferedInputStream(z.getInputStream(e)).use { input ->
                FileOutputStream(target).use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        entryBytes += n; written += n
                        // Hitung byte nyata, bukan header yang bisa dipalsukan.
                        if (entryBytes > ZIP_EXTRACT_MAX_ENTRY || written > ZIP_EXTRACT_MAX_TOTAL)
                            throw SecurityException("isi ZIP melebihi batas ekstraksi aman.")
                        if (written > 32L * 1024L * 1024L && written / compressed > ZIP_MAX_RATIO)
                            throw SecurityException("rasio kompresi tidak wajar (kemungkinan arsip pembengkak).")
                        out.write(buf, 0, n)
                        onProgress(written, done)
                        if (cancelled.get()) throw InterruptedIOException("Operasi dibatalkan")
                    }
                }
            }
            done++
            onProgress(written, done)
        }
    }
}

private fun MainActivity.progressMessage(done: Long, total: Long, files: Int, totalFiles: Int): String {
    val pct = if (total > 0) ((done.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0
    return "$pct% • ${bytesText(done)} / ${bytesText(total)} • $files/$totalFiles file"
}

internal fun MainActivity.runWithProgress(
    title: String, totalBytes: Long, totalFiles: Int,
    work: (AtomicBoolean, (Long, Int) -> Unit) -> String
) {
    val (dialog, cancelled) = showByteProgressDialog(title, totalBytes)
    toolThread {
        var lastUi = 0L
        val result = runCatching {
            work(cancelled) { bytes, files ->
                val now = System.currentTimeMillis()
                if (now - lastUi >= 120L || files >= totalFiles) {
                    lastUi = now
                    val pct = if (totalBytes > 0) ((bytes.toDouble() / totalBytes) * 1000).toInt().coerceIn(0, 1000) else 0
                    runOnToolUiThread {
                        if (dialog.isShowing) { dialog.progress = pct; dialog.setMessage(progressMessage(bytes, totalBytes, files, totalFiles)) }
                    }
                }
            }
        }.getOrElse { "Gagal: ${friendlyZipError(it)}" }
        // OPEN-05: dialog progres selalu ditutup, walau halaman sudah berganti
        // (runOnToolUiThread membuang aksi untuk halaman lama → dialog tertinggal).
        runOnUiThread { if (dialog.isShowing) runCatching { dialog.dismiss() } }
        runOnToolUiThread { output(result) }
    }
}

internal fun MainActivity.zipToolPicked(requestCode: Int, uri: Uri) {
    persistUriPermissionsSafely(uri, wantWrite = false)
    when (requestCode) {
        ZIP_SRC_FILE_PICK_REQUEST -> { zipSourceUri = uri; zipSourceIsFolder = false }
        ZIP_SRC_FOLDER_PICK_REQUEST -> { zipSourceUri = uri; zipSourceIsFolder = true }
        ZIP_ARCHIVE_PICK_REQUEST -> zipArchiveUri = uri
    }
    zipTool()
}

internal fun MainActivity.zipTool() {
    clearPage("ZIP / UNZIP")
    toolWorkspace("ZIP / UNZIP", "Pilih file atau folder, lihat lokasi hasil, lalu kompres atau ekstrak dengan aman.", "zip-box")

    // ---------------- COMPRESS ----------------
    toolWorkspaceSection("COMPRESS", "Pilih sumber dari penyimpanan HP, atau ketik nama di app storage.")
    val srcUri = zipSourceUri
    val srcLabel = if (srcUri == null) "Belum ada sumber dipilih"
        else (if (zipSourceIsFolder) "Folder: " + (DocumentFile.fromTreeUri(this, srcUri)?.name ?: "folder")
              else "File: " + displayNameOf(srcUri))
    content.addView(toolStatus(srcLabel, srcUri != null))
    val pickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val pickFile = button("Pilih file") {
        startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(android.content.Intent.CATEGORY_OPENABLE)
        }, ZIP_SRC_FILE_PICK_REQUEST)
    }
    val pickFolder = button("Pilih folder") {
        startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, ZIP_SRC_FOLDER_PICK_REQUEST)
    }
    styleAsSecondary(pickFile); styleAsSecondary(pickFolder)
    pickRow.addView(pickFile, LinearLayout.LayoutParams(0, -2, 1f))
    pickRow.addView(pickFolder, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
    content.addView(pickRow)
    val legacySrc = edit("Atau nama file/folder di app storage")
    content.addView(legacySrc)

    content.addView(label("Tingkat kompresi", 13f, true).apply { setPadding(dp(2), dp(10), 0, dp(4)) })
    val levelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    ZIP_LEVEL_LABELS.forEachIndexed { i, text ->
        val b = button(if (i == zipLevelChoice) "✓ $text" else text) { zipLevelChoice = i; zipTool() }
        if (i != zipLevelChoice) styleAsTertiary(b)
        levelRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(4) })
    }
    content.addView(levelRow)
    content.addView(subLabel("Cepat = hemat waktu • Maksimal = file paling kecil • Tanpa kompres = hanya dikemas (foto/video sudah terkompres).", 11f))

    val outName = edit("Nama ZIP hasil (kosongkan = otomatis)")
    content.addView(outName)
    content.addView(subLabel("Hasil disimpan di app storage: ${filesDir.absolutePath}. Jika nama sudah ada, otomatis diberi (1), (2), dst. — file lama tidak ditimpa.", 11f))

    content.addView(button("Buat ZIP") {
        val typed = legacySrc.text.toString().trim()
        val picked = if (zipSourceUri != null) runCatching { collectZipItems() }.getOrNull() else null
        val items: List<ZipItem>; val defaultName: String; val capped: Boolean
        if (picked != null) {
            items = picked.first; defaultName = picked.second; capped = picked.third
        } else if (zipSourceUri == null && typed.isNotEmpty()) {
            val f = File(filesDir, typed)
            if (!f.exists()) { toast("File tidak ditemukan di app storage"); return@button }
            items = collectLegacyItems(f); defaultName = f.nameWithoutExtension.ifBlank { f.name }; capped = false
        } else { toast("Pilih file/folder dulu, atau ketik nama di app storage"); return@button }
        if (items.isEmpty()) { toast("Tidak ada file untuk dikompres"); return@button }
        val total = items.sumOf { it.size }
        var name = cleanFileName(outName.text.toString().ifBlank { defaultName }, "arsip")
        if (!name.endsWith(".zip", true)) name += ".zip"
        val out = uniqueChild(filesDir, name)
        val level = ZIP_LEVEL_VALUES[zipLevelChoice.coerceIn(0, ZIP_LEVEL_VALUES.lastIndex)]
        runCatching { freeSpaceCheck(total) }.onFailure { AlertDialog.Builder(this).setTitle("Ruang tidak cukup").setMessage(friendlyZipError(it)).setPositiveButton("OK", null).show(); return@button }
        val msg = "Sumber: ${items.size} file • ${bytesText(total)}" + (if (capped) " (dibatasi $ZIP_MAX_ITEMS file)" else "") +
            "\nKompresi: ${ZIP_LEVEL_LABELS[zipLevelChoice]}\nHasil: ${out.absolutePath}" +
            (if (out.name != name) "\n(Nama sudah ada, diganti otomatis)" else "")
        AlertDialog.Builder(this).setTitle("Buat ZIP?").setMessage(msg)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Mulai") { _, _ ->
                val started = System.currentTimeMillis()
                runWithProgress("Membuat ZIP", total, items.size) { cancelled, onProgress ->
                    writeZip(items, out, level, cancelled, onProgress)
                    "ZIP selesai\n${out.absolutePath}\nUkuran: ${bytesText(out.length())} (dari ${bytesText(total)}) • ${items.size} file • ${(System.currentTimeMillis() - started) / 1000.0} dtk"
                }
            }.show()
    })

    // ---------------- EXTRACT ----------------
    toolWorkspaceSection("EXTRACT", "Pilih file ZIP dari penyimpanan HP, atau ketik nama ZIP di app storage.")
    val zUri = zipArchiveUri
    content.addView(toolStatus(if (zUri == null) "Belum ada ZIP dipilih" else "ZIP: " + displayNameOf(zUri), zUri != null))
    val pickZip = button("Pilih file ZIP") {
        startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
        }, ZIP_ARCHIVE_PICK_REQUEST)
    }
    styleAsSecondary(pickZip)
    content.addView(pickZip)
    val legacyZip = edit("Atau nama .zip di app storage")
    content.addView(legacyZip)
    val destName = edit("Nama folder hasil (kosongkan = otomatis)")
    content.addView(destName)
    content.addView(subLabel("Hasil diekstrak ke folder baru di app storage: ${filesDir.absolutePath}. Folder lama tidak ditimpa.", 11f))

    // Dapatkan ZIP (staging ke cache bila dari Uri); onReady dipanggil di thread kerja.
    fun withArchive(block: (File, String, Boolean) -> Unit) {
        val typed = legacyZip.text.toString().trim()
        val uri = zipArchiveUri
        if (uri != null) {
            toolThread("Membuka ZIP…") {
                val staged = runCatching { stageArchive(uri) }
                staged.onFailure { e -> runOnToolUiThread { output("Gagal: ${friendlyZipError(e)}") } }
                val f = staged.getOrNull() ?: return@toolThread
                block(f, cleanFileName(displayNameOf(uri).substringBeforeLast('.'), "hasil_ekstrak"), true)
            }
        } else if (typed.isNotEmpty()) {
            val f = File(filesDir, typed)
            if (!f.isFile) { toast("ZIP tidak ditemukan di app storage"); return }
            toolThread("Memeriksa ZIP…") { block(f, f.nameWithoutExtension.ifBlank { "hasil_ekstrak" }, false) }
        } else toast("Pilih file ZIP dulu, atau ketik nama ZIP")
    }

    content.addView(button("Ekstrak ZIP") {
        withArchive { zipFile, defaultName, isTemp ->
            val dest = uniqueChild(filesDir, cleanFileName(destName.text.toString().ifBlank { defaultName }, "hasil_ekstrak"))
            val scan = runCatching {
                dest.mkdirs()
                val s = scanZipForExtract(zipFile, dest)
                dest.delete() // hanya dibuat untuk canonical path; dibuat ulang saat mulai
                freeSpaceCheck(s.bytes)
                s
            }
            scan.onFailure { e ->
                dest.delete(); if (isTemp) zipFile.delete()
                runOnToolUiThread { AlertDialog.Builder(this).setTitle("ZIP tidak bisa diekstrak").setMessage(friendlyZipError(e)).setPositiveButton("OK", null).show() }
            }
            val s = scan.getOrNull() ?: return@withArchive
            runOnToolUiThread {
                val msg = "Isi: ${s.files} file • ${bytesText(s.bytes)}\nHasil: ${dest.absolutePath}\nPemeriksaan keamanan: lolos"
                AlertDialog.Builder(this).setTitle("Ekstrak ZIP?").setMessage(msg)
                    .setNegativeButton("Batal") { _, _ -> if (isTemp) zipFile.delete() }
                    .setOnCancelListener { if (isTemp) zipFile.delete() }
                    .setPositiveButton("Mulai") { _, _ ->
                        dest.mkdirs()
                        val started = System.currentTimeMillis()
                        val totalForProgress = s.bytes.coerceAtLeast(1L)
                        val (dialog, cancelled) = showByteProgressDialog("Mengekstrak ZIP", totalForProgress)
                        toolThread {
                            var lastUi = 0L
                            val result = runCatching {
                                extractVerified(zipFile, dest, s, cancelled) { bytes, files ->
                                    val now = System.currentTimeMillis()
                                    if (now - lastUi >= 120L || files >= s.files) {
                                        lastUi = now
                                        val pct = ((bytes.toDouble() / totalForProgress) * 1000).toInt().coerceIn(0, 1000)
                                        runOnToolUiThread { if (dialog.isShowing) { dialog.progress = pct; dialog.setMessage(progressMessage(bytes, totalForProgress, files, s.files)) } }
                                    }
                                }
                                "Ekstrak selesai\n${dest.absolutePath}\n${s.files} file • ${bytesText(s.bytes)} • ${(System.currentTimeMillis() - started) / 1000.0} dtk"
                            }.getOrElse { e ->
                                // Gagal/dibatalkan: buang hasil setengah jadi.
                                runCatching { deleteRecursivelySafe(dest) }
                                "Gagal: ${friendlyZipError(e)}"
                            }
                            if (isTemp) zipFile.delete()
                            runOnToolUiThread { if (dialog.isShowing) dialog.dismiss(); output(result) }
                        }
                    }.show()
            }
        }
    })

    content.addView(button("Lihat isi ZIP / Verifikasi") {
        withArchive { zipFile, _, isTemp ->
            val result = runCatching {
                val entries = GitlsMaxEngine.listArchive(zipFile)
                val total = entries.sumOf { it.size }
                val shown = entries.take(250).joinToString("\n") { e -> "${e.name}${if (e.directory) "/" else ""}  •  ${bytesText(e.size)}" }
                "Entry: ${entries.size}\nUkuran isi: ${bytesText(total)}\nSHA-256: ${GitlsMaxEngine.sha256(zipFile)}\n\n$shown" +
                    if (entries.size > 250) "\n… ${entries.size - 250} entry lainnya" else ""
            }.getOrElse { "ZIP bermasalah: ${friendlyZipError(it)}" }
            if (isTemp) zipFile.delete()
            runOnToolUiThread { AlertDialog.Builder(this).setTitle("Archive Inspector").setMessage(result).setPositiveButton("OK", null).show() }
        }
    })
}
