package com.example.aidetest

import android.os.Build
import android.os.Vibrator
import android.os.VibrationEffect
import android.widget.CheckBox
import java.nio.charset.StandardCharsets
import android.util.Base64

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.text.InputType
import android.view.Gravity
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
        content.addView(button("Normalize / Inspect") {
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
        content.addView(button("Inspect") {
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
        content.addView(button("Parse") {
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
        content.addView(button("Periksa") {
            val p=input.text.toString()
            val score=(if(p.length>=8)1 else 0)+(if(p.length>=12)1 else 0)+(if(p.any(Char::isUpperCase))1 else 0)+(if(p.any(Char::isLowerCase))1 else 0)+(if(p.any(Char::isDigit))1 else 0)+(if(p.any{!it.isLetterOrDigit()})1 else 0)
            val level=when(score){0,1->"Sangat lemah";2,3->"Lemah";4->"Sedang";5->"Kuat";else->"Sangat kuat"}
            result.text="$level • skor $score/6\nPanjang: ${p.length}\nHuruf besar: ${p.any(Char::isUpperCase)} • kecil: ${p.any(Char::isLowerCase)} • angka: ${p.any(Char::isDigit)} • simbol: ${p.any{!it.isLetterOrDigit()}}"
        })
    }

internal fun MainActivity.stopwatchTool() {
        clearPage("Stopwatch")
        addToolHeader("Stopwatch", "Stopwatch lokal dengan start, pause, reset dan lap.", "TIME")
        val display=label("00:00.000",34f,true);display.gravity=Gravity.CENTER
        content.addView(display)
        var running=false; var started=0L; var accumulated=0L; var lastLap=0L
        val handler=Handler(Looper.getMainLooper())
        lateinit var tick:Runnable
        fun render(ms:Long){display.text=String.format(Locale.US,"%02d:%02d.%03d",(ms/60000)%60,(ms/1000)%60,ms%1000)}
        tick=Runnable { if(running){render(accumulated+(System.currentTimeMillis()-started));handler.postDelayed(tick,50)} }
        content.addView(button("Start / Pause") {
            if(running){accumulated+=System.currentTimeMillis()-started;running=false}
            else {started=System.currentTimeMillis();running=true;handler.post(tick)}
        })
        content.addView(button("Lap") {
            val now=if(running) accumulated+System.currentTimeMillis()-started else accumulated
            val lap=now-lastLap;lastLap=now
            output("Lap: ${String.format(Locale.US,"%02d:%02d.%03d",(lap/60000)%60,(lap/1000)%60,lap%1000)}")
        })
        content.addView(button("Reset") {running=false;accumulated=0;lastLap=0;render(0)})
    }

internal fun MainActivity.timerTool() {
        clearPage("Timer")
        addToolHeader("Timer", "Hitung mundur sederhana.", "TIME")
        val seconds=edit("Detik",false).apply{setText("60")};content.addView(seconds)
        val display=label("60 s",30f,true);display.gravity=Gravity.CENTER;content.addView(display)
        val handler=Handler(Looper.getMainLooper()); var runnable:Runnable?=null
        content.addView(button("Mulai") {
            runnable?.let{handler.removeCallbacks(it)}
            var left=seconds.text.toString().toLongOrNull()?.coerceIn(1,86400) ?: 60L
            runnable=object:Runnable{override fun run(){
                display.text="$left s"
                if(left<=0){
                    if(Build.VERSION.SDK_INT>=26)(getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(250,VibrationEffect.DEFAULT_AMPLITUDE))
                    toast("Timer selesai");return
                }
                left--;handler.postDelayed(this,1000)
            }}.also{handler.post(it)}
        })
        content.addView(button("Stop") {runnable?.let{handler.removeCallbacks(it)}})
    }

internal fun MainActivity.textStatTool() {
        clearPage("Statistik Teks")
        addToolHeader("Statistik Teks", "Hitung karakter, kata, dan baris dari teks dengan cepat.", "format-letter-case")
        content.addView(toolSection("INPUT", "Masukkan teks yang ingin dianalisis."))
        val e=edit("Teks", true); content.addView(e)
        content.addView(button("Hitung") {
            val s=e.text.toString()
            output("Karakter: ${s.length}\nKata: ${s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size}\nBaris: ${if(s.isEmpty()) 0 else s.lines().size}")
        })
    }

internal fun MainActivity.caseTool() {
        clearPage("Case Converter")
        addToolHeader("Case Converter", "Ubah kapitalisasi teks tanpa meninggalkan halaman.", "format-letter-case")
        content.addView(toolSection("INPUT", "Masukkan teks yang ingin diubah."))
        val e=edit("Teks", true); content.addView(e)
        content.addView(button("UPPER") { output(e.text.toString().toUpperCase(Locale.getDefault())) })
        content.addView(button("lower") { output(e.text.toString().toLowerCase(Locale.getDefault())) })
        content.addView(button("Title") { output(e.text.toString().split(Regex("\\s+")).joinToString(" ") { it.substring(0, 1).toUpperCase(Locale.getDefault()) + it.substring(1) }) })
    }

internal fun MainActivity.dedupeTool() {
        clearPage("Hapus Baris Duplikat")
        addToolHeader("Hapus Baris Duplikat", "Bersihkan item yang berulang dari daftar teks.", "content-duplicate")
        content.addView(toolSection("INPUT", "Satu baris untuk setiap item. Hasil tidak mengubah urutan pertama kali muncul."))
        val e = edit("Tempel atau ketik daftar di sini…", true)
        content.addView(e)

        val stats = toolStatus("0 baris • 0 duplikat")
        content.addView(stats)
        e.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val lines = s?.toString()?.lines()?.filter { it.isNotEmpty() } ?: emptyList()
                val dup = (lines.size - lines.distinct().size).coerceAtLeast(0)
                stats.text = "●  ${lines.size} baris • $dup duplikat"
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        content.addView(toolSection("OPTIONS"))
        val ignoreCase = CheckBox(this).apply { text = "Abaikan huruf besar/kecil"; textSize = 13f; setTextColor(textMain) }
        val ignoreSpace = CheckBox(this).apply { text = "Abaikan spasi di awal/akhir"; textSize = 13f; setTextColor(textMain) }
        content.addView(ignoreCase)
        content.addView(ignoreSpace)

        content.addView(button("Hapus Duplikat") {
            val original = e.text.toString().lines()
            val seen = LinkedHashSet<String>()
            val result = original.filter { line ->
                val key = line.let { if (ignoreSpace.isChecked) it.trim() else it }.let {
                    if (ignoreCase.isChecked) it.lowercase(Locale.getDefault()) else it
                }
                seen.add(key)
            }
            val removed = (original.size - result.size).coerceAtLeast(0)
            output("$removed duplikat dihapus\n${result.joinToString("\n")}")
        })
    }

internal fun MainActivity.compareTool() {
        clearPage("Bandingkan Teks")
        addToolHeader("Bandingkan Teks", "Bandingkan dua teks dan tampilkan baris yang berbeda.", "compare")
        content.addView(toolSection("INPUT A"))
        val a=edit("Teks A", true); content.addView(a)
        content.addView(toolSection("INPUT B"))
        val b=edit("Teks B", true); content.addView(b)
        content.addView(button("Bandingkan") {
            val aa=a.text.toString().lines(); val bb=b.text.toString().lines()
            val max=maxOf(aa.size,bb.size); val sb=StringBuilder()
            for(i in 0 until max) if((aa.getOrNull(i)?:"") != (bb.getOrNull(i)?:""))
                sb.append("- ").append(aa.getOrNull(i)?:"").append("\n+ ").append(bb.getOrNull(i)?:"").append("\n")
            output(if(sb.isEmpty()) "Tidak ada perbedaan." else sb.toString())
        })
    }

internal fun MainActivity.slugTool() {
        clearPage("Slug Generator")
        addToolHeader("Slug Generator", "Ubah judul menjadi slug URL yang bersih.", "link-variant")
        content.addView(toolSection("INPUT"))
        val e=edit("Judul"); content.addView(e)
        content.addView(button("Buat Slug") { output(e.text.toString().toLowerCase(Locale.getDefault()).replace(Regex("[^a-z0-9]+"), "-").trim('-')) })
    }

internal fun MainActivity.loremTool() {
        clearPage("Lorem Ipsum")
        addToolHeader("Lorem Ipsum", "Buat teks placeholder untuk desain, prototipe, dan layout.", "text-box")
        content.addView(toolSection("GENERATE", "Hasil dapat langsung disalin atau dibagikan."))
        content.addView(button("Buat 100 kata") {
            val words="lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore et dolore magna aliqua".split(" ")
            output((0 until 100).joinToString(" ") { words[it % words.size] })
        })
    }

internal fun MainActivity.urlTool() {
        clearPage("URL Tools")
        addToolHeader("URL Tools", "Encode atau decode teks URL tanpa keluar dari halaman.", "↗")
        content.addView(toolSection("VALUE")); val e=edit("Masukkan URL atau teks"); content.addView(e)
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        row.addView(button("Encode"){output(java.net.URLEncoder.encode(e.text.toString(),"UTF-8"))},LinearLayout.LayoutParams(0,dp(50),1f))
        row.addView(button("Decode"){output(runCatching{java.net.URLDecoder.decode(e.text.toString(),"UTF-8")}.getOrDefault("URL tidak valid"))},LinearLayout.LayoutParams(0,dp(50),1f).apply{leftMargin=dp(6)})
        content.addView(row)
    }

internal fun MainActivity.regexTool() {
        clearPage("Regex Tester")
        addToolHeader("Regex Tester", "Uji pattern dan lihat hasil match secara langsung.", ".*")
        content.addView(toolSection("PATTERN")); val p=edit("Contoh: \\d+"); content.addView(p)
        content.addView(toolSection("TEST TEXT")); val t=edit("Teks yang diuji",true); content.addView(t)
        val status=toolStatus("Belum diuji"); content.addView(status)
        content.addView(button("Test Pattern") { runCatching { val matches=Regex(p.text.toString()).findAll(t.text).map{it.value}.toList(); status.text="●  ${matches.size} match ditemukan"; output(if(matches.isEmpty())"Tidak ada match" else matches.joinToString("\n")) }.onFailure{status.text="●  Regex error"; output("Regex error: ${it.message}")} })
    }

internal fun MainActivity.simpleTransform(name: String, a: String, b: String) {
        clearPage(name)
        addToolHeader(name, "Proses input dengan dua mode utama dan lihat hasil tanpa meninggalkan halaman.", "↔")
        content.addView(toolSection("INPUT", "Masukkan teks atau data yang akan diproses."))
        val e = edit("Teks", true)
        content.addView(e)
        content.addView(toolSection("ACTIONS"))
        content.addView(button(a) { output(Base64.encodeToString(e.text.toString().toByteArray(), Base64.NO_WRAP)) })
        content.addView(button(b) {
            output(runCatching { String(Base64.decode(e.text.toString(), Base64.DEFAULT), StandardCharsets.UTF_8) }
                .getOrElse { "Input Base64 tidak valid" })
        })
    }

internal fun MainActivity.simpleResultTool(name: String, fn: () -> String) {
        clearPage(name)
        addToolHeader(name, toolDescription(name), "•")
        content.addView(toolSection("ACTION", "Jalankan fungsi utama; hasil otomatis tersedia untuk Salin/Bagikan."))
        content.addView(button("Generate") { output(fn()) })
    }
