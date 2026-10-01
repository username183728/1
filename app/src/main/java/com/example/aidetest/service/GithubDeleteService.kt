package com.example.aidetest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import android.net.Uri

/**
 * Foreground worker untuk penghapusan massal GitHub.
 * Activity boleh ditutup; service tetap berjalan dan progress muncul di notification shade.
 */
class GithubDeleteService : Service() {
    companion object {
        const val EXTRA_TOKEN = "token"
        const val EXTRA_OWNER = "owner"
        const val EXTRA_REPO = "repo"
        const val EXTRA_BRANCH = "branch"
        const val EXTRA_PREFIX = "prefix"
        const val ACTION_CANCEL = "com.example.aidetest.GITHUB_DELETE_CANCEL"
        const val CHANNEL_ID = "github_delete_progress"
        const val NOTIFICATION_ID = 24071

        private const val PREFS = "gh_delete_state"
        private const val KEY_JOB = "job_id"
    }

    private val cancelled = AtomicBoolean(false)
    private var jobId = ""
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled.set(true)
            updateState(100, "Dibatalkan", "Penghapusan dibatalkan oleh pengguna.", true, false, "Penghapusan dibatalkan")
            detachForeground()
            stopSelf()
            return START_NOT_STICKY
        }

        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        val owner = intent?.getStringExtra(EXTRA_OWNER).orEmpty()
        val repo = intent?.getStringExtra(EXTRA_REPO).orEmpty()
        val branch = intent?.getStringExtra(EXTRA_BRANCH).orEmpty().ifBlank { "main" }
        val prefix = intent?.getStringExtra(EXTRA_PREFIX).orEmpty()
        if (token.isBlank() || owner.isBlank() || repo.isBlank()) {
            updateState(100, "Gagal", "Informasi GitHub tidak lengkap.", true, false, "Penghapusan gagal: data GitHub tidak lengkap")
            stopSelf()
            return START_NOT_STICKY
        }

        jobId = "$owner/$repo/$branch/${prefix.trim('/')}"
        updateState(0, "Menyiapkan penghapusan…", "Menghubungkan ke GitHub…", false, false, "")
        startForeground(NOTIFICATION_ID, buildNotification(0, "Menyiapkan penghapusan…", "Menghubungkan ke GitHub…", false))

        Thread {
            try {
                deleteTree(owner, repo, branch, prefix, token)
                updateState(100, "Selesai", "Semua file berhasil dihapus dan diverifikasi.", true, true, "Semua file berhasil dihapus")
                updateNotification(100, "Selesai", "Semua file berhasil dihapus", true)
            } catch (e: CancelledException) {
                updateState(0, "Dibatalkan", "Penghapusan dihentikan.", true, false, "Penghapusan dibatalkan")
                updateNotification(0, "Dibatalkan", "Penghapusan dihentikan", true)
            } catch (e: Exception) {
                val msg = e.message.orEmpty().ifBlank { "Terjadi kesalahan" }.take(180)
                updateState(0, "Gagal", msg, true, false, "Penghapusan gagal: $msg")
                updateNotification(0, "Gagal", msg, true)
            } finally {
                detachForeground()
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled.set(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** STOP_FOREGROUND_DETACH baru ada di API 24; minSdk proyek adalah 23. */
    @Suppress("DEPRECATION")
    private fun detachForeground() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else stopForeground(false)
    }

    private fun deleteTree(owner: String, repo: String, branch: String, prefix: String, token: String) {
        progress(5, "Menghubungkan ke GitHub", "Membaca branch $branch…")
        val base = "https://api.github.com/repos/${Uri.encode(owner)}/${Uri.encode(repo)}"
        try {
            val ref = requestRetry("GET", "$base/git/ref/heads/${enc(branch)}", null, token)
            val parentSha = ref.optJSONObject("object")?.optString("sha").orEmpty()
            require(parentSha.isNotBlank()) { "Branch $branch tidak ditemukan" }
            progress(15, "Membaca repository", "Mendapatkan commit terbaru…")
            val commit = requestRetry("GET", "$base/git/commits/$parentSha", null, token)
            val baseTree = commit.optJSONObject("tree")?.optString("sha").orEmpty()
            require(baseTree.isNotBlank()) { "Tree repository tidak ditemukan" }
            progress(25, "Membaca daftar file", "Menghitung semua file yang akan dihapus…")
            val treeJson = JSONObject(requestRaw("$base/git/trees/${enc(baseTree)}?recursive=1", token))
            if (treeJson.optBoolean("truncated", false)) throw IOException("GitHub HTTP 404: daftar file terlalu besar")
            val tree = treeJson.optJSONArray("tree") ?: JSONArray()
            val normalized = prefix.trim('/').let { if (it.isBlank()) "" else "$it/" }
            val deletes = JSONArray()
            for (i in 0 until tree.length()) {
                checkCancelled()
                val obj = tree.getJSONObject(i)
                val path = obj.optString("path")
                if (obj.optString("type") == "blob" && (normalized.isBlank() || path.startsWith(normalized))) {
                    deletes.put(JSONObject().put("path", path).put("mode", obj.optString("mode", "100644")).put("type", "blob").put("sha", JSONObject.NULL))
                }
            }
            if (deletes.length() > 0) {
                progress(42, "Menyiapkan penghapusan", "${deletes.length()} file ditemukan. Membuat perubahan…")
                val newTree = requestRetry("POST", "$base/git/trees", JSONObject().put("base_tree", baseTree).put("tree", deletes), token).optString("sha")
                require(newTree.isNotBlank()) { "Gagal membuat tree penghapusan" }
                progress(65, "Membuat commit", "Menyimpan penghapusan ke GitHub…")
                val message = if (prefix.isBlank()) "Delete all files via GITLS" else "Delete $prefix via GITLS"
                val newCommit = requestRetry("POST", "$base/git/commits", JSONObject().put("message", message).put("tree", newTree).put("parents", JSONArray().put(parentSha)), token).optString("sha")
                require(newCommit.isNotBlank()) { "Gagal membuat commit penghapusan" }
                progress(82, "Memperbarui branch", "Menerapkan commit ke $branch…")
                requestRetry("PATCH", "$base/git/refs/heads/${enc(branch)}", JSONObject().put("sha", newCommit).put("force", false), token)
                progress(94, "Memverifikasi", "Memastikan commit sudah masuk ke branch…")
                val verify = requestRetry("GET", "$base/git/ref/heads/${enc(branch)}", null, token)
                require(verify.optJSONObject("object")?.optString("sha") == newCommit) { "Verifikasi commit penghapusan gagal" }
                progress(100, "Selesai", "Semua file berhasil dihapus dan commit sudah diverifikasi.")
            } else {
                throw IOException("Tidak ada file untuk dihapus")
            }
        } catch (e: IOException) {
            if (e.message.orEmpty().contains("GitHub HTTP 404") && !cancelled.get()) {
                deleteViaContents(owner, repo, branch, prefix, token)
            } else throw e
        }
    }

    private fun deleteViaContents(owner: String, repo: String, branch: String, prefix: String, token: String) {
        progress(10, "Mode kompatibilitas", "Git Data API tidak tersedia. Menghapus per file…")
        val files = mutableListOf<Pair<String, String>>()
        collectContents(owner, repo, branch, prefix.trim('/'), token, files)
        require(files.isNotEmpty()) { "Tidak ada file untuk dihapus" }
        progress(18, "File ditemukan", "${files.size} file siap dihapus.")
        val base = "https://api.github.com/repos/${Uri.encode(owner)}/${Uri.encode(repo)}"
        files.forEachIndexed { index, pair ->
            checkCancelled()
            val (path, sha) = pair
            val body = JSONObject().put("message", "Delete $path via GITLS").put("sha", sha).put("branch", branch)
            requestRetry("DELETE", "$base/contents/${enc(path)}", body, token)
            val pct = 18 + (((index + 1).toDouble() / files.size.toDouble()) * 78.0).toInt()
            progress(pct, "Menghapus file", "${index + 1} dari ${files.size}: $path")
            Thread.sleep(250L)
        }
        progress(100, "Selesai", "${files.size} file berhasil dihapus.")
    }

    private fun collectContents(owner: String, repo: String, branch: String, prefix: String, token: String, out: MutableList<Pair<String, String>>) {
        checkCancelled()
        val base = "https://api.github.com/repos/${Uri.encode(owner)}/${Uri.encode(repo)}"
        val endpoint = "$base/contents/${if (prefix.isBlank()) "" else enc(prefix)}?ref=${Uri.encode(branch)}"
        val json = JSONArray(requestRaw(endpoint, token))
        for (i in 0 until json.length()) {
            checkCancelled()
            val item = json.getJSONObject(i)
            val path = item.optString("path")
            when (item.optString("type")) {
                "file", "symlink" -> item.optString("sha").takeIf { it.isNotBlank() }?.let { out += path to it }
                "dir" -> collectContents(owner, repo, branch, path, token, out)
            }
        }
    }

    private fun checkCancelled() { if (cancelled.get()) throw CancelledException() }

    private fun progress(pct: Int, title: String, detail: String) {
        updateState(pct, title, detail, false, false, "")
        updateNotification(pct, title, detail, false)
    }

    private fun updateState(pct: Int, title: String, detail: String, finished: Boolean, success: Boolean, result: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_JOB, jobId).putInt("progress", pct).putString("title", title).putString("detail", detail)
            .putBoolean("finished", finished).putBoolean("success", success).putString("result", result).apply()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Proses GitHub", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress operasi GitHub GITLS"
                setShowBadge(true)
            })
        }
    }

    private fun buildNotification(pct: Int, title: String, detail: String, done: Boolean): Notification {
        val cancelIntent = PendingIntent.getService(this, 24072, Intent(this, GithubDeleteService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent = PendingIntent.getActivity(this, 24073, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.example.aidetest.R.drawable.ic_bell)
            .setContentTitle("GITLS • $title")
            .setContentText(detail)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setAutoCancel(done)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (!done) {
            builder.setProgress(100, pct.coerceIn(0, 100), false).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Batalkan", cancelIntent)
        } else builder.setProgress(0, 0, false)
        return builder.build()
    }

    private fun updateNotification(pct: Int, title: String, detail: String, done: Boolean) {
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(pct, title, detail, done))
    }

    private fun requestRaw(url: String, token: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "GITLS-Android")
        }
        try {
            val code = c.responseCode
            val response = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(response).optString("message") }.getOrDefault(response.take(240))
                throw IOException("GitHub HTTP $code: ${msg.ifBlank { "Request gagal" }}")
            }
            return response
        } finally { c.disconnect() }
    }

    private fun requestRetry(method: String, url: String, body: JSONObject?, token: String, attempts: Int = 3): JSONObject {
        var last: IOException? = null
        for (i in 1..attempts) {
            checkCancelled()
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method; connectTimeout = 20_000; readTimeout = 60_000; doOutput = body != null
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    setRequestProperty("User-Agent", "GITLS-Android")
                }
                try {
                    if (body != null) c.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
                    val code = c.responseCode
                    val response = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) {
                        val msg = runCatching { JSONObject(response).optString("message") }.getOrDefault(response.take(240))
                        throw IOException("GitHub HTTP $code: ${msg.ifBlank { "Request gagal" }}")
                    }
                    return if (response.isBlank()) JSONObject() else JSONObject(response)
                } finally { c.disconnect() }
            } catch (e: IOException) {
                if (e is CancelledException) throw e
                val msg = e.message.orEmpty()
                val transient = msg.startsWith("GitHub HTTP 429") || (msg.startsWith("GitHub HTTP 403") && msg.contains("rate limit", true)) || (msg.startsWith("GitHub HTTP 409") && (method == "DELETE" || method == "PUT"))
                if (msg.startsWith("GitHub HTTP 4") && !transient) throw e
                last = e
                if (i < attempts) Thread.sleep(if (transient) 4000L * i else 700L * i)
            }
        }
        throw last ?: IOException("Request gagal")
    }

    private fun enc(value: String): String = value.split('/').filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }

    private class CancelledException : IOException()
}
