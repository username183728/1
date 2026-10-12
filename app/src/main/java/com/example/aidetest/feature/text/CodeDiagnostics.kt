package com.example.aidetest

/**
 * Mesin pemeriksa sintaks ringan untuk Text Editor (JS, HTML, CSS, JSON).
 *
 * Murni Kotlin (tanpa dependensi Android) supaya mudah diuji unit.
 * Semua pemeriksaan berjalan lokal di perangkat; tidak ada kode yang dikirim ke mana pun.
 *
 * Catatan: ini pemeriksaan struktur (kurung, kutip, tag, koma JSON), bukan parser
 * bahasa penuh. Pesan error sengaja tidak menyalin isi kode, hanya nomor baris dan
 * karakter struktural, agar aman bila diteruskan ke Bit.
 */
object CodeDiagnostics {
    enum class Severity { ERROR, WARNING }

    /** Satu perubahan teks: ganti [start, end) dengan [replacement]. */
    data class Edit(val start: Int, val end: Int, val replacement: String)

    data class Fix(val label: String, val edits: List<Edit>)

    data class Issue(
        val line: Int,
        val column: Int,
        val start: Int,
        val end: Int,
        val severity: Severity,
        val code: String,
        val message: String,
        val fix: Fix? = null,
        /** Urutan kemunculan; dipakai agar sisipan di posisi sama tetap berurutan. */
        val order: Int = 0
    )

    const val MAX_CHARS = 200_000

    fun normalizeMode(mode: String): String? = when (mode.lowercase()) {
        "html", "htm" -> "html"
        "css" -> "css"
        "js", "javascript", "mjs", "cjs" -> "js"
        "json" -> "json"
        else -> null
    }

    fun supports(mode: String): Boolean = normalizeMode(mode) != null

    fun analyze(text: String, mode: String): List<Issue> {
        val m = normalizeMode(mode) ?: return emptyList()
        if (text.isBlank() || text.length > MAX_CHARS) return emptyList()
        val ctx = Ctx(text)
        when (m) {
            "json" -> JsonScan(ctx).run()
            "js" -> scanCode(ctx, 0, text.length, css = false)
            "css" -> scanCode(ctx, 0, text.length, css = true)
            "html" -> scanHtml(ctx)
        }
        return ctx.issues.sortedBy { it.start }
    }

    /** Terapkan daftar edit. Edit yang tumpang tindih dengan edit sebelumnya dilewati. */
    fun applyEdits(text: String, edits: List<Edit>): String {
        val sorted = edits.sortedBy { it.start }
        val sb = StringBuilder()
        var cur = 0
        for (e in sorted) {
            if (e.start < cur || e.start > text.length) continue
            sb.append(text, cur, e.start).append(e.replacement)
            cur = e.end.coerceIn(e.start, text.length)
        }
        sb.append(text, cur, text.length)
        return sb.toString()
    }

    /** Terapkan semua perbaikan otomatis. Mengembalikan teks baru dan jumlah isu yang punya perbaikan. */
    fun applyAllFixes(text: String, issues: List<Issue>): Pair<String, Int> {
        val withFix = issues.filter { it.fix != null }.sortedBy { it.order }
        val edits = withFix.flatMap { it.fix!!.edits }
        return applyEdits(text, edits) to withFix.size
    }

    /** Baris sebelum/sesudah satu perbaikan, untuk pratinjau. */
    fun previewOf(text: String, issue: Issue): Pair<String, String>? {
        val fix = issue.fix ?: return null
        val first = fix.edits.minByOrNull { it.start } ?: return null
        val newText = applyEdits(text, fix.edits)
        val idx = text.substring(0, first.start.coerceIn(0, text.length)).count { it == '\n' }
        val before = text.lines().getOrNull(idx).orEmpty()
        var after = newText.lines().getOrNull(idx).orEmpty()
        if (after == before) after = newText.lines().getOrNull(idx + 1).orEmpty()
        return before.trim().take(100) to after.trim().take(100)
    }

    // ---------------------------------------------------------------- internals

    private class Ctx(val t: String) {
        val issues = ArrayList<Issue>()
        private val starts: IntArray

        init {
            val l = ArrayList<Int>()
            l.add(0)
            for (i in t.indices) if (t[i] == '\n') l.add(i + 1)
            starts = l.toIntArray()
        }

        fun line(pos: Int): Int {
            val p = pos.coerceIn(0, t.length)
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (starts[mid] <= p) lo = mid else hi = mid - 1
            }
            return lo + 1
        }

        fun col(pos: Int): Int = pos.coerceIn(0, t.length) - starts[line(pos) - 1] + 1

        fun lineEnd(pos: Int): Int {
            val n = t.indexOf('\n', pos.coerceIn(0, t.length))
            return if (n < 0) t.length else n
        }

        fun add(
            start: Int, end: Int, sev: Severity, code: String, msg: String, fix: Fix? = null
        ) {
            val s = start.coerceIn(0, t.length)
            val e = end.coerceIn(s, t.length)
            issues.add(Issue(line(s), col(s), s, e, sev, code, msg, fix, issues.size))
        }
    }

    private fun closerOf(open: Char): Char = when (open) {
        '(' -> ')'
        '[' -> ']'
        else -> '}'
    }

    /** Titik sisip penutup string: akhir baris, sebelum ; , ) ] } penutup di ujung baris. */
    private fun softLineEnd(t: String, from: Int, limit: Int): Int {
        var e = t.indexOf('\n', from)
        if (e < 0 || e > limit) e = limit
        while (e > from && t[e - 1] in ";,)]} \t\r") e--
        return maxOf(e, from)
    }

    private val REGEX_KEYWORDS = setOf(
        "return", "typeof", "case", "in", "of", "delete", "void", "throw", "new", "else", "do", "yield", "await"
    )

    private fun regexAllowed(lastSig: Char, lastWord: String): Boolean {
        if (lastSig == 'a') return lastWord in REGEX_KEYWORDS
        return lastSig == ' ' || lastSig in "(,=:[!&|?{;+-*%<>~^"
    }

    // ------------------------------------------------------------ JS / CSS

    private fun scanCode(c: Ctx, from: Int, to: Int, css: Boolean) {
        val t = c.t
        val stackCh = ArrayList<Char>()
        val stackPos = ArrayList<Int>()
        var i = from
        var lastSig = ' '
        var lastWord = ""

        while (i < to) {
            val ch = t[i]

            // komentar baris (JS)
            if (!css && ch == '/' && i + 1 < to && t[i + 1] == '/') {
                val e = t.indexOf('\n', i)
                i = if (e < 0 || e > to) to else e
                continue
            }
            // komentar blok
            if (ch == '/' && i + 1 < to && t[i + 1] == '*') {
                val e = t.indexOf("*/", i + 2)
                if (e < 0 || e + 2 > to) {
                    c.add(
                        i, i + 2, Severity.ERROR, "UNCLOSED_COMMENT",
                        "Komentar blok yang dibuka di baris ${c.line(i)} tidak ditutup dengan */",
                        Fix("Tambah penutup komentar */", listOf(Edit(to, to, "*/")))
                    )
                    i = to
                } else {
                    i = e + 2
                }
                continue
            }
            // string kutip tunggal / ganda
            if (ch == '"' || ch == '\'') {
                var j = i + 1
                var closed = false
                while (j < to) {
                    val x = t[j]
                    if (x == '\\') { j += 2; continue }
                    if (x == ch) { closed = true; break }
                    if (x == '\n') break
                    j++
                }
                if (!closed) {
                    val ins = softLineEnd(t, i + 1, to)
                    c.add(
                        i, maxOf(ins, i + 1), Severity.ERROR, "UNCLOSED_STRING",
                        "Tanda kutip $ch di baris ${c.line(i)} tidak ditutup",
                        Fix("Tambah tanda kutip penutup $ch", listOf(Edit(ins, ins, ch.toString())))
                    )
                    i = minOf(j, to)
                } else {
                    i = j + 1
                }
                lastSig = ch
                continue
            }
            // template literal (JS)
            if (!css && ch == '`') {
                var j = i + 1
                var depth = 0
                var closed = false
                while (j < to) {
                    val x = t[j]
                    if (x == '\\') { j += 2; continue }
                    if (depth == 0) {
                        if (x == '`') { closed = true; break }
                        if (x == '$' && j + 1 < to && t[j + 1] == '{') { depth = 1; j += 2; continue }
                    } else {
                        if (x == '{') depth++ else if (x == '}') depth--
                    }
                    j++
                }
                if (!closed) {
                    c.add(
                        i, i + 1, Severity.ERROR, "UNCLOSED_TEMPLATE",
                        "Template literal (`) yang dibuka di baris ${c.line(i)} tidak ditutup",
                        Fix("Tambah tanda ` penutup", listOf(Edit(to, to, "`")))
                    )
                    i = to
                } else {
                    i = j + 1
                }
                lastSig = '`'
                continue
            }
            // regex literal (JS)
            if (!css && ch == '/' && regexAllowed(lastSig, lastWord)) {
                var j = i + 1
                var inClass = false
                var ok = false
                while (j < to) {
                    val x = t[j]
                    if (x == '\n') break
                    if (x == '\\') { j += 2; continue }
                    if (x == '[') inClass = true
                    else if (x == ']') inClass = false
                    else if (x == '/' && !inClass) { ok = true; break }
                    j++
                }
                if (ok) {
                    j++
                    while (j < to && t[j].isLetter()) j++
                    i = j
                    lastSig = '0'
                    continue
                }
            }
            // identifier
            if (ch.isLetter() || ch == '_' || ch == '$') {
                var j = i + 1
                while (j < to && (t[j].isLetterOrDigit() || t[j] == '_' || t[j] == '$')) j++
                lastWord = t.substring(i, j)
                lastSig = 'a'
                if (!css && lastWord == "debugger") {
                    c.add(
                        i, j, Severity.WARNING, "DEBUGGER",
                        "Pernyataan debugger tertinggal di baris ${c.line(i)}",
                        Fix("Hapus debugger", listOf(Edit(i, j, "")))
                    )
                }
                i = j
                continue
            }
            // angka
            if (ch.isDigit()) {
                var j = i + 1
                while (j < to && (t[j].isLetterOrDigit() || t[j] == '.')) j++
                lastSig = '0'
                i = j
                continue
            }

            when (ch) {
                '(', '[', '{' -> {
                    stackCh.add(ch)
                    stackPos.add(i)
                }
                ')', ']', '}' -> {
                    var handled = false
                    while (!handled) {
                        if (stackCh.isEmpty()) {
                            c.add(
                                i, i + 1, Severity.ERROR, "STRAY_CLOSER",
                                "Kurung tutup '$ch' di baris ${c.line(i)} tidak punya pasangan pembuka",
                                Fix("Hapus '$ch' yang berlebih", listOf(Edit(i, i + 1, "")))
                            )
                            handled = true
                        } else {
                            val top = stackCh[stackCh.size - 1]
                            val topPos = stackPos[stackPos.size - 1]
                            val expected = closerOf(top)
                            if (expected == ch) {
                                stackCh.removeAt(stackCh.size - 1)
                                stackPos.removeAt(stackPos.size - 1)
                                handled = true
                            } else if (stackCh.any { closerOf(it) == ch }) {
                                // pembuka '$ch' ada lebih dalam: berarti 'top' belum ditutup.
                                // Sisipkan penutup di akhir pernyataan sebelumnya (sebelum ';' bila ada).
                                var p = i
                                while (p > from && t[p - 1].isWhitespace()) p--
                                if (p > from && t[p - 1] == ';') p--
                                c.add(
                                    topPos, topPos + 1, Severity.ERROR, "MISSING_CLOSER",
                                    "Kurung '$top' di baris ${c.line(topPos)} belum ditutup sebelum '$ch' di baris ${c.line(i)}",
                                    Fix("Tambah '$expected' sebelum '$ch'", listOf(Edit(p, p, expected.toString())))
                                )
                                stackCh.removeAt(stackCh.size - 1)
                                stackPos.removeAt(stackPos.size - 1)
                            } else {
                                c.add(
                                    i, i + 1, Severity.ERROR, "MISMATCH",
                                    "Kurung '$ch' di baris ${c.line(i)} tidak cocok dengan '$top' dari baris ${c.line(topPos)}",
                                    Fix("Ganti '$ch' menjadi '$expected'", listOf(Edit(i, i + 1, expected.toString())))
                                )
                                stackCh.removeAt(stackCh.size - 1)
                                stackPos.removeAt(stackPos.size - 1)
                                handled = true
                            }
                        }
                    }
                }
                else -> Unit
            }
            if (!ch.isWhitespace()) lastSig = ch
            i++
        }

        // pembuka yang tidak pernah ditutup: innermost lebih dulu
        var first = true
        for (k in stackCh.indices.reversed()) {
            val op = stackCh[k]
            val pos = stackPos[k]
            val cl = closerOf(op)
            val needNl = first && to > from && t[to - 1] != '\n'
            first = false
            c.add(
                pos, pos + 1, Severity.ERROR, "UNCLOSED_BRACKET",
                "Kurung '$op' di baris ${c.line(pos)} tidak ditutup",
                Fix("Tambah '$cl' di akhir", listOf(Edit(to, to, (if (needNl) "\n" else "") + cl)))
            )
        }
    }

    // ---------------------------------------------------------------- HTML

    private val VOID_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
        "param", "source", "track", "wbr"
    )
    private val OPTIONAL_END = setOf(
        "p", "li", "dt", "dd", "tr", "td", "th", "thead", "tbody", "tfoot", "option",
        "optgroup", "colgroup", "caption", "head", "body", "html", "rt", "rp"
    )
    private val ID_ATTR = Regex("\\sid\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val ALT_ATTR = Regex("\\salt\\s*=", RegexOption.IGNORE_CASE)
    private val TYPE_ATTR = Regex("\\stype\\s*=\\s*[\"']?([^\"'\\s>]+)", RegexOption.IGNORE_CASE)

    private fun isNameChar(x: Char) = x.isLetterOrDigit() || x == '-' || x == ':' || x == '_'

    private fun scanHtml(c: Ctx) {
        val t = c.t
        val n = t.length
        val names = ArrayList<String>()
        val poss = ArrayList<Int>()
        val ids = HashMap<String, Int>()
        var i = 0

        while (i < n) {
            val lt = t.indexOf('<', i)
            if (lt < 0) break
            i = lt

            if (t.startsWith("<!--", i)) {
                val e = t.indexOf("-->", i + 4)
                if (e < 0) {
                    c.add(
                        i, i + 4, Severity.ERROR, "UNCLOSED_COMMENT",
                        "Komentar HTML yang dibuka di baris ${c.line(i)} tidak ditutup dengan -->",
                        Fix("Tambah -->", listOf(Edit(n, n, "-->")))
                    )
                    return
                }
                i = e + 3
                continue
            }
            if (t.startsWith("<!", i) || t.startsWith("<?", i)) {
                val e = t.indexOf('>', i)
                if (e < 0) break
                i = e + 1
                continue
            }
            if (t.startsWith("</", i)) {
                var j = i + 2
                while (j < n && isNameChar(t[j])) j++
                val name = t.substring(i + 2, j).lowercase()
                if (name.isEmpty()) { i += 2; continue }
                val gt = t.indexOf('>', j)
                val end = if (gt < 0) n else gt + 1
                if (gt < 0) {
                    c.add(
                        i, j, Severity.ERROR, "UNCLOSED_TAG_END",
                        "Tag penutup di baris ${c.line(i)} tidak diakhiri '>'",
                        Fix("Tambah '>'", listOf(Edit(j, j, ">")))
                    )
                }
                closeTag(c, names, poss, name, i, end)
                i = end
                continue
            }
            if (i + 1 >= n || !t[i + 1].isLetter()) { i++; continue }

            // tag pembuka
            var j = i + 1
            while (j < n && isNameChar(t[j])) j++
            val name = t.substring(i + 1, j).lowercase()

            var k = j
            var quote = ' '
            var prev = ' '
            var tagEnd = -1
            var brokenAt = -1
            while (k < n) {
                val x = t[k]
                if (quote != ' ') {
                    if (x == quote) quote = ' '
                } else if ((x == '"' || x == '\'') && prev == '=') {
                    quote = x
                } else if (x == '>') {
                    tagEnd = k
                    break
                } else if (x == '<') {
                    brokenAt = k
                    break
                }
                if (!x.isWhitespace()) prev = x
                k++
            }

            val attrEnd: Int
            val next: Int
            if (tagEnd >= 0) {
                attrEnd = tagEnd
                next = tagEnd + 1
            } else if (brokenAt >= 0) {
                var p = brokenAt
                while (p > j && t[p - 1].isWhitespace()) p--
                c.add(
                    i, j, Severity.ERROR, "UNCLOSED_TAG_END",
                    "Tag di baris ${c.line(i)} belum ditutup dengan '>'",
                    Fix("Tambah '>' setelah atribut", listOf(Edit(p, p, ">")))
                )
                attrEnd = p
                next = brokenAt
            } else {
                val msg = if (quote != ' ') {
                    "Tanda kutip nilai atribut pada tag di baris ${c.line(i)} tidak ditutup"
                } else {
                    "Tag di baris ${c.line(i)} tidak diakhiri '>'"
                }
                c.add(i, j, Severity.ERROR, "UNCLOSED_TAG_END", msg)
                return
            }

            val attrs = t.substring(j, attrEnd.coerceAtLeast(j))
            val selfClosing = attrs.trimEnd().endsWith("/")

            ID_ATTR.findAll(attrs).forEach { m ->
                val v = m.groups[1]?.value ?: m.groups[2]?.value ?: return@forEach
                val abs = j + m.range.first + 1
                val firstLine = ids[v]
                if (firstLine == null) {
                    ids[v] = c.line(abs)
                } else {
                    c.add(
                        abs, abs + m.value.length - 1, Severity.WARNING, "DUPLICATE_ID",
                        "Nilai id dipakai lebih dari sekali (pertama di baris $firstLine, lagi di baris ${c.line(abs)})"
                    )
                }
            }
            if (name == "img" && !ALT_ATTR.containsMatchIn(attrs)) {
                var p = attrEnd
                if (selfClosing) {
                    p = j + attrs.trimEnd().length - 1
                }
                while (p > j && t[p - 1].isWhitespace()) p--
                c.add(
                    i, j, Severity.WARNING, "MISSING_ALT",
                    "Gambar di baris ${c.line(i)} belum punya atribut alt",
                    Fix("Tambah alt=\"\"", listOf(Edit(p, p, " alt=\"\"")))
                )
            }

            if (name in VOID_TAGS || selfClosing) {
                i = next
                continue
            }
            if (name == "script" || name == "style") {
                val closeRx = Regex("</$name\\s*>", RegexOption.IGNORE_CASE)
                val m = closeRx.find(t, next)
                val innerTo = m?.range?.first ?: n
                val type = TYPE_ATTR.find(attrs)?.groupValues?.get(1)?.lowercase()
                val isJs = type == null || type.contains("javascript") || type == "module"
                if (name == "style") scanCode(c, next, innerTo, css = true)
                else if (isJs) scanCode(c, next, innerTo, css = false)
                if (m == null) {
                    val needNl = n > 0 && t[n - 1] != '\n'
                    c.add(
                        i, j, Severity.ERROR, "UNCLOSED_TAG",
                        "Tag <$name> di baris ${c.line(i)} belum ditutup dengan </$name>",
                        Fix("Tambah </$name> di akhir", listOf(Edit(n, n, (if (needNl) "\n" else "") + "</$name>")))
                    )
                    return
                }
                i = m.range.last + 1
                continue
            }
            names.add(name)
            poss.add(i)
            i = next
        }

        // tag yang tidak pernah ditutup (innermost lebih dulu)
        var first = true
        for (k in names.indices.reversed()) {
            val nm = names[k]
            if (nm in OPTIONAL_END) continue
            val needNl = first && n > 0 && t[n - 1] != '\n'
            first = false
            c.add(
                poss[k], poss[k] + nm.length + 1, Severity.ERROR, "UNCLOSED_TAG",
                "Tag <$nm> di baris ${c.line(poss[k])} tidak pernah ditutup",
                Fix("Tambah </$nm> di akhir", listOf(Edit(n, n, (if (needNl) "\n" else "") + "</$nm>")))
            )
        }
    }

    private fun closeTag(
        c: Ctx, names: ArrayList<String>, poss: ArrayList<Int>, name: String, at: Int, end: Int
    ) {
        val idx = names.lastIndexOf(name)
        if (idx < 0) {
            if (name in VOID_TAGS) return
            c.add(
                at, end, Severity.ERROR, "STRAY_CLOSE_TAG",
                "Tag penutup di baris ${c.line(at)} tidak punya tag pembuka",
                Fix("Hapus tag penutup ini", listOf(Edit(at, end, "")))
            )
            return
        }
        for (k in names.size - 1 downTo idx + 1) {
            val inner = names[k]
            if (inner in OPTIONAL_END) continue
            c.add(
                poss[k], poss[k] + inner.length + 1, Severity.ERROR, "UNCLOSED_TAG",
                "Tag <$inner> di baris ${c.line(poss[k])} belum ditutup sebelum </$name> di baris ${c.line(at)}",
                Fix("Tambah </$inner> sebelum </$name>", listOf(Edit(at, at, "</$inner>")))
            )
        }
        while (names.size > idx) {
            names.removeAt(names.size - 1)
            poss.removeAt(poss.size - 1)
        }
    }

    // ---------------------------------------------------------------- JSON

    private class JsonScan(val c: Ctx) {
        val t = c.t
        val n = t.length
        var i = 0
        val stack = ArrayList<Char>()
        val stackPos = ArrayList<Int>()
        var eofDone = false
        val num = Regex("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")

        fun pop() {
            stack.removeAt(stack.size - 1)
            stackPos.removeAt(stackPos.size - 1)
        }

        fun run() {
            ws()
            if (i >= n) return
            if (!parseValue()) return
            ws()
            if (i < n) {
                var e = n
                while (e > i && t[e - 1].isWhitespace()) e--
                c.add(
                    i, e, Severity.ERROR, "TRAILING_CONTENT",
                    "Ada teks di baris ${c.line(i)} setelah data JSON selesai",
                    Fix("Hapus teks berlebih", listOf(Edit(i, e, "")))
                )
            }
        }

        fun ws() {
            while (i < n) {
                val x = t[i]
                if (x.isWhitespace()) {
                    i++
                } else if (x == '/' && i + 1 < n && t[i + 1] == '/') {
                    val e = c.lineEnd(i)
                    c.add(
                        i, e, Severity.ERROR, "JSON_COMMENT",
                        "Komentar tidak diizinkan di JSON (baris ${c.line(i)})",
                        Fix("Hapus komentar", listOf(Edit(i, e, "")))
                    )
                    i = e
                } else if (x == '/' && i + 1 < n && t[i + 1] == '*') {
                    val e = t.indexOf("*/", i + 2)
                    val end = if (e < 0) n else e + 2
                    c.add(
                        i, end, Severity.ERROR, "JSON_COMMENT",
                        "Komentar tidak diizinkan di JSON (baris ${c.line(i)})",
                        Fix("Hapus komentar", listOf(Edit(i, end, "")))
                    )
                    i = end
                } else {
                    break
                }
            }
        }

        fun isValueStart(x: Char) =
            x == '{' || x == '[' || x == '"' || x == '\'' || x == '-' || x.isDigit() || x.isLetter()

        fun isKeyStart(x: Char) = x == '"' || x == '\'' || x.isLetter() || x == '_' || x == '$'

        fun eof(): Boolean {
            if (eofDone) return false
            eofDone = true
            if (stack.isEmpty()) {
                c.add(n, n, Severity.ERROR, "UNEXPECTED_END", "JSON berakhir sebelum nilai lengkap")
                return false
            }
            var first = true
            for (k in stack.indices.reversed()) {
                val op = stack[k]
                val pos = stackPos[k]
                val cl = closerOf(op)
                val needNl = first && n > 0 && t[n - 1] != '\n'
                first = false
                c.add(
                    pos, pos + 1, Severity.ERROR, "UNCLOSED_BRACKET",
                    "Kurung '$op' di baris ${c.line(pos)} tidak ditutup",
                    Fix("Tambah '$cl' di akhir", listOf(Edit(n, n, (if (needNl) "\n" else "") + cl)))
                )
            }
            return false
        }

        fun parseValue(): Boolean {
            ws()
            if (i >= n) return eof()
            val x = t[i]
            return when {
                x == '{' -> parseObject()
                x == '[' -> parseArray()
                x == '"' -> { parseString(); true }
                x == '\'' -> { parseSingle(); true }
                x == '-' || x.isDigit() -> parseNumber()
                x.isLetter() -> parseWord()
                else -> {
                    c.add(i, i + 1, Severity.ERROR, "UNEXPECTED", "Karakter '$x' tidak terduga di baris ${c.line(i)}; nilai JSON diharapkan")
                    false
                }
            }
        }

        fun parseString() {
            val s = i
            var j = i + 1
            var closed = false
            while (j < n) {
                val x = t[j]
                if (x == '\\') { j += 2; continue }
                if (x == '"') { closed = true; break }
                if (x == '\n') break
                j++
            }
            if (!closed) {
                val ins = softLineEnd(t, s + 1, n)
                c.add(
                    s, maxOf(ins, s + 1), Severity.ERROR, "UNCLOSED_STRING",
                    "String di baris ${c.line(s)} tidak ditutup dengan tanda kutip",
                    Fix("Tambah tanda kutip penutup", listOf(Edit(ins, ins, "\"")))
                )
                i = minOf(j, n)
            } else {
                i = j + 1
            }
        }

        fun parseSingle() {
            val s = i
            var j = i + 1
            var closed = false
            while (j < n) {
                val x = t[j]
                if (x == '\\') { j += 2; continue }
                if (x == '\'') { closed = true; break }
                if (x == '\n') break
                j++
            }
            if (!closed) {
                val ins = softLineEnd(t, s + 1, n)
                c.add(
                    s, maxOf(ins, s + 1), Severity.ERROR, "SINGLE_QUOTE",
                    "String di baris ${c.line(s)} memakai tanda kutip tunggal dan tidak ditutup",
                    Fix("Ganti ke tanda kutip ganda dan tutup", listOf(Edit(s, s + 1, "\""), Edit(ins, ins, "\"")))
                )
                i = minOf(j, n)
                return
            }
            val raw = t.substring(s + 1, j)
            val inner = raw.replace("\\'", "'").replace(Regex("(?<!\\\\)\""), "\\\\\"")
            c.add(
                s, j + 1, Severity.ERROR, "SINGLE_QUOTE",
                "JSON harus memakai tanda kutip ganda, bukan tunggal (baris ${c.line(s)})",
                Fix("Ganti ke tanda kutip ganda", listOf(Edit(s, j + 1, "\"" + inner + "\"")))
            )
            i = j + 1
        }

        fun parseNumber(): Boolean {
            val m = num.matchAt(t, i)
            if (m == null) {
                c.add(i, i + 1, Severity.ERROR, "BAD_NUMBER", "Angka tidak valid di baris ${c.line(i)}")
                return false
            }
            i += m.value.length
            return true
        }

        fun parseWord(): Boolean {
            val s = i
            var j = i
            while (j < n && (t[j].isLetterOrDigit() || t[j] == '_' || t[j] == '$')) j++
            val w = t.substring(s, j)
            i = j
            when (w) {
                "true", "false", "null" -> Unit
                "True", "False", "None", "undefined", "NaN", "Infinity" -> {
                    val r = when (w) { "True" -> "true"; "False" -> "false"; else -> "null" }
                    c.add(
                        s, j, Severity.ERROR, "BAD_LITERAL",
                        "'$w' bukan literal JSON yang valid (baris ${c.line(s)}); gunakan $r",
                        Fix("Ganti '$w' menjadi $r", listOf(Edit(s, j, r)))
                    )
                }
                else -> c.add(
                    s, j, Severity.ERROR, "UNQUOTED_VALUE",
                    "Nilai teks di baris ${c.line(s)} harus diberi tanda kutip ganda",
                    Fix("Bungkus nilai dengan tanda kutip", listOf(Edit(s, j, "\"" + w + "\"")))
                )
            }
            return true
        }

        fun parseKey(): Boolean {
            val x = t[i]
            return when {
                x == '"' -> { parseString(); true }
                x == '\'' -> { parseSingle(); true }
                x.isLetter() || x == '_' || x == '$' -> {
                    var j = i
                    while (j < n && (t[j].isLetterOrDigit() || t[j] == '_' || t[j] == '$' || t[j] == '-')) j++
                    val w = t.substring(i, j)
                    c.add(
                        i, j, Severity.ERROR, "UNQUOTED_KEY",
                        "Key objek di baris ${c.line(i)} harus diberi tanda kutip ganda",
                        Fix("Bungkus key dengan tanda kutip", listOf(Edit(i, j, "\"" + w + "\"")))
                    )
                    i = j
                    true
                }
                else -> {
                    c.add(i, i + 1, Severity.ERROR, "BAD_KEY", "Key objek harus berupa string (baris ${c.line(i)})")
                    false
                }
            }
        }

        fun parseObject(): Boolean {
            stack.add('{')
            stackPos.add(i)
            i++
            ws()
            if (i >= n) return eof()
            if (t[i] == '}') { pop(); i++; return true }
            while (true) {
                ws()
                if (i >= n) return eof()
                if (!parseKey()) return false
                val keyEnd = i
                ws()
                if (i >= n) return eof()
                if (t[i] == ':') {
                    i++
                } else if (isValueStart(t[i])) {
                    c.add(
                        keyEnd, keyEnd, Severity.ERROR, "MISSING_COLON",
                        "Kurang tanda ':' setelah key di baris ${c.line(keyEnd)}",
                        Fix("Tambah ':'", listOf(Edit(keyEnd, keyEnd, ":")))
                    )
                } else {
                    c.add(i, i + 1, Severity.ERROR, "MISSING_COLON", "Kurang tanda ':' setelah key di baris ${c.line(i)}")
                    return false
                }
                if (!parseValue()) return false
                val valueEnd = i
                ws()
                if (i >= n) return eof()
                val x = t[i]
                when {
                    x == ',' -> {
                        val cp = i
                        i++
                        ws()
                        if (i >= n) return eof()
                        if (t[i] == '}') {
                            c.add(
                                cp, cp + 1, Severity.ERROR, "TRAILING_COMMA",
                                "Koma berlebih sebelum '}' di baris ${c.line(cp)}",
                                Fix("Hapus koma", listOf(Edit(cp, cp + 1, "")))
                            )
                            pop(); i++
                            return true
                        }
                        if (t[i] == ',') {
                            c.add(
                                i, i + 1, Severity.ERROR, "DOUBLE_COMMA",
                                "Koma ganda di baris ${c.line(i)}",
                                Fix("Hapus koma ganda", listOf(Edit(i, i + 1, "")))
                            )
                            i++
                        }
                    }
                    x == '}' -> { pop(); i++; return true }
                    x == ']' -> {
                        c.add(
                            i, i + 1, Severity.ERROR, "MISMATCH",
                            "Objek yang dibuka dengan '{' di baris ${c.line(stackPos[stackPos.size - 1])} ditutup dengan ']'",
                            Fix("Ganti ']' menjadi '}'", listOf(Edit(i, i + 1, "}")))
                        )
                        pop(); i++
                        return true
                    }
                    isKeyStart(x) -> c.add(
                        valueEnd, valueEnd, Severity.ERROR, "MISSING_COMMA",
                        "Kurang koma antara dua properti di baris ${c.line(valueEnd)}",
                        Fix("Tambah koma", listOf(Edit(valueEnd, valueEnd, ",")))
                    )
                    else -> {
                        c.add(i, i + 1, Severity.ERROR, "UNEXPECTED", "Karakter '$x' tidak terduga di baris ${c.line(i)}; mungkin kurang koma atau kurung")
                        return false
                    }
                }
            }
        }

        fun parseArray(): Boolean {
            stack.add('[')
            stackPos.add(i)
            i++
            ws()
            if (i >= n) return eof()
            if (t[i] == ']') { pop(); i++; return true }
            while (true) {
                if (!parseValue()) return false
                val valueEnd = i
                ws()
                if (i >= n) return eof()
                val x = t[i]
                when {
                    x == ',' -> {
                        val cp = i
                        i++
                        ws()
                        if (i >= n) return eof()
                        if (t[i] == ']') {
                            c.add(
                                cp, cp + 1, Severity.ERROR, "TRAILING_COMMA",
                                "Koma berlebih sebelum ']' di baris ${c.line(cp)}",
                                Fix("Hapus koma", listOf(Edit(cp, cp + 1, "")))
                            )
                            pop(); i++
                            return true
                        }
                        if (t[i] == ',') {
                            c.add(
                                i, i + 1, Severity.ERROR, "DOUBLE_COMMA",
                                "Koma ganda di baris ${c.line(i)}",
                                Fix("Hapus koma ganda", listOf(Edit(i, i + 1, "")))
                            )
                            i++
                        }
                    }
                    x == ']' -> { pop(); i++; return true }
                    x == '}' -> {
                        c.add(
                            i, i + 1, Severity.ERROR, "MISMATCH",
                            "Array yang dibuka dengan '[' di baris ${c.line(stackPos[stackPos.size - 1])} ditutup dengan '}'",
                            Fix("Ganti '}' menjadi ']'", listOf(Edit(i, i + 1, "]")))
                        )
                        pop(); i++
                        return true
                    }
                    isValueStart(x) -> c.add(
                        valueEnd, valueEnd, Severity.ERROR, "MISSING_COMMA",
                        "Kurang koma antara dua nilai array di baris ${c.line(valueEnd)}",
                        Fix("Tambah koma", listOf(Edit(valueEnd, valueEnd, ",")))
                    )
                    else -> {
                        c.add(i, i + 1, Severity.ERROR, "UNEXPECTED", "Karakter '$x' tidak terduga di baris ${c.line(i)}; mungkin kurang koma atau kurung")
                        return false
                    }
                }
            }
        }
    }
}
