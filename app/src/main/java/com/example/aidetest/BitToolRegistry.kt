package com.example.aidetest

/**
 * Registry aman untuk Bit: hanya ID/nama/deskripsi tool. Tidak menyimpan token, password,
 * atau credential. Bit memakai registry ini untuk memahami kemampuan APK.
 */
object BitToolRegistry {
    data class Match(val id: String, val name: String, val confidence: Int)

    private val aliases = mapOf(
        "githubzip" to listOf("github", "upload github", "upload ke github", "kirim ke github", "push github", "github publisher", "repo github"),
        "zip" to listOf("zip", "unzip", "extract", "ekstrak", "kompres", "compress", "arsip"),
        "filemanager" to listOf("file manager", "kelola file", "folder", "file"),
        "qr" to listOf("qr", "barcode", "scan qr", "scanner qr"),
        "dns" to listOf("dns", "domain dns", "lookup dns"),
        "hash" to listOf("hash", "sha", "sha256", "sha-256", "checksum hash"),
        "apk" to listOf("apk inspector", "inspect apk", "lihat apk"),
        "apkanalyzer" to listOf("apk analyzer", "analisis apk", "analyze apk"),
        "fileconvert" to listOf("convert file", "konversi file", "ubah format file"),
        "videoframes" to listOf("video to frames", "video ke frame", "video jadi gambar", "ekstrak frame", "extract frames", "frame video", "video ke foto", "screenshot video", "potong video jadi gambar", "video ke png", "video ke jpg", "frame ke zip"),
        "videotoaudio" to listOf("video ke audio", "mp4 ke mp3", "video to audio", "extract audio", "ekstrak audio", "ubah video ke suara", "video jadi suara", "ambil suara video"),
        // Photo Editor + Design Studio + Background Remover = satu tool Foto Studio.
        "creativestudio" to listOf(
            "studio kreatif", "creative studio", "photo editor", "edit foto", "edit gambar", "kecerahan", "kontras foto",
            "design studio", "kanvas", "thumbnail", "poster", "banner", "desain", "template desain",
            "hapus background", "hapus latar", "background remover", "remove background", "png transparan",
            "foto studio", "animation studio", "studio animasi", "animasi desain", "animasikan", "keyframe", "motion graphic", "gif animasi", "buat gif", "animasi teks", "intro animasi"
        ),
        "presentationstudio" to listOf("presentation studio", "presentasi", "slide", "buat presentasi", "slideshow", "powerpoint", "pitch deck", "pdf presentasi", "mode presentasi"),
        "videoeditor" to listOf("video editor", "edit video", "timeline video", "potong video", "gabung video", "trim video", "video potret", "teks di video", "subtitle video", "file srt", "impor srt", "musik di video", "tambah musik ke video", "bekukan frame", "hapus bagian hening"),
        "audioeditor" to listOf("audio editor", "edit audio", "waveform", "potong audio", "fade audio", "voice over", "rekam suara", "gabung audio"),
        "batchconvert" to listOf("batch convert", "konversi batch", "converter batch", "antrean konversi", "batch image", "batch video"),
        "imagecompress" to listOf("kompres gambar", "kompres foto", "compress image", "perkecil foto"),
        "imageconvert" to listOf("konversi gambar", "ubah format gambar", "jpg ke png", "png ke jpg", "image converter"),
        "autotrace" to listOf(
            "auto trace", "autotrace", "image to svg", "gambar ke svg", "trace gambar",
            "vectorize", "vectorisasi", "trace image", "svg trace", "raster to vector",
            "ubah gambar jadi svg", "konversi ke svg", "image tracer"
        ),
        "editingstudio" to listOf("editing studio", "studio editing", "edit studio"),
        "editor" to listOf("editor", "edit file", "teks", "kode", "html", "css", "javascript"),
        "json" to listOf("lottie", "animasi json", "preview lottie", "lottiefiles", "json data"),
        "jsonformat" to listOf("format json", "beautify json", "rapikan json", "pretty json"),
        "wifi" to listOf("wifi", "wi-fi", "ssid", "signal wifi", "sinyal wifi"),
        "battery" to listOf("battery", "baterai", "daya", "charging"),
        "storage" to listOf("storage", "penyimpanan", "ruang"),
        "whois" to listOf("whois", "domain registration", "registrasi domain"),
        "traceroute" to listOf("traceroute", "trace route", "jalur jaringan"),
        "subnetcalc" to listOf("subnet", "cidr", "subnet calculator"),
        "restclient" to listOf("rest", "api client", "http request", "request api"),
        "websocket" to listOf("websocket", "ws client", "web socket"),
        "ocr" to listOf("ocr", "teks dari foto", "foto jadi teks", "kamera jadi teks"),
        "qr" to listOf("qr", "barcode", "scan qr", "scanner qr")
    )

    fun find(question: String, tools: List<Pair<String,String>>): Match? {
        val q = question.trim().lowercase()
        if (q.isBlank()) return null

        // Intent khusus GitHub: kata upload/kirim + github + zip/file.
        if (q.contains("github") && (q.contains("upload") || q.contains("kirim") || q.contains("unggah") || q.contains("push"))) {
            val t = tools.firstOrNull { it.first == "githubzip" }
            if (t != null) return Match(t.first, t.second, 100)
        }

        var best: Match? = null
        for ((id,name) in tools) {
            var score = 0
            val n = name.lowercase()
            if (q.contains(id.lowercase())) score += 55
            if (n.split(Regex("[^a-z0-9]+" )).filter { it.length >= 3 }.any { q.contains(it) }) score += 35
            aliases[id]?.forEach { alias -> if (q.contains(alias)) score += if (alias.length >= 6) 50 else 25 }
            if (score > 0 && (best == null || score > best!!.confidence)) best = Match(id,name,score.coerceAtMost(99))
        }
        val local = best?.takeIf { it.confidence >= 35 }
        return local ?: BitToolKnowledge.findByMeaning(q, tools)
    }
}
