package com.example.aidetest

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibrationEffect
import android.widget.CheckBox
import android.util.Base64
import java.nio.charset.StandardCharsets
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.text.InputType
import android.view.Gravity
import android.view.View
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Text & developer utility tools extracted from MainActivity (gradual modularization).
 */

internal fun MainActivity.markdownViewerTool() {
        clearPage("Markdown Viewer")
        addToolHeader("Markdown Viewer", "Tulis Markdown dan lihat preview HTML sederhana secara lokal.", "MD")
        val source = edit("Markdown", true)
        source.setText("# MyTools\n\n**Bold**, *italic*, `code`\n\n- Item satu\n- Item dua")
        content.addView(source)
        content.addView(button("Preview") {
            val html = markdownToHtml(source.text.toString())
            previewHtmlText("<!doctype html><html><meta name='viewport' content='width=device-width,initial-scale=1'><body style='font-family:sans-serif;padding:18px'>$html</body></html>", "HTML")
        })
        content.addView(button("Salin HTML") { copyText(markdownToHtml(source.text.toString())) })
    }

internal fun MainActivity.markdownToHtml(src: String): String {
        var t = android.text.TextUtils.htmlEncode(src)
        t = t.replace(Regex("(?m)^######\\s+(.+)$"), "<h6>$1</h6>")
            .replace(Regex("(?m)^#####\\s+(.+)$"), "<h5>$1</h5>")
            .replace(Regex("(?m)^####\\s+(.+)$"), "<h4>$1</h4>")
            .replace(Regex("(?m)^###\\s+(.+)$"), "<h3>$1</h3>")
            .replace(Regex("(?m)^##\\s+(.+)$"), "<h2>$1</h2>")
            .replace(Regex("(?m)^#\\s+(.+)$"), "<h1>$1</h1>")
            .replace(Regex("(?m)^-\\s+(.+)$"), "<li>$1</li>")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
            .replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
            .replace(Regex("`(.+?)`"), "<code>$1</code>")
            .replace("\n", "<br>")
        return t
    }

internal fun MainActivity.sqlToolsTool() {
        clearPage("SQL Tools")
        addToolHeader("SQL Tools", "Formatter dan pemeriksa dasar SQL untuk query developer.", "SQL")
        val input = edit("SELECT * FROM users WHERE id = 1;", true)
        content.addView(input)
        content.addView(button("Format SQL") {
            var q = input.text.toString().trim().replace(Regex("\\s+"), " ")
            val keywords = listOf("SELECT","FROM","WHERE","GROUP BY","ORDER BY","HAVING","LIMIT","VALUES","SET","JOIN","LEFT JOIN","RIGHT JOIN","INNER JOIN","INSERT INTO","UPDATE","DELETE FROM")
            keywords.sortedByDescending { it.length }.forEach { k ->
                q = q.replace(Regex("(?i)\\b${Regex.escape(k)}\\b"), "\n$k")
            }
            output(q.replace(Regex("\n "), "\n").trim())
        })
        content.addView(button("Inspect") {
            val q=input.text.toString().trim()
            val warnings=mutableListOf<String>()
            if(q.isBlank()) warnings.add("Query kosong")
            if(q.contains("SELECT",true) && !q.contains("FROM",true)) warnings.add("SELECT biasanya membutuhkan FROM")
            if(q.count{it=='('} != q.count{it==')'}) warnings.add("Kurung tidak seimbang")
            output(if(warnings.isEmpty()) "Pemeriksaan dasar: OK" else warnings.joinToString("\n"))
        })
    }

internal fun MainActivity.yamlFormatterTool() {
        clearPage("YAML Formatter")
        addToolHeader("YAML Formatter", "Normalisasi whitespace dan pemeriksaan struktur dasar YAML.", "YAML")
        val input = edit("key: value", true)
        content.addView(input)
        pinToolAction(button("Normalize / Inspect") {
            val lines = input.text.toString().lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val bad = lines.filter {
                val t=it.trimStart()
                !t.startsWith("-") && !t.startsWith("#") && !t.contains(":")
            }
            output((if (bad.isEmpty()) "Struktur dasar terlihat valid.\n\n" else "Baris yang perlu diperiksa:\n${bad.joinToString("\n")}\n\n") + lines.joinToString("\n"))
        })
    }

internal fun MainActivity.tomlInspectorTool() {
        clearPage("TOML Inspector")
        addToolHeader("TOML Inspector", "Pemeriksa section, key=value, komentar dan struktur dasar TOML.", "TOML")
        val input = edit("[server]\nport = 8080", true)
        content.addView(input)
        pinToolAction(button("Inspect") {
            val errors=mutableListOf<String>()
            input.text.toString().lines().forEachIndexed { i,line ->
                val t=line.trim()
                if(t.isBlank() || t.startsWith("#") || (t.startsWith("[") && t.endsWith("]"))) return@forEachIndexed
                if(!t.contains("=")) errors.add("Baris ${i+1}: tidak memiliki '='")
            }
            output(if(errors.isEmpty()) "TOML dasar terlihat valid." else errors.joinToString("\n"))
        })
    }

internal fun MainActivity.cronHelperTool() {
        clearPage("Cron Helper")
        addToolHeader("Cron Helper", "Baca 5 field cron dan jelaskan arti sederhananya.", "CRON")
        val input = edit("*/5 * * * *").apply { setText("*/5 * * * *") }
        content.addView(input)
        val fields = listOf("Menit","Jam","Hari Bulan","Bulan","Hari Minggu")
        pinToolAction(button("Parse") {
            val p=input.text.toString().trim().split(Regex("\\s+"))
            if(p.size!=5){output("Cron harus memiliki 5 field.");return@button}
            output(fields.indices.joinToString("\n") { i -> "${fields[i]}: ${cronFieldMeaning(p[i])}" })
        })
    }

internal fun MainActivity.cronFieldMeaning(v:String):String = when {
        v=="*" -> "setiap nilai"
        v.startsWith("*/") -> "setiap ${v.removePrefix("*/")}"
        v.contains("-") -> "rentang $v"
        v.contains(",") -> "daftar $v"
        else -> "nilai $v"
    }

internal fun MainActivity.passwordStrengthTool() {
        clearPage("Password Strength")
        addToolHeader("Password Strength", "Pemeriksaan lokal panjang dan keragaman karakter.", "SEC")
        val input=edit("Password")
        input.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        content.addView(input)
        val result=label("Belum diperiksa",15f,true);content.addView(result)
        pinToolAction(button("Periksa") {
            val p=input.text.toString()
            val score=(if(p.length>=8)1 else 0)+(if(p.length>=12)1 else 0)+(if(p.any(Char::isUpperCase))1 else 0)+(if(p.any(Char::isLowerCase))1 else 0)+(if(p.any(Char::isDigit))1 else 0)+(if(p.any{!it.isLetterOrDigit()})1 else 0)
            val level=when(score){0,1->"Sangat lemah";2,3->"Lemah";4->"Sedang";5->"Kuat";else->"Sangat kuat"}
            result.text="$level • skor $score/6\nPanjang: ${p.length}\nHuruf besar: ${p.any(Char::isUpperCase)} • kecil: ${p.any(Char::isLowerCase)} • angka: ${p.any(Char::isDigit)} • simbol: ${p.any{!it.isLetterOrDigit()}}"
        })
    }

/** Kunci tinggi content = tinggi viewport agar tidak bisa di-scroll & tombol bawah tidak terpotong (semua ukuran HP). */
internal fun MainActivity.lockContentToViewport() {
    fun apply() {
        val h = scroll.height - scroll.paddingTop - scroll.paddingBottom
        if (h <= 0) return
        val lp = content.layoutParams ?: return
        if (lp.height != h) { lp.height = h; content.layoutParams = lp }
    }
    apply()
    scroll.post { apply() }
    scroll.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
        if (b - t != ob - ot && currentPage in setOf("Stopwatch", "Timer")) apply()
    }
}

internal fun MainActivity.stopwatchTool() {
        clearPage("Stopwatch")
        // Layout fixed: display + daftar lap (scroll) + tombol diam di bawah.
        // Parent ScrollView diisi tinggi viewport agar tombol tidak ikut bergeser.

        fun fmt(ms: Long): String = String.format(
            Locale.US,
            "%02d:%02d.%03d",
            (ms / 60000) % 60,
            (ms / 1000) % 60,
            ms % 1000
        )

        content.setPadding(dp(16), dp(8), dp(16), dp(12))
        lockContentToViewport()

        val display = label("00:00.000", 44f, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
            letterSpacing = 0.02f
        }
        content.addView(display, LinearLayout.LayoutParams(-1, -2))

        val lapList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        // NestedScrollView: hanya daftar lap yang bergulir (~10 baris terlihat).
        val lapScroll = androidx.core.widget.NestedScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = false
            addView(lapList, LinearLayout.LayoutParams(-1, -2))
        }
        lapScroll.isNestedScrollingEnabled = true
        content.addView(lapScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(4)
            bottomMargin = dp(10)
        })

        var running = false
        var started = 0L
        var accumulated = 0L
        var lastLap = 0L
        var lapCount = 0
        val handler = Handler(Looper.getMainLooper())
        lateinit var tick: Runnable

        fun render(ms: Long) {
            display.text = fmt(ms)
        }

        fun addLapRow(index: Int, splitMs: Long, totalMs: Long) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                minimumHeight = dp(40)
            }
            val num = TextView(this).apply {
                text = String.format(Locale.US, "%02d", index)
                textSize = 14f
                setTextColor(textMuted)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            val split = TextView(this).apply {
                text = "+ ${fmt(splitMs)}"
                textSize = 14f
                setTextColor(textMuted)
                gravity = Gravity.CENTER
            }
            val total = TextView(this).apply {
                text = fmt(totalMs)
                textSize = 14f
                setTextColor(textMain)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            row.addView(num, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(split, LinearLayout.LayoutParams(0, -2, 1.4f))
            row.addView(total, LinearLayout.LayoutParams(0, -2, 1.2f))
            // Lap terbaru di atas; scroll ke atas agar nomor baru terlihat.
            lapList.addView(row, 0)
            lapScroll.post { lapScroll.scrollTo(0, 0) }
        }

        tick = Runnable {
            if (running) {
                render(accumulated + (System.currentTimeMillis() - started))
                handler.postDelayed(tick, 50)
            }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Tombol tetap di bawah — tidak di dalam area scroll lap.
        }

        val startBtn = button("Start / Pause") {
            if (running) {
                accumulated += System.currentTimeMillis() - started
                running = false
                handler.removeCallbacks(tick)
                render(accumulated)
            } else {
                started = System.currentTimeMillis()
                running = true
                handler.post(tick)
            }
        }
        val lapBtn = button("Lap") {
            val now = if (running) {
                accumulated + (System.currentTimeMillis() - started)
            } else {
                accumulated
            }
            if (now <= 0L && lapCount == 0) return@button
            val split = now - lastLap
            lastLap = now
            lapCount++
            addLapRow(lapCount, split, now)
        }
        styleAsSecondary(lapBtn)
        val resetBtn = button("Reset") {
            running = false
            handler.removeCallbacks(tick)
            accumulated = 0L
            lastLap = 0L
            lapCount = 0
            lapList.removeAllViews()
            render(0L)
        }
        styleAsSecondary(resetBtn)

        controls.addView(startBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        controls.addView(lapBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        controls.addView(resetBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        content.addView(controls, LinearLayout.LayoutParams(-1, -2))
    }

internal fun MainActivity.timerTool() {
        clearPage("Timer")
        var endAt = 0L
        content.setPadding(dp(16), dp(8), dp(16), dp(12))
        lockContentToViewport()
        content.addView(subLabel("Hitung mundur: bulan, hari, jam, menit, detik.", 13f).apply {
            setPadding(dp(2), dp(2), dp(2), dp(10))
        })

        // Input 5 kolom: bulan(30 hr), hari, jam, menit, detik
        val units = listOf("Bln", "Hari", "Jam", "Mnt", "Dtk")
        val secPer = longArrayOf(30L * 86400, 86400, 3600, 60, 1)
        val inputs = units.mapIndexed { i, u ->
            edit(u, false).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                gravity = Gravity.CENTER
                setText(if (i == 3) "1" else "0")
                setSingleLine(true)
                setPadding(dp(2), 0, dp(2), 0)
                textSize = 16f
            }
        }
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        inputs.forEachIndexed { i, e ->
            inputRow.addView(e, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                if (i > 0) leftMargin = dp(6)
            })
        }
        content.addView(inputRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Tampilan sisa waktu: 5 sel angka + satuan
        val cells = units.map { u ->
            val num = label("00", 28f, true).apply { gravity = Gravity.CENTER }
            val cap = subLabel(u, 12f).apply { gravity = Gravity.CENTER }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(num, LinearLayout.LayoutParams(-1, -2))
                addView(cap, LinearLayout.LayoutParams(-1, -2))
            }
            Triple(box, num, cap)
        }
        val displayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(24), 0, dp(12))
        }
        cells.forEach { displayRow.addView(it.first, LinearLayout.LayoutParams(0, -2, 1f)) }
        content.addView(displayRow, LinearLayout.LayoutParams(-1, -2))

        fun render(totalSec: Long) {
            var r = totalSec.coerceAtLeast(0)
            cells.forEachIndexed { i, c ->
                val v = r / secPer[i]; r %= secPer[i]
                c.second.text = String.format(Locale.US, "%02d", v)
            }
        }
        fun inputSeconds(): Long = inputs.indices.sumOf { (inputs[it].text.toString().toLongOrNull() ?: 0L) * secPer[it] }
            .coerceIn(0L, 99L * 30 * 86400)
        render(inputSeconds())
        inputs.forEach { e ->
            e.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { if (endAt == 0L) render(inputSeconds()) }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }

        // Spacer mendorong tombol ke bawah; tombol selalu terlihat
        content.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))

        val handler = Handler(Looper.getMainLooper())
        var runnable: Runnable? = null

        val startButton = button("Mulai") {
            runnable?.let { handler.removeCallbacks(it) }
            val total = inputSeconds()
            if (total <= 0L) { toast("Isi waktu dulu"); return@button }
            endAt = SystemClock.elapsedRealtime() + total * 1000
            runnable = object : Runnable {
                override fun run() {
                    val left = ((endAt - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)
                    render(left)
                    if (left <= 0) {
                        endAt = 0L
                        runCatching {
                            if (Build.VERSION.SDK_INT >= 26) {
                                (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                                    .vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
                            }
                        }
                        toast("Timer selesai")
                        runnable = null
                        return
                    }
                    handler.postDelayed(this, 200)
                }
            }.also { handler.post(it) }
        }
        val stopButton = button("Stop") {
            runnable?.let { handler.removeCallbacks(it) }
            runnable = null
            endAt = 0L
            render(inputSeconds())
        }
        styleAsSecondary(stopButton)

        content.addView(startButton, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(8) })
        content.addView(stopButton, LinearLayout.LayoutParams(-1, dp(56)))
    }

internal fun MainActivity.textStatTool() {
        clearPage("Statistik Teks")
        addToolHeader("Statistik Teks", "Analisis live: karakter, kata, baris, kalimat, dan estimasi baca.", "format-letter-case")
        content.addView(toolSection("INPUT", "Ketik atau tempel teks — statistik update otomatis."))
        val e = edit("Teks…", true)
        content.addView(e)
        val live = toolStatus("0 karakter • 0 kata • 0 baris")
        content.addView(live)
        fun refresh() {
            val s = e.text.toString()
            val chars = s.length
            val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            val lines = if (s.isEmpty()) 0 else s.lines().size
            val sentences = s.split(Regex("[.!?]+")).filter { it.trim().isNotEmpty() }.size
            val mins = ((words / 200.0).coerceAtLeast(0.1) * 10).toInt() / 10.0
            live.text = "●  $chars karakter • $words kata • $lines baris • $sentences kalimat • ~${mins} mnt baca"
        }
        e.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { refresh() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        content.addView(toolSection("ACTIONS"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Salin ringkas") {
            val s = e.text.toString()
            val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            val summary = "Karakter: ${s.length}\nKata: $words\nBaris: ${if (s.isEmpty()) 0 else s.lines().size}"
            output(summary)
            copyToClipboard(summary)
            toast("Ringkasan disalin")
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        row.addView(button("Detail") {
            val s = e.text.toString()
            val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val freq = words.groupingBy { it.lowercase(Locale.getDefault()) }.eachCount().entries.sortedByDescending { it.value }.take(10)
            output(buildString {
                appendLine("Karakter (dengan spasi): ${s.length}")
                appendLine("Karakter (tanpa spasi): ${s.count { !it.isWhitespace() }}")
                appendLine("Kata: ${words.size}")
                appendLine("Baris: ${if (s.isEmpty()) 0 else s.lines().size}")
                appendLine("Paragraf: ${s.split(Regex("\\n\\s*\\n")).filter { it.trim().isNotEmpty() }.size}")
                if (freq.isNotEmpty()) {
                    appendLine("\nTop kata:")
                    freq.forEach { appendLine("  ${it.key}: ${it.value}") }
                }
            })
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(6) })
        content.addView(row)
    }

internal fun MainActivity.caseTool() {
        clearPage("Case Converter")
        addToolHeader("Case Converter", "Ubah kapitalisasi teks ke beberapa gaya.", "format-letter-case")
        content.addView(toolSection("INPUT"))
        val e = edit("Teks…", true)
        // Input lebih ringkas agar tombol & hasil tidak mendorong layar.
        e.layoutParams = LinearLayout.LayoutParams(-1, dp(120)).apply { bottomMargin = dp(Ds.SPACE_MD) }
        content.addView(e)

        // Satu kotak output di ATAS tombol style — diganti tiap kali style diketuk (tidak menumpuk).
        val outHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = bg(panel2, Ds.RADIUS_LG, line)
        }
        val outLabel = label("Hasil", 13f, true)
        val outText = TextView(this).apply {
            textSize = 15f
            setTextColor(textMain)
            setTextIsSelectable(true)
            setPadding(0, dp(6), 0, dp(10))
        }
        outHost.addView(outLabel)
        outHost.addView(outText)
        val outActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val copyBtn = button("Salin") { copyText(outText.text?.toString().orEmpty()) }
        styleAsSecondary(copyBtn)
        outActions.addView(copyBtn, LinearLayout.LayoutParams(0, -2, 1f))
        outHost.addView(outActions)
        content.addView(outHost, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(Ds.SPACE_MD)
        })

        content.addView(toolSection("STYLES", "Ketuk gaya — hasil diganti di kotak atas."))
        fun titleCase(s: String) = s.lowercase(Locale.getDefault()).split(Regex("\\s+")).joinToString(" ") {
            if (it.isEmpty()) it else it.substring(0, 1).uppercase(Locale.getDefault()) + it.substring(1)
        }
        fun snake(s: String) = s.trim().lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]+"), "_").trim('_')
        fun camel(s: String): String {
            val parts = s.trim().lowercase(Locale.getDefault()).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return ""
            return parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        }
        fun kebab(s: String) = s.trim().lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val styles = listOf(
            "UPPERCASE" to { s: String -> s.uppercase(Locale.getDefault()) },
            "lowercase" to { s: String -> s.lowercase(Locale.getDefault()) },
            "Title Case" to { s: String -> titleCase(s) },
            "camelCase" to { s: String -> camel(s) },
            "snake_case" to { s: String -> snake(s) },
            "kebab-case" to { s: String -> kebab(s) }
        )
        styles.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))
            }
            pair.forEachIndexed { i, (name, fn) ->
                val b = button(name) {
                    val r = fn(e.text.toString())
                    outText.text = r
                    outHost.visibility = android.view.View.VISIBLE
                    outLabel.text = "Hasil · $name"
                }
                // Tombol tidak dempet: margin antar kolom & baris.
                row.addView(
                    b,
                    LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                        if (i == 0) marginEnd = dp(8) else marginStart = dp(8)
                    }
                )
            }
            content.addView(row)
        }
        content.addView(
            chipRow("Contoh cepat", listOf("Hello World App", "git ls mobile", "API_RESPONSE_CODE")) {
                e.setText(it)
            }
        )
    }

internal enum class DedupeMode { LINE, WORD, CUSTOM }

internal data class DedupeResult(val before: List<String>, val after: List<String>, val joiner: String) {
    val removed: Int get() = (before.size - after.size).coerceAtLeast(0)
    fun text(): String = after.joinToString(joiner)
}

private val dedupeEdgePunct = Regex("^\\p{P}+|\\p{P}+$")

/** Mengubah "\\n", "\\t" yang diketik pengguna menjadi karakter sebenarnya. */
internal fun dedupeUnescape(s: String): String = s.replace("\\n", "\n").replace("\\t", "\t")

/**
 * Logika murni penghapus duplikat (mudah diuji).
 * - LINE: item = baris (perilaku lama, default).
 * - WORD: item = kata yang dipisah spasi/baris baru. Tanda baca ikut dihitung kecuali [ignorePunct].
 * - CUSTOM: item = potongan teks di antara [delimiter] (literal, bukan regex).
 */
internal fun dedupeItems(
    text: String, mode: DedupeMode, delimiter: String = ",",
    ignoreCase: Boolean = false, trim: Boolean = true, ignorePunct: Boolean = false,
    keepOrder: Boolean = true, wordPerLine: Boolean = false
): DedupeResult {
    val raw: List<String> = when (mode) {
        DedupeMode.LINE -> text.lines()
        DedupeMode.WORD -> text.split(Regex("\\s+"))
        DedupeMode.CUSTOM -> if (delimiter.isEmpty()) listOf(text) else text.split(delimiter)
    }
    val items = raw.map { if (trim || mode == DedupeMode.WORD) it.trim() else it }.filter { it.isNotEmpty() }
    val seen = HashSet<String>()
    val out = ArrayList<String>()
    for (item in items) {
        var key = item
        if (mode == DedupeMode.WORD && ignorePunct) {
            val stripped = dedupeEdgePunct.replace(key, "")
            if (stripped.isNotEmpty()) key = stripped
        }
        if (ignoreCase) key = key.lowercase(Locale.ROOT)
        if (seen.add(key)) out.add(item)
    }
    val ordered = if (keepOrder) out else out.sortedBy { it.lowercase(Locale.ROOT) }
    val joiner = when (mode) {
        DedupeMode.LINE -> "\n"
        DedupeMode.WORD -> if (wordPerLine) "\n" else " "
        // Pemisah satu karakter (, ; |) diberi spasi sesudahnya agar hasil mudah dibaca.
        DedupeMode.CUSTOM -> if (trim && delimiter.length == 1 && delimiter in ",;|") "$delimiter " else delimiter
    }
    return DedupeResult(items, ordered, joiner)
}

internal fun MainActivity.dedupeTool() {
    clearPage("Hapus Duplikat")
    addToolHeader("Hapus Duplikat", "Bersihkan item yang berulang dari daftar teks.", "content-duplicate")

    var mode = DedupeMode.LINE
    content.addView(toolSection("MODE", "Pilih cara teks dipecah menjadi item."))
    val modeGroup = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
    val monochromeControl = if (isDarkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    val controlTint = android.content.res.ColorStateList.valueOf(monochromeControl)
    val rbLine = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "Per baris (bawaan)"; isChecked = true; buttonTintList = controlTint }
    val rbWord = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "Per kata (dipisah spasi)"; buttonTintList = controlTint }
    val rbCustom = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "Pemisah khusus"; buttonTintList = controlTint }
    modeGroup.addView(rbLine); modeGroup.addView(rbWord); modeGroup.addView(rbCustom)
    content.addView(modeGroup)
    val modeHelp = subLabel("", 11f)
    content.addView(modeHelp)
    val delim = edit("Pemisah, mis: ,  ;  |  atau \\n").apply { setText(","); visibility = View.GONE }
    content.addView(delim)

    val inputHeader = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(toolSection("INPUT", "Tempel teks. Urutan kemunculan pertama dipertahankan."), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    val e = edit("Tempel atau ketik daftar di sini…", true).apply { minLines = 3; maxLines = 7 }
    val sample = TextView(this).apply {
        text = "Isi contoh"
        textSize = 12f
        setTextColor(textMuted)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(6), dp(8), dp(6))
        isClickable = true
        isFocusable = true
    }
    styleAsTertiary(sample)
    inputHeader.addView(sample, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
    content.addView(inputHeader)
    content.addView(e)

    content.addView(toolSection("OPSI"))
    val ignoreCase = CheckBox(this).apply { text = "Abaikan huruf besar/kecil"; isChecked = false }
    val trimItems = CheckBox(this).apply { text = "Pangkas spasi tepi item"; isChecked = true }
    val keepOrder = CheckBox(this).apply { text = "Pertahankan urutan awal"; isChecked = true }
    val ignorePunct = CheckBox(this).apply { text = "Abaikan tanda baca di tepi kata (GitLis, = GitLis)"; isChecked = false; visibility = View.GONE }
    val wordPerLine = CheckBox(this).apply { text = "Hasil satu kata per baris"; isChecked = false; visibility = View.GONE }
    listOf(ignoreCase, trimItems, keepOrder, ignorePunct, wordPerLine).forEach { it.buttonTintList = controlTint }
    listOf(ignoreCase, trimItems, keepOrder, ignorePunct, wordPerLine).forEach { content.addView(it) }

    content.addView(toolSection("PRATINJAU"))
    val stats = toolStatus("Awal: 0 item • Unik: 0 • Duplikat dihapus: 0")
    content.addView(stats)
    val before = subLabel("Sebelum: —", 11f)
    val after = subLabel("Sesudah: —", 11f)
    content.addView(before); content.addView(after)

    fun compute(): DedupeResult = dedupeItems(
        e.text.toString(), mode, dedupeUnescape(delim.text.toString()),
        ignoreCase.isChecked, trimItems.isChecked, ignorePunct.isChecked, keepOrder.isChecked, wordPerLine.isChecked
    )
    fun preview(s: String): String { val t = s.replace("\n", " ⏎ "); return if (t.length > 220) t.take(220) + "…" else t.ifEmpty { "—" } }
    fun refresh() {
        if (e.text.length > 100_000) { stats.text = "Teks besar — tekan \"Hapus duplikat\" untuk memproses"; return }
        if (mode == DedupeMode.CUSTOM && delim.text.isEmpty()) { stats.text = "Isi pemisah dulu"; return }
        val r = compute()
        stats.text = "●  Awal: ${r.before.size} item • Unik: ${r.after.size} • Duplikat dihapus: ${r.removed}"
        before.text = "Sebelum: " + preview(r.before.joinToString(r.joiner))
        after.text = "Sesudah: " + preview(r.text())
    }
    fun applyMode(m: DedupeMode) {
        mode = m
        delim.visibility = if (m == DedupeMode.CUSTOM) View.VISIBLE else View.GONE
        ignorePunct.visibility = if (m == DedupeMode.WORD) View.VISIBLE else View.GONE
        wordPerLine.visibility = if (m == DedupeMode.WORD) View.VISIBLE else View.GONE
        modeHelp.text = when (m) {
            DedupeMode.LINE -> "Satu baris dihitung sebagai satu item."
            DedupeMode.WORD -> "Pecah teks berdasarkan spasi dan baris baru."
            DedupeMode.CUSTOM -> "Pakai pemisah sendiri, misalnya koma."
        }
        refresh()
    }
    modeGroup.setOnCheckedChangeListener { _, id ->
        applyMode(when (id) { rbWord.id -> DedupeMode.WORD; rbCustom.id -> DedupeMode.CUSTOM; else -> DedupeMode.LINE })
    }
    val watcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }
    e.addTextChangedListener(watcher); delim.addTextChangedListener(watcher)
    listOf(ignoreCase, trimItems, keepOrder, ignorePunct, wordPerLine).forEach { it.setOnCheckedChangeListener { _, _ -> refresh() } }

    pinToolAction(button("Hapus duplikat") {
        if (mode == DedupeMode.CUSTOM && delim.text.isEmpty()) { toast("Isi pemisah dulu"); return@button }
        val r = compute()
        output(r.text())
        stats.text = "●  ${r.before.size} → ${r.after.size} item (hapus ${r.removed})"
    })
    sample.setOnClickListener {
        e.setText("GitHub, GitLis, Android, GitHub, APK Builder, GitLis")
        rbCustom.isChecked = true
    }
    applyMode(DedupeMode.LINE)
}

internal fun MainActivity.compareTool() {
        clearPage("Bandingkan Teks")
        addToolHeader("Bandingkan Teks", "Lihat perbedaan dua teks baris demi baris.", "compare")
        content.addView(toolSection("TEKS A"))
        val a = edit("Teks A", true); content.addView(a)
        content.addView(toolSection("TEKS B"))
        val b = edit("Teks B", true); content.addView(b)
        pinToolAction(button("Bandingkan") {
            val la = a.text.toString().lines()
            val lb = b.text.toString().lines()
            val max = maxOf(la.size, lb.size)
            val sb = StringBuilder()
            var diff = 0
            for (i in 0 until max) {
                val x = la.getOrNull(i)
                val y = lb.getOrNull(i)
                when {
                    x == null -> { sb.appendLine("+ [${i + 1}] $y"); diff++ }
                    y == null -> { sb.appendLine("- [${i + 1}] $x"); diff++ }
                    x != y -> { sb.appendLine("≠ [${i + 1}]"); sb.appendLine("  A: $x"); sb.appendLine("  B: $y"); diff++ }
                }
            }
            if (diff == 0) output("Identik — tidak ada perbedaan.")
            else output("$diff baris berbeda:\n\n$sb")
        })
    }

internal fun MainActivity.slugTool() {
        clearPage("Slug Generator")
        addToolHeader("Slug Generator", "Buat slug URL-friendly dari judul, dengan opsi pemisah.", "link-variant")
        content.addView(toolSection("JUDUL"))
        val e = edit("Judul artikel…")
        content.addView(e)
        val live = toolStatus("slug-akan-muncul-di-sini")
        content.addView(live)
        fun slugify(s: String, sep: String = "-") =
            s.trim().lowercase(Locale.getDefault())
                .replace(Regex("[^a-z0-9\\s-]"), "")
                .replace(Regex("\\s+"), sep)
                .replace(Regex("$sep+"), sep)
                .trim(sep.first())
        e.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                live.text = "●  " + slugify(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Salin slug") {
            val s = slugify(e.text.toString())
            output(s); copyToClipboard(s); toast("Slug disalin")
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        row.addView(button("snake_case") {
            val s = slugify(e.text.toString(), "_"); output(s)
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(6) })
        content.addView(row)
    }

internal fun MainActivity.loremTool() {
        clearPage("Lorem Ipsum")
        addToolHeader("Lorem Ipsum", "Generate teks placeholder untuk mockup UI.", "format-text")
        val words = listOf(
            "lorem","ipsum","dolor","sit","amet","consectetur","adipiscing","elit","sed","do",
            "eiusmod","tempor","incididunt","ut","labore","et","dolore","magna","aliqua","ut",
            "enim","ad","minim","veniam","quis","nostrud","exercitation","ullamco","laboris","nisi"
        )
        fun gen(n: Int) = (1..n).joinToString(" ") { words.random() }.replaceFirstChar { it.uppercaseChar() } + "."
        content.addView(toolSection("PANJANG"))
        listOf(20 to "Pendek", 50 to "Sedang", 100 to "Panjang", 200 to "Artikel").chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, (n, label) ->
                row.addView(button("$label ($n)") { output(gen(n)) },
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) leftMargin = dp(6) })
            }
            content.addView(row)
        }
        pinToolAction(button("Paragraf ×3") {
            output((1..3).joinToString("\n\n") { gen(40 + (0..30).random()) })
        })
    }

internal fun MainActivity.urlTool() {
        clearPage("URL Encode / Decode")
        addToolHeader("URL Encode / Decode", "Encode aman untuk query string, atau decode kembali. Live preview.", "link")
        content.addView(toolSection("VALUE"))
        val e = edit("Masukkan URL atau teks")
        content.addView(e)
        val live = toolStatus("Hasil encode muncul di sini saat mengetik")
        content.addView(live)
        e.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                live.text = "●  " + runCatching {
                    java.net.URLEncoder.encode(s?.toString().orEmpty(), "UTF-8")
                }.getOrDefault("(error)")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Encode") {
            output(java.net.URLEncoder.encode(e.text.toString(), "UTF-8"))
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        row.addView(button("Decode") {
            output(runCatching { java.net.URLDecoder.decode(e.text.toString(), "UTF-8") }.getOrDefault("URL tidak valid"))
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(6) })
        content.addView(row)
        content.addView(chipRow("Contoh", listOf("hello world", "a=1&b=two", "https://gitls.app/path?q=1")) { e.setText(it) })
    }

internal fun MainActivity.regexTool() {
        clearPage("Regex Tester")
        addToolHeader("Regex Tester", "Uji pattern seperti di VS Code: match live, grup, flags.", ".*")
        content.addView(toolSection("PATTERN", "Contoh: \\d{3}-\\d{4}  atau  (?i)error"))
        val p = edit("Pattern…"); content.addView(p)
        content.addView(toolSection("TEST TEXT"))
        val t = edit("Teks yang diuji…", true); content.addView(t)
        val ignoreCase = CheckBox(this).apply { text = "Ignore case (i)"; isChecked = false }
        val multiline = CheckBox(this).apply { text = "Multiline (m)"; isChecked = false }
        content.addView(ignoreCase)
        content.addView(multiline)
        val status = toolStatus("Ketik pattern & teks — match update otomatis")
        content.addView(status)
        fun runTest() {
            val pattern = p.text.toString()
            val body = t.text.toString()
            if (pattern.isEmpty()) { status.text = "●  Pattern kosong"; return }
            runCatching {
                var opts = setOf<RegexOption>()
                if (ignoreCase.isChecked) opts = opts + RegexOption.IGNORE_CASE
                if (multiline.isChecked) opts = opts + RegexOption.MULTILINE
                val re = Regex(pattern, opts)
                val matches = re.findAll(body).toList()
                status.text = "●  ${matches.size} match"
                if (matches.isEmpty()) {
                    output("Tidak ada match")
                } else {
                    output(matches.mapIndexed { i, m ->
                        buildString {
                            append("[$i] \"${m.value}\" @${m.range.first}")
                            if (m.groups.size > 1) {
                                append("  groups: ")
                                append(m.groupValues.drop(1).joinToString(", ") { "\"$it\"" })
                            }
                        }
                    }.joinToString("\n"))
                }
            }.onFailure {
                status.text = "●  Error: ${it.message}"
                output("Regex error: ${it.message}")
            }
        }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { runTest() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        p.addTextChangedListener(watcher)
        t.addTextChangedListener(watcher)
        ignoreCase.setOnCheckedChangeListener { _, _ -> runTest() }
        multiline.setOnCheckedChangeListener { _, _ -> runTest() }
        pinToolAction(button("Test ulang") { runTest() })
        content.addView(chipRow("Contoh pattern", listOf("\\d+", "[A-Z][a-z]+", "\\b\\w+@\\w+\\.\\w+\\b", "#\\w+")) {
            p.setText(it); runTest()
        })
    }

internal fun MainActivity.simpleTransform(name: String, a: String, b: String) {
        clearPage(name)
        addToolHeader(name, "Encode / decode dengan preview. Hasil bisa disalin langsung.", "↔")
        content.addView(toolSection("INPUT"))
        val e = edit("Teks atau data…", true)
        content.addView(e)
        val live = toolStatus("Preview encode muncul saat mengetik")
        content.addView(live)
        e.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                live.text = "●  " + Base64.encodeToString(
                    (s?.toString() ?: "").toByteArray(), Base64.NO_WRAP
                ).take(80).let { if (it.length >= 80) "$it…" else it }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        content.addView(toolSection("ACTIONS"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(a) {
            val r = Base64.encodeToString(e.text.toString().toByteArray(), Base64.NO_WRAP)
            output(r)
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        row.addView(button(b) {
            output(runCatching {
                String(Base64.decode(e.text.toString().trim(), Base64.DEFAULT), StandardCharsets.UTF_8)
            }.getOrElse { "Input Base64 tidak valid" })
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(6) })
        content.addView(row)
        content.addView(chipRow("Contoh", listOf("Hello Gitls", "user:pass")) { e.setText(it) })
    }

internal fun MainActivity.simpleResultTool(name: String, fn: () -> String) {
        clearPage(name)
        addToolHeader(name, toolDescription(name), "•")
        content.addView(toolSection("GENERATE", "Hasil langsung bisa disalin / dibagikan dari panel output."))
        content.addView(button("Generate") { output(fn()) })
        content.addView(button("Generate ×5") {
            output((1..5).joinToString("\n") { fn() })
        })
    }

/** Chip contoh cepat di bawah tool input. */
internal fun MainActivity.chipRow(title: String, samples: List<String>, onPick: (String) -> Unit): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        col.addView(TextView(this).apply {
            text = title
            textSize = 11f
            setTextColor(textMuted)
            setPadding(0, 0, 0, dp(4))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        samples.forEach { sample ->
            val chip = TextView(this).apply {
                text = sample.take(22)
                textSize = 11f
                setTextColor(textMain)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = bg(if (isDarkTheme) panel2 else android.graphics.Color.rgb(236, 238, 242), 14, line)
                isClickable = true
                setOnClickListener { onPick(sample) }
            }
            row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
        col.addView(row)
        return col
    }

internal fun MainActivity.copyToClipboard(text: String) {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clip.setPrimaryClip(android.content.ClipData.newPlainText("Gitls", text))
    }
