package com.example.aidetest

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// =====================================================================
// Inti hash: dipakai oleh "Checksum File" (1 file) dan "Bandingkan Hash" (2 file)
// =====================================================================
internal data class HashAlgo(val name: String, val hexLen: Int, val note: String)

internal val hashAlgos = listOf(
    HashAlgo("SHA-256", 64, "Pilihan yang disarankan: kuat dan dipakai luas untuk memverifikasi unduhan."),
    HashAlgo("SHA-512", 128, "Lebih panjang dari SHA-256; kekuatannya setara untuk keperluan sehari-hari."),
    HashAlgo("SHA-1", 40, "Sudah lemah terhadap pemalsuan yang disengaja. Cukup untuk mendeteksi salin/unduh yang rusak tidak sengaja."),
    HashAlgo("MD5", 32, "Sangat lama dan lemah terhadap pemalsuan yang disengaja. Hanya untuk mendeteksi file rusak tidak sengaja.")
)

internal fun hashAlgoExplain(a: HashAlgo): String =
    "${a.name} menghasilkan ${a.hexLen} karakter heksadesimal (${a.hexLen * 4} bit). ${a.note}"

internal const val HASH_DISCLAIMER =
    "Hash sama berarti isi file identik, dengan keyakinan kriptografis yang sangat tinggi. " +
    "Namun hash tidak membuktikan asal-usul atau keamanan file: file berbahaya yang sama persis juga punya hash yang sama. " +
    "Hash berbeda hanya berarti isi byte-nya tidak persis sama; itu tidak otomatis berarti file rusak atau berbahaya."

internal class HashUserError(message: String) : Exception(message)
internal class HashCancelled : Exception()

internal data class HashStreamResult(val hex: Map<String, String>, val bytes: Long)
internal data class HashFileInfo(val name: String, val size: Long, val modified: Long)
internal data class HashOutcome(val hex: Map<String, String>, val info: HashFileInfo, val bytes: Long, val warning: String?)

/** Hash satu aliran data untuk beberapa algoritma sekaligus (satu kali baca). */
internal fun hashStream(
    input: InputStream, algs: List<String>, totalBytes: Long, cancel: AtomicBoolean,
    onProgress: (Long, Long) -> Unit
): HashStreamResult {
    val digests = algs.associateWith { MessageDigest.getInstance(it) }
    val buf = ByteArray(256 * 1024)
    var read = 0L
    var lastTick = 0L
    while (true) {
        if (cancel.get()) throw HashCancelled()
        val n = input.read(buf)
        if (n < 0) break
        for (d in digests.values) d.update(buf, 0, n)
        read += n
        val now = System.nanoTime()
        if (now - lastTick > 100_000_000L) { lastTick = now; onProgress(read, totalBytes) }
    }
    onProgress(read, totalBytes)
    return HashStreamResult(digests.mapValues { e -> e.value.digest().joinToString("") { b -> "%02x".format(b) } }, read)
}

internal enum class HashMatch { MATCH, MISMATCH, UNKNOWN_FORMAT, NOT_COMPUTED }
internal data class HashVerdict(val match: HashMatch, val algo: String?)

/** Ambil hash heksadesimal dari teks tempelan (mendukung "sha256:...", "hash  namafile", spasi, titik dua). */
internal fun hashNormalize(raw: String): String? {
    val validLens = hashAlgos.map { it.hexLen }.toSet()
    for (token in raw.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))) {
        var t = token
        val prefix = Regex("^(md5|sha-?1|sha-?256|sha-?512)[:=]").find(t)
        if (prefix != null) t = t.substring(prefix.value.length)
        t = t.replace(":", "")
        if (t.length in validLens && t.all { it in '0'..'9' || it in 'a'..'f' }) return t
    }
    return null
}

internal fun hashVerdict(expectedRaw: String, computed: Map<String, String>): HashVerdict {
    val expected = hashNormalize(expectedRaw) ?: return HashVerdict(HashMatch.UNKNOWN_FORMAT, null)
    val algo = hashAlgos.firstOrNull { it.hexLen == expected.length } ?: return HashVerdict(HashMatch.UNKNOWN_FORMAT, null)
    val mine = computed[algo.name] ?: return HashVerdict(HashMatch.NOT_COMPUTED, algo.name)
    return HashVerdict(if (mine.equals(expected, ignoreCase = true)) HashMatch.MATCH else HashMatch.MISMATCH, algo.name)
}

internal fun MainActivity.hashFileInfo(uri: Uri): HashFileInfo {
    val name = queryName(uri) ?: uri.lastPathSegment ?: "file"
    var size = -1L
    var modified = -1L
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
        }
    }
    runCatching {
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) modified = c.getLong(0)
        }
    }
    return HashFileInfo(name, size, modified)
}

/** Hash satu URI dengan penanganan error ramah. Dipanggil dari thread latar belakang. */
internal fun MainActivity.hashUriBlocking(
    uri: Uri, algs: List<String>, who: String, cancel: AtomicBoolean, onProgress: (Long, Long) -> Unit
): HashOutcome {
    val before = hashFileInfo(uri)
    val opened = try {
        contentResolver.openInputStream(uri)
    } catch (e: SecurityException) {
        throw HashUserError("$who tidak bisa dibaca karena izin aksesnya hilang. Pilih ulang file-nya.")
    } catch (e: java.io.FileNotFoundException) {
        throw HashUserError("$who tidak ditemukan. Mungkin sudah dipindah atau dihapus.")
    } catch (e: IOException) {
        throw HashUserError("$who gagal dibuka: ${e.message ?: "kesalahan baca"}")
    }
    val stream = opened ?: throw HashUserError("$who tidak bisa dibuka (sumber file tidak menyediakan data).")
    val res = try {
        stream.use { hashStream(it, algs, before.size, cancel, onProgress) }
    } catch (e: IOException) {
        throw HashUserError("$who terputus saat dibaca (${e.message ?: "kesalahan baca"}). File mungkin dipindah atau penyimpanan dilepas.")
    }
    val after = hashFileInfo(uri)
    val warning = when {
        before.size >= 0 && res.bytes != before.size ->
            "$who berubah saat dihitung (dibaca ${res.bytes} byte, awalnya ${before.size} byte). Hasil tidak bisa diandalkan; coba lagi setelah file selesai ditulis."
        before.size >= 0 && after.size >= 0 && before.size != after.size ->
            "$who berubah ukuran saat dihitung. Hasil tidak bisa diandalkan; coba lagi."
        before.modified > 0 && after.modified > 0 && before.modified != after.modified ->
            "$who diubah saat proses berlangsung. Hasil tidak bisa diandalkan; coba lagi."
        else -> null
    }
    return HashOutcome(res.hex, after, res.bytes, warning)
}

internal fun MainActivity.hashProgressText(who: String, read: Long, total: Long): String =
    if (total > 0) "$who • ${(read * 100 / total).coerceIn(0, 100)}% • ${bytesText(read)} / ${bytesText(total)}"
    else "$who • ${bytesText(read)} dibaca"

internal fun MainActivity.hashErrorText(e: Throwable): String = when (e) {
    is HashUserError -> e.message ?: "Gagal menghitung hash"
    is OutOfMemoryError -> "Memori perangkat tidak cukup untuk memproses file ini"
    else -> "Gagal menghitung hash: ${e.message ?: e.javaClass.simpleName}"
}

internal fun MainActivity.hashValueCard(title: String, subtitle: String, hash: String): LinearLayout {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(10))
        background = bg(panel2, Ds.RADIUS_MD, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }
    card.addView(label(title, 13f, true))
    if (subtitle.isNotEmpty()) card.addView(subLabel(subtitle, 11f))
    card.addView(subLabel(hash, 12f).apply {
        setTextIsSelectable(true)
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(0, dp(6), 0, dp(6))
    })
    val copy = button("Salin hash") { copyText(hash) }
    styleAsSecondary(copy)
    card.addView(copy)
    return card
}

private class HashProgressUi(val box: LinearLayout, val bar: ProgressBar, val text: TextView, val cancelBtn: View)

private fun MainActivity.hashProgressUi(onCancel: () -> Unit): HashProgressUi {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    val text = subLabel("", 11f)
    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
    val cancel = button("Batalkan") { onCancel() }
    styleAsSecondary(cancel)
    box.addView(text); box.addView(bar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(4); bottomMargin = dp(6) }); box.addView(cancel)
    return HashProgressUi(box, bar, text, cancel)
}

private fun MainActivity.hashShowProgress(ui: HashProgressUi, who: String, read: Long, total: Long) {
    runOnToolUiThread {
        ui.text.text = hashProgressText(who, read, total)
        if (total > 0) { ui.bar.isIndeterminate = false; ui.bar.progress = (read * 100 / total).toInt().coerceIn(0, 100) }
        else ui.bar.isIndeterminate = true
    }
}

// =====================================================================
// Tool 1: Checksum File — hash SATU file + cocokkan dengan hash resmi
// =====================================================================
internal fun MainActivity.checksumTool(fromPicker: Boolean = false) {
    if (!fromPicker) checksumUri = null
    clearPage("Checksum File (1 file)")
    addToolHeader("Checksum File (1 file)", "Hitung hash satu file, lalu cocokkan dengan hash dari sumber resmi.", "HASH")
    content.addView(infoCard("Apa itu hash?",
        "Hash adalah \"sidik jari\" dari isi file. File yang isinya sama persis selalu menghasilkan hash yang sama. " +
            "Untuk membandingkan DUA file di HP, pakai tool \"Bandingkan Hash (2 file)\"."))
    content.addView(button("Pilih file") {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
        }, 1003)
    })

    val uri = checksumUri
    if (uri == null) {
        content.addView(subLabel("Pilih file, lalu MD5, SHA-1, SHA-256, dan SHA-512 dihitung di perangkat. File tidak diunggah.", 11f))
        return
    }

    val info = hashFileInfo(uri)
    content.addView(infoCard(info.name, if (info.size >= 0) "Ukuran ${bytesText(info.size)}" else "Ukuran tidak diketahui"))
    val cancel = AtomicBoolean(false)
    val progress = hashProgressUi { cancel.set(true) }
    content.addView(progress.box)
    val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(results)

    val algNames = listOf("SHA-256", "MD5", "SHA-1", "SHA-512")
    progress.box.visibility = View.VISIBLE
    progress.text.text = "Menyiapkan…"
    toolThread(showLoading = false) {
        val r = runCatching { hashUriBlocking(uri, algNames, "File", cancel) { read, total -> hashShowProgress(progress, "Menghitung", read, total) } }
        runOnToolUiThread {
            progress.box.visibility = View.GONE
            val failure = r.exceptionOrNull()
            if (failure is HashCancelled) { results.addView(subLabel("Dibatalkan.", 12f)); return@runOnToolUiThread }
            if (failure != null) { results.addView(infoCard("Gagal menghitung hash", hashErrorText(failure))); return@runOnToolUiThread }
            val out = r.getOrThrow()
            out.warning?.let { results.addView(infoCard("Peringatan: file berubah", it)) }
            val note = mapOf("SHA-256" to "Disarankan", "SHA-512" to "", "SHA-1" to "Lemah, hanya cek kerusakan", "MD5" to "Lemah, hanya cek kerusakan")
            for (name in listOf("SHA-256", "SHA-512", "SHA-1", "MD5")) {
                val a = hashAlgos.first { it.name == name }
                results.addView(hashValueCard("$name • ${a.hexLen} karakter", note[name].orEmpty(), out.hex[name].orEmpty()))
            }
            results.addView(toolSection("COCOKKAN DENGAN HASH RESMI", "Tempel hash dari situs/pengembang. Algoritma dikenali dari panjangnya."))
            val expected = edit("Tempel hash di sini (opsional)")
            results.addView(expected)
            val verdictBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val check = button("Cocokkan") {
                verdictBox.removeAllViews()
                val v = hashVerdict(expected.text.toString(), out.hex)
                val (title, body) = when (v.match) {
                    HashMatch.MATCH -> "Hash sama (${v.algo})" to "Isi file ini identik dengan file yang hash-nya kamu tempel, dengan keyakinan kriptografis sangat tinggi."
                    HashMatch.MISMATCH -> "Hash berbeda (${v.algo})" to "Isi file ini tidak persis sama dengan hash yang kamu tempel. Penyebab umum: versi file lain, unduhan terpotong, atau hash salah salin. Ini belum tentu berarti file berbahaya."
                    HashMatch.NOT_COMPUTED -> "Algoritma tidak dihitung" to "Algoritma ${v.algo} tidak termasuk yang dihitung di tool ini."
                    HashMatch.UNKNOWN_FORMAT -> "Format hash tidak dikenali" to "Hash harus berupa 32 (MD5), 40 (SHA-1), 64 (SHA-256), atau 128 (SHA-512) karakter heksadesimal."
                }
                verdictBox.addView(infoCard(title, body))
            }
            results.addView(check)
            results.addView(verdictBox)
            results.addView(subLabel(HASH_DISCLAIMER, 11f))
        }
    }
}

// =====================================================================
// Tool 2: Bandingkan Hash (2 file)
// =====================================================================
internal fun MainActivity.fileHashCompareTool() {
    fileHashUriA = null
    fileHashUriB = null
    clearPage("Bandingkan Hash (2 file)")
    addToolHeader("Bandingkan Hash (2 file)", "Cek apakah dua file memiliki isi yang sama persis.", "HASH")

    content.addView(toolSection("FILE A", "Pilih file pertama."))
    val a = filePickCard("File A belum dipilih", "Pilih file A") {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 1201)
    }
    content.addView(a)
    content.addView(toolSection("FILE B", "Pilih file kedua."))
    val b = filePickCard("File B belum dipilih", "Pilih file B") {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 1202)
    }
    content.addView(b)

    content.addView(toolSection("ALGORITMA"))
    val spinner = segmentedChoice(hashAlgos.map { it.name })
    content.addView(spinner, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(6) })
    val algoHelp = subLabel(hashAlgoExplain(hashAlgos[0]), 11f)
    // Penjelasan algoritma tersedia saat pengguna memilih algoritma; ringkas di layar utama.
    algoHelp.visibility = View.GONE
    content.addView(algoHelp)
    spinner.onSelected = { pos -> algoHelp.text = hashAlgoExplain(hashAlgos[pos]) }

    var running = false
    val cancel = AtomicBoolean(false)
    val progress = hashProgressUi { cancel.set(true) }
    val status = toolStatus("Siap • pilih dua file", false)
    val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(status)
    content.addView(progress.box)
    pinToolAction(button("Bandingkan File") {
        if (running) { toast("Masih menghitung…"); return@button }
        val ua = fileHashUriA
        val ub = fileHashUriB
        if (ua == null || ub == null) { toast("Pilih File A dan File B terlebih dahulu"); return@button }
        val algo = hashAlgos[spinner.selectedItemPosition]
        running = true
        cancel.set(false)
        results.removeAllViews()
        progress.box.visibility = View.VISIBLE
        status.text = "●  Menghitung hash…"
        toolThread(showLoading = false) {
            val r = runCatching {
                val oa = hashUriBlocking(ua, listOf(algo.name), "File A", cancel) { read, total -> hashShowProgress(progress, "File A", read, total) }
                val ob = hashUriBlocking(ub, listOf(algo.name), "File B", cancel) { read, total -> hashShowProgress(progress, "File B", read, total) }
                oa to ob
            }
            runOnToolUiThread {
                running = false
                progress.box.visibility = View.GONE
                val failure = r.exceptionOrNull()
                if (failure is HashCancelled) { status.text = "●  Dibatalkan"; return@runOnToolUiThread }
                if (failure != null) {
                    status.text = "●  Gagal"
                    results.addView(infoCard("Gagal menghitung hash", hashErrorText(failure)))
                    return@runOnToolUiThread
                }
                val (oa, ob) = r.getOrThrow()
                val ha = oa.hex[algo.name].orEmpty()
                val hb = ob.hex[algo.name].orEmpty()
                val same = ha.equals(hb, ignoreCase = true)
                status.text = "●  Selesai • ${if (same) "Hash sama" else "Hash berbeda"}"
                listOfNotNull(oa.warning, ob.warning).forEach { results.addView(infoCard("Peringatan: file berubah", it)) }
                val sameFile = ua == ub
                val title = if (same) "Hash sama" else "Hash berbeda"
                val body = buildString {
                    if (same) {
                        append("Isi kedua file identik, dengan keyakinan kriptografis sangat tinggi (${algo.name}).")
                        if (sameFile) append(" Catatan: File A dan File B adalah file yang sama.")
                    } else {
                        append("Isi kedua file tidak persis sama (${algo.name}). Satu byte berbeda saja sudah menghasilkan hash yang sama sekali berbeda.")
                        if (oa.info.size >= 0 && ob.info.size >= 0 && oa.info.size != ob.info.size)
                            append(" Ukuran juga berbeda: A ${bytesText(oa.info.size)}, B ${bytesText(ob.info.size)}.")
                        append(" Ini tidak berarti salah satu file rusak atau berbahaya; bisa jadi hanya versi yang berbeda.")
                    }
                }
                results.addView(infoCard(title, body))
                results.addView(hashValueCard("File A • ${oa.info.name}", "${algo.name} • ${algo.hexLen} karakter", ha))
                results.addView(hashValueCard("File B • ${ob.info.name}", "${algo.name} • ${algo.hexLen} karakter", hb))
                results.addView(subLabel(HASH_DISCLAIMER, 11f))
            }
        }
    })
    content.addView(results)
    content.addView(subLabel("Hash dihitung lokal di perangkat. File tidak diunggah ke server.", 11f))
    fileHashCompareLabelA = a.findViewWithTag<TextView>("fileLabel")
    fileHashCompareLabelB = b.findViewWithTag<TextView>("fileLabel")
}
