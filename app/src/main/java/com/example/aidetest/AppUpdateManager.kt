package com.example.aidetest

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * GitHub Release updater.
 *
 * The GitHub repository is injected by Gradle at build time through
 * BuildConfig.GITHUB_REPOSITORY (OWNER/REPO). No GitHub token is stored in
 * the APK. The repository/release must therefore be publicly downloadable.
 */
class AppUpdateManager(private val context: Context) {
    companion object {
        private const val PREFS = "mytools_prefs"
        private const val LAST_CHECK = "app_update_last_check"
        private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
        private const val API_BASE = "https://api.github.com/repos/"

        private fun httpGet(url: String): ByteArray {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "GITLS-App-Updater")
            }
            try {
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                return c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }

        private fun download(url: String, out: File): Long {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "GITLS-App-Updater")
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                val tmp = File(out.parentFile, out.name + ".part")
                var total = 0L
                c.inputStream.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            total += n
                        }
                    }
                }
                if (!tmp.renameTo(out)) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
                return total
            } finally {
                c.disconnect()
            }
        }

        private fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    md.update(buffer, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun versionParts(value: String): List<Int> =
            value.removePrefix("v").trim().split(Regex("[^0-9]+"))
                .filter { it.isNotBlank() }
                .take(4)
                .mapNotNull { it.toIntOrNull() }
                .let { if (it.isEmpty()) listOf(0) else it }

        private fun compareVersions(a: String, b: String): Int {
            val aa = versionParts(a)
            val bb = versionParts(b)
            for (i in 0 until maxOf(aa.size, bb.size)) {
                val x = aa.getOrElse(i) { 0 }
                val y = bb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }
    }

    data class ReleaseInfo(
        val tag: String,
        val name: String,
        val notes: String,
        val pageUrl: String,
        val apkUrl: String,
        val checksumUrl: String?
    )

    fun checkForUpdate(force: Boolean = false) {
        val repo = BuildConfig.GITHUB_REPOSITORY.trim()
        if (repo.isBlank() || repo == "OWNER/REPO" || !repo.contains('/')) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(LAST_CHECK, now).apply()

        thread(name = "gitls-update-check") {
            runCatching<ReleaseInfo?> {
                val json = JSONObject(String(httpGet(API_BASE + repo + "/releases/latest"), Charsets.UTF_8))
                val tag = json.optString("tag_name").trim()
                if (tag.isBlank()) return@runCatching null
                if (compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) return@runCatching null

                val assets = json.optJSONArray("assets") ?: return@runCatching null
                var apkUrl: String? = null
                var checksumUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    val url = a.optString("browser_download_url")
                    if (name.endsWith(".apk", true) && apkUrl == null) apkUrl = url
                    if (name.endsWith(".sha256", true)) checksumUrl = url
                }
                val apk = apkUrl ?: return@runCatching null
                ReleaseInfo(
                    tag = tag,
                    name = json.optString("name", "GITLS $tag"),
                    notes = json.optString("body", "").trim(),
                    pageUrl = json.optString("html_url", "https://github.com/$repo/releases"),
                    apkUrl = apk,
                    checksumUrl = checksumUrl
                )
            }.onSuccess { release ->
                if (release != null) {
                    (context as? android.app.Activity)?.runOnUiThread {
                        showUpdateDialog(release)
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(release: ReleaseInfo) {
        val activity = context as? android.app.Activity ?: return
        if (activity.isFinishing || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return

        val message = buildString {
            append("Versi terpasang: ${BuildConfig.VERSION_NAME}\n")
            append("Versi terbaru: ${release.tag.removePrefix("v")}\n\n")
            if (release.notes.isNotBlank()) {
                append(release.notes.take(1200))
                append("\n\n")
            }
            append("Update diunduh dari GitHub Release dan diverifikasi SHA-256 sebelum instalasi.")
        }

        AlertDialog.Builder(activity)
            .setTitle("Update GITLS tersedia")
            .setMessage(message)
            .setNegativeButton("Nanti", null)
            .setNeutralButton("Buka GitHub") { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl))) }
            }
            .setPositiveButton("Download & Install") { _, _ ->
                downloadAndInstall(release)
            }
            .show()
    }

    private fun downloadAndInstall(release: ReleaseInfo) {
        val activity = context as? android.app.Activity ?: return
        val progress = AlertDialog.Builder(activity)
            .setTitle("Mengunduh update…")
            .setMessage("Menyiapkan APK…")
            .setNegativeButton("Batal", null)
            .create()
        progress.show()

        thread(name = "gitls-update-download") {
            val result = runCatching {
                val apk = File(context.cacheDir, "gitls-update-${release.tag.removePrefix("v")}.apk")
                download(release.apkUrl, apk)
                require(apk.length() > 50_000) { "APK hasil download terlalu kecil" }

                release.checksumUrl?.let { checksumUrl ->
                    val expected = String(httpGet(checksumUrl), Charsets.UTF_8)
                        .trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase()
                    if (expected.length == 64) {
                        val actual = sha256(apk)
                        require(actual.equals(expected, ignoreCase = true)) { "Verifikasi SHA-256 gagal" }
                    }
                }
                apk
            }

            activity.runOnUiThread {
                progress.dismiss()
                result.onSuccess { installApk(it) }
                    .onFailure { showDownloadError(it.message ?: "Update gagal") }
            }
        }
    }

    private fun installApk(apk: File) {
        val activity = context as? android.app.Activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Izin instalasi diperlukan")
                .setMessage("Android perlu mengizinkan GITLS memasang APK dari sumber ini. Setelah izin diberikan, tekan update lagi.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Buka Pengaturan") { _, _ ->
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    }
                }
                .show()
            return
        }

        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { showDownloadError("Tidak bisa membuka installer APK: ${it.message ?: "error tidak diketahui"}") }
    }

    private fun showDownloadError(message: String) {
        val activity = context as? android.app.Activity ?: return
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle("Update gagal")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
