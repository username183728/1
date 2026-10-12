package com.example.aidetest

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import java.util.concurrent.RejectedExecutionException
import android.Manifest
import android.app.*
import android.app.usage.StorageStatsManager
import android.os.StatFs
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.*
import android.provider.Settings
import android.provider.MediaStore
import android.media.ImageReader
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.text.InputType
import android.view.*
import android.widget.*
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.MultiFormatReader
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.common.HybridBinarizer
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.*
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.roundToInt
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback
import com.airbnb.lottie.LottieListener


internal fun MainActivity.startHomeWifiHosting() {
        val root=pendingHostingRoot ?: File(prefs.getString("last_web_project","") ?: "")
        val port=pendingHostingPort
        if(!root.isDirectory || !File(root,"index.html").isFile){toast("Project tidak valid");return}
        stopWifiHtmlHosting()
        hostingStatusView?.text="MEMULAI SERVER…"
        val addresses=localIpv4Addresses()
        val host=wifiIpv4Address() ?: ""
        if(host.isBlank()) {
            hostingStatusView?.text="GAGAL • HP tidak terhubung ke Wi-Fi"; toast("Hubungkan HP ke Wi-Fi rumah dulu"); return
        }
        // Hosting Wi-Fi dibatasi ke interface Wi-Fi dan memakai token sesi acak.
        // Ini mencegah server terbuka ke interface lain dan mencegah orang di LAN
        // membaca project tanpa URL/token yang diberikan aplikasi.
        webHostingToken = android.util.Base64.encodeToString(
            ByteArray(24).also { SecureRandom().nextBytes(it) },
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )
        if(!startStaticWebServer(root,port,hostingStatusView,host,webHostingToken)){return}
        val link="http://$host:$port/?gitls_token=${Uri.encode(webHostingToken)}"
        hostingUrlView?.text="URL: $link\nAlamat lain: ${addresses.drop(1).joinToString(", ").ifBlank{"-"}}"
        val savedSsid = prefs.getString("last_hosting_ssid", null)?.takeIf { it.isNotBlank() } ?: currentWifiSsid()
        val savedPass: String? = null // Password Wi-Fi tidak lagi dipersist demi keamanan.
        val passNote = if (savedPass != null) "dicatat (opsional, tidak dipakai server)" else "perangkat lain harus sudah terhubung ke Wi-Fi yang sama"
        hostingCredentialsView?.text = "SSID: $savedSsid\nPassword Wi-Fi: $passNote"
        hostingStatusView?.text="SUCCESS • HOSTING AKTIF"
        hostingQrView?.visibility=View.VISIBLE
        generateHostingQr(link)
        toast("Hosting berhasil • buka URL dari perangkat lain")
    }
internal fun MainActivity.wifiIpv4Address(): String? {
        return runCatching {
            val all=NetworkInterface.getNetworkInterfaces()
            while(all.hasMoreElements()) {
                val ni=all.nextElement()
                val name=ni.name?.lowercase(Locale.US).orEmpty()
                if(!ni.isUp || ni.isLoopback || !(name.contains("wlan") || name.contains("wifi"))) continue
                val addrs=ni.inetAddresses
                while(addrs.hasMoreElements()) {
                    val a=addrs.nextElement()
                    if(a is Inet4Address && !a.isLoopbackAddress) return@runCatching a.hostAddress
                }
            }
            null
        }.getOrNull()
    }
internal fun MainActivity.currentWifiSsid(): String {
        return runCatching {
            val wm=applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION") val ssid=wm.connectionInfo?.ssid?.trim('"')
            if(ssid.isNullOrBlank() || ssid=="<unknown ssid>") "SSID tidak tersedia" else ssid
        }.getOrDefault("SSID tidak tersedia")
    }
internal fun MainActivity.ensureHotspotPermissionAndStart() { startHomeWifiHosting() }
internal fun MainActivity.startWifiHtmlHosting() { startHomeWifiHosting() }
internal fun MainActivity.stopWifiHtmlHosting() {
        stopStaticWebServer()
        hostingStatusView?.text="STOPPED"
        hostingUrlView?.text="URL: -"
        hostingCredentialsView?.text="SSID: -\nPassword Wi-Fi: -"
        hostingQrView?.visibility=View.GONE
    }
internal fun MainActivity.localIpv4Addresses(): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            val all = NetworkInterface.getNetworkInterfaces()
            while (all.hasMoreElements()) {
                val ni = all.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        if (!out.contains(ip)) out.add(ip)
                    }
                }
            }
        }
        return out
    }
internal fun MainActivity.generateHostingQr(url: String) {
        runCatching {
            val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 600, 600)
            val bmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
            for (x in 0 until 600) for (y in 0 until 600) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
            hostingQrView?.setImageBitmap(bmp)
        }
    }
internal fun MainActivity.timestampTool() {
        clearPage("Timestamp Converter")
        toolWorkspace("Timestamp Converter", "Konversi Unix timestamp ↔ tanggal lokal.", "clock-outline")
        val e=edit("Unix timestamp atau tanggal ISO"); content.addView(e)
        content.addView(button("Sekarang") { output("Unix seconds: ${System.currentTimeMillis()/1000}\nUnix millis: ${System.currentTimeMillis()}\nLocal: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}") })
        content.addView(button("Timestamp → Tanggal") {
            output(runCatching {
                val raw=e.text.toString().trim(); val ms=if(raw.length>10) raw.toLong() else raw.toLong()*1000
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(ms))
            }.getOrElse { "Timestamp tidak valid" })
        })
        content.addView(button("Tanggal → Timestamp") {
            output(runCatching {
                val formats=listOf("yyyy-MM-dd HH:mm:ss","yyyy-MM-dd HH:mm:ss.SSS","yyyy-MM-dd'T'HH:mm:ss","yyyy-MM-dd")
                val d=formats.asSequence().mapNotNull { f -> runCatching { SimpleDateFormat(f, Locale.getDefault()).apply { isLenient=false }.parse(e.text.toString().trim()) }.getOrNull() }.firstOrNull() ?: error("Format tidak dikenali")
                "Unix seconds: ${d.time/1000}\nUnix millis: ${d.time}"
            }.getOrElse { "Tanggal tidak valid: ${it.message}" })
        })
    }
internal fun MainActivity.unicodeTool() {
        clearPage("Unicode Inspector")
        toolWorkspace("Unicode Inspector", "Inspect code point, nama, dan escape Unicode.", "format-letter-case")
        val e=edit("Teks", true); content.addView(e)
        content.addView(button("Inspect") {
            val s=e.text.toString(); val sb=StringBuilder()
            var offset = 0
            var index = 0
            while (offset < s.length) {
                val cp = s.codePointAt(offset)
                val ch = String(Character.toChars(cp))
                sb.append(index).append("  ").append(ch).append("  U+")
                    .append(cp.toString(16).uppercase(Locale.getDefault()).padStart(4,'0'))
                    .append("  ").append(Character.getName(cp) ?: "UNKNOWN").append('\n')
                offset += Character.charCount(cp)
                index++
            }
            output(if(sb.isEmpty()) "Tidak ada karakter." else sb.toString())
        })
        content.addView(button("Text → \\uXXXX") {
            val s = e.text.toString()
            val sb = StringBuilder()
            var offset = 0
            while (offset < s.length) {
                val cp = s.codePointAt(offset)
                sb.append("\\u").append(String.format(Locale.US, "%04X", cp))
                offset += Character.charCount(cp)
            }
            output(sb.toString())
        })
    }
internal fun MainActivity.urlParserTool() {
        clearPage("URL Parser")
        toolWorkspace("URL Parser", "Pecah URL menjadi scheme, host, path, query, fragment.", "link-variant")
        val e=edit("https://example.com/path?a=1#section"); content.addView(e)
        pinToolAction(button("Parse") {
            output(runCatching {
                val u=URL(e.text.toString().trim())
                "Protocol: ${u.protocol}\nHost: ${u.host}\nPort: ${if(u.port==-1) "default" else u.port}\nPath: ${u.path}\nQuery: ${u.query ?: ""}\nFragment: ${u.ref ?: ""}\nUserInfo: ${u.userInfo ?: ""}"
            }.getOrElse { "URL tidak valid: ${it.message}" })
        })
    }
internal fun MainActivity.mimeTool() {
        clearPage("MIME Type Lookup")
        toolWorkspace("MIME Type Lookup", "Cari MIME type dari ekstensi file.", "file-find")
        val e=edit("nama file, contoh photo.png"); content.addView(e)
        pinToolAction(button("Lookup") {
            val ext=e.text.toString().substringAfterLast('.',"").lowercase(Locale.getDefault())
            output(if(ext.isEmpty()) "Ekstensi tidak ditemukan" else "Extension: .$ext\nMIME: ${MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"}")
        })
    }
/** Format JSON dengan indentasi spasi (2/4/8). Tidak mengubah nilai data — hanya whitespace. */
internal fun MainActivity.prettyJson(s: String, indentSpaces: Int = 4): String {
        val t = s.trim()
        val n = indentSpaces.coerceIn(0, 8)
        return if (t.startsWith("{")) JSONObject(t).toString(n) else JSONArray(t).toString(n)
    }
internal fun MainActivity.minifyJson(s: String): String {
        val t = s.trim()
        return if (t.startsWith("{")) JSONObject(t).toString() else JSONArray(t).toString()
    }
internal fun MainActivity.jsonFormatTool() {
        // Satu halaman dengan JSON Tools (Format / Minify / Validate / Copy).
        jsonTool()
    }
internal fun MainActivity.xmlFormatTool() {
        clearPage("XML Formatter")
        toolWorkspace("XML Formatter", "Rapikan dan periksa struktur XML dasar.", "xml")
        val e=edit("XML",true); content.addView(e)
        pinToolAction(button("Format XML") {
            output(runCatching {
                val f=javax.xml.transform.TransformerFactory.newInstance().newTransformer().apply {
                    setOutputProperty(javax.xml.transform.OutputKeys.INDENT,"yes")
                    setOutputProperty("{http://xml.apache.org/xslt}indent-amount","2")
                }
                val sw=StringWriter(); f.transform(javax.xml.transform.stream.StreamSource(StringReader(e.text.toString())), javax.xml.transform.stream.StreamResult(sw)); sw.toString()
            }.getOrElse { "XML error: ${it.message}" })
        })
    }
internal fun MainActivity.uuidBatchTool() {
        clearPage("UUID Batch Generator")
        toolWorkspace("UUID Batch Generator", "Generate banyak UUID sekaligus.", "identifier")
        val n=edit("Jumlah UUID (1-100)"); n.setText("10"); content.addView(n)
        pinToolAction(button("Generate") {
            val count=(n.text.toString().toIntOrNull() ?: 10).coerceIn(1,100)
            output((1..count).joinToString("\n") { UUID.randomUUID().toString() })
        })
    }
internal fun MainActivity.base64FileTool() {
        clearPage("Base64 File Tool")
        toolWorkspace("Base64 File Tool", "Encode/decode file Base64 di perangkat.", "file-code")
        content.addView(button("Encode File → Base64") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 1010)
        })
        content.addView(label("Pilih file untuk membaca Base64. File besar diproses dengan batas 8 MB untuk menjaga RAM."))
    }
internal fun MainActivity.httpHeadersTool() {
        clearPage("HTTP Headers")
        toolWorkspace("HTTP Headers", "Lihat response header dari URL.", "api")
        val e=edit("https://example.com"); content.addView(e)
        pinToolAction(button("GET Headers") {
            // OPEN-10: tanpa skema → anggap https://; http:// non-localhost ditolak dengan pesan jelas.
            val raw = e.text.toString().trim().let { if (it.isNotEmpty() && !it.contains("://")) "https://$it" else it }
            httpUrlProblem(raw)?.let { toast(it); output(it); return@button }
            toolThread {
                val result=runCatching {
                    val c=(URL(raw).openConnection() as HttpURLConnection).apply { requestMethod="HEAD"; connectTimeout=7000; readTimeout=7000; instanceFollowRedirects=true }
                    c.connect(); val sb=StringBuilder("Status: ${c.responseCode} ${c.responseMessage}\n")
                    c.headerFields.forEach { (k,v) -> if(k!=null) sb.append(k).append(": ").append(v.joinToString(", ")).append('\n') }
                    c.disconnect(); sb.toString()
                }.getOrElse { "HTTP error: ${it.message}" }
                runOnToolUiThread { output(result) }
            }
        })
    }
internal fun MainActivity.textReplaceTool() {
        clearPage("Find & Replace")
        toolWorkspace("Find & Replace", "Cari dan ganti teks secara lokal.", "find-replace")
        val text=edit("Teks",true); val find=edit("Cari"); val repl=edit("Ganti dengan")
        content.addView(text); content.addView(find); content.addView(repl)
        pinToolAction(button("Replace All") { output(text.text.toString().replace(find.text.toString(),repl.text.toString())) })
    }
internal fun MainActivity.wordFrequencyTool() {
        clearPage("Word Frequency")
        toolWorkspace("Word Frequency", "Hitung frekuensi kata dalam teks.", "counter")
        val e=edit("Teks",true); content.addView(e)
        pinToolAction(button("Analyze") {
            val map=e.text.toString().lowercase(Locale.getDefault()).split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            output(if(map.isEmpty()) "Tidak ada kata." else map.take(100).joinToString("\n") { "${it.key}: ${it.value}" })
        })
    }
internal fun MainActivity.digest(alg:String, bytes:ByteArray):String =
        MessageDigest.getInstance(alg).digest(bytes).joinToString("") { "%02x".format(it) }

internal fun MainActivity.randomString(n:Int, chars:String):String {
        val r=SecureRandom(); return buildString { repeat(n) { append(chars[r.nextInt(chars.length)]) } }
    }
internal fun MainActivity.randomBytes(n:Int):String {
        val b=ByteArray(n); SecureRandom().nextBytes(b); return b.joinToString("") { "%02x".format(it) }
    }
internal fun MainActivity.decodeB64Url(s:String):String =
        runCatching { String(android.util.Base64.decode(s.padEnd((s.length+3)/4*4,'='), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP), StandardCharsets.UTF_8) }.getOrElse { "decode error" }

internal fun MainActivity.totp(secret:String,counter:Long):String {
        val key=Base32.decode(secret)
        val data=ByteArray(8)
        for(i in 7 downTo 0) data[i]=(counter ushr (8*(7-i))).toByte()
        val mac=Mac.getInstance("HmacSHA1"); mac.init(SecretKeySpec(key,"HmacSHA1"))
        val h=mac.doFinal(data); val o=h.last().toInt() and 15
        var v=0
        for(i in 0..3) v=(v shl 8) or (h[o+i].toInt() and 255)
        return "%06d".format((v and 0x7fffffff)%1000000)
    }
internal fun MainActivity.aesKey(pass:String):ByteArray =
        MessageDigest.getInstance("SHA-256").digest(pass.toByteArray(StandardCharsets.UTF_8))

internal fun MainActivity.aesKeyV2(pass:String, salt:ByteArray):ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(pass.toCharArray(), salt, 120_000, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }
internal fun MainActivity.aesEncrypt(pass:String, plain:String):String {
        require(pass.isNotEmpty()) { "Password kosong" }
        val salt=ByteArray(16); val iv=ByteArray(12); SecureRandom().nextBytes(salt); SecureRandom().nextBytes(iv)
        val c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyV2(pass,salt),"AES"), GCMParameterSpec(128,iv))
        val enc=c.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        return "MYTOOLS-AES2:" + android.util.Base64.encodeToString(salt+iv+enc, android.util.Base64.NO_WRAP)
    }
internal fun MainActivity.aesDecrypt(pass:String, encoded:String):String {
        if (encoded.startsWith("MYTOOLS-AES2:")) {
            val all=android.util.Base64.decode(encoded.removePrefix("MYTOOLS-AES2:"), android.util.Base64.DEFAULT)
            require(all.size > 28) { "Data AES2 tidak lengkap" }
            val salt=all.copyOfRange(0,16); val iv=all.copyOfRange(16,28); val enc=all.copyOfRange(28,all.size)
            val c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyV2(pass,salt),"AES"), GCMParameterSpec(128,iv))
            return String(c.doFinal(enc), StandardCharsets.UTF_8)
        }
        // Compatibility with the previous MyTools AES format.
        val all=android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        require(all.size > 12) { "Data AES lama tidak lengkap" }
        val iv=all.copyOfRange(0,12); val enc=all.copyOfRange(12,all.size)
        val c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, SecretKeySpec(aesKey(pass),"AES"), GCMParameterSpec(128,iv))
        return String(c.doFinal(enc), StandardCharsets.UTF_8)
    }
internal fun MainActivity.filePickCard(titleText: String, actionText: String, onClick: () -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = bg(panel2, Ds.RADIUS_LG, line)
        }
        val icon = MdiIconView(this).apply {
            setIconName("file-outline"); setIconSize(22f); setTextColor(textMain)
            background = bg(panel, Ds.RADIUS_MD, line)
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        val labelView = label(titleText, 14f, true).apply { tag = "fileLabel" }
        card.addView(labelView, LinearLayout.LayoutParams(0, -2, 1f))
        val action = TextView(this).apply {
            text = actionText; textSize = 12f; gravity = Gravity.CENTER; minHeight = dp(40); setPadding(dp(10), 0, dp(10), 0)
            styleAsSecondary(this); isClickable = true; setOnClickListener { onClick() }
        }
        card.addView(action, LinearLayout.LayoutParams(dp(110), -2))
        card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        return card
    }
internal fun MainActivity.digestStream(input: InputStream, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            md.update(buffer, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }
internal fun MainActivity.showLockedFilePicker(password: String) {
        val local = filesDir.listFiles { f -> f.isFile && f.name.endsWith(".mytools.enc") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val items = arrayOf("Pilih dari penyimpanan...") + local.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Buka file terkunci")
            .setItems(items) { _, i ->
                if (i == 0) {
                    lockedOpenPassword = password
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    }, SECURITY_OPEN_PICK)
                } else {
                    val f = local[i - 1]
                    openLockedFile(f.name, password) { FileInputStream(f) }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }
internal fun MainActivity.displayNameOf(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment ?: "file"
    }
internal fun MainActivity.openLockedFile(displayName: String, password: String, source: () -> InputStream?) {
        toast("Membuka file...")
        toolThread {
            val result = runCatching {
                val all = (source() ?: error("File tidak bisa dibuka")).use { it.readBytes() }
                val head = "MYTOOLS-FILE-AES2".toByteArray(StandardCharsets.US_ASCII)
                require(all.size > head.size + 28 && all.copyOfRange(0, head.size).contentEquals(head)) { "Format file tidak dikenali" }
                val salt = all.copyOfRange(head.size, head.size + 16)
                val iv = all.copyOfRange(head.size + 16, head.size + 28)
                val enc = all.copyOfRange(head.size + 28, all.size)
                val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                c.init(javax.crypto.Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyV2(password, salt), "AES"), GCMParameterSpec(128, iv))
                val plain = c.doFinal(enc)
                val base = displayName.substringAfterLast('/')
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .removeSuffix(".mytools.enc").ifBlank { "file" }
                val dir = File(cacheDir, "opened").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                File(dir, base).also { f -> FileOutputStream(f).use { it.write(plain) } }
            }
            runOnToolUiThread {
                result.onSuccess { launchDecryptedViewer(it) }
                    .onFailure { output("Gagal: password salah atau file rusak (${it.message})") }
            }
        }
    }
internal fun MainActivity.launchDecryptedViewer(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(view)
            output("File berhasil dibuka\n${file.name} (${file.length()} byte)")
        } catch (e: ActivityNotFoundException) {
            output("Dekripsi berhasil, tapi tidak ada aplikasi yang bisa membuka ${file.name}.\nGunakan tombol Dekripsi untuk menyimpan hasilnya.")
        }
    }
internal fun MainActivity.helpBotTool() {
        clearPage("HelpBot Offline")
        addToolHeader("HelpBot Offline", "Chat bantuan lokal untuk memahami fungsi, permission, dan cara memakai tool MyTools. Tidak membutuhkan API/AI.", "HELP")
        val q = edit("Contoh: apa fungsi pipet warna?", false)
        content.addView(q)
        val out = label("Tanyakan fungsi atau cara memakai tool.", 14f)
        content.addView(out)

        val answers = listOf(
            Triple(listOf("pipet", "eyedropper", "warna layar", "ambil warna"), "Konverter Warna (HEX/RGB)", "Konversi warna HEX/RGB. Pipet tangkapan layar telah dinonaktifkan demi privasi."),
            Triple(listOf("wifi", "wi-fi", "wlan"), "Wi-Fi Info", "Menampilkan informasi jaringan Wi-Fi yang tersedia bagi aplikasi. Pada Android modern, beberapa operasi Wi-Fi membutuhkan izin Nearby Wi-Fi dan/atau lokasi tergantung API yang digunakan."),
            Triple(listOf("hash", "sha256", "checksum"), "Hash / Checksum", "Menghasilkan sidik jari data seperti SHA-256. Berguna untuk memverifikasi apakah file yang diterima sama dengan file sumber."),
            Triple(listOf("apk analyzer", "apk", "aplikasi analyzer"), "APK Analyzer", "Membaca metadata APK seperti package, versi, SDK, permission, sertifikat, dan komponen yang dapat membantu pemeriksaan teknis."),
            Triple(listOf("encrypt", "enkripsi", "file encryption"), "File Encryption", "Mengenkripsi file agar isi tidak mudah dibaca tanpa kunci. Jangan menghapus file asli sebelum memastikan hasil enkripsi dapat dibuka kembali."),
            Triple(listOf("totp", "2fa", "otp"), "2FA Manager", "Membuat kode OTP berbasis waktu untuk akun yang mendukung TOTP. Secret harus dijaga seperti password."),
            Triple(listOf("server", "hosting", "wifi hosting"), "HTTP Server / HTML Hosting", "Membuat server lokal di jaringan perangkat. Gunakan hanya pada jaringan yang dipercaya dan hentikan server setelah selesai."),
            Triple(listOf("url safety", "url", "phishing"), "URL Safety Checker", "Memeriksa beberapa indikator heuristik seperti HTTPS, punycode, userinfo, dan pola URL. Hasil 'tidak ada indikator' bukan jaminan bahwa situs aman."),
            Triple(listOf("network scanner", "scanner jaringan", "lan scanner"), "Network Scanner", "Mendeteksi host/port pada jaringan yang sedang digunakan. Gunakan hanya pada jaringan/perangkat yang kamu miliki atau punya izin untuk diuji."),
            Triple(listOf("help", "bantuan", "cara", "fungsi"), "HelpBot", "Saya bisa menjelaskan fungsi tool, permission yang dibutuhkan, contoh penggunaan, dan masalah umum secara offline." )
        )

        fun answer(raw: String): String {
            val text = raw.trim().lowercase(Locale.getDefault())
            if (text.isBlank()) return "Tulis pertanyaan terlebih dahulu."
            val hit = answers.firstOrNull { row -> row.first.any { key -> text.contains(key) } }
            return if (hit != null) "${hit.second}\n\n${hit.third}" else "Tool belum cocok dengan pertanyaan itu. Coba sebut nama tool, misalnya: pipet warna, APK Analyzer, hash, Wi-Fi, TOTP, enkripsi, atau Network Scanner."
        }

        content.addView(button("Tanya") { out.text = answer(q.text.toString()) })
        content.addView(button("Apa fungsi MyTools?") { out.text = "MyTools adalah kumpulan utility untuk file, jaringan, developer, keamanan, warna, perangkat, dan produktivitas. HelpBot menjelaskan fungsi tool secara offline." })
        content.addView(button("Cara aman memakai Security Tools") { out.text = "Gunakan tool jaringan hanya pada jaringan yang kamu miliki/izinkan. Jangan membagikan password, token, secret TOTP, atau API key. Untuk server lokal, hentikan server setelah selesai." })
    }
internal fun MainActivity.decodeSampledBitmap(uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
internal fun MainActivity.encodeStego(uri:Uri,text:String):String {
        val opts=BitmapFactory.Options().apply{inMutable=true;inPreferredConfig=Bitmap.Config.ARGB_8888}
        val bmp=contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,opts)}?:error("Gambar tidak dapat dibaca")
        try {
            val payload="MYTOOLS-STG1:${text.length}:$text".toByteArray(StandardCharsets.UTF_8)
            val totalBits=payload.size*8
            val w=bmp.width
            require(totalBits.toLong()<=w.toLong()*bmp.height*3L){"Pesan terlalu panjang untuk gambar ini"}
            val row=IntArray(w)
            var k=0
            var y=0
            while(y<bmp.height&&k<totalBits){
                bmp.getPixels(row,0,w,0,y,w,1)
                var x=0
                while(x<w&&k<totalBits){
                    val px=row[x]
                    var r=Color.red(px);var g=Color.green(px);var b=Color.blue(px)
                    if(k<totalBits){r=(r and 254) or ((payload[k shr 3].toInt() shr (7-(k and 7))) and 1);k++}
                    if(k<totalBits){g=(g and 254) or ((payload[k shr 3].toInt() shr (7-(k and 7))) and 1);k++}
                    if(k<totalBits){b=(b and 254) or ((payload[k shr 3].toInt() shr (7-(k and 7))) and 1);k++}
                    row[x]=Color.argb(Color.alpha(px),r,g,b)
                    x++
                }
                bmp.setPixels(row,0,w,0,y,w,1)
                y++
            }
            val out=File(filesDir,"stego_${System.currentTimeMillis()}.png")
            FileOutputStream(out).use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
            return "Pesan disembunyikan.\n${out.absolutePath}"
        } finally { bmp.recycle() }
    }
internal fun MainActivity.decodeStegoFromUri(uri:Uri){
        toolThread {
            val result=runCatching{
                val bmp=contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it)}?:error("Gambar tidak bisa dibaca")
                try { readStegoMessage(bmp) } finally { bmp.recycle() }
            }.getOrElse{"Gagal: ${it.message}"}
            runOnToolUiThread{output(result)}
        }
    }
internal fun MainActivity.readStegoMessage(bmp:Bitmap):String {
        val marker="MYTOOLS-STG1:"
        val header=StringBuilder()
        val body=ByteArrayOutputStream()
        var want=-1
        var cur=0;var n=0
        val w=bmp.width
        val row=IntArray(w)
        val maxWant=(w.toLong()*bmp.height*3L/8L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        for(y in 0 until bmp.height){
            bmp.getPixels(row,0,w,0,y,w,1)
            for(x in 0 until w){
                val px=row[x]
                for(c in 0..2){
                    val bit=when(c){0->(px shr 16) and 1;1->(px shr 8) and 1;else->px and 1}
                    cur=(cur shl 1) or bit;n++
                    if(n<8)continue
                    val byte=cur and 0xFF;cur=0;n=0
                    if(want<0){
                        header.append(byte.toChar())
                        if(header.length<=marker.length){
                            if(!marker.startsWith(header.toString()))error("Pesan tersembunyi tidak ditemukan")
                        } else {
                            val ch=header[header.length-1]
                            if(ch==':'){
                                val len=header.substring(marker.length,header.length-1).toIntOrNull()?:error("Pesan tersembunyi tidak ditemukan")
                                if(len<0||len>maxWant)error("Pesan tersembunyi tidak valid")
                                want=len
                                if(want==0)return ""
                            } else if(!ch.isDigit()||header.length>marker.length+10){
                                error("Pesan tersembunyi tidak ditemukan")
                            }
                        }
                    } else {
                        body.write(byte)
                        if(body.size()>=want){
                            val decoded=runCatching{
                                StandardCharsets.UTF_8.newDecoder()
                                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                                    .decode(java.nio.ByteBuffer.wrap(body.toByteArray())).toString()
                            }.getOrNull()
                            if(decoded!=null&&decoded.length>=want)return decoded.substring(0,want)
                        }
                    }
                }
            }
        }
        error("Pesan tersembunyi tidak ditemukan")
    }
internal fun MainActivity.sshKeyGeneratorTool(){
    val activity = this
    clearPage("SSH Key Generator");addToolHeader("SSH Key Generator","Generate RSA atau Ed25519 key pair lokal.","SSH");val type=segmentedChoice(listOf("RSA 3072","Ed25519"));content.addView(type);val out=label("Belum dibuat",13f);content.addView(out);content.addView(button("Generate") {toolThread {val r=runCatching{val alg=if(type.selectedItem.toString().startsWith("RSA"))"RSA" else "Ed25519";val gen=KeyPairGenerator.getInstance(alg);if(alg=="RSA")gen.initialize(3072);val kp=gen.generateKeyPair();val priv=android.util.Base64.encodeToString(kp.private.encoded, android.util.Base64.DEFAULT);val pub=android.util.Base64.encodeToString(kp.public.encoded, android.util.Base64.DEFAULT);"PRIVATE KEY (PKCS#8):\n-----BEGIN PRIVATE KEY-----\n$priv\n-----END PRIVATE KEY-----\n\nPUBLIC KEY (X.509):\n-----BEGIN PUBLIC KEY-----\n$pub\n-----END PUBLIC KEY-----"}.getOrElse{"Gagal: ${it.message}"};runOnToolUiThread{out.text=r}}})}
internal fun MainActivity.certificateViewerTool(){clearPage("Certificate Viewer");addToolHeader("Certificate Viewer","Lihat detail sertifikat X.509 dari file.","CERT");content.addView(button("Pilih Sertifikat") {startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="application/x-x509-ca-cert";addCategory(Intent.CATEGORY_OPENABLE)},CERT_PICK)});content.addView(label("Mendukung sertifikat X.509/DER/PEM yang dapat diparse Android."))}
internal fun MainActivity.viewCertificate(uri:Uri){toolThread {val r=runCatching{val raw=contentResolver.openInputStream(uri)?:error("File tidak bisa dibaca");val cert=raw.use{CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate};"Subject: ${cert.subjectX500Principal.name}\nIssuer: ${cert.issuerX500Principal.name}\nSerial: ${cert.serialNumber.toString(16)}\nValid dari: ${cert.notBefore}\nValid sampai: ${cert.notAfter}\nSignature: ${cert.sigAlgName}\nPublic key: ${cert.publicKey.algorithm}"}.getOrElse{"Gagal parse sertifikat: ${it.message}"};runOnToolUiThread{output(r)}}}
internal fun MainActivity.imageStudioTool() {
        // Hub ke tool gambar Stage 1
        studioHub(
            "Image Studio",
            "Edit, hapus latar, kompres, konversi, dan trace gambar ke SVG.",
            listOf(
                "Foto Studio (foto, desain, hapus latar)" to "creativestudio",
                "Image Compressor" to "imagecompress",
                "Image Converter" to "imageconvert",
                "Auto Trace (Image → SVG)" to "autotrace",
                "Color Tools" to "color",
                "OCR" to "ocr"
            )
        )
        return
        @Suppress("UNREACHABLE_CODE")
        clearPage("Image Studio")
        toolWorkspace("Image Studio", "Workspace utilitas gambar lokal.", "image")

        // Header is supplied by the app toolbar. Keep the workspace title-free to avoid
        // duplicate headings and use the same monochrome DesignSystem as the rest of GITLS.
        val intro = subLabel("Resize, kompres, konversi, putar, flip, dan simpan gambar dengan cepat.", 12f)
        intro.setPadding(dp(2), dp(4), dp(2), dp(10))
        content.addView(intro)

        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        applyInteractiveSurface(previewCard, Ds.RADIUS_LG, 1)

        val preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(190))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = bg(if (isDarkTheme) Color.rgb(32,32,35) else Color.rgb(244,246,248), 16, line)
            visibility = View.GONE
        }
        previewCard.addView(preview)

        val status = label("Belum ada gambar", 13f)
        status.setPadding(dp(4), dp(8), dp(4), dp(2))
        previewCard.addView(status)
        content.addView(previewCard)

        var selectedUri: Uri? = null
        var originalBitmap: Bitmap? = null
        var workingBitmap: Bitmap? = null
        var keepRatio = true
        var lastOutput: File? = null
        var outputMime = "image/jpeg"
        var rotation = 0
        var flipHorizontal = false
        var flipVertical = false

        content.addView(label("Resize & Preset", 15f, true).apply {
            setPadding(dp(2), dp(14), dp(2), dp(6))
        })

        lateinit var width: EditText
        lateinit var height: EditText
        lateinit var ratioButton: Button
        lateinit var downloadBtn: Button
        lateinit var shareBtn: Button
        lateinit var outputInfo: TextView

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val presetData = listOf(
            "Asli" to null,
            "HD" to 1920,
            "Story" to 1080,
            "Square" to 1080,
            "Thumb" to 512
        )
        presetData.forEachIndexed { index, pair ->
            val b = button(pair.first) {
                val bmp = originalBitmap ?: run { toast("Pilih gambar terlebih dahulu"); return@button }
                when (pair.first) {
                    "Asli" -> { width.setText(bmp.width.toString()); height.setText(bmp.height.toString()) }
                    "Square" -> { width.setText("1080"); height.setText("1080"); keepRatio = false; ratioButton.text = "Rasio bebas" }
                    else -> {
                        val target = pair.second ?: bmp.width
                        val w = target.coerceAtMost(8000)
                        val h = (bmp.height.toDouble() * w / bmp.width).roundToInt().coerceAtLeast(1)
                        width.setText(w.toString()); height.setText(h.toString()); keepRatio = true; ratioButton.text = "Pertahankan rasio"
                    }
                }
            }
            styleAsSecondary(b)
            presetRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) leftMargin = dp(5)
            })
        }
        content.addView(presetRow)

        width = edit("Lebar (px)", false)
        height = edit("Tinggi (px)", false)
        width.hint = "Lebar"
        height.hint = "Tinggi"
        width.visibility = View.GONE
        height.visibility = View.GONE
        content.addView(width); content.addView(height)

        ratioButton = button("Pertahankan rasio") {
            keepRatio = !keepRatio
            ratioButton.text = if (keepRatio) "Pertahankan rasio" else "Rasio bebas"
        }
        ratioButton.visibility = View.GONE
        content.addView(ratioButton)

        val formatLabel = label("Format output", 13f, true)
        formatLabel.visibility = View.GONE
        content.addView(formatLabel)
        val formatRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val formatButtons = listOf("JPG" to "image/jpeg", "PNG" to "image/png", "WebP" to "image/webp")
        val formatButtonList = mutableListOf<Button>()
        formatButtons.forEachIndexed { index, pair ->
            lateinit var formatButton: Button
            formatButton = button(pair.first) {
                outputMime = pair.second
                formatButtonList.forEach { styleAsSecondary(it) }
                styleAsPrimary(formatButton)
                formatLabel.text = "Format output: ${pair.first}"
            }
            val b = formatButton
            if (index == 0) styleAsPrimary(b) else styleAsSecondary(b)
            formatButtonList.add(b)
            formatRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(5) })
        }
        formatRow.visibility = View.GONE
        content.addView(formatRow)

        val qualityLabel = label("Kualitas: 85%", 13f)
        val quality = SeekBar(this).apply { max = 100; progress = 85 }
        quality.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                qualityLabel.text = "Kualitas: ${progress.coerceAtLeast(1)}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        qualityLabel.visibility = View.GONE; quality.visibility = View.GONE
        content.addView(qualityLabel); content.addView(quality)

        val editLabel = label("Edit cepat", 15f, true)
        editLabel.visibility = View.GONE
        content.addView(editLabel)
        val editRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var rotateBtn: Button
        lateinit var flipHBtn: Button
        lateinit var flipVBtn: Button
        rotateBtn = button("↻ Putar") {
            rotation = (rotation + 90) % 360
            workingBitmap?.let { preview.setImageBitmap(transformImage(it, rotation, flipHorizontal, flipVertical)) }
        }
        flipHBtn = button("↔ Flip H") {
            flipHorizontal = !flipHorizontal
            workingBitmap?.let { preview.setImageBitmap(transformImage(it, rotation, flipHorizontal, flipVertical)) }
        }
        flipVBtn = button("↕ Flip V") {
            flipVertical = !flipVertical
            workingBitmap?.let { preview.setImageBitmap(transformImage(it, rotation, flipHorizontal, flipVertical)) }
        }
        listOf(rotateBtn, flipHBtn, flipVBtn).forEachIndexed { i, b ->
            styleAsSecondary(b)
            editRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(5) })
        }
        editRow.visibility = View.GONE
        content.addView(editRow)

        content.addView(button("Pilih Gambar") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, 1212)
        })

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var processBtn: Button
        processBtn = button("Proses Gambar") {
            val source = originalBitmap ?: run { toast("Pilih gambar terlebih dahulu"); return@button }
            resizeImageAdvanced(source, width, height, keepRatio, rotation, flipHorizontal, flipVertical,
                outputMime, quality.progress, preview, status) { file ->
                lastOutput = file
                processBtn.text = "Proses Lagi"
                downloadBtn.visibility = View.VISIBLE
                shareBtn.visibility = View.VISIBLE
                outputInfo.visibility = View.VISIBLE
                outputInfo.text = "Hasil: ${formatImageBytes(file.length())} • ${file.name}"
            }
        }
        processBtn.visibility = View.GONE

        downloadBtn = button("Download") {
            val file = lastOutput ?: run { toast("Belum ada hasil"); return@button }
            downloadImageToDownloads(file)
        }
        shareBtn = button("Bagikan") {
            val file = lastOutput ?: run { toast("Belum ada hasil"); return@button }
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = outputMime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Bagikan gambar"))
        }
        downloadBtn.visibility = View.GONE
        shareBtn.visibility = View.GONE
        actionRow.addView(processBtn, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(actionRow)

        val resultRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        resultRow.addView(downloadBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        resultRow.addView(shareBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        content.addView(resultRow)

        outputInfo = label("", 12f)
        outputInfo.visibility = View.GONE
        outputInfo.setPadding(dp(4), dp(6), dp(4), dp(8))
        content.addView(outputInfo)

        val info = label("", 13f)
        info.visibility = View.GONE
        content.addView(info)

        imageStudioResult = { uri ->
            selectedUri = uri
            toolThread {
                val loaded = runCatching {
                    val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: error("Gambar tidak bisa dibuka")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }.takeIf { it != null && it >= 0 } ?: -1L
                    Triple(bmp, bounds.outMimeType ?: "image/*", size)
                }
                runOnToolUiThread {
                    loaded.onSuccess { (bmp, mime, size) ->
                        originalBitmap = bmp
                        workingBitmap = bmp
                        rotation = 0; flipHorizontal = false; flipVertical = false
                        preview.setImageBitmap(bmp); preview.visibility = View.VISIBLE
                        width.setText(bmp.width.toString()); height.setText(bmp.height.toString())
                        width.visibility = View.VISIBLE; height.visibility = View.VISIBLE; ratioButton.visibility = View.VISIBLE
                        formatLabel.visibility = View.VISIBLE; formatRow.visibility = View.VISIBLE
                        qualityLabel.visibility = View.VISIBLE; quality.visibility = View.VISIBLE
                        editLabel.visibility = View.VISIBLE; editRow.visibility = View.VISIBLE
                        processBtn.visibility = View.VISIBLE
                        info.visibility = View.VISIBLE
                        outputInfo.visibility = View.GONE
                        downloadBtn.visibility = View.GONE; shareBtn.visibility = View.GONE
                        outputMime = when {
                            mime.contains("png", true) -> "image/png"
                            mime.contains("webp", true) -> "image/webp"
                            else -> "image/jpeg"
                        }
                        formatButtons.forEachIndexed { index, pair ->
                            if (pair.second == outputMime) styleAsPrimary(formatButtonList[index]) else styleAsSecondary(formatButtonList[index])
                        }
                        formatLabel.text = "Format output: ${outputMime.substringAfter('/') .uppercase(Locale.US)}"
                        val sizeText = if (size >= 0) formatImageBytes(size) else "-"
                        info.text = "Asli: ${bmp.width} × ${bmp.height}px • $sizeText • $mime"
                        status.text = "Gambar siap diproses"
                    }.onFailure { status.text = "Gagal membaca gambar: ${it.message}" }
                }
            }
        }
    }
internal fun MainActivity.transformImage(source: Bitmap, rotation: Int, flipH: Boolean, flipV: Boolean): Bitmap {
        val matrix = android.graphics.Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
internal fun MainActivity.resizeImageAdvanced(
        source: Bitmap?, widthView: EditText, heightView: EditText, keepRatio: Boolean,
        rotation: Int, flipH: Boolean, flipV: Boolean, mime: String, quality: Int,
        preview: ImageView, status: TextView, onSaved: (File) -> Unit
    ) {
        val bmp = source ?: run { toast("Pilih gambar terlebih dahulu"); return }
        var w = widthView.text.toString().toIntOrNull()?.coerceIn(1, 8000) ?: bmp.width
        var h = heightView.text.toString().toIntOrNull()?.coerceIn(1, 8000) ?: bmp.height
        if (keepRatio) {
            if (w != bmp.width) h = (bmp.height.toDouble() * w / bmp.width).roundToInt().coerceIn(1, 8000)
            else if (h != bmp.height) w = (bmp.width.toDouble() * h / bmp.height).roundToInt().coerceIn(1, 8000)
        }
        toolThread {
            val result = runCatching {
                val transformed = transformImage(bmp, rotation, flipH, flipV)
                val outBmp = Bitmap.createScaledBitmap(transformed, w, h, true)
                val dir = File(filesDir, "image_exports").apply { mkdirs() }
                val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                val f = File(dir, "gitls_image_${System.currentTimeMillis()}.$ext")
                FileOutputStream(f).use { out ->
                    val format = when (mime) {
                        "image/png" -> Bitmap.CompressFormat.PNG
                        "image/webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                        else -> Bitmap.CompressFormat.JPEG
                    }
                    outBmp.compress(format, quality.coerceIn(1,100), out)
                }
                Pair(outBmp, f)
            }
            runOnToolUiThread {
                result.onSuccess { (outBmp, f) ->
                    preview.setImageBitmap(outBmp)
                    status.text = "Selesai • ${outBmp.width}×${outBmp.height}px • ${formatImageBytes(f.length())}"
                    onSaved(f)
                }.onFailure { status.text = "Gagal memproses: ${it.message}" }
            }
        }
    }
internal fun MainActivity.downloadImageToDownloads(file: File) {
        if (!file.exists()) { toast("File hasil tidak ditemukan"); return }
        // OPEN-11: Android 7–9 butuh izin tulis untuk Downloads publik. Belum ada → minta, lalu
        // pengguna menekan Download lagi (pesan dari onRequestPermissionsResult).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !ensureLegacyStorageAccess()) return
        toolThread {
            val result = runCatching {
                val mime = when (file.extension.lowercase(Locale.US)) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    else -> "image/jpeg"
                }
                val safeName = "GITLS_${System.currentTimeMillis()}.${file.extension.lowercase(Locale.US)}"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GITLS")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Tidak bisa membuat file Downloads")
                    try {
                        contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                            ?: error("Tidak bisa menulis file")
                        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        contentResolver.delete(uri, null, null)
                        throw e
                    }
                    "Downloads/GITLS/$safeName"
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
                    val target = File(dir, safeName)
                    file.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                    android.media.MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf(mime), null)
                    target.absolutePath
                }
            }
            runOnToolUiThread {
                result.onSuccess { path -> toast("Berhasil disimpan ke $path") }
                    .onFailure { toast("Download gagal: ${it.message}") }
            }
        }
    }
internal fun MainActivity.formatImageBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }

internal fun MainActivity.toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    @Volatile internal var exportBusy = false

internal fun MainActivity.webProjectBuilder() {
        clearPage("Web Project Builder")
        content.addView(label("Web Project Builder", 22f, true))
        content.addView(subLabel("Modul builder dipulihkan sebagai stub setelah ekstraksi Batch 2. Bangun ulang UI di batch berikutnya jika diperlukan.", 12f))
        content.addView(button("Buka Editor") { editor(null) })
        content.addView(button("Buka HTML Hosting") { openTool("webhostwifi") })
    }
