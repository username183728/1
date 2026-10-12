package com.example.aidetest

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

private const val APK_COMPARE_MAX_BYTES = 300L * 1024 * 1024

private fun apkCompareHex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun MainActivity.pickApkFor(requestCode: Int) {
    startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip")
            )
            addCategory(Intent.CATEGORY_OPENABLE)
        },
        requestCode
    )
}

private fun MainActivity.apkPickCard(slot: String, uri: Uri?, requestCode: Int): View {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(10))
        background = bg(panel2, Ds.RADIUS_MD, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
    }
    card.addView(label(slot, 13f, true))
    val name = uri?.let { runCatching { displayNameOf(it) }.getOrDefault("File dipilih") }
    card.addView(subLabel(name ?: "Belum dipilih", 12f))
    card.addView(button(if (uri == null) "Pilih $slot" else "Ganti $slot") { pickApkFor(requestCode) })
    return card
}

internal fun MainActivity.apkCompareTool() {
    clearPage("APK Compare")
    content.addView(label("APK Compare", 22f, true))
    content.addView(
        subLabel(
            "Bandingkan dua APK tanpa memasangnya: nama paket, versi, izin, tanda tangan, dan isi file. " +
                "GITLS tidak menilai keamanan APK.",
            12f
        )
    )
    val a = apkCompareFirstUri
    val b = apkCompareSecondUri
    content.addView(apkPickCard("APK A", a, APK_COMPARE_A))
    content.addView(apkPickCard("APK B", b, APK_COMPARE_B))
    val ready = a != null && b != null
    val go = button("Bandingkan") {
        val ua = apkCompareFirstUri
        val ub = apkCompareSecondUri
        if (ua == null || ub == null) toast("Pilih APK A dan APK B dulu") else compareApks(ua, ub)
    }
    go.isEnabled = ready
    go.alpha = if (ready) 1f else 0.5f
    pinToolAction(go)
    if (!ready) content.addView(subLabel("Pilih kedua APK untuk mulai membandingkan.", 11f))
}

internal fun MainActivity.compareApks(a: Uri, b: Uri) {
    toolThread("Membandingkan APK…") {
        val result = runCatching {
            val sa = readApkSnapshot(a, "apk_compare_a.apk")
            val sb = readApkSnapshot(b, "apk_compare_b.apk")
            Triple(sa, sb, buildApkCompareReport(sa, sb))
        }
        runOnToolUiThread {
            apkCompareTool()
            val r = result.getOrNull()
            if (r == null) {
                content.addView(toolStatus("Perbandingan gagal", false))
                content.addView(infoCard("Penyebab", result.exceptionOrNull()?.message ?: "File tidak bisa dibaca."))
            } else {
                renderApkCompare(r.first, r.second, r.third)
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun MainActivity.readApkSnapshot(uri: Uri, cacheName: String): ApkSnapshot {
    val name = runCatching { displayNameOf(uri) }.getOrDefault(cacheName)
    val temp = File(cacheDir, cacheName)
    temp.delete()
    try {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    if (size > APK_COMPARE_MAX_BYTES) throw IOException("$name lebih besar dari 300 MB, terlalu besar untuk dibandingkan di HP.")
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        } ?: throw IOException("$name tidak bisa dibuka.")
        val sha = md.digest().joinToString("") { "%02x".format(it) }

        val problems = ArrayList<String>()

        // Isi ZIP: nama entri -> (CRC, ukuran asli)
        var entries: Map<String, Pair<Long, Long>>? = null
        try {
            ZipFile(temp).use { zip ->
                val m = LinkedHashMap<String, Pair<Long, Long>>()
                val en = zip.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    if (e.isDirectory) continue
                    m[e.name] = e.crc to e.size
                }
                entries = m
            }
        } catch (e: Exception) {
            problems.add("Isi ZIP tidak terbaca (${e.message ?: e.javaClass.simpleName}). File mungkin rusak atau bukan APK.")
        }

        // Metadata lewat PackageManager
        var label: String? = null
        var pkg: String? = null
        var versionName: String? = null
        var versionCode: Long? = null
        var minSdk: Int? = null
        var targetSdk: Int? = null
        var permissions: List<String>? = null
        var signers: List<String>? = null
        val flags = (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES) or
            PackageManager.GET_PERMISSIONS
        val info = runCatching { packageManager.getPackageArchiveInfo(temp.absolutePath, flags) }.getOrNull()
        if (info == null) {
            problems.add("Metadata (nama paket, versi, izin, tanda tangan) tidak terbaca. File mungkin rusak, berupa bundle (.apks/.xapk), atau formatnya tidak didukung versi Android ini.")
        } else {
            pkg = info.packageName
            versionName = info.versionName
            versionCode = PackageInfoCompat.getLongVersionCode(info)
            val ai = info.applicationInfo
            if (ai != null) {
                ai.sourceDir = temp.absolutePath
                ai.publicSourceDir = temp.absolutePath
                label = runCatching { packageManager.getApplicationLabel(ai).toString() }.getOrNull()
                if (Build.VERSION.SDK_INT >= 24) minSdk = ai.minSdkVersion
                targetSdk = ai.targetSdkVersion
            }
            permissions = info.requestedPermissions?.toList() ?: emptyList()
            val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            if (sigs != null && sigs.isNotEmpty()) {
                signers = sigs.map { apkCompareHex(it.toByteArray()) }.sorted()
            }
        }

        return ApkSnapshot(
            fileName = name,
            size = size,
            sha256 = sha,
            appLabel = label,
            packageName = pkg,
            versionName = versionName,
            versionCode = versionCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            permissions = permissions,
            signers = signers,
            entries = entries,
            problems = problems
        )
    } finally {
        temp.delete()
    }
}

private fun MainActivity.apkSideRow(title: String, a: String, b: String): View {
    val differs = a != b
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = bg(panel2, Ds.RADIUS_MD, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
    }
    box.addView(label((if (differs) "≠  " else "=  ") + title, 12f, true))
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    row.addView(subLabel("A\n$a", 11f), LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(subLabel("B\n$b", 11f), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
    box.addView(row)
    return box
}

private fun MainActivity.renderApkCompare(a: ApkSnapshot, b: ApkSnapshot, report: ApkCompareReport) {
    val na = "tidak terbaca"
    content.addView(toolStatus(report.headline, report.identical))

    content.addView(toolSection("BERDAMPINGAN", "Tanda ≠ berarti nilainya berbeda."))
    content.addView(apkSideRow("Nama file", a.fileName, b.fileName))
    content.addView(apkSideRow("Ukuran", bytesText(a.size), bytesText(b.size)))
    content.addView(apkSideRow("Nama aplikasi", a.appLabel ?: na, b.appLabel ?: na))
    content.addView(apkSideRow("Nama paket", a.packageName ?: na, b.packageName ?: na))
    content.addView(
        apkSideRow(
            "Versi",
            if (a.versionCode == null) na else "${a.versionName ?: "-"} (kode ${a.versionCode})",
            if (b.versionCode == null) na else "${b.versionName ?: "-"} (kode ${b.versionCode})"
        )
    )
    content.addView(
        apkSideRow(
            "SDK minimum / target",
            if (a.minSdk == null || a.targetSdk == null) na else "${a.minSdk} / ${a.targetSdk}",
            if (b.minSdk == null || b.targetSdk == null) na else "${b.minSdk} / ${b.targetSdk}"
        )
    )
    content.addView(apkSideRow("Jumlah izin", a.permissions?.size?.toString() ?: na, b.permissions?.size?.toString() ?: na))
    content.addView(apkSideRow("File di dalam APK", a.entries?.size?.toString() ?: na, b.entries?.size?.toString() ?: na))
    content.addView(
        apkSideRow(
            "Tanda tangan (SHA-256)",
            a.signers?.firstOrNull()?.take(16)?.let { "$it…" } ?: na,
            b.signers?.firstOrNull()?.take(16)?.let { "$it…" } ?: na
        )
    )
    content.addView(apkSideRow("SHA-256 file", a.sha256.take(16) + "…", b.sha256.take(16) + "…"))

    content.addView(toolSection("PENJELASAN PERBEDAAN", "Apa yang sama dan apa yang berbeda, beserta artinya."))
    report.sections.forEach { s ->
        content.addView(infoCard((if (s.differs) "≠  " else "=  ") + s.title, s.body))
    }

    if (report.notComparable.isNotEmpty()) {
        content.addView(
            infoCard(
                "Tidak dapat dibandingkan",
                report.notComparable.joinToString("\n") { "• $it" } +
                    "\n\nBagian ini tidak ikut dalam kesimpulan di atas."
            )
        )
    }
    content.addView(
        subLabel(
            "Perbandingan ini hanya menunjukkan isi yang tertulis di dalam file. APK dengan hash sama berarti file yang sama; " +
                "hash berbeda belum tentu berarti fitur berbeda. GITLS tidak memindai malware.",
            11f
        )
    )
}
