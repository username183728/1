package com.example.aidetest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeSet
import java.util.zip.ZipFile

/** Hasil analisis satu APK. [statusOk] hanya berarti metadata terbaca — BUKAN bahwa APK aman. */
internal class ApkReport(
    val fileName: String,
    val size: Long,
    val sha256: String,
    val status: String,
    val statusOk: Boolean,
    val sections: List<Pair<String, String>>
)

private const val APK_MAX_BYTES = 300L * 1024 * 1024

private fun apkSdkName(api: Int): String = when (api) {
    21 -> "Android 5.0"; 22 -> "Android 5.1"; 23 -> "Android 6.0"; 24 -> "Android 7.0"; 25 -> "Android 7.1"
    26 -> "Android 8.0"; 27 -> "Android 8.1"; 28 -> "Android 9"; 29 -> "Android 10"; 30 -> "Android 11"
    31 -> "Android 12"; 32 -> "Android 12L"; 33 -> "Android 13"; 34 -> "Android 14"; 35 -> "Android 15"
    36 -> "Android 16"
    else -> if (api in 1..20) "Android 4.x atau lebih lama" else "Android versi baru"
} + " (API $api)"

internal fun MainActivity.apkInspector() {
    clearPage("APK Inspector")
    toolWorkspace(
        "APK Inspector",
        "Periksa metadata, izin, hash, dan penandatangan APK tanpa memasangnya.",
        "tools"
    )
    content.addView(
        infoCard(
            "Baca ini dulu",
            "Hasil analisis hanya menunjukkan apa yang tertulis di dalam file. GITLS tidak memindai malware dan tidak membuktikan " +
                "APK ini asli atau aman. Pasang APK hanya dari sumber tepercaya, dan cocokkan SHA-256 dengan yang dipublikasikan pengembangnya."
        )
    )
    content.addView(button("Pilih APK") {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip")
            )
            addCategory(Intent.CATEGORY_OPENABLE)
        }, 1002)
    })
}

/** Dipanggil dari onActivityResult (requestCode 1002). */
internal fun MainActivity.inspectZipOrApk(uri: Uri) {
    val name = runCatching { displayNameOf(uri) }.getOrDefault("file")
    toolThread("Menganalisis APK…") {
        val result = runCatching { analyzeApk(uri, name) }
        runOnToolUiThread {
            apkInspector()
            val r = result.getOrNull()
            if (r == null) {
                content.addView(toolStatus("Analisis gagal", false))
                content.addView(infoCard("Penyebab", result.exceptionOrNull()?.message ?: "File tidak bisa dibaca."))
            } else {
                renderApkReport(r)
            }
        }
    }
}

private fun MainActivity.renderApkReport(r: ApkReport) {
    content.addView(toolStatus(r.status, r.statusOk))
    val copy = button("Salin SHA-256") {
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("sha256", r.sha256))
        }
        toast("SHA-256 disalin")
    }
    styleAsSecondary(copy)
    content.addView(copy, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    r.sections.forEach { (title, body) -> content.addView(infoCard(title, body)) }
}

private fun MainActivity.analyzeApk(uri: Uri, name: String): ApkReport {
    val temp = File(cacheDir, "apk_inspect.apk")
    temp.delete()
    val md = MessageDigest.getInstance("SHA-256")
    var size = 0L
    try {
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    if (size > APK_MAX_BYTES) throw IOException("File lebih besar dari 300 MB, terlalu besar untuk dianalisis di HP.")
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        } ?: throw IOException("File tidak bisa dibuka.")
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        return buildApkReport(temp, name, size, sha)
    } finally {
        temp.delete()
    }
}

@Suppress("DEPRECATION")
private fun MainActivity.buildApkReport(temp: File, name: String, size: Long, sha: String): ApkReport {
    val sections = ArrayList<Pair<String, String>>()
    sections.add("File" to "$name\nUkuran: ${bytesText(size)} ($size byte)\nSHA-256:\n$sha")

    // ── 1. Struktur ZIP / integritas dasar ────────────────────────────────
    var zipProblem: String? = null
    var entries = 0
    var dex = 0
    var hasManifest = false
    var bundleHint = name.lowercase(Locale.ROOT).let { it.endsWith(".apks") || it.endsWith(".xapk") || it.endsWith(".aab") }
    val abis = TreeSet<String>()
    try {
        ZipFile(temp).use { zip ->
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.isDirectory) continue
                entries++
                val n = e.name
                if (n == "AndroidManifest.xml") hasManifest = true
                if (n.startsWith("classes") && n.endsWith(".dex") && !n.contains('/')) dex++
                if (n.startsWith("lib/") && n.endsWith(".so")) n.split('/').getOrNull(1)?.let { abis.add(it) }
                if (n == "base.apk" || n == "BundleConfig.pb" || n.endsWith("/base.apk")) bundleHint = true
            }
            // Membaca manifest sampai habis memicu pemeriksaan CRC oleh ZipFile.
            zip.getEntry("AndroidManifest.xml")?.let { e ->
                zip.getInputStream(e).use { s -> val b = ByteArray(8192); while (s.read(b) >= 0) { /* baca saja */ } }
            }
        }
    } catch (e: Exception) {
        zipProblem = e.message ?: e.javaClass.simpleName
    }
    if (zipProblem != null) {
        sections.add("Masalah file" to "File tidak bisa dibaca sebagai ZIP/APK: $zipProblem\nKemungkinan file rusak, terpotong saat diunduh, atau bukan APK.")
        return ApkReport(name, size, sha, "APK rusak atau bukan file APK yang valid", false, sections)
    }
    val contents = "File di dalam APK: $entries\nFile DEX: $dex\n" +
        "Library native (ABI): ${if (abis.isEmpty()) "tidak ada" else abis.joinToString(", ")}\n" +
        "AndroidManifest.xml: ${if (hasManifest) "ada" else "tidak ada"}"

    // ── 2. Metadata lewat PackageManager ──────────────────────────────────
    val pm = packageManager
    val flags = (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES) or
        PackageManager.GET_PERMISSIONS
    val info = runCatching { pm.getPackageArchiveInfo(temp.absolutePath, flags) }.getOrNull()
    if (info == null) {
        sections.add("Isi APK" to contents)
        sections.add(
            "Metadata tidak terbaca" to
                (if (bundleHint) "File ini tampak seperti bundle (.apks/.xapk/.aab) atau berisi split APK. Ekstrak dulu, lalu periksa file base.apk di dalamnya."
                else "Android tidak bisa membaca manifest. APK mungkin rusak, terpotong, memakai format yang tidak didukung versi Android ini, atau memang bukan APK.")
        )
        return ApkReport(name, size, sha, "Metadata APK tidak dapat dibaca", false, sections)
    }

    val appInfo = info.applicationInfo
    if (appInfo != null) {
        appInfo.sourceDir = temp.absolutePath
        appInfo.publicSourceDir = temp.absolutePath
    }
    val label = runCatching { appInfo?.let { pm.getApplicationLabel(it).toString() } }.getOrNull()
    sections.add(
        "Aplikasi" to
            "Nama: ${label ?: "tidak terbaca"}\nNama paket: ${info.packageName}\n" +
            "Versi: ${info.versionName ?: "tidak ada"} (kode ${PackageInfoCompat.getLongVersionCode(info)})"
    )

    val compat = StringBuilder()
    if (appInfo != null && Build.VERSION.SDK_INT >= 24) {
        compat.append("Android minimum: ${apkSdkName(appInfo.minSdkVersion)}\n")
        compat.append("Target SDK: ${apkSdkName(appInfo.targetSdkVersion)}")
        if (appInfo.minSdkVersion > Build.VERSION.SDK_INT) {
            compat.append("\n\nHP ini memakai API ${Build.VERSION.SDK_INT}, lebih rendah dari minimum APK, jadi APK ini tidak akan bisa dipasang di sini.")
        }
    } else {
        compat.append("Android minimum dan target SDK tidak bisa dibaca di versi Android ini.")
    }
    sections.add("Kompatibilitas" to compat.toString())

    // ── 3. Izin ───────────────────────────────────────────────────────────
    val perms = info.requestedPermissions?.toList().orEmpty()
    if (perms.isEmpty()) {
        sections.add("Izin yang diminta" to "APK tidak meminta izin apa pun (atau daftar izin tidak terbaca).")
    } else {
        val sensitive = ArrayList<String>()
        val other = ArrayList<String>()
        val unknown = ArrayList<String>()
        for (p in perms) {
            val level = runCatching { pm.getPermissionInfo(p, 0).protectionLevel and PermissionInfo.PROTECTION_MASK_BASE }.getOrNull()
            val shown = p.removePrefix("android.permission.")
            when (level) {
                PermissionInfo.PROTECTION_DANGEROUS -> sensitive.add(shown)
                null -> unknown.add(p)
                else -> other.add(shown)
            }
        }
        val body = StringBuilder("Total ${perms.size} izin.")
        if (sensitive.isNotEmpty()) body.append("\n\nIzin sensitif (perlu persetujuan pengguna, ${sensitive.size}):\n").append(sensitive.joinToString("\n") { "• $it" })
        if (other.isNotEmpty()) body.append("\n\nIzin lain (${other.size}):\n").append(other.joinToString("\n") { "• $it" })
        if (unknown.isNotEmpty()) body.append("\n\nTidak dikenali di HP ini — izin kustom atau milik aplikasi lain (${unknown.size}):\n").append(unknown.joinToString("\n") { "• $it" })
        body.append("\n\nDaftar ini hanya izin yang diminta. Izin sensitif tetap harus kamu setujui saat aplikasi berjalan.")
        sections.add("Izin yang diminta (${perms.size})" to body.toString())
    }

    // ── 4. Penandatangan ──────────────────────────────────────────────────
    val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    if (signers == null || signers.isEmpty()) {
        sections.add(
            "Penandatangan" to
                "Informasi tanda tangan tidak tersedia. APK mungkin belum ditandatangani, tanda tangannya rusak, atau tidak bisa diverifikasi di versi Android ini. " +
                "APK tanpa tanda tangan valid tidak bisa dipasang."
        )
    } else {
        val df = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val body = StringBuilder()
        signers.forEachIndexed { i, sig ->
            val der = sig.toByteArray()
            fun fp(alg: String) = MessageDigest.getInstance(alg).digest(der).joinToString(":") { "%02X".format(it) }
            val cert = runCatching {
                CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            }.getOrNull()
            if (i > 0) body.append("\n\n")
            body.append("Penandatangan ${i + 1}")
            if (cert != null) {
                val subject = cert.subjectX500Principal.name
                val issuer = cert.issuerX500Principal.name
                body.append("\nSubjek: $subject\nPenerbit: $issuer")
                body.append("\nBerlaku: ${df.format(cert.notBefore)} – ${df.format(cert.notAfter)}")
                body.append("\nAlgoritma: ${cert.sigAlgName}")
                if (subject == issuer) body.append("\nSelf-signed: umum untuk APK mana pun dan tidak menunjukkan siapa pengembangnya.")
                if (subject.contains("Android Debug", ignoreCase = true)) body.append("\nSertifikat debug: APK ini ditandatangani dengan kunci uji, bukan kunci rilis.")
                if (Date().after(cert.notAfter)) body.append("\nMasa berlaku sertifikat sudah lewat. Android tetap menerimanya saat pemasangan.")
            } else {
                body.append("\nDetail sertifikat tidak bisa diurai.")
            }
            body.append("\nSHA-256: ${fp("SHA-256")}\nSHA-1: ${fp("SHA-1")}")
        }
        body.append("\n\nSidik jari ini hanya berguna jika kamu membandingkannya dengan yang dipublikasikan pengembang atau dengan versi terpasang yang sudah kamu percaya.")
        sections.add("Penandatangan" to body.toString())
    }

    sections.add("Isi APK" to contents)
    return ApkReport(
        name, size, sha,
        "Metadata terbaca — ini BUKAN penilaian keamanan APK",
        true, sections
    )
}
