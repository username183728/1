package com.example.aidetest

import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.PriorityQueue

// ═════════════════════════ Large File Finder ═════════════════════════

private fun MainActivity.walkDocumentTree(
    dir: DocumentFile,
    prefix: String,
    stats: WalkStats,
    onFile: (parent: DocumentFile, child: DocumentFile, path: String) -> Unit
) {
    if (stats.files >= FINDER_MAX_FILES) {
        stats.truncated = true
        return
    }
    val kids = dir.listFiles()
    stats.dirs++
    for (child in kids) {
        val n = child.name ?: continue
        val p = if (prefix.isEmpty()) n else "$prefix/$n"
        if (child.isDirectory) {
            walkDocumentTree(child, p, stats, onFile)
        } else {
            if (stats.files >= FINDER_MAX_FILES) {
                stats.truncated = true
                return
            }
            stats.files++
            onFile(dir, child, p)
        }
    }
}

private fun MainActivity.renderLargeResults(box: LinearLayout, hits: List<FsHit>, descending: Boolean) {
    box.removeAllViews()
    val ordered = if (descending) hits.sortedByDescending { it.size } else hits.sortedBy { it.size }
    box.addView(label("File ditemukan (${hits.size})", 15f, true).apply { setPadding(0, dp(10), 0, dp(6)) })
    val toggle = button(if (descending) "Urutan: terbesar → terkecil" else "Urutan: terkecil → terbesar") {
        renderLargeResults(box, hits, !descending)
    }
    styleAsSecondary(toggle)
    box.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    ordered.forEach { box.addView(fileSearchHitCard(it)) }
}

internal fun MainActivity.largeFileFinderTool() {
    clearPage("Large File Finder")
    content.addView(label("Large File Finder", 22f, true))
    content.addView(subLabel("Temukan file besar di folder pilihanmu.", 12f))

    // 1) Ukuran minimum
    content.addView(label("Ukuran minimum file (MB)", 13f, true).apply { setPadding(dp(2), dp(12), 0, dp(4)) })
    val min = edit("Contoh: 50").apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        filters = arrayOf(android.text.InputFilter.LengthFilter(9))
    }
    content.addView(min)
    val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    listOf("50 MB" to "50", "100 MB" to "100", "500 MB" to "500", "1 GB" to "1024").forEachIndexed { i, preset ->
        val b = button(preset.first) {
            min.setText(preset.second)
            min.setSelection(min.text.length)
        }
        styleAsSecondary(b)
        presetRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(6) })
    }
    content.addView(presetRow)

    // 2) Lokasi pindai
    content.addView(label("Lokasi yang dipindai", 13f, true).apply { setPadding(dp(2), dp(14), 0, dp(4)) })
    val picked = largeFileFolderUri
    val defaultRoots = scanRoots()
    val pickedName = picked?.let { DocumentFile.fromTreeUri(this, it)?.name ?: "folder pilihan" }
    val scopeText = if (picked != null) {
        "$pickedName\nSemua subfolder ikut dipindai."
    } else {
        "Lokasi bawaan • ${defaultRoots.size} area\nSemua subfolder ikut dipindai. Ketuk untuk detail."
    }
    val scopeCard = infoCard("Lokasi pemindaian", scopeText).apply {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            val detail = if (picked != null) {
                "Folder: $pickedName\nURI: $picked"
            } else {
                "Lokasi bawaan yang diperiksa:\n" + defaultRoots.joinToString("\n") { "• ${it.absolutePath}" } +
                    "\n\nFolder Galeri/DCIM atau folder lain yang dibatasi Android perlu dipilih manual."
            }
            AlertDialog.Builder(this@largeFileFinderTool)
                .setTitle("Detail lokasi")
                .setMessage(detail)
                .setPositiveButton("Tutup", null)
                .show()
        }
    }
    content.addView(scopeCard)
    val folderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val pick = button("Pilih folder") {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            },
            LARGE_FILE_FOLDER_PICK_REQUEST
        )
    }
    styleAsSecondary(pick)
    folderRow.addView(pick, LinearLayout.LayoutParams(0, -2, 1f))
    if (picked != null) {
        val reset = button("Lokasi bawaan") {
            largeFileFolderUri = null
            largeFileFinderTool()
        }
        styleAsTertiary(reset)
        folderRow.addView(reset, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
    }
    content.addView(folderRow)

    val out = toolStatus("Siap memindai", false)
    val notes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    var scanning = false

    pinToolAction(button("Scan Large Files") {
        if (scanning) {
            toast("Pemindaian masih berjalan")
            return@button
        }
        val parsed = parseMinSizeMb(min.text.toString())
        notes.removeAllViews()
        listBox.removeAllViews()
        if (parsed.error != null) {
            out.text = "Input tidak valid"
            notes.addView(infoCard("Periksa ukuran minimum", parsed.error))
            return@button
        }
        val minBytes = parsed.bytes
        val minLabel = parsed.label
        val treeUri = largeFileFolderUri
        scanning = true
        out.text = "Memindai…"

        toolThread("Memindai file besar…") {
            val stats = WalkStats()
            val cap = ToolPerformance.SEARCH_RESULT_CAP
            val top = PriorityQueue<FsHit>(cap + 1, compareBy<FsHit> { it.size })
            var found = 0
            var lastUi = 0L

            fun tick() {
                val now = System.currentTimeMillis()
                if (now - lastUi < 250L) return
                lastUi = now
                val d = stats.dirs
                val f = stats.files
                val h = found
                runOnToolUiThread { out.text = "Memindai… $d folder • $f file diperiksa • $h file ≥ $minLabel MB" }
            }

            fun offer(hit: FsHit) {
                found++
                top.add(hit)
                if (top.size > cap) top.poll()
            }

            var failure: Exception? = null
            try {
                if (treeUri != null) {
                    val root = DocumentFile.fromTreeUri(this, treeUri) ?: throw IOException("Folder tidak bisa dibuka")
                    walkDocumentTree(root, "", stats) { parent, child, path ->
                        tick()
                        val len = child.length()
                        if (len >= minBytes) {
                            offer(FsHit(child.name ?: path, path, len, child.lastModified(), null, child.uri, parent.uri))
                        }
                    }
                } else {
                    for (r in scanRoots()) {
                        walkFiles(r, stats) { f ->
                            tick()
                            val len = f.length()
                            if (len >= minBytes) offer(FsHit(f.name, f.absolutePath, len, f.lastModified(), f, null, null))
                        }
                    }
                }
            } catch (e: Exception) {
                failure = e
            }

            val err = failure
            val hits = top.toList()
            val status = finderStatus(err, stats, hits.isNotEmpty())
            val dirsDone = stats.dirs
            val filesDone = stats.files
            val foundTotal = found
            val truncated = stats.truncated
            val unreadable = unreadableNote(stats)

            runOnToolUiThread {
                scanning = false
                out.text = "$status • $dirsDone folder • $filesDone file diperiksa • $foundTotal file ≥ $minLabel MB" +
                    (if (foundTotal > hits.size) " (menampilkan ${hits.size} terbesar)" else "")
                when (status) {
                    "Izin ditolak" -> notes.addView(
                        infoCard(
                            "Izin ditolak",
                            "GITLS tidak diizinkan membaca lokasi ini. Ini bukan berarti tidak ada file besar.\n" +
                                (unreadable ?: (err?.message ?: "")) +
                                "\n\nTekan \"Pilih folder\" lalu pilih folder yang ingin dipindai agar Android memberi akses."
                        )
                    )
                    "Gagal memindai" -> notes.addView(
                        infoCard(
                            "Gagal memindai",
                            "Terjadi kesalahan: ${err?.message ?: err?.javaClass?.simpleName ?: "tidak diketahui"}.\n" +
                                if (hits.isEmpty()) "Belum ada hasil yang terkumpul. Coba lagi atau pilih folder lain."
                                else "Hasil di bawah belum lengkap karena pemindaian terhenti."
                        )
                    )
                    "Tidak ada hasil" -> notes.addView(
                        infoCard(
                            "Tidak ada file ≥ $minLabel MB",
                            "Pemindaian berhasil: $filesDone file di $dirsDone folder diperiksa, tidak ada yang sebesar itu. Ini bukan error.\n" +
                                "Coba angka yang lebih kecil, atau tekan \"Pilih folder\" untuk memindai folder lain."
                        )
                    )
                }
                if (status != "Izin ditolak" && unreadable != null) notes.addView(infoCard("Sebagian folder tidak terbaca", unreadable))
                if (truncated) notes.addView(infoCard("Pemindaian dibatasi", "Pemindaian berhenti di $FINDER_MAX_FILES file. Pilih folder yang lebih kecil agar hasilnya lengkap."))
                if (hits.isNotEmpty()) renderLargeResults(listBox, hits, true)
            }
        }
    })
    content.addView(out)
    content.addView(notes)
    content.addView(listBox)
}

// ═════════════════════════ Duplicate Finder ═════════════════════════

private class DupUiGroup(val size: Long, val sha: String, val files: MutableList<File>, var keep: Int)

private fun MainActivity.dupGroupCard(g: DupUiGroup, number: Int, rerender: () -> Unit): View {
    val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val extra = g.files.size - 1
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(10))
        background = bg(panel2, Ds.RADIUS_MD, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
    }
    card.addView(label("Grup $number • ${g.files.size} file • ${bytesText(g.size)} per file", 13f, true))
    card.addView(subLabel("Hemat ± ${bytesText(g.size * extra)} jika hanya satu dipertahankan.\nSHA-256: ${g.sha.take(16)}…", 11f))
    g.files.forEachIndexed { i, f ->
        val isKeep = i == g.keep
        val modified = if (f.lastModified() > 0) fmt.format(Date(f.lastModified())) else "tanggal tidak diketahui"
        card.addView(
            subLabel((if (isKeep) "✓ DIPERTAHANKAN\n" else "Duplikat\n") + f.absolutePath + "\n" + modified, 11f)
                .apply { setPadding(0, dp(8), 0, dp(2)) }
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (!isKeep) {
            val keepBtn = button("Pertahankan ini") {
                g.keep = i
                rerender()
            }
            styleAsSecondary(keepBtn)
            row.addView(keepBtn, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val openBtn = button("Buka") { openRecentFile(f) }
        styleAsTertiary(openBtn)
        row.addView(openBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { if (!isKeep) leftMargin = dp(8) })
        card.addView(row)
    }
    val del = button("Hapus $extra duplikat lainnya…") { confirmDeleteDuplicates(g, rerender) }
    card.addView(del, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    return card
}

private fun MainActivity.confirmDeleteDuplicates(g: DupUiGroup, rerender: () -> Unit) {
    if (g.keep !in g.files.indices) return
    val keepFile = g.files[g.keep]
    val toDelete = g.files.filterIndexed { i, _ -> i != g.keep }
    val msg = "File yang DIPERTAHANKAN:\n${keepFile.absolutePath}\n\n" +
        "File yang akan DIHAPUS PERMANEN (${toDelete.size} file, ± ${bytesText(g.size * toDelete.size)}):\n" +
        toDelete.joinToString("\n") { "• ${it.absolutePath}" } +
        "\n\nSebelum dihapus, isi setiap file diperiksa ulang. File yang sudah berubah tidak akan dihapus. Tindakan ini tidak bisa dibatalkan."
    AlertDialog.Builder(this)
        .setTitle("Hapus duplikat?")
        .setMessage(msg)
        .setNegativeButton("Batal", null)
        .setPositiveButton("Hapus") { _, _ -> deleteDuplicates(g, keepFile, toDelete, rerender) }
        .show()
}

private fun MainActivity.deleteDuplicates(g: DupUiGroup, keepFile: File, toDelete: List<File>, rerender: () -> Unit) {
    toolThread("Memeriksa & menghapus…") {
        fun unchanged(f: File): Boolean =
            f.exists() && f.length() == g.size && runCatching { GitlsMaxEngine.sha256(f) }.getOrNull() == g.sha

        val keepOk = unchanged(keepFile)
        var deleted = 0
        var skipped = 0
        var failed = 0
        val gone = ArrayList<File>()
        if (keepOk) {
            for (f in toDelete) {
                if (!unchanged(f)) {
                    skipped++
                    if (!f.exists()) gone.add(f)
                    continue
                }
                if (f.delete()) {
                    deleted++
                    gone.add(f)
                } else {
                    failed++
                }
            }
        }
        runOnToolUiThread {
            if (!keepOk) {
                toast("File yang dipertahankan berubah atau hilang. Tidak ada yang dihapus. Scan ulang.")
            } else {
                g.files.removeAll(gone)
                g.keep = g.files.indexOf(keepFile).coerceAtLeast(0)
                toast("Dihapus $deleted • dilewati $skipped (berubah/hilang) • gagal $failed (tidak ada izin hapus)")
                rerender()
            }
        }
    }
}

internal fun MainActivity.duplicateFinderTool() {
    clearPage("Duplicate Finder")
    content.addView(label("Duplicate Finder", 22f, true))
    content.addView(subLabel("Mencari file yang isinya benar-benar sama. Tidak ada file yang dihapus otomatis.", 12f))
    val activity = this
    val rootsForInfo = scanRoots()
    val scanInfo = subLabel("ⓘ Cara kerja & lokasi pemindaian", 12f).apply {
        setPadding(dp(2), dp(6), dp(2), dp(4))
        isClickable = true
        isFocusable = true
        setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("Tentang Duplicate Finder")
                .setMessage(
                    "File dikelompokkan berdasarkan ukuran, lalu isi kandidat dibandingkan dengan SHA-256. Nama dan tanggal tidak menentukan hasil.\n\n" +
                        "Lokasi bawaan:\n" + rootsForInfo.joinToString("\n") { "• ${it.absolutePath}" } +
                        "\n\nFolder seperti Galeri/DCIM/WhatsApp mungkin perlu dipilih atau disalin ke folder yang dapat diakses. File tidak dihapus otomatis."
                )
                .setPositiveButton("Tutup", null)
                .show()
        }
    }
    content.addView(scanInfo)
    content.addView(label("Ukuran minimum file (KB)", 13f, true).apply { setPadding(dp(2), dp(12), 0, dp(4)) })
    val min = edit("Contoh: 100").apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        filters = arrayOf(android.text.InputFilter.LengthFilter(9))
    }
    content.addView(min)
    content.addView(subLabel("Kosongkan = 1 KB. File yang lebih kecil dari ini diabaikan.", 11f))

    val out = toolStatus("Siap", false)
    val notes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val summary = subLabel("", 12f)
    val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val groupsUi = ArrayList<DupUiGroup>()
    var scanning = false

    fun rerender() {
        groupsUi.removeAll { it.files.size < 2 }
        listBox.removeAllViews()
        var saving = 0L
        var extraFiles = 0
        for (g in groupsUi) {
            saving += g.size * (g.files.size - 1)
            extraFiles += g.files.size - 1
        }
        if (groupsUi.isEmpty()) {
            summary.text = ""
            return
        }
        summary.text = "${groupsUi.size} grup duplikat • $extraFiles file berlebih • perkiraan ruang yang bisa dihemat: ${bytesText(saving)}"
        listBox.addView(label("Hasil duplikat", 15f, true).apply { setPadding(0, dp(10), 0, dp(4) ) })
        listBox.addView(subLabel("Pilih file yang ingin dipertahankan di tiap grup, lalu hapus sisanya. Penghapusan selalu meminta konfirmasi.", 11f))
        val shown = groupsUi.take(ToolPerformance.DUPLICATE_GROUP_CAP)
        shown.forEachIndexed { i, g -> listBox.addView(dupGroupCard(g, i + 1) { rerender() }) }
        if (groupsUi.size > shown.size) {
            listBox.addView(subLabel("Menampilkan ${shown.size} grup dengan penghematan terbesar dari ${groupsUi.size} grup. Hapus sebagian lalu scan ulang untuk melihat sisanya.", 11f))
        }
    }

    pinToolAction(button("Scan Duplicate") {
        if (scanning) {
            toast("Pemindaian masih berjalan")
            return@button
        }
        notes.removeAllViews()
        listBox.removeAllViews()
        summary.text = ""
        groupsUi.clear()
        val parsed = parseMinSizeKb(min.text.toString())
        if (parsed.error != null) {
            out.text = "Input tidak valid"
            notes.addView(infoCard("Periksa ukuran minimum", parsed.error))
            return@button
        }
        val minBytes = parsed.bytes
        scanning = true
        out.text = "Memindai…"

        toolThread("Mencari duplikat…") {
            val stats = WalkStats()
            val entries = ArrayList<Pair<File, Long>>()
            var lastUi = 0L

            fun tickScan() {
                val now = System.currentTimeMillis()
                if (now - lastUi < 250L) return
                lastUi = now
                val d = stats.dirs
                val f = stats.files
                runOnToolUiThread { out.text = "Langkah 1/2: mengelompokkan menurut ukuran… $d folder • $f file" }
            }

            var failure: Exception? = null
            var result: DupScanResult? = null
            try {
                for (r in scanRoots()) {
                    walkFiles(r, stats) { f ->
                        tickScan()
                        val len = f.length()
                        if (len >= minBytes) entries.add(f to len)
                    }
                }
                result = findDuplicateGroups(
                    entries,
                    hash = { f -> try { GitlsMaxEngine.sha256(f) } catch (_: Exception) { null } },
                    onProgress = { done, total ->
                        val now = System.currentTimeMillis()
                        if (now - lastUi >= 250L || done == total) {
                            lastUi = now
                            runOnToolUiThread { out.text = "Langkah 2/2: memeriksa isi (SHA-256) $done dari $total file kandidat" }
                        }
                    }
                )
            } catch (e: Exception) {
                failure = e
            }

            val err = failure
            val res = result
            val groups = res?.groups ?: emptyList()
            val status = finderStatus(err, stats, groups.isNotEmpty(), "Tidak ada duplikat")
            val dirsDone = stats.dirs
            val filesDone = stats.files
            val truncated = stats.truncated
            val unreadable = unreadableNote(stats)
            val hashFailed = res?.hashFailed ?: 0
            val candidates = res?.candidateFiles ?: 0

            runOnToolUiThread {
                scanning = false
                out.text = "$status • $dirsDone folder • $filesDone file diperiksa • ${groups.size} grup duplikat"
                when (status) {
                    "Izin ditolak" -> notes.addView(
                        infoCard(
                            "Izin ditolak",
                            "GITLS tidak diizinkan membaca lokasi ini, jadi hasilnya belum menunjukkan apakah ada duplikat.\n" + (unreadable ?: "")
                        )
                    )
                    "Gagal memindai" -> notes.addView(
                        infoCard(
                            "Gagal memindai",
                            "Terjadi kesalahan: ${err?.message ?: err?.javaClass?.simpleName ?: "tidak diketahui"}. Coba lagi."
                        )
                    )
                    "Tidak ada duplikat" -> notes.addView(
                        infoCard(
                            "Tidak ada duplikat",
                            "Pemindaian berhasil: $filesDone file di $dirsDone folder diperiksa, $candidates di antaranya punya ukuran yang sama dengan file lain " +
                                "dan sudah dicek isinya. Tidak ada yang identik. Ini bukan error."
                        )
                    )
                }
                if (status != "Izin ditolak" && unreadable != null) notes.addView(infoCard("Sebagian folder tidak terbaca", unreadable))
                if (hashFailed > 0) notes.addView(infoCard("File dilewati", "$hashFailed file tidak bisa dibaca saat dihitung SHA-256 dan tidak ikut dicek."))
                if (truncated) notes.addView(infoCard("Pemindaian dibatasi", "Pemindaian berhenti di $FINDER_MAX_FILES file, jadi hasilnya mungkin belum lengkap."))
                for (g in groups) groupsUi.add(DupUiGroup(g.size, g.sha256, g.files.toMutableList(), 0))
                for (g in groupsUi) g.keep = defaultKeepIndex(g.files)
                rerender()
            }
        }
    })
    content.addView(out)
    content.addView(notes)
    content.addView(summary)
    content.addView(listBox)
}
