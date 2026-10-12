package com.example.aidetest

import android.content.*
import android.net.Uri
import android.util.Base64
import com.example.aidetest.GhCancelException
import com.example.aidetest.GhProgress
import com.example.aidetest.GhResult
import com.example.aidetest.GithubZipAnalysis
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import java.util.zip.*
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lapisan jaringan & data GitHub (request, retry, push, hapus tree, helper error).
 * Dipindah dari MainActivity (modularisasi bertahap). Memakai extension receiver
 * agar pemanggil di MainActivity tidak perlu diubah.
 */

internal fun MainActivity.ghFormatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> "%.2f GB".format(Locale.US, bytes / gb)
        bytes >= mb -> "%.1f MB".format(Locale.US, bytes / mb)
        bytes >= kb -> "%.1f KB".format(Locale.US, bytes / kb)
        else -> "$bytes B"
    }
}

/** Pesan error khusus penghapusan (tambahan 409, rate limit, branch protection). */
internal fun MainActivity.ghDeleteError(e: Throwable): String {
    val raw = e.message ?: "unknown error"
    val m = Regex("GitHub HTTP (\\d+): (.*)", RegexOption.DOT_MATCHES_ALL).find(raw) ?: return raw
    val code = m.groupValues[1].toInt()
    val detail = m.groupValues[2].trim()
    val hint = when {
        code == 401 -> "Token tidak valid atau kedaluwarsa. Buat token baru lalu simpan lagi."
        code == 403 && detail.contains("rate limit", true) -> "Terkena batas kecepatan GitHub. Tunggu beberapa menit lalu coba lagi."
        code == 403 -> "Token tidak punya izin tulis. Classic PAT butuh scope repo; fine-grained butuh Contents: Read and write. Branch yang diproteksi juga menolak penghapusan langsung."
        code == 404 -> "Repo, branch, atau file tidak ditemukan. Untuk repo private, pastikan token punya akses ke repo tersebut."
        code == 409 -> "Terjadi konflik commit (branch berubah saat proses). Coba lagi."
        code == 422 -> "File berubah di server (SHA tidak cocok) atau data tidak valid. Muat ulang daftar file lalu coba lagi."
        else -> ""
    }
    return if (hint.isBlank()) raw else "$hint\n\n(HTTP $code: $detail)"
}

internal fun MainActivity.intentJobId(token: String, prefix: String): String =
    "${ghUserValue}/${ghRepoValue}/${ghBranch}/${prefix.trim('/')}"

/**
 * Upload satu file dari Bit Chat melalui GitHub Contents API.
 * Dipakai hanya setelah pengguna memilih file dan secara eksplisit memberi perintah upload.
 */
internal fun MainActivity.ghUploadSingleFileToGithub(uri: Uri, path: String, token: String) {
    val clean = path.trim('/').replace(Regex("/{2,}"), "/")
    require(clean.isNotBlank()) { "Path file kosong" }

    val bytes = contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > 10L * 1024L * 1024L) {
                throw IOException("File terlalu besar untuk upload langsung dari chat (maksimal 10 MB). Gunakan GitHub Publisher untuk file besar.")
            }
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    } ?: throw IOException("File tidak dapat dibaca")

    require(bytes.isNotEmpty()) { "File kosong atau tidak dapat dibaca" }

    val base = "https://api.github.com/repos/${Uri.encode(ghUserValue)}/${Uri.encode(ghRepoValue)}"
    val url = "$base/contents/${encodePath(clean)}"
    val existingSha = runCatching {
        val info = JSONObject(githubRequestRaw("$url?ref=${Uri.encode(ghBranch)}", token))
        info.optString("sha").takeIf { it.isNotBlank() }
    }.getOrNull()

    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val body = JSONObject()
        .put("message", "Upload $clean via GITLS Bit")
        .put("content", encoded)
        .put("branch", ghBranch)
    existingSha?.let { body.put("sha", it) }

    githubRequestRetry("PUT", url, body, ghHeaders(token))
}

internal fun MainActivity.ghDeleteSingleFile(path: String, token: String) {
    val clean = path.trim('/')
    val base = "https://api.github.com/repos/${Uri.encode(ghUserValue)}/${Uri.encode(ghRepoValue)}"
    val url = "$base/contents/${encodePath(clean)}"
    val info = JSONObject(githubRequestRaw("$url?ref=${Uri.encode(ghBranch)}", token))
    val sha = info.optString("sha")
    require(sha.isNotBlank()) { "SHA file tidak ditemukan: $clean" }
    val body = JSONObject()
        .put("message", "Delete $clean via GITLS")
        .put("sha", sha)
        .put("branch", ghBranch)
    githubRequestRetry("DELETE", url, body, ghHeaders(token))
}

internal fun MainActivity.ghDeleteTreePrefix(
    prefix: String,
    token: String,
    onProgress: (Int, String, String) -> Unit = { _, _, _ -> }
) {
    try {
        ghDeleteTreePrefixGitData(prefix, token, onProgress)
    } catch (e: IOException) {
        if (e.message.orEmpty().contains("GitHub HTTP 404")) {
            onProgress(8, "Menggunakan mode kompatibilitas", "Git Data API tidak tersedia. Beralih ke penghapusan per file…")
            ghDeleteTreePrefixViaContents(prefix, token, onProgress)
        } else {
            throw e
        }
    }
}

internal fun MainActivity.ghDeleteTreePrefixGitData(
    prefix: String,
    token: String,
    onProgress: (Int, String, String) -> Unit = { _, _, _ -> }
) {
    onProgress(5, "Menghubungkan ke GitHub", "Membaca branch ${ghBranch}…")
    val base = "https://api.github.com/repos/${Uri.encode(ghUserValue)}/${Uri.encode(ghRepoValue)}"
    val ref = githubRequestRetry("GET", "$base/git/ref/heads/${encodePath(ghBranch)}", null, ghHeaders(token))
    val parentSha = ref.optJSONObject("object")?.optString("sha").orEmpty()
    require(parentSha.isNotBlank()) { "Branch $ghBranch tidak ditemukan" }
    onProgress(15, "Membaca repository", "Mendapatkan commit terbaru…")
    val commit = githubRequestRetry("GET", "$base/git/commits/$parentSha", null, ghHeaders(token))
    val baseTree = commit.optJSONObject("tree")?.optString("sha").orEmpty()
    require(baseTree.isNotBlank()) { "Tree repository tidak ditemukan" }
    // Endpoint /git/trees membutuhkan SHA tree, bukan SHA commit.
    // Sebelumnya parentSha dipakai di sini sehingga penghapusan massal dapat berakhir HTTP 404.
    onProgress(25, "Membaca daftar file", "Menghitung semua file yang akan dihapus…")
    val treeJson = JSONObject(githubRequestRaw("$base/git/trees/${Uri.encode(baseTree)}?recursive=1", token))
    // Repo sangat besar membuat listing rekursif terpotong; menghapus dari listing parsial
    // akan meninggalkan file. Lempar 404 sintetis agar ghDeleteTreePrefix memakai jalur Contents API.
    if (treeJson.optBoolean("truncated", false)) throw IOException("GitHub HTTP 404: daftar file terlalu besar (terpotong), memakai jalur Contents API")
    val tree = treeJson.optJSONArray("tree") ?: JSONArray()
    val normalized = prefix.trim('/').let { if (it.isBlank()) "" else "$it/" }
    val deletes = JSONArray()
    for (i in 0 until tree.length()) {
        val obj = tree.getJSONObject(i)
        val path = obj.optString("path")
        if (obj.optString("type") == "blob" && (normalized.isBlank() || path.startsWith(normalized))) {
            deletes.put(JSONObject()
                .put("path", path)
                .put("mode", obj.optString("mode", "100644"))
                .put("type", "blob")
                .put("sha", JSONObject.NULL))
        }
    }
    require(deletes.length() > 0) { "Tidak ada file untuk dihapus" }
    onProgress(42, "Menyiapkan penghapusan", "${deletes.length()} file ditemukan. Membuat perubahan…")
    val newTree = githubRequestRetry("POST", "$base/git/trees", JSONObject().put("base_tree", baseTree).put("tree", deletes), ghHeaders(token)).optString("sha")
    require(newTree.isNotBlank()) { "Gagal membuat tree penghapusan" }
    onProgress(65, "Membuat commit", "Menyimpan penghapusan ke GitHub…")
    val newCommit = githubRequestRetry("POST", "$base/git/commits", JSONObject().put("message", if (prefix.isBlank()) "Delete all files via GITLS" else "Delete $prefix via GITLS").put("tree", newTree).put("parents", JSONArray().put(parentSha)), ghHeaders(token)).optString("sha")
    require(newCommit.isNotBlank()) { "Gagal membuat commit penghapusan" }
    onProgress(82, "Memperbarui branch", "Menerapkan commit ke ${ghBranch}…")
    githubRequestRetry("PATCH", "$base/git/refs/heads/${encodePath(ghBranch)}", JSONObject().put("sha", newCommit).put("force", false), ghHeaders(token))
    onProgress(94, "Memverifikasi", "Memastikan commit sudah masuk ke branch…")
    val verify = githubRequestRetry("GET", "$base/git/ref/heads/${encodePath(ghBranch)}", null, ghHeaders(token))
    require(verify.optJSONObject("object")?.optString("sha") == newCommit) { "Verifikasi commit penghapusan gagal" }
    onProgress(100, "Selesai", "Semua file berhasil dihapus dan commit sudah diverifikasi.")
}

/**
 * Fallback untuk token/repository yang tidak menerima Git Data API.
 * Contents API dihapus satu per satu menggunakan SHA yang didapat dari
 * listing awal. Folder GitHub tidak mempunyai object tersendiri, sehingga
 * folder akan hilang otomatis setelah seluruh blob di dalamnya terhapus.
 */
internal fun MainActivity.ghDeleteTreePrefixViaContents(
    prefix: String,
    token: String,
    onProgress: (Int, String, String) -> Unit = { _, _, _ -> }
) {
    onProgress(10, "Mencari file", "Membaca isi repository secara bertahap…")
    val files = mutableListOf<Pair<String, String>>()
    ghCollectGithubContentFiles(prefix.trim('/'), token, files)

    require(files.isNotEmpty()) { "Tidak ada file untuk dihapus" }
    onProgress(18, "File ditemukan", "${files.size} file siap dihapus.")

    val base = "https://api.github.com/repos/${Uri.encode(ghUserValue)}/${Uri.encode(ghRepoValue)}"
    files.forEachIndexed { index, pair ->
        val (path, sha) = pair
        if (ghCancelled) throw GhCancelException()
        val url = "$base/contents/${encodePath(path)}"
        val body = JSONObject()
            .put("message", "Delete $path via GITLS")
            .put("sha", sha)
            .put("branch", ghBranch)

        githubRequestRetry("DELETE", url, body, ghHeaders(token))
        val pct = 18 + (((index + 1).toDouble() / files.size.toDouble()) * 78.0).roundToInt()
        onProgress(pct, "Menghapus file", "${index + 1} dari ${files.size}: $path")
        Thread.sleep(250L) // hindari secondary rate limit GitHub pada penghapusan beruntun
    }
    onProgress(100, "Selesai", "${files.size} file berhasil dihapus.")
}

/**
 * Mengumpulkan semua blob/symlink di sebuah folder secara rekursif.
 */
internal fun MainActivity.ghCollectGithubContentFiles(
    prefix: String,
    token: String,
    out: MutableList<Pair<String, String>>
) {
    val base = "https://api.github.com/repos/${Uri.encode(ghUserValue)}/${Uri.encode(ghRepoValue)}"
    val endpoint = "$base/contents/${if (prefix.isBlank()) "" else encodePath(prefix)}?ref=${Uri.encode(ghBranch)}"
    val json = githubRequestRaw(endpoint, token)

    val root = json.trimStart()
    if (!root.startsWith("[")) {
        val item = JSONObject(json)
        val itemPath = item.optString("path").ifBlank { prefix }
        val type = item.optString("type")
        val sha = item.optString("sha")
        if (type == "dir") {
            ghCollectGithubContentFiles(itemPath, token, out)
        } else if (sha.isNotBlank()) {
            out += itemPath to sha
        }
        return
    }

    val items = JSONArray(json)
    for (i in 0 until items.length()) {
        val item = items.getJSONObject(i)
        val itemPath = item.optString("path")
        if (itemPath.isBlank()) continue

        when (item.optString("type")) {
            "dir" -> ghCollectGithubContentFiles(itemPath, token, out)
            else -> {
                val sha = item.optString("sha")
                if (sha.isNotBlank()) out += itemPath to sha
            }
        }
    }
}

internal fun MainActivity.githubRequestRaw(url: String, token: String): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 20_000
        readTimeout = 60_000
        setRequestProperty("Authorization", "Bearer $token")
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("User-Agent", "GITLS-Android")
    }
    try {
        val code = connection.responseCode
        val response = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(response).optString("message") }.getOrDefault(response.take(240))
            throw IOException("GitHub HTTP $code: ${message.ifBlank { "Request gagal" }}")
        }
        return response
    } finally { connection.disconnect() }
}

internal fun MainActivity.ghIsTokenAuthError(error: Throwable): Boolean {
    val raw = error.message.orEmpty()
    return raw.contains("HTTP 401", ignoreCase = true)
}

internal fun MainActivity.ghIsNetworkError(error: Throwable): Boolean {
    var current: Throwable? = error
    repeat(4) {
        if (current is java.net.UnknownHostException ||
            current is java.net.ConnectException ||
            current is java.net.SocketTimeoutException ||
            current is java.net.NoRouteToHostException ||
            current is java.net.SocketException) return true
        current = current?.cause
    }
    val raw = error.message.orEmpty()
    return raw.contains("Unable to resolve host", true) ||
        raw.contains("Network is unreachable", true) ||
        raw.contains("No route to host", true)
}

internal fun MainActivity.ghInternetAvailable(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** File yang tidak boleh ikut terunggah secara default (rahasia / artefak build besar). */
internal fun ghIsSensitivePath(rel: String): Boolean {
    val p = rel.replace('\\', '/').trim('/')
    val name = p.substringAfterLast('/').lowercase(Locale.ROOT)
    if (name == ".env" || name.startsWith(".env.") || name == "local.properties") return true
    if (name.endsWith(".jks") || name.endsWith(".keystore") || name.endsWith(".p12") || name.endsWith(".pfx")) return true
    if (name == "id_rsa" || name == "id_ed25519" || name == "id_ecdsa") return true
    val parts = p.split('/')
    return parts.any { it == ".gradle" || it == "node_modules" }
}

internal fun ghFileMode(rel: String): String {
    val name = rel.substringAfterLast('/')
    return if (name == "gradlew" || name.endsWith(".sh")) "100755" else "100644"
}

/** Menyamarkan token GitHub (ghp_, gho_, ghu_, ghs_, ghr_, github_pat_) dan header Bearer pada teks apa pun. */
internal fun ghMaskSecrets(text: String): String {
    var out = text
    out = Regex("github_pat_[A-Za-z0-9_]{10,}").replace(out) { m -> m.value.take(11) + "••••" }
    out = Regex("gh[pousr]_[A-Za-z0-9]{10,}").replace(out) { m -> m.value.take(4) + "••••" }
    out = Regex("(?i)(bearer|token)\\s+[A-Za-z0-9._-]{12,}").replace(out) { m -> m.groupValues[1] + " ••••" }
    return out
}

/** Bentuk aman untuk menampilkan token: hanya 4 karakter awal dan 2 karakter akhir. */
internal fun ghMaskToken(token: String): String {
    val t = token.trim()
    if (t.isEmpty()) return "(kosong)"
    if (t.length <= 8) return "••••"
    return t.take(4) + "••••••" + t.takeLast(2)
}

internal fun MainActivity.ghFriendlyError(error: Throwable): String = ghMaskSecrets(ghFriendlyErrorRaw(error))

private fun MainActivity.ghFriendlyErrorRaw(error: Throwable): String {
    val raw = error.message ?: error.javaClass.simpleName
    return when {
        raw.contains("fast forward", true) || raw.contains("fast-forward", true) ->
            "Branch di GitHub berubah saat upload berjalan (bukan fast-forward). Jalankan upload sekali lagi. ($raw)"
        raw.contains("HTTP 401") -> "Token GitHub ditolak atau kedaluwarsa. Perbarui token pada Pengaturan GitHub. ($raw)"
        raw.contains("HTTP 403") -> "Akses ditolak. Pastikan token punya izin Contents read/write untuk repository ini. ($raw)"
        raw.contains("HTTP 404") -> "Repository atau branch tidak ditemukan, atau token tidak punya akses. ($raw)"
        raw.contains("HTTP 422") -> "GitHub menolak data yang dikirim. ($raw)"
        ghIsNetworkError(error) -> "Tidak bisa menjangkau GitHub. Periksa koneksi internet lalu coba lagi."
        else -> raw
    }
}

// =====================================================================
// Mesin upload: satu jalur untuk ZIP dan folder
// =====================================================================
internal fun MainActivity.ghHeaders(token: String) = mapOf(
    "Authorization" to "Bearer $token",
    "Accept" to "application/vnd.github+json",
    "X-GitHub-Api-Version" to "2022-11-28",
    "User-Agent" to "GITLS-Android"
)

/** Request dengan percobaan ulang untuk gangguan jaringan (bukan untuk error 4xx). */
internal fun MainActivity.githubRequestRetry(method: String, url: String, body: JSONObject?, headers: Map<String, String>, attempts: Int = 3, bodyFile: File? = null): JSONObject {
    var last: IOException? = null
    for (i in 1..attempts) {
        if (ghCancelled) throw GhCancelException()
        try {
            return githubRequest(method, url, body, headers, bodyFile)
        } catch (e: IOException) {
            val message = e.message.orEmpty()
            // Secondary rate limit (403/429) dan konflik commit beruntun (409 pada DELETE/PUT
            // Contents API) bersifat sementara, jadi dicoba ulang dengan jeda bertahap.
            val transient4xx = message.startsWith("GitHub HTTP 429") ||
                (message.startsWith("GitHub HTTP 403") && message.contains("rate limit", ignoreCase = true)) ||
                (message.startsWith("GitHub HTTP 409") && (method == "DELETE" || method == "PUT"))
            if (message.startsWith("GitHub HTTP 4") && !transient4xx) throw e
            last = e
            val retryAfterSec = Regex("retry-after=(\\d+)").find(message)?.groupValues?.get(1)?.toLongOrNull()
            val waitMs = if (transient4xx) (retryAfterSec?.coerceIn(1L, 90L)?.times(1000L) ?: (4000L * i)) else 700L * i
            if (i < attempts) Thread.sleep(waitMs)
        }
    }
    throw last ?: IOException("Request gagal")
}

// ---------- Pola pengecualian upload (mis. md, txt, *.test.js, docs/) ----------

/** Pisahkan input pengguna: koma, titik koma, spasi, atau baris baru. */
internal fun githubParseExcludePatterns(raw: String): List<String> =
    raw.split(',', ';', '\n', ' ', '\t')
        .map { it.trim().trim('"', '\'') }
        .filter { it.isNotEmpty() }
        .distinct()

internal fun MainActivity.githubExcludePatterns(): List<String> =
    githubParseExcludePatterns(prefs.getString("gh_exclude_patterns", "") ?: "")

internal fun MainActivity.githubSaveExcludePatterns(patterns: Collection<String>) {
    prefs.edit().putString("gh_exclude_patterns", patterns.distinct().joinToString(",")).apply()
}

// Aturan pola (tidak peka huruf besar/kecil):
//  - "md" atau ".md"       -> semua file berekstensi .md
//  - "readme.md"           -> file bernama persis itu di folder mana pun
//  - "*.test.js", "a?.txt" -> wildcard pada nama file (dua bintang = lintas folder)
//  - "docs/"               -> semua file di folder bernama docs (di kedalaman mana pun)
//  - "src/*.md"            -> wildcard terhadap path lengkap
// Catatan: jangan pakai blok komentar untuk contoh ini; "/*" di dalam komentar Kotlin membuka komentar bersarang.
internal fun githubExcludeMatches(patternRaw: String, relPath: String): Boolean {
    val p = patternRaw.trim().replace('\\', '/').lowercase(Locale.ROOT)
    if (p.isEmpty()) return false
    val path = relPath.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)
    val segments = path.split('/')
    val name = segments.last()
    if (p.endsWith("/")) {
        val dir = p.trim('/')
        return dir.isNotEmpty() && segments.dropLast(1).any { it == dir }
    }
    val hasWildcard = p.contains('*') || p.contains('?')
    if (!hasWildcard && !p.contains('/')) {
        return when {
            p.startsWith(".") -> name.endsWith(p)
            !p.contains('.') -> name == p || name.endsWith(".$p")
            else -> name == p
        }
    }
    val rx = StringBuilder()
    var i = 0
    while (i < p.length) {
        val c = p[i]
        when {
            c == '*' && i + 1 < p.length && p[i + 1] == '*' -> { rx.append(".*"); i++ }
            c == '*' -> rx.append("[^/]*")
            c == '?' -> rx.append("[^/]")
            else -> rx.append(Regex.escape(c.toString()))
        }
        i++
    }
    val target = if (p.contains('/')) path else name
    return runCatching { Regex(rx.toString()).matches(target) }.getOrDefault(false)
}

internal fun githubPathExcludedByPatterns(patterns: List<String>, relPath: String): Boolean =
    patterns.any { githubExcludeMatches(it, relPath) }

internal fun MainActivity.pushFilesToGitHub(
    allFiles: List<Pair<String, File>>, owner: String, repo: String, token: String, branch: String,
    commitMessage: String, createPrivate: Boolean, progress: (GhProgress) -> Unit,
    onlyChanged: Boolean = false, deleteMissing: Boolean = false, dryRun: Boolean = false
): GhResult {
    // File rahasia (.env, keystore, local.properties, dst.) dan folder .gradle/node_modules tidak diunggah.
    // Pola pengecualian pengguna (mis. md, txt) berlaku untuk upload ZIP maupun folder.
    val excludePatterns = githubExcludePatterns()
    val patternFiltered = if (excludePatterns.isEmpty()) allFiles
        else allFiles.filter { !githubPathExcludedByPatterns(excludePatterns, it.first) }
    val skippedByPattern = allFiles.size - patternFiltered.size
    val skippedSensitive = patternFiltered.count { ghIsSensitivePath(it.first) }
    val files = patternFiltered.filter { !ghIsSensitivePath(it.first) }
    require(files.isNotEmpty()) {
        if (skippedByPattern > 0) "Semua file dikecualikan oleh pola (${excludePatterns.joinToString(", ")}). Ubah pola di Kelola isi."
        else "Tidak ada file yang dapat di-upload"
    }
    require(files.size <= 3000) { "Maksimal 3000 file per upload" }
    // Validasi ukuran SEBELUM ada request jaringan, agar tidak gagal setelah ratusan blob terunggah.
    run {
        val maxSingle = 25L * 1024L * 1024L
        val maxTotal = 150L * 1024L * 1024L
        var sum = 0L
        for ((rel, f) in files) {
            val size = f.length()
            require(size <= maxSingle) { "File terlalu besar untuk upload aman dari HP: $rel (${size / 1024L / 1024L} MB). Maksimal 25 MB per file." }
            sum += size
        }
        require(sum <= maxTotal) { "Total upload terlalu besar untuk proses aman di HP. Maksimal 150 MB per upload." }
    }
    if (skippedByPattern > 0) progress(GhProgress(0, "$skippedByPattern file dikecualikan oleh pola (${excludePatterns.joinToString(", ")})"))
    if (skippedSensitive > 0) progress(GhProgress(0, "$skippedSensitive file sensitif/artefak dilewati (.env, keystore, local.properties, .gradle, node_modules)"))
    val base = "https://api.github.com/repos/${Uri.encode(owner)}/${Uri.encode(repo)}"
    val headers = ghHeaders(token)

    // 1. hubungkan & periksa token
    progress(GhProgress(0, "Memeriksa token…"))
    val me = try {
        githubRequestRetry("GET", "https://api.github.com/user", null, headers)
    } catch (e: IOException) {
        if (e.message.orEmpty().contains("HTTP 401")) throw e else JSONObject()
    }
    val login = me.optString("login")
    progress(GhProgress(0, if (login.isNotBlank()) "Terhubung sebagai $login" else "Terhubung"))

    // 2. repository
    progress(GhProgress(1, "Memeriksa $owner/$repo…"))
    val repoInfo: JSONObject? = try {
        githubRequestRetry("GET", base, null, headers)
    } catch (e: IOException) {
        if (e.message.orEmpty().contains("HTTP 404")) null else throw e
    }
    var created = false
    var htmlUrl = repoInfo?.optString("html_url").orEmpty()
    var defaultBranch = repoInfo?.optString("default_branch").orEmpty()
    if (repoInfo == null) {
        if (dryRun) throw IOException("Dry Run hanya bisa memeriksa repository yang sudah ada. Repository $owner/$repo belum ditemukan.")
        require(login.equals(owner, ignoreCase = true)) {
            "Repository $owner/$repo belum ada, dan token milik ${login.ifBlank { "akun lain" }} sehingga tidak bisa membuatnya otomatis."
        }
        progress(GhProgress(1, "Membuat repository ${if (createPrivate) "private" else "public"}…"))
        val made = try {
            githubRequestRetry("POST", "https://api.github.com/user/repos",
                JSONObject().put("name", repo).put("private", createPrivate).put("auto_init", true)
                    .put("description", "Dibuat lewat GITLS Publisher"), headers)
        } catch (e: IOException) {
            if (e.message.orEmpty().contains("HTTP 422")) {
                throw IOException("Repository $owner/$repo sudah ada tetapi tidak bisa diakses token ini. Untuk token fine-grained, beri akses ke repository tersebut (Contents: Read and write).")
            }
            throw e
        }
        created = true
        htmlUrl = made.optString("html_url")
        defaultBranch = made.optString("default_branch")
        Thread.sleep(800L)
    } else {
        val empty = try {
            githubRequest("GET", "$base/git/trees/HEAD", null, headers); false
        } catch (e: IOException) {
            e.message.orEmpty().contains("HTTP 409")
        }
        if (empty && !dryRun) {
            progress(GhProgress(1, "Repository masih kosong, membuat commit awal…"))
            val readme = android.util.Base64.encodeToString("# $repo\n".toByteArray(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)
            githubRequestRetry("PUT", "$base/contents/README.md", JSONObject().put("message", "Initial commit").put("content", readme), headers)
        } else {
            progress(GhProgress(1, "Repository ditemukan"))
        }
    }
    if (htmlUrl.isBlank()) htmlUrl = "https://github.com/$owner/$repo"

    // Hanya 404/409 (branch belum ada / repo kosong) yang berarti "tidak ada ref".
    // Error lain (401, jaringan, rate limit) harus dilempar, bukan dianggap branch baru.
    val ref = try {
        githubRequestRetry("GET", "$base/git/ref/heads/${encodePath(branch)}", null, headers)
    } catch (e: IOException) {
        val m = e.message.orEmpty()
        if (m.startsWith("GitHub HTTP 404") || m.startsWith("GitHub HTTP 409")) null else throw e
    }
    val refExists = ref != null
    var parentSha = ref?.optJSONObject("object")?.optString("sha").orEmpty()
    // Branch baru pada repo yang sudah berisi: cabangkan dari default branch, jangan buat commit yatim.
    if (!refExists && defaultBranch.isNotBlank() && defaultBranch != branch) {
        progress(GhProgress(1, "Branch $branch belum ada, dibuat dari $defaultBranch…"))
        val defRef = try {
            githubRequestRetry("GET", "$base/git/ref/heads/${encodePath(defaultBranch)}", null, headers)
        } catch (e: IOException) {
            val m = e.message.orEmpty()
            if (m.startsWith("GitHub HTTP 404") || m.startsWith("GitHub HTTP 409")) null else throw e
        }
        parentSha = defRef?.optJSONObject("object")?.optString("sha").orEmpty()
    }
    var baseTree = ""
    if (parentSha.isNotBlank()) {
        val parent = githubRequestRetry("GET", "$base/git/commits/$parentSha", null, headers)
        baseTree = parent.optJSONObject("tree")?.optString("sha").orEmpty()
    }

    // 3. Hitung perubahan terhadap tree remote.
    // Git blob SHA dihitung lokal sehingga file yang tidak berubah tidak perlu dikirim ulang.
    fun localGitBlobSha(file: File): String {
        // Git object SHA-1: sha1("blob " + decimalSize + NUL + content)
        val md = MessageDigest.getInstance("SHA-1")
        val size = file.length()
        val prefix = "blob $size".toByteArray(StandardCharsets.UTF_8)
        val header = ByteArray(prefix.size + 1)
        System.arraycopy(prefix, 0, header, 0, prefix.size)
        header[prefix.size] = 0 // NUL byte — wajib agar SHA cocok dengan GitHub
        md.update(header)
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (ghCancelled) throw GhCancelException()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    val remoteFiles = LinkedHashMap<String, JSONObject>()
    var remoteKnown = parentSha.isNotBlank()
    if (parentSha.isNotBlank()) {
        progress(GhProgress(1, "Membaca isi repository untuk membandingkan file…"))
        val treeShaForScan = if (baseTree.isNotBlank()) baseTree else parentSha
        val treeJson = githubRequestRetry(
            "GET", "$base/git/trees/$treeShaForScan?recursive=1", null, headers
        )
        val treeTruncated = treeJson.optBoolean("truncated", false)
        if (treeTruncated && (onlyChanged || deleteMissing)) {
            throw IOException("Tree repository terlalu besar untuk sinkronisasi aman. Matikan 'Hanya perubahan' dan 'Hapus file remote' atau sinkronkan repository yang lebih kecil.")
        }
        val arr = treeJson.optJSONArray("tree") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (item.optString("type") == "blob") {
                val path = item.optString("path")
                if (path.isNotBlank()) remoteFiles[path] = item
            }
        }
        if (treeTruncated) { remoteFiles.clear(); remoteKnown = false }
    }

    val changedFiles = if (onlyChanged && remoteFiles.isNotEmpty()) {
        val out = ArrayList<Pair<String, File>>()
        var unchanged = 0
        for ((rel, file) in files) {
            val remote = remoteFiles[rel]
            if (remote != null && remote.optString("sha") == localGitBlobSha(file)) {
                unchanged++
            } else out += rel to file
        }
        progress(GhProgress(1, "Perubahan: ${out.size} file • tidak berubah: $unchanged file"))
        out
    } else {
        if (onlyChanged) progress(GhProgress(1, "Repository belum memiliki file pembanding; semua file dianggap baru."))
        files
    }

    val localPaths = files.map { it.first }.toHashSet()
    val deletePaths = if (deleteMissing && remoteFiles.isNotEmpty()) {
        remoteFiles.keys.filter { path ->
            !localPaths.contains(path) &&
                !githubPathExcludedByPatterns(excludePatterns, path) &&
                !ghIsSensitivePath(path) &&
                !path.startsWith(".github/")
        }.sorted()
    } else emptyList()

    // Bedakan file baru dan file yang menimpa file lama di repository.
    val addedCount = if (remoteKnown) changedFiles.count { !remoteFiles.containsKey(it.first) } else changedFiles.size
    val updatedCount = if (remoteKnown) changedFiles.size - addedCount else 0
    val unchangedTotal = if (remoteKnown) files.size - changedFiles.size else 0
    if (remoteKnown && updatedCount > 0) progress(GhProgress(1, "Baru: $addedCount file • menimpa file lama: $updatedCount file"))

    if (dryRun) {
        val unchangedCount = files.size - changedFiles.size
        progress(GhProgress(4, "Dry run selesai • upload ${changedFiles.size}, hapus ${deletePaths.size}, tidak berubah $unchangedCount"))
        val shown = (changedFiles.take(12).map { "+ ${it.first}" } +
            deletePaths.take(12).map { "- $it" }).joinToString("\\n")
        if (shown.isNotBlank()) progress(GhProgress(4, shown))
        return GhResult(owner, repo, branch, changedFiles.size, changedFiles.sumOf { it.second.length() },
            "DRY-RUN", htmlUrl.trimEnd('/'), created, createPrivate, emptyList(),
            addedCount, updatedCount, deletePaths.size, unchangedTotal)
    }

    // Tidak ada perubahan sama sekali → tidak perlu commit kosong.
    if (changedFiles.isEmpty() && deletePaths.isEmpty()) {
        progress(GhProgress(4, "Repository sudah mutakhir • 0 file di-upload (semua isi sama)"))
        return GhResult(owner, repo, branch, 0, 0L,
            "NO-CHANGE", htmlUrl.trimEnd('/'), created, createPrivate, emptyList(),
            0, 0, 0, unchangedTotal)
    }

    // 4. unggah file
    val entries = JSONArray()
    var totalBytes = 0L
    val maxSingleFileBytes = 25L * 1024L * 1024L
    val maxUploadBytes = 150L * 1024L * 1024L
    changedFiles.forEachIndexed { index, (rel, file) ->
        val size = file.length()
        require(size <= maxSingleFileBytes) {
            "File terlalu besar untuk upload aman dari HP: $rel (${size / 1024L / 1024L} MB). Maksimal 25 MB per file."
        }
        require(totalBytes + size <= maxUploadBytes) {
            "Total upload terlalu besar untuk proses aman di HP. Maksimal 150 MB per upload."
        }
        progress(GhProgress(2, rel, index + 1, changedFiles.size))
        // Streaming: file dibaca per-chunk, di-Base64 langsung ke koneksi (RAM tetap kecil).
        val blob = githubRequestRetry("POST", "$base/git/blobs", null, headers, bodyFile = file)
        val sha = blob.optString("sha")
        require(sha.isNotBlank()) { "Gagal membuat blob untuk $rel" }
        totalBytes += size
        entries.put(JSONObject().put("path", rel).put("mode", ghFileMode(rel)).put("type", "blob").put("sha", sha))
    }

    // 4. tree, commit, push
    if (deletePaths.isNotEmpty()) {
        deletePaths.forEach { path ->
            val mode = remoteFiles[path]?.optString("mode").orEmpty().ifBlank { "100644" }
            entries.put(JSONObject().put("path", path).put("mode", mode).put("type", "blob").put("sha", JSONObject.NULL))
        }
        progress(GhProgress(3, "Menandai ${deletePaths.size} file untuk dihapus…"))
    }
    progress(GhProgress(3, "Membuat Git tree…", changedFiles.size, changedFiles.size))
    val treeBody = JSONObject().put("tree", entries)
    if (baseTree.isNotBlank()) treeBody.put("base_tree", baseTree)
    val treeSha = githubRequestRetry("POST", "$base/git/trees", treeBody, headers).optString("sha")
    require(treeSha.isNotBlank()) { "Gagal membuat Git tree" }
    progress(GhProgress(3, "Membuat commit…", files.size, files.size))
    val commitBody = JSONObject().put("message", commitMessage).put("tree", treeSha)
    if (parentSha.isNotBlank()) commitBody.put("parents", JSONArray().put(parentSha))
    val newSha = githubRequestRetry("POST", "$base/git/commits", commitBody, headers).optString("sha")
    require(newSha.isNotBlank()) { "Gagal membuat commit" }
    progress(GhProgress(3, "Memperbarui branch $branch…", files.size, files.size))
    if (!refExists) {
        githubRequestRetry("POST", "$base/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", newSha), headers)
    } else {
        githubRequestRetry("PATCH", "$base/git/refs/heads/${encodePath(branch)}", JSONObject().put("sha", newSha).put("force", false), headers)
    }

    // 5. verifikasi
    progress(GhProgress(4, "Memeriksa commit di GitHub…", files.size, files.size))
    val check = githubRequestRetry("GET", "$base/git/ref/heads/${encodePath(branch)}", null, headers)
    val remoteSha = check.optJSONObject("object")?.optString("sha").orEmpty()
    require(remoteSha == newSha) { "Verifikasi gagal: branch $branch belum menunjuk ke commit terbaru" }
    progress(GhProgress(4, "Commit ${newSha.take(7)} terverifikasi", files.size, files.size))

    val workflowFiles = files.map { it.first }.filter {
        val normalized = it.replace('\\', '/')
        normalized.startsWith(".github/workflows/") &&
            (normalized.endsWith(".yml", true) || normalized.endsWith(".yaml", true))
    }.sorted()
    return GhResult(owner, repo, branch, files.size, totalBytes, newSha, htmlUrl.trimEnd('/'), created, createPrivate, workflowFiles,
        addedCount, updatedCount, deletePaths.size, unchangedTotal)
}

internal fun MainActivity.uploadFolderToGitHub(
    treeUri: Uri, owner: String, repo: String, token: String, branch: String,
    commitMessage: String, createPrivate: Boolean, progress: (GhProgress) -> Unit,
    onlyChanged: Boolean = false, deleteMissing: Boolean = false, dryRun: Boolean = false
): GhResult {
    val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri) ?: error("Folder tidak dapat dibuka")
    val workDir = File(cacheDir, "github_folder_${System.currentTimeMillis()}").apply { mkdirs() }
    try {
        val localRoot = File(workDir, "project").apply { mkdirs() }
        var count = 0
        fun copyTree(dir: androidx.documentfile.provider.DocumentFile, target: File) {
            dir.listFiles().forEach { child ->
                if (ghCancelled) throw GhCancelException()
                val name = child.name ?: return@forEach
                if (name == ".git" || name == "__MACOSX" || name == ".DS_Store" || name == "Thumbs.db") return@forEach
                val out = File(target, name)
                if (child.isDirectory) { out.mkdirs(); copyTree(child, out) }
                else if (child.isFile) {
                    out.parentFile?.mkdirs()
                    contentResolver.openInputStream(child.uri)?.use { input -> FileOutputStream(out).use { output -> input.copyTo(output) } } ?: error("Tidak bisa membaca $name")
                    count++
                    if (count % 10 == 0) progress(GhProgress(0, "Membaca folder… $count file"))
                }
            }
        }
        progress(GhProgress(0, "Membaca isi folder yang dipilih…"))
        copyTree(root, localRoot)
        require(count > 0) { "Folder tidak berisi file yang bisa di-upload" }
        val files = localRoot.walkTopDown().filter { it.isFile }.map { f ->
            f.relativeTo(localRoot).path.replace(File.separatorChar, '/') to f
        }.filter { (rel, _) -> !rel.startsWith(".git/") && rel != ".git" && !rel.startsWith("__MACOSX/") && !rel.endsWith(".DS_Store") && !rel.endsWith("Thumbs.db") }.toList()
        return pushFilesToGitHub(files, owner, repo, token, branch, commitMessage, createPrivate, progress,
            onlyChanged, deleteMissing, dryRun)
    } finally { workDir.deleteRecursively() }
}

internal fun MainActivity.analyzeGithubZip(extracted: File): GithubZipAnalysis {
    val files = extracted.walkTopDown()
        .filter { it.isFile }
        .map { it.relativeTo(extracted).path.replace(File.separatorChar, '/') }
        .filter { it.isNotBlank() }
        .sorted()
        .toList()
    require(files.isNotEmpty()) { "ZIP tidak berisi file yang bisa di-upload" }

    val dirs = extracted.walkTopDown()
        .filter { it.isDirectory && it != extracted }
        .map { it.relativeTo(extracted).path.replace(File.separatorChar, '/') }
        .filter { it.isNotBlank() }
        .sorted()
        .toList()

    val excluded = files.filter {
        it == ".git" || it.startsWith(".git/") ||
        it == "__MACOSX" || it.startsWith("__MACOSX/") ||
        it == ".DS_Store" || it.endsWith("/.DS_Store") ||
        it == "Thumbs.db" || it.endsWith("/Thumbs.db")
    }.toSet()

    val top = extracted.listFiles()?.toList().orEmpty()
    val topDirs = top.filter { it.isDirectory }.map { it.name }.sorted()
    val topFiles = top.filter { it.isFile }
    var suggested = ""
    if (topDirs.size == 1 && topFiles.isEmpty()) {
        val wrapper = topDirs.first()
        val wrapperDir = File(extracted, wrapper)
        val children = wrapperDir.listFiles()?.toList().orEmpty()
        val childDirs = children.filter { it.isDirectory }.map { it.name }.sorted()
        val childFiles = children.filter { it.isFile }
        suggested = if (childDirs.size == 1 && childFiles.isEmpty() && childDirs.first().equals("web", true)) {
            "$wrapper/${childDirs.first()}"
        } else wrapper
    }
    return GithubZipAnalysis(files, dirs, suggested, excluded)
}

internal fun MainActivity.githubRequest(method: String, url: String, body: JSONObject?, headers: Map<String, String>, bodyFile: File? = null): JSONObject {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 20_000
        readTimeout = 60_000
        doInput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        headers.forEach { (key, value) -> setRequestProperty(key, value) }
    }
    try {
        if (bodyFile != null) {
            // Streaming blob: file -> Base64 -> koneksi tanpa menahan seluruh file di RAM.
            val prefix = "{\"encoding\":\"base64\",\"content\":\"".toByteArray(StandardCharsets.UTF_8)
            val suffix = "\"}".toByteArray(StandardCharsets.UTF_8)
            val b64Len = ((bodyFile.length() + 2L) / 3L) * 4L
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(prefix.size + b64Len + suffix.size)
            connection.outputStream.use { out ->
                out.write(prefix)
                val b64 = android.util.Base64OutputStream(out, android.util.Base64.NO_WRAP or android.util.Base64.NO_CLOSE)
                FileInputStream(bodyFile).use { input ->
                    val buf = ByteArray(48 * 1024)
                    while (true) {
                        if (ghCancelled) throw GhCancelException()
                        val n = input.read(buf)
                        if (n < 0) break
                        b64.write(buf, 0, n)
                    }
                }
                b64.close()
                out.write(suffix)
            }
        } else if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(response).optString("message") }.getOrDefault(response.take(240))
            val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            throw IOException("GitHub HTTP $code: ${message.ifBlank { "Request gagal" }}" + (if (retryAfter != null) " [retry-after=$retryAfter]" else ""))
        }
        return if (response.isBlank()) JSONObject() else JSONObject(response)
    } finally {
        connection.disconnect()
    }
}

internal fun MainActivity.encodePath(path: String): String = path.split('/').joinToString("/") { Uri.encode(it) }

internal fun MainActivity.unzipSafeForGithub(zip: File, dest: File, onBytes: ((Long) -> Unit)? = null) {
    val destCanonical = dest.canonicalFile
    var totalBytes = 0L
    var entries = 0
    val maxEntries = 5000
    val maxTotalBytes = 256L * 1024L * 1024L
    val maxEntryBytes = 64L * 1024L * 1024L
    ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
        while (true) {
            val entry = zis.nextEntry ?: break
            entries++
            require(entries <= maxEntries) { "ZIP terlalu banyak entry" }
            val normalized = entry.name.replace('\\', '/')
            if (normalized.startsWith("/") || normalized.split('/').any { it == ".." }) {
                throw SecurityException("ZIP entry tidak aman: ${entry.name}")
            }
            val target = File(destCanonical, normalized).canonicalFile
            require(target.path.startsWith(destCanonical.path + File.separator)) { "ZIP entry di luar folder tujuan" }
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                var entryBytes = 0L
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = zis.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        totalBytes += read
                        require(entryBytes <= maxEntryBytes && totalBytes <= maxTotalBytes) { "ZIP melebihi batas aman 256 MB" }
                        out.write(buffer, 0, read)
                        onBytes?.invoke(read.toLong())
                    }
                }
            }
            zis.closeEntry()
        }
    }
}
