package com.example.aidetest

/**
 * Deskripsi kemampuan tool untuk Bit.
 * Hanya metadata publik: tidak berisi token, password, cookie, atau credential.
 */
object BitToolKnowledge {
    data class Definition(
        val id: String,
        val purpose: String,
        val category: String,
        val actions: List<String>,
        val aliases: List<String> = emptyList()
    )

    private val definitions = listOf(
        Definition("githubzip", "mengunggah file/ZIP ke GitHub melalui GitHub Publisher", "GitHub", listOf("upload", "unggah", "kirim", "push"), listOf("github", "repository", "repo")),
        Definition("zip", "membuat, membuka, atau mengekstrak arsip ZIP", "File", listOf("buat", "extract", "ekstrak", "compress", "kompres"), listOf("zip", "unzip", "arsip")),
        Definition("filemanager", "membuka dan mengelola file serta folder", "File", listOf("buka", "kelola", "cari"), listOf("file", "folder")),
        Definition("qr", "memindai kode QR atau barcode", "Scanner", listOf("scan", "pindai", "baca"), listOf("qr", "barcode")),
        Definition("dns", "melakukan pencarian DNS untuk domain", "Network", listOf("cari", "cek", "lookup"), listOf("dns", "domain")),
        Definition("rdns", "melakukan reverse DNS lookup", "Network", listOf("cari", "cek", "lookup"), listOf("reverse dns", "rdns")),
        Definition("port", "memeriksa port jaringan pada host", "Network", listOf("cek", "periksa", "scan"), listOf("port checker", "port")),
        Definition("ping", "mengirim ping untuk memeriksa konektivitas", "Network", listOf("cek", "tes", "ping"), listOf("ping")),
        Definition("publicip", "menampilkan alamat IP publik", "Network", listOf("cek", "lihat", "tampilkan"), listOf("ip publik", "public ip")),
        Definition("hash", "membuat hash dari teks", "Security", listOf("buat", "hitung", "cek"), listOf("hash", "sha256", "sha-256")),
        Definition("checksum", "menghitung checksum file", "File", listOf("hitung", "cek", "bandingkan"), listOf("checksum")),
        Definition("fileconvert", "mengonversi file dari satu format ke format lain", "File", listOf("ubah", "konversi", "convert"), listOf("konversi file", "convert file")),
        Definition("editor", "membuka editor teks; bisa memeriksa error JS, HTML, CSS, JSON dan memperbaikinya otomatis dengan pratinjau", "Text", listOf("buka", "edit", "ubah", "cek error", "perbaiki"), listOf("editor", "teks")),
        Definition("json", "membantu mengolah data JSON dan mempratinjau animasi Lottie dari file JSON", "Developer", listOf("olah", "buka", "cek", "preview"), listOf("json", "lottie")),
        Definition("jsonformat", "memformat JSON agar mudah dibaca", "Developer", listOf("format", "rapikan", "beautify"), listOf("json formatter", "pretty json")),
        Definition("base64", "mengodekan atau mendekode Base64", "Developer", listOf("encode", "decode", "ubah"), listOf("base64")),
        Definition("uuid", "membuat UUID", "Developer", listOf("buat", "generate", "hasilkan"), listOf("uuid")),
        Definition("regex", "menguji pola regular expression", "Developer", listOf("tes", "uji", "cek"), listOf("regex", "regular expression")),
        Definition("url", "membantu pengolahan URL", "Developer", listOf("cek", "olah", "buka"), listOf("url tools", "url")),
        Definition("apk", "memeriksa informasi APK", "Android", listOf("cek", "lihat", "inspect"), listOf("apk inspector", "inspect apk")),
        Definition("apkanalyzer", "menganalisis APK", "Android", listOf("analisis", "cek", "periksa"), listOf("apk analyzer", "analisis apk")),
        Definition("network", "menampilkan informasi jaringan perangkat", "Network", listOf("cek", "lihat", "tampilkan"), listOf("network info", "jaringan")),
        Definition("storage", "menganalisis penggunaan penyimpanan", "Device", listOf("cek", "lihat", "analisis"), listOf("storage", "penyimpanan")),
        Definition("apps", "mengelola atau melihat aplikasi pada perangkat", "Device", listOf("buka", "kelola", "lihat"), listOf("app manager", "aplikasi")),
        Definition("clipboard", "mengelola isi clipboard", "Device", listOf("lihat", "salin", "kelola"), listOf("clipboard", "papan klip")),
        Definition("ocr", "membaca teks dari gambar", "Media", listOf("baca", "ambil", "scan"), listOf("ocr", "teks gambar")),
        Definition(
            "autotrace",
            "mengubah gambar raster menjadi SVG vector (auto trace B&W atau color)",
            "Media",
            listOf("trace", "vectorize", "ubah", "konversi", "buat"),
            listOf("auto trace", "image to svg", "gambar ke svg", "vectorisasi", "svg")
        ),
        Definition("reminder", "membuat atau mengelola pengingat", "Productivity", listOf("buat", "atur", "ingatkan"), listOf("pengingat", "reminder")),
        Definition("timer", "menjalankan timer", "Productivity", listOf("mulai", "jalankan", "atur"), listOf("timer")),
        Definition("stopwatch", "menjalankan stopwatch", "Productivity", listOf("mulai", "jalankan", "atur"), listOf("stopwatch"))
    )

    fun definition(id: String): Definition? = definitions.firstOrNull { it.id == id }

    fun enrich(id: String, fallbackName: String): String {
        val d = definition(id) ?: return fallbackName
        return "${d.purpose} (kategori ${d.category})."
    }

    fun aliasesFor(id: String): List<String> = definition(id)?.aliases.orEmpty()

    fun findByMeaning(question: String, tools: List<Pair<String, String>>): BitToolRegistry.Match? {
        val q = question.lowercase().trim()
        if (q.isBlank()) return null
        var best: BitToolRegistry.Match? = null
        for ((id, name) in tools) {
            val d = definition(id) ?: continue
            var score = 0
            d.aliases.forEach { if (q.contains(it)) score += if (it.length >= 6) 35 else 22 }
            d.actions.forEach { if (q.contains(it)) score += 8 }
            if (score > 0) {
                val confidence = score.coerceAtMost(98)
                if (best == null || confidence > best!!.confidence) best = BitToolRegistry.Match(id, name, confidence)
            }
        }
        return best?.takeIf { it.confidence >= 35 }
    }
}
