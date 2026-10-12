package com.example.aidetest

import android.app.AlertDialog
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.CheckBox
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val BK_FORMAT = "mytools-app-backup"
private const val BK_VERSION = 2
private const val BK_FILE_CAP = 20000
private const val BK_FILES_MAX = 300L * 1024L * 1024L
private const val BK_RESTORE_MAX_ENTRIES = 25000
private const val BK_RESTORE_MAX_BYTES = 600L * 1024L * 1024L
private const val BK_JSON_MAX = 20L * 1024L * 1024L
private const val BK_SNAPSHOT_DIR = "restore_snapshots"
private const val BK_MARGIN = 8L * 1024L * 1024L

// Sengaja tidak dicadangkan: terikat Android Keystore perangkat ini / rahasia akses.
// Token terenkripsi Keystore (tak berlaku di perangkat lain) dan riwayat clipboard (bisa berisi kata sandi) tidak ikut backup.
private val BK_EXCLUDED_KEYS = setOf("gh_token_enc", "gh_save_token", "clipboard_history")
private val BK_ORDER = listOf("settings", "history", "bit", "secure", "workspaces", "appfiles")

private const val BK_NOT_INCLUDED =
    "• Data keuangan — punya backup sendiri di tool Keuangan\n" +
    "• Token GitHub — sengaja tidak dicadangkan (terkunci ke perangkat ini)\n" +
    "• Profil pengguna dan API key integrasi (disimpan terpisah)\n" +
    "• File di luar app storage: Galeri, Download, folder pribadi, dan file APK\n" +
    "• Cache dan salinan pengaman restore"

private fun bkLabel(id: String) = when (id) {
    "settings" -> "Pengaturan & preferensi"
    "history" -> "Riwayat & Recent Files"
    "bit" -> "Data Bit (companion)"
    "secure" -> "Secure Notes & 2FA"
    "workspaces" -> "Workspace & project"
    else -> "File lain di app storage"
}

private fun bkNote(id: String) = when (id) {
    "settings" -> "Tema, tool yang disematkan, dan preferensi tool."
    "history" -> "Riwayat hasil tool dan daftar file terbaru."
    "bit" -> "Kondisi dan kemajuan Bit."
    "secure" -> "Tetap terenkripsi dengan password Anda; backup tidak membukanya."
    "workspaces" -> "Folder workspaces di app storage."
    else -> "Impor, hasil ZIP, dan file kerja lain di folder data aplikasi."
}

private fun prefGroup(k: String) = when {
    k.startsWith("bit_") -> "bit"
    k.startsWith("secure_note_") || k.startsWith("totp_vault_") -> "secure"
    k == "history" || k == "recent_files" || k == "recent_files_times" -> "history"
    else -> "settings"
}

private class BCat(val id: String) {
    var count = 0
    var bytes = 0L
    var files: List<File> = emptyList()
    var capped = false
    var tooBig = false
}

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

private fun MainActivity.appVersionName(): String =
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

private fun MainActivity.snapshotDir(): File = File(filesDir, BK_SNAPSHOT_DIR)

private fun MainActivity.computeBackupCats(): List<BCat> {
    val cats = BK_ORDER.associateWith { BCat(it) }
    prefs.all.forEach { (k, v) ->
        if (k in BK_EXCLUDED_KEYS) return@forEach
        val c = cats.getValue(prefGroup(k))
        c.count++; c.bytes += k.length + (v?.toString()?.length ?: 0)
    }
    val root = filesDir.canonicalFile
    val snap = snapshotDir()
    val ws = ArrayList<File>(); val other = ArrayList<File>()
    var capped = false
    var n = 0
    for (f in filesDir.walkTopDown().onEnter { it != snap }) {
        if (!f.isFile) continue
        if (f.name.endsWith(".part") || f.name.endsWith(".restore")) continue
        if (n >= BK_FILE_CAP) { capped = true; break }
        val canon = runCatching { f.canonicalFile }.getOrNull() ?: continue
        if (!canon.path.startsWith(root.path + File.separator)) continue // abaikan symlink keluar app storage
        n++
        val rel = f.relativeTo(filesDir).path.replace(File.separatorChar, '/')
        if (rel.startsWith("workspaces/")) ws.add(f) else other.add(f)
    }
    fun fill(c: BCat, list: List<File>) {
        c.files = list; c.count = list.size; c.bytes = list.sumOf { it.length().coerceAtLeast(0L) }
        c.capped = capped; c.tooBig = c.bytes > BK_FILES_MAX
    }
    fill(cats.getValue("workspaces"), ws)
    fill(cats.getValue("appfiles"), other)
    return BK_ORDER.map { cats.getValue(it) }
}

private fun MainActivity.prefsToJson(selected: Set<String>): JSONObject {
    val o = JSONObject()
    prefs.all.forEach { (k, v) ->
        if (k in BK_EXCLUDED_KEYS || prefGroup(k) !in selected) return@forEach
        when (v) {
            is Boolean -> o.put(k, v)
            is Int -> o.put(k, v)
            is Long -> o.put(k, v)
            is Float -> o.put(k, v.toDouble())
            is String -> o.put(k, v)
            is Set<*> -> o.put(k, JSONArray(v.toList()))
        }
    }
    return o
}

private fun MainActivity.locationText(uri: Uri): String {
    val seg = Uri.decode(uri.lastPathSegment ?: return uri.toString())
    return if (seg.startsWith("primary:")) "Penyimpanan internal/" + seg.removePrefix("primary:") else seg
}

private fun dateText(ms: Long) = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID")).format(Date(ms))

private fun MainActivity.catLine(id: String, count: Int, bytes: Long) = "• ${bkLabel(id)} — $count item • ${bytesText(bytes)}"

/** Tulis backup lengkap ke [out] (via file .part). Mengembalikan ringkasan per kategori. */
private fun MainActivity.buildBackupZip(
    out: File, selected: Set<String>, cancelled: AtomicBoolean, onProgress: (Long, Int) -> Unit
): List<BCat> {
    val cats = computeBackupCats().filter { it.id in selected && !it.tooBig && it.count > 0 }
    val tmp = File(out.parentFile, ".${out.name}.part")
    val hashes = JSONObject()
    var bytes = 0L; var done = 0
    try {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zos ->
            val createdAt = System.currentTimeMillis()
            val prefJson = prefsToJson(selected)
            val root = JSONObject().apply {
                put("format", BK_FORMAT); put("version", BK_VERSION); put("createdAt", createdAt)
                put("appVersion", appVersionName()); put("preferences", prefJson)
            }
            val bj = root.toString(2).toByteArray(StandardCharsets.UTF_8)
            zos.putNextEntry(ZipEntry("backup.json")); zos.write(bj); zos.closeEntry()
            hashes.put("backup.json", hex(MessageDigest.getInstance("SHA-256").digest(bj)))
            bytes += bj.size; onProgress(bytes, done)

            val buf = ByteArray(64 * 1024)
            for (c in cats) {
                if (c.id != "workspaces" && c.id != "appfiles") continue
                for (f in c.files) {
                    if (cancelled.get()) throw InterruptedIOException("Operasi dibatalkan")
                    if (!f.isFile) continue
                    val name = "files/" + f.relativeTo(filesDir).path.replace(File.separatorChar, '/')
                    val md = MessageDigest.getInstance("SHA-256")
                    zos.putNextEntry(ZipEntry(name))
                    f.inputStream().use { s ->
                        while (true) {
                            val n = s.read(buf); if (n < 0) break
                            zos.write(buf, 0, n); md.update(buf, 0, n); bytes += n
                            onProgress(bytes, done)
                            if (cancelled.get()) throw InterruptedIOException("Operasi dibatalkan")
                        }
                    }
                    zos.closeEntry()
                    hashes.put(name, hex(md.digest()))
                    done++; onProgress(bytes, done)
                }
            }
            val manifest = JSONObject().apply {
                put("format", BK_FORMAT); put("version", BK_VERSION); put("createdAt", createdAt)
                put("appVersion", appVersionName())
                put("categories", JSONArray().apply { cats.forEach { c -> put(JSONObject().put("id", c.id).put("label", bkLabel(c.id)).put("count", c.count).put("bytes", c.bytes)) } })
                put("excluded", JSONArray(BK_NOT_INCLUDED.split("\n")))
                put("entries", hashes)
            }
            zos.putNextEntry(ZipEntry("manifest.json")); zos.write(manifest.toString(2).toByteArray(StandardCharsets.UTF_8)); zos.closeEntry()
        }
        if (!tmp.renameTo(out)) throw IOException("Gagal menyelesaikan file backup")
    } catch (t: Throwable) { tmp.delete(); throw t }
    return cats
}

// ---------------------------------------------------------------- Verifikasi

private class BPlan(
    val legacy: Boolean, val createdAt: Long, val appVersion: String, val prefs: JSONObject,
    val fileNames: List<String>, val count: Map<String, Int>, val bytes: Map<String, Long>, val verified: Int
)

private fun readLimited(s: InputStream, limit: Long): String {
    val bo = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0L
    s.use { while (true) { val n = it.read(buf); if (n < 0) break; total += n; if (total > limit) throw SecurityException("isi JSON terlalu besar"); bo.write(buf, 0, n) } }
    return bo.toString("UTF-8")
}

private fun fileCat(entryName: String) = if (entryName.startsWith("files/workspaces/")) "workspaces" else "appfiles"

/** Periksa arsip tanpa mengubah apa pun: nama aman, batas ukuran, manifest, dan SHA-256 tiap entry. */
private fun verifyBackup(zip: File, cancelled: AtomicBoolean): BPlan {
    return ZipFile(zip).use { z ->
        val all = z.entries().toList()
        if (all.size > BK_RESTORE_MAX_ENTRIES) throw SecurityException("backup punya terlalu banyak entry")
        var declared = 0L
        for (e in all) {
            val n = e.name
            // Hanya segmen ".." yang berbahaya; nama seperti "catatan...txt" sah.
            if (n.startsWith("/") || n.contains('\\') || n.split('/').any { it == ".." }) throw SecurityException("nama entry tidak aman: $n")
            declared += e.size.coerceAtLeast(0L)
        }
        if (declared > BK_RESTORE_MAX_BYTES) throw SecurityException("isi backup melebihi batas aman ${BK_RESTORE_MAX_BYTES / (1024 * 1024)} MB")
        val be = z.getEntry("backup.json") ?: throw IOException("backup.json tidak ditemukan — ini bukan file backup MyTools")
        val root = JSONObject(readLimited(z.getInputStream(be), BK_JSON_MAX))
        if (root.optString("format") != BK_FORMAT) throw IOException("Format backup tidak dikenali")
        val prefs = root.optJSONObject("preferences") ?: JSONObject()
        val me = z.getEntry("manifest.json")
        val fileNames = ArrayList<String>()
        var verified = 0
        if (me != null) {
            val manifest = JSONObject(readLimited(z.getInputStream(me), BK_JSON_MAX))
            if (manifest.optInt("version", 1) > BK_VERSION) throw IOException("Backup dibuat oleh versi aplikasi yang lebih baru")
            val entries = manifest.optJSONObject("entries") ?: throw IOException("manifest tidak lengkap")
            val inZip = all.map { it.name }.filter { it != "manifest.json" && !it.endsWith("/") }.toSet()
            val listed = entries.keys().asSequence().toSet()
            if (inZip != listed) throw SecurityException("isi arsip tidak cocok dengan manifest (kemungkinan rusak atau diubah)")
            val buf = ByteArray(64 * 1024)
            for (name in listed) {
                if (cancelled.get()) throw InterruptedIOException("Operasi dibatalkan")
                val md = MessageDigest.getInstance("SHA-256")
                z.getInputStream(z.getEntry(name)).use { s -> while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
                if (hex(md.digest()) != entries.getString(name)) throw SecurityException("checksum tidak cocok: $name")
                verified++
                if (name.startsWith("files/")) fileNames.add(name)
            }
        }
        val count = HashMap<String, Int>(); val bytes = HashMap<String, Long>()
        val keys = prefs.keys()
        while (keys.hasNext()) {
            val k = keys.next(); if (k in BK_EXCLUDED_KEYS) continue
            val g = prefGroup(k); count[g] = (count[g] ?: 0) + 1; bytes[g] = (bytes[g] ?: 0L) + k.length + prefs.get(k).toString().length
        }
        for (e in all) if (e.name.startsWith("files/") && !e.isDirectory) {
            if (me == null) continue
            val g = fileCat(e.name); count[g] = (count[g] ?: 0) + 1; bytes[g] = (bytes[g] ?: 0L) + e.size.coerceAtLeast(0L)
        }
        BPlan(me == null, root.optLong("createdAt", 0L), root.optString("appVersion", "?"), prefs, fileNames, count, bytes, verified)
    }
}

// ---------------------------------------------------------------- UI: halaman tool

internal fun MainActivity.backupRestoreTool() {
    clearPage("Backup / Restore")
    content.addView(label("Backup / Restore", 22f, true))
    content.addView(subLabel("Backup disimpan sebagai satu file ZIP di lokasi yang Anda pilih. Tidak dikirim ke server.", 12f))
    val loading = toolStatus("Menghitung isi backup…", false)
    content.addView(loading)
    toolThread("Menghitung isi…", false) {
        val cats = runCatching { computeBackupCats() }.getOrElse { emptyList() }
        runOnToolUiThread { content.removeView(loading); renderBackupUi(cats) }
    }
}

private fun MainActivity.renderBackupUi(cats: List<BCat>) {
    val sel = backupSelected ?: cats.filter { it.count > 0 && !it.tooBig }.map { it.id }.toMutableSet().also { backupSelected = it }
    sel.retainAll(cats.filter { it.count > 0 && !it.tooBig }.map { it.id }.toSet())
    content.addView(label("Pilih data yang dicadangkan", 13f, true).apply { setPadding(dp(2), dp(10), 0, dp(4)) })
    val total = toolStatus("", true)
    fun refresh() {
        val chosen = cats.filter { it.id in sel }
        total.text = if (chosen.isEmpty()) "Belum ada data dipilih" else "Dipilih: ${chosen.sumOf { it.count }} item • sekitar ${bytesText(chosen.sumOf { it.bytes })}"
    }
    cats.forEach { c ->
        val usable = c.count > 0 && !c.tooBig
        val cb = CheckBox(this).apply {
            text = "${bkLabel(c.id)}\n" + when {
                c.count == 0 -> "Tidak ada data"
                c.tooBig -> "${c.count} file • ${bytesText(c.bytes)} — terlalu besar (maks ${bytesText(BK_FILES_MAX)})"
                else -> "${c.count} item • ${bytesText(c.bytes)}" + if (c.capped) " (dibatasi $BK_FILE_CAP file)" else ""
            }
            textSize = 13f; setTextColor(textMain); isEnabled = usable; isChecked = c.id in sel
            setOnCheckedChangeListener { _, on -> if (on) sel.add(c.id) else sel.remove(c.id); refresh() }
        }
        content.addView(cb)
        content.addView(subLabel(bkNote(c.id) + if (c.tooBig) " Gunakan ZIP / UNZIP untuk folder besar." else "", 11f).apply { setPadding(dp(32), 0, 0, dp(6)) })
    }
    refresh()
    content.addView(total)
    content.addView(infoCard("Tidak ikut dicadangkan", BK_NOT_INCLUDED))
    content.addView(button("Lihat daftar isi backup") {
        if (sel.isEmpty()) { toast("Pilih data dulu"); return@button }
        AlertDialog.Builder(this).setTitle("Isi backup").setMessage(backupPreview(cats, sel)).setPositiveButton("OK", null).show()
    })
    content.addView(button("Buat Backup") {
        if (sel.isEmpty()) { toast("Pilih minimal satu jenis data"); return@button }
        val msg = backupPreview(cats, sel) + "\n\nLokasi: Anda pilih di langkah berikutnya.\nWaktu: ${dateText(System.currentTimeMillis())}\n\nTidak ikut:\n$BK_NOT_INCLUDED"
        AlertDialog.Builder(this).setTitle("Buat backup?").setMessage(msg).setNegativeButton("Batal", null)
            .setPositiveButton("Pilih lokasi") { _, _ -> backupPendingSel = sel.toSet(); createAppBackup() }.show()
    })
    val restore = button("Restore Backup") { restoreAppBackup() }
    styleAsSecondary(restore)
    content.addView(restore)
    content.addView(subLabel("Restore memeriksa integritas backup lebih dulu dan meminta konfirmasi sebelum menimpa apa pun.", 11f))
}

private fun MainActivity.backupPreview(cats: List<BCat>, sel: Set<String>): String {
    val chosen = cats.filter { it.id in sel }
    val sb = StringBuilder("Akan dicadangkan:\n")
    chosen.forEach { c ->
        sb.append(catLine(c.id, c.count, c.bytes)).append('\n')
        if (c.files.isNotEmpty()) c.files.take(6).forEach { f -> sb.append("    – ").append(f.relativeTo(filesDir).path).append('\n') }
        if (c.files.size > 6) sb.append("    … ${c.files.size - 6} file lainnya\n")
    }
    sb.append("\nPerkiraan ukuran: ").append(bytesText(chosen.sumOf { it.bytes }))
    return sb.toString()
}

// ---------------------------------------------------------------- Buat backup (hasil CREATE_DOCUMENT)

internal fun MainActivity.writeAppBackupV2(uri: Uri) {
    val pendingSel = backupPendingSel
    backupPendingSel = null
    // OPEN-05: memindai filesDir (bisa ribuan file) dilakukan di worker, lalu lanjut di UI.
    toolThread("Menghitung isi backup…") {
        val all = runCatching { computeBackupCats() }.getOrElse { emptyList() }
        runOnUiThread { if (!isFinishing && !isDestroyed) writeAppBackupV2Start(uri, pendingSel, all) }
    }
}

private fun MainActivity.writeAppBackupV2Start(uri: Uri, pendingSel: Set<String>?, all: List<BCat>) {
    val selected = pendingSel ?: all.filter { it.count > 0 && !it.tooBig }.map { it.id }.toSet()
    val cats = all.filter { it.id in selected && it.count > 0 && !it.tooBig }
    val est = cats.sumOf { it.bytes }.coerceAtLeast(1L)
    val fileCount = cats.sumOf { if (it.id == "workspaces" || it.id == "appfiles") it.count else 0 } + 1
    runWithProgress("Membuat backup", est, fileCount) { cancelled, onProgress ->
        val started = System.currentTimeMillis()
        val temp = File(cacheDir, "backup_${System.nanoTime()}.zip")
        try {
            if (cacheDir.usableSpace < est + BK_MARGIN) throw IOException("Ruang penyimpanan tidak cukup: butuh sekitar ${bytesText(est + BK_MARGIN)}, tersedia ${bytesText(cacheDir.usableSpace)}.")
            val written = buildBackupZip(temp, selected, cancelled, onProgress)
            val plan = verifyBackup(temp, cancelled) // pastikan hasilnya sendiri valid sebelum disalin
            (contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Tidak bisa menulis ke lokasi yang dipilih")).use { out ->
                temp.inputStream().use { it.copyTo(out, 64 * 1024) }
            }
            val destSize = uriSize(uri)
            val sizeNote = if (destSize > 0 && destSize != temp.length()) "\n⚠ Ukuran file tujuan (${bytesText(destSize)}) berbeda dari yang ditulis (${bytesText(temp.length())}). Coba backup ulang." else ""
            "Backup selesai\n" +
                "Nama: ${displayNameOf(uri)}\nLokasi: ${locationText(uri)}\nUkuran: ${bytesText(temp.length())}\nWaktu: ${dateText(started)}\n\n" +
                "Isi:\n" + written.joinToString("\n") { catLine(it.id, it.count, it.bytes) } +
                "\n\nIntegritas: ${plan.verified} entry terverifikasi (SHA-256)$sizeNote\n\nTidak ikut dicadangkan:\n$BK_NOT_INCLUDED"
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, uri) } // jangan tinggalkan file kosong/rusak
            throw t
        } finally { temp.delete() }
    }
}

// ---------------------------------------------------------------- Restore

internal fun MainActivity.startRestoreFlow(uri: Uri) {
    toolThread("Memeriksa backup…") {
        var staged: File? = null
        val plan = runCatching {
            val f = stageArchive(uri); staged = f
            verifyBackup(f, AtomicBoolean(false))
        }
        plan.onFailure { e ->
            staged?.delete()
            runOnToolUiThread {
                AlertDialog.Builder(this).setTitle("Backup tidak bisa dipulihkan")
                    .setMessage(friendlyZipError(e) + "\n\nTidak ada data yang diubah.").setPositiveButton("OK", null).show()
            }
        }
        val p = plan.getOrNull() ?: return@toolThread
        val f = staged ?: return@toolThread
        runOnToolUiThread { restoreSummaryDialog(f, p, displayNameOf(uri)) }
    }
}

private fun MainActivity.restoreSummaryDialog(zip: File, p: BPlan, name: String) {
    val ids = BK_ORDER.filter { (p.count[it] ?: 0) > 0 }
    val sb = StringBuilder("File: $name\n")
    if (p.createdAt > 0) sb.append("Dibuat: ${dateText(p.createdAt)}\n")
    sb.append("Versi aplikasi: ${p.appVersion}\n")
    sb.append(if (p.legacy) "Integritas: backup lama (v1), tidak punya checksum — hanya struktur yang diperiksa.\n" else "Integritas: ✓ ${p.verified} entry cocok dengan checksum.\n")
    sb.append("\nIsi backup:\n")
    ids.forEach { sb.append(catLine(it, p.count[it] ?: 0, p.bytes[it] ?: 0L)).append('\n') }
    if (p.legacy && p.prefs.has("gh_token_enc")) sb.append("\nToken GitHub di backup lama tidak dipulihkan; masukkan ulang di tool GitHub.\n")
    sb.append("\nTidak ada di backup ini:\n").append(BK_NOT_INCLUDED)
    if (ids.isEmpty()) {
        zip.delete()
        AlertDialog.Builder(this).setTitle("Backup kosong").setMessage(sb.toString()).setPositiveButton("OK", null).show(); return
    }
    AlertDialog.Builder(this).setTitle("Isi backup").setMessage(sb.toString())
        .setNegativeButton("Batal") { _, _ -> zip.delete() }
        .setOnCancelListener { zip.delete() }
        .setPositiveButton("Pilih data") { _, _ -> restoreChooseDialog(zip, p, ids) }.show()
}

private fun MainActivity.restoreChooseDialog(zip: File, p: BPlan, ids: List<String>) {
    val checked = BooleanArray(ids.size) { true }
    AlertDialog.Builder(this).setTitle("Data yang dipulihkan")
        .setMultiChoiceItems(ids.map { "${bkLabel(it)} (${p.count[it]})" }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
        .setNegativeButton("Batal") { _, _ -> zip.delete() }
        .setOnCancelListener { zip.delete() }
        .setPositiveButton("Lanjut") { _, _ ->
            val chosen = ids.filterIndexed { i, _ -> checked[i] }.toSet()
            if (chosen.isEmpty()) { zip.delete(); toast("Tidak ada data dipilih"); return@setPositiveButton }
            restoreConfirmDialog(zip, p, chosen)
        }.show()
}

private fun MainActivity.restoreConfirmDialog(zip: File, p: BPlan, chosen: Set<String>) {
    val prefKeys = ArrayList<String>(); val keysIt = p.prefs.keys()
    while (keysIt.hasNext()) { val k = keysIt.next(); if (k !in BK_EXCLUDED_KEYS && prefGroup(k) in chosen) prefKeys.add(k) }
    val replacedPrefs = prefKeys.count { prefs.contains(it) }
    val files = p.fileNames.filter { fileCat(it) in chosen }
    val replacedFiles = files.count { File(filesDir, it.removePrefix("files/")).exists() }
    val msg = "Restore akan MENIMPA data yang ada:\n" +
        "• ${replacedPrefs} pengaturan diganti, ${prefKeys.size - replacedPrefs} baru ditambahkan\n" +
        "• ${replacedFiles} file diganti, ${files.size - replacedFiles} baru ditambahkan\n" +
        "Data lain tidak dihapus.\n\nSebelum menimpa, salinan pengaman data Anda saat ini disimpan otomatis di:\n${snapshotDir().absolutePath}\n\nLanjutkan?"
    AlertDialog.Builder(this).setTitle("Timpa data?").setMessage(msg)
        .setNegativeButton("Batal") { _, _ -> zip.delete() }
        .setOnCancelListener { zip.delete() }
        .setPositiveButton("Timpa & Restore") { _, _ -> runRestore(zip, p, chosen, prefKeys, files) }.show()
}

private fun MainActivity.runRestore(zip: File, p: BPlan, chosen: Set<String>, prefKeys: List<String>, files: List<String>) {
    // OPEN-05: pindai data saat ini + baca ukuran ZIP di worker, bukan di thread UI.
    toolThread("Menyiapkan restore…") {
        val all = runCatching { computeBackupCats() }.getOrElse { emptyList() }
        val restoreBytes = (runCatching { ZipFile(zip).use { z -> files.sumOf { z.getEntry(it)?.size?.coerceAtLeast(0L) ?: 0L } } }.getOrDefault(0L)) + 1L
        runOnUiThread { if (!isFinishing && !isDestroyed) runRestoreStart(zip, p, chosen, prefKeys, files, all, restoreBytes) }
    }
}

private fun MainActivity.runRestoreStart(zip: File, p: BPlan, chosen: Set<String>, prefKeys: List<String>, files: List<String>, all: List<BCat>, restoreBytes: Long) {
    val allCats = all.filter { it.id in chosen && it.count > 0 }
    // Salinan pengaman wajib lengkap: kategori yang terlalu besar/terpotong tidak bisa diamankan → jangan timpa.
    val unsafe = allCats.filter { it.tooBig || it.capped }
    if (unsafe.isNotEmpty()) {
        toast("Restore dibatalkan: data saat ini (${unsafe.joinToString { it.id }}) terlalu besar untuk salinan pengaman. Kurangi data atau jangan pilih kategori itu.")
        zip.delete()
        return
    }
    val snapCats = allCats
    val snapBytes = snapCats.sumOf { it.bytes }
    runWithProgress("Memulihkan backup", snapBytes + restoreBytes, files.size) { cancelled, onProgress ->
        try {
            val snapDir = snapshotDir().apply { mkdirs() }
            if (filesDir.usableSpace < snapBytes + restoreBytes + BK_MARGIN) throw IOException("Ruang penyimpanan tidak cukup untuk salinan pengaman dan restore. Tidak ada data yang diubah.")
            val snap = File(snapDir, "pre_restore_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.zip")
            buildBackupZip(snap, chosen, cancelled) { b, _ -> onProgress(b, 0) }
            snapDir.listFiles()?.filter { it.isFile && it.name.startsWith("pre_restore_") }?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }

            val root = filesDir.canonicalFile
            var restored = 0; var skipped = 0; var bytes = 0L
            ZipFile(zip).use { z ->
                val buf = ByteArray(64 * 1024)
                for (name in files) {
                    if (cancelled.get()) throw InterruptedIOException("Operasi dibatalkan")
                    val rel = name.removePrefix("files/")
                    if (rel.startsWith("$BK_SNAPSHOT_DIR/")) { skipped++; continue }
                    val target = File(root, rel).canonicalFile
                    if (!target.path.startsWith(root.path + File.separator)) throw SecurityException("entry di luar app storage: $name")
                    if (target.isDirectory) { skipped++; continue }
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, ".${target.name}.restore")
                    try {
                        z.getInputStream(z.getEntry(name)).use { s -> FileOutputStream(tmp).use { o ->
                            while (true) {
                                val n = s.read(buf); if (n < 0) break
                                o.write(buf, 0, n); bytes += n
                                onProgress(snapBytes + bytes, restored)
                                if (cancelled.get()) throw InterruptedIOException("Operasi dibatalkan")
                            }
                        } }
                        if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) throw IOException("Gagal menulis ${target.name}") }
                    } finally { tmp.delete() }
                    restored++
                }
            }
            val editor = prefs.edit()
            prefKeys.forEach { k ->
                when (val v = p.prefs.get(k)) {
                    is Boolean -> editor.putBoolean(k, v)
                    is Int -> if (prefs.all[k] is Long) editor.putLong(k, v.toLong()) else editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Double -> editor.putFloat(k, v.toFloat())
                    is String -> editor.putString(k, v)
                    is JSONArray -> editor.putStringSet(k, (0 until v.length()).map { v.optString(it) }.toSet())
                }
            }
            if (!editor.commit()) throw IOException("Gagal menyimpan pengaturan hasil restore")
            "Restore selesai\nDipulihkan: ${prefKeys.size} pengaturan, $restored file" + (if (skipped > 0) " ($skipped dilewati)" else "") +
                "\nSalinan pengaman data lama: ${snap.absolutePath}\n\nBuka ulang tool atau aplikasi agar semua pengaturan terbaca."
        } finally { zip.delete() }
    }
}
