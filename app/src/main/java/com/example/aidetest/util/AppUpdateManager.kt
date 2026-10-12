package com.example.aidetest

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Safe, check-only GitHub Release updater.
 *
 * GITLS never installs an APK itself and does not request
 * the APK-install permission. Users can review the release in GitHub and
 * install updates through their normal trusted distribution channel.
 */
class AppUpdateManager(private val context: Context) {
    companion object {
        private const val PREFS = "mytools_prefs"
        private const val LAST_CHECK = "app_update_last_check"
        private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
        private const val API_BASE = "https://api.github.com/repos/"

        private fun httpGet(url: String): String {
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
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
        }

        private fun versionParts(value: String): List<Int> =
            value.removePrefix("v").trim().split(Regex("[^0-9]+"))
                .filter { it.isNotBlank() }.take(4).mapNotNull { it.toIntOrNull() }
                .let { if (it.isEmpty()) listOf(0) else it }

        private fun compareVersions(a: String, b: String): Int {
            val aa = versionParts(a); val bb = versionParts(b)
            for (i in 0 until maxOf(aa.size, bb.size)) {
                val x = aa.getOrElse(i) { 0 }; val y = bb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }
    }

    data class ReleaseInfo(val tag: String, val name: String, val notes: String, val pageUrl: String)

    fun checkForUpdate(force: Boolean = false) {
        val repo = BuildConfig.GITHUB_REPOSITORY.trim()
        if (repo.isBlank() || repo == "OWNER/REPO" || !repo.contains('/')) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(LAST_CHECK, now).apply()

        thread(name = "gitls-update-check") {
            runCatching<ReleaseInfo?> {
                val json = JSONObject(httpGet(API_BASE + repo + "/releases/latest"))
                val tag = json.optString("tag_name").trim()
                if (tag.isBlank() || compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) return@runCatching null
                ReleaseInfo(
                    tag = tag,
                    name = json.optString("name", "GITLS $tag"),
                    notes = json.optString("body", "").trim(),
                    pageUrl = json.optString("html_url", "https://github.com/$repo/releases")
                )
            }.onSuccess { release ->
                if (release != null) (context as? android.app.Activity)?.let { activity ->
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !(android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) {
                            showUpdateDialog(release)
                        }
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(release: ReleaseInfo) {
        val activity = context as? android.app.Activity ?: return
        if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return
        val message = buildString {
            append("Versi terpasang: ${BuildConfig.VERSION_NAME}\n")
            append("Versi terbaru: ${release.tag.removePrefix("v")}\n\n")
            if (release.notes.isNotBlank()) append(release.notes.take(1200))
            append("\n\nUpdate dibuka melalui halaman resmi GitHub Release. GITLS tidak memasang APK secara otomatis.")
        }
        AlertDialog.Builder(activity)
            .setTitle("Update GITLS tersedia")
            .setMessage(message)
            .setNegativeButton("Nanti", null)
            .setPositiveButton("Buka GitHub") { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl))) }
            }.show()
    }
}
