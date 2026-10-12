package com.example.aidetest

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject

/** GITLS online integrations: PyPI/npm, IPinfo, and Cloudflare DoH. */
private object GitlsApiKeyStore {
    private const val KS = "AndroidKeyStore"
    private const val ALIAS = "gitls_api_keys"
    private const val PREF = "gitls_api_secrets"
    private fun key(): SecretKey {
        val ks = java.security.KeyStore.getInstance(KS).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return kg.generateKey()
    }
    fun save(context: Context, name: String, value: String) {
        if (value.isBlank()) { context.getSharedPreferences(PREF, 0).edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val enc = android.util.Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP)
        val iv = android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP)
        context.getSharedPreferences(PREF, 0).edit().putString(name, "$iv:$enc").apply()
    }
    fun get(context: Context, name: String): String {
        return runCatching {
            val raw = context.getSharedPreferences(PREF, 0).getString(name, null) ?: return ""
            val p = raw.split(":", limit = 2); if (p.size != 2) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, android.util.Base64.decode(p[0], android.util.Base64.DEFAULT)))
            String(cipher.doFinal(android.util.Base64.decode(p[1], android.util.Base64.DEFAULT)), StandardCharsets.UTF_8)
        }.getOrDefault("")
    }
}

private fun apiConnectionDialog(activity: MainActivity) {
    val box = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(8), activity.dp(4), activity.dp(8), 0)
    }
    val ip = activity.edit("IPinfo token (untuk IP Intelligence)", false).apply {
        setText(GitlsApiKeyStore.get(activity, "ipinfo"))
    }
    box.addView(ip)
    android.app.AlertDialog.Builder(activity).setTitle("Simpan credential")
        .setMessage(
            "Hanya IPinfo yang memakai API key di GITLS.\n" +
                "PyPI, npm, dan Cloudflare DoH tidak butuh key.\n\n" +
                "Token disimpan terenkripsi di perangkat (Android Keystore). Tidak dikirim ke server GITLS."
        )
        .setView(box)
        .setNegativeButton("Batal", null)
        .setPositiveButton("Simpan") { _, _ ->
            GitlsApiKeyStore.save(activity, "ipinfo", ip.text.toString().trim())
            activity.toast("API key tersimpan secara terenkripsi di perangkat")
        }.show()
}

/**
 * Hasil tes HTTP nyata — bukan sekadar “URL bisa dibaca”.
 * Berhasil hanya jika koneksi selesai dan status HTTP didapat (2xx = OK layanan; selain itu tetap “terhubung” tapi endpoint error).
 */
internal data class ApiProbeResult(
    val url: String,
    val method: String,
    val httpStatus: Int?,
    val durationMs: Long,
    val okTransport: Boolean,
    val bodyPreview: String,
    val error: String?
) {
    fun report(): String = buildString {
        appendLine("— Hasil tes koneksi —")
        appendLine("URL: $url")
        appendLine("Metode: $method")
        appendLine("Durasi: ${durationMs} ms")
        if (httpStatus != null) {
            appendLine("Status HTTP: $httpStatus")
            when {
                httpStatus in 200..299 -> appendLine("Kesimpulan: transport OK, endpoint merespons sukses.")
                httpStatus in 400..499 -> appendLine("Kesimpulan: transport OK, tetapi endpoint menolak permintaan (cek URL, key, atau kuota).")
                httpStatus in 500..599 -> appendLine("Kesimpulan: transport OK, server remote error.")
                else -> appendLine("Kesimpulan: transport OK, status tidak lazim.")
            }
        } else {
            appendLine("Status HTTP: (tidak didapat)")
            appendLine("Kesimpulan: koneksi GAGAL — URL terbaca di layar tidak berarti jaringan berhasil.")
        }
        if (!error.isNullOrBlank()) appendLine("Error: $error")
        if (bodyPreview.isNotBlank()) {
            appendLine()
            appendLine("Cuplikan respons (maks. 400 karakter):")
            appendLine(bodyPreview.take(400))
        }
    }
}

/** HTTP GET/HEAD probe dengan timeout; selalu mengembalikan status atau error eksplisit. */
private fun probeHttp(url: String, method: String = "GET", headers: Map<String, String> = emptyMap()): ApiProbeResult {
    val started = System.currentTimeMillis()
    return try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            requestMethod = method
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = runCatching { stream?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault("")
            ApiProbeResult(
                url = url,
                method = method,
                httpStatus = code,
                durationMs = System.currentTimeMillis() - started,
                okTransport = true,
                bodyPreview = text,
                error = null
            )
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        ApiProbeResult(
            url = url,
            method = method,
            httpStatus = null,
            durationMs = System.currentTimeMillis() - started,
            okTransport = false,
            bodyPreview = "",
            error = e.message ?: e.javaClass.simpleName
        )
    }
}

internal fun MainActivity.apiConnectionsTool() {
    clearPage("API Connections")
    toolWorkspace(
        "API Connections",
        "Kelola credential API dan uji koneksi ke layanan yang dipakai GITLS.",
        "api"
    )

    content.addView(
        infoCard(
            "Tujuan tool ini",
            "1) Menyimpan API key (saat ini: IPinfo) secara lokal & terenkripsi.\n" +
                "2) Menguji koneksi HTTP nyata ke endpoint — menampilkan URL, metode, status HTTP, durasi, dan error.\n\n" +
                "Bukan: diagram hubungan antarmuka API, bukan hanya “membaca” string URL.\n" +
                "Koneksi dianggap gagal jika tidak ada status HTTP (timeout, DNS, offline)."
        )
    )

    // Status konfigurasi
    toolWorkspaceSection("KONFIGURASI", "Credential tersimpan di perangkat.")
    val ipToken = GitlsApiKeyStore.get(this, "ipinfo")
    content.addView(
        infoCard(
            "IPinfo",
            if (ipToken.isBlank()) "Token: belum diisi → IP Intelligence tidak bisa lookup.\nDaftar token: ipinfo.io"
            else "Token: tersimpan (${ipToken.length} karakter, disembunyikan).\nSiap dipakai IP Intelligence."
        )
    )
    content.addView(
        infoCard(
            "Tanpa API key",
            "• PyPI / npm (Package Inspector) — registry publik\n" +
                "• Cloudflare DNS over HTTPS (1.1.1.1)\n" +
                "Tetap butuh internet; tanpa key bukan berarti selalu online."
        )
    )

    content.addView(button("Atur / ubah API key") { apiConnectionDialog(this) })

    toolWorkspaceSection("TES KONEKSI", "Setiap tes mengirim permintaan HTTP sungguhan.")

    content.addView(button("Test IPinfo (butuh token)") {
        val key = GitlsApiKeyStore.get(this, "ipinfo")
        if (key.isBlank()) {
            output(
                "IPinfo belum dikonfigurasi.\n\n" +
                    "Simpan token lewat tombol «Atur / ubah API key», lalu jalankan tes lagi.\n" +
                    "Tanpa token, tool tidak menganggap koneksi berhasil."
            )
            return@button
        }
        toolThread {
            val url = "https://api.ipinfo.io/lite/me?token=${URLEncoder.encode(key, "UTF-8")}"
            val probe = probeHttp(url, "GET")
            runOnToolUiThread { output(probe.report()) }
        }
    })

    content.addView(button("Test PyPI (tanpa key)") {
        toolThread {
            // Endpoint ringan: status JSON PyPI
            val probe = probeHttp("https://pypi.org/pypi/pip/json", "GET")
            runOnToolUiThread { output(probe.report()) }
        }
    })

    content.addView(button("Test npm registry (tanpa key)") {
        toolThread {
            val probe = probeHttp("https://registry.npmjs.org/lodash", "GET")
            runOnToolUiThread { output(probe.report()) }
        }
    })

    content.addView(button("Test Cloudflare DoH (tanpa key)") {
        toolThread {
            val url = "https://cloudflare-dns.com/dns-query?name=example.com&type=A"
            val probe = probeHttp(url, "GET", mapOf("Accept" to "application/dns-json"))
            runOnToolUiThread { output(probe.report()) }
        }
    })

    content.addView(
        subLabel(
            "Status 2xx = layanan merespons sukses. Status 4xx/5xx = jaringan jalan tetapi endpoint error. Tanpa status HTTP = gagal total (bukan “URL terbaca”).",
            11f
        ).apply { setPadding(dp(2), dp(8), 0, 0) }
    )
}

/**
 * Format field hanya jika nilai benar-benar ada (bukan kosong / "-").
 * Mencegah menampilkan metadata yang tidak diekstrak.
 */
private fun pkgLine(label: String, value: String?): String? {
    val v = value?.trim().orEmpty()
    if (v.isEmpty() || v == "-" || v == "null") return null
    return "$label: $v"
}

private fun formatPypiPackage(body: String, query: String): String {
    val o = JSONObject(body)
    val info = o.optJSONObject("info") ?: JSONObject()
    val lines = ArrayList<String>()
    lines += "Sumber: PyPI (registry paket Python)"
    lines += "Jenis input: nama paket di pypi.org (bukan file APK / bukan package.json lokal)"
    lines += ""
    lines += "— Identitas —"
    pkgLine("Nama", info.optString("name").ifBlank { query })?.let { lines += it }
    pkgLine("Versi stabil (info.version)", info.optString("version"))?.let { lines += it }
    pkgLine("Ringkasan", info.optString("summary"))?.let { lines += it }
    pkgLine("Lisensi", info.optString("license"))?.let { lines += it }
    pkgLine("Author", info.optString("author"))?.let { lines += it }
    pkgLine("Maintainer", info.optString("maintainer"))?.let { lines += it }
    pkgLine("Homepage", info.optString("home_page").ifBlank { info.optString("project_url") })?.let { lines += it }
    pkgLine("Dokumentasi", info.optString("docs_url"))?.let { lines += it }
    pkgLine("Bug tracker", info.optString("bugtrack_url"))?.let { lines += it }
    val classifiers = info.optJSONArray("classifiers")
    if (classifiers != null && classifiers.length() > 0) {
        val sample = (0 until minOf(5, classifiers.length())).map { classifiers.optString(it) }.filter { it.isNotBlank() }
        if (sample.isNotEmpty()) lines += "Classifier (contoh): ${sample.joinToString("; ")}"
    }
    lines += ""
    lines += "— Dependensi (requires_dist) —"
    val req = info.optJSONArray("requires_dist")
    if (req == null || req.length() == 0) {
        lines += "Tidak ada daftar requires_dist di metadata, atau paket tidak mendeklarasikan dependensi runtime."
    } else {
        lines += "Jumlah entri: ${req.length()}"
        val show = minOf(30, req.length())
        for (i in 0 until show) {
            val d = req.optString(i).trim()
            if (d.isNotEmpty()) lines += "• $d"
        }
        if (req.length() > show) lines += "… dan ${req.length() - show} entri lagi"
    }
    val releases = o.optJSONObject("releases")
    if (releases != null) {
        lines += ""
        lines += "— Rilis —"
        lines += "Jumlah versi di registry: ${releases.length()}"
    }
    lines += ""
    lines += "Catatan: data diambil dari API publik PyPI. Ini bukan inspeksi APK Android dan bukan scan folder proyek lokal."
    return lines.joinToString("\n")
}

private fun formatNpmPackage(body: String, query: String): String {
    val o = JSONObject(body)
    // npm registry kadang mengembalikan error object
    if (o.has("error")) {
        return "npm: paket tidak ditemukan atau error registry.\nDetail: ${o.optString("error")}"
    }
    val distTags = o.optJSONObject("dist-tags") ?: JSONObject()
    val latestTag = distTags.optString("latest").ifBlank { null }
    val versions = o.optJSONObject("versions")
    val latestMeta = latestTag?.let { versions?.optJSONObject(it) }
    val lines = ArrayList<String>()
    lines += "Sumber: npm (registry paket JavaScript / Node)"
    lines += "Jenis input: nama paket di registry.npmjs.org (bukan file APK / bukan node_modules lokal)"
    lines += ""
    lines += "— Identitas —"
    pkgLine("Nama", o.optString("name").ifBlank { query })?.let { lines += it }
    pkgLine("Versi latest (dist-tag)", latestTag)?.let { lines += it }
    // Deskripsi: di root atau di versi latest
    val desc = o.optString("description").ifBlank { latestMeta?.optString("description").orEmpty() }
    pkgLine("Deskripsi", desc)?.let { lines += it }
    pkgLine("Homepage", o.optString("homepage").ifBlank { latestMeta?.optString("homepage") })?.let { lines += it }
    val license = when {
        o.opt("license") is String -> o.optString("license")
        latestMeta?.opt("license") is String -> latestMeta.optString("license")
        else -> ""
    }
    pkgLine("Lisensi", license)?.let { lines += it }
    // Author bisa string atau object
    val authorStr = when (val a = o.opt("author") ?: latestMeta?.opt("author")) {
        is String -> a
        is JSONObject -> listOf(a.optString("name"), a.optString("email")).filter { it.isNotBlank() }.joinToString(" ")
        else -> ""
    }
    pkgLine("Author", authorStr)?.let { lines += it }
    if (versions != null) {
        lines += "Jumlah versi di registry: ${versions.length()}"
    }
    // Dependensi dari versi latest saja (yang benar-benar ada di metadata)
    lines += ""
    lines += "— Dependensi (versi latest) —"
    val deps = latestMeta?.optJSONObject("dependencies")
    val devDeps = latestMeta?.optJSONObject("devDependencies")
    val peerDeps = latestMeta?.optJSONObject("peerDependencies")
    if (latestTag == null || latestMeta == null) {
        lines += "Metadata versi latest tidak tersedia dari registry."
    } else {
        fun dumpDeps(title: String, obj: JSONObject?) {
            if (obj == null || obj.length() == 0) {
                lines += "$title: (tidak ada / tidak dideklarasikan)"
                return
            }
            lines += "$title: ${obj.length()} paket"
            val keys = obj.keys().asSequence().toList().sorted()
            val show = keys.take(25)
            show.forEach { k -> lines += "• $k: ${obj.optString(k)}" }
            if (keys.size > show.size) lines += "… dan ${keys.size - show.size} lagi"
        }
        dumpDeps("dependencies", deps)
        dumpDeps("devDependencies", devDeps)
        dumpDeps("peerDependencies", peerDeps)
    }
    lines += ""
    lines += "Catatan: data diambil dari registry.npmjs.org. Ini bukan inspeksi APK Android dan bukan scan package.json di HP."
    return lines.joinToString("\n")
}

internal fun MainActivity.packageInspectorTool() {
    clearPage("Package Inspector")
    toolWorkspace(
        "Package Inspector",
        "Metadata paket dari registry online — PyPI (Python) atau npm (JavaScript).",
        "package-variant"
    )

    content.addView(
        infoCard(
            "Apa yang diperiksa tool ini?",
            "• Input: nama paket di registry (contoh: requests, react, express).\n" +
                "• Sumber: API publik PyPI atau npm — butuh internet.\n" +
                "• Bukan: file APK Android (pakai APK Inspector), bukan scan folder proyek / node_modules / venv di HP.\n" +
                "• Hasil hanya field yang benar-benar ada di respons registry."
        )
    )

    toolWorkspaceSection("JENIS REGISTRY", "Pilih sumber metadata.")
    val source = Spinner(this).apply {
        adapter = ArrayAdapter(
            hostActivity,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("PyPI — paket Python (pypi.org)", "npm — paket JavaScript (registry.npmjs.org)")
        )
    }
    content.addView(source, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

    toolWorkspaceSection("NAMA PAKET", "Ketik nama paket tepat seperti di registry (bukan path file).")
    val name = edit("Nama paket", false).apply { hint = "contoh: requests  ·  react  ·  lodash" }
    content.addView(name, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

    content.addView(button("Periksa paket") {
        val n = name.text.toString().trim()
        if (n.isBlank()) {
            toast("Masukkan nama package")
            return@button
        }
        // Tolak path/file yang jelas bukan nama paket registry
        if (n.contains('/') && !n.startsWith("@") || n.endsWith(".apk", true) || n.endsWith(".aar", true)) {
            output(
                "Input terlihat seperti path atau file, bukan nama paket registry.\n\n" +
                    "• Untuk APK Android → buka tool APK Inspector.\n" +
                    "• Untuk PyPI/npm → ketik nama paket saja (mis. requests, @scope/name)."
            )
            return@button
        }
        val isPypi = source.selectedItemPosition == 0
        toolThread {
            val result = runCatching {
                val encoded = URLEncoder.encode(n, "UTF-8")
                // npm scoped packages: @scope/name → encode tetap valid di path
                val url = if (isPypi) {
                    "https://pypi.org/pypi/$encoded/json"
                } else {
                    "https://registry.npmjs.org/$encoded"
                }
                val body = getJson(url)
                if (isPypi) formatPypiPackage(body, n) else formatNpmPackage(body, n)
            }.getOrElse { e ->
                "Pemeriksaan gagal.\n${e.message ?: "network error"}\n\n" +
                    "Periksa koneksi internet dan ejaan nama paket. Registry bisa mengembalikan 404 jika paket tidak ada."
            }
            runOnToolUiThread { output(result) }
        }
    })

    content.addView(button("Buka APK Inspector (Android)") { apkInspector() })
    content.addView(button("API Connections") { apiConnectionsTool() })
}

internal fun MainActivity.ipIntelligenceTool() {
    clearPage("IP Intelligence")
    toolWorkspace("IP Intelligence", "Informasi IP melalui IPinfo Lite. Token diperlukan oleh endpoint Lite.", "ip-network")
    val ip=edit("IP kosong = IP koneksi saat ini", false); content.addView(ip)
    content.addView(button("Lookup IPinfo") {
        val token=GitlsApiKeyStore.get(this, "ipinfo")
        if(token.isBlank()){ toast("Masukkan IPinfo token di API Connections"); apiConnectionDialog(this); return@button }
        val target=ip.text.toString().trim().ifBlank { "me" }
        toolThread { val r=ipinfoRequest(target,token); runOnToolUiThread{output(r)} }
    })
    content.addView(button("API Connections") { apiConnectionDialog(this) })
    content.addView(subLabel("Data IPinfo Lite digunakan sesuai ketentuan layanan dan atribusi IPinfo."))
}

internal fun MainActivity.dohDnsTool() {
    clearPage("DNS over HTTPS")
    toolWorkspace("DNS over HTTPS", "DNS lookup melalui Cloudflare 1.1.1.1 tanpa API key.", "dns")
    val domain=edit("Domain", false).apply{hint="example.com"}; val type=Spinner(this).apply{adapter=ArrayAdapter(hostActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("A","AAAA","CNAME","MX","TXT","NS"))}
    content.addView(domain); content.addView(type)
    pinToolAction(button("Lookup DoH") {
        val d=domain.text.toString().trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        if(d.isBlank()){toast("Masukkan domain");return@button}
        toolThread { val r=runCatching{cloudflareDoh(d,type.selectedItem.toString())}.getOrElse { "DoH gagal: ${it.message ?: "network error"}"}; runOnToolUiThread{output(r)} }
    })
}

private fun getJson(url:String):String = (URL(url).openConnection() as HttpURLConnection).run { connectTimeout=8000; readTimeout=10000; requestMethod="GET"; setRequestProperty("Accept","application/json"); try { val code=responseCode; val text=(if(code in 200..299) inputStream else errorStream).bufferedReader().use{it.readText()}; if(code !in 200..299) error("HTTP $code: $text"); text } finally { disconnect() } }
private fun ipinfoRequest(target:String, token:String):String = getJson(if(target=="me") "https://api.ipinfo.io/lite/me?token=${URLEncoder.encode(token,"UTF-8")}" else "https://api.ipinfo.io/lite/${URLEncoder.encode(target,"UTF-8")}?token=${URLEncoder.encode(token,"UTF-8")}")
private fun cloudflareDoh(domain:String,type:String):String = getJson("https://cloudflare-dns.com/dns-query?name=${URLEncoder.encode(domain,"UTF-8")}&type=$type").let{raw->
    val o=JSONObject(raw); val ans=o.optJSONArray("Answer"); val sb=StringBuilder("Status: ${o.optInt("Status",-1)}\n"); if(ans==null||ans.length()==0) sb.append("Tidak ada Answer record.") else for(i in 0 until ans.length()){val a=ans.getJSONObject(i);sb.append("${a.optString("name")}  TTL=${a.optInt("TTL")}  ${a.optString("data")}\n")}; sb.toString().trim()
}

